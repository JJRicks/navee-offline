package local.navee.offlinelock.tests;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import local.navee.offlinelock.BleController;
import local.navee.offlinelock.MainActivity;
import local.navee.offlinelock.OnboardingScreen;
import local.navee.offlinelock.PhoneRideStore;
import local.navee.offlinelock.RidesScreen;
import local.navee.offlinelock.RouteMapView;
import local.navee.offlinelock.ScooterCommands;
import local.navee.offlinelock.ScooterService;
import local.navee.offlinelock.Ui;

/** UI checks on a fresh emulator only. No owner profile, sensor recording, radio or network work. */
public final class UiOrganizationValidation extends Instrumentation {
    private static final String[] PREFS = {"app_settings", "profiles", "scooter", "pending_documents", "onboarding"};
    private final List<String> checks = new ArrayList<>(), screenshots = new ArrayList<>();
    private Bundle arguments;
    private MainActivity activity;
    private boolean admitted;
    private File fixtureDirectory;
    private AlertDialog preview;

    @Override public void onCreate(Bundle args) { arguments = args == null ? new Bundle() : args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            requireFreshEmulator(); admitted = true;
            SharedPreferences settings = settings();
            require(settings.edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                    .putString("theme", "dark").putString("more_section", "App").commit(), "Cannot set emulator UI preferences");
            // These runners check the tabs, so skip the first-run onboarding a fresh install shows.
            OnboardingScreen.markDone(getTargetContext());
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.service() != null && root().isAttachedToWindow() && root().getWidth() > 0, 6000,
                    "Local workspace did not initialize");
            for (String name : new String[]{"Home", "Scooter", "Stats", "Rides", "More"}) {
                clickDescription(name); waitForIdleSync(); assertNoScooterWork(); assertNavigationFits();
            }
            requireScreenText("App preferences");
            check("All five destinations are reachable; navigation targets remain visible");
            screenshot("more-index-dark");

            clickDescription("Scooter");
            requireScreenText("RIDE"); requireScreenText("BATTERY"); requireScreenText("PROFILE");
            requireVisibleControl("start_speed"); requireVisibleControl("energy_recovery");
            main(() -> {
                require(topSpeedShown(), "Top speed card missing from the Scooter page");
                require(!allFeatures().contains("maximum_speed"), "Maximum speed must live only in the top speed card");
                for (String key : visibleFeatures()) require(!key.startsWith("line_") && !key.startsWith("star_"), "Line and star zones belong to the Lights page");
                require(findText(root(), "Line & star lights") != null, "Lights group must link to the line and star zones");
            });
            screenshot("scooter-dark");
            main(() -> first(root(), EditText.class).setText("speed"));
            main(() -> {
                Set<String> visible = visibleFeatures();
                require(topSpeedShown() && visible.contains("start_speed"), "Speed search lost primary speed controls");
                require(!visible.contains("energy_recovery"), "Speed search did not hide an unrelated setting");
                for (String key : visible) require(!isLight(key), "Speed search should hide lighting settings");
                require(findText(root(), "LIGHTS") == null, "Speed search should hide the empty Lights group");
            });
            main(() -> invoke(activity, "navigateBack"));
            main(() -> require(topSpeedShown() && "".equals(field(field(activity, "screen"), "query")), "Back did not clear the settings search"));
            requireScreenText("BATTERY");
            check("Scooter page shows the top speed card and every group; search hides empty groups and Back clears it without control writes");

