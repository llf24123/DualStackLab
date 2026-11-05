package com.dualstack.lab;

import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RefreshBounceInstrumentedTest {
    private Context context;
    private File work;
    private ActivityScenario<MainActivity> scenario;
    private MainActivity activity;
    private SwipeSelectListView list;
    private long downTime;
    private float originX, originY;

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.executeShellCommand("cmd appops set " + context.getPackageName() + " MANAGE_EXTERNAL_STORAGE allow");
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "refresh-bounce-").toFile();
        for (int i = 0; i < 30; i++) Files.write(new File(work, "row" + i + ".ini").toPath(), new byte[]{65});
        context.getSharedPreferences("MainActivity", 0).edit().clear()
                .putString("left", work.getPath()).putString("right", work.getPath()).commit();
        scenario = ActivityScenario.launch(MainActivity.class);
        assertNotNull(device.wait(Until.findObject(By.text("row0.ini")), 15000));
        scenario.onActivity(value -> { activity = value; list = (SwipeSelectListView) get(get(value, "left"), "list"); });
        SystemClock.sleep(350);
    }

    @After public void cleanup() throws Exception {
        if (scenario != null) scenario.close();
        new FileStore(context).delete(FileStore.local(work));
        context.getSharedPreferences("MainActivity", 0).edit().clear().commit();
    }

    @Test public void shortPullFollowsFingerAndDrawsOnlyItsOwnCurvedEdge() {
        int generation = generation();
        main(() -> {
            begin(); move(Ui.dp(context, 22));
            float first = list.getTranslationY();
            move(Ui.dp(context, 50));
            assertTrue(first > 0f);
            assertTrue(list.getTranslationY() > first);
            assertEquals(list.getTranslationY(), edge(true).depth(), .5f);
            assertEquals(0f, edge(false).depth(), .5f);
            end(MotionEvent.ACTION_UP);
        });
        assertRest();
        assertEquals("A short pull must not refresh", generation, generation());
    }

    @Test public void confirmedPullRefreshesWithoutSnappingToZeroAtRelease() {
        int generation = generation();
        main(() -> {
            begin(); move(Ui.dp(context, 180));
            float held = list.getTranslationY();
            assertTrue(held >= Ui.dp(context, 64));
            end(MotionEvent.ACTION_UP);
            assertEquals("Release starts at the held position", held, list.getTranslationY(), 1f);
        });
        assertRest();
        assertTrue("Release above threshold must refresh", generation() > generation);
    }

    @Test public void cancelledPullNeverRefreshesAndReturnsBothSurfacesToRest() {
        int generation = generation();
        main(() -> { begin(); move(Ui.dp(context, 200)); end(MotionEvent.ACTION_CANCEL); });
        assertRest();
        assertEquals(generation, generation());
    }

    @Test public void navigationCancelsAnInFlightRebound() {
        main(() -> {
            begin(); move(Ui.dp(context, 50)); end(MotionEvent.ACTION_UP);
            list.stopForNavigation();
            assertEquals(0f, list.getTranslationX(), .1f);
            assertEquals(0f, list.getTranslationY(), .1f);
        });
        assertRest();
    }

    @Test public void pulledPaneShowsTheToolbarColouredCurve() {
        main(() -> {
            begin(); move(Ui.dp(context, 100));
            assertTrue(edge(true).depth() > 0f);
        });
        SystemClock.sleep(160);
        try {
            File evidence = new File(context.getExternalFilesDir(null), "final-ui-evidence");
            assertTrue(evidence.isDirectory() || evidence.mkdirs());
            assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                    .takeScreenshot(new File(evidence, "elastic-pull-curve.png")));
        } finally { main(() -> end(MotionEvent.ACTION_CANCEL)); }
        assertRest();
    }

    private void begin() {
        int[] location = new int[2]; list.getLocationOnScreen(location);
        originX = location[0] + list.getWidth() * .6f; originY = location[1] + Ui.dp(context, 50);
        downTime = SystemClock.uptimeMillis(); send(MotionEvent.ACTION_DOWN, 0);
    }
    private void move(float distance) { send(MotionEvent.ACTION_MOVE, distance); }
    private void end(int action) { send(action, Ui.dp(context, 200)); }
    private void send(int action, float distance) {
        int[] location = new int[2]; list.getLocationOnScreen(location);
        MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                originX, originY + distance, 0);
        event.offsetLocation(-location[0], -location[1]);
        list.dispatchTouchEvent(event); event.recycle();
    }
    private void assertRest() {
        SystemClock.sleep(800);
        main(() -> {
            assertEquals(0f, list.getTranslationY(), .5f);
            assertEquals(0f, edge(true).depth(), .5f);
            assertEquals(0f, edge(false).depth(), .5f);
        });
    }
    private int generation() {
        int[] result = {0}; main(() -> result[0] = (Integer) get(get(activity, "left"), "generation")); return result[0];
    }
    private PullEdgeView edge(boolean left) { return (PullEdgeView) get(get(activity, left ? "left" : "right"), "pullEdge"); }
    private static void main(Runnable task) { InstrumentationRegistry.getInstrumentation().runOnMainSync(task); }
    private static Object get(Object target, String name) {
        try { java.lang.reflect.Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
}
