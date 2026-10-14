package local.navee.offlinelock;

import java.util.Map;

/** Synthetic display data has no wire evidence, credentials, moving scooter or persistent store. */
public final class ScooterSimulationTest {
    private static int checks;

    public static void main(String[] args) {
        completeSyntheticCatalog();
        boundedDynamicValues();
        localFeatureChanges();
        independentInstancesAndReset();
        rejectsMixingAndInvalidSamples();
        System.out.println("ScooterSimulation tests passed: " + checks + " assertions");
    }

    private static void completeSyntheticCatalog() {
        Telemetry telemetry = new Telemetry();
        ScooterSimulation simulation = new ScooterSimulation();
        simulation.tick(telemetry, 1000);
        for (Telemetry.Metric metric : telemetry.metrics()) {
            check(metric.value != null && !metric.value.isEmpty(), "Every catalog field has a sample");
            check(metric.sourceCmd == -1 && metric.updatedMs == 1000,
                    "Samples never impersonate a successfully received opcode");
            if (metric.numericValue != null) check(Double.isFinite(metric.numericValue), "Sample numbers are finite");
        }
        check(telemetry.frames().isEmpty() && telemetry.statuses().isEmpty(), "Simulation has no fabricated raw-frame or response evidence");
        check(telemetry.number("speed_kmh") == 0 && telemetry.number("driving_status") == 0,
                "Simulated scooter is parked for lock/control UI testing");
        check(telemetry.number("driving_mode") == 3, "Simulated scooter starts in Sport");
        check(telemetry.number("self_discharge_raw") == null && telemetry.number("response_70") == null,
                "Unknown raw formats never receive invented numeric measurements");
        check(metric(telemetry, "response_70").value.contains("Simulation"), "Unknown response text is clearly synthetic");
        for (ScooterCommands.Feature feature : ScooterCommands.catalog()) {
            if (feature.sensitive || feature.type == ScooterCommands.Type.TIME) continue;
            Integer value = simulation.featureValue(feature.key);
            check(value != null && feature.allows(value), "Every offered scalar sample stays inside the catalog domain");
        }
    }

    private static void boundedDynamicValues() {
        Telemetry telemetry = new Telemetry();
        ScooterSimulation simulation = new ScooterSimulation();
        double firstPower = 0;
        boolean changed = false;
        for (int second = 0; second <= 600; second++) {
            simulation.tick(telemetry, second * 1000L);
            double volts = telemetry.number("voltage_v"), amps = telemetry.number("current_a");
            double power = telemetry.number("power_w"), temperature = telemetry.number("temperature_c");
            check(volts >= 50.3 && volts <= 50.5 && amps >= .27 && amps <= .45,
                    "Parked electrical samples remain plausible and bounded");
            check(Math.abs(power - volts * amps) < .000001, "Sample power agrees with voltage/current");
            check(temperature >= 23 && temperature <= 24, "Temperature changes remain small");
            check(telemetry.number("speed_kmh") == 0 && telemetry.number("odometer_km") == 248.36,
                    "Animating live readings cannot create a fake ride or mileage");
            if (second == 0) firstPower = power;
            else changed |= Math.abs(power - firstPower) > .1;
        }
        check(changed, "Power actually varies so live widgets can be exercised");
        simulation.setCharging(true);
        simulation.tick(telemetry, 601000);
        check(simulation.charging() && telemetry.number("charging_state") == 1
                        && telemetry.number("current_sign_state") == 1
                        && telemetry.number("current_a") < 0 && telemetry.number("power_w") < 0,
                "Charging samples agree on charge state and signed current/power");
        simulation.setCharging(false);
        simulation.tick(telemetry, 602000);
        check(!simulation.charging() && telemetry.number("charging_state") == 0
                        && telemetry.number("power_w") > 0,
                "Leaving charging recovers normal parked samples");
    }

