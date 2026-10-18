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
    static ChargeSessionStore.Frame electrical(long offset,double soc,double volts,double amps){
        long mono=MONO+offset;return new ChargeSessionStore.Frame(WALL+offset,mono,value(1d,mono),value(soc,mono),value(volts,mono),value(amps,mono),value(23d,mono));
    }
    static ChargeSessionStore startup()throws Exception{
        ChargeSessionStore store=store();store.start("one",ChargeSessionStore.Charger.HIGH_SPEED,WALL,MONO);
        check(store.sample(electrical(0,73,51.398,-1.98)),"Real low-power startup sample accepted");return store;
    }
    static boolean watts(ChargeSessionStore store,long offset,double watts)throws Exception{return store.sample(electrical(offset,73,53,-watts/53));}
    static List<ChargeSessionStore.HistoryPoint> finish(ChargeSessionStore store)throws Exception{
        store.stop(ChargeSessionStore.Stop.STOPPED);return store.recordings("one").get(0).points;
    }
    static void sustainedElectricalChanges()throws Exception{
        ChargeSessionStore trace=startup();
        check(!watts(trace,10000,400)&&!watts(trace,20000,400),"Large new power awaits independent confirmation");
        check(trace.sampleCount()==1&&trace.lastFreshMono()==MONO+20000,"Fresh candidates keep capture alive without being saved");
        check(watts(trace,30000,400),"Sustained 400 W startup no longer waits for the old 305.30412 W threshold");
        check(watts(trace,40000,410),"Confirmed new power records normally at the next interval");
        check(trace.sample(electrical(881866,84,53.949,-5.65)),"Actual later 304.81185 W sample remains accepted");
        List<ChargeSessionStore.HistoryPoint> points=finish(trace);
        check(points.size()==4&&points.get(1).mono==MONO+30000,"Rejected candidates are not backfilled");
        near(-101.76804,points.get(0).power,"Exact initial signed power preserved");
        near(-400,points.get(1).power,"Confirming real measurement preserved");
        near(-304.81185,points.get(3).power,"Actual resumed sample preserved");
        check(points.get(1).gap&&!points.get(2).gap&&points.get(3).gap,"Confirmation and actual missing time break continuity; subsequent samples reconnect");

        ChargeSessionStore transientSpike=startup();
        check(!watts(transientSpike,10000,400)&&watts(transientSpike,20000,104),"Isolated spike remains excluded and normal power resumes");
        check(!watts(transientSpike,30000,400)&&!watts(transientSpike,40000,400)&&watts(transientSpike,50000,400),"A normal committed sample resets pending confirmation");
        points=finish(transientSpike);
        check(points.size()==3&&points.get(1).gap&&points.get(2).gap,"No isolated spike is persisted or connected across");

        ChargeSessionStore rapid=startup();
        for(int t=10000;t<30000;t+=1000)check(!watts(rapid,t,400),"Rapid fresh packets cannot shorten the confirmation window");
        check(watts(rapid,30000,400)&&rapid.sampleCount()==2,"Three cadence-spaced observations confirm after twenty seconds");
        finish(rapid);

        ChargeSessionStore changing=startup();
        check(!watts(changing,10000,400)&&!watts(changing,20000,800)&&!watts(changing,30000,400)
                &&!watts(changing,40000,400)&&watts(changing,50000,400),"Incompatible outliers restart rather than accumulate confirmation");
        finish(changing);

        ChargeSessionStore expired=startup();
        check(!watts(expired,10000,400)&&!watts(expired,20000,400)&&!watts(expired,50001,400)
                &&!watts(expired,60001,400)&&watts(expired,70001,400),"Confirmation cannot bridge a gap longer than twenty-five seconds");
        check(finish(expired).get(1).gap,"Accepted post-gap power starts a distinct segment");

        ChargeSessionStore invalid=startup();
        check(!watts(invalid,10000,400)&&!watts(invalid,20000,400),"Invalid-input fixture has two candidates");
        ChargeSessionStore.Frame stale=electrical(30000,73,53,-400d/53);
        check(!invalid.sample(new ChargeSessionStore.Frame(stale.wall,stale.mono,value(1d,MONO),stale.soc,stale.voltage,stale.current,stale.temperature)),"Stale charge state cannot confirm new power");
        check(!watts(invalid,40000,400)&&!watts(invalid,50000,400)&&watts(invalid,60000,400),"Unusable frame resets pending confirmation");
        finish(invalid);

        ChargeSessionStore voltage=startup();
        check(!voltage.sample(electrical(10000,73,56,-2))&&!voltage.sample(electrical(20000,73,56,-2))
                &&voltage.sample(electrical(30000,73,56,-2)),"Sustained valid voltage change also establishes a fresh baseline");
        check(finish(voltage).get(1).gap,"Voltage baseline change never joins the rejected interval");

        ChargeSessionStore falling=store();falling.start("one",ChargeSessionStore.Charger.HIGH_SPEED,WALL,MONO);
        check(watts(falling,0,400)&&!watts(falling,10000,104)&&!watts(falling,20000,104)&&watts(falling,30000,104),"Sustained large power decrease also recovers");
        finish(falling);

        ChargeSessionStore recovered=startup();check(!watts(recovered,10000,400),"Recovery fixture starts a pending candidate");
        recovered.markGap();
        check(watts(recovered,20000,400)&&watts(recovered,30000,410),"Verified recovery immediately establishes its own electrical baseline");
        points=finish(recovered);check(points.get(1).gap&&!points.get(2).gap,"Recovery keeps its explicit gap");

        ChargeSessionStore restarted=startup();
        check(!watts(restarted,10000,400)&&!watts(restarted,20000,400),"Session-reset fixture stops with pending evidence");
        finish(restarted);restarted.start("one",ChargeSessionStore.Charger.HIGH_SPEED,WALL+30000,MONO+30000);
        check(restarted.sample(electrical(30000,73,51.398,-1.98))&&!watts(restarted,40000,400)&&!watts(restarted,50000,400)
                &&watts(restarted,60000,400),"A new session cannot inherit prior confirmation");
        finish(restarted);

        ChargeSessionStore bounded=startup();
        for(int t=10000;t<=40000;t+=10000){
            check(!bounded.sample(electrical(t,73,101,-2)),"Sustained invalid voltage never becomes accepted");
            check(!bounded.sample(electrical(t,73,53,-31)),"Sustained invalid current never becomes accepted");
        }
        check(bounded.sampleCount()==1,"Confirmation does not bypass physical limits");finish(bounded);
    }
    static ChargingEstimate.Result estimate(double soc,Double amps){
        return ChargingEstimate.calculate(new ChargingEstimate.Value(1d,true,100),new ChargingEstimate.Value(52d,true,100),
                new ChargingEstimate.Value(amps,true,100),new ChargingEstimate.Value(soc,true,100),
                new ChargingEstimate.Value(14.5,true,100),new ChargingEstimate.Value(100d,true,100));
    }
    static ChargingEstimate.Result observed(ChargeSessionStore.Curve curve,double soc,Double amps){
        return ChargingEstimate.withObservedCurve(estimate(soc,amps),new ChargingEstimate.Value(soc,true,100),curve);
    }
    static ChargeSessionStore store()throws Exception{return new ChargeSessionStore(Files.createTempDirectory("charge-test-").toFile());}
    static void session(ChargeSessionStore store,String profile,ChargeSessionStore.Charger charger,int start,int end,long perPercent,boolean gap,boolean rollback)throws Exception{
        session(store,profile,charger,start,end,perPercent,gap,rollback,soc->2);
    }
    static void session(ChargeSessionStore store,String profile,ChargeSessionStore.Charger charger,int start,int end,long perPercent,boolean gap,boolean rollback,java.util.function.DoubleUnaryOperator amps)throws Exception{
        store.start(profile,charger,WALL,MONO);
        long finish=MONO+(end-start)*perPercent;
        for(long t=MONO;t<=finish;t+=10000){double soc=start+(t-MONO)/perPercent;
            if(gap&&t==MONO+5*perPercent)store.markGap();
            if(rollback&&t>=MONO+7*perPercent&&t<MONO+8*perPercent)soc-=3;
            ChargeSessionStore.Frame f=frame(t,soc);
            store.sample(new ChargeSessionStore.Frame(f.wall,f.mono,f.state,f.soc,f.voltage,value(-amps.applyAsDouble(soc),t),f.temperature));
        }
        store.stop(ChargeSessionStore.Stop.STOPPED);
    }
    static ChargeSessionStore.Curve curve(ChargeSessionStore store,String profile,ChargeSessionStore.Charger charger)throws Exception{return store.snapshot().curves.get(ChargeSessionStore.key(profile,charger));}
    /** Backup staging reads and re-associates journals without opening a store or recovering them. */
    static void journals()throws Exception{
        File folder=Files.createTempDirectory("charge-journal-test-").toFile();ChargeSessionStore store=new ChargeSessionStore(folder);
        session(store,"source-1",ChargeSessionStore.Charger.HIGH_SPEED,40,60,60000,false,false);
        File saved=folder.listFiles((d,n)->n.endsWith(".csv"))[0];byte[] before=Files.readAllBytes(saved.toPath());
        ChargeSessionStore.Journal journal=ChargeSessionStore.inspectJournal(saved);
        check(journal.profile.equals("source-1")&&journal.startedWall==WALL&&journal.startedMono==MONO&&journal.finished,"journal header facts are read");
        ChargeSessionStore.reassignJournal(saved,"local-2");byte[] after=Files.readAllBytes(saved.toPath());
        String a=new String(before,StandardCharsets.UTF_8),b=new String(after,StandardCharsets.UTF_8);
        check(ChargeSessionStore.inspectJournal(saved).profile.equals("local-2")&&a.substring(a.indexOf('\n')).equals(b.substring(b.indexOf('\n')))&&b.startsWith("charge-v2,local-2,HIGH_SPEED,"),"reassignment changes only the header profile");
        check(!new File(saved.getPath()+".mapping").exists(),"no temporary mapping file remains");
        File active=new File(folder,UUID.randomUUID()+".active");Files.write(active.toPath(),("charge-v2,source-1,STOCK,"+WALL+","+MONO+"\n").getBytes(StandardCharsets.UTF_8));
        check(!ChargeSessionStore.inspectJournal(active).finished&&active.exists(),"an unfinished journal is inspected without being finalized");
        for(String bad:new String[]{"charge-v3,a,STOCK,1,1\n","charge-v2,../x,STOCK,1,1\n","charge-v2,a,NONE,1,1\n","charge-v2,a,STOCK,1,1\nend,STOPPED\nend,STOPPED\n","charge-v2,a,STOCK,0,1\n"}){
            File file=new File(folder,UUID.randomUUID()+".csv");Files.write(file.toPath(),bad.getBytes(StandardCharsets.UTF_8));
            boolean refused=false;try{ChargeSessionStore.inspectJournal(file);}catch(IOException expected){refused=true;}check(refused,"unreadable journal refused: "+bad.trim());
            refused=false;try{ChargeSessionStore.reassignJournal(file,"x");}catch(IOException expected){refused=true;}check(refused&&Arrays.equals(Files.readAllBytes(file.toPath()),bad.getBytes(StandardCharsets.UTF_8)),"unreadable journal is never rewritten");
        }
        boolean refused=false;try{ChargeSessionStore.reassignJournal(saved,"bad/profile");}catch(IOException expected){refused=true;}check(refused,"invalid destination refused");
    }
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
        check(adjusted.status.contains("taper")&&adjusted.status.contains("stock"),"Fallback and selected charger are explained");
        check("From 2 past charges".equals(adjusted.label),"Short label names the learned source: "+adjusted.label);
        ChargingEstimate.Result unknownCutoff=ChargingEstimate.calculate(live,new ChargingEstimate.Value(52d,true,100),new ChargingEstimate.Value(-2d,true,100),new ChargingEstimate.Value(50d,true,100),new ChargingEstimate.Value(14.5,true,100),new ChargingEstimate.Value(null,true,100));
        ChargingEstimate.Result learnedUnknown=ChargingEstimate.withObservedCurve(unknownCutoff,new ChargingEstimate.Value(50d,true,100),learned);
        check(learnedUnknown.learnedToFull&&learnedUnknown.remainingMinutesToLimit==null,"Legacy learned full ETA with unknown cutoff preserves null instead of unboxing it");
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

        ChargeSessionStore stock=store();
        for(int n=0;n<2;n++)session(stock,"one",ChargeSessionStore.Charger.STOCK,40,100,180000,false,false,soc->2.75);
        ChargeSessionStore.Curve stockCurve=curve(stock,"one",ChargeSessionStore.Charger.STOCK);
        ChargingEstimate.Result faster=observed(stockCurve,60,5.5);
        near(14.5*.4/5.5*60,faster.remainingMinutesToFull,"Twice the training current keeps the review's approximately 63-minute live estimate");
        check(!faster.learnedToFull&&!faster.learnedToLimit&&faster.learnedSessions==0,"Mismatched power cannot claim a learned ETA");
        check(faster.status.contains("Live power differs")&&faster.status.contains("selected charger")&&!faster.status.contains("Observed average"),"Power mismatch is explicit without claiming charger identification");
        check("Charger mismatch · rate estimate".equals(faster.label),"Short label marks the mismatch as a rate estimate");
        ChargingEstimate.Result same=observed(stockCurve,60,2.75);
        near(120,same.remainingMinutesToFull,"Compatible stock power keeps the learned 120-minute curve");
        check(same.learnedToFull&&same.learnedSessions==2,"Two compatible stock sessions are reported");
        check(stockCurve.powerMatch(60,143*.65)==ChargeSessionStore.Curve.PowerMatch.MATCH&&stockCurve.powerMatch(60,143*1.35)==ChargeSessionStore.Curve.PowerMatch.MATCH,"Power tolerance includes the conservative 35 percent boundaries");
        check(stockCurve.powerMatch(60,143*.649)==ChargeSessionStore.Curve.PowerMatch.DIFFERENT&&stockCurve.powerMatch(60,143*1.351)==ChargeSessionStore.Curve.PowerMatch.DIFFERENT,"Power outside tolerance is rejected on either side");
        for(Double amps:new Double[]{null,Double.NaN,Double.POSITIVE_INFINITY,0d}){
            ChargingEstimate.Result missing=observed(stockCurve,60,amps);
            check(!missing.learnedToFull&&missing.remainingMinutesToFull==null,"Missing or insufficient live power cannot use a learned curve");
        }
        ChargeSessionStore tapered=store();
        for(int n=0;n<2;n++)session(tapered,"one",ChargeSessionStore.Charger.STOCK,40,100,180000,false,false,soc->Math.max(.5,2.75-.1125*Math.max(0,soc-80)));
        ChargeSessionStore.Curve taperCurve=curve(tapered,"one",ChargeSessionStore.Charger.STOCK);
        ChargingEstimate.Result taper=observed(taperCurve,90,1.625);
        near(30,taper.remainingMinutesToFull,"Taper compares with power observed at the same SOC, not bulk charging power");
        check(taper.learnedToFull,"Compatible taper remains a learned estimate");
        check(!observed(taperCurve,90,2.75).learnedToFull,"Bulk power at a recorded taper SOC falls back instead of misusing taper");
        ChargeSessionStore noPower=store();
        for(int n=0;n<2;n++)session(noPower,"one",ChargeSessionStore.Charger.STOCK,40,100,60000,false,false,soc->0);
        ChargingEstimate.Result unknown=observed(curve(noPower,"one",ChargeSessionStore.Charger.STOCK),60,2.75);
        check(!unknown.learnedToFull&&unknown.status.contains("lack consistent power"),"Time intervals without usable training power fail closed");
        near(estimate(60,2.75).remainingMinutesToFull,unknown.remainingMinutesToFull,"Missing training power preserves current-rate estimate");
        ChargeSessionStore mixedPower=store();
        session(mixedPower,"one",ChargeSessionStore.Charger.STOCK,40,100,180000,false,false,soc->2.75);
        session(mixedPower,"one",ChargeSessionStore.Charger.STOCK,40,100,180000,false,false,soc->5.5);
        check(!observed(curve(mixedPower,"one",ChargeSessionStore.Charger.STOCK),60,4.125).learnedToFull,"Disagreeing training power cannot establish an invented middle charger");
        check(observed(curve(completed,"one",ChargeSessionStore.Charger.STOCK),99,2d).learnedToFull,"Zero-current final packet does not dilute the 99 percent interval's charging power");

        ChargeSessionStore retained=store();
        for(int n=0;n<2;n++){
            session(retained,"one",ChargeSessionStore.Charger.HIGH_SPEED,40,60,60000,false,false);
            session(retained,"two",ChargeSessionStore.Charger.STOCK,40,60,60000,false,false);
        }
        for(int n=0;n<25;n++)session(retained,"one",ChargeSessionStore.Charger.STOCK,40,60,60000,false,false);
        check(curve(retained,"one",ChargeSessionStore.Charger.HIGH_SPEED).qualifiedSessions==2,"Frequent stock captures preserve a minority high-speed curve");
        check(curve(retained,"two",ChargeSessionStore.Charger.STOCK).qualifiedSessions==2,"Retention is fair across scooter profiles too");
        check(retained.snapshot().sessions==20,"Qualified capture retention stays globally bounded");
        for(int n=0;n<30;n++){
            retained.start("one",ChargeSessionStore.Charger.STOCK,WALL,MONO);
            retained.sample(frame(MONO,40));retained.stop(n%2==0?ChargeSessionStore.Stop.ERROR:ChargeSessionStore.Stop.STOPPED);
        }
        check(curve(retained,"one",ChargeSessionStore.Charger.HIGH_SPEED).estimate(50,60)!=null,"Failed and short capture floods preserve usable minority charger evidence");
        check(curve(retained,"two",ChargeSessionStore.Charger.STOCK).estimate(50,60)!=null,"Failed captures preserve minority profile evidence");
        check(curve(retained,"one",ChargeSessionStore.Charger.STOCK).qualifiedSessions==15,"Only the active capture's reserved slot reduces qualified data under a failed-capture flood");
        check(retained.snapshot().sessions==20&&retained.snapshot().bytes<=ChargeSessionStore.MAX_SESSIONS*(ChargeSessionStore.MAX_FILE_BYTES+1024),"Retention retains both global file and byte bounds");
        ChargeSessionStore exports=store();
        exports.start("export_one",ChargeSessionStore.Charger.STOCK,WALL,MONO);
        exports.sample(frame(MONO,50));
        StringWriter blocked=new StringWriter();boolean refused=false;
        try{exports.exportJson("export_one",blocked);}catch(IllegalStateException expected){refused=true;}
        check(refused&&blocked.toString().isEmpty(),"Active export is refused before writing a partial JSON");
        exports.sample(new ChargeSessionStore.Frame(WALL+10000,MONO+10000,value(1d,MONO+10000),value(50d,MONO+10000),value(52d,MONO+10000),value(-2d,MONO+10000),null));
        exports.stop(ChargeSessionStore.Stop.DISCONNECTED);
        exports.start("other_profile",ChargeSessionStore.Charger.HIGH_SPEED,WALL,MONO);exports.sample(frame(MONO,80));exports.stop(ChargeSessionStore.Stop.STOPPED);
        StringWriter json=new StringWriter();check(exports.exportJson("export_one",json)==1,"Only the selected scooter is exported");
        String output=json.toString();
        check(output.contains("\"format\":\"navee-charge-recordings-v1\"")&&output.contains("\"charger_selection\":\"manual\""),"Export schema and manual charger label explicit");
        check(output.contains("\"current_a\":-2.0")&&output.contains("\"power_w\":-104.0")&&output.contains("\"voltage_v\":52.0"),"Export keeps signed raw electrical data");
        check(output.contains("\"temperature_c\":null")&&output.contains("\"temperature_c\":23.0"),"Missing temperature stays null");
        check(output.contains("\"gap_before\":true")&&output.contains("\"gap_before\":false"),"Export preserves discontinuities");
        check(output.contains("\"stop_reason\":\"DISCONNECTED\"")&&output.contains("\"qualified_for_learning\":false"),"Incomplete capture remains exportable with status");
        check(!output.contains("other_profile")&&!output.contains("owner_identity"),"Export excludes unrelated profile and credentials");
        check(output.contains("\"started_wall_ms\":"+WALL)&&output.contains("\"elapsed_ms\":"+MONO),"Original clocks preserved");
        StringWriter empty=new StringWriter();check(exports.exportJson("missing",empty)==0&&empty.toString().endsWith("\"sessions\":[]}"),"No recordings is a valid empty document");
        ChargeSessionStore reconnected=store();
        reconnected.start("one",ChargeSessionStore.Charger.STOCK,WALL,MONO);
        reconnected.sample(frame(MONO,50));reconnected.sample(frame(MONO+10000,50));
        reconnected.markGap();
        ChargeSessionStore.Frame afterLoss=new ChargeSessionStore.Frame(WALL+50000,MONO+50000,value(1d,MONO+50000),value(54d,MONO+50000),value(56d,MONO+50000),value(-.2d,MONO+50000),null);
        check(reconnected.sample(afterLoss),"Recovery starts a new electrical baseline instead of rejecting legitimate changed power forever");
        reconnected.stop(ChargeSessionStore.Stop.STOPPED);
        StringWriter resumedJson=new StringWriter();reconnected.exportJson("one",resumedJson);
        check(resumedJson.toString().contains("\"battery_percent\":54.0,\"voltage_v\":56.0,\"current_a\":-0.2,\"power_w\":-11.200000000000001,\"temperature_c\":null,\"gap_before\":true"),"First post-loss sample keeps gap and real measurements");
        check(reconnected.snapshot().sessions==1,"Transient recovery remains one journal, not a second session");
        sustainedElectricalChanges();journals();
        System.out.println("ChargeSessionStore: "+checks+" checks passed");
    }
}
