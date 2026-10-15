package local.navee.offlinelock.tests;

import android.Manifest;
import android.app.AlertDialog;
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
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import local.navee.offlinelock.*;

/** Disposable emulator regression: real Quick access touches and system vibrator history. */
public final class QuickHapticsValidation extends Instrumentation {
    private MainActivity activity;
    private final Bundle result = new Bundle();
    private final ArrayList<String> checks = new ArrayList<>();
    private File evidence;
    private String oldSystem;
    private int assertions;
    @Override public void onCreate(Bundle args) { start(); }
    @Override public void onStart() {
        try {
            require(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "Refusing physical device");
            require(prefs("profiles").getAll().isEmpty() && prefs("scooter").getAll().isEmpty(), "Refusing existing profiles; clear disposable app first");
            require(Build.VERSION.SDK_INT >= 34, "Requires API 34+ vibrator diagnostics");
            require(prefs("app_settings").getAll().isEmpty(), "Refusing existing app preferences");
            require(getTargetContext().databaseList().length == 0, "Refusing existing databases");
            require(getTargetContext().getFilesDir().list().length == 0, "Refusing existing files");
            require(getTargetContext().checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED, "Grant Nearby permission first");
            BluetoothManager manager = getTargetContext().getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            require(adapter == null || !adapter.isEnabled(), "Disable emulator Bluetooth first");
            evidence = new File(getTargetContext().getExternalFilesDir(null), "haptics-validation/quick");
            require(evidence.mkdirs() || evidence.isDirectory(), "Evidence directory");
            oldSystem = shell("settings get system haptic_feedback_enabled").trim();
            require(oldSystem.equals("null") || oldSystem.matches("[0-9]+"), "Unexpected haptic preference value");
            shell("settings put system haptic_feedback_enabled 1");
            main(() -> {
                prefs("app_settings").edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                    .putBoolean("developer_options", true).putBoolean("haptics_enabled", true).putInt("dashboard_columns", 2).commit();
                ScooterProfiles.Profile profile = new ScooterProfiles(getTargetContext()).create("Synthetic quick-haptics fixture", "000000000000", 0);
                DashboardConfig config = new DashboardConfig(prefs("app_settings"), profile.id);
                config.widgets.clear();
                DashboardConfig.Widget quick = new DashboardConfig.Widget("quick");
                quick.quickActions.clear(); quick.quickActions.addAll(Arrays.asList("feature:traction_control", "feature:maximum_speed", "lights"));
                quick.layouts.put(2, new DashboardLayout.Cell(quick.id, 0, 0, 2, 4)); config.widgets.add(quick); config.save();
                OnboardingScreen.markDone(getTargetContext());
            });
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.service() != null && activity.getWindow().getDecorView().hasWindowFocus(), "Activity not ready");
            main(() -> { require(activity.service().setSimulation(true), "Simulation unavailable"); activity.redraw(); }); settle();
            require(value() == 1, "Expected starting traction control on");
            check("toggle-off", 22, () -> tap("feature:traction_control")); require(value() == 0, "Tap did not request Off");
            check("toggle-on", 21, () -> tap("feature:traction_control")); require(value() == 1, "Tap did not request On");
            check("refresh", 0, () -> main(() -> { activity.redraw(); activity.service().ble.tickSimulation(); activity.redraw(); }));
            main(() -> prefs("app_settings").edit().putBoolean("haptics_enabled", false).commit()); settle();
            check("app-opt-out", 0, () -> tap("feature:traction_control")); require(value() == 0, "Opt-out prevented control change");
            main(() -> prefs("app_settings").edit().putBoolean("haptics_enabled", true).commit());
            shell("settings put system haptic_feedback_enabled 0"); settle();
            check("system-opt-out", 0, () -> tap("feature:traction_control")); require(value() == 1, "System opt-out prevented control change");
            shell("settings put system haptic_feedback_enabled 1"); settle();
            check("menu", 0, () -> tap("lights"));
            main(() -> { require(field(activity, "quickMenuDialog") instanceof AlertDialog && ((AlertDialog)field(activity, "quickMenuDialog")).isShowing(), "Menu did not open"); call(activity, "closeQuickMenu"); }); settle();
            check("non-toggle", 0, () -> tap("feature:maximum_speed"));
            main(() -> { require(field(activity, "controlDialog") instanceof AlertDialog && ((AlertDialog)field(activity, "controlDialog")).isShowing(), "Non-toggle edit did not open"); ((AlertDialog)field(activity, "controlDialog")).dismiss(); }); settle();
            main(() -> setField(activity.service().ble, "mutation", pending()));
            check("pending", 0, () -> tap("feature:traction_control")); require(value() == 1, "Pending guard allowed toggle");
            main(() -> { setField(activity.service().ble, "mutation", null); call(field(activity, "screen"), "toggleEditing"); }); settle();
            check("editing", 0, () -> tap("feature:traction_control")); require(value() == 1, "Editing guard allowed toggle");
            main(() -> call(field(activity, "screen"), "toggleEditing")); settle();
            main(() -> activity.service().ble.disconnect()); settle();
            check("disconnected", 0, () -> tap("feature:traction_control")); require(value() == 1, "Disconnected guard allowed toggle");
            main(this::assertTransportIdle);
            result.putString("result", "PASS");
        } catch (Throwable error) { result.putString("result", "FAIL"); result.putString("error", error.toString()); }
        finally {
            if (oldSystem != null) try {
                shell(oldSystem.equals("null") ? "settings delete system haptic_feedback_enabled"
                        : "settings put system haptic_feedback_enabled " + oldSystem);
                require(oldSystem.equals(shell("settings get system haptic_feedback_enabled").trim()), "System haptics preference cleanup failed");
            } catch (Throwable error) { result.putString("result", "FAIL"); result.putString("cleanup_error", error.toString()); }
            if (activity != null) try {
                main(() -> { activity.finish(); ScooterService.setUiVisible(false);
                    getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); });
                waitForIdleSync();
            } catch (Throwable error) { result.putString("result", "FAIL"); result.putString("cleanup_error", error.toString()); }
            // The portable runner clears synthetic preferences and data in its outer finally.
            // This native finally still restores the global setting if an assertion fails.
            result.putInt("assertions", assertions); result.putStringArrayList("checks", checks); result.putBoolean("physical_device_tested", false);
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }
    private void check(String label, int expected, Runnable action) throws Exception {
        settle(); String before = dump(label + "-before"); action.run(); settle(); String after = dump(label + "-after");
        Set<String> diff = events(after); diff.removeAll(events(before));
        // A disabled system preference may retain an ignored request in diagnostic history;
        // only effects the vibrator manager actually plays count as feedback.
        ArrayList<String> played = new ArrayList<>();
        for (String line : diff) if (!line.contains("ignored")) played.add(line);
        require(played.size() == (expected == 0 ? 0 : 1), label + ": expected " + (expected == 0 ? 0 : 1) + " feedback, got " + played);
        if (expected != 0) require(played.get(0).contains("constant=" + expected + ")"), label + ": wrong requested direction " + played);
        checks.add(label + ": " + played.size() + " played toggle effects" + (expected == 0 ? "" : " constant=" + expected));
    }
    private Set<String> events(String dump) {
        Set<String> out = new LinkedHashSet<>();
        for (String line : dump.split("\n")) if (line.contains("local.navee.offlinelock (uid=") && (line.contains("constant=21)") || line.contains("constant=22)"))) out.add(line.trim());
        return out;
    }
    private String dump(String name) throws Exception {
        String text = shell("dumpsys vibrator_manager");
        try (FileOutputStream out = new FileOutputStream(new File(evidence, name + ".txt"))) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
        return text;
    }
    private String shell(String command) {
        try (ParcelFileDescriptor fd = getUiAutomation().executeShellCommand(command); InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192]; int count; while ((count = in.read(buf)) >= 0) out.write(buf, 0, count); return out.toString("UTF-8");
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    private void tap(String id) {
        AtomicReference<View> found = new AtomicReference<>();
        main(() -> { View view = activity.getWindow().getDecorView().findViewWithTag("quick-action:" + id); require(view != null && view.isShown(), "Missing actual quick cell: " + id); view.requestRectangleOnScreen(new Rect(0, 0, view.getWidth(), view.getHeight()), true); found.set(view); });
        SystemClock.sleep(150); int[] xy = new int[2];
        main(() -> { View view = found.get(); view.getLocationOnScreen(xy); xy[0] += view.getWidth()/2; xy[1] += view.getHeight()/2; });
        long down = SystemClock.uptimeMillis();
        MotionEvent first = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, xy[0], xy[1], 0);
        MotionEvent last = MotionEvent.obtain(down, down + 60, MotionEvent.ACTION_UP, xy[0], xy[1], 0);
        try { sendPointerSync(first); SystemClock.sleep(60); sendPointerSync(last); } finally { first.recycle(); last.recycle(); }
    }
    private int value() { AtomicReference<Integer> out = new AtomicReference<>(); main(() -> out.set(activity.service().ble.featureValue("traction_control"))); return out.get(); }
    private void assertTransportIdle() {
        BleController ble = activity.service().ble;
        for (String name : new String[]{"gatt", "writer", "scanner", "mutation"}) require(field(ble, name) == null, "Transport created " + name);
        require(((Collection<?>)field(ble, "queue")).isEmpty(), "Queued Bluetooth bytes");
        for (String name : new String[]{"scanning", "writing", "wanted", "challengePending", "pinReadPending"}) require(!Boolean.TRUE.equals(field(ble, name)), "Transport flag " + name);
        require(!activity.service().isRecording() && !activity.service().isChargeRecording() && !activity.service().phoneSaving() && field(activity.service(), "phoneRecorder") == null, "Recording started");
        require(activity.service().profiles.active().mac.isEmpty(), "Real address present");
    }
    private Object pending() { try { Class<?> type = Class.forName("local.navee.offlinelock.BleController$Mutation"); Constructor<?> c = type.getDeclaredConstructor(String.class, int.class, int.class, long.class); c.setAccessible(true); return c.newInstance("traction_control", 0, 0, 0L); } catch (Exception e) { throw new RuntimeException(e); } }
    private Object field(Object target, String name) { try { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch (Exception e) { throw new RuntimeException(e); } }
    private void setField(Object target, String name, Object value) { try { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target, value); } catch (Exception e) { throw new RuntimeException(e); } }
    private void call(Object target, String name) { try { Method m = target.getClass().getDeclaredMethod(name); m.setAccessible(true); m.invoke(target); } catch (Exception e) { throw new RuntimeException(e); } }
    private SharedPreferences prefs(String name) { return getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE); }
    private void main(Runnable action) { AtomicReference<Throwable> error = new AtomicReference<>(); runOnMainSync(() -> { try { action.run(); } catch (Throwable e) { error.set(e); } }); if (error.get() != null) throw new RuntimeException(error.get()); }
    private void await(BooleanSupplier condition, String message) { long until = SystemClock.uptimeMillis() + 8000; while (SystemClock.uptimeMillis() < until) { AtomicReference<Boolean> ready = new AtomicReference<>(false); main(() -> ready.set(condition.getAsBoolean())); if (ready.get()) return; SystemClock.sleep(100); } throw new AssertionError(message); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(400); }
    private void require(boolean pass, String message) { assertions++; if (!pass) throw new AssertionError(message); }
}
