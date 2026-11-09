package com.dualstack.lab;

import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import android.system.OsConstants;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background-only, serialized, reusable app_process mutation channel. Integration is deliberately
 * separate from FileStore/RootAccess: callers supply the root-context guards carried by Node.
 * A missing guard is snapshotted by the helper, NEVER inferred using app-side stat/canonicalPath.
 * No request, especially an interrupted mutation, is ever automatically replayed.
 */
final class RootMutations {
    private static final long REQUEST_WAIT_MS = 30000, IDLE_MS = 15000, LIFETIME_MS = 30000;
    private static final ScheduledExecutorService TIMER = Executors.newScheduledThreadPool(2, task -> {
        Thread thread = new Thread(task, "root-mutations-timer"); thread.setDaemon(true); return thread;
    });
    private static Session cached;
    private static boolean cachedRoot;
    private static ScheduledFuture<?> idle;

    static FileStore.Node create(Context context, FileStore.Node parent, String name, boolean directory) throws IOException {
        return node(call(context, RootFileOps.Request.create(directorySpec(parent), name, directory)));
    }
    static FileStore.Node rename(Context context, FileStore.Node source, FileStore.Node parent, String name) throws IOException {
        return node(call(context, RootFileOps.Request.rename(spec(source), directorySpec(parent), name, false)), source);
    }
    /** Returns null ONLY for the non-copying cross-device fast-path verdict. */
    static FileStore.Node moveIfSameFileSystem(Context context, FileStore.Node source, FileStore.Node parent, String name) throws IOException {
        return node(call(context, RootFileOps.Request.rename(spec(source), directorySpec(parent), name, true)), source);
    }
    static void delete(Context context, Collection<FileStore.Node> nodes) throws IOException {
        List<RootFileOps.Spec> specs = new ArrayList<>();
        for (FileStore.Node node : nodes) specs.add(spec(node));
        call(context, RootFileOps.Request.delete(specs));
    }
    /** Owned-inode rollback: removes one entry, never recursively clears a directory. */
    static void removeEmpty(Context context, FileStore.Node node) throws IOException {
        call(context, RootFileOps.Request.deleteEmpty(List.of(spec(node))));
    }
    /** Delete only the backed-up members; new children make rmdir fail instead of being traversed. */
    static void removeBacked(Context context, List<FileStore.Node> nodes) throws IOException {
        requireWorker();
        boolean root = nodes.stream().anyMatch(node -> node.root);
        Map<Integer, List<RootFileOps.Spec>> levels = new TreeMap<>(Comparator.reverseOrder());
        for (FileStore.Node node : nodes) {
            int depth = 0; for (int i = 0; i < node.path().length(); i++) if (node.path().charAt(i) == '/') depth++;
            levels.computeIfAbsent(depth, ignored -> new ArrayList<>()).add(spec(node));
        }
        for (List<RootFileOps.Spec> specs : levels.values())
            for (int i = 0; i < specs.size(); i += 4096)
                call(context, RootFileOps.Request.deleteEmpty(specs.subList(i, Math.min(i + 4096, specs.size()))), root);
    }
    static FilePermissions.State chmod(Context context, FileStore.Node source, int mode) throws IOException {
        RootFileOps.Result result = call(context, RootFileOps.Request.chmod(spec(source), mode));
        if (result == null) throw new IOException("chmod 响应没有结果");
        return new FilePermissions.State(result.mode & 07777, result.uid, result.gid, true, false);
    }
    private static RootFileOps.Spec directorySpec(FileStore.Node node) throws IOException {
        RootFileOps.Spec selected = spec(node);
        if (node.directoryIdentity == null) return selected;
        return new RootFileOps.Spec(selected.path, null, null, node.directoryIdentity, true);
    }
    private static RootFileOps.Spec spec(FileStore.Node node) throws IOException {
        if (node == null || node.file == null || node.uri != null || node.container != null) throw new IOException("ROOT mutation 只支持本地文件系统节点");
        return new RootFileOps.Spec(node.path(), node.identity, node.parentIdentity, node.directoryIdentity, node.identityFollowsLinks);
    }
    private static FileStore.Node node(RootFileOps.Result result) { return node(result, null); }
    private static FileStore.Node node(RootFileOps.Result result, FileStore.Node source) {
        if (result == null) return null;
        File file = new File(result.path);
        boolean link = source != null && OsConstants.S_ISLNK(result.mode);
        FileStore.Node node = new FileStore.Node(file.getName(), file, null,
                link ? source.directory : OsConstants.S_ISDIR(result.mode), true, result.size, result.modified);
        node.identity = result.identity; node.parentIdentity = result.parentIdentity;
        // Preserve the observed navigation target, without stat'ing through the newly moved path.
        // Mutation identity remains the real no-follow link inode returned by the helper.
        node.directoryIdentity = link ? source.directoryIdentity : result.directoryIdentity;
        node.identityFollowsLinks = false;
        return node;
    }
    private static RootFileOps.Result call(Context context, RootFileOps.Request request) throws IOException {
        return call(context, request, true);
    }
    private static RootFileOps.Result call(Context context, RootFileOps.Request request, boolean root) throws IOException {
        // Do not let the UI wait on a class monitor held by an IO worker or by su authorization.
        requireWorker();
        synchronized (RootMutations.class) {
            if (idle != null) { idle.cancel(false); idle = null; }
            if (cached != null && (cachedRoot != root || !cached.isAlive() || SystemClock.elapsedRealtime() - cached.created >= LIFETIME_MS)) close();
            try {
                if (cached == null) { cached = new Session(context.getApplicationContext(), root); cachedRoot = root; }
                return cached.execute(request, null);
            } finally {
                if (cached != null && !cached.isAlive()) { cached.close(); cached = null; }
                idle = TIMER.schedule(RootMutations::close, IDLE_MS, TimeUnit.MILLISECONDS);
            }
        }
    }
    static synchronized void close() {
        if (idle != null) { idle.cancel(false); idle = null; }
        if (cached != null) { cached.close(); cached = null; }
    }
    private static void requireWorker() throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IOException("ROOT mutation 必须在后台线程调用");
    }

    /** Launch-only shell syntax; no requested file path/name is ever interpolated into a shell. */
    static String helperCommand(Context context) {
        return "CLASSPATH=" + FileStore.quote(context.getApplicationInfo().sourceDir)
                + " exec /system/bin/app_process /system/bin com.dualstack.lab.RootFileOps";
    }
    /** Test hook executes on the calling thread. PREPARED is not ACKed; only STEP consumes a boolean. */
    interface EventHook { boolean onEvent(int event, String name) throws IOException; }
    private static final class Received {
        final RootFileOps.Event event;
        final IOException error;
        Received(RootFileOps.Event event, IOException error) { this.event = event; this.error = error; }
    }

    static final class Session implements AutoCloseable {
        final long created = SystemClock.elapsedRealtime();
        final Process process;
        final int helperPid, helperUid;
        private final DataOutputStream input;
        private final BlockingQueue<Received> events = new ArrayBlockingQueue<>(32);
        private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Thread reader, errors;
        private volatile ScheduledFuture<?> timeout;

        Session(Context context, boolean root) throws IOException {
            this(root ? RootShell.command(helperCommand(context))
                    : new ProcessBuilder("/system/bin/sh", "-c", helperCommand(context)), root);
        }
        static Session runAsForTest(Context context) throws IOException {
            return new Session(new ProcessBuilder("/system/bin/run-as", context.getPackageName(), "/system/bin/sh", "-c", helperCommand(context)), false);
        }
        private Session(ProcessBuilder builder, boolean root) throws IOException {
            requireWorker();
            process = builder.redirectErrorStream(false).start();
            input = new DataOutputStream(new BufferedOutputStream(process.getOutputStream()));
            reader = new Thread(() -> {
                try (DataInputStream output = new DataInputStream(new BufferedInputStream(process.getInputStream()))) {
                    while (!closed.get()) events.put(new Received(RootFileOps.Event.read(output), null));
                } catch (IOException error) { events.offer(new Received(null, error)); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            }, "root-mutations-output");
            errors = new Thread(() -> {
                try (InputStream error = process.getErrorStream()) {
                    byte[] buffer = new byte[2048]; int count;
                    while ((count = error.read(buffer)) != -1) synchronized (stderr) {
                        int keep = Math.min(count, 8192 - stderr.size());
                        if (keep > 0) stderr.write(buffer, 0, keep);
                        // Continue draining after the cap so verbose ART/su stderr cannot block the helper.
                    }
                } catch (IOException ignored) { }
            }, "root-mutations-stderr");
            reader.setDaemon(true); errors.setDaemon(true); reader.start(); errors.start();
            int pid, uid;
            armTimeout();
            try {
                RootFileOps.Event ready = next();
                if (ready.code != RootFileOps.READY) throw new IOException("ROOT helper 没有 READY");
                pid = ready.pid; uid = ready.uid;
                if (pid <= 0 || (root ? uid != 0 : uid != android.os.Process.myUid())) throw new IOException("helper UID 不符合请求：" + uid);
            } catch (IOException error) { abort(); throw diagnostic(error); }
            finally { disarmTimeout(); }
            helperPid = pid; helperUid = uid;
        }

        synchronized RootFileOps.Result execute(RootFileOps.Request request, EventHook hook) throws IOException {
            requireWorker();
            byte[] payload = request.encode(); // Serialize completely BEFORE touching the helper's pipe.
            if (!isAlive()) throw new IOException("ROOT helper 已关闭；请求未发送");
            boolean prepared = false, cancelling = false;
            IOException callbackFailure = null;
            armTimeout();
            try {
                input.writeInt(payload.length); input.write(payload); input.flush();
                while (true) {
                    RootFileOps.Event event = next();
                    armTimeout();
                    switch (event.code) {
                        case RootFileOps.PREPARED:
                            if (prepared) throw new IOException("重复 PREPARED");
                            prepared = true;
                            if (hook != null) try { if (!hook.onEvent(event.code, event.text)) cancelling = true; }
                            catch (IOException error) { callbackFailure = error; cancelling = true; }
                            break;
                        case RootFileOps.STEP:
                            if (!prepared) throw new IOException("STEP 早于 PREPARED");
                            if (!cancelling) try {
                                Tasks.Progress.step(event.text); // original caller's ThreadLocal, never a reader thread
                                if (hook != null && !hook.onEvent(event.code, event.text)) cancelling = true;
                            } catch (IOException error) { callbackFailure = error; cancelling = true; }
                            input.writeBoolean(!cancelling); input.flush();
                            break;
                        case RootFileOps.OK:
                            if (!prepared || cancelling) throw new IOException("ROOT helper 非法 OK 边界");
                            return event.result;
                        case RootFileOps.ERROR:
                            // A complete ERROR terminates exactly one request; it is not a transport retry.
                            throw new RemoteFailure(event.text);
                        case RootFileOps.CANCELLED:
                            if (!prepared || !cancelling) throw new IOException("意外 CANCELLED");
                            if (callbackFailure != null) throw callbackFailure;
                            throw new Tasks.CancelledException();
                        default: throw new IOException("ROOT helper 响应顺序错误");
                    }
                }
            } catch (RemoteFailure | Tasks.CancelledException error) {
                throw error;
            } catch (IOException error) {
                abort(); throw diagnostic(error);
            } catch (RuntimeException error) {
                abort(); throw new IOException("STEP 回调失败；已关闭会话，绝不重放请求", error);
            } finally { disarmTimeout(); }
        }
        private RootFileOps.Event next() throws IOException {
            try {
                Received received = events.poll(REQUEST_WAIT_MS, TimeUnit.MILLISECONDS);
                if (received == null) throw new IOException("ROOT helper 响应超过 30 秒；已停止，未重放，部分操作可能已完成");
                if (received.error != null) throw received.error;
                return received.event;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt(); throw new IOException("ROOT helper 等待已中断；部分操作可能已完成", error);
            }
        }
        boolean isAlive() { return !closed.get() && process.isAlive(); }
        private void armTimeout() {
            disarmTimeout(); timeout = TIMER.schedule(this::abort, REQUEST_WAIT_MS, TimeUnit.MILLISECONDS);
        }
        private void disarmTimeout() { ScheduledFuture<?> timer = timeout; if (timer != null) timer.cancel(false); timeout = null; }
        private IOException diagnostic(IOException cause) {
            String details;
            synchronized (stderr) { details = new String(stderr.toByteArray(), StandardCharsets.UTF_8).trim(); }
            return new IOException(cause.getMessage() + (details.isEmpty() ? "" : "\n" + details), cause);
        }
        private void abort() {
            if (!closed.compareAndSet(false, true)) return;
            // Destroy first: a writer blocked on a full pipe must not prevent the timeout thread closing it.
            process.destroy(); if (process.isAlive()) process.destroyForcibly();
            try { process.getOutputStream().close(); } catch (IOException ignored) { }
            try { process.getInputStream().close(); } catch (IOException ignored) { }
            try { process.getErrorStream().close(); } catch (IOException ignored) { }
            reader.interrupt(); errors.interrupt();
            events.offer(new Received(null, new IOException("ROOT helper 会话已终止；未重放请求")));
            // If a su supervisor outlives Process.destroy, EOF or the helper's own 29s watchdog
            // terminates the ART child. We never kill arbitrary pids or run a broad pkill command.
        }
        void closeGracefullyForTest() throws IOException {
            if (closed.get()) return;
            try {
                input.close(); // stdin EOF is the normal helper shutdown protocol, including idle retirement.
                if (!process.waitFor(2, TimeUnit.SECONDS)) throw new IOException("ROOT helper 未在 EOF 后退出");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt(); throw new IOException("等待 helper 退出已中断", error);
            } finally { disarmTimeout(); abort(); }
        }
        @Override public void close() {
            try { closeGracefullyForTest(); } catch (IOException ignored) { abort(); }
        }
    }
    private static final class RemoteFailure extends IOException { RemoteFailure(String message) { super(message); } }
    private RootMutations() { }
}
