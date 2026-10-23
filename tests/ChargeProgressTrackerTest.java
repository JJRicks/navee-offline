package local.navee.offlinelock;

/** Charge age: observed starts, unseen starts, inference, resets and bounded memory. */
public final class ChargeProgressTrackerTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static final long T0 = 50_000_000L, MIN = 60_000L;

    public static void main(String[] args) {
        ChargeProgressTracker t = new ChargeProgressTracker();
        check(t.progress("a", T0) == null, "No charge, no progress");
        t.observe("a", false, 40d, 0d, T0);
        t.observe("a", true, 40d, 2.3, T0 + MIN);
        ChargeProgressTracker.Progress p = t.progress("a", T0 + 21 * MIN);
        check(p.startObserved && Math.abs(p.ageMinutes - 20) < 1e-9 && p.startSoc == 40, "A seen start gives exact age and start percentage");
        check(t.progress("b", T0 + 21 * MIN) == null, "Progress belongs to one profile");
        t.observe("a", null, 41d, 2.2, T0 + 22 * MIN);
        check(t.progress("a", T0 + 22 * MIN).startObserved, "A stale charging state changes nothing");
        t.observe("a", true, 38d, 2.2, T0 + 23 * MIN);
        check(!t.progress("a", T0 + 23 * MIN).startObserved, "A falling percentage is a different charge");

        ChargeProgressTracker late = new ChargeProgressTracker();
        late.observe("a", false, 40d, 0d, T0);
        late.observe("a", true, 40d, 2.3, T0 + 6 * MIN);
        check(!late.progress("a", T0 + 6 * MIN).startObserved, "A start after the grace period is not claimed as seen");

        ChargeProgressTracker unseen = new ChargeProgressTracker();
        for (int i = 0; i <= 40; i++) {
            double amps = 1.94 * (1 + Ut5MaxStockPackModel.DECAY_DISPLAY * Math.exp(-(i * .5) / 25));
            unseen.observe("a", true, 30d + i / 20d, amps, T0 + i * 30_000L);
            ChargeProgressTracker.Progress q = unseen.progress("a", T0 + i * 30_000L);
            if (i * .5 < Ut5MaxStockPackModel.AGE_INFERENCE_MINUTES)
                check(!q.ageInferred && Math.abs(q.ageMinutes - i * .5) < 1e-9, "Until inference, an unseen start is assumed at first sight");
        }
        ChargeProgressTracker.Progress fresh = unseen.progress("a", T0 + 20 * MIN);
        check(fresh.ageInferred && Math.abs(fresh.ageMinutes - 20) < 1e-9 && fresh.startSoc == 30, "A decaying current confirms a fresh charge");

        ChargeProgressTracker settled = new ChargeProgressTracker();
        for (int i = 0; i <= 40; i++) settled.observe("a", true, 60d, 2.17, T0 + i * 30_000L);
        ChargeProgressTracker.Progress flat = settled.progress("a", T0 + 20 * MIN);
        check(flat.ageInferred && flat.ageMinutes >= 600, "A flat current is recognized as a settled charge");

        settled.observe("a", true, 60d, 2.17, T0 + 20 * MIN + ChargeProgressTracker.MAX_SILENCE_MS + 1);
        check(!settled.progress("a", T0 + 51 * MIN).ageInferred, "A long silence starts a new charge");
        settled.observe("b", true, 60d, 2.17, T0 + 52 * MIN);
        check(settled.progress("a", T0 + 52 * MIN) == null && settled.progress("b", T0 + 52 * MIN) != null, "Switching scooters restarts tracking");
        settled.reset();
        check(settled.progress("b", T0 + 53 * MIN) == null, "Reset forgets the charge");

        ChargeProgressTracker longer = new ChargeProgressTracker();
        for (int i = 0; i <= 2000; i++) longer.observe("a", true, 20d + i / 100d, 2.0, T0 + i * 30_000L);
        ChargeProgressTracker.Progress kept = longer.progress("a", T0 + 2000 * 30_000L);
        check(kept != null && kept.ageInferred && kept.startSoc == 20, "A long watched charge stays bounded and keeps its start");
        System.out.println("ChargeProgressTracker: " + checks + " checks passed");
    }
}
