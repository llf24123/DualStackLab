package com.dualstack.lab;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DirectFlowInstrumentedTest {
    private Context context;
    private UiDevice device;
    private File work, source, destination;
    private ActivityScenario<MainActivity> screen;

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.executeShellCommand("cmd appops set " + context.getPackageName() + " MANAGE_EXTERNAL_STORAGE allow");
        work = Files.createTempDirectory(context.getFilesDir().toPath(), "direct-flow-").toFile();
        source = new File(work, "source"); destination = new File(work, "destination");
        assertTrue(source.mkdir()); assertTrue(destination.mkdir());
        Files.write(new File(source, "item.txt").toPath(), "payload".getBytes(StandardCharsets.UTF_8));
        context.getSharedPreferences("MainActivity", 0).edit().clear()
                .putString("left", source.getPath()).putString("right", destination.getPath()).commit();
    }
    @After public void cleanup() throws Exception {
        if (screen != null) screen.close();
        new FileStore(context).delete(FileStore.local(work));
        context.getSharedPreferences("MainActivity", 0).edit().clear().commit();
    }

    @Test public void copyAndMoveExecuteFromTheSelectionMenu() throws Exception {
        screen = ActivityScenario.launch(MainActivity.class);
        transfer("复制");
        await(() -> new File(destination, "item.txt").exists());
        assertTrue(new File(source, "item.txt").exists());
        assertEquals("payload", new String(Files.readAllBytes(new File(destination, "item.txt").toPath()), StandardCharsets.UTF_8));
        Files.delete(new File(destination, "item.txt").toPath());
        transfer("移动");
        await(() -> new File(destination, "item.txt").exists() && !new File(source, "item.txt").exists());
        assertFalse(device.hasObject(By.text("已加入任务中心，可继续浏览")));
    }

    @Test public void deletionIsDirectAndConfirmsPermanentRemoval() throws Exception {
        File backups = new File(context.getFilesDir(), "deleted-index");
        String[] before = backups.list();
        screen = ActivityScenario.launch(MainActivity.class);
        select(); find(By.desc("文件操作 删除")).click();
        find(By.text("将永久删除所选文件及目录。"));
        find(By.text("删除")).click();
        await(() -> !new File(source, "item.txt").exists());
        assertArrayEquals(before, backups.list());
    }

    @Test public void drawerAndManifestHaveNoTaskCenterOrRecycleBin() throws Exception {
        screen = ActivityScenario.launch(MainActivity.class);
        find(By.desc("位置与工具")).click();
        find(By.text("设备信息"));
        assertFalse(device.hasObject(By.text("任务中心")));
        assertFalse(device.hasObject(By.text("回收站")));
        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(),
                PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES);
        assertFalse(Arrays.stream(info.activities).anyMatch(a -> a.name.endsWith("TaskCenterActivity") || a.name.endsWith("RecycleBinActivity")));
        assertTrue(info.services == null || info.services.length == 0);
    }

    @Test public void workerStartsBeforeProgressAndRecreationClosesItsOldWindow() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), ended = new CountDownLatch(1);
        AtomicBoolean secondJobRan = new AtomicBoolean();
        screen = ActivityScenario.launch(MainActivity.class);
        try {
            screen.onActivity(activity -> mutate(activity, () -> {
                started.countDown();
                try { assertTrue(release.await(15, TimeUnit.SECONDS)); return true; }
                finally { ended.countDown(); }
            }));
            assertTrue("The file job must begin immediately", started.await(2, TimeUnit.SECONDS));
            find(By.text("正在处理"));
            screen.recreate();
            find(By.desc("左窗口文件列表"));
            assertFalse(device.hasObject(By.text("正在处理")));
            screen.onActivity(activity -> mutate(activity, () -> { secondJobRan.set(true); return true; }));
            SystemClock.sleep(100);
            assertFalse("Recreation must not permit concurrent file writers", secondJobRan.get());
        } finally {
            release.countDown(); assertTrue(ended.await(5, TimeUnit.SECONDS));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        }
    }

    @Test public void shellDoubleBackLeavesCommandRunning() throws Exception {
        File done = new File(work, "shell-continued.txt");
        try (ActivityScenario<ShellActivity> shell = ActivityScenario.launch(new Intent(context, ShellActivity.class).putExtra("cwd", work.getPath()))) {
            find(By.desc("Shell 命令")).setText("printf SHELL_READY; sleep 8; printf continued > " + FileStore.quote(done.getPath()));
            find(By.desc("执行")).click(); find(By.textContains("SHELL_READY").desc("Shell 输出"));
            Process[] process = {null};
            shell.onActivity(activity -> {
                try {
                    java.lang.reflect.Field runner = ShellActivity.class.getDeclaredField("runner"); runner.setAccessible(true);
                    java.lang.reflect.Field child = ShellRunner.class.getDeclaredField("process"); child.setAccessible(true);
                    process[0] = (Process) child.get(runner.get(activity));
                } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            });
            shell.onActivity(activity -> ((android.view.inputmethod.InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(activity.getWindow().getDecorView().getWindowToken(), 0));
            SystemClock.sleep(300);
            device.pressBack(); SystemClock.sleep(100);
            shell.onActivity(activity -> assertFalse(activity.isFinishing()));
            device.pressBack();
            await(() -> shell.getState() == androidx.lifecycle.Lifecycle.State.DESTROYED);
            assertTrue("Closing the Shell page must keep its command alive", process[0].isAlive());
            await(done::exists);
            assertEquals("continued", new String(Files.readAllBytes(done.toPath()), StandardCharsets.UTF_8));
        }
    }

    @Test public void shellSelectionMenuIsChineseAndOutputWaitsForSelection() throws Exception {
        try (ActivityScenario<ShellActivity> shell = ActivityScenario.launch(new Intent(context, ShellActivity.class).putExtra("cwd", work.getPath()))) {
            find(By.desc("Shell 命令")).setText("printf SELECT_SHELL_ALPHA; sleep 3; printf SECOND_CHUNK");
            find(By.desc("执行")).click(); find(By.textContains("SELECT_SHELL_ALPHA").desc("Shell 输出"));
            shell.onActivity(activity -> ((android.view.inputmethod.InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(activity.getWindow().getDecorView().getWindowToken(), 0));
            SystemClock.sleep(300);
            android.graphics.Rect bounds = find(By.desc("Shell 输出")).getVisibleBounds();
            int x = bounds.left + Ui.dp(context, 35), y = bounds.top + Ui.dp(context, 20);
            device.swipe(x, y, x, y, 140);
            find(By.text("复制")); assertFalse(device.hasObject(By.text("Copy")));
            SystemClock.sleep(3500);
            assertFalse(find(By.desc("Shell 输出")).getText().contains("SECOND_CHUNK"));
            find(By.text("复制")).click();
            find(By.textContains("SECOND_CHUNK").desc("Shell 输出"));
        }
    }

    private static void mutate(MainActivity activity, Tasks.Job<Boolean> work) {
        try {
            java.lang.reflect.Method method = MainActivity.class.getDeclaredMethod("mutate", Tasks.Job.class, Tasks.Done.class);
            method.setAccessible(true); method.invoke(activity, work, (Tasks.Done<Boolean>) value -> {});
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private void transfer(String action) {
        select(); find(By.desc("文件操作 " + action)).click();
    }
    private void select() {
        UiObject2 pane = find(By.desc("左窗口文件列表"));
        UiObject2 item = pane.findObject(By.desc("选择 item.txt"));
        long until = SystemClock.uptimeMillis() + 10000;
        while (item == null && SystemClock.uptimeMillis() < until) {
            SystemClock.sleep(40); pane = find(By.desc("左窗口文件列表"));
            item = pane.findObject(By.desc("选择 item.txt"));
        }
        assertNotNull(item); item.click(); find(By.desc("操作")).click();
    }
    private UiObject2 find(BySelector selector) {
        UiObject2 item = device.wait(Until.findObject(selector), 10000);
        assertNotNull(selector.toString(), item); return item;
    }
    private static void await(BooleanSupplier condition) {
        long until = SystemClock.uptimeMillis() + 10000;
        while (!condition.getAsBoolean() && SystemClock.uptimeMillis() < until) SystemClock.sleep(30);
        assertTrue(condition.getAsBoolean());
    }
}
