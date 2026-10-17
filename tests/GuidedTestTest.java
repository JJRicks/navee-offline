package local.navee.offlinelock;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Guided reversible tests: eligibility, the happy path, every wire outcome, stale and duplicate
 * replies, an interruption at every phase, process death around each journal commit, conflicts,
 * restart with zero writes, and each resume branch.
 */
public final class GuidedTestTest {
    private static int assertions;
    private static final long SEEN=100000,NOW=SEEN+1000,WALL=1700000000000L;
    private static final String PROFILE="owner-1";
    public static void main(String[] args) throws Exception {
        eligibility(); gate(); happyPaths(); rejected(); ackWithoutReadback(); readbackUnchanged(); readbackDifferent(); staleAndDuplicates();
        writeRefused(); disconnectAtEveryPhase(); processDeath(); profileSwitch(); conflicts(); restartZeroWrites(); resume(); preconditions(); vocabulary();
        System.out.println("GuidedTest tests passed: "+assertions+" assertions");
    }

    // ---- Fixtures --------------------------------------------------------------------------------

    static final class Memory implements TestJournal.Storage {
        String text; int writes; boolean failWrites,failReads;
        @Override public String read() throws IOException{if(failReads)throw new IOException("unreadable");return text;}
        @Override public void writeAtomic(String t) throws IOException{if(failWrites)throw new IOException("disk full");text=t;writes++;}
    }
    private static final DeviceDescriptor.Firmware FW=new DeviceDescriptor.Firmware("2.0.3.1",null,null,null,null,SEEN);
    static DeviceDescriptor ut5(){return DeviceDescriptor.of("Q2538ZZ9US7777777","",0,FW,true,DeviceDescriptor.Transport.STANDARD,0);}
    static DeviceDescriptor oem(){return DeviceDescriptor.of("Q2443ZZ9US7777777","24431",1,FW,true,DeviceDescriptor.Transport.STANDARD,0);}
    static DeviceDescriptor shared(){return DeviceDescriptor.of("Q9999ZZ9US7777777","",0,FW,true,DeviceDescriptor.Transport.STANDARD,0);}
    /** Units km (1), range set 1 → {0,2,3} with D-mode (2), tail light on, automatic lights on, turn sound off, unlocked. */
    static byte[] data(){return CapabilityResolverTest.full();}
    static StatusFields status(byte[] d,long seen){return CapabilityResolverTest.status(d,seen);}
    static StatusFields with(int offset,int value){byte[] d=data();d[offset]=(byte)value;return status(d,SEEN);}
    static CapabilityResolver.Runtime parked(){CapabilityResolver.Runtime rt=CapabilityResolverTest.parked();rt.nowMs=NOW;return rt;}
    static FeatureVariant variant(String feature,DeviceDescriptor d){
        CapabilityResolver.Decision decision=CapabilityResolver.decideTest(feature,d,status(data(),SEEN),parked());
        check(decision.available(),"Testable "+feature+" on "+d+": "+decision.reasonText());
        return decision.variant;
    }
    /** Start a test and capture the baseline from a 0x70 requested after the start. */
    static GuidedTest prepared(TestJournal j,DeviceDescriptor d,String feature,int target,StatusFields s){
        GuidedTest t=new GuidedTest(j,PROFILE,d,variant(feature,d),target,NOW,WALL,new Random(7));
        check(t.phase()==GuidedTest.Phase.PRECHECK&&t.wantsRead()&&t.writeNow()==null,"Prepared: nothing written, a read is wanted");
        t.onReadRequested(0x70,NOW);t.onStatus(s,NOW);
        check(t.phase()==GuidedTest.Phase.BASELINE&&t.writeNow()==null&&t.nothingWritten(),"Baseline captured, still nothing written");
        return t;
    }
    /** Approve, take the target write, ACK it and send the follow-up read. */
    static void sendTarget(GuidedTest t,int cmd){
        check(t.approve(NOW),"Approved");
        Integer v=t.writeNow();check(v!=null&&v==t.target&&t.writeNow()==null,"Target handed out exactly once");
        t.onAck(cmd,0,NOW+50);t.onReadRequested(0x70,NOW+300);
    }
    static void sendRestore(GuidedTest t,int cmd){
        Integer v=t.writeNow();check(v!=null&&v.equals(t.baseline())&&t.writeNow()==null,"Baseline handed out exactly once");
        t.onAck(cmd,0,NOW+1050);t.onReadRequested(0x70,NOW+1300);
    }
    private static List<GuidedTest.Outcome> list(GuidedTest.Outcome... o){return Arrays.asList(o);}

    // ---- Eligibility (C4.3) -----------------------------------------------------------------------

