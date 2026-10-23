package local.navee.offlinelock;

/** Python/Java parity, physical invariants and estimate composition for ut5-stock-pack-v6. */
public final class Ut5MaxStockPackModelTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static ChargingEstimate.Value live(Double value) { return new ChargingEstimate.Value(value, true, 1000); }
    private static ChargingEstimate.Result baseline(double soc, double volts, double amps, Double target) {
        return ChargingEstimate.calculate(live(1d), live(volts), live(-amps), live(soc), live(15.3), live(target));
    }
    private static ChargingEstimate.Result estimate(double soc, double volts, double amps, Double target,
            ChargeSessionStore.Charger charger, ChargeSessionStore.Curve personal, ChargeProgressTracker.Progress progress) {
        return ChargingEstimate.forScooter(baseline(soc, volts, amps, target), live(soc), live(15.3), 2538, charger, personal,
                null, null, 0, 0, progress);
    }
    private static ChargeProgressTracker.Progress started(double minutesAgo, double startSoc) {
        ChargeProgressTracker tracker = new ChargeProgressTracker();
        long start = 10_000_000L;
        tracker.observe("p", false, startSoc, 0d, start - 60_000);
        tracker.observe("p", true, startSoc, 2.3, start);
        return tracker.progress("p", start + (long) (minutesAgo * 60_000));
    }

    public static void main(String[] args) {
        parity();
        invariants();
        composition();
        System.out.println("Ut5MaxStockPackModel: " + checks + " checks passed");
    }

    /** The generated table must reproduce the published Python model to well under a second. */
    private static void parity() {
        double worst = 0;
        for (double[] c : StockPackParity.CASES) {
            double got = Ut5MaxStockPackModel.minutes(c[0], c[1], c[2], c[3], c[4], c[5], 15.3, c[6] == 1);
            worst = Math.max(worst, Math.abs(got - c[7]));
        }
        check(StockPackParity.CASES.length > 500, "Parity covers the published replay points");
        check(worst < 1e-6, "Java pack model matches fit_pack_model.py: worst difference " + worst + " min");
    }

    private static void invariants() {
        // A synthetic settled standard charge: pack voltage rises with SOC along the fitted table.
        double previousFull = Double.POSITIVE_INFINITY, previousLimit = Double.POSITIVE_INFINITY;
        for (int soc = 5; soc < 89; soc++) {
            double volts = 45.8 + (53.2 - 45.8) * (soc - 5) / 83.0;
            Ut5MaxStockPackModel.Estimate full = Ut5MaxStockPackModel.estimate(soc, 100, volts, 1.94, 240, 5, 15.3);
            Ut5MaxStockPackModel.Estimate limit = Ut5MaxStockPackModel.estimate(soc, 95, volts, 1.94, 240, 5, 15.3);
            check(full != null && limit != null, "Bulk-stage inputs are inside the model domain at " + soc + "%");
            check(full.planning >= full.display && limit.planning >= limit.display, "Planning is never shorter than display at " + soc + "%");
            check(full.display < previousFull && limit.display <= previousLimit, "Time falls as a settled charge progresses at " + soc + "%");
            check(limit.display <= full.display, "A 95% cutoff arrives before displayed 100% at " + soc + "%");
            previousFull = full.display; previousLimit = limit.display;
        }
        // A higher cutoff never arrives sooner, across the 89% handover and the late catch-up.
        for (double soc : new double[]{10, 40, 70, 80, 85, 88, 88.5}) {
            for (double volts : new double[]{46.5, 49, 51.5, 52.8, 53.1, 53.3, 53.6}) {
                for (double age : new double[]{0, 20, 240}) {
                    double previousDisplay = -1, previousPlanning = -1;
                    for (double target = Math.ceil(soc); target <= 100; target += .25) {
                        Ut5MaxStockPackModel.Estimate e = Ut5MaxStockPackModel.estimate(soc, target, volts, 1.94, age, soc, 15.3);
                        if (e == null) break;  // contradictory voltage/percentage pairs are rejected below
                        check(e.display >= previousDisplay - 1e-9 && e.planning >= previousPlanning - 1e-9,
                                "Cutoff " + target + "% cannot arrive before a lower one at " + soc + "%, " + volts + " V");
                        previousDisplay = e.display; previousPlanning = e.planning;
                    }
                }
            }
        }
        // Continuous in voltage: blend weights, table edges and the gauge fade have no cliffs.
        // (Absurd pairs such as 85% at 45.5 V are steep but continuous; a 2-minute single step is a cliff.)
        for (double soc : new double[]{20, 57, 85}) {
            double previous = Double.NaN;
            for (double volts = 45.0; volts <= 54.2; volts += .0005) {
                Ut5MaxStockPackModel.Estimate e = Ut5MaxStockPackModel.estimate(soc, 100, volts, 2.07, 60, 20, 15.3);
                if (e == null) { previous = Double.NaN; continue; }
                double now = e.display;
                if (!Double.isNaN(previous)) check(Math.abs(now - previous) < 2, "No cliff near " + volts + " V at " + soc + "%");
                previous = now;
            }
        }
        check(Ut5MaxStockPackModel.estimate(50, 100, 54.0, 1.94, 240, 20, 15.3) == null,
                "Near-full pack voltage with a half-empty gauge is rejected as contradictory");
        check(Ut5MaxStockPackModel.estimate(85, 100, 45.5, 2.2, 240, 20, 15.3) == null,
                "Near-empty pack voltage with an almost full gauge is rejected as contradictory");
        check(Ut5MaxStockPackModel.estimate(1, 100, 45.5, 2.43, 0, 1, 15.3) != null
                && Ut5MaxStockPackModel.estimate(88, 100, 53.3, 1.94, 240, 22, 15.3) != null,
                "The recorded extremes of both scooters remain plausible");
        Ut5MaxStockPackModel.Estimate fresh = Ut5MaxStockPackModel.estimate(40, 100, 48.4, 2.3, 0, 40, 15.3);
        Ut5MaxStockPackModel.Estimate settled = Ut5MaxStockPackModel.estimate(40, 100, 48.4, 2.3, 600, 40, 15.3);
        check(fresh.display > settled.display, "A just-started charger's higher current is expected to decay");
        Ut5MaxStockPackModel.Estimate cutoff = Ut5MaxStockPackModel.estimate(30, 80, 47.6, 2.3, 0, 30, 15.3);
        double counted = 15.3 * .5 / 2.3 * 60;
        check(cutoff.display > counted, "Lower cutoffs include the warm-up current decay instead of the live rate alone");
        check(Ut5MaxStockPackModel.estimate(60, 60, 49.5, 2, 30, 50, 15.3).display == 0, "A reached cutoff is zero");
        check(Ut5MaxStockPackModel.estimate(60, 55, 49.5, 2, 30, 50, 15.3).planning == 0, "A passed cutoff is zero");
        check(Ut5MaxStockPackModel.estimate(89, 100, 53.3, 1.94, 30, 50, 15.3) == null, "89% and above stay with the finishing reference");
        check(Ut5MaxStockPackModel.estimate(50, 100, 49, 0.6, 30, 50, 15.3) == null, "Low current is outside the fitted charger");
        check(Ut5MaxStockPackModel.estimate(50, 100, 49, 4.5, 30, 50, 15.3) == null, "High-speed current is outside the fitted charger");
        check(Ut5MaxStockPackModel.estimate(50, 100, 49, 2, 30, 50, 25) == null, "Other battery capacities are outside the fitted pack");
        check(Ut5MaxStockPackModel.estimate(Double.NaN, 100, 49, 2, 30, 50, 15.3) == null, "Invalid SOC is rejected");
        check(Ut5MaxStockPackModel.estimate(50, 100, 49, 2, -1, 50, 15.3) == null, "Negative age is rejected");
        Ut5MaxStockPackModel.Estimate unknownStart = Ut5MaxStockPackModel.estimate(50, 100, 49.2, 2, 30, Double.NaN, 15.3);
        Ut5MaxStockPackModel.Estimate seenStart = Ut5MaxStockPackModel.estimate(50, 100, 49.2, 2, 30, 50, 15.3);
        check(unknownStart != null && unknownStart.display == seenStart.display, "An unknown start percentage uses the current percentage");

        // Charge-age inference from watched current.
        int n = 0; double[] t = new double[40], a = new double[40];
        for (int i = 0; i < 40; i++, n++) { t[i] = i * .5; a[i] = 1.94 * (1 + Ut5MaxStockPackModel.DECAY_DISPLAY * Math.exp(-(i * .5) / 25)); }
        check(Ut5MaxStockPackModel.inferStartAge(t, a, n) == 0, "A decaying current from first sight is a fresh charge");
        for (int i = 0; i < 40; i++) a[i] = 1.94 * (1 + Ut5MaxStockPackModel.DECAY_DISPLAY * Math.exp(-(30 + i * .5) / 25));
        check(Math.abs(Ut5MaxStockPackModel.inferStartAge(t, a, n) - 30) <= 5, "A partly decayed current is recognized as mid warm-up");
        for (int i = 0; i < 40; i++) a[i] = 2.17;
        check(Ut5MaxStockPackModel.inferStartAge(t, a, n) == 600, "A flat current is a settled charge");
        check(Ut5MaxStockPackModel.inferStartAge(t, a, 10) == 0, "Under eight watched minutes keeps the cautious fresh-charge assumption");
    }

    private static void composition() {
        ChargeProgressTracker.Progress early = started(5, 30);
        ChargingEstimate.Result r = estimate(30, 47.6, 2.3, 80d, ChargeSessionStore.Charger.STOCK, null, early);
        check(r.stockPackModel && r.referenceToFull && r.referenceToLimit && r.referenceCharger == ChargeSessionStore.Charger.STOCK,
                "Standard charging below 89% uses the pack model with reference attribution");
        check(r.planningMinutesToLimit >= r.remainingMinutesToLimit && r.planningMinutesToFull >= r.remainingMinutesToFull,
                "Plans are never shorter than the displayed estimate");
        check(r.remainingMinutesToLimit <= r.remainingMinutesToFull && r.planningMinutesToLimit <= r.planningMinutesToFull,
                "Cutoff stays within time to full");
        check(r.status.contains("pack voltage") && r.status.contains("Charge done by") && !r.status.contains("start not seen"),
                "An observed start explains the source and the cautious plan");
        ChargingEstimate.Result unseen = estimate(30, 47.6, 2.3, 80d, ChargeSessionStore.Charger.STOCK, null, null);
        check(unseen.stockPackModel && unseen.status.contains("start not seen"), "An unseen start is disclosed");
        check(unseen.remainingMinutesToLimit >= r.remainingMinutesToLimit - 1e-9, "An unseen start assumes the most warm-up decay ahead");
        ChargingEstimate.Result unknownCutoff = estimate(30, 47.6, 2.3, null, ChargeSessionStore.Charger.STOCK, null, early);
        check(unknownCutoff.stockPackModel && unknownCutoff.remainingMinutesToLimit == null && unknownCutoff.planningMinutesToLimit == null
                && unknownCutoff.status.contains("Cutoff unknown"), "An unknown cutoff is never invented");
        ChargingEstimate.Result full = estimate(30, 47.6, 2.3, 100d, ChargeSessionStore.Charger.STOCK, null, early);
        check(full.remainingMinutesToLimit.equals(full.remainingMinutesToFull) && full.planningMinutesToLimit.equals(full.planningMinutesToFull),
                "A 100% limit uses full timing for display and planning");

        ChargingEstimate.Result fast = estimate(30, 47.6, 2.3, 80d, ChargeSessionStore.Charger.HIGH_SPEED, null, early);
        check(!fast.stockPackModel, "High-speed charging never uses the standard-charger model");
        ChargingEstimate.Result unknown = estimate(30, 47.6, 2.3, 80d, null, null, early);
        check(!unknown.stockPackModel && unknown.planningMinutesToLimit.equals(unknown.remainingMinutesToLimit),
                "An unknown charger keeps the live estimate, with equal planning time");
        ChargingEstimate.Result finishing = estimate(95, 53.5, 1.94, 100d, ChargeSessionStore.Charger.STOCK, null, early);
        check(!finishing.stockPackModel && finishing.referenceToFull
                && finishing.planningMinutesToFull.equals(finishing.remainingMinutesToFull), "89% and above keep the finishing reference");
        ChargingEstimate.Result incompatible = estimate(30, 47.6, 2.3, 80d, ChargeSessionStore.Charger.STOCK, null, early);
        check(incompatible.stockPackModel, "Compatible stock power is admitted");
        ChargingEstimate.Result otherModel = ChargingEstimate.forScooter(baseline(30, 47.6, 2.3, 80d), live(30d), live(15.3), 2539,
                ChargeSessionStore.Charger.STOCK, null, null, null, 0, 0, early);
        check(!otherModel.stockPackModel && otherModel.planningMinutesToLimit.equals(otherModel.remainingMinutesToLimit),
                "Other models keep the live estimate");
        ChargingEstimate.Result stale = ChargingEstimate.forScooter(ChargingEstimate.calculate(live(1d), live(47.6),
                new ChargingEstimate.Value(-2.3, false, 1000), live(30d), live(15.3), live(80d)), live(30d), live(15.3), 2538,
                ChargeSessionStore.Charger.STOCK, null, null, null, 0, 0, early);
        check(!stale.stockPackModel && stale.remainingMinutesToFull == null, "Stale electrical readings never reach the model");
        check(ChargingEstimate.ESTIMATOR_VERSION.equals("ut5-stock-pack-v6"), "Recorded predictions identify the new estimator");
    }
}
