package local.navee.offlinelock;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

public final class ChargeChartDataTest {
    private static int checks;
    private static final long WALL = 1_800_000_000_000L, MONO = 1_000_000L;
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static void near(double expected, Double actual, String message) {
        check(actual != null && Math.abs(expected - actual) <= Math.max(.000001, Math.abs(expected) * 1e-14), message + ": " + actual);
    }
    private static String metadata(String version, Double full, Double limit, Double target) {
        return "," + version + "," + field(full) + "," + field(limit) + "," + field(target) + ",CURRENT_RATE,CURRENT_RATE,0";
    }
    private static String field(Double value) { return value == null ? "" : value.toString(); }
    private static String row(long offset, double soc, double current, Double temperature, boolean gap, String metadata) {
        return row(offset, 0, soc, current, temperature, gap, metadata);
    }
    private static String row(long offset, long wallShift, double soc, double current, Double temperature, boolean gap, String metadata) {
        return "s," + (WALL + offset + wallShift) + "," + (MONO + offset) + "," + soc + ",52.0," + current
                + "," + (52 * current) + "," + field(temperature) + "," + (gap ? "1" : "0") + metadata + "\n";
    }
    private static ChargeSessionStore.HistorySession session(String version, String rows, String stop) throws Exception {
        File directory = Files.createTempDirectory("charge-chart-test-").toFile();
        File file = new File(directory, UUID.randomUUID() + ".csv");
        Files.write(file.toPath(), (version + ",fixture,STOCK," + WALL + "," + MONO + "\n" + rows + "end," + stop + "\n").getBytes(StandardCharsets.UTF_8));
        List<ChargeSessionStore.HistorySession> result = new ChargeSessionStore(directory).recordings("fixture");
        check(result.size() == 1, "Synthetic journal parsed"); return result.get(0);
    }
    private static RideChartData.Series find(List<RideChartData.Series> data, String key) {
        for (RideChartData.Series series : data) if (series.key.equals(key)) return series;
        throw new AssertionError("Missing " + key);
    }
    private static boolean has(List<RideChartData.Series> data, String key) {
        for (RideChartData.Series series : data) if (series.key.equals(key)) return true; return false;
    }
    private static boolean connected(RideChartData.Series series, int left, int right) { return series.connects(series.values.get(left), series.values.get(right)); }