    private static void eligibility() {
        StatusFields s=status(data(),SEEN);
        for(ScooterCommands.Feature f:ScooterCommands.catalog()){
            boolean listed=ModelRegistry.testable(f.key);
            for(DeviceDescriptor d:new DeviceDescriptor[]{ut5(),oem(),shared()}){
                CapabilityResolver.Decision decision=CapabilityResolver.decideTest(f.key,d,s,parked());
                if(!listed)check(!decision.available()&&decision.reason==CapabilityResolver.Reason.NOT_TESTABLE,"Excluded from tests: "+f.key);
            }
            if(listed)check(!f.experimental&&!f.sensitive&&!f.requiresBond&&f.type!=ScooterCommands.Type.TIME&&f.cmd!=0x51,"Testable features are plain, non-Lab, non-lock "+f.key);
        }
        check(CapabilityResolver.decideTest("clock_sync",ut5(),s,parked()).reason==CapabilityResolver.Reason.NOT_TESTABLE,"Clock is never a test");
        for(String excluded:new String[]{"lock","pin","proximity_key","lock_expiry","maximum_speed","speed_limit","start_speed","acceleration","dual_drive","drive_speed_limit",
                "cruise_control","energy_recovery","traction_control","hill_start_assist","overspeed_control","low_power","long_range_mode","charge_limit","timed_charging","charge_start","line_color"})
            check(!ModelRegistry.testable(excluded),"Excluded family "+excluded);
        check(Arrays.equals(ModelRegistry.TESTABLE,new String[]{"display_units","headlight","turn_sound","auto_sensor","range_algorithm"}),"Exactly the C4.3 table");
        for(String f:ModelRegistry.TESTABLE){
            check(variant(f,ut5()).id.equals(f+".ut5"),"UT5 MAX tests use today's variant "+f);
            check(variant(f,oem()).id.endsWith(".oem")||variant(f,oem()).id.startsWith("range_algorithm.oem"),"OEM groups test the OEM variant "+f);
        }
        check(variant("display_units",shared()).id.equals("display_units.shared"),"Unidentified scooters may test units only");
        for(String f:new String[]{"headlight","turn_sound","auto_sensor","range_algorithm"})
            check(CapabilityResolver.decideTest(f,shared(),s,parked()).reason==CapabilityResolver.Reason.MODEL_NOT_IDENTIFIED,"Shared layer tests nothing else: "+f);
        // Report-only (D3): the shared test variant enables no ordinary control.
        check(!CapabilityResolver.decide("display_units",shared(),s,parked()).available(),"A shared units test maps nothing");
        // OEM exclusions still apply; a conflict and other layers are refused.
        check(CapabilityResolver.decideTest("turn_sound",ModelRegistryTest.advertised("25731"),s,parked()).reason==CapabilityResolver.Reason.MODEL_EXCLUDED,"OEM 2.1.9 hides turn sound for 2573");
        check(CapabilityResolver.decideTest("display_units",ModelRegistryTest.both("Q2443ZZ9US7777777","26201"),s,parked()).reason==CapabilityResolver.Reason.IDENTITY_CONFLICT,"Identity conflict: no tests");
        check(CapabilityResolver.decideTest("display_units",ModelRegistryTest.advertised("27361"),s,parked()).reason==CapabilityResolver.Reason.TRANSPORT_UNSUPPORTED,"Other transports: no tests");
        check(CapabilityResolver.decideTest("display_units",ModelRegistryTest.advertised("24351"),s,parked()).reason==CapabilityResolver.Reason.PRODUCT_UNSUPPORTED,"Golf: no tests");
        // A D1 mismatch keeps the UT5 MAX layer and its tests.
        check(CapabilityResolver.decideTest("display_units",ModelRegistryTest.both("Q2538ZZ9US7777777","26201"),s,parked()).available(),"D1: 2538 keeps its tests");
        try{new GuidedTest(new TestJournal(new Memory()),PROFILE,ut5(),ModelRegistry.SHARED_LOCK,0,NOW,WALL,new Random(1));check(false,"Lock test refused");}catch(IllegalArgumentException expected){check(true,"Lock test refused");}
        FeatureVariant lab=CapabilityResolver.decide("speed_limit",ut5(),s,parked()).variant;
        try{new GuidedTest(new TestJournal(new Memory()),PROFILE,ut5(),lab,10,NOW,WALL,new Random(1));check(false,"Lab test refused");}catch(IllegalArgumentException expected){check(true,"Lab test refused");}
    }

    private static void preconditions() {
        StatusFields s=status(data(),SEEN);
        CapabilityResolver.Runtime rt=parked();rt.ready=false;
        check(CapabilityResolver.decideTest("display_units",ut5(),s,rt).reason==CapabilityResolver.Reason.NOT_CONNECTED,"Needs a connection");
        rt=parked();rt.simulation=true;check(CapabilityResolver.decideTest("display_units",ut5(),s,rt).reason==CapabilityResolver.Reason.NEEDS_REAL_SCOOTER,"Never in simulation");
        rt=parked();rt.discovery=true;check(CapabilityResolver.decideTest("display_units",ut5(),s,rt).reason==CapabilityResolver.Reason.DISCOVERY_ACTIVE,"Not during a check");
        rt=parked();rt.busy=true;check(CapabilityResolver.decideTest("display_units",ut5(),s,rt).reason==CapabilityResolver.Reason.BUSY,"No pending mutation");
        rt=parked();rt.recording=true;check(CapabilityResolver.decideTest("display_units",ut5(),s,rt).reason==CapabilityResolver.Reason.RECORDING,"Not while recording a ride");
        rt=parked();rt.nowMs=SEEN+7001;check(CapabilityResolver.decideTest("display_units",ut5(),s,rt).reason==CapabilityResolver.Reason.STATUS_STALE,"Fresh status");
        check(CapabilityResolver.decideTest("display_units",ut5(),status(Arrays.copyOf(data(),7),SEEN),parked()).reason==CapabilityResolver.Reason.FIELD_ABSENT,"Field present");
        check(CapabilityResolver.decideTest("display_units",oem(),with(7,9),parked()).reason==CapabilityResolver.Reason.VALUE_UNKNOWN,"Baseline inside the domain");
        rt=parked();rt.stationary=false;check(CapabilityResolver.decideTest("display_units",ut5(),s,rt).reason==CapabilityResolver.Reason.MOVING,"Moving");
        rt=parked();rt.stationary=false;rt.motionSeen=false;check(CapabilityResolver.decideTest("display_units",ut5(),s,rt).reason==CapabilityResolver.Reason.PARKED_UNCONFIRMED,"No motion report: ask");
        rt.parkedConfirmed=true;check(CapabilityResolver.decideTest("display_units",ut5(),s,rt).available(),"No motion report and the owner ticked parked");
        rt=parked();rt.stationary=false;rt.motionSeen=true;rt.parkedConfirmed=true;check(CapabilityResolver.decideTest("display_units",ut5(),s,rt).reason==CapabilityResolver.Reason.MOVING,"A tick never overrides a motion report");
        rt=parked();rt.test=true;rt.testFeature="turn_sound";check(CapabilityResolver.decideTest("display_units",ut5(),s,rt).reason==CapabilityResolver.Reason.TEST_ACTIVE,"One test at a time");
        check(CapabilityResolver.decideTest("turn_sound",ut5(),s,rt).available(),"The running test's own feature");
        rt=parked();rt.owner=false;check(CapabilityResolver.decideTest("display_units",ut5(),s,rt).available(),"Shared role may test");
        check(CapabilityResolver.decideTest("headlight",oem(),with(2,1),parked()).reason==CapabilityResolver.Reason.LOCKED,"OEM light rows need unlocked");
        check(CapabilityResolver.decideTest("range_algorithm",ut5(),with(15,7),parked()).reason==CapabilityResolver.Reason.NO_CHOICES,"Range: dynamic choices only");
        check(!CapabilityResolver.admitTest("range_algorithm",1,ut5(),s,parked()).available()&&CapabilityResolver.admitTest("range_algorithm",3,ut5(),s,parked()).available(),"Range target must be offered now");
        // While a test runs every ordinary change waits, including the test's own feature.
        rt=parked();rt.test=true;rt.testFeature="display_units";
        check(CapabilityResolver.decide("display_units",ut5(),s,rt).reason==CapabilityResolver.Reason.TEST_ACTIVE&&CapabilityResolver.decide("cruise_control",ut5(),s,rt).reason==CapabilityResolver.Reason.TEST_ACTIVE,"Ordinary changes wait for the test");
    }

