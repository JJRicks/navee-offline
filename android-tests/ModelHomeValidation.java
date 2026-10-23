package com.jjricks.volt.tests;

import android.Manifest;
import android.app.Instrumentation;
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
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import com.jjricks.volt.DashboardConfig;
import com.jjricks.volt.MainActivity;
import com.jjricks.volt.ScooterProfiles;

/**
 * Fresh disposable emulator only. Home follows a known model (the GT5 Max, Model ID 25462): its default
 * Setting tiles swap scheduled charging, which its official app doesn't offer, for cruise control and keep
 * Top speed, including when an open Home learns the model and when defaults are restored (whose Undo survives
 * a rebuild); an owner's own tile choice is never touched; its reported drive modes read DRIVE and SPORT;
 * and a Top speed outside the model's choices shows a dash, not a raw number.
 */
public final class ModelHomeValidation extends Instrumentation {
    private static final List<String> DEFAULT = Arrays.asList("feature:maximum_speed", "feature:timed_charging"),
            GT5 = Arrays.asList("feature:maximum_speed", "feature:cruise_control");
    private Bundle arguments;
    private MainActivity activity;
    private int assertions;
    private final ArrayList<String> screenshots = new ArrayList<>();
    private String gt5Id;

    @Override public void onCreate(Bundle args) { arguments = args == null ? new Bundle() : args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            admit();
            require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false).putBoolean("developer_options", true)
                    .putString("theme", arguments.getString("theme", "dark")).commit(), "Fixture settings failed");
            seed();
            ownersChoiceKept();
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.service() != null, "App did not launch"); settle();
            main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "home")); settle();
            // Not identified yet: the release pair.
            main(() -> require(DEFAULT.equals(shortcuts(new DashboardConfig(settings(), gt5Id))) && described(root().findViewWithTag("setting-tiles"), "Charge schedule"),
                    "An unidentified scooter's Home lost the default tiles"));
            // The open Home learns the model (its advertised ID is captured): the untouched pair adapts in place, no reopening.
            main(() -> {
                ScooterProfiles.Profile p = null; for (ScooterProfiles.Profile q : activity.service().profiles.all()) if (q.id.equals(gt5Id)) p = q;
                p.advertisedPid = "25462"; p.advertisedPidWall = 1; activity.service().profiles.update(p);
                invoke(field(activity, "screen"), "refresh", new Class<?>[0]);
            });
            settle();
            // The GT5 Max dashboard: Top speed stays, Charge schedule becomes Cruise control, and that is saved.
            main(() -> {
                DashboardConfig saved = new DashboardConfig(settings(), gt5Id);
                require(GT5.equals(shortcuts(saved)), "The GT5 Max's default tiles were not adapted: " + shortcuts(saved));
                View grid = root().findViewWithTag("setting-tiles");
                require(grid != null && described(grid, "Top speed") && described(grid, "Cruise control") && !described(grid, "Charge schedule"), "Home does not show the GT5 Max tiles");
            });
            screenshot("home-gt5-tiles");
            restoreKeepsUndo();

            // Simulated GT5 Max: its reported codes 2 and 3 read DRIVE and SPORT; the simulator's 50 km/h isn't a GT5 Max choice.
            main(() -> require(activity.service().setSimulation(true), "Simulation did not start")); settle();
            main(() -> activity.service().ble.setSimulationModel("25462")); settle();
            await(() -> { AtomicReference<Boolean> ready = new AtomicReference<>(false); main(() -> ready.set(activity.service().ble.ready())); return ready.get(); }, "Simulated GT5 Max did not connect");
            simulateMode(2);
            await(() -> { AtomicReference<Boolean> shown = new AtomicReference<>(false); main(() -> shown.set(text(root(), "DRIVE") != null)); return shown.get(); }, "Mode 2 does not read DRIVE");
            screenshot("home-gt5-drive");
            simulateMode(3);
            await(() -> { AtomicReference<Boolean> shown = new AtomicReference<>(false); main(() -> shown.set(text(root(), "SPORT") != null && text(root(), "3 (raw)") == null)); return shown.get(); }, "Mode 3 does not read SPORT");
            await(() -> { AtomicReference<Boolean> shown = new AtomicReference<>(false);
                main(() -> { View grid = root().findViewWithTag("setting-tiles"); shown.set(grid != null && described(grid, "Top speed, —, unreadable value") && !shownText(grid, "50 km/h") && !shownText(grid, "31 mph")); });
                return shown.get(); }, "An unreadable Top speed shows a number instead of a dash");
            screenshot("home-gt5-sport");
            main(() -> require(activity.service().setSimulation(false), "Simulation did not stop"));
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", error.toString()); error.printStackTrace();
        } finally {
            result.putInt("assertions", assertions); result.putString("screenshots", screenshots.toString());
            result.putBoolean("physical_device_tested", false);
            try { if (activity != null) main(() -> activity.finish()); } catch (Throwable ignored) { }
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    /**
     * On the known GT5 Max: after the owner changes Home, Restore default widgets shows the GT5 Max tiles at once,
     * and its Undo survives an Activity rebuild and brings the owner's Home back.
     */
    private void restoreKeepsUndo() throws Exception {
        main(() -> invoke(field(activity, "screen"), "toggleEditing", new Class<?>[0])); settle();
        AtomicReference<String> owners = new AtomicReference<>();
        main(() -> {
            DashboardConfig config = (DashboardConfig) field(field(activity, "screen"), "config");
            for (DashboardConfig.Widget w : config.widgets) if (w.type.equals("shortcuts")) { w.quickActions.clear(); w.quickActions.addAll(Arrays.asList("feature:headlight", "feature:turn_sound")); }
            config.save(); owners.set(settings().getString("dashboard_v1_" + gt5Id, ""));
            invoke(field(activity, "screen"), "render", new Class<?>[0]);
        });
        settle();
        main(() -> invoke(field(activity, "screen"), "restoreDefaults", new Class<?>[0])); settle();
        main(() -> require(GT5.equals(shortcuts((DashboardConfig) field(field(activity, "screen"), "config"))) && GT5.equals(shortcuts(new DashboardConfig(settings(), gt5Id)))
                && settings().contains("dashboard_restore_undo_v1_" + gt5Id), "Restore default widgets on a GT5 Max did not show its tiles at once, or offered no Undo"));
        ActivityMonitor recreated = addMonitor(MainActivity.class.getName(), null, false);
        main(() -> activity.recreate());
        MainActivity next = (MainActivity) waitForMonitorWithTimeout(recreated, 10000); removeMonitor(recreated);
        require(next != null && next != activity, "The Activity was not recreated");
        activity = next; await(() -> activity.service() != null, "The recreated Activity did not bind"); settle();
        await(() -> { AtomicReference<Boolean> shown = new AtomicReference<>(false);
            main(() -> { Object notice = field(field(activity, "screen"), "notice"); shown.set(notice instanceof android.widget.PopupWindow && ((android.widget.PopupWindow) notice).isShowing()); });
            return shown.get(); }, "Restoring defaults lost its Undo after the rebuild");
        main(() -> {
            View undo = ((android.widget.PopupWindow) field(field(activity, "screen"), "notice")).getContentView().findViewWithTag("dashboard-notice-action");
            require(undo != null && undo.isClickable(), "The Defaults restored notice has no Undo"); undo.performClick();
        });
        settle();
        require(owners.get().equals(settings().getString("dashboard_v1_" + gt5Id, "")), "Undo did not bring the owner's Home back");
        main(() -> require(Arrays.asList("feature:headlight", "feature:turn_sound").equals(shortcuts(new DashboardConfig(settings(), gt5Id))), "The owner's tiles did not come back"));
        main(() -> invoke(field(activity, "screen"), "toggleEditing", new Class<?>[0])); settle();
        // Back to the model's defaults for the rest of the run.
        main(() -> {
            DashboardConfig config = (DashboardConfig) field(field(activity, "screen"), "config");
            for (DashboardConfig.Widget w : config.widgets) if (w.type.equals("shortcuts")) { w.quickActions.clear(); w.quickActions.addAll(GT5); }
            config.save(); invoke(field(activity, "screen"), "render", new Class<?>[0]);
        });
        settle();
    }

    /** Before Home opens: a dashboard whose owner chose other tiles keeps them; only the untouched pair adapts. */
    private void ownersChoiceKept() {
        main(() -> {
            SharedPreferences scratch = getTargetContext().getSharedPreferences("model_home_scratch", Context.MODE_PRIVATE);
            DashboardConfig chosen = new DashboardConfig(scratch, "chosen");
            List<String> owner = Arrays.asList("feature:timed_charging", "feature:headlight");
            DashboardConfig.Widget tiles = null; for (DashboardConfig.Widget w : chosen.widgets) if (w.type.equals("shortcuts")) tiles = w;
            require(tiles != null && DEFAULT.equals(tiles.quickActions), "A new dashboard does not start with the default tiles");
            tiles.quickActions.clear(); tiles.quickActions.addAll(owner);
            require(!chosen.adaptSettingTiles(GT5) && owner.equals(tiles.quickActions), "An owner's tile choice was replaced");
            tiles.quickActions.clear(); tiles.quickActions.addAll(DEFAULT);
            require(!chosen.adaptSettingTiles(DEFAULT) && chosen.adaptSettingTiles(GT5) && GT5.equals(tiles.quickActions) && !chosen.adaptSettingTiles(GT5), "The default pair did not adapt exactly once");
            scratch.edit().clear().commit();
            // A dashboard made for a known model starts with, and restores to, that model's tiles.
            DashboardConfig fresh = new DashboardConfig(scratch, "fresh", false, GT5);
            require(GT5.equals(shortcuts(fresh)), "A new GT5 Max dashboard did not start with its tiles");
            for (DashboardConfig.Widget w : fresh.widgets) if (w.type.equals("shortcuts")) { w.quickActions.clear(); w.quickActions.add("feature:headlight"); }
            fresh.reset();
            require(GT5.equals(shortcuts(fresh)), "Restoring defaults brought back tiles the GT5 Max doesn't offer");
            scratch.edit().clear().commit();
        });
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
        require(manager == null || manager.getAdapter() == null || !manager.getAdapter().isEnabled(), "Emulator Bluetooth must stay off");
    }
    private void seed() {
        main(() -> {
            ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
            ScooterProfiles.Profile gt5 = profiles.create("Synthetic GT5", "0F1E2D3C4B5A", 0);
            // Not identified yet: the test captures its Model ID while Home is open.
            gt5.mac = "C0:FF:EE:00:00:05"; gt5.lastConnected = System.currentTimeMillis() - 60000; profiles.update(gt5);
            gt5Id = gt5.id;
        });
        prefs("onboarding").edit().putBoolean("onboarding_done", true).commit();
    }
    private static List<String> shortcuts(DashboardConfig config) { for (DashboardConfig.Widget w : config.widgets) if (w.type.equals("shortcuts")) return w.quickActions; return null; }
    /** Some view under root has a content description starting with this text. */
    private static boolean described(View view, String prefix) {
        CharSequence d = view.getContentDescription();
        if (view.isShown() && d != null && d.toString().startsWith(prefix)) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) if (described(((ViewGroup) view).getChildAt(i), prefix)) return true;
        return false;
    }
    private static boolean shownText(View view, String text) { TextView found = text(view, text); return found != null; }
    private static TextView text(View view, String text) {
        if (view instanceof TextView && view.isShown() && text.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { TextView found = text(((ViewGroup) view).getChildAt(i), text); if (found != null) return found; }
        return null;
    }
    /** The simulator now reports this drive mode (it derives the mode from the acceleration readback, so move both). */
    @SuppressWarnings("unchecked") private void simulateMode(int code) {
        main(() -> {
            try {
                Object simulation = field(activity.service().ble, "simulation");
                java.util.Map<String, Object> fixed = (java.util.Map<String, Object>) field(simulation, "fixed");
                Class<?> sample = Class.forName("com.jjricks.volt.ScooterSimulation$Sample", true, simulation.getClass().getClassLoader());
                java.lang.reflect.Constructor<?> make = sample.getDeclaredConstructor(String.class, Double.class); make.setAccessible(true);
                fixed.put("driving_mode", make.newInstance("Mode " + code + " (simulation)", (double) code));
                ((java.util.Map<String, Integer>) field(simulation, "features")).put("acceleration", code);
                activity.service().ble.refresh(false);
            } catch (Exception error) { throw new IllegalStateException(error); }
        });
        settle();
    }
    private void screenshot(String label) throws Exception {
        File dir = new File(getTargetContext().getExternalFilesDir(null), "model-home-validation"); require(dir.isDirectory() || dir.mkdirs(), "Screenshot directory unavailable");
        File file = new File(dir, arguments.getString("theme", "dark") + "-" + label + ".png");
        android.graphics.Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "Screenshot unavailable");
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); } finally { bitmap.recycle(); }
        screenshots.add(file.getAbsolutePath());
    }
    private View root() { return (View) field(activity, "root"); }
    private SharedPreferences settings() { return prefs("app_settings"); }
    private SharedPreferences prefs(String name) { return getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(400); waitForIdleSync(); }
    private void main(Runnable task) {
        AtomicReference<Throwable> error = new AtomicReference<>();
        runOnMainSync(() -> { try { task.run(); } catch (Throwable failure) { error.set(failure); } });
        if (error.get() != null) throw new IllegalStateException(error.get());
    }
    private void await(BooleanSupplier condition, String message) {
        long end = SystemClock.uptimeMillis() + 12000;
        while (!condition.getAsBoolean() && SystemClock.uptimeMillis() < end) SystemClock.sleep(100);
        require(condition.getAsBoolean(), message);
    }
    private void require(boolean condition, String message) { assertions++; if (!condition) throw new IllegalStateException(message); }
    private Object field(Object target, String name) {
        try { Class<?> type = target.getClass(); while (type != null) { try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch (NoSuchFieldException next) { type = type.getSuperclass(); } }
            throw new NoSuchFieldException(name);
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    private Object invoke(Object target, String name, Class<?>[] types, Object... args) {
        try { Method m = target.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(target, args); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
}
