package local.navee.offlinelock;

/**
 * Experimental UT5 MAX stock-charger reference, available without personal charge recordings.
 * Relative measurements from an owner-observed finish; no identity, date or location metadata.
 * Below the observed finish, rated Ah and live current model the bulk stage. This is neither a
 * fixed-watt charger assumption nor a measured battery-capacity correction.
 */
public final class Ut5MaxStockChargeReference {
    public static final int PRODUCT_ID = 2538;
    private static final int FIRST_SOC = 89;
    // Remaining minutes at the first observed transition into each integer SOC, 89 through 100.
    // The rapid 91–95% rise and long 99% plateau are preserved rather than linearized.
    private static final double[] REMAINING = {
        51.28550, 46.07742, 42.88503, 42.38310, 42.04812, 41.53993,
        41.03345, 36.64575, 32.26502, 28.39457, 26.20978, 0
    };
    private static final double[] LOW_WATTS = {
        115.79724, 114.89688, 113.99566, 114.00636, 114.02990, 114.07056,
        111.62690, 110.71809, 109.88615, 109.16934, 36.98316
    };
    private static final double[] HIGH_WATTS = {
        119.18025, 115.94766, 115.04808, 114.01920, 114.05772, 114.09838,
        114.11764, 111.78574, 110.98512, 110.28590, 110.34945
    };
    // Approximately one-minute knots in the recorded 99% plateau: watts, minutes to displayed 100%.
    private static final double[][] TAPER = {
        {109.2424,26.20978}, {109.3175,25.19827}, {109.3987,24.18862},
        {109.4799,23.17985}, {109.5591,22.17167}, {109.6423,21.16277},
        {109.7256,20.15668}, {109.8108,19.14562}, {103.3768,18.13725},
        {96.3799,17.12933}, {90.4522,16.11947}, {83.9836,15.11347},
        {79.1378,14.10553}, {74.2828,13.09792}, {69.4221,12.09135},
        {66.1899,11.08400}, {62.4093,10.07528}, {58.6289,9.06895},
        {56.4689,8.06347}, {52.6797,7.05677}, {49.9762,6.04977},
        {47.8148,5.04113}, {45.1088,4.03262}, {42.9436,3.02452},
        {40.2323,2.01720}, {39.1514,1.01245}, {36.9832,0.00397}
    };

    private Ut5MaxStockChargeReference() {}

    /** Null means the reference does not apply; the original live estimate remains available. */
    public static Double estimate(double soc, double target, double ratedAh, double amps, double watts) {
        if (!finiteRange(soc,0,100) || !finiteRange(target,0,100) || target <= soc
                || !Double.isFinite(ratedAh) || ratedAh <= 0 || !Double.isFinite(amps) || amps < ChargingEstimate.MINIMUM_ETA_CURRENT_A
                || !Double.isFinite(watts) || watts < 1) return null;
        // Model recognition does not identify a charger. These broad observed-power bounds are
        // only a plausibility gate, including the lower-SOC captures (~95 W) and late bulk (~121 W).
        double currentRemaining;
        if (soc < FIRST_SOC) {
            if (watts < 95*.85 || watts > 121.5*1.15 || target <= FIRST_SOC) return null;
            currentRemaining = ratedAh*((FIRST_SOC-soc)/100d)/amps*60 + REMAINING[0];
        } else {
            if (soc != Math.floor(soc)) return null;
            int index = (int)soc-FIRST_SOC;
            if (index < 0 || index >= LOW_WATTS.length || watts < LOW_WATTS[index]*.85 || watts > HIGH_WATTS[index]*1.15) return null;
            if (soc < 99) {
                // Use the same first-arrival boundary as the bulk-stage anchor and cutoff
                // calculation. A midpoint here would cause an artificial jump upon reaching
                // 89%, or non-monotonic cutoff times if compensated only below the boundary.
                // Integer SOC still naturally updates in steps; this is not a timed countdown.
                currentRemaining = REMAINING[index];
            } else {
                double closest = Double.POSITIVE_INFINITY;
                for (double[] point : TAPER) closest = Math.min(closest,Math.abs(point[0]-watts));
                double sum=0, tolerance=Math.max(1.5,watts*.02); int count=0;
                for (double[] point : TAPER) if (Math.abs(point[0]-watts) <= closest+tolerance) { sum+=point[1]; count++; }
                if (count == 0) return null;
                currentRemaining=sum/count;
            }
        }
        double result = currentRemaining-boundaryRemaining(target);
        return Double.isFinite(result) ? Math.max(0,result) : null;
    }

    private static double boundaryRemaining(double target) {
        if (target >= 100) return 0;
        int index=(int)Math.floor(target)-FIRST_SOC;
        double fraction=target-Math.floor(target);
        return REMAINING[index]*(1-fraction)+REMAINING[index+1]*fraction;
    }

    private static boolean finiteRange(double value,double low,double high) {
        return Double.isFinite(value)&&value>=low&&value<=high;
    }
}
