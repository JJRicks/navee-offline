package local.navee.offlinelock.tests;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowInsetsController;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import local.navee.offlinelock.BleController;
import local.navee.offlinelock.MainActivity;
import local.navee.offlinelock.OnboardingScreen;
import local.navee.offlinelock.ScooterService;
import local.navee.offlinelock.Ui;

/** Fresh-emulator-only launch checks. Never grants permissions or contacts a scooter. */
public final class LaunchValidation extends Instrumentation {
    private Bundle arguments;
    private boolean admitted;
    private final List<Activity> activities = new ArrayList<>();
    private final List<String> checks = new ArrayList<>();
    private static final String[] PREFS = {"app_settings", "profiles", "scooter", "pending_documents", "onboarding"};
    private static final int BAR_MASK = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;

    @Override public void onCreate(Bundle arguments) {
        this.arguments = arguments == null ? new Bundle() : arguments;
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            requireFreshEmulator();
            String mode = arguments.getString("mode", arguments.getString("permissions", "denied"));
            require(mode.equals("denied") || mode.equals("granted"), "Use mode denied or granted");
            verifyPermissions(mode);
            admitted = true;
            SharedPreferences settings = getTargetContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE);
            require(settings.edit().putBoolean("auto_connect", false).putBoolean("background_connection", false).commit(),
                    "Could not set emulator-only connection policy");

            MainActivity activity = launch();
            assertAppearance(activity, "default dark launch");
            MainActivity fresh = activity;
            main(() -> require(field(fresh, "screen") instanceof OnboardingScreen, "Fresh launch did not open first-run onboarding"));
            OnboardingScreen.markDone(getTargetContext());
            main(fresh::rebuild);
            assertAppearance(activity, "after onboarding");
            check("Fresh launch opens first-run onboarding; finishing it shows the tabs");
            check("Fresh launch without a saved scooter; Bluetooth permissions " + mode);
            if (mode.equals("granted")) {
                MainActivity first = activity;
                await(() -> field(first, "scooterService") != null, 5000, "Permitted local workspace did not initialize");
            }
            for (String page : new String[]{"home", "controls", "stats", "rides", "more"}) {
                MainActivity current = activity;
                main(() -> select(current, page));
                assertAppearance(current, "tab " + page);
                main(() -> require(page.equals(field(current, "selectedPage")), "Wrong selected tab"));
            }
            check("All five tabs opened without control actions");
            finishActivity(activity);

            for (String theme : new String[]{"dark", "light", "system"}) {
                require(settings.edit().putString("theme", theme).commit(), "Could not select emulator theme");
                activity = launch();
                assertAppearance(activity, theme + " cold launch");
                MainActivity current = activity;
                main(() -> { current.rebuild(); current.rebuild(); current.rebuild(); });
                assertAppearance(current, theme + " repeated rebuild");
                check(theme + " launch and repeated rebuild preserve system-bar appearance");
                finishActivity(activity);
            }

            // Preference callbacks queue rebuilds. Only the last attached root may style the bars.
            activity = launch();
            MainActivity current = activity;
            main(() -> {
                settings.edit().putString("theme", "dark").commit(); current.rebuild();
                settings.edit().putString("theme", "light").commit(); current.rebuild();
                settings.edit().putString("theme", "dark").commit(); current.rebuild();
            });
            assertAppearance(current, "rapid theme replacement");
            check("Rapid theme replacement ends with the current root's dark appearance");

            ActivityMonitor recreation = addMonitor(MainActivity.class.getName(), null, false);
            try {
                main(current::recreate);
                Activity replacement = waitForMonitorWithTimeout(recreation, 6000);
                require(replacement instanceof MainActivity && replacement != current, "Activity recreation did not produce a new screen");
                activity = (MainActivity) replacement;
                activities.add(activity);
                assertAppearance(activity, "activity recreation");
                check("Activity recreation opens a new attached screen");
            } finally { removeMonitor(recreation); }

