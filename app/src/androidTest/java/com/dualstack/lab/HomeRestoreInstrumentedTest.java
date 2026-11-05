package com.dualstack.lab;

import android.content.Context;
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
public class HomeRestoreInstrumentedTest {
    private Context context;
    private File last, home;

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        UglyBridge.ensureStorage(context);
        File base = context.getExternalFilesDir(null);
        last = Files.createTempDirectory(base.toPath(), "last-exit-").toFile();
        home = Files.createTempDirectory(base.toPath(), "configured-home-").toFile();
    }
    @After public void cleanup() throws Exception {
        FileStore store = new FileStore(context);
        store.delete(FileStore.local(last)); store.delete(FileStore.local(home));
        context.getSharedPreferences("MainActivity", 0).edit().clear().commit();
    }

    @Test public void configuredHomeWinsOverLastExitDirectory() throws Exception {
        context.getSharedPreferences("MainActivity", 0).edit().clear()
                .putString("left", last.getPath()).putString("right", last.getPath())
                .putString("home-left", home.getPath()).putString("home-right", home.getPath()).commit();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            assertNotNull(device.wait(Until.findObject(By.desc("双栈 Lab · " + home.getPath())), 10000));
        }
    }

    @Test public void lastExitDirectoryIsUsedWhenNoHomeIsConfigured() throws Exception {
        context.getSharedPreferences("MainActivity", 0).edit().clear()
                .putString("left", last.getPath()).putString("right", last.getPath()).commit();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            assertNotNull(device.wait(Until.findObject(By.desc("双栈 Lab · " + last.getPath())), 10000));
        }
    }

    private static final class UglyBridge {
        static void ensureStorage(Context context) throws Exception {
            UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            device.executeShellCommand("cmd appops set " + context.getPackageName() + " MANAGE_EXTERNAL_STORAGE allow");
        }
    }
}
