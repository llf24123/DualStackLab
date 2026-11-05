package com.dualstack.lab;

import android.content.*;
import android.os.SystemClock;
import android.view.View;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PaneSyncInstrumentedTest {
    Context context;
    UiDevice device;
    File work, left, right;
    ActivityScenario<MainActivity> scenario;

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.setOrientationNatural();
        device.executeShellCommand("cmd appops set " + context.getPackageName() + " MANAGE_EXTERNAL_STORAGE allow");
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "pane-sync-").toFile();
        left = new File(work, "left"); right = new File(work, "right");
        assertTrue(left.mkdir()); assertTrue(right.mkdir());
        context.getSharedPreferences("MainActivity", 0).edit().clear()
                .putString("left", left.getPath()).putString("right", right.getPath()).commit();
    }
    @After public void cleanup() throws Exception {
        if (scenario != null) scenario.close();
        new FileStore(context).delete(FileStore.local(work));
        context.getSharedPreferences("MainActivity", 0).edit().clear().commit();
        device.unfreezeRotation();
    }
    @Test public void bottomLastActionSyncsLeftToRightAndKeepsSourceAndHistory() throws Exception {
        Files.write(new File(left, "source.ini").toPath(), new byte[]{65});
        launch(); sync(); ready(false, left);
        scenario.onActivity(activity -> {
            assertSame(get(activity, "left"), get(activity, "active"));
            assertEquals(left.getPath(), node(pane(activity, true)).path());
            LinearLayout bar = (LinearLayout) get(activity, "actions");
            assertEquals(5, bar.getChildCount());
            assertEquals("同步另一窗口", bar.getChildAt(4).getContentDescription());
            assertEquals(1, ((Deque<?>) get(pane(activity, false), "backHistory")).size());
        });
        scenario.onActivity(activity -> invoke(pane(activity, false), "back")); ready(false, right);
        assertFalse(device.hasObject(By.desc("上级"))); capture("sync-left");
    }
    @Test public void rightToLeftSyncKeepsRightActiveAndSamePathDoesNotAddHistory() throws Exception {
        Files.write(new File(right, "right.ini").toPath(), new byte[]{66});
        launch();
        UiObject2 row = find(By.desc("右窗口文件列表")).findObject(By.text("right.ini"));
        assertNotNull(row);
        scenario.onActivity(activity -> invoke(activity, "activate", pane(activity, false)));
        sync(); ready(true, right); sync();
        scenario.onActivity(activity -> {
            assertSame(get(activity, "right"), get(activity, "active"));
            assertEquals(right.getPath(), node(pane(activity, false)).path());
            assertEquals(1, ((Deque<?>) get(pane(activity, true), "backHistory")).size());
            assertEquals(180f, ((LinearLayout) get(activity, "actions")).getChildAt(4).getRotation(), .01f);
        });
        capture("sync-right");
    }
    @Test public void syncPreservesRootAndCompleteAndroidDataListing() throws Exception {
        launch();
        File data = new File("/storage/emulated/0/Android/data");
        navigate(true, FileStore.directory(data, false)); ready(true, data);
        sync(); ready(false, data);
        Set<String> expected = new TreeSet<>();
        for (FileStore.Node entry : new FileStore(context).list(FileStore.directory(data, true))) expected.add(entry.name);
        scenario.onActivity(activity -> {
            assertTrue(node(pane(activity, true)).root); assertTrue(node(pane(activity, false)).root);
            Set<String> actual = new TreeSet<>();
            for (Object entry : (List<?>) get(pane(activity, false), "all")) actual.add(((FileStore.Node) entry).name);
            assertEquals(expected, actual);
        });
        capture("sync-android-data");
    }
    @Test public void syncPreservesArchiveSubdirectoryAndSystemBack() throws Exception {
        File zip = new File(left, "sync.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip.toPath()))) {
            out.putNextEntry(new ZipEntry("folder/inside.ini")); out.write(65); out.closeEntry();
        }
        launch();
        FileStore.Node directory = FileStore.archiveNode(FileStore.local(zip), "folder/", true, 0, 0);
        navigate(true, directory); readyKey(true, directory.key());
        sync(); readyKey(false, directory.key());
        assertNotNull(find(By.desc("右窗口文件列表")).findObject(By.text("inside.ini")));
        device.pressBack();
        readyKey(true, FileStore.archiveNode(FileStore.local(zip), "", true, 0, 0).key());
        readyKey(false, directory.key()); capture("sync-archive");
    }
    @Test public void cachedRestrictedNavigationAndSyncDoNotWaitForBrowseWorkers() throws Exception {
        launch();
        File data = new File(context.getExternalFilesDir(null), "sync-" + UUID.randomUUID());
        assertTrue(data.mkdir());
        CountDownLatch occupied = new CountDownLatch(2), release = new CountDownLatch(1);
        try {
            navigate(true, FileStore.directory(data, false)); ready(true, data);
            navigate(true, FileStore.directory(left, false)); ready(true, left);
            for (int i = 0; i < 2; i++) Tasks.BROWSE.execute(() -> {
                occupied.countDown();
                try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(occupied.await(5, TimeUnit.SECONDS));
            scenario.onActivity(activity -> {
                invoke(pane(activity, true), "navigate", FileStore.directory(data, false));
                assertEquals(data.getPath(), plainKey((String) get(pane(activity, true), "recordedDirectory")));
                invoke(activity, "synchronizeOtherPane");
                assertEquals(data.getPath(), plainKey((String) get(pane(activity, false), "recordedDirectory")));
                assertTrue(((View) get(pane(activity, false), "list")).isEnabled());
            });
        } finally {
            release.countDown(); new FileStore(context).delete(FileStore.directory(data, true));
        }
    }
    @Test public void queuedNavigationRetainsFrameDisablesOldRowsAndLatestResultWins() throws Exception {
        Files.write(new File(left, "old.ini").toPath(), new byte[]{65}); launch();
        File a = new File(work, "a"), b = new File(work, "b");
        assertTrue(a.mkdir()); assertTrue(b.mkdir());
        Files.write(new File(b, "latest.ini").toPath(), new byte[]{66});
        CountDownLatch occupied = new CountDownLatch(2), release = new CountDownLatch(1);
        for (int i = 0; i < 2; i++) Tasks.BROWSE.execute(() -> {
            occupied.countDown();
            try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        try {
            assertTrue(occupied.await(5, TimeUnit.SECONDS));
            scenario.onActivity(activity -> {
                Object pane = pane(activity, true);
                for (int i = 0; i < 20; i++) invoke(pane, "navigate", FileStore.directory(i % 2 == 0 ? a : b, false));
                View list = (View) get(pane, "list");
                assertEquals(View.VISIBLE, list.getVisibility()); assertFalse(list.isEnabled());
                assertEquals(left.getPath(), plainKey((String) get(pane, "recordedDirectory")));
            });
        } finally { release.countDown(); }
        ready(true, b); ready(false, right);
        assertNotNull(find(By.desc("左窗口文件列表")).findObject(By.text("latest.ini")));
        SystemClock.sleep(250);
        scenario.onActivity(activity -> {
            View list = (View) get(pane(activity, true), "list");
            assertTrue(list.isEnabled()); assertEquals(1f, list.getAlpha(), .01f);
            assertEquals(0f, list.getTranslationY(), .01f);
        });
    }
    @Test public void refreshInvalidatesSharedSnapshotAndToolbarViewsAreReused() throws Exception {
        launch(); sync(); ready(false, left);
        File added = new File(left, "added.ini"); Files.write(added.toPath(), new byte[]{65});
        scenario.onActivity(activity -> {
            LinearLayout actions = (LinearLayout) get(activity, "actions");
            View button = actions.getChildAt(4);
            invoke(activity, "actionBar"); assertSame(button, actions.getChildAt(4));
            invoke(pane(activity, true), "reload");
        });
        long deadline = SystemClock.uptimeMillis() + 10000;
        while (!device.hasObject(By.text("added.ini")) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(30);
        assertTrue(device.hasObject(By.text("added.ini")));
        navigate(false, FileStore.directory(right, false)); ready(false, right);
        sync(); ready(false, left);
        assertNotNull(find(By.desc("右窗口文件列表")).findObject(By.text("added.ini")));
    }
    @Test public void rootReadChannelSerializesRequestsRecoversAfterFailureAndHandlesQuotedPaths() throws Exception {
        File folder = new File(work, "quote'and|pipe"); assertTrue(folder.mkdir());
        assertTrue(new File(folder, "a|b.ini").createNewFile());
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            List<Future<List<FileStore.Node>>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) results.add(workers.submit(() ->
                    new FileStore(context).list(FileStore.directory(folder, true))));
            for (Future<List<FileStore.Node>> future : results) {
                List<FileStore.Node> nodes = future.get(10, TimeUnit.SECONDS);
                assertEquals(1, nodes.size()); assertEquals("a|b.ini", nodes.get(0).name);
            }
            try { RootDirectoryReader.list(new File(folder, "missing").getPath()); fail("Missing must fail"); }
            catch (IOException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains("not a directory")
                    || expected.getMessage().contains("已关闭")); }
            RootDirectoryReader.close();
            assertEquals(1, new FileStore(context).list(FileStore.directory(folder, true)).size());
        } finally { workers.shutdownNow(); }
    }
    private void launch() {
        scenario = ActivityScenario.launch(MainActivity.class); ready(true, left); ready(false, right);
    }
    private void sync() { find(By.desc("同步另一窗口")).click(); }
    private void navigate(boolean left, FileStore.Node node) {
        scenario.onActivity(activity -> invoke(pane(activity, left), "navigate", node));
    }
    private void ready(boolean left, File path) { readyKey(left, path.getPath()); }
    private void readyKey(boolean left, String key) {
        long end = SystemClock.uptimeMillis() + 12000; boolean[] ready = {false};
        while (!ready[0] && SystemClock.uptimeMillis() < end) {
            scenario.onActivity(activity -> {
                Object pane = pane(activity, left); FileStore.Node node = node(pane);
                ready[0] = (node.key().equals(key) || node.path().equals(key))
                        && node.key().equals(get(pane, "recordedDirectory"))
                        && ((View) get(pane, "list")).isEnabled()
                        && ((View) get(pane, "list")).getVisibility() == View.VISIBLE;
            });
            if (!ready[0]) SystemClock.sleep(15);
        }
        assertTrue("Directory not ready: " + key, ready[0]);
    }
    private UiObject2 find(BySelector selector) {
        UiObject2 found = device.wait(Until.findObject(selector), 10000); assertNotNull(selector.toString(), found); return found;
    }
    private void capture(String name) throws Exception {
        File evidence = new File(context.getExternalFilesDir(null), "pane-sync-review-0611");
        assertTrue(evidence.isDirectory() || evidence.mkdirs());
        device.waitForIdle(); assertTrue(device.takeScreenshot(new File(evidence, name + ".png")));
        device.dumpWindowHierarchy(new File(evidence, name + ".xml"));
    }
    private static Object pane(MainActivity activity, boolean left) { return get(activity, left ? "left" : "right"); }
    private static String plainKey(String key) { return key.startsWith("root:") ? key.substring(5) : key; }
    private static FileStore.Node node(Object pane) { return (FileStore.Node) get(pane, "location"); }
    private static Object get(Object target, String name) {
        try { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void invoke(Object target, String name, Object... args) {
        try {
            Method method = Arrays.stream(target.getClass().getDeclaredMethods())
                    .filter(m -> m.getName().equals(name) && m.getParameterCount() == args.length).findFirst().orElseThrow();
            method.setAccessible(true); method.invoke(target, args);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
}
