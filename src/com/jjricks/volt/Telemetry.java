package com.jjricks.volt;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Read-only NAVEE telemetry, independent of Android and the BLE transport.
 * Offsets match the OEM BleHandler's payload AFTER the response-status byte.
 * A short or rejected reply never invents zero readings or erases earlier data.
 */
public final class Telemetry {
    public static final class Metric {
        public final String group;
        public final String key;
        public final String label;
        /** null means this field has not been supplied successfully. */
        public final String value;
        /** Normalized SI/metric quantity where its unit is confirmed; otherwise null. */
        public final Double numericValue;
        /** The actual read/report opcode, or -1 for unavailable/synthetic display data. */
        public final int sourceCmd;
        /** Monotonic timestamp of the underlying reading, or -1 if unavailable. */
        public final long updatedMs;

        private Metric(String group, String key, String label, String value,
                Double numericValue, int sourceCmd, long updatedMs) {
            this.group = group;
            this.key = key;
            this.label = label;
            this.value = value;
            this.numericValue = numericValue;
            this.sourceCmd = sourceCmd;
            this.updatedMs = updatedMs;
        }
    }

    private static final String RAW_SUFFIX = " (raw)";
    private static final int[] READ_COMMANDS = {
        0x70, 0x71, 0x72, 0x73, 0x74, 0x75, 0x76, 0x79, 0x7b, 0x90, 0x91, 0x92
    };
    private static final String[][] SETTINGS = {
        {"binding_status", "Account binding status (raw)"},
        {"driving_mode", "Driving mode (raw)"},
        {"lock_status", "Electronic lock"},
        {"cruise_control", "Cruise control (raw)"},
        {"tail_light", "Tail light setting (raw)"},
        {"energy_recovery", "Energy recovery setting (raw)"},
        {"range_algorithm", "Range algorithm (raw)"},
        {"display_units", "Dashboard units"},
        {"auto_sensor", "Automatic light sensor (raw)"},
        {"tyre_reminder", "Tyre reminder (raw)"},
        {"ambient_light", "Line lighting state / pattern (raw)"},
        {"traction_control", "Traction control (raw)"},
        {"turn_sound", "Turn signal sound (raw)"},
        {"proximity_key", "Proximity key setting (raw)"},
        {"night_mode", "Night mode (raw)"},
        {"range_algorithm_mode", "Range algorithm mode (raw)"},
        {"eco_light", "Line normal / Boost colors (packed raw)"},
        {"drive_light", "Star lighting state / pattern (raw)"},
        {"sport_light", "Star normal / Boost colors (packed raw)"},
        {"start_speed", "Start speed setting (raw)"},
        {"speed_limit", "Speed limit setting (raw)"},
        {"volume", "Audio setting (model-dependent raw)"},
        {"voice_language", "Voice language setting (raw)"},
        {"logo_light", "Logo light setting (raw)"},
        {"daytime_light", "Daytime running light (raw)"},
        {"maximum_speed_setting", "Maximum speed setting (raw)"},
        {"drive_mode_setting", "Drive mode setting (raw)"}
    };
    private final LinkedHashMap<String, Metric> readings = new LinkedHashMap<String, Metric>();
    private final LinkedHashMap<Integer, byte[]> rawFrames = new LinkedHashMap<Integer, byte[]>();
    private final LinkedHashMap<Integer, Integer> responseStatuses = new LinkedHashMap<Integer, Integer>();
    private Long rawVoltage;
    private Long rawCurrent;
    private Long rawTemperature;
    private int voltageCmd = -1, currentCmd = -1, temperatureCmd = -1;
    private long voltageMs = -1, currentMs = -1, temperatureMs = -1;
    private long electricalBatch, voltageBatch = -1, currentBatch = -1;
    private Integer currentSignState;
    private Integer temperatureSignState;

