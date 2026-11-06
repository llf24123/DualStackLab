package com.dualstack.lab;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.*;

final class ArchiveFormats {
    private static final long MAX_EXTRACTED = 4L * 1024 * 1024 * 1024;

    static boolean supported(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".tar") || name.endsWith(".tar.gz") || name.endsWith(".tgz") || name.endsWith(".7z");
    }
    private static boolean sevenZ(File file) {
        return file.getName().toLowerCase(Locale.ROOT).endsWith(".7z");
    }

    static List<ArchiveOps.Item> list(File file, String prefix) throws IOException {
        if (sevenZ(file)) return listSevenZ(file, prefix);
        String parent = selection(prefix);
        Map<String, ArchiveOps.Item> items = new LinkedHashMap<>();
        try (TarArchiveInputStream input = open(file)) {
            TarArchiveEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                String path = path(entry, input);
                if (path.isEmpty() || !path.startsWith(parent) || path.equals(parent)) continue;
                String suffix = path.substring(parent.length());
                int slash = suffix.indexOf('/');
                boolean directory = slash >= 0 || entry.isDirectory();
                String name = slash >= 0 ? suffix.substring(0, slash) : suffix;
                String child = parent + name + (directory ? "/" : "");
                ArchiveOps.Item item = new ArchiveOps.Item(child, name, directory,
                        directory ? 0 : entry.getSize(), entry.getLastModifiedDate().getTime());
                items.putIfAbsent(child, item);
            }
        }
        List<ArchiveOps.Item> result = new ArrayList<>(items.values());
        result.sort((a, b) -> a.directory() != b.directory() ? (a.directory() ? -1 : 1)
                : a.name().compareToIgnoreCase(b.name()));
        return result;
    }

    static InputStream openEntry(File file, String entry) throws IOException {
        if (sevenZ(file)) return openSevenZEntry(file, entry);
        String requested = selection(entry);
        TarArchiveInputStream input = open(file);
        try {
            TarArchiveEntry item;
            while ((item = input.getNextEntry()) != null) {
                String path = path(item, input);
                if (!path.equals(requested)) continue;
                if (item.isDirectory()) throw new IOException("请选择 TAR 内的文件");
                if (item.getSize() > MAX_EXTRACTED) throw new IOException("解压条目超过 4 GiB 上限");
                return input;
            }
            throw new IOException("TAR 条目不存在：" + entry);
        } catch (IOException | RuntimeException error) {
            try { input.close(); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    static void extract(File file, String prefix, File output, boolean selected) throws IOException {
        if (sevenZ(file)) { extractSevenZ(file, prefix, output, selected); return; }
        String requested = selection(prefix);
        String selectedName = requested.endsWith("/") ? requested.substring(0, requested.length() - 1) : requested;
        selectedName = selectedName.substring(selectedName.lastIndexOf('/') + 1);
        List<Path> created = new ArrayList<>();
        long total = 0;
        try {
            directories(output.toPath(), created);
            try (TarArchiveInputStream input = open(file)) {
                TarArchiveEntry entry;
                while ((entry = input.getNextEntry()) != null) {
                    String path = path(entry, input);
                    if (path.isEmpty()) continue;
                    Tasks.Progress.step(path);
                    String normalized = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
                    if (!requested.isEmpty() && !normalized.equals(requested)
                            && !(requested.endsWith("/") && path.startsWith(requested))) continue;
                    String relative = requested.endsWith("/") ? path.substring(requested.length()) : path;
                    if (selected && !requested.isEmpty()) relative = requested.endsWith("/")
                            ? selectedName + "/" + relative : selectedName;
                    if (relative.isEmpty()) continue;
                    Path target = ArchiveOps.safeTarget(output, relative).toPath();
                    if (entry.isDirectory()) {
                        directories(target, created);
                        continue;
                    }
                    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
                        throw new IOException("解压目标已存在：" + relative);
                    if (entry.getSize() > MAX_EXTRACTED - total) throw new IOException("本次解压超过 4 GiB 上限");
                    directories(target.getParent(), created);
                    Path temporary = Files.createTempFile(target.getParent(), ".tar-entry-", ".tmp");
                    try {
                        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(temporary))) {
                            total += copy(input, out, MAX_EXTRACTED - total);
                        }
                        Files.move(temporary, target);
                        created.add(target);
                    } finally { Files.deleteIfExists(temporary); }
                }
            }
        } catch (IOException | RuntimeException error) {
            for (int i = created.size() - 1; i >= 0; i--) {
                try { Files.deleteIfExists(created.get(i)); }
                catch (IOException cleanup) { error.addSuppressed(cleanup); }
            }
            throw error;
        }
    }

    static void pack(FileStore store, List<FileStore.Node> nodes, File output, String format) throws IOException {
        if (format.equals("7z")) { packSevenZ(store, nodes, output); return; }
        if (!format.equals("tar") && !format.equals("tar.gz")) throw new IOException("不支持的压缩格式：" + format);
        String destination = output.getCanonicalPath();
        for (FileStore.Node node : nodes) {
            if (node.file == null) continue;
            String source = node.file.getCanonicalPath();
            if (destination.equals(source) || node.directory && destination.startsWith(source + File.separator))
                throw new IOException("归档输出不能位于所选文件夹内部");
        }
        Path temporary = Files.createTempFile(output.getAbsoluteFile().getParentFile().toPath(), ".tar-pack-", ".tmp");
        try {
            try (OutputStream file = new BufferedOutputStream(Files.newOutputStream(temporary));
                 OutputStream compressed = format.equals("tar.gz") ? new GzipCompressorOutputStream(file) : file;
                 TarArchiveOutputStream tar = new TarArchiveOutputStream(compressed, "UTF-8")) {
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
                tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
                tar.setAddPaxHeadersForNonAsciiNames(true);
                Set<String> seen = new HashSet<>(), written = new HashSet<>();
                for (FileStore.Node node : nodes) packNode(store, node, node.name, tar, seen, written, 0);
            }
            FileStore.replace(temporary.toFile(), output);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void packNode(FileStore store, FileStore.Node node, String path, TarArchiveOutputStream output,
                                 Set<String> seen, Set<String> written, int depth) throws IOException {
        Tasks.Progress.step(path);
        ArchiveOps.validateEntry(path);
        if (node.file != null && Files.isSymbolicLink(node.file.toPath()))
            throw new IOException("TAR 压缩不跟随符号链接：" + path);
        String key = node.file != null ? node.file.getCanonicalPath() : node.key();
        if (depth > 256 || !seen.add(key)) throw new IOException("目录包含循环引用或嵌套过深");
        if (!written.add(path)) throw new IOException("归档存在同名条目：" + path);
        File payload = null;
        try {
            TarArchiveEntry entry = new TarArchiveEntry(path + (node.directory ? "/" : ""));
            if (node.modified > 0) entry.setModTime(node.modified);
            if (!node.directory) {
                if (node.file == null || node.root || node.size <= 0) payload = store.materialize(node);
                entry.setSize(payload != null ? payload.length()
                        : node.file != null ? node.file.length() : node.size);
            }
            output.putArchiveEntry(entry);
            if (!node.directory) try (InputStream input = payload == null ? store.read(node)
                    : new BufferedInputStream(new FileInputStream(payload))) {
                FileStore.pump(input, output);
            }
            output.closeArchiveEntry();
            if (node.directory) for (FileStore.Node child : store.list(node))
                packNode(store, child, path + "/" + child.name, output, seen, written, depth + 1);
        } finally {
            seen.remove(key);
            if (payload != null) store.release(node, payload);
        }
    }

    private static List<ArchiveOps.Item> listSevenZ(File file, String prefix) throws IOException {
        String parent = selection(prefix);
        Map<String, ArchiveOps.Item> items = new LinkedHashMap<>();
        try (SevenZFile archive = new SevenZFile(file)) {
            SevenZArchiveEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                String path = sevenPath(entry);
                if (path.isEmpty() || !path.startsWith(parent) || path.equals(parent)) continue;
                String suffix = path.substring(parent.length());
                int slash = suffix.indexOf('/');
                boolean directory = slash >= 0 || entry.isDirectory();
                String name = slash >= 0 ? suffix.substring(0, slash) : suffix;
                String child = parent + name + (directory ? "/" : "");
                items.putIfAbsent(child, new ArchiveOps.Item(child, name, directory,
                        directory ? 0 : entry.getSize(), entry.getLastModifiedDate() == null ? 0 : entry.getLastModifiedDate().getTime()));
            }
        }
        List<ArchiveOps.Item> result = new ArrayList<>(items.values());
        result.sort((a, b) -> a.directory() != b.directory() ? (a.directory() ? -1 : 1)
                : a.name().compareToIgnoreCase(b.name()));
        return result;
    }

    private static InputStream openSevenZEntry(File file, String entry) throws IOException {
        String requested = selection(entry);
        SevenZFile archive = new SevenZFile(file);
        try {
            SevenZArchiveEntry item;
            while ((item = archive.getNextEntry()) != null) {
                if (!sevenPath(item).equals(requested)) continue;
                if (item.isDirectory()) throw new IOException("请选择 7Z 内的文件");
                return new InputStream() {
                    @Override public int read() throws IOException { return archive.read(); }
                    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                        return archive.read(buffer, offset, length);
                    }
                    @Override public void close() throws IOException { archive.close(); }
                };
            }
            throw new IOException("7Z 条目不存在：" + entry);
        } catch (IOException | RuntimeException error) {
            try { archive.close(); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    private static void extractSevenZ(File file, String prefix, File output, boolean selected) throws IOException {
        String requested = selection(prefix);
        String selectedName = requested.endsWith("/") ? requested.substring(0, requested.length() - 1) : requested;
        selectedName = selectedName.substring(selectedName.lastIndexOf('/') + 1);
        List<Path> created = new ArrayList<>();
        long total = 0;
        try {
            directories(output.toPath(), created);
            try (SevenZFile archive = new SevenZFile(file)) {
                SevenZArchiveEntry item;
                while ((item = archive.getNextEntry()) != null) {
                    String path = sevenPath(item);
                    if (path.isEmpty()) continue;
                    Tasks.Progress.step(path);
                    String normalized = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
                    if (!requested.isEmpty() && !normalized.equals(requested)
                            && !(requested.endsWith("/") && path.startsWith(requested))) continue;
                    String relative = requested.endsWith("/") ? path.substring(requested.length()) : path;
                    if (selected && !requested.isEmpty()) relative = requested.endsWith("/")
                            ? selectedName + "/" + relative : selectedName;
                    if (relative.isEmpty()) continue;
                    Path target = ArchiveOps.safeTarget(output, relative).toPath();
                    if (item.isDirectory()) { directories(target, created); continue; }
                    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
                        throw new IOException("解压目标已存在：" + relative);
                    if (item.getSize() > MAX_EXTRACTED - total) throw new IOException("本次解压超过 4 GiB 上限");
                    directories(target.getParent(), created);
                    Path temporary = Files.createTempFile(target.getParent(), ".7z-entry-", ".tmp");
                    try {
                        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(temporary))) {
                            total += copySevenZ(archive, out, MAX_EXTRACTED - total);
                        }
                        Files.move(temporary, target);
                        created.add(target);
                    } finally { Files.deleteIfExists(temporary); }
                }
            }
        } catch (IOException | RuntimeException error) {
            for (int i = created.size() - 1; i >= 0; i--) {
                try { Files.deleteIfExists(created.get(i)); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            }
            throw error;
        }
    }

    private static void packSevenZ(FileStore store, List<FileStore.Node> nodes, File output) throws IOException {
        Path temporary = Files.createTempFile(output.getAbsoluteFile().getParentFile().toPath(), ".7z-pack-", ".tmp");
        try {
            try (SevenZOutputFile archive = new SevenZOutputFile(temporary.toFile())) {
                Set<String> seen = new HashSet<>(), written = new HashSet<>();
                for (FileStore.Node node : nodes) packSevenZNode(store, node, node.name, archive, seen, written, 0);
            }
            FileStore.replace(temporary.toFile(), output);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void packSevenZNode(FileStore store, FileStore.Node node, String path,
            SevenZOutputFile output, Set<String> seen, Set<String> written, int depth) throws IOException {
        Tasks.Progress.step(path);
        ArchiveOps.validateEntry(path);
        String key = node.file != null ? node.file.getCanonicalPath() : node.key();
        if (depth > 256 || !seen.add(key)) throw new IOException("目录包含循环引用或嵌套过深");
        if (!written.add(path)) throw new IOException("归档存在同名条目：" + path);
        File payload = null;
        try {
            SevenZArchiveEntry entry = new SevenZArchiveEntry();
            entry.setName(path + (node.directory ? "/" : ""));
            entry.setDirectory(node.directory);
            if (node.modified > 0) entry.setLastModifiedDate(new Date(node.modified));
            if (!node.directory) {
                if (node.file == null || node.root || node.size <= 0) payload = store.materialize(node);
                long length = payload != null ? payload.length() : node.file != null ? node.file.length() : node.size;
                entry.setSize(length); entry.setHasStream(true);
            }
            output.putArchiveEntry(entry);
            if (!node.directory) try (InputStream input = payload == null ? store.read(node)
                    : new BufferedInputStream(new FileInputStream(payload))) {
                byte[] buffer = new byte[131072]; int count;
                while ((count = input.read(buffer)) != -1) if (count > 0) output.write(buffer, 0, count);
            }
            output.closeArchiveEntry();
            if (node.directory) for (FileStore.Node child : store.list(node))
                packSevenZNode(store, child, path + "/" + child.name, output, seen, written, depth + 1);
        } finally {
            seen.remove(key);
            if (payload != null) store.release(node, payload);
        }
    }

    private static String sevenPath(SevenZArchiveEntry entry) throws IOException {
        if (entry.isAntiItem()) throw new IOException("7Z 反条目不受支持：" + entry.getName());
        String name = entry.getName();
        while (name.startsWith("./")) name = name.substring(2);
        if (name.isEmpty() || entry.isDirectory() && name.equals(".")) return "";
        ArchiveOps.validateEntry(name);
        for (String piece : name.split("/")) if (piece.isEmpty()) throw new IOException("7Z 路径包含空目录名");
        if (name.matches("^[A-Za-z]:.*")) throw new IOException("7Z 条目路径无效：" + name);
        return entry.isDirectory() && !name.endsWith("/") ? name + "/" : name;
    }

    private static long copySevenZ(SevenZFile input, OutputStream output, long remaining) throws IOException {
        byte[] buffer = new byte[128 * 1024];
        long count = 0;
        int read;
        while ((read = input.read(buffer, 0, buffer.length)) != -1) {
            if (read == 0) continue;
            if (read > remaining - count) throw new IOException("本次解压超过 4 GiB 上限");
            output.write(buffer, 0, read);
            count += read;
        }
        return count;
    }

    private static TarArchiveInputStream open(File file) throws IOException {
        BufferedInputStream input = new BufferedInputStream(new FileInputStream(file));
        try {
            input.mark(2);
            int first = input.read(), second = input.read();
            input.reset();
            InputStream content = first == 0x1f && second == 0x8b ? new GzipCompressorInputStream(input) : input;
            return new TarArchiveInputStream(content, "UTF-8");
        } catch (IOException | RuntimeException error) {
            try { input.close(); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    private static String path(TarArchiveEntry entry, TarArchiveInputStream input) throws IOException {
        if (entry.isSymbolicLink() || entry.isLink()) throw new IOException("TAR 链接条目不支持读取或解压");
        if ((!entry.isFile() && !entry.isDirectory()) || !input.canReadEntryData(entry))
            throw new IOException("不支持的 TAR 条目类型：" + entry.getName());
        String name = entry.getName();
        while (name.startsWith("./")) name = name.substring(2);
        if (entry.isDirectory() && (name.isEmpty() || name.equals("."))) return "";
        ArchiveOps.validateEntry(name);
        for (String piece : name.split("/")) if (piece.isEmpty()) throw new IOException("TAR 路径包含空目录名");
        if (name.matches("^[A-Za-z]:.*")) throw new IOException("TAR 条目路径无效：" + name);
        return entry.isDirectory() && !name.endsWith("/") ? name + "/" : name;
    }

    private static String selection(String value) throws IOException {
        if (!value.isEmpty()) {
            ArchiveOps.validateEntry(value);
            for (String piece : value.split("/")) if (piece.isEmpty()) throw new IOException("TAR 路径包含空目录名");
        }
        return value;
    }

    private static void directories(Path directory, List<Path> created) throws IOException {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory))
                throw new IOException("解压目录不是普通文件夹：" + directory);
            return;
        }
        Path parent = directory.getParent();
        if (parent != null) directories(parent, created);
        Files.createDirectory(directory);
        created.add(directory);
    }

    private static long copy(InputStream input, OutputStream output, long remaining) throws IOException {
        byte[] buffer = new byte[128 * 1024];
        long count = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read == 0) continue;
            if (read > remaining - count) throw new IOException("本次解压超过 4 GiB 上限");
            output.write(buffer, 0, read);
            count += read;
        }
        return count;
    }

    private ArchiveFormats() { }
}
