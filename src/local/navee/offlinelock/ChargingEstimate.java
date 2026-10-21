package local.navee.offlinelock;

/** Fresh battery-side charging measurements, model defaults and optional personal charge curves. */
public final class ChargingEstimate {
    /** Bump whenever the prediction algorithm/reference changes; independent of app versions. */
    public static final String ESTIMATOR_VERSION = "ut5-stock-voltage-v4";
    public static final double MINIMUM_ETA_CURRENT_A = 0.05;
    /** Short UI label when measured current is too low for a time estimate. */
    public static final String LOW_CURRENT = "Low current";

    private ChargingEstimate() {}

    /** Preserve exactly the result used at capture time, including unavailable predictions. */
    public static ChargeSessionStore.Estimate recordingSnapshot(Result result) {
        if(result==null)return new ChargeSessionStore.Estimate(ESTIMATOR_VERSION,null,null,null,null,null,0);
        return new ChargeSessionStore.Estimate(ESTIMATOR_VERSION,result.remainingMinutesToFull,result.remainingMinutesToLimit,result.targetPercent,
                result.learnedToFull?ChargeSessionStore.Source.PERSONAL:result.referenceToFull?referenceSource(result):ChargeSessionStore.Source.CURRENT_RATE,
                result.learnedToLimit?ChargeSessionStore.Source.PERSONAL:result.referenceToLimit?referenceSource(result):ChargeSessionStore.Source.CURRENT_RATE,
                result.learnedSessions);
    }

    private static ChargeSessionStore.Source referenceSource(Result result) {
        return result.referenceCharger==ChargeSessionStore.Charger.HIGH_SPEED
                ?ChargeSessionStore.Source.FAST_REFERENCE:ChargeSessionStore.Source.STOCK_REFERENCE;
    }

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
        /** Full diagnostic wording for tooltips and accessibility; {@code label} is the short UI state. */
        public final String status, label;
        public final boolean learnedToLimit, learnedToFull;
        public final boolean referenceToLimit, referenceToFull;
        public final ChargeSessionStore.Charger referenceCharger;
        public final int learnedSessions;
        /** A paired-voltage stock reference takes priority over SOC-only personal bulk timing. */
        public final boolean stockVoltageToFull;
        private final Ut5MaxStockVoltageReference.Match stockVoltageMatch;

        private Result(
                boolean charging,
                Double inputWatts,
                Double inputAmps,
                Double remainingMinutesToLimit,
                Double remainingMinutesToFull,
                Double targetPercent,
                String label,
                String status) {
            this(charging,inputWatts,inputAmps,remainingMinutesToLimit,remainingMinutesToFull,targetPercent,label,status,false,false,0);
        }

        private Result(boolean charging,Double inputWatts,Double inputAmps,Double remainingMinutesToLimit,Double remainingMinutesToFull,
                Double targetPercent,String label,String status,boolean learnedToLimit,boolean learnedToFull,int learnedSessions) {
            this(charging,inputWatts,inputAmps,remainingMinutesToLimit,remainingMinutesToFull,targetPercent,label,status,
                    learnedToLimit,learnedToFull,learnedSessions,false,false);
        }

        private Result(boolean charging,Double inputWatts,Double inputAmps,Double remainingMinutesToLimit,Double remainingMinutesToFull,
                Double targetPercent,String label,String status,boolean learnedToLimit,boolean learnedToFull,int learnedSessions,
                boolean referenceToLimit,boolean referenceToFull) {
            this(charging,inputWatts,inputAmps,remainingMinutesToLimit,remainingMinutesToFull,targetPercent,label,status,
                    learnedToLimit,learnedToFull,learnedSessions,referenceToLimit,referenceToFull,
                    referenceToLimit||referenceToFull?ChargeSessionStore.Charger.STOCK:null);
        }

        private Result(boolean charging,Double inputWatts,Double inputAmps,Double remainingMinutesToLimit,Double remainingMinutesToFull,
                Double targetPercent,String label,String status,boolean learnedToLimit,boolean learnedToFull,int learnedSessions,
                boolean referenceToLimit,boolean referenceToFull,ChargeSessionStore.Charger referenceCharger) {
            this(charging,inputWatts,inputAmps,remainingMinutesToLimit,remainingMinutesToFull,targetPercent,label,status,
                    learnedToLimit,learnedToFull,learnedSessions,referenceToLimit,referenceToFull,referenceCharger,null);
        }

