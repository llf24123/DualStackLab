package com.dualstack.lab;

import android.content.Context;
import android.util.AtomicFile;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** User deletion only. Internal cleanup/move/delete APIs deliberately do not recycle. */
final class DeletedFiles {
    private static final Set<String> IN_USE = java.util.concurrent.ConcurrentHashMap.newKeySet();
    interface Boundary { void verified(FileStore.Node source) throws Exception; }
    static volatile Boundary testBoundary;
    record Entry(String id, String name, String source, String parent, String state, long created,
                 long size, String checksum, File backup, boolean directory, String link) { }
    private static File catalog(Context c) throws IOException {
        File folder = new File(c.getFilesDir(), "deleted-index");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("无法创建删除备份索引");
        return folder;
    }
    private static File record(Context c, String id) throws IOException {
        if (!id.matches("[0-9a-f-]{36}")) throw new IOException("备份记录 ID 无效");
        return new File(catalog(c), id + ".json");
    }
    static synchronized List<Entry> entries(Context c) throws IOException {
        List<Entry> entries = new ArrayList<>();
        File[] files = catalog(c).listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) throw new IOException("无法读取删除备份索引");
        for (File file : files) {
            try { entries.add(decode(read(file))); }
            catch (Exception e) { throw new IOException("删除备份索引损坏，保留文件：" + file.getName(), e); }
        }
        entries.sort(Comparator.comparingLong(Entry::created).reversed()); return entries;
    }
    private static Entry decode(JSONObject j) throws JSONException {
        return new Entry(j.getString("id"), j.getString("name"), j.getString("source"), j.getString("parent"),
                j.getString("state"), j.getLong("created"), j.getLong("size"), j.getString("sha256"),
                new File(j.getString("backup")), j.getBoolean("directory"), j.optString("link", ""));
    }
    private static JSONObject read(File file) throws IOException, JSONException {
        try (InputStream in = new AtomicFile(file).openRead()) {
            return new JSONObject(new String(FileStore.readBytes(in, 32 * 1024 * 1024), java.nio.charset.StandardCharsets.UTF_8));
        }
    }
    private static void write(File file, JSONObject json) throws IOException {
        AtomicFile atomic = new AtomicFile(file); FileOutputStream output = null;
        try {
            output = atomic.startWrite(); output.write(json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.getFD().sync(); atomic.finishWrite(output);
        }
        catch (IOException | RuntimeException e) { if (output != null) atomic.failWrite(output); throw e; }
    }
    static Entry recycle(Context context, FileStore.Node requested) throws Exception {
        return recycle(context, requested, null);
    }
    static Entry recycle(Context context, FileStore.Node requested, FileStore.Node parentHint) throws Exception {
        FileStore.checkRemoval(requested);
        FileStore store = new FileStore(context);
        FileStore.Node source = RootAccess.automatic(requested), parent = store.parent(source);
        if (parent == null && parentHint != null && source.uri != null) {
            boolean belongs = false; for (FileStore.Node child : store.list(parentHint)) if (child.key().equals(source.key())) { belongs = true; break; }
            if (belongs) parent = parentHint;
        }
        if (parent == null) throw new IOException("无法记录原目录，原件未删除");
        File folder = new File(SafRecovery.backupDirectory(context), "deleted-files").getCanonicalFile();
        if (source.file != null && folder.toPath().startsWith(source.file.getCanonicalFile().toPath()))
            throw new IOException("删除备份目录不能位于待删除目录内，原件未删除");
        if (source.file != null && source.directory && (catalog(context).getCanonicalFile().toPath().startsWith(source.file.getCanonicalFile().toPath())
                || context.getCacheDir().getCanonicalFile().toPath().startsWith(source.file.getCanonicalFile().toPath())))
            throw new IOException("不能删除正在使用的备份索引或缓存目录，原件未删除");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("无法创建删除备份目录，原件未删除");
        if (!folder.canWrite()) throw new IOException("删除备份目录不可写，原件未删除");
        String id = UUID.randomUUID().toString();
        File archive = new File(folder, id + ".zip"), index = record(context, id);
        boolean privateStorage = folder.toPath().startsWith(context.getFilesDir().getCanonicalFile().toPath());
        JSONArray manifest = new JSONArray(); Map<String, String> versions = new LinkedHashMap<>();
        long[] size = {0}; boolean indexed = false, created = false; List<FileStore.Node> backed = new ArrayList<>(); IN_USE.add(id);
        try {
            String checksum;
            java.security.MessageDigest written = java.security.MessageDigest.getInstance("SHA-256");
            try (SafRecovery.PinnedDirectory pinned = new SafRecovery.PinnedDirectory(folder.getPath())) {
                try (FileOutputStream raw = SafRecovery.newLocalOutput(pinned.child(archive.getName()));
                     ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(
                             new java.security.DigestOutputStream(raw, written), 128 * 1024))) {
                    created = true; zip.setLevel(Deflater.BEST_SPEED);
                    pack(store, source, "data", zip, manifest, versions, new HashSet<>(), size, folder, backed);
                    zip.putNextEntry(new ZipEntry("manifest.json"));
                    zip.write(manifest.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
                    zip.finish(); zip.flush(); raw.getFD().sync();
                }
                checksum = hex(written.digest());
                pinned.verify(folder);
                verifyPublished(new File(pinned.child(archive.getName())), checksum);
                pinned.verify(folder);
            }
            syncDirectory(folder);
            syncDirectory(folder.getParentFile());
            JSONObject metadata = new JSONObject().put("id", id).put("name", source.name).put("source", source.key())
                    .put("parent", parent.key()).put("state", "已备份，待删除").put("created", System.currentTimeMillis())
                    .put("size", size[0]).put("sha256", checksum).put("backup", archive.getAbsolutePath())
                    .put("directory", source.directory).put("link", manifest.getJSONObject(0).optString("link", ""));
            write(index, metadata); indexed = true;
            syncDirectory(index.getParentFile());
            syncDirectory(context.getFilesDir());
            // Re-enumerate and verify content, including directory members, before deleting anything.
            Map<String, String> current = new LinkedHashMap<>(); snapshot(store, source, "data", current, new HashSet<>());
            if (!versions.equals(current)) throw new IOException("备份期间来源已变化，原件保留，备份可在回收站查看");
            Tasks.Progress.check();
            metadata.put("state", "删除中（中断时请核对原目录）"); write(index, metadata);
            try {
                Boundary boundary = testBoundary; if (boundary != null) boundary.verified(source);
                Collections.reverse(backed);
                if (source.file != null && source.container == null) RootMutations.removeBacked(context, backed);
                else if (source.container != null) store.delete(source);
                else {
                    for (FileStore.Node member : backed) {
                        Tasks.Progress.check();
                        if (member.directory && !store.list(member).isEmpty()) throw new IOException("来源目录出现未备份成员，已保留目录");
                        store.delete(member);
                    }
                }
                metadata.put("state", "已删除，可恢复"); write(index, metadata);
            } catch (Exception e) {
                metadata.put("state", "删除未完成，备份保留"); write(index, metadata); throw e;
            }
            return decode(metadata);
        } finally {
            if (!indexed && created && privateStorage) Files.deleteIfExists(archive.toPath());
            IN_USE.remove(id);
        }
    }
    private static void verifyPublished(File archive, String checksum) throws IOException {
        try {
            FileDescriptor descriptor = Os.open(archive.getPath(), OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW, 0);
            try (FileInputStream input = new FileInputStream(descriptor)) {
                StructStat opened = Os.fstat(descriptor);
                if (!OsConstants.S_ISREG(opened.st_mode)) throw new IOException("备份文件类型发生变化，原件未删除");
                Os.fsync(descriptor);
                if (!checksum.equals(FileStore.digest(input))) throw new IOException("备份发布后内容校验失败，原件未删除：" + archive);
                StructStat published = Os.lstat(archive.getPath());
                if (opened.st_dev != published.st_dev || opened.st_ino != published.st_ino
                        || !OsConstants.S_ISREG(published.st_mode)) throw new IOException("备份文件被替换，原件未删除");
            }
        } catch (ErrnoException error) { throw new IOException("无法持久化删除备份，原件未删除", error); }
    }
    private static void syncDirectory(File directory) throws IOException {
        try {
            FileDescriptor descriptor = Os.open(directory.getPath(), OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW, 0);
            try {
                if (!OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) throw new IOException("备份目录类型发生变化，原件未删除");
                Os.fsync(descriptor);
            } finally { Os.close(descriptor); }
        } catch (ErrnoException error) { throw new IOException("无法持久化备份目录，原件未删除：" + directory, error); }
    }
    private static String link(FileStore.Node node) throws IOException {
        if (node.file == null) return null;
        try {
            android.system.StructStat stat = android.system.Os.lstat(node.path());
            if (!android.system.OsConstants.S_ISLNK(stat.st_mode)) {
                if (!android.system.OsConstants.S_ISREG(stat.st_mode) && !android.system.OsConstants.S_ISDIR(stat.st_mode))
                    throw new IOException("特殊设备/管道不能备份，原件保留：" + node.path());
                return null;
            }
            return android.system.Os.readlink(node.path());
        } catch (android.system.ErrnoException e) {
            if (!node.root) throw new IOException(e);
            String mode = RootDirectoryReader.stat(node.path(), "%f", false);
            int type;
            try { type = Integer.parseInt(mode, 16); } catch (NumberFormatException failure) { throw new IOException("无法确认文件类型", failure); }
            if (android.system.OsConstants.S_ISLNK(type)) return FileStore.shell("readlink -n -- " + FileStore.quote(node.path()), true);
            if (!android.system.OsConstants.S_ISREG(type) && !android.system.OsConstants.S_ISDIR(type))
                throw new IOException("特殊设备/管道不能备份，原件保留：" + node.path());
            return null;
        }
    }
    private static String version(FileStore store, FileStore.Node node, String target) throws IOException {
        if (target != null) return "link:" + target;
        if (node.directory) {
            List<String> names = new ArrayList<>(); for (FileStore.Node child : store.list(node)) names.add(child.name);
            Collections.sort(names); return "dir:" + (node.container == null ? store.identity(node) : store.fingerprint(node.container)) + ":" + names.toString();
        }
        String fingerprint = store.fingerprint(node);
        if (node.container != null) try (InputStream in = store.read(node)) { fingerprint += ":" + FileStore.digest(in); }
        return fingerprint;
    }
    private static void pack(FileStore store, FileStore.Node node, String path, ZipOutputStream zip, JSONArray manifest,
                             Map<String, String> versions, Set<String> seen, long[] size, File storage, List<FileStore.Node> backed) throws Exception {
        Tasks.Progress.step(node.name);
        if (manifest.length() >= 100000) throw new IOException("删除备份超过 100000 项，原件未删除");
        String target = link(node), before = version(store, node, target);
        versions.put(path, before);
        backed.add(node);
        JSONObject item = new JSONObject().put("path", path).put("directory", target == null && node.directory);
        if (node.file != null) {
            FilePermissions.State permissions = FilePermissions.read(node);
            item.put("mode", permissions.mode()).put("uid", permissions.uid()).put("gid", permissions.gid());
        }
        if (target != null) item.put("link", target);
        manifest.put(item);
        if (target != null) return;
        if (!seen.add(node.key())) throw new IOException("目录循环，原件未删除");
        if (node.directory) {
            zip.putNextEntry(new ZipEntry(path + "/")); zip.closeEntry();
            for (FileStore.Node child : store.list(node)) { FileStore.checkName(child.name); pack(store, child, path + "/" + child.name, zip, manifest, versions, seen, size, storage, backed); }
        } else {
            zip.putNextEntry(new ZipEntry(path));
            java.security.MessageDigest copied = java.security.MessageDigest.getInstance("SHA-256");
            try (InputStream in = store.read(node)) {
                byte[] buffer = new byte[128 * 1024]; int n;
                while ((n = in.read(buffer)) != -1) if (n > 0) {
                    Tasks.Progress.check();
                    long interval = 8L * 1024 * 1024;
                    if ((size[0] == 0 || size[0] / interval != (size[0] + n) / interval)
                            && storage.getUsableSpace() < n + 16L * 1024 * 1024)
                        throw new IOException("备份空间不足，原件未删除");
                    zip.write(buffer, 0, n); copied.update(buffer, 0, n); size[0] += n; Tasks.Progress.bytes(n);
                }
            }
            zip.closeEntry();
            if (!before.substring(before.lastIndexOf(':') + 1).equals(hex(copied.digest())))
                throw new IOException("实际备份字节与来源基线不同，原件未删除：" + node.name);
        }
        // recycle() verifies one complete source snapshot after finishing and verifying the ZIP.
        seen.remove(node.key());
    }
    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) value.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return value.toString();
    }
    private static void snapshot(FileStore store, FileStore.Node node, String path, Map<String, String> versions, Set<String> seen) throws IOException {
        Tasks.Progress.check(); String target = link(node); versions.put(path, version(store, node, target));
        if (target != null || !node.directory) return;
        if (!seen.add(node.key())) throw new IOException("目录循环");
        for (FileStore.Node child : store.list(node)) snapshot(store, child, path + "/" + child.name, versions, seen);
        seen.remove(node.key());
    }
    static synchronized void purge(Context context, String id) throws Exception {
        if (!IN_USE.add(id)) throw new IOException("此备份正在写入或恢复，请稍后再试");
        try {
            Entry entry = decode(read(record(context, id))); validateBackup(entry);
            Files.deleteIfExists(entry.backup.toPath()); new AtomicFile(record(context, id)).delete();
        } finally { IN_USE.remove(id); }
    }
    private static void validateBackup(Entry entry) throws IOException {
        if (!entry.backup.isAbsolute() || !entry.backup.getName().equals(entry.id + ".zip")
                || !entry.backup.getCanonicalFile().equals(entry.backup) || Files.isSymbolicLink(entry.backup.toPath()))
            throw new IOException("备份位置已变化，拒绝处理");
    }
    static FileStore.Node restore(Context context, String id) throws Exception {
        if (!IN_USE.add(id)) throw new IOException("此备份正在处理");
        File privateCopy = null, workspace = null;
        try {
        Entry entry = decode(read(record(context, id))); validateBackup(entry);
        privateCopy = File.createTempFile("deleted-verified-", ".zip", context.getCacheDir());
        try (InputStream in = new FileInputStream(entry.backup); OutputStream out = new FileOutputStream(privateCopy)) { FileStore.pump(in, out); }
        try (InputStream in = new FileInputStream(privateCopy)) {
            if (!entry.checksum.equals(FileStore.digest(in))) throw new IOException("备份校验失败，原目录未改动");
        }
        FileStore store = new FileStore(context); FileStore.Node parent = Operations.resolve(store, entry.parent);
        FileStore.Node archiveParent = parent.container == null ? null : parent;
        if (archiveParent != null) { workspace = Files.createTempDirectory(context.getCacheDir().toPath(), "deleted-restore-").toFile(); parent = FileStore.local(workspace); }
        FileStore.Node output = null; Map<String, FileStore.Node> made = new HashMap<>();
        FileStore.CopyLedger ledger = store.new CopyLedger();
        List<String> warnings = new ArrayList<>();
        try (ZipFile zip = new ZipFile(privateCopy)) {
            ZipEntry metadata = zip.getEntry("manifest.json");
            if (metadata == null) throw new IOException("备份目录清单缺失");
            JSONArray manifest;
            try (InputStream in = zip.getInputStream(metadata)) { manifest = new JSONArray(new String(FileStore.readBytes(in, 32 * 1024 * 1024), java.nio.charset.StandardCharsets.UTF_8)); }
            for (int i = 0; i < manifest.length(); i++) {
                Tasks.Progress.check(); JSONObject item = manifest.getJSONObject(i);
                String path = item.getString("path"); ArchiveOps.validateEntry(path);
                if (!path.equals("data") && !path.startsWith("data/")) throw new IOException("备份条目越界");
                int slash = path.lastIndexOf('/');
                FileStore.Node destParent = path.equals("data") ? parent : made.get(path.substring(0, slash));
                if (destParent == null || !destParent.directory) throw new IOException("备份目录结构无效");
                String name = path.equals("data") ? store.unique(parent, entry.name) : path.substring(slash + 1); FileStore.checkName(name);
                Tasks.Progress.step(name);
                FileStore.Node node;
                if (item.has("link")) {
                    if (destParent.file == null || destParent.container != null) throw new IOException("此目标不支持恢复符号链接，备份保留");
                    File target = new File(destParent.file, name);
                    if (destParent.root) FileStore.shell("ln -s -- " + FileStore.quote(item.getString("link")) + " " + FileStore.quote(target.getAbsolutePath()), true);
                    else Files.createSymbolicLink(target.toPath(), new File(item.getString("link")).toPath());
                    node = FileStore.local(target, destParent.root);
                    if (destParent.root) {
                        node.identity = FileStore.shell("stat -c '%d:%i' -- " + FileStore.quote(target.getAbsolutePath()), true).trim();
                        node.identityFollowsLinks = false; node.directoryIdentity = null;
                    }
                } else {
                    node = store.create(destParent, name, item.getBoolean("directory"));
                    ledger.record(node);
                    if (!node.directory) {
                        ZipEntry payload = zip.getEntry(path); if (payload == null) throw new IOException("备份文件缺失：" + path);
                        File tmp = File.createTempFile("restore-deleted-", ".tmp", context.getCacheDir());
                        try {
                            try (InputStream in = zip.getInputStream(payload); OutputStream out = new FileOutputStream(tmp)) { FileStore.pump(in, out); }
                            store.write(node, tmp);
                            if (node.file != null) {
                                FileStore.Node published = Operations.resolve(store, node.key());
                                ledger.published(node, published.identity);
                            }
                        } finally { Files.deleteIfExists(tmp.toPath()); }
                    }
                }
                if (i == 0) output = node;
                if (item.has("link")) ledger.record(node);
                if (!item.has("link")) made.put(path, node);
            }
            // Apply directory permissions last, after all children have been published.
            for (int i = manifest.length() - 1; i >= 0; i--) {
                JSONObject item = manifest.getJSONObject(i); FileStore.Node node = made.get(item.getString("path"));
                if (node == null || !item.has("mode") || node.file == null) continue;
                try {
                    if (node.root) FileStore.shell("chown " + item.getLong("uid") + ":" + item.getLong("gid") + " " + FileStore.quote(node.path()), true);
                    FilePermissions.apply(context, node, item.getInt("mode"));
                } catch (IOException e) { warnings.add(node.name + "：" + e.getMessage()); }
            }
        } catch (Exception failure) {
            Tasks.Progress.cleanup(() -> ledger.rollback(failure));
            if (failure instanceof Tasks.CancelledException) throw failure;
            throw new IOException("恢复未完成，备份保留" + (output == null ? "" : "；请检查已创建的 " + output.path()) + "\n" + failure.getMessage(), failure);
        }
        if (archiveParent != null) output = store.copy(output, archiveParent, false);
        JSONObject state = read(record(context, id));
        state.put("state", "已恢复至 " + output.path() + (warnings.isEmpty() ? "" : "；部分权限未恢复：" + String.join("；", warnings)));
        write(record(context, id), state); return output;
        } finally {
            IN_USE.remove(id); if (privateCopy != null) Files.deleteIfExists(privateCopy.toPath());
            if (workspace != null) { File owned = workspace; Tasks.Progress.cleanup(() -> new FileStore(context).delete(FileStore.local(owned))); }
        }
    }
    private DeletedFiles() { }
}
