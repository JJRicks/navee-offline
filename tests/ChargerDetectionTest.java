package local.navee.offlinelock;

/** Synthetic streams exercise identification boundaries without including owner recordings. */
public final class ChargerDetectionTest {
    private static int checks;
    private static final String SCOPE = "test-scooter:connection-one";
    private static final ChargeSessionStore.Charger STOCK = ChargeSessionStore.Charger.STOCK;
    private static final ChargeSessionStore.Charger FAST = ChargeSessionStore.Charger.HIGH_SPEED;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static ChargeSessionStore.Charger observe(ChargerDetection detector, double soc,
            double volts, double amps, long time) {
        return detector.observe(SCOPE, 2538, true, soc, volts, amps, time, time);
    }

    private static ChargerDetection selected(ChargeSessionStore.Charger charger) {
        ChargerDetection result = new ChargerDetection();
        double current = charger == FAST ? -10.0 : -2.0;
        check(observe(result, 50, 50, current, 1000) == null, "One frame is insufficient");
        check(observe(result, 50, 50, current, 3000) == null, "Two frames are insufficient");
        check(observe(result, 50, 50, current, 5000) == charger, "Three stable frames select charger");
        check(result.selected() == charger, "Accessor agrees with observation");
        return result;
    }

    private static void duplicatesAndEvidence() {
        ChargerDetection d = new ChargerDetection();
        for (int i = 0; i < 200; i++) {
            check(d.observe(SCOPE, 2538, true, 50d, 50d, -10d, 1000, 1000 + i) == null,
                    "Redrawing one frame cannot establish confidence");
        }
        check(observe(d, 50, 50, -10, 3000) == null, "Duplicate renders were not counted");
        check(observe(d, 50, 50, -10, 5000) == FAST, "Distinct stable frames can establish confidence");
        d = new ChargerDetection();
        for (int i = 1; i <= 30; i++)
            check(observe(d, 50, 50, 10, i * 100) == null, "High frame rate still waits for stable duration");
        check(observe(d, 50, 50, 10, 4100) == FAST, "Positive charging-current convention is supported");
        d = new ChargerDetection();
        observe(d, 50, 50, -10, 1000);
        observe(d, 50, 50, -2, 3000);
        check(observe(d, 50, 50, -10, 5000) == null, "Alternating observations cannot accumulate confidence");
        check(observe(d, 50, 50, -10, 7000) == null, "Opposite frame restarted evidence run");
        check(observe(d, 50, 50, -10, 9000) == FAST, "A later stable run can recover");

        d = new ChargerDetection();
        for (int sample = 0; sample < 3; sample++) {
            long frame = 1000 + sample * 10000;
            check(observe(d, 50, 50, -10, frame) == (sample == 2 ? FAST : null),
                    "Ten-second battery polling needs three distinct electrical frames");
            for (int age = 2000; age <= 8000; age += 2000)
                check(d.observe(SCOPE, 2538, true, 50d, 50d, -10d, frame, frame + age)
                                == (sample == 2 ? FAST : null),
                        "Intermediate status renders preserve evidence without counting duplicates");
        }
    }

    private static void taperAndLateStart() {
        for (double[] late : new double[][]{{99, 54.4, .7}, {98, 54.4, 1.7}, {97, 54.4, 2}, {96, 54.4, 128 / 54.4}}) {
            ChargerDetection d = new ChargerDetection();
            for (int i = 1; i <= 12; i++)
                check(observe(d, late[0], late[1], -late[2], i * 2000) == null,
                        "Late overlapping power cannot identify stock at " + late[0]);
        }
        // Rounded aggregate reference points, intentionally synthetic rather than raw owner data.
        double[][] fast = {{16, 46, 10}, {50, 50, 10}, {70, 53.2, 10.1}, {72, 53.4, 10},
                {75, 53.7, 7.8}, {80, 54.1, 4.6}, {85, 54.2, 4.1}, {90, 54.2, 3.75},
                {95, 54.4, 2.85}, {96, 54.4, 2.4}, {97, 54.4, 2.0}, {98, 54.4, 1.7},
                {99, 54.4, 1.3}, {99, 54.4, .7}, {100, 54.4, .68}};
        ChargerDetection d = new ChargerDetection();
        long now = 1000;
        for (int index = 0; index < fast.length; index++) {
            for (int j = 0; j < 3; j++, now += 2000) {
                ChargeSessionStore.Charger actual = observe(d, fast[index][0], fast[index][1], -fast[index][2], now);
                check(actual == (index == 0 && j < 2 ? null : FAST), "Fast inference survives its entire taper");
            }
        }
        for (double soc : new double[]{85.01, 90, 95, 97, 99}) {
            d = new ChargerDetection();
            for (int i = 1; i <= 3; i++)
                check(observe(d, soc, 54, -2, i * 2000) == null, "Low power above safe stock window stays unknown");
        }
        d = selected(STOCK);
        for (int i = 0; i < 10; i++)
            check(observe(d, 90 + i, 54.4, -2 + .17 * i, 7000 + i * 2000) == STOCK,
                    "Previously established stock inference survives its taper");
        d = new ChargerDetection();
        observe(d, 94, 54.4, -3.1, 1000);
        observe(d, 94, 54.4, -3.1, 3000);
        check(observe(d, 94, 54.4, -3.1, 5000) == FAST, "High power identifies fast even late in the session");
    }

