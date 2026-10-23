package com.jjricks.volt;

import java.io.File;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

public final class ChargeEstimateHistoryTest {
    private static int checks;
    private static final long WALL=1_800_000_000_000L, MONO=1_000_000L;
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static void near(double expected,Double actual,String message){check(actual!=null&&Math.abs(expected-actual)<.000001,message+": "+actual);}
    private static ChargeSessionStore.Value value(double v,long mono){return new ChargeSessionStore.Value(v,mono,0x72);}
    private static ChargeSessionStore.Frame frame(long offset,double soc,long shift,boolean cutoff){
        long mono=MONO+offset;
        return new ChargeSessionStore.Frame(WALL+offset+shift,mono,value(cutoff?0:1,mono),value(soc,mono),value(52,mono),value(cutoff?0:-2,mono),value(23,mono));
    }
    private static ChargeSessionStore store()throws Exception{return new ChargeSessionStore(Files.createTempDirectory("charge-predictions-").toFile());}
    private static ChargeSessionStore.Estimate estimate(Double full,Double limit,Double target){
        return new ChargeSessionStore.Estimate("recorded-v7",full,limit,target,ChargeSessionStore.Source.STOCK_REFERENCE,ChargeSessionStore.Source.CURRENT_RATE,0);
    }
    private static ChargeSessionStore.HistorySession only(ChargeSessionStore store)throws Exception{
        List<ChargeSessionStore.HistorySession> records=store.recordings("one");check(records.size()==1,"One profile recording");return records.get(0);
    }
    private static File journal(File directory,String header,String rows,String stop)throws Exception{
        File file=new File(directory,UUID.randomUUID()+".csv");
        Files.write(file.toPath(),(header+",one,STOCK,"+WALL+","+MONO+"\n"+rows+"end,"+stop+"\n").getBytes(StandardCharsets.UTF_8));return file;
    }
    private static String row(long offset,double soc,boolean gap){return "s,"+(WALL+offset)+","+(MONO+offset)+","+soc+",52.0,-2.0,-104.0,23.0,"+(gap?1:0);}
    private static String prediction(){return ",saved-v1,12.3456789,2.5,80.0,STOCK_REFERENCE,CURRENT_RATE,0";}

