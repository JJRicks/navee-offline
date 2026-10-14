package local.navee.offlinelock;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.transition.ChangeBounds;
import android.transition.Fade;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.transition.TransitionValues;
import android.transition.Visibility;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import java.util.ArrayList;
import java.util.WeakHashMap;

/**
 * Volt's one motion vocabulary. Arrivals ease out; departures are shorter and accelerate away.
 * Nothing bounces, overshoots or lingers past ~280 ms, and everything collapses to an instant
 * change when Android animations are off (Remove animations / zero scale).
 *
 * Pages are rebuilt or swapped in place, so page changes work from a snapshot of the outgoing
 * frame rather than keeping two live screens. Moving between pages is a fade-through: the old
 * page clears as it starts to leave, then the new one fades in over the rest of its glide, so the
 * two never show through each other. Different content in the same place fades through without
 * travelling; the same content refined (a setting, fuller data) crossfades.
 */
final class Motion {
    private Motion() {}

    static final long EXIT_MS = 150, ENTER_MS = 260, CHANGE_MS = 220, CROSSFADE_MS = 220, PAGE_MS = 280;
    /** Quick start, long gentle settle: everything that arrives or changes size. */
    static final TimeInterpolator STANDARD = new PathInterpolator(.2f, 0f, 0f, 1f);
    /** Even ease for opacity, so a crossfade reads as a fade rather than a blink. */
    static final TimeInterpolator FADE = new PathInterpolator(.4f, 0f, .6f, 1f);
    /** Page travel eases in as well as out, so part of the glide is still visible after the fade-through. */
    private static final TimeInterpolator TRAVEL = new PathInterpolator(.4f, 0f, .2f, 1f);
    private static final TimeInterpolator FADE_OUT = new PathInterpolator(.4f, 0f, 1f, 1f);
    private static final TimeInterpolator FADE_IN = new PathInterpolator(0f, 0f, .2f, 1f);
    /** Share of a page change spent clearing the old page before the new one starts to show. */
    private static final float THROUGH = .35f;
    static final int DIALOG = 0, SHEET = 1, PAGE = 2;
    /** Detach callbacks run while a parent is still detaching its children; cleanup waits a turn. */
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static boolean enabled() { return Ui.motionEnabled(); }

    // ---- Windows -----------------------------------------------------------------------------

    /**
     * After the content is set and before show(): sheets rise from the bottom, full-screen pages
     * slide in, dialogs fade in and up, and the content's scrollbars stay hidden as it appears.
     */
    static void present(Dialog dialog, int kind) {
        Window window = dialog == null ? null : dialog.getWindow();
        if (window == null) return;
        window.setWindowAnimations(kind == SHEET ? R.style.VoltMotion_Sheet
                : kind == PAGE ? R.style.VoltMotion_Page : R.style.VoltMotion_Dialog);
        dialog.create(); // builds the content now so its scrollers can be quieted before they attach
        quietScrollbars(window.getDecorView());
    }

    // ---- Snapshots ---------------------------------------------------------------------------

    /** The view's visible frame (a ScrollView at its current scroll), or null when it cannot be drawn. */
    static Bitmap capture(View view) { return capture(view, false); }

    /**
     * With {@code backdrop}, whatever shows behind the view (its nearest background) is drawn
     * first, so a crossfading snapshot hides the new content instead of letting it show through.
     */
    static Bitmap capture(View view, boolean backdrop) {
        if (view == null || !view.isLaidOut() || !view.isShown() || view.getWidth() <= 0 || view.getHeight() <= 0) return null;
        try {
            Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            if (backdrop) drawBackdrop(view, canvas);
            canvas.translate(-view.getScrollX(), -view.getScrollY());
            view.draw(canvas);
            return bitmap;
        } catch (RuntimeException | OutOfMemoryError unavailable) {
            return null; // A missing snapshot only means this change is instant.
        }
    }

    private static void drawBackdrop(View view, Canvas canvas) {
        int[] at = new int[2], from = new int[2];
        view.getLocationInWindow(at);
        for (ViewParent parent = view.getParent(); parent instanceof View; parent = parent.getParent()) {
            Drawable background = ((View) parent).getBackground();
            if (background == null) continue;
            ((View) parent).getLocationInWindow(from);
            int saved = canvas.save();
            canvas.translate(from[0] - at[0], from[1] - at[1]);
            background.draw(canvas);
            canvas.restoreToCount(saved);
            return;
        }
    }

