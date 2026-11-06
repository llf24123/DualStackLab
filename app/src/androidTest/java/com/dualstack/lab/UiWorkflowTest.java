package com.dualstack.lab;

import android.content.*;
import android.graphics.Point;
import android.net.Uri;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.view.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import io.github.rosemoe.sora.widget.CodeEditor;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class UiWorkflowTest {
    private Context context;
    private UiDevice device;
    private File work, left, right;
    private ActivityScenario<?> scenario;

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.executeShellCommand("cmd appops set " + context.getPackageName() + " MANAGE_EXTERNAL_STORAGE allow");
        File shared = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "DualStackLab-UI-Tests");
        assertTrue(shared.isDirectory() || shared.mkdirs());
        work = Files.createTempDirectory(shared.toPath(), "ui-review-").toFile();
        left = new File(work, "left"); right = new File(work, "right");
        assertTrue(left.mkdir()); assertTrue(right.mkdir());
        context.getSharedPreferences("MainActivity", 0).edit()
                .putString("left", left.getAbsolutePath()).putString("right", right.getAbsolutePath()).commit();
        context.getSharedPreferences("script-execution", 0).edit().clear().commit();
    }
    @After public void cleanup() throws Exception {
        if (scenario != null) scenario.close();
        new FileStore(context).delete(FileStore.local(work));
        context.getSharedPreferences("MainActivity", 0).edit().clear().commit();
        device.executeShellCommand("cmd appops set " + context.getPackageName() + " MANAGE_EXTERNAL_STORAGE allow");
    }

    @Test public void mainArchivePanesEditImportExtractAndRestore() throws Exception {
        File zip = new File(left, "main.zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("folder/note.txt")); out.write("archive original".getBytes(StandardCharsets.UTF_8)); out.closeEntry();
        }
        writeText(new File(right, "import.txt"), "import payload");
        startMain(); pane(true).findObject(By.text("main.zip")).click();
        await(() -> pane(true).hasObject(By.text("folder"))); assertTrue(pane(false).hasObject(By.text("import.txt")));
        assertTrue(device.hasObject(By.desc("更多操作")));
        pane(false).findObject(By.desc("选择 import.txt")).click(); clickDescription("复制");
        await(() -> pane(true).hasObject(By.text("import.txt")));
        assertEquals("import payload", new String(ArchiveOps.bytes(zip, "import.txt", 100), StandardCharsets.UTF_8));
        pane(true).findObject(By.text("folder")).click(); find(By.text("note.txt")).click(); find(By.textContains("UTF-8"));
        setForegroundEditorText("edited in main pane"); clickDescription("保存");
        await(() -> {
            try { return new String(ArchiveOps.bytes(zip, "folder/note.txt", 100), StandardCharsets.UTF_8).equals("edited in main pane"); }
            catch (IOException e) { return false; }
        });
        clickDescription("返回"); await(() -> pane(true).hasObject(By.text("note.txt")));
        pane(true).findObject(By.text("..")).click(); await(() -> pane(true).hasObject(By.text("folder")));
        pane(true).findObject(By.desc("选择 folder")).click(); clickDescription("复制");
        await(() -> {
            try { return readText(new File(right, "folder/note.txt")).equals("edited in main pane") && device.hasObject(By.desc("新建")); }
            catch (IOException e) { return false; }
        });
        assertEquals("edited in main pane", readText(new File(right, "folder/note.txt")));
        scenario.recreate(); await(() -> pane(true).hasObject(By.text("folder")));
        assertTrue(pane(false).hasObject(By.text("import.txt")));
        assertTrue(context.getSharedPreferences("MainActivity", 0).getString("left", "").startsWith("zip:"));
        pane(true).findObject(By.text("..")).click(); await(() -> pane(true).hasObject(By.text("main.zip")));
    }

    @Test public void rarOpensInMainPaneAndCanExtractToOtherPane() throws Exception {
        File rar = new File(left, "sample.rar");
        try (InputStream in = InstrumentationRegistry.getInstrumentation().getContext().getAssets()
                .open("rar-fixtures/rar4-ext_time.rar"); OutputStream out = new FileOutputStream(rar)) {
            FileStore.pump(in, out);
        }
        startMain(); pane(true).findObject(By.text("sample.rar")).click();
        await(() -> pane(true).hasObject(By.text("files")));
        pane(true).findObject(By.text("files")).click(); pane(true).findObject(By.text("test")).click();
        // RAR entries open read-only in the editor; the text may still be loading right after the title appears.
        pane(true).findObject(By.text("short-text.txt")).click(); find(By.textContains("UTF-8"));
        // The page is shown before its text arrives, so compare through a wait rather than once.
        await(() -> foregroundEditorEditable() && foregroundEditorText().contains("Short text for example"));
        clickDescription("返回");
        await(() -> pane(true).hasObject(By.text("short-text.txt"))); pane(true).findObject(By.text("..")).click();
        pane(true).findObject(By.text("..")).click(); pane(true).findObject(By.text("..")).click();
        await(() -> pane(true).hasObject(By.text("sample.rar")));
        pane(true).findObject(By.text("sample.rar")).longClick(); clickDescription("文件操作 解压");
        find(By.res("android:id/button1")).click();
        File extracted = new File(right, "sample");
        await(() -> new File(extracted, "files/test/short-text.txt").isFile() || device.hasObject(By.text("操作失败")));
        if (!new File(extracted, "files/test/short-text.txt").isFile()) {
            File failure = new File(Environment.getExternalStorageDirectory(), "Documents/DualStackReview-failures");
            failure.mkdirs(); device.dumpWindowHierarchy(new File(failure, "rar-extract-failure.xml"));
            device.takeScreenshot(new File(failure, "rar-extract-failure.png"));
            fail("RAR extract did not create output; error dialog shown: " + device.hasObject(By.text("操作失败")));
        }
        // Just-written rows may still be flushing to the media provider when the dialog closes.
        await(() -> {
            try { return readText(new File(extracted, "files/test/short-text.txt")).equals("Short text for example"); }
            catch (IOException missing) { return false; }
        });
    }

    @Test public void reverseSelectionAndDirectorySizeAreAvailableFromManagerActions() throws Exception {
        writeText(new File(left, "one.txt"), "123");
        writeText(new File(left, "two.txt"), "4567");
        File folder = new File(left, "folder"); assertTrue(folder.mkdir());
        writeText(new File(folder, "inside.txt"), "nested");
        startMain();
        pane(true).findObject(By.desc("选择 one.txt")).click();
        clickDescription("更多操作"); clickText("反选当前列表");
        find(By.textContains("已选择 2 项"));
        clickText("取消");
        // A long press already opens the operations menu with the row selected.
        pane(true).findObject(By.text("folder")).longClick();
        find(By.desc("文件操作 压缩"));
        clickDescription("文件操作 工具"); clickText("统计大小");
        find(By.text("目录统计"));
        await(() -> device.hasObject(By.textContains("文件  1")) && device.hasObject(By.textContains("文件夹  1")));
    }

    @Test public void unknownOpenGridExcludedActionsAndExplicitScript() throws Exception {
        File source = new File(left, "task.unknown"); writeText(source, "printf UNKNOWN_UI_SCRIPT_OK\n");
        startMain(); pane(true).findObject(By.text("task.unknown")).longClick(); clickDescription("文件操作 打开方式…");
        find(By.text("打开方式…")); find(By.desc("打开方式 编辑文本")); find(By.desc("打开方式 Linux 脚本"));
        for (String excluded : List.of("查看字体", "Dex功能", "十六进制", "导入签名", "点九编辑", "插件信息", "Arsc功能", "Xml功能"))
            assertFalse(device.hasObject(By.text(excluded)));
        clickDescription("打开方式 Linux 脚本"); clickText("执行");
        awaitTerminal("UNKNOWN_UI_SCRIPT_OK"); awaitTerminal("exit 0");
        assertEquals("printf UNKNOWN_UI_SCRIPT_OK\n", readText(source)); terminalInput("\n");
        find(By.desc("左窗口文件列表"));
        pane(true).findObject(By.text("task.unknown")).longClick(); clickDescription("文件操作 打开方式…"); clickDescription("打开方式 编辑文本");
        find(By.textContains("UTF-8")); assertTrue(foregroundEditorText().contains("UNKNOWN_UI_SCRIPT_OK"));
    }

    @Test public void apkBackupInfoNormalInstallAndCopy() throws Exception {
        device.executeShellCommand("pm uninstall com.dualstack.installfixture");
        device.executeShellCommand("cmd appops set " + context.getPackageName() + " REQUEST_INSTALL_PACKAGES allow");
        File apk = fixtureApk("fixture.apk.1");
        try {
            startMain(); pane(true).findObject(By.text(apk.getName())).click();
            find(By.text("com.dualstack.installfixture")); find(By.text("未安装")); find(By.textContains("V2"));
            find(By.text("com.dualstack.installfixture")).longClick();
            java.util.concurrent.atomic.AtomicReference<String> copied = new java.util.concurrent.atomic.AtomicReference<>();
            scenario.onActivity(activity -> {
                ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard.hasPrimaryClip()) copied.set(clipboard.getPrimaryClip().getItemAt(0).coerceToText(activity).toString());
            });
            assertEquals("com.dualstack.installfixture", copied.get());
            clickText("安装");
            boolean installed = false;
            long deadline = android.os.SystemClock.uptimeMillis() + 30000;
            while (!installed && android.os.SystemClock.uptimeMillis() < deadline) {
                try { context.getPackageManager().getPackageInfo("com.dualstack.installfixture", 0); installed = true; }
                catch (android.content.pm.PackageManager.NameNotFoundException ignored) { }
                if (!installed) Thread.sleep(200);
            }
            assertTrue("Silent install did not install our fixture", installed);
        } finally {
            device.executeShellCommand("pm uninstall com.dualstack.installfixture");
        }
    }

    @Test public void apkInfoViewUsesMainArchiveAndHandlesRootAvailability() throws Exception {
        File apk = fixtureApk("fixture.apk"); startMain(); pane(true).findObject(By.text(apk.getName())).click();
        find(By.text("com.dualstack.installfixture")); clickText("查看");
        await(() -> pane(true).hasObject(By.text("AndroidManifest.xml")));
        clickText("AndroidManifest.xml"); findLong(By.textContains("UTF-8"));
        assertTrue(foregroundEditorText().contains("com.dualstack.installfixture")); clickDescription("返回");
        pane(true).findObject(By.text("..")).click(); await(() -> pane(true).hasObject(By.text(apk.getName())));
        pane(true).findObject(By.text(apk.getName())).click();
        boolean rootAvailable;
        try { rootAvailable = FileStore.shell("id -u", true).trim().equals("0"); }
        catch (IOException e) { rootAvailable = false; }
        device.executeShellCommand("pm uninstall com.dualstack.installfixture");
        try {
            find(By.text("com.dualstack.installfixture")); clickText("安装");
            if (rootAvailable) {
                await(() -> {
                    try { context.getPackageManager().getPackageInfo("com.dualstack.installfixture", 0); return true; }
                    catch (android.content.pm.PackageManager.NameNotFoundException e) { return false; }
                });
                await(() -> !device.hasObject(By.text("静默安装")));
                assertFalse(device.hasObject(By.text("操作失败")));
            } else {
                find(By.text("操作失败")); find(By.textContains("ROOT"));
                assertFalse(device.hasObject(By.text("安装成功")));
            }
        } finally { device.executeShellCommand("pm uninstall com.dualstack.installfixture"); }
    }

    @Test public void imageHtmlSvgPreviewsOpenFromUnknownFileGrid() throws Exception {
        File image = new File(left, "picture.unknown");
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(48, 32, android.graphics.Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(android.graphics.Color.RED);
        try (OutputStream out = new FileOutputStream(image)) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); }
        bitmap.recycle();
        writeText(new File(left, "page.unknown"), "<html><body><h1>HTML_PREVIEW_OK</h1></body></html>");
        writeText(new File(left, "shape.svg"), "<svg xmlns='http://www.w3.org/2000/svg' width='100' height='100'><rect width='100' height='100' fill='green'/></svg>");
        startMain(); pane(true).findObject(By.text("picture.unknown")).longClick(); clickDescription("文件操作 打开方式…"); clickDescription("打开方式 查看图片");
        find(By.desc("图片预览")); find(By.textContains("48 × 32")); clickDescription("返回");
        pane(true).findObject(By.text("page.unknown")).longClick(); clickDescription("文件操作 打开方式…"); clickDescription("打开方式 HTML 功能");
        // Chromium replaces the WebView accessibility node after rendering.
        find(By.clazz("android.webkit.WebView")); find(By.text("HTML_PREVIEW_OK")); clickDescription("返回");
        pane(true).findObject(By.text("shape.svg")).click(); find(By.clazz("android.webkit.WebView"));
    }

    @Test public void navigationRestoresScrolledPositionAndPullRefreshInvalidatesCache() throws Exception {
        for (int i = 0; i < 90; i++) assertTrue(new File(left, String.format(Locale.ROOT, "folder%03d", i)).mkdir());
        startMain(); pane(true).scroll(Direction.DOWN, .9f); pane(true).scroll(Direction.DOWN, .9f);
        List<UiObject2> folders = pane(true).findObjects(By.textStartsWith("folder"));
        assertTrue(folders.size() > 1); String selected = folders.get(1).getText();
        assertFalse(selected.equals("folder000")); folders.get(1).click();
        await(() -> device.hasObject(By.desc("双栈 Lab · " + new File(left, selected))));
        clickDescription("后退"); find(By.text(selected)); assertFalse(pane(true).hasObject(By.text("folder000")));
        File fresh = new File(left, "aaa-new"); assertTrue(fresh.mkdir());
        scenario.onActivity(activity -> {
            ArrayDeque<View> views = new ArrayDeque<>(); views.add(activity.getWindow().getDecorView());
            while (!views.isEmpty()) {
                View view = views.remove();
                if (view instanceof android.widget.ListView list
                        && "左窗口文件列表".contentEquals(view.getContentDescription())) list.setSelectionFromTop(0, 0);
                if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) views.add(group.getChildAt(i));
            }
        });
        await(() -> pane(true).hasObject(By.text("..")));
        boolean[] ready = new boolean[2];
        scenario.onActivity(activity -> {
            ArrayDeque<View> views = new ArrayDeque<>(); views.add(activity.getWindow().getDecorView());
            while (!views.isEmpty()) {
                View view = views.remove();
                if (view instanceof android.widget.ListView list
                        && "左窗口文件列表".contentEquals(view.getContentDescription())) {
                    ready[0] = !list.canScrollVertically(-1);
                    ready[1] = ((View) list.getParent().getParent()).isEnabled();
                }
                if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) views.add(group.getChildAt(i));
            }
        });
        assertTrue("List has not reached its top", ready[0]);
        assertTrue("Pull refresh is disabled", ready[1]);
        android.graphics.Rect bounds = pane(true).getVisibleBounds();
        device.swipe(bounds.centerX(), bounds.top + 80, bounds.centerX(), bounds.bottom - 100, 100);
        find(By.text("aaa-new"));
    }

    private File fixtureApk(String name) throws Exception {
        File apk = new File(left, name);
        try (InputStream in = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("install-fixture.apk");
             OutputStream out = new FileOutputStream(apk)) { FileStore.pump(in, out); }
        return apk;
    }
    @Test public void audioAndVideoPreviewsActuallyDecodeAndPlay() throws Exception {
        for (String name : List.of("tone.wav", "video.mp4")) {
            File media = new File(left, name);
            try (InputStream in = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(name);
                 OutputStream out = new FileOutputStream(media)) { FileStore.pump(in, out); }
        }
        startMain();
        for (String name : List.of("tone.wav", "video.mp4")) {
            pane(true).findObject(By.text(name)).click();
            find(By.desc(name.endsWith("wav") ? "音频播放器" : "视频播放器"));
            await(() -> {
                java.util.concurrent.atomic.AtomicBoolean playing = new java.util.concurrent.atomic.AtomicBoolean();
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    for (android.app.Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) {
                        android.widget.VideoView player = findPlayer(activity.getWindow().getDecorView());
                        if (player != null) playing.set(player.isPlaying() && player.getCurrentPosition() > 100);
                    }
                });
                return playing.get();
            });
            clickDescription("返回"); await(() -> pane(true).hasObject(By.text(name)));
        }
    }
    private static android.widget.VideoView findPlayer(View view) {
        if (view instanceof android.widget.VideoView video) return video;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            android.widget.VideoView video = findPlayer(group.getChildAt(i)); if (video != null) return video;
        }
        return null;
    }

    @Test public void dualPaneNavigationTransferRenameDelete() throws Exception {
        writeText(new File(left, "note.txt"), "transfer payload");
        File child = new File(left, "child"); assertTrue(child.mkdir());
        startMain();
        pane(true).findObject(By.text("child")).click();
        await(() -> device.hasObject(By.desc("双栈 Lab · " + child)));
        assertNotNull(pane(false).findObject(By.text("..")));
        clickDescription("后退");
        await(() -> pane(true).hasObject(By.text("note.txt")));
        pane(true).findObject(By.desc("选择 note.txt")).click();
        clickDescription("复制");
        File copy = new File(right, "note.txt");
        await(() -> {
            try { return copy.isFile() && readText(copy).equals("transfer payload") && device.hasObject(By.desc("新建")); }
            catch (IOException e) { return false; }
        });
        await(() -> pane(true).hasObject(By.desc("选择 note.txt")));
        pane(true).findObject(By.desc("选择 note.txt")).click();
        clickDescription("移动");
        File moved = new File(right, "note (1).txt");
        await(() -> moved.isFile() && !new File(left, "note.txt").exists());
        await(() -> pane(false).hasObject(By.text("note.txt")));
        pane(false).findObject(By.desc("选择 note.txt")).click();
        clickDescription("操作"); clickText("重命名");
        find(By.clazz("android.widget.EditText")).setText("renamed.txt"); clickText("保存");
        File renamed = new File(right, "renamed.txt"); await(renamed::isFile);
        await(() -> pane(false).hasObject(By.desc("选择 renamed.txt")));
        pane(false).findObject(By.desc("选择 renamed.txt")).click();
        clickDescription("删除"); clickText("删除");
        await(() -> !renamed.exists());
        assertTrue(moved.isFile());
    }

    @Test public void dragAcrossPanesAndRegexFilterSearch() throws Exception {
        File source = new File(left, "drag.txt"); writeText(source, "drag payload");
        writeText(new File(left, "other.json"), "{}");
        startMain();
        UiObject2 icon = pane(true).findObject(By.desc("选择 drag.txt"));
        Point target = pane(false).getVisibleCenter();
        icon.drag(target, 600);
        find(By.text("传入 1 项")); clickText("复制");
        File copied = new File(right, "drag.txt");
        await(() -> {
            try { return copied.isFile() && readText(copied).equals("drag payload") && device.hasObject(By.desc("新建")); }
            catch (IOException e) { return false; }
        });
        assertEquals("drag payload", readText(copied));
        assertTrue(source.isFile());
        clickDescription("更多操作"); clickText("过滤");
        find(By.clazz("android.widget.EditText")).setText(".*\\.txt$");
        clickText("正则表达式"); clickText("应用");
        await(() -> pane(true).hasObject(By.text("drag.txt")) && !pane(true).hasObject(By.text("other.json")));
        clickDescription("更多操作"); clickText("搜索");
        find(By.desc("搜索关键词")).setText("drag\\.txt");
        if (!find(By.desc("正则表达式")).isSelected()) clickDescription("正则表达式");
        if (find(By.desc("搜索范围：文件名或内容")).getText().equals("文件内容")) clickDescription("搜索范围：文件名或内容");
        clickDescription("开始搜索");
        findLong(By.textContains("1 项"));
        findLong(By.text("drag.txt")); clickDescription("返回");
    }

    @Test public void editorRegexReplaceUndoRedoSaveAndReopen() throws Exception {
        File note = new File(left, "note.txt"); writeText(note, "one 12\none 34\n");
        Intent intent = new Intent(context, EditorActivity.class);
        FileStore.local(note).put(intent);
        ActivityScenario<EditorActivity> editorScenario = ActivityScenario.launch(intent); scenario = editorScenario;
        await(() -> device.hasObject(By.textContains("UTF-8")));
        clickDescription("查找与替换");
        find(By.desc("查找内容"));
        List<UiObject2> inputs = device.findObjects(By.clazz("android.widget.EditText"));
        inputs.get(0).setText("one \\d+"); inputs.get(1).setText("done");
        clickDescription("正则表达式"); device.waitForIdle();
        // The editor searches asynchronously; wait for its worker before replacing.
        Thread.sleep(350);
        clickText("全部替换"); clickDescription("关闭查找");
        await(() -> editorText(editorScenario).equals("done\ndone\n"));
        clickDescription("撤销 / 上一页");
        await(() -> editorText(editorScenario).equals("one 12\none 34\n"));
        clickDescription("重做 / 下一页");
        await(() -> editorText(editorScenario).equals("done\ndone\n"));
        clickDescription("保存");
        await(() -> {
            try { return readText(note).equals("done\ndone\n"); }
            catch (IOException e) { return false; }
        });
        editorScenario.close(); scenario = null;
        ActivityScenario<EditorActivity> reopened = ActivityScenario.launch(intent); scenario = reopened;
        await(() -> editorText(reopened).equals("done\ndone\n"));
    }

    @Test public void archiveEntryCrudAndEditorWriteBack() throws Exception {
        File zip = new File(left, "sample.zip");
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(zip))) {
            output.putNextEntry(new ZipEntry("note.txt")); output.write("inside".getBytes(StandardCharsets.UTF_8)); output.closeEntry();
        }
        Intent intent = new Intent(context, ArchiveActivity.class); FileStore.local(zip).put(intent);
        scenario = ActivityScenario.launch(intent);
        find(By.text("note.txt"));
        clickText("新建");
        find(By.clazz("android.widget.EditText")).setText("created.txt"); clickText("创建");
        await(() -> {
            try (ZipFile archive = new ZipFile(zip)) { return archive.getEntry("created.txt") != null; }
            catch (IOException e) { return false; }
        });
        find(By.text("created.txt")).longClick(); clickText("重命名");
        find(By.clazz("android.widget.EditText")).setText("renamed.txt"); clickText("保存");
        find(By.text("renamed.txt")).click();
        find(By.textContains("UTF-8"));
        find(By.clazz(CodeEditor.class.getName())).click();
        device.executeShellCommand("input text archive_edited");
        clickDescription("保存");
        await(() -> {
            try { return new String(ArchiveOps.bytes(zip, "renamed.txt", 100), StandardCharsets.UTF_8).equals("archive_edited"); }
            catch (IOException e) { return false; }
        });
        clickDescription("返回");
        // A save still finishing keeps the editor open briefly; its title also reads renamed.txt.
        await(() -> !device.hasObject(By.desc("编辑器菜单")));
        longClickText("renamed.txt"); clickText("删除"); clickText("删除");
        await(() -> {
            try (ZipFile archive = new ZipFile(zip)) { return archive.getEntry("renamed.txt") == null; }
            catch (IOException e) { return false; }
        });
        assertEquals("inside", new String(ArchiveOps.bytes(zip, "note.txt", 100), StandardCharsets.UTF_8));
    }

    @Test public void safGrantCrudAndPersistedDirectory() throws Exception {
        // The system storage provider and the picker are separate processes; a frozen or half-started one shows
        // "Can't use this folder" with the confirmation disabled, so both are restarted before the grant.
        device.executeShellCommand("am force-stop com.google.android.documentsui");
        device.executeShellCommand("am force-stop com.android.externalstorage");
        device.executeShellCommand("cmd activity unfreeze com.android.externalstorage");
        SystemClock.sleep(1200);
        File safFolder = new File(Environment.getExternalStorageDirectory(), "Documents/DualStack-SAF-Review-" + System.currentTimeMillis());
        assertTrue(safFolder.mkdirs());
        try {
            writeText(new File(safFolder, "saf-note.txt"), "provider payload");
            context.getSharedPreferences("MainActivity", 0).edit().putString("left", safFolder.getAbsolutePath()).commit();
            startMain(); find(By.text("saf-note.txt"));
            scenario.onActivity(a -> {
                try { java.lang.reflect.Method select = MainActivity.class.getDeclaredMethod("chooseDirectory"); select.setAccessible(true); select.invoke(a); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
            // API 36's DocumentsUI confirms with "USE THIS FOLDER" (and may follow with "ALLOW").
            UiObject2 confirm = device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?i)allow|use this folder"))), 10000);
            for (int attempt = 0; attempt < 3 && confirm != null && confirm.isEnabled(); attempt++) {
                confirm.click(); SystemClock.sleep(800);
                confirm = device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?i)allow"))), 1500);
            }
            assertNotNull("Storage picker stayed open", device.wait(Until.gone(
                    By.text(java.util.regex.Pattern.compile("(?i)use this folder"))), 8000));
            long grantDeadline = SystemClock.uptimeMillis() + 10000;
            while (context.getContentResolver().getPersistedUriPermissions().isEmpty()
                    && SystemClock.uptimeMillis() < grantDeadline) SystemClock.sleep(100);
            // The provider is a separate process on a rooted emulator; it can answer a moment after the grant lands.
            await(() -> pane(true).hasObject(By.text("saf-note.txt")));
            android.content.UriPermission permission = context.getContentResolver().getPersistedUriPermissions().stream()
                    .filter(p -> p.getUri().toString().contains(safFolder.getName())).findFirst().orElseThrow();
            Uri tree = permission.getUri();
            Uri document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            FileStore store = new FileStore(context);
            FileStore.Node parent = store.document(document);
            FileStore.Node made = store.create(parent, "made.txt", false);
            File payload = new File(work, "payload.txt"); writeText(payload, "saf write");
            store.write(made, payload);
            made = store.rename(made, "changed.txt");
            try (InputStream input = store.read(made)) {
                assertEquals("saf write", new String(FileStore.readBytes(input, 100), StandardCharsets.UTF_8));
            }
            FileStore.Node copy = store.copy(made, FileStore.local(right), false);
            assertEquals("saf write", readText(copy.file));
            store.delete(made);
            scenario.close(); scenario = null; startMain();
            await(() -> pane(true).hasObject(By.text("saf-note.txt")));
            assertTrue(context.getSharedPreferences("MainActivity", 0).getString("left", "").startsWith("content:"));
            context.getContentResolver().releasePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } finally { new FileStore(context).delete(FileStore.local(safFolder)); }
    }

    @Test public void largeFileEditSaveAndNavigatePages() throws Exception {
        File large = new File(left, "large.txt");
        byte[] block = "line_\u4e2d\u6587\n".repeat(4096).getBytes(StandardCharsets.UTF_8);
        try (OutputStream output = new BufferedOutputStream(new FileOutputStream(large))) {
            while (large.length() <= EditorActivity.LIMIT) { output.write(block); output.flush(); }
            output.write("tail_end\n".getBytes(StandardCharsets.UTF_8));
        }
        long originalLength = large.length();
        Intent intent = new Intent(context, EditorActivity.class); FileStore.local(large).put(intent);
        scenario = ActivityScenario.launch(intent);
        find(By.textContains("分段"));
        String first = foregroundEditorText();
        assertTrue(first.startsWith("line_\u4e2d\u6587"));
        assertFalse(first.contains("\ufffd"));
        setForegroundEditorText("large_edit\n" + first);
        clickDescription("保存");
        await(() -> large.length() == originalLength + 11 && !device.hasObject(By.textContains("未保存")));
        clickDescription("重做 / 下一页");
        await(() -> !foregroundEditorText().startsWith("large_edit\n"));
        assertFalse(foregroundEditorText().contains("\ufffd"));
        clickDescription("撤销 / 上一页");
        await(() -> foregroundEditorText().startsWith("large_edit\n"));
        try (RandomAccessFile input = new RandomAccessFile(large, "r")) {
            input.seek(input.length() - 9);
            byte[] tail = new byte[9]; input.readFully(tail);
            assertEquals("tail_end\n", new String(tail, StandardCharsets.UTF_8));
        }
        scenario.close(); scenario = ActivityScenario.launch(intent);
        find(By.textContains("分段"));
        assertTrue(foregroundEditorText().startsWith("large_edit\n"));
    }

    @Test public void shellAndInstalledApkExtractionScreens() throws Exception {
        startMain();
        clickDescription("更多操作"); clickText("打开终端");
        device.findObjects(By.clazz("android.widget.EditText")).get(1).setText("printf ui_shell_ok"); clickDescription("执行");
        find(By.text("ui_shell_ok")); find(By.text("exit 0")); clickDescription("返回");
        clickDescription("位置与工具"); clickText("已安装应用");
        clickDescription("搜索应用");
        find(By.clazz("android.widget.EditText")).setText(context.getPackageName());
        find(By.desc("应用 " + context.getPackageName())).click();
        find(By.desc("应用详情 包名")); clickText("提取安装包");
        File folder = new File(left, context.getPackageName());
        await(() -> {
            File[] files = folder.listFiles();
            if (files == null || files.length == 0) return false;
            try { return ApkTools.sha256(files[0]).equals(ApkTools.sha256(new File(context.getApplicationInfo().sourceDir))); }
            catch (Exception e) { return false; }
        });
        await(() -> pane(true).hasObject(By.text(context.getPackageName())));
    }

    @Test public void apkManifestArscAndDexEditorScreens() throws Exception {
        File smali = new File(work, "smali"); assertTrue(smali.mkdir());
        writeText(new File(smali, "UiSample.smali"), ".class public Lfixture/UiSample;\n"
                + ".super Ljava/lang/Object;\n.method public static value()I\n"
                + ".registers 1\nconst/4 v0, 0x1\nreturn v0\n.end method\n");
        File dex = new File(work, "classes.dex"); ApkTools.assemble(smali, dex);
        File apk = new File(left, "ui.apk");
        try (ZipFile original = new ZipFile(context.getApplicationInfo().sourceDir);
             ZipOutputStream output = new ZipOutputStream(new FileOutputStream(apk))) {
            for (String entry : List.of("AndroidManifest.xml", "resources.arsc")) {
                output.putNextEntry(new ZipEntry(entry));
                try (InputStream input = original.getInputStream(original.getEntry(entry))) { FileStore.pump(input, output); }
                output.closeEntry();
            }
            output.putNextEntry(new ZipEntry("classes.dex")); output.write(Files.readAllBytes(dex.toPath())); output.closeEntry();
        }
        Intent intent = new Intent(context, ArchiveActivity.class); FileStore.local(apk).put(intent);
        scenario = ActivityScenario.launch(intent);
        find(By.text("AndroidManifest.xml")).click(); find(By.textContains("UTF-8"));
        String xml = foregroundEditorText();
        assertTrue(xml.contains("<manifest"));
        String version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        assertTrue(xml.contains(version));
        setForegroundEditorText(xml.replace(version, version + "-reviewed"));
        clickDescription("保存");
        await(() -> {
            try { return ApkTools.xmlText(apk, "AndroidManifest.xml").contains(version + "-reviewed"); }
            catch (Exception e) { return false; }
        });
        clickDescription("返回");
        find(By.text("resources.arsc")).click(); find(By.textContains("UTF-8"));
        assertTrue(foregroundEditorText().contains("\"packages\""));
        clickDescription("保存");
        await(() -> !device.hasObject(By.textContains("保存中")));
        clickDescription("返回");
        find(By.text("classes.dex")).click(); find(By.text("Lfixture/UiSample;")).click();
        find(By.textContains("UTF-8"));
        String text = foregroundEditorText();
        assertTrue(text.contains("0x1"));
        setForegroundEditorText(text.replace("0x1", "0x2")); clickDescription("保存");
        await(() -> {
            try {
                ArchiveOps.extractOne(apk, "classes.dex", dex);
                for (org.jf.dexlib2.iface.ClassDef def : org.jf.dexlib2.DexFileFactory.loadDexFile(dex, org.jf.dexlib2.Opcodes.getDefault()).getClasses())
                    for (org.jf.dexlib2.iface.Method method : def.getMethods())
                        for (org.jf.dexlib2.iface.instruction.Instruction ins : method.getImplementation().getInstructions())
                            if (ins instanceof org.jf.dexlib2.iface.instruction.NarrowLiteralInstruction literal)
                                return literal.getNarrowLiteral() == 2;
                return false;
            } catch (Exception e) { return false; }
        });
        clickDescription("返回"); clickText("关闭");
    }

    @Test public void installedAppDetailsCopyPathsTabsAndScriptPipeUi() throws Exception {
        startMain(); clickDescription("位置与工具"); clickText("已安装应用");
        find(By.desc("用户应用标签")); clickDescription("系统应用标签");
        await(() -> !find(By.desc("已安装应用列表")).getChildren().isEmpty());
        clickDescription("用户应用标签"); clickDescription("搜索应用");
        find(By.desc("应用搜索输入")).setText(context.getPackageName());
        find(By.desc("应用 " + context.getPackageName())).click();
        assertEquals(context.getPackageName(), find(By.desc("应用详情 包名")).getText());
        find(By.desc("应用详情 UID")); find(By.desc("应用详情 最低系统"));
        await(() -> find(By.desc("应用详情 签名状态")).getText().contains("V2"));
        find(By.desc("应用详情 包名")).longClick();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            assertEquals(context.getPackageName(), clipboard.getPrimaryClip().getItemAt(0).getText().toString());
        });
        captureReview("installed-details-050.png");
        clickDescription("应用详情 数据目录 1");
        await(() -> device.hasObject(By.desc("双栈 Lab · " + context.getApplicationInfo().dataDir)));
        clickDescription("位置与工具"); clickText("已安装应用");
        find(By.desc("应用 " + context.getPackageName())); captureReview("installed-list-050.png");
        find(By.desc("应用 " + context.getPackageName())).click(); clickDescription("应用详情 APK 路径");
        String apkDirectory = new File(context.getApplicationInfo().sourceDir).getParent();
        await(() -> device.hasObject(By.desc("双栈 Lab · " + apkDirectory)));
        find(By.desc("左窗口文件列表"));
        scenario.close(); scenario = null;
        context.getSharedPreferences("MainActivity", 0).edit().clear().putString("left", left.getAbsolutePath()).putString("right", right.getAbsolutePath()).commit();
        File script = new File(left, "pipeline.sh"); writeText(script, "printf PIPE_UI_OK; pwd\n"); startMain();
        pane(true).findObject(By.text("pipeline.sh")).click(); find(By.text("请选择要执行的操作"));
        clickText("设置"); clickText("管道执行"); clickText("保存"); find(By.text("管道执行"));
        captureReview("script-dialog-050.png");
        clickText("执行"); awaitTerminal("PIPE_UI_OK"); awaitTerminal("exit 0");
        assertTrue(foregroundTerminalText().contains(left.getAbsolutePath()));
        terminalInput("\n"); find(By.desc("左窗口文件列表"));
        pane(true).findObject(By.text("pipeline.sh")).click(); find(By.text("管道执行")); clickText("编辑");
        await(this::foregroundEditorEditable); assertTrue(foregroundEditorText().contains("PIPE_UI_OK"));
    }
    private void captureReview(String name) throws Exception {
        File directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "DualStackLabReview-050");
        Files.createDirectories(directory.toPath()); assertTrue(device.takeScreenshot(new File(directory, name)));
    }

    @Test public void bothPanesRefreshAfterExternalFileChanges() throws Exception {
        startMain();
        File a = new File(left, "fresh-left.txt"), b = new File(right, "fresh-right.txt");
        writeText(a, "left"); writeText(b, "right");
        assertFalse(pane(true).hasObject(By.text(a.getName())));
        refreshPane(true);
        await(() -> pane(true).hasObject(By.text(a.getName())));
        assertFalse("left refresh affected the other pane", pane(false).hasObject(By.text(b.getName())));
        refreshPane(false);
        await(() -> pane(false).hasObject(By.text(b.getName())));
        assertTrue(a.delete()); refreshPane(true);
        await(() -> !pane(true).hasObject(By.text(a.getName())));
        assertTrue(pane(false).hasObject(By.text(b.getName())));
    }

    @Test public void topPathLongPressCopiesFullActivePanePath() throws Exception {
        File deep = new File(left, "long-directory-name-that-is-truncated-in-the-header");
        assertTrue(deep.mkdir());
        context.getSharedPreferences("MainActivity", 0).edit().putString("left", deep.getAbsolutePath()).commit();
        startMain();
        for (String path : List.of(deep.getAbsolutePath(), right.getAbsolutePath())) {
            if (path.equals(right.getAbsolutePath())) pane(false).click();
            find(By.desc("双栈 Lab · " + path)).longClick();
            java.util.concurrent.atomic.AtomicReference<String> copied = new java.util.concurrent.atomic.AtomicReference<>();
            scenario.onActivity(activity -> {
                ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard.hasPrimaryClip()) copied.set(clipboard.getPrimaryClip().getItemAt(0).coerceToText(activity).toString());
            });
            assertEquals(path, copied.get());
            assertFalse(device.hasObject(By.text("路径跳转")));
        }
    }

    @Test public void androidDataNavigationAutomaticallyUsesRootAndSurvivesRecreation() throws Exception {
        try { Assume.assumeTrue(FileStore.shell("id -u", true).trim().equals("0")); }
        catch (IOException e) { Assume.assumeNoException(e); }
        Intent intent = new Intent(context, MainActivity.class);
        intent.putExtra("navigatePath", "/storage/emulated/0/Android");
        scenario = ActivityScenario.launch(intent);
        pane(true).findObject(By.text("data")).click();
        await(() -> pane(true).hasObject(By.text(context.getPackageName())));
        find(By.desc("双栈 Lab · /storage/emulated/0/Android/data")).click();
        assertEquals("/storage/emulated/0/Android/data", jumpPath()); clickText("取消");
        scenario.recreate();
        await(() -> pane(true).hasObject(By.text(context.getPackageName())));
        pane(true).findObject(By.text(context.getPackageName())).click();
        await(() -> pane(true).hasObject(By.text("files")));
        find(By.desc("双栈 Lab · /storage/emulated/0/Android/data/" + context.getPackageName())).click();
        assertEquals("/storage/emulated/0/Android/data/" + context.getPackageName(), jumpPath()); clickText("取消");
        captureReview("android-data-065.png");
    }

    @Test public void privateAppDataNavigationEditsFilesAndRestoresRootAfterRecreation() throws Exception {
        try { Assume.assumeTrue(FileStore.shell("id -u", true).trim().equals("0")); }
        catch (IOException e) { Assume.assumeNoException(e); }
        FileStore store = new FileStore(context);
        File fixture = new File("/data/user/0/com.dualstack.privatefixture.ui." + UUID.randomUUID());
        String path = fixture.getAbsolutePath();
        FileStore.shell("mkdir " + FileStore.quote(path) + " && chmod 700 " + FileStore.quote(path)
                + " && printf '[private]\\nvalue=1\\n' > " + FileStore.quote(path + "/settings.ini"), true);
        try {
            int expected = store.list(FileStore.directory(new File("/data/user/0"), false)).size();
            Intent intent = new Intent(context, MainActivity.class).putExtra("navigatePath", "/data/user/0");
            scenario = ActivityScenario.launch(intent);
            find(By.text("目录 " + expected + "  文件 0"));
            find(By.desc("双栈 Lab · /data/user/0")).click();
            assertEquals("/data/user/0", jumpPath()); clickText("取消");
            clickDescription("更多操作"); clickText("过滤");
            find(By.clazz("android.widget.EditText")).setText(fixture.getName()); clickText("应用");
            pane(true).findObject(By.text(fixture.getName())).click();
            await(() -> pane(true).hasObject(By.text("settings.ini")));
            pane(true).findObject(By.text("settings.ini")).click();
            await(this::foregroundEditorEditable);
            assertEquals("[private]\nvalue=1\n", foregroundEditorText());
            setForegroundEditorText("[private]\nvalue=2\n"); clickDescription("保存");
            await(() -> {
                try (InputStream input = store.read(FileStore.local(new File(fixture, "settings.ini")))) {
                    return new String(FileStore.readBytes(input, 1024), StandardCharsets.UTF_8).equals("[private]\nvalue=2\n");
                } catch (IOException e) { return false; }
            });
            clickDescription("返回"); pane(true); device.pressBack();
            find(By.desc("双栈 Lab · /data/user/0"));
            scenario.recreate();
            find(By.text("目录 " + expected + "  文件 0"));
            find(By.desc("双栈 Lab · /data/user/0")).click();
            assertEquals("/data/user/0", jumpPath()); clickText("取消");
            captureReview("private-data-067.png");
        } finally {
            if (scenario != null) { scenario.close(); scenario = null; }
            store.delete(FileStore.directory(fixture, true));
        }
    }

    @Test public void iniAndShellEditorAutoDetectAndSaveWithoutChangingOtherBytes() throws Exception {
        Map<String, byte[]> samples = new LinkedHashMap<>();
        samples.put("short.ini", "[x]".getBytes("UTF-16LE"));
        samples.put("legacy.sh", new byte[]{'#', ' ', (byte) 0x81, (byte) 0xff, '\n', 'e', 'x', 'i', 't', ' ', '0', '\n'});
        for (Map.Entry<String, byte[]> sample : samples.entrySet()) {
            File file = new File(left, sample.getKey());
            Files.write(file.toPath(), sample.getValue());
            Intent intent = new Intent(context, EditorActivity.class); FileStore.local(file).put(intent);
            scenario = ActivityScenario.launch(intent);
            await(this::foregroundEditorEditable);
            assertFalse(device.hasObject(By.text("操作失败")));
            String original = foregroundEditorText();
            clickDescription("保存");
            await(this::foregroundEditorEditable);
            assertArrayEquals(sample.getValue(), Files.readAllBytes(file.toPath()));
            setForegroundEditorText(original + "\n# edited"); clickDescription("保存");
            await(() -> file.length() > sample.getValue().length);
            byte[] edited = Files.readAllBytes(file.toPath());
            assertArrayEquals(sample.getValue(), Arrays.copyOf(edited, sample.getValue().length));
            scenario.close(); scenario = null;
        }
    }

    @Test public void editorOpensNullPaddedIniAndEmbeddedScriptPayloadWithoutLosingBytes() throws Exception {
        Map<String, byte[]> samples = new LinkedHashMap<>();
        samples.put("AndroidPak1.ini", ("[AndroidPak]\nEnabled=1\n\0\0\0").getBytes(StandardCharsets.UTF_8));
        samples.put("padded-unicode.ini", ("[配置]\n名称=中文\n\0\0").getBytes("UTF-16LE"));
        samples.put("payload.sh", new byte[]{'#', '!', '/', 'b', 'i', 'n', '/', 's', 'h', '\n',
                'e', 'x', 'i', 't', ' ', '0', '\n', 0, 1, (byte) 0x81, (byte) 0xff, 2});
        samples.put("damaged-mark.ini", new byte[]{(byte) 0xff, (byte) 0xfe, '[', 'x', ']', 0, 1});
        for (Map.Entry<String, byte[]> sample : samples.entrySet()) {
            File file = new File(left, sample.getKey()); Files.write(file.toPath(), sample.getValue());
            Intent intent = new Intent(context, EditorActivity.class); FileStore.local(file).put(intent);
            scenario = ActivityScenario.launch(intent);
            await(this::foregroundEditorEditable);
            assertFalse(device.hasObject(By.text("操作失败")));
            String original = foregroundEditorText();
            assertTrue("No text loaded: " + sample.getKey(), original.length() > 0);
            clickDescription("保存"); await(this::foregroundEditorEditable);
            assertArrayEquals(sample.getKey(), sample.getValue(), Files.readAllBytes(file.toPath()));
            setForegroundEditorText(original + "\n# edited"); clickDescription("保存");
            await(() -> file.length() > sample.getValue().length);
            assertArrayEquals(sample.getKey(), sample.getValue(),
                    Arrays.copyOf(Files.readAllBytes(file.toPath()), sample.getValue().length));
            scenario.close(); scenario = null;
        }
    }

    @Test public void editorRoundTripsProvidedBinarySampleAndKeepsUneditedBytes() throws Exception {
        String suppliedPath = InstrumentationRegistry.getArguments().getString("samplePath");
        byte[] original;
        if (suppliedPath != null) original = Files.readAllBytes(new File(suppliedPath).toPath());
        else {
            original = new byte[256];
            for (int i = 0; i < original.length; i++) original[i] = (byte) i;
        }
        String digest = sampleDigest(original);
        String expectedHash = InstrumentationRegistry.getArguments().getString("sampleSha256");
        if (expectedHash != null) assertEquals(expectedHash.toLowerCase(Locale.ROOT), digest);
        TextCodec.Decoded decoded = TextCodec.decodeForEditor(original, null, false);
        assertArrayEquals(original, TextCodec.encode(decoded.text(), decoded.charset(), decoded.bom()));
        File sample = new File(left, suppliedPath == null ? "binary-sample.ini" : new File(suppliedPath).getName());
        Files.write(sample.toPath(), original);
        startMain(); pane(true).findObject(By.text(sample.getName())).click();
        await(this::foregroundEditorEditable);
        assertFalse(device.hasObject(By.text("操作失败")));
        String text = foregroundEditorText();
        assertEquals(decoded.text(), text);
        long beforeSave = sample.lastModified();
        clickDescription("保存");
        await(() -> foregroundEditorEditable() && sample.lastModified() != beforeSave);
        assertArrayEquals("Unchanged save modified the sample", original, Files.readAllBytes(sample.toPath()));
        captureReview("provided-sample-editor-066.png");
        String editedText = text + "\n# roundtrip verified";
        setForegroundEditorText(editedText); clickDescription("保存");
        await(() -> sample.length() > original.length && foregroundEditorEditable());
        byte[] edited = Files.readAllBytes(sample.toPath());
        assertArrayEquals("Original bytes changed during append", original, Arrays.copyOf(edited, original.length));
        assertArrayEquals(TextCodec.encode(editedText, decoded.charset(), decoded.bom()), edited);
        clickDescription("返回"); pane(true);
        pane(true).findObject(By.text(sample.getName())).click();
        await(this::foregroundEditorEditable);
        assertEquals("Reopening changed the editor content", editedText, foregroundEditorText());
        if (suppliedPath != null)
            assertArrayEquals("Supplied source was modified", original, Files.readAllBytes(new File(suppliedPath).toPath()));
        int nulls = 0;
        for (byte value : original) if (value == 0) nulls++;
        org.json.JSONObject result = new org.json.JSONObject()
                .put("source", suppliedPath == null ? "generated byte-range fixture" : suppliedPath)
                .put("length", original.length).put("nullBytes", nulls).put("sha256", digest)
                .put("charset", decoded.charset().name()).put("uncertain", decoded.uncertain())
                .put("unchangedSaveIdentical", true).put("appendPreservesOriginalBytes", true)
                .put("reopenedContentIdentical", true).put("sourceUnchanged", true);
        File evidence = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                "DualStackLabReview-050/provided-sample-066.json");
        Files.write(evidence.toPath(), result.toString(2).getBytes(StandardCharsets.UTF_8));
    }

    private static String sampleDigest(byte[] data) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder text = new StringBuilder();
        for (byte value : digest) text.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return text.toString();
    }

    @Test public void editorAutoDetectsLegacyEncodingAndWritesItBack() throws Exception {
        File note = new File(left, "big5.txt");
        String original = "繁體中文檔案編碼自動識別測試，內容必須完整保留。\n".repeat(30);
        Files.write(note.toPath(), original.getBytes("Big5"));
        Intent intent = new Intent(context, EditorActivity.class); FileStore.local(note).put(intent);
        ActivityScenario<EditorActivity> opened = ActivityScenario.launch(intent); scenario = opened;
        await(() -> editorText(opened).equals(original));
        setForegroundEditorText(original + "修改完成"); clickDescription("保存");
        await(() -> {
            try { return new String(Files.readAllBytes(note.toPath()), "Big5").equals(original + "修改完成"); }
            catch (IOException e) { return false; }
        });
    }

    @Test public void archivePullRefreshReflectsExternalEntryChange() throws Exception {
        File zip = new File(left, "refresh.zip"), payload = new File(work, "payload.txt");
        writeText(payload, "fresh entry");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("original.txt")); out.write(new byte[]{65}); out.closeEntry();
        }
        Intent intent = new Intent(context, ArchiveActivity.class); FileStore.local(zip).put(intent);
        scenario = ActivityScenario.launch(intent); find(By.text("original.txt"));
        ArchiveOps.rewrite(zip, null, null, Map.of("fresh.txt", payload));
        assertFalse(device.hasObject(By.text("fresh.txt")));
        android.graphics.Rect bounds = find(By.clazz("android.widget.ListView")).getVisibleBounds();
        device.swipe(bounds.centerX(), bounds.top + 30, bounds.centerX(),
                Math.min(bounds.bottom - 30, bounds.top + 600), 35);
        find(By.text("fresh.txt")); assertTrue(device.hasObject(By.text("original.txt")));
    }

    @Test public void shellInteractiveInputScriptAndStopUi() throws Exception {
        startMain(); clickDescription("更多操作"); clickText("打开终端");
        device.findObjects(By.clazz("android.widget.EditText")).get(1)
                .setText("printf READY; read value; printf '\\ninput_%s' \"$value\"; exit 9");
        clickDescription("执行"); find(By.text("READY"));
        device.findObjects(By.clazz("android.widget.EditText")).get(2).setText("ok");
        clickDescription("发送输入"); find(By.textContains("input_ok")); find(By.text("exit 9"));
        device.findObjects(By.clazz("android.widget.EditText")).get(1).setText("printf waiting; sleep 60");
        clickDescription("执行"); find(By.text("waiting")); clickDescription("停止");
        find(By.text("已停止")); clickDescription("返回");
        File script = new File(left, "windows-script.sh");
        Files.write(script.toPath(), ("\ufeff#!/system/bin/sh\r\nprintf script_ok\r\npwd\r\n")
                .getBytes(StandardCharsets.UTF_8));
        refreshPane(true); await(() -> pane(true).hasObject(By.desc("选择 windows-script.sh")));
        pane(true).findObject(By.desc("选择 windows-script.sh")).click();
        clickDescription("操作"); clickDescription("文件操作 工具"); clickText("执行 .sh / ELF");
        find(By.text("请选择要执行的操作")); clickText("执行");
        awaitTerminal("script_ok"); awaitTerminal("exit 0");
        assertTrue(foregroundTerminalText().contains(left.getAbsolutePath()));
    }

    @Test public void elfFileExecutionStreamsInputAndStopsThroughFileMenu() throws Exception {
        File elf = new File(left, "native tool.elf");
        Files.copy(new File("/system/bin/sh").toPath(), elf.toPath());
        startMain(); pane(true).findObject(By.text("native tool.elf")).click();
        awaitTerminal("$");
        terminalInput("printf ELF_READY; read value; printf '\\nELF_%s' \"$value\"; exit 19\n");
        awaitTerminal("ELF_READY"); terminalInput("input_ok\n");
        awaitTerminal("ELF_input_ok"); awaitTerminal("exit 19");
        device.takeScreenshot(new File(context.getExternalFilesDir(null), "elf-file-execution.png"));
        clickDescription("终端菜单"); clickText("重新执行");
        // The old transcript already contains '$'; wait until the new session replaces it.
        await(() -> foregroundTerminalText().contains("$") && !foregroundTerminalText().contains("ELF_input_ok"));
        terminalInput("printf ELF_WAIT; sleep 60\n"); awaitTerminal("ELF_WAIT");
        clickDescription("终端菜单"); clickText("停止"); awaitTerminal("已停止");
        assertTrue(elf.isFile());
    }

    @Test public void bottomSwipeRecentBookmarksPersistAndNavigateActivePane() throws Exception {
        context.getSharedPreferences("directory-places", 0).edit().clear().commit();
        File child = new File(left, "bookmarked-child"); assertTrue(child.mkdir());
        try {
            startMain(); pane(true).findObject(By.text("bookmarked-child")).click();
            await(() -> device.hasObject(By.desc("双栈 Lab · " + child)));
            swipeBottom(); clickDescription("最近标签"); find(By.desc("最近与书签列表"));
            assertTrue(device.hasObject(By.text("bookmarked-child")));
            clickDescription("最近与书签菜单"); clickText("添加当前目录到书签");
            UiObject2 row = find(By.desc("目录快捷项 bookmarked-child")); row.longClick();
            clickText("重命名书签"); find(By.clazz("android.widget.EditText")).setText("My bookmark"); clickText("保存");
            find(By.text("My bookmark"));
            device.takeScreenshot(new File(work, "directory-sheet.png"));
            clickDescription("收起最近与书签"); pane(true).findObject(By.text("..")).click();
            await(() -> device.hasObject(By.desc("双栈 Lab · " + left)));
            pane(false).findObject(By.text("..")).click();
            await(() -> device.hasObject(By.desc("双栈 Lab · " + work)));
            swipeBottom(); clickDescription("书签标签"); clickText("My bookmark");
            await(() -> device.hasObject(By.desc("双栈 Lab · " + child)));
            assertTrue(pane(true).hasObject(By.text("bookmarked-child")));
            scenario.recreate(); find(By.desc("左窗口文件列表"));
            swipeBottom(); clickDescription("书签标签"); find(By.text("My bookmark"));
            find(By.desc("目录快捷项 My bookmark")).longClick(); clickText("删除书签");
            await(() -> !device.hasObject(By.text("My bookmark")));
            clickDescription("最近标签"); clickDescription("最近与书签菜单"); clickText("清空最近目录"); clickText("清空");
            find(By.text("暂无最近目录"));
            android.graphics.Rect sheet = find(By.text("暂无最近目录")).getVisibleBounds();
            device.swipe(sheet.centerX(), sheet.top + 12, sheet.centerX(), sheet.bottom - 10, 18);
            await(() -> !device.hasObject(By.desc("最近标签")));
        } finally { context.getSharedPreferences("directory-places", 0).edit().clear().commit(); }
    }

    @Test public void drawerPopupHiddenHomeAndSystemThemeWork() throws Exception {
        writeText(new File(left, ".hidden.txt"), "hidden");
        writeText(new File(left, "visible.txt"), "visible");
        try {
            startMain(); clickDescription("位置与工具");
            find(By.text("根目录")); find(By.text("内部存储")); find(By.text("已安装应用"));
            assertFalse(device.hasObject(By.text("选择授权目录（SAF）")));
            clickText("根目录"); await(() -> device.hasObject(By.desc("双栈 Lab · /")));
            await(() -> pane(true).hasObject(By.text("data")) && pane(true).hasObject(By.text("apex")));
            clickDescription("双栈 Lab · /");
            assertEquals("/", jumpPath());
            find(By.clazz("android.widget.EditText")).setText(left.getAbsolutePath());
            clickText("确定"); await(() -> pane(true).hasObject(By.text("visible.txt")));
            clickDescription("后退"); await(() -> device.hasObject(By.desc("双栈 Lab · /")));
            clickDescription("位置与工具"); clickText("内部存储");
            await(() -> device.hasObject(By.desc("双栈 Lab · " + Environment.getExternalStorageDirectory())));
            clickDescription("后退"); clickDescription("后退");
            await(() -> pane(true).hasObject(By.text(".hidden.txt")));
            clickDescription("更多操作");
            for (String title : new String[]{"刷新", "搜索", "全选", "过滤", "排序方式", "打开终端", "隐藏文件", "添加书签", "设为首页", "交换窗口", "设置", "退出"})
                find(By.text(title));
            clickText("隐藏文件"); clickText("不显示隐藏文件");
            await(() -> !pane(true).hasObject(By.text(".hidden.txt"))); assertTrue(pane(true).hasObject(By.text("visible.txt")));
            clickDescription("更多操作"); clickText("设为首页");
            String expectedHome = left.getAbsolutePath();
            await(() -> expectedHome.equals(context.getSharedPreferences("MainActivity", 0).getString("home-left", "")));
            pane(true).findObject(By.text("..")).click(); scenario.close(); scenario = null; startMain();
            await(() -> device.hasObject(By.desc("双栈 Lab · " + left)));
            assertFalse(pane(true).hasObject(By.text(".hidden.txt")));
            clickDescription("更多操作"); clickText("添加书签");
            await(() -> new DirectoryPlaces(context).entries(true).stream().anyMatch(e -> e.path().equals(left.getAbsolutePath())));
            clickDescription("位置与工具"); clickDescription("主题切换"); clickText("深色");
            await(() -> Ui.dark && device.hasObject(By.desc("左窗口文件列表")));
            clickDescription("位置与工具"); clickDescription("主题切换"); clickText("浅色");
            await(() -> !Ui.dark && device.hasObject(By.desc("左窗口文件列表")));
            clickDescription("位置与工具"); clickDescription("主题切换"); clickText("跟随系统");
            find(By.desc("左窗口文件列表"));
            device.executeShellCommand("cmd uimode night yes");
            await(() -> Ui.dark && device.hasObject(By.desc("左窗口文件列表")));
            device.executeShellCommand("cmd uimode night no");
            await(() -> !Ui.dark && device.hasObject(By.desc("左窗口文件列表")));
            pane(true).findObject(By.text("visible.txt")).click();
            await(() -> foregroundEditorEditable()); setForegroundEditorText("unsaved theme content");
            device.executeShellCommand("cmd uimode night yes");
            await(() -> Ui.dark && foregroundEditorText().equals("unsaved theme content"));
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                for (android.app.Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) {
                    CodeEditor editor = findEditor(activity.getWindow().getDecorView());
                    if (editor != null) assertEquals(Ui.SURFACE, editor.getColorScheme().getColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.WHOLE_BACKGROUND));
                }
            });
            device.executeShellCommand("cmd uimode night no");
            await(() -> !Ui.dark && foregroundEditorText().equals("unsaved theme content"));
            assertEquals("visible", readText(new File(left, "visible.txt")));
            clickDescription("返回"); clickText("放弃修改"); find(By.desc("左窗口文件列表"));
            clickDescription("更多操作"); clickText("打开终端");
            device.findObjects(By.clazz("android.widget.EditText")).get(1)
                    .setText("printf THEME_RUNNING; read value; printf \"\\nvalue_%s\" \"$value\"");
            clickDescription("执行"); find(By.text("THEME_RUNNING"));
            device.executeShellCommand("cmd uimode night yes"); await(() -> Ui.dark);
            device.findObjects(By.clazz("android.widget.EditText")).get(2).setText("preserved");
            clickDescription("发送输入"); find(By.textContains("value_preserved")); find(By.text("exit 0"));
            device.executeShellCommand("cmd uimode night no"); await(() -> !Ui.dark);
        } finally {
            context.getSharedPreferences("appearance", 0).edit().clear().commit();
            device.executeShellCommand("cmd uimode night no");
            context.getSharedPreferences("directory-places", 0).edit().clear().commit();
        }
    }

    private void swipeBottom() {
        android.graphics.Rect bar = find(By.desc("底部操作栏")).getVisibleBounds();
        device.swipe(bar.centerX(), bar.centerY(), bar.centerX(), bar.top - 280, 20);
    }

    private void refreshPane(boolean leftPane) {
        android.graphics.Rect bounds = pane(leftPane).getVisibleBounds();
        int x = bounds.centerX();
        device.swipe(x, bounds.top + 30, x, Math.min(bounds.bottom - 40, bounds.top + 600), 35);
    }

    @Test public void statefulLargeEditorSaveNavigateAndReopen() throws Exception {
        File note = new File(left, "stateful.txt");
        String original = "日本語の文章".repeat(200000) + "\n終端";
        Files.write(note.toPath(), original.getBytes("ISO-2022-JP"));
        Intent intent = new Intent(context, EditorActivity.class); FileStore.local(note).put(intent);
        scenario = ActivityScenario.launch(intent); find(By.textContains("分段"));
        String first = foregroundEditorText(); assertTrue(first.startsWith("日本語の文章"));
        clickDescription("重做 / 下一页");
        await(() -> !foregroundEditorText().equals(first));
        assertFalse(foregroundEditorText().contains("\ufffd"));
        assertTrue(foregroundEditorText().codePoints().anyMatch(cp -> cp == '日'));
        clickDescription("撤销 / 上一页"); await(() -> foregroundEditorText().equals(first));
        setForegroundEditorText("変更\n" + first); clickDescription("保存");
        await(() -> {
            try { return new String(Files.readAllBytes(note.toPath()), "ISO-2022-JP").equals("変更\n" + original); }
            catch (IOException e) { return false; }
        });
        scenario.close(); scenario = ActivityScenario.launch(intent); find(By.textContains("分段"));
        assertTrue(foregroundEditorText().startsWith("変更\n日本語"));
    }

    @Test public void shellRemainsControllableWhenFileWorkersAreBusy() throws Exception {
        startMain(); clickDescription("更多操作"); clickText("打开终端");
        java.util.concurrent.CountDownLatch occupied = new java.util.concurrent.CountDownLatch(3);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        for (int i = 0; i < 3; i++) Tasks.IO.execute(() -> {
            occupied.countDown();
            try { release.await(30, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        try {
            assertTrue(occupied.await(5, java.util.concurrent.TimeUnit.SECONDS));
            device.findObjects(By.clazz("android.widget.EditText")).get(1)
                    .setText("printf READY; read value; printf '%s' \"$value\"; sleep 60");
            clickDescription("执行"); find(By.text("READY"));
            device.findObjects(By.clazz("android.widget.EditText")).get(2).setText("control_ok");
            clickDescription("发送输入"); find(By.textContains("control_ok"));
            clickDescription("停止"); find(By.text("已停止"));
        } finally { release.countDown(); }
    }

    @Test public void shellLargeOutputLogRetainsBeginningAndEnd() throws Exception {
        startMain(); clickDescription("更多操作"); clickText("打开终端");
        device.findObjects(By.clazz("android.widget.EditText")).get(1)
                .setText("printf BEGIN_LOG; head -c 600000 /dev/zero | tr '\\000' x; printf END_LOG");
        clickDescription("执行"); find(By.text("exit 0"));
        assertTrue(find(By.desc("Shell 输出")).getText().endsWith("END_LOG"));
        clickDescription("打开完整输出"); find(By.textContains("UTF-8"));
        String text = foregroundEditorText();
        assertTrue(text.startsWith("BEGIN_LOG")); assertTrue(text.endsWith("END_LOG"));
        assertEquals(600016, text.length());
    }

    @Test public void smaliClassPreviewBeyondPagingLimitRetainsLastMethod() throws Exception {
        String header = ".class public Lfixture/Large;\n.super Ljava/lang/Object;\n";
        String method = ".method public static value()I\n.registers 1\nconst/4 v0, 0x7\nreturn v0\n.end method\n";
        File folder = new File(work, "smali"); assertTrue(folder.mkdir());
        writeText(new File(folder, "Large.smali"), header + method);
        File dex = new File(left, "large.dex"); ApkTools.assemble(folder, dex);
        File preview = new File(work, "large-preview.smali");
        String text = header + ("# " + "padding ".repeat(30) + "\n").repeat(10000) + method;
        writeText(preview, text); assertTrue(preview.length() > EditorActivity.LIMIT);
        Intent intent = new Intent(context, EditorActivity.class); FileStore.local(dex).put(intent);
        intent.putExtra("mode", "smali-class").putExtra("archivePath", dex.getAbsolutePath())
                .putExtra("preview", preview.getAbsolutePath()).putExtra("dexEntry", "")
                .putExtra("descriptor", "Lfixture/Large;");
        scenario = ActivityScenario.launch(intent); find(By.textContains("UTF-8"));
        assertEquals(text, foregroundEditorText());
        await(this::foregroundEditorEditable);
        setForegroundEditorText(text.replace("0x7", "0x6")); clickDescription("保存");
        await(() -> {
            try {
                for (org.jf.dexlib2.iface.ClassDef def : org.jf.dexlib2.DexFileFactory.loadDexFile(dex, org.jf.dexlib2.Opcodes.getDefault()).getClasses())
                    for (org.jf.dexlib2.iface.Method item : def.getMethods())
                        for (org.jf.dexlib2.iface.instruction.Instruction instruction : item.getImplementation().getInstructions())
                            if (instruction instanceof org.jf.dexlib2.iface.instruction.NarrowLiteralInstruction literal)
                                return literal.getNarrowLiteral() == 6;
                return false;
            } catch (Exception e) { return false; }
        });
    }

    private void startMain() {
        scenario = ActivityScenario.launch(new Intent(context, MainActivity.class));
        find(By.desc("左窗口文件列表")); find(By.desc("右窗口文件列表"));
        await(() -> !device.hasObject(By.text("读取中…")));
    }
    private static void writeText(File file, String text) throws IOException {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }
    private static String readText(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
    private UiObject2 pane(boolean leftPane) { return find(By.desc(leftPane ? "左窗口文件列表" : "右窗口文件列表")); }
    private void clickText(String text) {
        click(By.text(text));
        if (text.equals("打开终端")) { find(By.desc("Shell 输出")); await(() -> device.findObjects(By.clazz("android.widget.EditText")).size() == 3); }
    }
    private void clickDescription(String desc) {
        if (desc.equals("复制") || desc.equals("移动") || desc.equals("删除")) {
            click(By.desc("操作")); click(By.desc("文件操作 " + desc));
        } else click(By.desc(desc));
    }
    private String jumpPath() {
        return find(By.desc("跳转路径")).getText().trim();
    }
    private void click(BySelector selector) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try { find(selector).click(); return; }
            catch (StaleObjectException e) { device.waitForIdle(); }
        }
        fail("Unstable control: " + selector);
    }
    private void longClickText(String text) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try { find(By.text(text)).longClick(); return; }
            catch (StaleObjectException e) { device.waitForIdle(); }
        }
        fail("Unstable row: " + text);
    }
    private UiObject2 findLong(BySelector selector) {
        UiObject2 object = device.wait(Until.findObject(selector), 30000);
        assertNotNull("Missing UI element: " + selector, object); return object;
    }
    private UiObject2 find(BySelector selector) {
        UiObject2 object = device.wait(Until.findObject(selector), 8000);
        if (object == null) {
            File failure = new File(Environment.getExternalStorageDirectory(), "Documents/DualStackReview-failures");
            failure.mkdirs();
            try { device.dumpWindowHierarchy(new File(failure, "missing-element.xml")); }
            catch (IOException ignored) {}
            device.takeScreenshot(new File(failure, "missing-element.png"));
        }
        assertNotNull("Missing UI element: " + selector, object); return object;
    }
    private interface Check { boolean test(); }
    private void await(Check check) {
        // ROOT-backed materialization and WebView startup are slower than ordinary files.
        long until = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < until) {
            try { if (check.test()) return; }
            catch (StaleObjectException ignored) { /* Reacquire views while the activity is recreated. */ }
            android.os.SystemClock.sleep(80);
        }
        fail("UI condition timed out");
    }
    private String editorText(ActivityScenario<EditorActivity> activity) {
        String[] text = {""};
        activity.onActivity(a -> {
            CodeEditor editor = findEditor(a.getWindow().getDecorView());
            if (editor != null) text[0] = editor.getText().toString();
        });
        return text[0];
    }
    private String foregroundEditorText() {
        String[] result = {""};
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (android.app.Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) {
                CodeEditor editor = findEditor(activity.getWindow().getDecorView());
                if (editor != null) result[0] = editor.getText().toString();
            }
        });
        return result[0];
    }
    private String foregroundTerminalText() {
        String[] text = {""};
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (android.app.Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
                if (activity instanceof TerminalActivity terminal) text[0] = terminal.terminalText();
        });
        return text[0];
    }
    private void awaitTerminal(String text) {
        try { await(() -> foregroundTerminalText().contains(text)); }
        catch (AssertionError error) { throw new AssertionError("Missing terminal output " + text + ": " + foregroundTerminalText(), error); }
    }
    private void terminalInput(String text) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (android.app.Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
                if (activity instanceof TerminalActivity terminal) {
                    terminal.terminalView().onCreateInputConnection(new android.view.inputmethod.EditorInfo()).commitText(text, 1);
                    return;
                }
            fail("No resumed terminal");
        });
    }
    private boolean foregroundEditorEditable() {
        boolean[] result = {false};
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (android.app.Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) {
                CodeEditor editor = findEditor(activity.getWindow().getDecorView());
                if (editor != null) result[0] = editor.isEditable();
            }
        });
        return result[0];
    }
    private void setForegroundEditorText(String text) {
        await(this::foregroundEditorEditable);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (android.app.Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) {
                CodeEditor editor = findEditor(activity.getWindow().getDecorView());
                if (editor != null) { assertTrue(editor.isEditable()); editor.setText(text); return; }
            }
            fail("No resumed editor");
        });
    }
    private CodeEditor findEditor(View view) {
        if (view instanceof CodeEditor editor) return editor;
        if (view instanceof ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++) {
                CodeEditor editor = findEditor(group.getChildAt(i));
                if (editor != null) return editor;
            }
        return null;
    }
}
