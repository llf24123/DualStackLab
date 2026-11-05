package com.dualstack.lab;

import android.app.AlertDialog;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

public class TerminalActivity extends LabActivity {
    private TerminalView terminal;
    private TerminalSession session;
    private ExecutableFile.Prepared prepared;
    private File pidFile;
    private volatile File outputLog;
    private TextView loading;
    private String cwd, arguments = "", introduction = "";
    private boolean useRoot, pipe, preparing, disposed, ctrl, alt, stopped;
    private int terminalTextSize;
    private TextView controlKey, altKey;
    private LinearLayout firstKeys, secondKeys;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Callbacks callbacks = new Callbacks();
    private final ExecutorService control = Executors.newSingleThreadExecutor();
    private final Runnable timeout = () -> stopSession();

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        cwd = getIntent().getStringExtra("cwd");
        if (cwd == null) cwd = getFilesDir().getAbsolutePath();
        useRoot = getIntent().getBooleanExtra("executionRoot", getIntent().getBooleanExtra("root", false));
        pipe = getIntent().getBooleanExtra("pipe", false);
        LinearLayout body = Ui.column(this);
        FrameLayout display = new FrameLayout(this);
        terminal = new TerminalView(this, null);
        terminal.setTerminalViewClient(callbacks);
        terminalTextSize = Math.max(10, Math.min(30,
                getSharedPreferences("terminal-appearance", 0).getInt("textSize", 13)));
        applyTerminalTextSize();
        terminal.setTypeface(Typeface.MONOSPACE);
        terminal.setBackgroundColor(Color.BLACK);
        terminal.setContentDescription("终端输入与输出");
        terminal.setFocusableInTouchMode(true);
        terminal.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View view, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(view, info);
                info.setContentDescription("终端输入与输出");
                info.setText(terminalText());
            }
        });
        display.addView(terminal, new FrameLayout.LayoutParams(-1, -1));
        loading = Ui.text(this, "读取执行文件…", 15, 0xffdddddd);
        loading.setTypeface(Typeface.MONOSPACE);
        loading.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), 0, 0);
        display.addView(loading, new FrameLayout.LayoutParams(-1, -2));
        body.addView(display, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout first = Ui.row(this), second = Ui.row(this);
        firstKeys = first; secondKeys = second;
        key(first, "Esc", "终端 Esc", KeyEvent.KEYCODE_ESCAPE);
        key(first, "Tab", "终端 Tab", KeyEvent.KEYCODE_TAB);
        key(first, "PgUp", "终端 Page Up", KeyEvent.KEYCODE_PAGE_UP);
        key(first, "Home", "终端 Home", KeyEvent.KEYCODE_MOVE_HOME);
        key(first, "▲", "终端向上", KeyEvent.KEYCODE_DPAD_UP);
        key(first, "End", "终端 End", KeyEvent.KEYCODE_MOVE_END);
        ImageView menu = Ui.icon(this, R.drawable.ic_more, "终端菜单", 0xffdddddd, () -> menu());
        first.addView(menu, new LinearLayout.LayoutParams(0, -1, 1));
        controlKey = button(second, "Ctrl", "终端 Ctrl", () -> { ctrl = !ctrl; modifiers(); });
        altKey = button(second, "Alt", "终端 Alt", () -> { alt = !alt; modifiers(); });
        key(second, "PgDn", "终端 Page Down", KeyEvent.KEYCODE_PAGE_DOWN);
        key(second, "◀", "终端向左", KeyEvent.KEYCODE_DPAD_LEFT);
        key(second, "▼", "终端向下", KeyEvent.KEYCODE_DPAD_DOWN);
        key(second, "▶", "终端向右", KeyEvent.KEYCODE_DPAD_RIGHT);
        button(second, "IM", "终端输入法", this::showKeyboard);
        body.addView(first, new LinearLayout.LayoutParams(-1, keyHeight()));
        body.addView(second, new LinearLayout.LayoutParams(-1, keyHeight()));
        Ui.install(this, body);
        body.setBackgroundColor(Color.BLACK);
        body.getChildAt(0).setBackgroundColor(Color.BLACK);
        blackBars();
        prepare();
    }

    private TextView button(LinearLayout row, String text, String description, Runnable action) {
        TextView key = Ui.text(this, text, 14, 0xffdddddd);
        key.setGravity(Gravity.CENTER); key.setContentDescription(description); key.setTooltipText(description);
        key.setClickable(true); key.setFocusable(false);
        key.setOnClickListener(view -> action.run());
        key.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0xff444444), null, null));
        row.addView(key, new LinearLayout.LayoutParams(0, -1, 1));
        return key;
    }
    private void key(LinearLayout row, String text, String description, int keyCode) {
        button(row, text, description, () -> {
            terminal.requestFocus();
            terminal.onKeyDown(keyCode, new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
            terminal.onKeyUp(keyCode, new KeyEvent(KeyEvent.ACTION_UP, keyCode));
        });
    }
    private void modifiers() {
        controlKey.setTextColor(ctrl ? 0xff72cdb7 : 0xffdddddd); controlKey.setSelected(ctrl);
        altKey.setTextColor(alt ? 0xff72cdb7 : 0xffdddddd); altKey.setSelected(alt);
    }
    private void blackBars() {
        getWindow().setStatusBarColor(Color.BLACK); getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().getDecorView().setSystemUiVisibility(0);
        if (Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);
        if (Build.VERSION.SDK_INT >= 30) getWindow().getInsetsController().setSystemBarsAppearance(0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
    }
    @Override protected void onPaletteChanged() { blackBars(); }
    private int keyHeight() {
        return Ui.dp(this, getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE ? 36 : 48);
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        firstKeys.getLayoutParams().height = secondKeys.getLayoutParams().height = keyHeight();
        firstKeys.requestLayout(); secondKeys.requestLayout();
    }
    private void showKeyboard() {
        terminal.requestFocus();
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(terminal, InputMethodManager.SHOW_IMPLICIT);
    }

    private void prepare() {
        preparing = true;
        Tasks.run(this, () -> {
            if (useRoot) RootShell.capabilities();
            ExecutableFile.Prepared file = ExecutableFile.prepare(this, new FileStore(this).fromIntent(getIntent()),
                    getIntent().getBooleanExtra("forceScript", false));
            synchronized (this) {
                if (disposed) { file.close(); return null; }
                prepared = file;
            }
            return file;
        }, file -> {
            if (file == null) return;
            if (file.elf() != null) {
                pipe = false;
                if (!useRoot) useRoot = rootAvailable();
            }
            preparing = false; runFile();
        }, error -> {
            preparing = false;
            new AlertDialog.Builder(this).setTitle("无法执行")
                    .setMessage(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage())
                    .setPositiveButton("确定", (dialog, which) -> finish()).setOnDismissListener(dialog -> {
                        if (!isFinishing()) finish();
                    }).show();
        });
    }
    private boolean rootAvailable() {
        try {
            ShellRunner.Result result = new ShellRunner().run("/system/bin/toybox id -u", null, true, 5000, null, false);
            return result.exitCode() == 0 && result.output().trim().equals("0");
        } catch (Exception unavailable) { return false; }
    }
    private void runFile() {
        if (preparing || running() || prepared == null) return;
        if (prepared.elf() != null && prepared.elf().needsRoot() && !useRoot) {
            startSession("printf '%s\\n' " + FileStore.quote("此 ELF 需要 ROOT 权限，请在执行设置中开启 ROOT") + "; exit 1", "");
            return;
        }
        String command = prepared.command(useRoot, pipe) + arguments;
        startSession(command, prepared.originalPath() + arguments);
    }
    private void startSession(String command, String displayCommand) {
        try {
            if (pidFile != null) Files.deleteIfExists(pidFile.toPath());
            pidFile = File.createTempFile("terminal-pid-", ".txt", getCacheDir());
            String wrapper = "echo $$ > " + FileStore.quote(pidFile.getAbsolutePath()) + "\n"
                    + "export TERM=xterm-256color COLORTERM=truecolor LANG=C.UTF-8\n"
                    + "cd " + FileStore.quote(cwd) + " || exit\n" + command;
            String launch = useRoot ? "exec " + RootShell.shellCommand(wrapper) : wrapper;
            Map<String, String> env = new LinkedHashMap<>(System.getenv());
            env.put("PATH", "/system/bin:/system/xbin:" + env.getOrDefault("PATH", ""));
            env.put("TERM", "xterm-256color"); env.put("COLORTERM", "truecolor"); env.put("LANG", "C.UTF-8");
            String[] environment = env.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toArray(String[]::new);
            stopped = false;
            outputLog = null;
            introduction = displayCommand.isEmpty() ? "" : "$ " + displayCommand + "\r\n";
            session = new TerminalSession("/system/bin/sh", getFilesDir().getAbsolutePath(),
                    new String[]{"/system/bin/sh", "-c", launch}, environment, 10000, callbacks);
            terminal.attachSession(session);
            loading.setVisibility(View.GONE); terminal.requestFocus();
            handler.removeCallbacks(timeout); handler.postDelayed(timeout, 30 * 60 * 1000L);
        } catch (Exception error) {
            loading.setText("无法启动终端：" + error.getMessage()); loading.setVisibility(View.VISIBLE);
        }
    }
    private boolean running() { return session != null && session.isRunning(); }
    private void append(String text) {
        if (session != null && session.getEmulator() != null) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            session.getEmulator().append(bytes, bytes.length); terminal.onScreenUpdated();
        }
    }
    String terminalText() {
        return session == null || session.getEmulator() == null ? "" : session.getEmulator().getScreen().getTranscriptText();
    }
    TerminalView terminalView() { return terminal; }
    int terminalTextSize() { return terminalTextSize; }
    private void applyTerminalTextSize() {
        terminal.setTextSize(Math.round(terminalTextSize * getResources().getDisplayMetrics().scaledDensity));
        terminal.invalidate();
    }
    private void finished(TerminalSession finished) {
        if (disposed || finished != session) return;
        handler.removeCallbacks(timeout);
        int exit = finished.getExitStatus();
        String upstream = "[Process completed" + (exit > 0 ? " (code " + exit + ")" : exit < 0 ? " (signal " + -exit + ")" : "")
                + " - press Enter]";
        int lines = (upstream.length() - 1) / finished.getEmulator().mColumns;
        append("\r" + (lines > 0 ? "\u001b[" + lines + "A" : "") + "\u001b[J"
                + (stopped ? "已停止" : "exit " + exit) + "\r\n\r\n[进程已结束 - 按回车关闭]");
        String text = terminalText();
        Tasks.IO.execute(() -> {
            try {
                File log = File.createTempFile("terminal-output-", ".txt", getFilesDir());
                Files.write(log.toPath(), text.getBytes(StandardCharsets.UTF_8));
                handler.post(() -> { if (!disposed && finished == session) outputLog = log; });
            } catch (IOException ignored) { }
        });
    }
    private void stopSession() {
        TerminalSession current = session;
        if (current == null || !current.isRunning()) return;
        stopped = true;
        File marker = pidFile;
        boolean root = useRoot;
        control.execute(() -> terminate(current, marker, root));
    }
    private void terminate(TerminalSession current, File marker, boolean root) {
        if (current == null || !current.isRunning()) return;
        try {
            for (int attempt = 0; marker != null && marker.length() == 0 && current.isRunning() && attempt < 10; attempt++)
                Thread.sleep(25);
            String pid = marker == null ? "" : new String(Files.readAllBytes(marker.toPath()), StandardCharsets.US_ASCII).trim();
            int nativePid = current.getPid();
            if (!pid.matches("[1-9][0-9]*")) pid = "";
            if (nativePid > 0) {
                // Magisk and interactive shells can create separate groups. Capture descendants before killing leaders.
                String command = "table=$(/system/bin/toybox ps -A -o PID,PPID)\n"
                        + "collect() { echo \"$1\"; printf '%s\\n' \"$table\" | while read child parent; do "
                        + "if [ \"$parent\" = \"$1\" ]; then collect \"$child\"; fi; done; }\n"
                        + "pids=$(collect " + nativePid + (pid.isEmpty() || pid.equals("" + nativePid) ? "" : "; collect " + pid) + ")\n"
                        + "/system/bin/toybox kill -KILL -- -" + nativePid + " $pids 2>/dev/null; true";
                java.lang.Process killer = (root ? RootShell.command(command)
                        : new ProcessBuilder("/system/bin/sh", "-c", command)).start();
                killer.getOutputStream().close();
                if (!killer.waitFor(3, TimeUnit.SECONDS)) killer.destroyForcibly();
            }
        } catch (Exception ignored) { }
        current.finishIfRunning();
    }
    private void menu() {
        new AlertDialog.Builder(this).setItems(new String[]{"执行设置", "重新执行", "停止", "结束输入", "复制输出", "粘贴", "清屏", "打开输出日志", "关闭"},
                (dialog, index) -> {
                    switch (index) {
                        case 0 -> settings();
                        case 1 -> { if (running()) Tasks.toast(this, "请先停止当前进程"); else runFile(); }
                        case 2 -> stopSession();
                        case 3 -> { if (running() && !pipe) session.writeCodePoint(false, 4); }
                        case 4 -> copy(terminalText());
                        case 5 -> paste();
                        case 6 -> append("\u001b[2J\u001b[H");
                        case 7 -> {
                            if (outputLog == null) { Tasks.toast(this, "进程结束后可打开输出日志"); return; }
                            Intent intent = new Intent(this, EditorActivity.class); FileStore.local(outputLog).put(intent); startActivity(intent);
                        }
                        case 8 -> handleBack();
                    }
                }).show();
    }
    private void settings() {
        if (running()) { Tasks.toast(this, "请先停止当前进程"); return; }
        LinearLayout body = Ui.column(this); body.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        EditText args = Ui.input(this, "执行参数"); args.setText(arguments.trim());
        EditText location = Ui.input(this, "工作目录"); location.setText(cwd);
        CheckBox root = new CheckBox(this); root.setText("使用 ROOT 执行"); root.setChecked(useRoot);
        body.addView(args); body.addView(location); body.addView(root);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("执行设置").setView(body)
                .setNegativeButton("取消", null).setPositiveButton("执行", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(-1).setOnClickListener(view -> {
            String path = location.getText().toString().trim();
            if (!new File(path).isAbsolute()) { location.setError("请输入绝对路径"); return; }
            cwd = path; arguments = args.getText().toString().trim();
            if (!arguments.isEmpty()) arguments = " " + arguments;
            useRoot = root.isChecked(); dialog.dismiss(); runFile();
        }));
        dialog.show();
    }
    private void copy(String text) {
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("终端", text));
    }
    private void paste() {
        if (!running() || pipe) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = clipboard.getPrimaryClip();
        if (clip != null && clip.getItemCount() > 0) session.getEmulator().paste(clip.getItemAt(0).coerceToText(this).toString());
    }
    @Override protected void handleBack() {
        if (!running()) { finish(); return; }
        new AlertDialog.Builder(this).setTitle("停止正在运行的程序？").setNegativeButton("继续运行", null)
                .setPositiveButton("停止并退出", (dialog, index) -> { stopSession(); finish(); }).show();
    }
    @Override protected void onDestroy() {
        handler.removeCallbacks(timeout);
        ExecutableFile.Prepared file;
        synchronized (this) { disposed = true; file = prepared; prepared = null; }
        File marker = pidFile;
        TerminalSession current = session;
        boolean root = useRoot;
        control.execute(() -> {
            terminate(current, marker, root);
            try {
                if (file != null) file.close();
                if (marker != null) Files.deleteIfExists(marker.toPath());
            } catch (Exception ignored) { }
        });
        control.shutdown();
        super.onDestroy();
    }

    private final class Callbacks implements TerminalSessionClient, TerminalViewClient {
        @Override public void onTextChanged(TerminalSession changed) { if (!disposed) terminal.onScreenUpdated(); }
        @Override public void onTitleChanged(TerminalSession changed) { }
        @Override public void onSessionFinished(TerminalSession finishedSession) { finished(finishedSession); }
        @Override public void onCopyTextToClipboard(TerminalSession from, String text) { copy(text); }
        @Override public void onPasteTextFromClipboard(TerminalSession from) { paste(); }
        @Override public void onBell(TerminalSession from) { }
        @Override public void onColorsChanged(TerminalSession from) { if (!disposed) terminal.invalidate(); }
        @Override public void onTerminalCursorStateChange(boolean state) { }
        @Override public void setTerminalShellPid(TerminalSession from, int pid) { }
        @Override public Integer getTerminalCursorStyle() { return 0; }
        @Override public float onScale(float scale) {
            if (!Float.isFinite(scale) || scale <= 0) return 1f;
            if (scale > 1.04f || scale < .96f) {
                int next = Math.max(10, Math.min(30, terminalTextSize + (scale > 1 ? 1 : -1)));
                if (next != terminalTextSize) {
                    terminalTextSize = next; applyTerminalTextSize();
                    getSharedPreferences("terminal-appearance", 0).edit().putInt("textSize", next).apply();
                }
                return 1f;
            }
            return scale;
        }
        @Override public void onSingleTapUp(MotionEvent event) { showKeyboard(); }
        @Override public boolean shouldBackButtonBeMappedToEscape() { return false; }
        @Override public boolean shouldEnforceCharBasedInput() { return false; }
        @Override public boolean shouldUseCtrlSpaceWorkaround() { return false; }
        @Override public boolean isTerminalViewSelected() { return true; }
        @Override public void copyModeChanged(boolean copying) { }
        @Override public boolean onKeyDown(int keyCode, KeyEvent event, TerminalSession from) {
            if (session != null && !running()) {
                if (keyCode == KeyEvent.KEYCODE_ENTER) finish();
                return keyCode != KeyEvent.KEYCODE_BACK;
            }
            return pipe && keyCode != KeyEvent.KEYCODE_BACK;
        }
        @Override public boolean onKeyUp(int keyCode, KeyEvent event) { ctrl = alt = false; modifiers(); return false; }
        @Override public boolean onLongPress(MotionEvent event) { return false; }
        @Override public boolean readControlKey() { return ctrl; }
        @Override public boolean readAltKey() { return alt; }
        @Override public boolean readShiftKey() { return false; }
        @Override public boolean readFnKey() { return false; }
        @Override public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession from) {
            ctrl = alt = false; modifiers();
            if (!running()) {
                // IMEs can encode Enter as Ctrl+M or Ctrl+J before invoking this callback.
                if (codePoint == 10 || codePoint == 13 || ctrlDown && (codePoint == 'm' || codePoint == 'j')) finish();
                return true;
            }
            return pipe;
        }
        @Override public void onEmulatorSet() {
            if (!introduction.isEmpty()) { String text = introduction; introduction = ""; append(text); }
        }
        @Override public void logError(String tag, String message) { android.util.Log.e(tag, message); }
        @Override public void logWarn(String tag, String message) { android.util.Log.w(tag, message); }
        @Override public void logInfo(String tag, String message) { android.util.Log.i(tag, message); }
        @Override public void logDebug(String tag, String message) { android.util.Log.d(tag, message); }
        @Override public void logVerbose(String tag, String message) { android.util.Log.v(tag, message); }
        @Override public void logStackTraceWithMessage(String tag, String message, Exception error) { android.util.Log.e(tag, message, error); }
        @Override public void logStackTrace(String tag, Exception error) { android.util.Log.e(tag, error.getMessage(), error); }
    }
}
