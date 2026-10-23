package com.jjricks.volt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Recorded charge values adapted to the shared ride chart, without running an estimator. */
public final class ChargeChartData {
    private ChargeChartData() { }

    public static final String POWER = "power_w", BATTERY = "battery_percent",
            FINISH = "estimated_finish", COUNTDOWN = "estimated_countdown",
            VOLTAGE = "voltage_v", CURRENT = "current_a", TEMPERATURE = "temperature_c",
            LIMIT_FINISH = "limit_finish", LIMIT_COUNTDOWN = "limit_countdown",
            LIMIT = "charge_limit", OBSERVED_FINISH = "observed_finish", OBSERVED_COUNTDOWN = "observed_countdown";
    private static final String[] KEYS = {POWER, BATTERY, FINISH, COUNTDOWN, VOLTAGE, CURRENT,
            TEMPERATURE, LIMIT_FINISH, LIMIT_COUNTDOWN, LIMIT, OBSERVED_FINISH, OBSERVED_COUNTDOWN};
    private static final String[] LABELS = {"Power", "Battery %", "Est. finish time", "Est. countdown time",
            "Voltage", "Current", "Temperature", "Est. finish at limit", "Est. countdown to limit",
            "Charge limit", "Observed finish", "Actual countdown"};
    private static final int[] COLORS = {0xffe69f00, 0xfff0e442, 0xff009e73, 0xff56b4e9,
            0xffcc79a7, 0xffd55e00, 0xff999999, 0xff87c89e, 0xff9582d0, 0xffb99c39,
            0xff0072b2, 0xffe5a6be};

    public static Set<String> defaults() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(POWER, BATTERY, FINISH, COUNTDOWN, OBSERVED_FINISH)));
    }

    /**
     * Sample times are elapsed milliseconds. "Time" values are epoch milliseconds on the
     * recording's stable wall-clock anchor. The first seven series always exist, including
     * unavailable saved estimates in old recordings. Cutoff comparisons exist only when a
     * lower target was recorded; observed comparisons require a witnessed 100% endpoint.
     */
    public static List<RideChartData.Series> build(ChargeSessionStore.HistorySession session, boolean fahrenheit) {
        if (session == null) return Collections.emptyList();
        List<ChargeEstimateHistory.Point> full = ChargeEstimateHistory.build(session, false);
        List<ChargeEstimateHistory.Point> limit = ChargeEstimateHistory.build(session, true);
        List<List<RideChartData.Value>> values = new ArrayList<>();
        for (int i = 0; i < KEYS.length; i++) values.add(new ArrayList<>());
        long previousMono = 0; int position = 0, physicalSegment = 0, fullSegment = 0, limitSegment = 0, targetSegment = 0;
        Double previousTarget = null; boolean lowerTarget = false;
        for (ChargeSessionStore.HistoryPoint sample : session.points) {
            checkCanceled();
            // Match the estimator-history clock filtering so all categories share a timeline.
            if (sample.mono < session.startedMono || sample.mono - session.startedMono > ChargeSessionStore.MAX_SESSION_MS
                    || sample.mono <= previousMono) continue;
            ChargeEstimateHistory.Point prediction = full.get(position), cutoff = limit.get(position++);
            long elapsed = sample.mono - session.startedMono;
            Double target = sample.estimate == null ? null : sample.estimate.targetPercent;
            if (prediction.actualGap) physicalSegment++;
            if (prediction.gap) fullSegment++;
            if (cutoff.gap) limitSegment++;
            if (prediction.actualGap || !Objects.equals(target, previousTarget)) targetSegment++;
            lowerTarget |= target != null && target < 100;

            add(values, 0, elapsed, sample.power, physicalSegment);
            add(values, 1, elapsed, sample.batteryPercent, physicalSegment);
            add(values, 2, elapsed, number(prediction.predictedFinishWallMs), fullSegment);
            add(values, 3, elapsed, prediction.remainingMinutes, fullSegment);
            add(values, 4, elapsed, sample.voltage, physicalSegment);
            add(values, 5, elapsed, sample.current, physicalSegment);
            add(values, 6, elapsed, sample.temperature == null ? null
                    : DisplayTemperature.fromCelsius(sample.temperature, fahrenheit ? "f" : "c"), physicalSegment);
            add(values, 7, elapsed, number(cutoff.predictedFinishWallMs), limitSegment);
            add(values, 8, elapsed, cutoff.remainingMinutes, limitSegment);
            add(values, 9, elapsed, target, targetSegment);
            add(values, 10, elapsed, number(prediction.actualFinishWallMs), physicalSegment);
            add(values, 11, elapsed, prediction.actualRemainingMinutes, physicalSegment);
            previousMono = sample.mono; previousTarget = target;
        }

        // Estimated and observed versions of one quantity must share a scale to be comparable.
        ChartScale.Range finishScale = range(FINISH, "Time", values.get(2), values.get(10));
        ChartScale.Range countdownScale = range(COUNTDOWN, "min", values.get(3), values.get(11));
        List<RideChartData.Series> result = new ArrayList<>();
        for (int i = 0; i < KEYS.length; i++) {
            checkCanceled();
            if (i >= 7 && i <= 9 && !lowerTarget) continue;
            if (i >= 10 && session.observedCompleteMono == null) continue;
            String unit = i == 0 ? "W" : i == 1 || i == 9 ? "%" : i == 2 || i == 7 || i == 10 ? "Time"
                    : i == 3 || i == 8 || i == 11 ? "min" : i == 4 ? "V" : i == 5 ? "A" : fahrenheit ? "°F" : "°C";
            ChartScale.Range scale = i == 2 || i == 10 ? finishScale : i == 3 || i == 11 ? countdownScale
                    : range(KEYS[i], unit, values.get(i), Collections.emptyList());
            result.add(new RideChartData.Series(KEYS[i], LABELS[i], unit, COLORS[i], i, values.get(i), scale));
        }
        return Collections.unmodifiableList(result);
    }

    private static void add(List<List<RideChartData.Value>> values, int index, long time, Double value, int segment) {
        values.get(index).add(new RideChartData.Value(time, value != null && Double.isFinite(value) ? value : null, segment));
    }
    private static Double number(Long value) { return value == null ? null : value.doubleValue(); }
    private static ChartScale.Range range(String key, String unit, List<RideChartData.Value> first, List<RideChartData.Value> second) {
        double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
        for (List<RideChartData.Value> list : Arrays.asList(first, second)) for (RideChartData.Value value : list) {
            checkCanceled();
            if (value.value != null && Double.isFinite(value.value)) { low = Math.min(low, value.value); high = Math.max(high, value.value); }
        }
        if (!Double.isFinite(low)) { low = 0; high = "Time".equals(unit) ? 60000 : 1; }
        if ("Time".equals(unit)) {
            // Constant finish times still need useful clock labels rather than millisecond ticks.
            double center = low / 2 + high / 2;
            double half = Math.max(30000, high / 2 - low / 2);
            return ChartScale.range(key, unit, false, center - half, center + half);
        }
        if ("min".equals(unit)) return ChartScale.range(key, unit, true, 0, Math.max(1, high));
        return ChartScale.range(key, unit, "%".equals(unit), low, high);
    }
    private static void checkCanceled() {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Charge chart loading canceled");
    }
}