    public Telemetry() {
        define("Battery", "battery_percent", "Battery charge (%)");
        define("Battery", "voltage_v", "Battery voltage");
        define("Battery", "current_a", "Battery current (signed)");
        define("Battery", "power_w", "Battery power (signed)");
        define("Battery", "temperature_c", "Battery temperature");
        define("Battery", "battery_health_percent", "Reported battery health");
        define("Battery", "battery_status", "Battery status");
        define("Battery", "charging_state", "Charging state (raw)");
        define("Battery", "charge_cycles", "Reported charge count");
        define("Battery", "energy_throughput_wh", "Energy counter (unverified)");
        define("Battery", "capacity_throughput_ah", "Lifetime capacity throughput");
        define("Battery", "deep_discharge", "Deep discharge counter");
        define("Battery", "self_discharge_raw", "Self discharge rate (raw; unit unknown)");
        define("Battery", "rated_capacity_ah", "Rated battery capacity");
        define("Battery", "health_adjusted_capacity_ah", "Capacity estimate from reported health");
        define("Battery", "battery_manufactured", "Battery manufacturing date");
        define("Battery", "high_temperature_duration", "High temperature exposure");
        define("Battery", "extreme_temperature_charge_raw", "Extreme-temperature charging duration (raw; unit unknown)");
        define("Battery", "temperature_sign_state", "Temperature sign state (raw)");
        define("Battery", "current_sign_state", "Current sign state (raw)");

        define("Ride", "speed_kmh", "Live speed");
        define("Ride", "remaining_range_km", "Scooter's estimated remaining range");
        define("Ride", "trip_km", "Scooter's current trip distance");
        define("Ride", "trip_minutes", "Scooter's current trip time");
        define("Ride", "maximum_speed_kmh", "Current trip maximum speed");
        define("Ride", "average_speed_kmh", "Current trip average speed");
        define("Ride", "odometer_km", "Total odometer");
        define("Ride", "driving_status", "Driving status (raw)");
        define("Ride", "stored_trips", "Stored trips");

        for (String[] setting : SETTINGS) define("Settings", setting[0], setting[1]);
        define("Settings", "scheduled_charge_time_raw", "Scheduled charge time (raw timestamp)");
        define("Settings", "charge_limit_percent", "Charge limit");
        define("Settings", "low_power", "Low Battery Mode (raw)");
        define("Settings", "timed_charging", "Timed charging setting (raw)");
        define("Settings", "auto_lock_time", "Lock duration setting (raw)");
        define("Settings", "speed_limit_eligibility", "Speed-limit eligibility flag (raw)");
        define("Settings", "weather", "Weather setting (raw)");
        define("Settings", "slope_assist", "Slope assistance setting (raw)");
        define("Settings", "long_range_mode", "Long range setting (raw)");
        define("Settings", "overspeed_control", "Overspeed control setting (raw)");
        define("Settings", "overspeed_alert", "Overspeed alert setting (raw)");
        define("Settings", "drive_speed_limit", "Drive speed limit setting (raw)");

        define("Device", "meter_version", "Meter firmware");
        define("Device", "controller_version", "Motor controller firmware");
        define("Device", "battery_version", "Battery management firmware");
        define("Device", "screen_version", "Screen firmware");
        define("Device", "uwb_version", "Proximity firmware");
        define("Device", "scooter_serial", "Scooter serial number");
        define("Device", "battery_serial_raw", "Battery serial response (raw)");

        define("Diagnostics", "warning_code", "Active warning code");
        define("Diagnostics", "lock_push_warning", "Locked movement warning (raw)");
        define("Diagnostics", "hide_e9", "E9 display flag (raw)");
        define("Diagnostics", "ride_report_format", "Ride report format");
        define("Diagnostics", "ride_read_raw", "Ride read acknowledgment (raw)");
        define("Diagnostics", "fault_history_raw", "Fault history response (raw)");
        for (int cmd : READ_COMMANDS) define("Diagnostics", statusKey(cmd), commandName(cmd));
    }

    private void define(String group, String key, String label) {
        readings.put(key, new Metric(group, key, label, null, null, -1, -1));
    }