    private static void gate() {
        StatusFields s=status(data(),SEEN);
        MutationGate.Request test=new MutationGate.Request(MutationGate.Kind.SET,"display_units",0,MutationGate.Origin.GUIDED_TEST);
        MutationGate.Request restore=new MutationGate.Request(MutationGate.Kind.SET,"display_units",1,MutationGate.Origin.GUIDED_RESTORE);
        check(MutationGate.check(test,shared(),s,parked()).reason==CapabilityResolver.Reason.NO_TEST,"No guided write without a running test");
        CapabilityResolver.Runtime rt=parked();rt.test=true;rt.testFeature="turn_sound";
        check(MutationGate.check(test,shared(),s,rt).reason==CapabilityResolver.Reason.TEST_ACTIVE,"Only the running test's feature");
        rt.testFeature="display_units";
        for(DeviceDescriptor d:new DeviceDescriptor[]{ut5(),oem(),shared()}){
            check(MutationGate.check(test,d,s,rt).available()&&MutationGate.check(restore,d,s,rt).available(),"Guided writes for the running test "+d);
            check(MutationGate.check(new MutationGate.Request(MutationGate.Kind.SET,"display_units",5,MutationGate.Origin.GUIDED_TEST),d,s,rt).reason==CapabilityResolver.Reason.MODEL_EXCLUDED,"Guided value inside the domain");
        }
        check(MutationGate.check(new MutationGate.Request(MutationGate.Kind.SET,"display_units",0,MutationGate.Origin.USER),shared(),s,rt).reason!=CapabilityResolver.Reason.NONE,"D3: the owner still cannot use it on a shared scooter");
        check(!MutationGate.check(new MutationGate.Request(MutationGate.Kind.PIN_SET,"pin",1,MutationGate.Origin.GUIDED_TEST),ut5(),s,rt).available(),"No guided PIN");
        rt.testFeature="lock";check(!MutationGate.check(new MutationGate.Request(MutationGate.Kind.SET,"lock",1,MutationGate.Origin.GUIDED_TEST),ut5(),s,rt).available(),"No guided lock");
        rt=parked();rt.test=true;rt.testFeature="display_units";rt.stationary=false;rt.motionSeen=false;
        check(MutationGate.check(restore,ut5(),s,rt).reason==CapabilityResolver.Reason.PARKED_UNCONFIRMED,"Guided writes keep the parked rule");
    }

    // ---- Transactions -------------------------------------------------------------------------------

