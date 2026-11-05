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

    static String list(String path) throws IOException { return list(path, true); }
    static synchronized String list(String path, boolean followLinks) throws IOException {
        if (idle != null) idle.cancel(false);
        if (session != null && (!session.process.isAlive()
                || SystemClock.elapsedRealtime() - session.created >= 30000)) close();
        try {
            if (session == null) session = new Session();
            return session.list(FileStore.rootListCommand(path, followLinks));
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
            process = RootShell.command("exec /system/bin/sh").redirectErrorStream(true).start();
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
