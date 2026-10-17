package local.navee.offlinelock;

/**
 * Experimental UT5 MAX high-speed-charger reference, requiring no personal recordings.
 *
 * Calibration: one owner-observed, uninterrupted 16–100% charge (533 samples, 89.13243
 * minutes). Battery-side current settled near 10.09 A; taper began around 72%. The gauge
 * moved particularly quickly through 81–90%, then held 99% for 12.02587 minutes. Only
 * relative electrical measurements and timing are retained here, with no owner identifiers,
 * dates, locations or credentials. The endpoint is first displayed 100%, not balancing or
 * charger shutoff. This is a provisional calibration, not independent accuracy validation.
 *
 * Bulk time uses rated Ah and live current. The measured taper is always included for a full
 * target, so entering it does not suddenly add an unanticipated tail. Below the observed 16%
 * start, this bulk calculation is an extrapolation; no measured capacity is inferred. SOC
 * boundaries interpolate fractional readings/cutoffs. Power and voltage locate progress in
 * the long 99% plateau, where SOC alone cannot produce a useful countdown.
 */
public final class Ut5MaxFastChargeReference {
    public static final int PRODUCT_ID = 2538;
    private static final int FIRST_TAPER_SOC = 72;
    private static final double TOLERANCE = .15;
    // Minutes from first arrival at each displayed SOC (72 through 100) to displayed 100%.
    private static final double[] REMAINING = {
        38.35527, 37.51730, 36.51087, 35.33795, 34.16495, 32.82483, 31.48192,
        29.97317, 28.12753, 26.11460, 25.94648, 25.61095, 25.44335, 25.27555,
        24.94025, 24.77203, 24.43658, 24.26772, 24.09965, 23.59592, 22.58755,
        22.08293, 21.57907, 21.07538, 18.89668, 16.55172, 14.20453, 12.02587, 0
    };
    // Observed battery-side envelopes for SOC 72 through 99. Admission allows modest variation;
    // the owning estimator must choose the charger family, especially when late powers overlap.
    private static final double[] LOW_WATTS = {
        516.76170, 478.97838, 441.10986, 405.19206, 368.10828, 336.22992,
        303.73850, 270.10000, 237.43754, 235.30020, 230.47452, 227.79047,
        225.65121, 219.20625, 218.13181, 212.21312, 210.60849, 208.46595,
        200.94102, 188.03236, 180.48933, 174.55942, 168.64286, 144.36618,
        121.13137, 102.72339, 87.01440, 37.04640
    };
    private static final double[] HIGH_WATTS = {
        539.26005, 511.01095, 474.27150, 434.76750, 400.45240, 364.92672,
        332.51981, 301.08564, 267.43365, 235.30020, 232.62140, 227.79047,
        225.65121, 222.95380, 218.13181, 215.45332, 210.60849, 208.46595,
        205.77000, 198.78922, 185.33664, 179.41855, 171.86789, 165.94074,
        141.66819, 120.04278, 100.56600, 85.92988
    };
    private static final double[] LOW_VOLTS = {
        53.416, 53.509, 53.590, 53.675, 53.752, 53.824, 53.893, 53.958,
        54.027, 54.092, 54.098, 54.107, 54.113, 54.115, 54.127, 54.134,
        54.141, 54.147, 54.150, 54.166, 54.192, 54.205, 54.217, 54.229,
        54.279, 54.318, 54.359, 54.386
    };
    private static final double[] HIGH_VOLTS = {
        53.495, 53.577, 53.663, 53.739, 53.817, 53.883, 53.950, 54.020,
        54.086, 54.092, 54.102, 54.107, 54.113, 54.125, 54.127, 54.136,
        54.141, 54.147, 54.162, 54.188, 54.201, 54.211, 54.226, 54.273,
        54.319, 54.354, 54.384, 54.480
    };
    // Monotonically falling power provides progress within 99%. One-minute samples plus the
    // final still-99% point avoid baking a zero ETA into a scooter that has not displayed 100%.
    // Columns: battery-side watts, pack volts, minutes to first displayed 100%.
    private static final double[][] FINAL_TAPER = {
        {85.92988, 54.386, 12.02587}, {79.41962, 54.397, 11.02060},
        {74.54033, 54.409, 10.01457}, {68.56794, 54.419, 9.00940},
        {64.77051, 54.429, 8.00307}, {59.88180, 54.438, 6.99758},
        {56.62072, 54.443, 5.99095}, {51.72940, 54.452, 4.98462},
        {47.92392, 54.459, 3.97955}, {45.20512, 54.464, 2.97362},
        {43.03130, 54.470, 1.96872}, {39.22344, 54.477, 0.96337},
        {37.04640, 54.480, 0.12548}
    };

