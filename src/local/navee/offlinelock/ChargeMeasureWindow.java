package local.navee.offlinelock;

/**
 * Charge done by's bounded measuring step, before its one-shot arming. When Volt did not see the
 * charge begin, the cautious plan assumes a fresh charger; about ten watched minutes of current
 * let {@link ChargeProgressTracker} infer the charge's age instead. Arming pauses current, so the
 * order is measure, then schedule. This class never sends a command: it only decides when the
 * existing arming runs, and it ends no later than the cautious plan would have had to start.
 */
public final class ChargeMeasureWindow {
    /** Ten minutes of inference plus two 30 s samples of slack; shorter waits are not worth it. */
    public static final long MAX_MS = 12 * 60_000L, MIN_USEFUL_MS = 3 * 60_000L;
    public enum Step { WAIT, ARM, NOT_SET }
    public static final class Decision {
        public final Step step;
        /** ARM: true when the charge's age is known; false for the cautious fresh-charge plan. */
        public final boolean measured;
        public final String reason;
        Decision(Step step, boolean measured, String reason) { this.step = step; this.measured = measured; this.reason = reason; }
    }
    private static final Decision WAIT = new Decision(Step.WAIT, false, null);
    private String profile = "";
    private int minute;
    private long startedMono, deadlineMono;
    private boolean active, arming;

    /** Measuring helps only when the cautious start leaves room for it. */
    public static boolean useful(long nowWall, long cautiousStartWall) { return cautiousStartWall - nowWall >= MIN_USEFUL_MS; }

    public boolean active() { return active; }
    /** Arming was requested and its scooter writes are still in flight. */
    public boolean arming() { return active && arming; }
    public String profile() { return profile; }
    public int minute() { return minute; }
    public long remainingMs(long nowMono) { return active ? Math.max(0, deadlineMono - nowMono) : 0; }

    /** The deadline is the earlier of the bounded window and the cautious plan's own start. */
    public void start(String profile, int minute, long nowMono, long nowWall, long cautiousStartWall) {
        if (profile == null || profile.isEmpty() || minute < 0 || minute >= 1440 || nowMono <= 0 || !useful(nowWall, cautiousStartWall))
            throw new IllegalArgumentException("Measuring cannot help this plan");
        this.profile = profile; this.minute = minute; startedMono = nowMono;
        deadlineMono = nowMono + Math.min(MAX_MS, cautiousStartWall - nowWall);
        active = true; arming = false;
    }

    /**
     * charging is the fresh charging state (null when unknown); ageKnown means the tracker saw the
     * start or inferred the age. A disconnect may recover before the deadline; a late one cannot.
     */
    public Decision evaluate(long nowMono, String selectedProfile, boolean ready, Integer charging, boolean ageKnown) {
        if (!active || arming) return WAIT;
        if (!profile.equals(selectedProfile)) return new Decision(Step.NOT_SET, false, "The selected scooter changed while measuring");
        if (nowMono < startedMono) return new Decision(Step.NOT_SET, false, "The phone clock changed while measuring");
        if (ready && Integer.valueOf(0).equals(charging)) return new Decision(Step.NOT_SET, false, "Charging stopped before the schedule was set");
        boolean charge = ready && Integer.valueOf(1).equals(charging);
        if (charge && ageKnown) return new Decision(Step.ARM, true, null);
        if (nowMono < deadlineMono) return WAIT;
        return charge ? new Decision(Step.ARM, false, null)
                : new Decision(Step.NOT_SET, false, ready ? "Charging readings stopped while measuring" : "The scooter disconnected while measuring");
    }

    /** A new finish time: keep the bound, and end no later than the new cautious start. */
    public void retarget(int minute, long nowMono, long nowWall, long cautiousStartWall) {
        if (!active || arming || minute < 0 || minute >= 1440) return;
        this.minute = minute;
        deadlineMono = Math.min(deadlineMono, nowMono + Math.max(0, cautiousStartWall - nowWall));
    }

    /** Owner's "Schedule now": arm at once with whatever the charge's age is known to be. */
    public void arm() { if (active) arming = true; }
    public void finish() { active = arming = false; profile = ""; startedMono = deadlineMono = 0; }
}
