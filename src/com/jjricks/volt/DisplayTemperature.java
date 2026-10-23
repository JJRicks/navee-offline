package com.jjricks.volt;

import java.util.Locale;

/** Display-only conversion; scooter telemetry, stored readings and commands stay in Celsius. */
public final class DisplayTemperature {
    private DisplayTemperature() {}

    /** Temperature quantities only, excluding exposure durations and raw sign/status fields. */
    public static boolean isTemperature(String key) {
        return "temperature_c".equals(key)
                || (key != null && key.endsWith("_temperature_c"));
    }

    public static String unit(String preference) {
        return "f".equals(preference) ? "°F" : "°C";
    }

    public static double fromCelsius(double celsius, String preference) {
        return "f".equals(preference) ? celsius * 1.8 + 32 : celsius;
    }

    public static double toCelsius(double displayed, String preference) {
        return "f".equals(preference) ? (displayed - 32) / 1.8 : displayed;
    }

    /** A value and its selected unit, or an em dash when no finite reading is available. */
    public static String format(Double celsius, String preference, int decimals) {
        if (celsius == null) return "—";
        double displayed = fromCelsius(celsius, preference);
        if (!Double.isFinite(displayed)) return "—";
        return number(displayed, decimals) + " " + unit(preference);
    }

    /** Both endpoints share one unit; a missing endpoint leaves the range unavailable. */
    public static String range(Double lowCelsius, Double highCelsius, String preference, int decimals) {
        if (lowCelsius == null || highCelsius == null) return "—";
        double low = fromCelsius(lowCelsius, preference);
        double high = fromCelsius(highCelsius, preference);
        if (!Double.isFinite(low) || !Double.isFinite(high)) return "—";
        return number(low, decimals) + "–" + number(high, decimals) + " " + unit(preference);
    }

    private static String number(double value, int decimals) {
        int precision = Math.max(0, Math.min(6, decimals));
        // Avoid a visually negative zero when the available display precision rounds to zero.
        if (Math.abs(value) < .5 * Math.pow(10, -precision)) value = 0;
        return String.format(Locale.US, "%." + precision + "f", value);
    }
}
