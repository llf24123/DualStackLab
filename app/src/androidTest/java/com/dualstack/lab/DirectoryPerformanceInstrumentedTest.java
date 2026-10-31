package com.dualstack.lab;

import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.*;
import android.view.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.File;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DirectoryPerformanceInstrumentedTest {
    @Test public void measureRestrictedDirectoryNavigationAndScroll() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.executeShellCommand("cmd appops set " + context.getPackageName() + " MANAGE_EXTERNAL_STORAGE allow");
        File folder = Files.createTempDirectory(context.getExternalFilesDir(null).toPath(), "browse-perf-").toFile();
        File child = new File(folder, "child"); assertTrue(child.mkdir());
        for (int i = 0; i < 1200; i++) assertTrue(new File(folder, String.format(Locale.US, "item_%04d.ini", i)).createNewFile());
        context.getSharedPreferences("MainActivity", 0).edit().clear()
                .putString("left", folder.getPath()).putString("right", child.getPath()).commit();
        FileStore store = new FileStore(context);
        JSONObject result = new JSONObject();
        HandlerThread metricsThread = new HandlerThread("browse-frame-metrics"); metricsThread.start();
        List<Long> frames = Collections.synchronizedList(new ArrayList<>());
        Window.OnFrameMetricsAvailableListener listener = (window, metrics, dropped) -> {
            if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 0)
                frames.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION));
        };
        try {
            List<Long> enumeration = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                long start = SystemClock.elapsedRealtimeNanos();
                assertEquals(1201, store.list(FileStore.directory(folder, false)).size());
                enumeration.add(SystemClock.elapsedRealtimeNanos() - start);
            }
            result.put("rootEnumeration", summary(enumeration));
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(new Intent(context, MainActivity.class))) {
                await(scenario, folder);
                scenario.onActivity(activity -> activity.getWindow().addOnFrameMetricsAvailableListener(
                        listener, new Handler(metricsThread.getLooper())));
                List<Long> navigation = new ArrayList<>();
                for (int i = 0; i < 12; i++) {
                    File destination = i % 2 == 0 ? child : folder;
                    long start = SystemClock.elapsedRealtimeNanos();
                    scenario.onActivity(activity -> invoke(get(activity, "left"), "navigate",
                            FileStore.directory(destination, false)));
                    await(scenario, destination);
                    navigation.add(SystemClock.elapsedRealtimeNanos() - start);
                    SystemClock.sleep(220);
                }
                result.put("repeatedNavigation", summary(navigation));
                UiObject2 list = device.wait(Until.findObject(By.desc("左窗口文件列表")), 5000);
                assertNotNull(list); Rect bounds = list.getVisibleBounds();
                for (int i = 0; i < 12; i++) {
                    boolean down = i % 4 < 2;
                    device.swipe(bounds.centerX(), down ? bounds.bottom - 100 : bounds.top + 120,
                            bounds.centerX(), down ? bounds.top + 120 : bounds.bottom - 100, 20);
                }
                device.waitForIdle();
                scenario.onActivity(activity -> activity.getWindow().removeOnFrameMetricsAvailableListener(listener));
                List<Long> measured;
                synchronized (frames) { measured = new ArrayList<>(frames); }
                assertTrue("No rendered frames captured", measured.size() > 40);
                result.put("frames", summary(measured));
                result.put("framesOver16_67ms", measured.stream().filter(n -> n > 16666667).count());
                result.put("framesOver32ms", measured.stream().filter(n -> n > 32000000).count());
                result.put("frameCount", measured.size());
                File evidence = new File(context.getExternalFilesDir(null), "performance-0611");
                assertTrue(evidence.isDirectory() || evidence.mkdirs());
                Files.write(new File(evidence, "result.json").toPath(),
                        result.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                assertTrue(device.takeScreenshot(new File(evidence, "directory.png")));
                device.dumpWindowHierarchy(new File(evidence, "directory.xml"));
                android.util.Log.i("DirectoryPerformance", result.toString());
            }
        } finally {
            metricsThread.quitSafely(); metricsThread.join(2000);
            store.delete(FileStore.local(folder));
            context.getSharedPreferences("MainActivity", 0).edit().clear().commit();
        }
    }
    private static JSONObject summary(List<Long> values) throws JSONException {
        List<Long> sorted = new ArrayList<>(values); Collections.sort(sorted);
        return new JSONObject().put("samples", sorted.size())
                .put("medianMs", sorted.get(sorted.size() / 2) / 1000000.0)
                .put("p95Ms", sorted.get(Math.min(sorted.size() - 1, (int) (sorted.size() * .95))) / 1000000.0);
    }
    private static void await(ActivityScenario<MainActivity> scenario, File destination) {
        long end = SystemClock.uptimeMillis() + 15000;
        boolean[] loaded = {false};
        while (!loaded[0] && SystemClock.uptimeMillis() < end) {
            scenario.onActivity(activity -> {
                Object pane = get(activity, "left");
                FileStore.Node node = (FileStore.Node) get(pane, "location");
                loaded[0] = destination.getPath().equals(node.path()) && node.key().equals(get(pane, "recordedDirectory"))
                        && ((View) get(pane, "list")).getVisibility() == View.VISIBLE;
            });
            if (!loaded[0]) SystemClock.sleep(5);
        }
        assertTrue("Directory load did not finish: " + destination, loaded[0]);
    }
    private static Object get(Object target, String name) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static void invoke(Object pane, String name, FileStore.Node node) {
        try { Method method = pane.getClass().getDeclaredMethod(name, FileStore.Node.class); method.setAccessible(true); method.invoke(pane, node); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
}
