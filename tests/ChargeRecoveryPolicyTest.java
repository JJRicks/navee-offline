package local.navee.offlinelock;

public final class ChargeRecoveryPolicyTest {
    private static int checks;
    private static final long START = 100_000, LOST = START + 60_000;
    private static final String PROFILE = "one", SERIAL = "X2538SYNTHETIC0001";
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static ChargeRecoveryPolicy policy() { return new ChargeRecoveryPolicy(PROFILE, SERIAL, START, 7); }
    private static ChargeSessionStore.Value v(double value, long now) { return new ChargeSessionStore.Value(value, now, 0x72); }
    private static ChargeSessionStore.Frame frame(long now, double state, double soc) {
        return new ChargeSessionStore.Frame(1_800_000_000_000L + now, now, v(state, now), v(soc, now), v(52, now), v(-2, now), null);
    }
    private static ChargeRecoveryPolicy.Result evaluate(ChargeRecoveryPolicy p, long now, boolean connected, boolean ready, boolean retry, ChargeSessionStore.Frame frame) {
        return p.evaluate(now, PROFILE, SERIAL, 7, true, true, connected, ready, retry, connected ? SERIAL : "", frame);
    }
    private static void lose(ChargeRecoveryPolicy p) {
        ChargeRecoveryPolicy.Result r=evaluate(p, LOST, false, false, true, frame(LOST-1000,1,50));
        check(r.beganRecovery && p.recovering() && !r.record && r.stop==null, "Transient loss enters a bounded wait");
    }
    public static void main(String[] args) {
        ChargeRecoveryPolicy p=policy();
        check(evaluate(p,START,true,true,false,frame(START,1,50)).record,"Verified initial connection records normally");
        lose(p);
        check(!evaluate(p,LOST+1000,false,false,true,null).beganRecovery,"Repeated callbacks do not restart recovery");
        check(!evaluate(p,LOST+2000,true,false,true,frame(LOST+2000,1,50)).record,"Authentication without ready status cannot resume");
        ChargeSessionStore.Frame cached=frame(LOST,1,50);
        check(!evaluate(p,LOST+3000,true,true,true,cached).record&&p.recovering(),"Cached pre-loss readings cannot resume even if otherwise fresh");
        ChargeSessionStore.Frame mixed=new ChargeSessionStore.Frame(cached.wall,LOST+3000,v(1,LOST+3000),v(50,LOST),v(52,LOST+3000),v(-2,LOST+3000),null);
        check(!evaluate(p,LOST+3000,true,true,true,mixed).record,"Every required field must be observed after loss");
        ChargeRecoveryPolicy.Result resumed=evaluate(p,LOST+4000,true,true,true,frame(LOST+4000,1,51));
        check(resumed.record&&resumed.resumed&&!p.recovering(),"Same authenticated scooter with new paired charging data resumes");
        check(!evaluate(p,LOST+5000,true,true,false,frame(LOST+5000,1,51)).resumed,"Resume emitted once");
        long again=LOST+6000;
        check(evaluate(p,again,false,false,true,null).beganRecovery,"A later interruption gets a new recovery window");
        check(p.remainingMs(again+1)==ChargeRecoveryPolicy.RECOVERY_TIMEOUT_MS-1,"Window starts at the new interruption");

        p=policy();lose(p);
        check(evaluate(p,LOST+299999,true,false,true,null).stop==null,"Partial authentication can wait just under five minutes");
        check(evaluate(p,LOST+300000,true,true,true,frame(LOST+300000,1,55)).stop==ChargeSessionStore.Stop.RECOVERY_TIMEOUT,"Fresh readings at the deadline cannot extend recovery");
        p=policy();lose(p);
        check(evaluate(p,LOST+1000,false,false,false,null).stop==ChargeSessionStore.Stop.CONNECTION_FAILED,"Fatal auth/transport rejection stops rather than retries");
        p=policy();lose(p);
        check(p.evaluate(LOST+1000,PROFILE,SERIAL,8,true,true,false,false,true,"",null).stop==ChargeSessionStore.Stop.STOPPED,"Explicit disconnect wins over retry");
        check(p.evaluate(LOST+1000,"other",SERIAL,7,true,true,false,false,true,"",null).stop==ChargeSessionStore.Stop.PROFILE_CHANGED,"Changing profile ends capture");
        check(p.evaluate(LOST+1000,PROFILE,"OTHER",7,true,true,false,false,true,"",null).stop==ChargeSessionStore.Stop.IDENTITY_CHANGED,"Editing saved serial ends capture");
        check(p.evaluate(LOST+1000,PROFILE,SERIAL,7,false,true,false,false,true,"",null).stop==ChargeSessionStore.Stop.PERMISSION_LOST,"Permission loss ends capture");
        check(p.evaluate(LOST+1000,PROFILE,SERIAL,7,true,false,false,false,true,"",null).stop==ChargeSessionStore.Stop.BLUETOOTH_OFF,"Bluetooth off ends capture");
        check(p.evaluate(LOST+1000,PROFILE,SERIAL,7,true,true,true,true,true,"OTHER",frame(LOST+1000,1,50)).stop==ChargeSessionStore.Stop.IDENTITY_CHANGED,"Wrong authenticated scooter never contributes data");
        p=policy();lose(p);
        check(evaluate(p,LOST+1000,true,true,true,frame(LOST+1000,0,75)).stop==ChargeSessionStore.Stop.CHARGING_ENDED,"Charging already ended during gap is preserved as an incomplete session");
        p=policy();lose(p);
        resumed=evaluate(p,LOST+1000,true,true,true,frame(LOST+1000,0,100));
        check(resumed.resumed&&resumed.record,"Observed 100 percent endpoint is retained after gap without filling it");
        p=policy();lose(p);
        resumed=evaluate(p,LOST+1000,true,true,true,frame(LOST+1000,0,99));
        check(resumed.resumed&&resumed.record,"Fresh 99 percent cutoff goes through existing final-packet grace window");
        p=policy();lose(p);
        resumed=p.evaluate(LOST+1000,PROFILE,SERIAL,7,true,true,true,true,true,SERIAL,frame(LOST+1000,0,75),true);
        check(resumed.resumed&&resumed.record&&resumed.stop==null&&!p.recovering(),"A Volt schedule pause seen after a loss resumes into the pause");
        p=policy();lose(p);
        check(p.evaluate(LOST+1000,PROFILE,SERIAL,7,true,true,true,true,true,SERIAL,frame(LOST+1000,2,75),true).stop==ChargeSessionStore.Stop.CHARGING_ENDED,"Only charging=off counts as the scheduled pause");
        p=policy();lose(p);
        check(p.evaluate(LOST+1000,PROFILE,SERIAL,7,true,true,true,true,true,SERIAL,frame(LOST+1000,0,75),false).stop==ChargeSessionStore.Stop.CHARGING_ENDED,"Without a schedule, charging=off still ends the capture");
        p=policy();
        check(evaluate(p,START+ChargeSessionStore.MAX_SESSION_MS-1,false,false,true,null).beganRecovery,"Loss near session limit starts recovery");
        check(evaluate(p,START+ChargeSessionStore.MAX_SESSION_MS,false,false,true,null).stop==ChargeSessionStore.Stop.LIMIT,"Total eighteen-hour limit also applies while disconnected");
        p=policy();check(evaluate(p,START-1,true,true,false,frame(START-1,1,50)).stop==ChargeSessionStore.Stop.LIMIT,"Monotonic clock reversal fails closed");
        System.out.println("ChargeRecoveryPolicy: "+checks+" checks passed");
    }
}
