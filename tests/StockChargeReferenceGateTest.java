package local.navee.offlinelock;

/** Runtime continuity never outlives fresh measurements or a short bounded power mismatch. */
public final class StockChargeReferenceGateTest {
    private static int checks;
    private static final String SCOPE = "scooter-one:stock:connection-one";

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void near(double expected, Double actual, double tolerance, String message) {
        check(actual != null && Math.abs(expected - actual) <= tolerance, message + ": " + actual);
    }

    private static ChargingEstimate.Value live(Double value, long frame) {
        return new ChargingEstimate.Value(value, true, frame);
    }

    private static ChargingEstimate.Result baseline(double soc, double watts, Double target, long frame) {
        return ChargingEstimate.calculate(live(1d, frame), live(54d, frame), live(-watts / 54, frame),
                live(soc, frame), live(15.3, frame), live(target, frame));
    }

    private static ChargingEstimate.Result estimate(StockChargeReferenceGate gate, double soc,
            double watts, Double target, long frame, long now) {
        return ChargingEstimate.forScooter(baseline(soc, watts, target, frame), live(soc, frame),
                live(15.3, frame), 2538, null, null, gate, SCOPE, frame, now);
    }

    private static StockChargeReferenceGate entered() {
        StockChargeReferenceGate gate = new StockChargeReferenceGate();
        check(gate.accepts(SCOPE, 95, 95, 1000, 1000), "Compatible fresh power enters the reference");
        return gate;
    }

    private static void edgeJitter(double soc, double matchingWatts, double nearbyWatts) {
        StockChargeReferenceGate gate = new StockChargeReferenceGate();
        check(!gate.accepts(SCOPE, soc, nearbyWatts, 1000, 1000), "Soft-band power alone cannot enter");
        check(gate.accepts(SCOPE, soc, matchingWatts, 2000, 2000), "Strict match enters");
        check(gate.accepts(SCOPE, soc, nearbyWatts, 3000, 3000), "A nearby power dip keeps the reference");
        for (int i = 0; i < 20; i++)
            check(gate.accepts(SCOPE, soc, nearbyWatts, 3000, 3000 + i), "Repeated UI reads do not consume frame grace");
        check(gate.accepts(SCOPE, soc, matchingWatts, 4000, 4000), "A strict frame clears the mismatch streak");
        check(gate.accepts(SCOPE, soc, nearbyWatts, 5000, 5000), "A later nearby dip begins a fresh grace period");
        check(gate.accepts(SCOPE, soc, nearbyWatts, 6000, 6000), "Two distinct soft misses remain boundedly eligible");
        check(!gate.accepts(SCOPE, soc, nearbyWatts, 7000, 7000), "The third distinct soft miss ends reference use");
        check(!gate.accepts(SCOPE, soc, nearbyWatts, 8000, 8000), "An expired grace cannot restart from soft power");
        check(gate.accepts(SCOPE, soc, matchingWatts, 9000, 9000), "A new strict match can establish compatibility again");
    }

