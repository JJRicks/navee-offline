package local.navee.offlinelock;

/** Charging gate, current polarity, paired-frame integrity, cutoff and ETA regression cases. */
public final class ChargingEstimateTest {
    private static int checks;
    private static final long FRAME = 123456;

    private static ChargingEstimate.Value live(double number) {
        return new ChargingEstimate.Value(number, true, FRAME);
    }

    private static ChargingEstimate.Result estimate(ChargingEstimate.Value... values) {
        return ChargingEstimate.calculate(values[0], values[1], values[2], values[3], values[4], values[5]);
    }

    private static ChargingEstimate.Value[] nominal() {
        return new ChargingEstimate.Value[] {live(1), live(52), live(-2), live(50), live(14.5), live(80)};
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void near(double expected, Double actual, double tolerance, String message) {
        check(actual != null && Math.abs(expected - actual) <= tolerance, message + ": " + actual);
    }

    private static void noPowerOrTime(ChargingEstimate.Result result, String context) {
        check(result.inputWatts == null && result.inputAmps == null, context + " cannot invent charge power");
        noTime(result, context);
    }

    private static void noTime(ChargingEstimate.Result result, String context) {
        check(result.remainingMinutesToLimit == null && result.remainingMinutesToFull == null,
                context + " cannot produce charging times");
    }

    public static void main(String[] args) {
        ChargingEstimate.Result baseline = estimate(nominal());
        check(baseline.charging, "Fresh active state identifies charging");
        near(104, baseline.inputWatts, 0, "Battery-side V × |I|");
        near(2, baseline.inputAmps, 0, "Negative charging current uses magnitude");
        near(80, baseline.targetPercent, 0, "Confirmed cutoff retained");
        near(130.5, baseline.remainingMinutesToLimit, .00001, "Time from 50% to the 80% cutoff");
        near(217.5, baseline.remainingMinutesToFull, .00001, "Separate hypothetical time to 100%");
        check(baseline.status.contains("higher charge limit") && baseline.status.contains("taper"),
                "Time estimate explains cutoff and taper assumptions");
        check("Current rate".equals(baseline.label), "Short label names the current-rate source");

        ChargingEstimate.Value[] values = nominal(); values[2] = live(2);
        ChargingEstimate.Result positive = estimate(values);
        near(baseline.inputWatts, positive.inputWatts, 0, "Unconfirmed opposite current polarity gives same magnitude");
        near(baseline.remainingMinutesToLimit, positive.remainingMinutesToLimit, 0, "ETA is independent of current polarity");

        for (ChargingEstimate.Value state : new ChargingEstimate.Value[] {
                null, new ChargingEstimate.Value(null, true, FRAME), new ChargingEstimate.Value(1d, false, FRAME),
                live(Double.NaN), live(Double.POSITIVE_INFINITY), live(0), live(2), live(-1), live(1.1)}) {
            values = nominal(); values[0] = state; ChargingEstimate.Result result = estimate(values);
            check(!result.charging, "Only fresh exact state 1 is active charging");
            check(result.label != null && result.label.split(" ").length <= 4, "Inactive state keeps a short label");
            noPowerOrTime(result, "Inactive or unavailable charging state");
        }
        for (int index : new int[] {1, 2}) {
            for (ChargingEstimate.Value missing : new ChargingEstimate.Value[] {
                    null, new ChargingEstimate.Value(null, true, FRAME), new ChargingEstimate.Value(2d, false, FRAME),
                    live(Double.NaN), live(Double.POSITIVE_INFINITY), live(Double.NEGATIVE_INFINITY)}) {
                values = nominal(); values[index] = missing;
                noPowerOrTime(estimate(values), "Missing or stale electrical reading");
            }
        }
        for (long time : new long[] {FRAME - 1, FRAME + 1, 0, -1}) {
            values = nominal(); values[2] = new ChargingEstimate.Value(-2d, true, time);
            noPowerOrTime(estimate(values), "Different or absent source frame");
        }
        values = nominal(); values[1] = new ChargingEstimate.Value(52d, true, 0); values[2] = new ChargingEstimate.Value(-2d, true, 0);
        noPowerOrTime(estimate(values), "Matching zero timestamps still mean unknown source");
        for (double volts : new double[] {0, -52}) {
            values = nominal(); values[1] = live(volts); noPowerOrTime(estimate(values), "Invalid voltage");
        }
        values = nominal(); values[1] = live(Double.MAX_VALUE); values[2] = live(Double.MAX_VALUE);
        noPowerOrTime(estimate(values), "Power multiplication overflow");

        for (double amps : new double[] {0, -0d, .001, -.04999}) {
            values = nominal(); values[2] = live(amps); ChargingEstimate.Result result = estimate(values);
            near(Math.abs(amps), result.inputAmps, 0, "Low measured current is displayed honestly");
            near(Math.abs(amps) * 52, result.inputWatts, 0, "Low current is not replaced with nominal charger power");
            noTime(result, "Current below 0.05 A");
            check(ChargingEstimate.LOW_CURRENT.equals(result.label), "Low current has its own short label");
        }
        values = nominal(); values[2] = live(.05);
        near(8700, estimate(values).remainingMinutesToFull, .00001, "The minimum usable current threshold is inclusive");

        for (ChargingEstimate.Value capacity : new ChargingEstimate.Value[] {
                null, live(0), live(-14.5), live(Double.NaN), live(Double.POSITIVE_INFINITY),
                new ChargingEstimate.Value(14.5, false, FRAME)}) {
            values = nominal(); values[4] = capacity; ChargingEstimate.Result result = estimate(values);
            near(104, result.inputWatts, 0, "Measured charge power does not depend on rated capacity");
            noTime(result, "Unavailable or invalid capacity");
        }
        for (ChargingEstimate.Value soc : new ChargingEstimate.Value[] {
                null, live(-1), live(100.01), live(Double.NaN), live(Double.NEGATIVE_INFINITY),
                new ChargingEstimate.Value(50d, false, FRAME)}) {
            values = nominal(); values[3] = soc; noTime(estimate(values), "Missing, invalid or stale SOC");
        }
        for (ChargingEstimate.Value cutoff : new ChargingEstimate.Value[] {
                null, live(0), live(-80), live(101), live(Double.NaN), live(Double.POSITIVE_INFINITY),
                new ChargingEstimate.Value(80d, false, FRAME)}) {
            values = nominal(); values[5] = cutoff; ChargingEstimate.Result result = estimate(values);
            check(result.targetPercent == null && result.remainingMinutesToLimit == null, "Unknown cutoff is never guessed");
            near(217.5, result.remainingMinutesToFull, .00001, "Unknown cutoff still permits explicit hypothetical full time");
            check(result.status.contains("cutoff unknown") && result.status.contains("assumes charging continues"),
                    "Unknown-cutoff full time is identified as hypothetical");
        }
        values = nominal(); values[3] = live(90); ChargingEstimate.Result pastTarget = estimate(values);
        near(0, pastTarget.remainingMinutesToLimit, 0, "SOC already above cutoff never produces negative time");
        near(43.5, pastTarget.remainingMinutesToFull, .00001, "Hypothetical full time remains separate above cutoff");
        values = nominal(); values[3] = live(100); values[5] = live(100);
        near(0, estimate(values).remainingMinutesToFull, 0, "Already full has zero remaining time");
        near(0, estimate(values).remainingMinutesToLimit, 0, "Already at a 100% cutoff has zero time");
        values = nominal(); values[3] = live(0); values[5] = live(100);
        near(435, estimate(values).remainingMinutesToFull, .00001, "Full empty-to-full Ah estimate");
        near(435, estimate(values).remainingMinutesToLimit, .00001, "Full cutoff and 100% estimates agree");
        values = nominal(); values[4] = live(Double.MAX_VALUE); values[2] = live(.05);
        noTime(estimate(values), "ETA overflow");
        System.out.println("ChargingEstimate: " + checks + " assertions passed");
    }
}
