package com.dualstack.lab;

import com.github.junrar.Archive;
import com.github.junrar.ArchiveOptions;
import com.github.junrar.rarfile.FileHeader;
import com.github.junrar.rarfile.rar5.Rar5Redirection;
import com.github.junrar.rarfile.rar5.Rar5RedirType;
import java.io.*;
import java.nio.file.Files;
import java.util.*;

final class RarArchiveOps {
    private static final long MAX_DICTIONARY = 256L * 1024 * 1024;
    private static final long MAX_EXTRACTED = 4L * 1024 * 1024 * 1024;

    private RarArchiveOps() {}

    static List<ArchiveOps.Item> list(File file, String prefix) throws IOException {
        Map<String, Map<String, ArchiveOps.Item>> folders = new HashMap<>();
        try (Archive archive = open(file)) {
            for (FileHeader header : archive.getFileHeaders()) {
                String raw = normalize(header.getFileName());
                boolean directory = header.isDirectory() || raw.endsWith("/");
                String name = directory && raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
                ArchiveOps.validateEntry(name);
                String[] pieces = name.split("/");
                String parent = "";
                for (int i = 0; i < pieces.length; i++) {
                    if (pieces[i].isEmpty()) throw new IOException("RAR 路径包含空目录名");
                    boolean folder = i < pieces.length - 1 || directory;
                    String child = parent + pieces[i] + (folder ? "/" : "");
                    Map<String, ArchiveOps.Item> children = folders.computeIfAbsent(parent, ignored -> new LinkedHashMap<>());
                    if (!children.containsKey(child)) {
                        long size = folder ? 0 : Math.max(0, header.getFullUnpackSize());
                        long modified = header.getLastModifiedTime() == null ? 0 : header.getLastModifiedTime().toMillis();
                        children.put(child, new ArchiveOps.Item(child, pieces[i], folder, size, modified));
                    }
                    parent = child;
                }
            }
        } catch (Exception error) {
            throw io("无法读取 RAR 归档", error);
        }
        List<ArchiveOps.Item> result = new ArrayList<>(folders.getOrDefault(prefix, Collections.emptyMap()).values());
        sort(result);
        return result;
    }

    static InputStream openEntry(File file, String entry) throws IOException {
        Archive archive = open(file);
        try {
            FileHeader match = null;
            for (FileHeader header : archive.getFileHeaders()) {
                if (normalize(header.getFileName()).equals(entry)) { match = header; break; }
            }
            if (match == null || match.isDirectory()) throw new IOException("RAR 条目不存在");
            checkReadable(match);
            InputStream content = archive.getInputStream(match);
            return new FilterInputStream(content) {
                @Override public void close() throws IOException {
                    try { super.close(); } finally { archive.close(); }
                }
            };
        } catch (Exception error) {
            try { archive.close(); } catch (IOException ignored) { }
            throw io("无法读取 RAR 条目", error);
        }
    }

    static void extract(File file, String prefix, File output, boolean selected) throws IOException {
        if (!output.isDirectory() && !output.mkdirs()) throw new IOException("无法创建输出目录");
        String selectedName = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        selectedName = selectedName.substring(selectedName.lastIndexOf('/') + 1);
        long total = 0;
        try (Archive archive = open(file)) {
            for (FileHeader header : archive.getFileHeaders()) {
                String path = normalize(header.getFileName());
                checkNoLink(header);
                boolean directory = header.isDirectory() || path.endsWith("/");
                String normalized = directory && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
                ArchiveOps.validateEntry(normalized);
                if (!prefix.isEmpty() && !normalized.equals(prefix) && !(prefix.endsWith("/") && path.startsWith(prefix))) continue;
                String relative = prefix.endsWith("/") ? path.substring(prefix.length()) : path;
                if (selected && !prefix.isEmpty()) relative = prefix.endsWith("/") ? selectedName + "/" + relative : selectedName;
                if (relative.isEmpty()) continue;
                File target = ArchiveOps.safeTarget(output, relative);
                if (directory) {
                    if (!target.mkdirs() && !target.isDirectory()) throw new IOException("创建目录失败");
                    continue;
                }
                if (target.exists()) throw new IOException("解压目标已存在：" + relative);
                checkReadable(header);
                File parent = target.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("创建目录失败");
                File tmp = File.createTempFile(".extract-", ".tmp", parent);
                try {
                    try (InputStream in = archive.getInputStream(header); OutputStream out = new FileOutputStream(tmp)) {
                        total += copyBounded(in, out, MAX_EXTRACTED - total);
                    }
                    Files.move(tmp.toPath(), target.toPath());
                } finally { Files.deleteIfExists(tmp.toPath()); }
            }
        } catch (Exception error) {
            throw io("RAR 解压失败", error);
        }
    }

    private static Archive open(File file) throws IOException {
        try {
            return new Archive(file, ArchiveOptions.builder().maxDictionarySize(MAX_DICTIONARY).build());
        } catch (Exception error) {
            throw io("不是可读取的 RAR/RAR5 文件，或归档已损坏", error);
        }
    }

    private static void checkReadable(FileHeader header) throws IOException {
        checkNoLink(header);
        if (header.isEncrypted()) throw new IOException("此 RAR 条目受密码保护，当前版本暂不支持密码输入");
        if (header.isSplitBefore() || header.isSplitAfter()) throw new IOException("此条目属于分卷 RAR，请将所有分卷放在同一目录后重试");
    }

    private static void checkNoLink(FileHeader header) throws IOException {
        Rar5Redirection link = header.getRedirection();
        if (link != null && link.getType() != null && link.getType() != Rar5RedirType.NONE)
            throw new IOException("为避免路径越界，RAR 链接条目不支持读取或解压");
    }

    private static String normalize(String name) throws IOException {
        if (name == null || name.isEmpty()) throw new IOException("RAR 条目名称为空");
        String normalized = name.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*") || normalized.contains(":"))
            throw new IOException("RAR 条目路径无效：" + name);
        return normalized;
    }

    private static long copyBounded(InputStream in, OutputStream out, long remaining) throws IOException {
        byte[] buffer = new byte[131072]; long total = 0; int count;
        while ((count = in.read(buffer)) >= 0) {
            total += count;
            if (total > remaining) throw new IOException("本次解压超过 4 GiB 上限");
            out.write(buffer, 0, count);
        }
        return total;
    }

    private static void sort(List<ArchiveOps.Item> items) {
        items.sort((a, b) -> a.directory() != b.directory() ? (a.directory() ? -1 : 1)
                : a.name().compareToIgnoreCase(b.name()));
    }

    private static IOException io(String message, Exception error) {
        return error instanceof IOException value ? value : new IOException(message + ": " + error.getMessage(), error);
    }
}
