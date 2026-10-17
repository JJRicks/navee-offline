package local.navee.offlinelock;

/**
 * Session-local inference for the two observed UT5 MAX chargers. This is a prediction hint, not a
 * charger ID or a training label. Call only with fresh, paired electrical readings from one live
 * transport, and include the profile and transport generation in scope. Nothing is persisted.
 */
public final class ChargerDetection {
    public static final int UT5_MAX_PRODUCT_ID = 2538;
    // Battery polling is user-configurable up to ten seconds. Intermediate status/UI updates
    // may reuse that frame while it is still live; capture has a separate, stricter age limit.
    public static final long MAX_FRAME_AGE_MS = 15000, MAX_CONTINUITY_GAP_MS = 15000;
    public static final long MIN_EVIDENCE_MS = 4000;
    public static final int MIN_EVIDENCE_FRAMES = 3;

    private String scope;
    private ChargeSessionStore.Charger selected, candidate;
    private long lastFrame, lastNow, candidateStarted;
    private int candidateFrames;

    /** Clears inference at a connection, scooter, charging-state or manual-selection boundary. */
    public void reset() {
        scope = null;
        selected = candidate = null;
        lastFrame = lastNow = candidateStarted = 0;
        candidateFrames = 0;
    }

    /** Null means the evidence does not yet distinguish the two chargers. */
    public ChargeSessionStore.Charger selected() { return selected; }

    /**
     * frameMs identifies the paired voltage/current source frame; nowMs uses the same monotonic
     * clock. Rendering the same frame repeatedly never adds evidence. Both current signs are
     * accepted because charging has already been established independently by the caller.
     */
    public ChargeSessionStore.Charger observe(String scope, int productId, boolean charging,
            Double soc, Double volts, Double amps, long frameMs, long nowMs) {
        if (scope == null || scope.isEmpty() || productId != UT5_MAX_PRODUCT_ID || !charging
                || !between(soc, 0, 100) || !between(volts, 35, 60)
                || amps == null || !Double.isFinite(amps)
                || Math.abs(amps) <= 0 || Math.abs(amps) > 12
                || volts * Math.abs(amps) > 650
                || frameMs <= 0 || nowMs <= 0 || frameMs > nowMs
                || nowMs - frameMs > MAX_FRAME_AGE_MS) {
            reset();
            return null;
        }
        if (!scope.equals(this.scope)) {
            reset();
            this.scope = scope;
        }
        if (lastNow > 0 && (nowMs < lastNow || frameMs < lastFrame)) {
            reset();
            return null;
        }
        // Reopening after a gap must not inherit an old charger, even if the first new frame
        // happens to be fresh. A normal render of a cached frame still expires on frame age.
        if (lastFrame > 0 && (frameMs - lastFrame > MAX_CONTINUITY_GAP_MS
                || nowMs - lastNow > MAX_CONTINUITY_GAP_MS)) {
            reset();
            this.scope = scope;
        }
        lastNow = nowMs;
        if (frameMs == lastFrame) return selected;
        lastFrame = frameMs;

        double current = Math.abs(amps), watts = volts * current;
        ChargeSessionStore.Charger evidence = evidence(soc, current, watts);
        if (selected != null) {
            // A stock charger cannot produce clear high-power evidence. Conversely, low bulk
            // power on a previously fast session is not its normal late taper. Stop applying
            // the old curve immediately, then require a fresh stable run before switching.
            boolean incompatible = selected == ChargeSessionStore.Charger.STOCK
                    ? watts > 135 || current > 2.5
                    : soc <= 85 && (watts <= 150 || current < 2.8);
            if (!incompatible) return selected;
            selected = null;
            clearCandidate();
        }

        if (evidence == null) {
            clearCandidate();
            return null;
        }
        if (candidate != evidence) {
            candidate = evidence;
            candidateStarted = frameMs;
            candidateFrames = 1;
        } else {
            // Saturate because repeated high-rate samples need not grow this counter forever.
            candidateFrames = Math.min(MIN_EVIDENCE_FRAMES, candidateFrames + 1);
        }
        if (candidateFrames >= MIN_EVIDENCE_FRAMES && frameMs - candidateStarted >= MIN_EVIDENCE_MS) {
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

    private void clearCandidate() { candidate = null; candidateStarted = 0; candidateFrames = 0; }
    private static boolean between(Double value, double low, double high) {
        return value != null && Double.isFinite(value) && value >= low && value <= high;
    }
}
