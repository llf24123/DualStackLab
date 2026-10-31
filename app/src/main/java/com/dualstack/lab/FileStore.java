package com.dualstack.lab;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.TimeUnit;

final class FileStore {
    static final class Node {
        final String name;
        final File file;
        final Uri uri;
        final boolean directory, root;
        final long size, modified;
        final Node container;
        final String entry;
        private String cachedKey;
        Node(String name, File file, Uri uri, boolean directory, boolean root, long size, long modified) {
            this(name, file, uri, directory, root, size, modified, null, null);
        }
        Node(String name, File file, Uri uri, boolean directory, boolean root, long size, long modified,
             Node container, String entry) {
            this.name = name; this.file = file; this.uri = uri; this.directory = directory;
            this.root = root; this.size = size; this.modified = modified;
            this.container = container; this.entry = entry;
        }
        String key() {
            if (cachedKey != null) return cachedKey;
            if (container != null) return cachedKey = new Uri.Builder().scheme("zip").authority("entry")
                    .appendQueryParameter("source", container.key()).appendQueryParameter("entry", entry)
                    .appendQueryParameter("dir", directory ? "1" : "0").build().toString();
            return cachedKey = uri != null ? uri.toString() : (root ? "root:" : "") + file.getAbsolutePath();
        }
        String path() { return container != null ? container.path() + "!/" + entry : file != null ? file.getAbsolutePath() : name; }
        void put(Intent i) {
            if (container != null) { i.putExtra("archiveKey", key()).putExtra("name", name); return; }
            i.putExtra("name", name).putExtra("root", root);
            if (file != null) i.putExtra("path", file.getAbsolutePath());
            if (uri != null) i.putExtra("uri", uri.toString());
        }
    }
    private final Context context;
    FileStore(Context c) { context = c.getApplicationContext(); }
    static Node local(File f) { return local(f, false); }
    static Node local(File f, boolean root) {
        try {
            java.nio.file.attribute.BasicFileAttributes attributes = Files.readAttributes(f.toPath(),
                    java.nio.file.attribute.BasicFileAttributes.class);
            return new Node(f.getName().isEmpty() ? "/" : f.getName(), f, null, attributes.isDirectory(), root,
                    attributes.size(), attributes.lastModifiedTime().toMillis());
        } catch (IOException ignored) {}
        return new Node(f.getName().isEmpty() ? "/" : f.getName(), f, null,
                f.isDirectory(), root, f.length(), f.lastModified());
    }
    static Node directory(File f, boolean root) {
        return new Node(f.getName(), f, null, true, root, 0, 0);
    }
    Node fromIntent(Intent i) throws IOException {
        if (i.hasExtra("archiveKey")) return fromKey(i.getStringExtra("archiveKey"));
        if (i.hasExtra("uri")) return document(Uri.parse(i.getStringExtra("uri")));
        return RootAccess.automatic(local(new File(Objects.requireNonNull(i.getStringExtra("path"))), i.getBooleanExtra("root", false)));
    }
    Node fromKey(String key) throws IOException {
        if (key.startsWith("zip:")) {
            Uri value = Uri.parse(key);
            Node source = fromKey(Objects.requireNonNull(value.getQueryParameter("source")));
            String entry = Objects.requireNonNull(value.getQueryParameter("entry"));
            if (!entry.isEmpty()) ArchiveOps.validateEntry(entry);
            return archiveNode(source, entry, "1".equals(value.getQueryParameter("dir")), 0, 0);
        }
        if (key.startsWith("content:")) return document(Uri.parse(key));
        boolean root = key.startsWith("root:");
        File file = new File(root ? key.substring(5) : key);
        return local(file, root);
    }
    static Node archiveNode(Node source, String entry, boolean directory, long size, long modified) {
        String trimmed = entry.endsWith("/") ? entry.substring(0, entry.length() - 1) : entry;
        String name = entry.isEmpty() ? source.name : trimmed.substring(trimmed.lastIndexOf('/') + 1);
        return new Node(name, null, null, directory, false, size, modified, source, entry);
    }
    Node parent(Node node) {
        if (node.container != null) {
            if (node.entry.isEmpty()) return parent(node.container);
            String value = node.entry.endsWith("/") ? node.entry.substring(0, node.entry.length() - 1) : node.entry;
            int slash = value.lastIndexOf('/');
            return archiveNode(node.container, slash < 0 ? "" : value.substring(0, slash + 1), true, 0, 0);
        }
        return node.file != null && node.file.getParentFile() != null ? directory(node.file.getParentFile(), node.root) : null;
    }
    Node document(Uri uri) throws IOException {
        try (Cursor c = context.getContentResolver().query(uri, null, null, null, null)) {
            String name = uri.getLastPathSegment(), mime = context.getContentResolver().getType(uri);
            long size = 0, modified = 0;
            if (c != null && c.moveToFirst()) {
                int n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME), s = c.getColumnIndex(OpenableColumns.SIZE);
                int m = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
                int t = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
                if (n >= 0 && !c.isNull(n)) name = c.getString(n);
                if (m >= 0 && !c.isNull(m)) mime = c.getString(m);
                if (s >= 0 && !c.isNull(s)) size = c.getLong(s);
                if (t >= 0 && !c.isNull(t)) modified = c.getLong(t);
            }
            return new Node(name == null || name.isBlank() ? "document" : name, null, uri,
                    DocumentsContract.Document.MIME_TYPE_DIR.equals(mime), false, size, modified);
        } catch (Exception e) { throw new IOException("目录授权失效或不可读取：" + e.getMessage(), e); }
    }
    List<Node> list(Node parent) throws IOException {
        parent = RootAccess.automatic(parent);
        List<Node> out = new ArrayList<>();
        if (parent.container != null) {
            File archive = materialize(parent.container);
            try {
                for (ArchiveOps.Item item : ArchiveOps.list(archive, parent.entry))
                    out.add(archiveNode(parent.container, item.path(), item.directory(), item.size(), item.modified()));
            } finally { release(parent.container, archive); }
        } else if (parent.uri != null) {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(parent.uri,
                    DocumentsContract.getDocumentId(parent.uri));
            try (Cursor c = context.getContentResolver().query(children, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null)) {
                if (c == null) throw new IOException("存储提供方没有返回目录");
                while (c.moveToNext()) out.add(new Node(c.getString(1), null,
                        DocumentsContract.buildDocumentUriUsingTree(parent.uri, c.getString(0)),
                        DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2)),
                        false, c.isNull(3) ? 0 : c.getLong(3), c.isNull(4) ? 0 : c.getLong(4)));
            } catch (Exception e) { throw new IOException("目录读取失败：" + e.getMessage(), e); }
        } else if (parent.root) {
            out.addAll(parseRootListing(RootDirectoryReader.list(parent.path())));
        } else {
            File[] files = parent.file.listFiles();
            if (files == null) {
                if (StorageNavigation.storageContainer(parent.file)) out.add(StorageNavigation.knownStorageChild(parent.file));
                else throw new IOException("无法读取 " + parent.path() + "，请检查存储权限");
            } else for (File f : files) out.add(local(f));
        }
        out.sort((a, b) -> a.directory != b.directory ? (a.directory ? -1 : 1) : a.name.compareToIgnoreCase(b.name));
        return out;
    }
    static String rootListCommand(String path) {
        // Keep batch stat fast; dangling links must not reject the entire directory.
        String batch = "if ! /system/bin/toybox stat -L -c '%f|%s|%Y|%n' \"$@\" 2>/dev/null; then "
                + "for entry do if [ -L \"$entry\" ] && [ ! -e \"$entry\" ]; then "
                + "/system/bin/toybox stat -c '%f|%s|%Y|%n' \"$entry\" 2>/dev/null; fi; done; fi; exit 0";
        return "/system/bin/toybox find -H " + quote(path)
                + " -mindepth 1 -maxdepth 1 -exec /system/bin/sh -c " + quote(batch) + " sh {} +";
    }
    static List<Node> parseRootListing(String output) throws IOException {
        List<Node> out = new ArrayList<>();
        for (String line : output.split("\n")) {
                String[] fields = line.split("\\|", 4);
                if (fields.length != 4) continue;
                try {
                    int mode = Integer.parseInt(fields[0], 16);
                    File f = new File(fields[3]);
                    out.add(new Node(f.getName(), f, null, (mode & 0xf000) == 0x4000,
                            true, Long.parseLong(fields[1]), Long.parseLong(fields[2]) * 1000));
                } catch (NumberFormatException e) { throw new IOException("ROOT 目录解析失败", e); }
        }
        return out;
    }
    InputStream read(Node requested) throws IOException {
        Node node = RootAccess.automatic(requested);
        if (node.container != null) {
            File archive = materialize(node.container);
            try {
                return new FilterInputStream(ArchiveOps.openEntry(archive, node.entry)) {
                    @Override public void close() throws IOException {
                        try { super.close(); } finally { release(node.container, archive); }
                    }
                };
            } catch (IOException e) {
                release(node.container, archive); throw e;
            }
        }
        if (node.uri != null) {
            InputStream in = context.getContentResolver().openInputStream(node.uri);
            if (in == null) throw new IOException("无法打开文件");
            return in;
        }
        if (!node.root) return new BufferedInputStream(new FileInputStream(node.file));
        Process p = RootShell.command("cat " + quote(node.path())).start();
        return new FilterInputStream(p.getInputStream()) {
            @Override public void close() throws IOException {
                super.close();
                try {
                    if (!p.waitFor(10, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new IOException("ROOT 读取超时"); }
                    if (p.exitValue() != 0) throw new IOException("ROOT 读取失败");
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
            }
        };
    }
    void write(Node node, File content) throws IOException {
        node = RootAccess.automatic(node);
        if (node.container != null) {
            editArchive(node.container, null, null, Map.of(node.entry, content));
        } else if (node.uri != null) {
            try (InputStream in = new FileInputStream(content);
                 OutputStream out = context.getContentResolver().openOutputStream(node.uri, "wt")) {
                if (out == null) throw new IOException("此目录只读");
                pump(in, out);
            }
        } else if (node.root) {
            String tmp = node.path() + ".dualstack-" + UUID.randomUUID();
            Process p = RootShell.command(rootWriteCommand(node.path(), tmp)).start();
            try (OutputStream out = p.getOutputStream(); InputStream in = new FileInputStream(content)) { pump(in, out); }
            await(p);
        } else {
            File tmp = File.createTempFile(".dualstack-", ".tmp", node.file.getParentFile());
            try {
                Files.copy(content.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
                replace(tmp, node.file);
            } finally { Files.deleteIfExists(tmp.toPath()); }
        }
    }
    Node create(Node parent, String name, boolean directory) throws IOException {
        parent = RootAccess.automatic(parent);
        checkName(name);
        for (Node n : list(parent)) if (name.equals(n.name)) throw new IOException("同名文件已存在");
        if (parent.container != null) {
            String entry = parent.entry + name + (directory ? "/" : "");
            Map<String, File> additions = new HashMap<>(); additions.put(entry, null);
            editArchive(parent.container, null, null, additions);
            return archiveNode(parent.container, entry, directory, 0, System.currentTimeMillis());
        }
        if (parent.uri != null) {
            Uri u = DocumentsContract.createDocument(context.getContentResolver(), parent.uri,
                    directory ? DocumentsContract.Document.MIME_TYPE_DIR : "application/octet-stream", name);
            if (u == null) throw new IOException("无法创建");
            return document(u);
        }
        File f = new File(parent.file, name);
        if (parent.root) {
            shell((directory ? "mkdir " : "touch ") + quote(f.getAbsolutePath()), true);
            return new Node(name, f, null, directory, true, 0, System.currentTimeMillis());
        }
        if (!(directory ? f.mkdir() : f.createNewFile())) throw new IOException("无法创建 " + name);
        return local(f);
    }
    Node rename(Node node, String name) throws IOException {
        checkRemoval(node);
        node = RootAccess.automatic(node);
        checkName(name);
        if (node.container != null) {
            Node parent = parent(node);
            for (Node sibling : list(parent)) if (sibling.name.equals(name)) throw new IOException("同名文件已存在");
            String entry = parent.entry + name + (node.directory ? "/" : "");
            editArchive(node.container, node.entry, entry, Collections.emptyMap());
            return archiveNode(node.container, entry, node.directory, node.size, node.modified);
        }
        if (node.uri != null) {
            Uri u = DocumentsContract.renameDocument(context.getContentResolver(), node.uri, name);
            if (u == null) throw new IOException("无法重命名");
            return document(u);
        }
        File to = new File(node.file.getParentFile(), name);
        if (node.root) {
            shell("test ! -e " + quote(to.getAbsolutePath()) + " && mv "
                    + quote(node.path()) + " " + quote(to.getAbsolutePath()), true);
            return local(to, true);
        }
        if (to.exists()) throw new IOException("同名文件已存在");
        Files.move(node.file.toPath(), to.toPath());
        return local(to);
    }
    void delete(Node node) throws IOException {
        checkRemoval(node);
        node = RootAccess.automatic(node);
        if (node.container != null) {
            if (node.entry.isEmpty()) throw new IOException("不能删除归档根目录");
            editArchive(node.container, node.entry, null, Collections.emptyMap());
        } else if (node.uri != null) {
            if (!DocumentsContract.deleteDocument(context.getContentResolver(), node.uri)) throw new IOException("删除失败");
        } else if (node.root) {
            if (node.path().equals("/") || node.path().equals("/storage/emulated/0")) throw new IOException("不能删除存储根目录");
            shell("rm -rf -- " + quote(node.path()), true);
        } else {
            deleteLocal(node.file.toPath());
        }
    }
    void deleteAll(Collection<Node> requested) throws IOException {
        for (Node node : requested) {
            checkRemoval(node);
            if (node.container != null && node.entry.isEmpty()) throw new IOException("不能删除归档根目录");
        }
        Map<String, Node> roots = new LinkedHashMap<>();
        Map<String, List<Node>> archives = new LinkedHashMap<>();
        List<Node> ordinary = new ArrayList<>();
        for (Node original : requested) {
            Node node = RootAccess.automatic(original);
            if (node.container != null) archives.computeIfAbsent(node.container.key(), key -> new ArrayList<>()).add(node);
            else if (node.root) roots.put(node.path(), node);
            else ordinary.add(node);
        }
        String prefix = "/system/bin/toybox rm -rf --";
        StringBuilder command = new StringBuilder(prefix);
        int bytes = ShellRunner.quotedPayloadBytes(prefix);
        for (Node node : roots.values()) {
            String argument = " " + quote(node.path());
            int length = ShellRunner.quotedPayloadBytes(argument);
            if (ShellRunner.quotedPayloadBytes(prefix) + length > 65536) {
                // A single deep path can exceed the wrapper budget despite fitting PATH_MAX.
                File script = File.createTempFile("delete-", ".sh", context.getCacheDir());
                try {
                    Files.write(script.toPath(), (prefix + argument + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    shell("/system/bin/sh " + quote(script.getAbsolutePath()), true);
                } finally { Files.deleteIfExists(script.toPath()); }
                continue;
            }
            // Leave ample space for pid/session wrappers under the kernel's per-argument limit.
            if (bytes + length > 65536 && command.length() > prefix.length()) {
                shell(command.toString(), true); command = new StringBuilder(prefix);
                bytes = ShellRunner.quotedPayloadBytes(prefix);
            }
            command.append(argument); bytes += length;
        }
        if (command.length() > prefix.length()) shell(command.toString(), true);
        for (List<Node> entries : archives.values()) {
            synchronized (ArchiveOps.class) {
                Node source = RootAccess.automatic(entries.get(0).container);
                File archive = materialize(source);
                try {
                    Set<String> removals = new HashSet<>();
                    for (Node entry : entries) removals.add(entry.entry);
                    ArchiveOps.removeAll(archive, removals);
                    if (source.file == null || source.root) write(source, archive);
                } finally { release(source, archive); }
            }
        }
        for (Node node : ordinary) delete(node);
    }
    private static void deleteLocal(java.nio.file.Path path) throws IOException {
        // Walk without following links or enumerating/sorting metadata for display.
        Files.walkFileTree(path, new java.nio.file.SimpleFileVisitor<>() {
            @Override public java.nio.file.FileVisitResult visitFile(java.nio.file.Path file,
                    java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                Files.delete(file); return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override public java.nio.file.FileVisitResult postVisitDirectory(java.nio.file.Path directory,
                    IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(directory); return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }
    Node copy(Node source, Node parent, boolean move) throws IOException {
        if (move) checkRemoval(source);
        source = RootAccess.automatic(source);
        parent = RootAccess.automatic(parent);
        if (source.key().equals(parent.key())) throw new IOException("不能复制到自身");
        if (source.container != null && parent.container != null && source.directory
                && source.container.key().equals(parent.container.key()) && parent.entry.startsWith(source.entry))
            throw new IOException("不能复制到自己的子目录");
        for (Node ancestor = parent; ancestor != null; ancestor = ancestor.container)
            if (source.key().equals(ancestor.key())) throw new IOException("不能将归档放入自身");
        if (parent.container != null && source.directory && source.file != null) {
            Node outer = parent.container;
            while (outer.container != null) outer = outer.container;
            if (outer.file != null && outer.file.getCanonicalPath().startsWith(source.file.getCanonicalPath() + "/"))
                throw new IOException("不能将目录复制到其内部的归档");
        }
        if (source.file != null && parent.file != null && source.directory) {
            String a = source.file.getCanonicalPath(), b = parent.file.getCanonicalPath();
            if (b.equals(a) || b.startsWith(a + "/")) throw new IOException("不能复制到自己的子目录");
        }
        if (source.uri != null && parent.uri != null && source.directory) {
            String a = DocumentsContract.getDocumentId(source.uri);
            String b = DocumentsContract.getDocumentId(parent.uri);
            boolean child = source.uri.getAuthority().equals(parent.uri.getAuthority())
                    && (b.equals(a) || b.startsWith(a + "/"));
            if (Build.VERSION.SDK_INT >= 29 && !child)
                child = DocumentsContract.isChildDocument(context.getContentResolver(), source.uri, parent.uri);
            if (child) throw new IOException("不能复制到自己的子目录");
        }
        String name = unique(parent, source.name);
        if (move && source.file != null && parent.file != null) {
            if (source.file.getParentFile().getCanonicalFile().equals(parent.file.getCanonicalFile())) return source;
            File destination = new File(parent.file, name);
            if (source.root || parent.root) {
                String from = quote(source.path()), to = quote(destination.getAbsolutePath());
                String command = "test ! -e " + to + " && test ! -L " + to
                        + " && /system/bin/toybox mv -n -- " + from + " " + to
                        + " && test ! -e " + from + " && test ! -L " + from;
                ShellRunner.Result result = new ShellRunner().run(command, null, true, 30 * 60 * 1000L, null, false);
                if (result.timedOut() || result.exitCode() != 0)
                    throw new IOException("移动未完成，源或目标可能保留部分内容，请检查后重试\n" + result.output());
                return new Node(name, destination, null, source.directory, parent.root, source.size, source.modified);
            }
            try {
                if (android.system.Os.stat(source.path()).st_dev == android.system.Os.stat(parent.path()).st_dev) {
                    Files.move(source.file.toPath(), destination.toPath());
                    return local(destination);
                }
            } catch (android.system.ErrnoException error) { throw new IOException(error); }
        }
        if (parent.container != null) {
            Map<String, File> additions = new LinkedHashMap<>();
            Map<Node, File> temporary = new LinkedHashMap<>();
            String entry = parent.entry + name + (source.directory ? "/" : "");
            try {
                archiveAdditions(source, entry, additions, temporary, new HashSet<>());
                editArchive(parent.container, null, null, additions);
            } finally {
                for (Map.Entry<Node, File> staged : temporary.entrySet()) release(staged.getKey(), staged.getValue());
            }
            if (move) delete(source);
            return archiveNode(parent.container, entry, source.directory, source.size, source.modified);
        }
        Node made = create(parent, name, source.directory);
        try {
            copyContent(source, made, new HashSet<>());
        } catch (IOException | RuntimeException e) {
            try { delete(made); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
        if (move) delete(source);
        return made;
    }
    private void archiveAdditions(Node source, String entry, Map<String, File> additions, Map<Node, File> staged,
                                  Set<String> seen) throws IOException {
        String key = source.file != null ? source.file.getCanonicalPath() : source.key();
        if (!seen.add(key)) throw new IOException("目录包含循环引用");
        if (source.directory) {
            additions.put(entry, null);
            for (Node child : list(source)) archiveAdditions(child, entry + child.name + (child.directory ? "/" : ""), additions, staged, seen);
        } else {
            File payload = materialize(source); staged.put(source, payload); additions.put(entry, payload);
        }
        seen.remove(key);
    }
    private void copyContent(Node source, Node target, Set<String> seen) throws IOException {
        String key = source.file != null ? source.file.getCanonicalPath() : source.key();
        if (!seen.add(key)) throw new IOException("目录包含循环引用");
        if (source.directory) {
            for (Node n : list(source)) copyContent(n, create(target, n.name, n.directory), seen);
        } else {
            // Targets are newly created; direct streaming needs no second complete cache copy.
            target = RootAccess.automatic(target);
            if (target.root) {
                Process process = RootShell.command("cat > " + quote(target.path())).redirectErrorStream(true).start();
                try (InputStream in = read(source); OutputStream out = process.getOutputStream()) { pump(in, out); }
                catch (IOException error) { process.destroyForcibly(); throw error; }
                await(process);
            } else try (InputStream in = read(source);
                        OutputStream out = target.uri != null
                                ? context.getContentResolver().openOutputStream(target.uri, "wt")
                                : new BufferedOutputStream(new FileOutputStream(target.file))) {
                if (out == null) throw new IOException("目标目录只读");
                pump(in, out);
            }
        }
        seen.remove(key);
    }
    Node extract(List<Node> sources, Node parent, String folderName) throws IOException {
        if (sources.isEmpty()) throw new IOException("请选择压缩包或包内条目");
        checkName(folderName);
        if (parent.container != null) throw new IOException("请选择压缩包以外的解压目录");
        parent = RootAccess.automatic(parent);
        Node output = create(parent, unique(parent, folderName), true);
        File staged = null;
        try {
            if (sources.size() == 1 && sources.get(0).container == null) {
                Node source = sources.get(0);
                File archive = materialize(source);
                try {
                    if (output.file != null && !output.root) ArchiveOps.extract(archive, "", output.file);
                    else {
                        staged = Files.createTempDirectory(context.getCacheDir().toPath(), "extract-").toFile();
                        ArchiveOps.extract(archive, "", staged);
                        for (Node child : list(local(staged))) copy(child, output, false);
                    }
                } finally { release(source, archive); }
            } else {
                for (Node source : sources) {
                    if (source.container == null) throw new IOException("请选择同一压缩包内的条目");
                    // The archive index validates paths; selected entries use the same bounded extractor.
                    File archive = materialize(source.container);
                    try {
                        if (staged == null) staged = Files.createTempDirectory(context.getCacheDir().toPath(), "extract-").toFile();
                        ArchiveOps.extract(archive, source.entry, staged, true);
                    } finally { release(source.container, archive); }
                }
                for (Node child : list(local(staged))) copy(child, output, false);
            }
            return output;
        } catch (IOException | RuntimeException error) {
            try { delete(output); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        } finally { if (staged != null) delete(local(staged)); }
    }
    String unique(Node parent, String original) throws IOException {
        Set<String> names = new HashSet<>();
        for (Node n : list(parent)) names.add(n.name);
        if (!names.contains(original)) return original;
        int dot = original.lastIndexOf('.');
        String base = dot > 0 ? original.substring(0, dot) : original;
        String ext = dot > 0 ? original.substring(dot) : "";
        for (int i = 1;; i++) {
            String n = base + " (" + i + ")" + ext;
            if (!names.contains(n)) return n;
        }
    }
    File materialize(Node n) throws IOException {
        n = RootAccess.automatic(n);
        if (n.file != null && !n.root) return n.file;
        File f = File.createTempFile("import-", "-" + n.name.replaceAll("[^a-zA-Z0-9.]", "_"), context.getCacheDir());
        try (InputStream in = read(n); OutputStream out = new FileOutputStream(f)) { pump(in, out); }
        catch (IOException | RuntimeException e) { Files.deleteIfExists(f.toPath()); throw e; }
        return f;
    }
    void release(Node source, File file) throws IOException {
        if (source.file == null || !source.file.equals(file)) Files.deleteIfExists(file.toPath());
    }
    private static void checkRemoval(Node node) throws IOException {
        if (node.file != null && RootAccess.protectedStorageDirectory(node.file))
            throw new IOException("不能删除、移动或重命名存储根目录及 Android/data、Android/obb");
    }
    private void editArchive(Node source, String remove, String rename, Map<String, File> additions) throws IOException {
        synchronized (ArchiveOps.class) {
            source = RootAccess.automatic(source);
            File archive = materialize(source);
            try {
                ArchiveOps.rewrite(archive, remove, rename, additions);
                if (source.file == null || source.root) write(source, archive);
            } finally { release(source, archive); }
        }
    }
    static void pump(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[128 * 1024];
        int n;
        while ((n = in.read(buffer)) != -1) if (n > 0) out.write(buffer, 0, n);
        out.flush();
    }
    static byte[] readBytes(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(limit, 128 * 1024));
        byte[] buffer = new byte[Math.min(128 * 1024, Math.max(1, limit))];
        int total = 0;
        while (total < limit) {
            int n = in.read(buffer, 0, Math.min(buffer.length, limit - total));
            if (n < 0) break;
            if (n == 0) continue;
            out.write(buffer, 0, n); total += n;
        }
        return out.toByteArray();
    }
    static void skip(InputStream in, long count) throws IOException {
        long left = count;
        while (left > 0) {
            long skipped = in.skip(left);
            if (skipped > 0) { left -= skipped; continue; }
            if (in.read() < 0) throw new EOFException("文件已到末尾");
            left--;
        }
    }
    static void replace(File tmp, File to) throws IOException {
        try { Files.move(tmp.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
    static void checkName(String name) throws IOException {
        if (name.trim().isEmpty() || name.equals(".") || name.equals("..") ||
                name.contains("/") || name.contains("\\") || name.indexOf('\0') >= 0)
            throw new IOException("文件名无效");
    }
    static String quote(String s) { return "'" + s.replace("'", "'\\''") + "'"; }
    static String rootWriteCommand(String path, String temporary) {
        String source = quote(path), tmp = quote(temporary);
        return "cat > " + tmp + " && chmod \"$(stat -c %a " + source + ")\" " + tmp
                + " && chown \"$(stat -c %u:%g " + source + ")\" " + tmp
                + " && mv " + tmp + " " + source + " || { rm -f " + tmp + "; exit 1; }";
    }
    static String shell(String command, boolean root) throws IOException {
        ShellRunner.Result result = new ShellRunner().run(command, null, root, 45000, null, false);
        if (result.timedOut()) throw new IOException("命令超过 45 秒，已停止");
        if (result.exitCode() != 0) throw new IOException("exit " + result.exitCode() + "\n" + result.output());
        return result.output();
    }
    private static void await(Process p) throws IOException {
        try {
            if (!p.waitFor(30, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new IOException("ROOT 写入超时"); }
            if (p.exitValue() != 0) throw new IOException("ROOT 写入失败");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
    }
    static String size(long n) {
        if (n < 0) return "?";
        if (n < 1024) return n + " B";
        if (n < 1048576) return String.format(Locale.US, "%.1f K", n / 1024.0);
        if (n < 1073741824L) return String.format(Locale.US, "%.1f M", n / 1048576.0);
        return String.format(Locale.US, "%.1f G", n / 1073741824.0);
    }
}