    public static void main(String[] args)throws Exception {
        ChargeSessionStore live=store();live.start("one",ChargeSessionStore.Charger.STOCK,WALL,MONO);
        check(live.sample(frame(0,98,0,false),estimate(2.3456789,1.2,100d)),"Snapshot accompanies accepted sample");
        check(!live.sample(frame(5000,98,0,false),estimate(999d,999d,100d)),"Rejected cadence frame cannot overwrite recorded estimate");
        check(live.recordings("one").isEmpty(),"An active partial file is not exposed as saved history");
        live.sample(frame(10000,99,3_600_000,false),estimate(2d,1d,100d));
        live.sampleCompletion(frame(20000,100,3_600_000,true),ChargingEstimate.recordingSnapshot(null));
        live.stop(ChargeSessionStore.Stop.COMPLETE);
        ChargeSessionStore.HistorySession session=only(live);
        check(session.points.size()==3,"Every committed sample retained");
        near(2.3456789,session.points.get(0).estimate.remainingMinutesToFull,"Stored prediction preserves unrounded exact value");
        check("recorded-v7".equals(session.points.get(0).estimate.version),"Stored estimator version not today's implementation");
        check(session.points.get(0).estimate.fullSource==ChargeSessionStore.Source.STOCK_REFERENCE,"Full estimate source preserved");
        check(session.points.get(0).estimate.limitSource==ChargeSessionStore.Source.CURRENT_RATE,"Independent limit source preserved");
        check(session.points.get(2).estimate.remainingMinutesToFull==null&&ChargingEstimate.ESTIMATOR_VERSION.equals(session.points.get(2).estimate.version),"Unavailable terminal prediction retains estimator version");
        check(session.observedCompleteMono==MONO+20000&&session.observedCompleteWall==WALL+20000,"Observed finish anchored on monotonic time despite wall jump");
        List<ChargeEstimateHistory.Point> chart=ChargeEstimateHistory.build(session,false);
        near(1d/3,chart.get(0).actualRemainingMinutes,"Actual time remaining uses observed completion");
        near(1d/6,chart.get(1).actualRemainingMinutes,"Actual countdown unaffected by wall clock change");
        near(0,chart.get(2).actualRemainingMinutes,"Final actual remaining reaches zero");
        check(chart.get(1).wallMs==WALL+10000,"Chart time axis does not jump an hour");
        check(chart.get(1).predictedFinishWallMs==WALL+130000,"Predicted finish combines captured ETA with stable sample time");
        check(chart.get(2).predictedFinishWallMs==null,"No guessed estimate at completion");
        check(chart.get(0).actualFinishWallMs.equals(chart.get(1).actualFinishWallMs),"Observed finish is flat across chart");
        check(!chart.get(1).gap,"Wall clock adjustment does not create an artificial elapsed-time break");
        boolean immutable=false;try{session.points.clear();}catch(UnsupportedOperationException expected){immutable=true;}check(immutable,"History sample list immutable");
        immutable=false;try{live.recordings("one").clear();}catch(UnsupportedOperationException expected){immutable=true;}check(immutable,"History session list immutable");
        immutable=false;try{chart.clear();}catch(UnsupportedOperationException expected){immutable=true;}check(immutable,"Chart series immutable");
        StringWriter export=new StringWriter();live.exportJson("one",export);String json=export.toString();
        check(json.contains("\"format\":\"navee-charge-recordings-v1\"")&&json.contains("\"estimator_version\":\"recorded-v7\""),"Existing export schema receives additive estimate metadata");
        check(json.contains("\"remaining_minutes_to_full\":2.3456789")&&json.contains("\"remaining_minutes_to_limit\":1.2"),"Both exact estimates exported");
        check(json.contains("\"observed_complete_elapsed_ms\":"+(MONO+20000)),"Export includes observed completion for independent analysis");
        check(json.contains("\"remaining_minutes_to_full\":null")&&!json.contains("NaN")&&!json.contains("Infinity"),"Unavailable estimates exported as JSON null");
        live.start("other",ChargeSessionStore.Charger.HIGH_SPEED,WALL,MONO);live.sample(frame(0,40,0,false),estimate(999d,888d,80d));live.stop(ChargeSessionStore.Stop.STOPPED);
        check(live.recordings("one").size()==1&&live.recordings("missing").isEmpty(),"History remains scoped to selected profile");
        export=new StringWriter();live.exportJson("one",export);check(!export.toString().contains("other")&&!export.toString().contains("999.0"),"Export cannot leak another scooter's predictions");
        check(session.points.size()==3,"Previously returned snapshot remains stable after storage mutations");

        ChargeSessionStore unavailable=store();unavailable.start("one",ChargeSessionStore.Charger.STOCK,WALL,MONO);
        unavailable.sample(frame(0,50,0,false),null);
        unavailable.sample(frame(10000,50,0,false),estimate(null,5d,80d));
        unavailable.sample(frame(20000,50,0,false),estimate(20d,null,null));
        unavailable.markGap();unavailable.sample(frame(30000,50,0,false),estimate(19d,null,null));
        unavailable.stop(ChargeSessionStore.Stop.STOPPED);
        ChargeSessionStore.HistorySession unavailableSession=only(unavailable);
        check(unavailableSession.points.get(0).estimate!=null&&unavailableSession.points.get(0).estimate.version.equals(ChargingEstimate.ESTIMATOR_VERSION),"Null snapshot call still records current version");
        chart=ChargeEstimateHistory.build(unavailableSession,false);
        check(chart.get(0).remainingMinutes==null&&chart.get(1).remainingMinutes==null&&chart.get(2).gap&&chart.get(3).gap,"Missing ETA and explicit gaps never bridge estimate lines");
        List<ChargeEstimateHistory.Point> limits=ChargeEstimateHistory.build(unavailableSession,true);
        near(5,limits.get(1).remainingMinutes,"Valid cutoff estimate survives missing full prediction");
        check(limits.get(1).actualRemainingMinutes==null,"Lower cutoff is never compared with eventual 100 percent");
        check(unavailableSession.observedCompleteMono==null,"Manual stop is not an observed finish");
        ChargeSessionStore.Estimate invalid=new ChargeSessionStore.Estimate("v",Double.NaN,Double.POSITIVE_INFINITY,101d,ChargeSessionStore.Source.PERSONAL,ChargeSessionStore.Source.PERSONAL,9);
        check(invalid.remainingMinutesToFull==null&&invalid.remainingMinutesToLimit==null&&invalid.targetPercent==null&&invalid.fullSource==null&&invalid.learnedSessions==0,"Non-finite snapshots sanitized without invented learning metadata");
        invalid=new ChargeSessionStore.Estimate("v",-1d,10d,null,ChargeSessionStore.Source.PERSONAL,ChargeSessionStore.Source.CURRENT_RATE,9);
        check(invalid.remainingMinutesToFull==null&&invalid.remainingMinutesToLimit==null,"Negative or targetless predictions unavailable");
        check(estimate(1d,1d,.0000001).targetPercent==.0000001,"Every valid positive target is preserved exactly");
        boolean rejected=false;try{new ChargeSessionStore.Estimate("bad,version",1d,1d,100d,null,null,0);}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"Version cannot inject CSV/JSON content");

