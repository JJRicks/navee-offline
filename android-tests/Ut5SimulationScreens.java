package local.navee.offlinelock.tests;

import android.Manifest;
import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import local.navee.offlinelock.DashboardConfig;
import local.navee.offlinelock.MainActivity;
import local.navee.offlinelock.MoreScreen;
import local.navee.offlinelock.OnboardingScreen;
import local.navee.offlinelock.ScooterProfiles;
import local.navee.offlinelock.ScooterService;

/**
 * Fresh disposable emulator only. Captures the default UT5 MAX simulator on Home, the Scooter page,
 * settings search, Quick access and the More pages, with a view-tree dump next to each screenshot,
 * so two builds can be compared screen by screen. It uses only APIs that Volt 1.3.1 already has
 * (the same file builds against the 1.3.1 baseline), the simulator builds no frames, and Bluetooth
 * must be off.
 */
public final class Ut5SimulationScreens extends Instrumentation {
    private Bundle arguments;
    private MainActivity activity;
    private boolean admitted;
    private final ArrayList<String> screenshots = new ArrayList<>();

    @Override public void onCreate(Bundle args) { arguments = args == null ? new Bundle() : args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            admit(); admitted = true;
            require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                    .putBoolean("developer_options", true).putString("theme", arguments.getString("theme", "dark")).putString("units", "mi")
                    .putFloat("text_scale", Float.parseFloat(arguments.getString("text_scale", "1"))).commit(), "Fixture settings failed");
            main(() -> {
                ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
                ScooterProfiles.Profile saved = profiles.create("Synthetic UT5 screens fixture", "000000000000", 0);
                saved.serial = "X2538-SYNTHETIC"; saved.lastConnected = 1767225600000L; profiles.update(saved);
                // Default Home plus one Quick access card and one Setting tiles card with scooter controls.
                DashboardConfig config = new DashboardConfig(settings(), saved.id);
                DashboardConfig.Widget quick = new DashboardConfig.Widget("quick");
                quick.quickActions.clear(); quick.quickActions.addAll(Arrays.asList("feature:headlight", "feature:cruise_control",
                        "feature:energy_recovery", "feature:traction_control", "feature:maximum_speed", "feature:dual_drive", "lights", "controls"));
                DashboardConfig.Widget tiles = new DashboardConfig.Widget("shortcuts");
                tiles.quickActions.clear(); tiles.quickActions.addAll(Arrays.asList("feature:auto_sensor", "feature:turn_sound", "feature:charge_limit", "feature:range_algorithm"));
                config.widgets.add(quick); config.widgets.add(tiles); config.save();
                OnboardingScreen.markDone(getTargetContext());
            });
            activity = (MainActivity)startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.service() != null && root().isAttachedToWindow() && root().getWidth() > 0, "App did not launch"); settle();
            main(() -> require(activity.service().setSimulation(true), "Simulation did not start")); settle();
            capture("home");
            reveal("quick-access-grid"); capture("quick");
            reveal("setting-tiles"); capture("tiles");
            select("controls"); capture("scooter");
            main(() -> { EditText search = (EditText)field(field(activity, "screen"), "search"); search.setText("light"); search.clearFocus(); }); settle();
            capture("search-light");
            main(() -> { EditText search = (EditText)field(field(activity, "screen"), "search"); search.setText("speed"); search.clearFocus(); }); settle();
            capture("search-speed");
            main(() -> { EditText search = (EditText)field(field(activity, "screen"), "search"); search.setText(""); search.clearFocus(); }); settle();
            select("more"); capture("more");
            for (String section : new String[]{"Scooters", "Security", "Lab"}) {
                main(() -> invoke(more(), "selectSection", new Class<?>[]{String.class}, section)); settle(); capture("more-" + section.toLowerCase(Locale.ROOT));
            }
            main(() -> invoke(more(), "selectSection", new Class<?>[]{String.class}, "")); settle();
            main(() -> require(activity.service().setSimulation(false), "Simulation did not stop")); settle();
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", error.toString());
        } finally {
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
    private void select(String page) { main(() -> invoke(activity, "select", new Class<?>[]{String.class}, page)); settle(); main(() -> { View scroll = (View)field(activity, "scroll"); if (scroll != null) scroll.scrollTo(0, 0); }); settle(); }
    private void reveal(String tag) {
        main(() -> {
            View view = root().findViewWithTag(tag); require(view != null, "Missing " + tag);
            view.requestRectangleOnScreen(new Rect(0, 0, view.getWidth(), view.getHeight()), true);
        }); settle();
    }
    private void capture(String label) throws Exception {
        // The simulator's sample clock keeps running; a fresh tick right before capture keeps both builds alike.
        main(() -> { activity.service().ble.tickSimulation(); activity.redraw(); }); settle(); SystemClock.sleep(700); settle();
        File dir = new File(getTargetContext().getExternalFilesDir(null), "compatibility-validation"); require(dir.isDirectory() || dir.mkdirs(), "Screenshot directory unavailable");
        String variant = arguments.getString("variant", "ut5").replaceAll("[^a-zA-Z0-9_.-]", "");
        StringBuilder tree = new StringBuilder(); main(() -> dump(activity.getWindow().getDecorView(), 0, tree));
        try (FileOutputStream out = new FileOutputStream(new File(dir, variant + "-" + label + ".txt"))) { out.write(tree.toString().getBytes(StandardCharsets.UTF_8)); }
        android.graphics.Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "Screenshot unavailable");
        File file = new File(dir, variant + "-" + label + ".png");
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); } finally { bitmap.recycle(); }
        screenshots.add(file.getAbsolutePath());
    }
    /** Shown views only: type, on-screen bounds, enabled/alpha, text and spoken description. */
    private void dump(View view, int depth, StringBuilder out) {
        if (!view.isShown()) return;
        int[] at = new int[2]; view.getLocationOnScreen(at);
        char[] indent = new char[depth * 2]; Arrays.fill(indent, ' ');
        out.append(indent).append(view.getClass().getSimpleName()).append(" [").append(at[0]).append(',').append(at[1]).append(' ')
                .append(view.getWidth()).append('x').append(view.getHeight()).append(']');
        if (!view.isEnabled()) out.append(" disabled");
        if (view.getAlpha() < 1f) out.append(" alpha=").append(String.format(Locale.US, "%.2f", view.getAlpha()));
        if (view.getTag() instanceof String) out.append(" tag=").append(view.getTag());
        if (view instanceof TextView && ((TextView)view).getText().length() > 0) out.append(" text=\"").append(((TextView)view).getText().toString().replace('\n', '|')).append('"');
        if (view.getContentDescription() != null) out.append(" desc=\"").append(view.getContentDescription().toString().replace('\n', '|')).append('"');
        out.append('\n');
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) dump(((ViewGroup)view).getChildAt(i), depth + 1, out);
    }
    private MoreScreen more() { Object screen = field(activity, "screen"); require(screen instanceof MoreScreen, "More screen is not active"); return (MoreScreen)screen; }
    private SharedPreferences settings() { return prefs("app_settings"); }
    private SharedPreferences prefs(String name) { return getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE); }
    private View root() { return (View)field(activity, "root"); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(300); waitForIdleSync(); }
    private void main(Runnable task) { runOnMainSync(task); }
    private void await(BooleanSupplier condition, String message) { long end = SystemClock.uptimeMillis() + 8000; while (!condition.getAsBoolean() && SystemClock.uptimeMillis() < end) SystemClock.sleep(40); require(condition.getAsBoolean(), message); }
    private void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private Object field(Object target, String name) { try { Class<?> type = target.getClass(); while (type != null) { try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch (NoSuchFieldException e) { type = type.getSuperclass(); } } throw new NoSuchFieldException(name); } catch (Exception e) { throw new IllegalStateException("Field " + name, e); } }
    private Object invoke(Object target, String name, Class<?>[] types, Object... args) { try { Class<?> type = target.getClass(); while (type != null) { try { Method m = type.getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(target, args); } catch (NoSuchMethodException e) { type = type.getSuperclass(); } } throw new NoSuchMethodException(name); } catch (Exception e) { throw new IllegalStateException("Method " + name, e); } }
}
