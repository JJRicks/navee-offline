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
        planner();transactions();reconciliation();gating();startupEstimateGuard();persistence();preferences();restoration();externalManualEdits();connectedPlanEdits();
        System.out.println("ChargeScheduleControllerTest: "+checks+" assertions passed");
    }
    private static void planner(){
        TimeZone boise=TimeZone.getTimeZone("America/Boise");long now=time("2026-10-20T05:00:00Z");
        ChargeScheduleController.Plan plan=ChargeScheduleController.plan(now,8*60,90,boise,false);
        check(plan.deadlineWallMs==time("2026-10-20T14:00:00Z"),"11 PM -> next 8 AM");
        check(plan.startWallMs==time("2026-10-20T12:10:00Z"),"90 minutes plus 20 minute buffer starts at 6:10");
        check(!plan.startNow&&plan.offsetSeconds==-6*3600,"fixed clock offset");
        ChargeScheduleController.Plan whole=ChargeScheduleController.plan(now,8*60,90,boise,true);
        check(whole.startWallMs==time("2026-10-20T12:00:00Z"),"whole-hour models round earlier");
        check(ChargeScheduleController.plan(time("2026-10-20T13:00:00Z"),8*60,90,boise,false).startNow,"insufficient time charges now");
        check(ChargeScheduleController.plan(now,8*60,90.01,boise,false).startWallMs==time("2026-10-20T12:09:00Z"),"fractional minutes round earlier");
        check(!ChargeScheduleController.plan(time("2026-10-20T12:09:00Z"),8*60,90,boise,false).startNow,"one minute before buffered start remains scheduled");
        check(ChargeScheduleController.plan(time("2026-10-20T12:10:00Z"),8*60,90,boise,false).startNow,"exact buffered start charges now");
        check(ChargeScheduleController.plan(time("2026-10-20T12:15:00Z"),8*60,90,boise,false).startNow,"inside added ten-minute reserve charges now rather than waiting");
        check(ChargeScheduleController.nextOccurrence(time("2026-10-20T14:00:00Z"),8*60,boise)==time("2026-10-21T14:00:00Z"),"exact same minute chooses tomorrow");
        ChargeScheduleController.Plan spring=ChargeScheduleController.plan(time("2027-03-14T06:00:00Z"),8*60,90,boise,false);
        check(spring.deadlineWallMs==time("2027-03-14T14:00:00Z"),"DST next day uses chosen localdeadline");
        check(spring.startWallMs==time("2027-03-14T12:10:00Z")&&spring.offsetSeconds==-7*3600,"DST keeps fixed scooter-clock offset, correct elapsed duration");
        check(ChargeScheduleController.nextOccurrence(time("2027-03-14T10:00:00Z"),150,boise)==time("2027-03-15T08:30:00Z"),"past spring gap does not shift tomorrow's chosen time");
        check(ChargeScheduleController.nextOccurrence(time("2027-03-14T08:00:00Z"),150,boise)==time("2027-03-14T09:30:00Z"),"today's nonexistent time advances through the gap");
        check(ChargeScheduleController.nextOccurrence(time("2026-11-01T07:00:00Z"),90,boise)==time("2026-11-01T07:30:00Z"),"fall overlap selects first future occurrence");
        check(ChargeScheduleController.nextOccurrence(time("2026-11-01T08:00:00Z"),90,boise)==time("2026-11-01T08:30:00Z"),"fall overlap retains second occurrence after first has passed");
        ChargeScheduleController.Plan fall=ChargeScheduleController.plan(time("2026-11-01T05:00:00Z"),8*60,90,boise,false);
        check(fall.deadlineWallMs==time("2026-11-01T15:00:00Z"),"fall deadline uses next local8AM");
        ChargeScheduleController.Plan halfDst=ChargeScheduleController.plan(time("2026-10-03T12:00:00Z"),8*60,90,TimeZone.getTimeZone("Australia/Lord_Howe"),true);
        check((halfDst.startWallMs/1000+halfDst.offsetSeconds)%3600==0,"half-hour DST respects the wire whole-hour domain");
        check(halfDst.deadlineWallMs-halfDst.startWallMs>=110*60_000,"rounding preserves the 90 minute estimate plus 20 minute reserve");
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
        Rig cap=new Rig();cap.c.target=85d;cap.c.estimateMinutes=20d;cap.arm();check(cap.sentTime==time("2026-10-20T13:20:00Z"),"uses ETA to actual 85 percent cap plus 20 minute reserve");
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
        r.controller.setDoneTime(7,30,r.results::add);check(r.results.get(1)==null&&r.controller.view().doneByMinuteOfDay==450&&r.writes.isEmpty(),"unplugged time preference edit allowed without commands");
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
    private static void startupEstimateGuard(){
        String reason="Wait for charger detection or select a charger";
        Rig pending=new Rig();pending.c.estimateUnavailableReason=reason;
        check(reason.equals(pending.controller.view().doneByBlockedReason),"Startup estimate reason is visible despite a usable raw-rate ETA");
        pending.enable();
        check(pending.writes.isEmpty()&&pending.results.size()==1&&reason.equals(pending.results.get(0)),"Unknown automatic family cannot write a done-by schedule");
        pending.controller.setDoneTime(9,15,pending.results::add);
        check(pending.results.get(1)==null&&pending.controller.view().doneByMinuteOfDay==555&&pending.writes.isEmpty(),"Startup guard still permits choosing a finish-time preference");
        check(pending.controller.view().manualBlockedReason==null&&pending.controller.view().manualTimeBlockedReason==null,"Startup guard does not block manual controls");
        pending.c.ready=false;pending.controller.observe();pending.c.ready=true;pending.c.generation++;pending.recreate();pending.controller.observe();
        check(pending.writes.isEmpty(),"Blocked startup and reconnect never arm a schedule automatically");
        pending.c.estimateUnavailableReason=null;pending.controller.observe();
        check(pending.writes.isEmpty(),"Detection becoming ready does not retry a refused schedule");
        pending.results.clear();pending.arm();
        check(pending.controller.view().doneByEnabled,"Explicit activation proceeds once the charger family is available");

        Rig manual=new Rig();manual.c.estimateUnavailableReason=reason;
        manual.controller.setManualStart(manual.c.now+3600_000,manual.results::add);manual.reply(true);manual.reply(true);
        manual.controller.setManualEnabled(true,manual.results::add);manual.reply(true);manual.reply(true);manual.reply(true);
        check(manual.controller.view().manualEnabled,"Manual start and enable remain usable during charger identification");
        manual.controller.setManualEnabled(false,manual.results::add);manual.reply(true);
        check(!manual.controller.view().manualEnabled,"Manual cancellation remains usable during charger identification");

        Rig active=new Rig();active.arm();long deadline=active.controller.view().doneByWallMs;
        active.c.charging=1;active.c.estimateUnavailableReason=reason;
        active.controller.setDoneTime(9,0,active.results::add);
        check(active.writes.size()==3&&reason.equals(active.results.get(1))&&active.controller.view().doneByWallMs==deadline,
                "Active charging update refuses an unidentified fresh estimate without changing the existing plan");
        active.controller.setDoneEnabled(false,active.results::add);
        check(active.writes.size()==4&&active.writes.get(3).equals("off"),"Estimate guard does not prevent done-by cancellation");
        active.reply(true);check(!active.controller.view().doneByEnabled,"Done-by cancellation completes during identification");

        Rig paused=new Rig();paused.arm();paused.c.estimateUnavailableReason=reason;paused.c.estimateMinutes=null;
        paused.controller.setDoneTime(9,30,paused.results::add);paused.reply(true);
        check(paused.sentTime==time("2026-10-20T13:40:00Z"),"Paused explicit edit still reuses the verified saved duration");
        paused.c.wireStart=paused.sentTime/1000+paused.sentOffset;paused.reply(true);paused.reply(true);
        check(paused.results.get(1)==null&&paused.controller.view().doneByEnabled,"Paused edit completes without requiring charger identification");
    }
    private static void persistence(){
        Rig r=new Rig();r.arm();String encoded=r.saved.get("owner-A");ChargeScheduleController.State decoded=ChargeScheduleController.decode(encoded);
        check(decoded.mode==2&&decoded.minute==480&&decoded.target==100&&decoded.start==r.sentTime,"persistallownedfields");
        // A schedule saved by the ten-minute-buffer release is already on the scooter.
        // Recreating the controller must preserve that start until an explicit edit.
        decoded.start+=10*60_000L;decoded.wire+=600;decoded.calculatedWire+=600;
        r.saved.put("owner-A",ChargeScheduleController.encode(decoded));r.c.wireStart=decoded.wire;
        int writes=r.writes.size();r.recreate();r.controller.observe();
        check(r.controller.view().doneByEnabled&&r.controller.view().startWallMs==decoded.start,"existing ten-minute-buffer schedule survives upgrade without moving its start");
        check(r.writes.size()==writes,"upgrade does not reprogram a saved schedule");
        for(String bad:new String[]{"", "2|2|480|0|0|0|0|100|90|false", "1|2|480|0|0|0|0|100|90|false", "1|2|480|1|2|3|4|NaN|90|false", "1|2|1500|1|2|3|4|100|90|false", "garbage"})
            check(ChargeScheduleController.decode(bad).mode==0,"malformedrecordcannotarm");
        r.c.scope="owner-B";check(!r.controller.view().doneByEnabled,"profilesisolated");r.c.scope="simulation:owner-A";check(!r.controller.view().doneByEnabled,"simulationisolated");
    }
    private static void preferences(){
        Rig offline=new Rig();offline.c.ready=false;offline.c.charging=null;offline.c.timeReason="Unsupported model";offline.c.toggleReason="Connect first";
        check(offline.controller.view().timeBlockedReason==null,"local preference ignores transport/capability mutation gates");
        offline.controller.setDoneTime(9,45,offline.results::add);offline.recreate();
        check(offline.results.get(0)==null&&offline.controller.view().doneByMinuteOfDay==585&&offline.writes.isEmpty(),"offline preference survives recreation without BLE");
        offline.c.scope="owner-B";check(offline.controller.view().doneByMinuteOfDay==480,"offline preference isolated per scooter");
        offline.controller.setDoneTime(10,15,offline.results::add);offline.c.scope="owner-A";
        check(offline.controller.view().doneByMinuteOfDay==585,"other scooter preference cannot change original");
        offline.c.scope="";offline.controller.setDoneTime(9,0,offline.results::add);
        check(offline.results.get(2).equals("Choose a scooter first")&&!offline.saved.containsKey(""),"missing profile cannot store global preference");
        Rig active=new Rig();active.arm();long start=active.controller.view().startWallMs,deadline=active.controller.view().doneByWallMs;int commands=active.writes.size();
        active.c.ready=false;
        active.controller.setDoneTime(9,30,active.results::add);
        check(active.results.get(1)==null&&active.controller.view().doneByMinuteOfDay==570,"disconnected active plan allows preferred deadline edit");
        check(active.writes.size()==commands&&active.controller.view().startWallMs==start&&active.controller.view().doneByWallMs==deadline,"offline active preference edit does not reprogram or mislabel existing plan");
        active.c.ready=false;active.controller.setDoneTime(10,30,active.results::add);active.recreate();
        check(active.controller.view().doneByEnabled&&active.controller.view().doneByWallMs==deadline&&active.controller.view().doneByMinuteOfDay==630,"active disconnected preference preserves plan through restart");
        active.c.ready=true;active.controller.setDoneEnabled(false,active.results::add);active.c.enabled=0;active.reply(true);
        active.c.charging=1;active.enable();active.reply(true);active.c.wireStart=active.sentTime/1000+active.sentOffset;active.reply(true);active.c.enabled=1;active.c.charging=0;active.reply(true);
        check(active.controller.view().doneByWallMs==time("2026-10-20T16:30:00Z"),"next enable uses edited preference");
        Rig captured=new Rig();long manual=time("2026-10-20T11:00:00Z");captured.c.wireStart=manual/1000-6*3600;
        check(captured.controller.view().manualStartWallMs==manual,"unowned native start is available as a manual preference");
        captured.arm();check(captured.controller.view().manualStartWallMs==manual&&captured.controller.view().startWallMs!=manual,"first done-by overwrite captures preexisting native time");
        check(captured.controller.view().manualTimeBlockedReason.equals("Disable Charge done by first"),"manual time stays blocked while done-by active");
        Rig retryManual=new Rig();retryManual.c.wireStart=manual/1000-6*3600;retryManual.controller.setManualEnabled(true,retryManual.results::add);
        retryManual.reply(true);retryManual.reply(true);retryManual.reply(false);
        check(retryManual.controller.view().manualStartWallMs==manual&&!retryManual.controller.view().manualEnabled,"failed manual enable preserves chosen native preference for retry");
        retryManual.controller.setManualEnabled(true,retryManual.results::add);check(retryManual.writes.get(3).equals("clock"),"manual enable retry doesn't require choosing the same time again");
        Rig busy=new Rig();busy.enable();busy.controller.setDoneTime(9,0,busy.results::add);
        check(busy.results.get(0).contains("Wait")&&busy.controller.view().doneByMinuteOfDay==480,"in-flight activation cannot race local preference save");
        String v1="1|2|480|"+start+"|"+(start/1000-6*3600)+"|"+deadline+"|"+time("2026-10-20T05:00:00Z")+"|100.0|90.0|false";
        Rig legacy=new Rig();legacy.saved.put("owner-A",v1);legacy.recreate();legacy.c.charging=0;
        check(legacy.controller.view().doneByEnabled&&legacy.controller.view().manualStartWallMs==0,"v1 active done-by cannot fabricate lost manual preference");
        legacy.controller.setDoneEnabled(false,legacy.results::add);legacy.reply(true);
        check(legacy.writes.equals(java.util.Arrays.asList("off"))&&legacy.controller.view().manualStartWallMs==0,"v1 cancellation without manual preference only disables");
        String manualV1="1|1|480|"+manual+"|"+(manual/1000-6*3600)+"|"+(manual+86400000L)+"|"+time("2026-10-20T05:00:00Z")+"|100.0|0.0|false";
        check(ChargeScheduleController.decode(manualV1).manualStart==manual,"v1 genuine manual start migrates");
        check(ChargeScheduleController.decode(manualV1.replace("|0.0|false","|90.0|false")).manualStart==0,"v1 downgraded calculated schedule not mistaken for prior manual preference");
        ChargeScheduleController.State migrated=ChargeScheduleController.decode(manualV1);
        check(ChargeScheduleController.encode(migrated).startsWith("3|")&&ChargeScheduleController.decode(ChargeScheduleController.encode(migrated)).manualStart==manual,"new schema roundtrips migrated preference");
        check(ChargeScheduleController.decode(ChargeScheduleController.encode(migrated).replace("|"+manual,"|-1")).mode==0,"invalid v3 timestamp rejected");
        String v2=ChargeScheduleController.encode(migrated).replaceFirst("^3\\|","2|");v2=v2.substring(0,v2.lastIndexOf('|'));v2=v2.substring(0,v2.lastIndexOf('|'));
        check(ChargeScheduleController.decode(v2).manualStart==manual&&ChargeScheduleController.decode(v2).calculatedWire==0,"v2 manual preference remains readable");
    }
    private static Rig armedWithManual(){
        Rig r=new Rig();long manual=time("2026-10-20T11:00:00Z");
        r.controller.setManualStart(manual,r.results::add);r.reply(true);r.c.wireStart=r.sentTime/1000+r.sentOffset;r.reply(true);
        check(r.controller.view().manualStartWallMs==manual,"confirmed manual edit saves separate preference");r.writes.clear();r.results.clear();r.arm();return r;
    }
    private static void restoration(){
        Rig success=armedWithManual();long preferred=success.controller.view().manualStartWallMs;int before=success.writes.size();
        success.controller.setDoneEnabled(false,success.results::add);check(success.writes.get(before).equals("off"),"restore starts with OFF");
        success.c.enabled=0;success.reply(true);
        check(!success.controller.view().doneByEnabled&&!success.controller.view().manualEnabled&&success.controller.busy(),"confirmed OFF reflected before restoration completes");
        check(success.writes.get(before+1).equals("clock"),"clock restored only after OFF acknowledgement");success.reply(true);
        check(success.writes.get(before+2).equals("time")&&success.sentTime==preferred,"restores user's manual time instead of calculated start");
        success.c.wireStart=success.sentTime/1000+success.sentOffset;success.reply(true);
        check(success.results.get(1)==null&&!success.controller.busy()&&!success.controller.view().manualEnabled,"restoration succeeds without enabling manual schedule");
        check(success.controller.view().startWallMs==preferred&&success.controller.view().manualStartWallMs==preferred,"both actual and preferred time match after restore");
        check(success.writes.subList(before,success.writes.size()).equals(java.util.Arrays.asList("off","clock","time")),"restoration never emits ON");
        success.recreate();check(success.controller.view().manualStartWallMs==preferred&&!success.controller.view().manualEnabled,"restored preference/off survive recreation");
        Rig stale=armedWithManual();stale.c.now=time("2026-10-20T11:30:00Z");stale.controller.setDoneEnabled(false,stale.results::add);stale.c.enabled=0;stale.reply(true);stale.reply(true);
        check(stale.sentTime==time("2026-10-21T11:00:00Z"),"stale manual time advances to next chosen local occurrence");stale.c.wireStart=stale.sentTime/1000+stale.sentOffset;stale.reply(true);
        check(stale.controller.view().manualStartWallMs==stale.sentTime,"rolled manual time persisted");
        for(int failure=0;failure<3;failure++){
            Rig failed=armedWithManual();failed.controller.setDoneEnabled(false,failed.results::add);
            for(int step=0;step<failure;step++){if(step==0)failed.c.enabled=0;failed.reply(true);}failed.reply(false);
            check(failed.writes.size()==4+failure&&!failed.controller.busy(),"restoration failure halts subsequent commands");
            check(failed.results.size()==2&&failed.results.get(1)!=null,"restoration failure reported exactly once");
            check(failure==0?failed.controller.view().doneByEnabled:!failed.controller.view().doneByEnabled&&!failed.controller.view().manualEnabled,"confirmed OFF survives partial restoration failure");
            if(failure>0)check(failed.results.get(1).startsWith("Charge done by is off;"),"partial failure explicitly distinguishes cancellation from restoration");
            int writes=failed.writes.size();failed.recreate();failed.controller.observe();check(failed.writes.size()==writes,"failed restore never retries automatically");
            check(failed.controller.view().manualStartWallMs==time("2026-10-20T11:00:00Z"),"failure cannot destroy prior manual preference");
        }
        Rig switched=armedWithManual();switched.controller.setDoneEnabled(false,switched.results::add);switched.c.enabled=0;switched.reply(true);switched.c.scope="owner-B";switched.reply(true);
        check(switched.writes.size()==5&&switched.results.get(1).contains("Connection changed"),"profile switch after OFF prevents time restore on other scooter");
        switched.c.wireStart=null;check(switched.controller.view().manualStartWallMs==0,"other profile receives no manual preference");switched.c.scope="owner-A";switched.recreate();
        check(!switched.controller.view().doneByEnabled&&!switched.controller.view().manualEnabled,"original profile retains acknowledged OFF after switched context");
        Rig reconnect=armedWithManual();reconnect.controller.setDoneEnabled(false,reconnect.results::add);reconnect.c.enabled=0;reconnect.reply(true);reconnect.c.generation++;reconnect.reply(true);
        check(reconnect.writes.size()==5&&reconnect.results.get(1).contains("Connection changed"),"new session prevents pending time restoration");
        Rig dropped=armedWithManual();dropped.controller.setDoneEnabled(false,dropped.results::add);dropped.c.enabled=0;dropped.reply(true);dropped.c.ready=false;dropped.controller.observe();dropped.reply(false);
        check(dropped.results.size()==2&&!dropped.controller.busy()&&!dropped.controller.view().doneByEnabled,"lost restoration callback/disconnect completes only once and preserves OFF");
        Rig cancel=armedWithManual();cancel.controller.setDoneEnabled(false,cancel.results::add);cancel.c.enabled=0;cancel.reply(true);cancel.controller.cancel();cancel.reply(true);
        check(cancel.writes.size()==5&&cancel.results.size()==1&&!cancel.controller.busy()&&!cancel.controller.view().doneByEnabled,"maintenance cancellation cannot restore or rearm later");
        Rig unsupported=armedWithManual();unsupported.c.timeReason="Time setting unavailable";unsupported.controller.setDoneEnabled(false,unsupported.results::add);unsupported.c.enabled=0;unsupported.reply(true);
        check(unsupported.writes.size()==4&&!unsupported.controller.view().doneByEnabled&&unsupported.results.get(1).startsWith("Charge done by is off;"),"time gate still permits cancellation and honestly reports missing restore");
        Rig priorOn=armedWithManual();priorOn.controller.setDoneEnabled(false,priorOn.results::add);priorOn.c.enabled=0;priorOn.reply(true);priorOn.reply(true);priorOn.c.wireStart=priorOn.sentTime/1000+priorOn.sentOffset;priorOn.reply(true);
        priorOn.controller.setManualEnabled(true,priorOn.results::add);priorOn.reply(true);priorOn.reply(true);priorOn.c.enabled=1;priorOn.reply(true);
        check(priorOn.controller.view().manualEnabled,"manual schedule initially active");priorOn.writes.clear();priorOn.results.clear();priorOn.c.charging=1;priorOn.arm();
        priorOn.controller.setDoneEnabled(false,priorOn.results::add);priorOn.c.enabled=0;priorOn.reply(true);priorOn.reply(true);priorOn.c.wireStart=priorOn.sentTime/1000+priorOn.sentOffset;priorOn.reply(true);
        check(!priorOn.controller.view().manualEnabled&&priorOn.writes.equals(java.util.Arrays.asList("clock","time","on","off","clock","time")),"restoring previously active manual time still never re-enables schedule");
    }
    private static Rig withManualOnly(){
        Rig r=new Rig();long manual=time("2026-10-20T11:00:00Z");r.controller.setManualStart(manual,r.results::add);r.reply(true);
        r.c.wireStart=r.sentTime/1000+r.sentOffset;r.reply(true);r.writes.clear();r.results.clear();return r;
    }
    private static void externalManualEdits(){
        Rig external=withManualOnly();long edited=time("2026-10-20T12:00:00Z");external.c.wireStart=edited/1000+external.sentOffset;external.controller.observe();
        check(external.controller.view().startWallMs==edited&&external.controller.view().manualStartWallMs==edited,"positive native edit updates genuine manual preference");
        external.recreate();check(external.controller.view().manualStartWallMs==edited,"external manual edit persists");
        external.controller.setManualEnabled(true,external.results::add);external.reply(true);
        check(external.sentTime==edited,"manual enable preserves external choice rather than overwriting with old time");external.reply(true);external.reply(true);
        Rig externalDone=withManualOnly();externalDone.c.wireStart=edited/1000+externalDone.sentOffset;externalDone.controller.observe();externalDone.arm();
        check(externalDone.controller.view().manualStartWallMs==edited,"done-by retains most recent external manual choice");
        externalDone.controller.setDoneEnabled(false,externalDone.results::add);externalDone.c.enabled=0;externalDone.reply(true);externalDone.reply(true);
        check(externalDone.sentTime==edited,"done-by cancellation restores external manual choice");externalDone.c.wireStart=externalDone.sentTime/1000+externalDone.sentOffset;externalDone.reply(true);
        long later=edited+3600_000;externalDone.c.wireStart=later/1000+externalDone.sentOffset;externalDone.controller.observe();
        check(externalDone.controller.view().manualStartWallMs==later,"successful restoration returns to ordinary external manual reconciliation");
        Rig late=withManualOnly();long preferred=late.controller.view().manualStartWallMs;late.enable();late.reply(true);long calculated=late.sentTime,wire=late.sentTime/1000+late.sentOffset;
        check(ChargeScheduleController.decode(late.saved.get("owner-A")).calculatedWire==wire,"attempted calculated write is journaled before its confirmation");late.reply(false);late.recreate();late.c.wireStart=wire;late.controller.observe();
        check(late.controller.view().startWallMs==calculated&&late.controller.view().manualStartWallMs==preferred,"late readback after failed calculated ACK and restart does not become manual preference");
        Rig dropped=withManualOnly();preferred=dropped.controller.view().manualStartWallMs;dropped.enable();dropped.reply(true);wire=dropped.sentTime/1000+dropped.sentOffset;
        dropped.c.ready=false;dropped.controller.observe();dropped.recreate();dropped.c.ready=true;dropped.c.generation++;dropped.c.wireStart=wire;dropped.controller.observe();dropped.reply(true);
        check(dropped.controller.view().manualStartWallMs==preferred,"disconnect/restart during calculated write cannot corrupt manual preference from delayed readback");
        Rig active=armedWithManual();preferred=active.controller.view().manualStartWallMs;active.c.wireStart+=1800;active.controller.observe();
        check(active.controller.view().manualStartWallMs==preferred,"changed native time during owned done-by never replaces separate manual preference");
        Rig collision=new Rig();long sameCalculated=time("2026-10-20T12:10:00Z");collision.controller.setManualStart(sameCalculated,collision.results::add);collision.reply(true);collision.c.wireStart=collision.sentTime/1000+collision.sentOffset;collision.reply(true);
        collision.writes.clear();collision.results.clear();collision.arm();collision.c.soc=100d;collision.controller.observe();collision.c.wireStart+=1800;collision.controller.observe();
        check(collision.controller.view().manualStartWallMs==sameCalculated,"retired calculated start matching manual by coincidence does not establish manual context");
        Rig clockFailure=withManualOnly();clockFailure.enable();clockFailure.reply(false);clockFailure.c.wireStart=edited/1000+clockFailure.sentOffset;clockFailure.controller.observe();
        check(clockFailure.controller.view().manualStartWallMs==edited,"clock failure before calculated write does not suppress genuine external manual edits");
        Rig empty=new Rig();empty.enable();empty.reply(true);long firstCalculated=empty.sentTime,firstWire=empty.sentTime/1000+empty.sentOffset;empty.reply(false);
        empty.c.wireStart=firstWire;empty.recreate();
        for(int observation=0;observation<3;observation++){empty.controller.observe();check(empty.controller.view().startWallMs==firstCalculated&&empty.controller.view().manualStartWallMs==0,"empty preference never infers failed calculated readback as manual time");}
        empty.controller.setManualEnabled(true,empty.results::add);
        check(empty.writes.size()==2&&empty.results.get(1).equals("Choose Charge start first"),"unknown manual time remains unset after failed calculated write");
        empty.controller.setDoneTime(9,0,empty.results::add);empty.enable();empty.reply(true);long secondWire=empty.sentTime/1000+empty.sentOffset;empty.reply(false);
        check(secondWire!=firstWire&&empty.controller.view().manualStartWallMs==0,"earlier calculated readback is not inferred after another failed attempt replaces the marker");
        empty.recreate();empty.c.wireStart=secondWire;
        check(empty.controller.view().manualStartWallMs==0,"latest calculated readback also remains unknown after repeated attempts and restart");
        empty.enable();empty.reply(true);empty.c.wireStart=empty.sentTime/1000+empty.sentOffset;empty.reply(true);empty.c.enabled=1;empty.c.charging=0;empty.reply(true);
        check(empty.controller.view().doneByEnabled&&empty.controller.view().manualStartWallMs==0,"later successful done-by activation cannot persist a synthetic manual preference");
        int beforeOff=empty.writes.size();empty.controller.setDoneEnabled(false,empty.results::add);empty.c.enabled=0;empty.reply(true);
        check(empty.writes.size()==beforeOff+1&&empty.writes.get(beforeOff).equals("off"),"cancelling after unknown manual history sends OFF without restoring a calculated time");
        Rig emptyDropped=new Rig();emptyDropped.enable();emptyDropped.reply(true);long droppedWire=emptyDropped.sentTime/1000+emptyDropped.sentOffset;emptyDropped.c.ready=false;emptyDropped.controller.observe();emptyDropped.recreate();
        emptyDropped.c.ready=true;emptyDropped.c.generation++;emptyDropped.c.wireStart=droppedWire;emptyDropped.controller.observe();emptyDropped.reply(true);
        check(emptyDropped.controller.view().manualStartWallMs==0,"disconnect and lost callback with no prior manual preference cannot seed it from a calculated readback");
        String[] invalid=late.saved.get("owner-A").split("\\|",-1);invalid[11]="-1";
        check(ChargeScheduleController.decode(String.join("|",invalid)).mode==0,"invalid calculated wire marker rejected");
    }
    private static void connectedPlanEdits(){
        Rig paused=armedWithManual();long manual=paused.controller.view().manualStartWallMs;long oldDeadline=paused.controller.view().doneByWallMs;
        paused.controller.setDoneTime(9,30,paused.results::add);
        check(paused.writes.get(3).equals("clock")&&paused.controller.view().doneByMinuteOfDay==480,"active connected edit starts confirmation without prematurely saving new deadline");
        paused.reply(true);check(paused.sentTime==time("2026-10-20T13:40:00Z"),"paused plan reuses verified 90 minute estimate plus buffer");
        paused.c.wireStart=paused.sentTime/1000+paused.sentOffset;paused.reply(true);paused.reply(true);
        check(paused.results.get(1)==null&&paused.controller.view().doneByMinuteOfDay==570&&paused.controller.view().doneByWallMs==time("2026-10-20T15:30:00Z"),"connected active edit applies and displays confirmed new deadline");
        check(paused.controller.view().manualStartWallMs==manual,"active update preserves separate manual preference");
        paused.recreate();paused.controller.setDoneTime(10,0,paused.results::add);paused.reply(true);
        check(paused.sentTime==time("2026-10-20T14:10:00Z"),"captured ETA and SOC survive restart for explicit paused edit");paused.c.wireStart=paused.sentTime/1000+paused.sentOffset;paused.reply(true);paused.reply(true);
        Rig live=armedWithManual();live.c.charging=1;live.c.soc=80d;live.c.estimateMinutes=60d;live.controller.setDoneTime(10,0,live.results::add);live.reply(true);
        check(live.sentTime==time("2026-10-20T14:40:00Z"),"active charging uses fresh live estimate rather than older capture");live.c.wireStart=live.sentTime/1000+live.sentOffset;live.reply(true);live.reply(true);
        check(ChargeScheduleController.decode(live.saved.get("owner-A")).capturedSoc==80,"fresh update captures current SOC for next paused edit");
        Rig offline=armedWithManual();offline.c.ready=false;offline.controller.setDoneTime(11,0,offline.results::add);int commands=offline.writes.size();offline.c.ready=true;offline.c.generation++;offline.controller.observe();
        check(offline.writes.size()==commands&&offline.controller.view().doneByWallMs==oldDeadline&&offline.controller.view().doneByMinuteOfDay==660,"reconnect does not auto-apply offline preference to active plan");
        for(int unavailable=0;unavailable<4;unavailable++){
            Rig missing=armedWithManual();if(unavailable==0)missing.c.soc=null;if(unavailable==1)missing.c.soc=78d;if(unavailable==2)missing.c.charging=null;if(unavailable==3)missing.c.now=missing.sentTime+1000;
            missing.controller.setDoneTime(9,0,missing.results::add);
            check(missing.writes.size()==3&&missing.results.get(1).contains("fresh charging estimate"),"unverified paused state refuses captured ETA reuse");
            check(missing.controller.view().doneByMinuteOfDay==480&&missing.controller.view().doneByWallMs==oldDeadline,"refused update preserves original deadline and preference");
        }
        Rig legacy=armedWithManual();String old=legacy.saved.get("owner-A").replaceFirst("^3\\|","2|");old=old.substring(0,old.lastIndexOf('|'));old=old.substring(0,old.lastIndexOf('|'));
        legacy.saved.put("owner-A",old);legacy.recreate();legacy.controller.setDoneTime(9,0,legacy.results::add);
        check(legacy.writes.size()==3&&legacy.results.get(1).contains("fresh charging estimate"),"legacy active plan cannot invent missing captured SOC");
        for(int failure=0;failure<3;failure++){
            Rig failed=armedWithManual();failed.controller.setDoneTime(9,0,failed.results::add);for(int step=0;step<failure;step++){if(step==1)failed.c.wireStart=failed.sentTime/1000+failed.sentOffset;failed.reply(true);}failed.reply(false);
            check(failed.results.size()==2&&failed.results.get(1)!=null&&!failed.controller.busy(),"active update failure completes once");
            check(failed.controller.view().doneByMinuteOfDay==480&&failed.controller.view().manualStartWallMs==manual,"failed active update never saves unconfirmed new preference or overwrites manual preference");
            if(failure<2)check(failed.controller.view().doneByEnabled&&failed.controller.view().doneByWallMs==oldDeadline,"pre-time-confirmation failure retains old plan");
            else check(!failed.controller.view().doneByEnabled&&failed.results.get(1).contains("not confirmed"),"partial native time change is not falsely called an armed finish deadline");
        }
        for(int changed=0;changed<3;changed++){
            Rig race=armedWithManual();race.controller.setDoneTime(9,0,race.results::add);
            for(int step=0;step<changed;step++){if(step==1)race.c.wireStart=race.sentTime/1000+race.sentOffset;race.reply(true);}race.c.soc=80d;
            if(changed==1)race.c.wireStart=race.sentTime/1000+race.sentOffset;race.reply(true);
            check(race.results.size()==2&&race.results.get(1)!=null&&!race.controller.busy(),"SOC change during paused transaction prevents stale finish claim");
            check(race.controller.view().doneByMinuteOfDay==480&&race.controller.view().manualStartWallMs==manual,"SOC race preserves user preferences");
        }
        Rig late=armedWithManual();late.c.now=time("2026-10-20T11:00:00Z");late.controller.setDoneTime(6,0,late.results::add);
        check(late.writes.get(3).equals("off"),"updated deadline with insufficient time starts charging now");late.c.enabled=0;late.reply(true);
        check(!late.controller.view().doneByEnabled&&late.controller.view().doneByMinuteOfDay==360&&late.results.get(1).startsWith("Charging now"),"charge-now update saves new preference only after OFF confirmed");
        String[] bad=paused.saved.get("owner-A").split("\\|",-1);bad[12]="NaN";
        check(ChargeScheduleController.decode(String.join("|",bad)).mode==0,"invalid captured SOC rejected");
    }
}