    private static void happyPaths() throws Exception {
        for(DeviceDescriptor d:new DeviceDescriptor[]{ut5(),oem(),shared()}){
            Memory m=new Memory();TestJournal j=new TestJournal(m);
            GuidedTest t=prepared(j,d,"display_units",0,status(data(),SEEN));
            check(t.baseline()==1,"Baseline km");
            sendTarget(t,0x55);
            check(j.unresolved(PROFILE).size()==1&&j.unresolved(PROFILE).get(0).phase.equals("TARGET_AWAIT")||j.unresolved(PROFILE).get(0).phase.equals("TARGET_PENDING"),"Write-ahead entry exists");
            t.onStatus(with(7,0),NOW+400);
            check(t.phase()==GuidedTest.Phase.OBSERVE&&t.outcomes().equals(list(GuidedTest.Outcome.SETTING_CONFIRMED)),"Setting confirmed");
            check(j.unresolved(PROFILE).get(0).phase.equals("OBSERVE"),"Observe journalled");
            t.ownerEffect(true,NOW+800);
            check(t.phase()==GuidedTest.Phase.RESTORE_PENDING&&j.unresolved(PROFILE).get(0).phase.equals("RESTORE_PENDING"),"Restore journalled before the write");
            sendRestore(t,0x55);t.onStatus(with(7,1),NOW+1400);
            check(t.phase()==GuidedTest.Phase.DONE&&t.outcomes().equals(list(GuidedTest.Outcome.SETTING_CONFIRMED,GuidedTest.Outcome.EFFECT_OBSERVED,GuidedTest.Outcome.RESTORATION_CONFIRMED)),"Happy path outcomes "+t.outcomes());
            check(t.restorationStatus().equals("confirmed")&&j.unresolved(PROFILE).isEmpty()&&j.all(PROFILE).size()==1&&j.all(PROFILE).get(0).resolved,"Resolved as confirmed");
            TestJournal.Entry e=j.all(PROFILE).get(0);
            check(e.effect.equals("observed")&&e.baseline==1&&e.target==0&&e.registryVersion==ModelRegistry.VERSION&&e.meterFw.equals("2.0.3.1")&&e.pidPrefix.equals(d.family()),"Entry fields");
            check(e.elapsedMs.equals(Arrays.asList(400L,800L,1400L)),"Times are relative to approval "+e.elapsedMs);
            check(t.writeNow()==null,"Nothing more to write");
        }
        // Owner did not see it, or skipped: restoration still happens and the effect is explicit.
        for(Boolean seen:new Boolean[]{false,null}){
            TestJournal j=new TestJournal(new Memory());GuidedTest t=prepared(j,oem(),"turn_sound",1,status(data(),SEEN));
            sendTarget(t,0x60);t.onStatus(with(12,1),NOW+400);t.ownerEffect(seen,NOW+500);sendRestore(t,0x60);t.onStatus(with(12,0),NOW+1400);
            check(t.outcomes().equals(list(GuidedTest.Outcome.SETTING_CONFIRMED,GuidedTest.Outcome.RESTORATION_CONFIRMED))&&j.all(PROFILE).get(0).effect.equals(seen==null?"not_checked":"not_observed"),"Effect "+seen);
        }
        // Range estimate: a dynamic choice.
        TestJournal j=new TestJournal(new Memory());GuidedTest t=prepared(j,ut5(),"range_algorithm",3,status(data(),SEEN));
        sendTarget(t,0x56);t.onStatus(with(6,3),NOW+400);t.ownerEffect(true,NOW+500);sendRestore(t,0x56);t.onStatus(with(6,2),NOW+1400);
        check(t.phase()==GuidedTest.Phase.DONE&&t.restorationStatus().equals("confirmed"),"Range test restored");
        // Preparing a test whose target equals the baseline, or an absent field, writes nothing.
        GuidedTest same=new GuidedTest(j,PROFILE,ut5(),variant("display_units",ut5()),1,NOW,WALL,new Random(2));
        same.onReadRequested(0x70,NOW);same.onStatus(status(data(),SEEN),NOW);
        check(same.phase()==GuidedTest.Phase.DONE&&same.nothingWritten()&&!same.approve(NOW)&&same.writeNow()==null,"Target equal to baseline: no test");
        GuidedTest absent=new GuidedTest(j,PROFILE,ut5(),variant("display_units",ut5()),0,NOW,WALL,new Random(2));
        absent.onReadRequested(0x70,NOW);absent.onStatus(status(Arrays.copyOf(data(),7),SEEN),NOW);
        check(absent.phase()==GuidedTest.Phase.DONE&&absent.nothingWritten(),"Absent field: no test");
        // A baseline is never taken from a reply to a read requested before the test started.
        GuidedTest early=new GuidedTest(j,PROFILE,ut5(),variant("display_units",ut5()),0,NOW,WALL,new Random(2));
        early.onStatus(status(data(),SEEN),NOW);
        check(early.phase()==GuidedTest.Phase.PRECHECK,"Unrequested reply is not a baseline");
        // The journal cannot be written: the test ends with nothing written.
        Memory full=new Memory();TestJournal failing=new TestJournal(full);GuidedTest f=prepared(failing,ut5(),"display_units",0,status(data(),SEEN));
        full.failWrites=true;
        check(!f.approve(NOW)&&f.phase()==GuidedTest.Phase.DONE&&f.writeNow()==null&&f.nothingWritten()&&f.note().contains("Nothing was changed"),"No write without a journal commit");
        // Cancel before approval.
        GuidedTest c=prepared(j,ut5(),"display_units",0,status(data(),SEEN));
        check(c.cancel()&&c.phase()==GuidedTest.Phase.DONE&&c.writeNow()==null&&!c.approve(NOW),"Cancel writes nothing");
    }

    private static void rejected() {
        TestJournal j=new TestJournal(new Memory());GuidedTest t=prepared(j,oem(),"display_units",0,status(data(),SEEN));
        check(t.approve(NOW)&&t.writeNow()==0,"Target sent");
        t.onAck(0x55,3,NOW+50);
        check(t.outcomes().equals(list(GuidedTest.Outcome.REJECTED))&&t.outcomeTexts().get(0).equals("Rejected, code 3"),"Rejected with code");
        check(t.phase()==GuidedTest.Phase.RESTORE_AWAIT&&t.wantsRead()&&t.writeNow()==null,"No restore write after a rejection, only a verifying read");
        t.onAck(0x55,0,NOW+60);check(t.outcomes().size()==1,"A late duplicate ACK after a rejection is ignored");
        t.onReadRequested(0x70,NOW+100);t.onStatus(with(7,1),NOW+150);
        check(t.phase()==GuidedTest.Phase.DONE&&t.outcomes().equals(list(GuidedTest.Outcome.REJECTED,GuidedTest.Outcome.RESTORATION_CONFIRMED))&&t.restorationStatus().equals("confirmed"),"Rejected then verified unchanged");
        // Rejected but the readback shows the target anyway: outstanding, never success.
        GuidedTest odd=prepared(j,oem(),"display_units",0,status(data(),SEEN));odd.approve(NOW);odd.writeNow();odd.onAck(0x55,7,NOW+50);
        odd.onReadRequested(0x70,NOW+100);odd.onStatus(with(7,0),NOW+150);
        check(odd.phase()==GuidedTest.Phase.OUTSTANDING&&odd.restorationStatus().equals("outstanding"),"Rejected yet changed: restoration outstanding");
        // The restore write rejected.
        GuidedTest r=prepared(j,oem(),"display_units",0,status(data(),SEEN));sendTarget(r,0x55);r.onStatus(with(7,0),NOW+400);r.ownerEffect(true,NOW+500);
        r.writeNow();r.onAck(0x55,2,NOW+600);
        check(r.phase()==GuidedTest.Phase.OUTSTANDING&&r.restorationStatus().equals("outstanding")&&r.outcomeTexts().contains("Rejected, code 2"),"Restore rejected: outstanding");
    }

