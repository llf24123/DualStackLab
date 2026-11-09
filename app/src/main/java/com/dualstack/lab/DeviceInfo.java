package com.dualstack.lab;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.MediaDrm;
import android.os.*;
import android.provider.Settings;
import android.util.DisplayMetrics;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Read-only, on-device inventory. No provisioning, upload, or identifier mutation. */
final class DeviceInfo {
    record Field(String section, String name, String value) { }
    private static final UUID WIDEVINE = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed");
    private final List<Field> fields = new ArrayList<>();
    private void add(String section, String name, Object value) {
        String text = value == null ? "未提供" : value.toString().trim();
        fields.add(new Field(section, name, text.isEmpty() ? "未提供" : text));
    }
    static List<Field> basic(Context context) {
        DeviceInfo out = new DeviceInfo();
        out.add("设备", "设备名称", Settings.Global.getString(context.getContentResolver(), "device_name"));
        out.add("设备", "制造商", Build.MANUFACTURER); out.add("设备", "品牌", Build.BRAND);
        out.add("设备", "型号", Build.MODEL); out.add("设备", "产品", Build.PRODUCT);
        out.add("设备", "设备代号", Build.DEVICE); out.add("设备", "主板", Build.BOARD);
        out.add("设备", "硬件", Build.HARDWARE); out.add("设备", "Bootloader", Build.BOOTLOADER);
        out.add("标识符", "Android ID（本应用/用户/签名作用域）",
                Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID));
        out.add("标识符", "序列号（系统 API）", serial());
        out.add("系统", "Android", Build.VERSION.RELEASE);
        out.add("系统", "API", Build.VERSION.SDK_INT); out.add("系统", "安全补丁", Build.VERSION.SECURITY_PATCH);
        out.add("系统", "构建 ID", Build.ID); out.add("系统", "显示版本", Build.DISPLAY);
        out.add("系统", "增量版本", Build.VERSION.INCREMENTAL); out.add("系统", "Fingerprint", Build.FINGERPRINT);
        out.add("系统", "构建类型 / 标签", Build.TYPE + " / " + Build.TAGS);
        out.add("系统", "构建时间", new Date(Build.TIME)); out.add("系统", "内核", System.getProperty("os.version"));
        out.add("系统", "运行时", System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version"));
        out.add("系统", "已运行", android.text.format.DateUtils.formatElapsedTime(SystemClock.elapsedRealtime() / 1000));
        out.add("系统", "语言 / 时区", Locale.getDefault() + " / " + TimeZone.getDefault().getID());
        out.add("处理器", "ABI", String.join(", ", Build.SUPPORTED_ABIS));
        out.add("处理器", "64 位 ABI", String.join(", ", Build.SUPPORTED_64_BIT_ABIS));
        out.add("处理器", "32 位 ABI", String.join(", ", Build.SUPPORTED_32_BIT_ABIS));
        out.add("处理器", "可用逻辑核心", Runtime.getRuntime().availableProcessors());
        if (Build.VERSION.SDK_INT >= 31) {
            out.add("处理器", "SoC 制造商", Build.SOC_MANUFACTURER); out.add("处理器", "SoC 型号", Build.SOC_MODEL);
        }
        out.add("处理器", "/proc/cpuinfo", read("/proc/cpuinfo", 24576));
        out.add("处理器", "在线核心", read("/sys/devices/system/cpu/online", 1024));
        for (int i = 0; i < Math.min(32, Runtime.getRuntime().availableProcessors()); i++) {
            String base = "/sys/devices/system/cpu/cpu" + i + "/cpufreq/";
            String max = read(base + "cpuinfo_max_freq", 512);
            if (!max.startsWith("不可读取")) out.add("处理器", "CPU " + i + " 最高频率 (kHz)", max);
        }
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo(); manager.getMemoryInfo(memory);
        out.add("内存与存储", "内核可访问总内存（非标称 RAM）", FileStore.size(memory.totalMem));
        out.add("内存与存储", "当前可用内存", FileStore.size(memory.availMem));
        out.add("内存与存储", "低内存状态", memory.lowMemory);
        out.add("内存与存储", "应用内存上限", manager.getMemoryClass() + " MiB");
        try { out.add("内存与存储", "内存页大小", android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE) + " B"); }
        catch (Exception e) { out.add("内存与存储", "内存页大小", "不可读取"); }
        storage(out, "数据分区", Environment.getDataDirectory());
        storage(out, "共享存储", Environment.getExternalStorageDirectory());
        out.add("内存与存储", "/proc/meminfo", read("/proc/meminfo", 8192));
        DisplayMetrics display = context.getResources().getDisplayMetrics();
        out.add("屏幕", "应用可用像素", display.widthPixels + " × " + display.heightPixels);
        out.add("屏幕", "密度", display.densityDpi + " dpi / " + display.density);
        out.add("屏幕", "物理 DPI（系统报告）", display.xdpi + " × " + display.ydpi);
        out.add("屏幕", "字体缩放", context.getResources().getConfiguration().fontScale);
        android.hardware.display.DisplayManager displays = (android.hardware.display.DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        for (android.view.Display item : displays.getDisplays()) {
            android.view.Display.Mode mode = item.getMode();
            out.add("屏幕", item.getName(), mode.getPhysicalWidth() + " × " + mode.getPhysicalHeight() + " / " + mode.getRefreshRate() + " Hz");
            StringJoiner modes = new StringJoiner(", ");
            for (android.view.Display.Mode value : item.getSupportedModes())
                modes.add(value.getPhysicalWidth() + "×" + value.getPhysicalHeight() + "@" + value.getRefreshRate());
            out.add("屏幕", "支持模式 · " + item.getDisplayId(), modes);
        }
        Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            out.add("电池", "电量", battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / Math.max(1, scale) + "%");
            out.add("电池", "温度", battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f + " °C");
            out.add("电池", "电压", battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) + " mV");
            out.add("电池", "技术", battery.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY));
            int state = battery.getIntExtra(BatteryManager.EXTRA_STATUS, 1);
            out.add("电池", "状态", state == 2 ? "充电" : state == 3 ? "放电" : state == 4 ? "未充电" : state == 5 ? "已充满" : "未知");
        }
        BatteryManager batteries = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        long counter = batteries.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
        out.add("电池", "电荷计数", counter == Long.MIN_VALUE ? "硬件不支持" : counter + " µAh");
        drm(out);
        return out.fields;
    }
    private static void storage(DeviceInfo out, String label, File directory) {
        try { StatFs fs = new StatFs(directory.getPath()); out.add("内存与存储", label,
                FileStore.size(fs.getTotalBytes()) + " 总计 / " + FileStore.size(fs.getAvailableBytes()) + " 可用"); }
        catch (Exception e) { out.add("内存与存储", label, "不可读取：" + e.getClass().getSimpleName()); }
    }
    @android.annotation.SuppressLint("MissingPermission") // Optional protected API; denial is an expected field value, not a missing permission flow.
    private static String serial() {
        try { String serial = Build.getSerial(); return serial == null || serial.isBlank() || serial.equals(Build.UNKNOWN) ? "系统未提供" : serial; }
        catch (SecurityException denied) { return "系统限制，将尝试 ROOT 属性"; }
    }
    private static void drm(DeviceInfo out) {
        if (!MediaDrm.isCryptoSchemeSupported(WIDEVINE)) { out.add("DRM", "Widevine", "设备不支持"); return; }
        MediaDrm drm = null;
        try {
            drm = new MediaDrm(WIDEVINE);
            try {
                byte[] unique = drm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID);
                out.add("DRM", "DRM ID（Widevine / 应用可见）", hex(unique));
                out.add("DRM", "DRM ID（Base64）", android.util.Base64.encodeToString(unique, android.util.Base64.NO_WRAP));
            } catch (Exception e) { out.add("DRM", "DRM ID", "不可读取：" + e.getClass().getSimpleName()); }
            for (String property : new String[]{"vendor", "version", "description", "algorithms", "securityLevel", "systemId", "hdcpLevel", "maxHdcpLevel"}) {
                try { out.add("DRM", property, drm.getPropertyString(property)); }
                catch (Exception e) { out.add("DRM", property, "此实现未提供"); }
            }
        } catch (Exception e) { out.add("DRM", "Widevine", "不可读取：" + e.getClass().getSimpleName()); }
        finally { if (drm != null) drm.release(); }
    }
    static List<Field> privileged() {
        DeviceInfo out = new DeviceInfo();
        boolean root = false;
        try {
            ShellRunner.Result id = new ShellRunner().run("id -u", null, true, 8000, null, false);
            root = id.exitCode() == 0 && !id.timedOut() && id.output().trim().equals("0");
        } catch (Exception ignored) { }
        out.add("访问状态", "ROOT", root ? "已授权，补充受限信息" : "不可用或未授权，显示普通权限信息");
        try {
            ShellRunner.Result props = new ShellRunner().run("getprop", null, root, 6000, null, false);
            Map<String, String> properties = new TreeMap<>();
            for (String line : props.output().split("\n")) {
                int split = line.indexOf("]: [");
                if (line.startsWith("[") && split > 0 && line.endsWith("]"))
                    properties.put(line.substring(1, split), line.substring(split + 4, line.length() - 1));
            }
            for (String key : new String[]{"ro.serialno", "ro.boot.serialno", "ro.product.model", "ro.product.manufacturer",
                    "ro.build.id", "ro.build.display.id", "ro.build.version.release", "ro.build.version.incremental",
                    "ro.build.version.security_patch", "ro.build.fingerprint", "ro.soc.model", "gsm.version.baseband"})
                out.add("常用系统属性", key, properties.get(key));
        } catch (Exception e) { out.add("访问状态", "系统属性", "读取失败：" + e.getMessage()); }
        if (root) {
            for (String[] item : new String[][]{
                    {"Android ID（ROOT shell 设置视图，非本应用 ID）", "settings --user " + (android.os.Process.myUid() / 100000) + " get secure android_id"},
                    {"SELinux", "getenforce"}, {"内核版本", "uname -a"},
                    {"CPU 与拓扑", "cat /proc/cpuinfo"}, {"分区容量", "df -h"},
                    {"块设备", "cat /proc/partitions"}, {"GPU 型号", "cat /sys/class/kgsl/kgsl-3d0/gpu_model"},
                    {"电池健康数据", "cat /sys/class/power_supply/battery/uevent"}}) {
                try {
                    ShellRunner.Result result = new ShellRunner().run(item[1], null, true, 5000, null, false);
                    out.add("ROOT 详细信息", item[0], result.exitCode() == 0 ? result.output() : "设备未提供或访问受限");
                } catch (Exception e) { out.add("ROOT 详细信息", item[0], "不可读取：" + e.getClass().getSimpleName()); }
            }
        }
        return out.fields;
    }
    private static String read(String path, int limit) {
        try (InputStream in = new FileInputStream(path)) { return new String(FileStore.readBytes(in, limit), StandardCharsets.UTF_8); }
        catch (Exception e) { return "不可读取（" + e.getClass().getSimpleName() + "）"; }
    }
    static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder();
        for (byte b : bytes) value.append("0123456789abcdef".charAt((b & 255) >>> 4)).append("0123456789abcdef".charAt(b & 15));
        return value.toString();
    }
}
