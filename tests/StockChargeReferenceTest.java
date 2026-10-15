package local.navee.offlinelock;

/** Default UT5 MAX estimates work with no recordings, developer options or manual charger setup. */
public final class StockChargeReferenceTest {
    private static int checks;
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static void near(double expected,Double value,double tolerance,String message){check(value!=null&&Math.abs(value-expected)<=tolerance,message+": "+value);}
    private static ChargingEstimate.Value live(Double value){return new ChargingEstimate.Value(value,true,100);}
    private static ChargingEstimate.Result baseline(double soc,double watts,Double target){
        return ChargingEstimate.calculate(live(1d),live(54d),live(-watts/54),live(soc),live(15.3),live(target));
    }
    private static ChargingEstimate.Result estimate(double soc,double watts,Double target,int pid,
            ChargeSessionStore.Charger charger,ChargeSessionStore.Curve personal){
        return ChargingEstimate.forScooter(baseline(soc,watts,target),live(soc),live(15.3),pid,charger,personal);
    }
    public static void main(String[] args)throws Exception{
        ChargingEstimate.Result fresh=estimate(95,114,100d,2538,null,null);
        near(41.03345,fresh.remainingMinutesToFull,.01,"First-use default includes the observed finishing tail");
        check(fresh.referenceToFull&&fresh.referenceToLimit&&!fresh.learnedToFull&&!fresh.learnedToLimit&&fresh.learnedSessions==0,
                "Bundled evidence is never claimed as the user's saved charges");
        check(fresh.status.contains("Experimental UT5 MAX stock-charger")&&fresh.status.contains("does not identify")&&fresh.status.contains("balancing"),
                "Experimental default, charger uncertainty and endpoint are visible");
        near(fresh.remainingMinutesToFull,estimate(95,114,100d,2538,ChargeSessionStore.Charger.STOCK,null).remainingMinutesToFull,0,
                "Explicit stock and no manual choice use the same default");
        double early=estimate(99,110,100d,2538,null,null).remainingMinutesToFull;
        double middle=estimate(99,88,100d,2538,null,null).remainingMinutesToFull;
        double late=estimate(99,48,100d,2538,null,null).remainingMinutesToFull;
        check(early>20&&middle>13&&middle<18&&late<8&&early>middle&&middle>late,"Default progresses through the 99% taper without personal recordings");
        near(42.38310,estimate(92,114,100d,2538,null,null).remainingMinutesToFull,.01,
                "Default preserves the rapid 91–95% gauge transition");
        double bulk=15.3*.39/(100d/54)*60;
        ChargingEstimate.Result half=estimate(50,100,100d,2538,null,null);
        near(bulk+51.2855,half.remainingMinutesToFull,.0001,"Before 89%, rated Ah/live amps model bulk and observed finishing time is appended");
        check(half.status.contains("Bulk time uses rated Ah"),"Unobserved middle range is described as a model");
        ChargingEstimate.Result cutoff80=estimate(50,100,80d,2538,null,null);
        near(baseline(50,100,80d).remainingMinutesToLimit,cutoff80.remainingMinutesToLimit,0,"80% cutoff does not include a full-charge tail");
        check(!cutoff80.referenceToLimit&&cutoff80.referenceToFull,"Each target records its actual source");
        ChargingEstimate.Result cutoff95=estimate(50,100,95d,2538,null,null);
        near(bulk+51.2855-41.03345,cutoff95.remainingMinutesToLimit,.0001,"A cutoff inside the reference uses only time to that cutoff");
        check(cutoff95.referenceToLimit&&cutoff95.status.contains("higher charge limit"),"Inside-reference cutoff is explicit and full time remains hypothetical");
        ChargingEstimate.Result closeCutoff=estimate(95,114,98d,2538,null,null);
        near(41.03345-28.39457,closeCutoff.remainingMinutesToLimit,.01,"Near-full lower cutoff subtracts its observed boundary");
        double justBelow=estimate(88.999,114,100d,2538,null,null).remainingMinutesToFull;
        double atBoundary=estimate(89,114,100d,2538,null,null).remainingMinutesToFull;
        near(atBoundary,justBelow,.01,"Bulk and observed stages share a continuous 89% anchor");
        near(estimate(89,114,90d,2538,null,null).remainingMinutesToLimit,
                estimate(88.999,114,90d,2538,null,null).remainingMinutesToLimit,.01,
                "Lower-cutoff estimates also stay continuous at the bulk/reference boundary");
        double previous=-1;
        for(double target:new double[]{88.5,88.9,89,89.001,89.1,89.5,90,95,99,100}){
            double time=estimate(88.5,114,target,2538,null,null).remainingMinutesToLimit;
            check(time>=previous,"Later fractional cutoffs cannot have less remaining time at the 89% boundary");
            previous=time;
        }
        ChargingEstimate.Result unknown=estimate(95,114,null,2538,null,null);
        check(unknown.referenceToFull&&unknown.remainingMinutesToLimit==null&&!unknown.referenceToLimit&&unknown.status.contains("Cutoff unknown"),
                "No configured cutoff is invented");
        ChargingEstimate.Result targetReached=estimate(95,114,90d,2538,null,null);
        near(0,targetReached.remainingMinutesToLimit,0,"Already reached cutoff remains zero");
        for(int pid:new int[]{0,1,2537,2539}){
            ChargingEstimate.Result old=baseline(95,114,100d);
            check(ChargingEstimate.forScooter(old,live(95d),live(15.3),pid,null,null)==old,"Unknown models preserve the live fallback");
        }
        check(!estimate(95,114,100d,2538,ChargeSessionStore.Charger.HIGH_SPEED,null).referenceToFull,
                "Explicit high-speed selection disables the stock default even at compatible power");
        for(double watts:new double[]{0,10,40,250,500})check(!estimate(50,watts,100d,2538,null,null).referenceToFull,"Incompatible bulk power retains fallback");
        for(double watts:new double[]{10,25,250})check(!estimate(99,watts,100d,2538,null,null).referenceToFull,"Incompatible taper power retains fallback");
        check(!estimate(100,22,100d,2538,null,null).referenceToFull,"Displayed full needs no reference extrapolation");
        check(!estimate(95.5,114,100d,2538,null,null).referenceToFull,"Unknown fractional finishing SOC retains fallback");
        ChargingEstimate.Result stale=ChargingEstimate.calculate(live(1d),live(54d),new ChargingEstimate.Value(-2d,false,100),live(95d),live(15.3),live(100d));
        check(ChargingEstimate.forScooter(stale,live(95d),live(15.3),2538,null,null).remainingMinutesToFull==null,"Default cannot bypass fresh paired measurements");
        ChargingEstimate.Result old=baseline(95,114,100d);
        check(ChargingEstimate.forScooter(old,new ChargingEstimate.Value(95d,false,100),live(15.3),2538,null,null)==old,"Stale SOC cannot select a reference stage");
        check(ChargingEstimate.forScooter(old,live(95d),new ChargingEstimate.Value(15.3,false,100),2538,null,null)==old,"Stale rated Ah keeps the baseline unchanged");

        ChargeSessionStore store=ChargeSessionStoreTest.store();
        ChargeSessionStoreTest.session(store,"one",ChargeSessionStore.Charger.STOCK,40,100,60000,false,false);
        ChargeSessionStore.Curve one=ChargeSessionStoreTest.curve(store,"one",ChargeSessionStore.Charger.STOCK);
        ChargingEstimate.Result afterPartial=estimate(95,104,100d,2538,ChargeSessionStore.Charger.STOCK,one);
        check(afterPartial.referenceToFull&&!afterPartial.learnedToFull&&afterPartial.learnedSessions==0&&afterPartial.status.contains("Experimental UT5 MAX"),
                "One non-complete personal curve cannot erase or mislabel a valid default");
        ChargeSessionStoreTest.session(store,"one",ChargeSessionStore.Charger.STOCK,40,100,60000,false,false);
        ChargeSessionStore.Curve two=ChargeSessionStoreTest.curve(store,"one",ChargeSessionStore.Charger.STOCK);
        ChargingEstimate.Result personal=estimate(95,104,100d,2538,ChargeSessionStore.Charger.STOCK,two);
        near(5,personal.remainingMinutesToFull,0,"Qualified matching personal evidence can refine the default");
        check(personal.learnedToFull&&personal.learnedToLimit&&!personal.referenceToFull&&!personal.referenceToLimit&&personal.learnedSessions==2,
                "Personal source metadata does not masquerade as bundled reference");
        check(estimate(95,104,100d,2538,null,two).referenceToFull,"No manual charger selection cannot silently enable a personal charger curve");
        check(!estimate(95,104,100d,2538,ChargeSessionStore.Charger.HIGH_SPEED,two).learnedToFull,"Curve from another manual charger label is not used");
        ChargeSessionStore partialStore=ChargeSessionStoreTest.store();
        for(int i=0;i<2;i++)ChargeSessionStoreTest.session(partialStore,"one",ChargeSessionStore.Charger.STOCK,40,80,60000,false,false);
        ChargeSessionStore.Curve partial=ChargeSessionStoreTest.curve(partialStore,"one",ChargeSessionStore.Charger.STOCK);
        ChargingEstimate.Result mixed=estimate(50,104,80d,2538,ChargeSessionStore.Charger.STOCK,partial);
        near(30,mixed.remainingMinutesToLimit,0,"Personal cutoff evidence replaces only the supported target");
        near(estimate(50,104,80d,2538,null,null).remainingMinutesToFull,mixed.remainingMinutesToFull,0,"Unsupported personal full target retains bundled default");
        check(mixed.learnedToLimit&&!mixed.learnedToFull&&mixed.referenceToFull&&!mixed.referenceToLimit&&!mixed.status.contains("100% uses the current rate"),
                "Mixed personal/default targets retain accurate source descriptions");
        ChargeSessionStore slow=ChargeSessionStoreTest.store();
        for(int i=0;i<2;i++)ChargeSessionStoreTest.session(slow,"one",ChargeSessionStore.Charger.STOCK,40,100,60000,false,false,soc->1);
        ChargingEstimate.Result mismatched=estimate(95,114,100d,2538,ChargeSessionStore.Charger.STOCK,ChargeSessionStoreTest.curve(slow,"one",ChargeSessionStore.Charger.STOCK));
        check(mismatched.referenceToFull&&!mismatched.learnedToFull&&mismatched.status.contains("Experimental UT5 MAX"),"Mismatched private power does not discard a compatible default");
        System.out.printf(java.util.Locale.US,"Bundled stock reference: 50%% %.2f min; 95%% %.2f min; 99%% at 110/88/48 W %.2f / %.2f / %.2f min%n",
                half.remainingMinutesToFull,fresh.remainingMinutesToFull,early,middle,late);
        System.out.println("StockChargeReference: "+checks+" checks passed");
    }
}
