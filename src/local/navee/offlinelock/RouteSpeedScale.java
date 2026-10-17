package local.navee.offlinelock;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Speed route colours for one ride, without Android dependencies. Speeds stay in km/h here; only
 * labels convert to the user's units, so changing units never changes a colour.
 *
 * <p>Source: scooter speed when it is freshly matched (same GPS epoch, within the recorder's
 * freshness window) for at least half of the drawn points, or covers as many points as GPS does;
 * otherwise GPS speed. The choice applies to the whole ride, so the route never silently switches
 * between sensors. A point without the chosen source is no data, never zero.
 *
 * <p>Scale: 0 to the 95th percentile of moving (non-zero) points, capped at the upper quartile
 * plus three interquartile ranges when they spread so a lone spike cannot stretch the scale and
 * long stops cannot collapse it, then rounded up to a 5 km/h step with at least
 * 10 km/h of span. Values beyond the top clip to the top colour. The scale depends only on the
 * ride, so panning, zooming and expanding keep the same colours.
 */
final class RouteSpeedScale {
    static final String SCOOTER = "scooter", GPS = "GPS";
    static final double STEP_KMH = 5, MIN_TOP_KMH = 10, MAX_TOP_KMH = 120;
    /** Same blue → yellow sequential ramp as route power and elevation; gray marks missing data. */
    static final int LOW = 0xff23499b, HIGH = 0xffffd246, NO_DATA = 0xff808080;

    /** "scooter", "GPS", or null when the ride has no usable speed at all. */
    final String source;
    final double topKmh;
    private final Double[] values;

    private RouteSpeedScale(String source, double topKmh, Double[] values) {
        this.source = source; this.topKmh = topKmh; this.values = values;
    }

    static RouteSpeedScale of(List<PhoneRideStore.Point> points) {
        Double[] gps = new Double[points.size()], scooter = new Double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            PhoneRideStore.Point p = points.get(i);
            gps[i] = p.speedMps == null ? null : p.speedMps * 3.6;
            scooter[i] = p.scooterSpeedKmh;
        }
        return of(gps, scooter);
    }

    /** Parallel per-point arrays in km/h; null entries are unmeasured. */
    static RouteSpeedScale of(Double[] gpsKmh, Double[] scooterKmh) {
        int n = gpsKmh.length, gpsCount = count(gpsKmh), scooterCount = count(scooterKmh);
        String source = scooterCount > 0 && (2L * scooterCount >= n || scooterCount >= gpsCount) ? SCOOTER
                : gpsCount > 0 ? GPS : null;
        Double[] chosen = new Double[n];
        if (source != null) {
            Double[] from = SCOOTER.equals(source) ? scooterKmh : gpsKmh;
            for (int i = 0; i < n; i++) chosen[i] = usable(from[i]) ? Math.max(0, from[i]) : null;
        }
        return new RouteSpeedScale(source, top(chosen), chosen);
    }

    /** The chosen source's speed at drawn point index, or null when that point lacks it. */
    Double kmh(int index) { return index < 0 || index >= values.length ? null : values[index]; }

    /** Position on the ramp, clipped to 0..1. */
    double fraction(double kmh) { return Math.max(0, Math.min(1, kmh / topKmh)); }

    int color(Double kmh) {
        return kmh == null || !usable(kmh) ? NO_DATA : blend(LOW, HIGH, fraction(kmh));
    }

    int color(int index) { return color(kmh(index)); }

    /** "Speed · scooter" / "Speed · GPS" / "Speed". */
    String title() { return source == null ? "Speed" : "Speed · " + source; }

    /** Display label for a canonical km/h value; units are "mi", "km" or "dual". */
    static String label(double kmh, String units) {
        String metric = String.format(Locale.US, "%.0f km/h", kmh),
                imperial = String.format(Locale.US, "%.0f mph", kmh / RideStore.KM_PER_MILE);
        return "km".equals(units) ? metric : "dual".equals(units) ? imperial + " / " + metric : imperial;
    }

    static int blend(int a, int b, double fraction) {
        double f = Math.max(0, Math.min(1, fraction));
        int r = channel(a >> 16, b >> 16, f), g = channel(a >> 8, b >> 8, f), bl = channel(a, b, f);
        return 0xff000000 | r << 16 | g << 8 | bl;
    }

    private static int channel(int a, int b, double f) {
        a &= 0xff; b &= 0xff;
        return (int) (a + (b - a) * f);
    }

    private static boolean usable(Double v) { return v != null && !v.isNaN() && !v.isInfinite(); }

    private static int count(Double[] values) {
        int count = 0;
        for (Double v : values) if (usable(v)) count++;
        return count;
    }

    private static double top(Double[] values) {
        int moving = 0;
        for (Double v : values) if (v != null && v > 0) moving++;
        if (moving == 0) return MIN_TOP_KMH;
        double[] sorted = new double[moving];
        int k = 0;
        for (Double v : values) if (v != null && v > 0) sorted[k++] = v;
        Arrays.sort(sorted);
        double q1 = percentile(sorted, .25), q3 = percentile(sorted, .75), p95 = percentile(sorted, .95);
        // Identical quartiles (a held governor limit) would cap at q3 and clip every faster point.
        double robust = q3 > q1 ? Math.min(p95, q3 + 3 * (q3 - q1)) : p95;
        double top = Math.ceil(robust / STEP_KMH) * STEP_KMH;
        // A constant speed sits just below the top instead of on the clipped end.
        if (top <= robust) top += STEP_KMH;
        return Math.max(MIN_TOP_KMH, Math.min(MAX_TOP_KMH, top));
    }

    private static double percentile(double[] sorted, double p) {
        double at = p * (sorted.length - 1);
        int low = (int) Math.floor(at), high = (int) Math.ceil(at);
        return sorted[low] + (sorted[high] - sorted[low]) * (at - low);
    }
}
