package com.jjricks.volt;

/**
 * Experimental UT5 MAX stock-charger reference, available without personal charge recordings.
 * Relative measurements from two owner-observed finishes; no identity, date or location metadata.
 * Below the observed finish, rated Ah and live current model the bulk stage, with a small
 * uncertainty allowance for full-charge planning. This is neither a
 * fixed-watt charger assumption nor a measured battery-capacity correction.
 */
public final class Ut5MaxStockChargeReference {
    public static final int PRODUCT_ID = 2538;
    private static final int FIRST_SOC = 89;
    // Stock chargers settle at different currents across observed scooters. Keep the
    // measured rate instead of imposing one fixed current. Ten minutes is a bounded,
    // empirical full-charge allowance, not a guarantee or a battery-capacity correction.
    // Fade both this allowance and the history floor into the unchanged 89% finish.
    static double bulkPlanningWeight(double soc,double ratedAh) {
        if (!finiteRange(soc,0,89) || !Double.isFinite(ratedAh)
                || Math.abs(ratedAh-15.3)>1.53) return 0;
        return Math.min(1,(FIRST_SOC-soc)/10d);
    }
    // Keep the existing admission envelope: a second finish refines timing, not charger detection.
    private static final double[] LOW_WATTS = {
        115.79724, 114.89688, 113.99566, 114.00636, 114.02990, 114.07056,
        111.62690, 110.71809, 109.88615, 109.16934, 36.98316
    };
    private static final double[] HIGH_WATTS = {
        119.18025, 115.94766, 115.04808, 114.01920, 114.05772, 114.09838,
        114.11764, 111.78574, 110.98512, 110.28590, 110.34945
    };
    // Each trajectory keeps its own first-arrival SOC boundaries and observed power intervals.
    // Taper rows are watts, volts, minutes to displayed 100%, sampled about once per minute.
    private static final Reference[] REFERENCES = {
        new Reference(
            new double[]{51.28550, 46.07742, 42.88503, 42.38310, 42.04812, 41.53993, 41.03345, 36.64575, 32.26502, 28.39457, 26.20978, 0.00000},
            new double[]{115.79724, 114.89688, 113.99566, 114.00636, 114.02990, 114.07056, 111.62690, 110.71809, 109.88615, 109.16934, 36.98316},
            new double[]{119.18025, 115.94766, 115.04808, 114.01920, 114.05772, 114.09838, 114.11764, 111.78574, 110.98512, 110.28590, 110.34945},
            new double[][]{
                {109.24242, 53.814, 26.20978},
                {109.31753, 53.851, 25.19827},
                {109.39873, 53.891, 24.18862},
                {109.47993, 53.931, 23.17985},
                {109.55910, 53.970, 22.17167},
                {109.64233, 54.011, 21.16277},
                {109.72556, 54.052, 20.15668},
                {109.81082, 54.094, 19.14562},
                {103.37684, 54.124, 18.13725},
                {96.37988, 54.146, 17.12933},
                {90.45221, 54.163, 16.11947},
                {83.98365, 54.183, 15.11347},
                {79.13784, 54.204, 14.10553},
                {74.28277, 54.221, 13.09792},
                {69.42208, 54.236, 12.09135},
                {66.18988, 54.254, 11.08400},
                {62.40935, 54.269, 10.07528},
                {58.62888, 54.286, 9.06895},
                {56.46888, 54.297, 8.06347},
                {52.67973, 54.309, 7.05677},
                {49.97624, 54.322, 6.04977},
                {47.81480, 54.335, 5.04113},
                {45.10884, 54.348, 4.03262},
                {42.94361, 54.359, 3.02452},
                {40.23232, 54.368, 2.01720},
                {39.15144, 54.377, 1.01245},
                {36.98316, 54.387, 0.00397},
            }),
        new Reference(
            new double[]{49.88757, 49.55062, 49.21435, 48.70993, 48.20602, 47.70243, 47.36630, 42.49520, 37.79102, 33.75782, 31.57453, 0.00000},
            new double[]{103.29142, 103.31082, 103.33022, 103.36320, 103.39424, 103.42334, 103.44468, 103.72598, 103.99758, 104.22262, 36.95936},
            new double[]{103.30500, 103.32440, 103.35544, 103.38260, 103.41364, 103.43498, 103.72016, 103.98400, 104.21680, 104.34096, 104.84924},
            new double[][]{
                {104.35454, 53.791, 31.57453},
                {104.41856, 53.824, 30.56622},
                {104.47870, 53.855, 29.55910},
                {104.54078, 53.887, 28.55065},
                {104.60480, 53.920, 27.54347},
                {104.66882, 53.953, 26.53638},
                {104.73866, 53.989, 25.52737},
                {104.80656, 54.024, 24.52005},
                {102.16017, 54.053, 23.51078},
                {96.25172, 54.074, 22.50185},
                {90.33030, 54.090, 21.49383},
                {86.56960, 54.106, 20.48590},
                {81.72875, 54.125, 19.47910},
                {77.96448, 54.142, 18.47082},
                {74.19098, 54.154, 17.46193},
                {70.96139, 54.169, 16.45415},
                {67.18568, 54.182, 15.44652},
                {64.49324, 54.196, 14.43908},
                {62.33920, 54.208, 13.42997},
                {58.55976, 54.222, 12.42223},
                {57.48698, 54.233, 11.41423},
                {54.79048, 54.248, 10.40587},
                {52.63220, 54.260, 9.39908},
                {51.55745, 54.271, 8.39065},
                {48.85470, 54.283, 7.38283},
                {47.78048, 54.296, 6.37438},
                {45.07315, 54.305, 5.36670},
                {43.99272, 54.312, 4.35930},
                {41.82948, 54.324, 3.35102},
                {40.20642, 54.333, 2.34312},
                {39.12624, 54.342, 1.33605},
                {38.04500, 54.350, 0.32827},
                {36.95936, 54.352, 0.16020},
            })
    };

