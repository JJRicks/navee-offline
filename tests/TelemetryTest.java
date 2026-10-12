package local.navee.offlinelock;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Meaningful parser fixtures and boundary checks; runs with a JDK without Android. */
public final class TelemetryTest {
    private static int assertions;

    public static void main(String[] args) {
        missingAndRejectedDataNeverFabricateReadings();
        batteryUnitsEndianAndSignsMatchOem();
        signStatesCorrectExistingReadingsWithoutChangingAge();
        rideVariantsAndUnsignedOdometer();
        settingsHaveExactOffsets();
        firmwareSerialAndHistory();
        snapshotsAreDefensiveAndExcludeSecrets();
        truncatedAndRandomPayloadsStayBounded();
        System.out.println("Telemetry tests passed: " + assertions + " assertions");
    }

    private static void missingAndRejectedDataNeverFabricateReadings() {
        Telemetry model = new Telemetry();
        for (Telemetry.Metric metric : model.metrics()) {
            check(metric.value == null && metric.numericValue == null && metric.sourceCmd == -1
                    && metric.updatedMs == -1, "Initial state is unavailable: " + metric.key);
        }
        model.accept(0x72, new byte[] {0}, 10);
        check(model.number("voltage_v") == null, "Empty successful response is not zero volts");
        check(model.number("battery_percent") == null, "Empty successful response is not zero charge");
        model.accept(0x72, response(new byte[] {0, 54}), 20);
        near(54, model.number("battery_percent"), "Real short response supplies SOC only");
        check(model.number("current_a") == null, "Truncated current remains unavailable");
        model.accept(0x72, new byte[] {7, 0, 99}, 30);
        near(54, model.number("battery_percent"), "Rejected response keeps previous SOC");
        check(metric(model, "battery_percent").updatedMs == 20, "Error does not refresh reading age");
        check(model.statuses().get(0x72) == 7, "Nonzero error status available in snapshot");
        check(metric(model, "response_72").value.contains("status 7"), "Error recorded visibly");
        model.accept(0x72, null, 40);
        near(54, model.number("battery_percent"), "Missing status keeps previous reading");
        check(model.statuses().get(0x72) == -1, "Missing status distinguished from success");
        model.accept(0x72, response(new byte[] {0, (byte) 255}), 50);
        near(54, model.number("battery_percent"), "Invalid percentage cannot erase valid reading");
        check(metric(model, "response_72").value.contains("invalid battery_percent=255"),
                "Invalid percentage reported in diagnostics");
        model.accept(0x72, response(new byte[] {0, 80}), 15);
        near(54, model.number("battery_percent"), "Older sample cannot overwrite newer SOC");
        model.accept(0x72, response(new byte[] {0, 0}), 60);
        near(0, model.number("battery_percent"), "An explicitly reported zero is retained");
    }

    private static void batteryUnitsEndianAndSignsMatchOem() {
        byte[] battery = batteryFixture();
        Telemetry model = new Telemetry();
        model.accept(0x72, response(battery), 120);
        near(67, model.number("battery_percent"), "Battery SOC");
        near(52.345, model.number("voltage_v"), "LE millivolts converted to V");
        near(-1.234, model.number("current_a"), "Current is sign magnitude, not two's complement");
        near(-64.59373, model.number("power_w"), "Same-frame V*A power");
        near(-5, model.number("temperature_c"), "Temperature is sign magnitude, not signed byte");
        near(97, model.number("battery_health_percent"), "Health percent");
        near(0x1234, model.number("charge_cycles"), "Charge cycles use LE16");
        near(0x010203, model.number("energy_throughput_wh"), "Energy uses BE24 and Wh");
        near(0x0405, model.number("capacity_throughput_ah"), "Capacity throughput uses BE16 and Ah");
        near(0x0607, model.number("deep_discharge"), "Deep discharge uses BE16");
        near(13.5, model.number("rated_capacity_ah"), "Rated capacity uses hundredths Ah");
        near(13.095, model.number("health_adjusted_capacity_ah"), "Health-adjusted full capacity follows OEM formula");
        near(0x0809, model.number("extreme_temperature_charge_raw"), "Extreme charging duration uses BE16 with unconfirmed unit");
        check("2026-04-09".equals(metric(model, "battery_manufactured").value), "Manufacture date components");
        check("1 y 2 mo 3 d 4 h 5 min 6 s".equals(metric(model, "high_temperature_duration").value),
                "High temperature duration preserves all six components");
        Telemetry.Metric current = metric(model, "current_a");
        check(current.sourceCmd == 0x72 && current.updatedMs == 120, "Sensor provenance and age retained");
        check(metric(model, "self_discharge_raw").label.contains("unit unknown"), "Unproven self-discharge units explicit");
        write(battery, 6, 0x80000000L, 4, false);
        battery[11] = (byte) 0x80;
        model.accept(0x72, response(battery), 140);
        near(0, model.number("current_a"), "Negative zero current normalizes to zero");
        check("0.000 A".equals(metric(model, "current_a").value), "No confusing negative zero text");
        near(0, model.number("temperature_c"), "Negative zero temperature normalizes to zero");
        write(battery, 2, 0xffffffffL, 4, false);
        write(battery, 6, 0x7fffffffL, 4, false);
        model.accept(0x72, response(battery), 160);
        near(4294967.295, model.number("voltage_v"), "Unsigned32 voltage does not wrap");
        near(2147483.647, model.number("current_a"), "Sign magnitude maximum does not wrap");
    }