    /** Display simulator only: never creates a raw frame, response status or real source opcode. */
    synchronized void putSimulationSample(String key, String value, Double number, long elapsedMs) {
        if (!readings.containsKey(key) || value == null || elapsedMs < 0
                || (number != null && !Double.isFinite(number)))
            throw new IllegalArgumentException("Invalid simulated telemetry");
        put(key, value, number, -1, elapsedMs);
    }

    /** Accepts the status byte followed by only this response's data, not a whole wire frame. */
    public synchronized void accept(int cmd, byte[] dataWithStatus, long elapsedMs) {
        if (!isTelemetryCommand(cmd)) return; // Never retain credentials or PIN responses.
        if (dataWithStatus == null || dataWithStatus.length == 0) {
            responseStatuses.put(cmd, -1);
            put(statusKey(cmd), "Missing response status", null, cmd, elapsedMs);
            return;
        }
        rawFrames.put(cmd, dataWithStatus.clone());
        int status = dataWithStatus[0] & 255;
        responseStatuses.put(cmd, status);
        int payloadLength = dataWithStatus.length - 1;
        put(statusKey(cmd), status == 0 ? "OK; " + payloadLength + " data bytes"
                : "Scooter returned status " + status, null, cmd, elapsedMs);
        if (status != 0) return;
        byte[] data = new byte[payloadLength];
        System.arraycopy(dataWithStatus, 1, data, 0, payloadLength);
        switch (cmd) {
            case 0x70: settings(data, cmd, elapsedMs); break;
            case 0x71:
                put("ride_read_raw", data.length == 0 ? "Acknowledged; ride data arrives separately"
                        : hex(data), null, cmd, elapsedMs);
                break;
            case 0x72: battery(data, cmd, elapsedMs); break;
            case 0x73: firmware(data, cmd, elapsedMs); break;
            case 0x74:
                if (data.length > 0) put("scooter_serial", text(data, 0, Math.min(17, data.length)),
                        null, cmd, elapsedMs);
                break;
            case 0x75:
                if (data.length > 0) put("battery_serial_raw", hex(data), null, cmd, elapsedMs);
                break;
            case 0x76: history(data, cmd, elapsedMs); break;
            case 0x79: signStates(data, cmd, elapsedMs); break;
            case 0x7b:
                put("fault_history_raw", data.length == 0 ? "Empty response" : hex(data),
                        null, cmd, elapsedMs);
                break;
            case 0x90: home(data, cmd, elapsedMs); break;
            case 0x91: ride(data, false, cmd, elapsedMs); break;
            case 0x92: ride(data, true, cmd, elapsedMs); break;
            default: break;
        }
    }

    public synchronized List<Metric> metrics() {
        return Collections.unmodifiableList(new ArrayList<Metric>(readings.values()));
    }

    public synchronized Double number(String key) {
        Metric metric = readings.get(key);
        return metric == null ? null : metric.numericValue;
    }

    /**
     * Display-only names observed on the UT5 MAX. The parser keeps the original mode code:
     * other NAVEE models assign different meanings to these same bytes, and these names
     * must never be used to infer which command or value changes a scooter's riding mode.
     */
    public static String drivingModeLabel(int productId, Double code) {
        if (code == null || code.isNaN() || code.isInfinite()) return "—";
        if (productId == 2538) {
            if (code == 11.0) return "WALK";
            if (code == 2.0) return "ECO";
            if (code == 3.0) return "SPORT";
        }
        String value = code == Math.rint(code) ? String.format(Locale.US, "%.0f", code)
                : Double.toString(code);
        return value + " (raw)";
    }

    /**
     * Whether Live Stats marks a reading RAW (once, as a chip): its value carries the
     * " (raw)" suffix or its full name says the field is undecoded.
     */
    public static boolean rawReading(String label, String value) {
        return (value != null && value.endsWith(RAW_SUFFIX)) || (label != null && label.contains("(raw"));
    }

