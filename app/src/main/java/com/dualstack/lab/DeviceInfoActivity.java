package com.dualstack.lab;

import android.content.*;
import android.graphics.Color;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.util.*;

public class DeviceInfoActivity extends LabActivity {
    private final List<DeviceInfo.Field> all = new ArrayList<>(), visible = new ArrayList<>();
    private BaseAdapter adapter;
    private TextView status;
    private EditText filter;
    private int generation;
    private boolean loading;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout root = Ui.column(this), bar = Ui.toolbar(this, "设备信息", this::finish);
        bar.addView(Ui.icon(this, R.drawable.ic_copy, "复制全部设备信息", Color.WHITE, this::copyAll), new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 52)));
        bar.addView(Ui.icon(this, R.drawable.ic_refresh, "刷新设备信息", Color.WHITE, this::load), new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 52)));
        root.addView(bar);
        filter = Ui.input(this, "筛选字段、数值，例如 serial / DRM / 处理器"); filter.setContentDescription("筛选设备信息");
        root.addView(filter, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
        MainActivity.watch(filter, this::apply);
        status = Ui.text(this, "读取中…", 12, Ui.MUTED); status.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); root.addView(status);
        ListView list = new ListView(this); list.setContentDescription("设备信息列表"); list.setDivider(null);
        adapter = new BaseAdapter() {
            public int getCount() { return visible.size(); }
            public Object getItem(int position) { return visible.get(position); }
            public long getItemId(int position) { return position; }
            public View getView(int position, View reuse, ViewGroup parent) {
                LinearLayout row = reuse instanceof LinearLayout ? (LinearLayout) reuse : Ui.column(DeviceInfoActivity.this);
                if (row.getChildCount() == 0) {
                    row.setPadding(Ui.dp(DeviceInfoActivity.this, 16), Ui.dp(DeviceInfoActivity.this, 10), Ui.dp(DeviceInfoActivity.this, 16), Ui.dp(DeviceInfoActivity.this, 10));
                    row.setMinimumHeight(Ui.dp(DeviceInfoActivity.this, 64));
                    row.addView(Ui.text(DeviceInfoActivity.this, "", 12, Ui.ACCENT));
                    row.addView(Ui.text(DeviceInfoActivity.this, "", 15, Ui.TEXT));
                }
                DeviceInfo.Field field = visible.get(position);
                TextView title = (TextView) row.getChildAt(0), value = (TextView) row.getChildAt(1);
                title.setText(field.section() + " · " + field.name()); title.setTextColor(Ui.ACCENT);
                value.setText(field.value()); value.setMaxLines(6); value.setEllipsize(android.text.TextUtils.TruncateAt.END); value.setTextColor(Ui.TEXT);
                row.setBackground(Ui.ripple(Ui.SURFACE)); row.setContentDescription(field.name() + "：" + field.value());
                return row;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, position, id) -> {
            DeviceInfo.Field field = visible.get(position);
            new android.app.AlertDialog.Builder(this).setTitle(field.name()).setMessage(field.value())
                    .setPositiveButton("复制", (d, w) -> copy(field.name() + "\n" + field.value())).setNegativeButton("关闭", null).show();
        });
        list.setOnItemLongClickListener((p, v, position, id) -> { copy(visible.get(position).value()); return true; });
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1)); Ui.install(this, root);
        if (saved != null) filter.setText(saved.getString("filter", "")); load();
    }
    private void load() {
        if (loading) return;
        loading = true; int token = ++generation; all.clear(); apply(); status.setText("读取设备信息…");
        Context application = getApplicationContext();
        Tasks.run(this, () -> DeviceInfo.basic(application), fields -> {
            if (token != generation) return;
            all.addAll(fields); apply(); status.setText("已读取 " + all.size() + " 项，正在检查 ROOT…");
            Tasks.run(this, DeviceInfo::privileged, extra -> {
                if (token != generation) return;
                loading = false; all.addAll(extra); apply();
            }, error -> { loading = false; status.setText("ROOT 信息读取失败：" + error.getMessage()); });
        }, error -> { loading = false; status.setText("读取失败：" + error.getMessage()); });
    }
    private void apply() {
        if (filter == null || adapter == null) return;
        String query = filter.getText().toString().toLowerCase(Locale.ROOT);
        visible.clear(); for (DeviceInfo.Field field : all)
            if ((field.section() + field.name() + field.value()).toLowerCase(Locale.ROOT).contains(query)) visible.add(field);
        adapter.notifyDataSetChanged();
        if (!loading) status.setText("显示 " + visible.size() + " / " + all.size() + " 项 · 点按展开并复制 · 仅保留在本机");
    }
    private void copyAll() {
        StringBuilder text = new StringBuilder();
        for (DeviceInfo.Field field : all) text.append('[').append(field.section()).append("] ").append(field.name()).append(": ").append(field.value()).append('\n');
        copy(text.toString());
    }
    private void copy(String value) { ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("设备信息", value)); Tasks.toast(this, "已复制"); }
    @Override protected void onSaveInstanceState(Bundle state) { state.putString("filter", filter.getText().toString()); super.onSaveInstanceState(state); }
    @Override protected void onPaletteChanged() { if (adapter != null) adapter.notifyDataSetChanged(); }
}
