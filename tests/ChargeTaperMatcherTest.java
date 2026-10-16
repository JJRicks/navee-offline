package local.navee.offlinelock;

/** Two observed finishes are tested against each other, separately from fitting both together. */
public final class ChargeTaperMatcherTest {
    private static int checks;
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static void near(double expected,Double actual,String message){check(actual!=null&&Math.abs(expected-actual)<1e-6,message+": "+actual);}
    private static double[][] stage(double[][] trace){
        double end=trace[trace.length-1][0]; double[][] stage=new double[trace.length-1][3];
        for(int i=0;i<stage.length;i++) stage[i]=new double[]{Math.abs(trace[i][2]*trace[i][3]),trace[i][2],(end-trace[i][0])/60000};
        return stage;
    }
    private static double powerOnly(double watts,double[][] stage){
        double closest=Double.POSITIVE_INFINITY;
        for(double[] point:stage)closest=Math.min(closest,Math.abs(point[0]-watts));
        double sum=0;int count=0;
        for(double[] point:stage)if(Math.abs(point[0]-watts)<=closest+Math.max(1.5,watts*.02)){sum+=point[2];count++;}
        return sum/count;
    }
    private static double[] holdout(double[][] reference,double[][] observed){
        double error=0,oldError=0,worst=0;
        for(double[] point:observed){
            Double predicted=ChargeTaperMatcher.estimate(point[0],point[1],reference);
            check(predicted!=null,"The other capture covers this observed voltage/power stage");
            error+=Math.abs(predicted-point[2]);oldError+=Math.abs(powerOnly(point[0],reference)-point[2]);
            worst=Math.max(worst,Math.abs(predicted-point[2]));
        }
        return new double[]{error/observed.length,oldError/observed.length,worst};
    }
    public static void main(String[] args){
        double[][] phases={{104,53.8,30},{104,54.1,18}};
        near(30,ChargeTaperMatcher.estimate(104,53.8,phases),"Low-current early plateau keeps its early voltage stage");
        near(18,ChargeTaperMatcher.estimate(104,54.1,phases),"The same watts at a later voltage have less time remaining");
        for(double value:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,0,-1}){
            check(ChargeTaperMatcher.estimate(value,53.8,phases)==null,"Invalid live power is refused");
            check(ChargeTaperMatcher.estimate(104,value,phases)==null,"Invalid live voltage is refused");
        }
        check(ChargeTaperMatcher.estimate(250,53.8,phases)==null,"Another charger's power cannot map into the curve");
        check(ChargeTaperMatcher.estimate(104,53.69,phases)==null,"Unseen low voltage is not extrapolated");
        check(ChargeTaperMatcher.estimate(104,54.21,phases)==null,"Unseen high voltage is not extrapolated");
        check(ChargeTaperMatcher.estimate(104,53.8,null)==null,"Absent trajectory is refused");
        check(ChargeTaperMatcher.estimate(104,53.8,new double[][]{{104,53.8,30}})==null,"A single point cannot define a taper");
        check(ChargeTaperMatcher.estimate(104,53.8,new double[][]{{104,53.8,30},null})==null,"Malformed trajectory is refused");
        check(ChargeTaperMatcher.estimate(104,53.8,new double[][]{{104,53.8,30},{104,53.8,Double.NaN}})==null,"Invalid observed duration is refused");
        check(ChargeTaperMatcher.estimate(104,53.8,new double[][]{{104,53.8,Double.MAX_VALUE},{104,53.8,Double.MAX_VALUE}})==null,
                "Finite inputs cannot produce an infinite estimate");
        for(double tolerance:new double[]{-.1,.21,Double.NaN,Double.POSITIVE_INFINITY})
            check(ChargeTaperMatcher.estimate(104,53.8,phases,tolerance)==null,"Runtime tolerance cannot widen beyond the existing grace");
        check(ChargeTaperMatcher.estimate(104,53.8,new double[][]{{104,53.8,30},{104,53.8,18}})==null,"Ten-plus minutes of ambiguous voltage and power cannot invent progress");
        check(ChargeTaperMatcher.estimate(40,53.8,new double[][]{{110,53.8,30},{40,54.35,1}})==null,
                "Voltage must be close to a locally compatible power stage, not an unrelated point");
        double[][] first=stage(StockChargeHoldoutTrace.parse(StockChargeHoldoutTrace.FIRST));
        double[][] second=stage(StockChargeHoldoutTrace.parse(StockChargeHoldoutTrace.SECOND));
        double[] forward=holdout(first,second),reverse=holdout(second,first);
        check(forward[0]<2.5&&reverse[0]<2.5,"Each independent terminal capture predicts the other within the observed replay bound");
        check(forward[0]<forward[1]*.7&&reverse[0]<reverse[1]*.7,"Voltage and local power improve both directions against power-only stage matching");
        check(forward[2]<6&&reverse[2]<6,"Both holdouts avoid the thirteen-minute early-plateau mistake");
        double refitError=0;
        for(double[] point:second){
            Double value=Ut5MaxStockChargeReference.estimate(99,100,15.3,point[0]/point[1],point[0]);
            check(value!=null,"Bundled compact references cover the second capture's observed taper");
            refitError+=Math.abs(value-point[2]);
        }
        check(refitError/second.length<1.5,"Compact two-capture calibration retains the full-trajectory replay improvement");
        System.out.printf(java.util.Locale.US,"99%% cross-capture MAE: first to second %.3f min (power-only %.3f); second to first %.3f (power-only %.3f); two-capture refit %.3f%n",
                forward[0],forward[1],reverse[0],reverse[1],refitError/second.length);
        System.out.println("ChargeTaperMatcher: "+checks+" checks passed");
    }
}
