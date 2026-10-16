package local.navee.offlinelock;

import android.app.Dialog;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Saved predictions only: opening a recording never runs today's charge estimator. */
final class ChargeHistoryDialog {
    private final ScreenHost host;
    private final Ui ui;
    private final SharedPreferences settings;
    private final String profile;
    private Dialog dialog, expanded;
    private LinearLayout body;
    private final List<SegmentedSelector> selectors = new ArrayList<>();
    private List<ChargeSessionStore.HistorySession> recordings = Collections.emptyList();
    private boolean closed;
    private int loadGeneration;

    ChargeHistoryDialog(ScreenHost host) {
        this.host = host; ui = host.ui(); profile = activeProfile();
        settings = host.service() == null ? null : host.service().settings;
    }
    private String activeProfile() {
        ScooterService s = host.service();
        return s == null || s.profiles == null || s.profiles.active() == null ? "" : s.profiles.active().id;
    }
    void show() {
        if (closed || settings == null || profile.isEmpty() || host.activity().isDestroyed()) return;
        body = column(); body.setBackgroundColor(ui.bg);
        dialog = page(body);
        dialog.setOnDismissListener(d -> close());
        load();
    }
    void refresh() { if (!profile.equals(activeProfile())) close(); }
    void close() {
        if (closed) return;
        closed = true; loadGeneration++;
        for (SegmentedSelector selector : selectors) selector.finishMotion();
        selectors.clear();
        if (expanded != null) expanded.dismiss();
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
    }
    private boolean available() {
        return !closed && dialog != null && dialog.isShowing() && !host.activity().isDestroyed()
                && profile.equals(activeProfile());
    }
    private void load() {
        if (!available()) return;
        body.removeAllViews(); body.addView(header("Charge recordings", false));
        TextView loading = ui.caption("Loading recordings…"); loading.setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(12));
        body.addView(loading);
        int generation = ++loadGeneration;
        ScooterService s = host.service();
        if (s == null || !s.loadChargeRecordings(sessions -> {
            if (!available() || generation != loadGeneration) return;
            if (sessions == null) { failed(); return; }
            recordings = sessions; showList();
        })) failed();
    }
    private void failed() {
        if (!available()) return;
        body.removeAllViews(); body.addView(header("Charge recordings", false));
        LinearLayout box = padded(); box.addView(ui.caption("Could not load recordings."));
        box.addView(ui.secondaryButton("Retry", this::load)); body.addView(box);
    }
    private void showList() {
        if (!available()) return;
        settleSelectors(); body.removeAllViews(); body.addView(header("Charge recordings", false));
        LinearLayout list = padded(); list.setTag("charge-history-list");
        if (recordings.isEmpty()) list.addView(ui.caption("No saved charge recordings yet."));
        for (ChargeSessionStore.HistorySession session : recordings) {
            LinearLayout row = column(); row.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14));
            row.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 45)),
                    ui.shape(ui.surface, 18, 0), null));
            row.addView(ui.text(dateTime(session.startedWall), 16, true));
            TextView details = ui.caption(charger(session.charger) + " · " + batterySpan(session));
            details.setPadding(0, ui.dp(5), 0, 0); row.addView(details);
            TextView state = ui.caption(session.observedCompleteMono == null ? "Finish not observed" : "100% observed");
            state.setPadding(0, ui.dp(4), 0, 0); row.addView(state);
            row.setTag("charge-history-session:" + session.id); row.setClickable(true); row.setFocusable(true);
            row.setContentDescription(dateTime(session.startedWall) + ", " + details.getText() + ", " + state.getText());
            row.setOnClickListener(v -> showDetail(session));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2); rp.bottomMargin = ui.dp(10); list.addView(row, rp);
        }
        if (host.service() != null && host.service().isChargeRecording()) {
            TextView note = ui.caption("The current recording appears here after it stops."); note.setPadding(0, ui.dp(8), 0, 0); list.addView(note);
        }
        body.addView(scroller(list), new LinearLayout.LayoutParams(-1, 0, 1));
    }
    private void showDetail(ChargeSessionStore.HistorySession session) {
        if (!available() || !profile.equals(session.profile)) return;
        settleSelectors(); body.removeAllViews(); body.addView(header("Charge recording", true));
        LinearLayout contents = padded(); contents.setTag("charge-history-detail");
        contents.addView(ui.text(dateTime(session.startedWall), 20, true));
        TextView sub = ui.caption(charger(session.charger) + " · " + batterySpan(session));
        sub.setPadding(0, ui.dp(6), 0, ui.dp(12)); contents.addView(sub);
        Panel panel = new Panel(session, false, false, false);
        contents.addView(panel, new LinearLayout.LayoutParams(-1, -2));
        body.addView(scroller(contents), new LinearLayout.LayoutParams(-1, 0, 1));
    }
    private String batterySpan(ChargeSessionStore.HistorySession session) {
        Double first = null, last = null;
        for (ChargeSessionStore.HistoryPoint point : session.points) if (Double.isFinite(point.batteryPercent)) {
            if (first == null) first = point.batteryPercent; last = point.batteryPercent;
        }
        return first == null ? "No battery samples" : String.format(Locale.getDefault(), "%.0f%% → %.0f%%", first, last);
    }
    private void settleSelectors() {
        for (SegmentedSelector selector : selectors) selector.finishMotion(); selectors.clear();
    }
    private String charger(ChargeSessionStore.Charger charger) {
        return charger == ChargeSessionStore.Charger.HIGH_SPEED ? "High-speed charger" : "Stock charger";
    }
    private LinearLayout header(String title, boolean back) {
        LinearLayout head = new LinearLayout(host.activity()); head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(ui.dp(back ? 4 : 18), ui.dp(4), ui.dp(6), ui.dp(4));
        if (back) head.addView(iconButton("back", "Back to charge recordings", this::showList));
        TextView label = ui.text(title, 20, true); label.setMaxLines(2);
        head.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(iconButton("close", "Close charge recordings", this::close)); return head;
    }
    private Dialog page(View content) {
        Dialog opened = new Dialog(host.activity(), ui.dark ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        opened.requestWindowFeature(Window.FEATURE_NO_TITLE); opened.setContentView(content); Motion.present(opened, Motion.PAGE);
        Window window = opened.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(ui.bg)); window.setStatusBarColor(ui.bg); window.setNavigationBarColor(ui.bg);
            window.getDecorView().setPadding(0, 0, 0, 0);
            int appearance = ui.dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false);
            else appearance |= View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            window.getDecorView().setSystemUiVisibility(appearance);
            // Page dialogs draw edge-to-edge on modern Android. Apply safe areas to the
            // actual content, including landscape cutouts and gesture/button navigation.
            content.setOnApplyWindowInsetsListener((view, insets) -> {
                int left, top, right, bottom;
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    left = safe.left; top = safe.top; right = safe.right; bottom = safe.bottom;
                } else {
                    left = insets.getSystemWindowInsetLeft(); top = insets.getSystemWindowInsetTop();
                    right = insets.getSystemWindowInsetRight(); bottom = insets.getSystemWindowInsetBottom();
                    if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
                        android.view.DisplayCutout cutout = insets.getDisplayCutout();
                        left = Math.max(left, cutout.getSafeInsetLeft()); top = Math.max(top, cutout.getSafeInsetTop());
                        right = Math.max(right, cutout.getSafeInsetRight()); bottom = Math.max(bottom, cutout.getSafeInsetBottom());
                    }
                }
                view.setPadding(left, top, right, bottom); return insets;
            });
        }
        opened.show(); if (window != null) window.setLayout(-1, -1); content.requestApplyInsets(); return opened;
    }
    private View iconButton(String key, String description, Runnable action) {
        FrameLayout button = new FrameLayout(host.activity());
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 45)), ui.shape(ui.raised, 12, 0), null));
        View glyph = key.equals("fit") ? new View(host.activity()) {
            final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                stroke.setStyle(Paint.Style.STROKE); stroke.setStrokeWidth(ui.dp(1.8f)); stroke.setColor(ui.text);
                stroke.setStrokeCap(Paint.Cap.ROUND); stroke.setStrokeJoin(Paint.Join.ROUND);
                float edge = ui.dp(2), length = ui.dp(5), right = getWidth() - edge, bottom = getHeight() - edge;
                Path corners = new Path();
                corners.moveTo(edge, edge + length); corners.lineTo(edge, edge); corners.lineTo(edge + length, edge);
                corners.moveTo(right - length, edge); corners.lineTo(right, edge); corners.lineTo(right, edge + length);
                corners.moveTo(edge, bottom - length); corners.lineTo(edge, bottom); corners.lineTo(edge + length, bottom);
                corners.moveTo(right - length, bottom); corners.lineTo(right, bottom); corners.lineTo(right, bottom - length);
                canvas.drawPath(corners, stroke);
            }
        } : ui.icon(key, 21, ui.text);
        button.addView(glyph, new FrameLayout.LayoutParams(ui.dp(21), ui.dp(21), Gravity.CENTER));
        button.setContentDescription(description); button.setFocusable(true); button.setClickable(true); button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)); params.leftMargin = ui.dp(6); button.setLayoutParams(params); return button;
    }
    private LinearLayout column() { LinearLayout column = new LinearLayout(host.activity()); column.setOrientation(LinearLayout.VERTICAL); return column; }
    private LinearLayout padded() { LinearLayout column = column(); column.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(20)); return column; }
    private ScrollView scroller(View content) {
        ScrollView scroll = new ScrollView(host.activity()); scroll.setFillViewport(false); scroll.setClipToPadding(false);
        scroll.setVerticalFadingEdgeEnabled(true); scroll.setFadingEdgeLength(ui.dp(12)); scroll.addView(content); return scroll;
    }
    private String dateTime(long wall) { return DisplayTime.dateTime(host.activity(), settings, wall); }
    private String sampleDateTime(long wall) {
        String date = new SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "yMMMd"), Locale.getDefault()).format(new Date(wall));
        return date + " · " + clock(wall, false, true);
    }
    private String clock(long wall, boolean date, boolean seconds) {
        String time;
        if (seconds) {
            String skeleton = DisplayTime.is24Hour(host.activity(), settings) ? "Hms" : "hms";
            String pattern = android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton);
            time = new SimpleDateFormat(pattern, Locale.getDefault()).format(new Date(wall));
        } else time = DisplayTime.clock(host.activity(), settings, wall);
        return date ? new SimpleDateFormat("MMM d", Locale.getDefault()).format(new Date(wall)) + " " + time : time;
    }
    private boolean differentDay(long a, long b) {
        SimpleDateFormat day = new SimpleDateFormat("yyyy-DDD", Locale.ROOT); return !day.format(new Date(a)).equals(day.format(new Date(b)));
    }
    private static String minutes(double value) {
        return Math.abs(value) >= 1_000_000 ? Double.toString(value) + " min" : String.format(Locale.getDefault(), "%.2f min", value);
    }
    private static String source(ChargeSessionStore.Source source) {
        return source == ChargeSessionStore.Source.PERSONAL ? "Personal curve" : source == ChargeSessionStore.Source.STOCK_REFERENCE ? "Stock reference" : source == ChargeSessionStore.Source.CURRENT_RATE ? "Current rate" : "Unavailable";
    }

    private final class Panel extends LinearLayout {
        final ChargeSessionStore.HistorySession session;
        final boolean full;
        final LinearLayout controls, trace;
        final ScrollView controlsScroll, traceScroll;
        final SegmentedSelector modeSelector, targetSelector;
        final TextView note;
        final Chart chart;
        List<ChargeEstimateHistory.Point> points;
        boolean finishMode, toLimit;
        int selected;
        Panel(ChargeSessionStore.HistorySession session, boolean full, boolean finishMode, boolean toLimit) {
            super(host.activity()); this.session = session; this.full = full; this.finishMode = finishMode; this.toLimit = toLimit;
            setOrientation(VERTICAL); setBackground(ui.shape(ui.surface, full ? 0 : 20, 0));
            setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
            controls = column(); trace = column();
            modeSelector = new SegmentedSelector(ui, "Charge graph", new String[]{"Time remaining", "Predicted finish"}, choice -> { this.finishMode = choice == 1; update(false); });
            modeSelector.setTag(full ? "charge-history-fullscreen-mode" : "charge-history-mode"); modeSelector.setWrapChoices(true);
            controls.addView(modeSelector, new LinearLayout.LayoutParams(-1, -2)); selectors.add(modeSelector);
            boolean hasLimit = false;
            for (ChargeSessionStore.HistoryPoint point : session.points) if (point.estimate != null && point.estimate.targetPercent != null && point.estimate.targetPercent < 100) hasLimit = true;
            targetSelector = hasLimit ? new SegmentedSelector(ui, "Charge target", new String[]{"Full · 100%", "Saved limit"}, choice -> { this.toLimit = choice == 1; update(true); }) : null;
            if (targetSelector != null) {
                targetSelector.setTag(full ? "charge-history-fullscreen-target" : "charge-history-target"); targetSelector.setWrapChoices(true);
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2); tp.topMargin = ui.dp(8); controls.addView(targetSelector, tp); selectors.add(targetSelector);
            }
            LinearLayout legend = new LinearLayout(host.activity()); legend.setGravity(Gravity.CENTER_VERTICAL); legend.setPadding(0, ui.dp(8), 0, ui.dp(6));
            TextView labels = ui.text("━ Estimated   ┄ Observed", 12, false); labels.setTextColor(ui.muted); legend.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            if (!full) { View expand = iconButton("fit", "Expand charge graph", this::expand); expand.setTag("charge-history-expand"); legend.addView(expand); }
            controls.addView(legend);
            note = ui.caption(""); note.setTag(full ? "charge-history-fullscreen-note" : "charge-history-note"); note.setPadding(0, 0, 0, ui.dp(8)); controls.addView(note);
            chart = new Chart(this); chart.setTag(full ? "charge-history-fullscreen-chart" : "charge-history-chart");
            trace.setTag(full ? "charge-history-fullscreen-trace" : "charge-history-trace"); trace.setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8));
            controlsScroll = full ? scroller(controls) : null; traceScroll = full ? scroller(trace) : null;
            if (full) {
                addView(controlsScroll, new LinearLayout.LayoutParams(-1, ui.dp(120)));
                addView(chart, new LinearLayout.LayoutParams(-1, 0, 1));
                addView(traceScroll, new LinearLayout.LayoutParams(-1, ui.dp(120)));
            } else { addView(controls); addView(chart, new LinearLayout.LayoutParams(-1, ui.dp(280))); addView(trace); }
            update(true);
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            if (full && MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED) {
                int available = Math.max(0, MeasureSpec.getSize(heightSpec) - getPaddingTop() - getPaddingBottom());
                // Both text regions scroll independently. The plot retains the middle half of
                // even a short landscape window rather than clipping large-text controls.
                controlsScroll.getLayoutParams().height = Math.min(ui.dp(190), Math.round(available * .25f));
                traceScroll.getLayoutParams().height = Math.min(ui.dp(160), Math.round(available * .25f));
            }
            super.onMeasure(widthSpec, heightSpec);
        }
        void update(boolean rebuild) {
            if (rebuild) {
                points = ChargeEstimateHistory.build(session, toLimit); selected = Math.max(0, points.size() - 1);
            }
            modeSelector.select(finishMode ? 1 : 0, true); if (targetSelector != null) targetSelector.select(toLimit ? 1 : 0, true);
            boolean saved = false, estimates = false, actual = false;
            for (ChargeEstimateHistory.Point point : points) { saved |= point.estimate != null; estimates |= point.remainingMinutes != null; actual |= point.actualRemainingMinutes != null; }
            String text = !saved ? "Estimates weren't saved in this recording." : !estimates ? "No estimate was available in these samples." : "";
            String comparison = actual ? "Observed 100% · " + dateTime(session.observedCompleteWall)
                    : toLimit ? "Limit completion was not observed; no actual comparison." : "Finish not observed; no actual comparison.";
            note.setText(text.isEmpty() ? comparison : text + "\n" + comparison);
            chart.invalidate(); inspect(selected);
        }
        void inspect(int index) {
            trace.removeAllViews();
            if (points.isEmpty()) { trace.addView(ui.caption("No recorded samples.")); return; }
            selected = Math.max(0, Math.min(points.size() - 1, index)); ChargeEstimateHistory.Point point = points.get(selected);
            trace.addView(ui.caption("At " + sampleDateTime(point.wallMs)));
            String predicted = finishMode ? point.predictedFinishWallMs == null ? "—" : sampleDateTime(point.predictedFinishWallMs)
                    : point.remainingMinutes == null ? "—" : minutes(point.remainingMinutes);
            String actual = finishMode ? point.actualFinishWallMs == null ? "—" : sampleDateTime(point.actualFinishWallMs)
                    : point.actualRemainingMinutes == null ? "—" : minutes(point.actualRemainingMinutes);
            TextView estimateLine = ui.text("Estimated  " + predicted, 15, true); estimateLine.setTextColor(ui.accent); trace.addView(estimateLine);
            TextView actualLine = ui.text("Observed  " + actual, 14, false); actualLine.setTextColor(ui.blue); trace.addView(actualLine);
            if (point.remainingMinutes != null && point.actualRemainingMinutes != null) {
                double error = point.remainingMinutes - point.actualRemainingMinutes;
                trace.addView(ui.caption(String.format(Locale.getDefault(), "Error  %+.2f min", error)));
            }
            ChargeSessionStore.Estimate estimate = point.estimate;
            if (estimate != null) {
                String provenance = source(toLimit ? estimate.limitSource : estimate.fullSource) + " · Estimator " + estimate.version;
                if (estimate.learnedSessions > 0) provenance += " · " + estimate.learnedSessions + " learned charge" + (estimate.learnedSessions == 1 ? "" : "s");
                if (toLimit && estimate.targetPercent != null) provenance += String.format(Locale.getDefault(), " · %.0f%% target", estimate.targetPercent);
                trace.addView(ui.caption(provenance));
            } else trace.addView(ui.caption(point.actualGap ? "Connection gap" : "No saved estimate"));
            chart.setContentDescription("Charge graph. " + (finishMode ? "Predicted finish. " : "Time remaining. ") + "At " + sampleDateTime(point.wallMs) + ". Estimated " + predicted + ". Observed " + actual);
            chart.invalidate();
        }
        void expand() {
            if (!available() || expanded != null) return;
            LinearLayout expandedBody = column(); expandedBody.setBackgroundColor(ui.bg); expandedBody.setTag("charge-history-fullscreen");
            LinearLayout heading = new LinearLayout(host.activity()); heading.setGravity(Gravity.CENTER_VERTICAL); heading.setPadding(ui.dp(16), ui.dp(4), ui.dp(6), ui.dp(4));
            heading.addView(ui.text("Charge graph", 20, true), new LinearLayout.LayoutParams(0, -2, 1));
            Panel child = new Panel(session, true, finishMode, toLimit); child.inspect(selected);
            heading.addView(iconButton("close", "Close expanded charge graph", () -> { if (expanded != null) expanded.dismiss(); }));
            expandedBody.addView(heading); expandedBody.addView(child, new LinearLayout.LayoutParams(-1, 0, 1));
            expanded = page(expandedBody);
            expanded.setOnDismissListener(d -> {
                child.modeSelector.finishMotion(); selectors.remove(child.modeSelector);
                if (child.targetSelector != null) { child.targetSelector.finishMotion(); selectors.remove(child.targetSelector); }
                expanded = null;
                if (available()) { finishMode = child.finishMode; toLimit = child.toLimit; update(true); inspect(child.selected); }
            });
        }
    }

    /** A single shared scale makes prediction drift visible; missing samples never bridge a gap. */
    private final class Chart extends View {
        final Panel panel;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF plot = new RectF();
        final int slop;
        float downX, downY;
        boolean scrolling;
        double low, high, duration;
        ChartScale.Range range;
        static final int PREVIOUS_SAMPLE = 0x7e0c0001, NEXT_SAMPLE = 0x7e0c0002;
        Chart(Panel panel) { super(host.activity()); this.panel = panel; slop = ViewConfiguration.get(host.activity()).getScaledTouchSlop(); setFocusable(true); setClickable(true); }
        private Double value(ChargeEstimateHistory.Point point, boolean actual) {
            if (!panel.finishMode) return actual ? point.actualRemainingMinutes : point.remainingMinutes;
            Long wall = actual ? point.actualFinishWallMs : point.predictedFinishWallMs;
            return wall == null ? null : wall.doubleValue();
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            paint.setTypeface(ui.regular()); paint.setTextSize(Math.min(ui.sp(10), ui.dp(14))); paint.setStyle(Paint.Style.FILL);
            low = Double.POSITIVE_INFINITY; high = Double.NEGATIVE_INFINITY; duration = 1;
            for (ChargeEstimateHistory.Point point : panel.points) {
                duration = Math.max(duration, point.elapsedMinutes);
                for (boolean actual : new boolean[]{false, true}) { Double value = value(point, actual); if (value != null && Double.isFinite(value)) { low = Math.min(low, value); high = Math.max(high, value); } }
            }
            boolean available = Double.isFinite(low);
            if (!available) { low = 0; high = 1; }
            else if (panel.finishMode) { double padding = Math.max(30000, (high - low) * .08); low -= padding; high += padding; }
            // ChartScale keeps extreme finite imported values bounded without changing
            // the saved reading; its interpolation avoids overflow in ticks and geometry.
            range = panel.finishMode ? new ChartScale.Range(low, high)
                    : ChartScale.range("remaining_minutes", "min", true, low, Math.max(1, high));
            low = range.min; high = range.max;
            boolean dates = panel.finishMode && (differentDay((long)low, (long)high) || differentDay(panel.session.startedWall, (long)low));
            float axisWidth = 0;
            for (int i = 0; i <= 4; i++) axisWidth = Math.max(axisWidth, paint.measureText(axis(range.at(1 - i / 4d), dates)));
            plot.set(Math.min(getWidth() * .43f, axisWidth + ui.dp(10)), ui.dp(16), getWidth() - ui.dp(8), getHeight() - ui.dp(34));
            if (plot.width() <= 0 || plot.height() <= 0) return;
            paint.setTextAlign(Paint.Align.RIGHT);
            for (int i = 0; i <= 4; i++) {
                float y = plot.top + plot.height() * i / 4;
                paint.setColor(ui.outline); paint.setStrokeWidth(ui.dp(1)); canvas.drawLine(plot.left, y, plot.right, y, paint);
                paint.setColor(ui.muted); canvas.drawText(available ? axis(range.at(1 - i / 4d), dates) : "—", plot.left - ui.dp(6), y + ui.dp(4), paint);
            }
            paint.setColor(ui.muted);
            for (int i = 0; i <= 2; i++) {
                paint.setTextAlign(i == 0 ? Paint.Align.LEFT : i == 2 ? Paint.Align.RIGHT : Paint.Align.CENTER);
                canvas.drawText(elapsed(duration * i / 2), plot.left + plot.width() * i / 2, plot.bottom + ui.dp(23), paint);
            }
            if (!available) { paint.setTextAlign(Paint.Align.CENTER); canvas.drawText("No saved estimates", plot.centerX(), plot.centerY(), paint); return; }
            canvas.save(); canvas.clipRect(plot);
            drawSeries(canvas, true); drawSeries(canvas, false);
            if (!panel.points.isEmpty()) {
                ChargeEstimateHistory.Point selected = panel.points.get(panel.selected); float x = x(selected.elapsedMinutes);
                paint.setStyle(Paint.Style.STROKE); paint.setPathEffect(null); paint.setColor(ui.muted); paint.setStrokeWidth(ui.dp(1)); canvas.drawLine(x, plot.top, x, plot.bottom, paint);
                for (boolean actual : new boolean[]{true, false}) {
                    Double value = value(selected, actual); if (value == null) continue;
                    paint.setStyle(Paint.Style.FILL); paint.setColor(ui.surface); canvas.drawCircle(x, y(value), ui.dp(5), paint);
                    paint.setColor(actual ? ui.blue : ui.accent); canvas.drawCircle(x, y(value), ui.dp(3), paint);
                }
            }
            canvas.restore(); paint.setStyle(Paint.Style.FILL);
        }
        private String axis(double value, boolean date) {
            if (panel.finishMode) return clock((long)value, date, high - low < 4 * 60000);
            if (Math.abs(value) >= 1_000_000) return String.format(Locale.getDefault(), "%.1e min", value);
            return String.format(Locale.getDefault(), high - low < 10 ? "%.1f min" : "%.0f min", value);
        }
        private String elapsed(double minutes) {
            if (duration < 4) return String.format(Locale.getDefault(), "%.0f s", minutes * 60);
            return minutes >= 60 ? String.format(Locale.getDefault(), "%.1f h", minutes / 60) : String.format(Locale.getDefault(), "%.0f min", minutes);
        }
        private float x(double elapsed) { return plot.left + (float)(elapsed / duration) * plot.width(); }
        private float y(double value) { return plot.bottom - (float)range.fraction(value) * plot.height(); }
        private void drawSeries(Canvas canvas, boolean actual) {
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(ui.dp(2)); paint.setColor(actual ? ui.blue : ui.accent);
            paint.setPathEffect(actual ? new DashPathEffect(new float[]{ui.dp(6), ui.dp(4)}, 0) : null);
            Path path = new Path(); boolean started = false;
            for (ChargeEstimateHistory.Point point : panel.points) {
                Double value = value(point, actual);
                if (actual ? point.actualGap : point.gap) started = false;
                if (value == null || !Double.isFinite(value)) { started = false; continue; }
                float x = x(point.elapsedMinutes), y = y(value);
                if (started) path.lineTo(x, y); else { path.moveTo(x, y); canvas.drawPoint(x, y, paint); }
                started = true;
            }
            canvas.drawPath(path, paint); paint.setPathEffect(null); paint.setStyle(Paint.Style.FILL);
        }
        private void inspect(float x) {
            if (panel.points.isEmpty() || plot.width() <= 0) return;
            double target = Math.max(0, Math.min(1, (x - plot.left) / plot.width())) * duration;
            int nearest = 0; double delta = Double.POSITIVE_INFINITY;
            for (int i = 0; i < panel.points.size(); i++) { double distance = Math.abs(panel.points.get(i).elapsedMinutes - target); if (distance < delta) { delta = distance; nearest = i; } }
            panel.inspect(nearest);
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX(); downY = event.getY(); scrolling = false;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    inspect(event.getX()); return true;
                case MotionEvent.ACTION_MOVE:
                    if (!scrolling && Math.abs(event.getY() - downY) > slop && Math.abs(event.getY() - downY) > Math.abs(event.getX() - downX)) {
                        scrolling = true; if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    if (!scrolling) inspect(event.getX()); return true;
                case MotionEvent.ACTION_UP:
                    if (!scrolling) { inspect(event.getX()); performClick(); }
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false); return true;
                case MotionEvent.ACTION_CANCEL:
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false); return true;
                default: return true;
            }
        }
        @Override public boolean performClick() { super.performClick(); return true; }
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(PREVIOUS_SAMPLE, "Previous recorded sample"));
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(NEXT_SAMPLE, "Next recorded sample"));
        }
        @Override public boolean performAccessibilityAction(int action, android.os.Bundle arguments) {
            if (action == PREVIOUS_SAMPLE || action == NEXT_SAMPLE) { panel.inspect(panel.selected + (action == NEXT_SAMPLE ? 1 : -1)); return true; }
            return super.performAccessibilityAction(action, arguments);
        }
    }
}
