package com.jjricks.volt;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.*;
import android.location.*;
import android.os.*;

import java.io.IOException;
import java.util.Locale;

/**
 * Explicit-ride-only sensor owner. All callbacks, batching and disk writes run off the UI thread.
 */
public final class PhoneRideRecorder implements SensorEventListener, LocationListener {
    private final Context context;
    private final PhoneRideStore store;
    private final SensorManager sensors;
    private final LocationManager locations;
    private HandlerThread thread;
    private volatile Handler worker;
    private volatile boolean active, locationActive, closing;
    private volatile String status = "Phone sensors idle",
            gpsStatus = "GPS off",
            storageError,
            latestMotion = "Waiting for motion sensors";
    private String id;
    private boolean motionEnabled, hasLinear, hasRotation, hasPressure, hasRaw;
    private volatile long lastMotionMs = -1;
    private long lastPressureMs = -1, linearMs = -1, rawMs = -1, rotationMs = -1;
    private volatile long lastFixMs = -1;
    private final StringBuilder journal = new StringBuilder(), motion = new StringBuilder();
    private final float[] raw = new float[3],
            linear = new float[3],
            rotation = new float[9],
            quaternion = new float[4];
    private int accuracy;
    private int motionCount;
    private double motionPeak;

    public PhoneRideRecorder(Context context, PhoneRideStore store) {
        this.context = context.getApplicationContext();
        this.store = store;
        sensors = context.getSystemService(SensorManager.class);
        locations = context.getSystemService(LocationManager.class);
    }

