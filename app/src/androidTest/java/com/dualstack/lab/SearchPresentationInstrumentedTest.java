package com.dualstack.lab;

import android.app.AlertDialog;
import android.content.*;
import android.graphics.drawable.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SearchPresentationInstrumentedTest {
    private Context context;
    private UiDevice device;
    private File work, target, folder, right;
    private ActivityScenario<MainActivity> scenario;
    private MainActivity activity;
    private Intent originalIntent;
    private boolean rootAvailable;

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        rootAvailable = RootAccess.available();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "search-ui-").toFile();
        folder = new File(work, "nested"); right = new File(work, "right");
        assertTrue(folder.mkdir()); assertTrue(right.mkdir());
        for (int i = 0; i < 65; i++) Files.write(new File(folder, String.format(Locale.ROOT, "item-%03d.txt", i)).toPath(), new byte[]{65});
        target = new File(folder, "zz-result.unknown"); Files.write(target.toPath(), new byte[]{1, 2, 3});
        Files.write(new File(right, "other.txt").toPath(), new byte[]{66});
        context.getSharedPreferences("MainActivity", 0).edit().clear()
                .putString("left", work.getPath()).putString("right", right.getPath()).commit();
        scenario = ActivityScenario.launch(MainActivity.class);
        scenario.onActivity(value -> { activity = value; originalIntent = new Intent(value.getIntent()); });
        await(() -> ready(work));
    }

    @After public void cleanup() throws Exception {
        if (SearchJob.active() != null) SearchJob.active().cancel();
        // onNewIntent replaces the intent AndroidX uses to identify its ActivityScenario.
        if (activity != null) main(() -> { activity.setIntent(originalIntent); activity.finish(); });
        if (scenario != null && !device.hasObject(By.desc("搜索进度"))) scenario.close();
        new FileStore(context).delete(FileStore.local(work));
        context.getSharedPreferences("MainActivity", 0).edit().clear().commit();
    }

    @Test public void clickingSearchResultLocatesOffscreenFileAndOnlyTintsItsName() {
        SearchJob.start(context, FileStore.directory(work, false), "zz-result", Pattern.compile("zz-result"), false);
        main(() -> activity.startActivity(new Intent(activity, SearchActivity.class)));
        find(By.text(target.getName())).click();
        await(() -> ready(folder) && marked().equals(target.getPath()));
        assertNotNull(find(By.desc("选择 " + target.getName())));
        main(() -> {
            Object pane = get(activity, "left");
            assertTrue(((Set<?>) get(pane, "selected")).isEmpty());
            assertEquals(rootAvailable, ((FileStore.Node) get(pane, "location")).root);
            TextView name = findText((View) get(pane, "list"), target.getName()); assertNotNull(name);
            assertEquals(Ui.HIT, name.getCurrentTextColor());
            View row = (View) name.getParent().getParent();
            LayerDrawable ripple = (LayerDrawable) row.getBackground();
            assertEquals(Ui.SURFACE, ((GradientDrawable) ripple.getDrawable(0)).getColor().getDefaultColor());
        });
        assertFalse("A search hit must not open the file", device.hasObject(By.text("打开方式…")));
        capture("search-filename-light-green.png");
    }

    @Test public void tappingAnotherPaneFileClearsTheNameTint() {
        locate();
        find(By.desc("选择 other.txt")).click();
        await(() -> marked().isEmpty());
        main(() -> assertEquals(Ui.TEXT, findText((View) get(get(activity, "left"), "list"), target.getName()).getCurrentTextColor()));
    }

    @Test public void tappingTheResultClearsItsMarkBeforeOpeningNormally() {
        locate();
        find(By.text(target.getName())).click();
        find(By.text("打开方式…"));
        assertEquals("", marked());
        device.pressBack();
    }

    @Test public void backClearsTheSearchMarkAndNavigatesUp() {
        locate(); device.pressBack();
        await(() -> marked().isEmpty() && ready(work));
    }

    @Test public void pathJumpOffersPasteWithoutRootCheckboxAndDefaultsToRoot() {
        main(() -> {
            ((ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(
                    ClipData.newPlainText("path", folder.getPath()));
            invoke(activity, "jump");
        });
        find(By.text("粘贴")).click();
        assertEquals(folder.getPath(), find(By.desc("跳转路径")).getText());
        assertFalse(device.hasObject(By.text("使用 ROOT 访问")));
        find(By.res("android:id/button1")).click();
        await(() -> ready(folder));
        main(() -> assertEquals(rootAvailable, ((FileStore.Node) get(get(activity, "left"), "location")).root));
    }

    @Test public void apkKeepsBothCompressAndExtractAndPacksOriginalFilename() throws Exception {
        File apk = new File(work, "demo.apk"); Files.write(apk.toPath(), "apk-fixture".getBytes(StandardCharsets.UTF_8));
        main(() -> invoke(get(activity, "left"), "reload"));
        find(By.text("demo.apk")).longClick();
        assertTrue(find(By.desc("文件操作 压缩")).isEnabled());
        assertTrue(find(By.desc("文件操作 解压")).isEnabled());
        find(By.desc("文件操作 压缩")).click();
        find(By.desc("压缩文件名称")).setText("apk-pack.zip");
        find(By.res("android:id/button1")).click();
        File archive = new File(work, "apk-pack.zip");
        await(() -> {
            if (!archive.isFile()) return false;
            try (ZipFile zip = new ZipFile(archive)) {
                return zip.getEntry("demo.apk") != null && zip.getEntry("demo.apk").getSize() == apk.length();
            } catch (IOException loading) { return false; }
        });
        try (ZipFile zip = new ZipFile(archive); InputStream input = zip.getInputStream(zip.getEntry("demo.apk"))) {
            assertArrayEquals(Files.readAllBytes(apk.toPath()), FileStore.readBytes(input, 100));
        }
    }

    @Test public void conditionalMenuIsCenteredAndContainsAllSevenRules() {
        find(By.desc("选择 nested")).click(); find(By.desc("连选")).longClick();
        for (String text : List.of("选择同一天文件", "选择同一周文件", "选择同一月文件", "选择同一年文件",
                "按文件时间选择…", "按文件大小选择…", "按关键词选择…")) find(By.text(text));
        main(() -> {
            AlertDialog dialog = (AlertDialog) get(activity, "fileMenu");
            View decor = dialog.getWindow().getDecorView(); int[] xy = new int[2]; decor.getLocationOnScreen(xy);
            int width = activity.getResources().getDisplayMetrics().widthPixels;
            assertEquals(width / 2f, xy[0] + decor.getWidth() / 2f, Ui.dp(activity, 12));
        });
        capture("conditional-selection-dialog.png");
        device.pressBack();
    }

    @Test public void darkModeUsesBlackFolderBadgesAndOnlyColoursTheHitName() {
        SharedPreferences appearance = context.getSharedPreferences("appearance", 0);
        String previous = appearance.getString("mode", "system");
        try {
            appearance.edit().putString("mode", "dark").commit();
            scenario.recreate(); scenario.onActivity(value -> activity = value);
            await(() -> ready(work));
            main(() -> {
                assertTrue(Ui.dark); assertEquals(0xff000000, Ui.ICON_BG);
                Object pane = get(activity, "left");
                TextView parent = findText((View) get(pane, "list"), "nested");
                ViewGroup row = (ViewGroup) parent.getParent().getParent();
                ViewGroup iconFrame = (ViewGroup) row.getChildAt(0);
                assertEquals(0xff000000, ((GradientDrawable) iconFrame.getChildAt(0).getBackground()).getColor().getDefaultColor());
            });
            locate();
            main(() -> assertEquals(Ui.HIT, findText((View) get(get(activity, "left"), "list"), target.getName()).getCurrentTextColor()));
            capture("search-filename-dark-mode.png");
        } finally { appearance.edit().putString("mode", previous).commit(); }
    }

    private void capture(String name) {
        File evidence = new File(context.getExternalFilesDir(null), "final-ui-evidence");
        assertTrue(evidence.isDirectory() || evidence.mkdirs());
        assertTrue(device.takeScreenshot(new File(evidence, name)));
    }
    private void locate() {
        main(() -> activity.onNewIntent(new Intent(activity, MainActivity.class).putExtra("searchResult", target.getPath())));
        await(() -> ready(folder) && marked().equals(target.getPath()));
        find(By.desc("选择 " + target.getName()));
    }
    private String marked() {
        String[] value = {""}; main(() -> { Object mark = get(get(activity, "left"), "marked"); value[0] = mark == null ? "" : mark.toString(); }); return value[0];
    }
    private boolean ready(File directory) {
        boolean[] value = {false}; main(() -> {
            Object pane = get(activity, "left"); FileStore.Node location = (FileStore.Node) get(pane, "location");
            value[0] = location != null && location.path().equals(directory.getPath())
                    && location.key().equals(get(pane, "recordedDirectory")) && ((View) get(pane, "list")).isEnabled();
        }); return value[0];
    }
    private UiObject2 find(BySelector selector) {
        UiObject2 object = device.wait(Until.findObject(selector), 15000); assertNotNull(selector.toString(), object); return object;
    }
    private static void await(BooleanSupplier check) {
        long end = SystemClock.uptimeMillis() + 20000;
        while (SystemClock.uptimeMillis() < end) { if (check.getAsBoolean()) return; SystemClock.sleep(60); }
        fail("UI did not settle");
    }
    private static TextView findText(View view, String text) {
        if (view instanceof TextView label && text.contentEquals(label.getText())) return label;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            TextView found = findText(group.getChildAt(i), text); if (found != null) return found;
        }
        return null;
    }
    private static void main(Runnable task) { InstrumentationRegistry.getInstrumentation().runOnMainSync(task); }
    private static Object get(Object target, String name) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void invoke(Object target, String name) {
        try { Method method = target.getClass().getDeclaredMethod(name); method.setAccessible(true); method.invoke(target); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
}
