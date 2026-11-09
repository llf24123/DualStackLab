package com.dualstack.lab;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import io.github.rosemoe.sora.widget.CodeEditor;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class VersionConflictInstrumentedTest {
    private static final long TIMEOUT = 15000;
    private Context context;
    private FileStore store;
    private File work;
    private UiDevice device;
    private ActivityScenario<EditorActivity> scenario;
    private final Map<String, EditorDrafts> ownedDrafts = new LinkedHashMap<>();

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        store = new FileStore(context);
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "version-conflict-").toFile();
    }

    @After public void cleanup() throws Exception {
        try {
            if (scenario != null) scenario.close();
        } finally {
            try {
                // Closing can enqueue a last checkpoint. Clear only the sessions captured from our editors.
                EditorDrafts.WORKER.submit(() -> {}).get(10, TimeUnit.SECONDS);
                for (EditorDrafts drafts : ownedDrafts.values()) drafts.clear().get(10, TimeUnit.SECONDS);
            } finally {
                if (work != null && work.exists()) Files.walkFileTree(work.toPath(), new SimpleFileVisitor<Path>() {
                    @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                        Files.delete(path); return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult postVisitDirectory(Path path, IOException error) throws IOException {
                        if (error != null) throw error;
                        Files.delete(path); return FileVisitResult.CONTINUE;
                    }
                });
            }
        }
    }

    @Test public void fingerprintRejectsStaleWriteAfterSameMetadataInPlaceChange() throws Exception {
        File original = file("original.txt", "original\n"), payload = file("payload.txt", "my edit\n");
        FileStore.Node node = FileStore.local(original);
        FileStore.FileState before = FileStore.FileState.read(original);
        FileTime modified = Files.getLastModifiedTime(original.toPath());
        String revision = store.fingerprint(node);
        assertEquals(before.fingerprint(), revision);
        assertEquals(sha256("original\n"), before.digest());

        overwriteInPlacePreservingMetadata(original, "external\n");
        FileStore.FileState external = FileStore.FileState.read(original);
        assertSameMetadata(before, external);
        assertEquals(sha256("external\n"), external.digest());
        String externalRevision = store.fingerprint(node);
        assertNotEquals("Content, not metadata, must change the fingerprint", revision, externalRevision);
        assertEquals(external.fingerprint(), externalRevision);

        IOException conflict = assertThrows(IOException.class, () -> store.write(node, payload, revision));
        assertTrue(conflict.getMessage(), conflict.getMessage().contains("外部改变"));
        assertEquals("external\n", text(original));
        assertEquals("my edit\n", text(payload));
        assertEquals(external, FileStore.FileState.read(original));
        assertEquals(modified, Files.getLastModifiedTime(original.toPath()));
        assertEquals(externalRevision, store.fingerprint(node));
    }

    @Test public void publishRejectsSameMetadataContentChangeAndKeepsTemporary() throws Exception {
        File original = file("original.txt", "original\n"), temporary = file("temporary.txt", "edited\n");
        FileStore.FileState before = FileStore.FileState.read(original);
        FileTime modified = Files.getLastModifiedTime(original.toPath());

        overwriteInPlacePreservingMetadata(original, "external\n");
        FileStore.FileState external = FileStore.FileState.read(original);
        assertSameMetadata(before, external);
        assertNotEquals("FileState must include a content digest", before.digest(), external.digest());
        assertNotEquals(before, external);

        IOException conflict = assertThrows(IOException.class, () -> FileStore.publish(temporary, original, before));
        assertTrue(conflict.getMessage(), conflict.getMessage().contains("外部改变"));
        assertEquals("external\n", text(original));
        assertTrue("Rejected publication must retain its staged file", temporary.isFile());
        assertEquals("edited\n", text(temporary));
        assertEquals(external, FileStore.FileState.read(original));
        assertEquals(modified, Files.getLastModifiedTime(original.toPath()));
    }

    @Test public void expectedWriteReturnsFingerprintAndSupportsConsecutiveSaves() throws Exception {
        File original = file("original.txt", "original\n"), payload = file("payload.txt", "first saved\n");
        FileStore.Node node = FileStore.local(original);
        String initial = store.fingerprint(node);

        String first = store.write(node, payload, initial);
        assertNotNull(first);
        assertEquals("first saved\n", text(original));
        assertEquals(store.fingerprint(node), first);
        assertEquals(FileStore.FileState.read(original).fingerprint(), first);
        assertNotEquals(initial, first);

        Files.write(payload.toPath(), "second saved\n".getBytes(StandardCharsets.UTF_8));
        String second = store.write(node, payload, first);
        assertNotNull(second);
        assertEquals("second saved\n", text(original));
        assertEquals(store.fingerprint(node), second);
        assertEquals(FileStore.FileState.read(original).fingerprint(), second);
        assertNotEquals(first, second);
    }

    @Test public void editorRejectsSameMetadataExternalChangeAndKeepsDirtyDraft() throws Exception {
        File original = file("editor-conflict.txt", "original\n");
        FileStore.Node node = FileStore.local(original);
        String revision = store.fingerprint(node);
        FileStore.FileState before = FileStore.FileState.read(original);
        FileTime modified = Files.getLastModifiedTime(original.toPath());
        launch(original, null);
        scenario.onActivity(activity -> assertEquals(revision, session(activity).baseline));

        overwriteInPlacePreservingMetadata(original, "external\n");
        FileStore.FileState external = FileStore.FileState.read(original);
        assertSameMetadata(before, external);
        String externalRevision = store.fingerprint(node);
        assertNotEquals(revision, externalRevision);
        edit("unsaved editor changes\n");
        save();

        UiObject2 error = device.wait(Until.findObject(By.textContains("原文件已在外部改变")), TIMEOUT);
        assertNotNull("Editor must display the source-change error, not report a successful save", error);
        assertTrue(error.getText(), error.getText().contains("草稿已保留"));
        assertTrue(device.hasObject(By.text("操作失败")));
        awaitSaveFinished();
        scenario.onActivity(activity -> {
            assertTrue("Failed save must remain dirty", (boolean) get(activity, "dirty"));
            assertTrue(editor(activity).isEditable());
            assertEquals("unsaved editor changes\n", editor(activity).getText().toString());
            assertEquals("Failed save must not adopt the external version", revision, session(activity).baseline);
            assertNotNull(session(activity).memory);
            assertTrue(session(activity).memory.metadata().optBoolean("dirty"));
            assertEquals("unsaved editor changes\n", session(activity).memory.content().toString());
        });
        assertEquals("external\n", text(original));
        assertEquals(external, FileStore.FileState.read(original));
        assertEquals(modified, Files.getLastModifiedTime(original.toPath()));
        assertEquals(externalRevision, store.fingerprint(node));

        EditorDrafts.WORKER.submit(() -> {}).get(10, TimeUnit.SECONDS);
        EditorDrafts.State draft = EditorDrafts.read(context, draftId());
        assertNotNull("Failed save must retain a recoverable disk draft", draft);
        assertTrue(draft.metadata().optBoolean("dirty"));
        assertEquals(revision, draft.metadata().getString("baseline"));
        assertEquals("unsaved editor changes\n", draft.content().toString());
    }

    @Test public void editorUpdatesSourceRevisionAcrossSaveAndRecreation() throws Exception {
        File original = file("editor-recreate.txt", "original\n");
        FileStore.Node node = FileStore.local(original);
        String initial = store.fingerprint(node);
        launch(original, initial);
        assertEditorRevision(initial);
        String id = draftId();
        edit("first editor save\n");
        save(); awaitSaveFinished();
        scenario.onActivity(activity -> assertFalse("First save failed", (boolean) get(activity, "dirty")));
        assertEquals("first editor save\n", text(original));
        String first = store.fingerprint(node);
        assertNotEquals(initial, first);
        assertEditorRevision(first);
        assertNull(EditorDrafts.read(context, id));

        EditorActivity[] previous = {null};
        scenario.onActivity(activity -> {
            previous[0] = activity;
            activity.getIntent().putExtra("sourceRevision", initial);
        });
        scenario.recreate(); rememberDraft(); awaitLoaded("first editor save\n");
        scenario.onActivity(activity -> {
            assertNotSame("Test must use a recreated Activity", previous[0], activity);
            assertFalse((boolean) get(activity, "dirty"));
        });
        assertEquals(id, draftId());
        assertEditorRevision(first);
        assertEquals("first editor save\n", text(original));

        edit("second editor save after recreation\n");
        save(); awaitSaveFinished();
        scenario.onActivity(activity -> assertFalse("Own saved revision was treated as a stale preview", (boolean) get(activity, "dirty")));
        assertEquals("second editor save after recreation\n", text(original));
        String second = store.fingerprint(node);
        assertNotEquals(first, second);
        assertEditorRevision(second);
        assertNull(EditorDrafts.read(context, id));
        assertFalse(device.hasObject(By.text("操作失败")));
    }

    private void launch(File file, String sourceRevision) throws Exception {
        Intent intent = new Intent(context, EditorActivity.class);
        FileStore.local(file).put(intent);
        if (sourceRevision != null) intent.putExtra("sourceRevision", sourceRevision);
        scenario = ActivityScenario.launch(intent);
        rememberDraft();
        awaitLoaded(text(file));
    }

    private void rememberDraft() {
        scenario.onActivity(activity -> {
            EditorDrafts drafts = session(activity).drafts;
            assertNotNull(drafts);
            ownedDrafts.put(drafts.id, drafts);
        });
    }

    private String draftId() {
        String[] result = {null};
        scenario.onActivity(activity -> result[0] = session(activity).drafts.id);
        assertTrue("Only this editor's draft may be inspected or cleared", ownedDrafts.containsKey(result[0]));
        return result[0];
    }

    private void awaitLoaded(String expected) {
        awaitEditor("Editor did not load the expected ordinary text", activity ->
                (boolean) get(activity, "loaded") && !(boolean) get(activity, "reading")
                        && !(boolean) get(activity, "saving") && editor(activity).isEditable()
                        && expected.equals(editor(activity).getText().toString()));
        scenario.onActivity(activity -> {
            assertFalse((boolean) get(activity, "paged"));
            assertFalse((boolean) get(activity, "readOnly"));
            assertFalse((boolean) get(activity, "reviewOnly"));
            assertNull(get(activity, "preview"));
            assertNull(get(activity, "blockedSave"));
        });
    }

    private void edit(String value) {
        scenario.onActivity(activity -> {
            assertTrue(editor(activity).isEditable());
            editor(activity).setText(value);
            assertTrue("Body edit must make the editor dirty", (boolean) get(activity, "dirty"));
            assertEquals(value, editor(activity).getText().toString());
        });
    }

    private void save() {
        scenario.onActivity(activity -> {
            assertFalse((boolean) get(activity, "saving"));
            try {
                Method method = EditorActivity.class.getDeclaredMethod("save");
                method.setAccessible(true); method.invoke(activity);
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            // The completion callback cannot run until this main-thread action returns.
            assertTrue("Save was not started", (boolean) get(activity, "saving"));
        });
    }

    private void awaitSaveFinished() {
        awaitEditor("Editor save did not finish", activity ->
                !(boolean) get(activity, "saving") && !session(activity).saving);
    }

    private void assertEditorRevision(String expected) {
        scenario.onActivity(activity -> {
            assertEquals("Intent must carry the editor's newly saved source version", expected,
                    activity.getIntent().getStringExtra("sourceRevision"));
            assertEquals(expected, session(activity).baseline);
            assertNull(get(activity, "blockedSave"));
        });
    }

    private void awaitEditor(String message, Predicate<EditorActivity> condition) {
        long end = SystemClock.uptimeMillis() + TIMEOUT;
        do {
            boolean[] ready = {false};
            scenario.onActivity(activity -> ready[0] = condition.test(activity));
            if (ready[0]) return;
            SystemClock.sleep(15);
        } while (SystemClock.uptimeMillis() < end);
        fail(message);
    }

    /** No rename/replacement: overwrite the existing inode, then restore even sub-second mtime. */
    private static void overwriteInPlacePreservingMetadata(File file, String value) throws Exception {
        FileStore.FileState before = FileStore.FileState.read(file);
        FileTime modified = Files.getLastModifiedTime(file.toPath());
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        assertEquals("Fixture must have the same UTF-8 byte length", before.size(), (long) bytes.length);
        Files.write(file.toPath(), bytes, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.setLastModifiedTime(file.toPath(), modified);
        assertEquals("Full mtime must be restored, not just whole seconds", modified, Files.getLastModifiedTime(file.toPath()));
        FileStore.FileState after = FileStore.FileState.read(file);
        assertSameMetadata(before, after);
        assertNotEquals("Fixture must really change the content", before.digest(), after.digest());
        assertEquals(value, text(file));
    }

    private static void assertSameMetadata(FileStore.FileState before, FileStore.FileState after) {
        assertEquals("device changed", before.device(), after.device());
        assertEquals("inode changed: fixture was not rewritten in place", before.inode(), after.inode());
        assertEquals("length changed", before.size(), after.size());
        assertEquals("mtime changed", before.modified(), after.modified());
        assertEquals("mode changed", before.mode(), after.mode());
        assertEquals("uid changed", before.uid(), after.uid());
        assertEquals("gid changed", before.gid(), after.gid());
    }

    private File file(String name, String value) throws IOException {
        File file = new File(work, name);
        Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static String text(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static String sha256(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest) hex.append(Character.forDigit((value & 255) >>> 4, 16))
                .append(Character.forDigit(value & 15, 16));
        return hex.toString();
    }

    private static EditorSession session(EditorActivity activity) { return (EditorSession) get(activity, "session"); }
    private static CodeEditor editor(EditorActivity activity) { return (CodeEditor) get(activity, "editor"); }
    private static Object get(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true); return field.get(target);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
}
