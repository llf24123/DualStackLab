package com.dualstack.lab;

import android.content.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.ListView;
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
import java.util.zip.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class TransferUiInstrumentedTest {
    private Context context;
    private File work, target;
    private ActivityScenario<MainActivity> screen;
    private final byte[] payload = new byte[32768];
    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "transfer-ui-").toFile();
        target = new File(work, "target"); assertTrue(target.mkdir()); new Random(3).nextBytes(payload);
    }
    @After public void cleanup() throws Exception {
        if (screen != null) screen.close();
        new FileStore(context).delete(FileStore.local(work));
        context.getSharedPreferences("MainActivity", 0).edit().clear().commit();
    }
    @Test public void archiveCopyKeepsSourceListAndShowsTargetWithoutDialog() throws Exception {
        File archive = new File(work, "source.zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("payload.bin")); zip.write(payload); zip.closeEntry();
        }
        FileStore.Node source = FileStore.archiveNode(FileStore.local(archive), "", true, 0, 0);
        open(source.key(), target.getPath());
        int[] generation = {0}; screen.onActivity(activity -> generation[0] = (Integer) get(get(activity, "left"), "generation"));
        long millis = transfer(FileStore.archiveNode(FileStore.local(archive), "payload.bin", false, 0, 0), false, "payload.bin");
        screen.onActivity(activity -> assertEquals("Unchanged archive list must be retained", generation[0], get(get(activity, "left"), "generation")));
        assertArrayEquals(payload, Files.readAllBytes(new File(target, "payload.bin").toPath()));
        android.util.Log.i("TransferUiTiming", "archive32KB-to-visible-row=" + millis + "ms");
    }
    @Test public void sameDirectoryCopyRefreshesBothPanesAndSmallMoveShowsTarget() throws Exception {
        File source = new File(work, "payload.bin"); Files.write(source.toPath(), payload);
        open(work.getPath(), work.getPath());
        long copy = transfer(FileStore.local(source), false, "payload (1).bin");
        screen.onActivity(activity -> assertTrue(hasRow(get(activity, "left"), "payload (1).bin")));
        assertArrayEquals(payload, Files.readAllBytes(new File(work, "payload (1).bin").toPath()));
        screen.close(); screen = null; open(work.getPath(), target.getPath());
        long move = transfer(FileStore.local(source), true, "payload.bin");
        assertFalse(source.exists()); assertArrayEquals(payload, Files.readAllBytes(new File(target, "payload.bin").toPath()));
        android.util.Log.i("TransferUiTiming", "copy32KB-to-visible-row=" + copy + "ms move32KB-to-visible-row=" + move + "ms");
    }
    private void open(String left, String right) {
        context.getSharedPreferences("MainActivity", 0).edit().clear().putString("left", left).putString("right", right).commit();
        screen = ActivityScenario.launch(MainActivity.class);
        waitReady();
    }
    private void waitReady() {
        boolean[] ready = {false}; long until = SystemClock.uptimeMillis() + 10000;
        while (!ready[0] && SystemClock.uptimeMillis() < until) {
            screen.onActivity(activity -> ready[0] = ((ListView) get(get(activity, "left"), "list")).isEnabled()
                    && ((ListView) get(get(activity, "right"), "list")).isEnabled());
            if (!ready[0]) SystemClock.sleep(10);
        }
        assertTrue(ready[0]);
    }
    private long transfer(FileStore.Node node, boolean move, String output) {
        long[] start = {0};
        screen.onActivity(activity -> {
            try {
                Object source = get(activity, "left"), destination = get(activity, "right");
                Method method = MainActivity.class.getDeclaredMethod("transfer", List.class, source.getClass(), source.getClass(), boolean.class);
                method.setAccessible(true); start[0] = SystemClock.uptimeMillis();
                method.invoke(activity, List.of(node), source, destination, move);
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
        boolean[] done = {false}; long until = SystemClock.uptimeMillis() + 10000;
        while (!done[0] && SystemClock.uptimeMillis() < until) {
            screen.onActivity(activity -> done[0] = !(Boolean) get(activity, "busy") && hasRow(get(activity, "right"), output));
            if (!done[0]) SystemClock.sleep(10);
        }
        assertTrue("Target row must be displayed", done[0]);
        long elapsed = SystemClock.uptimeMillis() - start[0];
        assertFalse(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).hasObject(By.text("正在处理")));
        return elapsed;
    }
    private boolean hasRow(Object pane, String name) {
        ViewGroup list = (ViewGroup) get(pane, "list");
        for (int i = 0; i < list.getChildCount(); i++) {
            Object holder = list.getChildAt(i).getTag(); if (holder == null) continue;
            FileStore.Node node = (FileStore.Node) get(holder, "node");
            if (node != null && node.name.equals(name)) return true;
        }
        return false;
    }
    private static Object get(Object target, String name) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
}
