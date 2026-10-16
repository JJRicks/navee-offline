package local.navee.offlinelock;

import java.util.*;

/** Local distance/SOC history, not an energy-capacity or battery-health measurement. */
public final class LearnedRangeEstimate {
    public static final int WINDOW_DAYS = 90, MIN_RIDES = 3;
    public static final double MIN_BATTERY_POINTS = 20, MIN_DISTANCE_KM = 3;
    private static final long DAY = 86400000L, ENDPOINT_MS = 3000;
    private LearnedRangeEstimate() {}

    /** Compact evidence from a full, completed journal; no raw route or owner data retained. */
    public static final class Evidence {
        public final RideStore.Summary summary;
        public final String rejection;
        private Evidence(RideStore.Summary summary, String rejection) {
            this.summary = summary; this.rejection = rejection;
        }
    }
    public static final class Result {
        public final String profileId, reason;
        public final int rides, rejectedRides;
        public final double distanceKm, batteryPoints;
        public final Double fullRangeKm, minimumTemperatureC, maximumTemperatureC,
                minimumBatteryPercent, maximumBatteryPercent;
        public final long computedWallMs;
        private Result(String profile, String problem, int count, int rejected, double km,
                double points, Double lowTemp, Double highTemp, Double lowSoc, Double highSoc, long now) {
            profileId = profile == null ? "" : profile; rides = count; rejectedRides = rejected;
            distanceKm = km; batteryPoints = points; minimumTemperatureC = lowTemp;
            maximumTemperatureC = highTemp; minimumBatteryPercent = lowSoc;
            maximumBatteryPercent = highSoc; computedWallMs = now;
            boolean enough = count >= MIN_RIDES && points >= MIN_BATTERY_POINTS && km >= MIN_DISTANCE_KM;
            fullRangeKm = problem == null && enough && Double.isFinite(km * 100 / points)
                    ? km * 100 / points : null;
            reason = problem != null ? problem : enough ? null : "More recorded riding needed";
        }
        /** Preserve missing/stale SOC at the display layer; never substitute zero for missing data. */
        public Double remainingKm(Double batteryPercent) {
            return fullRangeKm == null || !soc(batteryPercent) ? null : fullRangeKm * batteryPercent / 100;
        }
    }
    public static Result unavailable(String profile, String reason) {
        return new Result(profile, reason, 0, 0, 0, 0, null, null, null, null, System.currentTimeMillis());
    }
    public static Evidence inspect(RideStore.Summary s, List<RideStore.Point> fullPoints) {
        return new Evidence(s, rejection(s, fullPoints));
    }
    public static Result calculate(String profile, List<Evidence> evidence, long now) {
        Map<String,Evidence> unique = new LinkedHashMap<>();
        if (evidence != null) for (Evidence e : evidence) {
            if (e == null || e.summary == null || !Objects.equals(profile, e.summary.scooterId)) continue;
            Evidence old = unique.get(e.summary.id);
            if (old == null || e.summary.sampleCount > old.summary.sampleCount) unique.put(e.summary.id, e);
        }
        int count = 0, rejected = 0; double km = 0, points = 0;
        Double lowTemp = null, highTemp = null, lowSoc = null, highSoc = null;
        for (Evidence e : unique.values()) {
            RideStore.Summary s = e.summary;
            if (s.endWallMs < now - WINDOW_DAYS * DAY) continue;
            if (e.rejection != null || s.endWallMs > now + 300000) { rejected++; continue; }
            count++; km += s.distanceKm; points += s.batteryDropPercent;
            lowTemp = min(lowTemp, s.minimumTemperatureC); highTemp = max(highTemp, s.maximumTemperatureC);
            lowSoc = min(lowSoc, s.endBatteryPercent); highSoc = max(highSoc, s.startBatteryPercent);
        }
        return new Result(profile, null, count, rejected, km, points, lowTemp, highTemp, lowSoc, highSoc, now);
    }
    private static String rejection(RideStore.Summary s, List<RideStore.Point> points) {
        if (s == null || s.active) return "Recording is unfinished";
        if (s.recovered || s.gapCount > 0 || s.clockDiscontinuities > 0
                || s.endWallMs < s.startWallMs || Math.abs((s.endWallMs - s.startWallMs) - s.elapsedMs) > 5000) return "Recording has gaps or clock changes";
        if (s.odometerReset || !positive(s.distanceKm)) return "Odometer distance unavailable";
        if (!soc(s.startBatteryPercent) || !soc(s.endBatteryPercent)
                || s.batteryDropPercent == null || s.batteryDropPercent < 2)
            return "Battery drop below two points";
        if (s.elapsedMs < 60000 || s.movingMs < 30000 || s.distanceKm < .2)
            return "Too little recorded movement";
        if (s.speedCoverage < .95 || !positive(s.speedIntegratedDistanceKm)) return "Movement coverage below 95%";
        // Odometer is coarse on some models. Reject resets/large jumps, not normal 0.1 km rounding.
        if (Math.abs(s.distanceKm - s.speedIntegratedDistanceKm) > Math.max(.3, s.distanceKm * .25))
            return "Distance readings disagree";
        if (points == null || points.size() < 2 || points.size() != s.sampleCount)
            return "Full ride samples unavailable";
        RideStore.Point first = points.get(0), last = points.get(points.size() - 1);
        if (first.elapsedMs < 0 || first.elapsedMs > ENDPOINT_MS || last.elapsedMs > s.elapsedMs
                || s.elapsedMs - last.elapsedMs > ENDPOINT_MS
                || !same(first.batteryPercent, s.startBatteryPercent) || !same(last.batteryPercent, s.endBatteryPercent)
                || !same(first.odometerKm, s.startOdometerKm) || !same(last.odometerKm, s.endOdometerKm))
            return "Battery and distance endpoints missing";
        double lowest = first.batteryPercent; long previous = -1; int validSocSamples = 0;
        for (RideStore.Point p : points) {
            if (p.elapsedMs <= previous || p.segment != first.segment) return "Recording timeline changed";
            previous = p.elapsedMs;
            if (finite(p.chargingState) && p.chargingState != 0) return "Charging occurred during recording";
            if (soc(p.batteryPercent)) {
                validSocSamples++;
                // A single point of gauge rounding/recovery is tolerated; sustained rises are not.
                if (p.batteryPercent > lowest + 1.000001) return "Battery gauge rose";
                lowest = Math.min(lowest, p.batteryPercent);
            }
        }
        if (validSocSamples < points.size() * .95) return "Battery coverage below 95%";
        return null;
    }
    private static boolean same(Double a, Double b) { return finite(a) && finite(b) && Math.abs(a-b) < .000001; }
    private static boolean finite(Double v) { return v != null && Double.isFinite(v); }
    private static boolean positive(Double v) { return finite(v) && v > 0; }
    private static boolean soc(Double v) { return finite(v) && v >= 0 && v <= 100; }
    private static Double min(Double a, Double b) { if (!finite(b)) return a; if (a == null) return b; return Math.min(a,b); }
    private static Double max(Double a, Double b) { if (!finite(b)) return a; if (a == null) return b; return Math.max(a,b); }
}
