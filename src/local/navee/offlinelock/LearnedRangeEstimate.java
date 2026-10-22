package local.navee.offlinelock;

import java.util.*;

/**
 * Local range forecast from this phone's recordings (docs/validation/learned-range.md), not an
 * energy-capacity or battery-health measurement. With recorded power it splits the forecast into
 * battery energy left at the current gauge reading and energy per km ridden; otherwise it keeps
 * the older distance-per-gauge-point ratio.
 */
public final class LearnedRangeEstimate {
    public static final int WINDOW_DAYS = 90, MIN_RIDES = 3, BANDS = 10;
    public static final double MIN_BATTERY_POINTS = 20, MIN_DISTANCE_KM = 3;
    /** Pseudo-points pulling a thinly observed 10-point gauge band toward the pooled energy per point. */
    static final double BAND_PRIOR_POINTS = 8;
    static final int MIN_BAND_POINTS = 3;
    /** Below the lowest band seen, energy per point keeps falling with voltage; assume 10% less than any band seen. */
    static final double UNSEEN_FLOOR = .9;
    /** Likely range: actual ÷ forecast km over runs of consecutive rides of at least this distance, 5th to 95th percentile. */
    static final double STRETCH_KM = 6, LIKELY_TAIL = .05;
    static final int MIN_STRETCHES = 8;
    static final double MIN_ELECTRICAL_COVERAGE = .95;
    private static final long DAY = 86400000L, ENDPOINT_MS = 3000;
    /** Longest silent link + disconnect grace accepted after an automatic parked stop (about 25 s in practice). */
    static final long PARKED_TAIL_MS = 60000;
    private LearnedRangeEstimate() {}