    private static void signStatesCorrectExistingReadingsWithoutChangingAge() {
        Telemetry model = new Telemetry();
        byte[] battery = batteryFixture();
        write(battery, 6, 1800, 4, false);
        battery[11] = 7;
        model.accept(0x72, response(battery), 100);
        near(1.8, model.number("current_a"), "Positive raw current initially positive");
        model.accept(0x79, response(new byte[] {1, 1}), 200);
        near(-1.8, model.number("current_a"), "Separate current state can mark unsigned value negative");
        near(-7, model.number("temperature_c"), "Separate temperature state can mark value negative");
        near(-94.221, model.number("power_w"), "Power sign follows corrected current");
        check(metric(model, "current_a").updatedMs == 100, "State query does not refresh electrical sample age");
        check(metric(model, "temperature_c").updatedMs == 100, "State query does not refresh temperature age");
        model.accept(0x79, response(new byte[] {0, 0}), 150);
        near(-1.8, model.number("current_a"), "Out-of-order sign flags cannot undo later sign state");
        model.accept(0x79, response(new byte[] {0, 0}), 250);
        near(1.8, model.number("current_a"), "New sign state can restore positive value");
        write(battery, 6, 0x80000708L, 4, false);
        battery[11] = (byte) 0x87;
        model.accept(0x72, response(battery), 300);
        near(-1.8, model.number("current_a"), "Inline current sign wins even when separate sign says positive");
        near(-7, model.number("temperature_c"), "Inline temperature sign wins over separate positive state");
        byte[] voltageOnly = Arrays.copyOf(battery, 6);
        write(voltageOnly, 2, 40000, 4, false);
        model.accept(0x72, response(voltageOnly), 400);
        near(40, model.number("voltage_v"), "New partial report updates only voltage");
        check(metric(model, "power_w").updatedMs == 300, "Partial voltage cannot falsely refresh computed power");
        model.accept(0x72, response(battery), 450);
        double sameTimestampPower = model.number("power_w");
        model.accept(0x72, response(voltageOnly), 450);
        near(sameTimestampPower, model.number("power_w"), "Different packets in the same millisecond cannot form a new power reading");
        model.clear();
        model.accept(0x72, response(new byte[] {0, 60}), 500);
        check(model.number("current_a") == null, "Clearing connection drops old current and sign state");
        write(battery, 6, 1800, 4, false);
        battery[11] = 7;
        model.accept(0x72, response(battery), 600);
        near(1.8, model.number("current_a"), "Previous-session sign state is not reused");
    }