        private Result(boolean charging,Double inputWatts,Double inputAmps,Double remainingMinutesToLimit,Double remainingMinutesToFull,
                Double targetPercent,String label,String status,boolean learnedToLimit,boolean learnedToFull,int learnedSessions,
                boolean referenceToLimit,boolean referenceToFull,ChargeSessionStore.Charger referenceCharger,Ut5MaxStockVoltageReference.Match stockVoltageMatch) {
            this.stockVoltageMatch=stockVoltageMatch;
            this.stockVoltageToFull=stockVoltageMatch!=null;
            this.label = label;
            this.learnedToLimit=learnedToLimit;this.learnedToFull=learnedToFull;this.learnedSessions=learnedSessions;
            this.referenceToLimit=referenceToLimit;this.referenceToFull=referenceToFull;
            this.referenceCharger=referenceToLimit||referenceToFull?referenceCharger:null;
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
            return result(false, null, null, null, null, target, "Waiting for data", "Waiting for a fresh charging state.");
        if (chargingState.number != 1d)
            return result(false, null, null, null, null, target, "Not charging", "Not charging.");
        if (!valid(voltageV)
                || voltageV.number <= 0
                || !valid(currentA)
                || voltageV.observedMs <= 0
                || voltageV.observedMs != currentA.observedMs)
            return result(true, null, null, null, null, target, "Waiting for readings", "Waiting for paired voltage and current readings.");

        double amps = Math.abs(currentA.number);
        double watts = voltageV.number * amps;
        if (!Double.isFinite(watts))
            return result(true, null, null, null, null, target, "Readings out of range", "Charging measurements are outside a usable range.");
        if (amps < MINIMUM_ETA_CURRENT_A)
            return result(true, watts, amps, null, null, target, LOW_CURRENT, "Charging current is too low for a useful time estimate.");
        if (!valid(ratedAh) || ratedAh.number <= 0)
            return result(true, watts, amps, null, null, target, "Capacity unknown", "A fresh rated battery capacity is needed for a time estimate.");
        if (!valid(batteryPercent) || batteryPercent.number < 0 || batteryPercent.number > 100)
            return result(true, watts, amps, null, null, target, "Waiting for battery %", "Waiting for a fresh battery percentage.");

        Double full = minutes(ratedAh.number, 100 - batteryPercent.number, amps);
        Double limit = target == null ? null : minutes(ratedAh.number, target - batteryPercent.number, amps);
        if (full == null || (target != null && limit == null))
            return result(true, watts, amps, null, null, target, "No estimate", "Charging time is outside a usable range.");
        String status = "At the current rate; taper and balancing can lengthen charging.";
        if (target == null)
            status += " Charge cutoff unknown; the 100% time assumes charging continues.";
        else if (target < 100)
            status += " The 100% time assumes a higher charge limit.";
        return result(true, watts, amps, limit, full, target, "Current rate", status);
    }

    /**
     * Compose the bundled model and personal evidence independently from the original live-rate
     * baseline, then choose each target's source. An unavailable personal curve must not erase a
     * valid default or describe that default as a personal/current-rate estimate.
     */
    public static Result forScooter(Result baseline, Value soc, Value ratedAh, int productId,
            ChargeSessionStore.Charger selectedCharger, ChargeSessionStore.Curve personal) {
        return forScooter(baseline,soc,ratedAh,productId,selectedCharger,personal,null,null,0,0);
    }

