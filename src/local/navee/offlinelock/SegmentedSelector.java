package local.navee.offlinelock;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.PathInterpolator;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.function.IntConsumer;

/** A shared rounded track with readable choices and a short sliding selection. */
public final class SegmentedSelector extends HorizontalScrollView {
    private final Ui ui;
    private final Track track;

    public SegmentedSelector(Ui ui, String title, String[] labels, IntConsumer onSelected) {
        super(ui.context);
        if (labels.length == 0) throw new IllegalArgumentException("A selector needs choices");
        this.ui = ui;
        setFillViewport(true);
        setHorizontalScrollBarEnabled(false);
        setHorizontalFadingEdgeEnabled(true);
        setFadingEdgeLength(ui.dp(16));
        setClipToOutline(true);
        setBackground(ui.shape(ui.dark ? ui.bg : ui.raised, 14, ui.outline));
        // Natural text widths and a wrapping height preserve large text. A narrow screen scrolls
        // the whole track instead of shrinking labels or reducing their touch targets.
        track = new Track(title, labels, onSelected);
        addView(track, new HorizontalScrollView.LayoutParams(-2, -2));
    }

    /** Dense section labels can use less horizontal padding while retaining full-height targets. */
    public void useCompactPadding() {
        for (TextView button : track.choices)
            button.setPadding(ui.dp(4), button.getPaddingTop(), ui.dp(4), button.getPaddingBottom());
        requestLayout();
    }

    public void select(int selected, boolean animate) {
        // -1 represents an unknown/unreported scooter setting, not the first choice.
        int next = Math.max(-1, Math.min(track.choices.size() - 1, selected));
        if (track.selected == next) {
            if (!Ui.motionEnabled()) track.finishMotion();
            return;
        }
        track.selected = next;
        track.updateSelection(animate);
        if (next >= 0 && track.getWidth() > 0) revealSelection(animate);
    }

    @Override public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (track != null) for (TextView button : track.choices) button.setEnabled(enabled);
    }

    /** The containing screen can settle this before it is removed or rebuilt. */
    public void finishMotion() { track.finishMotion(); }

    private void revealSelection(boolean animate) {
        TextView button = track.choices.get(track.selected);
        int target = Math.max(0, button.getLeft() - (getWidth() - button.getWidth()) / 2);
        if (animate && Ui.motionEnabled()) smoothScrollTo(target, 0);
        else scrollTo(target, 0);
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused && track != null) finishMotion();
    }

    @Override protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        if (track != null && !isShown()) finishMotion();
    }

    @Override protected void onDetachedFromWindow() {
        finishMotion();
        super.onDetachedFromWindow();
    }

    private final class Track extends LinearLayout {
        final ArrayList<TextView> choices = new ArrayList<>();
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF indicator = new RectF();
        ValueAnimator animation;
        int selected = -1;

        Track(String title, String[] labels, IntConsumer onSelected) {
            super(ui.context);
            setOrientation(HORIZONTAL);
            setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4));
            setWillNotDraw(false);
            for (int i = 0; i < labels.length; i++) {
                final int choice = i;
                TextView button = ui.text(labels[i], 13, true);
                button.setGravity(Gravity.CENTER);
                button.setSingleLine(true);
                button.setMinWidth(ui.dp(48));
                button.setMinHeight(ui.dp(48));
                button.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
                button.setFocusable(true);
                button.setClickable(true);
                button.setContentDescription(title + ": " + labels[i]);
                button.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 28)),
                        null, ui.shape(Color.WHITE, 14, 0)));
                button.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                    @Override public void onInitializeAccessibilityNodeInfo(View view, AccessibilityNodeInfo info) {
                        super.onInitializeAccessibilityNodeInfo(view, info);
                        info.setClassName("android.widget.RadioButton");
                        info.setCheckable(true); info.setChecked(view.isSelected());
                        info.setCollectionItemInfo(AccessibilityNodeInfo.CollectionItemInfo.obtain(0, 1, choice, 1, false, view.isSelected()));
                    }
                });
                button.setOnClickListener(v -> {
                    if (!SegmentedSelector.this.isEnabled() || !button.isEnabled()) return;
                    if (selected == choice) return;
                    select(choice, true);
                    if (onSelected != null) onSelected.accept(choice);
                });
                choices.add(button);
                addView(button, new LinearLayout.LayoutParams(-2, -2, 1));
            }
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setCollectionInfo(AccessibilityNodeInfo.CollectionInfo.obtain(1, choices.size(), false,
                    AccessibilityNodeInfo.CollectionInfo.SELECTION_MODE_SINGLE));
        }

        private RectF target() {
            if (selected < 0) return new RectF();
            TextView button = choices.get(selected);
            return new RectF(button.getLeft(), button.getTop(), button.getRight(), button.getBottom());
        }

        void finishMotion() {
            if (animation != null) { ValueAnimator old = animation; animation = null; old.cancel(); }
            indicator.set(target()); invalidate();
        }

        void updateSelection(boolean animate) {
            for (int i = 0; i < choices.size(); i++) {
                TextView button = choices.get(i);
                button.setSelected(i == selected);
                button.setTextColor(i == selected ? ui.onAccent : ui.text);
                if (Build.VERSION.SDK_INT >= 30) button.setStateDescription(i == selected ? "Selected" : "");
            }
            RectF end = target();
            if (animation != null) { ValueAnimator old = animation; animation = null; old.cancel(); }
            if (end.isEmpty()) { indicator.setEmpty(); invalidate(); return; }
            if (!animate || indicator.isEmpty() || !isShown() || !isAttachedToWindow() || !Ui.motionEnabled()) {
                indicator.set(end); invalidate(); return;
            }
            RectF start = new RectF(indicator);
            final ValueAnimator motion = ValueAnimator.ofFloat(0, 1);
            animation = motion;
            motion.setDuration(190);
            motion.setInterpolator(new PathInterpolator(.2f, 0, 0, 1));
            motion.addUpdateListener(a -> {
                if (!Ui.motionEnabled()) { finishMotion(); return; }
                float f = (float) a.getAnimatedValue();
                indicator.set(start.left + (end.left - start.left) * f,
                        start.top + (end.top - start.top) * f,
                        start.right + (end.right - start.right) * f,
                        start.bottom + (end.bottom - start.bottom) * f);
                invalidate();
            });
            motion.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator ended) {
                    if (animation == ended) { animation = null; indicator.set(target()); invalidate(); }
                }
            });
            motion.start();
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            if (changed || indicator.isEmpty()) {
                updateSelection(false);
                if (selected >= 0) revealSelection(false);
            }
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            paint.setShader(new LinearGradient(0, 0, 0, ui.dp(10),
                    Color.argb(ui.dark ? 80 : 24, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(0, 0, getWidth(), getHeight(), ui.dp(14), ui.dp(14), paint);
            paint.setShader(null); paint.setColor(ui.accent);
            canvas.drawRoundRect(indicator, ui.dp(14), ui.dp(14), paint);
        }
    }
}
