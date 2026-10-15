package local.navee.offlinelock;

/** Runtime-only tolerance for short power fluctuations; never caches readings or an ETA. */
public final class StockChargeReferenceGate {
    private String scope;
    private long lastFrame, lastNow, outsideSince;
    private int outsideFrames;
    private boolean applied;

    /**
     * Enter at the reference's usual 15% tolerance. Once admitted, allow at most two distinct
     * electrical frames within 20%, for less than 15 seconds. Multiple widgets reading the same
     * frame cannot exhaust the allowance. Callers must reset on loss of fresh charging data.
     */
    public boolean accepts(String context, double soc, double watts, long frameMs, long nowMs) {
        if (context == null || context.isEmpty() || frameMs <= 0 || nowMs <= 0) {
            reset(); return false;
        }
        if (!context.equals(scope) || frameMs < lastFrame || nowMs < lastNow) reset();
        boolean newFrame = frameMs != lastFrame;
        scope = context; lastFrame = frameMs; lastNow = nowMs;
        if (Ut5MaxStockChargeReference.powerCompatible(soc, watts, .15)) {
            applied = true; outsideFrames = 0; outsideSince = 0; return true;
        }
        if (!applied || !Ut5MaxStockChargeReference.powerCompatible(soc, watts, .20)) {
            applied = false; outsideFrames = 0; outsideSince = 0; return false;
        }
        if (outsideFrames == 0) { outsideSince = nowMs; outsideFrames = 1; }
        else if (newFrame) outsideFrames++;
        if (outsideFrames >= 3 || nowMs - outsideSince >= 15000) {
            applied = false; outsideFrames = 0; outsideSince = 0; return false;
        }
        return true;
    }

    public void reset() {
        scope = null; lastFrame = lastNow = outsideSince = 0; outsideFrames = 0; applied = false;
    }
}
