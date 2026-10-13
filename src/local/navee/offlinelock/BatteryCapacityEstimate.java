package local.navee.offlinelock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Experimental SOC-based energy equivalents from recorded rides, never a pack-health test. SOC is
 * not necessarily an energy fraction: voltage window, load, cold and gauge calibration affect this
 * extrapolation even with verified current direction. No missing energy is invented.
 */
public final class BatteryCapacityEstimate {
    public static final int DEFAULT_WINDOW_DAYS = 90;
    public static final double MIN_RIDE_DROP = 5, MIN_TOTAL_DROP = 20, MIN_COVERAGE = .95;
    private static final long DAY = 86400000L, ENDPOINT_TOLERANCE_MS = 3000;

    private BatteryCapacityEstimate() {}

    public static final class Ride {
        public final RideStore.Summary summary;
        public final List<RideStore.Point> points;

        public Ride(RideStore.Summary summary, List<RideStore.Point> points) {
            this.summary = summary;
            this.points =
                    points == null
                            ? Collections.emptyList()
                            : Collections.unmodifiableList(new ArrayList<>(points));
        }
    }

    public static final class Result {
        public final String profileId, status, method, limitations;
        public final Double estimatedUsableWh,
                throughputEquivalentWh,
                percentOfAdvertised,
                throughputPercentOfAdvertised,
                advertisedWh,
                minimumTemperatureC,
                maximumTemperatureC;
        public final int qualifiedRides, proxyRides, rejectedRides, windowDays;
        public final double batteryDropPoints,
                energyWh,
                proxyBatteryDropPoints,
                proxyEnergyWh,
                electricalCoverage;
        public final boolean includesActive;
        public final long computedWallMs;
        public final Map<String, Integer> rejectionReasons;

        private Result(
                String profileId,
                Double advertisedWh,
                int windowDays,
                long now,
                Pool confirmed,
                Pool proxy,
                Map<String, Integer> rejected,
                String unavailable) {
            this.profileId = profileId == null ? "" : profileId;
            this.advertisedWh = positive(advertisedWh) ? advertisedWh : null;
            this.windowDays = windowDays;
            computedWallMs = now;
            qualifiedRides = confirmed.count;
            proxyRides = proxy.count;
            rejectedRides = rejected.values().stream().mapToInt(Integer::intValue).sum();
            rejectionReasons = Collections.unmodifiableMap(new LinkedHashMap<>(rejected));
            batteryDropPoints = confirmed.drop;
            energyWh = confirmed.energy;
            proxyBatteryDropPoints = proxy.drop;
            proxyEnergyWh = proxy.energy;
            estimatedUsableWh =
                    confirmed.drop >= MIN_TOTAL_DROP
                            ? confirmed.energy * 100 / confirmed.drop
                            : null;
            throughputEquivalentWh =
                    proxy.drop >= MIN_TOTAL_DROP ? proxy.energy * 100 / proxy.drop : null;
            percentOfAdvertised = ratio(estimatedUsableWh, this.advertisedWh);
            throughputPercentOfAdvertised = ratio(throughputEquivalentWh, this.advertisedWh);
            Pool presented =
                    estimatedUsableWh != null
                            ? confirmed
                            : throughputEquivalentWh != null
                                    ? proxy
                                    : confirmed.drop >= proxy.drop ? confirmed : proxy;
            includesActive = presented.active;
            electricalCoverage =
                    presented.elapsed == 0 ? 0 : (double) presented.covered / presented.elapsed;
            minimumTemperatureC = presented.minTemperature;
            maximumTemperatureC = presented.maxTemperature;
            if (unavailable != null) status = unavailable;
            else if (estimatedUsableWh != null)
                status =
                        "Experimental energy equivalent"
                                + (includesActive ? " · ride in progress" : "");
            else if (throughputEquivalentWh != null)
                status =
                        "Throughput proxy · current direction unconfirmed"
                                + (includesActive ? " · ride in progress" : "");
            else if (confirmed.count + proxy.count > 0)
                status =
                        String.format(
                                Locale.US,
                                "Need 20 battery percentage points in one evidence group; %.1f"
                                    + " confirmed, %.1f unconfirmed so far",
                                confirmed.drop,
                                proxy.drop);
            else if (!rejected.isEmpty()) status = "No qualifying rides: " + mostCommon(rejected);
            else status = "Record a ride with at least a 5-point battery drop";
            method =
                    "100 × summed recorded Wh ÷ summed battery percentage-point drop. Confirmed net"
                        + " energy and unconfirmed absolute throughput use separate groups.";
            StringBuilder notes =
                    new StringBuilder(
                            "Experimental SOC-based energy equivalent, not measured pack capacity"
                                + " or health. The battery gauge may track charge rather than"
                                + " energy: rounding, calibration, voltage range, cold and riding"
                                + " load can bias this result. Unconfirmed absolute throughput"
                                + " counts both draw and return and can overstate depletion. Record"
                                + " riding without charger input. Use at least 5 percentage points"
                                + " per ride and 20 combined; each ride needs 1 minute, observed"
                                + " movement, 95% electrical coverage, aligned end readings and no"
                                + " recording gaps or direction changes. Visible gauge rebounds"
                                + " over 1 point are rejected; sampled trends can miss brief"
                                + " changes.");
            if (presented.count > 0) {
                notes.append(
                        String.format(
                                Locale.US,
                                " Accepted electrical coverage: %.1f%%.",
                                electricalCoverage * 100));
                if (minimumTemperatureC != null && maximumTemperatureC != null) {
                    notes.append(
                            String.format(
                                    Locale.US,
                                    " Observed pack temperature: %.1f–%.1f °C.",
                                    minimumTemperatureC,
                                    maximumTemperatureC));
                    if (minimumTemperatureC < 10)
                        notes.append(
                                " Cold conditions may reduce usable energy; no temperature"
                                    + " correction is applied.");
                } else notes.append(" Pack temperature was not available throughout the evidence.");
                if (presented.edgeSoc)
                    notes.append(" Near-full or near-empty gauge readings add uncertainty.");
                if (includesActive)
                    notes.append(
                            " The current ride is provisional and replaces its earlier"
                                + " contribution.");
            }
            limitations = notes.toString();
        }
    }

