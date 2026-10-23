package local.navee.offlinelock;

/** Fresh battery-side charging measurements, model defaults and optional personal charge curves. */
public final class ChargingEstimate {
    /** Bump whenever the prediction algorithm/reference changes; independent of app versions. */
    public static final String ESTIMATOR_VERSION = "ut5-stock-pack-v6";
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
        /**
         * Cautious minutes for Charge done by. Equal to the displayed estimate except where a
         * model quantifies its uncertainty; never shorter than the displayed estimate.
         */
        public final Double planningMinutesToLimit, planningMinutesToFull;
        /** The standard-charger pack-voltage model supplied the bulk-stage estimate. */
        public final boolean stockPackModel;
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
            this(charging,inputWatts,inputAmps,remainingMinutesToLimit,remainingMinutesToFull,targetPercent,label,status,
                    learnedToLimit,learnedToFull,learnedSessions,referenceToLimit,referenceToFull,referenceCharger,stockVoltageMatch,
                    remainingMinutesToLimit,remainingMinutesToFull,false);
        }

        private Result(boolean charging,Double inputWatts,Double inputAmps,Double remainingMinutesToLimit,Double remainingMinutesToFull,
                Double targetPercent,String label,String status,boolean learnedToLimit,boolean learnedToFull,int learnedSessions,
                boolean referenceToLimit,boolean referenceToFull,ChargeSessionStore.Charger referenceCharger,Ut5MaxStockVoltageReference.Match stockVoltageMatch,
                Double planningMinutesToLimit,Double planningMinutesToFull,boolean stockPackModel) {
            this.planningMinutesToLimit=planningMinutesToLimit;this.planningMinutesToFull=planningMinutesToFull;
            this.stockPackModel=stockPackModel;
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
        return forScooter(baseline,soc,ratedAh,productId,selectedCharger,personal,gate,scope,frameMs,nowMs,null);
    }

    /**
     * progress describes this charge's age and starting percentage; null means its start was
     * not seen, which the standard-charger model treats as a charge that has just begun.
     */
    public static Result forScooter(Result baseline, Value soc, Value ratedAh, int productId,
            ChargeSessionStore.Charger selectedCharger, ChargeSessionStore.Curve personal,
            ChargeReferenceGate gate, String scope, long frameMs, long nowMs, ChargeProgressTracker.Progress progress) {
        if(selectedCharger==null){if(gate!=null)gate.reset();return baseline;}
        Result prior=selectedCharger==ChargeSessionStore.Charger.HIGH_SPEED
                ?withFastReference(baseline,soc,ratedAh,productId,gate,scope,frameMs,nowMs)
                :withStockReference(baseline,soc,ratedAh,productId,selectedCharger,gate,scope,frameMs,nowMs);
        // Below 89% the pack model replaces both the bundled bulk timing and percentage-only
        // personal history: the gauge's late catch-up varies between charges, pack voltage does not.
        Result pack=selectedCharger==ChargeSessionStore.Charger.STOCK?withStockPackModel(prior,baseline,soc,ratedAh,productId,progress):null;
        if (pack!=null) return pack;
        if (personal==null || personal.charger!=selectedCharger) return prior;
        Result learned=withObservedCurve(baseline,soc,personal);
        if (learned==null || !learned.learnedToFull&&!learned.learnedToLimit) return prior;
        double planningWeight=prior.referenceToFull
                &&selectedCharger==ChargeSessionStore.Charger.STOCK
                &&productId==Ut5MaxStockChargeReference.PRODUCT_ID
                &&valid(soc)&&valid(ratedAh)
                ?Ut5MaxStockChargeReference.bulkPlanningWeight(soc.number,ratedAh.number):0;
        if (prior.stockVoltageToFull || planningWeight>0) {
            // Outside a confident voltage match, shorter SOC-only history must not
            // erase the conservative first guess. At full match weight, measured
            // voltage progress still determines the estimate; 89%+ is untouched.
            Double stockFull=Ut5MaxStockChargeReference.estimate(soc.number,100,ratedAh.number,
                    baseline.inputAmps,baseline.inputWatts,gate!=null);
            double fallback=stockFull==null?prior.remainingMinutesToFull:stockFull;
            boolean guardedHistory=learned.learnedToFull&&learned.remainingMinutesToFull<fallback&&planningWeight>0;
            boolean historyContributes=learned.learnedToFull&&(!guardedHistory||planningWeight<1);
            if(learned.learnedToFull)fallback=guardedHistory
                    ?learned.remainingMinutesToFull+(fallback-learned.remainingMinutesToFull)*planningWeight
                    :learned.remainingMinutesToFull;
            double full=prior.stockVoltageMatch==null?fallback:prior.stockVoltageMatch.blend(fallback);
            boolean personalFull=learned.learnedToFull&&!guardedHistory&&prior.stockVoltageMatch==null;
            historyContributes=historyContributes&&(prior.stockVoltageMatch==null||prior.stockVoltageMatch.weight<1);
            // Lower cutoffs retain their own evidence. Full-charge observations do not
            // establish a new SOC-to-time curve for an 80% or 95% cutoff.
            Double stockLimit=prior.targetPercent==null?null:Ut5MaxStockChargeReference.estimate(
                    soc.number,prior.targetPercent,ratedAh.number,baseline.inputAmps,baseline.inputWatts,gate!=null);
            boolean personalLimit=prior.targetPercent!=null&&prior.targetPercent<100&&learned.learnedToLimit;
            Double limit=personalLimit?learned.remainingMinutesToLimit
                    :stockLimit==null?baseline.remainingMinutesToLimit:stockLimit;
            if(prior.targetPercent!=null&&prior.targetPercent==100)limit=full;
            else if(limit!=null)limit=Math.min(limit,full);
            boolean limitFromPersonal=prior.targetPercent!=null&&prior.targetPercent==100?personalFull:personalLimit;
            boolean referenceLimit=prior.targetPercent!=null&&!limitFromPersonal
                    &&(prior.targetPercent==100||prior.referenceToLimit||!same(limit,baseline.remainingMinutesToLimit));
            String status=personalLimit?prior.status.replace("Time to the limit uses the current rate. ","")
                    +" Time to the limit uses personal history, bounded by time to full.":prior.status;
            if(personalFull)status+=" Time to full uses longer personal history.";
            else if(historyContributes)status+=" Near the reference boundary, full time blends with personal history.";
            if(guardedHistory)status+=" Uncertain bulk timing limits how much shorter percentage-only history can reduce full time.";
            int sessions=personalFull||personalLimit||historyContributes?learned.learnedSessions:0;
            return new Result(prior.charging,prior.inputWatts,prior.inputAmps,limit,full,
                    prior.targetPercent,personalFull?learned.label:prior.label,status,
                    limitFromPersonal,personalFull,sessions,referenceLimit,!personalFull,
                    ChargeSessionStore.Charger.STOCK,prior.stockVoltageMatch);
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
        if(Ut5MaxStockChargeReference.bulkPlanningWeight(soc.number,ratedAh.number)>0)
            status+="Uncertain full-charge timing includes up to ten extra minutes. ";
        if(limit==null&&baseline.targetPercent!=null)status+="Time to the limit uses the current rate. ";
        status+="This does not identify the charger. Time to displayed 100%; balancing may continue.";
        status+=cutoffNote(baseline.targetPercent);
        Double finalLimit=limit==null?baseline.remainingMinutesToLimit:limit;
        if(baseline.targetPercent!=null&&baseline.targetPercent==100&&full!=null)finalLimit=full;
        else if(full!=null&&finalLimit!=null&&(voltageMatch!=null
                ||Ut5MaxStockChargeReference.bulkPlanningWeight(soc.number,ratedAh.number)>0))finalLimit=Math.min(finalLimit,full);
        boolean referenceLimit=limit!=null||!same(finalLimit,baseline.remainingMinutesToLimit);
        return new Result(baseline.charging,baseline.inputWatts,baseline.inputAmps,
                finalLimit,full==null?baseline.remainingMinutesToFull:full,
                baseline.targetPercent,"Stock reference",status,false,false,0,referenceLimit,full!=null,
                ChargeSessionStore.Charger.STOCK,voltageMatch);
    }

    /** Null unless the admitted standard-charger reference covers this bulk-stage reading. */
    private static Result withStockPackModel(Result prior, Result baseline, Value soc, Value ratedAh, int productId,
            ChargeProgressTracker.Progress progress) {
        if (prior==null || prior==baseline || !prior.referenceToFull || prior.referenceCharger!=ChargeSessionStore.Charger.STOCK
                || productId!=Ut5MaxStockPackModel.PRODUCT_ID || !valid(soc) || !valid(ratedAh)
                || soc.number>=Ut5MaxStockPackModel.LAST_SOC || baseline.inputAmps==null || baseline.inputWatts==null
                || baseline.inputAmps<=0) return null;
        double amps=baseline.inputAmps, volts=baseline.inputWatts/amps;
        double age=progress==null?0:progress.ageMinutes, start=progress==null?Double.NaN:progress.startSoc;
        Ut5MaxStockPackModel.Estimate full=Ut5MaxStockPackModel.estimate(soc.number,100,volts,amps,age,start,ratedAh.number);
        if (full==null) return null;
        Double target=baseline.targetPercent, limit=null, planningLimit=null;
        if (target!=null) {
            Ut5MaxStockPackModel.Estimate cutoff=Ut5MaxStockPackModel.estimate(soc.number,target,volts,amps,age,start,ratedAh.number);
            if (cutoff==null) return null;
            limit=Math.min(cutoff.display,full.display);
            planningLimit=Math.max(limit,Math.min(cutoff.planning,full.planning));
        }
        String status="Experimental UT5 MAX stock-charger estimate from pack voltage, charging current and observed finishes. ";
        if (progress==null || !progress.startObserved && !progress.ageInferred)
            status+="Charging start not seen, so it assumes charging just began until the current trend is known. ";
        status+="Charge done by uses a longer, cautious estimate. This does not identify the charger. "
                +"Time to displayed 100%; balancing may continue."+cutoffNote(target);
        return new Result(baseline.charging,baseline.inputWatts,baseline.inputAmps,limit,full.display,target,
                "Stock reference",status,false,false,0,target!=null,true,ChargeSessionStore.Charger.STOCK,null,
                planningLimit,full.planning,true);
    }

    private static boolean same(Double a,Double b){return a==null?b==null:a.equals(b);}

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
