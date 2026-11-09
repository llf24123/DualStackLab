package com.dualstack.lab;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.*;
import android.widget.*;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.android.apksig.ApkVerifier;
import java.io.*;
import java.text.*;
import java.util.*;
import java.util.zip.*;

public class InstalledAppsActivity extends LabActivity {
    private record App(PackageInfo info, String label, long bytes, boolean system) {
        String name() { return info.packageName; }
        String version() { return info.versionName == null ? "?" : info.versionName; }
    }
    private final List<App> all = new ArrayList<>(), visible = new ArrayList<>();
    private final LruCache<String, Drawable> icons = new LruCache<>(48);
    private final Set<String> loadingIcons = new HashSet<>();
    private final TextView[] tabs = new TextView[2];
    private final View[] indicators = new View[2];
    private EditText query;
    private ListView list;
    private BaseAdapter adapter;
    private SwipeRefreshLayout refresh;
    private TextView empty;
    private int selectedTab, sort, generation;
    private boolean extracting;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null) { selectedTab = saved.getInt("tab"); sort = saved.getInt("sort"); }
        LinearLayout body = Ui.column(this);
        LinearLayout toolbar = Ui.toolbar(this, "已安装应用", this::finish);
        toolbar.addView(Ui.icon(this, R.drawable.ic_search, "搜索应用", Color.WHITE, () -> {
            query.setVisibility(query.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            if (query.getVisibility() == View.VISIBLE) query.requestFocus(); else query.setText("");
        }), lp(48, 52));
        toolbar.addView(Ui.icon(this, R.drawable.ic_more, "应用列表菜单", Color.WHITE, this::menu), lp(48, 52));
        body.addView(toolbar, lp(-1, 52));
        LinearLayout modes = Ui.row(this); modes.setBackgroundColor(Ui.INK);
        for (int i = 0; i < 2; i++) {
            int index = i; LinearLayout section = Ui.column(this);
            tabs[i] = Ui.text(this, i == 0 ? "用户应用" : "系统应用", 16, Color.WHITE);
            tabs[i].setGravity(Gravity.CENTER); tabs[i].setTypeface(null, Typeface.BOLD);
            tabs[i].setContentDescription(i == 0 ? "用户应用标签" : "系统应用标签");
            tabs[i].setBackground(Ui.ripple(Ui.INK)); tabs[i].setOnClickListener(v -> { selectedTab = index; apply(); });
            section.addView(tabs[i], lp(-1, 48)); indicators[i] = new View(this);
            section.addView(indicators[i], lp(-1, 2)); modes.addView(section, new LinearLayout.LayoutParams(0, -2, 1));
        }
        body.addView(modes);
        query = Ui.input(this, "名称 / 包名"); query.setContentDescription("应用搜索输入"); query.setVisibility(View.GONE);
        body.addView(query); MainActivity.watch(query, this::apply);
        FrameLayout frame = new FrameLayout(this); frame.setBackgroundColor(Ui.PANEL);
        list = new ListView(this); list.setDivider(null); list.setContentDescription("已安装应用列表");
        list.setClipToPadding(false); list.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        adapter = new BaseAdapter() {
            public int getCount() { return visible.size(); }
            public App getItem(int n) { return visible.get(n); }
            public long getItemId(int n) { return getItem(n).name().hashCode(); }
            public View getView(int n, View reuse, ViewGroup parent) {
                App app = getItem(n); AppRow row = reuse == null ? new AppRow() : (AppRow) reuse.getTag();
                row.key = app.name(); row.card.setBackground(Ui.background(Ui.SURFACE, Ui.dp(InstalledAppsActivity.this, 4)));
                row.title.setText(app.label); row.meta.setText(app.version() + "  " + FileStore.size(app.bytes)); row.pkg.setText(app.name());
                row.outer.setContentDescription("应用 " + app.name());
                Drawable icon = icons.get(app.name());
                if (icon != null) row.icon.setImageDrawable(icon); else {
                    row.icon.setImageResource(R.drawable.ic_package);
                    if (loadingIcons.add(app.name())) Tasks.run(InstalledAppsActivity.this,
                            () -> app.info.applicationInfo.loadIcon(getPackageManager()), loaded -> {
                                loadingIcons.remove(app.name()); icons.put(app.name(), loaded);
                                if (app.name().equals(row.key)) row.icon.setImageDrawable(loaded);
                            }, error -> loadingIcons.remove(app.name()));
                }
                row.title.setTextColor(Ui.TEXT); row.meta.setTextColor(Ui.MUTED); row.pkg.setTextColor(Ui.MUTED);
                return row.outer;
            }
        };
        list.setAdapter(adapter); frame.addView(list, new FrameLayout.LayoutParams(-1, -1));
        empty = Ui.text(this, "读取中…", 14, Ui.MUTED); empty.setGravity(Gravity.CENTER);
        frame.addView(empty, new FrameLayout.LayoutParams(-1, -1)); list.setEmptyView(empty);
        refresh = new SwipeRefreshLayout(this); refresh.addView(frame); refresh.setColorSchemeColors(Ui.ACCENT);
        refresh.setOnChildScrollUpCallback((parent, child) -> list.canScrollVertically(-1));
        refresh.setOnRefreshListener(this::reload); body.addView(refresh, new LinearLayout.LayoutParams(-1, 0, 1));
        list.setOnItemClickListener((parent, view, position, id) -> details(visible.get(position)));
        list.setOnItemLongClickListener((parent, view, position, id) -> { copy(visible.get(position).name()); return true; });
        Ui.install(this, body);
        if (saved != null && !saved.getString("query", "").isEmpty()) {
            query.setVisibility(View.VISIBLE); query.setText(saved.getString("query"));
        }
        reload();
    }
    private final class AppRow {
        final LinearLayout outer = Ui.column(InstalledAppsActivity.this), card = Ui.row(InstalledAppsActivity.this);
        final ImageView icon = new ImageView(InstalledAppsActivity.this);
        final TextView title = Ui.single(InstalledAppsActivity.this, "", 18, Ui.TEXT);
        final TextView meta = Ui.single(InstalledAppsActivity.this, "", 14, Ui.MUTED);
        final TextView pkg = Ui.single(InstalledAppsActivity.this, "", 14, Ui.MUTED);
        String key;
        AppRow() {
            outer.setTag(this); outer.setPadding(Ui.dp(InstalledAppsActivity.this, 8), Ui.dp(InstalledAppsActivity.this, 6),
                    Ui.dp(InstalledAppsActivity.this, 8), Ui.dp(InstalledAppsActivity.this, 6));
            card.setPadding(Ui.dp(InstalledAppsActivity.this, 10), Ui.dp(InstalledAppsActivity.this, 8),
                    Ui.dp(InstalledAppsActivity.this, 10), Ui.dp(InstalledAppsActivity.this, 8));
            card.setElevation(Ui.dp(InstalledAppsActivity.this, 2)); icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            card.addView(icon, lp(54, 54)); LinearLayout text = Ui.column(InstalledAppsActivity.this);
            title.setEllipsize(TextUtils.TruncateAt.END); pkg.setEllipsize(TextUtils.TruncateAt.END);
            text.addView(title, lp(-1, 25)); text.addView(meta, lp(-1, 21)); text.addView(pkg, lp(-1, 21));
            LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(0, -2, 1); space.leftMargin = Ui.dp(InstalledAppsActivity.this, 10);
            card.addView(text, space); outer.addView(card, lp(-1, -2));
        }
    }
    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w < 0 ? w : Ui.dp(this, w), h < 0 ? h : Ui.dp(this, h));
    }
    private void reload() {
        int token = ++generation; long started = SystemClock.uptimeMillis(); refresh.setRefreshing(true);
        Tasks.run(this, () -> {
            List<App> apps = new ArrayList<>();
            for (PackageInfo info : getPackageManager().getInstalledPackages(0)) {
                ApplicationInfo a = info.applicationInfo; if (a == null) continue;
                long bytes = new File(a.sourceDir).length();
                if (a.splitSourceDirs != null) for (String split : a.splitSourceDirs) bytes += new File(split).length();
                apps.add(new App(info, a.loadLabel(getPackageManager()).toString(), bytes,
                        (a.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0));
            }
            return apps;
        }, apps -> {
            if (token != generation) return;
            all.clear(); all.addAll(apps); apply();
            refresh.postDelayed(() -> { if (token == generation) refresh.setRefreshing(false); },
                    Math.max(0, 380 - (SystemClock.uptimeMillis() - started)));
            list.animate().cancel(); list.setAlpha(1f);
        }, error -> { refresh.setRefreshing(false); empty.setText(error.getMessage()); Tasks.showError(this, error); });
    }
    private void apply() {
        String needle = query.getText().toString().toLowerCase(Locale.ROOT); visible.clear();
        for (App app : all) if (app.system == (selectedTab == 1)
                && (app.label.toLowerCase(Locale.ROOT).contains(needle) || app.name().toLowerCase(Locale.ROOT).contains(needle))) visible.add(app);
        Collator collator = Collator.getInstance(Locale.CHINA);
        visible.sort((a, b) -> {
            if (a.name().equals(getPackageName()) != b.name().equals(getPackageName())) return a.name().equals(getPackageName()) ? -1 : 1;
            int compared = sort == 1 ? Long.compare(b.info.firstInstallTime, a.info.firstInstallTime)
                    : sort == 2 ? Long.compare(b.bytes, a.bytes) : 0;
            return compared != 0 ? compared : collator.compare(a.label, b.label);
        });
        for (int i = 0; i < 2; i++) { tabs[i].setSelected(i == selectedTab); indicators[i].setBackgroundColor(i == selectedTab ? 0xff54a8d5 : Ui.INK); }
        empty.setText(all.isEmpty() ? "暂无应用" : "没有匹配应用"); adapter.notifyDataSetChanged();
    }
    private void menu() {
        new AlertDialog.Builder(this).setItems(new String[]{"刷新", "排序方式"}, (dialog, which) -> {
            if (which == 0) reload(); else new AlertDialog.Builder(this).setTitle("排序方式")
                    .setSingleChoiceItems(new String[]{"名称", "安装时间", "安装包大小"}, sort, (d, value) -> { sort = value; apply(); d.dismiss(); }).show();
        }).show();
    }
    private void copy(String value) {
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("应用信息", value));
        Tasks.toast(this, "已复制");
    }
    private void details(App app) {
        LinearLayout body = Ui.column(this); body.setPadding(Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 10));
        LinearLayout heading = Ui.row(this); ImageView icon = new ImageView(this); icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Drawable cached = icons.get(app.name()); if (cached != null) icon.setImageDrawable(cached); else icon.setImageResource(R.drawable.ic_app);
        heading.addView(icon, lp(54, 54)); LinearLayout names = Ui.column(this);
        TextView name = Ui.single(this, app.label, 20, Ui.TEXT), version = Ui.single(this, app.version(), 14, Ui.MUTED);
        names.addView(name, lp(-1, 48)); names.addView(version, lp(-1, 48));
        LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(0, -2, 1); space.leftMargin = Ui.dp(this, 12); heading.addView(names, space);
        name.setOnLongClickListener(v -> { copy(app.label); return true; }); version.setOnLongClickListener(v -> { copy(app.version()); return true; });
        icon.setOnLongClickListener(v -> { copy(app.name()); return true; }); body.addView(heading);
        View gap = new View(this); body.addView(gap, lp(-1, 10));
        ApplicationInfo info = app.info.applicationInfo;
        Map<String, TextView> values = new LinkedHashMap<>();
        field(body, values, "包名", app.name(), null);
        field(body, values, "版本号", Long.toString(Build.VERSION.SDK_INT >= 28 ? app.info.getLongVersionCode() : app.info.versionCode), null);
        field(body, values, "安装包大小", FileStore.size(app.bytes), null);
        field(body, values, "签名状态", "检查中…", null); field(body, values, "加固状态", "检查中…", null);
        field(body, values, "目标系统", androidVersion(info.targetSdkVersion), null);
        field(body, values, "最低系统", androidVersion(info.minSdkVersion), null);
        field(body, values, "数据目录 1", info.dataDir, () -> jump(info.dataDir, false));
        String external = new File(Environment.getExternalStorageDirectory(), "Android/data/" + app.name()).getAbsolutePath();
        field(body, values, "数据目录 2", external, () -> jump(external, false));
        field(body, values, "APK 路径", info.sourceDir, () -> jump(info.sourceDir, true));
        DateFormat dates = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
        field(body, values, "首次安装", dates.format(new Date(app.info.firstInstallTime)), null);
        field(body, values, "最后更新", dates.format(new Date(app.info.lastUpdateTime)), null);
        field(body, values, "UID", Integer.toString(info.uid), null);
        ScrollView scroll = new ScrollView(this); scroll.addView(body);
        AlertDialog dialog = new AlertDialog.Builder(this).setView(scroll).setNeutralButton("更多", (d, w) -> more(app, values))
                .setPositiveButton("提取安装包", (d, w) -> extract(app)).create(); dialog.show();
        Window window = dialog.getWindow();
        if (window != null) window.setLayout(Math.min(Ui.dp(this, 440), Math.round(getResources().getDisplayMetrics().widthPixels * .88f)), -2);
        Tasks.run(this, () -> inspect(app), result -> {
            if (!dialog.isShowing()) return;
            values.get("签名状态").setText(result[0]); values.get("加固状态").setText(result[1]);
        }, error -> { if (dialog.isShowing()) { values.get("签名状态").setText("无法检查"); values.get("加固状态").setText("无法检查"); } });
    }
    private void field(LinearLayout parent, Map<String, TextView> values, String label, String value, Runnable click) {
        LinearLayout row = Ui.row(this); TextView key = Ui.single(this, label, 15, Ui.TEXT);
        TextView text = Ui.single(this, value == null ? "?" : value, 14, Ui.MUTED); text.setEllipsize(TextUtils.TruncateAt.END);
        text.setContentDescription("应用详情 " + label); values.put(label, text);
        row.addView(key, lp(103, 48)); row.addView(text, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1));
        View.OnLongClickListener copy = v -> { copy(text.getText().toString()); return true; };
        row.setOnLongClickListener(copy); key.setOnLongClickListener(copy); text.setOnLongClickListener(copy);
        if (click != null) { row.setOnClickListener(v -> click.run()); text.setOnClickListener(v -> click.run()); key.setOnClickListener(v -> click.run()); }
        parent.addView(row);
    }
    private void jump(String path, boolean apk) {
        File directory = apk ? new File(path).getParentFile() : new File(path);
        if (directory == null) return;
        setResult(RESULT_OK, new Intent().putExtra("navigatePath", directory.getAbsolutePath())); finish();
    }
    private String[] inspect(App app) throws Exception {
        File apk = new File(app.info.applicationInfo.sourceDir);
        ApkVerifier.Result signature = new ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(Math.max(26, app.info.applicationInfo.minSdkVersion)).build().verify();
        List<String> schemes = new ArrayList<>();
        if (signature.isVerifiedUsingV1Scheme()) schemes.add("V1"); if (signature.isVerifiedUsingV2Scheme()) schemes.add("V2");
        if (signature.isVerifiedUsingV3Scheme()) schemes.add("V3");
        String signed = signature.isVerified() ? String.join(" / ", schemes) : "签名校验失败";
        String packed = "未检测到加固特征";
        try (ZipFile zip = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName().toLowerCase(Locale.ROOT);
                if (name.contains("libjiagu") || name.contains("libdexhelper") || name.contains("libprotectclass")
                        || name.contains("libchaosvmp") || name.startsWith("assets/ijiami") || name.equals("assets/shell.dex")) {
                    packed = "疑似加固（文件特征）"; break;
                }
            }
        }
        return new String[]{signed.isEmpty() ? "已验证" : signed, packed};
    }
    private void more(App app, Map<String, TextView> values) {
        new AlertDialog.Builder(this).setTitle(app.label).setItems(new String[]{"打开应用", "系统应用信息", "打开 APK", "复制全部信息"}, (d, which) -> {
            if (which == 0) {
                Intent launch = getPackageManager().getLaunchIntentForPackage(app.name());
                if (launch == null) Tasks.toast(this, "此应用没有启动入口"); else startActivity(launch);
            } else if (which == 1) startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.name())));
            else if (which == 2) { Intent archive = new Intent(this, ArchiveActivity.class); FileStore.local(new File(app.info.applicationInfo.sourceDir)).put(archive); startActivity(archive); }
            else { StringBuilder text = new StringBuilder(app.label + "\n" + app.version()); values.forEach((key, value) -> text.append('\n').append(key).append(": ").append(value.getText())); copy(text.toString()); }
        }).show();
    }
    private void extract(App app) {
        if (extracting) return; extracting = true; refresh.setRefreshing(true);
        Tasks.run(this, () -> {
            FileStore store = new FileStore(this); FileStore.Node destination = store.fromIntent(getIntent());
            if (destination.file != null) destination = FileStore.directory(destination.file, destination.root);
            FileStore.Node folder = store.create(destination, store.unique(destination, app.name()), true);
            try {
                File base = new File(app.info.applicationInfo.sourceDir); store.copy(FileStore.local(base, !base.canRead()), folder, false);
                if (app.info.applicationInfo.splitSourceDirs != null) for (String path : app.info.applicationInfo.splitSourceDirs) {
                    File split = new File(path); store.copy(FileStore.local(split, !split.canRead()), folder, false);
                }
                return folder;
            } catch (Exception error) { try { store.delete(folder); } catch (IOException cleanup) { error.addSuppressed(cleanup); } throw error; }
        }, folder -> { extracting = false; refresh.setRefreshing(false); Tasks.toast(this, "已提取 · " + folder.path()); setResult(RESULT_OK, new Intent().putExtra("extracted", true)); finish(); },
                error -> { extracting = false; refresh.setRefreshing(false); Tasks.showError(this, error); });
    }
    static String androidVersion(int api) {
        String release = switch (api) { case 21 -> "5.0"; case 22 -> "5.1"; case 23 -> "6"; case 24 -> "7"; case 25 -> "7.1";
            case 26 -> "8"; case 27 -> "8.1"; case 28 -> "9"; case 29 -> "10"; case 30 -> "11"; case 31, 32 -> "12";
            case 33 -> "13"; case 34 -> "14"; case 35 -> "15"; case 36 -> "16"; default -> ""; };
        return release.isEmpty() ? "API " + api : "Android " + release + " (API " + api + ")";
    }
    @Override protected void onPaletteChanged() { if (adapter != null) { adapter.notifyDataSetChanged(); apply(); } }
    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); out.putInt("tab", selectedTab); out.putInt("sort", sort); out.putString("query", query.getText().toString()); }
}
