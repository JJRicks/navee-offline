package local.navee.offlinelock;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Stop reason in the ride journal and the learned-range view of automatically ended rides. Synthetic data only. */
public final class RideAutoStopStoreTest {
    private static final long NOW = 1800000000000L;
    private static final List<File> directories = new ArrayList<>();
    private static int checks;
    public static void main(String[] args) throws Exception {
        try {
            stopReasonIsBackwardCompatible();
            learnedRangeUsesMotionNotTail();
            failedSaveKeepsRecording();
            System.out.println("RideAutoStopStoreTest: " + checks + " assertions passed");
        } finally { for (File file : directories) remove(file); }
    }

    private static void stopReasonIsBackwardCompatible() throws Exception {
        File dir = directory(); RideStore store = new RideStore(dir);
        String manual = store.start("A", "Manual", NOW, 1000); store.stop(NOW + 5000, 6000);
        String auto = store.start("A", "Auto", NOW + 10000, 11000); store.stop(NOW + 15000, 16000, RideStore.STOP_AUTO_PARKED);
        String unknown = store.start("A", "Odd", NOW + 20000, 21000); store.stop(NOW + 25000, 26000, "SOMETHING_ELSE");
        String journal = new String(Files.readAllBytes(new File(dir, manual + ".csv").toPath()), StandardCharsets.UTF_8);
        check(journal.trim().endsWith(",,"), "A rider's stop writes the same empty details column as before");
        journal = new String(Files.readAllBytes(new File(dir, auto + ".csv").toPath()), StandardCharsets.UTF_8);
        check(journal.trim().endsWith("," + RideStore.STOP_AUTO_PARKED), "Automatic stop stores its reason in the existing details column");
        check(new String(Files.readAllBytes(new File(dir, unknown + ".csv").toPath()), StandardCharsets.UTF_8).trim().endsWith(",,"), "Unknown reasons are not written");
        RideStore reopened = new RideStore(dir);
        check(reopened.activeId() == null, "Stopped rides are not recovered as active after a restart");
        RideStore.Summary m = reopened.get(manual), a = reopened.get(auto);
        check("".equals(m.stopReason) && !m.automaticStop && !m.active, "Rider stop reads back as the rider's");
        check(RideStore.STOP_AUTO_PARKED.equals(a.stopReason) && a.automaticStop && !a.active, "Automatic stop reads back");
        check(a.toJson().contains("\"stopReason\":\"automatic_parked_disconnect\"") && m.toJson().contains("\"stopReason\":\"rider\""), "Analysis JSON names the stop");
        check(a.elapsedMs == 5000 && a.endWallMs == NOW + 15000, "Stop time is the real stop time, not backdated");
        check(RideStore.inspectJournal(new File(dir, auto + ".csv")).automaticStop, "Backup staging inspection accepts the journal");
        // A journal written by 1.3.1 (STOP with an empty details column) still reads as stopped by the rider.
        File legacy = new File(dir, "00000000-0000-4000-8000-000000000001.csv");
        String header = journalHeader(new File(dir, manual + ".csv"));
        Files.write(legacy.toPath(), (header
                + "1,START,2027-01-15T08:00:00.000Z,1800000000000,1000,,A,,Legacy,,,,,UNCONFIRMED,\n"
                + "1,STOP,2027-01-15T08:00:05.000Z,1800000005000,6000,,,,,,,,,,\n").getBytes(StandardCharsets.UTF_8));
        RideStore.Summary old = new RideStore(dir).get("00000000-0000-4000-8000-000000000001");
        check(old != null && !old.active && "".equals(old.stopReason) && !old.automaticStop, "Legacy journal unchanged in meaning");
        check(new RideStore(dir).get(auto).stopReason.equals(RideStore.STOP_AUTO_PARKED), "Still readable after repeated reopen");
        check(store.activeSummary() == null, "No ride left active");
    }

