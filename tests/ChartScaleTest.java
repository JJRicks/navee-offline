package local.navee.offlinelock;

import java.util.Random;

public final class ChartScaleTest {
    private static int checks;
    public static void main(String[] args) {
        ChartScale.Range noise = ChartScale.range("voltage_v", "V", false, 51.999, 52.001);
        require(noise.max - noise.min >= .12 && noise.max - noise.min < .13, "Relative voltage exaggerates sensor noise");
        ChartScale.Range legacy = ChartScale.range(null, "W", false, 5, 15);
        equal(3.8, legacy.min); equal(16.2, legacy.max);
        for (String key : new String[] {"battery_percent", "battery_health_percent", "charge_limit_percent"}) {
            ChartScale.Range full = ChartScale.range(key, "%", true, 92, 100);
            equal(0, full.min); equal(100, full.max);
            ChartScale.Range relative = ChartScale.range(key, "%", false, 99, 100);
            require(relative.min > 98 && relative.max == 100, "Relative percentage lost zoom or physical bound");
            for (double value : new double[] {-10, 0, 50, 100, 110}) {
                ChartScale.Range range = ChartScale.range(key, "%", false, value, value);
                valid(range); require(range.min >= 0 && range.max <= 100, "Percent range escaped 0–100");
                equal(0, range.fraction(-100)); equal(1, range.fraction(200));
            }
        }
        for (String[] pair : new String[][] {{"power_w", "W"}, {"current_a", "A"}}) {
            for (double[] values : new double[][] {{-600, -300}, {10, 1400}, {-140, 250}, {0, 0}}) {
                ChartScale.Range range = ChartScale.range(pair[0], pair[1], true, values[0], values[1]);
                valid(range); equal(-range.min, range.max); equal(.5, range.fraction(0));
                require(range.min <= values[0] && range.max >= values[1], "Signed axis hid a reading");
            }
        }
        for (String[] pair : new String[][] {{"voltage_v", "V"}, {"speed_kmh", "mph"}, {"odometer_km", "mi"}, {"charge_count", ""}}) {
            ChartScale.Range range = ChartScale.range(pair[0], pair[1], true, 31, 52);
            valid(range); equal(0, range.min); require(range.max >= 52, "Grounded axis hid its maximum");
        }
        for (String unit : new String[] {"°C", "°F"}) {
            ChartScale.Range cold = ChartScale.range("temperature_c", unit, true, -30, -10);
            require(cold.min <= -30 && cold.max == 0, "Cold temperature should retain zero and negative values");
            ChartScale.Range mixed = ChartScale.range("temperature_c", unit, true, -20, 10);
            require(mixed.min <= -20 && mixed.max >= 10, "Signed temperature was grounded incorrectly");
            ChartScale.Range warm = ChartScale.range("temperature_c", unit, true, 20, 30);
            require(warm.min == 0 && warm.max >= 30, "Warm temperature should include zero");
        }
        for (double low : new double[] {-Double.MAX_VALUE, -1e300, -1, 0, 1, 1e300, Double.MAX_VALUE}) {
            for (double high : new double[] {low, Double.MAX_VALUE}) for (boolean absolute : new boolean[] {false, true}) {
                ChartScale.Range range = ChartScale.range("power_w", "W", absolute, low, high);
                valid(range); require(Double.isFinite(range.at(.5)), "Midpoint overflow");
                require(Double.isFinite(range.fraction(low)) && Double.isFinite(range.fraction(high)), "Projection overflow");
            }
        }
        valid(ChartScale.range("", "", false, Double.NaN, 4));
        valid(ChartScale.range("", "", true, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY));
        ChartScale.Range swapped = ChartScale.range("power_w", "W", false, 15, 5);
        equal(legacy.min, swapped.min); equal(legacy.max, swapped.max);
        Random random = new Random(20261014L);
        for (int i = 0; i < 1000; i++) {
            double low = random.nextDouble() * 100, high = low + random.nextDouble() * (100 - low);
            ChartScale.Range range = ChartScale.range("battery_percent", "%", false, low, high);
            valid(range); require(range.min >= 0 && range.min <= low && range.max >= high && range.max <= 100, "Percent crop hid valid data");
            equal(0, range.fraction(range.min)); equal(1, range.fraction(range.max));
            require(range.fraction(low) <= range.fraction(high), "Projection reversed data order");
        }
        require(ChartScale.labelStarts(100, new float[] {60, 60}, 8) == null, "Overlapping endpoint labels accepted");
        require(ChartScale.labelStarts(100, new float[] {101, 10}, 8) == null, "Overflowing label accepted");
        require(ChartScale.labelStarts(100, new float[] {Float.NaN, 10}, 8) == null, "Invalid label accepted");
        for (int count = 2; count <= 6; count++) for (int width = 80; width <= 900; width += 7) {
            float[] widths = new float[count]; for (int i = 0; i < count; i++) widths[i] = 30 + random.nextInt(80);
            float[] starts = ChartScale.labelStarts(width, widths, 12);
            if (starts == null) continue;
            for (int i = 0; i < count; i++) {
                require(starts[i] >= 0 && starts[i] + widths[i] <= width + .001, "Timestamp escaped plot width");
                if (i > 0) require(starts[i] >= starts[i-1] + widths[i-1] + 12, "Timestamp collision");
            }
        }
        require(ChartScale.labelStarts(600, new float[] {48, 48, 48, 48, 48, 48}, 12) != null, "Wide history lost intermediate labels");
        System.out.println("ChartScale tests passed: " + checks + " assertions");
    }
    private static void valid(ChartScale.Range range) {
        require(Double.isFinite(range.min) && Double.isFinite(range.max) && range.max > range.min, "Invalid scale " + range.min + ".." + range.max);
    }
    private static void equal(double expected, double actual) {
        require(Math.abs(expected - actual) <= 1e-9, "Expected " + expected + ", got " + actual);
    }
    private static void require(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
