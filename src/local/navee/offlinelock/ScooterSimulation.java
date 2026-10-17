package local.navee.offlinelock;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Deterministic, memory-only display samples. This class never builds scooter commands or frames. */
public final class ScooterSimulation {
    private final LinkedHashMap<String, Integer> features = new LinkedHashMap<>();
    private final LinkedHashMap<String, Sample> fixed = new LinkedHashMap<>();
    private boolean charging;
    private long chargeStart;
    /** Synthetic advertised model ID; "" is the default UT5 MAX simulator. Never stored or exported. */
    private String model = "";

    private static final class Sample {
        final String text;
        final Double number;
        Sample(String text, Double number) { this.text = text; this.number = number; }
    }

    public ScooterSimulation() {
        for (ScooterCommands.Feature feature : ScooterCommands.catalog())
            if (!feature.sensitive && feature.type != ScooterCommands.Type.TIME)
                features.put(feature.key, feature.optionValues.length == 0
                        ? feature.min : feature.optionValues[0]);
        choose("energy_recovery", 30);
        choose("acceleration", 3);
        choose("headlight", 1);
        choose("auto_sensor", 1);
        choose("traction_control", 1);
        choose("turn_sound", 1);
        choose("range_algorithm", 2);
        choose("start_speed", 3);
        choose("maximum_speed", 50);
        choose("dual_drive", 1);
        choose("charge_limit", 100);
        choose("lock_expiry", 3);
        choose("low_power", 1);
        choose("hill_start_assist", 1);
        choose("parking_assist", 1);
        choose("line_light", 1);
        choose("star_light", 1);
        choose("line_boost_color", 1);
        choose("star_boost_color", 1);

        sample("battery_percent", "78 %", 78);
        sample("battery_health_percent", "97 %", 97);
        sample("battery_status", "Normal (simulation)", 0);
        sample("charge_cycles", "42", 42);
        sample("energy_throughput_wh", "14280 Wh", 14280);
        sample("capacity_throughput_ah", "282.40 Ah", 282.4);
        sample("deep_discharge", "2", 2);
        sample("rated_capacity_ah", "19.80 Ah", 19.8);
        sample("health_adjusted_capacity_ah", "19.21 Ah", 19.206);
        text("battery_manufactured", "Simulation · 2026-01-15");
        text("high_temperature_duration", "Simulation · 0 y 0 mo 0 d 2 h 15 min 0 s");
        sample("speed_kmh", "0.0 km/h", 0);
        sample("remaining_range_km", "38.4 km", 38.4);
        sample("trip_km", "4.26 km", 4.26);
        sample("trip_minutes", "18.5 min", 18.5);
        sample("maximum_speed_kmh", "32.0 km/h", 32);
        sample("average_speed_kmh", "18.4 km/h", 18.4);
        sample("odometer_km", "248.36 km", 248.36);
        sample("driving_status", "Stopped (simulation)", 0);
        text("stored_trips", "Simulation · 12 sample trips");
        sample("binding_status", "Simulation owner", 1);
        sample("driving_mode", "SPORT (simulation)", 3);
        sample("range_algorithm_mode", "Simulation choices", 2);
        sample("tyre_reminder", "No reminder (simulation)", 0);
        sample("night_mode", "Off (simulation)", 0);
        sample("voice_language", "English (simulation)", 1);
        sample("logo_light", "On (simulation)", 1);
        sample("daytime_light", "On (simulation)", 1);
        sample("speed_limit_eligibility", "Available in simulation", 1);
        sample("weather", "Simulation · clear", 0);
        sample("overspeed_alert", "Off (simulation)", 0);
        sample("warning_code", "No warning (simulation)", 0);
        sample("lock_push_warning", "No movement (simulation)", 0);
        sample("hide_e9", "Off (simulation)", 0);
        text("ride_report_format", "Simulation · no BLE report");
        text("scooter_serial", "SIMULATED SCOOTER");
        text("battery_serial_raw", "SIMULATED BATTERY");
        for (String key : new String[]{"meter_version", "controller_version", "battery_version",
                "screen_version", "uwb_version"}) text(key, "Simulation · 1.0");
    }

    private void choose(String key, int value) { features.put(key, value); }
    private void sample(String key, String text, double number) { fixed.put(key, new Sample(text, number)); }
    private void text(String key, String text) { fixed.put(key, new Sample(text, null)); }

