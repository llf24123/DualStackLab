package com.dualstack.lab;

import android.content.*;
import android.os.SystemClock;
import android.view.*;
import android.view.inputmethod.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class TerminalInstrumentedTest {
    private Context context;
    private UiDevice device;
    private File work, evidence;
    private ActivityScenario<TerminalActivity> scenario;
    private String previousKeyboard;

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "terminal-review-").toFile();
        evidence = new File(context.getExternalFilesDir(null), "terminal-review-062");
        Files.createDirectories(evidence.toPath());
        previousKeyboard = device.executeShellCommand("settings get secure show_ime_with_hard_keyboard").trim();
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1");
    }
    @After public void cleanup() throws Exception {
        if (scenario != null && scenario.getState() != androidx.lifecycle.Lifecycle.State.DESTROYED) {
            scenario.onActivity(activity -> {
                try {
                    java.lang.reflect.Method stop = TerminalActivity.class.getDeclaredMethod("stopSession");
                    stop.setAccessible(true); stop.invoke(activity);
                } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            });
            scenario.close();
        }
        device.unfreezeRotation(); device.setOrientationNatural();
        device.executeShellCommand("cmd uimode night no");
        device.executeShellCommand(previousKeyboard.equals("null")
                ? "settings delete secure show_ime_with_hard_keyboard"
                : "settings put secure show_ime_with_hard_keyboard " + previousKeyboard);
        new FileStore(context).delete(FileStore.local(work));
    }
    private File script(String name, String text) throws Exception {
        File file = new File(work, name); Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8)); return file;
    }
    private void launch(File file, boolean root, boolean pipe) {
        Intent intent = new Intent(context, TerminalActivity.class);
        FileStore.local(file).put(intent);
        intent.putExtra("cwd", work.getAbsolutePath()).putExtra("executionRoot", root).putExtra("pipe", pipe);
        scenario = ActivityScenario.launch(intent);
    }
    private String text() {
        String[] text = {""}; scenario.onActivity(activity -> text[0] = activity.terminalText()); return text[0];
    }
    private void await(BooleanSupplier check) {
        long deadline = SystemClock.uptimeMillis() + 20000;
        while (SystemClock.uptimeMillis() < deadline) {
            if (check.getAsBoolean()) return;
            SystemClock.sleep(50);
        }
        String transcript = text();
        StringBuilder diagnostic = new StringBuilder("Terminal condition timed out; characters=")
                .append(transcript.length()).append("; tail=")
                .append(transcript.substring(Math.max(0, transcript.length() - 1800)));
        for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            if (!entry.getKey().getName().startsWith("TermSession")) continue;
            diagnostic.append("\n").append(entry.getKey().getName()).append(" ").append(entry.getKey().getState());
            for (StackTraceElement frame : entry.getValue()) diagnostic.append("\n  at ").append(frame);
        }
        fail(diagnostic.toString());
    }
    private void output(String value) { await(() -> text().contains(value)); }
    private void input(String value) {
        scenario.onActivity(activity -> {
            InputConnection connection = activity.terminalView().onCreateInputConnection(new EditorInfo());
            assertNotNull(connection); assertTrue(connection.commitText(value, 1));
        });
    }
    private void click(String description) {
        UiObject2 item = device.wait(Until.findObject(By.desc(description)), 10000);
        assertNotNull(description, item); item.click();
    }
    private void menu(String label) {
        click("终端菜单");
        UiObject2 item = device.wait(Until.findObject(By.text(label)), 10000);
        if (item == null) {
            try { capture("missing-menu"); } catch (Exception error) { throw new AssertionError(error); }
        }
        assertNotNull(label, item); item.click();
    }
    private void capture(String name) throws Exception {
        // Output callbacks schedule drawing; wait for UI idle before taking a display snapshot.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        device.waitForIdle(1000);
        assertTrue(device.takeScreenshot(new File(evidence, name + ".png")));
        Files.write(new File(evidence, name + ".txt").toPath(), text().getBytes(StandardCharsets.UTF_8));
        device.dumpWindowHierarchy(new File(evidence, name + ".xml"));
    }

    @Test public void scriptsAcceptUnknownBytesBinaryTailsAndUnicodeWithoutChangingOriginals() throws Exception {
        byte[] raw = "#!/system/bin/sh\n# legacy ".getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(raw); bytes.write(0x81); bytes.write(0xff);
        bytes.write("\nprintf RAW_SCRIPT_OK\nexit 0\n".getBytes(StandardCharsets.US_ASCII));
        byte[] legacy = bytes.toByteArray();
        bytes.write(new byte[]{0, 1, (byte) 0xff, '\r', '\n', 2});
        byte[] binary = bytes.toByteArray();
        try { TextCodec.decode(binary, null); fail("Regression fixture must be rejected by editor decoding"); }
        catch (IOException expected) { }
        List<byte[]> fixtures = new ArrayList<>(List.of(legacy, binary,
                "\ufeff#!/system/bin/sh\r\nprintf RAW_SCRIPT_OK\r\n".getBytes(StandardCharsets.UTF_8)));
        String unicode = "#!/system/bin/sh\r\nprintf 'RAW_SCRIPT_OK 中文'\r\n";
        for (String encoding : List.of("UTF-16LE", "UTF-16BE", "UTF-32LE", "UTF-32BE")) {
            fixtures.add(unicode.getBytes(encoding)); fixtures.add(("\ufeff" + unicode).getBytes(encoding));
        }
        for (int i = 0; i < fixtures.size(); i++) {
            File source = new File(work, "encoding-" + i + ".sh");
            byte[] original = fixtures.get(i); Files.write(source.toPath(), original);
            File staged;
            try (ExecutableFile.Prepared file = ExecutableFile.prepare(context, FileStore.local(source))) {
                staged = file.file();
                ShellRunner.Result result = new ShellRunner().run(file.command(false), work, false, 10000, null, false);
                assertEquals("Fixture " + i + ": " + result.output(), 0, result.exitCode());
                assertTrue(result.output().contains("RAW_SCRIPT_OK"));
                assertArrayEquals(original, Files.readAllBytes(source.toPath()));
                if (i == 1) assertArrayEquals(binary, Files.readAllBytes(staged.toPath()));
            }
            assertFalse(staged.exists());
        }
        File binaryScript = new File(work, "binary-tail.sh"); Files.write(binaryScript.toPath(), binary);
        launch(binaryScript, false, false); output("RAW_SCRIPT_OK"); output("exit 0"); capture("raw-script");
    }

    @Test public void scriptPtyTapKeyboardUnicodeInputExitAndEnterToClose() throws Exception {
        File source = script("interactive.sh", "[ -t 0 ] && [ -t 1 ] && printf 'PTY_READY\\n'\n"
                + "printf '请输入内容: '; read value; printf '\\nINPUT_OK=%s\\n' \"$value\"; exit 7\n");
        launch(source, false, false); output("PTY_READY"); capture("script-waiting");
        click("终端输入与输出");
        await(() -> {
            boolean[] active = {false}; scenario.onActivity(activity -> active[0] =
                    ((InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE)).isActive(activity.terminalView()));
            try { return active[0] && device.executeShellCommand("dumpsys input_method").contains("mInputShown=true"); }
            catch (IOException ignored) { return false; }
        });
        input("中文 keyboard input\n"); output("INPUT_OK=中文 keyboard input"); output("exit 7");
        output("[进程已结束 - 按回车关闭]"); capture("script-keyboard");
        input("\n");
        long deadline = SystemClock.uptimeMillis() + 10000;
        while (scenario.getState() != androidx.lifecycle.Lifecycle.State.DESTROYED && SystemClock.uptimeMillis() < deadline)
            SystemClock.sleep(50);
        assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, scenario.getState());
    }

    @Test public void elfPtyAnsiArrowKeysAndCtrlC() throws Exception {
        File elf = new File(work, "interactive.elf"); Files.copy(new File("/system/bin/sh").toPath(), elf.toPath());
        launch(elf, false, false); output("$");
        input("stty -echo -icanon min 1 time 0; printf '\\033[31mRAW_READY\\033[0m\\n'; dd bs=1 count=3 2>/dev/null | od -An -tx1; stty sane\n");
        output("RAW_READY"); click("终端向上"); output("1b 5b 41");
        assertFalse(text().contains("\u001b[31m"));
        input("trap 'printf CTRL_C_OK; exit 23' INT; printf INTERRUPT_READY; while :; do sleep 1; done\n");
        output("INTERRUPT_READY"); SystemClock.sleep(200);
        // Ctrl-C through the PTY ends the foreground process; the status it reports depends on the shell build.
        click("终端 Ctrl"); input("c"); output("CTRL_C_OK"); output("exit 23"); capture("elf-keys");
    }

    @Test public void pipeExecutionKeepsArgumentsAndOriginalDirectory() throws Exception {
        File source = script("pipe.sh", "printf 'PIPE_OK|%s|%s\\n' \"$0\" \"$1\"; pwd\n");
        launch(source, false, true); output("PIPE_OK|"); output("exit 0");
        menu("执行设置");
        UiObject2 argument = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10000);
        assertNotNull("Execution settings argument field", argument);
        argument.setText("'argument with spaces'");
        device.findObject(By.text("执行")).click(); output("|argument with spaces"); output("exit 0");
        assertTrue(text().contains(work.getAbsolutePath())); capture("pipe-settings");
    }

    @Test public void rootPtyInputAndStopKillsDescendantsAndCleansStagedFiles() throws Exception {
        Assume.assumeTrue("Requires a granted su binary", RootAccess.available());
        File childPid = new File(work, "child.pid");
        File source = script("root.sh", "id -u; [ -t 0 ] && [ -t 1 ] && printf 'ROOT_PTY\\n'\n"
                + "read value; printf 'ROOT_INPUT=%s\\n' \"$value\"\nsleep 120 &\necho $! > child.pid\nprintf CHILD_RUNNING\nwait\n");
        launch(source, true, false); output("ROOT_PTY"); input("root_input\n"); output("ROOT_INPUT=root_input"); output("CHILD_RUNNING");
        String pid = new String(Files.readAllBytes(childPid.toPath()), StandardCharsets.US_ASCII).trim();
        assertTrue(pid.matches("[1-9][0-9]*"));
        menu("停止"); output("已停止");
        await(() -> {
            try { return FileStore.shell("kill -0 " + pid + " 2>/dev/null && echo live || echo gone", true).trim().equals("gone"); }
            catch (IOException ignored) { return false; }
        });
        capture("root-stopped"); scenario.close(); scenario = null;
        File[] pending = context.getCodeCacheDir().listFiles(f -> f.getName().startsWith("executable-"));
        long until = SystemClock.uptimeMillis() + 5000;
        while (pending != null && pending.length > 0 && SystemClock.uptimeMillis() < until) {
            SystemClock.sleep(50); pending = context.getCodeCacheDir().listFiles(f -> f.getName().startsWith("executable-"));
        }
        assertNotNull(pending); assertEquals(0, pending.length);
    }

    @Test public void terminalLayoutRotationAndThemeKeepSessionAndKeyboardControls() throws Exception {
        File source = script("resize.sh", "printf RESIZE_READY; read value; printf 'RESIZE_OK=%s' \"$value\"\n");
        launch(source, false, false); output("RESIZE_READY");
        device.setOrientationLeft(); output("RESIZE_READY"); capture("landscape");
        device.executeShellCommand("cmd uimode night yes"); output("RESIZE_READY");
        device.setOrientationNatural(); click("终端输入法");
        SystemClock.sleep(500); capture("portrait-ime");
        scenario.onActivity(activity -> {
            View terminal = activity.terminalView(); assertTrue(terminal.getWidth() > 0); assertTrue(terminal.getHeight() > 0);
            assertTrue(terminal.getHeight() < activity.getWindow().getDecorView().getHeight());
        });
        input("preserved\n"); output("RESIZE_OK=preserved"); output("exit 0");
    }

    @Test public void restrictedDataAdbScriptAndRootElfExecuteInPty() throws Exception {
        Assume.assumeTrue("Requires a granted su binary", RootAccess.available());
        String directory = "/data/adb/dualstack-terminal-review-" + UUID.randomUUID();
        File original = script("private.sh", "[ -t 0 ] && printf DATA_ADB_PTY; pwd; id -u; exit 12\n");
        File elf = new File(work, "root.elf"); Files.copy(new File("/system/bin/sh").toPath(), elf.toPath());
        try {
            FileStore.shell("mkdir " + FileStore.quote(directory) + "; chmod 700 " + FileStore.quote(directory)
                    + "; cp " + FileStore.quote(original.getAbsolutePath()) + " " + FileStore.quote(directory + "/private.sh")
                    + "; chmod 600 " + FileStore.quote(directory + "/private.sh"), true);
            Intent intent = new Intent(context, TerminalActivity.class);
            FileStore.local(new File(directory, "private.sh"), true).put(intent);
            intent.putExtra("cwd", directory).putExtra("executionRoot", true);
            scenario = ActivityScenario.launch(intent); output("DATA_ADB_PTY"); output("exit 12");
            assertTrue(text().contains(directory)); capture("data-adb-script"); scenario.close(); scenario = null;
            launch(elf, true, false); output("$");
            input("[ -t 0 ] && printf 'ROOT_ELF_PTY\\n'; read value; printf 'ROOT_ELF_INPUT=%s\\n' \"$value\"; exit 11\n");
            output("ROOT_ELF_PTY"); input("elf_root\n"); output("ROOT_ELF_INPUT=elf_root"); output("exit 11"); capture("root-elf");
        } finally { FileStore.shell("rm -rf " + FileStore.quote(directory), true); }
    }

    @Test public void doubleBackLeavesProcessRunningUntilNaturalExit() throws Exception {
        File done = new File(work, "continued.txt");
        File source = script("continue.sh", "printf CONTINUE_READY\n"
                + "sleep 4\nprintf natural-exit > continued.txt\n");
        launch(source, false, false); output("CONTINUE_READY");
        com.termux.terminal.TerminalSession[] child = {null};
        scenario.onActivity(activity -> child[0] = activity.terminalView().getCurrentSession());
        device.pressBack(); SystemClock.sleep(150);
        scenario.onActivity(activity -> assertFalse(activity.isFinishing()));
        assertFalse(device.hasObject(By.text("停止正在运行的程序？")));
        device.pressBack();
        long deadline = SystemClock.uptimeMillis() + 3000;
        while (scenario.getState() != androidx.lifecycle.Lifecycle.State.DESTROYED && SystemClock.uptimeMillis() < deadline)
            SystemClock.sleep(30);
        assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, scenario.getState());
        assertTrue("Returning must keep the PTY child alive", child[0].isRunning());
        deadline = SystemClock.uptimeMillis() + 10000;
        while ((!done.exists() || child[0].isRunning()) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(30);
        assertTrue(done.exists()); assertFalse(child[0].isRunning());
        assertEquals("natural-exit", new String(Files.readAllBytes(done.toPath()), StandardCharsets.UTF_8));
        scenario = null;
    }

    @Test public void recreationReattachesTheSameProcessWithoutExecutingTwice() throws Exception {
        File source = script("recreate.sh", "printf run >> runs.txt\nprintf RECREATE_READY\nsleep 3\nprintf DONE\n");
        launch(source, false, false); output("RECREATE_READY");
        com.termux.terminal.TerminalSession[] before = {null};
        scenario.onActivity(activity -> before[0] = activity.terminalView().getCurrentSession());
        scenario.recreate();
        scenario.onActivity(activity -> assertSame(before[0], activity.terminalView().getCurrentSession()));
        output("DONE"); output("exit 0");
        assertEquals("run", new String(Files.readAllBytes(new File(work, "runs.txt").toPath()), StandardCharsets.UTF_8));
    }

    @Test public void selectionActionsAreChineseAndOutsideTapCancelsSelection() throws Exception {
        File source = script("selection.sh", "printf 'SELECT_ALPHA SELECT_BETA\\n'; sleep 10\n");
        launch(source, false, false); output("SELECT_ALPHA");
        scenario.onActivity(activity -> {
            ((ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE))
                    .setPrimaryClip(ClipData.newPlainText("测试", "粘贴测试"));
            com.termux.view.TerminalView terminal = activity.terminalView();
            long now = SystemClock.uptimeMillis();
            MotionEvent event = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 50, 20, 0);
            terminal.startTextSelectionMode(event); event.recycle();
            assertTrue(terminal.isSelectingText());
        });
        assertNotNull(device.wait(Until.findObject(By.text("复制")), 5000));
        assertNotNull(device.wait(Until.findObject(By.text("粘贴")), 5000));
        assertNotNull(device.wait(Until.findObject(By.text("完成")), 5000));
        assertFalse(device.hasObject(By.text("Copy"))); assertFalse(device.hasObject(By.text("Paste")));
        scenario.onActivity(activity -> {
            com.termux.view.TerminalView terminal = activity.terminalView();
            long now = SystemClock.uptimeMillis();
            for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP}) {
                MotionEvent event = MotionEvent.obtain(now, now + 30, action, 100, action == MotionEvent.ACTION_DOWN ? 100 : 180, 0);
                terminal.onTouchEvent(event); event.recycle();
            }
        });
        SystemClock.sleep(400);
        scenario.onActivity(activity -> {
            assertTrue(activity.terminalView().isSelectingText());
            try {
                com.termux.view.TerminalView terminal = activity.terminalView();
                java.lang.reflect.Field field = com.termux.view.TerminalView.class.getDeclaredField("mTextSelectionCursorController"); field.setAccessible(true);
                com.termux.view.textselection.TextSelectionCursorController cursor = (com.termux.view.textselection.TextSelectionCursorController) field.get(terminal);
                int[] selection = new int[4]; cursor.getSelectors(selection);
                float x = terminal.getPointX(selection[2]) + terminal.mRenderer.getFontWidth() / 2;
                float y = terminal.getPointY(selection[0]) + terminal.mRenderer.getFontLineSpacing() / 2f;
                long now = SystemClock.uptimeMillis();
                for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
                    MotionEvent event = MotionEvent.obtain(now, now + 30, action, x, y, 0);
                    terminal.onTouchEvent(event); event.recycle();
                }
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
        SystemClock.sleep(400);
        scenario.onActivity(activity -> assertTrue(activity.terminalView().isSelectingText()));
        android.graphics.Rect body = device.wait(Until.findObject(By.desc("终端输入与输出")), 5000).getVisibleBounds();
        device.click(body.centerX(), body.centerY());
        await(() -> { boolean[] selected = {true}; scenario.onActivity(a -> selected[0] = a.terminalView().isSelectingText()); return !selected[0]; });
        scenario.onActivity(activity -> {
            long now = SystemClock.uptimeMillis();
            MotionEvent event = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 50, 20, 0);
            activity.terminalView().startTextSelectionMode(event); event.recycle();
        });
        click("终端 Ctrl");
        scenario.onActivity(activity -> assertFalse(activity.terminalView().isSelectingText()));
        menu("停止"); output("已停止");
    }

    @Test public void physicalLongPressSelectsAndQuickCopyFinishesSelection() throws Exception {
        File source = script("physical-selection.sh", "printf 'LONG_PRESS_READY SELECT_WORD\\n'; sleep 20\n");
        launch(source, false, false); output("LONG_PRESS_READY");
        UiObject2 view = device.wait(Until.findObject(By.desc("终端输入与输出")), 5000);
        assertNotNull(view);
        for (String description : List.of("终端 Esc", "终端 Page Up", "终端 Home", "终端菜单", "终端 Ctrl", "终端输入法")) {
            android.graphics.Rect key = device.wait(Until.findObject(By.desc(description)), 5000).getVisibleBounds();
            assertTrue(description, key.width() >= Ui.dp(context, 48));
            assertTrue(description, key.height() >= Ui.dp(context, 48));
        }
        android.graphics.Rect bounds = view.getVisibleBounds();
        int x = bounds.left + Ui.dp(context, 24), y = bounds.top + Ui.dp(context, 30);
        device.click(x, y);
        scenario.onActivity(a -> assertFalse(a.terminalView().isSelectingText()));
        await(() -> {
            try { return device.executeShellCommand("dumpsys input_method").contains("mInputShown=true"); }
            catch (IOException ignored) { return false; }
        });
        device.pressBack(); // Hide the input method before testing the selection gesture.
        await(() -> {
            try { return !device.executeShellCommand("dumpsys input_method").contains("mInputShown=true"); }
            catch (IOException ignored) { return false; }
        });
        device.swipe(x, y, x, y, 140);
        await(() -> { boolean[] selected = {false}; scenario.onActivity(a -> selected[0] = a.terminalView().isSelectingText()); return selected[0]; });
        capture("chinese-selection");
        String[] copied = {""};
        scenario.onActivity(a -> copied[0] = a.terminalView().getSelectedText());
        device.wait(Until.findObject(By.text("复制")), 5000).click();
        await(() -> { boolean[] selected = {true}; scenario.onActivity(a -> selected[0] = a.terminalView().isSelectingText()); return !selected[0]; });
        scenario.onActivity(a -> {
            ClipboardManager clipboard = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
            assertEquals(copied[0], clipboard.getPrimaryClip().getItemAt(0).coerceToText(a).toString());
            com.termux.view.TerminalView terminal = a.terminalView();
            terminal.stopTextSelectionMode();
            long now = SystemClock.uptimeMillis();
            MotionEvent event = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 50, 20, 0);
            terminal.startTextSelectionMode(event); event.recycle();
            terminal.stopTextSelectionMode();
            assertFalse("Explicit actions must work inside the former 300 ms guard", terminal.isSelectingText());
        });
        menu("停止"); output("已停止");
    }



    @Test public void repeatedShortPtyScriptsKeepFinalOutputBeforeExitNotification() throws Exception {
        for (int i = 0; i < 30; i++) {
            String marker = "FINAL_OUTPUT_" + i;
            File source = script("short-" + i + ".sh", "printf '" + marker + "\\n'; exit 19\n");
            launch(source, false, i % 2 == 1); output("exit 19");
            assertTrue("Final PTY output lost on run " + i, text().contains(marker));
            assertTrue(text().indexOf(marker) < text().indexOf("exit 19"));
            scenario.close(); scenario = null;
        }
    }

    @Test public void largePtyOutputIsDrainedBeforeExitNotification() throws Exception {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 2500; i++) rows.append(String.format(Locale.ROOT,
                "ROW_%04d_abcdefghijklmnopqrstuvwxyz0123456789\n", i));
        rows.append("LARGE_OUTPUT_END\n");
        script("large-output.txt", rows.toString());
        // Emit beyond the queue capacity in one process; Android printf is an external
        // executable, so a shell loop would measure 2500 forks rather than PTY draining.
        File source = script("large.sh", "cat large-output.txt; exit 17\n");
        launch(source, false, false); output("exit 17");
        String transcript = text();
        for (int i = 0; i < 2500; i++)
            assertTrue("Lost output row " + i, transcript.contains(String.format(Locale.ROOT,
                    "ROW_%04d_abcdefghijklmnopqrstuvwxyz0123456789", i)));
        assertTrue(transcript.indexOf("LARGE_OUTPUT_END") < transcript.indexOf("exit 17"));
        capture("large-output");
    }

    @Test public void largePasteWhileChildDoesNotReadKeepsStopResponsive() throws Exception {
        File source = script("blocked-input.sh", "stty -echo; printf NO_STDIN_READY; sleep 120\n");
        launch(source, false, false); output("NO_STDIN_READY");
        scenario.onActivity(activity -> {
            ((ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE))
                    .setPrimaryClip(ClipData.newPlainText("large paste", "p".repeat(256 * 1024)));
        });
        menu("粘贴");
        menu("停止"); output("已停止"); capture("large-paste-stopped");
    }
}
