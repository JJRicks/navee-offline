package local.navee.offlinelock;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Full-pipeline retrospective replay. All personal evidence predates the replayed charge. */
public final class StockVoltageReplayTest {
    private static int checks;
    private static void check(boolean value,String message) {
        checks++; if(!value)throw new AssertionError(message);
    }
    static ChargingEstimate.Value live(Double value,long time) {
        return new ChargingEstimate.Value(value,true,time);
    }
    static ChargingEstimate.Result estimate(StockVoltageReplayTrace.Case trace,
            StockVoltageReplayTrace.Point point,Double target,ChargeSessionStore.Curve personal,
            ChargeReferenceGate gate) {
        long time=point.elapsed+1000;
        ChargingEstimate.Result baseline=ChargingEstimate.calculate(live(1d,time),live(point.volts,time),
                live(point.amps,time),live(point.soc,time),live(15.3,time),live(target,time));
        return ChargingEstimate.forScooter(baseline,live(point.soc,time),live(15.3,time),2538,
                trace.charger,personal,gate,trace.name,time,time);
    }
    static ChargeSessionStore.Curve history(StockVoltageReplayTrace.Case trace) throws IOException {
        if(trace==StockVoltageReplayTrace.STOCK_BULK)
            return curve(StockVoltageReplayTrace.STOCK_LATE);
        if(trace==StockVoltageReplayTrace.FAST_RECOVERED)
            return curve(StockVoltageReplayTrace.FAST_PRIMARY);
        if(trace==StockVoltageReplayTrace.WARM_PHYSICAL_CHARGE)
            return curve(StockVoltageReplayTrace.STOCK_LATE,StockVoltageReplayTrace.STOCK_BULK);
        return null;
    }
    static ChargeSessionStore.Curve curve(StockVoltageReplayTrace.Case... traces) throws IOException {
        File directory=Files.createTempDirectory("stock-voltage-replay-").toFile();
        try {
            int file=0;
            for(StockVoltageReplayTrace.Case trace:traces)for(StockVoltageReplayTrace.Part part:trace.parts) {
                long start=1000000L+(file*100000000L);
                StringBuilder journal=new StringBuilder("charge-v1,fixture,").append(trace.charger)
                        .append(',').append(start).append(',').append(start).append('\n');
                for(StockVoltageReplayTrace.Point point:part.points) {
                    long time=start+point.elapsed;
                    journal.append("s,").append(time).append(',').append(time).append(',')
                            .append(point.soc).append(',').append(point.volts).append(',')
                            .append(point.amps).append(',').append(point.volts*point.amps)
                            .append(",,").append(point.gap?1:0).append('\n');
                }
                journal.append("end,").append(part.stop).append('\n');
                Files.write(new File(directory,String.format("00000000-0000-0000-0000-%012d.csv",file++)).toPath(),
                        journal.toString().getBytes(StandardCharsets.UTF_8));
            }
            return new ChargeSessionStore(directory).snapshot().curves.get(
                    ChargeSessionStore.key("fixture",traces[0].charger));
        } finally {
            File[] files=directory.listFiles();
            if(files!=null)for(File file:files)Files.delete(file.toPath());
            Files.delete(directory.toPath());
        }
    }
    static boolean voltageMatched(ChargingEstimate.Result result)throws Exception {
        // Reflection keeps the offline harness compilable against the frozen v3 sources.
        try {return result.getClass().getField("stockVoltageToFull").getBoolean(result);}
        catch(NoSuchFieldException oldEstimator) {return false;}
    }
    public static void main(String[] args)throws Exception {
        StockVoltageReplayTrace.Case warm=StockVoltageReplayTrace.WARM_PHYSICAL_CHARGE;
        check(warm.parts.length==2,"Both warm recordings remain one physical charge");
        check(warm.parts[0].stop==ChargeSessionStore.Stop.BLUETOOTH_OFF
                &&warm.parts[1].stop==ChargeSessionStore.Stop.COMPLETE,"Only the second part observes completion");
        long split=warm.parts[1].physicalOffset-warm.parts[0].points.get(warm.parts[0].points.size()-1).elapsed;
        check(split==1114894L,"The missing split is retained in elapsed truth, never filled with samples");
        int samples=0,internalGaps=0;
        for(StockVoltageReplayTrace.Part part:warm.parts) {
            samples+=part.points.size();
            for(int i=1;i<part.points.size();i++)if(part.points.get(i).gap)internalGaps++;
        }
        check(samples==1833&&internalGaps==4,"Warm sample count and continuity markers are preserved");
        check(history(warm).qualifiedSessions==2,"Warm validation learns only the two earlier stock sessions");
        double warmError=0,selectedError=0,selectedSavedError=0;
        int warmSamples=0,selectedSamples=0,unchangedWarm=0;
        for(StockVoltageReplayTrace.Case trace:StockVoltageReplayTrace.ALL) {
            ChargeSessionStore.Curve personal=history(trace);
            for(StockVoltageReplayTrace.Part part:trace.parts) {
                ChargeReferenceGate gate=new ChargeReferenceGate();
                for(StockVoltageReplayTrace.Point point:part.points) {
                    if(point.gap)gate.reset();
                    ChargingEstimate.Result full=estimate(trace,point,100d,personal,gate);
                    check(full.remainingMinutesToFull!=null&&full.remainingMinutesToFull>=0,
                            trace.name+" finite nonnegative full time");
                    check(full.remainingMinutesToFull.equals(full.remainingMinutesToLimit),
                            trace.name+" full target agrees with full time");
                    if(point.soc==100)check(full.remainingMinutesToFull==0d,trace.name+" displayed completion is zero");
                    ChargingEstimate.Result unknown=estimate(trace,point,null,personal,gate);
                    check(unknown.remainingMinutesToLimit==null&&unknown.targetPercent==null,
                            trace.name+" unknown cutoff remains unknown");
                    check(unknown.remainingMinutesToFull.equals(full.remainingMinutesToFull),
                            trace.name+" unknown cutoff preserves the same hypothetical full time");
                    boolean matched=voltageMatched(full);
                    if(matched) {
                        check(trace.charger==ChargeSessionStore.Charger.STOCK&&point.soc<89,
                                "Voltage progress stays within the stock bulk scope");
                        check(full.referenceToFull&&!full.learnedToFull&&full.referenceToLimit&&!full.learnedToLimit,
                                "Percentage-only personal evidence cannot replace the voltage full estimate");
                    }
                    for(double target:new double[]{80,95}) {
                        ChargingEstimate.Result limit=estimate(trace,point,target,personal,gate);
                        check(limit.remainingMinutesToLimit!=null&&limit.remainingMinutesToLimit>=0,
                                trace.name+" valid lower-cutoff estimate");
                        check(limit.remainingMinutesToFull.equals(full.remainingMinutesToFull),
                                trace.name+" lower cutoff preserves the same hypothetical full time");
                        if(point.soc>=target)check(limit.remainingMinutesToLimit==0d,
                                trace.name+" a reached cutoff has no remaining time");
                        if(matched)check(limit.remainingMinutesToLimit<=limit.remainingMinutesToFull,
                                "Lower-cutoff time cannot exceed refined time to full");
                    }
                    if(trace==warm&&point.soc<100) {
                        double error=Math.abs(full.remainingMinutesToFull-warm.truth(part,point));
                        warmError+=error; warmSamples++;
                        if(matched) {
                            selectedError+=error; selectedSavedError+=Math.abs(point.saved-warm.truth(part,point));
                            selectedSamples++;
                        } else {
                            if(point.soc>=89)check(point.saved.equals(full.remainingMinutesToFull),
                                    "The established finishing taper retains recorded v3 behavior exactly");
                            else check(full.remainingMinutesToFull>=point.saved,
                                    "Uncertain bulk cannot become more optimistic than the original prediction");
                            unchangedWarm++;
                        }
                    }
                }
            }
        }
        check(warmSamples==1832&&selectedSamples>=750&&selectedSamples<=900,
                "Validation covers the complete observed physical charge and a bounded matched subset");
        check(unchangedWarm>900,"Uncovered earlier voltage and the final taper are included in validation");
        check(selectedError/selectedSamples<3&&selectedSavedError/selectedSamples>30,
                "Retrospective matched-sample MAE improves without allowing personal SOC precedence");
        check(warmError/warmSamples<27.6,"Conservative whole-charge replay still improves on the recorded v3 baseline");
        System.out.println("StockVoltageReplayTest: "+checks+" checks; retrospective warm matched MAE "
                +(selectedError/selectedSamples)+" min over "+selectedSamples+" samples");
    }
}