    private static void switchesAndGaps() {
        ChargerDetection d = selected(STOCK);
        check(observe(d, 50, 50, -10, 7000) == null, "First contrary fast frame invalidates stock");
        check(observe(d, 50, 50, -10, 9000) == null, "A switch needs stable new evidence");
        check(observe(d, 50, 50, -10, 11000) == FAST, "Stable fast readings select replacement");
        check(observe(d, 50, 50, -2, 13000) == null, "Contrary bulk power invalidates fast");
        check(observe(d, 50, 50, -2, 15000) == null, "Stock replacement also needs stable evidence");
        check(observe(d, 50, 50, -2, 17000) == STOCK, "Stable stock readings select replacement");
        d = selected(FAST);
        check(observe(d, 50, 50, -.8, 7000) == null, "Abnormally low bulk power drops fast without assuming stock");
        d = selected(STOCK);
        check(observe(d, 50, 54, -2.7, 7000) == null, "Power between stock and fast bands remains uncertain");
        d = selected(FAST);
        check(observe(d, 99, 54.4, -.7, 21000) == null, "Long frame gap discards old fast inference");
        d = selected(FAST);
        check(d.observe(SCOPE, 2538, true, 50d, 50d, -10d, 5000, 20001) == null,
                "Cached frame eventually becomes stale even while repeatedly rendered");
        d = selected(FAST);
        check(d.observe(SCOPE, 2538, true, 50d, 50d, -10d, 5000, 20000) == FAST,
                "Exactly fresh-age boundary remains valid");
        check(d.observe(SCOPE, 2538, true, 50d, 50d, -10d, 20000, 20000) == FAST,
                "Fresh replacement retains selection within continuity bound");
        d = selected(FAST);
        check(observe(d, 50, 50, -10, 4000) == null, "Backward source clock clears selection");
        d = selected(FAST);
        check(d.observe(SCOPE, 2538, true, 50d, 50d, -10d, 5000, 6000) == FAST, "Cached fresh render is accepted");
        check(d.observe(SCOPE, 2538, true, 50d, 50d, -10d, 5000, 5500) == null, "Backward observation clock clears selection");
        d = selected(FAST);
        check(d.observe("other:transport", 2538, true, 99d, 54d, -1d, 7000, 7000) == null,
                "Different scope cannot inherit selection");
        check(observe(d, 99, 54, -1, 9000) == null, "Returning to old scope cannot resurrect selection");
        d = selected(FAST);
        d.reset();
        check(d.selected() == null, "Explicit reset clears inference");
        check(observe(d, 99, 54, -1, 7000) == null, "Explicit reset clears taper continuity");
    }

    private static void invalidity() {
        for (int scenario = 0; scenario < 23; scenario++) {
            ChargerDetection d = selected(FAST);
            String scope = SCOPE;
            int pid = 2538;
            boolean charging = true;
            Double soc = 50d, volts = 50d, amps = -10d;
            long frame = 7000, now = 7000;
            switch (scenario) {
                case 0: scope = null; break;
                case 1: scope = ""; break;
                case 2: pid = 9999; break;
                case 3: charging = false; break;
                case 4: soc = null; break;
                case 5: soc = Double.NaN; break;
                case 6: soc = -1d; break;
                case 7: soc = 101d; break;
                case 8: volts = null; break;
                case 9: volts = Double.POSITIVE_INFINITY; break;
                case 10: volts = 34d; break;
                case 11: volts = 61d; break;
                case 12: amps = null; break;
                case 13: amps = Double.NaN; break;
                case 14: amps = Double.NEGATIVE_INFINITY; break;
                case 15: amps = -12.01; break;
                case 16: amps = 0d; break;
                case 17: volts = 60d; amps = -11d; break;
                case 18: frame = 0; break;
                case 19: now = 0; break;
                case 20: frame = 7001; break;
                case 21: now = 22001; break;
                case 22: pid = 0; break;
                default: throw new AssertionError();
            }
            check(d.observe(scope, pid, charging, soc, volts, amps, frame, now) == null,
                    "Invalid input clears inference: " + scenario);
            check(d.selected() == null, "Invalid input clears accessor: " + scenario);
            check(observe(d, 99, 54, -1, 15000) == null, "Invalidation cannot resurrect tapered inference: " + scenario);
        }
        ChargerDetection d = new ChargerDetection();
        for (int i = 1; i < 20; i++)
            check(d.observe(SCOPE, 1, true, 50d, 50d, -10d, i * 2000, i * 2000) == null,
                    "Unknown model never acquires inference");
    }

    public static void main(String[] args) {
        duplicatesAndEvidence();
        taperAndLateStart();
        switchesAndGaps();
        invalidity();
        System.out.println("ChargerDetectionTest: " + checks + " checks passed");
    }
}
