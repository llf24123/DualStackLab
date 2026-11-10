package com.dualstack.lab;

import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class UpgradeInstrumentedTest {
    private Context context;
    private File root;
    private FileStore store;
    private Map<String, ?> preferences;
    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext(); store = new FileStore(context);
        root = Files.createTempDirectory(context.getFilesDir().toPath(), "upgrade-test-").toFile();
        preferences = new HashMap<>(context.getSharedPreferences("MainActivity", 0).getAll());
        context.getSharedPreferences("MainActivity", 0).edit().clear().putString("left", root.getPath()).putString("right", root.getPath()).commit();
    }
    @After public void cleanup() throws Exception {
        store.delete(FileStore.local(root));
        SharedPreferences.Editor edit = context.getSharedPreferences("MainActivity", 0).edit().clear();
        for (Map.Entry<String, ?> item : preferences.entrySet()) {
            Object value = item.getValue();
            if (value instanceof String s) edit.putString(item.getKey(), s);
            else if (value instanceof Boolean b) edit.putBoolean(item.getKey(), b);
            else if (value instanceof Integer n) edit.putInt(item.getKey(), n);
        }
        edit.commit();
    }

    @Test public void cancelledPumpStopsAtChunkBoundaryAndCountsTransferredBytes() throws Exception {
        Tasks.Progress progress = new Tasks.Progress(); ByteArrayOutputStream out = new ByteArrayOutputStream();
        OutputStream controlled = new FilterOutputStream(out) {
            @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); progress.cancelled.set(true); }
        };
        try { progress.run(() -> { FileStore.pump(new ByteArrayInputStream(new byte[1024*1024]), controlled); return null; }); fail("Must stop"); }
        catch (Tasks.CancelledException expected) { }
        assertEquals(128*1024, progress.transferred.get()); assertEquals(128*1024, out.size());
    }
    @Test public void feederBindingCarriesProgressAndRestoresThreadLocal() throws Exception {
        Tasks.Progress progress = new Tasks.Progress();
        progress.run(() -> {
            Thread child = new Thread(Tasks.Progress.bind(() -> Tasks.Progress.bytes(1234))); child.start(); child.join(); return null;
        });
        assertEquals(1234, progress.transferred.get()); Tasks.Progress.bytes(10); assertEquals(1234, progress.transferred.get());
    }
    @Test public void deviceInformationContainsRequestedCategoriesAndNoFabricatedId() throws Exception {
        List<DeviceInfo.Field> values = DeviceInfo.basic(context);
        assertTrue(values.size() > 25);
        assertTrue(values.stream().anyMatch(f -> f.name().contains("Android ID")));
        assertTrue(values.stream().anyMatch(f -> f.name().contains("序列号")));
        assertTrue(values.stream().anyMatch(f -> f.section().equals("DRM")));
        assertTrue(values.stream().anyMatch(f -> f.section().equals("处理器")));
        assertEquals("00ff10", DeviceInfo.hex(new byte[]{0,(byte)255,16}));
    }
    @Test public void selectionModeKeepsListViewportWithOriginalRows() throws Exception {
        for (int i = 0; i < 180; i++) file(root, String.format(Locale.ROOT,"f%03d",i), "v");
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitMainList(scenario, "left", 181);
            scenario.onActivity(a -> { try { ((ListView) field(field(a,"left"),"list")).setSelectionFromTop(80, -3); } catch(Exception e){throw new RuntimeException(e);} });
            idle(); AtomicInteger pos = new AtomicInteger(), offset = new AtomicInteger();
            scenario.onActivity(a -> { try {
                ListView list=(ListView)field(field(a,"left"),"list"); pos.set(list.getFirstVisiblePosition()); offset.set(list.getChildAt(0).getTop());
                View icon=((android.view.ViewGroup)list.getChildAt(1)).getChildAt(0);
                boolean comfortable=(Boolean)field(a,"comfortable");
                assertEquals(Ui.dp(a,comfortable?40:32),icon.getWidth()); icon.performClick();
            }catch(Exception e){throw new RuntimeException(e);} }); idle();
            scenario.onActivity(a -> { try { ListView list=(ListView)field(field(a,"left"),"list"); assertEquals(pos.get(),list.getFirstVisiblePosition()); assertEquals(offset.get(),list.getChildAt(0).getTop()); assertEquals(View.GONE,((View)field(a,"status")).getVisibility()); }catch(Exception e){throw new RuntimeException(e);} });
        }
    }

    private static File file(File p,String name,String contents)throws IOException{File f=new File(p,name);Files.write(f.toPath(),contents.getBytes(StandardCharsets.UTF_8));return f;}

    private static Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static void idle(){InstrumentationRegistry.getInstrumentation().waitForIdleSync();}
    private static void await(BooleanSupplier condition,long timeout)throws Exception{long until=SystemClock.uptimeMillis()+timeout;while(!condition.getAsBoolean()&&SystemClock.uptimeMillis()<until)Thread.sleep(30);assertTrue("Timed out",condition.getAsBoolean());}
    private static void awaitMainList(ActivityScenario<MainActivity> scenario,String side,int size)throws Exception{AtomicInteger count=new AtomicInteger();await(()->{scenario.onActivity(a->{try{count.set(((ListView)field(field(a,side),"list")).getCount());}catch(Exception e){throw new RuntimeException(e);}});return count.get()>=size;},15000);}
}