    private static void rideVariantsAndUnsignedOdometer() {
        Telemetry model = new Telemetry();
        model.accept(0x91, response(new byte[] {90, 1, 25, 30, 5, 18, 29, 19, (byte) 220}), 10);
        near(25, model.number("speed_kmh"), "v0 speed whole km/h");
        near(5, model.number("trip_km"), "v0 distance whole km");
        near(18, model.number("trip_minutes"), "v0 duration minutes");
        near(220, model.number("odometer_km"), "v0 unsigned odometer");
        byte[] ride = new byte[18];
        ride[0] = 89;
        ride[1] = 1;
        write(ride, 2, 247, 2, false);
        ride[4] = 29;
        write(ride, 5, 53, 2, false);
        ride[7] = 19;
        write(ride, 8, 303, 2, false);
        write(ride, 10, 171, 2, false);
        write(ride, 12, 65000, 2, false);
        write(ride, 14, 12345678, 4, false);
        model.accept(0x92, response(ride), 20);
        near(24.7, model.number("speed_kmh"), "v1 speed tenths km/h");
        near(29, model.number("remaining_range_km"), "Remaining range is unscaled in v1");
        near(5.3, model.number("trip_km"), "v1 distance tenths km");
        near(19, model.number("trip_minutes"), "v1 duration remains unscaled minutes");
        near(30.3, model.number("maximum_speed_kmh"), "v1 maximum speed");
        near(17.1, model.number("average_speed_kmh"), "v1 average speed");
        near(1234567.8, model.number("odometer_km"), "Extended odometer takes priority");
        write(ride, 14, 0xf1234567L, 4, false);
        model.accept(0x92, response(ride), 30);
        near(404562058.3, model.number("odometer_km"), "Unsigned32 odometer handles high bit");
        write(ride, 14, 0, 4, false);
        model.accept(0x92, response(ride), 40);
        near(6500, model.number("odometer_km"), "Zero extended odometer falls back to legacy16 as OEM does");
        model.accept(0x92, response(Arrays.copyOf(ride, 14)), 50);
        near(6500, model.number("odometer_km"), "Short v1 report includes legacy odometer");
        model.accept(0x71, response(new byte[] {1, 2, 3, 4}), 60);
        near(24.7, model.number("speed_kmh"), "Unparsed71 cannot invent speed");
        check(metric(model, "speed_kmh").updatedMs == 50, "71 does not refresh passive telemetry age");
        byte[] home = new byte[18];
        home[0] = 3; home[1] = 2; home[2] = 88; home[3] = 0;
        home[4] = 1; home[5] = 1; home[6] = 27; home[7] = 2;
        write(home, 8, 48500, 4, false);
        write(home, 12, 2000, 4, false);
        home[17] = 9;
        model.accept(0x90, response(home), 70);
        near(88, model.number("battery_percent"), "Home report SOC offset2");
        near(48.5, model.number("voltage_v"), "Home report electrical offset8");
        near(97, model.number("power_w"), "Home report power calculated from same packet");
        near(1, model.number("lock_status"), "Home lock state subtracts1");
        near(9, model.number("hide_e9"), "Home E9 offset17 not16");
        home[7] = 0;
        model.accept(0x90, response(Arrays.copyOf(home, 8)), 80);
        near(1, model.number("lock_status"), "Home lock zero means not supplied and cannot erase known lock");
        check(metric(model, "lock_status").updatedMs == 70, "Absent lock preserves timestamp");
        model.accept(0x90, response(new byte[16]), 90);
        near(48.5, model.number("voltage_v"), "Home zero electrical fields mean absent as in OEM");
        check(metric(model, "voltage_v").updatedMs == 70, "Home absence cannot refresh voltage age");
        write(home, 12, 0, 4, false);
        model.accept(0x90, response(home), 95);
        near(0, model.number("current_a"), "Home positive voltage plus zero current is a real idle reading");
        near(0, model.number("power_w"), "Idle home packet clears previous power draw");
        check(metric(model, "power_w").updatedMs == 95, "Real idle power is freshly measured");
        model.accept(0x72, response(new byte[10]), 100);
        near(0, model.number("current_a"), "Battery response can report real zero current");
        near(0, model.number("voltage_v"), "Explicit battery zero voltage retained separately from homeabsence");
    }

