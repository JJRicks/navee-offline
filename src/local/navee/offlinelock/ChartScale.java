package local.navee.offlinelock;

import java.util.Locale;

/** Viewing ranges only: never changes the recorded readings or their inspected values. */
public final class ChartScale {
    private ChartScale() { }
    public static final class Range {
        public final double min, max;
        Range(double min, double max) { this.min = min; this.max = max; }
        public double at(double fraction) { return min * (1 - fraction) + max * fraction; }
        public double fraction(double value) {
            // Halving before subtraction keeps even the largest finite signed range usable.
            double fraction = (value / 2 - min / 2) / (max / 2 - min / 2);
            return Math.max(0, Math.min(1, fraction));
        }
    }
    public static Range range(String metric, String unit, boolean absolute, double low, double high) {
        metric = metric == null ? "" : metric.toLowerCase(Locale.ROOT);
        unit = unit == null ? "" : unit.trim();
        if (!Double.isFinite(low) || !Double.isFinite(high)) { low = 0; high = 1; }
        if (low > high) { double swap = low; low = high; high = swap; }
        boolean percent = unit.equals("%") || metric.endsWith("_percent");
        if (percent) {
            if (absolute) return new Range(0, 100);
            low = Math.max(0, Math.min(100, low)); high = Math.max(0, Math.min(100, high));
        }
        double center = low / 2 + high / 2;
        double minimumSpan = unit.equals("V") ? .1 : unit.equals("W") ? Math.max(1, Math.abs(center) * .02) : 1;
        if (!absolute) {
            double half = Math.max(high / 2 - low / 2, minimumSpan / 2);
            double padded = finite(half * 1.24);
            double min = finite(center - padded), max = finite(center + padded);
            if (percent) { min = Math.max(0, min); max = Math.min(100, max); }
            // At extreme magnitudes a minimum span may be smaller than one ULP.
            if (min == max) { min = Math.nextDown(min); max = Math.nextUp(max); }
            return new Range(finite(min), finite(max));
        }
        boolean signed = metric.equals("power_w") || metric.equals("current_a") || unit.equals("W") || unit.equals("A");
        double floor = unit.equals("V") ? .1 : 1;
        if (signed) {
            double extent = ceiling(finite(Math.max(floor, Math.max(Math.abs(low), Math.abs(high))) * 1.08));
            return new Range(-extent, extent);
        }
        boolean temperature = metric.contains("temperature") || unit.equals("°C") || unit.equals("°F");
        boolean nonnegative = !temperature && (percent || unit.equals("V") || unit.equals("km/h") || unit.equals("mph")
                || unit.equals("km") || unit.equals("mi") || unit.equals("Ah") || unit.equals("Wh") || unit.equals("min")
                || metric.startsWith("speed_") || metric.startsWith("odometer_") || metric.contains("_count"));
        double min = nonnegative || low >= 0 ? 0 : -ceiling(finite(Math.abs(low) * 1.08));
        double max = high > 0 ? ceiling(finite(high * 1.08)) : 0;
        if (min == max) max = floor;
        return new Range(min, max);
    }
    /** Round outward to a readable 1/2/2.5/5/10 magnitude without overflowing. */
    private static double ceiling(double value) {
        if (value <= 0) return 1;
        double magnitude = Math.pow(10, Math.floor(Math.log10(value)));
        if (magnitude == 0) return value;
        double scaled = value / magnitude;
        double nice = scaled <= 1 ? 1 : scaled <= 2 ? 2 : scaled <= 2.5 ? 2.5 : scaled <= 5 ? 5 : 10;
        return finite(nice * magnitude);
    }
    private static double finite(double value) {
        return value == Double.POSITIVE_INFINITY ? Double.MAX_VALUE : value == Double.NEGATIVE_INFINITY ? -Double.MAX_VALUE : value;
    }
    /** Left edges for evenly spaced time ticks, or null when labels would touch/overflow. */
    public static float[] labelStarts(float width, float[] labelWidths, float gap) {
        if (!Float.isFinite(width) || width <= 0 || labelWidths == null || labelWidths.length < 2 || labelWidths.length > 6) return null;
        float[] starts = new float[labelWidths.length];
        for (int i = 0; i < starts.length; i++) {
            float size = labelWidths[i];
            if (!Float.isFinite(size) || size < 0 || size > width) return null;
            float tick = width * i / (starts.length - 1);
            starts[i] = Math.max(0, Math.min(width - size, tick - size / 2));
            if (i > 0 && starts[i] < starts[i - 1] + labelWidths[i - 1] + Math.max(0, gap)) return null;
        }
        return starts;
    }
}
