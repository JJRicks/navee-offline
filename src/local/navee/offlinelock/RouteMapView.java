package local.navee.offlinelock;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.LruCache;
import android.view.MotionEvent;
import android.view.View;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Local route geometry works without a basemap. Tiles load only for the visible viewport. */
public final class RouteMapView extends View {
    private static final ExecutorService TILES =
            Executors.newFixedThreadPool(
                    2,
                    r -> {
                        Thread t = new Thread(r, "route-tiles");
                        t.setDaemon(true);
                        return t;
                    });
    private final Ui ui;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<PhoneRideStore.Point> points;
    private final MapTiles provider;
    private final LruCache<String, Bitmap> tiles =
            new LruCache<String, Bitmap>(12 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };
    private final Set<String> pending = new HashSet<String>();
    private final Map<String, Long> failed = new HashMap<String, Long>();
    private final Consumer<PhoneRideStore.Point> selection;
    private int zoom = 15, selected = -1;
    private double centerX, centerY;
    private float lastX, lastY, downX, downY;
    private String metric = "power";
    private boolean fitted;
    private volatile boolean streets, attached;
    private volatile boolean closed;
    private volatile long generation;
    private static final double MAX_LATITUDE = 85.05112878;

    public RouteMapView(
            Ui ui,
            PhoneRideStore.Track track,
            String tileUrl,
            Consumer<PhoneRideStore.Point> selection) {
        super(ui.context);
        this.ui = ui;
        this.selection = selection;
        this.points = track.points;
        provider = new MapTiles(ui.context.getCacheDir(), tileUrl);
        setMinimumHeight(ui.dp(290));
        setFocusable(true);
        setClickable(true);
        setContentDescription(
                "Ride route. Drag to pan, use zoom buttons, tap a point to inspect its readings.");
    }

    public void setMetric(String value) {
        metric = value;
        invalidate();
    }

    public void setStreets(boolean value) {
        streets = value;
        generation++;
        invalidate();
    }

    public void zoomBy(int delta) {
        zoom = Math.max(2, Math.min(19, zoom + delta));
        generation++;
        invalidate();
    }

    public void fitRoute() {
        fitted = false;
        generation++;
        invalidate();
    }

    public void close() {
        closed = true;
        generation++;
        pending.clear();
        tiles.evictAll();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        generation++;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fitted = false;
    }

    private static double x(double longitude) {
        return (longitude + 180) / 360;
    }

    private static double y(double latitude) {
        double lat = Math.toRadians(Math.max(-MAX_LATITUDE, Math.min(MAX_LATITUDE, latitude)));
        return (1 - Math.log(Math.tan(lat) + 1 / Math.cos(lat)) / Math.PI) / 2;
    }

    private double dx(double px) {
        double d = px - centerX;
        if (d > .5) d--;
        if (d < -.5) d++;
        return d;
    }

    private float screenX(PhoneRideStore.Point p) {
        return (float) (getWidth() / 2.0 + dx(x(p.longitude)) * world());
    }

    private float screenY(PhoneRideStore.Point p) {
        return (float) (getHeight() / 2.0 + (y(p.latitude) - centerY) * world());
    }

    private double world() {
        return ui.dp(256) * Math.pow(2, zoom);
    }

