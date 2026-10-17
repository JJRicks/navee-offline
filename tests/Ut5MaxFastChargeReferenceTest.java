package local.navee.offlinelock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** A calibration replay is a regression check, not an independent accuracy claim. */
public final class Ut5MaxFastChargeReferenceTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void near(double expected, Double actual, double tolerance, String message) {
        check(actual != null && Math.abs(actual - expected) <= tolerance, message + ": " + actual);
    }
    private static Double estimate(double soc, double target, double amps, double watts) {
        return Ut5MaxFastChargeReference.estimate(soc, target, 15.3, amps, watts);
    }

    public static void main(String[] args) throws Exception {
        near(38.35527, estimate(72, 100, 10.09, 538.96744), .00001,
                "A full target always includes the measured 72-to-100 taper");
        near(15.3 * .56 / 10.06 * 60 + 38.35527, estimate(16, 100, 10.06, 479), .00001,
                "Bulk uses rated Ah/live current instead of multiplying the stock curve");
        near(15.3 * .72 / 10.09 * 60 + 38.35527, estimate(0, 100, 10.09, 430), .00001,
                "Below the observed 16 percent start is explicitly a bulk extrapolation");
        check(estimate(30, 100, 9, 450) > estimate(30, 100, 10, 500),
                "Less bulk current lengthens the estimate without deleting the finishing tail");
        near(estimate(72, 100, 10.09, 538.96744), estimate(71.999999, 100, 10.09, 538.96744), .00001,
                "Bulk and observed taper share the same continuous boundary");
        near(15.3 * .1 / 10.09 * 60, estimate(60, 70, 10.09, 522), .00001,
                "A cutoff below taper excludes the full-charge tail");
        near(15.3 * .12 / 10.09 * 60 + 38.35527 - 28.12753,
                estimate(60, 80, 10.09, 522), .00001,
                "A cutoff inside taper subtracts the future observed trajectory");
        near(26.11460 - 24.09965, estimate(81, 90, 4.35, 235.30020), .00001,
                "The measured rapid 81-to-90 gauge transition is preserved");
        near(21.07538 - 16.55172, estimate(95, 97, 3.06, 165.94074), .00001,
                "A near-full cutoff excludes the remaining finish rather than scaling a full ETA");
        near(12.02587, estimate(99, 100, 1.58, 85.92988), .00001,
                "First reaching 99 percent still has its measured twelve-minute dwell");
        near(4.98462, estimate(99, 100, .95, 51.72940), .00001,
                "Power locates progress during the 99 percent plateau");
        near(.12548, estimate(99, 100, .68, 37.04640), .00001,
                "A nearly completed taper does not promise zero before displayed full");
        check(estimate(99, 100, 1.58, 85.92988) > estimate(99, 100, .95, 51.72940),
                "The estimate advances even while displayed SOC stays 99 percent");
        check(estimate(99, 100, 85.92988 / 52, 85.92988) == null,
                "Similar power at an incompatible pack voltage cannot select the tail");
        check(estimate(99, 100, 85.92988 / 56, 85.92988) == null,
                "An out-of-trajectory voltage preserves fallback");

        for (double soc : new double[]{0, 16, 60, 71, 72, 80, 90}) {
            check(estimate(soc, 100, 2, 106) == null,
                    "Selecting fast manually cannot force stock power onto an incompatible fast stage: " + soc);
        }
        check(estimate(50, 100, 2, 500) == null, "Wrong-current evidence cannot pass on watts alone");
        check(estimate(50, 100, 20, 500) == null, "Wrong voltage/current pair refuses the reference");
        check(estimate(98, 100, 10, 540) == null, "Bulk power cannot masquerade as late taper");
        check(estimate(99, 100, .2, 10) == null, "Unobserved nearly-zero charging power preserves fallback");
        check(estimate(100, 100, .68, 37) == null, "Displayed full requires no extrapolated reference");
        check(estimate(95, 80, 3.06, 165.94074) == null, "A reached cutoff is left to the caller's zero result");
        check(estimate(50, Double.NaN, 10, 500) == null, "Unknown cutoff remains unknown");
        for (double invalid : new double[]{Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            check(estimate(invalid, 100, 10, 500) == null, "Nonfinite SOC");
            check(estimate(50, invalid, 10, 500) == null, "Nonfinite target");
            check(estimate(50, 100, invalid, 500) == null, "Nonfinite current");
            check(estimate(50, 100, 10, invalid) == null, "Nonfinite power");
            check(Ut5MaxFastChargeReference.estimate(50, 100, invalid, 10, 500) == null, "Nonfinite capacity");
        }
        check(Ut5MaxFastChargeReference.estimate(50, 100, 0, 10, 500) == null, "Zero rated capacity");
        check(Ut5MaxFastChargeReference.estimate(50, 100, -15.3, 10, 500) == null, "Negative capacity");
        check(Ut5MaxFastChargeReference.estimate(50, 100, Double.MAX_VALUE, 8.5, 440) == null,
                "Overflowing bulk math cannot leak an infinite ETA");
        check(!Ut5MaxFastChargeReference.powerCompatible(80, 260, Double.NaN), "Invalid tolerance");
        check(!Ut5MaxFastChargeReference.powerCompatible(80, 260, -1), "Negative tolerance");

        double previous = -1;
        for (int tenth = 601; tenth <= 1000; tenth++) {
            Double minutes = estimate(60, tenth / 10d, 10.09, 522);
            check(minutes != null && minutes >= previous, "A later fractional target cannot finish earlier");
            previous = minutes;
        }
        near(estimate(60, 72, 10.09, 522), estimate(60, 72.000001, 10.09, 522), .00001,
                "Target interpolation stays continuous where bulk meets taper");
        // A fractional SOC inside the rushed gauge section retains the interpolated time,
        // rather than silently truncating it or treating it as an unsupported integer stage.
        near((25.94648 + 25.61095) / 2, estimate(82.5, 100, 230.205935 / 54.1025, 230.205935), .00001,
                "Fractional finishing SOC interpolates measured boundaries");
        near((25.94648 - 25.61095) / 2, estimate(82, 82.5, 4.3, 232.62140), .00001,
                "Fractional target uses the same trajectory as fractional current SOC");
        previous = Double.POSITIVE_INFINITY;
        for (int step = 0; step <= 490; step++) {
            double watts = 86 - step / 10d;
            Double minutes = estimate(99, 100, watts / 54.44, watts);
            check(minutes != null && minutes <= previous && minutes > 0,
                    "Falling compatible taper power has a monotonic finite positive countdown");
            previous = minutes;
        }

        Path fixture = args.length == 0 ? Paths.get("tests/fixtures/ut5-max-fast-charge-calibration.csv") : Paths.get(args[0]);
        List<double[]> calibration = new ArrayList<>();
        for (String line : Files.readAllLines(fixture)) {
            if (line.trim().isEmpty() || line.startsWith("#")) continue;
            String[] fields = line.split(",");
            check(fields.length == 5, "Calibration includes only relative time and four electrical readings");
            double[] row = new double[5];
            for (int i = 0; i < row.length; i++) row[i] = Double.parseDouble(fields[i]);
            calibration.add(row);
        }
        check(calibration.size() == 533, "The full captured calibration sequence is replayed");
        double finish = calibration.get(calibration.size() - 1)[0];
        near(89.13243333, finish, .00001, "Reference finishes at first displayed 100 percent");
        List<Double> errors = new ArrayList<>();
        double totalError = 0;
        int rejected = 0;
        for (double[] row : calibration) {
            if (row[1] >= 100) continue;
            Double minutes = estimate(row[1], 100, row[3], row[4]);
            if (minutes == null) { rejected++; continue; }
            double error = Math.abs(minutes - (finish - row[0]));
            errors.add(error);
            totalError += error;
        }
        check(rejected == 0, "All still-charging calibration samples remain supported: " + rejected);
        Collections.sort(errors);
        double mae = totalError / errors.size();
        double p95 = errors.get((int)Math.floor((errors.size() - 1) * .95));
        double max = errors.get(errors.size() - 1);
        check(mae < .7 && p95 < 1.9 && max < 3.5,
                "Same-session calibration regression bounds: MAE=" + mae + ", p95=" + p95 + ", max=" + max);
        System.out.printf(Locale.US,
                "Fast reference CALIBRATION REPLAY (not independent validation): %d samples, MAE %.3f min, p95 %.3f min, max %.3f min%n",
                errors.size(), mae, p95, max);
        System.out.println("Ut5MaxFastChargeReference: " + checks + " checks passed");
    }
}