    /**
     * Short Live Stats wording for a stored value. Stored values, history and exports keep
     * their full text (codes, "(raw)", "(estimate)"), so Diagnostics still has them.
     */
    public static String displayValue(String key, String value, Double number) {
        if (value == null || key == null) return value;
        switch (key) {
            case "remaining_range_km": value = value.replace(" km (estimate)", " km"); break;
            case "ride_read_raw":
                if (value.startsWith("Acknowledged;")) return "Acknowledged";
                break;
            case "ride_report_format":
                if (value.startsWith("v1:")) return "v1";
                if (value.startsWith("v0:")) return "v0";
                break;
            case "stored_trips":
                if (value.startsWith("Unknown history format ")) {
                    String rest = value.substring("Unknown history format ".length());
                    int records = rest.indexOf(" records");
                    return records < 0 ? value : "Unknown format " + rest.substring(0, records)
                            .replace("; ", " · ") + " records";
                }
                if (number != null && number >= 1 && value.startsWith("Trip ")) {
                    long trips = Math.round(number);
                    return trips + (trips == 1 ? " trip" : " trips");
                }
                break;
            case "display_units": case "warning_code": case "battery_status": case "lock_status":
                // "Imperial (0)", "None (0)", "Locked (1)", "Abnormal; code 3": the word only.
                if (!value.endsWith(RAW_SUFFIX)) value = value.replaceAll(" \\([0-9]+\\)$", "").replaceAll("; code [0-9]+$", "");
                break;
            default: break;
        }
        return value.endsWith(RAW_SUFFIX) ? value.substring(0, value.length() - RAW_SUFFIX.length()) : value;
    }

    /** Defensive copies of the latest accepted read/report payloads, INCLUDING response status. */
    public synchronized Map<Integer, byte[]> frames() {
        LinkedHashMap<Integer, byte[]> result = new LinkedHashMap<Integer, byte[]>();
        for (Map.Entry<Integer, byte[]> entry : rawFrames.entrySet()) {
            result.put(entry.getKey(), entry.getValue().clone());
        }
        return Collections.unmodifiableMap(result);
    }

    public synchronized Map<Integer, Integer> statuses() {
        return Collections.unmodifiableMap(new LinkedHashMap<Integer, Integer>(responseStatuses));
    }

    /** Start a fresh connection without applying sign-state flags from a previous scooter/session. */
    public synchronized void clear() {
        for (Map.Entry<String, Metric> entry : readings.entrySet()) {
            Metric old = entry.getValue();
            entry.setValue(new Metric(old.group, old.key, old.label, null, null, -1, -1));
        }
        rawFrames.clear();
        responseStatuses.clear();
        rawVoltage = rawCurrent = rawTemperature = null;
        voltageCmd = currentCmd = temperatureCmd = -1;
        voltageMs = currentMs = temperatureMs = -1;
        electricalBatch = 0;
        voltageBatch = currentBatch = -1;
        currentSignState = temperatureSignState = null;
    }

    private void settings(byte[] data, int cmd, long time) {
        for (int i = 0; i < SETTINGS.length; i++) {
            if (!has(data, i, 1)) continue;
            int value = data[i] & 255;
            if (i == 2) lock(value, cmd, time);
            else if (i == 7) put("display_units", value == 0 ? "Imperial (0)"
                    : value == 1 ? "Metric (1)" : value + " (raw)", (double) value, cmd, time);
            else raw(SETTINGS[i][0], value, cmd, time);
        }
        if (has(data, 27, 4)) raw("scheduled_charge_time_raw", unsigned(data, 27, 4, true), cmd, time);
        if (has(data, 31, 1)) percent("charge_limit_percent", data[31] & 255, cmd, time);
        String[] extra = {"low_power", "timed_charging", "auto_lock_time", "speed_limit_eligibility",
            "weather", "slope_assist", "long_range_mode", "overspeed_control", "overspeed_alert"};
        for (int i = 0; i < extra.length; i++) if (has(data, 32 + i, 1)) raw(extra[i], data[32 + i] & 255, cmd, time);
        // OEM deliberately does not decode byte 41. It remains available in frames().
        if (has(data, 42, 1)) raw("drive_speed_limit", data[42] & 255, cmd, time);
    }