    /** A realistic automatic end: ride, park, 12 s silent link, gap, 10 s grace, STOP. */
    private static void learnedRangeUsesMotionNotTail() throws Exception {
        Ride normal = ride("normal"), auto = ride("auto"), manualTail = ride("manual_tail"), longTail = ride("long_tail"), midGap = ride("mid_gap");
        LearnedRangeEstimate.Evidence n = normal.evidence(), a = auto.evidence();
        check(n.rejection == null, "Control ride trains: " + n.rejection);
        check(a.rejection == null, "Automatically ended ride still trains on its motion: " + a.rejection);
        check(a.summary.gapCount == 1 && a.summary.elapsedMs > n.summary.elapsedMs + 20000, "The archive keeps the real gap and stop time");
        check(Math.abs(a.summary.distanceKm - n.summary.distanceKm) < 1e-9 && Math.abs(a.summary.batteryDropPercent - n.summary.batteryDropPercent) < 1e-9,
                "Post-ride idle adds no distance or battery use");
        check(manualTail.evidence().rejection != null, "The same tail stopped by hand keeps today's conservative rejection");
        check(longTail.evidence().rejection != null, "A tail far longer than the auto-stop path is not trimmed");
        check(midGap.evidence().rejection != null, "An earlier gap still rejects an automatic ride");
        LearnedRangeEstimate.Result streamed = auto.store.learnedRange("A", NOW + 3_600_000L);
        LearnedRangeEstimate.Result full = LearnedRangeEstimate.calculate("A", Arrays.asList(a), NOW + 3_600_000L);
        check(streamed.rides == 1 && full.rides == 1 && streamed.distanceKm == full.distanceKm && streamed.batteryPoints == full.batteryPoints,
                "Streaming analysis matches full-detail inspection");
    }

    private static void failedSaveKeepsRecording() throws Exception {
        File dir = directory(); RideStore store = new RideStore(dir);
        String id = store.start("A", "Storage", NOW, 1000);
        File journal = new File(dir, id + ".csv");
        check(journal.delete() && journal.mkdir(), "Synthetic unwritable journal");
        boolean failed = false;
        try { store.stop(NOW + 5000, 6000, RideStore.STOP_AUTO_PARKED); } catch (IOException expected) { failed = true; }
        check(failed && id.equals(store.activeId()), "A failed automatic save leaves the ride recording for Retry save");
        journal.delete();
    }

    private static final class Ride {
        final RideStore store; final String id;
        Ride(RideStore store, String id) { this.store = store; this.id = id; }
        LearnedRangeEstimate.Evidence evidence() throws Exception { return LearnedRangeEstimate.inspect(store.get(id), store.fullTrend(id)); }
    }
    private static Ride ride(String mode) throws Exception {
        RideStore store = new RideStore(directory());
        int seconds = 600; long start = NOW, mono = 0;
        String id = store.start("A", "Synthetic", start, 0);
        for (int sec = 0; sec <= seconds; sec++) {
            mono = sec * 1000L;
            boolean parked = sec > seconds - 30;
            double speed = parked ? 0 : 24, soc = 80 - 8 * Math.min(1, sec / (double) (seconds - 30)), odo = 100 + 4 * Math.min(1, sec / (double) (seconds - 30));
            if (mode.equals("mid_gap") && sec == 300) store.markGap(start + mono, mono, "Synthetic gap");
            store.sample(start + mono, mono, values(speed, soc, odo, mono));
        }
        if (!mode.equals("normal")) {
            long last = mono;
            // Silent link: the tick still commits frames, but every reading is older than 3 s by now.
            for (int i = 1; i <= 12; i++) { mono = last + i * 1000L; store.sample(start + mono, mono, values(0, 72, 104, last)); }
            store.markGap(start + mono + 1000, mono + 1000, "Bluetooth disconnected");
            int tail = mode.equals("long_tail") ? 120 : 10;
            for (int i = 1; i <= tail; i++) store.heartbeat(start + mono + 1000 + i * 1000L, mono + 1000 + i * 1000L);
            mono += 1000 + tail * 1000L;
        }
        store.stop(start + mono, mono, mode.equals("manual_tail") || mode.equals("normal") ? "" : RideStore.STOP_AUTO_PARKED);
        return new Ride(store, id);
    }
    private static Map<String, RideStore.Sample> values(double speed, double soc, double odo, long updated) {
        Map<String, RideStore.Sample> values = new LinkedHashMap<>();
        values.put("battery_percent", new RideStore.Sample(soc, updated));
        values.put("odometer_km", new RideStore.Sample(odo, updated));
        values.put("speed_kmh", new RideStore.Sample(speed, updated));
        values.put("temperature_c", new RideStore.Sample(20, updated));
        values.put("charging_state", new RideStore.Sample(0, updated));
        return values;
    }
    private static String journalHeader(File journal) throws IOException {
        String text = new String(Files.readAllBytes(journal.toPath()), StandardCharsets.UTF_8);
        return text.substring(0, text.indexOf('\n') + 1);
    }
    private static File directory() throws IOException { File dir = Files.createTempDirectory("volt-autostop-").toFile(); directories.add(dir); return dir; }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static void remove(File file) { File[] children = file.listFiles(); if (children != null) for (File child : children) remove(child); file.delete(); }
}