    private static void settingsHaveExactOffsets() {
        Telemetry model = new Telemetry();
        byte[] settings = new byte[43];
        for (int i = 0; i < settings.length; i++) settings[i] = (byte) i;
        settings[2] = 1;
        settings[7] = 0;
        write(settings, 27, 0xf1234567L, 4, true);
        settings[31] = 85;
        model.accept(0x70, response(settings), 200);
        near(1, model.number("lock_status"), "Settings lock offset2");
        check("Imperial (0)".equals(metric(model, "display_units").value), "Dashboard imperial code0");
        near(4045620583.0, model.number("scheduled_charge_time_raw"), "BE unsigned scheduled timestamp");
        near(85, model.number("charge_limit_percent"), "Charge limit offset31");
        near(32, model.number("low_power"), "Extended settings offset32");
        near(40, model.number("overspeed_alert"), "Extended settings offset40");
        near(42, model.number("drive_speed_limit"), "Drive speed limit skips reserved offset41");
        check(metric(model, "start_speed").label.contains("raw"), "Unconfirmed settings units stay raw");
    }

    private static void firmwareSerialAndHistory() {
        Telemetry model = new Telemetry();
        model.accept(0x73, response("12345678".getBytes(StandardCharsets.US_ASCII)), 10);
        check("1.2.3.4".equals(metric(model, "meter_version").value), "Firmware uses ASCII components");
        check("5.6.7.8".equals(metric(model, "controller_version").value), "Second firmware block offset4");
        check(metric(model, "battery_version").value == null, "Absent firmware block not empty version");
        model.accept(0x73, response(new byte[] {1, 2, 3, 4}), 20);
        check("01020304 (raw)".equals(metric(model, "meter_version").value), "Binary firmware does not display bogus ASCII");
        byte[] serial = "UT5SERIAL123456789extra".getBytes(StandardCharsets.UTF_8);
        model.accept(0x74, response(serial), 30);
        check(metric(model, "scooter_serial").value.length() == 17, "Scooter serial maximum matches OEM17bytes");
        model.accept(0x75, response(new byte[] {65, 66, 67}), 40);
        check("414243".equals(metric(model, "battery_serial_raw").value), "Unconfirmed battery serial format remains raw");
        byte[] history = new byte[17];
        write(history, 0, 14, 2, false); write(history, 2, 57, 2, false);
        write(history, 4, 182, 2, false); write(history, 6, 311, 2, false);
        history[16] = 1;
        model.accept(0x76, response(history), 50);
        check("Trip 1: 5.7 km; 14 min; avg 18.2 km/h; max 31.1 km/h"
                .equals(metric(model, "stored_trips").value), "Version1 history units and unused-record filtering");
        near(1, model.number("stored_trips"), "Stored history count");
        model.accept(0x76, response(Arrays.copyOf(history, 7)), 60);
        check(metric(model, "stored_trips").updatedMs == 50, "Malformed history cannot erase valid trips");
        check(metric(model, "response_76").value.contains("incomplete"), "Malformed history diagnosed");
        model.accept(0x7b, response(new byte[] {1, 2, (byte) 255}), 70);
        check("0102ff".equals(metric(model, "fault_history_raw").value), "Unknown fault history units/formats remain raw");
    }

    private static void snapshotsAreDefensiveAndExcludeSecrets() {
        Telemetry model = new Telemetry();
        byte[] response = response(new byte[] {0, 60});
        model.accept(0x72, response, 5);
        response[2] = 0;
        check(model.frames().get(0x72)[2] == 60, "Incoming payload cloned");
        model.frames().get(0x72)[2] = 0;
        check(model.frames().get(0x72)[2] == 60, "Outgoing payload cloned");
        final Map<Integer, byte[]> frames = model.frames();
        rejects(new Runnable() { public void run() { frames.clear(); }}, "Frame map immutable");
        final Map<Integer, Integer> statuses = model.statuses();
        rejects(new Runnable() { public void run() { statuses.clear(); }}, "Status map immutable");
        final List<Telemetry.Metric> metrics = model.metrics();
        rejects(new Runnable() { public void run() { metrics.clear(); }}, "Metric list immutable");
        for (int cmd : new int[] {0x30, 0x31, 0x51, 0x7a, 0x63, 0x6f}) {
            model.accept(cmd, new byte[] {0, 1, 2, 3, 4}, 100);
            check(!model.frames().containsKey(cmd), "Credential/control response excluded: " + cmd);
            check(!model.statuses().containsKey(cmd), "Credential status excluded: " + cmd);
        }
        check(metrics.get(0).value == null || metrics.get(0).updatedMs <= 5, "Earlier snapshots keep immutable readings");
        model.clear();
        check(model.frames().isEmpty() && model.statuses().isEmpty(), "Clear removes all session frames");
        check(model.number("battery_percent") == null, "Clear removes session readings");
        check(model.number("nonexistent") == null, "Unknown key is unavailable");
    }

