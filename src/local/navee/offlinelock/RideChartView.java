package local.navee.offlinelock;

import android.graphics.*;
import android.view.*;
import java.util.*;

/** Independent colored axes, raw-sample inspection, pinch zoom and timeline pan. */
public final class RideChartView extends View {
    public interface InspectionListener { void changed(double time, List<RideChartData.Series> series); }
    private final Ui ui; private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<RideChartData.Series> series = Collections.emptyList();
    private double duration = 1, from = 0, to = 1, inspectedTime = Double.NaN;
    private float lastX, downX, downY, axisScroll, axesWidth, multiX;
    private boolean draggingAxes, panningTime, horizontalGesture, scrollingPage, suppressSingle;
    private final int touchSlop; private InspectionListener inspectionListener;
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
        setContentDescription("Ride chart");
    }
    // Custom accessibility action ids outside both the framework (0x01…) and app (0x7f…) resource ranges.
    private static final int ACTION_ZOOM_IN = 0x7e0a0001, ACTION_ZOOM_OUT = 0x7e0a0002, ACTION_FIT = 0x7e0a0003;
    @Override public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(ACTION_ZOOM_IN, "Zoom in"));
        info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(ACTION_ZOOM_OUT, "Zoom out"));
        info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(ACTION_FIT, "Fit whole ride"));
    }
    @Override public boolean performAccessibilityAction(int action, android.os.Bundle arguments) {
        if (action == ACTION_ZOOM_IN) { zoomBy(2); return true; }
        if (action == ACTION_ZOOM_OUT) { zoomBy(.5); return true; }
        if (action == ACTION_FIT) { reset(); return true; }
        return super.performAccessibilityAction(action, arguments);
    }
    public void setInspectionListener(InspectionListener listener) { inspectionListener = listener; notifyInspection(); }
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
    private void notifyInspection() { if (inspectionListener != null) inspectionListener.changed(inspectedTime, Collections.unmodifiableList(series)); }
    private void pan(float x, float previous) { double delta = (previous - x) / Math.max(1, plot.width()) * (to - from); from += delta; to += delta; clamp(); invalidate(); }
    private void intercept(boolean disallow) { if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(disallow); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float axis = ui.dp(48);
        axesWidth = Math.min(axis * series.size(), axis * Math.max(1, (int)(Math.min(ui.dp(144), getWidth() * .34f) / axis)));
        axisScroll = Math.max(0, Math.min(axisScroll, axis * series.size() - axesWidth));
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
        for (int i = 0; i < series.size(); i++) {
            RideChartData.Series s = series.get(i); float x = ui.dp(8) + axis * (i + 1) - ui.dp(6) - axisScroll;
            int color = ui.dark ? s.color : darken(s.color); paint.setColor(color); paint.setTextAlign(Paint.Align.RIGHT);
            canvas.save(); canvas.clipRect(ui.dp(8), 0, plot.left - ui.dp(4), getHeight());
            canvas.drawText(s.unit, x, plot.top - ui.dp(12), paint);
            for (int tick = 0; tick < 5; tick++) canvas.drawText(s.available() ? axisNumber(s, s.max - (s.max - s.min) * tick / 4) : "—", x, plot.top + plot.height() * tick / 4 + ui.dp(4), paint);
            paint.setStrokeWidth(ui.dp(1)); canvas.drawLine(x + ui.dp(3), plot.top, x + ui.dp(3), plot.bottom, paint);
            canvas.restore();
            canvas.save(); canvas.clipRect(plot); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(ui.dp(2));
            paint.setPathEffect(s.index % 3 == 0 ? null : new DashPathEffect(new float[]{ui.dp(s.index % 3 == 1 ? 7 : 2), ui.dp(3)}, 0));
            Path path = new Path();
            int[] visible = s.drawIndices(from, to, (int)Math.ceil(plot.width()));
            for (int position = 0; position < visible.length; position++) {
                int encoded = visible[position];
                boolean start = encoded < 0; RideChartData.Value p = s.values.get(start ? -encoded - 1 : encoded);
                float px = plot.left + (float)((p.time - from) / (to - from)) * plot.width();
                float py = plot.bottom - (float)((p.value - s.min) / (s.max - s.min)) * plot.height();
                if (start) {
                    path.moveTo(px, py);
                    if (position + 1 == visible.length || visible[position + 1] < 0) canvas.drawPoint(px, py, paint);
                } else path.lineTo(px, py);
            }
            canvas.drawPath(path, paint); paint.setPathEffect(null); paint.setStyle(Paint.Style.FILL); canvas.restore();
        }
        drawScaleFades(canvas, axis);
        if (Double.isFinite(inspectedTime) && inspectedTime >= from && inspectedTime <= to) {
            float cursor = plot.left + (float)((inspectedTime - from) / (to - from)) * plot.width();
            paint.setColor(ui.muted); paint.setStrokeWidth(ui.dp(1)); canvas.drawLine(cursor, plot.top, cursor, plot.bottom, paint);
            canvas.save(); canvas.clipRect(plot);
            for (RideChartData.Series s : series) {
                RideChartData.Value value = s.sampleAt(inspectedTime);
                if (value == null || value.value == null || !Double.isFinite(value.value)) continue;
                float x = plot.left + (float)((value.time - from) / (to - from)) * plot.width();
                float y = plot.bottom - (float)((value.value - s.min) / (s.max - s.min)) * plot.height();
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
            float left = plot.left + (float) ((runs[i] - from) / (to - from)) * plot.width();
            float right = plot.left + (float) ((runs[i + 1] - from) / (to - from)) * plot.width();
            if (right - left < ui.dp(2)) continue;
            canvas.save(); canvas.clipRect(left, plot.bottom - band, right, plot.bottom);
            for (float x = left - band; x < right; x += step) canvas.drawLine(x, plot.bottom, x + band, plot.bottom - band, paint);
            canvas.restore();
        }
        canvas.restore(); paint.setStyle(Paint.Style.FILL);
    }
    /** When more colored scales exist than fit, their strip fades at the edge that hides more, showing it scrolls. */
    private void drawScaleFades(Canvas canvas, float axis) {
        float overflow = axis * series.size() - axesWidth;
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
                if (draggingAxes) { axisScroll = Math.max(0, Math.min(ui.dp(48) * series.size() - axesWidth, axisScroll + lastX - event.getX())); invalidate(); }
                else if (panningTime) pan(event.getX(), lastX);
                else inspect(event.getX());
                lastX = event.getX();
            }
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            intercept(false);
            if (action == MotionEvent.ACTION_UP && !scrollingPage && !suppressSingle) {
                if (draggingAxes) { axisScroll = Math.round(axisScroll / ui.dp(48)) * ui.dp(48); invalidate(); }
                else if (!panningTime) inspect(event.getX());
                performClick();
            }
        }
        return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
}
