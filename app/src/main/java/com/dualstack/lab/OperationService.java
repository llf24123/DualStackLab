package com.dualstack.lab;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;

public class OperationService extends Service {
    private static final String CHANNEL = "file-operations";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Operations operations;
    private int latestStartId;
    private long idleSince;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!operations.active()) {
                long now = SystemClock.elapsedRealtime();
                if (idleSince == 0) idleSince = now;
                // A new start can reach ActivityManager before its onStartCommand reaches our main thread.
                // Never stop a service that still owes that newer start its foreground handshake.
                if (now - idleSince >= 1500 && stopSelfResult(latestStartId)) { stopForeground(STOP_FOREGROUND_REMOVE); return; }
                handler.postDelayed(this, 250); return;
            }
            idleSince = 0;
            getSystemService(NotificationManager.class).notify(901, notification()); handler.postDelayed(this, 1000);
        }
    };
    @Override public void onCreate() {
        super.onCreate(); operations = Operations.get(this);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "文件任务", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("复制、移动、删除备份、压缩和解压的进度");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId; idleSince = 0;
        if (Build.VERSION.SDK_INT >= 29) startForeground(901, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(901, notification());
        if (intent != null && "stop".equals(intent.getAction())) {
            for (Operations.Task t : operations.list()) if (t.active()) try { operations.stop(t); } catch (java.io.IOException ignored) { }
        }
        handler.removeCallbacks(tick); handler.post(tick); return START_NOT_STICKY;
    }
    private Notification notification() {
        Operations.Task active = null; int queued = 0;
        for (Operations.Task t : operations.list()) if (t.active()) { if (active == null || t.state.equals("处理中")) active = t; queued++; }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, TaskCenterActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, OperationService.class).setAction("stop"), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String summary = active == null ? "准备文件任务" : active.spec.kind + " · 累计传输 " + FileStore.size(active.bytes());
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_copy).setContentTitle("文件任务 · " + queued + " 项")
                .setContentText(summary).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS).setVisibility(Notification.VISIBILITY_PRIVATE)
                .addAction(new Notification.Action.Builder(null, "停止任务", stop).build()).build();
    }
    @Override public void onTimeout(int startId, int type) { operations.timeout(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }
    @Override public void onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