            main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "lights")); waitForIdleSync();
            main(() -> {
                Set<String> expected = new HashSet<>(Arrays.asList("headlight", "auto_sensor", "line_light", "line_pattern",
                        "line_color", "line_boost_color", "star_light", "star_pattern", "star_color", "star_boost_color"));
                require(allFeatures().containsAll(expected), "Scooter must retain all lighting settings");
                require(visibleFeatures().equals(expected), "A lighting zone is hidden without a search");
                require(!topSpeedShown(), "The Lights page should not show the top speed card");
                require(findText(root(), "HEADLIGHTS") != null && findText(root(), "LINE LIGHTS") != null
                        && findText(root(), "STAR LIGHTS") != null, "Lights page groups are missing");
                require(findDescription(root(), "Back to Scooter settings") != null, "Lights page has no way back to Scooter");
            });
            screenshot("lights-dark");
            main(() -> first(root(), EditText.class).setText("boost"));
            main(() -> require(visibleFeatures().equals(new HashSet<>(Arrays.asList("line_boost_color", "star_boost_color", "parking_assist"))),
                    "Global scooter search should find both Boost colors and Power Assist"));
            main(() -> invoke(activity, "navigateBack"));
            main(() -> invoke(activity, "navigateBack"));
            main(() -> require(topSpeedShown() && findText(root(), "LINE LIGHTS") == null && settings.getString("scooter_section", "").isEmpty(),
                    "Back from Lights did not return to the Scooter page"));
            check("Lights page groups headlights, line and star zones; search covers all settings; Back returns to Scooter");

            clickDescription("More"); clickText(root(), "App preferences");
            requireScreenText("Connect automatically"); screenshot("preferences-dark");
            main(() -> invoke(activity, "navigateBack"));
            if (wideMore()) {
                // Wide menu-submenu layout: Back leaves the tab like Home; the menu is there on return.
                main(() -> require("home".equals(field(activity, "selectedPage")), "Wide More Back did not leave the tab"));
                clickDescription("More");
            }
            requireScreenText("My scooters");
            check("More opens preferences; Back returns to its settings index (phone) or leaves the tab beside its menu (wide)");
            verifyMoreReturn(settings);

            clickDescription("Stats");
            // Wide Stats keeps Readings in its own pane beside the sections, without a Readings tab.
            if (!Boolean.TRUE.equals(field(field(activity, "screen"), "wide"))) clickText(root(), "Readings");
            Object stats = field(activity, "screen");
            @SuppressWarnings("unchecked") Map<String, Object> readings = (Map<String, Object>) field(stats, "rows");
            View voltage = (View) field(readings.get("voltage_v"), "layout"); click(voltage);
            AlertDialog details = (AlertDialog) field(stats, "metricDialog");
            require(details != null && details.isShowing(), "Reading source details did not open");
            click(details.getButton(AlertDialog.BUTTON_NEUTRAL));
            require(settings.getStringSet("stats_favourites", new HashSet<>()).contains("voltage_v"), "Favourite action was not saved");
            screenshot("stats-readings-dark");
            check("Reading detail opens on tap; favourite action remains available without repeated source labels");

            main(() -> settings.edit().putString("theme", "light").putFloat("text_scale", 1.4f).apply());
            await(() -> !activity.ui().dark && activity.ui().textScale > 1.3f, 4000, "Large light theme did not apply");
            clickDescription("Scooter"); assertNavigationFits(); screenshot("scooter-large-light");
            main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "lights")); waitForIdleSync(); assertNavigationFits(); screenshot("lights-large-light");
            clickDescription("More"); clickText(root(), "App preferences"); screenshot("preferences-large-light");
            check("Light theme and 140% app text preserve navigation and grouped settings");
            main(() -> settings.edit().putString("theme", "dark").putFloat("text_scale", 1f).apply());
            await(() -> activity.ui().dark && activity.ui().textScale == 1f, 4000, "Dark theme did not restore");

            PhoneRideStore.Track fixture = syntheticTrack();
            require(fixture.locationCount >= 30 && fixture.maxGpsAccelerationMps2 != null, "Synthetic UI fixture did not load");
            clickDescription("Rides");
            Object rides = field(activity, "screen");
            main(() -> {
                settings.edit().putBoolean("map_basemap_enabled", false).commit();
                invoke(rides, "presentPhoneRide", new Class<?>[]{PhoneRideStore.Track.class}, fixture);
                @SuppressWarnings("unchecked") List<AlertDialog> dialogs = (List<AlertDialog>) field(rides, "dialogs");
                preview = dialogs.get(dialogs.size() - 1);
                preview.setTitle("Synthetic UI preview · Route");
            });
            await(() -> preview.isShowing() && first(preview.getWindow().getDecorView(), RouteMapView.class).getWidth() > 0,
                    4000, "Route preview did not attach");
            View dialogRoot = preview.getWindow().getDecorView();
            RouteMapView map = first(dialogRoot, RouteMapView.class);
            require(!((Boolean)field(map, "streets")), "Explicitly disabled basemap must remain offline");
            verifyMapSources(map);
            reveal(map);
            main(() -> {
                long now = SystemClock.uptimeMillis();
                MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, map.getWidth() / 2f, map.getHeight() / 2f, 0);
                MotionEvent up = MotionEvent.obtain(now, now + 25, MotionEvent.ACTION_UP, map.getWidth() / 2f, map.getHeight() / 2f, 0);
                try { map.dispatchTouchEvent(down); map.dispatchTouchEvent(up); } finally { down.recycle(); up.recycle(); }
                require(findTextContaining(dialogRoot, "Power ") != null, "Tapping the route did not expose synchronized readings");
            });
            verifyMapGestures(map);
            clickText(dialogRoot, "Grade"); clickText(dialogRoot, "Accel"); clickText(dialogRoot, "Power");
            clickDescription(dialogRoot, "Zoom in"); clickDescription(dialogRoot, "Zoom out"); clickDescription(dialogRoot, "Fit route");
            ScrollView scroller = first(dialogRoot, ScrollView.class);
            main(() -> scroller.fullScroll(View.FOCUS_UP)); waitForIdleSync();
            screenshot("synthetic-route-top-dark");
            main(() -> scroller.fullScroll(View.FOCUS_DOWN)); waitForIdleSync();
            TextView export = findText(dialogRoot, "Export phone data");
            require(export != null, "Route export action is missing"); reveal(export); requireFullyVisible(export, "Phone export action");
            screenshot("synthetic-route-bottom-dark");
            main(() -> {
                require(!((Boolean)field(map, "streets")), "Preview enabled network tile loading");
                require(((Set<?>)field(map, "pending")).isEmpty(), "Offline preview queued a tile download");
            });
            click(preview.getButton(AlertDialog.BUTTON_POSITIVE)); preview = null;
            assertNoScooterWork();
            check("Synthetic route renders offline, responds to touch/zoom/color controls, and scrolls to phone exports");

            result.putString("result", "PASS"); result.putStringArrayList("checks", new ArrayList<>(checks));
            result.putStringArrayList("screenshots", new ArrayList<>(screenshots));
            result.putInt("check_count", checks.size()); result.putInt("scooter_mutations", 0);
            result.putInt("width_dp", getTargetContext().getResources().getConfiguration().screenWidthDp);
            result.putBoolean("hardware_sensor_claims", false); result.putBoolean("street_tiles_enabled", false);
            cleanup(); finish(0, result);
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", error.getClass().getSimpleName() + ": " + error.getMessage());
            result.putStringArrayList("completed_checks", new ArrayList<>(checks));
            result.putStringArrayList("screenshots", new ArrayList<>(screenshots));
            try { if (admitted && activity != null && !activity.isDestroyed()) screenshot("failure"); } catch (Throwable ignored) { }
            try { cleanup(); } catch (Throwable failure) { result.putString("cleanup_error", failure.toString()); }
            finish(1, result);
        }
    }

    private void verifyMapGestures(RouteMapView map) {
        main(() -> {
            AtomicInteger taps = new AtomicInteger(); map.setOnClickListener(v -> taps.incrementAndGet());
            try {
                long now = SystemClock.uptimeMillis();
                float cx = map.getWidth()*.6f, cy = map.getHeight()*.45f;
                float half = Math.min(map.getWidth()*.12f, map.getHeight()*.1f);
                float dx = activity.ui().dp(13), dy = activity.ui().dp(9);
                double oldZoom = number(map, "zoom");
                double anchorX = mapCoordinate(map, cx, true), anchorY = mapCoordinate(map, cy, false);
                mapTouch(map, now, 0, MotionEvent.ACTION_DOWN, new int[]{3}, cx-half,cy);
                mapTouch(map, now, 1, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                        new int[]{3,7}, cx-half,cy,cx+half,cy);
                float fx = cx+dx, fy = cy+dy, spread = half*1.4f;
                mapTouch(map, now, 2, MotionEvent.ACTION_MOVE, new int[]{3,7}, fx-spread,fy,fx+spread,fy);
                double nextZoom = number(map, "zoom");
                require(Math.abs(nextZoom-(oldZoom+Math.log(1.4)/Math.log(2))) < .00001,
                        "Pinch did not produce smooth fractional zoom");
                require(Math.abs(nextZoom-Math.rint(nextZoom)) > .05, "Pinch snapped to a whole zoom level");
                assertMapAnchor(map, fx, fy, anchorX, anchorY, "Pinch focal point drifted");

                // Reorder pointer indices while moving the centroid, with their separation unchanged.
                fx += dx; fy -= dy;
                mapTouch(map, now, 3, MotionEvent.ACTION_MOVE, new int[]{7,3}, fx+spread,fy,fx-spread,fy);
                require(Math.abs(number(map, "zoom")-nextZoom) < .00001, "Two-finger pan changed zoom");
                assertMapAnchor(map, fx, fy, anchorX, anchorY, "Two-finger pan lost its anchor");
                double beforeX = number(map,"centerX"), beforeY = number(map,"centerY");
                // Lift pointer 3 and continue with pointer 7 at its own coordinates.
                mapTouch(map, now, 4, MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                        new int[]{7,3}, fx+spread,fy,fx-spread,fy);
                mapTouch(map, now, 5, MotionEvent.ACTION_MOVE, new int[]{7}, fx+spread,fy);
                require(Math.abs(number(map,"centerX")-beforeX) < 1e-10 && Math.abs(number(map,"centerY")-beforeY) < 1e-10,
                        "Map jumped when the first finger lifted");
                double handoffX = mapCoordinate(map, fx+spread, true), handoffY = mapCoordinate(map, fy, false);
                mapTouch(map, now, 6, MotionEvent.ACTION_MOVE, new int[]{7}, fx+spread+dx,fy+dy);
                assertMapAnchor(map, fx+spread+dx, fy+dy, handoffX, handoffY, "Remaining finger did not pan naturally");
                mapTouch(map, now, 7, MotionEvent.ACTION_UP, new int[]{7}, fx+spread+dx,fy+dy);
                require(taps.get() == 0, "Pinching or handing off a pointer selected a route point");
                require(((Integer)field(map,"activePointer")) == -1, "Final pointer-up did not end the gesture");

                map.zoomBy(100); pinchAtBound(map, now+200, cx, cy, half, 1.5f, 19);
                map.zoomBy(-100); pinchAtBound(map, now+400, cx, cy, half, .6f, 2);
                require(taps.get() == 0, "A clamped pinch selected a route point");

                // Cancellation must leave the next one-finger tap usable and keep small jitter still.
                mapTouch(map, now+600, 0, MotionEvent.ACTION_DOWN, new int[]{3}, cx-half,cy);
                mapTouch(map, now+600, 1, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                        new int[]{3,7}, cx-half,cy,cx+half,cy);
                mapTouch(map, now+600, 2, MotionEvent.ACTION_CANCEL, new int[]{3,7}, cx-half,cy,cx+half,cy);
                require(((Integer)field(map,"activePointer")) == -1, "Cancel did not release the active pointer");
                beforeX = number(map,"centerX"); beforeY = number(map,"centerY");
                mapTouch(map, now+800, 0, MotionEvent.ACTION_DOWN, new int[]{4}, cx,cy);
                mapTouch(map, now+800, 1, MotionEvent.ACTION_MOVE, new int[]{4}, cx+1,cy+1);
                mapTouch(map, now+800, 2, MotionEvent.ACTION_UP, new int[]{4}, cx+1,cy+1);
                require(taps.get() == 1, "A normal tap stopped selecting after a canceled pinch");
                require(Math.abs(number(map,"centerX")-beforeX) < 1e-10 && Math.abs(number(map,"centerY")-beforeY) < 1e-10,
                        "Tap jitter moved the map before touch slop");
                require(!((Boolean)field(map,"streets")) && ((Set<?>)field(map,"pending")).isEmpty(),
                        "Offline gestures enabled map tile requests");
            } finally { map.setOnClickListener(null); map.fitRoute(); }
        }); waitForIdleSync();
        check("Pinch zoom and two-finger pan keep their focal point, pointer handoff stays steady, bounds clamp, and canceled pinches preserve taps");
    }
    private void pinchAtBound(RouteMapView map, long now, float x, float y, float half, float factor, double expected) {
        mapTouch(map, now, 0, MotionEvent.ACTION_DOWN, new int[]{3}, x-half,y);
        mapTouch(map, now, 1, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                new int[]{3,7}, x-half,y,x+half,y);
        mapTouch(map, now, 2, MotionEvent.ACTION_MOVE, new int[]{3,7}, x-half*factor,y,x+half*factor,y);
        require(number(map,"zoom") == expected, "Pinch exceeded the zoom bound " + expected);
        mapTouch(map, now, 3, MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                new int[]{3,7}, x-half*factor,y,x+half*factor,y);
        mapTouch(map, now, 4, MotionEvent.ACTION_UP, new int[]{3}, x-half*factor,y);
    }
    private static double number(Object object, String name) { return ((Number)field(object,name)).doubleValue(); }
    private static double mapCoordinate(RouteMapView map, float pixel, boolean horizontal) {
        return number(map, horizontal ? "centerX" : "centerY")
                + (pixel-(horizontal ? map.getWidth() : map.getHeight())/2.0)/((Number)invoke(map,"world")).doubleValue();
    }
    private static void assertMapAnchor(RouteMapView map, float x, float y, double expectedX, double expectedY, String message) {
        double dx = mapCoordinate(map,x,true)-expectedX; dx -= Math.rint(dx);
        double dy = mapCoordinate(map,y,false)-expectedY;
        double world = ((Number)invoke(map,"world")).doubleValue();
        require(Math.abs(dx)*world < .01 && Math.abs(dy)*world < .01, message);
    }
    private static void mapTouch(RouteMapView map, long down, int step, int action, int[] ids, float... xy) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[ids.length];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[ids.length];
        for (int i=0; i<ids.length; i++) {
            properties[i] = new MotionEvent.PointerProperties(); properties[i].id = ids[i]; properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coordinates[i] = new MotionEvent.PointerCoords(); coordinates[i].x = xy[2*i]; coordinates[i].y = xy[2*i+1];
            coordinates[i].pressure = 1; coordinates[i].size = 1;
        }
        MotionEvent event = MotionEvent.obtain(down,down+step*16L,action,ids.length,properties,coordinates,0,0,1,1,0,0,
                android.view.InputDevice.SOURCE_TOUCHSCREEN,0);
        try { require(map.dispatchTouchEvent(event), "Map did not consume touch action " + action); }
        finally { event.recycle(); }
    }

    private void verifyMapSources(RouteMapView map) throws Exception {
        String usgs = "https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/tile/{z}/{y}/{x}";
        String osm = "https://tile.openstreetmap.org/{z}/{x}/{y}.png";
        main(() -> {
            map.setSource(osm);
            require(((Integer)field(map, "maximumTileZoom")) == 19, "OpenStreetMap zoom limit");
            require(!((Boolean)field(map, "streets")), "Source changes preserve offline mode");
            map.setSource(usgs);
            require(((Integer)field(map, "maximumTileZoom")) == 16, "USGS zoom limit");
        });

        // Hold both existing workers so the old request can be canceled before any network work.
        ExecutorService executor = (ExecutorService)field(map, "TILES");
        CountDownLatch occupied = new CountDownLatch(2), releaseOne = new CountDownLatch(1), releaseTwo = new CountDownLatch(1);
        Future<?> firstWorker = executor.submit(() -> waitForWorker(occupied, releaseOne));
        Future<?> secondWorker = executor.submit(() -> waitForWorker(occupied, releaseTwo));
        String key = "2/1/1";
        try {
            require(occupied.await(5, TimeUnit.SECONDS), "Tile workers did not reach the queue fixture");
            main(() -> {
                map.setStreets(true);
                invoke(map, "request", new Class<?>[]{String.class, int.class, int.class, int.class}, key, 2, 1, 1);
                require(((Set<?>)field(map, "pending")).contains(key), "Old-provider request was not queued");
                map.setSource(usgs); map.setStreets(false);
                @SuppressWarnings("unchecked") Set<String> pending = (Set<String>)field(map, "pending");
                pending.add(key); // Stand in for the new source's matching key while keeping network loading off.
            });
            Future<?> drained = executor.submit(() -> {});
            releaseOne.countDown(); drained.get(5, TimeUnit.SECONDS); waitForIdleSync();
            main(() -> {
                require(((Set<?>)field(map, "pending")).contains(key), "A stale provider callback cleared the new source's pending tile");
                require(((android.util.LruCache<?,?>)field(map, "tiles")).size() == 0, "Stale provider supplied a tile");
                require(((Map<?,?>)field(map, "failed")).isEmpty(), "Stale provider supplied a failure");
            });
        } finally {
            main(() -> { map.setStreets(false); map.setSource(usgs); });
            releaseOne.countDown(); releaseTwo.countDown();
            firstWorker.get(5, TimeUnit.SECONDS); secondWorker.get(5, TimeUnit.SECONDS);
        }
        main(() -> require(((Set<?>)field(map, "pending")).isEmpty(), "Offline source has no queued tiles"));
        check("USGS and OpenStreetMap preserve offline loading; stale queued callbacks cannot alter the replacement source");
    }
    private static void waitForWorker(CountDownLatch occupied, CountDownLatch release) {
        occupied.countDown();
        try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Tile queue fixture timed out"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
    }
    private void verifyMoreReturn(SharedPreferences settings) {
        clickText(root(), "App preferences");
        clickDescription("Home"); clickDescription("More");
        requireScreenText("Connect automatically");
        main(() -> activity.rebuild()); waitForIdleSync();
        requireScreenText("Connect automatically");
        main(() -> {
            require(!settings.contains("more_away_elapsed"), "Active More must not have an absence timer");
            invoke(activity, "expireMoreSection", new Class<?>[]{boolean.class, long.class}, true, Long.MAX_VALUE);
        });
        requireScreenText("Connect automatically");
        require(settings.getString("more_section", "").equals("App"), "Reading a submenu must not expire it");

        clickDescription("Home");
        long left = settings.getLong("more_away_elapsed", -1);
        require(left >= 0, "Leaving More did not start its retention timer");
        main(() -> {
            require(!(Boolean) invoke(activity, "expireMoreSection", new Class<?>[]{boolean.class, long.class}, true, left + 59_999),
                    "More expired before one minute");
            require((Boolean) invoke(activity, "expireMoreSection", new Class<?>[]{boolean.class, long.class}, true, left + 60_000),
                    "More did not expire at one minute");
        });
        clickDescription("More"); requireScreenText("App preferences");
        require(settings.getString("more_section", "").isEmpty(), "Expired More did not return to its index");
        check("More retains brief tab returns and rebuilds, stays open while active, and expires at exactly one minute away");

        clickText(root(), "App preferences");
        main(() -> callActivityOnPause(activity));
        require(settings.contains("more_away_elapsed"), "Pausing on More did not start retention");
        main(() -> callActivityOnResume(activity)); waitForIdleSync();
        requireScreenText("Connect automatically");
        require(!settings.contains("more_away_elapsed"), "Resuming More did not end the absence");

        // A different boot invalidates a persisted monotonic timestamp without a long test sleep.
        main(() -> {
            callActivityOnPause(activity);
            settings.edit().putInt("more_away_boot", -2).commit();
            callActivityOnResume(activity);
        }); waitForIdleSync();
        requireScreenText("App preferences");
        require(settings.getString("more_section", "").isEmpty(), "Expired background return left the old submenu visible");

        clickText(root(), "App preferences"); clickDescription("Home");
        long originalLeft = settings.getLong("more_away_elapsed", -1);
        main(() -> { callActivityOnPause(activity); callActivityOnResume(activity); });
        require(settings.getLong("more_away_elapsed", -2) == originalLeft, "Backgrounding another tab extended More's absence timer");
        main(() -> {
            settings.edit().putInt("more_away_boot", -2).commit();
            invoke(activity, "openMoreSection", new Class<?>[]{String.class}, "Security");
        }); waitForIdleSync();
        require(settings.getString("more_section", "").equals("Security"), "Security shortcut was replaced by an expired More index");
        requireScreenText("Handlebar PIN");
        main(() -> invoke(activity, "navigateBack"));
        if (wideMore()) main(() -> require("home".equals(field(activity, "selectedPage"))
                && settings.getString("more_section", "").equals("Security"), "Wide More Back must leave the tab and keep its submenu"));
        else requireScreenText("App preferences");
        check("More handles brief and expired background returns, preserves an earlier tab-away time, and honors the Security shortcut");
    }

    private void requireFreshEmulator() {
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
        require(hardware.contains("ranchu") || hardware.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"),
                "UI validation refuses physical phones");
        require(Build.VERSION.SDK_INT >= 31, "Use an Android 12 or newer emulator");
        for (String key : PREFS) require(getTargetContext().getSharedPreferences(key, Context.MODE_PRIVATE).getAll().isEmpty(),
                "Fresh emulator app data required; existing preferences refused");
        File[] files = getTargetContext().getFilesDir().listFiles();
        require(files != null && files.length == 0, "Fresh emulator app data required; existing private files refused");
        require(getTargetContext().checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && getTargetContext().checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED,
                "Grant Nearby devices externally on the fresh emulator before this runner");
        BluetoothManager manager = getTargetContext().getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        require(adapter == null || !adapter.isEnabled(), "Bluetooth must be off or unavailable; this runner never changes radio state");
    }
    private void assertNoScooterWork() {
        main(() -> {
            ScooterService s = activity.service(); require(s != null, "Scooter workspace disappeared");
            require(s.profiles.all().isEmpty() && s.profiles.active() == null, "Unexpected owner profile");
            require(!s.isRecording() && s.ble.state() == BleController.State.DISCONNECTED, "Unexpected recording or connection work");
            require(((Number)field(s.ble, "mutationSequence")).longValue() == 0 && s.ble.commandResults().isEmpty(), "Unexpected scooter write");
            try { require(s.rides.list().isEmpty(), "UI preview must not create real ride history"); }
            catch (java.io.IOException e) { throw new IllegalStateException(e); }
        });
    }
    private PhoneRideStore.Track syntheticTrack() throws Exception {
        fixtureDirectory = new File(getTargetContext().getCacheDir(), "ui-synthetic-" + SystemClock.elapsedRealtime());
        require(fixtureDirectory.mkdir(), "Could not create isolated UI fixture");
        PhoneRideStore store = new PhoneRideStore(fixtureDirectory);
        String id = "synthetic-ui-preview"; long start = 100000, wall = 1700000000000L;
        store.start(id, wall, start, "pocket"); StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 70; i++) {
            long mono = start + i * 1000, stamp = wall + i * 1000;
            if (i == 36) rows.append(PhoneRideStore.row("GAP", stamp, mono));
            double speed = 6 + Math.sin(i / 5.0), power = 900 + 600 * Math.sin(i / 12.0);
            rows.append(PhoneRideStore.row("PRESSURE", stamp, mono, 1000 - i * .03));
            rows.append(PhoneRideStore.row("TELEMETRY", stamp, mono, speed * 3.6, power, 95 - i * .03, mono, mono, mono));
            rows.append(PhoneRideStore.row("GPS", stamp, mono, 0.1 + i * .000045, 0.1 + Math.sin(i / 12.0) * .0001,
                    3, speed, .2, 120 + i * .25, 4));
        }
        rows.append(PhoneRideStore.row("MOTION", wall + 69000, start + 69000, 500, 2.8));
        store.appendBatch(id, rows.toString(), ""); store.stop(id, wall + 70000, start + 70000);
        return store.load(id);
    }
    private void assertNavigationFits() {
        main(() -> {
            for (String name : new String[]{"Home", "Scooter", "Stats", "Rides", "More"}) {
                View item = findDescription(root(), name); require(item != null && item.isShown(), "Missing navigation " + name);
                requireFullyVisibleNow(item, "Navigation " + name);
                TextView label = first(item, TextView.class);
                if (label != null) {
                    // The expanded side rail keeps text captions.
                    require(label.getLayout() != null, "Navigation caption did not lay out");
                    require(label.getLayout().getHeight() <= label.getHeight() + 2, "Navigation caption clipped vertically: " + name);
                } else {
                    // The compact floating tab bar is icon-only; each target must still be 48dp.
                    int minimum = Math.round(48 * item.getResources().getDisplayMetrics().density);
                    require(item.getWidth() >= minimum && item.getHeight() >= minimum, "Navigation target smaller than 48dp: " + name);
                }
            }
        });
    }
    private boolean topSpeedShown() { return ((View)field(field(field(activity, "screen"), "topSpeed"), "card")).isShown(); }
    private void requireVisibleControl(String key) { main(() -> require(visibleFeatures().contains(key), "Missing visible control " + key)); }
    private Set<String> allFeatures() { return features(false); }
    private Set<String> visibleFeatures() { return features(true); }
    private Set<String> features(boolean visible) {
        Set<String> keys = new HashSet<>(); Object screen = field(activity, "screen");
        for (Object row : (List<?>)field(screen, "rows")) {
            View card = (View)field(row, "card");
            if (!visible || card.isShown()) keys.add(((ScooterCommands.Feature)field(row, "feature")).key);
        }
        return keys;
    }
    private static boolean isLight(String key) { return key.startsWith("line_") || key.startsWith("star_") || key.equals("headlight") || key.equals("auto_sensor"); }
    private void clickDescription(String description) { View view = findDescription(root(), description); require(view != null, "Missing navigation " + description); click(view); }
    private void clickDescription(View tree, String description) { View view = findDescription(tree, description); require(view != null, "Missing control " + description); click(view); }
    private void clickText(View tree, String text) { TextView view = findText(tree, text); require(view != null, "Missing UI label " + text); click(view); }
    private void requireScreenText(String text) { main(() -> require(findText(root(), text) != null, "Expected screen label " + text)); }
    private void click(View target) {
        View actionable = target;
        while (!actionable.isClickable() && actionable.getParent() instanceof View) actionable = (View)actionable.getParent();
        final View button = actionable;
        require(button.isClickable() && button.isEnabled(), "View is not an enabled action: " + target.getContentDescription());
        reveal(button); main(() -> { require(button.performClick(), "Action did not dispatch"); }); waitForIdleSync();
    }
    private void reveal(View view) {
        main(() -> view.requestRectangleOnScreen(new Rect(0, 0, view.getWidth(), view.getHeight()), true));
        waitForIdleSync();
    }
    /** More shows its menu and submenu side by side (no submenu Back step) at unfolded widths. */
    private boolean wideMore() { return activity.getResources().getConfiguration().screenWidthDp >= 650; }
    private void requireFullyVisible(View view, String name) { main(() -> requireFullyVisibleNow(view, name)); }
    private void requireFullyVisibleNow(View view, String name) {
        Rect rect = new Rect(); require(view.getGlobalVisibleRect(rect), name + " is not visible");
        require(rect.width() >= view.getWidth() - 2 && rect.height() >= view.getHeight() - 2, name + " is clipped");
    }
    private View root() { return (View)field(activity, "root"); }
    private SharedPreferences settings() { return getTargetContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE); }
    private void screenshot(String name) throws Exception {
        waitForIdleSync(); SystemClock.sleep(120);
        Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "Emulator screenshot unavailable");
        String variant = arguments.getString("variant", "ui").replaceAll("[^A-Za-z0-9_-]", "");
        File directory = new File(getTargetContext().getExternalFilesDir(null), "ui-validation");
        require(directory.isDirectory() || directory.mkdirs(), "Could not create screenshot directory");
        File output = new File(directory, variant + "-" + name + ".png");
        try (FileOutputStream stream = new FileOutputStream(output)) { require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream), "Screenshot encoding failed"); }
        finally { bitmap.recycle(); }
        screenshots.add(output.getAbsolutePath());
    }
    private void cleanup() {
        if (!admitted) return;
        if (activity != null && !activity.isDestroyed()) {
            main(() -> { if (preview != null) preview.dismiss(); activity.finish(); });
            await(activity::isDestroyed, 4000, "UI activity did not finish");
        }
        main(() -> { ScooterService.setUiVisible(false); getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); });
        waitForIdleSync();
        for (String name : PREFS) getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit();
        // Delete only our dedicated synthetic cache fixture. Real ride storage is never touched.
        if (fixtureDirectory != null) { File[] files = fixtureDirectory.listFiles(); if (files != null) for (File file : files) file.delete(); fixtureDirectory.delete(); }
        admitted = false;
    }
    private void await(BooleanSupplier condition, long timeout, String message) {
        long until = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < until) {
            AtomicReference<Boolean> found = new AtomicReference<>(false); main(() -> found.set(condition.getAsBoolean()));
            if (found.get()) return; SystemClock.sleep(40);
        }
        throw new IllegalStateException(message);
    }
    private void main(Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new IllegalStateException(failure.get().getMessage(), failure.get());
    }
    private static Object field(Object value, String name) {
        if (value == null) throw new IllegalStateException("Null object while reading " + name);
        try { Field field = value.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(value); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("Cannot inspect " + name, e); }
    }
    private static Object invoke(Object value, String name) { return invoke(value, name, new Class<?>[0]); }
    private static Object invoke(Object value, String name, Class<?>[] signature, Object... args) {
        try { Method method = value.getClass().getDeclaredMethod(name, signature); method.setAccessible(true); return method.invoke(value, args); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("Cannot invoke " + name + ": " + e.getCause(), e); }
    }
    private static View findDescription(View root, String description) {
        if (root.isShown() && description.contentEquals(root.getContentDescription() == null ? "" : root.getContentDescription()) && root.isClickable()) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)root).getChildCount(); i++) { View found = findDescription(((ViewGroup)root).getChildAt(i), description); if (found != null) return found; }
        return null;
    }
    private static TextView findText(View root, String text) {
        if (root.isShown() && root instanceof TextView && text.contentEquals(((TextView)root).getText())) return (TextView)root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)root).getChildCount(); i++) { TextView found = findText(((ViewGroup)root).getChildAt(i), text); if (found != null) return found; }
        return null;
    }
    private static TextView findTextContaining(View root, String text) {
        if (root.isShown() && root instanceof TextView && ((TextView)root).getText().toString().contains(text)) return (TextView)root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)root).getChildCount(); i++) { TextView found = findTextContaining(((ViewGroup)root).getChildAt(i), text); if (found != null) return found; }
        return null;
    }
    private static TextView findTextIncludingHidden(View root, String text) {
        if (root instanceof TextView && text.contentEquals(((TextView)root).getText())) return (TextView)root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)root).getChildCount(); i++) {
            TextView found = findTextIncludingHidden(((ViewGroup)root).getChildAt(i), text); if (found != null) return found;
        }
        return null;
    }
    private static <T> T first(View root, Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)root).getChildCount(); i++) { T found = first(((ViewGroup)root).getChildAt(i), type); if (found != null) return found; }
        return null;
    }
    private void check(String text) { checks.add(text); Bundle status = new Bundle(); status.putString("check", text); sendStatus(0, status); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