    private static final class Pool {
        int count;
        double drop, energy;
        long elapsed, covered;
        boolean active, edgeSoc;
        Double minTemperature, maxTemperature;

        void add(RideStore.Summary s, double wh) {
            count++;
            drop += s.batteryDropPercent;
            energy += wh;
            elapsed += s.elapsedMs;
            covered += s.electricalCoverageMs;
            active |= s.active;
            edgeSoc |= s.startBatteryPercent >= 95 || s.endBatteryPercent <= 10;
            minTemperature = min(minTemperature, s.minimumTemperatureC);
            maxTemperature = max(maxTemperature, s.maximumTemperatureC);
        }
    }

    public static Result unavailable(
            String profileId, Double advertisedWh, int windowDays, String reason) {
        return new Result(
                profileId,
                advertisedWh,
                boundedWindow(windowDays),
                System.currentTimeMillis(),
                new Pool(),
                new Pool(),
                Collections.emptyMap(),
                reason);
    }

    public static Result calculate(String profileId, List<Ride> rides, Double advertisedWh) {
        return calculate(
                profileId, rides, advertisedWh, System.currentTimeMillis(), DEFAULT_WINDOW_DAYS);
    }

    public static Result calculate(
            String profileId, List<Ride> rides, Double advertisedWh, long now, int windowDays) {
        int days = boundedWindow(windowDays);
        Pool confirmed = new Pool(), proxy = new Pool();
        Map<String, Integer> rejected = new LinkedHashMap<>();
        Map<String, Ride> unique = new LinkedHashMap<>();
        if (rides != null)
            for (Ride ride : rides) {
                if (ride == null
                        || ride.summary == null
                        || profileId == null
                        || !profileId.equals(ride.summary.scooterId)) continue;
                Ride old = unique.get(ride.summary.id);
                if (old == null || newer(ride.summary, old.summary))
                    unique.put(ride.summary.id, ride);
            }
        for (Ride ride : unique.values()) {
            RideStore.Summary s = ride.summary;
            if (s.endWallMs < now - days * DAY) continue;
            String reason = rejection(ride, now);
            if (reason != null) {
                rejected.put(reason, rejected.getOrDefault(reason, 0) + 1);
                continue;
            }
            if (s.directionConfirmed) confirmed.add(s, s.netConsumptionWh);
            else proxy.add(s, s.measuredThroughputWh);
        }
        return new Result(profileId, advertisedWh, days, now, confirmed, proxy, rejected, null);
    }

