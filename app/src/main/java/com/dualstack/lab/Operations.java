package com.dualstack.lab;

import android.content.*;
import android.os.*;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/** Serial, Activity-independent operation queue. Completed items are never replayed by Retry. */
final class Operations {
    interface Boundary { void beforeItem(Task task, int index) throws Exception; }
    static volatile Boundary testBoundary;
    static final String COPY = "复制", MOVE = "移动", DELETE = "删除并备份", EXTRACT = "解压", PACK = "压缩", CREATE = "新建", RENAME = "重命名", RESTORE = "恢复删除文件";
    static final class Spec {
        String kind, destination = "", name = "", displayName = "", format = "zip", sourceRevision = "";
        boolean folder, passwordRequired;
        int targetPane = -1;
        final List<String> sources = new ArrayList<>();
        final Map<String, String> identities = new HashMap<>();
        char[] password = new char[0]; // Never serialized to disk or notification text.
        static Spec of(String kind, Collection<FileStore.Node> nodes, FileStore.Node destination, String name) {
            Spec s = new Spec(); s.kind = kind; s.destination = destination == null ? "" : destination.key(); s.name = name == null ? "" : name;
            s.displayName = !s.name.isEmpty() ? s.name : nodes.size() == 1 ? nodes.iterator().next().name : nodes.size() + " 项";
            for (FileStore.Node node : nodes) { s.sources.add(node.key()); if (node.identity != null) s.identities.put(node.key(), node.identity); }
            if (destination != null && destination.identity != null) s.identities.put(destination.key(), destination.identity);
            return s;
        }
        JSONObject json() throws JSONException {
            return new JSONObject().put("kind", kind).put("destination", destination).put("name", name).put("displayName", displayName).put("format", format).put("sourceRevision", sourceRevision)
                    .put("folder", folder).put("passwordRequired", passwordRequired).put("targetPane", targetPane).put("sources", new JSONArray(sources)).put("identities", new JSONObject(identities));
        }
        static Spec read(JSONObject j) throws JSONException {
            Spec s = new Spec(); s.kind = j.getString("kind"); s.destination = j.optString("destination"); s.name = j.optString("name");
            s.displayName = j.optString("displayName", s.kind.equals(RESTORE) ? "" : s.name);
            s.format = j.optString("format", "zip"); s.folder = j.optBoolean("folder"); s.passwordRequired = j.optBoolean("passwordRequired");
            s.sourceRevision = j.optString("sourceRevision");
            s.targetPane = j.optInt("targetPane", -1);
            JSONArray a = j.getJSONArray("sources"); for (int i = 0; i < a.length(); i++) s.sources.add(a.getString(i));
            JSONObject identities = j.optJSONObject("identities");
            if (identities != null) for (Iterator<String> keys = identities.keys(); keys.hasNext();) { String key = keys.next(); s.identities.put(key, identities.getString(key)); }
            return s;
        }
    }
    static final class Task {
        final String id; final Spec spec; final long created;
        volatile String state = "排队中", current = "", note = "";
        volatile long started, finished, elapsed, storedBytes;
        volatile int inFlight = -1, attempts;
        final Set<Integer> completed = Collections.synchronizedSet(new HashSet<>());
        final List<String> outputs = new ArrayList<>(), errors = new ArrayList<>();
        volatile Tasks.Progress progress = new Tasks.Progress();
        Task(String id, Spec spec, long created) { this.id = id; this.spec = spec; this.created = created; }
        boolean active() { return state.equals("排队中") || state.equals("处理中") || state.equals("正在停止"); }
        long bytes() { return storedBytes + progress.transferred.get(); }
        int count() { return spec.kind.equals(PACK) || spec.kind.equals(EXTRACT) || spec.sources.isEmpty() ? 1 : spec.sources.size(); }
        String summary() {
            long duration = active() && started > 0 ? SystemClock.elapsedRealtime() - started : elapsed;
            long bytes = bytes(), speed = duration > 0 ? progress.transferred.get() * 1000 / Math.max(1, duration) : 0;
            String item = progress.current.isEmpty() ? current : progress.current;
            return state + " · " + completed.size() + "/" + count() + " 项 · 累计传输 " + FileStore.size(bytes)
                    + (active() ? " · " + FileStore.size(speed) + "/s" : "") + (item.isEmpty() ? "" : "\n" + item)
                    + (note.isEmpty() ? "" : "\n" + note);
        }
    }
    private static Operations instance;
    private final Context context;
    private final AtomicFile journal;
    private final List<Task> tasks = new ArrayList<>();
    private final Set<Runnable> listeners = new HashSet<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.atomic.AtomicBoolean updatePending = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile String journalError = "";
    private long lastCheckpoint;
    static synchronized Operations get(Context context) { if (instance == null) instance = new Operations(context.getApplicationContext()); return instance; }
    private Operations(Context context) {
        this.context = context; journal = new AtomicFile(new File(context.getFilesDir(), "operations.json"));
        try (InputStream in = journal.openRead()) {
            JSONArray saved = new JSONArray(new String(FileStore.readBytes(in, 8 * 1024 * 1024), java.nio.charset.StandardCharsets.UTF_8));
            for (int i = 0; i < saved.length(); i++) {
                JSONObject j = saved.getJSONObject(i); Task t = new Task(j.getString("id"), Spec.read(j.getJSONObject("spec")), j.getLong("created"));
                t.state = j.getString("state"); t.current = j.optString("current"); t.note = j.optString("note");
                t.storedBytes = j.optLong("bytes"); t.elapsed = j.optLong("elapsed"); t.inFlight = j.optInt("inFlight", -1); t.attempts = j.optInt("attempts");
                JSONArray done = j.getJSONArray("completed"); for (int k = 0; k < done.length(); k++) t.completed.add(done.getInt(k));
                JSONArray outputs = j.getJSONArray("outputs"); for (int k = 0; k < outputs.length(); k++) t.outputs.add(outputs.getString(k));
                JSONArray errors = j.getJSONArray("errors"); for (int k = 0; k < errors.length(); k++) t.errors.add(errors.getString(k));
                if (t.active()) {
                    if (!t.state.equals("排队中") && t.inFlight < 0) {
                        for (int k = 0; k < t.count(); k++) if (!t.completed.contains(k)) { t.inFlight = k; break; }
                    }
                    t.state = "已中断";
                    t.note = t.inFlight >= 0 ? "进程已结束，上次检查点之后的项目结果待核对；确认来源和目标后再重试。已持久记录的完成项目不会重复执行。" : "进程已结束，可重试未完成项目。";
                }
                tasks.add(t);
            }
        } catch (FileNotFoundException ignored) { }
        catch (Exception e) { journalError = "任务历史读取失败，原记录保留：" + e.getMessage(); }
    }
    synchronized List<Task> list() { return new ArrayList<>(tasks); }
    synchronized Task find(String id) { for (Task t : tasks) if (t.id.equals(id)) return t; return null; }
    synchronized void listen(Runnable listener) { listeners.add(listener); }
    synchronized void unlisten(Runnable listener) { listeners.remove(listener); }
    String journalError() { return journalError; }
    synchronized boolean active() { for (Task t : tasks) if (t.active()) return true; return false; }
    private void changed() {
        if (!updatePending.compareAndSet(false, true)) return;
        main.postDelayed(() -> {
            updatePending.set(false); List<Runnable> callbacks;
            synchronized (this) { callbacks = new ArrayList<>(listeners); }
            for (Runnable r : callbacks) r.run();
        }, 80);
    }
    synchronized Task enqueue(Spec spec) throws IOException {
        if (!journalError.isEmpty()) throw new IOException(journalError);
        if (tasks.size() >= 100) {
            Task oldest = null; for (Task t : tasks) if (t.state.equals("已完成")) oldest = t;
            if (oldest == null) throw new IOException("任务记录已满，请移除不需要的失败/停止记录后重试"); tasks.remove(oldest);
        }
        Task task = new Task(UUID.randomUUID().toString(), spec, System.currentTimeMillis()); tasks.add(0, task);
        try { persist(); startService(); }
        catch (IOException | RuntimeException e) { tasks.remove(task); persist(); throw new IOException("无法启动文件任务：" + e.getMessage(), e); }
        worker.execute(() -> execute(task)); changed(); return task;
    }
    private void startService() { context.startForegroundService(new Intent(context, OperationService.class)); }
    synchronized void stop(Task t) throws IOException {
        if (!t.active()) return;
        t.progress.cancelled.set(true);
        if (t.state.equals("排队中")) t.state = "已停止"; else t.state = "正在停止";
        t.note = "已请求停止，已完成项目保留；正在处理的项目会完成或报告未完成状态，部分系统操作需等待返回。"; persist(); changed();
    }
    synchronized void retry(Task t, char[] password, boolean acknowledgeInterrupted) throws IOException {
        if (t.active()) throw new IOException("任务仍在执行");
        if (t.inFlight >= 0 && t.state.equals("已中断") && !acknowledgeInterrupted) throw new IOException("请先核对中断项目的来源与输出，避免重复副本");
        if (t.spec.passwordRequired) {
            if (password == null || password.length == 0) throw new IOException("请重新输入压缩密码");
            Arrays.fill(t.spec.password, '\0'); t.spec.password = password.clone();
        }
        t.storedBytes = t.bytes(); t.progress = new Tasks.Progress(); t.state = "排队中"; t.note = ""; t.errors.clear(); t.inFlight = -1;
        try { persist(); }
        catch (IOException e) { t.state = "失败"; t.note = "无法保存重试记录：" + e.getMessage(); changed(); throw e; }
        try { startService(); } catch (RuntimeException e) { t.state = "失败"; t.note = e.getMessage(); persist(); throw new IOException(e); }
        worker.execute(() -> execute(t)); changed();
    }
    synchronized void clearFinished() throws IOException {
        tasks.removeIf(t -> !t.active() && t.state.equals("已完成")); persist(); changed();
    }
    synchronized void remove(Task task) throws IOException {
        if (task.active()) throw new IOException("请先停止任务");
        tasks.remove(task); Arrays.fill(task.spec.password, '\0'); persist(); changed();
    }
    void timeout() {
        for (Task t : list()) if (t.active()) {
            t.progress.cancelled.set(true); t.note = "系统后台运行时限已到，正在停止；请在任务中心核对结果。";
            if (t.state.equals("排队中")) t.state = "已停止";
        }
        try { persist(); } catch (IOException e) { journalError = e.getMessage(); } changed();
    }
    private void execute(Task t) {
        synchronized (this) { if (!t.state.equals("排队中")) { changed(); return; } t.state = "处理中"; t.started = SystemClock.elapsedRealtime(); t.attempts++; }
        changed();
        try {
            persist();
            t.progress.run(() -> {
                FileStore store = new FileStore(context);
                Map<String, Set<String>> copyNames = new HashMap<>();
                if (t.spec.kind.equals(DELETE) || t.spec.kind.equals(MOVE))
                    for (int i = 0; i < t.spec.sources.size(); i++) if (!t.completed.contains(i)) FileStore.checkRemoval(source(t.spec, i, store));
                boolean firstItem = true;
                for (int i = 0; i < t.count(); i++) {
                    synchronized (this) { if (t.completed.contains(i)) continue; }
                    Tasks.Progress.check();
                    t.inFlight = i; t.current = i < t.spec.sources.size() ? displayLocation(t.spec.sources.get(i)) : t.spec.displayName;
                    if (firstItem) { persist(); firstItem = false; } else checkpoint(); changed();
                    try {
                        Boundary boundary = testBoundary; if (boundary != null) boundary.beforeItem(t, i);
                        Tasks.Progress.check();
                        FileStore.Node output = item(t, i, store, copyNames);
                        synchronized (this) { t.completed.add(i); if (output != null) t.outputs.add(output.key()); t.inFlight = -1; }
                        checkpoint();
                    } catch (Tasks.CancelledException stop) { t.inFlight = -1; throw stop; }
                    catch (Exception error) {
                        if (t.progress.cancelled.get()) { t.inFlight = -1; throw new Tasks.CancelledException(); }
                        synchronized (this) { if (t.errors.size() < 100) t.errors.add(t.current + "\n" + errorMessage(error)); t.inFlight = -1; }
                        persist();
                    }
                }
                return null;
            });
            synchronized (this) { t.state = t.completed.size() == t.count() ? "已完成" : "部分失败"; }
        } catch (Tasks.CancelledException stop) { t.state = "已停止"; }
        catch (Exception error) { t.state = "失败"; synchronized (this) { if (t.errors.size() < 100) t.errors.add(errorMessage(error)); } }
        finally {
            t.elapsed = Math.max(0, SystemClock.elapsedRealtime() - t.started); t.finished = System.currentTimeMillis();
            if (t.state.equals("已完成")) { Arrays.fill(t.spec.password, '\0'); t.spec.password = new char[0]; t.note = ""; }
            try { persist(); } catch (IOException e) { journalError = "任务记录写入失败：" + e.getMessage(); }
            changed();
        }
    }
    private FileStore.Node source(Spec spec, int index, FileStore store) throws IOException {
        String key = spec.sources.get(index); FileStore.Node node = resolve(store, key), original = node;
        if (spec.identities.containsKey(key) && !spec.identities.get(key).equals(original.identity)) throw new IOException("来源文件身份已变化，请重新选择");
        if (!spec.sourceRevision.isEmpty() && !spec.sourceRevision.equals(store.fingerprint(node))) throw new IOException("归档来源已变化，请刷新后重新选择");
        return node;
    }
    private FileStore.Node item(Task t, int index, FileStore store, Map<String, Set<String>> copyNames) throws Exception {
        Spec spec = t.spec;
        FileStore.Node destination = spec.destination.isEmpty() ? null : resolve(store, spec.destination);
        if (destination != null && spec.identities.containsKey(spec.destination) && !spec.identities.get(spec.destination).equals(destination.identity))
            throw new IOException("目标目录身份已变化，请重新选择目标");
        if (spec.kind.equals(COPY) || spec.kind.equals(MOVE)) return store.copy(source(spec, index, store), destination, spec.kind.equals(MOVE), copyNames);
        if (spec.kind.equals(DELETE)) { DeletedFiles.recycle(context, source(spec, index, store), destination); return null; }
        if (spec.kind.equals(RESTORE)) return DeletedFiles.restore(context, spec.name);
        if (spec.kind.equals(CREATE)) { FileStore.checkName(spec.name); return store.create(destination, spec.name, spec.folder); }
        if (spec.kind.equals(RENAME)) return store.rename(source(spec, 0, store), spec.name);
        List<FileStore.Node> nodes = new ArrayList<>(); for (int i = 0; i < spec.sources.size(); i++) nodes.add(source(spec, i, store));
        if (spec.kind.equals(EXTRACT)) return store.extract(nodes, destination, spec.name);
        if (spec.kind.equals(PACK)) {
            if (spec.passwordRequired && spec.password.length == 0) throw new IOException("压缩密码未保留，请在重试时重新输入");
            File tmp = File.createTempFile("task-pack-", "." + spec.format, context.getCacheDir());
            try {
                ArchiveOps.pack(store, nodes, tmp, spec.format, spec.password);
                FileStore.Node output = store.create(destination, spec.name, false);
                try { store.write(output, tmp); }
                catch (Exception e) { try { Tasks.Progress.cleanup(() -> store.delete(output)); } catch (Exception cleanup) { e.addSuppressed(cleanup); } throw e; }
                if (spec.password.length > 0 && output.file != null) ArchiveOps.setPassword(output.file, spec.password);
                return output;
            } finally { Files.deleteIfExists(tmp.toPath()); }
        }
        throw new IOException("未知文件任务");
    }
    private synchronized void persist() throws IOException {
        if (!journalError.isEmpty()) throw new IOException(journalError);
        JSONArray saved = new JSONArray();
        try {
            for (Task t : tasks) saved.put(new JSONObject().put("id", t.id).put("spec", t.spec.json()).put("created", t.created)
                    .put("state", t.state).put("current", t.current).put("note", t.note).put("bytes", t.bytes()).put("elapsed", t.elapsed)
                    .put("inFlight", t.inFlight).put("attempts", t.attempts).put("completed", new JSONArray(t.completed))
                    .put("outputs", new JSONArray(t.outputs)).put("errors", new JSONArray(t.errors)));
        } catch (JSONException e) { throw new IOException(e); }
        byte[] bytes = saved.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > 8 * 1024 * 1024) throw new IOException("任务历史超过 8 MiB，请清理记录后重试");
        FileOutputStream output = null;
        try { output = journal.startWrite(); output.write(bytes); output.getFD().sync(); journal.finishWrite(output); lastCheckpoint = SystemClock.elapsedRealtime(); }
        catch (IOException e) { if (output != null) journal.failWrite(output); throw e; }
    }
    private synchronized void checkpoint() throws IOException {
        if (SystemClock.elapsedRealtime() - lastCheckpoint >= 250) persist();
    }
    private static String errorMessage(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
    static String displayLocation(String key) {
        if (key.startsWith("root:")) return "ROOT · " + key.substring(5);
        if (key.startsWith("zip:")) {
            android.net.Uri uri = android.net.Uri.parse(key); String source = uri.getQueryParameter("source");
            if (source != null) return displayLocation(source) + "!/" + uri.getQueryParameter("entry");
        }
        return key;
    }
    static FileStore.Node resolve(FileStore store, String key) throws IOException {
        if (!key.startsWith("root:")) return store.fromKey(key);
        File file = new File(key.substring(5));
        String value = RootDirectoryReader.inspect(file.getAbsolutePath());
        String[] parts = value.split("\\|");
        try {
            int mode = Integer.parseInt(parts[3], 16);
            FileStore.Node node = new FileStore.Node(file.getName(), file, null, android.system.OsConstants.S_ISDIR(mode), true,
                    Long.parseLong(parts[4]), Long.parseLong(parts[5]) * 1000);
            node.identity = parts[1] + ":" + parts[2]; node.identityFollowsLinks = parts[0].equals("F");
            if (node.directory) node.directoryIdentity = node.identity;
            if (file.getParentFile() != null) node.parentIdentity = RootDirectoryReader.stat(file.getParent(), "%d:%i", true);
            return node;
        } catch (RuntimeException e) { throw new IOException("无法读取 ROOT 文件身份：" + file, e); }
    }
}
