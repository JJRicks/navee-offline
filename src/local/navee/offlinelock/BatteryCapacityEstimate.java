package local.navee.offlinelock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Usable ride energy: the battery-side energy recorded rides drew per full battery gauge (100
 * percentage points), plus the charge per gauge and a 90% range. Never a pack-health test: the
 * gauge's 0-100% window is set by the battery controller, and voltage window, load, cold and gauge
 * calibration all affect the result even with verified current direction. No missing energy is
 * invented.
 */
public final class BatteryCapacityEstimate {
    public static final int DEFAULT_WINDOW_DAYS = 90;
    public static final double MIN_RIDE_DROP = 5, MIN_TOTAL_DROP = 60, MIN_COVERAGE = .95;
    /** A single estimate needs this many rides in one evidence group; fewer rides vary by +-15%. */
    public static final int MIN_RIDES = 6;
    /**
     * Rides further than this from the median energy per gauge point (plus one point of rounding)
     * are set aside: the gauge corrected itself during the ride. Needs OUTLIER_MIN_RIDES to judge.
     */
    public static final double OUTLIER_TOLERANCE = .25;
    public static final int OUTLIER_MIN_RIDES = 5;
    static final String GAUGE_JUMP = "battery gauge jumped during the ride";
    private static final long DAY = 86400000L, ENDPOINT_TOLERANCE_MS = 3000, MAX_POINT_STEP_MS = 30000;

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
        /** The short UI state; measured evidence below supplies the details on tap. */
        public final String profileId, label;
        /** Non-null when analysis could not complete; distinct from completed, insufficient evidence. */
        public final String unavailableReason;
        /** Short (four words or fewer) most common rejection reason, or null when none. */
        public final String reason;
        /** Battery points in the larger evidence group, toward {@link #MIN_TOTAL_DROP}. */
        public final double progressPoints;
        public final Double estimatedUsableWh,
                /** 90% range of the presented energy estimate, from the spread between rides. */
                estimatedLowWh,
                estimatedHighWh,
                throughputEquivalentWh,
                throughputLowWh,
                throughputHighWh,
                /** Battery charge drawn per full gauge (Ah per 100 points), with its 90% range. */
                ahPerGauge,
                ahPerGaugeLow,
                ahPerGaugeHigh,
                /** Independent check: charge accepted per full gauge in completed charges. */
                chargingAhPerGauge,
                percentOfAdvertised,
                throughputPercentOfAdvertised,
                advertisedWh,
                minimumTemperatureC,
                maximumTemperatureC;
        public final int qualifiedRides, proxyRides, rejectedRides, windowDays, chargingSessions;
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
                String unavailable,
                ChargeEvidence charging) {
            this.profileId = profileId == null ? "" : profileId;
            this.advertisedWh = positive(advertisedWh) ? advertisedWh : null;
            this.windowDays = windowDays;
            unavailableReason = unavailable;
            computedWallMs = now;
            qualifiedRides = confirmed.count;
            proxyRides = proxy.count;
            rejectedRides = rejected.values().stream().mapToInt(Integer::intValue).sum();
            rejectionReasons = Collections.unmodifiableMap(new LinkedHashMap<>(rejected));
            batteryDropPoints = confirmed.drop;
            energyWh = confirmed.energy;
            proxyBatteryDropPoints = proxy.drop;
            proxyEnergyWh = proxy.energy;
            estimatedUsableWh = confirmed.enough() ? confirmed.energy * 100 / confirmed.drop : null;
            throughputEquivalentWh = proxy.enough() ? proxy.energy * 100 / proxy.drop : null;
            double[] energyRange = estimatedUsableWh != null ? confirmed.range(false) : null;
            double[] proxyRange = throughputEquivalentWh != null ? proxy.range(false) : null;
            estimatedLowWh = energyRange == null ? null : energyRange[0];
            estimatedHighWh = energyRange == null ? null : energyRange[1];
            throughputLowWh = proxyRange == null ? null : proxyRange[0];
            throughputHighWh = proxyRange == null ? null : proxyRange[1];
            Pool charged = estimatedUsableWh != null ? confirmed : throughputEquivalentWh != null ? proxy : null;
            ahPerGauge = charged == null || charged.chargeDrop < MIN_TOTAL_DROP ? null : charged.charge * 100 / charged.chargeDrop;
            double[] ahRange = ahPerGauge == null ? null : charged.range(true);
            ahPerGaugeLow = ahRange == null ? null : ahRange[0];
            ahPerGaugeHigh = ahRange == null ? null : ahRange[1];
            chargingAhPerGauge = charging == null || charging.points <= 0 ? null : charging.ah * 100 / charging.points;
            chargingSessions = charging == null ? 0 : charging.sessions;
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
            progressPoints = Math.max(confirmed.drop, proxy.drop);
            reason = rejected.isEmpty() ? null : shortReason(mostCommon(rejected));
            if (unavailable != null) label = unavailable;
            else if (estimatedUsableWh != null) label = "Experimental estimate";
            else if (throughputEquivalentWh != null) label = "Proxy · not health";
            else if (confirmed.count + proxy.count > 0) {
                Pool larger = confirmed.drop >= proxy.drop ? confirmed : proxy;
                label = progressPoints >= MIN_TOTAL_DROP
                        ? String.format(Locale.US, "%d / %d rides", larger.count, MIN_RIDES)
                        : String.format(Locale.US, "%.0f / %.0f pts", Math.floor(progressPoints), MIN_TOTAL_DROP);
            }
            else if (!rejected.isEmpty()) label = "No usable rides yet";
            else label = "Record a longer ride";
        }
    }

    /** Charge accepted from a charge's first percentage change to displayed 100%, pooled. */
    public static final class ChargeEvidence {
        public final double ah, points;
        public final int sessions;
        public ChargeEvidence(double ah, double points, int sessions) {
            this.ah = ah; this.points = points; this.sessions = sessions;
        }
    }

    private static final class Entry {
        final RideStore.Summary summary;
        final double drop, energy;
        final Double charge;
        Entry(RideStore.Summary summary, double energy, Double charge) {
            this.summary = summary; this.drop = summary.batteryDropPercent; this.energy = energy; this.charge = charge;
        }
    }

    private static final class Pool {
        final List<Entry> entries = new ArrayList<>();
        int count;
        double drop, energy, charge, chargeDrop;
        long elapsed, covered;
        boolean active;
        Double minTemperature, maxTemperature;

        void add(Entry e) {
            RideStore.Summary s = e.summary;
            entries.add(e);
            count++;
            drop += e.drop;
            energy += e.energy;
            if (e.charge != null) { charge += e.charge; chargeDrop += e.drop; }
            elapsed += s.elapsedMs;
            covered += s.electricalCoverageMs;
            active |= s.active;
            minTemperature = min(minTemperature, s.minimumTemperatureC);
            maxTemperature = max(maxTemperature, s.maximumTemperatureC);
        }

        boolean enough() { return drop >= MIN_TOTAL_DROP && count >= MIN_RIDES; }

        /**
         * Jackknife (leave-one-ride-out) 90% range of the pooled per-gauge ratio. Wide when rides
         * disagree; it reflects ride-to-ride spread, not unknown bias such as the gauge window.
         */
        double[] range(boolean useCharge) {
            List<Entry> used = new ArrayList<>();
            for (Entry e : entries) if (!useCharge || e.charge != null) used.add(e);
            int n = used.size();
            double total = 0, points = 0;
            for (Entry e : used) { total += useCharge ? e.charge : e.energy; points += e.drop; }
            if (n < 2 || points <= 0) return null;
            double center = total * 100 / points, mean = 0;
            double[] leaveOut = new double[n];
            for (int i = 0; i < n; i++) {
                Entry e = used.get(i);
                double rest = points - e.drop;
                if (rest <= 0) return null;
                leaveOut[i] = (total - (useCharge ? e.charge : e.energy)) * 100 / rest;
                mean += leaveOut[i] / n;
            }
            double variance = 0;
            for (double value : leaveOut) variance += (value - mean) * (value - mean);
            double half = studentT90(n - 1) * Math.sqrt(variance * (n - 1) / n);
            return new double[]{center - half, center + half};
        }
    }

    /** Two-sided 90% Student t critical values; conservative between listed degrees of freedom. */
    static double studentT90(int df) {
        int[] dfs = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 12, 15, 20, 30, 60};
        double[] ts = {6.314, 2.920, 2.353, 2.132, 2.015, 1.943, 1.895, 1.860, 1.833, 1.812, 1.782, 1.753, 1.725, 1.697, 1.671};
        if (df < 1) return ts[0];
        for (int i = dfs.length - 1; i >= 0; i--) if (df >= dfs[i]) return ts[i];
        return ts[0];
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
                reason,
                null);
    }

    public static Result calculate(String profileId, List<Ride> rides, Double advertisedWh) {
        return calculate(
                profileId, rides, advertisedWh, System.currentTimeMillis(), DEFAULT_WINDOW_DAYS);
    }

    public static Result calculate(
            String profileId, List<Ride> rides, Double advertisedWh, long now, int windowDays) {
        return calculate(profileId, rides, advertisedWh, now, windowDays, null);
    }

    /** charging may be null; it is shown beside, never mixed into, the ride-based estimate. */
    public static Result calculate(String profileId, List<Ride> rides, Double advertisedWh, long now,
            int windowDays, ChargeEvidence charging) {
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
        List<Entry> confirmedRides = new ArrayList<>(), proxyRides = new ArrayList<>();
        for (Ride ride : unique.values()) {
            RideStore.Summary s = ride.summary;
            if (s.endWallMs < now - days * DAY) continue;
            String reason = rejection(ride, now);
            if (reason != null) {
                rejected.put(reason, rejected.getOrDefault(reason, 0) + 1);
                continue;
            }
            // A ride whose direction changed has no single sign for its points, so no charge figure.
            if (s.directionConfirmed) confirmedRides.add(new Entry(s, s.netConsumptionWh, drawSign(s) == 0 ? null : charge(ride, s.netConsumptionWh, drawSign(s))));
            else proxyRides.add(new Entry(s, s.measuredThroughputWh, charge(ride, s.measuredThroughputWh, 0)));
        }
        pool(confirmedRides, confirmed, rejected);
        pool(proxyRides, proxy, rejected);
        return new Result(profileId, advertisedWh, days, now, confirmed, proxy, rejected, null, charging);
    }

    /** Set aside rides whose energy per gauge point is far from the group's median. */
    private static void pool(List<Entry> rides, Pool into, Map<String, Integer> rejected) {
        double median = Double.NaN;
        if (rides.size() >= OUTLIER_MIN_RIDES) {
            double[] ratios = new double[rides.size()];
            for (int i = 0; i < ratios.length; i++) ratios[i] = rides.get(i).energy / rides.get(i).drop;
            java.util.Arrays.sort(ratios);
            int n = ratios.length;
            median = n % 2 == 1 ? ratios[n / 2] : (ratios[n / 2 - 1] + ratios[n / 2]) / 2;
        }
        for (Entry e : rides) {
            if (median > 0 && Math.abs(e.energy / e.drop - median) / median > OUTLIER_TOLERANCE + 1 / e.drop) {
                rejected.put(GAUGE_JUMP, rejected.getOrDefault(GAUGE_JUMP, 0) + 1);
                continue;
            }
            into.add(e);
        }
    }

    /**
     * Battery charge (Ah) for the summary's energy: the analysis points supply the charge-weighted
     * voltage, so charge stays consistent with the full-resolution journal energy. Points keep the
     * scooter's sign: drawSign +1 or -1 turns draw positive and regeneration negative; 0 counts
     * throughput both ways.
     */
    static Double charge(Ride ride, double energyWh, int drawSign) {
        double wh = 0, ah = 0;
        RideStore.Point previous = null;
        for (RideStore.Point point : ride.points) {
            if (previous != null && previous.segment == point.segment && finite(previous.powerW)
                    && finite(previous.voltageV) && previous.voltageV > 0) {
                long step = point.elapsedMs - previous.elapsedMs;
                if (step > 0 && step <= MAX_POINT_STEP_MS) {
                    double hours = step / 3600000d, watts = drawSign != 0 ? drawSign * previous.powerW : Math.abs(previous.powerW);
                    wh += watts * hours;
                    ah += watts / previous.voltageV * hours;
                }
            }
            previous = point;
        }
        if (!(wh > 0) || !(ah > 0) || !(energyWh > 0)) return null;
        return energyWh * ah / wh;
    }

    /** +1 or -1 for the recorded convention; 0 when unconfirmed or changed during the ride. */
    static int drawSign(RideStore.Summary s) {
        if (s.directionChanges > 0) return 0;
        return "POSITIVE_IS_DRAW".equals(s.currentDirection) ? 1 : "NEGATIVE_IS_DRAW".equals(s.currentDirection) ? -1 : 0;
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

    /** Four words or fewer for each rejection reason, for the card's details. */
    public static String shortReason(String reason) {
        if (reason == null) return null;
        switch (reason) {
            case "recording clock changed": return "Clock changed";
            case "recording has gaps or recovery": return "Recording gaps";
            case "current direction changed during the ride": return "Current direction changed";
            case "record at least 1 minute": return "Under 1 minute";
            case "battery end readings are missing": return "Battery ends missing";
            case "battery drop is below 5 percentage points": return "Drop under 5%";
            case "electrical coverage is below 95%": return "Coverage under 95%";
            case "at least 30 seconds of measured movement is needed": return "Too little movement";
            case "measured energy is unavailable": return "No energy reading";
            case "net measured energy is not positive": return "No net energy";
            case "endpoint power and battery readings are missing": return "End readings missing";
            case "power and battery readings do not align with both ride ends": return "Ends misaligned";
            case "battery gauge rebounded or rose while recording": return "Battery gauge rose";
            case GAUGE_JUMP: return "Gauge jumped";
            default: return "Insufficient data";
        }
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
