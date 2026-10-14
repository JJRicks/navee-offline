package local.navee.offlinelock;
import java.util.*;
import java.nio.file.Files;
import java.io.File;

public final class RideChartDataTest {
    static int checks;
    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    static void near(double expected, Double actual, String message) { check(actual != null && Math.abs(expected - actual) < 0.00001, message + ": " + actual); }
    public static void main(String[] args) throws Exception {
        near(50, RideChartData.energyPerDistance(1000d, 20d, false), "W divided by km/h yields Wh/km");
        near(80.4672, RideChartData.energyPerDistance(1000d, 20d, true), "Imperial conversion preserves energy");
        near(-10, RideChartData.energyPerDistance(-200d, 20d, false), "Battery input keeps negative sign");
        check(RideChartData.energyPerDistance(1000d, 2.9, false) == null, "Low-speed ratio unavailable");
        check(RideChartData.energyPerDistance(1000d, 0d, false) == null, "Stationary energy does not divide by zero");
        check(RideChartData.energyPerDistance(Double.NaN, 20d, false) == null, "Nonfinite readings excluded");
        check(RideChartData.energyPerDistance(null, 20d, false) == null, "Missing readings excluded");
        check(RideChartData.defaults().equals(new LinkedHashSet<String>(Arrays.asList("power_w", "speed_kmh"))), "Power and wheel speed default together");
        File dir = Files.createTempDirectory("ride-chart").toFile(); RideStore store = new RideStore(dir);
        String id = store.start("fixture", "Hill", 1000000, 1000);
        for (int i = 0; i < 3; i++) { Map<String,RideStore.Sample> row = new HashMap<>(); long mono = 1000 + i * 1000;
            row.put("voltage_v", new RideStore.Sample(50d, "", "V", 0x72, mono)); row.put("current_a", new RideStore.Sample(20d, "", "A", 0x72, mono));
            row.put("speed_kmh", new RideStore.Sample(20d, mono)); row.put("battery_percent", new RideStore.Sample(95d, mono)); store.sample(1000000 + i * 1000, mono, row); }
        store.stop(1003000, 4000); List<RideStore.Point> points = store.trend(id, 600);
        List<RideChartData.Series> metric = RideChartData.build(points, null, false), imperial = RideChartData.build(points, null, true);
        check(metric.size() == 8, "Every unified graph series exists");
        near(20, metric.get(1).values.get(0).value, "Metric wheel speed"); near(20 / 1.609344, imperial.get(1).values.get(0).value, "Imperial wheel speed");
        near(50, metric.get(7).values.get(0).value, "Instantaneous efficiency is separate from ride average");
        check(metric.get(4).min == 0 && metric.get(4).max == 100, "Battery scale fixed to meaningful limits");
        check(!metric.get(2).available() && !metric.get(5).available() && !metric.get(6).available(), "Old rides do not invent phone data");
        check(metric.get(0).max > metric.get(0).min, "Constant data still has valid scale");
        check(points.get(0).speedKmh == 20, "Display conversion does not mutate canonical readings");
        PhoneRideStore phoneStore = new PhoneRideStore(Files.createTempDirectory("ride-chart-phone").toFile());
        phoneStore.start("delayed", 1000500, 1500, "pocket");
        phoneStore.appendBatch("delayed", PhoneRideStore.row("GPS", 1001000, 2000, 43.82, -111.78, 3, 5, .2, 1500, 4), "");
        PhoneRideStore.Track track = phoneStore.load("delayed");
        List<RideChartData.Series> together = RideChartData.build(points, track, false);
        check(together.get(2).values.get(0).time == 1000, "Delayed phone start aligns using source clocks, not its own zero");
        near(18, together.get(2).values.get(0).value, "GPS speed converted from m/s independently of wheel speed");
        check(together.get(5).available(), "Recorded phone elevation included in unified plot");
        String gapId = store.start("fixture", "Gap", 2000000, 10000);
        Map<String,RideStore.Sample> row = new HashMap<>(); row.put("speed_kmh", new RideStore.Sample(10d, 10000));
        store.sample(2000000, 10000, row); store.markGap(2000500, 10500, "Bluetooth disconnected");
        row.put("speed_kmh", new RideStore.Sample(12d, 11000)); store.sample(2001000, 11000, row); store.stop(2001500,11500);
        List<RideChartData.Value> segmented = RideChartData.build(store.trend(gapId, 600), null, false).get(1).values;
        check(segmented.get(0).segment != segmented.get(1).segment, "Short gaps break plotted lines even below ten seconds");
        System.out.println("RideChartDataTest: " + checks + " assertions passed");
    }
}
