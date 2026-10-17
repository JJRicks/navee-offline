package local.navee.offlinelock;

/** Deterministic clock-driven checks; synthetic identities only. */
public final class RideAutoStopPolicyTest {
    private static int checks;
    private static final String RIDE = "ride-one", PROFILE = "one", SERIAL = "X2538SYNTHETIC0001";
    private static final long T = 1_000_000;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static RideAutoStopPolicy policy() { return new RideAutoStopPolicy(RIDE, PROFILE, SERIAL); }
    /** One fresh motion push received at {@code at} on transport 1, observed at the same instant. */
    private static void push(RideAutoStopPolicy p, long at, int speed, int driving) { p.connected(at, PROFILE, SERIAL, 1, at, at, speed, driving); }
    /** Ride, then stop for {@code seconds} with one push per second; returns the last push time. */
    private static long rideThenPark(RideAutoStopPolicy p, long from, int seconds) {
        push(p, from, 150, 1); push(p, from + 1000, 80, 1);
        long at = from + 2000;
        for (int i = 0; i <= seconds; i++, at += 1000) push(p, at, 0, 0);
        return at - 1000;
    }

    public static void main(String[] args) {
        stationaryDropTimesOut();
        reconnectJustBeforeTimeout();
        repeatedDropsDoNotAccumulate();
        movingAndUncertainLossesStayRecoverable();
        staleAndMissingSpeedNeverArm();
        clockEdges();
        identityProfileAndExplicitStops();
        failureAndLateCallbacks();
        System.out.println("RideAutoStopPolicyTest: " + checks + " assertions passed");
    }

    private static void stationaryDropTimesOut() {
        RideAutoStopPolicy p = policy();
        long last = rideThenPark(p, T, 4);
        // The link goes silent; the 12 s read timeout then reports the loss.
        p.connected(last + 12000, PROFILE, SERIAL, 1, last, last, 0, 0);
        long lost = last + 13000; p.disconnected(lost, true);
        check(p.armed() && p.remainingMs(lost) == RideAutoStopPolicy.DISCONNECT_GRACE_MS, "Parked loss starts the grace");
        p.disconnected(lost + 1000, true);
        check(p.remainingMs(lost + 1000) == 9000, "Repeated loss callbacks do not restart the grace");
        check(!p.due(lost + 9999), "Not due a millisecond early");
        check(p.due(lost + 10000), "Due after ten continuous seconds");
        check(!p.due(lost + 11000) && !p.armed(), "Fires once");
        p = policy(); last = rideThenPark(p, T, 3);
        p.disconnected(last + 500, true);
        check(p.armed(), "Three seconds of zeros across four pushes arm a prompt native disconnect");
        p = policy(); last = rideThenPark(p, T, 4);
        p.disconnected(last + RideAutoStopPolicy.LOSS_WINDOW_MS + 1, true);
        check(!p.armed(), "A loss long after the last frame is not explained by walking away");
    }

    private static void reconnectJustBeforeTimeout() {
        RideAutoStopPolicy p = policy();
        long last = rideThenPark(p, T, 4), lost = last + 2000;
        p.disconnected(lost, true);
        p.connected(lost + 9999, PROFILE, SERIAL, 2, lost + 9999, 0, -1, -1);
        check(!p.armed() && !p.due(lost + 10000) && !p.due(lost + 60000), "Authenticated reconnect just before the deadline cancels");
        p = policy(); last = rideThenPark(p, T, 4); lost = last + 2000;
        p.disconnected(lost, true);
        p.connected(lost + 5000, PROFILE, "", 2, lost + 5000, 0, -1, -1);
        check(!p.armed(), "Reconnect before the serial is read still proves the scooter is near");
    }

    private static void repeatedDropsDoNotAccumulate() {
        RideAutoStopPolicy p = policy();
        long last = rideThenPark(p, T, 4), lost = last + 2000;
        p.disconnected(lost, true);
        long back = lost + 6000; p.connected(back, PROFILE, SERIAL, 2, back, 0, -1, -1);
        p.disconnected(back + 1000, true);
        check(!p.armed(), "A drop right after reconnect has no new stationary evidence");
        check(!p.due(lost + 10000) && !p.due(back + 1000 + 10000), "Separate short gaps never add up");
        // New evidence on the new connection arms a new, separately timed loss.
        long at = back + 12000; for (int i = 0; i < 4; i++, at += 1000) p.connected(at, PROFILE, SERIAL, 3, at, at, 0, 0);
        p.disconnected(at, true);
        check(p.armed() && !p.due(at + 9999) && p.due(at + 10000), "Grace restarts from the latest continuous loss");
    }

