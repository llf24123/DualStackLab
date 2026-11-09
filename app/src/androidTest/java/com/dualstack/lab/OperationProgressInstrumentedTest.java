package com.dualstack.lab;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** Verifies the replacement for the old blocking Activity-owned progress dialog. */
@RunWith(AndroidJUnit4.class)
public class OperationProgressInstrumentedTest {
    private Context context;
    private File root, output;
    private Operations operations;
    private Operations.Task task;
    private ActivityScenario<MainActivity> main;
    private ActivityScenario<TaskCenterActivity> center;
    private final CountDownLatch reached = new CountDownLatch(1), release = new CountDownLatch(1);
    @Before public void setup() throws Exception {
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();operations=Operations.get(context);
        root=Files.createTempDirectory(context.getFilesDir().toPath(),"task-center-test-").toFile();output=new File(root,"output");assertTrue(output.mkdir());
        context.getSharedPreferences("MainActivity",0).edit().clear().putString("left",root.getPath()).putString("right",output.getPath()).commit();
        main=ActivityScenario.launch(MainActivity.class);
    }
    @After public void cleanup() throws Exception {
        release.countDown();Operations.testBoundary=null;
        if(task!=null&&task.active())operations.stop(task);
        if(task!=null)await(()->!task.active());
        if(center!=null)center.close();if(main!=null)main.close();
        if(task!=null)operations.remove(task);
        new FileStore(context).delete(FileStore.local(root));context.getSharedPreferences("MainActivity",0).edit().clear().commit();
    }
    private void startHeld(boolean second) throws Exception {
        File first=new File(root,"first.bin"), next=new File(root,"next.bin");Files.write(first.toPath(),new byte[256*1024]);Files.write(next.toPath(),new byte[]{2});
        Operations.testBoundary=(t,index)->{if(t.spec.destination.equals(output.getPath())&&index==(second?1:0)){reached.countDown();if(!release.await(20,TimeUnit.SECONDS))throw new IOException("Test gate timed out");}};
        Operations.Spec spec=Operations.Spec.of(Operations.COPY,List.of(FileStore.local(first),FileStore.local(next)),FileStore.local(output),null);spec.targetPane=1;
        task=operations.enqueue(spec);assertTrue(reached.await(15,TimeUnit.SECONDS));
    }
    @Test public void longTaskHasForegroundServiceAndDoesNotOpenBlockingDialog() throws Exception {
        startHeld(false);assertTrue(task.active());
        File browse=new File(root,"browse");assertTrue(browse.mkdir());
        main.onActivity(a->{try{Object pane=field(a,"left");Method navigate=pane.getClass().getDeclaredMethod("navigate",FileStore.Node.class);navigate.setAccessible(true);navigate.invoke(pane,FileStore.local(browse));}catch(Exception e){throw new RuntimeException(e);}});
        AtomicBoolean navigated=new AtomicBoolean();await(()->{main.onActivity(a->{try{navigated.set(((FileStore.Node)field(field(a,"left"),"location")).path().equals(browse.getPath()));}catch(Exception e){throw new RuntimeException(e);}});return navigated.get();});
        await(()->context.getSystemService(ActivityManager.class).getRunningServices(50).stream().anyMatch(s->s.service.getClassName().equals(OperationService.class.getName())&&s.foreground));
        center=ActivityScenario.launch(TaskCenterActivity.class);capture("upgrade-task-center.png");center.close();center=null;
        assertTrue(task.active());release.countDown();await(()->!task.active());assertEquals("已完成",task.state);
    }
    @Test public void recreateMainAndCenterNeverRestartsTheSameTask() throws Exception {
        startHeld(false);String id=task.id;main.recreate();center=ActivityScenario.launch(TaskCenterActivity.class);center.recreate();
        assertSame(task,operations.find(id));release.countDown();await(()->!task.active());assertEquals(2,output.list().length);assertEquals(1,task.attempts);
    }
    @Test public void stopButtonPreservesCompletedItemAndLeavesRemainderRetryable() throws Exception {
        startHeld(true);assertEquals(1,task.completed.size());assertTrue(task.bytes()>=256*1024);
        center=ActivityScenario.launch(TaskCenterActivity.class);
        center.onActivity(a->{try{details(a,task);dialog(a).getButton(AlertDialog.BUTTON_POSITIVE).performClick();}catch(Exception e){throw new RuntimeException(e);}});
        release.countDown();await(()->!task.active());assertEquals("已停止",task.state);assertTrue(new File(output,"first.bin").exists());assertFalse(new File(output,"next.bin").exists());
        Operations.testBoundary=null;operations.retry(task,null,false);await(()->!task.active());assertEquals("已完成",task.state);assertEquals(2,output.list().length);
    }
    @Test public void taskDetailsShowRealBytesSpeedAndSpecificErrors() throws Exception {
        startHeld(true);center=ActivityScenario.launch(TaskCenterActivity.class);
        center.onActivity(a->{try{details(a,task);String text=allText(dialog(a).getWindow().getDecorView());assertTrue(text.contains("累计传输"));assertTrue(text.contains("/s"));assertTrue(text.contains("1/2"));}catch(Exception e){throw new RuntimeException(e);}});
        Files.delete(new File(root,"next.bin").toPath());release.countDown();await(()->!task.active());assertEquals("部分失败",task.state);assertFalse(task.errors.isEmpty());
        center.onActivity(a->{try{dialog(a).dismiss();details(a,task);String text=allText(dialog(a).getWindow().getDecorView());assertTrue(text.contains("错误清单"));assertTrue(text.contains("next.bin"));}catch(Exception e){throw new RuntimeException(e);}});
    }
    @Test public void stoppingQueuedTaskDoesNotCreateItsOutput() throws Exception {
        startHeld(false);File another=new File(root,"queued.bin");Files.write(another.toPath(),new byte[]{3});
        Operations.Task queued=operations.enqueue(Operations.Spec.of(Operations.COPY,List.of(FileStore.local(another)),FileStore.local(output),null));
        try{operations.stop(queued);release.countDown();await(()->!task.active());assertEquals("已停止",queued.state);assertFalse(new File(output,"queued.bin").exists());}
        finally{operations.remove(queued);}
    }
    @Test public void taskDetailsDismissOnBackgroundAndCanReopenFresh() throws Exception {
        startHeld(false);center=ActivityScenario.launch(TaskCenterActivity.class);AtomicReference<AlertDialog> old=new AtomicReference<>();
        center.onActivity(a->{try{details(a,task);old.set(dialog(a));assertTrue(old.get().isShowing());}catch(Exception e){throw new RuntimeException(e);}});
        center.moveToState(Lifecycle.State.CREATED);assertFalse(old.get().isShowing());center.moveToState(Lifecycle.State.RESUMED);
        center.onActivity(a->{try{details(a,task);assertTrue(dialog(a).isShowing());assertNotSame(old.get(),dialog(a));}catch(Exception e){throw new RuntimeException(e);}});
    }
    @Test public void deviceInfoScreenFiltersAndProvides48dpControls() throws Exception {
        try(ActivityScenario<DeviceInfoActivity> screen=ActivityScenario.launch(DeviceInfoActivity.class)){
            AtomicBoolean loaded=new AtomicBoolean();await(()->{screen.onActivity(a->{try{loaded.set(!((Boolean)field(a,"loading"))&&((List<?>)field(a,"all")).size()>25);}catch(Exception e){throw new RuntimeException(e);}});return loaded.get();});
            capture("upgrade-device-info.png");
            screen.onActivity(a->{try{assertTargets(a.getWindow().getDecorView(),a);((EditText)field(a,"filter")).setText("Android ID");List<?> visible=(List<?>)field(a,"visible");assertTrue(visible.size()>=1);assertTrue(visible.size()<((List<?>)field(a,"all")).size());}catch(Exception e){throw new RuntimeException(e);}});
            capture("upgrade-device-filter.png");
            screen.onActivity(a -> {
                try {
                    ((EditText) field(a, "filter")).setText("常用系统属性");
                    assertEquals(12, ((List<?>) field(a, "visible")).size());
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            capture("optimization-device-properties.png");
        }
    }
    @Test public void rapidShortTasksDoNotRaceForegroundServiceShutdown() throws Exception {
        for(int i=0;i<20;i++){
            File source=new File(root,"rapid-"+i);Files.write(source.toPath(),new byte[]{1});
            task=operations.enqueue(Operations.Spec.of(Operations.COPY,List.of(FileStore.local(source)),FileStore.local(output),null));
            await(()->!task.active());assertEquals("已完成",task.state);operations.remove(task);
        }
        assertEquals(20,output.list().length);
        Thread.sleep(2000); // Cross the idle-stop boundary so a delayed foreground-start failure cannot hide.
    }
    @Test public void editorSearchControlsHave48dpTouchTargets() throws Exception {
        File source=new File(root,"editor.txt");Files.write(source.toPath(),"hello\nhello".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Intent intent=new Intent(context,EditorActivity.class);FileStore.local(source).put(intent);
        try(ActivityScenario<EditorActivity> editor=ActivityScenario.launch(intent)){
            AtomicBoolean loaded=new AtomicBoolean();await(()->{editor.onActivity(a->{try{loaded.set((Boolean)field(a,"loaded"));}catch(Exception e){throw new RuntimeException(e);}});return loaded.get();});
            editor.onActivity(a->{try{Method m=a.getClass().getDeclaredMethod("openSearch");m.setAccessible(true);m.invoke(a);}catch(Exception e){throw new RuntimeException(e);}});
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            editor.onActivity(a->assertTargets(a.getWindow().getDecorView(),a));capture("upgrade-editor-controls.png");
        }
    }
    private void capture(String name){File file=new File(context.getExternalFilesDir(null),name);UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());device.waitForIdle();assertTrue(device.takeScreenshot(file));}
    private static void assertTargets(View view,Context c){
        if(!view.isShown())return;
        if(view.isClickable()&&view.isEnabled()&&view.getImportantForAccessibility()!=View.IMPORTANT_FOR_ACCESSIBILITY_NO){
            assertTrue(view.getClass()+" "+view.getContentDescription()+" width="+view.getWidth(),view.getWidth()>=Ui.dp(c,48));
            assertTrue(view.getClass()+" "+view.getContentDescription()+" height="+view.getHeight(),view.getHeight()>=Ui.dp(c,48));
        }
        if(view instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++)assertTargets(group.getChildAt(i),c);
    }
    private static Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static void details(TaskCenterActivity a,Operations.Task t)throws Exception{Method m=a.getClass().getDeclaredMethod("details",Operations.Task.class);m.setAccessible(true);m.invoke(a,t);}
    private static AlertDialog dialog(TaskCenterActivity a)throws Exception{return(AlertDialog)field(a,"detailDialog");}
    private static String allText(View view){StringBuilder s=new StringBuilder();if(view instanceof TextView text)s.append(text.getText());if(view instanceof ViewGroup g)for(int i=0;i<g.getChildCount();i++)s.append(allText(g.getChildAt(i)));return s.toString();}
    private static void await(BooleanSupplier condition)throws Exception{long until=SystemClock.uptimeMillis()+15000;while(!condition.getAsBoolean()&&SystemClock.uptimeMillis()<until)Thread.sleep(30);assertTrue("Timed out",condition.getAsBoolean());}
}
