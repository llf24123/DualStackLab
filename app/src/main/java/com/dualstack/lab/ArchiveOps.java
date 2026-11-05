package com.dualstack.lab;

import net.lingala.zip4j.model.FileHeader;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;

final class ArchiveOps {
    record Item(String path, String name, boolean directory, long size, long modified) {}
    private record Index(long size, long modified, Map<String, List<Item>> folders) {}
    private static final Map<String, Index> indexes = new LinkedHashMap<>(8, .75f, true);
    private static final Map<String, char[]> passwords = new HashMap<>();
    static final class PasswordRequired extends IOException {
        final File file;
        PasswordRequired(File file) { super("此 ZIP 已加密，请输入密码"); this.file = file; }
    }
    static synchronized void setPassword(File file, char[] password) {
        try { passwords.put(file.getCanonicalPath(), password.clone()); } catch (IOException ignored) { }
    }
    static synchronized void copyPassword(File source, File staged) {
        char[] value = password(source);
        if (value != null) setPassword(staged, value);
    }
    static synchronized void clearPassword(File file) {
        try {
            char[] removed = passwords.remove(file.getCanonicalPath());
            if (removed != null) Arrays.fill(removed, '\0');
        } catch (IOException ignored) { }
    }
    private static synchronized char[] password(File file) {
        try { return passwords.get(file.getCanonicalPath()); } catch (IOException ignored) { return null; }
    }
    private static boolean encrypted(String message) {
        return message != null && message.toLowerCase(Locale.ROOT).contains("encrypted");
    }
    static boolean isEncrypted(File file) { return isEncryptedZip(file); }
    static synchronized boolean hasPassword(File file) { return password(file) != null; }
    private static boolean isEncryptedZip(File file) {
        if (isRar(file) || ArchiveFormats.supported(file)) return false;
        try { return new net.lingala.zip4j.ZipFile(file).isEncrypted(); }
        catch (Exception ignored) { return false; }
    }
    private static void cachePassword(File archive, char[] password) {
        if (password != null && password.length > 0) setPassword(archive, password);
    }
    static synchronized List<Item> list(File file, String prefix) throws IOException {
        if (isRar(file)) return RarArchiveOps.list(file, prefix);
        if (ArchiveFormats.supported(file)) return ArchiveFormats.list(file, prefix);
        String key = file.getCanonicalPath();
        Index cached = indexes.get(key);
        if (cached != null && cached.size == file.length() && cached.modified == file.lastModified())
            return new ArrayList<>(cached.folders.getOrDefault(prefix, Collections.emptyList()));
        Map<String, Map<String, Item>> folders = new HashMap<>();
        int indexed = 0;
        try (ZipFile zip = new ZipFile(file)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String path = entry.getName();
                validateEntry(path);
                String parent = "";
                String[] pieces = path.split("/");
                for (int i = 0; i < pieces.length; i++) {
                    String name = pieces[i]; if (name.isEmpty()) throw new IOException("归档路径包含空目录名");
                    boolean folder = i < pieces.length - 1 || entry.isDirectory();
                    String child = parent + name + (folder ? "/" : "");
                    Map<String, Item> children = folders.computeIfAbsent(parent, p -> new LinkedHashMap<>());
                    if (!children.containsKey(child)) {
                        children.put(child, new Item(child, name, folder, folder ? 0 : entry.getSize(), entry.getTime()));
                        if (++indexed > 12000) return listPrefix(file, prefix);
                    }
                    parent = child;
                }
            }
        } catch (ZipException error) {
            if (encrypted(error.getMessage())) return listZip4j(file, prefix);
            throw error;
        }
        Map<String, List<Item>> lists = new HashMap<>();
        folders.forEach((parent, items) -> {
            List<Item> children = new ArrayList<>(items.values());
            children.sort((a, b) -> a.directory != b.directory ? (a.directory ? -1 : 1) : a.name.compareToIgnoreCase(b.name));
            lists.put(parent, children);
        });
        // Keep only small indexes; large archives remain bounded to one traversal per load.
        if (lists.values().stream().mapToInt(List::size).sum() < 12000) {
            if (indexes.size() >= 8) indexes.remove(indexes.keySet().iterator().next());
            indexes.put(key, new Index(file.length(), file.lastModified(), lists));
        }
        return new ArrayList<>(lists.getOrDefault(prefix, Collections.emptyList()));
    }
    private static List<Item> listZip4j(File file, String prefix) throws IOException {
        Map<String, Map<String, Item>> folders = new HashMap<>();
        try (net.lingala.zip4j.ZipFile zip = new net.lingala.zip4j.ZipFile(file)) {
            for (FileHeader header : zip.getFileHeaders()) {
                String path = header.getFileName().replace('\\', '/');
                boolean directory = header.isDirectory() || path.endsWith("/");
                validateEntry(directory && path.endsWith("/") ? path.substring(0, path.length() - 1) : path);
                String[] pieces = path.split("/");
                String parent = "";
                for (int i = 0; i < pieces.length; i++) {
                    if (pieces[i].isEmpty()) throw new IOException("归档路径包含空目录名");
                    boolean folder = i < pieces.length - 1 || directory;
                    String child = parent + pieces[i] + (folder ? "/" : "");
                    folders.computeIfAbsent(parent, ignored -> new LinkedHashMap<>()).putIfAbsent(child,
                            new Item(child, pieces[i], folder, folder ? 0 : Math.max(0, header.getUncompressedSize()),
                                    Math.max(0, header.getLastModifiedTime())));
                    parent = child;
                }
            }
        } catch (Exception error) {
            throw new IOException("无法读取加密 ZIP 目录：" + error.getMessage(), error);
        }
        List<Item> result = new ArrayList<>(folders.getOrDefault(prefix, Collections.emptyMap()).values());
        result.sort((a, b) -> a.directory != b.directory ? (a.directory ? -1 : 1) : a.name.compareToIgnoreCase(b.name));
        return result;
    }
    private static List<Item> listPrefix(File file, String prefix) throws IOException {
        Map<String, Item> items = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(file)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement(); String path = entry.getName(); validateEntry(path);
                if (!path.startsWith(prefix) || path.equals(prefix)) continue;
                String suffix = path.substring(prefix.length()); int slash = suffix.indexOf('/');
                boolean folder = slash >= 0 || entry.isDirectory();
                String name = slash >= 0 ? suffix.substring(0, slash) : suffix;
                if (name.isEmpty()) continue;
                String key = prefix + name + (folder ? "/" : "");
                items.putIfAbsent(key, new Item(key, name, folder, folder ? 0 : entry.getSize(), entry.getTime()));
            }
        }
        List<Item> result = new ArrayList<>(items.values());
        result.sort((a, b) -> a.directory != b.directory ? (a.directory ? -1 : 1) : a.name.compareToIgnoreCase(b.name));
        return result;
    }
    static long entrySize(File file, String entry) throws IOException {
        if (ArchiveFormats.supported(file)) {
            for (Item item : list(file, entry.contains("/") ? entry.substring(0, entry.lastIndexOf('/') + 1) : ""))
                if (item.path().equals(entry)) return item.size();
            return -1;
        }
        if (isRar(file)) return RarArchiveOps.entrySize(file, entry);
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry item = zip.getEntry(entry);
            if (item == null || item.isDirectory()) return -1;
            return item.getSize();
        }
    }
    static boolean containsEntry(File file, String entry) throws IOException { return entrySize(file, entry) >= 0; }
    static List<String> entryNames(File file) throws IOException {
        if (ArchiveFormats.supported(file)) {
            List<String> names = new ArrayList<>();
            ArrayDeque<String> pending = new ArrayDeque<>(); pending.add("");
            while (!pending.isEmpty()) for (Item item : list(file, pending.remove())) {
                names.add(item.path()); if (item.directory()) pending.add(item.path());
            }
            return names;
        }
        if (isRar(file)) return RarArchiveOps.entryNames(file);
        List<String> names = new ArrayList<>();
        try (ZipFile zip = new ZipFile(file)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) names.add(entries.nextElement().getName());
        }
        return names;
    }
    static byte[] bytes(File file, String entry, int limit) throws IOException {
        try (InputStream in = openEntry(file, entry)) {
            byte[] data = FileStore.readBytes(in, limit + 1);
            if (data.length > limit) throw new IOException("条目超过编辑上限 " + FileStore.size(limit));
            return data;
        }
    }
    static InputStream openEntry(File file, String entry) throws IOException {
        if (isRar(file)) return RarArchiveOps.openEntry(file, entry);
        if (ArchiveFormats.supported(file)) return ArchiveFormats.openEntry(file, entry);
        if (isEncryptedZip(file)) {
            char[] password = password(file);
            if (password == null) throw new PasswordRequired(file);
            return openZip4j(file, entry, password);
        }
        ZipFile zip = new ZipFile(file);
        try {
            ZipEntry item = zip.getEntry(entry);
            if (item == null || item.isDirectory()) throw new IOException("归档条目不存在");
            return new FilterInputStream(zip.getInputStream(item)) {
                @Override public void close() throws IOException {
                    try { super.close(); } finally { zip.close(); }
                }
            };
        } catch (IOException error) {
            zip.close();
            if (error instanceof ZipException && encrypted(error.getMessage())) {
                char[] password = password(file);
                if (password == null) throw new PasswordRequired(file);
                return openZip4j(file, entry, password);
            }
            throw error;
        }
    }
    private static InputStream openZip4j(File file, String entry, char[] password) throws IOException {
        net.lingala.zip4j.ZipFile zip = new net.lingala.zip4j.ZipFile(file, password);
        try {
            FileHeader header = zip.getFileHeader(entry);
            if (header == null || header.isDirectory()) throw new IOException("归档条目不存在");
            InputStream input = zip.getInputStream(header);
            return new FilterInputStream(input) {
                @Override public void close() throws IOException {
                    try { super.close(); } finally { zip.close(); }
                }
            };
        } catch (IOException | RuntimeException error) {
            try { zip.close(); } catch (IOException ignored) { }
            throw error instanceof IOException value ? value : new IOException(error.getMessage(), error);
        }
    }
    static boolean isRar(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".rar")) return true;
        if (file.length() < 7) return false;
        try (InputStream in = new FileInputStream(file)) {
            byte[] signature = new byte[8]; int count = in.read(signature);
            return count >= 7 && signature[0] == 'R' && signature[1] == 'a' && signature[2] == 'r'
                    && signature[3] == '!' && signature[4] == 0x1a && signature[5] == 0x07
                    && (signature[6] == 0x00 || (count >= 8 && signature[6] == 0x01 && signature[7] == 0x00));
        } catch (IOException ignored) { return false; }
    }
    static File extractOne(File archive, String entry, File target) throws IOException {
        if (isRar(archive) || ArchiveFormats.supported(archive) || isEncryptedZip(archive)) {
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("无法创建输出目录");
            if (target.exists()) throw new IOException("解压目标已存在：" + target.getName());
            File tmp = File.createTempFile(".rar-entry-", ".tmp", parent);
            try {
                try (InputStream in = openEntry(archive, entry); OutputStream out = new FileOutputStream(tmp)) {
                    copyBounded(in, out, 4L * 1024 * 1024 * 1024);
                }
                Files.move(tmp.toPath(), target.toPath());
            } finally { Files.deleteIfExists(tmp.toPath()); }
            return target;
        }
        try (ZipFile zip = new ZipFile(archive)) {
            ZipEntry e = zip.getEntry(entry);
            if (e == null || e.isDirectory()) throw new IOException("归档条目不存在");
            try (InputStream in = zip.getInputStream(e); OutputStream out = new FileOutputStream(target)) { FileStore.pump(in, out); }
            return target;
        }
    }
    static File safeTarget(File root, String path) throws IOException {
        if (path.startsWith("/") || path.startsWith("\\") || path.contains("\\") || path.indexOf('\0') >= 0)
            throw new IOException("归档路径无效：" + path);
        File target = new File(root, path);
        if (!target.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator))
            throw new IOException("归档条目越过输出目录：" + path);
        return target;
    }
    static void extract(File archive, String prefix, File output) throws IOException {
        extract(archive, prefix, output, false);
    }
    static void extract(File archive, String prefix, File output, boolean selected) throws IOException {
        if (ArchiveFormats.supported(archive)) { ArchiveFormats.extract(archive, prefix, output, selected); return; }
        if (isRar(archive)) { RarArchiveOps.extract(archive, prefix, output, selected); return; }
        if (!output.isDirectory() && !output.mkdirs()) throw new IOException("无法创建输出目录");
        String selectedName = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        selectedName = selectedName.substring(selectedName.lastIndexOf('/') + 1);
        long total = 0;
        try (ZipFile zip = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement(); String path = e.getName();
                if (!prefix.isEmpty() && !path.equals(prefix) && !(prefix.endsWith("/") && path.startsWith(prefix))) continue;
                String relative = prefix.endsWith("/") ? path.substring(prefix.length()) : path;
                if (selected && !prefix.isEmpty()) relative = prefix.endsWith("/") ? selectedName + "/" + relative : selectedName;
                if (relative.isEmpty()) continue;
                validateEntry(path);
                File target = safeTarget(output, relative);
                if (e.isDirectory()) {
                    if (!target.mkdirs() && !target.isDirectory()) throw new IOException("创建目录失败");
                    continue;
                }
                if (target.exists()) throw new IOException("解压目标已存在：" + relative);
                File parent = target.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("创建目录失败");
                File tmp = File.createTempFile(".extract-", ".tmp", parent);
                try {
                    try (InputStream in = zip.getInputStream(e); OutputStream out = new FileOutputStream(tmp)) {
                        byte[] buffer = new byte[131072]; int n;
                        while ((n = in.read(buffer)) >= 0) {
                            total += n;
                            if (total > 4L * 1024 * 1024 * 1024) throw new IOException("本次解压超过 4 GiB 上限");
                            out.write(buffer, 0, n);
                        }
                    }
                    Files.move(tmp.toPath(), target.toPath());
                } finally { Files.deleteIfExists(tmp.toPath()); }
            }
        } catch (ZipException error) {
            if (!encrypted(error.getMessage())) throw error;
            char[] password = password(archive);
            if (password == null) throw new PasswordRequired(archive);
            extractZip4j(archive, prefix, output, selected, password);
        }
    }
    private static void extractZip4j(File archive, String prefix, File output, boolean selected, char[] password) throws IOException {
        String selectedName = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        selectedName = selectedName.substring(selectedName.lastIndexOf('/') + 1);
        long total = 0;
        net.lingala.zip4j.ZipFile zip = new net.lingala.zip4j.ZipFile(archive, password);
        try {
            for (FileHeader header : zip.getFileHeaders()) {
                String path = header.getFileName().replace('\\', '/');
                boolean directory = header.isDirectory() || path.endsWith("/");
                String normalized = directory && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
                validateEntry(normalized);
                if (!prefix.isEmpty() && !normalized.equals(prefix) && !(prefix.endsWith("/") && path.startsWith(prefix))) continue;
                String relative = prefix.endsWith("/") ? path.substring(prefix.length()) : path;
                if (selected && !prefix.isEmpty()) relative = prefix.endsWith("/") ? selectedName + "/" + relative : selectedName;
                if (relative.isEmpty()) continue;
                File target = safeTarget(output, relative);
                if (directory) {
                    if (!target.mkdirs() && !target.isDirectory()) throw new IOException("创建目录失败");
                    continue;
                }
                if (target.exists()) throw new IOException("解压目标已存在：" + relative);
                File parent = target.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("创建目录失败");
                File tmp = File.createTempFile(".extract-", ".tmp", parent);
                try {
                    try (InputStream in = zip.getInputStream(header); OutputStream out = new FileOutputStream(tmp)) {
                        total += copyBounded(in, out, 4L * 1024 * 1024 * 1024 - total);
                    }
                    Files.move(tmp.toPath(), target.toPath());
                } finally { Files.deleteIfExists(tmp.toPath()); }
            }
        } catch (IOException | RuntimeException error) {
            throw error instanceof IOException value ? value : new IOException(error.getMessage(), error);
        } finally {
            try { zip.close(); } catch (IOException ignored) { }
        }
    }
    // Rebuild beside the archive and commit only after every entry has been written.
    static synchronized void rewrite(File archive, String remove, String rename, Map<String, File> additions) throws IOException {
        rewrite(archive, remove, rename, additions, Collections.emptySet());
    }
    static synchronized void removeAll(File archive, Set<String> removals) throws IOException {
        for (String name : removals) { validateEntry(name); if (name.isEmpty()) throw new IOException("不能删除归档根目录"); }
        rewrite(archive, null, null, Collections.emptyMap(), removals);
    }
    private static void rewrite(File archive, String remove, String rename, Map<String, File> additions,
                                Set<String> removals) throws IOException {
        if (isRar(archive) || ArchiveFormats.supported(archive) || isEncryptedZip(archive))
            throw new IOException("此归档格式暂不支持直接修改，请先解压后编辑");
        for (String name : additions.keySet()) validateEntry(name);
        if (rename != null) validateEntry(rename);
        File tmp = File.createTempFile(".archive-", ".zip", archive.getParentFile());
        boolean apk = ApkInstall.isApkName(archive.getName());
        try {
            Set<String> written = new HashSet<>();
            try (ZipFile zip = new ZipFile(archive); ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement(); String name = e.getName();
                    if (removals.contains(name) || removedParent(name, removals)) continue;
                    boolean removed = remove != null && (name.equals(remove) || (remove.endsWith("/") && name.startsWith(remove)));
                    if (apk && isSignature(name)) continue;
                    if (additions.containsKey(name)) continue;
                    if (removed && rename == null) continue;
                    String to = removed ? rename + name.substring(remove.length()) : name;
                    if (!written.add(to)) throw new IOException("归档存在重复条目：" + to);
                    ZipEntry next = new ZipEntry(to);
                    if (e.getTime() >= 0) next.setTime(e.getTime());
                    if (e.getComment() != null) next.setComment(e.getComment());
                    if (e.getMethod() == ZipEntry.STORED) {
                        next.setMethod(ZipEntry.STORED); next.setSize(e.getSize()); next.setCrc(e.getCrc());
                    }
                    out.putNextEntry(next);
                    if (!e.isDirectory()) try (InputStream in = zip.getInputStream(e)) { FileStore.pump(in, out); }
                    out.closeEntry();
                }
                for (Map.Entry<String, File> added : additions.entrySet()) {
                    if (!written.add(added.getKey())) throw new IOException("条目冲突");
                    out.putNextEntry(new ZipEntry(added.getKey()));
                    if (added.getValue() != null)
                        try (InputStream in = new FileInputStream(added.getValue())) { FileStore.pump(in, out); }
                    out.closeEntry();
                }
            }
            FileStore.replace(tmp, archive);
            indexes.remove(archive.getCanonicalPath());
        } finally { Files.deleteIfExists(tmp.toPath()); }
    }
    private static boolean removedParent(String name, Set<String> removals) {
        for (int slash = name.indexOf('/'); slash >= 0; slash = name.indexOf('/', slash + 1))
            if (removals.contains(name.substring(0, slash + 1))) return true;
        return false;
    }
    static boolean isSignature(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.equals("META-INF/MANIFEST.MF") || (upper.startsWith("META-INF/")
                && (upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA")
                || upper.endsWith(".EC") || upper.startsWith("META-INF/SIG-")));
    }
    static void validateEntry(String name) throws IOException {
        if (name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.indexOf('\0') >= 0)
            throw new IOException("归档路径无效");
        for (String piece : name.split("/")) if (piece.equals(".") || piece.equals("..")) throw new IOException("归档路径无效");
    }
    private static long copyBounded(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] buffer = new byte[131072]; long total = 0; int n;
        while ((n = in.read(buffer)) >= 0) {
            total += n;
            if (total > limit) throw new IOException("解压条目超过 4 GiB 上限");
            out.write(buffer, 0, n);
        }
        return total;
    }
    static void pack(FileStore store, List<FileStore.Node> nodes, File output) throws IOException {
        pack(store, nodes, output, "zip", new char[0]);
    }
    static void pack(FileStore store, List<FileStore.Node> nodes, File output, String format, char[] password) throws IOException {
        if (!format.equals("zip")) {
            if (password.length != 0) throw new IOException("此格式不支持压缩密码，请使用 ZIP");
            ArchiveFormats.pack(store, nodes, output, format); return;
        }
        if (password.length == 0) {
            try (ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(output)))) {
                Set<String> seen = new HashSet<>();
                for (FileStore.Node n : nodes) packNode(store, n, n.name, out, seen);
            }
        } else try (net.lingala.zip4j.io.outputstream.ZipOutputStream out = new net.lingala.zip4j.io.outputstream.ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(output)), password)) {
            Set<String> seen = new HashSet<>();
            for (FileStore.Node node : nodes) packEncrypted(store, node, node.name, out, seen);
            cachePassword(output, password);
        }
    }
    private static void packEncrypted(FileStore store, FileStore.Node node, String path,
            net.lingala.zip4j.io.outputstream.ZipOutputStream out, Set<String> seen) throws IOException {
        String key = node.file != null ? node.file.getCanonicalPath() : node.key();
        if (!seen.add(key)) throw new IOException("目录包含循环引用");
        validateEntry(path);
        net.lingala.zip4j.model.ZipParameters parameters = new net.lingala.zip4j.model.ZipParameters();
        parameters.setFileNameInZip(path + (node.directory ? "/" : ""));
        parameters.setEncryptFiles(!node.directory);
        parameters.setEncryptionMethod(net.lingala.zip4j.model.enums.EncryptionMethod.AES);
        parameters.setAesKeyStrength(net.lingala.zip4j.model.enums.AesKeyStrength.KEY_STRENGTH_256);
        if (node.modified > 0) parameters.setLastModifiedFileTime(node.modified);
        out.putNextEntry(parameters);
        if (!node.directory) try (InputStream input = store.read(node)) { FileStore.pump(input, out); }
        out.closeEntry();
        if (node.directory) for (FileStore.Node child : store.list(node))
            packEncrypted(store, child, path + "/" + child.name, out, seen);
        seen.remove(key);
    }
    private static void packNode(FileStore store, FileStore.Node node, String path, ZipOutputStream out,
                                 Set<String> seen) throws IOException {
        String canonical = node.file != null ? node.file.getCanonicalPath() : node.key();
        if (!seen.add(canonical)) throw new IOException("目录包含循环引用");
        validateEntry(path);
        ZipEntry entry = new ZipEntry(path + (node.directory ? "/" : ""));
        if (node.modified > 0) entry.setTime(node.modified);
        out.putNextEntry(entry);
        if (!node.directory) try (InputStream in = store.read(node)) { FileStore.pump(in, out); }
        out.closeEntry();
        if (node.directory) for (FileStore.Node n : store.list(node)) packNode(store, n, path + "/" + n.name, out, seen);
        seen.remove(canonical);
    }
    private ArchiveOps() {}
}
