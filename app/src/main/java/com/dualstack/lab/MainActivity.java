package com.dualstack.lab;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public class MainActivity extends LabActivity {
    private static final int TREE = 100, ARTIFACT = 200, INSTALLED = 400;
    private FileStore store;
    private DirectoryPlaces directoryPlaces;
    private Pane left, right, active;
    private TextView title, subtitle, status;
    private LinearLayout actions, permission;
    private View moreButton;
    private Dialog drawer;
    private DirectorySheet sheet;
    private PopupWindow popupMenu;
    private Dialog fileMenu;
    private long exitArmedAt;
    private int externalGeneration;
    private String handledExternal;
    private boolean showHidden;
    private boolean busy, resumed;
    private Boolean selectionActions;
    private final Map<String, DirectorySnapshot> directorySnapshots = new LinkedHashMap<>(24, .75f, true);
    private record DirectorySnapshot(List<FileStore.Node> nodes, Map<String, String> metadata, long space, long time) {}
    private File pendingInstall;
    private final SimpleDateFormat date = new SimpleDateFormat("yy-MM-dd HH:mm", Locale.CHINA);

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new FileStore(this);
        if (saved != null && saved.getString("pendingInstall") != null) pendingInstall = new File(saved.getString("pendingInstall"));
        directoryPlaces = new DirectoryPlaces(this);
        showHidden = getPreferences(0).getBoolean("showHidden", true);
        LinearLayout root = Ui.column(this);
        LinearLayout top = Ui.row(this);
        top.setBackgroundColor(Ui.INK);
        top.addView(Ui.icon(this, R.drawable.ic_menu, "位置与工具", Color.WHITE, this::places), lp(48, 58));
        LinearLayout labels = Ui.column(this);
        title = Ui.single(this, "双栈 Lab", 18, Color.WHITE);
        title.setOnClickListener(v -> jump());
        title.setOnLongClickListener(v -> {
            if (active == null || active.location == null) return true;
            ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(
                    ClipData.newPlainText("目录路径", active.location.path()));
            Tasks.toast(this, "路径已复制"); return true;
        });
        title.setTooltipText("路径跳转");
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        subtitle = Ui.single(this, "文件管理", 11, 0xffaeb9b5);
        labels.addView(title, lp(-1, 29)); labels.addView(subtitle, lp(-1, 20));
        top.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        moreButton = Ui.icon(this, R.drawable.ic_more, "更多操作", Color.WHITE, this::more);
        top.addView(moreButton, lp(44, 54));
        root.addView(top, lp(-1, 58));
        permission = Ui.row(this);
        permission.setPadding(dp(12), 0, dp(6), 0); permission.setBackgroundColor(Ui.WARNING);
        TextView explain = Ui.text(this, "存储访问权限未开启", 12, Ui.TEXT);
        permission.addView(explain, new LinearLayout.LayoutParams(0, dp(40), 1));
        TextView allow = Ui.text(this, "开启权限", 12, Ui.ACCENT);
        allow.setPadding(dp(10), 0, dp(10), 0); allow.setOnClickListener(v -> storagePermission());
        permission.addView(allow, lp(-2, 40)); root.addView(permission);
        LinearLayout panes = new DualPaneLayout(this); panes.setBackgroundColor(Ui.SURFACE);
        left = new Pane(true); right = new Pane(false);
        panes.addView(left, new LinearLayout.LayoutParams(0, -1, 1));
        panes.addView(right, new LinearLayout.LayoutParams(0, -1, 1));
        root.addView(panes, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(Ui.line(this));
        status = Ui.single(this, "准备就绪", 10, Ui.MUTED);
        status.setPadding(dp(12), 0, dp(12), 0); status.setBackgroundColor(Ui.PANEL);
        root.addView(status, lp(-1, 20));
        status.setVisibility(View.GONE);
        actions = new DirectorySheet.SwipeLayout(this, true, this::directorySheet);
        actions.setOrientation(LinearLayout.HORIZONTAL); actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setContentDescription("底部操作栏"); actions.setBackgroundColor(Ui.SURFACE); root.addView(actions, lp(-1, 58));
        Ui.install(this, root);
        activate(left);
        restore(left, saved); restore(right, saved);
        if (saved != null) handledExternal = saved.getString("handledExternal");
        if (saved == null || ExternalFiles.uri(getIntent()) != null) navigateIntent(getIntent());
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent); externalGeneration++; handledExternal = null; navigateIntent(intent);
    }
    private void navigateIntent(Intent intent) {
        if (intent == null) return;
        Uri external = ExternalFiles.uri(intent);
        if (external != null) {
            String key = intent.getAction() + ":" + external;
            if (key.equals(handledExternal)) return;
            int token = ++externalGeneration;
            status.setText("读取外部文件…"); status.setVisibility(View.VISIBLE);
            Tasks.IO.execute(() -> {
                try {
                    ExternalFiles.Received received = ExternalFiles.receive(this, external, intent.getFlags());
                    runOnUiThread(() -> {
                        if (token != externalGeneration || isDestroyed() || isFinishing()) return;
                        handledExternal = key;
                        activate(left);
                        if (received.directory() != null) {
                            left.reveal = received.node().name;
                            left.navigate(FileStore.directory(received.directory(), received.node().root));
                        } else {
                            status.setVisibility(View.GONE);
                            Tasks.toast(this, "来源未提供可访问的实际目录，直接打开原文件");
                            open(received.node());
                        }
                    });
                } catch (Exception error) {
                    runOnUiThread(() -> {
                        if (token != externalGeneration || isDestroyed() || isFinishing()) return;
                        status.setText("外部文件读取失败"); Tasks.showError(this, error);
                    });
                }
            });
            return;
        }
        String path = intent.getStringExtra("navigatePath");
        if (path != null) { activate(left); left.navigate(FileStore.directory(new File(path), false)); }
    }
    private void restore(Pane pane, Bundle saved) {
        String key = pane.isLeft ? "left" : "right";
        String path = saved != null ? saved.getString(key) : getPreferences(0).getString("home-" + key,
                getPreferences(0).getString(key, null));
        try {
            if (path != null && path.startsWith("zip:")) {
                pane.location = store.fromKey(path);
            } else if (path != null && path.startsWith("content:")) {
                Uri tree = Uri.parse(path);
                pane.location = store.document(tree);
            } else if (path != null && path.startsWith("root:")) {
                pane.location = FileStore.directory(new File(path.substring(5)), true);
            } else pane.location = FileStore.directory(new File(path == null ?
                    Environment.getExternalStorageDirectory().getAbsolutePath() : path), false);
        } catch (Exception e) { pane.location = FileStore.directory(Environment.getExternalStorageDirectory(), false); }
        pane.reload();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("left", left.location.key()); out.putString("right", right.location.key());
        out.putString("handledExternal", handledExternal);
        if (pendingInstall != null) out.putString("pendingInstall", pendingInstall.getAbsolutePath());
    }
    @Override protected void onPause() {
        super.onPause();
        exitArmedAt = 0;
        getPreferences(0).edit().putString("left", left.location.key()).putString("right", right.location.key()).apply();
    }
    @Override protected void onResume() {
        super.onResume();
        if (permission != null) {
            permission.setVisibility(hasStorage() ? View.GONE : View.VISIBLE);
            if (resumed) { directorySnapshots.clear(); left.reload(); right.reload(); }
            resumed = true;
        }
        if (pendingInstall != null && getPackageManager().canRequestPackageInstalls()) {
            File file = pendingInstall; pendingInstall = null; startNormalInstall(file);
        }
    }
    private boolean hasStorage() {
        return Build.VERSION.SDK_INT >= 30 ? Environment.isExternalStorageManager() :
                checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }
    private void storagePermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            try { startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()))); }
            catch (ActivityNotFoundException e) { startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); }
        } else requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == 1) {
            permission.setVisibility(hasStorage() ? View.GONE : View.VISIBLE);
            left.reload(); right.reload();
        }
    }
    private int dp(int n) { return Ui.dp(this, n); }
    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w < 0 ? w : dp(w), h < 0 ? h : dp(h));
    }
    private void activate(Pane p) {
        if (active == p) return;
        active = p; left.indicator.setBackgroundColor(p == left ? Ui.ACCENT : Ui.LINE);
        right.indicator.setBackgroundColor(p == right ? Ui.ACCENT : Ui.LINE);
        header(); actionBar();
    }
    private void header() {
        if (active == null || active.location == null) return;
        int folders = active.folderCount;
        subtitle.setText("目录 " + folders + "  文件 " + (active.entries.size() - folders)
                + (active.location.file != null && !active.location.root ?
                " · 可用 " + FileStore.size(active.usableSpace) : ""));
        title.setText(busy ? "正在处理…" : active.location.path() + (active.location.file != null
                && !active.location.path().endsWith("/") ? "/" : ""));
        title.setContentDescription("双栈 Lab · " + active.location.path());
    }
    private void actionBar() {
        if (active == null) return;
        boolean selection = !active.selected.isEmpty();
        boolean rebuild = selectionActions == null || selectionActions != selection;
        selectionActions = selection;
        if (rebuild) actions.removeAllViews();
        status.setVisibility(active.selected.isEmpty() && !busy ? View.GONE : View.VISIBLE);
        if (selection) {
            if (rebuild) {
                addAction(R.drawable.ic_copy, "复制", () -> transfer(active, other(), false));
                addAction(R.drawable.ic_move, "移动", () -> transfer(active, other(), true));
                addAction(R.drawable.ic_trash, "删除", this::deleteSelection);
                addAction(R.drawable.ic_more, "操作", this::selectionMenu);
                addAction(R.drawable.ic_check, "清选", () -> { active.selected.clear(); active.adapter.notifyDataSetChanged(); actionBar(); });
            }
            status.setText("已选择 " + active.selected.size() + " 项 · " + (active == left ? "左 → 右" : "右 → 左"));
        } else {
            if (rebuild) {
                addAction(R.drawable.ic_back, "后退", () -> active.back());
                addAction(R.drawable.ic_back, "前进", () -> active.forward());
                actions.getChildAt(1).setRotation(180);
                addAction(R.drawable.ic_plus, "新建", this::createDialog);
                addAction(R.drawable.ic_swap, "交换", () -> {
                    FileStore.Node a = left.location, b = right.location; left.navigate(b); right.navigate(a);
                });
                addAction(R.drawable.ic_send, "同步另一窗口", this::synchronizeOtherPane);
            }
            actions.getChildAt(0).setEnabled(!active.backHistory.isEmpty());
            actions.getChildAt(0).setAlpha(active.backHistory.isEmpty() ? .28f : 1f);
            actions.getChildAt(1).setEnabled(!active.forwardHistory.isEmpty());
            actions.getChildAt(1).setAlpha(active.forwardHistory.isEmpty() ? .28f : 1f);
            actions.getChildAt(4).setRotation(active == left ? 0 : 180);
        }
    }
    private void synchronizeOtherPane() {
        if (active == null || active.location == null) return;
        Pane target = other();
        if (!target.location.key().equals(active.location.key())) target.navigate(active.location);
    }
    private void addAction(int icon, String text, Runnable task) {
        actions.addView(Ui.icon(this, icon, text, Ui.TEXT, task), new LinearLayout.LayoutParams(0, -1, 1));
    }
    private Pane other() { return active == left ? right : left; }
    private void places() {
        drawer = NavigationDrawer.show(this, () -> navigateDirectory(new File("/"), true),
                () -> active.navigate(FileStore.directory(Environment.getExternalStorageDirectory(), false)), this::installed);
    }
    private void navigateDirectory(File path, boolean preferRoot) {
        Pane target = active;
        Tasks.run(this, () -> RootAccess.directory(path, preferRoot), node -> {
            target.navigate(node);
            if (preferRoot) Tasks.toast(this, node.root ? "已使用 ROOT 访问" : "su 不可用，使用普通权限访问");
        });
    }
    private void directorySheet() {
        Pane target = active;
        if (target == null || target.location == null) return;
        sheet = new DirectorySheet(this, directoryPlaces, target.location, entry ->
                Tasks.run(this, () -> entry.resolve(store), node -> { activate(target); target.navigate(node); }));
        sheet.show();
    }
    private void chooseDirectory() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        File home = Environment.getExternalStorageDirectory();
        File current = active.location.file;
        if (current != null && current.getAbsolutePath().startsWith(home.getAbsolutePath() + "/")) {
            String relative = current.getAbsolutePath().substring(home.getAbsolutePath().length() + 1);
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents", "primary:" + relative));
        }
        startActivityForResult(intent, TREE + (active == left ? 0 : 1));
    }
    private void more() {
        String[] names = {"刷新", "搜索", "全选", "过滤", "排序方式", "打开终端", "隐藏文件", "添加书签", "设为首页", "交换窗口", "设置", "退出"};
        int[] icons = {R.drawable.ic_refresh, R.drawable.ic_search, R.drawable.ic_select_all, R.drawable.ic_filter,
                R.drawable.ic_sort, R.drawable.ic_terminal, R.drawable.ic_eye, R.drawable.ic_bookmark, R.drawable.ic_home,
                R.drawable.ic_swap, R.drawable.ic_settings, R.drawable.ic_exit};
        if (active.location.container != null) {
            names = Arrays.copyOf(names, names.length + 1); names[names.length - 1] = "解压当前目录";
            icons = Arrays.copyOf(icons, icons.length + 1); icons[icons.length - 1] = R.drawable.ic_download;
        }
        names = Arrays.copyOf(names, names.length + 1); names[names.length - 1] = "反选当前列表";
        icons = Arrays.copyOf(icons, icons.length + 1); icons[icons.length - 1] = R.drawable.ic_check;
        LinearLayout menu = Ui.column(this); menu.setBackgroundColor(Ui.SURFACE);
        ScrollView scroll = new ScrollView(this); scroll.addView(menu);
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        PopupWindow popup = new PopupWindow(scroll, Math.min(dp(232), Math.round(getResources().getDisplayMetrics().widthPixels * .56f)),
                Math.min(dp(names.length * 48), screenHeight - dp(100)), true);
        popupMenu = popup;
        popup.setBackgroundDrawable(Ui.background(Ui.SURFACE, dp(4))); popup.setElevation(dp(10)); popup.setOutsideTouchable(true);
        for (int i = 0; i < names.length; i++) {
            int which = i; LinearLayout row = Ui.row(this); row.setPadding(dp(14), 0, dp(12), 0);
            row.addView(Ui.image(this, icons[i], Ui.TEXT), lp(24, 24));
            TextView text = Ui.text(this, names[i], 16, Ui.TEXT); text.setPadding(dp(14), 0, 0, 0);
            row.addView(text, new LinearLayout.LayoutParams(0, dp(48), 1)); row.setBackground(Ui.ripple(Ui.SURFACE));
            row.setOnClickListener(v -> { popup.dismiss(); menuAction(which); }); menu.addView(row);
        }
        int[] location = new int[2]; moreButton.getLocationInWindow(location);
        popup.showAtLocation(getWindow().getDecorView(), Gravity.TOP | Gravity.END, dp(4), location[1]);
    }
    private void menuAction(int which) {
        switch (which) {
            case 0 -> active.reload(true);
            case 1 -> searchDialog();
            case 2 -> { for (FileStore.Node f : active.entries) active.selected.add(f.key()); active.adapter.notifyDataSetChanged(); actionBar(); }
            case 3 -> filterDialog();
            case 4 -> new AlertDialog.Builder(this).setTitle("排序方式").setSingleChoiceItems(new String[]{"名称", "时间", "大小"},
                    active.sort, (d, n) -> { active.sort = n; active.apply(); d.dismiss(); }).show();
            case 5 -> shellDialog();
            case 6 -> hiddenOptions();
            case 7 -> { directoryPlaces.bookmark(DirectoryPlaces.Entry.from(active.location)); Tasks.toast(this, "已添加书签"); }
            case 8 -> { getPreferences(0).edit().putString("home-" + (active == left ? "left" : "right"), active.location.key()).apply(); Tasks.toast(this, "已设为当前窗口首页"); }
            case 9 -> { FileStore.Node a = left.location, b = right.location; left.navigate(b); right.navigate(a); }
            case 10 -> settings();
            case 11 -> finish();
            case 12 -> {
                if (active.location.container != null) extractDialog(List.of(active.location));
                else invertVisibleSelection();
            }
            case 13 -> invertVisibleSelection();
        }
    }
    private void invertVisibleSelection() {
        for (FileStore.Node node : active.entries) {
            if (!showHidden && node.name.startsWith(".")) continue;
            if (!active.selected.remove(node.key())) active.selected.add(node.key());
        }
        active.adapter.notifyDataSetChanged(); actionBar();
    }
    private void hiddenOptions() {
        new AlertDialog.Builder(this).setTitle("隐藏文件").setSingleChoiceItems(new String[]{"显示隐藏文件", "不显示隐藏文件"},
                showHidden ? 0 : 1, (d, n) -> {
                    showHidden = n == 0; getPreferences(0).edit().putBoolean("showHidden", showHidden).apply();
                    left.apply(); right.apply(); d.dismiss();
                }).show();
    }
    private void settings() {
        new AlertDialog.Builder(this).setTitle("设置").setItems(new String[]{"主题", "隐藏文件", "恢复默认首页"}, (d, n) -> {
            if (n == 0) Ui.chooseTheme(this);
            else if (n == 1) hiddenOptions();
            else { getPreferences(0).edit().remove("home-left").remove("home-right").apply(); Tasks.toast(this, "首页设置已清除"); }
        }).show();
    }
    @Override public void onDestroy() {
        for (Pane pane : new Pane[]{left, right}) if (pane != null) {
            pane.generation++;
            if (pane.loading != null) pane.loading.cancel(false);
            pane.list.animate().cancel();
        }
        if (drawer != null) drawer.dismiss();
        if (sheet != null) sheet.dismiss();
        if (popupMenu != null) popupMenu.dismiss();
        super.onDestroy();
    }
    private void jump() {
        Pane pane = active;
        LinearLayout body = Ui.column(this);
        EditText input = Ui.input(this, "/storage/emulated/0/Download");
        input.setText(pane.location.file != null ? pane.location.path() : "");
        CheckBox rootMode = new CheckBox(this); rootMode.setText("使用 ROOT 访问");
        rootMode.setTextColor(Ui.TEXT); rootMode.setChecked(pane.location.root);
        body.addView(input); body.addView(rootMode);
        new AlertDialog.Builder(this).setTitle("路径跳转").setView(body)
                .setNegativeButton("取消", null).setPositiveButton("打开", (d, w) -> {
                    File f = new File(input.getText().toString().trim());
                    if (!f.isAbsolute()) { Tasks.toast(this, "请输入绝对路径"); return; }
                    pane.navigate(FileStore.directory(f, rootMode.isChecked()));
                }).show();
    }
    private void filterDialog() {
        Pane pane = active;
        LinearLayout body = Ui.column(this); EditText input = Ui.input(this, "文件名 / 正则表达式");
        input.setText(pane.filter);
        CheckBox regex = new CheckBox(this); regex.setText("正则表达式"); regex.setChecked(pane.regex);
        body.addView(input); body.addView(regex);
        new AlertDialog.Builder(this).setTitle("当前目录过滤").setView(body).setNegativeButton("取消", null)
                .setPositiveButton("应用", (d, w) -> {
                    try { if (regex.isChecked()) Pattern.compile(input.getText().toString()); }
                    catch (Exception e) { Tasks.toast(this, e.getMessage()); return; }
                    pane.filter = input.getText().toString(); pane.regex = regex.isChecked(); pane.apply();
                }).show();
    }
    private void createDialog() {
        Pane pane = active;
        LinearLayout body = Ui.column(this); EditText name = Ui.input(this, "名称");
        Spinner type = new Spinner(this);
        type.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"文件", "目录"}));
        body.addView(name); body.addView(type);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("新建").setView(body)
                .setNegativeButton("取消", null).setPositiveButton("创建", null).create();
        dialog.setOnShowListener(v -> dialog.getButton(-1).setOnClickListener(x -> {
            String value = name.getText().toString(); boolean folder = type.getSelectedItemPosition() == 1;
            mutate(() -> store.create(pane.location, value, folder), n -> { dialog.dismiss(); pane.reload(); });
        })); dialog.show();
    }
    private void selectionMenu() {
        Pane source = active;
        List<FileStore.Node> nodes = source.chosen();
        if (nodes.isEmpty()) return;
        FileStore.Node node = nodes.get(0);
        boolean single = nodes.size() == 1, files = nodes.stream().noneMatch(n -> n.directory);
        Pane target = source == left ? right : left;
        Dialog dialog = new Dialog(this); fileMenu = dialog;
        LinearLayout body = Ui.column(this); body.setBackgroundColor(Ui.SURFACE);
        LinearLayout heading = Ui.row(this); heading.setPadding(dp(12), 0, dp(2), 0);
        TextView hint = Ui.single(this, "带 • 的菜单可长按触发单窗口操作", 12, Ui.MUTED);
        heading.addView(hint, new LinearLayout.LayoutParams(0, dp(34), 1));
        TextView close = Ui.text(this, "×", 24, Ui.MUTED); close.setGravity(Gravity.CENTER);
        close.setContentDescription("关闭文件操作"); close.setBackground(Ui.ripple(Ui.SURFACE));
        Runnable clear = () -> { source.selected.clear(); source.adapter.notifyDataSetChanged(); actionBar(); };
        close.setOnClickListener(v -> { clear.run(); dialog.dismiss(); });
        heading.addView(close, lp(36, 34)); body.addView(heading); body.addView(Ui.line(this));
        String[] labels = {"复制", "移动", "删除", "重命名", "工具", "压缩", "属性", "分享", "打开方式…", "添加书签"};
        boolean extraction = nodes.stream().allMatch(n -> n.container != null)
                || single && !node.directory && (isArchive(node.name.toLowerCase(Locale.ROOT)) || ApkInstall.isApkName(node.name));
        if (extraction) labels[5] = "解压";
        int[] icons = {R.drawable.ic_copy, R.drawable.ic_cut, R.drawable.ic_trash, R.drawable.ic_edit,
                R.drawable.ic_tools, R.drawable.ic_download, R.drawable.ic_info, R.drawable.ic_share,
                R.drawable.ic_check, R.drawable.ic_bookmark};
        boolean[] enabled = {!busy, !busy, !busy, single && !busy, single, !busy, single, files,
                single && !node.directory, single && node.directory};
        Runnable[] run = {() -> transfer(nodes, source, target, false), () -> transfer(nodes, source, target, true),
                this::deleteSelection, () -> rename(node), () -> fileTools(node), () -> { if (extraction) extractDialog(nodes); else compress(nodes); },
                () -> properties(node), () -> shareFiles(nodes), () -> openWith(node),
                () -> { directoryPlaces.bookmark(DirectoryPlaces.Entry.from(node)); Tasks.toast(this, "已添加书签"); }};
        for (int row = 0; row < 5; row++) {
            LinearLayout line = Ui.row(this);
            for (int column = 0; column < 2; column++) {
                int index = row * 2 + column;
                LinearLayout cell = Ui.row(this); cell.setPadding(dp(12), 0, dp(6), 0);
                int color = enabled[index] ? Ui.TEXT : Ui.MUTED;
                ImageView icon = Ui.image(this, icons[index], color); cell.addView(icon, lp(24, 24));
                String text = index < 2 ? (source == left ? "→ " : "← ") + labels[index] : labels[index];
                TextView label = Ui.single(this, text, 17, color);
                LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-2, -1); layout.leftMargin = dp(12);
                cell.addView(label, layout);
                if (index < 2) { TextView dot = Ui.text(this, " •", 17, 0xff42a5f5); cell.addView(dot, lp(-2, -1)); }
                cell.setContentDescription("文件操作 " + labels[index]); cell.setFocusable(enabled[index]);
                cell.setBackground(Ui.ripple(Ui.SURFACE)); cell.setEnabled(enabled[index]);
                cell.setOnClickListener(v -> {
                    dialog.dismiss(); run[index].run();
                    if (index >= 4) clear.run();
                });
                if (index < 2) cell.setOnLongClickListener(v -> {
                    dialog.dismiss(); transferWithinPane(nodes, source, index == 1); return true;
                });
                line.addView(cell, new LinearLayout.LayoutParams(0, dp(58), 1));
            }
            body.addView(line);
        }
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(body);
        dialog.setContentView(scroll); dialog.setOnCancelListener(d -> clear.run());
        dialog.setOnDismissListener(d -> { if (fileMenu == dialog) fileMenu = null; }); dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(Ui.background(Ui.SURFACE, dp(2)));
            window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(440)), -2);
            window.setDimAmount(.12f);
        }
    }
    private void transferWithinPane(List<FileStore.Node> nodes, Pane source, boolean move) {
        EditText path = Ui.input(this, "目标目录完整路径"); path.setText(source.location.file != null ? source.location.path() : "");
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(move ? "单窗口移动" : "单窗口复制").setView(path)
                .setNegativeButton("取消", null).setPositiveButton(move ? "移动" : "复制", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(-1).setOnClickListener(v -> {
            File target = new File(path.getText().toString().trim());
            if (!target.isAbsolute()) { path.setError("请输入绝对路径"); return; }
            FileStore.Node destination = FileStore.directory(target, source.location.root);
            mutate(() -> { store.list(destination); for (FileStore.Node n : nodes) store.copy(n, destination, move); return true; }, done -> {
                dialog.dismiss(); source.selected.clear(); left.reload(); right.reload(); actionBar(); Tasks.toast(this, "操作完成");
            });
        })); dialog.show();
    }
    private void fileTools(FileStore.Node node) {
        String[] items = node.directory ? new String[]{"打开目录", "统计大小", "属性"} : new String[]{"打开", "执行 .sh / ELF", "SHA-256", "属性"};
        new AlertDialog.Builder(this).setTitle("工具").setItems(items, (d, index) -> {
            if (index == 0) { if (node.directory) active.navigate(node); else open(node); }
            else if (node.directory && index == 1) directoryStats(node);
            else if (node.directory) properties(node);
            else if (!node.directory && index == 1) executeFile(node);
            else if (!node.directory && index == 2) Tasks.run(this, () -> {
                java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                try (InputStream input = store.read(node)) { byte[] buffer = new byte[65536]; int size;
                    while ((size = input.read(buffer)) != -1) digest.update(buffer, 0, size); }
                StringBuilder hex = new StringBuilder(); for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
                return hex.toString();
            }, hash -> new AlertDialog.Builder(this).setTitle("SHA-256").setMessage(hash)
                    .setPositiveButton("复制", (x, w) -> ((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE))
                            .setPrimaryClip(ClipData.newPlainText(node.name, hash))).setNegativeButton("关闭", null).show());
            else properties(node);
        }).show();
    }
    private void directoryStats(FileStore.Node node) {
        AtomicBoolean cancelled = new AtomicBoolean();
        boolean[] completed = {false};
        TextView message = Ui.text(this, "正在统计目录内容…", 14, Ui.TEXT);
        message.setPadding(dp(20), dp(14), dp(20), dp(14));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("目录统计").setView(message)
                .setNegativeButton("停止", (d, which) -> cancelled.set(true))
                .setPositiveButton("关闭", null).create();
        dialog.setOnCancelListener(d -> cancelled.set(true));
        dialog.setOnDismissListener(d -> { if (!completed[0]) cancelled.set(true); });
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            Tasks.run(this, () -> DirectoryStats.measure(store, node, cancelled), result -> {
                completed[0] = true;
                message.setText("总大小  " + FileStore.size(result.bytes()) + "\n"
                        + "文件  " + result.files() + "    文件夹  " + result.directories()
                        + (result.unknownSizeFiles() == 0 ? "" : "\n" + result.unknownSizeFiles() + " 个文件的大小由提供方报告为未知/0"));
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
            }, error -> {
                if (cancelled.get()) { dialog.dismiss(); return; }
                completed[0] = true; dialog.dismiss(); Tasks.showError(this, error);
            });
        });
        dialog.show();
    }
    private void shareFiles(List<FileStore.Node> nodes) {
        Tasks.run(this, () -> {
            ArrayList<Uri> streams = new ArrayList<>();
            for (FileStore.Node node : nodes) {
                File file = ApkInstall.stageShared(this, node, "-" + node.name);
                streams.add(androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".files", file));
            }
            return streams;
        }, streams -> {
            Intent intent = new Intent(streams.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE)
                    .setType("*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (streams.size() == 1) intent.putExtra(Intent.EXTRA_STREAM, streams.get(0));
            else intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, streams);
            ClipData clip = ClipData.newRawUri("files", streams.get(0));
            for (int i = 1; i < streams.size(); i++) clip.addItem(new ClipData.Item(streams.get(i)));
            intent.setClipData(clip);
            try { startActivity(Intent.createChooser(intent, "分享文件")); } catch (Exception e) { Tasks.showError(this, e); }
        });
    }
    private void rename(FileStore.Node node) {
        Pane pane = active; EditText name = Ui.input(this, "文件名"); name.setText(node.name);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("重命名").setView(name)
                .setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(v -> dialog.getButton(-1).setOnClickListener(x ->
                mutate(() -> store.rename(node, name.getText().toString()), done -> {
                    dialog.dismiss(); pane.selected.clear(); pane.reload(); actionBar();
                }))); dialog.show();
    }
    private void properties(FileStore.Node node) {
        FilePermissions.show(this, node, date.format(new Date(node.modified)), () -> { left.reload(); right.reload(); });
    }
    private void deleteSelection() {
        Pane pane = active; List<FileStore.Node> nodes = pane.chosen();
        new AlertDialog.Builder(this).setTitle("删除 " + nodes.size() + " 项？")
                .setMessage("将永久删除所选文件及目录。").setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> mutate(() -> {
                    try { store.deleteAll(nodes); return nodes.size(); }
                    finally { runOnUiThread(() -> {
                        if (isDestroyed() || isFinishing()) return;
                        pane.selected.clear(); directorySnapshots.clear(); left.reload(); right.reload(); actionBar();
                    }); }
                }, n -> Tasks.toast(this, "已删除 " + n + " 项"))).show();
    }
    private void transfer(Pane source, Pane target, boolean move) {
        transfer(source.chosen(), source, target, move);
    }
    private void transfer(List<FileStore.Node> nodes, Pane source, Pane target, boolean move) {
        if (nodes.isEmpty()) { Tasks.toast(this, "先长按选择文件"); return; }
        FileStore.Node destination = target.location;
        mutate(() -> { for (FileStore.Node n : nodes) store.copy(n, destination, move); return nodes.size(); },
                n -> { source.selected.clear(); source.reload(); target.reload(); actionBar();
                    status.setText((move ? "移动" : "复制") + "完成 · " + n + " 项"); });
    }
    private <T> void mutate(Tasks.Job<T> job, Tasks.Done<T> done) {
        if (busy) { Tasks.toast(this, "请等待当前操作完成"); return; }
        busy = true; header();
        Tasks.run(this, () -> {
            try { return job.run(); } finally { runOnUiThread(() -> { busy = false; directorySnapshots.clear(); header(); }); }
        }, done);
    }
    private void compress(List<FileStore.Node> nodes) {
        Pane pane = active; EditText name = Ui.input(this, "归档名称"); name.setText("archive.zip");
        new AlertDialog.Builder(this).setTitle("压缩为 ZIP").setView(name).setNegativeButton("取消", null)
                .setPositiveButton("压缩", (d, w) -> {
                    String fileName = name.getText().toString();
                    mutate(() -> {
                        FileStore.checkName(fileName);
                        File tmp = File.createTempFile("pack-", ".zip", getCacheDir());
                        try {
                            ArchiveOps.pack(store, nodes, tmp);
                            FileStore.Node out = store.create(pane.location, fileName, false);
                            try { store.write(out, tmp); } catch (IOException e) { store.delete(out); throw e; }
                        } finally { java.nio.file.Files.deleteIfExists(tmp.toPath()); }
                        return true;
                    }, ok -> pane.reload());
                }).show();
    }
    private void extractDialog(List<FileStore.Node> nodes) {
        Pane source = active, target = other();
        FileStore.Node defaultDestination = target.location;
        if (defaultDestination.container != null) defaultDestination = FileStore.directory(Environment.getExternalStorageDirectory(), false);
        FileStore.Node initial = defaultDestination;
        LinearLayout body = Ui.column(this); body.setPadding(dp(16), dp(4), dp(16), 0);
        EditText path = Ui.input(this, "解压到目录"); path.setContentDescription("解压目标目录");
        path.setText(initial.path()); path.setEnabled(initial.file != null); body.addView(path);
        String base = nodes.size() == 1 ? nodes.get(0).name.replaceFirst("(?i)\\.(zip|rar|jar|apk(?:\\.1)?|apks|xapk)$", "") : "extracted";
        if (base.isBlank()) base = "extracted";
        EditText name = Ui.input(this, "新建文件夹"); name.setContentDescription("解压文件夹名称"); name.setText(base); body.addView(name);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("解压").setView(body)
                .setNegativeButton("取消", null).setPositiveButton("解压", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(-1).setOnClickListener(v -> {
            String folder = name.getText().toString().trim();
            try { FileStore.checkName(folder); } catch (IOException error) { name.setError(error.getMessage()); return; }
            FileStore.Node destination = initial;
            if (initial.file != null) {
                File value = new File(path.getText().toString().trim());
                if (!value.isAbsolute()) { path.setError("请输入绝对路径"); return; }
                destination = FileStore.directory(value, initial.root && initial.file.equals(value));
            }
            FileStore.Node outputParent = destination;
            mutate(() -> store.extract(nodes, outputParent, folder), output -> {
                dialog.dismiss(); source.selected.clear(); source.reload();
                target.reveal = output.name; target.navigate(outputParent);
                Tasks.toast(this, "解压完成 · " + output.path()); actionBar();
            });
        })); dialog.show();
    }
    private void open(FileStore.Node node) {
        String name = node.name.toLowerCase(Locale.ROOT);
        if (ApkInstall.isApkName(name)) { apkDialog(node); return; }
        if (isArchive(name)) { browseArchive(node); return; }
        if (node.container != null && name.endsWith(".xml")) {
            Tasks.run(this, () -> {
                try (InputStream input = store.read(node)) {
                    return com.reandroid.arsc.chunk.xml.ResXmlDocument.isResXmlBlock(FileStore.readBytes(input, 8));
                }
            }, binary -> { if (binary) structuredArchive(node, "xml"); else editText(node); }); return;
        }
        if (node.container != null && name.equals("resources.arsc")) { structuredArchive(node, "arsc"); return; }
        if (name.endsWith(".elf")) { executeFile(node); return; }
        if (name.endsWith(".sh")) {
            scriptDialog(node);
            return;
        }
        if (name.matches(".*\\.(png|jpg|jpeg|gif|webp|bmp)$")) { preview(node, "image"); return; }
        if (name.matches(".*\\.(mp3|wav|ogg|m4a|flac|aac)$")) { preview(node, "audio"); return; }
        if (name.matches(".*\\.(mp4|mkv|webm|3gp|avi)$")) { preview(node, "video"); return; }
        if (name.endsWith(".html") || name.endsWith(".htm")) { preview(node, "html"); return; }
        if (name.endsWith(".svg")) { preview(node, "svg"); return; }
        if (!isText(name) && !name.endsWith(".dex") && !name.endsWith(".arsc")) { openWith(node); return; }
        Class<?> type = name.endsWith(".dex") || name.endsWith(".arsc") ? ArchiveActivity.class : EditorActivity.class;
        Intent i = new Intent(this, type); node.put(i); startActivity(i);
    }
    private static boolean isArchive(String name) {
        return name.endsWith(".zip") || name.endsWith(".rar") || name.endsWith(".jar") || name.endsWith(".apks") || name.endsWith(".xapk");
    }
    private static boolean knownType(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return isText(lower) || isArchive(lower) || ApkInstall.isApkName(lower)
                || lower.matches(".*\\.(rar|elf|dex|arsc|png|jpg|jpeg|gif|webp|bmp|mp3|wav|ogg|m4a|flac|aac|mp4|mkv|webm|3gp|avi|htm|svg)$");
    }
    private void editText(FileStore.Node node) {
        Intent intent = new Intent(this, EditorActivity.class); node.put(intent); startActivity(intent);
    }
    private void archiveTools(FileStore.Node node) {
        Intent intent = new Intent(this, ArchiveActivity.class); node.put(intent); startActivity(intent);
    }
    private void structuredArchive(FileStore.Node node, String mode) {
        Tasks.run(this, () -> {
            File folder = java.nio.file.Files.createTempDirectory(getCacheDir().toPath(), "resource-edit-").toFile();
            File work = new File(folder, node.container.name);
            try (InputStream in = store.read(node.container); OutputStream out = new FileOutputStream(work)) { FileStore.pump(in, out); }
            return work;
        }, file -> {
            Intent intent = new Intent(this, EditorActivity.class); node.container.put(intent);
            intent.putExtra("archivePath", file.getAbsolutePath()).putExtra("archiveEntry", node.entry)
                    .putExtra("displayName", node.name).putExtra("mode", mode).putExtra("ownedArchive", true); startActivity(intent);
        });
    }
    private void browseArchive(FileStore.Node node) {
        Pane pane = active;
        FileStore.Node directory = FileStore.archiveNode(node, "", true, 0, 0);
        Tasks.run(this, () -> { store.list(directory); return directory; }, pane::navigate);
    }
    private void preview(FileStore.Node node, String kind) {
        Intent intent = new Intent(this, PreviewActivity.class); node.put(intent); intent.putExtra("kind", kind); startActivity(intent);
    }
    private void apkDialog(FileStore.Node node) {
        ApkDialog.show(this, node, () -> browseArchive(node), () -> archiveTools(node),
                () -> installApk(node, false), () -> installApk(node, true), path -> navigateDirectory(path, false));
    }
    private void installApk(FileStore.Node node, boolean silent) {
        AlertDialog progress = new AlertDialog.Builder(this).setTitle(silent ? "静默安装" : "准备安装")
                .setMessage("正在处理 " + node.name).setCancelable(false).create(); progress.show();
        Tasks.run(this, Tasks.SHELL, () -> {
            File file = ApkInstall.stage(this, node);
            if (getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), 0) == null) {
                file.delete(); throw new IOException("文件不是有效 APK");
            }
            if (silent) {
                try { ApkInstall.silent(file); } finally { file.delete(); }
            }
            return file;
        }, file -> {
            progress.dismiss();
            if (silent) { Tasks.toast(this, "安装成功"); return; }
            if (getPackageManager().canRequestPackageInstalls()) startNormalInstall(file);
            else new AlertDialog.Builder(this).setTitle("允许安装应用")
                    .setMessage("请在系统设置中允许双栈 Lab 安装应用，返回后继续安装。")
                    .setNegativeButton("取消", (d, w) -> file.delete()).setPositiveButton("打开设置", (d, w) -> {
                        pendingInstall = file;
                        try { startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))); }
                        catch (ActivityNotFoundException e) { pendingInstall = null; Tasks.showError(this, e); }
                    }).show();
        }, error -> { progress.dismiss(); Tasks.showError(this, error); });
    }
    private void startNormalInstall(File file) {
        try { startActivity(ApkInstall.normalIntent(this, file)); }
        catch (Exception e) { Tasks.showError(this, e); }
    }
    private void openWith(FileStore.Node node) {
        String[] labels = {"编辑文本", "浏览压缩包", "查看图片", "播放音乐", "播放视频", "Apk 信息", "浏览 APKS", "Linux 脚本", "HTML 功能", "Svg 功能", "其他应用", "选择文件"};
        int[] icons = {R.drawable.ic_file, R.drawable.ic_archive, R.drawable.ic_image, R.drawable.ic_music,
                R.drawable.ic_video, R.drawable.ic_package, R.drawable.ic_package, R.drawable.ic_terminal,
                R.drawable.ic_globe, R.drawable.ic_image, R.drawable.ic_send, R.drawable.ic_check};
        int[] colors = {0xff3c60a5, 0xff795548, 0xff379da0, 0xffd93b40, 0xffec9500, 0xff46a540,
                0xff795548, 0xff3c60a5, 0xff2186b6, 0xff379da0, 0xff568299, 0xff64942d};
        LinearLayout body = Ui.column(this); body.setPadding(dp(10), dp(6), dp(10), dp(8));
        EditText search = Ui.input(this, "筛选打开方式"); search.setVisibility(View.GONE); body.addView(search);
        GridLayout grid = new GridLayout(this); grid.setColumnCount(3); body.addView(grid);
        ScrollView scroll = new ScrollView(this); scroll.addView(body);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("打开方式…").setView(scroll)
                .setNeutralButton("筛选", null).setPositiveButton("关闭", null).create();
        Runnable populate = () -> {
            grid.removeAllViews(); String query = search.getText().toString().toLowerCase(Locale.ROOT);
            for (int i = 0; i < labels.length; i++) {
                if (!labels[i].toLowerCase(Locale.ROOT).contains(query)) continue;
                int action = i;
                LinearLayout cell = Ui.column(this); cell.setGravity(Gravity.CENTER); cell.setBackground(Ui.ripple(Ui.SURFACE));
                ImageView icon = Ui.image(this, icons[i], Color.WHITE); icon.setPadding(dp(12), dp(12), dp(12), dp(12));
                icon.setBackground(Ui.background(colors[i], dp(6))); cell.addView(icon, lp(48, 48));
                TextView text = Ui.text(this, labels[i], 12, Ui.TEXT); text.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams textParams = lp(-1, 28); textParams.topMargin = dp(5); cell.addView(text, textParams);
                cell.setContentDescription("打开方式 " + labels[i]);
                GridLayout.LayoutParams cellParams = new GridLayout.LayoutParams(); cellParams.width = 0; cellParams.height = dp(103);
                cellParams.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f); grid.addView(cell, cellParams);
                cell.setOnClickListener(v -> {
                    dialog.dismiss();
                    switch (action) {
                        case 0 -> editText(node);
                        case 1, 6 -> browseArchive(node);
                        case 2 -> preview(node, "image");
                        case 3 -> preview(node, "audio");
                        case 4 -> preview(node, "video");
                        case 5 -> apkDialog(node);
                        case 7 -> scriptDialog(node);
                        case 8 -> preview(node, "html");
                        case 9 -> preview(node, "svg");
                        case 10 -> externalOpen(node);
                        case 11 -> active.toggle(node);
                    }
                });
            }
        };
        watch(search, populate); populate.run(); dialog.show();
        dialog.getButton(-3).setOnClickListener(v -> { search.setVisibility(View.VISIBLE); search.requestFocus(); });
    }
    private void externalOpen(FileStore.Node node) {
        Tasks.run(this, () -> ApkInstall.stageShared(this, node, "-" + node.name), file -> {
            try {
                Uri uri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".files", file);
                startActivity(Intent.createChooser(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "*/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "打开方式"));
            } catch (Exception e) { Tasks.showError(this, e); }
        });
    }
    private void executeFile(FileStore.Node node) {
        if (node.directory) { Tasks.toast(this, "请选择 .sh 或 ELF 文件"); return; }
        if (node.name.toLowerCase(Locale.ROOT).endsWith(".sh")) { scriptDialog(node); return; }
        launchExecutable(node, node.root, false, true);
    }
    private void launchExecutable(FileStore.Node node, boolean root, boolean pipe, boolean autoRun) {
        Intent script = new Intent(this, TerminalActivity.class);
        node.put(script); script.putExtra("script", true);
        script.putExtra("executionRoot", root).putExtra("pipe", pipe).putExtra("autorun", autoRun);
        script.putExtra("forceScript", !node.name.toLowerCase(Locale.ROOT).endsWith(".elf"));
        if (node.file != null) script.putExtra("cwd", node.file.getParent());
        startActivity(script);
    }
    private void scriptDialog(FileStore.Node node) {
        android.content.SharedPreferences prefs = getSharedPreferences("script-execution", 0);
        boolean root = prefs.getBoolean("root", node.root), pipe = prefs.getBoolean("pipe", false);
        LinearLayout body = Ui.column(this); body.setPadding(dp(24), dp(8), dp(24), dp(12));
        TextView prompt = Ui.text(this, "请选择要执行的操作", 17, Ui.TEXT); body.addView(prompt);
        TextView mode = Ui.text(this, pipe ? "管道执行" : "普通执行", 13, Ui.MUTED);
        mode.setPadding(0, dp(10), 0, dp(4)); body.addView(mode);
        if (root) {
            TextView warning = Ui.text(this, "已选择使用 ROOT 权限执行，请确认脚本内容。", 13, 0xffd04b53);
            body.addView(warning);
        }
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(node.name).setView(body)
                .setNeutralButton("设置", (d, w) -> scriptSettings(node))
                .setNegativeButton("编辑", (d, w) -> {
                    Intent editor = new Intent(this, EditorActivity.class); node.put(editor); startActivity(editor);
                }).setPositiveButton("执行", (d, w) -> launchExecutable(node, root, pipe, true)).create();
        dialog.show();
    }
    private void scriptSettings(FileStore.Node node) {
        android.content.SharedPreferences prefs = getSharedPreferences("script-execution", 0);
        LinearLayout body = Ui.column(this); body.setPadding(dp(18), 0, dp(18), dp(8));
        CheckBox root = new CheckBox(this); root.setText("使用 ROOT 执行"); root.setChecked(prefs.getBoolean("root", node.root));
        RadioGroup methods = new RadioGroup(this);
        RadioButton normal = new RadioButton(this); normal.setId(View.generateViewId()); normal.setText("普通执行");
        RadioButton pipe = new RadioButton(this); pipe.setId(View.generateViewId()); pipe.setText("管道执行");
        methods.addView(normal); methods.addView(pipe); methods.check(prefs.getBoolean("pipe", false) ? pipe.getId() : normal.getId());
        body.addView(root); body.addView(methods);
        TextView note = Ui.text(this, "管道方式将脚本送入 Shell，运行时不能追加标准输入。", 12, Ui.MUTED);
        note.setPadding(dp(6), dp(8), dp(6), dp(4)); body.addView(note);
        new AlertDialog.Builder(this).setTitle("脚本执行设置").setView(body)
                .setNegativeButton("取消", (d, w) -> scriptDialog(node))
                .setPositiveButton("保存", (d, w) -> {
                    prefs.edit().putBoolean("root", root.isChecked()).putBoolean("pipe", methods.getCheckedRadioButtonId() == pipe.getId())
                            .remove("elfLaunchMode").apply();
                    scriptDialog(node);
                }).show();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == INSTALLED && result == RESULT_OK && data != null) {
            String path = data.getStringExtra("navigatePath");
            if (path != null) navigateDirectory(new File(path), data.getBooleanExtra("preferRoot", false));
            if (data.getBooleanExtra("extracted", false)) active.reload(true);
            return;
        }
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == TREE || request == TREE + 1) {
            try {
                int grants = Intent.FLAG_GRANT_READ_URI_PERMISSION;
                if ((data.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0)
                    grants |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                getContentResolver().takePersistableUriPermission(uri, grants);
                Uri document = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri));
                Pane pane = request == TREE ? left : right;
                Tasks.run(this, () -> store.document(document), pane::navigate);
            } catch (Exception e) { Tasks.toast(this, "无法保存授权：" + e.getMessage()); }
        } else if (request == ARTIFACT) Tasks.run(this, () -> store.document(uri), this::open);
    }
    private void installed() {
        Intent intent = new Intent(this, InstalledAppsActivity.class); active.location.put(intent);
        startActivityForResult(intent, INSTALLED);
    }
    private void shellDialog() {
        Intent intent = new Intent(this, ShellActivity.class);
        intent.putExtra("cwd", active.location.file == null ? getFilesDir().getAbsolutePath() : active.location.path());
        intent.putExtra("root", active.location.root); startActivity(intent);
    }
    private void searchDialog() {
        FileStore.Node start = active.location;
        LinearLayout body = Ui.column(this); EditText query = Ui.input(this, "文件名 / 内容");
        Spinner scope = new Spinner(this);
        scope.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"当前目录及子目录", "全部共享存储"}));
        CheckBox regex = new CheckBox(this); regex.setText("正则表达式");
        CheckBox content = new CheckBox(this); content.setText("搜索文本内容（每个文件最多 2 MiB）");
        body.addView(query); body.addView(scope); body.addView(regex); body.addView(content);
        new AlertDialog.Builder(this).setTitle("递归搜索 · 当前目录").setView(body).setNegativeButton("取消", null)
                .setPositiveButton("搜索", (d, w) -> {
                    String q = query.getText().toString(); if (q.isEmpty()) return;
                    Pattern pattern;
                    try { pattern = Pattern.compile(regex.isChecked() ? q : Pattern.quote(q), Pattern.CASE_INSENSITIVE); }
                    catch (Exception e) { Tasks.toast(this, e.getMessage()); return; }
                    boolean contents = content.isChecked();
                    FileStore.Node searchRoot = scope.getSelectedItemPosition() == 1 ?
                            FileStore.directory(Environment.getExternalStorageDirectory(), false) : start;
                    AtomicBoolean cancel = new AtomicBoolean();
                    TextView progress = Ui.text(this, "搜索中…", 13, Ui.TEXT); progress.setPadding(dp(20), dp(20), dp(20), dp(20));
                    AlertDialog waiting = new AlertDialog.Builder(this).setTitle("搜索").setView(progress)
                            .setNegativeButton("停止", (x, y) -> cancel.set(true)).create();
                    waiting.setOnCancelListener(x -> cancel.set(true)); waiting.show();
                    Tasks.run(this, () -> {
                        ArrayList<FileStore.Node> matches = new ArrayList<>();
                        ArrayDeque<FileStore.Node> queue = new ArrayDeque<>(); queue.add(searchRoot);
                        Set<String> visited = new HashSet<>(); int denied = 0;
                        while (!queue.isEmpty() && !cancel.get() && matches.size() < 2000) {
                            FileStore.Node dir = queue.remove();
                            String key = dir.file != null ? dir.file.getCanonicalPath() : dir.key();
                            if (!visited.add(key)) continue;
                            List<FileStore.Node> children;
                            try { children = store.list(dir); } catch (IOException e) { denied++; continue; }
                            for (FileStore.Node n : children) {
                                if (cancel.get()) break;
                                if (n.directory) queue.add(n);
                                boolean match = !contents && pattern.matcher(n.name).find();
                                if (contents && !n.directory && isText(n.name)) {
                                    try (InputStream in = store.read(n)) {
                                        byte[] bytes = FileStore.readBytes(in, 2 * 1024 * 1024);
                                        match = pattern.matcher(TextCodec.decode(bytes, null).text()).find();
                                    } catch (IOException e) { denied++; }
                                }
                                if (match) matches.add(n);
                                if (matches.size() >= 2000) break;
                            }
                        }
                        return new SearchResult(matches, denied, cancel.get(), matches.size() >= 2000);
                    }, result -> {
                        waiting.dismiss(); showSearch(result);
                    });
                }).show();
    }
    private record SearchResult(List<FileStore.Node> matches, int denied, boolean stopped, boolean limited) {}
    private void showSearch(SearchResult result) {
        ListView list = new ListView(this); List<String> labels = new ArrayList<>();
        for (FileStore.Node n : result.matches) labels.add(n.name + "\n" + n.path());
        list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels));
        String heading = result.matches.size() + " 项" + (result.stopped ? " · 已停止" : "")
                + (result.limited ? " · 达到 2000 项上限" : "") + (result.denied > 0 ? " · 跳过 " + result.denied + " 项" : "");
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(heading).setView(list).setPositiveButton("关闭", null).create();
        list.setOnItemClickListener((p, v, pos, id) -> {
            FileStore.Node n = result.matches.get(pos); dialog.dismiss();
            if (n.directory) active.navigate(n); else open(n);
        }); dialog.show();
    }
    static boolean isText(String name) {
        return name.toLowerCase(Locale.ROOT).matches(".*\\.(txt|xml|json|smali|java|kt|gradle|properties|md|js|html|css|yml|yaml|sh|ini|cfg|log|csv)$");
    }
    static void watch(EditText v, Runnable action) {
        v.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) { action.run(); }
            public void afterTextChanged(Editable e) {}
        });
    }
    @Override protected void handleBack() {
        if (fileMenu != null && fileMenu.isShowing()) { fileMenu.cancel(); return; }
        if (popupMenu != null && popupMenu.isShowing()) { popupMenu.dismiss(); return; }
        if (drawer != null && drawer.isShowing()) { drawer.dismiss(); return; }
        if (!active.selected.isEmpty()) { active.selected.clear(); active.adapter.notifyDataSetChanged(); actionBar(); exitArmedAt = 0; return; }
        FileStore.Node parent = StorageNavigation.parent(store, active.location);
        if (parent != null) { exitArmedAt = 0; active.up(); return; }
        if ((active.location.uri != null || active.location.container != null) && !active.backHistory.isEmpty()) {
            exitArmedAt = 0; active.back(); return;
        }
        long now = SystemClock.uptimeMillis();
        if (exitArmedAt != 0 && now - exitArmedAt < 2000) finish();
        else { exitArmedAt = now; Tasks.toast(this, "再按一次返回退出"); }
    }
    private final class Pane extends LinearLayout {
        final boolean isLeft;
        final View indicator;
        final TextView path, empty, summary;
        final SwipeSelectListView list;
        final SwipeRefreshLayout refresh;
        final Adapter adapter;
        final List<FileStore.Node> all = new ArrayList<>(), entries = new ArrayList<>();
        final Set<String> selected = new HashSet<>();
        final ArrayDeque<FileStore.Node> backHistory = new ArrayDeque<>(), forwardHistory = new ArrayDeque<>();
        FileStore.Node location;
        String filter = "";
        String reveal;
        boolean regex;
        volatile int generation;
        int sort, folderCount;
        java.util.concurrent.Future<?> loading;
        long usableSpace;
        String recordedDirectory;
        Map<String, String> metadata = Collections.emptyMap();
        final Map<String, int[]> positions = new LinkedHashMap<>();
        Pane(boolean isLeft) {
            super(MainActivity.this); this.isLeft = isLeft; setOrientation(VERTICAL); setBackgroundColor(Ui.SURFACE);
            indicator = new View(MainActivity.this); addView(indicator, lp(-1, 3));
            LinearLayout heading = Ui.row(MainActivity.this); heading.setPadding(dp(10), 0, dp(4), 0);
            heading.setBackgroundColor(Ui.PANEL);
            TextView label = Ui.text(MainActivity.this, isLeft ? "L" : "R", 10, Ui.ACCENT);
            label.setTypeface(null, android.graphics.Typeface.BOLD); heading.addView(label, lp(16, 30));
            path = Ui.single(MainActivity.this, "内部存储", 12, Ui.TEXT);
            heading.addView(path, new LinearLayout.LayoutParams(0, dp(30), 1));
            path.setOnClickListener(v -> { activate(this); jump(); });
            heading.addView(Ui.icon(MainActivity.this, R.drawable.ic_more, "窗口菜单", Ui.MUTED,
                    () -> { activate(this); more(); }), lp(30, 30));
            heading.setVisibility(GONE); addView(heading);
            FrameLayout frame = new FrameLayout(MainActivity.this);
            list = new SwipeSelectListView(MainActivity.this); list.setDivider(null); list.setFastScrollEnabled(false);
            list.setVerticalScrollBarEnabled(false);
            list.setScrollingCacheEnabled(false); list.setSmoothScrollbarEnabled(true);
            list.setContentDescription(isLeft ? "左窗口文件列表" : "右窗口文件列表");
            adapter = new Adapter(); list.setAdapter(adapter); frame.addView(list, new FrameLayout.LayoutParams(-1, -1));
            empty = Ui.text(MainActivity.this, "读取中…", 12, Ui.MUTED); empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(12), dp(12), dp(12), dp(12));
            frame.addView(empty, new FrameLayout.LayoutParams(-1, -1)); list.setEmptyView(empty);
            refresh = new SwipeRefreshLayout(MainActivity.this);
            refresh.setColorSchemeColors(Ui.ACCENT);
            refresh.addView(frame);
            refresh.setOnChildScrollUpCallback((parent, child) -> list.canScrollVertically(-1));
            refresh.setOnRefreshListener(() -> { activate(this); reload(true); });
            addView(refresh, new LinearLayout.LayoutParams(-1, 0, 1));
            list.setSelectionListener(new SwipeSelectListView.Selection() {
                public boolean selected(int position) {
                    return position > 0 && position <= entries.size() && selected.contains(entries.get(position - 1).key());
                }
                public void change(int first, int last, boolean checked) {
                    activate(Pane.this);
                    for (int i = Math.max(1, first); i <= Math.min(last, entries.size()); i++) {
                        String key = entries.get(i - 1).key();
                        if (checked) selected.add(key); else selected.remove(key);
                    }
                    adapter.notifyDataSetChanged(); actionBar();
                }
                public void gesture(boolean active) { refresh.setEnabled(!active); }
            });
            summary = Ui.single(MainActivity.this, "", 10, Ui.MUTED);
            summary.setPadding(dp(10), 0, dp(6), 0); summary.setBackgroundColor(Ui.PANEL);
            summary.setVisibility(GONE); addView(summary, lp(-1, 18));
            list.setOnTouchListener((v, e) -> { if (e.getAction() == MotionEvent.ACTION_DOWN) activate(this); return false; });
            list.setOnItemClickListener((p, v, pos, id) -> {
                if (!list.isEnabled()) return;
                activate(this); if (pos == 0) { up(); return; }
                FileStore.Node n = entries.get(pos - 1);
                if (!selected.isEmpty()) toggle(n); else if (n.directory) navigate(n); else open(n);
            });
            list.setOnItemLongClickListener((p, v, pos, id) -> {
                if (!list.isEnabled()) return true;
                activate(this);
                if (pos > 0) {
                    FileStore.Node node = entries.get(pos - 1);
                    if (!selected.contains(node.key())) selected.add(node.key());
                    adapter.notifyDataSetChanged(); actionBar(); selectionMenu();
                }
                return true;
            });
            list.setOnDragListener((v, event) -> {
                if (!(event.getLocalState() instanceof DragItems drag)) return false;
                if (event.getAction() == DragEvent.ACTION_DRAG_STARTED) { refresh.setEnabled(false); return true; }
                if (event.getAction() == DragEvent.ACTION_DRAG_ENDED) {
                    refresh.setEnabled(true); setBackgroundColor(Ui.SURFACE); return true;
                }
                if (drag.source == this) return false;
                switch (event.getAction()) {
                    case DragEvent.ACTION_DRAG_ENTERED -> setBackgroundColor(Ui.TINT);
                    case DragEvent.ACTION_DRAG_EXITED -> setBackgroundColor(Ui.SURFACE);
                    case DragEvent.ACTION_DROP -> new AlertDialog.Builder(MainActivity.this)
                            .setTitle("传入 " + drag.nodes.size() + " 项")
                            .setMessage(location.path()).setNegativeButton("取消", null)
                            .setNeutralButton("移动", (d, w) -> transfer(drag.nodes, drag.source, this, true))
                            .setPositiveButton("复制", (d, w) -> transfer(drag.nodes, drag.source, this, false)).show();
                }
                return true;
            });
        }
        void toggle(FileStore.Node n) {
            if (!selected.add(n.key())) selected.remove(n.key()); adapter.notifyDataSetChanged(); actionBar();
        }
        List<FileStore.Node> chosen() {
            List<FileStore.Node> out = new ArrayList<>();
            for (FileStore.Node n : all) if (selected.contains(n.key())) out.add(n); return out;
        }
        void navigate(FileStore.Node n) {
            exitArmedAt = 0;
            rememberPosition();
            if (location != null) backHistory.push(location); forwardHistory.clear();
            location = StorageNavigation.location(n); selected.clear(); filter = ""; reload(false, true, 1); if (active == this) actionBar();
        }
        void back() {
            if (backHistory.isEmpty()) return;
            exitArmedAt = 0;
            rememberPosition(); forwardHistory.push(location); location = backHistory.pop(); selected.clear(); filter = "";
            reload(false, true, -1); actionBar();
        }
        void forward() {
            if (forwardHistory.isEmpty()) return;
            exitArmedAt = 0;
            rememberPosition(); backHistory.push(location); location = forwardHistory.pop(); selected.clear(); filter = "";
            reload(false, true, 1); actionBar();
        }
        void up() {
            exitArmedAt = 0;
            FileStore.Node parent = StorageNavigation.parent(store, location);
            if (parent != null) {
                if (!backHistory.isEmpty() && backHistory.peek().key().equals(parent.key())) back();
                else navigate(parent);
            } else if (location.uri != null || location.container != null) back();
        }
        void rememberPosition() {
            if (location != null && location.key().equals(recordedDirectory) && list.getVisibility() == VISIBLE) {
                View first = list.getChildAt(0);
                positions.put(location.key(), new int[]{list.getFirstVisiblePosition(), first == null ? 0 : first.getTop()});
                if (positions.size() > 64) positions.remove(positions.keySet().iterator().next());
            }
        }
        void reload() {
            reload(false);
        }
        void reload(boolean feedback) {
            rememberPosition();
            if (location != null) directorySnapshots.remove(location.key());
            reload(feedback, false, 0);
        }
        void reload(boolean feedback, boolean allowCache, int direction) {
            if (location == null) return;
            location = RootAccess.cachedAccess(StorageNavigation.location(location));
            int token = ++generation; FileStore.Node current = location;
            if (loading != null) { loading.cancel(false); loading = null; }
            long started = SystemClock.uptimeMillis();
            list.stopForNavigation();
            if (feedback) refresh.setRefreshing(true);
            path.setText(current.file != null ? (current.path().equals("/storage/emulated/0") ? "内部存储" : current.name)
                    : current.name);
            path.setContentDescription(current.path()); path.setTooltipText(current.path());
            DirectorySnapshot cached = allowCache ? directorySnapshots.get(current.key()) : null;
            if (cached != null && started - cached.time < 15000) {
                show(current, cached, null, direction); refresh.setRefreshing(false); return;
            }
            if (direction != 0) {
                list.finishGesture(); list.setEnabled(false);
                empty.setText("读取中…");
                if (list.getChildCount() == 0) { list.setVisibility(GONE); empty.setVisibility(VISIBLE); }
                else {
                    refresh.postDelayed(() -> {
                        if (token == generation && !list.isEnabled()) refresh.setRefreshing(true);
                    }, 120);
                }
                if (active == this) header();
            }
            loading = Tasks.BROWSE.submit(() -> {
                if (token != generation || isDestroyed()) return;
                List<FileStore.Node> found; String error = null; FileStore.Node resolved = current;
                try { resolved = RootAccess.automatic(current); found = store.list(resolved); }
                catch (Exception e) { found = Collections.emptyList(); error = e.getMessage(); }
                Map<String, String> descriptions = new HashMap<>();
                SimpleDateFormat formatter = new SimpleDateFormat("yy-MM-dd HH:mm", Locale.CHINA);
                for (FileStore.Node n : found) descriptions.put(n.key(), formatter.format(new Date(n.modified))
                        + (n.directory ? "" : "  " + FileStore.size(n.size)));
                long space = resolved.file != null && !resolved.root ? resolved.file.getUsableSpace() : 0;
                List<FileStore.Node> result = found; String message = error; FileStore.Node loadedDirectory = resolved;
                runOnUiThread(() -> {
                    if (token != generation || isDestroyed()) return;
                    loading = null;
                    location = loadedDirectory;
                    int[] position = positions.get(current.key());
                    if (position != null) positions.put(loadedDirectory.key(), position);
                    if (feedback) {
                        refresh.postDelayed(() -> { if (token == generation) refresh.setRefreshing(false); },
                                Math.max(0, 380 - (SystemClock.uptimeMillis() - started)));
                    } else refresh.setRefreshing(false);
                    DirectorySnapshot snapshot = new DirectorySnapshot(result, descriptions, space, SystemClock.uptimeMillis());
                    if (message == null && result.size() <= 12000) {
                        if (directorySnapshots.size() >= 24) directorySnapshots.remove(directorySnapshots.keySet().iterator().next());
                        directorySnapshots.put(loadedDirectory.key(), snapshot);
                    }
                    show(loadedDirectory, snapshot, message, direction);
                });
            });
        }
        void show(FileStore.Node current, DirectorySnapshot snapshot, String message, int direction) {
                    list.setEnabled(true);
                    usableSpace = snapshot.space;
                    metadata = snapshot.metadata;
                    all.clear(); all.addAll(snapshot.nodes);
                    if (!selected.isEmpty()) {
                        Set<String> existing = new HashSet<>(); for (FileStore.Node n : all) existing.add(n.key());
                        selected.retainAll(existing);
                    }
                    apply();
                    empty.setText(message != null ? message : "此目录为空");
                    if (message != null) { status.setText("目录不可读取 · 请检查权限"); list.setVisibility(GONE); empty.setVisibility(VISIBLE); }
                    else {
                        list.setVisibility(VISIBLE);
                        empty.setVisibility(GONE);
                        int[] position = positions.get(current.key());
                        list.setSelectionFromTop(position == null ? 0 : Math.min(position[0], entries.size()), position == null ? 0 : position[1]);
                        if (reveal != null) {
                            for (int i = 0; i < entries.size(); i++) if (entries.get(i).name.equals(reveal)) {
                                list.setSelectionFromTop(i + 1, dp(12)); break;
                            }
                            reveal = null;
                        }
                        if (!current.key().equals(recordedDirectory)) {
                            directoryPlaces.visit(current); recordedDirectory = current.key();
                        }
                    }
                    if (active == this) actionBar();
        }
        void apply() {
            list.finishGesture();
            entries.clear(); Pattern pattern = null;
            if (regex && !filter.isEmpty()) {
                try { pattern = Pattern.compile(filter, Pattern.CASE_INSENSITIVE); }
                catch (Exception e) { status.setText("正则无效"); }
            }
            String lower = filter.toLowerCase(Locale.ROOT);
            for (FileStore.Node n : all) if ((showHidden || !n.name.startsWith(".")) && (filter.isEmpty() || (pattern != null ? pattern.matcher(n.name).find()
                    : n.name.toLowerCase(Locale.ROOT).contains(lower)))) entries.add(n);
            if (sort != 0) entries.sort((a, b) -> {
                if (a.directory != b.directory) return a.directory ? -1 : 1;
                return sort == 1 ? Long.compare(b.modified, a.modified) : sort == 2 ? Long.compare(b.size, a.size)
                        : a.name.compareToIgnoreCase(b.name);
            });
            adapter.notifyDataSetChanged(); int folders = 0;
            for (FileStore.Node n : entries) if (n.directory) folders++;
            folderCount = folders;
            summary.setText(folders + " 目录 · " + (entries.size() - folders) + " 文件" + (!filter.isEmpty() ? " · 过滤" : ""));
            if (active == this) header();
        }
        private final class Adapter extends BaseAdapter {
            public int getCount() { return entries.size() + 1; }
            public Object getItem(int pos) { return pos == 0 ? null : entries.get(pos - 1); }
            public long getItemId(int pos) { return pos; }
            public View getView(int pos, View reuse, ViewGroup parent) {
                FileStore.Node n = pos == 0 ? null : entries.get(pos - 1);
                Row holder;
                if (reuse == null) holder = new Row();
                else holder = (Row) reuse.getTag();
                boolean checked = n != null && selected.contains(n.key());
                holder.node = n;
                holder.background.setColor(checked ? Ui.TINT : Ui.SURFACE);
                boolean apk = n != null && ApkInstall.isApkName(n.name);
                boolean archive = n != null && isArchive(n.name.toLowerCase(Locale.ROOT));
                int color = n == null || n.directory ? Ui.ICON_BG : apk ? Ui.ACCENT : archive ? Ui.GOLD : 0xff4374b3;
                int drawable = checked ? R.drawable.ic_check : n == null ? R.drawable.ic_folder : n.directory ? R.drawable.ic_folder :
                        apk ? R.drawable.ic_package : archive ? R.drawable.ic_archive : R.drawable.ic_file;
                if (holder.drawable != drawable) { holder.icon.setImageResource(drawable); holder.drawable = drawable; }
                holder.iconBackground.setColor(color);
                holder.icon.setContentDescription(n == null ? "上级目录" : "选择 " + n.name);
                holder.icon.setSelected(checked);
                holder.name.setText(n == null ? ".." : n.name);
                holder.meta.setText(n == null ? "" : metadata.get(n.key()));
                holder.meta.setVisibility(n == null ? View.GONE : View.VISIBLE);
                return holder.row;
            }
            private final class Row {
                final LinearLayout row = Ui.row(MainActivity.this);
                final ImageView icon = Ui.image(MainActivity.this, R.drawable.ic_folder, Color.WHITE);
                final TextView name = Ui.single(MainActivity.this, "", 13, Ui.TEXT);
                final TextView meta = Ui.single(MainActivity.this, "", 10, Ui.MUTED);
                final android.graphics.drawable.GradientDrawable background = Ui.background(Ui.SURFACE, 0);
                final android.graphics.drawable.GradientDrawable iconBackground = Ui.background(Ui.ICON_BG, dp(5));
                FileStore.Node node;
                int drawable;
                Row() {
                    row.setPadding(dp(5), dp(2), dp(5), dp(2));
                    row.setLayoutParams(new AbsListView.LayoutParams(-1, dp(41))); row.setTag(this);
                    row.setBackground(new android.graphics.drawable.RippleDrawable(
                            android.content.res.ColorStateList.valueOf(0x227f9991), background, null));
                    icon.setPadding(dp(5), dp(5), dp(5), dp(5)); icon.setBackground(iconBackground);
                    row.addView(icon, lp(28, 28));
                    LinearLayout text = Ui.column(MainActivity.this); name.setEllipsize(TextUtils.TruncateAt.END);
                    text.addView(name, lp(-1, 21)); text.addView(meta, lp(-1, 15));
                    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1); p.leftMargin = dp(5);
                    row.addView(text, p);
                    icon.setOnClickListener(v -> {
                        if (!list.isEnabled()) return;
                        activate(Pane.this); if (node == null) up(); else toggle(node);
                    });
                    icon.setOnLongClickListener(v -> {
                        if (node == null || !list.isEnabled()) return false;
                        activate(Pane.this);
                        List<FileStore.Node> nodes = selected.contains(node.key()) ? chosen() : List.of(node);
                        refresh.setEnabled(false);
                        if (!icon.startDragAndDrop(ClipData.newPlainText("files", node.name),
                                new View.DragShadowBuilder(row), new DragItems(Pane.this, nodes), 0)) refresh.setEnabled(true);
                        return true;
                    });
                }
            }
        }
    }
    private record DragItems(Pane source, List<FileStore.Node> nodes) {}
}