    public static void main(String[] args) throws Exception {
        check(ChargeChartData.defaults().equals(new LinkedHashSet<>(Arrays.asList("power_w", "battery_percent", "estimated_finish", "estimated_countdown", "observed_finish"))), "Four requested categories and observed finish selected by default");
        check(ChargeChartData.build(null, false).isEmpty(), "Missing session is safe");
        boolean immutable = false; try { ChargeChartData.defaults().clear(); } catch (UnsupportedOperationException expected) { immutable = true; }
        check(immutable, "Default selection immutable");

        ChargeSessionStore.HistorySession legacy = session("charge-v1", row(0, 50, -2, 20d, true, "") + row(10000, 51, 1.5, null, false, ""), "STOPPED");
        List<RideChartData.Series> data = ChargeChartData.build(legacy, false);
        check(data.size() == 7, "Legacy retains the seven standard categories");
        near(-104, find(data, ChargeChartData.POWER).values.get(0).value, "Recorded negative battery input power preserved");
        near(78, find(data, ChargeChartData.POWER).values.get(1).value, "Recorded positive power preserved");
        near(-2, find(data, ChargeChartData.CURRENT).values.get(0).value, "Recorded signed current preserved");
        near(52, find(data, ChargeChartData.VOLTAGE).values.get(0).value, "Voltage included");
        near(50, find(data, ChargeChartData.BATTERY).values.get(0).value, "Battery percent included");
        check(find(data, ChargeChartData.BATTERY).min == 0 && find(data, ChargeChartData.BATTERY).max == 100, "Battery scale remains zero to 100");
        check(!find(data, ChargeChartData.FINISH).available() && !find(data, ChargeChartData.COUNTDOWN).available(), "Legacy estimates remain unavailable, not recalculated");
        check(find(data, ChargeChartData.COUNTDOWN).values.size() == 2 && find(data, ChargeChartData.COUNTDOWN).values.get(0).value == null, "Legacy samples stay aligned in the empty ETA series");
        near(20, find(data, ChargeChartData.TEMPERATURE).values.get(0).value, "Celsius unchanged");
        check(find(data, ChargeChartData.TEMPERATURE).values.get(1).value == null, "Missing temperature remains missing");
        List<RideChartData.Series> fahrenheit = ChargeChartData.build(legacy, true);
        near(68, find(fahrenheit, ChargeChartData.TEMPERATURE).values.get(0).value, "Fahrenheit converts display values");
        check("°F".equals(find(fahrenheit, ChargeChartData.TEMPERATURE).unit) && "°C".equals(find(data, ChargeChartData.TEMPERATURE).unit), "Temperature units match conversion");
        near(20, legacy.points.get(0).temperature, "Conversion does not mutate source temperature");
        check(find(data, ChargeChartData.POWER).sampleAt(5000).value == -104, "Touch inspection selects a real physical sample");
        immutable = false; try { data.clear(); } catch (UnsupportedOperationException expected) { immutable = true; } check(immutable, "Series list immutable");
        immutable = false; try { find(data, ChargeChartData.POWER).values.clear(); } catch (UnsupportedOperationException expected) { immutable = true; } check(immutable, "Series values immutable");

        ChargeSessionStore.HistorySession completed = session("charge-v2",
                row(0, 99, -2, 20d, true, metadata("v1", 2.3456789, 2.3456789, 100d))
                + row(10000, 3_600_000, 99.5, -2, 20d, false, metadata("v1", 1d, 1d, 100d))
                + row(20000, 3_600_000, 100, 0, 20d, false, metadata("v1", null, null, 100d)), "COMPLETE");
        data = ChargeChartData.build(completed, false);
        check(data.size() == 9 && has(data, ChargeChartData.OBSERVED_FINISH) && has(data, ChargeChartData.OBSERVED_COUNTDOWN), "Witnessed full completion adds both actual comparisons");
        check(!has(data, ChargeChartData.LIMIT_FINISH), "Full-only target avoids duplicate cutoff categories");
        RideChartData.Series countdown = find(data, ChargeChartData.COUNTDOWN), finish = find(data, ChargeChartData.FINISH);
        near(2.3456789, countdown.sampleAt(0).value, "Exact recorded countdown retained");
        near(WALL + Math.round(2.3456789 * 60000), finish.values.get(0).value, "Finish derives from exact recorded prediction and anchored time");
        near(WALL + 70000, finish.values.get(1).value, "Wall-clock change does not jump a finish prediction");
        check("Time".equals(finish.unit) && finish.values.get(1).time == 10000, "Clock values and elapsed axis have distinct units");
        near(1d / 3, find(data, ChargeChartData.OBSERVED_COUNTDOWN).values.get(0).value, "Actual countdown uses observed completion");
        near(0, find(data, ChargeChartData.OBSERVED_COUNTDOWN).values.get(2).value, "Actual countdown reaches observed zero");
        near(WALL + 20000, find(data, ChargeChartData.OBSERVED_FINISH).values.get(1).value, "Observed finish stays constant despite wall shift");
        check(countdown.values.get(2).value == null && finish.values.get(2).value == null, "Terminal missing estimate is not fabricated as zero");
        check(connected(find(data, ChargeChartData.OBSERVED_COUNTDOWN), 1, 2), "Actual comparison survives missing terminal ETA");
        check(finish.min == find(data, ChargeChartData.OBSERVED_FINISH).min && finish.max == find(data, ChargeChartData.OBSERVED_FINISH).max, "Finish and actual comparison share a scale");
        check(countdown.min == find(data, ChargeChartData.OBSERVED_COUNTDOWN).min && countdown.max == find(data, ChargeChartData.OBSERVED_COUNTDOWN).max, "Countdown and actual comparison share a scale");

        StringBuilder gaps = new StringBuilder();
        gaps.append(row(0, 50, -2, 20d, true, metadata("v1", 20d, 5d, 80d)));
        gaps.append(row(10000, 50, -2, 20d, false, metadata("v1", 19d, 4.9, 80d)));
        gaps.append(row(20000, 50, -2, 20d, false, metadata("v1", null, 4.8, 80d)));
        gaps.append(row(30000, 50, -2, 20d, false, metadata("v1", 18d, 4.7, 80d)));
        gaps.append(row(40000, 50, -2, 20d, false, metadata("v2", 17d, 4.6, 80d)));
        gaps.append(row(50000, 50, -2, 20d, false, metadata("v2", 16d, 8d, 90d)));
        gaps.append(row(60000, 50, -2, 20d, false, metadata("v2", 15d, 7.9, 90d)));
        gaps.append(row(80000, 50, -2, 20d, true, metadata("v2", 14d, 7.8, 90d)));
        gaps.append(row(110000, 50, -2, 20d, false, metadata("v2", 13d, 7.7, 90d)));
        data = ChargeChartData.build(session("charge-v2", gaps.toString(), "STOPPED"), false);
        RideChartData.Series power = find(data, ChargeChartData.POWER); countdown = find(data, ChargeChartData.COUNTDOWN);
        RideChartData.Series limitCountdown = find(data, ChargeChartData.LIMIT_COUNTDOWN), chargeLimit = find(data, ChargeChartData.LIMIT);
        check(data.size() == 10, "Lower targets add three optional categories");
        check(connected(power, 1, 2) && connected(power, 2, 3) && connected(power, 3, 4) && connected(power, 4, 5), "Missing ETA, version changes and cutoff changes do not break electrical traces");
        check(!connected(countdown, 1, 2) && !connected(countdown, 2, 3) && !connected(countdown, 3, 4), "Missing estimates and version changes break predictions");
        check(connected(countdown, 4, 5), "Cutoff change does not break full-charge prediction");
        check(connected(limitCountdown, 1, 2) && connected(limitCountdown, 2, 3), "Valid cutoff ETA survives missing full ETA");
        check(!connected(limitCountdown, 3, 4) && !connected(limitCountdown, 4, 5), "Version and target changes break cutoff predictions");
        check(connected(chargeLimit, 3, 4) && !connected(chargeLimit, 4, 5), "Target trace does not interpolate between different saved cutoff settings");
        check(chargeLimit.min == 0 && chargeLimit.max == 100, "Target scale matches battery percentage");
        for (RideChartData.Series series : data) check(!connected(series, 6, 7) && !connected(series, 7, 8), "Explicit and long gaps break " + series.key);
        check(power.sampleAt(70000) == null && countdown.sampleAt(35000) == null, "Touch cursor never invents data through gaps");

        for (String stop : new String[]{"STOPPED", "CHARGING_ENDED", "DISCONNECTED", "RECOVERY_TIMEOUT"}) {
            data = ChargeChartData.build(session("charge-v1", row(0, 99, -2, null, true, "") + row(10000, 100, 0, null, false, ""), stop), false);
            check(!has(data, ChargeChartData.OBSERVED_FINISH) && !has(data, ChargeChartData.OBSERVED_COUNTDOWN), "Manual/unobserved stop does not claim full endpoint: " + stop);
        }
        data = ChargeChartData.build(session("charge-v1", row(0, 99, -2, null, true, "") + row(10000, 100, 0, null, true, ""), "COMPLETE"), false);
        check(!has(data, ChargeChartData.OBSERVED_FINISH), "Reconnect at 100 is not an observed completion");
        data = ChargeChartData.build(session("charge-v1", row(0, 99, -2, null, true, "") + row(10000, 100, 0, null, false, ""), "COMPLETE"), false);
        check(has(data, ChargeChartData.OBSERVED_FINISH) && !find(data, ChargeChartData.FINISH).available(), "Legacy witnessed completion compares actuals without inventing predictions");
        check(find(data, ChargeChartData.OBSERVED_FINISH).max - find(data, ChargeChartData.OBSERVED_FINISH).min >= 60000, "Constant clock trace gets at least a minute of viewing range");

        data = ChargeChartData.build(session("charge-v2", row(0, 50, -2, null, true, metadata("v1", Double.MAX_VALUE, Double.MAX_VALUE, 80d))
                + row(10000, 50, -2, null, false, metadata("v1", Double.MIN_VALUE, Double.MIN_VALUE, 80d)), "STOPPED"), false);
        countdown = find(data, ChargeChartData.COUNTDOWN);
        check(countdown.values.get(0).value == Double.MAX_VALUE && countdown.values.get(1).value == Double.MIN_VALUE, "Finite extreme predictions preserved for exact inspection");
        check(find(data, ChargeChartData.FINISH).values.get(0).value == null, "Unrepresentable predicted calendar time remains unavailable");
        for (RideChartData.Series series : data) {
            check(Double.isFinite(series.min) && Double.isFinite(series.max) && series.max > series.min, "Extreme metadata keeps finite usable scale: " + series.key);
            ChartScale.Range scale = new ChartScale.Range(series.min, series.max);
            check(Double.isFinite(scale.at(.5)) && Double.isFinite(scale.fraction(series.max)), "Extreme scale interpolation stays finite: " + series.key);
        }
        data = ChargeChartData.build(session("charge-v2", row(0, 50, -2, null, true, metadata("v1", Double.MIN_VALUE, null, null)), "STOPPED"), false);
        check(find(data, ChargeChartData.COUNTDOWN).min == 0 && find(data, ChargeChartData.COUNTDOWN).max >= 1, "Tiny positive countdown still has minimum one-minute range");

        StringBuilder all = new StringBuilder();
        for (int i = 0; i < ChargeSessionStore.MAX_SAMPLES; i++) all.append(row(i * 9000L, 50, i == 1234 ? -3 : -2, 20d, i == 0,
                metadata("exact-v1", i + .123456789, null, null)));
        ChargeSessionStore.HistorySession dense = session("charge-v2", all.toString(), "STOPPED");
        data = ChargeChartData.build(dense, false); countdown = find(data, ChargeChartData.COUNTDOWN); power = find(data, ChargeChartData.POWER);
        for (RideChartData.Series series : data) check(series.values.size() == ChargeSessionStore.MAX_SAMPLES, "All samples retained in " + series.key);
        for (int i = 0; i < ChargeSessionStore.MAX_SAMPLES; i++) near(i + .123456789, countdown.sampleAt(i * 9000L).value, "Exact inspection sample " + i);
        near(-156, power.sampleAt(1234 * 9000L).value, "Brief electrical spike remains inspectable");
        int[] drawing = power.drawIndices(0, power.endTime, 200);
        check(Arrays.stream(drawing).anyMatch(index -> index == 1234 || index == -1235), "Pixel simplification preserves short power extremum");
        check(drawing.length < 850 && power.values.size() == 6500, "Drawing stays bounded without truncating inspectable data");
        Thread.currentThread().interrupt(); boolean canceled = false;
        try { ChargeChartData.build(dense, false); } catch (java.util.concurrent.CancellationException expected) { canceled = true; } finally { Thread.interrupted(); }
        check(canceled, "Charge adapter honors canceled background loading");
        System.out.println("ChargeChartDataTest: " + checks + " assertions passed");
    }
}
