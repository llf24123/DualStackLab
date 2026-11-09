package com.dualstack.lab;

import android.os.SystemClock;
import java.io.File;
import java.io.IOException;

/** File panes operate with ROOT whenever the device grants it; unrooted devices fall back silently. */
final class RootAccess {
    private static volatile long grantedUntil;
    private static volatile long checkedAt = -1;
    private static volatile boolean available;

    /** Whether su currently returns UID 0. Cached briefly so navigation stays cheap. */
    static boolean available() {
        long now = SystemClock.elapsedRealtime();
        if (checkedAt >= 0 && now - checkedAt < 30000) return available;
        synchronized (RootAccess.class) {
            if (checkedAt >= 0 && now - checkedAt < 30000) return available;
            boolean granted = false;
            try {
                granted = FileStore.shell("/system/bin/toybox id -u", true).trim().equals("0");
            } catch (IOException ignored) { }
            available = granted;
            checkedAt = SystemClock.elapsedRealtime();
            if (granted) grantedUntil = checkedAt + 30000;
            return granted;
        }
    }

    /** The last probe verdict without spawning su; safe to call from the UI thread. */
    static boolean known() {
        return checkedAt >= 0 && available;
    }

    /** Filesystem directories open as ROOT by default; only unreadable paths stop the probe early. */
    static FileStore.Node directory(File path, boolean preferRoot) throws IOException {
        if (!preferRoot && !available()) {
            if (StorageNavigation.storageContainer(path) || readable(path)) return FileStore.directory(path, false);
        }
        try {
            if (SystemClock.elapsedRealtime() >= grantedUntil) {
                synchronized (RootAccess.class) {
                    if (SystemClock.elapsedRealtime() >= grantedUntil) {
                        if (!FileStore.shell("/system/bin/toybox id -u", true).trim().equals("0"))
                            throw new IOException("su 未返回 ROOT 身份");
                        grantedUntil = SystemClock.elapsedRealtime() + 30000;
                        available = true; checkedAt = SystemClock.elapsedRealtime();
                    }
                }
            }
            return FileStore.directory(path, true);
        } catch (IOException error) {
            available = false; checkedAt = SystemClock.elapsedRealtime();
            if (path.getAbsolutePath().equals("/") || StorageNavigation.storageContainer(path) || readable(path))
                return FileStore.directory(path, false);
            throw new IOException("无法读取 " + path + "\n" + error.getMessage(), error);
        }
    }

    /** File panes run as ROOT by default, so every filesystem node carries the elevated handle. */
    static FileStore.Node automatic(FileStore.Node node) throws IOException {
        if (node.file == null || node.root) return node;
        if (available()) return elevated(node);
        if (!restrictedStorage(node.file)) return node;
        FileStore.Node access = directory(node.directory ? node.file : node.file.getParentFile(), false);
        return !access.root ? node : elevated(node);
    }

    static FileStore.Node cachedAccess(FileStore.Node node) {
        if (node.file == null || node.root || !known()) return node;
        return elevated(node);
    }

    /** Drops the cached su verdict so the next navigation probes again. */
    static void reset() {
        synchronized (RootAccess.class) { checkedAt = -1; available = false; grantedUntil = 0; }
    }

    private static FileStore.Node elevated(FileStore.Node node) {
        FileStore.Node elevated = new FileStore.Node(node.name, node.file, null, node.directory, true,
                node.size, node.modified);
        elevated.identity = node.identity; elevated.parentIdentity = node.parentIdentity;
        elevated.directoryIdentity = node.directoryIdentity; elevated.identityFollowsLinks = node.identityFollowsLinks;
        return elevated;
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
