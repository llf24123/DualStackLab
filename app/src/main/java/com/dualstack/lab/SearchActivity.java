package com.dualstack.lab;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import java.util.*;

public class SearchActivity extends LabActivity {
    private SearchJob job;
    private TextView progress, stop;
    private ArrayAdapter<String> adapter;
    private final List<FileStore.Node> nodes = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();
    private final Runnable changed = this::update;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        job = SearchJob.active();
        LinearLayout root = Ui.column(this);
        root.addView(Ui.toolbar(this, "搜索结果", this::finish), new LinearLayout.LayoutParams(-1, Ui.dp(this, 52)));
        progress = Ui.text(this, "搜索中…", 13, Ui.MUTED);
        progress.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 10));
        progress.setContentDescription("搜索进度"); root.addView(progress);
        ListView list = new ListView(this); list.setContentDescription("搜索结果列表");
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_2, android.R.id.text1, labels) {
            @Override public View getView(int position, View convert, android.view.ViewGroup parent) {
                View row = super.getView(position, convert, parent);
                FileStore.Node node = nodes.get(position);
                TextView name = row.findViewById(android.R.id.text1), path = row.findViewById(android.R.id.text2);
                name.setText(node.name); name.setTextColor(Ui.TEXT);
                path.setText(node.path()); path.setTextColor(Ui.MUTED);
                return row;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            FileStore.Node node = nodes.get(position);
            Intent intent = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            intent.putExtra("searchResult", node.key());
            startActivity(intent); finish();
        });
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout buttons = Ui.row(this);
        stop = Ui.text(this, "停止搜索", 15, Ui.ACCENT); stop.setGravity(android.view.Gravity.CENTER);
        stop.setOnClickListener(v -> { if (job != null) job.cancel(); });
        TextView background = Ui.text(this, "后台搜索", 15, Ui.ACCENT); background.setGravity(android.view.Gravity.CENTER);
        background.setOnClickListener(v -> finish());
        buttons.addView(stop, new LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1));
        buttons.addView(background, new LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1)); root.addView(buttons);
        Ui.install(this, root); update();
    }
    @Override protected void onStart() {
        super.onStart(); if (job != null) job.listen(changed); update();
    }
    @Override protected void onStop() {
        if (job != null) job.unlisten(changed); super.onStop();
    }
    private void update() {
        if (progress == null) return;
        if (job == null) { progress.setText("暂无搜索任务"); stop.setEnabled(false); return; }
        SearchJob.Snapshot state = job.snapshot();
        int oldSize = nodes.size(); nodes.clear(); nodes.addAll(state.matches());
        for (int i = oldSize; i < nodes.size(); i++) labels.add(nodes.get(i).name);
        if (oldSize != nodes.size()) adapter.notifyDataSetChanged();
        String status = state.finished() ? state.cancelled() ? "已停止" : "搜索完成" : state.cancelled() ? "正在停止…" : "搜索中…";
        progress.setText(status + " · " + nodes.size() + " 项 · 已检查 " + state.scanned()
                + (state.denied() == 0 ? "" : " · 跳过 " + state.denied())
                + (nodes.size() >= 2000 ? " · 达到 2000 项上限" : "")
                + (state.error() == null ? "" : "\n" + state.error()));
        stop.setEnabled(!state.finished() && !state.cancelled());
    }
}
