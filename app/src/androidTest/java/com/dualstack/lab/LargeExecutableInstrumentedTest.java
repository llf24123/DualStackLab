package com.dualstack.lab;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class LargeExecutableInstrumentedTest {
    private Context context;
    private File work;

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "large-exec-").toFile();
    }
    @After public void cleanup() throws Exception { new FileStore(context).delete(FileStore.local(work)); }

    private File script(String name, String encoding, boolean bom, int characters, String newline, String ending) throws Exception {
        File source = new File(work, name);
        try (Writer writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(source), Charset.forName(encoding)))) {
            if (bom) writer.write('\ufeff');
            writer.write("#!/system/bin/sh" + newline);
            String comment = "#" + " ".repeat(8190) + newline;
            for (int written = 0; written < characters; written += comment.length()) writer.write(comment);
            writer.write(ending);
        }
        assertTrue("Fixture must exceed old 4 MiB boundary", source.length() > 4L * 1024 * 1024);
        return source;
    }
    private byte[] digest(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[65536]; int count;
            while ((count = input.read(bytes)) != -1) digest.update(bytes, 0, count);
        }
        return digest.digest();
    }
    private void execute(File source, boolean pipe, boolean root) throws Exception {
        byte[] original = digest(source);
        File staged;
        try (ExecutableFile.Prepared prepared = ExecutableFile.prepare(context, FileStore.local(source))) {
            staged = prepared.file();
            ShellRunner.Result result = new ShellRunner().run(prepared.command(root, pipe)
                    + " " + FileStore.quote("large argument ' 中文"), work, root, 30000, null, false);
            assertEquals(result.output(), 19, result.exitCode());
            assertTrue(result.output(), result.output().contains("LARGE_OK|large argument ' 中文|"));
            assertTrue(result.output(), result.output().contains(source.getAbsolutePath()));
            assertTrue(result.output(), result.output().contains(work.getAbsolutePath()));
        }
        assertFalse(staged.exists()); assertFalse(staged.getParentFile().exists());
        assertArrayEquals("Source changed", original, digest(source));
    }
    private String ending(String newline) {
        return "printf 'LARGE_OK|%s|%s\\n' \"$1\" \"$0\"" + newline + "pwd" + newline + "exit 19" + newline;
    }

    @Test public void largeScriptRunsNormallyAndThroughPipe() throws Exception {
        File source = script("large script's name.sh", "UTF-8", false, 16 * 1024 * 1024, "\n", ending("\n"));
        execute(source, false, false); execute(source, true, false);
    }
    @Test public void largeCrLfAndUtf8BomScriptsRun() throws Exception {
        for (String newline : List.of("\r\n", "\r")) {
            File source = script("windows.sh", "UTF-8", true, 5 * 1024 * 1024, newline, ending(newline));
            execute(source, false, false); execute(source, true, false);
        }
    }
    @Test public void largeUnicodeScriptsRunWithAndWithoutBom() throws Exception {
        for (String encoding : List.of("UTF-16LE", "UTF-16BE", "UTF-32LE", "UTF-32BE"))
            for (boolean bom : List.of(false, true)) {
                File source = script(encoding + "-" + bom + ".sh", encoding, bom, 3 * 1024 * 1024,
                        "\r\n", ending("\r\n"));
                execute(source, bom, false);
            }
    }
    @Test public void binaryTailAfterFourMiBPreservesAllBytesAndCrLf() throws Exception {
        File source = script("payload.sh", "UTF-8", false, 5 * 1024 * 1024, "\n", ending("\n"));
        try (OutputStream out = new FileOutputStream(source, true)) { out.write(new byte[]{'\r', '\n', 0, 1, (byte) 0xff}); }
        try (ExecutableFile.Prepared prepared = ExecutableFile.prepare(context, FileStore.local(source))) {
            assertArrayEquals(digest(source), digest(prepared.file()));
        }
        execute(source, false, false); execute(source, true, false);
    }
    @Test public void invalidUnicodeFailsAndCleansPartialStaging() throws Exception {
        File source = script("broken.sh", "UTF-16LE", true, 3 * 1024 * 1024, "\n", ending("\n"));
        try (OutputStream out = new FileOutputStream(source, true)) { out.write(0x41); }
        byte[] original = digest(source);
        Set<String> before = stagingDirectories();
        try (ExecutableFile.Prepared ignored = ExecutableFile.prepare(context, FileStore.local(source))) {
            fail("Truncated Unicode accepted");
        } catch (IOException expected) {
            assertFalse(expected.getMessage().contains("4 MiB"));
        }
        assertEquals(before, stagingDirectories());
        assertArrayEquals(original, digest(source));
    }
    private Set<String> stagingDirectories() {
        String[] names = context.getCodeCacheDir().list((dir, name) -> name.startsWith("executable-"));
        assertNotNull(names); return new HashSet<>(Arrays.asList(names));
    }
    @Test public void largeLegacyScriptDoesNotRequireEditorDecoding() throws Exception {
        File source = script("legacy.sh", "UTF-8", false, 5 * 1024 * 1024, "\n", "");
        try (OutputStream out = new FileOutputStream(source, true)) {
            out.write(new byte[]{'#', (byte) 0x81, (byte) 0xff, '\n'});
            out.write(ending("\n").getBytes(StandardCharsets.UTF_8));
        }
        execute(source, false, false);
    }
    @Test public void crLfAcrossStreamBlocksIsNormalizedWithoutLosingBytes() throws Exception {
        File source = new File(work, "boundary.sh"), expected = new File(work, "expected");
        try (Writer original = new BufferedWriter(new FileWriter(source, StandardCharsets.UTF_8));
             Writer normalized = new BufferedWriter(new FileWriter(expected, StandardCharsets.UTF_8))) {
            String comment = "#" + " ".repeat(65534);
            for (int i = 0; i < 80; i++) {
                original.write(comment); original.write("\r\n");
                normalized.write(comment); normalized.write('\n');
            }
            original.write(ending("\r\n")); normalized.write(ending("\n"));
        }
        try (ExecutableFile.Prepared prepared = ExecutableFile.prepare(context, FileStore.local(source))) {
            assertArrayEquals(digest(expected), digest(prepared.file()));
        }
        execute(source, false, false); execute(source, true, false);
    }
    @Test public void rootOnlyLargeScriptCanBeReadAndExecutedFromDataAdb() throws Exception {
        assertEquals("0", FileStore.shell("id -u", true).trim());
        File source = script("private.sh", "UTF-8", false, 8 * 1024 * 1024, "\n", ending("\n"));
        String directory = "/data/adb/dualstack-large-exec-" + UUID.randomUUID();
        try {
            FileStore.shell("mkdir " + FileStore.quote(directory) + " && chmod 700 " + FileStore.quote(directory)
                    + " && cp " + FileStore.quote(source.getAbsolutePath()) + " " + FileStore.quote(directory + "/private.sh")
                    + " && chmod 600 " + FileStore.quote(directory + "/private.sh"), true);
            File privateFile = new File(directory, "private.sh");
            FileStore.Node node = FileStore.local(privateFile, true);
            try (ExecutableFile.Prepared prepared = ExecutableFile.prepare(context, node)) {
                assertArrayEquals(digest(source), digest(prepared.file()));
                for (boolean pipe : List.of(false, true)) {
                    ShellRunner.Result result = new ShellRunner().run(prepared.command(true, pipe) + " private",
                            new File(directory), true, 30000, null, false);
                    assertEquals(result.output(), 19, result.exitCode());
                    assertTrue(result.output(), result.output().contains("LARGE_OK|private|" + privateFile.getAbsolutePath()));
                    assertTrue(result.output(), result.output().contains(directory));
                }
            }
            try (InputStream input = new FileStore(context).read(node)) {
                File copy = new File(work, "read-back");
                try (OutputStream output = new FileOutputStream(copy)) { FileStore.pump(input, output); }
                assertArrayEquals(digest(source), digest(copy));
            }
        } finally { FileStore.shell("rm -rf " + FileStore.quote(directory), true); }
    }
    private File elf(int megabytes) throws Exception {
        File source = new File(work, "large.elf");
        Files.copy(new File("/system/bin/sh").toPath(), source.toPath());
        try (RandomAccessFile file = new RandomAccessFile(source, "rw")) { file.setLength(megabytes * 1024L * 1024); }
        return source;
    }
    @Test public void elfBeyondPrevious256MiBCapRunsAndRemainsUnchanged() throws Exception {
        File source = elf(257);
        byte[] original = digest(source);
        File staged;
        try (ExecutableFile.Prepared prepared = ExecutableFile.prepare(context, FileStore.local(source))) {
            staged = prepared.file(); assertEquals(source.length(), staged.length());
            assertArrayEquals(original, digest(staged));
            ShellRunner.Result result = new ShellRunner().run(prepared.command(false) + " -c 'printf LARGE_ELF_OK'",
                    work, false, 30000, null, false);
            assertEquals(result.output(), 0, result.exitCode()); assertEquals("LARGE_ELF_OK", result.output());
        }
        assertFalse(staged.exists()); assertArrayEquals(original, digest(source));
    }
    @Test public void largeRootScriptAndPipeRun() throws Exception {
        assertEquals("ROOT test environment unavailable", "0", FileStore.shell("id -u", true).trim());
        File source = script("root.sh", "UTF-8", false, 5 * 1024 * 1024, "\n", ending("\n"));
        execute(source, false, true); execute(source, true, true);
    }
    private String transcript(ActivityScenario<TerminalActivity> scenario) {
        String[] text = {""}; scenario.onActivity(activity -> text[0] = activity.terminalText()); return text[0];
    }
    private void output(ActivityScenario<TerminalActivity> scenario, String marker) {
        long deadline = SystemClock.uptimeMillis() + 30000;
        String text;
        do {
            text = transcript(scenario);
            if (text.contains(marker)) return;
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("Missing " + marker + "; terminal=" + text);
    }
    private void input(ActivityScenario<TerminalActivity> scenario, String text) {
        scenario.onActivity(activity -> {
            InputConnection connection = activity.terminalView().onCreateInputConnection(new EditorInfo());
            assertNotNull(connection); assertTrue(connection.commitText(text, 1));
        });
    }
    private Intent intent(File file, boolean root, boolean pipe) {
        Intent intent = new Intent(context, TerminalActivity.class);
        FileStore.local(file).put(intent);
        return intent.putExtra("cwd", work.getAbsolutePath()).putExtra("executionRoot", root).putExtra("pipe", pipe);
    }
    @Test public void largeScriptPtySupportsInputAndPipe() throws Exception {
        File source = script("interactive.sh", "UTF-8", false, 8 * 1024 * 1024, "\n",
                "printf LARGE_PTY_READY; read value; printf 'LARGE_INPUT=%s\\n' \"$value\"; exit 21\n");
        try (ActivityScenario<TerminalActivity> scenario = ActivityScenario.launch(intent(source, false, false))) {
            output(scenario, "LARGE_PTY_READY"); input(scenario, "大脚本 input\n");
            output(scenario, "LARGE_INPUT=大脚本 input"); output(scenario, "exit 21");
        }
        File piped = script("piped.sh", "UTF-8", false, 8 * 1024 * 1024, "\n", "printf LARGE_PIPE_PTY; exit 22\n");
        try (ActivityScenario<TerminalActivity> scenario = ActivityScenario.launch(intent(piped, false, true))) {
            output(scenario, "LARGE_PIPE_PTY"); output(scenario, "exit 22");
        }
    }
    @Test public void largeElfPtySupportsInputInOrdinaryAndRootModes() throws Exception {
        File source = elf(12);
        for (boolean root : List.of(false, true))
            try (ActivityScenario<TerminalActivity> scenario = ActivityScenario.launch(intent(source, root, false))) {
                output(scenario, "$");
                input(scenario, "printf LARGE_ELF_READY; read value; printf 'LARGE_ELF_INPUT=%s\\n' \"$value\"; exit 23\n");
                output(scenario, "LARGE_ELF_READY"); input(scenario, "elf input\n");
                output(scenario, "LARGE_ELF_INPUT=elf input"); output(scenario, "exit 23");
            }
    }
}
