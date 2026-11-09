package com.dualstack.lab;

import android.app.*;
import android.graphics.Color;
import android.graphics.Rect;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.function.Consumer;

final class DirectorySheet {
    private final Activity activity;
    private final DirectoryPlaces places;
    private final FileStore.Node current;
    private final Consumer<DirectoryPlaces.Entry> navigate;
    private final FrameLayout overlay;
    private final View scrim;
    private final SwipeLayout body;
    private int dialogHeight;
    private ViewGroup host;
    private final List<DirectoryPlaces.Entry> visible = new ArrayList<>();
    private final ListView list;
    private final TextView recent, bookmarks, empty;
    private final View recentLine, bookmarkLine;
    private final BaseAdapter adapter;
    private boolean showingBookmarks;
    /** TRUE while sliding open, FALSE while sliding closed, null when settled or following a finger. */
    private Boolean settling;

    DirectorySheet(Activity activity, DirectoryPlaces places, FileStore.Node current,
                   Consumer<DirectoryPlaces.Entry> navigate) {
        this.activity = activity; this.places = places; this.current = current; this.navigate = navigate;
        overlay = new FrameLayout(activity) {
            @Override public boolean dispatchTouchEvent(MotionEvent event) {
                // While closing, touches reach the panes underneath instead of being swallowed by the fading scrim.
                return !Boolean.FALSE.equals(settling) && super.dispatchTouchEvent(event);
            }
        };
        overlay.setContentDescription("最近与书签面板");
        overlay.setOnApplyWindowInsetsListener((view, insets) -> { resize(); return insets; });
        overlay.addOnLayoutChangeListener((view, l, t, r, b, oldL, oldT, oldR, oldB) -> resize());
        scrim = new View(activity); scrim.setBackgroundColor(0x66000000);
        scrim.setOnClickListener(v -> close());
        overlay.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        showingBookmarks = true;
        body = new SwipeLayout(activity, false, this::close);
        body.setDragCallbacks(() -> { settling = null; body.animate().cancel(); scrim.animate().cancel(); },
                this::position, commit -> settle(!commit));
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
        body.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1));
        body.addView(Ui.line(activity));
        LinearLayout footer = Ui.row(activity); footer.setGravity(Gravity.CENTER);
        footer.addView(Ui.icon(activity, R.drawable.ic_chevron_down, "收起最近与书签", Ui.MUTED, this::close), lp(64, 52));
        body.addView(footer, lp(-1, 52));
        body.setClipToPadding(false); body.setClipChildren(false);
        body.persistentInsets = true;
        body.setPadding(0, 0, 0, safeBottom());
        body.canSwipe = () -> !list.canScrollVertically(-1);
        list.setOnItemClickListener((p, v, position, id) -> { DirectoryPlaces.Entry entry = visible.get(position); close(); navigate.accept(entry); });
        list.setOnItemLongClickListener((p, v, position, id) -> { entryMenu(visible.get(position)); return true; });
        update();
    }
    void show() { show(true); settle(true); }
    void showForDrag() { show(true); }
    private void show(boolean startHidden) {
        if (isShowing()) return;
        host = activity.findViewById(android.R.id.content);
        overlay.addView(body, new FrameLayout.LayoutParams(1, 1, Gravity.BOTTOM | Gravity.LEFT));
        host.addView(overlay, new ViewGroup.LayoutParams(-1, -1));
        resize();
        body.setElevation(Ui.dp(activity, 8));
        position(startHidden ? dialogHeight : 0);
        overlay.requestApplyInsets();
    }
    private int safeBottom() {
        WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
        if (insets == null) return 0;
        return android.os.Build.VERSION.SDK_INT >= 30
                ? insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()).bottom
                : insets.getSystemWindowInsetBottom();
    }

    private void resize() {
        if (host == null || host.getWidth() == 0 || host.getHeight() == 0) return;
        Rect insets = safeInsets();
        float density = activity.getResources().getDisplayMetrics().density;
        Rect bounds = panelBounds(host.getWidth(), host.getHeight(), insets, density);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) body.getLayoutParams();
        int bottom = host.getHeight() - bounds.bottom;
        int padding = safeBottom();
        if (params.width == bounds.width() && params.height == bounds.height()
                && params.leftMargin == bounds.left && params.bottomMargin == bottom
                && body.getPaddingBottom() == padding) return;
        float fraction = dialogHeight == 0 ? 0 : body.getTranslationY() / dialogHeight;
        body.animate().cancel(); scrim.animate().cancel();
        dialogHeight = bounds.height();
        params.width = bounds.width(); params.height = bounds.height();
        params.leftMargin = bounds.left; params.bottomMargin = bottom;
        body.setLayoutParams(params);
        // The drawer reaches the screen edge; its content keeps clear of the navigation bar.
        body.setPadding(0, 0, 0, padding);
        // ListView ends at the drawer's bottom padding; keep its last row above that band.
        list.setClipToPadding(false);
        body.requestLayout();
        position(dialogHeight * fraction);
        if (settling != null) settle(settling);
    }
    private Rect safeInsets() {
        WindowInsets insets = host.getRootWindowInsets();
        if (insets == null) return new Rect();
        int left, top, right, bottom;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            left = bars.left; top = bars.top; right = bars.right; bottom = bars.bottom;
        } else {
            left = insets.getSystemWindowInsetLeft(); top = insets.getSystemWindowInsetTop();
            right = insets.getSystemWindowInsetRight(); bottom = insets.getSystemWindowInsetBottom();
        }
        int[] xy = new int[2]; host.getLocationInWindow(xy);
        View decor = activity.getWindow().getDecorView();
        // Insets are window-relative; content may already exclude a system bar on older Android versions.
        return new Rect(Math.max(0, left - xy[0]), Math.max(0, top - xy[1]),
                Math.max(0, xy[0] + host.getWidth() - (decor.getWidth() - right)),
                Math.max(0, xy[1] + host.getHeight() - (decor.getHeight() - bottom)));
    }
    static Rect panelBounds(int width, int height, Rect insets, float density) {
        int availableWidth = Math.max(1, width - insets.left - insets.right);
        int availableHeight = Math.max(1, height - insets.top - insets.bottom);
        // Phones and tablets use the same full-width drawer; it meets the screen edge and pads its content.
        int drawerHeight = Math.min(availableHeight,
                Math.max(Math.round(200 * density), Math.round(availableHeight * .54f)));
        int top = height - drawerHeight;
        return new Rect(insets.left, top, insets.left + availableWidth, height);
    }
    boolean isShowing() { return overlay.getParent() != null; }
    private void position(float offset) {
        body.setTranslationY(Math.max(0, Math.min(dialogHeight, offset)));
        scrim.setAlpha(1f - body.getTranslationY() / Math.max(1, dialogHeight));
    }
    void drag(float distance) { position(dialogHeight - distance); }
    void finishDrag(boolean commit) { settle(commit); }
    /** Every close path slides the panel out first; dismiss() stays immediate for teardown. */
    void close() { if (isShowing() && !Boolean.FALSE.equals(settling)) settle(false); }
    private void settle(boolean open) {
        body.animate().cancel(); scrim.animate().cancel();
        settling = open;
        body.animate().translationY(open ? 0 : dialogHeight).setDuration(Ui.motionEnabled() ? Ui.MOTION_PANEL : 0)
                .setInterpolator(Ui.EASING)
                .withEndAction(() -> { settling = null; if (!open) dismiss(); }).start();
        scrim.animate().alpha(open ? 1 : 0).setDuration(Ui.motionEnabled() ? Ui.MOTION_PANEL : 0).setInterpolator(Ui.EASING).start();
    }
    void dismiss() {
        settling = null;
        body.animate().cancel(); scrim.animate().cancel();
        if (overlay.getParent() instanceof ViewGroup parent) parent.removeView(overlay);
        overlay.removeView(body); host = null;
    }

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
        /** The drawer supplies its own bottom padding, so the system must not add it again. */
        boolean persistentInsets;
        private Runnable dragStart;
        private Consumer<Float> dragProgress;
        private Consumer<Boolean> dragEnd;
        java.util.function.BooleanSupplier canSwipe = () -> true;
        SwipeLayout(android.content.Context context, boolean upward, Runnable action) {
            super(context); this.upward = upward; this.action = action;
            setClickable(true);
            setFocusable(true); setOnClickListener(v -> action.run());
            slop = ViewConfiguration.get(context).getScaledTouchSlop();
        }
        void setDragCallbacks(Runnable start, Consumer<Float> progress, Consumer<Boolean> end) {
            dragStart = start; dragProgress = progress; dragEnd = end;
        }
        @Override public WindowInsets onApplyWindowInsets(WindowInsets insets) {
            return persistentInsets ? insets : super.onApplyWindowInsets(insets);
        }
        private void begin(MotionEvent event) {
            // A tap must not freeze an opening animation; a real drag cancels it through dragStart.
            if (dragStart == null) animate().cancel();
            x = event.getRawX(); y = event.getRawY(); swiping = false;
        }
        private boolean track(MotionEvent event) {
            float dy = event.getRawY() - y;
            if (!swiping && (upward ? -dy : dy) > slop
                    && Math.abs(dy) > Math.abs(event.getRawX() - x) * 1.5f && canSwipe.getAsBoolean()) {
                swiping = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                if (dragStart != null) dragStart.run();
            }
            return swiping;
        }
        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) begin(event);
            return event.getActionMasked() == MotionEvent.ACTION_MOVE && track(event);
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            int type = event.getActionMasked();
            if (type == MotionEvent.ACTION_DOWN) { begin(event); return true; }
            if (type == MotionEvent.ACTION_MOVE && track(event)) {
                float distance = Math.max(0, upward ? y - event.getRawY() : event.getRawY() - y);
                if (dragProgress != null) dragProgress.accept(distance);
                else setTranslationY((upward ? -1 : 1) * distance);
                return true;
            }
            if (!swiping) {
                if (type == MotionEvent.ACTION_UP) {
                    if (Math.abs(event.getRawX() - x) <= slop && Math.abs(event.getRawY() - y) <= slop) performClick();
                    return true;
                }
                return super.onTouchEvent(event);
            }
            if (type == MotionEvent.ACTION_UP || type == MotionEvent.ACTION_CANCEL || type == MotionEvent.ACTION_POINTER_DOWN) {
                float distance = Math.max(0, upward ? y - event.getRawY() : event.getRawY() - y);
                boolean commit = type == MotionEvent.ACTION_UP && distance >= Ui.dp(getContext(), 48);
                swiping = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                if (dragEnd != null) dragEnd.accept(commit);
                else if (commit) action.run();
                else animate().translationY(0).setDuration(Ui.motionEnabled() ? Ui.MOTION_PANEL : 0).setInterpolator(Ui.EASING).start();
            }
            return true;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
    }

}
