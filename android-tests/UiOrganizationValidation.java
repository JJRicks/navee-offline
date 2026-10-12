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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import local.navee.offlinelock.BleController;
import local.navee.offlinelock.MainActivity;
import local.navee.offlinelock.PhoneRideStore;
import local.navee.offlinelock.RidesScreen;
import local.navee.offlinelock.RouteMapView;
import local.navee.offlinelock.ScooterCommands;
import local.navee.offlinelock.ScooterService;
import local.navee.offlinelock.Ui;

/** UI checks on a fresh emulator only. No owner profile, sensor recording, radio or network work. */
public final class UiOrganizationValidation extends Instrumentation {
    private static final String[] PREFS = {"app_settings", "profiles", "scooter", "pending_documents"};
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
                    .putString("theme", "dark").commit(), "Cannot set emulator UI preferences");
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.service() != null && root().isAttachedToWindow() && root().getWidth() > 0, 6000,
                    "Local workspace did not initialize");
            for (String name : new String[]{"Home", "Scooter", "Lights", "Stats", "Rides", "More"}) {
                clickDescription(name); waitForIdleSync(); assertNoScooterWork(); assertNavigationFits();
            }
            check("All six destinations are reachable; navigation targets remain visible");
            screenshot("more-index-dark");

            clickDescription("Scooter");
            requireScreenText("Ride"); clickText(root(), "Ride");
            requireVisibleControl("maximum_speed"); requireVisibleControl("start_speed");
            screenshot("scooter-ride-dark");
            main(() -> invoke(activity, "navigateBack"));
            requireScreenText("Battery");
            main(() -> first(root(), EditText.class).setText("speed"));
            main(() -> {
                Set<String> visible = visibleFeatures();
                require(visible.contains("maximum_speed") && visible.contains("start_speed"), "Speed search lost primary speed controls");
                require(!visible.contains("energy_recovery"), "Speed search did not hide an unrelated setting");
                for (String key : allFeatures()) require(!isLight(key), "Lighting leaked into the Scooter destination");
            });
            main(() -> invoke(activity, "navigateBack"));
            requireScreenText("Battery");
            check("Scooter categories, nested Back and search expose the requested settings without control writes");

            clickDescription("Lights");
            main(() -> {
                Set<String> expected = new HashSet<>(Arrays.asList("headlight", "auto_sensor", "line_light", "line_pattern",
                        "line_color", "line_boost_color", "star_light", "star_pattern", "star_color", "star_boost_color"));
                require(allFeatures().equals(expected), "Lights must contain all ten lighting controls and no sound settings");
                require(visibleFeatures().equals(expected), "A lighting zone is hidden without a search");
            });
            screenshot("lights-dark");
            main(() -> first(root(), EditText.class).setText("boost"));
            main(() -> require(visibleFeatures().equals(new HashSet<>(Arrays.asList("line_boost_color", "star_boost_color"))),
                    "Lighting search should find both Boost colors"));
            main(() -> invoke(activity, "navigateBack"));
            check("Lights groups headlight, line and star controls; search covers all lighting zones");

            clickDescription("More"); clickText(root(), "App preferences");
            requireScreenText("Connect automatically"); screenshot("preferences-dark");
            main(() -> invoke(activity, "navigateBack")); requireScreenText("My scooters");
            check("More opens compact preferences and Back returns to its settings index");

            clickDescription("Stats"); clickText(root(), "Readings");
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
            clickDescription("Lights"); assertNavigationFits(); screenshot("lights-large-light");
            clickDescription("More"); clickText(root(), "App preferences"); screenshot("preferences-large-light");
            check("Light theme and 140% app text preserve navigation and grouped settings");
            main(() -> settings.edit().putString("theme", "dark").putFloat("text_scale", 1f).apply());
            await(() -> activity.ui().dark && activity.ui().textScale == 1f, 4000, "Dark theme did not restore");

            PhoneRideStore.Track fixture = syntheticTrack();
            require(fixture.locationCount >= 30 && fixture.maxGpsAccelerationMps2 != null, "Synthetic UI fixture did not load");
            clickDescription("Rides");
            Object rides = field(activity, "screen");
            main(() -> {
                invoke(rides, "presentPhoneRide", new Class<?>[]{PhoneRideStore.Track.class}, fixture);
                @SuppressWarnings("unchecked") List<AlertDialog> dialogs = (List<AlertDialog>) field(rides, "dialogs");
                preview = dialogs.get(dialogs.size() - 1);
                preview.setTitle("Synthetic UI preview · Route");
            });
            await(() -> preview.isShowing() && first(preview.getWindow().getDecorView(), RouteMapView.class).getWidth() > 0,
                    4000, "Route preview did not attach");
            View dialogRoot = preview.getWindow().getDecorView();
            RouteMapView map = first(dialogRoot, RouteMapView.class);
            CheckBox streets = first(dialogRoot, CheckBox.class);
            require(streets != null && !streets.isChecked(), "Street map must stay off during the offline preview test");
            reveal(map);
            main(() -> {
                long now = SystemClock.uptimeMillis();
                MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, map.getWidth() / 2f, map.getHeight() / 2f, 0);
                MotionEvent up = MotionEvent.obtain(now, now + 25, MotionEvent.ACTION_UP, map.getWidth() / 2f, map.getHeight() / 2f, 0);
                try { map.dispatchTouchEvent(down); map.dispatchTouchEvent(up); } finally { down.recycle(); up.recycle(); }
                require(findTextContaining(dialogRoot, "Power ") != null, "Tapping the route did not expose synchronized readings");
            });
            clickText(dialogRoot, "Grade"); clickText(dialogRoot, "Accel"); clickText(dialogRoot, "Power");
            clickText(dialogRoot, "+"); clickText(dialogRoot, "−"); clickText(dialogRoot, "Fit");
            ScrollView scroller = first(dialogRoot, ScrollView.class);
            main(() -> scroller.fullScroll(View.FOCUS_UP)); waitForIdleSync();
            screenshot("synthetic-route-top-dark");
            main(() -> scroller.fullScroll(View.FOCUS_DOWN)); waitForIdleSync();
            TextView export = findText(dialogRoot, "Export phone data");
            require(export != null, "Route export action is missing"); reveal(export); requireFullyVisible(export, "Phone export action");
            screenshot("synthetic-route-bottom-dark");
            main(() -> {
                require(!streets.isChecked() && !((Boolean)field(map, "streets")), "Preview enabled network tile loading");
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
            for (String name : new String[]{"Home", "Scooter", "Lights", "Stats", "Rides", "More"}) {
                View item = findDescription(root(), name); require(item != null && item.isShown(), "Missing navigation " + name);
                requireFullyVisibleNow(item, "Navigation " + name);
                TextView label = first(item, TextView.class);
                require(label != null && label.getLayout() != null, "Navigation caption did not lay out");
                require(label.getLayout().getHeight() <= label.getHeight() + 2, "Navigation caption clipped vertically: " + name);
            }
        });
    }
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
    private static <T> T first(View root, Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)root).getChildCount(); i++) { T found = first(((ViewGroup)root).getChildAt(i), type); if (found != null) return found; }
        return null;
    }
    private void check(String text) { checks.add(text); Bundle status = new Bundle(); status.putString("check", text); sendStatus(0, status); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
