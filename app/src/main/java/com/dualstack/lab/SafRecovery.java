package com.dualstack.lab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Durable recovery copies, not atomic SAF replacement or automatic rollback of external edits. */
final class SafRecovery {
    static final String BACKUP = "original.bin";
    static final String METADATA = "metadata.properties";
    static final String METADATA_TEMP = "metadata.tmp";

    static final String PREFERENCES = "SafRecovery";
    static final String ENABLED = "enabled", BACKUP_DIRECTORY = "backupDirectory";

    static final class Record {
        final File directory, backup, metadata;
        // Null means the payload is relative to its catalog record (including legacy v1 records).
        final File storageRoot;
        final Uri uri;
        final String name, originalSha256, payloadSha256, state;
        final long createdAt, originalSize, backupDevice, backupInode;
        final boolean writeStarted;
        private Record(File directory, File storageRoot, Uri uri, String name, long createdAt, long originalSize,
                       String originalSha256, String payloadSha256, String state) {
            this(directory, storageRoot, uri, name, createdAt, originalSize, originalSha256, payloadSha256, state, 0, 0);
        }
        private Record(File directory, File storageRoot, Uri uri, String name, long createdAt, long originalSize,
                       String originalSha256, String payloadSha256, String state, long backupDevice, long backupInode) {
            this.directory = directory; this.storageRoot = storageRoot;
            this.backup = new File(storageRoot == null ? directory : new File(storageRoot, directory.getName()), BACKUP);
            this.metadata = new File(directory, METADATA); this.uri = uri; this.name = name;
            this.createdAt = createdAt; this.originalSize = originalSize;
            this.originalSha256 = originalSha256; this.payloadSha256 = payloadSha256;
            this.state = state; this.writeStarted = "writing".equals(state);
            this.backupDevice = backupDevice; this.backupInode = backupInode;
        }
        private Record withOriginal(Digest original, String nextState) {
            return new Record(directory, storageRoot, uri, name, createdAt, original.size, original.sha256,
                    payloadSha256, nextState, backupDevice, backupInode);
        }
        private Record withIdentity(android.system.StructStat stat) {
            return new Record(directory, storageRoot, uri, name, createdAt, originalSize, originalSha256,
                    payloadSha256, state, stat.st_dev, stat.st_ino);
        }
    }

    record Entry(String id, String name, File backup, long createdAt, long size,
                 boolean recoverable, String problem, Record record) {}

    /** The provider may already have truncated the source, even when opening its output failed. */
    static final class RecoveryException extends IOException {
        final Record record;
        RecoveryException(Record record, Throwable cause) {
            super("SAF 保存或恢复失败，原件可能已截断或不完整。持久恢复备份保留在：\n"
                    + record.backup.getAbsolutePath() + "\n来源：" + record.uri
                    + "\n不会自动回滚；恢复会覆盖来源当前内容。\n原因：" + cause.getMessage(), cause);
            this.record = record;
        }
    }

    static File directory(Context context) { return new File(context.getFilesDir(), "saf-recovery"); }

    private static SharedPreferences preferences(Context context) {
        // Do not unwrap ContextWrapper: its private-files root can intentionally differ from the application.
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }
    static boolean enabled(Context context) { return preferences(context).getBoolean(ENABLED, true); }
    static void setEnabled(Context context, boolean enabled) {
        // UI-safe: do not wait for an in-flight backup/restore's IO lock or disk commit.
        preferences(context).edit().putBoolean(ENABLED, enabled).apply();
    }
    static File backupDirectory(Context context) {
        String path = preferences(context).getString(BACKUP_DIRECTORY, null);
        return path == null ? directory(context) : new File(path);
    }

    /** Worker-thread API. Only ordinary app-writable local paths; no root shell or fallback. */
    static synchronized void setBackupDirectory(Context context, File root) throws IOException {
        SharedPreferences.Editor editor = preferences(context).edit();
        if (root == null) editor.remove(BACKUP_DIRECTORY);
        else {
            File selected = resolveLocalRoot(root, true), catalog = catalog(context);
            if (selected.equals(catalog)) {
                File validated = validateLocalRoot(selected, true);
                privateDirectory(validated);
                syncDirectory(validated.getParentFile());
                editor.remove(BACKUP_DIRECTORY);
            }
            else {
                if (selected.toPath().startsWith(catalog.toPath()))
                    throw new IOException("自定义备份目录不能放在私有恢复索引内部");
                rejectRegisteredPayloadRoot(context, catalog, selected);
                editor.putString(BACKUP_DIRECTORY, validateLocalRoot(selected, true).getPath());
            }
        }
        if (!editor.commit()) throw new IOException("无法保存恢复备份目录设置");
    }

