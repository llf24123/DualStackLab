package com.dualstack.lab;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.system.Os;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ExecutableIdentityInstrumentedTest {
    private Context context;
    private File work;

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "elf-identity-").toFile();
    }
    @After public void cleanup() throws Exception { new FileStore(context).delete(FileStore.local(work)); }

    private File probe(String name, int permissions) throws Exception {
        File file = new File(work, name);
        Context tests = InstrumentationRegistry.getInstrumentation().getContext();
        try (InputStream input = tests.getAssets().open("executable-probe/" + Build.SUPPORTED_ABIS[0])) {
            Files.copy(input, file.toPath());
        }
        Os.chmod(file.getAbsolutePath(), permissions);
        return file;
    }

    @Test public void rootAutomaticExecutionPreservesNativeSelfPathWithShExtension() throws Exception {
        assertEquals("0", FileStore.shell("id -u", true).trim());
        File source = probe("original tool's 中文.sh", 0500);
        byte[] before = Files.readAllBytes(source.toPath());
        try (ExecutableFile.Prepared prepared = ExecutableFile.prepare(context, FileStore.local(source))) {
            assertNotNull("ELF magic must override .sh suffix", prepared.elf());
            ShellRunner.Result result = new ShellRunner().run(prepared.command(true) + " --expect-self "
                    + FileStore.quote(source.getAbsolutePath()), work, true, 10000, null, false);
            assertEquals(result.output(), 0, result.exitCode());
            assertTrue(result.output(), result.output().contains("SELF_IDENTITY=1"));
        }
        assertArrayEquals(before, Files.readAllBytes(source.toPath()));
        assertEquals(0500, Os.stat(source.getAbsolutePath()).st_mode & 07777);
    }

    private ShellRunner.Result run(ExecutableFile.Prepared file, boolean root, String arguments) throws Exception {
        return new ShellRunner().run(file.command(root, false) + arguments, work, root, 10000, null, false);
    }
    @Test public void rootAutoAddsOnlyOwnerExecuteAndPreservesOriginalIdentity() throws Exception {
        File source = probe("without execute.sh", 0400);
        try (ExecutableFile.Prepared prepared = ExecutableFile.prepare(context, FileStore.local(source))) {
            ShellRunner.Result result = run(prepared, true,
                    " --expect-self " + FileStore.quote(source.getAbsolutePath()));
            assertEquals(result.output(), 0, result.exitCode());
            assertTrue(result.output(), result.output().contains("ARGV0=" + source.getAbsolutePath()));
        }
        assertEquals(0500, Os.stat(source.getAbsolutePath()).st_mode & 07777);
        assertTrue(source.isFile());
    }
    @Test public void rootAutomaticPreservesArgumentsWorkingDirectoryAndExecfn() throws Exception {
        File source = probe("native ' argument.sh", 0500);
        try (ExecutableFile.Prepared prepared = ExecutableFile.prepare(context, FileStore.local(source))) {
            for (int i = 0; i < 2; i++) {
                File expected = source;
                ShellRunner.Result result = run(prepared, true, " --expect-self "
                        + FileStore.quote(expected.getAbsolutePath()) + " " + FileStore.quote("argument ' 中文 space"));
                assertEquals(result.output(), 0, result.exitCode());
                assertTrue(result.output(), result.output().contains("ARG=argument ' 中文 space"));
                assertTrue(result.output(), result.output().contains("CWD=" + work.getAbsolutePath()));
                assertTrue(result.output(), result.output().contains("EXECFN=" + expected.getAbsolutePath()));
            }
        }
        assertTrue(source.isFile());
    }
    @Test public void noexecStorageAutomaticallyUsesExecutableLocation() throws Exception {
        File payload = probe("noexec.sh", 0500);
        File source = new File(context.getExternalFilesDir(null), "noexec-" + java.util.UUID.randomUUID() + ".sh");
        Files.copy(payload.toPath(), source.toPath());
        try (ExecutableFile.Prepared prepared = ExecutableFile.prepare(context, FileStore.local(source))) {
            ShellRunner.Result result = run(prepared, true, " --expect-self " + FileStore.quote(prepared.file().getAbsolutePath()));
            assertEquals(result.output(), 0, result.exitCode());
            assertTrue(result.output(), result.output().contains("PROBE_EXECUTED"));
        } finally { Files.deleteIfExists(source.toPath()); }
    }
    @Test public void nonzeroProgramExitDoesNotRunTheProgramAgain() throws Exception {
        for (int permissions : List.of(0400, 0500)) {
            File source = probe("exit-" + permissions + ".sh", permissions);
            File counter = new File(work, "counter-" + permissions);
            Files.write(counter.toPath(), new byte[0]);
            try (ExecutableFile.Prepared prepared = ExecutableFile.prepare(context, FileStore.local(source))) {
                ShellRunner.Result result = run(prepared, true,
                        " --counter " + FileStore.quote(counter.getAbsolutePath()) + " --exit 126");
                assertEquals(result.output(), 126, result.exitCode());
                assertEquals("RUN\n", new String(Files.readAllBytes(counter.toPath()), StandardCharsets.UTF_8));
            }
        }
    }
    @Test public void failedNativeExecRestoresOriginalPermissionsWithoutRunningStagedProgram() throws Exception {
        File source = probe("invalid-native.sh", 0600);
        try (ExecutableFile.Prepared prepared = ExecutableFile.prepare(context, FileStore.local(source))) {
            try (RandomAccessFile file = new RandomAccessFile(source, "rw")) {
                file.seek(18); file.write(new byte[]{(byte) 0xff, (byte) 0xff});
            }
            Os.chmod(source.getAbsolutePath(), 0400);
            ShellRunner.Result result = run(prepared, true, "");
            assertEquals(result.output(), 126, result.exitCode());
            assertTrue(result.output(), result.output().contains("Cannot start"));
            assertFalse(result.output(), result.output().contains("PROBE_EXECUTED"));
            assertEquals(0400, Os.stat(source.getAbsolutePath()).st_mode & 07777);
        }
    }

    @Test public void archiveElfAutomaticallyExecutesExtractedEntryOnce() throws Exception {
        File source = probe("archive tool.sh", 0500);
        File archive = new File(work, "tools.zip");
        try (java.util.zip.ZipOutputStream output = new java.util.zip.ZipOutputStream(new FileOutputStream(archive))) {
            output.putNextEntry(new java.util.zip.ZipEntry("archive tool.sh"));
            Files.copy(source.toPath(), output); output.closeEntry();
        }
        FileStore.Node node = FileStore.archiveNode(FileStore.local(archive), "archive tool.sh", false, source.length(), 0);
        try (ExecutableFile.Prepared prepared = ExecutableFile.prepare(context, node)) {
            assertNull(prepared.localPath());
            ShellRunner.Result result = run(prepared, true, "");
            assertEquals(result.output(), 0, result.exitCode());
            assertTrue(result.output(), result.output().contains("ARGV0=" + prepared.file().getAbsolutePath()));
        }
    }
    @Test public void restrictedDataAdbNativeFileKeepsItsOriginalPath() throws Exception {
        File source = probe("private.sh", 0500);
        String directory = "/data/adb/dualstack-elf-identity-" + java.util.UUID.randomUUID();
        File original = new File(directory, "private.sh");
        try {
            FileStore.shell("mkdir " + FileStore.quote(directory) + " && chmod 700 " + FileStore.quote(directory)
                    + " && cp " + FileStore.quote(source.getAbsolutePath()) + " " + FileStore.quote(original.getAbsolutePath())
                    + " && chmod 600 " + FileStore.quote(original.getAbsolutePath()), true);
            try (ExecutableFile.Prepared prepared = ExecutableFile.prepare(context, FileStore.local(original, true))) {
                ShellRunner.Result result = run(prepared, true,
                        " --expect-self " + FileStore.quote(original.getAbsolutePath()));
                assertEquals(result.output(), 0, result.exitCode());
            }
        } finally { FileStore.shell("rm -rf " + FileStore.quote(directory), true); }
    }
    @Test public void largeElfWithShSuffixIsNotTranscodedAndRetainsAllBytes() throws Exception {
        File source = probe("large native.sh", 0600);
        try (RandomAccessFile output = new RandomAccessFile(source, "rw")) {
            output.setLength(8 * 1024 * 1024);
            output.seek(output.length() - 8); output.write(new byte[]{0, 1, 2, '\r', '\n', (byte) 0xff, 3, 0});
        }
        byte[] before = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source.toPath()));
        try (ExecutableFile.Prepared prepared = ExecutableFile.prepare(context, FileStore.local(source), true)) {
            assertNotNull(prepared.elf());
            assertArrayEquals(before, MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(prepared.file().toPath())));
            ShellRunner.Result result = run(prepared, false, " --exit 17");
            assertEquals(result.output(), 17, result.exitCode());
        }
        assertArrayEquals(before, MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source.toPath())));
    }
    private Intent intent(File source, boolean root, boolean pipe) {
        Intent intent = new Intent(context, TerminalActivity.class);
        FileStore.local(source).put(intent);
        return intent.putExtra("cwd", work.getAbsolutePath()).putExtra("executionRoot", root)
                .putExtra("pipe", pipe).putExtra("forceScript", true);
    }
    private String transcript(ActivityScenario<TerminalActivity> scenario) {
        String[] text = {""}; scenario.onActivity(activity -> text[0] = activity.terminalText()); return text[0];
    }
    private void output(ActivityScenario<TerminalActivity> scenario, String marker) {
        long deadline = SystemClock.uptimeMillis() + 15000;
        String text;
        do {
            text = transcript(scenario);
            if (text.contains(marker)) return;
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("Missing " + marker + "; terminal=" + text);
    }
    @Test public void elfWithShSuffixAcceptsPtyInputDespiteSavedPipePreference() throws Exception {
        File source = probe("interactive binary.sh", 0500);
        for (boolean root : List.of(false, true)) {
            try (ActivityScenario<TerminalActivity> scenario = ActivityScenario.launch(intent(source, root, true))) {
                output(scenario, "PROBE_EXECUTED"); output(scenario, "exit 0");
                UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
                device.findObject(By.desc("终端菜单")).click();
                device.wait(Until.findObject(By.text("执行设置")), 5000).click();
                UiObject2 arguments = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5000);
                assertNotNull("执行设置 did not open", arguments);
                arguments.setText("--read");
                device.wait(Until.findObject(By.text("执行")), 5000).click();
                output(scenario, "PROBE_INPUT_READY");
                scenario.onActivity(activity -> {
                    InputConnection input = activity.terminalView().onCreateInputConnection(new EditorInfo());
                    assertNotNull(input); assertTrue(input.commitText("native 中文\n", 1));
                });
                output(scenario, "PROBE_INPUT=native 中文"); output(scenario, "exit 0");
                File evidence = new File(context.getExternalFilesDir(null), "elf-compat-0610");
                Files.createDirectories(evidence.toPath());
                Files.write(new File(evidence, "pipe-elf-" + root + ".txt").toPath(),
                        transcript(scenario).getBytes(StandardCharsets.UTF_8));
                assertTrue(device.takeScreenshot(new File(evidence, "pipe-elf-" + root + ".png")));
            }
        }
    }
    @Test public void terminalSettingsUseOneExecutionPathAndIgnoreLegacyMode() throws Exception {
        File source = probe("settings.sh", 0600);
        try (ActivityScenario<TerminalActivity> scenario = ActivityScenario.launch(intent(source, true, false)
                .putExtra("elfLaunchMode", "COPY"))) {
            output(scenario, "exit 0");
            UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            for (int i = 0; i < 2; i++) {
                device.findObject(By.desc("终端菜单")).click();
                device.wait(Until.findObject(By.text("执行设置")), 5000).click();
                assertFalse(device.hasObject(By.desc("ELF 启动方式")));
                assertFalse(device.hasObject(By.text("临时副本")));
                assertFalse(device.hasObject(By.text("原文件")));
                device.findObject(By.text("执行")).click();
                output(scenario, "exit 0");
                String text = transcript(scenario);
                assertTrue(text, text.contains("ARGV0=" + source.getAbsolutePath()));
                assertTrue(text, text.contains("SELF_IDENTITY=1"));
            }
        }
    }
}
