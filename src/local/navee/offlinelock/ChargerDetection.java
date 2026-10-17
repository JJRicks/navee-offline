package local.navee.offlinelock;

/**
 * Session-local inference for the two observed UT5 MAX chargers. This is a prediction hint, not a
 * charger ID or a training label. Call only with fresh, paired electrical readings; the caller
 * owns freshness. A confident family is remembered for one profile's physical charge, across
 * reconnects and isolated unusable frames. Nothing is persisted.
 */
public final class ChargerDetection {
    // Battery polling is user-configurable up to ten seconds. A longer gap restarts an evidence
    // run; only a far longer silence (several missed polls or a slow reconnect) forgets a choice.
    public static final long MAX_CONTINUITY_GAP_MS = 15000, RETAIN_MS = 120000;
    public static final long MIN_EVIDENCE_MS = 4000;
    public static final int MIN_EVIDENCE_FRAMES = 3;
    // The references' own admitted-estimate grace; outside it the remembered curve cannot apply.
    private static final double CONTRARY_TOLERANCE = .20;

    private String profile;
    private ChargeSessionStore.Charger selected, candidate;
    private long lastFrame, lastNow, lastValid, candidateStarted, contraryStarted;
    private int candidateFrames, contraryFrames;
    private double lastSoc = Double.NaN;

    /** Clears inference at a scooter, charging-state or manual-selection boundary. */
    public void reset() {
        profile = null;
        selected = null;
        lastFrame = lastNow = lastValid = 0;
        lastSoc = Double.NaN;
        clearCandidate();
        clearContrary();
    }

    /** Null unless this profile's charge produced confident evidence within RETAIN_MS. */
    public ChargeSessionStore.Charger selected(String profile, long nowMs) {
        return selected != null && profile != null && profile.equals(this.profile)
                && nowMs >= lastValid && nowMs - lastValid <= RETAIN_MS ? selected : null;
    }

    /**
     * frameMs identifies the paired voltage/current source frame; nowMs uses the same monotonic
     * clock. Rendering the same frame repeatedly never adds evidence. Both current signs are
     * accepted because charging has already been established independently by the caller.
     * Null readings mark an unusable frame: it neither adds evidence nor ends the charge.
     */
    public ChargeSessionStore.Charger observe(String profile, int productId, boolean charging,
            Double soc, Double volts, Double amps, long frameMs, long nowMs) {
        if (profile == null || profile.isEmpty() || nowMs <= 0) return null;
        boolean supported = productId == Ut5MaxStockChargeReference.PRODUCT_ID;
        if (!supported || !charging || !profile.equals(this.profile) || nowMs < lastNow
                || lastValid > 0 && nowMs - lastValid > RETAIN_MS
                || between(soc, 0, 100) && soc <= lastSoc - 1) reset();
        if (!supported || !charging) return null;
        this.profile = profile;
        lastNow = nowMs;
        if (!between(soc, 0, 100) || !between(volts, 35, 60) || amps == null || !Double.isFinite(amps)
                || Math.abs(amps) <= 0 || Math.abs(amps) > 12 || volts * Math.abs(amps) > 650
                || frameMs <= 0 || frameMs > nowMs || frameMs < lastFrame) {
            clearCandidate();
            clearContrary();
            return selected;
        }
        if (frameMs == lastFrame) return selected;
        if (lastFrame > 0 && frameMs - lastFrame > MAX_CONTINUITY_GAP_MS) { clearCandidate(); clearContrary(); }
        lastFrame = frameMs;
        lastValid = nowMs;
        lastSoc = soc;

        double current = Math.abs(amps), watts = volts * current;
        ChargeSessionStore.Charger evidence = evidence(soc, current, watts);
        if (evidence == null || evidence == selected) clearCandidate();
        else if (candidate != evidence) { candidate = evidence; candidateStarted = frameMs; candidateFrames = 1; }
        // Saturate because repeated high-rate samples need not grow this counter forever.
        else candidateFrames = Math.min(MIN_EVIDENCE_FRAMES, candidateFrames + 1);
        if (selected != null) {
            // Near full both families overlap, and each reference rejects an incompatible frame
            // itself. Forget the family only after a sustained run outside its own envelope.
            if (soc >= 100 || compatible(selected, soc, watts)) { clearContrary(); return selected; }
            if (contraryFrames == 0) contraryStarted = frameMs;
            contraryFrames = Math.min(MIN_EVIDENCE_FRAMES, contraryFrames + 1);
            if (contraryFrames < MIN_EVIDENCE_FRAMES || frameMs - contraryStarted < MIN_EVIDENCE_MS) return selected;
            selected = null;
            clearContrary();
        }
        if (candidate != null && candidateFrames >= MIN_EVIDENCE_FRAMES && frameMs - candidateStarted >= MIN_EVIDENCE_MS) {
            selected = candidate;
            clearCandidate();
        }
        return selected;
    }

    private static ChargeSessionStore.Charger evidence(double soc, double current, double watts) {
        // Recorded stock sessions reach about 121 W / 2.25 A. The extra margin prevents normal
        // stock variation selecting fast. A fast charger is still identifiable well into taper.
        if (watts > 150 && current >= 2.8) return ChargeSessionStore.Charger.HIGH_SPEED;
        // Around 96-100% the fast taper overlaps stock power. Never call that stock at cold start.
        // Even below this guard, require a normal stock bulk band rather than arbitrary low power.
        if (soc <= 85 && watts >= 65 && watts <= 135 && current >= 1.4 && current <= 2.5)
            return ChargeSessionStore.Charger.STOCK;
        return null;
    }

    private static boolean compatible(ChargeSessionStore.Charger family, double soc, double watts) {
        return family == ChargeSessionStore.Charger.HIGH_SPEED
                ? Ut5MaxFastChargeReference.powerCompatible(soc, watts, CONTRARY_TOLERANCE)
                : Ut5MaxStockChargeReference.powerCompatible(soc, watts, CONTRARY_TOLERANCE);
    }

    private void clearCandidate() { candidate = null; candidateStarted = 0; candidateFrames = 0; }
    private void clearContrary() { contraryStarted = 0; contraryFrames = 0; }
    private static boolean between(Double value, double low, double high) {
        return value != null && Double.isFinite(value) && value >= low && value <= high;
    }
}
