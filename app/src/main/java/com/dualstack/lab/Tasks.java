package com.dualstack.lab;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.Toast;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

final class Tasks {
    static final ExecutorService IO = Executors.newFixedThreadPool(3);
    static final ExecutorService BROWSE = Executors.newFixedThreadPool(2);
    static final ExecutorService SHELL = Executors.newCachedThreadPool();
    static final ExecutorService CONTROL = Executors.newCachedThreadPool();
    interface Job<T> { T run() throws Exception; }
    interface Done<T> { void accept(T value); }
    static <T> void run(Activity a, Job<T> work, Done<T> done) {
        run(a, work, done, e -> showError(a, e));
    }
    static <T> void run(Activity a, Job<T> work, Done<T> done, Done<Exception> failed) {
        run(a, IO, work, done, failed);
    }
    static <T> void run(Activity a, Executor executor, Job<T> work, Done<T> done, Done<Exception> failed) {
        executor.execute(() -> {
            try {
                T value = work.run();
                a.runOnUiThread(() -> { if (!a.isFinishing() && !a.isDestroyed()) done.accept(value); });
            } catch (Exception e) {
                a.runOnUiThread(() -> { if (!a.isFinishing() && !a.isDestroyed()) failed.accept(e); });
            }
        });
    }
    static void showError(Activity a, Exception e) {
        new AlertDialog.Builder(a).setTitle("操作失败")
                .setMessage(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
                .setPositiveButton("确定", null).show();
    }
    static void toast(Activity a, String text) { Toast.makeText(a, text, Toast.LENGTH_SHORT).show(); }
    private Tasks() {}
}
