package com.jjricks.volt;

/** Synthetic streams exercise identification boundaries without including owner recordings. */
public final class ChargerDetectionTest {
    private static int checks;
    private static final String SCOPE = "test-scooter";
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
        check(result.selected(SCOPE, 5000) == charger, "Accessor agrees with observation");
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
        // Stock power stays near 103-119 W until 99%, then tapers to about 37 W.
        for (int i = 0; i < 10; i++)
            check(observe(d, 89 + i, 54, -(i % 2 == 0 ? 104 : 116) / 54d, 7000 + i * 2000) == STOCK,
                    "Previously established stock inference survives its finish");
        for (int i = 0; i < 8; i++)
            check(observe(d, 99, 54.3, -(110 - i * 10) / 54.3, 27000 + i * 2000) == STOCK,
                    "Previously established stock inference survives its 99% taper");
        d = new ChargerDetection();
        observe(d, 94, 54.4, -3.1, 1000);
        observe(d, 94, 54.4, -3.1, 3000);
        check(observe(d, 94, 54.4, -3.1, 5000) == FAST, "High power identifies fast even late in the session");
    }

    private static void switchesAndGaps() {
        ChargerDetection d = selected(STOCK);
        check(observe(d, 50, 50, -10, 7000) == STOCK, "One contrary frame cannot erase a confident family");
        check(observe(d, 50, 50, -10, 9000) == STOCK, "A switch needs sustained contrary evidence");
        check(observe(d, 50, 50, -10, 11000) == FAST, "Stable fast readings select replacement");
        check(observe(d, 50, 50, -2, 13000) == FAST, "Contrary bulk power must persist");
        check(observe(d, 50, 50, -2, 15000) == FAST, "Stock replacement also needs stable evidence");
        check(observe(d, 50, 50, -2, 17000) == STOCK, "Stable stock readings select replacement");
        d = selected(FAST);
        check(observe(d, 50, 50, -.8, 7000) == FAST && observe(d, 50, 50, -.8, 9000) == FAST,
                "Brief low bulk power keeps the family; the reference itself rejects the frames");
        check(observe(d, 50, 50, -.8, 11000) == null, "Sustained abnormal bulk power drops fast without assuming stock");
        d = selected(STOCK);
        check(observe(d, 50, 54, -2.6, 7000) == STOCK && observe(d, 50, 54, -2.6, 11000) == STOCK,
                "Power still inside the stock reference's own envelope keeps stock");
        d = selected(STOCK);
        check(observe(d, 50, 54, -2.75, 7000) == STOCK && observe(d, 50, 54, -2.75, 9000) == STOCK
                && observe(d, 50, 54, -2.75, 11000) == null, "Sustained power between both envelopes becomes uncertain");
        d = selected(FAST);
        check(observe(d, 99, 54.4, -.7, 21000) == FAST, "One missed ten-second poll keeps the taper's family");
        check(observe(d, 99, 54.4, -.7, 21000 + ChargerDetection.RETAIN_MS) == FAST, "Retention is measured from the last usable frame");
        check(observe(d, 99, 54.4, -.7, 21001 + 2 * ChargerDetection.RETAIN_MS) == null, "Long silence discards old fast inference");
        d = selected(FAST);
        check(d.selected(SCOPE, 5000 + ChargerDetection.RETAIN_MS) == FAST, "Accessor keeps a recent choice");
        check(d.selected(SCOPE, 5001 + ChargerDetection.RETAIN_MS) == null, "Accessor expires without an update");
        check(d.selected("other", 6000) == null && d.selected(null, 6000) == null, "Accessor checks the profile");
        check(d.selected(SCOPE, 4000) == null, "Accessor refuses a clock before its evidence");
        check(d.observe(SCOPE, 2538, true, 50d, 50d, -10d, 5000, 20000) == FAST, "Re-rendering a frame adds nothing");
        check(d.selected(SCOPE, 5001 + ChargerDetection.RETAIN_MS) == null, "Repeated renders cannot refresh retention");
        d = selected(FAST);
        check(d.observe(SCOPE, 2538, true, 50d, 50d, -10d, 4000, 6000) == FAST, "A backward source frame is ignored");
        check(d.observe(SCOPE, 2538, true, 50d, 50d, -10d, 5000, 4500) == null, "Backward observation clock clears selection");
        d = selected(FAST);
        check(d.observe("other", 2538, true, 99d, 54d, -1d, 7000, 7000) == null,
                "Different profile cannot inherit selection");
        check(observe(d, 99, 54, -1, 9000) == null, "Returning to old profile cannot resurrect selection");
        d = selected(FAST);
        d.reset();
        check(d.selected(SCOPE, 5000) == null, "Explicit reset clears inference");
        check(observe(d, 99, 54, -1, 7000) == null, "Explicit reset clears taper continuity");
    }

    /** One physical charge keeps its family across reconnects and isolated unusable frames. */
    private static void sameCharge() {
        ChargerDetection d = selected(FAST);
        check(observe(d, 97, 54.318, -2.21, 40000) == FAST, "A reconnect within the same charge keeps the family");
        check(d.observe(SCOPE, 2538, true, 97d, 54.318, 0d, 42000, 42000) == FAST, "One zero-current frame is ignored");
        check(d.observe(SCOPE, 2538, true, 97d, null, null, 44000, 44000) == FAST, "One unpaired frame is ignored");
        check(d.observe(SCOPE, 2538, true, 97d, 70d, -2.21, 46000, 46000) == FAST, "One implausible frame is ignored");
        check(d.observe(SCOPE, 2538, true, 97d, 54.318, -2.21, 48001, 48000) == FAST, "One future-stamped frame is ignored");
        check(observe(d, 97, 54.318, -2.21, 50000) == FAST, "Normal frames continue the remembered taper");
        check(observe(d, 96.5, 54.318, -2.21, 52000) == FAST, "Gauge jitter below one point is not a new charge");
        check(observe(d, 95, 54.318, -2.21, 54000) == null, "A falling battery level ends the charge");
        d = selected(FAST);
        check(d.observe(SCOPE, 2538, false, 60d, 51d, -10d, 7000, 7000) == null, "Charging off ends the charge");
        check(observe(d, 97, 54.318, -2.21, 9000) == null, "Late taper after unplugging cannot inherit fast");
        d = selected(FAST);
        check(d.observe(SCOPE, 1, true, 60d, 51d, -10d, 7000, 7000) == null, "Another model clears inference");
        check(observe(d, 97, 54.318, -2.21, 9000) == null, "Model change cannot resurrect inference");
        // Every calibration-like fast frame stays inside the fast envelope, so the remembered
        // family never flickers through the taper or the long 99% finish.
        d = new ChargerDetection();
        long now = 1000;
        for (double soc = 16; soc < 72; soc += 1, now += 2000) observe(d, soc, 47 + soc * .09, -10.09, now);
        check(d.selected(SCOPE, now) == FAST, "Bulk frames select fast");
        double[][] taper = {{72, 53.42, 516.76 / 53.42}, {81, 54.09, 235.3 / 54.09}, {90, 54.15, 200.94 / 54.15},
                {95, 54.23, 144.37 / 54.23}, {99, 54.39, 85.93 / 54.39}, {99, 54.48, 37.05 / 54.48}};
        for (double[] row : taper) for (int i = 0; i < 3; i++, now += 2000)
            check(observe(d, row[0], row[1], -row[2], now) == FAST, "Fast taper keeps its family at " + row[0]);
    }

    private static void invalidity() {
        for (int scenario = 0; scenario < 23; scenario++) {
            ChargerDetection d = selected(FAST);
            String scope = SCOPE;
            int pid = 2538;
            boolean charging = true, clears = false;
            Double soc = 50d, volts = 50d, amps = -10d;
            long frame = 7000, now = 7000;
            switch (scenario) {
                case 0: scope = null; break;
                case 1: scope = ""; break;
                case 2: pid = 9999; clears = true; break;
                case 3: charging = false; clears = true; break;
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
                case 21: soc = 48d; clears = true; break;
                case 22: pid = 0; clears = true; break;
                default: throw new AssertionError();
            }
            ChargeSessionStore.Charger result = d.observe(scope, pid, charging, soc, volts, amps, frame, now);
            check(scope == null || scope.isEmpty() || now <= 0 ? result == null : result == (clears ? null : FAST),
                    "Unusable input is isolated; boundaries clear inference: " + scenario);
            check(d.selected(SCOPE, 7000) == (clears ? null : FAST), "Accessor agrees: " + scenario);
            check(observe(d, 99, 54.4, -1, 9000) == (clears ? null : FAST),
                    "Only a boundary prevents the taper keeping its family: " + scenario);
        }
        ChargerDetection d = new ChargerDetection();
        for (int i = 1; i < 20; i++)
            check(d.observe(SCOPE, 1, true, 50d, 50d, -10d, i * 2000, i * 2000) == null,
                    "Unknown model never acquires inference");
        d = new ChargerDetection();
        observe(d, 50, 50, -10, 1000);
        observe(d, 50, 50, -10, 3000);
        d.observe(SCOPE, 2538, true, 50d, 50d, 0d, 4000, 4000);
        check(observe(d, 50, 50, -10, 5000) == null, "An unusable frame restarts acquisition evidence");
    }

    /** A charge shaped unlike the one calibration session keeps its family while its charger fits. */
    private static void offReferenceShapes() {
        ChargerDetection d = selected(FAST);
        for (int i = 0; i < 20; i++)
            check(observe(d, 77, 54, -4.4, 7000 + i * 2000) == FAST, "Early fast taper below the reference keeps fast");
        d = selected(STOCK);
        for (int i = 0; i < 10; i++)
            check(observe(d, 93, 54, -1.6, 7000 + i * 2000) == STOCK, "Low stock finish above 85% keeps stock");
        d = selected(STOCK);
        for (int i = 0; i < 10; i++)
            check(observe(d, 60, 48, -1.5, 7000 + i * 2000) == STOCK, "Low stock bulk power keeps stock");
    }

    /** Near full a reconnect cannot tell a swapped charger apart, so it needs fresh agreement. */
    private static void reconnects() {
        ChargerDetection d = selected(FAST);
        check(observe(d, 97, 54.3, -2.21, 7000) == FAST, "Fast taper keeps its family while connected");
        d.disconnected();
        check(observe(d, 97, 54.2, -104 / 54.2, 67000) == null, "A near-full reconnect drops the old family");
        for (int i = 1; i <= 10; i++)
            check(observe(d, 97, 54.2, -(i % 2 == 0 ? 104 : 116) / 54.2, 67000 + i * 2000) == null,
                    "Overlapping power never re-selects a family");
        d = selected(FAST);
        d.disconnected();
        check(observe(d, 96, 54.2, -3.0, 40000) == FAST, "Fresh fast power after reconnect keeps fast");
        d = selected(FAST);
        d.disconnected();
        check(observe(d, 80, 54.1, -4.6, 40000) == FAST, "A bulk reconnect keeps the family");
        check(observe(d, 97, 54.3, -2.21, 42000) == FAST, "The reconnect check applies only once");
        d = selected(FAST);
        d.disconnected();
        check(d.observe(SCOPE, 2538, true, 97d, null, null, 40000, 40000) == FAST, "An unusable frame defers the check");
        check(observe(d, 97, 54.3, -2.21, 42000) == null, "The next usable near-full frame applies it");
        d = selected(FAST);
        d.disconnected();
        d.reset();
        check(observe(d, 50, 50, -10, 7000) == null && observe(d, 50, 50, -10, 9000) == null
                && observe(d, 50, 50, -10, 11000) == FAST, "Reset clears a pending reconnect");
        check(observe(d, 97, 54.3, -2.21, 13000) == FAST, "No stale reconnect check after reset");
        // Above the stock bulk window a fast-to-stock swap is otherwise never seen.
        d = selected(FAST);
        check(observe(d, 90, 54.2, -3.7, 7000) == FAST, "Fast taper at 90% keeps its family");
        d.disconnected();
        check(observe(d, 90, 54.2, -104 / 54.2, 67000) == null, "A swap to stock at 90% drops fast on reconnect");
        for (int i = 1; i <= 10; i++)
            check(observe(d, 90 + i * .1, 54.2, -(i % 2 == 0 ? 104 : 116) / 54.2, 67000 + i * 2000) == null,
                    "Stock power above 85% never re-selects a family");
        for (double[] row : new double[][]{{86, 54.13, 218.13}, {90, 54.15, 200.94}, {95, 54.23, 144.37}}) {
            d = selected(FAST);
            d.disconnected();
            check(observe(d, row[0], row[1], -row[2] / row[1], 40000) == FAST,
                    "Fast power stock cannot produce keeps fast on reconnect at " + row[0]);
        }
        d = selected(STOCK);
        check(observe(d, 90, 54, -110 / 54d, 7000) == STOCK, "Stock finish at 90% keeps its family");
        d.disconnected();
        check(observe(d, 90, 54, -104 / 54d, 40000) == STOCK && observe(d, 90.5, 54, -116 / 54d, 42000) == STOCK,
                "A stock reconnect at 90% keeps stock");
        d = selected(FAST);
        for (int i = 0; i < 10; i++)
            check(observe(d, 90, 54.2, -110 / 54.2, 7000 + i * 2000) == FAST,
                    "Without a disconnect, low power above 85% keeps fast");
    }

    public static void main(String[] args) {
        duplicatesAndEvidence();
        taperAndLateStart();
        switchesAndGaps();
        sameCharge();
        invalidity();
        offReferenceShapes();
        reconnects();
        System.out.println("ChargerDetectionTest: " + checks + " checks passed");
    }
}