        File legacyDir=Files.createTempDirectory("charge-legacy-").toFile();
        journal(legacyDir,"charge-v1",row(0,98,true)+"\n"+row(10000,99,false)+"\n"+row(20000,100,false)+"\n","COMPLETE");
        ChargeSessionStore legacy=new ChargeSessionStore(legacyDir);ChargeSessionStore.HistorySession old=only(legacy);
        check(old.points.get(0).estimate==null,"Legacy history is not silently replayed through today's estimator");
        check(old.observedCompleteMono==MONO+20000,"Genuine old observed completion stays available");
        chart=ChargeEstimateHistory.build(old,false);
        check(chart.get(0).remainingMinutes==null,"Legacy graph does not fabricate predictions");
        check(chart.get(1).gap&&!chart.get(1).actualGap,"Legacy actual countdown continuous even without saved prediction");
        export=new StringWriter();legacy.exportJson("one",export);check(export.toString().contains("\"estimate\":null"),"Legacy exports identify missing estimate metadata");

        File cutoffDir=Files.createTempDirectory("charge-cutoff-comparison-").toFile();
        journal(cutoffDir,"charge-v2",row(0,99,true)+prediction()+"\n"+row(10000,100,false)+prediction()+"\n","COMPLETE");
        ChargeSessionStore.HistorySession cutoffSession=only(new ChargeSessionStore(cutoffDir));
        check(ChargeEstimateHistory.build(cutoffSession,false).get(0).actualRemainingMinutes!=null,"Observed100 completion can compare full prediction");
        check(ChargeEstimateHistory.build(cutoffSession,true).get(0).actualRemainingMinutes==null,"Full completion cannot stand in for earlier configured80 cutoff");

