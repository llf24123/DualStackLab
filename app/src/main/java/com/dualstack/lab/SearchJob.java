package com.dualstack.lab;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

final class SearchJob {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile SearchJob active;
    private final List<FileStore.Node> matches = new ArrayList<>();
    private final Set<Runnable> listeners = new HashSet<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean pending = new AtomicBoolean();
    final String query;
    private int scanned, denied;
    private boolean finished;
    private String error;
    private long lastPublish;
    record Snapshot(List<FileStore.Node> matches, int scanned, int denied, boolean finished,
                    boolean cancelled, String error) { }
    private SearchJob(String query) { this.query = query; }
    static SearchJob active() { return active; }
    static SearchJob start(Context context, FileStore.Node root, String query, Pattern pattern, boolean contents) {
        if (active != null) active.cancel();
        SearchJob job = new SearchJob(query); active = job;
        FileStore store = new FileStore(context.getApplicationContext());
        WORKER.execute(() -> job.search(store, root, pattern, contents));
        return job;
    }
    synchronized Snapshot snapshot() {
        return new Snapshot(new ArrayList<>(matches), scanned, denied, finished, cancelled.get(), error);
    }
    synchronized void listen(Runnable listener) { listeners.add(listener); }
    synchronized void unlisten(Runnable listener) { listeners.remove(listener); }
    void cancel() { cancelled.set(true); publish(true); }
    private void search(FileStore store, FileStore.Node root, Pattern pattern, boolean contents) {
        ArrayDeque<FileStore.Node> queue = new ArrayDeque<>(); queue.add(root);
        Set<String> visited = new HashSet<>();
        try {
            while (!queue.isEmpty() && !cancelled.get()) {
                FileStore.Node directory = queue.remove();
                List<FileStore.Node> children;
                try {
                    String key = directory.file != null ? directory.file.getCanonicalPath() : directory.key();
                    if (!visited.add(key)) continue;
                    children = store.list(directory);
                } catch (IOException | SecurityException deniedDirectory) {
                    synchronized (this) { denied++; }
                    publish(false); continue;
                }
                for (FileStore.Node node : children) {
                    if (cancelled.get()) break;
                    if (node.directory) queue.add(node);
                    boolean match = !contents && pattern.matcher(node.name).find();
                    if (contents && !node.directory && MainActivity.isText(node.name)) {
                        try (InputStream input = store.read(node)) {
                            match = pattern.matcher(TextCodec.decode(FileStore.readBytes(input, 2 * 1024 * 1024), null).text()).find();
                        } catch (IOException | SecurityException deniedFile) { synchronized (this) { denied++; } }
                    }
                    synchronized (this) {
                        scanned++;
                        if (match) matches.add(node);
                        if (matches.size() >= 2000) { queue.clear(); break; }
                    }
                    publish(false);
                }
            }
        } catch (Exception failure) { synchronized (this) { error = failure.getMessage(); } }
        finally { synchronized (this) { finished = true; } publish(true); }
    }
    private void publish(boolean immediate) {
        long now = SystemClock.uptimeMillis();
        if (!immediate && now - lastPublish < 100) return;
        lastPublish = now;
        if (!pending.compareAndSet(false, true)) return;
        MAIN.post(() -> {
            pending.set(false);
            List<Runnable> observers;
            synchronized (this) { observers = new ArrayList<>(listeners); }
            for (Runnable observer : observers) observer.run();
        });
    }
}