    private static void rejectRegisteredPayloadRoot(Context context, File catalog, File selected) throws IOException {
        if (!catalog.exists()) return;
        File[] operations = catalog.listFiles();
        if (operations == null) throw new IOException("无法检查恢复备份目录边界：" + catalog);
        for (File operation : operations) {
            Record existing;
            try { existing = load(context, operation); }
            catch (IOException | RuntimeException corrupt) { continue; }
            File payloadOperation = existing.backup.getParentFile();
            if (selected.toPath().startsWith(payloadOperation.toPath()))
                throw new IOException("自定义根不能是已登记备份的操作目录或其子目录：" + payloadOperation);
        }
    }

    private static File resolveLocalRoot(File root, boolean selected) throws IOException {
        if (!root.isAbsolute()) throw new IOException("备份目录必须是绝对本地路径：" + root);
        if (!root.toPath().normalize().equals(root.toPath())) throw new IOException("备份目录不能包含路径跳转：" + root);
        // Android exposes trusted storage through aliases such as /data/user/0. Resolve parents only
        // when selecting a root; the persisted canonical path must not subsequently turn into an alias.
        if (!selected) rejectLinks(root);
        if (Files.isSymbolicLink(root.toPath())) throw new IOException("备份根目录不能是符号链接：" + root);
        File canonical = root.getCanonicalFile();
        rejectLinks(canonical);
        return canonical;
    }

    private static File validateLocalRoot(File root, boolean create) throws IOException {
        root = resolveLocalRoot(root, create);
        if (create) Files.createDirectories(root.toPath());
        rejectLinks(root);
        if (!root.isDirectory() || !root.canRead() || !root.canWrite())
            throw new IOException("备份目录不是普通应用可读写的本地目录：" + root);
        File canonical = root.getCanonicalFile();
        // Probe with this process's uid, not su. Do not chmod or require directory fsync on user storage.
        if (create) {
            java.nio.file.Path probe = Files.createTempFile(canonical.toPath(), ".saf-write-probe-", ".tmp");
            Files.delete(probe);
        }
        return canonical;
    }

    private static final class UnsafePathException extends IOException {
        UnsafePathException(File file) { super("恢复路径不能是符号链接：" + file); }
    }
    private static void rejectLinks(File file) throws IOException {
        for (File part = file.getAbsoluteFile(); part != null; part = part.getParentFile())
            if (Files.isSymbolicLink(part.toPath())) throw new UnsafePathException(part);
    }

