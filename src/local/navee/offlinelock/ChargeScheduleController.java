package local.navee.offlinelock;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.function.Consumer;

/** One-shot charging intentions. Reconciliation never sends commands or arms another day. */
public final class ChargeScheduleController {
    public static final long BUFFER_MS = 10 * 60_000L;
    private static final long SETTLE_MS = 30_000L, START_GRACE_MS = 120_000L;
    public interface Store { String read(String scope); void write(String scope, String value); }
    public interface Driver {
        Context context();
        void clock(Consumer<Boolean> completion);
        void time(long wallMs, int offsetSeconds, Consumer<Boolean> completion);
        void enabled(boolean enabled, Consumer<Boolean> completion);
    }
    /** All readings must come from the current authenticated transport, never a display cache. */
    public static final class Context {
        public String scope = "", timeReason, toggleReason, estimateUnavailableReason;
        public boolean ready, wholeHours;
        public int generation;
        public long now, sampleMs;
        public Integer charging, enabled;
        public Long wireStart;
        public Double soc, target, estimateMinutes;
        public TimeZone zone = TimeZone.getDefault();
    }
    public static final class View {
        public final boolean manualEnabled, doneByEnabled, busy, chargerConnected;
        public final int doneByMinuteOfDay;
        public final long startWallMs, manualStartWallMs, doneByWallMs;
        public final String manualBlockedReason, manualTimeBlockedReason, doneByBlockedReason, timeBlockedReason, status;
        private View(State state, Context c, boolean busy) {
            this.busy=busy;doneByEnabled=state.mode==2;
            manualEnabled=state.mode==1 || state.mode==0&&!state.retired&&Integer.valueOf(1).equals(c.enabled);
            chargerConnected=c.ready&&Integer.valueOf(1).equals(c.charging);
            doneByMinuteOfDay=state.minute;startWallMs=state.start;manualStartWallMs=state.manualStart;
            doneByWallMs=state.mode==2?state.deadline:0;
            status=state.status;
            manualBlockedReason=busy?"Wait for the current change to finish":doneByEnabled?"Disable Charge done by first":baseReason(c,false);
            manualTimeBlockedReason=manualBlockedReason!=null?manualBlockedReason:baseReason(c,true);
            String unavailable=busy?"Wait for the current change to finish":!chargerConnected?"Connect a charger first":baseReason(c,true);
            doneByBlockedReason=unavailable!=null?unavailable:c.estimateUnavailableReason;
            // The picker remains available offline. Connected active plans are
            // validated and explicitly reprogrammed by setDoneTime instead.
            timeBlockedReason=busy?"Wait for the current change to finish":c.scope.isEmpty()?"Choose a scooter first":null;
        }
    }
    static final class State {
        int mode, minute=8*60;
        long start, manualStart, wire, deadline, armed, calculatedWire;
        double target=100, estimate, capturedSoc=-1;
        boolean retired;
        String status="";
        long earlyFirst, earlyLast;
        State copy(){State n=new State();n.mode=mode;n.minute=minute;n.start=start;n.manualStart=manualStart;n.wire=wire;n.deadline=deadline;n.armed=armed;n.calculatedWire=calculatedWire;n.target=target;n.estimate=estimate;n.capturedSoc=capturedSoc;n.retired=retired;n.status=status;return n;}
    }
    public static final class Plan {
        public final long deadlineWallMs, startWallMs;
        public final int offsetSeconds;
        public final boolean startNow;
        Plan(long deadline,long start,int offset,boolean now){deadlineWallMs=deadline;startWallMs=start;offsetSeconds=offset;startNow=now;}
    }
    private final Store store;
    private final Driver driver;
    private final Map<String,State> states=new HashMap<>();
    private long operation;
    private boolean busy;
    private Context pendingContext;
    private Consumer<String> pendingCompletion;
    public ChargeScheduleController(Store store,Driver driver){this.store=store;this.driver=driver;}
    private Context context(){
        Context raw=driver.context(),c=new Context();
        c.scope=raw.scope;c.ready=raw.ready;c.wholeHours=raw.wholeHours;c.generation=raw.generation;c.now=raw.now;c.sampleMs=raw.sampleMs;
        c.charging=raw.charging;c.enabled=raw.enabled;c.wireStart=raw.wireStart;c.soc=raw.soc;c.target=raw.target;c.estimateMinutes=raw.estimateMinutes;
        c.timeReason=raw.timeReason;c.toggleReason=raw.toggleReason;c.estimateUnavailableReason=raw.estimateUnavailableReason;c.zone=(TimeZone)raw.zone.clone();return c;
    }
    private State state(Context c){return states.computeIfAbsent(c.scope,k->decode(store.read(k)));}
    public boolean busy(){return busy;}
    public View view(){Context c=context();reconcile(c);State s=state(c);State shown=s;
        if(s.start==0&&c.wireStart!=null&&c.wireStart>0){shown=s.copy();shown.start=fromWire(c.wireStart,c.zone.getOffset(c.now));
            // Without a prior confirmed start, any readback after an attempted
            // calculated write may be ours (including an earlier failed attempt).
            // Only genuinely unowned native times can seed a manual preference.
            if(shown.manualStart==0&&s.calculatedWire==0)shown.manualStart=shown.start;}
        return new View(shown,c,busy);}
    /** Fresh observations may retire a remembered intention; this method never touches the radio. */
    public void observe(){Context c=context();
        // A transport can remove its queued callback when it closes between two steps.
        // The service's connection notification must release that operation too.
        if(busy&&pendingContext!=null&&!sameContext(pendingContext,c))interrupt();
        reconcile(c);
    }
    private void reconcile(Context c){
        State s=state(c);if(busy||!c.ready||c.scope.isEmpty())return;
        // Positive changed readback is useful evidence; absent/zero after reboot is ambiguous.
        if(s.wire>0&&c.wireStart!=null&&c.wireStart>0&&c.wireStart!=s.wire){
            long offset=s.wire*1000L-s.start;
            if(Math.abs(offset)>86_400_000L)offset=c.zone.getOffset(c.now);
            boolean manualContext=s.mode!=2&&s.manualStart>0&&s.start==s.manualStart
                    &&s.wire!=s.calculatedWire&&c.wireStart!=s.calculatedWire;
            s.mode=0;s.retired=false;s.start=fromWire(c.wireStart,offset);s.wire=c.wireStart;
            // Follow genuine manual edits made on the scooter/OEM app, but do not
            // mistake a late readback from our calculated write for a manual choice.
            if(manualContext)s.manualStart=s.start;
            s.status="Charging time changed on the scooter";save(c,s);return;
        }
        if(s.mode==0)return;
        if(finite(c.target)&&c.target>0&&c.target<=100&&c.target!=s.target){
            // A ceiling edit changes the ETA contract. Preserve the actual scheduled start,
            // but stop presenting an obsolete finish deadline as a calculated plan.
            if(s.mode==2){s.mode=1;s.deadline=s.start+24*60*60_000L;s.status="Charge limit changed; update Charge done by";}
            s.target=c.target;save(c,s);
        }
        if(finite(c.soc)&&c.soc>=s.target){retire(c,s,"Charge target reached");return;}
        if(c.charging==null)return;
        if(c.now>=s.deadline+START_GRACE_MS){retire(c,s,"Charging schedule ended");return;}
        if(c.now>=s.start+START_GRACE_MS&&c.charging==0){retire(c,s,"Scheduled start has passed");return;}
        if(c.charging==1&&c.now<s.start&&c.now>=s.armed+SETTLE_MS){
            if(c.sampleMs>0&&c.sampleMs!=s.earlyLast){
                if(s.earlyFirst==0||c.sampleMs<s.earlyLast)s.earlyFirst=c.sampleMs;
                s.earlyLast=c.sampleMs;
                if(c.sampleMs-s.earlyFirst>=5000)retire(c,s,"Charging started before the schedule");
            }
        }else{s.earlyFirst=s.earlyLast=0;}
    }
    private void retire(Context c,State s,String status){s.mode=0;s.retired=true;s.status=status;s.earlyFirst=s.earlyLast=0;save(c,s);}
    private void save(Context c,State s){if(!c.scope.isEmpty())store.write(c.scope,encode(s));}
    private static String baseReason(Context c,boolean needsTime){
        if(c.scope.isEmpty()||!c.ready)return "Connect the scooter first";
        if(c.toggleReason!=null)return c.toggleReason;
        return needsTime?c.timeReason:null;
    }
    public void setDoneTime(int hour,int minute,Consumer<String> completion){
        Context c=context();View view=view();
        if(hour<0||hour>23||minute<0||minute>59){completion.accept("Choose a valid time");return;}
        if(view.timeBlockedReason!=null){completion.accept(view.timeBlockedReason);return;}
        if(c.ready&&view.doneByEnabled){enableDone(hour*60+minute,completion);return;}
        State s=state(c);s.minute=hour*60+minute;save(c,s);completion.accept(null);
    }
    public void setDoneEnabled(boolean enabled,Consumer<String> completion){
        Context c=context();if(enabled){enableDone(state(c).minute,completion);return;}
        if(state(c).mode!=2){completion.accept(null);return;}
        disable(true,completion);
    }
    private void enableDone(int minute,Consumer<String> completion){
        Context c=context();View view=view();
        String blocked=view.doneByEnabled?(busy?"Wait for the current change to finish":baseReason(c,true)):view.doneByBlockedReason;
        if(blocked!=null){completion.accept(blocked);return;}
        State previous=state(c);boolean paused=!Integer.valueOf(1).equals(c.charging);
        if(!paused&&c.estimateUnavailableReason!=null){completion.accept(c.estimateUnavailableReason);return;}
        if(paused){
            // Native scheduled charging intentionally pauses current flow. Reuse its
            // captured ETA only while fresh electrical readings still prove that the
            // same amount of charging remains before the owned start time.
            if(previous.mode!=2||!Integer.valueOf(0).equals(c.charging)||c.now>=previous.start
                    ||previous.capturedSoc<0||!finite(c.soc)||c.soc!=previous.capturedSoc
                    ||!finite(c.target)||c.target!=previous.target||previous.estimate<=0){
                completion.accept("A fresh charging estimate is needed; disable Charge done by and charge briefly");return;
            }
            c.estimateMinutes=previous.estimate;
        }
        if(!finite(c.soc)||c.soc<0||c.soc>100||!finite(c.target)||c.target<=0||c.target>100||!finite(c.estimateMinutes)||c.estimateMinutes<=0){
            completion.accept(finite(c.soc)&&finite(c.target)&&c.soc>=c.target?"Battery is already at its charge limit":"Wait for a charging time estimate");return;
        }
        final Plan plan;
        try{plan=plan(c.now,minute,c.estimateMinutes,c.zone,c.wholeHours);}catch(IllegalArgumentException bad){completion.accept("Charging time is outside a usable range");return;}
        // Keep an unowned native manual time before replacing it with our calculated
        // start. An old owned done-by time is intentionally not treated as manual.
        if(state(c).manualStart==0&&view.manualStartWallMs>0){state(c).manualStart=view.manualStartWallMs;save(c,state(c));}
        if(plan.startNow){
            disable(result->{if(result==null){State s=state(c);s.minute=minute;save(c,s);completion.accept("Charging now — not enough time to wait before the deadline");}else completion.accept(result);});return;
        }
        program(c,plan.startWallMs,plan.offsetSeconds,()->{
            State s=state(c);s.mode=2;s.minute=minute;s.start=plan.startWallMs;s.wire=plan.startWallMs/1000+plan.offsetSeconds;
            s.deadline=plan.deadlineWallMs;s.armed=context().now;s.target=c.target;s.estimate=c.estimateMinutes;s.capturedSoc=c.soc;s.retired=false;s.status="";save(c,s);
        },true,c.target,paused?c.soc:null,completion);
    }
    public void setManualEnabled(boolean enabled,Consumer<String> completion){
        Context c=context();View view=view();
        if(view.manualBlockedReason!=null){completion.accept(view.manualBlockedReason);return;}
        if(!enabled){disable(completion);return;}
        long start=view.manualStartWallMs;
        if(start<=c.now){
            if(start<=0){completion.accept("Choose Charge start first");return;}
            Calendar old=Calendar.getInstance(c.zone);old.setTimeInMillis(start);
            start=nextOccurrence(c.now,old.get(Calendar.HOUR_OF_DAY)*60+old.get(Calendar.MINUTE),c.zone);
        }
        final long chosen=start;int offset=c.zone.getOffset(c.now)/1000;
        // Enabling an existing native time is also an explicit manual choice. Keep
        // it even if confirmation fails after the native timestamp was rewritten.
        state(c).manualStart=chosen;save(c,state(c));
        program(c,chosen,offset,()->{state(c).manualStart=chosen;state(c).calculatedWire=0;armManual(c,chosen,offset);},true,null,null,completion);
    }
    public void setManualStart(long wallMs,Consumer<String> completion){
        Context c=context();View view=view();
        String reason=view.manualTimeBlockedReason;
        if(reason!=null){completion.accept(reason);return;}
        if(wallMs<=c.now||wallMs-c.now>48*60*60_000L){completion.accept("Choose a charge start within 48 hours");return;}
        if(c.wholeHours){Calendar cal=Calendar.getInstance(c.zone);cal.setTimeInMillis(wallMs);if(cal.get(Calendar.MINUTE)!=0||cal.get(Calendar.SECOND)!=0){completion.accept("Choose a whole hour within 48 hours");return;}}
        int offset=c.zone.getOffset(c.now)/1000;
        boolean enabled=view.manualEnabled;
        program(c,wallMs,offset,()->{
            state(c).manualStart=wallMs;state(c).calculatedWire=0;
            if(enabled)armManual(c,wallMs,offset);
            else{State s=state(c);s.start=wallMs;s.wire=wallMs/1000+offset;s.retired=false;save(c,s);}
        },false,null,null,completion);
    }
    private void armManual(Context c,long start,int offset){State s=state(c);s.mode=1;s.start=start;s.wire=start/1000+offset;s.armed=context().now;
        s.deadline=start+24*60*60_000L;s.target=finite(c.target)&&c.target>0&&c.target<=100?c.target:100;s.retired=false;s.status="";save(c,s);}
    private void disable(Consumer<String> completion){
        disable(false,completion);
    }
    private void disable(boolean restoreManual,Consumer<String> completion){
        Context c=context();String reason=busy?"Wait for the current change to finish":baseReason(c,false);
        if(reason!=null){completion.accept(reason);return;}
        long token=++operation;busy=true;pendingContext=c;pendingCompletion=completion;
        driver.enabled(false,success->{
            if(!valid(c,token,completion))return;
            if(!success){finishBusy();completion.accept("Schedule cancellation was not confirmed; reconnect and try again");return;}
            // OFF is a confirmed change even if a later clock/time restoration fails.
            // Persist it immediately, and never retry this operation on reconnect.
            retire(c,state(c),"");
            if(!restoreManual||state(c).manualStart==0){finishBusy();completion.accept(null);return;}
            restoreManualStart(c,token,completion);
        });
    }
    private void restoreManualStart(Context c,long token,Consumer<String> completion){
        Context latest=context();
        if(latest.timeReason!=null){restoreFailed(c,"Charge start could not be restored: "+latest.timeReason,completion);return;}
        driver.clock(clock->{
            if(!valid(c,token,completion))return;
            if(!clock){restoreFailed(c,"the scooter clock could not be confirmed",completion);return;}
            long start=state(c).manualStart;Context now=context();
            if(start<=now.now){Calendar old=Calendar.getInstance(c.zone);old.setTimeInMillis(start);
                start=nextOccurrence(now.now,old.get(Calendar.HOUR_OF_DAY)*60+old.get(Calendar.MINUTE),c.zone);}
            final long restored=start;int offset=c.zone.getOffset(c.now)/1000;
            driver.time(restored,offset,time->{
                if(!valid(c,token,completion))return;
                if(!time){restoreFailed(c,"restoring Charge start was not confirmed",completion);return;}
                State s=state(c);s.manualStart=restored;s.start=restored;s.wire=restored/1000+offset;s.calculatedWire=0;s.status="";save(c,s);
                finishBusy();completion.accept(null);
            });
        });
    }
    private void restoreFailed(Context c,String detail,Consumer<String> completion){
        State s=state(c);s.status="Charge done by is off; "+detail;save(c,s);finishBusy();completion.accept(s.status);
    }
    private void program(Context c,long start,int offset,Runnable commit,boolean enable,Double deadlineTarget,Double stableSoc,Consumer<String> completion){
        if(busy){completion.accept("Wait for the current change to finish");return;}
        long token=++operation;busy=true;pendingContext=c;pendingCompletion=completion;
        driver.clock(clock->{
            if(!valid(c,token,completion))return;
            if(!clock){finishBusy();completion.accept("Could not set the scooter clock; schedule unchanged");return;}
            if(stableSoc!=null&&!stableSoc.equals(context().soc)){
                finishBusy();completion.accept("Battery level changed; a fresh charging estimate is needed");return;
            }
            // A transmitted calculated time may apply despite a lost confirmation.
            // Retain its identity across restart so its later readback cannot replace
            // the separate manual preference during reconciliation.
            if(deadlineTarget!=null){State s=state(c);s.calculatedWire=start/1000+offset;save(c,s);}
            driver.time(start,offset,time->{
                if(!valid(c,token,completion))return;
                if(!time){finishBusy();completion.accept("Charge start was not confirmed; check the scooter schedule");return;}
                // This is a confirmed partial change even if enabling later fails. Keep its time,
                // but never claim that the calculated deadline was successfully armed.
                State s=state(c);boolean wasEnabled=s.mode!=0||Integer.valueOf(1).equals(c.enabled);
                s.start=start;s.wire=start/1000+offset;s.mode=wasEnabled?1:0;s.retired=false;
                s.deadline=start+24*60*60_000L;s.armed=context().now;save(c,s);
                if(!enable){commit.run();finishBusy();completion.accept(null);return;}
                Context latest=context();
                if(deadlineTarget!=null&&(!finite(latest.target)||!deadlineTarget.equals(latest.target))){
                    finishBusy();completion.accept("Charge limit changed; check the schedule and try again");return;
                }
                if(stableSoc!=null&&!stableSoc.equals(latest.soc)){
                    finishBusy();completion.accept("Charge start changed, but a fresh charging estimate is needed");return;
                }
                if(start<=latest.now){
                    // ACK/readback can consume a start only seconds away. Never enable a
                    // newly expired timestamp or silently roll its start to another day.
                    driver.enabled(false,disabled->{
                        if(!valid(c,token,completion))return;
                        finishBusy();
                        if(disabled){retire(c,state(c),"");completion.accept("Charging now — the calculated start time has passed");}
                        else completion.accept("Start time passed, but schedule cancellation was not confirmed");
                    });return;
                }
                driver.enabled(true,switched->{
                    if(!valid(c,token,completion))return;
                    finishBusy();
                    if(switched){
                        Context after=context();
                        if(deadlineTarget!=null&&(!finite(after.target)||!deadlineTarget.equals(after.target))){
                            armManual(after,start,offset);completion.accept("Charge limit changed; the charge start is saved without a finish estimate");
                        }else if(stableSoc!=null&&!stableSoc.equals(after.soc)){
                            armManual(after,start,offset);completion.accept("Battery level changed; the charge start is saved without a finish estimate");
                        }else{commit.run();completion.accept(null);}
                    }
                    else completion.accept("Charge start changed, but enabling the schedule was not confirmed");
                });
            });
        });
    }
    private boolean valid(Context original,long token,Consumer<String> completion){
        if(token!=operation)return false;
        Context current=context();
        if(!sameContext(original,current)){interrupt();return false;}
        return true;
    }
    private static boolean sameContext(Context original,Context current){
        return current.ready&&original.scope.equals(current.scope)&&original.generation==current.generation
                &&original.zone.getID().equals(current.zone.getID())
                &&original.zone.getOffset(original.now)==current.zone.getOffset(current.now);
    }
    private void finishBusy(){busy=false;pendingContext=null;pendingCompletion=null;}
    private void interrupt(){Consumer<String> callback=pendingCompletion;++operation;finishBusy();
        if(callback!=null)callback.accept("Connection changed; check the scooter schedule before trying again");}
    /** Stops callback continuations only. It never guesses whether a transmitted command applied. */
    public void cancel(){++operation;finishBusy();}
    public static Plan plan(long now,int minute,double estimateMinutes,TimeZone zone,boolean wholeHours){
        if(now<=0||minute<0||minute>=1440||!Double.isFinite(estimateMinutes)||estimateMinutes<=0||estimateMinutes>48*60)
            throw new IllegalArgumentException("Invalid charging plan");
        long deadline=nextOccurrence(now,minute,zone);
        long start=deadline-(long)Math.ceil(estimateMinutes*60_000d)-BUFFER_MS;
        int offset=zone.getOffset(now)/1000;
        // The scooter RTC retains the offset sent now; it does not perform a later DST change.
        // Round its wire-local hour earlier, including zones with half-hour DST transitions.
        if(wholeHours)start=Math.floorDiv(start+offset*1000L,3_600_000L)*3_600_000L-offset*1000L;
        else start=Math.floorDiv(start,60_000L)*60_000L;
        return new Plan(deadline,Math.max(now,start),offset,start<=now);
    }
    public static long nextOccurrence(long now,int minute,TimeZone zone){
        ZoneId id=zone.toZoneId();LocalDate today=Instant.ofEpochMilli(now).atZone(id).toLocalDate();
        LocalTime chosen=LocalTime.of(minute/60,minute%60);
        for(int day=0;day<3;day++){
            // Rebuild the requested wall time for each day. Normalizing a spring gap
            // must not carry its shifted hour into tomorrow's perfectly valid time.
            LocalDateTime wall=LocalDateTime.of(today.plusDays(day),chosen);
            List<ZoneOffset> offsets=id.getRules().getValidOffsets(wall);
            if(offsets.isEmpty()){
                long shifted=wall.atZone(id).toInstant().toEpochMilli();
                if(shifted>now)return shifted; // A nonexistent time moves forward by the gap.
            }else{
                long next=Long.MAX_VALUE;
                for(ZoneOffset offset:offsets){long candidate=wall.toInstant(offset).toEpochMilli();if(candidate>now)next=Math.min(next,candidate);}
                if(next!=Long.MAX_VALUE)return next; // Earliest remaining occurrence in a repeated hour.
            }
        }
        throw new IllegalArgumentException("No upcoming charge time");
    }
    private static long fromWire(long raw,long clockOffsetMs){return raw*1000L-clockOffsetMs;}
    private static boolean finite(Double n){return n!=null&&Double.isFinite(n);}
    static String encode(State s){return "3|"+s.mode+"|"+s.minute+"|"+s.start+"|"+s.wire+"|"+s.deadline+"|"+s.armed+"|"+s.target+"|"+s.estimate+"|"+s.retired+"|"+s.manualStart+"|"+s.calculatedWire+"|"+s.capturedSoc;}
    static State decode(String text){
        State s=new State();if(text==null)return s;
        try{String[] v=text.split("\\|",-1);boolean legacy=v.length==10&&v[0].equals("1"),v2=v.length==11&&v[0].equals("2");if(!legacy&&!v2&&!(v.length==13&&v[0].equals("3")))return s;
            State n=new State();n.mode=Integer.parseInt(v[1]);n.minute=Integer.parseInt(v[2]);n.start=Long.parseLong(v[3]);n.wire=Long.parseLong(v[4]);n.deadline=Long.parseLong(v[5]);n.armed=Long.parseLong(v[6]);n.target=Double.parseDouble(v[7]);n.estimate=Double.parseDouble(v[8]);n.retired=Boolean.parseBoolean(v[9]);
            // Version 1 did not save manual and calculated starts separately. Only
            // a record without a calculated estimate can safely supply that preference.
            n.manualStart=legacy?(n.mode!=2&&n.estimate==0?n.start:0):Long.parseLong(v[10]);
            n.calculatedWire=legacy||v2?(n.mode==2?n.wire:0):Long.parseLong(v[11]);
            n.capturedSoc=legacy||v2?-1:Double.parseDouble(v[12]);
            if(n.mode<0||n.mode>2||n.minute<0||n.minute>=1440||n.start<0||n.start>0xffffffffL*1000L||n.manualStart<0||n.manualStart>0xffffffffL*1000L||n.wire<0||n.wire>0xffffffffL||n.calculatedWire<0||n.calculatedWire>0xffffffffL||n.deadline<0||n.deadline>0xffffffffL*1000L||n.armed<0||n.armed>0xffffffffL*1000L||!Double.isFinite(n.target)||n.target<=0||n.target>100||!Double.isFinite(n.estimate)||n.estimate<0||n.estimate>2880||!Double.isFinite(n.capturedSoc)||n.capturedSoc< -1||n.capturedSoc>100)return s;
            if(n.mode!=0&&(n.start==0||n.armed==0||n.deadline<n.start))return s;return n;
        }catch(RuntimeException invalid){return s;}
    }
}
