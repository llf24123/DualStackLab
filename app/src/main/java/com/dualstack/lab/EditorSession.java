package com.dualstack.lab;

import android.content.Context;
import androidx.lifecycle.ViewModel;
import io.github.rosemoe.sora.text.Content;
import org.json.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

public final class EditorSession extends ViewModel {
    EditorDrafts drafts;
    EditorDrafts.State memory;
    String document, baseline;
    File archive;
    volatile boolean saving;
    // Main-thread edit generation, independent of EditorDrafts' persistence/clear tokens.
    long contentRevision;
    private final Set<File> temporary = new HashSet<>();
    private final Set<File> emptyParents = new HashSet<>();
    private boolean closed, released;
    private int operations;

    public EditorSession() {}
    synchronized void initialize(Context context, String id) throws IOException {
        if (drafts == null) { drafts = new EditorDrafts(context, id); drafts.activate(); }
    }
    synchronized void restore(Context context, EditorDrafts.State state) throws IOException {
        if (drafts.id.equals(state.id())) {
            if (state.metadata().optBoolean("dirty", true)) drafts.adopt(state);
            memory = state; return;
        }
        drafts.deactivate();
        drafts = new EditorDrafts(context, state.id()); drafts.activate(); drafts.adopt(state);
        memory = state;
    }
    synchronized void begin() { operations++; }
    synchronized void end() { operations--; cleanup(); }
    synchronized void own(File file) { temporary.add(file); }
    synchronized void ownParent(File parent) { emptyParents.add(parent); }
    /** Main-thread publication: an IO success only cleans the exact edit generation it captured. */
    boolean saved(long savedRevision, String sourceRevision, long length, PagedTextFile.Page page) throws JSONException {
        baseline = sourceRevision;
        boolean clean = contentRevision == savedRevision;
        EditorDrafts.State state = memory;
        if (state != null) {
            JSONObject data = new JSONObject(state.metadata().toString()).put("baseline", baseline)
                    .put("dirty", !clean).put("time", System.currentTimeMillis());
            if (length >= 0) data.put("length", length);
            if (page != null) data.put("end", page.end());
            if (clean) discard(); // Invalidates older queued checkpoints; never wait for the draft worker here.
            memory = clean ? new EditorDrafts.State(drafts.id, state.document(), 0, data, new Content(""))
                    : drafts.remember(state.document(), data, state.content());
            if (!clean) drafts.persist(memory);
        } else if (clean) discard();
        return clean;
    }
    Future<?> discard() { memory = null; return drafts.clear(); }
    private synchronized void cleanup() {
        if (!closed || operations != 0) return;
        Set<File> files = new HashSet<>(temporary), parents = new HashSet<>(emptyParents);
        temporary.clear(); emptyParents.clear();
        if (drafts != null && !released) { released = true; EditorDrafts.WORKER.execute(drafts::deactivate); }
        Tasks.IO.execute(() -> {
            for (File file : files) try { Files.deleteIfExists(file.toPath()); } catch (IOException ignored) { }
            for (File parent : parents) try { Files.deleteIfExists(parent.toPath()); } catch (IOException ignored) { }
        });
    }
    @Override protected synchronized void onCleared() {
        closed = true;
        cleanup();
    }
}
