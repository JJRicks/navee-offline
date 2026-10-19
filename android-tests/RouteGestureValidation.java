package local.navee.offlinelock.tests;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import local.navee.offlinelock.*;

/** Synthetic route views on an emulator only. Does not start the app or touch Bluetooth. */
public final class RouteGestureValidation extends Instrumentation {
    private int checks;
    private File directory;
    private Ui ui;
    private long clock;

    @Override public void onCreate(Bundle args) { start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
            check(hardware.contains("ranchu") || hardware.contains("goldfish"), "Refusing physical phone");
            directory = new File(getTargetContext().getCacheDir(), "route-gesture-" + System.nanoTime());
            PhoneRideStore store = new PhoneRideStore(directory);
            double[][] line = new double[41][2];
            for (int i = 0; i < line.length; i++) { line[i][0] = 43.82; line[i][1] = -111.78 + i * .00001; }
            PhoneRideStore.Track track = track(store, "line", line);
            PhoneRideStore.Track crossing = track(store, "crossing", new double[][]{
                    {43.82, -111.78}, {43.8201, -111.7799}, {43.8201, -111.78}, {43.82, -111.7799}});
            runOnMainSync(() -> {
                ui = new Ui(getTargetContext(), true, 1);
                for (int[] size : new int[][]{{344, 290}, {725, 600}}) inspectAndNavigate(track, size[0], size[1]);
                crossingContinuity(crossing);
                gapsAndSinglePoint(store);
            });
            result.putString("result", "PASS");
        } catch (Throwable failure) {
            result.putString("result", "FAIL"); result.putString("error", android.util.Log.getStackTraceString(failure));
        } finally {
            remove(directory);
            result.putInt("assertions", checks);
            result.putString("fixture", "Synthetic local GPS routes; inline/fullscreen-sized views; no Bluetooth or tile requests");
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    private PhoneRideStore.Track track(PhoneRideStore store, String id, double[][] coordinates) throws Exception {
        long wall = 1_800_000_000_000L;
        store.start(id, wall, 1000, "mounted");
        StringBuilder csv = new StringBuilder();
        for (int i = 0; i < coordinates.length; i++)
            csv.append(PhoneRideStore.row("GPS", wall + i * 1000, 1000 + i * 1000,
                    coordinates[i][0], coordinates[i][1], 2, 5, .1, 1500, 2));
        store.appendBatch(id, csv.toString(), ""); store.stop(id, wall + coordinates.length * 1000, 1000 + coordinates.length * 1000);
        PhoneRideStore.Track track = store.loadSnapshot(id);
        check(track.points.size() == coordinates.length, "All synthetic fixes accepted");
        return track;
    }

    private void inspectAndNavigate(PhoneRideStore.Track track, int width, int height) {
        AtomicReference<PhoneRideStore.Point> reading = new AtomicReference<>();
        RouteMapView map = map(track, width, height, reading);
        Parent parent = new Parent(getTargetContext()); parent.addView(map);
        float[] start = at(map, track.points.get(5)), end = at(map, track.points.get(35));
        double centerX = number(map, "centerX"), centerY = number(map, "centerY"), zoom = number(map, "zoom");
        float outside = start[1] + ui.dp(75);
        down(map, start[0], start[1] + ui.dp(12));
        check(reading.get() == track.points.get(5), "Near-line press immediately selects a reading");
        check(parent.disallowed, "Route gesture prevents parent page scrolling");
        move(map, end[0], outside);
        check(reading.get() == track.points.get(35), "Drag beyond the line target continues to the nearest route reading");
        unchanged(map, centerX, centerY, zoom, "Route scrubbing never pans or zooms the map");
        move(map, start[0], -ui.dp(30));
        check(reading.get() == track.points.get(5), "Captured drag can reverse beyond the map edge without losing inspection");
        move(map, end[0], outside); up(map, end[0], outside);
        check(reading.get() == track.points.get(35), "Release away from the line preserves the last inspected reading");
        check(!parent.disallowed && number(map, "activePointer") == -1, "Release returns gesture control to the page");

        // A blank-area start stays a pan even when the finger later crosses the route.
        down(map, end[0], outside);
        move(map, start[0], start[1]); up(map, start[0], start[1]);
        check(Math.abs(number(map, "centerX") - centerX) > 1e-10, "Off-route drag pans even after crossing the route");
        check(reading.get() == track.points.get(35), "Panning does not change the inspected reading");
        fit(map);
        down(map, ui.dp(10), ui.dp(10)); up(map, ui.dp(10), ui.dp(10));
        check(reading.get() == null, "Blank map tap still dismisses the reading");

        // A canceled scrub must release interception and leave the next blank drag usable.
        down(map, start[0], start[1]); move(map, end[0], outside); cancel(map, end[0], outside);
        check(!parent.disallowed && !(boolean)field(map, "scrubbingRoute"), "Cancel releases scrub ownership");
        centerX = number(map, "centerX");
        down(map, ui.dp(20), ui.dp(20)); move(map, ui.dp(65), ui.dp(20)); up(map, ui.dp(65), ui.dp(20));
        check(Math.abs(number(map, "centerX") - centerX) > 1e-10, "Normal panning works after canceled inspection");
        fit(map);

        // A second finger explicitly changes from inspection to pinch; lifting it cannot jump.
        down(map, start[0], start[1]);
        float second = start[0] + ui.dp(36);
        event(map, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), new int[]{3, 7}, start[0], start[1], second, start[1]);
        check(!(boolean)field(map, "scrubbingRoute"), "Second finger intentionally switches to map navigation");
        event(map, MotionEvent.ACTION_MOVE, new int[]{3, 7}, start[0] - ui.dp(18), start[1], second + ui.dp(18), start[1]);
        check(number(map, "zoom") > zoom, "Pinch zoom remains usable after starting on the route");
        event(map, MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), new int[]{7, 3}, second + ui.dp(18), start[1], start[0] - ui.dp(18), start[1]);
        centerX = number(map, "centerX"); centerY = number(map, "centerY"); zoom = number(map, "zoom");
        event(map, MotionEvent.ACTION_MOVE, new int[]{7}, second + ui.dp(18), start[1]);
        unchanged(map, centerX, centerY, zoom, "Pointer handoff does not jump after the pinch");
        event(map, MotionEvent.ACTION_MOVE, new int[]{7}, second + ui.dp(40), start[1]);
        check(Math.abs(number(map, "centerX") - centerX) > 1e-10, "Remaining pinch finger stays in navigation instead of recapturing route");
        event(map, MotionEvent.ACTION_UP, new int[]{7}, second + ui.dp(40), start[1]);

