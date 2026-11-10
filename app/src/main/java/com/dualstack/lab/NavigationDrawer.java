package com.dualstack.lab;

import android.app.*;
import android.graphics.Color;
import android.os.Environment;
import android.view.*;
import android.widget.*;
import java.io.File;

final class NavigationDrawer {
    static Dialog show(Activity activity, Runnable root, Runnable internal, Runnable installed) {
        Dialog dialog = new Dialog(activity); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout body = Ui.column(activity); body.setBackgroundColor(Ui.SURFACE);
        LinearLayout header = Ui.row(activity); header.setBackgroundColor(Ui.INK);
        header.setPadding(Ui.dp(activity, 18), Ui.dp(activity, 18), Ui.dp(activity, 10), Ui.dp(activity, 14));
        ImageView brand = Ui.image(activity, R.drawable.ic_app, Color.WHITE);
        brand.setImageTintList(null);
        header.addView(brand, new LinearLayout.LayoutParams(Ui.dp(activity, 32), Ui.dp(activity, 40)));
        TextView title = Ui.single(activity, "双栈 Lab", 22, Color.WHITE); title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(Ui.dp(activity, 14), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, Ui.dp(activity, 56), 1));
        header.addView(Ui.icon(activity, R.drawable.ic_theme, "主题切换", Color.WHITE,
                () -> { dialog.dismiss(); Ui.chooseTheme(activity); }), new LinearLayout.LayoutParams(Ui.dp(activity, 48), Ui.dp(activity, 48)));
        body.addView(header);
        ScrollView scroll = new ScrollView(activity); scroll.setContentDescription("位置与工具列表");
        LinearLayout content = Ui.column(activity); scroll.addView(content);
        content.addView(section(activity, "本地"));
        storage(activity, content, "根目录", new File("/"), dialog, root);
        storage(activity, content, "内部存储", Environment.getExternalStorageDirectory(), dialog, internal);
        content.addView(section(activity, "工具"));
        content.addView(row(activity, "设备信息", R.drawable.ic_info, () -> { dialog.dismiss(); activity.startActivity(new android.content.Intent(activity, DeviceInfoActivity.class)); }));
        content.addView(row(activity, "已安装应用", R.drawable.ic_package, () -> { dialog.dismiss(); installed.run(); }));
        body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        dialog.setContentView(body); Window window = dialog.getWindow();
        if (window == null) return dialog;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); window.setDimAmount(.45f);
        window.setGravity(Gravity.START | Gravity.TOP); window.setWindowAnimations(R.style.NavigationDrawerAnimation);
        window.setNavigationBarColor(Ui.SURFACE);
        window.getDecorView().setSystemUiVisibility(Ui.dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        int screen = activity.getResources().getDisplayMetrics().widthPixels;
        window.setLayout(Math.min(Ui.dp(activity, 340), Math.round(screen * .82f)), -1);
        dialog.show(); return dialog;
    }
    private static TextView section(Activity activity, String title) {
        TextView text = Ui.text(activity, title, 14, Ui.MUTED);
        text.setPadding(Ui.dp(activity, 18), 0, 0, 0);
        text.setLayoutParams(new LinearLayout.LayoutParams(-1, Ui.dp(activity, 46))); return text;
    }
    private static LinearLayout row(Activity activity, String name, int drawable, Runnable action) {
        LinearLayout row = Ui.row(activity); row.setPadding(Ui.dp(activity, 12), 0, Ui.dp(activity, 16), 0);
        ImageView icon = Ui.image(activity, drawable, Color.WHITE); icon.setBackground(Ui.background(Ui.ICON_BG, Ui.dp(activity, 22)));
        icon.setPadding(Ui.dp(activity, 10), Ui.dp(activity, 10), Ui.dp(activity, 10), Ui.dp(activity, 10));
        row.addView(icon, new LinearLayout.LayoutParams(Ui.dp(activity, 40), Ui.dp(activity, 40)));
        TextView text = Ui.text(activity, name, 17, Ui.TEXT); text.setPadding(Ui.dp(activity, 14), 0, 0, 0);
        row.addView(text, new LinearLayout.LayoutParams(0, Ui.dp(activity, 58), 1));
        row.setBackground(Ui.ripple(Ui.SURFACE)); row.setOnClickListener(v -> action.run()); return row;
    }
    private static void storage(Activity activity, LinearLayout parent, String title, File file, Dialog dialog, Runnable action) {
        LinearLayout row = row(activity, title, R.drawable.ic_folder, () -> { dialog.dismiss(); action.run(); });
        TextView label = (TextView) row.getChildAt(1); row.removeView(label);
        LinearLayout details = Ui.column(activity); details.setPadding(Ui.dp(activity, 14), Ui.dp(activity, 8), 0, Ui.dp(activity, 8));
        label.setPadding(0, 0, 0, 0); details.addView(label, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 28)));
        ProgressBar capacity = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        capacity.setProgressTintList(android.content.res.ColorStateList.valueOf(0xff4b9ece));
        details.addView(capacity, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 6)));
        TextView value = Ui.single(activity, "读取容量…", 11, Ui.MUTED);
        details.addView(value, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 24)));
        row.addView(details, new LinearLayout.LayoutParams(0, -2, 1)); parent.addView(row);
        Tasks.IO.execute(() -> {
            long total = file.getTotalSpace(), free = file.getUsableSpace();
            activity.runOnUiThread(() -> {
                if (!dialog.isShowing()) return;
                long used = Math.max(0, total - free);
                capacity.setProgress(total == 0 ? 0 : (int) Math.min(100, used * 100 / total));
                value.setText(total == 0 ? "容量不可用" : FileStore.size(used) + " 已用，" + FileStore.size(free) + " 可用");
            });
        });
    }
    private NavigationDrawer() {}
}
