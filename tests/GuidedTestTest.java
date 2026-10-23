package com.jjricks.volt;

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
        tries(); sliderTries(); reportedThenSetBack(); staleReport(); writeRefused(); disconnectAtEveryPhase(); processDeath(); profileSwitch(); conflicts(); restartZeroWrites(); resume(); preconditions(); vocabulary(); inheritedPolls(); lostPollTimeout(); queuedWriteState(); outOfOrderReplies();
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

    // ---- Experimental controls tries ------------------------------------------------------------

    /** A try is a guided test that keeps its value: one journalled write, readback, the owner's answer, no restore write. */
    private static GuidedTest tried(TestJournal j,DeviceDescriptor d,FeatureVariant v,String kind,int target,int expected,StatusFields s){
        GuidedTest t=GuidedTest.trial(j,PROFILE,d,v,kind,target,expected,NOW,WALL,new Random(9));
        check(t.phase()==GuidedTest.Phase.PRECHECK&&t.wantsRead()&&t.writeNow()==null&&t.keeps(),"A try prepares like a test");
        t.onReadRequested(0x70,NOW);t.onStatus(s,NOW);return t;
    }
    private static void tries() throws IOException {
        DeviceDescriptor d=oem();StatusFields s=status(data(),SEEN);
        FeatureVariant prompt=CapabilityResolver.decideExperimental("prompt_tone",d,s,parked()).variant,units=CapabilityResolver.decideVerify("display_units",d,s,parked()).variant;
        check(prompt.lab&&!units.lab,"Fixture variants: an experimental and a Help verify one");
        try{GuidedTest.trial(new TestJournal(new Memory()),PROFILE,d,prompt,TestJournal.TEST,4,2,NOW,WALL,new Random(1));check(false,"A try has a try kind");}catch(IllegalArgumentException expected){check(true,"A try has a try kind");}
        // Worked here, the owner's answer, kept: no restore write, then Set back reopens it.
        Memory m=new Memory();TestJournal j=new TestJournal(m);
        GuidedTest t=tried(j,d,prompt,TestJournal.EXPERIMENTAL,4,2,s);
        check(t.phase()==GuidedTest.Phase.BASELINE&&m.writes==0,"Baseline captured; nothing journalled or written yet");
        sendTarget(t,0x6c);t.onStatus(with(21,4),NOW+400);
        TestJournal.Entry e=j.find(t.testId());
        check(t.phase()==GuidedTest.Phase.OBSERVE&&e.kind.equals("experimental")&&e.layer.equals("oem_group")&&e.phase.equals("OBSERVE")&&!e.resolved,"Journalled as an experimental try before the answer: "+TestJournal.line(e));
        t.ownerEffect(true,NOW+600);
        e=j.find(t.testId());
        check(t.phase()==GuidedTest.Phase.DONE&&t.writeNow()==null,"Kept: no restore write");
        check(e.resolved&&e.resolution.equals("kept")&&e.restoration.equals(TestJournal.DECLINED)&&e.effect.equals("observed")&&CompatibilityReport.tryResult(e).equals("worked_here"),"Worked here, seen, kept: "+TestJournal.line(e));
        GuidedTest back=GuidedTest.restoreFrom(j,e,prompt,d,with(21,4),NOW+5000,WALL+5000);
        check(!j.find(t.testId()).resolved&&j.find(t.testId()).phase.equals("RESTORE_PENDING"),"Set back reopens the try in the journal before writing");
        sendRestore(back,0x6c);back.onStatus(with(21,2),NOW+6000);
        e=j.find(t.testId());
        check(back.phase()==GuidedTest.Phase.DONE&&e.resolved&&e.restoration.equals(TestJournal.CONFIRMED)&&e.resolution.equals("restored")&&e.kind.equals("experimental"),"Set back confirmed: "+TestJournal.line(e));
        try{GuidedTest.restoreFrom(j,e,prompt,d,with(21,2),NOW+7000,WALL+7000);check(false,"Nothing to set back once restored");}catch(IllegalArgumentException expected){check(true,"Nothing to set back once restored");}
        // The value changed since the sheet showed it: nothing written.
        Memory m2=new Memory();GuidedTest moved=tried(new TestJournal(m2),d,prompt,TestJournal.EXPERIMENTAL,4,1,s);
        check(moved.phase()==GuidedTest.Phase.DONE&&moved.nothingWritten()&&moved.note().contains("now shows")&&m2.writes==0,"A changed baseline ends the try with nothing written");
        // Help verify: the same flow under its own kind.
        Memory m3=new Memory();TestJournal j3=new TestJournal(m3);GuidedTest v=tried(j3,d,units,TestJournal.VERIFY,0,1,s);
        sendTarget(v,0x55);v.onStatus(with(7,0),NOW+400);v.ownerEffect(null,NOW+500);
        check(j3.find(v.testId()).kind.equals("verify")&&j3.find(v.testId()).effect.equals("not_checked")&&j3.find(v.testId()).resolution.equals("kept"),"Help verify try kept, not answered");
        // Interrupted while asking: process death leaves it for review (never written again by itself).
        Memory m4=new Memory();TestJournal j4=new TestJournal(m4);GuidedTest asking=tried(j4,d,prompt,TestJournal.EXPERIMENTAL,4,2,s);
        sendTarget(asking,0x6c);asking.onStatus(with(21,4),NOW+400);int writes=m4.writes;
        TestJournal reopened=new TestJournal(m4);check(GuidedTest.recover(reopened,WALL+9000)==1,"Recovered after a restart");
        e=reopened.find(asking.testId());
        check(e.phase.equals("OUTSTANDING")&&!e.resolved&&e.outcomes.get(e.outcomes.size()-1).equals("interrupted")&&m4.writes==writes+1,"Unfinished, waiting for review: "+TestJournal.line(e));
        GuidedTest.Review r=GuidedTest.review(e,PROFILE,true,d,with(21,4),NOW,prompt,false);
        check(r.state==GuidedTest.ReviewState.RESTORE_NEEDED&&r.restorable,"Review offers to set it back");
        GuidedTest restore=GuidedTest.restoreFrom(reopened,e,prompt,d,with(21,4),NOW,WALL+9500);sendRestore(restore,0x6c);restore.onStatus(with(21,2),NOW+1500);
        check(reopened.find(asking.testId()).resolved&&reopened.find(asking.testId()).restoration.equals(TestJournal.CONFIRMED),"Restored after review");
        // Every other ending, as the report and the row name it; an interrupted write is never "not sent" or "no change".
        GuidedTest refused=tried(new TestJournal(new Memory()),d,prompt,TestJournal.EXPERIMENTAL,4,2,s);
        check(refused.approve(NOW)&&refused.writeNow()==4,"Write handed out");refused.onWriteRefused(NOW+10);refused.onReadRequested(0x70,NOW+20);refused.onStatus(with(21,2),NOW+30);
        check(refused.phase()==GuidedTest.Phase.DONE&&CompatibilityReport.tryResult(refused.entry()).equals("not_sent"),"Refused by Volt before sending: Not sent "+refused.outcomeTexts());
        GuidedTest rejected=tried(new TestJournal(new Memory()),d,prompt,TestJournal.EXPERIMENTAL,4,2,s);
        check(rejected.approve(NOW)&&rejected.writeNow()==4,"Write handed out");rejected.onAck(0x6c,3,NOW+50);rejected.onReadRequested(0x70,NOW+60);rejected.onStatus(with(21,2),NOW+70);
        check(rejected.phase()==GuidedTest.Phase.DONE&&CompatibilityReport.tryResult(rejected.entry()).equals("refused"),"Rejected: Refused by scooter");
        GuidedTest unchanged=tried(new TestJournal(new Memory()),d,prompt,TestJournal.EXPERIMENTAL,4,2,s);
        sendTarget(unchanged,0x6c);unchanged.onStatus(with(21,2),NOW+400);
        check(unchanged.phase()==GuidedTest.Phase.DONE&&CompatibilityReport.tryResult(unchanged.entry()).equals("no_change_seen")&&unchanged.entry().resolved,"Acknowledged, read back unchanged: No change seen");
        GuidedTest timeout=tried(new TestJournal(new Memory()),d,prompt,TestJournal.EXPERIMENTAL,4,2,s);
        check(timeout.approve(NOW)&&timeout.writeNow()==4,"Write handed out");timeout.onAck(0x6c,0,NOW+50);timeout.onMutationTimeout(true,NOW+6500);
        check(timeout.phase()==GuidedTest.Phase.OUTSTANDING&&CompatibilityReport.tryResult(timeout.entry()).equals("unconfirmed"),"Acknowledged, never read back: Unconfirmed, left for review");
        GuidedTest dropped=tried(new TestJournal(new Memory()),d,prompt,TestJournal.EXPERIMENTAL,4,2,s);
        check(dropped.approve(NOW)&&dropped.writeNow()==4,"Write handed out");dropped.onDisconnect(NOW+20);
        check(dropped.phase()==GuidedTest.Phase.OUTSTANDING&&CompatibilityReport.tryResult(dropped.entry()).equals("interrupted"),"Dropped after sending: Unconfirmed (interrupted), never not sent");
        GuidedTest other=tried(new TestJournal(new Memory()),d,prompt,TestJournal.EXPERIMENTAL,4,2,s);
        sendTarget(other,0x6c);other.onStatus(with(21,3),NOW+400);
        check(other.phase()==GuidedTest.Phase.CONFLICT&&CompatibilityReport.tryResult(other.entry()).equals("other_value"),"Another value: Other value read, left for review");
    }

    /**
     * The UT5 MAX speed caps are sliders: a min/max/step domain with no listed choices, and bit 7 of the status byte is
     * the cap's own enable flag. Off → 10 km/h and 10 km/h → Off each journal one write, read back, keep the value and
     * Set back; an interrupted one is reviewed and restored. The coupled-field guard still holds for masked assists.
     */
    private static void sliderTries() throws IOException {
        DeviceDescriptor d=ut5();
        String[] keys={"speed_limit","drive_speed_limit"};int[] cmds={0x6b,0x6e},offsets={20,42};
        for(int k=0;k<keys.length;k++){
            String key=keys[k];int cmd=cmds[k],at=offsets[k];
            for(boolean fromOff:new boolean[]{true,false}){
                final int before=fromOff?0:10,after=fromOff?10:0;String step=key+" "+before+" → "+after;
                StatusFields start=with(at,fromOff?0x00:0x8a),tried=with(at,fromOff?0x8a:0x00);
                CapabilityResolver.Decision admitted=CapabilityResolver.admitTry(key,after,d,start,parked());
                check(admitted.available()&&admitted.experimental()&&admitted.variant.values.length==0&&admitted.values().length==0,step+": a slider try is admitted "+admitted+" "+admitted.reasonText());
                FeatureVariant v=admitted.variant;
                Memory m=new Memory();TestJournal j=new TestJournal(m);
                GuidedTest t=tried(j,d,v,TestJournal.EXPERIMENTAL,after,before,start);
                check(t.phase()==GuidedTest.Phase.BASELINE&&t.baseline()==before&&m.writes==0,step+": baseline captured inside the slider's domain, not refused: "+t.phase()+" "+t.note());
                check(t.approve(NOW)&&m.writes==1,step+": journalled before the write");
                Integer sent=t.writeNow();
                check(sent!=null&&sent==after&&t.writeNow()==null&&Arrays.equals(v.encode(after),ScooterCommands.set(key,after)),step+": one write with the UT5 MAX bytes");
                t.onAck(cmd,0,NOW+50);t.onReadRequested(0x70,NOW+300);t.onStatus(tried,NOW+400);
                check(t.phase()==GuidedTest.Phase.OBSERVE,step+": confirmed; the enable bit is the cap's own, not a related setting: "+t.phase()+" "+t.note());
                // Off may keep the old limit in the low bits; bit 7 clear still reads Off and changes nothing else.
                if(!fromOff)t.onStatus(with(at,0x0a),NOW+450);
                check(t.phase()==GuidedTest.Phase.OBSERVE,step+": a remembered limit behind Off is not a change outside the try");
                t.ownerEffect(true,NOW+500);
                TestJournal.Entry e=j.find(t.testId());
                check(e.resolved&&"kept".equals(e.resolution)&&e.related==-1&&CompatibilityReport.tryResult(e).equals("worked_here"),step+": kept "+TestJournal.line(e));
                GuidedTest back=GuidedTest.restoreFrom(j,e,v,d,tried,NOW+5000,WALL+5000);
                Integer restore=back.writeNow();
                check(restore!=null&&restore==before,step+": Set back sends "+before);
                back.onAck(cmd,0,NOW+5050);back.onReadRequested(0x70,NOW+5300);back.onStatus(start,NOW+5400);
                e=j.find(t.testId());
                check(back.phase()==GuidedTest.Phase.DONE&&e.resolved&&e.restoration.equals(TestJournal.CONFIRMED)&&e.resolution.equals("restored"),step+": Set back confirmed "+back.note()+" "+TestJournal.line(e));
                // Interrupted after the target: the review offers the slider's earlier value, and Restore writes it once.
                Memory m2=new Memory();TestJournal j2=new TestJournal(m2);
                GuidedTest cut=tried(j2,d,v,TestJournal.EXPERIMENTAL,after,before,start);
                check(cut.approve(NOW)&&cut.writeNow()==after,step+": interrupted try sent");cut.onAck(cmd,0,NOW+50);cut.onReadRequested(0x70,NOW+300);cut.onStatus(tried,NOW+400);
                TestJournal reopened=new TestJournal(m2);check(GuidedTest.recover(reopened,WALL+9000)==1,step+": recovered after a restart");
                TestJournal.Entry open=reopened.find(cut.testId());
                GuidedTest.Review r=GuidedTest.review(open,PROFILE,true,d,tried,NOW,v,false);
                check(r.state==GuidedTest.ReviewState.RESTORE_NEEDED&&r.restorable,step+": review offers the earlier value "+r.state+" "+r.text);
                GuidedTest restored=GuidedTest.restoreFrom(reopened,open,v,d,tried,NOW,WALL+9500);
                check(restored.writeNow()==before,step+": Restore sends "+before);
                restored.onAck(cmd,0,NOW+1050);restored.onReadRequested(0x70,NOW+1300);restored.onStatus(start,NOW+1400);
                check(reopened.find(cut.testId()).resolved&&reopened.find(cut.testId()).restoration.equals(TestJournal.CONFIRMED),step+": restored after review");
                check(GuidedTest.review(reopened.find(cut.testId()),PROFILE,true,d,start,NOW,v,false).state==GuidedTest.ReviewState.ALREADY_RESTORED,step+": then already back");
            }
            // A value outside the slider's domain (1..7 km/h for the custom cap, 1..4 for the drive cap) is never tried.
            FeatureVariant v=CapabilityResolver.decideTry(key,d,with(at,0),parked()).variant;
            try{GuidedTest.trial(new TestJournal(new Memory()),PROFILE,d,v,TestJournal.EXPERIMENTAL,3,0,NOW,WALL,new Random(3));check(false,key+": 3 km/h refused");}
            catch(IllegalArgumentException expected){check(true,key+": 3 km/h refused");}
        }
        // A masked assist keeps its coupled-field guard: another assist's bit changing during the try is a conflict.
        DeviceDescriptor n65i=DeviceDescriptor.of("","22131",1,FW,true,DeviceDescriptor.Transport.STANDARD,0);
        StatusFields s=status(data(),SEEN);
        CapabilityResolver.Decision assist=CapabilityResolver.admitTry("hill_start_assist",0,n65i,s,parked());
        check(assist.available()&&assist.experimental()&&(assist.variant.mask&255)!=255,"Fixture: a masked assist try "+assist+" "+assist.reasonText());
        GuidedTest coupled=tried(new TestJournal(new Memory()),n65i,assist.variant,TestJournal.EXPERIMENTAL,0,1,s);
        check(coupled.approve(NOW)&&coupled.writeNow()==0,"Assist try sent");coupled.onAck(assist.variant.cmd,0,NOW+50);coupled.onReadRequested(0x70,NOW+300);
        coupled.onStatus(with(37,4|2),NOW+400);
        check(coupled.phase()==GuidedTest.Phase.CONFLICT&&coupled.note().contains("related"),"Another assist changed with the try: conflict "+coupled.note());
    }

    /**
     * A report is a snapshot: sharing, saving or emailing it acknowledges only the entries that still read as it shows
     * them. The Codex case: Preview shows an interrupted try outstanding, Restore then confirms it, the stale preview is
     * saved or shared, and one more try overflows the journal (19 older, unreported results of another scooter).
     */
    private static void staleReport() throws IOException {
        DeviceDescriptor d=oem();StatusFields s=status(data(),SEEN);
        FeatureVariant prompt=CapabilityResolver.decideExperimental("prompt_tone",d,s,parked()).variant;
        for(int mode=0;mode<3;mode++){
            // 0: stale preview acknowledged after the restore; 1: acknowledged before it (still matching); 2: a fresh preview after it.
            String what=mode==0?"stale preview":mode==1?"matching preview":"fresh preview";
            Memory m=new Memory();TestJournal j=new TestJournal(m);
            for(int i=0;i<TestJournal.MAX_ENTRIES-1;i++){
                GuidedTest other=GuidedTest.trial(j,"owner-2",d,prompt,TestJournal.EXPERIMENTAL,4,2,NOW,WALL+i,new Random(100+i));
                other.onReadRequested(0x70,NOW);other.onStatus(s,NOW);sendTarget(other,0x6c);other.onStatus(with(21,4),NOW+400);other.ownerEffect(true,NOW+500);
            }
            GuidedTest cut=GuidedTest.trial(j,PROFILE,d,prompt,TestJournal.EXPERIMENTAL,4,2,NOW,WALL+1000,new Random(200));
            cut.onReadRequested(0x70,NOW);cut.onStatus(s,NOW);sendTarget(cut,0x6c);cut.onStatus(with(21,4),NOW+400);cut.onDisconnect(NOW+500);
            String id=cut.testId();
            CompatibilityReport preview=new CompatibilityReport.Builder("1.6.0",new Random(1)).tests(j.all(PROFILE)).build();
            check(preview.json().replaceAll("\\s","").contains("\"restoration\":\"outstanding\"")&&preview.included().size()==1,what+": the preview shows the try outstanding");
            if(mode==1){j.markReported(preview.included(),WALL+1500);check(j.find(id).reported,what+": a snapshot that still matches is acknowledged");}
            GuidedTest back=GuidedTest.restoreFrom(j,j.find(id),prompt,d,with(21,4),NOW+2000,WALL+2000);
            sendRestore(back,0x6c);back.onStatus(with(21,2),NOW+3000);
            check(j.find(id).restoration.equals(TestJournal.CONFIRMED)&&!j.find(id).reported,what+": restored after the preview, unreported");
            if(mode==0)j.markReported(preview.included(),WALL+3500);
            if(mode==2)j.markReported(new CompatibilityReport.Builder("1.6.0",new Random(2)).tests(j.all(PROFILE)).build().included(),WALL+3500);
            check(j.find(id).reported==(mode==2),what+": the confirmed restoration is acknowledged only by a report that shows it");
            check(!new TestJournal(m).find(id).reported==(mode!=2),what+": the same on disk");
            GuidedTest next=GuidedTest.trial(j,PROFILE,d,prompt,TestJournal.EXPERIMENTAL,4,2,NOW,WALL+4000,new Random(300));
            next.onReadRequested(0x70,NOW);next.onStatus(s,NOW);sendTarget(next,0x6c);
            check((j.find(id)!=null)==(mode!=2)&&j.all("owner-2").size()==(mode!=2?TestJournal.MAX_ENTRIES-2:TestJournal.MAX_ENTRIES-1),
                what+": one more try evicts "+(mode!=2?"the oldest unreported result; the restoration stays":"the reported restoration first"));
        }
    }

    /**
     * Set back adds evidence no sent report contained: the try is unreported again, so the overflow keeps it. The Codex
     * case: a kept, reported try, 19 unreported ones, the confirmed Set back, then one more try.
     */
    private static void reportedThenSetBack() throws IOException {
        DeviceDescriptor d=oem();StatusFields s=status(data(),SEEN);
        FeatureVariant prompt=CapabilityResolver.decideExperimental("prompt_tone",d,s,parked()).variant;
        Memory m=new Memory();TestJournal j=new TestJournal(m);
        GuidedTest t=tried(j,d,prompt,TestJournal.EXPERIMENTAL,4,2,s);sendTarget(t,0x6c);t.onStatus(with(21,4),NOW+400);t.ownerEffect(true,NOW+500);
        String first=t.testId();
        j.markReported(Arrays.asList(TestJournal.revision(j.find(first))),WALL+1000);
        check(j.find(first).reported,"Reported as kept");
        for(int i=0;i<TestJournal.MAX_ENTRIES-1;i++){
            TestJournal.Entry filler=j.find(first).copy();filler.testId=String.format(java.util.Locale.ROOT,"%016x",0xf00+i);filler.reported=false;
            filler.createdWall=filler.updatedWall=WALL+2000+i;j.commit(filler);
        }
        check(j.all(PROFILE).size()==TestJournal.MAX_ENTRIES&&j.find(first).reported,"Full: the reported try and 19 unreported ones");
        GuidedTest back=GuidedTest.restoreFrom(j,j.find(first),prompt,d,with(21,4),NOW+5000,WALL+5000);
        check(!j.find(first).reported&&!back.entry().reported,"Reopened for Set back: no longer reported");
        sendRestore(back,0x6c);back.onStatus(with(21,2),NOW+6000);
        TestJournal.Entry restored=j.find(first);
        check(restored.resolved&&restored.restoration.equals(TestJournal.CONFIRMED)&&!restored.reported,"Set back confirmed and unreported: "+TestJournal.line(restored));
        check(!new TestJournal(m).find(first).reported,"Unreported on disk too");
        GuidedTest next=GuidedTest.trial(j,PROFILE,d,prompt,TestJournal.EXPERIMENTAL,4,2,NOW,WALL+7000,new Random(11));next.onReadRequested(0x70,NOW);next.onStatus(s,NOW);sendTarget(next,0x6c);
        check(!next.testId().equals(first),"Fixture: a new try ID");
        check(j.find(first)!=null&&j.find(String.format(java.util.Locale.ROOT,"%016x",0xf00))==null&&j.find(next.testId())!=null&&j.all(PROFILE).size()==TestJournal.MAX_ENTRIES,
            "One more try: the oldest unreported try makes room, the set-back one stays");
        // A reported unfinished try that is then restored, kept or dismissed is unreported again; an unchanged save keeps the flag.
        Memory m2=new Memory();TestJournal k=new TestJournal(m2);
        GuidedTest cut=tried(k,d,prompt,TestJournal.EXPERIMENTAL,4,2,s);sendTarget(cut,0x6c);cut.onStatus(with(21,4),NOW+400);cut.onDisconnect(NOW+500);
        k.markReported(Arrays.asList(TestJournal.revision(k.find(cut.testId()))),WALL+600);
        k.commit(k.find(cut.testId()));
        check(k.find(cut.testId()).reported,"Saving the same record keeps it reported");
        GuidedTest.restoreFrom(k,k.find(cut.testId()),prompt,d,with(21,4),NOW+700,WALL+700);
        check(!k.find(cut.testId()).reported,"Restoring a reported unfinished try: unreported");
        k.markReported(Arrays.asList(TestJournal.revision(k.find(cut.testId()))),WALL+800);
        k.resolve(cut.testId(),TestJournal.DECLINED,"dismissed",WALL+900);
        check(!k.find(cut.testId()).reported,"Dismissing it afterwards: unreported");
    }

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
        check(r.outcomeTexts().get(r.outcomeTexts().size()-1).equals("Readback unchanged, value 1"),"The unchanged value is shown");
    }

    private static void readbackDifferent() {
        TestJournal j=new TestJournal(new Memory());GuidedTest t=prepared(j,ut5(),"range_algorithm",3,status(data(),SEEN));
        sendTarget(t,0x56);t.onStatus(with(6,0),NOW+400);
        check(t.phase()==GuidedTest.Phase.CONFLICT&&t.outcomes().equals(list(GuidedTest.Outcome.READBACK_DIFFERENT))&&t.note().contains("Theoretical"),"Readback different: conflict shows the value");
        check(t.writeNow()==null&&j.unresolved(PROFILE).get(0).phase.equals("CONFLICT"),"Conflict needs the owner");
        check(j.unresolved(PROFILE).get(0).outcomes.equals(Arrays.asList("readback_different_0")),"The value read back is journalled");
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
        check(t.phase()==GuidedTest.Phase.TARGET_AWAIT&&t.wantsRead(),"Mixed pre/post ACK requests drain before a fresh probe");
        t.onReadRequested(0x70,NOW+321);t.onStatus(with(7,0),NOW+322);
        check(t.phase()==GuidedTest.Phase.OBSERVE&&t.outcomes().equals(list(GuidedTest.Outcome.SETTING_CONFIRMED)),"The follow-up reply confirms");
        t.onStatus(with(7,0),NOW+321);t.onAck(0x55,0,NOW+322);
        check(t.phase()==GuidedTest.Phase.OBSERVE&&t.outcomes().size()==1,"Duplicate notifications change nothing");
        // An unaccepted reply still counts as an answer, but never as a reading.
        GuidedTest u=prepared(j,ut5(),"display_units",0,status(data(),SEEN));sendTarget(u,0x55);
        u.onStatus(StatusFields.of(StatusFieldsTest.notification(0x70,1,data()),SEEN),NOW+310);
        check(u.phase()==GuidedTest.Phase.TARGET_AWAIT,"An error reply is not a reading");
        u.onStatus(with(7,0),NOW+320);check(u.phase()==GuidedTest.Phase.TARGET_AWAIT,"An unsolicited reply after an error is not a new probe");
        u.onReadRequested(0x70,NOW+325);u.onStatus(with(7,0),NOW+330);check(u.phase()==GuidedTest.Phase.OBSERVE,"The next accepted probe is conclusive");
        // Another command's ACK is not ours.
        GuidedTest o=prepared(j,ut5(),"display_units",0,status(data(),SEEN));o.approve(NOW);o.writeNow();o.onAck(0x54,0,NOW+40);o.onReadRequested(0x70,NOW+50);o.onStatus(with(7,0),NOW+60);
        check(o.phase()==GuidedTest.Phase.TARGET_AWAIT,"Only the variant's command acknowledges");
    }

    private static void inheritedPolls() throws Exception {
        TestJournal j=new TestJournal(new Memory());FeatureVariant v=variant("display_units",ut5());
        GuidedTest t=new GuidedTest(j,PROFILE,ut5(),v,0,NOW,WALL,new Random(900));
        t.inheritOutstandingReads(1); // ordinary A was already transmitted before startTest
        t.onReadRequested(0x70,NOW+10); // baseline B
        t.onStatus(with(7,1),NOW+20); // A
        check(t.phase()==GuidedTest.Phase.PRECHECK&&!t.approve(NOW+21),"An inherited poll cannot impersonate the new baseline read");
        t.onStatus(with(7,1),NOW+30); // B
        check(t.phase()==GuidedTest.Phase.PRECHECK&&t.wantsRead(),"All inherited/mixed reads drain before baseline probe");
        t.onReadRequested(0x70,NOW+30);t.onStatus(with(7,1),NOW+31);
        check(t.phase()==GuidedTest.Phase.BASELINE&&t.approve(NOW+31)&&t.writeNow()==0,"Approval waits for both pre-write responses");
        t.onAck(v.cmd,0,NOW+40);t.onReadRequested(0x70,NOW+41);
        check(t.phase()==GuidedTest.Phase.TARGET_AWAIT&&!j.unresolved().isEmpty(),"Restore responsibility stays until fresh target readback");
        t.onStatus(with(7,0),NOW+50);
        check(t.phase()==GuidedTest.Phase.OBSERVE&&!t.restorationStatus().equals("confirmed"),"Actual target requires an explicit restore");
        t.onDisconnect(NOW+60);TestJournal.Entry e=t.entry();
        GuidedTest restore=GuidedTest.restoreFrom(j,e,v,ut5(),with(7,0),NOW+70,WALL+70);
        restore.inheritOutstandingReads(1);
        check(restore.writeNow()==1,"Explicit restore still writes baseline once");
        restore.onAck(v.cmd,0,NOW+80);restore.onReadRequested(0x70,NOW+81);
        restore.onStatus(with(7,1),NOW+82); // older read happens to contain baseline: still not proof
        check(restore.phase()==GuidedTest.Phase.RESTORE_AWAIT&&!j.unresolved().isEmpty(),"An inherited reply cannot clear explicit restoration");
        restore.onStatus(with(7,0),NOW+83); // genuine restore verification shows refusal
        check(restore.phase()==GuidedTest.Phase.RESTORE_AWAIT&&restore.wantsRead(),"Mixed restore responses only drain the old requests");
        restore.onReadRequested(0x70,NOW+84);restore.onStatus(with(7,0),NOW+85);
        check(restore.phase()==GuidedTest.Phase.OUTSTANDING&&!j.unresolved().isEmpty(),"Failed actual restore remains outstanding");
    }

    private static void queuedWriteState(){
        TestJournal j=new TestJournal(new Memory());
        GuidedTest t=prepared(j,oem(),"display_units",0,status(data(),SEEN));t.approve(NOW);t.writeNow();
        check(t.admitsQueuedWrite(with(7,1),NOW+20),"Unchanged baseline admits queued target");
        check(!t.admitsQueuedWrite(with(7,0),NOW+30)&&t.phase()==GuidedTest.Phase.CONFLICT,"Outside change while target queued is not overwritten");
        check(!t.admitsQueuedWrite(with(7,1),NOW+40),"Terminal test never revives a queued write");
        GuidedTest r=prepared(j,oem(),"display_units",0,status(data(),SEEN));sendTarget(r,0x55);r.onStatus(with(7,0),NOW+400);r.ownerEffect(true,NOW+500);r.writeNow();
        check(r.admitsQueuedWrite(with(7,0),NOW+510),"Unchanged target admits explicit restoration");
        check(!r.admitsQueuedWrite(with(7,1),NOW+520)&&r.phase()==GuidedTest.Phase.CONFLICT,"A change while restore queued needs a fresh owner review");
    }

    private static void lostPollTimeout(){
        TestJournal j=new TestJournal(new Memory());
        GuidedTest t=new GuidedTest(j,PROFILE,oem(),variant("display_units",oem()),0,NOW,WALL,new Random(901));
        t.inheritOutstandingReads(1);
        for(int i=1;i<5;i++){long at=NOW+i*2000;t.onReadRequested(0x70,at);t.onStatus(status(data(),at),at+20);}
        t.tick(NOW+9999);check(t.phase()==GuidedTest.Phase.PRECHECK,"A short delayed response can still drain");
        t.tick(NOW+10000);check(t.phase()==GuidedTest.Phase.DONE&&t.nothingWritten()&&t.note().contains("Reconnect"),"Lost earlier poll ends preparation with reconnect guidance");
        t.onStatus(with(7,1),NOW+10020);check(t.baseline()==null&&!t.approve(NOW+10030)&&t.writeNow()==null,"Late replies cannot revive timed-out preparation");
        GuidedTest verify=prepared(j,oem(),"display_units",0,status(data(),SEEN));verify.approve(NOW);verify.writeNow();
        verify.onWriteRefused(NOW+50);verify.onReadRequested(0x70,NOW+100);
        verify.tick(NOW+10049);check(verify.verifying(),"Unsent write verification keeps its full bounded window");
        verify.tick(NOW+10050);check(verify.phase()==GuidedTest.Phase.OUTSTANDING&&!j.unresolved().isEmpty(),"Missing verification stays outstanding, never claims restored");
        verify.onStatus(with(7,1),NOW+10070);check(verify.phase()==GuidedTest.Phase.OUTSTANDING,"A late baseline cannot clear an outstanding verification");
    }

    private static void outOfOrderReplies(){
        TestJournal j=new TestJournal(new Memory());GuidedTest t=prepared(j,ut5(),"display_units",0,with(7,1));
        t.onReadRequested(0x70,NOW+10); // A snapshots the pre-write baseline
        check(t.approve(NOW+20)&&t.writeNow()==0,"Target sent with ordinary poll pending");
        t.onAck(0x55,0,NOW+30);check(!t.wantsRead(),"Wait for older poll before confirmation probe");
        t.onReadRequested(0x70,NOW+40); // C: an already queued ordinary poll transmits after ACK
        t.onStatus(with(7,0),NOW+50); // C arrives before A
        t.onStatus(with(7,1),NOW+60); // A is the last reply, but is stale
        check(t.phase()==GuidedTest.Phase.TARGET_AWAIT&&!j.unresolved().isEmpty()&&t.wantsRead(),"Last stale reply cannot falsely confirm restoration");
        t.onReadRequested(0x70,NOW+70);t.onStatus(with(7,0),NOW+80);
        check(t.phase()==GuidedTest.Phase.OBSERVE&&!t.restorationStatus().equals("confirmed"),"New probe sees actual target and preserves restore responsibility");
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
        // A long history of failed restores still leaves room for the final restoration.
        TestJournal.Entry full=e.copy();while(full.outcomes.size()<TestJournal.MAX_OUTCOMES)TestJournal.append(full,"interrupted",1000L*full.outcomes.size());
        TestJournal capped=new TestJournal(new Memory());capped.commit(full);
        GuidedTest.recover(capped,WALL+6000);GuidedTest.confirmAlreadyRestored(capped,capped.find(e.testId),WALL+7000);
        TestJournal.Entry kept=capped.find(e.testId);
        check(kept.resolved&&kept.restoration.equals("confirmed")&&kept.outcomes.size()==TestJournal.MAX_OUTCOMES&&kept.elapsedMs.size()==TestJournal.MAX_OUTCOMES
            &&kept.outcomes.get(0).equals(e.outcomes.get(0))&&kept.outcomes.get(kept.outcomes.size()-1).equals("restoration_confirmed"),"Bounded history keeps the first and latest steps\n"+kept.outcomes);
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
        // The coupled range choice set changed while the test was interrupted: a conflict, never a plain restore.
        check(re.related==data()[15],"Related value journalled");
        byte[] moved=data();moved[6]=3;moved[15]=2;
        GuidedTest.Review coupled=GuidedTest.review(re,PROFILE,true,ut5(),status(moved,SEEN),now,rv,false);
        check(coupled.state==GuidedTest.ReviewState.CHANGED_OUTSIDE&&!coupled.restorable&&coupled.text.contains("related"),"Related change at resume: conflict");
        try{GuidedTest.restoreFrom(k,re,rv,ut5(),status(moved,SEEN),now,WALL);check(false,"Related change refuses the restore");}catch(IllegalArgumentException expected){check(true,"Related change refuses the restore");}
        check(k.find(re.testId).phase.equals("OUTSTANDING")&&k.find(re.testId).related==re.related,"Refused restore leaves the record as it was");
        byte[] same=data();same[6]=3;
        check(GuidedTest.review(re,PROFILE,true,ut5(),status(same,SEEN),now,rv,false).state==GuidedTest.ReviewState.RESTORE_NEEDED,"Unchanged related value: restore offered");
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
        String[] texts={"Read supported","Rejected, code N","Acknowledged, unconfirmed","Readback unchanged","Readback different","Setting confirmed","Effect observed","Restoration confirmed","Interrupted/no response","Not sent"};
        GuidedTest.Outcome[] all=GuidedTest.Outcome.values();
        check(all.length==texts.length,"Ten outcomes (Not sent only for tries)");
        for(int i=0;i<all.length;i++){
            check(all[i].text.equals(texts[i]),"docs/03 wording "+all[i]);
            check(GuidedTest.Outcome.parse(all[i].token(42))==all[i],"Token round trip "+all[i]);
        }
        check(GuidedTest.Outcome.REJECTED.text(3).equals("Rejected, code 3")&&GuidedTest.Outcome.describe("rejected_3").equals("Rejected, code 3"),"Code substituted");
        check(GuidedTest.Outcome.describe("readback_different_7").equals("Readback different, value 7")&&GuidedTest.Outcome.code("readback_unchanged_0")==0&&GuidedTest.Outcome.code("setting_confirmed")==-1,"Readback value substituted");
        check(GuidedTest.Outcome.parse("readback_different")==null&&GuidedTest.Outcome.parse("readback_different_256")==null&&GuidedTest.Outcome.parse("setting_confirmed_1")==null,"Readback tokens carry a bounded value");
        check(GuidedTest.Outcome.parse("rejected_256")==null&&GuidedTest.Outcome.parse("rejected_")==null&&GuidedTest.Outcome.parse("REJECTED")==null&&GuidedTest.Outcome.parse("ok")==null,"Unknown tokens refused");
    }

    static void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}
}
