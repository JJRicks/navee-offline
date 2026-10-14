package local.navee.offlinelock;

/** Unit conversion preserves Celsius storage and does not invent unavailable readings. */
public final class DisplayTemperatureTest {
    private static int checks;

    public static void main(String[] args) {
        near(32, DisplayTemperature.fromCelsius(0, "f"), "Freezing in Fahrenheit");
        near(0, DisplayTemperature.toCelsius(32, "f"), "Freezing returns to Celsius");
        near(-40, DisplayTemperature.fromCelsius(-40, "f"), "Negative forty agrees in both units");
        near(100, DisplayTemperature.toCelsius(212, "f"), "Boiling returns to Celsius");
        for (double celsius : new double[] {-100, -40, -.25, 0, 23.5, 70, 120}) {
            near(celsius, DisplayTemperature.toCelsius(
                    DisplayTemperature.fromCelsius(celsius, "f"), "f"), "Round trip " + celsius);
        }
        for (String preference : new String[] {null, "", "unknown", "F", "c"}) {
            equal("°C", DisplayTemperature.unit(preference), "Unrecognized preference defaults to Celsius");
            near(23.5, DisplayTemperature.fromCelsius(23.5, preference), "Celsius display stays unchanged");
            near(23.5, DisplayTemperature.toCelsius(23.5, preference), "Celsius input stays unchanged");
        }
        equal("32 °F", DisplayTemperature.format(0d, "f", 0), "Formatted freezing reading");
        equal("74.3 °F", DisplayTemperature.format(23.5, "f", 1), "Decimal Fahrenheit reading");
        equal("23.5 °C", DisplayTemperature.format(23.5, "c", 1), "Decimal Celsius reading");
        equal("0.0 °C", DisplayTemperature.format(-.01, "c", 1), "No rounded negative zero");
        for (Double missing : new Double[] {null, Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY}) {
            equal("—", DisplayTemperature.format(missing, "f", 1), "Unavailable temperature stays unavailable");
            equal("—", DisplayTemperature.range(missing, 23d, "f", 1), "Unknown low endpoint");
            equal("—", DisplayTemperature.range(0d, missing, "c", 1), "Unknown high endpoint");
        }
        equal("—", DisplayTemperature.format(Double.MAX_VALUE, "f", 1), "Overflow is unavailable");
        equal("-40–32 °F", DisplayTemperature.range(-40d, 0d, "f", 0), "Range converts both endpoints");
        equal("0.0–23.5 °C", DisplayTemperature.range(0d, 23.5, "c", 1), "Range shares one unit");
        check(DisplayTemperature.isTemperature("temperature_c"), "Battery temperature quantity");
        check(DisplayTemperature.isTemperature("controller_temperature_c"), "Named Celsius temperature quantity");
        for (String key : new String[] {null, "", "high_temperature_duration", "temperature_sign_state",
                "extreme_temperature_charge_raw", "battery_percent", "counter_c"}) {
            check(!DisplayTemperature.isTemperature(key), "Non-temperature quantity remains unchanged: " + key);
        }
        System.out.println("DisplayTemperature tests passed: " + checks + " assertions");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void near(double expected, double actual, String message) {
        check(Math.abs(expected - actual) < .000001, message + ": " + actual);
    }

    private static void equal(String expected, String actual, String message) {
        check(expected.equals(actual), message + ": " + actual);
    }
}
