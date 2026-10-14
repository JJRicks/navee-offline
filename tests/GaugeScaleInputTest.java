package local.navee.offlinelock;

import java.util.Locale;

public final class GaugeScaleInputTest {
    private static int checks;
    public static void main(String[] args) {
        Locale originalLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            GaugeScaleInput temperature = new GaugeScaleInput(23.5, 23.5 * 1.8 + 32);
            equal("74.3", temperature.text);
            exact(23.5, temperature.resolve("74.3", f -> (f - 32) / 1.8));
            exact(23.5, temperature.resolve(" 74.3000 ", f -> { throw new AssertionError("Unchanged input converted"); }));
            exact(100, temperature.resolve("212", f -> (f - 32) / 1.8));
            GaugeScaleInput speed = new GaugeScaleInput(50, 50 / 1.609344);
            equal("31.06856", speed.text);
            exact(50, speed.resolve(speed.text, mph -> mph * 1.609344));
            exact(16.09344, speed.resolve("10", mph -> mph * 1.609344));
            GaugeScaleInput conciseSpeed = new GaugeScaleInput(50, 50 / 1.609344, 1);
            equal("31.1", conciseSpeed.text);
            exact(50, conciseSpeed.resolve("31.10", mph -> { throw new AssertionError("Rounded speed changed"); }));
            exact(16.09344, conciseSpeed.resolve("10", mph -> mph * 1.609344));
            equal("50", new GaugeScaleInput(50, 50, 1).text);
            equal("0", new GaugeScaleInput(-.01, -.01, 1).text);
            exact(-.01, new GaugeScaleInput(-.01, -.01, 1).resolve("0", v -> v));
            equal("-40", new GaugeScaleInput(-40, -40).text);
            equal("0", new GaugeScaleInput(0, -0.0).text);
            equal("12.345679", new GaugeScaleInput(12.3456789, 12.3456789).text);
            equal("0.000000001", new GaugeScaleInput(1e-9, 1e-9).text);
            exact(1e-9, new GaugeScaleInput(1e-9, 1e-9).resolve("0.000000001", v -> v));
            for (String invalid : new String[] {"", "NaN", "Infinity", "-Infinity", "74,3", "abc"}) {
                try { temperature.resolve(invalid, v -> v); throw new AssertionError("Accepted " + invalid); }
                catch (NumberFormatException expected) { checks++; }
            }
            try { temperature.resolve("1e308", v -> v * 100); throw new AssertionError("Accepted overflow"); }
            catch (NumberFormatException expected) { checks++; }
        } finally { Locale.setDefault(originalLocale); }
        System.out.println("GaugeScaleInput tests passed: " + checks + " assertions");
    }
    private static void exact(double expected, double actual) {
        checks++;
        if (Double.doubleToLongBits(expected) != Double.doubleToLongBits(actual))
            throw new AssertionError("Expected exact " + expected + ", got " + actual);
    }
    private static void equal(String expected, String actual) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