    private void battery(byte[] data, int cmd, long time) {
        if (has(data, 0, 1)) batteryStatus(data[0] & 255, cmd, time);
        if (has(data, 1, 1)) percent("battery_percent", data[1] & 255, cmd, time);
        electrical(data, 2, 6, false, cmd, time);
        if (has(data, 10, 1)) percent("battery_health_percent", data[10] & 255, cmd, time);
        if (has(data, 11, 1) && time >= temperatureMs) {
            rawTemperature = (long) (data[11] & 255);
            temperatureCmd = cmd;
            temperatureMs = time;
            refreshSignedReadings();
        }
        if (has(data, 12, 1)) raw("charging_state", data[12] & 255, cmd, time);
        measured(data, 13, 2, false, "charge_cycles", 1, " charges", 0, cmd, time);
        measured(data, 15, 3, true, "energy_throughput_wh", 1, " Wh", 0, cmd, time);
        measured(data, 18, 2, true, "capacity_throughput_ah", 1, " Ah", 0, cmd, time);
        measured(data, 20, 2, true, "deep_discharge", 1, "", 0, cmd, time);
        if (has(data, 22, 1)) raw("self_discharge_raw", data[22] & 255, cmd, time);
        measured(data, 23, 2, true, "rated_capacity_ah", 100, " Ah", 2, cmd, time);
        if (has(data, 23, 2) && has(data, 10, 1) && (data[10] & 255) <= 100) {
            double capacity = unsigned(data, 23, 2, true) * (data[10] & 255) / 10000.0;
            quantity("health_adjusted_capacity_ah", capacity, " Ah", 2, cmd, time);
        }
        if (has(data, 25, 3)) {
            int year = data[25] & 255, month = data[26] & 255, day = data[27] & 255;
            String date = year <= 99 && month >= 1 && month <= 12 && day >= 1 && day <= 31
                    ? String.format(Locale.US, "20%02d-%02d-%02d", year, month, day)
                    : "Year " + year + ", month " + month + ", day " + day + " (raw)";
            put("battery_manufactured", date, null, cmd, time);
        }
        if (has(data, 28, 6)) {
            put("high_temperature_duration", String.format(Locale.US,
                    "%d y %d mo %d d %d h %d min %d s", data[28] & 255, data[29] & 255,
                    data[30] & 255, data[31] & 255, data[32] & 255, data[33] & 255),
                    null, cmd, time);
        }
        if (has(data, 34, 2)) raw("extreme_temperature_charge_raw", unsigned(data, 34, 2, true), cmd, time);
    }

    private void electrical(byte[] data, int voltageOffset, int currentOffset,
            boolean zeroMeansUnavailable, int cmd, long time) {
        long batch = ++electricalBatch;
        boolean suppliedVoltage = has(data, voltageOffset, 4)
                && unsigned(data, voltageOffset, 4, false) != 0;
        if (has(data, voltageOffset, 4) && time >= voltageMs
                && (!zeroMeansUnavailable || unsigned(data, voltageOffset, 4, false) != 0)) {
            rawVoltage = unsigned(data, voltageOffset, 4, false);
            voltageCmd = cmd;
            voltageMs = time;
            voltageBatch = batch;
            quantity("voltage_v", rawVoltage / 1000.0, " V", 3, cmd, time);
        }
        if (has(data, currentOffset, 4) && time >= currentMs
                && (!zeroMeansUnavailable || suppliedVoltage
                    || unsigned(data, currentOffset, 4, false) != 0)) {
            rawCurrent = unsigned(data, currentOffset, 4, false);
            currentCmd = cmd;
            currentMs = time;
            currentBatch = batch;
        }
        refreshSignedReadings();
    }

