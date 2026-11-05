package com.dualstack.lab;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

final class DirectoryStats {
    record Result(long bytes, long files, long directories, long unknownSizeFiles) { }
    private record Pending(FileStore.Node node, int depth) { }

    static Result measure(FileStore store, FileStore.Node root, AtomicBoolean cancelled) throws IOException {
        ArrayDeque<Pending> pending = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        pending.add(new Pending(root, 0));
        long bytes = 0, files = 0, directories = 0, unknownSizeFiles = 0, examined = 0;
        while (!pending.isEmpty()) {
            if (cancelled.get()) throw new IOException("已停止目录统计");
            Pending item = pending.removeLast();
            FileStore.Node node = item.node();
            if (++examined > 1_000_000) throw new IOException("目录项目超过 100 万项，已停止统计");
            if (node.file != null && java.nio.file.Files.isSymbolicLink(node.file.toPath())) continue;
            if (!node.directory) {
                bytes = saturatedAdd(bytes, Math.max(0, node.size));
                if (node.size < 0) unknownSizeFiles++;
                files++; continue;
            }
            if (item.depth() >= 128) throw new IOException("目录嵌套超过 128 层，已停止统计");
            String identity = node.file == null ? node.key() : node.file.getAbsolutePath();
            if (!visited.add(identity)) continue;
            directories++;
            // ROOT lstat skips symlinks too, even where the app cannot inspect them itself.
            for (FileStore.Node child : store.listForStats(node)) pending.add(new Pending(child, item.depth() + 1));
        }
        return new Result(bytes, files, directories, unknownSizeFiles);
    }

    private static long saturatedAdd(long a, long b) {
        if (Long.MAX_VALUE - a < b) return Long.MAX_VALUE;
        return a + b;
    }
    private DirectoryStats() { }
}
