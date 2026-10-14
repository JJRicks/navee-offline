package local.navee.offlinelock.tests;

import android.Manifest;
import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import local.navee.offlinelock.ChargeSessionStore;
import local.navee.offlinelock.DataMaintenance;
import local.navee.offlinelock.ScooterProfiles;
import local.navee.offlinelock.ScooterService;
import local.navee.offlinelock.TelemetryHistory;

/** Disposable fresh emulator only. Confirms a portable backup cannot race service writes. */
public final class AppDataMaintenanceValidation extends Instrumentation {
    private ScooterService service;
    private boolean bound;
    private int assertions;
    private final CountDownLatch connected = new CountDownLatch(1);
    private final CountDownLatch releaseWork = new CountDownLatch(1);
    private final CountDownLatch releaseHistory = new CountDownLatch(1);
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((ScooterService.LocalBinder) binder).service(); connected.countDown();
        }
        @Override public void onServiceDisconnected(ComponentName name) { }
    };

    @Override public void onCreate(Bundle args) { start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            admit();
            File transfer = new File(getTargetContext().getNoBackupFilesDir(), "app-data-transfer");
            File pending = new File(transfer, "stage-pending-fixture");
            require(pending.mkdirs(), "Could not stage a startup recovery marker");
            main(() -> require(!DataMaintenance.readyForStartup(getTargetContext()) && !DataMaintenance.recovered(),
                    "Pending recovery was admitted through the clean startup path"));
            require(pending.delete() && transfer.delete(), "Could not remove startup fixture marker");
            require(prefs("app_settings").edit().putBoolean("auto_connect", false)
                    .putBoolean("background_connection", false).commit(), "Fixture settings failed");
            main(() -> {
                ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
                profiles.create("Synthetic maintenance fixture", "000000000000", 0);
                bound = getTargetContext().bindService(new Intent(getTargetContext(), ScooterService.class),
                        connection, Context.BIND_AUTO_CREATE);
                require(bound, "Could not bind the service");
            });
            require(connected.await(10, TimeUnit.SECONDS), "Service did not connect");
            require(DataMaintenance.recovered(), "Clean service startup did not finish the recovery gate");
            refusals();
            drainAndFreeze();
            result.putString("result", "PASS");
        } catch (Throwable failure) {
            result.putString("result", "FAIL");
            result.putString("error", failure.getClass().getSimpleName() + ": " + failure.getMessage());
        } finally {
            releaseWork.countDown();
            releaseHistory.countDown();
            main(() -> {
                if (bound) getTargetContext().unbindService(connection);
                getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class));
            });
            result.putInt("assertions", assertions);
            result.putString("fixture", "Fresh emulator; synthetic profiles; Bluetooth disabled; no real recording or owner identity");
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    private void admit() {
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
        require(hardware.contains("ranchu") || hardware.contains("goldfish")
                || Build.PRODUCT.startsWith("sdk_gphone"), "Refusing a physical phone");
        for (String name : new String[]{"app_settings", "profiles", "scooter", "pending_documents", "control_evidence", "owner_capture_cleanup"})
            require(prefs(name).getAll().isEmpty(), "Refusing existing private preferences");
        File[] files = getTargetContext().getFilesDir().listFiles();
        require(files != null && files.length == 0 && getTargetContext().databaseList().length == 0,
                "Refusing existing private data");
        for (String permission : new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT})
            require(getTargetContext().checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED,
                    "Grant Nearby permission before validation");
        BluetoothManager manager = getTargetContext().getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        require(adapter == null || !adapter.isEnabled(), "Bluetooth must be disabled");
        require(!DataMaintenance.active(), "Maintenance was already active");
    }

    private void refusals() {
        main(() -> {
            require(service.dataMaintenanceUnavailable() == null, "Idle service refused maintenance");
            String profile = service.profiles.active().id;
            try {
                service.rides.start(profile, "Synthetic unfinished ride", System.currentTimeMillis(), SystemClock.elapsedRealtime());
                refused("Active ride admitted");
                service.rides.stop(System.currentTimeMillis(), SystemClock.elapsedRealtime());
                ChargeSessionStore charges = (ChargeSessionStore) field(service, "chargeStore");
                charges.start(profile, ChargeSessionStore.Charger.STOCK, System.currentTimeMillis(), SystemClock.elapsedRealtime());
                refused("Active charge admitted");
                charges.stop(ChargeSessionStore.Stop.STOPPED);
                setField(service, "phoneSavingCount", 1); refused("Phone finalization admitted");
                setField(service, "phoneSavingCount", 0);
                setField(service, "chargeExporting", true); refused("Charge export admitted");
                setField(service, "chargeExporting", false);
                require(prefs("pending_documents").edit().putString("path", "synthetic").commit(), "Could not stage synthetic export");
                refused("Pending document admitted"); prefs("pending_documents").edit().clear().commit();
                require(prefs("owner_capture_cleanup").edit().putBoolean("pending", true).commit(), "Could not stage cleanup marker");
                refused("Pending capture cleanup admitted"); prefs("owner_capture_cleanup").edit().clear().commit();
                File pending = new File(getTargetContext().getFilesDir(), "pending-owner-import.json");
                try (FileOutputStream out = new FileOutputStream(pending)) { out.write(new byte[]{'{','}'}); }
                refused("Pending owner import admitted"); require(pending.delete(), "Could not remove fixture import");
            } catch (Exception failure) { throw new IllegalStateException(failure); }
            require(service.dataMaintenanceUnavailable() == null, "Finished operations remained blocked");
        });
    }

    private void refused(String message) {
        require(service.dataMaintenanceUnavailable() != null, message);
        AtomicReference<String> refusal = new AtomicReference<>();
        service.prepareDataMaintenance(refusal::set);
        require(refusal.get() != null && !DataMaintenance.active(), "Refusal froze or started maintenance");
        require(!Boolean.TRUE.equals(field(service, "destroyed")), "Refusal destroyed the usable service");
    }

    private void drainAndFreeze() throws Exception {
        CountDownLatch queued = new CountDownLatch(1), done = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();
        File sentinel = new File(getTargetContext().getFilesDir(), "maintenance-write-sentinel");
        ExecutorService worker = (ExecutorService) field(service, "chargeWorker");
        worker.execute(() -> {
            queued.countDown();
            try {
                releaseWork.await();
                try (FileOutputStream out = new FileOutputStream(sentinel)) { out.write(42); out.getFD().sync(); }
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        });
        require(queued.await(10, TimeUnit.SECONDS), "Synthetic worker did not start");
        ((ExecutorService) field(service.history, "worker")).execute(() -> {
            try { releaseHistory.await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        String profile = service.profiles.active().id;
        Map<String, TelemetryHistory.Reading> values = new LinkedHashMap<>();
        long wall = System.currentTimeMillis();
        values.put("battery_percent", new TelemetryHistory.Reading("battery_percent", "Battery", "Battery",
                "63 %", "%", 63d, wall, true));
        service.history.record(profile, new TelemetryHistory.Snapshot(values, Collections.emptyMap(), wall), 1, true);
        main(() -> {
            prefs("app_settings").edit().putString("maintenance_test_marker", "preserved").apply();
            service.prepareDataMaintenance(message -> { error.set(message); done.countDown(); });
            require(DataMaintenance.active(), "Maintenance gate was not installed synchronously");
            require(!DataMaintenance.readyForStartup(getTargetContext()), "Another startup bypassed maintenance");
            require(!service.workAllowed(), "Background work remained allowed");
        });
        require(!done.await(200, TimeUnit.MILLISECONDS), "Maintenance completed before a queued durable write");
        releaseWork.countDown();
        require(!done.await(200, TimeUnit.MILLISECONDS), "Maintenance completed before pending history reached SQLite");
        releaseHistory.countDown();
        require(done.await(35, TimeUnit.SECONDS), "Maintenance completion missing");
        require(error.get() == null, "Maintenance failed: " + error.get());
        require(sentinel.length() == 1, "Accepted write was dropped");
        require(worker.isTerminated(), "Charge worker still running after successful preparation");
        require(((ExecutorService)field(service, "capacityWorker")).isTerminated(), "Capacity worker still running");
        require(service.history.closeAndAwait(100), "History database did not close");
        TelemetryHistory reopened = new TelemetryHistory(getTargetContext());
        CountDownLatch loaded = new CountDownLatch(1);
        AtomicReference<TelemetryHistory.Snapshot> snapshot = new AtomicReference<>();
        reopened.load(profile, value -> { snapshot.set(value); loaded.countDown(); });
        require(loaded.await(10, TimeUnit.SECONDS), "Closed database could not be reopened");
        require(snapshot.get().readings.get("battery_percent").number == 63d,
                "The final accepted history sample was not saved");
        require(reopened.closeAndAwait(10000), "Reopened fixture did not close");
        main(() -> {
            service.visible(true);
            require(!service.workAllowed(), "A visibility callback resumed maintenance service");
            require(!service.setSimulation(true), "Preview resumed a frozen service");
            require(!service.setChargeCharger(ChargeSessionStore.Charger.HIGH_SPEED), "Settings changed after freeze");
            require(!service.clearChargeRecordings(null), "A cleanup job started after freeze");
            service.startRide("must remain stopped");
            require(!service.isRecording() && !service.isChargeRecording() && !service.phoneSaving(), "Recording resumed after freeze");
            require(field(service.ble, "gatt") == null && field(service.ble, "scanner") == null,
                    "Bluetooth transport survived maintenance");
        });
    }

    private SharedPreferences prefs(String name) { return getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE); }
    private void main(Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new IllegalStateException(failure.get());
    }
    private static Object field(Object object, String name) {
        try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    private static void setField(Object object, String name, Object value) {
        try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    private void require(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
}
