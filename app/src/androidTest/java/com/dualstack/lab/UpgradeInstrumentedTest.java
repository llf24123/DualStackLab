package com.dualstack.lab;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class UpgradeInstrumentedTest {
    private Context context;
    private File root;
    private FileStore store;
    private final Set<String> previousBackups = new HashSet<>();
    private Map<String, ?> preferences;
    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext(); store = new FileStore(context);
        root = Files.createTempDirectory(context.getFilesDir().toPath(), "upgrade-test-").toFile();
        preferences = new HashMap<>(context.getSharedPreferences("MainActivity", 0).getAll());
        for (DeletedFiles.Entry entry : DeletedFiles.entries(context)) previousBackups.add(entry.id());
        DeletedFiles.testBoundary = null; Operations.testBoundary = null;
        context.getSharedPreferences("MainActivity", 0).edit().clear().putString("left", root.getPath()).putString("right", root.getPath()).commit();
    }
    @After public void cleanup() throws Exception {
        DeletedFiles.testBoundary = null; Operations.testBoundary = null;
        for (Operations.Task task : Operations.get(context).list()) if (task.active() && task.spec.sources.stream().anyMatch(s -> s.contains(root.getName()))) Operations.get(context).stop(task);
        await(() -> Operations.get(context).list().stream().noneMatch(t -> t.active() && t.spec.sources.stream().anyMatch(s -> s.contains(root.getName()))), 15000);
        for (DeletedFiles.Entry entry : DeletedFiles.entries(context)) if (!previousBackups.contains(entry.id())) DeletedFiles.purge(context, entry.id());
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
    @Test public void deletionBacksUpBytesAndRestoresWithoutOverwritingCollision() throws Exception {
        File file = file(root, "config.txt", "原始内容\n123");
        DeletedFiles.Entry entry = DeletedFiles.recycle(context, FileStore.local(file));
        assertFalse(file.exists()); assertTrue(entry.backup().isFile()); assertEquals("已删除，可恢复", entry.state());
        file(root, "config.txt", "newer");
        FileStore.Node restored = DeletedFiles.restore(context, entry.id());
        assertEquals("config (1).txt", restored.name); assertEquals("原始内容\n123", read(restored.file.toPath()));
        assertEquals("newer", read(file.toPath())); assertTrue(entry.backup().exists());
    }
    @Test public void deletionPreservesNestedEmptyDirectoriesAndSymlinkItself() throws Exception {
        File source = dir(root, "tree"), nested = dir(source, "nested"); dir(nested, "empty");
        file(nested, "payload", "keep"); File external = file(root, "outside", "do not touch");
        Files.createSymbolicLink(new File(source, "link").toPath(), external.toPath());
        DeletedFiles.Entry entry = DeletedFiles.recycle(context, FileStore.local(source));
        assertFalse(source.exists()); assertEquals("do not touch", read(external.toPath()));
        FileStore.Node restored = DeletedFiles.restore(context, entry.id());
        assertTrue(new File(restored.file, "nested/empty").isDirectory());
        assertEquals("keep", read(new File(restored.file, "nested/payload").toPath()));
        assertTrue(Files.isSymbolicLink(new File(restored.file, "link").toPath()));
        assertEquals(external.toPath(), Files.readSymbolicLink(new File(restored.file, "link").toPath()));
    }
    @Test public void externalBackupIsSyncedAndRestoresVerifiedBytes() throws Exception {
        String old = context.getSharedPreferences("SafRecovery", 0).getString(SafRecovery.BACKUP_DIRECTORY, null);
        File external = Files.createTempDirectory(context.getExternalFilesDir(null).toPath(), "deleted-external-").toFile();
        DeletedFiles.Entry entry = null;
        try {
            SafRecovery.setBackupDirectory(context, external);
            File source = file(root, "external-backed.txt", "durable external backup");
            entry = DeletedFiles.recycle(context, FileStore.local(source));
            assertFalse(source.exists()); assertTrue(entry.backup().toPath().startsWith(external.toPath()));
            FileStore.Node restored = DeletedFiles.restore(context, entry.id());
            assertEquals("durable external backup", read(restored.file.toPath()));
        } finally {
            if (entry != null) DeletedFiles.purge(context, entry.id());
            if (old == null) SafRecovery.setBackupDirectory(context, null); else SafRecovery.setBackupDirectory(context, new File(old));
            store.delete(FileStore.local(external));
        }
    }
    @Test public void newSourceMemberAfterBackupIsNeverDeleted() throws Exception {
        File source = dir(root, "tree"); file(source, "old", "old");
        DeletedFiles.testBoundary = node -> file(source, "unbacked-new", "must survive");
        try { DeletedFiles.recycle(context, FileStore.local(source)); fail("New child must block directory deletion"); }
        catch (IOException expected) { }
        assertEquals("must survive", read(new File(source, "unbacked-new").toPath()));
        assertTrue(DeletedFiles.entries(context).stream().anyMatch(e -> e.source().contains("tree") && e.backup().isFile()));
    }
    @Test public void replacedSourceDirectoryIsNeverDeleted() throws Exception {
        File source = dir(root, "tree"); file(source, "same", "original");
        DeletedFiles.testBoundary = node -> {
            Files.move(source.toPath(), new File(root, "original-kept").toPath());
            dir(root, "tree"); file(source, "same", "replacement");
        };
        try { DeletedFiles.recycle(context, FileStore.local(source)); fail("Replaced source must be rejected"); }
        catch (IOException expected) { }
        assertEquals("replacement", read(new File(source, "same").toPath()));
        assertEquals("original", read(new File(root, "original-kept/same").toPath()));
    }
    @Test public void tamperedBackupIsRejectedBeforeCreatingOutput() throws Exception {
        File source = file(root, "payload", "original"); DeletedFiles.Entry entry = DeletedFiles.recycle(context, FileStore.local(source));
        Files.write(entry.backup().toPath(), new byte[]{1,2,3});
        try { DeletedFiles.restore(context, entry.id()); fail("Corrupt backup must be rejected"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("校验")); }
        assertFalse(source.exists()); assertTrue(entry.backup().isFile());
    }
    @Test public void purgeOnlyDeletesBackupAndNeverRestoredFile() throws Exception {
        File source = file(root, "payload", "original"); DeletedFiles.Entry entry = DeletedFiles.recycle(context, FileStore.local(source));
        FileStore.Node restored = DeletedFiles.restore(context, entry.id()); DeletedFiles.purge(context, entry.id());
        assertEquals("original", read(restored.file.toPath())); assertFalse(entry.backup().exists());
    }
    @Test public void deletedFileAppearsInRecycleUiAndRestoreActionRunsTask() throws Exception {
        File source=file(root,"recycle-visible.txt","recover from UI");DeletedFiles.Entry backup=DeletedFiles.recycle(context,FileStore.local(source));
        try(ActivityScenario<RecycleBinActivity> screen=ActivityScenario.launch(RecycleBinActivity.class)){
            AtomicBoolean ready=new AtomicBoolean();await(()->{screen.onActivity(a->{try{ready.set(!((Boolean)field(a,"loading"))&&((List<?>)field(a,"deletedEntries")).stream().anyMatch(e->((DeletedFiles.Entry)e).id().equals(backup.id())));}catch(Exception e){throw new RuntimeException(e);}});return ready.get();},15000);
            screen.onActivity(a->{try{ListView list=(ListView)field(a,"list");list.setSelectionFromTop(list.getHeaderViewsCount(),0);}catch(Exception e){throw new RuntimeException(e);}});
            androidx.test.uiautomator.UiDevice device=androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());device.waitForIdle();assertTrue(device.takeScreenshot(new File(context.getExternalFilesDir(null),"upgrade-recycle.png")));
            screen.onActivity(a->{try{java.lang.reflect.Method method=a.getClass().getDeclaredMethod("deletedActions",DeletedFiles.Entry.class);method.setAccessible(true);method.invoke(a,backup);android.app.AlertDialog dialog=(android.app.AlertDialog)field(a,"actionDialog");ListView list=dialog.getListView();assertTrue(list.getAdapter().getItem(0).toString().contains("恢复到原目录"));list.performItemClick(list.getChildAt(0),0,0);}catch(Exception e){throw new RuntimeException(e);}});
            await(()->source.exists()&&Operations.get(context).list().stream().anyMatch(t->t.spec.kind.equals(Operations.RESTORE)&&t.spec.name.equals(backup.id())&&!t.active()),15000);
            assertEquals("recover from UI",read(source.toPath()));device.pressBack();
        }
    }
    @Test public void deletionRefusesBackupLocationInsideSource() throws Exception {
        File source = dir(root, "with-backups"); file(source, "keep", "content");
        String old = context.getSharedPreferences(SafRecovery.PREFERENCES, 0).getString(SafRecovery.BACKUP_DIRECTORY, null);
        try {
            SafRecovery.setBackupDirectory(context, new File(source, "backups"));
            try { DeletedFiles.recycle(context, FileStore.local(source)); fail("Recursive backup must be rejected"); }
            catch (IOException expected) { }
            assertEquals("content", read(new File(source, "keep").toPath()));
        } finally { if (old == null) SafRecovery.setBackupDirectory(context, null); else SafRecovery.setBackupDirectory(context, new File(old)); }
    }
    @Test public void safDeletionAndRestoreUseExplicitParent() throws Exception {
        Uri control = Uri.parse("content://" + CountingDocumentsProvider.AUTHORITY);
        Uri tree = Uri.parse(grantFixture().getString("uri")); FileStore.Node parent = store.document(tree);
        Bundle seed = new Bundle(); seed.putString("name", "delete-me.txt"); seed.putString("text", "SAF bytes");
        context.getContentResolver().call(control, "seed", null, seed);
        try {
            FileStore.Node source = store.list(parent).stream().filter(n -> n.name.equals("delete-me.txt")).findFirst().orElseThrow();
            DeletedFiles.Entry entry = DeletedFiles.recycle(context, source, parent);
            assertTrue(store.list(parent).isEmpty()); FileStore.Node restored = DeletedFiles.restore(context, entry.id());
            try (InputStream in = store.read(restored)) { assertEquals("SAF bytes", new String(FileStore.readBytes(in, 100), StandardCharsets.UTF_8)); }
        } finally { context.getContentResolver().call(control, "reset", null, null); }
    }
    @Test public void retrySkipsCompletedCopyItemsAndReportsErrors() throws Exception {
        File source = file(root, "good", "one"), missing = new File(root, "missing"), output = dir(root, "out");
        try (ActivityScenario<MainActivity> ignored = ActivityScenario.launch(MainActivity.class)) {
            Operations operations = Operations.get(context);
            Operations.Task task = operations.enqueue(Operations.Spec.of(Operations.COPY, List.of(FileStore.local(source), FileStore.local(missing)), FileStore.local(output), null));
            await(() -> !task.active(), 15000); assertEquals("部分失败", task.state); assertEquals(1, task.completed.size()); assertFalse(task.errors.isEmpty());
            file(root, "missing", "two"); operations.retry(task, null, false); await(() -> !task.active(), 15000);
            assertEquals("已完成", task.state); assertEquals(2, output.list().length); assertFalse(new File(output, "good (1)").exists());
            assertTrue(task.bytes() > 0);
        }
    }
    @Test public void backupRejectsDifferentStreamEvenWhenLaterSourceReadsMatch() throws Exception {
        Uri control=Uri.parse("content://"+CountingDocumentsProvider.AUTHORITY), tree=Uri.parse(grantFixture().getString("uri"));
        FileStore.Node parent=store.document(tree);
        Bundle seed=new Bundle();seed.putString("name","variant.txt");seed.putString("text","stable-original");context.getContentResolver().call(control,"seed",null,seed);
        Bundle config=new Bundle();config.putString("alternateReadName","variant.txt");config.putInt("alternateReadAt",2);context.getContentResolver().call(control,"configure",null,config);
        try {
            FileStore.Node source=store.list(parent).get(0);
            try{DeletedFiles.recycle(context,source,parent);fail("Actual copied bytes must match baseline");}
            catch(IOException expected){assertTrue(expected.getMessage(),expected.getMessage().contains("备份字节"));}
            assertEquals(1,store.list(parent).size());
            try(InputStream in=store.read(source)){assertEquals("stable-original",new String(FileStore.readBytes(in,100),StandardCharsets.UTF_8));}
        }finally{context.getContentResolver().call(control,"reset",null,null);}
    }
    @Test public void cancelledRestoreKeepsBackupAndCreatesNoOutput() throws Exception {
        File source=file(root,"cancel-restore","original");DeletedFiles.Entry backup=DeletedFiles.recycle(context,FileStore.local(source));
        Tasks.Progress progress=new Tasks.Progress();progress.cancelled.set(true);
        try{progress.run(()->DeletedFiles.restore(context,backup.id()));fail("Restore must stop");}catch(Tasks.CancelledException expected){}
        assertFalse(source.exists());assertTrue(backup.backup().isFile());
    }
    @Test public void stoppedTaskRetainsCompletedItemsAndCanRetryRemainder() throws Exception {
        File a = file(root, "a", "A"), b = file(root, "b", "B"), output = dir(root, "out");
        CountDownLatch reached = new CountDownLatch(1), release = new CountDownLatch(1);
        Operations.testBoundary = (task, index) -> { if (task.spec.destination.equals(output.getPath()) && index == 1) { reached.countDown(); assertTrue(release.await(15, TimeUnit.SECONDS)); } };
        try (ActivityScenario<MainActivity> ignored = ActivityScenario.launch(MainActivity.class)) {
            Operations operations = Operations.get(context);
            Operations.Task task = operations.enqueue(Operations.Spec.of(Operations.COPY, List.of(FileStore.local(a), FileStore.local(b)), FileStore.local(output), null));
            try { assertTrue(reached.await(15, TimeUnit.SECONDS)); operations.stop(task); }
            finally { release.countDown(); }
            await(() -> !task.active(), 15000); assertEquals("已停止", task.state); assertTrue(new File(output, "a").exists()); assertFalse(new File(output, "b").exists());
            Operations.testBoundary = null; operations.retry(task, null, false); await(() -> !task.active(), 15000);
            assertEquals("已完成", task.state); assertEquals(2, output.list().length);
        } finally { release.countDown(); }
    }
    @Test public void moveAndDeleteRetryDoNotResolveAlreadyRemovedSources() throws Exception {
        try (ActivityScenario<MainActivity> ignored = ActivityScenario.launch(MainActivity.class)) {
            for (String kind : new String[]{Operations.MOVE, Operations.DELETE}) {
                File source = file(root, "done-" + kind, "first"), missing = new File(root, "pending-" + kind), destination = dir(root, "out-" + kind);
                Operations operations = Operations.get(context);
                Operations.Task task = operations.enqueue(Operations.Spec.of(kind, List.of(FileStore.local(source), FileStore.local(missing)), FileStore.local(destination), null));
                await(() -> !task.active(), 15000); assertEquals("部分失败", task.state); assertEquals(1, task.completed.size()); assertFalse(source.exists());
                file(root, missing.getName(), "second"); operations.retry(task, null, false); await(() -> !task.active(), 15000);
                assertEquals("已完成", task.state); assertFalse(missing.exists()); assertEquals(2, task.completed.size());
            }
        }
    }
    @Test public void archiveDirectoryDeletionCanBeRestoredFromItsBackup() throws Exception {
        File tree=dir(root,"tree");file(tree,"note.txt","archive bytes");dir(tree,"empty");
        File archive=new File(root,"original.zip");ArchiveOps.pack(store,List.of(FileStore.local(tree)),archive);
        FileStore.Node entry=FileStore.archiveNode(FileStore.local(archive),"tree/",true,0,0);
        DeletedFiles.Entry backup=DeletedFiles.recycle(context,entry);
        assertTrue(ArchiveOps.list(archive,"").isEmpty());
        DeletedFiles.restore(context,backup.id());
        assertEquals("archive bytes",new String(ArchiveOps.bytes(archive,"tree/note.txt",100),StandardCharsets.UTF_8));
        assertTrue(ArchiveOps.list(archive,"tree/").stream().anyMatch(item->item.name().equals("empty")));
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
            scenario.onActivity(a -> { try { ListView list=(ListView)field(field(a,"left"),"list"); assertEquals(pos.get(),list.getFirstVisiblePosition()); assertEquals(offset.get(),list.getChildAt(0).getTop()); assertEquals(Ui.dp(a,48),((View)field(a,"status")).getHeight()); }catch(Exception e){throw new RuntimeException(e);} });
        }
    }
    @Test public void taskContinuesAcrossMainActivityRecreationAndHighlightsOutput() throws Exception {
        File source = file(root, "zz-new", "data"), output = dir(root, "out");
        context.getSharedPreferences("MainActivity",0).edit().putString("right",output.getPath()).commit();
        CountDownLatch reached=new CountDownLatch(1), release=new CountDownLatch(1);
        Operations.testBoundary=(task,index)->{if(task.spec.destination.equals(output.getPath())){reached.countDown();assertTrue(release.await(15,TimeUnit.SECONDS));}};
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            awaitMainList(scenario,"right",1);
            Operations.Spec spec=Operations.Spec.of(Operations.COPY,List.of(FileStore.local(source)),FileStore.local(output),null);spec.targetPane=1;
            Operations.Task task=Operations.get(context).enqueue(spec);
            try { assertTrue(reached.await(15,TimeUnit.SECONDS)); scenario.recreate(); } finally {release.countDown();}
            await(()->!task.active(),15000); assertEquals("已完成",task.state);
            AtomicBoolean flashed=new AtomicBoolean();
            await(()->{scenario.onActivity(a->{try{flashed.set(!((Set<?>)field(field(a,"right"),"resultHighlights")).isEmpty());}catch(Exception e){throw new RuntimeException(e);}});return flashed.get();},4000);
            assertEquals("data",read(new File(output,"zz-new").toPath()));assertEquals(1,output.list().length);
        }finally{release.countDown();}
    }
    private Bundle grantFixture() throws Exception {
        CountDownLatch done=new CountDownLatch(1);AtomicReference<Bundle> result=new AtomicReference<>();
        String pkg=InstrumentationRegistry.getInstrumentation().getContext().getPackageName();
        context.sendOrderedBroadcast(new Intent(CountingDocumentsProvider.AUTHORITY+".GRANT").setComponent(new ComponentName(pkg,CountingDocumentsProvider.GrantReceiver.class.getName())).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES),null,new BroadcastReceiver(){
            @Override public void onReceive(Context c,Intent i){result.set(getResultExtras(false));done.countDown();}
        },null,Activity.RESULT_CANCELED,null,null);
        assertTrue(done.await(20,TimeUnit.SECONDS));assertNotNull(result.get());return result.get();
    }
    private static String read(Path path)throws IOException{return new String(Files.readAllBytes(path),StandardCharsets.UTF_8);}
    private static File file(File p,String name,String contents)throws IOException{File f=new File(p,name);Files.write(f.toPath(),contents.getBytes(StandardCharsets.UTF_8));return f;}
    private static File dir(File p,String name)throws IOException{File f=new File(p,name);Files.createDirectories(f.toPath());return f;}
    private static Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static void idle(){InstrumentationRegistry.getInstrumentation().waitForIdleSync();}
    private static void await(BooleanSupplier condition,long timeout)throws Exception{long until=SystemClock.uptimeMillis()+timeout;while(!condition.getAsBoolean()&&SystemClock.uptimeMillis()<until)Thread.sleep(30);assertTrue("Timed out",condition.getAsBoolean());}
    private static void awaitMainList(ActivityScenario<MainActivity> scenario,String side,int size)throws Exception{AtomicInteger count=new AtomicInteger();await(()->{scenario.onActivity(a->{try{count.set(((ListView)field(field(a,side),"list")).getCount());}catch(Exception e){throw new RuntimeException(e);}});return count.get()>=size;},15000);}
}
