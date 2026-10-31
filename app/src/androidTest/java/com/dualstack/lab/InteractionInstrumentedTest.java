package com.dualstack.lab;

import android.content.*;
import android.net.Uri;
import android.os.SystemClock;
import android.view.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.file.*;
import java.util.zip.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class InteractionInstrumentedTest {
    Context context; UiDevice device; File work, left, right, evidence;
    ActivityScenario<?> scenario;
    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.setOrientationNatural(); device.waitForIdle();
        device.executeShellCommand("cmd appops set " + context.getPackageName() + " MANAGE_EXTERNAL_STORAGE allow");
        work = Files.createTempDirectory(context.getExternalFilesDir(null).toPath(), "interaction-").toFile();
        left = new File(work, "left"); right = new File(work, "right");
        assertTrue(left.mkdir()); assertTrue(right.mkdir());
        evidence = new File(context.getExternalFilesDir(null), "interaction-review-063"); evidence.mkdirs();
        context.getSharedPreferences("MainActivity", 0).edit().clear().putString("left", left.getPath())
                .putString("right", right.getPath()).commit();
        context.getSharedPreferences("terminal-appearance", 0).edit().clear().commit();
    }
    @After public void cleanup() throws Exception {
        if (scenario != null) scenario.close();
        device.unfreezeRotation(); device.setOrientationNatural();
        device.executeShellCommand("cmd uimode night no");
        new FileStore(context).delete(FileStore.local(work));
        context.getSharedPreferences("MainActivity", 0).edit().clear().commit();
        context.getSharedPreferences("terminal-appearance", 0).edit().clear().commit();
    }
    UiObject2 find(BySelector selector) {
        UiObject2 found = device.wait(Until.findObject(selector), 10000);
        if (found == null) try { capture("missing-element-066"); } catch (Exception ignored) { }
        assertNotNull(selector.toString(), found); return found;
    }
    void main() { scenario = ActivityScenario.launch(MainActivity.class); find(By.desc("左窗口文件列表")); }
    void awaitContents(File file, String expected) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 10000;
        while (SystemClock.uptimeMillis() < deadline) {
            if (file.isFile() && new String(Files.readAllBytes(file.toPath())).equals(expected)) return;
            SystemClock.sleep(50);
        }
        fail("Copy not complete: " + file);
    }
    File zip() throws Exception {
        File file = new File(left, "external.zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
            out.putNextEntry(new ZipEntry("inside.txt")); out.write("ZIP_OK".getBytes()); out.closeEntry();
        }
        return file;
    }
    void capture(String name) throws Exception {
        device.waitForIdle(); assertTrue(device.takeScreenshot(new File(evidence, name + ".png")));
        device.dumpWindowHierarchy(new File(evidence, name + ".xml"));
    }
    @Test public void restoredDirectoryBackGoesToParent() throws Exception {
        Files.write(new File(left, "back.txt").toPath(), "back".getBytes()); main(); find(By.text("back.txt"));
        device.pressBack(); find(By.desc("双栈 Lab · " + work.getPath()));
        assertTrue(device.hasObject(By.desc("左窗口文件列表"))); capture("back-parent");
    }
    @Test public void rootNeedsTwoBackActionsToExit() throws Exception {
        Intent intent = new Intent(context, MainActivity.class).putExtra("navigatePath", "/");
        scenario = ActivityScenario.launch(intent); find(By.desc("双栈 Lab · /"));
        device.pressBack(); assertTrue(device.hasObject(By.desc("双栈 Lab · /")));
        device.pressBack(); assertTrue(device.wait(Until.gone(By.desc("左窗口文件列表")), 5000));
    }
    @Test public void longPressMenuCopiesRenamesAndBookmarks() throws Exception {
        Files.write(new File(left, "note.txt").toPath(), "COPY_OK".getBytes());
        File folder = new File(left, "bookmarked"); assertTrue(folder.mkdir()); main();
        find(By.text("note.txt")).longClick();
        for (String label : new String[]{"复制", "移动", "删除", "重命名", "工具", "压缩", "属性", "分享", "打开方式…", "添加书签"})
            find(By.desc("文件操作 " + label));
        capture("file-actions-light"); find(By.desc("文件操作 复制")).click();
        assertTrue(device.wait(Until.findObject(By.desc("右窗口文件列表")), 5000) != null);
        long end = SystemClock.uptimeMillis() + 10000;
        awaitContents(new File(right, "note.txt"), "COPY_OK");
        assertTrue(device.wait(Until.gone(By.desc("清选")), 5000));
        assertEquals("COPY_OK", new String(Files.readAllBytes(new File(right, "note.txt").toPath())));
        find(By.desc("左窗口文件列表")).findObject(By.text("note.txt")).longClick();
        find(By.desc("文件操作 重命名")).click(); find(By.clazz(android.widget.EditText.class)).setText("renamed.txt");
        find(By.text("保存")).click(); find(By.text("renamed.txt"));
        find(By.text("bookmarked")).longClick(); assertFalse(find(By.desc("文件操作 分享")).isEnabled());
        assertFalse(find(By.desc("文件操作 打开方式…")).isEnabled()); capture("folder-actions-light");
        assertTrue(find(By.desc("文件操作 添加书签")).isEnabled());
        find(By.desc("文件操作 添加书签")).click();
        assertTrue("Bookmark action did not close menu", device.wait(Until.gone(By.desc("文件操作 添加书签")), 5000));
        end = SystemClock.uptimeMillis() + 5000;
        while (new DirectoryPlaces(context).entries(true).stream().noneMatch(e -> e.path().equals(folder.getPath()))
                && SystemClock.uptimeMillis() < end) SystemClock.sleep(50);
        assertTrue("Expected " + folder.getPath() + "; actual " + new DirectoryPlaces(context).entries(true),
                new DirectoryPlaces(context).entries(true).stream().anyMatch(e -> e.path().equals(folder.getPath())));
    }
    @Test public void externalViewNavigatesToContainingDirectoryAndBrowsesZip() throws Exception {
        File file = zip();
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.fromFile(file)).setClass(context, MainActivity.class);
        scenario = ActivityScenario.launch(intent); find(By.desc("双栈 Lab · " + left.getPath()));
        find(By.text(file.getName())).click(); find(By.text("inside.txt")); capture("external-zip");
        device.pressBack(); find(By.desc("双栈 Lab · " + left.getPath()));
    }
    @Test public void externalFileProviderNavigatesToOriginalWithoutImport() throws Exception {
        File shared = new File(context.getCacheDir(), "shared"); shared.mkdirs();
        File file = new File(shared, "stream.zip"); Files.copy(zip().toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        Uri uri = androidx.core.content.FileProvider.getUriForFile(context, context.getPackageName() + ".files", file);
        Intent intent = new Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).setClass(context, MainActivity.class);
        scenario = ActivityScenario.launch(intent); find(By.desc("双栈 Lab · " + shared.getCanonicalPath()));
        assertTrue(file.exists()); find(By.text("stream.zip")).click(); find(By.text("inside.txt"));
        capture("external-stream-zip");
    }
    @Test public void externalAppContentUriLocatesOriginalAndPipeDoesNotImport() throws Exception {
        device.executeShellCommand("cmd appops set com.dualstack.lab.test MANAGE_EXTERNAL_STORAGE allow");
        File base = new File("/storage/emulated/0/Download/dualstack-external-069");
        assertTrue(base.isDirectory() || base.mkdirs());
        File original = new File(base, "external-app.zip"); Files.write(original.toPath(), ExternalFixtureProvider.zipBytes());
        try {
            Uri disk = Uri.parse("content://" + ExternalFixtureProvider.AUTHORITY + "/disk").buildUpon()
                    .appendQueryParameter("path", original.getPath()).build();
            Intent intent = new Intent(Intent.ACTION_VIEW, disk).setClass(context, MainActivity.class)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            scenario = ActivityScenario.launch(intent);
            find(By.desc("双栈 Lab · " + base.getPath())); find(By.text("external-app.zip"));
            find(By.text("external-app.zip")).click(); find(By.text("provider.txt")); device.pressBack();
            find(By.desc("双栈 Lab · " + base.getPath())); scenario.close(); scenario = null;
            File imported = new File(context.getFilesDir(), "received-files");
            int before = imported.exists() ? imported.list().length : 0;
            Intent pipe = new Intent(Intent.ACTION_VIEW, Uri.parse("content://" + ExternalFixtureProvider.AUTHORITY + "/pipe"))
                    .setClass(context, MainActivity.class).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            scenario = ActivityScenario.launch(pipe);
            find(By.text("provider.txt")); capture("external-uri-original-069");
            assertEquals(before, imported.exists() ? imported.list().length : 0); assertTrue(original.exists());
            device.pressBack(); find(By.desc("双栈 Lab · " + base.getPath()));
        } finally { Files.deleteIfExists(original.toPath()); }
    }
    @Test public void propertiesEditOctalAndCheckboxesForFileAndDirectory() throws Exception {
        File privateWork = Files.createTempDirectory(context.getCacheDir().toPath(), "permissions-ui-").toFile();
        File file = new File(privateWork, "mode.txt"), folder = new File(privateWork, "mode-folder");
        Files.write(file.toPath(), new byte[]{65}); assertTrue(folder.mkdir()); android.system.Os.chmod(file.getPath(), 0644);
        try {
            Intent incoming = new Intent(context, MainActivity.class).putExtra("navigatePath", privateWork.getPath());
            scenario = ActivityScenario.launch(incoming); find(By.text("mode.txt")).longClick();
            find(By.desc("文件操作 属性")).click();
            UiObject2 octal = find(By.desc("八进制权限")); octal.setText("900");
            assertFalse(find(By.text("保存权限")).isEnabled());
            octal.setText("600"); find(By.desc("所有者执行")).click();
            assertEquals("700", find(By.desc("八进制权限")).getText());
            capture("permissions-file-069"); find(By.text("保存权限")).click();
            assertTrue(device.wait(Until.gone(By.desc("八进制权限")), 5000));
            assertEquals(0700, android.system.Os.stat(file.getPath()).st_mode & 07777);
            find(By.text("mode-folder")).longClick(); find(By.desc("文件操作 属性")).click();
            find(By.desc("八进制权限")).setText("750"); capture("permissions-folder-069");
            find(By.text("保存权限")).click();
            assertTrue(device.wait(Until.gone(By.desc("八进制权限")), 5000));
            assertEquals(0750, android.system.Os.stat(folder.getPath()).st_mode & 07777);
        } finally { new FileStore(context).delete(FileStore.local(privateWork)); }
    }
    @Test public void mainArchiveMenuExtractsIntoOtherPaneAndSelectedFolderWorks() throws Exception {
        File archive = zip(); main(); find(By.text("external.zip")).longClick();
        find(By.desc("文件操作 解压")).click(); find(By.desc("解压文件夹名称")).setText("whole");
        find(By.text("解压").clazz(android.widget.Button.class)).click();
        awaitContents(new File(right, "whole/inside.txt"), "ZIP_OK"); find(By.text("whole"));
        find(By.desc("左窗口文件列表")).findObject(By.text("external.zip")).click(); find(By.text("inside.txt")).longClick();
        find(By.desc("文件操作 解压")).click(); find(By.desc("解压文件夹名称")).setText("partial");
        find(By.text("解压").clazz(android.widget.Button.class)).click();
        awaitContents(new File(right, "partial/inside.txt"), "ZIP_OK"); capture("extract-selected-069");
        assertTrue(archive.exists()); assertTrue(new File(right, "whole/inside.txt").exists());
    }
    @Test public void manifestResolvesExternalZipViewAndShare() {
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://example/document.zip"), "application/zip")
                .addCategory(Intent.CATEGORY_DEFAULT).setPackage(context.getPackageName());
        assertNotNull(context.getPackageManager().resolveActivity(view, 0));
        Intent share = new Intent(Intent.ACTION_SEND).setType("application/octet-stream").setPackage(context.getPackageName());
        assertNotNull(context.getPackageManager().resolveActivity(share, 0));
    }
    @Test public void repeatedExternalIntentReturnsToSourceAndRecreationPreservesLocation() throws Exception {
        File file = zip();
        Intent incoming = new Intent(Intent.ACTION_VIEW, Uri.fromFile(file)).setClass(context, MainActivity.class);
        scenario = ActivityScenario.launch(incoming); find(By.text("external.zip"));
        device.pressBack(); find(By.desc("双栈 Lab · " + work.getPath()));
        scenario.onActivity(a -> ((MainActivity)a).onNewIntent(incoming)); find(By.desc("双栈 Lab · " + left.getPath()));
        device.pressBack(); find(By.desc("双栈 Lab · " + work.getPath())); scenario.recreate();
        find(By.desc("双栈 Lab · " + work.getPath()));
    }
    @Test public void singlePaneCopyAndMultiSelectionMenuWork() throws Exception {
        Files.write(new File(left, "one.txt").toPath(), "one".getBytes());
        Files.write(new File(left, "two.txt").toPath(), "two".getBytes());
        File destination = new File(left, "destination"); assertTrue(destination.mkdir()); main();
        find(By.text("one.txt")).longClick(); find(By.desc("文件操作 复制")).longClick();
        find(By.clazz(android.widget.EditText.class)).setText(destination.getPath()); find(By.text("复制")).click();
        long deadline = SystemClock.uptimeMillis() + 10000;
        awaitContents(new File(destination, "one.txt"), "one");
        assertTrue(device.wait(Until.gone(By.text("单窗口复制")), 5000));
        assertEquals("one", new String(Files.readAllBytes(new File(destination, "one.txt").toPath())));
        find(By.desc("选择 one.txt")).click(); find(By.desc("选择 two.txt")).click(); find(By.desc("操作")).click();
        assertFalse(find(By.desc("文件操作 重命名")).isEnabled());
        assertTrue(find(By.desc("文件操作 分享")).isEnabled()); find(By.desc("文件操作 移动")).click();
        deadline = SystemClock.uptimeMillis() + 10000;
        awaitContents(new File(right, "two.txt"), "two");
        assertTrue(device.wait(Until.gone(By.desc("清选")), 5000));
        assertEquals("two", new String(Files.readAllBytes(new File(right, "two.txt").toPath())));
        assertFalse(new File(left, "one.txt").exists()); assertFalse(new File(left, "two.txt").exists());
    }
    private void swipeRow(boolean leftPane, String name, boolean rightwards) {
        android.graphics.Rect bounds = find(By.desc(leftPane ? "左窗口文件列表" : "右窗口文件列表")).getVisibleBounds();
        int y = find(By.text(name)).getVisibleBounds().centerY();
        int a = bounds.left + bounds.width() * 27 / 100, b = bounds.left + bounds.width() * 86 / 100;
        assertTrue(device.swipe(rightwards ? a : b, y, rightwards ? b : a, y, 18));
    }
    @Test public void sidewaysSelectionCopiesFilesAndFoldersAndDoesNotOpenRows() throws Exception {
        Files.write(new File(left, "one.txt").toPath(), "one".getBytes());
        File folder = new File(left, "folder"); assertTrue(folder.mkdir());
        Files.write(new File(folder, "child.txt").toPath(), "child".getBytes());
        main(); find(By.text("one.txt")); swipeRow(true, "folder", true);
        find(By.desc("复制")); assertTrue(device.hasObject(By.text("one.txt")));
        swipeRow(true, "one.txt", false); find(By.textContains("已选择 2 项"));
        find(By.desc("复制")).click();
        awaitContents(new File(right, "one.txt"), "one"); awaitContents(new File(right, "folder/child.txt"), "child");
        assertTrue(device.wait(Until.gone(By.desc("清选")), 5000));
        swipeRow(false, "one.txt", true); find(By.desc("清选"));
        swipeRow(false, "one.txt", false); assertTrue(device.wait(Until.gone(By.desc("清选")), 5000));
    }
    @Test public void horizontalThenVerticalStrokeSelectsRangeAndCanClearIt() throws Exception {
        for (int i = 0; i < 3; i++) Files.write(new File(left, "row-" + i + ".txt").toPath(), ("row" + i).getBytes());
        main(); find(By.text("row-2.txt"));
        android.graphics.Rect bounds = find(By.desc("左窗口文件列表")).getVisibleBounds();
        int x = bounds.left + bounds.width() * 28 / 100, end = bounds.left + bounds.width() * 85 / 100;
        int top = find(By.text("row-0.txt")).getVisibleBounds().centerY();
        int bottom = find(By.text("row-2.txt")).getVisibleBounds().centerY();
        for (int count : new int[]{3, 0}) {
            assertTrue(device.swipe(new android.graphics.Point[]{new android.graphics.Point(x, top),
                    new android.graphics.Point(end, top), new android.graphics.Point(end, bottom)}, 14));
            if (count > 0) {
                capture("range-first-stroke");
                assertTrue(find(By.textContains("已选择 ")).getText(),
                        device.hasObject(By.textContains("已选择 3 项")));
            }
            else assertTrue(device.wait(Until.gone(By.desc("清选")), 5000));
        }
        find(By.text("row-0.txt")).click(); find(By.desc("保存"));
    }
    private <T> T descendant(android.view.View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof android.view.ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                T found = descendant(group.getChildAt(i), type); if (found != null) return found;
            }
        }
        return null;
    }
    @Test public void centralShadowUsesNoTouchViewAndAdaptsToThemeAndRotation() throws Exception {
        main();
        for (String mode : new String[]{"no", "yes"}) {
            device.executeShellCommand("cmd uimode night " + mode); device.waitForIdle();
            scenario.onActivity(a -> {
                DualPaneLayout panes = descendant(a.getWindow().getDecorView(), DualPaneLayout.class);
                assertNotNull(panes); assertEquals(2, panes.getChildCount());
                assertEquals(panes.getChildAt(0).getRight(), panes.getChildAt(1).getLeft());
                assertFalse(panes.isClickable()); assertFalse(panes.isFocusable());
                android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
                        panes.getWidth(), panes.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
                panes.draw(new android.graphics.Canvas(bitmap));
                int x = panes.getWidth() / 2, y = panes.getHeight() * 3 / 4;
                int base = bitmap.getPixel(x + 10, y), edge = bitmap.getPixel(x - 1, y);
                assertTrue("Missing shadow in " + mode, android.graphics.Color.red(edge) < android.graphics.Color.red(base));
                assertEquals("Shadow spilled into right pane", base, bitmap.getPixel(x + 1, y));
                bitmap.recycle();
            });
            capture("pane-shadow-" + mode);
        }
        device.setOrientationLeft(); find(By.desc("右窗口文件列表"));
        scenario.onActivity(a -> {
            DualPaneLayout panes = descendant(a.getWindow().getDecorView(), DualPaneLayout.class);
            assertEquals(panes.getChildAt(0).getRight(), panes.getChildAt(1).getLeft());
        });
    }
    @Test public void verticalScrollAndParentRowSwipeDoNotSelectFiles() throws Exception {
        for (int i = 0; i < 80; i++) Files.write(new File(left, String.format("vertical-%03d.txt", i)).toPath(), new byte[]{65});
        main(); find(By.text("vertical-000.txt")); swipeRow(true, "..", true);
        assertFalse(device.hasObject(By.desc("清选")));
        android.graphics.Rect bounds = find(By.desc("左窗口文件列表")).getVisibleBounds();
        assertTrue(device.swipe(bounds.centerX(), bounds.bottom - 120, bounds.centerX(), bounds.top + 100, 35));
        assertFalse(device.hasObject(By.desc("清选")));
        capture("vertical-scroll-066");
        scenario.onActivity(a -> {
            SwipeSelectListView list = descendant(a.getWindow().getDecorView(), SwipeSelectListView.class);
            assertTrue("Vertical scroll was blocked", list.getFirstVisiblePosition() > 0);
        });
        assertTrue(device.hasObject(By.desc("双栈 Lab · " + left.getPath())));
    }
    @Test public void selectionStrokeAutoScrollsToReachOffscreenFiles() throws Exception {
        for (int i = 0; i < 100; i++) Files.write(new File(left, String.format("range-%03d.txt", i)).toPath(), new byte[]{65});
        main(); find(By.text("range-000.txt"));
        UiObject2 pane = find(By.desc("左窗口文件列表"));
        int initiallyVisible = pane.findObjects(By.descStartsWith("选择 ")).size();
        android.graphics.Rect bounds = pane.getVisibleBounds();
        int x = bounds.left + bounds.width() * 28 / 100, endX = bounds.left + bounds.width() * 85 / 100;
        int y = find(By.text("range-000.txt")).getVisibleBounds().centerY();
        android.graphics.Point[] points = new android.graphics.Point[24];
        points[0] = new android.graphics.Point(x, y); points[1] = new android.graphics.Point(endX, y);
        for (int i = 2; i < points.length; i++) points[i] = new android.graphics.Point(endX, bounds.bottom - 80);
        assertTrue(device.swipe(points, 16));
        String selected = find(By.textContains("已选择 ")).getText();
        java.util.regex.Matcher count = java.util.regex.Pattern.compile("已选择 (\\d+) 项").matcher(selected);
        assertTrue(selected, count.find());
        assertTrue("Selection did not scroll past " + initiallyVisible + " rows: " + selected,
                Integer.parseInt(count.group(1)) > initiallyVisible);
        capture("swipe-range-autoscroll"); find(By.desc("清选")).click();
        assertTrue(device.wait(Until.gone(By.desc("清选")), 5000));
    }
    @Test public void fileActionsFollowDarkThemeAndFitLandscape() throws Exception {
        Files.write(new File(left, "theme.txt").toPath(), "theme".getBytes());
        device.executeShellCommand("cmd uimode night yes"); main(); find(By.text("theme.txt")).longClick();
        find(By.desc("文件操作 打开方式…")); capture("file-actions-dark");
        find(By.desc("关闭文件操作")).click(); device.setOrientationLeft();
        find(By.text("theme.txt")).longClick(); find(By.desc("文件操作 复制")); capture("file-actions-landscape");
    }
    @Test public void terminalFontBoundsAndPreferencePersist() throws Exception {
        File script = new File(left, "size.sh"); Files.write(script.toPath(), "printf SIZE_READY; sleep 120\n".getBytes());
        Intent intent = new Intent(context, TerminalActivity.class); FileStore.local(script).put(intent);
        scenario = ActivityScenario.launch(intent); find(By.desc("终端输入与输出"));
        scenario.onActivity(a -> {
            TerminalActivity terminal = (TerminalActivity)a;
            for (int i = 0; i < 60; i++) terminal.terminalView().mClient.onScale(1.2f);
            assertEquals(30, terminal.terminalTextSize());
            for (int i = 0; i < 60; i++) terminal.terminalView().mClient.onScale(.8f);
            assertEquals(10, terminal.terminalTextSize());
        });
        scenario.close(); scenario = ActivityScenario.launch(intent);
        scenario.onActivity(a -> assertEquals(10, ((TerminalActivity)a).terminalTextSize()));
    }
    @Test public void terminalPinchChangesSizeAndKeepsRunningSession() throws Exception {
        File script = new File(left, "pinch.sh"); Files.write(script.toPath(), "printf PINCH_READY; read value; printf 'PINCH_INPUT_%s' \"$value\"\n".getBytes());
        Intent intent = new Intent(context, TerminalActivity.class); FileStore.local(script).put(intent);
        scenario = ActivityScenario.launch(intent); find(By.desc("终端输入与输出"));
        long deadline = SystemClock.uptimeMillis() + 10000;
        boolean[] ready = {false}; while (!ready[0] && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity(a -> ready[0] = ((TerminalActivity) a).terminalText().contains("PINCH_READY")); SystemClock.sleep(50);
        }
        assertTrue(ready[0]); float[] before = {0}, after = {0};
        scenario.onActivity(a -> before[0] = ((TerminalActivity)a).terminalView().mRenderer.getFontWidth());
        find(By.desc("终端输入与输出")).pinchOpen(.65f, 1200);
        scenario.onActivity(a -> after[0] = ((TerminalActivity)a).terminalView().mRenderer.getFontWidth());
        assertTrue("Pinch must increase text size", after[0] > before[0]);
        capture("terminal-pinch-large");
        find(By.desc("终端输入与输出")).pinchClose(.65f, 1200);
        scenario.onActivity(a -> assertTrue(((TerminalActivity)a).terminalView().mRenderer.getFontWidth() < after[0]));
        capture("terminal-pinch-small");
        scenario.onActivity(a -> {
            TerminalActivity t = (TerminalActivity)a;
            t.terminalView().onCreateInputConnection(new android.view.inputmethod.EditorInfo()).commitText("OK\n", 1);
        });
        deadline = SystemClock.uptimeMillis() + 10000; ready[0] = false;
        while (!ready[0] && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity(a -> ready[0] = ((TerminalActivity) a).terminalText().contains("PINCH_INPUT_OK")); SystemClock.sleep(50);
        }
        assertTrue("Pinch must preserve live input", ready[0]);
    }
}