    private static void movingAndUncertainLossesStayRecoverable() {
        RideAutoStopPolicy p = policy();
        long last = rideThenPark(p, T, 4);
        push(p, last + 1000, 120, 1); p.disconnected(last + 3000, true);
        check(!p.armed() && !p.due(last + 60000), "Moving loss (tunnel or glitch) keeps recording");
        p = policy(); last = rideThenPark(p, T, 4);
        push(p, last + 1000, 0, 2); p.disconnected(last + 3000, true);
        check(!p.armed(), "Nonzero driving status at zero speed is not parked");
        p = policy(); last = rideThenPark(p, T, 4);
        p.disconnected(last + 2000, false);
        check(!p.armed(), "A non-recoverable loss (owner rejected, Bluetooth off) never arms");
        p = policy(); long at = T;
        for (int i = 0; i < 6; i++, at += 1000) push(p, at, 0, 0);
        p.disconnected(at, true);
        check(!p.armed(), "Zeros from a scooter that never moved this session (startup or restart) never arm");
        p = policy(); push(p, T, 150, 1); push(p, T + 1000, 0, 0); push(p, T + 2000, 0, 0);
        p.disconnected(T + 3000, true);
        check(!p.armed(), "Two zeros two seconds apart are a pause, not a stop");
        p = policy(); push(p, T, 150, 1); push(p, T + 1000, 0, 0); push(p, T + 4500, 0, 0);
        p.disconnected(T + 5000, true);
        check(p.armed(), "Two distinct zero pushes spanning more than three seconds arm");
    }

    private static void staleAndMissingSpeedNeverArm() {
        RideAutoStopPolicy p = policy();
        push(p, T, 150, 1);
        for (int i = 1; i <= 8; i++) p.connected(T + i * 1000, PROFILE, SERIAL, 1, T + i * 1000, T + 1000, 0, 0);
        p.disconnected(T + 9000, true);
        check(!p.armed(), "One zero push seen repeatedly is one sample, not a sustained stop");
        p = policy(); long last = rideThenPark(p, T, 4);
        // Other packets keep arriving but motion pushes stopped: the zeros went stale.
        p.connected(last + 9000, PROFILE, SERIAL, 1, last + 9000, last, 0, 0);
        p.disconnected(last + 10000, true);
        check(!p.armed(), "Zeros older than six seconds at the last frame are stale");
        p = policy(); push(p, T, 150, 1);
        long at = T + 1000; for (int i = 0; i < 5; i++, at += 1000) p.connected(at + 7000, PROFILE, SERIAL, 1, at + 7000, at, 0, 0);
        p.disconnected(at + 7000, true);
        check(!p.armed(), "Pushes first seen more than six seconds late are not fresh evidence");
        p = policy(); last = rideThenPark(p, T, 4);
        push(p, last + 1000, -1, -1); p.disconnected(last + 2000, true);
        check(!p.armed(), "A missing speed push clears the stationary run");
        p = policy(); push(p, T, 150, 1);
        for (int i = 1; i <= 6; i++) p.connected(T + i * 1000, PROFILE, SERIAL, 1, T + i * 1000, 0, -1, -1);
        p.disconnected(T + 7000, true);
        check(!p.armed(), "No motion data after riding never becomes zero");
        p = policy(); last = rideThenPark(p, T, 4);
        p.connected(last + 1000, PROFILE, SERIAL, 1, last + 1000, last - 2500, 0, 0);
        p.disconnected(last + 2000, true);
        check(!p.armed(), "A push timestamp that moves backwards is uncertain");
        p = policy(); push(p, T, 150, 1);
        p.connected(T + 1000, PROFILE, SERIAL, 1, T + 1000, T + 5000, 0, 0);
        p.connected(T + 6000, PROFILE, SERIAL, 1, T + 6000, T + 5000, 0, 0);
        p.disconnected(T + 6500, true);
        check(!p.armed(), "A push stamped in the future is rejected");
    }

