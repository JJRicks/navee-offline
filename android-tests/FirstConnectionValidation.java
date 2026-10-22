package local.navee.offlinelock.tests;

import android.Manifest;
import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import local.navee.offlinelock.BleController;
import local.navee.offlinelock.ConnectionEventLog;
import local.navee.offlinelock.ScooterProfiles;

/** Disposable emulator only: production failure/retry transitions, with Bluetooth disabled. */
public final class FirstConnectionValidation extends Instrumentation {
    private BleController ble;
    private ScooterProfiles profiles;
    private ScooterProfiles.Profile profile;
    private int checks;
    private boolean admitted;

    @Override public void onCreate(Bundle args) { start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            admit(); admitted = true;
            main(() -> {
                prefs("app_settings").edit().putBoolean("auto_connect", false)
                        .putBoolean("background_connection", false).commit();
                profiles = new ScooterProfiles(getTargetContext());
                profile = profiles.create("Synthetic unpaired scooter", "000000000000", 0);
            });
            firstConnection(); absentProfile(); rejectedIdentity(); savedScooterRetry(); backgroundCancellation();
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", error.toString()); error.printStackTrace();
        } finally {
            result.putInt("assertions", checks);
            result.putBoolean("physical_device_tested", false);
            if (admitted) try { main(() -> { if (ble != null) { require(field(ble,"gatt") == null && field(ble,"scanner") == null, "A real transport was started"); ble.destroy(); } }); }
            catch (Throwable error) { result.putString("result", "FAIL"); result.putString("cleanup_error", error.toString()); }
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    private void admit() {
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
        require(hardware.contains("ranchu") || hardware.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "Physical device refused");
        require(getTargetContext().checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED,
                "Grant emulator Nearby devices permission first");
        BluetoothAdapter adapter = getTargetContext().getSystemService(BluetoothManager.class).getAdapter();
        require(adapter != null && !adapter.isEnabled(), "Emulator Bluetooth must stay off");
        for (String name : new String[]{"profiles", "scooter", "app_settings", "control_evidence", "onboarding"})
            require(prefs(name).getAll().isEmpty(), "Existing preferences refused");
        File[] files = getTargetContext().getFilesDir().listFiles();
        require(files != null && files.length == 0 && getTargetContext().databaseList().length == 0, "Existing app data refused");
    }

    private void fresh(boolean known) {
        main(() -> {
            if (ble != null) ble.destroy();
            profile.mac = known ? "C0:FF:EE:00:00:01" : "";
            profile.serial = known ? "Q2538ZZ9US7777777" : "";
            profiles.update(profile);
            ble = new BleController(getTargetContext(), profiles);
            ble.visibility(true, false);
            set(ble, "profile", profile); set(ble, "wanted", true);
            set(ble, "state", BleController.State.AUTHENTICATING);
        });
    }

    private void firstConnection() {
        fresh(false);
        main(() -> {
            fail("Timed out", true, ConnectionEventLog.Reason.CONNECT_TIMEOUT);
            require(ble.state() == BleController.State.DISCONNECTED, "Unsaved scooter was left waiting for an impossible retry");
            require("Timed out".equals(ble.message()), "First failure falsely advertised a retry");
            require(((Number) field(ble,"retries")).intValue() == 0, "No-address failure consumed a retry");
            require(!hasRetryEvent(), "No-address failure scheduled a retry");
            require(profile.mac.isEmpty() && profile.serial.isEmpty() && profile.lastConnected == 0,
                    "Unverified scooter identifiers were saved");
            require(ble.recoverableConnectionLoss(), "Transport failure classification changed");
        });
        SystemClock.sleep(1150);
        main(() -> require(ble.state() == BleController.State.DISCONNECTED && "Timed out".equals(ble.message()),
                "No-address timeout changed again without an explicit retry"));
    }

    private void absentProfile() {
        fresh(false);
        main(() -> {
            set(ble,"profile",null); fail("Connection failed", true, ConnectionEventLog.Reason.CONNECT_FAILED);
            require(ble.state() == BleController.State.DISCONNECTED && !hasRetryEvent(), "Missing profile still claimed a reconnect");
        });
    }

    private void rejectedIdentity() {
        fresh(true);
        main(() -> {
            fail(BleController.OWNER_REJECTED, false, ConnectionEventLog.Reason.AUTHENTICATION_FAILED);
            require(ble.state() == BleController.State.DISCONNECTED && !hasRetryEvent(), "Rejected identity was retried");
            require(BleController.OWNER_REJECTED.equals(ble.message()), "Identity rejection message changed");
        });
    }

    private void savedScooterRetry() {
        fresh(true);
        main(() -> {
            fail("Timed out", true, ConnectionEventLog.Reason.CONNECT_TIMEOUT);
            require(ble.state() == BleController.State.WAITING && ble.message().contains("retrying"), "Saved scooter lost its reconnect behavior");
            require(((Number) field(ble,"retries")).intValue() == 1 && hasRetryEvent(), "Saved scooter retry was not scheduled");
            ble.disconnect();
        });
        SystemClock.sleep(1150);
        main(() -> require(ble.state() == BleController.State.DISCONNECTED && "Disconnected".equals(ble.message()),
                "Explicit disconnect did not cancel the saved-scooter retry"));
    }

    private void backgroundCancellation() {
        fresh(true);
        main(() -> {
            fail("Timed out", true, ConnectionEventLog.Reason.CONNECT_TIMEOUT);
            ble.visibility(false, false);
            require(ble.state() == BleController.State.DISCONNECTED, "Background pause retained a reconnect");
        });
        SystemClock.sleep(1150);
        main(() -> require(ble.state() == BleController.State.DISCONNECTED && "Paused in background".equals(ble.message()),
                "Background retry escaped cancellation"));
    }

    private boolean hasRetryEvent() { for (String line : ble.events()) if (line.contains("Reconnect in ")) return true; return false; }
    private void fail(String message, boolean retry, ConnectionEventLog.Reason reason) {
        try { Method m = BleController.class.getDeclaredMethod("failed", String.class, boolean.class, ConnectionEventLog.Reason.class, int.class);
            m.setAccessible(true); m.invoke(ble, message, retry, reason, -1);
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    private SharedPreferences prefs(String name) { return getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE); }
    private void require(boolean yes, String message) { checks++; if (!yes) throw new AssertionError(message); }
    private void main(Runnable action) {
        AtomicReference<Throwable> error = new AtomicReference<>();
        runOnMainSync(() -> { try { action.run(); } catch (Throwable failure) { error.set(failure); } });
        if (error.get() != null) throw new AssertionError(error.get());
    }
    private static Object field(Object object, String name) { try { Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object); } catch (Exception error) { throw new IllegalStateException(error); } }
    private static void set(Object object, String name, Object value) { try { Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); f.set(object,value); } catch (Exception error) { throw new IllegalStateException(error); } }
}
