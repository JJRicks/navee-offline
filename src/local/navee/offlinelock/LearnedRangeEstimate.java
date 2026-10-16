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
    public enum Status { READY, INSUFFICIENT, UNAVAILABLE }
    public static final class Result {
        public final Status status;
        public final String profileId, reason;
        public final int rides, rejectedRides, unreadableRides;
        public final double distanceKm, batteryPoints;
        public final Double fullRangeKm, minimumTemperatureC, maximumTemperatureC,
                minimumBatteryPercent, maximumBatteryPercent;
        public final long computedWallMs;
        private Result(String profile, String problem, int count, int rejected, int unreadable, double km,
                double points, Double lowTemp, Double highTemp, Double lowSoc, Double highSoc, long now) {
            profileId = profile == null ? "" : profile; rides = count; rejectedRides = rejected; unreadableRides = unreadable;
            distanceKm = km; batteryPoints = points; minimumTemperatureC = lowTemp;
            maximumTemperatureC = highTemp; minimumBatteryPercent = lowSoc;
            maximumBatteryPercent = highSoc; computedWallMs = now;
            boolean enough = count >= MIN_RIDES && points >= MIN_BATTERY_POINTS && km >= MIN_DISTANCE_KM;
            fullRangeKm = problem == null && enough && Double.isFinite(km * 100 / points)
                    ? km * 100 / points : null;
            status = problem != null ? Status.UNAVAILABLE : fullRangeKm != null ? Status.READY : Status.INSUFFICIENT;
            reason = problem != null ? problem : fullRangeKm != null ? null : "More recorded riding needed";
        }
        /** Preserve missing/stale SOC at the display layer; never substitute zero for missing data. */
        public Double remainingKm(Double batteryPercent) {
            return fullRangeKm == null || !soc(batteryPercent) ? null : fullRangeKm * batteryPercent / 100;
        }
    }
    public static Result unavailable(String profile, String reason) {
        return new Result(profile, reason, 0, 0, 0, 0, 0, null, null, null, null, System.currentTimeMillis());
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
        // A partly unreadable archive must not masquerade as an empty/insufficient archive.
        // A valid model can still use the independently checked rides; retain the omitted count.
        boolean enough = count >= MIN_RIDES && points >= MIN_BATTERY_POINTS && km >= MIN_DISTANCE_KM;
        String problem = unreadable > 0 && !enough ? "Some recorded rides could not be read" : null;
        return new Result(profile, problem, count, rejected, unreadable, km, points, lowTemp, highTemp, lowSoc, highSoc, now);
    }
    /** O(1) validation state; every committed sample is checked, including hidden preview spikes. */
    static final class Inspector {
        private RideStore.Point first, last;
        private long count, validSocSamples, previous = -1;
        private double lowest;
        private String pointRejection;
        void accept(RideStore.Point p) {
            if (first == null) { first = p; lowest = p.batteryPercent == null ? Double.NaN : p.batteryPercent; }
            last = p; count++;
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
        }
        Evidence finish(RideStore.Summary summary) { return new Evidence(summary, rejection(summary)); }
        private String rejection(RideStore.Summary s) {
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
            if (count < 2 || count != s.sampleCount) return "Full ride samples unavailable";
            if (first.elapsedMs < 0 || first.elapsedMs > ENDPOINT_MS || last.elapsedMs > s.elapsedMs
                    || s.elapsedMs - last.elapsedMs > ENDPOINT_MS
                    || !same(first.batteryPercent, s.startBatteryPercent) || !same(last.batteryPercent, s.endBatteryPercent)
                    || !same(first.odometerKm, s.startOdometerKm) || !same(last.odometerKm, s.endOdometerKm))
                return "Battery and distance endpoints missing";
            if (pointRejection != null) return pointRejection;
            if (validSocSamples < count * .95) return "Battery coverage below 95%";
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
