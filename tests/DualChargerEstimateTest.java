package com.jjricks.volt;

import java.io.File;
import java.nio.file.Files;

/** Selection, model composition and stored provenance, independent of Android views. */
public final class DualChargerEstimateTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void near(double want,Double actual,double tolerance,String message){
        check(actual!=null&&Math.abs(actual-want)<=tolerance,message+": "+actual);
    }
    private static ChargingEstimate.Value live(double value){return new ChargingEstimate.Value(value,true,1000);}
    private static ChargingEstimate.Result base(double soc,double volts,double amps,Double target){
        return ChargingEstimate.calculate(live(1),live(volts),live(-amps),live(soc),live(15.3),
                new ChargingEstimate.Value(target,true,1000));
    }
    private static ChargingEstimate.Result estimate(double soc,double volts,double amps,Double target,int pid,
            ChargeSessionStore.Charger family,ChargeSessionStore.Curve personal){
        return ChargingEstimate.forScooter(base(soc,volts,amps,target),live(soc),live(15.3),pid,family,personal);
    }
    public static void main(String[] args)throws Exception{
        ChargeSessionStore.Charger fast=ChargeSessionStore.Charger.HIGH_SPEED,stock=ChargeSessionStore.Charger.STOCK;
        ChargingEstimate.Result unknown=base(97,54.318,2.21,100d);
        check(ChargingEstimate.forScooter(unknown,live(97),live(15.3),2538,null,null)==unknown,
                "Ambiguous late connection cannot silently become a stock reference");
        ChargerDetection detection=new ChargerDetection();
        for(int i=0;i<3;i++)detection.observe("one",2538,true,60d,51.71,10.09,1000+i*2000,1000+i*2000);
        check(detection.selected("one",5000)==fast,"Fresh fast bulk frames select high-speed");
        ChargingEstimate.Result bulk=estimate(60,51.71,10.09,100d,2538,detection.selected("one",5000),null);
        check(bulk.referenceToFull&&!bulk.learnedToFull&&bulk.referenceCharger==fast,"Fast default needs no personal recordings");
        near(49.25,bulk.remainingMinutesToFull,1,"Bulk includes the observed future taper");
        ChargeSessionStore.Estimate saved=ChargingEstimate.recordingSnapshot(bulk);
        check(saved.fullSource==ChargeSessionStore.Source.FAST_REFERENCE&&saved.limitSource==ChargeSessionStore.Source.FAST_REFERENCE,
                "Fast reference does not masquerade as stock or personal evidence");
        check(saved.version.equals("ut5-stock-pack-v6"),"Algorithm changes carry a new capture version");
        detection.observe("one",2538,true,97d,54.318,2.21,7000,7000);
        check(detection.selected("one",7000)==fast,"Known fast taper cannot become stock at 120W");
        ChargingEstimate.Result late=estimate(97,54.318,2.21,100d,2538,detection.selected("one",7000),null);
        near(16.55,late.remainingMinutesToFull,1.5,"Fast family uses its own late-charge timing");
        detection.observe("one",2538,true,97d,54.318,0d,30000,30000);
        detection.observe("one",2538,true,97d,54.318,2.21,40000,40000);
        check(detection.selected("one",40000)==fast,"A reconnect and an unusable frame within this charge keep the family");
        detection.observe("one",2538,false,97d,null,null,42000,42000);
        detection.observe("one",2538,true,97d,54.318,2.21,44000,44000);
        check(detection.selected("one",44000)==null,"A new charge in the overlap returns unknown");
        check(!estimate(60,52,2,100d,2538,fast,null).referenceToFull,"Fast manual override still checks compatible power");
        check(!estimate(60,51.71,10.09,100d,2538,stock,null).referenceToFull,"Stock manual override rejects fast power");
        check(!estimate(60,51.71,10.09,100d,9999,fast,null).referenceToFull,"Other models keep their live fallback");
        ChargingEstimate.Result noCutoff=estimate(60,51.71,10.09,null,2538,fast,null);
        check(noCutoff.referenceToFull&&noCutoff.remainingMinutesToLimit==null&&!noCutoff.referenceToLimit,
                "Reference never invents a missing charge limit");
        ChargingEstimate.Result reached=estimate(80,54.027,4.95,70d,2538,fast,null);
        near(0,reached.remainingMinutesToLimit,0,"An already reached cutoff remains zero");
        check(reached.status.contains("higher charge limit"),"Full ETA explains lower configured cutoff");
        ChargingEstimate.Result stale=ChargingEstimate.calculate(live(1),live(51.71),new ChargingEstimate.Value(-10.09,false,1000),live(60),live(15.3),live(100));
        check(ChargingEstimate.forScooter(stale,live(60),live(15.3),2538,fast,null)==stale,"Fast curve cannot bypass stale inputs");

        ChargeSessionStore personalStore=ChargeSessionStoreTest.store();
        for(int i=0;i<2;i++)ChargeSessionStoreTest.session(personalStore,"one",fast,40,80,60000,false,false,soc->10.09);
        ChargeSessionStore.Curve personal=ChargeSessionStoreTest.curve(personalStore,"one",fast);
        ChargingEstimate.Result mixed=estimate(60,51.71,10.09,75d,2538,fast,personal);
        check(mixed.learnedToLimit&&!mixed.learnedToFull&&mixed.referenceToFull&&mixed.referenceCharger==fast,
                "Partial personal evidence refines only its covered target");
        near(15,mixed.remainingMinutesToLimit,.0001,"Personal cutoff takes precedence over default");
        near(bulk.remainingMinutesToFull,mixed.remainingMinutesToFull,.0001,"Uncovered full target preserves bundled taper");
        saved=ChargingEstimate.recordingSnapshot(mixed);
        check(saved.fullSource==ChargeSessionStore.Source.FAST_REFERENCE&&saved.limitSource==ChargeSessionStore.Source.PERSONAL,
                "Each saved target retains its own source");
        check(!estimate(60,51.71,10.09,75d,2538,stock,personal).learnedToLimit,"Other-family personal evidence stays isolated");
        File dir=Files.createTempDirectory("dual-charge-provenance-").toFile();
        ChargeSessionStore journal=new ChargeSessionStore(dir);
        journal.start("one",fast,ChargeSessionStoreTest.WALL,ChargeSessionStoreTest.MONO);
        journal.sample(ChargeSessionStoreTest.frame(ChargeSessionStoreTest.MONO,60),saved);
        journal.stop(ChargeSessionStore.Stop.STOPPED);
        ChargeSessionStore.Estimate loaded=new ChargeSessionStore(dir).recordings("one").get(0).points.get(0).estimate;
        check(loaded.fullSource==saved.fullSource&&loaded.limitSource==saved.limitSource,"Fast provenance survives journal reload");
        near(saved.remainingMinutesToFull,loaded.remainingMinutesToFull,0,"Reload preserves original prediction");
        System.out.println("DualChargerEstimate: "+checks+" checks passed");
    }
}
