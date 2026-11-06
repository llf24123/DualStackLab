package com.dualstack.lab;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Search page: query, options and live results in one place; a running search continues if the page closes. */
public class SearchActivity extends LabActivity {
    private SearchJob job;
    private EditText query;
    private ImageView action;
    private TextView progress, empty, modeChip, regexChip, caseChip, hiddenChip, scopeChip;
    private ProgressBar spinner;
    private LinearLayout history;
    private BaseAdapter adapter;
    private final List<FileStore.Node> nodes = new ArrayList<>();
    private final Runnable changed = this::update;
    private boolean contents, regex, caseSensitive, hidden = true, shared, resolving, started;
    private String rootKey, rootPath;
    private SharedPreferences preferences;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        preferences = getSharedPreferences("search", 0);
        job = SearchJob.active();
        rootKey = getIntent().getStringExtra("rootKey");
        rootPath = getIntent().getStringExtra("rootPath");
        if (job != null && (saved != null ? !saved.getBoolean("jobAttached")
                : rootPath != null && !rootPath.equals(job.root.path()))) job = null;
        contents = preferences.getBoolean("contents", false); regex = preferences.getBoolean("regex", false);
        caseSensitive = preferences.getBoolean("case", false); hidden = preferences.getBoolean("hidden", true);
        if (job != null) {
            contents = job.contents; hidden = job.hidden;
            shared = job.root.path().equals(android.os.Environment.getExternalStorageDirectory().getPath());
            regex = !job.pattern.pattern().equals(Pattern.quote(job.query));
            caseSensitive = (job.pattern.flags() & Pattern.CASE_INSENSITIVE) == 0;
            if (rootKey == null) { rootKey = job.root.key(); rootPath = job.root.path(); }
        }
        if (saved != null) {
            contents = saved.getBoolean("contents"); regex = saved.getBoolean("regex");
            caseSensitive = saved.getBoolean("case"); hidden = saved.getBoolean("hidden"); shared = saved.getBoolean("shared");
            rootKey = saved.getString("rootKey"); rootPath = saved.getString("rootPath");
        }
        LinearLayout root = Ui.column(this);
        root.addView(Ui.toolbar(this, "搜索", this::finish), new LinearLayout.LayoutParams(-1, Ui.dp(this, 52)));
        root.addView(buildQueryBar());
        root.addView(buildChips(), new LinearLayout.LayoutParams(-1, Ui.dp(this, 44)));
        LinearLayout status = Ui.row(this); status.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        spinner = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall); spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        status.addView(spinner, new LinearLayout.LayoutParams(Ui.dp(this, 16), Ui.dp(this, 16)));
        progress = Ui.single(this, "", 12, Ui.MUTED); progress.setContentDescription("搜索进度");
        progress.setPadding(Ui.dp(this, 8), 0, 0, 0);
        status.addView(progress, new LinearLayout.LayoutParams(0, Ui.dp(this, 32), 1));
        root.addView(status); root.addView(Ui.line(this));
        FrameLayout frame = new FrameLayout(this);
        ListView list = new ListView(this); list.setContentDescription("搜索结果列表"); list.setDivider(null);
        adapter = new ResultAdapter(); list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> open(nodes.get(position)));
        list.setOnItemLongClickListener((parent, view, position, id) -> { copyPath(nodes.get(position)); return true; });
        frame.addView(list, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout placeholder = Ui.column(this); placeholder.setPadding(Ui.dp(this, 20), Ui.dp(this, 24), Ui.dp(this, 20), 0);
        empty = Ui.text(this, "", 14, Ui.MUTED); empty.setGravity(Gravity.CENTER_HORIZONTAL);
        placeholder.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        history = Ui.column(this);
        LinearLayout.LayoutParams historyParams = new LinearLayout.LayoutParams(-1, -2); historyParams.topMargin = Ui.dp(this, 12);
        placeholder.addView(history, historyParams);
        ScrollView scroll = new ScrollView(this); scroll.addView(placeholder);
        frame.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        list.setEmptyView(scroll);
        root.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1));
        Ui.install(this, root);
        if (saved != null) query.setText(saved.getString("query", ""));
        else if (job != null) query.setText(job.query);
        query.setSelection(query.getText().length());
        updateChips(); update();
        if (job == null && saved == null) { query.requestFocus(); showKeyboard(); }
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("query", query.getText().toString()); out.putBoolean("jobAttached", job != null);
        out.putString("rootKey", rootKey); out.putString("rootPath", rootPath);
        out.putBoolean("contents", contents); out.putBoolean("regex", regex);
        out.putBoolean("case", caseSensitive); out.putBoolean("hidden", hidden); out.putBoolean("shared", shared);
    }
    private View buildQueryBar() {
        LinearLayout bar = Ui.row(this); bar.setBackgroundColor(Ui.PANEL);
        bar.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8));
        query = Ui.input(this, "文件名或内容"); query.setContentDescription("搜索关键词");
        query.setBackground(Ui.background(Ui.SURFACE, Ui.dp(this, 6)));
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_SEARCH && (event == null || event.getKeyCode() != android.view.KeyEvent.KEYCODE_ENTER)) return false;
            if (event == null || event.getAction() == android.view.KeyEvent.ACTION_UP) begin();
            return true;
        });
        bar.addView(query, new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1));
        action = Ui.icon(this, R.drawable.ic_search, "开始搜索", Ui.ACCENT, this::toggleSearch);
        bar.addView(action, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 44)));
        return bar;
    }
    private View buildChips() {
        HorizontalScrollView scroll = new HorizontalScrollView(this); scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = Ui.row(this); row.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        modeChip = chip(row, "文件名", "搜索范围：文件名或内容", () -> contents = !contents);
        regexChip = chip(row, "正则表达式", "正则表达式", () -> regex = !regex);
        caseChip = chip(row, "区分大小写", "区分大小写", () -> caseSensitive = !caseSensitive);
        hiddenChip = chip(row, "包含隐藏文件", "包含隐藏文件", () -> hidden = !hidden);
        scopeChip = chip(row, "当前目录", "搜索位置", () -> shared = !shared);
        scroll.addView(row);
        return scroll;
    }
    private TextView chip(LinearLayout row, String label, String description, Runnable flip) {
        TextView chip = Ui.single(this, label, 13, Ui.MUTED); chip.setGravity(Gravity.CENTER);
        chip.setContentDescription(description); chip.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        chip.setOnClickListener(v -> { flip.run(); updateChips(); });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, Ui.dp(this, 32)); params.rightMargin = Ui.dp(this, 8);
        row.addView(chip, params);
        return chip;
    }
    private void updateChips() {
        modeChip.setText(contents ? "文件内容" : "文件名");
        scopeChip.setText(shared || rootKey == null ? "全部共享存储" : "当前目录 · " + shortName(rootPath == null ? "/" : rootPath));
        scopeChip.setEnabled(rootKey != null);
        style(modeChip, contents); style(regexChip, regex); style(caseChip, caseSensitive);
        style(hiddenChip, hidden); style(scopeChip, shared);
        if (job == null && nodes.isEmpty()) empty.setText(contents
                ? "在常见文本文件中查找内容，每个文件只读取前 2 MiB"
                : "按文件名搜索，包含所有子目录");
    }
    private void style(TextView chip, boolean on) {
        chip.setSelected(on);
        chip.setTextColor(on ? Ui.ACCENT : chip.isEnabled() ? Ui.TEXT : Ui.MUTED);
        android.graphics.drawable.GradientDrawable shape = Ui.background(on ? Ui.TINT : Ui.SURFACE, Ui.dp(this, 16));
        shape.setStroke(Ui.dp(this, 1), on ? Ui.ACCENT : Ui.LINE);
        chip.setBackground(shape);
    }
    private static String shortName(String path) {
        if (path.equals("/storage/emulated/0")) return "内部存储";
        String trimmed = path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
        String name = trimmed.substring(trimmed.lastIndexOf('/') + 1);
        return name.isEmpty() ? "/" : name;
    }
    private void toggleSearch() {
        if (job != null && !job.snapshot().finished() && !job.snapshot().cancelled()) job.cancel(); else begin();
    }
    private void begin() {
        if (resolving) return;
        String text = query.getText().toString();
        if (text.isEmpty()) { query.setError("请输入关键词"); return; }
        Pattern pattern;
        try {
            int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            pattern = Pattern.compile(regex ? text : Pattern.quote(text), flags);
        } catch (PatternSyntaxException error) { query.setError("正则错误：" + error.getDescription()); return; }
        query.setError(null);
        preferences.edit().putBoolean("contents", contents).putBoolean("regex", regex)
                .putBoolean("case", caseSensitive).putBoolean("hidden", hidden).apply();
        remember(text);
        hideKeyboard();
        boolean searchContents = contents, includeHidden = hidden, everywhere = shared || rootKey == null;
        String key = rootKey;
        resolving = true; action.setEnabled(false);
        Tasks.run(this, () -> everywhere
                ? FileStore.directory(android.os.Environment.getExternalStorageDirectory(), false)
                : new FileStore(this).fromKey(key), root -> {
            resolving = false; action.setEnabled(true);
            if (job != null) job.unlisten(changed);
            job = SearchJob.start(this, root, text, pattern, searchContents, includeHidden);
            if (started) job.listen(changed);
            nodes.clear(); adapter.notifyDataSetChanged(); update();
        }, error -> { resolving = false; action.setEnabled(true); query.setError(error.getMessage()); });
    }
    private void remember(String text) {
        List<String> recent = recent(); recent.remove(text); recent.add(0, text);
        while (recent.size() > 8) recent.remove(recent.size() - 1);
        preferences.edit().putString("history", String.join("\n", recent)).apply();
    }
    private List<String> recent() {
        String stored = preferences.getString("history", "");
        List<String> values = new ArrayList<>();
        for (String value : stored.split("\n")) if (!value.isEmpty()) values.add(value);
        return values;
    }
    private void showHistory() {
        history.removeAllViews();
        List<String> recent = recent();
        if (job != null) return;
        if (SearchJob.active() != null) {
            TextView previous = Ui.text(this, "查看上次搜索", 14, Ui.ACCENT); previous.setGravity(Gravity.CENTER);
            previous.setBackground(Ui.ripple(Ui.SURFACE));
            previous.setOnClickListener(v -> { startActivity(new Intent(this, SearchActivity.class)); finish(); });
            history.addView(previous, new LinearLayout.LayoutParams(-1, Ui.dp(this, 44)));
        }
        if (recent.isEmpty()) return;
        LinearLayout heading = Ui.row(this);
        heading.addView(Ui.text(this, "最近搜索", 13, Ui.MUTED), new LinearLayout.LayoutParams(0, Ui.dp(this, 36), 1));
        TextView clear = Ui.text(this, "清除", 13, Ui.ACCENT); clear.setGravity(Gravity.CENTER);
        clear.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 4), 0); clear.setBackground(Ui.ripple(Ui.SURFACE));
        clear.setOnClickListener(v -> { preferences.edit().remove("history").apply(); showHistory(); });
        heading.addView(clear, new LinearLayout.LayoutParams(-2, Ui.dp(this, 36)));
        history.addView(heading);
        for (String value : recent) {
            LinearLayout row = Ui.row(this); row.setBackground(Ui.ripple(Ui.SURFACE));
            ImageView icon = Ui.image(this, R.drawable.ic_search, Ui.MUTED); icon.setPadding(Ui.dp(this, 2), 0, Ui.dp(this, 10), 0);
            row.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 30), Ui.dp(this, 20)));
            row.addView(Ui.single(this, value, 15, Ui.TEXT), new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1));
            row.setContentDescription("最近搜索 " + value);
            row.setOnClickListener(v -> { query.setText(value); query.setSelection(value.length()); begin(); });
            history.addView(row);
        }
    }
    @Override protected void onPaletteChanged() {
        updateChips(); adapter.notifyDataSetChanged();
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
    }
    @Override protected void onStart() {
        super.onStart(); started = true; if (job != null) job.listen(changed); update();
    }
    @Override protected void onStop() {
        started = false; if (job != null) job.unlisten(changed); super.onStop();
    }
    private void update() {
        if (progress == null) return;
        if (job == null) {
            progress.setText("输入关键词后按搜索"); spinner.setVisibility(View.GONE);
            action.setImageResource(R.drawable.ic_search); action.setContentDescription("开始搜索");
            showHistory(); return;
        }
        SearchJob.Snapshot state = job.snapshot();
        int oldSize = nodes.size(); nodes.clear(); nodes.addAll(state.matches());
        if (oldSize != nodes.size()) adapter.notifyDataSetChanged();
        boolean running = !state.finished() && !state.cancelled();
        spinner.setVisibility(running ? View.VISIBLE : View.GONE);
        action.setImageResource(running ? R.drawable.ic_stop : R.drawable.ic_search);
        action.setContentDescription(running ? "停止搜索" : "开始搜索");
        String status = state.finished() ? state.cancelled() ? "已停止" : "搜索完成" : state.cancelled() ? "正在停止…" : "搜索中…";
        progress.setText(status + " · " + nodes.size() + " 项 · 已检查 " + state.scanned()
                + (state.denied() == 0 ? "" : " · 跳过 " + state.denied())
                + (nodes.size() >= SearchJob.LIMIT ? " · 达到 " + SearchJob.LIMIT + " 项上限" : "")
                + (state.error() == null ? "" : " · " + state.error()));
        history.removeAllViews();
        empty.setText(running ? "正在查找“" + job.query + "”…" : "没有找到“" + job.query + "”" +
                (job.contents ? "\n内容搜索只检查常见文本文件" : "\n可改用内容搜索或关闭区分大小写"));
    }
    private void open(FileStore.Node node) {
        Intent intent = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra("searchResult", node.key());
        startActivity(intent); finish();
    }
    private void copyPath(FileStore.Node node) {
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("路径", node.path()));
        Tasks.toast(this, "路径已复制");
    }
    private void showKeyboard() {
        query.post(() -> ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(query, InputMethodManager.SHOW_IMPLICIT));
    }
    private void hideKeyboard() {
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(query.getWindowToken(), 0);
    }
    /** Location relative to the searched folder, so long shared prefixes do not crowd the row. */
    private String relativeFolder(FileStore.Node node) {
        String path = node.path(), base = job == null || job.root == null ? "" : job.root.path();
        int slash = path.lastIndexOf('/');
        String folder = slash > 0 ? path.substring(0, slash) : "/";
        if (!base.isEmpty() && (folder.equals(base) || folder.startsWith(base.endsWith("/") ? base : base + "/"))) {
            String rest = folder.substring(base.length());
            return rest.isEmpty() ? "搜索目录" : "…" + rest;
        }
        return folder;
    }
    private CharSequence highlight(String text, int start, int end, boolean bold) {
        SpannableString span = new SpannableString(text);
        if (start >= 0 && end > start && end <= text.length()) {
            span.setSpan(new BackgroundColorSpan(Ui.dark ? 0x66c9a227 : 0x66f2c94c), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (bold) span.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            else span.setSpan(new ForegroundColorSpan(Ui.TEXT), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return span;
    }
    private final class ResultAdapter extends BaseAdapter {
        public int getCount() { return nodes.size(); }
        public Object getItem(int position) { return nodes.get(position); }
        public long getItemId(int position) { return position; }
        public View getView(int position, View convert, ViewGroup parent) {
            Row row = convert == null ? new Row() : (Row) convert.getTag();
            FileStore.Node node = nodes.get(position);
            SearchJob.Hit hit = job == null ? null : job.hit(node);
            String lower = node.name.toLowerCase(Locale.ROOT);
            boolean archive = lower.matches(".*\\.(zip|rar|jar|7z|tar|gz|tgz|apks|xapk)$"), apk = ApkInstall.isApkName(lower);
            row.iconBackground.setColor(node.directory ? Ui.ICON_BG : apk ? Ui.ACCENT : archive ? Ui.GOLD : 0xff4374b3);
            row.icon.setImageResource(node.directory ? R.drawable.ic_folder : apk ? R.drawable.ic_package
                    : archive ? R.drawable.ic_archive : R.drawable.ic_file);
            boolean nameHit = hit != null && hit.line() == 0;
            row.name.setText(nameHit ? highlight(node.name, hit.start(), hit.end(), true) : node.name);
            row.path.setText(relativeFolder(node));
            if (hit != null && hit.line() > 0) {
                row.snippet.setVisibility(View.VISIBLE);
                String prefix = hit.line() + "  ";
                SpannableString line = new SpannableString(prefix + hit.text());
                line.setSpan(new ForegroundColorSpan(Ui.ACCENT), 0, prefix.length() - 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                int start = prefix.length() + hit.start(), end = prefix.length() + hit.end();
                if (end > start && end <= line.length())
                    line.setSpan(new BackgroundColorSpan(Ui.dark ? 0x66c9a227 : 0x66f2c94c), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                row.snippet.setText(line);
            } else row.snippet.setVisibility(View.GONE);
            row.root.setContentDescription("搜索结果 " + node.name);
            return row.root;
        }
    }
    private final class Row {
        final LinearLayout root = Ui.row(SearchActivity.this);
        final android.graphics.drawable.GradientDrawable iconBackground = Ui.background(Ui.ICON_BG, Ui.dp(SearchActivity.this, 6));
        final ImageView icon = Ui.image(SearchActivity.this, R.drawable.ic_file, Color.WHITE);
        final TextView name = Ui.single(SearchActivity.this, "", 15, Ui.TEXT);
        final TextView path = Ui.single(SearchActivity.this, "", 11, Ui.MUTED);
        final TextView snippet = Ui.single(SearchActivity.this, "", 12, Ui.MUTED);
        Row() {
            root.setPadding(Ui.dp(SearchActivity.this, 14), Ui.dp(SearchActivity.this, 8), Ui.dp(SearchActivity.this, 14), Ui.dp(SearchActivity.this, 8));
            root.setBackground(Ui.ripple(Ui.SURFACE)); root.setTag(this);
            root.setLayoutParams(new AbsListView.LayoutParams(-1, -2));
            icon.setBackground(iconBackground);
            int pad = Ui.dp(SearchActivity.this, 7); icon.setPadding(pad, pad, pad, pad);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(Ui.dp(SearchActivity.this, 36), Ui.dp(SearchActivity.this, 36));
            iconParams.gravity = Gravity.CENTER_VERTICAL;
            root.addView(icon, iconParams);
            LinearLayout text = Ui.column(SearchActivity.this);
            name.setEllipsize(TextUtils.TruncateAt.END);
            path.setEllipsize(TextUtils.TruncateAt.START);
            snippet.setEllipsize(TextUtils.TruncateAt.END); snippet.setTypeface(Typeface.MONOSPACE);
            name.setMinHeight(Ui.dp(SearchActivity.this, 22)); path.setMinHeight(Ui.dp(SearchActivity.this, 18));
            snippet.setMinHeight(Ui.dp(SearchActivity.this, 20));
            text.addView(name, new LinearLayout.LayoutParams(-1, -2));
            text.addView(path, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams snippetParams = new LinearLayout.LayoutParams(-1, -2);
            snippetParams.topMargin = Ui.dp(SearchActivity.this, 2); text.addView(snippet, snippetParams);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, -2, 1);
            textParams.leftMargin = Ui.dp(SearchActivity.this, 12);
            root.addView(text, textParams);
        }
    }
}