    /** The caller supplies a manual or confidently detected family; null remains unknown. */
    public static Result forScooter(Result baseline, Value soc, Value ratedAh, int productId,
            ChargeSessionStore.Charger selectedCharger, ChargeSessionStore.Curve personal,
            ChargeReferenceGate gate, String scope, long frameMs, long nowMs) {
        if(selectedCharger==null){if(gate!=null)gate.reset();return baseline;}
        Result prior=selectedCharger==ChargeSessionStore.Charger.HIGH_SPEED
                ?withFastReference(baseline,soc,ratedAh,productId,gate,scope,frameMs,nowMs)
                :withStockReference(baseline,soc,ratedAh,productId,selectedCharger,gate,scope,frameMs,nowMs);
        if (personal==null || personal.charger!=selectedCharger) return prior;
        Result learned=withObservedCurve(baseline,soc,personal);
        if (learned==null || !learned.learnedToFull&&!learned.learnedToLimit) return prior;
        if (prior.stockVoltageToFull) {
            // The percentage gauge can shift between charges. Do not let an old SOC-only
            // personal finish replace a matched voltage estimate. Lower cutoffs still use
            // their existing evidence, bounded by the time to full; 89%+ is unchanged.
            double full=learned.learnedToFull?prior.stockVoltageMatch.blend(learned.remainingMinutesToFull)
                    :prior.remainingMinutesToFull;
            boolean personalLimit=prior.targetPercent!=null&&prior.targetPercent<100&&learned.learnedToLimit;
            // Recompute the pre-cap stock/rate cutoff: composition can select a different
            // full-time fallback, so an earlier cap against bundled full must not survive.
            Double stockLimit=prior.targetPercent==null?null:Ut5MaxStockChargeReference.estimate(
                    soc.number,prior.targetPercent,ratedAh.number,baseline.inputAmps,baseline.inputWatts,gate!=null);
            Double limit=personalLimit?learned.remainingMinutesToLimit
                    :stockLimit==null?baseline.remainingMinutesToLimit:stockLimit;
            if(prior.targetPercent!=null&&prior.targetPercent==100)limit=full;
            else if(limit!=null)limit=Math.min(limit,full);
            String status=prior.status+(personalLimit?" Time to the limit uses personal history, bounded by time to full.":"");
            if(learned.learnedToFull&&prior.stockVoltageMatch.weight<1)status+=" Near the reference boundary, full time blends with personal history.";
            return new Result(prior.charging,prior.inputWatts,prior.inputAmps,limit,full,
                    prior.targetPercent,prior.label,status,personalLimit,false,personalLimit?learned.learnedSessions:0,
                    !personalLimit&&prior.referenceToLimit,true,ChargeSessionStore.Charger.STOCK,prior.stockVoltageMatch);
        }
        boolean referenceFull=!learned.learnedToFull&&prior.referenceToFull;
        boolean referenceLimit=!learned.learnedToLimit&&prior.referenceToLimit;
        String status=learned.learnedSessions==1?"Provisional personal estimate from 1 completed charge. "
                :"Personal estimate from "+learned.learnedSessions+" qualified charges. ";
        if(referenceFull||referenceLimit)status+="Experimental UT5 MAX charger reference covers the other target. ";
        if(!learned.learnedToFull&&!referenceFull)status+="Time to 100% uses the current rate. ";
        if(prior.targetPercent!=null&&!learned.learnedToLimit&&!referenceLimit)status+="Time to the limit uses the current rate. ";
        status+="Time to displayed 100%; balancing may continue.";
        status+=cutoffNote(prior.targetPercent);
        return new Result(prior.charging,prior.inputWatts,prior.inputAmps,
                learned.learnedToLimit?learned.remainingMinutesToLimit:prior.remainingMinutesToLimit,
                learned.learnedToFull?learned.remainingMinutesToFull:prior.remainingMinutesToFull,
                prior.targetPercent,"From "+learned.learnedSessions+" past charge"+(learned.learnedSessions==1?"":"s"),status,learned.learnedToLimit,learned.learnedToFull,learned.learnedSessions,
                referenceLimit,referenceFull,prior.referenceCharger);
    }

    private static Result withFastReference(Result baseline,Value soc,Value ratedAh,int productId,
            ChargeReferenceGate gate,String scope,long frameMs,long nowMs) {
        if(baseline==null||!baseline.charging||baseline.inputWatts==null||baseline.inputAmps==null
                ||baseline.remainingMinutesToFull==null||productId!=Ut5MaxFastChargeReference.PRODUCT_ID
                ||!valid(soc)||!valid(ratedAh)){if(gate!=null)gate.reset();return baseline;}
        ChargeSessionStore.Charger fast=ChargeSessionStore.Charger.HIGH_SPEED;
        String context=scope==null||scope.isEmpty()?null:scope+":"+productId+":"+fast;
        if(gate!=null&&!gate.accepts(context,fast,soc.number,baseline.inputWatts,frameMs,nowMs))return baseline;
        Double full=Ut5MaxFastChargeReference.estimate(soc.number,100,ratedAh.number,baseline.inputAmps,baseline.inputWatts,gate!=null);
        Double limit=baseline.targetPercent==null?null:Ut5MaxFastChargeReference.estimate(
                soc.number,baseline.targetPercent,ratedAh.number,baseline.inputAmps,baseline.inputWatts,gate!=null);
        if(full==null&&limit==null)return baseline;
        String status="Experimental UT5 MAX high-speed reference from one observed charge. ";
        if(soc.number<72)status+="Bulk time uses rated Ah and live current, followed by the observed taper. ";
        if(full==null)status+="Time to 100% uses the current rate. ";
        if(limit==null&&baseline.targetPercent!=null)status+="Time to the limit uses the current rate. ";
        status+="Time to displayed 100%; balancing may continue."+cutoffNote(baseline.targetPercent);
        return new Result(baseline.charging,baseline.inputWatts,baseline.inputAmps,
                limit==null?baseline.remainingMinutesToLimit:limit,full==null?baseline.remainingMinutesToFull:full,
                baseline.targetPercent,"High-speed reference",status,false,false,0,limit!=null,full!=null,ChargeSessionStore.Charger.HIGH_SPEED);
    }