    private static void verifyGate() {
        edgeJitter(95, 95, 94.5);
        edgeJitter(98, 93, 92.5);

        StockChargeReferenceGate gate = entered();
        check(gate.accepts(SCOPE, 95, 94.5, 2000, 2000), "Time limit starts at the first power miss");
        check(gate.accepts(SCOPE, 95, 94.5, 2000, 16999), "One repeatedly displayed miss may survive just under fifteen seconds");
        check(!gate.accepts(SCOPE, 95, 94.5, 2000, 17000), "Fifteen-second limit expires even without a new frame");
        check(!gate.accepts(SCOPE, 95, 94.5, 18000, 18000), "Time-expired grace does not restart from soft power");

        for (double power : new double[]{1, 70, 145, 250, Double.NaN, Double.POSITIVE_INFINITY}) {
            gate = entered();
            check(!gate.accepts(SCOPE, 95, power, 2000, 2000), "Hard incompatible or invalid power immediately rejects: " + power);
            check(!gate.accepts(SCOPE, 95, 94.5, 3000, 3000), "Hard rejection clears prior compatibility");
        }
        gate = entered();
        gate.reset();
        check(!gate.accepts(SCOPE, 95, 94.5, 2000, 2000), "Explicit lifecycle reset clears compatibility");
        gate = entered();
        check(!gate.accepts("another-scooter:stock:connection-two", 95, 94.5, 2000, 2000), "A different scope cannot inherit compatibility");
        check(!gate.accepts(SCOPE, 95, 94.5, 3000, 3000), "Switching back cannot resurrect the old scope");

        for (String invalidScope : new String[]{null, ""}) {
            gate = entered();
            check(!gate.accepts(invalidScope, 95, 95, 2000, 2000), "Missing context cannot select the reference");
            check(!gate.accepts(SCOPE, 95, 94.5, 3000, 3000), "Missing context clears compatibility");
        }
        for (long invalidFrame : new long[]{0, -1}) {
            gate = entered();
            check(!gate.accepts(SCOPE, 95, 95, invalidFrame, 2000), "Unknown source frame cannot enter or retain");
            check(!gate.accepts(SCOPE, 95, 94.5, 3000, 3000), "Unknown frame clears compatibility");
        }
        for (long invalidNow : new long[]{0, -1}) {
            gate = entered();
            check(!gate.accepts(SCOPE, 95, 95, 2000, invalidNow), "Invalid observation clock cannot enter or retain");
            check(!gate.accepts(SCOPE, 95, 94.5, 3000, 3000), "Invalid observation clock clears compatibility");
        }
        gate = entered();
        check(gate.accepts(SCOPE, 95, 95, 3000, 3000), "Later source frame advances normally");
        check(!gate.accepts(SCOPE, 95, 94.5, 2000, 4000), "Backward source frame clears compatibility");
        gate = entered();
        check(gate.accepts(SCOPE, 95, 95, 1500, 3000), "Repeated-frame timing can advance independently");
        check(!gate.accepts(SCOPE, 95, 94.5, 1500, 2500), "Backward observation clock clears compatibility");
    }