    private Ut5MaxStockChargeReference() {}

    /** Null means the reference does not apply; the original live estimate remains available. */
    public static Double estimate(double soc, double target, double ratedAh, double amps, double watts) {
        return estimate(soc,target,ratedAh,amps,watts,false);
    }

    /** Wider bounds are only for an already admitted, fresh runtime estimate's brief grace. */
    static Double estimate(double soc, double target, double ratedAh, double amps, double watts, boolean edgeGrace) {
        double tolerance = edgeGrace ? .20 : .15;
        if (!finiteRange(soc,0,100) || !finiteRange(target,0,100) || target <= soc
                || !Double.isFinite(ratedAh) || ratedAh <= 0 || !Double.isFinite(amps)
                || amps < ChargingEstimate.MINIMUM_ETA_CURRENT_A || !powerCompatible(soc,watts,tolerance)) return null;
        if ((soc < FIRST_SOC && target <= FIRST_SOC) || (soc >= FIRST_SOC && soc != Math.floor(soc))) return null;
        double volts = watts / amps;
        if (!Double.isFinite(volts) || volts <= 0) return null;
        int stage = soc < FIRST_SOC ? 0 : (int)soc-FIRST_SOC;
        boolean anyCompatible = false;
        for (Reference reference : REFERENCES) if (reference.compatible(stage,watts,tolerance)) anyCompatible = true;
        double sum = 0; int count = 0;
        for (Reference reference : REFERENCES) {
            // At a supported 89% boundary, bulk and finishing calculations select the same
            // evidence. Earlier low-power bulk may use both tails without inventing 89% data.
            if (!reference.compatible(stage,watts,tolerance) && (soc >= FIRST_SOC || anyCompatible)) continue;
            Double remaining;
            if (soc < FIRST_SOC) remaining = ratedAh*((FIRST_SOC-soc)/100d)/amps*60 + reference.remaining[0]
                    + (target==100 ? 10*bulkPlanningWeight(soc,ratedAh) : 0);
            else if (soc < 99) remaining = reference.remaining[stage];
            else remaining = ChargeTaperMatcher.estimate(watts,volts,reference.taper,tolerance);
            if (remaining == null) continue;
            double estimate = Math.max(0,remaining-reference.boundaryRemaining(target));
            if (Double.isFinite(estimate)) { sum += estimate; count++; }
        }
        return count == 0 ? null : sum/count;
    }

    static boolean powerCompatible(double soc,double watts,double tolerance) {
        if (!finiteRange(soc,0,99.999) || !Double.isFinite(watts) || watts < 1) return false;
        if (soc < FIRST_SOC) return watts >= 95*(1-tolerance) && watts <= 121.5*(1+tolerance);
        if (soc != Math.floor(soc)) return false;
        int index=(int)soc-FIRST_SOC;
        return index>=0 && index<LOW_WATTS.length && watts>=LOW_WATTS[index]*(1-tolerance)
                && watts<=HIGH_WATTS[index]*(1+tolerance);
    }

    private static final class Reference {
        final double[] remaining, lowWatts, highWatts;
        final double[][] taper;
        Reference(double[] remaining,double[] lowWatts,double[] highWatts,double[][] taper) {
            this.remaining=remaining;this.lowWatts=lowWatts;this.highWatts=highWatts;this.taper=taper;
        }
        boolean compatible(int stage,double watts,double tolerance) {
            return stage >= 0 && stage < lowWatts.length && watts >= lowWatts[stage]*(1-tolerance)
                    && watts <= highWatts[stage]*(1+tolerance);
        }
        double boundaryRemaining(double target) {
            if (target >= 100) return 0;
            int index=(int)Math.floor(target)-FIRST_SOC;
            double fraction=target-Math.floor(target);
            return remaining[index]*(1-fraction)+remaining[index+1]*fraction;
        }
    }

    private static boolean finiteRange(double value,double low,double high) {
        return Double.isFinite(value)&&value>=low&&value<=high;
    }
}
