package local.navee.offlinelock;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewParent;
import android.view.animation.PathInterpolator;

/** A brief, non-interactive removal ghost; never delays or performs the actual deletion. */
public final class WidgetRemovalEffect implements View.OnAttachStateChangeListener {
    private static final int MAX_PIXELS = 2_000_000;
    private static final int MAX_SIDE = 2048;
    private static final long DURATION_MS = 200;

    private final View owner;
    private final Ghost ghost;
    private final ValueAnimator animator;
    private boolean finished;

    private WidgetRemovalEffect(View owner, Ghost ghost) {
        this.owner = owner;
        this.ghost = ghost;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(DURATION_MS);
        animator.setInterpolator(new PathInterpolator(.2f, 0f, .2f, 1f));
        animator.addUpdateListener(animation -> {
            if (!Ui.motionEnabled() || !owner.isAttachedToWindow()) {
                cancel();
                return;
            }
            ghost.progress = (Float) animation.getAnimatedValue();
            ghost.invalidateSelf();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { cleanup(); }
            @Override public void onAnimationCancel(Animator animation) { cleanup(); }
        });
    }

    /**
     * Call on the main thread immediately before removing/rendering the card.
     * Owner must be an attached ancestor that survives that render. Returns null
     * when motion is disabled, geometry is unavailable or a snapshot cannot be made.
     * Retain the returned handle to cancel on another action or dashboard close.
     */
    public static WidgetRemovalEffect play(View owner, View card, Ui ui) {
        if (Looper.myLooper() != Looper.getMainLooper() || owner == null || card == null
                || ui == null || owner == card || !Ui.motionEnabled()
                || !owner.isAttachedToWindow() || !card.isAttachedToWindow()
                || !owner.isShown() || !card.isShown()
                || owner.getWidth() <= 0 || owner.getHeight() <= 0
                || card.getWidth() <= 0 || card.getHeight() <= 0) return null;

        Bitmap snapshot = null;
        WidgetRemovalEffect effect = null;
        try {
            Matrix cardToOwner = new Matrix();
            if (!mapToAncestor(card, owner, cardToOwner)) return null;
            RectF bounds = new RectF(0, 0, card.getWidth(), card.getHeight());
            cardToOwner.mapRect(bounds);
            if (!finite(bounds) || bounds.width() <= 0 || bounds.height() <= 0
                    || !RectF.intersects(bounds, new RectF(0, 0, owner.getWidth(), owner.getHeight())))
                return null;

            double pixels = (double) card.getWidth() * card.getHeight();
            double scale = Math.min(1d, Math.sqrt(MAX_PIXELS / pixels));
            scale = Math.min(scale, Math.min(MAX_SIDE / (double) card.getWidth(),
                    MAX_SIDE / (double) card.getHeight()));
            int width = Math.max(1, (int) Math.floor(card.getWidth() * scale));
            int height = Math.max(1, (int) Math.floor(card.getHeight() * scale));
            snapshot = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas capture = new Canvas(snapshot);
            capture.scale(width / (float) card.getWidth(), height / (float) card.getHeight());
            card.draw(capture);

            Matrix bitmapToOwner = new Matrix(cardToOwner);
            bitmapToOwner.preScale(card.getWidth() / (float) width, card.getHeight() / (float) height);
            Ghost ghost = new Ghost(snapshot, bitmapToOwner, bounds, card.getAlpha(), ui);
            ghost.setBounds(0, 0, owner.getWidth(), owner.getHeight());
            effect = new WidgetRemovalEffect(owner, ghost);
            owner.addOnAttachStateChangeListener(effect);
            owner.getOverlay().add(ghost);
            effect.animator.start();
            return effect;
        } catch (RuntimeException | OutOfMemoryError ignored) {
            // A decorative effect must never prevent the owner-requested deletion.
            if (effect != null) effect.cancel();
            else if (snapshot != null && !snapshot.isRecycled()) snapshot.recycle();
            return null;
        }
    }

