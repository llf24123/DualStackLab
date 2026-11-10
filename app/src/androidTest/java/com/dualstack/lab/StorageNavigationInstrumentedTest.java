package com.dualstack.lab;

import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class StorageNavigationInstrumentedTest {
    private Context context;
    private UiDevice device;
    private File home, work;
    private ActivityScenario<MainActivity> scenario;

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.setOrientationNatural();
        permission(true);
        home = Environment.getExternalStorageDirectory();
        work = Files.createTempDirectory(context.getExternalFilesDir(null).toPath(), "storage-nav-").toFile();
        context.getSharedPreferences("MainActivity", 0).edit().clear()
                .putString("left", home.getPath()).putString("right", work.getPath()).commit();
    }

    @After public void cleanup() throws Exception {
        permission(true);
        if (scenario != null) scenario.close();
        new FileStore(context).delete(FileStore.local(work));
        context.getSharedPreferences("MainActivity", 0).edit().clear().commit();
        device.unfreezeRotation();
    }

    @Test public void internalStorageParentRowReachesFilesystemRoot() throws Exception {
        Assume.assumeTrue("Requires readable filesystem root", RootAccess.available() || new File("/").list() != null);
        launch(home.getPath()); ready(true, home);
        up(); ready(true, new File("/storage/emulated"));
        find(By.desc("左窗口文件列表")).findObject(By.text(home.getName())).click(); ready(true, home);
        for (String path : new String[]{"/storage/emulated", "/storage", "/"}) { up(); ready(true, new File(path)); }
        up(); ready(true, new File("/"));
        ready(false, work); capture("home-up");
    }

    @Test public void systemBackClimbsAboveStorageThenNeedsTwoActionsAtRoot() throws Exception {
        Assume.assumeTrue("Requires readable filesystem root", RootAccess.available() || new File("/").list() != null);
        launch(home.getPath()); ready(true, home);
        for (String path : new String[]{"/storage/emulated", "/storage", "/"}) { device.pressBack(); ready(true, new File(path)); }
        device.pressBack();
        scenario.onActivity(activity -> assertFalse(activity.isFinishing()));
        ready(true, new File("/"));
        device.pressBack();
        assertTrue(device.wait(Until.gone(By.desc("左窗口文件列表")), 5000));
    }

    @Test public void aliasesAndDotSegmentsBackReachActualStorageParent() throws Exception {
        for (String alias : new String[]{"/sdcard", "/storage/self/primary", home + "/."}) {
            launch(alias); ready(true, home);
            device.pressBack();
            scenario.onActivity(activity -> assertFalse(activity.isFinishing()));
            ready(true, new File("/storage/emulated"));
        }
    }

    @Test public void legacySavedLocationsRecoverBothPanes() throws Exception {
        context.getSharedPreferences("MainActivity", 0).edit()
                .putString("left", "/storage/emulated").putString("right", "/storage/emulated/").commit();
        File parent = new File("/storage/emulated");
        launch(null); ready(true, parent); ready(false, parent);
        scenario.recreate(); ready(true, parent); ready(false, parent);
        scenario.close(); scenario = null;
        assertEquals(parent.getPath(), plainKey(context.getSharedPreferences("MainActivity", 0).getString("left", "")));
        assertEquals(parent.getPath(), plainKey(context.getSharedPreferences("MainActivity", 0).getString("right", "")));
    }

    @Test public void savedHomeAndBundleRecoverStorageContainer() throws Exception {
        context.getSharedPreferences("MainActivity", 0).edit()
                .putString("home-left", "/storage/emulated/")
                .putString("home-right", "/storage/self/primary").commit();
        launch(null); ready(true, new File("/storage/emulated")); ready(false, home);
        scenario.onActivity(activity -> {
            Object pane = pane(activity, true);
            set(pane, "location", FileStore.directory(new File("/storage/emulated"), false));
        });
        scenario.recreate(); ready(true, new File("/storage/emulated")); ready(false, home);
    }

    @Test public void rapidUpWithQueuedLoadsStopsAtFilesystemRoot() throws Exception {
        Assume.assumeTrue("Requires readable filesystem root", RootAccess.available() || new File("/").list() != null);
        launch(home.getPath()); ready(true, home); ready(false, work);
        CountDownLatch occupied = new CountDownLatch(3), release = new CountDownLatch(1);
        for (int i = 0; i < 3; i++) Tasks.IO.execute(() -> {
            occupied.countDown();
            try { release.await(15, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        try {
            assertTrue(occupied.await(5, TimeUnit.SECONDS));
            scenario.onActivity(activity -> {
                activity.onNewIntent(new Intent(activity, MainActivity.class).putExtra("navigatePath", work.getPath()));
                for (int i = 0; i < 20; i++) invoke(pane(activity, true), "up");
                assertEquals("/", node(pane(activity, true)).path());
            });
        } finally { release.countDown(); }
        ready(true, new File("/")); ready(false, work);
        scenario.recreate(); ready(true, new File("/")); ready(false, work);
        capture("rapid-up");
    }

    @Test public void automaticRootReturnContinuesAboveSharedHome() throws Exception {
        Assume.assumeTrue("Requires a granted su binary", RootAccess.available());
        launch(new File(home, "Android/data").getPath());
        ready(true, new File(home, "Android/data"));
        scenario.onActivity(activity -> assertTrue(node(pane(activity, true)).root));
        device.pressBack(); ready(true, new File(home, "Android"));
        device.pressBack(); ready(true, home);
        up(); ready(true, new File("/storage/emulated"));
        device.pressBack(); ready(true, new File("/storage"));
        capture("automatic-root-home");
    }

    @Test public void explicitRootContainerRemainsBrowsable() throws Exception {
        Assume.assumeTrue("Requires a granted su binary", RootAccess.available());
        context.getSharedPreferences("MainActivity", 0).edit()
                .putString("left", "root:/storage/emulated").commit();
        launch(null); ready(true, new File("/storage/emulated"));
        scenario.onActivity(activity -> assertTrue(node(pane(activity, true)).root));
        up(); ready(true, new File("/storage"));
        up(); ready(true, new File("/"));
        scenario.onActivity(activity -> {
            Object pane = pane(activity, true);
            assertTrue(node(pane).root);
            java.util.List<?> entries = (java.util.List<?>) get(pane, "all");
            assertTrue("ROOT listing must contain /data", entries.stream()
                    .anyMatch(entry -> entry instanceof FileStore.Node node && node.name.equals("data") && node.directory));
        });
        capture("explicit-root");
    }

    @Test public void archiveAtHomeReturnsToStorageHome() throws Exception {
        File zip = new File(home, "storage-nav-" + java.util.UUID.randomUUID() + ".zip");
        try {
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip.toPath()))) {
                out.putNextEntry(new ZipEntry("inside.txt")); out.write(65); out.closeEntry();
            }
            launch(home.getPath()); ready(true, home);
            scenario.onActivity(activity -> {
                Object pane = pane(activity, true);
                invoke(pane, "navigate", FileStore.Node.class, FileStore.archiveNode(FileStore.local(zip), "", true, 0, 0));
            });
            find(By.text("inside.txt")); device.pressBack(); ready(true, home);
            up(); ready(true, new File("/storage/emulated"));
        } finally { Files.deleteIfExists(zip.toPath()); }
    }

    @Test public void missingDirectoryStaysVisibleInsteadOfRedirecting() throws Exception {
        // ROOT is the default, so a permissions failure no longer hides the path; a missing one still must show.
        // A plain file cannot be listed, which ROOT reports as a failure without substituting another path.
        File missingDirectory = new File(work, "regular-file-" + java.util.UUID.randomUUID());
        java.nio.file.Files.write(missingDirectory.toPath(), new byte[]{65});
        assertTrue(missingDirectory.isFile());
        launch(missingDirectory.getPath());
        long deadline = SystemClock.uptimeMillis() + 10000;
        boolean[] denied = {false};
        while (SystemClock.uptimeMillis() < deadline && !denied[0]) {
            scenario.onActivity(activity -> {
                Object pane = pane(activity, true);
                TextView empty = (TextView) get(pane, "empty");
                denied[0] = empty.getVisibility() == View.VISIBLE && empty.getText().toString().contains("无法读取");
                assertEquals(missingDirectory.getPath(), node(pane).path());
            });
            SystemClock.sleep(40);
        }
        assertTrue("A missing directory must remain visible", denied[0]);
        scenario.onActivity(activity -> activity.onNewIntent(
                new Intent(activity, MainActivity.class).putExtra("navigatePath", home.getPath())));
        ready(true, home);
    }

    @Test public void typedMissingDirectoryFallsBackToItsParent() throws Exception {
        File parent = new File(work, "parent"); assertTrue(parent.mkdir());
        Files.write(new File(parent, "visible.txt").toPath(), new byte[]{65});
        launch(work.getPath()); ready(true, work);
        typedJump(new File(parent, "missing").getPath()); ready(true, parent);
        find(By.text("visible.txt"));
    }
    @Test public void typedMissingAncestorsClimbToExistingDirectory() throws Exception {
        launch(home.getPath()); ready(true, home);
        Files.write(new File(work, "anchor.txt").toPath(), new byte[]{65});
        typedJump(new File(work, "missing/deeper/path").getPath()); ready(true, work);
    }
    @Test public void typedRegularFileUsesParentAndExistingDirectoryOpensNormally() throws Exception {
        File file = new File(work, "file.txt"); Files.write(file.toPath(), new byte[]{65});
        launch(home.getPath()); ready(true, home);
        typedJump(file.getPath()); ready(true, work);
        typedJump(home.getPath()); ready(true, home);
    }
    private void typedJump(String path) {
        find(By.desc("路径跳转")).click();
        find(By.desc("跳转路径")).setText(path);
        find(By.text("确定")).click();
    }

    /** Saved keys drop the ROOT mark; older builds may still carry it. */
    private static String plainKey(String key) { return key.startsWith("root:") ? key.substring(5) : key; }
    private void permission(boolean granted) throws Exception {
        device.executeShellCommand("cmd appops set " + context.getPackageName()
                + " MANAGE_EXTERNAL_STORAGE " + (granted ? "allow" : "deny"));
    }
    private void launch(String path) {
        if (scenario != null) scenario.close();
        Intent intent = new Intent(context, MainActivity.class);
        if (path != null) intent.putExtra("navigatePath", path);
        scenario = ActivityScenario.launch(intent);
    }
    private void ready(boolean left, File directory) {
        long deadline = SystemClock.uptimeMillis() + 12000;
        boolean[] ready = {false};
        String[] actual = {""};
        while (SystemClock.uptimeMillis() < deadline && !ready[0]) {
            scenario.onActivity(activity -> {
                Object pane = pane(activity, left);
                FileStore.Node node = node(pane);
                TextView empty = (TextView) get(pane, "empty");
                String loaded = (String) get(pane, "recordedDirectory");
                actual[0] = node.path() + ": " + empty.getText() + "; loaded=" + loaded;
                ready[0] = node.path().equals(directory.getPath()) && ((View) get(pane, "list")).getVisibility() == View.VISIBLE
                        && empty.getVisibility() != View.VISIBLE && node.key().equals(loaded);
            });
            if (!ready[0]) SystemClock.sleep(40);
        }
        assertTrue("Expected " + directory + "; actual " + actual[0], ready[0]);
    }
    private UiObject2 find(BySelector selector) {
        UiObject2 found = device.wait(Until.findObject(selector), 10000);
        assertNotNull(selector.toString(), found); return found;
    }
    private void up() {
        find(By.desc("左窗口文件列表")).findObject(By.desc("上级目录")).click();
    }
    private void capture(String name) throws Exception {
        File evidence = new File(context.getExternalFilesDir(null), "storage-nav-review-0611");
        assertTrue(evidence.isDirectory() || evidence.mkdirs());
        device.waitForIdle();
        assertTrue(device.takeScreenshot(new File(evidence, name + ".png")));
        device.dumpWindowHierarchy(new File(evidence, name + ".xml"));
    }
    private static Object pane(MainActivity activity, boolean left) { return get(activity, left ? "left" : "right"); }
    private static FileStore.Node node(Object pane) { return (FileStore.Node) get(pane, "location"); }
    private static Object get(Object target, String name) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static void set(Object target, String name, Object value) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static void invoke(Object target, String name) { invoke(target, name, null, null); }
    private static void invoke(Object target, String name, Class<?> type, Object value) {
        try {
            Method method = type == null ? target.getClass().getDeclaredMethod(name) : target.getClass().getDeclaredMethod(name, type);
            method.setAccessible(true);
            if (type == null) method.invoke(target); else method.invoke(target, value);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
}