            MainActivity finalActivity = activity;
            main(() -> assertNoScooterWork(finalActivity));
            requireNoSavedRides();
            result.putString("result", "PASS");
            result.putString("mode", mode);
            result.putString("device", Build.MODEL + " / Android " + Build.VERSION.RELEASE);
            result.putStringArrayList("checks", new ArrayList<>(checks));
            result.putInt("check_count", checks.size());
            result.putInt("scooter_mutations", 0);
            result.putBoolean("permissions_granted_by_runner", false);
            cleanup();
            finish(0, result);
        } catch (Throwable failure) {
            result.putString("result", "FAIL");
            result.putString("error", failure.getClass().getSimpleName() + ": " + failure.getMessage());
            result.putStringArrayList("completed_checks", new ArrayList<>(checks));
            try { cleanup(); } catch (Throwable cleanupFailure) {
                result.putString("cleanup_error", cleanupFailure.getClass().getSimpleName());
            }
            finish(1, result);
        }
    }

    private void requireFreshEmulator() {
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
        require(hardware.contains("ranchu") || hardware.contains("goldfish")
                || Build.PRODUCT.startsWith("sdk_gphone") || Build.MODEL.startsWith("sdk_gphone"),
                "Launch validation is restricted to an emulator; owner phones are refused");
        require(Build.VERSION.SDK_INT >= 30, "System-bar launch regression requires Android 11 or newer");
        for (String preference : PREFS) {
            require(getTargetContext().getSharedPreferences(preference, Context.MODE_PRIVATE).getAll().isEmpty(),
                    "Fresh empty app data required; existing preferences are refused");
        }
        File[] files = getTargetContext().getFilesDir().listFiles();
        require(files != null && files.length == 0, "Fresh empty app data required; existing files are refused");
    }

    private void verifyPermissions(String mode) {
        boolean scan = getTargetContext().checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        boolean connect = getTargetContext().checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        if (mode.equals("denied")) require(!scan && !connect, "Denied mode requires both Nearby devices permissions denied");
        else {
            require(scan && connect, "Granted mode requires permissions granted externally before this test");
            BluetoothManager manager = getTargetContext().getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            require(adapter == null || !adapter.isEnabled(), "Granted mode requires Bluetooth off or unavailable");
        }
    }

    private MainActivity launch() {
        Activity activity = startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        require(activity instanceof MainActivity, "Unexpected launch activity");
        activities.add(activity);
        return (MainActivity) activity;
    }

    private void assertAppearance(MainActivity activity, String phase) {
        await(() -> {
            View root = (View) field(activity, "root");
            Ui design = (Ui) field(activity, "design");
            if (root == null || design == null || !root.isAttachedToWindow() || root.getWidth() == 0 || root.getHeight() == 0) return false;
            WindowInsetsController controller = root.getWindowInsetsController();
            if (controller == null) return false;
            String theme = getTargetContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE).getString("theme", "dark");
            boolean dark = theme.equals("dark") || (theme.equals("system")
                    && (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
            require(design.dark == dark, phase + ": UI theme does not follow preference");
            return (controller.getSystemBarsAppearance() & BAR_MASK) == (dark ? 0 : BAR_MASK);
        }, 4000, phase + ": attached root or system-bar appearance unavailable");
        main(() -> assertNoScooterWork(activity));
    }

    private void assertNoScooterWork(MainActivity activity) {
        ScooterService service = (ScooterService) field(activity, "scooterService");
        if (service == null) return;
        require(service.profiles.all().isEmpty() && service.profiles.active() == null, "Unexpected owner profile");
        require(!service.isRecording(), "Unexpected recording");
        require(service.ble.state() == BleController.State.DISCONNECTED && !service.ble.connected(), "Unexpected Bluetooth connection work");
        require(((Number) field(service.ble, "mutationSequence")).longValue() == 0, "Unexpected scooter mutation");
        require(service.ble.commandResults().isEmpty(), "Unexpected scooter command result");
    }

    private void requireNoSavedRides() {
        File rides = new File(getTargetContext().getFilesDir(), "rides");
        File[] files = rides.listFiles();
        require(!rides.exists() || (files != null && files.length == 0), "Launch test created a saved ride");
    }

    private void finishActivity(Activity activity) {
        main(() -> { if (!activity.isFinishing() && !activity.isDestroyed()) activity.finish(); });
        await(activity::isDestroyed, 4000, "Launch activity did not finish");
    }

    private void cleanup() {
        if (!admitted) return;
        for (Activity activity : activities) {
            if (!activity.isDestroyed()) finishActivity(activity);
        }
        main(() -> {
            ScooterService.setUiVisible(false);
            getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class));
        });
        waitForIdleSync();
        for (String preference : PREFS) {
            require(getTargetContext().getSharedPreferences(preference, Context.MODE_PRIVATE).edit().clear().commit(),
                    "Could not restore fresh emulator preferences");
        }
        // Leave the empty rides directory; this runner never deletes app files.
        admitted = false;
    }

    private void await(BooleanSupplier condition, long milliseconds, String error) {
        long deadline = SystemClock.elapsedRealtime() + milliseconds;
        while (SystemClock.elapsedRealtime() < deadline) {
            AtomicReference<Boolean> done = new AtomicReference<>(false);
            main(() -> done.set(condition.getAsBoolean()));
            if (Boolean.TRUE.equals(done.get())) return;
            SystemClock.sleep(40);
        }
        throw new IllegalStateException(error);
    }

    private void main(Runnable action) {
        AtomicReference<Throwable> problem = new AtomicReference<>();
        runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { problem.set(error); } });
        if (problem.get() != null) throw new IllegalStateException(problem.get().getClass().getSimpleName()
                + ": " + problem.get().getMessage(), problem.get());
    }

    private void select(MainActivity activity, String page) {
        try {
            Method method = MainActivity.class.getDeclaredMethod("select", String.class);
            method.setAccessible(true); method.invoke(activity, page);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Could not select tab", error); }
    }

    private Object field(Object object, String name) {
        try {
            Field field = object.getClass().getDeclaredField(name);
            field.setAccessible(true); return field.get(object);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Could not inspect launch state", error); }
    }

    private void check(String text) { checks.add(text); Bundle status = new Bundle(); status.putString("check", text); sendStatus(0, status); }
    private static void require(boolean value, String error) { if (!value) throw new IllegalStateException(error); }
}
