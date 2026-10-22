package local.navee.offlinelock;

/** Real first-frame failures, conservative composition and unmodified matched/taper scope. */
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
    public static void main(String[] args)throws Exception{
        ChargeSessionStore.Curve history=StockVoltageReplayTest.curve(StockVoltageReplayTrace.STOCK_LATE,
                StockVoltageReplayTrace.STOCK_BULK,StockVoltageReplayTrace.WARM_PHYSICAL_CHARGE);
        // Retrospective selection checks. No trace is added to the measured reference
        // curves; these starts cover two scooters and different stock charger currents.
        for(double[] row:new double[][]{{60,49.411,2.3,179.544233},{22,46.971,2.3,325.798033},{51,48.696,2.3,210.015733},{1,45.496,2.43,381.988}}){
            for(ChargeSessionStore.Curve personal:new ChargeSessionStore.Curve[]{null,history}){
                ChargingEstimate.Result result=estimate(row[0],row[1],row[2],100d,personal);
                check(result.remainingMinutesToFull+10>=row[3],"Observed starts fit within the existing ten-minute scheduling buffer");
                check(Math.abs(result.remainingMinutesToFull-row[3])<=12,"First full guesses remain within twelve minutes in these retrospective cases");
                near(result.remainingMinutesToFull,result.remainingMinutesToLimit,0,"Full cutoff uses full timing");
            }
        }
        // Regression: the previous history result at 55% was 173.44 min, but
        // this independently recorded charge needed another195.28 min.
        ChargingEstimate.Result noHistory=estimate(55,49.194,2.14,100d,null);
        ChargingEstimate.Result withHistory=estimate(55,49.194,2.14,100d,history);
        check(withHistory.remainingMinutesToFull>=195.2799,"Early personal history cannot recreate the22-minute optimistic miss");
        near(noHistory.remainingMinutesToFull,withHistory.remainingMinutesToFull,0,"Shorter personal fallback does not erase conservative bulk");
        check(withHistory.referenceToFull&&!withHistory.learnedToFull,"Source reports the selected stock estimate");
        near(125.88136488461544,estimate(70,50.979,1.96,100d,history).remainingMinutesToFull,.000001,
                "A confident voltage match keeps its precise prediction");
        ChargeSessionStore shortStore=ChargeSessionStoreTest.store(),longStore=ChargeSessionStoreTest.store();
        for(int i=0;i<2;i++){
            ChargeSessionStoreTest.session(shortStore,"one",ChargeSessionStore.Charger.STOCK,40,100,60000,false,false);
            ChargeSessionStoreTest.session(longStore,"one",ChargeSessionStore.Charger.STOCK,40,100,300000,false,false);
        }
        ChargeSessionStore.Curve shortHistory=ChargeSessionStoreTest.curve(shortStore,"one",ChargeSessionStore.Charger.STOCK);
        ChargeSessionStore.Curve longHistory=ChargeSessionStoreTest.curve(longStore,"one",ChargeSessionStore.Charger.STOCK);
        ChargingEstimate.Result longer=estimate(51,48.696,2,100d,longHistory);
        check(longer.learnedToFull&&longer.learnedToLimit&&longer.learnedSessions==2
                &&longer.status.contains("Time to full uses longer personal history"),"Longer personal full timing identifies its actual source");
        ChargingEstimate.Result blended=estimate(84,54,2,100d,shortHistory);
        check(blended.referenceToFull&&blended.learnedSessions==2&&blended.status.contains("blends with personal history"),
                "A fading history floor discloses its personal contribution");
        near(estimate(89,54,2,100d,shortHistory).remainingMinutesToFull,
                estimate(88.999999,54,2,100d,shortHistory).remainingMinutesToFull,.0001,
                "Shorter personal history has no cliff where the full-charge floor ends at89%");
        ChargingEstimate.Result voltageBlend=estimate(57,49.45,2.07,100d,longHistory);
        check(voltageBlend.stockVoltageToFull&&voltageBlend.learnedSessions==2
                &&voltageBlend.status.contains("blends with personal history"),"Partial voltage match discloses longer personal fallback");
        ChargingEstimate.Result personalCutoff=estimate(51,48,2.3,80d,shortHistory);
        check(personalCutoff.learnedToLimit&&personalCutoff.status.contains("Time to the limit uses personal history")
                &&!personalCutoff.status.contains("Time to the limit uses the current rate"),"Personal cutoff removes the superseded rate-source description");
        for(double[] pair:new double[][]{{49.38,49.75},{53.74,53.85}}){
            Double previous=null;
            for(double v=pair[0];v<=pair[1];v+=.0001){
                double now=estimate(57,v,2.07,100d,history).remainingMinutesToFull;
                if(previous!=null)check(Math.abs(now-previous)<.5,"Composed fallback enters/exits measured voltage smoothly");
                previous=now;
            }
        }
        for(Double target:new Double[]{null,40d,51d,52d,80d,89d,95d,100d}){
            ChargingEstimate.Result result=estimate(51,48.696,2.3,target,history);
            if(target==null)check(result.remainingMinutesToLimit==null,"Unknown cutoff stays unknown");
            else{
                check(result.remainingMinutesToLimit>=0&&result.remainingMinutesToLimit<=result.remainingMinutesToFull,"Cutoff ordered within full time");
                if(target<=51)near(0,result.remainingMinutesToLimit,0,"Reached cutoff remains zero");
            }
        }
        near(15.3*.29/2.3*60,estimate(51,48.696,2.3,80d,null).remainingMinutesToLimit,.00001,
                "Lower cutoffs preserve their existing independent method");
        for(double amps:new double[]{1.7,1.94,2,2.01,2.3}){
            ChargingEstimate.Result result=estimate(51,48.696,amps,100d,null);
            check(result.inputAmps==amps,"Display still shows actual current, never the planning assumption");
            if(amps<=2)near(Ut5MaxStockChargeReference.estimate(51,100,15.3,amps,48.696*amps),result.remainingMinutesToFull,0,"Slower live rate is preserved");
        }
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
