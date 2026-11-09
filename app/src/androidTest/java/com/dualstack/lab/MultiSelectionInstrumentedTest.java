package com.dualstack.lab;

import android.content.*;
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
import java.nio.file.Files;
import java.time.*;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MultiSelectionInstrumentedTest {
    private Context context;
    private UiDevice device;
    private File work, left, right;
    private SharedPreferences preferences;
    private Map<String, ?> previous;
    private ActivityScenario<MainActivity> scenario;

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        preferences = context.getSharedPreferences("MainActivity", 0);
        previous = new HashMap<>(preferences.getAll());
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "selection-review-").toFile();
        left = new File(work, "left"); right = new File(work, "right"); assertTrue(left.mkdir()); assertTrue(right.mkdir());
        make(left, "a.txt", 100, "2024-02-29T10:00");
        make(left, "b.txt", 200, "2024-02-29T23:59");
        make(left, "c.log", 2048, "2024-02-10T12:00");
        make(left, "d.txt", 4096, "2024-03-04T00:00");
        make(left, "e.txt", 0, "2025-01-01T00:00");
        make(left, ".hidden.txt", 1, "2024-02-29T10:00");
        make(right, "a.txt", 3, "2024-02-29T10:00");
        File folder = new File(left, "folder"); assertTrue(folder.mkdir()); assertTrue(folder.setLastModified(stamp("2024-02-29T10:00")));
        preferences.edit().clear().putString("left", left.getPath()).putString("right", right.getPath()).putBoolean("showHidden", false).commit();
        scenario = ActivityScenario.launch(MainActivity.class);
        scenario.onActivity(activity -> activity.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
        pane(true).findObject(By.text("a.txt"));
        assertNotNull(device.wait(Until.findObject(By.text("e.txt")), 10000));
    }

    @After public void cleanup() throws Exception {
        if (scenario != null) scenario.close();
        new FileStore(context).delete(FileStore.local(work));
        SharedPreferences.Editor restore = preferences.edit().clear();
        for (Map.Entry<String, ?> entry : previous.entrySet()) {
            Object value = entry.getValue(); String key = entry.getKey();
            if (value instanceof String text) restore.putString(key, text);
            else if (value instanceof Boolean flag) restore.putBoolean(key, flag);
            else if (value instanceof Integer number) restore.putInt(key, number);
            else if (value instanceof Long number) restore.putLong(key, number);
            else if (value instanceof Float number) restore.putFloat(key, number);
        }
        restore.commit();
    }

    @Test public void swipeShowsSelectionControlsAndBulkOperationsRemainAvailable() throws Exception {
        android.graphics.Rect row = pane(true).findObject(By.text("a.txt")).getVisibleBounds();
        android.graphics.Rect bounds = pane(true).getVisibleBounds();
        assertTrue(device.swipe(bounds.left + bounds.width() / 4, row.centerY(), bounds.right - bounds.width() / 8, row.centerY(), 20));
        for (String label : List.of("全选", "反选", "取消", "连选", "操作")) find(By.desc(label));
        click("全选"); assertSelected(true, "folder", "a.txt", "b.txt", "c.log", "d.txt", "e.txt");
        click("操作"); find(By.desc("文件操作 复制")); find(By.desc("文件操作 移动")); find(By.desc("文件操作 删除"));
        device.pressBack(); select("a.txt"); click("取消"); find(By.desc("新建")); assertSelected(true);
        select("a.txt"); click("全选"); click("反选"); find(By.desc("新建")); assertSelected(true);
    }

    @Test public void rangeSelectionIncludesBothEndsAndSupportsReverseDirection() throws Exception {
        select("a.txt"); click("连选"); pane(true).findObject(By.text("d.txt")).click();
        assertSelected(true, "a.txt", "b.txt", "c.log", "d.txt");
        click("取消"); select("d.txt"); click("连选"); pane(true).findObject(By.text("b.txt")).click();
        assertSelected(true, "b.txt", "c.log", "d.txt");
        click("连选"); device.pressBack();
        assertSelected(true, "b.txt", "c.log", "d.txt");
    }

    @Test public void sameDayWeekMonthYearUseReferenceAndExcludeHiddenFilesAndDirectories() throws Exception {
        for (String option : List.of("选择同一天文件", "选择同一周文件", "选择同一月文件", "选择同一年文件")) {
            if (device.hasObject(By.desc("取消"))) click("取消");
            select("a.txt"); condition(option);
            if (option.equals("选择同一天文件")) assertSelected(true, "a.txt", "b.txt");
            else if (option.equals("选择同一周文件")) assertSelected(true, "a.txt", "b.txt");
            else if (option.equals("选择同一月文件")) assertSelected(true, "a.txt", "b.txt", "c.log");
            else assertSelected(true, "a.txt", "b.txt", "c.log", "d.txt");
        }
    }

    @Test public void keywordSelectionCanReplaceAppendAndKeepsSelectionOnNoMatches() throws Exception {
        select("a.txt"); condition("按关键词选择…");
        find(By.desc("选择关键词")).setText(".LOG"); find(By.text("选择")).click(); assertSelected(true, "c.log");
        find(By.desc("连选")).longClick(); find(By.desc("追加选择")).click(); find(By.text("按关键词选择…")).click();
        find(By.desc("选择关键词")).setText("d.txt"); find(By.text("选择")).click(); assertSelected(true, "c.log", "d.txt");
        condition("按关键词选择…"); find(By.desc("选择关键词")).setText("absent-name"); find(By.text("选择")).click();
        assertSelected(true, "c.log", "d.txt");
    }

    @Test public void customSizeAndTimeIncludeBoundariesAndRejectReversedRange() throws Exception {
        select("a.txt"); condition("按文件大小选择…");
        find(By.desc("最小文件大小")).setText("200"); find(By.desc("最大文件大小")).setText("100");
        find(By.text("选择")).click(); find(By.text("最小大小不能大于最大大小")); assertSelected(true, "a.txt");
        find(By.desc("最小文件大小")).setText("100"); find(By.desc("最大文件大小")).setText("200");
        find(By.text("选择")).click(); assertSelected(true, "a.txt", "b.txt");
        condition("按文件时间选择…"); find(By.desc("选择起始时间")).setText("2024-02-29");
        find(By.desc("选择结束时间")).setText("2024-02-29"); find(By.text("选择")).click(); assertSelected(true, "a.txt", "b.txt");
    }

    @Test public void selectionsRemainIndependentAcrossPanesAndRangeSurvivesSorting() throws Exception {
        select("b.txt"); click("连选");
        scenario.onActivity(activity -> {
            Object leftPane = get(activity, "left"); set(leftPane, "sort", 2); invoke(leftPane, "apply");
        });
        pane(true).findObject(By.text("d.txt")).click(); assertSelected(true, "b.txt", "c.log", "d.txt");
        pane(false).findObject(By.desc("选择 a.txt")).click(); assertSelected(false, "a.txt");
        click("取消"); assertSelected(false); assertSelected(true, "b.txt", "c.log", "d.txt");
        pane(true).click(); find(By.desc("操作"));
    }

    @Test public void filteringDropsInvisibleSelectionsAndInvalidatesRangeAnchor() throws Exception {
        select("a.txt"); click("全选"); click("连选");
        scenario.onActivity(activity -> {
            Object pane = get(activity, "left"); set(pane, "filter", "b.txt"); invoke(pane, "apply");
            assertFalse((Boolean) get(pane, "rangeSelecting"));
        });
        assertSelected(true, "b.txt"); click("反选"); assertSelected(true);
    }

    @Test public void sizeAndTimeParsingHandleLeapDaysDecimalsOverflowAndInvalidDates() {
        assertEquals(1536, SelectionTools.bytes("1.5", 1)); assertEquals(0, SelectionTools.bytes("0", 0));
        for (String invalid : List.of("-1", "1.1", "9223372036854775808", "abc")) {
            try { SelectionTools.bytes(invalid, 0); fail(invalid); } catch (IllegalArgumentException expected) { }
        }
        long reference = stamp("2024-02-29T12:00");
        long[] month = SelectionTools.dateRange(reference, 1);
        assertEquals(stamp("2024-02-01T00:00"), month[0]); assertEquals(stamp("2024-03-01T00:00"), month[1]);
        assertEquals(stamp("2024-03-01T00:00") - 1, SelectionTools.timeBound("2024-02-29", true));
        for (String invalid : List.of("2025-02-29", "2024-13-01", "2024-02-29 25:00", "junk")) {
            try { SelectionTools.timeBound(invalid, false); fail(invalid); } catch (IllegalArgumentException expected) { }
        }
    }

    private void condition(String option) { find(By.desc("连选")).longClick(); find(By.text(option)).click(); }
    private void select(String name) { pane(true).findObject(By.desc("选择 " + name)).click(); find(By.desc("操作")); }
    private void click(String description) { find(By.desc(description)).click(); }
    private UiObject2 pane(boolean left) { return find(By.desc(left ? "左窗口文件列表" : "右窗口文件列表")); }
    private UiObject2 find(BySelector selector) {
        UiObject2 object = device.wait(Until.findObject(selector), 10000); assertNotNull(selector.toString(), object); return object;
    }
    private void assertSelected(boolean left, String... names) {
        // UiAutomator injects the tap asynchronously; wait for the list to process it before comparing.
        Set<String> expected = new HashSet<>(Arrays.asList(names));
        long deadline = SystemClock.uptimeMillis() + 8000;
        Set<String> actual = new HashSet<>();
        while (SystemClock.uptimeMillis() < deadline) {
            actual.clear();
            scenario.onActivity(activity -> {
                Object pane = get(activity, left ? "left" : "right");
                Set<?> keys = (Set<?>) get(pane, "selected");
                for (Object item : (List<?>) get(pane, "all")) {
                    FileStore.Node node = (FileStore.Node) item; if (keys.contains(node.key())) actual.add(node.name);
                }
            });
            if (actual.equals(expected)) return;
            SystemClock.sleep(60);
        }
        assertEquals(expected, actual);
    }
    private static void make(File parent, String name, int size, String modified) throws Exception {
        File file = new File(parent, name); Files.write(file.toPath(), new byte[size]); assertTrue(file.setLastModified(stamp(modified)));
    }
    private static long stamp(String local) { return LocalDateTime.parse(local).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(); }
    private static Object get(Object object, String name) {
        try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void set(Object object, String name, Object value) {
        try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void invoke(Object object, String name) {
        try { Method method = object.getClass().getDeclaredMethod(name); method.setAccessible(true); method.invoke(object); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
}