    private Ut5MaxFastChargeReference() {}

    /** Null means unsupported/incompatible evidence; preserve the caller's live estimate. */
    public static Double estimate(double soc, double target, double ratedAh, double amps, double watts) {
        if (!finiteRange(soc, 0, 100) || !finiteRange(target, 0, 100) || target <= soc
                || !Double.isFinite(ratedAh) || ratedAh <= 0
                || !Double.isFinite(amps) || amps < .1 || !powerCompatible(soc, watts, TOLERANCE)) return null;
        double volts = watts / amps;
        if (!finiteRange(volts, 40, 56)) return null;
        if (soc < FIRST_TAPER_SOC) {
            // The wattage envelope alone cannot identify a 10 A charger at arbitrary voltage.
            if (!finiteRange(amps, 8.5, 11.6)) return null;
            double bulkTarget = Math.min(target, FIRST_TAPER_SOC);
            double bulkMinutes = ratedAh * ((bulkTarget - soc) / 100d) / amps * 60;
            double tailMinutes = target > FIRST_TAPER_SOC
                    ? REMAINING[0] - boundaryRemaining(target) : 0;
            return finiteEstimate(bulkMinutes + tailMinutes);
        }
        double lowVolts = envelope(LOW_VOLTS, soc), highVolts = envelope(HIGH_VOLTS, soc);
        if (volts < lowVolts - .15 || volts > highVolts + .15) return null;
        double currentRemaining = boundaryRemaining(soc);
        if (soc >= 99) {
            Double byPower = finalTaperRemaining(watts, volts);
            if (byPower == null) return null;
            currentRemaining = Math.min(currentRemaining, byPower);
        }
        // A fractional cutoff may already have been crossed inside an integer gauge plateau.
        return finiteEstimate(Math.max(0, currentRemaining - boundaryRemaining(target)));
    }

    /** Shape compatibility is not charger identification: stock/fast powers overlap near full. */
    static boolean powerCompatible(double soc, double watts, double tolerance) {
        if (!finiteRange(soc, 0, 100) || soc >= 100 || !Double.isFinite(watts) || watts < 1
                || !finiteRange(tolerance, 0, .25)) return false;
        // Before the measured taper, support 10 A bulk through the normal pack-voltage range.
        if (soc < FIRST_TAPER_SOC) return watts >= 420 * (1 - tolerance) && watts <= 550 * (1 + tolerance);
        return watts >= envelope(LOW_WATTS, soc) * (1 - tolerance)
                && watts <= envelope(HIGH_WATTS, soc) * (1 + tolerance);
    }

    private static double boundaryRemaining(double soc) {
        if (soc >= 100) return 0;
        int index = (int)Math.floor(soc) - FIRST_TAPER_SOC;
        double fraction = soc - Math.floor(soc);
        return REMAINING[index] * (1 - fraction) + REMAINING[index + 1] * fraction;
    }

    private static double envelope(double[] values, double soc) {
        int index = Math.min(values.length - 1, (int)Math.floor(soc) - FIRST_TAPER_SOC);
        if (index == values.length - 1) return values[index];
        double fraction = soc - Math.floor(soc);
        return values[index] * (1 - fraction) + values[index + 1] * fraction;
    }

    private static Double finalTaperRemaining(double watts, double volts) {
        double[] first = FINAL_TAPER[0], last = FINAL_TAPER[FINAL_TAPER.length - 1];
        double expectedVolts, remaining;
        if (watts >= first[0]) { expectedVolts = first[1]; remaining = first[2]; }
        else if (watts <= last[0]) { expectedVolts = last[1]; remaining = last[2]; }
        else {
            int index = 1;
            while (index < FINAL_TAPER.length - 1 && watts < FINAL_TAPER[index][0]) index++;
            double[] early = FINAL_TAPER[index - 1], late = FINAL_TAPER[index];
            double fraction = (early[0] - watts) / (early[0] - late[0]);
            expectedVolts = early[1] + fraction * (late[1] - early[1]);
            remaining = early[2] + fraction * (late[2] - early[2]);
        }
        return Math.abs(volts - expectedVolts) > .15 ? null : remaining;
    }

    private static Double finiteEstimate(double value) {
        return Double.isFinite(value) && value >= 0 ? value : null;
    }

    private static boolean finiteRange(double value, double low, double high) {
        return Double.isFinite(value) && value >= low && value <= high;
    }
}