    private static void ackWithoutReadback() {
        TestJournal j=new TestJournal(new Memory());GuidedTest t=prepared(j,ut5(),"display_units",0,status(data(),SEEN));
        sendTarget(t,0x55);t.onMutationTimeout(true,NOW+6500);
        check(t.phase()==GuidedTest.Phase.OUTSTANDING&&t.outcomes().equals(list(GuidedTest.Outcome.ACK_UNCONFIRMED))&&t.restorationStatus().equals("outstanding"),"ACK without readback");
        check(j.unresolved(PROFILE).size()==1&&j.unresolved(PROFILE).get(0).phase.equals("OUTSTANDING")&&t.writeNow()==null,"Outstanding and journalled, no write");
        GuidedTest silent=prepared(j,ut5(),"display_units",0,status(data(),SEEN));silent.approve(NOW);silent.writeNow();silent.onMutationTimeout(false,NOW+6500);
        check(silent.outcomes().equals(list(GuidedTest.Outcome.INTERRUPTED))&&silent.phase()==GuidedTest.Phase.OUTSTANDING,"No reply at all: interrupted");
    }

    private static void readbackUnchanged() {
        TestJournal j=new TestJournal(new Memory());GuidedTest t=prepared(j,ut5(),"turn_sound",1,status(data(),SEEN));
        sendTarget(t,0x60);t.onStatus(status(data(),SEEN+400),NOW+400);
        check(t.phase()==GuidedTest.Phase.DONE&&t.outcomes().equals(list(GuidedTest.Outcome.READBACK_UNCHANGED,GuidedTest.Outcome.RESTORATION_CONFIRMED)),"Unchanged needs no restore");
        check(t.restorationStatus().equals("confirmed")&&j.unresolved(PROFILE).isEmpty()&&t.writeNow()==null,"Resolved by readback, no write");
        // The restore leaves the test value in place.
        GuidedTest r=prepared(j,ut5(),"turn_sound",1,status(data(),SEEN));sendTarget(r,0x60);r.onStatus(with(12,1),NOW+400);r.ownerEffect(null,NOW+500);
        sendRestore(r,0x60);r.onStatus(with(12,1),NOW+1400);
        check(r.phase()==GuidedTest.Phase.OUTSTANDING&&r.outcomes().get(r.outcomes().size()-1)==GuidedTest.Outcome.READBACK_UNCHANGED&&r.restorationStatus().equals("outstanding"),"Restore unchanged: outstanding");
    }

    private static void readbackDifferent() {
        TestJournal j=new TestJournal(new Memory());GuidedTest t=prepared(j,ut5(),"range_algorithm",3,status(data(),SEEN));
        sendTarget(t,0x56);t.onStatus(with(6,0),NOW+400);
        check(t.phase()==GuidedTest.Phase.CONFLICT&&t.outcomes().equals(list(GuidedTest.Outcome.READBACK_DIFFERENT))&&t.note().contains("Theoretical"),"Readback different: conflict shows the value");
        check(t.writeNow()==null&&j.unresolved(PROFILE).get(0).phase.equals("CONFLICT"),"Conflict needs the owner");
        GuidedTest r=prepared(j,ut5(),"range_algorithm",3,status(data(),SEEN));sendTarget(r,0x56);r.onStatus(with(6,3),NOW+400);r.ownerEffect(true,NOW+500);
        sendRestore(r,0x56);r.onStatus(with(6,0),NOW+1400);
        check(r.phase()==GuidedTest.Phase.CONFLICT&&r.restorationStatus().equals("outstanding"),"Restore readback different: conflict");
    }

    private static void staleAndDuplicates() {
        TestJournal j=new TestJournal(new Memory());GuidedTest t=prepared(j,ut5(),"display_units",0,status(data(),SEEN));
        t.onReadRequested(0x70,NOW);                         // a poll sent before the write
        check(t.approve(NOW)&&t.writeNow()==0,"Target sent");
        t.onStatus(status(data(),SEEN),NOW+20);              // its reply before the ACK
        check(t.phase()==GuidedTest.Phase.TARGET_AWAIT,"A pre-ACK reply is ignored");
        t.onReadRequested(0x70,NOW+30);                      // another poll, still before the ACK
        t.onAck(0x55,0,NOW+50);t.onAck(0x55,0,NOW+51);      // duplicate ACK
        t.onReadRequested(0x70,NOW+300);                     // the follow-up read
        t.onStatus(status(data(),SEEN+40),NOW+310);          // reply to the pre-ACK poll (stale)
        check(t.phase()==GuidedTest.Phase.TARGET_AWAIT&&t.outcomes().isEmpty(),"A reply to a read sent before the ACK never confirms");
        t.onStatus(with(7,0),NOW+320);
        check(t.phase()==GuidedTest.Phase.OBSERVE&&t.outcomes().equals(list(GuidedTest.Outcome.SETTING_CONFIRMED)),"The follow-up reply confirms");
        t.onStatus(with(7,0),NOW+321);t.onAck(0x55,0,NOW+322);
        check(t.phase()==GuidedTest.Phase.OBSERVE&&t.outcomes().size()==1,"Duplicate notifications change nothing");
        // An unaccepted reply still counts as an answer, but never as a reading.
        GuidedTest u=prepared(j,ut5(),"display_units",0,status(data(),SEEN));sendTarget(u,0x55);
        u.onStatus(StatusFields.of(StatusFieldsTest.notification(0x70,1,data()),SEEN),NOW+310);
        check(u.phase()==GuidedTest.Phase.TARGET_AWAIT,"An error reply is not a reading");
        u.onStatus(with(7,0),NOW+330);check(u.phase()==GuidedTest.Phase.OBSERVE,"The next accepted reply is conclusive");
        // Another command's ACK is not ours.
        GuidedTest o=prepared(j,ut5(),"display_units",0,status(data(),SEEN));o.approve(NOW);o.writeNow();o.onAck(0x54,0,NOW+40);o.onReadRequested(0x70,NOW+50);o.onStatus(with(7,0),NOW+60);
        check(o.phase()==GuidedTest.Phase.TARGET_AWAIT,"Only the variant's command acknowledges");
    }

