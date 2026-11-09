package com.dualstack.lab;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.widget.TextView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import io.github.rosemoe.sora.widget.CodeEditor;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.zip.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ArchiveRevisionInstrumentedTest {
    private static final String DESCRIPTOR = "Lfixture/Revision;";
    private Context context;
    private File work, source;
    private FileStore store;
    private UiDevice device;
    private ActivityScenario<ArchiveActivity> scenario;
    private ActivityScenario<EditorActivity> editorScenario;
    private ArchiveActivity activity;
    private Set<String> draftsBefore;
    private final Set<File> owned = new HashSet<>();
    private record Snapshot(String revision, byte[] bytes) {}

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        store = new FileStore(context);
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "archive-revision-test-").toFile();
        draftsBefore = drafts();
    }
    @After public void cleanup() throws Exception {
        if (editorScenario != null) editorScenario.close();
        if (scenario != null) scenario.close();
        EditorDrafts.WORKER.submit(() -> {}).get(10, TimeUnit.SECONDS);
        for (String name : drafts()) if (!draftsBefore.contains(name))
            Files.deleteIfExists(new File(context.getFilesDir(), "editor-drafts/" + name).toPath());
        for (File file : owned) if (file.exists()) store.delete(FileStore.local(file));
        if (work != null && work.exists()) store.delete(FileStore.local(work));
    }

    @Test public void externalReplacementBeforeChangePreservesNewEntriesWithoutMtimeChange() throws Exception {
        open(zip("input.zip", Map.of("first.txt", bytes("old"))));
        Snapshot before = snapshot();
        overwriteKeepingMetadata(Map.of("fresh.txt", bytes("new")));
        AtomicInteger ran = new AtomicInteger();
        change(() -> {
            ran.incrementAndGet(); add("local.txt"); return true;
        });
        waitForFailure();
        assertEquals("Conflict must be detected before the workspace job runs", 0, ran.get());
        assertTrue(ArchiveOps.entryNames(source).contains("fresh.txt"));
        assertFalse(ArchiveOps.entryNames(source).contains("local.txt"));
        assertArrayEquals(before.bytes(), snapshot().bytes());
        assertEquals(before.revision(), snapshot().revision());
        assertNotEquals(before.revision(), store.fingerprint(FileStore.local(source)));
    }

    @Test public void consecutiveChangesRebindBaselineToTheActualWorkspace() throws Exception {
        open(zip("input.zip", Map.of("first.txt", bytes("old"))));
        String initial = snapshot().revision();
        change(() -> { add("one.txt"); return true; });
        assertSynchronizedSnapshot();
        assertNotEquals(initial, snapshot().revision());
        change(() -> { add("two.txt"); return true; });
        assertSynchronizedSnapshot();
        assertTrue(ArchiveOps.entryNames(source).containsAll(List.of("first.txt", "one.txt", "two.txt")));
    }

    @Test public void refreshPublishesMatchingRevisionAndCopyThenAllowsChange() throws Exception {
        open(zip("input.zip", Map.of("first.txt", bytes("old"))));
        String previous = snapshot().revision();
        overwriteKeepingMetadata(Map.of("fresh.txt", bytes("new")));
        refreshFromSource();
        assertNotEquals(previous, snapshot().revision());
        assertSynchronizedSnapshot();
        assertTrue(ArchiveOps.entryNames(workspace()).contains("fresh.txt"));
        change(() -> { add("local.txt"); return true; });
        assertSynchronizedSnapshot();
        assertTrue(ArchiveOps.entryNames(source).containsAll(List.of("fresh.txt", "local.txt")));
    }

    @Test public void externalReplacementDuringChangeIsRejectedAndWorkspaceIsRolledBack() throws Exception {
        open(zip("input.zip", Map.of("first.txt", bytes("old"))));
        Snapshot before = snapshot();
        AtomicInteger ran = new AtomicInteger();
        change(() -> {
            ran.incrementAndGet(); add("local.txt");
            overwriteKeepingMetadata(Map.of("fresh.txt", bytes("new")));
            return true;
        });
        waitForFailure();
        assertEquals(1, ran.get());
        assertTrue(ArchiveOps.entryNames(source).contains("fresh.txt"));
        assertFalse(ArchiveOps.entryNames(source).contains("local.txt"));
        assertArrayEquals(before.bytes(), snapshot().bytes());
        assertEquals(before.revision(), snapshot().revision());
    }

    @Test public void failedRefreshDoesNotPublishEitherCopyOrRevision() throws Exception {
        open(zip("input.zip", Map.of("first.txt", bytes("old"))));
        Snapshot before = snapshot();
        Files.write(source.toPath(), bytes("not a zip"));
        scenario.onActivity(value -> invoke(value, "reload", true));
        waitForFailure();
        await(() -> !refreshing());
        assertArrayEquals(before.bytes(), snapshot().bytes());
        assertEquals(before.revision(), snapshot().revision());
    }

    @Test public void ordinaryAndStructuredEditorIntentsCarryWorkspaceRevision() throws Exception {
        open(zip("input.zip", Map.of("first.txt", bytes("old"))));
        String revision = snapshot().revision();
        for (String mode : Arrays.asList(null, "xml", "arsc")) {
            Intent intent;
            synchronized (field(activity, "workspaceLock")) {
                intent = (Intent) call(activity, "editorIntent", "first.txt", mode);
            }
            assertEquals(revision, intent.getStringExtra("sourceRevision"));
            assertEquals(workspace().getAbsolutePath(), intent.getStringExtra("archivePath"));
            assertEquals(mode, intent.getStringExtra("mode"));
        }
    }

    @Test public void oldSmaliPreviewKeepsCapturedRevisionAfterRefreshAndEditorRejectsIt() throws Exception {
        File oldDex = dex("old-dex", 1), newDex = dex("new-dex", 2);
        open(zip("input.apk", Map.of("classes.dex", Files.readAllBytes(oldDex.toPath()))));
        String oldRevision = snapshot().revision();
        Object preview = call(activity, "classPreview", "classes.dex", DESCRIPTOR);
        File previewFile = (File) call(preview, "file"); owned.add(previewFile.getParentFile());
        assertTrue(text(previewFile).contains("0x1"));
        overwriteKeepingMetadata(Map.of("classes.dex", Files.readAllBytes(newDex.toPath())));
        refreshFromSource();
        assertSynchronizedSnapshot();
        assertNotEquals(oldRevision, snapshot().revision());
        // Deliberately run the UI-callback half only after refresh changed the activity field.
        Intent intent = (Intent) call(activity, "classEditorIntent", "classes.dex", DESCRIPTOR, preview);
        assertEquals(oldRevision, intent.getStringExtra("sourceRevision"));
        assertNotEquals(snapshot().revision(), intent.getStringExtra("sourceRevision"));
        assertEquals(previewFile.getAbsolutePath(), intent.getStringExtra("preview"));
        byte[] latest = Files.readAllBytes(source.toPath());
        editorScenario = ActivityScenario.launch(intent);
        waitForFailure();
        editorScenario.onActivity(editor -> {
            assertFalse("Stale preview must not become a loaded editing session", (Boolean) field(editor, "loaded"));
            assertFalse(((CodeEditor) field(editor, "editor")).isEditable());
        });
        assertArrayEquals(latest, Files.readAllBytes(source.toPath()));
    }

    @Test public void refreshedDexCannotRecompileOldProjectAndDoesNotDeleteUserFiles() throws Exception {
        File oldDex = dex("old-dex", 1), newDex = dex("new-dex", 2);
        open(zip("input.apk", Map.of("classes.dex", Files.readAllBytes(oldDex.toPath()))));
        File project = new File(work, "user-project");
        synchronized (field(activity, "workspaceLock")) {
            @SuppressWarnings("unchecked") Map<String, File> projects = (Map<String, File>) field(activity, "projects");
            projects.put("classes.dex", project);
        }
        scenario.onActivity(value -> invoke(value, "disassemble", "classes.dex"));
        assertNotNull(device.wait(Until.findObject(By.text("已输出")), 15000));
        device.findObject(By.text("关闭")).click();
        await(() -> !busy());
        File smali = new File(project, "fixture/Revision.smali");
        assertTrue(smali.isFile());
        File marker = new File(project, "my-notes.txt"); Files.write(marker.toPath(), bytes("keep my work"));
        byte[] smaliBefore = Files.readAllBytes(smali.toPath());
        File revisionFile = (File) call(activity, "projectRevisionFile", project);
        assertEquals(snapshot().revision(), text(revisionFile));
        overwriteKeepingMetadata(Map.of("classes.dex", Files.readAllBytes(newDex.toPath())));
        refreshFromSource();
        byte[] current = Files.readAllBytes(source.toPath());
        scenario.onActivity(value -> invoke(value, "assemble", "classes.dex"));
        UiObject2 compile = device.wait(Until.findObject(By.text("编译")), 5000); assertNotNull(compile); compile.click();
        waitForFailure(); await(() -> !busy());
        assertNotNull(device.findObject(By.textContains("工作区来自旧版本")));
        assertArrayEquals(current, Files.readAllBytes(source.toPath()));
        assertArrayEquals(smaliBefore, Files.readAllBytes(smali.toPath()));
        assertEquals("keep my work", text(marker));
        assertNotEquals(snapshot().revision(), text(revisionFile));
    }

    private void open(File file) {
        source = file;
        Intent intent = new Intent(context, ArchiveActivity.class); FileStore.local(file).put(intent);
        scenario = ActivityScenario.launch(intent);
        scenario.onActivity(value -> activity = value);
        await(() -> {
            String[] status = {""};
            scenario.onActivity(value -> status[0] = ((TextView) field(value, "summary")).getText().toString());
            return status[0].contains(" 项 · ") && snapshot().revision() != null;
        });
        owned.add(workspace().getParentFile());
    }
    private void change(Tasks.Job<Boolean> job) {
        scenario.onActivity(value -> invoke(value, "change", job));
        await(() -> !busy());
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
    private void add(String name) throws Exception {
        Map<String, File> entries = new HashMap<>(); entries.put(name, null);
        ArchiveOps.rewrite(workspace(), null, null, entries);
    }
    private void refreshFromSource() throws Exception {
        String expected = store.fingerprint(FileStore.local(source));
        scenario.onActivity(value -> invoke(value, "reload", true));
        await(() -> expected.equals(snapshot().revision()) && !refreshing());
    }
    private boolean busy() {
        boolean[] value = {false}; scenario.onActivity(a -> value[0] = (Boolean) field(a, "busy")); return value[0];
    }
    private boolean refreshing() {
        boolean[] value = {false};
        scenario.onActivity(a -> value[0] = ((SwipeRefreshLayout) field(a, "refresh")).isRefreshing()); return value[0];
    }
    private Snapshot snapshot() {
        synchronized (field(activity, "workspaceLock")) {
            File file = (File) field(activity, "archive");
            try { return new Snapshot((String) field(activity, "sourceRevision"), file == null ? null : Files.readAllBytes(file.toPath())); }
            catch (IOException error) { throw new AssertionError(error); }
        }
    }
    private File workspace() { return (File) field(activity, "archive"); }
    private void assertSynchronizedSnapshot() throws Exception {
        Snapshot current = snapshot();
        assertEquals(store.fingerprint(FileStore.local(source)), current.revision());
        assertArrayEquals(Files.readAllBytes(source.toPath()), current.bytes());
    }
    private void waitForFailure() {
        assertNotNull("Expected an explicit conflict/error instead of a silent acceptance",
                device.wait(Until.findObject(By.text("操作失败")), 10000));
    }
    /** In-place, same-length ZIP update: inode, size and mtime cannot reveal this change. */
    private void overwriteKeepingMetadata(Map<String, byte[]> entries) throws Exception {
        long size = source.length(); FileTime modified = Files.getLastModifiedTime(source.toPath());
        long inode = android.system.Os.stat(source.getPath()).st_ino;
        File replacement = zip("external.zip", entries);
        byte[] bytes = Files.readAllBytes(replacement.toPath());
        assertEquals("Fixture must not depend on a size change", size, bytes.length);
        Files.write(source.toPath(), bytes); Files.setLastModifiedTime(source.toPath(), modified);
        ArchiveOps.forget(source);
        assertEquals(inode, android.system.Os.stat(source.getPath()).st_ino);
        assertEquals(size, source.length()); assertEquals(modified, Files.getLastModifiedTime(source.toPath()));
    }
    private File zip(String name, Map<String, byte[]> entries) throws Exception {
        File file = new File(work, name);
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(file))) {
            for (String nameInZip : new TreeSet<>(entries.keySet())) {
                byte[] bytes = entries.get(nameInZip); CRC32 crc = new CRC32(); crc.update(bytes);
                ZipEntry entry = new ZipEntry(nameInZip); entry.setMethod(ZipEntry.STORED);
                entry.setTime(1700000000000L); entry.setSize(bytes.length); entry.setCompressedSize(bytes.length); entry.setCrc(crc.getValue());
                output.putNextEntry(entry); output.write(bytes); output.closeEntry();
            }
        }
        return file;
    }
    private File dex(String directory, int value) throws Exception {
        File folder = new File(work, directory); assertTrue(folder.mkdir());
        Files.write(new File(folder, "Revision.smali").toPath(), bytes(".class public " + DESCRIPTOR
                + "\n.super Ljava/lang/Object;\n.method public static value()I\n"
                + ".registers 1\nconst/4 v0, 0x" + value + "\nreturn v0\n.end method\n"));
        File dex = new File(work, directory + ".dex"); ApkTools.assemble(folder, dex); return dex;
    }
    private Set<String> drafts() {
        String[] names = new File(context.getFilesDir(), "editor-drafts").list();
        return names == null ? new HashSet<>() : new HashSet<>(Arrays.asList(names));
    }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String text(File file) throws IOException { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
    private static Object field(Object target, String name) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static Object call(Object target, String name, Object... arguments) throws Exception {
        for (Method method : target.getClass().getDeclaredMethods())
            if (method.getName().equals(name) && method.getParameterCount() == arguments.length) {
                method.setAccessible(true);
                try { return method.invoke(target, arguments); }
                catch (InvocationTargetException error) {
                    if (error.getCause() instanceof Exception exception) throw exception;
                    throw new AssertionError(error.getCause());
                }
            }
        throw new AssertionError("No method " + name);
    }
    private static void invoke(Object target, String name, Object... arguments) {
        try { call(target, name, arguments); } catch (Exception error) { throw new AssertionError(error); }
    }
    private static void await(BooleanSupplier condition) {
        long until = SystemClock.uptimeMillis() + 15000;
        while (SystemClock.uptimeMillis() < until) {
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(30);
        }
        fail("Timed out waiting for activity work");
    }
}
