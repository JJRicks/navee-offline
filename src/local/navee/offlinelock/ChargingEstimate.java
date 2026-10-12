package local.navee.offlinelock;

/** Battery-side charging measurements and a constant-current time estimate, without Android APIs. */
public final class ChargingEstimate {
    public static final double MINIMUM_ETA_CURRENT_A = 0.05;

    private ChargingEstimate() {}

    /**
     * A reading whose freshness has already been checked by its caller. Voltage and current must
     * share the same positive source-frame timestamp; adjacent frames are not interchangeable.
     * observedMs may use any consistent clock and is never interpreted as a duration or date here.
     */
    public static final class Value {
        public final Double number;
        public final boolean fresh;
        public final long observedMs;

        public Value(Double number, boolean fresh, long observedMs) {
            this.number = number;
            this.fresh = fresh;
            this.observedMs = observedMs;
        }
    }

    public static final class Result {
        public final boolean charging;
        public final Double inputWatts, inputAmps;
        public final Double remainingMinutesToLimit, remainingMinutesToFull, targetPercent;
        public final String status;

        private Result(
                boolean charging,
                Double inputWatts,
                Double inputAmps,
                Double remainingMinutesToLimit,
                Double remainingMinutesToFull,
                Double targetPercent,
                String status) {
            this.charging = charging;
            this.inputWatts = inputWatts;
            this.inputAmps = inputAmps;
            this.remainingMinutesToLimit = remainingMinutesToLimit;
            this.remainingMinutesToFull = remainingMinutesToFull;
            this.targetPercent = targetPercent;
            this.status = status;
        }
    }

    /**
     * Reports |V × I| and |I| only with a fresh active-charging state and paired electrical values.
     * Polarity is deliberately not inferred: the scooter's current sign convention is unverified.
     * These are battery-side measurements, not charger AC input or wall power.
     *
     * Time estimates assume the current remains constant: rated Ah × missing SOC / |current|.
     * Charging taper, balancing, temperature and SOC error can increase actual time. The estimate
     * to 100% is hypothetical if a lower cutoff is configured or the cutoff is unknown.
     * A charge limit is never guessed from the model or silently replaced with 100%.
     */
    public static Result calculate(
            Value chargingState,
            Value voltageV,
            Value currentA,
            Value batteryPercent,
            Value ratedAh,
            Value chargeLimitPercent) {
        Double target =
                valid(chargeLimitPercent)
                                && chargeLimitPercent.number > 0
                                && chargeLimitPercent.number <= 100
                        ? chargeLimitPercent.number
                        : null;
        if (!valid(chargingState))
            return result(false, null, null, null, null, target, "Waiting for a fresh charging state.");
        if (chargingState.number != 1d)
            return result(false, null, null, null, null, target, "Not charging.");
        if (!valid(voltageV)
                || voltageV.number <= 0
                || !valid(currentA)
                || voltageV.observedMs <= 0
                || voltageV.observedMs != currentA.observedMs)
            return result(true, null, null, null, null, target, "Waiting for paired voltage and current readings.");

        double amps = Math.abs(currentA.number);
        double watts = voltageV.number * amps;
        if (!Double.isFinite(watts))
            return result(true, null, null, null, null, target, "Charging measurements are outside a usable range.");
        if (amps < MINIMUM_ETA_CURRENT_A)
            return result(true, watts, amps, null, null, target, "Charging current is too low for a useful time estimate.");
        if (!valid(ratedAh) || ratedAh.number <= 0)
            return result(true, watts, amps, null, null, target, "A fresh rated battery capacity is needed for a time estimate.");
        if (!valid(batteryPercent) || batteryPercent.number < 0 || batteryPercent.number > 100)
            return result(true, watts, amps, null, null, target, "Waiting for a fresh battery percentage.");

        Double full = minutes(ratedAh.number, 100 - batteryPercent.number, amps);
        Double limit = target == null ? null : minutes(ratedAh.number, target - batteryPercent.number, amps);
        if (full == null || (target != null && limit == null))
            return result(true, watts, amps, null, null, target, "Charging time is outside a usable range.");
        String status = "At the current rate; taper and balancing can lengthen charging.";
        if (target == null)
            status += " Charge cutoff unknown; the 100% time assumes charging continues.";
        else if (target < 100)
            status += " The 100% time assumes a higher charge limit.";
        return result(true, watts, amps, limit, full, target, status);
    }

    private static boolean valid(Value value) {
        return value != null && value.fresh && value.number != null && Double.isFinite(value.number);
    }

    private static Double minutes(double ratedAh, double missingPercent, double amps) {
        double result = ratedAh * (Math.max(0, missingPercent) / 100d) / amps * 60d;
        return Double.isFinite(result) && result >= 0 ? result : null;
    }

    private static Result result(
            boolean charging,
            Double watts,
            Double amps,
            Double limit,
            Double full,
            Double target,
            String status) {
        return new Result(charging, watts, amps, limit, full, target, status);
    }
}
