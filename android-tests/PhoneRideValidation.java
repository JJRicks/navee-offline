package com.jjricks.volt.tests;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import com.jjricks.volt.BleController;
import com.jjricks.volt.MainActivity;
import com.jjricks.volt.PhoneRideRecorder;
import com.jjricks.volt.PhoneRideStore;
import com.jjricks.volt.ScooterService;
import org.json.JSONObject;

/** Emulator-only fixtures: refuses owner phones, saved profiles, files, or enabled Bluetooth. */
public final class PhoneRideValidation extends Instrumentation {
    private Bundle arguments;
    private MainActivity activity;
    private ScooterService service;
    private boolean admitted;
    private final List<String> checks = new ArrayList<>();
    private final List<String> fixtureIds = new ArrayList<>();
    private final List<String> scooterFixtureIds = new ArrayList<>();
    private static final String[] PREFS = {"app_settings", "profiles", "scooter", "pending_documents"};

    @Override public void onCreate(Bundle arguments) {
        this.arguments = arguments == null ? new Bundle() : arguments;
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            String mode = arguments.getString("mode", "denied");
            require(mode.equals("denied") || mode.equals("granted"), "Use mode denied or granted");
            requireFreshEmulator(mode);
            admitted = true;
            SharedPreferences settings = getTargetContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE);
            require(settings.edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                    .putBoolean("phone_gps", true).putBoolean("phone_motion", true).putString("phone_placement", "pocket").commit(),
                    "Could not set fixture policy");
            Activity launched = startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            require(launched instanceof MainActivity, "Unexpected Activity");
            activity = (MainActivity) launched;
            await(() -> field(activity, "scooterService") != null, 6000, "Local service unavailable");
            main(() -> { service = (ScooterService) field(activity, "scooterService"); assertNoScooterWork(); });
            recorderLifecycle(mode);
            immediateStopLifecycle();
            journalFixture();
            main(this::assertNoScooterWork);
            result.putString("result", "PASS");
            result.putString("mode", mode);
            result.putStringArrayList("checks", new ArrayList<>(checks));
            result.putInt("check_count", checks.size());
            result.putInt("scooter_mutations", 0);
            result.putBoolean("production_startRide_exercised", false);
            result.putString("fixture", "Synthetic phone recording on empty emulator; no scooter profile or Bluetooth connection");
            cleanup();
            finish(0, result);
        } catch (Throwable failure) {
            result.putString("result", "FAIL");
            result.putString("error", failure.getClass().getSimpleName() + ": " + failure.getMessage());
            result.putStringArrayList("completed_checks", new ArrayList<>(checks));
            try { cleanup(); } catch (Throwable error) { result.putString("cleanup_error", error.getClass().getSimpleName() + ": " + error.getMessage()); }
            finish(1, result);
        }
    }

    private void requireFreshEmulator(String mode) {
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
        require(hardware.contains("ranchu") || hardware.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone")
                || Build.MODEL.startsWith("sdk_gphone"), "Phone recording validation refuses physical owner phones");
        require(Build.VERSION.SDK_INT >= 31, "This permission regression requires Android 12+");
        for (String name : PREFS) require(getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE).getAll().isEmpty(),
                "Fresh empty app data required; existing preferences refused");
        File[] files = getTargetContext().getFilesDir().listFiles();
        require(files != null && files.length == 0, "Fresh empty app data required; existing files refused");
        require(granted(Manifest.permission.BLUETOOTH_SCAN) && granted(Manifest.permission.BLUETOOTH_CONNECT),
                "Grant Nearby devices externally before this test");
        BluetoothManager manager = getTargetContext().getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        require(adapter == null || !adapter.isEnabled(), "Bluetooth must be disabled or unavailable");
        boolean precise = granted(Manifest.permission.ACCESS_FINE_LOCATION), approximate = granted(Manifest.permission.ACCESS_COARSE_LOCATION);
        require(mode.equals("granted") ? precise && approximate : !precise && !approximate,
                "Location grant does not match test mode");
        if (mode.equals("granted")) require(getTargetContext().getSystemService(LocationManager.class)
                .isProviderEnabled(LocationManager.GPS_PROVIDER), "Granted mode requires emulator GPS enabled");
    }

    private boolean granted(String permission) { return getTargetContext().checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED; }

    private void recorderLifecycle(String mode) throws Exception {
        // Deliberately create the journal below the BLE-authenticated Start button. This tests
        // sensor/service lifecycle without pretending an emulator is a real connected scooter.
        AtomicReference<String> id = new AtomicReference<>();
        main(() -> {
            try { id.set(service.rides.start("emulator-fixture", "Synthetic sensor validation", System.currentTimeMillis(), SystemClock.elapsedRealtime())); }
            catch (Exception error) { throw new IllegalStateException(error); }
        });
        fixtureIds.add(id.get());
        scooterFixtureIds.add(id.get());
        main(() -> invoke(service, "applyWorkPolicy", new Class<?>[]{boolean.class}, false));
        PhoneRideRecorder recorder = (PhoneRideRecorder) field(service, "phoneRecorder");
        require(recorder != null, "Phone recorder was not initialized");
        await(() -> recorder.active() && !recorder.status().startsWith("Starting phone sensors"), 5000, "Phone capture did not start");
        require(recorder.locationActive() == mode.equals("granted"), "GPS registration does not match granted capability");
        require((Boolean) field(service, "phoneLocationForeground") == mode.equals("granted"), "Location foreground type does not follow permission");
        HandlerThread captureThread = (HandlerThread) field(recorder, "thread");
        require(captureThread != null && captureThread.isAlive(), "Sensor worker thread unavailable");
        check(mode.equals("granted") ? "Granted location registers GPS under the location foreground service" : "Denied location preserves sensor recording without GPS or a location foreground type");
        injectLocations(recorder);

        main(() -> activity.moveTaskToBack(true));
        await(() -> !service.isVisible(), 4000, "Activity did not enter background");
        try{
            getUiAutomation().executeShellCommand("input keyevent 223").close();
            SystemClock.sleep(11000);
            require(!getTargetContext().getSystemService(android.os.PowerManager.class).isInteractive(),"Ride screen-off fixture remained interactive");
            require(((android.os.PowerManager.WakeLock)field(service,"wakeLock")).isHeld(),"Ride lost its wake lock with the screen off");
        }finally{
            getUiAutomation().executeShellCommand("input keyevent 224").close();
            getUiAutomation().executeShellCommand("wm dismiss-keyguard").close();
        }
        main(() -> {
            require(service.isRecording() && recorder.active() && service.workAllowed(), "Explicit recording stopped at the idle background cutoff");
            require(service.onStartCommand(new Intent(getTargetContext(),ScooterService.class).setAction("RECORDING_STARTED"),0,50)==android.app.Service.START_STICKY,"Active ride does not request recovery after process loss");
            require(service.backgroundDeadlineMs() == 0, "Active recording unexpectedly retained an idle-stop deadline");
            assertNoScooterWork();
        });
        check("Active recording holds CPU and survives screen-off beyond the ten-second idle cutoff");
        main(service::stopRide);
        await(() -> !recorder.active() && !recorder.locationActive() && !captureThread.isAlive(), 5000,
                "Phone sensors or their callback thread remained active after Stop recording");
        require(!service.isRecording(), "Scooter journal remained active after Stop");
        require(field(service, "phoneRecorder") == null, "Service retained stopped recorder");
        require(service.phoneRides.load(id.get()).locationCount >= 1, "Recorder did not flush synthetic location callbacks");
        check("Stop recording saves the ride and releases phone capture");
    }

    private void injectLocations(PhoneRideRecorder recorder) throws Exception {
        Handler worker = (Handler) field(recorder, "worker");
        require(worker != null, "Recorder worker unavailable");
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        worker.post(() -> {
            try {
                long now = SystemClock.elapsedRealtime(), wall = System.currentTimeMillis();
                for (int i = 0; i < 3; i++) {
                    Location location = new Location("gps");
                    location.setLatitude(0); location.setLongitude(i * 0.0001);
                    location.setAccuracy(3); location.setSpeed(5 + i); location.setSpeedAccuracyMetersPerSecond(0.2f);
                    location.setAltitude(10); location.setVerticalAccuracyMeters(2);
                    location.setTime(wall - 4000 + i * 2000); location.setElapsedRealtimeNanos((now - 4000 + i * 2000) * 1000000L);
                    recorder.onLocationChanged(location);
                }
            } catch (Throwable error) { failure.set(error); }
            finally { finished.countDown(); }
        });
        require(finished.await(5, TimeUnit.SECONDS), "Synthetic location callbacks did not finish");
        if (failure.get() != null) throw new IllegalStateException("Synthetic GPS callback failed", failure.get());
    }

    private void immediateStopLifecycle() {
        for (int i = 0; i < 3; i++) {
            String id = "emulator-immediate-stop-" + i;
            fixtureIds.add(id);
            PhoneRideRecorder recorder = new PhoneRideRecorder(getTargetContext(), service.phoneRides);
            AtomicReference<HandlerThread> thread = new AtomicReference<>();
            main(() -> {
                recorder.start(id, "pocket", false, true);
                thread.set((HandlerThread) field(recorder, "thread"));
                recorder.stop();
            });
            await(() -> !recorder.active() && !recorder.locationActive() && !thread.get().isAlive(), 5000,
                    "Immediate Stop leaked the callback thread or location registration");
        }
        check("Immediate start/stop cycles release capture without a startup race");
    }

    private void journalFixture() throws Exception {
        String id = "emulator-route-fixture";
        fixtureIds.add(id);
        PhoneRideStore store = service.phoneRides;
        require(store != null, "Phone journal unavailable");
        long wall = 1791835200000L, mono = 100000;
        store.start(id, wall, mono, "pocket");
        StringBuilder batch = new StringBuilder();
        for (int i = 0; i < 14; i++) {
            long dt = i * 2000L;
            batch.append(PhoneRideStore.row("PRESSURE", wall + dt, mono + dt, 1013.25 - i * 0.05));
            batch.append(PhoneRideStore.row("TELEMETRY", wall + dt, mono + dt, 18 + i * 0.36, 500, 90));
            batch.append(PhoneRideStore.row("GPS", wall + dt, mono + dt, 0, i * 0.0001, 3, 5 + i * 0.1, 0.2, 10 + i * 0.4, 2));
        }
        batch.append(PhoneRideStore.row("GPS", wall + 28000, mono + 28000, 0, 1, 100, 5, 0.2, 20, 2));
        batch.append(PhoneRideStore.row("GAP", wall + 30000, mono + 30000));
        batch.append(PhoneRideStore.row("GPS", wall + 32000, mono + 32000, 0, 0.0015, 3, 5, 0.2, 20, 2));
        batch.append(PhoneRideStore.row("GPS", wall + 34000, mono + 34000, 0, 0.0016, 3, 5, 0.2, 20, 2));
        store.appendBatch(id, batch.toString(), "");
        store.stop(id, wall + 36000, mono + 36000);
        PhoneRideStore.Track track = store.load(id);
        require(track.locationCount == 16 && track.rejectedLocationCount == 1, "GPS quality filtering returned unexpected counts");
        require(track.distanceMeters != null && track.distanceMeters > 140 && track.distanceMeters < 165, "Route integrated a rejected fix or bridged its gap");
        require(track.maxGpsAccelerationMps2 != null && Math.abs(track.maxGpsAccelerationMps2 - 0.05) < 0.001,
                "GPS speed derivative does not preserve units/source intervals");
        require(track.maxScooterAccelerationMps2 != null && Math.abs(track.maxScooterAccelerationMps2 - 0.05) < 0.001,
                "Scooter speed derivative does not preserve units/source intervals");
        boolean grade = false, power = false;
        for (PhoneRideStore.Point point : track.points) { grade |= point.gradePercent != null; power |= point.powerW != null && point.powerW == 500; }
        require(grade && power, "Route lacks grade or synchronized power");
        JSONObject json = new JSONObject(track.toJson());
        require(json.getJSONArray("points").length() == 16, "Route JSON dropped accepted fixes");
        require(track.toCsv().contains("gps_acceleration_mps2") && track.toCsv().split("\n").length == 17, "CSV export lacks accepted route data");
        String gpx = track.toGpx();
        require(gpx.contains("<trkpt lat=\"0.0\"") && gpx.split("<trkseg>", -1).length == 3, "GPX export does not preserve route segments");
        check("Synthetic route filters poor fixes and synchronizes power, grade, and acceleration");
        check("CSV, JSON, and GPX exports retain accepted fixes and disconnected segments");
    }

    private void assertNoScooterWork() {
        require(service.profiles.all().isEmpty() && service.profiles.active() == null, "Unexpected owner profile");
        require(service.ble.state() == BleController.State.DISCONNECTED && !service.ble.connected(), "Unexpected Bluetooth work");
        require(((Number) field(service.ble, "mutationSequence")).longValue() == 0, "Unexpected scooter mutation");
        require(service.ble.commandResults().isEmpty(), "Unexpected scooter command result");
    }

    private void cleanup() throws Exception {
        if (!admitted) return;
        if (service != null) {
            main(() -> { if (service.isRecording()) service.stopRide(); });
            for (String id : scooterFixtureIds) service.rides.delete(id);
            if (service.phoneRides != null) for (String id : fixtureIds) service.phoneRides.delete(id);
        }
        if (activity != null && !activity.isDestroyed()) {
            main(activity::finish);
            await(activity::isDestroyed, 5000, "Fixture Activity did not finish");
        }
        main(() -> { ScooterService.setUiVisible(false); getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); });
        waitForIdleSync();
        for (String name : PREFS) require(getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit(), "Could not clear fixture preferences");
        admitted = false;
    }

    private Object field(Object object, String name) {
        try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot inspect " + name, error); }
    }

    private void invoke(Object object, String name, Class<?>[] types, Object... values) {
        try { Method method = object.getClass().getDeclaredMethod(name, types); method.setAccessible(true); method.invoke(object, values); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot call " + name, error); }
    }

    private void await(BooleanSupplier condition, long timeout, String failure) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < deadline) {
            AtomicReference<Boolean> done = new AtomicReference<>(false);
            main(() -> done.set(condition.getAsBoolean()));
            if (done.get()) return;
            SystemClock.sleep(40);
        }
        throw new IllegalStateException(failure);
    }

    private void main(Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new IllegalStateException(failure.get().getClass().getSimpleName() + ": " + failure.get().getMessage(), failure.get());
    }

    private void check(String message) { checks.add(message); Bundle status = new Bundle(); status.putString("check", message); sendStatus(0, status); }
    private static void require(boolean condition, String failure) { if (!condition) throw new IllegalStateException(failure); }
}