        for(String bad:new String[]{",broken,NaN,2.5,80.0,STOCK_REFERENCE,CURRENT_RATE,0",",broken,12.0,2.5,80.0,BOGUS,CURRENT_RATE,0",",broken,12.0,2.5,80.0,STOCK_REFERENCE,CURRENT_RATE,-1"}){
            File dir=Files.createTempDirectory("charge-malformed-prediction-").toFile();journal(dir,"charge-v2",row(0,50,true)+bad+"\n","STOPPED");
            ChargeSessionStore malformed=new ChargeSessionStore(dir);check(only(malformed).points.get(0).estimate==null,"Malformed optional prediction does not remove valid raw sample");
        }
        for(String end:new String[]{"STOPPED","CHARGING_ENDED","DISCONNECTED","INTERRUPTED","RECOVERY_TIMEOUT"}){
            File dir=Files.createTempDirectory("charge-incomplete-").toFile();journal(dir,"charge-v2",row(0,99,true)+prediction()+"\n"+row(10000,100,false)+prediction()+"\n",end);
            check(only(new ChargeSessionStore(dir)).observedCompleteMono==null,"Stop reason "+end+" cannot claim exact completed charge");
        }
        for(String rows:new String[]{row(0,98,true)+"\n"+row(10000,99,false)+"\n",row(0,99,true)+"\n"+row(50000,100,true)+"\n",row(0,99,true)+"\n"+row(50000,100,false)+"\n",row(0,100,true)+"\n"+row(10000,100,false)+"\n",row(10000,99,true)+"\n"+row(0,100,false)+"\n"}){
            File dir=Files.createTempDirectory("charge-unobserved-finish-").toFile();journal(dir,"charge-v1",rows,"COMPLETE");
            check(only(new ChargeSessionStore(dir)).observedCompleteMono==null,"Missing, gap-crossing, repeated or invalid-clock endpoint is not exact completion");
        }
        ChargeSessionStore terminalGap=store();terminalGap.start("one",ChargeSessionStore.Charger.STOCK,WALL,MONO);terminalGap.sample(frame(0,99,0,false));terminalGap.markGap();terminalGap.sampleCompletion(frame(10000,100,0,true));terminalGap.stop(ChargeSessionStore.Stop.COMPLETE);
        check(only(terminalGap).observedCompleteMono==null,"Final reconnect at 100 percent lacks observed exact finish");
        ChargeSessionStore overflow=store();overflow.start("one",ChargeSessionStore.Charger.STOCK,WALL,MONO);overflow.sample(frame(0,50,0,false),estimate(Double.MAX_VALUE,null,null));overflow.stop(ChargeSessionStore.Stop.STOPPED);
        check(ChargeEstimateHistory.build(only(overflow),false).get(0).predictedFinishWallMs==null,"Huge saved duration cannot overflow calendar timestamp");

