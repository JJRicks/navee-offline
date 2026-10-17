package local.navee.offlinelock;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Replay a deidentified observed finish, including its rapid SOC rise and long 99% taper. */
public final class CompletedChargeEstimateTest {
    private static int checks;
    private static final long WALL=1_800_000_000_000L, MONO=1_000_000L;
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static void range(Double value,double low,double high,String message){check(value!=null&&value>=low&&value<=high,message+": "+value);}
    private static ChargeSessionStore.Value value(double value,long mono){return new ChargeSessionStore.Value(value,mono,0x72);}
    private static List<double[]> trace(){
        List<double[]> points=new ArrayList<>();
        for(String line:OBSERVED.split("\n")){
            String[] fields=line.split(",");double[] point=new double[4];
            for(int i=0;i<4;i++)point[i]=Double.parseDouble(fields[i]);
            points.add(point);
        }
        return points;
    }
    private static ChargeSessionStore store()throws Exception{return new ChargeSessionStore(Files.createTempDirectory("finishing-charge-test-").toFile());}
    private static void record(ChargeSessionStore store,String profile,ChargeSessionStore.Charger charger,List<double[]> points,
            ChargeSessionStore.Stop stop,int gap,int clockJump)throws Exception{
        record(store,profile,charger,points,stop,new int[]{gap},clockJump);
    }
    private static void record(ChargeSessionStore store,String profile,ChargeSessionStore.Charger charger,List<double[]> points,
            ChargeSessionStore.Stop stop,int[] gaps,int clockJump)throws Exception{
        store.start(profile,charger,WALL,MONO);
        for(int i=0;i<points.size();i++){
            double[] p=points.get(i);long mono=MONO+(long)p[0];
            for(int gap:gaps)if(i==gap)store.markGap();
            ChargeSessionStore.Frame frame=new ChargeSessionStore.Frame(WALL+(long)p[0]+(clockJump>=0&&i>=clockJump?4000:0),mono,
                    value(p[1]==100?0:1,mono),value(p[1],mono),value(p[2],mono),value(p[3],mono),value(23,mono));
            if(p[1]==100)check(store.sampleCompletion(frame),"Final measurement saved");
            else check(store.sample(frame),"Observed paired measurement saved");
        }
        store.stop(stop);
    }
    private static List<double[]> steadyTrace(int samplesPerPercent,long interval){
        List<double[]> points=new ArrayList<>();
        for(int i=0;i<=40*samplesPerPercent;i++)
            points.add(new double[]{i*interval,60+i/samplesPerPercent,54,-2});
        return points;
    }
    private static ChargeSessionStore.Curve curve(ChargeSessionStore store)throws Exception{
        return store.snapshot().curves.get(ChargeSessionStore.key("sample",ChargeSessionStore.Charger.STOCK));
    }
    private static ChargingEstimate.Result estimate(ChargeSessionStore.Curve curve,double soc,double watts,Double cutoff){
        // Representative paired voltages from the recorded 99% stages, rather than an
        // arbitrary fixed voltage that would contradict the phase being exercised.
        double volts=soc==99?(watts>=100?53.814:watts>=70?54.172:watts>=45?54.335:54.374):54;
        ChargingEstimate.Result baseline=ChargingEstimate.calculate(new ChargingEstimate.Value(1d,true,100),
                new ChargingEstimate.Value(volts,true,100),new ChargingEstimate.Value(-watts/volts,true,100),
                new ChargingEstimate.Value(soc,true,100),new ChargingEstimate.Value(15.3,true,100),
                new ChargingEstimate.Value(cutoff,true,100));
        return ChargingEstimate.withObservedCurve(baseline,new ChargingEstimate.Value(soc,true,100),curve);
    }
    public static void main(String[] args)throws Exception{
        ChargeSessionStore store=store();record(store,"sample",ChargeSessionStore.Charger.STOCK,trace(),ChargeSessionStore.Stop.COMPLETE,-1,-1);
        ChargeSessionStore.Curve one=curve(store);
        check(one!=null&&one.qualifiedSessions==1,"One observed complete finish is retained");
        ChargingEstimate.Result at95=estimate(one,95,114d,100d);
        range(at95.remainingMinutesToFull,38,42,"95% includes the observed approximately 41-minute finish");
        check(at95.learnedToFull&&at95.learnedToLimit&&at95.learnedSessions==1&&at95.status.contains("Provisional")&&at95.status.contains("matched battery level and charging power"),"Single prior charge is explicitly provisional and matched to live readings");
        range(estimate(one,92,114,100d).remainingMinutesToFull,41,43,"A genuine 20-second SOC interval does not erase finishing coverage");
        double early=estimate(one,99,110,100d).remainingMinutesToFull;
        double middle=estimate(one,99,88,100d).remainingMinutesToFull;
        double late=estimate(one,99,48,100d).remainingMinutesToFull;
        range(early,22,27,"Early 99% includes the observed long plateau instead of approximately 4.5 minutes");
        range(middle,13,18,"Observed taper identifies the middle of the 99% plateau");
        range(late,3,8,"Observed taper reduces ETA while SOC remains 99%");
        range(estimate(one,99,39,100d).remainingMinutesToFull,0,3,"End of observed taper is close to reported full");
        check(early>middle&&middle>late,"Power-stage estimates progress within one SOC value");
        check(!estimate(one,88,120,100d).learnedToFull,"Initial partially observed SOC interval is not extrapolated");
        check(!estimate(one,50,100,100d).learnedToFull,"No below-range bulk extrapolation");
        for(double watts:new double[]{10,25,250,1000})check(!estimate(one,99,watts,100d).learnedToFull,"Incompatible charger/power preserves fallback");
        check(!estimate(one,99,0,100d).learnedToFull,"Zero-current freshness/baseline gate remains in force");
        ChargingEstimate.Result lower=estimate(one,95,114,80d);
        check(lower.learnedToFull&&!lower.learnedToLimit&&lower.remainingMinutesToLimit==0&&lower.status.contains("higher charge limit"),"A full-finish curve does not invent lower-cutoff evidence");
        ChargingEstimate.Result unknown=estimate(one,95,114,null);
        check(unknown.learnedToFull&&unknown.remainingMinutesToLimit==null&&unknown.status.contains("Cutoff unknown"),"Unknown cutoff remains explicit");
        ChargingEstimate.Value live=new ChargingEstimate.Value(1d,true,100);
        ChargingEstimate.Result stale=ChargingEstimate.calculate(live,new ChargingEstimate.Value(54d,true,100),new ChargingEstimate.Value(-2d,false,100),new ChargingEstimate.Value(95d,true,100),new ChargingEstimate.Value(15.3,true,100),new ChargingEstimate.Value(100d,true,100));
        check(ChargingEstimate.withObservedCurve(stale,new ChargingEstimate.Value(95d,true,100),one).remainingMinutesToFull==null,"Finishing history never bypasses stale electrical gate");
        record(store,"sample",ChargeSessionStore.Charger.STOCK,trace(),ChargeSessionStore.Stop.COMPLETE,-1,-1);
        ChargeSessionStore.Curve two=curve(store);
        List<double[]> nineBins=trace();nineBins.removeIf(point->point[1]<89);
        ChargeSessionStore continuousShortBins=store();
        for(int i=0;i<2;i++)record(continuousShortBins,"sample",ChargeSessionStore.Charger.STOCK,nineBins,ChargeSessionStore.Stop.COMPLETE,-1,-1);
        check(curve(continuousShortBins).estimate(95,96)!=null,"An uninterrupted finish with only nine eligible legacy bins retains its previous lower-cutoff evidence");
        check(estimate(two,99,48,100d).learnedSessions==2,"Two compatible complete finishes are counted");
        range(estimate(two,99,48,100d).remainingMinutesToFull,3,8,"Legacy SOC average cannot replace live taper progress after the second charge");
        for(ChargeSessionStore.Stop stop:new ChargeSessionStore.Stop[]{ChargeSessionStore.Stop.STOPPED,ChargeSessionStore.Stop.DISCONNECTED,ChargeSessionStore.Stop.CHARGING_ENDED,ChargeSessionStore.Stop.INTERRUPTED}){
            ChargeSessionStore incomplete=store();record(incomplete,"sample",ChargeSessionStore.Charger.STOCK,trace(),stop,-1,-1);
            check(!estimate(curve(incomplete),99,88,100d).learnedToFull,"A non-complete stop cannot supply finishing evidence");
        }
        ChargeSessionStore earlyGap=store();record(earlyGap,"sample",ChargeSessionStore.Charger.STOCK,trace(),ChargeSessionStore.Stop.COMPLETE,40,-1);
        ChargeSessionStore.Curve afterGap=curve(earlyGap);
        check(afterGap!=null&&!afterGap.hasFinishingCoverage(89)&&afterGap.hasFinishingCoverage(90),"An early gap retains only complete SOC bins after the next observed rise");
        range(estimate(afterGap,95,114,100d).remainingMinutesToFull,38,42,"A valid uninterrupted suffix preserves the observed finishing time");
        check(estimate(afterGap,95,114,100d).learnedToFull,"Earlier missing data does not erase a valid completed suffix");
        ChargeSessionStore multipleGaps=store();record(multipleGaps,"sample",ChargeSessionStore.Charger.STOCK,steadyTrace(6,10000),ChargeSessionStore.Stop.COMPLETE,new int[]{12,60},-1);
        ChargeSessionStore.Curve afterLastGap=curve(multipleGaps);
        check(afterLastGap!=null&&!afterLastGap.hasFinishingCoverage(70)&&afterLastGap.hasFinishingCoverage(71),"Gaps at 62% and 70% retain only the observed 71–100% suffix");
        check(estimate(afterLastGap,95,108,100d).learnedToFull,"The final complete suffix can provide a provisional estimate");
        List<double[]> sparseTrace=new ArrayList<>();
        for(int i=0;i<=18;i++)sparseTrace.add(new double[]{i*10000L,49+i/6,54,-2});
        for(int i=0;i<=66;i++)sparseTrace.add(new double[]{1200000L+i*10000L,89+i/6,54,-2});
        ChargeSessionStore sparse=store();
        for(int i=0;i<2;i++)record(sparse,"sample",ChargeSessionStore.Charger.STOCK,sparseTrace,ChargeSessionStore.Stop.COMPLETE,19,-1);
        ChargeSessionStore.Curve sparseCurve=curve(sparse);
        check(sparseCurve!=null&&sparseCurve.hasFinishingCoverage(90)&&!sparseCurve.hasFinishingCoverage(50),"A sparse recording retains its qualifying continuous finish only");
        check(sparseCurve.estimate(50,51)==null&&sparseCurve.powerMatch(50,108d)==ChargeSessionStore.Curve.PowerMatch.UNKNOWN,"Two recovered tails cannot qualify pre-gap timing or power bins");
        check(sparseCurve.estimateFinishing(95,108d,54d)!=null,"Clearing unqualified legacy bins preserves the valid finishing suffix");
        ChargeSessionStore shortSuffix=store();record(shortSuffix,"sample",ChargeSessionStore.Charger.STOCK,steadyTrace(2,20000),ChargeSessionStore.Stop.COMPLETE,20,-1);
        check(!estimate(curve(shortSuffix),95,108,100d).learnedToFull,"A long total session cannot qualify a suffix with fewer than 61 retained samples");
        ChargeSessionStore minimumSuffix=store();record(minimumSuffix,"sample",ChargeSessionStore.Charger.STOCK,steadyTrace(6,10000),ChargeSessionStore.Stop.COMPLETE,174,-1);
        check(estimate(curve(minimumSuffix),95,108,100d).learnedToFull,"A suffix with exactly 61 samples, 10 SOC points and 10 minutes qualifies");
        ChargeSessionStore gap=store();record(gap,"sample",ChargeSessionStore.Charger.STOCK,trace(),ChargeSessionStore.Stop.COMPLETE,100,-1);
        check(!estimate(curve(gap),99,88,100d).learnedToFull,"A late recording gap leaves too little SOC span for finishing evidence");
        ChargeSessionStore terminalGap=store();record(terminalGap,"sample",ChargeSessionStore.Charger.STOCK,trace(),ChargeSessionStore.Stop.COMPLETE,trace().size()-1,-1);
        check(!estimate(curve(terminalGap),99,88,100d).learnedToFull,"A gap at the 100% endpoint cannot establish an observed finish");
        ChargeSessionStore earlyClock=store();record(earlyClock,"sample",ChargeSessionStore.Charger.STOCK,trace(),ChargeSessionStore.Stop.COMPLETE,-1,40);
        ChargeSessionStore.Curve afterClock=curve(earlyClock);
        check(afterClock!=null&&!afterClock.hasFinishingCoverage(89)&&afterClock.hasFinishingCoverage(90),"A clock discontinuity resets coverage before a new complete SOC bin");
        check(estimate(afterClock,95,114,100d).learnedToFull,"A stable completed suffix after an early clock jump remains usable");
        ChargeSessionStore clock=store();record(clock,"sample",ChargeSessionStore.Charger.STOCK,trace(),ChargeSessionStore.Stop.COMPLETE,-1,100);
        check(!estimate(curve(clock),99,88,100d).learnedToFull,"A late wall-clock discontinuity cannot borrow coverage from the preceding segment");
        List<double[]> missing=trace();missing.remove(missing.size()-1);
        ChargeSessionStore unfinished=store();record(unfinished,"sample",ChargeSessionStore.Charger.STOCK,missing,ChargeSessionStore.Stop.COMPLETE,-1,-1);
        check(!estimate(curve(unfinished),99,88,100d).learnedToFull,"Stop reason alone cannot invent an observed 100% endpoint");
        List<double[]> rebound=trace();for(int i=250;i<270;i++)rebound.get(i)[3]=rebound.get(200)[3];
        ChargeSessionStore noisy=store();record(noisy,"sample",ChargeSessionStore.Charger.STOCK,rebound,ChargeSessionStore.Stop.COMPLETE,-1,-1);
        check(!estimate(curve(noisy),99,88,100d).learnedToFull,"Substantial power rebound makes a taper stage ambiguous");
        record(noisy,"sample",ChargeSessionStore.Charger.STOCK,rebound,ChargeSessionStore.Stop.COMPLETE,-1,-1);
        check(!estimate(curve(noisy),99,88,100d).learnedToFull,"A second ambiguous trajectory cannot re-enable a frozen SOC-only finish");
        List<double[]> flat=trace();for(double[] point:flat)if(point[1]==99)point[3]=-100/point[2];
        ChargeSessionStore plateau=store();
        for(int i=0;i<2;i++)record(plateau,"sample",ChargeSessionStore.Charger.STOCK,flat,ChargeSessionStore.Stop.COMPLETE,-1,-1);
        check(!estimate(curve(plateau),99,100,100d).learnedToFull,"A long flat-power plateau cannot claim a learned countdown, even with two charges");
        List<double[]> shortSpan=trace();shortSpan.removeIf(point->point[1]<95);
        ChargeSessionStore shortCapture=store();record(shortCapture,"sample",ChargeSessionStore.Charger.STOCK,shortSpan,ChargeSessionStore.Stop.COMPLETE,-1,-1);
        check(!estimate(curve(shortCapture),99,88,100d).learnedToFull,"A tiny completed SOC span cannot supply finishing evidence");
        List<double[]> earlyRollback=trace();earlyRollback.get(40)[1]-=1;
        ChargeSessionStore earlyRolledBack=store();record(earlyRolledBack,"sample",ChargeSessionStore.Charger.STOCK,earlyRollback,ChargeSessionStore.Stop.COMPLETE,-1,-1);
        ChargeSessionStore.Curve afterRollback=curve(earlyRolledBack);
        check(afterRollback!=null&&!afterRollback.hasFinishingCoverage(88)&&afterRollback.hasFinishingCoverage(89),"An SOC reversal resets coverage and waits for a later valid rise");
        check(estimate(afterRollback,95,114,100d).learnedToFull,"The uninterrupted suffix after an early reversal can qualify independently");
        List<double[]> rollback=trace();rollback.get(150)[1]-=1;
        ChargeSessionStore rolledBack=store();record(rolledBack,"sample",ChargeSessionStore.Charger.STOCK,rollback,ChargeSessionStore.Stop.COMPLETE,-1,-1);
        check(!estimate(curve(rolledBack),99,88,100d).learnedToFull,"A late SOC rollback leaves an insufficient finishing suffix");
        List<double[]> skipped=trace();skipped.removeIf(point->point[1]==92);
        ChargeSessionStore skippedSoc=store();record(skippedSoc,"sample",ChargeSessionStore.Charger.STOCK,skipped,ChargeSessionStore.Stop.COMPLETE,-1,-1);
        check(!estimate(curve(skippedSoc),99,88,100d).learnedToFull,"A skipped SOC step cannot manufacture a complete trajectory");
        List<double[]> noLoadEndpoint=trace();noLoadEndpoint.get(noLoadEndpoint.size()-1)[3]=0;
        ChargeSessionStore zeroEndpoint=store();record(zeroEndpoint,"sample",ChargeSessionStore.Charger.STOCK,noLoadEndpoint,ChargeSessionStore.Stop.COMPLETE,-1,-1);
        check(estimate(curve(zeroEndpoint),99,88,100d).learnedToFull,"An observed zero-current 100% endpoint still completes a genuine finish");
        ChargeSessionStore isolated=store();record(isolated,"different",ChargeSessionStore.Charger.STOCK,trace(),ChargeSessionStore.Stop.COMPLETE,-1,-1);
        record(isolated,"sample",ChargeSessionStore.Charger.HIGH_SPEED,trace(),ChargeSessionStore.Stop.COMPLETE,-1,-1);
        check(curve(isolated)==null,"Finishing curves retain profile and manual charger isolation");
        System.out.printf(java.util.Locale.US,"Observed replay: 95%% %.2f min; 99%% at 110/88/48/39 W: %.2f / %.2f / %.2f / %.2f min%n",
                at95.remainingMinutesToFull,early,middle,late,estimate(one,99,39,100d).remainingMinutesToFull);
        System.out.println("CompletedChargeEstimate: "+checks+" checks passed");
    }

