package com.dualstack.lab;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.test.uiautomator.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** Real widgets and the real provider/recovery protocol; never runs fixture documents as programs. */
@RunWith(AndroidJUnit4.class)
public class RecycleBinInstrumentedTest {
    private static final Uri CONTROL = Uri.parse("content://" + CountingDocumentsProvider.AUTHORITY);
    private Context context;
    private UiDevice device;
    private File work;
    private Uri tree;
    private ActivityScenario<RecycleBinActivity> recycle;
    private ActivityScenario<MainActivity> main;
    private final List<Fixture> fixtures = new ArrayList<>();
    private final Set<String> orphanIds = new HashSet<>();
    private final Map<String, Map<String, ?>> preferences = new LinkedHashMap<>();
    private final Set<String> baselineIds = new HashSet<>();

    private static final class Fixture {
        final String name;
        final Uri source;
        final byte[] original;
        final SafRecovery.Record record;
        String id;
        Fixture(String name, Uri source, byte[] original, SafRecovery.Record record) {
            this.name = name; this.source = source; this.original = original; this.record = record;
        }
    }

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.setOrientationNatural();
        // Preserve absent keys too, not just their effective default values.
        for (String name : new String[]{"MainActivity", "SafRecovery", "appearance"}) {
            preferences.put(name, new HashMap<>(context.getSharedPreferences(name, 0).getAll()));
        }
        for (SafRecovery.Entry entry : SafRecovery.entries(context)) baselineIds.add(entry.id());
        work = Files.createTempDirectory(context.getFilesDir().toPath(), "recycle-ui-").toFile();
        SafRecovery.setBackupDirectory(context, new File(work, "backups"));
        SafRecovery.setEnabled(context, true);
        context.getSharedPreferences("MainActivity", 0).edit().clear()
                .putString("left", work.getPath()).putString("right", work.getPath())
                .putString("home-left", work.getPath()).putString("home-right", work.getPath()).commit();
        tree = Uri.parse(grantFixture().getString("uri"));
    }

    @After public void cleanup() throws Exception {
        try {
            if (recycle != null) { recycle.close(); recycle = null; }
            // The menu test opens this Activity outside ActivityScenario's own target.
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                Set<Activity> activities = new HashSet<>();
                for (Stage stage : Stage.values()) activities.addAll(ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(stage));
                for (Activity activity : activities) if (activity instanceof RecycleBinActivity) activity.finish();
            });
            if (main != null) { main.close(); main = null; }
            // A closed Activity's submitted mutation is allowed to finish before restoring preferences.
            RecycleBinActivity.WORKER.submit(() -> {}).get(20, TimeUnit.SECONDS);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            if (context != null) {
                // Only UUID records returned by OUR failed writes may ever be deleted.
                for (Fixture fixture : fixtures) {
                    for (SafRecovery.Entry entry : SafRecovery.entries(context)) {
                        if (entry.backup().equals(fixture.record.backup)) {
                            UUID.fromString(entry.id());
                            assertFalse("A fixture must never claim a pre-existing user record", baselineIds.contains(entry.id()));
                            SafRecovery.delete(context, entry.id());
                            break;
                        }
                    }
                }
                for (String id : orphanIds) {
                    UUID.fromString(id); assertFalse(baselineIds.contains(id)); SafRecovery.delete(context, id);
                }
                if (tree != null) call("reset", null);
                if (work != null) remove(work);
            }
        } finally {
            if (context != null) for (Map.Entry<String, Map<String, ?>> entry : preferences.entrySet()) {
                restorePreferences(entry.getKey(), entry.getValue());
            }
            if (device != null) device.unfreezeRotation();
        }
    }

    @Test public void mainDrawerAndSettingsBothReachRecycleBin() throws Exception {
        SafRecovery.setEnabled(context, false); // Existing real records must not interrupt this navigation test.
        main = ActivityScenario.launch(MainActivity.class);
        clickDescription("位置与工具");
        UiObject2 drawer = find(By.desc("位置与工具列表"));
        for (int i = 0; !device.hasObject(By.text("回收站")) && i < 6; i++)
            drawer.scroll(Direction.DOWN, .75f);
        clickText("回收站");
        find(By.desc("保存备份开关"));
        assertFalse(find(By.desc("保存备份开关")).isChecked());
        capture("drawer-entry");
        device.pressBack();
        clickDescription("更多操作");
        UiObject2 menu = find(By.desc("更多操作列表"));
        for (int i = 0; !device.hasObject(By.text("设置")) && i < 8; i++)
            menu.scroll(Direction.DOWN, .75f);
        clickText("设置"); clickText("备份与回收站");
        find(By.desc("备份存放目录")); capture("settings-entry");
        device.pressBack();
    }

    @Test public void disabledPersistsAcrossRecreationKeepsRecordsAndSuppressesStartupReminder() throws Exception {
        Fixture fixture = seed(); launch();
        assertTrue(find(By.desc("保存备份开关")).isChecked());
        CountDownLatch occupied = new CountDownLatch(1), release = new CountDownLatch(1);
        RecycleBinActivity.WORKER.execute(() -> {
            occupied.countDown();
            try { release.await(20, TimeUnit.SECONDS); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        });
        try {
            assertTrue(occupied.await(5, TimeUnit.SECONDS));
            clickDescription("保存备份开关");
            assertFalse("Disabling must not wait behind a blocked record scan", SafRecovery.enabled(context));
            recycle.recreate(); // Record scans are queued, but the setting is already effective.
            assertFalse(find(By.desc("保存备份开关")).isChecked());
        } finally { release.countDown(); }
        ready();
        assertFalse(find(By.desc("保存备份开关")).isChecked());
        assertNotNull(scrollTo(By.desc("备份操作 " + fixture.id)));
        assertTrue(fixture.record.backup.isFile());
        capture("disabled-existing-record");
        recycle.close(); recycle = null;
        main = ActivityScenario.launch(MainActivity.class);
        find(By.desc("左窗口文件列表"));
        // Drain the same Tasks.IO queue used by startup, then allow the UI to deliver its callbacks.
        CountDownLatch drained = new CountDownLatch(1); Tasks.IO.execute(drained::countDown);
        assertTrue(drained.await(15, TimeUnit.SECONDS)); device.waitForIdle();
        assertFalse(device.hasObject(By.textContains("份 SAF 恢复备份")));
        assertFalse(device.hasObject(By.text("SAF 持久恢复备份")));
        assertFalse(device.hasObject(By.text("恢复备份扫描失败")));
        assertTrue(fixture.record.backup.isFile()); capture("disabled-main-startup");
    }

    @Test public void directoryValidationSuggestionAndDefaultResetUseRealDialog() throws Exception {
        Fixture old = seed();
        File suggested = new File(work, "window-directory"); assertTrue(suggested.mkdir());
        recycle = ActivityScenario.launch(new Intent(context, RecycleBinActivity.class)
                .putExtra("suggestedDirectory", suggested.getAbsolutePath())); ready();
        clickDescription("备份存放目录"); clickText("填入当前窗口目录");
        assertEquals(suggested.getAbsolutePath(), find(By.desc("备份目录输入")).getText());
        File custom = new File(work, "custom-created-by-dialog");
        find(By.desc("备份目录输入")).setText(custom.getAbsolutePath()); clickText("保存");
        awaitDirectory(custom);
        assertTrue(custom.isDirectory());
        assertTrue(find(By.desc("备份存放目录")).getText().contains(custom.getCanonicalPath()));
        recycle.recreate(); ready(); awaitDirectory(custom);
        // A real local file is a deterministic invalid directory, independent of ROOT availability.
        File invalid = new File(work, "not-a-directory"); Files.write(invalid.toPath(), new byte[]{9});
        clickDescription("备份存放目录");
        find(By.desc("备份目录输入")).setText(invalid.getAbsolutePath()); clickText("保存");
        find(By.text("更改备份目录"));
        await("Validation error must be visible without closing the editor", () -> device.hasObject(By.desc("备份目录错误")));
        assertEquals(custom.getCanonicalFile(), SafRecovery.backupDirectory(context).getCanonicalFile());
        assertTrue(old.record.backup.isFile()); capture("invalid-directory");
        find(By.desc("备份目录输入")).setText("relative/not-local"); clickText("保存");
        assertEquals(custom.getCanonicalFile(), SafRecovery.backupDirectory(context).getCanonicalFile());
        clickText("恢复默认"); awaitDirectory(SafRecovery.directory(context));
        assertTrue(find(By.desc("备份存放目录")).getText().startsWith("默认"));
        assertTrue("Directory changes must not discard old records", old.record.backup.isFile());
        scrollTo(By.desc("备份操作 " + old.id)); capture("default-directory-old-record");
    }

    @Test public void unavailableConfiguredPathStillAllowsResettingTheDirectory() throws Exception {
        File configured = SafRecovery.backupDirectory(context);
        File moved = new File(work, "former-backup-directory");
        Files.move(configured.toPath(), moved.toPath());
        android.system.Os.symlink(configured.getName(), configured.getPath());
        recycle = ActivityScenario.launch(RecycleBinActivity.class);
        find(By.text("操作失败")); clickText("确定");
        assertTrue(find(By.desc("保存备份开关")).isEnabled());
        assertTrue(find(By.desc("备份存放目录")).isEnabled());
        clickDescription("备份存放目录"); clickText("恢复默认");
        awaitDirectory(SafRecovery.directory(context));
        capture("unavailable-path-reset");
    }
    @Test public void localDirectoryPickerSelectsReadableDirectoryWithoutSafTree() throws Exception {
        File child = new File(work, "picker-child"); assertTrue(child.mkdir());
        launch(); clickDescription("备份存放目录");
        find(By.desc("备份目录输入")).setText(work.getCanonicalPath());
        clickText("浏览本地目录");
        find(By.desc("本地目录列表")); clickText(child.getName());
        clickText("使用此目录");
        assertEquals(child.getCanonicalPath(), find(By.desc("备份目录输入")).getText());
        clickText("保存"); awaitDirectory(child); capture("local-directory-picker");
    }

    @Test public void deleteRowRequiresConfirmationAndNeverTouchesSourceOrOtherRecord() throws Exception {
        Fixture first = seed(), second = seed();
        byte[] source = read(first.source), otherSource = read(second.source);
        launch(); rowMenu(first); clickText("删除备份");
        find(By.text("删除这份备份？"));
        assertArrayEquals(source, read(first.source)); assertTrue(first.record.backup.isFile());
        capture("delete-confirmation"); clickText("取消");
        assertTrue(device.wait(Until.gone(By.text("删除这份备份？")), 5000));
        device.waitForIdle();
        assertTrue(first.record.backup.isFile());
        rowMenu(first); clickText("删除备份"); clickText("删除备份");
        await("Only the selected backup must disappear", () -> !first.record.backup.exists());
        ready(); assertFalse(hasEntry(first.id)); assertTrue(hasEntry(second.id));
        assertArrayEquals(source, read(first.source)); assertArrayEquals(otherSource, read(second.source));
        assertTrue(second.record.backup.isFile()); assertBaselinePresent(); capture("row-deleted");
    }

    @Test public void clearDeletesOnlyConfirmedSnapshotAndNeverSourceOrLaterBackup() throws Exception {
        // Clearing a production list with user entries would be destructive. Do not hide/move those entries.
        Assume.assumeTrue("Clear-all UI test needs a catalog without pre-existing user records", baselineIds.isEmpty());
        Fixture first = seed(); byte[] source = read(first.source);
        launch(); clickDescription("清空备份"); find(By.text("清空备份？"));
        capture("clear-confirmation"); clickText("取消"); assertTrue(first.record.backup.isFile());
        clickDescription("清空备份"); find(By.text("清空备份？"));
        Fixture later = seed(); byte[] laterSource = read(later.source);
        clickText("清空备份");
        await("Confirmed snapshot backup was not deleted", () -> !first.record.backup.exists());
        ready(); assertFalse(hasEntry(first.id)); assertTrue(hasEntry(later.id));
        assertTrue(later.record.backup.isFile());
        assertArrayEquals(source, read(first.source)); assertArrayEquals(laterSource, read(later.source));
        capture("snapshot-cleared-later-kept");
    }

    @Test public void restoreCanBeCancelledAndVerifiedSuccessRefreshesList() throws Exception {
        Fixture fixture = seed(); byte[] external = new byte[]{71, 72, 73, 74, 75};
        try (OutputStream output = context.getContentResolver().openOutputStream(fixture.source, "wt")) {
            assertNotNull(output); output.write(external);
        }
        launch(); clickDescription("保存备份开关");
        await("Manual restore must remain available with backups disabled", () -> !SafRecovery.enabled(context)); ready();
        rowMenu(fixture); clickText("恢复原件");
        find(By.text("确认覆盖来源当前内容？")); capture("restore-confirmation");
        assertArrayEquals(external, read(fixture.source)); clickText("取消");
        assertArrayEquals(external, read(fixture.source)); assertTrue(fixture.record.backup.isFile());
        rowMenu(fixture); clickText("恢复原件"); clickText("确认覆盖并恢复");
        await("Verified restore must clean its backup", () -> !fixture.record.backup.exists());
        assertTrue("Success callback must refresh without tapping refresh",
                device.wait(Until.gone(By.desc("备份操作 " + fixture.id)), 15000));
        ready(); assertArrayEquals(fixture.original, read(fixture.source)); assertFalse(hasEntry(fixture.id));
        assertBaselinePresent(); capture("restored-refreshed");
    }

    @Test public void preparedAndOrphanEntriesRemainManageableWithoutRestoreAction() throws Exception {
        Fixture prepared = seed();
        Properties metadata = new Properties();
        try (InputStream input = new FileInputStream(prepared.record.metadata)) { metadata.load(input); }
        metadata.setProperty("state", "prepared");
        try (OutputStream output = new FileOutputStream(prepared.record.metadata)) { metadata.store(output, "UI fixture only"); }
        String orphanId = UUID.randomUUID().toString();
        File orphan = new File(SafRecovery.directory(context), orphanId); assertTrue(orphan.mkdir()); orphanIds.add(orphanId);
        launch();
        scrollTo(By.desc("备份操作 " + prepared.id)).click();
        assertFalse("Prepared-only backups must not overwrite a newer source", device.hasObject(By.text("恢复原件")));
        clickText("取消");
        scrollTo(By.desc("备份操作 " + orphanId));
        UiObject2 row = find(By.desc("备份记录 " + orphanId));
        assertNotNull(row.findObject(By.textContains("大小未知")));
        assertNotNull(row.findObject(By.textContains("不可恢复"))); capture("unavailable-orphan");
        scrollTo(By.desc("备份操作 " + orphanId)).click();
        assertFalse(device.hasObject(By.text("恢复原件")));
        clickText("删除备份"); clickText("删除备份");
        await("Own orphan directory must remain deletable", () -> !orphan.exists()); ready();
        assertTrue(hasEntry(prepared.id)); assertBaselinePresent();
    }

    @Test public void darkLandscapeAndRecreationKeepSettingsAndRecords() throws Exception {
        Fixture fixture = seed();
        context.getSharedPreferences("appearance", 0).edit().putString("mode", "light").commit();
        launch(); scrollTo(By.desc("备份操作 " + fixture.id)); capture("light-portrait");
        context.getSharedPreferences("appearance", 0).edit().putString("mode", "dark").commit();
        device.setOrientationLeft(); recycle.recreate(); ready();
        assertTrue(SafRecovery.enabled(context)); assertTrue(hasEntry(fixture.id));
        scrollTo(By.desc("备份操作 " + fixture.id)); capture("dark-landscape");
    }

    private Fixture seed() throws Exception {
        String name = "recycle-fixture-" + UUID.randomUUID() + ".bin";
        byte[] original = new byte[8192 + 37]; new Random(name.hashCode()).nextBytes(original);
        Bundle seed = new Bundle(); seed.putString("name", name); seed.putByteArray("bytes", original); call("seed", seed);
        Uri source = DocumentsContract.buildDocumentUriUsingTree(tree, "root/" + name);
        File payload = new File(work, name + ".payload"); Files.write(payload.toPath(), new byte[]{1, 2, 3, 4});
        Bundle failure = new Bundle(); failure.putString("truncateWriteName", name); call("configure", failure);
        SafRecovery.Record record;
        try {
            SafRecovery.write(context, new FileStore(context).document(source), payload);
            throw new AssertionError("truncateWriteName must produce a durable RecoveryException record");
        } catch (SafRecovery.RecoveryException error) { record = error.record; }
        finally { call("configure", new Bundle()); }
        Fixture fixture = new Fixture(name, source, original, record); fixtures.add(fixture);
        for (SafRecovery.Entry entry : SafRecovery.entries(context)) {
            if (entry.backup().equals(record.backup)) {
                UUID.fromString(entry.id()); assertFalse(baselineIds.contains(entry.id()));
                fixture.id = entry.id(); break;
            }
        }
        assertNotNull("Failed-write fixture must be indexed", fixture.id);
        assertArrayEquals(original, Files.readAllBytes(record.backup.toPath()));
        return fixture;
    }
    private void launch() { recycle = ActivityScenario.launch(RecycleBinActivity.class); ready(); }
    private void ready() {
        await("Recovery list did not finish loading", () -> {
            UiObject2 status = device.findObject(By.desc("备份扫描状态"));
            return status != null && status.getText() != null && status.getText().startsWith("删除备份 ");
        });
    }
    private void awaitDirectory(File expected) throws Exception {
        String path = expected.getCanonicalPath();
        await("Backup directory setting not persisted", () -> {
            try { return path.equals(SafRecovery.backupDirectory(context).getCanonicalPath()); }
            catch (IOException temporarilyUnavailable) { return false; }
        });
        assertTrue(device.wait(Until.gone(By.text("更改备份目录")), 15000)); ready();
    }
    private void rowMenu(Fixture fixture) { scrollTo(By.desc("备份操作 " + fixture.id)).click(); }
    private boolean hasEntry(String id) throws Exception {
        for (SafRecovery.Entry entry : SafRecovery.entries(context)) if (entry.id().equals(id)) return true;
        return false;
    }
    private void assertBaselinePresent() throws Exception {
        Set<String> ids = new HashSet<>(); for (SafRecovery.Entry entry : SafRecovery.entries(context)) ids.add(entry.id());
        assertTrue("Existing user recovery records must remain indexed", ids.containsAll(baselineIds));
    }
    private UiObject2 scrollTo(BySelector selector) {
        UiObject2 object = device.findObject(selector);
        if (object == null) { top(); object = device.findObject(selector); }
        for (int i = 0; object == null && i < 60; i++) {
            UiObject2 list = find(By.desc("备份列表"));
            if (!list.scroll(Direction.DOWN, .75f)) break;
            object = device.findObject(selector);
        }
        if (object == null) object = device.findObject(selector);
        assertNotNull("Missing recovery row " + selector, object); return object;
    }
    private UiObject2 find(BySelector selector) {
        UiObject2 view = device.wait(Until.findObject(selector), 15000); assertNotNull(String.valueOf(selector), view); return view;
    }
    private void top() {
        UiObject2 list = device.findObject(By.desc("备份列表"));
        if (list != null) for (int i = 0; i < 60 && list.scroll(Direction.UP, .8f); i++) { /* bounded return to settings */ }
    }
    private void clickDescription(String description) {
        if (!device.hasObject(By.desc(description))) top();
        find(By.desc(description)).click();
    }
    private void clickText(String text) { find(By.text(text)).click(); }
    private void capture(String name) throws IOException {
        File directory = context.getExternalFilesDir(null); assertNotNull(directory);
        File destination = new File(directory, "review-recycle-" + name + ".png");
        assertTrue("Screenshot not saved: " + destination, device.takeScreenshot(destination));
    }
    private static void await(String message, BooleanSupplier condition) {
        long deadline = SystemClock.uptimeMillis() + 15000;
        do { if (condition.getAsBoolean()) return; SystemClock.sleep(40); } while (SystemClock.uptimeMillis() < deadline);
        fail(message);
    }
    private byte[] read(Uri source) throws IOException {
        try (InputStream input = context.getContentResolver().openInputStream(source)) {
            assertNotNull(input); ByteArrayOutputStream output = new ByteArrayOutputStream(); FileStore.pump(input, output);
            return output.toByteArray();
        }
    }
    private Bundle call(String method, Bundle extras) {
        Bundle result = context.getContentResolver().call(CONTROL, method, null, extras); assertNotNull(result); return result;
    }
    private Bundle grantFixture() throws Exception {
        CountDownLatch finished = new CountDownLatch(1); AtomicReference<Bundle> result = new AtomicReference<>();
        AtomicInteger code = new AtomicInteger(Activity.RESULT_CANCELED);
        String testPackage = InstrumentationRegistry.getInstrumentation().getContext().getPackageName();
        Intent intent = new Intent(CountingDocumentsProvider.AUTHORITY + ".GRANT")
                .setComponent(new ComponentName(testPackage, CountingDocumentsProvider.GrantReceiver.class.getName()))
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        context.sendOrderedBroadcast(intent, null, new BroadcastReceiver() {
            @Override public void onReceive(Context receiverContext, Intent received) {
                code.set(getResultCode()); result.set(getResultExtras(false)); finished.countDown();
            }
        }, null, Activity.RESULT_CANCELED, null, null);
        assertTrue("Fixture grant did not finish", finished.await(30, TimeUnit.SECONDS));
        assertNotNull(result.get()); assertEquals(String.valueOf(result.get()), Activity.RESULT_OK, code.get()); return result.get();
    }
    private void restorePreferences(String name, Map<String, ?> values) {
        SharedPreferences.Editor editor = context.getSharedPreferences(name, 0).edit().clear();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            String key = entry.getKey(); Object value = entry.getValue();
            if (value instanceof String text) editor.putString(key, text);
            else if (value instanceof Boolean flag) editor.putBoolean(key, flag);
            else if (value instanceof Integer number) editor.putInt(key, number);
            else if (value instanceof Long number) editor.putLong(key, number);
            else if (value instanceof Float number) editor.putFloat(key, number);
            else if (value instanceof Set<?> set) {
                Set<String> copy = new HashSet<>(); for (Object item : set) copy.add((String) item); editor.putStringSet(key, copy);
            }
        }
        assertTrue("Preferences not restored: " + name, editor.commit());
    }
    private static void remove(File root) throws IOException {
        if (!root.exists()) return;
        Files.walkFileTree(root.toPath(), new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                if (error != null) throw error; Files.delete(directory); return FileVisitResult.CONTINUE;
            }
        });
    }
}
