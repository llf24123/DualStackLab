package com.dualstack.lab;

import android.app.AlertDialog;
import android.content.*;
import android.graphics.Color;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;

public class TaskCenterActivity extends LabActivity {
    private Operations operations;
    private final List<Operations.Task> tasks = new ArrayList<>();
    private BaseAdapter adapter;
    private TextView empty;
    private AlertDialog detailDialog;
    private String lastSnapshot = "";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable changed = this::refresh;
    private final Runnable tick = new Runnable() { public void run() { refresh(); handler.postDelayed(this, 500); } };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); operations = Operations.get(this);
        LinearLayout root = Ui.column(this), bar = Ui.toolbar(this, "任务中心", this::finish);
        bar.addView(Ui.icon(this, R.drawable.ic_trash, "清除已完成任务记录", Color.WHITE, () -> {
            try { operations.clearFinished(); } catch (Exception e) { Tasks.showError(this, e); }
        }), new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 52))); root.addView(bar);
        TextView help = Ui.text(this, "退出本页可收起进度 · 停止保留已完成项 · 重试跳过已完成项", 12, Ui.MUTED);
        help.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12)); root.addView(help);
        ListView list = new ListView(this); list.setContentDescription("文件任务列表"); list.setDivider(null);
        adapter = new BaseAdapter() {
            public int getCount() { return tasks.size(); }
            public Object getItem(int p) { return tasks.get(p); }
            public long getItemId(int p) { return p; }
            public View getView(int position, View reuse, ViewGroup parent) {
                LinearLayout row = reuse instanceof LinearLayout ? (LinearLayout) reuse : Ui.column(TaskCenterActivity.this);
                if (row.getChildCount() == 0) {
                    row.setPadding(Ui.dp(TaskCenterActivity.this, 16), Ui.dp(TaskCenterActivity.this, 14), Ui.dp(TaskCenterActivity.this, 16), Ui.dp(TaskCenterActivity.this, 14));
                    row.addView(Ui.text(TaskCenterActivity.this, "", 17, Ui.TEXT)); row.addView(Ui.text(TaskCenterActivity.this, "", 12, Ui.MUTED));
                }
                Operations.Task task = tasks.get(position);
                TextView title = (TextView) row.getChildAt(0), detail = (TextView) row.getChildAt(1);
                title.setText(task.spec.kind + (task.spec.displayName.isEmpty() ? "" : " · " + task.spec.displayName)); title.setTextColor(Ui.TEXT);
                detail.setText(task.summary()); detail.setTextColor(task.active() ? Ui.ACCENT : Ui.MUTED);
                row.setMinimumHeight(Ui.dp(TaskCenterActivity.this, 80)); row.setBackground(Ui.ripple(Ui.SURFACE));
                row.setContentDescription("文件任务 " + task.spec.kind + " " + task.state); return row;
            }
        };
        list.setAdapter(adapter); list.setOnItemClickListener((p, v, position, id) -> details(tasks.get(position)));
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        empty = Ui.text(this, "暂无文件任务", 14, Ui.MUTED); empty.setGravity(Gravity.CENTER); empty.setPadding(0, Ui.dp(this, 24), 0, Ui.dp(this, 24)); root.addView(empty);
        Ui.install(this, root);
    }
    private void refresh() {
        if (adapter == null) return;
        List<Operations.Task> current = operations.list(); StringBuilder signature = new StringBuilder(operations.journalError());
        for (Operations.Task task : current) signature.append(task.id).append(task.state).append(task.bytes()).append(task.progress.current).append(task.completed.size()).append(task.note);
        String snapshot = signature.toString(); if (snapshot.equals(lastSnapshot)) return; lastSnapshot = snapshot;
        tasks.clear(); tasks.addAll(current); adapter.notifyDataSetChanged();
        empty.setVisibility(tasks.isEmpty() || !operations.journalError().isEmpty() ? View.VISIBLE : View.GONE);
        empty.setText(operations.journalError().isEmpty() ? "暂无文件任务" : operations.journalError());
    }
    private void details(Operations.Task task) {
        LinearLayout body = Ui.column(this); body.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
        TextView text = Ui.text(this, "", 14, Ui.TEXT); text.setTextIsSelectable(true); body.addView(text);
        ScrollView scroll = new ScrollView(this); scroll.addView(body);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(task.spec.kind).setView(scroll).setNegativeButton("收起", null)
                .setPositiveButton(task.active() ? "停止" : "重试未完成", null).setNeutralButton("复制详情", null).create();
        detailDialog = dialog;
        Button openOutput = new Button(this); openOutput.setText("定位最新输出"); openOutput.setMinHeight(Ui.dp(this, 48)); body.addView(openOutput);
        openOutput.setOnClickListener(v -> {
            String key; synchronized (operations) { key = task.outputs.isEmpty() ? null : task.outputs.get(task.outputs.size() - 1); }
            if (key != null) { startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("searchResult", key)); finish(); }
        });
        Button removeRecord = new Button(this); removeRecord.setText("移除任务记录"); body.addView(removeRecord);
        removeRecord.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("移除任务记录？")
                .setMessage("仅移除记录，不删除来源、输出或回收站备份；移除后不能从此记录重试。")
                .setNegativeButton("取消", null).setPositiveButton("移除", (d, w) -> {
                    try { operations.remove(task); dialog.dismiss(); } catch (Exception e) { Tasks.showError(this, e); }
                }).show());
        Runnable update = new Runnable() { public void run() {
            if (!dialog.isShowing()) return;
            List<String> errors, outputs; synchronized (operations) { errors = new ArrayList<>(task.errors); outputs = new ArrayList<>(task.outputs); }
            text.setText(task.summary() + "\n\n目标：" + Operations.displayLocation(task.spec.destination) + "\n\n来源：\n" + task.spec.sources.stream().map(Operations::displayLocation).collect(java.util.stream.Collectors.joining("\n"))
                    + "\n\n输出：\n" + outputs.stream().map(Operations::displayLocation).collect(java.util.stream.Collectors.joining("\n")) + "\n\n错误清单：\n" + (errors.isEmpty() ? "无" : String.join("\n\n", errors)));
            dialog.getButton(-1).setText(task.active() ? "停止" : "重试未完成"); dialog.getButton(-1).setEnabled(!task.state.equals("已完成"));
            openOutput.setEnabled(!outputs.isEmpty());
            removeRecord.setEnabled(!task.active());
            handler.postDelayed(this, 500);
        }};
        dialog.setOnDismissListener(d -> handler.removeCallbacks(update)); dialog.show(); update.run();
        dialog.getButton(-3).setOnClickListener(v -> ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("任务详情", text.getText())));
        dialog.getButton(-1).setOnClickListener(v -> {
            if (task.active()) { try { operations.stop(task); } catch (Exception e) { Tasks.showError(this, e); } }
            else { dialog.dismiss(); retry(task); }
        });
    }
    private void retry(Operations.Task task) {
        LinearLayout body = Ui.column(this); body.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
        TextView note = Ui.text(this, task.inFlight >= 0 && task.state.equals("已中断")
                ? "进程在当前项提交期间结束，请先核对来源和目标。确认重试可能生成同名避让副本；已完成项目仍会跳过。"
                : "只重试失败或未开始的项目，已完成项目不会重复执行。", 14, Ui.TEXT); body.addView(note);
        EditText password = Ui.input(this, "重新输入压缩密码"); password.setInputType(0x81);
        if (task.spec.passwordRequired) body.addView(password, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("重试任务").setView(body).setPositiveButton("确认重试", null).setNegativeButton("取消", null).create();
        dialog.show(); dialog.getButton(-1).setOnClickListener(v -> {
            char[] secret = password.getText().toString().toCharArray();
            try { operations.retry(task, secret, true); dialog.dismiss(); }
            catch (Exception e) { note.setText(e.getMessage()); }
            finally { Arrays.fill(secret, '\0'); }
        });
    }
    @Override protected void onStart() { super.onStart(); operations.listen(changed); handler.post(tick); }
    @Override protected void onStop() { if (detailDialog != null) detailDialog.dismiss(); operations.unlisten(changed); handler.removeCallbacksAndMessages(null); super.onStop(); }
    @Override protected void onPaletteChanged() { if (adapter != null) adapter.notifyDataSetChanged(); }
}