    private static void writeRefused() {
        TestJournal j=new TestJournal(new Memory());GuidedTest t=prepared(j,ut5(),"display_units",0,status(data(),SEEN));
        t.approve(NOW);t.writeNow();t.onWriteRefused(NOW+10);
        check(t.phase()==GuidedTest.Phase.RESTORE_AWAIT&&t.wantsRead()&&t.writeNow()==null,"Refused target: verify only");
        t.onReadRequested(0x70,NOW+20);t.onStatus(status(data(),SEEN+20),NOW+30);
        check(t.phase()==GuidedTest.Phase.DONE&&t.outcomes().equals(list(GuidedTest.Outcome.INTERRUPTED,GuidedTest.Outcome.RESTORATION_CONFIRMED)),"Verified unchanged");
        GuidedTest r=prepared(j,ut5(),"display_units",0,status(data(),SEEN));sendTarget(r,0x55);r.onStatus(with(7,0),NOW+400);r.ownerEffect(true,NOW+500);r.writeNow();r.onWriteRefused(NOW+510);
        check(r.phase()==GuidedTest.Phase.OUTSTANDING&&r.restorationStatus().equals("outstanding"),"Refused restore: outstanding");
    }

    /** Runs a test to a given phase, then interrupts it. */
    private static void disconnectAtEveryPhase() {
        for(GuidedTest.Phase stop:GuidedTest.Phase.values()){
            if(stop==GuidedTest.Phase.DONE||stop==GuidedTest.Phase.OUTSTANDING||stop==GuidedTest.Phase.CONFLICT)continue;
            for(int how=0;how<3;how++){
                Memory m=new Memory();TestJournal j=new TestJournal(m);
                GuidedTest t=runTo(j,stop);
                check(t.phase()==stop,"Reached "+stop+" got "+t.phase());
                if(how==0)t.onDisconnect(NOW+5000);else if(how==1)t.onProfileSwitch(NOW+5000);else t.onProcessStart(NOW+5000);
                boolean before=stop==GuidedTest.Phase.PRECHECK||stop==GuidedTest.Phase.BASELINE;
                if(before)check(t.phase()==GuidedTest.Phase.DONE&&t.nothingWritten()&&j.all(PROFILE).isEmpty()&&m.writes==0,"Interrupted before a write at "+stop+": nothing journalled");
                else{
                    check(t.phase()==GuidedTest.Phase.OUTSTANDING&&t.restorationStatus().equals("outstanding"),"Interrupted at "+stop+": outstanding");
                    check(t.outcomes().get(t.outcomes().size()-1)==GuidedTest.Outcome.INTERRUPTED,"Interrupted outcome at "+stop);
                    check(j.unresolved(PROFILE).size()==1&&j.unresolved(PROFILE).get(0).phase.equals("OUTSTANDING"),"Journalled outstanding at "+stop);
                }
                // Nothing is written afterwards, whatever arrives.
                t.onAck(0x55,0,NOW+6000);t.onReadRequested(0x70,NOW+6000);t.onStatus(with(7,1),NOW+6001);t.onStatus(with(7,0),NOW+6002);t.ownerEffect(true,NOW+6003);
                check(t.writeNow()==null&&!t.approve(NOW+6004)&&t.writeNow()==null,"No write after an interruption at "+stop);
            }
        }
    }
    private static int seed=100;
    static GuidedTest runTo(TestJournal j,GuidedTest.Phase stop){
        GuidedTest t=new GuidedTest(j,PROFILE,ut5(),variant("display_units",ut5()),0,NOW,WALL,new Random(++seed));
        if(stop==GuidedTest.Phase.PRECHECK)return t;
        t.onReadRequested(0x70,NOW);t.onStatus(status(data(),SEEN),NOW);if(stop==GuidedTest.Phase.BASELINE)return t;
        t.approve(NOW);if(stop==GuidedTest.Phase.TARGET_PENDING)return t;
        t.writeNow();if(stop==GuidedTest.Phase.TARGET_AWAIT)return t;
        t.onAck(0x55,0,NOW+50);t.onReadRequested(0x70,NOW+300);t.onStatus(with(7,0),NOW+400);if(stop==GuidedTest.Phase.OBSERVE)return t;
        t.ownerEffect(true,NOW+500);if(stop==GuidedTest.Phase.RESTORE_PENDING)return t;
        t.writeNow();return t;
    }

    private static void processDeath() {
        // Before the target commit: nothing to replay.
        Memory m=new Memory();TestJournal j=new TestJournal(m);GuidedTest t=prepared(j,ut5(),"display_units",0,status(data(),SEEN));
        String beforeTarget=m.text;
        check(t.approve(NOW),"Approved");String afterTarget=m.text;
        t.writeNow();t.onAck(0x55,0,NOW+50);t.onReadRequested(0x70,NOW+300);t.onStatus(with(7,0),NOW+400);
        String beforeRestore=m.text;
        t.ownerEffect(true,NOW+500);String afterRestore=m.text;
        check(beforeTarget==null&&afterTarget!=null&&!afterTarget.equals(beforeRestore)&&!beforeRestore.equals(afterRestore),"Each phase is a separate commit");
        Memory dead=new Memory();dead.text=beforeTarget;TestJournal replay=new TestJournal(dead);
        check(GuidedTest.recover(replay,WALL+60000)==0&&replay.unresolved().isEmpty(),"Death before the target commit: nothing outstanding");
        String[][] cases={{afterTarget,"TARGET_PENDING"},{beforeRestore,"OBSERVE"},{afterRestore,"RESTORE_PENDING"}};
        for(String[] c:cases){
            dead=new Memory();dead.text=c[0];replay=new TestJournal(dead);
            check(replay.unresolved(PROFILE).size()==1&&replay.unresolved(PROFILE).get(0).phase.equals(c[1]),"Saved phase "+c[1]);
            check(GuidedTest.recover(replay,WALL+60000)==1,"Recovered "+c[1]);
            TestJournal.Entry e=replay.unresolved(PROFILE).get(0);
            check(e.phase.equals("OUTSTANDING")&&e.restoration.equals("outstanding")&&e.outcomes.get(e.outcomes.size()-1).equals("interrupted"),"Death at "+c[1]+": outstanding, interrupted");
            GuidedTest back=GuidedTest.fromJournal(replay,e);
            check(back.phase()==GuidedTest.Phase.OUTSTANDING&&back.writeNow()==null&&back.terminal(),"Replay never writes ("+c[1]+")");
            check(GuidedTest.recover(new TestJournal(dead),WALL+70000)==0,"Recovery is idempotent");
        }
    }

