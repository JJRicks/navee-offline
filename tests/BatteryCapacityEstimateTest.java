package local.navee.offlinelock;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Synthetic committed journals; no owner files or scooter hardware. */
public final class BatteryCapacityEstimateTest {
    private static final long NOW = 1800000000000L, DAY = 86400000L;
    private static int assertions;
    private static final List<File> directories = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        try {
            weightedConfirmedAndDeduplicated();
            unconfirmedProxyAndSeparatePools();
            activeUpdatesReplaceTheirPriorContribution();
            endpointAndGaugeQuality();
            gapsCoverageAndMovement();
            initialAndMidRideDirectionChanges();
            scopeAndEnvironmentalCautions();
            analysisSnapshotsAreReadOnlyAndConsistent();
            evidenceMinimumsAndLabels();
            gaugeJumpsAreSetAside();
            rangeAndCharge();
            unavailableIsNotInsufficient();
            System.out.println("BatteryCapacityEstimateTest: " + assertions + " assertions passed");
        } finally {
            for (File directory : directories) remove(directory);
        }
    }

    private static void evidenceMinimumsAndLabels() throws Exception {
        // 5 rides x 15 points = 75 points: enough points, too few rides.
        BatteryCapacityEstimate.Result r = calculate("A", rides(5, 80, 65, 750, RideStore.CurrentDirection.POSITIVE_IS_DRAW, "normal"), 716.04);
        check(r.estimatedUsableWh == null && "5 / 6 rides".equals(r.label),
                "Points alone cannot produce an estimate; the label counts rides");
        // 6 rides x 9 points = 54 points: enough rides, too few points.
        r = calculate("A", rides(6, 80, 71, 450, RideStore.CurrentDirection.POSITIVE_IS_DRAW, "normal"), 716.04);
        check(r.estimatedUsableWh == null && "54 / 60 pts".equals(r.label),
                "Rides alone cannot produce an estimate; the label counts points");
        r = calculate("A", rides(6, 80, 70, 600, RideStore.CurrentDirection.POSITIVE_IS_DRAW, "normal"), 716.04);
        check(r.estimatedUsableWh != null && r.qualifiedRides == BatteryCapacityEstimate.MIN_RIDES
                && r.batteryDropPoints >= BatteryCapacityEstimate.MIN_TOTAL_DROP, "Six 10-point rides meet both minimums");
    }

    private static void gaugeJumpsAreSetAside() throws Exception {
        List<BatteryCapacityEstimate.Ride> normal = rides(6, 80, 70, 600, RideStore.CurrentDirection.POSITIVE_IS_DRAW, "normal");
        // 20 points for only 80 Wh (400 Wh per gauge, a third below the others): the gauge corrected itself.
        Fixture jumped = fixture("A", 80, 60, 800, 360, RideStore.CurrentDirection.POSITIVE_IS_DRAW, "normal", false);
        BatteryCapacityEstimate.Result r = calculate("A", with(normal, jumped.ride()), 716.04);
        near(600, r.estimatedUsableWh, "A gauge jump cannot pull the pooled estimate");
        check(r.qualifiedRides == 6 && r.rejectedRides == 1 && "Gauge jumped".equals(r.reason)
                        && r.rejectionReasons.get(BatteryCapacityEstimate.GAUGE_JUMP) == 1,
                "The set-aside ride is counted and named");
        // 700 Wh per gauge is within tolerance and stays.
        Fixture high = fixture("A", 80, 60, 1400, 360, RideStore.CurrentDirection.POSITIVE_IS_DRAW, "normal", false);
        r = calculate("A", with(normal, high.ride()), 716.04);
        check(r.qualifiedRides == 7 && r.rejectedRides == 0, "Ordinary ride-to-ride variation is kept");
        // A 5-point ride may differ by one point of rounding (20%) on top of the tolerance.
        Fixture rounded = fixture("A", 80, 75, 400, 360, RideStore.CurrentDirection.POSITIVE_IS_DRAW, "normal", false);
        r = calculate("A", with(normal, rounded.ride()), 716.04);
        check(r.qualifiedRides == 7, "Short rides get one point of rounding tolerance (800 Wh per gauge, +33%)");
        // Below OUTLIER_MIN_RIDES there is no reliable median, so nothing is set aside.
        r = calculate("A", with(rides(3, 80, 70, 600, RideStore.CurrentDirection.POSITIVE_IS_DRAW, "normal"), jumped.ride()), 716.04);
        check(r.qualifiedRides == 4 && r.rejectedRides == 0, "Too few rides to judge a jump keep every ride");
    }

    private static void rangeAndCharge() throws Exception {
        List<BatteryCapacityEstimate.Ride> mixed = rides(3, 80, 70, 600, RideStore.CurrentDirection.POSITIVE_IS_DRAW, "normal");
        mixed.addAll(rides(3, 80, 70, 660, RideStore.CurrentDirection.POSITIVE_IS_DRAW, "normal"));
        BatteryCapacityEstimate.Result r = calculate("A", mixed, 716.04);
        near(630, r.estimatedUsableWh, "Pooled between the two groups");
        check(r.estimatedLowWh < 630 && r.estimatedHighWh > 630 && r.estimatedHighWh - r.estimatedLowWh < 60,
                "The 90% range brackets the estimate and reflects the spread");
        check(Math.abs((r.estimatedUsableWh - r.estimatedLowWh) - (r.estimatedHighWh - r.estimatedUsableWh)) < 1e-9,
                "The range is symmetric around the pooled ratio");
        near(630 / 50.0, r.ahPerGauge, "Charge per gauge uses the charge-weighted voltage");
        check(r.ahPerGaugeLow < r.ahPerGauge && r.ahPerGaugeHigh > r.ahPerGauge, "Charge has its own range");
        check(r.chargingAhPerGauge == null && r.chargingSessions == 0, "No charge evidence, no charging check");
        r = BatteryCapacityEstimate.calculate("A", mixed, 716.04, NOW, 90, new BatteryCapacityEstimate.ChargeEvidence(26.6, 200, 4));
        near(13.3, r.chargingAhPerGauge, "Charging check reports charge per full gauge");
        check(r.chargingSessions == 4, "Charging check counts its charges");
        near(630, r.estimatedUsableWh, "The charging check never changes the ride-based estimate");
        check(BatteryCapacityEstimate.studentT90(5) > BatteryCapacityEstimate.studentT90(17)
                && BatteryCapacityEstimate.studentT90(17) > BatteryCapacityEstimate.studentT90(1000)
                && Math.abs(BatteryCapacityEstimate.studentT90(1000) - 1.671) < 1e-9, "Fewer rides widen the range");
    }

    private static void unavailableIsNotInsufficient() {
        BatteryCapacityEstimate.Result empty = calculate("A", Collections.emptyList(), 716.04);
        check(empty.unavailableReason == null && empty.estimatedUsableWh == null,
                "Completed empty history remains distinguishable from failed analysis");
        BatteryCapacityEstimate.Result failed = BatteryCapacityEstimate.unavailable(
                "A", 716.04, 90, "Recorded rides could not be analyzed");
        check("Recorded rides could not be analyzed".equals(failed.unavailableReason),
                "Failed analysis preserves an explicit reason instead of claiming insufficient history");
    }

    private static void weightedConfirmedAndDeduplicated() throws Exception {
        Fixture a =
                fixture(
                        "A",
                        80,
                        70,
                        600,
                        360,
                        RideStore.CurrentDirection.POSITIVE_IS_DRAW,
                        "normal",
                        false);
        Fixture b =
                fixture(
                        "A",
                        70,
                        50,
                        1200,
                        360,
                        RideStore.CurrentDirection.POSITIVE_IS_DRAW,
                        "normal",
                        false);
        List<BatteryCapacityEstimate.Ride> baseline = rides(4, 80, 70, 600, RideStore.CurrentDirection.POSITIVE_IS_DRAW, "normal");
        BatteryCapacityEstimate.Result r =
                calculate("A", with(baseline, a.ride(), b.ride(), a.ride()), 716.04);
        near(600, r.estimatedUsableWh, "Energy-weighted 20/10pp rides agree at 600 Wh");
        near(
                600 / 716.04 * 100,
                r.percentOfAdvertised,
                "Reference ratio is arithmetic, not battery-health clamp");
        check(
                r.qualifiedRides == 6 && r.proxyRides == 0 && r.throughputEquivalentWh == null,
                "IDs deduplicated and pool identities retained");
        near(70, r.batteryDropPoints, "SOC drops summed rather than ratio-average");
        near(600, r.estimatedLowWh, "Agreeing rides have no spread");
        near(600, r.estimatedHighWh, "Agreeing rides have no spread");
        near(12, r.ahPerGauge, "Charge per gauge follows the 50 V pack: 600 Wh / 50 V");
        Fixture differentRatio =
                fixture(
                        "A",
                        90,
                        70,
                        1400,
                        360,
                        RideStore.CurrentDirection.POSITIVE_IS_DRAW,
                        "normal",
                        false);
        r = calculate("A", with(baseline, a.ride(), differentRatio.ride()), 500.0);
        near(
                440.0 / 70 * 100,
                r.estimatedUsableWh,
                "Aggregate sums energy and drop, does not average 600/700 ratios");
        near(
                440.0 / 70 * 100 / 500 * 100,
                r.percentOfAdvertised,
                "Implausibly high result remains visible instead of clipping to 100%");
        check(r.estimatedLowWh < r.estimatedUsableWh && r.estimatedHighWh > r.estimatedUsableWh,
                "Disagreeing rides widen the range around the pooled estimate");
    }

    private static void unconfirmedProxyAndSeparatePools() throws Exception {
        Fixture proxy =
                fixture(
                        "A",
                        80,
                        60,
                        1000,
                        360,
                        RideStore.CurrentDirection.UNCONFIRMED,
                        "normal",
                        false);
        BatteryCapacityEstimate.Result r =
                calculate("A", with(rides(5, 80, 60, 1000, RideStore.CurrentDirection.UNCONFIRMED, "normal"), proxy.ride()), 716.04);
        near(
                500,
                r.throughputEquivalentWh,
                "Unconfirmed absolute throughput is only an energy-equivalent proxy");
        near(
                500 / 716.04 * 100,
                r.throughputPercentOfAdvertised,
                "Explicit proxy-to-reference arithmetic");
        check(
                r.estimatedUsableWh == null
                        && r.percentOfAdvertised == null
                        && r.qualifiedRides == 0
                        && r.proxyRides == 6,
                "Proxy never promoted to confirmed capacity");
        check("Proxy · not health".equals(r.label), "Proxy keeps a short honesty label");
        Fixture confirmed =
                fixture(
                        "A",
                        80,
                        70,
                        600,
                        360,
                        RideStore.CurrentDirection.POSITIVE_IS_DRAW,
                        "normal",
                        false);
        Fixture smallProxy =
                fixture(
                        "A",
                        80,
                        70,
                        500,
                        360,
                        RideStore.CurrentDirection.UNCONFIRMED,
                        "normal",
                        false);
        List<BatteryCapacityEstimate.Ride> mixed = new ArrayList<>(rides(2, 80, 70, 600, RideStore.CurrentDirection.POSITIVE_IS_DRAW, "normal"));
        mixed.add(confirmed.ride());
        mixed.addAll(rides(2, 80, 70, 500, RideStore.CurrentDirection.UNCONFIRMED, "normal"));
        mixed.add(smallProxy.ride());
        r = calculate("A", mixed, 716.04);
        check(
                r.estimatedUsableWh == null && r.throughputEquivalentWh == null,
                "Separate 3-ride pools cannot manufacture 6-ride evidence");
        Fixture returned =
                fixture(
                        "A",
                        80,
                        60,
                        -1000,
                        360,
                        RideStore.CurrentDirection.POSITIVE_IS_DRAW,
                        "normal",
                        false);
        check(
                calculate("A", Collections.singletonList(returned.ride()), 716.04).rejectedRides
                        == 1,
                "Net input with fallingSOC is not a discharge estimate");
    }

    private static void activeUpdatesReplaceTheirPriorContribution() throws Exception {
        Fixture active =
                fixture(
                        "A",
                        80,
                        70,
                        600,
                        360,
                        RideStore.CurrentDirection.POSITIVE_IS_DRAW,
                        "normal",
                        true);
        BatteryCapacityEstimate.Ride earlier = active.ride();
        for (int sec = 361; sec <= 720; sec++)
            active.store.sample(
                    active.wall + sec * 1000L,
                    sec * 1000L,
                    values(sec * 1000L, 600, 80 - 20 * sec / 720.0, "normal", sec));
        BatteryCapacityEstimate.Ride later = active.ride();
        BatteryCapacityEstimate.Result r = calculate("A", with(rides(5, 80, 70, 600, RideStore.CurrentDirection.POSITIVE_IS_DRAW, "normal"), earlier, later), 716.04);
        check(
                r.qualifiedRides == 6 && r.includesActive,
                "Active ride replaces its earlier snapshot");
        near(
                600,
                r.estimatedUsableWh,
                "Active energy/SOC continuously updates without double counting");
        near(70, r.batteryDropPoints, "Active replacement retains only the latest battery drop");
    }

    private static void endpointAndGaugeQuality() throws Exception {
        for (String mode :
                new String[] {
                    "missing_first_soc",
                    "missing_last_soc",
                    "missing_first_power",
                    "missing_last_power",
                    "rebound"
                }) {
            Fixture f =
                    fixture(
                            "A",
                            80,
                            60,
                            1000,
                            360,
                            RideStore.CurrentDirection.POSITIVE_IS_DRAW,
                            mode,
                            false);
            BatteryCapacityEstimate.Result r =
                    calculate("A", Collections.singletonList(f.ride()), 716.04);
            check(
                    r.estimatedUsableWh == null && r.rejectedRides == 1,
                    "Reject " + mode + " even when summary energy/SOC exist");
        }
        Fixture zero =
                fixture(
                        "A",
                        80,
                        80,
                        1000,
                        360,
                        RideStore.CurrentDirection.POSITIVE_IS_DRAW,
                        "normal",
                        false);
        check(
                calculate("A", Collections.singletonList(zero.ride()), 716.04)
                                .rejectedRides == 1,
                "NoSOCchange cannot yield capacity");
        BatteryCapacityEstimate.Result rejectedOnly =
                calculate("A", Collections.singletonList(zero.ride()), 716.04);
        check(
                "No usable rides yet".equals(rejectedOnly.label)
                        && "Drop under 5%".equals(rejectedOnly.reason),
                "Rejected rides show a short state with the reason in details");
        Fixture shortDrop =
                fixture(
                        "A",
                        80,
                        76,
                        1000,
                        360,
                        RideStore.CurrentDirection.POSITIVE_IS_DRAW,
                        "normal",
                        false);
        check(
                calculate("A", Collections.singletonList(shortDrop.ride()), 716.04)
                                .estimatedUsableWh
                        == null,
                "SmallSOC drop excluded");
    }

    private static void gapsCoverageAndMovement() throws Exception {
        Fixture gap =
                fixture(
                        "A",
                        80,
                        60,
                        1000,
                        360,
                        RideStore.CurrentDirection.POSITIVE_IS_DRAW,
                        "gap",
                        false);
        check(
                calculate("A", Collections.singletonList(gap.ride()), 716.04)
                        .rejectedRides == 1,
                "Brief real recordinggap blocks extrapolation");
        Fixture missing =
                fixture(
                        "A",
                        80,
                        60,
                        1000,
                        360,
                        RideStore.CurrentDirection.POSITIVE_IS_DRAW,
                        "low_coverage",
                        false);
        check(
                calculate("A", Collections.singletonList(missing.ride()), 716.04)
                        .rejectedRides == 1,
                "Lowpower coverage excluded");
        Fixture tinyLoss =
                fixture(
                        "A",
                        80,
                        60,
                        1000,
                        360,
                        RideStore.CurrentDirection.POSITIVE_IS_DRAW,
                        "small_loss",
                        false);
        BatteryCapacityEstimate.Result r =
                calculate("A", with(rides(5, 80, 60, 1000, RideStore.CurrentDirection.POSITIVE_IS_DRAW, "normal"), tinyLoss.ride()), 716.04);
        check(
                r.estimatedUsableWh != null
                        && r.electricalCoverage >= .95
                        && r.electricalCoverage < 1,
                "Small internalpower loss remains usable with disclosedcoverage");
        Fixture still =
                fixture(
                        "A",
                        80,
                        60,
                        1000,
                        360,
                        RideStore.CurrentDirection.POSITIVE_IS_DRAW,
                        "stationary",
                        false);
        check(
                calculate("A", Collections.singletonList(still.ride()), 716.04)
                        .rejectedRides == 1,
                "Stationary/charger-like session excluded");
        Fixture brief =
                fixture(
                        "A",
                        80,
                        60,
                        1000,
                        30,
                        RideStore.CurrentDirection.POSITIVE_IS_DRAW,
                        "normal",
                        false);
        check(
                calculate("A", Collections.singletonList(brief.ride()), 716.04)
                        .rejectedRides == 1,
                "Too-short recording excluded");
    }

    private static void initialAndMidRideDirectionChanges() throws Exception {
        Fixture initial =
                fixture(
                        "A",
                        80,
                        60,
                        1000,
                        360,
                        RideStore.CurrentDirection.POSITIVE_IS_DRAW,
                        "initial_direction",
                        false);
        check(
                initial.summary().directionChanges == 0,
                "Initial pre-sample choice is not a midride direction change");
        near(
                500,
                calculate("A", with(rides(5, 80, 60, 1000, RideStore.CurrentDirection.POSITIVE_IS_DRAW, "normal"), initial.ride()), 716.04).estimatedUsableWh,
                "Initial configured direction permits estimate");
        Fixture flip =
                fixture(
                        "A",
                        80,
                        60,
                        1000,
                        360,
                        RideStore.CurrentDirection.POSITIVE_IS_DRAW,
                        "direction_flip",
                        false);
        check(
                flip.summary().directionChanges == 2,
                "Confirmed-unconfirmed-confirmed settingchanges both counted");
        check(
                calculate("A", Collections.singletonList(flip.ride()), 716.04)
                        .rejectedRides == 1,
                "Direction flips reject misleading summaryenergy");
        RideStore replay = new RideStore(flip.directory);
        check(
                replay.get(flip.id).directionChanges == 2,
                "Direction changes survive journal replay");
        RideStore replayInitial = new RideStore(initial.directory);
        check(
                replayInitial.get(initial.id).directionChanges == 0,
                "Initial choice remains uncounted on replay");
    }

    private static void scopeAndEnvironmentalCautions() throws Exception {
        Fixture cold =
                fixture(
                        "A",
                        100,
                        80,
                        1000,
                        360,
                        RideStore.CurrentDirection.POSITIVE_IS_DRAW,
                        "cold",
                        false);
        List<BatteryCapacityEstimate.Ride> colds = with(rides(5, 100, 80, 1000, RideStore.CurrentDirection.POSITIVE_IS_DRAW, "cold"), cold.ride());
        BatteryCapacityEstimate.Result r = calculate("A", colds, 716.04);
        check(
                r.estimatedUsableWh != null
                        && r.qualifiedRides == 6
                        && r.rejectedRides == 0,
                "Cold/full start remains usable evidence");
        near(500, r.estimatedUsableWh, "Cold/full start does not invent a capacity correction");
        near(2, r.minimumTemperatureC, "Actual observed packtemperature preserved");
        check(
                calculate("B", colds, 716.04).qualifiedRides == 0,
                "Other scooter profile never contributes");
        r =
                BatteryCapacityEstimate.calculate(
                        "A", colds, 716.04, NOW + 100 * DAY, 90);
        check(
                r.qualifiedRides == 0 && r.estimatedUsableWh == null,
                "Oldrides excluded from chosenwindow");
        r = calculate("A", colds, Double.NaN);
        check(
                r.advertisedWh == null && r.percentOfAdvertised == null,
                "Invalid modelreference doesnot fabricate comparison");
        check(
                BatteryCapacityEstimate.calculate("A", Collections.emptyList(), 716.04, NOW, 90)
                                .estimatedUsableWh
                        == null,
                "Empty journals remain insufficient evidence");
        check(
                "Record a longer ride"
                        .equals(BatteryCapacityEstimate.calculate("A", Collections.emptyList(), 716.04, NOW, 90).label),
                "Empty journals show a short next step");
        for (String reason :
                new String[] {
                    "recording clock changed",
                    "recording has gaps or recovery",
                    "current direction changed during the ride",
                    "record at least 1 minute",
                    "battery end readings are missing",
                    "battery drop is below 5 percentage points",
                    "electrical coverage is below 95%",
                    "at least 30 seconds of measured movement is needed",
                    "measured energy is unavailable",
                    "net measured energy is not positive",
                    "endpoint power and battery readings are missing",
                    "power and battery readings do not align with both ride ends",
                    "battery gauge rebounded or rose while recording",
                    BatteryCapacityEstimate.GAUGE_JUMP
                }) {
            String label = BatteryCapacityEstimate.shortReason(reason);
            check(
                    label != null && !label.equals("Insufficient data") && label.split(" ").length <= 4,
                    "Rejection reason has a short label: " + reason);
        }
    }

    private static void analysisSnapshotsAreReadOnlyAndConsistent() throws Exception {
        Fixture completed =
                fixture(
                        "A",
                        80,
                        60,
                        1000,
                        360,
                        RideStore.CurrentDirection.POSITIVE_IS_DRAW,
                        "normal",
                        false);
        File journal = new File(completed.directory, completed.id + ".csv");
        long length = journal.length();
        List<RideStore.AnalysisSnapshot> first = completed.store.analysisSnapshots("A", 0);
        List<RideStore.AnalysisSnapshot> cached = completed.store.analysisSnapshots("A", 0);
        check(
                first.size() == 1 && first.get(0) == cached.get(0),
                "Completed immutable analysis snapshot reused without replay");
        check(
                journal.length() == length && !first.get(0).summary.recovered,
                "Analysis never appends recovery or changes journal");
        check(
                completed.store.analysisSnapshots("B", 0).isEmpty(),
                "Analysis snapshots preserve profile isolation");
        completed.store.start("A", "Active analysis", completed.wall + DAY, 0);
        for (int sec = 0; sec <= 60; sec++)
            completed.store.sample(
                    completed.wall + DAY + sec * 1000L,
                    sec * 1000L,
                    values(sec * 1000L, 600, 80 - sec / 3.0, "normal", sec));
        List<RideStore.AnalysisSnapshot> live = completed.store.analysisSnapshots("A", 0);
        RideStore.AnalysisSnapshot active = null;
        for (RideStore.AnalysisSnapshot snapshot : live)
            if (snapshot.summary.active) active = snapshot;
        check(
                live.size() == 2 && active != null,
                "Active contribution included once alongside completed ride");
        RideStore.Point end = active.points.get(active.points.size() - 1);
        check(
                active.summary.elapsedMs == end.elapsedMs
                        && active.summary.endBatteryPercent.equals(end.batteryPercent),
                "Active summary and trend endpoint copied atomically");
        completed.store.delete(completed.id);
        check(
                completed.store.analysisSnapshots("A", 0).size() == 1,
                "Deleted completed ride disappears from analysis cache");
    }

    private static Fixture fixture(
            String profile,
            double start,
            double end,
            double watts,
            int duration,
            RideStore.CurrentDirection direction,
            String mode,
            boolean active)
            throws Exception {
        File directory = Files.createTempDirectory("capacity-estimate-").toFile();
        directories.add(directory);
        RideStore store = new RideStore(directory);
        long wall = NOW - 2 * DAY;
        if (!mode.equals("initial_direction")) store.setCurrentDirection(direction);
        String id = store.start(profile, "Synthetic fixture", wall, 0);
        if (mode.equals("initial_direction")) store.setCurrentDirection(direction);
        for (int sec = 0; sec <= duration; sec++) {
            double soc = start - (start - end) * sec / duration;
            if (mode.equals("rebound") && sec >= 180 && sec < 185) soc += 3;
            if (mode.equals("gap") && sec == 180)
                store.markGap(wall + sec * 1000L, sec * 1000L, "Synthetic disconnection");
            if (mode.equals("direction_flip") && sec == 180) {
                store.setCurrentDirection(RideStore.CurrentDirection.UNCONFIRMED);
                store.setCurrentDirection(direction);
            }
            Map<String, RideStore.Sample> data = values(sec * 1000L, watts, soc, mode, sec);
            if ((mode.equals("missing_first_soc") && sec == 0)
                    || (mode.equals("missing_last_soc") && sec == duration))
                data.remove("battery_percent");
            if ((mode.equals("missing_first_power") && sec == 0)
                    || (mode.equals("missing_last_power") && sec == duration))
                data.remove("current_a");
            store.sample(wall + sec * 1000L, sec * 1000L, data);
        }
        if (!active) store.stop(wall + duration * 1000L, duration * 1000L);
        return new Fixture(store, directory, id, wall);
    }

    private static Map<String, RideStore.Sample> values(
            long mono, double watts, double soc, String mode, int sec) {
        Map<String, RideStore.Sample> data = new LinkedHashMap<>();
        data.put("voltage_v", new RideStore.Sample(50.0, "50V", "V", 0x72, mono));
        data.put("current_a", new RideStore.Sample(watts / 50, "current", "A", 0x72, mono));
        data.put("speed_kmh", new RideStore.Sample(mode.equals("stationary") ? 0.0 : 20.0, mono));
        data.put("battery_percent", new RideStore.Sample(soc, mono));
        data.put("temperature_c", new RideStore.Sample(mode.equals("cold") ? 2.0 : 22.0, mono));
        if ((mode.equals("low_coverage") && sec % 4 == 1)
                || (mode.equals("small_loss") && sec == 180)) data.remove("current_a");
        return data;
    }

    /** More completed rides shaped like a template, so one evidence group can reach MIN_RIDES. */
    private static List<BatteryCapacityEstimate.Ride> rides(
            int count, double start, double end, double watts, RideStore.CurrentDirection direction, String mode)
            throws Exception {
        List<BatteryCapacityEstimate.Ride> result = new ArrayList<>();
        for (int i = 0; i < count; i++) result.add(fixture("A", start, end, watts, 360, direction, mode, false).ride());
        return result;
    }

    private static List<BatteryCapacityEstimate.Ride> with(
            List<BatteryCapacityEstimate.Ride> more, BatteryCapacityEstimate.Ride... rides) {
        List<BatteryCapacityEstimate.Ride> result = new ArrayList<>(Arrays.asList(rides));
        result.addAll(more);
        return result;
    }

    private static BatteryCapacityEstimate.Result calculate(
            String profile, List<BatteryCapacityEstimate.Ride> rides, Double reference) {
        return BatteryCapacityEstimate.calculate(profile, rides, reference, NOW, 90);
    }

    private static final class Fixture {
        final RideStore store;
        final File directory;
        final String id;
        final long wall;

        Fixture(RideStore store, File directory, String id, long wall) {
            this.store = store;
            this.directory = directory;
            this.id = id;
            this.wall = wall;
        }

        RideStore.Summary summary() throws Exception {
            return store.get(id);
        }

        BatteryCapacityEstimate.Ride ride() throws Exception {
            return new BatteryCapacityEstimate.Ride(summary(), store.trend(id, 600));
        }
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static void near(double expected, Double actual, String message) {
        check(
                actual != null && Math.abs(expected - actual) < 1e-7,
                message + " expected=" + expected + " actual=" + actual);
    }

    private static void remove(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) remove(child);
        file.delete();
    }
}
