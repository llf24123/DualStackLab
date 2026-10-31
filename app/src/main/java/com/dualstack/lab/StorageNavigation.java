package com.dualstack.lab;

import android.os.Environment;
import java.io.File;

final class StorageNavigation {
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