    private static void validId(String id) throws IOException {
        try {
            if (id == null || !UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException error) { throw new IOException("非法恢复记录 UUID：" + id, error); }
    }

    private static File catalog(Context context) throws IOException {
        File root = directory(context);
        if (Files.isSymbolicLink(root.toPath())) throw new IOException("恢复索引不能是符号链接：" + root);
        root = new File(context.getFilesDir().getCanonicalFile(), "saf-recovery");
        rejectLinks(root);
        return root.getCanonicalFile();
    }

    /** Call from a worker. All local preconditions and durable metadata precede the destructive open. */
    static synchronized void write(Context context, FileStore.Node node, File content) throws IOException {
        write(context, node, content, null);
    }
    static synchronized void write(Context context, FileStore.Node node, File content, String expectedFingerprint) throws IOException {
        if (node.uri == null || node.directory || node.container != null)
            throw new IOException("SAF 恢复写入只接受普通文档");
        Digest payload = digestFile(content);
        if (!enabled(context)) {
            try {
                checkRevision(context, node, expectedFingerprint);
                writeAndVerify(context, node.uri, content, payload);
            } catch (RuntimeException error) { throw new IOException("SAF 保存失败：" + error.getMessage(), error); }
            return;
        }
        Record record;
        try { record = prepare(context, node, payload.sha256); }
        catch (IOException | RuntimeException error) {
            throw new IOException("未能完成持久恢复备份，未打开来源写入，原件未由本次操作截断。\n备份目录："
                    + directory(context).getAbsolutePath(), error);
        }
        // A prepared-only record is not offered for recovery: a revision conflict must not invite rollback.
        if (expectedFingerprint != null && !expectedFingerprint.equals(new FileStore(context).fingerprint(node)))
            throw new IOException("原文件已在外部改变，未打开来源写入；请重新打开后合并修改。\n备份保留：" + record.backup);
        record = record.withOriginal(new Digest(record.originalSize, record.originalSha256), "writing");
        try {
            verifyOriginal(record);
            publishMetadata(record);
            verifyOriginal(record);
        }
        catch (IOException | RuntimeException error) {
            throw new IOException("写入前恢复元数据持久化失败，未打开来源写入。\n备份保留：" + record.backup, error);
        }
        try {
            writeAndVerify(context, record.uri, content, payload);
        } catch (IOException | RuntimeException error) { throw new RecoveryException(record, error); }
        cleanupVerified(record);
    }

    private static void checkRevision(Context context, FileStore.Node node, String expected) throws IOException {
        if (expected != null && !expected.equals(new FileStore(context).fingerprint(node)))
            throw new IOException("原文件已在外部改变，未打开来源写入；请重新打开后合并修改。");
    }

    /** Explicit destructive operation: the caller must confirm overwriting the source's CURRENT bytes. */
    static synchronized void restore(Context context, Record pending) throws IOException {
        Record record = pending;
        try {
            record = load(context, pending.directory);
            if (!record.writeStarted) throw new IOException("该记录未进入来源写入阶段，不能用于覆盖外部版本");
            try (BackupInput backup = BackupInput.open(record)) {
                Digest original = backup.verifyAndRewind();
                record = record.withIdentity(backup.identity);
                writeAndVerify(context, record.uri, backup.input, original);
            }
        } catch (IOException | RuntimeException error) { throw new RecoveryException(record, error); }
        cleanupVerified(record);
    }

    /** Worker-thread structural snapshot; hashes are checked only when writing/restoring, not listing.
     * Entry.recoverable denotes a writing candidate whose file can be opened and has the recorded size.
     * A single bad record must not hide any other operation.
     */
    static synchronized List<Entry> entries(Context context) throws IOException {
        File root = catalog(context);
        if (!root.exists()) return Collections.emptyList();
        File[] operations = root.listFiles();
        if (operations == null) throw new IOException("无法扫描持久恢复备份：" + root);
        List<Entry> result = new ArrayList<>();
        for (File operation : operations) {
            try { validId(operation.getName()); }
            catch (IOException unknownName) { continue; }
            if (!Files.isDirectory(operation.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(operation.toPath())) continue;
            Record record = null;
            String problem = null;
            boolean recoverable = false;
            try {
                record = load(context, operation);
                if ("copying".equals(record.state)) problem = "原件备份未完成；来源未进入写入阶段";
                else {
                    inspectOriginal(record);
                    if (record.writeStarted) recoverable = true;
                    else problem = "备份已准备，但来源未进入写入阶段；不能覆盖外部版本";
                }
            } catch (IOException | RuntimeException error) { problem = error.getMessage(); }
            if (!recoverable && (problem == null || problem.isEmpty())) problem = "恢复记录不可用";
            // Never read a linked payload, even to determine the fallback display size.
            result.add(new Entry(operation.getName(), record == null ? operation.getName() : record.name,
                    record == null ? new File(operation, BACKUP) : record.backup,
                    record == null ? recordTime(operation) : record.createdAt,
                    record == null ? -1 : record.originalSize, recoverable, problem, record));
        }
        result.sort(Comparator.comparingLong(Entry::createdAt).reversed().thenComparing(Entry::id));
        return Collections.unmodifiableList(result);
    }

    private static long recordTime(File operation) {
        try { return Files.getLastModifiedTime(operation.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS).toMillis(); }
        catch (IOException | RuntimeException unreadable) { return 0; }
    }

    /** Structurally readable writing candidates only; restore still requires full SHA-256 verification. */
    static synchronized List<Record> pending(Context context) throws IOException {
        List<Record> result = new ArrayList<>();
        for (Entry entry : entries(context)) if (entry.recoverable) result.add(entry.record);
        result.sort(Comparator.comparingLong(record -> record.createdAt));
        return Collections.unmodifiableList(result);
    }

    /** Synchronous worker-thread API, serialized with write/restore; never scans a custom root. */
    static synchronized void delete(Context context, String id) throws IOException {
        validId(id);
        File operation = new File(catalog(context), id);
        rejectLinks(operation);
        if (!operation.exists()) return;
        checkedOperation(context, operation);
        File metadata = new File(operation, METADATA);
        rejectLinks(metadata);
        Record record;
        try { record = load(context, operation); }
        catch (UnsafePathException unsafe) { throw unsafe; }
        catch (IOException | RuntimeException badMetadata) {
            // With no trusted locator, remove only the known private UUID files. Never guess a user path.
            deleteFiles(operation, null);
            return;
        }
        deleteFiles(operation, record);
    }

    private static Record prepare(Context context, FileStore.Node node, String payloadSha256) throws IOException {
        File root = catalog(context);
        if (!root.isDirectory() && !root.mkdir()) throw new IOException("无法创建私有恢复目录：" + root);
        privateDirectory(root);
        syncDirectory(root.getParentFile());
        File configured = backupDirectory(context);
        File storageRoot = preferences(context).contains(BACKUP_DIRECTORY) ? validateLocalRoot(configured, false) : null;
        File operation = new File(root, UUID.randomUUID().toString());
        if (!operation.mkdir()) throw new IOException("无法创建独立恢复目录：" + operation);
        privateDirectory(operation);
        syncDirectory(root);
        Record copying = new Record(operation, storageRoot, node.uri, node.name, System.currentTimeMillis(),
                -1, "", payloadSha256, "copying");
        // Publish the locator BEFORE creating/copying original.bin, including custom payloads and failures.
        publishMetadata(copying);
        Record prepared;
        if (storageRoot == null) {
            try (FileOutputStream output = newLocalOutput(copying.backup.getPath())) {
                privateFile(copying.backup);
                prepared = copyOriginal(context, copying, output);
            }
            syncDirectory(operation);
        } else {
            // Pin both directories. A public directory entry replaced with a symlink must never redirect
            // O_CREAT into an unrelated file, and an existing original.bin must never be truncated.
            try (PinnedDirectory pinnedRoot = new PinnedDirectory(storageRoot.getPath())) {
                String child = pinnedRoot.child(operation.getName());
                try { Os.mkdir(child, 0700); }
                catch (ErrnoException error) { throw new IOException("无法创建独立自定义备份目录", error); }
                try (PinnedDirectory pinnedOperation = new PinnedDirectory(child);
                     FileOutputStream output = newLocalOutput(pinnedOperation.child(BACKUP))) {
                    prepared = copyOriginal(context, copying, output);
                    pinnedRoot.verify(storageRoot);
                    pinnedOperation.verify(copying.backup.getParentFile());
                }
            }
        }
        verifyOriginal(prepared);
        publishMetadata(prepared);
        return prepared;
    }

    private static Record copyOriginal(Context context, Record copying, FileOutputStream output) throws IOException {
        // Do not remove even an incomplete copy on failure.
        Digest original = readDocument(context, copying.uri, output);
        output.flush(); output.getFD().sync();
        try {
            android.system.StructStat identity = Os.fstat(output.getFD());
            if (!OsConstants.S_ISREG(identity.st_mode)) throw new IOException("备份描述符不是普通文件");
            return copying.withOriginal(original, "prepared").withIdentity(identity);
        } catch (ErrnoException error) { throw new IOException(error); }
    }

    static final class PinnedDirectory implements Closeable {
        private final ParcelFileDescriptor descriptor;
        PinnedDirectory(String path) throws IOException {
            try {
                // O_DIRECTORY is not in Android's public SDK. Open no-follow/nonblocking, then
                // require a directory via fstat before exposing the fd or resolving any child.
                FileDescriptor fd = Os.open(path, OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW | OsConstants.O_NONBLOCK, 0);
                try {
                    if (!OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) throw new IOException("备份路径不是普通目录：" + path);
                    descriptor = ParcelFileDescriptor.dup(fd);
                }
                finally { Os.close(fd); }
            } catch (ErrnoException error) { throw new IOException("无法固定本地备份目录：" + path, error); }
        }
        String child(String name) { return "/proc/self/fd/" + descriptor.getFd() + "/" + name; }
        void verify(File path) throws IOException {
            rejectLinks(path);
            try {
                android.system.StructStat opened = Os.fstat(descriptor.getFileDescriptor()), actual = Os.lstat(path.getPath());
                if (!OsConstants.S_ISDIR(actual.st_mode) || opened.st_dev != actual.st_dev || opened.st_ino != actual.st_ino)
                    throw new IOException("备份目录在操作期间被替换：" + path);
            } catch (ErrnoException error) { throw new IOException(error); }
        }
        @Override public void close() throws IOException { descriptor.close(); }
    }

    /** Internal paths may be relative to a pinned /proc/self/fd directory; always exclusive and no-follow. */
    static FileOutputStream newLocalOutput(String path) throws IOException {
        try {
            return ownedOutput(Os.open(path,
                    OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_EXCL | OsConstants.O_NOFOLLOW, 0600));
        } catch (ErrnoException error) { throw new IOException("无法创建独立原件备份：" + path, error); }
    }

    private static void publishMetadata(Record record) throws IOException {
        Properties values = new Properties();
        values.setProperty("version", "2"); values.setProperty("uri", record.uri.toString());
        values.setProperty("state", record.state);
        values.setProperty("name", record.name); values.setProperty("createdAt", Long.toString(record.createdAt));
        values.setProperty("originalSize", Long.toString(record.originalSize));
        values.setProperty("originalSha256", record.originalSha256); values.setProperty("payloadSha256", record.payloadSha256);
        // Identity is intentionally per-operation, not persisted: FUSE mount/inode identities may change after reboot.
        if (record.storageRoot != null) values.setProperty("storageRoot", record.storageRoot.getPath());
        File temporary = new File(record.directory, METADATA_TEMP);
        try (FileOutputStream output = localOutput(temporary)) {
            privateFile(temporary);
            values.store(output, "DualStackLab SAF persistent recovery copy; explicit restore only");
            output.flush(); output.getFD().sync();
        }
        // This atomic move is only on app-private storage, never a promise about provider replacement.
        rejectLinks(record.metadata);
        Files.move(temporary.toPath(), record.metadata.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        syncDirectory(record.directory);
    }

    private static File checkedOperation(Context context, File operation) throws IOException {
        validId(operation.getName()); rejectLinks(operation);
        File canonical = operation.getCanonicalFile();
        if (!catalog(context).equals(canonical.getParentFile()) || !canonical.isDirectory())
            throw new IOException("恢复记录不在私有恢复目录：" + operation);
        return canonical;
    }

    /** Parse metadata even when the payload is incomplete, missing, or corrupt, so it remains manageable. */
    private static Record load(Context context, File operation) throws IOException {
        File canonical = checkedOperation(context, operation);
        File metadata = new File(canonical, METADATA);
        rejectLinks(metadata);
        if (!Files.isRegularFile(metadata.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw new IOException("恢复元数据缺失或不是普通文件（孤立记录）：" + canonical);
        Properties values = new Properties();
        try (InputStream input = Files.newInputStream(metadata.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) { values.load(input); }
        try {
            Uri uri = Uri.parse(required(values, "uri"));
            String version = required(values, "version"), state = required(values, "state");
            if ((!"1".equals(version) && !"2".equals(version)) || !"content".equals(uri.getScheme()))
                throw new IllegalArgumentException("Unsupported recovery metadata");
            boolean copying = "2".equals(version) && "copying".equals(state);
            if (!copying && !"prepared".equals(state) && !"writing".equals(state))
                throw new IllegalArgumentException("Invalid recovery state");
            long size = Long.parseLong(required(values, "originalSize"));
            long created = Long.parseLong(required(values, "createdAt"));
            String original = required(values, "originalSha256"), payload = required(values, "payloadSha256");
            if (created < 0 || !payload.matches("[0-9a-f]{64}")
                    || (copying ? size != -1 || !original.isEmpty() : size < 0 || !original.matches("[0-9a-f]{64}")))
                throw new IllegalArgumentException("Invalid recovery digest or size");
            File storageRoot = null;
            if (values.containsKey("storageRoot")) {
                if (!"2".equals(version)) throw new IllegalArgumentException("Legacy metadata cannot redirect payloads");
                storageRoot = new File(required(values, "storageRoot"));
                if (!storageRoot.isAbsolute() || !storageRoot.toPath().normalize().equals(storageRoot.toPath()))
                    throw new IllegalArgumentException("Invalid absolute storageRoot");
                // Unavailable storage is not corrupt metadata; retain its locator for a later retry.
                if (storageRoot.toPath().startsWith(catalog(context).toPath()))
                    throw new IllegalArgumentException("storageRoot overlaps private catalog");
            }
            return new Record(canonical, storageRoot, uri, required(values, "name"), created, size, original, payload, state);
        } catch (IllegalArgumentException error) { throw new IOException("恢复元数据损坏，备份仍保留：" + canonical, error); }
    }

    private static void inspectOriginal(Record record) throws IOException {
        try (BackupInput backup = BackupInput.open(record)) {
            if (backup.identity.st_size != record.originalSize)
                throw new IOException("恢复备份字节数与记录不符：" + record.backup);
            backup.verifyLocation();
        }
    }

    private static Digest verifyOriginal(Record record) throws IOException {
        try (BackupInput backup = BackupInput.open(record)) { return backup.verifyAndRewind(); }
    }

    /** Hash and restore use the SAME read-only fd, not two path-based opens on mutable public storage. */
    private static final class BackupInput implements Closeable {
        final Record record;
        final PinnedDirectory root, operation;
        final FileInputStream input;
        final android.system.StructStat identity;
        private BackupInput(Record record, PinnedDirectory root, PinnedDirectory operation,
                            FileInputStream input, android.system.StructStat identity) {
            this.record = record; this.root = root; this.operation = operation;
            this.input = input; this.identity = identity;
        }
        static BackupInput open(Record record) throws IOException {
            PinnedDirectory root = null, operation = null;
            FileInputStream input = null;
            try {
                rejectLinks(record.backup);
                String path = record.backup.getPath();
                if (record.storageRoot != null) {
                    root = new PinnedDirectory(record.storageRoot.getPath());
                    operation = new PinnedDirectory(root.child(record.directory.getName()));
                    path = operation.child(BACKUP);
                }
                // O_NONBLOCK also prevents an attacker-swapped FIFO from hanging the entire catalog scan.
                input = ownedInput(Os.open(path, OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW | OsConstants.O_NONBLOCK, 0));
                android.system.StructStat identity = Os.fstat(input.getFD());
                if (!OsConstants.S_ISREG(identity.st_mode)) throw new IOException("恢复备份不是普通文件：" + record.backup);
                BackupInput opened = new BackupInput(record, root, operation, input, identity);
                opened.verifyLocation();
                return opened;
            } catch (ErrnoException error) {
                IOException failure = new IOException("无法读取恢复备份：" + record.backup, error);
                closeAfterFailure(failure, input, operation, root); throw failure;
            } catch (IOException | RuntimeException error) {
                closeAfterFailure(error, input, operation, root); throw error;
            }
        }
        void verifyLocation() throws IOException {
            if (root != null) {
                root.verify(record.storageRoot);
                operation.verify(record.backup.getParentFile());
            }
            checkIdentity(record.withIdentity(identity));
            if (record.backupInode != 0 && (record.backupDevice != identity.st_dev || record.backupInode != identity.st_ino))
                throw new IOException("原件备份在写入前被替换：" + record.backup);
        }
        Digest verifyAndRewind() throws IOException {
            Digest original = transfer(input, null);
            verifyLocation();
            if (original.size != record.originalSize || !original.sha256.equals(record.originalSha256))
                throw new IOException("恢复备份校验失败，未打开来源写入");
            try { Os.lseek(input.getFD(), 0, OsConstants.SEEK_SET); }
            catch (ErrnoException error) { throw new IOException(error); }
            return original;
        }
        @Override public void close() throws IOException {
            IOException failure = null;
            for (Closeable resource : new Closeable[]{input, operation, root}) {
                if (resource == null) continue;
                try { resource.close(); }
                catch (IOException error) {
                    if (failure == null) failure = error; else failure.addSuppressed(error);
                }
            }
            if (failure != null) throw failure;
        }
    }

    private static void closeAfterFailure(Throwable failure, Closeable... resources) {
        for (Closeable resource : resources) {
            if (resource == null) continue;
            try { resource.close(); } catch (IOException error) { failure.addSuppressed(error); }
        }
    }

    private static void checkIdentity(Record record) throws IOException {
        rejectLinks(record.backup);
        try {
            android.system.StructStat actual = Os.lstat(record.backup.getPath());
            if (!OsConstants.S_ISREG(actual.st_mode)
                    || (record.backupInode != 0 && (actual.st_dev != record.backupDevice || actual.st_ino != record.backupInode)))
                throw new IOException("原字节备份缺失、被替换或不是普通文件：" + record.backup);
        } catch (ErrnoException error) { throw new IOException("原字节备份缺失或不可读取：" + record.backup, error); }
    }

    private static String required(Properties values, String key) {
        String value = values.getProperty(key);
        if (value == null) throw new IllegalArgumentException("Missing " + key);
        return value;
    }

    private static void writeAndVerify(Context context, Uri uri, File content, Digest expected) throws IOException {
        // Opening the local payload before wt also prevents an unreadable payload from truncating the source.
        try (InputStream input = Files.newInputStream(content.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            writeAndVerify(context, uri, input, expected);
        }
    }
    private static void writeAndVerify(Context context, Uri uri, InputStream input, Digest expected) throws IOException {
        try (ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(uri, "wt")) {
            if (descriptor == null) throw new IOException("提供方未返回可写文件描述符");
            try (OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor)) {
                Digest sent = transfer(input, output);
                output.flush();
                if (descriptor.canDetectErrors()) descriptor.checkError();
                if (!sent.equals(expected)) throw new IOException("写入期间 payload 发生变化");
            } // Close failures are part of the write, never ignored.
        }
        Digest actual = readDocument(context, uri, null);
        if (!expected.equals(actual)) throw new IOException("来源 read-back SHA-256 或字节数与 payload 不符");
    }

    private static Digest readDocument(Context context, Uri uri, OutputStream copy) throws IOException {
        try (ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(uri, "r")) {
            if (descriptor == null) throw new IOException("提供方未返回可读文件描述符");
            long size = descriptor.getStatSize();
            try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
                Digest digest = transfer(input, copy);
                // In particular, reliable pipe EOF must not turn a provider read error into a short backup.
                if (descriptor.canDetectErrors()) descriptor.checkError();
                if (size >= 0 && size != digest.size) throw new IOException("提供方读取不完整");
                return digest;
            }
        }
    }

    private record Digest(long size, String sha256) {}
    private static Digest digestFile(File file) throws IOException {
        try (InputStream input = Files.newInputStream(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) { return transfer(input, null); }
    }
    private static Digest transfer(InputStream input, OutputStream output) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        byte[] buffer = new byte[128 * 1024]; long size = 0; int count;
        while ((count = input.read(buffer)) != -1) {
            if (count == 0) continue;
            digest.update(buffer, 0, count); size += count;
            if (output != null) output.write(buffer, 0, count);
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest.digest()) hex.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return new Digest(size, hex.toString());
    }
    private static FileOutputStream localOutput(File file) throws IOException {
        rejectLinks(file);
        if (Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw new IOException("备份路径不是普通文件：" + file);
        try {
            int flags = OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_TRUNC | OsConstants.O_NOFOLLOW;
            if (android.os.Build.VERSION.SDK_INT >= 27) flags |= OsConstants.O_CLOEXEC;
            return ownedOutput(Os.open(file.getPath(), flags, 0600));
        } catch (ErrnoException error) { throw new IOException("无法写入本地恢复文件：" + file, error); }
    }

    private static FileInputStream ownedInput(FileDescriptor fd) throws IOException {
        try { return new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(fd)); }
        finally {
            try { Os.close(fd); }
            catch (ErrnoException error) { throw new IOException(error); }
        }
    }

    static FileOutputStream ownedOutput(FileDescriptor fd) throws IOException {
        try { return new ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(fd)); }
        finally {
            try { Os.close(fd); }
            catch (ErrnoException error) { throw new IOException(error); }
        }
    }

    private static void privateDirectory(File file) throws IOException { chmod(file, 0700); }
    private static void privateFile(File file) throws IOException { chmod(file, 0600); }
    private static void chmod(File file, int mode) throws IOException {
        try { Os.chmod(file.getAbsolutePath(), mode); }
        catch (ErrnoException error) { throw new IOException(error); }
    }
    private static void syncDirectory(File directory) throws IOException {
        try {
            int flags = OsConstants.O_RDONLY;
            if (android.os.Build.VERSION.SDK_INT >= 27) flags |= OsConstants.O_CLOEXEC;
            java.io.FileDescriptor descriptor = Os.open(directory.getAbsolutePath(), flags, 0);
            try { Os.fsync(descriptor); } finally { Os.close(descriptor); }
        } catch (ErrnoException error) { throw new IOException("无法持久化恢复目录：" + directory, error); }
    }

    private static void deleteFiles(File operation, Record record) throws IOException {
        rejectLinks(operation);
        File backup = record == null ? new File(operation, BACKUP) : record.backup;
        File metadata = new File(operation, METADATA), temporary = new File(operation, METADATA_TEMP);
        // Preflight every known path before deleting anything. Files.delete never follows a link either.
        for (File file : new File[]{backup, metadata, temporary}) rejectLinks(file);
        if (record != null && record.storageRoot != null) deleteCustomPayload(record);
        else {
            if (record != null && record.backupInode != 0
                    && Files.exists(backup.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) checkIdentity(record);
            Files.deleteIfExists(backup.toPath());
        }
        // Keep the locator until payload deletion succeeds. A failed cleanup remains visible in the catalog.
        Files.deleteIfExists(temporary.toPath());
        Files.deleteIfExists(metadata.toPath());
        syncDirectory(operation);
        deleteEmptyDirectory(operation);
        syncDirectory(operation.getParentFile());
    }

    private static void deleteCustomPayload(Record record) throws IOException {
        rejectLinks(record.backup);
        if (!record.storageRoot.isDirectory() || !record.storageRoot.canRead() || !record.storageRoot.canWrite())
            throw new IOException("备份目录暂不可用，备份及索引尚未删除；请恢复目录访问后重试：" + record.storageRoot);
        try (PinnedDirectory root = new PinnedDirectory(record.storageRoot.getPath())) {
            String operationPath = root.child(record.directory.getName());
            if (!Files.exists(new File(operationPath).toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
            try (PinnedDirectory operation = new PinnedDirectory(operationPath)) {
                root.verify(record.storageRoot);
                operation.verify(record.backup.getParentFile());
                java.nio.file.Path payload = new File(operation.child(BACKUP)).toPath();
                if (Files.isSymbolicLink(payload)) throw new UnsafePathException(record.backup);
                if (Files.exists(payload, java.nio.file.LinkOption.NOFOLLOW_LINKS) && record.backupInode != 0)
                    checkIdentity(record);
                Files.deleteIfExists(payload);
                String[] remaining = new File(operation.child(".")).list();
                if (remaining == null) throw new IOException("无法读取已固定的自定义备份目录");
                // Extra user files are left untouched; the root itself is never removed or fsynced.
                if (remaining.length == 0) {
                    root.verify(record.storageRoot);
                    operation.verify(record.backup.getParentFile());
                    Files.delete(new File(operationPath).toPath());
                }
            }
        }
    }

    private static void deleteEmptyDirectory(File directory) throws IOException {
        rejectLinks(directory);
        if (!directory.exists()) return;
        File[] children = directory.listFiles();
        if (children == null) throw new IOException("无法读取恢复记录目录：" + directory);
        // Never recurse into a user directory, or delete files we did not record.
        if (children.length == 0) Files.delete(directory.toPath());
    }

    private static void cleanupVerified(Record record) {
        // Only reached after read-back verification. Failure to tidy up must not be reported as data loss.
        try { deleteFiles(record.directory, record); }
        catch (IOException | RuntimeException cleanup) { Log.w("SafRecovery", "已核对成功，恢复目录未完全清理：" + record.directory, cleanup); }
    }

    static RecoveryException findRecovery(Throwable error) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = error; cause != null && seen.add(cause); cause = cause.getCause())
            if (cause instanceof RecoveryException) return (RecoveryException) cause;
        return null;
    }
    static void showRecovery(Activity activity, RecoveryException error) {
        if (enabled(activity)) offerRecord(activity, error.record, error.getMessage());
    }
    private static void offerRecord(Activity activity, Record record, String message) {
        new AlertDialog.Builder(activity).setTitle("SAF 持久恢复备份")
                .setMessage(message + "\n\n备份：" + record.backup + "\n来源：" + record.uri)
                .setPositiveButton("恢复原件", (dialog, which) -> confirmRestore(activity, record, null))
                .setNeutralButton("查看备份", (dialog, which) -> viewBackups(activity))
                .setNegativeButton("稍后", null).show();
    }
    static void confirmRestore(Activity activity, Record record, Runnable onSuccess) {
        new AlertDialog.Builder(activity).setTitle("确认覆盖来源当前内容？")
                .setMessage("将使用持久备份恢复「" + record.name + "」。\n来源：" + record.uri
                        + "\n\n这会覆盖来源当前内容，包括保存失败后其他应用写入的新版本。此操作不是合并，也不会自动保留该新版本。"
                        + "\n仅在写回并重新读取 SHA-256 核对成功后清理这份备份。")
                .setPositiveButton("确认覆盖并恢复", (dialog, which) -> Tasks.run(activity,
                        () -> { restore(activity, record); return true; },
                        restored -> {
                            Tasks.toast(activity, "原字节已恢复并核对成功");
                            if (onSuccess != null) onSuccess.run();
                        }))
                .setNegativeButton("取消", null).show();
    }
    private static void viewBackups(Activity activity) {
        Intent intent = new Intent(activity, RecycleBinActivity.class);
        try { activity.startActivity(intent); } catch (RuntimeException error) { Tasks.showError(activity, error); }
    }

    /** Hook from MainActivity.onCreate; scanning and all restores run off the UI thread. */
    static void offerPending(Activity activity) {
        if (!enabled(activity)) return;
        Tasks.run(activity, () -> {
            if (!enabled(activity)) return Collections.<Record>emptyList();
            synchronized (SafRecovery.class) {
                return enabled(activity) ? pending(activity) : Collections.<Record>emptyList();
            }
        }, records -> {
            if (!enabled(activity) || records.isEmpty()) return;
            String[] labels = new String[records.size()];
            for (int i = 0; i < labels.length; i++) labels[i] = records.get(i).name + " · " + new Date(records.get(i).createdAt);
            new AlertDialog.Builder(activity).setTitle("发现 " + records.size() + " 份 SAF 恢复备份")
                    .setItems(labels, (dialog, which) -> {
                        if (enabled(activity)) offerRecord(activity, records.get(which),
                                "发现上次保存留下的持久恢复备份。原件可能不完整，也可能已被外部更新；不会自动恢复。");
                    })
                    .setNeutralButton("查看备份", (dialog, which) -> viewBackups(activity))
                    .setNegativeButton("稍后", null).show();
        }, error -> {
            if (!enabled(activity)) return;
            new AlertDialog.Builder(activity).setTitle("恢复备份扫描失败")
                    .setMessage(error.getMessage() + "\n未写入或删除任何来源文档。备份目录：" + directory(activity))
                    .setPositiveButton("查看备份", (dialog, which) -> viewBackups(activity))
                    .setNegativeButton("稍后", null).show();
        });
    }
    private SafRecovery() {}
}
