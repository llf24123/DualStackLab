package com.dualstack.lab;

import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.ListView;

final class SwipeSelectListView extends ListView {
    interface Selection {
        boolean selected(int position);
        void change(int first, int last, boolean checked);
        void gesture(boolean active);
    }

    private Selection selection;
    private final int slop, edgeSize;
    private float downX, downY, pointerY;
    private int start = INVALID_POSITION, last = INVALID_POSITION;
    private boolean selecting, checked, vertical;
    private long downTime = -1;
    private final Runnable scrollSelection = new Runnable() {
        @Override public void run() {
            if (!selecting) return;
            int delta = pointerY < getPaddingTop() + edgeSize ? -Ui.dp(getContext(), 7)
                    : pointerY > getHeight() - getPaddingBottom() - edgeSize ? Ui.dp(getContext(), 7) : 0;
            if (delta != 0 && canScrollVertically(delta)) {
                scrollListBy(delta);
                updateRange();
            }
            postOnAnimation(this);
        }
    };

    SwipeSelectListView(Context context) {
        super(context);
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        edgeSize = Ui.dp(context, 32);
    }

    void setSelectionListener(Selection listener) { selection = listener; }

    void stopForNavigation() {
        finishGesture();
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 0, 0, 0);
        MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
        super.onTouchEvent(down); super.onTouchEvent(cancel);
        down.recycle(); cancel.recycle();
        animate().cancel(); clearAnimation(); setAlpha(1f); setTranslationY(0); setLayerType(LAYER_TYPE_NONE, null);
    }

    @Override public boolean performClick() { return super.performClick(); }

    private void track(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && downTime != event.getDownTime()) {
            finishGesture();
            downTime = event.getDownTime();
            downX = event.getX(); downY = event.getY(); pointerY = downY;
            start = pointToPosition((int) downX, (int) downY); last = start; vertical = false;
        } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE && !selecting && !vertical) {
            float dx = Math.abs(event.getX() - downX), dy = Math.abs(event.getY() - downY);
            if (dy > slop && dy >= dx) {
                vertical = true; getParent().requestDisallowInterceptTouchEvent(false);
            } else if (start >= 0 && selection != null && dx > Math.max(slop * 2, Ui.dp(getContext(), 24))
                    && dx > dy * 1.4f) {
                selecting = true; checked = !selection.selected(start);
                MotionEvent cancel = MotionEvent.obtain(event); cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.onTouchEvent(cancel); cancel.recycle();
                getParent().requestDisallowInterceptTouchEvent(true);
                selection.gesture(true);
                selection.change(start, start, checked);
                postOnAnimation(scrollSelection);
            }
        }
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        track(event);
        if (selecting) return true;
        return super.onInterceptTouchEvent(event);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        track(event);
        if (selecting) {
            pointerY = event.getY();
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) updateRange();
            else if (event.getActionMasked() == MotionEvent.ACTION_UP) { updateRange(); finishGesture(); }
            else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL
                    || event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) finishGesture();
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL)
            finishGesture();
        return super.onTouchEvent(event);
    }

    private void updateRange() {
        int y = Math.max(getPaddingTop(), Math.min(getHeight() - getPaddingBottom() - 1, (int) pointerY));
        int position = pointToPosition(getWidth() / 2, y);
        if (position > 0 && position != last) {
            selection.change(Math.min(last, position), Math.max(last, position), checked);
            last = position;
        }
    }

    void finishGesture() {
        removeCallbacks(scrollSelection);
        if (selecting && selection != null) selection.gesture(false);
        selecting = false;
        start = last = INVALID_POSITION;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
    }

    @Override protected void onDetachedFromWindow() {
        finishGesture();
        super.onDetachedFromWindow();
    }
}
