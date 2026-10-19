package local.navee.offlinelock;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.LruCache;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

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
    private MapTiles provider;
    private int maximumTileZoom;
    private Consumer<String> statusListener;
    private final LruCache<String, Bitmap> tiles =
            new LruCache<String, Bitmap>(12 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };
    private final Set<String> pending = new HashSet<String>();
    /** When each newly loaded tile arrived, so it fades up over the grid instead of popping in. */
    private final Map<String, Long> arrivals = new HashMap<String, Long>();
    private final Map<String, Long> failed = new HashMap<String, Long>();
    private final Consumer<PhoneRideStore.Point> selection;
    private final int powerLow, powerHigh;
    /** Computed once per ride so inline, expanded, panned and zoomed views share colours. */
    private final RouteSpeedScale speedScale;
    private double elevationLow = Double.POSITIVE_INFINITY, elevationHigh = Double.NEGATIVE_INFINITY;
    private int selected = -1, activePointer = -1;
    private double zoom = 15;
    private double centerX, centerY;
    private float lastX, lastY, downX, downY;
    private float focusX, focusY, span, lastFocusX, lastFocusY, lastSpan;
    private final int touchSlop;
    private boolean dragged, multiplePointers, scrubbingRoute;
    private double scrubPosition = Double.NaN;
    private String metric = "power";
    private boolean fitted, preserveViewportOnFirstSize;
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
        touchSlop = ViewConfiguration.get(ui.context).getScaledTouchSlop();
        this.selection = selection;
        this.points = track.points;
        for (PhoneRideStore.Point point : points) if (point.relativeAltitudeM != null) { elevationLow = Math.min(elevationLow, point.relativeAltitudeM); elevationHigh = Math.max(elevationHigh, point.relativeAltitudeM); }
        powerLow = RouteSpeedScale.LOW;
        powerHigh = RouteSpeedScale.HIGH;
        speedScale = RouteSpeedScale.of(points);
        provider = new MapTiles(ui.context, tileUrl);
        maximumTileZoom = MapSource.maximumTileZoom(tileUrl);
        setMinimumHeight(ui.dp(290));
        setFocusable(true);
        setClickable(true);
        setContentDescription("Ride route map");
    }

    /** Selection exists only while the user is inspecting the route. */
    void clearSelection() {
        if (selected < 0) return;
        selected = -1;
        if (selection != null) selection.accept(null);
        invalidate();
    }

    String metric() { return metric; }

    /** Opening or closing the expanded map preserves inspection instead of resetting the route. */
    void copyViewStateFrom(RouteMapView other) {
        zoom = other.zoom; centerX = other.centerX; centerY = other.centerY; fitted = other.fitted;
        metric = other.metric; selected = other.selected;
        preserveViewportOnFirstSize = getWidth() == 0 || getHeight() == 0;
        generation++;
        if (selection != null) selection.accept(selected >= 0 && selected < points.size() ? points.get(selected) : null);
        invalidate();
    }

    public void setMetric(String value) {
        metric = value;
        invalidate();
    }

    public void setStreets(boolean value) {
        streets = value;
        generation++;
        notifyStatus();
        invalidate();
    }

    public void setSource(String tileUrl) {
        generation++;
        provider = new MapTiles(ui.context, tileUrl);
        maximumTileZoom = MapSource.maximumTileZoom(tileUrl);
        pending.clear(); failed.clear(); tiles.evictAll(); arrivals.clear();
        notifyStatus();
        invalidate();
    }

    public void setStatusListener(Consumer<String> listener) {
        statusListener = listener;
        notifyStatus();
    }

    private void notifyStatus() {
        if (statusListener != null && !closed) statusListener.accept(streets ? provider.status() : "");
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
        endGesture();
        closed = true;
        generation++;
        pending.clear();
        tiles.evictAll();
        arrivals.clear();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
    }

    @Override
    protected void onDetachedFromWindow() {
        endGesture();
        attached = false;
        generation++;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (!preserveViewportOnFirstSize) fitted = false;
        preserveViewportOnFirstSize = false;
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
        // Missing runs join into one dashed path: per-fix lines are often shorter than a dash, which drew them solid.
        android.graphics.Path missingPath = new android.graphics.Path(); int missingEnd = -1;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 1; i < points.size(); i++) {
                PhoneRideStore.Point a = points.get(i - 1), b = points.get(i);
                if (b.gapBefore || b.segment != a.segment) continue;
                paint.setColor(pass == 0 ? routeCasingColor() : color(i));
                paint.setStrokeWidth(ui.dp(pass == 0 ? 7 : 4));
                boolean missing = "speed".equals(metric) ? speedScale.kmh(i) == null : "grade".equals(metric) ? b.gradePercent == null : "accel".equals(metric) ? b.gpsAccelerationMps2 == null : "elevation".equals(metric) ? b.relativeAltitudeM == null : b.powerW == null;
                if (pass == 1 && missing) {
                    if (missingEnd != i - 1) missingPath.moveTo(screenX(a), screenY(a));
                    missingPath.lineTo(screenX(b), screenY(b)); missingEnd = i;
                    continue;
                }
                canvas.drawLine(screenX(a), screenY(a), screenX(b), screenY(b), paint);
            }
        }
        if (missingEnd >= 0) {
            // Butt caps keep the gaps open; round caps would close a 5 dp gap on a 4 dp line.
            paint.setColor(Color.rgb(128, 128, 128)); paint.setStrokeWidth(ui.dp(4)); paint.setStrokeCap(Paint.Cap.BUTT);
            paint.setPathEffect(new android.graphics.DashPathEffect(new float[]{ui.dp(4), ui.dp(5)}, 0));
            canvas.drawPath(missingPath, paint);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }
        paint.setPathEffect(null);
        if (!points.isEmpty()) {
            marker(canvas, points.get(0), "S", true);
            marker(canvas, points.get(points.size() - 1), "E", false);
            if (selected >= 0) {
                PhoneRideStore.Point chosen = points.get(selected);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.WHITE);
                canvas.drawCircle(screenX(chosen), screenY(chosen), ui.dp(7), paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(ui.dp(3));
                paint.setColor(ui.accent);
                canvas.drawCircle(screenX(chosen), screenY(chosen), ui.dp(6), paint);
                drawTooltip(canvas, chosen, selected);
            }
        } else {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(ui.muted);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(ui.regular());
            paint.setTextSize(ui.sp(14));
            canvas.drawText("No GPS route recorded", getWidth() / 2f, getHeight() / 2f, paint);
        }
        // Scale chip, top-left: the bottom-left corner belongs to the attribution chip.
        double meters =
                Math.cos(Math.atan(Math.sinh(Math.PI * (1 - 2 * centerY))))
                        * 40075016.686
                        / world()
                        * ui.dp(80);
        boolean feet = imperial();
        String scale =
                String.format(
                        Locale.US,
                        feet ? "%.0f ft" : "%.0f m",
                        feet ? meters * 3.280839895 : meters);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Ui.withAlpha(paper(), 205));
        canvas.drawRoundRect(
                new RectF(ui.dp(8), ui.dp(8), ui.dp(104), ui.dp(38)), ui.dp(8), ui.dp(8), paint);
        paint.setColor(ink());
        paint.setTypeface(ui.medium());
        paint.setTextSize(Math.min(ui.sp(10), ui.dp(14)));
        paint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(scale, ui.dp(16), ui.dp(23), paint);
        paint.setStrokeWidth(ui.dp(1.5f));
        canvas.drawLine(ui.dp(16), ui.dp(30), ui.dp(96), ui.dp(30), paint);
    }

    /** Tiles can be pale in either theme; the casing keeps every ramp value visible. */
    private int routeCasingColor() { return streets || !ui.dark ? 0xff20242c : Color.WHITE; }

    /** Volt map plate: near-black with light text in both themes, so it reads on any basemap. */
    private int paper() {
        return ui.dark ? ui.bg : ui.text;
    }

    private int ink() {
        return ui.dark ? ui.text : ui.surface;
    }

    private boolean imperial() {
        return !"km".equals(units());
    }

    private String units() {
        return ui.context.getSharedPreferences("app_settings", 0).getString("units", "mi");
    }

    private void marker(Canvas canvas, PhoneRideStore.Point p, String label, boolean start) {
        float px = screenX(p), py = screenY(p);
        int dark = paper();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(start ? dark : Color.WHITE);
        canvas.drawCircle(px, py, ui.dp(10), paint);
        paint.setColor(start ? Color.WHITE : dark);
        canvas.drawCircle(px, py, ui.dp(8), paint);
        paint.setColor(start ? dark : Color.WHITE);
        paint.setTypeface(ui.bold());
        paint.setTextSize(ui.dp(10));
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(label, px, py + ui.dp(3.5f), paint);
    }

    /** A dark tooltip beside the tapped point: the selected colour metric first, then context. */
    private void drawTooltip(Canvas canvas, PhoneRideStore.Point p, int index) {
        String primary, power = power(p.powerW), grade = grade(p.gradePercent),
                speed = speed(p.speedMps), accel = accel(p.gpsAccelerationMps2);
        List<String> rest = new ArrayList<String>();
        if ("speed".equals(metric)) {
            // Name the source the route colour uses; the other sensor stays as labelled context.
            boolean scooter = RouteSpeedScale.SCOOTER.equals(speedScale.source);
            Double chosen = speedScale.kmh(index);
            String name = scooter ? "scooter" : "GPS";
            primary = speedScale.source == null ? "Speed not measured" : chosen == null ? (scooter ? "No scooter speed" : "No GPS speed")
                    : RouteSpeedScale.label(chosen, units()) + " · " + name;
            if (power != null) rest.add(power);
            if (scooter && speed != null) rest.add("GPS " + speed);
            else if (!scooter && p.scooterSpeedKmh != null) rest.add("Scooter " + RouteSpeedScale.label(p.scooterSpeedKmh, units()));
            speed = null;
        } else if ("grade".equals(metric)) {
            primary = grade == null ? "Grade not measured" : grade;
            if (power != null) rest.add(power);
        } else if ("elevation".equals(metric)) {
            primary = p.relativeAltitudeM == null ? "Elevation not measured" : String.format(Locale.US, imperial() ? "Elevation %+.0f ft" : "Elevation %+.0f m", p.relativeAltitudeM * (imperial() ? 3.280839895 : 1));
            if (power != null) rest.add(power);
        } else if ("accel".equals(metric)) {
            primary = accel == null ? "GPS accel not measured" : accel;
            if (power != null) rest.add(power);
        } else {
            primary = power == null ? "Power not measured" : power;
            if (grade != null) rest.add(grade);
        }
        if (speed != null) rest.add(speed);
        String secondary = android.text.TextUtils.join(" · ", rest);
        android.text.TextPaint big = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG),
                small = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
        big.setTypeface(ui.bold());
        big.setTextSize(Math.min(ui.sp(14), ui.dp(20)));
        small.setTypeface(ui.regular());
        small.setTextSize(Math.min(ui.sp(10.5f), ui.dp(15)));
        float padX = ui.dp(10), padY = ui.dp(7), gap = secondary.isEmpty() ? 0 : ui.dp(3);
        Paint.FontMetrics bm = big.getFontMetrics(), sm = small.getFontMetrics();
        float h1 = bm.descent - bm.ascent, h2 = secondary.isEmpty() ? 0 : sm.descent - sm.ascent;
        // The zoom/fit buttons occupy a 48 dp column at the right edge; keep the tooltip beside it, not under it.
        float controls = ui.dp(60);
        float maxWidth = Math.max(ui.dp(60), getWidth() - ui.dp(8) - controls);
        float width =
                Math.min(
                        maxWidth,
                        Math.max(
                                        big.measureText(primary),
                                        secondary.isEmpty() ? 0 : small.measureText(secondary))
                                + 2 * padX);
        float height = h1 + gap + h2 + 2 * padY;
        float px = screenX(p), py = screenY(p);
        float left = Math.max(ui.dp(8), Math.min(getWidth() - controls - width, px - width / 2));
        float top = py - ui.dp(16) - height;
        if (top < ui.dp(44)) top = py + ui.dp(16);
        top = Math.max(ui.dp(8), Math.min(getHeight() - ui.dp(8) - height, top));
        RectF box = new RectF(left, top, left + width, top + height);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(paper());
        canvas.drawRoundRect(box, ui.dp(10), ui.dp(10), paint);
        if (ui.dark) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(ui.dp(1));
            paint.setColor(ui.outline);
            canvas.drawRoundRect(box, ui.dp(10), ui.dp(10), paint);
        }
        float textWidth = width - 2 * padX;
        big.setColor(ink());
        canvas.drawText(
                android.text.TextUtils.ellipsize(
                                primary, big, textWidth, android.text.TextUtils.TruncateAt.END)
                        .toString(),
                left + padX,
                top + padY - bm.ascent,
                big);
        if (!secondary.isEmpty()) {
            small.setColor(Ui.withAlpha(ink(), 185));
            canvas.drawText(
                    android.text.TextUtils.ellipsize(
                                    secondary, small, textWidth, android.text.TextUtils.TruncateAt.END)
                            .toString(),
                    left + padX,
                    top + padY + h1 + gap - sm.ascent,
                    small);
        }
    }

    private static String power(Double watts) {
        if (watts == null) return null;
        return Math.abs(watts) >= 1000
                ? String.format(Locale.US, "%.2f kW", watts / 1000)
                : String.format(Locale.US, "%.0f W", watts);
    }

    private static String grade(Double percent) {
        return percent == null ? null : String.format(Locale.US, "≈%+.1f%% grade", percent);
    }

    private static String accel(Double mps2) {
        return mps2 == null ? null : String.format(Locale.US, "%+.2f m/s² GPS accel", mps2);
    }

    private String speed(Double mps) {
        if (mps == null) return null;
        double kmh = mps * 3.6, mph = kmh / RideStore.KM_PER_MILE;
        String units = units();
        return units.equals("km")
                ? String.format(Locale.US, "%.0f km/h", kmh)
                : units.equals("dual")
                        ? String.format(Locale.US, "%.0f mph / %.0f km/h", mph, kmh)
                        : String.format(Locale.US, "%.0f mph", mph);
    }

    /** Blue → yellow sequential and blue → neutral → orange diverging scales avoid red/green ambiguity. */
    private int color(int index) {
        if ("speed".equals(metric)) return speedScale.color(index);
        PhoneRideStore.Point p = points.get(index);
        Double value = "grade".equals(metric) ? p.gradePercent : "accel".equals(metric) ? p.gpsAccelerationMps2
                : "elevation".equals(metric) ? p.relativeAltitudeM : p.powerW;
        if (value == null) return Color.rgb(128, 128, 128);
        if ("power".equals(metric)) return blend(powerLow, powerHigh, Math.min(1, Math.abs(value) / 2000));
        if ("elevation".equals(metric)) {
            return blend(powerLow, powerHigh, elevationHigh > elevationLow ? (value - elevationLow) / (elevationHigh - elevationLow) : .5);
        }
        double bound = "grade".equals(metric) ? 12 : 2;
        return blend(Color.rgb(226, 226, 226), value < 0 ? Color.rgb(0, 114, 178) : Color.rgb(213, 94, 0), Math.min(1, Math.abs(value) / bound));
    }

    private static int blend(int a, int b, double fraction) {
        return Color.rgb(
                (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * fraction),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * fraction),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * fraction));
    }

    private void drawTiles(Canvas canvas) {
        int tileZoom = Math.min((int)Math.floor(zoom), maximumTileZoom);
        // Enlarge available tiles without limiting close-up route inspection.
        double size = ui.dp(256) * Math.pow(2, zoom - tileZoom),
                w = world(),
                left = centerX * w - getWidth() / 2.0,
                top = centerY * w - getHeight() / 2.0;
        int n = 1 << tileZoom,
                startX = (int) Math.floor(left / size),
                endX = (int) Math.floor((left + getWidth()) / size),
                startY = (int) Math.floor(top / size),
                endY = (int) Math.floor((top + getHeight()) / size);
        long now = android.os.SystemClock.uptimeMillis();
        boolean fading = false;
        for (int ty = startY; ty <= endY; ty++)
            for (int tx = startX; tx <= endX; tx++) {
                if (ty < 0 || ty >= n) continue;
                int wrapX = ((tx % n) + n) % n;
                String key = tileZoom + "/" + wrapX + "/" + ty;
                Bitmap tile = tiles.get(key);
                if (tile != null) {
                    int alpha = tileAlpha(key, now);
                    fading |= alpha < 255;
                    paint.setStyle(Paint.Style.FILL);
                    paint.setAlpha(alpha);
                    canvas.drawBitmap(
                            tile,
                            null,
                            new RectF(
                                    (float) (tx * size - left),
                                    (float) (ty * size - top),
                                    (float) ((tx + 1) * size - left),
                                    (float) ((ty + 1) * size - top)),
                            paint);
                } else request(key, tileZoom, wrapX, ty);
            }
        paint.setAlpha(255);
        if (fading) postInvalidateOnAnimation();
    }

    private int tileAlpha(String key, long now) {
        Long arrived = arrivals.get(key);
        if (arrived == null) return 255;
        float progress = (now - arrived) / (float) Motion.CHANGE_MS;
        if (progress >= 1 || !Ui.motionEnabled()) { arrivals.remove(key); return 255; }
        return Math.round(255 * Motion.FADE.getInterpolation(Math.max(0, progress)));
    }

    private void request(String key, int z, int tx, int ty) {
        if (closed || !attached || !streets || pending.contains(key) || pending.size() >= 32)
            return;
        Long failedAt = failed.get(key);
        if (failedAt != null && android.os.SystemClock.elapsedRealtime() - failedAt < 60000) return;
        pending.add(key);
        final long revision = generation;
        final MapTiles requestedProvider = provider;
        TILES.execute(
                () -> {
                    if (closed || revision != generation || !attached || !streets) {
                        post(() -> {
                            if (provider != requestedProvider) return;
                            pending.remove(key); if (attached && streets && !closed) invalidate();
                        });
                        return;
                    }
                    Bitmap result = requestedProvider.get(z, tx, ty);
                    post(
                            () -> {
                                if (provider != requestedProvider) return;
                                pending.remove(key);
                                if (closed) return;
                                if (result != null) {
                                    tiles.put(key, result);
                                    if (Ui.motionEnabled()) arrivals.put(key, android.os.SystemClock.uptimeMillis());
                                }
                                else failed.put(key, android.os.SystemClock.elapsedRealtime());
                                notifyStatus();
                                if (attached) invalidate();
                            });
                });
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (closed || points.isEmpty()) return super.onTouchEvent(event);
        if (!fitted) fit();
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            activePointer = event.getPointerId(0);
            lastX = downX = event.getX(0);
            lastY = downY = event.getY(0);
            dragged = multiplePointers = false;
            lastSpan = 0;
            RouteHit hit = nearestRoute(downX, downY, ui.dp(18), Double.NaN);
            scrubbingRoute = hit != null;
            scrubPosition = Double.NaN;
            if (scrubbingRoute) selectRouteHit(hit);
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            multiplePointers = true;
            // A deliberate second finger takes over for zoom/pan until every finger lifts.
            scrubbingRoute = false;
            rebasePointers(event, -1);
            return true;
        }
        if (action == MotionEvent.ACTION_POINTER_UP) {
            multiplePointers = true;
            rebasePointers(event, event.getActionIndex());
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (event.getPointerCount() > 1) {
                multiplePointers = true;
                measureFocus(event, -1);
                double oldWorld = world();
                double anchorX = centerX + (lastFocusX - getWidth()/2.0) / oldWorld;
                double anchorY = centerY + (lastFocusY - getHeight()/2.0) / oldWorld;
                // Avoid unstable scale factors when fingers meet; panning still works.
                if (lastSpan >= ui.dp(8) && span >= ui.dp(8))
                    zoom = Math.max(2, Math.min(19, zoom + Math.log(span / lastSpan) / Math.log(2)));
                double newWorld = world();
                centerX = anchorX - (focusX - getWidth()/2.0) / newWorld;
                centerY = anchorY - (focusY - getHeight()/2.0) / newWorld;
                lastFocusX = focusX; lastFocusY = focusY; lastSpan = span;
                dragged = true;
            } else {
                int pointer = event.findPointerIndex(activePointer);
                if (pointer < 0) { rebasePointers(event, -1); return true; }
                float px = event.getX(pointer), py = event.getY(pointer);
                if (scrubbingRoute) {
                    dragged |= Math.hypot(px - downX, py - downY) > touchSlop;
                    selectRouteHit(nearestRoute(px, py, Double.POSITIVE_INFINITY, scrubPosition));
                    lastX = px; lastY = py;
                    return true;
                }
                if (!dragged && !multiplePointers && Math.hypot(px-downX, py-downY) <= touchSlop) return true;
                centerX -= (px-lastX)/world(); centerY -= (py-lastY)/world();
                lastX = px; lastY = py; dragged = true;
            }
            centerX -= Math.floor(centerX);
            centerY = Math.max(0, Math.min(1, centerY));
            generation++; invalidate();
            return true;
        }
        if (action == MotionEvent.ACTION_UP) {
            if (scrubbingRoute)
                selectRouteHit(nearestRoute(event.getX(), event.getY(), Double.POSITIVE_INFINITY, scrubPosition));
            if (!multiplePointers && !dragged && Math.hypot(event.getX()-downX, event.getY()-downY) <= touchSlop) {
                if (!scrubbingRoute) {
                    selected = hitRoute(event.getX(), event.getY());
                    if (selection != null) selection.accept(selected >= 0 ? points.get(selected) : null);
                }
                performClick();
                invalidate();
            }
            endGesture();
            return true;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            endGesture();
            return true;
        }
        return true;
    }

    /** Hit the drawn line, not an arbitrarily distant GPS fix; gaps never form selectable bridges. */
    private int hitRoute(float tapX, float tapY) {
        RouteHit hit = nearestRoute(tapX, tapY, ui.dp(18), Double.NaN);
        return hit == null ? -1 : hit.index;
    }

    private void selectRouteHit(RouteHit hit) {
        if (hit == null) return;
        scrubPosition = hit.position;
        if (selected == hit.index) return;
        selected = hit.index;
        if (selection != null) selection.accept(points.get(selected));
        invalidate();
    }

    /** Once captured, inspection follows the nearest drawn segment even outside its hit target. */
    private RouteHit nearestRoute(float tapX, float tapY, double limit, double previousPosition) {
        RouteHit hit = null;
        RouteHit previousFirst = null, previousSecond = null;
        for (int i = 1; i < points.size(); i++) {
            PhoneRideStore.Point a = points.get(i - 1), b = points.get(i);
            if (b.gapBefore || b.segment != a.segment) continue;
            double ax = screenX(a), ay = screenY(a), bx = screenX(b), by = screenY(b);
            double vx = bx - ax, vy = by - ay, lengthSquared = vx * vx + vy * vy;
            double fraction = lengthSquared == 0 ? 0 : Math.max(0, Math.min(1,
                    ((tapX - ax) * vx + (tapY - ay) * vy) / lengthSquared));
            double distance = Math.hypot(tapX - ax - fraction * vx, tapY - ay - fraction * vy);
            boolean nearer = distance <= limit && betterHit(distance, i - 1 + fraction, hit, previousPosition);
            boolean previousSegment = i - 1 <= previousPosition && previousPosition <= i;
            if (nearer || previousSegment) {
                RouteHit candidate = new RouteHit(fraction <= .5 ? i - 1 : i, i - 1 + fraction, distance, i - 1);
                if (nearer) hit = candidate;
                if (previousSegment) {
                    if (previousFirst == null) previousFirst = candidate;
                    else previousSecond = candidate;
                }
            }
        }
        // Start/end markers are visible even on a single-fix route or beside a GPS gap.
        for (int i : new int[]{0, points.size() - 1}) {
            if (i < 0 || i >= points.size()) continue;
            double distance = Math.hypot(screenX(points.get(i)) - tapX, screenY(points.get(i)) - tapY);
            if (distance <= limit && betterHit(distance, i, hit, previousPosition))
                hit = new RouteHit(i, i, distance, -1);
        }
        if (hit == null || previousFirst == null || hit.segment < 0) return hit;
        int previousLast = previousSecond == null ? previousFirst.segment : previousSecond.segment;
        // Moving along the same part of the route stays exact, without a sticky trailing reading.
        if (hit.segment >= previousFirst.segment - 1 && hit.segment <= previousLast + 1) return hit;
        // At a crossing, allow a little finger wobble before changing to another pass. The band
        // stays anchored to the globally nearest distance; successive candidates cannot widen it.
        double continuityLimit = Math.min(limit, hit.distance + ui.dp(3));
        RouteHit retained = previousFirst.distance <= continuityLimit ? previousFirst : null;
        if (previousSecond != null && previousSecond.distance <= continuityLimit
                && (retained == null || Math.abs(previousSecond.position - previousPosition)
                        < Math.abs(retained.position - previousPosition))) retained = previousSecond;
        return retained == null ? hit : retained;
    }

    private boolean betterHit(double distance, double position, RouteHit best, double previousPosition) {
        if (best == null) return true;
        // Exact distance ties prefer the current chronological pass.
        if (!Double.isNaN(previousPosition) && distance == best.distance)
            return Math.abs(position - previousPosition) < Math.abs(best.position - previousPosition);
        return distance <= best.distance;
    }

    private static final class RouteHit {
        final int index, segment;
        final double position, distance;
        RouteHit(int index, double position, double distance, int segment) {
            this.index = index; this.position = position; this.distance = distance; this.segment = segment;
        }
    }

    /** Seed a new finger set without interpreting a finger arriving/leaving as map movement. */
    private void rebasePointers(MotionEvent event, int excluded) {
        measureFocus(event, excluded);
        lastFocusX = focusX; lastFocusY = focusY; lastSpan = span;
        for (int i=0; i<event.getPointerCount(); i++) if (i != excluded) {
            activePointer = event.getPointerId(i); lastX = event.getX(i); lastY = event.getY(i); break;
        }
    }

    private void measureFocus(MotionEvent event, int excluded) {
        int count = event.getPointerCount() - (excluded >= 0 ? 1 : 0);
        double sx = 0, sy = 0, variance = 0;
        for (int i=0; i<event.getPointerCount(); i++) if (i != excluded) { sx += event.getX(i); sy += event.getY(i); }
        focusX = (float)(sx/count); focusY = (float)(sy/count);
        for (int i=0; i<event.getPointerCount(); i++) if (i != excluded) {
            double dx = event.getX(i)-focusX, dy = event.getY(i)-focusY; variance += dx*dx + dy*dy;
        }
        span = (float)(2*Math.sqrt(variance/count));
    }

    private void endGesture() {
        activePointer = -1; lastSpan = 0; scrubbingRoute = false; scrubPosition = Double.NaN;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