    // Relative milliseconds, reported SOC, volts, signed amps. No dates, IDs, location or credentials.
    // Original paired measurements retained; tests use synthetic wall time and profile metadata.
    private static final String OBSERVED =
            "0,88,52.644,-2.3\n" +
            "10084,88,52.68,-2.3\n" +
            "20392,88,52.705,-2.3\n" +
            "30470,88,52.725,-2.3\n" +
            "40616,88,52.739,-2.3\n" +
            "50718,88,52.754,-2.3\n" +
            "60817,88,52.764,-2.3\n" +
            "70911,88,52.774,-2.3\n" +
            "80999,88,52.787,-2.3\n" +
            "91087,88,52.795,-2.3\n" +
            "101229,88,52.804,-2.27\n" +
            "111322,88,52.81,-2.27\n" +
            "121418,88,52.822,-2.27\n" +
            "131495,88,52.825,-2.27\n" +
            "141560,88,52.832,-2.27\n" +
            "151626,88,52.842,-2.27\n" +
            "161681,88,52.847,-2.27\n" +
            "171758,88,52.856,-2.27\n" +
            "181843,88,52.86,-2.27\n" +
            "191922,88,52.868,-2.27\n" +
            "202051,88,52.874,-2.27\n" +
            "212132,88,52.884,-2.27\n" +
            "222208,88,52.892,-2.27\n" +
            "232282,88,52.896,-2.25\n" +
            "242370,88,52.902,-2.25\n" +
            "252468,88,52.91,-2.25\n" +
            "262469,88,52.917,-2.25\n" +
            "272606,88,52.923,-2.25\n" +
            "282737,88,52.932,-2.25\n" +
            "292826,88,52.936,-2.25\n" +
            "302910,88,52.942,-2.25\n" +
            "313001,89,52.952,-2.25\n" +
            "323088,89,52.957,-2.25\n" +
            "333158,89,52.963,-2.25\n" +
            "343232,89,52.969,-2.25\n" +
            "353279,89,52.976,-2.23\n" +
            "363368,89,52.984,-2.23\n" +
            "373459,89,52.989,-2.23\n" +
            "383558,89,52.995,-2.23\n" +
            "393650,89,53.0,-2.23\n" +
            "403734,89,53.009,-2.23\n" +
            "413762,89,53.014,-2.23\n" +
            "423812,89,53.022,-2.23\n" +
            "433857,89,53.026,-2.23\n" +
            "443899,89,53.033,-2.23\n" +
            "454161,89,53.038,-2.23\n" +
            "464244,89,53.047,-2.23\n" +
            "474328,89,53.052,-2.21\n" +
            "484346,89,53.06,-2.21\n" +
            "494465,89,53.064,-2.21\n" +
            "504567,89,53.072,-2.21\n" +
            "514632,89,53.079,-2.21\n" +
            "524730,89,53.084,-2.21\n" +
            "534815,89,53.09,-2.21\n" +
            "544900,89,53.094,-2.21\n" +
            "554981,89,53.102,-2.21\n" +
            "565066,89,53.107,-2.21\n" +
            "575149,89,53.115,-2.21\n" +
            "585240,89,53.118,-2.18\n" +
            "595313,89,53.126,-2.18\n" +
            "605408,89,53.132,-2.18\n" +
            "615450,89,53.139,-2.18\n" +
            "625486,90,53.145,-2.18\n" +
            "635655,90,53.15,-2.18\n" +
            "645732,90,53.156,-2.18\n" +
            "655814,90,53.163,-2.18\n" +
            "665881,90,53.168,-2.18\n" +
            "675951,90,53.175,-2.18\n" +
            "686047,90,53.182,-2.18\n" +
            "696084,90,53.187,-2.18\n" +
            "706127,90,53.193,-2.16\n" +
            "716221,90,53.198,-2.16\n" +
            "726300,90,53.203,-2.16\n" +
            "736347,90,53.21,-2.16\n" +
            "746392,90,53.217,-2.16\n" +
            "756541,90,53.221,-2.16\n" +
            "766631,90,53.229,-2.16\n" +
            "776703,90,53.233,-2.16\n" +
            "786772,90,53.241,-2.16\n" +
            "796857,90,53.245,-2.16\n" +
            "806954,90,53.252,-2.16\n" +
            "817029,91,53.256,-2.16\n" +
            "827082,91,53.263,-2.16\n" +
            "837115,91,53.269,-2.14\n" +
            "847145,92,53.274,-2.14\n" +
            "857190,92,53.28,-2.14\n" +
            "867244,93,53.285,-2.14\n" +
            "877283,93,53.292,-2.14\n" +
            "887336,93,53.298,-2.14\n" +
            "897735,94,53.304,-2.14\n" +
            "907862,94,53.308,-2.14\n" +
            "917951,94,53.317,-2.14\n" +
            "928124,95,53.319,-2.14\n" +
            "938254,95,53.326,-2.14\n" +
            "948367,95,53.331,-2.12\n" +
            "958481,95,53.338,-2.12\n" +
            "968598,95,53.345,-2.12\n" +
            "978644,95,53.347,-2.12\n" +
            "988734,95,53.354,-2.12\n" +
            "998992,95,53.358,-2.12\n" +
            "1009183,95,53.366,-2.12\n" +
            "1019307,95,53.371,-2.12\n" +
            "1029427,95,53.377,-2.12\n" +
            "1039434,95,53.382,-2.12\n" +
            "1049594,95,53.386,-2.12\n" +
            "1059768,95,53.391,-2.12\n" +
            "1069896,95,53.396,-2.12\n" +
            "1080018,95,53.404,-2.12\n" +
            "1090033,95,53.41,-2.09\n" +
            "1100258,95,53.414,-2.09\n" +
            "1110385,95,53.419,-2.09\n" +
            "1120524,95,53.424,-2.09\n" +
            "1130653,95,53.432,-2.09\n" +
            "1140739,95,53.435,-2.09\n" +
            "1150912,95,53.44,-2.09\n" +
            "1161051,95,53.447,-2.09\n" +
            "1171177,95,53.451,-2.09\n" +
            "1181311,95,53.458,-2.09\n" +
            "1191386,96,53.464,-2.09\n" +
            "1201429,96,53.468,-2.09\n" +
            "1211470,96,53.473,-2.09\n" +
            "1221512,96,53.48,-2.09\n" +
            "1231559,96,53.486,-2.09\n" +
            "1241735,96,53.487,-2.07\n" +
            "1251916,96,53.496,-2.07\n" +
            "1262063,96,53.5,-2.07\n" +
            "1272173,96,53.505,-2.07\n" +
            "1282195,96,53.512,-2.07\n" +
            "1292247,96,53.516,-2.07\n" +
            "1302291,96,53.521,-2.07\n" +
            "1312302,96,53.527,-2.07\n" +
            "1322331,96,53.532,-2.07\n" +
            "1332863,96,53.539,-2.07\n" +
            "1342899,96,53.542,-2.07\n" +
            "1352948,96,53.549,-2.07\n" +
            "1363480,96,53.554,-2.07\n" +
            "1373524,96,53.559,-2.07\n" +
            "1383555,96,53.566,-2.07\n" +
            "1393590,96,53.571,-2.07\n" +
            "1403642,96,53.577,-2.07\n" +
            "1413644,96,53.58,-2.07\n" +
            "1423670,96,53.588,-2.07\n" +
            "1433728,96,53.593,-2.07\n" +
            "1444004,96,53.597,-2.07\n" +
            "1454230,97,53.603,-2.05\n" +
            "1464339,97,53.609,-2.07\n" +
            "1474431,97,53.616,-2.07\n" +
            "1484868,97,53.62,-2.05\n" +
            "1494955,97,53.628,-2.05\n" +
            "1504996,97,53.632,-2.05\n" +
            "1515024,97,53.64,-2.05\n" +
            "1525354,97,53.644,-2.05\n" +
            "1535419,97,53.649,-2.05\n" +
            "1545477,97,53.655,-2.05\n" +
            "1555537,97,53.661,-2.05\n" +
            "1565606,97,53.667,-2.05\n" +
            "1575666,97,53.673,-2.05\n" +
            "1585715,97,53.679,-2.05\n" +
            "1595770,97,53.684,-2.05\n" +
            "1605809,97,53.689,-2.05\n" +
            "1615860,97,53.697,-2.05\n" +
            "1625901,97,53.701,-2.05\n" +
            "1635943,97,53.707,-2.05\n" +
            "1646229,97,53.711,-2.05\n" +
            "1656302,97,53.72,-2.05\n" +
            "1666372,97,53.724,-2.05\n" +
            "1676445,97,53.732,-2.05\n" +
            "1686457,98,53.738,-2.05\n" +
            "1696596,98,53.744,-2.05\n" +
            "1706631,98,53.746,-2.05\n" +
            "1716662,98,53.756,-2.05\n" +
            "1726715,98,53.76,-2.05\n" +
            "1736759,98,53.768,-2.05\n" +
            "1747032,98,53.773,-2.05\n" +
            "1757129,98,53.778,-2.03\n" +
            "1767210,98,53.786,-2.05\n" +
            "1777299,98,53.787,-2.05\n" +
            "1787386,98,53.798,-2.05\n" +
            "1797455,98,53.804,-2.03\n" +
            "1807499,98,53.811,-2.03\n" +
            "1817544,99,53.814,-2.03\n" +
            "1827754,99,53.823,-2.03\n" +
            "1837851,99,53.829,-2.05\n" +
            "1847940,99,53.835,-2.03\n" +
            "1858029,99,53.839,-2.03\n" +
            "1868088,99,53.847,-2.03\n" +
            "1878235,99,53.851,-2.03\n" +
            "1888352,99,53.863,-2.03\n" +
            "1898434,99,53.865,-2.03\n" +
            "1908527,99,53.875,-2.03\n" +
            "1918598,99,53.877,-2.03\n" +
            "1928701,99,53.888,-2.03\n" +
            "1938814,99,53.891,-2.03\n" +
            "1948909,99,53.899,-2.03\n" +
            "1958997,99,53.903,-2.03\n" +
            "1969009,99,53.915,-2.03\n" +
            "1979042,99,53.917,-2.03\n" +
            "1989254,99,53.926,-2.03\n" +
            "1999340,99,53.931,-2.03\n" +
            "2009416,99,53.94,-2.03\n" +
            "2019514,99,53.943,-2.03\n" +
            "2029585,99,53.951,-2.03\n" +
            "2039671,99,53.956,-2.03\n" +
            "2049690,99,53.966,-2.03\n" +
            "2059831,99,53.97,-2.03\n" +
            "2069913,99,53.978,-2.03\n" +
            "2080006,99,53.982,-2.03\n" +
            "2090090,99,53.992,-2.03\n" +
            "2100194,99,53.996,-2.03\n" +
            "2110269,99,54.006,-2.03\n" +
            "2120365,99,54.011,-2.03\n" +
            "2130458,99,54.019,-2.03\n" +
            "2140544,99,54.022,-2.03\n" +
            "2150633,99,54.032,-2.03\n" +
            "2160646,99,54.037,-2.03\n" +
            "2170688,99,54.043,-2.03\n" +
            "2180730,99,54.052,-2.03\n" +
            "2190954,99,54.059,-2.03\n" +
            "2201038,99,54.066,-2.03\n" +
            "2211133,99,54.073,-2.03\n" +
            "2221143,99,54.08,-2.03\n" +
            "2231303,99,54.085,-2.03\n" +
            "2241394,99,54.094,-2.03\n" +
            "2251480,99,54.099,-2.03\n" +
            "2261549,99,54.107,-2.03\n" +
            "2271628,99,54.11,-1.98\n" +
            "2281722,99,54.113,-1.96\n" +
            "2291801,99,54.12,-1.94\n" +
            "2301896,99,54.124,-1.91\n" +
            "2311972,99,54.124,-1.89\n" +
            "2322052,99,54.128,-1.87\n" +
            "2332149,99,54.135,-1.85\n" +
            "2342233,99,54.138,-1.82\n" +
            "2352322,99,54.138,-1.8\n" +
            "2362371,99,54.146,-1.78\n" +
            "2372414,99,54.149,-1.76\n" +
            "2382458,99,54.152,-1.73\n" +
            "2392556,99,54.155,-1.71\n" +
            "2402741,99,54.158,-1.69\n" +
            "2412888,99,54.162,-1.69\n" +
            "2422963,99,54.163,-1.67\n" +
            "2433032,99,54.169,-1.64\n" +
            "2443117,99,54.172,-1.62\n" +
            "2453194,99,54.176,-1.6\n" +
            "2463234,99,54.179,-1.58\n" +
            "2473268,99,54.182,-1.58\n" +
            "2483323,99,54.183,-1.55\n" +
            "2493541,99,54.187,-1.53\n" +
            "2503635,99,54.191,-1.51\n" +
            "2513668,99,54.195,-1.51\n" +
            "2523718,99,54.199,-1.49\n" +
            "2533770,99,54.2,-1.46\n" +
            "2543799,99,54.204,-1.46\n" +
            "2553914,99,54.206,-1.44\n" +
            "2563991,99,54.209,-1.42\n" +
            "2574072,99,54.209,-1.42\n" +
            "2584149,99,54.218,-1.4\n" +
            "2594227,99,54.217,-1.37\n" +
            "2604256,99,54.221,-1.37\n" +
            "2614299,99,54.223,-1.35\n" +
            "2624406,99,54.227,-1.35\n" +
            "2634467,99,54.229,-1.33\n" +
            "2644529,99,54.231,-1.31\n" +
            "2654586,99,54.234,-1.31\n" +
            "2664650,99,54.236,-1.28\n" +
            "2674688,99,54.241,-1.28\n" +
            "2684733,99,54.243,-1.26\n" +
            "2694781,99,54.244,-1.24\n" +
            "2704919,99,54.249,-1.24\n" +
            "2715014,99,54.25,-1.22\n" +
            "2725091,99,54.254,-1.22\n" +
            "2735168,99,54.257,-1.19\n" +
            "2745250,99,54.258,-1.19\n" +
            "2755347,99,54.262,-1.17\n" +
            "2765434,99,54.262,-1.17\n" +
            "2775528,99,54.265,-1.15\n" +
            "2785614,99,54.269,-1.15\n" +
            "2795693,99,54.272,-1.13\n" +
            "2805786,99,54.272,-1.13\n" +
            "2815857,99,54.274,-1.1\n" +
            "2825921,99,54.277,-1.1\n" +
            "2835964,99,54.282,-1.08\n" +
            "2845994,99,54.286,-1.08\n" +
            "2856038,99,54.286,-1.08\n" +
            "2866085,99,54.285,-1.06\n" +
            "2876173,99,54.293,-1.06\n" +
            "2886248,99,54.291,-1.06\n" +
            "2896285,99,54.296,-1.04\n" +
            "2906323,99,54.297,-1.04\n" +
            "2916414,99,54.299,-1.01\n" +
            "2926482,99,54.301,-1.01\n" +
            "2936528,99,54.303,-1.01\n" +
            "2946596,99,54.309,-0.99\n" +
            "2956664,99,54.308,-0.99\n" +
            "2966725,99,54.309,-0.97\n" +
            "2976806,99,54.313,-0.97\n" +
            "2986876,99,54.314,-0.95\n" +
            "2996955,99,54.32,-0.95\n" +
            "3007023,99,54.32,-0.95\n" +
            "3017072,99,54.322,-0.92\n" +
            "3027145,99,54.322,-0.92\n" +
            "3037223,99,54.324,-0.9\n" +
            "3047310,99,54.326,-0.9\n" +
            "3057389,99,54.329,-0.9\n" +
            "3067479,99,54.33,-0.88\n" +
            "3077494,99,54.334,-0.88\n" +
            "3087663,99,54.335,-0.88\n" +
            "3097766,99,54.336,-0.86\n" +
            "3107855,99,54.338,-0.86\n" +
            "3117934,99,54.341,-0.86\n" +
            "3127982,99,54.344,-0.86\n" +
            "3138089,99,54.346,-0.83\n" +
            "3148174,99,54.348,-0.83\n" +
            "3158252,99,54.349,-0.81\n" +
            "3168318,99,54.35,-0.81\n" +
            "3178352,99,54.353,-0.81\n" +
            "3188396,99,54.353,-0.79\n" +
            "3198578,99,54.358,-0.79\n" +
            "3208660,99,54.359,-0.79\n" +
            "3218731,99,54.36,-0.79\n" +
            "3228812,99,54.361,-0.77\n" +
            "3238896,99,54.361,-0.77\n" +
            "3248983,99,54.364,-0.77\n" +
            "3259059,99,54.366,-0.74\n" +
            "3269099,99,54.368,-0.74\n" +
            "3279209,99,54.371,-0.74\n" +
            "3289260,99,54.372,-0.74\n" +
            "3299296,99,54.374,-0.72\n" +
            "3309304,99,54.375,-0.72\n" +
            "3319345,99,54.376,-0.72\n" +
            "3329384,99,54.377,-0.72\n" +
            "3339423,99,54.383,-0.7\n" +
            "3349465,99,54.382,-0.7\n" +
            "3359516,99,54.384,-0.7\n" +
            "3369555,99,54.385,-0.7\n" +
            "3379744,99,54.387,-0.7\n" +
            "3389893,99,54.387,-0.68\n" +
            "3390131,100,54.335,-0.41\n";
}
