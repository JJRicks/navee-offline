package local.navee.offlinelock.tests;

import android.Manifest;
import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import local.navee.offlinelock.BleController;
import local.navee.offlinelock.DashboardConfig;
import local.navee.offlinelock.DashboardLayout;
import local.navee.offlinelock.DashboardScreen;
import local.navee.offlinelock.MainActivity;
import local.navee.offlinelock.ScooterProfiles;
import local.navee.offlinelock.ScooterService;
import local.navee.offlinelock.TelemetryHistory;
import local.navee.offlinelock.Ui;

/**
 * Isolated native measurement regressions for arbitrary window sizes and OEM fold callbacks.
 * Refuses physical devices and existing app data. READY is a view-only fixture; CONNECTING
 * temporarily changes only the display state, restoring it before the UI thread yields. No
 * real GATT, authentication, recording, or scooter command runs; quick-action checks use only
 * the memory-only simulator for local control taps.
 */
public final class ResponsiveDashboardValidation extends Instrumentation {
    private Bundle arguments;
    private MainActivity activity;
    private boolean admitted;
    /** Fresh Home: Lock bar, Scooter overview, Reading tiles, Setting tiles, Ride recorder. */
    private static final String[] DEFAULT_STACK = {"lockbar", "overview", "tiles", "shortcuts", "ride"};
    private final ArrayList<String> checks = new ArrayList<>(), screenshots = new ArrayList<>();
    private final ArrayList<String> overlayGeometry = new ArrayList<>();