        // Opening/closing fullscreen copies the inspection and viewport, not the active gesture.
        RouteMapView expanded = map(track, 725, 600, new AtomicReference<>());
        invoke(expanded, "copyViewStateFrom", new Class[]{RouteMapView.class}, map);
        check(number(expanded, "selected") == number(map, "selected") && number(expanded, "zoom") == number(map, "zoom"), "Expanded view retains inspected point and zoom");
        check(!(boolean)field(expanded, "scrubbingRoute"), "Fullscreen copy does not inherit a held gesture");
        down(map, start[0], start[1]); map.close();
        check(!parent.disallowed && number(map, "activePointer") == -1, "Closing a map releases any active gesture");
        expanded.close();
    }

    private void crossingContinuity(PhoneRideStore.Track track) {
        RouteMapView map = map(track, 400, 350, new AtomicReference<>());
        float[] a = at(map, track.points.get(2)), b = at(map, track.points.get(3));
        down(map, a[0] * .8f + b[0] * .2f, a[1] * .8f + b[1] * .2f);
        move(map, (a[0] + b[0]) / 2, (a[1] + b[1]) / 2);
        check(Math.abs(number(map, "scrubPosition") - 2.5) < .01, "Crossing retains the current pass rather than jumping to an earlier pass");
        float[] earlierA = at(map, track.points.get(0)), earlierB = at(map, track.points.get(1));
        float cx = (a[0] + b[0]) / 2, cy = (a[1] + b[1]) / 2;
        double length = Math.hypot(earlierB[0] - earlierA[0], earlierB[1] - earlierA[1]);
        float ux = (float)((earlierB[0] - earlierA[0]) / length), uy = (float)((earlierB[1] - earlierA[1]) / length);
        for (int direction : new int[]{1, -1}) {
            float px = cx + ux * ui.dp(2) * direction, py = cy + uy * ui.dp(2) * direction;
            check((int)invoke(map, "hitRoute", new Class[]{float.class, float.class}, px, py) < 2,
                    "Offset crossing fixture is geometrically closer to the earlier pass");
            move(map, px, py);
            check(number(map, "scrubPosition") >= 2 && number(map, "scrubPosition") <= 3,
                    "Small finger wobble at a crossing retains the current pass");
        }
        move(map, cx + ux * ui.dp(10), cy + uy * ui.dp(10));
        check(number(map, "scrubPosition") < 1, "A clearly nearer pass escapes crossing hysteresis");
        move(map, a[0] * .8f + b[0] * .2f, a[1] * .8f + b[1] * .2f);
        move(map, a[0] * .2f + b[0] * .8f, a[1] * .2f + b[1] * .8f);
        check(number(map, "selected") == 3, "Dragging through the crossing continues in the finger's direction");
        up(map, b[0], b[1]); map.close();
    }

    private void gapsAndSinglePoint(PhoneRideStore store) {
        try {
            PhoneRideStore.Track track = track(store, "gap", new double[][]{{43.82, -111.78}, {43.82, -111.7798}});
            RouteMapView map = map(track, 400, 300, new AtomicReference<>());
            float[] a = at(map, track.points.get(0)), b = at(map, track.points.get(1));
            set(track.points.get(1), "gapBefore", true);
            down(map, (a[0] + b[0]) / 2, a[1]);
            check(!(boolean)field(map, "scrubbingRoute"), "A missing GPS bridge cannot capture route inspection");
            cancel(map, (a[0] + b[0]) / 2, a[1]);
            down(map, a[0], a[1]); move(map, b[0], b[1] + ui.dp(40));
            check(number(map, "selected") == 1, "Scrubbing across a gap snaps to a real visible endpoint");
            up(map, b[0], b[1]); map.close();
            PhoneRideStore.Track one = track(store, "single", new double[][]{{43.82, -111.78}});
            map = map(one, 344, 290, new AtomicReference<>());
            a = at(map, one.points.get(0)); down(map, a[0], a[1]); move(map, a[0] + ui.dp(100), a[1] + ui.dp(100));
            check(number(map, "selected") == 0, "A single GPS marker remains inspectable while dragging");
            up(map, a[0], a[1]); map.close();
        } catch (Exception error) { throw new RuntimeException(error); }
    }

    private RouteMapView map(PhoneRideStore.Track track, int width, int height, AtomicReference<PhoneRideStore.Point> reading) {
        RouteMapView map = new RouteMapView(ui, track, "https://example.invalid/{z}/{x}/{y}.png", reading::set);
        int w = ui.dp(width), h = ui.dp(height);
        map.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        map.layout(0, 0, w, h); fit(map); return map;
    }
    private void fit(RouteMapView map) { invoke(map, "fit", new Class[0]); }
    private float[] at(RouteMapView map, PhoneRideStore.Point point) {
        return new float[]{((Number)invoke(map, "screenX", new Class[]{PhoneRideStore.Point.class}, point)).floatValue(),
                ((Number)invoke(map, "screenY", new Class[]{PhoneRideStore.Point.class}, point)).floatValue()};
    }
    private void unchanged(RouteMapView map, double x, double y, double zoom, String message) {
        check(Math.abs(number(map, "centerX") - x) < 1e-10 && Math.abs(number(map, "centerY") - y) < 1e-10 && number(map, "zoom") == zoom, message);
    }
    private void down(RouteMapView map, float x, float y) { clock = SystemClock.uptimeMillis(); event(map, MotionEvent.ACTION_DOWN, new int[]{3}, x, y); }
    private void move(RouteMapView map, float x, float y) { event(map, MotionEvent.ACTION_MOVE, new int[]{3}, x, y); }
    private void up(RouteMapView map, float x, float y) { event(map, MotionEvent.ACTION_UP, new int[]{3}, x, y); }
    private void cancel(RouteMapView map, float x, float y) { event(map, MotionEvent.ACTION_CANCEL, new int[]{3}, x, y); }
    private void event(RouteMapView map, int action, int[] ids, float... xy) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[ids.length];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[ids.length];
        for (int i = 0; i < ids.length; i++) {
            properties[i] = new MotionEvent.PointerProperties(); properties[i].id = ids[i]; properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coordinates[i] = new MotionEvent.PointerCoords(); coordinates[i].x = xy[2 * i]; coordinates[i].y = xy[2 * i + 1]; coordinates[i].pressure = 1; coordinates[i].size = 1;
        }
        MotionEvent event = MotionEvent.obtain(clock, clock + 16, action, ids.length, properties, coordinates, 0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try { check(map.dispatchTouchEvent(event), "Route consumes touch " + action); } finally { event.recycle(); }
        clock += 16;
    }
    private final class Parent extends FrameLayout {
        boolean disallowed;
        Parent(Context context) { super(context); }
        @Override public void requestDisallowInterceptTouchEvent(boolean value) { disallowed = value; super.requestDisallowInterceptTouchEvent(value); }
    }
    private static Object field(Object object, String name) {
        try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
        catch (Exception error) { throw new RuntimeException(error); }
    }
    private static void set(Object object, String name, Object value) {
        try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value); }
        catch (Exception error) { throw new RuntimeException(error); }
    }
    private static double number(Object object, String name) { return ((Number)field(object, name)).doubleValue(); }
    private static Object invoke(Object object, String name, Class<?>[] types, Object... args) {
        try { Method method = object.getClass().getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(object, args); }
        catch (Exception error) { throw new RuntimeException(error); }
    }
    private void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static void remove(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles(); if (children != null) for (File child : children) remove(child);
        file.delete();
    }
}
