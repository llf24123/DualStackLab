package com.dualstack.lab;

import android.app.*;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.function.Consumer;

final class DirectorySheet {
    private final Activity activity;
    private final DirectoryPlaces places;
    private final FileStore.Node current;
    private final Consumer<DirectoryPlaces.Entry> navigate;
    private final Dialog dialog;
    private final List<DirectoryPlaces.Entry> visible = new ArrayList<>();
    private final ListView list;
    private final TextView recent, bookmarks, empty;
    private final View recentLine, bookmarkLine;
    private final BaseAdapter adapter;
    private final SwipeLayout body;
    private boolean showingBookmarks;
    private boolean closing;

    DirectorySheet(Activity activity, DirectoryPlaces places, FileStore.Node current,
                   Consumer<DirectoryPlaces.Entry> navigate) {
        this.activity = activity; this.places = places; this.current = current; this.navigate = navigate;
        dialog = new Dialog(activity); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        body = new SwipeLayout(activity, false, this::close);
        body.setOrientation(LinearLayout.VERTICAL); body.setBackgroundColor(Ui.SURFACE);
        LinearLayout tabs = Ui.row(activity);
        recent = Ui.text(activity, "最近", 17, Ui.MUTED); bookmarks = Ui.text(activity, "书签", 17, Ui.MUTED);
        recentLine = new View(activity); bookmarkLine = new View(activity);
        tabs.addView(tab(recent, recentLine, false), lp(88, 52));
        tabs.addView(tab(bookmarks, bookmarkLine, true), lp(88, 52));
        tabs.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1));
        tabs.addView(Ui.icon(activity, R.drawable.ic_more, "最近与书签菜单", Ui.MUTED, this::menu), lp(48, 52));
        body.addView(tabs, lp(-1, 52));
        FrameLayout frame = new FrameLayout(activity);
        list = new ListView(activity); list.setDivider(null); list.setContentDescription("最近与书签列表");
        adapter = new BaseAdapter() {
            public int getCount() { return visible.size(); }
            public Object getItem(int i) { return visible.get(i); }
            public long getItemId(int i) { return i; }
            public View getView(int i, View convert, ViewGroup parent) {
                Row row = convert == null ? new Row() : (Row) convert.getTag();
                DirectoryPlaces.Entry entry = visible.get(i);
                row.title.setText(entry.label()); row.path.setText(entry.displayPath());
                row.row.setContentDescription("目录快捷项 " + entry.label());
                return row.row;
            }
        };
        list.setAdapter(adapter); frame.addView(list, new FrameLayout.LayoutParams(-1, -1));
        empty = Ui.text(activity, "", 14, Ui.MUTED); empty.setGravity(Gravity.CENTER);
        frame.addView(empty, new FrameLayout.LayoutParams(-1, -1)); list.setEmptyView(empty);
        body.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1)); body.addView(Ui.line(activity));
        LinearLayout footer = Ui.row(activity); footer.setGravity(Gravity.CENTER);
        footer.addView(Ui.icon(activity, R.drawable.ic_chevron_down, "收起最近与书签", Ui.MUTED, this::close), lp(64, 52));
        body.addView(footer, lp(-1, 52));
        body.canSwipe = () -> !list.canScrollVertically(-1);
        list.setOnItemClickListener((p, v, position, id) -> { dialog.dismiss(); navigate.accept(visible.get(position)); });
        list.setOnItemLongClickListener((p, v, position, id) -> { entryMenu(visible.get(position)); return true; });
        dialog.setContentView(body); update();
    }
    void show() {
        Window window = dialog.getWindow();
        if (window == null) return;
        int height = activity.getResources().getDisplayMetrics().heightPixels;
        int width = activity.getResources().getDisplayMetrics().widthPixels;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        // 幕布从 0 淡入，不再让系统在动画开始前就把 0.4 的黑色压上去（那一下就是"闪黑"）
        window.setDimAmount(0f);
        window.setGravity(Gravity.BOTTOM);
        // 关掉系统窗口动画：Dialog 首次 measure 时高度还没稳定，translate 会按错误高度算距离，表现为"跳一下"
        window.setWindowAnimations(0);
        window.setNavigationBarColor(Ui.SURFACE);
        window.getDecorView().setSystemUiVisibility(Ui.dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        window.setLayout(-1, sheetHeight(width > height ? .78f : .54f));
        dialog.setOnCancelListener(d -> close());
        dialog.setOnShowListener(d -> animateIn());
        dialog.show();
    }

    /** 面板高度按"屏幕高 − 导航栏"算，避免底部按钮被手势条压住或悬空。 */
    private int sheetHeight(float ratio) {
        int height = activity.getResources().getDisplayMetrics().heightPixels;
        int bar = 0;
        int id = activity.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        if (id > 0) bar = activity.getResources().getDimensionPixelSize(id);
        return Math.max(Ui.dp(activity, 240), Math.round((height - bar) * ratio));
    }

    private void animateIn() {
        closing = false;
        Window window = dialog.getWindow();
        float distance = body.getHeight() > 0 ? body.getHeight() : sheetHeight(.54f);
        body.setTranslationY(distance);
        body.animate().translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(2f)).start();
        if (window != null) {
            android.animation.ValueAnimator dim = android.animation.ValueAnimator.ofFloat(0f, .4f);
            dim.setDuration(220);
            dim.addUpdateListener(a -> window.setDimAmount((Float) a.getAnimatedValue()));
            dim.start();
        }
    }

    /** 下滑、收起按钮、点外部、返回键全部走这里，收起动画才和手势一致。 */
    void close() {
        if (closing || !dialog.isShowing()) { if (dialog.isShowing()) dialog.dismiss(); return; }
        closing = true;
        Window window = dialog.getWindow();
        float distance = body.getHeight() > 0 ? body.getHeight() : sheetHeight(.54f);
        body.animate().translationY(distance).setDuration(160)
                .setInterpolator(new android.view.animation.AccelerateInterpolator(2f))
                .withEndAction(() -> { try { dialog.dismiss(); } catch (Exception ignored) { } }).start();
        if (window != null) {
            android.animation.ValueAnimator dim = android.animation.ValueAnimator.ofFloat(.4f, 0f);
            dim.setDuration(160);
            dim.addUpdateListener(a -> window.setDimAmount((Float) a.getAnimatedValue()));
            dim.start();
        }
    }

    void dismiss() { close(); }
    private View tab(TextView label, View underline, boolean bookmark) {
        LinearLayout tab = Ui.column(activity); label.setGravity(Gravity.CENTER);
        tab.addView(label, new LinearLayout.LayoutParams(-1, 0, 1)); tab.addView(underline, lp(-1, 2));
        tab.setOnClickListener(v -> { showingBookmarks = bookmark; update(); });
        tab.setContentDescription(bookmark ? "书签标签" : "最近标签"); tab.setBackground(Ui.ripple(Ui.SURFACE));
        return tab;
    }
    private void update() {
        visible.clear(); visible.addAll(places.entries(showingBookmarks)); adapter.notifyDataSetChanged();
        empty.setText(showingBookmarks ? "暂无书签" : "暂无最近目录");
        recent.setTextColor(showingBookmarks ? Ui.MUTED : Ui.TEXT);
        bookmarks.setTextColor(showingBookmarks ? Ui.TEXT : Ui.MUTED);
        recent.setTypeface(null, showingBookmarks ? android.graphics.Typeface.NORMAL : android.graphics.Typeface.BOLD);
        bookmarks.setTypeface(null, showingBookmarks ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        recentLine.setBackgroundColor(showingBookmarks ? Color.TRANSPARENT : 0xff4b9ece);
        bookmarkLine.setBackgroundColor(showingBookmarks ? 0xff4b9ece : Color.TRANSPARENT);
    }
    private void menu() {
        new AlertDialog.Builder(activity).setItems(new String[]{"添加当前目录到书签", "清空最近目录", "清空书签"},
                (d, which) -> {
                    if (which == 0) { places.bookmark(DirectoryPlaces.Entry.from(current)); showingBookmarks = true; update(); }
                    else new AlertDialog.Builder(activity).setTitle(which == 1 ? "清空最近目录？" : "清空全部书签？")
                            .setNegativeButton("取消", null).setPositiveButton("清空", (a, b) -> {
                                places.clear(which == 2); update();
                            }).show();
                }).show();
    }
    private void entryMenu(DirectoryPlaces.Entry entry) {
        String[] choices = showingBookmarks ? new String[]{"重命名书签", "上移", "下移", "删除书签"}
                : new String[]{"添加到书签", "移除最近记录"};
        boolean bookmark = showingBookmarks;
        new AlertDialog.Builder(activity).setTitle(entry.label()).setItems(choices, (d, which) -> {
            if (!bookmark) {
                if (which == 0) places.bookmark(entry); else places.remove(false, entry.key());
            } else if (which == 0) { rename(entry); return; }
            else if (which == 3) places.remove(true, entry.key());
            else places.move(entry.key(), which == 1 ? -1 : 1);
            update();
        }).show();
    }
    private void rename(DirectoryPlaces.Entry entry) {
        EditText name = Ui.input(activity, "书签名称"); name.setText(entry.label());
        AlertDialog rename = new AlertDialog.Builder(activity).setTitle("重命名书签").setView(name)
                .setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        rename.setOnShowListener(v -> rename.getButton(-1).setOnClickListener(w -> {
            String value = name.getText().toString().trim();
            if (value.isEmpty()) { name.setError("名称不能为空"); return; }
            places.rename(entry.key(), value); rename.dismiss(); update();
        })); rename.show();
    }
    private LinearLayout.LayoutParams lp(int width, int height) {
        return new LinearLayout.LayoutParams(width < 0 ? width : Ui.dp(activity, width), height < 0 ? height : Ui.dp(activity, height));
    }
    private final class Row {
        final LinearLayout row = Ui.row(activity);
        final TextView title = Ui.single(activity, "", 16, Ui.TEXT), path = Ui.single(activity, "", 11, Ui.MUTED);
        Row() {
            row.setPadding(Ui.dp(activity, 12), 0, Ui.dp(activity, 12), 0);
            row.setLayoutParams(new AbsListView.LayoutParams(-1, Ui.dp(activity, 60)));
            ImageView icon = Ui.image(activity, R.drawable.ic_folder, Color.WHITE);
            icon.setBackground(Ui.background(Ui.ICON_BG, Ui.dp(activity, 4))); icon.setPadding(Ui.dp(activity, 7), Ui.dp(activity, 7), Ui.dp(activity, 7), Ui.dp(activity, 7));
            row.addView(icon, lp(36, 36));
            LinearLayout text = Ui.column(activity); text.setPadding(Ui.dp(activity, 12), 0, 0, 0);
            text.addView(title, lp(-1, 25)); text.addView(path, lp(-1, 18));
            row.addView(text, new LinearLayout.LayoutParams(0, -2, 1)); row.setTag(this);
        }
    }

    static final class SwipeLayout extends LinearLayout {
        private final boolean upward;
        private final Runnable action;
        private final int slop;
        private float x, y;
        private boolean swiping;
        java.util.function.BooleanSupplier canSwipe = () -> true;
        SwipeLayout(android.content.Context context, boolean upward, Runnable action) {
            super(context); this.upward = upward; this.action = action;
            setClickable(true);
            slop = ViewConfiguration.get(context).getScaledTouchSlop() * 2;
        }
        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { x = event.getX(); y = event.getY(); swiping = false; }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float dy = event.getY() - y;
                if ((upward ? -dy : dy) > slop && Math.abs(dy) > Math.abs(event.getX() - x) * 1.5f && canSwipe.getAsBoolean()) {
                    swiping = true; return true;
                }
            }
            return false;
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { x = event.getX(); y = event.getY(); swiping = false; return true; }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float dy = event.getY() - y;
                if ((upward ? -dy : dy) > slop && Math.abs(dy) > Math.abs(event.getX() - x) * 1.5f && canSwipe.getAsBoolean()) swiping = true;
            }
            if (!swiping) return super.onTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_UP) { swiping = false; action.run(); }
            if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) swiping = false;
            return true;
        }
    }
}
