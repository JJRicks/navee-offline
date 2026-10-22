package local.navee.offlinelock;

import android.app.Dialog;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.SimpleDateFormat;
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
        body.removeAllViews(); body.addView(header("Charge recordings", false));
        LinearLayout list = padded(); list.setTag("charge-history-list");
        if (recordings.isEmpty()) list.addView(ui.caption("No saved charge recordings yet."));
        for (ChargeSessionStore.HistorySession session : recordings) {
            LinearLayout row = column(); row.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14));
            row.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 45)),
                    ui.shape(ui.surface, 18, 0), null));
            row.addView(ui.text(dateTime(session.startedWall), 16, true));
            TextView details = ui.caption(charger(session.charger) + " · " + batterySpan(session));
            details.setPadding(0, ui.dp(5), 0, 0); row.addView(details);
            TextView state = ui.caption((session.observedCompleteMono == null ? "Finish not observed" : "100% observed") + paused(session));
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
        body.removeAllViews(); body.addView(header("Charge recording", true));
        LinearLayout contents = padded(); contents.setTag("charge-history-detail");
        contents.addView(ui.text(dateTime(session.startedWall), 20, true));
        TextView sub = ui.caption(charger(session.charger) + " · " + batterySpan(session));
        sub.setPadding(0, ui.dp(6), 0, ui.dp(12)); contents.addView(sub);
        Panel panel = new Panel(session, false);
        contents.addView(panel, new LinearLayout.LayoutParams(-1, -2));
        body.addView(scroller(contents), new LinearLayout.LayoutParams(-1, 0, 1));
    }
    /** A Volt charging schedule held current off; the chart shows it as a gap. */
    private static String paused(ChargeSessionStore.HistorySession session) {
        if (session.pauses == 0) return "";
        long minutes = Math.round(session.pausedMs / 60000d);
        return " · paused " + (minutes >= 60 ? minutes / 60 + " h " + minutes % 60 + " min" : minutes + " min") + " for the schedule";
    }
    private String batterySpan(ChargeSessionStore.HistorySession session) {
        Double first = null, last = null;
        for (ChargeSessionStore.HistoryPoint point : session.points) if (Double.isFinite(point.batteryPercent)) {
            if (first == null) first = point.batteryPercent; last = point.batteryPercent;
        }
        return first == null ? "No battery samples" : String.format(Locale.getDefault(), "%.0f%% → %.0f%%", first, last);
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
    private static String source(ChargeSessionStore.Source source) {
        return source == ChargeSessionStore.Source.PERSONAL ? "Personal curve" : source == ChargeSessionStore.Source.STOCK_REFERENCE ? "Stock reference" : source == ChargeSessionStore.Source.FAST_REFERENCE ? "High-speed reference" : source == ChargeSessionStore.Source.CURRENT_RATE ? "Current rate" : "Unavailable";
    }

    private final class Panel extends LinearLayout {
        final ChargeSessionStore.HistorySession session;
        final boolean full;
        final List<RideChartData.Series> data;
        final List<ChargeEstimateHistory.Point> history;
        final java.util.Set<String> chosen;
        final Flow choices;
        final LinearLayout controls, trace;
        final ScrollView controlsScroll, traceScroll;
        final RideChartView chart;
        final TextView note;
        String inspectionSignature = "";

        Panel(ChargeSessionStore.HistorySession session, boolean full) {
            super(host.activity()); this.session = session; this.full = full;
            data = ChargeChartData.build(session, "f".equals(settings.getString("temperature_unit", "c")));
            history = ChargeEstimateHistory.build(session, false);
            chosen = new java.util.LinkedHashSet<>(settings.getStringSet("charge_chart_series", ChargeChartData.defaults()));
            setTag(tag("panel")); setOrientation(VERTICAL);
            if (full) setMinimumHeight(ui.dp(520));
            setBackground(ui.shape(ui.surface, full ? 0 : 20, 0));
            setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
            controls = column();
            if (!full) {
                LinearLayout heading = new LinearLayout(host.activity()); heading.setGravity(Gravity.CENTER_VERTICAL);
                heading.addView(ui.text("Charge readings", 18, true), new LinearLayout.LayoutParams(0, -2, 1));
                View expand = iconButton("fit", "Expand charge graph", this::expand);
                expand.setTag("charge-history-expand"); heading.addView(expand); controls.addView(heading);
            }
            choices = new Flow(); choices.setTag(tag("choices")); choices.setPadding(0, ui.dp(8), 0, ui.dp(10)); controls.addView(choices);
            note = ui.caption(comparisonNote()); note.setTag(tag("note")); note.setPadding(0, 0, 0, ui.dp(8)); controls.addView(note);
            controlsScroll = full ? scroller(controls) : null;
            if (full) addView(controlsScroll, new LinearLayout.LayoutParams(-1, ui.dp(112))); else addView(controls);
            chart = new RideChartView(ui); chart.setTag(tag("chart"));
            chart.setChartTitle("Charge chart", "Fit whole charge");
            chart.setValueFormatter(new RideChartView.ValueFormatter() {
                @Override public String axis(RideChartData.Series series, double value) {
                    if ("Time".equals(series.unit)) return clock(Math.round(value), crossesDay(series), series.max - series.min < 240_000);
                    double step = series.max / 4 - series.min / 4;
                    int places = step > 0 ? Math.max(0, Math.min(6, (int)Math.ceil(-Math.log10(step)))) : 0;
                    double number = Math.abs(value) < Math.pow(10, -places) / 2 ? 0 : value;
                    return Math.abs(number) >= 1_000_000 ? String.format(Locale.getDefault(), "%.1e", number)
                            : String.format(Locale.getDefault(), "%." + places + "f", number);
                }
                @Override public String sample(RideChartData.Series series, RideChartData.Value value) {
                    if (value == null || value.value == null || !Double.isFinite(value.value)) return "—";
                    if ("Time".equals(series.unit)) return sampleDateTime(Math.round(value.value));
                    if (Math.abs(value.value) >= 1_000_000 || (value.value != 0 && Math.abs(value.value) < .005)) return Double.toString(value.value) + " " + series.unit;
                    return RideChartView.sampleValue(series, value);
                }
            });
            android.widget.HorizontalScrollView graph = new android.widget.HorizontalScrollView(host.activity());
            graph.setFillViewport(true); graph.addView(chart, new android.widget.HorizontalScrollView.LayoutParams(-1, full ? -1 : ui.dp(280)));
            addView(graph, new LinearLayout.LayoutParams(-1, full ? 0 : ui.dp(280), full ? 1 : 0));
            trace = new LinearLayout(host.activity()); trace.setOrientation(HORIZONTAL); trace.setGravity(Gravity.TOP);
            trace.setTag(tag("trace")); trace.setMinimumHeight(Math.max(ui.dp(86), (int)ui.sp(70) + ui.dp(12)));
            android.widget.HorizontalScrollView readings = new android.widget.HorizontalScrollView(host.activity());
            readings.setHorizontalScrollBarEnabled(false); readings.setHorizontalFadingEdgeEnabled(true); readings.setFadingEdgeLength(ui.dp(16));
            readings.addView(trace, new android.widget.HorizontalScrollView.LayoutParams(-2, -2));
            traceScroll = full ? scroller(readings) : null;
            if (full) addView(traceScroll, new LinearLayout.LayoutParams(-1, ui.dp(104))); else addView(readings);
            chart.setInspectionListener(this::inspect);
            LinearLayout zoom = new LinearLayout(host.activity()); zoom.setGravity(Gravity.CENTER); zoom.setPadding(0, ui.dp(6), 0, 0);
            TextView[] buttons = {ui.secondaryButton("−", () -> chart.zoomBy(.5)), ui.secondaryButton("Fit", chart::reset), ui.secondaryButton("+", () -> chart.zoomBy(2))};
            String[] ids = {"zoom-out", "fit", "zoom-in"}, descriptions = {"Zoom out charge chart", "Fit whole charge", "Zoom in charge chart"};
            for (int i = 0; i < buttons.length; i++) {
                buttons[i].setTag(tag(ids[i])); buttons[i].setContentDescription(descriptions[i]);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ui.dp(48), 1); p.setMargins(ui.dp(3), 0, ui.dp(3), 0); zoom.addView(buttons[i], p);
            }
            addView(zoom); update();
        }
        String tag(String suffix) { return (full ? "charge-history-fullscreen-" : "charge-history-") + suffix; }
        String comparisonNote() {
            boolean saved = false;
            for (ChargeSessionStore.HistoryPoint point : session.points) saved |= point.estimate != null;
            String comparison = session.observedCompleteMono == null ? "Finish not observed; no actual comparison."
                    : "Observed 100% · " + dateTime(session.observedCompleteWall);
            return (saved ? "" : "Estimates weren't saved in this recording.\n") + comparison;
        }
        @Override protected void onMeasure(int width, int height) {
            if (full) {
                if (MeasureSpec.getMode(height) == MeasureSpec.UNSPECIFIED || MeasureSpec.getSize(height) < getSuggestedMinimumHeight())
                    height = MeasureSpec.makeMeasureSpec(getSuggestedMinimumHeight(), MeasureSpec.EXACTLY);
                int available = Math.max(0, MeasureSpec.getSize(height) - getPaddingTop() - getPaddingBottom() - ui.dp(54));
                // Controls and raw readings remain scrollable even in short landscape windows.
                // Half the available height is always reserved for the plot.
                controlsScroll.getLayoutParams().height = Math.min(ui.dp(150), Math.round(available * .25f));
                traceScroll.getLayoutParams().height = Math.min(ui.dp(120), Math.round(available * .25f));
            }
            super.onMeasure(width, height);
        }
        void update() {
            choices.removeAllViews();
            for (RideChartData.Series series : data) {
                android.widget.CheckBox toggle = new android.widget.CheckBox(host.activity());
                toggle.setTag((full ? "charge-fullscreen-series:" : "charge-series:") + series.key);
                toggle.setButtonDrawable(null); toggle.setMinHeight(ui.dp(48));
                toggle.setText(pattern(series) + series.label + (series.available() ? "" : " · no data"));
                toggle.setTextSize(12 * ui.textScale); toggle.setTypeface(ui.semibold()); toggle.setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8));
                toggle.setChecked(chosen.contains(series.key));
                toggle.setContentDescription(series.label + (series.available() ? "" : ", no samples recorded"));
                Runnable style = () -> { toggle.setTextColor(toggle.isChecked() ? ui.text : ui.muted); toggle.setBackground(ui.shape(toggle.isChecked() ? Ui.withAlpha(series.color, 50) : ui.raised, 12, toggle.isChecked() ? series.color : ui.outline)); };
                style.run(); toggle.setOnCheckedChangeListener((button, checked) -> {
                    if (checked) chosen.add(series.key); else chosen.remove(series.key);
                    settings.edit().putStringSet("charge_chart_series", new java.util.LinkedHashSet<>(chosen)).apply(); style.run(); plot();
                }); choices.addView(toggle);
            }
            plot();
        }
        void plot() {
            for (RideChartData.Series series : data) chart.setAxisWidthDp(series.key,
                    "Time".equals(series.unit) ? crossesDay(series) || series.max - series.min < 240_000 ? 96 : 84 : 48);
            chart.set(data, chosen);
        }
        boolean crossesDay(RideChartData.Series series) {
            SimpleDateFormat day = new SimpleDateFormat("yyyy-DDD", Locale.ROOT);
            return !day.format(new Date(Math.round(series.min))).equals(day.format(new Date(Math.round(series.max))));
        }
        String pattern(RideChartData.Series series) { return series.index % 3 == 0 ? "━ " : series.index % 3 == 1 ? "┄ " : "⋯ "; }
        void inspect(double time, List<RideChartData.Series> selected) {
            StringBuilder signature = new StringBuilder();
            for (RideChartData.Series series : selected) {
                RideChartData.Value value = series.sampleAt(time);
                signature.append(series.key).append(':').append(value == null ? "gap" : value.time + ":" + value.value).append('|');
            }
            if (signature.toString().equals(inspectionSignature)) return;
            inspectionSignature = signature.toString(); trace.removeAllViews();
            for (RideChartData.Series series : selected) {
                RideChartData.Value value = series.sampleAt(time);
                LinearLayout cell = column(); cell.setMinimumWidth(ui.dp(124)); cell.setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6));
                TextView label = ui.text(pattern(series) + series.label, 12, false); label.setSingleLine(true); cell.addView(label);
                TextView reading = ui.text(chart.formatSample(series, value), 17, true); reading.setSingleLine(true); cell.addView(reading);
                TextView stamp = ui.caption(value == null || value.value == null ? "—" : "At " + RideChartView.sampleTime(value.time)); stamp.setSingleLine(true); cell.addView(stamp);
                if (value != null && (series.key.startsWith("estimated_") || series.key.startsWith("limit_"))) {
                    ChargeSessionStore.Estimate estimate = estimateAt(value.time);
                    if (estimate != null) {
                        boolean limit = series.key.startsWith("limit_");
                        String detail = source(limit ? estimate.limitSource : estimate.fullSource) + " · Estimator " + estimate.version;
                        if (estimate.learnedSessions > 0) detail += " · " + estimate.learnedSessions + " learned charges";
                        if (limit && estimate.targetPercent != null) detail += String.format(Locale.getDefault(), " · %.0f%% target", estimate.targetPercent);
                        TextView provenance = ui.caption(detail); provenance.setSingleLine(true); cell.addView(provenance);
                    }
                }
                cell.setContentDescription(series.label + ": " + reading.getText() + ", " + stamp.getText()); trace.addView(cell);
            }
        }
        ChargeSessionStore.Estimate estimateAt(long time) {
            int low = 0, high = history.size() - 1;
            while (low <= high) {
                int mid = (low + high) >>> 1; ChargeEstimateHistory.Point point = history.get(mid);
                long stamp = Math.round(point.elapsedMinutes * 60_000d);
                if (stamp < time) low = mid + 1; else if (stamp > time) high = mid - 1; else return point.estimate;
            }
            return null;
        }
        void expand() {
            if (!available() || expanded != null) return;
            LinearLayout expandedBody = column(); expandedBody.setBackgroundColor(ui.bg); expandedBody.setTag("charge-history-fullscreen");
            LinearLayout heading = new LinearLayout(host.activity()); heading.setGravity(Gravity.CENTER_VERTICAL); heading.setPadding(ui.dp(16), ui.dp(4), ui.dp(6), ui.dp(4));
            heading.addView(ui.text("Charge readings", 20, true), new LinearLayout.LayoutParams(0, -2, 1));
            Panel child = new Panel(session, true); child.chart.restoreState(chart.getState());
            heading.addView(iconButton("close", "Close expanded charge graph", () -> { if (expanded != null) expanded.dismiss(); }));
            ScrollView viewport = scroller(child); viewport.setFillViewport(true);
            expandedBody.addView(heading); expandedBody.addView(viewport, new LinearLayout.LayoutParams(-1, 0, 1));
            expanded = page(expandedBody);
            expanded.setOnDismissListener(d -> {
                expanded = null;
                if (available() && isAttachedToWindow()) {
                    chosen.clear(); chosen.addAll(child.chosen); update(); chart.restoreState(child.chart.getState());
                }
            });
        }
    }

    /** Same wrapping category layout as ride recordings, including RTL order. */
    private final class Flow extends android.view.ViewGroup {
        Flow() { super(host.activity()); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec), available = Math.max(1, width - getPaddingLeft() - getPaddingRight());
            int x = 0, y = getPaddingTop(), rowHeight = 0, gap = ui.dp(6);
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                child.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                if (x > 0 && x + child.getMeasuredWidth() > available) { y += rowHeight + gap; x = 0; rowHeight = 0; }
                x += child.getMeasuredWidth() + gap; rowHeight = Math.max(rowHeight, child.getMeasuredHeight());
            }
            setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(y + rowHeight + getPaddingBottom(), heightSpec));
        }
        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int available = getWidth() - getPaddingLeft() - getPaddingRight(), x = 0, y = getPaddingTop(), rowHeight = 0, gap = ui.dp(6);
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i); int w = child.getMeasuredWidth(), h = child.getMeasuredHeight();
                if (x > 0 && x + w > available) { y += rowHeight + gap; x = 0; rowHeight = 0; }
                int left = rtl ? getWidth() - getPaddingRight() - x - w : getPaddingLeft() + x;
                child.layout(left, y, left + w, y + h); x += w + gap; rowHeight = Math.max(rowHeight, h);
            }
        }
    }
}
