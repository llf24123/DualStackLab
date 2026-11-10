package com.dualstack.lab;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.lang.ref.WeakReference;

public class ShellActivity extends LabActivity {
    private EditText command, directory, input;
    private CheckBox rootMode;
    private TextView output, status;
    private ScrollView scroll;
    private View execute, stop, send, eof;
    private ShellRunner runner;
    private volatile ExecutableFile.Prepared script;
    private boolean preparing, prepareFailed;
    private volatile boolean disposed;
    private long lastBack;
    private ExecutionFiles executionFiles;
    private boolean pipeMode, autoRun;
    private String generatedCommand, restoredArguments = "";
    private final ExecutorService inputExecutor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StringBuilder pending = new StringBuilder();
    private boolean scheduled;
    private long omitted;
    private File outputLog;
    private final Runnable flush = this::flushOutput;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        pipeMode = getIntent().getBooleanExtra("pipe", false);
        autoRun = saved == null && getIntent().getBooleanExtra("autorun", false);
        LinearLayout body = Ui.column(this);
        LinearLayout top = Ui.toolbar(this, "Shell", this::leave);
        top.addView(Ui.icon(this, R.drawable.ic_file, "打开完整输出", Color.WHITE, () -> {
            if (runner != null || outputLog == null) { Tasks.toast(this, "命令结束后可打开输出日志"); return; }
            android.content.Intent intent = new android.content.Intent(this, EditorActivity.class);
            FileStore.local(outputLog).put(intent); startActivity(intent);
        }), lp(48, 52));
        top.addView(Ui.icon(this, R.drawable.ic_trash, "清空输出", Color.WHITE, () -> output.setText("")),
                lp(48, 52));
        body.addView(top, lp(-1, 52));
        LinearLayout location = Ui.row(this);
        directory = Ui.input(this, "工作目录");
        directory.setContentDescription("工作目录");
        String cwd = getIntent().getStringExtra("cwd");
        directory.setText(cwd == null ? getFilesDir().getAbsolutePath() : cwd);
        location.addView(directory, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1));
        rootMode = new CheckBox(this); rootMode.setText("ROOT");
        rootMode.setTextSize(14);
        rootMode.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 8), 0);
        rootMode.setChecked(getIntent().getBooleanExtra("executionRoot", getIntent().getBooleanExtra("root", false)));
        rootMode.setOnCheckedChangeListener((button, checked) -> {
            if (script == null || generatedCommand == null) return;
            String current = command.getText().toString();
            if (!current.startsWith(generatedCommand)) return;
            String suffix = current.substring(generatedCommand.length());
            generatedCommand = script.command(checked, pipeMode);
            command.setText(generatedCommand + suffix);
        });
        location.addView(rootMode, lp(88, 48)); body.addView(location);
        command = Ui.input(this, "Shell 命令"); command.setSingleLine(false); command.setMaxLines(4);
        command.setContentDescription("Shell 命令");
        command.setTypeface(Typeface.MONOSPACE); command.setText("pwd");
        command.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        body.addView(command, lp(-1, -2));
        LinearLayout controls = Ui.row(this);
        execute = Ui.icon(this, R.drawable.ic_play, "执行", Ui.ACCENT, this::execute);
        stop = Ui.icon(this, R.drawable.ic_stop, "停止", Ui.TEXT, this::stop);
        controls.addView(execute, lp(48, 48)); controls.addView(stop, lp(48, 48));
        status = Ui.single(this, "准备就绪", 11, Ui.MUTED);
        controls.addView(status, new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1)); body.addView(controls);
        body.addView(Ui.line(this));
        output = Ui.text(this, "", 12, Ui.TEXT); output.setTypeface(Typeface.MONOSPACE);
        output.setGravity(Gravity.TOP | Gravity.START);
        output.setTextIsSelectable(true); output.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        output.setContentDescription("Shell 输出");
        scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(output);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1)); body.addView(Ui.line(this));
        LinearLayout stdin = Ui.row(this);
        input = Ui.input(this, "标准输入"); stdin.addView(input, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1));
        input.setContentDescription("标准输入");
        send = Ui.icon(this, R.drawable.ic_send, "发送输入", Ui.ACCENT, this::sendInput);
        eof = Ui.icon(this, R.drawable.ic_check, "结束输入", Ui.TEXT, () -> {
            ShellRunner running = runner;
            if (running != null) inputExecutor.execute(() -> {
                try { running.closeInput(); } catch (IOException e) { runOnUiThread(() -> Tasks.toast(this, e.getMessage())); }
            });
        });
        stdin.addView(send, lp(48, 48)); stdin.addView(eof, lp(48, 48)); body.addView(stdin);
        ChineseTextActions.install(command, directory, input, output);
        Ui.install(this, body);
        if (saved != null) {
            directory.setText(saved.getString("cwd")); command.setText(saved.getString("command"));
            output.setText(saved.getString("output")); rootMode.setChecked(saved.getBoolean("root"));
            String log = saved.getString("log");
            if (log != null) outputLog = new File(log);
            restoredArguments = saved.getString("arguments", "");
        }
        updateControls();
        if (getIntent().getBooleanExtra("script", false)) prepareScript();
    }
    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w < 0 ? w : Ui.dp(this, w), h < 0 ? h : Ui.dp(this, h));
    }
    private void prepareScript() {
        preparing = true; status.setText("读取执行文件…"); updateControls();
        Tasks.run(this, () -> {
            FileStore store = new FileStore(this); FileStore.Node node = store.fromIntent(getIntent());
            ExecutableFile.Prepared staged = ExecutableFile.prepare(this, node, getIntent().getBooleanExtra("forceScript", false));
            synchronized (this) {
                if (disposed || isFinishing() || isDestroyed()) { staged.close(); return null; }
                script = staged;
            }
            return staged;
        }, file -> {
            if (file == null) return;
            preparing = false;
            if (file.elf() != null) pipeMode = false;
            generatedCommand = file.command(rootMode.isChecked(), pipeMode);
            command.setText(generatedCommand + restoredArguments);
            status.setText(file.status() + (pipeMode ? " · 管道执行" : "")); updateControls();
            if (autoRun) { autoRun = false; execute(); }
        }, error -> {
            preparing = false; prepareFailed = true; command.setText(""); updateControls();
            status.setText("执行文件读取失败"); Tasks.showError(this, error);
        });
    }
    private void execute() {
        if (runner != null || preparing) return;
        String text = command.getText().toString();
        if (text.trim().isEmpty()) return;
        File cwd = new File(directory.getText().toString().trim());
        if (!cwd.isAbsolute()) { directory.setError("请输入绝对路径"); return; }
        boolean useRoot = rootMode.isChecked();
        if (script != null && script.elf() != null && script.elf().needsRoot() && !useRoot) {
            Tasks.showError(this, new IOException("非 PIE 或静态 ELF 需要 ROOT；普通模式支持 Android 动态 PIE ELF"));
            return;
        }
        ShellRunner current = new ShellRunner(); runner = current;
        handler.removeCallbacks(flush);
        synchronized (pending) { pending.setLength(0); scheduled = false; omitted = 0; }
        output.setText(""); status.setText("运行中"); updateControls();
        ExecutionFiles files = executionFiles = new ExecutionFiles(script);
        File logs = getFilesDir();
        WeakReference<ShellActivity> page = new WeakReference<>(this);
        Tasks.SHELL.execute(() -> {
            File log = null; ShellRunner.Result result = null; Exception failure = null;
            try {
                log = File.createTempFile("shell-output-", ".txt", logs);
                try (Writer writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(log), StandardCharsets.UTF_8))) {
                    result = current.run(text, cwd, useRoot, Long.MAX_VALUE, chunk -> {
                        try { writer.write(chunk); }
                        catch (IOException e) { throw new UncheckedIOException(e); }
                        ShellActivity activity = page.get();
                        if (activity != null) activity.queueOutput(chunk);
                    }, true);
                }
            } catch (Exception error) { failure = error; }
            finally { files.finished(); }
            File completedLog = log; ShellRunner.Result completed = result; Exception error = failure;
            new Handler(Looper.getMainLooper()).post(() -> {
                ShellActivity activity = page.get();
                if (activity == null || activity.disposed || activity.runner != current) return;
                activity.outputLog = completedLog; activity.flushOutput(); activity.runner = null; activity.updateControls();
                if (error != null) {
                    activity.status.setText("执行失败"); activity.queueOutput(error.getMessage() + "\n");
                } else activity.status.setText(completed.timedOut() ? "运行超时" : completed.stopped() ? "已停止" : "exit " + completed.exitCode());
            });
        });
    }
    private void queueOutput(String chunk) {
        if (disposed) return;
        synchronized (pending) {
            pending.append(chunk);
            int overflow = pending.length() - 16 * 1024;
            if (overflow > 0) { pending.delete(0, overflow); omitted += overflow; }
            if (!scheduled) { scheduled = true; handler.postDelayed(flush, 60); }
        }
    }
    private void flushOutput() {
        if (disposed) return;
        if (output.hasSelection() || output.isPressed()) {
            handler.removeCallbacks(flush); handler.postDelayed(flush, 250); return;
        }
        String text;
        long dropped;
        handler.removeCallbacks(flush);
        synchronized (pending) { text = pending.toString(); dropped = omitted; omitted = 0; pending.setLength(0); scheduled = false; }
        if (isDestroyed() || text.isEmpty()) return;
        boolean bottom = !scroll.canScrollVertically(1);
        int max = 16 * 1024;
        output.append(text);
        if (output.length() > max || dropped > 0) {
            CharSequence tail = output.getText().subSequence(Math.max(0, output.length() - max), output.length());
            output.setText("[显示最近输出，完整内容已保存至日志]\n" + tail);
        }
        if (bottom) scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }
    private void sendInput() {
        ShellRunner current = runner;
        if (current == null) return;
        String text = input.getText().toString(); input.setText("");
        WeakReference<ShellActivity> page = new WeakReference<>(this);
        inputExecutor.execute(() -> {
            try { current.send(text); } catch (IOException e) {
                ShellActivity activity = page.get();
                if (activity != null) activity.queueOutput(e.getMessage() + "\n");
            }
        });
    }
    private void stop() {
        ShellRunner current = runner;
        if (current != null) { status.setText("停止中…"); Tasks.CONTROL.execute(current::stop); }
    }
    private void updateControls() {
        boolean running = runner != null;
        execute.setEnabled(!running && !preparing && !prepareFailed);
        execute.setAlpha(running || preparing || prepareFailed ? .35f : 1f);
        stop.setEnabled(running); stop.setAlpha(running ? 1f : .35f);
        send.setEnabled(running && !pipeMode); eof.setEnabled(running && !pipeMode); input.setEnabled(running && !pipeMode);
        command.setEnabled(!running && !preparing && !prepareFailed); directory.setEnabled(!running); rootMode.setEnabled(!running);
    }
    private void leave() {
        long now = SystemClock.uptimeMillis();
        if (lastBack != 0 && now - lastBack <= 2000) { finish(); return; }
        lastBack = now;
        Tasks.toast(this, runner != null ? "再返回一次回到目录，命令继续运行" : "再返回一次回到目录");
    }
    @Override protected void handleBack() { leave(); }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("cwd", directory.getText().toString()); out.putString("command", command.getText().toString());
        out.putString("output", output.getText().toString()); out.putBoolean("root", rootMode.isChecked());
        if (outputLog != null) out.putString("log", outputLog.getAbsolutePath());
        String text = command.getText().toString();
        if (generatedCommand != null && text.startsWith(generatedCommand))
            out.putString("arguments", text.substring(generatedCommand.length()));
    }
    @Override public void onDestroy() {
        ExecutableFile.Prepared old;
        synchronized (this) { disposed = true; old = script; script = null; }
        if (executionFiles != null) executionFiles.detach();
        else Tasks.IO.execute(() -> { if (old != null) try { old.close(); } catch (IOException ignored) { } });
        inputExecutor.shutdownNow();
        handler.removeCallbacks(flush);
        super.onDestroy();
    }
    private static final class ExecutionFiles {
        private final ExecutableFile.Prepared file;
        private boolean finished, detached, released;
        ExecutionFiles(ExecutableFile.Prepared file) { this.file = file; }
        synchronized void finished() { finished = true; release(); }
        synchronized void detach() { detached = true; release(); }
        private void release() {
            if (!finished || !detached || released) return;
            released = true;
            Tasks.IO.execute(() -> { if (file != null) try { file.close(); } catch (IOException ignored) { } });
        }
    }
}
