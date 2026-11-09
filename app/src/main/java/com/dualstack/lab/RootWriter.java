package com.dualstack.lab;

import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.*;
import java.util.Objects;

public final class RootWriter {
    public static void main(String[] args) {
        try {
            if (args.length != 4) throw new IOException("写入参数不完整");
            String identity = write(new File(args[0]), new File(args[1]), Long.parseLong(args[2]), args[3]);
            System.out.println(identity);
        } catch (Exception error) {
            System.err.println("安全写入失败：" + error.getMessage());
            System.exit(1);
        }
    }
    private static String write(File target, File temporary, long size, String expected) throws Exception {
        if (!Objects.equals(target.getParent(), temporary.getParent())) throw new IOException("临时文件必须位于目标同目录");
        FileDescriptor rawParent = Os.open(target.getParent(), OsConstants.O_RDONLY, 0);
        ParcelFileDescriptor parent;
        try { parent = ParcelFileDescriptor.dup(rawParent); } finally { Os.close(rawParent); }
        try (ParcelFileDescriptor directory = parent) {
            if (!OsConstants.S_ISDIR(Os.fstat(directory.getFileDescriptor()).st_mode)) throw new IOException("目标父路径不是目录");
            String pinnedParent = "/proc/self/fd/" + directory.getFd() + "/";
            String destination = pinnedParent + target.getName(), staging = pinnedParent + temporary.getName();
            String before = fingerprint(destination);
            if (!expected.isEmpty() && !expected.equals(before)) throw new IOException("原文件已在外部改变，请重新打开");
            StructStat original = Os.lstat(destination);
            String owned = null;
            boolean published = false;
            try {
                FileDescriptor descriptor = Os.open(staging,
                        OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_EXCL | OsConstants.O_NOFOLLOW, 0600);
                try (FileOutputStream output = new FileOutputStream(descriptor)) {
                    owned = identity(Os.fstat(descriptor));
                    FileStore.pump(System.in, output);
                    StructStat staged = Os.fstat(descriptor);
                    if (size >= 0 && staged.st_size != size) throw new IOException("写入内容长度不完整");
                    if (staged.st_uid != original.st_uid || staged.st_gid != original.st_gid)
                        Os.fchown(descriptor, original.st_uid, original.st_gid);
                    Os.fchmod(descriptor, original.st_mode & 07777);
                    Os.fsync(descriptor);
                    StructStat ready = Os.fstat(descriptor);
                    if (ready.st_uid != original.st_uid || ready.st_gid != original.st_gid
                            || (ready.st_mode & 07777) != (original.st_mode & 07777))
                        throw new IOException("无法保留原文件权限");
                    if (!before.equals(fingerprint(destination))) throw new IOException("原文件已在外部改变，请重新打开");
                    requireOwned(staging, owned);
                    // rename(2) replaces the final directory entry instead of following a destination symlink.
                    Os.rename(staging, destination);
                    published = true;
                    requireOwned(destination, owned);
                    return owned;
                }
            } finally {
                if (!published && owned != null) {
                    try {
                        StructStat current = Os.lstat(staging);
                        if (OsConstants.S_ISREG(current.st_mode) && owned.equals(identity(current))) Os.remove(staging);
                    } catch (ErrnoException missing) {
                        if (missing.errno != OsConstants.ENOENT) throw missing;
                    }
                }
            }
        }
    }
    private static void requireOwned(String path, String owned) throws Exception {
        StructStat current = Os.lstat(path);
        if (!OsConstants.S_ISREG(current.st_mode) || !owned.equals(identity(current)))
            throw new IOException("文件目录项已被其他操作替换");
    }
    private static String fingerprint(String path) throws Exception {
        FileDescriptor descriptor = Os.open(path, OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW, 0);
        try (FileInputStream input = new FileInputStream(descriptor)) {
            StructStat before = Os.fstat(descriptor);
            if (!OsConstants.S_ISREG(before.st_mode)) throw new IOException("保存目标不是普通文件");
            String digest = FileStore.digest(input);
            if (!FileStore.statIdentity(before).equals(FileStore.statIdentity(Os.fstat(descriptor))))
                throw new IOException("读取期间原文件已变化");
            return FileStore.statIdentity(before) + ":" + digest;
        }
    }
    private static String identity(StructStat stat) { return stat.st_dev + ":" + stat.st_ino; }
    private RootWriter() {}
}