    private void fit() {
        fitted = true;
        if (points.isEmpty()) return;
        double anchor = x(points.get(0).longitude),
                minx = anchor,
                maxx = anchor,
                miny = y(points.get(0).latitude),
                maxy = miny;
        for (PhoneRideStore.Point p : points) {
            double px = x(p.longitude);
            if (px - anchor > .5) px--;
            if (px - anchor < -.5) px++;
            minx = Math.min(minx, px);
            maxx = Math.max(maxx, px);
            miny = Math.min(miny, y(p.latitude));
            maxy = Math.max(maxy, y(p.latitude));
        }
        centerX = (minx + maxx) / 2;
        centerX = centerX - Math.floor(centerX);
        centerY = (miny + maxy) / 2;
        double scale =
                Math.min(
                        Math.max(ui.dp(70), getWidth() - ui.dp(70))
                                / Math.max(0.0000001, maxx - minx),
                        Math.max(ui.dp(70), getHeight() - ui.dp(70))
                                / Math.max(0.0000001, maxy - miny));
        zoom =
                Math.max(
                        2,
                        Math.min(18, (int) Math.floor(Math.log(scale / ui.dp(256)) / Math.log(2))));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!fitted) fit();
        canvas.drawColor(ui.raised);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1);
        paint.setColor(ui.outline);
        for (int px = 0; px < getWidth(); px += ui.dp(40))
            canvas.drawLine(px, 0, px, getHeight(), paint);
        for (int py = 0; py < getHeight(); py += ui.dp(40))
            canvas.drawLine(0, py, getWidth(), py, paint);
        if (streets && !points.isEmpty()) drawTiles(canvas);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStyle(Paint.Style.STROKE);
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 1; i < points.size(); i++) {
                PhoneRideStore.Point a = points.get(i - 1), b = points.get(i);
                if (b.gapBefore || b.segment != a.segment) continue;
                paint.setColor(pass == 0 ? Color.argb(200, 255, 255, 255) : color(b));
                paint.setStrokeWidth(ui.dp(pass == 0 ? 7 : 4));
                canvas.drawLine(screenX(a), screenY(a), screenX(b), screenY(b), paint);
            }
        }
        if (!points.isEmpty()) {
            marker(canvas, points.get(0), "S", ui.accent);
            marker(canvas, points.get(points.size() - 1), "E", ui.blue);
            if (selected >= 0) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(ui.text);
                canvas.drawCircle(
                        screenX(points.get(selected)),
                        screenY(points.get(selected)),
                        ui.dp(7),
                        paint);
                paint.setColor(ui.surface);
                canvas.drawCircle(
                        screenX(points.get(selected)),
                        screenY(points.get(selected)),
                        ui.dp(3),
                        paint);
            }
        } else {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(ui.muted);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(ui.sp(14));
            canvas.drawText("No GPS route recorded", getWidth() / 2f, getHeight() / 2f, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(210, 255, 255, 255));
        canvas.drawRoundRect(
                new RectF(ui.dp(9), getHeight() - ui.dp(32), ui.dp(149), getHeight() - ui.dp(9)),
                ui.dp(6),
                ui.dp(6),
                paint);
        paint.setColor(Color.rgb(42, 42, 42));
        paint.setTextSize(ui.sp(10));
        paint.setTextAlign(Paint.Align.LEFT);
        double meters =
                Math.cos(Math.atan(Math.sinh(Math.PI * (1 - 2 * centerY))))
                        * 40075016.686
                        / world()
                        * ui.dp(80);
        boolean feet =
                !"km"
                        .equals(
                                ui.context
                                        .getSharedPreferences("app_settings", 0)
                                        .getString("units", "mi"));
        canvas.drawText(
                String.format(
                        Locale.US,
                        feet ? "%.0f ft" : "%.0f m",
                        feet ? meters * 3.280839895 : meters),
                ui.dp(16),
                getHeight() - ui.dp(19),
                paint);
        paint.setStrokeWidth(ui.dp(1));
        canvas.drawLine(
                ui.dp(16), getHeight() - ui.dp(14), ui.dp(96), getHeight() - ui.dp(14), paint);
    }

    private void marker(Canvas canvas, PhoneRideStore.Point p, String label, int color) {
        float px = screenX(p), py = screenY(p);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(px, py, ui.dp(10), paint);
        paint.setColor(color);
        canvas.drawCircle(px, py, ui.dp(8), paint);
        paint.setColor(ui.dark ? ui.bg : Color.WHITE);
        paint.setTextSize(ui.sp(10));
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(label, px, py + ui.dp(3), paint);
    }

    private int color(PhoneRideStore.Point p) {
        Double value =
                "grade".equals(metric)
                        ? p.gradePercent
                        : "accel".equals(metric) ? p.gpsAccelerationMps2 : p.powerW;
        if (value == null) return ui.muted;
        if (!"power".equals(metric)) {
            double bound = "grade".equals(metric) ? 12 : 2;
            if (value < 0) return blend(ui.accent, ui.blue, Math.min(1, -value / bound));
            return blend(ui.accent, ui.danger, Math.min(1, value / bound));
        }
        return blend(ui.blue, ui.orange, Math.min(1, Math.abs(value) / 2000));
    }

    private int blend(int a, int b, double fraction) {
        return Color.rgb(
                (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * fraction),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * fraction),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * fraction));
    }

    private void drawTiles(Canvas canvas) {
        double size = ui.dp(256),
                w = world(),
                left = centerX * w - getWidth() / 2.0,
                top = centerY * w - getHeight() / 2.0;
        int n = 1 << zoom,
                startX = (int) Math.floor(left / size),
                endX = (int) Math.floor((left + getWidth()) / size),
                startY = (int) Math.floor(top / size),
                endY = (int) Math.floor((top + getHeight()) / size);
        for (int ty = startY; ty <= endY; ty++)
            for (int tx = startX; tx <= endX; tx++) {
                if (ty < 0 || ty >= n) continue;
                int wrapX = ((tx % n) + n) % n;
                String key = zoom + "/" + wrapX + "/" + ty;
                Bitmap tile = tiles.get(key);
                if (tile != null) {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setAlpha(255);
                    canvas.drawBitmap(
                            tile,
                            null,
                            new RectF(
                                    (float) (tx * size - left),
                                    (float) (ty * size - top),
                                    (float) ((tx + 1) * size - left),
                                    (float) ((ty + 1) * size - top)),
                            paint);
                } else request(key, zoom, wrapX, ty);
            }
    }

    private void request(String key, int z, int tx, int ty) {
        if (closed || !attached || !streets || pending.contains(key) || pending.size() >= 32)
            return;
        Long failedAt = failed.get(key);
        if (failedAt != null && android.os.SystemClock.elapsedRealtime() - failedAt < 60000) return;
        pending.add(key);
        final long revision = generation;
        TILES.execute(
                () -> {
                    if (closed || revision != generation || !attached || !streets) {
                        post(() -> { pending.remove(key); if (attached && streets && !closed) invalidate(); });
                        return;
                    }
                    Bitmap result = provider.get(z, tx, ty);
                    post(
                            () -> {
                                pending.remove(key);
                                if (closed) return;
                                if (result != null) tiles.put(key, result);
                                else failed.put(key, android.os.SystemClock.elapsedRealtime());
                                if (attached) invalidate();
                            });
                });
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (points.isEmpty()) return super.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            lastX = downX = event.getX();
            lastY = downY = event.getY();
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            centerX -= (event.getX() - lastX) / world();
            centerX -= Math.floor(centerX);
            centerY = Math.max(0, Math.min(1, centerY - (event.getY() - lastY) / world()));
            lastX = event.getX();
            lastY = event.getY();
            generation++;
            invalidate();
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            if (Math.hypot(event.getX() - downX, event.getY() - downY) < ui.dp(9)) {
                double best = Double.MAX_VALUE;
                for (int i = 0; i < points.size(); i++) {
                    double d =
                            Math.hypot(
                                    screenX(points.get(i)) - event.getX(),
                                    screenY(points.get(i)) - event.getY());
                    if (d < best) {
                        best = d;
                        selected = i;
                    }
                }
                if (selected >= 0 && selection != null) selection.accept(points.get(selected));
                performClick();
                invalidate();
            }
            getParent().requestDisallowInterceptTouchEvent(false);
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            getParent().requestDisallowInterceptTouchEvent(false);
            return true;
        }
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