    private static void localFeatureChanges() {
        ScooterSimulation simulation = new ScooterSimulation();
        Telemetry telemetry = new Telemetry();
        simulation.set("lock", 1);
        simulation.set("charge_limit", 85);
        simulation.set("line_light", 1);
        simulation.set("line_pattern", 2);
        simulation.set("line_color", 1);
        simulation.set("line_boost_color", 0);
        simulation.set("hill_start_assist", 0);
        simulation.set("parking_assist", 1);
        simulation.set("hill_descent_control", 1);
        simulation.setChargeStart(1800000000L, -21600);
        simulation.tick(telemetry, 1000);
        check(simulation.featureValue("lock") == 1 && telemetry.number("lock_status") == 1,
                "Lock switch updates local readback without a real packet");
        check(telemetry.number("charge_limit_percent") == 85, "Charge limit sample follows its local feature");
        check(telemetry.number("ambient_light") == 130 && telemetry.number("eco_light") == 1,
                "Shared lighting readbacks compose independent feature bit fields");
        check(telemetry.number("slope_assist") == 6, "Assistance readbacks combine each sample setting");
        check(telemetry.number("scheduled_charge_time_raw") == 1799978400,
                "Local schedule stores the requested adjusted sample time");
        check(telemetry.frames().isEmpty() && telemetry.statuses().isEmpty(), "Local setting edits still create no wire evidence");
        rejects(() -> simulation.set("lock", 2));
        rejects(() -> simulation.set("charge_limit", 86));
        rejects(() -> simulation.set("speed_limit", 1));
        rejects(() -> simulation.set("pin", 0));
        rejects(() -> simulation.set("charge_start", 0));
        rejects(() -> simulation.set("unknown", 1));
        rejects(() -> simulation.setChargeStart(-1, 0));
        rejects(() -> simulation.setChargeStart(Long.MAX_VALUE, 86400));
        rejects(() -> simulation.setChargeStart(1800000000, 999999));
        int[] choices = simulation.featureChoices("range_algorithm");
        check(choices.length == ScooterCommands.find("range_algorithm").optionValues.length,
                "Preview exposes the full catalog choice list");
        choices[0] = 999;
        check(simulation.featureChoices("range_algorithm")[0] != 999, "Choice lists cannot modify simulator or catalog state");
        Map<String, Integer> features = simulation.features();
        boolean rejected = false;
        try { features.put("lock", 0); } catch (UnsupportedOperationException expected) { rejected = true; }
        check(rejected && simulation.featureValue("lock") == 1, "Exported feature snapshot is read-only");
    }

    private static void independentInstancesAndReset() {
        ScooterSimulation first = new ScooterSimulation(), second = new ScooterSimulation();
        first.set("lock", 1); first.setCharging(true);
        check(second.featureValue("lock") == 0 && !second.charging(), "Simulation state does not leak across sessions");
        Telemetry telemetry = new Telemetry();
        first.tick(telemetry, 1000);
        telemetry.clear();
        for (Telemetry.Metric metric : telemetry.metrics())
            check(metric.value == null && metric.numericValue == null && metric.updatedMs == -1,
                    "Clearing a simulation leaves no fake current telemetry");
        telemetry.accept(0x90, new byte[]{0, 0, 77, 10, 0, 0, 0, 0, 0}, 2000);
        check(metric(telemetry, "response_90").sourceCmd == 0x90,
                "Normal BLE decoding works again after simulated data is cleared");
    }

    private static void rejectsMixingAndInvalidSamples() {
        ScooterSimulation simulation = new ScooterSimulation();
        Telemetry telemetry = new Telemetry();
        telemetry.accept(0x70, new byte[]{0, 0, 3, 0}, 1000);
        rejects(() -> simulation.tick(telemetry, 2000));
        check(metric(telemetry, "response_70").sourceCmd == 0x70,
                "Simulation cannot overwrite telemetry that still has real wire evidence");
        telemetry.clear();
        rejects(() -> telemetry.putSimulationSample("not-in-catalog", "sample", 1.0, 1));
        rejects(() -> telemetry.putSimulationSample("power_w", "sample", Double.NaN, 1));
        rejects(() -> telemetry.putSimulationSample("power_w", null, 1.0, 1));
        rejects(() -> simulation.tick(telemetry, -1));
    }

    private static Telemetry.Metric metric(Telemetry telemetry, String key) {
        for (Telemetry.Metric metric : telemetry.metrics()) if (metric.key.equals(key)) return metric;
        throw new AssertionError("Missing metric " + key);
    }

    private static void rejects(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("Expected invalid simulation input to be rejected");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