        ChargingEstimate.Value charging=new ChargingEstimate.Value(1d,true,MONO), volts=new ChargingEstimate.Value(52d,true,MONO), amps=new ChargingEstimate.Value(-2d,true,MONO);
        ChargingEstimate.Value soc=new ChargingEstimate.Value(50d,true,MONO), capacity=new ChargingEstimate.Value(14.5d,true,MONO), cutoff=new ChargingEstimate.Value(80d,true,MONO);
        ChargingEstimate.Result rate=ChargingEstimate.calculate(charging,volts,amps,soc,capacity,cutoff);
        ChargeSessionStore.Estimate captured=ChargingEstimate.recordingSnapshot(rate);
        near(rate.remainingMinutesToFull,captured.remainingMinutesToFull,"Factory captures exact current-rate result");
        check(captured.fullSource==ChargeSessionStore.Source.CURRENT_RATE&&captured.targetPercent==80d,"Factory retains source and selected target");
        ChargingEstimate.Result reference=ChargingEstimate.forScooter(rate,soc,capacity,Ut5MaxStockChargeReference.PRODUCT_ID,ChargeSessionStore.Charger.STOCK,null);
        captured=ChargingEstimate.recordingSnapshot(reference);
        check(captured.fullSource==ChargeSessionStore.Source.STOCK_REFERENCE,"Factory labels bundled reference accurately");
        near(reference.remainingMinutesToFull,captured.remainingMinutesToFull,"Factory does not recalculate reference");
        File rawDir=Files.createTempDirectory("charge-raw-learning-").toFile(), predictionDir=Files.createTempDirectory("charge-prediction-learning-").toFile();
        StringBuilder rawRows=new StringBuilder(), predictedRows=new StringBuilder();
        for(int i=0;i<=120;i++){
            String raw=row(i*10000L,40+i/6,i==0);rawRows.append(raw).append('\n');
            predictedRows.append(raw).append(",version-999,999999.123,888888.321,100.0,PERSONAL,PERSONAL,20\n");
        }
        for(int i=0;i<2;i++){journal(rawDir,"charge-v1",rawRows.toString(),"STOPPED");journal(predictionDir,"charge-v2",predictedRows.toString(),"STOPPED");}
        ChargeSessionStore.Curve rawCurve=new ChargeSessionStore(rawDir).snapshot().curves.get("one:STOCK");
        ChargeSessionStore.Curve predictedCurve=new ChargeSessionStore(predictionDir).snapshot().curves.get("one:STOCK");
        near(rawCurve.estimate(50,60).minutes,predictedCurve.estimate(50,60).minutes,"Saved guesses never feed back into learned measurements");
        check(rawCurve.qualifiedSessions==predictedCurve.qualifiedSessions,"Estimate provenance does not change learning qualification");
        captured=ChargingEstimate.recordingSnapshot(ChargingEstimate.withObservedCurve(rate,soc,predictedCurve));
        check(captured.limitSource==ChargeSessionStore.Source.CURRENT_RATE,"Missing learned cutoff coverage does not invent learned source");

        ChargingEstimate.Result partialLearned=ChargingEstimate.withObservedCurve(ChargingEstimate.calculate(charging,volts,amps,soc,capacity,new ChargingEstimate.Value(55d,true,MONO)),soc,predictedCurve);
        captured=ChargingEstimate.recordingSnapshot(partialLearned);
        check(captured.fullSource==ChargeSessionStore.Source.CURRENT_RATE&&captured.limitSource==ChargeSessionStore.Source.PERSONAL&&captured.learnedSessions==2,"Mixed personal and current-rate sources preserve independent provenance");

        File boundedDir=Files.createTempDirectory("charge-estimate-bounds-").toFile();ChargeSessionStore bounded=new ChargeSessionStore(boundedDir);bounded.start("one",ChargeSessionStore.Charger.STOCK,WALL,MONO);
        ChargeSessionStore.Estimate maximum=new ChargeSessionStore.Estimate("v123456789012345678901234567890123456789012345678901234567890123",Double.MAX_VALUE,Double.MAX_VALUE,100d,ChargeSessionStore.Source.PERSONAL,ChargeSessionStore.Source.PERSONAL,20);
        for(int i=0;i<ChargeSessionStore.MAX_SAMPLES;i++)check(bounded.sample(frame(i*9000L,100,0,false),maximum),"Full 6500-sample budget still fits expanded metadata");
        check(!bounded.sample(frame(ChargeSessionStore.MAX_SAMPLES*9000L,100,0,false),maximum)&&!bounded.active(),"Sample limit remains enforced");
        check(only(bounded).points.size()==ChargeSessionStore.MAX_SAMPLES,"All retained samples round-trip");
        check(boundedDir.listFiles()[0].length()<ChargeSessionStore.MAX_FILE_BYTES,"Expanded journal bounded below file cap");
        for(int i=0;i<25;i++){bounded.start("one",ChargeSessionStore.Charger.STOCK,WALL+i,MONO+i);bounded.stop(ChargeSessionStore.Stop.STOPPED);}
        check(boundedDir.listFiles().length==ChargeSessionStore.MAX_SESSIONS,"Expanded metadata preserves session count retention");
        System.out.println("ChargeEstimateHistory: "+checks+" checks passed");
    }
}
