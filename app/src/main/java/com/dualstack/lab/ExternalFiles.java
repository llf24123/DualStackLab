package com.dualstack.lab;

import android.content.*;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.system.*;
import java.io.*;
import java.util.*;

/** Receives granted file data without interpreting sender extras as internal file or ROOT handles. */
final class ExternalFiles {
    record Received(FileStore.Node node, File directory) {}
    static Uri uri(Intent intent) {
        if (Intent.ACTION_VIEW.equals(intent.getAction())) return intent.getData();
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Object stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream instanceof Uri uri) return uri;
            ClipData clip = intent.getClipData();
            if (clip != null && clip.getItemCount() > 0) return clip.getItemAt(0).getUri();
        }
        return null;
    }
    static Received receive(Context context, Uri uri, int flags) throws IOException {
        if ("file".equals(uri.getScheme())) {
            File file = sharedFile(context, new File(Objects.requireNonNull(uri.getPath())));
            if (file == null) throw new IOException("来源路径不可直接访问，请通过文件提供方分享");
            return new Received(FileStore.local(file), file.getParentFile());
        }
        if (!"content".equals(uri.getScheme())) throw new IOException("仅支持本地文件和已授权内容文件");
        ContentResolver resolver = context.getContentResolver();
        // The granted descriptor proves both access and file identity; provider hints alone do not.
        try (AssetFileDescriptor descriptor = resolver.openAssetFileDescriptor(uri, "r")) {
            if (descriptor == null) throw new IOException("来源没有提供可读取文件");
            if ((flags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) {
                try { resolver.takePersistableUriPermission(uri, flags & Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                catch (SecurityException ignored) { }
            }
            File resolved = null;
            FileStore.Node rootFile = null;
            try {
                StructStat opened = Os.fstat(descriptor.getFileDescriptor());
                boolean wholeFile = OsConstants.S_ISREG(opened.st_mode) && descriptor.getStartOffset() == 0
                        && (descriptor.getDeclaredLength() < 0 || descriptor.getDeclaredLength() == opened.st_size);
                if (wholeFile) {
                    String path = Os.readlink("/proc/self/fd/" + descriptor.getParcelFileDescriptor().getFd());
                    if (path.startsWith("/") && !path.endsWith(" (deleted)")) {
                        resolved = sameFile(context, new File(path), opened);
                        if (resolved == null) rootFile = descriptorRootFile(new File(path), opened);
                    }
                    if (resolved == null) resolved = sameFile(context, resolveSharedPath(context, uri), opened);
                }
            } catch (ErrnoException ignored) { }
            if (resolved != null) return new Received(FileStore.local(resolved), resolved.getParentFile());
            if (rootFile != null) return new Received(rootFile, rootFile.file.getParentFile());
            // Pipes, cloud documents and private provider files retain their granted URI.
            return new Received(new FileStore(context).document(uri), null);
        } catch (SecurityException e) { throw new IOException("来源未授予文件读取权限", e); }
    }
    private static FileStore.Node descriptorRootFile(File file, StructStat opened) {
        // Only a kernel-resolved descriptor path can request ROOT; sender-supplied path hints cannot.
        try {
            FileStore.Node parent = RootAccess.directory(file.getParentFile(), true);
            if (!parent.root) return null;
            String value = FileStore.shell("/system/bin/toybox stat -L -c '%d|%i|%f' -- "
                    + FileStore.quote(file.getAbsolutePath()), true).trim();
            String[] fields = value.split("\\|");
            if (fields.length != 3 || Long.parseUnsignedLong(fields[0]) != opened.st_dev
                    || Long.parseUnsignedLong(fields[1]) != opened.st_ino
                    || !OsConstants.S_ISREG(Integer.parseInt(fields[2], 16))) return null;
            // Confirm this directory can actually be browsed in the privileged mount namespace.
            FileStore.shell("test -d " + FileStore.quote(file.getParent()) + " && test -r "
                    + FileStore.quote(file.getParent()) + " && test -x " + FileStore.quote(file.getParent()), true);
            return new FileStore.Node(file.getName(), file, null, false, true, opened.st_size, opened.st_mtime * 1000);
        } catch (IOException | RuntimeException ignored) { return null; }
    }
    private static File sameFile(Context context, File candidate, StructStat opened) {
        if (candidate == null) return null;
        String path = candidate.getAbsolutePath().replaceFirst("^/mnt/runtime/(default|read|write|full)/emulated/", "/storage/emulated/")
                .replaceFirst("^/mnt/user/\\d+/emulated/", "/storage/emulated/");
        for (File file : List.of(candidate, new File(path))) {
            try {
                File accessible = sharedFile(context, file);
                if (accessible == null) continue;
                StructStat actual = Os.stat(accessible.getPath());
                if (actual.st_dev == opened.st_dev && actual.st_ino == opened.st_ino) return accessible;
            } catch (IOException | ErrnoException ignored) { }
        }
        return null;
    }
    static File resolveSharedPath(Context context, Uri uri) {
        String authority = uri.getAuthority();
        try {
            if ("com.android.externalstorage.documents".equals(authority) && DocumentsContract.isDocumentUri(context, uri)) {
                String id = DocumentsContract.getDocumentId(uri); int colon = id.indexOf(':');
                if (colon > 0) {
                    String volume = id.substring(0, colon), relative = id.substring(colon + 1);
                    if (relative.startsWith("/")) return null;
                    File root = volume.equalsIgnoreCase("primary") ? Environment.getExternalStorageDirectory() : new File("/storage", volume);
                    File path = new File(root, relative).getCanonicalFile();
                    if (!within(path, root.getCanonicalFile())) return null;
                    return sharedFile(context, path);
                }
            }
            if ("com.android.providers.downloads.documents".equals(authority) && DocumentsContract.isDocumentUri(context, uri)) {
                String id = DocumentsContract.getDocumentId(uri);
                if (id.startsWith("raw:")) return sharedFile(context, new File(id.substring(4)));
            }
            // Legacy DATA is queried only from system media/downloads providers, never arbitrary senders.
            if (Set.of("media", "downloads", "com.android.providers.media.documents", "com.android.providers.downloads.documents").contains(authority)) {
                try (Cursor cursor = context.getContentResolver().query(uri, new String[]{"_data"}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return sharedFile(context, new File(cursor.getString(0)));
                }
            }
        } catch (IOException | RuntimeException ignored) { }
        return null;
    }
    private static boolean within(File file, File root) {
        return file.toPath().startsWith(root.toPath());
    }
    private static File sharedFile(Context context, File file) throws IOException {
        File canonical = file.getCanonicalFile();
        if (!canonical.isFile() || !canonical.canRead() || canonical.getParentFile().list() == null) return null;
        boolean allowed = within(canonical, Environment.getExternalStorageDirectory().getCanonicalFile());
        allowed |= within(canonical, context.getDataDir().getCanonicalFile());
        for (File folder : context.getExternalFilesDirs(null)) {
            if (folder == null) continue;
            String path = folder.getCanonicalPath(); int boundary = path.indexOf("/Android/data/");
            if (boundary > 0 && within(canonical, new File(path.substring(0, boundary)))) allowed = true;
        }
        return allowed ? canonical : null;
    }
}
