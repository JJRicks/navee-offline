package local.navee.offlinelock;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.os.Bundle;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A short vertical list reordered by grab handles. The held row lifts and follows the finger,
 * its neighbours glide aside to show where it will land, and it settles into that slot on release.
 * Screen readers get Move up / Move down actions on every row instead of dragging.
 */
final class ReorderList extends LinearLayout {
    interface Listener { void reordered(List<String> ids); }

    private static final int MOVE_UP = R.id.reorder_move_up, MOVE_DOWN = R.id.reorder_move_down;
    private final Ui ui;
    private final Listener listener;
    private final ArrayList<String> ids = new ArrayList<>();
    private final ArrayList<String> spoken = new ArrayList<>();
    private View held;
    private int from, to, pointer = -1;
    private float downY;
    private int[] restingTops = new int[0];

    ReorderList(Ui ui, Listener listener) {
        super(ui.context);
        this.ui = ui; this.listener = listener;
        setOrientation(VERTICAL); setClipChildren(false); setClipToPadding(false);
    }

    List<String> order() { return Collections.unmodifiableList(ids); }

    /** {@code said} is how screen readers name the row (the visible label can carry symbols). */
    void add(String id, String label, String said) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(ui.dp(56)); row.setPadding(ui.dp(16), ui.dp(6), ui.dp(4), ui.dp(6));
        row.setBackground(ui.shape(ui.raised, 14, 0));
        TextView name = ui.text(label, 15, false);
        name.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(name, new LayoutParams(0, -2, 1));
        FrameLayout handle = new FrameLayout(getContext());
        handle.addView(ui.icon("grip", 20, ui.muted), new FrameLayout.LayoutParams(ui.dp(20), ui.dp(20), Gravity.CENTER));
        handle.setContentDescription("Drag to reorder " + said);
        // Dragging is for touch; screen readers reach the row itself and its Move actions.
        handle.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        handle.setOnTouchListener((view, event) -> drag(row, event));
        row.addView(handle, new LayoutParams(ui.dp(48), ui.dp(48)));
        row.setAccessibilityDelegate(new AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                int index = indexOfChild(host);
                if (index > 0) info.addAction(new AccessibilityNodeInfo.AccessibilityAction(MOVE_UP, "Move up"));
                if (index >= 0 && index < getChildCount() - 1)
                    info.addAction(new AccessibilityNodeInfo.AccessibilityAction(MOVE_DOWN, "Move down"));
            }
            @Override public boolean performAccessibilityAction(View host, int action, Bundle arguments) {
                int index = indexOfChild(host);
                if (action == MOVE_UP && index > 0) { move(index, index - 1, true); return true; }
                if (action == MOVE_DOWN && index >= 0 && index < getChildCount() - 1) { move(index, index + 1, true); return true; }
                return super.performAccessibilityAction(host, action, arguments);
            }
        });
        LayoutParams params = new LayoutParams(-1, -2);
        params.bottomMargin = ui.dp(8);
        addView(row, params);
        row.setFocusable(true); row.setContentDescription(said);
        ids.add(id); spoken.add(said);
    }

    private boolean drag(View row, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (held != null) return false;
                held = row; from = to = indexOfChild(row); pointer = event.getPointerId(0); downY = event.getRawY();
                restingTops = new int[getChildCount()];
                for (int i = 0; i < getChildCount(); i++) restingTops[i] = getChildAt(i).getTop();
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                row.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                row.animate().cancel();
                if (Motion.enabled())
                    row.animate().scaleX(1.03f).scaleY(1.03f).translationZ(ui.dp(10)).setDuration(Motion.EXIT_MS)
                            .setInterpolator(Motion.STANDARD).setListener(null).start();
                else row.setTranslationZ(ui.dp(10));
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (held != row) return false;
                int index = event.findPointerIndex(pointer);
                if (index < 0) return true;
                // Only the finger that lifted the row moves it. getRawY(int) needs API 29, and
                // every pointer shares this view's transform, so offset from the first pointer.
                float dy = event.getRawY() + (event.getY(index) - event.getY(0)) - downY;
                // Keep the held row within the list: its top stops at the first row's top, its bottom at the last row's bottom.
                View last = getChildAt(getChildCount() - 1);
                float lowest = restingTops[0] - restingTops[from];
                float highest = restingTops[getChildCount() - 1] + last.getHeight() - restingTops[from] - row.getHeight();
                dy = Math.max(lowest, Math.min(Math.max(lowest, highest), dy));
                row.setTranslationY(dy);
                // A neighbour gives way once the held row's leading edge passes its middle,
                // so rows of different heights (wrapped labels, large text) still trade places.
                float top = restingTops[from] + dy, bottom = top + row.getHeight();
                int target = from;
                for (int i = 0; i < getChildCount(); i++) {
                    float middle = restingTops[i] + getChildAt(i).getHeight() / 2f;
                    if (i < from && middle > top) target--;
                    else if (i > from && middle < bottom) target++;
                }
                if (target != to) {
                    to = target;
                    row.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    int[] slots = slots(from, to);
                    for (int i = 0; i < getChildCount(); i++) {
                        if (i == from) continue;
                        View other = getChildAt(i);
                        float shift = slots[i] - restingTops[i];
                        if (Motion.enabled()) other.animate().translationY(shift).setDuration(Motion.CHANGE_MS)
                                .setInterpolator(Motion.STANDARD).setListener(null).start();
                        else other.setTranslationY(shift);
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP:
                if (held != row) return false;
                if (event.getPointerId(event.getActionIndex()) != pointer) return true;
                // The dragging finger lifted while another stays down: drop here.
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (held != row || pointer < 0) return held == row;
                pointer = -1;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                // Only a real release drops the row; a cancelled gesture puts everything back.
                settle(row, event.getActionMasked() != MotionEvent.ACTION_CANCEL);
                return true;
            default:
                return held == row;
        }
    }

    /** Resting top of every current child once {@code moving} sits at index {@code target}. */
    private int[] slots(int moving, int target) {
        ArrayList<Integer> order = new ArrayList<>();
        for (int i = 0; i < getChildCount(); i++) if (i != moving) order.add(i);
        order.add(target, moving);
        int[] tops = new int[getChildCount()];
        int y = restingTops.length > 0 ? restingTops[0] : getPaddingTop();
        for (int index : order) {
            View child = getChildAt(index);
            tops[index] = y;
            y += child.getHeight() + ((LayoutParams) child.getLayoutParams()).bottomMargin;
        }
        return tops;
    }

    /** Lands the held row in its new slot ({@code drop}) or back where it started, saving only a drop. */
    private void settle(View row, boolean drop) {
        if (!drop) to = from;
        int[] slots = slots(from, to);
        float landing = slots[from] - restingTops[from];
        Runnable commit = () -> {
            row.setScaleX(1); row.setScaleY(1); row.setTranslationZ(0);
            for (int i = 0; i < getChildCount(); i++) { getChildAt(i).animate().cancel(); getChildAt(i).setTranslationY(0); }
            int moved = from, target = to;
            held = null;
            if (moved != target) {
                reorder(moved, target);
                announceForAccessibility(spoken.get(target) + " moved to position " + (target + 1) + " of " + ids.size());
                if (listener != null) listener.reordered(order());
            }
        };
        if (!Motion.enabled()) { commit.run(); return; }
        if (!drop) for (int i = 0; i < getChildCount(); i++) {
            if (i != from) getChildAt(i).animate().translationY(0).setDuration(Motion.CHANGE_MS)
                    .setInterpolator(Motion.STANDARD).setListener(null).start();
        }
        row.animate().translationY(landing).scaleX(1).scaleY(1).translationZ(0).setDuration(Motion.CHANGE_MS)
                .setInterpolator(Motion.STANDARD).setListener(new AnimatorListenerAdapter() {
                    boolean done;
                    @Override public void onAnimationEnd(Animator animation) { if (!done) { done = true; commit.run(); } }
                    @Override public void onAnimationCancel(Animator animation) { if (!done) { done = true; commit.run(); } }
                }).start();
    }

    private void reorder(int moved, int target) {
        View child = getChildAt(moved);
        removeViewAt(moved); addView(child, target);
        ids.add(target, ids.remove(moved)); spoken.add(target, spoken.remove(moved));
    }

    /** Screen-reader moves: rows glide from their old place to the new one. */
    private void move(int moved, int target, boolean notify) {
        int[] before = new int[getChildCount()];
        View[] children = new View[getChildCount()];
        for (int i = 0; i < getChildCount(); i++) { children[i] = getChildAt(i); before[i] = children[i].getTop(); }
        reorder(moved, target);
        if (Motion.enabled()) {
            Motion.beforeNextDraw(this, () -> {
                for (int i = 0; i < children.length; i++) {
                    View child = children[i];
                    child.setTranslationY(before[i] - child.getTop());
                    child.animate().translationY(0).setDuration(Motion.CHANGE_MS).setInterpolator(Motion.STANDARD).setListener(null).start();
                }
            }, null);
        }
        announceForAccessibility(spoken.get(target) + " moved to position " + (target + 1) + " of " + ids.size());
        if (notify && listener != null) listener.reordered(order());
    }
}
