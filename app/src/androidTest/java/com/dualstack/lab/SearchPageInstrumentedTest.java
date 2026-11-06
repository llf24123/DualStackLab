package com.dualstack.lab;

import android.content.*;
import android.os.SystemClock;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SearchPageInstrumentedTest {
    private Context context;
    private UiDevice device;
    private File work;
    private ActivityScenario<SearchActivity> scenario;
    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "search-page-").toFile();
        Files.write(new File(work, "alpha.txt").toPath(), "first\nneedle in content\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.write(new File(work, ".hidden.txt").toPath(), "needle".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        SearchJob seed = SearchJob.start(context, FileStore.directory(work, false), "alpha", Pattern.compile(Pattern.quote("alpha"), Pattern.CASE_INSENSITIVE), false);
        await(() -> seed.snapshot().finished());
        Intent intent = new Intent(context, SearchActivity.class).putExtra("rootKey", FileStore.local(work).key()).putExtra("rootPath", work.getPath());
        scenario = ActivityScenario.launch(intent);
        find(By.desc("搜索关键词"));
    }
    @After public void cleanup() throws Exception {
        if (SearchJob.active() != null) { SearchJob.active().cancel(); await(() -> SearchJob.active().snapshot().finished()); }
        if (scenario != null) scenario.close();
        new FileStore(context).delete(FileStore.local(work));
    }
    @Test public void invalidRegexStaysInlineAndOptionsSurviveRecreation() {
        find(By.desc("正则表达式")).click();
        find(By.desc("区分大小写")).click();
        find(By.desc("搜索关键词")).setText("["); find(By.desc("开始搜索")).click();
        scenario.onActivity(a -> assertNotNull(((EditText) field(a, "query")).getError()));
        assertFalse(device.hasObject(By.text("操作失败")));
        scenario.recreate();
        assertEquals("[", find(By.desc("搜索关键词")).getText());
        scenario.onActivity(a -> { assertEquals(true, field(a, "regex")); assertEquals(true, field(a, "caseSensitive")); });
        capture("search-regex-validation.png");
    }
    @Test public void contentResultsShowHighlightedSnippetAndCanCopyPath() {
        find(By.desc("搜索范围：文件名或内容")).click();
        find(By.desc("包含隐藏文件")).click();
        find(By.desc("搜索关键词")).setText("needle"); find(By.desc("开始搜索")).click();
        await(() -> SearchJob.active().contents && SearchJob.active().snapshot().finished());
        find(By.text("alpha.txt"));
        assertFalse(device.hasObject(By.text(".hidden.txt")));
        scenario.onActivity(a -> {
            ListView list = descendant(a.getWindow().getDecorView(), ListView.class);
            assertNotNull(list); assertEquals(1, list.getAdapter().getCount());
            TextView snippet = text(list, "2  needle in content"); assertNotNull(snippet);
            assertTrue(snippet.getText() instanceof Spanned);
            assertEquals(1, ((Spanned) snippet.getText()).getSpans(0, snippet.length(), BackgroundColorSpan.class).length);
        });
        capture("search-content-results.png");
        find(By.desc("搜索结果 alpha.txt")).longClick();
        scenario.onActivity(a -> {
            ClipboardManager clipboard = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
            assertEquals(new File(work, "alpha.txt").getPath(), clipboard.getPrimaryClip().getItemAt(0).getText().toString());
        });
    }
    @Test public void openingFromAnotherDirectoryStartsFreshAndKeepsPreviousResultsAccessible() throws Exception {
        File other = new File(work, "other"); assertTrue(other.mkdir());
        scenario.close();
        Intent intent = new Intent(context, SearchActivity.class).putExtra("rootKey", FileStore.local(other).key()).putExtra("rootPath", other.getPath());
        scenario = ActivityScenario.launch(intent);
        scenario.onActivity(a -> assertEquals(0, ((EditText) field(a, "query")).length()));
        find(By.text("查看上次搜索"));
        scenario.onActivity(a -> { assertNull(field(a, "job")); assertEquals(other.getPath(), field(a, "rootPath")); });
        scenario.recreate();
        scenario.onActivity(a -> assertNull(field(a, "job")));
        find(By.desc("搜索关键词")).setText("missing");
        capture("search-history.png");
    }
    @Test public void darkLandscapeKeepsSearchControlsAndResultsReachable() throws Exception {
        SharedPreferences appearance = context.getSharedPreferences("appearance", 0);
        String previous = appearance.getString("mode", "system");
        try {
            appearance.edit().putString("mode", "dark").commit(); scenario.recreate();
            device.setOrientationLeft(); find(By.desc("搜索关键词")); find(By.text("alpha.txt"));
            assertTrue(find(By.desc("开始搜索")).isEnabled());
            scenario.onActivity(a -> assertTrue(Ui.dark));
            capture("search-dark-landscape.png");
        } finally {
            appearance.edit().putString("mode", previous).commit();
            device.setOrientationNatural(); device.unfreezeRotation();
        }
    }
    @Test public void noMatchesAndCaseSensitiveSearchCanBeRetried() {
        find(By.desc("区分大小写")).click();
        find(By.desc("搜索关键词")).setText("ALPHA"); find(By.desc("开始搜索")).click();
        await(() -> SearchJob.active().query.equals("ALPHA") && SearchJob.active().snapshot().finished());
        find(By.textContains("没有找到“ALPHA”"));
        find(By.desc("区分大小写")).click(); find(By.desc("开始搜索")).click();
        find(By.text("alpha.txt"));
    }
    private void capture(String name) {
        File out = new File(context.getExternalFilesDir(null), "search-page-evidence");
        assertTrue(out.isDirectory() || out.mkdirs()); assertTrue(device.takeScreenshot(new File(out, name)));
    }
    private UiObject2 find(BySelector selector) {
        UiObject2 value = device.wait(Until.findObject(selector), 10000); assertNotNull(selector.toString(), value); return value;
    }
    private static void await(BooleanSupplier condition) {
        long end = SystemClock.uptimeMillis() + 15000;
        while (SystemClock.uptimeMillis() < end) { if (condition.getAsBoolean()) return; SystemClock.sleep(40); }
        fail("Search did not settle");
    }
    private static Object field(Object target, String name) {
        try { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static <T> T descendant(View root, Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        if (root instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            T found = descendant(group.getChildAt(i), type); if (found != null) return found;
        }
        return null;
    }
    private static TextView text(View root, String value) {
        if (root instanceof TextView label && value.contentEquals(label.getText())) return label;
        if (root instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            TextView found = text(group.getChildAt(i), value); if (found != null) return found;
        }
        return null;
    }
}
