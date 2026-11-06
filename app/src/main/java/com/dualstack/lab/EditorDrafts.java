package com.dualstack.lab;

import android.content.Context;
import android.util.AtomicFile;
import io.github.rosemoe.sora.text.Content;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

final class EditorDrafts {
    static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Object DISK = new Object();
    private static final Map<String, Object> OWNERS = new HashMap<>();
    private final Object owner = new Object();
    private static final int MAGIC = 0x44534431;
    record State(String id, String document, long revision, JSONObject metadata, Content content) {}
    private final File directory;
    final String id;
    private long revision;
    private volatile State current;
    private volatile IOException error;

    EditorDrafts(Context context, String id) throws IOException {
        this.id = id == null ? UUID.randomUUID().toString() : UUID.fromString(id).toString();
        directory = new File(context.getFilesDir(), "editor-drafts");
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory())
            throw new IOException("无法创建草稿目录");
    }
    void activate() { synchronized (OWNERS) { OWNERS.put(id, owner); } }
    void deactivate() { synchronized (OWNERS) { OWNERS.remove(id, owner); } }
    private boolean owns() { synchronized (OWNERS) { Object holder = OWNERS.get(id); return holder == null || holder == owner; } }
    private static boolean claimed(String id) { synchronized (OWNERS) { return OWNERS.containsKey(id); } }
    State current() { return current; }
    IOException error() { return error; }
    synchronized State remember(String document, JSONObject metadata, Content content) {
        State state = new State(id, document, ++revision, metadata, content);
        current = state;
        return state;
    }
    synchronized void adopt(State state) {
        revision = Math.max(revision, state.revision());
        current = state;
    }
    Future<?> persist(State state) {
        return WORKER.submit(() -> {
            synchronized (this) { if (current != state || state.revision() != revision) return; }
            if (!owns()) return;
            try {
                synchronized (DISK) { write(new File(directory, id + ".draft"), state); }
                error = null;
            } catch (IOException failure) { error = failure; }
        });
    }
    Future<?> clear() {
        long token;
        synchronized (this) { current = null; token = ++revision; }
        return WORKER.submit(() -> {
            synchronized (this) { if (current != null || revision != token) return; }
            if (!owns()) return;
            synchronized (DISK) { new AtomicFile(new File(directory, id + ".draft")).delete(); }
            error = null;
        });
    }
    static State read(Context context, String id) throws IOException {
        UUID.fromString(id);
        synchronized (DISK) { return readFile(new File(context.getFilesDir(), "editor-drafts/" + id + ".draft")); }
    }
    /** Removes drafts no editor holds whose last checkpoint is older than {@code maxAge}; returns how many went. */
    static int prune(Context context, long maxAge) {
        File directory = new File(context.getFilesDir(), "editor-drafts");
        long cutoff = System.currentTimeMillis() - maxAge; int removed = 0;
        synchronized (DISK) {
            File[] files = directory.listFiles((dir, name) -> name.endsWith(".draft"));
            if (files == null) return 0;
            for (File file : files) {
                String id = file.getName().substring(0, file.getName().length() - ".draft".length());
                if (claimed(id)) continue;
                long time;
                try { State state = readFile(file); time = state == null ? 0 : state.metadata().optLong("time", file.lastModified()); }
                catch (IOException malformed) { time = file.lastModified(); }
                if (time < cutoff) { new AtomicFile(file).delete(); removed++; }
            }
        }
        return removed;
    }
    static State latest(Context context, String document) throws IOException {
        File directory = new File(context.getFilesDir(), "editor-drafts");
        synchronized (DISK) {
            File[] files = directory.listFiles((dir, name) -> name.endsWith(".draft") || name.endsWith(".draft.bak"));
            State latest = null;
            if (files == null) return null;
            for (File candidate : files) {
                File file = candidate.getName().endsWith(".bak")
                        ? new File(directory, candidate.getName().substring(0, candidate.getName().length() - 4)) : candidate;
                State state;
                try { state = readFile(file); } catch (IOException malformed) { continue; }
                if (state != null && !claimed(state.id()) && state.document().equals(document) && (latest == null
                        || state.metadata().optLong("time") > latest.metadata().optLong("time"))) latest = state;
            }
            return latest;
        }
    }
    private static void write(File file, State state) throws IOException {
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream stream = null;
        try {
            JSONObject header = new JSONObject().put("id", state.id()).put("document", state.document())
                    .put("revision", state.revision()).put("metadata", state.metadata());
            byte[] metadata = header.toString().getBytes(StandardCharsets.UTF_8);
            stream = atomic.startWrite();
            DataOutputStream output = new DataOutputStream(new BufferedOutputStream(stream));
            output.writeInt(MAGIC); output.writeInt(metadata.length); output.write(metadata);
            String text = state.content().toString();
            output.writeInt(text.length()); output.writeChars(text); output.flush();
            atomic.finishWrite(stream);
        } catch (Exception failure) {
            if (stream != null) atomic.failWrite(stream);
            throw failure instanceof IOException ? (IOException) failure : new IOException("草稿写入失败", failure);
        }
    }
    private static State readFile(File file) throws IOException {
        AtomicFile atomic = new AtomicFile(file);
        if (!file.exists() && !new File(file.getPath() + ".bak").exists()) return null;
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(atomic.openRead()))) {
            if (input.readInt() != MAGIC) throw new IOException("草稿格式错误");
            int headerLength = input.readInt();
            if (headerLength < 0 || headerLength > 1024 * 1024) throw new IOException("草稿元数据损坏");
            byte[] bytes = new byte[headerLength]; input.readFully(bytes);
            JSONObject header = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            int length = input.readInt();
            if (length < 0 || (long) length * 2 > file.length()) throw new IOException("草稿正文损坏");
            char[] text = new char[length];
            for (int i = 0; i < text.length; i++) text[i] = input.readChar();
            if (input.read() != -1) throw new IOException("草稿包含多余内容");
            String id = header.getString("id"); UUID.fromString(id);
            return new State(id, header.getString("document"), header.getLong("revision"),
                    header.getJSONObject("metadata"), new Content(new String(text)));
        } catch (JSONException | IllegalArgumentException malformed) { throw new IOException("草稿格式错误", malformed); }
    }
}
