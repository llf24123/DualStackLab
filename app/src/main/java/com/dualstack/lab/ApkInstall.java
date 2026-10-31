package com.dualstack.lab;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.core.content.FileProvider;
import java.io.*;
import java.nio.file.Files;
import java.util.Locale;

final class ApkInstall {
    static boolean isApkName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".apk") || lower.endsWith(".apk.1");
    }
    static File stage(Context context, FileStore.Node node) throws IOException {
        return stageShared(context, node, ".apk");
    }
    static File stageShared(Context context, FileStore.Node node, String suffix) throws IOException {
        File folder = new File(context.getCacheDir(), "shared");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("无法创建安装缓存");
        File[] old = folder.listFiles();
        if (old != null) for (File item : old)
            if (System.currentTimeMillis() - item.lastModified() > 86400000) Files.deleteIfExists(item.toPath());
        File apk = File.createTempFile("shared-", suffix, folder);
        try (InputStream in = new FileStore(context).read(node); OutputStream out = new FileOutputStream(apk)) {
            FileStore.pump(in, out);
        } catch (IOException e) { Files.deleteIfExists(apk.toPath()); throw e; }
        return apk;
    }
    static Intent normalIntent(Context context, File file) {
        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".files", file);
        Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        java.util.List<android.content.pm.ResolveInfo> installers = context.getPackageManager()
                .queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_SYSTEM_ONLY);
        if (!installers.isEmpty()) {
            android.content.pm.ActivityInfo activity = installers.get(0).activityInfo;
            intent.setComponent(new android.content.ComponentName(activity.packageName, activity.name));
        }
        return intent;
    }
    static String silentCommand(File file) {
        // su reads the private file; Package Manager receives bytes rather than a private path.
        return "/system/bin/pm install -r -S " + file.length() + " < " + FileStore.quote(file.getAbsolutePath());
    }
    static String silent(File file) throws IOException {
        ShellRunner.Result result = new ShellRunner().run(silentCommand(file), null, true, 120000, null, false);
        if (result.timedOut()) throw new IOException("安装超过 120 秒，已停止");
        if (result.exitCode() != 0 || !result.output().trim().matches("(?s)(?:.*\\n)?Success"))
            throw new IOException("静默安装失败（需 ROOT 授权）\nexit " + result.exitCode() + "\n" + result.output());
        return "安装成功";
    }
    private ApkInstall() {}
}