    @Override public void onCreate(Bundle args) { arguments = args == null ? new Bundle() : args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            admit(); admitted = true;
            require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                    .putString("theme", "dark").putString("units", "mi")
                    .putFloat("text_scale", Float.parseFloat(arguments.getString("text_scale", "1"))).commit(), "Fixture settings failed");
            seed();
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.service() != null && field(activity, "screen") instanceof DashboardScreen
                    && grid().getWidth() > 0, "Dashboard did not launch");
            await(() -> activity.service().displayReadings().containsKey("odometer_km"), "Cached fixture did not load");
            stopUiPulse(); settle();
            String mode = arguments.getString("mode", "all");
            if (mode.equals("example")) {
                verifyAllWidgetsExample();
            } else if (mode.equals("compact")) {
                verifyCompactReadingHeights();
            } else if (mode.equals("narrow")) {
                verifyMinimumWidthContents();
            } else if (mode.equals("chart")) {
                verifyChartAxes();
            } else if (mode.equals("followup")) {
                verifyDashboardFollowup();
            } else if (mode.equals("gauge")) {
                verifyGaugeContent();
                check("270-degree gauges keep live/dual/unknown/stale/long readings and endpoint labels inside narrow/wide and short/tall cards");
            } else if (mode.equals("quick")) {
                verifyQuickAccess();
            } else if (mode.equals("motion")) {
                verifyEditorMotion();
                check("Editor transitions keep synchronous state and steady hit targets; gentle jiggle pauses during gestures, focus loss and detach, and disabled animations stay still");
            } else if (mode.equals("edit") || mode.equals("editor")) {
                seedEveryWidget();
                verifyEditOverlays();
                check("Editing keeps equal icon-only controls in one row or a compact grid, without overlaps, smaller touch targets, or resized cards");
                if (mode.equals("editor")) { verifyEditorActions(); check("Save/duplicate/remove and Undo/Redo preserve the intended widgets and settings; six-column sizing and cancelled resize keep safe layout constraints"); }
            } else if (!mode.equals("fold")) {
                assertGridAndHeader(); check("Default cards fit the available window and start immediately after the header");
                assertInitialLockVisible();
                if (!mode.equals("battery")) {
                    assertLockStates(); check("Connected, disconnected and connecting lock controls fit without inner scrolling");
                    verifyClassicLockStates(mode.equals("constraints"));
                    check("The classic Lock, swapped in for the Lock bar, keeps its connected, disconnected, connecting and Disconnect states inside the card"
                            + (mode.equals("constraints") ? ", including its four-row minimum with Disconnect" : ""));
                }
                assertDefaultCardContents(); screenshot("default-disconnected");
                check("Default battery overview and reading tiles fit without hidden overflow");
                if (mode.equals("constraints")) {
                    verifyMinimumLockAndCharging();
                    check("Minimum lock height restores cleanly; minimum battery size fits its active charging readings");
                    verifyLockWidthTransitions();
                    check("Lock headings restore margins, weights and child bounds across LTR/RTL narrow-wide-narrow measurements");
                    verifyLockMotionAndDisconnect();
                    check("Disconnect preference preserves Connect/Cancel; lock motion keeps confirmed accessibility state, cancels cleanly and stops off-screen");
                }
            }
            if (mode.equals("all") || mode.equals("fold")) {
                verifyFoldCallbacks(); check("Fold creases never split, reload or replay Home, flat or half-open; physical hinges retain the two-pane layout");
            }
            assertNoScooterWork();
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", error.toString());
            try { if (admitted && activity != null) screenshot("failure"); } catch (Throwable ignored) { }
        } finally {
            result.putStringArrayList("checks", checks); result.putStringArrayList("screenshots", screenshots);
            result.putStringArrayList("overlay_geometry", overlayGeometry);
            result.putInt("width_dp", getTargetContext().getResources().getConfiguration().screenWidthDp);
            result.putBoolean("physical_device_tested", false); result.putInt("scooter_mutations", 0);
            if (admitted && activity != null) {
                try { main(() -> { activity.finish(); ScooterService.setUiVisible(false);
                    getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); }); }
                catch (Throwable error) { result.putString("cleanup_error", error.toString()); }
            }
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }
    /** Restore the shipped example; do not construct a lookalike in the fixture. */
    private void verifyAllWidgetsExample() throws Exception {
        final AtomicReference<String> savedExample = new AtomicReference<>();
        main(() -> {
            DashboardConfig config = dashboardConfig();
            DashboardConfig.SavedSlot slot = config.slot(2);
            require(slot != null && "All widgets".equals(slot.name)
                            && slot.widgetCount == DashboardConfig.TYPES.length && slot.columns == 6,
                    "Fresh profile is missing the six-column All widgets example in slot 3");
            String key = "dashboard_slot_v1_" + activity.service().profiles.active().id + "_2";
            savedExample.set(settings().getString(key, null));
            require(savedExample.get() != null, "Example slot was not persisted");
            require(config.restoreSlot(2), "Could not restore All widgets example");
            invoke(dashboard(), "render");
        });
        settleMotion(); stopUiPulse();
        main(() -> ((ScrollView)field(activity, "scroll")).scrollTo(0, 0));
        settle();
        main(() -> {
            DashboardConfig config = dashboardConfig();
            require(settings().getInt("dashboard_columns", 0) == 6, "Example lost its preferred column setting");
            require(config.widgets.size() == DashboardConfig.TYPES.length && tiles().size() == DashboardConfig.TYPES.length,
                    "Example did not render all " + DashboardConfig.TYPES.length + " widgets");
            for (String type : DashboardConfig.TYPES) {
                int count = 0;
                for (DashboardConfig.Widget widget : config.widgets) if (widget.type.equals(type)) count++;
                require(count == 1, "Example must contain exactly one " + type + " widget");
            }
            DashboardConfig.Widget gauge = (DashboardConfig.Widget)field(tile("gauge"), "widget");
            DashboardConfig.Widget live = (DashboardConfig.Widget)field(tile("live"), "widget");
            require("speed_kmh".equals(gauge.metric) && gauge.minimum == 0 && gauge.maximum == 50,
                    "Example speed gauge lost its useful range");
            require("power_w".equals(live.metric) && live.windowMs == 120000,
                    "Example live chart lost its two-minute power view");
            require(((DashboardConfig.Widget)field(tile("lock"), "widget")).showDisconnect,
                    "Example lock card lost its Disconnect option");
            require(!((DashboardConfig.Widget)field(tile("lockbar"), "widget")).showDisconnect,
                    "Example lock bar should keep the default: no Disconnect button");
            if ((Integer)field(grid(), "columns") == 2) {
                DashboardConfig.Widget quick = (DashboardConfig.Widget)field(tile("quick"), "widget");
                DashboardLayout.Cell quickCell = (DashboardLayout.Cell)invoke(grid(), "currentCell",
                        new Class<?>[] {DashboardConfig.Widget.class}, quick);
                require(quickCell != null && quickCell.x == 0 && quickCell.w == 2,
                        "Narrow example leaves Quick access stranded in a half-empty row");
            }
            assertNoEditorControls();
            for (Object item : tiles()) {
                View card = (View)item, body = (View)field(item, "body");
                ScrollView viewport = (ScrollView)field(item, "viewport");
                DashboardConfig.Widget widget = (DashboardConfig.Widget)field(item, "widget");
                DashboardLayout.Cell cell = (DashboardLayout.Cell)invoke(grid(), "currentCell",
                        new Class<?>[] {DashboardConfig.Widget.class}, widget);
                require(cell != null && cell.h >= widget.minimumHeight(), "Example card is below its minimum height: " + widget.type);
                require(viewport.getWidth() > 0 && viewport.getHeight() > 0
                                && body.getWidth() <= viewport.getWidth(), "Example card lost its content viewport: " + widget.type);
                // Some cards intentionally scroll at small widths. Record that geometry
                // for inspection rather than treating supported scrolling as clipping.
                overlayGeometry.add("example " + widget.type + " cell=" + cellText(cell)
                        + " card=" + card.getWidth() + "x" + card.getHeight()
                        + " body=" + body.getWidth() + "x" + body.getHeight()
                        + " viewport=" + viewport.getWidth() + "x" + viewport.getHeight());
            }
            String key = "dashboard_slot_v1_" + activity.service().profiles.active().id + "_2";
            require(savedExample.get().equals(settings().getString(key, null)), "Rendering changed the saved example");
        });
        assertGridAndHeader();
        assertInitialLockVisible();
        await(() -> ((Ui.ChartView)field(tile("history"), "chart")).pointCount() > 0
                        && ((Ui.ChartView)field(tile("health"), "chart")).pointCount() > 0,
                "Example history charts did not load synthetic fixture samples");
        captureExampleSections();
        check("Saved All widgets example restores all " + DashboardConfig.TYPES.length + " types with useful parameters; cards fit without overlap and the compact Connect action starts on-screen");
        check("Overlapping page captures cover every example card, including compact, wide and enlarged-text arrangements; intentional card scrolling is preserved");
    }
    private void captureExampleSections() throws Exception {
        final int[] size = new int[2];
        final Map<String, int[]> covered = new LinkedHashMap<>();
        main(() -> {
            ScrollView scroll = (ScrollView)field(activity, "scroll");
            // Exclude the floating navigation bar/padding from the covered region.
            size[1] = scroll.getHeight() - scroll.getPaddingTop() - scroll.getPaddingBottom();
            require(size[1] > activity.ui().dp(100), "Example has no usable scroll viewport");
            require(scroll.getChildCount() == 1, "Example scroll content is missing");
            // A huge probe can overflow ScrollView's clamp arithmetic. Compute the
            // actual range from measured content instead, without moving to a sentinel.
            size[0] = Math.max(0, scroll.getChildAt(0).getHeight() - size[1]);
            scroll.scrollTo(0, 0);
            for (Object item : tiles()) covered.put(((DashboardConfig.Widget)field(item, "widget")).type,
                    new int[] {Integer.MAX_VALUE, Integer.MIN_VALUE});
        });
        int step = Math.max(1, size[1] * 3 / 4);
        long sectionCount = ((long)size[0] + step - 1) / step + 1;
        require(sectionCount <= 64, "Example screenshot range is unexpectedly large: " + sectionCount);
        final int[] previousOffset = {-1};
        for (int index = 0; index < (int)sectionCount; index++) {
            final int offset = (int)Math.min(size[0], (long)index * step);
            main(() -> ((ScrollView)field(activity, "scroll")).scrollTo(0, offset));
            settle();
            main(() -> {
                ScrollView scroll = (ScrollView)field(activity, "scroll");
                int actual = scroll.getScrollY();
                require(actual == offset, "Example scroll range changed: requested " + offset + ", got " + actual);
                require(actual > previousOffset[0], "Example screenshot scrolling made no progress");
                previousOffset[0] = actual;
                int top = scroll.getPaddingTop(), bottom = scroll.getHeight() - scroll.getPaddingBottom();
                for (Object item : tiles()) {
                    View card = (View)item; Rect box = relativeBounds(card, scroll);
                    if (box.bottom <= top || box.top >= bottom) continue;
                    int[] range = covered.get(((DashboardConfig.Widget)field(item, "widget")).type);
                    range[0] = Math.min(range[0], Math.max(0, top - box.top));
                    range[1] = Math.max(range[1], Math.min(card.getHeight(), bottom - box.top));
                }
            });
            screenshot(String.format(Locale.ROOT, "example-section-%02d", index + 1));
        }
        main(() -> {
            for (Object item : tiles()) {
                String type = ((DashboardConfig.Widget)field(item, "widget")).type;
                int[] range = covered.get(type);
                require(range[0] == 0 && range[1] == ((View)item).getHeight(),
                        "Page captures missed part of example card: " + type);
            }
            ((ScrollView)field(activity, "scroll")).scrollTo(0, 0);
        });
    }
    private void verifyChartAxes() throws Exception {
        main(() -> {
            Ui ui = new Ui(activity, true, Float.parseFloat(arguments.getString("text_scale", "1")));
            Ui.ChartView probe = ui.new ChartView(); probe.setHistoryMode(true);
            long start = System.currentTimeMillis() - 3600000;
            List<Ui.Point> measured = Arrays.asList(new Ui.Point(start, 99), new Ui.Point(start + 600000, 99.2),
                    new Ui.Point(start + 1200000, 99.6, true), new Ui.Point(start + 3600000, 100));
            probe.setAxis("battery_health_percent", false); probe.set(measured, "%", ui.accent, "No data");
            require(((Number)field(probe, "max")).doubleValue() == 100, "Relative health escaped 100 percent");
            int narrowCount = 0, wideCount = 0;
            for (int width : new int[] {168, 300, 620}) {
                probe.measure(View.MeasureSpec.makeMeasureSpec(ui.dp(width), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(ui.dp(230), View.MeasureSpec.EXACTLY));
                probe.layout(0, 0, ui.dp(width), ui.dp(230));
                Bitmap bitmap = Bitmap.createBitmap(probe.getWidth(), probe.getHeight(), Bitmap.Config.ARGB_8888);
                try { probe.draw(new android.graphics.Canvas(bitmap)); } finally { bitmap.recycle(); }
                List<?> labels = (List<?>)field(probe, "timeLabels");
                float[] starts = (float[])field(probe, "timeLabelStarts"), widths = (float[])field(probe, "timeLabelWidths");
                float plotWidth = ((Number)field(probe, "right")).floatValue() - ((Number)field(probe, "left")).floatValue();
                require(labels.size() >= (width == 168 && ui.textScale > 1 ? 1 : 2) && labels.size() <= 6,
                        "History width lost its fitting time ticks: " + width);
                for (int i = 0; i < labels.size(); i++) {
                    require(starts[i] >= 0 && starts[i] + widths[i] <= plotWidth + .01f, "Time label escaped chart");
                    if (i > 0) require(starts[i] >= starts[i-1] + widths[i-1] + ui.dp(12) - .01f, "Time labels overlap");
                }
                if (width == 168) narrowCount = labels.size(); if (width == 620) wideCount = labels.size();
            }
            require(wideCount > narrowCount && wideCount >= 4, "History labels did not use wider space");
            probe.setAxis("power_w", true); probe.set(Arrays.asList(new Ui.Point(start, -300), new Ui.Point(start+3600000, 850)), "W", ui.accent, "No data");
            double minimum = ((Number)field(probe, "min")).doubleValue(), maximum = ((Number)field(probe, "max")).doubleValue();
            require(minimum == -maximum && maximum >= 850, "Absolute power is not centered around zero");
            require(Boolean.TRUE.equals(field(probe, "absoluteAxis")), "Refreshing samples reset absolute axis");
            probe.setAxis("voltage_v", true); probe.set(Arrays.asList(new Ui.Point(start, 49), new Ui.Point(start+3600000, 51)), "V", ui.accent, "No data");
            require(((Number)field(probe, "min")).doubleValue() == 0 && ((Number)field(probe, "max")).doubleValue() >= 51,
                    "Absolute voltage did not stay grounded");
            // Preserve both explicit gaps and invalid-point gaps, while inspecting the unmodified measurement.
            probe.setAxis("battery_percent", true);
            probe.set(Arrays.asList(new Ui.Point(start+3600000, 120), new Ui.Point(start, 97), null,
                    new Ui.Point(start+600000, 98, true), new Ui.Point(start+1200000, Double.NaN), new Ui.Point(start+1800000, 99)), "%", ui.accent, "No data");
            List<?> retained = (List<?>)field(probe, "points");
            require(probe.pointCount() == 4 && ((Ui.Point)retained.get(1)).gapBefore && ((Ui.Point)retained.get(2)).gapBefore,
                    "Scale changes lost measured samples or gaps");
            float x = ((Number)field(probe, "right")).floatValue(); long now = SystemClock.uptimeMillis();
            android.view.MotionEvent down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, x, ui.dp(100), 0);
            android.view.MotionEvent up = android.view.MotionEvent.obtain(now, now+1, android.view.MotionEvent.ACTION_UP, x, ui.dp(100), 0);
            try { probe.onTouchEvent(down); probe.onTouchEvent(up); } finally { down.recycle(); up.recycle(); }
            require(probe.getContentDescription().toString().startsWith("120 %"), "Physical bounds changed the inspected raw reading");
            require(((Number)invoke(probe, "y", new Class<?>[] {double.class}, 120d)).floatValue() == ((Number)field(probe, "top")).floatValue(),
                    "Out-of-domain percentage drew outside the plot");
            probe.set(Collections.emptyList(), "%", ui.accent, "No measured data");
            require(probe.pointCount() == 0 && probe.getContentDescription().toString().equals("No measured data"), "Empty chart retained old samples");
            ScrollView scroll = new ScrollView(activity); LinearLayout content = new LinearLayout(activity);
            content.setOrientation(LinearLayout.VERTICAL); content.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(16)); content.setBackgroundColor(ui.bg);
            scroll.addView(content); activity.setContentView(scroll);
            String[] titles = {"Relative battery health · capped at 100%", "Absolute battery power · centered at zero", "Absolute pack voltage · grounded at zero"};
            String[] metrics = {"battery_health_percent", "power_w", "voltage_v"}, units = {"%", "W", "V"};
            for (int chartIndex = 0; chartIndex < 3; chartIndex++) {
                TextView title = new TextView(activity); title.setText(titles[chartIndex]); title.setTextColor(ui.text); title.setTextSize(16); content.addView(title);
                Ui.ChartView chart = ui.new ChartView(); chart.setHistoryMode(true); chart.setAxis(metrics[chartIndex], chartIndex > 0);
                ArrayList<Ui.Point> values = new ArrayList<>();
                for (int i = 0; i <= 20; i++) values.add(new Ui.Point(start + i*180000,
                        chartIndex == 0 ? 99 + i*.05 : chartIndex == 1 ? Math.sin(i*.5)*450+100 : 48+Math.cos(i*.25)*2, i == 9));
                chart.set(values, units[chartIndex], ui.accent, "No data"); content.addView(chart);
            }
        });
        settle(); screenshot("chart-axes");
        check("Relative percentages stay within 0–100; absolute signed power centers on zero and voltage grounds at zero; refreshed settings, raw touch values and explicit/invalid gaps are preserved");
        check("Localized history timestamps adapt to 168/300/620dp without overlap or clipping; wide charts show intermediate times and tiny enlarged-text charts retain one readable endpoint");
    }
    private void admit() {
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
        require(hardware.contains("ranchu") || hardware.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "Refusing a physical phone");
        for (String name : new String[] {"app_settings", "profiles", "scooter", "pending_documents"})
            require(getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE).getAll().isEmpty(), "Refusing existing preferences");
        File[] files = getTargetContext().getFilesDir().listFiles();
        require(files != null && files.length == 0 && getTargetContext().databaseList().length == 0, "Refusing existing private data");
        for (String permission : new String[] {Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT})
            require(getTargetContext().checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED, "Grant Nearby permission before the test");
        BluetoothManager manager = getTargetContext().getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        require(adapter == null || !adapter.isEnabled(), "Bluetooth must already be disabled");
    }
    private void seed() {
        AtomicReference<TelemetryHistory.Snapshot> saved = new AtomicReference<>();
        AtomicReference<TelemetryHistory> history = new AtomicReference<>();
        main(() -> {
            ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
            ScooterProfiles.Profile profile = profiles.create("Synthetic Fold dashboard", "000000000000", 0);
            profile.serial = "X2538-SYNTHETIC"; profile.lastConnected = System.currentTimeMillis() - 86400000L; profiles.update(profile);
            long wall = System.currentTimeMillis() - 60000;
            Map<String, TelemetryHistory.Reading> values = new LinkedHashMap<>();
            reading(values, "battery_percent", "100 %", "%", 100d, wall);
            reading(values, "power_w", "14.6 W", "W", 14.6d, wall);
            reading(values, "battery_health_percent", "100 %", "%", 100d, wall);
            reading(values, "remaining_range_km", "49.8 km", "km", 49.8d, wall);
            reading(values, "temperature_c", "23 °C", "°C", 23d, wall);
            reading(values, "odometer_km", "24.89 km", "km", 24.89d, wall);
            reading(values, "driving_mode", "3", "", 3d, wall);
            reading(values, "charging_state", "Not charging", "", 0d, wall);
            java.util.Calendar schedule = java.util.Calendar.getInstance();
            schedule.set(java.util.Calendar.HOUR_OF_DAY, 13); schedule.set(java.util.Calendar.MINUTE, 45);
            schedule.set(java.util.Calendar.SECOND, 0); schedule.set(java.util.Calendar.MILLISECOND, 0);
            double rawSchedule = (schedule.getTimeInMillis() + java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis())) / 1000d;
            reading(values, "scheduled_charge_time_raw", "Fixture schedule", "", rawSchedule, wall);
            TelemetryHistory store = new TelemetryHistory(getTargetContext()); history.set(store);
            if (Arrays.asList("example", "narrow", "compact").contains(arguments.getString("mode", "all"))) {
                // Fixture-only samples make the example's extra cards legible without a
                // real scooter, synthetic rides, or changes to production example data.
                reading(values, "speed_kmh", "18.4 km/h", "km/h", 18.4d, wall);
                reading(values, "voltage_v", "52.1 V", "V", 52.1d, wall);
                reading(values, "current_a", "0.28 A", "A", .28d, wall);
                reading(values, "trip_km", "3.6 km", "km", 3.6d, wall);
                reading(values, "trip_minutes", "14 min", "min", 14d, wall);
                reading(values, "maximum_speed_kmh", "25 km/h", "km/h", 25d, wall);
                reading(values, "charge_cycles", "12", "", 12d, wall);
                reading(values, "rated_capacity_ah", "12.75 Ah", "Ah", 12.75d, wall);
                for (int day = 6; day >= 1; day--) {
                    long at = wall - day * 86400000L;
                    Map<String, TelemetryHistory.Reading> past = new LinkedHashMap<>();
                    double percent = 100 - day * 4d;
                    reading(past, "battery_percent", percent + " %", "%", percent, at);
                    reading(past, "battery_health_percent", "100 %", "%", 100d, at);
                    store.record(profile.id, new TelemetryHistory.Snapshot(past, Collections.emptyMap(), at), 1, true);
                }
                for (int sample = 12; sample >= 1; sample--) {
                    long at = wall - sample * 5000L;
                    Map<String, TelemetryHistory.Reading> past = new LinkedHashMap<>();
                    double power = 14.6 + Math.sin(sample * .6) * 4;
                    reading(past, "power_w", power + " W", "W", power, at);
                    store.record(profile.id, new TelemetryHistory.Snapshot(past, Collections.emptyMap(), at), 1, true);
                }
            }
            store.record(profile.id, new TelemetryHistory.Snapshot(values, Collections.singletonMap("timed_charging", 1), wall), 1, true);
            store.load(profile.id, saved::set);
        });
        await(() -> saved.get() != null, "Synthetic cache did not persist");
        main(() -> history.get().close());
    }
    private void reading(Map<String, TelemetryHistory.Reading> values, String key, String text, String unit, double value, long wall) {
        values.put(key, new TelemetryHistory.Reading(key, key, "Synthetic fixture", text, unit, value, wall, true));
    }
    private void stopUiPulse() {
        main(() -> {
            ((Handler)field(activity, "handler")).removeCallbacks((Runnable)field(activity, "pulse"));
            invoke(activity, "cancelDeferredBodyRefresh");
            // Freeze only this Activity's automatic presentation refresh while supplying view-only fixtures.
            if (activity.service() != null) activity.service().unlisten((Runnable)field(activity, "serviceChanged"));
        });
    }
    private void assertGridAndHeader() {
        main(() -> {
            ViewGroup grid = grid(); View toolbar = (View)field(dashboard(), "toolbar");
            require(grid.getParent() == toolbar.getParent(), "Flat window split the header away from the grid");
            int expected = toolbar.getBottom();
            // The separate "Last seen" caption ("stale") may be removed entirely; the header line carries the time.
            for (String name : new String[] {"tools", "hint", "stale", "chargingHero"}) {
                Object item = optionalField(dashboard(), name);
                if (item instanceof View && ((View)item).getVisibility() != View.GONE)
                    expected = Math.max(expected, ((View)item).getBottom());
            }
            Object stale = optionalField(dashboard(), "stale");
            require(!(stale instanceof TextView) || !((TextView)stale).isShown() || ((TextView)stale).length() == 0,
                    "Home still shows a separate Last seen caption under the header");
            require(grid.getTop() - expected <= activity.ui().dp(16), "Unexplained gap before the first card: " + (grid.getTop() - expected));
            List<Rect> occupied = new ArrayList<>();
            int first = Integer.MAX_VALUE, leftmost = Integer.MAX_VALUE, rightmost = 0;
            for (Object tile : tiles()) {
                View card = (View)tile; Rect box = new Rect(card.getLeft(), card.getTop(), card.getRight(), card.getBottom());
                require(box.left >= 0 && box.right <= grid.getWidth() && box.top >= 0 && box.bottom <= grid.getHeight()
                        && box.width() > 0 && box.height() > 0, "Card lies outside the grid: " + box);
                for (Rect other : occupied) require(!Rect.intersects(box, other), "Cards overlap");
                occupied.add(box); first = Math.min(first, box.top);
                leftmost = Math.min(leftmost, box.left); rightmost = Math.max(rightmost, box.right);
            }
            require(leftmost == 0 && rightmost == grid.getWidth(), "Dashboard has unequal left/right edge gaps");
            int count = ((Number)field(grid, "columns")).intValue();
            if (settings().getInt("dashboard_columns", 0) == 5 && grid.getWidth() >= activity.ui().dp(280)) {
                require(count == 5, "A five-column dashboard silently switched layout on a cover screen");
                if (activity.ui().textScale * activity.getResources().getConfiguration().fontScale <= 1.05f
                        && dashboardConfig().widgets.size() == DEFAULT_STACK.length) {
                    // The default Home is one full-width stack: rows 0, 1–4, 5–6, 7 and 8.
                    int[][] rows = {{0, 1}, {1, 4}, {5, 2}, {7, 1}, {8, 1}};
                    for (int i = 0; i < DEFAULT_STACK.length; i++) {
                        DashboardLayout.Cell cell = (DashboardLayout.Cell)invoke(grid, "currentCell",
                                new Class<?>[]{DashboardConfig.Widget.class}, field(tile(DEFAULT_STACK[i]), "widget"));
                        require(cell.x == 0 && cell.w == 5 && cell.y == rows[i][0] && cell.h == rows[i][1],
                                "Narrow default dashboard shuffled its full-width five-column stack: " + DEFAULT_STACK[i] + " " + cellText(cell));
                    }
                }
            }
            require(first <= activity.ui().dp(16), "Default grid has phantom leading rows");
        });
    }
    /** Narrow fresh installs must expose the safety/connection action before any scrolling. */
    private void assertInitialLockVisible() {
        main(() -> {
            int columns = ((Number)field(grid(), "columns")).intValue();
            float scale = activity.ui().textScale * getTargetContext().getResources().getConfiguration().fontScale;
            ScrollView scroll = (ScrollView)field(activity, "scroll");
            if (columns != 2 || scale > 1.05f || scroll.getHeight() < activity.ui().dp(600)) return;
            require(scroll.getScrollY() == 0, "Fresh default visibility must be checked before scrolling");
            View lock = (View)lockTile();
            TextView connect = null;
            for (TextView text : texts(lock)) if (text.isShown() && "Connect".contentEquals(text.getText())) connect = text;
            require(connect != null, "Fresh disconnected dashboard lost Connect");
            for (View view : new View[] {lock, connect}) {
                Rect visible = new Rect();
                require(view.getGlobalVisibleRect(visible) && visible.width() == view.getWidth()
                                && visible.height() == view.getHeight(),
                        "Fresh two-column lock/Connect is not fully visible on the first screen");
            }
            check("Fresh two-column lock and Connect remain fully visible on the first screen at default text");
        });
    }
    private void assertDefaultCardContents() { assertCardContents("overview", "tiles"); }
    private void assertCardContents(String... types) {
        main(() -> {
            for (String type : types) {
                Object item = tile(type); View body = (View)field(item, "body");
                ScrollView viewport = (ScrollView)field(item, "viewport");
                require(body.getHeight() <= viewport.getHeight() + 1, type + " default needs inner scrolling: body=" + body.getWidth() + "x" + body.getHeight() + ", viewport=" + viewport.getWidth() + "x" + viewport.getHeight() + ", tile=" + ((View)item).getWidth() + "x" + ((View)item).getHeight());
                for (TextView text : texts(body)) assertText(text, viewport, type);
            }
        });
    }
    private void assertLockStates() throws Exception {
        Object item = lockTile(); View card = (View)item;
        final boolean bar = "lockbar".equals(((DashboardConfig.Widget)field(item, "widget")).type);
        AtomicReference<Boolean> belowMinimum = new AtomicReference<>(false);
        main(() -> {
            DashboardConfig.Widget widget = (DashboardConfig.Widget)field(item, "widget");
            DashboardLayout.Cell cell = (DashboardLayout.Cell)invoke(grid(), "currentCell",
                    new Class<?>[] {DashboardConfig.Widget.class}, widget);
            int least = ((Number)invoke(grid(), "minimumWidth", new Class<?>[] {DashboardConfig.Widget.class}, widget)).intValue();
            belowMinimum.set(cell != null && cell.w < least);
        });
        // Text fit is only promised from the type's minimum span; narrower stored cells are not laid out that way.
        if (belowMinimum.get()) return;
        for (String state : new String[] {"disconnected", "unlocked", "locked", "connecting"}) {
            Runnable showState = () -> {
                invoke(activity, "updateHeader");
                if (state.equals("connecting")) {
                    BleController ble = activity.service().ble;
                    BleController.State original = ble.state();
                    try {
                        setField(ble, "state", BleController.State.CONNECTING);
                        invoke(activity, "updateHeader");
                    } finally { setField(ble, "state", original); }
                }
                if (state.equals("unlocked") || state.equals("locked")) {
                    setVisibilityIfPresent("lockOffline", View.GONE);
                    setVisibilityIfPresent("lockReady", View.VISIBLE);
                    invoke(activity, "updateConnectionAction", new Class<?>[] {boolean.class, boolean.class}, true, false);
                    TextView title = (TextView)field(activity, "lockTitle"); title.setText(state.equals("locked") ? "Locked" : "Unlocked");
                    // Preserve the production glyph size installed by updateHeader; only the text/state changes.
                    invoke(field(activity, "lockControl"), "update", new Class<?>[] {Boolean.class, boolean.class}, state.equals("locked"), true);
                }
                card.requestLayout();
            };
            main(showState); settle();
            main(() -> {
                // A service refresh may restore Disconnected between frames. Apply the display-only
                // fixture and measure atomically; never pause the transport or fabricate a GATT.
                showState.run();
                int width = card.getWidth(), height = card.getHeight();
                for (int pass = 0; pass < 3; pass++) {
                    forceMeasure(card);
                    card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                    card.layout(card.getLeft(), card.getTop(), card.getLeft() + width, card.getTop() + height);
                }
                View body = (View)field(item, "body"); ScrollView viewport = (ScrollView)field(item, "viewport");
                require(body.getHeight() <= viewport.getHeight() + 1, state + " lock needs hidden inner scrolling: body " + body.getHeight() + ", viewport " + viewport.getHeight());
                View action = (View)field(activity, state.equals("locked") || state.equals("unlocked") ? "lockControl" : "connectionAction");
                Rect bounds = relativeBounds(action, viewport);
                require(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= viewport.getWidth() && bounds.bottom <= viewport.getHeight(),
                        state + " action is clipped: " + bounds + " in " + viewport.getWidth() + "x" + viewport.getHeight());
                require(action.getWidth() >= activity.ui().dp(48) && action.getHeight() >= activity.ui().dp(48), state + " action lost its touch target");
                if (bar && (state.equals("locked") || state.equals("unlocked")))
                    require(action.isShown(), "Connected Lock bar does not show its tap-or-slide LockControl");
                if (bar && state.equals("disconnected")) {
                    boolean unknown = false, connect = false;
                    for (TextView text : texts(body)) if (text.isShown()) {
                        unknown |= "Lock state unknown".contentEquals(text.getText());
                        connect |= "Connect".contentEquals(text.getText());
                    }
                    require(unknown && connect, "Disconnected Lock bar must say Lock state unknown and offer Connect");
                }
                TextView shownDisconnect = (TextView)field(activity, "connectionAction");
                if ((state.equals("locked") || state.equals("unlocked")) && (!bar || shownDisconnect.isShown())) {
                    // The one-row Lock bar may give the whole card to the LockControl; a shown Disconnect must still fit.
                    TextView disconnect = shownDisconnect;
                    require(disconnect.getVisibility() == View.VISIBLE && disconnect.getText().toString().equals("Disconnect"), "Default connected card lost Disconnect");
                    Rect disconnectBounds = relativeBounds(disconnect, viewport);
                    require(disconnectBounds.left >= 0 && disconnectBounds.top >= 0 && disconnectBounds.right <= viewport.getWidth()
                            && disconnectBounds.bottom <= viewport.getHeight(), "Disconnect action is clipped: " + disconnectBounds);
                    require(disconnect.getHeight() >= activity.ui().dp(48), "Disconnect lost its touch target");
                }
                for (TextView text : texts(body))
                    if (!iconOnlyAction(text)) assertText(text, viewport, state, ellipsizedByDesign(text));
                if (state.equals("connecting")) {
                    CharSequence spoken = ((TextView)field(activity, "offlineTitle")).getContentDescription();
                    require(spoken != null && spoken.toString().contains(activity.service().profiles.active().name), "Connecting status lost its accessible scooter name");
                }
            });
            if (state.equals("unlocked")) screenshot("connected-view-fixture");
        }
        main(() -> invoke(activity, "updateHeader")); settle();
    }
    /** View-only checks: never fake a GATT, tap an action, or submit a lock command. */
    private void verifyLockMotionAndDisconnect() {
        main(() -> {
            // updateConnectionAction reloads each card's stored views and preference, so the
            // fixture flips the first card's own flag rather than the activity's working copy.
            java.util.List<?> locks = (java.util.List<?>)field(activity, "dashboardLocks");
            require(!locks.isEmpty(), "Lock motion check needs a lock card on Home");
            Object card = locks.get(0);
            boolean showDisconnect = (Boolean)field(card, "showDisconnect");
            boolean resumed = (Boolean)field(activity, "resumed");
            View slider = (View)field(activity, "lockControl");
            try {
                TextView action = (TextView)field(activity, "connectionAction");
                require(action == field(card, "connectionAction"), "The activity's lock views are not the first card's");
                for (boolean show : new boolean[] {false, true}) {
                    setField(card, "showDisconnect", show);
                    invoke(activity, "updateConnectionAction", new Class<?>[] {boolean.class, boolean.class}, true, false);
                    require(action.getVisibility() == (show ? View.VISIBLE : View.GONE), "Disconnect setting was ignored");
                    require(action.getText().toString().equals("Disconnect"), "Ready connection action changed meaning");
                    invoke(activity, "updateConnectionAction", new Class<?>[] {boolean.class, boolean.class}, false, false);
                    require(action.getVisibility() == View.VISIBLE && action.getText().toString().equals("Connect"), "Hiding Disconnect also hid Connect");
                    invoke(activity, "updateConnectionAction", new Class<?>[] {boolean.class, boolean.class}, false, true);
                    require(action.getVisibility() == View.VISIBLE && action.getText().toString().equals("Cancel"), "Hiding Disconnect also hid Cancel");
                }
                setVisibilityIfPresent("lockOffline", View.GONE);
                setVisibilityIfPresent("lockReady", View.VISIBLE);
                invoke(slider, "update", new Class<?>[] {Boolean.class, boolean.class}, false, true);
                invoke(slider, "stopMotion");
                require(((Number)field(slider, "position")).floatValue() == 0, "Unlocked thumb did not settle left");
                invoke(slider, "update", new Class<?>[] {Boolean.class, boolean.class}, true, true);
                android.view.accessibility.AccessibilityNodeInfo checked = slider.createAccessibilityNodeInfo();
                try { require(checked.isCheckable() && checked.isChecked(), "Animation delayed the confirmed accessible lock state"); }
                finally { checked.recycle(); }
                require(Boolean.TRUE.equals(field(slider, "value")), "Animation replaced the confirmed value");
                if (local.navee.offlinelock.Ui.motionEnabled() && resumed && slider.isShown())
                    require(field(slider, "motion") != null && ((Number)field(slider, "position")).floatValue() < 1, "Confirmed state change skipped enabled motion");
                else require(((Number)field(slider, "position")).floatValue() == 1, "Reduced motion did not snap to the confirmed state");
                invoke(slider, "stopMotion");
                require(field(slider, "motion") == null && ((Number)field(slider, "position")).floatValue() == 1, "Stopping motion left an intermediate thumb position");

                invoke(slider, "update", new Class<?>[] {Boolean.class, boolean.class}, false, true);
                invoke(slider, "stopMotion");
                long when = SystemClock.uptimeMillis();
                float y = slider.getHeight()/2f, start = slider.getHeight()/2f, end = slider.getWidth()-slider.getHeight()/2f;
                for (int event : new int[] {android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE, android.view.MotionEvent.ACTION_CANCEL}) {
                    android.view.MotionEvent touch = android.view.MotionEvent.obtain(when, when, event, event == android.view.MotionEvent.ACTION_DOWN ? start : end, y, 0);
                    try { slider.onTouchEvent(touch); } finally { touch.recycle(); }
                    if (event == android.view.MotionEvent.ACTION_MOVE)
                        require(Boolean.FALSE.equals(field(slider, "value")) && ((Number)field(slider, "position")).floatValue() > .5f, "Drag preview changed confirmed state or failed to follow the finger");
                }
                invoke(slider, "stopMotion");
                require(!(Boolean)field(slider, "dragging") && Boolean.FALSE.equals(field(slider, "value"))
                        && ((Number)field(slider, "position")).floatValue() == 0, "Cancelled drag retained a proposed lock state");
                setField(activity, "resumed", false);
                invoke(slider, "update", new Class<?>[] {Boolean.class, boolean.class}, true, false);
                require(field(slider, "motion") == null && ((Number)field(slider, "position")).floatValue() == 1, "Background update kept animating");
                invoke(slider, "update", new Class<?>[] {Boolean.class, boolean.class}, null, false);
                require(field(slider, "motion") == null && !slider.isEnabled() && ((Number)field(slider, "position")).floatValue() == 0, "Unavailable state retained a moving/interactive control");
            } finally {
                setField(activity, "resumed", resumed);
                setField(card, "showDisconnect", showDisconnect);
                invoke(slider, "stopMotion"); invoke(activity, "updateHeader");
            }
        });
        settle();
    }
    private void verifyMinimumLockAndCharging() throws Exception {
        int columns = ((Number)field(grid(), "columns")).intValue();
        DashboardConfig.Widget lock = (DashboardConfig.Widget)field(lockTile(), "widget");
        int originalLockHeight = lock.layouts.get(columns).h;
        try {
            main(() -> { lock.layouts.get(columns).h = lock.minimumHeight(); grid().requestLayout(); });
            settle(); assertLockStates();
        } finally {
            main(() -> { lock.layouts.get(columns).h = originalLockHeight; grid().requestLayout(); }); settle();
        }
        assertLockStates();
        // The classic battery card is no longer in the default; seed it so its charging checks keep running.
        final DashboardConfig.Widget seededBattery = new DashboardConfig.Widget("battery");
        main(() -> {
            int end = 0;
            for (DashboardConfig.Widget widget : dashboardConfig().widgets) {
                DashboardLayout.Cell cell = widget.layouts.get(columns);
                if (cell != null) end = Math.max(end, cell.y + cell.h);
            }
            seededBattery.layouts.put(columns, new DashboardLayout.Cell(seededBattery.id, 0, end, columns, seededBattery.defaultHeight()));
            dashboardConfig().widgets.add(seededBattery); invoke(dashboard(), "render");
        }); settleMotion(); stopUiPulse();
        Object item = tile("battery");
        DashboardConfig.Widget battery = (DashboardConfig.Widget)field(item, "widget");
        int originalHeight = battery.layouts.get(columns).h, originalWidth = battery.layouts.get(columns).w;
        Map<String, TelemetryHistory.Reading> cached = activity.service().displayReadings();
        Map<String, TelemetryHistory.Reading> preview = new LinkedHashMap<>(cached);
        long wall = System.currentTimeMillis();
        reading(preview, "charging_state", "Charging", "", 1d, wall);
        reading(preview, "voltage_v", "52 V", "V", 52d, wall);
        reading(preview, "current_a", "-2 A", "A", -2d, wall);
        reading(preview, "battery_percent", "50 %", "%", 50d, wall);
        reading(preview, "rated_capacity_ah", "14.5 Ah", "Ah", 14.5d, wall);
        reading(preview, "charge_limit_percent", "80 %", "%", 80d, wall);
        try {
            main(() -> {
                battery.layouts.get(columns).h = battery.minimumHeight(); battery.layouts.get(columns).w = 1;
                invoke(item, "refresh", new Class<?>[] {Map.class}, preview);
                require(((View)field(item, "chargingData")).getVisibility() == View.VISIBLE, "Fresh charging calculation did not select the charging view");
                grid().requestLayout();
            });
            settle();
            main(() -> require(((View)field(item, "chargingData")).getVisibility() == View.VISIBLE, "Fresh charging fixture did not appear"));
            assertCardContents("battery"); screenshot("minimum-charging-view-fixture");
            main(() -> {
                Object overview = tile("overview");
                invoke(overview, "refresh", new Class<?>[] {Map.class}, preview);
                StringBuilder text = new StringBuilder();
                for (TextView view : texts((View)field(overview, "body"))) if (view.isShown()) text.append(view.getText()).append('\n');
                require(text.toString().contains("50") && text.toString().contains("Charging"),
                        "Scooter overview did not show the fresh charging percentage and Charging caption: " + text);
            });
        } finally {
            main(() -> {
                battery.layouts.get(columns).h = originalHeight; battery.layouts.get(columns).w = originalWidth;
                invoke(item, "refresh", new Class<?>[] {Map.class}, cached);
                invoke(tile("overview"), "refresh", new Class<?>[] {Map.class}, cached);
                dashboardConfig().widgets.remove(seededBattery); invoke(dashboard(), "render");
            }); settleMotion(); stopUiPulse();
        }
        assertGridAndHeader(); assertDefaultCardContents();
    }
    /** The classic Lock card's heading rows; the Lock bar replaces it on the default Home, so swap one in briefly. */
    /**
     * Lock and Lock bar are exclusive and the default uses the Lock bar, so swap the classic Lock
     * into its place (same order and column, settled around the taller card), run the lock state
     * checks, then restore the original widgets and placements.
     */
    private void verifyClassicLockStates(boolean minimumHeights) throws Exception {
        final ArrayList<DashboardConfig.Widget> original = new ArrayList<>();
        final Map<String, Map<Integer, DashboardLayout.Cell>> placements = new LinkedHashMap<>();
        final AtomicReference<DashboardConfig.Widget> classic = new AtomicReference<>();
        main(() -> {
            DashboardConfig config = dashboardConfig(); original.addAll(config.widgets);
            for (DashboardConfig.Widget widget : config.widgets) {
                Map<Integer, DashboardLayout.Cell> cells = new LinkedHashMap<>();
                for (Map.Entry<Integer, DashboardLayout.Cell> entry : widget.layouts.entrySet()) cells.put(entry.getKey(), entry.getValue().copy());
                placements.put(widget.id, cells);
            }
            int at = -1;
            for (int i = 0; i < config.widgets.size(); i++) if (config.widgets.get(i).type.equals("lockbar")) at = i;
            require(at >= 0 && tileOrNull("lock") == null, "Classic Lock pass expects the Lock bar and no Lock");
            DashboardConfig.Widget bar = config.widgets.get(at), lock = new DashboardConfig.Widget("lock");
            for (Map.Entry<Integer, DashboardLayout.Cell> entry : bar.layouts.entrySet()) {
                DashboardLayout.Cell from = entry.getValue();
                lock.layouts.put(entry.getKey(), new DashboardLayout.Cell(lock.id, from.x, from.y, from.w, lock.defaultHeight()));
            }
            config.widgets.set(at, lock);
            for (int c = 1; c <= DashboardConfig.MAX_COLUMNS; c++) if (lock.layouts.containsKey(c)) config.layout(c, lock.id);
            classic.set(lock); invoke(dashboard(), "render");
        }); settleMotion(); stopUiPulse();
        try {
            main(() -> require(classic.get().showDisconnect && tileOrNull("lockbar") == null, "Classic Lock fixture should show Disconnect"));
            assertLockStates();
            if (minimumHeights) {
                int columns = ((Number)field(grid(), "columns")).intValue();
                DashboardConfig.Widget lock = classic.get();
                main(() -> {
                    require(lock.minimumHeight() == 4, "Classic Lock with Disconnect must reserve four rows");
                    lock.showDisconnect = false;
                    require(lock.minimumHeight() == 3, "Hiding Disconnect must allow the three-row classic Lock");
                    lock.showDisconnect = true;
                    lock.layouts.get(columns).h = 4; grid().requestLayout();
                }); settle(); assertLockStates();
            }
        } finally {
            main(() -> {
                DashboardConfig config = dashboardConfig(); config.widgets.clear(); config.widgets.addAll(original);
                for (DashboardConfig.Widget widget : config.widgets) {
                    Map<Integer, DashboardLayout.Cell> cells = placements.get(widget.id);
                    widget.layouts.clear();
                    for (Map.Entry<Integer, DashboardLayout.Cell> entry : cells.entrySet()) widget.layouts.put(entry.getKey(), entry.getValue().copy());
                }
                invoke(dashboard(), "render");
            }); settleMotion(); stopUiPulse();
        }
    }
    private void verifyLockWidthTransitions() {
        if (tileOrNull("lock") != null) { verifyClassicLockWidthTransitions(); return; }
        final ArrayList<DashboardConfig.Widget> original = new ArrayList<>();
        main(() -> {
            DashboardConfig config = dashboardConfig(); original.addAll(config.widgets);
            DashboardConfig.Widget lock = new DashboardConfig.Widget("lock");
            for (int c = 1; c <= DashboardConfig.MAX_COLUMNS; c++)
                lock.layouts.put(c, new DashboardLayout.Cell(lock.id, 0, 0, c, lock.defaultHeight()));
            config.widgets.clear(); config.widgets.add(lock); invoke(dashboard(), "render");
        }); settleMotion(); stopUiPulse();
        try { verifyClassicLockWidthTransitions(); }
        finally {
            main(() -> {
                DashboardConfig config = dashboardConfig(); config.widgets.clear(); config.widgets.addAll(original);
                invoke(dashboard(), "render");
            }); settleMotion(); stopUiPulse();
        }
    }
    private void verifyClassicLockWidthTransitions() {
        main(() -> {
            LinearLayout heading = (LinearLayout)((View)field(activity, "lockTitle")).getParent();
            LinearLayout offline = (LinearLayout)((View)field(activity, "offlineTitle")).getParent().getParent();
            // The heading no longer carries an "Electronic lock" caption to stack beside the state.
            require(heading.getChildCount() == 1, "Lock heading regained a caption beside its state");
            for (LinearLayout row : new LinearLayout[] {offline}) {
                int left = row.getLeft(), top = row.getTop(), width = row.getWidth(), height = row.getHeight(), direction = row.getLayoutDirection();
                boolean ready = row == heading;
                try {
                    for (int layoutDirection : new int[] {View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
                      row.setLayoutDirection(layoutDirection);
                      boolean rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL;
                      for (int widthDp : new int[] {132, 540, 132}) {
                        int targetWidth = activity.ui().dp(widthDp);
                        // Remeasure the parent only, as a resized window does. Forcing its children
                        // would conceal stale child measurement or unresolved margin behavior.
                        row.forceLayout();
                        row.measure(View.MeasureSpec.makeMeasureSpec(targetWidth, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                        row.layout(0, 0, targetWidth, row.getMeasuredHeight());
                        boolean narrow = widthDp == 132;
                        require(row.getOrientation() == (narrow ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL), "Lock header did not adapt to " + widthDp + " dp");
                        View first = row.getChildAt(0), second = row.getChildAt(1);
                        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams)second.getLayoutParams();
                        int gap = activity.ui().dp(ready ? 12 : 10);
                        require(params.leftMargin == (!narrow && !rtl ? gap : 0) && params.rightMargin == (!narrow && rtl ? gap : 0),
                                "Lock header retained stale resolved margins at " + widthDp + " dp: " + params.leftMargin + "," + params.rightMargin);
                        if (ready) {
                            LinearLayout.LayoutParams state = (LinearLayout.LayoutParams)first.getLayoutParams();
                            require(state.width == (narrow ? -1 : 0) && state.weight == (narrow ? 0 : 1), "Lock state retained stale width/weight");
                            require(narrow ? second.getTop() == first.getBottom() + activity.ui().dp(4)
                                    : rtl ? first.getLeft() == second.getRight() + gap : second.getLeft() == first.getRight() + gap,
                                    "Lock caption gap did not follow its orientation");
                        } else {
                            require(first.getVisibility() == (narrow ? View.GONE : View.VISIBLE), "Offline lock symbol did not adapt");
                            require(params.width == (narrow ? -1 : 0) && params.weight == (narrow ? 0 : 1), "Offline status retained stale width/weight");
                            require(narrow ? second.getLeft() == 0 : rtl ? first.getLeft() == second.getRight() + gap
                                    : second.getLeft() == first.getRight() + gap, "Offline words retained a stale start position");
                        }
                        for (View child : new View[] {first, second}) if (child.getVisibility() != View.GONE)
                            require(child.getWidth() > 0 && child.getLeft() >= 0 && child.getTop() >= 0
                                    && child.getRight() <= row.getWidth() && child.getBottom() <= row.getHeight(), "Lock heading child lies outside resized parent");
                      }
                    }
                } finally {
                    row.setLayoutDirection(direction);
                    row.forceLayout();
                    row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                    row.layout(left, top, left + width, top + height);
                }
            }
            ((View)tile("lock")).requestLayout();
        });
        settle();
    }
    private void verifyFoldCallbacks() throws Exception {
        Rect horizontal = new Rect(0, root().getHeight() / 2, root().getWidth(), root().getHeight() / 2);
        Rect vertical = new Rect(root().getWidth() / 2, 0, root().getWidth() / 2, root().getHeight());
        // A foldable's crease, flat or half-open, never reloads, re-splits or replays the screen.
        final Object[] shown = new Object[1];
        main(() -> shown[0] = field(activity, "root"));
        for (Rect crease : new Rect[] {horizontal, vertical}) {
            for (int state : new int[] {1, 2, 1}) {
                inject(new FakeFeature(crease, 1, state)); assertGridAndHeader();
                main(() -> {
                    require(field(activity, "root") == shown[0], "A fold posture change rebuilt the screen");
                    require(!(Boolean)invoke(activity, "horizontalHinge") && !(Boolean)invoke(activity, "verticalHinge"),
                            "A fold crease split the layout");
                });
            }
        }
        screenshot("flat-crease");
        inject(new FakeFeature(horizontal, 1, 2));
        screenshot("half-folded-unchanged");
        // A physical hinge always hides content, so it keeps the split around it in any posture.
        Rect realHinge = new Rect(0, horizontal.top, horizontal.right, horizontal.top + activity.ui().dp(8));
        inject(new FakeFeature(realHinge, 2, 1));
        main(() -> require((Boolean)invoke(activity, "horizontalHinge"), "Physical hinge must remain separating while flat"));
        screenshot("physical-hinge");
        Rect realVertical = new Rect(vertical.left, 0, vertical.left + activity.ui().dp(8), vertical.bottom);
        inject(new FakeFeature(realVertical, 2, 1));
        main(() -> require((Boolean)invoke(activity, "verticalHinge"), "Physical vertical hinge must remain separating while flat"));
        inject(); assertGridAndHeader();
    }
    private void inject(FakeFeature... features) {
        main(() -> invoke(field(activity, "fold"), "layout", new Class<?>[] {Object.class}, new FakeLayout(features)));
        settle(); stopUiPulse();
        await(() -> grid().getWidth() > 0, "Fold callback did not finish layout");
    }
    public static final class FakeLayout {
        private final List<FakeFeature> features;
        public FakeLayout(FakeFeature... values) { features = Arrays.asList(values); }
        public List<FakeFeature> getDisplayFeatures() { return features; }
    }
    public static final class FakeFeature {
        private final Rect bounds; private final int type, state;
        public FakeFeature(Rect bounds, int type, int state) { this.bounds = new Rect(bounds); this.type = type; this.state = state; }
        public Rect getBounds() { return new Rect(bounds); }
        public int getType() { return type; }
        public int getState() { return state; }
    }
    private void assertNoScooterWork() {
        main(() -> {
            ScooterService service = activity.service();
            require(service.profiles.all().size() == 1 && service.profiles.active().mac.isEmpty(), "Unexpected scooter identity");
            require(service.ble.state() == BleController.State.DISCONNECTED && !service.isRecording() && !service.phoneSaving(), "Unexpected Bluetooth or recording work");
            require(((Number)field(service.ble, "mutationSequence")).longValue() == 0 && service.ble.commandResults().isEmpty(), "Unexpected scooter command");
        });
    }
    private void verifyDashboardFollowup() throws Exception {
        main(() -> invoke(dashboard(), "toggleEditing")); settleMotion();
        int before = dashboardConfig().widgets.size();
        main(() -> invoke(dashboard(), "palette")); settle();
        main(() -> {
            described(dialogRoot(), "Add Gauge").performClick();
            require(dashboardConfig().widgets.size() == before, "Unsaved add entered the live layout");
            require(new DashboardConfig(settings(), activity.service().profiles.active().id).widgets.size() == before,
                    "Unsaved add reached persisted layout");
        }); settle();
        main(() -> {
            // Choosing a sub-option must not implicitly save the new card.
            View parameter = null;
            for (TextView text : texts(dialogRoot())) if (text.getText().toString().equals("Parameter")) parameter = (View)text.getParent();
            require(parameter != null, "Gauge parameter option missing"); parameter.performClick();
        }); settle();
        main(() -> {
            boolean chosen = false;
            for (TextView text : texts(dialogRoot())) if (text.getText().toString().equals("Battery voltage")) {
                chosen = ((View)text.getParent()).performClick(); break;
            }
            require(chosen, "Draft parameter choice was not exercised");
            require(dashboardConfig().widgets.size() == before, "Sub-option saved a draft card");
            invoke(dashboard(), "dismiss");
        }); settle();
        main(() -> { invoke(dashboard(), "palette"); }); settle();
        main(() -> described(dialogRoot(), "Add Note").performClick()); settle();
        main(() -> described(dialogRoot(), "Save widget options").performClick()); settleMotion();
        require(dashboardConfig().widgets.size() == before + 1, "Save did not commit exactly one new card");
        main(() -> described(root(), "Undo dashboard edit").performClick()); settleMotion();
        require(dashboardConfig().widgets.size() == before, "Draft cancel consumed Undo or Save was not undoable");
        check("New widget drafts remain unsaved through sub-options and cancellation; only Save adds one undoable card");

        DashboardConfig.Widget quick = new DashboardConfig.Widget("quick");
        main(() -> {
            dashboardConfig().widgets.clear(); dashboardConfig().widgets.add(quick);
            invoke(dashboard(), "render"); invoke(dashboard(), "quickOptions", new Class<?>[]{DashboardConfig.Widget.class}, quick);
        }); settle();
        android.app.AlertDialog sheet = editorDialog();
        main(() -> {
            String second = quick.quickActions.get(1);
            String name = local.navee.offlinelock.QuickAccessActions.find(second).label;
            described(dialogRoot(), "Move " + name + " earlier").performClick();
            require(editorDialog() == sheet, "Reorder recreated the sheet");
            require(quick.quickActions.get(0).equals(second), "Reorder changed wrong shortcut");
        }); settleMotion();
        main(() -> {
            described(dialogRoot(), "Remove shortcut ").performClick();
            require(editorDialog() == sheet, "Remove recreated the sheet");
            require(quick.quickActions.size() == 3, "Remove did not remove one shortcut");
        }); settleMotion(); screenshot("shortcut-sheet-stable");
        main(() -> invoke(dashboard(), "dismiss")); settle();
        if (((Number)invoke(grid(), "maximumColumns")).intValue() >= 5) {
            main(() -> {
                settings().edit().putInt("dashboard_columns", 5).commit();
                DashboardConfig.Widget photo = new DashboardConfig.Widget("scooter");
                photo.layouts.put(5, new DashboardLayout.Cell(photo.id, 0, 0, 4, 4));
                quick.layouts.put(5, new DashboardLayout.Cell(quick.id, 4, 0, 1, 3));
                dashboardConfig().widgets.add(photo); invoke(dashboard(), "render");
            }); settleMotion();
            main(() -> {
                DashboardLayout.Cell cell = (DashboardLayout.Cell)invoke(grid(), "currentCell", new Class<?>[]{DashboardConfig.Widget.class}, quick);
                require(((Number)field(grid(), "columns")).intValue() == 5 && cell.w == 1 && cell.x == 4 && cell.y == 0,
                        "Five-column photo/shortcut split was expanded or displaced");
                float railDp = ((View)tileById(quick.id)).getWidth() / activity.getResources().getDisplayMetrics().density;
                require(cell.h == 3, "One-column shortcut rail cannot shrink to three rows");
            }); screenshot("five-column-shortcut-rail");
        }
        main(() -> {
            View card = (View)tileById(quick.id);
            measureCard(card, activity.ui().dp(70), activity.ui().dp(182));
            ViewGroup quickGrid = (ViewGroup)card.findViewWithTag("quick-access-grid");
            require(Boolean.TRUE.equals(field(quickGrid, "iconsOnly")), "Narrow shortcuts did not switch to icons");
            for (int i = 0; i < quickGrid.getChildCount(); i++) {
                View button = quickGrid.getChildAt(i);
                require(button.getWidth() >= activity.ui().dp(48) && button.getHeight() >= activity.ui().dp(48), "Icon-only button target shrank");
                require(button.getContentDescription() != null, "Icon-only shortcut lost its accessible label");
                for (TextView label : texts(button)) require(label.getVisibility() == View.GONE, "Narrow shortcut still shows text");
            }
            List<View> controls = editorControls(card);
            ArrayList<Rect> occupied = new ArrayList<>();
            for (View control : controls) {
                Rect box = relativeBounds(control, card);
                require(control.getWidth() >= activity.ui().dp(48) && control.getHeight() >= activity.ui().dp(48), "Narrow editor target shrank");
                require(box.top >= 0 && box.bottom <= card.getHeight(), "Narrow editor target escaped card");
                for (Rect other : occupied) require(!Rect.intersects(box, other), "Narrow editor controls overlap");
                occupied.add(box);
            }
            saveCardBitmap(card, "narrow-shortcut-editor");
            require(controls.size() == 3, "Tiny rail needs only Move/Options/Resize on the card");
            require(quick.minimumHeight(70) == 3, "Tiny rail still requires four rows");
            for (int width : new int[]{58, 70, 72, 94, 96, 113, 143}) {
                require(quick.minimumHeight(width) == 3, "Two-column editor rail cannot shrink to three rows");
                measureCard(card, activity.ui().dp(width), activity.ui().dp(182));
                ArrayList<Rect> boxes = new ArrayList<>();
                for (View control : editorControls(card)) {
                    Rect box = relativeBounds(control, card);
                    require(control.getWidth() >= activity.ui().dp(48) && control.getHeight() >= activity.ui().dp(48), "Three-row rail shrank an editor target");
                    require(box.left >= 0 && box.right <= card.getWidth() && box.top >= 0 && box.bottom <= card.getHeight(), "Three-row rail editor target escaped card");
                    for (Rect other : boxes) require(!Rect.intersects(box, other), "Three-row rail editor targets overlap");
                    boxes.add(box);
                }
                require(card.findViewWithTag("quick-access-grid") != null, "Three-row rail lost shortcuts");
                saveCardBitmap(card, "three-row-shortcut-editor-" + width);
            }
        });
        main(() -> {
            View card = (View)tileById(quick.id);
            measureCard(card, activity.ui().dp(72), activity.ui().dp(182));
            described(card, "Options for ").performClick();
            android.widget.PopupMenu menu = (android.widget.PopupMenu)field(card.findViewWithTag("widget-editor-bar"), "actionsMenu");
            require(menu != null && menu.getMenu().size() == 3, "Tiny rail actions menu missing");
            menu.getMenu().performIdentifierAction(1, 0);
        }); settle();
        main(() -> {
            require(described(dialogRoot(), "Save widget options") != null, "Tiny rail Edit did not open existing options");
            require(described(dialogRoot(), "Duplicate widget") == null && described(dialogRoot(), "Remove widget") == null, "Actions leaked into Save-only options sheet");
            invoke(dashboard(), "dismiss");
        }); settle();
        final int beforeDuplicate = dashboardConfig().widgets.size();
        main(() -> {
            View card = (View)tileById(quick.id);
            measureCard(card, activity.ui().dp(72), activity.ui().dp(182));
            described(card, "Options for ").performClick();
            android.widget.PopupMenu menu = (android.widget.PopupMenu)field(card.findViewWithTag("widget-editor-bar"), "actionsMenu");
            require(menu.getMenu().findItem(2).isEnabled(), "Tiny rail lost Duplicate");
            menu.getMenu().performIdentifierAction(2, 0);
        }); settleMotion();
        require(dashboardConfig().widgets.size() == beforeDuplicate + 1, "Tiny rail actions did not duplicate");
        main(() -> described(root(), "Undo dashboard edit").performClick()); settleMotion();
        main(() -> {
            View card = (View)tileById(quick.id);
            measureCard(card, activity.ui().dp(72), activity.ui().dp(182));
            described(card, "Options for ").performClick();
            android.widget.PopupMenu menu = (android.widget.PopupMenu)field(card.findViewWithTag("widget-editor-bar"), "actionsMenu");
            menu.getMenu().performIdentifierAction(3, 0);
        }); settleMotion();
        require(dashboardConfig().widgets.size() == beforeDuplicate - 1, "Tiny rail actions did not remove");
        main(() -> described(root(), "Undo dashboard edit").performClick()); settleMotion();
        require(dashboardConfig().widgets.size() == beforeDuplicate, "Undo did not restore deleted rail");
        main(() -> invoke(dashboard(), "toggleEditing")); settleMotion();
        DashboardConfig.Widget recorder = new DashboardConfig.Widget("ride");
        DashboardConfig.Widget scooter = new DashboardConfig.Widget("scooter");
        main(() -> {
            settings().edit().putInt("dashboard_columns", 2).commit();
            dashboardConfig().widgets.clear(); dashboardConfig().widgets.add(recorder); dashboardConfig().widgets.add(scooter);
            recorder.layouts.put(2, new DashboardLayout.Cell(recorder.id, 0, 8, 2, 1));
            scooter.layouts.put(2, new DashboardLayout.Cell(scooter.id, 0, 10, 2, 3));
            scooter.keys.clear(); scooter.keys.addAll(Arrays.asList("odometer_km", "remaining_range_km", "driving_mode"));
            invoke(dashboard(), "render");
        }); settleMotion();
        main(() -> {
            int first = Integer.MAX_VALUE;
            for (Object card : tiles()) first = Math.min(first, ((View)card).getTop());
            require(first == 0, "Layout kept a leading gap");
            View card = (View)tileById(recorder.id);
            measureCard(card, activity.ui().dp(300), activity.ui().dp(54));
            ScrollView viewport = (ScrollView)field(card, "viewport");
            require(!viewport.canScrollVertically(1), "One-row recorder requires scrolling");
            require(((View)field(card, "rideButton")).getHeight() >= activity.ui().dp(48), "One-row recorder touch target shrank");
            saveCardBitmap(card, "one-row-recorder");
            View scooterCard = (View)tileById(scooter.id);
            measureCard(scooterCard, activity.ui().dp(310), activity.ui().dp(182 * activity.ui().textScale
                    * getTargetContext().getResources().getConfiguration().fontScale));
            Map<String,TextView> values = (Map<String,TextView>)field(scooterCard, "amounts");
            float size = values.values().iterator().next().getTextSize();
            for (TextView value : values.values()) require(Math.abs(value.getTextSize() - size) < .1f, "Scooter values use different sizes");
            require(!((ScrollView)field(scooterCard, "viewport")).canScrollVertically(1), "Three compact scooter readings unnecessarily scroll: body=" + ((View)field(scooterCard, "body")).getHeight() + " viewport=" + ((View)field(scooterCard, "viewport")).getHeight() + " info=" + ((View)field(scooterCard, "scooterInfo")).getHeight() + " size=" + size);
            saveCardBitmap(scooterCard, "scooter-common-values");
        });
        check("Leading gaps normalize; narrow shortcuts retain safe icon/editor targets; recorder fits one row and scooter numbers share a fitted size");
        main(() -> { invoke(dashboard(), "toggleEditing"); }); settleMotion();
        main(() -> {
            View card = (View)tileById(recorder.id);
            measureCard(card, activity.ui().dp(300), activity.ui().dp(54));
            ArrayList<Rect> occupied = new ArrayList<>();
            for (View control : editorControls(card)) {
                Rect box = relativeBounds(control, card);
                require(box.left >= 0 && box.top >= 0 && box.right <= card.getWidth() && box.bottom <= card.getHeight(), "One-row recorder editor escaped card");
                for (Rect other : occupied) require(!Rect.intersects(box, other), "One-row recorder editor controls overlap");
                occupied.add(box);
            }
            saveCardBitmap(card, "one-row-recorder-editor");
            invoke(dashboard(), "chooseReadings", new Class<?>[]{DashboardConfig.Widget.class}, scooter);
        }); settle();
        screenshot("pinned-selected-readings");
        main(() -> {
            View root = dialogRoot();
            require(root.findViewWithTag("selected-reading:odometer_km") != null, "Selected reading not pinned");
            require(root.findViewWithTag("reading-choice:odometer_km") != null, "Pinned reading missing from original group");
            ((android.widget.EditText)described(root, "Search readings")).setText("battery");
            require(root.findViewWithTag("selected-reading:odometer_km").isShown(), "Search hid pinned selection");
            root.findViewWithTag("selected-reading:odometer_km").performClick();
            require(root.findViewWithTag("selected-reading:odometer_km") == null, "Pinned uncheck failed");
            require(!root.findViewWithTag("reading-choice:odometer_km").isSelected(), "Pinned and original checkboxes disagree");
            described(root, "Save selected readings").performClick();
            require(!scooter.keys.contains("odometer_km"), "Pinned uncheck not saved");
        }); settle();
        main(() -> invoke(dashboard(), "dismiss"));
        check("Selected reading copies remain at the top during search and uncheck/save synchronizes both copies");
        main(() -> {
            quick.quickActions.clear(); quick.quickActions.addAll(Arrays.asList("feature:timed_charging", "stats"));
            dashboardConfig().widgets.clear(); dashboardConfig().widgets.add(quick);
            settings().edit().putString("time_format", "12h").commit();
            invoke(dashboard(), "render");
        }); settle();
        main(() -> {
            View button = ((View)tileById(quick.id)).findViewWithTag("quick-action:feature:timed_charging");
            String value = ((TextView)field(button, "value")).getText().toString();
            require(value.startsWith("On • ") && value.contains("1:45") && value.contains("PM"), "Scheduled shortcut lost its 12-hour time: " + value);
            settings().edit().putString("time_format", "24h").commit(); invoke(dashboard(), "refresh");
            value = ((TextView)field(button, "value")).getText().toString();
            require(value.equals("On • 13:45"), "Scheduled shortcut lost its 24-hour time: " + value);
            require(button.getContentDescription().toString().contains("13:45"), "Icon-only schedule lost clock accessibility");
        });
        check("Scheduled charging shortcut shows the stored time in the selected 12/24-hour format and exposes it accessibly");
    }

    private void verifyQuickAccess() throws Exception {
        DashboardConfig.Widget widget = new DashboardConfig.Widget("quick");
        main(() -> { dashboardConfig().widgets.clear(); dashboardConfig().widgets.add(widget); dashboardConfig().save(); invoke(dashboard(), "render"); });
        settleMotion();
        main(() -> {
            View card = (View)tileById(widget.id);
            int width = Math.min(card.getWidth(), activity.ui().dp(310));
            measureCard(card, width, activity.ui().dp(190 * activity.ui().textScale * getTargetContext().getResources().getConfiguration().fontScale));
            ScrollView viewport = (ScrollView)field(card, "viewport");
            require(!viewport.canScrollVertically(1) && !viewport.canScrollVertically(-1), "Four default quick buttons still overflow the two-row card");
            assertQuickButtons(card, widget.quickActions);
            saveCardBitmap(card,"quick-four-fit");
            measureCard(card, width, activity.ui().dp(118));
            require(viewport.canScrollVertically(1), "Short quick card lost scrolling for the second row");
            assertQuickButtons(card, widget.quickActions);
        });
        screenshot("quick-defaults");
        ArrayList<String> every = new ArrayList<>();
        for (local.navee.offlinelock.QuickAccessActions.Entry entry : local.navee.offlinelock.QuickAccessActions.catalog()) every.add(entry.id);
        for (int start = 0; start < every.size(); start += 8) {
            final int offset = start;
            main(() -> {
                widget.quickActions.clear(); widget.quickActions.addAll(every.subList(offset, Math.min(every.size(), offset+8)));
                while (widget.quickActions.size()<2) widget.quickActions.add("stats");
                invoke(dashboard(), "render");
            }); settle();
            main(() -> {
                View card = (View)tileById(widget.id);
                measureCard(card, Math.min(card.getWidth(), activity.ui().dp(310)), activity.ui().dp(330 * activity.ui().textScale));
                assertQuickButtons(card, widget.quickActions);
                saveCardBitmap(card,"quick-catalog-"+offset);
            });
        }
        check("Four default quick actions fit without tiny overflow in a two-row card; shorter cards scroll and every catalog label fits its button");
        main(() -> {
            widget.quickActions.clear(); widget.quickActions.addAll(Arrays.asList("feature:traction_control", "feature:start_speed", "feature:energy_recovery", "section:Battery"));
            dashboardConfig().save(); invoke(dashboard(), "render");
            require(settings().edit().putBoolean("developer_options", true).commit(), "Could not enable isolated simulator");
            require(activity.service().setSimulation(true), "Could not start isolated simulator");
        }); settleMotion(); stopUiPulse();
        await(() -> field(activity, "screen") instanceof DashboardScreen && activity.service().simulating(), "Simulator dashboard did not rebuild");
        main(() -> {
            BleController ble = activity.service().ble;
            Integer before = ble.featureValue("traction_control"); require(before!=null, "Missing sample traction state");
            View button = ((View)tileById(widget.id)).findViewWithTag("quick-action:feature:traction_control");
            require(button!=null && button.isEnabled(), "Connected quick toggle is unavailable"); button.performClick();
            require(Integer.valueOf(before==0?1:0).equals(ble.featureValue("traction_control")), "Quick toggle failed to change the sample feature in place");
            require("home".equals(field(activity,"selectedPage")) && field(activity,"screen") instanceof DashboardScreen, "Quick toggle navigated away from Home");
        }); settle();
        verifySettingTilesDispatch();
        for (String action : new String[]{"feature:start_speed", "feature:energy_recovery", "section:Battery"}) {
            main(() -> { invoke(activity,"quickAction",new Class<?>[]{String.class},action); }); settle();
            main(() -> {
                require("home".equals(field(activity,"selectedPage")) && field(activity,"screen") instanceof DashboardScreen,"Quick menu navigated away from Home: "+action);
                android.app.Dialog dialog = (android.app.Dialog)field(activity,action.startsWith("section:")?"quickMenuDialog":"controlDialog");
                require(dialog!=null && dialog.isShowing(),"Quick menu did not open an overlay: "+action);
                View menu = dialog.getWindow().getDecorView();
                for(TextView text:texts(menu)) if(text.isShown() && text.getWidth()>0) assertText(text,text,"Quick menu");
                if(action.equals("feature:energy_recovery")) {
                    Object choice=field(activity,"choiceSheet");int[] values=(int[])field(choice,"choices");
                    int current=activity.service().ble.featureValue("energy_recovery");
                    int index=values[0]==current?1:0;
                    invoke(choice,"choose",new Class<?>[]{int.class},index);
                    require(Integer.valueOf(values[index]).equals(activity.service().ble.featureValue("energy_recovery")),"Quick choice did not update the sample feature");
                    require(!dialog.isShowing(),"Sample readback did not dismiss the Home quick-choice sheet");
                } else dialog.dismiss();
            }); settle();
        }
        for(String id:new String[]{"more:App","section:Security"}) {
            final String moreBefore=settings().getString("more_section", "");
            main(() -> invoke(activity,"quickAction",new Class<?>[]{String.class},id));settle();
            main(() -> {
                android.app.Dialog dialog=(android.app.Dialog)field(activity,"quickMenuDialog");
                require(dialog!=null&&dialog.isShowing()&&field(activity,"quickMoreScreen")!=null,"Standalone More quick panel missing: "+id);
                require("home".equals(field(activity,"selectedPage"))&&field(activity,"screen") instanceof DashboardScreen,"Standalone More panel navigated away from Home");
                require(settings().getString("more_section", "").equals(moreBefore),"Standalone panel changed saved More submenu");
                dialog.dismiss();
            });
            await(() -> field(activity,"quickMenuDialog")==null&&field(activity,"quickMoreScreen")==null,"Standalone More panel leaked references after dismissal");settle();
        }
        check("App preferences and Security overlays preserve Home and saved More navigation, then release their temporary screen references");
        main(() -> { activity.service().setSimulation(false); }); settleMotion(); stopUiPulse();
        assertNoScooterWork();
        check("Sample traction toggles in place; kick-start, energy recovery and settings sections open over Home and dismiss without navigation or real scooter work");
        main(() -> { invoke(dashboard(),"toggleEditing"); });settleMotion();
        verifyQuickOptions((DashboardConfig.Widget)field(tileById(widget.id),"widget"));
        main(() -> {
            DashboardConfig.Widget source=(DashboardConfig.Widget)field(tileById(widget.id),"widget");
            invoke(dashboard(),"duplicateWidget",new Class<?>[]{DashboardConfig.Widget.class},source);
            DashboardConfig.Widget copy=dashboardConfig().widgets.get(dashboardConfig().widgets.size()-1);
            require(!copy.id.equals(source.id) && copy.quickActions.equals(source.quickActions) && copy.quickActions!=source.quickActions,"Quick duplicate did not copy independent ordered settings");
            invoke(dashboard(),"toggleEditing");
        });settleMotion();
        seedEveryWidget(); verifyEditOverlays();
        verifyAddedWidgetReveal();
    }
    /** Setting tiles reuse Quick access dispatch: in the isolated simulator, a toggle tile changes the sample in place. */
    private void verifySettingTilesDispatch() throws Exception {
        final DashboardConfig.Widget settingTiles = new DashboardConfig.Widget("shortcuts");
        require(settingTiles.quickActions.equals(Arrays.asList("feature:maximum_speed", "feature:timed_charging")),
                "Setting tiles default shortcuts changed");
        main(() -> {
            settingTiles.quickActions.clear();
            settingTiles.quickActions.addAll(Arrays.asList("feature:traction_control", "feature:start_speed"));
            int end = 0;
            for (DashboardConfig.Widget widget : dashboardConfig().widgets)
                for (DashboardLayout.Cell cell : widget.layouts.values()) end = Math.max(end, cell.y + cell.h);
            for (int c = 1; c <= DashboardConfig.MAX_COLUMNS; c++)
                settingTiles.layouts.put(c, new DashboardLayout.Cell(settingTiles.id, 0, end, c, settingTiles.defaultHeight()));
            dashboardConfig().widgets.add(settingTiles); invoke(dashboard(), "render");
        }); settleMotion(); stopUiPulse();
        main(() -> {
            BleController ble = activity.service().ble;
            Integer before = ble.featureValue("traction_control"); require(before != null, "Missing sample traction state");
            View card = (View)tileById(settingTiles.id);
            View traction = tileAction(card, local.navee.offlinelock.QuickAccessActions.find("feature:traction_control").label);
            View kick = tileAction(card, local.navee.offlinelock.QuickAccessActions.find("feature:start_speed").label);
            require(traction != null && kick != null && traction != kick, "Setting tiles did not show two separate tiles");
            require(traction.isEnabled() && traction.getWidth() >= activity.ui().dp(48) && traction.getHeight() >= activity.ui().dp(48),
                    "Connected setting tile is not a full-size enabled tap target");
            traction.performClick();
            require(Integer.valueOf(before == 0 ? 1 : 0).equals(ble.featureValue("traction_control")),
                    "Setting tile did not dispatch its Quick access toggle");
            require("home".equals(field(activity,"selectedPage")) && field(activity,"screen") instanceof DashboardScreen,
                    "Setting tile navigated away from Home");
        }); settle();
        main(() -> { dashboardConfig().widgets.remove(settingTiles); invoke(dashboard(), "render"); });
        settleMotion(); stopUiPulse();
        check("Setting tiles show one tile per shortcut and a tap dispatches the same Quick access action in place");
    }
    /** The clickable tile holding a visible label, or a clickable view described by that label. */
    private View tileAction(View root, String label) {
        if (root.isShown() && root.isClickable()) {
            CharSequence description = root.getContentDescription();
            if (description != null && description.toString().toLowerCase(Locale.ROOT).startsWith(label.toLowerCase(Locale.ROOT))) return root;
        }
        if (root instanceof TextView && root.isShown() && label.equalsIgnoreCase(((TextView)root).getText().toString())) {
            View action = root;
            while (!action.isClickable() && action.getParent() instanceof View) action = (View)action.getParent();
            if (action.isClickable()) return action;
        }
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)root).getChildCount(); i++) {
            View found = tileAction(((ViewGroup)root).getChildAt(i), label); if (found != null) return found;
        }
        return null;
    }
    /** The lock control Home shows first: the Lock bar by default, or whichever lock card sits highest. */
    private Object lockTile() {
        Object best = null;
        for (Object item : tiles()) {
            String type = ((DashboardConfig.Widget)field(item, "widget")).type;
            if ((type.equals("lock") || type.equals("lockbar")) && (best == null || ((View)item).getTop() < ((View)best).getTop())) best = item;
        }
        require(best != null, "Missing lock control");
        return best;
    }
    private Object tileOrNull(String type) {
        for (Object item : tiles()) if (((DashboardConfig.Widget)field(item, "widget")).type.equals(type)) return item;
        return null;
    }
    private void setVisibilityIfPresent(String name, int visibility) {
        Object view = optionalField(activity, name);
        if (view instanceof View) ((View)view).setVisibility(visibility);
    }
    private static Object optionalField(Object target, String name) {
        try { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
        catch (Exception error) { return null; }
    }
    private void saveCardBitmap(View card,String name) {
        Bitmap bitmap=Bitmap.createBitmap(card.getWidth(),card.getHeight(),Bitmap.Config.ARGB_8888);
        try {card.draw(new android.graphics.Canvas(bitmap));saveNativeBitmap(bitmap,name);}finally{bitmap.recycle();}
    }
    private void measureCard(View card,int width,int height) {
        for(int pass=0;pass<3;pass++) {
            forceMeasure(card); card.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
            card.layout(card.getLeft(),card.getTop(),card.getLeft()+width,card.getTop()+height);
        }
    }
    private void assertQuickButtons(View card,List<String> ids) {
        View grid=card.findViewWithTag("quick-access-grid"); require(grid instanceof ViewGroup,"Quick action grid missing");
        ArrayList<Rect> bounds=new ArrayList<>();
        for(String id:ids) {
            View button=card.findViewWithTag("quick-action:"+id); require(button!=null,"Quick action missing: "+id);
            require(button.getHeight()>=activity.ui().dp(48) && button.getWidth()>=activity.ui().dp(48),"Quick target fell below 48dp: "+id);
            Rect box=relativeBounds(button,grid); require(box.left>=0 && box.top>=0 && box.right<=grid.getWidth()+1 && box.bottom<=grid.getHeight()+1,"Quick button escaped its grid");
            for(Rect other:bounds)require(!Rect.intersects(box,other),"Quick targets overlap"); bounds.add(box);
            List<TextView> labels=texts(button);require(!labels.isEmpty(),"Quick action has no readable name: "+id);
            for(TextView label:labels)assertText(label,button,"Quick "+id);
        }
    }
    private void verifyQuickOptions(DashboardConfig.Widget widget) throws Exception {
        main(() -> invoke(dashboard(),"quickOptions",new Class<?>[]{DashboardConfig.Widget.class},widget));settle();
        while(widget.quickActions.size()>2) {
            main(() -> {View remove=described(dialogRoot(),"Remove shortcut ");require(remove!=null&&remove.isEnabled(),"Remove shortcut unavailable above minimum");remove.performClick();});settle();
        }
        main(() -> {
            View remove=described(dialogRoot(),"Remove shortcut ");require(remove!=null&&!remove.isEnabled(),"Quick options permit fewer than two items");
        });
        while(widget.quickActions.size()<8) {
            final int before=widget.quickActions.size();
            final AtomicReference<String> selected=new AtomicReference<>();
            main(() -> {View add=described(dialogRoot(),"Add quick shortcut");require(add!=null&&add.isEnabled(),"Add shortcut missing before maximum");add.performClick();});settle();
            main(() -> {
                android.widget.EditText search=(android.widget.EditText)described(dialogRoot(),"Search quick settings");require(search!=null,"Quick choice search missing");
                search.setText("nothing_matches_this_fixture");require(dialogRoot().findViewWithTag("quick-choice:stats")==null,"Quick search did not filter choices");search.setText("");
                for(String id:widget.quickActions) {View duplicate=dialogRoot().findViewWithTag("quick-choice:"+id);require(duplicate!=null&&!duplicate.isEnabled(),"Picker permits duplicate shortcut");}
                for(local.navee.offlinelock.QuickAccessActions.Entry entry:local.navee.offlinelock.QuickAccessActions.catalog()) if(!widget.quickActions.contains(entry.id)) {
                    View choose=dialogRoot().findViewWithTag("quick-choice:"+entry.id);require(choose!=null&&choose.isEnabled(),"Available shortcut not selectable");selected.set(entry.id);choose.performClick();break;
                }
                require(widget.quickActions.size()==before+1 && widget.quickActions.get(before).equals(selected.get()),"Shortcut append lost selection/order");
            });settle();
        }
        screenshot("quick-eight-options");
        main(() -> {
            require(described(dialogRoot(),"Add quick shortcut")==null,"Quick options permit more than eight items");
            for(TextView text:texts(dialogRoot()))if(text.isShown()&&text.getWidth()>0)assertText(text,text,"Quick options label");
            String second=widget.quickActions.get(1);local.navee.offlinelock.QuickAccessActions.Entry entry=local.navee.offlinelock.QuickAccessActions.find(second);
            View earlier=described(dialogRoot(),"Move "+entry.label+" earlier");require(earlier!=null&&earlier.isEnabled(),"Shortcut reorder control unavailable");earlier.performClick();
            require(widget.quickActions.get(0).equals(second),"Shortcut reorder did not move selected action");
            invoke(dashboard(),"dismiss");
        });settle();
        check("Quick options enforce 2–8 independent ordered choices, searchable named actions and duplicate prevention without executing settings");
    }

    private void verifyAddedWidgetReveal() throws Exception {
        main(() -> { invoke(dashboard(),"toggleEditing"); });settleMotion();
        main(() -> {
            ScrollView scroll=(ScrollView)field(activity,"scroll");scroll.scrollTo(0,0);
            invoke(dashboard(),"palette");
        });settle();
        final AtomicReference<String> added=new AtomicReference<>();
        main(() -> {
            View add=described(dialogRoot(),"Add Note");require(add!=null,"Note add action missing");add.performClick();
            added.set((String)field(dashboard(),"pendingAddedWidgetId"));
        });settleMotion();
        main(() -> {
            require(added.get().equals(field(dashboard(),"pendingAddedWidgetId")),"New widget not tracked until options are saved");
            for(TextView text:texts(dialogRoot()))require(!text.getText().toString().equals("Size"),"Redundant Size option remains");
            View save=described(dialogRoot(),"Save widget options");require(save!=null,"New widget Save missing");save.performClick();
        });
        await(() -> ((ScrollView)field(activity,"scroll")).getScrollY()>0,"Saving offscreen widget did not scroll toward it");
        final AtomicReference<Object> reveal=new AtomicReference<>();
        main(() -> {
            Object effect=field(dashboard(),"widgetReveal");require(effect!=null,"New widget arrival outline was not created");reveal.set(effect);
            if(!local.navee.offlinelock.Ui.motionEnabled())require(field(effect,"scrolling")==null && field(effect,"highlighting")==null,"Reduced motion started a reveal animator");
        });
        await(() -> Boolean.TRUE.equals(field(reveal.get(),"overlayAdded")) && ((Number)field(field(reveal.get(),"glow"),"strength")).floatValue()>0, "Arrival outline never became visible");
        main(() -> {
            View tile=(View)tileById(added.get());Rect visible=new Rect();
            require(tile.getGlobalVisibleRect(visible) && visible.height()>=Math.min(tile.getHeight(),activity.ui().dp(48)),"Added widget still not visible after reveal");
            require(editorDialog()==null && field(dashboard(),"pendingAddedWidgetId")==null,"Save did not dismiss options and consume pending add");
            require(Boolean.TRUE.equals(field(reveal.get(),"overlayAdded")),"New widget outline vanished before its arrival cue");
            Object glow=field(reveal.get(),"glow");require(((Number)field(glow,"strength")).floatValue()>0,"Arrival outline never became visible");
            require((((android.graphics.Paint)field(glow,"paint")).getColor() & 0xffffff)==0xffffff,"Arrival outline is not white");
        });screenshot("new-widget-reveal");
        SystemClock.sleep(1600);waitForIdleSync();
        main(() -> {
            require(Boolean.TRUE.equals(field(reveal.get(),"finished")) && Boolean.FALSE.equals(field(reveal.get(),"overlayAdded"))
                    && field(reveal.get(),"scrolling")==null && field(reveal.get(),"highlighting")==null,"Finished arrival cue retained its overlay or animator");
            View tile=(View)tileById(added.get());
            local.navee.offlinelock.WidgetReveal focus=local.navee.offlinelock.WidgetReveal.start(tile,(ScrollView)field(activity,"scroll"),activity.ui());
            require(focus!=null,"Lifecycle reveal fixture failed");focus.onWindowFocusChanged(false);
            require(Boolean.TRUE.equals(field(focus,"finished")) && Boolean.FALSE.equals(field(focus,"overlayAdded")),"Focus loss retained arrival cue");
            local.navee.offlinelock.WidgetReveal detach=local.navee.offlinelock.WidgetReveal.start(tile,(ScrollView)field(activity,"scroll"),activity.ui());
            require(detach!=null,"Detach reveal fixture failed");detach.onViewDetachedFromWindow(tile);
            require(Boolean.TRUE.equals(field(detach,"finished")),"Detached widget retained arrival cue");
            invoke(dashboard(),"toggleEditing");
        });settleMotion();
        check("Saving a new offscreen widget closes options, gently scrolls it into view and clears the pending add; edit controls retain equal larger targets");
    }

    private void verifyGaugeContent() throws Exception {
        final DashboardConfig.Widget widget = new DashboardConfig.Widget("gauge");
        widget.title = "Speed gauge"; widget.metric = "speed_kmh"; widget.minimum = 0; widget.maximum = 50;
        main(() -> { dashboardConfig().widgets.clear(); dashboardConfig().widgets.add(widget); dashboardConfig().save(); invoke(dashboard(), "render"); });
        settleMotion();
        int columns = ((Number)field(grid(), "columns")).intValue();
        int minimum = ((Number)invoke(grid(), "minimumWidth", new Class<?>[] {DashboardConfig.Widget.class}, widget)).intValue();
        for (int width : new int[] {minimum, columns}) for (int height : new int[] {widget.minimumHeight(), widget.defaultHeight()+2}) {
            main(() -> { widget.layouts.put(columns, new DashboardLayout.Cell(widget.id, 0, 0, width, height)); invoke(grid(), "settle", new Class<?>[] {String.class}, widget.id); });
            settleMotion();
            for (String state : new String[] {"live", "dual", "unknown", "stale", "long"}) {
                main(() -> {
                    Object item = tileById(widget.id); View gauge = (View)field(item, "gauge");
                    require(gauge.getWidth() > 0 && gauge.getHeight() > 0, "Gauge has no drawing area");
                    View viewport = (View)field(item, "viewport");
                    SharedPreferences.OnSharedPreferenceChangeListener listener = (SharedPreferences.OnSharedPreferenceChangeListener)field(activity, "settingsChanged");
                    String previous = settings().getString("units", "mi"); settings().unregisterOnSharedPreferenceChangeListener(listener);
                    try {
                        // Exercise real formatting without rebuilding the Activity halfway through this draw-only fixture.
                        settings().edit().putString("units", state.equals("dual") ? "dual" : "mi").commit();
                        double number = state.equals("long") ? -12345.678 : state.equals("stale") ? -21.25 : 27.3;
                        TelemetryHistory.Reading reading = state.equals("unknown") ? null : new TelemetryHistory.Reading("speed_kmh", "Speed", "Synthetic fixture",
                                number + " km/h", "km/h", number, System.currentTimeMillis()-60000, !state.equals("stale"));
                        Map<String, TelemetryHistory.Reading> values = new LinkedHashMap<>(activity.service().displayReadings());
                        if (reading == null) values.remove(widget.metric); else values.put(widget.metric, reading);
                        invoke(item, "refresh", new Class<?>[] {Map.class}, values);
                        View card = (View)item;
                        for (int pass = 0; pass < 3; pass++) {
                            forceMeasure(card); card.measure(View.MeasureSpec.makeMeasureSpec(card.getWidth(), View.MeasureSpec.EXACTLY),
                                    View.MeasureSpec.makeMeasureSpec(card.getHeight(), View.MeasureSpec.EXACTLY));
                            card.layout(card.getLeft(), card.getTop(), card.getRight(), card.getBottom());
                        }
                        Rect target = relativeBounds(gauge, viewport);
                        require(target.left >= 0 && target.top >= 0 && target.right <= viewport.getWidth() && target.bottom <= viewport.getHeight(), "Gauge view escapes its card");
                        TextView footer = (TextView)field(item, "footer");
                        require(!state.equals("stale") || footer.getVisibility() == View.VISIBLE && footer.getText().toString().startsWith("Last seen"), "Stale gauge lost its visible timestamp");
                        Bitmap image = Bitmap.createBitmap(gauge.getWidth(), gauge.getHeight(), Bitmap.Config.ARGB_8888);
                        try {
                            android.graphics.Canvas canvas = new android.graphics.Canvas(image); canvas.drawColor(activity.ui().surface); gauge.draw(canvas);
                            android.graphics.RectF arc = (android.graphics.RectF)field(gauge, "arcBounds");
                            float stroke = ((Number)field(gauge, "arcStroke")).floatValue();
                            require(((Number)field(gauge, "SWEEP_ANGLE")).floatValue() == 270f && !arc.isEmpty(), "Gauge is no longer a 270-degree ring");
                            float ringBottom = arc.centerY()+arc.width()/2f*(float)Math.sqrt(.5)+stroke/2f;
                            require(arc.left-stroke/2 >= -1 && arc.right+stroke/2 <= gauge.getWidth()+1 && arc.top-stroke/2 >= -1 && ringBottom <= gauge.getHeight()+1, "Gauge ring is clipped");
                            android.graphics.RectF block = new android.graphics.RectF();
                            for (String fieldName : new String[] {"valueBounds", "unitBounds", "secondaryBounds", "minimumLabelBounds", "maximumLabelBounds"}) {
                                android.graphics.RectF box = (android.graphics.RectF)field(gauge, fieldName);
                                if (box.isEmpty()) continue;
                                require(box.left >= -1 && box.top >= -1 && box.right <= gauge.getWidth()+1 && box.bottom <= gauge.getHeight()+1, fieldName + " is clipped in " + state + ": " + box);
                                if (fieldName.endsWith("LabelBounds")) require(box.top >= ringBottom+activity.ui().dp(2), "Range label touches the ring");
                                else {
                                    block.union(box);
                                    float radius = arc.width()/2f-stroke/2;
                                    for (float xx : new float[] {box.left, box.right}) for (float yy : new float[] {box.top, box.bottom})
                                        require(Math.hypot(xx-arc.centerX(), yy-arc.centerY()) < radius, "Gauge reading touches the ring");
                                }
                            }
                            require(!block.isEmpty() && Math.abs(block.centerY()-arc.centerY()) <= activity.ui().dp(2), "Gauge reading is not vertically centered");
                            android.graphics.RectF low = (android.graphics.RectF)field(gauge, "minimumLabelBounds"), high = (android.graphics.RectF)field(gauge, "maximumLabelBounds");
                            require(!low.isEmpty() && !high.isEmpty() && !android.graphics.RectF.intersects(low, high), "Gauge range labels overlap or disappeared");
                            require(!state.equals("dual") || !((android.graphics.RectF)field(gauge, "secondaryBounds")).isEmpty(), "Dual units lost the second reading");
                            require(!state.equals("unknown") || field(gauge, "amount").equals("—"), "Unknown reading became a numeric value");
                            require(!state.equals("stale") || gauge.getContentDescription().toString().contains("last seen"), "Stale gauge lost its accessible state");
                            if (state.equals("live") || state.equals("dual")) saveNativeBitmap(image, "gauge-"+width+"x"+height+"-"+state);
                        } finally { image.recycle(); }
                    } finally { settings().edit().putString("units", previous).commit(); settings().registerOnSharedPreferenceChangeListener(listener); }
                });
            }
        }
        main(() -> { invoke(tileById(widget.id), "refresh", new Class<?>[] {Map.class}, activity.service().displayReadings()); });
        screenshot("gauge-card");
    }
    private void saveNativeBitmap(Bitmap bitmap, String name) {
        File folder = new File(getTargetContext().getExternalFilesDir(null), "responsive-validation"); require(folder.isDirectory() || folder.mkdirs(), "Cannot create gauge evidence folder");
        File output = new File(folder, arguments.getString("variant", "responsive").replaceAll("[^A-Za-z0-9_-]", "")+"-"+name+".png");
        try (FileOutputStream stream = new FileOutputStream(output)) { require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream), "Gauge evidence failed"); }
        catch (Exception error) { throw new IllegalStateException(error); }
        screenshots.add(output.getAbsolutePath());
    }
    private void seedEveryWidget() {
        main(() -> {
            DashboardConfig config = dashboardConfig(); config.widgets.clear();
            for (String type : DashboardConfig.TYPES) {
                DashboardConfig.Widget widget = new DashboardConfig.Widget(type);
                if (type.equals("metric") || type.equals("live")) widget.metric = "power_w";
                if (type.equals("history")) widget.metric = "battery_percent";
                if (type.equals("gauge")) { widget.metric = "speed_kmh"; widget.maximum = 50; }
                if (type.equals("note")) widget.note = "UI test layout";
                config.widgets.add(widget);
            }
            for (int columns = 1; columns <= DashboardConfig.MAX_COLUMNS; columns++) {
                int row = 0;
                for (int i = 0; i < config.widgets.size();) {
                    DashboardConfig.Widget first = config.widgets.get(i++);
                    int firstWidth = columns == 1 ? 1 : (columns + 1) / 2;
                    int height = first.defaultHeight();
                    first.layouts.put(columns, new DashboardLayout.Cell(first.id, 0, row, firstWidth, height));
                    if (columns > 1 && i < config.widgets.size()) {
                        DashboardConfig.Widget second = config.widgets.get(i++);
                        second.layouts.put(columns, new DashboardLayout.Cell(second.id, firstWidth, row, columns-firstWidth, second.defaultHeight()));
                        height = Math.max(height, second.defaultHeight());
                    }
                    row += height;
                }
            }
            config.save(); invoke(dashboard(), "render");
        }); settleMotion();
    }
    /** Tiny cards keep one complete reading; ordinary heights show the full list without scrolling. */
    private void verifyCompactReadingHeights() throws Exception {
        main(() -> settings().edit().putInt("dashboard_columns", 5).commit());
        seedEveryWidget(); stopUiPulse();
        main(() -> {
            require(((Number)field(grid(), "columns")).intValue() == 5, "Compact fixture lost five columns");
            float density = activity.getResources().getDisplayMetrics().density;
            float available = grid().getWidth() / density;
            float scale = activity.ui().textScale * activity.getResources().getConfiguration().fontScale;
            float rowHeight = ((Number)invoke(grid(), "rowHeight")).floatValue();
            @SuppressWarnings("unchecked") Map<String, TextView> tripValues =
                    (Map<String, TextView>)field(tile("trip"), "amounts");
            for (String key : new String[]{"speed_kmh", "trip_km", "trip_minutes", "maximum_speed_kmh"})
                require(tripValues.get(key).length() > 1 && !"—".contentEquals(tripValues.get(key).getText()),
                        "Compact fixture is missing a numeric trip reading: " + key);
            require(((View)field(tile("trip"), "footer")).getVisibility() == View.VISIBLE,
                    "Compact fixture must reserve stale-footer space");
            for (String type : new String[]{"trip", "readings", "charging"}) {
                View card = (View)tile(type);
                DashboardConfig.Widget widget = (DashboardConfig.Widget)field(card, "widget");
                int originalWidth = card.getWidth(), originalHeight = card.getHeight();
                String saved = cellText(widget.layouts.get(5));
                int span = widget.minimumWidth(5, available, 10, scale);
                int width = Math.round(density * (span * (available + 10) / 5 - 10));
                int minimumHeight = Math.round(widget.minimumHeight(width / density) * rowHeight) - activity.ui().dp(10);
                try {
                    measureCard(card, width, minimumHeight);
                    ScrollView viewport = (ScrollView)field(card, "viewport");
                    ViewGroup body = (ViewGroup)field(card, "body");
                    ViewGroup group = (ViewGroup)body.getChildAt(0);
                    ViewGroup row = (ViewGroup)group.getChildAt(0);
                    View first = row.getChildAt(0);
                    for (TextView text : texts(first)) assertText(text, viewport, "Complete first " + type + " reading");
                    for (TextView text : texts(body)) {
                        if (text.getClass().getSimpleName().equals("FittedValue"))
                            require(text.getTextSize() + 1 >= 12 * activity.ui().textScale
                                            * activity.getResources().getDisplayMetrics().scaledDensity,
                                    type + " reduced a value below its existing readable floor");
                    }
                    saveCardBitmap(card, "compact-" + type + "-minimum");
                    overlayGeometry.add(type + " minimum card=" + width + "x" + minimumHeight
                            + " first=" + relativeBounds(first, viewport) + " viewport=" + viewport.getHeight());
                    if (!type.equals("charging")) {
                        require(viewport.canScrollVertically(1), type + " lost scrolling for the remaining short-card readings");
                        viewport.scrollTo(0, body.getHeight());
                        measureCard(card, width, Math.round(widget.defaultHeight() * rowHeight) - activity.ui().dp(10));
                        require(!viewport.canScrollVertically(1) && !viewport.canScrollVertically(-1),
                                type + " ordinary-height card still needs internal scrolling");
                        for (TextView text : texts(body)) assertText(text, viewport, "Complete ordinary-height " + type);
                        saveCardBitmap(card, "compact-" + type + "-ordinary");
                    }
                    // Giving the card its room back must restore the original typography.
                    measureCard(card, activity.ui().dp(600), activity.ui().dp(600));
                    TextView restoredValue = (TextView)((ViewGroup)first).getChildAt(type.equals("trip") ? 0 : 1);
                    float normal = Math.round((type.equals("trip") ? 20 : 26) * activity.ui().textScale)
                            * activity.getResources().getDisplayMetrics().scaledDensity;
                    require(Math.abs(restoredValue.getTextSize() - normal) <= 1,
                            type + " did not restore its full-size value after expanding");
                    require(cellText(widget.layouts.get(5)).equals(saved), type + " measurement changed its saved grid rectangle");
                } finally { measureCard(card, originalWidth, originalHeight); }
            }
        });
        check("Minimum-height trip, reading and charging cards show their first complete reading; ordinary trip/reading cards fit all values without shrinking below the readable floor or changing saved rectangles");
        verifyLiveReadingHeights();
        verifyChangingCaptions();
    }

    /** Live cards have no stale footer: those extra pixels can expose rounding and font fallback errors. */
    private void verifyLiveReadingHeights() {
        Map<String, TelemetryHistory.Reading> live = new LinkedHashMap<>();
        main(() -> {
            for (TelemetryHistory.Reading reading : activity.service().displayReadings().values())
                live.put(reading.key, new TelemetryHistory.Reading(reading.key, reading.label, reading.group,
                        reading.value, reading.unit, reading.number, System.currentTimeMillis(), true));
            float density = activity.getResources().getDisplayMetrics().density;
            float available = grid().getWidth() / density;
            float scale = activity.ui().textScale * activity.getResources().getConfiguration().fontScale;
            float rowHeight = ((Number)invoke(grid(), "rowHeight")).floatValue();
            for (String type : new String[]{"readings", "trip"}) {
                View card = (View)tile(type);
                DashboardConfig.Widget widget = (DashboardConfig.Widget)field(card, "widget");
                ScrollView viewport = (ScrollView)field(card, "viewport");
                ViewGroup body = (ViewGroup)field(card, "body");
                int originalWidth = card.getWidth(), originalHeight = card.getHeight();
                String saved = cellText(widget.layouts.get(5));
                invoke(card, "refresh", new Class<?>[]{Map.class}, live);
                require(((View)field(card, "footer")).getVisibility() == View.GONE,
                        "Live " + type + " fixture must not reserve footer space");
                try {
                    // Actual narrow phone, five-column and half-width fold spans.
                    for (int columns : new int[]{2, 4, 5}) {
                        int span = Math.max(columns == 4 ? 2 : 1, widget.minimumWidth(columns, available, 10, scale));
                        int width = Math.round(density * (span * (available + 10) / columns - 10));
                        measureCard(card, width, Math.round(widget.defaultHeight() * rowHeight) - activity.ui().dp(10));
                        require(!viewport.canScrollVertically(1) && !viewport.canScrollVertically(-1),
                                "Live " + type + " ordinary-height card still scrolls at " + columns
                                + " columns: body=" + body.getHeight() + " viewport=" + viewport.getHeight()
                                + " cardWidth=" + width);
                        for (TextView text : texts(body)) assertText(text, viewport, "Complete live " + type);
                        overlayGeometry.add("live " + type + " columns=" + columns + " card=" + width
                                + "x" + card.getHeight() + " body=" + body.getHeight() + " viewport=" + viewport.getHeight());
                        saveCardBitmap(card, "compact-live-" + type + "-" + columns + "-columns");
                    }
                    require(cellText(widget.layouts.get(5)).equals(saved), "Live measurement changed its saved rectangle");
                } finally {
                    invoke(card, "refresh", new Class<?>[]{Map.class}, activity.service().displayReadings());
                    measureCard(card, originalWidth, originalHeight);
                }
            }
        });
        // Leave the live values in the real grid after sizing. Measurement must
        // settle rather than keep scheduling another layout via candidate fonts.
        main(() -> {
            for (String type : new String[]{"readings", "trip"})
                invoke(tile(type), "refresh", new Class<?>[]{Map.class}, live);
            grid().requestLayout();
        });
        settleMotion();
        final int[] layouts = {0};
        android.view.ViewTreeObserver.OnGlobalLayoutListener listener = () -> layouts[0]++;
        main(() -> grid().getViewTreeObserver().addOnGlobalLayoutListener(listener));
        SystemClock.sleep(750);
        waitForIdleSync();
        main(() -> {
            grid().getViewTreeObserver().removeOnGlobalLayoutListener(listener);
            require(layouts[0] <= 2, "Stable live values kept scheduling layouts: " + layouts[0]);
            overlayGeometry.add("stable live layout passes in 750ms=" + layouts[0]);
            for (String type : new String[]{"readings", "trip"})
                invoke(tile(type), "refresh", new Class<?>[]{Map.class}, activity.service().displayReadings());
        });
        check("Live numeric Trip and Readings cards fit their real TextView layouts without a stale footer or internal scrolling across narrow and unfolded spans; stable live measurement settles without a layout loop");
    }
    /** Inspect the actual smallest five-column cells, rather than comfortable default spans. */
    private void verifyMinimumWidthContents() throws Exception {
        main(() -> settings().edit().putInt("dashboard_columns", 5).commit());
        seedEveryWidget();
        main(() -> {
            require(((Number)field(grid(), "columns")).intValue() == 5,
                    "Narrow fixture must retain its requested five columns");
            float density = activity.getResources().getDisplayMetrics().density;
            float available = grid().getWidth() / density;
            float scale = activity.ui().textScale * activity.getResources().getConfiguration().fontScale;
            int row = 0;
            for (DashboardConfig.Widget widget : dashboardConfig().widgets) {
                if (widget.type.equals("scooter")) {
                    widget.keys.clear();
                    widget.keys.addAll(Arrays.asList("odometer_km", "remaining_range_km", "driving_mode"));
                }
                if (widget.type.equals("note")) widget.note = "Check tire pressure before riding.";
                int span = widget.minimumWidth(5, available, 10, scale);
                float width = span * (available + 10) / 5 - 10;
                int height = widget.minimumHeight(width);
                widget.layouts.put(5, new DashboardLayout.Cell(widget.id, 0, row, span, height));
                row += height;
            }
            invoke(dashboard(), "render");
        }); settleMotion(); stopUiPulse();
        final ArrayList<String> problems = new ArrayList<>();
        main(() -> {
            require(tiles().size() == DashboardConfig.TYPES.length, "Narrow fixture lost a widget type");
            for (Object item : tiles()) {
                View card = (View)item;
                DashboardConfig.Widget widget = (DashboardConfig.Widget)field(item, "widget");
                DashboardLayout.Cell cell = (DashboardLayout.Cell)invoke(grid(), "currentCell",
                        new Class<?>[]{DashboardConfig.Widget.class}, widget);
                int minimum = ((Number)invoke(grid(), "minimumWidth",
                        new Class<?>[]{DashboardConfig.Widget.class}, widget)).intValue();
                require(cell.w == minimum, widget.type + " fixture is wider than its minimum span");
                require(cell.h == widget.minimumHeight(card.getWidth() / activity.getResources().getDisplayMetrics().density),
                        widget.type + " fixture is taller than its minimum height");
                for (TextView text : texts(card)) collectNarrowTextProblems(text, widget.type, problems);
                saveCardBitmap(card, "minimum-" + widget.type);
                overlayGeometry.add("minimum " + widget.type + " cell=" + cellText(cell)
                        + " card=" + card.getWidth() + "x" + card.getHeight());
            }
            List<?> scooterStats = (List<?>)field(tile("scooter"), "scooterStats");
            String[] spokenNames = {"Total odometer", "Scooter's estimated remaining range", "Drive mode"};
            for (int index = 0; index < scooterStats.size(); index++) {
                Object value = scooterStats.get(index);
                LinearLayout item = (LinearLayout)value;
                TextView label = (TextView)item.getChildAt(0);
                require(spokenNames[index].contentEquals(label.getContentDescription())
                                && label.getContentDescription().equals(item.getTooltipText()),
                        "Compact scooter label lost its full spoken/tooltip name");
                require(label.getLayout() != null && label.getLayout().getLineCount() <= 2,
                        "Compact scooter label consumes more than two lines: " + label.getText());
            }
        });
        for (String problem : problems) overlayGeometry.add("text failure: " + problem);
        verifyChangingCaptions();
        verifyEditOverlays();
        main(() -> {
            for (Object item : tiles()) {
                String type = ((DashboardConfig.Widget)field(item, "widget")).type;
                for (TextView text : texts((View)item)) collectNarrowTextProblems(text, type + " after editing", problems);
            }
        });
        require(problems.isEmpty(), "Minimum-width content: " + String.join("; ", problems));
        verifyCompactWidgetActions();
        check("All " + DashboardConfig.TYPES.length + " widget types keep whole words within their smallest five-column spans, including after editing; compact scooter captions retain full spoken names");
    }
    /** A refresh changes the meaning, so a later measure must not restore old wording. */
    private void verifyChangingCaptions() {
        main(() -> {
            View scooter = (View)tile("scooter");
            int scooterWidth = scooter.getWidth(), scooterHeight = scooter.getHeight();
            TextView odometer = (TextView)((LinearLayout)((List<?>)field(scooter, "scooterStats")).get(0)).getChildAt(0);
            try {
                int fullWidth = (int)Math.ceil(odometer.getPaint().measureText("Odometer")) + 2;
                int shortWidth = (int)Math.ceil(odometer.getPaint().measureText("Odo.")) + 2;
                for (int width : new int[]{fullWidth, shortWidth, activity.ui().dp(16), fullWidth}) {
                    odometer.forceLayout();
                    odometer.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                    String expected = width == fullWidth ? "Odometer" : width == shortWidth ? "Odo." : "";
                    require(expected.contentEquals(odometer.getText()), "Caption did not use/restore whole-word text tier");
                    require((odometer.getCompoundDrawablesRelative()[0] != null) == expected.isEmpty(),
                            "Caption icon fallback is missing or remained after widening");
                    require("Total odometer".contentEquals(odometer.getContentDescription())
                                    && "Total odometer".contentEquals(odometer.getTooltipText()),
                            "Caption fallback lost the full spoken name");
                }
            } finally { measureCard(scooter, scooterWidth, scooterHeight); }
            Map<String, TelemetryHistory.Reading> values = new LinkedHashMap<>(activity.service().displayReadings());
            for (String type : new String[]{"live", "history"}) {
                Object item = tile(type); View card = (View)item;
                DashboardConfig.Widget widget = (DashboardConfig.Widget)field(item, "widget");
                int width = card.getWidth(), height = card.getHeight(); long originalWindow = widget.windowMs;
                try {
                    for (long window : new long[]{60000L, 120000L, 3600000L, 86400000L, 172800000L}) {
                        widget.windowMs = window;
                        invoke(item, "drawChart", new Class<?>[]{Map.class, String.class}, values, widget.metric);
                        measureCard(card, activity.ui().dp(600), height);
                        TextView caption = (TextView)field(item, "subtitle");
                        String expected = window == 60000L ? "1 minute" : window == 120000L ? "2 minutes"
                                : window == 3600000L ? "1 hour" : window == 86400000L ? "1 day" : "2 days";
                        require(caption.getText().toString().contains(expected)
                                        && caption.getContentDescription().toString().contains(expected),
                                type + " measure restored the old chart-window caption");
                        measureCard(card, width, height);
                    }
                } finally {
                    widget.windowMs = originalWindow;
                    invoke(item, "drawChart", new Class<?>[]{Map.class, String.class}, values, widget.metric);
                    measureCard(card, width, height);
                }
            }
            Object item = tile("charging"); View card = (View)item;
            int width = card.getWidth(), height = card.getHeight();
            try {
                long now = System.currentTimeMillis();
                reading(values, "charging_state", "Charging", "", 1, now);
                reading(values, "voltage_v", "52 V", "V", 52, now);
                reading(values, "current_a", "-3 A", "A", -3, now);
                reading(values, "battery_percent", "55 %", "%", 55, now);
                reading(values, "rated_capacity_ah", "12.75 Ah", "Ah", 12.75, now);
                for (int target : new int[]{80, 100}) {
                    reading(values, "charge_limit_percent", target + " %", "%", target, now);
                    invoke(item, "refreshCharging", new Class<?>[]{Map.class}, values);
                    measureCard(card, activity.ui().dp(600), height);
                    TextView caption = (TextView)field(item, "chargingEtaLabel");
                    String expected = target == 80 ? "To 80% limit" : "Time to full";
                    require(expected.contentEquals(caption.getText()) && expected.contentEquals(caption.getContentDescription()),
                            "Measure restored an obsolete charging-target label");
                    measureCard(card, width, height);
                }
            } finally {
                invoke(item, "refreshCharging", new Class<?>[]{Map.class}, activity.service().displayReadings());
                measureCard(card, width, height);
            }
        });
        check("Changing chart windows and charge targets survive narrow/wide measurement with matching spoken labels");
    }
    private void verifyCompactWidgetActions() throws Exception {
        main(() -> invoke(dashboard(), "toggleEditing")); settleMotion();
        final int count = dashboardConfig().widgets.size();
        for (String type : new String[]{"metric", "readings", "trip", "note"}) {
            main(() -> {
                View card = (View)tile(type);
                if (editorControls(card).size() != 3) return;
                android.widget.PopupMenu menu = openCompactActions(card);
                require(menu.getMenu().size() == 3 && menu.getMenu().findItem(1).isEnabled()
                                && menu.getMenu().findItem(2).isEnabled() && menu.getMenu().findItem(3).isEnabled(),
                        type + " compact editor lost Edit/Duplicate/Remove");
                menu.dismiss();
            });
        }
        final String id = ((DashboardConfig.Widget)field(tile("metric"), "widget")).id;
        if (editorControls((View)tileById(id)).size() == 3) {
            main(() -> openCompactActions((View)tileById(id)).getMenu().performIdentifierAction(1, 0)); settle();
            main(() -> {
                require(described(dialogRoot(), "Save widget options") != null, "Compact Edit failed to open options");
                invoke(dashboard(), "dismiss");
            }); settle();
            main(() -> openCompactActions((View)tileById(id)).getMenu().performIdentifierAction(2, 0)); settleMotion();
            require(dashboardConfig().widgets.size() == count + 1, "Compact Duplicate did not add one card");
            historyAction("Undo");
            main(() -> openCompactActions((View)tileById(id)).getMenu().performIdentifierAction(3, 0)); settleMotion();
            require(dashboardConfig().widgets.size() == count - 1 && !containsWidget(id), "Compact Remove deleted the wrong card");
            historyAction("Undo");
            require(dashboardConfig().widgets.size() == count && containsWidget(id), "Undo did not restore compact card");
        }
        main(() -> invoke(dashboard(), "toggleEditing")); settleMotion();
        check("Short narrow editors keep distinct targets and functional Edit/Duplicate/Remove in their named action menu");
    }
    private android.widget.PopupMenu openCompactActions(View card) {
        described(card, "Options for ").performClick();
        android.widget.PopupMenu menu = (android.widget.PopupMenu)field(card.findViewWithTag("widget-editor-bar"), "actionsMenu");
        require(menu != null, "Compact Options did not open an action menu");
        return menu;
    }
    private void collectNarrowTextProblems(TextView text, String type, List<String> problems) {
        if (!text.isShown() || text.length() == 0) return;
        android.text.Layout layout = text.getLayout();
        if (layout == null) { problems.add(type + " has no text layout: " + text.getText()); return; }
        String value = text.getText().toString();
        int width = text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight();
        for (int line = 0; line < layout.getLineCount(); line++) {
            // Deliberate title/status ellipses have their own full accessibility text.
            if (layout.getEllipsisCount(line) != 0) continue;
            if (layout.getLineMax(line) > width + 2)
                problems.add(type + " clips text horizontally: " + value);
            int end = layout.getLineEnd(line);
            if (line + 1 < layout.getLineCount() && end > 0 && end < value.length()
                    && Character.isLetter(value.charAt(end - 1)) && Character.isLetter(value.charAt(end)))
                problems.add(type + " breaks a word: " + value.substring(0, end) + " / " + value.substring(end));
        }
    }
    private void verifyEditOverlays() throws Exception {
        final Map<String, Rect> normalFrames = new LinkedHashMap<>();
        main(() -> {
            assertNoEditorControls();
            for (Object item : tiles()) { View card = (View)item;
                normalFrames.put(((DashboardConfig.Widget)field(item, "widget")).id, new Rect(0, 0, card.getWidth(), card.getHeight())); }
            invoke(dashboard(), "toggleEditing");
        });
        settleMotion(); screenshot("edit-overlays");
        main(() -> {
            assertEditorControls();
            for (Object item : tiles()) { View card = (View)item;
                Rect before = normalFrames.get(((DashboardConfig.Widget)field(item, "widget")).id);
                require(before.width() == card.getWidth() && before.height() == card.getHeight(), "Editor changed the card's dashboard dimensions"); }
            verifyRecorderEditorTargets();
            invoke(dashboard(), "toggleEditing");
        });
        settleMotion();
        main(this::assertNoEditorControls);
    }
    private void verifyRecorderEditorTargets() {
        View card = (View)tile("ride"); int width = card.getWidth(), height = card.getHeight();
        try {
            for (int dp : new int[]{192, 216, 300}) {
                measureCard(card, activity.ui().dp(dp), activity.ui().dp(54));
                drawEditorFrame(card);
                assertEditorControls(); // One-row controls must leave the resize target its own lane.
            }
            measureCard(card, activity.ui().dp(113), activity.ui().dp(170));
            // Large text can select compact recorder content even in a physically tall card.
            // Exercise that condition directly, without changing global settings or rebuilding.
            setField(card, "compactRecorder", true);
            View bar = card.findViewWithTag("widget-editor-bar"); bar.forceLayout();
            bar.measure(View.MeasureSpec.makeMeasureSpec(card.getWidth(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(card.getHeight(), View.MeasureSpec.AT_MOST));
            bar.layout(0, 0, card.getWidth(), bar.getMeasuredHeight());
            drawEditorFrame(card); assertEditorControls();
        } finally { measureCard(card, width, height); }
    }
    private void drawEditorFrame(View card) {
        // View updates a resized RippleDrawable's bounds on draw, after measurement/layout.
        // Inspect the resulting painted frame, not the previous size's drawable bounds.
        Bitmap bitmap = Bitmap.createBitmap(card.getWidth(), card.getHeight(), Bitmap.Config.ARGB_8888);
        try { card.draw(new android.graphics.Canvas(bitmap)); } finally { bitmap.recycle(); }
    }
    private void assertNoEditorControls() {
        for (Object item : tiles()) require(editorControls((View)item).isEmpty(), "Widget editor controls escaped dashboard edit mode");
        for (String prefix : new String[] {"Undo", "Redo"}) {
            View action = described(root(), prefix);
            require(action == null || !action.isShown(), prefix + " escaped dashboard edit mode");
        }
    }
    private void assertEditorControls() {
        for (Object item : tiles()) {
            ViewGroup card = (ViewGroup)item;
            DashboardConfig.Widget widget = (DashboardConfig.Widget)field(item, "widget");
            List<View> controls = editorControls(card);
            boolean compactActions = controls.size() == 3;
            require(compactActions || controls.size() == 5, "Expected accessible editor actions on " + widget.title + ", got " + controls.size());
            ArrayList<Rect> occupied = new ArrayList<>();
            View grip = described(card, "Move "), options = described(card, "Options for "),
                    duplicate = described(card, "Duplicate "), remove = described(card, "Remove ");
            Rect gripBounds = relativeBounds(grip, card), optionBounds = relativeBounds(options, card),
                    duplicateBounds = relativeBounds(duplicate, card), removeBounds = relativeBounds(remove, card);
            // Five columns now remain available on narrow covers. Test the actual square
            // targets rather than assuming four fit across every card; the 36dp floor stays.
            int inlineTarget = grip.getWidth();
            require(inlineTarget >= activity.ui().dp(36) - 1, "Inline controls fell below the compact target floor");
            require(gripBounds.left == 0 && gripBounds.top == 0, "Move handle left its top-left anchor");
            boolean wrapped = compactActions || duplicateBounds.top > 0;
            if (wrapped) {
                require(inlineTarget == activity.ui().dp(48), "Wrapped editor did not retain full 48dp targets");
                if (compactActions) {
                    require((optionBounds.left == 0 && optionBounds.top == gripBounds.bottom)
                                    || (optionBounds.left == gripBounds.right && optionBounds.top == 0),
                            "Compact Options must sit directly beside or below Move");
                    require(duplicate.getVisibility() == View.GONE && remove.getVisibility() == View.GONE,
                            "Tiny rail kept hidden actions as overlapping targets");
                } else {
                    require(optionBounds.left == gripBounds.right && optionBounds.top == 0
                                    && duplicateBounds.left == 0 && duplicateBounds.top == gripBounds.bottom
                                    && removeBounds.top == duplicateBounds.top && removeBounds.right == card.getWidth(),
                            "Compact editor must keep Move/Edit above Duplicate/Remove, with Remove at the right");
                }
            } else {
                int resizeLane = ((Boolean)field(item, "compactRecorder")) && card.getHeight() < activity.ui().dp(96)
                        ? activity.ui().dp(48) : 0;
                require(inlineTarget == Math.min(activity.ui().dp(52), (card.getWidth() - resizeLane) / 4),
                        "Inline controls lost their equal available-width targets");
                require(optionBounds.left == gripBounds.right && duplicateBounds.left == optionBounds.right
                                && removeBounds.right == card.getWidth() - resizeLane,
                        "Move/Edit/Duplicate must sit together at the left, with Remove at the right");
                require(optionBounds.top == 0 && duplicateBounds.top == 0 && removeBounds.top == 0,
                        "Wide editor wrapped despite room for a full row");
            }
            if (widget.type.equals("scooter")) for (TextView text : texts(card)) if (text.getText().toString().equals("SPORT"))
                require(text.getLayout() != null && text.getLayout().getLineCount() == 1, "Scooter drive mode wrapped onto a second line");
            for (View control : controls) {
                String label = control.getContentDescription().toString();
                boolean resize = label.startsWith("Resize ");
                int expectedTarget = resize ? activity.ui().dp(48) : inlineTarget;
                require(control.getWidth() == expectedTarget && control.getHeight() == expectedTarget
                        && control.isClickable() && control.isFocusable(), label + " lost its equal square touch target");
                Rect target = relativeBounds(control, card);
                require(target.left >= 0 && target.top >= 0 && target.right <= card.getWidth() && target.bottom <= card.getHeight(), label + " target extends outside its card: " + target + " / " + card.getWidth() + "x" + card.getHeight());
                for (Rect other : occupied) require(!Rect.intersects(target, other), label + " touch target overlaps another editor action");
                occupied.add(target);
                require(texts(control).isEmpty(), label + " still contains visible button text");
                if (label.startsWith("Duplicate ")) require(control.isEnabled() != (widget.type.equals("lock") || widget.type.equals("lockbar")), "Duplicate lock/lock bar restriction changed");
                require(control instanceof ViewGroup, label + " has no glyph container");
                ViewGroup group = (ViewGroup)control;
                require(group.getChildCount() == 1, label + " has unexpected glyph children");
                View glyph = group.getChildAt(0); Rect glyphBox = relativeBounds(glyph, control);
                require(glyphBox.left >= 0 && glyphBox.top >= 0 && glyphBox.right <= control.getWidth() && glyphBox.bottom <= control.getHeight(), label + " glyph is clipped");
                if (resize) {
                    require(glyphBox.equals(new Rect(0, 0, control.getWidth(), control.getHeight())), "Resize mark lost its full-size canvas"); continue;
                }
                String expectedIcon = label.startsWith("Move ") ? "grip" : label.startsWith("Options for ") ? "gear" : label.startsWith("Duplicate ") ? "copy" : "trash";
                require(expectedIcon.contentEquals(glyph.getContentDescription()), label + " displays the wrong icon");
                require(glyphBox.width() == activity.ui().dp(17) && glyphBox.height() == activity.ui().dp(17), label + " icon is not the shared size");
                Drawable paintedSquare = findEditorSquare(control.getBackground());
                require(paintedSquare != null, label + " has no visible centered square");
                Rect square = new Rect(paintedSquare.getBounds());
                require(square.width() == activity.ui().dp(30) && square.height() == activity.ui().dp(30), label + " visual square changed size");
                float dx = glyphBox.exactCenterX() - square.exactCenterX(), dy = glyphBox.exactCenterY() - square.exactCenterY();
                overlayGeometry.add(label + ": target=" + target + ", glyph_center_delta=" + dx + "," + dy);
                require(Math.abs(dx) <= 1 && Math.abs(dy) <= 1, label + " glyph is off-center by " + dx + "," + dy);
                require(Math.abs(square.exactCenterX() - control.getWidth() / 2f) <= 1
                                && Math.abs(square.exactCenterY() - control.getHeight() / 2f) <= 1,
                        label + " visual square is not centered in its touch target");
                if (label.startsWith("Remove ")) {
                    // The trash glyph sits on the solid text-coloured square: a deep red on the light
                    // square (dark theme), a lightened red on the dark square (light theme).
                    local.navee.offlinelock.Ui ui = activity.ui();
                    int expected = ui.dark ? ui.accentDeep : ((Number)invoke(dashboard(), "blend",
                            new Class<?>[] {int.class, int.class, float.class}, ui.danger, android.graphics.Color.WHITE, .4f)).intValue();
                    require(((Number)field(glyph, "color")).intValue() == expected, "Remove icon lost its destructive color");
                }
            }
        }
    }
    private List<View> editorControls(View root) {
        List<View> found = new ArrayList<>();
        CharSequence description = root.getContentDescription();
        String label = description == null ? "" : description.toString();
        if (root.getVisibility() != View.VISIBLE) return found;
        if (label.startsWith("Move ") || label.startsWith("Options for ") || label.startsWith("Duplicate ") || label.startsWith("Remove ") || label.startsWith("Resize ")) found.add(root);
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)root).getChildCount(); i++) found.addAll(editorControls(((ViewGroup)root).getChildAt(i)));
        return found;
    }
    private View described(View root, String prefix) {
        CharSequence label = root.getContentDescription();
        if (label != null && label.toString().startsWith(prefix)) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)root).getChildCount(); i++) {
            View found = described(((ViewGroup)root).getChildAt(i), prefix); if (found != null) return found;
        }
        return null;
    }
    private android.app.AlertDialog editorDialog() { return (android.app.AlertDialog)field(dashboard(), "dialog"); }
    private View dialogRoot() { android.app.AlertDialog dialog = editorDialog(); require(dialog != null && dialog.isShowing(), "Expected editor sheet"); return dialog.getWindow().getDecorView(); }
    private DashboardConfig dashboardConfig() { return (DashboardConfig)field(dashboard(), "config"); }
    private Object tileById(String id) { for (Object item : tiles()) if (((DashboardConfig.Widget)field(item, "widget")).id.equals(id)) return item; throw new IllegalStateException("Missing widget " + id); }
    /**
     * Lets short motion finish. The edit transition lasts up to max(210, 240 + 20·(n−1)) ms for
     * n staggered controls, so wait for it to clear rather than trusting a fixed sleep.
     */
    private void settleMotion() {
        waitForIdleSync(); SystemClock.sleep(260);
        long end = SystemClock.elapsedRealtime() + 4000;
        while (true) {
            AtomicReference<Boolean> moving = new AtomicReference<>(false);
            main(() -> { Object screen = field(activity, "screen");
                moving.set(screen instanceof DashboardScreen && field(screen, "editTransition") != null); });
            if (!moving.get()) break;
            require(SystemClock.elapsedRealtime() < end, "Editor transition did not finish");
            SystemClock.sleep(16);
        }
        waitForIdleSync();
    }
    private void verifyEditorActions() throws Exception {
        main(() -> invoke(dashboard(), "toggleEditing")); settleMotion();
        main(() -> assertHistoryControls(false, false));
        final DashboardConfig.Widget source = (DashboardConfig.Widget)field(tile("live"), "widget");
        final int count = dashboardConfig().widgets.size();
        main(() -> {
            source.metric = "power_w"; source.color = 2; source.windowMs = 120000; source.minimum = -80; source.maximum = 1800;
            source.note = "Duplicate fixture"; source.advertisedWh = 650; source.keys.clear(); source.keys.addAll(Arrays.asList("power_w", "battery_percent"));
            described((View)tileById(source.id), "Options for ").performClick();
        }); settleMotion();
        main(() -> {
            View sheet = dialogRoot();
            require(described(sheet, "Duplicate ") == null && described(sheet, "Remove ") == null, "Old destructive actions remain inside the options sheet");
            for (TextView text : texts(sheet)) require(!text.getText().toString().equals("Size"), "Redundant Size section remains in widget options");
            View save = described(sheet, "Save widget options");
            require(save instanceof TextView && ((TextView)save).getText().toString().equals("✓ Save"), "Options sheet lost its Save action");
            require(save.getHeight() >= activity.ui().dp(48), "Save target is too short");
            ((android.widget.EditText)described(sheet, "Widget title")).setText("Power test"); save.performClick();
        }); settleMotion();
        main(() -> {
            require(source.title.equals("Power test") && editorDialog() == null, "Save did not persist title and close the sheet");
            assertHistoryControls(false, false);
            View sourceCard = (View)tileById(source.id);
            described(sourceCard, "Duplicate ").performClick();
            DashboardConfig.Widget justCopied = dashboardConfig().widgets.get(dashboardConfig().widgets.size() - 1);
            Map<String, android.graphics.RectF> previous = (Map<String, android.graphics.RectF>)field(grid(), "previousFrames");
            if (local.navee.offlinelock.Ui.motionEnabled()) {
                android.graphics.RectF frame = previous.get(justCopied.id);
                require(frame != null && frame.left == sourceCard.getLeft() && frame.top == sourceCard.getTop()
                        && frame.width() == sourceCard.getWidth(), "Duplicate does not split from its source frame");
            }
        }); settleMotion();
        final AtomicReference<DashboardConfig.Widget> duplicate = new AtomicReference<>();
        main(() -> {
            require(dashboardConfig().widgets.size() == count + 1, "Duplicate did not add exactly one widget");
            for (DashboardConfig.Widget widget : dashboardConfig().widgets) if (widget.type.equals(source.type) && !widget.id.equals(source.id)) duplicate.set(widget);
            DashboardConfig.Widget copy = duplicate.get(); require(copy != null, "Duplicate missing");
            require(copy.title.equals(source.title) && copy.metric.equals(source.metric) && copy.note.equals(source.note)
                    && copy.keys.equals(source.keys) && copy.keys != source.keys && copy.color == source.color && copy.windowMs == source.windowMs
                    && copy.minimum == source.minimum && copy.maximum == source.maximum && copy.advertisedWh == source.advertisedWh, "Duplicate dropped widget options");
            for (Integer columns : source.layouts.keySet()) require(copy.layouts.containsKey(columns) && copy.layouts.get(columns) != source.layouts.get(columns), "Duplicate shares or lost a saved rectangle");
            assertHistoryControls(true, false);
        });
        historyAction("Undo");
        main(() -> { require(dashboardConfig().widgets.size() == count && !containsWidget(duplicate.get().id), "Undo did not remove the duplicated widget"); assertHistoryControls(false, true); });
        historyAction("Redo");
        main(() -> {
            require(dashboardConfig().widgets.size() == count + 1 && containsWidget(duplicate.get().id), "Redo did not restore the duplicated widget identity");
            described((View)tileById(duplicate.get().id), "Remove ").performClick();
        }); settleMotion();
        verifyDeletionHistory(duplicate.get(), count);
        main(() -> {
            require(dashboardConfig().widgets.size() == count && dashboardConfig().widgets.contains(source)
                    && !dashboardConfig().widgets.contains(duplicate.get()), "Remove deleted the wrong widget");
            invoke(dashboard(), "layoutOptions");
        }); settleMotion();
        final boolean sixAvailable = ((Number)invoke(grid(), "maximumColumns")).intValue() >= 6;
        main(() -> {
            View six = described(dialogRoot(), "6 columns"); require(six != null && six.isEnabled() == sixAvailable, "Six-column availability does not match the usable width");
            if (sixAvailable) { six.performClick(); require(settings().getInt("dashboard_columns", 0) == 6, "Six-column selector did not save the choice"); }
            invoke(dashboard(), "dismiss");
        }); settleMotion();
        main(() -> {
            int columns = ((Number)field(grid(), "columns")).intValue();
            require(columns == (sixAvailable ? 6 : ((Number)invoke(grid(), "maximumColumns")).intValue()), "Editor selected an invalid column count");
            if (sixAvailable) {
                DashboardConfig.Widget scooter = (DashboardConfig.Widget)field(tile("scooter"), "widget");
                DashboardConfig.Widget lock = (DashboardConfig.Widget)field(tile("lock"), "widget");
                scooter.layouts.put(6, new DashboardLayout.Cell(scooter.id, 0, 0, 4, 4));
                lock.layouts.put(6, new DashboardLayout.Cell(lock.id, 4, 0, 2, 4));
                invoke(grid(), "settle", new Class<?>[] {String.class}, lock.id);
            }
        }); settleMotion();
        main(() -> {
            assertEditorControls();
            int columns = ((Number)field(grid(), "columns")).intValue();
            ArrayList<DashboardLayout.Cell> cells = new ArrayList<>();
            for (DashboardConfig.Widget widget : dashboardConfig().widgets) {
                DashboardLayout.Cell cell = (DashboardLayout.Cell)invoke(grid(), "currentCell", new Class<?>[] {DashboardConfig.Widget.class}, widget);
                int minimum = ((Number)invoke(grid(), "minimumWidth", new Class<?>[] {DashboardConfig.Widget.class}, widget)).intValue();
                require(cell.w >= minimum && cell.x >= 0 && cell.x + cell.w <= columns, "Widget went below its type's minimum width");
                for (DashboardLayout.Cell other : cells) require(!(cell.x < other.x + other.w && cell.x + cell.w > other.x && cell.y < other.y + other.h && cell.y + cell.h > other.y), "Six-column projected cards overlap");
                cells.add(cell);
            }
            if (sixAvailable) {
                DashboardLayout.Cell scooter = (DashboardLayout.Cell)invoke(grid(), "currentCell", new Class<?>[] {DashboardConfig.Widget.class}, field(tile("scooter"), "widget"));
                DashboardLayout.Cell lock = (DashboardLayout.Cell)invoke(grid(), "currentCell", new Class<?>[] {DashboardConfig.Widget.class}, field(tile("lock"), "widget"));
                require(scooter.w == 4 && lock.w == 2 && scooter.y == lock.y, "Four/two-column scooter and lock split is unavailable");
            }
        }); screenshot(sixAvailable ? "six-column-editor" : "compact-editor");
        verifyResizeCancellation(source);
        verifyCommittedGestureHistory(source, false);
        verifyCommittedGestureHistory(source, true);
        verifyRemovalEffectCancellation();
        main(() -> invoke(dashboard(), "toggleEditing")); settleMotion(); main(this::assertNoEditorControls);
    }
    private void verifyEditorMotion() throws Exception {
        await(() -> grid().hasWindowFocus(), "Motion fixture did not receive window focus");
        final boolean animate = android.animation.ValueAnimator.areAnimatorsEnabled();
        final Map<String, Rect> normalFrames = new LinkedHashMap<>();
        main(() -> {
            for (Object item : tiles()) { View card = (View)item;
                normalFrames.put(((DashboardConfig.Widget)field(item, "widget")).id, new Rect(0, 0, card.getWidth(), card.getHeight())); }
            invoke(dashboard(), "toggleEditing");
            require(dashboard().isEditing(), "Entry animation delayed edit state");
        }); settleMotion();
        main(() -> {
            assertHistoryControls(false, false); assertEditorControls(); assertEditorTransformsSettled();
            for (Object item : tiles()) { View card = (View)item; Rect before = normalFrames.get(((DashboardConfig.Widget)field(item, "widget")).id);
                require(before.width() == card.getWidth() && before.height() == card.getHeight(), "Entry motion changed a widget's final dimensions"); }
        });
        final boolean[] observedJiggle = new boolean[] {false};
        for (int sample = 0; sample < 3; sample++) {
            SystemClock.sleep(100); main(() -> {
                for (Object item : tiles()) {
                    float angle = ((Number)field(item, "jiggleAngle")).floatValue();
                    require(Math.abs(angle) <= .201f, "Editing jiggle exceeded its gentle angle limit");
                    require(((View)item).getRotation() == 0, "Jiggle changed widget hit geometry");
                    if (Math.abs(angle) > .0001f) observedJiggle[0] = true;
                    if (!animate) require(angle == 0, "Disabled animations still jiggled a widget");
                }
                require(animate || Boolean.FALSE.equals(field(grid(), "jigglePosted")), "Disabled animations retained a jiggle callback");
            });
        }
        main(() -> require(!animate || observedJiggle[0], "Editing never showed its gentle jiggle"));
        screenshot("editor-motion");
        final Object moving = tiles().get(0);
        main(() -> {
            View card = (View)moving; int[] location = new int[2]; card.getLocationOnScreen(location);
            invoke(grid(), "beginGesture", new Class<?>[] {card.getClass(), boolean.class, float.class, float.class}, card, false,
                    location[0] + card.getWidth() / 2f, location[1] + card.getHeight() / 2f);
        }); SystemClock.sleep(100);
        main(() -> {
            require(((Number)field(moving, "jiggleAngle")).floatValue() == 0, "Dragged widget kept jiggling");
            invoke(grid(), "finishGesture", new Class<?>[] {boolean.class}, false);
        });
        final AtomicReference<android.app.AlertDialog> focusDialog = new AtomicReference<>();
        main(() -> { focusDialog.set(new android.app.AlertDialog.Builder(activity).setTitle("UI validation").setMessage("Temporary focus fixture").create()); focusDialog.get().show(); });
        await(() -> !grid().hasWindowFocus(), "Focus fixture did not take window focus");
        SystemClock.sleep(120); main(this::assertJiggleStopped);
        main(() -> focusDialog.get().dismiss());
        await(() -> grid().hasWindowFocus(), "Dashboard did not regain window focus"); settleMotion();
        final AtomicReference<Object> exit = new AtomicReference<>();
        final ArrayList<Object> exitGhosts = new ArrayList<>();
        final long touchStarted = SystemClock.uptimeMillis();
        main(() -> {
            if (animate) require(Boolean.TRUE.equals(field(grid(), "jigglePosted")), "Focus return did not resume editor motion");
            invoke(dashboard(), "toggleEditing");
            require(!dashboard().isEditing(), "Exit animation delayed ordinary dashboard state");
            assertNoEditorControls();
            exit.set(field(dashboard(), "editTransition"));
            if (animate) {
                require(exit.get() != null, "Exit omitted its visual transition");
                exitGhosts.addAll((List<?>)field(exit.get(), "ghosts"));
                require(!exitGhosts.isEmpty(), "Exit did not snapshot visible controls");
                long pixels = 0;
                for (Object ghost : exitGhosts) { Bitmap bitmap = (Bitmap)field(ghost, "bitmap");
                    require(bitmap != null && !bitmap.isRecycled() && bitmap.getWidth() <= 1024 && bitmap.getHeight() <= 1024,
                            "Exit control snapshot is unavailable or oversized");
                    pixels += (long)bitmap.getWidth() * bitmap.getHeight(); }
                require(pixels <= 1000000, "Exit snapshots exceed their shared budget");
                android.view.MotionEvent down = android.view.MotionEvent.obtain(touchStarted, touchStarted, android.view.MotionEvent.ACTION_DOWN, 0, 0, 0);
                try { require(grid().dispatchTouchEvent(down) && Boolean.TRUE.equals(field(grid(), "suppressTransitionTouch")),
                        "Exit transition did not consume a touch on its decorative controls"); } finally { down.recycle(); }
            } else require(exit.get() == null, "Disabled motion still created an exit transition");
        }); settleMotion();
        main(() -> {
            assertJiggleStopped(); assertEditorTransformsSettled();
            if (exit.get() != null) {
                android.view.MotionEvent up = android.view.MotionEvent.obtain(touchStarted, SystemClock.uptimeMillis(), android.view.MotionEvent.ACTION_UP, 0, 0, 0);
                try { require(grid().dispatchTouchEvent(up) && Boolean.FALSE.equals(field(grid(), "suppressTransitionTouch")),
                        "Exit touch sequence leaked into the live dashboard"); } finally { up.recycle(); }
                require(Boolean.TRUE.equals(field(exit.get(), "finished")) && field(exit.get(), "observer") == null
                                && ((List<?>)field(exit.get(), "ghosts")).isEmpty(), "Finished exit retained callbacks or ghosts");
                for (Object ghost : exitGhosts) require(field(ghost, "bitmap") == null, "Finished exit retained a control snapshot");
            }
            invoke(dashboard(), "toggleEditing"); Object first = field(dashboard(), "editTransition");
            invoke(dashboard(), "toggleEditing"); Object second = field(dashboard(), "editTransition");
            invoke(dashboard(), "toggleEditing");
            require(dashboard().isEditing(), "Rapid toggles delayed the final editing state");
            for (Object cancelled : new Object[] {first, second}) if (cancelled != null)
                require(Boolean.TRUE.equals(field(cancelled, "finished")) && field(cancelled, "observer") == null,
                        "Rapid toggle left an obsolete transition active");
        }); settleMotion();
        main(() -> {
            View dashboardView = (View)field(dashboard(), "view");
            require(dashboardView.getParent() instanceof ViewGroup, "Dashboard fixture has no attached parent");
            ((ViewGroup)dashboardView.getParent()).removeView(dashboardView);
            assertJiggleStopped();
            invoke(dashboard(), "close");
            assertJiggleStopped();
        });
    }
    private void assertJiggleStopped() {
        require(Boolean.FALSE.equals(field(grid(), "jigglePosted")), "Inactive dashboard retained its jiggle callback");
        for (Object item : tiles()) require(((Number)field(item, "jiggleAngle")).floatValue() == 0 && ((View)item).getRotation() == 0,
                "Inactive dashboard retained a tilted widget");
    }
    private void assertEditorTransformsSettled() {
        require(field(dashboard(), "editTransition") == null, "Finished editor transition remains active");
        require(Math.abs(grid().getTranslationY()) <= .01f && grid().getScaleX() == 1 && grid().getScaleY() == 1
                        && Math.abs(((View)field(dashboard(), "headerActions")).getTranslationY()) <= .01f,
                "Editor transition left the dashboard displaced");
        for (Object item : tiles()) for (View control : editorControls((View)item)) {
            require(Math.abs(control.getScaleX() - 1) <= .001f && Math.abs(control.getScaleY() - 1) <= .001f,
                    "Finished editor transition left a control scaled");
            float expectedAlpha = control.isEnabled() ? 1 : .42f;
            require(Math.abs(control.getAlpha() - expectedAlpha) <= .01f, "Editor pop did not restore the control's normal alpha");
        }
    }
    private boolean containsWidget(String id) {
        for (DashboardConfig.Widget widget : dashboardConfig().widgets) if (widget.id.equals(id)) return true;
        return false;
    }
    private void assertHistoryControls(boolean undoEnabled, boolean redoEnabled) {
        View undo = described(root(), "Undo"), redo = described(root(), "Redo");
        require(undo != null && redo != null && undo.isShown() && redo.isShown(), "Editing is missing Undo/Redo actions");
        require(undo.isEnabled() == undoEnabled && redo.isEnabled() == redoEnabled, "Undo/Redo enabled states do not match history");
        for (View action : new View[] {undo, redo}) require(action.isClickable() && action.isFocusable()
                        && action.getWidth() >= activity.ui().dp(48) && action.getHeight() >= activity.ui().dp(48),
                "Undo/Redo lost its accessible target");
        Rect a = relativeBounds(undo, root()), b = relativeBounds(redo, root());
        View done = described(root(), "Finish editing dashboard"); require(done != null, "Done action is missing beside history");
        Rect doneBounds = relativeBounds(done, root());
        require(!Rect.intersects(a, b) && a.left >= 0 && b.right <= root().getWidth(), "Undo/Redo overlap or escape the window");
        require(a.width() == b.width() && a.height() == b.height() && a.top == b.top && b.left >= a.right
                        && doneBounds.left >= b.right && Math.abs(doneBounds.exactCenterY() - b.exactCenterY()) <= 1,
                "Undo/Redo must be equal adjacent controls in the same row before Done");
    }
    private void historyAction(String prefix) {
        main(() -> { View action = described(root(), prefix); require(action != null && action.isEnabled(), prefix + " is unavailable"); action.performClick(); });
        settleMotion();
    }
    private void verifyDeletionHistory(DashboardConfig.Widget removed, int count) {
        main(() -> {
            require(dashboardConfig().widgets.size() == count && !containsWidget(removed.id), "Remove did not remove exactly its selected widget");
            Object effect = field(dashboard(), "removalEffect");
            if (!android.animation.ValueAnimator.areAnimatorsEnabled()) require(effect == null, "Disabled animations still created a removal effect");
            else if (effect != null) require(Boolean.TRUE.equals(field(effect, "finished")) && field(field(effect, "ghost"), "bitmap") == null,
                    "Completed removal effect retained its snapshot");
        });
        historyAction("Undo");
        main(() -> {
            require(dashboardConfig().widgets.size() == count + 1 && containsWidget(removed.id), "Undo removal lost the widget identity");
            assertHistoryControls(true, true);
            described((View)tileById(removed.id), "Options for ").performClick();
        }); settleMotion();
        main(() -> { View sheet = dialogRoot(); ((android.widget.EditText)described(sheet, "Widget title")).setText("Settings survive history"); described(sheet, "Save widget options").performClick(); });
        settleMotion();
        main(() -> assertHistoryControls(true, true));
        historyAction("Redo");
        main(() -> require(dashboardConfig().widgets.size() == count && !containsWidget(removed.id), "Redo removal did not remove the same widget"));
        historyAction("Undo");
        main(() -> {
            DashboardConfig.Widget restored = (DashboardConfig.Widget)field(tileById(removed.id), "widget");
            require(restored.title.equals("Settings survive history") && restored.metric.equals(removed.metric)
                            && restored.windowMs == removed.windowMs && restored.keys.equals(removed.keys),
                    "Undo removal rolled back widget settings");
        });
        historyAction("Redo");
    }
    private Map<String, String> storedCells() {
        LinkedHashMap<String, String> snapshot = new LinkedHashMap<>();
        for (DashboardConfig.Widget widget : dashboardConfig().widgets)
            for (Map.Entry<Integer, DashboardLayout.Cell> cell : widget.layouts.entrySet())
                snapshot.put(widget.id + ":" + cell.getKey(), cellText(cell.getValue()));
        return snapshot;
    }
    private void verifyCommittedGestureHistory(DashboardConfig.Widget widget, boolean resize) {
        final AtomicReference<Map<String, String>> before = new AtomicReference<>(), after = new AtomicReference<>();
        main(() -> {
            before.set(storedCells()); View card = (View)tileById(widget.id); int[] location = new int[2]; card.getLocationOnScreen(location);
            float x = location[0] + card.getWidth() / 2f, y = location[1] + card.getHeight() / 2f;
            float step = ((Number)invoke(grid(), "rowHeight")).floatValue();
            invoke(grid(), "beginGesture", new Class<?>[] {card.getClass(), boolean.class, float.class, float.class}, card, resize, x, y);
            invoke(grid(), "updateGesture", new Class<?>[] {float.class, float.class}, x, y + step * 2);
            invoke(grid(), "finishGesture", new Class<?>[] {boolean.class}, true);
        }); settleMotion();
        main(() -> {
            after.set(storedCells()); require(!before.get().equals(after.get()), "Committed " + (resize ? "resize" : "move") + " did not change geometry");
            assertHistoryControls(true, false);
        });
        historyAction("Undo"); main(() -> require(before.get().equals(storedCells()), "Undo did not restore every saved rectangle after gesture"));
        historyAction("Redo"); main(() -> require(after.get().equals(storedCells()), "Redo did not restore the committed gesture rectangles"));
        historyAction("Undo");
    }
    private void verifyRemovalEffectCancellation() {
        main(() -> {
            View card = (View)tiles().get(0); DashboardConfig.Widget widget = (DashboardConfig.Widget)field(card, "widget");
            int count = dashboardConfig().widgets.size();
            described(card, "Remove ").performClick();
            require(dashboardConfig().widgets.size() == count - 1 && !containsWidget(widget.id), "Removal waited for animation before deleting");
            Object effect = field(dashboard(), "removalEffect");
            if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
                require(effect != null && Boolean.FALSE.equals(field(effect, "finished")), "Visible removal did not start its brief effect");
                Bitmap snapshot = (Bitmap)field(field(effect, "ghost"), "bitmap");
                require(snapshot != null && !snapshot.isRecycled() && (long)snapshot.getWidth() * snapshot.getHeight() <= 2000000
                                && snapshot.getWidth() <= 2048 && snapshot.getHeight() <= 2048,
                        "Removal snapshot is missing or exceeds its bounded size");
            } else require(effect == null, "Disabled animations still created a removal snapshot");
            described(root(), "Undo").performClick();
            require(dashboardConfig().widgets.size() == count && containsWidget(widget.id), "Immediate Undo did not restore the widget");
            require(field(dashboard(), "removalEffect") == null, "Immediate Undo left a removal effect attached");
            if (effect != null) require(Boolean.TRUE.equals(field(effect, "finished")) && field(field(effect, "ghost"), "bitmap") == null,
                    "Immediate Undo did not release the removal snapshot");
        }); settleMotion();
    }
    private void verifyResizeCancellation(DashboardConfig.Widget widget) {
        final LinkedHashMap<String, String> before = new LinkedHashMap<>();
        main(() -> {
            Object history = field(dashboard(), "editHistory");
            int undoCount = ((java.util.Collection<?>)field(history, "undo")).size();
            int redoCount = ((java.util.Collection<?>)field(history, "redo")).size();
            int columns = ((Number)field(grid(), "columns")).intValue();
            for (DashboardConfig.Widget other : dashboardConfig().widgets) before.put(other.id, cellText(other.layouts.get(columns)));
            View card = (View)tileById(widget.id); int[] location = new int[2]; card.getLocationOnScreen(location);
            float x = location[0] + card.getWidth(), y = location[1] + card.getHeight();
            invoke(grid(), "beginGesture", new Class<?>[] {card.getClass(), boolean.class, float.class, float.class}, card, true, x, y);
            invoke(grid(), "updateGesture", new Class<?>[] {float.class, float.class}, x - card.getWidth() * 3, y - card.getHeight() * 3);
            DashboardLayout.Cell requested = (DashboardLayout.Cell)invoke(grid(), "previewCell");
            int minimum = ((Number)invoke(grid(), "minimumWidth", new Class<?>[] {DashboardConfig.Widget.class}, widget)).intValue();
            require(requested != null && requested.w >= minimum && requested.h >= widget.minimumHeight(), "Resize preview ignored minimum card size");
            invoke(grid(), "finishGesture", new Class<?>[] {boolean.class}, false);
            for (DashboardConfig.Widget other : dashboardConfig().widgets) require(before.get(other.id).equals(cellText(other.layouts.get(columns))), "Cancelled resize changed a saved rectangle");
            require(field(grid(), "gestureTile") == null && field(grid(), "previewBounds") == null, "Cancel left a live drag or preview");
            require(undoCount == ((java.util.Collection<?>)field(history, "undo")).size()
                            && redoCount == ((java.util.Collection<?>)field(history, "redo")).size(),
                    "Cancelled resize consumed history or discarded redo");
        }); settleMotion();
    }
    private String cellText(DashboardLayout.Cell cell) { return cell.x + "," + cell.y + "," + cell.w + "," + cell.h; }
    private Drawable findEditorSquare(Drawable drawable) {
        if (drawable == null) return null;
        if (drawable instanceof GradientDrawable && drawable.getBounds().width() == activity.ui().dp(30)
                && drawable.getBounds().height() == activity.ui().dp(30)) return drawable;
        if (drawable instanceof InsetDrawable) return findEditorSquare(((InsetDrawable)drawable).getDrawable());
        if (drawable instanceof LayerDrawable) {
            LayerDrawable layers = (LayerDrawable)drawable;
            for (int i = 0; i < layers.getNumberOfLayers(); i++) {
                Drawable found = findEditorSquare(layers.getDrawable(i));
                if (found != null) return found;
            }
        }
        return null;
    }
    private void forceMeasure(View view) {
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++)
            forceMeasure(((ViewGroup)view).getChildAt(i));
        view.forceLayout();
    }
    /**
     * Single lines that ellipsize by design: the Lock bar's "Last verified" detail, and the Lock
     * bar's title once Connect has already collapsed to its 48 dp icon. Every other line must fit.
     */
    private boolean ellipsizedByDesign(TextView text) {
        if (text.getEllipsize() == null || text.getMaxLines() != 1) return false;
        for (Object lock : (List<?>)field(activity, "dashboardLocks")) {
            if (!Boolean.TRUE.equals(field(lock, "compact"))) continue;
            if (text == field(lock, "offlineDetail")) return true;
            if (text == field(lock, "offlineTitle") && iconOnlyAction((TextView)field(lock, "connectionAction"))) return true;
        }
        return false;
    }
    /** The Lock bar's narrow Connect/Cancel: a 48 dp icon whose text stays for accessibility only. */
    private static boolean iconOnlyAction(TextView text) {
        android.text.method.TransformationMethod words = text.getTransformationMethod();
        return words != null && words.getTransformation(text.getText(), text).length() == 0
                && text.getCompoundDrawablesRelative()[0] != null;
    }
    private void assertText(TextView text, View ancestor, String context) { assertText(text, ancestor, context, false); }
    private void assertText(TextView text, View ancestor, String context, boolean mayEllipsize) {
        if (!text.isShown() || text.length() == 0) return;
        android.text.Layout layout = text.getLayout(); require(layout != null && layout.getLineCount() > 0, context + " text has no layout: " + text.getText());
        Rect rect = relativeBounds(text, ancestor);
        require(rect.left >= 0 && rect.top >= 0 && rect.right <= ancestor.getWidth() + 1 && rect.bottom <= ancestor.getHeight() + 1,
                context + " text outside viewport: " + text.getText() + " " + rect);
        require(layout.getHeight() <= text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom() + 2,
                context + " text clipped vertically: " + text.getText());
        require(layout.getLineEnd(layout.getLineCount() - 1) >= text.length(), context + " text lost lines: " + text.getText());
        for (int line = 0; line < layout.getLineCount(); line++) require(mayEllipsize || layout.getEllipsisCount(line) == 0, context + " text ellipsized: " + text.getText());
    }
    private Rect relativeBounds(View view, View ancestor) {
        Rect box = new Rect(0, 0, view.getWidth(), view.getHeight());
        while (view != ancestor) {
            require(view.getParent() instanceof View, "Missing ancestor");
            View parent = (View)view.getParent();
            // A child's internal text scroll does not move its hit target; its parent's scroll does.
            box.offset(view.getLeft() - parent.getScrollX(), view.getTop() - parent.getScrollY()); view = parent;
        }
        return box;
    }
    private List<TextView> texts(View view) {
        List<TextView> found = new ArrayList<>(); if (view instanceof TextView) found.add((TextView)view);
        if (view instanceof ViewGroup) for (int i=0; i<((ViewGroup)view).getChildCount(); i++) found.addAll(texts(((ViewGroup)view).getChildAt(i)));
        return found;
    }
    private SharedPreferences settings() { return getTargetContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE); }
    private DashboardScreen dashboard() { return (DashboardScreen)field(activity, "screen"); }
    private ViewGroup grid() { return (ViewGroup)field(dashboard(), "grid"); }
    private View root() { return (View)field(activity, "root"); }
    private List<?> tiles() { return (List<?>)field(dashboard(), "tiles"); }
    private Object tile(String type) { for (Object item : tiles()) if (((DashboardConfig.Widget)field(item, "widget")).type.equals(type)) return item; throw new IllegalStateException("Missing " + type); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(100); waitForIdleSync(); }
    private void check(String text) { checks.add(text); }
    private void screenshot(String name) throws Exception {
        settle(); Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "Screenshot unavailable");
        File folder = new File(getTargetContext().getExternalFilesDir(null), "responsive-validation"); require(folder.isDirectory() || folder.mkdirs(), "Cannot create evidence folder");
        File output = new File(folder, arguments.getString("variant", "responsive").replaceAll("[^A-Za-z0-9_-]", "") + "-" + name + ".png");
        try (FileOutputStream stream = new FileOutputStream(output)) { require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream), "Screenshot failed"); }
        finally { bitmap.recycle(); } screenshots.add(output.getAbsolutePath());
    }
    private void await(BooleanSupplier condition, String message) { long end=SystemClock.elapsedRealtime()+6000;
        while (SystemClock.elapsedRealtime()<end) { AtomicReference<Boolean> result=new AtomicReference<>(false); main(() -> result.set(condition.getAsBoolean())); if(result.get())return; SystemClock.sleep(40); } throw new IllegalStateException(message); }
    private void main(Runnable action) { AtomicReference<Throwable> failure = new AtomicReference<>(); runOnMainSync(() -> { try { action.run(); } catch(Throwable error) { failure.set(error); } }); if(failure.get()!=null) throw new IllegalStateException(failure.get().getMessage(), failure.get()); }
    private static Object field(Object target,String name) { try { Field f=target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch(Exception error) { throw new IllegalStateException("Field " + name,error); } }
    private static void setField(Object target,String name,Object value) { try { Field f=target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target,value); } catch(Exception error) { throw new IllegalStateException("Field " + name,error); } }
    private static Object invoke(Object target,String name) { return invoke(target,name,new Class<?>[0]); }
    private static Object invoke(Object target,String name,Class<?>[] types,Object...args) { try { Method m=target.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(target,args); } catch(Exception error) { throw new IllegalStateException("Method " + name,error); } }
    private static void require(boolean value,String message) { if(!value)throw new IllegalStateException(message); }
}
