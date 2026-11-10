package com.dualstack.lab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.content.pm.*;
import android.os.Environment;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import com.android.apksig.ApkVerifier;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.*;

final class ApkDialog {
    static void show(Activity activity, FileStore.Node node, Runnable browse, Runnable tools,
                     Runnable install, Runnable silent, Tasks.Done<File> jump) {
        LinearLayout body = Ui.column(activity);
        body.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 12), Ui.dp(activity, 16), Ui.dp(activity, 8));
        LinearLayout heading = Ui.row(activity);
        ImageView icon = Ui.image(activity, R.drawable.ic_package, Ui.ACCENT);
        heading.addView(icon, new LinearLayout.LayoutParams(Ui.dp(activity, 44), Ui.dp(activity, 44)));
        LinearLayout titles = Ui.column(activity);
        TextView name = Ui.text(activity, node.name, 20, Ui.TEXT);
        TextView version = Ui.text(activity, "读取中…", 12, Ui.MUTED);
        name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END);
        version.setMinHeight(Ui.dp(activity, 20));
        titles.addView(name); titles.addView(version);
        name.setOnLongClickListener(v -> { copy(activity, name.getText().toString()); return true; });
        version.setOnLongClickListener(v -> { copy(activity, version.getText().toString()); return true; });
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1); titleParams.leftMargin = Ui.dp(activity, 12);
        heading.addView(titles, titleParams); body.addView(heading);
        View divider = Ui.line(activity);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-1, Ui.dp(activity, 1));
        dividerParams.topMargin = Ui.dp(activity, 10); dividerParams.bottomMargin = Ui.dp(activity, 6);
        body.addView(divider, dividerParams);
        Map<String, TextView> fields = new LinkedHashMap<>();
        ScrollView scroll = new ScrollView(activity); scroll.addView(body);
        AlertDialog dialog = new AlertDialog.Builder(activity).setView(scroll)
                .setNeutralButton("功能", null).setNegativeButton("查看", (d, w) -> browse.run())
                .setPositiveButton("安装", null).create();
        dialog.setOnShowListener(v -> {
            dialog.getButton(-1).setEnabled(false);
            dialog.getButton(-1).setOnClickListener(x -> { dialog.dismiss(); silent.run(); });
            dialog.getButton(-3).setOnClickListener(x -> new AlertDialog.Builder(activity).setTitle("APK 功能")
                    .setItems(new String[]{"APK 编辑工具", "复制全部信息"}, (d, which) -> {
                        if (which == 0) { dialog.dismiss(); tools.run(); }
                        else {
                            StringBuilder text = new StringBuilder(name.getText() + "\n" + version.getText());
                            fields.forEach((key, value) -> text.append('\n').append(key).append(": ").append(value.getText()));
                            copy(activity, text.toString());
                        }
                    }).show());
        });
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) window.setLayout(Math.min(Ui.dp(activity, 440), Math.round(activity.getResources().getDisplayMetrics().widthPixels * .9f)), -2);
        Tasks.run(activity, () -> {
            File file = ApkInstall.stage(activity, node);
            try {
                PackageInfo info = activity.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), 0);
                if (info == null || info.applicationInfo == null) throw new IOException("文件不是有效 APK");
                info.applicationInfo.sourceDir = file.getAbsolutePath(); info.applicationInfo.publicSourceDir = file.getAbsolutePath();
                return new Info(file, info, info.applicationInfo.loadLabel(activity.getPackageManager()).toString(),
                        info.applicationInfo.loadIcon(activity.getPackageManager()));
            } catch (Exception e) { java.nio.file.Files.deleteIfExists(file.toPath()); throw e; }
        }, result -> {
            if (!dialog.isShowing()) { result.file.delete(); return; }
            PackageInfo info = result.info; ApplicationInfo app = info.applicationInfo;
            name.setText(result.label); version.setText(info.versionName); icon.setImageTintList(null); icon.setImageDrawable(result.icon);
            field(activity, body, fields, "包名", info.packageName, null);
            field(activity, body, fields, "版本号", Long.toString(code(info)), null);
            field(activity, body, fields, "安装包大小", FileStore.size(result.file.length()), null);
            field(activity, body, fields, "签名状态", "检查中…", null);
            field(activity, body, fields, "加固状态", "检查中…", null);
            field(activity, body, fields, "目标系统", system(app.targetSdkVersion), null);
            field(activity, body, fields, "最低系统", system(app.minSdkVersion), null);
            PackageInfo installed = null;
            try { installed = activity.getPackageManager().getPackageInfo(info.packageName, 0); }
            catch (PackageManager.NameNotFoundException ignored) { }
            field(activity, body, fields, "已安装", installed == null ? "未安装" : installed.versionName + " (" + code(installed) + ")", null);
            if (installed != null && installed.applicationInfo != null) {
                ApplicationInfo existing = installed.applicationInfo;
                String external = new File(Environment.getExternalStorageDirectory(), "Android/data/" + info.packageName).getAbsolutePath();
                field(activity, body, fields, "数据目录 1", existing.dataDir, () -> { dialog.dismiss(); jump.accept(new File(existing.dataDir)); });
                field(activity, body, fields, "数据目录 2", external, () -> { dialog.dismiss(); jump.accept(new File(external)); });
                field(activity, body, fields, "APK 路径", existing.sourceDir, () -> { dialog.dismiss(); jump.accept(new File(existing.sourceDir).getParentFile()); });
                SimpleDateFormat date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
                field(activity, body, fields, "首次安装", date.format(new Date(installed.firstInstallTime)), null);
                field(activity, body, fields, "最后更新", date.format(new Date(installed.lastUpdateTime)), null);
                field(activity, body, fields, "UID", Integer.toString(existing.uid), null);
            }
            dialog.getButton(-1).setEnabled(true);
            Tasks.run(activity, () -> inspect(result.file, app.minSdkVersion), details -> {
                if (dialog.isShowing()) { fields.get("签名状态").setText(details[0]); fields.get("加固状态").setText(details[1]); }
            }, error -> { if (dialog.isShowing()) fields.get("签名状态").setText("检查失败"); });
        }, error -> { version.setText(error.getMessage()); dialog.getButton(-1).setEnabled(false); });
    }
    private record Info(File file, PackageInfo info, String label, android.graphics.drawable.Drawable icon) {}
    private static long code(PackageInfo info) { return android.os.Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode; }
    static String[] inspect(File file, int min) throws Exception {
        if (ArchiveOps.isRar(file)) throw new IOException("文件是 RAR 归档，不是 APK");
        ApkVerifier.Result verified = new ApkVerifier.Builder(file).setMinCheckedPlatformVersion(Math.max(26, min)).build().verify();
        List<String> schemes = new ArrayList<>();
        if (verified.isVerifiedUsingV1Scheme()) schemes.add("V1"); if (verified.isVerifiedUsingV2Scheme()) schemes.add("V2");
        if (verified.isVerifiedUsingV3Scheme()) schemes.add("V3");
        String packed = "未检测到加固特征";
        try (ZipFile zip = new ZipFile(file)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String entry = entries.nextElement().getName().toLowerCase(Locale.ROOT);
                if (entry.contains("libjiagu") || entry.contains("libdexhelper") || entry.contains("libprotectclass")
                        || entry.contains("libchaosvmp") || entry.startsWith("assets/ijiami") || entry.equals("assets/shell.dex")) {
                    packed = "疑似加固（文件特征）"; break;
                }
            }
        }
        return new String[]{verified.isVerified() ? String.join(" / ", schemes) : "签名校验失败", packed};
    }
    private static void field(Activity activity, LinearLayout parent, Map<String, TextView> fields, String key, String value, Runnable click) {
        LinearLayout row = Ui.row(activity);
        int height = Ui.dp(activity, click == null ? 30 : 48);
        TextView label = Ui.single(activity, key, 13, Ui.TEXT);
        row.addView(label, new LinearLayout.LayoutParams(Ui.dp(activity, 82), height));
        TextView text = Ui.single(activity, value, 13, Ui.MUTED); text.setEllipsize(TextUtils.TruncateAt.END);
        text.setContentDescription(key + " " + value); text.setTooltipText(value);
        row.addView(text, new LinearLayout.LayoutParams(0, height, 1));
        text.setOnLongClickListener(v -> { copy(activity, text.getText().toString()); return true; });
        label.setOnLongClickListener(v -> { copy(activity, text.getText().toString()); return true; });
        if (click != null) {
            row.setOnClickListener(v -> click.run());
            text.setOnClickListener(v -> click.run());
            label.setOnClickListener(v -> click.run());
        }
        fields.put(key, text); parent.addView(row);
    }
    private static void copy(Activity a, String value) {
        ((ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("APK 信息", value));
        Tasks.toast(a, "已复制");
    }
    private static String system(int api) {
        String version = switch (api) { case 26 -> "8"; case 27 -> "8.1"; case 28 -> "9"; case 29 -> "10";
            case 30 -> "11"; case 31 -> "12"; case 32 -> "12L"; case 33 -> "13"; case 34 -> "14"; case 35 -> "15"; case 36 -> "16"; default -> ""; };
        return version.isEmpty() ? "API " + api : "Android " + version + " (API " + api + ")";
    }
    private ApkDialog() {}
}