    private static void profileSwitch() {
        TestJournal j=new TestJournal(new Memory());GuidedTest t=runTo(j,GuidedTest.Phase.OBSERVE);
        t.onProfileSwitch(NOW+700);
        check(t.phase()==GuidedTest.Phase.OUTSTANDING&&j.unresolved(PROFILE).size()==1&&j.unresolved("other").isEmpty(),"Profile switch: outstanding and kept for its own profile");
        check(j.unresolved().size()==1,"Still listed across profiles");
    }

    private static void conflicts() {
        // The owner (or another app) changes it back while we wait for the observation.
        TestJournal j=new TestJournal(new Memory());GuidedTest t=runTo(j,GuidedTest.Phase.OBSERVE);
        t.onStatus(with(7,1),NOW+600);
        check(t.phase()==GuidedTest.Phase.CONFLICT&&t.note().startsWith("Changed outside the test")&&t.writeNow()==null,"External change: conflict, no write");
        t.ownerEffect(true,NOW+700);check(t.writeNow()==null,"No restore after a conflict");
        // A related field (the range choice set) changes during the test.
        GuidedTest r=prepared(j,ut5(),"range_algorithm",3,status(data(),SEEN));sendTarget(r,0x56);
        byte[] d=data();d[6]=3;d[15]=2;r.onStatus(status(d,SEEN+400),NOW+400);
        check(r.phase()==GuidedTest.Phase.CONFLICT&&r.note().contains("related"),"Coupled field change at confirmation: conflict");
        GuidedTest o=prepared(j,ut5(),"range_algorithm",3,status(data(),SEEN));sendTarget(o,0x56);o.onStatus(with(6,3),NOW+400);
        d=data();d[6]=3;d[15]=2;o.onStatus(status(d,SEEN+500),NOW+500);
        check(o.phase()==GuidedTest.Phase.CONFLICT,"Coupled field change while observing: conflict");
    }

    private static void restartZeroWrites() {
        Memory m=new Memory();TestJournal j=new TestJournal(m);
        for(GuidedTest.Phase p:new GuidedTest.Phase[]{GuidedTest.Phase.TARGET_PENDING,GuidedTest.Phase.TARGET_AWAIT,GuidedTest.Phase.OBSERVE,GuidedTest.Phase.RESTORE_PENDING,GuidedTest.Phase.RESTORE_AWAIT})runTo(j,p);
        check(j.unresolved(PROFILE).size()==5,"Five tests left mid-way");
        TestJournal restarted=new TestJournal(m);GuidedTest.recover(restarted,WALL+1000);
        int writes=0;
        for(TestJournal.Entry e:restarted.unresolved(PROFILE)){
            GuidedTest t=GuidedTest.fromJournal(restarted,e);
            for(int i=0;i<3;i++){t.onReadRequested(0x70,NOW+i);t.onStatus(with(7,i%2),NOW+i);t.onAck(0x55,0,NOW+i);t.ownerEffect(true,NOW+i);t.onProcessStart(NOW+i);if(t.writeNow()!=null)writes++;}
            check(t.phase()==GuidedTest.Phase.OUTSTANDING,"Replayed test stays outstanding");
        }
        check(writes==0,"Restart produces zero writes");
        check(restarted.unresolved(PROFILE).size()==5,"All five stay visible until resolved");
    }

