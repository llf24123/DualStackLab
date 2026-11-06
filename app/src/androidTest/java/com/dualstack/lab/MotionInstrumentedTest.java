package com.dualstack.lab;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MotionInstrumentedTest {
    private Context context;
    private File work;
    private ActivityScenario<MainActivity> scenario;

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "motion-").toFile();
        for (String name : new String[]{"a.txt", "b.txt"}) Files.write(new File(work, name).toPath(), new byte[]{65});
        context.getSharedPreferences("MainActivity", 0).edit().clear()
                .putString("left", work.getPath()).putString("right", work.getPath()).commit();
    }
    @After public void cleanup() throws Exception {
        if (scenario != null) scenario.close();
        context.getSharedPreferences("MainActivity", 0).edit().clear().commit();
        new FileStore(context).delete(FileStore.local(work));
    }
    @Test public void activePaneIndicatorAndSelectionSettleOnTheirFinalColours() throws Exception {
        scenario = ActivityScenario.launch(MainActivity.class);
        await(() -> row("a.txt") != null);
        scenario.onActivity(activity -> invoke(activity, "activate", get(activity, "right")));
        SystemClock.sleep(Ui.MOTION_SHORT + 150);
        scenario.onActivity(activity -> {
            assertEquals(Ui.ACCENT, color((View) get(get(activity, "right"), "indicator")));
            assertEquals(Ui.LINE, color((View) get(get(activity, "left"), "indicator")));
            invoke(activity, "activate", get(activity, "left"));
            Object pane = get(activity, "left");
            invoke(pane, "toggle", ((java.util.List<?>) get(pane, "entries")).get(0));
        });
        SystemClock.sleep(Ui.MOTION_SHORT + 250);
        scenario.onActivity(activity -> {
            assertEquals(Ui.ACCENT, color((View) get(get(activity, "left"), "indicator")));
            View selectedRow = row("a.txt");
            assertEquals(Ui.TINT, rowColor(selectedRow));
            View icon = ((ViewGroup) ((ViewGroup) selectedRow).getChildAt(0)).getChildAt(0);
            assertEquals(1f, icon.getScaleX(), .01f);
            assertEquals("Fade must end fully opaque", 1f, ((View) get(activity, "actions")).getAlpha(), .01f);
            Object pane = get(activity, "left");
            invoke(pane, "resetSelection"); invoke(pane, "selectionChanged");
        });
        SystemClock.sleep(Ui.MOTION_SHORT + 150);
        scenario.onActivity(activity -> assertEquals(Ui.SURFACE, rowColor(row("a.txt"))));
    }
    @Test public void comfortableDensityEnlargesRowsAndPersists() throws Exception {
        scenario = ActivityScenario.launch(MainActivity.class);
        await(() -> row("a.txt") != null);
        int[] compact = {0};
        scenario.onActivity(activity -> compact[0] = row("a.txt").getHeight());
        scenario.onActivity(activity -> activity.getPreferences(0).edit().putBoolean("comfortable", true).commit());
        scenario.recreate();
        await(() -> row("a.txt") != null && row("a.txt").getHeight() > 0);
        scenario.onActivity(activity -> {
            try {
                assertTrue("Comfortable rows are taller", row("a.txt").getHeight() > compact[0]);
                assertEquals(15f * activity.getResources().getDisplayMetrics().scaledDensity,
                        ((TextView) findText((ViewGroup) row("a.txt"), "a.txt")).getTextSize(), 1f);
            } finally { activity.getPreferences(0).edit().remove("comfortable").commit(); }
        });
    }
    private View row(String name) {
        View[] found = {null};
        scenario.onActivity(activity -> {
            ViewGroup list = (ViewGroup) get(get(activity, "left"), "list");
            for (int i = 0; i < list.getChildCount(); i++) if (findText((ViewGroup) list.getChildAt(i), name) != null) found[0] = list.getChildAt(i);
        });
        return found[0];
    }
    private static View findText(ViewGroup group, String text) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView view && text.contentEquals(view.getText())) return child;
            if (child instanceof ViewGroup nested) { View found = findText(nested, text); if (found != null) return found; }
        }
        return null;
    }
    private static int rowColor(View row) {
        return ((GradientDrawable) ((LayerDrawable) row.getBackground()).getDrawable(0)).getColor().getDefaultColor();
    }
    private static int color(View view) { return ((ColorDrawable) view.getBackground()).getColor(); }
    private static Object get(Object target, String name) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(target); }
            catch (NoSuchFieldException next) { } catch (IllegalAccessException error) { throw new AssertionError(error); }
        }
        throw new AssertionError("No field " + name);
    }
    private static void invoke(Object target, String name, Object... arguments) {
        for (Method method : target.getClass().getDeclaredMethods())
            if (method.getName().equals(name) && method.getParameterCount() == arguments.length) {
                try { method.setAccessible(true); method.invoke(target, arguments); return; }
                catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            }
        throw new AssertionError("No method " + name);
    }
    private static void await(BooleanSupplier condition) {
        long end = SystemClock.uptimeMillis() + 15000;
        while (!condition.getAsBoolean() && SystemClock.uptimeMillis() < end) SystemClock.sleep(30);
        assertTrue("Timed out", condition.getAsBoolean());
    }
}