    private static void truncatedAndRandomPayloadsStayBounded() {
        byte[] battery = batteryFixture();
        for (int length = 0; length <= battery.length; length++) {
            Telemetry model = new Telemetry();
            model.accept(0x72, response(Arrays.copyOf(battery, length)), length);
            check((model.number("voltage_v") != null) == (length >= 6), "Voltage requires all4bytes: " + length);
            check((model.number("current_a") != null) == (length >= 10), "Current requires all4bytes: " + length);
            check((model.number("power_w") != null) == (length >= 10), "Power requires both complete values: " + length);
            check((model.number("energy_throughput_wh") != null) == (length >= 18), "BE24 field complete: " + length);
            check((model.number("rated_capacity_ah") != null) == (length >= 25), "Capacity complete: " + length);
            check((metric(model, "battery_manufactured").value != null) == (length >= 28), "Date requires3components: " + length);
            check((metric(model, "high_temperature_duration").value != null) == (length >= 34), "Duration requires6components: " + length);
        }
        Random random = new Random(270426);
        int[] commands = {0x70, 0x71, 0x72, 0x73, 0x74, 0x75, 0x76, 0x79, 0x7b, 0x90, 0x91, 0x92};
        Telemetry model = new Telemetry();
        int catalogSize = model.metrics().size();
        for (int i = 0; i < 4096; i++) {
            byte[] data = new byte[random.nextInt(255) + 1];
            random.nextBytes(data);
            data[0] = 0;
            model.accept(commands[i % commands.length], data, i);
            check(model.metrics().size() == catalogSize, "Random input cannot grow catalog");
            check(model.frames().size() <= commands.length, "Random input cannot grow frame store");
        }
    }

    private static byte[] batteryFixture() {
        byte[] data = new byte[36];
        data[0] = 0; data[1] = 67;
        write(data, 2, 52345, 4, false);
        write(data, 6, 0x800004d2L, 4, false);
        data[10] = 97; data[11] = (byte) 0x85; data[12] = 1;
        write(data, 13, 0x1234, 2, false);
        write(data, 15, 0x010203, 3, true);
        write(data, 18, 0x0405, 2, true);
        write(data, 20, 0x0607, 2, true);
        data[22] = 9;
        write(data, 23, 1350, 2, true);
        data[25] = 26; data[26] = 4; data[27] = 9;
        for (int i = 28; i < 34; i++) data[i] = (byte) (i - 27);
        write(data, 34, 0x0809, 2, true);
        return data;
    }

    private static byte[] response(byte[] payload) {
        byte[] data = new byte[payload.length + 1];
        System.arraycopy(payload, 0, data, 1, payload.length);
        return data;
    }

    private static void write(byte[] data, int offset, long value, int length, boolean bigEndian) {
        for (int i = 0; i < length; i++) {
            int index = bigEndian ? offset + length - 1 - i : offset + i;
            data[index] = (byte) (value >>> (8 * i));
        }
    }

    private static Telemetry.Metric metric(Telemetry model, String key) {
        for (Telemetry.Metric metric : model.metrics()) if (key.equals(metric.key)) return metric;
        throw new AssertionError("Missing metric " + key);
    }

    private static void near(double expected, Double actual, String description) {
        check(actual != null && Math.abs(expected - actual) <= Math.max(1e-8, Math.abs(expected) * 1e-12),
                description + ": expected " + expected + ", actual " + actual);
    }

    private static void check(boolean condition, String description) {
        assertions++;
        if (!condition) throw new AssertionError(description);
    }

    private static void rejects(Runnable operation, String description) {
        boolean rejected = false;
        try { operation.run(); } catch (UnsupportedOperationException expected) { rejected = true; }
        check(rejected, description);
    }
}
