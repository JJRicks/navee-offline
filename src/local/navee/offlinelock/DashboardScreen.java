package local.navee.offlinelock;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.*;
import android.widget.*;

import java.util.*;

/** A local, editable dashboard. Widget movement never shares gestures with scooter controls. */
public final class DashboardScreen implements AppScreen {
    public interface Actions {
        View lock(boolean showDisconnect);

        void clearLock();

        void navigate(String page);

        default void quickAction(String id) { navigate(id); }

        void startRide();

        void rename();

        /** Opens the connection dialog (details plus Connect or Disconnect). */
        void connectionDetails();
    }

    private final ScreenHost host;
    private final Actions actions;
    private final Ui ui;
    private final ScooterService service;
    private final SharedPreferences preferences;
    private final DashboardConfig config;
    private final String profileId;
    private final LinearLayout view, toolbar, tools, headerActions, historyActions;
    private final View undo, redo;
    private final DashboardEditHistory editHistory = new DashboardEditHistory();
    private WidgetRemovalEffect removalEffect;
    private EditTransition editTransition;
    private WidgetReveal widgetReveal;
    private String pendingAddedWidgetId, revealWidgetId;
    private final FrameLayout edit, renameControl;
    /** Home header: connection status line, scooter name, edit hint and the last-seen caption. */
    private final TextView status, profileName, hint, stale;
    private final View add, layoutButton;
    private final ChargingHero chargingHero;
    private final Context dialogContext;
    private final Grid grid;
    private final ArrayList<Tile> tiles = new ArrayList<>();
    private boolean editing, closed;
    private AlertDialog dialog;
    private Bitmap scooterPhoto;

    public DashboardScreen(ScreenHost host, Actions actions) {
        this.host = host;
        this.actions = actions;
        ui = host.ui();
        dialogContext =
                new ContextThemeWrapper(
                        host.activity(),
                        ui.dark
                                ? R.style.VoltDialog
                                : R.style.VoltDialogLight);
        service = host.service();
        preferences = host.activity().getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        ScooterProfiles.Profile profile = service == null ? null : service.profiles.active();
        profileId = profile == null ? "unconfigured" : profile.id;
        config = new DashboardConfig(preferences, profileId);
        editing = preferences.getBoolean("dashboard_editing", false);
        view = column();
        // Home header: a muted status line, the scooter name and a round edit button.
        toolbar = new LinearLayout(host.activity()) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                boolean stacked = editing && MeasureSpec.getSize(widthSpec) < ui.dp(520 * ui.textScale);
                setOrientation(stacked ? VERTICAL : HORIZONTAL);
                for (int i = 0; i < getChildCount(); i++) {
                    LinearLayout.LayoutParams p = (LinearLayout.LayoutParams)getChildAt(i).getLayoutParams();
                    p.width = stacked ? -1 : i == 0 ? 0 : -2;
                    p.weight = !stacked && i == 0 ? 1 : 0;
                    p.topMargin = stacked && i == 1 ? ui.dp(6) : 0;
                }
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(0, ui.dp(8), 0, ui.dp(10));
        LinearLayout words = column();
        status = ui.text("", 12.5f, false);
        status.setTextColor(ui.muted);
        ui.fitStatusLine(status);
        // Extra padding gives the one-line status a full touch target without moving the name.
        status.setPadding(0, ui.dp(15), 0, ui.dp(15));
        status.setTooltipText("Connection details");
        status.setOnClickListener(
                v -> {
                    if (!editing) actions.connectionDetails();
                });
        LinearLayout.LayoutParams statusSize = new LinearLayout.LayoutParams(-1, -2);
        statusSize.topMargin = -ui.dp(15);
        statusSize.bottomMargin = -ui.dp(15);
        words.addView(status, statusSize);
        profileName = ui.text("Your scooter", 23, true);
        profileName.setMaxLines(2);
        profileName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (android.os.Build.VERSION.SDK_INT >= 28) profileName.setAccessibilityHeading(true);
        renameControl = roundAction("pencil", "Rename scooter", 24, 12, actions::rename);
        LinearLayout nameRow =
                new LinearLayout(host.activity()) {
                    @Override
                    protected void onMeasure(int widthSpec, int heightSpec) {
                        // The rename pencil sits right after the name, never pushed off the edge.
                        int width = MeasureSpec.getSize(widthSpec);
                        int reserve = renameControl.getVisibility() == View.GONE ? 0 : ui.dp(48);
                        int limit = Math.max(ui.dp(48), width - reserve);
                        if (profileName.getMaxWidth() != limit) profileName.setMaxWidth(limit);
                        super.onMeasure(widthSpec, heightSpec);
                    }
                };
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        nameRow.addView(profileName, new LinearLayout.LayoutParams(-2, -2));
        nameRow.addView(renameControl, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        words.addView(nameRow, new LinearLayout.LayoutParams(-1, -2));
        toolbar.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        edit = new FrameLayout(host.activity());
        edit.setFocusable(true);
        edit.setClickable(true);
        edit.setMinimumWidth(ui.dp(48));
        edit.setMinimumHeight(ui.dp(48));
        edit.setOnClickListener(v -> toggleEditing());
        headerActions = row();
        headerActions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        historyActions = row();
        historyActions.setGravity(Gravity.CENTER_VERTICAL);
        historyActions.setTag("dashboard-history-controls");
        historyActions.setBackground(ui.shape(ui.surface, 14, ui.outline));
        historyActions.setClipToOutline(true);
        undo = historyAction("undo", "Undo dashboard edit", () -> restoreEdit(false));
        redo = historyAction("redo", "Redo dashboard edit", () -> restoreEdit(true));
        historyActions.addView(undo, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        View divider = new View(host.activity()); divider.setBackgroundColor(ui.outline);
        historyActions.addView(divider, new LinearLayout.LayoutParams(ui.dp(1), ui.dp(20)));
        historyActions.addView(redo, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        headerActions.addView(historyActions, new LinearLayout.LayoutParams(-2, ui.dp(48)));
        LinearLayout.LayoutParams editSize = new LinearLayout.LayoutParams(-2, ui.dp(48));
        editSize.setMarginStart(ui.dp(8));
        headerActions.addView(edit, editSize);
        toolbar.addView(headerActions, new LinearLayout.LayoutParams(-2, -2));
        view.addView(toolbar);
        tools = row();
        tools.setPadding(0, ui.dp(2), 0, 0);
        add = toolButton("Add widget", "plus", this::palette);
        LinearLayout.LayoutParams addSpace = new LinearLayout.LayoutParams(0, -2, 1);
        addSpace.setMarginEnd(ui.dp(8));
        tools.addView(add, addSpace);
        layoutButton = toolButton("Layout", "grid", this::layoutOptions);
        tools.addView(layoutButton, new LinearLayout.LayoutParams(0, -2, 1));
        view.addView(tools);
        hint = ui.caption("Drag the dots to move · drag the lower-right corner to resize");
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(ui.dp(8), ui.dp(10), ui.dp(8), ui.dp(12));
        view.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        stale = ui.caption("");
        stale.setTextColor(ui.muted);
        ui.fitStatusLine(stale);
        stale.setPadding(ui.dp(2), 0, ui.dp(2), ui.dp(10));
        view.addView(stale);
        chargingHero = new ChargingHero();
        view.addView(chargingHero, new LinearLayout.LayoutParams(-1, -2));
        grid = new Grid();
        view.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        render();
    }

    @Override
    public View view() {
        return view;
    }

    /**
     * Tabletop posture: hands Home's header (status, name, edit tools, last-seen line and the
     * charging card) to MainActivity's upper pane above the hinge, leaving the widgets below it.
     * The same views keep updating; only their parent changes.
     */
    public View takeHeader() {
        LinearLayout pane = column();
        for (View part : new View[] {toolbar, tools, hint, stale, chargingHero}) {
            ViewGroup.LayoutParams params = part.getLayoutParams();
            view.removeView(part);
            pane.addView(part, params);
        }
        return pane;
    }

    private LinearLayout column() {
        LinearLayout v = new LinearLayout(host.activity());
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }

    private LinearLayout row() {
        LinearLayout v = column();
        v.setOrientation(LinearLayout.HORIZONTAL);
        return v;
    }

    /** Mixes two colours; used for tints derived from the Volt tokens. */
    private static int blend(int from, int to, float amount) {
        float a = Math.max(0, Math.min(1, amount));
        return Color.rgb(
                Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * a),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * a),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * a));
    }

