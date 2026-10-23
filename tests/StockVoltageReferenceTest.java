package com.jjricks.volt;

/** Admission, continuity, target semantics, and provenance of voltage-aware stock progress. */
public final class StockVoltageReferenceTest {
    private static int checks;
    private static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    private static void near(double want,Double got,double tolerance,String why){check(got!=null&&Math.abs(want-got)<=tolerance,why+": "+got);}
    private static ChargingEstimate.Value value(Double x){return new ChargingEstimate.Value(x,true,1000);}
    private static ChargingEstimate.Result base(double soc,double volts,double amps,Double target){
        return ChargingEstimate.calculate(value(1d),value(volts),value(-amps),value(soc),value(15.3),value(target));
    }
    private static ChargingEstimate.Result estimate(double soc,double volts,double amps,Double target){
        return ChargingEstimate.forScooter(base(soc,volts,amps,target),value(soc),value(15.3),2538,ChargeSessionStore.Charger.STOCK,null);
    }
    /** A charge observed from its start minutesAgo, at startSoc (as ChargeProgressTracker reports it). */
    private static ChargingEstimate.Result estimate(double soc,double volts,double amps,Double target,double minutesAgo,double startSoc){
        return ChargingEstimate.forScooter(base(soc,volts,amps,target),value(soc),value(15.3),2538,ChargeSessionStore.Charger.STOCK,null,
                null,null,0,0,new ChargeProgressTracker.Progress(minutesAgo,startSoc,true,false));
    }
    public static void main(String[] args) throws Exception {
        // The recorded warm charge at 55%, 150 minutes after it started at 22%, had 175.87 min left.
        ChargingEstimate.Result warm=estimate(55,49.702,1.94,100d,150,22);
        check(Math.abs(warm.remainingMinutesToFull-175.8677)<=5&&warm.planningMinutesToFull>=175.8677
                &&warm.remainingMinutesToFull<base(55,49.702,1.94,100d).remainingMinutesToFull-15,
                "Pack voltage locates the warm charge within five minutes, a cautious plan is not late, and the raw rate improves by 15+ minutes");
        check(warm.stockPackModel&&warm.referenceToFull&&warm.referenceToLimit&&!warm.learnedToFull&&warm.learnedSessions==0,"Bundled pack evidence has honest provenance");
        near(warm.remainingMinutesToFull,warm.remainingMinutesToLimit,0,"Full and 100% cutoff agree");
        near(warm.remainingMinutesToFull,estimate(55,49.702,1.94,null,150,22).remainingMinutesToFull,0,"Unknown cutoff does not change hypothetical full");
        check(estimate(55,49.702,1.94,null,150,22).remainingMinutesToLimit==null,"Unknown cutoff stays unknown");
        double previous=0;
        for(double target:new double[]{40,55,60,70,80,85,88,89,90,95,98,99,100}){
            ChargingEstimate.Result r=estimate(55,49.702,1.94,target,150,22);
            check(r.remainingMinutesToLimit>=previous&&r.remainingMinutesToLimit<=r.remainingMinutesToFull,"Cutoffs are ordered and cannot exceed full: "+target);
            if(target<=55)near(0,r.remainingMinutesToLimit,0,"Reached cutoff stays zero");
            previous=r.remainingMinutesToLimit;
        }
        near(56.722,estimate(80,53.030,1.91,100d,240,22).remainingMinutesToFull,3,"Same voltage locates warm high SOC catch-up");
        near(73.66,estimate(76,52.5,1.94,100d,240,22).remainingMinutesToFull,3,"Warm percentage offset");
        check(Math.abs(estimate(83,52.5,1.94,100d,240,22).remainingMinutesToFull-estimate(76,52.5,1.94,100d,240,22).remainingMinutesToFull)<=10,
                "A different percentage at the same electrical stage stays within ten minutes");
        for(double soc:new double[]{89,90,95,98,99,100}){
            ChargingEstimate.Result r=estimate(soc,53.817,1.91,100d);
            check(!r.stockVoltageToFull&&!r.stockPackModel,"89% and later preserve existing finish and taper");
        }
        for(double[] point:new double[][]{{70,55,2},{55,49.702,.8},{55,49.702,5}})
            check(!estimate(point[0],point[1],point[2],100d).stockPackModel,"Contradictory voltage or out-of-range current retains fallback");
        for(double[] point:new double[][]{{40,47,2},{50,49,1.94}})
            check(estimate(point[0],point[1],point[2],100d).stockPackModel,"The pack model covers the whole recorded bulk voltage range");
        for(double invalid:new double[]{Double.NaN,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY,-1}){
            check(Ut5MaxStockVoltageReference.refine(invalid,15.3,1.94,99,200)==null,"Invalid SOC rejected");
            check(Ut5MaxStockVoltageReference.refine(55,invalid,1.94,99,200)==null,"Invalid capacity rejected");
            check(Ut5MaxStockVoltageReference.refine(55,15.3,invalid,99,200)==null,"Invalid amps rejected");
            check(Ut5MaxStockVoltageReference.refine(55,15.3,1.94,invalid,200)==null,"Invalid power rejected");
            check(Ut5MaxStockVoltageReference.refine(55,15.3,1.94,99,invalid)==null,"Invalid baseline rejected");
        }
        check(Ut5MaxStockVoltageReference.refine(55,10,1.94,99,200)==null,"Different capacity cannot reuse the observed pack curve");
        ChargingEstimate.Result b=base(55,49.702,1.94,100d);
        for(int model:new int[]{0,1,2537,2539})check(ChargingEstimate.forScooter(b,value(55d),value(15.3),model,ChargeSessionStore.Charger.STOCK,null)==b,"Other models unchanged");
        check(ChargingEstimate.forScooter(b,value(55d),value(15.3),2538,null,null)==b,"Unknown charger unchanged");
        check(!ChargingEstimate.forScooter(b,value(55d),value(15.3),2538,ChargeSessionStore.Charger.HIGH_SPEED,null).stockVoltageToFull,"Fast charger never uses stock voltage curve");
        ChargingEstimate.Result stale=ChargingEstimate.calculate(value(1d),value(49.702),new ChargingEstimate.Value(-1.94,false,1000),value(55d),value(15.3),value(100d));
        check(ChargingEstimate.forScooter(stale,value(55d),value(15.3),2538,ChargeSessionStore.Charger.STOCK,null)==stale,"Stale paired data cannot use voltage reference");
        ChargingEstimate.Result mismatch=ChargingEstimate.calculate(value(1d),value(49.702),new ChargingEstimate.Value(-1.94,true,1001),value(55d),value(15.3),value(100d));
        check(ChargingEstimate.forScooter(mismatch,value(55d),value(15.3),2538,ChargeSessionStore.Charger.STOCK,null)==mismatch,"Different electrical frames cannot use voltage reference");
        check(ChargingEstimate.forScooter(b,value(55d),value(15.3),2538,ChargeSessionStore.Charger.STOCK,null,new ChargeReferenceGate(),null,1000,1000)==b,"Runtime scope gate retained");
        // Dense voltage and current sweeps catch hard reference switches/entry cliffs.
        double last=Double.NaN;
        for(int i=0;i<=5000;i++){
            double v=49.35+i*.001;
            Double refined=Ut5MaxStockVoltageReference.refine(70,15.3,1.94,v*1.94,140);
            double now=refined==null?140:refined;
            if(Double.isFinite(last))check(Math.abs(now-last)<1,"No voltage boundary cliff at "+v);
            last=now;
        }
        last=Double.NaN;
        for(int i=0;i<=2000;i++){
            double amps=1.5+i*.0005;
            Double refined=Ut5MaxStockVoltageReference.refine(80,15.3,amps,53.03*amps,90);
            double now=refined==null?90:refined;
            if(Double.isFinite(last))check(Math.abs(now-last)<1,"No current admission/reference-switch cliff at "+amps);
            last=now;
        }
        // Full composition must blend against personal history, not only bundled bulk.
        ChargeSessionStore.Curve history=StockVoltageReplayTest.history(StockVoltageReplayTrace.WARM_PHYSICAL_CHARGE);
        for(double[] sweep:new double[][]{{80,1.94,53.75,53.85},{71,1.7,50.85,50.92}}){
            double prev=Double.NaN;
            for(double v=sweep[2];v<=sweep[3];v+=.0001){
                ChargingEstimate.Result r=ChargingEstimate.forScooter(base(sweep[0],v,sweep[1],100d),value(sweep[0]),value(15.3),2538,ChargeSessionStore.Charger.STOCK,history);
                if(Double.isFinite(prev))check(Math.abs(r.remainingMinutesToFull-prev)<.5,"Full personal composition has no voltage cliff: "+v);
                near(r.remainingMinutesToFull,r.remainingMinutesToLimit,0,"Personal-edge full cutoff agrees");
                prev=r.remainingMinutesToFull;
            }
        }
        double prev=Double.NaN;
        for(double amps=1.645;amps<1.653;amps+=.00001){
            ChargingEstimate.Result r=ChargingEstimate.forScooter(base(71,52.4,amps,100d),value(71d),value(15.3),2538,ChargeSessionStore.Charger.STOCK,history);
            if(Double.isFinite(prev))check(Math.abs(r.remainingMinutesToFull-prev)<.5,"Full personal composition has no current-admission cliff: "+amps);
            prev=r.remainingMinutesToFull;
        }
        for(Double target:new Double[]{null,80d,95d,100d}){
            ChargingEstimate.Result before=ChargingEstimate.forScooter(base(80,53.7999999,1.94,target),value(80d),value(15.3),2538,ChargeSessionStore.Charger.STOCK,history);
            ChargingEstimate.Result after=ChargingEstimate.forScooter(base(80,53.8000001,1.94,target),value(80d),value(15.3),2538,ChargeSessionStore.Charger.STOCK,history);
            near(before.remainingMinutesToFull,after.remainingMinutesToFull,.001,"Personal fallback continuous at exact upper coverage boundary");
            if(target==null)check(before.remainingMinutesToLimit==null&&after.remainingMinutesToLimit==null,"Boundary with unknown cutoff");
            else near(before.remainingMinutesToLimit,after.remainingMinutesToLimit,.001,"Lower-cutoff cap uses the selected full fallback");
        }
        ChargeSessionStore.Estimate snapshot=ChargingEstimate.recordingSnapshot(warm);
        check(snapshot.version.equals("ut5-stock-pack-v6")&&snapshot.fullSource==ChargeSessionStore.Source.STOCK_REFERENCE,"New captures identify changed algorithm and source");
        System.out.println("StockVoltageReference: "+checks+" checks passed");
    }
}
