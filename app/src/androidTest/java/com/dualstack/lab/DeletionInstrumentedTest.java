package com.dualstack.lab;

import android.content.Context;
import android.os.SystemClock;
import android.system.Os;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DeletionInstrumentedTest {
    private Context context;
    private File work;
    private FileStore store;
    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        store = new FileStore(context);
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "delete-review-").toFile();
    }
    @After public void cleanup() throws Exception { store.delete(FileStore.local(work)); }

    @Test public void measureRootBulkDeletion() throws Exception {
        List<FileStore.Node> nodes = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            File file = new File(work, "small-" + i + ".ini");
            Files.write(file.toPath(), new byte[]{65}); nodes.add(FileStore.local(file, true));
        }
        File survivor = new File(work, "keep.ini"); Files.write(survivor.toPath(), new byte[]{66});
        long start = SystemClock.elapsedRealtimeNanos();
        boolean bulk;
        try {
            java.lang.reflect.Method method = FileStore.class.getDeclaredMethod("deleteAll", Collection.class);
            method.setAccessible(true); method.invoke(store, nodes); bulk = true;
        } catch (NoSuchMethodException oldBuild) {
            for (FileStore.Node node : nodes) store.delete(node); bulk = false;
        }
        double elapsed = (SystemClock.elapsedRealtimeNanos() - start) / 1000000.0;
        assertArrayEquals(new String[]{"keep.ini"}, work.list());
        JSONObject result = new JSONObject().put("files", nodes.size()).put("bytesPerFile", 1)
                .put("elapsedMs", elapsed).put("batch", bulk);
        File evidence = new File(context.getExternalFilesDir(null), "optimization-0612");
        assertTrue(evidence.isDirectory() || evidence.mkdirs());
        Files.write(new File(evidence, "deletion.json").toPath(), result.toString(2).getBytes(StandardCharsets.UTF_8));
        android.util.Log.i("BulkDeletion", result.toString());
    }

    @Test public void localDirectoryTreeDoesNotFollowLinksOutsideSelection() throws Exception {
        File tree = new File(work, "tree"), outside = new File(work, "outside");
        assertTrue(tree.mkdir()); assertTrue(outside.mkdir());
        File keep = new File(outside, "keep.ini"); Files.write(keep.toPath(), new byte[]{65});
        for (int i = 0; i < 12; i++) {
            File folder = new File(tree, "folder" + i); assertTrue(folder.mkdir());
            for (int j = 0; j < 50; j++) Files.write(new File(folder, "tiny" + j).toPath(), new byte[]{66});
        }
        Os.symlink(outside.getPath(), new File(tree, "outside-link").getPath());
        Os.symlink("missing", new File(tree, "broken-link").getPath());
        store.deleteAll(List.of(FileStore.local(tree)));
        assertFalse(tree.exists()); assertArrayEquals(new byte[]{65}, Files.readAllBytes(keep.toPath()));
    }

    @Test public void archiveBatchRemovesFilesAndSubtreeWithoutTouchingSimilarPrefix() throws Exception {
        File zip = new File(work, "remove.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip.toPath()))) {
            for (String name : List.of("a.ini", "folder/one", "folder/two", "folder-extra/keep", "keep.ini")) {
                out.putNextEntry(new ZipEntry(name)); out.write(name.getBytes(StandardCharsets.UTF_8)); out.closeEntry();
            }
        }
        FileStore.Node source = FileStore.local(zip);
        store.deleteAll(List.of(FileStore.archiveNode(source, "a.ini", false, 0, 0),
                FileStore.archiveNode(source, "folder/", true, 0, 0)));
        try (ZipFile opened = new ZipFile(zip)) {
            assertEquals(2, opened.size()); assertNotNull(opened.getEntry("folder-extra/keep")); assertNotNull(opened.getEntry("keep.ini"));
        }
    }

    @Test public void protectedTargetRejectsWholeBatchBeforeDeletingOrdinaryFile() throws Exception {
        File keep = new File(work, "keep.ini"); Files.write(keep.toPath(), new byte[]{65});
        for (String path : List.of("/", "/storage", "/storage/emulated", "/storage/emulated/0", "/data/user/0")) {
            try { store.deleteAll(List.of(FileStore.local(keep), FileStore.directory(new File(path), true))); fail(path); }
            catch (IOException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains("不能删除")); }
            assertTrue(keep.exists());
        }
    }

    @Test public void rootBatchChunksLongUnicodeNamesAndPreservesUnselectedFile() throws Exception {
        List<FileStore.Node> nodes = new ArrayList<>();
        for (int i = 0; i < 280; i++) {
            File file = new File(work, "quoted' " + "中文".repeat(20) + i + ".ini");
            Files.write(file.toPath(), new byte[]{65}); nodes.add(FileStore.local(file, true));
        }
        File keep = new File(work, "keep.ini"); Files.write(keep.toPath(), new byte[]{66});
        store.deleteAll(nodes); assertArrayEquals(new String[]{"keep.ini"}, work.list());
    }

    @Test public void rootBatchAccountsForNestedQuotesBeforeKernelArgumentLimit() throws Exception {
        List<FileStore.Node> nodes = new ArrayList<>();
        for (int i = 0; i < 38; i++) {
            File file = new File(work, "'".repeat(200) + i + ".ini");
            Files.write(file.toPath(), new byte[]{65}); nodes.add(FileStore.local(file, true));
        }
        File keep = new File(work, "keep.ini"); Files.write(keep.toPath(), new byte[]{66});
        store.deleteAll(nodes); assertArrayEquals(new String[]{"keep.ini"}, work.list());
        File deepRoot = new File(work, "deep"), deep = deepRoot; assertTrue(deep.mkdir());
        for (int i = 0; i < 18; i++) {
            deep = new File(deep, "'".repeat(200) + i); assertTrue(deep.mkdir());
        }
        File longPath = new File(deep, "one.ini"); Files.write(longPath.toPath(), new byte[]{67});
        store.deleteAll(List.of(FileStore.local(longPath, true)));
        assertFalse(longPath.exists());
        store.delete(FileStore.local(deepRoot));
        assertArrayEquals(new String[]{"keep.ini"}, work.list());
    }

    @Test public void mainSelectAllDeleteRefreshesBothPanes() throws Exception {
        File external = Files.createTempDirectory(context.getExternalFilesDir(null).toPath(), "bulk-delete-ui-").toFile();
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.executeShellCommand("cmd appops set " + context.getPackageName() + " MANAGE_EXTERNAL_STORAGE allow");
        for (int i = 0; i < 35; i++) Files.write(new File(external, "tiny" + i + ".ini").toPath(), new byte[]{65});
        context.getSharedPreferences("MainActivity", 0).edit().clear()
                .putString("left", external.getPath()).putString("right", external.getPath()).commit();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            assertNotNull(device.wait(Until.findObject(By.text("tiny0.ini")), 10000));
            device.findObject(By.desc("更多操作")).click(); device.wait(Until.findObject(By.text("全选")), 5000).click();
            UiObject2 delete = device.wait(Until.findObject(By.desc("删除")), 5000);
            assertNotNull("Selection actions did not appear", delete); delete.click();
            UiObject2 confirm = device.wait(Until.findObject(By.text("删除")), 5000);
            assertNotNull("Deletion confirmation did not appear", confirm); confirm.click();
            long deadline = SystemClock.uptimeMillis() + 15000;
            while (external.list().length != 0 && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(30);
            assertEquals(0, external.list().length);
            assertTrue(device.wait(Until.gone(By.text("tiny0.ini")), 10000));
            assertNotNull(device.wait(Until.findObject(By.desc("同步另一窗口")), 5000));
            assertFalse(device.hasObject(By.text("操作失败")));
            File evidence = new File(context.getExternalFilesDir(null), "optimization-0612"); evidence.mkdirs();
            device.takeScreenshot(new File(evidence, "delete-both-panes.png"));
        } finally {
            store.delete(FileStore.local(external)); context.getSharedPreferences("MainActivity", 0).edit().clear().commit();
        }
    }
}
