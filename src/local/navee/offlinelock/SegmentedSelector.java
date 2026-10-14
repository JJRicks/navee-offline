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
        setClipToOutline(true);
        setBackground(ui.shape(ui.dark ? ui.bg : ui.raised, 14, ui.outline));
        // Natural text widths and a wrapping height preserve large text. A narrow screen scrolls
        // the whole track instead of shrinking labels or reducing their touch targets.
        track = new Track(title, labels, onSelected);
        addView(track, new HorizontalScrollView.LayoutParams(-2, -2));
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

    /** Inline controls can show every choice without horizontal scrolling or smaller text. */
    public void setWrapChoices(boolean wrap) {
        track.wrapChoices = wrap;
        for (TextView button : track.choices) button.setSingleLine(!wrap);
        requestLayout();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        track.wrapWidth = track.wrapChoices && MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED
                ? Math.max(0, MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight()) : 0;
        super.onMeasure(widthSpec, heightSpec);
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
        boolean wrapChoices;
        int wrapWidth, rowCount = 1, columnCount;
        final ArrayList<android.graphics.Rect> bounds = new ArrayList<>();
        final ArrayList<Integer> rows = new ArrayList<>(), columns = new ArrayList<>();

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
                        info.setCollectionItemInfo(AccessibilityNodeInfo.CollectionItemInfo.obtain(rows.get(choice), 1, columns.get(choice), 1, false, view.isSelected()));
                    }
                });
                button.setOnClickListener(v -> {
                    if (!SegmentedSelector.this.isEnabled() || !button.isEnabled()) return;
                    if (selected == choice) return;
                    select(choice, true);
                    if (onSelected != null) onSelected.accept(choice);
                });
                choices.add(button);
                bounds.add(new android.graphics.Rect()); rows.add(0); columns.add(i);
                addView(button, new LinearLayout.LayoutParams(-2, -2, 1));
            }
            columnCount = choices.size();
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            if (!wrapChoices || wrapWidth == 0) { super.onMeasure(widthSpec, heightSpec); return; }
            int available = Math.max(1, wrapWidth - getPaddingLeft() - getPaddingRight());
            int naturalTotal = 0, wordMinimum = ui.dp(48);
            int[] natural = new int[choices.size()];
            for (int i = 0; i < choices.size(); i++) {
                TextView button = choices.get(i);
                button.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                natural[i] = button.getMeasuredWidth(); naturalTotal += natural[i];
                // Keep whole words readable at the user's font size. Only the opted-in lights
                // use this fit-or-wrap path; the other shared selectors retain their layout.
                for (String word : button.getText().toString().split("\\s+")) wordMinimum = Math.max(wordMinimum,
                        (int)Math.ceil(button.getPaint().measureText(word)) + button.getCompoundPaddingLeft() + button.getCompoundPaddingRight());
            }
            boolean single = naturalTotal <= available;
            columnCount = single ? choices.size() : Math.max(1, Math.min(choices.size(), available / wordMinimum));
            rowCount = (choices.size() + columnCount - 1) / columnCount;
            int y = getPaddingTop();
            for (int first = 0, row = 0; first < choices.size(); first += columnCount, row++) {
                int count = Math.min(columnCount, choices.size() - first), x = getPaddingLeft(), rowHeight = 0;
                int extra = single ? available - naturalTotal : 0;
                for (int col = 0; col < count; col++) {
                    int index = first + col;
                    int width = single ? natural[index] + extra / count + (col < extra % count ? 1 : 0)
                            : available / count + (col < available % count ? 1 : 0);
                    TextView button = choices.get(index);
                    button.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                    rowHeight = Math.max(rowHeight, button.getMeasuredHeight());
                    bounds.get(index).set(x, y, x + width, y); rows.set(index, row); columns.set(index, col); x += width;
                }
                for (int col = 0; col < count; col++) {
                    int index = first + col; android.graphics.Rect box = bounds.get(index); box.bottom = y + rowHeight;
                    choices.get(index).measure(MeasureSpec.makeMeasureSpec(box.width(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY));
                }
                y += rowHeight;
            }
            setMeasuredDimension(wrapWidth, y + getPaddingBottom());
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setCollectionInfo(AccessibilityNodeInfo.CollectionInfo.obtain(rowCount, columnCount, false,
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
            if (wrapChoices && wrapWidth > 0) {
                for (int i = 0; i < choices.size(); i++) {
                    android.graphics.Rect box = bounds.get(i); TextView button = choices.get(i);
                    int left = getLayoutDirection() == LAYOUT_DIRECTION_RTL ? getWidth() - box.right : box.left;
                    button.layout(left, box.top, left + box.width(), box.bottom);
                }
            } else super.onLayout(changed, l, t, r, b);
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
