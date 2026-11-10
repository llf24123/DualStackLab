package com.dualstack.lab;

import android.os.SystemClock;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;

/** A short-lived read-only channel, separate from executable and interactive shells. */
final class RootDirectoryReader {
    private static final int OUTPUT_LIMIT = 16 * 1024 * 1024;
    private static final ScheduledExecutorService IDLE = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "root-directory-idle"); thread.setDaemon(true); return thread;
    });
    private static Session session;
    private static ScheduledFuture<?> idle;

    record Listing(String identity, String output) {}
    static String nearestDirectory(String path) throws IOException {
        return query("path=" + FileStore.quote(path) + "\n"
                + "while :; do\n"
                + "if [ -d \"$path\" ]; then printf '%s' \"$path\"; break; fi\n"
                + "if [ \"$path\" = / ]; then echo '无法读取根目录' >&2; exit 1; fi\n"
                + "parent=${path%/*}; [ -n \"$parent\" ] || parent=/\n"
                + "if [ -e \"$parent\" ] && [ -d \"$parent\" ] && [ ! -x \"$parent\" ]; then "
                + "echo '没有目录访问权限' >&2; exit 1; fi\n"
                + "path=$parent\ndone").trim();
    }

    static Listing snapshot(String path) throws IOException {
        String marker = "__DUALSTACK_PARENT_" + UUID.randomUUID() + "_";
        String quoted = FileStore.quote(path), listing = FileStore.rootListCommand(path);
        String command = "if [ -d " + quoted + " ]; then /system/bin/toybox stat -L -c '"
                + marker + "%d:%i' -- " + quoted + " && " + listing + "; else " + listing + "; fi";
        String output = query(command);
        int newline = output.indexOf('\n');
        if (newline < 0 || !output.startsWith(marker)) throw new IOException("ROOT 目录快照缺少身份信息");
        String identity = output.substring(marker.length(), newline);
        if (!identity.matches("[0-9]+:[0-9]+")) throw new IOException("ROOT 目录身份无效");
        return new Listing(identity, output.substring(newline + 1));
    }
    static String list(String path) throws IOException { return list(path, true); }
    static String list(String path, boolean followLinks) throws IOException {
        return query(FileStore.rootListCommand(path, followLinks));
    }
    static String stat(String path, String format, boolean followLinks) throws IOException {
        return query("/system/bin/toybox stat " + (followLinks ? "-L " : "") + "-c "
                + FileStore.quote(format) + " -- " + FileStore.quote(path)).trim();
    }
    static String[] identities(String... paths) throws IOException {
        StringBuilder command = new StringBuilder("/system/bin/toybox stat -L -c '%d:%i' --");
        for (String path : paths) command.append(' ').append(FileStore.quote(path));
        String[] identities = query(command.toString()).trim().split("\\n");
        if (identities.length != paths.length) throw new IOException("ROOT 身份快照数量不匹配");
        for (String identity : identities)
            if (!identity.matches("[0-9]+:[0-9]+")) throw new IOException("ROOT 身份快照无效");
        return identities;
    }
    static String inspect(String path) throws IOException {
        String quoted = FileStore.quote(path);
        return query("/system/bin/toybox stat -L -c 'F|%d|%i|%f|%s|%Y' -- " + quoted
                + " 2>/dev/null || /system/bin/toybox stat -c 'L|%d|%i|%f|%s|%Y' -- " + quoted).trim();
    }
    private static synchronized String query(String command) throws IOException {
        if (idle != null) idle.cancel(false);
        if (session != null && (!session.process.isAlive()
                || SystemClock.elapsedRealtime() - session.created >= 30000)) close();
        try {
            if (session == null) session = new Session();
            return session.list(command);
        } catch (IOException e) {
            close(); throw e;
        } finally {
            idle = IDLE.schedule(RootDirectoryReader::close, 15, TimeUnit.SECONDS);
        }
    }

    static synchronized void close() {
        if (session != null) { session.close(); session = null; }
    }

    private record Line(String text, IOException error) {}
    private static final class Session {
        final long created = SystemClock.elapsedRealtime();
        final Process process;
        final BufferedWriter input;
        final BlockingQueue<Line> lines = new ArrayBlockingQueue<>(256);
        final Thread reader;

        Session() throws IOException {
            this(RootShell.command("exec /system/bin/sh"));
        }
        Session(ProcessBuilder command) throws IOException {
            process = command.redirectErrorStream(true).start();
            input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            reader = new Thread(() -> {
                try (BufferedReader output = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = output.readLine()) != null) lines.put(new Line(line, null));
                    lines.put(new Line(null, new IOException("ROOT 目录通道已关闭")));
                } catch (IOException error) {
                    lines.offer(new Line(null, error));
                } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            }, "root-directory-output");
            reader.setDaemon(true); reader.start();
        }

        String list(String command) throws IOException {
            String marker = "__DUALSTACK_DIRECTORY_" + UUID.randomUUID() + "_";
            input.write(command);
            input.write("\nresult=$?\nprintf '\\n" + marker + "%d\\n' \"$result\"\n");
            input.flush();
            long deadline = SystemClock.elapsedRealtime() + 10000;
            StringBuilder output = new StringBuilder();
            try {
                while (true) {
                    Line line = lines.poll(Math.max(1, deadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS);
                    if (line == null) throw new IOException("ROOT 目录读取超过 10 秒，已停止");
                    if (line.error != null) throw line.error;
                    if (line.text.startsWith(marker)) {
                        String status = line.text.substring(marker.length());
                        if (!status.equals("0")) throw new IOException("exit " + status + "\n" + output);
                        return output.toString();
                    }
                    if (output.length() + line.text.length() + 1 > OUTPUT_LIMIT)
                        throw new IOException("ROOT 目录列表超过 16 MiB，请进入更小的子目录");
                    output.append(line.text).append('\n');
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt(); throw new IOException("目录读取已取消", error);
            }
        }

        void close() {
            process.destroy(); reader.interrupt();
            try { input.close(); } catch (IOException ignored) { }
            if (process.isAlive()) process.destroyForcibly();
        }
    }
    private RootDirectoryReader() {}
}