    /** Compact evidence from a full, completed journal; no raw route or owner data retained. */
    public static final class Evidence {
        public final RideStore.Summary summary;
        /** Why the ride can't count as distance per gauge point (the older model); null when it can. */
        public final String rejection;
        /** Net energy for its odometer distance, corrected for missing power samples; null when unusable. */
        public final Double rideWh;
        /** Energy drawn in each fully counted gauge point, summed per 10-point band; empty without confirmed power. */
        final double[] bandWh;
        final int[] bandPoints;
        private Evidence(RideStore.Summary summary, String rejection, Double rideWh, double[] bandWh, int[] bandPoints) {
            this.summary = summary; this.rejection = rejection; this.rideWh = rideWh; this.bandWh = bandWh; this.bandPoints = bandPoints;
        }
        int gaugePoints() { int n = 0; for (int p : bandPoints) n += p; return n; }
    }
    public enum Status { READY, INSUFFICIENT, UNAVAILABLE }
    /** ENERGY: battery energy left ÷ energy per km. DISTANCE: km per gauge point (no power recorded). */
    public enum Model { ENERGY, DISTANCE }
    public static final class Result {
        public final Status status;
        public final Model model;
        public final String profileId, reason;
        public final int rides, rejectedRides, unreadableRides, energyRides, gaugePoints;
        public final double distanceKm, batteryPoints, energyKm;
        public final Double fullRangeKm, minimumTemperatureC, maximumTemperatureC,
                minimumBatteryPercent, maximumBatteryPercent, whPerKm, likelyLowFactor, likelyHighFactor;
        public final long computedWallMs;
        /** Battery energy left at each whole gauge reading 0..100 (ENERGY only). */
        private final double[] remainingWh;
        private Result(String profile, String problem, int count, int rejected, int unreadable, double km,
                double points, Double lowTemp, Double highTemp, Double lowSoc, Double highSoc, long now,
                int energyCount, double energyDistance, int counted, double[] remaining, Double perKm, Double lowFactor, Double highFactor) {
            profileId = profile == null ? "" : profile; rides = count; rejectedRides = rejected; unreadableRides = unreadable;
            distanceKm = km; batteryPoints = points; minimumTemperatureC = lowTemp;
            maximumTemperatureC = highTemp; minimumBatteryPercent = lowSoc;
            maximumBatteryPercent = highSoc; computedWallMs = now;
            energyRides = energyCount; energyKm = energyDistance; gaugePoints = counted;
            boolean energy = problem == null && remaining != null && perKm != null && perKm > 0;
            boolean enough = count >= MIN_RIDES && points >= MIN_BATTERY_POINTS && km >= MIN_DISTANCE_KM;
            model = energy ? Model.ENERGY : Model.DISTANCE;
            remainingWh = energy ? remaining : null; whPerKm = energy ? perKm : null;
            likelyLowFactor = energy ? lowFactor : null; likelyHighFactor = energy ? highFactor : null;
            fullRangeKm = energy ? (Double) (remaining[100] / perKm)
                    : problem == null && enough && Double.isFinite(km * 100 / points) ? km * 100 / points : null;
            status = problem != null ? Status.UNAVAILABLE : fullRangeKm != null ? Status.READY : Status.INSUFFICIENT;
            reason = problem != null ? problem : fullRangeKm != null ? null : "More recorded riding needed";
        }
        /** Preserve missing/stale SOC at the display layer; never substitute zero for missing data. */
        public Double remainingKm(Double batteryPercent) {
            if (fullRangeKm == null || !soc(batteryPercent)) return null;
            return remainingWh == null ? fullRangeKm * batteryPercent / 100 : energyAt(batteryPercent) / whPerKm;
        }
        /** Likely range: how far past 6 km stretches fell short of or beat this forecast, 10th to 90th percentile. */
        public Double likelyLowKm(Double batteryPercent) {
            Double km = likelyLowFactor == null ? null : remainingKm(batteryPercent); return km == null ? null : km * likelyLowFactor;
        }
        public Double likelyHighKm(Double batteryPercent) {
            Double km = likelyHighFactor == null ? null : remainingKm(batteryPercent); return km == null ? null : km * likelyHighFactor;
        }
        /** Linear between whole readings, each taken as the middle of its gauge point. */
        private double energyAt(double percent) { return energy(remainingWh, percent); }
    }
    public static Result unavailable(String profile, String reason) {
        return new Result(profile, reason, 0, 0, 0, 0, 0, null, null, null, null, System.currentTimeMillis(), 0, 0, 0, null, null, null, null);
    }
    public static Evidence inspect(RideStore.Summary s, List<RideStore.Point> fullPoints) {
        Inspector inspector = new Inspector();
        if (fullPoints != null) for (RideStore.Point point : fullPoints) inspector.accept(point);
        return inspector.finish(s);
    }
    public static Result calculate(String profile, List<Evidence> evidence, long now) {
        return calculate(profile, evidence, now, 0);
    }
    static Result calculate(String profile, List<Evidence> evidence, long now, int unreadable) {
        Map<String,Evidence> unique = new LinkedHashMap<>();
        if (evidence != null) for (Evidence e : evidence) {
            if (e == null || e.summary == null || !Objects.equals(profile, e.summary.scooterId)) continue;
            Evidence old = unique.get(e.summary.id);
            if (old == null || e.summary.sampleCount > old.summary.sampleCount) unique.put(e.summary.id, e);
        }
        int count = 0, rejected = 0, energyCount = 0; double km = 0, points = 0, energyKm = 0, energyWh = 0;
        Double lowTemp = null, highTemp = null, lowSoc = null, highSoc = null;
        double[] bandWh = new double[BANDS]; int[] bandPoints = new int[BANDS];
        List<Evidence> riding = new ArrayList<>();
        for (Evidence e : unique.values()) {
            RideStore.Summary s = e.summary;
            if (s.endWallMs < now - WINDOW_DAYS * DAY) continue;
            if (s.endWallMs > now + 300000) { rejected++; continue; }
            for (int b = 0; b < BANDS; b++) { bandWh[b] += e.bandWh[b]; bandPoints[b] += e.bandPoints[b]; }
            if (e.rideWh != null) { energyCount++; energyKm += s.distanceKm; energyWh += e.rideWh; riding.add(e); }
            if (e.rejection != null) { rejected++; continue; }
            count++; km += s.distanceKm; points += s.batteryDropPercent;
            lowTemp = min(lowTemp, s.minimumTemperatureC); highTemp = max(highTemp, s.maximumTemperatureC);
            lowSoc = min(lowSoc, s.endBatteryPercent); highSoc = max(highSoc, s.startBatteryPercent);
        }
        int counted = 0; for (int p : bandPoints) counted += p;
        boolean energy = counted >= MIN_BATTERY_POINTS && energyCount >= MIN_RIDES && energyKm >= MIN_DISTANCE_KM && energyWh > 0;
        double[] remaining = energy ? remainingWh(bandWh, bandPoints) : null;
        Double perKm = energy ? energyWh / energyKm : null, lowFactor = null, highFactor = null;
        if (energy) {
            List<Double> stretches = stretches(riding, remaining, perKm);
            if (stretches.size() >= MIN_STRETCHES) {
                // Route, riding and gauge surprises together; the forecast itself always stays inside.
                lowFactor = Math.min(1, quantile(stretches, LIKELY_TAIL)); highFactor = Math.max(1, quantile(stretches, 1 - LIKELY_TAIL));
            }
        }
        // A partly unreadable archive must not masquerade as an empty/insufficient archive.
        // A valid model can still use the independently checked rides; retain the omitted count.
        boolean enough = energy || count >= MIN_RIDES && points >= MIN_BATTERY_POINTS && km >= MIN_DISTANCE_KM;
        String problem = unreadable > 0 && !enough ? "Some recorded rides could not be read" : null;
        return new Result(profile, problem, count, rejected, unreadable, km, points, lowTemp, highTemp, lowSoc, highSoc, now,
                energyCount, energyKm, counted, remaining, perKm, lowFactor, highFactor);
    }
    /**
     * Energy left at each whole reading. Each band's energy per point is pulled toward the pooled
     * value by BAND_PRIOR_POINTS; bands below the lowest one seen use UNSEEN_FLOOR of the smallest.
     * A reading is the middle of its gauge point, so 100% holds 99.5 points.
     */
    static double[] remainingWh(double[] bandWh, int[] bandPoints) {
        double total = 0; int n = 0;
        for (int b = 0; b < BANDS; b++) { total += bandWh[b]; n += bandPoints[b]; }
        double pooled = total / n, smallest = pooled; Double[] band = new Double[BANDS]; int lowest = BANDS;
        for (int b = 0; b < BANDS; b++) if (bandPoints[b] >= MIN_BAND_POINTS) {
            band[b] = (bandWh[b] + BAND_PRIOR_POINTS * pooled) / (bandPoints[b] + BAND_PRIOR_POINTS);
            smallest = Math.min(smallest, band[b]); lowest = Math.min(lowest, b);
        }
        double[] point = new double[101];
        for (int level = 1; level <= 100; level++) {
            int b = Math.min(BANDS - 1, (level - 1) / 10);
            if (band[b] != null) point[level] = band[b];
            else if (b < lowest) point[level] = UNSEEN_FLOOR * smallest;
            else { // Unseen band above the lowest: the nearer seen band, the smaller on a tie.
                Double near = null; int distance = BANDS;
                for (int c = 0; c < BANDS; c++) if (band[c] != null && (Math.abs(c - b) < distance || Math.abs(c - b) == distance && band[c] < near)) { near = band[c]; distance = Math.abs(c - b); }
                point[level] = near;
            }
        }
        double[] remaining = new double[101]; double below = 0;
        for (int level = 1; level <= 100; level++) { remaining[level] = below + point[level] / 2; below += point[level]; }
        return remaining;
    }
    /** Actual ÷ forecast km for each run of consecutive rides, oldest first, that reaches STRETCH_KM. */
    private static List<Double> stretches(List<Evidence> riding, double[] remaining, double perKm) {
        List<Evidence> ordered = new ArrayList<>();
        for (Evidence e : riding) if (soc(e.summary.startBatteryPercent) && soc(e.summary.endBatteryPercent)
                && e.summary.startBatteryPercent >= e.summary.endBatteryPercent) ordered.add(e);
        Collections.sort(ordered, (a, b) -> Long.compare(a.summary.endWallMs, b.summary.endWallMs));
        List<Double> result = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            double km = 0, forecast = 0;
            for (int j = i; j < ordered.size() && km < STRETCH_KM; j++) {
                RideStore.Summary s = ordered.get(j).summary; km += s.distanceKm;
                forecast += (energy(remaining, s.startBatteryPercent) - energy(remaining, s.endBatteryPercent)) / perKm;
            }
            if (km < STRETCH_KM) break;
            if (forecast > 0) result.add(km / forecast);
        }
        return result;
    }
    private static double energy(double[] remaining, double percent) {
        int below = (int) Math.floor(percent), above = (int) Math.ceil(percent);
        return remaining[below] + (percent - below) * (remaining[above] - remaining[below]);
    }
    private static double quantile(List<Double> values, double q) {
        List<Double> sorted = new ArrayList<>(values); Collections.sort(sorted);
        double at = q * (sorted.size() - 1); int below = (int) Math.floor(at);
        return below + 1 >= sorted.size() ? sorted.get(below) : sorted.get(below) + (at - below) * (sorted.get(below + 1) - sorted.get(below));
    }
    /** O(1) validation state; every committed sample is checked, including hidden preview spikes. */
    static final class Inspector {
        private RideStore.Point first, last, complete, previousPoint;
        private long count, validSocSamples, previous = -1, completeCount, completeValidSoc;
        private double lowest;
        private String pointRejection;
        private boolean charged;
        // The gauge point being counted: entered by a one-point drop, closed by the next.
        private int level = -1; private boolean levelEntered;
        private double levelWh; private long levelMs, levelPowerMs, lastSocMs;
        private final double[] bandWh = new double[BANDS]; private final int[] bandPoints = new int[BANDS];
        void accept(RideStore.Point p) {
            if (first == null) { first = p; lowest = p.batteryPercent == null ? Double.NaN : p.batteryPercent; }
            last = p; count++;
            gauge(p);
            previousPoint = p;
            if (finite(p.chargingState) && p.chargingState != 0) charged = true;
            // Preserve the original first-failure order within each sample.
            if (pointRejection != null) return;
            if (p.elapsedMs <= previous || p.segment != first.segment) { pointRejection = "Recording timeline changed"; return; }
            previous = p.elapsedMs;
            if (finite(p.chargingState) && p.chargingState != 0) { pointRejection = "Charging occurred during recording"; return; }
            if (soc(p.batteryPercent)) {
                validSocSamples++;
                if (p.batteryPercent > lowest + 1.000001) { pointRejection = "Battery gauge rose"; return; }
                lowest = Math.min(lowest, p.batteryPercent);
            }
            if (finite(p.speedKmh) && soc(p.batteryPercent) && finite(p.odometerKm)) { complete = p; completeCount = count; completeValidSoc = validSocSamples; }
        }
        /**
         * Energy per fully counted gauge point. Any break discards the open point: a gap, charging, a
         * rise, a skipped point, or battery readings missing for longer than a sample interval, since
         * the drop that closes it could then have happened at any time in between.
         */
        private void gauge(RideStore.Point p) {
            RideStore.Point q = previousPoint;
            boolean broken = q == null || p.segment != q.segment || p.elapsedMs <= q.elapsedMs
                    || p.elapsedMs - q.elapsedMs > RideStore.MAX_INTEGRATION_INTERVAL_MS || finite(p.chargingState) && p.chargingState != 0;
            if (!broken && level >= 0) {
                long dt = p.elapsedMs - q.elapsedMs; levelMs += dt;
                if (finite(q.powerW) && finite(p.powerW)) { levelWh += (q.powerW + p.powerW) / 2 * dt / 3600000.0; levelPowerMs += dt; }
            }
            // Judge the gap since the last battery reading both while missing and when a reading returns.
            broken |= level >= 0 && p.elapsedMs - lastSocMs > RideStore.MAX_INTEGRATION_INTERVAL_MS;
            if (broken) { level = -1; levelEntered = false; }
            if (!soc(p.batteryPercent)) return;
            lastSocMs = p.elapsedMs;
            int now = (int) Math.ceil(p.batteryPercent - 1e-9);
            if (level < 0) { level = now; levelEntered = false; levelWh = 0; levelMs = 0; levelPowerMs = 0; return; }
            if (now == level) return;
            if (now == level - 1 && levelEntered && level >= 1 && levelMs > 0 && levelPowerMs >= levelMs * MIN_ELECTRICAL_COVERAGE) {
                int band = Math.min(BANDS - 1, (level - 1) / 10);
                bandWh[band] += levelWh * levelMs / levelPowerMs; bandPoints[band]++;
            }
            levelEntered = now == level - 1; level = now; levelWh = 0; levelMs = 0; levelPowerMs = 0;
        }
        Evidence finish(RideStore.Summary summary) {
            String rejection = rejection(summary);
            int sign = summary == null ? 0 : "POSITIVE_IS_DRAW".equals(summary.currentDirection) ? 1 : "NEGATIVE_IS_DRAW".equals(summary.currentDirection) ? -1 : 0;
            // Power is signed by the ride's one confirmed current direction; a change mid-ride is ambiguous.
            boolean signed = sign != 0 && summary.directionChanges == 0 && !summary.active;
            double[] wh = new double[BANDS]; int[] n = new int[BANDS];
            if (signed) for (int b = 0; b < BANDS; b++) {
                if (bandPoints[b] > 0 && sign * bandWh[b] > 0) { wh[b] = sign * bandWh[b]; n[b] = bandPoints[b]; }
            }
            return new Evidence(summary, rejection, signed ? rideWh(summary) : null, wh, n);
        }
        /**
         * Energy for the odometer distance, independent of the gauge: short gaps are allowed while
         * at least 95% of the ride has power readings, and the missing share is scaled in.
         */
        private Double rideWh(RideStore.Summary s) {
            if (s.recovered || s.clockDiscontinuities > 0 || s.odometerReset || charged || s.netConsumptionWh == null
                    || s.unconfirmedDirectionCoverageMs > 0 || !positive(s.distanceKm) || s.distanceKm < .2) return null;
            if (s.endWallMs < s.startWallMs || Math.abs((s.endWallMs - s.startWallMs) - s.elapsedMs) > 5000) return null;
            long elapsed = parkedTail(s) ? complete.elapsedMs : s.elapsedMs;
            if (elapsed < 60000 || s.movingMs < 30000 || !positive(s.speedIntegratedDistanceKm)
                    || Math.abs(s.distanceKm - s.speedIntegratedDistanceKm) > Math.max(.3, s.distanceKm * .25)) return null;
            double electrical = Math.min(1, s.electricalCoverageMs / (double) elapsed), speed = Math.min(1, s.speedCoverageMs / (double) elapsed);
            if (electrical < MIN_ELECTRICAL_COVERAGE || speed < MIN_ELECTRICAL_COVERAGE || s.netConsumptionWh <= 0) return null;
            return s.netConsumptionWh / electrical;
        }
        /** A ride Volt ended after a parked Bluetooth loss: its tail holds no distance or battery use. */
        private boolean parkedTail(RideStore.Summary s) {
            return s.automaticStop && complete != null && !s.recovered && s.clockDiscontinuities == 0
                    && s.gapCount == complete.segment + 1L && s.elapsedMs >= complete.elapsedMs
                    && s.elapsedMs - complete.elapsedMs <= PARKED_TAIL_MS;
        }
        private String rejection(RideStore.Summary s) {
            if (s == null || s.active) return "Recording is unfinished";
            // A ride Volt ended after a parked Bluetooth loss closes with the silent link, one
            // disconnect gap and the grace. Judge it at its last complete reading instead: that
            // tail holds no distance or battery use, and the journal itself is never shortened.
            boolean parkedTail = parkedTail(s);
            RideStore.Point end = parkedTail ? complete : last;
            long elapsed = parkedTail ? complete.elapsedMs : s.elapsedMs, gaps = parkedTail ? s.gapCount - 1 : s.gapCount;
            long counted = parkedTail ? completeCount : count, validSoc = parkedTail ? completeValidSoc : validSocSamples;
            double speedCoverage = !parkedTail ? s.speedCoverage : elapsed <= 0 ? 0 : Math.min(1, complete.speedCoverageMs / (double) elapsed);
            if (s.recovered || gaps > 0 || s.clockDiscontinuities > 0
                    || s.endWallMs < s.startWallMs || Math.abs((s.endWallMs - s.startWallMs) - s.elapsedMs) > 5000) return "Recording has gaps or clock changes";
            if (s.odometerReset || !positive(s.distanceKm)) return "Odometer distance unavailable";
            if (!soc(s.startBatteryPercent) || !soc(s.endBatteryPercent)
                    || s.batteryDropPercent == null || s.batteryDropPercent < 2)
                return "Battery drop below two points";
            if (elapsed < 60000 || s.movingMs < 30000 || s.distanceKm < .2)
                return "Too little recorded movement";
            if (speedCoverage < .95 || !positive(s.speedIntegratedDistanceKm)) return "Movement coverage below 95%";
            // Odometer is coarse on some models. Reject resets/large jumps, not normal 0.1 km rounding.
            if (Math.abs(s.distanceKm - s.speedIntegratedDistanceKm) > Math.max(.3, s.distanceKm * .25))
                return "Distance readings disagree";
            if (count < 2 || count != s.sampleCount) return "Full ride samples unavailable";
            if (first.elapsedMs < 0 || first.elapsedMs > ENDPOINT_MS || end.elapsedMs > elapsed
                    || elapsed - end.elapsedMs > ENDPOINT_MS
                    || !same(first.batteryPercent, s.startBatteryPercent) || !same(end.batteryPercent, s.endBatteryPercent)
                    || !same(first.odometerKm, s.startOdometerKm) || !same(end.odometerKm, s.endOdometerKm))
                return "Battery and distance endpoints missing";
            if (pointRejection != null) return pointRejection;
            if (validSoc < counted * .95) return "Battery coverage below 95%";
            return null;
        }
    }
    private static boolean same(Double a, Double b) { return finite(a) && finite(b) && Math.abs(a-b) < .000001; }
    private static boolean finite(Double v) { return v != null && Double.isFinite(v); }
    private static boolean positive(Double v) { return finite(v) && v > 0; }
    private static boolean soc(Double v) { return finite(v) && v >= 0 && v <= 100; }
    private static Double min(Double a, Double b) { if (!finite(b)) return a; if (a == null) return b; return Math.min(a,b); }
    private static Double max(Double a, Double b) { if (!finite(b)) return a; if (a == null) return b; return Math.max(a,b); }
}
