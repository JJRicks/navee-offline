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
import android.widget.TextView;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import com.jjricks.volt.*;

/** Empty disposable emulator only. Holds the real worker to inspect pending UI deterministically. */
public final class CapacityWidgetValidation extends Instrumentation {
    private MainActivity activity;
    private ScooterService service;
    private DashboardScreen dashboard;
    private String profile;
    private int checks;

    @Override public void onCreate(Bundle arguments) { start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            admit();
            main(() -> {
                settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                        .putBoolean("developer_options", true).commit();
                ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
                profile = profiles.create("Synthetic capacity fixture", "000000000000", 0).id;
                DashboardConfig config = new DashboardConfig(settings(), profile);
                config.widgets.clear();
                DashboardConfig.Widget capacity = new DashboardConfig.Widget("capacity");
                capacity.advertisedWh = 716.04;
                config.widgets.add(capacity); config.save();
            });
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.service() != null, "Service absent");
            service = activity.service();
            pendingThenInsufficient();
            seedRide();
            pendingThenEstimate();
            refreshRetainsEstimate();
            analysisFailure(false);
            analysisFailure(true);
            simulationChanges();
            profileChanges();
            closedCardIgnoresCompletion();
            main(() -> require(field(service.ble, "gatt") == null && field(service.ble, "scanner") == null,
                    "Capacity analysis touched Bluetooth"));
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", error.toString());
            error.printStackTrace();
        } finally {
            result.putInt("assertions", checks);
            result.putBoolean("physical_device_tested", false);
            if (activity != null) main(() -> {
                if (dashboard != null) dashboard.close();
                activity.finish(); ScooterService.setUiVisible(false);
                getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class));
            });
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    private void pendingThenInsufficient() throws Exception {
        CountDownLatch release = holdWorker();
        try { main(() -> { fresh(); assertProcessing(); }); }
        finally { release.countDown(); }
        awaitIdle();
        main(() -> {
            require(value().equals("Not enough ride data"), "Completed empty history did not show insufficient data");
            require(result().unavailableReason == null, "Empty history became an analysis error");
        });
        release = holdWorker();
        try { main(() -> { refreshNow(); assertProcessing(); }); }
        finally { release.countDown(); }
        awaitIdle();
    }

    private void pendingThenEstimate() throws Exception {
        CountDownLatch release = holdWorker();
        try { main(() -> { fresh(); assertProcessing(); }); }
        finally { release.countDown(); }
        awaitIdle();
        main(this::assertEstimate);
    }

    private void refreshRetainsEstimate() throws Exception {
        CountDownLatch release = holdWorker();
        try {
            main(() -> {
                refreshNow();
                require(Boolean.TRUE.equals(field(tile(), "capacityPending")), "Refresh did not wait for analysis");
                assertEstimate();
                require(text("capacitySummary").contains("Updating"), "Refresh lacks an updating state");
                require(((View) field(tile(), "capacityCompare")).getVisibility() == View.VISIBLE,
                        "Pending refresh hid the last comparison");
            });
        } finally { release.countDown(); }
        awaitIdle();
        main(this::assertEstimate);
    }

    private void analysisFailure(boolean retainEstimate) throws Exception {
        File rides = new File(getTargetContext().getFilesDir(), "rides");
        File unavailable = new File(getTargetContext().getFilesDir(), "capacity-test-unavailable");
        require(rides.renameTo(unavailable), "Could not temporarily move synthetic ride storage");
        try {
            main(() -> { if (retainEstimate) refreshNow(); else fresh(); });
            awaitIdle();
            main(() -> {
                require(field(tile(), "capacityUnavailable") != null, "Analysis failure was not recorded");
                if (retainEstimate) {
                    assertEstimate();
                    require(text("capacitySummary").contains("refresh unavailable"), "Failed refresh did not label retained estimate");
                } else {
                    require(value().equals("Estimate unavailable"), "Failed analysis masqueraded as insufficient rides");
                    require(result() == null, "Analysis failure became completed ride evidence");
                }
            });
        } finally { require(unavailable.renameTo(rides), "Could not restore synthetic ride storage"); }
        main(this::refreshNow);
        awaitIdle();
        main(this::assertEstimate);
    }

    private void simulationChanges() throws Exception {
        CountDownLatch release = holdWorker();
        try {
            main(() -> {
                refreshNow();
                require(service.setSimulation(true), "Could not enable sample mode");
                invoke(tile(), "refreshCapacity");
                assertSample();
            });
        } finally { release.countDown(); }
        drainWorker();
        main(this::assertSample);
        release = holdWorker();
        try {
            main(() -> {
                require(service.setSimulation(false), "Could not leave sample mode");
                invoke(tile(), "refreshCapacity");
                assertProcessing();
                require(((View) field(tile(), "capacityMethod")).getVisibility() == View.GONE,
                        "Leaving simulation kept the Sample chip");
            });
        } finally { release.countDown(); }
        awaitIdle();
        main(this::assertEstimate);
    }

    private void profileChanges() throws Exception {
        CountDownLatch release = holdWorker();
        try {
            main(() -> {
                fresh(); assertProcessing();
                service.profiles.create("Other synthetic scooter", "111111111111", 0);
                // The existing card can still receive a queued callback before its host rebuilds.
                invoke(tile(), "refreshCapacity");
                require(result() == null && value().equals("Processing…"), "Profile change retained old evidence");
            });
        } finally { release.countDown(); }
        drainWorker();
        main(() -> {
            require(result() == null && value().equals("Processing…"), "Old profile completion leaked into the changed context");
            service.profiles.select(profile);
            fresh();
        });
        awaitIdle();
        main(this::assertEstimate);
    }

    private void closedCardIgnoresCompletion() throws Exception {
        CountDownLatch release = holdWorker();
        try { main(() -> { fresh(); assertProcessing(); dashboard.close(); }); }
        finally { release.countDown(); }
        drainWorker();
        main(() -> require(result() == null, "Closed card accepted a late analysis result"));
    }

    private void fresh() {
        if (dashboard != null) dashboard.close();
        service.invalidateCapacityEstimate();
        // A separate real card remains stable while MainActivity reacts to simulation/profile events.
        dashboard = new DashboardScreen(activity, new DashboardScreen.Actions() {
            public View lock(boolean showDisconnect) { return new View(activity); }
            public void clearLock() { }
            public void navigate(String page) { }
            public void startRide() { }
            public void rename() { }
            public void connectionDetails() { }
        });
    }

    private void refreshNow() {
        service.invalidateCapacityEstimate();
        set(tile(), "capacityChecked", 0L);
        invoke(tile(), "refreshCapacity");
    }

    private void assertProcessing() {
        require(Boolean.TRUE.equals(field(tile(), "capacityPending")), "Analysis was not pending");
        require(value().equals("Processing…"), "Pending card claimed completed insufficient data: " + value());
        require(result() == null, "Pending card exposed completed evidence");
        require(((View) field(tile(), "capacityBar")).getVisibility() == View.GONE, "Pending card showed evidence progress");
    }

    private void assertEstimate() {
        require(value().equals("≈ 600 Wh"), "Expected genuine 600 Wh estimate: " + value());
        require(text("capacitySummary").startsWith("6 rides · ") || text("capacitySummary").contains("Updating")
                        || text("capacitySummary").contains("refresh unavailable"),
                "Estimate summary lacks its rides and range: " + text("capacitySummary"));
        require(result() != null && profile.equals(result().profileId)
                && result().estimatedUsableWh != null && Math.abs(result().estimatedUsableWh - 600) < .001,
                "Displayed estimate does not match the genuine fixture");
    }

    private void assertSample() {
        require(result() == null && text("capacityMethod").equals("Sample"), "Pending real callback replaced sample state");
        require(value().equals("≈ 680 Wh"), "Sample card did not show reference-based sample estimate");
    }

    /** Six identical 20-point rides at 600 Wh per gauge: enough rides and points for an estimate. */
    private void seedRide() throws Exception {
        for (int ride = 0; ride < BatteryCapacityEstimate.MIN_RIDES; ride++) seedRide(ride);
    }

    private void seedRide(int ride) throws Exception {
        RideStore fixture = new RideStore(new File(getTargetContext().getCacheDir(), "capacity-" + UUID.randomUUID()));
        fixture.setCurrentDirection(RideStore.CurrentDirection.POSITIVE_IS_DRAW);
        long start = System.currentTimeMillis() - 1200000 - ride * 3600000L;
        String id = fixture.start(profile, "Synthetic capacity ride", start, 0);
        for (int sec = 0; sec <= 360; sec++) {
            long mono = sec * 1000L;
            Map<String, RideStore.Sample> values = new LinkedHashMap<>();
            values.put("voltage_v", new RideStore.Sample(50d, "50 V", "V", 0x72, mono));
            values.put("current_a", new RideStore.Sample(24d, "24 A", "A", 0x72, mono));
            values.put("speed_kmh", new RideStore.Sample(20, mono));
            values.put("battery_percent", new RideStore.Sample(80 - 20d * sec / 360, mono));
            values.put("temperature_c", new RideStore.Sample(22, mono));
            fixture.sample(start + mono, mono, values);
        }
        fixture.stop(start + 360000, 360000);
        fixture.exportCsv(id, new File(new File(getTargetContext().getFilesDir(), "rides"), id + ".csv"));
    }

    private CountDownLatch holdWorker() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ((ExecutorService) field(service, "capacityWorker")).execute(() -> {
            entered.countDown();
            try { release.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        });
        require(entered.await(5, TimeUnit.SECONDS), "Could not hold analysis worker");
        return release;
    }

    private void drainWorker() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        ((ExecutorService) field(service, "capacityWorker")).execute(done::countDown);
        require(done.await(5, TimeUnit.SECONDS), "Analysis worker did not finish");
        waitForIdleSync();
    }

    private void awaitIdle() {
        await(() -> {
            AtomicBoolean done = new AtomicBoolean();
            main(() -> done.set(!Boolean.TRUE.equals(field(tile(), "capacityPending"))));
            return done.get();
        }, "Card analysis did not finish");
    }

    private void admit() {
        require(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")
                || Build.PRODUCT.startsWith("sdk_gphone"), "Refusing physical device");
        for (String name : new String[]{"app_settings", "profiles", "scooter", "pending_documents", "control_evidence"})
            require(getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE).getAll().isEmpty(), "Refusing existing preferences");
        require(getTargetContext().getFilesDir().list().length == 0 && getTargetContext().databaseList().length == 0,
                "Refusing existing app data");
        for (String permission : new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT})
            require(getTargetContext().checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED, "Nearby permission missing");
        BluetoothManager manager = getTargetContext().getSystemService(BluetoothManager.class);
        require(manager == null || manager.getAdapter() == null || !manager.getAdapter().isEnabled(), "Disable emulator Bluetooth");
    }

    private Object tile() { return ((List<?>) field(dashboard, "tiles")).get(0); }
    private BatteryCapacityEstimate.Result result() { return (BatteryCapacityEstimate.Result) field(tile(), "capacityResult"); }
    private String value() { return text("capacityValue"); }
    private String text(String name) { return ((TextView) field(tile(), name)).getText().toString(); }
    private SharedPreferences settings() { return getTargetContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE); }
    private void main(Runnable action) { runOnMainSync(action); }
    private void require(boolean condition, String message) { checks++; if (!condition) throw new IllegalStateException(message); }
    private void await(BooleanSupplier ready, String message) {
        long end = SystemClock.uptimeMillis() + 15000;
        while (!ready.getAsBoolean() && SystemClock.uptimeMillis() < end) SystemClock.sleep(30);
        require(ready.getAsBoolean(), message);
    }
    private Object field(Object target, String name) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    private void set(Object target, String name, Object value) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    private void invoke(Object target, String name) {
        try { Method method = target.getClass().getDeclaredMethod(name); method.setAccessible(true); method.invoke(target); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
}
