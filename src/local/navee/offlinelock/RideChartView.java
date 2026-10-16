package local.navee.offlinelock;

import android.graphics.*;
import android.view.*;
import java.util.*;

/** Independent colored axes, raw-sample inspection, pinch zoom and timeline pan. */
public final class RideChartView extends View {
    public interface InspectionListener { void changed(double time, List<RideChartData.Series> series); }
    public interface ValueFormatter {
        String axis(RideChartData.Series series, double value);
        String sample(RideChartData.Series series, RideChartData.Value value);
    }
    /** View-only state: recorded values and the selected categories belong to the caller. */
    public static final class State {
        public final double from, to, inspectedTime;
        public final float axisScroll;
        public State(double from, double to, double inspectedTime, float axisScroll) {
            this.from = from; this.to = to; this.inspectedTime = inspectedTime; this.axisScroll = axisScroll;
        }
    }
    private static final ValueFormatter DEFAULT_FORMATTER = new ValueFormatter() {
        @Override public String axis(RideChartData.Series series, double value) { return axisNumber(series, value); }
        @Override public String sample(RideChartData.Series series, RideChartData.Value value) { return sampleValue(series, value); }
    };
    private final Ui ui; private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<RideChartData.Series> series = Collections.emptyList();
    private double duration = 1, from = 0, to = 1, inspectedTime = Double.NaN;
    private float lastX, downX, downY, axisScroll, axesWidth, multiX;
    private float axisWidthDp = 48;
    private final Map<String, Float> seriesAxisWidths = new HashMap<>();
    private boolean draggingAxes, panningTime, horizontalGesture, scrollingPage, suppressSingle;
    private final int touchSlop; private InspectionListener inspectionListener;
    private ValueFormatter valueFormatter = DEFAULT_FORMATTER;
    private String chartTitle = "Ride chart", fitActionLabel = "Fit whole ride";
    private final ScaleGestureDetector scale; private final GestureDetector gestures;
    private final RectF plot = new RectF();
    public RideChartView(Ui ui) {
        super(ui.context); this.ui = ui; setFocusable(true); setClickable(true);
        touchSlop = ViewConfiguration.get(ui.context).getScaledTouchSlop();
        scale = new ScaleGestureDetector(ui.context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) { zoom(detector.getScaleFactor(), detector.getFocusX()); return true; }
        });
        gestures = new GestureDetector(ui.context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onDoubleTap(MotionEvent e) { suppressSingle = true; reset(); return true; }
        });
        setContentDescription(chartTitle);
    }
    // Custom accessibility action ids outside both the framework (0x01…) and app (0x7f…) resource ranges.
    private static final int ACTION_ZOOM_IN = 0x7e0a0001, ACTION_ZOOM_OUT = 0x7e0a0002, ACTION_FIT = 0x7e0a0003;
    private static final int ACTION_PREVIOUS_SAMPLE = 0x7e0a0004, ACTION_NEXT_SAMPLE = 0x7e0a0005;
    @Override public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(ACTION_ZOOM_IN, "Zoom in"));
        info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(ACTION_ZOOM_OUT, "Zoom out"));
        info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(ACTION_FIT, fitActionLabel));
        if (Double.isFinite(adjacentSampleTime(false)))
            info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(ACTION_PREVIOUS_SAMPLE, "Previous recorded sample"));
        if (Double.isFinite(adjacentSampleTime(true)))
            info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(ACTION_NEXT_SAMPLE, "Next recorded sample"));
    }
    @Override public boolean performAccessibilityAction(int action, android.os.Bundle arguments) {
        if (action == ACTION_ZOOM_IN) { zoomBy(2); return true; }
        if (action == ACTION_ZOOM_OUT) { zoomBy(.5); return true; }
        if (action == ACTION_FIT) { reset(); return true; }
        if (action == ACTION_PREVIOUS_SAMPLE) return stepSample(false);
        if (action == ACTION_NEXT_SAMPLE) return stepSample(true);
        return super.performAccessibilityAction(action, arguments);
    }
    public void setInspectionListener(InspectionListener listener) { inspectionListener = listener; notifyInspection(); }
    public void setValueFormatter(ValueFormatter formatter) {
        valueFormatter = formatter == null ? DEFAULT_FORMATTER : formatter;
        notifyInspection(); invalidate();
    }
    public String formatSample(RideChartData.Series series, RideChartData.Value value) {
        return valueFormatter.sample(series, value);
    }
    /** Wider columns allow formatted clock labels while the default ride axes stay compact. */
    public void setAxisWidthDp(float width) {
        if (!Float.isFinite(width) || width <= 0) return;
        float next = Math.max(24, Math.min(144, width));
        if (next == axisWidthDp) return;
        float position = axisColumnPosition(); axisWidthDp = next;
        restoreAxisColumnPosition(position); invalidate();
    }
    /** Clock axes can be wider without adding empty space around numerical axes. */
    public void setAxisWidthDp(String key, float width) {
        if (key == null || !Float.isFinite(width) || width <= 0) return;
        float next = Math.max(24, Math.min(144, width));
        Float previous = seriesAxisWidths.get(key);
        if (previous != null && previous == next) return;
        float position = axisColumnPosition(); seriesAxisWidths.put(key, next);
        restoreAxisColumnPosition(position); invalidate();
    }
    private float axisWidth(RideChartData.Series selected) {
        Float width = seriesAxisWidths.get(selected.key);
        return ui.dp(width == null ? axisWidthDp : width);
    }
    private float totalAxisWidth() {
        float total = 0;
        for (RideChartData.Series selected : series) total += axisWidth(selected);
        return total;
    }
    private float axisColumnPosition() {
        float offset = 0;
        for (int i = 0; i < series.size(); i++) {
            float width = axisWidth(series.get(i));
            if (axisScroll <= offset + width) return i + (axisScroll - offset) / width;
            offset += width;
        }
        return series.size();
    }
    private void restoreAxisColumnPosition(float position) {
        float offset = 0;
        for (int i = 0; i < series.size() && i < position; i++)
            offset += axisWidth(series.get(i)) * Math.min(1, position - i);
        axisScroll = offset;
    }
    private void snapAxisScroll() {
        float limit = Math.max(0, totalAxisWidth() - axesWidth);
        float closest = 0, distance = Math.abs(axisScroll), offset = 0;
        for (RideChartData.Series selected : series) {
            offset += axisWidth(selected);
            float candidate = Math.min(limit, offset), delta = Math.abs(candidate - axisScroll);
            if (delta < distance) { closest = candidate; distance = delta; }
        }
        axisScroll = closest;
    }
    public void setChartTitle(String title, String fitActionLabel) {
        chartTitle = title == null || title.trim().isEmpty() ? "Recording chart" : title;
        this.fitActionLabel = fitActionLabel == null ? "Fit whole recording" : fitActionLabel;
        updateAccessibilityDescription();
    }
    public State getState() { return new State(from, to, inspectedTime, axisScroll); }
    /** Apply after set(...), allowing the same position and selection in an expanded view. */
    public void restoreState(State state) {
        if (state == null) return;
        if (Double.isFinite(state.from) && Double.isFinite(state.to) && state.to > state.from) {
            from = state.from; to = state.to; clamp();
        }
        inspectedTime = Double.isFinite(state.inspectedTime)
                ? Math.max(0, Math.min(duration, state.inspectedTime)) : Double.NaN;
        axisScroll = Float.isFinite(state.axisScroll) ? Math.max(0, state.axisScroll) : 0;
        notifyInspection(); invalidate();
    }
    public void set(List<RideChartData.Series> all, Set<String> selected) {
        series = new ArrayList<>(); double end = 1;
        for (RideChartData.Series s : all) { end = Math.max(end, s.endTime); if (selected.contains(s.key)) series.add(s); }
        boolean reset = duration <= 1; duration = end; if (reset) { from = 0; to = duration; } else clamp();
        // Start the cursor on the last recorded sample so the readout always shows values.
        if (!Double.isFinite(inspectedTime)) inspectedTime = lastSampleTime();
        else inspectedTime = Math.min(inspectedTime, duration);
        hatches.clear();
        if (selected.contains("wh_per_distance")) for (RideChartData.Series s : all)
            if ("speed_kmh".equals(s.key)) hatches.add(RideChartData.lowSpeedSpans(s));
        setMinimumWidth(ui.dp(220)); notifyInspection(); requestLayout(); invalidate();
    }
    /** The latest time every selected series has a real reading at or after; NaN when none has data. */
    private double lastSampleTime() {
        double result = Double.NaN;
        for (RideChartData.Series s : series) for (int i = s.values.size() - 1; i >= 0; i--) {
            RideChartData.Value value = s.values.get(i);
            if (value.value != null && Double.isFinite(value.value)) { result = Double.isFinite(result) ? Math.min(result, value.time) : value.time; break; }
        }
        return result;
    }
    private final List<double[]> hatches = new ArrayList<>();
    public void reset() { from = 0; to = duration; invalidate(); }
    public void zoomBy(double amount) { zoom(amount, plot.centerX()); }
    private void zoom(double amount, float focus) {
        if (amount <= 0 || !Double.isFinite(amount)) return;
        double fraction = Math.max(0, Math.min(1, (focus - plot.left) / Math.max(1, plot.width())));
        double anchor = from + fraction * (to - from), range = Math.max(Math.min(duration, 1000), Math.min(duration, (to - from) / amount));
        from = anchor - range * fraction; to = from + range; clamp(); invalidate();
    }
    private void clamp() { double range = Math.min(duration, Math.max(1, to - from)); from = Math.max(0, Math.min(from, duration - range)); to = from + range; }
    private void inspect(float x) {
        inspectedTime = from + Math.max(0, Math.min(1, (x - plot.left) / Math.max(1, plot.width()))) * (to - from);
        notifyInspection(); invalidate();
    }
    private void notifyInspection() {
        updateAccessibilityDescription();
        if (inspectionListener != null) inspectionListener.changed(inspectedTime, Collections.unmodifiableList(series));
    }
    private void updateAccessibilityDescription() {
        StringBuilder description = new StringBuilder(chartTitle);
        if (series.isEmpty()) description.append(". No readings selected");
        else if (!Double.isFinite(inspectedTime)) description.append(". No recorded samples");
        else {
            description.append(". At ").append(sampleTime((long) inspectedTime));
            for (RideChartData.Series selected : series) {
                RideChartData.Value value = selected.sampleAt(inspectedTime);
                description.append(". ").append(selected.label).append(": ");
                if (value == null || value.value == null || !Double.isFinite(value.value)) description.append("No reading");
                else {
                    description.append(valueFormatter.sample(selected, value));
                    if (value.time != inspectedTime) description.append(" at ").append(sampleTime(value.time));
                }
            }
        }
        setContentDescription(description.toString());
    }
    /** Step across the union of selected series' actual samples. Missing values and
     * duplicate timestamps cannot trap the cursor, and gaps never become interpolation. */
    private double adjacentSampleTime(boolean forward) {
        double anchor = Double.isFinite(inspectedTime) ? inspectedTime
                : forward ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        double result = Double.NaN;
        for (RideChartData.Series selected : series) {
            int size = selected.values.size();
            int[] neighbors = selected.visibleRange(anchor, anchor);
            // Ordered sources yield a bounded binary-search neighborhood. Unordered old
            // journals return the full range and need a scan to choose the nearest time.
            boolean orderedNeighborhood = neighbors[0] > 0 || neighbors[1] < size;
            int start = forward ? neighbors[0] : neighbors[1] - 1;
            for (int i = start; forward ? i < size : i >= 0; i += forward ? 1 : -1) {
                RideChartData.Value value = selected.values.get(i);
                if (value.time < 0 || value.value == null || !Double.isFinite(value.value)
                        || (forward ? value.time <= anchor : value.time >= anchor)) continue;
                if (!Double.isFinite(result) || (forward ? value.time < result : value.time > result)) result = value.time;
                if (orderedNeighborhood) break;
            }
        }
        return result;
    }
    private boolean stepSample(boolean forward) {
        double target = adjacentSampleTime(forward);
        if (!Double.isFinite(target)) return false;
        inspectedTime = target;
        if (target < from || target > to) {
            double range = to - from;
            from = target - range / 2; to = from + range; clamp();
        }
        notifyInspection(); invalidate();
        sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SELECTED);
        return true;
    }
    private void pan(float x, float previous) { double delta = (previous - x) / Math.max(1, plot.width()) * (to - from); from += delta; to += delta; clamp(); invalidate(); }
    private void intercept(boolean disallow) { if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(disallow); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float totalWidth = 0, widest = 0, completeWidth = 0;
        float budget = Math.min(ui.dp(144), getWidth() * .34f);
        for (RideChartData.Series selected : series) {
            float width = axisWidth(selected);
            totalWidth += width; widest = Math.max(widest, width);
            if (totalWidth <= budget) completeWidth = totalWidth;
        }
        // Prefer whole columns, but always fit the widest axis when scrolled into view.
        axesWidth = Math.min(totalWidth, Math.max(widest, completeWidth));
        axisScroll = Math.max(0, Math.min(axisScroll, totalWidth - axesWidth));
        plot.set(ui.dp(8) + axesWidth, ui.dp(32), getWidth() - ui.dp(12), getHeight() - ui.dp(40));
        paint.setTypeface(ui.regular()); paint.setTextSize(Math.min(ui.sp(10), ui.dp(14)));
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(ui.dp(1)); paint.setColor(ui.outline);
        for (int j = 0; j < 5; j++) { float y = plot.top + plot.height() * j / 4; canvas.drawLine(plot.left, y, plot.right, y, paint); }
        paint.setStyle(Paint.Style.FILL); paint.setTextAlign(Paint.Align.CENTER); paint.setColor(ui.muted);
        int ticks = Math.max(2, Math.min(7, (int)(plot.width() / ui.dp(60))));
        String previousTick = ""; float previousRight = Float.NEGATIVE_INFINITY;
        for (int i = 0; i <= ticks; i++) {
            String label = tickTime(from + (to - from) * i / ticks, (to - from) / ticks);
            if (label.equals(previousTick)) continue;
            float x = plot.left + plot.width() * i / ticks, width = paint.measureText(label);
            float left = i == 0 ? x : i == ticks ? x - width : x - width / 2;
            if (left < previousRight + ui.dp(4)) continue;
            previousTick = label; previousRight = left + width;
            paint.setTextAlign(i == 0 ? Paint.Align.LEFT : i == ticks ? Paint.Align.RIGHT : Paint.Align.CENTER);
            canvas.drawText(label, x, plot.bottom + ui.dp(24), paint);
        }
        paint.setTextAlign(Paint.Align.CENTER);
        if (series.isEmpty()) { canvas.drawText("Choose readings above", plot.centerX(), plot.centerY(), paint); return; }
        drawHatches(canvas);
        float axisEnd = 0, axisTextSize = paint.getTextSize();
        for (int i = 0; i < series.size(); i++) {
            RideChartData.Series s = series.get(i);
            float width = axisWidth(s); axisEnd += width;
            float x = ui.dp(8) + axisEnd - ui.dp(6) - axisScroll;
            int color = ui.dark ? s.color : darken(s.color); paint.setColor(color); paint.setTextAlign(Paint.Align.RIGHT);
            paint.setTextSize(axisTextSize);
            String[] labels = new String[5]; float textWidth = paint.measureText(s.unit);
            for (int tick = 0; tick < labels.length; tick++) {
                labels[tick] = s.available() ? valueFormatter.axis(s, s.scale.at(1 - tick / 4d)) : "—";
                textWidth = Math.max(textWidth, paint.measureText(labels[tick]));
            }
            // Shrink only this axis when its longest formatted clock/date needs more room.
            float labelRoom = Math.max(1, width - ui.dp(12));
            if (textWidth > labelRoom) paint.setTextSize(axisTextSize * labelRoom / textWidth);
            canvas.save(); canvas.clipRect(ui.dp(8), 0, plot.left - ui.dp(4), getHeight());
            canvas.drawText(s.unit, x, plot.top - ui.dp(12), paint);
            for (int tick = 0; tick < labels.length; tick++) canvas.drawText(labels[tick], x, plot.top + plot.height() * tick / 4 + ui.dp(4), paint);
            paint.setStrokeWidth(ui.dp(1)); canvas.drawLine(x + ui.dp(3), plot.top, x + ui.dp(3), plot.bottom, paint);
            canvas.restore(); paint.setTextSize(axisTextSize);
            canvas.save(); canvas.clipRect(plot); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(ui.dp(2));
            paint.setPathEffect(s.index % 3 == 0 ? null : new DashPathEffect(new float[]{ui.dp(s.index % 3 == 1 ? 7 : 2), ui.dp(3)}, 0));
            Path path = new Path();
            int[] visible = s.drawIndices(from, to, (int)Math.ceil(plot.width()));
            for (int position = 0; position < visible.length; position++) {
                int encoded = visible[position];
                boolean start = encoded < 0; RideChartData.Value p = s.values.get(start ? -encoded - 1 : encoded);
                float px = plot.left + (float)((p.time - from) / (to - from)) * plot.width();
                float py = plot.bottom - (float)(s.scale.fraction(p.value)) * plot.height();
                if (start) {
                    path.moveTo(px, py);
                    if (position + 1 == visible.length || visible[position + 1] < 0) canvas.drawPoint(px, py, paint);
                } else path.lineTo(px, py);
            }
            canvas.drawPath(path, paint); paint.setPathEffect(null); paint.setStyle(Paint.Style.FILL); canvas.restore();
        }
        drawScaleFades(canvas);
        if (Double.isFinite(inspectedTime) && inspectedTime >= from && inspectedTime <= to) {
            float cursor = plot.left + (float)((inspectedTime - from) / (to - from)) * plot.width();
            paint.setColor(ui.muted); paint.setStrokeWidth(ui.dp(1)); canvas.drawLine(cursor, plot.top, cursor, plot.bottom, paint);
            canvas.save(); canvas.clipRect(plot);
            for (RideChartData.Series s : series) {
                RideChartData.Value value = s.sampleAt(inspectedTime);
                if (value == null || value.value == null || !Double.isFinite(value.value)) continue;
                float x = plot.left + (float)((value.time - from) / (to - from)) * plot.width();
                float y = plot.bottom - (float)(s.scale.fraction(value.value)) * plot.height();
                paint.setColor(ui.surface); canvas.drawCircle(x, y, ui.dp(5.5f), paint);
                paint.setColor(ui.dark ? s.color : darken(s.color)); canvas.drawCircle(x, y, ui.dp(3.5f), paint);
            }
            canvas.restore(); paint.setColor(ui.text); paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("At " + sampleTime((long)inspectedTime), plot.centerX(), plot.top - ui.dp(12), paint);
        }
    }
    /** Light diagonal hatching along the plot floor where energy / distance is below its speed limit. */
    private void drawHatches(Canvas canvas) {
        if (hatches.isEmpty()) return;
        float band = ui.dp(10), step = ui.dp(5);
        canvas.save(); canvas.clipRect(plot);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(ui.dp(1)); paint.setColor(Ui.withAlpha(ui.muted, 110));
        for (double[] runs : hatches) for (int i = 0; i + 1 < runs.length; i += 2) {
            if (runs[i + 1] < from || runs[i] > to) continue;
            float left = plot.left + (float) ((Math.max(from, runs[i]) - from) / (to - from)) * plot.width();
            float right = plot.left + (float) ((Math.min(to, runs[i + 1]) - from) / (to - from)) * plot.width();
            if (right - left < ui.dp(2)) continue;
            canvas.save(); canvas.clipRect(left, plot.bottom - band, right, plot.bottom);
            for (float x = left - band; x < right; x += step) canvas.drawLine(x, plot.bottom, x + band, plot.bottom - band, paint);
            canvas.restore();
        }
        canvas.restore(); paint.setStyle(Paint.Style.FILL);
    }
    /** When more colored scales exist than fit, their strip fades at the edge that hides more, showing it scrolls. */
    private void drawScaleFades(Canvas canvas) {
        float overflow = totalAxisWidth() - axesWidth;
        if (overflow <= 1) return;
        float start = ui.dp(8), end = plot.left - ui.dp(4), fade = Math.min(ui.dp(22), (end - start) / 3);
        int background = backgroundColor();
        if (axisScroll < overflow - 1) {
            paint.setShader(new LinearGradient(end - fade, 0, end, 0, Ui.withAlpha(background, 0), background, Shader.TileMode.CLAMP));
            canvas.drawRect(end - fade, 0, end, plot.bottom + ui.dp(4), paint);
        }
        if (axisScroll > 1) {
            paint.setShader(new LinearGradient(start, 0, start + fade, 0, background, Ui.withAlpha(background, 0), Shader.TileMode.CLAMP));
            canvas.drawRect(start, 0, start + fade, plot.bottom + ui.dp(4), paint);
        }
        paint.setShader(null);
    }
    /** The surface the chart sits on (its parents draw it); falls back to the card surface. */
    private int backgroundColor() {
        for (ViewParent parent = getParent(); parent instanceof View; parent = parent.getParent()) {
            android.graphics.drawable.Drawable background = ((View) parent).getBackground();
            if (background instanceof android.graphics.drawable.GradientDrawable && ((android.graphics.drawable.GradientDrawable) background).getColor() != null)
                return ((android.graphics.drawable.GradientDrawable) background).getColor().getDefaultColor();
            if (background instanceof android.graphics.drawable.ColorDrawable) return ((android.graphics.drawable.ColorDrawable) background).getColor();
        }
        return ui.surface;
    }
    private static int darken(int color) { return Color.rgb((int)(Color.red(color) * .65), (int)(Color.green(color) * .65), (int)(Color.blue(color) * .65)); }
    private static String axisNumber(RideChartData.Series series, double value) { return series.unit.equals("mph") || series.unit.equals("km/h") ? String.format(Locale.US, "%.1f", Math.abs(value) < .05 ? 0 : value) : number(value); }
    private static String number(double value) { if (Math.abs(value) < .005) value = 0; return String.format(Locale.US, Math.abs(value) >= 100 ? "%.0f" : Math.abs(value) >= 10 ? "%.1f" : "%.2f", value); }
    private static String time(double ms) { long seconds = Math.max(0, (long)(ms / 1000)); return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60); }
    public static String tickTime(double ms, double spacingMs) {
        if (spacingMs >= 1000) return time(ms);
        long tenths = Math.max(0, Math.round(ms / 100));
        return String.format(Locale.US, "%d:%02d.%d", tenths / 600, tenths / 10 % 60, tenths % 10);
    }
    public static String sampleTime(long ms) { return time(ms) + String.format(Locale.US, ".%03d", Math.max(0, ms) % 1000); }
    public static String sampleValue(RideChartData.Series s, RideChartData.Value value) {
        if (value == null || value.value == null || !Double.isFinite(value.value)) return "—";
        int places = s.unit.equals("mph") || s.unit.equals("km/h") ? 1 : 2;
        double raw = Math.abs(value.value) < Math.pow(10, -places) / 2 ? 0 : value.value;
        return String.format(Locale.US, "%." + places + "f", raw) + " " + s.unit;
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            downX = lastX = event.getX(); downY = event.getY();
            draggingAxes = downX < plot.left; panningTime = !draggingAxes && downY >= plot.bottom;
            horizontalGesture = scrollingPage = suppressSingle = false;
            intercept(true); // Decide direction ourselves before the nested scroll views intercept.
        }
        gestures.onTouchEvent(event);
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            suppressSingle = true; draggingAxes = panningTime = false; multiX = (event.getX(0) + event.getX(1)) / 2;
        }
        if (event.getPointerCount() > 1) {
            intercept(true);
            if (action == MotionEvent.ACTION_MOVE) { float focus = (event.getX(0) + event.getX(1)) / 2; pan(focus, multiX); multiX = focus; }
        }
        scale.onTouchEvent(event);
        if (action == MotionEvent.ACTION_DOWN && !suppressSingle && !draggingAxes && !panningTime) inspect(event.getX());
        if (action == MotionEvent.ACTION_MOVE && event.getPointerCount() == 1 && !suppressSingle) {
            float dx = Math.abs(event.getX() - downX), dy = Math.abs(event.getY() - downY);
            if (!horizontalGesture && dy > touchSlop && dy > dx) { scrollingPage = true; intercept(false); return false; }
            if (scrollingPage) return false;
            if (dx > touchSlop) horizontalGesture = true;
            if (horizontalGesture) {
                if (draggingAxes) { axisScroll = Math.max(0, Math.min(totalAxisWidth() - axesWidth, axisScroll + lastX - event.getX())); invalidate(); }
                else if (panningTime) pan(event.getX(), lastX);
                else inspect(event.getX());
                lastX = event.getX();
            }
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            intercept(false);
            if (action == MotionEvent.ACTION_UP && !scrollingPage && !suppressSingle) {
                if (draggingAxes) { snapAxisScroll(); invalidate(); }
                else if (!panningTime) inspect(event.getX());
                performClick();
            }
        }
        return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
}
