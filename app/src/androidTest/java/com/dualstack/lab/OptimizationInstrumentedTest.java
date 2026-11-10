package com.dualstack.lab;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.View;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class OptimizationInstrumentedTest {
    private Context context;
    private File work;
    private FileStore store;

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        store = new FileStore(context);
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).executeShellCommand(
                "cmd appops set " + context.getPackageName() + " MANAGE_EXTERNAL_STORAGE allow");
        work = Files.createTempDirectory(context.getExternalFilesDir(null).toPath(), "optimization-").toFile();
    }
    @After public void cleanup() throws Exception {
        store.delete(FileStore.local(work));
    }
    @Test public void measureTransfersAndArchiveReads() throws Exception {
        File source = directory("source"), copied = directory("copied"), moved = directory("moved");
        byte[] data = new byte[64 * 1024]; new Random(42).nextBytes(data);
        for (int i = 0; i < 24; i++) Files.write(new File(source, "item-" + i).toPath(), data);
        JSONObject result = new JSONObject();
        long start = SystemClock.elapsedRealtime();
        store.copyAll(store.list(FileStore.local(source)), FileStore.local(copied), false);
        result.put("copyMs", SystemClock.elapsedRealtime() - start);
        assertEquals(24, copied.list().length);
        start = SystemClock.elapsedRealtime();
        store.copyAll(store.list(FileStore.local(copied)), FileStore.local(moved), true);
        result.put("moveMs", SystemClock.elapsedRealtime() - start);
        assertEquals(0, copied.list().length); assertEquals(24, moved.list().length);
        start = SystemClock.elapsedRealtime();
        store.deleteAll(store.list(FileStore.local(moved)));
        result.put("deleteMs", SystemClock.elapsedRealtime() - start);
        assertEquals(0, moved.list().length);
        File archive = new File(work, "large-directory.zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) {
            for (int i = 0; i < 4000; i++) {
                zip.putNextEntry(new ZipEntry("folder/item-" + i)); zip.write(new byte[]{1, 2, 3}); zip.closeEntry();
            }
        }
        ArchiveOps.forget(archive); start = SystemClock.elapsedRealtime();
        assertEquals(1, store.list(FileStore.archiveNode(FileStore.local(archive), "", true, 0, 0)).size());
        result.put("archiveListMs", SystemClock.elapsedRealtime() - start);
        start = SystemClock.elapsedRealtime();
        for (int i = 0; i < 24; i++) try (InputStream input = store.read(
                FileStore.archiveNode(FileStore.local(archive), "folder/item-" + i, false, 3, 0))) {
            assertArrayEquals(new byte[]{1, 2, 3}, FileStore.readBytes(input, 10));
        }
        result.put("archiveRead24Ms", SystemClock.elapsedRealtime() - start);
        android.util.Log.i("OptimizationTiming", result.toString());
        Files.write(new File(context.getExternalFilesDir(null), "optimization-timing.json").toPath(),
                result.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    @Test public void cachedRootDenialDoesNotProbeAgainForAppExternalFiles() throws Exception {
        long checked = SystemClock.elapsedRealtime();
        setStatic(RootAccess.class, "checkedAt", checked); setStatic(RootAccess.class, "available", false);
        setStatic(RootAccess.class, "grantedUntil", 0L); Thread.sleep(5);
        try {
            File source = new File(work, "cached-denial"); Files.write(source.toPath(), new byte[]{42});
            for (int i = 0; i < 24; i++) {
                assertFalse(RootAccess.automatic(FileStore.local(source)).root);
                assertFalse(RootAccess.directory(work, false).root);
            }
            assertEquals(checked, ((Number) field(RootAccess.class, null, "checkedAt")).longValue());
        } finally { RootAccess.reset(); }
    }
    @Test public void metadataChannelHandlesQuotedPathsAndVerifiedReadableRootFiles() throws Exception {
        RootDirectoryReader.close();
        Class<?> sessionClass = Class.forName("com.dualstack.lab.RootDirectoryReader$Session");
        java.lang.reflect.Constructor<?> constructor = sessionClass.getDeclaredConstructor(ProcessBuilder.class);
        constructor.setAccessible(true);
        Object channel = constructor.newInstance(new ProcessBuilder("/system/bin/sh"));
        setStatic(RootDirectoryReader.class, "session", channel);
        try {
            File parent = new File(work, "parent'quoted|name"); assertTrue(parent.mkdir());
            assertEquals(parent.getPath(), RootDirectoryReader.nearestDirectory(new File(parent, "missing/deeper").getPath()));
            File source = new File(work, "quoted'pipe|and\nnewline");
            Files.write(source.toPath(), new byte[]{10, 20, 30});
            FileStore.Node rootNode = store.fromKey("root:" + source.getPath());
            assertEquals(FileStore.local(source).identity, rootNode.identity);
            for (int i = 0; i < 12; i++) try (InputStream input = store.read(rootNode)) {
                assertArrayEquals(new byte[]{10, 20, 30}, FileStore.readBytes(input, 10));
            }
            assertEquals(store.fingerprint(FileStore.local(source)), store.fingerprint(rootNode));
            assertEquals(FilePermissions.read(FileStore.local(source)).mode(), FilePermissions.read(rootNode).mode());
            File link = new File(context.getCacheDir(), "optimization-link-" + UUID.randomUUID());
            try {
                Files.createSymbolicLink(link.toPath(), new File(work, "missing").toPath());
                assertFalse(store.fromKey("root:" + link.getPath()).identityFollowsLinks);
            } finally { Files.deleteIfExists(link.toPath()); }
            assertSame(channel, field(RootDirectoryReader.class, null, "session"));
        } finally { RootDirectoryReader.close(); }
    }
    @Test public void readableRootCopyUsesCreatedInodeWithoutStartingWriterPerFile() throws Exception {
        RootDirectoryReader.close(); RootMutations.close();
        Class<?> sessionClass = Class.forName("com.dualstack.lab.RootDirectoryReader$Session");
        java.lang.reflect.Constructor<?> constructor = sessionClass.getDeclaredConstructor(ProcessBuilder.class);
        constructor.setAccessible(true);
        setStatic(RootDirectoryReader.class, "session", constructor.newInstance(new ProcessBuilder("/system/bin/sh")));
        RootMutations.Session mutations = new RootMutations.Session(context, false);
        setStatic(RootMutations.class, "cached", mutations); setStatic(RootMutations.class, "cachedRoot", true);
        long now = SystemClock.elapsedRealtime();
        setStatic(RootAccess.class, "available", true); setStatic(RootAccess.class, "checkedAt", now);
        setStatic(RootAccess.class, "grantedUntil", now + 30000);
        try {
            File source = new File(work, "source.bin"), destination = directory("root-copy");
            Files.write(source.toPath(), new byte[0]);
            FileStore.Node staleSource = FileStore.local(source, true);
            byte[] data = new byte[256 * 1024]; new Random(7).nextBytes(data); Files.write(source.toPath(), data);
            FileStore.Node parent = FileStore.local(destination, true);
            Map<String, Set<String>> names = new HashMap<>(); names.put(parent.key(), new HashSet<>());
            FileStore.Node output = store.copy(staleSource, parent, false, names);
            assertArrayEquals(data, Files.readAllBytes(output.file.toPath()));
            assertSame(mutations, field(RootMutations.class, null, "cached"));
            assertEquals(output.identity, FileStore.local(output.file).identity);
            java.lang.reflect.Method direct = FileStore.class.getDeclaredMethod("copyCreatedFileLocally", FileStore.Node.class, FileStore.Node.class);
            direct.setAccessible(true);
            assertEquals(false, direct.invoke(store, staleSource, FileStore.local(output.file, true)));
            assertArrayEquals(data, Files.readAllBytes(output.file.toPath()));
            try (OutputStream large = new FileOutputStream(source)) {
                for (int i = 0; i < 256; i++) large.write(data);
            }
            Tasks.Progress progress = new Tasks.Progress();
            java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
            Thread copy = new Thread(() -> {
                try { progress.run(() -> store.copy(FileStore.local(source, true), parent, false, names)); }
                catch (Throwable error) { failure.set(error); }
            });
            copy.start(); long deadline = SystemClock.uptimeMillis() + 15000;
            while (copy.isAlive() && progress.transferred.get() == 0 && SystemClock.uptimeMillis() < deadline) Thread.sleep(1);
            progress.cancelled.set(true); copy.join(10000);
            assertFalse("Cancelled copy must finish", copy.isAlive());
            assertTrue(String.valueOf(failure.get()), failure.get() instanceof Tasks.CancelledException);
            assertArrayEquals(new String[]{output.name}, destination.list());
            assertArrayEquals(data, Files.readAllBytes(output.file.toPath()));
        } finally { RootAccess.reset(); RootDirectoryReader.close(); RootMutations.close(); }
    }
    @Test public void archiveMembersStreamIntoReadableRootTargetsWithRealEntrySizes() throws Exception {
        File archive = new File(work, "root-members.zip"), destination = directory("root-members-out");
        byte[] payload = new byte[256 * 1024]; new Random(19).nextBytes(payload);
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("payload.bin")); zip.write(payload); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("zero.bin")); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("empty/")); zip.closeEntry();
        }
        RootDirectoryReader.close(); RootMutations.close();
        Class<?> sessionClass = Class.forName("com.dualstack.lab.RootDirectoryReader$Session");
        java.lang.reflect.Constructor<?> constructor = sessionClass.getDeclaredConstructor(ProcessBuilder.class); constructor.setAccessible(true);
        setStatic(RootDirectoryReader.class, "session", constructor.newInstance(new ProcessBuilder("/system/bin/sh")));
        RootMutations.Session mutations = new RootMutations.Session(context, false);
        setStatic(RootMutations.class, "cached", mutations); setStatic(RootMutations.class, "cachedRoot", true);
        long now = SystemClock.elapsedRealtime();
        setStatic(RootAccess.class, "available", true); setStatic(RootAccess.class, "checkedAt", now); setStatic(RootAccess.class, "grantedUntil", now + 30000);
        try {
            FileStore.Node source = FileStore.local(archive);
            List<FileStore.Node> members = List.of(FileStore.archiveNode(source, "payload.bin", false, 0, 0),
                    FileStore.archiveNode(source, "zero.bin", false, 0, 0), FileStore.archiveNode(source, "empty/", true, 0, 0));
            FileStore.Node parent = FileStore.local(destination, true);
            Map<String, Set<String>> names = new HashMap<>(); names.put(parent.key(), new HashSet<>());
            for (FileStore.Node member : members) store.copy(member, parent, false, names);
            assertArrayEquals(payload, Files.readAllBytes(new File(destination, "payload.bin").toPath()));
            assertEquals(0, new File(destination, "zero.bin").length()); assertTrue(new File(destination, "empty").isDirectory());
            assertSame(mutations, field(RootMutations.class, null, "cached"));
        } finally { RootAccess.reset(); RootDirectoryReader.close(); RootMutations.close(); }
    }

    @Test public void deviceInventoryOmitsSensorsAndKeepsTwelveCommonProperties() {
        List<DeviceInfo.Field> basic = DeviceInfo.basic(context);
        assertTrue(basic.stream().noneMatch(item -> item.section().equals("传感器") || item.section().equals("系统特性")));
        List<DeviceInfo.Field> extra = DeviceInfo.privileged();
        List<DeviceInfo.Field> properties = extra.stream().filter(item -> item.section().equals("常用系统属性")).toList();
        assertEquals(12, properties.size());
        assertTrue(properties.stream().anyMatch(item -> item.name().equals("ro.serialno")));
        assertTrue(properties.stream().anyMatch(item -> item.name().equals("ro.build.fingerprint")));
        assertTrue(extra.stream().noneMatch(item -> item.section().equals("完整系统属性")));
    }

    @Test public void fileRowsMatchOriginalGeometry() throws Exception {
        File source = new File(work, "layout.txt"); Files.write(source.toPath(), new byte[]{42});
        SharedPreferences preferences = context.getSharedPreferences("MainActivity", 0);
        String left = preferences.getString("left", null), right = preferences.getString("right", null);
        Boolean previousDensity = preferences.contains("comfortable") ? preferences.getBoolean("comfortable", false) : null;
        preferences.edit().putString("left", work.getPath()).putString("right", work.getPath()).commit();
        try {
            for (boolean density : new boolean[]{false, true}) {
                preferences.edit().putBoolean("comfortable", density).commit();
                try (ActivityScenario<MainActivity> page = ActivityScenario.launch(MainActivity.class)) {
                    UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
                    find(device, By.text("layout.txt"));
                    page.onActivity(activity -> {
                        try {
                            Object pane = field(activity.getClass(), activity, "left");
                            ListView list = (ListView) field(pane.getClass(), pane, "list");
                            View row = list.getChildAt(1); Object holder = row.getTag();
                            View icon = (View) field(holder.getClass(), holder, "icon");
                            View iconFrame = (View) field(holder.getClass(), holder, "iconFrame");
                            TextView name = (TextView) field(holder.getClass(), holder, "name");
                            TextView meta = (TextView) field(holder.getClass(), holder, "meta");
                            boolean comfortable = (Boolean) field(activity.getClass(), activity, "comfortable");
                            int[] iconPosition = new int[2], namePosition = new int[2];
                            icon.getLocationOnScreen(iconPosition); name.getLocationOnScreen(namePosition);
                            int gap = namePosition[0] - iconPosition[0] - icon.getWidth();
                            assertEquals(Ui.dp(activity, comfortable ? 40 : 32) - Ui.dp(activity, comfortable ? 36 : 28)
                                    + Ui.dp(activity, comfortable ? 8 : 4), gap);
                            assertEquals(Ui.dp(activity, comfortable ? 40 : 32), iconFrame.getWidth());
                            assertEquals(Ui.dp(activity, 2), row.getPaddingTop());
                            assertEquals(Ui.dp(activity, 2), row.getPaddingBottom());
                            assertEquals(Ui.dp(activity, comfortable ? 24 : 21), name.getHeight());
                            assertEquals(Ui.dp(activity, comfortable ? 17 : 15), meta.getHeight());
                            assertEquals(Ui.dp(activity, comfortable ? 52 : 41), row.getHeight());
                            assertFalse(name.getIncludeFontPadding());
                        } catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
                    });
                    device.waitForIdle(); device.takeScreenshot(new File(context.getExternalFilesDir(null), "original-file-rows-" + (density ? "comfortable" : "compact") + ".png"));
                }
            }
        } finally {
            SharedPreferences.Editor edit = preferences.edit();
            if (left == null) edit.remove("left"); else edit.putString("left", left);
            if (right == null) edit.remove("right"); else edit.putString("right", right);
            if (previousDensity == null) edit.remove("comfortable"); else edit.putBoolean("comfortable", previousDensity);
            edit.commit();
        }
    }
    private static Object field(Class<?> type, Object target, String name) throws ReflectiveOperationException {
        java.lang.reflect.Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static void setStatic(Class<?> type, String name, Object value) throws ReflectiveOperationException {
        java.lang.reflect.Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(null, value);
    }
    private static UiObject2 find(UiDevice device, BySelector selector) {
        UiObject2 found = device.wait(Until.findObject(selector), 10000); assertNotNull(selector.toString(), found); return found;
    }
    private static void waitText(UiDevice device, String description, String value) {
        assertTrue(device.wait(Until.hasObject(By.desc(description).text(value)), 10000));
    }
    private File directory(String name) throws IOException {
        File directory = new File(work, name); if (!directory.mkdir()) throw new IOException(name); return directory;
    }
}