    private static void clockEdges() {
        RideAutoStopPolicy p = policy();
        long last = rideThenPark(p, T, 4), lost = last + 2000;
        p.disconnected(lost, true);
        check(!p.due(lost - 1) && !p.armed(), "Clock rollback while waiting cancels instead of guessing");
        check(!p.due(lost + 20000), "Nothing fires after a rollback");
        p = policy(); last = rideThenPark(p, T, 4);
        p.connected(last - 5000, PROFILE, SERIAL, 1, last - 5000, last - 5000, 0, 0);
        p.disconnected(last, true);
        check(!p.armed(), "Rollback during the stop discards the run");
        p = policy(); last = rideThenPark(p, T, 4);
        p.disconnected(0, true);
        check(!p.armed(), "A zero clock is invalid");
        p = policy(); last = rideThenPark(p, T, 4); lost = last + 1000;
        p.disconnected(lost, true);
        check(p.due(lost + RideAutoStopPolicy.DISCONNECT_GRACE_MS + 3_600_000L), "A long sleep is still one continuous disconnection");
    }

    private static void identityProfileAndExplicitStops() {
        RideAutoStopPolicy p = policy();
        long at = T; p.connected(at, PROFILE, "OTHER2538SYNTHETIC", 1, at, at, 150, 1);
        for (int i = 1; i <= 5; i++) p.connected(at + i * 1000, PROFILE, "OTHER2538SYNTHETIC", 1, at + i * 1000, at + i * 1000, 0, 0);
        p.disconnected(at + 6000, true);
        check(!p.armed(), "Another scooter's zeros are not evidence");
        p = policy(); p.connected(at, "two", SERIAL, 1, at, at, 150, 1);
        for (int i = 1; i <= 5; i++) p.connected(at + i * 1000, "two", SERIAL, 1, at + i * 1000, at + i * 1000, 0, 0);
        p.disconnected(at + 6000, true);
        check(!p.armed(), "Another profile's zeros are not evidence");
        p = policy(); long last = rideThenPark(p, T, 4); p.disconnected(last + 1000, true);
        p.reset();
        check(!p.armed() && !p.due(last + 20000), "Explicit stop, user disconnect, setting off or new profile cancels");
        p = policy(); last = rideThenPark(p, T, 4); p.reset();
        p.disconnected(last + 1000, true);
        check(!p.armed(), "Reset forgets earlier stationary evidence");
        p = policy(); last = rideThenPark(p, T, 4);
        at = last + 1000; for (int i = 0; i < 5; i++, at += 1000) p.connected(at, PROFILE, SERIAL, 7, at, at, 0, 0);
        p.disconnected(at, true);
        check(p.armed(), "Motion earlier in the ride is remembered across a reconnect");
        p = policy(); last = rideThenPark(p, T, 4);
        p.connected(last + 1000, PROFILE, SERIAL, 7, last + 1000, last + 1000, 0, 0);
        p.disconnected(last + 1500, true);
        check(!p.armed(), "A new transport restarts the stationary run");
        boolean threw = false;
        try { new RideAutoStopPolicy(RIDE, PROFILE, ""); } catch (IllegalArgumentException expected) { threw = true; }
        check(threw, "A policy needs a verified serial");
    }

    /** Mirrors ScooterService: one policy per ride id, fired once, guarded by owner and instance. */
    private static void failureAndLateCallbacks() {
        RideAutoStopPolicy old = policy();
        long last = rideThenPark(old, T, 4), lost = last + 1000;
        old.disconnected(lost, true);
        check(old.due(lost + 10000), "First attempt");
        // Storage failed: the ride stays recording. No automatic retry storm on later ticks.
        check(!old.due(lost + 11000) && !old.due(lost + 60000), "A failed save is not retried automatically");
        old.connected(lost + 70000, PROFILE, SERIAL, 2, lost + 70000, 0, -1, -1);
        check(!old.armed(), "After a failed save, reconnecting returns to ordinary recording");

        RideAutoStopPolicy first = policy(); last = rideThenPark(first, T, 4); first.disconnected(last + 1000, true);
        RideAutoStopPolicy next = new RideAutoStopPolicy("ride-two", PROFILE, SERIAL);
        check(!first.owns("ride-two") && next.owns("ride-two"), "A late decision from the previous ride's policy cannot match the new ride");
        check(!next.armed() && !next.due(last + 20000), "A new ride starts unarmed");
        RideAutoStopPolicy restarted = policy();
        restarted.disconnected(T + 5000, true);
        check(!restarted.armed() && !restarted.due(T + 60000), "A restarted service sees the gap but never treats it as parking");
    }
}
