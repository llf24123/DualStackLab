package com.dualstack.lab;

import android.os.Environment;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.attribute.BasicFileAttributes;

final class StorageNavigation {
    /** Resolves typed paths before changing the pane, falling back through missing parents. */
    static FileStore.Node jump(File input) throws IOException {
        File path = input.toPath().toAbsolutePath().normalize().toFile();
        if (RootAccess.available())
            return location(FileStore.directory(new File(RootDirectoryReader.nearestDirectory(path.getPath())), true));
        while (path != null) {
            try {
                if (Files.readAttributes(path.toPath(), BasicFileAttributes.class).isDirectory())
                    return location(FileStore.directory(path, false));
            } catch (NoSuchFileException | NotDirectoryException missing) {
                // Permission failures are surfaced instead of changing to an unrelated directory.
            }
            path = path.getParentFile();
        }
        throw new IOException("找不到可进入的上级目录");
    }

    static FileStore.Node location(FileStore.Node node) {
        if (node == null || node.file == null) return node;
        String path = normalized(node.file);
        if (storageHome(path))
            return FileStore.directory(Environment.getExternalStorageDirectory(), node.root);
        return node;
    }

    static FileStore.Node parent(FileStore store, FileStore.Node node) {
        return location(store.parent(location(node)));
    }

    /** Paths whose metadata the media provider owns; writes must run with the app's own identity there. */
    static boolean ordinaryStorage(File path) {
        String value = normalized(path);
        return value.startsWith("/storage/emulated/") || value.startsWith("/storage/self/primary")
                || value.startsWith("/mnt/user/") || value.equals("/sdcard") || value.startsWith("/sdcard/");
    }

    /** The app owns normal shared-storage writes; virtual ownership is supplied by the mounted filesystem. */
    static boolean appManagedStorage(File path) {
        if (!ordinaryStorage(path) || !path.canRead() || !path.canWrite()) return false;
        try {
            File canonical = path.getCanonicalFile();
            // Android's Java provider does not implement Files.getFileStore. Ask the storage
            // service about the resolved volume instead of inferring ownership from a prefix.
            return Environment.isExternalStorageEmulated(canonical);
        } catch (IOException | IllegalArgumentException | UnsupportedOperationException | SecurityException error) {
            return false;
        }
    }

    static boolean storageContainer(File path) {
        String value = normalized(path);
        return value.equals("/storage") || value.equals("/storage/emulated");
    }

    static FileStore.Node knownStorageChild(File path) {
        return FileStore.directory(normalized(path).equals("/storage")
                ? new File("/storage/emulated") : Environment.getExternalStorageDirectory(), false);
    }

    private static boolean storageHome(String path) {
        return path.equals(normalized(Environment.getExternalStorageDirectory()))
                || path.equals("/sdcard") || path.equals("/storage/self/primary");
    }

    private static String normalized(File file) {
        return file.toPath().toAbsolutePath().normalize().toString();
    }

    private StorageNavigation() {}
}
