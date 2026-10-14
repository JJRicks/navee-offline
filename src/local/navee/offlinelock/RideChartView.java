package local.navee.offlinelock;

import android.graphics.*;
import android.view.*;
import java.util.*;

/** Independent colored Y axes, shared ride time, pinch zoom and horizontal timeline pan. */
public final class RideChartView extends View {
    private final Ui ui; private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<RideChartData.Series> series = Collections.emptyList();
    private double duration = 1, from = 0, to = 1; private float lastX, axisScroll, axesWidth; private boolean draggingAxes;
    private final ScaleGestureDetector scale; private final GestureDetector gestures;
    private final RectF plot = new RectF();
    public RideChartView(Ui ui) {
        super(ui.context); this.ui = ui; setFocusable(true); setClickable(true);
        scale = new ScaleGestureDetector(ui.context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) { zoom(detector.getScaleFactor(), detector.getFocusX()); return true; }
        });
        gestures = new GestureDetector(ui.context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onDoubleTap(MotionEvent e) { reset(); return true; }
        });
        setContentDescription("Recorded ride chart. Pinch to zoom time, drag to pan, double tap to show the whole ride. Swipe the colored scales separately to see every selected reading.");
    }
    public void set(List<RideChartData.Series> all, Set<String> selected) {
        series = new ArrayList<>(); double end = 1;
        for (RideChartData.Series s : all) { for (RideChartData.Value p : s.values) end = Math.max(end, p.time); if (selected.contains(s.key)) series.add(s); }
        boolean reset = duration <= 1; duration = end; if (reset) { from = 0; to = duration; } else clamp();
        setMinimumWidth(ui.dp(220)); requestLayout(); invalidate();
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
            Path path = new Path(); boolean drawing = false; RideChartData.Value previous = null;
            int[] visible=s.visibleRange(from,to);
            for (int point=visible[0];point<visible[1];point++) {
                RideChartData.Value p=s.values.get(point);
                if (p.value == null || !Double.isFinite(p.value)) { drawing = false; previous = p; continue; }
                float px = plot.left + (float)((p.time - from) / (to - from)) * plot.width();
                float py = plot.bottom - (float)((p.value - s.min) / (s.max - s.min)) * plot.height();
                if (!drawing || previous == null || p.segment != previous.segment || p.time <= previous.time || p.time - previous.time > Math.max(10000, s.typicalIntervalMs * 3)) path.moveTo(px, py); else path.lineTo(px, py);
                drawing = true; previous = p;
            }
            canvas.drawPath(path, paint); paint.setPathEffect(null); paint.setStyle(Paint.Style.FILL); canvas.restore();
        }
    }
    private static int darken(int color) { return Color.rgb((int)(Color.red(color) * .65), (int)(Color.green(color) * .65), (int)(Color.blue(color) * .65)); }
    private static String axisNumber(RideChartData.Series series, double value) { return series.unit.equals("mph") || series.unit.equals("km/h") ? String.format(Locale.US, "%.1f", Math.abs(value) < .05 ? 0 : value) : number(value); }
    private static String number(double value) { if (Math.abs(value) < .005) value = 0; return String.format(Locale.US, Math.abs(value) >= 100 ? "%.0f" : Math.abs(value) >= 10 ? "%.1f" : "%.2f", value); }
    private static String time(double ms) { long seconds = Math.max(0, (long)(ms / 1000)); return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60); }
    @Override public boolean onTouchEvent(MotionEvent event) {
        gestures.onTouchEvent(event); scale.onTouchEvent(event);
        if (event.getPointerCount() > 1 && getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { lastX = event.getX(); draggingAxes = lastX < plot.left; getParent().requestDisallowInterceptTouchEvent(draggingAxes || to - from < duration - 1); return true; }
        if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) { draggingAxes = false; return true; }
        if (event.getActionMasked() == MotionEvent.ACTION_POINTER_UP) { int remaining = event.getActionIndex() == 0 ? 1 : 0; if (remaining < event.getPointerCount()) lastX = event.getX(remaining); draggingAxes = false; return true; }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE && !scale.isInProgress() && event.getPointerCount() == 1) {
            if (draggingAxes) { axisScroll = Math.max(0, Math.min(ui.dp(48) * series.size() - axesWidth, axisScroll + lastX - event.getX())); lastX = event.getX(); invalidate(); return true; }
            double delta = (lastX - event.getX()) / Math.max(1, plot.width()) * (to - from); from += delta; to += delta; clamp(); lastX = event.getX(); invalidate(); return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) { getParent().requestDisallowInterceptTouchEvent(false); if (event.getActionMasked() == MotionEvent.ACTION_UP) { if (draggingAxes) { axisScroll = Math.round(axisScroll / ui.dp(48)) * ui.dp(48); invalidate(); } performClick(); } }
        return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
}
