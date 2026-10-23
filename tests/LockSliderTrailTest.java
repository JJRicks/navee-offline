package com.jjricks.volt;

public final class LockSliderTrailTest {
    private static int checks;
    public static void main(String[] args) {
        LockSliderTrail trail = new LockSliderTrail();
        long w = LockSliderTrail.WINDOW_MS;
        require(trail.tail(1000, .3f) == .3f && trail.isEmpty(), "Empty trail has no length");

        // Steady motion: one unit per window, sampled every 8 ms.
        for (long t = 0; t <= 400; t += 8) trail.record(t / (float) w, t);
        require(near(trail.tail(400, 400 / (float) w), 344 / (float) w), "Tail lags one window behind steady motion");
        require(near(trail.tail(404, 400 / (float) w), 348 / (float) w), "Tail interpolates between samples");

        // Motion stops: the tail catches up within one window and then stays on the thumb.
        float rest = 400 / (float) w;
        require(trail.tail(400 + w / 2, rest) < rest, "Trail still visible just after stopping");
        require(trail.tail(400 + w, rest) == rest && trail.tail(400 + 3 * w, rest) == rest, "Trail collapses after a window");

        // Faster motion leaves a longer trail.
        LockSliderTrail slow = new LockSliderTrail(), fast = new LockSliderTrail();
        for (long t = 0; t <= 200; t += 8) { slow.record(t * .001f, t); fast.record(t * .004f, t); }
        require(.2f - slow.tail(200, .2f) < .8f - fast.tail(200, .8f), "Trail length follows velocity");

        // Reversal: the tail points back along the actual path.
        LockSliderTrail reverse = new LockSliderTrail();
        for (long t = 0; t <= 80; t += 8) reverse.record(t * .01f, t);
        for (long t = 88; t <= 160; t += 8) reverse.record(.8f - (t - 80) * .01f, t);
        require(reverse.tail(160, 0) > 0, "Fast reversal trails behind the thumb");

        // Sparse history only reaches back to the oldest retained sample.
        LockSliderTrail sparse = new LockSliderTrail();
        sparse.record(.5f, 1000);
        require(sparse.tail(1010, .6f) == .5f, "Short history is bounded by its oldest sample");

        // Bounded capacity keeps only recent samples.
        LockSliderTrail bounded = new LockSliderTrail();
        for (long t = 0; t < 100; t++) bounded.record(t, t);
        require(bounded.tail(99, 99) == 99 - LockSliderTrail.CAPACITY + 1, "Capacity bounds history");

        // Repeated timestamps replace rather than grow.
        LockSliderTrail same = new LockSliderTrail();
        same.record(.1f, 50); same.record(.4f, 50);
        require(same.tail(50 + w, .4f) == .4f, "Same-time sample replaced");

        // Clock rollback and explicit clears never leave a ghost.
        LockSliderTrail rollback = new LockSliderTrail();
        rollback.record(0, 500); rollback.record(1, 520);
        require(rollback.tail(100, 1) == 1 && rollback.isEmpty(), "Clock rollback clears the trail");
        rollback.record(0, 600); rollback.record(1, 610); rollback.clear();
        require(rollback.tail(612, 1) == 1, "Clear removes the trail");
        rollback.record(0, 600); rollback.record(1, 610); rollback.record(.5f, 400);
        require(rollback.tail(400, .5f) == .5f, "Backwards sample starts a fresh trail");

        System.out.println("LockSliderTrail: " + checks + " checks passed");
    }
    private static boolean near(float a, float b) { return Math.abs(a - b) < 1e-4f; }
    private static void require(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
}