    public static boolean hasLocationPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
    }

    public boolean active() {
        return active;
    }

    public boolean locationActive() {
        return locationActive;
    }

    public String status() {
        if (storageError != null) return storageError;
        if (closing) return "Saving phone sensors";
        if (!active) return "Phone sensors idle";
        String gps =
                locationActive
                        ? (lastFixMs < 0
                                ? gpsStatus
                                : SystemClock.elapsedRealtime() - lastFixMs > 10000
                                        ? "GPS signal lost"
                                        : "GPS recording")
                        : gpsStatus;
        return gps
                + (hasPressure ? " · barometer" : "")
                + (motionEnabled
                        ? (hasLinear
                                ? " · experimental motion"
                                : hasRaw ? " · raw motion" : " · motion sensor unavailable")
                        : "");
    }

    public String liveMotion() {
        if (!motionEnabled) return "";
        if (!active || lastMotionMs < 0 || SystemClock.elapsedRealtime() - lastMotionMs > 2000)
            return "Waiting for motion sensors";
        return latestMotion;
    }
    /**
     * gpsEnabled must only be true after the service successfully enters location foreground mode.
     */
    public synchronized void start(
            String id, String placement, boolean gpsEnabled, boolean motionEnabled) {
        if (active) return;
        if (thread != null) throw new IllegalStateException("Recorder already used");
        this.id = id;
        this.motionEnabled = motionEnabled;
        active = true;
        status = "Starting phone sensors";
        gpsStatus = "Starting phone sensors";
        thread = new HandlerThread("Volt-phone-ride");
        thread.start();
        worker = new Handler(thread.getLooper());
        final Handler sessionWorker = worker;
        sessionWorker.post(
                () -> {
                    try {
                        store.start(
                                id,
                                System.currentTimeMillis(),
                                SystemClock.elapsedRealtime(),
                                placement);
                    } catch (IOException e) {
                        storageError = "Phone sensor storage unavailable";
                        active = false;
                        thread.quitSafely();
                        return;
                    }
                    if (!active) return;
                    if (sensors != null) {
                        Sensor pressure = sensors.getDefaultSensor(Sensor.TYPE_PRESSURE);
                        hasPressure =
                                register(
                                        pressure, SensorManager.SENSOR_DELAY_NORMAL, sessionWorker);
                        if (motionEnabled) {
                            Sensor a = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
                                    l = sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION),
                                    r = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
                            hasRaw = register(a, 50000, sessionWorker);
                            hasLinear = register(l, 50000, sessionWorker);
                            hasRotation = register(r, 50000, sessionWorker);
                        }
                    }
                    if (gpsEnabled && hasLocationPermission(context) && locations != null)
                        try {
                            if (locations.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                                locations.requestLocationUpdates(
                                        LocationManager.GPS_PROVIDER,
                                        1000,
                                        0,
                                        this,
                                        sessionWorker.getLooper());
                                locationActive = true;
                                gpsStatus = "Waiting for GPS";
                            } else gpsStatus = "Location is off";
                        } catch (SecurityException | IllegalArgumentException e) {
                            gpsStatus = "GPS unavailable";
                        }
                    else
                        gpsStatus =
                                gpsEnabled
                                        ? "GPS permission unavailable"
                                        : hasLocationPermission(context)
                                                ? "GPS off"
                                                : "Location permission not granted";
                    updateStatus();
                    if (active) sessionWorker.postDelayed(flush, 1000);
                });
    }

    private boolean register(Sensor sensor, int rate, Handler handler) {
        if (sensor == null) return false;
        try {
            return sensors.registerListener(this, sensor, rate, handler);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private void updateStatus() {
        status = status();
    }
    /**
     * Drain accepted callbacks, unregister, then flush the final batch before signalling
     * completion.
     */
    public void stop() {
        stop(null);
    }

    public synchronized void stop(Runnable completion) {
        if (worker == null) {
            if (completion != null) completion.run();
            return;
        }
        final Handler stopWorker = worker;
        worker = null;
        closing = true;
        boolean posted =
                stopWorker.post(
                        () -> {
                            active = false;
                            stopWorker.removeCallbacksAndMessages(null);
                            if (sensors != null) sensors.unregisterListener(this);
                            if (locations != null)
                                try {
                                    locations.removeUpdates(this);
                                } catch (SecurityException ignored) {
                                }
                            locationActive = false;
                            flushNow();
                            try {
                                store.stop(
                                        id,
                                        System.currentTimeMillis(),
                                        SystemClock.elapsedRealtime());
                            } catch (IOException e) {
                                storageError = "Phone sensor storage unavailable";
                            }
                            closing = false;
                            status = status();
                            thread.quitSafely();
                            if (completion != null) completion.run();
                        });
        if (!posted) {
            active = false;
            closing = false;
            if (completion != null) completion.run();
        }
    }

    private final Runnable flush =
            new Runnable() {
                @Override
                public void run() {
                    if (!active) return;
                    flushNow();
                    Handler h = worker;
                    if (h != null && active) h.postDelayed(this, 1000);
                }
            };

    private void flushNow() {
        if (motionCount > 0) {
            journal.append(
                    PhoneRideStore.row(
                            "MOTION",
                            System.currentTimeMillis(),
                            SystemClock.elapsedRealtime(),
                            motionCount,
                            hasLinear ? motionPeak : null));
            motionCount = 0;
            motionPeak = 0;
        }
        try {
            store.appendBatch(id, journal.toString(), motion.toString());
            journal.setLength(0);
            motion.setLength(0);
        } catch (IOException e) {
            journal.setLength(0);
            motion.setLength(0);
            storageError = "Phone sensor storage unavailable";
            // If storage recovers, keep a boundary instead of silently bridging lost data.
            journal.append('\n').append(PhoneRideStore.row("GAP", System.currentTimeMillis(), SystemClock.elapsedRealtime()));
        }
    }

    public void telemetry(
            long sourceMs,
            Double speedKmh,
            Double powerW,
            Double batteryPercent,
            long speedUpdatedMs,
            long powerUpdatedMs,
            long batteryUpdatedMs) {
        Handler h = worker;
        if (!active || h == null) return;
        h.post(
                () -> {
                    if (active)
                        journal.append(
                                PhoneRideStore.row(
                                        "TELEMETRY",
                                        System.currentTimeMillis(),
                                        sourceMs,
                                        speedKmh,
                                        powerW,
                                        batteryPercent,
                                        speedUpdatedMs,
                                        powerUpdatedMs,
                                        batteryUpdatedMs));
                });
    }

    @Override
    public void onLocationChanged(Location location) {
        if (!active) return;
        long mono = location.getElapsedRealtimeNanos() / 1000000L,
                now = SystemClock.elapsedRealtime();
        if (mono < 0 || mono > now + 1000 || now - mono > 10000 || !location.hasAccuracy()) return;
        Double speed = location.hasSpeed() ? (double) location.getSpeed() : null,
                speedAccuracy =
                        Build.VERSION.SDK_INT >= 26 && location.hasSpeedAccuracy()
                                ? (double) location.getSpeedAccuracyMetersPerSecond()
                                : null,
                altitude = location.hasAltitude() ? location.getAltitude() : null,
                vertical =
                        Build.VERSION.SDK_INT >= 26 && location.hasVerticalAccuracy()
                                ? (double) location.getVerticalAccuracyMeters()
                                : null;
        journal.append(
                PhoneRideStore.row(
                        "GPS",
                        location.getTime(),
                        mono,
                        location.getLatitude(),
                        location.getLongitude(),
                        location.getAccuracy(),
                        speed,
                        speedAccuracy,
                        altitude,
                        vertical));
        lastFixMs = mono;
        gpsStatus = "GPS recording";
        updateStatus();
    }

    @Override
    public void onProviderEnabled(String provider) {
        if (locationActive) gpsStatus = "Waiting for GPS";
    }

    @Override
    public void onProviderDisabled(String provider) {
        if (locationActive) {
            gpsStatus = "GPS signal unavailable";
            lastFixMs = -1;
            journal.append(
                    PhoneRideStore.row(
                            "GAP", System.currentTimeMillis(), SystemClock.elapsedRealtime()));
        }
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {}

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!active) return;
        long mono = event.timestamp / 1000000L;
        if (mono < 0) return;
        int type = event.sensor.getType();
        if (type == Sensor.TYPE_PRESSURE) {
            if (lastPressureMs < 0 || mono - lastPressureMs >= 900) {
                lastPressureMs = mono;
                journal.append(
                        PhoneRideStore.row(
                                "PRESSURE", System.currentTimeMillis(), mono, event.values[0]));
            }
            return;
        }
        if (!motionEnabled) return;
        if (type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotation, event.values);
            SensorManager.getQuaternionFromVector(quaternion, event.values);
            rotationMs = mono;
            return;
        }
        if (type == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, raw, 0, 3);
            rawMs = mono;
            if (hasLinear) return;
        } else if (type == Sensor.TYPE_LINEAR_ACCELERATION) {
            System.arraycopy(event.values, 0, linear, 0, 3);
            linearMs = mono;
            accuracy = event.accuracy;
        } else return;
        if (lastMotionMs >= 0 && mono - lastMotionMs < 45) return;
        lastMotionMs = mono;
        boolean rawFresh = rawMs >= 0 && Math.abs(mono - rawMs) <= 150,
                linearFresh = linearMs >= 0 && Math.abs(mono - linearMs) <= 150,
                rotationFresh =
                        hasRotation && rotationMs >= 0 && Math.abs(mono - rotationMs) <= 300;
        Double east = null, north = null, up = null;
        if (linearFresh && rotationFresh) {
            east =
                    (double)
                            (rotation[0] * linear[0]
                                    + rotation[1] * linear[1]
                                    + rotation[2] * linear[2]);
            north =
                    (double)
                            (rotation[3] * linear[0]
                                    + rotation[4] * linear[1]
                                    + rotation[5] * linear[2]);
            up =
                    (double)
                            (rotation[6] * linear[0]
                                    + rotation[7] * linear[1]
                                    + rotation[8] * linear[2]);
        }
        Object[] values = {
            mono,
            rawFresh ? raw[0] : null,
            rawFresh ? raw[1] : null,
            rawFresh ? raw[2] : null,
            linearFresh ? linear[0] : null,
            linearFresh ? linear[1] : null,
            linearFresh ? linear[2] : null,
            east,
            north,
            up,
            rotationFresh ? quaternion[1] : null,
            rotationFresh ? quaternion[2] : null,
            rotationFresh ? quaternion[3] : null,
            rotationFresh ? quaternion[0] : null,
            accuracy
        };
        for (int i = 0; i < values.length; i++) {
            if (i > 0) motion.append(',');
            if (values[i] != null) motion.append(values[i]);
        }
        motion.append('\n');
        motionCount++;
        latestMotion =
                linearFresh
                        ? String.format(
                                Locale.ROOT,
                                "Phone linear acceleration: x %.2f · y %.2f · z %.2f m/s²",
                                linear[0],
                                linear[1],
                                linear[2])
                        : rawFresh
                                ? String.format(
                                        Locale.ROOT,
                                        "Phone raw acceleration (includes gravity): x %.2f · y %.2f"
                                            + " · z %.2f m/s²",
                                        raw[0],
                                        raw[1],
                                        raw[2])
                                : "Waiting for motion sensors";
        if (linearFresh)
            motionPeak =
                    Math.max(
                            motionPeak,
                            Math.sqrt(
                                    linear[0] * linear[0]
                                            + linear[1] * linear[1]
                                            + linear[2] * linear[2]));
        // Defensive cap if a slow filesystem prevents scheduled flushes from keeping up.
        if (motion.length() > 1024 * 1024) flushNow();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION) this.accuracy = accuracy;
    }
}
