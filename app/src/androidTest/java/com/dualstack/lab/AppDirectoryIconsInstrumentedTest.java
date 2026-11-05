package com.dualstack.lab;

import android.content.*;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.File;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AppDirectoryIconsInstrumentedTest {
    private Context context;
    private UiDevice device;
    private File work, data;
    private ActivityScenario<MainActivity> scenario;
    private Map<String, ?> before;
    private final String missing = "com.dualstack.fixture.notinstalled";

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        before = new HashMap<>(context.getSharedPreferences("MainActivity", 0).getAll());
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "app-icons-").toFile();
        data = new File(work, "Android/data"); assertTrue(data.mkdirs());
        assertTrue(new File(data, context.getPackageName()).mkdir());
        assertTrue(new File(data, missing).mkdir());
        assertTrue(new File(data, "ordinary-folder").mkdir());
        context.getSharedPreferences("MainActivity", 0).edit().clear()
                .putString("left", data.getPath()).putString("right", data.getPath()).commit();
        scenario = ActivityScenario.launch(MainActivity.class);
        find(By.text(context.getPackageName()));
    }
    @After public void cleanup() throws Exception {
        if (scenario != null) scenario.close();
        new FileStore(context).delete(FileStore.local(work));
        SharedPreferences.Editor editor = context.getSharedPreferences("MainActivity", 0).edit().clear();
        for (Map.Entry<String, ?> entry : before.entrySet()) {
            Object value = entry.getValue(); String key = entry.getKey();
            if (value instanceof String text) editor.putString(key, text);
            else if (value instanceof Boolean flag) editor.putBoolean(key, flag);
            else if (value instanceof Integer number) editor.putInt(key, number);
            else if (value instanceof Long number) editor.putLong(key, number);
            else if (value instanceof Float number) editor.putFloat(key, number);
        }
        editor.commit();
    }

    @Test public void packageContainersIncludeSharedStorageAliasesAndPrivateData() {
        for (String path : List.of("/storage/emulated/0/Android/data", "/sdcard/Android/data", "/storage/self/primary/Android/obb",
                "/data/data", "/data/user/0", "/data/user_de/10", "/mnt/expand/volume/user/0")) {
            FileStore.Node parent = FileStore.directory(new File(path), true);
            assertTrue(path, AppIcons.packageDirectory(parent,
                    FileStore.directory(new File(path, context.getPackageName()), true)));
        }
        FileStore.Node normal = FileStore.directory(new File("/storage/emulated/0/Download"), false);
        assertFalse(AppIcons.packageDirectory(normal, FileStore.directory(new File(normal.file, context.getPackageName()), false)));
        assertFalse(AppIcons.packageDirectory(FileStore.directory(data, false), FileStore.directory(new File(data, "ordinary-folder"), false)));
        assertFalse(AppIcons.packageDirectory(FileStore.directory(data, false), FileStore.local(new File(data, "file.txt"))));
    }

    @Test public void duplicateAsyncRequestsNotifyBothPanesWithIndependentDrawables() throws Exception {
        AppIcons icons = new AppIcons(context);
        AtomicReference<Drawable> first = new AtomicReference<>(), second = new AtomicReference<>();
        CountDownLatch received = new CountDownLatch(2);
        main(() -> {
            icons.load(context.getPackageName(), image -> { first.set(image); received.countDown(); });
            icons.load(context.getPackageName(), image -> { second.set(image); received.countDown(); });
        });
        assertTrue(received.await(10, TimeUnit.SECONDS));
        assertNotNull(first.get()); assertNotNull(second.get()); assertNotSame(first.get(), second.get());
        CountDownLatch miss = new CountDownLatch(1);
        AtomicReference<Drawable> absent = new AtomicReference<>(first.get());
        main(() -> icons.load(missing, image -> { absent.set(image); miss.countDown(); }));
        assertTrue(miss.await(10, TimeUnit.SECONDS)); assertNull(absent.get());
        boolean[] cached = {false};
        main(() -> { icons.load(missing, image -> cached[0] = image == null); assertTrue("Missing packages must be cached too", cached[0]); });
    }

    @Test public void appBadgesAppearOnBothPanesAndHideWhileSelected() {
        await(() -> badgeVisible(true, context.getPackageName()) && badgeVisible(false, context.getPackageName()));
        assertFalse(badgeVisible(true, missing)); assertFalse(badgeVisible(true, "ordinary-folder"));
        find(By.desc("左窗口文件列表")).findObject(By.desc("选择 " + context.getPackageName())).click();
        find(By.desc("取消")); await(() -> !badgeVisible(true, context.getPackageName()));
        find(By.desc("取消")).click(); await(() -> badgeVisible(true, context.getPackageName()));
        File evidence = new File(context.getExternalFilesDir(null), "app-icons-tablet-evidence");
        assertTrue(evidence.isDirectory() || evidence.mkdirs());
        assertTrue(device.takeScreenshot(new File(evidence, "package-directory-badges.png")));
    }

    @Test public void recycledRowsNeverKeepThePreviousPackageIcon() {
        AtomicReference<View> row = new AtomicReference<>();
        scenario.onActivity(activity -> {
            Object pane = get(activity, "left"); BaseAdapter adapter = (BaseAdapter) get(pane, "adapter");
            row.set(adapter.getView(index(pane, context.getPackageName()), null, (ViewGroup) get(pane, "list")));
            adapter.getView(index(pane, missing), row.get(), (ViewGroup) get(pane, "list"));
        });
        SystemClock.sleep(600);
        main(() -> assertEquals(View.GONE, ((ImageView) get(row.get().getTag(), "appBadge")).getVisibility()));
        scenario.onActivity(activity -> {
            Object pane = get(activity, "left"); BaseAdapter adapter = (BaseAdapter) get(pane, "adapter");
            adapter.getView(index(pane, context.getPackageName()), row.get(), (ViewGroup) get(pane, "list"));
        });
        await(() -> {
            boolean[] visible = {false}; main(() -> visible[0] = ((ImageView) get(row.get().getTag(), "appBadge")).getVisibility() == View.VISIBLE); return visible[0];
        });
    }

    private boolean badgeVisible(boolean left, String name) {
        boolean[] visible = {false};
        scenario.onActivity(activity -> {
            Object pane = get(activity, left ? "left" : "right"); ViewGroup list = (ViewGroup) get(pane, "list");
            for (int i = 0; i < list.getChildCount(); i++) {
                Object holder = list.getChildAt(i).getTag();
                if (holder == null) continue;
                FileStore.Node node = (FileStore.Node) get(holder, "node");
                if (node != null && node.name.equals(name)) {
                    ImageView badge = (ImageView) get(holder, "appBadge");
                    visible[0] = badge.getVisibility() == View.VISIBLE && badge.getDrawable() != null && badge.getImageTintList() == null;
                }
            }
        });
        return visible[0];
    }
    private static int index(Object pane, String name) {
        List<?> entries = (List<?>) get(pane, "entries");
        for (int i = 0; i < entries.size(); i++) if (((FileStore.Node) entries.get(i)).name.equals(name)) return i + 1;
        throw new AssertionError(name);
    }
    private UiObject2 find(BySelector selector) {
        UiObject2 result = device.wait(Until.findObject(selector), 15000); assertNotNull(selector.toString(), result); return result;
    }
    private static void await(BooleanSupplier check) {
        long end = SystemClock.uptimeMillis() + 15000;
        while (SystemClock.uptimeMillis() < end) { if (check.getAsBoolean()) return; SystemClock.sleep(60); }
        fail("Application badge did not settle");
    }
    private static void main(Runnable task) { InstrumentationRegistry.getInstrumentation().runOnMainSync(task); }
    private static Object get(Object target, String name) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
}