    // ---- Page changes ------------------------------------------------------------------------

    /** How far pages travel: enough to read as direction, never a full-width swipe. */
    private static float travel(View view, int width) {
        return Math.min(width * .16f, view.getResources().getDisplayMetrics().density * 48);
    }

    /**
     * Runs one change from {@code cover} (a snapshot over the live content) to {@code incoming}.
     * {@code through} is a fade-through: the cover clears in the first part while it starts to
     * leave (toward {@code -shift} when {@code coverTravels}), then the incoming views fade in as
     * they finish gliding from {@code shift}. Otherwise it is a straight crossfade. {@code end}
     * runs exactly once, however the animation stops.
     */
    private static ValueAnimator drive(View cover, boolean coverTravels, boolean through, float shift, View[] incoming, Runnable end) {
        final int[] layers = new int[incoming.length];
        for (int i = 0; i < incoming.length; i++) {
            layers[i] = incoming[i].getLayerType();
            if (through) incoming[i].setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(through ? PAGE_MS : CROSSFADE_MS);
        animator.setInterpolator(new LinearInterpolator()); // each property applies its own curve
        ValueAnimator.AnimatorUpdateListener frame = animation -> {
            float t = animation.getAnimatedFraction();
            if (!through) { cover.setAlpha(1 - FADE.getInterpolation(t)); return; }
            float glide = TRAVEL.getInterpolation(t);
            cover.setAlpha(1 - FADE_OUT.getInterpolation(Math.min(1f, t / THROUGH)));
            if (coverTravels) cover.setTranslationX(-shift * glide);
            float shown = FADE_IN.getInterpolation(Math.max(0f, (t - THROUGH) / (1 - THROUGH)));
            for (View view : incoming) { view.setAlpha(shown); view.setTranslationX(shift * (1 - glide)); }
        };
        animator.addUpdateListener(frame);
        frame.onAnimationUpdate(animator); // the first frame already shows the starting state
        animator.addListener(new AnimatorListenerAdapter() {
            boolean done;
            void finish() {
                if (done) return; done = true;
                for (int i = 0; i < incoming.length; i++) {
                    incoming[i].setAlpha(1); incoming[i].setTranslationX(0);
                    if (incoming[i].getLayerType() != layers[i]) incoming[i].setLayerType(layers[i], null);
                }
                end.run();
            }
            @Override public void onAnimationEnd(Animator animation) { finish(); }
            @Override public void onAnimationCancel(Animator animation) { finish(); }
        });
        return animator;
    }

    /**
     * After a full rebuild of the window ({@code previous} is the old window): in place, the old
     * window crossfades into the new one. Between tabs ({@code direction} non-zero), the old page
     * clears and {@code incoming} (the new header and page) arrive from that side.
     */
    static void crossfade(ViewGroup root, Bitmap previous, int direction, View... incoming) {
        if (previous == null) return;
        if (!enabled() || root == null || root.getWidth() > 0 && root.getWidth() != previous.getWidth()) {
            previous.recycle(); return;
        }
        ImageView cover = new ImageView(root.getContext());
        cover.setImageBitmap(previous);
        cover.layout(0, 0, previous.getWidth(), previous.getHeight());
        root.getOverlay().add(cover);
        ArrayList<View> moving = new ArrayList<>();
        if (direction != 0) for (View view : incoming) if (view != null) moving.add(view);
        // The cover holds the old tab bar too, so it fades without travelling; the bars match.
        float shift = moving.isEmpty() ? 0 : Math.signum(direction) * travel(root, previous.getWidth());
        ValueAnimator animator = drive(cover, false, shift != 0, shift, moving.toArray(new View[0]), () -> {
            root.getOverlay().remove(cover); cover.setImageDrawable(null); previous.recycle();
        });
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {}
            @Override public void onViewDetachedFromWindow(View view) { view.removeOnAttachStateChangeListener(this); MAIN.post(animator::cancel); }
        });
        animator.start();
    }

    private static final WeakHashMap<View, Runnable> running = new WeakHashMap<>();

    /**
     * A change inside one scrolling page (sub-menu, section, sub-page): the outgoing frame leaves
     * toward {@code -direction} while the new content arrives from {@code direction}. Direction 0
     * crossfades in place, for the same content refined. {@code change} runs immediately and
     * exactly once.
     */
    static void page(View pageView, int direction, Runnable change) { swap(pageView, direction, direction != 0, change); }

    /** Different content in the same place (another ride in the detail pane): a fade-through without travel. */
    static void replace(View pageView, Runnable change) { swap(pageView, 0, true, change); }

    private static void swap(View pageView, int direction, boolean through, Runnable change) {
        Runnable previous = running.remove(pageView);
        if (previous != null) previous.run();
        Bitmap snapshot = enabled() ? capture(pageView, !through) : null;
        change.run();
        if (snapshot == null) return;
        ViewGroup parent = pageView.getParent() instanceof ViewGroup ? (ViewGroup) pageView.getParent() : null;
        if (parent == null) { snapshot.recycle(); return; }
        ImageView cover = new ImageView(pageView.getContext());
        cover.setImageBitmap(snapshot);
        cover.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        final boolean sibling = parent instanceof FrameLayout;
        final ViewGroup decor = sibling ? null : (ViewGroup) pageView.getRootView();
        if (sibling) {
            // Directly above the page and below anything floating over it (the tab bar).
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(snapshot.getWidth(), snapshot.getHeight());
            params.leftMargin = pageView.getLeft() - parent.getPaddingLeft();
            params.topMargin = pageView.getTop() - parent.getPaddingTop();
            parent.addView(cover, parent.indexOfChild(pageView) + 1, params);
        } else {
            int[] at = new int[2]; pageView.getLocationInWindow(at);
            int[] origin = new int[2]; decor.getLocationInWindow(origin);
            cover.layout(at[0] - origin[0], at[1] - origin[1], at[0] - origin[0] + snapshot.getWidth(), at[1] - origin[1] + snapshot.getHeight());
            decor.getOverlay().add(cover);
        }
        float shift = Math.signum(direction) * travel(pageView, snapshot.getWidth());
        final View.OnAttachStateChangeListener[] detach = new View.OnAttachStateChangeListener[1];
        ValueAnimator animator = drive(cover, true, through, shift, new View[] {pageView}, () -> {
            running.remove(pageView);
            pageView.removeOnAttachStateChangeListener(detach[0]);
            if (sibling) parent.removeView(cover); else decor.getOverlay().remove(cover);
            cover.setImageDrawable(null); snapshot.recycle();
        });
        detach[0] = new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {}
            // The parent is still detaching its children here; removing the cover from it
            // now would corrupt that pass, so the cleanup waits for the next loop.
            @Override public void onViewDetachedFromWindow(View view) { MAIN.post(animator::cancel); }
        };
        running.put(pageView, animator::cancel);
        pageView.addOnAttachStateChangeListener(detach[0]);
        animator.start();
    }

    /**
     * An in-place section change: only {@code sections} move (the outgoing one leaves toward
     * {@code -direction}, the incoming one arrives from {@code direction}, fading through); headers,
     * tabs and the scroll position stay still. Call immediately before changing the sections' visibility.
     */
    static void shared(ViewGroup sceneRoot, int direction, View... sections) {
        if (!enabled() || sceneRoot == null || !sceneRoot.isLaidOut()) return;
        SharedAxis axis = new SharedAxis(direction);
        for (View section : sections) if (section != null) axis.addTarget(section);
        TransitionManager.beginDelayedTransition(sceneRoot, axis);
    }

    private static final class SharedAxis extends Visibility {
        private final int direction;
        SharedAxis(int direction) { this.direction = Integer.signum(direction); setDuration(PAGE_MS); }
        @Override public Animator onAppear(ViewGroup root, View view, TransitionValues start, TransitionValues end) {
            return part(view, direction * travel(root, root.getWidth()), true);
        }
        @Override public Animator onDisappear(ViewGroup root, View view, TransitionValues start, TransitionValues end) {
            return part(view, direction * travel(root, root.getWidth()), false);
        }
        /** One side of the same fade-through that {@link #drive} gives whole pages. */
        private static Animator part(View view, float shift, boolean arriving) {
            ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(PAGE_MS);
            animator.setInterpolator(new LinearInterpolator());
            ValueAnimator.AnimatorUpdateListener frame = animation -> {
                float t = animation.getAnimatedFraction(), glide = TRAVEL.getInterpolation(t);
                if (arriving) {
                    view.setAlpha(FADE_IN.getInterpolation(Math.max(0f, (t - THROUGH) / (1 - THROUGH))));
                    view.setTranslationX(shift * (1 - glide));
                } else {
                    view.setAlpha(1 - FADE_OUT.getInterpolation(Math.min(1f, t / THROUGH)));
                    view.setTranslationX(-shift * glide);
                }
            };
            animator.addUpdateListener(frame);
            frame.onAnimationUpdate(animator);
            animator.addListener(new AnimatorListenerAdapter() {
                // Section views are reused: never leave one shifted or transparent.
                @Override public void onAnimationEnd(Animator animation) { view.setTranslationX(0); view.setAlpha(1); }
                @Override public void onAnimationCancel(Animator animation) { view.setTranslationX(0); view.setAlpha(1); }
            });
            return animator;
        }
    }

    // ---- Scrolling -----------------------------------------------------------------------------

    /**
     * Runs {@code action} just before the view's next frame, once. If the view detaches first,
     * {@code abandoned} (when given) runs instead and nothing stays registered on the window.
     */
    static void beforeNextDraw(View view, Runnable action, Runnable abandoned) {
        final class Once implements ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
            ViewTreeObserver observer = view.getViewTreeObserver();
            void remove() {
                // A listener added before attach moves to the window's observer when the view attaches.
                (observer.isAlive() ? observer : view.getViewTreeObserver()).removeOnPreDrawListener(this);
                view.removeOnAttachStateChangeListener(this);
            }
            @Override public boolean onPreDraw() { remove(); action.run(); return true; }
            @Override public void onViewAttachedToWindow(View attached) { observer = attached.getViewTreeObserver(); }
            @Override public void onViewDetachedFromWindow(View detached) { remove(); if (abandoned != null) abandoned.run(); }
        }
        Once once = new Once();
        view.getViewTreeObserver().addOnPreDrawListener(once);
        view.addOnAttachStateChangeListener(once);
    }

    /** Applies a scroll position in the first frame that shows the new content, without a scrollbar flash. */
    static void scrollBeforeDraw(ScrollView scroll, int y) {
        if (scroll == null) return;
        beforeNextDraw(scroll, () -> {
            boolean bars = scroll.isVerticalScrollBarEnabled();
            scroll.setVerticalScrollBarEnabled(false);
            scroll.scrollTo(0, Math.max(0, y));
            scroll.setVerticalScrollBarEnabled(bars);
        }, null);
    }

    /**
     * Android flashes the scrollbar of every scroller as it attaches to a visible window, which
     * reads as a flicker after a rebuild or as a sheet opens. Call before {@code root} attaches:
     * its scrollers keep their bars hidden until the user scrolls.
     */
    static void quietScrollbars(View root) {
        if (root == null) return;
        if (root.isVerticalScrollBarEnabled() || root.isHorizontalScrollBarEnabled()) {
            final View view = root;
            final boolean vertical = view.isVerticalScrollBarEnabled(), horizontal = view.isHorizontalScrollBarEnabled();
            Runnable restore = () -> { view.setVerticalScrollBarEnabled(vertical); view.setHorizontalScrollBarEnabled(horizontal); };
            view.setVerticalScrollBarEnabled(false); view.setHorizontalScrollBarEnabled(false);
            beforeNextDraw(view, () -> view.post(restore), restore);
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) quietScrollbars(group.getChildAt(i));
        }
    }

    // ---- Layout changes ------------------------------------------------------------------------

    /**
     * Call just before changing visibility or order inside {@code root}: changes glide instead of
     * jumping. The contents of {@code instant} views change in place (the views themselves still
     * glide), for content that is rebuilt rather than revealed.
     */
    static void animateChanges(ViewGroup root, View... instant) {
        if (!enabled() || root == null || !root.isLaidOut()) return;
        TransitionSet set = new TransitionSet();
        for (View view : instant) if (view != null) set.excludeChildren(view, true);
        set.setOrdering(TransitionSet.ORDERING_TOGETHER);
        set.addTransition(new Fade(Fade.OUT).setDuration(EXIT_MS));
        set.addTransition(new ChangeBounds().setDuration(CHANGE_MS));
        set.addTransition(new Fade(Fade.IN).setDuration(CHANGE_MS));
        set.setInterpolator(STANDARD);
        TransitionManager.beginDelayedTransition(root, set);
    }

    /** Content that finished loading fades up in place rather than snapping in. */
    static void fadeIn(View view) {
        if (!enabled() || view == null) return;
        view.setAlpha(0);
        view.animate().alpha(1).setDuration(CHANGE_MS).setInterpolator(STANDARD).setListener(null).start();
    }
}
