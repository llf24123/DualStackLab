package com.dualstack.lab;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.system.Os;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import com.termux.terminal.TerminalSession;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.junit.Assert.*;

/** Run on an isolated device with app-authorized su to exercise the original escalation. */
@RunWith(AndroidJUnit4.class)
public class TerminalPrivilegeInstrumentedTest {
    private Context context;
    private UiDevice device;
    private File work;
    private ActivityScenario<TerminalActivity> scenario;
    private boolean expectedRoot, adoptedDocumentsPermission;
    private Uri fixtureDirectory;
    private String fixtureId;
    private final Set<File> stagedDirectories = new LinkedHashSet<>();
    private final Set<File> pidFiles = new LinkedHashSet<>();
    private final Set<File> outputLogs = new LinkedHashSet<>();

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "terminal-privilege-").toFile();
        assertTrue("The instrumentation itself must run as the app, not UID 0", android.os.Process.myUid() > 0);
        assertEquals(context.getApplicationInfo().uid, android.os.Process.myUid());
    }

    @After public void cleanup() throws Exception {
        try {
            if (scenario != null) closeTerminal();
        } finally {
            try {
                // Never reset the provider: neighbouring fixtures belong to other tests.
                if (fixtureDirectory != null)
                    assertTrue(DocumentsContract.deleteDocument(context.getContentResolver(), fixtureDirectory));
            } finally {
                if (adoptedDocumentsPermission)
                    InstrumentationRegistry.getInstrumentation().getUiAutomation().dropShellPermissionIdentity();
                for (File log : outputLogs) Files.deleteIfExists(log.toPath());
                if (work != null) remove(work.toPath());
            }
        }
    }

    private File probe(String name) throws Exception {
        File file = new File(work, name);
        Context tests = InstrumentationRegistry.getInstrumentation().getContext();
        try (InputStream input = tests.getAssets().open("executable-probe/" + Build.SUPPORTED_ABIS[0])) {
            Files.copy(input, file.toPath());
        }
        Os.chmod(file.getAbsolutePath(), 0500);
        ExecutableFile.Elf elf = ExecutableFile.inspect(file);
        assertEquals("Use the harmless, compatible dynamic PIE test asset", 3, elf.type());
        assertFalse(elf.needsRoot());
        return file;
    }

    private Bundle fixtureCall(String method, Bundle extras) {
        Bundle result = context.getContentResolver().call(
                Uri.parse("content://" + CountingDocumentsProvider.AUTHORITY), method, null, extras);
        assertNotNull(result);
        return result;
    }

    private FileStore.Node safProbe(File source) throws Exception {
        if (!adoptedDocumentsPermission) {
            // Access the existing test provider without its destructive reset/grant receiver.
            // This changes Binder permissions, not the UID inherited by the terminal process.
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .adoptShellPermissionIdentity("android.permission.MANAGE_DOCUMENTS");
            adoptedDocumentsPermission = true;
            Bundle directory = new Bundle();
            directory.putString("name", "terminal-privilege-" + UUID.randomUUID());
            directory.putBoolean("directory", true);
            fixtureId = fixtureCall("seed", directory).getString("id");
            assertNotNull(fixtureId);
            fixtureDirectory = DocumentsContract.buildDocumentUri(CountingDocumentsProvider.AUTHORITY, fixtureId);
        }
        Bundle document = new Bundle();
        document.putString("parent", fixtureId);
        document.putString("name", source.getName());
        document.putByteArray("bytes", Files.readAllBytes(source.toPath()));
        String id = fixtureCall("seed", document).getString("id");
        assertNotNull(id);
        return new FileStore(context).document(DocumentsContract.buildDocumentUri(CountingDocumentsProvider.AUTHORITY, id));
    }

    private Intent intent(FileStore.Node source, boolean root) {
        Intent intent = new Intent(context, TerminalActivity.class);
        source.put(intent);
        return intent.putExtra("cwd", work.getAbsolutePath()).putExtra("executionRoot", root)
                .putExtra("pipe", true).putExtra("forceScript", true);
    }

    private void launch(Intent intent, boolean root) {
        expectedRoot = root;
        scenario = ActivityScenario.launch(intent);
        state(); // Check at the first available UI turn, as well as every later observation.
    }

    private static Object field(TerminalActivity activity, String name) {
        try {
            Field field = TerminalActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(activity);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private record State(boolean root, boolean preparing, boolean pipe, ExecutableFile.Prepared prepared,
                         TerminalSession session, File pidFile, File outputLog, String text) {}

    private State snapshot() {
        State[] result = new State[1];
        scenario.onActivity(activity -> {
            result[0] = new State((Boolean) field(activity, "useRoot"), (Boolean) field(activity, "preparing"),
                    (Boolean) field(activity, "pipe"), (ExecutableFile.Prepared) field(activity, "prepared"),
                    (TerminalSession) field(activity, "session"), (File) field(activity, "pidFile"),
                    (File) field(activity, "outputLog"), activity.terminalText());
        });
        State state = result[0];
        if (state.prepared != null) stagedDirectories.add(state.prepared.file().getParentFile());
        if (state.pidFile != null) pidFiles.add(state.pidFile);
        if (state.outputLog != null) outputLogs.add(state.outputLog);
        return state;
    }

    private State state() {
        State state = snapshot();
        assertEquals("prepare/re-run must not change the requested execution privilege", expectedRoot, state.root);
        return state;
    }

    private static void await(String message, BooleanSupplier condition) {
        long deadline = SystemClock.uptimeMillis() + 20000;
        do {
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        fail(message);
    }

    private void output(String marker) {
        await("Missing terminal output: " + marker, () -> state().text.contains(marker));
    }

    private void completed(int exit) {
        output("exit " + exit);
        output("[进程已结束 - 按回车关闭]");
        // The activity writes output logs asynchronously; retain only this terminal's log for cleanup.
        await("Terminal output log was not published", () -> state().outputLog != null);
        assertFalse(state().session.isRunning());
    }

    private void dynamicPrepared() {
        output("PROBE_EXECUTED");
        completed(0);
        State state = state();
        assertFalse(state.preparing);
        assertNotNull(state.prepared);
        assertNotNull("ELF magic must win over .sh and forceScript", state.prepared.elf());
        assertFalse(state.prepared.elf().needsRoot());
        assertFalse("ELF must retain interactive PTY input despite pipe=true", state.pipe);
    }

    private UiObject2 item(BySelector selector) {
        UiObject2 item = device.wait(Until.findObject(selector), 10000);
        assertNotNull("Missing UI element: " + selector, item);
        return item;
    }

    private void menu(String label) {
        TerminalSession previous = state().session;
        item(By.desc("终端菜单")).click();
        item(By.text(label)).click();
        if (label.equals("重新执行")) awaitNewSession(previous);
    }

    private void awaitNewSession(TerminalSession previous) {
        await("The UI action did not start a new terminal session", () -> {
            TerminalSession current = state().session;
            return current != null && current != previous;
        });
    }

    private void readMode() {
        TerminalSession previous = state().session;
        menu("执行设置");
        assertEquals("The actual settings checkbox must agree with the intent", expectedRoot,
                item(By.text("使用 ROOT 执行")).isChecked());
        item(By.clazz("android.widget.EditText")).setText("--read");
        item(By.text("执行")).click();
        awaitNewSession(previous);
        output("PROBE_INPUT_READY");
        assertTrue(state().session.isRunning());
    }

    private void finishInput() {
        scenario.onActivity(activity -> {
            InputConnection input = activity.terminalView().onCreateInputConnection(new EditorInfo());
            assertNotNull(input);
            assertTrue(input.commitText("privilege-check\n", 1));
        });
        output("PROBE_INPUT=privilege-check");
        completed(0);
    }

    private String shell(String command, boolean root) throws IOException {
        ShellRunner.Result result = new ShellRunner().run(command, work, root, 10000, null, false);
        assertEquals(result.output(), 0, result.exitCode());
        return result.output();
    }

    private void requireSu() {
        boolean available = false;
        try {
            ShellRunner.Result result = new ShellRunner().run("/system/bin/toybox id -u", work, true, 5000, null, false);
            available = result.exitCode() == 0 && result.output().trim().equals("0");
        } catch (IOException ignored) { }
        Assume.assumeTrue("Requires app-authorized su on an isolated device; do not change device settings", available);
    }

    private void assertProbeUid(int expectedUid) throws Exception {
        State state = state();
        assertTrue("--read must hold the real ELF alive for UID inspection", state.session.isRunning());
        assertTrue(state.text.contains("PROBE_INPUT_READY"));
        int sessionPid = state.session.getPid();
        assertTrue(sessionPid > 0);
        String marker = new String(Files.readAllBytes(state.pidFile.toPath()), StandardCharsets.US_ASCII).trim();
        assertTrue("Missing terminal wrapper PID", marker.matches("[1-9][0-9]*"));

        // su implementations may detach from the PTY leader. Include the PID written inside
        // the wrapper, then follow descendants; never mistake the outer shell's UID for the ELF's.
        Set<Integer> descendants = new LinkedHashSet<>(List.of(sessionPid, Integer.parseInt(marker)));
        String table = shell("/system/bin/toybox ps -A -o PID,PPID", expectedRoot);
        boolean changed;
        do {
            changed = false;
            for (String row : table.split("\\R")) {
                String[] columns = row.trim().split("\\s+");
                if (columns.length != 2 || !columns[0].matches("[0-9]+") || !columns[1].matches("[0-9]+")) continue;
                int pid = Integer.parseInt(columns[0]), parent = Integer.parseInt(columns[1]);
                if (descendants.contains(parent)) changed |= descendants.add(pid);
            }
        } while (changed);
        StringBuilder pids = new StringBuilder();
        for (int pid : descendants) pids.append(pid).append(' ');
        String processes = shell("for pid in " + pids + "; do printf '\\nPROBE_PID=%s\\n' \"$pid\"; "
                + "/system/bin/toybox tr '\\000' '\\n' < /proc/\"$pid\"/cmdline 2>/dev/null; "
                + "/system/bin/toybox cat /proc/\"$pid\"/status 2>/dev/null; done; true", expectedRoot);
        int matched = 0;
        for (String process : processes.split("(?m)^PROBE_PID=")) {
            List<String> lines = Arrays.asList(process.split("\\R"));
            if (!lines.contains("--read") || !(lines.contains(state.prepared.file().getAbsolutePath())
                    || lines.contains(state.prepared.originalPath()))) continue;
            matched++;
            Matcher uid = Pattern.compile("(?m)^Uid:[ \\t]+(\\d+)[ \\t]+(\\d+)[ \\t]+(\\d+)[ \\t]+(\\d+)[ \\t]*$").matcher(process);
            assertTrue("Missing /proc status for the actual probe: " + process, uid.find());
            for (int i = 1; i <= 4; i++)
                assertEquals("Probe real/effective/saved/fs UID: " + process, expectedUid, Integer.parseInt(uid.group(i)));
        }
        assertEquals("Must identify exactly one target ELF, not a wrapper shell: " + processes, 1, matched);
        assertTrue(state().session.isRunning());
    }

    private void recreated() {
        State before = state();
        File oldDirectory = before.prepared.file().getParentFile();
        File oldPid = before.pidFile;
        scenario.recreate();
        state();
        await("Recreation leaked the previous prepared resources", () -> !oldDirectory.exists() && !oldPid.exists());
    }

    private void closeTerminal() {
        snapshot();
        scenario.close();
        scenario = null;
        await("Closing the terminal leaked its own staged ELF/launcher or PID marker", () ->
                stagedDirectories.stream().noneMatch(File::exists) && pidFiles.stream().noneMatch(File::exists));
    }

    @Test public void explicitRootOffLocalDynamicElfRunsAsAppUid() throws Exception {
        File source = probe("local native.elf");
        byte[] original = Files.readAllBytes(source.toPath());
        launch(intent(FileStore.local(source), false), false);
        dynamicPrepared();
        readMode();
        assertProbeUid(android.os.Process.myUid());
        finishInput();
        assertArrayEquals(original, Files.readAllBytes(source.toPath()));
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void explicitRootOffSafShRunsAsAppUidWithoutRequiringSu() throws Exception {
        launch(intent(safProbe(probe("unprivileged SAF.sh")), false), false);
        dynamicPrepared();
        assertNull(state().prepared.localPath());
        readMode();
        assertProbeUid(android.os.Process.myUid());
        finishInput();
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void suCannotPromoteExplicitRootOffSafShAfterRecreateOrRerun() throws Exception {
        requireSu();
        FileStore.Node source = safProbe(probe("SAF native ' 中文.sh"));
        // A legacy/source root flag must not override the explicit executionRoot=false.
        launch(intent(source, false).putExtra("root", true), false);
        dynamicPrepared();
        assertNull("Exercise the SAF staged-file execution path", state().prepared.localPath());
        recreated();
        dynamicPrepared();
        readMode();
        assertProbeUid(android.os.Process.myUid());
        finishInput();
        menu("重新执行");
        output("PROBE_INPUT_READY");
        assertProbeUid(android.os.Process.myUid());
        finishInput();
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void explicitRootOnSafDynamicElfStillRunsAsUidZero() throws Exception {
        requireSu();
        launch(intent(safProbe(probe("root SAF.sh")), true), true);
        dynamicPrepared();
        readMode();
        assertProbeUid(0);
        finishInput();
    }

    @Test public void legacyRootIntentStillAppliesWithoutExecutionRootExtra() throws Exception {
        requireSu();
        Intent intent = intent(FileStore.local(probe("legacy root.sh"), true), true);
        intent.removeExtra("executionRoot");
        launch(intent, true);
        dynamicPrepared();
        readMode();
        assertProbeUid(0);
        finishInput();
    }

    @Test public void rootRequiredElfOffOnlyShowsActionableErrorAcrossRerunAndRecreate() throws Exception {
        for (boolean staticLike : List.of(false, true)) {
            File source = rootRequiredProbe(staticLike);
            byte[] original = Files.readAllBytes(source.toPath());
            launch(intent(FileStore.local(source), false), false);
            blockedForRoot();
            menu("执行设置");
            assertFalse(item(By.text("使用 ROOT 执行")).isChecked());
            item(By.text("取消")).click();
            menu("重新执行");
            blockedForRoot();
            recreated();
            blockedForRoot();
            assertArrayEquals(original, Files.readAllBytes(source.toPath()));
            closeTerminal();
        }
    }

    private void blockedForRoot() {
        output("此 ELF 需要 ROOT 权限，请在执行设置中开启 ROOT");
        completed(1);
        assertTrue(state().prepared.elf().needsRoot());
        assertFalse("The target must not run when ROOT is off", state().text.contains("PROBE_EXECUTED"));
    }

    private File rootRequiredProbe(boolean staticLike) throws Exception {
        File source = probe(staticLike ? "static-guard.sh" : "nonpie-guard.sh");
        // Classification-only derivatives of the harmless asset, never executed by these tests.
        // Cover both branches of needsRoot(): ET_EXEC and missing PT_INTERP.
        Os.chmod(source.getAbsolutePath(), 0600);
        try (RandomAccessFile file = new RandomAccessFile(source, "rw")) {
            if (!staticLike) {
                file.seek(16); file.write(new byte[]{2, 0});
            } else {
                byte[] header = new byte[64]; file.readFully(header);
                ByteBuffer elf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
                boolean wide = header[4] == 2;
                long offset = wide ? elf.getLong(32) : Integer.toUnsignedLong(elf.getInt(28));
                int size = Short.toUnsignedInt(elf.getShort(wide ? 54 : 42));
                int count = Short.toUnsignedInt(elf.getShort(wide ? 56 : 44));
                boolean removed = false;
                for (int i = 0; i < count; i++) {
                    long position = offset + (long) i * size;
                    file.seek(position);
                    if (Integer.reverseBytes(file.readInt()) != 3) continue;
                    file.seek(position); file.writeInt(0); removed = true;
                }
                assertTrue("The dynamic probe must have PT_INTERP", removed);
            }
        }
        Os.chmod(source.getAbsolutePath(), 0500);
        assertTrue(ExecutableFile.inspect(source).needsRoot());
        return source;
    }

    private static void remove(Path root) throws IOException {
        if (!Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(directory); return FileVisitResult.CONTINUE;
            }
        });
    }
}
