package com.dualstack.lab;

import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.system.Os;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.widget.CodeEditor;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.zip.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class EditorDraftsInstrumentedTest {
    private Context context;
    private File work;
    private UiDevice device;
    private ActivityScenario<EditorActivity> scenario;
    private final Set<String> ids = new HashSet<>();

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "editor-drafts-test-").toFile();
    }
    @After public void cleanup() throws Exception {
        if (scenario != null) scenario.close();
        EditorDrafts.WORKER.submit(() -> {}).get(10, TimeUnit.SECONDS);
        for (String id : ids) new EditorDrafts(context, id).clear().get(10, TimeUnit.SECONDS);
        new FileStore(context).delete(FileStore.local(work));
    }
    @Test public void diskRecordKeepsTextEncodingAndMetadataTogether() throws Exception {
        EditorDrafts drafts = drafts();
        String text = "草稿\u0000\ud800\nsecond";
        JSONObject metadata = new JSONObject().put("time", 1).put("charset", "UTF-16LE")
                .put("bom", "//4=").put("start", 262144L).put("leftLine", 1);
        EditorDrafts.State state = drafts.remember("document", metadata, new Content(text));
        drafts.persist(state).get(10, TimeUnit.SECONDS);
        EditorDrafts.State restored = EditorDrafts.read(context, drafts.id);
        assertNotNull(restored); assertEquals(text, restored.content().toString());
        assertEquals("UTF-16LE", restored.metadata().getString("charset"));
        assertEquals(262144L, restored.metadata().getLong("start"));
        assertEquals(state.revision(), restored.revision());
    }
    @Test public void queuedOldCheckpointsCannotResurrectDiscardedDraft() throws Exception {
        EditorDrafts drafts = drafts();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Future<?> blocker = EditorDrafts.WORKER.submit(() -> {
            entered.countDown();
            try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        Future<?> removed;
        try {
            EditorDrafts.State old = drafts.remember("document", new JSONObject(), new Content("old"));
            drafts.persist(old);
            EditorDrafts.State newer = drafts.remember("document", new JSONObject(), new Content("new"));
            drafts.persist(newer); removed = drafts.clear();
        } finally { release.countDown(); }
        blocker.get(10, TimeUnit.SECONDS); removed.get(10, TimeUnit.SECONDS);
        assertNull(EditorDrafts.read(context, drafts.id));
    }
    @Test public void pruneRemovesOnlyOldUnclaimedDrafts() throws Exception {
        long day = 24L * 60 * 60 * 1000, now = System.currentTimeMillis();
        EditorDrafts old = drafts(), fresh = drafts(), held = drafts();
        old.persist(old.remember("a", new JSONObject().put("time", now - 30 * day), new Content("old"))).get(10, TimeUnit.SECONDS);
        fresh.persist(fresh.remember("b", new JSONObject().put("time", now - day), new Content("fresh"))).get(10, TimeUnit.SECONDS);
        held.persist(held.remember("c", new JSONObject().put("time", now - 30 * day), new Content("held"))).get(10, TimeUnit.SECONDS);
        held.activate();
        try {
            assertTrue(EditorDrafts.prune(context, 14 * day) >= 1);
            assertNull(EditorDrafts.read(context, old.id));
            assertEquals("fresh", EditorDrafts.read(context, fresh.id).content().toString());
            assertEquals("An open editor's draft must survive", "held", EditorDrafts.read(context, held.id).content().toString());
        } finally { held.deactivate(); }
    }
    @Test public void activeSessionsAreNotOfferedAsAbandonedDrafts() throws Exception {
        EditorDrafts drafts = drafts();
        drafts.persist(drafts.remember("document", new JSONObject().put("time", 1), new Content("text"))).get(10, TimeUnit.SECONDS);
        drafts.activate();
        try { assertNull(EditorDrafts.latest(context, "document")); }
        finally { drafts.deactivate(); }
        assertEquals(drafts.id, EditorDrafts.latest(context, "document").id());
    }
    @Test public void recreationRestoresUnsavedUtf16TextBomAndSelection() throws Exception {
        File file = new File(work, "unicode.txt");
        Files.write(file.toPath(), TextCodec.encode("original\nline", StandardCharsets.UTF_16LE, new byte[]{(byte) 0xff, (byte) 0xfe}));
        byte[] original = Files.readAllBytes(file.toPath());
        launch(file, null);
        edit("爸爸\n草稿\u0000tail");
        scenario.onActivity(activity -> editor(activity).setSelection(1, 2));
        scenario.recreate(); ready();
        assertEquals("爸爸\n草稿\u0000tail", text());
        scenario.onActivity(activity -> {
            assertTrue((boolean) get(activity, "dirty"));
            assertEquals(StandardCharsets.UTF_16LE, get(activity, "charset"));
            assertArrayEquals(new byte[]{(byte) 0xff, (byte) 0xfe}, (byte[]) get(activity, "bom"));
            assertEquals(1, editor(activity).getCursor().getLeftLine());
            assertEquals(2, editor(activity).getCursor().getLeftColumn());
        });
        assertArrayEquals(original, Files.readAllBytes(file.toPath()));
    }
    @Test public void recreationRestoresNonFirstPageAndSavePreservesOtherPages() throws Exception {
        File file = new File(work, "large.txt");
        byte[] original = new byte[3 * 1024 * 1024]; Arrays.fill(original, (byte) 'A'); Files.write(file.toPath(), original);
        launch(file, null);
        scenario.onActivity(activity -> invoke(activity, "changePage", true));
        await(() -> readyNow() && offset() > 0);
        long start = offset(); final long[] end = {0};
        scenario.onActivity(activity -> end[0] = ((PagedTextFile.Page) get(activity, "currentPage")).end());
        edit("replacement-page"); scenario.recreate(); ready();
        assertEquals(start, offset()); assertEquals("replacement-page", text());
        scenario.onActivity(activity -> invoke(activity, "save")); await(() -> !saving() && !dirty());
        byte[] actual = Files.readAllBytes(file.toPath());
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.write(original, 0, (int) start); expected.write("replacement-page".getBytes(StandardCharsets.UTF_8));
        expected.write(original, (int) end[0], original.length - (int) end[0]);
        assertArrayEquals(expected.toByteArray(), actual);
    }
    @Test public void statefulNonFirstPageDraftRecreationPreservesAllOtherText() throws Exception {
        File file = new File(work, "stateful-draft.txt");
        Charset encoding = Charset.forName("ISO-2022-JP");
        String original = "日本語の文章".repeat(200000) + "\n終端";
        byte[] originalBytes = original.getBytes(encoding);
        assertTrue(originalBytes.length > EditorActivity.LIMIT); Files.write(file.toPath(), originalBytes);
        launch(file, null);
        scenario.onActivity(activity -> {
            assertTrue((boolean) get(activity, "paged"));
            assertTrue((boolean) get(activity, "transcodedPages"));
            assertEquals(encoding, get(activity, "charset")); invoke(activity, "changePage", true);
        });
        await(() -> readyNow() && offset() > 0);
        long start = offset(); long[] end = {0};
        scenario.onActivity(activity -> end[0] = ((PagedTextFile.Page) get(activity, "currentPage")).end());
        String replacement = "変更した草稿\n" + text(); edit(replacement);
        scenario.recreate(); ready();
        assertEquals(start, offset()); assertEquals(replacement, text());
        scenario.onActivity(activity -> {
            assertTrue((boolean) get(activity, "transcodedPages"));
            assertEquals(encoding, get(activity, "charset")); assertTrue((boolean) get(activity, "dirty"));
        });
        assertArrayEquals(originalBytes, Files.readAllBytes(file.toPath()));
        scenario.onActivity(activity -> invoke(activity, "save")); await(() -> !saving() && !dirty());
        byte[] utf8 = original.getBytes(StandardCharsets.UTF_8);
        String expected = new String(utf8, 0, (int) start, StandardCharsets.UTF_8) + replacement
                + new String(utf8, (int) end[0], utf8.length - (int) end[0], StandardCharsets.UTF_8);
        assertEquals(expected, new String(Files.readAllBytes(file.toPath()), encoding));
    }
    @Test public void structuredManifestDraftRestoresWithoutOldCacheAndSavesReadableApk() throws Exception {
        File apk = new File(work, "manifest-fixture.apk");
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("install-fixture.apk");
             OutputStream output = Files.newOutputStream(apk.toPath())) { FileStore.pump(input, output); }
        byte[] original = Files.readAllBytes(apk.toPath());
        Intent intent = new Intent(context, EditorActivity.class); FileStore.local(apk).put(intent);
        intent.putExtra("archivePath", new File(work, "missing-manifest-workspace.apk").getPath())
                .putExtra("archiveEntry", "AndroidManifest.xml").putExtra("mode", "xml");
        launch(apk, intent);
        String manifest = text(); assertTrue(manifest.contains("DualStack Install Fixture"));
        String changed = manifest.replace("DualStack Install Fixture", "Draft Restored Fixture"); edit(changed);
        File[] workspace = {null}; scenario.onActivity(activity -> workspace[0] = (File) get(activity, "archive"));
        assertTrue(workspace[0].isFile()); closeWithCheckpoint();
        await(() -> !workspace[0].isFile());
        intent.putExtra("archivePath", workspace[0].getPath());
        scenario = ActivityScenario.launch(intent); click("恢复草稿"); ready(); rememberId();
        assertEquals(changed, text());
        scenario.onActivity(activity -> {
            assertEquals("xml", get(activity, "mode")); assertEquals("AndroidManifest.xml", get(activity, "entry"));
            assertTrue((boolean) get(activity, "dirty")); assertTrue(((File) get(activity, "archive")).isFile());
        });
        assertArrayEquals(original, Files.readAllBytes(apk.toPath()));
        scenario.onActivity(activity -> invoke(activity, "save")); await(() -> !saving() && !dirty());
        String saved = ApkTools.xmlText(apk, "AndroidManifest.xml");
        assertTrue(saved.contains("Draft Restored Fixture")); assertFalse(saved.contains("DualStack Install Fixture"));
        android.content.pm.PackageInfo parsed = context.getPackageManager().getPackageArchiveInfo(apk.getPath(), 0);
        assertNotNull(parsed); assertEquals("com.dualstack.installfixture", parsed.packageName);
        try (ZipFile zip = new ZipFile(apk)) {
            assertNotNull(zip.getEntry("AndroidManifest.xml"));
            try (InputStream input = zip.getInputStream(zip.getEntry("AndroidManifest.xml"))) { assertTrue(input.read() >= 0); }
        }
    }
    @Test public void abandonedArchiveEntryDraftRebuildsWorkspaceBeforeSave() throws Exception {
        File archive = new File(work, "sample.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(archive.toPath()))) {
            out.putNextEntry(new ZipEntry("note.txt")); out.write("inside".getBytes(StandardCharsets.UTF_8)); out.closeEntry();
            out.putNextEntry(new ZipEntry("keep.txt")); out.write("keep".getBytes(StandardCharsets.UTF_8)); out.closeEntry();
        }
        Intent intent = new Intent(context, EditorActivity.class); FileStore.local(archive).put(intent);
        intent.putExtra("archivePath", new File(work, "missing-workspace.zip").getPath()).putExtra("archiveEntry", "note.txt");
        launch(archive, intent); edit("archive draft"); closeWithCheckpoint();
        scenario = ActivityScenario.launch(intent); click("恢复草稿"); ready(); rememberId();
        assertEquals("archive draft", text());
        assertEquals("inside", new String(ArchiveOps.bytes(archive, "note.txt", 100), StandardCharsets.UTF_8));
        scenario.onActivity(activity -> invoke(activity, "save")); await(() -> !saving() && !dirty());
        assertEquals("archive draft", new String(ArchiveOps.bytes(archive, "note.txt", 100), StandardCharsets.UTF_8));
        assertEquals("keep", new String(ArchiveOps.bytes(archive, "keep.txt", 100), StandardCharsets.UTF_8));
    }
    @Test public void failedSymlinkSaveKeepsDraftAndDoesNotModifyEitherFile() throws Exception {
        File original = new File(work, "real.txt"), link = new File(work, "link.txt");
        Files.write(original.toPath(), "original".getBytes(StandardCharsets.UTF_8)); Os.symlink(original.getPath(), link.getPath());
        launch(link, null); edit("not saved");
        scenario.onActivity(activity -> invoke(activity, "save")); await(() -> !saving());
        EditorDrafts.WORKER.submit(() -> {}).get(10, TimeUnit.SECONDS);
        assertTrue(dirty()); assertTrue(Files.isSymbolicLink(link.toPath()));
        assertEquals("original", new String(Files.readAllBytes(original.toPath()), StandardCharsets.UTF_8));
        assertEquals("not saved", EditorDrafts.read(context, id()).content().toString());
    }
    @Test public void explicitDiscardDoesNotLeaveARecoverableDraft() throws Exception {
        File file = new File(work, "discard.txt"); Files.write(file.toPath(), "original".getBytes(StandardCharsets.UTF_8));
        launch(file, null); edit("discard me"); String id = id();
        scenario.onActivity(activity -> { invoke(activity, "checkpoint", true); invoke(activity, "leave"); });
        click("放弃修改"); scenario.close(); scenario = null;
        EditorDrafts.WORKER.submit(() -> {}).get(10, TimeUnit.SECONDS);
        assertNull(EditorDrafts.read(context, id));
        assertEquals("original", new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }
    @Test public void externalChangeBlocksRestoredDraftFromOverwritingNewSource() throws Exception {
        File file = new File(work, "conflict.txt"); Files.write(file.toPath(), "original".getBytes(StandardCharsets.UTF_8));
        launch(file, null); edit("old draft"); closeWithCheckpoint();
        Files.write(file.toPath(), "changed externally".getBytes(StandardCharsets.UTF_8));
        launchWithoutWaiting(file); click("恢复草稿"); ready(); rememberId(); click("知道了");
        assertEquals("old draft", text());
        scenario.onActivity(activity -> { assertNotNull(get(activity, "blockedSave")); invoke(activity, "save"); });
        await(() -> !saving());
        assertEquals("changed externally", new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        assertTrue(dirty());
    }
    @Test public void readOnlyPreviewDoesNotCreateDrafts() throws Exception {
        File file = new File(work, "readonly.txt"); Files.write(file.toPath(), "original".getBytes(StandardCharsets.UTF_8));
        Intent intent = new Intent(context, EditorActivity.class); FileStore.local(file).put(intent); intent.putExtra("readOnly", true);
        launch(file, intent); edit("programmatic preview change");
        scenario.onActivity(activity -> invoke(activity, "checkpoint", true));
        EditorDrafts.WORKER.submit(() -> {}).get(10, TimeUnit.SECONDS);
        assertNull(EditorDrafts.read(context, id()));
    }
    @Test public void cleanSmaliRecreationKeepsClassTextAndRebuildsMissingPreview() throws Exception {
        File smali = new File(work, "smali"); assertTrue(smali.mkdir());
        String source = ".class public Lfixture/Sample;\n.super Ljava/lang/Object;\n.method public static value()I\n"
                + ".registers 1\nconst/4 v0, 0x1\nreturn v0\n.end method\n";
        Files.write(new File(smali, "Sample.smali").toPath(), source.getBytes(StandardCharsets.UTF_8));
        File dex = new File(work, "classes.dex"); ApkTools.assemble(smali, dex);
        File preview = new File(work, "Sample-preview.smali");
        ApkTools.disassembleClass(dex, "", "Lfixture/Sample;", preview);
        Intent intent = new Intent(context, EditorActivity.class); FileStore.local(dex).put(intent);
        intent.putExtra("preview", preview.getPath()).putExtra("mode", "smali-class").putExtra("archivePath", dex.getPath())
                .putExtra("dexEntry", "").putExtra("descriptor", "Lfixture/Sample;");
        launch(dex, intent);
        String loaded = text(); assertTrue(loaded, loaded.contains("Lfixture/Sample;"));
        scenario.recreate(); ready();
        assertEquals("Clean class text was replaced by an empty draft", loaded, text());
        assertTrue(preview.delete());
        scenario.recreate(); ready();
        assertTrue("Missing preview was not rebuilt from the DEX", text().contains("Lfixture/Sample;"));
        assertFalse(dirty());
        edit(text().replace("0x1", "0x2"));
        scenario.onActivity(activity -> invoke(activity, "save")); await(() -> !saving() && !dirty());
        File check = new File(work, "after-save.smali"); ApkTools.disassembleClass(dex, "", "Lfixture/Sample;", check);
        assertTrue(new String(Files.readAllBytes(check.toPath()), StandardCharsets.UTF_8).contains("0x2"));
    }
    @Test public void revokedDocumentStillShowsKnownDraftReadOnlyWithoutSaving() throws Exception {
        Uri root = Uri.parse(grantFixture().getString("uri"));
        Bundle seed = new Bundle(); seed.putString("parent", "root"); seed.putString("name", "remote.txt");
        seed.putBoolean("directory", false); seed.putString("text", "remote original");
        fixture("seed", seed);
        Uri document = DocumentsContract.buildDocumentUriUsingTree(root, "root/remote.txt");
        try {
            Intent intent = new Intent(context, EditorActivity.class);
            intent.putExtra("uri", document.toString()).putExtra("name", "remote.txt");
            scenario = ActivityScenario.launch(intent); ready(); rememberId();
            edit("remote draft"); String id = id();
            scenario.onActivity(activity -> invoke(activity, "checkpoint", true));
            EditorDrafts.WORKER.submit(() -> {}).get(10, TimeUnit.SECONDS);
            fixture("reset", null);
            scenario.recreate(); click("知道了"); ready();
            assertEquals("remote draft", text());
            scenario.onActivity(activity -> {
                assertTrue((boolean) get(activity, "reviewOnly"));
                assertFalse(editor(activity).isEditable());
                invoke(activity, "save");
            });
            assertFalse(saving());
            assertEquals("remote draft", EditorDrafts.read(context, id).content().toString());
        } finally { fixture("reset", null); }
    }
    @Test public void retiredSessionCannotOverwriteOrDeleteDraftOfNewOwner() throws Exception {
        EditorDrafts stale = drafts(), owner = new EditorDrafts(context, stale.id);
        stale.activate();
        EditorDrafts.State old = stale.remember("document", new JSONObject().put("time", 1), new Content("old session"));
        owner.activate();
        owner.persist(owner.remember("document", new JSONObject().put("time", 2), new Content("new owner"))).get(10, TimeUnit.SECONDS);
        stale.persist(old).get(10, TimeUnit.SECONDS);
        stale.clear().get(10, TimeUnit.SECONDS);
        stale.deactivate();
        assertEquals("new owner", EditorDrafts.read(context, stale.id).content().toString());
        assertNull("Retired owner released the new owner's claim", EditorDrafts.latest(context, "document"));
        owner.deactivate();
        assertEquals(stale.id, EditorDrafts.latest(context, "document").id());
    }
    @Test public void clearedSessionKeepsItsClaimUntilRunningWorkFinishes() throws Exception {
        EditorSession session = new EditorSession();
        session.initialize(context, null); ids.add(session.drafts.id);
        session.drafts.persist(session.drafts.remember("document", new JSONObject().put("time", 1), new Content("in flight")))
                .get(10, TimeUnit.SECONDS);
        session.begin();
        Method cleared = EditorSession.class.getDeclaredMethod("onCleared"); cleared.setAccessible(true); cleared.invoke(session);
        EditorDrafts.WORKER.submit(() -> {}).get(10, TimeUnit.SECONDS);
        assertNull("Claim was released while a save was still running", EditorDrafts.latest(context, "document"));
        session.end();
        EditorDrafts.WORKER.submit(() -> {}).get(10, TimeUnit.SECONDS);
        assertEquals(session.drafts.id, EditorDrafts.latest(context, "document").id());
    }
    private Bundle fixture(String method, Bundle extras) {
        Bundle result = context.getContentResolver().call(Uri.parse("content://" + CountingDocumentsProvider.AUTHORITY), method, null, extras);
        assertNotNull(method, result); return result;
    }
    private Bundle grantFixture() throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        Bundle[] result = {null}; int[] code = {android.app.Activity.RESULT_CANCELED};
        String testPackage = InstrumentationRegistry.getInstrumentation().getContext().getPackageName();
        Intent intent = new Intent(CountingDocumentsProvider.AUTHORITY + ".GRANT")
                .setComponent(new ComponentName(testPackage, CountingDocumentsProvider.GrantReceiver.class.getName()))
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        context.sendOrderedBroadcast(intent, null, new BroadcastReceiver() {
            @Override public void onReceive(Context receiverContext, Intent received) {
                code[0] = getResultCode(); result[0] = getResultExtras(false); finished.countDown();
            }
        }, null, android.app.Activity.RESULT_CANCELED, null, null);
        assertTrue("Fixture grant did not finish", finished.await(30, TimeUnit.SECONDS));
        assertEquals(String.valueOf(result[0]), android.app.Activity.RESULT_OK, code[0]);
        return result[0];
    }
    private EditorDrafts drafts() throws IOException {
        EditorDrafts drafts = new EditorDrafts(context, null); ids.add(drafts.id); return drafts;
    }
    private void launch(File file, Intent override) {
        if (override == null) { override = new Intent(context, EditorActivity.class); FileStore.local(file).put(override); }
        scenario = ActivityScenario.launch(override); ready(); rememberId();
    }
    private void launchWithoutWaiting(File file) {
        Intent intent = new Intent(context, EditorActivity.class); FileStore.local(file).put(intent); scenario = ActivityScenario.launch(intent);
    }
    private void closeWithCheckpoint() throws Exception {
        scenario.onActivity(activity -> invoke(activity, "checkpoint", true));
        EditorDrafts.WORKER.submit(() -> {}).get(10, TimeUnit.SECONDS);
        scenario.close(); scenario = null;
        EditorDrafts.WORKER.submit(() -> {}).get(10, TimeUnit.SECONDS);
    }
    private void rememberId() { ids.add(id()); }
    private String id() {
        String[] result = {null}; scenario.onActivity(activity -> result[0] = ((EditorSession) get(activity, "session")).drafts.id); return result[0];
    }
    private void edit(String value) { scenario.onActivity(activity -> editor(activity).setText(value)); }
    private String text() {
        String[] result = {""}; scenario.onActivity(activity -> result[0] = editor(activity).getText().toString()); return result[0];
    }
    private long offset() {
        long[] result = {0}; scenario.onActivity(activity -> result[0] = (long) get(activity, "offset")); return result[0];
    }
    private boolean saving() { return flag("saving"); }
    private boolean dirty() { return flag("dirty"); }
    private boolean flag(String name) {
        boolean[] result = {false}; scenario.onActivity(activity -> result[0] = (boolean) get(activity, name)); return result[0];
    }
    private void ready() { await(this::readyNow); }
    private boolean readyNow() { return flag("loaded") && !flag("reading"); }
    private void click(String text) {
        UiObject2 button = device.wait(Until.findObject(By.text(text)), 10000); assertNotNull(text, button); button.click();
    }
    private static CodeEditor editor(EditorActivity activity) { return (CodeEditor) get(activity, "editor"); }
    private static Object get(Object target, String name) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void invoke(Object target, String name, Object... arguments) {
        for (Method method : target.getClass().getDeclaredMethods()) if (method.getName().equals(name) && method.getParameterCount() == arguments.length) {
            try { method.setAccessible(true); method.invoke(target, arguments); return; }
            catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        }
        throw new AssertionError(name);
    }
    private static void await(BooleanSupplier predicate) {
        long end = SystemClock.uptimeMillis() + 15000;
        while (!predicate.getAsBoolean() && SystemClock.uptimeMillis() < end) SystemClock.sleep(15);
        assertTrue("Timed out waiting for editor state", predicate.getAsBoolean());
    }
}
