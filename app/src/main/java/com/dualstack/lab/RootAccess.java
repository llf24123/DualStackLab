package com.dualstack.lab;

import java.io.File;
import java.io.IOException;

final class RootAccess {
    private static volatile long grantedUntil;
    static FileStore.Node directory(File path, boolean preferRoot) throws IOException {
        if (!preferRoot && !restrictedStorage(path) && readable(path)) return FileStore.directory(path, false);
        try {
            if (android.os.SystemClock.elapsedRealtime() >= grantedUntil) {
                synchronized (RootAccess.class) {
                    if (android.os.SystemClock.elapsedRealtime() >= grantedUntil) {
                        if (!FileStore.shell("/system/bin/toybox id -u", true).trim().equals("0"))
                            throw new IOException("su 未返回 ROOT 身份");
                        grantedUntil = android.os.SystemClock.elapsedRealtime() + 30000;
                    }
                }
            }
            return FileStore.directory(path, true);
        } catch (IOException error) {
            if (path.getAbsolutePath().equals("/") || StorageNavigation.storageContainer(path) || readable(path))
                return FileStore.directory(path, false);
            throw new IOException("无法读取 " + path + "\n" + error.getMessage(), error);
        }
    }
    static FileStore.Node automatic(FileStore.Node node) throws IOException {
        if (node.file == null || node.root || !restrictedStorage(node.file)) return node;
        FileStore.Node access = directory(node.directory ? node.file : node.file.getParentFile(), false);
        return !access.root ? node : new FileStore.Node(node.name, node.file, null, node.directory, true,
                node.size, node.modified);
    }
    static FileStore.Node cachedAccess(FileStore.Node node) {
        if (node.file == null || node.root || android.os.SystemClock.elapsedRealtime() >= grantedUntil
                || !restrictedStorage(node.file)) return node;
        return new FileStore.Node(node.name, node.file, null, node.directory, true, node.size, node.modified);
    }
    static boolean protectedStorageDirectory(File path) {
        String normalized = path.toPath().toAbsolutePath().normalize().toString();
        return normalized.equals("/") || StorageNavigation.storageContainer(path) || privateDataContainer(normalized) || normalized.matches(
                "(?:/storage/emulated/\\d+|/sdcard|/storage/self/primary|/mnt/user/\\d+/primary)"
                        + "(?:/Android/(?:data|obb))?");
    }
    private static boolean restrictedStorage(File path) {
        String normalized = path.toPath().toAbsolutePath().normalize().toString();
        return StorageNavigation.storageContainer(path) || normalized.matches("(?:/storage/emulated/\\d+|/sdcard|/storage/self/primary|/mnt/user/\\d+/primary)"
                + "/Android/(?:data|obb)(?:/.*)?") || privateDataContainer(normalized)
                || (normalized.matches("(?:/data/data|/data/user(?:_de)?/\\d+"
                        + "|/mnt/expand/[^/]+/user(?:_de)?/\\d+)(?:/.*)?") && !path.canRead());
    }
    private static boolean privateDataContainer(String path) {
        return path.equals("/data") || path.equals("/data/data")
                || path.matches("/data/user(?:_de)?(?:/\\d+)?")
                || path.matches("/mnt/expand/[^/]+/user(?:_de)?(?:/\\d+)?");
    }
    private static boolean readable(File path) {
        try (java.nio.file.DirectoryStream<java.nio.file.Path> stream = java.nio.file.Files.newDirectoryStream(path.toPath())) {
            stream.iterator().hasNext(); return true;
        } catch (IOException | java.nio.file.DirectoryIteratorException | SecurityException ignored) { return false; }
    }
    private RootAccess() {}
}
