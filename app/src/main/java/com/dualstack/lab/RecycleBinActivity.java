package com.dualstack.lab;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** User deletion archives and existing SAF save-recovery copies share one recovery page. */
public class RecycleBinActivity extends LabActivity {
    public static final String EXTRA_SUGGESTED_DIRECTORY = "suggestedDirectory";
    // FIFO across Activity instances: a recreated page reads the directory AFTER a submitted edit.
    // The backup switch is deliberately independent and never waits for this IO queue or scan locks.
    static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private final List<SafRecovery.Entry> entries = new ArrayList<>();
    private final List<DeletedFiles.Entry> deletedEntries = new ArrayList<>();
    private ListView list;
    private BaseAdapter adapter;
    private Switch enabled;
    private TextView directoryText, explanation, status, empty;
    private ProgressBar spinner;
    private View refreshAction, clearAction;
    private File configuredDirectory;
    private String suggestedDirectory, restoredDraft;
    private boolean active, changing, loading, synchronizingSwitch, scanFailed, reopenEditor;
    private int generation, editorGeneration;
    private int restoredPosition = -1, restoredOffset;
    private AlertDialog editorDialog, actionDialog;
    private EditText directoryInput;
    private DirectoryPicker picker;

    private record Snapshot(List<SafRecovery.Entry> entries, List<DeletedFiles.Entry> deleted, File directory, boolean defaultDirectory,
                            Exception scanError) {}
    private record DeleteReport(int deleted, List<String> errors) {}

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        suggestedDirectory = getIntent().getStringExtra(EXTRA_SUGGESTED_DIRECTORY);
        if (saved != null) {
            reopenEditor = saved.getBoolean("directoryEditor"); restoredDraft = saved.getString("directoryDraft");
            restoredPosition = saved.getInt("listPosition", -1); restoredOffset = saved.getInt("listOffset");
        }
        LinearLayout root = Ui.column(this);
        LinearLayout toolbar = Ui.toolbar(this, "回收站", this::finish);
        int toolbarHeight = Math.max(Ui.dp(this, 52), Math.round(Ui.dp(this, 52)
                * getResources().getConfiguration().fontScale));
        refreshAction = Ui.icon(this, R.drawable.ic_refresh, "刷新", Color.WHITE, this::reload);
        clearAction = Ui.icon(this, R.drawable.ic_trash, "清空备份", Color.WHITE, this::confirmClear);
        toolbar.addView(refreshAction, new LinearLayout.LayoutParams(Ui.dp(this, 48), toolbarHeight));
        toolbar.addView(clearAction, new LinearLayout.LayoutParams(Ui.dp(this, 48), toolbarHeight));
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, toolbarHeight));
        LinearLayout progress = Ui.row(this);
        progress.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
        spinner = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        progress.addView(spinner, new LinearLayout.LayoutParams(Ui.dp(this, 18), Ui.dp(this, 18)));
        status = Ui.text(this, "正在读取备份…", 12, Ui.MUTED); status.setContentDescription("备份扫描状态");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setPadding(Ui.dp(this, 8), 0, 0, 0);
        progress.addView(status, new LinearLayout.LayoutParams(0, -2, 1)); root.addView(progress);
        root.addView(Ui.line(this));
        list = new ListView(this); list.setContentDescription("备份列表"); list.setDivider(null);
        // Settings are a scrolling header, not a fixed-height panel that consumes a landscape screen.
        list.addHeaderView(settings(), null, false);
        adapter = new EntryAdapter(); list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        configuredDirectory = SafRecovery.backupDirectory(this).getAbsoluteFile();
        directoryText.setText("备份存放目录 · 点按更改\n" + configuredDirectory.getAbsolutePath());
        Ui.install(this, root); setSwitch(SafRecovery.enabled(this)); tintSwitch(); setControls();
    }

    private View settings() {
        LinearLayout header = Ui.column(this);
        enabled = new Switch(this); enabled.setText("保存备份"); enabled.setTextSize(17); enabled.setTextColor(Ui.TEXT);
        enabled.setContentDescription("保存备份开关"); enabled.setShowText(false);
        enabled.setMinHeight(Ui.dp(this, 56)); enabled.setSwitchPadding(Ui.dp(this, 16));
        enabled.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 10));
        enabled.setOnCheckedChangeListener((button, checked) -> { if (!synchronizingSwitch) changeEnabled(checked); });
        header.addView(enabled, new LinearLayout.LayoutParams(-1, -2));
        explanation = paragraph("", 13, Ui.MUTED); header.addView(explanation);
        directoryText = paragraph("备份存放目录\n正在读取…", 14, Ui.TEXT);
        directoryText.setMaxLines(3); directoryText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        directoryText.setContentDescription("备份存放目录"); directoryText.setFocusable(true);
        directoryText.setBackground(Ui.ripple(Ui.PANEL)); directoryText.setOnClickListener(v -> editDirectory(null));
        header.addView(directoryText, new LinearLayout.LayoutParams(-1, -2));
        TextView help = paragraph("普通删除会先生成 ZIP 备份；这里也保留 SAF 保存恢复备份。上方开关仅控制 SAF 保存备份，不关闭删除备份。点按查看说明。", 13, Ui.MUTED);
        help.setContentDescription("回收站说明"); help.setBackground(Ui.ripple(Ui.SURFACE));
        help.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("备份说明")
                .setMessage("普通删除前生成完整 ZIP 和私有索引，备份失败时保留原件。恢复到原目录，同名时使用新名称，不覆盖现有文件。删除备份需手动清理。\n\n"
                        + "下列规则只适用于 SAF 保存备份：成功保存后临时备份自动清理。关闭保存备份不会删除旧记录，也不影响编辑器草稿。\n\n"
                        + "更改目录仅影响新备份，旧记录仍可管理。自定义目录可能被其他应用访问。\n\n"
                        + "列表只快速检查记录和文件大小，恢复前会完整核对校验和，并要求确认覆盖来源当前内容。")
                .setPositiveButton("知道了", null).show());
        header.addView(help);
        header.addView(Ui.line(this));
        empty = paragraph("正在扫描保存恢复备份…", 14, Ui.MUTED); empty.setGravity(Gravity.CENTER);
        header.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        updateExplanation(true); return header;
    }
    private TextView paragraph(String text, int size, int color) {
        TextView view = Ui.text(this, text, size, color);
        view.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 10)); return view;
    }
    private void updateExplanation(boolean on) {
        explanation.setText(on
                ? "已开启：SAF 写回前创建恢复备份，成功保存后自动清理。"
                : "已关闭：不新建备份、不自动提醒；保存失败可能损坏原件。");
    }
    private void setSwitch(boolean on) {
        synchronizingSwitch = true; enabled.setChecked(on); synchronizingSwitch = false; updateExplanation(on);
    }
    private void tintSwitch() {
        int[][] states = {new int[]{android.R.attr.state_checked}, new int[0]};
        enabled.setThumbTintList(new ColorStateList(states, new int[]{Ui.ACCENT, Ui.MUTED}));
        enabled.setTrackTintList(new ColorStateList(states, new int[]{Ui.ACCENT, Ui.LINE}));
        spinner.setIndeterminateTintList(ColorStateList.valueOf(Ui.ACCENT));
    }
    private void setControls() {
        if (enabled == null) return;
        enabled.setEnabled(active); // A long scan/write/delete must never delay disabling new backups.
        directoryText.setEnabled(!changing && !loading && configuredDirectory != null);
        refreshAction.setEnabled(!changing);
        clearAction.setEnabled(!changing && !loading && !scanFailed && (!entries.isEmpty() || !deletedEntries.isEmpty()));
        clearAction.setAlpha(clearAction.isEnabled() ? 1f : .4f);
        spinner.setVisibility(loading || changing ? View.VISIBLE : View.GONE);
        if (adapter != null) adapter.notifyDataSetChanged();
    }
    private boolean live(int token) { return active && !isFinishing() && !isDestroyed() && token == generation; }

    private void reload() {
        if (!active || changing) return;
        int token = ++generation; loading = true; status.setText("正在扫描备份…"); setControls();
        Context application = getApplicationContext();
        Tasks.run(this, WORKER, () -> {
            File directory = SafRecovery.backupDirectory(application).getCanonicalFile();
            boolean defaults = directory.equals(SafRecovery.directory(application).getCanonicalFile());
            List<SafRecovery.Entry> found = new ArrayList<>(); List<DeletedFiles.Entry> deleted = new ArrayList<>(); Exception scanError = null;
            try { found.addAll(SafRecovery.entries(application)); deleted.addAll(DeletedFiles.entries(application)); }
            catch (Exception error) { scanError = error; }
            found.sort(Comparator.comparingLong(SafRecovery.Entry::createdAt).reversed()
                    .thenComparing(SafRecovery.Entry::name, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(SafRecovery.Entry::id));
            return new Snapshot(found, deleted, directory, defaults, scanError);
        }, snapshot -> {
            if (!live(token)) return;
            loading = false; scanFailed = snapshot.scanError() != null;
            configuredDirectory = snapshot.directory(); setSwitch(SafRecovery.enabled(this));
            directoryText.setText((snapshot.defaultDirectory() ? "默认（应用私有）" : "自定义（仅影响新备份）")
                    + " · 点按更改\n" + configuredDirectory.getAbsolutePath());
            if (scanFailed) {
                status.setText("扫描失败" + (entries.isEmpty() ? "" : "，仍显示上次列表") + "：" + message(snapshot.scanError()));
                empty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
                empty.setText("无法读取备份，请点按刷新重试。\n仍可更改备份设置；未删除任何备份或来源文件。");
                setControls(); Tasks.showError(this, snapshot.scanError()); return;
            }
            entries.clear(); entries.addAll(snapshot.entries()); deletedEntries.clear(); deletedEntries.addAll(snapshot.deleted());
            long recoverable = 0; for (SafRecovery.Entry entry : entries) if (entry.recoverable()) recoverable++;
            status.setText("删除备份 " + deletedEntries.size() + " 份 · 保存备份 " + entries.size() + " 份 · 保存可校验 " + recoverable + " 份");
            empty.setVisibility(entries.isEmpty() && deletedEntries.isEmpty() ? View.VISIBLE : View.GONE);
            empty.setText("回收站为空\n普通删除会先备份；成功保存的临时恢复备份会自动清理。");
            setControls();
            if (restoredPosition >= 0) {
                list.setSelectionFromTop(restoredPosition, restoredOffset); restoredPosition = -1;
            }
            if (reopenEditor) { reopenEditor = false; editDirectory(restoredDraft); restoredDraft = null; }
        }, error -> {
            if (!live(token)) return;
            loading = false; scanFailed = true;
            status.setText("扫描失败" + (entries.isEmpty() ? "" : "，仍显示上次列表") + "：" + message(error));
            empty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
            empty.setText("无法读取备份，请点按刷新重试。\n未删除任何备份或来源文件。"); setControls();
            Tasks.showError(this, error);
        });
    }
    private void changeEnabled(boolean desired) {
        if (!active) return;
        // This API updates SharedPreferences memory without acquiring the backend's write/restore lock.
        // Never enqueue it behind a scan: an accepted OFF must affect the very next save/reminder.
        try {
            SafRecovery.setEnabled(getApplicationContext(), desired); setSwitch(desired);
            if (!loading && !changing) reload();
        } catch (RuntimeException error) {
            setSwitch(SafRecovery.enabled(this)); Tasks.showError(this, error);
        }
    }

    private void actions(SafRecovery.Entry entry) {
        if (changing || loading || scanFailed) return;
        List<String> labels = new ArrayList<>();
        boolean canRestore = entry.recoverable() && entry.record() != null;
        if (canRestore) labels.add("恢复原件");
        labels.add("查看备份目录"); labels.add("删除备份");
        actionDialog = new AlertDialog.Builder(this).setTitle(entry.name())
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    if (!active || changing || loading) return;
                    String action = labels.get(which);
                    if (action.equals("恢复原件")) {
                        SafRecovery.confirmRestore(this, entry.record(), () -> { if (active) reload(); });
                    } else if (action.equals("查看备份目录")) viewDirectory(entry);
                    else confirmDelete(entry);
                }).setNegativeButton("取消", null).show();
    }
    private void deletedActions(DeletedFiles.Entry entry) {
        if (changing || loading || scanFailed) return;
        actionDialog = new AlertDialog.Builder(this).setTitle(entry.name()).setItems(new String[]{"恢复到原目录（同名不覆盖）", "查看删除备份", "永久删除这份备份"}, (dialog, which) -> {
            if (which == 0) {
                Operations.Spec spec = new Operations.Spec(); spec.kind = Operations.RESTORE; spec.name = entry.id(); spec.destination = entry.parent();
                spec.displayName = entry.name();
                try { Operations.get(this).enqueue(spec); startActivity(new Intent(this, TaskCenterActivity.class)); }
                catch (Exception error) { Tasks.showError(this, error); }
            } else if (which == 1) {
                new AlertDialog.Builder(this).setTitle("删除备份").setMessage(entry.backup() + "\n\n来源：" + entry.source() + "\n状态：" + entry.state())
                        .setNegativeButton("关闭", null).setPositiveButton("打开备份目录", (d, w) -> {
                            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                    .putExtra("navigatePath", entry.backup().getParent())); finish();
                        }).show();
            } else new AlertDialog.Builder(this).setTitle("永久删除这份备份？").setMessage("仅删除「" + entry.name() + "」的删除备份和索引，不修改原目录。此操作不可撤销。")
                    .setNegativeButton("取消", null).setPositiveButton("删除备份", (d, w) -> deleteSnapshot(List.of("trash:" + entry.id()))).show();
        }).setNegativeButton("取消", null).show();
    }
    private void viewDirectory(SafRecovery.Entry entry) {
        File directory = entry.backup().getParentFile();
        if (directory == null) { Tasks.showError(this, new IOException("备份目录不可用")); return; }
        Intent intent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("navigatePath", directory.getAbsolutePath());
        try { startActivity(intent); finish(); } catch (RuntimeException error) { Tasks.showError(this, error); }
    }
    private void confirmDelete(SafRecovery.Entry entry) {
        actionDialog = new AlertDialog.Builder(this).setTitle("删除这份备份？")
                .setMessage("仅删除「" + entry.name() + "」的这份恢复备份和索引。\n备份：" + entry.backup()
                        + "\n\n不会删除、修改或恢复来源文件。备份删除后无法再用它恢复原字节。")
                .setPositiveButton("删除备份", (dialog, which) -> deleteSnapshot(Collections.singletonList(entry.id())))
                .setNegativeButton("取消", null).show();
    }
    private void confirmClear() {
        if (changing || loading || scanFailed || entries.isEmpty() && deletedEntries.isEmpty()) return;
        // Snapshot NOW, not after confirmation and never by recursively removing a backup root.
        List<String> ids = new ArrayList<>(); for (SafRecovery.Entry entry : entries) ids.add(entry.id());
        for (DeletedFiles.Entry entry : deletedEntries) ids.add("trash:" + entry.id());
        actionDialog = new AlertDialog.Builder(this).setTitle("清空备份？")
                .setMessage("将永久删除当前列表中的 " + ids.size() + " 份恢复备份及其索引。"
                        + "\n仅处理打开本确认框时的列表快照，之后新增的备份不受影响。"
                        + "\n\n不会删除或修改任何来源文件；此操作不可撤销。")
                .setPositiveButton("清空备份", (dialog, which) -> deleteSnapshot(ids))
                .setNegativeButton("取消", null).show();
    }
    private void deleteSnapshot(List<String> snapshot) {
        if (!active || changing) return;
        List<String> ids = new ArrayList<>(new LinkedHashSet<>(snapshot));
        changing = true; loading = false; ++generation; status.setText("正在删除备份…"); setControls();
        Context application = getApplicationContext();
        Tasks.run(this, WORKER, () -> {
            int deleted = 0; List<String> errors = new ArrayList<>();
            for (String id : ids) {
                try { if (id.startsWith("trash:")) DeletedFiles.purge(application, id.substring(6)); else SafRecovery.delete(application, id); deleted++; }
                catch (Exception error) { errors.add(id + "：" + message(error)); }
            }
            return new DeleteReport(deleted, errors);
        }, report -> {
            changing = false; if (!active) return;
            reload(); // Also refresh on partial failure; already-deleted entries must not remain actionable.
            if (!report.errors().isEmpty()) Tasks.showError(this, new IOException("已删除 " + report.deleted()
                    + " 份备份，部分项目失败；来源文件未改动。\n" + String.join("\n", report.errors())));
            else Tasks.toast(this, "已删除 " + report.deleted() + " 份备份，来源文件未改动");
        }, error -> { changing = false; if (active) { reload(); Tasks.showError(this, error); } });
    }

    private void editDirectory(String draft) {
        if (!active || changing || configuredDirectory == null || editorDialog != null) return;
        int token = ++editorGeneration;
        LinearLayout content = Ui.column(this);
        content.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), Ui.dp(this, 8));
        content.addView(paragraph("可存放到内部存储或应用私有目录，保存时验证读写权限并创建目录。"
                + "\n更改位置后，新备份使用新目录，已有备份继续从原位置读取。", 13, Ui.MUTED));
        EditText input = Ui.input(this, "/本地/备份目录"); input.setSingleLine(false);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setContentDescription("备份目录输入"); input.setText(draft == null ? configuredDirectory.getAbsolutePath() : draft);
        content.addView(input, new LinearLayout.LayoutParams(-1, -2));
        TextView errorLabel = paragraph("", 13, Ui.TEXT); errorLabel.setContentDescription("备份目录错误");
        errorLabel.setVisibility(View.GONE); errorLabel.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(errorLabel);
        Button browse = button("浏览本地目录"); content.addView(browse, new LinearLayout.LayoutParams(-1, -2));
        Button internal = button("填入内部存储备份目录"); content.addView(internal, new LinearLayout.LayoutParams(-1, -2));
        internal.setOnClickListener(v -> {
            if (changing) return;
            input.setText(new File(android.os.Environment.getExternalStorageDirectory(), "DualStackLab/RecycleBin").getPath());
            errorLabel.setVisibility(View.GONE);
        });
        if (android.os.Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) {
            Button permission = button("允许访问内部存储"); content.addView(permission, new LinearLayout.LayoutParams(-1, -2));
            permission.setOnClickListener(v -> {
                try { startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()))); }
                catch (android.content.ActivityNotFoundException unavailable) {
                    startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                }
            });
        }
        Button suggestion = button("填入当前窗口目录");
        if (suggestedDirectory != null && !suggestedDirectory.isEmpty()) {
            content.addView(suggestion, new LinearLayout.LayoutParams(-1, -2));
            suggestion.setOnClickListener(v -> { input.setText(suggestedDirectory); errorLabel.setVisibility(View.GONE); });
        }
        ScrollView scroll = new ScrollView(this); scroll.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("更改备份目录").setView(scroll)
                .setPositiveButton("保存", null).setNeutralButton("恢复默认", null).setNegativeButton("取消", null).create();
        editorDialog = dialog; directoryInput = input;
        dialog.setOnDismissListener(ignored -> {
            if (editorDialog == dialog) { editorDialog = null; directoryInput = null; ++editorGeneration; }
            if (picker != null) picker.dismiss();
        });
        dialog.show(); input.setSelection(input.getText().length());
        browse.setOnClickListener(v -> {
            String value = input.getText().toString().trim();
            File start = value.startsWith("/") ? new File(value) : configuredDirectory;
            picker = new DirectoryPicker(input, start); picker.show();
        });
        Runnable clearError = () -> errorLabel.setVisibility(View.GONE);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            clearError.run(); String value = input.getText().toString().trim();
            if (value.isEmpty() || !new File(value).isAbsolute()) {
                errorLabel.setText("请输入本地绝对路径（以 / 开头），不能填写 SAF URI 或相对路径。");
                errorLabel.setVisibility(View.VISIBLE); return;
            }
            applyDirectory(new File(value), dialog, input, errorLabel, browse, suggestion, token);
        });
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v ->
                applyDirectory(null, dialog, input, errorLabel, browse, suggestion, token));
    }
    private Button button(String label) {
        Button button = new Button(this); button.setText(label); button.setTextSize(14); button.setAllCaps(false);
        button.setMinHeight(Ui.dp(this, 48)); return button;
    }
    private void applyDirectory(File directory, AlertDialog dialog, EditText input, TextView errorLabel,
                                Button browse, Button suggestion, int token) {
        if (!active || changing || token != editorGeneration) return;
        changing = true; ++generation; loading = false; status.setText("正在验证备份目录…"); setControls();
        errorLabel.setVisibility(View.GONE);
        directoryEditorEnabled(dialog, input, browse, suggestion, false);
        Context application = getApplicationContext();
        Tasks.run(this, WORKER, () -> { SafRecovery.setBackupDirectory(application, directory); return true; }, ignored -> {
            changing = false;
            if (!active) return;
            if (token == editorGeneration) dialog.dismiss();
            reload();
        }, error -> {
            changing = false;
            if (!active) return;
            if (token == editorGeneration) {
                directoryEditorEnabled(dialog, input, browse, suggestion, true);
                errorLabel.setText("目录未更改：" + message(error)); errorLabel.setVisibility(View.VISIBLE);
            } else Tasks.showError(this, error);
            reload(); // Retain and display the previous valid setting, not the rejected candidate.
        });
    }
    private void directoryEditorEnabled(AlertDialog dialog, EditText input, Button browse, Button suggestion, boolean value) {
        input.setEnabled(value); browse.setEnabled(value); suggestion.setEnabled(value); dialog.setCancelable(value);
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_NEGATIVE})
            dialog.getButton(which).setEnabled(value);
    }

    /** A File-only browser. It never asks a provider or ROOT shell to pretend a directory is locally writable. */
    private final class DirectoryPicker {
        private final EditText destination;
        private final File initial;
        private final List<File> directories = new ArrayList<>();
        private AlertDialog dialog;
        private TextView path, error;
        private Button up;
        private BaseAdapter rows;
        private File current;
        private int request;
        private boolean showing;
        DirectoryPicker(EditText destination, File initial) { this.destination = destination; this.initial = initial; }
        void show() {
            LinearLayout body = Ui.column(RecycleBinActivity.this);
            path = paragraph("", 13, Ui.TEXT); path.setContentDescription("本地目录路径"); body.addView(path);
            path.setMinHeight(Ui.dp(RecycleBinActivity.this, 48)); path.setBackground(Ui.ripple(Ui.SURFACE));
            path.setSingleLine(true); path.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            path.setOnClickListener(v -> jump());
            LinearLayout locations = Ui.row(RecycleBinActivity.this);
            Button internal = button("内部存储"), privateFiles = button("应用私有目录");
            up = button("上一级目录");
            for (Button location : new Button[]{internal, privateFiles, up}) {
                location.setTextSize(12); location.setSingleLine(true);
                location.setPadding(Ui.dp(RecycleBinActivity.this, 6), 0, Ui.dp(RecycleBinActivity.this, 6), 0);
                locations.addView(location, new LinearLayout.LayoutParams(0, Ui.dp(RecycleBinActivity.this, 48), 1));
            }
            body.addView(locations);
            internal.setOnClickListener(v -> load(android.os.Environment.getExternalStorageDirectory()));
            privateFiles.setOnClickListener(v -> load(getFilesDir()));
            up.setOnClickListener(v -> { if (current != null && current.getParentFile() != null) load(current.getParentFile()); });
            error = paragraph("", 12, Ui.MUTED); error.setMaxLines(2);
            body.addView(error, new LinearLayout.LayoutParams(-1, Ui.dp(RecycleBinActivity.this, 40)));
            ListView choices = new ListView(RecycleBinActivity.this); choices.setContentDescription("本地目录列表");
            rows = new BaseAdapter() {
                public int getCount() { return directories.size(); }
                public File getItem(int position) { return directories.get(position); }
                public long getItemId(int position) { return position; }
                public View getView(int position, View reuse, ViewGroup parent) {
                    TextView label = reuse instanceof TextView text ? text : paragraph("", 15, Ui.TEXT);
                    label.setMinHeight(Ui.dp(RecycleBinActivity.this, 48)); label.setText(getItem(position).getName());
                    label.setTextColor(Ui.TEXT); return label;
                }
            };
            choices.setAdapter(rows); choices.setOnItemClickListener((parent, view, position, id) -> load(directories.get(position)));
            int height = Math.max(Ui.dp(RecycleBinActivity.this, 48), Math.min(Ui.dp(RecycleBinActivity.this, 220),
                    getResources().getDisplayMetrics().heightPixels - Ui.dp(RecycleBinActivity.this, 320)));
            body.addView(choices, new LinearLayout.LayoutParams(-1, height));
            dialog = new AlertDialog.Builder(RecycleBinActivity.this).setTitle("选择本地目录").setView(body)
                    .setPositiveButton("使用此目录", null).setNegativeButton("取消", null).create();
            dialog.setOnDismissListener(ignored -> { showing = false; ++request; if (picker == this) picker = null; });
            dialog.show(); showing = true;
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                destination.setText(current.getAbsolutePath()); dismiss();
            });
            load(initial);
        }
        private void jump() {
            EditText input = Ui.input(RecycleBinActivity.this, "/storage/emulated/0");
            input.setContentDescription("本地目录跳转输入"); input.setText(current.getAbsolutePath());
            AlertDialog prompt = new AlertDialog.Builder(RecycleBinActivity.this).setTitle("跳转到目录").setView(input)
                    .setPositiveButton("跳转", null).setNegativeButton("取消", null).create();
            prompt.show(); prompt.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                File selected = new File(input.getText().toString().trim());
                if (!selected.isAbsolute()) { input.setError("请输入绝对目录路径"); return; }
                load(selected); prompt.dismiss();
            });
            dialog.setOnDismissListener(ignored -> { prompt.dismiss(); showing = false; ++request; if (picker == this) picker = null; });
        }
        private void load(File candidate) {
            int token = ++request; current = candidate; path.setText(candidate.getAbsolutePath());
            error.setText("正在读取应用可访问的子目录…"); directories.clear(); rows.notifyDataSetChanged();
            up.setEnabled(candidate.getParentFile() != null); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            Tasks.run(RecycleBinActivity.this, WORKER, () -> {
                File canonical = candidate.getCanonicalFile();
                if (!canonical.isDirectory() || !canonical.canRead()) throw new IOException("无法读取此目录，请允许文件访问，或使用内部存储、应用私有目录入口。");
                File[] children = canonical.listFiles();
                if (children == null) throw new IOException("无法列出该目录；请返回上级或直接输入其他绝对路径。");
                List<File> readable = new ArrayList<>();
                for (File child : children) if (child.isDirectory()) readable.add(child);
                readable.sort(Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER).thenComparing(File::getName));
                return new DirectoryListing(canonical, readable);
            }, result -> {
                if (!active || !showing || token != request) return;
                current = result.directory(); directories.addAll(result.children()); rows.notifyDataSetChanged();
                path.setText(current.getAbsolutePath()); up.setEnabled(current.getParentFile() != null);
                error.setText(directories.isEmpty() ? "此目录暂无子目录，可点按路径跳转。"
                        : "点按路径可跳转，保存时验证读写权限。");
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
            }, failure -> {
                if (active && showing && token == request) error.setText(message(failure));
            });
        }
        void dismiss() { if (dialog != null) dialog.dismiss(); }
    }
    private record DirectoryListing(File directory, List<File> children) {}

    private final class EntryAdapter extends BaseAdapter {
        private final List<Object> rows = new ArrayList<>();
        @Override public void notifyDataSetChanged() {
            rows.clear(); rows.addAll(deletedEntries); rows.addAll(entries);
            rows.sort(Comparator.comparingLong((Object item) -> item instanceof DeletedFiles.Entry deleted
                    ? deleted.created() : ((SafRecovery.Entry) item).createdAt()).reversed());
            super.notifyDataSetChanged();
        }
        public int getCount() { return rows.size(); }
        public Object getItem(int position) { return rows.get(position); }
        public long getItemId(int position) { return position; }
        public View getView(int position, View reuse, ViewGroup parent) {
            EntryRow row = reuse == null ? new EntryRow() : (EntryRow) reuse.getTag();
            if (getItem(position) instanceof DeletedFiles.Entry deleted) {
                row.deleted = deleted; row.entry = null;
                row.name.setText(deleted.name()); row.detail.setText("删除备份 · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(deleted.created())) + " · " + FileStore.size(deleted.size()));
                row.state.setText(deleted.state()); row.source.setText("来源：" + deleted.source());
                row.root.setContentDescription("删除备份记录 " + deleted.id()); row.more.setContentDescription("删除备份操作 " + deleted.id());
                row.root.setEnabled(!changing && !loading && !scanFailed); row.more.setEnabled(row.root.isEnabled());
                row.name.setTextColor(Ui.TEXT); row.detail.setTextColor(Ui.MUTED); row.state.setTextColor(Ui.ACCENT); row.source.setTextColor(Ui.MUTED);
                row.root.setBackground(Ui.ripple(Ui.SURFACE)); return row.root;
            }
            SafRecovery.Entry entry = (SafRecovery.Entry) getItem(position); row.entry = entry; row.deleted = null;
            row.name.setText(entry.name());
            String date = entry.createdAt() <= 0 ? "日期未知" : DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(new Date(entry.createdAt()));
            row.detail.setText(date + " · " + (entry.size() < 0 ? "大小未知" : FileStore.size(entry.size())));
            String problem = entry.problem();
            row.state.setText((entry.recoverable() ? "可校验恢复" : "不可恢复")
                    + (problem == null || problem.isEmpty() ? "" : " · " + problem));
            row.source.setText(entry.record() == null ? "来源：未知（孤立目录或元数据不可用）" : "来源：" + entry.record().uri);
            row.root.setContentDescription("备份记录 " + entry.id()); row.more.setContentDescription("备份操作 " + entry.id());
            row.root.setEnabled(!changing && !loading && !scanFailed); row.more.setEnabled(row.root.isEnabled());
            row.name.setTextColor(Ui.TEXT); row.detail.setTextColor(Ui.MUTED);
            row.state.setTextColor(entry.recoverable() ? Ui.ACCENT : Ui.MUTED); row.source.setTextColor(Ui.MUTED);
            row.more.setImageTintList(ColorStateList.valueOf(Ui.MUTED)); row.root.setBackground(Ui.ripple(Ui.SURFACE));
            return row.root;
        }
    }
    private final class EntryRow {
        final LinearLayout root = Ui.row(RecycleBinActivity.this);
        final TextView name = Ui.text(RecycleBinActivity.this, "", 16, Ui.TEXT);
        final TextView detail = Ui.text(RecycleBinActivity.this, "", 12, Ui.MUTED);
        final TextView state = Ui.text(RecycleBinActivity.this, "", 13, Ui.MUTED);
        final TextView source = Ui.text(RecycleBinActivity.this, "", 12, Ui.MUTED);
        SafRecovery.Entry entry;
        DeletedFiles.Entry deleted;
        final ImageView more = Ui.icon(RecycleBinActivity.this, R.drawable.ic_more, "备份操作", Ui.MUTED, () -> { if (deleted != null) deletedActions(deleted); else actions(entry); });
        EntryRow() {
            root.setTag(this); root.setGravity(Gravity.TOP);
            root.setPadding(Ui.dp(RecycleBinActivity.this, 16), Ui.dp(RecycleBinActivity.this, 12),
                    Ui.dp(RecycleBinActivity.this, 4), Ui.dp(RecycleBinActivity.this, 12));
            LinearLayout text = Ui.column(RecycleBinActivity.this);
            for (TextView label : new TextView[]{name, detail, state, source}) {
                label.setPadding(0, Ui.dp(RecycleBinActivity.this, 3), Ui.dp(RecycleBinActivity.this, 8), Ui.dp(RecycleBinActivity.this, 3));
                text.addView(label, new LinearLayout.LayoutParams(-1, -2));
            }
            root.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
            root.addView(more, new LinearLayout.LayoutParams(Ui.dp(RecycleBinActivity.this, 48), Ui.dp(RecycleBinActivity.this, 48)));
            root.setOnClickListener(v -> { if (deleted != null) deletedActions(deleted); else actions(entry); });
        }
    }

    private static String message(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
    @Override protected void onStart() {
        super.onStart(); active = true; setSwitch(SafRecovery.enabled(this)); setControls(); reload();
    }
    @Override protected void onStop() {
        active = false; ++generation; ++editorGeneration;
        if (editorDialog != null && directoryInput != null && !changing) {
            reopenEditor = true; restoredDraft = directoryInput.getText().toString();
        }
        if (picker != null) picker.dismiss();
        if (editorDialog != null) editorDialog.dismiss();
        if (actionDialog != null) actionDialog.dismiss();
        super.onStop();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        if (editorDialog != null && directoryInput != null && !changing) {
            out.putBoolean("directoryEditor", true); out.putString("directoryDraft", directoryInput.getText().toString());
        } else if (reopenEditor) {
            out.putBoolean("directoryEditor", true); out.putString("directoryDraft", restoredDraft);
        }
        out.putInt("listPosition", list.getFirstVisiblePosition());
        View first = list.getChildAt(0); out.putInt("listOffset", first == null ? 0 : first.getTop());
        super.onSaveInstanceState(out);
    }
    @Override protected void onPaletteChanged() { if (enabled != null) { tintSwitch(); adapter.notifyDataSetChanged(); } }
}
