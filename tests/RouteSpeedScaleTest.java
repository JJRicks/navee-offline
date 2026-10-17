package local.navee.offlinelock;

import java.io.File;
import java.nio.file.Files;

/** Speed route source, scale and colour choices; units affect labels only. */
public final class RouteSpeedScaleTest {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static Double[] nulls(int n) { return new Double[n]; }

    private static Double[] constant(int n, double v) {
        Double[] out = new Double[n];
        for (int i = 0; i < n; i++) out[i] = v;
        return out;
    }

    private static void delete(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File child : children) delete(child);
        f.delete();
    }

    private static int red(int c) { return c >> 16 & 0xff; }

    public static void main(String[] args) throws Exception {
        // Stops, slow and fast riding on GPS only.
        Double[] gps = new Double[60];
        for (int i = 0; i < 60; i++) gps[i] = i < 10 ? 0.0 : i < 30 ? 10.0 : 25.0;
        RouteSpeedScale g = RouteSpeedScale.of(gps, nulls(60));
        check(RouteSpeedScale.GPS.equals(g.source) && "Speed · GPS".equals(g.title()), "GPS-only ride uses and names GPS");
        check(g.topKmh == 30, "Robust top rounds up past steady fast speed: " + g.topKmh);
        check(g.color(0) == RouteSpeedScale.LOW, "Stop is the low end, not no-data");
        check(red(g.color(15)) < red(g.color(45)), "Fast segment is warmer than slow");
        check(g.color(45) != RouteSpeedScale.HIGH, "Steady fast speed is not clipped to the end");

        // Scooter speed preferred when fresh for most points, applied to the whole ride.
        Double[] scooter = constant(60, 20.0); scooter[5] = null; scooter[6] = null;
        RouteSpeedScale s = RouteSpeedScale.of(gps, scooter);
        check(RouteSpeedScale.SCOOTER.equals(s.source) && "Speed · scooter".equals(s.title()), "Mostly fresh scooter speed wins");
        check(s.kmh(5) == null && s.color(5) == RouteSpeedScale.NO_DATA, "Missing scooter sample is no-data, not GPS or zero");
        check(s.kmh(0) == 20.0, "Scooter value used even where GPS shows a stop");

        // Scooter-only and sparse scooter data.
        RouteSpeedScale only = RouteSpeedScale.of(nulls(20), constant(20, 15.0));
        check(RouteSpeedScale.SCOOTER.equals(only.source), "Scooter-only ride uses scooter speed");
        Double[] sparse = nulls(60); for (int i = 0; i < 10; i++) sparse[i] = 18.0;
        RouteSpeedScale fallback = RouteSpeedScale.of(gps, sparse);
        check(RouteSpeedScale.GPS.equals(fallback.source), "Sparse scooter speed falls back to GPS for the whole ride");
        check(fallback.kmh(0) == 0.0, "Fallback never mixes in scooter samples");

        // Nothing measured.
        RouteSpeedScale none = RouteSpeedScale.of(nulls(5), nulls(5));
        check(none.source == null && none.color(0) == RouteSpeedScale.NO_DATA && "Speed".equals(none.title()), "No speed is all no-data");
        check(RouteSpeedScale.of(new Double[0], new Double[0]).source == null, "Empty ride is safe");
        Double[] odd = {Double.NaN, Double.POSITIVE_INFINITY, 12.0};
        RouteSpeedScale bad = RouteSpeedScale.of(odd, nulls(3));
        check(bad.kmh(0) == null && bad.kmh(1) == null && bad.kmh(2) == 12.0, "Non-finite values are missing");
        check(bad.kmh(-1) == null && bad.kmh(3) == null, "Out-of-range index is missing");

        // One absurd spike neither stretches the scale nor flattens the colours; it clips.
        Double[] spiky = new Double[40];
        for (int i = 0; i < 40; i++) spiky[i] = 10.0 + i % 10;
        spiky[20] = 89.0;
        RouteSpeedScale spike = RouteSpeedScale.of(spiky, nulls(40));
        check(spike.topKmh == 20 || spike.topKmh == 25, "Spike ignored by scale: " + spike.topKmh);
        check(spike.color(20) == RouteSpeedScale.HIGH, "Spike clips to the top colour");
        check(red(spike.color(0)) < red(spike.color(9)), "Normal speeds keep a spread");
        Double[] shortSpike = {20.0, 21.0, 19.0, 20.0, 22.0, 20.0, 88.0, 21.0, 20.0, 19.0};
        check(RouteSpeedScale.of(shortSpike, nulls(10)).topKmh <= 30, "Short ride spike also ignored");

        // All-zero and constant tracks.
        RouteSpeedScale zero = RouteSpeedScale.of(constant(30, 0.0), nulls(30));
        check(zero.topKmh == RouteSpeedScale.MIN_TOP_KMH && zero.color(3) == RouteSpeedScale.LOW, "All-zero ride shows stopped, with a minimum span");
        RouteSpeedScale steady = RouteSpeedScale.of(constant(30, 20.0), nulls(30));
        check(steady.topKmh == 25 && steady.fraction(20) == .8, "Constant speed sits below the clipped end");
        check(RouteSpeedScale.of(constant(30, 3.0), nulls(30)).topKmh == RouteSpeedScale.MIN_TOP_KMH, "Slow constant speed keeps the minimum span");
        // Long stops neither collapse the scale nor clip all riding to the top colour.
        Double[] parked = new Double[100];
        for (int i = 0; i < 100; i++) parked[i] = i < 80 ? 0.0 : 15.0 + (i - 80) / 2.0;
        RouteSpeedScale stopped = RouteSpeedScale.of(parked, nulls(100));
        check(stopped.topKmh == 25, "Mostly-parked ride scales to its moving speed: " + stopped.topKmh);
        check(red(stopped.color(80)) < red(stopped.color(99)) && stopped.color(90) != RouteSpeedScale.HIGH, "Moving speeds keep a spread after long stops");
        Double[] governed = constant(40, 25.0); for (int i = 36; i < 40; i++) governed[i] = 33.0;
        RouteSpeedScale limit = RouteSpeedScale.of(governed, nulls(40));
        check(limit.topKmh == 35 && limit.color(39) != RouteSpeedScale.HIGH, "Held speed limit keeps faster stretches below the top: " + limit.topKmh);
        Double[] negative = {-1.0, 5.0};
        check(RouteSpeedScale.of(negative, nulls(2)).kmh(0) == 0.0, "Negative speed reads as stopped");

        // Units change labels only.
        int before = g.color(15);
        check(RouteSpeedScale.label(30, "km").equals("30 km/h"), "Metric label");
        check(RouteSpeedScale.label(30, "mi").equals("19 mph"), "Imperial label");
        check(RouteSpeedScale.label(30, "dual").equals("19 mph / 30 km/h"), "Dual label");
        check(g.color(15) == before && g.color(10.0) == before, "Colour depends on km/h only");
        check(RouteSpeedScale.blend(0xff000000, 0xffffffff, 2) == 0xffffffff && RouteSpeedScale.blend(0xff000000, 0xffffffff, -1) == 0xff000000, "Blend clips");

        // Through the real loader: fresh telemetry wins, gaps keep their own segments, stale scooter speed is not used.
        File dir = Files.createTempDirectory("route-speed-test").toFile();
        try {
            PhoneRideStore store = new PhoneRideStore(dir);
            store.start("ride", 1700000000000L, 1000, "mounted");
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 20; i++) {
                long mono = 1000 + i * 1000, wall = 1700000000000L + mono;
                if (i == 10) b.append(PhoneRideStore.row("GAP", wall - 500, mono - 500));
                // Telemetry sampled at the fix, but its speed reading is stale for i == 3.
                b.append(PhoneRideStore.row("TELEMETRY", wall, mono, 12 + i, 400, 80, i == 3 ? mono - 9000 : mono, mono, mono));
                b.append(PhoneRideStore.row("GPS", wall, mono, 43.82 + i * .00004, -111.78, 3, 4.0, .2, 1500, 4));
            }
            store.appendBatch("ride", b.toString(), "");
            store.stop("ride", 1700000021000L, 21000);
            PhoneRideStore.Track track = store.load("ride");
            RouteSpeedScale loaded = RouteSpeedScale.of(track.points);
            check(track.points.size() == 20, "All fixes drawn: " + track.points.size());
            check(RouteSpeedScale.SCOOTER.equals(loaded.source), "Fresh scooter speed chosen from recording");
            check(loaded.kmh(3) == null, "Stale scooter speed is missing, not GPS or zero");
            check(loaded.kmh(4) == 16.0, "Matched scooter speed kept in km/h");
            check(track.points.get(10).gapBefore && track.points.get(10).segment != track.points.get(9).segment, "Gap separates route segments");

            store.start("gps", 1700000000000L, 1000, "mounted");
            b.setLength(0);
            for (int i = 0; i < 10; i++) {
                long mono = 1000 + i * 1000;
                b.append(PhoneRideStore.row("GPS", 1700000000000L + mono, mono, 43.82 + i * .00004, -111.78, 3, i < 3 ? 0.0 : 5.0, .2, 1500, 4));
            }
            store.appendBatch("gps", b.toString(), "");
            store.stop("gps", 1700000011000L, 11000);
            RouteSpeedScale gpsLoaded = RouteSpeedScale.of(store.load("gps").points);
            check(RouteSpeedScale.GPS.equals(gpsLoaded.source) && Math.abs(gpsLoaded.kmh(5) - 18) < 1e-9, "GPS m/s converted to km/h");
            check(gpsLoaded.kmh(0) == 0.0 && gpsLoaded.color(0) == RouteSpeedScale.LOW, "Recorded stop stays a stop");
        } finally {
            delete(dir);
        }
        System.out.println("RouteSpeedScaleTest: " + checks + " checks passed");
    }
}