    private static boolean newer(RideStore.Summary next, RideStore.Summary old) {
        if (next.sampleCount != old.sampleCount) return next.sampleCount > old.sampleCount;
        if (next.elapsedMs != old.elapsedMs) return next.elapsedMs > old.elapsedMs;
        return !next.active || old.active;
    }

    private static String rejection(Ride ride, long now) {
        RideStore.Summary s = ride.summary;
        if (s.endWallMs > now + 300000 || s.endWallMs < s.startWallMs || s.clockDiscontinuities > 0)
            return "recording clock changed";
        if (s.gapCount > 0 || s.recovered) return "recording has gaps or recovery";
        if (s.directionChanges > 0) return "current direction changed during the ride";
        if (s.elapsedMs < 60000) return "record at least 1 minute";
        if (!finite(s.batteryDropPercent)
                || !validSoc(s.startBatteryPercent)
                || !validSoc(s.endBatteryPercent)) return "battery end readings are missing";
        if (s.batteryDropPercent < MIN_RIDE_DROP)
            return "battery drop is below 5 percentage points";
        if (s.electricalCoverage < MIN_COVERAGE) return "electrical coverage is below 95%";
        if (s.movingMs < 30000) return "at least 30 seconds of measured movement is needed";
        if (!positive(s.measuredThroughputWh)) return "measured energy is unavailable";
        if (s.directionConfirmed && !positive(s.netConsumptionWh))
            return "net measured energy is not positive";
        if (ride.points.size() < 2) return "endpoint power and battery readings are missing";
        RideStore.Point first = ride.points.get(0), last = ride.points.get(ride.points.size() - 1);
        if (first.elapsedMs < 0
                || first.elapsedMs > ENDPOINT_TOLERANCE_MS
                || s.elapsedMs - last.elapsedMs < 0
                || s.elapsedMs - last.elapsedMs > ENDPOINT_TOLERANCE_MS
                || !finite(first.powerW)
                || !finite(last.powerW)
                || !validSoc(first.batteryPercent)
                || !validSoc(last.batteryPercent)
                || Math.abs(first.batteryPercent - s.startBatteryPercent) > .001
                || Math.abs(last.batteryPercent - s.endBatteryPercent) > .001)
            return "power and battery readings do not align with both ride ends";
        double lowestSoc = first.batteryPercent;
        long previousElapsed = -1;
        for (RideStore.Point point : ride.points) {
            if (point.elapsedMs < previousElapsed) return "recording clock changed";
            previousElapsed = point.elapsedMs;
            if (!validSoc(point.batteryPercent)) continue;
            if (point.batteryPercent > lowestSoc + 1.000001)
                return "battery gauge rebounded or rose while recording";
            lowestSoc = Math.min(lowestSoc, point.batteryPercent);
        }
        return null;
    }

    private static int boundedWindow(int days) {
        return Math.max(1, Math.min(3650, days));
    }

    private static boolean finite(Double v) {
        return v != null && Double.isFinite(v);
    }

    private static boolean positive(Double v) {
        return finite(v) && v > 0;
    }

    private static boolean validSoc(Double v) {
        return finite(v) && v >= 0 && v <= 100;
    }

    private static Double ratio(Double value, Double reference) {
        return value == null || reference == null ? null : value / reference * 100;
    }

    private static Double min(Double a, Double b) {
        return !finite(b) ? a : a == null ? b : Math.min(a, b);
    }

    private static Double max(Double a, Double b) {
        return !finite(b) ? a : a == null ? b : Math.max(a, b);
    }

    private static String mostCommon(Map<String, Integer> values) {
        String best = "insufficient data";
        int count = 0;
        for (Map.Entry<String, Integer> value : values.entrySet())
            if (value.getValue() > count) {
                best = value.getKey();
                count = value.getValue();
            }
        return best;
    }
}
