package com.dualstack.lab;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static int INK = Color.rgb(48, 48, 48);
    static int TEXT = Color.rgb(36, 42, 43);
    static int MUTED = Color.rgb(119, 127, 130);
    static int ACCENT = Color.rgb(22, 125, 104);
    static int LINE = Color.rgb(232, 236, 237);
    static int TINT = Color.rgb(236, 247, 243);
    static int SURFACE = Color.WHITE, PANEL = 0xfff6f8f7, WARNING = 0xfff5f3e9, ICON_BG = 0xff303030;
    static boolean dark;
    static final int GOLD = Color.rgb(193, 143, 42);

    static String themeMode(Context context) { return context.getSharedPreferences("appearance", 0).getString("mode", "system"); }
    static boolean isDark(Context context) {
        String mode = themeMode(context);
        return mode.equals("dark") || (mode.equals("system") &&
                (context.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                        == android.content.res.Configuration.UI_MODE_NIGHT_YES);
    }
    static void configure(Context context) {
        dark = isDark(context);
        INK = dark ? 0xff202020 : 0xff303030;
        TEXT = dark ? 0xffe6e8e7 : 0xff242a2b;
        MUTED = dark ? 0xffa4aaa7 : 0xff777f82;
        ACCENT = dark ? 0xff72cdb7 : 0xff167d68;
        LINE = dark ? 0xff353936 : 0xffe8eced;
        TINT = dark ? 0xff24483e : 0xffecf7f3;
        SURFACE = dark ? 0xff181b19 : Color.WHITE;
        PANEL = dark ? 0xff222624 : 0xfff6f8f7;
        WARNING = dark ? 0xff3b3520 : 0xfff5f3e9;
        ICON_BG = dark ? 0xff454a47 : 0xff303030;
    }
    static void chooseTheme(Activity activity) {
        String mode = themeMode(activity);
        int selected = mode.equals("light") ? 0 : mode.equals("dark") ? 1 : 2;
        new android.app.AlertDialog.Builder(activity).setTitle("主题")
                .setSingleChoiceItems(new String[]{"浅色", "深色", "跟随系统"}, selected, (d, which) -> {
                    activity.getSharedPreferences("appearance", 0).edit()
                            .putString("mode", new String[]{"light", "dark", "system"}[which]).apply();
                    d.dismiss(); activity.recreate();
                }).setNegativeButton("取消", null).show();
    }
    static int[] palette() { return new int[]{SURFACE, PANEL, TEXT, MUTED, ACCENT, LINE, TINT, WARNING, INK}; }
    static void updateTheme(Activity activity, int[] old) {
        configure(activity);
        int[] now = palette();
        repaint(activity.getWindow().getDecorView(), old, now);
        activity.getWindow().setStatusBarColor(INK); activity.getWindow().setNavigationBarColor(SURFACE);
        activity.getWindow().getDecorView().setSystemUiVisibility(dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }
    private static int mapped(int color, int[] old, int[] now, boolean text) {
        for (int i = text ? 2 : 0; i < old.length; i++) if (old[i] == color) return now[i];
        return color;
    }
    private static void repaint(View view, int[] old, int[] now) {
        android.graphics.drawable.Drawable background = view.getBackground();
        repaintDrawable(background, old, now);
        if (view instanceof TextView text) {
            text.setTextColor(mapped(text.getCurrentTextColor(), old, now, true));
            text.setHintTextColor(mapped(text.getCurrentHintTextColor(), old, now, true));
        }
        if (view instanceof ImageView image && image.getImageTintList() != null)
            image.setImageTintList(ColorStateList.valueOf(mapped(image.getImageTintList().getDefaultColor(), old, now, true)));
        if (view instanceof android.widget.CheckBox check) check.setButtonTintList(ColorStateList.valueOf(ACCENT));
        if (view instanceof android.view.ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++) repaint(group.getChildAt(i), old, now);
        view.invalidate();
    }
    private static void repaintDrawable(android.graphics.drawable.Drawable drawable, int[] old, int[] now) {
        if (drawable instanceof android.graphics.drawable.ColorDrawable color)
            color.setColor(mapped(color.getColor(), old, now, false));
        else if (drawable instanceof GradientDrawable gradient && gradient.getColor() != null)
            gradient.setColor(mapped(gradient.getColor().getDefaultColor(), old, now, false));
        else if (drawable instanceof android.graphics.drawable.LayerDrawable layers)
            for (int i = 0; i < layers.getNumberOfLayers(); i++) repaintDrawable(layers.getDrawable(i), old, now);
    }

    static int dp(Context c, int n) {
        return Math.round(c.getResources().getDisplayMetrics().density * n);
    }

    static LinearLayout column(Context c) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }

    static LinearLayout row(Context c) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    static TextView text(Context c, String s, int size, int color) {
        TextView v = new TextView(c);
        v.setText(s);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setIncludeFontPadding(false);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    static TextView single(Context c, String s, int size, int color) {
        TextView v = text(c, s, size, color);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        return v;
    }

    static EditText input(Context c, String hint) {
        EditText v = new EditText(c);
        v.setTextSize(14);
        v.setTextColor(TEXT);
        v.setHintTextColor(MUTED);
        v.setHint(hint);
        v.setSingleLine(true);
        v.setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8));
        return v;
    }

    static ImageView image(Context c, int drawable, int color) {
        ImageView v = new ImageView(c);
        v.setImageResource(drawable);
        v.setImageTintList(ColorStateList.valueOf(color));
        v.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        return v;
    }

    static ImageView icon(Context c, int drawable, String description, int color, Runnable action) {
        ImageView v = image(c, drawable, color);
        v.setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12));
        v.setContentDescription(description);
        v.setTooltipText(description);
        v.setBackground(ripple(Color.TRANSPARENT));
        v.setFocusable(true);
        v.setOnClickListener(x -> action.run());
        v.setMinimumWidth(dp(c, 44));
        v.setMinimumHeight(dp(c, 44));
        return v;
    }

    static View command(Context c, int drawable, String title, Runnable action) {
        LinearLayout v = column(c);
        v.setGravity(Gravity.CENTER);
        ImageView icon = image(c, drawable, TEXT);
        v.addView(icon, new LinearLayout.LayoutParams(dp(c, 21), dp(c, 21)));
        TextView label = single(c, title, 10, TEXT);
        label.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(c, 18));
        p.topMargin = dp(c, 4);
        v.addView(label, p);
        v.setContentDescription(title);
        v.setTooltipText(title);
        v.setBackground(ripple(SURFACE));
        v.setOnClickListener(x -> action.run());
        return v;
    }

    static GradientDrawable background(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    static RippleDrawable ripple(int color) {
        return new RippleDrawable(ColorStateList.valueOf(0x227f9991),
                background(color, 0), background(Color.WHITE, 0));
    }

    static View line(Context c) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(c, 1)));
        return v;
    }

    static void install(Activity a, LinearLayout root) {
        a.getWindow().setStatusBarColor(INK);
        a.getWindow().setNavigationBarColor(SURFACE);
        a.getWindow().getDecorView().setSystemUiVisibility(dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        root.setBackgroundColor(SURFACE);
        View statusGuard = new View(a);
        statusGuard.setBackgroundColor(INK);
        root.addView(statusGuard, 0, new LinearLayout.LayoutParams(-1, 0));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                top = bars.top;
                v.setPadding(bars.left, 0, bars.right, bars.bottom);
            } else {
                top = insets.getSystemWindowInsetTop();
                v.setPadding(insets.getSystemWindowInsetLeft(), 0,
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            if (statusGuard.getLayoutParams().height != top) {
                statusGuard.getLayoutParams().height = top;
                statusGuard.requestLayout();
            }
            return insets;
        });
        a.setContentView(root);
        root.requestApplyInsets();
    }

    static LinearLayout toolbar(Activity a, String title, Runnable back) {
        LinearLayout bar = row(a);
        bar.setBackgroundColor(INK);
        bar.addView(icon(a, R.drawable.ic_back, "返回", Color.WHITE, back),
                new LinearLayout.LayoutParams(dp(a, 48), dp(a, 52)));
        TextView label = single(a, title, 17, Color.WHITE);
        label.setTypeface(null, Typeface.BOLD);
        bar.addView(label, new LinearLayout.LayoutParams(0, -1, 1));
        return bar;
    }

    private Ui() {}
}
