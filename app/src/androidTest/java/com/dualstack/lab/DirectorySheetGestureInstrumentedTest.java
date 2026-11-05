package com.dualstack.lab;

import android.content.Context;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DirectorySheetGestureInstrumentedTest {
    private Context context;
    private File work;

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.executeShellCommand("cmd appops set " + context.getPackageName() + " MANAGE_EXTERNAL_STORAGE allow");
        work = Files.createTempDirectory(context.getExternalFilesDir(null).toPath(), "sheet-gesture-").toFile();
        context.getSharedPreferences("MainActivity", 0).edit().clear()
                .putString("left", work.getPath()).putString("right", work.getPath()).commit();
    }

    @After public void cleanup() throws Exception {
        new FileStore(context).delete(FileStore.local(work));
        context.getSharedPreferences("MainActivity", 0).edit().clear().commit();
    }

    @Test public void panelFollowsFingerBeforeReleaseAndSnapsOnRelease() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            assertNotNull(device.wait(Until.findObject(By.desc("底部操作栏")), 10000));
            assertNotNull(device.wait(Until.findObject(By.desc("双栈 Lab · " + work.getPath())), 10000));
            AtomicReference<Float> first = new AtomicReference<>(), second = new AtomicReference<>();
            scenario.onActivity(activity -> {
                View bar = find(activity.getWindow().getDecorView(), "底部操作栏");
                assertNotNull(bar);
                int cx = bar.getWidth() / 2, cy = bar.getHeight() / 2;
                long t = SystemClock.uptimeMillis();
                dispatch(bar, t, MotionEvent.ACTION_DOWN, cx, cy);
                dispatch(bar, t + 16, MotionEvent.ACTION_MOVE, cx, cy - 120);
                View overlay = find(activity.getWindow().getDecorView(), "最近与书签面板");
                assertNotNull("Panel must be visible while the finger is still down", overlay);
                View body = panelBody(overlay);
                assertTrue("Panel should start below the screen edge", body.getTranslationY() > 0);
                first.set(body.getTranslationY());
                dispatch(bar, t + 32, MotionEvent.ACTION_MOVE, cx, cy - 260);
                second.set(body.getTranslationY());
                dispatch(bar, t + 48, MotionEvent.ACTION_UP, cx, cy - 260);
            });
            assertTrue("Panel must follow the finger upward", second.get() < first.get());
            SystemClock.sleep(350);
            AtomicReference<Float> settled = new AtomicReference<>();
            scenario.onActivity(activity -> {
                View overlay = find(activity.getWindow().getDecorView(), "最近与书签面板");
                assertNotNull(overlay);
                settled.set(panelBody(overlay).getTranslationY());
            });
            assertEquals("Released panel should snap fully open", 0f, settled.get(), 1f);
        }
    }

    @Test public void releaseBelowThresholdClosesPanel() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            assertNotNull(device.wait(Until.findObject(By.desc("底部操作栏")), 10000));
            assertNotNull(device.wait(Until.findObject(By.desc("双栈 Lab · " + work.getPath())), 10000));
            scenario.onActivity(activity -> {
                View bar = find(activity.getWindow().getDecorView(), "底部操作栏");
                int cx = bar.getWidth() / 2, cy = bar.getHeight() / 2;
                long t = SystemClock.uptimeMillis();
                dispatch(bar, t, MotionEvent.ACTION_DOWN, cx, cy);
                dispatch(bar, t + 16, MotionEvent.ACTION_MOVE, cx, cy - 20);
                dispatch(bar, t + 32, MotionEvent.ACTION_UP, cx, cy - 20);
            });
            SystemClock.sleep(350);
            scenario.onActivity(activity -> assertNull("Short drag must not leave a panel",
                    find(activity.getWindow().getDecorView(), "最近与书签面板")));
        }
    }

    private static void dispatch(View view, long time, int action, int x, int y) {
        MotionEvent event = MotionEvent.obtain(time, time, action, x, y, 0);
        view.dispatchTouchEvent(event); event.recycle();
    }
    private static View panelBody(View overlay) {
        ViewGroup group = (ViewGroup) overlay;
        return group.getChildAt(group.getChildCount() - 1);
    }
    private static View find(View root, String description) {
        CharSequence value = root.getContentDescription();
        if (value != null && description.contentEquals(value)) return root;
        if (root instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            View found = find(group.getChildAt(i), description);
            if (found != null) return found;
        }
        return null;
    }
}
