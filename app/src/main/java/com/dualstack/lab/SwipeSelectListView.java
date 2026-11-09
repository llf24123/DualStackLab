package com.dualstack.lab;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.animation.OvershootInterpolator;
import android.widget.ListView;

final class SwipeSelectListView extends ListView {
    interface Selection {
        boolean selected(int position);
        void change(int first, int last, boolean checked);
        void gesture(boolean active);
        void pull(float distance);
        void refresh();
    }

    private Selection selection;
    private final int slop, edgeSize;
    private final float maxPull, refreshAt;
    private float downX, downY, pointerY, pull;
    private int start = INVALID_POSITION, last = INVALID_POSITION;
    private boolean selecting, checked, vertical, pulling;
    private long downTime = -1;
    private ValueAnimator rebound;
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
        maxPull = Ui.dp(context, 132);
        refreshAt = Ui.dp(context, 64);
        setOverScrollMode(OVER_SCROLL_NEVER);
    }

    void setSelectionListener(Selection listener) { selection = listener; }

    void stopForNavigation() {
        finishGesture(); cancelRebound();
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 0, 0, 0);
        MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
        super.onTouchEvent(down); super.onTouchEvent(cancel);
        down.recycle(); cancel.recycle();
        animate().cancel(); clearAnimation(); setAlpha(1f); setTranslationX(0f);
        setLayerType(LAYER_TYPE_NONE, null);
        pulling = false; offset(0f);
    }

    void stopForRefresh() {
        finishGesture(); cancelRebound();
        animate().cancel(); clearAnimation(); setAlpha(1f); setTranslationX(0f);
        pulling = false; offset(0f);
    }

    private float damped(float distance) {
        return maxPull * (1f - 1f / (Math.max(0f, distance) / maxPull + 1f));
    }

    private boolean atTop() {
        return !canScrollVertically(-1);
    }

    private void offset(float value) {
        pull = value;
        setTranslationY(value);
        if (selection != null) selection.pull(Math.max(0f, value));
    }

    private void cancelRebound() {
        if (rebound != null) { rebound.cancel(); rebound = null; }
    }

    private void releasePull(boolean confirmed) {
        float released = pull;
        boolean refresh = confirmed && released >= refreshAt;
        pulling = false;
        finishGesture();
        cancelRebound();
        if (refresh && selection != null) selection.refresh();
        rebound = ValueAnimator.ofFloat(released, 0f);
        rebound.setDuration(Ui.motionEnabled() ? Ui.MOTION_PANEL : 0);
        rebound.setInterpolator(Ui.EASING);
        rebound.addUpdateListener(value -> offset((float) value.getAnimatedValue()));
        rebound.start();
    }

    @Override public boolean performClick() { return super.performClick(); }

    private void track(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && downTime != event.getDownTime()) {
            finishGesture(); cancelRebound();
            animate().cancel(); setTranslationX(0f); setAlpha(1f); offset(0f);
            downTime = event.getDownTime();
            downX = event.getRawX(); downY = event.getRawY(); pointerY = event.getY();
            start = pointToPosition((int) event.getX(), (int) event.getY());
            last = start; vertical = false; pulling = false;
        } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE && !selecting) {
            // Screen coordinates stay stable while the list itself is translated under the finger.
            float dx = Math.abs(event.getRawX() - downX), dy = event.getRawY() - downY;
            if (dx > slop && dx > Math.abs(dy)) cancelPendingInputEvents();
            // Any early vertical travel marks the stroke as a scroll, even if it later drifts sideways.
            if (!vertical && Math.abs(dy) > slop && Math.abs(dy) * 1.2f >= dx) vertical = true;
            if (pulling || vertical && dy > slop && atTop()) {
                if (!pulling) {
                    pulling = true;
                    MotionEvent cancel = MotionEvent.obtain(event); cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.onTouchEvent(cancel); cancel.recycle();
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                offset(damped(dy));
            } else if (!vertical && start >= 0 && selection != null
                    && dx > Math.max(slop * 3, Ui.dp(getContext(), 40)) && dx > Math.abs(dy) * 2.2f) {
                // A deliberate, mostly horizontal stroke starts selection; a slanted scroll does not.
                selecting = true; checked = !selection.selected(start);
                performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
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
        if (selecting || pulling) return true;
        return super.onInterceptTouchEvent(event);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        track(event);
        if (pulling) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_UP) releasePull(true);
            else if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN) releasePull(false);
            return true;
        }
        if (selecting) {
            pointerY = event.getY();
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) updateRange();
            else if (event.getActionMasked() == MotionEvent.ACTION_UP) { updateRange(); finishGesture(); }
            else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL
                    || event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) finishGesture();
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            boolean sideways = event.getActionMasked() == MotionEvent.ACTION_UP && !vertical
                    && Math.abs(event.getRawX() - downX) > slop && Math.abs(event.getRawY() - downY) <= slop;
            finishGesture();
            if (sideways) {
                MotionEvent cancel = MotionEvent.obtain(event); cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.onTouchEvent(cancel); cancel.recycle(); return true;
            }
        }
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
        finishGesture(); cancelRebound();
        super.onDetachedFromWindow();
    }
}
