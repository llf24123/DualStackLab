package com.dualstack.lab;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.rosemoe.sora.widget.CodeEditor;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class EditorResponsivenessInstrumentedTest {
    private static final String TAG = "EditorResponsiveness";
    private Context context;
    private File work;

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "editor-responsive-").toFile();
    }
    @After public void cleanup() throws Exception { new FileStore(context).delete(FileStore.local(work)); }

    /** An ELF renamed to .sh opens and scrolls without the per-frame stalls that froze the editor. */
    @Test public void denseBinaryPayloadOpensAndScrollsSmoothly() throws Exception {
        String supplied = InstrumentationRegistry.getArguments().getString("samplePath");
        File sample = supplied != null ? new File(supplied) : binaryFixture(new File(work, "payload.sh"));
        try (ActivityScenario<EditorActivity> scenario = ActivityScenario.launch(intent(sample))) {
            awaitLoaded(scenario);
            scenario.onActivity(activity -> {
                assertTrue((boolean) get(activity, "denseControls"));
                assertFalse("Dense control bytes must not be drawn as measured badges", editor(activity).isRenderFunctionCharacters());
            });
            List<Long> frames = scrollAndMeasure(scenario);
            long worst = frames.isEmpty() ? -1 : Collections.max(frames);
            Log.i(TAG, "sample=" + sample.getName() + " frames=" + frames.size() + " worstFrameMs=" + worst);
            assertTrue("No frames were drawn while scrolling", frames.size() > 10);
            assertTrue("Scrolling stalled a frame for " + worst + " ms", worst < 250);
        }
    }
    /** Ordinary text with a few control bytes keeps showing them as visible badges. */
    @Test public void sparseControlBytesStillRenderAsVisibleBadges() throws Exception {
        File file = new File(work, "padded.ini");
        Files.write(file.toPath(), "[AndroidPak]\nEnabled=1\n\0\0\u0001\u0002".getBytes(StandardCharsets.UTF_8));
        try (ActivityScenario<EditorActivity> scenario = ActivityScenario.launch(intent(file))) {
            awaitLoaded(scenario);
            scenario.onActivity(activity -> {
                assertTrue((boolean) get(activity, "controlCharacters"));
                assertFalse((boolean) get(activity, "denseControls"));
                assertTrue(editor(activity).isRenderFunctionCharacters());
            });
        }
    }
    /** Loading itself must not block the main thread while the binary page is decoded. */
    @Test public void denseBinaryPayloadLoadsWithoutLongMainThreadStalls() throws Exception {
        File sample = binaryFixture(new File(work, "payload.sh"));
        Handler main = new Handler(Looper.getMainLooper());
        long worst = 0, started = SystemClock.uptimeMillis();
        try (ActivityScenario<EditorActivity> scenario = ActivityScenario.launch(intent(sample))) {
            while (SystemClock.uptimeMillis() - started < 15000) {
                CountDownLatch answered = new CountDownLatch(1);
                long posted = SystemClock.uptimeMillis();
                main.post(answered::countDown);
                assertTrue("Main thread stopped responding", answered.await(10, TimeUnit.SECONDS));
                worst = Math.max(worst, SystemClock.uptimeMillis() - posted);
                if (loaded(scenario)) break;
                SystemClock.sleep(100);
            }
            assertTrue("Editor never finished loading", loaded(scenario));
        }
        assertTrue("Main thread stalled for " + worst + " ms while loading", worst < 1500);
    }
    private Intent intent(File file) {
        Intent intent = new Intent(context, EditorActivity.class); FileStore.local(file).put(intent); return intent;
    }
    private static List<Long> scrollAndMeasure(ActivityScenario<EditorActivity> scenario) {
        List<Long> frames = Collections.synchronizedList(new ArrayList<>());
        long[] last = {0}; boolean[] running = {true};
        Choreographer.FrameCallback[] tick = new Choreographer.FrameCallback[1];
        tick[0] = time -> {
            if (last[0] != 0) frames.add((time - last[0]) / 1000000);
            last[0] = time;
            if (running[0]) Choreographer.getInstance().postFrameCallback(tick[0]);
        };
        scenario.onActivity(activity -> Choreographer.getInstance().postFrameCallback(tick[0]));
        for (int i = 0; i < 12; i++) {
            int direction = i % 2 == 0 ? 1 : -1;
            scenario.onActivity(activity -> {
                CodeEditor editor = editor(activity);
                editor.getScroller().startScroll(editor.getOffsetX(), editor.getOffsetY(), 0, direction * 900, 0);
                editor.invalidate();
            });
            SystemClock.sleep(300);
        }
        running[0] = false;
        synchronized (frames) { return new ArrayList<>(frames); }
    }
    private static void awaitLoaded(ActivityScenario<EditorActivity> scenario) {
        long end = SystemClock.uptimeMillis() + 15000;
        while (!loaded(scenario) && SystemClock.uptimeMillis() < end) SystemClock.sleep(50);
        assertTrue("Editor never finished loading", loaded(scenario));
        SystemClock.sleep(300);
    }
    private static boolean loaded(ActivityScenario<EditorActivity> scenario) {
        boolean[] value = {false};
        scenario.onActivity(activity -> value[0] = (boolean) get(activity, "loaded") && !(boolean) get(activity, "reading"));
        return value[0];
    }
    private static CodeEditor editor(EditorActivity activity) { return (CodeEditor) get(activity, "editor"); }
    private static Object get(Object target, String name) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    /** ELF-like bytes: dense NUL and C0 controls with sparse newlines, comparable to a stripped ARM64 binary. */
    private static File binaryFixture(File file) throws IOException {
        Random random = new Random(20261105);
        byte[] data = new byte[7 * 1024 * 1024];
        byte[] header = {0x7f, 'E', 'L', 'F', 2, 1, 1, 0};
        System.arraycopy(header, 0, data, 0, header.length);
        for (int i = header.length; i < data.length; i++) {
            int roll = random.nextInt(100);
            data[i] = (byte) (roll < 26 ? 0 : roll < 52 ? 1 + random.nextInt(31) : 32 + random.nextInt(224));
            if (data[i] == '\n' && random.nextInt(4) != 0) data[i] = 0x11;
        }
        Files.write(file.toPath(), data);
        return file;
    }
}
