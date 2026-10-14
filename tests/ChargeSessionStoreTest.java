package local.navee.offlinelock;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

public final class ChargeSessionStoreTest {
    static int checks;
    static final long WALL=1_800_000_000_000L, MONO=1_000_000L;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static void near(double expected,Double actual,String message){check(actual!=null&&Math.abs(actual-expected)<.0001,message+": "+actual);}
    static ChargeSessionStore.Value value(Double v,long mono){return new ChargeSessionStore.Value(v,mono,0x72);}
    static ChargeSessionStore.Frame frame(long mono,double soc){return new ChargeSessionStore.Frame(WALL+mono-MONO,mono,value(1d,mono),value(soc,mono),value(52d,mono),value(-2d,mono),value(23d,mono));}
    static ChargeSessionStore store()throws Exception{return new ChargeSessionStore(Files.createTempDirectory("charge-test-").toFile());}
    static void session(ChargeSessionStore store,String profile,ChargeSessionStore.Charger charger,int start,int end,long perPercent,boolean gap,boolean rollback)throws Exception{
        store.start(profile,charger,WALL,MONO);
        long finish=MONO+(end-start)*perPercent;
        for(long t=MONO;t<=finish;t+=10000){double soc=start+(t-MONO)/perPercent;
            if(gap&&t==MONO+5*perPercent)store.markGap();
            if(rollback&&t>=MONO+7*perPercent&&t<MONO+8*perPercent)soc-=3;
            store.sample(frame(t,soc));
        }
        store.stop(ChargeSessionStore.Stop.STOPPED);
    }
    static ChargeSessionStore.Curve curve(ChargeSessionStore store,String profile,ChargeSessionStore.Charger charger)throws Exception{return store.snapshot().curves.get(ChargeSessionStore.key(profile,charger));}
    public static void main(String[] args)throws Exception{
        ChargeSessionStore a=store();
        session(a,"one",ChargeSessionStore.Charger.STOCK,40,100,60000,false,false);
        ChargeSessionStore.Curve first=curve(a,"one",ChargeSessionStore.Charger.STOCK);
        check(first!=null&&first.qualifiedSessions==1&&first.estimate(50,100)==null,"One session cannot claim learned ETA");
        session(a,"one",ChargeSessionStore.Charger.STOCK,40,100,120000,false,false);
        ChargeSessionStore.Curve learned=curve(a,"one",ChargeSessionStore.Charger.STOCK);
        near(75,learned.estimate(50,100).minutes,"Independent session curves averaged");
        near(14.25,learned.estimate(50.5,60).minutes,"Partial starting interval");
        check(learned.estimate(40,100)==null,"Unobserved initial partial interval not invented");
        check(learned.estimate(50,100).sessions==2,"At least two distinct sessions per interval");
        session(a,"one",ChargeSessionStore.Charger.HIGH_SPEED,40,100,60000,false,false);
        check(curve(a,"one",ChargeSessionStore.Charger.HIGH_SPEED).estimate(50,100)==null,"Charger models are isolated");
        session(a,"two",ChargeSessionStore.Charger.STOCK,40,100,60000,false,false);
        check(curve(a,"two",ChargeSessionStore.Charger.STOCK).estimate(50,100)==null,"Profile models are isolated");
        ChargeSessionStore partial=store();for(int n=0;n<2;n++)session(partial,"one",ChargeSessionStore.Charger.STOCK,40,80,60000,false,false);
        ChargeSessionStore.Curve p=curve(partial,"one",ChargeSessionStore.Charger.STOCK);
        check(p.estimate(50,100)==null,"Missing taper does not become a full-charge estimate");near(30,p.estimate(50,80).minutes,"Covered cutoff still learned");
        ChargingEstimate.Value live=new ChargingEstimate.Value(1d,true,100);
        ChargingEstimate.Result baseline=ChargingEstimate.calculate(live,new ChargingEstimate.Value(52d,true,100),new ChargingEstimate.Value(-2d,true,100),new ChargingEstimate.Value(50d,true,100),new ChargingEstimate.Value(14.5,true,100),new ChargingEstimate.Value(80d,true,100));
        ChargingEstimate.Result adjusted=ChargingEstimate.withObservedCurve(baseline,new ChargingEstimate.Value(50d,true,100),p);
        check(adjusted.learnedToLimit&&!adjusted.learnedToFull&&adjusted.learnedSessions==2,"Result exposes mixed estimate sources");near(30,adjusted.remainingMinutesToLimit,"Learned cutoff replaces baseline");near(217.5,adjusted.remainingMinutesToFull,"Current-rate fallback retained for uncovered taper");
        check(adjusted.status.contains("taper")&&adjusted.status.contains("manual"),"Fallback and selected charger are explained");
        ChargingEstimate.Result stale=ChargingEstimate.calculate(live,new ChargingEstimate.Value(52d,true,100),new ChargingEstimate.Value(-2d,false,100),new ChargingEstimate.Value(50d,true,100),new ChargingEstimate.Value(14.5,true,100),new ChargingEstimate.Value(80d,true,100));
        check(ChargingEstimate.withObservedCurve(stale,new ChargingEstimate.Value(50d,true,100),learned).inputWatts==null,"Learning cannot bypass paired fresh electrical gate");
        ChargeSessionStore bad=store();bad.start("one",ChargeSessionStore.Charger.STOCK,WALL,MONO);
        check(bad.sample(frame(MONO,50)),"Fresh initial sample accepted");
        check(!bad.sample(frame(MONO,50)),"Duplicate source frame ignored");
        for(ChargeSessionStore.Frame f:Arrays.asList(
                new ChargeSessionStore.Frame(WALL,MONO+10000,value(1d,MONO),value(50d,MONO+10000),value(52d,MONO+10000),value(-2d,MONO+10000),null),
                new ChargeSessionStore.Frame(WALL,MONO+10000,value(1d,MONO+10000),value(50d,MONO+10000),value(52d,MONO+10000),value(-2d,MONO+9999),null),
                new ChargeSessionStore.Frame(WALL,MONO+10000,value(1d,MONO+10000),value(50d,MONO+10000),new ChargeSessionStore.Value(52d,MONO+10000,-1),value(-2d,MONO+10000),null),
                frame(MONO+10000,Double.NaN),frame(MONO+10000,101)))check(!bad.sample(f),"Stale, unpaired, synthetic or invalid sample rejected");
        check(!bad.sample(new ChargeSessionStore.Frame(WALL,MONO+10000,value(1d,MONO+10000),value(50d,MONO+10000),value(52d,MONO+10000),value(-20d,MONO+10000),null)),"Electrical spike excluded");
        bad.stop(ChargeSessionStore.Stop.STOPPED);check(bad.snapshot().curves.isEmpty(),"Short session excluded");
        ChargeSessionStore gap=store();for(int n=0;n<2;n++)session(gap,"one",ChargeSessionStore.Charger.STOCK,40,80,60000,true,false);
        check(curve(gap,"one",ChargeSessionStore.Charger.STOCK).estimate(44,47)==null,"Gap-crossing interval excluded");
        ChargeSessionStore rollback=store();for(int n=0;n<2;n++)session(rollback,"one",ChargeSessionStore.Charger.STOCK,40,80,60000,false,true);
        check(curve(rollback,"one",ChargeSessionStore.Charger.STOCK).estimate(46,49)==null,"Rollback intervals excluded");
        ChargeSessionStore disagreement=store();session(disagreement,"one",ChargeSessionStore.Charger.STOCK,40,60,60000,false,false);session(disagreement,"one",ChargeSessionStore.Charger.STOCK,40,60,180000,false,false);
        check(curve(disagreement,"one",ChargeSessionStore.Charger.STOCK).estimate(50,60)==null,"Two strongly disagreeing curves remain unknown");
        File dir=Files.createTempDirectory("charge-recovery-").toFile();ChargeSessionStore crash=new ChargeSessionStore(dir);crash.start("one",ChargeSessionStore.Charger.STOCK,WALL,MONO);crash.sample(frame(MONO,50));
        ChargeSessionStore recovered=new ChargeSessionStore(dir);check(!recovered.active()&&recovered.snapshot().sessions==1&&recovered.snapshot().curves.isEmpty(),"Process restart finalizes but excludes interrupted capture");
        File sessionFile=dir.listFiles()[0];try(FileOutputStream out=new FileOutputStream(sessionFile,true)){out.write("s,broken".getBytes(StandardCharsets.UTF_8));}
        check(recovered.snapshot().sessions==0,"Trailing truncated data fails closed");
        for(int i=0;i<25;i++){recovered.start("one",ChargeSessionStore.Charger.STOCK,WALL+i,MONO+i);recovered.stop(ChargeSessionStore.Stop.STOPPED);}
        check(dir.listFiles().length<=ChargeSessionStore.MAX_SESSIONS,"Session retention is bounded");
        recovered.clear("one");check(recovered.snapshot().sessions==0,"Per-profile clear removes recordings");
        ChargeSessionStore cadence=store();cadence.start("one",ChargeSessionStore.Charger.STOCK,WALL,MONO);
        check(cadence.sample(frame(MONO,50)),"Cadence fixture initial sample");
        check(!cadence.sample(frame(MONO+5000,50))&&cadence.sampleCount()==1,"Samples are bounded to ten-second cadence");
        check(cadence.sample(frame(MONO+10000,50))&&cadence.sampleCount()==2,"Ten-second boundary accepted");
        check(!cadence.sample(frame(MONO+ChargeSessionStore.MAX_SESSION_MS+1,51))&&!cadence.active(),"Eighteen-hour capture limit stops journal");
        ChargeSessionStore.Frame mismatched=new ChargeSessionStore.Frame(WALL,MONO,value(1d,MONO),value(50d,MONO),value(52d,MONO),new ChargeSessionStore.Value(-2d,MONO,0x70),null);
        check(!mismatched.usable(),"Matching times from different electrical opcodes rejected");
        check(ChargeSessionStore.fresh(value(1d,MONO),MONO+5000)&&!ChargeSessionStore.fresh(value(1d,MONO),MONO+5001),"Five-second freshness boundary");
        for(Double invalid:new Double[]{null,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY})
            check(!ChargeSessionStore.fresh(value(invalid,MONO),MONO),"Invalid measurement rejected");
        for(long age:new long[]{-1,5001,10000})check(!ChargeSessionStore.fresh(value(1d,MONO),MONO+age),"Clock/freshness invalidity excluded");
        ChargeSessionStore outlier=store();session(outlier,"one",ChargeSessionStore.Charger.STOCK,40,60,60000,false,false);session(outlier,"one",ChargeSessionStore.Charger.STOCK,40,60,60000,false,false);session(outlier,"one",ChargeSessionStore.Charger.STOCK,40,60,600000,false,false);
        near(10,curve(outlier,"one",ChargeSessionStore.Charger.STOCK).estimate(50,60).minutes,"A divergent third curve does not skew two consistent sessions");
        ChargeSessionStore wallJump=store();
        for(int n=0;n<2;n++){
            wallJump.start("one",ChargeSessionStore.Charger.STOCK,WALL,MONO);
            for(long t=MONO;t<=MONO+2400000;t+=10000){ChargeSessionStore.Frame f=frame(t,40+(t-MONO)/60000);long wall=f.wall+(t>=MONO+300000?600000:0);
                wallJump.sample(new ChargeSessionStore.Frame(wall,f.mono,f.state,f.soc,f.voltage,f.current,f.temperature));}
            wallJump.stop(ChargeSessionStore.Stop.STOPPED);
        }
        check(curve(wallJump,"one",ChargeSessionStore.Charger.STOCK).estimate(44,46)==null,"Wall-clock jumps cannot bridge a learned interval");
        ChargeSessionStore tooSmall=store();for(int n=0;n<2;n++)session(tooSmall,"one",ChargeSessionStore.Charger.STOCK,40,50,60000,false,false);
        check(tooSmall.snapshot().curves.isEmpty(),"Ten-percent partial capture has fewer than ten complete observed intervals");
        ChargeSessionStore stopped=store();stopped.start("one",ChargeSessionStore.Charger.STOCK,WALL,MONO);stopped.stop(ChargeSessionStore.Stop.PROCESS_ENDED);
        check(!stopped.sample(frame(MONO+10000,50))&&!stopped.active(),"A stopped journal cannot resume from later samples");
        ChargeSessionStore completed=store();
        for(int n=0;n<2;n++){
            completed.start("one",ChargeSessionStore.Charger.STOCK,WALL,MONO);
            for(long t=MONO;t<MONO+3600000;t+=10000)completed.sample(frame(t,40+(t-MONO)/60000));
            long t=MONO+3600000;
            ChargeSessionStore.Frame terminal=new ChargeSessionStore.Frame(WALL+t-MONO,t,value(0d,t),value(100d,t),value(52d,t),value(0d,t),value(23d,t));
            check(completed.sampleCompletion(terminal),"Final 100% packet retained when charging state and current drop to zero");
            completed.stop(ChargeSessionStore.Stop.COMPLETE);
        }
        near(1,curve(completed,"one",ChargeSessionStore.Charger.STOCK).estimate(99,100).minutes,"Two completed sessions retain the final taper interval");
        check(!completed.sampleCompletion(frame(MONO+10000,100)),"Completion samples cannot start or resume capture");
        ChargeSessionStore.Frame notComplete=new ChargeSessionStore.Frame(WALL,MONO,value(0d,MONO),value(99d,MONO),value(52d,MONO),value(0d,MONO),null);
        check(!notComplete.usableCompletion(),"Charging ended below 100 is not a completion sample");
        System.out.println("ChargeSessionStore: "+checks+" checks passed");
    }
}
