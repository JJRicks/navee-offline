package local.navee.offlinelock;

import java.io.*;
import java.nio.file.Files;
import java.util.*;

/** Complete synthetic journals only; no personal recordings or routes. */
public final class LearnedRangeEstimateTest {
    private static final long NOW = 1800000000000L, DAY = 86400000L;
    private static final List<File> directories = new ArrayList<>();
    private static int checks;
    public static void main(String[] args) throws Exception {
        try {
            if (args.length > 0 && args[0].equals("memory-pressure")) { memoryPressure(); return; }
            poolingAndScope(); quality(); completedAndFullDetail(); streamingParity(); storageStates(); energyModel(); likelyRange(); memorySubprocess();
            System.out.println("LearnedRangeEstimateTest: " + checks + " assertions passed");
        } finally { for (File file : directories) remove(file); }
    }
    private static void poolingAndScope() throws Exception {
        Fixture a = fixture("A", 8, 2, "normal", NOW, 600);
        Fixture b = fixture("A", 8, 1.2, "normal", NOW, 600);
        Fixture c = fixture("A", 4, 1.2, "normal", NOW, 600);
        List<LearnedRangeEstimate.Evidence> evidence = Arrays.asList(a.evidence(), b.evidence(), c.evidence(), a.evidence(),
                fixture("B", 50, 50, "normal", NOW, 600).evidence(),
                fixture("A", 20, 20, "normal", NOW-91*DAY, 600).evidence());
        LearnedRangeEstimate.Result r = LearnedRangeEstimate.calculate("A", evidence, NOW);
        check(r.rides == 3 && r.rejectedRides == 0, "Profile/date scope and ID deduplication");
        near(22, r.fullRangeKm, "Ratio of totals, including efficient four-point short leg");
        near(11, r.remainingKm(50d), "Remaining range follows current gauge");
        near(0, r.remainingKm(0d), "Zero battery is zero estimate");
        check(r.remainingKm(null) == null && r.remainingKm(-1d) == null && r.remainingKm(101d) == null
                && r.remainingKm(Double.NaN) == null, "Invalid SOC stays unavailable");
        check(LearnedRangeEstimate.calculate("B", evidence, NOW).fullRangeKm == null, "Other profile cannot borrow history");
        r = LearnedRangeEstimate.calculate("A", Arrays.asList(a.evidence(), b.evidence()), NOW);
        check(r.fullRangeKm == null && r.rides == 2 && r.batteryPoints == 16, "Insufficient evidence retains progress");
        r = LearnedRangeEstimate.calculate("A", Arrays.asList(a.evidence(), b.evidence(), fixture("A",2,.5,"normal",NOW,600).evidence()),NOW);
        check(r.fullRangeKm == null && r.batteryPoints == 18, "Twenty-point evidence minimum");
        r = LearnedRangeEstimate.calculate("A", Arrays.asList(a.evidence(), b.evidence(), fixture("A",4,1.2,"one_rebound",NOW,600).evidence()),NOW);
        check(r.fullRangeKm != null, "One point of gauge rounding is tolerated");
        r = LearnedRangeEstimate.calculate("A", Arrays.asList(fixture("A",8,2,"no_temperature",NOW,600).evidence(), b.evidence(), c.evidence()), NOW);
        check(r.fullRangeKm != null && r.minimumTemperatureC == 20, "Missing optional temperature does not reject a valid ride");
        r = LearnedRangeEstimate.calculate("A", Arrays.asList(fixture("A",8,2,"no_temperature",NOW,600).evidence(), fixture("A",8,2,"no_temperature",NOW,600).evidence(), fixture("A",4,1,"no_temperature",NOW,600).evidence()), NOW);
        check(r.fullRangeKm != null && r.minimumTemperatureC == null && r.maximumTemperatureC == null, "Entirely absent temperature stays unknown");
        check(LearnedRangeEstimate.unavailable("A", "Unavailable").remainingKm(60d) == null, "Unavailable never becomes zero");
    }
    private static void quality() throws Exception {
        for (String mode : Arrays.asList("gap", "rebound", "charging", "reset", "missing_first_soc", "missing_last_odo", "speed_missing", "soc_missing", "clock")) {
            Fixture f = fixture("A",8,2,mode,NOW,600);
            check(f.evidence().rejection != null, "Reject " + mode);
        }
        check(fixture("A",1,2,"normal",NOW,600).evidence().rejection != null, "One-point drop too quantized");
        check(fixture("A",4,.1,"normal",NOW,600).evidence().rejection != null, "Tiny distance rejected");
        check(fixture("A",4,2,"normal",NOW,20).evidence().rejection != null, "Tiny duration rejected");
        Fixture mismatch = fixture("A",4,2,"distance_mismatch",NOW,600);
        check(mismatch.evidence().rejection != null, "Large odometer jump fails speed crosscheck");
        Fixture future = fixture("A",4,2,"normal",NOW+DAY,600);
        check(LearnedRangeEstimate.calculate("A",Arrays.asList(future.evidence()),NOW).rejectedRides == 1, "Future dated recording rejected");
    }
    private static void completedAndFullDetail() throws Exception {
        Fixture active = fixture("A",20,3,"active",NOW,1000);
        check(LearnedRangeEstimate.inspect(active.store.activeSummary(),active.store.trend(active.id,600)).rejection != null, "Active ride never trains");
        Fixture longRide = fixture("A",20,3,"normal",NOW,1000);
        check(longRide.store.fullTrend(longRide.id).size() == 1001, "Full fixture exceeds preview cap");
        check(longRide.evidence().rejection == null, "Full long recording accepted");
        check(LearnedRangeEstimate.inspect(longRide.store.get(longRide.id),longRide.store.trend(longRide.id,600)).rejection != null, "Downsampled quality evidence rejected");
        Fixture spike = fixture("A",20,3,"single_charging_sample",NOW,1000);
        check(spike.evidence().rejection != null, "Single charging sample in long journal is seen");
        longRide.store.delete(longRide.id);
        check(LearnedRangeEstimate.calculate("A",Collections.emptyList(),NOW).fullRangeKm == null, "Recalculation without deleted history clears model");
    }
    private static void streamingParity() throws Exception {
        for (String mode : Arrays.asList("normal", "gap", "rebound", "one_rebound", "charging", "reset",
                "missing_first_soc", "missing_last_odo", "speed_missing", "soc_missing", "clock", "no_temperature",
                "distance_mismatch", "single_charging_sample")) {
            Fixture f = fixture("A",20,3,mode,NOW,1000);
            LearnedRangeEstimate.Result full = LearnedRangeEstimate.calculate("A", Arrays.asList(f.evidence()), NOW);
            LearnedRangeEstimate.Result streamed = f.store.learnedRange("A", NOW);
            check(full.status == streamed.status && full.rides == streamed.rides
                    && full.rejectedRides == streamed.rejectedRides && full.distanceKm == streamed.distanceKm
                    && full.batteryPoints == streamed.batteryPoints, "Every-sample streaming parity: " + mode);
        }
        Fixture active = fixture("A",20,3,"active",NOW,1000);
        LearnedRangeEstimate.Result r = active.store.learnedRange("A",NOW);
        check(r.status == LearnedRangeEstimate.Status.INSUFFICIENT && r.rides == 0 && r.unreadableRides == 0,
                "Active capture is intentionally omitted, not an analysis failure");
    }
    private static void storageStates() throws Exception {
        File dir = Files.createTempDirectory("volt-range-batch-").toFile(); directories.add(dir);
        RideStore store = new RideStore(dir);
        check(store.learnedRange("A",NOW).status == LearnedRangeEstimate.Status.INSUFFICIENT, "True empty archive has insufficient status");
        check(LearnedRangeEstimate.unavailable("A","read failed").status == LearnedRangeEstimate.Status.UNAVAILABLE, "Read error has distinct unavailable status");
        File broken = new File(dir,UUID.randomUUID()+".csv"); Files.write(broken.toPath(),"invalid journal".getBytes("UTF-8"));
        LearnedRangeEstimate.Result failed = store.learnedRange("A",NOW);
        check(failed.status == LearnedRangeEstimate.Status.UNAVAILABLE && failed.unreadableRides == 1,
                "Unreadable archive cannot look like no ride history");
        for(int n=0;n<3;n++) { Fixture f=fixture("A",8,2,"normal",NOW,600);f.store.exportCsv(f.id,new File(dir,f.id+".csv")); }
        LearnedRangeEstimate.Result partial = store.learnedRange("A",NOW);
        check(partial.status == LearnedRangeEstimate.Status.READY && partial.rides == 3 && partial.unreadableRides == 1,
                "Unreadable unrelated file does not discard verified sufficient evidence");
        near(25,partial.fullRangeKm,"Streaming pool unchanged");
        File moved = new File(dir.getParentFile(),dir.getName()+"-moved"); directories.add(moved);
        check(dir.renameTo(moved),"Rename fixture directory");
        try { store.learnedRange("A",NOW);throw new AssertionError("Missing directory was reported empty"); }
        catch(IOException expected) { checks++; }
        check(moved.renameTo(dir),"Restore fixture directory");
        Fixture source=fixture("A",20,3,"normal",NOW,1000);
        // Simulate the two metadata observations around a concurrent file replacement, without
        // nondeterministic sleep/thread timing or changing the real reader/parser.
        final boolean[] armed={false}; final int[] lengths={0};
        File changedFile=new File(source.directory,source.id+".csv") {
            @Override public long length(){long actual=super.length();return armed[0]&&++lengths[0]>1?actual+1:actual;}
        };
        File changedDirectory=new File(source.directory.getPath()) {
            @Override public File[] listFiles(){return new File[]{changedFile};}
        };
        RideStore changing = new RideStore(changedDirectory);armed[0]=true;
        try { changing.learnedRange("A",NOW);throw new AssertionError("Mixed journal snapshot accepted"); }
        catch(RideStore.JournalChangedException expected) { checks++; }
    }
    /** Rides with recorded power: battery energy left at the reading ÷ energy per km. */
    private static void energyModel() throws Exception {
        // 80% to 60% at 600 W for 600 s: 100 Wh over 20 gauge points (5 Wh each) and 2.5 km (40 Wh/km).
        List<LearnedRangeEstimate.Evidence> three = new ArrayList<>();
        for (int i = 0; i < 3; i++) three.add(energy("A", 20, 60, 2.5, 600, "normal", NOW - i * DAY).evidence());
        LearnedRangeEstimate.Evidence one = three.get(0);
        check(one.rejection == null && one.rideWh != null && Math.abs(one.rideWh - 100) < 1e-6, "Ride energy is the net draw: " + one.rideWh);
        check(one.gaugePoints() == 19, "Points entered and left by a one-point drop are counted; the partial first is not: " + one.gaugePoints());
        LearnedRangeEstimate.Result r = LearnedRangeEstimate.calculate("A", three, NOW);
        check(r.model == LearnedRangeEstimate.Model.ENERGY && r.status == LearnedRangeEstimate.Status.READY, "Recorded power selects the energy model");
        near(40, r.whPerKm, "Energy per km pools rides");
        // Seen bands 61-70 and 71-80 hold 5 Wh/pt; above, the nearest seen band; 60 and below, 10% less.
        near((40 * 5 + 60 * 4.5 - 2.5) / 40, r.fullRangeKm, "Full range: 99.5 points, with caution below the lowest band seen");
        near((10 * 5 + 60 * 4.5 - 2.5) / 40, r.remainingKm(70d), "A reading is the middle of its gauge point");
        near(2.25 / 40 / 2, r.remainingKm(1d) / 2, "Bottom point uses the cautious floor");
        near(0, r.remainingKm(0d), "Zero battery is zero estimate");
        check(r.remainingKm(20d) < r.fullRangeKm * .2, "Low battery is no longer linear in the gauge");
        double previous = -1; boolean monotone = true;
        for (double soc = 0; soc <= 100; soc += .25) { double km = r.remainingKm(soc); monotone &= km >= previous; previous = km; }
        check(monotone, "More battery never forecasts less range");
        check(r.remainingKm(null) == null && r.remainingKm(Double.NaN) == null && r.remainingKm(101d) == null, "Invalid SOC stays unavailable");
        // Without a confirmed current direction there is no energy evidence: the older model stays.
        List<LearnedRangeEstimate.Evidence> unsigned = new ArrayList<>();
        for (int i = 0; i < 3; i++) unsigned.add(energy("A", 20, 60, 2.5, 600, "unconfirmed", NOW).evidence());
        check(unsigned.get(0).rideWh == null && unsigned.get(0).gaugePoints() == 0, "Unconfirmed direction yields no energy evidence");
        LearnedRangeEstimate.Result legacy = LearnedRangeEstimate.calculate("A", unsigned, NOW);
        check(legacy.model == LearnedRangeEstimate.Model.DISTANCE, "No power keeps distance per gauge point");
        near(12.5, legacy.fullRangeKm, "Older model unchanged: 7.5 km over 60 points");
        // Negative-is-draw scooters give the same evidence; regeneration counts against draw.
        LearnedRangeEstimate.Evidence negative = energy("A", 20, 60, 2.5, -600, "negative", NOW).evidence();
        check(negative.rideWh != null && Math.abs(negative.rideWh - 100) < 1e-6 && negative.gaugePoints() == 19, "Negative-is-draw convention");
        LearnedRangeEstimate.Evidence regen = energy("A", 20, 60, 2.5, 600, "regen", NOW).evidence();
        check(regen.rideWh != null && regen.rideWh < 100, "Regeneration lowers net energy: " + regen.rideWh);
        // A short gap rejects the ride for the older model but leaves its energy evidence usable.
        LearnedRangeEstimate.Evidence gap = energy("A", 20, 60, 2.5, 600, "gap", NOW).evidence();
        // The gap falls on the 71 to 70 drop: 71 is cut off and 70 was never entered by a seen drop.
        check(gap.rejection != null && gap.rideWh != null && gap.gaugePoints() == 17, "Gap: counted points stop at the break, energy still counts: " + gap.gaugePoints());
        LearnedRangeEstimate.Evidence charging = energy("A", 20, 60, 2.5, 600, "charging", NOW).evidence();
        check(charging.rideWh == null, "Charging during a ride gives no energy per km");
        LearnedRangeEstimate.Evidence jump = energy("A", 20, 60, 2.5, 600, "jump", NOW).evidence();
        check(jump.gaugePoints() < 19, "A skipped gauge point is never counted: " + jump.gaugePoints());
        LearnedRangeEstimate.Evidence sparse = energy("A", 20, 60, 2.5, 600, "sparse_power", NOW).evidence();
        check(sparse.rideWh == null && sparse.gaugePoints() == 0, "Under 95% power coverage gives no energy evidence");
        Fixture streamed = energy("A", 20, 60, 2.5, 600, "normal", NOW);
        LearnedRangeEstimate.Result full = LearnedRangeEstimate.calculate("A", Arrays.asList(streamed.evidence()), NOW);
        LearnedRangeEstimate.Result viaStore = streamed.store.learnedRange("A", NOW);
        check(full.gaugePoints == viaStore.gaugePoints && full.energyRides == viaStore.energyRides && Math.abs(full.energyKm - viaStore.energyKm) < 1e-9,
                "Every-sample streaming parity for energy evidence");
    }
    /** Likely range from how past 6 km stretches compared with the forecast. */
    private static void likelyRange() throws Exception {
        List<LearnedRangeEstimate.Evidence> rides = new ArrayList<>();
        // Ten 20-point rides alternating 2 and 3 km: three-ride stretches go 7 or 8 km against a 7.5 km forecast.
        for (int i = 0; i < 10; i++) rides.add(energy("A", 20, 60, i % 2 == 0 ? 2 : 3, 600, "normal", NOW - (10 - i) * 3600000L).evidence());
        LearnedRangeEstimate.Result r = LearnedRangeEstimate.calculate("A", rides, NOW);
        check(r.likelyLowFactor != null && r.likelyLowFactor < 1 && r.likelyHighFactor > 1, "Varying stretches give a band around the forecast");
        // Each ride's forecast: 80% to 60% is 19.5 points at 5 Wh plus half of the 4.5 Wh floor point = 99.75 Wh at 40 Wh/km.
        near(7 / (3 * 99.75 / 40), r.likelyLowFactor, "Low end: 5th percentile of actual over forecast km");
        near(8 / (3 * 99.75 / 40), r.likelyHighFactor, "High end: 95th percentile");
        check(r.likelyLowKm(50d) < r.remainingKm(50d) && r.likelyHighKm(50d) > r.remainingKm(50d), "The forecast sits inside its likely range");
        check(r.likelyLowKm(null) == null, "No reading, no range");
        LearnedRangeEstimate.Result few = LearnedRangeEstimate.calculate("A", rides.subList(0, 4), NOW);
        check(few.status == LearnedRangeEstimate.Status.READY && few.likelyLowFactor == null && few.likelyLowKm(50d) == null,
                "Too few 6 km stretches: forecast only, no band");
    }
    private static Fixture energy(String profile, double drop, double soc0, double km, double watts, String mode, long end) throws Exception {
        // soc0 is the end level: the ride runs from soc0 + drop down to soc0 over 600 s.
        File dir = Files.createTempDirectory("volt-range-energy-").toFile(); directories.add(dir);
        RideStore store = new RideStore(dir); int seconds = 600; long start = end - seconds * 1000L;
        store.setCurrentDirection(mode.equals("unconfirmed") ? RideStore.CurrentDirection.UNCONFIRMED
                : mode.equals("negative") ? RideStore.CurrentDirection.NEGATIVE_IS_DRAW : RideStore.CurrentDirection.POSITIVE_IS_DRAW);
        String id = store.start(profile, "Synthetic", start, 0);
        for (int sec = 0; sec <= seconds; sec++) {
            long mono = sec * 1000L; double fraction = sec / (double) seconds;
            double soc = Math.ceil(soc0 + drop - drop * fraction);
            if (mode.equals("jump") && sec >= 300) soc = Math.max(soc0, soc - 3);
            Map<String,RideStore.Sample> values = new LinkedHashMap<>();
            values.put("battery_percent", new RideStore.Sample(soc, mono));
            values.put("odometer_km", new RideStore.Sample(100 + km * fraction, mono));
            values.put("speed_kmh", new RideStore.Sample(km * 3600 / seconds, mono));
            values.put("charging_state", new RideStore.Sample(mode.equals("charging") && sec == 400 ? 1 : 0, mono));
            double current = watts / 50;
            if (mode.equals("regen") && sec >= 100 && sec < 160) current = -current / 2;
            if (!mode.equals("sparse_power") || sec % 10 < 5) {
                values.put("voltage_v", new RideStore.Sample(50.0, "50V", "V", 0x72, mono));
                values.put("current_a", new RideStore.Sample(current, "current", "A", 0x72, mono));
            }
            if (mode.equals("gap") && sec == 300) store.markGap(start + mono, mono, "Synthetic gap");
            store.sample(start + mono, mono, values);
        }
        store.stop(end, seconds * 1000L);
        return new Fixture(store, id, dir);
    }
    private static void memorySubprocess() throws Exception {
        String java = new File(System.getProperty("java.home"),"bin/java").getPath();
        Process child = new ProcessBuilder(java,"-Xmx64m","-XX:+UseSerialGC","-cp",System.getProperty("java.class.path"),
                LearnedRangeEstimateTest.class.getName(),"memory-pressure").inheritIO().start();
        check(child.waitFor()==0,"Bounded streaming under memory pressure");
    }
    private static void memoryPressure() throws Exception {
        Fixture f=fixture("A",20,3,"normal",NOW,1000);
        Runtime runtime=Runtime.getRuntime(); System.gc();
        List<byte[]> pressure=new ArrayList<>();
        // Hold enough live memory to trigger the existing full-detail reserve, leaving ample
        // space for a bounded frame/preview. This reproduces the old false analysis failure.
        while(runtime.maxMemory()-(runtime.totalMemory()-runtime.freeMemory()) > 6L*1024*1024)
            pressure.add(new byte[256*1024]);
        try { f.store.fullTrend(f.id);throw new AssertionError("Expected full-detail memory guard"); }
        catch(RideStore.DetailMemoryException expected) { checks++; }
        LearnedRangeEstimate.Result result=f.store.learnedRange("A",NOW);
        check(result.rides==1 && result.rejectedRides==0 && result.status==LearnedRangeEstimate.Status.INSUFFICIENT,
                "Streaming remains valid when old full-list path trips its memory guard");
        check(pressure.size()>0,"Keep memory pressure reachable through analysis");
        System.out.println("Learned range memory pressure: full-detail guard reproduced; streaming passed");
    }
    private static Fixture fixture(String profile, double drop, double km, String mode, long end, int seconds) throws Exception {
        File dir = Files.createTempDirectory("volt-range-").toFile(); directories.add(dir);
        RideStore store = new RideStore(dir); long start = end - seconds*1000L;
        String id = store.start(profile,"Synthetic",start,0);
        for (int sec=0;sec<=seconds;sec++) {
            long mono=sec*1000L; double fraction=sec/(double)seconds, soc=80-drop*fraction;
            if (mode.equals("one_rebound") && sec == seconds/2) soc += .9;
            if (mode.equals("rebound") && sec == seconds/2) soc += 3;
            double odo=100+km*fraction;
            if (mode.equals("reset") && sec > seconds/2) odo -= 10;
            Map<String,RideStore.Sample> values = new LinkedHashMap<>();
            if ((!mode.equals("missing_first_soc") || sec != 0) && (!mode.equals("soc_missing") || sec % 2 == 0)) values.put("battery_percent",new RideStore.Sample(soc,mono));
            if (!mode.equals("missing_last_odo") || sec != seconds) values.put("odometer_km",new RideStore.Sample(odo,mono));
            if (!mode.equals("speed_missing") || sec % 2 == 0) values.put("speed_kmh",new RideStore.Sample(km*3600/seconds*(mode.equals("distance_mismatch")?2:1),mono));
            if (!mode.equals("no_temperature")) values.put("temperature_c",new RideStore.Sample(20,mono));
            values.put("charging_state",new RideStore.Sample(mode.equals("charging") || mode.equals("single_charging_sample")&&sec==501 ? 1:0,mono));
            if (mode.equals("gap") && sec==seconds/2) store.markGap(start+mono,mono,"Synthetic gap");
            store.sample(start+mono+(mode.equals("clock")&&sec>seconds/2?30000:0),mono,values);
        }
        if (!mode.equals("active")) store.stop(end+(mode.equals("clock")?30000:0),seconds*1000L);
        return new Fixture(store,id,dir);
    }
    private static final class Fixture {
        final RideStore store; final String id; final File directory;
        Fixture(RideStore store,String id,File directory){this.store=store;this.id=id;this.directory=directory;}
        LearnedRangeEstimate.Evidence evidence() throws Exception { return LearnedRangeEstimate.inspect(store.get(id),store.fullTrend(id)); }
    }
    private static void near(double expected,Double actual,String message){check(actual!=null&&Math.abs(expected-actual)<1e-7,message+" expected="+expected+" actual="+actual);}
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static void remove(File file){File[] children=file.listFiles();if(children!=null)for(File child:children)remove(child);file.delete();}
}
