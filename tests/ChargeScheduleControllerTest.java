package local.navee.offlinelock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.function.Consumer;

public final class ChargeScheduleControllerTest {
    private static int checks;
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static long time(String iso){return Instant.parse(iso).toEpochMilli();}
    private static final class Rig implements ChargeScheduleController.Store,ChargeScheduleController.Driver {
        final Map<String,String> saved=new HashMap<>();
        final List<String> writes=new ArrayList<>();
        final ChargeScheduleController.Context c=new ChargeScheduleController.Context();
        final List<String> results=new ArrayList<>();
        ChargeScheduleController controller;
        Consumer<Boolean> callback;
        long sentTime;
        int sentOffset;
        Rig(){c.scope="owner-A";c.ready=true;c.generation=1;c.now=ChargeScheduleControllerTest.time("2026-10-20T05:00:00Z");c.zone=TimeZone.getTimeZone("America/Boise");c.charging=1;c.enabled=0;c.soc=79d;c.target=100d;c.estimateMinutes=90d;c.sampleMs=100_000;controller=new ChargeScheduleController(this,this);}
        public String read(String key){return saved.get(key);}
        public void write(String key,String value){saved.put(key,value);}
        public ChargeScheduleController.Context context(){return c;}
        public void clock(Consumer<Boolean> result){check(callback==null,"one pending write");writes.add("clock");callback=result;}
        public void time(long wall,int offset,Consumer<Boolean> result){check(callback==null,"one pending time");writes.add("time");sentTime=wall;sentOffset=offset;callback=result;}
        public void enabled(boolean enabled,Consumer<Boolean> result){check(callback==null,"one pending enable");writes.add(enabled?"on":"off");callback=result;}
        void reply(boolean success){Consumer<Boolean> done=callback;callback=null;done.accept(success);}
        void enable(){controller.setDoneEnabled(true,results::add);}
        void arm(){enable();check(writes.equals(java.util.Arrays.asList("clock")),"clock first");reply(true);check(writes.size()==2&&writes.get(1).equals("time"),"time second");c.wireStart=sentTime/1000+sentOffset;reply(true);check(writes.get(2).equals("on"),"enable last");c.enabled=1;c.charging=0;reply(true);check(results.size()==1&&results.get(0)==null,"confirmed success");}
        void advance(long ms){c.now+=ms;c.sampleMs+=ms;}
        void recreate(){controller=new ChargeScheduleController(this,this);}
    }
    public static void main(String[] args){
        planner();transactions();reconciliation();gating();persistence();
        System.out.println("ChargeScheduleControllerTest: "+checks+" assertions passed");
    }
    private static void planner(){
        TimeZone boise=TimeZone.getTimeZone("America/Boise");long now=time("2026-10-20T05:00:00Z");
        ChargeScheduleController.Plan plan=ChargeScheduleController.plan(now,8*60,90,boise,false);
        check(plan.deadlineWallMs==time("2026-10-20T14:00:00Z"),"11 PM -> next 8 AM");
        check(plan.startWallMs==time("2026-10-20T12:20:00Z"),"90 minutes plus10 buffer starts6:20");
        check(!plan.startNow&&plan.offsetSeconds==-6*3600,"fixed clock offset");
        ChargeScheduleController.Plan whole=ChargeScheduleController.plan(now,8*60,90,boise,true);
        check(whole.startWallMs==time("2026-10-20T12:00:00Z"),"whole-hour models round earlier");
        check(ChargeScheduleController.plan(time("2026-10-20T13:00:00Z"),8*60,90,boise,false).startNow,"insufficient time charges now");
        check(ChargeScheduleController.plan(now,8*60,90.01,boise,false).startWallMs==time("2026-10-20T12:19:00Z"),"fractional minutes round earlier");
        check(ChargeScheduleController.nextOccurrence(time("2026-10-20T14:00:00Z"),8*60,boise)==time("2026-10-21T14:00:00Z"),"exact same minute chooses tomorrow");
        ChargeScheduleController.Plan spring=ChargeScheduleController.plan(time("2027-03-14T06:00:00Z"),8*60,90,boise,false);
        check(spring.deadlineWallMs==time("2027-03-14T14:00:00Z"),"DST next day uses chosen localdeadline");
        check(spring.startWallMs==time("2027-03-14T12:20:00Z")&&spring.offsetSeconds==-7*3600,"DST keeps fixed scooter-clock offset, correct elapsed duration");
        ChargeScheduleController.Plan fall=ChargeScheduleController.plan(time("2026-11-01T05:00:00Z"),8*60,90,boise,false);
        check(fall.deadlineWallMs==time("2026-11-01T15:00:00Z"),"fall deadline uses next local8AM");
        ChargeScheduleController.Plan halfDst=ChargeScheduleController.plan(time("2026-10-03T12:00:00Z"),8*60,90,TimeZone.getTimeZone("Australia/Lord_Howe"),true);
        check((halfDst.startWallMs/1000+halfDst.offsetSeconds)%3600==0,"half-hour DST respects the wire whole-hour domain");
        check(halfDst.deadlineWallMs-halfDst.startWallMs>=100*60_000,"rounding never starts later than the buffered estimate");
        for(double bad:new double[]{Double.NaN,Double.POSITIVE_INFINITY,-1,0,3000}){
            boolean rejected=false;try{ChargeScheduleController.plan(now,480,bad,boise,false);}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"reject bad duration");
        }
    }
    private static void transactions(){
        Rig r=new Rig();r.arm();check(r.controller.view().doneByEnabled&&!r.controller.view().manualEnabled,"done-by is distinct from manual");
        check(r.controller.view().startWallMs==r.sentTime,"display actual UTC start");check(!r.saved.isEmpty(),"confirmed intent persisted");
        r.controller.setManualEnabled(true,r.results::add);check(r.results.get(1).contains("Disable Charge done by"),"manual mutually exclusive");
        r.controller.setDoneEnabled(false,r.results::add);check(r.writes.get(3).equals("off"),"paused state can disable");r.reply(true);check(!r.controller.view().doneByEnabled,"disabled afterconfirmation");
        for(int failure=0;failure<3;failure++){
            Rig f=new Rig();f.enable();for(int step=0;step<failure;step++)f.reply(true);f.reply(false);
            check(!f.controller.view().doneByEnabled,"failure doesn't claim doneby");check(!f.controller.busy(),"failure releasestransaction");check(f.writes.size()==failure+1,"failure stops writes");check(f.results.size()==1&&f.results.get(0)!=null,"failure reportedonce");
        }
        Rig cross=new Rig();cross.enable();cross.c.scope="owner-B";cross.reply(true);check(cross.writes.size()==1,"profile switch aborts continuation");check(cross.results.get(0).contains("Connection changed"),"profile switch reported");
        Rig reconnect=new Rig();reconnect.enable();reconnect.c.generation++;reconnect.reply(true);check(reconnect.writes.size()==1,"new session aborts continuation");
        Rig zoneChange=new Rig();zoneChange.enable();zoneChange.c.zone=TimeZone.getTimeZone("UTC");zoneChange.reply(true);check(zoneChange.writes.size()==1,"timezonechangeabortspartialprogramming");
        Rig lostCallback=new Rig();lostCallback.enable();lostCallback.c.ready=false;lostCallback.controller.observe();
        check(!lostCallback.controller.busy()&&lostCallback.results.size()==1,"disconnect observation releases even a dropped BLE callback");
        lostCallback.reply(false);check(lostCallback.results.size()==1,"late failure callback is ignored after connection observation");
        Rig cancel=new Rig();cancel.enable();cancel.controller.cancel();cancel.reply(true);check(cancel.writes.size()==1&&cancel.results.isEmpty(),"maintenance cancel no late callback/write");
        Rig now=new Rig();now.c.now=time("2026-10-20T13:00:00Z");now.enable();check(now.writes.equals(java.util.Arrays.asList("off")),"too late sends only disable");now.reply(true);check(now.results.get(0).startsWith("Charging now"),"charge-now explained");check(!now.controller.view().doneByEnabled,"no tomorrow schedule claimed");
        Rig elapsed=new Rig();elapsed.enable();elapsed.reply(true);elapsed.c.now=elapsed.sentTime+1;elapsed.reply(true);
        check(elapsed.writes.get(2).equals("off"),"start passed during confirmation disables instead of arming expiredtimestamp");
        elapsed.reply(true);check(!elapsed.controller.view().doneByEnabled&&elapsed.results.get(0).startsWith("Charging now"),"late transaction reports chargingnow");
        Rig changedCap=new Rig();changedCap.enable();changedCap.reply(true);changedCap.c.target=85d;changedCap.reply(true);
        check(changedCap.writes.size()==2&&!changedCap.controller.view().doneByEnabled&&changedCap.results.get(0).startsWith("Charge limit changed"),"ceiling change before enable aborts stale estimate");
        Rig finalCap=new Rig();finalCap.enable();finalCap.reply(true);finalCap.reply(true);finalCap.c.target=85d;finalCap.reply(true);
        check(!finalCap.controller.view().doneByEnabled&&finalCap.controller.view().manualEnabled,"ceiling change after enable preserves actual schedule without false deadline");
        Rig cap=new Rig();cap.c.target=85d;cap.c.estimateMinutes=20d;cap.arm();check(cap.sentTime==time("2026-10-20T13:30:00Z"),"uses ETAtoactual85percentcap");
        Rig manual=new Rig();manual.controller.setManualStart(time("2026-10-20T12:00:00Z"),manual.results::add);manual.reply(true);manual.reply(true);
        check(manual.writes.size()==2&&!manual.controller.view().manualEnabled,"time edit does not enable");
        manual.controller.setManualEnabled(true,manual.results::add);manual.reply(true);manual.reply(true);manual.reply(true);check(manual.controller.view().manualEnabled,"manual active persisted");
        manual.c.enabled=0;manual.recreate();check(manual.controller.view().manualEnabled,"manual reboot zero doesn't hideownedon");
        manual.controller.setManualEnabled(false,manual.results::add);check(manual.writes.get(manual.writes.size()-1).equals("off"),"disable sends even when wirealreadyzero");manual.reply(true);check(!manual.controller.view().manualEnabled,"manual disable clearsownedstate");
    }
    private static void reconciliation(){
        Rig r=new Rig();r.arm();int commands=r.writes.size();r.c.ready=false;r.advance(3600_000);r.controller.observe();check(r.controller.view().doneByEnabled,"disconnectretain");
        r.c.ready=true;r.c.generation++;r.c.enabled=0;r.c.wireStart=0L;r.recreate();check(r.controller.view().doneByEnabled,"missing time/rebootzero retained");check(r.writes.size()==commands,"reconnectneverrearms");
        r.c.charging=1;r.advance(1);r.controller.observe();check(r.controller.view().doneByEnabled,"one early sample insufficient");r.advance(4000);r.controller.observe();check(r.controller.view().doneByEnabled,"earlycharging mustpersist");r.advance(1000);r.controller.observe();check(!r.controller.view().doneByEnabled,"sustainedearlychargingretires");
        Rig settle=new Rig();settle.arm();settle.c.charging=1;settle.advance(2000);settle.controller.observe();settle.advance(10_000);settle.controller.observe();check(settle.controller.view().doneByEnabled,"commandsettlinggrace");
        Rig ended=new Rig();ended.arm();ended.c.soc=100d;ended.controller.observe();check(!ended.controller.view().doneByEnabled&&!ended.controller.view().manualEnabled,"completed targetclearsboth despitewire1");
        Rig capped=new Rig();capped.c.target=85d;capped.arm();capped.c.soc=85d;capped.controller.observe();check(!capped.controller.view().doneByEnabled,"lowercaptargetretires");
        Rig past=new Rig();past.arm();past.c.now=past.sentTime+120_000;past.c.charging=0;past.controller.observe();check(!past.controller.view().doneByEnabled,"nonchargingafterstartretires");
        Rig started=new Rig();started.arm();started.c.now=started.sentTime+120_000;started.c.charging=1;started.controller.observe();check(started.controller.view().doneByEnabled,"chargingatscheduledstartkeepsdeadlineuntilcomplete");
        Rig external=new Rig();external.arm();external.c.wireStart+=3600;external.controller.observe();check(!external.controller.view().doneByEnabled&&external.controller.view().manualEnabled,"positive changedtimefollowsdevice");
        Rig stale=new Rig();stale.arm();stale.c.charging=null;stale.c.soc=null;stale.c.ready=false;stale.advance(2*24*3600_000L);stale.controller.observe();check(stale.controller.view().doneByEnabled,"old cachedvaluesneverretire");
        Rig newCap=new Rig();newCap.c.target=85d;newCap.arm();newCap.c.target=100d;newCap.c.soc=85d;newCap.controller.observe();check(!newCap.controller.view().doneByEnabled&&newCap.controller.view().manualEnabled,"chargeceilingeditpreservesstartbutretiresobsoleteestimate");
        Rig dst=new Rig();dst.c.now=time("2026-11-01T05:00:00Z");dst.arm();long changedStart=time("2026-11-01T16:00:00Z");
        dst.c.wireStart=changedStart/1000+dst.sentOffset;dst.controller.observe();check(dst.controller.view().startWallMs==changedStart,"changed wire time keeps original RTC offset across DST");
        Rig unownedDst=new Rig();unownedDst.c.now=time("2026-11-01T05:00:00Z");unownedDst.c.wireStart=changedStart/1000-6*3600;
        check(unownedDst.controller.view().startWallMs==changedStart,"unowned readback uses current RTC offset not pseudo UTC offset");
        Rig unknown=new Rig();unknown.c.enabled=1;unknown.c.wireStart=time("2026-10-20T12:00:00Z")/1000-6*3600;check(unknown.controller.view().manualEnabled,"unownedwireonstilldisplayed");check(unknown.saved.isEmpty(),"readbackaloneisnotownedintent");
    }
    private static void gating(){
        Rig r=new Rig();r.c.charging=0;r.enable();check(r.results.get(0).equals("Connect a charger first")&&r.writes.isEmpty(),"unpluggedtapmessage");
        r.controller.setDoneTime(7,30,r.results::add);check(r.results.get(1).equals("Connect a charger first"),"unpluggedtimeeditblocked");
        r.c.charging=1;r.c.estimateMinutes=null;r.enable();check(r.results.get(2).equals("Wait for a charging time estimate")&&r.writes.isEmpty(),"waitforETA");
        r.c.target=null;r.c.estimateMinutes=90d;r.enable();check(r.writes.isEmpty(),"unknowncapblocksestimate");
        r.c.target=100d;r.c.toggleReason="Stop the scooter first";r.enable();check(r.results.get(4).equals("Stop the scooter first"),"mutationgate surfaced");
        Rig ready=new Rig();ready.c.ready=false;ready.enable();check(ready.results.get(0).equals("Connect a charger first"),"disconnectednoenable");
        Rig editing=new Rig();editing.controller.setDoneTime(7,30,editing.results::add);check(editing.controller.view().doneByMinuteOfDay==450&&editing.writes.isEmpty(),"preferredtimeeditdoesnotarm");
        editing.enable();editing.controller.setManualStart(editing.c.now+3600_000,editing.results::add);check(editing.results.get(1).contains("Wait"),"busyblocksconcurrentrequest");
        Rig noTime=new Rig();noTime.c.timeReason="Time setting unavailable";
        check(noTime.controller.view().manualBlockedReason==null&&noTime.controller.view().manualTimeBlockedReason.equals("Time setting unavailable"),"time gate independent from switch gate");
        noTime.controller.setManualStart(noTime.c.now+3600_000,noTime.results::add);check(noTime.writes.isEmpty()&&noTime.results.get(0).equals("Time setting unavailable"),"time refusal before clock write");
        Rig invalid=new Rig();invalid.controller.setDoneTime(30,0,invalid.results::add);check(invalid.results.get(0).contains("valid"),"invalidpickerrejected");
    }
    private static void persistence(){
        Rig r=new Rig();r.arm();String encoded=r.saved.get("owner-A");ChargeScheduleController.State decoded=ChargeScheduleController.decode(encoded);
        check(decoded.mode==2&&decoded.minute==480&&decoded.target==100&&decoded.start==r.sentTime,"persistallownedfields");
        for(String bad:new String[]{"", "2|2|480|0|0|0|0|100|90|false", "1|2|480|0|0|0|0|100|90|false", "1|2|480|1|2|3|4|NaN|90|false", "1|2|1500|1|2|3|4|100|90|false", "garbage"})
            check(ChargeScheduleController.decode(bad).mode==0,"malformedrecordcannotarm");
        r.c.scope="owner-B";check(!r.controller.view().doneByEnabled,"profilesisolated");r.c.scope="simulation:owner-A";check(!r.controller.view().doneByEnabled,"simulationisolated");
    }
}
