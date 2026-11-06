package com.dualstack.lab;

import android.content.Context;
import androidx.lifecycle.ViewModel;
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
    void saved() throws IOException {
        try { drafts.clear().get(); memory = null; baseline = null; }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
        catch (ExecutionException failed) { throw new IOException("清理已保存草稿失败", failed.getCause()); }
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
