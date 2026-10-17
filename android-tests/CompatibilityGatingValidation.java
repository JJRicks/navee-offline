package local.navee.offlinelock.tests;

import android.Manifest;
import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import local.navee.offlinelock.BleController;
import local.navee.offlinelock.CapabilityResolver;
import local.navee.offlinelock.MainActivity;
import local.navee.offlinelock.ScooterCommands;
import local.navee.offlinelock.ScooterProfiles;
import local.navee.offlinelock.ScooterService;

/**
 * Fresh disposable emulator only. Uses the developer simulator's model choices (UT5 MAX, a mapped
 * OEM group, an unknown model) to check that every UI gate follows the same resolver decisions as
 * the write gate: Scooter rows, the limited-model banner, the clock row, the lock switch and Quick
 * access tiles (muted, never removed from the saved layout). The simulator builds no frames.
 */
public final class CompatibilityGatingValidation extends Instrumentation {
    private static final String[][] MODELS = {{"", "ut5"}, {"24431", "mapped"}, {"39991", "unknown"}};
    private Bundle arguments;
    private MainActivity activity;
    private boolean admitted;
    private int assertions;
    private final ArrayList<String> checks = new ArrayList<>(), screenshots = new ArrayList<>();

    @Override public void onCreate(Bundle args) { arguments = args == null ? new Bundle() : args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            admit(); admitted = true;
            require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                    .putBoolean("developer_options", true).putString("theme", "dark").putString("units", "mi")
                    .putFloat("text_scale", Float.parseFloat(arguments.getString("text_scale", "1"))).commit(), "Fixture settings failed");
            seed(); launch();
            Map<String, String> layouts = layoutSnapshot();
            main(() -> require(activity.service().setSimulation(true), "Simulation did not start")); settle();
            for (String[] model : MODELS) {
                main(() -> activity.service().ble.setSimulationModel(model[0])); settle();
                verifyScooterPage(model[0], model[1]);
                verifyHome(model[0], model[1]);
            }
            checks.add("Scooter rows show only controls with a known encoding for the simulated model; the UT5 MAX keeps every row");
            checks.add("Limited-model banner, clock row and lock switch follow the resolver; unlock stays available on every standard model");
            checks.add("Unmapped Quick access tiles show a muted dash and stay in the saved layout");
            main(() -> activity.service().ble.setSimulationModel("")); settle();
            require(layouts.equals(layoutSnapshot()), "Saved dashboard layouts changed while switching simulated models");
            main(() -> require(activity.service().setSimulation(false), "Simulation did not stop")); settle();
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", error.toString());
            if (activity != null) try { screenshot("failure"); } catch (Throwable ignored) { }
        } finally {
            result.putInt("assertions", assertions); result.putStringArrayList("checks", checks);
            result.putStringArrayList("screenshots", screenshots); result.putBoolean("physical_device_tested", false);
            if (admitted && activity != null) try { main(() -> { activity.service().setSimulation(false); activity.finish(); ScooterService.setUiVisible(false); getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); }); } catch (Throwable ignored) { }
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    private void admit() {
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
        require(hardware.contains("ranchu") || hardware.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "Refusing a physical phone");
        for (String name : new String[]{"app_settings", "profiles", "scooter", "pending_documents", "control_evidence"})
            require(prefs(name).getAll().isEmpty(), "Refusing existing private preferences");
        File[] files = getTargetContext().getFilesDir().listFiles();
        require(files != null && files.length == 0 && getTargetContext().databaseList().length == 0, "Refusing existing private data");
        for (String permission : new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT})
            require(getTargetContext().checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED, "Grant Nearby permission on the emulator first");
        BluetoothManager manager = getTargetContext().getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        require(adapter == null || !adapter.isEnabled(), "Bluetooth must be disabled before validation");
    }
    private void seed() {
        main(() -> {
            ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
            ScooterProfiles.Profile profile = profiles.create("Synthetic compatibility fixture", "000000000000", 0);
            profile.serial = "X2538-SYNTHETIC"; profile.lastConnected = System.currentTimeMillis() - 60000; profiles.update(profile);
        });
    }
    private void launch() {
        activity = (MainActivity)startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> activity.service() != null && root().isAttachedToWindow() && root().getWidth() > 0, "App did not launch"); settle();
    }

    private void verifyScooterPage(String pid, String label) throws Exception {
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "controls")); settle();
        main(() -> {
            BleController ble = activity.service().ble;
            Object screen = field(activity, "screen");
            require(screen.getClass().getSimpleName().equals("ControlsScreen"), "Scooter page is not active");
            boolean ut5 = pid.isEmpty();
            for (Object row : (List<?>)field(screen, "rows")) {
                ScooterCommands.Feature feature = (ScooterCommands.Feature)field(row, "feature");
                View card = (View)field(row, "card");
                String group = (String)invoke(screen, "groupOf", new Class<?>[]{ScooterCommands.Feature.class}, feature);
                boolean onPage = !group.equals("Line lights") && !group.equals("Star lights");
                boolean known = feature.key.equals("range_algorithm") || ble.controlOffered(feature.key);
                require(known == (feature.key.equals("range_algorithm") || ble.decision(feature.key).support == CapabilityResolver.Support.KNOWN_ENCODING), label + ": simulated models always have identity evidence");
                require((card.getVisibility() == View.VISIBLE) == (known && onPage), label + ": row " + feature.key + " visibility does not follow its decision");
                if (ut5) require(known, "UT5 MAX lost row " + feature.key);
            }
            View banner = (View)field(screen, "readOnlyBanner");
            require((banner.getVisibility() == View.VISIBLE) == !ut5, label + ": limited-model banner");
            require(((View)field(screen, "identifyAction")).getVisibility() != View.VISIBLE, label + ": no Identify for a simulated advertised model");
            View clockCard = (View)field(field(screen, "clockLine"), "card");
            boolean clockKnown = ble.controlOffered("clock_sync");
            require((clockCard.getVisibility() == View.VISIBLE) == clockKnown, label + ": clock row visibility");
            CapabilityResolver.Decision clock = ble.decision("clock_sync");
            require(((View)field(screen, "clockButton")).isEnabled() == (clock.available() || clock.blockedByRide()), label + ": clock row enabled state");
            View topSpeed = (View)field(field(screen, "topSpeed"), "card");
            require((topSpeed.getVisibility() == View.VISIBLE) == ble.controlOffered("maximum_speed"), label + ": top speed card");
        });
        screenshot(label + "-scooter");
        checks.add(label + ": Scooter page follows decisions");
    }

    private void verifyHome(String pid, String label) throws Exception {
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "home")); settle();
        main(() -> {
            BleController ble = activity.service().ble;
            Object lock = field(activity, "lockControl");
            if (lock instanceof View) {
                require(ble.decision("lock", 1).available() && ble.decision("lock", 0).available(), label + ": lock and unlock are offered on a standard model");
                require(((View)lock).isEnabled(), label + ": lock switch enabled");
            }
            for (View grid : tagged(root(), "quick-access-grid", "setting-tiles"))
                for (int i = 0; i < ((ViewGroup)grid).getChildCount(); i++) {
                    View cell = ((ViewGroup)grid).getChildAt(i);
                    Object entry = field(cell, "entry");
                    ScooterCommands.Feature feature = (ScooterCommands.Feature)invoke(entry, "feature");
                    if (feature == null || feature.key.equals("range_algorithm")) continue;
                    boolean unmapped = !ble.controlOffered(feature.key);
                    CharSequence description = cell.getContentDescription();
                    require(unmapped == (description != null && description.toString().contains("not on this model")), label + ": tile " + feature.key + " muted state");
                    if (pid.isEmpty()) require(!unmapped, "UT5 MAX tile became unmapped: " + feature.key);
                }
        });
        screenshot(label + "-home");
    }

    private List<View> tagged(View view, String... tags) {
        ArrayList<View> found = new ArrayList<>();
        for (String tag : tags) if (tag.equals(view.getTag())) found.add(view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) found.addAll(tagged(((ViewGroup)view).getChildAt(i), tags));
        return found;
    }
    /** Saved layouts and slots: switching the simulated model must never rewrite them. */
    private Map<String, String> layoutSnapshot() {
        TreeMap<String, String> layouts = new TreeMap<>();
        for (Map.Entry<String, ?> e : settings().getAll().entrySet()) {
            String key = e.getKey().toLowerCase(Locale.ROOT);
            if (key.contains("dashboard") || key.contains("layout") || key.contains("slot") || key.contains("quick")) layouts.put(e.getKey(), String.valueOf(e.getValue()));
        }
        return layouts;
    }

    private SharedPreferences settings() { return prefs("app_settings"); }
    private SharedPreferences prefs(String name) { return getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE); }
    private View root() { return (View)field(activity, "root"); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(300); waitForIdleSync(); }
    private void main(Runnable task) { runOnMainSync(task); }
    private void await(BooleanSupplier condition, String message) { long end = SystemClock.uptimeMillis() + 8000; while (!condition.getAsBoolean() && SystemClock.uptimeMillis() < end) SystemClock.sleep(40); require(condition.getAsBoolean(), message); }
    private void require(boolean condition, String message) { assertions++; if (!condition) throw new IllegalStateException(message); }
    private Object field(Object target, String name) { try { Class<?> type = target.getClass(); while (type != null) { try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch (NoSuchFieldException e) { type = type.getSuperclass(); } } throw new NoSuchFieldException(name); } catch (Exception e) { throw new IllegalStateException("Field " + name, e); } }
    private Object invoke(Object target, String name) { return invoke(target, name, new Class<?>[0]); }
    private Object invoke(Object target, String name, Class<?>[] types, Object... args) { try { Class<?> type = target.getClass(); while (type != null) { try { Method m = type.getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(target, args); } catch (NoSuchMethodException e) { type = type.getSuperclass(); } } throw new NoSuchMethodException(name); } catch (Exception e) { throw new IllegalStateException("Method " + name, e); } }
    private void screenshot(String label) throws Exception {
        File dir = new File(getTargetContext().getExternalFilesDir(null), "compatibility-validation"); require(dir.isDirectory() || dir.mkdirs(), "Screenshot directory unavailable");
        String variant = arguments.getString("variant", "compat").replaceAll("[^a-zA-Z0-9_-]", ""); File file = new File(dir, variant + "-" + label + ".png");
        android.graphics.Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "Screenshot unavailable");
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); } finally { bitmap.recycle(); }
        screenshots.add(file.getAbsolutePath());
    }
}
