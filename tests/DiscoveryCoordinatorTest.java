package local.navee.offlinelock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Fixed read order, one read at a time, per-class attempts, late and rejected replies, interruption and time bounds. */
public final class DiscoveryCoordinatorTest {
    private static int assertions;
    private static final long T0=50000;
    public static void main(String[] args) {
        order(); oneOutstanding(); attempts(); late(); rejected(); duplicates(); malformed(); interruption(); timing(); passive(); zeroWrites();
        System.out.println("DiscoveryCoordinator tests passed: "+assertions+" assertions");
    }

    /** Runs a check where every read answers immediately; returns the opcodes it sent. */
    private static List<Integer> run(DiscoveryCoordinator d,Set<Integer> silent,long step){
        List<Integer> sent=new ArrayList<>();
        for(long t=T0;!d.finished(t)&&t<T0+60000;t+=step){
            Integer cmd=d.nextRead(t);
            if(cmd!=null){sent.add(cmd);if(!silent.contains(cmd))d.onFrame(cmd,0,cmd==0x70?43:cmd==0x74?17:8,t+20);}
        }
        return sent;
    }
    private static DiscoveryCoordinator.Read read(DiscoveryCoordinator d,int cmd){for(DiscoveryCoordinator.Read r:d.reads())if(r.cmd==cmd)return r;throw new AssertionError("no read "+cmd);}

