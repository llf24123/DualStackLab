package com.dualstack.lab;

import android.content.Context;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class CopyExperienceInstrumentedTest {
    private Context context;
    private UiDevice device;
    private File work;
    private ActivityScenario<MainActivity> screen;
    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        work = Files.createTempDirectory(context.getFilesDir().toPath(), "copy-experience-").toFile();
        context.getSharedPreferences("MainActivity", 0).edit().clear()
                .putString("left", work.getPath()).putString("right", work.getPath()).commit();
        screen = ActivityScenario.launch(MainActivity.class);
    }
    @After public void cleanup() throws Exception {
        screen.close(); new FileStore(context).delete(FileStore.local(work));
        context.getSharedPreferences("MainActivity", 0).edit().clear().commit();
    }
    @Test public void copyingCanRunWithoutAProgressDialog() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), ended = new CountDownLatch(1);
        CountDownLatch occupied = new CountDownLatch(3), freeIo = new CountDownLatch(1);
        try {
            for (int i = 0; i < 3; i++) Tasks.IO.execute(() -> {
                occupied.countDown();
                try { assertTrue(freeIo.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
            });
            assertTrue(occupied.await(5, TimeUnit.SECONDS));
            screen.onActivity(activity -> {
                try {
                    Method method = MainActivity.class.getDeclaredMethod("mutate", Tasks.Job.class, Tasks.Done.class, boolean.class);
                    method.setAccessible(true);
                    Tasks.Job<Boolean> job = () -> {
                        started.countDown();
                        try { assertTrue(release.await(10, TimeUnit.SECONDS)); return true; }
                        finally { ended.countDown(); }
                    };
                    method.invoke(activity, job, (Tasks.Done<Boolean>) value -> {}, false);
                } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS)); SystemClock.sleep(800);
            assertFalse(device.hasObject(By.text("正在处理")));
            assertFalse(device.hasObject(By.desc("操作进度")));
            find(By.desc("左窗口文件列表"));
        } finally { release.countDown(); freeIo.countDown(); assertTrue(ended.await(5, TimeUnit.SECONDS)); }
    }
    @Test public void apkInformationUsesCompactRowsAndKeepsPathActions() throws Exception {
        screen.onActivity(activity -> {
            try {
                Method method = MainActivity.class.getDeclaredMethod("apkDialog", FileStore.Node.class); method.setAccessible(true);
                method.invoke(activity, FileStore.local(new File(context.getApplicationInfo().sourceDir)));
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
        UiObject2 packageName = find(By.desc("包名 " + context.getPackageName()));
        assertEquals(Ui.dp(context, 30), packageName.getVisibleBounds().height());
        UiObject2 path = find(By.descStartsWith("数据目录 1 "));
        assertTrue(path.getVisibleBounds().height() >= Ui.dp(context, 48));
        find(By.descStartsWith("UID "));
        assertTrue(device.takeScreenshot(new File(context.getExternalFilesDir(null), "apk-info-0619.png")));
        path.click();
        assertTrue(device.wait(Until.gone(By.desc("包名 " + context.getPackageName())), 5000));
    }
    private UiObject2 find(BySelector selector) {
        UiObject2 item = device.wait(Until.findObject(selector), 10000);
        assertNotNull(selector.toString(), item); return item;
    }
}