    private static Result withStockReference(Result baseline, Value soc, Value ratedAh, int productId,
            ChargeSessionStore.Charger selectedCharger, ChargeReferenceGate gate, String scope, long frameMs, long nowMs) {
        if (baseline==null || !baseline.charging || baseline.inputWatts==null || baseline.inputAmps==null
                || baseline.remainingMinutesToFull==null || productId!=Ut5MaxStockChargeReference.PRODUCT_ID
                || !valid(soc) || !valid(ratedAh)) {
            if(gate!=null)gate.reset(); return baseline;
        }
        String context=scope==null||scope.isEmpty()?null:scope+":"+productId+":"+selectedCharger;
        if(gate!=null&&!gate.accepts(context,soc.number,baseline.inputWatts,frameMs,nowMs))return baseline;
        Double full=Ut5MaxStockChargeReference.estimate(soc.number,100,ratedAh.number,baseline.inputAmps,baseline.inputWatts,gate!=null);
        Double limit=baseline.targetPercent==null?null:Ut5MaxStockChargeReference.estimate(
                soc.number,baseline.targetPercent,ratedAh.number,baseline.inputAmps,baseline.inputWatts,gate!=null);
        if(full==null&&limit==null)return baseline;
        Ut5MaxStockVoltageReference.Match voltageMatch=full==null?null:Ut5MaxStockVoltageReference.match(
                soc.number,ratedAh.number,baseline.inputAmps,baseline.inputWatts);
        if(voltageMatch!=null) {
            full=voltageMatch.blend(full);
            if(baseline.targetPercent!=null&&baseline.targetPercent==100)limit=full;
        }
        String status="Experimental UT5 MAX stock-charger estimate based on observed charges; live power is compatible. ";
        if(voltageMatch!=null)status+="Bulk progress matches pack voltage and charging current. ";
        else if(soc.number<89)status+="Bulk time uses rated Ah and current charging rate. ";
        if(limit==null&&baseline.targetPercent!=null)status+="Time to the limit uses the current rate. ";
        status+="This does not identify the charger. Time to displayed 100%; balancing may continue.";
        status+=cutoffNote(baseline.targetPercent);
        Double finalLimit=limit==null?baseline.remainingMinutesToLimit:limit;
        if(voltageMatch!=null&&finalLimit!=null)finalLimit=Math.min(finalLimit,full);
        return new Result(baseline.charging,baseline.inputWatts,baseline.inputAmps,
                finalLimit,full==null?baseline.remainingMinutesToFull:full,
                baseline.targetPercent,"Stock reference",status,false,false,0,limit!=null,full!=null,
                ChargeSessionStore.Charger.STOCK,voltageMatch);
    }

    private static String cutoffNote(Double target) {
        return target==null?" Cutoff unknown; 100% assumes charging continues."
                :target<100?" The 100% time assumes a higher charge limit.":"";
    }

