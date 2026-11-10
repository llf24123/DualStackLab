package com.dualstack.lab;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.os.*;
import android.widget.*;
import android.view.*;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.reandroid.arsc.chunk.xml.ResXmlDocument;
import org.jf.dexlib2.*;
import org.jf.dexlib2.iface.*;
import org.jf.baksmali.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;

public class ArchiveActivity extends LabActivity {
    private static final int IMPORT = 301, OUTPUT = 302, EDIT = 303;
    private FileStore store;
    private FileStore.Node source;
    private File archive, pendingOutput;
    // A revision belongs to the bytes in archive, never to a later observation of source.
    private final Object workspaceLock = new Object();
    private String sourceRevision;
    private record ClassPreview(File file, String revision, String archivePath) {}
    private String prefix = "", replacement, dexEntry;
    private TextView path, summary;
    private ListView list;
    private SwipeRefreshLayout refresh;
    private int reloadGeneration;
    private final List<ArchiveOps.Item> items = new ArrayList<>();
    private BaseAdapter adapter;
    private boolean busy, rawDex, rar;
    private final Map<String, File> projects = new HashMap<>();
    private LinearLayout tools, browser;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new FileStore(this); LinearLayout root = Ui.column(this);
        LinearLayout top = Ui.toolbar(this, getIntent().getStringExtra("name"), this::finish);
        View toolButton = Ui.icon(this, R.drawable.ic_more, "APK 工具", Color.WHITE, this::toolMenu);
        top.addView(toolButton, lp(48, 52));
        root.addView(top, lp(-1, 52));
        LinearLayout tabs = Ui.row(this); tabs.setBackgroundColor(Ui.PANEL);
        TextView files = tab("归档", () -> { if (!rawDex) { browser.setVisibility(View.VISIBLE); tools.setVisibility(View.GONE); } });
        TextView apk = tab("APK 工具", () -> { browser.setVisibility(View.GONE); tools.setVisibility(View.VISIBLE); });
        tabs.addView(files, new LinearLayout.LayoutParams(0, dp(48), 1));
        tabs.addView(apk, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(tabs); root.addView(Ui.line(this));
        browser = Ui.column(this); browser.setBackgroundColor(Ui.SURFACE);
        LinearLayout location = Ui.row(this); location.setPadding(dp(10), 0, dp(8), 0);
        location.addView(Ui.icon(this, R.drawable.ic_up, "上级目录", Ui.ACCENT, this::up), lp(48, 48));
        path = Ui.single(this, "/", 12, Ui.TEXT); location.addView(path, new LinearLayout.LayoutParams(0, dp(48), 1));
        browser.addView(location); browser.addView(Ui.line(this));
        list = new ListView(this); list.setDivider(null);
        adapter = new BaseAdapter() {
            public int getCount() { return items.size(); }
            public Object getItem(int n) { return items.get(n); }
            public long getItemId(int n) { return n; }
            public View getView(int n, View reuse, ViewGroup parent) {
                ArchiveOps.Item item = items.get(n);
                ArchiveRow row = reuse == null ? new ArchiveRow() : (ArchiveRow) reuse.getTag();
                row.icon.setImageResource(item.directory() ? R.drawable.ic_folder :
                        item.name().endsWith(".dex") ? R.drawable.ic_package : R.drawable.ic_file);
                row.background.setColor(item.directory() ? Ui.ICON_BG : item.name().endsWith(".dex") ? Ui.ACCENT : 0xff4374b3);
                row.name.setText(item.name()); row.meta.setText(item.directory() ? "目录" : FileStore.size(item.size()));
                return row.row;
            }
        };
        list.setAdapter(adapter);
        refresh = new SwipeRefreshLayout(this); refresh.setColorSchemeColors(Ui.ACCENT);
        refresh.addView(list); refresh.setOnRefreshListener(() -> reload(true));
        browser.addView(refresh, new LinearLayout.LayoutParams(-1, 0, 1));
        summary = Ui.single(this, "读取中…", 11, Ui.MUTED); summary.setPadding(dp(14), 0, dp(12), 0);
        browser.addView(summary, lp(-1, 30));
        LinearLayout commands = Ui.row(this); commands.setBackgroundColor(Ui.SURFACE);
        command(commands, R.drawable.ic_plus, "添加", this::importFile);
        command(commands, R.drawable.ic_folder, "新建", this::newEntry);
        command(commands, R.drawable.ic_download, "解压", () -> extract(prefix));
        command(commands, R.drawable.ic_refresh, "刷新", () -> reload(true));
        browser.addView(Ui.line(this)); browser.addView(commands, lp(-1, 58));
        root.addView(browser, new LinearLayout.LayoutParams(-1, 0, 1));
        tools = Ui.column(this); tools.setBackgroundColor(Ui.SURFACE);
        String[] labels = {"APK 信息与 SHA-256", "查看 / 编辑 AndroidManifest.xml", "编辑 ARSC 资源表",
                "DEX 类浏览", "DEX 反汇编到工作区", "Smali 回编译并写回 DEX", "对齐并签名 APK",
                "仅对齐 APK", "验证 APK 签名", "制作共存 APK", "导出当前归档"};
        int[] icons = {R.drawable.ic_package, R.drawable.ic_edit, R.drawable.ic_file, R.drawable.ic_search,
                R.drawable.ic_download, R.drawable.ic_save, R.drawable.ic_check, R.drawable.ic_refresh,
                R.drawable.ic_check, R.drawable.ic_copy, R.drawable.ic_download};
        for (int i = 0; i < labels.length; i++) {
            final int action = i;
            LinearLayout row = Ui.row(this); row.setPadding(dp(12), 0, dp(16), 0); row.setBackground(Ui.ripple(Ui.SURFACE));
            row.addView(Ui.image(this, icons[i], Ui.ACCENT), lp(36, 44));
            row.addView(Ui.text(this, labels[i], 14, Ui.TEXT), new LinearLayout.LayoutParams(0, dp(48), 1));
            row.setOnClickListener(v -> tool(action)); tools.addView(row); tools.addView(Ui.line(this));
        }
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(tools);
        tools.setVisibility(View.GONE);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        scroll.setVisibility(View.GONE);
        apk.setOnClickListener(v -> { browser.setVisibility(View.GONE); scroll.setVisibility(View.VISIBLE); tools.setVisibility(View.VISIBLE); });
        files.setOnClickListener(v -> { if (!rawDex) { browser.setVisibility(View.VISIBLE); scroll.setVisibility(View.GONE); } });
        list.setOnItemClickListener((p, v, pos, id) -> {
            ArchiveOps.Item item = items.get(pos);
            if (item.directory()) { prefix = item.path(); reload(); } else open(item);
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> { itemMenu(items.get(pos)); return true; });
        Ui.install(this, root);
        Tasks.run(this, () -> {
            source = store.fromIntent(getIntent()); rawDex = source.name.toLowerCase(Locale.ROOT).endsWith(".dex");
            if (source.name.toLowerCase(Locale.ROOT).endsWith(".arsc")) {
                synchronized (workspaceLock) { sourceRevision = store.fingerprint(source); }
                return null;
            }
            File dir = Files.createTempDirectory(getCacheDir().toPath(), "archive-").toFile();
            File copy = new File(dir, source.name.replace('/', '_'));
            synchronized (workspaceLock) {
                try {
                    sourceRevision = copySource(copy, false, "");
                    archive = copy;
                } catch (Exception error) {
                    Files.deleteIfExists(copy.toPath()); Files.deleteIfExists(dir.toPath()); throw error;
                }
            }
            return copy;
        }, copy -> {
            if (copy == null) {
                Intent i = new Intent(this, EditorActivity.class); source.put(i); i.putExtra("mode", "arsc-file");
                i.putExtra("sourceRevision", sourceRevision);
                startActivity(i); finish(); return;
            }
            rar = ArchiveOps.isRar(copy) || ArchiveFormats.supported(copy);
            if (rar) {
                apk.setVisibility(View.GONE); toolButton.setVisibility(View.GONE);
                commands.getChildAt(0).setVisibility(View.GONE);
                commands.getChildAt(1).setVisibility(View.GONE);
            }
            if (rawDex) {
                browser.setVisibility(View.GONE); scroll.setVisibility(View.VISIBLE); tools.setVisibility(View.VISIBLE); dexEntry = "";
                dexBrowser("");
            } else reload();
        });
    }
    private final class ArchiveRow {
        final LinearLayout row = Ui.row(ArchiveActivity.this);
        final ImageView icon = Ui.image(ArchiveActivity.this, R.drawable.ic_file, Color.WHITE);
        final TextView name = Ui.single(ArchiveActivity.this, "", 14, Ui.TEXT);
        final TextView meta = Ui.single(ArchiveActivity.this, "", 11, Ui.MUTED);
        final android.graphics.drawable.GradientDrawable background = Ui.background(Ui.ICON_BG, dp(5));
        ArchiveRow() {
            row.setTag(this); row.setPadding(dp(14), dp(5), dp(14), dp(5));
            row.setLayoutParams(new AbsListView.LayoutParams(-1, dp(58)));
            icon.setPadding(dp(6), dp(6), dp(6), dp(6)); icon.setBackground(background); row.addView(icon, lp(34, 36));
            LinearLayout text = Ui.column(ArchiveActivity.this); text.addView(name, lp(-1, 25)); text.addView(meta, lp(-1, 17));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1); p.leftMargin = dp(12); row.addView(text, p);
        }
    }
    private int dp(int n) { return Ui.dp(this, n); }
    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w < 0 ? w : dp(w), h < 0 ? h : dp(h));
    }
    private TextView tab(String text, Runnable task) {
        TextView v = Ui.text(this, text, 13, Ui.ACCENT); v.setGravity(Gravity.CENTER); v.setBackground(Ui.ripple(Ui.PANEL));
        v.setOnClickListener(x -> task.run()); return v;
    }
    private void command(LinearLayout bar, int icon, String text, Runnable task) {
        bar.addView(Ui.command(this, icon, text, task), new LinearLayout.LayoutParams(0, -1, 1));
    }
    /** Caller holds workspaceLock; publish only bytes observed under one stable source revision. */
    private String copySource(File destination, boolean validate, String current) throws IOException {
        File staged = File.createTempFile("source-", "-" + destination.getName(), destination.getParentFile());
        try {
            String before = store.fingerprint(source);
            try (InputStream input = store.read(source); OutputStream output = new FileOutputStream(staged)) {
                FileStore.pump(input, output);
            }
            if (source.file != null) ArchiveOps.copyPassword(source.file, staged);
            if (validate && !rawDex) ArchiveOps.list(staged, current);
            if (!before.equals(store.fingerprint(source))) throw revisionConflict();
            FileStore.replace(staged, destination);
            ArchiveOps.forget(destination);
            if (source.file != null) ArchiveOps.copyPassword(source.file, destination);
            return before;
        } finally {
            ArchiveOps.clearPassword(staged); ArchiveOps.forget(staged);
            Files.deleteIfExists(staged.toPath());
        }
    }
    private IOException revisionConflict() {
        return new IOException("原文件已变化或工作副本已失效，请刷新或重新打开后再修改；旧工作区不会自动合并");
    }
    private void requireRevision() throws IOException {
        if (sourceRevision == null || !sourceRevision.equals(store.fingerprint(source))) throw revisionConflict();
    }
    private void reload() {
        reload(false);
    }
    private void reload(boolean readSource) {
        if (archive == null || (rawDex && !readSource)) { if (refresh != null) refresh.setRefreshing(false); return; }
        if (readSource && busy) { refresh.setRefreshing(false); return; }
        int token = ++reloadGeneration;
        long started = SystemClock.uptimeMillis();
        if (readSource) refresh.setRefreshing(true);
        String current = prefix;
        Tasks.run(this, () -> {
            synchronized (workspaceLock) {
                if (readSource) sourceRevision = copySource(archive, true, current);
                return rawDex ? Collections.<ArchiveOps.Item>emptyList() : ArchiveOps.list(archive, current);
            }
        }, found -> {
            if (token != reloadGeneration || !current.equals(prefix)) return;
            if (readSource) {
                refresh.postDelayed(() -> { if (token == reloadGeneration) refresh.setRefreshing(false); },
                        Math.max(0, 380 - (SystemClock.uptimeMillis() - started)));
                list.animate().cancel(); list.setAlpha(1f);
            } else refresh.setRefreshing(false);
            items.clear(); items.addAll(found); adapter.notifyDataSetChanged();
            path.setText("/" + prefix); summary.setText(items.size() + " 项 · " + FileStore.size(archive.length()));
        }, error -> { if (token == reloadGeneration) { refresh.setRefreshing(false); Tasks.showError(this, error); } });
    }
    private void refreshChangedSource() {
        if (archive == null || busy) return;
        Tasks.run(this, () -> {
            synchronized (workspaceLock) { return !Objects.equals(sourceRevision, store.fingerprint(source)); }
        }, changed -> { if (changed) reload(true); });
    }
    private void up() {
        if (prefix.isEmpty()) return;
        String p = prefix.substring(0, prefix.length() - 1);
        int slash = p.lastIndexOf('/'); prefix = slash < 0 ? "" : p.substring(0, slash + 1); reload();
    }
    private void open(ArchiveOps.Item item) {
        if (rar) {
            FileStore.Node entry = FileStore.archiveNode(source, item.path(), false, item.size(), item.modified());
            String name = item.name().toLowerCase(Locale.ROOT);
            String kind = name.matches(".*\\.(png|jpg|jpeg|gif|webp|bmp)$") ? "image"
                    : name.matches(".*\\.(mp3|wav|ogg|m4a|flac|aac)$") ? "audio"
                    : name.matches(".*\\.(mp4|mkv|webm|3gp|avi)$") ? "video"
                    : name.endsWith(".html") || name.endsWith(".htm") ? "html"
                    : name.endsWith(".svg") ? "svg" : null;
            if (kind != null) {
                Intent preview = new Intent(this, PreviewActivity.class); entry.put(preview);
                preview.putExtra("kind", kind); startActivity(preview); return;
            }
            Tasks.run(this, () -> {
                synchronized (workspaceLock) {
                    if (sourceRevision == null) throw revisionConflict();
                    Intent editor = new Intent(this, EditorActivity.class); entry.put(editor);
                    return editor.putExtra("displayName", item.path()).putExtra("readOnly", true)
                            .putExtra("sourceRevision", sourceRevision);
                }
            }, this::startActivity); return;
        }
        String name = item.name().toLowerCase(Locale.ROOT);
        if (name.endsWith(".dex")) { dexEntry = item.path(); dexBrowser(item.path()); return; }
        if (name.equals("resources.arsc")) { edit(item.path(), "arsc"); return; }
        if (name.endsWith(".xml")) {
            Tasks.run(this, () -> {
                synchronized (workspaceLock) {
                    boolean binary = ResXmlDocument.isResXmlBlock(ArchiveOps.bytes(archive, item.path(), EditorActivity.LIMIT));
                    return editorIntent(item.path(), binary ? "xml" : null);
                }
            }, this::openEditor); return;
        }
        edit(item.path(), null);
    }
    /** Caller holds workspaceLock so intent metadata describes the selected workspace. */
    private Intent editorIntent(String entry, String mode) throws IOException {
        if (sourceRevision == null) throw revisionConflict();
        Intent i = new Intent(this, EditorActivity.class); source.put(i);
        return i.putExtra("archivePath", archive.getAbsolutePath()).putExtra("archiveEntry", entry)
                .putExtra("displayName", entry).putExtra("mode", mode).putExtra("sourceRevision", sourceRevision);
    }
    private void edit(String entry, String mode) {
        Tasks.run(this, () -> {
            synchronized (workspaceLock) { return editorIntent(entry, mode); }
        }, this::openEditor);
    }
    private void openEditor(Intent intent) { startActivityForResult(intent, EDIT); }
    private void itemMenu(ArchiveOps.Item item) {
        String[] options = rar ? new String[]{"打开", "解压此项"}
                : new String[]{"打开", "解压此项", "替换资源", "重命名", "删除"};
        new AlertDialog.Builder(this).setTitle(item.name()).setItems(options, (d, n) -> {
                    switch (n) {
                        case 0 -> { if (item.directory()) { prefix = item.path(); reload(); } else open(item); }
                        case 1 -> extract(item.path());
                        case 2 -> {
                            if (item.directory()) { Tasks.toast(this, "请选择文件"); return; }
                            replacement = item.path(); pickImport();
                        }
                        case 3 -> rename(item);
                        case 4 -> new AlertDialog.Builder(this).setTitle("删除 " + item.name() + "？")
                                .setMessage("将永久删除归档中的此项。")
                                .setNegativeButton("取消", null).setPositiveButton("删除", (x, w) -> change(() -> {
                                    ArchiveOps.rewrite(archive, item.path(), null, Collections.emptyMap()); return true;
                                })).show();
                    }
                }).show();
    }
    private void rename(ArchiveOps.Item item) {
        EditText name = Ui.input(this, "名称"); name.setText(item.name());
        new AlertDialog.Builder(this).setTitle("重命名").setView(name).setNegativeButton("取消", null)
                .setPositiveButton("保存", (d, w) -> {
                    String text = name.getText().toString();
                    change(() -> {
                        FileStore.checkName(text);
                        String to = prefix + text + (item.directory() ? "/" : "");
                        for (ArchiveOps.Item n : ArchiveOps.list(archive, prefix))
                            if (n.path().equals(to)) throw new IOException("同名条目已存在");
                        ArchiveOps.rewrite(archive, item.path(), to, Collections.emptyMap()); return true;
                    });
                }).show();
    }
    private void newEntry() {
        if (rar) { Tasks.toast(this, "此归档格式当前为只读浏览"); return; }
        LinearLayout body = Ui.column(this); EditText name = Ui.input(this, "名称"); body.addView(name);
        CheckBox folder = new CheckBox(this); folder.setText("目录"); body.addView(folder);
        new AlertDialog.Builder(this).setTitle("新建归档条目").setView(body).setNegativeButton("取消", null)
                .setPositiveButton("创建", (d, w) -> {
                    String text = name.getText().toString(); boolean directory = folder.isChecked();
                    change(() -> {
                        FileStore.checkName(text);
                        String entry = prefix + text + (directory ? "/" : "");
                        for (ArchiveOps.Item n : ArchiveOps.list(archive, prefix))
                            if (n.name().equals(text)) throw new IOException("同名条目已存在");
                        Map<String, File> map = new HashMap<>(); map.put(entry, null);
                        ArchiveOps.rewrite(archive, null, null, map); return true;
                    });
                }).show();
    }
    private void importFile() { replacement = null; pickImport(); }
    private void pickImport() {
        if (archive == null || busy) return;
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                .addCategory(Intent.CATEGORY_OPENABLE), IMPORT);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == EDIT) { if (result == RESULT_OK) reload(true); return; }
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (request == IMPORT) {
            String target = replacement, current = prefix;
            change(() -> {
                FileStore.Node imported = store.document(data.getData()); File file = store.materialize(imported);
                try {
                    String entry = target == null ? current + imported.name : target;
                    if (target == null) {
                        if (ArchiveOps.containsEntry(archive, entry))
                    throw new IOException("条目已存在，请使用“替换资源”");
                    }
                    ArchiveOps.rewrite(archive, null, null, Map.of(entry, file)); return true;
                } finally { Files.deleteIfExists(file.toPath()); }
            });
        } else if (request == OUTPUT && pendingOutput != null) {
            File file = pendingOutput;
            Tasks.run(this, () -> {
                try (InputStream in = new FileInputStream(file);
                     OutputStream out = getContentResolver().openOutputStream(data.getData(), "wt")) {
                    if (out == null) throw new IOException("输出不可写"); FileStore.pump(in, out);
                }
                return true;
            }, ok -> { Tasks.toast(this, "已导出"); pendingOutput = null; });
        }
    }
    private <T> void change(Tasks.Job<T> job) {
        if (rar) { Tasks.toast(this, "此归档格式当前为只读浏览"); return; }
        if (busy) { Tasks.toast(this, "请等待当前操作"); return; }
        busy = true; refresh.setEnabled(false); summary.setText("处理并写回中…");
        Tasks.run(this, () -> applyChange(job), result -> {
            finishChange(); Tasks.toast(this, "已写回原文件");
        }, error -> { finishChange(); Tasks.showError(this, error); });
    }
    private void finishChange() {
        busy = false; refresh.setEnabled(true); reload();
    }
    private <T> T applyChange(Tasks.Job<T> job) throws Exception {
        synchronized (workspaceLock) {
            requireRevision();
            String expected = sourceRevision;
            File backup = File.createTempFile("before-change-", ".bak", archive.getParentFile());
            try {
                Files.copy(archive.toPath(), backup.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                try {
                    T result = job.run();
                    store.write(source, archive, expected);
                    // A post-write fingerprint alone could attach an external update to our old bytes.
                    // Re-copy a stable source snapshot instead, before publishing its matching baseline.
                    sourceRevision = copySource(archive, true, prefix);
                    return result;
                } catch (Exception error) {
                    try {
                        FileStore.replace(backup, archive); ArchiveOps.forget(archive);
                        sourceRevision = expected;
                    } catch (IOException rollback) { sourceRevision = null; error.addSuppressed(rollback); }
                    throw error;
                }
            } finally { Files.deleteIfExists(backup.toPath()); }
        }
    }
    private File outputDir() throws IOException {
        File base = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager() ?
                new File(Environment.getExternalStorageDirectory(), "Documents/DualStackLab") : getExternalFilesDir("projects");
        if (base == null || (!base.isDirectory() && !base.mkdirs())) throw new IOException("输出目录不可写，请开启存储权限");
        return base;
    }
    private void extract(String entry) {
        if (archive == null || busy) return;
        Tasks.run(this, () -> {
            File dest = new File(outputDir(), source.name + "-extract-" + System.currentTimeMillis());
            ArchiveOps.extract(archive, entry, dest); return dest;
        }, this::showOutput);
    }
    private void showOutput(File file) {
        new AlertDialog.Builder(this).setTitle("已输出").setMessage(file.getAbsolutePath())
                .setNegativeButton("关闭", null).setPositiveButton(file.isDirectory() ? "打开目录" : "浏览输出目录", (d, w) -> {
                    Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    i.putExtra("navigatePath", file.isDirectory() ? file.getAbsolutePath() : file.getParent());
                    startActivity(i);
                }).show();
    }
    private void toolMenu() {
        new AlertDialog.Builder(this).setTitle("APK 工具").setItems(new String[]{"APK 信息", "DEX 反汇编", "Smali 回编译",
                "对齐并签名", "制作共存包", "导出当前文件"}, (d, n) -> tool(new int[]{0, 4, 5, 6, 9, 10}[n])).show();
    }
    private void tool(int n) {
        if (archive == null || busy) { Tasks.toast(this, "等待读取完成"); return; }
        if (rar) { Tasks.toast(this, "此归档格式支持浏览、查看和解压"); return; }
        if (rawDex && n != 3 && n != 4 && n != 5 && n != 10 && n != 0) { Tasks.toast(this, "此操作需要 APK"); return; }
        switch (n) {
            case 0 -> Tasks.run(this, () -> metadata(), text -> message("文件信息", text));
            case 1 -> edit("AndroidManifest.xml", "xml");
            case 2 -> edit("resources.arsc", "arsc");
            case 3 -> chooseDex(this::dexBrowser);
            case 4 -> chooseDex(this::disassemble);
            case 5 -> chooseDex(this::assemble);
            case 6 -> outputApk(true, false, null);
            case 7 -> outputApk(false, false, null);
            case 8 -> Tasks.run(this, () -> ApkTools.verify(archive), text -> message("APK 签名", text));
            case 9 -> cloneDialog();
            case 10 -> export(archive, source.name);
        }
    }
    private String metadata() throws Exception {
        StringBuilder out = new StringBuilder(source.name + "\n" + FileStore.size(archive.length())
                + "\n\nSHA-256\n" + ApkTools.sha256(archive));
        if (!rawDex) {
            android.content.pm.PackageInfo info = getPackageManager().getPackageArchiveInfo(archive.getAbsolutePath(), 0);
            if (info != null) out.append("\n\n").append(info.packageName).append("\n版本 ").append(info.versionName)
                    .append("\nSDK ").append(info.applicationInfo.minSdkVersion).append(" / ").append(info.applicationInfo.targetSdkVersion);
        }
        return out.toString();
    }
    private void message(String title, String text) {
        TextView view = Ui.text(this, text, 12, Ui.TEXT); view.setTextIsSelectable(true); view.setPadding(dp(18), dp(12), dp(18), dp(12));
        ScrollView scroll = new ScrollView(this); scroll.addView(view);
        new AlertDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton("关闭", null).show();
    }
    private interface DexAction { void run(String entry); }
    private void chooseDex(DexAction action) {
        if (rawDex) { action.run(""); return; }
        Tasks.run(this, () -> {
            List<String> names = new ArrayList<>();
            for (String name : ArchiveOps.entryNames(archive)) if (name.endsWith(".dex")) names.add(name);
            Collections.sort(names); return names;
        }, names -> {
            if (names.isEmpty()) { Tasks.toast(this, "没有 DEX 文件"); return; }
            new AlertDialog.Builder(this).setTitle("选择 DEX").setItems(names.toArray(new String[0]),
                    (d, n) -> { dexEntry = names.get(n); action.run(dexEntry); }).show();
        });
    }
    private File dexFile(String entry) throws IOException {
        if (rawDex) return archive;
        File out = new File(archive.getParentFile(), entry.replace('/', '_'));
        return ArchiveOps.extractOne(archive, entry, out);
    }
    private File project(String entry) throws Exception {
        if (projects.containsKey(entry)) return projects.get(entry);
        String identity = source.key() + "!" + entry;
        String hash = Integer.toHexString(identity.hashCode());
        File dir = new File(outputDir(), source.name + "-" + (entry.isEmpty() ? "dex" : entry.replace('/', '_')) + "-" + hash);
        projects.put(entry, dir); return dir;
    }
    private File projectRevisionFile(File project) {
        return new File(project.getParentFile(), project.getName() + ".source-revision");
    }
    private void recordProjectRevision(File project) throws IOException {
        File revision = projectRevisionFile(project);
        File staged = File.createTempFile("project-revision-", ".tmp", revision.getParentFile());
        try {
            Files.write(staged.toPath(), sourceRevision.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            FileStore.replace(staged, revision);
        } finally { Files.deleteIfExists(staged.toPath()); }
    }
    private File verifiedProject(String entry) throws Exception {
        File project = project(entry), revision = projectRevisionFile(project);
        if (!revision.isFile() || sourceRevision == null || !sourceRevision.equals(
                new String(Files.readAllBytes(revision.toPath()), java.nio.charset.StandardCharsets.UTF_8)))
            throw new IOException("反汇编工作区来自旧版本或缺少来源版本，请刷新/重开并在新工作区反汇编；已有 project 已保留：" + project);
        return project;
    }
    private void disassemble(String entry) {
        busy = true; summary.setText("DEX 反汇编中…");
        Tasks.run(this, () -> {
            try {
                synchronized (workspaceLock) {
                    requireRevision();
                    File out = project(entry);
                    if (out.exists()) {
                        String[] children = out.list();
                        if (children == null || children.length != 0) {
                            // Explicit re-disassembly gets a fresh directory, never erases a user's project.
                            out = Files.createTempDirectory(out.getParentFile().toPath(), out.getName() + "-").toFile();
                            projects.put(entry, out);
                        }
                    }
                    ApkTools.disassemble(dexFile(entry), out);
                    recordProjectRevision(out); return out;
                }
            } finally { runOnUiThread(() -> busy = false); }
        }, this::showOutput);
    }
    private void assemble(String entry) {
        new AlertDialog.Builder(this).setTitle("回编译并写回 DEX？").setMessage(entry.isEmpty() ? source.name : entry)
                .setNegativeButton("取消", null).setPositiveButton("编译", (d, w) -> change(() -> {
                    File out = new File(archive.getParentFile(), "compiled.dex");
                    ApkTools.assemble(verifiedProject(entry), out);
                    if (rawDex) FileStore.replace(out, archive);
                    else ArchiveOps.rewrite(archive, null, null, Map.of(entry, out));
                    return true;
                })).show();
    }
    private void dexBrowser(String entry) {
        Tasks.run(this, () -> {
            synchronized (workspaceLock) {
                List<String> names = new ArrayList<>();
                for (ClassDef def : DexFileFactory.loadDexFile(dexFile(entry), Opcodes.getDefault()).getClasses()) names.add(def.getType());
                Collections.sort(names); return names;
            }
        }, names -> {
            LinearLayout body = Ui.column(this); EditText find = Ui.input(this, "类名过滤"); body.addView(find);
            ListView classes = new ListView(this); body.addView(classes, lp(-1, 420));
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>(names));
            classes.setAdapter(adapter);
            MainActivity.watch(find, () -> {
                adapter.clear(); String q = find.getText().toString().toLowerCase(Locale.ROOT);
                for (String s : names) if (s.toLowerCase(Locale.ROOT).contains(q)) adapter.add(s);
            });
            AlertDialog dialog = new AlertDialog.Builder(this).setTitle(names.size() + " 个类 · " + (entry.isEmpty() ? source.name : entry))
                    .setView(body).setNegativeButton("关闭", null).setPositiveButton("反汇编到工作区", (d, w) -> disassemble(entry)).create();
            classes.setOnItemClickListener((p, v, pos, id) -> {
                String descriptor = adapter.getItem(pos);
                Tasks.run(this, () -> classPreview(entry, descriptor), preview -> openEditor(
                        classEditorIntent(entry, descriptor, preview)));
            }); dialog.show();
        });
    }
    private ClassPreview classPreview(String entry, String descriptor) throws Exception {
        synchronized (workspaceLock) {
            if (sourceRevision == null) throw revisionConflict();
            String revision = sourceRevision, archivePath = archive.getAbsolutePath();
            File dex = dexFile(entry), folder = Files.createTempDirectory(getCacheDir().toPath(), "class-").toFile();
            BaksmaliOptions options = new BaksmaliOptions(); options.apiLevel = 28; options.debugInfo = true;
            if (!Baksmali.disassembleDexFile(DexFileFactory.loadDexFile(dex, Opcodes.getDefault()),
                    folder, 1, options, List.of(descriptor))) throw new IOException("类反汇编失败");
            List<File> smali = new ArrayList<>(); collectSmali(folder, smali);
            if (smali.isEmpty()) throw new IOException("未输出 Smali");
            return new ClassPreview(smali.get(0), revision, archivePath);
        }
    }
    private Intent classEditorIntent(String entry, String descriptor, ClassPreview preview) {
        Intent i = new Intent(this, EditorActivity.class); source.put(i);
        return i.putExtra("preview", preview.file().getAbsolutePath()).putExtra("displayName", descriptor)
                .putExtra("mode", "smali-class").putExtra("archivePath", preview.archivePath())
                .putExtra("dexEntry", entry).putExtra("descriptor", descriptor)
                .putExtra("sourceRevision", preview.revision());
    }
    private void collectSmali(File dir, List<File> out) throws IOException {
        File[] children = dir.listFiles(); if (children == null) throw new IOException("工作区不可读取");
        for (File f : children) if (f.isDirectory()) collectSmali(f, out); else if (f.getName().endsWith(".smali")) out.add(f);
    }
    private void cloneDialog() {
        Tasks.run(this, () -> ApkTools.packageName(archive), old -> {
            EditText input = Ui.input(this, "新包名"); input.setText(old + ".clone");
            new AlertDialog.Builder(this).setTitle("制作共存 APK").setView(input).setNegativeButton("取消", null)
                    .setPositiveButton("制作并签名", (d, w) -> outputApk(true, true, input.getText().toString())).show();
        });
    }
    private void outputApk(boolean sign, boolean clone, String packageName) {
        if (busy) return; busy = true; summary.setText(sign ? "对齐并签名中…" : "对齐中…");
        Tasks.run(this, () -> {
            try {
                String base = source.name.replaceFirst("(?i)\\.apk(?:\\.1)?$", "");
                File unsigned = File.createTempFile("aligned-", ".apk", getCacheDir());
                File out = new File(outputDir(), base + (clone ? "-clone" : sign ? "-signed" : "-aligned") + "-" + System.currentTimeMillis() + ".apk");
                try {
                    if (clone) ApkTools.clonePackage(archive, unsigned, packageName); else ApkTools.align(archive, unsigned);
                    if (sign) ApkTools.sign(this, unsigned, out); else Files.copy(unsigned.toPath(), out.toPath());
                    return out;
                } finally { Files.deleteIfExists(unsigned.toPath()); }
            } finally { runOnUiThread(() -> busy = false); }
        }, this::showOutput);
    }
    private void export(File file, String name) {
        pendingOutput = file;
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream")
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, name), OUTPUT);
    }
    @Override protected void handleBack() {
        if (busy) { Tasks.toast(this, "当前操作正在执行"); return; }
        if (!prefix.isEmpty()) up(); else finish();
    }
}