    private void refreshSignedReadings() {
        if (rawCurrent != null) {
            double amps = signedMagnitude(rawCurrent, 0x80000000L, currentSignState) / 1000.0;
            quantity("current_a", amps, " A", 3, currentCmd, currentMs);
            // Do not combine measurements from different reports into a new power reading.
            if (rawVoltage != null && voltageBatch == currentBatch) {
                quantity("power_w", rawVoltage / 1000.0 * amps, " W", 1, currentCmd, currentMs);
            }
        }
        if (rawTemperature != null) {
            quantity("temperature_c", signedMagnitude(rawTemperature, 0x80L, temperatureSignState),
                    " °C", 0, temperatureCmd, temperatureMs);
        }
    }

    private void signStates(byte[] data, int cmd, long time) {
        if (has(data, 0, 1) && readings.get("temperature_sign_state").updatedMs <= time) {
            temperatureSignState = data[0] & 255;
            raw("temperature_sign_state", temperatureSignState, cmd, time);
        }
        if (has(data, 1, 1) && readings.get("current_sign_state").updatedMs <= time) {
            currentSignState = data[1] & 255;
            raw("current_sign_state", currentSignState, cmd, time);
        }
        // Correct the sign without making an older sensor sample appear freshly measured.
        refreshSignedReadings();
    }

    private void home(byte[] data, int cmd, long time) {
        if (has(data, 0, 1)) {
            int warning = data[0] & 255;
            put("warning_code", warning == 0 ? "None (0)" : "Code " + warning,
                    (double) warning, cmd, time);
        }
        if (has(data, 1, 1)) raw("driving_mode", data[1] & 255, cmd, time);
        if (has(data, 2, 1)) percent("battery_percent", data[2] & 255, cmd, time);
        if (has(data, 3, 1)) batteryStatus(data[3] & 255, cmd, time);
        if (has(data, 4, 1)) raw("charging_state", data[4] & 255, cmd, time);
        if (has(data, 5, 1)) raw("lock_push_warning", data[5] & 255, cmd, time);
        measured(data, 6, 1, false, "remaining_range_km", 1, " km (estimate)", 0, cmd, time);
        // Report0 means 'not supplied'. 1/2 correspond to unlocked/locked.
        if (has(data, 7, 1) && (data[7] & 255) > 0) lock((data[7] & 255) - 1, cmd, time);
        // All-zero electrical fields in the optional home report mean absent. A valid
        // voltage paired with zero current is a real idle reading and clears old draw.
        electrical(data, 8, 12, true, cmd, time);
        if (has(data, 17, 1)) raw("hide_e9", data[17] & 255, cmd, time);
    }

    private void ride(byte[] data, boolean expanded, int cmd, long time) {
        put("ride_report_format", expanded ? "v1: tenths; optional 32-bit odometer"
                : "v0: whole units", expanded ? 1.0 : 0.0, cmd, time);
        if (has(data, 0, 1)) percent("battery_percent", data[0] & 255, cmd, time);
        if (has(data, 1, 1)) raw("driving_status", data[1] & 255, cmd, time);
        if (expanded) {
            measured(data, 2, 2, false, "speed_kmh", 10, " km/h", 1, cmd, time);
            measured(data, 4, 1, false, "remaining_range_km", 1, " km (estimate)", 0, cmd, time);
            measured(data, 5, 2, false, "trip_km", 10, " km", 1, cmd, time);
            measured(data, 7, 1, false, "trip_minutes", 1, " min", 0, cmd, time);
            measured(data, 8, 2, false, "maximum_speed_kmh", 10, " km/h", 1, cmd, time);
            measured(data, 10, 2, false, "average_speed_kmh", 10, " km/h", 1, cmd, time);
            // OEM selects the optional unsigned32 value when present AND nonzero.
            if (has(data, 14, 4) && unsigned(data, 14, 4, false) > 0) {
                measured(data, 14, 4, false, "odometer_km", 10, " km", 1, cmd, time);
            } else measured(data, 12, 2, false, "odometer_km", 10, " km", 1, cmd, time);
        } else {
            measured(data, 2, 1, false, "speed_kmh", 1, " km/h", 0, cmd, time);
            measured(data, 3, 1, false, "remaining_range_km", 1, " km (estimate)", 0, cmd, time);
            measured(data, 4, 1, false, "trip_km", 1, " km", 0, cmd, time);
            measured(data, 5, 1, false, "trip_minutes", 1, " min", 0, cmd, time);
            measured(data, 6, 1, false, "maximum_speed_kmh", 1, " km/h", 0, cmd, time);
            measured(data, 7, 1, false, "average_speed_kmh", 1, " km/h", 0, cmd, time);
            measured(data, 8, 1, false, "odometer_km", 1, " km", 0, cmd, time);
        }
    }

