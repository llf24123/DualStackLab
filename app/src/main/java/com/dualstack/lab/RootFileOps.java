package com.dualstack.lab;

import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.*;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * app_process entry point. Only launch the copy in our installed APK; stdout is framed binary.
 * No shell filesystem operations, hidden Android methods, JNI, or app-namespace inode guesses.
 *
 * O_PATH below is a Linux open(2) ABI flag, passed to the PUBLIC Os.open API, not a hidden SDK
 * symbol. It pins and classifies a leaf without opening a device/FIFO. chmod then reopens ONLY
 * that owned proc-FD, never the untrusted leaf, and verifies fstat again before fchmod.
 *
 * Public Os has neither renameat2(RENAME_NOREPLACE) nor conditional unlink. Hardlink publication
 * is exclusive; the fallback reservation + rename and final check + remove still have a final
 * name-level race against writers of the SAME pinned directory. They do not follow symlinks or
 * escape through renamed ancestors. Do not describe these checks as atomic compare-and-delete.
 */
public final class RootFileOps {
    static final int VERSION = 2, MAX_REQUEST = 8 * 1024 * 1024, MAX_EVENT = 256 * 1024, PAYLOAD_LIMIT = 128 * 1024;
    static final int READY = 1, PREPARED = 2, STEP = 3, OK = 4, ERROR = 5, CANCELLED = 6, READ_PAYLOAD = 7;
    static final int CREATE = 1, RENAME = 2, MOVE = 3, DELETE = 4, CHMOD = 5, DELETE_EMPTY = 6, WRITE_EMPTY = 7;
    static final int O_PATH = 010000000; // Linux UAPI, stable on all Android architectures.
    private static final int MAX_ITEMS = 16384, MAX_DEPTH = 256;
    // A shorter self-deadline than the client's 30s wait prevents a surviving su ART child.
    private static final long STALL_MS = 29000;

    static final class Spec {
        final String path, identity, parentIdentity, directoryIdentity;
        final boolean follows;
        Spec(String path, String identity, String parentIdentity, String directoryIdentity, boolean follows) {
            this.path = path; this.identity = identity; this.parentIdentity = parentIdentity;
            this.directoryIdentity = directoryIdentity; this.follows = follows;
        }
        void write(DataOutputStream out) throws IOException {
            out.writeUTF(path); nullable(out, identity); nullable(out, parentIdentity); nullable(out, directoryIdentity); out.writeBoolean(follows);
        }
        static Spec read(DataInputStream in) throws IOException {
            return new Spec(in.readUTF(), nullable(in), nullable(in), nullable(in), in.readBoolean());
        }
    }

