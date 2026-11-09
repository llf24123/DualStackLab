package com.dualstack.lab;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class ShellRunner {
    static final int OUTPUT_LIMIT = 2 * 1024 * 1024;
    record Result(int exitCode, String output, boolean stopped, boolean timedOut, boolean truncated) {}
    private volatile Process process;
    private volatile boolean stopped;
    private final Object inputLock = new Object();
    private volatile File pidFile;
    private volatile boolean root;

    static int quotedPayloadBytes(String command) {
        int bytes = command.getBytes(StandardCharsets.UTF_8).length;
        // The session wrapper quotes the payload twice; each apostrophe adds 12 bytes.
        for (int i = 0; i < command.length(); i++) if (command.charAt(i) == '\'') bytes += 12;
        return bytes;
    }

    Result run(String command, File directory, boolean root, long timeoutMillis,
               Consumer<String> listener, boolean interactive) throws IOException {
        return run(command, directory, root, timeoutMillis, listener, interactive, null);
    }

    /** {@code stdin}, when given, is pumped into the command's stdin and then closed. */
    Result run(String command, File directory, boolean root, long timeoutMillis,
               Consumer<String> listener, boolean interactive, InputStream stdin) throws IOException {
        this.root = root;
        ProcessBuilder builder;
        if (directory != null && !root && !directory.isDirectory()) throw new IOException("工作目录不存在：" + directory);
        pidFile = File.createTempFile("shell-pid-", ".txt");
        String wrapped = "echo $$ > " + FileStore.quote(pidFile.getAbsolutePath()) + "\n"
                + (directory == null ? "" : "cd " + FileStore.quote(directory.getAbsolutePath()) + " || exit\n") + command;
        // Magisk can start a group leader: setsid would fork and return before the command.
        // Start it as a background child, forward stdin explicitly, then wait for its exit.
        String launch = "exec 3<&0\n/system/bin/toybox setsid /system/bin/sh -c " + FileStore.quote(wrapped)
                + " <&3 3<&- &\nchild=$!\nexec 3<&-\nwait \"$child\"";
        String shell = "exec /system/bin/sh -c " + FileStore.quote(launch);
        builder = root ? RootShell.command(shell) : new ProcessBuilder("/system/bin/sh", "-c", shell);
        builder.directory(directory != null && !root ? directory : pidFile.getParentFile());
        builder.redirectErrorStream(true);
        String path = builder.environment().get("PATH");
        builder.environment().put("PATH", "/system/bin:/system/xbin" + (path == null ? "" : ":" + path));
        builder.environment().put("LANG", "C.UTF-8");
        builder.environment().put("TERM", "dumb");
        StringBuilder captured = new StringBuilder();
        boolean[] truncated = {false};
        java.util.concurrent.atomic.AtomicReference<IOException> outputFailure = new java.util.concurrent.atomic.AtomicReference<>();
        try {
            try { process = builder.start(); }
            catch (IOException e) { throw new IOException(root ? "无法启动 su；设备需已 ROOT 并允许授权" : "无法启动系统 Shell", e); }
            if (stopped) stop();
            if (stdin != null) {
                // Pump separately: a command that writes more than the pipe buffer would otherwise deadlock.
                Thread feeder = new Thread(Tasks.Progress.bind(() -> {
                    try (OutputStream out = process.getOutputStream(); InputStream source = stdin) { FileStore.pump(source, out); }
                    catch (IOException failure) {
                        outputFailure.compareAndSet(null, failure);
                        if (failure instanceof Tasks.CancelledException) {
                            // EOF lets transactional readers reject a short payload and run their finally cleanup.
                            try { if (!process.waitFor(5, TimeUnit.SECONDS)) stop(); }
                            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); stop(); }
                        } else stop();
                    }
                }), "shell-input");
                feeder.setDaemon(true); feeder.start();
            } else if (!interactive) process.getOutputStream().close();
            Thread reader = new Thread(() -> {
                try (Reader input = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                    char[] buffer = new char[4096]; int count;
                    while ((count = input.read(buffer)) >= 0) {
                        if (count == 0) continue;
                        String chunk = new String(buffer, 0, count);
                        synchronized (captured) {
                            int remaining = OUTPUT_LIMIT - captured.length();
                            if (remaining > 0) captured.append(chunk, 0, Math.min(remaining, chunk.length()));
                            if (chunk.length() > remaining) truncated[0] = true;
                        }
                        if (listener != null) listener.accept(chunk);
                    }
                } catch (IOException ignored) { }
                catch (RuntimeException e) {
                    outputFailure.set(e instanceof UncheckedIOException io ? io.getCause() : new IOException("输出处理失败", e));
                    stop();
                }
            }, "shell-output");
            reader.setDaemon(true); reader.start();
            boolean finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!finished) stop();
            process.waitFor(2, TimeUnit.SECONDS);
            if (process.isAlive()) process.destroyForcibly();
            reader.join(2000);
            if (reader.isAlive()) { process.getInputStream().close(); reader.join(500); }
            if (outputFailure.get() != null) throw outputFailure.get();
            synchronized (captured) {
                return new Result(process.isAlive() ? -1 : process.exitValue(), captured.toString(),
                        stopped && finished, !finished, truncated[0]);
            }
        } catch (InterruptedException e) {
            stop(); Thread.currentThread().interrupt(); throw new IOException("执行被中断", e);
        } finally {
            if (process != null && process.isAlive()) stop();
            if (pidFile != null) java.nio.file.Files.deleteIfExists(pidFile.toPath());
        }
    }

    void send(String line) throws IOException {
        synchronized (inputLock) {
            Process running = process;
            if (running == null || !running.isAlive()) throw new IOException("当前没有正在运行的命令");
            running.getOutputStream().write((line + "\n").getBytes(StandardCharsets.UTF_8));
            running.getOutputStream().flush();
        }
    }
    void closeInput() throws IOException {
        synchronized (inputLock) { if (process != null) process.getOutputStream().close(); }
    }
    void stop() {
        stopped = true;
        Process running = process;
        if (running == null) return;
        // A separate session lets cancellation terminate shell children as well.
        try {
            for (int attempt = 0; pidFile != null && pidFile.length() == 0 && running.isAlive() && attempt < 10; attempt++)
                Thread.sleep(25);
            if (pidFile != null && pidFile.length() > 0) {
                String pid = new String(java.nio.file.Files.readAllBytes(pidFile.toPath()), StandardCharsets.US_ASCII).trim();
                if (pid.matches("[1-9][0-9]*")) {
                    signalGroup(pid, "TERM");
                    Thread.sleep(150);
                    signalGroup(pid, "KILL");
                }
            }
        } catch (Exception ignored) { }
        running.destroy();
        try { if (!running.waitFor(300, TimeUnit.MILLISECONDS)) running.destroyForcibly(); }
        catch (InterruptedException e) { running.destroyForcibly(); Thread.currentThread().interrupt(); }
    }
    private void signalGroup(String pid, String signalName) throws IOException, InterruptedException {
        String kill = "/system/bin/toybox kill -" + signalName + " -- -" + pid;
        Process signal = (root ? RootShell.command(kill) : new ProcessBuilder("/system/bin/sh", "-c", kill)).start();
        signal.getOutputStream().close();
        if (!signal.waitFor(1, TimeUnit.SECONDS)) signal.destroyForcibly();
    }
}
