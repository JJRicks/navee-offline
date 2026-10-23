package local.navee.offlinelock;

/** First guesses, the 55% regression and composition limits for the standard-charger pack model. */
public final class StockStartupReferenceTest {
    private static int checks;
    private static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
    private static void near(double expected,Double got,double tolerance,String why){check(got!=null&&Math.abs(expected-got)<=tolerance,why+": "+got);}
    private static ChargingEstimate.Value live(Double value){return new ChargingEstimate.Value(value,true,1000);}
    private static ChargingEstimate.Result base(double soc,double volts,double amps,Double target,double ah){
        return ChargingEstimate.calculate(live(1d),live(volts),live(-amps),live(soc),live(ah),live(target));
    }
    private static ChargingEstimate.Result estimate(double soc,double volts,double amps,Double target,ChargeSessionStore.Curve history){
        return ChargingEstimate.forScooter(base(soc,volts,amps,target,15.3),live(soc),live(15.3),2538,ChargeSessionStore.Charger.STOCK,history);
    }
    private static ChargingEstimate.Result estimate(double soc,double volts,double amps,Double target,ChargeSessionStore.Curve history,
            ChargeProgressTracker.Progress progress){
        return ChargingEstimate.forScooter(base(soc,volts,amps,target,15.3),live(soc),live(15.3),2538,ChargeSessionStore.Charger.STOCK,history,
                null,null,0,0,progress);
    }
    /** A charge whose start was seen minutesAgo, at startSoc. */
    private static ChargeProgressTracker.Progress settled(double minutesAgo,double startSoc){
        ChargeProgressTracker tracker=new ChargeProgressTracker();long start=10_000_000L;
        tracker.observe("p",false,startSoc,0d,start-60_000);tracker.observe("p",true,startSoc,2d,start);
        return tracker.progress("p",start+(long)(minutesAgo*60_000));
    }
    public static void main(String[] args)throws Exception{
        ChargeSessionStore.Curve history=StockVoltageReplayTest.curve(StockVoltageReplayTrace.STOCK_LATE,
                StockVoltageReplayTrace.STOCK_BULK,StockVoltageReplayTrace.WARM_PHYSICAL_CHARGE);
        // Retrospective first guesses (estimator v6). No charge start is supplied, so the model
        // assumes charging just began: the most warm-up decay is still ahead.
        for(double[] row:new double[][]{{60,49.411,2.3,179.544233},{22,46.971,2.3,325.798033},{51,48.696,2.3,210.015733},{1,45.496,2.43,381.988}}){
            for(ChargeSessionStore.Curve personal:new ChargeSessionStore.Curve[]{null,history}){
                ChargingEstimate.Result result=estimate(row[0],row[1],row[2],100d,personal);
                check(result.stockPackModel,"Standard bulk charging uses the pack model");
                check(Math.abs(result.remainingMinutesToFull-row[3])<=12,"First full guesses remain within twelve minutes in these retrospective cases");
                check(result.planningMinutesToFull>=row[3],"The cautious plan for these first guesses is never late");
                near(result.remainingMinutesToFull,result.remainingMinutesToLimit,0,"Full cutoff uses full timing");
                near(result.planningMinutesToFull,result.planningMinutesToLimit,0,"Full cutoff uses full planning time");
            }
        }
        // Regression: v4 with history said 173.44 min at 55%, but this charge needed 195.28 more.
        ChargingEstimate.Result noHistory=estimate(55,49.194,2.14,100d,null);
        ChargingEstimate.Result withHistory=estimate(55,49.194,2.14,100d,history);
        check(withHistory.remainingMinutesToFull>=195.2799,"Early personal history cannot recreate the 22-minute optimistic miss");
        near(noHistory.remainingMinutesToFull,withHistory.remainingMinutesToFull,0,"Below 89% percentage-only history no longer changes the estimate");
        check(withHistory.referenceToFull&&!withHistory.learnedToFull&&withHistory.learnedSessions==0,"Source reports the bundled stock model");
        // With a known settled charge the voltage-led estimate matches the recorded 70% point.
        near(125.88136488461544,estimate(70,50.979,1.96,100d,history,settled(120,60)).remainingMinutesToFull,1,
                "A settled mid-charge reading predicts the observed remaining time");
        ChargeSessionStore shortStore=ChargeSessionStoreTest.store(),longStore=ChargeSessionStoreTest.store();
        for(int i=0;i<2;i++){
            ChargeSessionStoreTest.session(shortStore,"one",ChargeSessionStore.Charger.STOCK,40,100,60000,false,false);
            ChargeSessionStoreTest.session(longStore,"one",ChargeSessionStore.Charger.STOCK,40,100,300000,false,false);
        }
        ChargeSessionStore.Curve shortHistory=ChargeSessionStoreTest.curve(shortStore,"one",ChargeSessionStore.Charger.STOCK);
        ChargeSessionStore.Curve longHistory=ChargeSessionStoreTest.curve(longStore,"one",ChargeSessionStore.Charger.STOCK);
        for(ChargeSessionStore.Curve personal:new ChargeSessionStore.Curve[]{shortHistory,longHistory}){
            ChargingEstimate.Result bulk=estimate(51,48.696,2,100d,personal);
            check(bulk.stockPackModel&&!bulk.learnedToFull&&!bulk.learnedToLimit&&bulk.learnedSessions==0
                    &&bulk.remainingMinutesToFull.equals(estimate(51,48.696,2,100d,null).remainingMinutesToFull),
                    "Shorter or longer percentage-only history cannot move a bulk-stage estimate");
            ChargingEstimate.Result cutoff=estimate(51,48,2.3,80d,personal);
            check(cutoff.stockPackModel&&cutoff.referenceToLimit&&!cutoff.learnedToLimit&&!cutoff.status.contains("current rate"),
                    "A bulk-stage cutoff reports the pack model, not a superseded source");
        }
        // The 89% handover to the finishing reference stays bounded on a realistic settled charge.
        double below=estimate(88,53.3,1.94,100d,null,settled(240,20)).remainingMinutesToFull;
        double above=estimate(89,53.3,1.94,100d,null,settled(240,20)).remainingMinutesToFull;
        check(Math.abs(above-below)<=6,"The 89% handover changes the estimate by at most six minutes: "+(above-below));
        for(Double target:new Double[]{null,40d,51d,52d,80d,89d,95d,100d}){
            ChargingEstimate.Result result=estimate(51,48.696,2.3,target,history);
            if(target==null)check(result.remainingMinutesToLimit==null&&result.planningMinutesToLimit==null,"Unknown cutoff stays unknown");
            else{
                check(result.remainingMinutesToLimit>=0&&result.remainingMinutesToLimit<=result.remainingMinutesToFull,"Cutoff ordered within full time");
                check(result.planningMinutesToLimit>=result.remainingMinutesToLimit,"A plan is never shorter than the displayed cutoff time");
                if(target<=51)near(0,result.remainingMinutesToLimit,0,"Reached cutoff remains zero");
            }
        }
        near(15.3*.29/2.0*60,estimate(51,48.696,2.0,80d,null,settled(240,20)).remainingMinutesToLimit,.01,
                "A settled charge counts a lower cutoff at its live rate");
        check(estimate(51,48.696,2.3,80d,null).remainingMinutesToLimit>15.3*.29/2.3*60,
                "A just-started charge includes its expected warm-up current decay");
        for(double amps:new double[]{1.7,1.94,2,2.01,2.3}){
            ChargingEstimate.Result result=estimate(51,48.696,amps,100d,null);
            check(result.inputAmps==amps,"Display still shows actual current, never the planning assumption");
        }
        check(estimate(51,48.696,1.7,100d,null).remainingMinutesToFull>estimate(51,48.696,2.3,100d,null).remainingMinutesToFull,
                "A slower live rate takes longer");
        for(int pid:new int[]{0,1,2539}){
            ChargingEstimate.Result b=base(51,48.696,2.3,100d,15.3);
            check(ChargingEstimate.forScooter(b,live(51d),live(15.3),pid,ChargeSessionStore.Charger.STOCK,null)==b,"Unknown models unchanged");
        }
        ChargingEstimate.Result b=base(51,48.696,2.3,100d,15.3);
        check(ChargingEstimate.forScooter(b,live(51d),live(15.3),2538,null,null)==b,"Unknown-family dashboard fallback unchanged");
        ChargingEstimate.Result otherCapacity=ChargingEstimate.forScooter(base(51,48.696,2.3,100d,25),live(51d),live(25d),2538,ChargeSessionStore.Charger.STOCK,null);
        near(25*.38/2.3*60+(51.2855+49.88757)/2,otherCapacity.remainingMinutesToFull,.000001,"Different rated capacity does not acquire calibrated current cap");
        System.out.println("StockStartupReference: "+checks+" checks passed");
    }
}