    static final class Request {
        final int operation, mode;
        final Spec source, parent;
        final String name;
        final boolean directory;
        final List<Spec> items;
        final long size;
        private Request(int operation, Spec source, Spec parent, String name, boolean directory, int mode, List<Spec> items) {
            this(operation, source, parent, name, directory, mode, items, -1);
        }
        private Request(int operation, Spec source, Spec parent, String name, boolean directory, int mode, List<Spec> items, long size) {
            this.operation = operation; this.source = source; this.parent = parent; this.name = name;
            this.directory = directory; this.mode = mode; this.items = items; this.size = size;
        }
        static Request create(Spec parent, String name, boolean directory) { return new Request(CREATE, null, parent, name, directory, 0, List.of()); }
        static Request rename(Spec source, Spec parent, String name, boolean move) { return new Request(move ? MOVE : RENAME, source, parent, name, false, 0, List.of()); }
        static Request delete(Collection<Spec> items) { return new Request(DELETE, null, null, "", false, 0, new ArrayList<>(items)); }
        static Request deleteEmpty(Collection<Spec> items) { return new Request(DELETE_EMPTY, null, null, "", false, 0, new ArrayList<>(items)); }
        static Request chmod(Spec source, int mode) { return new Request(CHMOD, source, null, "", false, mode, List.of()); }
        static Request writeEmpty(Spec target, long size) { return new Request(WRITE_EMPTY, target, null, "", false, 0, List.of(), size); }
        byte[] encode() throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(VERSION); out.writeInt(operation);
                switch (operation) {
                    case CREATE: parent.write(out); out.writeUTF(name); out.writeBoolean(directory); break;
                    case RENAME: case MOVE: source.write(out); parent.write(out); out.writeUTF(name); break;
                    case DELETE: case DELETE_EMPTY:
                        if (items.size() > MAX_ITEMS) throw new IOException("删除批次超过 16384 项");
                        out.writeInt(items.size()); for (Spec spec : items) spec.write(out); break;
                    case CHMOD: source.write(out); out.writeInt(mode); break;
                    case WRITE_EMPTY: source.write(out); out.writeLong(size); break;
                    default: throw new IOException("未知操作");
                }
            }
            if (bytes.size() > MAX_REQUEST) throw new IOException("ROOT 请求超过 8 MiB");
            return bytes.toByteArray();
        }
        static Request decode(byte[] bytes) throws IOException {
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
                if (in.readInt() != VERSION) throw new IOException("ROOT 协议版本不一致");
                int operation = in.readInt(); Request result;
                switch (operation) {
                    case CREATE: result = create(Spec.read(in), in.readUTF(), in.readBoolean()); break;
                    case RENAME: case MOVE: result = rename(Spec.read(in), Spec.read(in), in.readUTF(), operation == MOVE); break;
                    case CHMOD: result = chmod(Spec.read(in), in.readInt()); break;
                    case WRITE_EMPTY: result = writeEmpty(Spec.read(in), in.readLong()); break;
                    case DELETE: case DELETE_EMPTY:
                        int count = in.readInt(); if (count < 0 || count > MAX_ITEMS) throw new IOException("无效批次数量");
                        List<Spec> items = new ArrayList<>(count);
                        for (int i = 0; i < count; i++) items.add(Spec.read(in));
                        result = operation == DELETE_EMPTY ? deleteEmpty(items) : delete(items); break;
                    default: throw new IOException("未知操作");
                }
                if (in.available() != 0) throw new IOException("请求有多余数据");
                return result;
            }
        }
    }

    static final class Result {
        final String path, identity, parentIdentity, directoryIdentity;
        final int mode, uid, gid;
        final long size, modified;
        Result(String path, StructStat stat, String parentIdentity) {
            this(path, id(stat), parentIdentity, OsConstants.S_ISDIR(stat.st_mode) ? id(stat) : null,
                    stat.st_mode, stat.st_uid, stat.st_gid, stat.st_size, stat.st_mtime * 1000L);
        }
        private Result(String path, String identity, String parentIdentity, String directoryIdentity,
                       int mode, int uid, int gid, long size, long modified) {
            this.path = path; this.identity = identity; this.parentIdentity = parentIdentity; this.directoryIdentity = directoryIdentity;
            this.mode = mode; this.uid = uid; this.gid = gid; this.size = size; this.modified = modified;
        }
        void write(DataOutputStream out) throws IOException {
            out.writeUTF(path); out.writeUTF(identity); out.writeUTF(parentIdentity); nullable(out, directoryIdentity);
            out.writeInt(mode); out.writeInt(uid); out.writeInt(gid); out.writeLong(size); out.writeLong(modified);
        }
        static Result read(DataInputStream in) throws IOException {
            return new Result(in.readUTF(), in.readUTF(), in.readUTF(), nullable(in), in.readInt(), in.readInt(), in.readInt(), in.readLong(), in.readLong());
        }
    }

    /** Each event is one length-delimited frame; PREPARED is informational, STEP alone consumes ACK. */
    static final class Event {
        final int code, pid, uid;
        final String text;
        final Result result;
        Event(int code, String text, Result result, int pid, int uid) { this.code = code; this.text = text; this.result = result; this.pid = pid; this.uid = uid; }
        static Event read(DataInputStream input) throws IOException {
            byte[] frame = frame(input, MAX_EVENT, false);
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(frame))) {
                int code = in.readInt(), pid = 0, uid = -1; String text = ""; Result result = null;
                switch (code) {
                    case READY:
                        if (in.readInt() != VERSION) throw new IOException("ROOT 协议版本不一致");
                        pid = in.readInt(); uid = in.readInt(); break;
                    case PREPARED: case STEP: case ERROR: text = in.readUTF(); break;
                    case OK: if (in.readBoolean()) result = Result.read(in); break;
                    case CANCELLED: case READ_PAYLOAD: break;
                    default: throw new IOException("未知 ROOT 响应");
                }
                if (in.available() != 0) throw new IOException("ROOT 响应边界损坏");
                return new Event(code, text, result, pid, uid);
            }
        }
    }

    public static void main(String[] args) {
        AtomicLong touched = new AtomicLong(SystemClock.elapsedRealtime());
        Thread watchdog = new Thread(() -> {
            while (true) {
                if (SystemClock.elapsedRealtime() - touched.get() >= STALL_MS) Runtime.getRuntime().halt(124);
                try { Thread.sleep(1000); } catch (InterruptedException ignored) { return; }
            }
        }, "root-mutations-watchdog");
        watchdog.setDaemon(true); watchdog.start();
        try {
            if (args.length != 0) throw new IOException("ROOT helper 不接受路径参数");
            Os.umask(0022);
            serve(new DataInputStream(new BufferedInputStream(System.in)), new DataOutputStream(new BufferedOutputStream(System.out)), touched);
        } catch (Throwable error) {
            System.err.println("ROOT mutations stopped: " + error.getClass().getSimpleName() + ": " + error.getMessage());
            Runtime.getRuntime().halt(125);
        } finally { watchdog.interrupt(); }
    }

    private static void serve(DataInputStream input, DataOutputStream output, AtomicLong touched) throws IOException {
        Channel channel = new Channel(input, output, touched);
        channel.send(READY, "", null);
        while (true) {
            // Read the ENTIRE request before preparation can fail. Never leave a writer blocked on a pipe.
            byte[] bytes = frame(input, MAX_REQUEST, true);
            if (bytes == null) return;
            touched.set(SystemClock.elapsedRealtime());
            try {
                Result result;
                try (Scope scope = new Scope()) {
                    Request request = Request.decode(bytes);
                    Prepared prepared = new Prepared(request, scope);
                    channel.send(PREPARED, "fixed", null);
                    result = prepared.run(channel);
                } // Close every request FD BEFORE its one terminal frame; a close error cannot trail OK.
                channel.send(OK, "", result);
            } catch (Cancelled cancelled) {
                channel.send(CANCELLED, "", null);
            } catch (FatalCredentials fatal) {
                channel.send(ERROR, fatal.getMessage(), null);
                throw fatal; // Caller MUST terminate this VM; never process another request.
            } catch (ErrnoException error) {
                channel.send(ERROR, error.getMessage(), null);
            } catch (IOException | RuntimeException error) {
                if (error instanceof EOFException) throw (EOFException) error;
                channel.send(ERROR, error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(), null);
            }
        }
    }

    private interface ProgressGate { void step(String name) throws IOException; }

    /** Local shared-storage entry points never enter chmod or credential switching paths. */
    static Result createShared(Request request) throws IOException {
        if (request.operation != CREATE) throw new IOException("本地创建请求类型错误");
        try (Scope scope = new Scope()) {
            return new Prepared(request, scope).create(Tasks.Progress::step, true);
        } catch (ErrnoException error) { throw new IOException(error); }
    }
    static Result moveShared(Request request) throws IOException {
        if (request.operation != MOVE) throw new IOException("本地移动请求类型错误");
        try (Scope scope = new Scope()) {
            return new Prepared(request, scope).run(Tasks.Progress::step);
        } catch (ErrnoException error) { throw new IOException(error); }
    }

    private static final class Channel implements ProgressGate {
        final DataInputStream input;
        final DataOutputStream output;
        final AtomicLong touched;
        Channel(DataInputStream input, DataOutputStream output, AtomicLong touched) { this.input = input; this.output = output; this.touched = touched; }
        void send(int code, String text, Result result) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(code);
                if (code == READY) { out.writeInt(VERSION); out.writeInt(Os.getpid()); out.writeInt(Os.getuid()); }
                else if (code == PREPARED || code == STEP || code == ERROR) out.writeUTF(shortText(text));
                else if (code == OK) { out.writeBoolean(result != null); if (result != null) result.write(out); }
            }
            touched.set(SystemClock.elapsedRealtime());
            output.writeInt(bytes.size()); bytes.writeTo(output); output.flush();
        }
        public void step(String name) throws IOException {
            send(STEP, name, null);
            boolean proceed = input.readBoolean();
            touched.set(SystemClock.elapsedRealtime());
            if (!proceed) throw new Cancelled();
        }
        int readPayload(byte[] buffer) throws IOException {
            send(READ_PAYLOAD, "", null);
            int count = input.readInt();
            touched.set(SystemClock.elapsedRealtime());
            if (count == -2) throw new Cancelled();
            if (count == -1) return -1;
            if (count <= 0 || count > buffer.length) throw new EOFException("ROOT 数据块长度无效");
            input.readFully(buffer, 0, count);
            return count;
        }
    }

    private static final class Prepared {
        final Request request;
        final Scope scope;
        Handle parent, writable;
        Entry source;
        final List<Entry> items = new ArrayList<>();
        final Set<String> critical;
        Prepared(Request request, Scope scope) throws IOException, ErrnoException {
            this.request = request; this.scope = scope;
            critical = request.operation == CREATE || request.operation == WRITE_EMPTY ? Collections.emptySet() : criticalIdentities();
            switch (request.operation) {
                case CREATE:
                    checkName(request.name); parent = pinDirectory(request.parent, scope);
                    absent(parent.child(request.name)); break;
                case RENAME: case MOVE:
                    checkName(request.name); rejectProtected(request.source.path);
                    source = entry(request.source, scope); rejectCritical(source, critical);
                    parent = pinDirectory(request.parent, scope);
                    // A different-device MOVE is only a verdict: not even a destination conflict
                    // or subtree lookup changes it into a copying/mutating fast path.
                    if (request.operation != MOVE || source.stat.st_dev == parent.stat.st_dev) {
                        if (source.directory != null) rejectDescendant(source.directory, parent, scope);
                        absent(parent.child(request.name));
                    }
                    break;
                case DELETE: case DELETE_EMPTY:
                    // Lexical preflight of EVERY item happens before opening or removing any item.
                    Set<String> paths = new HashSet<>();
                    for (Spec spec : request.items) {
                        String path = normalized(spec.path); rejectProtected(path);
                        if (!paths.add(path)) throw new IOException("删除批次包含重复路径");
                    }
                    for (String path : paths) {
                        for (File ancestor = new File(path).getParentFile(); ancestor != null; ancestor = ancestor.getParentFile())
                            if (paths.contains(ancestor.getPath())) throw new IOException("删除批次包含重叠目录");
                    }
                    Set<String> entries = new HashSet<>();
                    for (Spec spec : request.items) {
                        Entry entry = entry(spec, scope); rejectCritical(entry, critical);
                        if (!entries.add(id(entry.parent.stat) + "/" + entry.name)) throw new IOException("删除批次包含同一目录项别名");
                        items.add(entry);
                    }
                    rejectBatchOverlaps(items);
                    break;
                case CHMOD:
                    if (request.mode < 0 || request.mode > 07777) throw new IOException("权限值超出 0000–7777");
                    source = entry(request.source, scope);
                    if (!regularOrDirectory(source.stat)) throw new IOException("chmod 仅支持普通文件和目录，不支持链接、设备或 FIFO");
                    // O_PATH|NOFOLLOW NEVER opens a device even if the leaf is replaced after lstat.
                    Handle path = scope.open(source.path(), O_PATH | OsConstants.O_NOFOLLOW | OsConstants.O_NONBLOCK);
                    requireSame(source.stat, path.stat);
                    if (!regularOrDirectory(path.stat)) throw new IOException("chmod 目标类型已改变");
                    // Following this magic link is intentional: it names OUR pinned fd, not a user symlink.
                    writable = scope.open(path.proc(), OsConstants.O_RDONLY | OsConstants.O_NONBLOCK);
                    requireSame(path.stat, writable.stat);
                    if (!regularOrDirectory(writable.stat)) throw new IOException("chmod 目标类型已改变");
                    break;
                case WRITE_EMPTY:
                    if (request.size < -1 || request.source.identity == null || request.source.parentIdentity == null || request.source.follows)
                        throw new IOException("新文件写入缺少固定身份或长度无效");
                    source = entry(request.source, scope);
                    requireEmpty(source.stat);
                    Handle empty = scope.open(source.path(), O_PATH | OsConstants.O_NOFOLLOW | OsConstants.O_NONBLOCK);
                    requireSame(source.stat, empty.stat); requireEmpty(empty.stat);
                    // Reopen only our classified FD. A replaced leaf must never open a device/FIFO.
                    writable = scope.open(empty.proc(), OsConstants.O_WRONLY | OsConstants.O_NONBLOCK);
                    requireSame(source.stat, writable.stat); requireEmpty(writable.stat); source.requireCurrent();
                    break;
                default: throw new IOException("未知操作");
            }
        }
        Result run(ProgressGate channel) throws IOException, ErrnoException {
            switch (request.operation) {
                case CREATE: return create(channel);
                case WRITE_EMPTY:
                    if (!(channel instanceof Channel)) throw new IOException("新文件写入需要数据通道");
                    return writeEmpty((Channel) channel);
                case CHMOD:
                    channel.step(source.name); source.requireCurrent();
                    Os.fchmod(writable.fd(), request.mode);
                    StructStat actual = Os.fstat(writable.fd()); requireSame(source.stat, actual);
                    if ((actual.st_mode & 07777) != request.mode)
                        throw new IOException("文件系统没有应用此权限（实际 " + Integer.toOctalString(actual.st_mode & 07777) + "）；不模拟 FUSE chmod 成功");
                    source.requireCurrent(); return new Result(request.source.path, actual, id(source.parent.stat));
                case RENAME: case MOVE: return rename(channel);
                case DELETE:
                    for (Entry item : items) remove(item, channel, 0, critical);
                    return null;
                case DELETE_EMPTY:
                    for (Entry item : items) {
                        item.requireCurrent(); channel.step(item.name); item.requireCurrent();
                        // Deliberately no traversal: a foreign child makes rmdir fail, never disappear.
                        Os.remove(item.path());
                    }
                    return null;
                default: throw new IOException("未知操作");
            }
        }
        private Result writeEmpty(Channel channel) throws IOException, ErrnoException {
            channel.step(source.name); source.requireCurrent(); requireEmpty(Os.fstat(writable.fd()));
            byte[] buffer = new byte[PAYLOAD_LIMIT];
            long total = 0; int count;
            while ((count = channel.readPayload(buffer)) != -1) {
                if (request.size >= 0 && count > request.size - total) throw new IOException("来源超过声明长度");
                int offset = 0;
                while (offset < count) {
                    int written = Os.write(writable.fd(), buffer, offset, count - offset);
                    if (written <= 0) throw new IOException("目标写入未前进");
                    offset += written;
                }
                total += count;
            }
            if (request.size >= 0 && total != request.size) throw new IOException("来源长度不完整");
            channel.step("sync:" + source.name); source.requireCurrent();
            Os.fsync(writable.fd());
            StructStat actual = Os.fstat(writable.fd()); requireSame(source.stat, actual);
            if (actual.st_size != total || actual.st_nlink != 1 || !OsConstants.S_ISREG(actual.st_mode)
                    || actual.st_mode != source.stat.st_mode || actual.st_uid != source.stat.st_uid || actual.st_gid != source.stat.st_gid)
                throw new IOException("写入期间新文件的长度、类型或元数据变化");
            source.requireCurrent();
            return new Result(request.source.path, actual, id(source.parent.stat));
        }
        private static void requireEmpty(StructStat stat) throws IOException {
            if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size != 0 || stat.st_nlink != 1)
                throw new IOException("流式复制仅支持本次创建的空普通文件");
        }
        private Result create(ProgressGate channel) throws IOException, ErrnoException { return create(channel, false); }
        private Result create(ProgressGate channel, boolean appIdentity) throws IOException, ErrnoException {
            channel.step(request.name);
            String destination = parent.child(request.name);
            if (request.directory) {
                if (appIdentity) Os.mkdir(destination, 0755);
                else createDirectoryAsOwner(parent, request.name);
                // No path-based chown, not even on a supposedly fresh mkdir result.
                try (Scope opened = new Scope()) {
                    Handle directory = opened.open(destination, O_PATH | OsConstants.O_NOFOLLOW | OsConstants.O_NONBLOCK);
                    StructStat actual = directory.stat;
                    if (!OsConstants.S_ISDIR(actual.st_mode) || !appIdentity && (actual.st_uid != parent.stat.st_uid || actual.st_gid != parent.stat.st_gid))
                        throw new IOException("新目录的 owner/type 不符合父目录映射；未对路径执行 chown/chmod");
                    // mkdir's mode is a request, not an explicit chmod contract. FUSE/default ACLs
                    // may map 0755 to e.g. 2770; return the actual mode rather than reject or fake it.
                    requireEntry(destination, actual);
                    return new Result(join(request.parent.path, request.name), actual, id(parent.stat));
                }
            }
            // The returned creation fd is the ONLY fd ever used to correct ownership.
            // Creation requests defaults, unlike chmod: preserve the filesystem's real mode mapping.
            StructStat created = null;
            boolean completed = false;
            Throwable failure = null;
            try {
                Result result;
                try (Scope opened = new Scope()) {
                    Handle file = opened.open(destination, OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_EXCL | OsConstants.O_NOFOLLOW, 0644);
                    created = file.stat; // identity comes from the creating FD, never a mutable path
                    if (!OsConstants.S_ISREG(file.stat.st_mode)) throw new IOException("新文件不是普通文件");
                    channel.step("created:" + request.name);
                    StructStat born = Os.fstat(file.fd()); requireSame(file.stat, born);
                    if (!appIdentity && (born.st_uid != parent.stat.st_uid || born.st_gid != parent.stat.st_gid))
                        Os.fchown(file.fd(), parent.stat.st_uid, parent.stat.st_gid);
                    StructStat actual = Os.fstat(file.fd()); requireSame(file.stat, actual);
                    if (!OsConstants.S_ISREG(actual.st_mode) || !appIdentity && (actual.st_uid != parent.stat.st_uid || actual.st_gid != parent.stat.st_gid))
                        throw new IOException("文件系统没有应用新文件的父目录 owner 映射");
                    requireEntry(destination, actual);
                    result = new Result(join(request.parent.path, request.name), actual, id(parent.stat));
                }
                completed = true;
                return result;
            } catch (IOException | ErrnoException | RuntimeException | Error error) {
                failure = error; throw error;
            } finally {
                if (!completed && created != null) try { removeCreatedEmpty(destination, created); }
                catch (ErrnoException cleanup) {
                    // Preserve CANCELLED / the original failure and therefore the protocol boundary.
                    if (failure != null) failure.addSuppressed(cleanup); else throw cleanup;
                }
            }
        }
        private Result rename(ProgressGate channel) throws IOException, ErrnoException {
            // This fast path NEVER copies, not even when rename/link later returns EXDEV.
            if (source.stat.st_dev != parent.stat.st_dev) {
                if (request.operation == MOVE) return null;
                throw new IOException("rename 不能跨文件系统");
            }
            channel.step(source.name); source.requireCurrent();
            String destination = parent.child(request.name);
            absent(destination);
            boolean hardlinked = false;
            if (OsConstants.S_ISREG(source.stat.st_mode)) {
                try { Os.link(source.path(), destination); hardlinked = true; }
                catch (ErrnoException error) {
                    if (error.errno == OsConstants.EXDEV && request.operation == MOVE) return null;
                    if (error.errno != OsConstants.EPERM && error.errno != OsConstants.EACCES && error.errno != OsConstants.EOPNOTSUPP
                            && error.errno != OsConstants.ENOSYS && error.errno != OsConstants.EMLINK) throw error;
                }
            }
            if (hardlinked) {
                requireEntry(destination, source.stat);
                // Cancellation after publication keeps BOTH names; never pretend to roll back a move.
                channel.step("unlink:" + source.name);
                source.requireCurrent(); Os.remove(source.path());
                requireEntry(destination, source.stat);
                return resultAt(destination);
            }
            // Directory/symlink/FUSE fallback: reserve the EXACT final name, never interpret it as a directory.
            StructStat reservation = reserve(destination, source.directory != null);
            boolean published = false;
            try {
                source.requireCurrent(); requireEntry(destination, reservation);
                try { Os.rename(source.path(), destination); }
                catch (ErrnoException error) {
                    // A reservation already existed: do NOT report the zero-mutation null verdict.
                    // Same-dev EXDEV (e.g. distinct bind mounts) is an explicit safe failure instead.
                    throw error;
                }
                published = true;
                requireEntry(destination, source.stat);
                return resultAt(destination);
            } finally {
                if (!published) removeReservation(destination, reservation);
            }
        }
        private Result resultAt(String destination) throws IOException, ErrnoException {
            StructStat actual = Os.lstat(destination); requireSame(source.stat, actual);
            return new Result(join(request.parent.path, request.name), actual, id(parent.stat));
        }
    }

    private static final class Handle implements AutoCloseable {
        final ParcelFileDescriptor descriptor;
        final StructStat stat;
        Handle(String path, int flags, int mode) throws ErrnoException, IOException {
            FileDescriptor raw = Os.open(path, flags, mode);
            ParcelFileDescriptor owned = null;
            try { owned = ParcelFileDescriptor.dup(raw); }
            finally {
                try { Os.close(raw); }
                catch (ErrnoException error) { if (owned != null) owned.close(); throw error; }
            }
            descriptor = owned;
            try { stat = Os.fstat(fd()); }
            catch (ErrnoException error) { descriptor.close(); throw error; }
        }
        FileDescriptor fd() { return descriptor.getFileDescriptor(); }
        String proc() { return "/proc/self/fd/" + descriptor.getFd(); }
        String child(String name) { return proc() + "/" + name; }
        @Override public void close() throws IOException { descriptor.close(); }
    }
    private static final class Scope implements AutoCloseable {
        final List<Handle> handles = new ArrayList<>();
        final Map<String, Handle> directories = new HashMap<>();
        Handle open(String path, int flags) throws IOException, ErrnoException { return open(path, flags, 0); }
        Handle open(String path, int flags, int mode) throws IOException, ErrnoException {
            Handle handle = new Handle(path, flags, mode); handles.add(handle); return handle;
        }
        @Override public void close() throws IOException {
            IOException failure = null;
            for (int i = handles.size() - 1; i >= 0; i--) try { handles.get(i).close(); }
            catch (IOException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            handles.clear(); directories.clear(); if (failure != null) throw failure;
        }
    }
    private static final class Entry {
        final Handle parent, directory;
        final String name;
        final StructStat stat;
        Entry(Handle parent, String name, StructStat stat, Handle directory) { this.parent = parent; this.name = name; this.stat = stat; this.directory = directory; }
        String path() { return parent.child(name); }
        void requireCurrent() throws IOException, ErrnoException { requireEntry(path(), stat); }
    }

    private static Handle rawDirectory(String path, String guard, Scope scope) throws IOException, ErrnoException {
        Handle fixed = scope.directories.get(path);
        if (fixed != null) { requireGuard(guard, fixed.stat); return fixed; }
        StructStat snapshot = Os.stat(path); requireGuard(guard, snapshot);
        Handle directory = scope.open(path, O_PATH | OsConstants.O_NONBLOCK);
        requireSame(snapshot, directory.stat);
        if (!OsConstants.S_ISDIR(directory.stat.st_mode)) throw new IOException("父路径不是目录");
        scope.directories.put(path, directory);
        return directory;
    }
    private static Handle pinDirectory(Spec spec, Scope scope) throws IOException, ErrnoException {
        String path = normalized(spec.path);
        if (path.equals("/")) {
            Handle root = rawDirectory(path, spec.directoryIdentity, scope);
            requireGuard(spec.identity, root.stat);
            if (spec.parentIdentity != null) requireGuard(spec.parentIdentity, root.stat);
            return root;
        }
        File file = new File(path);
        Handle ancestor = rawDirectory(file.getParent(), spec.parentIdentity, scope);
        String anchored = ancestor.child(file.getName());
        StructStat entry = Os.lstat(anchored);
        StructStat compared = compareGuard(spec, anchored, entry);
        StructStat target = !OsConstants.S_ISLNK(entry.st_mode) || spec.follows ? compared : Os.stat(anchored);
        requireGuard(spec.directoryIdentity, target);
        Handle directory = scope.open(anchored, O_PATH | OsConstants.O_NONBLOCK);
        requireSame(target, directory.stat);
        if (spec.follows) requireGuard(spec.identity, directory.stat);
        requireEntry(anchored, entry); // retain a no-follow link guard across the FD open as well
        if (!OsConstants.S_ISDIR(directory.stat.st_mode)) throw new IOException("目标父路径不是目录");
        return directory;
    }
    private static Entry entry(Spec spec, Scope scope) throws IOException, ErrnoException {
        String path = normalized(spec.path); File file = new File(path);
        if (path.equals("/")) throw new IOException("不能操作文件系统根目录");
        Handle parent = rawDirectory(file.getParent(), spec.parentIdentity, scope);
        return child(parent, file.getName(), spec, scope);
    }
    private static Entry child(Handle parent, String name, Spec spec, Scope scope) throws IOException, ErrnoException {
        checkName(name); String path = parent.child(name);
        StructStat before = Os.lstat(path);
        if (spec != null) compareGuard(spec, path, before);
        Handle directory = null;
        if (OsConstants.S_ISDIR(before.st_mode)) {
            directory = scope.open(path, O_PATH | OsConstants.O_NOFOLLOW | OsConstants.O_NONBLOCK);
            requireSame(before, directory.stat);
            if (!OsConstants.S_ISDIR(directory.stat.st_mode)) throw new IOException("目录项已被替换");
            requireEntry(path, before);
        }
        return new Entry(parent, name, before, directory);
    }
    private static StructStat compareGuard(Spec spec, String path, StructStat entry) throws IOException, ErrnoException {
        StructStat compared = spec.follows && OsConstants.S_ISLNK(entry.st_mode) ? Os.stat(path) : entry;
        requireGuard(spec.identity, compared);
        if (spec.directoryIdentity != null) {
            StructStat directory = OsConstants.S_ISLNK(entry.st_mode) && !spec.follows ? Os.stat(path) : compared;
            if (!OsConstants.S_ISDIR(directory.st_mode)) throw new IOException("观察到的目录已不再是目录");
            requireGuard(spec.directoryIdentity, directory);
        }
        // Pin no-follow identity as well. Later mutation only touches this directory entry, never its target.
        requireEntry(path, entry);
        return compared;
    }
    private static void remove(Entry entry, ProgressGate channel, int depth, Set<String> critical) throws IOException, ErrnoException {
        if (depth > MAX_DEPTH) throw new IOException("递归目录超过 256 层，已安全停止");
        rejectCritical(entry, critical); entry.requireCurrent();
        channel.step(entry.name); entry.requireCurrent();
        if (entry.directory != null) {
            // The traversal stream and every child lookup are relative to an already-open directory FD.
            try (DirectoryStream<Path> children = Files.newDirectoryStream(new File(entry.directory.proc() + "/.").toPath())) {
                for (Path path : children) {
                    try (Scope childScope = new Scope()) {
                        Entry child = child(entry.directory, path.getFileName().toString(), null, childScope);
                        if (child.directory != null && child.stat.st_dev != entry.stat.st_dev)
                            throw new IOException("递归删除拒绝进入另一个文件系统的挂载点");
                        remove(child, channel, depth + 1, critical);
                    }
                }
            }
            // A second gate matters for cancellation while a large directory is being enumerated.
            channel.step("rmdir:" + entry.name);
        }
        entry.requireCurrent();
        // Os.remove is public and dispatches unlink/rmdir without following a final symlink.
        Os.remove(entry.path());
    }
    private static void createDirectoryAsOwner(Handle parent, String name) throws IOException, ErrnoException {
        if ((parent.stat.st_mode & 0300) != 0300) throw new IOException("父目录 owner 没有创建权限，拒绝临时修改父目录权限");
        int uid = Os.geteuid(), gid = Os.getegid();
        if (uid != 0) {
            if (uid != parent.stat.st_uid) throw new IOException("创建目录需要父目录所有者身份");
            // Android cache directories inherit a separate cache GID through setgid.
            // The normal mkdir path preserves that mapping without a forbidden setegid syscall.
            Os.mkdir(parent.child(name), 0755); return;
        }
        if (uid == parent.stat.st_uid && gid == parent.stat.st_gid) {
            Os.mkdir(parent.child(name), 0755); return;
        }
        int dumpable = Os.prctl(OsConstants.PR_GET_DUMPABLE, 0, 0, 0, 0);
        if (dumpable != 0 && dumpable != 1) throw new IOException("无法保证恢复 helper dumpable 状态，拒绝切换凭据");
        boolean changedUid = false, changedGid = false;
        Throwable original = null;
        try {
            if (gid != parent.stat.st_gid) { Os.setegid(parent.stat.st_gid); changedGid = true; }
            if (uid != parent.stat.st_uid) { Os.seteuid(parent.stat.st_uid); changedUid = true; }
            if (Os.geteuid() != parent.stat.st_uid || Os.getegid() != parent.stat.st_gid) throw new IOException("无法切换为父目录 owner");
            // Credential changes can reset dumpability and make our OWN /proc/self/fd directory
            // root-owned/untraversable. Public prctl makes it traversable by the temporary euid.
            // Saved/real UID remain privileged, so same-UID outsiders still cannot ptrace us.
            // This isolated helper has no other filesystem worker and restores the original state.
            if (changedUid || changedGid) Os.prctl(OsConstants.PR_SET_DUMPABLE, 1, 0, 0, 0);
            Os.mkdir(parent.child(name), 0755);
        } catch (IOException | ErrnoException | RuntimeException error) { original = error; throw error; }
        finally {
            try {
                if (changedUid) Os.seteuid(uid);
                if (changedGid) Os.setegid(gid);
                Os.prctl(OsConstants.PR_SET_DUMPABLE, dumpable, 0, 0, 0);
                if (Os.geteuid() != uid || Os.getegid() != gid || Os.prctl(OsConstants.PR_GET_DUMPABLE, 0, 0, 0, 0) != dumpable)
                    throw new IOException("凭据恢复校验失败");
            } catch (Exception restore) {
                FatalCredentials fatal = new FatalCredentials("ROOT helper 凭据恢复失败，必须终止会话", restore);
                if (original != null) fatal.addSuppressed(original);
                throw fatal;
            }
        }
    }
    private static StructStat reserve(String destination, boolean directory) throws IOException, ErrnoException {
        if (directory) {
            Os.mkdir(destination, 0700);
            try (Scope scope = new Scope()) {
                Handle handle = scope.open(destination, O_PATH | OsConstants.O_NOFOLLOW | OsConstants.O_NONBLOCK);
                if (!OsConstants.S_ISDIR(handle.stat.st_mode))
                    throw new IOException("目标占位目录被替换，拒绝继续");
                // Shared storage may synthesize owner and 2770 even for root's mkdir(0700).
                // Pin/check the actual directory identity; never chown/chmod a replacement.
                requireEntry(destination, handle.stat); return handle.stat;
            }
        }
        try (Scope scope = new Scope()) {
            Handle handle = scope.open(destination, OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_EXCL | OsConstants.O_NOFOLLOW, 0600);
            requireEntry(destination, handle.stat); return handle.stat;
        }
    }
    private static void removeCreatedEmpty(String path, StructStat created) throws ErrnoException {
        StructStat current = lstatOrNull(path);
        if (current != null && OsConstants.S_ISREG(current.st_mode) && current.st_size == 0 && same(current, created))
            Os.remove(path);
        // Only the observed empty, matching no-follow entry is eligible. Captured FD identity
        // + last lstat is not conditional-unlink CAS: the final same-directory race remains.
    }
    private static void removeReservation(String path, StructStat owned) throws IOException, ErrnoException {
        StructStat current = lstatOrNull(path);
        if (current != null && same(current, owned)) Os.remove(path);
    }
    private static void rejectBatchOverlaps(List<Entry> items) throws IOException, ErrnoException {
        Set<String> selectedDirectories = new HashSet<>(), checkedAncestors = new HashSet<>();
        for (Entry entry : items) if (entry.directory != null) selectedDirectories.add(id(entry.stat));
        if (selectedDirectories.isEmpty()) return;
        for (Entry entry : items) try (Scope ancestors = new Scope()) {
            Handle current = entry.parent;
            for (int depth = 0; ; depth++) {
                if (depth > MAX_DEPTH) throw new IOException("删除批次祖先超过 256 层");
                if (selectedDirectories.contains(id(current.stat))) throw new IOException("删除批次包含重叠目录或目录别名");
                if (!checkedAncestors.add(id(current.stat))) break;
                Handle parent = rawDirectory(current.child(".."), null, ancestors);
                if (same(current.stat, parent.stat)) break;
                current = parent;
            }
        }
    }
    private static void rejectDescendant(Handle source, Handle destination, Scope scope) throws IOException, ErrnoException {
        // Compare actual ancestors by inode, not app-side canonical strings; includes aliased mount paths.
        Handle current = destination;
        for (int depth = 0; depth <= MAX_DEPTH; depth++) {
            if (same(source.stat, current.stat)) throw new IOException("不能将目录移动到自身或子目录");
            Handle parent = rawDirectory(current.child(".."), null, scope);
            if (same(current.stat, parent.stat)) return;
            current = parent;
        }
        throw new IOException("目标目录祖先超过 256 层");
    }
    private static Set<String> criticalIdentities() {
        Set<String> result = new HashSet<>();
        for (String path : new String[]{"/", "/data", "/data/data", "/data/user", "/data/user/0", "/data/user_de", "/data/user_de/0",
                "/storage", "/storage/emulated", "/storage/emulated/0", "/sdcard", "/storage/self/primary",
                "/storage/emulated/0/Android/data", "/storage/emulated/0/Android/obb",
                "/system", "/vendor", "/product", "/odm", "/apex", "/proc", "/sys", "/dev", "/mnt", "/data/local", "/data/local/tmp"}) {
            try { result.add(id(Os.stat(path))); } catch (ErrnoException ignored) { }
        }
        return result;
    }
    private static void rejectCritical(Entry entry, Set<String> critical) throws IOException, ErrnoException {
        String physicalParent = Os.readlink(entry.parent.proc());
        rejectProtected(join(physicalParent, entry.name));
        if (entry.directory != null && critical.contains(id(entry.stat))) throw new IOException("不能删除、移动或重命名关键根容器");
    }
    private static void rejectProtected(String path) throws IOException {
        String normalized = normalized(path);
        if (RootAccess.protectedStorageDirectory(new File(normalized)) || normalized.matches("/(?:system|vendor|product|odm|apex|proc|sys|dev|mnt)")
                || normalized.equals("/data/local") || normalized.equals("/data/local/tmp"))
            throw new IOException("不能删除、移动或重命名存储根目录及关键容器：" + normalized);
    }
    private static String normalized(String path) throws IOException {
        if (path == null || !path.startsWith("/") || path.indexOf('\0') >= 0 || path.length() > 4096) throw new IOException("无效绝对路径");
        for (String part : path.split("/")) if (part.equals(".") || part.equals("..")) throw new IOException("路径不能包含 . 或 ..");
        return new File(path).toPath().normalize().toString();
    }
    private static void checkName(String name) throws IOException {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..") || name.indexOf('/') >= 0 || name.indexOf('\0') >= 0)
            throw new IOException("无效文件名");
    }
    private static String join(String parent, String name) { return (parent.endsWith("/") ? parent : parent + "/") + name; }
    private static void absent(String path) throws IOException, ErrnoException { if (lstatOrNull(path) != null) throw new IOException("同名目录项已存在（含符号链接）"); }
    private static StructStat lstatOrNull(String path) throws ErrnoException {
        try { return Os.lstat(path); } catch (ErrnoException error) { if (error.errno == OsConstants.ENOENT) return null; throw error; }
    }
    private static void requireEntry(String path, StructStat owned) throws IOException, ErrnoException { requireSame(owned, Os.lstat(path)); }
    private static void requireSame(StructStat before, StructStat after) throws IOException { if (!same(before, after)) throw new IOException("目录项身份已变化，请刷新后重试"); }
    private static boolean same(StructStat a, StructStat b) { return a.st_dev == b.st_dev && a.st_ino == b.st_ino && (a.st_mode & OsConstants.S_IFMT) == (b.st_mode & OsConstants.S_IFMT); }
    private static String id(StructStat stat) { return stat.st_dev + ":" + stat.st_ino; }
    private static void requireGuard(String expected, StructStat actual) throws IOException {
        if (expected != null && !expected.equals(id(actual))) throw new IOException("观察到的 inode 已变化，请刷新后重试");
    }
    private static boolean regularOrDirectory(StructStat stat) { return OsConstants.S_ISREG(stat.st_mode) || OsConstants.S_ISDIR(stat.st_mode); }
    private static String shortText(String text) { if (text == null) return ""; return text.length() > 8000 ? text.substring(0, 8000) : text; }
    private static void nullable(DataOutputStream out, String value) throws IOException { out.writeBoolean(value != null); if (value != null) out.writeUTF(value); }
    private static String nullable(DataInputStream in) throws IOException { return in.readBoolean() ? in.readUTF() : null; }
    private static byte[] frame(DataInputStream in, int maximum, boolean allowEof) throws IOException {
        int first = in.read(); if (first == -1) { if (allowEof) return null; throw new EOFException("ROOT helper 已关闭"); }
        int size = (first << 24) | (in.readUnsignedByte() << 16) | (in.readUnsignedByte() << 8) | in.readUnsignedByte();
        if (size <= 0 || size > maximum) throw new IOException("ROOT 帧长度无效");
        byte[] bytes = new byte[size]; in.readFully(bytes); return bytes;
    }
    private static final class Cancelled extends IOException { }
    private static final class FatalCredentials extends IOException { FatalCredentials(String message, Throwable cause) { super(message, cause); } }
    private RootFileOps() { }
}
