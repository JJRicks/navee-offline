package com.jjricks.volt;

public final class ChargeMeasureWindowTest {
    private static int checks;
    private static final long MONO = 5_000_000L, WALL = 1_800_000_000_000L, MIN = 60_000L;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static ChargeMeasureWindow started(long cautiousStartIn) {
        ChargeMeasureWindow w = new ChargeMeasureWindow();
        w.start("one", 7 * 60, MONO, WALL, WALL + cautiousStartIn);
        return w;
    }
    private static ChargeMeasureWindow.Decision at(ChargeMeasureWindow w, long offset, boolean ready, Integer charging, boolean known) {
        return w.evaluate(MONO + offset, "one", ready, charging, known);
    }
    public static void main(String[] args) {
        check(!ChargeMeasureWindow.useful(WALL, WALL + 3 * MIN - 1) && ChargeMeasureWindow.useful(WALL, WALL + 3 * MIN),
                "Measuring is offered only with at least three minutes before the cautious start");
        boolean refused = false;
        try { new ChargeMeasureWindow().start("one", 420, MONO, WALL, WALL + MIN); } catch (IllegalArgumentException expected) { refused = true; }
        check(refused, "A window that cannot help is refused rather than started");

        ChargeMeasureWindow w = started(6 * 60 * MIN);
        check(w.active() && !w.arming() && w.remainingMs(MONO) == ChargeMeasureWindow.MAX_MS, "A far deadline gets the full twelve minutes");
        check(at(w, MIN, true, 1, false).step == ChargeMeasureWindow.Step.WAIT, "Charging with unknown age keeps measuring");
        ChargeMeasureWindow.Decision measured = at(w, 10 * MIN, true, 1, true);
        check(measured.step == ChargeMeasureWindow.Step.ARM && measured.measured, "Inferred age arms with the measured plan");
        w.arm();
        check(w.arming() && at(w, 10 * MIN + 1000, true, 1, true).step == ChargeMeasureWindow.Step.WAIT, "Arming is requested once while its writes run");
        w.finish();
        check(!w.active() && w.remainingMs(MONO) == 0 && at(w, 0, true, 1, true).step == ChargeMeasureWindow.Step.WAIT, "A finished window decides nothing");

        w = started(6 * 60 * MIN);
        ChargeMeasureWindow.Decision timeout = at(w, ChargeMeasureWindow.MAX_MS, true, 1, false);
        check(timeout.step == ChargeMeasureWindow.Step.ARM && !timeout.measured, "Timeout falls back to the cautious fresh-charge plan");
        check(at(started(6 * 60 * MIN), ChargeMeasureWindow.MAX_MS - 1, true, 1, false).step == ChargeMeasureWindow.Step.WAIT, "Not a millisecond early");

        w = started(5 * MIN);
        check(w.remainingMs(MONO) == 5 * MIN, "The window ends when the cautious plan would have to start");
        check(at(w, 5 * MIN, true, 1, false).step == ChargeMeasureWindow.Step.ARM, "Arming is never later than the cautious start");

        w = started(6 * 60 * MIN);
        ChargeMeasureWindow.Decision stopped = at(w, 2 * MIN, true, 0, false);
        check(stopped.step == ChargeMeasureWindow.Step.NOT_SET && stopped.reason.startsWith("Charging stopped"), "Unplugging ends the window with a reason");
        w = started(6 * 60 * MIN);
        check(at(w, 2 * MIN, false, null, false).step == ChargeMeasureWindow.Step.WAIT, "A disconnect may recover before the deadline");
        check(at(w, 4 * MIN, true, 1, true).step == ChargeMeasureWindow.Step.ARM, "Reconnected readings can still arm");
        w = started(6 * 60 * MIN);
        ChargeMeasureWindow.Decision lost = at(w, ChargeMeasureWindow.MAX_MS, false, null, false);
        check(lost.step == ChargeMeasureWindow.Step.NOT_SET && lost.reason.contains("disconnected"), "Disconnected at the deadline is reported, never armed blind");
        w = started(6 * 60 * MIN);
        ChargeMeasureWindow.Decision stale = at(w, ChargeMeasureWindow.MAX_MS, true, null, false);
        check(stale.step == ChargeMeasureWindow.Step.NOT_SET && stale.reason.contains("readings"), "Stale charging state at the deadline is reported");
        w = started(6 * 60 * MIN);
        check(w.evaluate(MONO + MIN, "other", true, 1, true).step == ChargeMeasureWindow.Step.NOT_SET, "Another scooter never inherits the window");
        check(at(started(6 * 60 * MIN), -1, true, 1, true).step == ChargeMeasureWindow.Step.NOT_SET, "A clock reversal fails closed");
        check(at(started(6 * 60 * MIN), MIN, true, 1, true).step == ChargeMeasureWindow.Step.ARM, "An age already known arms at the first evaluation");
        // An edited finish time can only shorten the window, never extend it past its bound.
        w = started(6 * 60 * MIN);
        w.retarget(7 * 60 + 30, MONO + MIN, WALL + MIN, WALL + 5 * MIN);
        check(w.minute() == 7 * 60 + 30 && w.remainingMs(MONO + MIN) == 4 * MIN, "An earlier finish time pulls the deadline to its cautious start");
        check(at(w, 5 * MIN, true, 1, false).step == ChargeMeasureWindow.Step.ARM, "The shortened window arms at the new cautious start");
        w = started(5 * MIN);
        w.retarget(9 * 60, MONO + MIN, WALL + MIN, WALL + 8 * 60 * MIN);
        check(w.remainingMs(MONO + MIN) == 4 * MIN, "A later finish time never extends the window");
        w = started(6 * 60 * MIN);
        w.retarget(7 * 60, MONO + MIN, WALL + MIN, WALL);
        check(w.remainingMs(MONO + MIN) == 0 && at(w, MIN, true, 1, false).step == ChargeMeasureWindow.Step.ARM, "A start already due arms at the next evaluation");
        w = started(6 * 60 * MIN); w.arm(); long before = w.remainingMs(MONO);
        w.retarget(7 * 60, MONO, WALL, WALL + MIN);
        check(w.remainingMs(MONO) == before && w.minute() == 7 * 60, "Arming in flight is never retargeted");
        System.out.println("ChargeMeasureWindow: " + checks + " checks passed");
    }
}
