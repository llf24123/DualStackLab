package com.dualstack.lab;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.reandroid.apk.ApkModule;
import com.reandroid.arsc.chunk.TableBlock;
import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.instruction.NarrowLiteralInstruction;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class CoreInstrumentedTest {
    private Context context;
    private File work;
    private FileStore store;
    private static final String CLASS = "Lfixture/Sample;";
    private static final String SMALI = ".class public Lfixture/Sample;\n"
            + ".super Ljava/lang/Object;\n.method public static value()I\n"
            + ".registers 1\nconst/4 v0, 0x1\nreturn v0\n.end method\n";

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        store = new FileStore(context);
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "review-test-").toFile();
    }
    @After public void cleanup() throws Exception { store.delete(FileStore.local(work)); }

    @Test public void archiveNodesSupportCrudTransferAndSelfCopyProtection() throws Exception {
        File zip = new File(work, "virtual.zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("implicit/note.txt")); out.write("inside".getBytes(StandardCharsets.UTF_8)); out.closeEntry();
        }
        FileStore.Node archive = FileStore.archiveNode(FileStore.local(zip), "", true, 0, 0);
        FileStore.Node implicit = store.list(archive).get(0);
        assertTrue(implicit.directory); assertNull(implicit.file);
        FileStore.Node note = store.list(implicit).get(0);
        assertEquals(archive.key(), store.parent(implicit).key());
        FileStore.Node copy = store.copy(implicit, FileStore.local(work), false);
        assertEquals("inside", new String(Files.readAllBytes(new File(copy.file, "note.txt").toPath()), StandardCharsets.UTF_8));
        File payload = new File(work, "payload.txt"); text(payload, "replaced");
        store.write(note, payload);
        assertEquals("replaced", new String(ArchiveOps.bytes(zip, "implicit/note.txt", 100), StandardCharsets.UTF_8));
        FileStore.Node renamed = store.rename(implicit, "renamed");
        assertEquals("renamed/", store.list(archive).get(0).entry);
        store.copy(FileStore.local(copy.file), archive, false);
        assertEquals(2, store.list(archive).size());
        FileStore.Node empty = store.create(archive, "empty.txt", false); store.write(empty, payload);
        assertEquals(8, ArchiveOps.bytes(zip, "empty.txt", 100).length);
        try { store.copy(renamed, renamed, false); fail("archive recursion accepted"); } catch (IOException expected) { }
        try { store.copy(FileStore.local(zip), archive, false); fail("archive self import accepted"); } catch (IOException expected) { }
        try { store.copy(FileStore.local(work), archive, false); fail("ancestor import accepted"); } catch (IOException expected) { }
        store.delete(renamed); store.delete(empty);
        assertEquals(1, store.list(archive).size());
    }

    @Test public void nestedArchivesAndBookmarksRestoreWithoutPhysicalEntryPaths() throws Exception {
        File inner = new File(work, "inner.zip"), outer = new File(work, "outer.zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(inner))) {
            out.putNextEntry(new ZipEntry("note.txt")); out.write("nested".getBytes(StandardCharsets.UTF_8)); out.closeEntry();
        }
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(outer))) {
            out.putNextEntry(new ZipEntry("inner.zip")); out.write(Files.readAllBytes(inner.toPath())); out.closeEntry();
        }
        FileStore.Node top = FileStore.archiveNode(FileStore.local(outer), "", true, 0, 0);
        FileStore.Node nested = FileStore.archiveNode(store.list(top).get(0), "", true, 0, 0);
        android.content.Intent intent = new android.content.Intent(); nested.put(intent);
        FileStore.Node restored = store.fromIntent(intent);
        assertEquals(nested.key(), restored.key()); assertNull(restored.file);
        assertEquals(nested.key(), DirectoryPlaces.Entry.from(nested).resolve(store).key());
        File replacement = new File(work, "replacement.txt"); text(replacement, "nested edit");
        store.write(store.list(restored).get(0), replacement);
        try (InputStream in = store.read(store.list(nested).get(0))) {
            assertEquals("nested edit", new String(FileStore.readBytes(in, 100), StandardCharsets.UTF_8));
        }
        assertEquals(top.key(), store.parent(nested).key());
        assertEquals("nested", new String(ArchiveOps.bytes(inner, "note.txt", 100), StandardCharsets.UTF_8));
    }

    @Test public void apkBackupSuffixStageSignatureAndSharedUri() throws Exception {
        File apk = new File(work, "fixture.APK.1");
        try (InputStream in = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("install-fixture.apk");
             OutputStream out = new FileOutputStream(apk)) { FileStore.pump(in, out); }
        assertTrue(ApkInstall.isApkName(apk.getName())); assertFalse(ApkInstall.isApkName("fixture.apk.12"));
        File staged = ApkInstall.stage(context, FileStore.local(apk));
        try {
            assertEquals("com.dualstack.installfixture", context.getPackageManager().getPackageArchiveInfo(staged.getAbsolutePath(), 0).packageName);
            assertEquals(ApkTools.sha256(apk), ApkTools.sha256(staged));
            assertTrue(ApkDialog.inspect(staged, 26)[0].contains("V2"));
            android.content.Intent install = ApkInstall.normalIntent(context, staged);
            assertEquals("content", install.getData().getScheme());
            assertTrue((install.getFlags() & android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
            assertTrue(ApkInstall.silentCommand(staged).contains("-S " + staged.length() + " < "));
            File payload = new File(work, "extra.txt"); text(payload, "changed APK");
            ArchiveOps.rewrite(apk, null, null, Map.of("extra.txt", payload));
            assertEquals("changed APK", new String(ArchiveOps.bytes(apk, "extra.txt", 100), StandardCharsets.UTF_8));
            assertEquals("签名校验失败", ApkDialog.inspect(apk, 26)[0]);
        } finally { staged.delete(); }
    }

    @Test public void explicitUnknownScriptRunsWithoutRenamingOriginal() throws Exception {
        File source = new File(work, "task.unknown"); text(source, "printf EXPLICIT_SCRIPT_OK\n");
        try (ExecutableFile.Prepared prepared = ExecutableFile.prepare(context, FileStore.local(source), true)) {
            ShellRunner.Result result = new ShellRunner().run(prepared.command(false), work, false, 10000, null, false);
            assertEquals(0, result.exitCode()); assertEquals("EXPLICIT_SCRIPT_OK", result.output());
        }
        assertEquals("printf EXPLICIT_SCRIPT_OK\n", new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8));
    }

    @Test public void batchedDirectoryEnumerationMatchesOriginalAndRecordsTimings() throws Exception {
        File directory = new File(work, "benchmark"); assertTrue(directory.mkdir());
        for (int i = 0; i < 300; i++) text(new File(directory, "file-" + i), "data");
        String batched = FileStore.rootListCommand(directory.getAbsolutePath());
        String original = "/system/bin/toybox find " + FileStore.quote(directory.getAbsolutePath())
                + " -mindepth 1 -maxdepth 1 -exec /system/bin/toybox stat -L -c '%f|%s|%Y|%n' {} \\;";
        long begin = android.os.SystemClock.elapsedRealtime();
        String before = FileStore.shell(original, false);
        long baseline = android.os.SystemClock.elapsedRealtime() - begin;
        begin = android.os.SystemClock.elapsedRealtime(); String after = FileStore.shell(batched, false);
        long optimized = android.os.SystemClock.elapsedRealtime() - begin;
        assertEquals(300, FileStore.parseRootListing(before).size()); assertEquals(before, after);
        String report = "300 entries; per-file stat=" + baseline + " ms; batched stat=" + optimized + " ms; uid=app (no su)\n";
        text(new File(context.getExternalFilesDir(null), "root-benchmark-060.txt"), report);
        android.util.Log.i("DualStackReview", report);
    }
    private static void text(File file, String value) throws IOException { Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8)); }

    @Test public void rootListingUsesAndroidCommandsAndFollowsDirectoryLinks() throws Exception {
        File directory = new File(work, "root dir ' |" ); assertTrue(directory.mkdir());
        File child = new File(directory, "folder"); assertTrue(child.mkdir());
        File payload = new File(directory, "pipe|name.txt"); Files.write(payload.toPath(), "root-list".getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(new File(directory, "linked").toPath(), child.toPath());
        Files.createSymbolicLink(new File(directory, "broken").toPath(), new File(directory, "missing").toPath());
        List<FileStore.Node> result = FileStore.parseRootListing(FileStore.shell(FileStore.rootListCommand(directory.getAbsolutePath()), false));
        assertEquals(4, result.size());
        assertTrue(result.stream().anyMatch(n -> n.name.equals("linked") && n.directory && n.root));
        assertTrue(result.stream().anyMatch(n -> n.name.equals("broken") && !n.directory && n.root));
        assertTrue(result.stream().anyMatch(n -> n.name.equals("pipe|name.txt") && n.size == 9 && !n.directory));
        assertFalse(RootAccess.directory(directory, false).root);
    }

    @Test public void rootNamespaceOptionsPreserveCommandsAndProtectPrivateContainers() {
        String command = "printf '%s' \"quoted; argument\"";
        assertEquals(List.of("su", "--mount-master", "-c", command),
                RootShell.arguments(command, "Options: --mount-master Force run in global mount namespace"));
        assertEquals(List.of("su", "-c", command), RootShell.arguments(command, "Usage: su -c COMMAND"));
        assertEquals(List.of("su", "-c", command), RootShell.arguments(command, ""));
        for (String path : List.of("/data", "/data/data", "/data/user", "/data/user/0", "/data/user/10",
                "/data/user_de/0", "/mnt/expand/test-volume/user/0")) {
            assertTrue(path, RootAccess.protectedStorageDirectory(new File(path)));
        }
        assertFalse(RootAccess.protectedStorageDirectory(new File("/data/user/0/com.dualstack.lab")));
        assertFalse(RootAccess.protectedStorageDirectory(new File("/data/user/0/com.dualstack.lab/files")));
    }

    @Test public void pipelineScriptPreservesEncodingArgumentsDirectoryAndOriginal() throws Exception {
        File resource = new File(work, "adjacent.txt"); Files.write(resource.toPath(), "PIPE_RESOURCE".getBytes(StandardCharsets.UTF_8));
        File source = new File(work, "script ' |.sh");
        byte[] bytes = ("\ufeff#!/system/bin/sh\r\nprintf '%s|%s|' \"$0\" \"$1\"\r\ncat adjacent.txt\r\n").getBytes(StandardCharsets.UTF_8);
        Files.write(source.toPath(), bytes);
        try (ExecutableFile.Prepared file = ExecutableFile.prepare(context, FileStore.local(source))) {
            ShellRunner.Result result = new ShellRunner().run(file.command(false, true) + " 'argument with spaces'", work, false, 10000, null, false);
            assertEquals(0, result.exitCode());
            assertEquals(source.getAbsolutePath() + "|argument with spaces|PIPE_RESOURCE", result.output());
            assertArrayEquals(bytes, Files.readAllBytes(source.toPath()));
        }
    }

    @Test public void fileCrudCopyMoveAndCollision() throws Exception {
        FileStore.Node home = FileStore.local(work);
        FileStore.Node a = store.create(home, "a", true), b = store.create(home, "b", true);
        FileStore.Node text = store.create(a, "note.txt", false);
        File payload = new File(work, "payload"); Files.write(payload.toPath(), "roundtrip".getBytes(StandardCharsets.UTF_8));
        store.write(text, payload);
        text = store.rename(text, "renamed.txt");
        FileStore.Node first = store.copy(text, b, false);
        FileStore.Node duplicate = store.copy(text, b, false);
        assertEquals("renamed (1).txt", duplicate.name);
        assertEquals("roundtrip", new String(Files.readAllBytes(first.file.toPath()), StandardCharsets.UTF_8));
        FileStore.Node moved = store.copy(text, home, true);
        assertFalse(text.file.exists()); assertTrue(moved.file.exists());
        try { store.copy(a, a, false); fail("self copy accepted"); } catch (IOException expected) {}
        FileStore.Node child = store.create(a, "child", true);
        try { store.copy(a, child, false); fail("recursive copy accepted"); } catch (IOException expected) {}
        store.delete(b); assertFalse(b.file.exists());
    }

    @Test public void textEncodingRoundTripsAndBoundedRead() throws Exception {
        String text = "Hello \u4e2d\u6587\nsecond line";
        for (String name : new String[]{"UTF-8", "UTF-16LE", "UTF-16BE", "GB18030", "GBK"}) {
            Charset charset = Charset.forName(name);
            byte[] bytes = TextCodec.encode(text, charset, new byte[0]);
            assertEquals(name, text, TextCodec.decode(bytes, name).text());
        }
        byte[] bom = {(byte) 0xff, (byte) 0xfe};
        byte[] encoded = TextCodec.encode(text, StandardCharsets.UTF_16LE, bom);
        TextCodec.Decoded decoded = TextCodec.decode(encoded, null);
        assertEquals(text, decoded.text()); assertArrayEquals(encoded, TextCodec.encode(decoded.text(), decoded.charset(), decoded.bom()));
        assertEquals("GB18030", TextCodec.decode(text.getBytes("GB18030"), null).charset().name());
        try { TextCodec.encode("\u4e2d", StandardCharsets.US_ASCII, new byte[0]); fail("lossy encoding"); }
        catch (IOException expected) {}
        InputStream in = new ByteArrayInputStream(new byte[100]);
        FileStore.skip(in, 90); assertEquals(10, FileStore.readBytes(in, 32).length);
    }

    @Test public void pagedMultibyteBoundariesAndStreamingWrite() throws Exception {
        String text = ("abc\u4e2d\u6587\ud83d\ude03\n").repeat(40000);
        for (String name : new String[]{"UTF-8", "UTF-16LE", "UTF-16BE", "GB18030"}) {
            Charset encoding = Charset.forName(name);
            byte[] mark = name.equals("UTF-8") ? new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf} :
                    name.equals("UTF-16LE") ? new byte[]{(byte) 0xff, (byte) 0xfe} :
                    name.equals("UTF-16BE") ? new byte[]{(byte) 0xfe, (byte) 0xff} : new byte[0];
            File original = new File(work, name + ".txt");
            Files.write(original.toPath(), TextCodec.encode(text, encoding, mark));
            StringBuilder read = new StringBuilder();
            long offset = 0;
            PagedTextFile.Page first = null;
            while (offset < original.length()) {
                PagedTextFile.Page page = PagedTextFile.read(original, offset, encoding, mark, 256 * 1024);
                if (first == null) first = page;
                assertTrue(page.end() > page.start()); read.append(page.text()); offset = page.end();
            }
            assertEquals(name, text, read.toString());
            File edited = new File(work, name + "-edited.txt");
            PagedTextFile.write(original, edited, first, "changed\n", encoding, mark);
            assertEquals("changed\n" + text.substring(first.text().length()),
                    TextCodec.decode(Files.readAllBytes(edited.toPath()), name).text());
            byte[] prefix = Arrays.copyOf(Files.readAllBytes(original.toPath()), 256 * 1024);
            assertEquals(name, TextCodec.decodePrefix(prefix, name).charset().name());
        }
    }

    @Test public void automaticEncodingHandlesShortBomlessConfigurationText() throws Exception {
        for (String encoding : List.of("UTF-16LE", "UTF-16BE", "UTF-32LE", "UTF-32BE")) {
            for (String text : List.of("a", "[x]", "a=1", "x=1\n")) {
                byte[] original = text.getBytes(encoding);
                TextCodec.Decoded decoded = TextCodec.decode(original, null);
                assertEquals(encoding, decoded.charset().name());
                assertEquals(text, decoded.text());
                assertArrayEquals(original, TextCodec.encode(decoded.text(), decoded.charset(), decoded.bom()));
            }
        }
    }

    @Test public void encodingPrefixRetainsIncompleteCharactersWithoutDiscardingInvalidBytes() throws Exception {
        byte[] partial = {'x', '=', '1', '\n', (byte) 0xe4, (byte) 0xb8};
        TextCodec.Decoded decoded = TextCodec.decodePrefix(partial, null);
        assertEquals(StandardCharsets.UTF_8, decoded.charset());
        assertEquals("x=1\n", decoded.text());
        byte[] invalid = {'x', '=', '1', '\n', (byte) 0xff};
        TextCodec.Decoded fallback = TextCodec.decodePrefix(invalid, null);
        assertTrue(fallback.uncertain());
        assertArrayEquals(invalid, TextCodec.encode(fallback.text(), fallback.charset(), fallback.bom()));
    }

    @Test public void automaticEncodingKeepsUnknownCommentBytesAndRejectsBinary() throws Exception {
        byte[] original = {'#', ' ', (byte) 0x81, (byte) 0xff, '\n', 'a', '=', '1', '\n'};
        TextCodec.Decoded decoded = TextCodec.decode(original, null);
        assertTrue(decoded.uncertain());
        assertArrayEquals(original, TextCodec.encode(decoded.text(), decoded.charset(), decoded.bom()));
        assertArrayEquals(original, TextCodec.encode(TextCodec.decodePrefix(original, null).text(),
                decoded.charset(), decoded.bom()));
        try { TextCodec.decode(new byte[]{'a', 0, 'b', 0, 0, 1, 2, 3, 4}, null); fail("Binary accepted"); }
        catch (IOException expected) {}
    }

    @Test public void editorDetectsNullPaddedTextAndPreservesControlCharacters() throws Exception {
        String text = "[AndroidPak]\nname=中文\nvalue=1\n\0\0\0\0";
        for (String encoding : List.of("UTF-8", "UTF-16LE", "UTF-16BE", "UTF-32LE", "UTF-32BE", "GB18030")) {
            byte[] original = text.getBytes(encoding);
            TextCodec.Decoded decoded = TextCodec.decodeForEditor(original, null, false);
            assertEquals(encoding, text, decoded.text());
            assertArrayEquals(encoding, original, TextCodec.encode(decoded.text(), decoded.charset(), decoded.bom()));
            assertEquals(text, TextCodec.decodeForEditor(original, encoding, false).text());
        }
    }

    @Test public void editorFallbackKeepsDamagedBomAndUnmappedPayloadBytes() throws Exception {
        byte[] original = {(byte) 0xff, (byte) 0xfe, 0, (byte) 0xd8, 'A', 0, 1};
        for (boolean prefix : List.of(false, true)) {
            TextCodec.Decoded decoded = TextCodec.decodeForEditor(original, null, prefix);
            assertTrue(decoded.uncertain()); assertEquals(StandardCharsets.ISO_8859_1, decoded.charset());
            assertArrayEquals(original, TextCodec.encode(decoded.text(), decoded.charset(), decoded.bom()));
        }
        try { TextCodec.decodeForEditor(original, "UTF-8", false); fail("Explicit invalid encoding accepted"); }
        catch (IOException expected) { }
    }

    @Test public void nullContainingPagedFilePreservesOtherPagesWhenEdited() throws Exception {
        File original = new File(work, "padded.ini"), edited = new File(work, "edited.ini");
        byte[] block = "[section]\nx=1\n\0\0\0".repeat(200000).getBytes(StandardCharsets.UTF_8);
        Files.write(original.toPath(), block);
        PagedTextFile.Page first = PagedTextFile.read(original, 0, StandardCharsets.UTF_8, new byte[0], 256 * 1024);
        assertTrue(first.text().indexOf('\0') >= 0);
        PagedTextFile.Page second = PagedTextFile.read(original, first.end(), StandardCharsets.UTF_8, new byte[0], 256 * 1024);
        assertTrue(second.end() > first.end()); assertTrue(second.text().indexOf('\0') >= 0);
        PagedTextFile.write(original, edited, first, first.text() + "# edit\n", StandardCharsets.UTF_8, new byte[0]);
        ByteArrayOutputStream expected = new ByteArrayOutputStream(); expected.write(block, 0, (int) first.end());
        expected.write("# edit\n".getBytes(StandardCharsets.UTF_8));
        expected.write(block, (int) first.end(), block.length - (int) first.end());
        assertArrayEquals(expected.toByteArray(), Files.readAllBytes(edited.toPath()));
        assertArrayEquals(block, Files.readAllBytes(original.toPath()));
    }

    @Test public void automaticEncodingDetectionPreservesInternationalText() throws Exception {
        Map<String, String> samples = new LinkedHashMap<>();
        samples.put("GB18030", "中文文件编码自动识别，修改保存以后内容不能损坏。\n".repeat(25));
        samples.put("Big5", "繁體中文檔案編碼測試，資料與文字應該保持完整。\n".repeat(25));
        samples.put("Shift_JIS", "日本語の文字コードを自動判別します。保存した後も同じ文章になります。\n".repeat(25));
        samples.put("EUC-KR", "한국어 문서의 문자 인코딩을 자동으로 확인하고 내용을 보존합니다.\n".repeat(25));
        samples.put("windows-1252", "Fran\u00e7ais: \u00e9l\u00e8ve, d\u00e9j\u00e0, \u00e0 bient\u00f4t. \u201cCaf\u00e9\u201d et \u20ac.\n".repeat(25));
        samples.put("windows-1251", "Русский текст для автоматического определения кодировки и проверки сохранения.\n".repeat(25));
        samples.put("ISO-2022-JP", "日本語の文字コードを自動判別します。".repeat(25));
        for (Map.Entry<String, String> sample : samples.entrySet()) {
            byte[] original = sample.getValue().getBytes(sample.getKey());
            TextCodec.Decoded decoded = TextCodec.decode(original, null);
            assertEquals(sample.getKey() + " detected as " + decoded.charset(), sample.getValue(), decoded.text());
            assertArrayEquals(sample.getKey(), original, TextCodec.encode(decoded.text(), decoded.charset(), decoded.bom()));
        }
        for (String name : List.of("UTF-32LE", "UTF-32BE")) {
            byte[] mark = name.endsWith("LE") ? new byte[]{(byte) 0xff, (byte) 0xfe, 0, 0}
                    : new byte[]{0, 0, (byte) 0xfe, (byte) 0xff};
            String text = "UTF32 \u4e2d\u6587 \ud83d\ude03\n";
            byte[] bytes = TextCodec.encode(text, Charset.forName(name), mark);
            TextCodec.Decoded decoded = TextCodec.decode(bytes, null);
            assertEquals(name, decoded.charset().name()); assertEquals(text, decoded.text());
            assertArrayEquals(bytes, TextCodec.encode(decoded.text(), decoded.charset(), decoded.bom()));
            assertEquals(text, TextCodec.decode(text.getBytes(name), null).text());
        }
    }

    @Test public void shellStreamsInputExitAndCancelsChildProcesses() throws Exception {
        ShellRunner runner = new ShellRunner();
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.FutureTask<ShellRunner.Result> task = new java.util.concurrent.FutureTask<>(() ->
                runner.run("printf READY; read value; printf '\\n%s\\n' \"$value\"; pwd; exit 7", work, false,
                        10000, output -> { if (output.contains("READY")) ready.countDown(); }, true));
        new Thread(task).start();
        assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)); runner.send("stdin_ok");
        ShellRunner.Result result = task.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(7, result.exitCode()); assertTrue(result.output().contains("stdin_ok"));
        String[] lines = result.output().trim().split("\\R");
        assertEquals("Shell output: " + result.output(), work.getCanonicalPath(),
                new File(lines[lines.length - 1]).getCanonicalPath());
        File orphan = new File(work, "orphan-marker");
        ShellRunner cancel = new ShellRunner();
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.FutureTask<ShellRunner.Result> cancelling = new java.util.concurrent.FutureTask<>(() ->
                cancel.run("trap '' TERM; (sleep 2; echo orphan > " + FileStore.quote(orphan.getAbsolutePath())
                        + ") & printf READY; wait", work, false, 10000,
                        chunk -> { if (chunk.contains("READY")) started.countDown(); }, true));
        new Thread(cancelling).start();
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS)); cancel.stop();
        assertTrue(cancelling.get(5, java.util.concurrent.TimeUnit.SECONDS).stopped());
        Thread.sleep(2300); assertFalse("cancelled shell left a running child", orphan.exists());
        assertTrue(new ShellRunner().run("sleep 5", work, false, 150, null, false).timedOut());
        assertEquals("", FileStore.shell("read value || true", false));
    }

    @Test public void statefulEncodingLargeFilePagesPreserveTextAndTail() throws Exception {
        Charset iso = Charset.forName("ISO-2022-JP");
        String original = "日本語の文章".repeat(200000) + "\n終端";
        File source = new File(work, "stateful.txt"), staged = new File(work, "staged.txt");
        Files.write(source.toPath(), original.getBytes(iso));
        assertTrue(source.length() > EditorActivity.LIMIT);
        TextCodec.Decoded detected = TextCodec.decodePrefix(
                Arrays.copyOf(Files.readAllBytes(source.toPath()), EditorActivity.PAGE), null);
        assertEquals(iso, detected.charset());
        assertTrue(detected.text().startsWith("日本語の文章"));
        TextCodec.transcode(source, staged, iso, StandardCharsets.UTF_8, new byte[0], new byte[0]);
        StringBuilder read = new StringBuilder(); long offset = 0; PagedTextFile.Page first = null;
        while (offset < staged.length()) {
            PagedTextFile.Page page = PagedTextFile.read(staged, offset, StandardCharsets.UTF_8, new byte[0], EditorActivity.PAGE);
            if (first == null) first = page;
            read.append(page.text()); assertTrue(page.end() > offset); offset = page.end();
        }
        assertEquals(original, read.toString());
        File edited = new File(work, "edited.txt"), persisted = new File(work, "persisted.txt");
        PagedTextFile.write(staged, edited, first, "変更\n" + first.text(), StandardCharsets.UTF_8, new byte[0]);
        TextCodec.transcode(edited, persisted, StandardCharsets.UTF_8, iso, new byte[0], new byte[0]);
        assertEquals("変更\n" + original, new String(Files.readAllBytes(persisted.toPath()), iso));
    }

    @Test public void zipEditExtractAddRenameDeleteAndTraversal() throws Exception {
        File zip = new File(work, "test.zip");
        // Put a file before its explicit parent entry, as many real ZIP writers do.
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            entry(out, "folder/readme.txt", "one".getBytes(StandardCharsets.UTF_8));
            entry(out, "folder/", null); entry(out, "untouched.txt", new byte[]{42});
        }
        assertEquals(2, ArchiveOps.list(zip, "").size());
        File payload = new File(work, "replace.txt"); Files.write(payload.toPath(), "two".getBytes(StandardCharsets.UTF_8));
        ArchiveOps.rewrite(zip, null, null, Map.of("folder/readme.txt", payload, "added.txt", payload));
        assertEquals("two", new String(ArchiveOps.bytes(zip, "folder/readme.txt", 10), StandardCharsets.UTF_8));
        assertArrayEquals(new byte[]{42}, ArchiveOps.bytes(zip, "untouched.txt", 10));
        ArchiveOps.rewrite(zip, "folder/", "renamed/", Collections.emptyMap());
        File partial = new File(work, "partial");
        ArchiveOps.extract(zip, "renamed/readme.txt", partial);
        assertEquals("two", new String(Files.readAllBytes(new File(partial, "renamed/readme.txt").toPath()), StandardCharsets.UTF_8));
        ArchiveOps.extract(zip, "", new File(work, "all"));
        ArchiveOps.rewrite(zip, "renamed/", null, Collections.emptyMap());
        assertEquals(2, ArchiveOps.list(zip, "").size());
        String hash = ApkTools.sha256(zip);
        try { ArchiveOps.rewrite(zip, null, null, Map.of("../escape.txt", payload)); fail("traversal"); }
        catch (IOException expected) {}
        assertEquals(hash, ApkTools.sha256(zip));
        File bad = new File(work, "bad.zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(bad))) { entry(out, "../escape.txt", new byte[]{1}); }
        try { ArchiveOps.extract(bad, "", new File(work, "bad-out")); fail("zip slip"); }
        catch (IOException expected) {}
        assertFalse(new File(work, "escape.txt").exists());
    }

    @Test public void zipPackDirectoryRoundTrip() throws Exception {
        FileStore.Node folder = store.create(FileStore.local(work), "pack-source", true);
        FileStore.Node inner = store.create(folder, "inner", true);
        FileStore.Node text = store.create(inner, "file.txt", false);
        Files.write(text.file.toPath(), new byte[]{1, 2, 3});
        File zip = new File(work, "packed.zip");
        ArchiveOps.pack(store, List.of(folder), zip);
        File output = new File(work, "unpacked");
        ArchiveOps.extract(zip, "", output);
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(new File(output, "pack-source/inner/file.txt").toPath()));
    }

    @Test public void dexDisassembleReassembleAndSingleClassEdit() throws Exception {
        File dex = fixtureDex();
        assertEquals(1, DexFileFactory.loadDexFile(dex, Opcodes.getDefault()).getClasses().size());
        File smali = new File(work, "disassembled");
        ApkTools.disassemble(dex, smali);
        assertTrue(new File(smali, "fixture/Sample.smali").isFile());
        File rebuilt = new File(work, "rebuilt.dex"); ApkTools.assemble(smali, rebuilt);
        ApkTools.saveDexClass(rebuilt, "", CLASS, SMALI.replace("0x1", "0x2"), work);
        assertEquals(2, literal(rebuilt));
        String hash = ApkTools.sha256(rebuilt);
        try { ApkTools.saveDexClass(rebuilt, "", CLASS, "broken smali", work); fail("invalid smali"); }
        catch (IOException expected) {}
        assertEquals(hash, ApkTools.sha256(rebuilt));
    }

    @Test public void binaryXmlAndArscWriteBack() throws Exception {
        File apk = fixtureApk();
        String xml = ApkTools.xmlText(apk, "AndroidManifest.xml");
        assertTrue(xml.contains("com.dualstack.lab"));
        ApkTools.saveXml(apk, "AndroidManifest.xml", manifest("com.dualstack.fixture"));
        assertTrue(ApkTools.xmlText(apk, "AndroidManifest.xml").contains("com.dualstack.fixture"));
        String json = ApkTools.arscText(apk, "resources.arsc");
        ApkTools.saveArsc(apk, "resources.arsc", json);
        assertEquals(canonical(new com.reandroid.json.JSONObject(json), ""),
                canonical(new com.reandroid.json.JSONObject(ApkTools.arscText(apk, "resources.arsc")), ""));
        com.reandroid.json.JSONObject modified = new com.reandroid.json.JSONObject(json);
        boolean changed = false;
        for (Object pkg : modified.getJSONArray("packages"))
            for (Object spec : ((com.reandroid.json.JSONObject) pkg).getJSONArray("specs"))
                for (Object type : ((com.reandroid.json.JSONObject) spec).getJSONArray("types")) {
                    com.reandroid.json.JSONObject resourceType = (com.reandroid.json.JSONObject) type;
                    if (!"string".equals(resourceType.getString("name"))) continue;
                    for (Object item : resourceType.getJSONArray("entries")) {
                        com.reandroid.json.JSONObject resource = (com.reandroid.json.JSONObject) item;
                        if ("app_name".equals(resource.optString("entry_name"))) {
                            resource.getJSONObject("value").put("data", "Resource edit verified");
                            changed = true;
                        }
                    }
                }
        assertTrue("fixture must contain app_name", changed);
        ApkTools.saveArsc(apk, "resources.arsc", modified.toString());
        assertEquals(canonical(modified, ""),
                canonical(new com.reandroid.json.JSONObject(ApkTools.arscText(apk, "resources.arsc")), ""));
        File arsc = new File(work, "resources.arsc"); ApkTools.saveArscFile(arsc, json);
        assertEquals(canonical(new com.reandroid.json.JSONObject(json), ""), canonical(TableBlock.load(arsc).toJson(), ""));
        String hash = ApkTools.sha256(apk);
        try { ApkTools.saveArsc(apk, "resources.arsc", "broken json"); fail("invalid JSON accepted"); }
        catch (Exception expected) {}
        assertEquals(hash, ApkTools.sha256(apk));
    }

    @Test public void apkAlignSignVerifyCloneAndDexReplacement() throws Exception {
        File apk = fixtureApk();
        ApkTools.saveXml(apk, "AndroidManifest.xml", manifest("com.dualstack.fixture"));
        ApkTools.saveDexClass(apk, "classes.dex", CLASS, SMALI.replace("0x1", "0x3"), work);
        File extracted = new File(work, "extract.dex"); ArchiveOps.extractOne(apk, "classes.dex", extracted);
        assertEquals(3, literal(extracted));
        File aligned = new File(work, "aligned.apk"), signed = new File(work, "signed.apk");
        ApkTools.align(apk, aligned); ApkTools.sign(context, aligned, signed);
        assertTrue(ApkTools.verify(signed), ApkTools.verify(signed).contains("\u901a\u8fc7"));
        File clone = new File(work, "clone.apk"); ApkTools.clonePackage(apk, clone, "com.dualstack.fixture.clone");
        try (ApkModule module = ApkModule.loadApkFile(clone)) { assertEquals("com.dualstack.fixture.clone", module.getPackageName()); }
        File cloneSigned = new File(work, "clone-signed.apk"); ApkTools.sign(context, clone, cloneSigned);
        assertTrue(ApkTools.verify(cloneSigned).contains("\u901a\u8fc7"));
        File review = new File(context.getExternalFilesDir(null), "review");
        review.mkdirs();
        Files.copy(signed.toPath(), new File(review, "fixture-signed.apk").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(cloneSigned.toPath(), new File(review, "fixture-clone.apk").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        androidx.test.uiautomator.UiDevice device = androidx.test.uiautomator.UiDevice.getInstance(
                InstrumentationRegistry.getInstrumentation());
        device.executeShellCommand("mkdir -p /sdcard/Documents/DualStackReview-fixtures");
        for (String name : List.of("fixture-signed.apk", "fixture-clone.apk"))
            device.executeShellCommand("cp " + FileStore.quote(new File(review, name).getAbsolutePath())
                    + " " + FileStore.quote("/sdcard/Documents/DualStackReview-fixtures/" + name));
    }

    @Test public void executableShAndElfFilesRunWithArguments() throws Exception {
        File original = new File(work, "original script's name.sh");
        Files.write(original.toPath(), ("\ufeff#!/system/bin/sh\r\ncat sibling.txt\r\n"
                + "printf '\\nself=%s\\narg=%s\\n' \"$0\" \"$1\"\r\nexit 17\r\n").getBytes(StandardCharsets.UTF_8));
        Files.write(new File(work, "sibling.txt").toPath(), "sibling_ok".getBytes(StandardCharsets.UTF_8));
        try (ExecutableFile.Prepared script = ExecutableFile.prepare(context, FileStore.local(original))) {
            assertNull(script.elf());
            ShellRunner.Result result = new ShellRunner().run(script.command(false) + " " + FileStore.quote("space ' argument"),
                    work, false, 5000, null, false);
            assertEquals(result.output(), 17, result.exitCode());
            assertTrue(result.output().contains("sibling_ok"));
            assertTrue(result.output().contains("self=" + original.getAbsolutePath()));
            assertTrue(result.output().contains("arg=space ' argument"));
        }
        File originalElf = new File(work, "native tool's name.elf");
        Files.copy(new File("/system/bin/sh").toPath(), originalElf.toPath());
        byte[] before = Files.readAllBytes(originalElf.toPath());
        File staged;
        try (ExecutableFile.Prepared elf = ExecutableFile.prepare(context, FileStore.local(originalElf))) {
            staged = elf.file(); assertNotNull(elf.elf()); assertFalse(elf.elf().needsRoot());
            String argument = "ELF argument with ' and 空格";
            ShellRunner.Result result = new ShellRunner().run(elf.command(false) + " -c 'printf %s \"$1\"' fixture " + FileStore.quote(argument),
                    work, false, 5000, null, false);
            assertEquals(result.output(), 0, result.exitCode()); assertEquals(argument, result.output());
            result = new ShellRunner().run(elf.command(false) + " -c 'exit 23'", work, false, 5000, null, false);
            assertEquals(result.output(), 23, result.exitCode());
        }
        assertFalse(staged.exists()); assertArrayEquals(before, Files.readAllBytes(originalElf.toPath()));
        File multicall = new File(work, "toybox");
        Files.copy(new File("/system/bin/toybox").toPath(), multicall.toPath());
        try (ExecutableFile.Prepared elf = ExecutableFile.prepare(context, FileStore.local(multicall))) {
            ShellRunner.Result result = new ShellRunner().run(elf.command(false) + " printf multicall_ok", work, false, 5000, null, false);
            assertEquals(result.output(), 0, result.exitCode()); assertEquals("multicall_ok", result.output());
        }
    }

    @Test public void executableValidationRejectsWrongAbiAndNonAndroidFiles() throws Exception {
        File file = new File(work, "bad.elf");
        Files.write(file.toPath(), new byte[]{0x7f, 'E', 'L', 'F'});
        expectExecutableError(file, "文件头");
        byte[] bytes = Files.readAllBytes(new File("/system/bin/toybox").toPath());
        byte[] invalid = bytes.clone(); invalid[24] = 0;
        Arrays.fill(invalid, 24, 32, (byte) 0);
        Files.write(file.toPath(), invalid); expectExecutableError(file, "不是可执行");
        invalid = bytes.clone();
        int machine = (invalid[18] & 255) | ((invalid[19] & 255) << 8);
        invalid[18] = (byte) (machine == 62 ? 183 : 62); invalid[19] = 0;
        Files.write(file.toPath(), invalid); expectExecutableError(file, "架构");
        invalid = bytes.clone();
        byte[] interpreter = "/system/bin/linker64".getBytes(StandardCharsets.US_ASCII);
        int start = -1;
        for (int i = 0; i <= invalid.length - interpreter.length; i++) {
            if (Arrays.equals(Arrays.copyOfRange(invalid, i, i + interpreter.length), interpreter)) { start = i; break; }
        }
        assertTrue("64-bit Android fixture interpreter", start >= 0);
        invalid[start + 1] = 'X'; Files.write(file.toPath(), invalid); expectExecutableError(file, "解释器");
        Files.write(file.toPath(), "printf unexpected".getBytes(StandardCharsets.UTF_8));
        expectExecutableError(file, "不是 ELF");
    }

    private void expectExecutableError(File file, String message) throws Exception {
        try (ExecutableFile.Prepared ignored = ExecutableFile.prepare(context, FileStore.local(file))) {
            fail("Invalid executable was accepted");
        } catch (IOException e) { assertTrue(e.getMessage(), e.getMessage().contains(message)); }
    }

    @Test public void directoryPlacesPersistDeduplicateAndKeepRootAndSafIdentity() throws Exception {
        context.getSharedPreferences("directory-places", 0).edit().clear().commit();
        DirectoryPlaces places = new DirectoryPlaces(context);
        try {
            assertEquals(2, places.entries(true).size()); places.clear(true);
            for (int i = 0; i < 60; i++) places.visit(FileStore.directory(new File(work, "dir" + i), false));
            assertEquals(50, places.entries(false).size());
            FileStore.Node latest = FileStore.directory(work, false);
            places.visit(latest); places.visit(latest);
            assertEquals(50, places.entries(false).size()); assertEquals(latest.key(), places.entries(false).get(0).key());
            places.bookmark(DirectoryPlaces.Entry.from(latest)); places.bookmark(DirectoryPlaces.Entry.from(latest));
            FileStore.Node root = FileStore.directory(new File("/data/adb"), true);
            places.bookmark(DirectoryPlaces.Entry.from(root));
            android.net.Uri uri = android.net.Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADocuments/document/primary%3ADocuments");
            FileStore.Node saf = new FileStore.Node("SAF Documents", null, uri, true, false, 0, 0);
            places.bookmark(DirectoryPlaces.Entry.from(saf));
            places.rename(latest.key(), "我的目录"); places.move(saf.key(), -1);
            places = new DirectoryPlaces(context);
            assertEquals(3, places.entries(true).size()); assertEquals("我的目录", places.entries(true).get(0).label());
            assertEquals(uri.toString(), places.entries(true).get(1).uri());
            assertTrue(places.entries(true).get(2).root());
            assertEquals(work.getAbsolutePath(), places.entries(true).get(0).resolve(store).path());
            places.remove(true, root.key()); places.clear(false);
            assertEquals(2, places.entries(true).size()); assertTrue(places.entries(false).isEmpty());
        } finally { context.getSharedPreferences("directory-places", 0).edit().clear().commit(); }
    }

    @Test public void installedApkExtractionAndShell() throws Exception {
        FileStore.Node apk = FileStore.local(new File(context.getApplicationInfo().sourceDir));
        FileStore.Node copy = store.copy(apk, FileStore.local(work), false);
        assertEquals(ApkTools.sha256(apk.file), ApkTools.sha256(copy.file));
        assertEquals("shell-ok", FileStore.shell("printf shell-ok", false));
        try { FileStore.shell("exit 7", false); fail("failed exit ignored"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("exit 7")); }
    }

    @Test public void androidRootWriteCommandPreservesModeAndOwner() throws Exception {
        File target = new File(work, "quoted' target.txt"), temporary = new File(work, ".root-write-check");
        Files.write(target.toPath(), "before".getBytes(StandardCharsets.UTF_8));
        FileStore.shell("chmod 640 " + FileStore.quote(target.getAbsolutePath()), false);
        String before = FileStore.shell("stat -c '%a:%u:%g' " + FileStore.quote(target.getAbsolutePath()), false);
        Process process = new ProcessBuilder("sh", "-c", FileStore.rootWriteCommand(
                target.getAbsolutePath(), temporary.getAbsolutePath())).start();
        try (OutputStream output = process.getOutputStream()) { output.write("after".getBytes(StandardCharsets.UTF_8)); }
        assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)); assertEquals(0, process.exitValue());
        assertEquals("after", new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
        assertEquals(before, FileStore.shell("stat -c '%a:%u:%g' " + FileStore.quote(target.getAbsolutePath()), false));
        assertFalse(temporary.exists());
    }

    private File fixtureDex() throws Exception {
        File smali = new File(work, "smali"); smali.mkdir();
        Files.write(new File(smali, "Sample.smali").toPath(), SMALI.getBytes(StandardCharsets.UTF_8));
        File dex = new File(work, "classes.dex"); ApkTools.assemble(smali, dex); return dex;
    }
    private File fixtureApk() throws Exception {
        File apk = new File(work, "fixture.apk"); File dex = fixtureDex();
        try (ZipFile original = new ZipFile(context.getApplicationInfo().sourceDir);
             ZipOutputStream out = new ZipOutputStream(new FileOutputStream(apk))) {
            for (String name : List.of("AndroidManifest.xml", "resources.arsc")) {
                out.putNextEntry(new ZipEntry(name));
                try (InputStream in = original.getInputStream(original.getEntry(name))) { FileStore.pump(in, out); }
                out.closeEntry();
            }
            entry(out, "classes.dex", Files.readAllBytes(dex.toPath()));
            entry(out, "assets/sample.txt", "sample".getBytes(StandardCharsets.UTF_8));
        }
        return apk;
    }
    private String manifest(String pkg) {
        return "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"" + pkg
                + "\" android:versionCode=\"1\" android:versionName=\"1.0\">"
                + "<uses-sdk android:minSdkVersion=\"26\" android:targetSdkVersion=\"35\"/>"
                + "<application android:label=\"Fixture\" android:hasCode=\"false\"/></manifest>";
    }
    private int literal(File dex) throws Exception {
        for (ClassDef def : DexFileFactory.loadDexFile(dex, Opcodes.getDefault()).getClasses())
            for (Method method : def.getMethods()) if (method.getName().equals("value"))
                for (org.jf.dexlib2.iface.instruction.Instruction ins : method.getImplementation().getInstructions())
                    if (ins instanceof NarrowLiteralInstruction) return ((NarrowLiteralInstruction) ins).getNarrowLiteral();
        throw new AssertionError("missing value instruction");
    }
    private static void entry(ZipOutputStream out, String name, byte[] data) throws Exception {
        out.putNextEntry(new ZipEntry(name)); if (data != null) out.write(data); out.closeEntry();
    }
    private static String canonical(Object value, String field) {
        if (value instanceof com.reandroid.json.JSONObject object) {
            TreeMap<String, String> map = new TreeMap<>();
            for (String key : object.keySet()) map.put(key, canonical(object.get(key), key));
            return map.toString();
        }
        if (value instanceof com.reandroid.json.JSONArray array) {
            List<String> values = new ArrayList<>();
            for (Object item : array) values.add(canonical(item, ""));
            // Resource configurations may be reordered when the binary table is rebuilt.
            if (Set.of("packages", "specs", "types", "entries").contains(field)) Collections.sort(values);
            return values.toString();
        }
        return String.valueOf(value);
    }
}
