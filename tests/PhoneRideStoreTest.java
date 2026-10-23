package com.jjricks.volt;

import java.io.*;
import java.nio.file.Files;

/** Phone signal quality, segmentation and export tests; electrical RideStore stays independent. */
public final class PhoneRideStoreTest {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static void near(double expected, Double actual, double tolerance, String message) {
        check(actual != null && Math.abs(expected - actual) <= tolerance, message + ": " + actual);
    }

    private static String gps(
            long mono,
            double lat,
            double accuracy,
            double speed,
            Double speedAccuracy,
            Double altitude,
            Double vertical) {
        return PhoneRideStore.row(
                "GPS",
                1700000000000L + mono,
                mono,
                lat,
                -111.78,
                accuracy,
                speed,
                speedAccuracy,
                altitude,
                vertical);
    }

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("phone-rides-test").toFile();
        PhoneRideStore store = new PhoneRideStore(dir);
        try {
            PhoneRideStore.Track missing = store.load("missing");
            check(missing.points.isEmpty(), "Old recordings have no synthetic route");
            check(missing.distanceMeters == null, "Missing GPS is unavailable");
            store.start("hill", 1700000000000L, 1000, "pocket");
            StringBuilder b = new StringBuilder();
            for (int i = 0; i <= 40; i++) {
                long mono = 1000 + i * 1000;
                double height = i * .25, p = 900 * Math.pow(1 - height / 44330, 1 / 0.190294957);
                b.append(PhoneRideStore.row("PRESSURE", 1700000000000L + mono, mono, p));
                b.append(
                        PhoneRideStore.row(
                                "TELEMETRY",
                                1700000000000L + mono,
                                mono,
                                18,
                                1500,
                                90,
                                mono,
                                mono,
                                mono));
                b.append(gps(mono, 43.82 + i * 5 / 111194.9266, 3, 5, .2, 1500 + height, 4.0));
            }
            b.append(PhoneRideStore.row("MOTION", 1700000041000L, 41000, 800, 3.4));
            store.appendBatch("hill", b.toString(), "");
            store.stop("hill", 1700000042000L, 42000);
            PhoneRideStore.Track hill = store.load("hill");
            check(hill.locationCount == 41, "Route location count");
            near(200, hill.distanceMeters, .1, "Route distance");
            near(5, hill.points.get(40).gradePercent, .3, "Pressure relative grade");
            check(
                    hill.ascentMeters != null && hill.ascentMeters > 6 && hill.ascentMeters < 11,
                    "Hysteresis ascent rejects noise");
            near(0, hill.maxGpsAccelerationMps2, .0001, "Constant GPS speed acceleration");
            near(0, hill.maxScooterAccelerationMps2, .0001, "Constant scooter speed acceleration");
            near(1500, hill.points.get(40).powerW, 0, "Fresh matched telemetry on route");
            check(hill.motionSampleCount == 800, "Motion sample count");
            near(3.4, hill.maxLinearAccelerationMps2, 0, "Experimental motion magnitude");
            check(hill.toCsv().contains("grade_percent"), "Joined CSV unit labels");
            check(hill.toJson().contains("\"powerW\":1500.0"), "JSON power matching");
            check(hill.toGpx().contains("<trkseg>"), "GPX export");
            check(hill == store.load("hill"), "Unchanged track cached");
            store.start("altitude-spike", 1700000000000L, 1000, "pocket");
            StringBuilder spike = new StringBuilder();
            for (int i = 0; i <= 30; i++) {
                double altitude = i >= 5 && i <= 25 ? 1500 + 76.2 * (1 - Math.abs(i - 15) / 10.0) : 1500;
                spike.append(gps(1000 + i * 1000, 43.82 + i * 5 / 111194.9266, 3, 5, .2, altitude, 4d));
            }
            store.appendBatch("altitude-spike", spike.toString(), "");
            PhoneRideStore.Track rejectedAltitude = store.load("altitude-spike");
            check(rejectedAltitude.rejectedElevationCount > 10, "250 ft excursion in 20 seconds filtered");
            check(rejectedAltitude.locationCount == 31, "Elevation outlier does not erase valid GPS route");
            check(rejectedAltitude.points.get(15).relativeAltitudeM == null && rejectedAltitude.points.get(15).gradePercent == null, "Peak hidden from elevation and grade");
            check(rejectedAltitude.points.get(30).relativeAltitudeM != null, "Elevation recovers after return");
            check(rejectedAltitude.toCsv().contains("1576.2"), "Original altitude retained in export");
            check(rejectedAltitude.toJson().contains("rejectedElevationCount"), "Filtering disclosed in analysis");
            store.start("pressure-spike", 1700000000000L, 1000, "pocket");
            StringBuilder pressureSpike = new StringBuilder();
            for (int i = 0; i <= 60; i++) {
                double altitude = i >= 5 && i <= 45 ? 76.2 * (1 - Math.abs(i - 25) / 20.0) : 0;
                double pressure = 900 * Math.pow(1 - altitude / 44330, 1 / 0.190294957);
                pressureSpike.append(PhoneRideStore.row("PRESSURE", 1700000000000L + i * 1000, 1000 + i * 1000, pressure));
                pressureSpike.append(gps(1000 + i * 1000, 43.82 + i * 5 / 111194.9266, 3, 5, .2, 1500d, 4d));
            }
            store.appendBatch("pressure-spike", pressureSpike.toString(), "");
            PhoneRideStore.Track pressureRejected = store.load("pressure-spike");
            check(pressureRejected.rejectedElevationCount > 0, "Smoothed barometer ramps also checked for implausible elevation");
            check(pressureRejected.ascentMeters == null || pressureRejected.ascentMeters < 25, "250 ft pressure spike does not inflate ascent");
            check(pressureRejected.descentMeters == null || pressureRejected.descentMeters < 25, "250 ft pressure spike does not inflate descent");
            store.start("quality", 1700000000000L, 1000, "mounted");
            b.setLength(0);
            b.append(gps(1000, 43.82, 2, 0, .1, 1500.0, 25.0));
            b.append(gps(2000, 43.82005, 2, 0, .1, 1550.0, 25.0));
            b.append(gps(3000, 43.8201, 90, 5, .1, 1600.0, 25.0));
            b.append(gps(4000, 43.8202, 2, 5, .1, 1600.0, 25.0));
            b.append(gps(20000, 43.83, 2, 5, .1, 1600.0, 25.0));
            b.append(gps(21000, 45.0, 2, 5, .1, 1600.0, 25.0));
            b.append(gps(20000, 45.001, 2, 5, .1, 1600.0, 25.0));
            store.appendBatch("quality", b.toString(), "");
            PhoneRideStore.Track quality = store.load("quality");
            check(
                    quality.rejectedLocationCount == 3,
                    "Poor accuracy and non-increasing fix excluded");
            check(quality.locationCount == 4, "Accepted fix count");
            check(
                    quality.distanceMeters == null || quality.distanceMeters == 0,
                    "Stationary drift and gaps not added to distance");
            for (PhoneRideStore.Point p : quality.points)
                check(p.gradePercent == null, "Poor GNSS altitude cannot make grade");
            check(
                    count(quality.toGpx(), "<trkseg>") == 3,
                    "GPX does not bridge signal gaps/outliers");
            store.start("accel", 1700000000000L, 1000, "pocket");
            b.setLength(0);
            for (int i = 0; i < 6; i++) {
                long mono = 1000 + i * 1000;
                b.append(
                        PhoneRideStore.row(
                                "TELEMETRY",
                                1700000000000L + mono,
                                mono,
                                i * 3.6,
                                100,
                                90,
                                mono,
                                mono,
                                mono));
                b.append(gps(mono, 43.82 + i * 4 / 111194.9266, 2, i, .1, null, null));
            }
            b.append(
                    PhoneRideStore.row(
                            "TELEMETRY", 1700000007000L, 7000, 50, 200, 80, 1000, 1000, 1000));
            b.append(gps(7000, 43.82022, 2, 7, .1, null, null));
            store.appendBatch("accel", b.toString(), "");
            PhoneRideStore.Track accel = store.load("accel");
            near(
                    1,
                    accel.points.get(4).gpsAccelerationMps2,
                    .0001,
                    "GPS speed change acceleration");
            near(
                    1,
                    accel.points.get(4).scooterAccelerationMps2,
                    .0001,
                    "Scooter source-clock acceleration");
            check(
                    accel.points.get(accel.points.size() - 1).powerW == null,
                    "Stale underlying power never matched");
            store.start("restart", 1700000000000L, 1000, "pocket");
            store.appendBatch(
                    "restart",
                    gps(1000, 43.82, 2, 5, .1, null, null)
                            + PhoneRideStore.row("GAP", 1700000001500L, 1500)
                            + gps(2000, 43.83, 2, 5, .1, null, null),
                    "");
            PhoneRideStore.Track restart = store.load("restart");
            check(restart.points.get(1).gapBefore, "Explicit restart segment");
            check(restart.distanceMeters == null, "No integration over recovery gap");
            store.start("tail", 1700000000000L, 1000, "pocket");
            store.appendBatch(
                    "tail",
                    gps(1000, 43.82, 2, 5, .1, null, null) + "GPS,1700000002000,2000,43.83",
                    "");
            check(store.load("tail").locationCount == 1, "Interrupted final row ignored");
            store.start("tail", 1700000002000L, 2000, "pocket");
            store.appendBatch("tail", gps(3000, 43.8201, 2, 5, .1, null, null), "");
            check(
                    store.load("tail").locationCount == 2,
                    "Recovery trims partial tail before new rows");
            store.start("gps-height", 1700000000000L, 1000, "pocket");
            store.appendBatch(
                    "gps-height",
                    gps(1000, 43.82, 2, 5, .1, 1500.0, 2.0)
                            + gps(2000, 43.8201, 2, 5, .1, 1501.0, 2.0),
                    "");
            PhoneRideStore.Track gpsHeight = store.load("gps-height");
            near(
                    0,
                    gpsHeight.points.get(0).relativeAltitudeM,
                    0,
                    "GPS elevation uses relative baseline");
            near(1, gpsHeight.points.get(1).relativeAltitudeM, 0, "GPS relative height change");
            check(
                    !gpsHeight.toGpx().contains("<ele>"),
                    "Ellipsoid height not mislabeled as GPX sea-level elevation");
            store.start("uncertain-speed", 1700000000000L, 1000, "pocket");
            store.appendBatch(
                    "uncertain-speed",
                    gps(1000, 43.82, 2, 0, null, null, null)
                            + gps(3000, 43.8201, 2, 5, .1, null, null),
                    "");
            check(
                    store.load("uncertain-speed").maxGpsAccelerationMps2 == null,
                    "Both derivative endpoints require speed accuracy");
            store.start("stale-speed", 1700000000000L, 1000, "pocket");
            store.appendBatch(
                    "stale-speed",
                    PhoneRideStore.row(
                                    "TELEMETRY", 1700000001000L, 1000, 0, 100, 90, 1000, 1000, 1000)
                            + PhoneRideStore.row(
                                    "TELEMETRY",
                                    1700000020000L,
                                    20000,
                                    100,
                                    100,
                                    90,
                                    3000,
                                    3000,
                                    3000),
                    "");
            check(
                    store.load("stale-speed").maxScooterAccelerationMps2 == null,
                    "Stale speed cannot create a summary peak");
            store.start("reboot", 1700000000000L, 100000, "pocket");
            store.appendBatch(
                    "reboot",
                    gps(100000, 43.82, 2, 5, .1, null, null)
                            + PhoneRideStore.row("GAP", 1700000002000L, 1000)
                            + gps(1000, 43.8201, 2, 5, .1, null, null),
                    "");
            PhoneRideStore.Track reboot = store.load("reboot");
            check(
                    reboot.locationCount == 2 && reboot.points.get(1).gapBefore,
                    "New clock epoch retained as separate segment");
            store.delete("hill");
            check(
                    !store.rawCsv("hill").exists() && !store.motionCsv("hill").exists(),
                    "Delete removes phone locations and raw motion");
            boolean blocked = false;
            try {
                store.rawCsv("../owner");
            } catch (IllegalArgumentException expected) {
                blocked = true;
            }
            check(blocked, "Invalid ID cannot traverse private files");
            store.start("long", 1700000000000L, 1000, "pocket");
            StringBuilder longRows = new StringBuilder();
            for (int i = 0; i < 6500; i++) longRows.append(gps(1000 + i * 1000L, 43.82 + i * .00001, 2, i == 1235 ? 15 : 5, .1, 1500.0, 2.0));
            store.appendBatch("long", longRows.toString(), ""); store.stop("long", 1700006500000L, 6501000);
            PhoneRideStore.Track longTrack = store.loadSnapshot("long");
            check(longTrack.points.size() <= 3000 && longTrack.fullPoints().size() == 6500, "Full phone snapshot retains all accepted fixes beyond map preview cap");
            near(15, longTrack.fullPoints().get(1235).speedMps, 0, "Short GPS speed peak remains in full source");
            check(longTrack.points.stream().noneMatch(p -> p.monoMs == 1236000), "Phone peak omitted by preview is covered by full-data regression");
            java.util.List<RideChartData.Series> longSeries = RideChartData.build(java.util.Collections.emptyList(), longTrack, false, true);
            check(longSeries.get(2).values.size() == 6500, "Expanded phone graph uses every accepted fix");
            near(54, longSeries.get(2).sampleAt(1235000).value, 0.00001, "Phone peak can be inspected in display units at its source elapsed time");
            Thread.currentThread().interrupt(); boolean canceled = false;
            try { store.loadSnapshot("long"); } catch (InterruptedIOException expected) { canceled = true; } finally { Thread.interrupted(); }
            check(canceled && store.loadSnapshot("long").fullPoints().size() == 6500, "Canceled phone load closes reader and retries cleanly");
            try (RandomAccessFile oversized = new RandomAccessFile(store.rawCsv("oversized"), "rw")) { oversized.setLength(128L * 1024 * 1024 + 1); }
            boolean tooLarge = false; try { store.loadSnapshot("oversized"); } catch (IOException expected) { tooLarge = true; }
            check(tooLarge, "Oversized phone journal reports a load error instead of a silently partial full graph");
            System.out.println("PhoneRideStoreTest: " + checks + " assertions passed");
        } finally {
            File[] files = dir.listFiles();
            if (files != null) for (File f : files) f.delete();
            dir.delete();
        }
    }

    private static int count(String text, String needle) {
        int n = 0, pos = 0;
        while ((pos = text.indexOf(needle, pos)) >= 0) {
            n++;
            pos += needle.length();
        }
        return n;
    }
}