    public Map<String, Integer> features() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(features));
    }

    public Integer featureValue(String key) { return features.get(key); }

    public int[] featureChoices(String key) {
        try { return ScooterCommands.find(key).optionValues.clone(); }
        catch (IllegalArgumentException unknown) { return new int[0]; }
    }

    /** Validates the named catalog domain, then changes only this in-memory instance. */
    public void set(String key, int value) {
        ScooterCommands.Feature feature = ScooterCommands.find(key);
        if (feature.sensitive || feature.type == ScooterCommands.Type.TIME || !feature.allows(value))
            throw new IllegalArgumentException("Unavailable simulation setting");
        features.put(key, value);
    }

    public void setChargeStart(long seconds, int offset) {
        long local = seconds + (long) offset;
        if (seconds < 0 || local < 0 || local > 0xffffffffL || Math.abs((long) offset) > 86400)
            throw new IllegalArgumentException("Invalid simulation charging time");
        chargeStart = local;
    }

    /** Developer model choice. Keeps every catalogue value: the UI hides or greys controls by decision. */
    public void setModel(String advertisedPid) {
        String pid = advertisedPid == null ? "" : advertisedPid;
        if (!pid.isEmpty() && !pid.matches("[1-9][0-9]{0,4}"))
            throw new IllegalArgumentException("Invalid simulation model");
        model = pid;
    }
    public String model() { return model; }

    public boolean charging() { return charging; }
    public void setCharging(boolean value) { charging = value; }

    /** Replaces every catalog reading with clearly synthetic source=-1 data; no wire bytes exist. */
    public void tick(Telemetry telemetry, long elapsedMs) {
        if (telemetry == null || elapsedMs < 0) throw new IllegalArgumentException("Invalid simulation clock");
        if (!telemetry.frames().isEmpty() || !telemetry.statuses().isEmpty())
            throw new IllegalArgumentException("Clear real telemetry before simulating");
        for (Telemetry.Metric metric : telemetry.metrics()) {
            Sample value = fixed.get(metric.key);
            telemetry.putSimulationSample(metric.key,
                    value == null ? "Simulation · no real response" : value.text,
                    value == null ? null : value.number, elapsedMs);
        }
        // Feature readback fields can be shared bit-packed status values. Compose
        // their documented masks for display, without constructing a BLE frame.
        LinkedHashMap<String, Integer> fields = new LinkedHashMap<>();
        for (ScooterCommands.Feature feature : ScooterCommands.catalog()) {
            Integer value = features.get(feature.key);
            if (value == null || feature.readbackKey.isEmpty()) continue;
            int previous = fields.containsKey(feature.readbackKey) ? fields.get(feature.readbackKey) : 0;
            fields.put(feature.readbackKey, (previous & ~feature.readbackMask)
                    | ((value << feature.readbackShift) & feature.readbackMask));
        }
        for (Map.Entry<String, Integer> field : fields.entrySet())
            telemetry.putSimulationSample(field.getKey(), field.getValue() + " (simulation)",
                    field.getValue().doubleValue(), elapsedMs);
        telemetry.putSimulationSample("lock_status", features.get("lock") == 0
                ? "Unlocked (simulation)" : "Locked (simulation)", features.get("lock").doubleValue(), elapsedMs);
        telemetry.putSimulationSample("charge_limit_percent", features.get("charge_limit") + " %",
                features.get("charge_limit").doubleValue(), elapsedMs);
        telemetry.putSimulationSample("scheduled_charge_time_raw", chargeStart == 0
                ? "No schedule (simulation)" : chargeStart + " (simulation)", (double) chargeStart, elapsedMs);

        double phase = elapsedMs / 1000.0;
        double volts = 50.4 + .08 * Math.sin(phase / 19);
        double amps = charging ? -(2.8 + .12 * Math.sin(phase / 8))
                : .36 + .08 * Math.sin(phase / 7);
        quantity(telemetry, "voltage_v", volts, "V", 2, elapsedMs);
        quantity(telemetry, "current_a", amps, "A", 2, elapsedMs);
        quantity(telemetry, "power_w", volts * amps, "W", 1, elapsedMs);
        quantity(telemetry, "temperature_c", 23.5 + .5 * Math.sin(phase / 37), "°C", 1, elapsedMs);
        telemetry.putSimulationSample("charging_state", charging ? "Charging (simulation)" : "Not charging (simulation)",
                charging ? 1.0 : 0.0, elapsedMs);
        telemetry.putSimulationSample("current_sign_state", charging ? "1 (simulation)" : "0 (simulation)",
                charging ? 1.0 : 0.0, elapsedMs);
        telemetry.putSimulationSample("temperature_sign_state", "0 (simulation)", 0.0, elapsedMs);
    }

    private static void quantity(Telemetry telemetry, String key, double number,
            String unit, int decimals, long time) {
        telemetry.putSimulationSample(key, String.format(Locale.US, "%." + decimals + "f %s", number, unit), number, time);
    }
}
