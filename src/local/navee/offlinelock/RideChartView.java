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
        setContentDescription("Recorded ride chart. Tap or slide across the plot to inspect recorded readings. Pinch to zoom; drag the time labels or use two fingers to pan. Double tap to show the whole ride. Swipe the colored scales separately.");
    }
    public void setInspectionListener(InspectionListener listener) { inspectionListener = listener; notifyInspection(); }
    public void set(List<RideChartData.Series> all, Set<String> selected) {
        series = new ArrayList<>(); double end = 1;
        for (RideChartData.Series s : all) { end = Math.max(end, s.endTime); if (selected.contains(s.key)) series.add(s); }
        boolean reset = duration <= 1; duration = end; if (reset) { from = 0; to = duration; } else clamp();
        setMinimumWidth(ui.dp(220)); notifyInspection(); requestLayout(); invalidate();
    }
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
        for (int i = 0; i <= ticks; i++) { paint.setTextAlign(i == 0 ? Paint.Align.LEFT : i == ticks ? Paint.Align.RIGHT : Paint.Align.CENTER); double t = from + (to - from) * i / ticks; canvas.drawText(time(t), plot.left + plot.width() * i / ticks, plot.bottom + ui.dp(24), paint); }
        paint.setTextAlign(Paint.Align.CENTER);
        if (series.isEmpty()) { canvas.drawText("Choose readings above", plot.centerX(), plot.centerY(), paint); return; }
        if (axis * series.size() > axesWidth + 1) {
            paint.setTextSize(Math.min(ui.sp(9), ui.dp(12)));
            canvas.drawText("Scales ↔", axesWidth / 2, plot.bottom + ui.dp(24), paint);
            paint.setTextSize(Math.min(ui.sp(10), ui.dp(14)));
        }
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
    private static int darken(int color) { return Color.rgb((int)(Color.red(color) * .65), (int)(Color.green(color) * .65), (int)(Color.blue(color) * .65)); }
    private static String axisNumber(RideChartData.Series series, double value) { return series.unit.equals("mph") || series.unit.equals("km/h") ? String.format(Locale.US, "%.1f", Math.abs(value) < .05 ? 0 : value) : number(value); }
    private static String number(double value) { if (Math.abs(value) < .005) value = 0; return String.format(Locale.US, Math.abs(value) >= 100 ? "%.0f" : Math.abs(value) >= 10 ? "%.1f" : "%.2f", value); }
    private static String time(double ms) { long seconds = Math.max(0, (long)(ms / 1000)); return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60); }
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
