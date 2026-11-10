package com.dualstack.lab;

import android.app.*;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import androidx.lifecycle.ViewModelProvider;
import org.json.*;
import android.view.View;
import android.widget.*;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.EditorSearcher;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;
import io.github.rosemoe.sora.event.ContentChangeEvent;
import io.github.rosemoe.sora.event.PublishSearchResultEvent;
import io.github.rosemoe.sora.event.SelectionChangeEvent;
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
    private boolean dirty, loading, loaded, saving, paged, reading, readOnly, reviewOnly;
    private boolean transcodedPages, uncertainEncoding, controlCharacters, denseControls;
    private CodeLanguage.Kind kind = CodeLanguage.Kind.GENERIC;
    private TextView position, matchCount, regexToggle, caseToggle;
    private LinearLayout searchBar, replaceRow, symbols;
    private View undoButton, redoButton;
    private final List<View> bodyControls = new ArrayList<>();
    private EditText findInput, replaceInput;
    private boolean regexSearch, caseSensitive = true, imeVisible = android.os.Build.VERSION.SDK_INT < 30;
    private android.graphics.Typeface defaultTypeface;
    private volatile long savedSize = -1;
    private long offset, length;
    private File archive, preview, pageFile;
    private PagedTextFile.Page currentPage;
    private final ArrayDeque<Long> previousPages = new ArrayDeque<>();
    private String entry, mode;
    private int loadGeneration;
    private EditorSession session;
    private EditorDrafts.State pendingDraft;
    private String blockedSave;
    private Runnable afterSave;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long checkpointStarted;
    private final Runnable draftCheckpoint = () -> checkpoint(true);
    private record Loaded(TextCodec.Decoded decoded, Content content, boolean paged) {}
    private record Opening(FileStore.Node source, String document, EditorDrafts.State draft, boolean automatic,
                           IOException unavailable) {}
    private boolean classEdit() { return "smali-class".equals(mode); }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null && saved.getBoolean("savedForCaller")) getIntent().putExtra("savedForCaller", true);
        if (getIntent().getBooleanExtra("savedForCaller", false)) setResult(RESULT_OK);
        if (saved != null && saved.containsKey("sourceRevision"))
            getIntent().putExtra("sourceRevision", saved.getString("sourceRevision"));
        store = new FileStore(this);
        session = new ViewModelProvider(this).get(EditorSession.class);
        session.sourceSaved |= getIntent().getBooleanExtra("savedForCaller", false);
        try { session.initialize(getApplicationContext(), saved == null ? null : saved.getString("editorSession")); }
        catch (IOException error) { Tasks.showError(this, error); finish(); return; }
        entry = getIntent().getStringExtra("archiveEntry");
        mode = getIntent().getStringExtra("mode");
        readOnly = getIntent().getBooleanExtra("readOnly", false);
        if (getIntent().hasExtra("archivePath")) archive = new File(getIntent().getStringExtra("archivePath"));
        if (getIntent().hasExtra("preview")) preview = new File(getIntent().getStringExtra("preview"));
        LinearLayout root = Ui.column(this);
        String name = getIntent().getStringExtra("displayName");
        if (name == null) name = getIntent().getStringExtra("name");
        LinearLayout top = Ui.toolbar(this, name == null ? "编辑器" : name, this::leave);
        top.addView(Ui.icon(this, R.drawable.ic_search, "查找与替换", Color.WHITE, this::openSearch), lp(48, 52));
        View saveButton = Ui.icon(this, R.drawable.ic_save, "保存", Color.WHITE, this::save);
        if (readOnly) saveButton.setVisibility(View.GONE);
        top.addView(saveButton, lp(48, 52));
        top.addView(Ui.icon(this, R.drawable.ic_more, "编辑器菜单", Color.WHITE, this::menu), lp(48, 52));
        root.addView(top, lp(-1, 52));
        root.addView(buildSearchBar());
        editor = new CodeEditor(this); editor.setTabWidth(4);
        editor.setRenderFunctionCharacters(true);
        kind = classEdit() ? CodeLanguage.Kind.SMALI : "xml".equals(mode) ? CodeLanguage.Kind.MARKUP
                : mode != null && mode.startsWith("arsc") ? CodeLanguage.Kind.JSON
                : CodeLanguage.kindFor(entry != null ? entry : name);
        applyLanguage();
        editor.setEditable(false);
        applyDisplayPreferences();
        onPaletteChanged();
        root.addView(editor, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(Ui.line(this));
        LinearLayout bottom = Ui.row(this); bottom.setBackgroundColor(Ui.SURFACE);
        undoButton = Ui.icon(this, R.drawable.ic_back, "撤销 / 上一页", Ui.TEXT, () -> {
            if (paged) changePage(false); else undo();
        }); bottom.addView(undoButton, lp(48, 48));
        redoButton = Ui.icon(this, R.drawable.ic_back, "重做 / 下一页", Ui.TEXT, () -> {
            if (paged) changePage(true); else redo();
        }); redoButton.setRotation(180); bottom.addView(redoButton, lp(48, 48));
        info = Ui.single(this, "读取中…", 10, Ui.MUTED);
        bottom.addView(info, new LinearLayout.LayoutParams(0, -1, 1));
        position = Ui.single(this, "", 11, Ui.MUTED); position.setGravity(android.view.Gravity.CENTER);
        position.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 12), 0); position.setContentDescription("光标位置 · 跳转到行");
        position.setMinimumWidth(Ui.dp(this, 48)); position.setBackground(Ui.ripple(Ui.SURFACE)); position.setOnClickListener(v -> gotoLine());
        bottom.addView(position, new LinearLayout.LayoutParams(-2, -1)); root.addView(bottom, lp(-1, 48));
        root.addView(buildSymbolBar(), lp(-1, 48));
        Ui.install(this, root);
        root.getViewTreeObserver().addOnGlobalLayoutListener(this::updateSymbolBar);
        updateEditingControls();
        editor.subscribeEvent(ContentChangeEvent.class, (e, unsubscribe) -> {
            if (loading) return;
            session.contentRevision++;
            if (loaded) {
                dirty = true; details();
                // Public Sora APIs can bypass editable. Keep the latest body even if this Activity rotates mid-save.
                if (saving || session.saving) checkpoint(true); else scheduleCheckpoint();
            }
        });
        editor.subscribeEvent(SelectionChangeEvent.class, (e, unsubscribe) -> { updatePosition(); updateMatchCount(); });
        editor.subscribeEvent(PublishSearchResultEvent.class, (e, unsubscribe) -> updateMatchCount());
        openEditor(saved != null);
    }
    private void openEditor(boolean recreated) {
        if (isDestroyed() || isFinishing()) return;
        if (session.saving) { info.setText("保存中…"); handler.postDelayed(() -> openEditor(recreated), 50); return; }
        runEditor(() -> {
            EditorDrafts.State known = session.memory;
            if (known == null && recreated) known = EditorDrafts.read(this, session.drafts.id);
            FileStore.Node node;
            try { node = store.fromIntent(getIntent()); }
            catch (IOException | RuntimeException unavailable) {
                if (known == null || !known.metadata().optBoolean("dirty", true)) throw unavailable;
                IOException reason = unavailable instanceof IOException io ? io : new IOException(unavailable.getMessage(), unavailable);
                return new Opening(null, known.document(), known, true, reason);
            }
            String document = String.join("\u0000", documentKey(node), Objects.toString(entry, ""),
                    Objects.toString(mode, ""), Objects.toString(getIntent().getStringExtra("dexEntry"), ""),
                    Objects.toString(getIntent().getStringExtra("descriptor"), ""));
            EditorDrafts.State state = known;
            boolean automatic = state != null;
            if (state == null && !readOnly) state = EditorDrafts.latest(this, document);
            if (state != null && !state.document().equals(document)) state = null;
            return new Opening(node, document, state, automatic, null);
        }, opened -> {
            if (opened.unavailable() != null) { reviewDraft(opened.draft(), opened.unavailable()); return; }
            source = opened.source(); session.document = opened.document();
            if (opened.draft() == null) { load(null); return; }
            if (opened.automatic()) { restoreDraft(opened.draft()); return; }
            new AlertDialog.Builder(this).setTitle("发现未保存草稿")
                    .setMessage("恢复上次编辑内容？原文件不会自动改写。")
                    .setPositiveButton("恢复草稿", (d, which) -> restoreDraft(opened.draft()))
                    .setNegativeButton("放弃草稿", (d, which) -> {
                        try { session.restore(getApplicationContext(), opened.draft()); session.discard(); load(null); }
                        catch (IOException error) { Tasks.showError(this, error); }
                    }).setNeutralButton("取消", (d, which) -> finish()).setOnCancelListener(d -> finish()).show();
        }, error -> { info.setText("无法读取 · 草稿仍保留"); Tasks.showError(this, error); });
    }
    private void reviewDraft(EditorDrafts.State state, IOException reason) {
        try { session.restore(getApplicationContext(), state); }
        catch (IOException error) { Tasks.showError(this, error); return; }
        reviewOnly = true;
        blockedSave = "无法访问原文件，草稿只读显示；请从原来源重新打开后再保存";
        JSONObject data = state.metadata();
        charset = Charset.forName(data.optString("charset", "UTF-8"));
        bom = Base64.decode(data.optString("bom"), Base64.DEFAULT);
        paged = data.optBoolean("paged"); offset = data.optLong("start"); length = data.optLong("length");
        dirty = true; reading = false;
        loading = true; editor.setText(state.content().copyTextShallow(true), true, null); loading = false;
        classifyControls(editor.getText().toString());
        loaded = true; updateEditingControls(); details(); updatePosition();
        new AlertDialog.Builder(this).setTitle("原文件暂时无法访问")
                .setMessage(blockedSave + "。\n" + (reason.getMessage() == null ? reason.getClass().getSimpleName() : reason.getMessage()))
                .setPositiveButton("知道了", null).show();
    }
    private static String documentKey(FileStore.Node node) {
        if (node.container != null) return documentKey(node.container) + "!/" + node.entry;
        return node.file != null ? node.file.toPath().toAbsolutePath().normalize().toString() : node.uri.toString();
    }
    private void restoreDraft(EditorDrafts.State state) {
        try {
            session.restore(getApplicationContext(), state); pendingDraft = state;
            session.baseline = state.metadata().optString("baseline");
            load(state.metadata().optBoolean("paged") ? state.metadata().optString("charset", "UTF-8") : null);
        } catch (IOException error) { Tasks.showError(this, error); }
    }
    private <T> void runEditor(Tasks.Job<T> work, Tasks.Done<T> done, Tasks.Done<Exception> failed) {
        session.begin();
        Tasks.IO.execute(() -> {
            try {
                T result = work.run();
                handler.post(() -> { if (!isFinishing() && !isDestroyed()) done.accept(result); });
            } catch (Exception error) {
                handler.post(() -> { if (!isFinishing() && !isDestroyed()) failed.accept(error); });
            } finally { session.end(); }
        });
    }
    private void own(File file) { session.own(file); }
    private String fingerprint() throws IOException { return store.fingerprint(source); }
    private void prepareArchive() throws IOException {
        if (archive == null) return;
        if (session.archive != null && session.archive.isFile()) { archive = session.archive; return; }
        File original = archive;
        File copy = File.createTempFile("editor-archive-", "-" + source.name.replaceAll("[^a-zA-Z0-9.]", "_"), getCacheDir());
        own(copy);
        try (InputStream input = store.read(source); OutputStream output = new FileOutputStream(copy)) { FileStore.pump(input, output); }
        if (source.file != null) ArchiveOps.copyPassword(source.file, copy);
        archive = copy; session.archive = copy;
        if (getIntent().getBooleanExtra("ownedArchive", false)) { own(original); session.ownParent(original.getParentFile()); }
    }
    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w < 0 ? w : Ui.dp(this, w), h < 0 ? h : Ui.dp(this, h));
    }
    private void load(String encoding) {
        int token = ++loadGeneration;
        loaded = false; reading = true; updateEditingControls(); info.setText("读取中…");
        runEditor(() -> {
            String observed = fingerprint();
            String workspaceRevision = getIntent().getStringExtra("sourceRevision");
            if (workspaceRevision != null) {
                if (session.baseline != null) workspaceRevision = session.baseline;
                if (!workspaceRevision.equals(observed))
                    throw new IOException("工作副本对应的原文件已变化，请返回工具页刷新后重新打开");
            }
            if (session.baseline == null) session.baseline = observed;
            else if (!session.baseline.equals(observed)) blockedSave = "原文件已变化，草稿仅供核对，请重新打开原文件后合并修改";
            prepareArchive();
            if (classEdit()) {
                boolean draftText = pendingDraft != null && pendingDraft.metadata().optBoolean("dirty", true);
                if (pendingDraft != null || preview == null || !preview.isFile()) {
                    File rebuilt = File.createTempFile("editor-class-", ".smali", getCacheDir()); own(rebuilt);
                    if (draftText) Files.write(rebuilt.toPath(), pendingDraft.content().toString().getBytes(StandardCharsets.UTF_8));
                    else ApkTools.disassembleClass(archive, Objects.toString(getIntent().getStringExtra("dexEntry"), ""),
                            getIntent().getStringExtra("descriptor"), rebuilt);
                    preview = rebuilt;
                }
            }
            transcodedPages = false;
            byte[] bytes = readContent();
            if (!observed.equals(fingerprint())) throw new IOException("读取期间原文件已变化，请重新打开");
            boolean structured = mode != null && (mode.startsWith("arsc") || mode.equals("xml") || classEdit());
            if (structured && bytes.length > STRUCTURED_LIMIT) throw new IOException("结构化编辑内容超过 32 MiB");
            boolean large = bytes.length > LIMIT && !structured;
            TextCodec.Decoded decoded = TextCodec.decodeForEditor(large ? Arrays.copyOf(bytes, PAGE) : bytes, encoding, large);
            if (large && TextCodec.isStateful(decoded.charset())) {
                File converted = File.createTempFile("stateful-editor-", ".txt", getCacheDir());
                own(converted);
                try {
                    TextCodec.transcode(pageFile, converted, decoded.charset(), StandardCharsets.UTF_8, decoded.bom(), new byte[0]);
                } catch (Exception e) { Files.deleteIfExists(converted.toPath()); throw e; }
                pageFile = converted; length = converted.length(); transcodedPages = true;
            }
            return new Loaded(decoded, large ? null : new Content(decoded.text()), large);
        }, result -> {
            if (token != loadGeneration) return;
            reading = false;
            if (getIntent().hasExtra("sourceRevision") && blockedSave == null)
                getIntent().putExtra("sourceRevision", session.baseline);
            TextCodec.Decoded decoded = result.decoded();
            charset = decoded.charset(); bom = decoded.bom(); paged = result.paged(); uncertainEncoding = decoded.uncertain();
            classifyControls(decoded.text());
            if (paged) {
                offset = 0; previousPages.clear();
                page(pendingDraft == null ? 0 : pendingDraft.metadata().optLong("start")); return;
            }
            loading = true; editor.setText(result.content(), true, null);
            loading = false; loaded = true; dirty = false; completeLoad();
        }, error -> {
            if (token != loadGeneration) return;
            reading = false;
            if (pendingDraft != null) {
                blockedSave = "无法读取原文件，草稿已保留；恢复来源访问权限后再保存";
                loaded = true; completeLoad();
            } else { info.setText("无法解码 · 可重新选择编码"); Tasks.showError(this, error); }
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
                own(pageFile);
                ArchiveOps.extractOne(archive, entry, pageFile);
            } else {
                pageFile = preview != null ? preview : store.materialize(source);
                if (preview == null && (source.file == null || source.root)) own(pageFile);
            }
            length = pageFile.length();
            try (InputStream in = new FileInputStream(pageFile)) {
                return FileStore.readBytes(in, classEdit() ? STRUCTURED_LIMIT + 1 : LIMIT + 1);
            }
    }
    private void page(long position) {
        if (position >= length && position != 0) {
            if (pendingDraft != null) { blockedSave = "原文件分段已变化，草稿已保留"; completeLoad(); }
            return;
        }
        offset = position; loaded = false; reading = true; updateEditingControls();
        runEditor(() -> {
            PagedTextFile.Page result = PagedTextFile.read(pageFile, position,
                    transcodedPages ? StandardCharsets.UTF_8 : charset, transcodedPages ? new byte[0] : bom, PAGE);
            return new PageContent(result, new Content(result.text()));
        }, result -> {
            reading = false;
            currentPage = result.page();
            classifyControls(currentPage.text());
            loading = true; editor.setText(result.content(), true, null); loading = false; loaded = true; dirty = false;
            completeLoad();
        }, error -> {
            reading = false;
            if (pendingDraft != null) {
                blockedSave = "原文件分段已变化，草稿已保留；请核对后重新打开原文件";
                loaded = true; completeLoad();
            } else { info.setText("分段读取失败"); Tasks.showError(this, error); }
        });
    }
    private void completeLoad() {
        EditorDrafts.State state = pendingDraft; pendingDraft = null;
        if (state != null) {
            JSONObject data = state.metadata();
            charset = Charset.forName(data.optString("charset", "UTF-8"));
            bom = Base64.decode(data.optString("bom"), Base64.DEFAULT);
            dirty = data.optBoolean("dirty", true); paged = data.optBoolean("paged");
            uncertainEncoding = data.optBoolean("uncertain"); transcodedPages = data.optBoolean("transcoded");
            if (dirty) { loading = true; editor.setText(state.content().copyTextShallow(true), true, null); loading = false; }
            if (paged) {
                offset = data.optLong("start");
                currentPage = new PagedTextFile.Page(offset, data.optLong("end"), editor.getText().toString());
                previousPages.clear(); JSONArray previous = data.optJSONArray("previous");
                if (previous != null) for (int i = 0; i < previous.length(); i++) previousPages.addLast(previous.optLong(i));
            }
            classifyControls(editor.getText().toString());
            session.memory = state;
            handler.post(() -> {
                if (isDestroyed() || isFinishing()) return;
                Content content = editor.getText();
                int left = Math.max(0, Math.min(data.optInt("leftLine"), content.getLineCount() - 1));
                int right = Math.max(left, Math.min(data.optInt("rightLine", left), content.getLineCount() - 1));
                int leftColumn = Math.max(0, Math.min(data.optInt("leftColumn"), content.getColumnCount(left)));
                int rightColumn = Math.max(left == right ? leftColumn : 0,
                        Math.min(data.optInt("rightColumn", leftColumn), content.getColumnCount(right)));
                editor.setSelectionRegion(left, leftColumn, right, rightColumn);
                editor.getScroller().startScroll(editor.getOffsetX(), editor.getOffsetY(),
                        data.optInt("scrollX") - editor.getOffsetX(), data.optInt("scrollY") - editor.getOffsetY(), 0);
                editor.invalidate();
            });
        }
        loaded = true; updateEditingControls(); details();
        updatePosition(); updateSymbolBar(); runSearch();
        if (blockedSave != null) new AlertDialog.Builder(this).setTitle("草稿需要核对")
                .setMessage(blockedSave + "。不会自动覆盖原文件。")
                .setPositiveButton("知道了", null).show();
    }
    private void scheduleCheckpoint() {
        if (readOnly || preview != null && !classEdit() || source == null) return;
        long now = SystemClock.uptimeMillis();
        if (checkpointStarted == 0) checkpointStarted = now;
        handler.removeCallbacks(draftCheckpoint);
        handler.postAtTime(draftCheckpoint, Math.min(now + 500, checkpointStarted + 2000));
    }
    private void checkpoint(boolean persist) {
        handler.removeCallbacks(draftCheckpoint); checkpointStarted = 0;
        if (!loaded || reading || source == null || readOnly
                || preview != null && !classEdit() || session.document == null) return;
        try {
            JSONObject data = new JSONObject().put("time", System.currentTimeMillis()).put("dirty", dirty)
                    .put("baseline", session.baseline).put("charset", charset.name())
                    .put("bom", Base64.encodeToString(bom, Base64.NO_WRAP)).put("paged", paged)
                    .put("start", paged ? currentPage.start() : 0).put("end", paged ? currentPage.end() : 0)
                    .put("length", length).put("uncertain", uncertainEncoding).put("transcoded", transcodedPages)
                    .put("previous", new JSONArray(previousPages)).put("leftLine", editor.getCursor().getLeftLine())
                    .put("leftColumn", editor.getCursor().getLeftColumn()).put("rightLine", editor.getCursor().getRightLine())
                    .put("rightColumn", editor.getCursor().getRightColumn()).put("scrollX", editor.getOffsetX()).put("scrollY", editor.getOffsetY());
            EditorDrafts.State state = dirty ? session.drafts.remember(session.document, data, editor.getText().copyTextShallow(true))
                    : new EditorDrafts.State(session.drafts.id, session.document, 0, data, new Content(""));
            session.memory = state;
            if (persist && dirty) {
                session.drafts.persist(state);
                EditorDrafts.WORKER.execute(() -> {
                    IOException failure = session.drafts.error();
                    if (failure != null) handler.post(() -> {
                        if (!isDestroyed() && !isFinishing()) { details(); Tasks.toast(this, "草稿写入失败，原文件未改写：" + failure.getMessage()); }
                    });
                });
            }
        } catch (JSONException error) { Tasks.showError(this, error); }
    }
    private record PageContent(PagedTextFile.Page page, Content content) {}
    private void changePage(boolean next) {
        if (!loaded) return;
        if (saving) { afterSave = () -> changePage(next); return; }
        if (blockedSave != null) { Tasks.toast(this, blockedSave); return; }
        Runnable navigate = () -> {
            if (next && currentPage.end() < length) {
                previousPages.push(offset); page(currentPage.end());
            } else if (!next && !previousPages.isEmpty()) page(previousPages.pop());
        };
        if (dirty) new AlertDialog.Builder(this).setTitle("当前分段尚未保存")
                .setNegativeButton("继续编辑", null).setPositiveButton("放弃修改", (d, w) -> {
                    if (saving || session.saving) { afterSave = () -> changePage(next); return; }
                    dirty = false; handler.removeCallbacks(draftCheckpoint); session.discard(); navigate.run();
                })
                .setNeutralButton("保存并翻页", (d, w) -> save(navigate, () -> changePage(next))).show();
        else navigate.run();
    }
    private void details() {
        info.setText(charset.name() + (uncertainEncoding ? " · 推测" : "") + (controlCharacters ? (denseControls ? " · 控制字符·简化显示" : " · 控制字符") : "") + " · " + (paged ? "分段 " + FileStore.size(offset) + "/" + FileStore.size(length)
                + (dirty ? " · 未保存" : "") + (readOnly ? " · 只读" : "") :
                editor.getLineCount() + " 行" + (mode == null && entry == null && preview == null && length > 0 ? " · " + FileStore.size(length) : "")
                + (dirty ? " · 未保存" : (readOnly || preview != null) && !classEdit() ? " · 只读" : ""))
                + (blockedSave == null ? "" : " · 来源待核对")
                + (session.drafts.error() == null ? "" : " · 草稿写入失败"));
    }
    /**
     * Sora measures each control-character badge against its whole line, so dense binary pages cost
     * hundreds of milliseconds per frame. Such pages draw control bytes as plain glyphs instead.
     */
    private void classifyControls(String text) {
        int total = 0, line = 0, worstLine = 0;
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (value == '\n') { worstLine = Math.max(worstLine, line); line = 0; }
            else if (value < 32 && value != '\r' && value != '\t') { total++; line++; }
        }
        worstLine = Math.max(worstLine, line);
        controlCharacters = total > 0;
        boolean dense = total > 8192 || worstLine > 512;
        if (dense && !denseControls) Tasks.toast(this, "控制字符过多，已改为简化显示以保持流畅");
        denseControls = dense;
        editor.setRenderFunctionCharacters(!dense);
        applyLanguage();
        applyWordwrap();
    }
    /** Dense binary pages get plain styling: regex highlighting of control bytes is noise and costs time. */
    private void applyLanguage() {
        CodeLanguage.Kind effective = denseControls ? CodeLanguage.Kind.PLAIN : kind;
        if (editor.getEditorLanguage() instanceof CodeLanguage current && current.kind == effective) return;
        editor.setEditorLanguage(new CodeLanguage(effective));
    }
    private void save() { save(null); }
    private void save(Runnable done) { save(done, this::leave); }
    private void save(Runnable done, Runnable changed) {
        if (reviewOnly) { Tasks.toast(this, blockedSave); return; }
        if (!loaded || reading || ((readOnly || preview != null) && !classEdit()) || saving || session.saving) {
            Tasks.toast(this, ((readOnly || preview != null) && !classEdit()) ? "当前为只读预览" : "等待读写完成"); return;
        }
        checkpoint(true);
        long revision = session.contentRevision;
        String text = editor.getText().toString(); Charset current = charset; byte[] mark = bom;
        saving = true; session.saving = true; updateEditingControls(); info.setText("保存中…");
        session.begin();
        Tasks.IO.execute(() -> {
            String baseline = null; Exception failure = null;
            try {
                if (blockedSave != null) throw new IOException(blockedSave);
                store.checkWritable(source);
                if (!Objects.equals(session.baseline, fingerprint()))
                    throw new IOException("原文件已在外部改变，草稿已保留；请重新打开核对后再保存");
                baseline = writeContent(text, current, mark);
            } catch (Exception error) { failure = error; }
            String published = baseline; Exception error = failure; long written = savedSize;
            // Unlike ordinary load callbacks, finalization must survive onDestroy's handler cleanup.
            // The recreated Activity waits on session.saving until baseline AND draft metadata agree.
            new Handler(Looper.getMainLooper()).post(() -> completeSave(revision, published, written, error, done, changed));
        });
    }
    private void completeSave(long revision, String baseline, long written, Exception error, Runnable done, Runnable changed) {
        try {
            boolean clean = false;
            if (error == null) {
                getIntent().putExtra("savedForCaller", true); setResult(RESULT_OK);
                if (written >= 0) length = written;
                try { clean = session.saved(revision, baseline, paged ? length : written, paged ? currentPage : null); }
                catch (JSONException failure) { error = failure; }
            }
            saving = false; session.saving = false;
            if (isFinishing() || isDestroyed()) return;
            updateEditingControls();
            if (getIntent().hasExtra("sourceRevision")) getIntent().putExtra("sourceRevision", session.baseline);
            if (error != null) {
                afterSave = null;
                dirty = true; details(); checkpoint(true); Tasks.showError(this, error); return;
            }
            dirty = !clean; details(); checkpoint(!clean);
            Tasks.toast(this, clean ? "已保存" : "已保存快照；当前修改尚未保存");
            Runnable queued = afterSave; afterSave = null;
            if (clean) { if (done != null) done.run(); }
            else if (done != null && queued == null) changed.run();
            // These are leave/changePage entry points, not raw finish/page actions: they recheck dirty.
            if (queued != null && !isFinishing()) queued.run();
        } finally { session.end(); }
    }
    private String writeContent(String text, Charset current, byte[] mark) throws Exception {
        savedSize = -1;
        String revision = null;
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
                    ArchiveOps.rewrite(archive, null, null, Map.of(entry, payload)); revision = store.write(source, archive, session.baseline);
                } else revision = store.write(source, payload, session.baseline);
                if (!pageFile.equals(source.file) || archive != null) FileStore.replace(tmp, pageFile);
                currentPage = result; length = pageFile.length();
            } finally {
                Files.deleteIfExists(tmp.toPath());
                if (encoded != null) Files.deleteIfExists(encoded.toPath());
            }
            return revision;
        } else if (classEdit()) {
            ApkTools.saveDexClass(archive, getIntent().getStringExtra("dexEntry"),
                    getIntent().getStringExtra("descriptor"), text, getCacheDir());
            Files.write(preview.toPath(), text.getBytes(StandardCharsets.UTF_8));
        } else if ("xml".equals(mode)) ApkTools.saveXml(archive, entry, text);
        else if ("arsc".equals(mode)) ApkTools.saveArsc(archive, entry, text);
        else if ("arsc-file".equals(mode)) {
            File tmp = File.createTempFile("resources-", ".arsc", getCacheDir());
            try { ApkTools.saveArscFile(tmp, text); return store.write(source, tmp, session.baseline); }
            finally { Files.deleteIfExists(tmp.toPath()); }
        } else {
            byte[] encoded = TextCodec.encode(text, current, mark);
            File tmp = File.createTempFile("editor-", ".tmp", getCacheDir());
            try {
                Files.write(tmp.toPath(), encoded);
                if (archive != null && entry != null) ArchiveOps.rewrite(archive, null, null, Map.of(entry, tmp));
                else { revision = store.write(source, tmp, session.baseline); savedSize = encoded.length; }
            } finally { Files.deleteIfExists(tmp.toPath()); }
        }
        if (archive != null) return store.write(source, archive, session.baseline);
        return revision;
    }
    private View buildSearchBar() {
        searchBar = Ui.column(this); searchBar.setBackgroundColor(Ui.PANEL); searchBar.setVisibility(View.GONE);
        searchBar.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
        LinearLayout findRow = Ui.row(this);
        findInput = searchInput("查找"); findInput.setContentDescription("查找内容");
        findInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        findInput.setOnEditorActionListener((v, action, event) -> { jumpMatch(true); return true; });
        findRow.addView(findInput, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1));
        matchCount = Ui.single(this, "", 11, Ui.MUTED); matchCount.setGravity(android.view.Gravity.CENTER);
        findRow.addView(matchCount, lp(56, 48));
        View previous = Ui.icon(this, R.drawable.ic_up, "上一处", Ui.TEXT, () -> jumpMatch(false));
        findRow.addView(previous, lp(48, 48));
        View next = Ui.icon(this, R.drawable.ic_up, "下一处", Ui.TEXT, () -> jumpMatch(true));
        next.setRotation(180); findRow.addView(next, lp(48, 48));
        TextView close = Ui.text(this, "×", 22, Ui.MUTED); close.setGravity(android.view.Gravity.CENTER);
        close.setContentDescription("关闭查找"); close.setBackground(Ui.ripple(Ui.PANEL));
        close.setOnClickListener(v -> closeSearch()); findRow.addView(close, lp(48, 48));
        searchBar.addView(findRow);
        replaceRow = Ui.row(this);
        replaceInput = searchInput("替换为"); replaceInput.setContentDescription("替换内容");
        replaceRow.addView(replaceInput, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1));
        regexToggle = toggle(".*", "正则表达式", () -> regexSearch = !regexSearch);
        caseToggle = toggle("Aa", "区分大小写", () -> caseSensitive = !caseSensitive);
        replaceRow.addView(regexToggle, lp(48, 48)); replaceRow.addView(caseToggle, lp(48, 48));
        replaceRow.addView(searchAction("替换", this::replaceOne), lp(-2, 48));
        replaceRow.addView(searchAction("全部替换", this::replaceAll), lp(-2, 48));
        searchBar.addView(replaceRow);
        MainActivity.watch(findInput, this::runSearch);
        View.OnFocusChangeListener focus = (v, has) -> updateSymbolBar();
        findInput.setOnFocusChangeListener(focus); replaceInput.setOnFocusChangeListener(focus);
        updateToggles();
        return searchBar;
    }
    private EditText searchInput(String hint) {
        EditText input = Ui.input(this, hint); input.setTextSize(13);
        input.setBackground(Ui.background(Ui.SURFACE, Ui.dp(this, 4)));
        input.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        return input;
    }
    private TextView toggle(String label, String description, Runnable flip) {
        TextView toggle = Ui.text(this, label, 13, Ui.MUTED); toggle.setGravity(android.view.Gravity.CENTER);
        toggle.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        toggle.setContentDescription(description);
        toggle.setOnClickListener(v -> { flip.run(); updateToggles(); runSearch(); });
        return toggle;
    }
    private void updateToggles() {
        for (TextView toggle : new TextView[]{regexToggle, caseToggle}) {
            boolean on = toggle == regexToggle ? regexSearch : caseSensitive;
            toggle.setTextColor(on ? Ui.ACCENT : Ui.MUTED); toggle.setSelected(on);
            toggle.setBackground(Ui.background(on ? Ui.TINT : Ui.PANEL, Ui.dp(this, 4)));
        }
    }
    private TextView searchAction(String label, Runnable action) {
        TextView button = Ui.text(this, label, 13, Ui.ACCENT); button.setGravity(android.view.Gravity.CENTER);
        button.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0); button.setBackground(Ui.ripple(Ui.PANEL));
        button.setMinimumWidth(Ui.dp(this, 48)); button.setMinimumHeight(Ui.dp(this, 48)); button.setFocusable(true);
        button.setOnClickListener(v -> action.run()); bodyControls.add(button);
        return button;
    }
    private void openSearch() {
        if (!loaded) return;
        if (searchBar.getVisibility() == View.VISIBLE) { closeSearch(); return; }
        replaceRow.setVisibility(canReplace() ? View.VISIBLE : View.GONE);
        findInput.setHint(paged ? "查找（当前分段）" : "查找");
        io.github.rosemoe.sora.text.Cursor cursor = editor.getCursor();
        if (cursor.isSelected() && cursor.getLeftLine() == cursor.getRightLine() && cursor.getRight() - cursor.getLeft() <= 100)
            findInput.setText(editor.getText().subSequence(cursor.getLeft(), cursor.getRight()));
        searchBar.setVisibility(View.VISIBLE);
        searchBar.animate().cancel(); searchBar.setAlpha(.4f); searchBar.setTranslationY(-Ui.dp(this, 8));
        searchBar.animate().alpha(1f).translationY(0).setDuration(Ui.motionEnabled() ? Ui.MOTION_SHORT : 0)
                .setInterpolator(Ui.EASING).start();
        findInput.requestFocus(); findInput.selectAll();
        ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .showSoftInput(findInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        runSearch();
    }
    private boolean closeSearch() {
        if (searchBar == null || searchBar.getVisibility() != View.VISIBLE) return false;
        searchBar.animate().cancel(); searchBar.setAlpha(1f); searchBar.setTranslationY(0);
        searchBar.setVisibility(View.GONE); editor.getSearcher().stopSearch(); findInput.setError(null);
        ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(findInput.getWindowToken(), 0);
        editor.requestFocus(); updateSymbolBar();
        return true;
    }
    private void runSearch() {
        if (searchBar == null || searchBar.getVisibility() != View.VISIBLE) return;
        String needle = findInput.getText().toString();
        findInput.setError(null);
        if (needle.isEmpty()) { editor.getSearcher().stopSearch(); matchCount.setText(""); return; }
        if (regexSearch) {
            try { Pattern.compile(needle); }
            catch (java.util.regex.PatternSyntaxException error) {
                editor.getSearcher().stopSearch(); findInput.setError("正则错误：" + error.getDescription()); matchCount.setText(""); return;
            }
        }
        editor.getSearcher().search(needle, new EditorSearcher.SearchOptions(regexSearch
                ? EditorSearcher.SearchOptions.TYPE_REGULAR_EXPRESSION : EditorSearcher.SearchOptions.TYPE_NORMAL, !caseSensitive));
        matchCount.setText("…");
    }
    private void updateMatchCount() {
        if (matchCount == null || searchBar.getVisibility() != View.VISIBLE) return;
        EditorSearcher searcher = editor.getSearcher();
        if (!searcher.hasQuery()) { matchCount.setText(""); return; }
        try {
            int total = searcher.getMatchedPositionCount(), current = searcher.getCurrentMatchedPositionIndex();
            matchCount.setText(total == 0 ? "无匹配" : (current < 0 ? "-" : String.valueOf(current + 1)) + "/" + total);
        } catch (RuntimeException pending) { matchCount.setText("…"); }
    }
    private void jumpMatch(boolean forward) {
        EditorSearcher searcher = editor.getSearcher();
        if (!searcher.hasQuery()) { findInput.requestFocus(); return; }
        try { if (!(forward ? searcher.gotoNext() : searcher.gotoPrevious())) Tasks.toast(this, "没有匹配"); }
        catch (RuntimeException pending) { Tasks.toast(this, "正在查找，请稍候"); }
        updateMatchCount();
    }
    private boolean canReplace() { return !reviewOnly && !readOnly && (preview == null || classEdit()); }
    private boolean canMutateBody() {
        return loaded && !loading && !reading && !saving && !session.saving && canReplace() && editor.isEditable();
    }
    private void updateEditingControls() {
        boolean editable = loaded && !loading && !reading && !saving && !session.saving && canReplace();
        editor.setEditable(editable);
        for (View control : bodyControls) control.setEnabled(editable);
        // Page arrows must remain available to queue navigation while a save is in flight.
        if (undoButton != null) undoButton.setEnabled(paged ? loaded : editable);
        if (redoButton != null) redoButton.setEnabled(paged ? loaded : editable);
        updateSymbolBar();
    }
    private void undo() { if (canMutateBody()) editor.undo(); }
    private void redo() { if (canMutateBody()) editor.redo(); }
    private void replaceOne() {
        if (!canMutateBody()) { Tasks.toast(this, "当前不可修改"); return; }
        EditorSearcher searcher = editor.getSearcher();
        if (!searcher.hasQuery()) return;
        try {
            if (searcher.isMatchedPositionSelected()) searcher.replaceCurrentMatch(replaceInput.getText().toString());
            else if (!searcher.gotoNext()) Tasks.toast(this, "没有匹配");
        } catch (RuntimeException pending) { Tasks.toast(this, "正在查找，请稍候"); }
        updateMatchCount();
    }
    private void replaceAll() {
        if (!canMutateBody()) { Tasks.toast(this, "当前不可修改"); return; }
        if (!editor.getSearcher().hasQuery()) return;
        try { editor.getSearcher().replaceAll(replaceInput.getText().toString()); }
        catch (RuntimeException error) { Tasks.toast(this, error.getMessage() == null ? "正在查找，请稍候" : error.getMessage()); }
    }
    private static final String[] SYMBOLS = {"Tab", "{", "}", "(", ")", "[", "]", "<", ">", "\"", "'", ";", ":",
            "=", "+", "-", "*", "/", "\\", "|", "&", "!", "?", "$", "#", "%", "_", ".", ",", "@", "~", "^", "`"};
    private View buildSymbolBar() {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false); scroll.setBackgroundColor(Ui.PANEL);
        symbols = Ui.row(this);
        for (String symbol : SYMBOLS) {
            TextView key = Ui.text(this, symbol, symbol.length() > 1 ? 12 : 16, Ui.TEXT);
            key.setGravity(android.view.Gravity.CENTER); key.setTypeface(android.graphics.Typeface.MONOSPACE);
            key.setContentDescription("插入 " + symbol); key.setBackground(Ui.ripple(Ui.PANEL));
            key.setMinimumWidth(Ui.dp(this, symbol.length() > 1 ? 52 : 48));
            key.setOnClickListener(v -> insertSymbol(symbol)); bodyControls.add(key);
            symbols.addView(key, new LinearLayout.LayoutParams(-2, -1));
        }
        scroll.addView(symbols, new FrameLayout.LayoutParams(-2, -1));
        scroll.setVisibility(View.GONE);
        return scroll;
    }
    private void insertSymbol(String symbol) {
        if (!canMutateBody()) return;
        if (symbol.equals("Tab")) editor.indentOrCommitTab();
        else editor.insertText(symbol, symbol.length());
    }
    private void updateSymbolBar() {
        if (symbols == null) return;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsets insets = getWindow().getDecorView().getRootWindowInsets();
            imeVisible = insets != null && insets.isVisible(android.view.WindowInsets.Type.ime());
        }
        boolean searching = findInput.hasFocus() || replaceInput.hasFocus();
        boolean show = canMutateBody() && imeVisible && !searching;
        View bar = (View) symbols.getParent();
        int wanted = show ? View.VISIBLE : View.GONE;
        if (bar.getVisibility() != wanted) { bar.setVisibility(wanted); if (show) Ui.fadeIn(bar); }
    }
    private void updatePosition() {
        if (position == null || !loaded) return;
        io.github.rosemoe.sora.text.Cursor cursor = editor.getCursor();
        position.setText(cursor.isSelected() ? "选中 " + (cursor.getRight() - cursor.getLeft())
                : (cursor.getLeftLine() + 1) + ":" + (cursor.getLeftColumn() + 1));
    }
    private void gotoLine() {
        if (!loaded) return;
        EditText line = Ui.input(this, "1 – " + editor.getLineCount());
        line.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        line.setContentDescription("目标行号");
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("跳转到行").setView(line)
                .setNegativeButton("取消", null).setPositiveButton("跳转", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                int target = Integer.parseInt(line.getText().toString().trim()) - 1;
                if (target < 0 || target >= editor.getLineCount()) { line.setError("行号超出范围"); return; }
                editor.setSelection(target, 0); editor.ensurePositionVisible(target, 0); dialog.dismiss();
            } catch (NumberFormatException e) { line.setError("请输入行号"); }
        }));
        dialog.show();
    }
    private android.content.SharedPreferences display() { return getSharedPreferences("editor-display", 0); }
    private void applyDisplayPreferences() {
        defaultTypeface = editor.getTypefaceText();
        float density = getResources().getDisplayMetrics().density;
        editor.setScaleTextSizes(9 * density * getResources().getConfiguration().fontScale,
                32 * density * getResources().getConfiguration().fontScale);
        editor.setTextSize(Math.max(9f, Math.min(32f, display().getFloat("textSize", 13f))));
        applyTypeface(); applyWordwrap();
    }
    private void applyTypeface() {
        android.graphics.Typeface face = display().getBoolean("monospace", false) ? android.graphics.Typeface.MONOSPACE : defaultTypeface;
        editor.setTypefaceText(face); editor.setTypefaceLineNumber(face);
    }
    /** Wrapping a dense binary page lays out every control byte; it stays off there whatever the preference. */
    private void applyWordwrap() {
        boolean wrap = display().getBoolean("wordwrap", false) && !denseControls;
        if (editor.isWordwrap() != wrap) editor.setWordwrap(wrap);
    }
    private void saveTextSize() {
        float sp = editor.getTextSizePx() / (getResources().getDisplayMetrics().density * getResources().getConfiguration().fontScale);
        if (sp > 0) display().edit().putFloat("textSize", Math.max(9f, Math.min(32f, sp))).apply();
    }
    private void menu() {
        boolean wrap = display().getBoolean("wordwrap", false), mono = display().getBoolean("monospace", false);
        new AlertDialog.Builder(this).setItems(new String[]{wrap ? "关闭自动换行" : "开启自动换行",
                mono ? "使用默认字体" : "使用等宽字体", "跳转到行", "重新选择读取编码", "转换保存编码",
                "撤销", "重做", "保存"}, (d, n) -> {
            switch (n) {
                case 0 -> {
                    display().edit().putBoolean("wordwrap", !wrap).apply(); applyWordwrap();
                    if (!wrap && denseControls) Tasks.toast(this, "当前内容控制字符过多，暂不自动换行");
                }
                case 1 -> { display().edit().putBoolean("monospace", !mono).apply(); applyTypeface(); }
                case 2 -> gotoLine();
                case 3 -> {
                    if (dirty) { Tasks.toast(this, "先保存修改，再重新读取"); return; }
                    encoding(false);
                }
                case 4 -> encoding(true);
                case 5 -> undo();
                case 6 -> redo();
                case 7 -> save();
            }
        }).show();
    }
    private void encoding(boolean convert) {
        if (reading || saving || session.saving || convert && !canMutateBody()) { Tasks.toast(this, "等待读写完成或当前不可修改"); return; }
        if (convert && paged) { Tasks.toast(this, "大文件分段编辑保留原编码"); return; }
        String[] choices = {"自动识别", "UTF-8", "UTF-16LE", "UTF-16BE", "UTF-32LE", "UTF-32BE",
                "GB18030", "GBK", "Big5", "Shift_JIS", "EUC-JP", "EUC-KR", "ISO-2022-JP",
                "windows-1252", "windows-1251", "KOI8-R", "ISO-8859-1"};
        if (convert) choices = Arrays.copyOfRange(choices, 1, choices.length);
        String[] encodings = choices;
        new AlertDialog.Builder(this).setTitle(convert ? "保存编码" : "读取编码").setItems(choices, (d, n) -> {
            // A chooser opened before save must not change encoding/reload the body after snapshot capture.
            if (reading || saving || session.saving || convert && !canMutateBody()) { Tasks.toast(this, "等待读写完成或当前不可修改"); return; }
            if (convert) {
                charset = Charset.forName(encodings[n]); bom = new byte[0]; uncertainEncoding = false;
                session.contentRevision++; dirty = true; details(); scheduleCheckpoint();
            } else load(n == 0 ? null : encodings[n]);
        }).show();
    }
    private void leave() {
        if (saving) { afterSave = this::leave; Tasks.toast(this, "保存完成后返回"); return; }
        if (reviewOnly) { finish(); return; }
        if (dirty) new AlertDialog.Builder(this).setTitle("修改尚未保存").setNegativeButton("继续编辑", null)
                .setPositiveButton("放弃修改", (d, w) -> {
                    if (saving || session.saving) { afterSave = this::leave; return; }
                    dirty = false; handler.removeCallbacks(draftCheckpoint); session.discard(); finish();
                }).setNeutralButton("保存", (d, w) -> save(this::finish)).show();
        else finish();
    }
    @Override protected void handleBack() { if (!closeSearch()) leave(); }
    @Override protected void onPaletteChanged() {
        if (editor == null) return;
        EditorColorScheme colors = editor.getColorScheme();
        colors.setColor(EditorColorScheme.WHOLE_BACKGROUND, Ui.SURFACE);
        colors.setColor(EditorColorScheme.TEXT_NORMAL, Ui.TEXT);
        colors.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, Ui.PANEL);
        colors.setColor(EditorColorScheme.LINE_NUMBER, Ui.MUTED);
        colors.setColor(EditorColorScheme.CURRENT_LINE, Ui.TINT);
        colors.setColor(EditorColorScheme.KEYWORD, Ui.dark ? 0xffeba6d2 : 0xffa34a86);
        colors.setColor(EditorColorScheme.LITERAL, Ui.dark ? 0xffedbd86 : 0xff8a5a00);
        colors.setColor(EditorColorScheme.HTML_TAG, Ui.dark ? 0xff78c9ec : 0xff247caa);
        colors.setColor(EditorColorScheme.ATTRIBUTE_NAME, Ui.dark ? 0xffc9b2f0 : 0xff7a4fb3);
        colors.setColor(EditorColorScheme.ATTRIBUTE_VALUE, Ui.dark ? 0xffa6e4b4 : 0xff2f7a3f);
        colors.setColor(EditorColorScheme.IDENTIFIER_VAR, Ui.dark ? 0xff7fd3e0 : 0xff0b7285);
        colors.setColor(EditorColorScheme.FUNCTION_NAME, Ui.dark ? 0xffe8c27a : 0xff8a5a00);
        colors.setColor(EditorColorScheme.ANNOTATION, Ui.dark ? 0xffd8c07a : 0xff7a4fb3);
        colors.setColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_FOREGROUND, Ui.ACCENT);
        colors.setColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_BACKGROUND, Ui.TINT);
        colors.setColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_UNDERLINE, Ui.ACCENT);
        colors.setColor(EditorColorScheme.MATCHED_TEXT_BACKGROUND, Ui.dark ? 0x66c9a227 : 0x66f2c94c);
        colors.setColor(EditorColorScheme.COMMENT, Ui.MUTED); editor.invalidate();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putBoolean("savedForCaller", session != null && session.sourceSaved);
        checkpoint(true); out.putString("editorSession", session.drafts.id);
        if (getIntent().hasExtra("sourceRevision")) out.putString("sourceRevision",
                session.baseline != null ? session.baseline : getIntent().getStringExtra("sourceRevision"));
        super.onSaveInstanceState(out);
    }
    @Override protected void onPause() { checkpoint(true); saveTextSize(); super.onPause(); }
    @Override public void finish() {
        if (session != null && session.sourceSaved) setResult(RESULT_OK);
        super.finish();
    }
    @Override protected void onStop() { checkpoint(true); super.onStop(); }
    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (editor != null) editor.release();
        super.onDestroy();
    }
}
