package local.navee.offlinelock.tests;

import android.Manifest;
import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGatt;
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
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import local.navee.offlinelock.BleController;
import local.navee.offlinelock.ConnectionEventLog;
import local.navee.offlinelock.ScooterCommands;
import local.navee.offlinelock.ScooterProfiles;
import local.navee.offlinelock.ScooterService;
import local.navee.offlinelock.Telemetry;
import local.navee.offlinelock.TelemetryHistory;

/** Disposable emulator only: production failure/retry and hardware binding, with Bluetooth disabled. */
public final class FirstConnectionValidation extends Instrumentation {
    private BleController ble;
    private ScooterProfiles profiles;
    private ScooterProfiles.Profile profile;
    private int checks;
    private boolean admitted;
    private final Map<String,String> observedReadings = new LinkedHashMap<>();
    private final Map<String,Integer> observedFeatures = new LinkedHashMap<>();
    private int unverifiedChanges;
    private ScooterService historyObserver;
    private TelemetryHistory testHistory;

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
            hardwareBinding();
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", error.toString()); error.printStackTrace();
        } finally {
            result.putInt("assertions", checks);
            result.putBoolean("physical_device_tested", false);
            if (admitted) try { main(() -> { if (ble != null) { require(field(ble,"gatt") == null && field(ble,"scanner") == null, "A real transport was started"); ble.destroy(); } if(testHistory!=null)testHistory.close(); }); }
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
            observedReadings.clear(); observedFeatures.clear(); unverifiedChanges = 0;
            if(testHistory!=null){
                // Exercise the actual service observer without starting its lifecycle or radio.
                historyObserver=new ScooterService();historyObserver.ble=ble;historyObserver.profiles=profiles;
                historyObserver.settings=prefs("app_settings");historyObserver.history=testHistory;
                historyCache().put(profile.id,TelemetryHistory.Snapshot.empty());
            }
            ble.setListener(this::observeBinding);
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

    private void hardwareBinding() {
        final ScooterProfiles.Profile[] saved = new ScooterProfiles.Profile[1];
        main(() -> {
            testHistory = new TelemetryHistory(getTargetContext());
            saved[0] = profiles.create("Saved UT5 MAX", profile.identity, 0);
            saved[0].mac = "C0:FF:EE:00:00:A0"; saved[0].serial = "Q2538ZZ9US1111111";
            saved[0].lastConnected = 1234; saved[0].advertisedPid = "2538"; saved[0].advertisedPidWall = 1234;
            profiles.update(saved[0]); profiles.select(profile.id);
            require(profiles.hardwareMatch(null, "c0:ff:ee:00:00:a0", "") == saved[0], "Saved scan address match is case sensitive");
            require(profiles.hardwareMatch(profile.id, "", "q2538zz9us1111111") == saved[0], "Serial match missed rotated address");
            require(profiles.hardwareMatch(saved[0].id, saved[0].mac, saved[0].serial) == null, "The same profile conflicts with itself");
            require(profiles.hardwareMatch(profile.id, "", "") == null, "Owner identity alone was treated as hardware");
        });
        // A known MAC is enough, even if its reported serial differs. A rotated MAC must
        // still be caught by the serial. Both packet orders reach the production binding.
        rejectedHardware(saved[0], saved[0].mac.toLowerCase(Locale.ROOT), "Q2443ZZ9US2222222", false, false);
        rejectedHardware(saved[0], "C0:FF:EE:00:00:A1", saved[0].serial, true, false);
        rejectedHardware(saved[0], saved[0].mac, saved[0].serial, false, false);
        rejectedHardware(saved[0], saved[0].mac, "Q2538ZZ9US7777777", true, true);
        AtomicReference<TelemetryHistory.Snapshot> stored = new AtomicReference<>();
        testHistory.load(profile.id,stored::set);
        long until = SystemClock.uptimeMillis()+5000;
        while(stored.get()==null&&SystemClock.uptimeMillis()<until)SystemClock.sleep(25);
        require(stored.get()!=null&&stored.get().readings.isEmpty()&&stored.get().features.isEmpty(), "Rejected scooter data was persisted by the real service history observer");

        fresh(false);
        main(() -> {
            String before = prefs("profiles").getString("items", "");
            syntheticConnection("C0:FF:EE:00:00:B0");
            receiveEarlyReadings();
            receive(0x74, "Q2443ZZ9US2222222".getBytes(StandardCharsets.US_ASCII));
            require(profile.serial.isEmpty() && profile.mac.isEmpty() && before.equals(prefs("profiles").getString("items", "")),
                    "Serial-only response changed saved hardware before a verified binding");
            receive(0x70, new byte[43]);
            require(ble.ready() && profile.mac.equals("C0:FF:EE:00:00:B0") && profile.serial.equals("Q2443ZZ9US2222222"),
                    "A distinct scooter sharing the account owner identity was rejected");
            require(saved[0].mac.equals("C0:FF:EE:00:00:A0") && saved[0].serial.equals("Q2538ZZ9US1111111") && saved[0].lastConnected == 1234,
                    "A successful new binding changed the other scooter");
            require(observedReadings.get("scooter_serial").equals(profile.serial) && !observedFeatures.isEmpty(), "Verified serial and buffered settings did not reach the listener");
            require(!ble.telemetry.statuses().containsKey(0x72) && ble.motionSampleMs() == 0, "Pre-validation battery or motion was replayed as verified data");
            receive(0x72, new byte[36]);
            require(ble.telemetry.statuses().containsKey(0x72) && observedReadings.containsKey("response_72"), "Fresh readings after valid binding were not published");
            require(!historyCache().get(profile.id).readings.isEmpty() && !historyCache().get(profile.id).features.isEmpty(), "Verified readings and settings did not reach the real service cache");
            ble.disconnect();
        });
        fresh(true);
        main(() -> {
            syntheticConnection(profile.mac);
            receiveEarlyReadings(); receive(0x70, new byte[43]);
            require(observedReadings.isEmpty() && observedFeatures.isEmpty(), "Status-first handshake leaked saved-scooter observations");
            receive(0x74, profile.serial.getBytes(StandardCharsets.US_ASCII));
            require(ble.ready(), "An existing scooter could not reconnect to its own profile");
            require(!observedFeatures.isEmpty() && observedReadings.get("scooter_serial").equals(profile.serial), "Status-first binding lost the buffered verified readings");
            ble.disconnect();
        });
        fresh(true);
        main(() -> {
            syntheticConnection("C0:FF:EE:00:00:B1");
            receive(0x74, profile.serial.getBytes(StandardCharsets.US_ASCII)); receive(0x70, new byte[43]);
            require(ble.ready() && profile.mac.equals("C0:FF:EE:00:00:B1"), "A profile's own serial could not update its rotated address");
            ble.disconnect();
        });
    }

    private void rejectedHardware(ScooterProfiles.Profile saved, String address, String serial, boolean statusFirst, boolean known) {
        fresh(known);
        main(() -> {
            String before = prefs("profiles").getString("items", "");
            syntheticConnection(address);
            receiveEarlyReadings();
            if (statusFirst) receive(0x70, new byte[43]);
            require(observedReadings.isEmpty() && observedFeatures.isEmpty(), "Pre-serial status or battery escaped into a consumer cache");
            receive(0x74, serial.getBytes(StandardCharsets.US_ASCII));
            if (!statusFirst) receive(0x70, new byte[43]);
            require(ble.state() == BleController.State.DISCONNECTED && !ble.connected() && !ble.ready(), "Another saved scooter was accepted");
            require(field(ble,"gatt") == null && field(ble,"writer") == null && !((Boolean) field(ble,"wanted")), "Rejected hardware retained a transport or reconnect request");
            require(ble.currentTransportSerial().isEmpty() && noLiveReadings(), "Rejected hardware left live identity or telemetry behind");
            require(unverifiedChanges > 1 && observedReadings.isEmpty() && observedFeatures.isEmpty(), "Rejected hardware was exposed to a listener before rejection");
            require(!hasRetryEvent(), "Another saved scooter was automatically retried");
            require(ble.message().contains(saved.name) && ble.message().contains("My scooters") && ble.message().contains("scan"), "Hardware rejection does not identify the saved scooter and recovery actions");
            require(before.equals(prefs("profiles").getString("items", "")), "Rejected hardware changed persisted profiles");
            // A later save serializes all live Profile objects, so also prove no in-memory
            // change can leak into storage through a rename/import/another profile update.
            profiles.save();
            require(before.equals(prefs("profiles").getString("items", "")), "Rejected hardware changed an in-memory profile");
        });
    }

    private void receiveEarlyReadings() {
        receive(0x72, new byte[36]); receive(0x73, new byte[40]);
        receive(0x90, new byte[8]); receive(0x91, new byte[]{0, 1, 12});
        require(noLiveReadings() && observedReadings.isEmpty() && observedFeatures.isEmpty(), "Early authenticated readings escaped before hardware validation");
    }

    /** Checks every handshake callback and also runs the real service's history observer. */
    private void observeBinding() {
        boolean unverified=ble.connected()&&ble.state()!=BleController.State.READY;
        if (unverified) {
            unverifiedChanges++;
            require(noLiveReadings(), "A listener saw telemetry before hardware validation");
            require(ble.statusAge() == Long.MAX_VALUE && ble.motionSampleMs() == 0 && ble.speedRaw() == -1 && ble.drivingStatus() == -1,
                    "A listener saw status or motion before hardware validation");
            for (ScooterCommands.Feature feature : ScooterCommands.catalog()) require(ble.featureValue(feature.key) == null,
                    "A listener saw an unverified setting: " + feature.key);
        }
        if(historyObserver!=null){
            historyObserver.displayFeatures();
            if(unverified){TelemetryHistory.Snapshot cache=historyCache().get(profile.id);
                require(cache.readings.isEmpty()&&cache.features.isEmpty(),"The real service cached readings or settings before hardware validation");}
        }
        if (!ble.connected()) return;
        for (Telemetry.Metric metric : ble.telemetry.metrics()) if (metric.value != null && metric.updatedMs >= 0)
            observedReadings.put(metric.key, metric.value);
        if (ble.statusAge() < 60000) for (ScooterCommands.Feature feature : ScooterCommands.catalog()) {
            Integer value = ble.featureValue(feature.key); if (value != null) observedFeatures.put(feature.key, value);
        }
    }

    @SuppressWarnings("unchecked") private Map<String,TelemetryHistory.Snapshot> historyCache(){return (Map<String,TelemetryHistory.Snapshot>)field(historyObserver,"displayCache");}

    private boolean noLiveReadings() {
        for (Telemetry.Metric metric : ble.telemetry.metrics()) if (metric.value != null || metric.numericValue != null || metric.updatedMs >= 0) return false;
        return ble.telemetry.statuses().isEmpty();
    }

    /** A GATT object with no service, writer or radio connection; only getDevice() is used. */
    private void syntheticConnection(String address) {
        try {
            Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
            Field unsafe = unsafeType.getDeclaredField("theUnsafe"); unsafe.setAccessible(true);
            BluetoothGatt fake = (BluetoothGatt) unsafeType.getMethod("allocateInstance", Class.class).invoke(unsafe.get(null), BluetoothGatt.class);
            set(fake, "mDevice", getTargetContext().getSystemService(BluetoothManager.class).getAdapter().getRemoteDevice(address.toUpperCase(Locale.ROOT)));
            set(fake, "mStateLock", new Object());
            require(field(fake,"mService") == null && field(ble,"writer") == null, "Synthetic GATT unexpectedly has a transport");
            set(ble,"gatt",fake); set(ble,"authenticated",false);
            receive(0x30, new byte[0]);
            require((Boolean) field(ble,"authenticated"), "Synthetic owner authentication did not reach production binding");
        } catch (Exception error) { throw new IllegalStateException("Synthetic GATT unavailable; relax hidden_api_policy on the disposable emulator", error); }
    }

    private void receive(int command, byte[] payload) {
        byte[] packet = new byte[payload.length + 9]; packet[0] = 0x55; packet[1] = (byte) 0xaa;
        packet[3] = (byte) command; packet[4] = (byte) (payload.length + 1);
        System.arraycopy(payload, 0, packet, 6, payload.length); int sum = 0;
        for (int i = 0; i < packet.length - 3; i++) sum += packet[i] & 255;
        packet[packet.length - 3] = (byte) sum; packet[packet.length - 2] = (byte) 0xfe; packet[packet.length - 1] = (byte) 0xfd;
        try { Method method = BleController.class.getDeclaredMethod("incoming", byte[].class); method.setAccessible(true); method.invoke(ble, packet); }
        catch (Exception error) { throw new IllegalStateException(error); }
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
