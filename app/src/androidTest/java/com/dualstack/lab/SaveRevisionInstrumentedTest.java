package com.dualstack.lab;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
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
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import static org.junit.Assert.*;

/** Real Activity + SAF backup-read gates: no sample is installed or executed by these tests. */
@RunWith(AndroidJUnit4.class)
public class SaveRevisionInstrumentedTest {
    private static final long TIMEOUT = 15000;
    private static final String A = "A", B = "A-B";
    private static final Uri CONTROL = Uri.parse("content://" + CountingDocumentsProvider.AUTHORITY);
    private Context context;
    private FileStore store;
    private UiDevice device;
    private ActivityScenario<EditorActivity> scenario;
    private Uri rootUri, sourceUri;
    private String sourceName, initialRevision;
    private Map<String, ?> recoveryPreferences, displayPreferences;
    private final Map<String, EditorDrafts> ownedDrafts = new LinkedHashMap<>();
    private final Set<String> recoveryIds = new HashSet<>();
    private CountDownLatch releaseDraftWorker;

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        store = new FileStore(context);
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        recoveryPreferences = new HashMap<>(context.getSharedPreferences("SafRecovery", 0).getAll());
        displayPreferences = new HashMap<>(context.getSharedPreferences("editor-display", 0).getAll());
        SafRecovery.setEnabled(context, true);
        SafRecovery.setBackupDirectory(context, null);
        rootUri = Uri.parse(grantFixture().getString("uri"));
        sourceName = "save-revision-" + UUID.randomUUID() + ".txt";
        Bundle seed = new Bundle(); seed.putString("name", sourceName); seed.putString("text", "original");
        fixture("seed", seed);
        sourceUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, "root/" + sourceName);
        initialRevision = fingerprint();
    }

    @After public void cleanup() throws Exception {
        try {
            if (rootUri != null) {
                fixture("configure", new Bundle()); // Disarm any next gate even when setup/assertion failed mid-chain.
                fixture("releaseRead", null);
            }
            if (releaseDraftWorker != null) releaseDraftWorker.countDown();
            if (scenario != null) {
                try { awaitSaveFinished(); }
                finally { scenario.close(); scenario = null; }
            }
        } finally {
            try {
                EditorDrafts.WORKER.submit(() -> {}).get(10, TimeUnit.SECONDS);
                // Never diff/delete the global drafts directory: only clear sessions we actually opened.
                for (EditorDrafts drafts : ownedDrafts.values()) drafts.clear().get(10, TimeUnit.SECONDS);
                rememberRecoveryRecords(false);
                for (String id : recoveryIds) SafRecovery.delete(context, id);
                if (rootUri != null) fixture("reset", null);
            } finally {
                if (recoveryPreferences != null) restorePreferences("SafRecovery", recoveryPreferences);
                if (displayPreferences != null) restorePreferences("editor-display", displayPreferences);
            }
        }
    }

    @Test public void toolbarMenuAndDirectBodyControlsCannotUndoOrRedoDuringBackup() throws Exception {
        launchDocument("original");
        for (boolean undo : new boolean[]{true, false}) {
            scenario.onActivity(activity -> {
                CodeEditor editor = editor(activity);
                editor.setText(undo ? A : B);
                editor.setSelection(0, editor.getText().length());
                editor.insertText(undo ? "-B" : "-C", 2);
                if (undo) {
                    editor.undo(); assertEquals(A, editor.getText().toString());
                    editor.redo();
                } else {
                    editor.undo(); assertEquals(B, editor.getText().toString());
                    editor.redo(); assertEquals(B + "-C", editor.getText().toString());
                    editor.undo();
                }
                assertEquals("The fixture must have a real undo/redo operation available", B, editor.getText().toString());
                invoke(activity, "openSearch");
                ((EditText) get(activity, "findInput")).setText(A);
                ((EditText) get(activity, "replaceInput")).setText("wrong");
            });
            blockBackupRead(() -> scenario.onActivity(activity -> invoke(activity, "save")));
            try {
                scenario.onActivity(activity -> {
                    assertFalse(editor(activity).isEditable());
                    View control = described(activity.getWindow().getDecorView(), undo ? "撤销 / 上一页" : "重做 / 下一页");
                    assertNotNull(control); assertFalse("Ordinary history buttons must be disabled", control.isEnabled());
                    control.performClick(); // Deliberate direct listener invocation, even on a disabled View.
                    invoke(activity, undo ? "undo" : "redo");
                    // Re-enable Sora alone to prove each app-level mutation entry checks the save state too.
                    editor(activity).setEditable(true);
                    invoke(activity, undo ? "undo" : "redo");
                    invoke(activity, "insertSymbol", "!");
                    invoke(activity, "replaceOne"); invoke(activity, "replaceAll");
                    assertEquals(B, editor(activity).getText().toString());
                    editor(activity).setEditable(false);
                    invoke(activity, "menu");
                });
                click(undo ? "撤销" : "重做");
                scenario.onActivity(activity -> assertEquals("Menu history must also be guarded", B, editor(activity).getText().toString()));
            } finally { fixture("releaseRead", null); }
            awaitSaveFinished(); assertEquals(B, sourceText());
            scenario.onActivity(activity -> { assertFalse((boolean) get(activity, "dirty")); invoke(activity, "closeSearch"); });
        }
    }

    @Test public void bypassedContentChangeKeepsNewDraftAndBaselineAndQueuedBackWarns() throws Exception {
        launchDocument("original"); edit(B);
        String id = draftId();
        blockBackupRead(() -> scenario.onActivity(activity -> invoke(activity, "save")));
        try {
            scenario.onActivity(activity -> {
                assertFalse(editor(activity).isEditable());
                editor(activity).setText(A); // Public Sora API bypasses editable; this must emit ContentChangeEvent.
                assertEquals(A, editor(activity).getText().toString());
                invoke(activity, "leave");
                assertFalse(activity.isFinishing());
                assertNotNull(get(activity, "afterSave"));
            });
        } finally { fixture("releaseRead", null); }
        awaitSaveFinished();
        assertEquals("The captured snapshot, not the later UI body, is the saved payload", B, sourceText());
        String savedRevision = fingerprint(); assertNotEquals(initialRevision, savedRevision);
        assertDirtyBody(A, savedRevision);
        assertDraft(id, A, savedRevision);
        assertNotNull("Queued Back must use the usual unsaved dialog", device.wait(Until.findObject(By.text("修改尚未保存")), TIMEOUT));
        scenario.onActivity(activity -> assertFalse(activity.isFinishing()));
        click("继续编辑");
        scenario.recreate(); rememberDraft(); awaitLoaded(A);
        assertDirtyBody(A, savedRevision); assertDraft(id, A, savedRevision);
        // Saving after restoration must use the newly published B baseline, not reject our own write as stale.
        scenario.onActivity(activity -> invoke(activity, "save")); awaitSaveFinished();
        assertEquals(A, sourceText());
        scenario.onActivity(activity -> assertFalse((boolean) get(activity, "dirty")));
        drainDrafts(); assertNull(EditorDrafts.read(context, id));
    }

    @Test public void saveFromLeaveDialogCannotFinishAfterANewerBodyChange() throws Exception {
        launchDocument("original"); edit(B);
        scenario.onActivity(activity -> invoke(activity, "leave"));
        assertNotNull(device.wait(Until.findObject(By.text("修改尚未保存")), TIMEOUT));
        blockBackupRead(() -> click("保存")); // Exercises save(done = finish), not only toolbar save().
        try { scenario.onActivity(activity -> editor(activity).setText(A)); }
        finally { fixture("releaseRead", null); }
        awaitSaveFinished();
        assertNotNull(device.wait(Until.findObject(By.text("修改尚未保存")), TIMEOUT));
        scenario.onActivity(activity -> assertFalse("A stale save completion must not call finish", activity.isFinishing()));
        assertEquals(B, sourceText()); assertDirtyBody(A, fingerprint()); assertDraft(draftId(), A, fingerprint());
        click("继续编辑");
    }

    @Test public void normalSaveDoesNotWaitForDraftWorkerAndRecreationUsesSavedRevision() throws Exception {
        launchDocument("original"); edit(B); String id = draftId();
        scenario.onActivity(activity -> invoke(activity, "checkpoint", true));
        drainDrafts(); assertNotNull(EditorDrafts.read(context, id));
        blockBackupRead(() -> scenario.onActivity(activity -> invoke(activity, "save")));
        CountDownLatch entered = new CountDownLatch(1);
        releaseDraftWorker = new CountDownLatch(1);
        Future<?> blocker = EditorDrafts.WORKER.submit(() -> {
            entered.countDown();
            try { if (!releaseDraftWorker.await(30, TimeUnit.SECONDS)) throw new AssertionError("Draft-worker gate timed out"); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        try {
            fixture("releaseRead", null);
            awaitSaveFinished(); // Neither worker nor UI may Future.get() the queued clear.
            scenario.onActivity(activity -> {
                assertFalse((boolean) get(activity, "dirty"));
                assertEquals(B, editor(activity).getText().toString());
                assertNull(session(activity).drafts.current());
                assertNotNull(session(activity).memory);
                assertFalse(session(activity).memory.metadata().optBoolean("dirty"));
            });
        } finally { releaseDraftWorker.countDown(); }
        blocker.get(10, TimeUnit.SECONDS); drainDrafts();
        assertEquals(B, sourceText()); assertNull(EditorDrafts.read(context, id));
        String savedRevision = fingerprint();
        scenario.onActivity(activity -> {
            assertEquals(savedRevision, session(activity).baseline);
            activity.getIntent().putExtra("sourceRevision", initialRevision); // Saved Bundle must override this stale extra.
        });
        scenario.recreate(); rememberDraft(); awaitLoaded(B);
        scenario.onActivity(activity -> {
            assertFalse((boolean) get(activity, "dirty"));
            assertEquals(savedRevision, session(activity).baseline);
            assertEquals(savedRevision, activity.getIntent().getStringExtra("sourceRevision"));
            assertNull(get(activity, "blockedSave"));
        });
        assertEquals(id, draftId());
        scenario.onActivity(activity -> {
            invoke(activity, "leave");
            assertTrue("A clean save should still allow immediate exit", activity.isFinishing());
        });
        assertTrue(device.wait(Until.gone(By.text("修改尚未保存")), 1000));
        scenario.close(); scenario = null;
    }

    @Test public void queuedClearCannotDeleteAnEditMadeImmediatelyAfterCleanSave() throws Exception {
        launchDocument("original"); edit(B);
        blockBackupRead(() -> scenario.onActivity(activity -> invoke(activity, "save")));
        CountDownLatch entered = new CountDownLatch(1); releaseDraftWorker = new CountDownLatch(1);
        Future<?> blocker = EditorDrafts.WORKER.submit(() -> {
            entered.countDown();
            try { releaseDraftWorker.await(30, TimeUnit.SECONDS); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        try {
            fixture("releaseRead", null); awaitSaveFinished();
            edit(A);
            scenario.onActivity(activity -> invoke(activity, "checkpoint", true));
        } finally { releaseDraftWorker.countDown(); }
        blocker.get(10, TimeUnit.SECONDS);
        assertEquals(B, sourceText()); assertDraft(draftId(), A, fingerprint());
    }

    @Test public void recreationWhileSaveIsBlockedPreservesBypassedBodyAndNewBaseline() throws Exception {
        launchDocument("original"); edit(B);
        blockBackupRead(() -> scenario.onActivity(activity -> invoke(activity, "save")));
        try {
            scenario.onActivity(activity -> editor(activity).setText(A));
            scenario.recreate(); rememberDraft();
            scenario.onActivity(activity -> assertFalse("Recreated editor waits for save finalization", (boolean) get(activity, "loaded")));
        } finally { fixture("releaseRead", null); }
        awaitLoaded(A); awaitSaveFinished();
        assertEquals(B, sourceText()); assertDirtyBody(A, fingerprint()); assertDraft(draftId(), A, fingerprint());
        scenario.onActivity(activity -> assertTrue("Recreated caller must inherit the completed save", session(activity).sourceSaved));
    }

    @Test public void queuedPageAndSaveThenPageRecheckNewerBodyInsteadOfDroppingIt() throws Exception {
        byte[] original = new byte[EditorActivity.LIMIT + EditorActivity.PAGE + 17]; Arrays.fill(original, (byte) 'x');
        // Use SAF for the same deterministic backup gate, but keep every byte in this test's own document.
        try (OutputStream output = context.getContentResolver().openOutputStream(sourceUri, "wt")) {
            assertNotNull(output); output.write(original);
        }
        initialRevision = fingerprint(); launchDocument(null);
        scenario.onActivity(activity -> assertTrue((boolean) get(activity, "paged")));
        edit(B);
        blockBackupRead(() -> scenario.onActivity(activity -> invoke(activity, "save")));
        try {
            scenario.onActivity(activity -> {
                editor(activity).setText(A);
                View next = described(activity.getWindow().getDecorView(), "重做 / 下一页");
                assertNotNull(next); assertTrue("Paged navigation remains queueable during save", next.isEnabled()); next.performClick();
                assertNotNull(get(activity, "afterSave"));
            });
        } finally { fixture("releaseRead", null); }
        awaitSaveFinished();
        assertNotNull(device.wait(Until.findObject(By.text("当前分段尚未保存")), TIMEOUT));
        scenario.onActivity(activity -> { assertEquals(0L, get(activity, "offset")); assertEquals((long) B.length(), ((PagedTextFile.Page) get(activity, "currentPage")).end()); });
        assertDirtyBody(A, fingerprint()); assertDraft(draftId(), A, fingerprint());
        byte[] expected = new byte[B.length() + original.length - EditorActivity.PAGE];
        System.arraycopy(B.getBytes(StandardCharsets.UTF_8), 0, expected, 0, B.length());
        Arrays.fill(expected, B.length(), expected.length, (byte) 'x');
        assertArrayEquals(expected, sourceBytes());
        // The dialog's save-and-page continuation must also recheck dirty, not call its raw navigate Runnable.
        blockBackupRead(() -> click("保存并翻页"));
        try { scenario.onActivity(activity -> editor(activity).setText("newer-page")); }
        finally { fixture("releaseRead", null); }
        awaitSaveFinished();
        assertNotNull(device.wait(Until.findObject(By.text("当前分段尚未保存")), TIMEOUT));
        scenario.onActivity(activity -> { assertEquals(0L, get(activity, "offset")); assertFalse(activity.isFinishing()); });
        assertDirtyBody("newer-page", fingerprint()); assertDraft(draftId(), "newer-page", fingerprint());
        click("继续编辑");
    }

    private void launchDocument(String expected) {
        Intent intent = new Intent(context, EditorActivity.class);
        intent.putExtra("uri", sourceUri.toString()).putExtra("name", sourceName).putExtra("sourceRevision", initialRevision);
        scenario = ActivityScenario.launch(intent); rememberDraft(); awaitLoaded(expected);
    }
    private void awaitLoaded(String expected) {
        awaitEditor("Editor did not load", activity -> (boolean) get(activity, "loaded") && !(boolean) get(activity, "reading")
                && !session(activity).saving && editor(activity).isEditable()
                && (expected == null || expected.equals(editor(activity).getText().toString())));
        scenario.onActivity(activity -> { assertNull(get(activity, "blockedSave")); assertFalse((boolean) get(activity, "reviewOnly")); });
    }
    private void edit(String text) {
        scenario.onActivity(activity -> { assertTrue(editor(activity).isEditable()); editor(activity).setText(text); assertTrue((boolean) get(activity, "dirty")); });
    }
    private void assertDirtyBody(String text, String revision) {
        scenario.onActivity(activity -> {
            assertFalse(activity.isFinishing()); assertTrue((boolean) get(activity, "dirty")); assertTrue(editor(activity).isEditable());
            assertEquals(text, editor(activity).getText().toString());
            assertEquals(revision, session(activity).baseline);
            assertEquals(revision, activity.getIntent().getStringExtra("sourceRevision"));
            assertNotNull(session(activity).memory); assertTrue(session(activity).memory.metadata().optBoolean("dirty"));
            assertEquals(text, session(activity).memory.content().toString()); assertNull(get(activity, "blockedSave"));
        });
    }
    private void assertDraft(String id, String text, String revision) throws Exception {
        drainDrafts();
        assertTrue("Only inspect our captured sessions", ownedDrafts.containsKey(id));
        EditorDrafts.State state = EditorDrafts.read(context, id);
        assertNotNull("Unsaved body must remain recoverable on disk", state);
        assertTrue(state.metadata().optBoolean("dirty")); assertEquals(text, state.content().toString());
        assertEquals(revision, state.metadata().getString("baseline"));
    }
    private void rememberDraft() {
        scenario.onActivity(activity -> { EditorDrafts drafts = session(activity).drafts; ownedDrafts.put(drafts.id, drafts); });
    }
    private String draftId() {
        String[] id = {null}; scenario.onActivity(activity -> id[0] = session(activity).drafts.id);
        assertTrue(ownedDrafts.containsKey(id[0])); return id[0];
    }
    private void awaitSaveFinished() {
        awaitEditor("Save did not finish", activity -> !(boolean) get(activity, "saving") && !session(activity).saving);
    }
    private void awaitEditor(String message, Predicate<EditorActivity> condition) {
        long end = SystemClock.uptimeMillis() + TIMEOUT;
        do {
            boolean[] ready = {false}; scenario.onActivity(activity -> ready[0] = condition.test(activity));
            if (ready[0]) return; SystemClock.sleep(15);
        } while (SystemClock.uptimeMillis() < end);
        fail(message);
    }

    /** Gate #1: Activity SHA check; #2: FileStore SHA check; #3: SafRecovery's actual pre-write backup. */
    private void blockBackupRead(Runnable startSave) throws Exception {
        fixture("clearCounters", null); configureReadGate(); startSave.run();
        for (int read = 1; read <= 3; read++) {
            long deadline = SystemClock.uptimeMillis() + TIMEOUT; boolean held = false;
            do {
                Bundle stats = fixture("stats", null), state = fixture("readState", null);
                assertNull(state.getString("error")); assertEquals("Must not open wt before the backup completes", 0, stats.getInt("writeOpens"));
                if (stats.getInt("readOpens") == read && state.getBoolean("opened") && state.getBoolean("prefixWritten")) { held = true; break; }
                SystemClock.sleep(10);
            } while (SystemClock.uptimeMillis() < deadline);
            assertTrue("Read gate " + read + " was not reached", held);
            if (read < 3) { configureReadGate(); fixture("releaseRead", null); }
        }
        rememberRecoveryRecords(true);
        scenario.onActivity(activity -> { assertTrue((boolean) get(activity, "saving")); assertTrue(session(activity).saving); });
    }
    private void configureReadGate() {
        Bundle options = new Bundle(); options.putString("blockReadName", sourceName); fixture("configure", options);
    }
    /** Inspect only matching metadata: entries()/pending() deliberately take the in-flight backup's IO lock. */
    private void rememberRecoveryRecords(boolean requireCopying) throws Exception {
        if (context == null || sourceUri == null) return;
        File[] directories = SafRecovery.directory(context).listFiles(); boolean copying = false;
        if (directories != null) for (File directory : directories) {
            File metadata = new File(directory, SafRecovery.METADATA);
            if (!metadata.isFile()) continue;
            Properties properties = new Properties();
            try (InputStream input = new FileInputStream(metadata)) { properties.load(input); }
            catch (IOException | IllegalArgumentException unrelatedOrRetired) { continue; }
            if (!sourceUri.toString().equals(properties.getProperty("uri"))) continue;
            recoveryIds.add(directory.getName()); copying |= "copying".equals(properties.getProperty("state"));
        }
        if (requireCopying) assertTrue("The held read must belong to our durable SAF backup, not an earlier fingerprint", copying);
    }
    private byte[] sourceBytes() throws IOException {
        try (InputStream input = context.getContentResolver().openInputStream(sourceUri)) {
            assertNotNull(input); ByteArrayOutputStream output = new ByteArrayOutputStream(); FileStore.pump(input, output); return output.toByteArray();
        }
    }
    private String sourceText() throws IOException { return new String(sourceBytes(), StandardCharsets.UTF_8); }
    private String fingerprint() throws IOException { return store.fingerprint(store.document(sourceUri)); }
    private static void drainDrafts() throws Exception { EditorDrafts.WORKER.submit(() -> {}).get(10, TimeUnit.SECONDS); }
    private Bundle fixture(String method, Bundle options) {
        Bundle result = context.getContentResolver().call(CONTROL, method, null, options); assertNotNull(method, result); return result;
    }
    private Bundle grantFixture() throws Exception {
        CountDownLatch finished = new CountDownLatch(1); Bundle[] result = {null}; int[] code = {Activity.RESULT_CANCELED};
        String testPackage = InstrumentationRegistry.getInstrumentation().getContext().getPackageName();
        Intent intent = new Intent(CountingDocumentsProvider.AUTHORITY + ".GRANT")
                .setComponent(new ComponentName(testPackage, CountingDocumentsProvider.GrantReceiver.class.getName()))
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        context.sendOrderedBroadcast(intent, null, new BroadcastReceiver() {
            @Override public void onReceive(Context receiverContext, Intent received) {
                code[0] = getResultCode(); result[0] = getResultExtras(false); finished.countDown();
            }
        }, null, Activity.RESULT_CANCELED, null, null);
        assertTrue("Fixture grant did not finish", finished.await(30, TimeUnit.SECONDS));
        assertNotNull(result[0]); assertEquals(String.valueOf(result[0]), Activity.RESULT_OK, code[0]); return result[0];
    }
    private void restorePreferences(String name, Map<String, ?> saved) {
        SharedPreferences.Editor editor = context.getSharedPreferences(name, 0).edit().clear();
        for (Map.Entry<String, ?> entry : saved.entrySet()) {
            String key = entry.getKey(); Object value = entry.getValue();
            if (value instanceof String text) editor.putString(key, text);
            else if (value instanceof Boolean flag) editor.putBoolean(key, flag);
            else if (value instanceof Integer number) editor.putInt(key, number);
            else if (value instanceof Long number) editor.putLong(key, number);
            else if (value instanceof Float number) editor.putFloat(key, number);
            else if (value instanceof Set<?> values) { Set<String> strings = new HashSet<>(); for (Object item : values) strings.add((String) item); editor.putStringSet(key, strings); }
        }
        assertTrue(editor.commit());
    }
    private void click(String text) {
        UiObject2 button = device.wait(Until.findObject(By.text(text)), TIMEOUT); assertNotNull(text, button); button.click();
    }
    private static View described(View root, String description) {
        if (description.contentEquals(Objects.toString(root.getContentDescription(), ""))) return root;
        if (root instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            View found = described(group.getChildAt(i), description); if (found != null) return found;
        }
        return null;
    }
    private static EditorSession session(EditorActivity activity) { return (EditorSession) get(activity, "session"); }
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
        throw new AssertionError("No method " + name);
    }
}