    /** Idempotent; removes the overlay and releases its bounded snapshot immediately. */
    public void cancel() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            new Handler(Looper.getMainLooper()).post(this::cancel);
            return;
        }
        if (finished) return;
        animator.cancel();
        cleanup();
    }

    private void cleanup() {
        if (finished) return;
        finished = true;
        owner.removeOnAttachStateChangeListener(this);
        animator.removeAllUpdateListeners();
        animator.removeAllListeners();
        try {
            owner.getOverlay().remove(ghost);
        } catch (RuntimeException | OutOfMemoryError ignored) {
            // Overlay teardown is decorative too; always release its bitmap.
        } finally {
            ghost.release();
        }
    }

    @Override public void onViewAttachedToWindow(View view) { }
    @Override public void onViewDetachedFromWindow(View view) { cancel(); }

    /** Works on API 26 too and includes in-flight scale/translation and ancestor scroll. */
    private static boolean mapToAncestor(View card, View owner, Matrix transform) {
        View current = card;
        while (current != owner) {
            transform.postConcat(current.getMatrix());
            transform.postTranslate(current.getLeft(), current.getTop());
            ViewParent parent = current.getParent();
            if (!(parent instanceof View)) return false;
            current = (View) parent;
            transform.postTranslate(-current.getScrollX(), -current.getScrollY());
        }
        return true;
    }

    private static boolean finite(RectF bounds) {
        return Float.isFinite(bounds.left) && Float.isFinite(bounds.top)
                && Float.isFinite(bounds.right) && Float.isFinite(bounds.bottom);
    }

    private static final class Ghost extends Drawable {
        private Bitmap bitmap;
        private final Matrix bitmapToOwner;
        private final RectF cardBounds;
        private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint puffPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float initialAlpha, puffRadius, travel;
        private final Shader puffShader;
        private float progress;

        Ghost(Bitmap bitmap, Matrix bitmapToOwner, RectF bounds, float alpha, Ui ui) {
            this.bitmap = bitmap;
            this.bitmapToOwner = new Matrix(bitmapToOwner);
            cardBounds = new RectF(bounds);
            initialAlpha = Math.max(0, Math.min(1, alpha));
            puffRadius = ui.dp(9);
            travel = ui.dp(14);
            int tint = Color.rgb(Color.red(ui.muted), Color.green(ui.muted), Color.blue(ui.muted));
            puffShader = new RadialGradient(0, 0, 1, new int[] {tint, tint, Color.TRANSPARENT},
                    new float[] {0f, .35f, 1f}, Shader.TileMode.CLAMP);
        }

        @Override public void draw(Canvas canvas) {
            Bitmap current = bitmap;
            if (current == null || current.isRecycled()) return;
            float t = Math.max(0, Math.min(1, progress));
            float cx = cardBounds.centerX(), cy = cardBounds.centerY();
            float shrink = 1f - .16f * t;
            int saved = canvas.save();
            canvas.scale(shrink, shrink, cx, cy);
            canvas.concat(bitmapToOwner);
            imagePaint.setAlpha(Math.round(255 * initialAlpha * (1f - t)));
            canvas.drawBitmap(current, 0, 0, imagePaint);
            canvas.restoreToCount(saved);

            float bloom = Math.max(0, Math.min(1, (t - .08f) / .92f));
            if (bloom <= 0 || bloom >= 1) return;
            float radius = puffRadius * (.45f + .8f * bloom);
            puffPaint.setShader(puffShader);
            puffPaint.setAlpha(Math.round(105 * initialAlpha * (float) Math.sin(Math.PI * bloom)));
            for (int i = 0; i < 8; i++) {
                double angle = Math.PI * (i / 4d + .125d);
                float x = cx + (float) Math.cos(angle) * (cardBounds.width() * .42f + travel * bloom);
                float y = cy + (float) Math.sin(angle) * (cardBounds.height() * .42f + travel * bloom);
                saved = canvas.save();
                canvas.translate(x, y);
                canvas.scale(radius, radius);
                canvas.drawCircle(0, 0, 1, puffPaint);
                canvas.restoreToCount(saved);
            }
        }

        void release() {
            Bitmap old = bitmap;
            bitmap = null;
            if (old != null && !old.isRecycled()) old.recycle();
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter colorFilter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