    private static void verifyComposition() throws Exception {
        StockChargeReferenceGate taperGate = new StockChargeReferenceGate();
        for (int i = 0; i < 4; i++) {
            double watts = i == 0 ? 32 : 31, volts = 54.387;
            long frame = (i + 1) * 1000;
            ChargingEstimate.Result base = ChargingEstimate.calculate(live(1d, frame), live(volts, frame),
                    live(-watts / volts, frame), live(99d, frame), live(15.3, frame), live(100d, frame));
            ChargingEstimate.Result result = ChargingEstimate.forScooter(base, live(99d, frame), live(15.3, frame),
                    2538, null, null, taperGate, SCOPE, frame, frame);
            check(result.referenceToFull == (i < 3), "The 99% matcher honors two-frame grace, then expires");
            if (i < 3) check(result.remainingMinutesToFull < 1, "An accepted taper grace cannot jump to a sixteen-minute live-rate ETA");
            else near(base.remainingMinutesToFull, result.remainingMinutesToFull, 0, "Expired taper grace uses the fresh baseline");
        }
        for (double[] edge : new double[][]{{95, 95, 94.5}, {98, 93, 92.5}}) {
            StockChargeReferenceGate gate = new StockChargeReferenceGate();
            ChargingEstimate.Result first = estimate(gate, edge[0], edge[1], 100d, 1000, 1000);
            ChargingEstimate.Result dip = estimate(gate, edge[0], edge[2], 100d, 2000, 2000);
            ChargingEstimate.Result recovered = estimate(gate, edge[0], edge[1], 100d, 3000, 3000);
            check(first.referenceToFull && dip.referenceToFull && recovered.referenceToFull,
                    "Brief edge jitter keeps the same estimate source at SOC " + edge[0]);
            near(first.remainingMinutesToFull, dip.remainingMinutesToFull, .00001, "Jitter does not cause a reference/live-rate ETA cliff");
            near(first.remainingMinutesToFull, recovered.remainingMinutesToFull, .00001, "Recovery preserves the reference estimate");
            near(edge[2], dip.inputWatts, .00001, "The displayed power still uses the current frame");
            check(!dip.learnedToFull && !dip.learnedToLimit && dip.learnedSessions == 0,
                    "Runtime gate never labels bundled evidence as a personal charge");
        }
        StockChargeReferenceGate gate = new StockChargeReferenceGate();
        estimate(gate, 95, 95, 100d, 1000, 1000);
        estimate(gate, 95, 94.5, 100d, 2000, 2000);
        estimate(gate, 95, 94.5, 100d, 3000, 3000);
        ChargingEstimate.Result expired = estimate(gate, 95, 94.5, 100d, 4000, 4000);
        check(!expired.referenceToFull && !expired.referenceToLimit, "Sustained drift returns both targets to live-rate estimates");
        near(baseline(95, 94.5, 100d, 4000).remainingMinutesToFull, expired.remainingMinutesToFull, 0,
                "Fallback is freshly calculated rather than retaining an earlier ETA");

        for (int invalid : new int[]{0, 1, 2, 3, 4, 5, 6, 7}) {
            gate = new StockChargeReferenceGate();
            estimate(gate, 95, 95, 100d, 1000, 1000);
            ChargingEstimate.Value soc = live(95d, 2000), rated = live(15.3, 2000);
            ChargingEstimate.Result base = baseline(95, 94.5, 100d, 2000);
            int pid = 2538;
            ChargeSessionStore.Charger charger = null;
            if (invalid == 0) base = null;
            if (invalid == 1) base = ChargingEstimate.calculate(live(0d, 2000), live(54d, 2000), live(-2d, 2000), soc, rated, live(100d, 2000));
            if (invalid == 2) base = ChargingEstimate.calculate(live(1d, 2000), live(54d, 2000), new ChargingEstimate.Value(-2d, false, 2000), soc, rated, live(100d, 2000));
            if (invalid == 3) soc = new ChargingEstimate.Value(95d, false, 2000);
            if (invalid == 4) rated = new ChargingEstimate.Value(15.3, false, 2000);
            if (invalid == 5) pid = 9999;
            if (invalid == 6) charger = ChargeSessionStore.Charger.HIGH_SPEED;
            if (invalid == 7) base = ChargingEstimate.calculate(live(1d, 2000), live(54d, 2000), live(-2d, 2001), soc, rated, live(100d, 2000));
            ChargingEstimate.Result blocked = ChargingEstimate.forScooter(base, soc, rated, pid, charger, null,
                    gate, SCOPE, 2000, 2000);
            check(blocked == base, "Unavailable inputs or excluded models preserve their original baseline: " + invalid);
            check(!estimate(gate, 95, 94.5, 100d, 3000, 3000).referenceToFull,
                    "Unavailable inputs and explicit exclusions clear prior eligibility: " + invalid);
        }

        gate = new StockChargeReferenceGate();
        estimate(gate, 95, 95, 100d, 1000, 1000);
        ChargingEstimate.Result unknownCutoff = estimate(gate, 95, 94.5, null, 2000, 2000);
        check(unknownCutoff.referenceToFull && !unknownCutoff.referenceToLimit && unknownCutoff.remainingMinutesToLimit == null,
                "Continuity never invents an unknown cutoff");
        gate = new StockChargeReferenceGate();
        estimate(gate, 95, 95, 98d, 1000, 1000);
        ChargingEstimate.Result lowerCutoff = estimate(gate, 95, 94.5, 98d, 2000, 2000);
        near((41.03345 + 47.36630 - 28.39457 - 33.75782) / 2, lowerCutoff.remainingMinutesToLimit, .00001,
                "A lower configured cutoff excludes the finishing time after that boundary");
        check(lowerCutoff.referenceToFull && lowerCutoff.referenceToLimit && lowerCutoff.status.contains("higher charge limit"),
                "Per-target reference flags and hypothetical-full explanation remain accurate");

        ChargeSessionStore store = ChargeSessionStoreTest.store();
        for (int i = 0; i < 2; i++)
            ChargeSessionStoreTest.session(store, "one", ChargeSessionStore.Charger.STOCK, 40, 80, 60000, false, false);
        ChargeSessionStore.Curve personal = ChargeSessionStoreTest.curve(store, "one", ChargeSessionStore.Charger.STOCK);
        ChargingEstimate.Result mixed = ChargingEstimate.forScooter(baseline(50, 104, 80d, 1000), live(50d, 1000),
                live(15.3, 1000), 2538, ChargeSessionStore.Charger.STOCK, personal,
                new StockChargeReferenceGate(), SCOPE, 1000, 1000);
        check(mixed.learnedToLimit && mixed.referenceToFull && !mixed.learnedToFull && !mixed.referenceToLimit,
                "A runtime gate preserves independent personal-cutoff and bundled-full sources");
        near(30, mixed.remainingMinutesToLimit, 0, "Qualified personal cutoff still refines its supported target");
    }

    public static void main(String[] args) throws Exception {
        verifyGate();
        verifyComposition();
        System.out.println("StockChargeReferenceGate: " + checks + " checks passed");
    }
}