    private static android.graphics.drawable.Drawable ripple(
            int color, android.graphics.drawable.Drawable content, android.graphics.drawable.Drawable mask) {
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(color), content, mask);
    }

    /** Dark outlined button with a leading glyph, centred together (Add widget, Layout). */
    private LinearLayout toolButton(String label, String glyph, Runnable action) {
        LinearLayout button = row();
        button.setGravity(Gravity.CENTER);
        button.setMinimumHeight(ui.dp(48));
        button.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8));
        button.setBackground(
                ripple(Ui.withAlpha(ui.accent, 30), ui.shape(ui.surface, 14, ui.outline), null));
        button.setClickable(true);
        button.setFocusable(true);
        button.addView(new GlyphView(glyph, ui.text), new LinearLayout.LayoutParams(ui.dp(17), ui.dp(17)));
        TextView text = ui.text(label, 13.5f, false);
        text.setTypeface(ui.semibold());
        LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-2, -2);
        space.setMarginStart(ui.dp(8));
        button.addView(text, space);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    /** A round control (a visual disc inside a 48 dp target), e.g. the rename pencil or a sheet close. */
    private FrameLayout roundAction(String key, String label, int discDp, int iconDp, Runnable action) {
        FrameLayout target = new FrameLayout(host.activity());
        int inset = Math.max(0, (48 - discDp) / 2);
        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(ui.dark ? ui.surface : ui.raised);
        target.setBackground(
                ripple(
                        Ui.withAlpha(ui.accent, 35),
                        new android.graphics.drawable.InsetDrawable(disc, ui.dp(inset)),
                        null));
        target.addView(
                new GlyphView(key, ui.text),
                new FrameLayout.LayoutParams(ui.dp(iconDp), ui.dp(iconDp), Gravity.CENTER));
        target.setContentDescription(label);
        target.setFocusable(true);
        target.setClickable(true);
        target.setMinimumWidth(ui.dp(48));
        target.setMinimumHeight(ui.dp(48));
        if (action != null) target.setOnClickListener(v -> action.run());
        return target;
    }

    private void toggleEditing() {
        cancelWidgetReveal();
        pendingAddedWidgetId = null;
        grid.finishGesture(false);
        stopRemovalEffect();
        stopEditTransition();
        grid.stopJiggle();
        EditTransition transition = Ui.motionEnabled() && grid.isAttachedToWindow()
                && grid.hasWindowFocus() ? new EditTransition(editing) : null;
        editHistory.clear();
        editing = !editing;
        preferences.edit().putBoolean("dashboard_editing", editing).apply();
        render();
        if (transition != null) {
            editTransition = transition;
            transition.start();
        }
    }

    private void stopEditTransition() {
        if (editTransition != null) editTransition.cancel();
    }

    /** Visible editor controls only; offscreen cards do not need decorative work. */
    private ArrayList<View> editorControls() {
        ArrayList<View> result = new ArrayList<>();
        Rect visible = new Rect();
        for (View control : new View[] {edit, historyActions, renameControl, add, layoutButton})
            if (control.getVisibility() == View.VISIBLE && control.getGlobalVisibleRect(visible))
                result.add(control);
        for (Tile tile : tiles) {
            if (!tile.getGlobalVisibleRect(visible)) continue;
            View bar = tile.findViewWithTag("widget-editor-bar");
            if (bar instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) bar;
                for (int i = 0; i < group.getChildCount(); i++)
                    if (group.getChildAt(i).getGlobalVisibleRect(visible)) result.add(group.getChildAt(i));
            }
            if (tile.resizeControl != null && tile.resizeControl.getGlobalVisibleRect(visible))
                result.add(tile.resizeControl);
        }
        return result;
    }

    /** One short visual transition; editing/permissions/history change synchronously. */
    private final class EditTransition implements ViewTreeObserver.OnPreDrawListener {
        private static final int PIXEL_BUDGET = 1_000_000;
        final boolean leaving;
        final View overlayOwner;
        final int oldGridY, oldActionsY;
        final ArrayList<ExitControlGhost> ghosts = new ArrayList<>();
        final ArrayList<View> controls = new ArrayList<>();
        final Map<View, Float> controlAlphas = new IdentityHashMap<>();
        android.animation.ValueAnimator animator;
        ViewTreeObserver observer;
        boolean finished;
        float gridOffset, actionsOffset;

        EditTransition(boolean leaving) {
            this.leaving = leaving;
            overlayOwner = view.getRootView();
            oldGridY = screenY(grid);
            oldActionsY = screenY(headerActions);
            if (leaving) captureExitControls();
        }

        private int screenY(View target) {
            int[] at = new int[2]; target.getLocationOnScreen(at); return at[1];
        }

        private void captureExitControls() {
            int[] ownerAt = new int[2]; overlayOwner.getLocationOnScreen(ownerAt);
            int remaining = PIXEL_BUDGET;
            for (View control : editorControls()) {
                if (control.getWidth() <= 0 || control.getHeight() <= 0 || remaining < 64) continue;
                Rect visible = new Rect();
                if (!control.getGlobalVisibleRect(visible)) continue;
                Bitmap bitmap = null;
                try {
                    double pixels = (double) control.getWidth() * control.getHeight();
                    double scale = Math.min(1, Math.sqrt(Math.min(120_000, remaining) / pixels));
                    scale = Math.min(scale, Math.min(1024d / control.getWidth(), 1024d / control.getHeight()));
                    int w = Math.max(1, (int) (control.getWidth() * scale));
                    int h = Math.max(1, (int) (control.getHeight() * scale));
                    bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    Canvas capture = new Canvas(bitmap);
                    capture.scale(w / (float) control.getWidth(), h / (float) control.getHeight());
                    control.draw(capture);
                    int[] at = new int[2]; control.getLocationOnScreen(at);
                    RectF bounds = new RectF(at[0] - ownerAt[0], at[1] - ownerAt[1],
                            at[0] - ownerAt[0] + control.getWidth(), at[1] - ownerAt[1] + control.getHeight());
                    RectF clip = new RectF(visible);
                    clip.offset(-ownerAt[0], -ownerAt[1]);
                    boolean inGrid = false;
                    for (ViewParent parent = control.getParent(); parent instanceof View; parent = parent.getParent())
                        if (parent == grid) { inGrid = true; break; }
                    ExitControlGhost ghost = new ExitControlGhost(bitmap, bounds, clip, inGrid, control.getAlpha());
                    ghost.setBounds(0, 0, overlayOwner.getWidth(), overlayOwner.getHeight());
                    ghosts.add(ghost);
                    remaining -= w * h;
                } catch (RuntimeException | OutOfMemoryError ignored) {
                    if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                    break;
                }
            }
        }

        void start() {
            if (closed || !Ui.motionEnabled() || !grid.isAttachedToWindow()) { cancel(); return; }
            observer = grid.getViewTreeObserver();
            observer.addOnPreDrawListener(this);
            grid.requestLayout();
        }

        @Override public boolean onPreDraw() {
            removePreDraw();
            if (finished || closed || !Ui.motionEnabled() || !grid.hasWindowFocus()
                    || !grid.isAttachedToWindow()) { cancel(); return true; }
            gridOffset = oldGridY - screenY(grid);
            actionsOffset = oldActionsY - screenY(headerActions);
            grid.setTranslationY(gridOffset);
            headerActions.setTranslationY(actionsOffset);
            if (!leaving) controls.addAll(editorControls());
            else controls.add(edit);
            for (View control : controls) {
                controlAlphas.put(control, control.getAlpha());
                control.setAlpha(0);
                control.setScaleX(.86f); control.setScaleY(.86f);
            }
            try {
                for (ExitControlGhost ghost : ghosts) overlayOwner.getOverlay().add(ghost);
            } catch (RuntimeException | OutOfMemoryError ignored) {
                cancel();
                return true;
            }
            animator = android.animation.ValueAnimator.ofFloat(0, 1);
            animator.setDuration(210);
            animator.setInterpolator(new android.view.animation.PathInterpolator(.2f, 0, 0, 1));
            animator.addUpdateListener(a -> {
                if (!Ui.motionEnabled() || closed || !grid.hasWindowFocus()) { cancel(); return; }
                float progress = (Float) a.getAnimatedValue();
                grid.setTranslationY(gridOffset * (1 - progress));
                headerActions.setTranslationY(actionsOffset * (1 - progress));
                float pop = Math.min(1, progress / .82f);
                for (View control : controls) {
                    control.setAlpha(controlAlphas.get(control) * pop);
                    control.setScaleX(.86f + .14f * pop); control.setScaleY(.86f + .14f * pop);
                }
                for (ExitControlGhost ghost : ghosts) {
                    ghost.progress = Math.min(1, progress / .7f);
                    ghost.offset = ghost.inGrid ? -gridOffset * progress : 0;
                    ghost.invalidateSelf();
                }
            });
            animator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator animation) { finish(); }
                @Override public void onAnimationCancel(android.animation.Animator animation) { finish(); }
            });
            animator.start();
            return true;
        }

        private void removePreDraw() {
            if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
            observer = null;
        }

        void cancel() {
            if (finished) return;
            if (animator != null) animator.cancel();
            finish();
        }

        private void finish() {
            if (finished) return;
            finished = true;
            removePreDraw();
            if (animator != null) { animator.removeAllUpdateListeners(); animator.removeAllListeners(); }
            grid.setTranslationY(0); headerActions.setTranslationY(0);
            for (View control : controls) {
                control.setAlpha(controlAlphas.get(control)); control.setScaleX(1); control.setScaleY(1);
            }
            controls.clear();
            controlAlphas.clear();
            for (ExitControlGhost ghost : ghosts) {
                try { overlayOwner.getOverlay().remove(ghost); }
                catch (RuntimeException | OutOfMemoryError ignored) { }
                finally { ghost.release(); }
            }
            ghosts.clear();
            if (editTransition == this) editTransition = null;
            grid.updateEditorMotion();
        }
    }

    /** Exit controls are only a fading image; no stale action survives the state change. */
    private static final class ExitControlGhost extends android.graphics.drawable.Drawable {
        Bitmap bitmap;
        final RectF bounds, clip;
        final boolean inGrid;
        final float alpha;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        float progress, offset;

        ExitControlGhost(Bitmap bitmap, RectF bounds, RectF clip, boolean inGrid, float alpha) {
            this.bitmap = bitmap; this.bounds = bounds; this.clip = clip;
            this.inGrid = inGrid; this.alpha = alpha;
        }

        @Override public void draw(Canvas canvas) {
            if (bitmap == null || bitmap.isRecycled()) return;
            paint.setAlpha(Math.round(255 * alpha * (1 - progress)));
            int save = canvas.save();
            canvas.translate(0, offset);
            canvas.clipRect(clip);
            canvas.scale(1 - .15f * progress, 1 - .15f * progress, bounds.centerX(), bounds.centerY());
            canvas.drawBitmap(bitmap, null, bounds, paint);
            canvas.restoreToCount(save);
        }

        void release() {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            bitmap = null;
        }
        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter filter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private View historyAction(String icon, String label, Runnable action) {
        FrameLayout control = new FrameLayout(host.activity());
        control.addView(new GlyphView(icon, ui.text),
                new FrameLayout.LayoutParams(ui.dp(21), ui.dp(21), Gravity.CENTER));
        control.setBackground(ripple(Ui.withAlpha(ui.accent, 45), null, ui.shape(Color.WHITE, 10, 0)));
        control.setContentDescription(label);
        control.setTooltipText(icon.equals("undo") ? "Undo" : "Redo");
        control.setFocusable(true); control.setClickable(true);
        control.setOnClickListener(v -> action.run());
        return control;
    }

    private void updateHistoryControls() {
        historyActions.setVisibility(editing ? View.VISIBLE : View.GONE);
        undo.setEnabled(editing && editHistory.canUndo());
        redo.setEnabled(editing && editHistory.canRedo());
        undo.setAlpha(undo.isEnabled() ? 1 : .3f);
        redo.setAlpha(redo.isEnabled() ? 1 : .3f);
    }

    private void recordEdit(DashboardEditHistory.Snapshot before) {
        editHistory.record(before, config);
        updateHistoryControls();
    }

    private void restoreEdit(boolean forward) {
        if (!editing || closed) return;
        grid.finishGesture(false);
        stopRemovalEffect();
        dismiss();
        if (forward ? editHistory.redo(config) : editHistory.undo(config)) {
            config.save();
            render();
        }
    }

    private void stopRemovalEffect() {
        if (removalEffect != null) { removalEffect.cancel(); removalEffect = null; }
    }

    private void cancelWidgetReveal() {
        if (widgetReveal != null) { widgetReveal.cancel(); widgetReveal = null; }
    }

    private void revealAddedWidget() {
        if (revealWidgetId == null || closed || !editing || dialog != null
                || !grid.hasWindowFocus() || !grid.isLaidOut()) return;
        Tile target = null;
        for (Tile tile : tiles) if (tile.widget.id.equals(revealWidgetId)) { target = tile; break; }
        if (target == null) { revealWidgetId = null; return; }
        if (!target.isLaidOut() || target.isLayoutRequested()) {
            grid.postOnAnimation(this::revealAddedWidget);
            return;
        }
        revealWidgetId = null;
        cancelWidgetReveal();
        widgetReveal = WidgetReveal.start(target, grid.outerScroll(), ui);
    }

    private void revealIfAdded(DashboardConfig.Widget widget) {
        if (!widget.id.equals(pendingAddedWidgetId)) return;
        pendingAddedWidgetId = null;
        revealWidgetId = widget.id;
        grid.post(this::revealAddedWidget);
    }

    private void render() {
        if (closed) return;
        cancelWidgetReveal();
        stopEditTransition();
        grid.stopJiggle();
        grid.finishGesture(false);
        grid.rememberFrames();
        for (Tile tile : tiles) tile.active = false;
        tiles.clear();
        grid.removeAllViews();
        actions.clearLock();
        edit.removeAllViews();
        if (editing) {
            // A red Done pill replaces the round pencil.
            TextView done = ui.text("Done", 14, true);
            done.setTextColor(ui.onAccent);
            done.setGravity(Gravity.CENTER);
            done.setMinHeight(ui.dp(38));
            done.setPadding(ui.dp(20), ui.dp(6), ui.dp(20), ui.dp(6));
            done.setBackground(ui.shape(ui.accent, 100, 0));
            edit.addView(done, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            edit.setBackground(ripple(Ui.withAlpha(Color.WHITE, 45), null, ui.shape(Color.WHITE, 100, 0)));
        } else {
            FrameLayout disc = new FrameLayout(host.activity());
            disc.setBackground(roundDisc());
            disc.addView(
                    new GlyphView("pencil", ui.text),
                    new FrameLayout.LayoutParams(ui.dp(17), ui.dp(17), Gravity.CENTER));
            edit.addView(disc, new FrameLayout.LayoutParams(ui.dp(38), ui.dp(38), Gravity.CENTER));
            edit.setBackground(ripple(Ui.withAlpha(ui.accent, 35), null, roundDisc()));
        }
        edit.setContentDescription(editing ? "Finish editing dashboard" : "Edit dashboard");
        updateHistoryControls();
        renameControl.setVisibility(
                editing && service != null && service.profiles.active() != null ? View.VISIBLE : View.GONE);
        tools.setVisibility(editing ? View.VISIBLE : View.GONE);
        hint.setVisibility(editing ? View.VISIBLE : View.GONE);
        status.setClickable(!editing);
        for (DashboardConfig.Widget widget : config.widgets) {
            Tile tile = new Tile(widget);
            tiles.add(tile);
            grid.addView(tile, new ViewGroup.LayoutParams(-1, -2));
        }
        grid.requestLayout();
        grid.invalidate();
        refresh();
    }

    private GradientDrawable roundDisc() {
        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(ui.dark ? ui.surface : ui.raised);
        return disc;
    }

    @Override
    public void refresh() {
        if (closed) return;
        ScooterProfiles.Profile current = service == null ? null : service.profiles.active();
        String id = current == null ? "unconfigured" : current.id;
        if (!profileId.equals(id)) {
            host.rebuild();
            return;
        }
        boolean live = service != null && service.ble.ready();
        long last = 0;
        Map<String, TelemetryHistory.Reading> readings = readings();
        for (TelemetryHistory.Reading r : readings.values())
            last = Math.max(last, r.capturedWallMs);
        refreshHeader(current, readings);
        stale.setText(!editing && !live && last > 0 ? "Last seen · " + when(last) : "");
        stale.setVisibility(stale.getText().length() == 0 ? View.GONE : View.VISIBLE);
        chargingHero.refresh(readings);
        for (Tile tile : tiles) tile.refresh(readings);
        grid.updateEditorMotion();
    }

    /** Home status line: Connected · drive mode, Connecting… or Disconnected · last seen. */
    private void refreshHeader(
            ScooterProfiles.Profile profile, Map<String, TelemetryHistory.Reading> readings) {
        profileName.setText(profile == null ? "Your scooter" : profile.name);
        if (editing) {
            status.setText("Editing dashboard");
            status.setContentDescription("Editing dashboard");
            status.setClickable(false);
            return;
        }
        BleController ble = service == null ? null : service.ble;
        boolean ready = ble != null && ble.ready();
        BleController.State state = ble == null ? null : ble.state();
        boolean connecting =
                ble != null
                        && !ready
                        && (ble.connected()
                                || state == BleController.State.CONNECTING
                                || state == BleController.State.AUTHENTICATING
                                || state == BleController.State.SCANNING
                                || state == BleController.State.WAITING);
        long lastConnected = profile == null ? 0 : profile.lastConnected;
        String mode = null;
        TelemetryHistory.Reading drive = readings.get("driving_mode");
        if (ready && drive != null && drive.live) {
            String value = format(drive);
            if (value.matches("[A-Z]+"))
                mode = value.charAt(0) + value.substring(1).toLowerCase(Locale.ROOT);
        }
        String word = ready ? "Connected" : connecting ? "Connecting" : "Disconnected";
        if (connecting) status.setText(connectingLabel());
        else
            status.setText(
                    word
                            + (mode != null ? " · " + mode : "")
                            + (!ready && lastConnected > 0
                                    ? " · last seen "
                                            + DisplayTime.recent(
                                                    host.activity(),
                                                    preferences,
                                                    lastConnected,
                                                    System.currentTimeMillis())
                                    : ""));
        status.setContentDescription(
                word
                        + (mode != null ? ", " + mode + " mode" : "")
                        + (!ready && !connecting && lastConnected > 0
                                ? ", last connected "
                                        + DisplayTime.dateTime(host.activity(), preferences, lastConnected)
                                : "")
                        + ". Connection details");
        status.setClickable(true);
    }

    /** An inline spinner keeps "Connecting…" on its single status line. */
    private CharSequence connectingLabel() {
        android.text.SpannableString text = new android.text.SpannableString("Connecting…  ￼");
        TextView owner = status;
        android.graphics.drawable.Drawable spinner =
                new android.graphics.drawable.Drawable() {
                    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

                    @Override
                    public void draw(Canvas canvas) {
                        RectF arc = new RectF(getBounds());
                        arc.inset(ui.dp(2), ui.dp(2));
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setStrokeWidth(ui.dp(2));
                        paint.setStrokeCap(Paint.Cap.ROUND);
                        paint.setColor(ui.raised);
                        canvas.drawOval(arc, paint);
                        paint.setColor(ui.accent);
                        boolean motion = android.animation.ValueAnimator.areAnimatorsEnabled();
                        float start = motion ? (android.os.SystemClock.uptimeMillis() % 1000) * .36f : -90;
                        canvas.drawArc(arc, start, 90, false, paint);
                        if (motion && owner.isShown()) owner.postInvalidateDelayed(32);
                    }

                    @Override
                    public void setAlpha(int alpha) {
                        paint.setAlpha(alpha);
                    }

                    @Override
                    public void setColorFilter(ColorFilter filter) {
                        paint.setColorFilter(filter);
                    }

                    @Override
                    public int getOpacity() {
                        return PixelFormat.TRANSLUCENT;
                    }
                };
        int size = Math.round(owner.getTextSize());
        spinner.setBounds(0, 0, size, size);
        text.setSpan(
                new android.text.style.ImageSpan(
                        spinner, android.text.style.DynamicDrawableSpan.ALIGN_BASELINE),
                text.length() - 1,
                text.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    private Map<String, TelemetryHistory.Reading> readings() {
        return service == null ? Collections.emptyMap() : service.displayReadings();
    }

    private String when(long time) {
        return DisplayTime.recent(host.activity(), preferences, time, System.currentTimeMillis());
    }

    private int color(DashboardConfig.Widget widget) {
        return new int[] {ui.accent, ui.blue, ui.orange, ui.danger}[widget.color];
    }

    private String units() {
        return preferences.getString("units", "mi");
    }

    private String temperatureUnit() {
        return preferences.getString("temperature_unit", "c");
    }

    private String format(TelemetryHistory.Reading r) {
        if (r == null || r.value == null) return "—";
        if (r.key.equals("driving_mode") && r.number != null) {
            ScooterProfiles.Profile profile = service == null ? null : service.profiles.active();
            return Telemetry.drivingModeLabel(profile == null ? 0 : profile.pid(), r.number);
        }
        if (r.number != null) {
            if (DisplayTemperature.isTemperature(r.key))
                return DisplayTemperature.format(r.number, temperatureUnit(), 0);
            if (r.key.endsWith("_kmh")) return DisplayUnits.speed(r.number, units());
            if (r.key.endsWith("_km"))
                return DisplayUnits.metricText(
                        String.format(Locale.US, "%.2f km", r.number), units());
        }
        return DisplayUnits.metricText(r.value, units());
    }

    /** "about 31 mi": the range reading rounded, in the rider's distance units. */
    private String rangeText(TelemetryHistory.Reading r) {
        if (r == null || r.value == null) return "";
        if (r.number == null) return "about " + format(r);
        String km = String.format(Locale.US, "%.0f km", r.number);
        String miles = String.format(Locale.US, "%.0f mi", r.number / 1.609344);
        String units = units();
        return "about " + (units.equals("km") ? km : units.equals("dual") ? miles + " / " + km : miles);
    }

    /** Large value with its unit drawn smaller (and muted when live), e.g. 412 W. */
    private CharSequence withUnit(String value, boolean live) {
        int space = value.lastIndexOf(' ');
        if (space <= 0 || space >= value.length() - 1 || value.contains("/")) return value;
        String unit = value.substring(space + 1);
        char first = unit.charAt(0);
        if (unit.length() > 5 || !(Character.isLetter(first) || first == '%' || first == '°'))
            return value;
        if (!Character.isDigit(value.charAt(space - 1))) return value;
        android.text.SpannableString text = new android.text.SpannableString(value);
        int end = value.length();
        text.setSpan(new android.text.style.RelativeSizeSpan(.52f), space, end,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (live)
            text.setSpan(new android.text.style.ForegroundColorSpan(ui.muted), space, end,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    private double chartNumber(String key, double value) {
        if (DisplayTemperature.isTemperature(key))
            return DisplayTemperature.fromCelsius(value, temperatureUnit());
        return !units().equals("km") && (key.endsWith("_km") || key.endsWith("_kmh"))
                ? value / 1.609344
                : value;
    }

    private String chartUnit(String key, Map<String, TelemetryHistory.Reading> values) {
        if (DisplayTemperature.isTemperature(key)) return DisplayTemperature.unit(temperatureUnit());
        if (key.endsWith("_kmh")) return units().equals("km") ? "km/h" : "mph";
        if (key.endsWith("_km")) return units().equals("km") ? "km" : "mi";
        TelemetryHistory.Reading r = values.get(key);
        return r == null ? "" : r.unit;
    }

    private String label(String key) {
        if (key.equals("driving_mode")) return "Drive mode";
        for (Telemetry.Metric m : new Telemetry().metrics())
            if (m.key.equals(key)) return m.label.replace(" (signed)", "");
        return key;
    }

    private String widgetIcon(String type) {
        switch (type) {
            case "lock":
                return "lock";
            case "scooter":
                return "scooter";
            case "battery":
                return "battery";
            case "metric":
                return "text";
            case "gauge":
                return "speed";
            case "live":
                return "trend";
            case "history":
                return "stats";
            case "readings":
                return "list";
            case "trip":
                return "rides";
            case "health":
                return "heart";
            case "capacity":
                return "lab";
            case "charging":
                return "bolt";
            case "quick":
                return "grid";
            case "ride":
                return "record";
            case "note":
                return "note";
            default:
                return "stats";
        }
    }

    private String readingIcon(String key) {
        if (key.equals("remaining_range_km")) return "route";
        if (key.equals("power_w")) return "bolt";
        if (key.equals("battery_health_percent")) return "heart";
        if (key.equals("temperature_c")) return "temperature";
        if (key.equals("odometer_km") || key.equals("trip_km")) return "route";
        return ui.featureIcon(key);
    }

    private Bitmap scooterPhoto() {
        if (scooterPhoto == null) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 2;
            scooterPhoto =
                    BitmapFactory.decodeResource(host.activity().getResources(), R.drawable.ut5max, o);
        }
        return scooterPhoto;
    }

    private String duration(Double minutes) {
        if (minutes == null || !Double.isFinite(minutes)) return "—";
        long value = Math.max(0, Math.round(minutes));
        return value == 0
                ? "At target"
                : "≈ " + (value >= 60 ? value / 60 + "h " : "") + value % 60 + "m";
    }

    private ChargingEstimate.Value chargeValue(
            Map<String, TelemetryHistory.Reading> values, String key) {
        TelemetryHistory.Reading r = values.get(key);
        return new ChargingEstimate.Value(
                r == null ? null : r.number, r != null && r.live, r == null ? 0 : r.capturedWallMs);
    }

    private ChargingEstimate.Result chargingEstimate(Map<String, TelemetryHistory.Reading> values) {
        ScooterService service = host.service();
        if (service != null) return service.chargingEstimate(values);
        return ChargingEstimate.calculate(
                chargeValue(values, "charging_state"),
                chargeValue(values, "voltage_v"),
                chargeValue(values, "current_a"),
                chargeValue(values, "battery_percent"),
                chargeValue(values, "rated_capacity_ah"),
                chargeValue(values, "charge_limit_percent"));
    }

    /** Line glyphs: the shared Ui set plus a few dashboard-only shapes. */
    private void drawGlyph(Canvas c, String key, int color, float cx, float cy, float size) {
        if (!Arrays.asList("grid", "note", "record", "stop", "trend", "restore", "list", "undo", "redo").contains(key)) {
            ui.drawIcon(c, key, color, cx, cy, size);
            return;
        }
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.8f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        c.save();
        c.translate(cx - size / 2, cy - size / 2);
        c.scale(size / 24f, size / 24f);
        Path p = new Path();
        switch (key) {
            case "undo":
            case "redo":
                if (key.equals("redo")) c.scale(-1, 1, 12, 12);
                p.moveTo(9, 4); p.lineTo(4, 9); p.lineTo(9, 14);
                p.moveTo(4, 9); p.lineTo(13, 9); p.cubicTo(22, 9, 22, 20, 13, 20);
                c.drawPath(p, paint);
                break;
            case "grid":
                for (int x = 3; x <= 14; x += 11)
                    for (int y = 3; y <= 14; y += 11)
                        c.drawRoundRect(new RectF(x, y, x + 7, y + 7), 1.5f, 1.5f, paint);
                break;
            case "note":
                c.drawRoundRect(new RectF(5, 3, 19, 21), 2, 2, paint);
                c.drawLine(8.5f, 8.5f, 15.5f, 8.5f, paint);
                c.drawLine(8.5f, 12.5f, 15.5f, 12.5f, paint);
                c.drawLine(8.5f, 16.5f, 13, 16.5f, paint);
                break;
            case "record":
                c.drawCircle(12, 12, 8, paint);
                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(12, 12, 3.6f, paint);
                break;
            case "stop":
                paint.setStyle(Paint.Style.FILL);
                c.drawRoundRect(new RectF(6, 6, 18, 18), 2.5f, 2.5f, paint);
                break;
            case "trend":
                p.moveTo(2, 13);
                p.lineTo(6, 13);
                p.lineTo(9, 6);
                p.lineTo(13, 18);
                p.lineTo(16, 10);
                p.lineTo(18, 13);
                p.lineTo(22, 13);
                c.drawPath(p, paint);
                break;
            case "restore":
                c.drawArc(new RectF(4, 4, 20, 20), -150, 150, false, paint);
                c.drawArc(new RectF(4, 4, 20, 20), 30, 150, false, paint);
                p.moveTo(20, 3.5f);
                p.lineTo(20, 9);
                p.lineTo(14.5f, 9);
                p.moveTo(4, 20.5f);
                p.lineTo(4, 15);
                p.lineTo(9.5f, 15);
                c.drawPath(p, paint);
                break;
            case "list":
                for (int y = 6; y <= 18; y += 6) {
                    c.drawLine(9, y, 20, y, paint);
                    paint.setStyle(Paint.Style.FILL);
                    c.drawCircle(4.5f, y, 1.3f, paint);
                    paint.setStyle(Paint.Style.STROKE);
                }
                break;
        }
        c.restore();
    }

    /** A non-interactive icon view sized by its layout parameters. */
    private final class GlyphView extends View {
        String key;
        int color;

        GlyphView(String key, int color) {
            super(host.activity());
            this.key = key;
            this.color = color;
            setContentDescription(key);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void set(String key, int color) {
            if (this.key.equals(key) && this.color == color) return;
            this.key = key;
            this.color = color;
            setContentDescription(key);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float size = Math.min(getWidth(), getHeight());
            if (size > 0) drawGlyph(canvas, key, color, getWidth() / 2f, getHeight() / 2f, size);
        }
    }

    /** A rounded square holding a glyph, at the start of rows (palette, capacity, layout). */
    private FrameLayout square(String key, int color, int sizeDp) {
        FrameLayout box = new FrameLayout(host.activity());
        box.setBackground(ui.shape(ui.dark ? ui.bg : ui.raised, 10, 0));
        int glyph = Math.round(sizeDp * .5f);
        box.addView(
                new GlyphView(key, color),
                new FrameLayout.LayoutParams(ui.dp(glyph), ui.dp(glyph), Gravity.CENTER));
        box.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return box;
    }

    /** A plain (unboxed) glyph as a TextView compound drawable. */
    private android.graphics.drawable.Drawable glyphDrawable(String key, int color, int sizeDp) {
        android.graphics.drawable.Drawable icon =
                new android.graphics.drawable.Drawable() {
                    @Override
                    public void draw(Canvas canvas) {
                        Rect b = getBounds();
                        drawGlyph(canvas, key, color, b.exactCenterX(), b.exactCenterY(),
                                Math.min(b.width(), b.height()));
                    }

                    @Override
                    public void setAlpha(int alpha) {}

                    @Override
                    public void setColorFilter(ColorFilter filter) {}

                    @Override
                    public int getOpacity() {
                        return PixelFormat.TRANSLUCENT;
                    }
                };
        icon.setBounds(0, 0, ui.dp(sizeDp), ui.dp(sizeDp));
        return icon;
    }

    private final class CapacityBar extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Double percent;
        CapacityBar() { super(host.activity()); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        void set(Double value) { percent = value; invalidate(); }
        @Override protected void onDraw(Canvas canvas) {
            float radius = getHeight() / 2f;
            paint.setColor(ui.raised);
            canvas.drawRoundRect(0, 0, getWidth(), getHeight(), radius, radius, paint);
            if (percent == null || !Double.isFinite(percent)) return;
            paint.setColor(percent > 100 ? ui.orange : ui.accent);
            float width = (float)(getWidth() * Math.max(0, Math.min(100, percent)) / 100);
            canvas.drawRoundRect(0, 0, width, getHeight(), radius, radius, paint);
        }
    }

    /** Fit from the available width, never from a previous WRAP_CONTENT height. */
    private final class FittedValue extends TextView {
        final int minimumSp, maximumSp, targetLines;
        int fittedWidth = -1;
        String fittedText;
        float fittedPixels;
        int heightBudget = Integer.MAX_VALUE;
        int sharedMaximumSp = Integer.MAX_VALUE;

        FittedValue(String text, int minimum, int maximum, int lines) {
            super(host.activity());
            minimumSp = (int) Math.ceil(minimum * ui.textScale);
            maximumSp = Math.max(minimumSp, Math.round(maximum * ui.textScale));
            targetLines = lines;
            setText(text);
            setTextColor(ui.text);
            setTypeface(ui.bold());
            setIncludeFontPadding(false);
            setLineSpacing(ui.dp(3), 1);
            setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, maximumSp);
            // At the readable floor, long custom text wraps into the card's
            // scroller instead of disappearing behind a maximum-line limit.
        }

        void fitHeight(int pixels) {
            int next = Math.max(1, pixels);
            if (heightBudget == next) return;
            heightBudget = next;
            fittedWidth = -1;
            requestLayout();
        }

        void capAt(int sizeSp) {
            if (sharedMaximumSp == sizeSp) return;
            sharedMaximumSp = sizeSp;
            fittedWidth = -1;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec)
                    - getCompoundPaddingLeft() - getCompoundPaddingRight();
            String text = getText().toString();
            float density = getResources().getDisplayMetrics().scaledDensity;
            if (width != fittedWidth || !text.equals(fittedText)) {
                fittedWidth = width;
                fittedText = text;
                int ceiling = Math.min(maximumSp, sharedMaximumSp);
                fittedPixels = ceiling * density;
                if (width > 0 && MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED) {
                    android.text.TextPaint paint = new android.text.TextPaint(getPaint());
                    for (int size = ceiling; size >= minimumSp; size--) {
                        fittedPixels = size * density;
                        paint.setTextSize(fittedPixels);
                        android.text.StaticLayout layout = android.text.StaticLayout.Builder
                                .obtain(text, 0, text.length(), paint, width)
                                .setIncludePad(getIncludeFontPadding())
                                .setLineSpacing(getLineSpacingExtra(), getLineSpacingMultiplier())
                                .setBreakStrategy(getBreakStrategy())
                                .setHyphenationFrequency(getHyphenationFrequency())
                                .setTextDirection(android.text.TextDirectionHeuristics.FIRSTSTRONG_LTR)
                                .build();
                        int count = layout.getLineCount();
                        boolean fits = count <= targetLines && layout.getHeight() <= heightBudget
                                && (count == 0 || layout.getLineEnd(count - 1) == text.length());
                        for (int line = 0; fits && line < count; line++)
                            fits = layout.getLineMax(line) <= width;
                        if (fits) break;
                    }
                }
            }
            if (getTextSize() != fittedPixels)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, fittedPixels);
            super.onMeasure(widthSpec, heightSpec);
        }
    }


    /** Compact, labeled metric cards shared by charging and the reading-list widget. */
    private final class InfoCell extends LinearLayout {
        final TextView label, value;
        InfoCell(String icon, String title) {
            super(host.activity());
            setOrientation(VERTICAL);
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
            setBackground(ui.shape(ui.raised, 14, 0));
            label = ui.text(title, 12, false);
            label.setTextColor(ui.muted);
            label.setCompoundDrawablesRelative(glyphDrawable(icon, ui.muted, 15), null, null, null);
            label.setCompoundDrawablePadding(ui.dp(5));
            addView(label, new LinearLayout.LayoutParams(-1, -2));
            // Keep ordinary number/unit pairs together before permitting a
            // readable-floor wrap for unusually long diagnostic values.
            value = new FittedValue("—", 12, 26, 1);
            value.setIncludeFontPadding(true);
            value.setGravity(Gravity.CENTER_VERTICAL);
            value.setMinHeight(ui.dp(48 * ui.textScale));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
            p.topMargin = ui.dp(2);
            addView(value, p);
        }
    }

    private final class InfoTiles extends LinearLayout {
        final ArrayList<InfoCell> cells = new ArrayList<>();
        int columns;
        InfoTiles() { super(host.activity()); setOrientation(VERTICAL); }
        InfoCell add(String icon, String title) {
            InfoCell cell = new InfoCell(icon, title); cells.add(cell); columns = 0;
            requestLayout(); return cell;
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int next = MeasureSpec.getSize(widthSpec) >= ui.dp(280 * ui.textScale) ? 2 : 1;
            next = Math.min(next, Math.max(1, cells.size()));
            if (next != columns) {
                columns = next;
                for (InfoCell cell : cells) if (cell.getParent() instanceof ViewGroup)
                    ((ViewGroup)cell.getParent()).removeView(cell);
                removeAllViews();
                for (int i = 0; i < cells.size(); i += columns) {
                    LinearLayout line = row(); line.setBaselineAligned(false);
                    for (int j = 0; j < columns && i + j < cells.size(); j++) {
                        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
                        if (j > 0) p.setMarginStart(ui.dp(8));
                        line.addView(cells.get(i + j), p);
                    }
                    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
                    if (i > 0) p.topMargin = ui.dp(8);
                    addView(line, p);
                }
            }
            super.onMeasure(widthSpec, heightSpec);
        }
    }

    /**
     * Charging takeover, kept as a hero above the grid: shown only while charging is freshly
     * reported, with the percentage, the scooter photo over a red glow and a floor line that
     * fills to the charge and then, in grey, to the charge limit.
     */
    private final class ChargingHero extends LinearLayout {
        final TextView chip, percent, etaLabel, eta, power, note;
        final ImageView photo;
        final Stage stage;
        double level = Double.NaN, limit = 100;
        boolean shown;
        android.animation.ValueAnimator reveal;
        int revealGeneration;

        ChargingHero() {
            super(host.activity());
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            setPadding(0, ui.dp(2), 0, ui.dp(14));
            setVisibility(View.GONE);
            chip = ui.text("Charging", 12.5f, false);
            chip.setTypeface(ui.semibold());
            chip.setTextColor(ui.accent);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(ui.dp(10), ui.dp(4), ui.dp(12), ui.dp(4));
            chip.setBackground(ui.shape(Ui.withAlpha(ui.accent, ui.dark ? 34 : 24), 100, 0));
            chip.setCompoundDrawablesRelative(glyphDrawable("bolt", ui.accent, 14), null, null, null);
            chip.setCompoundDrawablePadding(ui.dp(5));
            addView(chip, new LinearLayout.LayoutParams(-2, -2));
            percent = ui.text("—", 72, true);
            percent.setGravity(Gravity.CENTER);
            percent.setLineSpacing(0, 1);
            LinearLayout.LayoutParams percentSize = new LinearLayout.LayoutParams(-2, -2);
            percentSize.topMargin = ui.dp(6);
            addView(percent, percentSize);
            stage = new Stage();
            photo = new ImageView(host.activity());
            photo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            photo.setContentDescription("NAVEE UT5 MAX scooter");
            FrameLayout.LayoutParams photoSize =
                    new FrameLayout.LayoutParams(-1, ui.dp(196), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            photoSize.bottomMargin = ui.dp(20);
            stage.addView(photo, photoSize);
            addView(stage, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout rows = column();
            InfoTiles tiles = new InfoTiles();
            InfoCell powerCell = tiles.add("bolt", "Charging power");
            InfoCell timeCell = tiles.add("clock", "Time to full");
            power = powerCell.value;
            etaLabel = timeCell.label;
            eta = timeCell.value;
            rows.addView(tiles, new LinearLayout.LayoutParams(-1, -2));
            note = ui.caption("");
            note.setPadding(0, ui.dp(4), 0, 0);
            rows.addView(note);
            addView(rows, new LinearLayout.LayoutParams(-1, -2));
        }

        void stopReveal() {
            revealGeneration++;
            if (reveal != null) { reveal.cancel(); reveal = null; }
            if (getLayoutParams() != null) { getLayoutParams().height = -2; requestLayout(); }
            setAlpha(1); setTranslationY(0);
            setVisibility(shown ? View.VISIBLE : View.GONE);
        }

        private void showAnimated(boolean show) {
            if (show == shown) return;
            int current = getVisibility() == View.VISIBLE ? getHeight() : 0;
            shown = show; stopReveal();
            View parent = getParent() instanceof View ? (View)getParent() : null;
            if (!Ui.motionEnabled() || closed || parent == null || parent.getWidth() == 0) return;
            setVisibility(View.VISIBLE);
            int width = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
            measure(MeasureSpec.makeMeasureSpec(Math.max(1, width), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int full = Math.max(1, getMeasuredHeight());
            int start = Math.max(0, Math.min(full, current)), end = show ? full : 0;
            final int generation = revealGeneration;
            reveal = android.animation.ValueAnimator.ofInt(start, end);
            reveal.setDuration(240);
            reveal.setInterpolator(new android.view.animation.DecelerateInterpolator());
            reveal.addUpdateListener(a -> {
                if (generation != revealGeneration) return;
                if (!Ui.motionEnabled() || closed) { stopReveal(); return; }
                int h = (Integer)a.getAnimatedValue();
                getLayoutParams().height = h; requestLayout();
                float fraction = Math.max(0, Math.min(1, h / (float)full));
                setAlpha(fraction); setTranslationY(-ui.dp(16) * (1 - fraction));
            });
            reveal.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator animation) {
                    if (generation == revealGeneration) stopReveal();
                }
            });
            getLayoutParams().height = start; requestLayout();
            reveal.start();
        }

        @Override protected void onDetachedFromWindow() { stopReveal(); super.onDetachedFromWindow(); }

        void refresh(Map<String, TelemetryHistory.Reading> values) {
            ChargingEstimate.Result estimate = chargingEstimate(values);
            boolean show = estimate.charging && !editing;
            if (!show) { showAnimated(false); return; }
            TelemetryHistory.Reading charge = values.get("battery_percent");
            level = charge == null || charge.number == null ? Double.NaN
                    : Math.max(0, Math.min(100, charge.number));
            boolean hasLimit = estimate.targetPercent != null && estimate.targetPercent < 100;
            limit = hasLimit ? estimate.targetPercent : 100;
            chip.setText(hasLimit
                    ? String.format(Locale.US, "Charging · limit %.0f%%", estimate.targetPercent)
                    : "Charging");
            if (Double.isNaN(level)) {
                percent.setText("—");
                percent.setContentDescription("Battery not reported");
            } else {
                String number = Long.toString(Math.round(level));
                android.text.SpannableString text = new android.text.SpannableString(number + "%");
                text.setSpan(new android.text.style.RelativeSizeSpan(.4f), number.length(), number.length() + 1,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                percent.setText(text);
                percent.setContentDescription("Battery " + number + " percent");
            }
            ScooterProfiles.Profile p = service == null ? null : service.profiles.active();
            boolean known = p != null && p.pid() == 2538;
            if (known && photo.getDrawable() == null) photo.setImageBitmap(scooterPhoto());
            photo.setVisibility(known ? View.VISIBLE : View.GONE);
            stage.setMinimumHeight(ui.dp(known ? 236 : 44));
            etaLabel.setText(hasLimit
                    ? String.format(Locale.US, "Time to %.0f%%", estimate.targetPercent)
                    : "Time to full");
            eta.setText(duration(hasLimit ? estimate.remainingMinutesToLimit : estimate.remainingMinutesToFull));
            String watts = estimate.inputWatts == null ? null
                    : String.format(Locale.US, "%.0f W", estimate.inputWatts);
            power.setText(watts == null ? "—" : watts);
            power.setTextColor(watts == null ? ui.muted : ui.ok);
            note.setText(estimate.status == null ? "" : estimate.status);
            note.setVisibility(note.getText().length() == 0 ? View.GONE : View.VISIBLE);
            stage.setContentDescription(
                    (Double.isNaN(level) ? "Charge not reported" : "Charge " + Math.round(level) + " percent")
                            + (hasLimit ? ", charge limit " + Math.round(limit) + " percent" : ""));
            stage.invalidate();
            showAnimated(true);
        }

        /** Glow and floor line under the photo. */
        private final class Stage extends FrameLayout {
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            Stage() {
                super(host.activity());
                setWillNotDraw(false);
                setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            }

            @Override
            protected void onDraw(Canvas canvas) {
                float w = getWidth(), floor = getHeight() - ui.dp(18);
                float left = w * .06f, right = w * .94f;
                if (photo.getVisibility() == View.VISIBLE) {
                    // A soft red glow on the floor under the wheels.
                    float rx = w * .42f, ry = ui.dp(34);
                    canvas.save();
                    canvas.translate(w / 2, floor);
                    canvas.scale(1, ry / rx);
                    paint.setStyle(Paint.Style.FILL);
                    paint.setShader(new RadialGradient(0, 0, rx,
                            new int[] {Ui.withAlpha(ui.accent, ui.dark ? 140 : 90), Ui.withAlpha(ui.accent, 0)},
                            null, Shader.TileMode.CLAMP));
                    canvas.drawCircle(0, 0, rx, paint);
                    paint.setShader(null);
                    canvas.restore();
                }
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeWidth(ui.dp(3));
                float span = right - left;
                float limitX = left + span * (float) (limit / 100);
                paint.setColor(ui.raised);
                canvas.drawLine(left, floor, limitX, floor, paint);
                if (!Double.isNaN(level) && level > 0) {
                    paint.setColor(ui.accent);
                    canvas.drawLine(left, floor, left + span * (float) (level / 100), floor, paint);
                }
            }
        }
    }

    /** Types whose content carries its own heading, so the card shows no title line. */
    private static boolean untitled(String type) {
        return type.equals("lock") || type.equals("battery") || type.equals("scooter")
                || type.equals("ride") || type.equals("capacity");
    }

    private final class Tile extends FrameLayout {
        final DashboardConfig.Widget widget;
        final LinearLayout body, heading, content;
        final TextView footer, title;
        final LinkedHashMap<String, TextView> amounts = new LinkedHashMap<>();
        /** Readings that decide the last-seen footer besides the visible amounts. */
        final ArrayList<String> watched = new ArrayList<>();
        BatteryCell battery;
        Ui.ChartView chart;
        Gauge gauge;
        GlyphView headingIcon, recordGlyph;
        TextView big, subtitle, healthDetail, rideTitle, rideDetail;
        View rideButton, recordDisc;
        TextView capacityValue, capacitySummary, capacityCompare, capacityEvidence, capacityMethod, capacityNote;
        CapacityBar capacityBar;
        TextView chargingWatts,
                chargingEta,
                chargingEtaLabel,
                chargingNote,
                chargingTemperature;
        LinearLayout batteryData, chargingData;
        boolean capacityPending, dragging, showsRange;
        View resizeControl;
        QuickAccessGrid quickAccess;
        float jiggleAngle;
        Boolean recordingShown;
        long capacityChecked;
        ImageView photo;
        TextView photoHint;
        ScrollView viewport;
        LinearLayout scooterRow, scooterInfo;
        RecordLead recorder;
        boolean compactRecorder;
        FrameLayout photoPane;
        final ArrayList<LinearLayout> scooterStats = new ArrayList<>();
        int scooterColumns;
        final ArrayList<Ui.Point> livePoints = new ArrayList<>();
        boolean wasLive;
        boolean active = true, querying;
        long queriedAt;
        String queryKey = "";
        List<TelemetryHistory.Point> history = Collections.emptyList();

        Tile(DashboardConfig.Widget widget) {
            super(host.activity());
            this.widget = widget;
            setClipChildren(false);
            setClipToPadding(false);
            setBackground(chrome());
            content = column();
            int pad = widget.type.equals("battery") ? 8 : 14;
            content.setPadding(ui.dp(pad), ui.dp(widget.type.equals("battery") ? 8 : 12), ui.dp(pad),
                    ui.dp(widget.type.equals("battery") ? 8 : 12));
            addView(content, new FrameLayout.LayoutParams(-1, -1));
            heading = row();
            heading.setGravity(Gravity.CENTER_VERTICAL);
            title = ui.text(widget.title, 13, false);
            title.setTypeface(ui.medium());
            title.setTextColor(ui.muted);
            title.setMaxLines(2);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            if (widget.type.equals("note")) {
                title.setCompoundDrawablesRelative(glyphDrawable("note", ui.muted, 15), null, null, null);
                title.setCompoundDrawablePadding(ui.dp(6));
            }
            heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            if (widget.type.equals("metric")) {
                headingIcon = new GlyphView(readingIcon(widget.metric), ui.accent);
                LinearLayout.LayoutParams iconSize = new LinearLayout.LayoutParams(ui.dp(19), ui.dp(19));
                iconSize.setMarginStart(ui.dp(8));
                heading.addView(headingIcon, iconSize);
            }
            heading.setVisibility(untitled(widget.type) ? View.GONE : View.VISIBLE);
            content.addView(heading, new LinearLayout.LayoutParams(-1, -2));
            body = column();
            if (widget.type.equals("lock") || widget.type.equals("metric")
                    || widget.type.equals("gauge") || widget.type.equals("ride"))
                body.setGravity(Gravity.CENTER_VERTICAL);
            body.setPadding(0, untitled(widget.type) ? 0 : ui.dp(6), 0, 0);
            viewport = new WidgetScrollView();
            viewport.setVerticalScrollBarEnabled(true);
            viewport.setScrollbarFadingEnabled(false);
            viewport.setFillViewport(true);
            viewport.setClipToPadding(false);
            viewport.addView(body, new ScrollView.LayoutParams(-1, -1));
            content.addView(viewport, new LinearLayout.LayoutParams(-1, 0, 1));
            footer = ui.caption("");
            footer.setTextColor(ui.dim);
            ui.fitStatusLine(footer);
            footer.setPadding(0, ui.dp(4), 0, 0);
            content.addView(footer);
            build();
            if (editing) {
                FrameLayout grip =
                        overlay("grip", "Move " + widget.title, () -> position(widget));
                EditorTouch touch = new EditorTouch(this, false);
                grip.setTag(touch);
                grip.setOnTouchListener(touch);
                grip.setOnLongClickListener(
                        v -> {
                            touch.start();
                            return true;
                        });
                EditorBar editor = new EditorBar(grip);
                editor.setTag("widget-editor-bar");
                addView(editor, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
                FrameLayout resize = cornerHandle("Resize " + widget.title, () -> size(widget));
                resizeControl = resize;
                resize.setOnTouchListener(new EditorTouch(this, true));
                addFloating(resize, Gravity.BOTTOM | Gravity.RIGHT);
            }
        }

        @Override public void draw(Canvas canvas) {
            if (jiggleAngle == 0) { super.draw(canvas); return; }
            // A drawing cue only: card layout, native rotation and hit targets stay fixed.
            int saved = canvas.save();
            canvas.rotate(jiggleAngle, getWidth() / 2f, getHeight() / 2f);
            super.draw(canvas);
            canvas.restoreToCount(saved);
        }

        /** Volt card: surface, 20 dp corners; a faint edge while editing, red while dragged or recording. */
        android.graphics.drawable.Drawable chrome() {
            boolean recording = widget.type.equals("ride") && service != null && service.isRecording();
            GradientDrawable card = new GradientDrawable();
            card.setCornerRadius(ui.dp(20));
            if (recording) {
                card.setOrientation(GradientDrawable.Orientation.TL_BR);
                card.setColors(new int[] {blend(ui.surface, ui.accent, ui.dark ? .14f : .08f), ui.surface});
            } else card.setColor(ui.surface);
            if (dragging) card.setStroke(ui.dp(1.5f), ui.accent);
            else if (recording) card.setStroke(ui.dp(1), blend(ui.surface, ui.accent, .32f));
            else if (editing) card.setStroke(ui.dp(1), Ui.withAlpha(ui.text, ui.dark ? 22 : 30));
            return card;
        }

        /** Slightly larger icon squares; four separate targets still fit compact cards. */
        private FrameLayout overlay(String icon, String label, Runnable action) {
            FrameLayout control = new FrameLayout(host.activity());
            android.graphics.drawable.LayerDrawable square = new android.graphics.drawable.LayerDrawable(
                    new android.graphics.drawable.Drawable[] {ui.shape(Color.argb(160, 0, 0, 0), 8, 0)});
            square.setLayerSize(0, ui.dp(30), ui.dp(30));
            square.setLayerGravity(0, Gravity.CENTER);
            control.setBackground(ripple(Ui.withAlpha(ui.accent, 60), square, ui.shape(Color.WHITE, 14, 0)));
            control.setPadding(0, 0, 0, 0);
            control.addView(new GlyphView(icon, icon.equals("trash") ? ui.danger : Color.WHITE),
                    new FrameLayout.LayoutParams(ui.dp(17), ui.dp(17), Gravity.CENTER));
            control.setContentDescription(label);
            if (!icon.equals("grip")) control.setTooltipText(label);
            control.setFocusable(true);
            control.setClickable(true);
            control.setOnClickListener(v -> action.run());
            return control;
        }

        /** Overlay actions keep their touch targets; tiny rails move secondary actions into a popup. */
        private final class EditorBar extends ViewGroup {
            final View grip, editButton, duplicateButton, removeButton;
            int target, controlColumns;
            boolean compactRail;
            PopupMenu actionsMenu;

            EditorBar(View grip) {
                super(host.activity());
                this.grip = grip;
                editButton = overlay("gear", "Options for " + widget.title, () -> options(widget));
                editButton.setOnClickListener(v -> {
                    if (compactRail) showActions(); else options(widget);
                });
                duplicateButton = overlay("copy", "Duplicate " + widget.title, () -> duplicateWidget(widget));
                removeButton = overlay("trash", "Remove " + widget.title, () -> removeWidget(widget));
                duplicateButton.setEnabled(!widget.type.equals("lock") && config.widgets.size() < 32);
                duplicateButton.setAlpha(duplicateButton.isEnabled() ? 1f : .42f);
                duplicateButton.setTooltipText(widget.type.equals("lock")
                        ? "One lock widget per dashboard" : "Duplicate widget");
                for (View control : new View[] {grip, editButton, duplicateButton, removeButton}) addView(control);
            }

            private void showActions() {
                if (actionsMenu != null) actionsMenu.dismiss();
                actionsMenu = new PopupMenu(host.activity(), editButton);
                actionsMenu.getMenu().add(0, 1, 0, "Edit widget").setIcon(glyphDrawable("gear", ui.text, 20));
                actionsMenu.getMenu().add(0, 2, 1, "Duplicate widget").setIcon(glyphDrawable("copy", ui.text, 20))
                        .setEnabled(!widget.type.equals("lock") && config.widgets.size() < 32);
                android.text.SpannableString removeLabel = new android.text.SpannableString("Remove widget");
                removeLabel.setSpan(new android.text.style.ForegroundColorSpan(ui.danger), 0,
                        removeLabel.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                actionsMenu.getMenu().add(0, 3, 2, removeLabel).setIcon(glyphDrawable("trash", ui.danger, 20));
                if (android.os.Build.VERSION.SDK_INT >= 29) actionsMenu.setForceShowIcon(true);
                actionsMenu.setOnMenuItemClickListener(item -> {
                    if (!editing) return false;
                    if (item.getItemId() == 1) options(widget);
                    else if (item.getItemId() == 2) duplicateWidget(widget);
                    else if (item.getItemId() == 3) removeWidget(widget);
                    else return false;
                    return true;
                });
                actionsMenu.show();
            }

            @Override protected void onDetachedFromWindow() {
                if (actionsMenu != null) { actionsMenu.dismiss(); actionsMenu = null; }
                super.onDetachedFromWindow();
            }

            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int width = MeasureSpec.getSize(widthSpec);
                compactRail = widget.type.equals("quick") && width < ui.dp(96);
                duplicateButton.setVisibility(compactRail ? GONE : VISIBLE);
                removeButton.setVisibility(compactRail ? GONE : VISIBLE);
                controlColumns = width < ui.dp(144) ? (width >= ui.dp(96) ? 2 : 1) : 4;
                target = Math.min(ui.dp(controlColumns < 4 ? 48 : 52),
                        (width - (compactRecorder ? ui.dp(48) : 0)) / controlColumns);
                for (int i = 0; i < getChildCount(); i++)
                    getChildAt(i).measure(MeasureSpec.makeMeasureSpec(target, MeasureSpec.EXACTLY),
                            MeasureSpec.makeMeasureSpec(target, MeasureSpec.EXACTLY));
                setMeasuredDimension(width, target * ((compactRail ? 2 : 4) / controlColumns));
            }

            @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                if (controlColumns < 4) {
                    for (int i = 0; i < (compactRail ? 2 : 4); i++) {
                        int x = (i % controlColumns) * target, y = (i / controlColumns) * target;
                        getChildAt(i).layout(x, y, x + target, y + target);
                    }
                    return;
                }
                grip.layout(0, 0, target, target);
                editButton.layout(target, 0, target * 2, target);
                duplicateButton.layout(target * 2, 0, target * 3, target);
                int end = getWidth() - (compactRecorder ? ui.dp(48) : 0);
                removeButton.layout(end - target, 0, end, target);
            }
        }

        /** The resize corner: a white L mark at the lower-right edge of the card. */
        private FrameLayout cornerHandle(String label, Runnable action) {
            FrameLayout control = new FrameLayout(host.activity());
            control.addView(new CornerMark(), new FrameLayout.LayoutParams(-1, -1));
            control.setBackground(ripple(Ui.withAlpha(ui.accent, 60), null, ui.shape(Color.WHITE, 14, 0)));
            control.setContentDescription(label);
            control.setFocusable(true);
            control.setClickable(true);
            control.setOnClickListener(v -> action.run());
            return control;
        }

        private void addFloating(View control, int gravity) {
            addView(control, new FrameLayout.LayoutParams(ui.dp(48), ui.dp(48), gravity));
        }

        private void build() {
            switch (widget.type) {
                case "lock":
                    body.addView(actions.lock(widget.showDisconnect));
                    break;
                case "scooter":
                    scooterRow = row();
                    scooterRow.setGravity(Gravity.CENTER_VERTICAL);
                    photoPane = new FrameLayout(host.activity());
                    photo = new ImageView(host.activity());
                    photo.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    photo.setContentDescription("NAVEE UT5 MAX scooter");
                    photo.setImageBitmap(scooterPhoto());
                    photoPane.addView(photo, new FrameLayout.LayoutParams(-1, -1));
                    photoHint = ui.caption("Scooter");
                    photoHint.setGravity(Gravity.CENTER);
                    photoPane.addView(photoHint, new FrameLayout.LayoutParams(-1, -1));
                    scooterRow.addView(
                            photoPane, new LinearLayout.LayoutParams(ui.dp(88), ui.dp(100)));
                    scooterInfo = column();
                    LinearLayout.LayoutParams info = new LinearLayout.LayoutParams(0, -2, 1);
                    info.leftMargin = ui.dp(10);
                    scooterRow.addView(scooterInfo, info);
                    body.addView(scooterRow);
                    for (String key : widget.keys) {
                        LinearLayout item = column();
                        item.setPadding(0, ui.dp(3), ui.dp(4), ui.dp(3));
                        TextView label = ui.caption(ScooterReadingLabels.label(key, label(key)));
                        label.setContentDescription(label(key));
                        item.setTooltipText(label(key));
                        label.setTextSize(12.5f * ui.textScale);
                        item.addView(label, new LinearLayout.LayoutParams(-1, -2));
                        // Short captions leave more room for prominent readings. The
                        // fitter still uses actual width and each row's height budget,
                        // keeping narrow/short cards and long readings readable.
                        TextView value = new FittedValue(
                                "—", 14, widget.keys.size() <= 2 ? 32 : 28,
                                key.equals("driving_mode") ? 1 : 2);
                        item.addView(value, new LinearLayout.LayoutParams(-1, -2));
                        scooterStats.add(item);
                        amounts.put(key, value);
                    }
                    break;
                case "battery":
                    battery = new BatteryCell();
                    body.addView(battery, new LinearLayout.LayoutParams(-1, ui.dp(150)));
                    IconReadingStrip readings = new IconReadingStrip(IconReadingStrip.READINGS);
                    batteryData = readings;
                    // Range is spoken inside the battery cell; other readings sit underneath.
                    for (String key : widget.keys) {
                        if (key.equals("remaining_range_km")) {
                            showsRange = true;
                            watched.add(key);
                        } else amounts.put(key, readings.addReading(readingIcon(key), label(key)));
                    }
                    LinearLayout.LayoutParams stripSpace = new LinearLayout.LayoutParams(-1, -2);
                    stripSpace.topMargin = ui.dp(8);
                    body.addView(batteryData, stripSpace);
                    IconReadingStrip charging = new IconReadingStrip(IconReadingStrip.READINGS);
                    chargingData = charging;
                    chargingWatts = charging.addReading("bolt", "Charging power");
                    chargingEta = charging.addReading("clock", "Time to full");
                    chargingTemperature = charging.addReading("temperature", "Battery temperature");
                    // Compact status is announced and available on long press, without
                    // adding a second block of explanatory text to the glanceable card.
                    chargingEtaLabel = ui.caption("Time to full");
                    chargingNote = ui.caption("");
                    LinearLayout.LayoutParams chargingSpace = new LinearLayout.LayoutParams(-1, -2);
                    chargingSpace.topMargin = ui.dp(8);
                    body.addView(chargingData, chargingSpace);
                    break;
                case "metric":
                    big = new FittedValue("—", 14, 34, 2);
                    body.addView(big, new LinearLayout.LayoutParams(-1, -2));
                    subtitle = ui.caption(label(widget.metric));
                    subtitle.setPadding(0, ui.dp(2), 0, 0);
                    body.addView(subtitle);
                    break;
                case "gauge":
                    gauge = new Gauge(widget);
                    body.addView(gauge, new LinearLayout.LayoutParams(-1, ui.dp(140)));
                    subtitle = ui.caption(label(widget.metric));
                    subtitle.setGravity(Gravity.CENTER);
                    body.addView(subtitle);
                    break;
                case "live":
                case "history":
                    chart = ui.new ChartView();
                    chart.setHistoryMode(true);
                    body.addView(chart, new LinearLayout.LayoutParams(-1, ui.dp(140)));
                    subtitle = ui.caption(label(widget.metric));
                    body.addView(subtitle);
                    break;
                case "health":
                    final TextView health = new FittedValue("—", 14, 34, 1);
                    health.setIncludeFontPadding(true);
                    health.setPadding(0, ui.dp(3), 0, ui.dp(2));
                    healthDetail = ui.caption("");
                    final TextView detail = healthDetail;
                    LinearLayout top =
                            new LinearLayout(host.activity()) {
                                @Override
                                protected void onMeasure(int widthSpec, int heightSpec) {
                                    // The percentage is the card's main reading: in a narrow card
                                    // (or with large text) the details stack underneath it instead
                                    // of taking the row, and side by side they never take over half.
                                    int width = MeasureSpec.getSize(widthSpec);
                                    float scale = Math.max(1f, ui.textScale
                                            * getResources().getConfiguration().fontScale);
                                    boolean stack = width < ui.dp(220 * scale);
                                    if (stack != (getOrientation() == VERTICAL)) layoutHealth(this, health, detail, stack);
                                    int limit = stack ? Integer.MAX_VALUE : Math.max(ui.dp(48), (int) (width * .5f));
                                    // setMaxWidth requests layout, so only change it when it differs.
                                    if (detail.getMaxWidth() != limit) detail.setMaxWidth(limit);
                                    super.onMeasure(widthSpec, heightSpec);
                                }
                            };
                    amounts.put("battery_health_percent", health);
                    top.addView(health);
                    top.addView(healthDetail);
                    layoutHealth(top, health, detail, false);
                    body.addView(top, new LinearLayout.LayoutParams(-1, -2));
                    watched.add("charge_cycles");
                    watched.add("rated_capacity_ah");
                    chart = ui.new ChartView();
                    chart.setHistoryMode(true);
                    body.addView(chart, new LinearLayout.LayoutParams(-1, ui.dp(190)));
                    subtitle = ui.caption("Reported by the controller, not a capacity test");
                    body.addView(subtitle);
                    break;
                case "capacity":
                    TextView name = ui.text(widget.title, 14, false);
                    name.setTextColor(ui.muted);
                    name.setCompoundDrawablesRelative(glyphDrawable("lab", ui.accent, 18), null, null, null);
                    name.setCompoundDrawablePadding(ui.dp(6));
                    body.addView(name);
                    capacityMethod = ui.caption("Experimental estimate");
                    capacityMethod.setPadding(0, ui.dp(4), 0, 0);
                    body.addView(capacityMethod);
                    capacityValue = new FittedValue("Not enough ride data", 16, 34, 2);
                    capacityValue.setPadding(0, ui.dp(8), 0, ui.dp(4));
                    body.addView(capacityValue, new LinearLayout.LayoutParams(-1, -2));
                    capacityCompare = ui.text("", 14, true);
                    body.addView(capacityCompare);
                    capacityBar = new CapacityBar();
                    LinearLayout.LayoutParams barSpace = new LinearLayout.LayoutParams(-1, ui.dp(10));
                    barSpace.topMargin = barSpace.bottomMargin = ui.dp(10);
                    body.addView(capacityBar, barSpace);
                    capacitySummary = ui.caption("Record rides to build an estimate");
                    body.addView(capacitySummary);
                    // Full evidence and caveats stay one tap away, rather than filling the card.
                    capacityEvidence = ui.caption("Checking recorded rides…");
                    capacityNote = ui.caption("Experimental; depends on battery gauge and riding conditions.");
                    TextView evidence = ui.secondaryButton("Estimate details", () -> {
                        LinearLayout details = column();
                        details.addView(ui.text(capacityMethod.getText().toString(), 18, true));
                        details.addView(ui.caption(capacityEvidence.getText().toString()));
                        details.addView(ui.caption(capacityNote.getText().toString()));
                        details.addView(ui.secondaryButton("How this is estimated", () -> showCapacityMethod(widget)));
                        show("Capacity estimate", details);
                    });
                    LinearLayout.LayoutParams evidenceSpace = new LinearLayout.LayoutParams(-1, -2);
                    evidenceSpace.topMargin = ui.dp(8);
                    body.addView(evidence, evidenceSpace);
                    break;
                case "charging":
                    buildCharging(body, true);
                    break;
                case "readings":
                    InfoTiles readingTiles = new InfoTiles();
                    for (String key : widget.keys) {
                        InfoCell cell = readingTiles.add(readingIcon(key), ScooterReadingLabels.label(key, label(key)));
                        amounts.put(key, cell.value);
                    }
                    body.addView(readingTiles, new LinearLayout.LayoutParams(-1, -2));
                    break;
                case "trip":
                    IconReadingStrip stats = new IconReadingStrip(IconReadingStrip.STATS);
                    for (String key :
                            new String[] {
                                "speed_kmh",
                                "trip_km",
                                "trip_minutes",
                                "maximum_speed_kmh",
                                "odometer_km"
                            }) amounts.put(key, stats.addReading(null, label(key)));
                    body.addView(stats, new LinearLayout.LayoutParams(-1, -2));
                    break;
                case "quick":
                    quickAccess = new QuickAccessGrid(host, widget.quickActions, editing,
                            id -> { if (!editing) actions.quickAction(id); });
                    body.addView(quickAccess, new LinearLayout.LayoutParams(-1, -2));
                    break;
                case "ride":
                    buildRecorder();
                    break;
                case "note":
                    subtitle =
                            ui.text(
                                    widget.note.isEmpty()
                                            ? "Add a reminder in widget options."
                                            : widget.note,
                                    15,
                                    false);
                    if (widget.note.isEmpty()) subtitle.setTextColor(ui.muted);
                    body.addView(subtitle);
                    break;
            }
        }

        /** Record card: a red circle that starts or stops, the state beside it, and a way to Rides. */
        private void buildRecorder() {
            LinearLayout line = row();
            line.setGravity(Gravity.CENTER_VERTICAL);
            FrameLayout disc = new FrameLayout(host.activity());
            recordDisc = disc;
            recordGlyph = new GlyphView("record", ui.onAccent);
            disc.addView(recordGlyph, new FrameLayout.LayoutParams(ui.dp(20), ui.dp(20), Gravity.CENTER));
            LinearLayout words = column();
            rideTitle = ui.text("Record ride", 15, false);
            rideTitle.setTypeface(ui.medium());
            words.addView(rideTitle);
            rideDetail = ui.caption("GPS · elevation · motion");
            rideDetail.setPadding(0, ui.dp(2), 0, 0);
            words.addView(rideDetail);
            RecordLead lead = new RecordLead(disc, words);
            recorder = lead;
            rideButton = lead;
            lead.setOnClickListener(
                    v -> {
                        if (service != null && service.isRecording()) service.stopRide();
                        else actions.startRide();
                        host.redraw();
                    });
            line.addView(lead, new LinearLayout.LayoutParams(0, -2, 1));
            FrameLayout more = new FrameLayout(host.activity());
            more.addView(new GlyphView("chevron", ui.muted),
                    new FrameLayout.LayoutParams(ui.dp(16), ui.dp(16), Gravity.CENTER));
            more.setContentDescription("View rides");
            more.setFocusable(true);
            more.setClickable(true);
            more.setBackground(ripple(Ui.withAlpha(ui.accent, 30), null, ui.shape(Color.WHITE, 14, 0)));
            more.setOnClickListener(v -> actions.navigate("rides"));
            line.addView(more, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
            body.addView(line, new LinearLayout.LayoutParams(-1, -2));
        }

        /** Disc and words side by side, stacking on narrow cards so the words stay readable. */
        private final class RecordLead extends LinearLayout {
            final View disc, words;

            RecordLead(View disc, View words) {
                super(host.activity());
                this.disc = disc;
                this.words = words;
                setMinimumHeight(ui.dp(56));
                setPadding(0, ui.dp(4), ui.dp(4), ui.dp(4));
                setClickable(true);
                setFocusable(true);
                setBackground(ripple(Ui.withAlpha(ui.accent, 30), null, ui.shape(Color.WHITE, 14, 0)));
                addView(disc, new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
                addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            }

            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                boolean narrow = !compactRecorder && MeasureSpec.getSize(widthSpec) < ui.dp(170 * ui.textScale);
                setMinimumHeight(ui.dp(compactRecorder ? 48 : 56));
                setPadding(0, compactRecorder ? 0 : ui.dp(4), ui.dp(4), compactRecorder ? 0 : ui.dp(4));
                disc.getLayoutParams().width = disc.getLayoutParams().height = ui.dp(compactRecorder ? 28 : 40);
                rideDetail.setVisibility(compactRecorder ? GONE : VISIBLE);
                rideTitle.setMaxLines(compactRecorder ? 1 : Integer.MAX_VALUE);
                rideTitle.setEllipsize(compactRecorder ? android.text.TextUtils.TruncateAt.END : null);
                setOrientation(narrow ? VERTICAL : HORIZONTAL);
                setGravity(narrow ? Gravity.START : Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) words.getLayoutParams();
                int width = narrow ? -1 : 0, start = narrow ? 0 : ui.dp(12), top = narrow ? ui.dp(8) : 0;
                float weight = narrow ? 0 : 1;
                if (p.width != width || p.weight != weight || p.getMarginStart() != start || p.topMargin != top) {
                    p.width = width;
                    p.weight = weight;
                    p.setMarginStart(start);
                    p.topMargin = top;
                    words.setLayoutParams(p);
                }
                super.onMeasure(widthSpec, heightSpec);
            }
        }

        /** Capacity row: flask square, name and evidence, with the Wh at the end (or below when narrow). */
        private final class CapacityRow extends LinearLayout {
            final LinearLayout words;
            final TextView value;
            Boolean narrow;

            CapacityRow(View square, LinearLayout words, TextView value) {
                super(host.activity());
                this.words = words;
                this.value = value;
                setOrientation(HORIZONTAL);
                setGravity(Gravity.CENTER_VERTICAL);
                addView(square, new LinearLayout.LayoutParams(ui.dp(38), ui.dp(38)));
                LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(0, -2, 1);
                space.setMarginStart(ui.dp(12));
                addView(words, space);
            }

            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                boolean next = MeasureSpec.getSize(widthSpec) < ui.dp(240 * ui.textScale);
                if (narrow == null || narrow != next) {
                    narrow = next;
                    if (value.getParent() instanceof ViewGroup) ((ViewGroup) value.getParent()).removeView(value);
                    value.setGravity(next ? Gravity.START : Gravity.END);
                    if (next) {
                        LinearLayout.LayoutParams below = new LinearLayout.LayoutParams(-1, -2);
                        below.topMargin = ui.dp(4);
                        words.addView(value, below);
                    } else {
                        LinearLayout.LayoutParams end = new LinearLayout.LayoutParams(-2, -2);
                        end.setMarginStart(ui.dp(10));
                        addView(value, end);
                    }
                }
                int limit = next ? Integer.MAX_VALUE : Math.round(MeasureSpec.getSize(widthSpec) * .42f);
                if (value.getMaxWidth() != limit) value.setMaxWidth(limit);
                super.onMeasure(widthSpec, heightSpec);
            }
        }

        /** Let a swipe anywhere inside overflowing content scroll that widget first. */
        private final class WidgetScrollView extends ScrollView {
            float startY;

            WidgetScrollView() {
                super(host.activity());
            }

            @Override
            public boolean onInterceptTouchEvent(MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startY = event.getY();
                        if (!editing && (canScrollVertically(-1) || canScrollVertically(1)))
                            getParent().requestDisallowInterceptTouchEvent(true);
                        break;
                    case MotionEvent.ACTION_MOVE:
                        float delta = event.getY() - startY;
                        if (Math.abs(delta) > ViewConfiguration.get(getContext()).getScaledTouchSlop()
                                && !canScrollVertically(delta > 0 ? -1 : 1))
                            getParent().requestDisallowInterceptTouchEvent(false);
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        getParent().requestDisallowInterceptTouchEvent(false);
                        break;
                }
                return super.onInterceptTouchEvent(event);
            }
        }

        /**
         * Cells that wrap into as many columns as fit: icon readings (battery), stat cells (trip)
         * or shortcut tiles (quick access). One glanceable row at ordinary widths.
         */
        private final class IconReadingStrip extends LinearLayout {
            static final int READINGS = 0, STATS = 1, TILES = 2;
            final ArrayList<LinearLayout> cells = new ArrayList<>();
            final int mode;
            int columns;

            IconReadingStrip(int mode) {
                super(host.activity());
                this.mode = mode;
                setOrientation(VERTICAL);
            }

            TextView addReading(String iconKey, String label) {
                LinearLayout cell = column();
                TextView value;
                if (mode == READINGS) {
                    cell.setGravity(Gravity.CENTER);
                    cell.setPadding(ui.dp(3), ui.dp(4), ui.dp(3), ui.dp(4));
                    cell.setTooltipText(label);
                    cell.addView(new GlyphView(iconKey, ui.muted),
                            new LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)));
                    value = new FittedValue("—", 12, 15, 2);
                    value.setGravity(Gravity.CENTER);
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
                    params.topMargin = ui.dp(3);
                    cell.addView(value, params);
                } else {
                    cell.setPadding(0, ui.dp(4), ui.dp(8), ui.dp(4));
                    value = new FittedValue("—", 12, 20, 2);
                    cell.addView(value, new LinearLayout.LayoutParams(-1, -2));
                    TextView name = ui.caption(label);
                    cell.addView(name, new LinearLayout.LayoutParams(-1, -2));
                }
                value.setContentDescription(label + ", not reported");
                cells.add(cell);
                return value;
            }

            void addShortcut(String label, String spoken, String page, String icon) {
                LinearLayout cell = column();
                cell.setGravity(Gravity.CENTER);
                cell.setMinimumHeight(ui.dp(64));
                cell.setPadding(ui.dp(4), ui.dp(10), ui.dp(4), ui.dp(10));
                cell.setBackground(ripple(Ui.withAlpha(ui.accent, 40), ui.shape(ui.raised, 12, 0), null));
                cell.addView(new GlyphView(icon, ui.accent), new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)));
                TextView name = ui.text(label, 12, false);
                name.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-2, -2);
                space.topMargin = ui.dp(5);
                cell.addView(name, space);
                cell.setContentDescription(spoken);
                cell.setTooltipText(spoken);
                cell.setClickable(true);
                cell.setFocusable(true);
                // Cells attach during measure, after disableInputs() ran, so they set their own
                // edit-mode state and the listener refuses to navigate while arranging widgets.
                cell.setEnabled(!editing);
                cell.setOnClickListener(v -> { if (!editing) actions.navigate(page); });
                cells.add(cell);
            }

            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                int width = MeasureSpec.getSize(widthSpec);
                float cellDp = mode == READINGS ? 62 : mode == STATS ? 92 : 72;
                int most = mode == STATS ? 3 : 4;
                int next = Math.min(Math.min(most, cells.size()),
                        Math.max(1, width / Math.max(1, ui.dp(cellDp * ui.textScale))));
                if (cells.size() == 4 && next == 3) next = 2;
                if (next != columns) {
                    columns = next;
                    if (mode == READINGS) for (LinearLayout cell : cells) {
                        // A one-column list reads naturally as icon + value. Repeating
                        // a separate icon row wastes height in narrow, large-text cards.
                        boolean inline = columns == 1;
                        cell.setOrientation(inline ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
                        cell.setGravity(inline ? Gravity.CENTER_VERTICAL : Gravity.CENTER);
                        View value = cell.getChildAt(1);
                        LinearLayout.LayoutParams valueSize = (LinearLayout.LayoutParams)value.getLayoutParams();
                        valueSize.width = inline ? 0 : -1;
                        valueSize.weight = inline ? 1 : 0;
                        valueSize.topMargin = inline ? 0 : ui.dp(3);
                        valueSize.setMarginStart(inline ? ui.dp(8) : 0);
                        value.setLayoutParams(valueSize);
                    }
                    for (LinearLayout cell : cells)
                        if (cell.getParent() instanceof ViewGroup)
                            ((ViewGroup) cell.getParent()).removeView(cell);
                    removeAllViews();
                    int gap = mode == TILES ? ui.dp(3) : 0;
                    for (int i = 0; i < cells.size(); i += columns) {
                        LinearLayout line = row();
                        line.setGravity(mode == READINGS ? Gravity.BOTTOM : Gravity.TOP);
                        line.setBaselineAligned(false);
                        for (int j = i; j < i + columns; j++) {
                            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, j < cells.size() ? -2 : 1, 1);
                            p.leftMargin = p.rightMargin = gap;
                            p.topMargin = p.bottomMargin = gap;
                            line.addView(j < cells.size() ? cells.get(j) : new View(host.activity()), p);
                        }
                        addView(line, new LinearLayout.LayoutParams(-1, -2));
                    }
                }
                super.onMeasure(widthSpec, heightSpec);
            }
        }

        private void viewSize(View view, int width, int height) {
            ViewGroup.LayoutParams params = view.getLayoutParams();
            if (params.width != width || params.height != height) {
                params.width = width;
                params.height = height;
                view.setLayoutParams(params);
            }
        }

        private void linearSize(View v, int width, int height, float weight, int margin) {
            LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) v.getLayoutParams();
            if (p.width != width
                    || p.height != height
                    || p.weight != weight
                    || p.leftMargin != margin) {
                p.width = width;
                p.height = height;
                p.weight = weight;
                p.leftMargin = margin;
                v.setLayoutParams(p);
            }
        }

        private void arrangeScooter(int width) {
            int photoWidth = Math.max(ui.dp(36), Math.min(Math.round(width * .45f),
                    width - ui.dp(10) - ui.dp(106 * ui.textScale)));
            viewSize(photoPane, photoWidth, photoPane.getLayoutParams().height);
            int available = width - photoWidth - ui.dp(10);
            boolean glanceable = widget.keys.size() <= 2;
            int columns = !glanceable && available >= ui.dp(180 * ui.textScale) ? 2 : 1;
            if (columns == scooterColumns) return;
            scooterColumns = columns;
            for (LinearLayout item : scooterStats)
                if (item.getParent() instanceof ViewGroup)
                    ((ViewGroup) item.getParent()).removeView(item);
            scooterInfo.removeAllViews();
            for (int i = 0; i < scooterStats.size(); i += columns) {
                LinearLayout line = row();
                for (int j = i; j < Math.min(i + columns, scooterStats.size()); j++)
                    line.addView(scooterStats.get(j), new LinearLayout.LayoutParams(0, -2, 1));
                if (columns == 2 && i + 1 == scooterStats.size())
                    line.addView(new View(host.activity()), new LinearLayout.LayoutParams(0, 1, 1));
                scooterInfo.addView(line, new LinearLayout.LayoutParams(-1, -2));
            }
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            boolean narrowQuick = quickAccess != null && MeasureSpec.getSize(widthSpec) < ui.dp(144);
            compactRecorder = recorder != null && MeasureSpec.getSize(heightSpec) < ui.dp(100
                    * Math.max(1, ui.textScale * getResources().getConfiguration().fontScale));
            int pad = narrowQuick ? 10 : widget.type.equals("battery") ? 16 : 28;
            int vertical = compactRecorder ? 2 : narrowQuick ? 5 : widget.type.equals("battery") ? 8 : 12;
            content.setPadding(ui.dp(pad / 2), ui.dp(vertical), ui.dp(pad / 2), ui.dp(vertical));
            if (quickAccess != null) heading.setVisibility(narrowQuick ? GONE : VISIBLE);
            int width = Math.max(1, MeasureSpec.getSize(widthSpec) - ui.dp(pad));
            if (photo != null) {
                arrangeScooter(width);
                for (LinearLayout item : scooterStats) ((FittedValue)item.getChildAt(1)).capAt(Integer.MAX_VALUE);
            }
            super.onMeasure(widthSpec, heightSpec);
            int budget =
                    Math.max(
                            1,
                            viewport.getMeasuredHeight()
                                    - body.getPaddingTop()
                                    - body.getPaddingBottom());
            if (photo != null) {
                int rows = Math.max(1, scooterInfo.getChildCount());
                int rowBudget = Math.max(1, budget / rows);
                for (LinearLayout item : scooterStats) {
                    int inset = rowBudget < ui.dp(56) ? ui.dp(1) : ui.dp(3);
                    item.setPadding(0, inset, ui.dp(4), inset);
                    TextView label = (TextView) item.getChildAt(0);
                    label.setTextSize((rowBudget < ui.dp(56) ? 11.5f : 12.5f) * ui.textScale);
                    label.measure(MeasureSpec.makeMeasureSpec(Math.max(1, item.getMeasuredWidth()
                                    - item.getPaddingLeft() - item.getPaddingRight()), MeasureSpec.EXACTLY),
                            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                    ((FittedValue) item.getChildAt(1)).fitHeight(
                            rowBudget - label.getMeasuredHeight()
                                    - item.getPaddingTop() - item.getPaddingBottom());
                }
                // Fit every number against the same row budget, then share the smallest
                // resulting size. No stale previous measurement can make one value dominate.
                int common = Integer.MAX_VALUE;
                float density = getResources().getDisplayMetrics().scaledDensity;
                for (LinearLayout item : scooterStats) {
                    FittedValue value = (FittedValue)item.getChildAt(1);
                    value.measure(MeasureSpec.makeMeasureSpec(Math.max(1, item.getMeasuredWidth()
                            - item.getPaddingLeft() - item.getPaddingRight()), MeasureSpec.EXACTLY),
                            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                    common = Math.min(common, Math.round(value.fittedPixels / density));
                }
                for (LinearLayout item : scooterStats) ((FittedValue)item.getChildAt(1)).capAt(common);
                viewSize(photoPane, photoPane.getLayoutParams().width, Math.max(ui.dp(40), budget));
            }
            if (gauge != null) {
                viewSize(
                        gauge,
                        -1,
                        Math.max(1, budget - (subtitle.getVisibility() == View.GONE
                                ? 0 : subtitle.getMeasuredHeight())));
            }
            if (quickAccess != null) quickAccess.fitHeight(budget);
            if (chart != null) {
                // The chart takes whatever its card has left after the other lines.
                int other = 0;
                for (int i = 0; i < body.getChildCount(); i++) {
                    View child = body.getChildAt(i);
                    if (child == chart || child.getVisibility() == View.GONE) continue;
                    LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) child.getLayoutParams();
                    other += child.getMeasuredHeight() + p.topMargin + p.bottomMargin;
                }
                viewSize(chart, -1, Math.max(ui.dp(120), budget - other));
            }
            if (battery != null) {
                // The readings stay at the bottom; the battery cell fills the rest of the card.
                View strip = chargingData.getVisibility() == View.VISIBLE ? chargingData : batteryData;
                int stripHeight = strip.getVisibility() == View.GONE ? 0
                        : strip.getMeasuredHeight() + ((LinearLayout.LayoutParams) strip.getLayoutParams()).topMargin;
                // The percentage cell can give a little height back to wrapped readings.
                // A fixed 96 dp floor made otherwise readable narrow cards overflow by
                // only a few pixels; reserve the actual readable text block instead.
                viewSize(battery, -1, Math.max(battery.minimumContentHeight(), budget - stripHeight));
            }
            super.onMeasure(widthSpec, heightSpec);
        }

        private void addReading(String key) {
            addReading(body, key);
        }

        private void addReading(LinearLayout container, String key) {
            ReadingRow line = new ReadingRow(label(key));
            container.addView(line, new LinearLayout.LayoutParams(-1, -2));
            amounts.put(key, line.value);
        }

        private TextView chargingRow(LinearLayout container, String label) {
            ReadingRow line = new ReadingRow(label);
            container.addView(line, new LinearLayout.LayoutParams(-1, -2));
            if (label.equals("Time to full")) chargingEtaLabel = line.name;
            return line.value;
        }

        /** A value never takes the entire width away from its label; rows are divided by a hairline. */
        private final class ReadingRow extends LinearLayout {
            final TextView name, value;
            final Paint rule = new Paint();
            boolean narrow;

            ReadingRow(String label) {
                super(host.activity());
                setGravity(Gravity.CENTER_VERTICAL);
                setWillNotDraw(false);
                name = ui.text(label, 13.5f, false);
                name.setTextColor(ui.muted);
                value = new FittedValue("—", 12, 15, 2);
                addView(name);
                addView(value);
                rule.setColor(ui.raised);
            }

            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                narrow = MeasureSpec.getSize(widthSpec) < ui.dp(220 * ui.textScale);
                setOrientation(narrow ? VERTICAL : HORIZONTAL);
                setPadding(0, ui.dp(narrow ? 5 : 8), 0, ui.dp(narrow ? 5 : 8));
                linearSize(name, narrow ? -1 : 0, -2, narrow ? 0 : 1, 0);
                linearSize(value, narrow ? -1 : 0, -2, narrow ? 0 : 1, narrow ? 0 : ui.dp(8));
                value.setGravity(narrow ? Gravity.START : Gravity.END);
                super.onMeasure(widthSpec, heightSpec);
            }

            @Override
            protected void onDraw(Canvas canvas) {
                ViewParent parent = getParent();
                if (parent instanceof ViewGroup && ((ViewGroup) parent).indexOfChild(this) > 0
                        && ((ViewGroup) parent).getChildAt(((ViewGroup) parent).indexOfChild(this) - 1) instanceof ReadingRow)
                    canvas.drawRect(0, 0, getWidth(), Math.max(1, ui.dp(1)), rule);
            }
        }

        private void buildCharging(LinearLayout container, boolean detailed) {
            InfoTiles tiles = new InfoTiles();
            chargingWatts = tiles.add("bolt", "Charging power").value;
            InfoCell time = tiles.add("clock", "Time to full");
            chargingEta = time.value;
            chargingEtaLabel = time.label;
            container.addView(tiles, new LinearLayout.LayoutParams(-1, -2));
            chargingNote = ui.caption("Connect for fresh charging readings.");
            chargingNote.setPadding(0, ui.dp(6), 0, 0);
            container.addView(chargingNote);
        }

        private void refreshCharging(Map<String, TelemetryHistory.Reading> values) {
            ChargingEstimate.Result estimate = chargingEstimate(values);
            if (batteryData != null) {
                batteryData.setVisibility(estimate.charging ? View.GONE : View.VISIBLE);
                chargingData.setVisibility(estimate.charging ? View.VISIBLE : View.GONE);
                battery.setCharging(estimate.charging);
            }
            chargingWatts.setText(
                    estimate.inputWatts == null
                            ? "—"
                            : String.format(Locale.US, "%.0f W", estimate.inputWatts));
            chargingWatts.setTextColor(estimate.inputWatts == null ? ui.muted : ui.ok);
            boolean limit = estimate.targetPercent != null && estimate.targetPercent < 100;
            chargingEtaLabel.setText(
                    limit
                            ? String.format(Locale.US, "To %.0f%% limit", estimate.targetPercent)
                            : "Time to full");
            Double minutes =
                    limit ? estimate.remainingMinutesToLimit : estimate.remainingMinutesToFull;
            chargingEta.setText(duration(minutes));
            chargingEta.setTextColor(minutes == null ? ui.muted : ui.text);
            chargingNote.setText(
                    (estimate.inputWatts == null ? "" : "Battery-side readings. ")
                            + estimate.status);
            describeCharging(chargingWatts, "Charging power", "Battery-side power");
            describeCharging(chargingEta, chargingEtaLabel.getText().toString(), estimate.status);
            if (chargingTemperature != null) {
                TelemetryHistory.Reading temperature = values.get("temperature_c");
                chargingTemperature.setText(format(temperature));
                chargingTemperature.setTextColor(
                        temperature != null && temperature.live ? ui.text : ui.muted);
                describeCharging(chargingTemperature, "Battery temperature", "");
            }
        }

        private void describeCharging(TextView value, String label, String detail) {
            String description = label + ", " + value.getText()
                    + (detail.isEmpty() ? "" : ". " + detail);
            value.setContentDescription(description);
            if (value.getParent() instanceof View)
                ((View) value.getParent()).setTooltipText(description);
        }

        void refresh(Map<String, TelemetryHistory.Reading> values) {
            if (!active) return;
            if (quickAccess != null) quickAccess.refresh();
            if (capacityValue != null) refreshCapacity();
            if (chargingWatts != null) refreshCharging(values);
            boolean anyStale = false;
            long oldest = Long.MAX_VALUE;
            boolean styled = widget.type.equals("scooter") || widget.type.equals("trip")
                    || widget.type.equals("health");
            ArrayList<String> shown = new ArrayList<>(amounts.keySet());
            shown.addAll(watched);
            if (big != null || gauge != null) shown.add(widget.metric);
            if (battery != null) shown.add("battery_percent");
            for (String key : shown) {
                TelemetryHistory.Reading r = values.get(key);
                boolean live = r != null && r.live;
                anyStale |= r != null && r.value != null && !live;
                if (r != null && r.value != null) oldest = Math.min(oldest, r.capturedWallMs);
                TextView amount = amounts.get(key);
                if (amount != null) {
                    String text = format(r);
                    amount.setText(styled && !key.equals("driving_mode") ? withUnit(text, live) : text);
                    amount.setTextColor(live ? ui.text : ui.muted);
                    if (widget.type.equals("battery") && key.equals("power_w") && live && r.number != null)
                        amount.setTextColor(r.number < 0 ? ui.ok : r.number > 0 ? ui.danger : ui.text);
                    amount.setContentDescription(
                            label(key)
                                    + ", "
                                    + text
                                    + (r != null && !live
                                            ? ", last seen " + when(r.capturedWallMs)
                                            : ""));
                    if (batteryData != null && amount.getParent() instanceof View)
                        ((View) amount.getParent()).setTooltipText(amount.getContentDescription());
                    if (key.equals("driving_mode"))
                        ui.styleDriveMode(amount, text, live);
                }
            }
            TelemetryHistory.Reading selected = values.get(widget.metric);
            boolean selectedLive = selected != null && selected.live;
            if (big != null) {
                String text = format(selected);
                big.setText(widget.metric.equals("driving_mode") ? text : withUnit(text, selectedLive));
                big.setTextColor(selectedLive ? ui.text : ui.muted);
                big.setContentDescription(label(widget.metric) + ", " + text
                        + (selected != null && !selectedLive ? ", last seen " + when(selected.capturedWallMs) : ""));
                if (widget.metric.equals("driving_mode")) {
                    big.setContentDescription("Drive mode, " + text
                            + (selected != null && !selectedLive
                                    ? ", last seen " + when(selected.capturedWallMs) : ""));
                    ui.styleDriveMode(big, text, selectedLive);
                }
                if (subtitle != null && widget.type.equals("metric"))
                    subtitle.setVisibility(widget.title.equalsIgnoreCase(label(widget.metric))
                            ? View.GONE : View.VISIBLE);
            }
            if (headingIcon != null) headingIcon.set(readingIcon(widget.metric), selectedLive ? color(widget) : ui.dim);
            if (gauge != null) {
                gauge.set(selected);
                subtitle.setVisibility(widget.title.equalsIgnoreCase(label(widget.metric))
                        ? View.GONE : View.VISIBLE);
            }
            if (battery != null) {
                TelemetryHistory.Reading charge = values.get("battery_percent"),
                        charging = values.get("charging_state");
                boolean isCharging =
                        charging != null
                                && charging.live
                                && charging.number != null
                                && charging.number == 1;
                battery.set(charge == null ? null : charge.number,
                        showsRange ? rangeText(values.get("remaining_range_km")) : "",
                        isCharging, charge != null && charge.live);
            }
            if (healthDetail != null) {
                TelemetryHistory.Reading cycles = values.get("charge_cycles"),
                        rated = values.get("rated_capacity_ah");
                ArrayList<String> parts = new ArrayList<>();
                if (cycles != null && cycles.value != null)
                    parts.add((cycles.number != null ? String.format(Locale.US, "%.0f", cycles.number)
                            : format(cycles)) + " reported charges");
                if (rated != null && rated.value != null) parts.add(format(rated) + " rated");
                healthDetail.setText(android.text.TextUtils.join("\n", parts));
                healthDetail.setVisibility(parts.isEmpty() ? View.GONE : View.VISIBLE);
            }
            if (photo != null) {
                ScooterProfiles.Profile p = service == null ? null : service.profiles.active();
                // Model identity is saved with the profile, independent of live readings.
                boolean show = p != null && p.pid() == 2538;
                photo.setVisibility(show ? View.VISIBLE : View.GONE);
                photoHint.setVisibility(show ? View.GONE : View.VISIBLE);
            }
            if (rideButton != null) refreshRecorder();
            footer.setText(anyStale ? "Last seen · " + when(oldest) : "");
            footer.setContentDescription(anyStale ? "Last seen · " + when(oldest) : "");
            footer.setVisibility(footer.getText().length() == 0 ? View.GONE : View.VISIBLE);
            if (chargingData != null && chargingData.getVisibility() == View.VISIBLE)
                footer.setVisibility(View.GONE);
            if (chart != null) refreshChart(values);
            // Editing is a layout operation; data controls stay inert until Done.
            body.setAlpha(editing ? .92f : 1f);
            if (editing) disableInputs(body);
        }

        private void refreshRecorder() {
            boolean recording = service != null && service.isRecording();
            boolean ready = service != null && service.ble.ready();
            boolean saving = service != null && service.phoneSaving();
            boolean enabled = service != null && (recording || ready) && !saving;
            rideButton.setEnabled(enabled);
            recordDisc.setBackground(oval(enabled || recording ? ui.accent : ui.raised));
            recordGlyph.set(recording ? "stop" : "record", enabled || recording ? ui.onAccent : ui.dim);
            if (recordingShown == null || recordingShown != recording) {
                recordingShown = recording;
                setBackground(chrome());
            }
            RideStore.Summary summary = recording ? service.rides.activeSummary() : null;
            if (recording) {
                long seconds = summary == null ? 0 : summary.elapsedMs / 1000;
                String clock = seconds >= 3600
                        ? String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                        : String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
                rideTitle.setText("Recording · " + clock);
                rideTitle.setTextColor(ui.danger);
                rideTitle.setTypeface(ui.semibold());
                ArrayList<String> parts = new ArrayList<>();
                if (summary != null && summary.distanceKm != null) parts.add(distance(summary.distanceKm));
                if (summary != null && summary.measuredThroughputWh != null)
                    parts.add(String.format(Locale.US, "%.0f Wh", summary.measuredThroughputWh));
                rideDetail.setText(parts.isEmpty() ? "Measuring…" : android.text.TextUtils.join(" · ", parts));
                rideButton.setContentDescription("Recording " + clock
                        + (parts.isEmpty() ? "" : ", " + android.text.TextUtils.join(", ", parts)) + ". Stop & save");
            } else {
                rideTitle.setText("Record ride");
                rideTitle.setTextColor(enabled ? ui.text : ui.muted);
                rideTitle.setTypeface(ui.medium());
                rideDetail.setText(saving ? "Saving the last ride…"
                        : ready ? "GPS · elevation · motion" : "Connect to record");
                rideButton.setContentDescription("Start recording. " + rideDetail.getText());
            }
        }

        private GradientDrawable oval(int fill) {
            GradientDrawable disc = new GradientDrawable();
            disc.setShape(GradientDrawable.OVAL);
            disc.setColor(fill);
            return disc;
        }

        private String distance(double km) {
            String metric = String.format(Locale.US, "%.1f km", km),
                    miles = String.format(Locale.US, "%.1f mi", km / 1.609344);
            String units = units();
            return units.equals("km") ? metric : units.equals("dual") ? miles + " / " + metric : miles;
        }

        /** Side by side, or the details stacked under the percentage for narrow cards. */
        private void layoutHealth(LinearLayout top, TextView health, TextView detail, boolean stack) {
            top.setOrientation(stack ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
            top.setGravity(stack ? Gravity.START : Gravity.BOTTOM);
            health.setLayoutParams(stack ? new LinearLayout.LayoutParams(-1, -2) : new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-2, -2);
            if (stack) space.topMargin = ui.dp(2);
            else { space.setMarginStart(ui.dp(8)); space.bottomMargin = ui.dp(4); }
            detail.setGravity(stack ? Gravity.START : Gravity.END);
            detail.setLayoutParams(space);
        }

        private void disableInputs(View v) {
            if (v instanceof ViewGroup) {
                for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++)
                    disableInputs(((ViewGroup) v).getChildAt(i));
            }
            if (v.isClickable()) v.setEnabled(false);
        }

        private void refreshCapacity() {
            if (service == null || capacityPending) return;
            if (service.simulating()) {
                Double advertised = rating(widget);
                double sample = advertised == null ? 610 : advertised * .95;
                capacityValue.setText(String.format(Locale.US, "≈ %.0f Wh", sample));
                capacityValue.setTextColor(ui.text);
                capacitySummary.setText("Sample · 6 rides · 42 points used");
                capacityMethod.setText("Simulated estimate");
                capacityCompare.setText(advertised == null ? "Sample capacity" : String.format(Locale.US, "95%% of %.0f Wh · sample", advertised));
                capacityCompare.setTextColor(ui.muted);
                capacityBar.set(advertised == null ? null : 95d);
                capacityEvidence.setText("Sample data for layout preview");
                capacityNote.setText("No recorded rides are used or changed.");
                capacityNote.setTextColor(ui.muted);
                return;
            }
            if (service.profiles.active() == null) {
                capacityEvidence.setText(
                        "Set up a scooter profile, then record rides to build an estimate.");
                return;
            }
            long now = android.os.SystemClock.elapsedRealtime();
            if (capacityChecked > 0 && now - capacityChecked < 10000) return;
            capacityChecked = now;
            capacityPending = true;
            service.requestCapacityEstimate(
                    rating(widget),
                    (int) (widget.windowMs / 86400000L),
                    result -> {
                        capacityPending = false;
                        if (closed || !active || !profileId.equals(result.profileId)) return;
                        boolean estimated = result.estimatedUsableWh != null;
                        boolean proxy = !estimated && result.throughputEquivalentWh != null;
                        Double value =
                                estimated
                                        ? result.estimatedUsableWh
                                        : result.throughputEquivalentWh;
                        Double percent =
                                estimated
                                        ? result.percentOfAdvertised
                                        : result.throughputPercentOfAdvertised;
                        capacityValue.setText(
                                value == null
                                        ? "Not enough ride data"
                                        : String.format(Locale.US, "≈ %.0f Wh", value));
                        capacityValue.setTextColor(
                                value == null ? ui.muted : proxy ? ui.orange : ui.text);
                        capacitySummary.setText(
                                value == null
                                        ? result.status
                                        : String.format(
                                                Locale.US,
                                                "%d rides · %.0f points used",
                                                estimated ? result.qualifiedRides : result.proxyRides,
                                                estimated
                                                        ? result.batteryDropPoints
                                                        : result.proxyBatteryDropPoints));
                        capacityMethod.setText(
                                estimated
                                        ? "Ride-based · experimental"
                                        : proxy ? "Throughput proxy · not battery health"
                                                : "Experimental estimate");
                        capacityCompare.setText(
                                percent != null && result.advertisedWh != null
                                        ? String.format(
                                                Locale.US,
                                                "%.0f%% of %.0f Wh advertised%s",
                                                percent,
                                                result.advertisedWh,
                                                proxy ? " · proxy only" : "")
                                        : result.advertisedWh != null
                                                ? String.format(
                                                        Locale.US,
                                                        "Reference: %.0f Wh advertised",
                                                        result.advertisedWh)
                                                : "Set advertised Wh in widget options to compare");
                        capacityCompare.setTextColor(ui.muted);
                        capacityBar.set(percent);
                        String evidence =
                                value == null
                                        ? result.status
                                        : String.format(
                                                Locale.US,
                                                "%d rides · %.1f battery points · %.1f%% coverage",
                                                estimated
                                                        ? result.qualifiedRides
                                                        : result.proxyRides,
                                                estimated
                                                        ? result.batteryDropPoints
                                                        : result.proxyBatteryDropPoints,
                                                result.electricalCoverage * 100);
                        if (result.minimumTemperatureC != null
                                && result.maximumTemperatureC != null)
                            evidence += "\nPack: " + DisplayTemperature.range(
                                    result.minimumTemperatureC, result.maximumTemperatureC,
                                    temperatureUnit(), 0);
                        evidence +=
                                "\n"
                                        + result.windowDays
                                        + "-day window"
                                        + (result.includesActive ? " · recording live" : "");
                        capacityEvidence.setText(evidence);
                        capacityNote.setText(
                                proxy
                                        ? "Draw and return are both counted. This proxy can"
                                                + " overstate usable energy; the percentage is not"
                                                + " battery health."
                                        : "Cold, voltage range and battery-gauge changes affect"
                                                + " this estimate; it is not a measured capacity"
                                                + " test.");
                        capacityNote.setTextColor(ui.muted);
                    });
        }

        private void refreshChart(Map<String, TelemetryHistory.Reading> values) {
            long now = System.currentTimeMillis();
            String key = widget.type.equals("health") ? "battery_health_percent" : widget.metric;
            if (service != null && service.simulating()) {
                history = service.simulationHistory(key, now - widget.windowMs, now);
                livePoints.clear(); wasLive = service.ble.connected();
                drawChart(values, key);
                if (!widget.type.equals("health")) subtitle.setText("Sample · " + windowName(widget.windowMs));
                return;
            }
            if (widget.type.equals("live")) {
                TelemetryHistory.Reading reading = values.get(key);
                boolean live = reading != null && reading.live && reading.number != null;
                if (live
                        && (livePoints.isEmpty()
                                || reading.capturedWallMs
                                        > livePoints.get(livePoints.size() - 1).millis)) {
                    boolean gap =
                            !wasLive
                                    || (!livePoints.isEmpty()
                                            && reading.capturedWallMs
                                                            - livePoints.get(livePoints.size() - 1)
                                                                    .millis
                                                    > 20000);
                    livePoints.add(new Ui.Point(reading.capturedWallMs, reading.number, gap));
                }
                wasLive = live;
                while (!livePoints.isEmpty()
                        && (livePoints.get(0).millis < now - widget.windowMs
                                || livePoints.size() > 900)) livePoints.remove(0);
            }
            if (service != null
                    && service.history != null
                    && !querying
                    && (now - queriedAt > 10000 || !queryKey.equals(key))) {
                querying = true;
                queriedAt = now;
                queryKey = key;
                service.history.query(
                        profileId,
                        key,
                        now - widget.windowMs,
                        now,
                        points -> {
                            if (closed || !active || service.simulating()) return;
                            querying = false;
                            history = points;
                            drawChart(readings(), key);
                        });
            }
            drawChart(values, key);
        }

        private void drawChart(Map<String, TelemetryHistory.Reading> values, String key) {
            ArrayList<Ui.Point> points = new ArrayList<>();
            long tailStart = livePoints.isEmpty() ? Long.MAX_VALUE : livePoints.get(0).millis;
            for (TelemetryHistory.Point p : history)
                if (p.wallMs < tailStart)
                    points.add(new Ui.Point(p.wallMs, chartNumber(key, p.value), p.gapBefore));
            for (Ui.Point p : livePoints)
                points.add(new Ui.Point(p.millis, chartNumber(key, p.value), p.gapBefore));
            chart.setAxis(key, widget.absoluteAxis);
            chart.set(points, chartUnit(key, values), color(widget), "No saved readings yet");
            if (!widget.type.equals("health")) {
                String metricLabel = label(key);
                subtitle.setText(
                        (widget.title.equalsIgnoreCase(metricLabel) ? "" : metricLabel + " · ")
                                + windowName(widget.windowMs));
            }
            footer.setText(widget.type.equals("live") && !wasLive && !points.isEmpty()
                    ? "Last seen" : "");
            footer.setTextColor(ui.dim);
            footer.setVisibility(footer.getText().length() == 0 ? View.GONE : View.VISIBLE);
        }
    }

    /** White L at the lower-right corner of a card being edited: drag it to resize. */
    private final class CornerMark extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        CornerMark() {
            super(host.activity());
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float inset = ui.dp(9), arm = ui.dp(13), radius = ui.dp(4);
            float right = getWidth() - inset, bottom = getHeight() - inset;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(ui.dp(2.5f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(ui.dark ? Ui.withAlpha(Color.WHITE, 220) : ui.text);
            Path p = new Path();
            p.moveTo(right - arm, bottom);
            p.lineTo(right - radius, bottom);
            p.quadTo(right, bottom, right, bottom - radius);
            p.lineTo(right, bottom - arm);
            canvas.drawPath(p, paint);
        }
    }

    /**
     * Tall battery cell: a dark rounded track that fills from the bottom to the charge level,
     * with the percentage and range in white on the red. Last-seen charge turns the fill grey.
     */
    private final class BatteryCell extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint measurementPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect measurementBounds = new Rect();
        private final Paint.FontMetrics measurementMetrics = new Paint.FontMetrics();
        private final Path trackClip = new Path();
        private final RectF trackBounds = new RectF();
        private double percent = Double.NaN;
        private String detail = "";
        private boolean charging, live = true;

        BatteryCell() {
            super(host.activity());
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            describe();
        }

        void set(Double value, String detail, boolean charging, boolean live) {
            double next = value != null && Double.isFinite(value) && value >= 0 && value <= 100
                    ? value : Double.NaN;
            String text = detail == null ? "" : detail;
            if (Double.compare(next, percent) == 0 && text.equals(this.detail)
                    && charging == this.charging && live == this.live) return;
            percent = next;
            this.detail = text;
            this.charging = charging;
            this.live = live;
            describe();
            invalidate();
        }

        void setCharging(boolean value) {
            if (value == charging) return;
            charging = value;
            describe();
            invalidate();
        }

        /** Enough room for a readable percentage, optional range/status and their padding. */
        int minimumContentHeight() {
            measurementPaint.setTypeface(ui.bold());
            measurementPaint.setTextSize(ui.sp(24));
            measurementPaint.getTextBounds("100", 0, 3, measurementBounds);
            float height = measurementBounds.height() + ui.dp(24);
            if (charging || !detail.isEmpty()) {
                measurementPaint.setTypeface(ui.medium());
                measurementPaint.setTextSize(ui.sp(12));
                measurementPaint.getFontMetrics(measurementMetrics);
                height += ui.dp(5) + measurementMetrics.descent - measurementMetrics.ascent;
            }
            return Math.max(ui.dp(48), (int)Math.ceil(height));
        }

        private void describe() {
            setContentDescription("Battery " + (Double.isNaN(percent) ? "not reported" : Math.round(percent) + " percent")
                    + (detail.isEmpty() ? "" : ", " + detail) + (charging ? ", charging" : "")
                    + (live ? "" : ", last seen reading"));
        }

        @Override
        protected void onMeasure(int width, int height) {
            setMeasuredDimension(resolveSize(ui.dp(120), width), resolveSize(ui.dp(150), height));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), corner = ui.dp(14);
            if (w <= 0 || h <= 0) return;
            boolean knownLive = live && !Double.isNaN(percent);
            int track = knownLive
                    ? blend(ui.dark ? ui.bg : ui.surface, ui.accent, ui.dark ? .26f : .12f)
                    : ui.raised;
            trackBounds.set(0, 0, w, h);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(track);
            canvas.drawRoundRect(trackBounds, corner, corner, paint);
            // Quiet diagonal texture marks unused capacity even when the track and card
            // have similar brightness. Unknown and last-seen readings remain neutral.
            trackClip.reset();
            trackClip.addRoundRect(trackBounds, corner, corner, Path.Direction.CW);
            int saved = canvas.save();
            canvas.clipPath(trackClip);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(ui.dp(1));
            paint.setColor(blend(track, knownLive ? ui.accent : ui.dim, .25f));
            for (float x = -h; x < w; x += ui.dp(12))
                canvas.drawLine(x, h, x + h, 0, paint);
            canvas.restoreToCount(saved);
            paint.setStyle(Paint.Style.FILL);
            int fill = !live ? blend(ui.raised, ui.dim, ui.dark ? .45f : .6f)
                    : !Double.isNaN(percent) && percent < 15 && !charging ? ui.orange : ui.accent;
            float top = h;
            if (!Double.isNaN(percent) && percent > 0) {
                top = h - (float) (h * percent / 100);
                float round = Math.min(corner, (h - top) / 2);
                paint.setColor(fill);
                canvas.drawRoundRect(new RectF(0, top, w, h), round, round, paint);
            }
            // Keep the full-capacity silhouette visible against the surrounding card.
            float edge = Math.max(1, ui.dp(1));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(edge);
            paint.setColor(knownLive ? blend(track, ui.accent, .6f) : ui.outline);
            trackBounds.inset(edge / 2, edge / 2);
            canvas.drawRoundRect(trackBounds, Math.max(0, corner - edge / 2), Math.max(0, corner - edge / 2), paint);
            paint.setStyle(Paint.Style.FILL);
            String amount = Double.isNaN(percent) ? "—" : Long.toString(Math.round(percent));
            String unit = Double.isNaN(percent) ? "" : "%";
            String line = charging ? "Charging" : detail;
            float pad = ui.dp(12), available = Math.max(1, w - 2 * pad);
            float amountSize = ui.sp(34), unitSize = ui.sp(17), lineSize = ui.sp(12);
            paint.setTypeface(ui.bold());
            paint.setTextSize(amountSize);
            float amountWidth = paint.measureText(amount);
            paint.setTextSize(unitSize);
            float unitWidth = unit.isEmpty() ? 0 : paint.measureText(unit) + ui.dp(2);
            float fit = Math.min(1, available / Math.max(1, amountWidth + unitWidth));
            // Keep the block inside short cells, too.
            fit = Math.min(fit, (h - pad) / Math.max(1, (amountSize + lineSize) * 1.1f));
            amountSize *= fit;
            unitSize *= fit;
            lineSize = Math.max(Math.min(lineSize, ui.sp(12) * fit), Math.min(ui.sp(10), lineSize));
            paint.setTextSize(amountSize);
            Rect digits = new Rect();
            paint.getTextBounds("0", 0, 1, digits);
            float capHeight = digits.height();
            float gap = line.isEmpty() ? 0 : ui.dp(5);
            float lineHeight = line.isEmpty() ? 0 : lineSize;
            float block = capHeight + gap + lineHeight;
            float fillHeight = h - top;
            boolean inside = fillHeight >= block + ui.dp(18);
            float blockTop = inside ? top + (fillHeight - block) / 2
                    : Math.max(pad, top - ui.dp(10) - block);
            int ink = inside
                    ? (live ? ui.onAccent : (ui.dark ? blend(ui.muted, ui.text, .45f) : ui.text))
                    : (live ? ui.text : ui.muted);
            float baseline = blockTop + capHeight;
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setColor(ink);
            paint.setTypeface(ui.bold());
            paint.setTextSize(amountSize);
            canvas.drawText(amount, pad, baseline, paint);
            if (!unit.isEmpty()) {
                paint.setTextSize(unitSize);
                canvas.drawText(unit, pad + (amountWidth + ui.dp(2)) * fit, baseline, paint);
            }
            if (!line.isEmpty()) {
                paint.setTypeface(ui.medium());
                paint.setTextSize(lineSize);
                String shown = android.text.TextUtils.ellipsize(line, new android.text.TextPaint(paint),
                        available, android.text.TextUtils.TruncateAt.END).toString();
                canvas.drawText(shown, pad, baseline + gap + lineHeight * .9f, paint);
            }
        }
    }

    private final class Gauge extends View {
        private static final float START_ANGLE = 135f, SWEEP_ANGLE = 270f;
        private static final float FOOT = .70710678f;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint.FontMetrics fontMetrics = new Paint.FontMetrics();
        private final Rect glyphBounds = new Rect();
        private final RectF arcBounds = new RectF(), valueBounds = new RectF(),
                unitBounds = new RectF(), secondaryBounds = new RectF(),
                minimumLabelBounds = new RectF(), maximumLabelBounds = new RectF();
        private final DashboardConfig.Widget widget;
        private TelemetryHistory.Reading reading;
        private String amount = "—", unit = "", secondary = "";
        private float arcStroke;

        Gauge(DashboardConfig.Widget widget) {
            super(host.activity());
            this.widget = widget;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            set(null);
        }

        void set(TelemetryHistory.Reading reading) {
            this.reading = reading;
            String formatted = format(reading);
            amount = formatted;
            unit = secondary = "";
            // Preserve the app's value precision and unit preference, including
            // both readings in dual-unit mode, rather than rounding gauge values.
            int split = formatted.indexOf(' ');
            if (reading != null && reading.number != null && split > 0
                    && Character.isDigit(formatted.charAt(split - 1))) {
                amount = formatted.substring(0, split);
                unit = formatted.substring(split + 1).trim();
                int dual = unit.indexOf(" / ");
                if (dual >= 0) {
                    secondary = unit.substring(dual + 3);
                    unit = unit.substring(0, dual);
                }
            }
            setContentDescription(label(widget.metric) + ", " + formatted
                    + (reading != null && !reading.live
                            ? ", last seen " + when(reading.capturedWallMs) : ""));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            arcBounds.setEmpty();
            valueBounds.setEmpty();
            unitBounds.setEmpty();
            secondaryBounds.setEmpty();
            minimumLabelBounds.setEmpty();
            maximumLabelBounds.setEmpty();
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float pad = Math.min(ui.dp(6), Math.min(w, h) * .06f);
            arcStroke = Math.min(ui.dp(12), Math.min(w, h) * .06f);
            float labelGap = Math.min(ui.dp(8), h * .06f);
            String minimum = String.format(Locale.US, "%.0f", chartNumber(widget.metric, widget.minimum));
            String maximum = String.format(Locale.US, "%.0f", chartNumber(widget.metric, widget.maximum));
            float labelSize = Math.min(ui.sp(11), ui.dp(15));
            paint.setTypeface(ui.regular());
            paint.setTextSize(labelSize);
            paint.getFontMetrics(fontMetrics);
            float labelHeight = fontMetrics.descent - fontMetrics.ascent;
            // A 270-degree ring ends at cy + sqrt(.5)*r. Reserve a separate,
            // padded label row below those feet and center the complete graphic.
            float radius = Math.min((w - 2 * pad - arcStroke) / 2f,
                    (h - 2 * pad - arcStroke - labelGap - labelHeight) / (1 + FOOT));
            if (radius <= ui.dp(4)) return;
            float cx = w / 2f, footX = radius * FOOT;
            float labelWidth = Math.max(1, Math.min(2 * (cx - footX - pad),
                    2 * footX - ui.dp(8)));
            float measuredLabel = Math.max(paint.measureText(minimum), paint.measureText(maximum));
            labelSize *= Math.min(1, labelWidth / Math.max(1, measuredLabel));
            paint.setTextSize(labelSize);
            paint.getFontMetrics(fontMetrics);
            labelHeight = fontMetrics.descent - fontMetrics.ascent;
            float graphicHeight = (1 + FOOT) * radius + arcStroke + labelGap + labelHeight;
            float top = (h - graphicHeight) / 2f;
            float cy = top + arcStroke / 2f + radius;
            arcBounds.set(cx - radius, cy - radius, cx + radius, cy + radius);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(arcStroke);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(ui.raised);
            canvas.drawArc(arcBounds, START_ANGLE, SWEEP_ANGLE, false, paint);
            if (reading != null && reading.number != null) {
                double fraction = Math.max(0, Math.min(1,
                        (reading.number - widget.minimum) / (widget.maximum - widget.minimum)));
                paint.setColor(reading.live ? color(widget) : ui.muted);
                canvas.drawArc(arcBounds, START_ANGLE, (float) (SWEEP_ANGLE * fraction), false, paint);
            }

            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            float growth = Math.max(1, radius / ui.dp(54));
            float valueSize = Math.min(ui.sp(34) * growth, radius * .82f);
            float unitSize = Math.min(ui.sp(13) * growth, radius * .30f);
            float secondarySize = Math.min(ui.sp(11) * growth, radius * .25f);
            float valueHeight = textHeight(amount, valueSize, ui.bold());
            float textWidth = paint.measureText(amount);
            float unitHeight = textHeight(unit, unitSize, ui.medium());
            textWidth = Math.max(textWidth, paint.measureText(unit));
            float secondaryHeight = textHeight(secondary, secondarySize, ui.regular());
            textWidth = Math.max(textWidth, paint.measureText(secondary));
            float unitGap = unit.isEmpty() ? 0 : Math.min(ui.dp(5), radius * .1f);
            float secondaryGap = secondary.isEmpty() ? 0 : Math.min(ui.dp(3), radius * .06f);
            // Fit the entire reading block in a padded square inscribed inside
            // the ring, so long/negative values and dual units cannot hit the bar.
            float safeSide = Math.max(1, (radius - arcStroke / 2f - pad) * 1.41421356f);
            float blockHeight = valueHeight + unitGap + unitHeight + secondaryGap + secondaryHeight;
            float fit = Math.min(1, Math.min(safeSide / Math.max(1, textWidth),
                    safeSide / Math.max(1, blockHeight)));
            valueSize *= fit;
            unitSize *= fit;
            secondarySize *= fit;
            unitGap *= fit;
            secondaryGap *= fit;
            valueHeight = textHeight(amount, valueSize, ui.bold());
            unitHeight = textHeight(unit, unitSize, ui.medium());
            secondaryHeight = textHeight(secondary, secondarySize, ui.regular());
            blockHeight = valueHeight + unitGap + unitHeight + secondaryGap + secondaryHeight;
            float textTop = cy - blockHeight / 2f;
            int ink = reading != null && reading.live ? ui.text : ui.muted;
            drawTextTop(canvas, amount, cx, textTop, valueSize, ui.bold(), ink, valueBounds);
            textTop += valueHeight + unitGap;
            if (!unit.isEmpty())
                drawTextTop(canvas, unit, cx, textTop, unitSize, ui.medium(), ui.muted, unitBounds);
            textTop += unitHeight + secondaryGap;
            if (!secondary.isEmpty())
                drawTextTop(canvas, secondary, cx, textTop, secondarySize, ui.regular(), ui.muted, secondaryBounds);

            paint.setTypeface(ui.regular());
            paint.setTextSize(labelSize);
            paint.getFontMetrics(fontMetrics);
            paint.setColor(ui.muted);
            float labelBaseline = cy + radius * FOOT + arcStroke / 2f + labelGap - fontMetrics.ascent;
            drawTextBaseline(canvas, minimum, cx - footX, labelBaseline, minimumLabelBounds);
            drawTextBaseline(canvas, maximum, cx + footX, labelBaseline, maximumLabelBounds);
        }

        private float textHeight(String text, float size, Typeface face) {
            paint.setTypeface(face);
            paint.setTextSize(size);
            if (text.isEmpty()) {
                glyphBounds.setEmpty();
                return 0;
            }
            paint.getTextBounds(text, 0, text.length(), glyphBounds);
            return glyphBounds.height();
        }

        private void drawTextTop(Canvas canvas, String text, float x, float top,
                float size, Typeface face, int color, RectF bounds) {
            paint.setTypeface(face);
            paint.setTextSize(size);
            paint.setColor(color);
            paint.getTextBounds(text, 0, text.length(), glyphBounds);
            drawTextBaseline(canvas, text, x, top - glyphBounds.top, bounds);
        }

        private void drawTextBaseline(Canvas canvas, String text, float x, float baseline, RectF bounds) {
            paint.getTextBounds(text, 0, text.length(), glyphBounds);
            float left = x - paint.measureText(text) / 2f;
            bounds.set(left + glyphBounds.left, baseline + glyphBounds.top,
                    left + glyphBounds.right, baseline + glyphBounds.bottom);
            canvas.drawText(text, x, baseline, paint);
        }
    }


    /** Editing gestures own their hit targets; content scrolling never starts a move. */
    private final class EditorTouch implements View.OnTouchListener {
        final Tile tile;
        final boolean resize;
        float downX, downY;
        boolean started;
        View handle;
        final Runnable hold = () -> start();

        EditorTouch(Tile tile, boolean resize) {
            this.tile = tile;
            this.resize = resize;
        }

        void start() {
            if (started || handle == null || !editing || !tile.active || !tile.hasWindowFocus())
                return;
            started = true;
            handle.removeCallbacks(hold);
            grid.beginGesture(tile, resize, downX, downY);
            handle.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    handle = v;
                    started = false;
                    downX = event.getRawX();
                    downY = event.getRawY();
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    if (!resize) v.postDelayed(hold, ViewConfiguration.getLongPressTimeout());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!started
                            && Math.hypot(event.getRawX() - downX, event.getRawY() - downY)
                                    > ViewConfiguration.get(host.activity()).getScaledTouchSlop())
                        start();
                    if (started) grid.updateGesture(event.getRawX(), event.getRawY());
                    return true;
                case MotionEvent.ACTION_POINTER_UP:
                    if (event.getActionIndex() != 0) return true;
                    // End cleanly if the primary finger leaves during a multi-touch gesture.
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.removeCallbacks(hold);
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                    if (started)
                        grid.finishGesture(event.getActionMasked() != MotionEvent.ACTION_CANCEL);
                    else if (handle != null && event.getActionMasked() == MotionEvent.ACTION_UP)
                        v.performClick();
                    handle = null;
                    started = false;
                    return true;
                default:
                    return true;
            }
        }
    }

    private final class Grid extends ViewGroup {
        int columns = 2;
        private int measuredWidth;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Tile gestureTile;
        List<DashboardLayout.Cell> gestureSnapshot;
        private List<DashboardLayout.Cell> storedGestureSnapshot;
        private DashboardEditHistory.Snapshot gestureHistory;
        private List<DashboardLayout.Cell> displayed = Collections.emptyList();
        private final Map<String, RectF> previousFrames = new HashMap<>();
        private DashboardLayout.Cell gestureOrigin, requestedCell;
        private boolean resizing;
        private float rawX, rawY, startRawX, startRawY, anchorX, anchorY, startGridY;
        private int gestureColumns, gestureMinimumHeight;
        private RectF previewBounds;
        private android.animation.ValueAnimator previewAnimation;
        private boolean jigglePosted, suppressTransitionTouch;
        private long jiggleStarted;
        private final Rect jiggleVisible = new Rect();
        private ViewTreeObserver jiggleObserver;
        private final ViewTreeObserver.OnScrollChangedListener jiggleScroll = this::updateEditorMotion;
        private final Runnable jiggleFrame = new Runnable() {
            @Override public void run() {
                jigglePosted = false;
                if (!canJiggle()) { stopJiggle(); return; }
                long elapsed = android.os.SystemClock.uptimeMillis() - jiggleStarted;
                float ramp = Math.min(1, elapsed / 160f);
                boolean visible = false;
                for (Tile tile : tiles) {
                    boolean shown = tile != gestureTile && tile.isShown()
                            && tile.getGlobalVisibleRect(jiggleVisible);
                    float angle = shown ? .2f * ramp * (float) Math.sin(elapsed * Math.PI / 310d
                            + (tile.widget.id.hashCode() & 255) * Math.PI / 128d) : 0;
                    if (Math.abs(tile.jiggleAngle - angle) > .001f) {
                        tile.jiggleAngle = angle;
                        tile.invalidate();
                    }
                    visible |= shown;
                }
                if (visible) {
                    jigglePosted = true;
                    postDelayed(this, 32);
                }
            }
        };
        private final Runnable scrollFrame =
                new Runnable() {
                    @Override
                    public void run() {
                        if (gestureTile == null) return;
                        ScrollView scroll = outerScroll();
                        if (scroll != null) {
                            int[] p = new int[2];
                            scroll.getLocationOnScreen(p);
                            float y = rawY - p[1];
                            int speed =
                                    y < ui.dp(64)
                                            ? -ui.dp(9)
                                            : y > scroll.getHeight() - ui.dp(64) ? ui.dp(9) : 0;
                            if (speed != 0) {
                                scroll.scrollBy(0, speed);
                                updateGesture(rawX, rawY);
                            }
                        }
                        postOnAnimation(this);
                    }
                };

        Grid() {
            super(host.activity());
            setWillNotDraw(false);
            setClipChildren(false);
            setClipToPadding(false);
        }

        @Override
        public void onWindowFocusChanged(boolean focused) {
            super.onWindowFocusChanged(focused);
            if (!focused) {
                cancelWidgetReveal();
                finishGesture(false);
                stopEditTransition();
                stopJiggle();
                suppressTransitionTouch = false;
            } else { updateEditorMotion(); revealAddedWidget(); }
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            jiggleObserver = getViewTreeObserver();
            jiggleObserver.addOnScrollChangedListener(jiggleScroll);
            updateEditorMotion();
        }

        @Override protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            if (visibility != View.VISIBLE) {
                stopEditTransition(); stopJiggle(); suppressTransitionTouch = false;
            }
            else updateEditorMotion();
        }

        @Override protected void onVisibilityChanged(View changedView, int visibility) {
            super.onVisibilityChanged(changedView, visibility);
            if (visibility != View.VISIBLE) {
                stopEditTransition(); stopJiggle(); suppressTransitionTouch = false;
            }
            else updateEditorMotion();
        }

        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                cancelWidgetReveal(); revealWidgetId = null;
            }
            // Old decorative controls must never redirect a quick tap into live scooter controls.
            if (editTransition != null || suppressTransitionTouch) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) suppressTransitionTouch = true;
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
                    suppressTransitionTouch = false;
                return true;
            }
            return super.dispatchTouchEvent(event);
        }

        private boolean canJiggle() {
            return editing && !closed && editTransition == null && Ui.motionEnabled()
                    && isAttachedToWindow() && hasWindowFocus()
                    && getWindowVisibility() == View.VISIBLE && isShown();
        }

        void updateEditorMotion() {
            if (!canJiggle()) { stopJiggle(); return; }
            if (jigglePosted) return;
            boolean visible = false;
            for (Tile tile : tiles)
                if (tile != gestureTile && tile.getGlobalVisibleRect(jiggleVisible)) { visible = true; break; }
            if (!visible) return;
            jiggleStarted = android.os.SystemClock.uptimeMillis();
            jigglePosted = true;
            postOnAnimation(jiggleFrame);
        }

        void stopJiggle() {
            removeCallbacks(jiggleFrame);
            jigglePosted = false;
            for (Tile tile : tiles) if (tile.jiggleAngle != 0) {
                tile.jiggleAngle = 0;
                tile.invalidate();
            }
        }

        float columnWidth() {
            return Math.max(1, measuredWidth / (float) columns);
        }

        /** The most columns this width can show; narrower screens use fewer. */
        int maximumColumns() {
            int ordinary = adaptiveColumns();
            return measuredWidth >= ui.dp(400)
                    ? Math.max(ordinary, Math.min(DashboardConfig.MAX_COLUMNS, measuredWidth / ui.dp(80)))
                    : ordinary;
        }

        private int adaptiveColumns() {
            return Math.max(1, Math.min(4, measuredWidth / ui.dp(150)));
        }

        private float textScale() {
            return ui.textScale * getResources().getConfiguration().fontScale;
        }

        int minimumWidth(DashboardConfig.Widget widget) {
            return widget.minimumWidth(columns, measuredWidth / getResources().getDisplayMetrics().density,
                    10, textScale());
        }

        DashboardLayout.Cell currentCell(DashboardConfig.Widget widget) {
            for (DashboardLayout.Cell cell : displayed) if (cell.id.equals(widget.id)) return cell;
            return widget.layouts.get(columns);
        }

        void prepareEdit() {
            apply(snapshot(), columns);
        }

        void rememberFrames() {
            previousFrames.clear();
            for (Tile tile : tiles) if (tile.getWidth() > 0) {
                float x = tile.getLeft() + tile.getTranslationX(), y = tile.getTop() + tile.getTranslationY();
                previousFrames.put(tile.widget.id, new RectF(x, y,
                        x + tile.getWidth() * tile.getScaleX(), y + tile.getHeight() * tile.getScaleY()));
                tile.animate().cancel();
            }
        }

        float rowHeight() {
            // Stored rectangles use grid units. Give those units enough physical height
            // for both app text sizing and Android's accessibility font preference.
            float systemTextScale = host.activity().getResources().getConfiguration().fontScale;
            return ui.dp(64 * Math.max(1, ui.textScale * systemTextScale));
        }

        private boolean motionEnabled() {
            return android.animation.ValueAnimator.areAnimatorsEnabled();
        }

        void stopMotion() {
            if (previewAnimation != null) previewAnimation.cancel();
            stopJiggle();
            for (Tile tile : tiles) {
                tile.animate().cancel();
                tile.setTranslationX(0); tile.setTranslationY(0);
                tile.setScaleX(1); tile.setScaleY(1);
            }
            previousFrames.clear();
        }

        @Override protected void onDetachedFromWindow() {
            cancelWidgetReveal();
            finishGesture(false);
            stopEditTransition();
            stopMotion();
            suppressTransitionTouch = false;
            if (jiggleObserver != null && jiggleObserver.isAlive())
                jiggleObserver.removeOnScrollChangedListener(jiggleScroll);
            jiggleObserver = null;
            super.onDetachedFromWindow();
        }

        private List<DashboardLayout.Cell> snapshot() {
            ArrayList<DashboardLayout.Cell> result = new ArrayList<>();
            for (DashboardConfig.Widget widget : config.widgets)
                result.add(currentCell(widget).copy());
            return result;
        }

        private void apply(List<DashboardLayout.Cell> cells, int layoutColumns) {
            for (DashboardLayout.Cell cell : cells)
                for (DashboardConfig.Widget widget : config.widgets)
                    if (widget.id.equals(cell.id)) widget.layouts.put(layoutColumns, cell.copy());
        }

        void beginGesture(Tile tile, boolean resize, float x, float y) {
            finishGesture(false);
            stopEditTransition();
            stopRemovalEffect();
            config.layout(columns, null);
            gestureHistory = DashboardEditHistory.capture(config);
            storedGestureSnapshot = new ArrayList<>();
            for (DashboardConfig.Widget widget : config.widgets)
                storedGestureSnapshot.add(widget.layouts.get(columns).copy());
            gestureSnapshot = snapshot();
            gestureColumns = columns;
            gestureTile = tile;
            tile.jiggleAngle = 0;
            tile.invalidate();
            resizing = resize;
            gestureOrigin = currentCell(tile.widget).copy();
            gestureMinimumHeight =
                    Math.max(
                            gestureOrigin.y + gestureOrigin.h,
                            Math.round(getHeight() / rowHeight()));
            requestedCell = null;
            startRawX = rawX = x;
            startRawY = rawY = y;
            int[] p = new int[2];
            tile.getLocationOnScreen(p);
            anchorX = x - p[0];
            anchorY = y - p[1];
            getLocationOnScreen(p);
            startGridY = p[1];
            tile.animate().cancel();
            tile.setTranslationX(0);
            tile.setTranslationY(0);
            tile.setScaleX(1);
            tile.setScaleY(1);
            tile.setElevation(ui.dp(12));
            tile.setAlpha(.94f);
            tile.dragging = true;
            tile.setBackground(tile.chrome());
            updateGesture(x, y);
            postOnAnimation(scrollFrame);
        }

        void updateGesture(float x, float y) {
            if (gestureTile == null) return;
            rawX = x;
            rawY = y;
            int[] p = new int[2];
            getLocationOnScreen(p);
            DashboardLayout.Cell target = gestureOrigin.copy();
            if (resizing) {
                target.w =
                        Math.max(
                                minimumWidth(gestureTile.widget),
                                Math.min(
                                        columns - target.x,
                                        gestureOrigin.w
                                                + Math.round((x - startRawX) / columnWidth())));
                target.h =
                        Math.max(
                                gestureTile.widget.minimumHeight((target.w * columnWidth() - ui.dp(10))
                                        / getResources().getDisplayMetrics().density),
                                Math.min(
                                        16,
                                        gestureOrigin.h
                                                + Math.round(
                                                        (y - startRawY + startGridY - p[1])
                                                                / rowHeight())));
            } else {
                target.x =
                        Math.max(
                                0,
                                Math.min(
                                        columns - target.w,
                                        Math.round((x - p[0] - anchorX) / columnWidth())));
                target.y =
                        Math.max(0, Math.min(300, Math.round((y - p[1] - anchorY) / rowHeight())));
            }
            if (requestedCell == null
                    || target.x != requestedCell.x
                    || target.y != requestedCell.y
                    || target.w != requestedCell.w
                    || target.h != requestedCell.h) {
                requestedCell = target;
                ArrayList<DashboardLayout.Cell> next = new ArrayList<>();
                for (DashboardLayout.Cell original : gestureSnapshot)
                    next.add(original.id.equals(target.id) ? target.copy() : original.copy());
                apply(DashboardLayout.preview(next, columns, target.id), columns);
                animatePreview(target);
                requestLayout();
            }
            followFinger();
            invalidate();
        }

        DashboardLayout.Cell previewCell() {
            return requestedCell == null ? null : requestedCell.copy();
        }

        private void followFinger() {
            if (gestureTile == null || resizing) return;
            int[] p = new int[2];
            getLocationOnScreen(p);
            gestureTile.setTranslationX(rawX - p[0] - anchorX - gestureTile.getLeft());
            gestureTile.setTranslationY(rawY - p[1] - anchorY - gestureTile.getTop());
        }

        private RectF bounds(DashboardLayout.Cell cell) {
            return new RectF(
                    cell.x * columnWidth(),
                    cell.y * rowHeight(),
                    (cell.x + cell.w) * columnWidth() - ui.dp(10),
                    (cell.y + cell.h) * rowHeight() - ui.dp(10));
        }

        private void animatePreview(DashboardLayout.Cell cell) {
            RectF end = bounds(cell);
            if (previewAnimation != null) previewAnimation.cancel();
            if (previewBounds == null || !motionEnabled()) {
                previewBounds = end;
                return;
            }
            RectF start = new RectF(previewBounds);
            previewAnimation = android.animation.ValueAnimator.ofFloat(0, 1);
            previewAnimation.setDuration(150);
            previewAnimation.setInterpolator(new android.view.animation.PathInterpolator(.2f, 0, 0, 1));
            previewAnimation.addUpdateListener(
                    a -> {
                        float f = (float) a.getAnimatedValue();
                        previewBounds =
                                new RectF(
                                        start.left + (end.left - start.left) * f,
                                        start.top + (end.top - start.top) * f,
                                        start.right + (end.right - start.right) * f,
                                        start.bottom + (end.bottom - start.bottom) * f);
                        invalidate();
                    });
            previewAnimation.start();
        }

        void finishGesture(boolean commit) {
            if (gestureTile == null) return;
            removeCallbacks(scrollFrame);
            if (!commit) apply(storedGestureSnapshot, gestureColumns);
            else {
                apply(DashboardLayout.withoutLeadingRows(config.layout(gestureColumns, gestureTile.widget.id)), gestureColumns);
                config.save();
                recordEdit(gestureHistory);
            }
            gestureHistory = null;
            Tile tile = gestureTile;
            gestureTile = null;
            gestureSnapshot = null;
            storedGestureSnapshot = null;
            gestureOrigin = requestedCell = null;
            if (previewAnimation != null) previewAnimation.cancel();
            previewBounds = null;
            tile.setAlpha(1);
            tile.setElevation(0);
            tile.dragging = false;
            tile.setBackground(tile.chrome());
            if (motionEnabled())
                tile.animate()
                        .translationX(0)
                        .translationY(0)
                        .scaleX(1)
                        .scaleY(1)
                        .setDuration(180)
                        .setInterpolator(new android.view.animation.PathInterpolator(.2f, 0, 0, 1))
                        .start();
            else {
                tile.setTranslationX(0);
                tile.setTranslationY(0);
            }
            requestLayout();
            invalidate();
            updateEditorMotion();
        }

        void settle(String priority) {
            apply(config.layout(columns, priority,
                    measuredWidth / getResources().getDisplayMetrics().density, 10, textScale()), columns);
            requestLayout();
            invalidate();
        }

        private ScrollView outerScroll() {
            ViewParent parent = getParent();
            while (parent != null && !(parent instanceof ScrollView)) parent = parent.getParent();
            return parent instanceof ScrollView ? (ScrollView) parent : null;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            measuredWidth = MeasureSpec.getSize(widthSpec);
            int maximum = maximumColumns();
            int requested = preferences.getInt("dashboard_columns", 0);
            int next = requested == 0 || requested > maximum ? adaptiveColumns() : Math.max(1, requested);
            // Parents can probe several widths in one measurement pass. Keep the
            // active layout stable until an actual laid-out width changes.
            columns = gestureTile == null ? next : gestureColumns;
            List<DashboardLayout.Cell> cells = gestureTile == null
                    ? config.layout(columns, null, measuredWidth / getResources().getDisplayMetrics().density,
                            10, textScale())
                    : config.layout(columns, gestureTile.widget.id);
            displayed = cells;
            int end = gestureTile == null ? 0 : gestureMinimumHeight;
            for (int i = 0; i < tiles.size(); i++) {
                DashboardLayout.Cell cell = cells.get(i);
                int width = Math.max(1, Math.round(cell.w * columnWidth()) - ui.dp(10));
                int height = Math.round(cell.h * rowHeight()) - ui.dp(10);
                tiles.get(i)
                        .measure(
                                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
                end = Math.max(end, cell.y + cell.h);
            }
            setMeasuredDimension(
                    measuredWidth,
                    Math.max(
                            ui.dp(100),
                            Math.round(end * rowHeight()) + (editing ? ui.dp(140) : 0)));
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (gestureTile != null && w != oldw) {
                finishGesture(false);
                requestLayout();
            }
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            for (Tile tile : tiles) {
                DashboardLayout.Cell cell = currentCell(tile.widget);
                int x = Math.round(cell.x * columnWidth()), y = Math.round(cell.y * rowHeight());
                int w = tile.getMeasuredWidth(), h = tile.getMeasuredHeight();
                boolean moved =
                        tile.getLeft() != x
                                || tile.getTop() != y
                                || tile.getWidth() != w
                                || tile.getHeight() != h;
                float oldX = tile.getLeft() + tile.getTranslationX(),
                        oldY = tile.getTop() + tile.getTranslationY();
                float oldW = tile.getWidth() * tile.getScaleX(),
                        oldH = tile.getHeight() * tile.getScaleY();
                RectF remembered = previousFrames.get(tile.widget.id);
                if (oldW == 0 && remembered != null) {
                    oldX = remembered.left; oldY = remembered.top;
                    oldW = remembered.width(); oldH = remembered.height();
                    moved = oldX != x || oldY != y || oldW != w || oldH != h;
                }
                tile.layout(x, y, x + w, y + h);
                if (tile == gestureTile) {
                    if (!resizing) { followFinger(); continue; }
                }
                if (moved && oldW > 0 && oldH > 0 && motionEnabled()) {
                    tile.animate().cancel();
                    tile.setPivotX(0);
                    tile.setPivotY(0);
                    tile.setTranslationX(oldX - x);
                    tile.setTranslationY(oldY - y);
                    tile.setScaleX(oldW / w);
                    tile.setScaleY(oldH / h);
                    tile.animate()
                            .translationX(0)
                            .translationY(0)
                            .scaleX(1)
                            .scaleY(1)
                            .setDuration(tile == gestureTile ? 140 : 210)
                            .setInterpolator(new android.view.animation.PathInterpolator(.2f, 0, 0, 1))
                            .start();
                } else if (!motionEnabled()) {
                    tile.animate().cancel();
                    tile.setTranslationX(0); tile.setTranslationY(0);
                    tile.setScaleX(1); tile.setScaleY(1);
                }
            }
            previousFrames.clear();
            updateEditorMotion();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (!editing) return;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Ui.withAlpha(ui.muted, 45));
            for (int col = 0; col <= columns; col++)
                for (float y = 0; y < getHeight(); y += rowHeight())
                    c.drawCircle(col * columnWidth(), y, ui.dp(1.5f), paint);
            if (previewBounds != null) {
                paint.setColor(Ui.withAlpha(ui.accent, ui.dark ? 18 : 14));
                c.drawRoundRect(previewBounds, ui.dp(20), ui.dp(20), paint);
                paint.setColor(Ui.withAlpha(ui.accent, 180));
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(ui.dp(2));
                paint.setPathEffect(new DashPathEffect(new float[] {ui.dp(7), ui.dp(5)}, 0));
                c.drawRoundRect(previewBounds, ui.dp(20), ui.dp(20), paint);
                paint.setPathEffect(null);
                paint.setStyle(Paint.Style.FILL);
            }
        }
    }

    /** Rows in a sheet sit on a raised card so they read as one group. */
    private LinearLayout sheetCard() {
        LinearLayout card = column();
        card.setPadding(ui.dp(12), ui.dp(2), ui.dp(12), ui.dp(2));
        card.setBackground(ui.shape(ui.dark ? ui.raised : ui.bg, 16, 0));
        return card;
    }

    /** A Volt chip with a full-height touch target for the option sheets. */
    private TextView sheetChip(String label, boolean selected) {
        TextView chip = ui.chip(label, selected, null);
        chip.setMinHeight(ui.dp(44));
        chip.setMinWidth(ui.dp(48));
        return chip;
    }

    private void sheetDivider(LinearLayout parent) {
        View line = new View(host.activity());
        line.setBackgroundColor(ui.outline);
        parent.addView(line, new LinearLayout.LayoutParams(-1, Math.max(1, ui.dp(1))));
    }

    private TextView sheetLabel(String text) {
        TextView label = ui.text(text, 13, false);
        label.setTextColor(ui.muted);
        label.setPadding(ui.dp(2), ui.dp(14), ui.dp(2), ui.dp(8));
        return label;
    }

    /** Chips and swatches wrap onto as many lines as they need. */
    private final class Flow extends ViewGroup {
        Flow() {
            super(host.activity());
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec), gap = ui.dp(8);
            int x = 0, y = 0, line = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                if (x > 0 && x + child.getMeasuredWidth() > width) {
                    y += line + gap;
                    x = 0;
                    line = 0;
                }
                x += child.getMeasuredWidth() + gap;
                line = Math.max(line, child.getMeasuredHeight());
            }
            setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(y + line, heightSpec));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int width = getWidth(), gap = ui.dp(8), x = 0, y = 0, line = 0;
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                int w = child.getMeasuredWidth(), h = child.getMeasuredHeight();
                if (x > 0 && x + w > width) {
                    y += line + gap;
                    x = 0;
                    line = 0;
                }
                int left = rtl ? width - x - w : x;
                child.layout(left, y, left + w, y + h);
                x += w + gap;
                line = Math.max(line, h);
            }
        }
    }

    private void palette() {
        if (config.widgets.size() >= 32) {
            host.toast("Up to 32 widgets per scooter.");
            return;
        }
        boolean hasLock = false;
        for (DashboardConfig.Widget item : config.widgets) hasLock |= item.type.equals("lock");
        LinearLayout list = sheetCard();
        for (int i = 0; i < DashboardConfig.TYPES.length; i++) {
            final String type = DashboardConfig.TYPES[i];
            boolean present = type.equals("lock") && hasLock;
            if (i > 0) sheetDivider(list);
            LinearLayout row = row();
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(ui.dp(64));
            row.setPadding(0, ui.dp(10), 0, ui.dp(10));
            row.setBackground(ripple(Ui.withAlpha(ui.accent, 26), null, ui.shape(Color.WHITE, 10, 0)));
            row.setClickable(true);
            row.setFocusable(true);
            row.setTag("add-widget:" + type);
            row.setContentDescription("Add " + DashboardConfig.NAMES[i]);
            row.addView(square(widgetIcon(type), present ? ui.dim : ui.accent, 38),
                    new LinearLayout.LayoutParams(ui.dp(38), ui.dp(38)));
            LinearLayout words = column();
            TextView name = ui.text(DashboardConfig.NAMES[i], 15, false);
            name.setTypeface(ui.medium());
            if (present) name.setTextColor(ui.muted);
            words.addView(name);
            TextView detail = ui.caption(DashboardConfig.DESCRIPTIONS[i]);
            detail.setPadding(0, ui.dp(2), 0, 0);
            words.addView(detail);
            LinearLayout.LayoutParams wordsSpace = new LinearLayout.LayoutParams(0, -2, 1);
            wordsSpace.setMarginStart(ui.dp(12));
            wordsSpace.setMarginEnd(ui.dp(10));
            row.addView(words, wordsSpace);
            if (present) {
                TextView on = ui.text("On Home", 13.5f, false);
                on.setTextColor(ui.muted);
                row.addView(on);
            } else {
                FrameLayout plus = new FrameLayout(host.activity());
                GradientDrawable disc = new GradientDrawable();
                disc.setShape(GradientDrawable.OVAL);
                disc.setColor(ui.accent);
                plus.setBackground(disc);
                plus.addView(new GlyphView("plus", ui.onAccent),
                        new FrameLayout.LayoutParams(ui.dp(16), ui.dp(16), Gravity.CENTER));
                plus.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                row.addView(plus, new LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)));
            }
            if (android.os.Build.VERSION.SDK_INT >= 30)
                if (present) row.setStateDescription("Already on Home");
            row.setOnClickListener(
                    v -> {
                        if (type.equals("lock"))
                            for (DashboardConfig.Widget item : config.widgets)
                                if (item.type.equals("lock")) {
                                    host.toast("The lock widget is already on your dashboard.");
                                    return;
                                }
                        DashboardConfig.Widget widget = new DashboardConfig.Widget(type);
                        int end = 0;
                        for (DashboardConfig.Widget item : config.widgets) {
                            DashboardLayout.Cell cell = item.layouts.get(grid.columns);
                            if (cell != null) end = Math.max(end, cell.y + cell.h);
                        }
                        widget.layouts.put(
                                grid.columns,
                                new DashboardLayout.Cell(
                                        widget.id,
                                        0,
                                        end,
                                        widget.defaultWidth(grid.columns),
                                        widget.defaultHeight()));
                        // Keep a new card out of saved layouts and Undo until its own Save.
                        pendingAddedWidgetId = widget.id;
                        dismiss();
                        options(widget);
                    });
            list.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        showSheet("Add widget", list, null, "Close add widget", null);
    }

    private void options(DashboardConfig.Widget widget) {
        if (!editing) return;
        LinearLayout list = column();
        // Title is edited in place and saved when the sheet closes.
        list.addView(sheetLabel("Title"));
        EditText titleInput = new EditText(dialogContext);
        titleInput.setText(widget.title);
        titleInput.setSingleLine(true);
        titleInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        titleInput.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(80)});
        titleInput.setTextColor(ui.text);
        titleInput.setHintTextColor(ui.muted);
        titleInput.setTypeface(ui.regular());
        titleInput.setTextSize(15 * ui.textScale);
        titleInput.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12));
        titleInput.setMinHeight(ui.dp(52));
        titleInput.setBackground(ui.shape(ui.dark ? ui.bg : ui.surface, 14, ui.outline));
        titleInput.setContentDescription("Widget title");
        list.addView(titleInput, new LinearLayout.LayoutParams(-1, -2));
        Runnable applyTitle =
                () -> {
                    String value = titleInput.getText().toString().trim();
                    if (!value.isEmpty() && !value.equals(widget.title)) {
                        widget.title = value;
                        config.save();
                        render();
                    }
                };
        LinearLayout settings = sheetCard();
        if (widget.type.equals("quick"))
            addSettingRow(settings, ui.settingRow("Quick settings", widget.quickActions.size() + " selected",
                    () -> quickOptions(widget)), "grid");
        if (widget.type.equals("lock")) {
            Switch disconnect = new Switch(dialogContext);
            disconnect.setText("Show Disconnect button");
            disconnect.setTextColor(ui.text);
            disconnect.setTextSize(14 * ui.textScale);
            disconnect.setMinHeight(ui.dp(56));
            disconnect.setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8));
            disconnect.setChecked(widget.showDisconnect);
            disconnect.setContentDescription("Show Disconnect button when connected");
            disconnect.setOnCheckedChangeListener((button, checked) -> {
                widget.showDisconnect = checked;
                config.save();
                render();
            });
            settings.addView(disconnect);
        }
        if (widget.type.equals("scooter"))
            addSettingRow(settings,
                    ui.settingRow(
                            "Scooter name",
                            service == null || service.profiles.active() == null
                                    ? ""
                                    : service.profiles.active().name,
                            () -> {
                                dismiss();
                                actions.rename();
                            }), "scooter");
        if (Arrays.asList("metric", "gauge", "live", "history").contains(widget.type))
            addSettingRow(settings,
                    ui.settingRow("Parameter", label(widget.metric), () -> chooseMetric(widget)),
                    readingIcon(widget.metric));
        if (widget.type.equals("readings")
                || widget.type.equals("battery")
                || widget.type.equals("scooter"))
            addSettingRow(settings,
                    ui.settingRow(
                            "Readings",
                            widget.keys.size() + " selected",
                            () -> chooseReadings(widget)), "stats");
        if (widget.type.equals("note"))
            addSettingRow(settings,
                    ui.settingRow(
                            "Note",
                            widget.note,
                            () ->
                                    editText(
                                            widget,
                                            "Note",
                                            widget.note,
                                            value -> widget.note = value)), "text");
        if (widget.type.equals("capacity"))
            addSettingRow(settings,
                    ui.settingRow(
                            "Advertised capacity",
                            ratingLabel(widget),
                            () -> advertisedCapacity(widget)), "battery");
        if (widget.type.equals("gauge")) {
            addSettingRow(settings,
                    ui.settingRow(
                            "Scale minimum",
                            scaleSummary(widget, widget.minimum),
                            () -> scale(widget, false)), "speed");
            addSettingRow(settings,
                    ui.settingRow(
                            "Scale maximum",
                            scaleSummary(widget, widget.maximum),
                            () -> scale(widget, true)), "speed");
        }
        if (settings.getChildCount() > 0) {
            LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, -2);
            space.topMargin = ui.dp(12);
            list.addView(settings, space);
        }
        if (Arrays.asList("live", "history", "health", "capacity").contains(widget.type)) {
            list.addView(sheetLabel(widget.type.equals("capacity") ? "Ride data window" : "Time window"));
            long[] options = windowOptions(widget);
            String[] windowLabels = new String[options.length]; int selectedWindow = 0;
            for (int i = 0; i < options.length; i++) {
                windowLabels[i] = shortWindow(options[i]);
                if (options[i] == widget.windowMs) selectedWindow = i;
            }
            SegmentedSelector windows = new SegmentedSelector(ui, "Time window", windowLabels, choice -> {
                widget.windowMs = options[choice]; config.save(); render();
            });
            windows.select(selectedWindow, false);
            list.addView(windows, new LinearLayout.LayoutParams(-1, -2));
        }
        if (Arrays.asList("live", "history", "health").contains(widget.type)) {
            list.addView(sheetLabel("Y axis"));
            SegmentedSelector axis = new SegmentedSelector(ui, "Y axis",
                    new String[]{"Relative", "Absolute"}, choice -> {
                        widget.absoluteAxis = choice == 1; config.save(); render();
                    });
            axis.select(widget.absoluteAxis ? 1 : 0, false);
            list.addView(axis, new LinearLayout.LayoutParams(-1, -2));
        }
        list.addView(sheetLabel("Color"));
        Flow swatches = new Flow();
        String[] names = {"Red", "Blue", "Amber", "Rose"};
        int[] colors = {ui.accent, ui.blue, ui.orange, ui.danger};
        ArrayList<Swatch> all = new ArrayList<>();
        for (int i = 0; i < colors.length; i++) {
            final int index = i;
            Swatch swatch = new Swatch(colors[i], names[i]);
            swatch.setOnClickListener(
                    v -> {
                        widget.color = index;
                        config.save();
                        for (Swatch other : all) other.select(other == swatch);
                        render();
                    });
            swatch.select(widget.color == i);
            all.add(swatch);
            swatches.addView(swatch, new ViewGroup.LayoutParams(ui.dp(48), ui.dp(48)));
        }
        list.addView(swatches, new LinearLayout.LayoutParams(-1, -2));
        TextView save = ui.button("✓ Save", () -> {
            applyTitle.run();
            if (!config.widgets.contains(widget)) {
                DashboardEditHistory.Snapshot before = DashboardEditHistory.capture(config);
                config.widgets.add(widget);
                grid.settle(widget.id);
                recordEdit(before);
            }
            config.save();
            dismiss();
            render();
            revealIfAdded(widget);
        });
        save.setContentDescription("Save widget options");
        LinearLayout buttons = row();
        buttons.setGravity(Gravity.CENTER);
        buttons.setPadding(0, ui.dp(12), 0, 0);
        buttons.addView(save, new LinearLayout.LayoutParams(-1, -2));
        String index = DashboardConfig.NAMES[Math.max(0, Arrays.asList(DashboardConfig.TYPES).indexOf(widget.type))];
        showSheet(index, list, buttons, "Close widget options", applyTitle);
    }

    /** Ordered, named actions. Configuration never executes a scooter command. */
    private void quickOptions(DashboardConfig.Widget widget) {
        LinearLayout list = column();
        TextView count = ui.caption("");
        count.setPadding(0, ui.dp(8), 0, ui.dp(10));
        list.addView(count);
        LinearLayout rows = column();
        rows.setTag("quick-options-rows");
        list.addView(rows);
        Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            Map<String, Float> old = childPositions(rows);
            rows.removeAllViews();
            count.setText(widget.quickActions.size() + " of 8 shortcuts · tap to change");
            for (String id : new ArrayList<>(widget.quickActions)) {
                QuickAccessActions.Entry entry = QuickAccessActions.find(id);
                LinearLayout line = row(); line.setGravity(Gravity.CENTER_VERTICAL);
                line.setTag("quick-option:" + id);
                line.setPadding(ui.dp(8), ui.dp(6), 0, ui.dp(6));
                line.setBackground(ui.shape(ui.raised, 14, 0));
                LinearLayout select = row(); select.setGravity(Gravity.CENTER_VERTICAL);
                select.setMinimumHeight(ui.dp(48)); select.setClickable(true); select.setFocusable(true);
                int index = widget.quickActions.indexOf(id);
                select.setContentDescription("Change shortcut " + (index + 1) + ", " + entry.label);
                select.setTag("quick-select:" + index);
                select.addView(new GlyphView(entry.icon, ui.accent), new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)));
                TextView name = ui.text(entry.label, 14, false);
                name.setPadding(ui.dp(10), 0, ui.dp(4), 0);
                select.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
                select.setOnClickListener(v -> chooseQuickAction(widget, widget.quickActions.indexOf(id)));
                line.addView(select, new LinearLayout.LayoutParams(0, -2, 1));
                FrameLayout up = roundAction("back", "Move " + entry.label + " earlier", 30, 15, () -> {
                    int at = widget.quickActions.indexOf(id);
                    if (at <= 0) return;
                    Collections.swap(widget.quickActions, at, at - 1);
                    config.save(); render(); fill[0].run();
                });
                up.getChildAt(0).setRotation(90);
                up.setEnabled(index > 0); up.setAlpha(index > 0 ? 1 : .3f);
                line.addView(up, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
                FrameLayout remove = roundAction("trash", "Remove shortcut " + entry.label, 30, 15, () -> {
                    if (widget.quickActions.size() <= QuickAccessActions.MIN_ITEMS) return;
                    stopRemovalEffect();
                    removalEffect = WidgetRemovalEffect.play(list, line, ui);
                    widget.quickActions.remove(id);
                    config.save(); render(); fill[0].run();
                });
                remove.setEnabled(widget.quickActions.size() > QuickAccessActions.MIN_ITEMS);
                remove.setAlpha(remove.isEnabled() ? 1 : .3f);
                line.addView(remove, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
                LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, -2); space.bottomMargin = ui.dp(8);
                rows.addView(line, space);
            }
            if (widget.quickActions.size() < QuickAccessActions.MAX_ITEMS) {
                View addShortcut = toolButton("Add shortcut", "plus", () -> chooseQuickAction(widget, -1));
                addShortcut.setTag("add-quick-shortcut");
                addShortcut.setContentDescription("Add quick shortcut");
                rows.addView(addShortcut, new LinearLayout.LayoutParams(-1, ui.dp(52)));
            }
            animateRows(rows, old);
        };
        fill[0].run();
        TextView save = ui.button("✓ Save", () -> options(widget));
        showSheet("Quick settings", list, save, "Close quick settings", null);
    }

    private Map<String, Float> childPositions(ViewGroup parent) {
        Map<String, Float> positions = new HashMap<>();
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child.getTag() instanceof String && child.isLaidOut())
                positions.put((String)child.getTag(), child.getY());
        }
        return positions;
    }

    /** FLIP the changed list in its existing sheet; no dialog recreation or scroll reset. */
    private void animateRows(ViewGroup parent, Map<String, Float> old) {
        if (!Ui.motionEnabled() || old.isEmpty()) return;
        parent.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                if (parent.getViewTreeObserver().isAlive()) parent.getViewTreeObserver().removeOnPreDrawListener(this);
                if (!parent.isAttachedToWindow() || !Ui.motionEnabled()) return true;
                for (int i = 0; i < parent.getChildCount(); i++) {
                    View child = parent.getChildAt(i);
                    Float previous = old.get(child.getTag());
                    child.animate().cancel();
                    child.setTranslationY(previous == null ? -ui.dp(12) : previous - child.getTop());
                    child.setAlpha(previous == null ? 0 : 1);
                    child.animate().translationY(0).alpha(1).setDuration(210)
                            .setInterpolator(new android.view.animation.PathInterpolator(.2f, 0, 0, 1)).start();
                }
                return true;
            }
        });
    }

    private void saveQuickActions(DashboardConfig.Widget widget) {
        config.save(); render(); quickOptions(widget);
    }

    private void chooseQuickAction(DashboardConfig.Widget widget, int index) {
        LinearLayout content = column();
        EditText search = new EditText(dialogContext);
        search.setSingleLine(true); search.setHint("Search settings");
        search.setContentDescription("Search quick settings"); ui.styleSearch(search);
        content.addView(search, ui.searchLayout());
        LinearLayout choices = column(); content.addView(choices);
        Runnable filter = () -> {
            choices.removeAllViews();
            String query = search.getText().toString().toLowerCase(Locale.ROOT).trim();
            for (QuickAccessActions.Entry entry : QuickAccessActions.catalog()) {
                if (!query.isEmpty() && !(entry.label + " " + entry.group).toLowerCase(Locale.ROOT).contains(query)) continue;
                boolean selected = widget.quickActions.contains(entry.id);
                boolean current = index >= 0 && widget.quickActions.get(index).equals(entry.id);
                LinearLayout item = row(); item.setGravity(Gravity.CENTER_VERTICAL);
                item.setMinimumHeight(ui.dp(60)); item.setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8));
                item.addView(new GlyphView(entry.icon, ui.accent), new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)));
                LinearLayout words = column();
                words.addView(ui.text(entry.label, 14, false));
                words.addView(ui.caption(entry.group + (selected ? " · Selected" : entry.directToggle() ? " · Toggle" : "")));
                LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(0, -2, 1); space.setMarginStart(ui.dp(12));
                item.addView(words, space);
                item.setTag("quick-choice:" + entry.id);
                item.setContentDescription(entry.label + (selected ? ", selected" : ""));
                item.setClickable(true); item.setFocusable(true); item.setEnabled(!selected || current);
                item.setAlpha(item.isEnabled() ? 1 : .4f);
                item.setBackground(ripple(Ui.withAlpha(ui.accent, 30), null, ui.shape(Color.WHITE, 12, 0)));
                item.setOnClickListener(v -> {
                    if (selected && !current) return;
                    if (index < 0) {
                        if (widget.quickActions.size() >= QuickAccessActions.MAX_ITEMS) return;
                        widget.quickActions.add(entry.id);
                    } else widget.quickActions.set(index, entry.id);
                    saveQuickActions(widget);
                });
                choices.addView(item, new LinearLayout.LayoutParams(-1, -2));
            }
            if (choices.getChildCount() == 0) choices.addView(ui.caption("No matching settings"));
        };
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { filter.run(); }
            public void afterTextChanged(android.text.Editable text) {}
        });
        filter.run();
        showSheet("Choose shortcut", content, null, "Close shortcut picker", null);
        if (dialog != null) SearchFocus.bind(search, dialog.getWindow());
    }

    private void duplicateWidget(DashboardConfig.Widget widget) {
        if (!editing) return;
        grid.finishGesture(false);
        if (widget.type.equals("lock")) {
            host.toast("One lock widget per dashboard.");
            return;
        }
        if (config.widgets.size() >= 32) {
            host.toast("Up to 32 widgets per scooter.");
            return;
        }
        DashboardEditHistory.Snapshot before = DashboardEditHistory.capture(config);
        DashboardConfig.Widget copy = new DashboardConfig.Widget(widget.type);
        copy.title = widget.title;
        copy.metric = widget.metric;
        copy.note = widget.note;
        copy.keys.clear();
        copy.keys.addAll(widget.keys);
        copy.quickActions.clear();
        copy.quickActions.addAll(widget.quickActions);
        copy.color = widget.color;
        copy.windowMs = widget.windowMs;
        copy.minimum = widget.minimum;
        copy.maximum = widget.maximum;
        copy.advertisedWh = widget.advertisedWh;
        copy.showDisconnect = widget.showDisconnect;
        copy.absoluteAxis = widget.absoluteAxis;
        for (Map.Entry<Integer, DashboardLayout.Cell> entry : widget.layouts.entrySet()) {
            DashboardLayout.Cell original = entry.getValue();
            copy.layouts.put(entry.getKey(), new DashboardLayout.Cell(copy.id, original.x,
                    original.y + original.h, original.w, original.h));
        }
        config.widgets.add(copy);
        grid.settle(copy.id);
        recordEdit(before);
        RectF origin = null;
        for (Tile tile : tiles) if (tile.widget == widget)
            origin = new RectF(tile.getLeft(), tile.getTop(), tile.getRight(), tile.getBottom());
        config.save();
        dismiss();
        render();
        // The copy starts at its parent’s rectangle and separates into its settled cell.
        if (origin != null && Ui.motionEnabled()) grid.previousFrames.put(copy.id, origin);
    }

    private void removeWidget(DashboardConfig.Widget widget) {
        if (!editing) return;
        grid.finishGesture(false);
        if (!config.widgets.contains(widget)) return;
        DashboardEditHistory.Snapshot before = DashboardEditHistory.capture(config);
        stopRemovalEffect();
        for (Tile tile : tiles) if (tile.widget == widget) {
            removalEffect = WidgetRemovalEffect.play(view, tile, ui);
            break;
        }
        config.widgets.remove(widget);
        config.save();
        recordEdit(before);
        dismiss();
        render();
    }

    private void addSettingRow(LinearLayout card, LinearLayout row, String icon) {
        if (card.getChildCount() > 0) sheetDivider(card);
        ui.decorate((TextView) row.getChildAt(0), icon);
        card.addView(row);
    }

    /** A round colour swatch inside a 48 dp target; the selected one gets a ring. */
    private final class Swatch extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final int color;
        boolean selected;

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            // A fixed 48dp target; the flow layout measures children without their LayoutParams.
            setMeasuredDimension(resolveSize(ui.dp(48), widthSpec), resolveSize(ui.dp(48), heightSpec));
        }

        Swatch(int color, String name) {
            super(host.activity());
            this.color = color;
            setContentDescription(name);
            setFocusable(true);
            setClickable(true);
            setBackground(ripple(Ui.withAlpha(color, 50), null, roundDisc()));
        }

        void select(boolean value) {
            selected = value;
            setSelected(value);
            if (android.os.Build.VERSION.SDK_INT >= 30) setStateDescription(value ? "Selected" : "");
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            canvas.drawCircle(cx, cy, ui.dp(selected ? 14 : 17), paint);
            if (selected) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(ui.dp(2.5f));
                paint.setColor(ui.text);
                canvas.drawCircle(cx, cy, ui.dp(19.5f), paint);
            }
        }
    }

    private long[] windowOptions(DashboardConfig.Widget widget) {
        if (widget.type.equals("capacity"))
            return new long[] {
                7 * 86400000L, 30 * 86400000L, 90 * 86400000L, 180 * 86400000L, 365 * 86400000L, 730 * 86400000L
            };
        return new long[] {
            2 * 60000L,
            5 * 60000L,
            15 * 60000L,
            3600000L,
            6 * 3600000L,
            86400000L,
            7 * 86400000L,
            30 * 86400000L,
            90 * 86400000L,
            365 * 86400000L,
            730 * 86400000L
        };
    }

    /** Chip labels: 2 min, 1 h, 7 days, 1 year. */
    private String shortWindow(long ms) {
        if (ms < 3600000) return ms / 60000 + " min";
        if (ms < 86400000) return ms / 3600000 + " h";
        long days = ms / 86400000;
        if (days == 365) return "1 year";
        if (days == 730) return "2 years";
        return days == 1 ? "1 day" : days + " days";
    }

    /**
     * Bottom sheet with a heading and an X close; the content scrolls and an optional footer
     * stays pinned underneath it.
     */
    private void showSheet(String title, View content, View footer, String closeLabel, Runnable onClose) {
        showSheet(title, null, content, footer, closeLabel, onClose);
    }

    /** A fixed search header keeps filtering available while a long choice list scrolls. */
    private void showSheet(String title, View fixedHeader, View content, View footer,
            String closeLabel, Runnable onClose) {
        dismiss();
        int maximum = (int) (host.activity().getResources().getDisplayMetrics().heightPixels * .86);
        LinearLayout sheet =
                new LinearLayout(host.activity()) {
                    @Override
                    protected void onMeasure(int widthSpec, int heightSpec) {
                        int limit = MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED
                                ? maximum : Math.min(maximum, MeasureSpec.getSize(heightSpec));
                        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(limit,
                                fixedHeader == null ? MeasureSpec.AT_MOST : MeasureSpec.EXACTLY));
                    }
                };
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(ui.dp(18), ui.dp(8), ui.dp(18), ui.dp(18));
        View grab = new View(host.activity());
        grab.setBackground(ui.shape(ui.outline, 2, 0));
        LinearLayout.LayoutParams grabSize = new LinearLayout.LayoutParams(ui.dp(36), ui.dp(4));
        grabSize.gravity = Gravity.CENTER_HORIZONTAL;
        grabSize.bottomMargin = ui.dp(4);
        sheet.addView(grab, grabSize);
        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = ui.text(title, 20, true);
        heading.setTag("widget-options-title");
        if (android.os.Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        FrameLayout close = roundAction("close", closeLabel, 34, 14, this::dismiss);
        LinearLayout.LayoutParams closeSize = new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48));
        closeSize.setMarginStart(ui.dp(8));
        header.addView(close, closeSize);
        sheet.addView(header, new LinearLayout.LayoutParams(-1, -2));
        if (fixedHeader != null) sheet.addView(fixedHeader, new LinearLayout.LayoutParams(-1, -2));
        ScrollView scroll = new ScrollView(host.activity());
        scroll.setClipToPadding(false);
        scroll.setPadding(0, ui.dp(4), 0, ui.dp(4));
        scroll.addView(content);
        sheet.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (footer != null) sheet.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog created = new AlertDialog.Builder(dialogContext).create();
        dialog = created;
        created.setOnDismissListener(d -> {
            if (onClose != null) onClose.run();
            if (dialog == created) dialog = null;
            // Child sheets replace this one synchronously. A real dismissal abandons
            // the unpersisted draft; Save already consumed its arrival marker.
            view.post(() -> { if (dialog == null) pendingAddedWidgetId = null; });
        });
        // Build the sheet completely before it is shown: replacing AlertDialog's wrapping panel
        // after show() drew one centred, empty frame before the sheet jumped to the bottom.
        // The custom panel keeps the settings scrolling and the footer pinned to the sheet's edge.
        created.create();
        created.setContentView(sheet);
        Window window = created.getWindow();
        Motion.present(created, Motion.SHEET);
        if (window != null) {
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(ui.surface);
            float r = ui.dp(24);
            shape.setCornerRadii(new float[] {r, r, r, r, 0, 0, 0, 0});
            window.setBackgroundDrawable(shape);
            window.setGravity(Gravity.BOTTOM);
            window.setLayout(-1, -2);
            // The panel was installed without an editor; let the title field take the keyboard.
            window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        }
        created.show();
        if (window != null) window.setLayout(-1, -2);
    }

    private void changedWidget(DashboardConfig.Widget widget) {
        if (!config.widgets.contains(widget)) {
            options(widget);
            return;
        }
        config.save();
        dismiss();
        render();
        revealIfAdded(widget);
    }

    private Double rating(DashboardConfig.Widget widget) {
        if (widget.advertisedWh > 0) return widget.advertisedWh;
        ScooterProfiles.Profile profile = service == null ? null : service.profiles.active();
        // NAVEE US UT5 MAX specification: 46.8 V nominal × 15.3 Ah.
        return profile != null && profile.pid() == 2538 ? 716.04 : null;
    }

    private String ratingLabel(DashboardConfig.Widget widget) {
        Double value = rating(widget);
        return value == null
                ? "Set comparison value"
                : String.format(
                        Locale.US, "%.2f Wh%s", value, widget.advertisedWh == 0 ? " · model" : "");
    }

    private void advertisedCapacity(DashboardConfig.Widget widget) {
        dismiss();
        LinearLayout content = column();
        content.addView(
                ui.caption(
                        "Use the pack’s advertised energy in Wh, or nominal voltage × rated Ah."
                            + " Enter 0 to use the model rating when known. This only changes the"
                            + " comparison."));
        EditText input = new EditText(dialogContext);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setText(Double.toString(widget.advertisedWh));
        content.addView(input);
        dialog =
                new AlertDialog.Builder(dialogContext)
                        .setTitle("Advertised capacity (Wh)")
                        .setView(content)
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Save", null)
                        .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                        v -> {
                            try {
                                double n = Double.parseDouble(input.getText().toString());
                                if (!Double.isFinite(n) || n < 0 || n > 10000)
                                    throw new IllegalArgumentException();
                                widget.advertisedWh = n;
                                changedWidget(widget);
                            } catch (Exception e) {
                                input.setError("Enter Wh between 0 and 10,000.");
                            }
                        });
    }

    private void showCapacityMethod(DashboardConfig.Widget widget) {
        LinearLayout content = column();
        content.addView(ui.text("Experimental capacity estimate", 20, true));
        content.addView(
                ui.caption(
                        "Estimated full-pack energy = 100 × recorded energy ÷ battery-percentage"
                            + " points used. Qualified rides are combined by their percentage drop,"
                            + " with the active ride replacing its previous contribution."));
        content.addView(
                ui.caption(
                        "Use rides lasting at least a minute, each dropping at least 5 percentage"
                            + " points, with at least 20 points combined and 95% electrical"
                            + " coverage. Gaps, mismatched endpoints and battery-gauge rises are"
                            + " filtered. The selected window controls how much recent ride data is"
                            + " included."));
        content.addView(
                ui.caption(
                        "With confirmed current direction, the estimate uses net energy. Otherwise"
                            + " it shows a separate absolute-throughput proxy: returned energy is"
                            + " counted too, so it can overstate usable capacity. A percentage"
                            + " beside this proxy is not battery health."));
        content.addView(
                ui.caption(
                        "Cold, load, gauge rounding and recalibration affect both estimates. Energy"
                            + " per percentage point also changes with battery voltage. These"
                            + " estimates do not establish the cells’ actual capacity or prove a"
                            + " pack is smaller than advertised. No extra discharge or ride is"
                            + " started."));
        content.addView(
                ui.caption(
                        "UT5 MAX model reference: 46.8 V × 15.3 Ah = 716.04 Wh, from NAVEE US"
                                + " product specifications. Other models need a comparison value in"
                                + " widget options."));
        show("How this is estimated", content);
    }

    private void editText(
            DashboardConfig.Widget widget,
            String label,
            String value,
            java.util.function.Consumer<String> apply) {
        dismiss();
        EditText input = new EditText(dialogContext);
        input.setText(value);
        input.setSelectAllOnFocus(true);
        input.setMaxLines(label.equals("Note") ? 6 : 2);
        input.setFilters(
                new android.text.InputFilter[] {
                    new android.text.InputFilter.LengthFilter(label.equals("Note") ? 1200 : 80)
                });
        dialog =
                new AlertDialog.Builder(dialogContext)
                        .setTitle(label)
                        .setView(input)
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton(
                                "Save",
                                (d, w) -> {
                                    apply.accept(input.getText().toString());
                                    changedWidget(widget);
                                })
                        .show();
    }

    private void chooseMetric(DashboardConfig.Widget widget) {
        List<Telemetry.Metric> metrics = new Telemetry().metrics();
        LinearLayout content = column();
        EditText search = new EditText(dialogContext);
        search.setHint("Search parameters");
        search.setSingleLine(true);
        ui.styleSearch(search);
        content.addView(search, ui.searchLayout());
        LinearLayout rows = column();
        content.addView(rows);
        Runnable fill =
                () -> {
                    rows.removeAllViews();
                    String q = search.getText().toString().toLowerCase(Locale.ROOT);
                    for (Telemetry.Metric metric : metrics)
                        if ((metric.label + metric.group + metric.key)
                                .toLowerCase(Locale.ROOT)
                                .contains(q)) {
                            LinearLayout row =
                                    ui.settingRow(
                                            metric.label,
                                            metric.group,
                                            () -> {
                                                widget.metric = metric.key;
                                                changedWidget(widget);
                                            });
                            ui.decorate((TextView) row.getChildAt(0), ui.featureIcon(metric.key));
                            rows.addView(row);
                        }
                };
        search.addTextChangedListener(
                new android.text.TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int st, int c, int a) {}

                    public void onTextChanged(CharSequence s, int st, int b, int c) {
                        fill.run();
                    }

                    public void afterTextChanged(android.text.Editable e) {}
                });
        fill.run();
        show("Choose parameter", content);
        SearchFocus.bind(search, dialog.getWindow());
    }

    private void chooseReadings(DashboardConfig.Widget widget) {
        List<Telemetry.Metric> metrics = new Telemetry().metrics();
        // Keep the draft independent of the filtered views. Closing discards it;
        // saving retains selection order, including readings hidden by the query.
        LinkedHashSet<String> chosen = new LinkedHashSet<>(widget.keys);
        LinearLayout header = column();
        EditText search = new EditText(dialogContext);
        search.setSingleLine(true);
        search.setHint("Search readings");
        search.setContentDescription("Search readings");
        ui.styleSearch(search);
        search.setBackground(ui.shape(ui.dark ? ui.bg : ui.raised, 14, ui.outline));
        header.addView(search, ui.searchLayout());
        TextView count = ui.caption(chosen.size() + " of 12 selected");
        count.setPadding(ui.dp(2), 0, ui.dp(2), ui.dp(10));
        count.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        header.addView(count);
        LinearLayout choices = column();
        TextView selectedHeading = sheetLabel("Selected");
        selectedHeading.setTag("selected-readings-heading");
        choices.addView(selectedHeading);
        LinearLayout selectedRows = column();
        selectedRows.setTag("selected-readings");
        choices.addView(selectedRows);
        TextView allHeading = sheetLabel("All readings");
        allHeading.setTag("all-readings-heading");
        choices.addView(allHeading);
        ArrayList<View> items = new ArrayList<>();
        Map<String, View> originals = new LinkedHashMap<>();
        Runnable[] refreshSelected = new Runnable[1];
        for (Telemetry.Metric metric : metrics) {
            LinearLayout item = row();
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setMinimumHeight(ui.dp(64));
            item.setPadding(ui.dp(12), ui.dp(12), ui.dp(8), ui.dp(12));
            item.setClickable(true);
            item.setFocusable(true);
            item.setTag("reading-choice:" + metric.key);
            item.setContentDescription(metric.label + ", " + metric.group);
            GlyphView icon = new GlyphView(readingIcon(metric.key), ui.accent);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            item.addView(icon, new LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)));
            LinearLayout words = column();
            words.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            words.addView(ui.text(metric.label, 14, false));
            TextView group = ui.caption(metric.group);
            group.setPadding(0, ui.dp(3), 0, 0);
            words.addView(group);
            LinearLayout.LayoutParams wordsSize = new LinearLayout.LayoutParams(0, -2, 1);
            wordsSize.setMargins(ui.dp(12), 0, ui.dp(8), 0);
            item.addView(words, wordsSize);
            CheckBox check = new CheckBox(dialogContext);
            check.setButtonTintList(new android.content.res.ColorStateList(
                    new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
                    new int[] {ui.accent, ui.muted}));
            check.setClickable(false);
            check.setFocusable(false);
            check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            item.addView(check, new LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)));
            Runnable style = () -> {
                boolean selected = chosen.contains(metric.key);
                check.setChecked(selected);
                item.setSelected(selected);
                item.setBackground(ripple(Ui.withAlpha(ui.accent, 30),
                        ui.shape(selected ? blend(ui.raised, ui.accent, ui.dark ? .14f : .08f) : ui.raised,
                                14, selected ? Ui.withAlpha(ui.accent, 110) : 0), null));
            };
            item.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View view,
                        android.view.accessibility.AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(view, info);
                    info.setClassName("android.widget.CheckBox");
                    info.setCheckable(true);
                    info.setChecked(chosen.contains(metric.key));
                }
            });
            item.setOnClickListener(v -> {
                if (chosen.contains(metric.key)) chosen.remove(metric.key);
                else if (chosen.size() < 12) chosen.add(metric.key);
                else {
                    host.toast("Choose up to 12 readings.");
                    return;
                }
                style.run();
                count.setText(chosen.size() + " of 12 selected");
                refreshSelected[0].run();
            });
            style.run();
            LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2);
            gap.bottomMargin = ui.dp(8);
            choices.addView(item, gap);
            items.add(item);
            originals.put(metric.key, item);
        }
        refreshSelected[0] = () -> {
            Map<String, Float> old = childPositions(choices);
            Map<String, Float> pinned = childPositions(selectedRows);
            selectedRows.removeAllViews();
            selectedHeading.setVisibility(chosen.isEmpty() ? View.GONE : View.VISIBLE);
            for (String key : chosen) {
                View original = originals.get(key);
                if (original == null) continue;
                LinearLayout selected = row(); selected.setGravity(Gravity.CENTER_VERTICAL);
                selected.setPadding(ui.dp(12), ui.dp(8), ui.dp(8), ui.dp(8));
                selected.setMinimumHeight(ui.dp(56));
                selected.setTag("selected-reading:" + key);
                selected.setContentDescription(label(key) + ", selected, uncheck");
                selected.setClickable(true); selected.setFocusable(true);
                selected.setBackground(ripple(Ui.withAlpha(ui.accent, 30),
                        ui.shape(blend(ui.raised, ui.accent, ui.dark ? .14f : .08f), 14,
                                Ui.withAlpha(ui.accent, 110)), null));
                selected.addView(new GlyphView(readingIcon(key), ui.accent),
                        new LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)));
                TextView name = ui.text(ScooterReadingLabels.label(key, label(key)), 14, false);
                name.setPadding(ui.dp(12), 0, ui.dp(8), 0);
                selected.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
                CheckBox check = new CheckBox(dialogContext); check.setChecked(true);
                check.setButtonTintList(android.content.res.ColorStateList.valueOf(ui.accent));
                check.setClickable(false); check.setFocusable(false);
                check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                selected.addView(check, new LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)));
                selected.setOnClickListener(v -> original.performClick());
                selected.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                    @Override public void onInitializeAccessibilityNodeInfo(View v,
                            android.view.accessibility.AccessibilityNodeInfo info) {
                        super.onInitializeAccessibilityNodeInfo(v, info);
                        info.setClassName("android.widget.CheckBox"); info.setCheckable(true); info.setChecked(true);
                    }
                });
                LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2); gap.bottomMargin = ui.dp(8);
                selectedRows.addView(selected, gap);
            }
            animateRows(choices, old);
            animateRows(selectedRows, pinned);
        };
        refreshSelected[0].run();
        TextView empty = ui.caption("No matching readings");
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(ui.dp(12), ui.dp(24), ui.dp(12), ui.dp(24));
        choices.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        Runnable filter = () -> {
            String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            int matches = 0;
            for (int i = 0; i < metrics.size(); i++) {
                Telemetry.Metric metric = metrics.get(i);
                boolean visible = (metric.label + " " + metric.group + " " + metric.key
                        + " " + ScooterReadingLabels.label(metric.key, metric.label))
                        .toLowerCase(Locale.ROOT).contains(query);
                items.get(i).setVisibility(visible ? View.VISIBLE : View.GONE);
                if (visible) matches++;
            }
            empty.setVisibility(matches == 0 ? View.VISIBLE : View.GONE);
            if (choices.getParent() instanceof ScrollView)
                ((ScrollView) choices.getParent()).scrollTo(0, 0);
        };
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { filter.run(); }
            public void afterTextChanged(android.text.Editable text) {}
        });
        filter.run();
        TextView save = ui.button("✓ Save", () -> {
            if (chosen.size() > 12) {
                host.toast("Choose up to 12 readings.");
                return;
            }
            widget.keys.clear();
            widget.keys.addAll(chosen);
            config.save();
            render();
            options(widget);
        });
        save.setContentDescription("Save selected readings");
        LinearLayout footer = column();
        footer.setPadding(0, ui.dp(12), 0, 0);
        footer.addView(save, new LinearLayout.LayoutParams(-1, -2));
        showSheet("Choose readings", header, choices, footer, "Cancel reading selection", null);
        if (dialog != null) SearchFocus.bind(search, dialog.getWindow());
    }

    private String windowName(long ms) {
        if (ms < 3600000) return ms / 60000 + " minutes";
        if (ms < 86400000) return ms / 3600000 + " hours";
        return ms / 86400000 + " days";
    }

    private String scaleSummary(DashboardConfig.Widget widget, double value) {
        double displayed = chartNumber(widget.metric, value);
        String amount = widget.metric.endsWith("_kmh")
                ? new GaugeScaleInput(value, displayed, 1).text
                : String.format(Locale.US, "%.2f", displayed);
        return amount + " " + chartUnit(widget.metric, readings());
    }

    private void scale(DashboardConfig.Widget widget, boolean maximum) {
        dismiss();
        EditText input = new EditText(dialogContext);
        input.setInputType(
                InputType.TYPE_CLASS_NUMBER
                        | InputType.TYPE_NUMBER_FLAG_DECIMAL
                        | InputType.TYPE_NUMBER_FLAG_SIGNED);
        double original = maximum ? widget.maximum : widget.minimum;
        GaugeScaleInput scaleInput = new GaugeScaleInput(original, chartNumber(widget.metric, original),
                widget.metric.endsWith("_kmh") ? 1 : 6);
        input.setText(scaleInput.text);
        dialog =
                new AlertDialog.Builder(dialogContext)
                        .setTitle(
                                maximum
                                        ? "Scale maximum (display units)"
                                        : "Scale minimum (display units)")
                        .setView(input)
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Save", null)
                        .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                        v -> {
                            try {
                                double n = scaleInput.resolve(input.getText().toString(), value -> {
                                    if (DisplayTemperature.isTemperature(widget.metric))
                                        return DisplayTemperature.toCelsius(value, temperatureUnit());
                                    if (!units().equals("km") && (widget.metric.endsWith("_km")
                                            || widget.metric.endsWith("_kmh"))) return value * 1.609344;
                                    return value;
                                });
                                if (!Double.isFinite(n)
                                        || (maximum ? n <= widget.minimum : n >= widget.maximum))
                                    throw new IllegalArgumentException();
                                if (maximum) widget.maximum = n;
                                else widget.minimum = n;
                                changedWidget(widget);
                            } catch (Exception e) {
                                input.setError("Enter a finite range with maximum above minimum.");
                            }
                        });
    }

    private void size(DashboardConfig.Widget widget) {
        dismiss();
        DashboardLayout.Cell cell = grid.currentCell(widget);
        LinearLayout controls = column();
        NumberPicker width = new NumberPicker(dialogContext);
        width.setMinValue(grid.minimumWidth(widget));
        width.setMaxValue(grid.columns);
        width.setValue(cell.w);
        NumberPicker height = new NumberPicker(dialogContext);
        int minimumHeight = widget.minimumHeight((cell.w * grid.columnWidth() - ui.dp(10))
                / host.activity().getResources().getDisplayMetrics().density);
        height.setMinValue(minimumHeight);
        height.setMaxValue(16);
        height.setValue(Math.max(minimumHeight, cell.h));
        controls.addView(ui.caption("Columns"));
        controls.addView(width);
        controls.addView(ui.caption("Rows"));
        controls.addView(height);
        dialog =
                new AlertDialog.Builder(dialogContext)
                        .setTitle("Widget size")
                        .setView(controls)
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton(
                                "Apply",
                                (d, n) -> {
                                    DashboardEditHistory.Snapshot before = DashboardEditHistory.capture(config);
                                    grid.prepareEdit();
                                    DashboardLayout.Cell latest = widget.layouts.get(grid.columns);
                                    latest.w = width.getValue();
                                    latest.h = height.getValue();
                                    grid.settle(widget.id);
                                    recordEdit(before);
                                    changedWidget(widget);
                                })
                        .show();
    }

    private void position(DashboardConfig.Widget widget) {
        dismiss();
        DashboardLayout.Cell cell = grid.currentCell(widget);
        LinearLayout controls = column();
        EditText x = new EditText(dialogContext), y = new EditText(dialogContext);
        x.setInputType(InputType.TYPE_CLASS_NUMBER);
        y.setInputType(InputType.TYPE_CLASS_NUMBER);
        x.setText(Integer.toString(cell.x + 1));
        y.setText(Integer.toString(cell.y + 1));
        controls.addView(ui.caption("Column (1–" + grid.columns + ")"));
        controls.addView(x);
        controls.addView(ui.caption("Row (1–301)"));
        controls.addView(y);
        dialog =
                new AlertDialog.Builder(dialogContext)
                        .setTitle("Move widget")
                        .setView(controls)
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Move", null)
                        .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                        v -> {
                            try {
                                int xx = Integer.parseInt(x.getText().toString()) - 1,
                                        yy = Integer.parseInt(y.getText().toString()) - 1;
                                if (xx < 0 || xx >= grid.columns || yy < 0 || yy > 300)
                                    throw new IllegalArgumentException();
                                DashboardEditHistory.Snapshot before = DashboardEditHistory.capture(config);
                                grid.prepareEdit();
                                DashboardLayout.Cell latest = widget.layouts.get(grid.columns);
                                latest.x = xx;
                                latest.y = yy;
                                grid.settle(widget.id);
                                recordEdit(before);
                                changedWidget(widget);
                            } catch (Exception e) {
                                host.toast("Use the column and row ranges shown.");
                            }
                        });
    }

    /** One recessed segmented control; its highlight retargets from the current visual position. */
    private final class ColumnSelector extends HorizontalScrollView {
        final ColumnTrack track;

        ColumnSelector(int usable, int selected) {
            super(host.activity());
            setTag("dashboard-column-selector");
            setFillViewport(true);
            setHorizontalScrollBarEnabled(false);
            setClipToOutline(true);
            setBackground(ui.shape(ui.dark ? ui.bg : ui.raised, 14, ui.outline));
            track = new ColumnTrack(usable, selected);
            addView(track, new HorizontalScrollView.LayoutParams(-2, -2));
        }

        private final class ColumnTrack extends LinearLayout {
            final ArrayList<TextView> choices = new ArrayList<>();
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            final RectF indicator = new RectF();
            android.animation.ValueAnimator animation;
            int selected;

            ColumnTrack(int usable, int selected) {
                super(host.activity());
                this.selected = Math.max(0, Math.min(DashboardConfig.MAX_COLUMNS, selected));
                setOrientation(HORIZONTAL);
                setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4));
                setWillNotDraw(false);
                for (int i = 0; i <= DashboardConfig.MAX_COLUMNS; i++) {
                    final int choice = i;
                    TextView button = ui.text(i == 0 ? "Adaptive" : Integer.toString(i), 13, true);
                    button.setGravity(Gravity.CENTER);
                    button.setMinWidth(ui.dp(i == 0 ? 90 : 48));
                    button.setPadding(ui.dp(12), 0, ui.dp(12), 0);
                    button.setFocusable(true);
                    button.setClickable(true);
                    button.setEnabled(i == 0 || i <= usable);
                    button.setAlpha(button.isEnabled() ? 1f : .4f);
                    button.setContentDescription(i == 0 ? "Adaptive columns"
                            : i + (i == 1 ? " column" : " columns")
                                    + (button.isEnabled() ? "" : ", needs a wider screen"));
                    button.setOnClickListener(v -> {
                        this.selected = choice;
                        preferences.edit().putInt("dashboard_columns", choice).apply();
                        updateSelection(true);
                        grid.finishGesture(false);
                        grid.requestLayout();
                        int target = button.getLeft() - (ColumnSelector.this.getWidth() - button.getWidth()) / 2;
                        if (android.animation.ValueAnimator.areAnimatorsEnabled())
                            ColumnSelector.this.smoothScrollTo(Math.max(0, target), 0);
                        else ColumnSelector.this.scrollTo(Math.max(0, target), 0);
                    });
                    choices.add(button);
                    addView(button, new LinearLayout.LayoutParams(-2, ui.dp(48), 1));
                }
            }

            private void updateSelection(boolean animate) {
                for (int i = 0; i < choices.size(); i++) {
                    TextView button = choices.get(i);
                    button.setSelected(i == selected);
                    button.setTextColor(i == selected ? ui.onAccent : ui.text);
                    if (android.os.Build.VERSION.SDK_INT >= 30)
                        button.setStateDescription(i == selected ? "Selected" : "");
                }
                TextView button = choices.get(selected);
                if (button.getWidth() == 0) return;
                RectF end = new RectF(button.getLeft(), button.getTop(), button.getRight(), button.getBottom());
                if (animation != null) animation.cancel();
                if (!animate || indicator.isEmpty() || !android.animation.ValueAnimator.areAnimatorsEnabled()) {
                    indicator.set(end);
                    invalidate();
                    return;
                }
                RectF start = new RectF(indicator);
                animation = android.animation.ValueAnimator.ofFloat(0, 1);
                animation.setDuration(190);
                animation.setInterpolator(new android.view.animation.PathInterpolator(.2f, 0, 0, 1));
                animation.addUpdateListener(a -> {
                    float f = (float) a.getAnimatedValue();
                    indicator.set(start.left + (end.left - start.left) * f,
                            start.top + (end.top - start.top) * f,
                            start.right + (end.right - start.right) * f,
                            start.bottom + (end.bottom - start.bottom) * f);
                    invalidate();
                });
                animation.start();
            }

            @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
                super.onLayout(changed, l, t, r, b);
                if (changed || indicator.isEmpty()) updateSelection(false);
            }

            @Override protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                // A restrained inner shade gives the shared track a recessed edge.
                paint.setShader(new LinearGradient(0, 0, 0, ui.dp(10),
                        Color.argb(ui.dark ? 80 : 24, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP));
                canvas.drawRoundRect(0, 0, getWidth(), getHeight(), ui.dp(14), ui.dp(14), paint);
                paint.setShader(null);
                paint.setColor(ui.accent);
                canvas.drawRoundRect(indicator, ui.dp(14), ui.dp(14), paint);
            }

            @Override protected void onDetachedFromWindow() {
                if (animation != null) animation.cancel();
                super.onDetachedFromWindow();
            }
        }
    }

    private void layoutOptions() {
        grid.finishGesture(false);
        LinearLayout content = column();
        content.addView(sheetLabel("Columns"));
        int usable = grid.maximumColumns();
        int selected = preferences.getInt("dashboard_columns", 0);
        content.addView(new ColumnSelector(usable, selected), new LinearLayout.LayoutParams(-1, -2));
        String unavailable = usable < DashboardConfig.MAX_COLUMNS
                ? "More columns become available on wider screens. " : "";
        TextView note = ui.caption(unavailable + "Layouts are saved separately for each column count.");
        note.setPadding(ui.dp(2), ui.dp(10), ui.dp(2), ui.dp(14));
        content.addView(note);
        content.addView(sheetLabel("Saved layouts"));
        TextView savedHint = ui.caption("Each slot keeps this scooter’s folded and unfolded layouts, including widget options.");
        savedHint.setPadding(ui.dp(2), 0, ui.dp(2), ui.dp(10));
        content.addView(savedHint);
        for (int i = 0; i < DashboardConfig.SLOT_COUNT; i++) addLayoutSlot(content, i);
        content.addView(sheetLabel("Default layout"));
        LinearLayout restore = row();
        restore.setGravity(Gravity.CENTER_VERTICAL);
        restore.setMinimumHeight(ui.dp(64));
        restore.setPadding(ui.dp(12), ui.dp(10), ui.dp(8), ui.dp(10));
        restore.setBackground(ripple(Ui.withAlpha(ui.accent, 26),
                ui.shape(ui.dark ? ui.raised : ui.bg, 16, 0), null));
        restore.setClickable(true);
        restore.setFocusable(true);
        restore.addView(square("restore", ui.accent, 38), new LinearLayout.LayoutParams(ui.dp(38), ui.dp(38)));
        LinearLayout words = column();
        TextView name = ui.text("Restore default widgets", 15, false);
        name.setTypeface(ui.medium());
        words.addView(name);
        TextView detail = ui.caption("Lock, scooter, battery, live trend, capacity, recorder");
        detail.setPadding(0, ui.dp(2), 0, 0);
        words.addView(detail);
        LinearLayout.LayoutParams wordsSpace = new LinearLayout.LayoutParams(0, -2, 1);
        wordsSpace.setMarginStart(ui.dp(12));
        restore.addView(words, wordsSpace);
        restore.addView(new GlyphView("chevron", ui.muted), new LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)));
        restore.setOnClickListener(
                v -> {
                    dismiss();
                    dialog =
                            new AlertDialog.Builder(dialogContext)
                                    .setTitle("Restore default dashboard?")
                                    .setMessage(
                                            "This replaces the current folded and unfolded layouts."
                                                    + " Saved slots, readings and rides are kept.")
                                    .setNegativeButton("Cancel", null)
                                    .setPositiveButton(
                                            "Restore",
                                            (d, n) -> {
                                                stopRemovalEffect();
                                                editHistory.clear();
                                                config.reset();
                                                render();
                                            })
                                    .show();
                });
        content.addView(restore, new LinearLayout.LayoutParams(-1, -2));
        showSheet("Dashboard layout", content, null, "Close dashboard layout", null);
    }

    private void addLayoutSlot(LinearLayout parent, int index) {
        DashboardConfig.SavedSlot saved = config.slot(index);
        boolean occupied = config.slotExists(index);
        String slotName = "Slot " + (index + 1);
        String title = saved == null || saved.name.equals(slotName)
                ? slotName : slotName + " · " + saved.name;
        LinearLayout card = sheetCard();
        card.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
        TextView heading = ui.text(title, 15, true);
        if (android.os.Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        card.addView(heading);
        String detail = saved == null
                ? (occupied ? "Saved layout unavailable · save again to replace it" : "Empty")
                : (saved.widgetCount == 1 ? "1 widget" : saved.widgetCount + " widgets")
                        + " · " + DisplayTime.recent(host.activity(), preferences,
                                saved.savedAtMs, System.currentTimeMillis());
        TextView summary = ui.caption(detail);
        summary.setPadding(0, ui.dp(3), 0, ui.dp(8));
        card.addView(summary);
        LinearLayout buttons = row();
        TextView save = ui.secondaryButton("Save", () -> saveLayoutSlot(index));
        save.setContentDescription("Save current dashboard to slot " + (index + 1));
        save.setTag("dashboard-slot-save-" + index);
        TextView load = ui.button("Restore", () -> restoreLayoutSlot(index));
        load.setContentDescription("Restore slot " + (index + 1)
                + (saved == null ? "" : ", " + saved.name));
        load.setTag("dashboard-slot-restore-" + index);
        load.setEnabled(saved != null);
        LinearLayout.LayoutParams saveSize = new LinearLayout.LayoutParams(0, -2, 1);
        saveSize.setMarginEnd(ui.dp(8));
        buttons.addView(save, saveSize);
        buttons.addView(load, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(buttons);
        LinearLayout.LayoutParams cardSize = new LinearLayout.LayoutParams(-1, -2);
        cardSize.bottomMargin = ui.dp(10);
        parent.addView(card, cardSize);
    }

    private void saveLayoutSlot(int index) {
        DashboardConfig.SavedSlot saved = config.slot(index);
        boolean replacing = config.slotExists(index);
        dismiss();
        LinearLayout content = column();
        content.setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0);
        TextView message = ui.caption(replacing
                ? "Replace this saved slot with your current folded and unfolded layouts?"
                : "Save your current folded and unfolded layouts together.");
        message.setPadding(0, 0, 0, ui.dp(12));
        content.addView(message);
        EditText name = new EditText(dialogContext);
        name.setSingleLine(true);
        name.setHint("Layout name");
        name.setContentDescription("Saved layout name");
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setFilters(new android.text.InputFilter[] {
            new android.text.InputFilter.LengthFilter(DashboardConfig.SLOT_NAME_LIMIT)
        });
        name.setText(saved == null ? "Slot " + (index + 1) : saved.name);
        name.setSelectAllOnFocus(true);
        content.addView(name, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog created = new AlertDialog.Builder(dialogContext)
                .setTitle((replacing ? "Replace slot " : "Save to slot ") + (index + 1) + "?")
                .setView(content)
                .setNegativeButton("Cancel", (d, which) -> returnToLayoutOptions(d))
                .setOnCancelListener(this::returnToLayoutOptions)
                .setPositiveButton(replacing ? "Replace" : "Save", null)
                .create();
        dialog = created;
        created.show();
        created.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (closed || !editing) return;
            grid.finishGesture(false);
            boolean stored = config.saveSlot(index, name.getText().toString(),
                    preferences.getInt("dashboard_columns", 0));
            if (!stored) {
                host.toast("Couldn’t save the layout. Please try again.");
                return;
            }
            host.toast("Saved to slot " + (index + 1));
            layoutOptions();
        });
    }

    private void restoreLayoutSlot(int index) {
        DashboardConfig.SavedSlot saved = config.slot(index);
        if (saved == null) {
            host.toast("This saved layout is unavailable.");
            layoutOptions();
            return;
        }
        dismiss();
        AlertDialog created = new AlertDialog.Builder(dialogContext)
                .setTitle("Restore “" + saved.name + "”?")
                .setMessage("Replace the current folded and unfolded layouts, widget options"
                        + " and column choice? Column choice is shared across all scooters on this phone."
                        + " Save your current layout to another slot first if"
                        + " you want to keep it. Saved slots, readings and rides are kept.")
                .setNegativeButton("Cancel", (d, which) -> returnToLayoutOptions(d))
                .setOnCancelListener(this::returnToLayoutOptions)
                .setPositiveButton("Restore", null)
                .create();
        dialog = created;
        created.show();
        created.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (closed || !editing) return;
            grid.finishGesture(false);
            if (!config.restoreSlot(index)) {
                host.toast("Couldn’t restore the layout. Your current dashboard is kept.");
                return;
            }
            stopRemovalEffect();
            editHistory.clear();
            dismiss();
            render();
            host.toast("Restored “" + saved.name + "”");
        });
    }

    private void returnToLayoutOptions(android.content.DialogInterface source) {
        if (!closed && editing && dialog == source) layoutOptions();
    }

    private void show(String title, View content) {
        dismiss();
        ScrollView scroll = new ScrollView(host.activity());
        scroll.setFillViewport(false);
        int p = ui.dp(20);
        scroll.setPadding(p, 0, p, p);
        scroll.addView(content);
        dialog =
                new AlertDialog.Builder(dialogContext)
                        .setTitle(title)
                        .setView(scroll)
                        .setNegativeButton("Close", null)
                        .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(ui.shape(ui.surface, 24, 0));
            dialog.getWindow().setLayout(-1, (int) (host.activity().getResources().getDisplayMetrics().heightPixels * .86));
        }
        Motion.present(dialog, Motion.DIALOG);
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(ui.accent);
        if (dialog.getWindow() != null)
            dialog.getWindow().setLayout(-1, (int) (host.activity().getResources().getDisplayMetrics().heightPixels * .86));
    }

    private void dismiss() {
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
    }

    public boolean isEditing() {
        return editing;
    }

    /** Stop decorative scrolling immediately when the user takes over anywhere on the page. */
    public void onUserTouch() {
        cancelWidgetReveal(); revealWidgetId = null;
    }

    public boolean back() {
        if (editing) {
            toggleEditing();
            return true;
        }
        return false;
    }

    @Override
    public void close() {
        closed = true;
        chargingHero.stopReveal();
        cancelWidgetReveal();
        pendingAddedWidgetId = revealWidgetId = null;
        stopRemovalEffect();
        stopEditTransition();
        editHistory.clear();
        for (Tile tile : tiles) tile.active = false;
        dismiss();
        if (grid != null) {
            grid.finishGesture(false);
            grid.stopMotion();
        }
    }
}
