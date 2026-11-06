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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SearchJob {
    static final int LIMIT = 2000;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile SearchJob active;
    private final List<FileStore.Node> matches = new ArrayList<>();
    private final Map<String, Hit> hits = new HashMap<>();
    private final Set<Runnable> listeners = new HashSet<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean pending = new AtomicBoolean();
    final String query;
    final FileStore.Node root;
    final boolean contents, hidden;
    final Pattern pattern;
    private int scanned, denied;
    private boolean finished;
    private String error;
    private long lastPublish;
    /** Where a match sits: {@code line} is 1-based for content hits and 0 for name hits; start/end index {@code text}. */
    record Hit(int line, String text, int start, int end) {}
    record Snapshot(List<FileStore.Node> matches, int scanned, int denied, boolean finished,
                    boolean cancelled, String error) { }
    private SearchJob(String query, FileStore.Node root, Pattern pattern, boolean contents, boolean hidden) {
        this.query = query; this.root = root; this.pattern = pattern; this.contents = contents; this.hidden = hidden;
    }
    static SearchJob active() { return active; }
    static SearchJob start(Context context, FileStore.Node root, String query, Pattern pattern, boolean contents) {
        return start(context, root, query, pattern, contents, true);
    }
    static SearchJob start(Context context, FileStore.Node root, String query, Pattern pattern, boolean contents, boolean hidden) {
        if (active != null) active.cancel();
        SearchJob job = new SearchJob(query, root, pattern, contents, hidden); active = job;
        FileStore store = new FileStore(context.getApplicationContext());
        WORKER.execute(() -> job.search(store, root, pattern, contents, hidden));
        return job;
    }
    synchronized Snapshot snapshot() {
        return new Snapshot(new ArrayList<>(matches), scanned, denied, finished, cancelled.get(), error);
    }
    synchronized Hit hit(FileStore.Node node) { return hits.get(node.key()); }
    synchronized void listen(Runnable listener) { listeners.add(listener); }
    synchronized void unlisten(Runnable listener) { listeners.remove(listener); }
    void cancel() { cancelled.set(true); publish(true); }
    private void search(FileStore store, FileStore.Node root, Pattern pattern, boolean contents, boolean hidden) {
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
                    if (!hidden && node.name.startsWith(".")) continue;
                    if (node.directory) queue.add(node);
                    Hit hit = null;
                    if (!contents) {
                        Matcher m = pattern.matcher(node.name);
                        if (m.find()) hit = new Hit(0, node.name, m.start(), m.end());
                    } else if (!node.directory && MainActivity.isText(node.name)) {
                        try (InputStream input = store.read(node)) {
                            hit = contentHit(pattern, TextCodec.decode(FileStore.readBytes(input, 2 * 1024 * 1024), null).text());
                        } catch (IOException | SecurityException deniedFile) { synchronized (this) { denied++; } }
                    }
                    synchronized (this) {
                        scanned++;
                        if (hit != null) { matches.add(node); hits.put(node.key(), hit); }
                        if (matches.size() >= LIMIT) { queue.clear(); break; }
                    }
                    publish(false);
                }
            }
        } catch (Exception failure) { synchronized (this) { error = failure.getMessage(); } }
        finally { synchronized (this) { finished = true; } publish(true); }
    }
    /** The first matching line, trimmed around the match so it fits one result row. */
    static Hit contentHit(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        if (!m.find()) return null;
        int lineStart = text.lastIndexOf('\n', m.start() - 1) + 1;
        int lineEnd = text.indexOf('\n', m.start());
        if (lineEnd < 0) lineEnd = text.length();
        int line = 1;
        for (int i = 0; i < lineStart; i++) if (text.charAt(i) == '\n') line++;
        int from = Math.max(lineStart, m.start() - 40);
        int to = Math.min(lineEnd, Math.min(from + 160, Math.max(m.end(), m.start() + 1) + 60));
        String prefix = from > lineStart ? "…" : "", suffix = to < lineEnd ? "…" : "";
        StringBuilder snippet = new StringBuilder(prefix);
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            snippet.append(c < 32 || c == 0x7f ? ' ' : c);
        }
        snippet.append(suffix);
        int start = prefix.length() + (m.start() - from);
        int end = Math.min(snippet.length() - suffix.length(), prefix.length() + (Math.min(m.end(), to) - from));
        return new Hit(line, snippet.toString(), start, Math.max(start, end));
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