    private void firmware(byte[] data, int cmd, long time) {
        String[] names = {"meter_version", "controller_version", "battery_version", "screen_version", "uwb_version"};
        for (int i = 0; i < names.length; i++) {
            if (!has(data, i * 4, 4)) continue;
            StringBuilder value = new StringBuilder();
            boolean ascii = true;
            for (int j = 0; j < 4; j++) {
                int part = data[i * 4 + j] & 255;
                if (part < 32 || part > 126) ascii = false;
                if (j > 0) value.append('.');
                value.append((char) part);
            }
            put(names[i], ascii ? value.toString() : hexRange(data, i * 4, 4) + " (raw)",
                    null, cmd, time);
        }
    }

    private void history(byte[] data, int cmd, long time) {
        int records = data.length / 8;
        boolean valid = data.length % 8 == 0 || data.length % 8 == 1;
        if (!valid) {
            put(statusKey(cmd), "OK; incomplete history record (" + data.length + " data bytes)",
                    null, cmd, time);
            return;
        }
        int version = data.length % 8 == 0 ? 0 : data[data.length - 1] & 255;
        if (version != 0 && version != 1) {
            put("stored_trips", "Unknown history format " + version + "; " + records
                    + " records (raw bytes available in export)", null, cmd, time);
            return;
        }
        StringBuilder result = new StringBuilder();
        int count = 0;
        for (int i = 0; i < records; i++) {
            int offset = i * 8;
            long mileage = unsigned(data, offset + 2, 2, false);
            if (mileage == 0) continue; // OEM excludes unused records.
            if (count > 0) result.append('\n');
            count++;
            double scale = version == 1 ? 10.0 : 1.0;
            result.append("Trip ").append(count).append(": ")
                    .append(format(mileage / scale, version == 1 ? 1 : 0)).append(" km; ")
                    .append(unsigned(data, offset, 2, false)).append(" min; avg ")
                    .append(format(unsigned(data, offset + 4, 2, false) / scale, version == 1 ? 1 : 0))
                    .append(" km/h; max ")
                    .append(format(unsigned(data, offset + 6, 2, false) / scale, version == 1 ? 1 : 0))
                    .append(" km/h");
        }
        put("stored_trips", count == 0 ? "No stored trips reported" : result.toString(),
                (double) count, cmd, time);
    }

    private void batteryStatus(int value, int cmd, long time) {
        put("battery_status", value == 0 ? "Normal (0)" : "Abnormal; code " + value,
                (double) value, cmd, time);
    }

    private void lock(int value, int cmd, long time) {
        put("lock_status", value == 0 ? "Unlocked (0)" : value == 1 ? "Locked (1)"
                : value + " (raw)", (double) value, cmd, time);
    }

    private void percent(String key, int value, int cmd, long time) {
        if (value > 100) {
            Metric diagnostic = readings.get(statusKey(cmd));
            put(statusKey(cmd), diagnostic.value + "; invalid " + key + "=" + value,
                    null, cmd, time);
            return;
        }
        put(key, value + " %", (double) value, cmd, time);
    }

