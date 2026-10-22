package local.navee.offlinelock;

/**
 * Session-local charge age for one profile's current charge. A start is "observed" only when a
 * fresh not-charging state precedes charging within a short grace period. Otherwise the charge
 * is assumed to have started at first sight until enough watched current lets
 * {@link Ut5MaxStockPackModel#inferStartAge} estimate how far its warm-up had progressed.
 * Nothing is persisted; a process restart observes the charge again.
 */
public final class ChargeProgressTracker {
    public static final long START_GRACE_MS = 5 * 60_000L, MAX_SILENCE_MS = 30 * 60_000L, SPACING_MS = 30_000L;
    private static final int CAPACITY = 120;

    /** Immutable view for one estimate. */
    public static final class Progress {
        public final double ageMinutes, startSoc;
        public final boolean startObserved, ageInferred;
        Progress(double ageMinutes, double startSoc, boolean startObserved, boolean ageInferred) {
            this.ageMinutes = ageMinutes; this.startSoc = startSoc;
            this.startObserved = startObserved; this.ageInferred = ageInferred;
        }
    }

    private String profile;
    private boolean active, startObserved;
    private long firstSeen, lastSeen, lastNotCharging, lastSample, spacing = SPACING_MS;
    private double startSoc = Double.NaN, lastSoc = Double.NaN;
    private final double[] minutes = new double[CAPACITY], amps = new double[CAPACITY];
    private int count;

    public void reset() {
        profile = null; lastNotCharging = 0; clearSession();
    }

    /**
     * charging is the fresh charging state (null when stale/unknown, which changes nothing).
     * soc and amps are paired fresh readings or null. Times share one monotonic clock.
     */
    public void observe(String profile, Boolean charging, Double soc, Double amps, long nowMs) {
        if (profile == null || profile.isEmpty() || nowMs <= 0 || charging == null) return;
        if (!profile.equals(this.profile)) { reset(); this.profile = profile; }
        if (active && nowMs < lastSeen) clearSession();
        if (!charging) { lastNotCharging = nowMs; clearSession(); return; }
        if (active && (nowMs - lastSeen > MAX_SILENCE_MS || valid(soc, 0, 100) && finite(lastSoc) && soc < lastSoc - 1))
            clearSession();
        if (!active) {
            active = true; firstSeen = nowMs; lastSample = 0;
            startObserved = lastNotCharging > 0 && nowMs - lastNotCharging <= START_GRACE_MS;
        }
        lastSeen = nowMs;
        if (valid(soc, 0, 100)) {
            if (!finite(startSoc)) startSoc = soc;
            lastSoc = soc;
        }
        if (!startObserved && amps != null && finite(amps) && Math.abs(amps) > 0 && (count == 0 || nowMs - lastSample >= spacing)) {
            if (count == CAPACITY) compact();
            minutes[count] = (nowMs - firstSeen) / 60000d;
            this.amps[count++] = Math.abs(amps);
            lastSample = nowMs;
        }
    }

    /** Null without an active charge for this profile. */
    public Progress progress(String profile, long nowMs) {
        if (!active || profile == null || !profile.equals(this.profile) || nowMs < firstSeen) return null;
        double watched = (nowMs - firstSeen) / 60000d;
        if (startObserved) return new Progress(watched, startSoc, true, false);
        boolean infer = watched >= Ut5MaxStockPackModel.AGE_INFERENCE_MINUTES && count >= 2;
        double before = infer ? Ut5MaxStockPackModel.inferStartAge(minutes, amps, count) : 0;
        return new Progress(before + watched, startSoc, false, infer);
    }

    /** Keep the whole watched span at half the resolution once the buffer fills. */
    private void compact() {
        int kept = 0;
        for (int i = 0; i < count; i += 2) { minutes[kept] = minutes[i]; amps[kept++] = amps[i]; }
        count = kept; spacing *= 2;
    }

    private void clearSession() {
        active = startObserved = false; firstSeen = lastSeen = lastSample = 0; spacing = SPACING_MS;
        startSoc = lastSoc = Double.NaN; count = 0;
    }

    private static boolean finite(double value) { return !Double.isNaN(value) && !Double.isInfinite(value); }
    private static boolean valid(Double value, double low, double high) {
        return value != null && finite(value) && value >= low && value <= high;
    }
}
