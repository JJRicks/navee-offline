package com.jjricks.volt;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.animation.PathInterpolator;
import android.widget.ScrollView;

/** A bounded, drawing-only arrival cue for a newly added dashboard card. */
public final class WidgetReveal implements View.OnAttachStateChangeListener,
        ViewTreeObserver.OnWindowFocusChangeListener, ViewTreeObserver.OnPreDrawListener {
    private static final long SCROLL_MS = 320;
    private static final long GLOW_MS = 1000;
    private static final long MAX_LIFETIME_MS = 1900;

    private final View target;
    private final ScrollView outer;
    private final Ui ui;
    private final Glow glow;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeout = this::cancel;
    private final ViewTreeObserver observer;
    private final RectF initialContentBounds;
    private final int initialWidth, initialHeight, outerWidth, outerHeight;
    private ValueAnimator scrolling, highlighting;
    private boolean finished, overlayAdded;

    private WidgetReveal(View target, ScrollView outer, Ui ui, RectF contentBounds) {
        this.target = target;
        this.outer = outer;
        this.ui = ui;
        initialContentBounds = new RectF(contentBounds);
        initialWidth = target.getWidth();
        initialHeight = target.getHeight();
        outerWidth = outer.getWidth();
        outerHeight = outer.getHeight();
        glow = new Glow(initialWidth, initialHeight, ui);
        observer = outer.getViewTreeObserver();
    }

    /**
     * Call on the main thread, after the saved card has been laid out and its sheet has
     * dismissed and returned window focus. Already-visible cards never cause scrolling.
     * Off-screen cards are brought to their top, including cards taller than the viewport.
     *
     * Returns null if the card is unavailable. Keep the handle and call cancel() on any
     * touch, dashboard render, new action or screen close. This helper deliberately does
     * not install touch listeners, intercept gestures or change card measurement.
     */
    public static WidgetReveal start(View target, ScrollView outer, Ui ui) {
        if (Looper.myLooper() != Looper.getMainLooper() || target == null || outer == null
                || ui == null || target == outer || !usable(target) || !usable(outer)
                || !outer.hasWindowFocus() || outer.getChildCount() == 0) return null;
        RectF bounds = contentBounds(target, outer);
        if (bounds == null) return null;

        WidgetReveal effect = new WidgetReveal(target, outer, ui, bounds);
        try {
            target.addOnAttachStateChangeListener(effect);
            outer.addOnAttachStateChangeListener(effect);
            effect.observer.addOnWindowFocusChangeListener(effect);
            effect.observer.addOnPreDrawListener(effect);
            effect.handler.postDelayed(effect.timeout, MAX_LIFETIME_MS);
            effect.begin(bounds);
            return effect;
        } catch (RuntimeException ignored) {
            // A decorative cue must never interfere with saving the new widget.
            effect.cancel();
            return null;
        }
    }

    private void begin(RectF bounds) {
        float viewportTop = outer.getScrollY() + outer.getPaddingTop();
        float viewportBottom = outer.getScrollY() + outer.getHeight() - outer.getPaddingBottom();
        float viewportLeft = outer.getScrollX() + outer.getPaddingLeft();
        float viewportRight = outer.getScrollX() + outer.getWidth() - outer.getPaddingRight();
        boolean visible = RectF.intersects(bounds,
                new RectF(viewportLeft, viewportTop, viewportRight, viewportBottom));
        int from = outer.getScrollY();
        int destination = from;
        if (!visible) {
            View child = outer.getChildAt(0);
            int contentHeight = child.getHeight();
            if (child.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) child.getLayoutParams();
                contentHeight += margins.topMargin + margins.bottomMargin;
            }
            int maximum = Math.max(0,
                    contentHeight - (outer.getHeight() - outer.getPaddingTop() - outer.getPaddingBottom()));
            destination = Math.max(0, Math.min(maximum,
                    Math.round(bounds.top) - outer.getPaddingTop() - ui.dp(12)));
        }
        if (destination == from || !Ui.motionEnabled()) {
            if (destination != from) outer.scrollTo(outer.getScrollX(), destination);
            highlight();
            return;
        }
        scrolling = ValueAnimator.ofInt(from, destination);
        scrolling.setDuration(SCROLL_MS);
        scrolling.setInterpolator(new PathInterpolator(.2f, 0f, .2f, 1f));
        scrolling.addUpdateListener(animation -> {
            if (!valid() || !Ui.motionEnabled()) {
                cancel();
                return;
            }
            outer.scrollTo(outer.getScrollX(), (Integer) animation.getAnimatedValue());
        });
        scrolling.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (!finished) highlight();
            }
        });
        scrolling.start();
    }

    private void highlight() {
        if (!valid()) {
            cancel();
            return;
        }
        if (overlayAdded) return;
        overlayAdded = true;
        target.getOverlay().add(glow);
        if (!Ui.motionEnabled()) {
            // Remove-animation mode gets a brief steady border and immediate positioning.
            glow.strength = .8f;
            glow.invalidateSelf();
            handler.removeCallbacks(timeout);
            handler.postDelayed(timeout, 850);
            return;
        }
        highlighting = ValueAnimator.ofFloat(0f, 1f);
        highlighting.setDuration(GLOW_MS);
        highlighting.setInterpolator(new android.view.animation.LinearInterpolator());
        highlighting.addUpdateListener(animation -> {
            if (!valid() || !Ui.motionEnabled()) {
                cancel();
                return;
            }
            float t = (Float) animation.getAnimatedValue();
            // Rise quickly enough to locate the card, then let its normal chrome return.
            float phase = t < .25f ? t / .25f : 1f - (t - .25f) / .75f;
            glow.strength = phase * phase * (3f - 2f * phase);
            glow.invalidateSelf();
        });
        highlighting.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { cancel(); }
        });
        highlighting.start();
    }

    /** Idempotent, including before the cue finishes scrolling. */
    public void cancel() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(this::cancel);
            return;
        }
        if (finished) return;
        finished = true;
        handler.removeCallbacks(timeout);
        target.removeOnAttachStateChangeListener(this);
        outer.removeOnAttachStateChangeListener(this);
        if (observer.isAlive()) {
            observer.removeOnWindowFocusChangeListener(this);
            observer.removeOnPreDrawListener(this);
        }
        stop(scrolling);
        stop(highlighting);
        scrolling = null;
        highlighting = null;
        if (overlayAdded) {
            overlayAdded = false;
            target.getOverlay().remove(glow);
        }
    }

    private static void stop(ValueAnimator animator) {
        if (animator == null) return;
        animator.removeAllUpdateListeners();
        animator.removeAllListeners();
        animator.cancel();
    }

    private boolean valid() {
        if (finished || !usable(target) || !usable(outer) || !outer.hasWindowFocus()
                || initialWidth != target.getWidth() || initialHeight != target.getHeight()
                || outerWidth != outer.getWidth() || outerHeight != outer.getHeight()) return false;
        RectF current = contentBounds(target, outer);
        // Scrolling is allowed; a reflow or an edit makes this arrival cue obsolete.
        return current != null && Math.abs(current.left - initialContentBounds.left) < 1f
                && Math.abs(current.top - initialContentBounds.top) < 1f
                && Math.abs(current.right - initialContentBounds.right) < 1f
                && Math.abs(current.bottom - initialContentBounds.bottom) < 1f;
    }

    private static boolean usable(View view) {
        return view.isAttachedToWindow() && view.isShown()
                && view.getWidth() > 0 && view.getHeight() > 0;
    }

    private static RectF contentBounds(View target, ScrollView outer) {
        Matrix transform = new Matrix();
        View current = target;
        while (current != outer) {
            transform.postConcat(current.getMatrix());
            transform.postTranslate(current.getLeft(), current.getTop());
            ViewParent parent = current.getParent();
            if (!(parent instanceof View)) return null;
            current = (View) parent;
            transform.postTranslate(-current.getScrollX(), -current.getScrollY());
        }
        RectF bounds = new RectF(0, 0, target.getWidth(), target.getHeight());
        transform.mapRect(bounds);
        bounds.offset(outer.getScrollX(), outer.getScrollY());
        return Float.isFinite(bounds.left) && Float.isFinite(bounds.top)
                && Float.isFinite(bounds.right) && Float.isFinite(bounds.bottom) ? bounds : null;
    }

    @Override public void onViewAttachedToWindow(View view) { }
    @Override public void onViewDetachedFromWindow(View view) { cancel(); }
    @Override public void onWindowFocusChanged(boolean hasFocus) { if (!hasFocus) cancel(); }
    @Override public boolean onPreDraw() { if (!valid()) cancel(); return true; }

    /** Several soft strokes give an inexpensive halo without a bitmap or software layer. */
    private static final class Glow extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF outline;
        private final float radius, unit;
        private float strength;

        Glow(int width, int height, Ui ui) {
            unit = Math.max(1, ui.dp(1));
            float inset = ui.dp(5);
            outline = new RectF(inset, inset, width - inset, height - inset);
            radius = Math.min(ui.dp(18), Math.min(outline.width(), outline.height()) / 2f);
            setBounds(0, 0, width, height);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(Color.WHITE);
        }

        @Override public void draw(Canvas canvas) {
            if (strength <= 0 || outline.isEmpty()) return;
            for (int pass = 4; pass >= 0; pass--) {
                paint.setStrokeWidth(unit * (1.5f + pass * 1.7f));
                paint.setAlpha(Math.round(strength * (pass == 0 ? 210 : 14 + (4 - pass) * 5)));
                canvas.drawRoundRect(outline, radius, radius, paint);
            }
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter filter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
