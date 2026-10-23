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
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import com.jjricks.volt.MainActivity;
import com.jjricks.volt.ScooterProfiles;

/**
 * Fresh disposable emulator only. More's menu card lines up on every width: Back up & restore and each
 * section row put their icon and title at the same left edge, both in the phone layout and in the
 * wide (unfolded) layout beside its submenu. Pass -e wide true or false to say which layout the
 * emulator's size must produce, so a run can't pass on the other one.
 */
public final class MoreMenuValidation extends Instrumentation {
    private Bundle arguments;
    private MainActivity activity;
    private int assertions;
    private final ArrayList<String> screenshots = new ArrayList<>();

    @Override public void onCreate(Bundle args) { arguments = args == null ? new Bundle() : args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            admit();
            require(arguments.containsKey("wide"), "Pass -e wide true or false");
            boolean wide = Boolean.parseBoolean(arguments.getString("wide"));
            require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false).putBoolean("developer_options", true)
                    .putString("theme", arguments.getString("theme", "dark")).putString("more_section", wide ? "App" : "").commit(), "Fixture settings failed");
            seed();
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.service() != null, "App did not launch"); settle();
            main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "more")); settle();
            Object more = more();
            require((Boolean) field(more, "wide") == wide, "Expected the " + (wide ? "wide" : "phone") + " More layout at this size");
            screenshot(wide ? "wide-menu" : "phone-menu");
            aligned((LinearLayout) field(more, "sectionMenu"));
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

    /** Every row in the menu card, Back up & restore included, shares one icon edge and one title edge. */
    private void aligned(LinearLayout menu) {
        AtomicReference<String> problem = new AtomicReference<>();
        AtomicReference<Integer> rows = new AtomicReference<>(0);
        main(() -> {
            Integer iconLeft = null, titleLeft = null; String first = null; int count = 0;
            for (int i = 0; i < menu.getChildCount(); i++) {
                View child = menu.getChildAt(i);
                if (!(child instanceof LinearLayout) || child.getVisibility() != View.VISIBLE || ((LinearLayout) child).getChildCount() < 2) continue;
                View icon = ((LinearLayout) child).getChildAt(0);
                TextView title = firstText(((LinearLayout) child).getChildAt(1));
                if (title == null) continue;
                int[] at = new int[2]; icon.getLocationOnScreen(at); int ix = at[0]; title.getLocationOnScreen(at); int tx = at[0];
                String label = String.valueOf(title.getText());
                if (iconLeft == null) { iconLeft = ix; titleLeft = tx; first = label; }
                else if ((Math.abs(ix - iconLeft) > 1 || Math.abs(tx - titleLeft) > 1) && problem.get() == null)
                    problem.set(label + " starts at icon x=" + ix + ", title x=" + tx + " but " + first + " at icon x=" + iconLeft + ", title x=" + titleLeft);
                count++;
            }
            rows.set(count);
        });
        require(rows.get() >= 4, "Menu card shows " + rows.get() + " rows");
        require(problem.get() == null, String.valueOf(problem.get()));
    }
    private TextView firstText(View view) {
        if (view instanceof TextView && ((TextView) view).getText().length() > 0) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { TextView found = firstText(((ViewGroup) view).getChildAt(i)); if (found != null) return found; }
        return null;
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
            ScooterProfiles.Profile profile = profiles.create("Synthetic menu fixture", "A1B2C3D4E5F6", 0);
            profile.serial = "X2538-SYNTHETIC"; profile.mac = "C0:FF:EE:12:34:56"; profile.lastConnected = System.currentTimeMillis() - 60000; profiles.update(profile);
        });
        prefs("onboarding").edit().putBoolean("onboarding_done", true).commit();
    }
    private void screenshot(String label) throws Exception {
        File dir = new File(getTargetContext().getExternalFilesDir(null), "more-menu-validation"); require(dir.isDirectory() || dir.mkdirs(), "Screenshot directory unavailable");
        File file = new File(dir, arguments.getString("theme", "dark") + "-" + label + ".png");
        android.graphics.Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "Screenshot unavailable");
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); } finally { bitmap.recycle(); }
        screenshots.add(file.getAbsolutePath());
    }
    private Object more() { Object screen = field(activity, "screen"); require(screen != null && screen.getClass().getSimpleName().equals("MoreScreen"), "More screen is not active"); return screen; }
    private SharedPreferences settings() { return prefs("app_settings"); }
    private SharedPreferences prefs(String name) { return getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(300); waitForIdleSync(); }
    private void main(Runnable task) {
        AtomicReference<Throwable> error = new AtomicReference<>();
        runOnMainSync(() -> { try { task.run(); } catch (Throwable failure) { error.set(failure); } });
        if (error.get() != null) throw new IllegalStateException(error.get());
    }
    private void await(BooleanSupplier condition, String message) {
        long end = SystemClock.uptimeMillis() + 10000;
        while (!condition.getAsBoolean() && SystemClock.uptimeMillis() < end) SystemClock.sleep(50);
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