    private static void resume() throws Exception {
        TestJournal j=new TestJournal(new Memory());GuidedTest t=runTo(j,GuidedTest.Phase.OBSERVE);t.onDisconnect(NOW+600);
        TestJournal.Entry e=j.unresolved(PROFILE).get(0);FeatureVariant v=variant("display_units",ut5());long now=NOW;
        // Preconditions for review.
        check(GuidedTest.review(e,PROFILE,false,ut5(),with(7,0),now,v,false).state==GuidedTest.ReviewState.WAITING,"Review needs a connection");
        check(GuidedTest.review(e,"other",true,ut5(),with(7,0),now,v,false).state==GuidedTest.ReviewState.WAITING,"Review needs the same profile");
        check(GuidedTest.review(e,PROFILE,true,ut5(),with(7,0),SEEN+8000,v,false).state==GuidedTest.ReviewState.WAITING,"Review needs a fresh reading");
        check(GuidedTest.review(e,PROFILE,true,ut5(),status(Arrays.copyOf(data(),7),SEEN),now,v,false).state==GuidedTest.ReviewState.WAITING,"Review needs the field");
        check(GuidedTest.review(e,PROFILE,true,oem(),with(7,0),now,v,false).state==GuidedTest.ReviewState.MODEL_CHANGED,"Different model evidence: no restore");
        check(GuidedTest.review(e,PROFILE,true,ut5(),with(7,0),now,variant("display_units",shared()),false).state==GuidedTest.ReviewState.MODEL_CHANGED,"Different test rules: no restore");
        DeviceDescriptor newer=ut5().withFirmware(new DeviceDescriptor.Firmware("2.0.4.0",null,null,null,null,SEEN),true),unread=ut5().withFirmware(FW,false);
        GuidedTest.Review fw=GuidedTest.review(e,PROFILE,true,newer,with(7,0),now,v,false);
        check(fw.state==GuidedTest.ReviewState.FIRMWARE_CHANGED&&fw.text.equals("Firmware changed since the test."),"Firmware changed");
        check(GuidedTest.review(e,PROFILE,true,unread,with(7,0),now,v,false).state==GuidedTest.ReviewState.FIRMWARE_CHANGED,"Firmware not read this session");
        check(GuidedTest.review(e,PROFILE,true,newer,with(7,0),now,v,true).state==GuidedTest.ReviewState.RESTORE_NEEDED,"Confirmed firmware change continues");
        // Already restored: no write.
        GuidedTest.Review done=GuidedTest.review(e,PROFILE,true,ut5(),with(7,1),now,v,false);
        check(done.state==GuidedTest.ReviewState.ALREADY_RESTORED&&!done.restorable,"Already restored");
        TestJournal copy=new TestJournal(new Memory());copy.commit(e);
        GuidedTest.confirmAlreadyRestored(copy,e,WALL+5000);
        TestJournal.Entry resolved=copy.find(e.testId);
        check(resolved.resolved&&resolved.restoration.equals("confirmed")&&resolved.resolution.equals("already_restored")&&resolved.outcomes.get(resolved.outcomes.size()-1).equals("restoration_confirmed"),"Already restored: confirmed by readback");
        // Restore needed: one explicit tap, journalled before the write.
        GuidedTest.Review need=GuidedTest.review(e,PROFILE,true,ut5(),with(7,0),now,v,false);
        check(need.state==GuidedTest.ReviewState.RESTORE_NEEDED&&need.restorable&&need.current==0,"Restore needed");
        GuidedTest restore=GuidedTest.restoreFrom(j,e,v,ut5(),with(7,0),now,WALL+9000);
        check(restore.phase()==GuidedTest.Phase.RESTORE_PENDING&&j.find(e.testId).phase.equals("RESTORE_PENDING"),"Restore journalled first");
        check(restore.writeNow()==1&&restore.writeNow()==null,"Restore writes the baseline once");
        restore.onAck(0x55,0,now+50);restore.onReadRequested(0x70,now+300);restore.onStatus(with(7,1),now+400);
        check(restore.phase()==GuidedTest.Phase.DONE&&j.unresolved(PROFILE).isEmpty()&&j.find(e.testId).restoration.equals("confirmed"),"Restored after review");
        // Changed outside the test: Keep (declined) or Set the baseline.
        TestJournal k=new TestJournal(new Memory());GuidedTest r=prepared(k,ut5(),"range_algorithm",3,status(data(),SEEN));sendTarget(r,0x56);r.onStatus(with(6,3),NOW+400);r.onDisconnect(NOW+500);
        TestJournal.Entry re=k.unresolved(PROFILE).get(0);FeatureVariant rv=variant("range_algorithm",ut5());
        GuidedTest.Review outside=GuidedTest.review(re,PROFILE,true,ut5(),with(6,0),now,rv,false);
        check(outside.state==GuidedTest.ReviewState.CHANGED_OUTSIDE&&outside.text.equals("Changed outside the test (now Theoretical).")&&outside.restorable,"Changed outside");
        k.resolve(re.testId,TestJournal.DECLINED,"kept",WALL+10000);
        check(k.unresolved(PROFILE).isEmpty()&&k.find(re.testId).restoration.equals("declined"),"Keep records declined, never confirmed");
        // The baseline is no longer offered (choice set changed): restore is refused.
        byte[] d=data();d[6]=0;d[15]=0;
        GuidedTest.Review gone=GuidedTest.review(re,PROFILE,true,ut5(),status(d,SEEN),now,rv,false);
        check(gone.state==GuidedTest.ReviewState.CHANGED_OUTSIDE&&!gone.restorable,"Baseline not offered: cannot restore");
        try{GuidedTest.restoreFrom(k,re,rv,ut5(),status(d,SEEN),now,WALL);check(false,"Resolved test cannot restore");}catch(IllegalArgumentException expected){check(true,"Resolved test cannot restore");}
        // Dismiss records declined.
        TestJournal x=new TestJournal(new Memory());runTo(x,GuidedTest.Phase.TARGET_AWAIT).onDisconnect(NOW);
        TestJournal.Entry xe=x.unresolved(PROFILE).get(0);x.resolve(xe.testId,TestJournal.DECLINED,"dismissed",WALL+1);
        check(x.find(xe.testId).restoration.equals("declined")&&x.unresolved().isEmpty(),"Dismiss records declined");
    }

    private static void vocabulary() {
        String[] texts={"Read supported","Rejected, code N","Acknowledged, unconfirmed","Readback unchanged","Readback different","Setting confirmed","Effect observed","Restoration confirmed","Interrupted/no response"};
        GuidedTest.Outcome[] all=GuidedTest.Outcome.values();
        check(all.length==texts.length,"Nine outcomes");
        for(int i=0;i<all.length;i++){
            check(all[i].text.equals(texts[i]),"docs/03 wording "+all[i]);
            check(GuidedTest.Outcome.parse(all[i].token(42))==all[i],"Token round trip "+all[i]);
        }
        check(GuidedTest.Outcome.REJECTED.text(3).equals("Rejected, code 3")&&GuidedTest.Outcome.describe("rejected_3").equals("Rejected, code 3"),"Code substituted");
        check(GuidedTest.Outcome.parse("rejected_256")==null&&GuidedTest.Outcome.parse("rejected_")==null&&GuidedTest.Outcome.parse("REJECTED")==null&&GuidedTest.Outcome.parse("ok")==null,"Unknown tokens refused");
    }

    static void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}
}