    private static void order() {
        DiscoveryCoordinator d=new DiscoveryCoordinator(T0);
        List<Integer> sent=run(d,new HashSet<Integer>(),100);
        check(sent.equals(Arrays.asList(0x74,0x70,0x73,0x72,0x76,0x7b,0x71,0x75,0x79)),"Fixed order "+sent);
        for(DiscoveryCoordinator.Read r:d.reads())check(r.outcome==DiscoveryCoordinator.Outcome.READ_SUPPORTED&&r.attempts==1&&!r.late,"Answered "+r);
        check(read(d,0x70).length==43&&read(d,0x70).status==0&&read(d,0x70).firstMs>=T0,"Length, status and time recorded");
        check(Arrays.equals(DiscoveryCoordinator.ORDER,new int[]{0x74,0x70,0x73,0x72,0x76,0x7b,0x71,0x75,0x79}),"ORDER constant");
        check(read(d,0x74).cls==ReadPolicy.Cls.CORE&&read(d,0x72).cls==ReadPolicy.Cls.OPTIONAL&&read(d,0x75).cls==ReadPolicy.Cls.PROBE,"Classes");
    }
    private static void oneOutstanding() {
        DiscoveryCoordinator d=new DiscoveryCoordinator(T0);
        check(Integer.valueOf(0x74).equals(d.nextRead(T0)),"First read");
        for(long t=T0+1;t<T0+2500;t+=50)check(d.nextRead(t)==null,"Nothing else while one is outstanding");
        d.onFrame(0x74,0,17,T0+100);
        check(d.nextRead(T0+299)==null,"300 ms spacing");
        check(Integer.valueOf(0x70).equals(d.nextRead(T0+300)),"Next after spacing");
        check(Integer.valueOf(0x70).equals(d.current()),"Current read");
    }
    private static void attempts() {
        // Every read silent: core 3, optional 2, probe 1 attempts, each 2.5 s; the 30 s cap ends it.
        DiscoveryCoordinator d=new DiscoveryCoordinator(T0);
        Set<Integer> all=new HashSet<>();for(int c:DiscoveryCoordinator.ORDER)all.add(c);
        List<Integer> sent=run(d,all,100);
        check(sent.subList(0,9).equals(Arrays.asList(0x74,0x74,0x74,0x70,0x70,0x70,0x73,0x73,0x73)),"Core reads retried three times "+sent);
        check(sent.subList(9,11).equals(Arrays.asList(0x72,0x72)),"Optional read retried twice "+sent);
        check(read(d,0x74).outcome==DiscoveryCoordinator.Outcome.NO_RESPONSE&&read(d,0x74).attempts==3,"Core silent");
        check(read(d,0x72).outcome==DiscoveryCoordinator.Outcome.NO_RESPONSE&&read(d,0x72).attempts==2,"Optional silent");
        check(read(d,0x79).outcome==DiscoveryCoordinator.Outcome.INTERRUPTED&&read(d,0x79).attempts==0,"Never tried before the cap: interrupted");
        check(!d.interrupted(),"Cap is not an owner interruption");
        // Probes: one attempt each.
        DiscoveryCoordinator p=new DiscoveryCoordinator(T0);
        Set<Integer> probes=new HashSet<>(Arrays.asList(0x71,0x75,0x79));
        List<Integer> s2=run(p,probes,100);
        int n=0;for(int c:s2)if(probes.contains(c))n++;
        check(n==3,"Each probe once "+s2);
        for(int c:probes)check(read(p,c).outcome==DiscoveryCoordinator.Outcome.NO_RESPONSE&&read(p,c).attempts==1,"Probe silent once");
    }
    private static void late() {
        DiscoveryCoordinator d=new DiscoveryCoordinator(T0);
        d.nextRead(T0);check(Integer.valueOf(0x74).equals(d.nextRead(T0+2500)),"Retry after timeout");
        d.onFrame(0x74,0,17,T0+2600);
        DiscoveryCoordinator.Read r=read(d,0x74);
        check(r.outcome==DiscoveryCoordinator.Outcome.READ_SUPPORTED&&r.late&&r.attempts==2,"Reply after a timeout is supported and late");
        // A probe resolves NO_RESPONSE, then its reply arrives.
        DiscoveryCoordinator p=new DiscoveryCoordinator(T0);
        long t=T0;for(int c:new int[]{0x74,0x70,0x73,0x72,0x76,0x7b}){Integer cmd;while((cmd=p.nextRead(t))==null)t+=50;p.onFrame(cmd,0,cmd==0x70?13:cmd==0x74?17:4,t);}
        Integer probe;while((probe=p.nextRead(t))==null)t+=50;check(probe==0x71,"Probe 0x71");
        t+=2500;Integer next;while((next=p.nextRead(t))==null)t+=50;check(next==0x75,"Moved on after probe silence");
        check(read(p,0x71).outcome==DiscoveryCoordinator.Outcome.NO_RESPONSE,"Probe no response");
        p.onFrame(0x71,0,4,t+10);
        check(read(p,0x71).outcome==DiscoveryCoordinator.Outcome.READ_SUPPORTED&&read(p,0x71).late,"Late probe reply still proves the read");
        check(Integer.valueOf(0x75).equals(p.current()),"Late reply does not clear the current read");
    }
    private static void rejected() {
        DiscoveryCoordinator d=new DiscoveryCoordinator(T0);
        d.nextRead(T0);d.onFrame(0x74,3,0,T0+40);
        DiscoveryCoordinator.Read r=read(d,0x74);
        check(r.outcome==DiscoveryCoordinator.Outcome.REJECTED&&r.status==3&&r.attempts==1,"Nonzero status rejects with its code");
        check(Integer.valueOf(0x70).equals(d.nextRead(T0+340)),"Rejected read is not retried");
        d.onFrame(0x74,0,17,T0+400);check(read(d,0x74).outcome==DiscoveryCoordinator.Outcome.REJECTED,"A later success does not overwrite a rejection");
    }
    private static void duplicates() {
        DiscoveryCoordinator d=new DiscoveryCoordinator(T0);
        d.nextRead(T0);d.onFrame(0x74,0,17,T0+10);d.onFrame(0x74,4,9,T0+20);d.onFrame(0x74,0,3,T0+30);
        DiscoveryCoordinator.Read r=read(d,0x74);
        check(r.outcome==DiscoveryCoordinator.Outcome.READ_SUPPORTED&&r.length==17&&r.firstMs==T0+10&&r.status==0,"Duplicate notification counted once");
        d.onFrame(0x76,0,16,T0+40);check(read(d,0x76).outcome==DiscoveryCoordinator.Outcome.PENDING,"An unrequested reply is ignored");
        d.onFrame(0x7a,0,5,T0+50);d.onFrame(0x63,0,1,T0+50);
        for(DiscoveryCoordinator.Read x:d.reads())check(x.cmd!=0x7a&&x.cmd!=0x63,"No PIN rows");
    }
    private static void malformed() {
        DiscoveryCoordinator d=new DiscoveryCoordinator(T0);
        d.nextRead(T0);d.onFrame(0x74,0,16,T0+10);
        check(read(d,0x74).outcome==DiscoveryCoordinator.Outcome.MALFORMED,"Short serial reply is malformed");
        d.nextRead(T0+300);d.onFrame(0x70,0,12,T0+310);
        check(read(d,0x70).outcome==DiscoveryCoordinator.Outcome.MALFORMED,"Short status reply is malformed");
        d.nextRead(T0+600);d.onFrame(0x73,0,0,T0+610);
        check(read(d,0x73).outcome==DiscoveryCoordinator.Outcome.READ_SUPPORTED&&read(d,0x73).length==0,"Zero length is a reply, not a mapping");
    }
    private static void interruption() {
        for(int stop=0;stop<=DiscoveryCoordinator.ORDER.length;stop++){
            DiscoveryCoordinator d=new DiscoveryCoordinator(T0);long t=T0;int answered=0;
            while(answered<stop){Integer cmd=d.nextRead(t);if(cmd!=null){d.onFrame(cmd,0,cmd==0x70?13:cmd==0x74?17:4,t+5);answered++;}t+=100;}
            if(stop<DiscoveryCoordinator.ORDER.length){while(d.nextRead(t)==null)t+=100;}
            d.onDisconnect(t+10);
            check(d.finished(t+10)&&d.interrupted()&&d.nextRead(t+5000)==null,"Disconnect ends the check at read "+stop);
            for(int i=0;i<DiscoveryCoordinator.ORDER.length;i++){
                DiscoveryCoordinator.Read r=d.reads().get(i);
                check(i<stop?r.outcome==DiscoveryCoordinator.Outcome.READ_SUPPORTED:r.outcome==DiscoveryCoordinator.Outcome.INTERRUPTED,"Outcome after disconnect at "+stop+": "+r);
            }
            d.onFrame(0x79,0,2,t+20);check(read(d,0x79).outcome!=DiscoveryCoordinator.Outcome.READ_SUPPORTED||stop>8,"No updates after the check ended");
        }
        DiscoveryCoordinator d=new DiscoveryCoordinator(T0);d.nextRead(T0);d.interrupt(T0+10);
        check(read(d,0x74).outcome==DiscoveryCoordinator.Outcome.INTERRUPTED&&d.interrupted(),"Owner stop interrupts");
    }
    private static void timing() {
        DiscoveryCoordinator d=new DiscoveryCoordinator(T0);
        long t=T0;for(int i=0;i<9;i++){Integer cmd;while((cmd=d.nextRead(t))==null)t+=100;d.onFrame(cmd,0,cmd==0x70?13:cmd==0x74?17:4,t+5);}
        check(t<T0+10000&&!d.finished(t+10),"All answered but the passive window is still open");
        check(!d.finished(T0+9999)&&d.finished(T0+10000),"Finishes when the 10 s window elapses");
        check(d.nextRead(T0+10001)==null,"Nothing after finishing");
        DiscoveryCoordinator capped=new DiscoveryCoordinator(T0);capped.nextRead(T0);
        check(!capped.finished(T0+29999),"Still running before the cap");
        check(capped.finished(T0+30000),"Hard cap at 30 s");
        for(DiscoveryCoordinator.Read r:capped.reads())check(r.resolved(),"Every read resolved at the cap");
        check(DiscoveryCoordinator.maxAttempts(ReadPolicy.Cls.CORE)==3&&DiscoveryCoordinator.maxAttempts(ReadPolicy.Cls.OPTIONAL)==2&&DiscoveryCoordinator.maxAttempts(ReadPolicy.Cls.PROBE)==1,"Attempts per class");
    }
    private static void passive() {
        DiscoveryCoordinator d=new DiscoveryCoordinator(T0);
        d.onFrame(0x90,0,18,T0+100);d.onFrame(0x90,0,9,T0+200);d.onFrame(0x92,0,16,T0+300);
        check(d.passive().size()==3,"Three passive reports");
        for(DiscoveryCoordinator.Read p:d.passive()){
            if(p.cmd==0x90)check(p.outcome==DiscoveryCoordinator.Outcome.READ_SUPPORTED&&p.length==18,"First 0x90 length kept");
            else if(p.cmd==0x91)check(p.outcome==DiscoveryCoordinator.Outcome.PENDING&&p.length==-1,"0x91 not seen stays unknown");
            else check(p.outcome==DiscoveryCoordinator.Outcome.READ_SUPPORTED&&p.length==16,"0x92 seen");
        }
        for(DiscoveryCoordinator.Read r:d.reads())check(r.outcome==DiscoveryCoordinator.Outcome.PENDING,"Passive reports never resolve reads");
    }
    /** The emitted command set is a subset of the known reads, under every reply pattern. */
    private static void zeroWrites() {
        Set<Integer> allowed=new HashSet<>(Arrays.asList(0x70,0x71,0x72,0x73,0x74,0x75,0x76,0x79,0x7b));
        java.util.Random random=new java.util.Random(7);
        for(int run=0;run<400;run++){
            DiscoveryCoordinator d=new DiscoveryCoordinator(T0);
            for(long t=T0;!d.finished(t)&&t<T0+40000;t+=random.nextInt(400)+1){
                Integer cmd=d.nextRead(t);
                if(cmd!=null){
                    check(allowed.contains(cmd),"Only known reads: "+cmd);
                    int roll=random.nextInt(4);
                    if(roll==0)d.onFrame(cmd,0,random.nextInt(60),t+random.nextInt(3000));
                    else if(roll==1)d.onFrame(cmd,1+random.nextInt(9),0,t+5);
                }
                if(random.nextInt(50)==0)d.onFrame(0x90+random.nextInt(3),0,random.nextInt(30),t);
                if(random.nextInt(800)==0)d.onDisconnect(t);
            }
            check(d.finished(T0+40000),"Every run ends by the cap");
        }
        for(int c:DiscoveryCoordinator.ORDER)check(c!=0x7a&&c!=0x30&&c!=0x31&&c<0x80&&c>=0x70,"ORDER has only reads");
    }
    private static void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}
}
