package com.dualstack.lab;

import android.app.*;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.EditorSearcher;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;
import io.github.rosemoe.sora.event.ContentChangeEvent;
import io.github.rosemoe.sora.text.Content;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Pattern;

public class EditorActivity extends LabActivity {
    static final int LIMIT = 2 * 1024 * 1024, PAGE = 256 * 1024, STRUCTURED_LIMIT = 32 * 1024 * 1024;
    private FileStore store;
    private FileStore.Node source;
    private CodeEditor editor;
    private TextView info;
    private Charset charset = StandardCharsets.UTF_8;
    private byte[] bom = new byte[0];
    private boolean dirty, loading, loaded, saving, paged, reading, readOnly;
    private boolean transcodedPages, uncertainEncoding, controlCharacters;
    private long offset, length;
    private File archive, preview, pageFile;
    private final Set<File> temporaryFiles = new HashSet<>();
    private PagedTextFile.Page currentPage;
    private final ArrayDeque<Long> previousPages = new ArrayDeque<>();
    private String entry, mode;
    private int loadGeneration;
    private record Loaded(TextCodec.Decoded decoded, Content content, boolean paged) {}
    private boolean classEdit() { return "smali-class".equals(mode); }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new FileStore(this);
        entry = getIntent().getStringExtra("archiveEntry");
        mode = getIntent().getStringExtra("mode");
        readOnly = getIntent().getBooleanExtra("readOnly", false);
        if (getIntent().hasExtra("archivePath")) archive = new File(getIntent().getStringExtra("archivePath"));
        if (getIntent().hasExtra("preview")) preview = new File(getIntent().getStringExtra("preview"));
        LinearLayout root = Ui.column(this);
        String name = getIntent().getStringExtra("displayName");
        if (name == null) name = getIntent().getStringExtra("name");
        LinearLayout top = Ui.toolbar(this, name == null ? "编辑器" : name, this::leave);
        top.addView(Ui.icon(this, R.drawable.ic_search, "查找与替换", Color.WHITE, this::search), lp(46, 52));
        View saveButton = Ui.icon(this, R.drawable.ic_save, "保存", Color.WHITE, this::save);
        if (readOnly) saveButton.setVisibility(View.GONE);
        top.addView(saveButton, lp(46, 52));
        top.addView(Ui.icon(this, R.drawable.ic_more, "编辑器菜单", Color.WHITE, this::menu), lp(42, 52));
        root.addView(top, lp(-1, 52));
        editor = new CodeEditor(this); editor.setTextSize(13); editor.setTabWidth(4);
        editor.setRenderFunctionCharacters(true);
        editor.setEditorLanguage(new CodeLanguage()); editor.setEditable(false);
        onPaletteChanged();
        root.addView(editor, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(Ui.line(this));
        LinearLayout bottom = Ui.row(this); bottom.setBackgroundColor(Ui.SURFACE);
        bottom.addView(Ui.icon(this, R.drawable.ic_back, "撤销 / 上一页", Ui.TEXT, () -> {
            if (paged) changePage(false); else editor.undo();
        }), lp(44, 42));
        android.view.View redo = Ui.icon(this, R.drawable.ic_back, "重做 / 下一页", Ui.TEXT, () -> {
            if (paged) changePage(true); else editor.redo();
        }); redo.setRotation(180); bottom.addView(redo, lp(44, 42));
        info = Ui.single(this, "读取中…", 10, Ui.MUTED);
        bottom.addView(info, new LinearLayout.LayoutParams(0, -1, 1)); root.addView(bottom, lp(-1, 42));
        Ui.install(this, root);
        editor.subscribeEvent(ContentChangeEvent.class, (e, unsubscribe) -> {
            if (!loading && loaded) { dirty = true; details(); }
        });
        Tasks.run(this, () -> store.fromIntent(getIntent()), n -> { source = n; load(null); });
    }
    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w < 0 ? w : Ui.dp(this, w), h < 0 ? h : Ui.dp(this, h));
    }
    private void load(String encoding) {
        int token = ++loadGeneration;
        loaded = false; reading = true; editor.setEditable(false); info.setText("读取中…");
        Tasks.run(this, () -> {
            transcodedPages = false;
            byte[] bytes = readContent();
            boolean structured = mode != null && (mode.startsWith("arsc") || mode.equals("xml") || classEdit());
            if (structured && bytes.length > STRUCTURED_LIMIT) throw new IOException("结构化编辑内容超过 32 MiB");
            boolean large = bytes.length > LIMIT && !structured;
            TextCodec.Decoded decoded = TextCodec.decodeForEditor(large ? Arrays.copyOf(bytes, PAGE) : bytes, encoding, large);
            if (large && TextCodec.isStateful(decoded.charset())) {
                File converted = File.createTempFile("stateful-editor-", ".txt", getCacheDir());
                temporaryFiles.add(converted);
                try {
                    TextCodec.transcode(pageFile, converted, decoded.charset(), StandardCharsets.UTF_8, decoded.bom(), new byte[0]);
                } catch (Exception e) { Files.deleteIfExists(converted.toPath()); throw e; }
                pageFile = converted; length = converted.length(); transcodedPages = true;
            }
            return new Loaded(decoded, large ? null : new Content(decoded.text()), large);
        }, result -> {
            if (token != loadGeneration) return;
            reading = false;
            TextCodec.Decoded decoded = result.decoded();
            charset = decoded.charset(); bom = decoded.bom(); paged = result.paged(); uncertainEncoding = decoded.uncertain();
            controlCharacters = hasControls(decoded.text());
            if (paged) { offset = 0; previousPages.clear(); page(0); return; }
            loading = true; editor.setText(result.content(), true, null);
            editor.setEditable((preview == null && !readOnly) || classEdit());
            loading = false; loaded = true; dirty = false; details();
        }, error -> {
            if (token != loadGeneration) return;
            reading = false;
            info.setText("无法解码 · 可重新选择编码"); Tasks.showError(this, error);
        });
    }
    private byte[] readContent() throws Exception {
            if ("xml".equals(mode)) return ApkTools.xmlText(archive, entry).getBytes(StandardCharsets.UTF_8);
            if ("arsc".equals(mode)) return ApkTools.arscText(archive, entry).getBytes(StandardCharsets.UTF_8);
            if ("arsc-file".equals(mode)) return ApkTools.arscFileText(store.materialize(source)).getBytes(StandardCharsets.UTF_8);
            if (archive != null && entry != null) {
                long size = ArchiveOps.entrySize(archive, entry);
                if (size < 0) throw new IOException("归档条目不存在");
                if (size <= LIMIT) return ArchiveOps.bytes(archive, entry, LIMIT);
                pageFile = File.createTempFile("large-entry-", ".txt", getCacheDir());
                temporaryFiles.add(pageFile);
                ArchiveOps.extractOne(archive, entry, pageFile);
            } else {
                pageFile = preview != null ? preview : store.materialize(source);
                if (preview == null && (source.file == null || source.root)) temporaryFiles.add(pageFile);
            }
            length = pageFile.length();
            try (InputStream in = new FileInputStream(pageFile)) {
                return FileStore.readBytes(in, classEdit() ? STRUCTURED_LIMIT + 1 : LIMIT + 1);
            }
    }
    private void page(long position) {
        if (position >= length && position != 0) return;
        offset = position; loaded = false; reading = true; editor.setEditable(false);
        Tasks.run(this, () -> {
            PagedTextFile.Page result = PagedTextFile.read(pageFile, position,
                    transcodedPages ? StandardCharsets.UTF_8 : charset, transcodedPages ? new byte[0] : bom, PAGE);
            return new PageContent(result, new Content(result.text()));
        }, result -> {
            reading = false;
            currentPage = result.page();
            controlCharacters = hasControls(currentPage.text());
            loading = true; editor.setText(result.content(), true, null); loading = false; loaded = true; dirty = false;
            editor.setEditable((preview == null && !readOnly) || classEdit()); details();
        }, error -> { reading = false; info.setText("分段读取失败"); Tasks.showError(this, error); });
    }
    private record PageContent(PagedTextFile.Page page, Content content) {}
    private void changePage(boolean next) {
        if (!loaded || saving) return;
        Runnable navigate = () -> {
            if (next && currentPage.end() < length) {
                previousPages.push(offset); page(currentPage.end());
            } else if (!next && !previousPages.isEmpty()) page(previousPages.pop());
        };
        if (dirty) new AlertDialog.Builder(this).setTitle("当前分段尚未保存")
                .setNegativeButton("继续编辑", null).setPositiveButton("放弃修改", (d, w) -> navigate.run())
                .setNeutralButton("保存并翻页", (d, w) -> save(navigate)).show();
        else navigate.run();
    }
    private void details() {
        info.setText(charset.name() + (uncertainEncoding ? " · 推测" : "") + (controlCharacters ? " · 控制字符" : "") + " · " + (paged ? "分段 " + FileStore.size(offset) + "/" + FileStore.size(length)
                + (dirty ? " · 未保存" : "") + (readOnly ? " · 只读" : "") :
                editor.getLineCount() + " 行" + (dirty ? " · 未保存" : (readOnly || preview != null) && !classEdit() ? " · 只读" : "")));
    }
    private static boolean hasControls(String text) {
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (value < 32 && value != '\r' && value != '\n' && value != '\t') return true;
        }
        return false;
    }
    private void save() { save(null); }
    private void save(Runnable done) {
        if (!loaded || ((readOnly || preview != null) && !classEdit()) || saving) {
            Tasks.toast(this, ((readOnly || preview != null) && !classEdit()) ? "当前为只读预览" : "等待读取完成"); return;
        }
        String text = editor.getText().toString(); Charset current = charset; byte[] mark = bom;
        saving = true; editor.setEditable(false); info.setText("保存中…");
        Tasks.run(this, () -> {
            try {
                if (paged) {
                    File tmp = File.createTempFile("large-editor-", ".tmp", getCacheDir());
                    File encoded = null;
                    try {
                        PagedTextFile.Page result = PagedTextFile.write(pageFile, tmp, currentPage, text,
                                transcodedPages ? StandardCharsets.UTF_8 : current, transcodedPages ? new byte[0] : mark);
                        File payload = tmp;
                        if (transcodedPages) {
                            encoded = File.createTempFile("stateful-save-", ".tmp", getCacheDir());
                            TextCodec.transcode(tmp, encoded, StandardCharsets.UTF_8, current, new byte[0], mark);
                            payload = encoded;
                        }
                        if (archive != null && entry != null) {
                            ArchiveOps.rewrite(archive, null, null, Map.of(entry, payload));
                            store.write(source, archive);
                        } else store.write(source, payload);
                        if (!pageFile.equals(source.file) || archive != null) FileStore.replace(tmp, pageFile);
                        currentPage = result; length = pageFile.length();
                    } finally {
                        Files.deleteIfExists(tmp.toPath());
                        if (encoded != null) Files.deleteIfExists(encoded.toPath());
                    }
                    return true;
                } else if (classEdit()) {
                    ApkTools.saveDexClass(archive, getIntent().getStringExtra("dexEntry"),
                            getIntent().getStringExtra("descriptor"), text, getCacheDir());
                    Files.write(preview.toPath(), text.getBytes(StandardCharsets.UTF_8));
                } else if ("xml".equals(mode)) ApkTools.saveXml(archive, entry, text);
                else if ("arsc".equals(mode)) ApkTools.saveArsc(archive, entry, text);
                else if ("arsc-file".equals(mode)) {
                    File tmp = File.createTempFile("resources-", ".arsc", getCacheDir());
                    try { ApkTools.saveArscFile(tmp, text); store.write(source, tmp); }
                    finally { Files.deleteIfExists(tmp.toPath()); }
                    return true;
                } else {
                    byte[] encoded = TextCodec.encode(text, current, mark);
                    File tmp = File.createTempFile("editor-", ".tmp", getCacheDir());
                    try {
                        Files.write(tmp.toPath(), encoded);
                        if (archive != null && entry != null) ArchiveOps.rewrite(archive, null, null, Map.of(entry, tmp));
                        else store.write(source, tmp);
                    } finally { Files.deleteIfExists(tmp.toPath()); }
                }
                if (archive != null) store.write(source, archive);
                return true;
            } finally { runOnUiThread(() -> { saving = false; editor.setEditable(true); details(); }); }
        }, ok -> { dirty = false; details(); Tasks.toast(this, "已保存"); if (done != null) done.run(); });
    }
    private void search() {
        if (!loaded) return;
        LinearLayout body = Ui.column(this);
        EditText find = Ui.input(this, "查找"); EditText replace = Ui.input(this, "替换为");
        CheckBox regex = new CheckBox(this); regex.setText("正则表达式");
        CheckBox insensitive = new CheckBox(this); insensitive.setText("忽略大小写");
        body.addView(find); body.addView(replace); body.addView(regex); body.addView(insensitive);
        LinearLayout controls = Ui.row(this);
        TextView next = Ui.text(this, "下一处", 13, Ui.ACCENT); next.setGravity(android.view.Gravity.CENTER);
        TextView all = Ui.text(this, "全部替换", 13, Ui.ACCENT); all.setGravity(android.view.Gravity.CENTER);
        controls.addView(next, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1));
        controls.addView(all, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1)); body.addView(controls);
        Runnable query = () -> {
            String needle = find.getText().toString();
            if (needle.isEmpty()) { editor.getSearcher().stopSearch(); return; }
            try {
                if (regex.isChecked()) Pattern.compile(needle);
                editor.getSearcher().search(needle, new EditorSearcher.SearchOptions(
                        regex.isChecked() ? EditorSearcher.SearchOptions.TYPE_REGULAR_EXPRESSION :
                                EditorSearcher.SearchOptions.TYPE_NORMAL, insensitive.isChecked()));
            } catch (Exception e) { Tasks.toast(this, "正则错误：" + e.getMessage()); }
        };
        MainActivity.watch(find, query);
        regex.setOnCheckedChangeListener((v, checked) -> query.run());
        insensitive.setOnCheckedChangeListener((v, checked) -> query.run());
        next.setOnClickListener(v -> {
            try { if (!editor.getSearcher().gotoNext()) Tasks.toast(this, "没有匹配"); }
            catch (Exception e) { Tasks.toast(this, e.getMessage()); }
        });
        all.setOnClickListener(v -> {
            if ((readOnly || preview != null) && !classEdit()) { Tasks.toast(this, "当前为只读预览"); return; }
            try { editor.getSearcher().replaceAll(replace.getText().toString()); }
            catch (Exception e) { Tasks.toast(this, e.getMessage()); }
        });
        new AlertDialog.Builder(this).setTitle(paged ? "查找与替换 · 当前分段" : "查找与替换")
                .setView(body).setNegativeButton("关闭", null).show();
    }
    private void menu() {
        new AlertDialog.Builder(this).setItems(new String[]{"自动换行", "重新选择读取编码", "转换保存编码", "跳转到行",
                "撤销", "重做", "保存"}, (d, n) -> {
            switch (n) {
                case 0 -> editor.setWordwrap(!editor.isWordwrap());
                case 1 -> {
                    if (dirty) { Tasks.toast(this, "先保存修改，再重新读取"); return; }
                    encoding(false);
                }
                case 2 -> encoding(true);
                case 3 -> {
                    EditText line = Ui.input(this, "行号"); line.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
                    new AlertDialog.Builder(this).setTitle("跳转到行").setView(line).setNegativeButton("取消", null)
                            .setPositiveButton("跳转", (x, w) -> {
                                try { int target = Integer.parseInt(line.getText().toString()) - 1;
                                    editor.setSelection(Math.max(0, Math.min(target, editor.getLineCount() - 1)), 0);
                                } catch (Exception e) { Tasks.toast(this, "行号无效"); }
                            }).show();
                }
                case 4 -> editor.undo();
                case 5 -> editor.redo();
                case 6 -> save();
            }
        }).show();
    }
    private void encoding(boolean convert) {
        if (reading || saving) { Tasks.toast(this, "等待读写完成"); return; }
        if (convert && paged) { Tasks.toast(this, "大文件分段编辑保留原编码"); return; }
        String[] choices = {"自动识别", "UTF-8", "UTF-16LE", "UTF-16BE", "UTF-32LE", "UTF-32BE",
                "GB18030", "GBK", "Big5", "Shift_JIS", "EUC-JP", "EUC-KR", "ISO-2022-JP",
                "windows-1252", "windows-1251", "KOI8-R", "ISO-8859-1"};
        if (convert) choices = Arrays.copyOfRange(choices, 1, choices.length);
        String[] encodings = choices;
        new AlertDialog.Builder(this).setTitle(convert ? "保存编码" : "读取编码").setItems(choices, (d, n) -> {
            if (convert) { charset = Charset.forName(encodings[n]); bom = new byte[0]; uncertainEncoding = false; dirty = true; details(); }
                else load(n == 0 ? null : encodings[n]);
        }).show();
    }
    private void leave() {
        if (saving) { Tasks.toast(this, "保存中"); return; }
        if (dirty) new AlertDialog.Builder(this).setTitle("修改尚未保存").setNegativeButton("继续编辑", null)
                .setPositiveButton("放弃修改", (d, w) -> finish()).setNeutralButton("保存", (d, w) -> save(this::finish)).show();
        else finish();
    }
    @Override protected void handleBack() { leave(); }
    @Override protected void onPaletteChanged() {
        if (editor == null) return;
        EditorColorScheme colors = editor.getColorScheme();
        colors.setColor(EditorColorScheme.WHOLE_BACKGROUND, Ui.SURFACE);
        colors.setColor(EditorColorScheme.TEXT_NORMAL, Ui.TEXT);
        colors.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, Ui.PANEL);
        colors.setColor(EditorColorScheme.LINE_NUMBER, Ui.MUTED);
        colors.setColor(EditorColorScheme.CURRENT_LINE, Ui.TINT);
        colors.setColor(EditorColorScheme.KEYWORD, Ui.dark ? 0xffeba6d2 : 0xffa34a86);
        colors.setColor(EditorColorScheme.LITERAL, Ui.dark ? 0xffedbd86 : 0xffad6630);
        colors.setColor(EditorColorScheme.HTML_TAG, Ui.dark ? 0xff78c9ec : 0xff247caa);
        colors.setColor(EditorColorScheme.COMMENT, Ui.MUTED); editor.invalidate();
    }
    @Override public void onDestroy() {
        if (editor != null) editor.release();
        if (!reading && !saving) {
            Set<File> files = new HashSet<>(temporaryFiles);
            File owned = getIntent().getBooleanExtra("ownedArchive", false) ? archive : null;
            Tasks.IO.execute(() -> {
                for (File file : files) try { Files.deleteIfExists(file.toPath()); } catch (IOException ignored) { }
                if (owned != null) try { Files.deleteIfExists(owned.toPath()); Files.deleteIfExists(owned.getParentFile().toPath()); } catch (IOException ignored) { }
            });
        }
        super.onDestroy();
    }
}
