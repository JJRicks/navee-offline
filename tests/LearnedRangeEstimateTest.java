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
            poolingAndScope(); quality(); completedAndFullDetail();
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
        return new Fixture(store,id);
    }
    private static final class Fixture {
        final RideStore store; final String id;
        Fixture(RideStore store,String id){this.store=store;this.id=id;}
        LearnedRangeEstimate.Evidence evidence() throws Exception { return LearnedRangeEstimate.inspect(store.get(id),store.fullTrend(id)); }
    }
    private static void near(double expected,Double actual,String message){check(actual!=null&&Math.abs(expected-actual)<1e-7,message+" expected="+expected+" actual="+actual);}
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static void remove(File file){File[] children=file.listFiles();if(children!=null)for(File child:children)remove(child);file.delete();}
}