    /** Learned curves may replace a usable current-rate estimate, never bypass its fresh paired-data gate. */
    public static Result withObservedCurve(Result baseline,Value soc,ChargeSessionStore.Curve curve) {
        if(baseline==null||!baseline.charging||baseline.inputWatts==null||baseline.remainingMinutesToFull==null
                ||!valid(soc)||!Double.isFinite(soc.number)||soc.number<0||soc.number>100||curve==null)return baseline;
        // Prefer the finishing trajectory even after a second recording becomes available:
        // a SOC-only average would otherwise freeze the ETA throughout a long 99% plateau.
        Double volts=baseline.inputAmps==null||baseline.inputAmps<=0?null:baseline.inputWatts/baseline.inputAmps;
        ChargeSessionStore.Duration finishing=curve.estimateFinishing(soc.number,baseline.inputWatts,volts);
        ChargeSessionStore.Curve.PowerMatch power=curve.powerMatch(soc.number,baseline.inputWatts);
        if(finishing!=null){
            ChargeSessionStore.Duration limit=baseline.targetPercent==null?null:baseline.targetPercent==100
                    ?finishing:power==ChargeSessionStore.Curve.PowerMatch.MATCH?curve.estimate(soc.number,baseline.targetPercent):null;
            String charger=curve.charger==ChargeSessionStore.Charger.STOCK?"stock":"high-speed";
            String status=(finishing.sessions==1?"Provisional finish estimate from 1 completed ":"Approximate finish estimate from "+finishing.sessions+" completed ")
                    +charger+" charge"+(finishing.sessions==1?"":"s")
                    +"; matched battery level and charging power. Time to displayed 100%; balancing may continue.";
            if(baseline.targetPercent==null)status+=" Cutoff unknown; 100% assumes charging continues.";
            else if(baseline.targetPercent<100)status+=" The 100% time assumes a higher charge limit.";
            if(limit==null&&baseline.targetPercent!=null)status+=" Time to the limit uses the current-rate estimate.";
            return new Result(baseline.charging,baseline.inputWatts,baseline.inputAmps,
                    limit==null?baseline.remainingMinutesToLimit:Double.valueOf(limit.minutes),finishing.minutes,baseline.targetPercent,
                    "From "+finishing.sessions+" past charge"+(finishing.sessions==1?"":"s"),status,limit!=null,true,finishing.sessions);
        }
        if(power!=ChargeSessionStore.Curve.PowerMatch.MATCH){
            String reason=power==ChargeSessionStore.Curve.PowerMatch.DIFFERENT
                    ?"Live power differs from the selected charger's saved charges at this battery level. "
                    :"Saved charges lack consistent power at this battery level. ";
            return new Result(baseline.charging,baseline.inputWatts,baseline.inputAmps,
                    baseline.remainingMinutesToLimit,baseline.remainingMinutesToFull,baseline.targetPercent,
                    "Charger mismatch · rate estimate",reason+baseline.status);
        }
        // If a complete trajectory cannot locate this stage safely, its older SOC-only bins
        // must not silently resurrect the same frozen countdown we rejected above.
        boolean unresolvedFinish=curve.hasFinishingCoverage(soc.number);
        ChargeSessionStore.Duration full=unresolvedFinish?null:curve.estimate(soc.number,100);
        ChargeSessionStore.Duration limit=baseline.targetPercent==null||unresolvedFinish&&baseline.targetPercent==100
                ?null:curve.estimate(soc.number,baseline.targetPercent);
        if(full==null&&limit==null)return new Result(baseline.charging,baseline.inputWatts,baseline.inputAmps,
                baseline.remainingMinutesToLimit,baseline.remainingMinutesToFull,baseline.targetPercent,
                "Rate-based estimate","Current-rate estimate; not enough matching charge curves cover the remaining range. Taper can lengthen charging. "
                        +(baseline.targetPercent==null?"Cutoff unknown; 100% assumes charging continues.":baseline.targetPercent<100?"100% assumes a higher cutoff.":""));
        int sessions=full==null?limit.sessions:limit==null?full.sessions:Math.min(full.sessions,limit.sessions);
        String charger=curve.charger==ChargeSessionStore.Charger.STOCK?"stock":"high-speed";
        String status="Observed average from "+sessions+" "+charger+" charges; live power is compatible at this battery level.";
        if(full==null)status+=" Full-charge taper is not covered; 100% uses the current-rate estimate.";
        if(limit==null&&baseline.targetPercent!=null)status+=" Time to the limit uses the current-rate estimate.";
        if(baseline.targetPercent==null)status+=" Cutoff unknown; 100% assumes charging continues.";
        else if(baseline.targetPercent<100)status+=" The 100% time assumes a higher charge limit.";
        return new Result(baseline.charging,baseline.inputWatts,baseline.inputAmps,
                limit==null?baseline.remainingMinutesToLimit:Double.valueOf(limit.minutes),full==null?baseline.remainingMinutesToFull:full.minutes,
                baseline.targetPercent,"From "+sessions+" past charge"+(sessions==1?"":"s"),status,limit!=null,full!=null,sessions);
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
            String label,
            String status) {
        return new Result(charging, watts, amps, limit, full, target, label, status);
    }
}
