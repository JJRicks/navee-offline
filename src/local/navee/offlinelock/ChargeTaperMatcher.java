package local.navee.offlinelock;

/** Locate an observed taper stage using paired live voltage and compatible charging power. */
final class ChargeTaperMatcher {
    private static final double POWER_TOLERANCE = .15;
    private static final double VOLTAGE_NEIGHBORHOOD = .01;
    private static final double MAX_VOLTAGE_DISTANCE = .10;
    private static final double MAX_AMBIGUOUS_MINUTES = 10;

    private ChargeTaperMatcher() {}

    /** Rows are battery-side watts, volts, and observed minutes to displayed 100%. */
    static Double estimate(double liveWatts, double liveVolts, double[][] stage) {
        return estimate(liveWatts,liveVolts,stage,POWER_TOLERANCE);
    }

    /** Only an admitted runtime stock reference may use the existing bounded 20% grace. */
    static Double estimate(double liveWatts, double liveVolts, double[][] stage, double powerTolerance) {
        if (!Double.isFinite(liveWatts) || liveWatts < 1 || !Double.isFinite(liveVolts)
                || liveVolts <= 0 || stage == null || stage.length < 2 || !Double.isFinite(powerTolerance)
                || powerTolerance < 0 || powerTolerance > .20) return null;
        double closest = Double.POSITIVE_INFINITY;
        for (double[] point : stage) {
            if (point == null || point.length != 3 || !Double.isFinite(point[0]) || point[0] < 1
                    || !Double.isFinite(point[1]) || point[1] <= 0
                    || !Double.isFinite(point[2]) || point[2] < 0) return null;
            if (compatible(liveWatts, point[0],powerTolerance)) closest = Math.min(closest, Math.abs(point[1] - liveVolts));
        }
        // A nearby wattage alone must not map an early low-current plateau to a later taper.
        // Refuse distant voltage rather than extending the trajectory to an unseen pack state.
        if (closest > MAX_VOLTAGE_DISTANCE + 1e-9) return null;
        double sum = 0, low = Double.POSITIVE_INFINITY, high = 0; int count = 0;
        for (double[] point : stage) {
            if (compatible(liveWatts, point[0],powerTolerance)
                    && Math.abs(point[1] - liveVolts) <= closest + VOLTAGE_NEIGHBORHOOD + 1e-9) {
                sum += point[2]; low = Math.min(low, point[2]); high = Math.max(high, point[2]); count++;
            }
        }
        if (count == 0 || high - low > MAX_AMBIGUOUS_MINUTES) return null;
        double result=sum/count;
        return Double.isFinite(result)?result:null;
    }

    private static boolean compatible(double liveWatts, double observedWatts, double tolerance) {
        return liveWatts >= observedWatts * (1 - tolerance)
                && liveWatts <= observedWatts * (1 + tolerance);
    }
}