    private void measured(byte[] data, int offset, int length, boolean bigEndian, String key,
            double divisor, String unit, int decimals, int cmd, long time) {
        if (has(data, offset, length)) quantity(key, unsigned(data, offset, length, bigEndian)
                / divisor, unit, decimals, cmd, time);
    }

    private void quantity(String key, double value, String unit, int decimals, int cmd, long time) {
        put(key, format(value, decimals) + unit, value, cmd, time);
    }

    private void raw(String key, long value, int cmd, long time) {
        put(key, value + " (raw)", (double) value, cmd, time);
    }

    private void put(String key, String value, Double numeric, int cmd, long time) {
        Metric previous = readings.get(key);
        if (previous == null || previous.updatedMs > time) return;
        readings.put(key, new Metric(previous.group, key, previous.label, value, numeric, cmd, time));
    }

    private static boolean has(byte[] data, int offset, int length) {
        return offset >= 0 && length >= 0 && offset <= data.length && length <= data.length - offset;
    }

    private static long unsigned(byte[] data, int offset, int length, boolean bigEndian) {
        long result = 0;
        for (int i = 0; i < length; i++) {
            int index = bigEndian ? offset + i : offset + length - 1 - i;
            result = (result << 8) | (data[index] & 255L);
        }
        return result;
    }

    private static long signedMagnitude(long raw, long signBit, Integer separateSignState) {
        long magnitude = raw & (signBit - 1);
        return (raw & signBit) != 0 || (separateSignState != null && separateSignState == 1)
                ? -magnitude : magnitude;
    }

    private static String format(double value, int decimals) {
        // Sign-magnitude can encode negative zero; keep the displayed zero plain.
        return String.format(Locale.US, "%." + decimals + "f", value == 0.0 ? 0.0 : value);
    }

    private static String text(byte[] data, int offset, int length) {
        int end = offset + length;
        while (end > offset && data[end - 1] == 0) end--;
        String decoded = new String(data, offset, end - offset, StandardCharsets.UTF_8);
        for (int i = 0; i < decoded.length(); i++) {
            char character = decoded.charAt(i);
            if (character < 32 || character == 127 || character == '\ufffd') return hexRange(data, offset, length) + " (raw)";
        }
        return decoded.length() == 0 ? "Empty serial value" : decoded;
    }

    private static String hex(byte[] data) { return hexRange(data, 0, data.length); }

    private static String hexRange(byte[] data, int offset, int length) {
        final char[] alphabet = "0123456789abcdef".toCharArray();
        char[] result = new char[length * 2];
        for (int i = 0; i < length; i++) {
            int value = data[offset + i] & 255;
            result[i * 2] = alphabet[value >>> 4];
            result[i * 2 + 1] = alphabet[value & 15];
        }
        return new String(result);
    }

    private static boolean isTelemetryCommand(int cmd) {
        for (int allowed : READ_COMMANDS) if (cmd == allowed) return true;
        return false;
    }

    private static String statusKey(int cmd) { return String.format(Locale.US, "response_%02x", cmd); }

    private static String commandName(int cmd) {
        String name;
        switch (cmd) {
            case 0x70: name = "Scooter settings read"; break;
            case 0x71: name = "Ride read"; break;
            case 0x72: name = "Battery diagnostics read"; break;
            case 0x73: name = "Firmware versions read"; break;
            case 0x74: name = "Scooter serial read"; break;
            case 0x75: name = "Battery serial read"; break;
            case 0x76: name = "Ride history read"; break;
            case 0x79: name = "Battery sign states read"; break;
            case 0x7b: name = "Fault history read"; break;
            case 0x90: name = "Home telemetry report"; break;
            case 0x91: name = "Ride telemetry report v0"; break;
            case 0x92: name = "Ride telemetry report v1"; break;
            default: name = "Read"; break;
        }
        return name + String.format(Locale.US, " (0x%02X)", cmd);
    }
}
