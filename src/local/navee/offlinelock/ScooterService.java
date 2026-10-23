package local.navee.offlinelock;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;

import org.json.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Foreground connected-device service keeps recording alive through folds and screen-off. */
public final class ScooterService extends Service {
    private static volatile boolean uiVisible;
    private static volatile ScooterService running;
    /** Main may have lost its binder/permission while the foreground service still owns data. */
    static ScooterService currentForMaintenance() { return running; }
    /** Main calls this before a binder exists, so late service starts see current visibility. */
    public static void setUiVisible(boolean value){
        uiVisible=value;ScooterService service=running;if(service==null)return;
        if(Looper.myLooper()==Looper.getMainLooper())service.applyVisibility(value);
        else service.handler.post(()->{if(running==service)service.applyVisibility(uiVisible);});
    }
    public final class LocalBinder extends Binder {public ScooterService service(){return ScooterService.this;}}
    private final LocalBinder binder=new LocalBinder();private final Handler handler=new Handler(Looper.getMainLooper());private final LinkedHashSet<Runnable> listeners=new LinkedHashSet<>();
    public ScooterProfiles profiles;public BleController ble;public RideStore rides;public PhoneRideStore phoneRides;public TelemetryHistory history;public SharedPreferences settings;
    private final Map<String,TelemetryHistory.Snapshot> displayCache=new LinkedHashMap<>();
    private final Set<String> loadingHistory=new HashSet<>(),clearingHistory=new HashSet<>();
    private final Map<String,Long> observedSources=new HashMap<>(),historyClearCutoff=new HashMap<>();
    private String observedProfile = "";
    private boolean historyConnected,historyDirty;private long historySession,lastHistoryWrite;
    private long historyWall,historyMono;
    private long historySessionCounter=new java.util.Random().nextLong()&Long.MAX_VALUE;
    private final ExecutorService capacityWorker = Executors.newSingleThreadExecutor(r -> new Thread(r, "battery-capacity-analysis"));
    private final Map<String, CapacityCache> capacityCache = new LinkedHashMap<>();
    private final Map<String, List<Consumer<BatteryCapacityEstimate.Result>>> capacityPending = new HashMap<>();
    private long capacityGeneration;
    private final Map<String, LearnedRangeEstimate.Result> rangeCache = new LinkedHashMap<>();
    private final Map<String, RangeJob> rangePending = new HashMap<>();
    private static final class RangeJob {
        final String profile;
        final List<Consumer<LearnedRangeEstimate.Result>> callbacks = new ArrayList<>();
        int restarts;
        RangeJob(String profile) { this.profile = profile; }
    }
    private static final class CapacityCache {
        final long mono;
        final BatteryCapacityEstimate.Result result;
        CapacityCache(long mono, BatteryCapacityEstimate.Result result) { this.mono = mono; this.result = result; }
    }
    private ChargeScheduleController chargeScheduleController;
    private ChargeSessionStore chargeStore;
    private ChargeSessionStore.Snapshot chargeSnapshot;
    private final ChargeReferenceGate chargeReferenceGate = new ChargeReferenceGate();
    private final ChargerDetection chargerDetection = new ChargerDetection();
    private final ChargeProgressTracker chargeProgress = new ChargeProgressTracker();
    /** Charge done by's bounded measuring step; at most one, for the selected scooter. */
    private final ChargeMeasureWindow chargeMeasure = new ChargeMeasureWindow();
    private boolean chargeModelLoading, chargeUpdating, chargeExporting;
    private long chargeEndingMono;
    private ChargeRecoveryPolicy chargeRecovery;
    private String chargeStatus = "No charge recording yet";
    private final ExecutorService chargeWorker = Executors.newSingleThreadExecutor(r -> new Thread(r,"charge-curve-analysis"));
    private PhoneRideRecorder phoneRecorder;private boolean phoneLocationForeground;private int phoneSavingCount;private String phoneFinalStatus;
    private static final long RECORDING_WAKE_TIMEOUT_MS=10*60*1000L, RECORDING_WAKE_RENEW_MS=5*60*1000L;
    private long wakeLockRenewedMs;
    private PowerManager.WakeLock wakeLock;private boolean visible,foregroundStarted,gapMarked,workActive,destroyed;private int samples;private long lastNotification,backgroundDeadlineMs;
    private String trendProfile = "", recordingError;
    /** Forgotten-ride decision for the active ride only; rebuilt unarmed after any restart. */
    private RideAutoStopPolicy rideAutoStop;private long rideAutoStopDisconnects;
    static final String RIDE_AUTO_STOP = "ride_auto_stop";private static final int AUTO_STOP_NOTICE = 72, CHARGE_DONE_NOTICE = 73;
    private final RangeFollowPolicy rangeFollowPolicy = new RangeFollowPolicy();
    private boolean selectingRange;
    private final ArrayDeque<Ui.Point> power=new ArrayDeque<>(),voltage=new ArrayDeque<>(),speed=new ArrayDeque<>();
    private boolean dataMaintenance, guardedLifecycle;
    private final SharedPreferences.OnSharedPreferenceChangeListener settingsChanged =
            (prefs, key) -> {
                if (key != null && key.startsWith("charge_charger_")) {
                    if (Looper.myLooper() == Looper.getMainLooper()) chargeReferenceGate.reset();
                    else handler.post(chargeReferenceGate::reset);
                    return;
                }
                if (key == null) return;
                if (!key.equals("background_connection") && !key.equals("auto_connect")) return;
                Runnable apply =
                        () -> {
                            if (!destroyed)
                                applyWorkPolicy(
                                        key.equals("auto_connect")
                                                && settings.getBoolean("auto_connect", true));
                        };
                if (Looper.myLooper() == Looper.getMainLooper()) apply.run();
                else handler.post(apply);
            };

    @Override public void onCreate(){super.onCreate();
        // A notification or delayed Activity bind must not reopen stores during backup/restore.
        // MainActivity recovers an interrupted restore off-main before starting this service.
        // A sticky system restart cannot open a half-restored data tree in the meantime.
        if (!DataMaintenance.readyForStartup(this)) {
            guardedLifecycle = destroyed = true; stopSelf(); return;
        }
        settings = getSharedPreferences("app_settings", MODE_PRIVATE);
        profiles=new ScooterProfiles(this);ble=new BleController(this,profiles);ble.setListener(this::changed);
        // A guided test interrupted by process death stays outstanding; nothing is written on restart.
        ble.recoverTests();try{
            rides = new RideStore(new File(getFilesDir(), "rides"));
        }catch(IOException e){rides=null;}
        history=new TelemetryHistory(this);ensureDisplayCache();
        try { chargeStore=new ChargeSessionStore(new File(getFilesDir(),"charge-recordings"));refreshChargeCurves(); }
        catch(IOException unavailable){chargeStatus="Charge recording storage is unavailable";}

        try{
            phoneRides = new PhoneRideStore(new File(getFilesDir(), "phone-rides"));
        }catch(IOException ignored){}
        PowerManager pm=getSystemService(PowerManager.class);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NaveeOffline:Recorder");
        wakeLock.setReferenceCounted(false);
        visible=uiVisible;running=this;settings.registerOnSharedPreferenceChangeListener(settingsChanged);ble.visibility(visible,isRecording()||isChargeRecording()||chargeMeasure.active(),backgroundDeadlineMs);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if (destroyed || DataMaintenance.active()) { stopSelf(); return START_NOT_STICKY; }
        if(!ble.permissions()){finishChargeCapture(ChargeSessionStore.Stop.PERMISSION_LOST);endChargeMeasure("Bluetooth permission was removed while measuring");stopIdleWork();return START_NOT_STICKY;}
        // A queued startForegroundService still needs to fulfill Android's startup contract.
        // Current visibility is checked immediately afterwards, before any BLE work starts.
        String action=intent==null?null:intent.getAction();
        boolean stopAction="STOP_RECORDING".equals(action)||"STOP_CHARGE_RECORDING".equals(action)||"DISCONNECT".equals(action);
        startForegroundNotice(true);applyVisibility(uiVisible,!stopAction);
        if("STOP_RECORDING".equals(action))stopRide();
        else if("STOP_CHARGE_RECORDING".equals(action))stopChargeRecording();
        else if("SCHEDULE_CHARGE_NOW".equals(action))scheduleChargeNow();
        else if("DISCONNECT".equals(action)){ble.disconnect();if(!isRecording()&&!isChargeRecording()&&!phoneSaving()&&!visible){stopIdleWork();return START_NOT_STICKY;}}
        applyWorkPolicy(!stopAction||isRecording());return isRecording()?START_STICKY:START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent){return guardedLifecycle?null:binder;}
    public void listen(Runnable listener){listeners.add(listener);}public void unlisten(Runnable listener){listeners.remove(listener);}
    public boolean simulating() { return ble != null && ble.simulating(); }

    /** A full portable backup is deliberately refused while another operation owns app data. */
    public String dataMaintenanceUnavailable() {
        if (destroyed || dataMaintenance || DataMaintenance.active()) return "App data maintenance is already in progress";
        if (isRecording() || isChargeRecording()) return "Stop the current recording first";
        if (phoneSaving() || phoneRecorder != null) return "Wait for the phone recording to finish saving";
        if (chargeExporting) return "Finish the current charge export first";
        if (chargeScheduleController != null && chargeScheduleController.busy()) return "Wait for the charging schedule to finish";
        if (chargeMeasure.active()) return "Wait for Charge done by to finish measuring";
        if (!clearingHistory.isEmpty()) return "Wait for history cleanup to finish";
        if (ble != null && ble.pending() != null) return "Wait for the current scooter change to finish";
        ShizukuCaptureClient capture = ShizukuCaptureClient.get(this);
        if (capture.busy || capture.pendingCleanup()) return "Finish owner setup and restore NAVEE logging first";
        if (getSharedPreferences("pending_documents", MODE_PRIVATE).contains("path")) return "Finish the current export first";
        if (new File(getFilesDir(), "pending-owner-import.json").exists()
                || new File(getFilesDir(), "pending-owner-import.tmp").exists()) return "Finish importing owner setup first";
        return null;
    }

    /**
     * Terminal maintenance boundary. The caller closes its screens before invoking this on main,
     * and restarts the process after maintenance even when it is cancelled or fails. A null result
     * is delivered only after all accepted durable writes have finished and SQLite is closed.
     */
    public void prepareDataMaintenance(Consumer<String> completion) {
        if (Looper.myLooper() != Looper.getMainLooper())
            throw new IllegalStateException("Enter app data maintenance on the main thread");
        Objects.requireNonNull(completion);
        String unavailable = dataMaintenanceUnavailable();
        if (unavailable != null) { completion.accept(unavailable); return; }
        // Capture the last genuine values before suppressing listeners and Bluetooth callbacks.
        observeTelemetry();
        persistHistory(true);
        DataMaintenance.enter();
        dataMaintenance = true;
        if(chargeScheduleController!=null)chargeScheduleController.cancel();
        resetChargeEstimateContext();
        destroyed = true;
        workActive = false;
        uiVisible = false;
        visible = false;
        cancelBackgroundDeadline();
        handler.removeCallbacksAndMessages(null);
        listeners.clear();
        ble.setListener(null);
        ble.destroy();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        foregroundStarted = false;
        capacityPending.clear();
        rangePending.clear();
        chargeWorker.shutdown();
        capacityWorker.shutdown();
        if (history != null) history.close();
        new Thread(() -> {
            String error = null;
            long deadline = SystemClock.elapsedRealtime() + 30000;
            try {
                boolean drained = chargeWorker.awaitTermination(remaining(deadline), TimeUnit.MILLISECONDS);
                drained &= capacityWorker.awaitTermination(remaining(deadline), TimeUnit.MILLISECONDS);
                if (history != null) drained &= history.closeAndAwait(remaining(deadline));
                if (!drained) error = "Couldn't prepare app data. Reopen Volt.";
                else {
                    // commit waits for earlier apply writes for each preference file. The empty
                    // transaction changes no user setting and leaves no maintenance marker behind.
                    for (String name : new String[]{"app_settings", "profiles", "scooter", "control_evidence", OnboardingScreen.PREFS})
                        if (!getSharedPreferences(name, MODE_PRIVATE).edit().commit())
                            throw new IOException("Preferences did not finish saving");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                error = "Couldn't prepare app data. Reopen Volt.";
            } catch (RuntimeException | IOException failure) {
                error = "Couldn't prepare app data. Reopen Volt.";
            }
            final String result = error;
            handler.post(() -> completion.accept(result));
        }, "app-data-maintenance").start();
    }

    private static long remaining(long deadline) {
        return Math.max(0, deadline - SystemClock.elapsedRealtime());
    }

    /** Runtime-only preview: actual caches and journals never receive sample readings. */
    public boolean setSimulation(boolean enabled) {
        if (destroyed || ble == null) return false;
        if (enabled == simulating()) return true;
        if (enabled && (isRecording() || isChargeRecording() || phoneSaving() || chargeMeasure.active())) {
            android.widget.Toast.makeText(this, chargeMeasure.active() ? "Wait for Charge done by to finish measuring" : "Finish the current recording before simulating", android.widget.Toast.LENGTH_LONG).show();
            return false;
        }
        if (enabled && (profiles.active() == null || !settings.getBoolean("developer_options", false))) return false;
        // Flush only genuine data before the BLE layer closes its current connection.
        if (enabled) { observeTelemetry(); persistHistory(true); }
        historyConnected = false; historyDirty = false; observedSources.clear();
        power.clear(); voltage.clear(); speed.clear();
        invalidateCapacityEstimate();
        resetChargeEstimateContext();
        if(chargeScheduleController!=null)chargeScheduleController.cancel();
        ble.setSimulation(enabled);
        cancelBackgroundDeadline();
        applyWorkPolicy(false);
        changed();
        return simulating() == enabled;
    }

    public boolean isChargeRecording(){return chargeStore!=null&&chargeStore.active();}
    private String chargerKey(){return "charge_charger_"+activeProfileId();}
    /** Null means Automatic. Preserve existing explicit per-scooter choices on upgrade. */
    public ChargeSessionStore.Charger chargeCharger(){
        try{return ChargeSessionStore.Charger.valueOf(settings.getString(chargerKey(),""));}catch(IllegalArgumentException invalid){return null;}
    }
    /** Why a charger family drives estimates, in precedence order. */
    public enum ChargerBasis{RECORDING,MANUAL,DETECTED,UNKNOWN}
    /** A recording's manually confirmed label is temporary and never changes the preference. */
    public ChargerBasis chargerBasis(){
        if(isChargeRecording()&&activeProfileId().equals(chargeStore.profile()))return ChargerBasis.RECORDING;
        if(chargeCharger()!=null)return ChargerBasis.MANUAL;
        return detectedCharger()!=null?ChargerBasis.DETECTED:ChargerBasis.UNKNOWN;
    }
    public ChargeSessionStore.Charger effectiveChargeCharger(){
        switch(chargerBasis()){
            case RECORDING:return chargeStore.charger();
            case MANUAL:return chargeCharger();
            case DETECTED:return detectedCharger();
            default:return null;
        }
    }
    /** Automatic inference for the active scooter's current charge, already checked for age. */
    private ChargeSessionStore.Charger detectedCharger(){
        ScooterProfiles.Profile profile=profiles==null?null:profiles.active();
        return destroyed||simulating()||profile==null?null:chargerDetection.selected(profile.id,SystemClock.elapsedRealtime());
    }
    private int qualifiedSessions(ChargeSessionStore.Charger charger){
        ChargeSessionStore.Curve curve=chargeSnapshot==null?null:chargeSnapshot.curves.get(ChargeSessionStore.key(activeProfileId(),charger));
        return curve==null?0:curve.qualifiedSessions;
    }
    public boolean setChargeCharger(ChargeSessionStore.Charger charger){
        if(destroyed||isChargeRecording()||activeProfileId().isEmpty())return false;
        chargeReferenceGate.reset();
        SharedPreferences.Editor edit=settings.edit();
        if(charger==null)edit.remove(chargerKey());else edit.putString(chargerKey(),charger.name());
        edit.apply();changed();return true;
    }
    public String chargeCaptureUnavailable(){
        if(destroyed||chargeStore==null)return "Charge recording storage is unavailable";
        if(!settings.getBoolean("developer_options",false))return "Enable developer options first";
        if(simulating())return "Turn off simulation to record a real charge";
        if(isRecording()||phoneSaving())return "Finish the ride recording first";
        if(chargeExporting)return "Preparing charge export";
        if(chargeModelLoading)return "Loading saved charge recordings";
        if(!ble.ready()||profiles.active()==null||ble.profile()==null||!profiles.active().id.equals(ble.profile().id))return "Connect your scooter first";
        ChargeSessionStore.Frame frame=chargeFrame(SystemClock.elapsedRealtime(),System.currentTimeMillis());
        if(!frame.usable())return "Wait for fresh charging readings";
        if(frame.soc.value>=100)return "Battery is already at 100%";
        NotificationManager manager=getSystemService(NotificationManager.class);
        NotificationChannel channel=manager.getNotificationChannel("scooter");
        if(!manager.areNotificationsEnabled()||channel!=null&&channel.getImportance()==NotificationManager.IMPORTANCE_NONE)
            return "Allow app notifications so the Stop action stays available";
        return null;
    }
    public boolean startChargeRecording(ChargeSessionStore.Charger charger){
        if(isChargeRecording())return false;
        String unavailable=chargeCaptureUnavailable();
        if(unavailable!=null||charger==null){chargeStatus=unavailable==null?"Choose the attached charger":unavailable;changed();return false;}
        long mono=SystemClock.elapsedRealtime(),wall=System.currentTimeMillis();
        try{
            chargeStore.start(activeProfileId(),charger,wall,mono);chargeEndingMono=0;
            chargeRecovery=new ChargeRecoveryPolicy(activeProfileId(),ble.currentTransportSerial(),mono,ble.intentionalDisconnectSequence());
            ble.recordDiagnostic(ConnectionEventLog.Reason.CHARGE_STARTED,-1);
            chargeReferenceGate.reset();
            chargeStore.sample(chargeFrame(mono,wall),chargeRecordingEstimate());chargeStatus="Charge recording started";
            cancelBackgroundDeadline();applyWorkPolicy(false);updateNotification();changed();return isChargeRecording();
        }catch(IOException|IllegalStateException failure){chargeStatus="Could not start charge recording";finishChargeCapture(ChargeSessionStore.Stop.ERROR);changed();return false;}
    }
    public void stopChargeRecording(){finishChargeCapture(ChargeSessionStore.Stop.STOPPED);}
    public String chargeCaptureStatus(){
        if(!isChargeRecording())return chargeStatus;
        if(chargeRecovery!=null&&chargeRecovery.recovering())
            return "Reconnecting · "+Math.max(1,(chargeRecovery.remainingMs(SystemClock.elapsedRealtime())+999)/1000)+"s";
        return (chargeStore.charger()==ChargeSessionStore.Charger.STOCK?"Stock charger":"High-speed charger")+" · "
                +(chargeStore.paused()?"paused until the scheduled start":chargeStore.sampleCount()+" local samples");
    }
    /** Qualified sessions behind the curve estimates actually use; 0 while the charger is unknown, -1 while loading. */
    public int qualifiedChargeCurves(){
        if(chargeModelLoading)return -1;
        ChargeSessionStore.Charger charger=effectiveChargeCharger();
        return charger==null?0:qualifiedSessions(charger);
    }
    public String chargeLearningSummary(){
        if(chargeModelLoading)return "Loading saved charge recordings";
        ChargeSessionStore.Charger charger=effectiveChargeCharger();
        int qualified=qualifiedChargeCurves();
        return (charger==null?"Not applied until the charger is known":qualified+" qualified "+(charger==ChargeSessionStore.Charger.HIGH_SPEED?"high-speed":"stock")+" charge"+(qualified==1?"":"s"))
                +" · "+(chargeSnapshot==null?0:chargeSnapshot.sessions)+" recordings stored";
    }
    public boolean clearChargeRecordings(Runnable completion){
        if(destroyed||chargeStore==null||isChargeRecording()||chargeModelLoading)return false;
        final String profile=activeProfileId();if(profile.isEmpty())return false;chargeModelLoading=true;
        chargeWorker.execute(()->{String error=null;ChargeSessionStore.Snapshot snapshot=null;
            try{chargeStore.clear(profile);snapshot=chargeStore.snapshot();}catch(IOException failure){error="Charge recordings could not be cleared";}
            final String message=error;final ChargeSessionStore.Snapshot result=snapshot;
            handler.post(()->{if(destroyed)return;chargeModelLoading=false;if(result!=null)chargeSnapshot=result;
                chargeStatus=message==null?"Charge recordings cleared":message;changed();if(completion!=null)completion.run();});});return true;
    }
    public boolean chargeExporting(){return chargeExporting;}
    /** Read only this scooter's closed journals off-main; never re-run today's estimator on history. */
    public boolean loadChargeRecordings(Consumer<List<ChargeSessionStore.HistorySession>> completion){
        if(destroyed||dataMaintenance||chargeStore==null||activeProfileId().isEmpty())return false;
        final String profile=activeProfileId();
        chargeWorker.execute(()->{
            List<ChargeSessionStore.HistorySession> recordings=null;
            try{recordings=chargeStore.recordings(profile);}catch(IOException unavailable){}
            final List<ChargeSessionStore.HistorySession> result=recordings;
            handler.post(()->{
                if(destroyed)return;
                completion.accept(!dataMaintenance&&profile.equals(activeProfileId())?result:null);
            });
        });return true;
    }
    /** The profile is captured before work starts so changing screens cannot export a different scooter. */
    public boolean exportChargeRecordings(Consumer<File> completion){
        if(destroyed||chargeStore==null||isChargeRecording()||chargeExporting||activeProfileId().isEmpty())return false;
        final String profile=activeProfileId();chargeExporting=true;
        chargeWorker.execute(()->{
            File output=null;String error=null;
            try{
                File directory=new File(getCacheDir(),"charge-exports");
                if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException();
                File[] old=directory.listFiles();long cutoff=System.currentTimeMillis()-24*60*60*1000L;
                if(old!=null)for(File entry:old)if(entry.lastModified()<cutoff)entry.delete();
                output=File.createTempFile("charges-",".json",directory);
                int count;
                try(Writer writer=new BufferedWriter(new OutputStreamWriter(new FileOutputStream(output),java.nio.charset.StandardCharsets.UTF_8))){
                    count=chargeStore.exportJson(profile,writer);
                }
                if(count==0){output.delete();output=null;error="No saved charge recordings for this scooter";}
            }catch(IOException|IllegalStateException failure){if(output!=null)output.delete();output=null;error="Charge recordings could not be exported";}
            final File result=output;final String message=error;
            handler.post(()->{chargeExporting=false;if(destroyed){if(result!=null)result.delete();return;}
                if(message!=null)chargeStatus=message;changed();completion.accept(result);});
        });changed();return true;
    }
    private void refreshChargeCurves(){
        if(destroyed||chargeStore==null||chargeModelLoading)return;chargeModelLoading=true;
        chargeWorker.execute(()->{ChargeSessionStore.Snapshot snapshot=null;
            try{snapshot=chargeStore.snapshot();}catch(IOException ignored){}
            final ChargeSessionStore.Snapshot result=snapshot;handler.post(()->{if(destroyed)return;chargeModelLoading=false;
                if(result!=null)chargeSnapshot=result;changed();});});
    }
    private ChargeSessionStore.Frame chargeFrame(long mono,long wall){
        Map<String,ChargeSessionStore.Value> values=new HashMap<>();
        for(Telemetry.Metric metric:ble.telemetry.metrics())values.put(metric.key,new ChargeSessionStore.Value(metric.numericValue,metric.updatedMs,metric.sourceCmd));
        return new ChargeSessionStore.Frame(wall,mono,values.get("charging_state"),values.get("battery_percent"),values.get("voltage_v"),values.get("current_a"),values.get("temperature_c"));
    }
    private void updateChargeCapture(long mono,long wall){
        if(chargeUpdating||!isChargeRecording())return;chargeUpdating=true;
        try{
            ChargeSessionStore.Stop stop=null;
            if(simulating()||!settings.getBoolean("developer_options",false))stop=ChargeSessionStore.Stop.STOPPED;
            else if(!Objects.equals(chargeStore.profile(),activeProfileId()))stop=ChargeSessionStore.Stop.PROFILE_CHANGED;
            else if(ble.connected()&&(ble.profile()==null||!Objects.equals(chargeStore.profile(),ble.profile().id)))stop=ChargeSessionStore.Stop.PROFILE_CHANGED;
            else if(chargeRecovery==null)stop=ChargeSessionStore.Stop.CONNECTION_FAILED;
            else{
                ChargeSessionStore.Frame frame=chargeFrame(mono,wall);
                ScooterProfiles.Profile active=profiles.active();
                boolean pause=chargeStore.holdsSchedulePause(scheduledChargePause(),mono);
                ChargeRecoveryPolicy.Result recovery=chargeRecovery.evaluate(mono,activeProfileId(),active==null?"":active.serial,
                        ble.intentionalDisconnectSequence(),ble.permissions(),ble.bluetoothEnabled(),ble.connected(),ble.ready(),
                        ble.recoverableConnectionLoss(),ble.currentTransportSerial(),frame,pause);
                if(!applyChargeRecoveryResult(recovery)){
                    if(isChargeRecording()&&!chargeRecovery.recovering()&&(mono<chargeStore.lastFreshMono()||mono-chargeStore.lastFreshMono()>30000))
                        finishChargeCapture(ChargeSessionStore.Stop.READINGS_STOPPED);
                    return;
                }
                ChargeSessionStore.Estimate estimate=chargeRecordingEstimate();
                boolean completed=frame.usableCompletion()&&chargeStore.sampleCompletion(frame,estimate);
                if(!isChargeRecording()){chargeStatus="Charge recording reached its storage or time limit";refreshChargeCurves();applyWorkPolicy(false);return;}
                if(completed)stop=ChargeSessionStore.Stop.COMPLETE;
                else if(pause&&ChargeSessionStore.fresh(frame.state,mono)&&frame.state.value==0){
                    // Volt's own schedule holds current off until its start: wait and mark it.
                    chargeEndingMono=0;
                    if(chargeStore.paused())chargeStore.heartbeat(mono);
                    else{chargeStore.pause(wall,mono);updateNotification();}
                    if(!isChargeRecording()){chargeStatus="Charge recording reached its storage or time limit";refreshChargeCurves();applyWorkPolicy(false);return;}
                }
                else if(ChargeSessionStore.fresh(frame.state,mono)&&frame.state.value!=1){
                    if(frame.state.value==0&&ChargeSessionStore.fresh(frame.soc,mono)&&frame.soc.value>=99){
                        // Some firmware sends charging=off before the last SOC/electrical packet.
                        // Give exactly one bounded poll window to preserve the observed 99→100 bin.
                        if(chargeEndingMono==0){chargeEndingMono=mono;ble.refresh(false);}
                        else if(mono-chargeEndingMono>=5000)stop=ChargeSessionStore.Stop.CHARGING_ENDED;
                    }else stop=ChargeSessionStore.Stop.CHARGING_ENDED;
                }
                else{
                    chargeEndingMono=0;
                    if(chargeStore.paused()&&frame.usable()){chargeStore.resume(wall,mono);updateNotification();}
                    boolean sampled=chargeStore.sample(frame,estimate);
                    if(!isChargeRecording()){chargeStatus="Charge recording reached its storage or time limit";refreshChargeCurves();applyWorkPolicy(false);return;}
                    if(sampled&&frame.soc.value>=100)stop=ChargeSessionStore.Stop.COMPLETE;
                    else if(mono<chargeStore.lastFreshMono()||mono-chargeStore.lastFreshMono()>30000)stop=ChargeSessionStore.Stop.READINGS_STOPPED;
                }
            }
            if(stop!=null)finishChargeCapture(stop);
        }catch(IOException failure){finishChargeCapture(ChargeSessionStore.Stop.ERROR);}
        finally{chargeUpdating=false;}
    }
    /** Apply a policy transition without changing ownership of the one active journal. */
    private boolean applyChargeRecoveryResult(ChargeRecoveryPolicy.Result recovery){
        if(recovery.stop!=null){finishChargeCapture(recovery.stop);return false;}
        if(recovery.beganRecovery){
            chargeStore.markGap();chargeEndingMono=0;
            ble.recordDiagnostic(ConnectionEventLog.Reason.CHARGE_RECOVERY_STARTED,-1);
            updateRecordingWakeLock();updateNotification();
        }
        if(recovery.resumed){
            ble.recordDiagnostic(ConnectionEventLog.Reason.CHARGE_RECOVERY_RESUMED,-1);
            updateNotification();
        }
        return recovery.record;
    }
    private void finishChargeCapture(ChargeSessionStore.Stop reason){
        if(!isChargeRecording())return;
        boolean recovering=chargeRecovery!=null&&chargeRecovery.recovering();
        chargeEndingMono=0;chargeRecovery=null;
        ble.recordDiagnostic(reason==ChargeSessionStore.Stop.RECOVERY_TIMEOUT?ConnectionEventLog.Reason.CHARGE_RECOVERY_TIMEOUT
                :reason==ChargeSessionStore.Stop.IDENTITY_CHANGED||reason==ChargeSessionStore.Stop.PROFILE_CHANGED?ConnectionEventLog.Reason.CHARGE_WRONG_SCOOTER
                :recovering?ConnectionEventLog.Reason.CHARGE_RECOVERY_CANCELLED:ConnectionEventLog.Reason.CHARGE_STOPPED,-1);
        try{chargeStore.stop(reason);chargeStatus=reason==ChargeSessionStore.Stop.COMPLETE?"Charge recording saved · battery reached 100%"
                :reason==ChargeSessionStore.Stop.STOPPED?"Charge recording saved":reason==ChargeSessionStore.Stop.ERROR?"Charge recording stopped after a storage error"
                :"Charge recording stopped · "+reason.name().toLowerCase(Locale.ROOT).replace('_',' ');}
        catch(IOException failure){chargeStatus="Recording stopped; charge file could not be finalized";}
        // Recovery owns the reconnect attempt. Stop/timeout cancels it even when the UI or
        // ordinary background connection preference would otherwise keep retrying.
        if(recovering)ble.suspend();
        if(wakeLock!=null&&wakeLock.isHeld()&&!isRecording()&&!phoneSaving())wakeLock.release();
        if(!destroyed){refreshChargeCurves();applyWorkPolicy(false);updateNotification();}
    }
    private ChargingEstimate.Value chargingValue(Map<String,TelemetryHistory.Reading> readings,String key){
        TelemetryHistory.Reading reading=readings.get(key);
        return new ChargingEstimate.Value(reading==null?null:reading.number,reading!=null&&reading.live,reading==null?0:reading.capturedWallMs);
    }
    /** Same service calculation used by the dashboard, also evaluated with the screen off. */
    private ChargeSessionStore.Estimate chargeRecordingEstimate(){
        return ChargingEstimate.recordingSnapshot(chargingEstimate(displayReadings()));
    }
    /** Fresh paired data first; recognized models get defaults without requiring capture setup. */
    public ChargingEstimate.Result chargingEstimate(Map<String,TelemetryHistory.Reading> readings){
        ChargingEstimate.Value soc=chargingValue(readings,"battery_percent"),rated=chargingValue(readings,"rated_capacity_ah");
        ChargingEstimate.Result baseline=ChargingEstimate.calculate(chargingValue(readings,"charging_state"),chargingValue(readings,"voltage_v"),
                chargingValue(readings,"current_a"),soc,rated,chargingValue(readings,"charge_limit_percent"));
        ChargeSessionStore.Charger charger=effectiveChargeCharger();
        ChargeSessionStore.Curve curve=simulating()||chargeSnapshot==null||charger==null?null:chargeSnapshot.curves.get(ChargeSessionStore.key(activeProfileId(),charger));
        ScooterProfiles.Profile profile=profiles.active();
        if(!canRetainStockReference()){
            chargeReferenceGate.reset();
            return ChargingEstimate.forScooter(baseline,soc,rated,ModelRegistry.chargeReferenceProduct(profile==null?null:profile.savedDescriptor()),charger,curve);
        }
        // UT5 MAX reference curves only for the UT5 MAX layer; other models get live measured estimates.
        long now=SystemClock.elapsedRealtime();
        return ChargingEstimate.forScooter(baseline,soc,rated,ModelRegistry.chargeReferenceProduct(profile.savedDescriptor()),charger,curve,chargeReferenceGate,
                profile.id+":"+ble.currentTransportSerial(),chargingValue(readings,"current_a").observedMs,now,chargeProgress.progress(profile.id,now));
    }

    private ChargeScheduleController schedules(){
        if(chargeScheduleController==null)chargeScheduleController=new ChargeScheduleController(new ChargeScheduleController.Store(){
            public String read(String scope){return scope.startsWith("simulation:")?null:settings.getString("charge_schedule_owned_"+scope,null);}
            public void write(String scope,String value){if(!scope.startsWith("simulation:"))settings.edit().putString("charge_schedule_owned_"+scope,value).apply();}
        },new ChargeScheduleController.Driver(){
            public ChargeScheduleController.Context context(){return chargeScheduleContext();}
            public void clock(Consumer<Boolean> completion){ble.syncClock(completion);}
            public void time(long wallMs,int offsetSeconds,Consumer<Boolean> completion){ble.setChargeStart(wallMs/1000,offsetSeconds,completion);}
            public void enabled(boolean enabled,Consumer<Boolean> completion){ble.setChargeSchedule(enabled,completion);}
        });
        return chargeScheduleController;
    }
    /** Remembered intentions are displayed separately from raw firmware readings. */
    public ChargeScheduleController.View chargeSchedule(){return schedules().view();}
    private Consumer<String> scheduleCompletion(Consumer<String> completion){return result->{if(!destroyed){changed();if(completion!=null)completion.accept(result);}};}
    public void setChargeDoneByEnabled(boolean enabled,Consumer<String> completion){schedules().setDoneEnabled(enabled,scheduleCompletion(completion));changed();}
    public void setChargeDoneByTime(int hour,int minute,Consumer<String> completion){
        schedules().setDoneTime(hour,minute,scheduleCompletion(result->{if(result==null)retargetChargeMeasure(hour*60+minute);if(completion!=null)completion.accept(result);}));changed();
    }
    public void setManualChargeEnabled(boolean enabled,Consumer<String> completion){schedules().setManualEnabled(enabled,scheduleCompletion(completion));changed();}
    public void setManualChargeStart(long wallMs,Consumer<String> completion){schedules().setManualStart(wallMs,scheduleCompletion(completion));changed();}

    /**
     * Charge done by may first measure for up to about ten minutes, only when the pack model would
     * otherwise plan for a fresh charge because Volt did not see this one begin.
     */
    public boolean chargeMeasureOffered(){
        if(destroyed||simulating()||chargeMeasure.active()||profiles==null||profiles.active()==null||!notificationsAllowed())return false;
        ChargeScheduleController.View plan=chargeSchedule();
        if(plan.busy||plan.doneByEnabled||plan.doneByBlockedReason!=null)return false;
        ChargeProgressTracker.Progress progress=chargeProgress.progress(profiles.active().id,SystemClock.elapsedRealtime());
        if(progress==null||progress.startObserved||progress.ageInferred||!chargingEstimate(displayReadings()).stockPackModel)return false;
        Long start=cautiousChargeStart(plan.doneByMinuteOfDay);
        return start!=null&&ChargeMeasureWindow.useful(System.currentTimeMillis(),start);
    }
    /** Start of today's cautious plan; null when it would charge now or has no estimate. */
    private Long cautiousChargeStart(int minute){
        ChargeScheduleController.Context c=chargeScheduleContext();
        if(!c.ready||c.estimateMinutes==null||!Double.isFinite(c.estimateMinutes)||c.estimateMinutes<=0)return null;
        try{ChargeScheduleController.Plan plan=ChargeScheduleController.plan(c.now,minute,c.estimateMinutes,c.zone,c.wholeHours);return plan.startNow?null:plan.startWallMs;}
        catch(IllegalArgumentException outside){return null;}
    }
    public boolean isChargeMeasuring(){return chargeMeasure.active();}
    public boolean chargeMeasureArming(){return chargeMeasure.arming();}
    /** Whole minutes left, rounded up. */
    public int chargeMeasureMinutesLeft(){return (int)((chargeMeasure.remainingMs(SystemClock.elapsedRealtime())+59_999)/60_000);}
    public boolean startChargeMeasure(){
        if(!chargeMeasureOffered())return false;
        int minute=chargeSchedule().doneByMinuteOfDay;Long start=cautiousChargeStart(minute);
        return start!=null&&beginChargeMeasure(minute,start);
    }
    private boolean beginChargeMeasure(int minute,long cautiousStartWall){
        if(destroyed||chargeMeasure.active()||activeProfileId().isEmpty()||!ChargeMeasureWindow.useful(System.currentTimeMillis(),cautiousStartWall))return false;
        chargeMeasure.start(activeProfileId(),minute,SystemClock.elapsedRealtime(),System.currentTimeMillis(),cautiousStartWall);
        cancelChargeDoneNotice();cancelBackgroundDeadline();applyWorkPolicy(false);updateNotification();changed();return true;
    }
    /** The owner's Stop measuring: an explicit choice, so nothing is armed and no notice is posted. */
    public void cancelChargeMeasure(){if(!chargeMeasure.active()||chargeMeasure.arming())return;chargeMeasure.finish();endChargeMeasureWork();}
    /** The owner's Schedule now: arm with what is known, the cautious fresh-charge plan if age is not. */
    public void scheduleChargeNow(){
        if(!chargeMeasure.active()||chargeMeasure.arming())return;
        ChargeProgressTracker.Progress progress=chargeProgress.progress(chargeMeasure.profile(),SystemClock.elapsedRealtime());
        armMeasured(progress!=null&&(progress.startObserved||progress.ageInferred));
    }
    private void updateChargeMeasure(long mono){
        if(!chargeMeasure.active()||chargeMeasure.arming())return;
        ChargeScheduleController.Context c=chargeScheduleContext();
        ChargeProgressTracker.Progress progress=chargeProgress.progress(chargeMeasure.profile(),mono);
        boolean known=progress!=null&&(progress.startObserved||progress.ageInferred)&&chargeSchedule().doneByBlockedReason==null;
        ChargeMeasureWindow.Decision decision=chargeMeasure.evaluate(mono,activeProfileId(),c.ready,c.charging,known);
        if(decision.step==ChargeMeasureWindow.Step.ARM)armMeasured(decision.measured);
        else if(decision.step==ChargeMeasureWindow.Step.NOT_SET)endChargeMeasure(decision.reason);
    }
    /**
     * A finish time edited while measuring: the window can only end sooner, and a plan that must
     * already start arms at once instead of running on into the next day's deadline.
     */
    private void retargetChargeMeasure(int minute){
        if(!chargeMeasure.active()||chargeMeasure.arming())return;
        long nowMono=SystemClock.elapsedRealtime(),nowWall=System.currentTimeMillis();Long start=cautiousChargeStart(minute);
        if(start!=null&&ChargeMeasureWindow.useful(nowWall,start)){chargeMeasure.retarget(minute,nowMono,nowWall,start);updateNotification();changed();return;}
        ChargeProgressTracker.Progress progress=chargeProgress.progress(chargeMeasure.profile(),nowMono);
        armMeasured(progress!=null&&(progress.startObserved||progress.ageInferred));
    }
    /** Keeps the bounded connection until the one-shot arming has finished its scooter writes. */
    private void armMeasured(boolean measured){
        chargeMeasure.arm();updateNotification();changed();
        schedules().setDoneEnabled(true,result->{
            if(!chargeMeasure.active())return;
            ChargeScheduleController.View plan=chargeSchedule();boolean armed=result==null&&plan.doneByEnabled;
            chargeMeasure.finish();
            chargeDoneNotice(armed,armed?(measured?"Measured this charge. ":"Planned for a fresh charge. ")+"Charging resumes at "
                    +DisplayTime.clock(this,settings,plan.startWallMs)+" to finish by "+DisplayTime.clock(this,settings,plan.doneByWallMs)+"."
                    :result==null?"Charge done by is off.":result+".");
            endChargeMeasureWork();
        });
    }
    /** A window that cannot finish is never silent: the owner hears why nothing was armed. */
    private void endChargeMeasure(String reason){
        if(!chargeMeasure.active())return;
        chargeMeasure.finish();chargeDoneNotice(false,reason+". Charge done by is off.");
        if(!destroyed)endChargeMeasureWork();
    }
    private void endChargeMeasureWork(){if(destroyed)return;applyWorkPolicy(false);updateNotification();changed();}
    private boolean notificationsAllowed(){
        NotificationManager manager=getSystemService(NotificationManager.class);NotificationChannel channel=manager.getNotificationChannel("scooter");
        return manager.areNotificationsEnabled()&&(channel==null||channel.getImportance()!=NotificationManager.IMPORTANCE_NONE);
    }
    /** Separate from the foreground notice, which Android removes once measuring ends. */
    private void chargeDoneNotice(boolean set,String text){
        if(visible){android.widget.Toast.makeText(this,(set?"Charge done by is set. ":"Charge done by wasn't set. ")+text,android.widget.Toast.LENGTH_LONG).show();return;}
        try{
            notification();// Ensures the channel exists even if the foreground notice never posted.
            PendingIntent open=PendingIntent.getActivity(this,3,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            getSystemService(NotificationManager.class).notify(CHARGE_DONE_NOTICE,new Notification.Builder(this,"scooter").setSmallIcon(R.drawable.ic_volt_notification)
                    .setContentTitle(set?"Charge done by is set":"Charge done by wasn't set").setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(open).setAutoCancel(true).setCategory(Notification.CATEGORY_STATUS).setVisibility(Notification.VISIBILITY_PRIVATE).build());
        }catch(RuntimeException unavailable){}
    }
    private void cancelChargeDoneNotice(){try{getSystemService(NotificationManager.class).cancel(CHARGE_DONE_NOTICE);}catch(RuntimeException ignored){}}
    /** Volt-programmed or native charge time ahead: current is held off on purpose, not finished. */
    private boolean scheduledChargePause(){
        if(chargeScheduleController==null)return false;
        if(chargeScheduleController.busy())return true;
        ChargeScheduleController.View plan=chargeSchedule();
        return (plan.doneByEnabled||plan.manualEnabled)&&plan.startWallMs>0
                &&System.currentTimeMillis()<plan.startWallMs+ChargeScheduleController.START_GRACE_MS;
    }
    private ChargeScheduleController.Context chargeScheduleContext(){
        ChargeScheduleController.Context c=new ChargeScheduleController.Context();c.now=System.currentTimeMillis();
        if(ble==null||profiles==null)return c;
        ScooterProfiles.Profile active=profiles.active(),transport=ble.profile();
        boolean simulation=simulating();
        c.scope=active==null?"":simulation?"simulation:"+active.id+":"+ble.simulationModel()+":"+ble.simulationRegion():active.id;
        c.ready=!destroyed&&!dataMaintenance&&ble.ready()&&active!=null&&transport!=null&&active.id.equals(transport.id)
                &&(simulation||active.serial.equals(ble.currentTransportSerial()));
        c.generation=ble.connectionGeneration();
        CapabilityResolver.Decision toggle=ble.decision("timed_charging"),time=ble.decision("charge_start");
        c.toggleReason=toggle.available()?null:toggle.reasonText();c.timeReason=time.available()?null:time.reasonText();
        c.wholeHours=time.layer!=ModelRegistry.Layer.UT5_MAX;
        if(!c.ready)return c;
        c.enabled=ble.featureValue("timed_charging");
        long mono=SystemClock.elapsedRealtime();
        Map<String,Telemetry.Metric> readings=new HashMap<>();
        for(Telemetry.Metric metric:ble.telemetry.metrics())readings.put(metric.key,metric);
        Telemetry.Metric charging=readings.get("charging_state");
        Double chargingValue=freshScheduleNumber(charging,mono);
        c.charging=chargingValue!=null&&(chargingValue==0||chargingValue==1)?chargingValue.intValue():null;
        c.sampleMs=charging==null?0:charging.updatedMs;
        c.soc=freshScheduleNumber(readings.get("battery_percent"),mono);
        c.target=freshScheduleNumber(readings.get("charge_limit_percent"),mono);
        Double raw=freshScheduleNumber(readings.get("scheduled_charge_time_raw"),mono);
        if(raw!=null&&raw>=0&&raw<=0xffffffffL)c.wireStart=raw.longValue();
        if(Integer.valueOf(1).equals(c.charging)){
            ChargingEstimate.Result estimate=chargingEstimate(displayReadings());
            // Plans use the cautious estimate; the dashboard shows the most likely one.
            c.estimateMinutes=estimate.planningMinutesToLimit;
            // A raw current-rate estimate remains useful on the dashboard while automatic
            // identification gathers its first frames, but must not freeze a finish plan yet.
            if(ModelRegistry.chargeReferenceProduct(active.savedDescriptor())==Ut5MaxStockChargeReference.PRODUCT_ID
                    &&effectiveChargeCharger()==null)
                c.estimateUnavailableReason="Wait for charger detection or select a charger";
        }
        return c;
    }
    private Double freshScheduleNumber(Telemetry.Metric metric,long now){
        return metric!=null&&metric.numericValue!=null&&Double.isFinite(metric.numericValue)
                &&metric.updatedMs>0&&now>=metric.updatedMs&&now-metric.updatedMs<=10_000?metric.numericValue:null;
    }

    private void resetChargeEstimateContext(){
        chargeReferenceGate.reset();
        chargerDetection.reset();
        chargeProgress.reset();
    }

    /**
     * Observe real electrical frames once per update, even when no widget is open. A disconnect
     * or unusable frame leaves the remembered family to expire by age; renders only read it.
     * A lost link is noted so a near-full reconnect cannot keep a possibly swapped charger.
     */
    private void updateChargerDetection(){
        ScooterProfiles.Profile profile=profiles==null?null:profiles.active();
        if(destroyed||simulating()||profile==null){chargerDetection.reset();chargeProgress.reset();return;}
        if(ble!=null&&ble.currentTransportSerial().isEmpty())chargerDetection.disconnected();
        if(ble==null||!ble.ready()||ble.profile()==null||!profile.id.equals(ble.profile().id))return;
        long now=SystemClock.elapsedRealtime();
        ChargeSessionStore.Frame frame=chargeFrame(now,System.currentTimeMillis());
        if(!liveCharge(frame.state,"charging_state",now))return;
        boolean paired=liveCharge(frame.soc,"battery_percent",now)&&liveCharge(frame.voltage,"voltage_v",now)
                &&liveCharge(frame.current,"current_a",now)&&frame.voltage.mono==frame.current.mono;
        chargerDetection.observe(profile.id,ModelRegistry.chargeReferenceProduct(profile.savedDescriptor()),frame.state.value==1d,paired?frame.soc.value:null,
                paired?frame.voltage.value:null,paired?frame.current.value:null,paired?frame.current.mono:0,now);
        chargeProgress.observe(profile.id,frame.state.value==1d,paired?frame.soc.value:null,paired?frame.current.value:null,now);
    }
    private static boolean liveCharge(ChargeSessionStore.Value value,String key,long now){
        return value!=null&&value.value!=null&&Double.isFinite(value.value)&&value.mono>0&&now>=value.mono
                &&now-value.mono<=TelemetryHistory.freshnessMs(key);
    }

    /** Losing readiness or charging freshness invalidates grace even when no widget is open. */
    private boolean canRetainStockReference(){
        if(destroyed||simulating()||ble==null||!ble.ready()||profiles.active()==null||ble.profile()==null
                ||!profiles.active().id.equals(ble.profile().id))return false;
        long now=SystemClock.elapsedRealtime();
        Map<String,Telemetry.Metric> values=new HashMap<>();
        for(Telemetry.Metric metric:ble.telemetry.metrics())values.put(metric.key,metric);
        for(String key:new String[]{"charging_state","battery_percent","voltage_v","current_a","rated_capacity_ah"}){
            Telemetry.Metric metric=values.get(key);
            if(metric==null||metric.numericValue==null||!Double.isFinite(metric.numericValue)||metric.updatedMs<=0
                    ||now<metric.updatedMs||now-metric.updatedMs>TelemetryHistory.freshnessMs(key))return false;
        }
        return values.get("charging_state").numericValue==1d
                &&values.get("voltage_v").updatedMs==values.get("current_a").updatedMs;
    }

    public boolean isRecording(){return rides!=null&&rides.activeId()!=null;}
    public String recordingError(){return recordingError;}
    public boolean phoneSaving(){return phoneSavingCount>0;}
    public String phoneStatus(){
        return phoneSaving()
                ? "Saving phone sensors"
                : phoneRecorder == null
                        ? (phoneFinalStatus != null
                                ? phoneFinalStatus
                                : phoneRides == null
                                        ? "Phone sensor storage unavailable"
                                        : "Phone sensors idle")
                        : phoneRecorder.status();
    }
    public String phoneLiveReadings(){return phoneRecorder==null?"":phoneRecorder.liveMotion();}
    public boolean isVisible(){return visible;}
    public long backgroundDeadlineMs(){return backgroundDeadlineMs;}
    public boolean workAllowed(){
        if (destroyed || DataMaintenance.active()) return false;
        if (simulating()) return visible;
        return visible
                || isRecording()
                || isChargeRecording()
                || chargeMeasure.active()
                || phoneSaving()
                || settings.getBoolean("background_connection", false)
                || backgroundDeadlineMs > SystemClock.elapsedRealtime();
    }
    public void visible(boolean value){setUiVisible(value);if(running!=this)applyVisibility(value);}
    private void applyVisibility(boolean value){applyVisibility(value,true);}
    private void applyVisibility(boolean value,boolean connectOnShow){
        if(destroyed)return;boolean wasVisible=visible;visible=value;
        if(value)cancelBackgroundDeadline();
        else if(wasVisible){
            if(!isRecording()&&!isChargeRecording()&&!chargeMeasure.active()&&!settings.getBoolean("background_connection",false)){
                backgroundDeadlineMs=SystemClock.elapsedRealtime()+10000;handler.removeCallbacks(backgroundStop);handler.postDelayed(backgroundStop,10000);
            }else cancelBackgroundDeadline();
        }
        applyWorkPolicy(connectOnShow&&value&&!wasVisible);
    }
    private void cancelBackgroundDeadline(){backgroundDeadlineMs=0;handler.removeCallbacks(backgroundStop);}

    private final Runnable backgroundStop =
            new Runnable() {
                @Override
                public void run() {
                    if (destroyed) return;
                    if (visible
                            || isRecording()
                            || isChargeRecording()
                            || chargeMeasure.active()
                            || phoneSaving()
                            || settings.getBoolean("background_connection", false)) {
                        cancelBackgroundDeadline();
                        return;
                    }
                    if (backgroundDeadlineMs == 0) return;
                    long remaining = backgroundDeadlineMs - SystemClock.elapsedRealtime();
                    if (remaining > 0) {
                        handler.postDelayed(this, remaining);
                        return;
                    }
                    stopIdleWork();
                }
            };

    private void applyWorkPolicy(boolean connect){
        if(destroyed)return;
        if (simulating()) {
            cancelBackgroundDeadline();
            ble.visibility(visible, false, 0);
            handler.removeCallbacks(tick);
            workActive = visible;
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            // A visual preview has no reason to keep a foreground service or sensors alive.
            stopForeground(STOP_FOREGROUND_REMOVE); foregroundStarted = false;
            if (visible) handler.post(tick);
            changed();
            return;
        }
        if (visible
                || isRecording()
                || isChargeRecording()
                || chargeMeasure.active()
                || phoneSaving()
                || settings.getBoolean("background_connection", false)) cancelBackgroundDeadline();
        ble.visibility(visible,isRecording()||isChargeRecording()||chargeMeasure.active(),backgroundDeadlineMs);
        if(!workAllowed()){stopIdleWork();return;}
        workActive=true;startForegroundNotice(false);updateRecordingWakeLock();if(isRecording()&&phoneRecorder==null)startPhoneRecording();handler.removeCallbacks(tick);handler.post(tick);
        if (isRecording() && !ble.connected()) ble.connectLast();
        else if (connect
                && (visible || settings.getBoolean("background_connection", false))
                && settings.getBoolean("auto_connect", true)
                && ble.state() == BleController.State.DISCONNECTED) ble.connectLast();
        changed();
    }
    private void stopIdleWork(){
        workActive=false;cancelBackgroundDeadline();handler.removeCallbacks(tick);ble.visibility(visible,isRecording()||isChargeRecording()||chargeMeasure.active(),0);ble.suspend();
        if(wakeLock!=null&&wakeLock.isHeld()&&!isRecording()&&!isChargeRecording()&&!phoneSaving())wakeLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE);foregroundStarted=false;stopSelf();
    }
    public void startRide(String label){if(destroyed)return;if(isChargeRecording()){android.widget.Toast.makeText(this,"Stop charge recording before starting a ride",android.widget.Toast.LENGTH_SHORT).show();return;}if(simulating()) {
            android.widget.Toast.makeText(this, "Exit simulation first", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }if(phoneSaving()){
            android.widget.Toast.makeText(
                            this, "Finishing the previous ride", android.widget.Toast.LENGTH_SHORT)
                    .show();
            return;}if(rides==null||!ble.ready()||isRecording())return;try{rides.setCurrentDirection(RideStore.CurrentDirection.POSITIVE_IS_DRAW);
            rides.start(profiles.active().id,label,System.currentTimeMillis(),SystemClock.elapsedRealtime());invalidateCapacityEstimate();recordingError=null;gapMarked=false;samples=0;rideAutoStop=null;cancelAutoStopNotice();applyWorkPolicy(false);
            // Re-evaluate START_STICKY after the user starts a ride, rather than retaining
            // the idle service's earlier START_NOT_STICKY result.
            startService(new Intent(this,ScooterService.class).setAction("RECORDING_STARTED"));ble.refresh(false);}catch(IOException|IllegalStateException e){android.widget.Toast.makeText(this,e.getMessage(),android.widget.Toast.LENGTH_LONG).show();}}

    public void stopRide(){stopRide("");}
    /** The one stop/save path: rider, notification and automatic stops share error handling, sensor drain and wake-lock release. */
    private boolean stopRide(String reason){if(!isRecording())return false;rideAutoStop=null;try{rides.stop(System.currentTimeMillis(),SystemClock.elapsedRealtime(),reason);invalidateCapacityEstimate();recordingError=null;}catch(IOException e){
            recordingError =
                    "Storage full · free space, then Retry save";
            android.widget.Toast.makeText(this,recordingError,android.widget.Toast.LENGTH_LONG).show();updateNotification();changed();return false;}stopPhoneRecording();if(wakeLock.isHeld()&&!phoneSaving())wakeLock.release();applyWorkPolicy(false);changed();return true;}
    /**
     * Feeds the forgotten-ride policy from the current authenticated transport, never from cached
     * readings. Runs on every BLE change (so a loss is seen as it happens) and every service tick,
     * screen on or off. Charges have their own recovery policy and never pass through here.
     */
    private void observeRideAutoStop(long mono){
        if(destroyed||simulating()||!isRecording()){rideAutoStop=null;return;}
        String id=rides.activeId();
        if(rideAutoStop==null||!rideAutoStop.owns(id)){
            RideStore.Summary ride=rides.activeSummary();ScooterProfiles.Profile active=profiles.active(),bound=ble.profile();
            String serial=ride!=null&&active!=null&&active.id.equals(ride.scooterId)?active.serial:"";
            if((serial==null||serial.isEmpty())&&ride!=null&&bound!=null&&bound.id.equals(ride.scooterId))serial=ble.currentTransportSerial();
            if(ride==null||serial==null||serial.isEmpty()||ride.scooterId.isEmpty()){rideAutoStop=null;return;}
            rideAutoStop=new RideAutoStopPolicy(id,ride.scooterId,serial);rideAutoStopDisconnects=ble.intentionalDisconnectSequence();
        }
        if(!settings.getBoolean(RIDE_AUTO_STOP,true)||recordingError!=null||!rideAutoStop.profile.equals(activeProfileId())
                ||ble.intentionalDisconnectSequence()!=rideAutoStopDisconnects){
            rideAutoStop.reset();rideAutoStopDisconnects=ble.intentionalDisconnectSequence();return;
        }
        if(ble.connected()){ScooterProfiles.Profile bound=ble.profile();
            rideAutoStop.connected(mono,bound==null?"":bound.id,ble.currentTransportSerial(),ble.connectionGeneration(),ble.lastFrameMs(),ble.motionSampleMs(),ble.speedRaw(),ble.drivingStatus());}
        else rideAutoStop.disconnected(mono,ble.recoverableConnectionLoss()&&ble.permissions()&&ble.bluetoothEnabled());
    }
    /** Called from the tick only. The policy instance and ride id must both still be current. */
    private void autoStopRide(RideAutoStopPolicy policy){
        if(destroyed||policy==null||policy!=rideAutoStop||!isRecording()||!policy.owns(rides.activeId())||ble.connected())return;
        String id=policy.rideId;
        if(stopRide(RideStore.STOP_AUTO_PARKED)){
            if(visible)android.widget.Toast.makeText(this,"Ride saved automatically",android.widget.Toast.LENGTH_SHORT).show();
            showAutoStopNotice(id);
        }
    }
    /** Separate from the foreground notice, which Android removes once nothing is recording. */
    private void showAutoStopNotice(String id){
        try{
            notification();// Ensures the channel exists even if the foreground notice never posted.
            PendingIntent open=PendingIntent.getActivity(this,2,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            RideStore.Summary ride=null;try{ride=rides.get(id);}catch(IOException ignored){}
            String text=ride==null||ride.label.isEmpty()?"Bluetooth lost while parked":ride.label+" · Bluetooth lost while parked";
            getSystemService(NotificationManager.class).notify(AUTO_STOP_NOTICE,new Notification.Builder(this,"scooter").setSmallIcon(R.drawable.ic_volt_notification)
                    .setContentTitle("Ride saved automatically").setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_STATUS).setVisibility(Notification.VISIBILITY_PRIVATE).build());
        }catch(RuntimeException unavailable){}
    }
    private void cancelAutoStopNotice(){try{getSystemService(NotificationManager.class).cancel(AUTO_STOP_NOTICE);}catch(RuntimeException ignored){}}
    private void startPhoneRecording(){
        if (simulating()) return;
        if(phoneRides==null||!isRecording()||phoneRecorder!=null)return;
        boolean gps =
                settings.getBoolean("phone_gps", true)
                        && visible
                        && PhoneRideRecorder.hasLocationPermission(this);
        if(gps){phoneLocationForeground=true;try{startForegroundNotice(false);}catch(SecurityException|IllegalStateException e){phoneLocationForeground=false;gps=false;startForegroundNotice(false);}}
        phoneFinalStatus=null;phoneRecorder=new PhoneRideRecorder(this,phoneRides);
        phoneRecorder.start(
                rides.activeId(),
                settings.getString("phone_placement", "pocket"),
                gps,
                settings.getBoolean("phone_motion", true));
    }
    private void stopPhoneRecording(){if(phoneRecorder!=null){PhoneRideRecorder finishing=phoneRecorder;phoneRecorder=null;phoneSavingCount++;finishing.stop(()->handler.post(()->{phoneSavingCount=Math.max(0,phoneSavingCount-1);phoneFinalStatus=finishing.status();if(!destroyed){if(!isRecording()&&!phoneSaving()&&wakeLock.isHeld())wakeLock.release();applyWorkPolicy(false);changed();}}));}phoneLocationForeground=false;}
    private void recordPhoneTelemetry(long now){
        if (simulating()) return;
        if(phoneRecorder==null)return;Telemetry.Metric speedMetric=null,volts=null,amps=null,battery=null;
        if(ble.connected())for(Telemetry.Metric m:ble.telemetry.metrics()){
            if(m.numericValue==null||m.updatedMs<0||now<m.updatedMs||now-m.updatedMs>3000)continue;
                if (m.key.equals("speed_kmh")) speedMetric = m;
                else if (m.key.equals("voltage_v")) volts = m;
                else if (m.key.equals("current_a")) amps = m;
                else if (m.key.equals("battery_percent")) battery = m;
        }
        boolean pair=volts!=null&&amps!=null&&volts.numericValue>0&&volts.numericValue<=200&&Math.abs(amps.numericValue)<=500&&volts.updatedMs==amps.updatedMs&&volts.sourceCmd==amps.sourceCmd;
        phoneRecorder.telemetry(now,speedMetric==null?null:speedMetric.numericValue,pair?volts.numericValue*amps.numericValue:null,battery==null?null:battery.numericValue,speedMetric==null?-1:speedMetric.updatedMs,pair?volts.updatedMs:-1,battery==null?-1:battery.updatedMs);
    }
    public List<Ui.Point> trend(String key){
        if (simulating()) {
            long wall = System.currentTimeMillis(), mono = SystemClock.elapsedRealtime();
            List<Ui.Point> points = new ArrayList<>();
            for (TelemetryHistory.Point p : simulationHistory(key, wall - 120000, wall))
                points.add(new Ui.Point(p.wallMs - wall + mono, p.value, p.gapBefore));
            return points;
        }
        ArrayDeque<Ui.Point> source =
                key.equals("voltage_v") ? voltage : key.equals("speed_kmh") ? speed : power;
        return new ArrayList<>(source);}

    private final Runnable tick =
            new Runnable() {
                public void run() {
                    if (destroyed || !workActive) return;
                    if (!workAllowed()) {
                        stopIdleWork();
                        return;
                    }
                    if (simulating()) {
                        ble.tickSimulation();
                        followRangeMode();
                        if (visible) changed();
                        if (visible && workActive) handler.postDelayed(this, 1000);
                        return;
                    }
                    followRangeMode();
                    long mono = SystemClock.elapsedRealtime(), wall = System.currentTimeMillis();
                    ScooterProfiles.Profile selected = profiles.active();
                    String selectedId = selected == null ? "" : selected.id;
                    if (!selectedId.equals(trendProfile)) {
                        trendProfile = selectedId;
                        power.clear();
                        voltage.clear();
                        speed.clear();
                    }
                    if (ble.connected()) {
                        addTrend(power, "power_w", mono);
                        addTrend(voltage, "voltage_v", mono);
                        addTrend(speed, "speed_kmh", mono);
                    }
                    updateChargeCapture(mono,wall);
                    updateChargeMeasure(mono);
                    updateRecordingWakeLock();
                    if (isRecording()) {
                        recordPhoneTelemetry(mono);
                        try {
                            if (recordingError == null) {
                                if (!ble.connected()) {
                                    if (!gapMarked) {
                                        rides.markGap(wall, mono, "Bluetooth disconnected");
                                        gapMarked = true;
                                    }
                                    rides.heartbeat(wall, mono);
                                } else {
                                    gapMarked = false;
                                    Map<String, RideStore.Sample> values = new LinkedHashMap<>();
                                    for (Telemetry.Metric m : ble.telemetry.metrics())
                                        values.put(
                                                m.key,
                                                new RideStore.Sample(
                                                        m.numericValue,
                                                        m.value,
                                                        "",
                                                        m.sourceCmd,
                                                        m.updatedMs));
                                    rides.sample(wall, mono, values);
                                    if (++samples % 2 == 0) ble.refresh(false);
                                }
                            }
                        } catch (IOException e) {
                            recordingError =
                                    "Paused: storage full";
                            android.widget.Toast.makeText(
                                            ScooterService.this,
                                            recordingError,
                                            android.widget.Toast.LENGTH_LONG)
                                    .show();
                        }
                    } else if (wakeLock.isHeld() && !phoneSaving() && !isChargeRecording() && !chargeMeasure.active()) wakeLock.release();
                    // Re-read the clock: a loss seen inside this tick (a failed refresh write) was fed a later time.
                    long autoNow = SystemClock.elapsedRealtime();
                    observeRideAutoStop(autoNow);
                    RideAutoStopPolicy autoStop = rideAutoStop;
                    if (autoStop != null && autoStop.due(autoNow)) autoStopRide(autoStop);
                    if (mono - lastNotification > 5000) {
                        lastNotification = mono;
                        updateNotification();
                    }
                    if (visible || isRecording() || isChargeRecording()) changed();
                    if (workActive && workAllowed())
                        handler.postDelayed(this, visible || isRecording() || isChargeRecording() || chargeMeasure.active() ? 1000 : 15000);
                }
            };

    private void addTrend(ArrayDeque<Ui.Point> values,String key,long now){for(Telemetry.Metric m:ble.telemetry.metrics())if(m.key.equals(key)&&m.numericValue!=null&&m.updatedMs>=0&&now>=m.updatedMs&&now-m.updatedMs<2500){if(values.isEmpty()||values.peekLast().millis<m.updatedMs)values.add(new Ui.Point(m.updatedMs,m.numericValue));break;}while(!values.isEmpty()&&now-values.peek().millis>120000)values.remove();while(values.size()>240)values.remove();}
    private void changed(){if(destroyed)return;if(chargeScheduleController!=null)chargeScheduleController.observe();updateChargerDetection();if(!canRetainStockReference())chargeReferenceGate.reset();updateChargeCapture(SystemClock.elapsedRealtime(),System.currentTimeMillis());observeRideAutoStop(SystemClock.elapsedRealtime());observeTelemetry();followRangeMode();for(Runnable r:new ArrayList<>(listeners))r.run();}
    public boolean followsRidingMode() {
        return settings.getBoolean("range_follow_mode_" + activeProfileId(), true);
    }
    public void followRidingMode(boolean enabled) {
        if (destroyed) return;
        settings.edit().putBoolean("range_follow_mode_" + activeProfileId(), enabled).apply();
        rangeFollowPolicy.reset();
        changed();
    }
    private void followRangeMode() {
        if(chargeScheduleController!=null&&chargeScheduleController.busy())return;
        // A running compatibility check or guided test holds every automatic write (MutationGate refuses them too).
        if (destroyed || ble == null || selectingRange || ble.discovering() || ble.testing()) return;
        ScooterProfiles.Profile profile = profiles.active();
        Telemetry.Metric mode = null;
        for (Telemetry.Metric metric : ble.telemetry.metrics()) if (metric.key.equals("driving_mode")) { mode = metric; break; }
        long now = SystemClock.elapsedRealtime();
        ScooterProfiles.Profile bound = ble.profile();
        boolean sameProfile = profile != null && bound != null && profile.id.equals(bound.id);
        boolean fresh = sameProfile && mode != null && mode.numericValue != null && mode.updatedMs > 0
                && now >= mode.updatedMs && now - mode.updatedMs <= 7000 && ble.statusAge() <= 7000;
        Integer target = rangeFollowPolicy.next(profile == null ? "" : profile.id,
                ModelRegistry.rangeFollowProduct(profile == null ? null : profile.savedDescriptor()), mode == null ? null : mode.numericValue,
                ble.featureChoices("range_algorithm"), ble.featureValue("range_algorithm"),
                ble.connected(), ble.ready() && ble.stationary(), ble.pending() != null, fresh, followsRidingMode());
        if (target != null) {
            selectingRange = true;
            try { ble.set("range_algorithm", target, MutationGate.Origin.AUTOMATIC_RANGE_FOLLOW); } finally { selectingRange = false; }
        }
    }
    private String activeProfileId(){ScooterProfiles.Profile profile=profiles==null?null:profiles.active();
        return profile == null ? "" : profile.id;
    }

    private void ensureDisplayCache() {
        if (history == null) return;
        String id = activeProfileId();
        if (id.isEmpty()
                || clearingHistory.contains(id)
                || displayCache.containsKey(id)
                || loadingHistory.contains(id)) return;
        displayCache.put(id, TelemetryHistory.Snapshot.empty());
        loadingHistory.add(id);
        history.load(
                id,
                loaded -> {
                    loadingHistory.remove(id);
                    if (destroyed || clearingHistory.contains(id)) return;
                    TelemetryHistory.Snapshot newer = displayCache.get(id);
                    Map<String, TelemetryHistory.Reading> readings =
                            new LinkedHashMap<>(loaded.readings);
                    Map<String, Integer> features = new LinkedHashMap<>(loaded.features);
                    if (newer != null) {
                        readings.putAll(newer.readings);
                        features.putAll(newer.features);
                    }
                    displayCache.put(
                            id,
                            new TelemetryHistory.Snapshot(
                                    readings,
                                    features,
                                    Math.max(
                                            loaded.capturedWallMs,
                                            newer == null ? 0 : newer.capturedWallMs)));
                    changed();
                });
    }
    /**
     * Current values and grey last-known values are display data only. BLE decisions never consult
     * this cache.
     */
    public Map<String, TelemetryHistory.Reading> displayReadings() {
        if (simulating()) {
            Map<String, TelemetryHistory.Reading> samples = new LinkedHashMap<>();
            long wall = System.currentTimeMillis(), mono = SystemClock.elapsedRealtime();
            for (Telemetry.Metric m : ble.telemetry.metrics())
                samples.put(m.key, new TelemetryHistory.Reading(m.key, m.label, m.group,
                        m.value, TelemetryHistory.unitFor(m.key), m.numericValue,
                        Math.max(1, wall - Math.max(0, mono - m.updatedMs)), ble.connected()));
            return Collections.unmodifiableMap(samples);
        }
        observeTelemetry();
        String id = activeProfileId();
        TelemetryHistory.Snapshot snapshot = displayCache.get(id);
        Map<String, TelemetryHistory.Reading> result = new LinkedHashMap<>();
        long now = SystemClock.elapsedRealtime();
        for (Telemetry.Metric metric : ble.telemetry.metrics()) {
            TelemetryHistory.Reading saved =
                    snapshot == null ? null : snapshot.readings.get(metric.key);
            Long source = observedSources.get(metric.key);
            boolean live =
                    historyConnected
                            && id.equals(observedProfile)
                            && source != null
                            && now >= source
                            && now - source <= TelemetryHistory.freshnessMs(metric.key);
            result.put(
                    metric.key,
                    new TelemetryHistory.Reading(
                            metric.key,
                            metric.label,
                            metric.group,
                            saved == null ? null : saved.value,
                            TelemetryHistory.unitFor(metric.key),
                            saved == null ? null : saved.number,
                            saved == null ? 0 : saved.capturedWallMs,
                            live && saved != null));
        }
        return Collections.unmodifiableMap(result);
    }

    /** Local choice: never writes the scooter's firmware range mode. */
    public boolean usesLearnedRange() {
        return !activeProfileId().isEmpty() && "learned".equals(settings.getString("range_source_" + activeProfileId(), "scooter"));
    }
    public void useLearnedRange(boolean enabled) {
        if (destroyed || activeProfileId().isEmpty()) return;
        settings.edit().putString("range_source_" + activeProfileId(), enabled ? "learned" : "scooter").apply();
        changed();
    }
    /** Current forecast for glanceable UI. Recorded charts and explicit scooter readings stay raw. */
    public Map<String, TelemetryHistory.Reading> dashboardReadings() {
        Map<String, TelemetryHistory.Reading> raw = displayReadings();
        if (!usesLearnedRange() || simulating()) return raw;
        String profile = activeProfileId();
        LearnedRangeEstimate.Result estimate = currentRangeCache(profile);
        if (estimate == null && !rangePending.containsKey(profile)) {
            requestLearnedRangeEstimate(result -> { if (!destroyed && profile.equals(activeProfileId())) changed(); });
        }
        TelemetryHistory.Reading soc = raw.get("battery_percent");
        Double km = estimate == null || soc == null || soc.value == null ? null : estimate.remainingKm(soc.number);
        if (km == null) return raw;
        Map<String, TelemetryHistory.Reading> result = new LinkedHashMap<>(raw);
        result.put("remaining_range_km", new TelemetryHistory.Reading("remaining_range_km", "Learned remaining range",
                "Ride", String.format(Locale.US, "%.2f km", km), "km", km, soc.capturedWallMs, soc.live));
        return Collections.unmodifiableMap(result);
    }
    private LearnedRangeEstimate.Result currentRangeCache(String profile) {
        LearnedRangeEstimate.Result result = rangeCache.get(profile);
        // Recompute at least daily so old rides age out even without new recordings.
        long now = System.currentTimeMillis();
        // Retain failures for Home's fallback, avoiding a retry storm on every telemetry update.
        // An explicit picker request bypasses UNAVAILABLE below, and history changes clear it.
        long lifetime = 86400000L;
        return result != null && now >= result.computedWallMs && now - result.computedWallMs < lifetime ? result : null;
    }
    /** UI availability must refresh when completed or restored ride evidence changes. */
    public long learnedRangeRevision() { return capacityGeneration; }
    public void requestLearnedRangeEstimate(Consumer<LearnedRangeEstimate.Result> callback) {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post(() -> requestLearnedRangeEstimate(callback)); return; }
        String profile = activeProfileId();
        if (destroyed || dataMaintenance || simulating() || rides == null || profile.isEmpty()) {
            callback.accept(LearnedRangeEstimate.unavailable(profile, simulating() ? "Sample data" : profile.isEmpty() ? "Choose a scooter first" : "Ride storage unavailable"));
            return;
        }
        LearnedRangeEstimate.Result cached = currentRangeCache(profile);
        if (cached != null && cached.status != LearnedRangeEstimate.Status.UNAVAILABLE) { callback.accept(cached); return; }
        RangeJob waiting = rangePending.get(profile);
        if (waiting != null) { waiting.callbacks.add(callback); return; }
        RangeJob job = new RangeJob(profile); job.callbacks.add(callback); rangePending.put(profile, job);
        runRangeAnalysis(job);
    }
    private void runRangeAnalysis(RangeJob job) {
        long generation = capacityGeneration; RideStore store = rides;
        capacityWorker.execute(() -> {
            LearnedRangeEstimate.Result calculated;
            try {
                try { calculated = store.learnedRange(job.profile, System.currentTimeMillis()); }
                catch (RideStore.JournalChangedException changed) {
                    // Rename/delete/import can replace a file during replay. Retry the coherent
                    // snapshot once; a persistent error is reported, never silently called empty.
                    calculated = store.learnedRange(job.profile, System.currentTimeMillis());
                }
            } catch (IOException | RuntimeException error) {
                calculated = LearnedRangeEstimate.unavailable(job.profile, "Recorded rides could not be analyzed");
            }
            LearnedRangeEstimate.Result result = calculated;
            handler.post(() -> finishRangeAnalysis(job, generation, result));
        });
    }
    private void finishRangeAnalysis(RangeJob job, long generation, LearnedRangeEstimate.Result result) {
        // A late completion must never remove a newer request or repopulate an invalidated cache.
        if (destroyed || rangePending.get(job.profile) != job) return;
        if (generation != capacityGeneration) {
            if (!dataMaintenance && !simulating() && job.profile.equals(activeProfileId()) && job.restarts++ == 0) {
                runRangeAnalysis(job); return;
            }
            result = LearnedRangeEstimate.unavailable(job.profile, "Ride history changed; try again");
        } else {
            rangeCache.put(job.profile, result);
            while (rangeCache.size() > 16) rangeCache.remove(rangeCache.keySet().iterator().next());
        }
        rangePending.remove(job.profile);
        for (Consumer<LearnedRangeEstimate.Result> done : job.callbacks) done.accept(result);
    }

    /** Display-only analysis. It does not start recording, connect BLE, or request sensors. */
    public void requestCapacityEstimate(Double advertisedWh, Consumer<BatteryCapacityEstimate.Result> callback) {
        requestCapacityEstimate(advertisedWh, BatteryCapacityEstimate.DEFAULT_WINDOW_DAYS, callback);
    }

    public void invalidateCapacityEstimate() {
        capacityCache.clear();
        rangeCache.clear();
        capacityGeneration++;
    }

    public void requestCapacityEstimate(Double advertisedWh, int windowDays,
            Consumer<BatteryCapacityEstimate.Result> callback) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> requestCapacityEstimate(advertisedWh, windowDays, callback));
            return;
        }
        String profile = activeProfileId();
        if (simulating()) {
            callback.accept(BatteryCapacityEstimate.unavailable(profile, advertisedWh, windowDays,
                    "Sample data"));
            return;
        }
        int days = Math.max(1, Math.min(3650, windowDays));
        Double reference = advertisedWh != null && Double.isFinite(advertisedWh) && advertisedWh > 0 ? advertisedWh : null;
        if (destroyed || rides == null || profile.isEmpty()) {
            callback.accept(BatteryCapacityEstimate.unavailable(profile, reference, days,
                    profile.isEmpty() ? "No scooter set up" : "Ride storage unavailable"));
            return;
        }
        String key = profile + ":" + days + ":" + reference;
        CapacityCache cached = capacityCache.get(key);
        long now = SystemClock.elapsedRealtime();
        if (cached != null && now >= cached.mono && now - cached.mono < 10000) {
            callback.accept(cached.result);
            return;
        }
        List<Consumer<BatteryCapacityEstimate.Result>> waiting = capacityPending.get(key);
        if (waiting != null) { waiting.add(callback); return; }
        waiting = new ArrayList<>(); waiting.add(callback); capacityPending.put(key, waiting);
        long generation = capacityGeneration;
        RideStore store = rides;
        capacityWorker.execute(() -> {
            BatteryCapacityEstimate.Result calculated;
            try {
                long wall = System.currentTimeMillis();
                List<BatteryCapacityEstimate.Ride> input = new ArrayList<>();
                for (RideStore.AnalysisSnapshot snapshot : store.analysisSnapshots(profile, wall - days * 86400000L)) {
                    input.add(new BatteryCapacityEstimate.Ride(snapshot.summary, snapshot.points));
                }
                calculated = BatteryCapacityEstimate.calculate(profile, input, reference, wall, days);
            } catch (IOException | RuntimeException e) {
                calculated = BatteryCapacityEstimate.unavailable(profile, reference, days, "Recorded rides could not be analyzed");
            }
            BatteryCapacityEstimate.Result result = calculated;
            handler.post(() -> {
                List<Consumer<BatteryCapacityEstimate.Result>> callbacks = capacityPending.remove(key);
                if (destroyed) return;
                if (generation != capacityGeneration && profile.equals(activeProfileId())) {
                    if (callbacks != null) for (Consumer<BatteryCapacityEstimate.Result> done : callbacks)
                        requestCapacityEstimate(reference, days, done);
                    return;
                }
                if (generation == capacityGeneration) {
                    capacityCache.put(key, new CapacityCache(SystemClock.elapsedRealtime(), result));
                    while (capacityCache.size() > 16) capacityCache.remove(capacityCache.keySet().iterator().next());
                }
                if (callbacks != null) for (Consumer<BatteryCapacityEstimate.Result> done : callbacks) done.accept(result);
            });
        });
    }

    /** Bounded, generated chart previews; no database writes or real history queries. */
    public List<TelemetryHistory.Point> simulationHistory(String key, long from, long to) {
        if (!simulating() || to <= from) return Collections.emptyList();
        Double base = ble.telemetry.number(key);
        if (base == null || !Double.isFinite(base)) return Collections.emptyList();
        List<TelemetryHistory.Point> points = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            double fraction = i / 119d;
            double value = base;
            if (key.equals("power_w")) value += 5 * Math.sin(fraction * 24);
            else if (key.equals("voltage_v")) value += .15 * Math.sin(fraction * 18);
            else if (key.equals("temperature_c")) value += .6 * Math.sin(fraction * 8);
            else if (key.equals("battery_health_percent")) value = Math.min(100, base + 1 - fraction);
            else if (key.equals("battery_percent")) value = Math.min(100, base + 8 * (1 - fraction));
            else if (key.equals("odometer_km")) value = Math.max(0, base - 3 * (1 - fraction));
            points.add(new TelemetryHistory.Point(from + (long) ((to - (double) from) * fraction), value, false));
        }
        return points;
    }

    public Map<String, Integer> displayFeatures() {
        if (simulating()) {
            Map<String, Integer> samples = new LinkedHashMap<>();
            for (ScooterCommands.Feature f : ScooterCommands.catalog()) {
                Integer value = ble.featureValue(f.key);
                if (value != null) samples.put(f.key, value);
            }
            return Collections.unmodifiableMap(samples);
        }
        observeTelemetry();
        TelemetryHistory.Snapshot snapshot = displayCache.get(activeProfileId());
        return snapshot == null ? Collections.emptyMap() : snapshot.features;
    }

    public boolean readingFresh(String key) {
        TelemetryHistory.Reading reading = displayReadings().get(key);
        return reading != null && reading.live;
    }

    public boolean featureFresh(String key) {
        ScooterProfiles.Profile profile = ble.profile();
        return ble.ready()
                && profile != null
                && profile.id.equals(activeProfileId())
                && ble.featureValue(key) != null;
    }

    public void clearHistory(String profile, Runnable completion) {
        if (destroyed) return;
        if (history == null) {
            completion.run();
            return;
        }
        long cutoff = SystemClock.elapsedRealtime();
        Set<String> clearing = new HashSet<>();
        if (profile == null) {
            clearing.addAll(displayCache.keySet());
            for (ScooterProfiles.Profile p : profiles.all()) clearing.add(p.id);
        } else clearing.add(profile);
        for (String id : clearing) {
            historyClearCutoff.put(id, cutoff);
            clearingHistory.add(id);
            displayCache.remove(id);
        }
        if (profile == null || profile.equals(observedProfile)) {
            observedSources.clear();
            historyDirty = false;
        }
        history.clear(
                profile,
                () -> {
                    for (String id : clearing) {
                        clearingHistory.remove(id);
                        displayCache.remove(id);
                    }
                    ensureDisplayCache();
                    if (!destroyed) changed();
                    completion.run();
                });
    }

    private void observeTelemetry() {
        if (history == null || destroyed || ble == null || simulating()) return;
        ensureDisplayCache();
        String id = activeProfileId();
        ScooterProfiles.Profile connected = ble.profile();
        boolean valid =
                ble.connected() && connected != null && connected.id.equals(id) && !id.isEmpty();
        if (clearingHistory.contains(id)) return;
        if (!valid) {
            if (historyConnected) persistHistory(true);
            historyConnected = false;
            observedSources.clear();
            return;
        }
        if (!historyConnected || !id.equals(observedProfile)) {
            persistHistory(true);
            observedProfile = id;
            historyConnected = true;
            observedSources.clear();
            historySession = ++historySessionCounter;
            lastHistoryWrite = 0;
        }
        TelemetryHistory.Snapshot before = displayCache.get(id);
        Map<String, TelemetryHistory.Reading> readings = new LinkedHashMap<>();
        Map<String, Integer> features = new LinkedHashMap<>();
        if (before != null) {
            readings.putAll(before.readings);
            features.putAll(before.features);
        }
        long now = SystemClock.elapsedRealtime(),
                wall = System.currentTimeMillis(),
                cutoff = historyClearCutoff.containsKey(id) ? historyClearCutoff.get(id) : -1;
        boolean updated = false;
        if (historyMono > 0 && Math.abs((wall - historyWall) - (now - historyMono)) > 2000) {
            persistHistory(true);
            historySession = ++historySessionCounter;
        }
        historyWall = wall;
        historyMono = now;
        for (Telemetry.Metric metric : ble.telemetry.metrics()) {
            if (metric.updatedMs < 0
                    || metric.updatedMs <= cutoff
                    || metric.updatedMs > now
                    || metric.value == null) continue;
            Long seen = observedSources.get(metric.key);
            TelemetryHistory.Reading prior = readings.get(metric.key);
            if (seen != null
                    && seen == metric.updatedMs
                    && prior != null
                    && Objects.equals(prior.value, metric.value)
                    && Objects.equals(prior.number, metric.numericValue)) continue;
            observedSources.put(metric.key, metric.updatedMs);
            readings.put(
                    metric.key,
                    new TelemetryHistory.Reading(
                            metric.key,
                            metric.label,
                            metric.group,
                            metric.value,
                            TelemetryHistory.unitFor(metric.key),
                            metric.numericValue,
                            Math.max(1, wall - (now - metric.updatedMs)),
                            true));
            updated = true;
        }
        long statusAge = ble.statusAge();
        if (statusAge >= 0 && statusAge < 60000 && now - statusAge > cutoff)
            for (ScooterCommands.Feature feature : ScooterCommands.catalog())
                if (!feature.key.equals("pin")) {
                    Integer value = ble.featureValue(feature.key);
                    if (value != null && !value.equals(features.get(feature.key))) {
                        features.put(feature.key, value);
                        updated = true;
                    }
                }
        if (updated) {
            displayCache.put(id, new TelemetryHistory.Snapshot(readings, features, wall));
            historyDirty = true;
        }
        persistHistory(false);
    }

    private void persistHistory(boolean force) {
        if (simulating() || history == null || !historyDirty || observedProfile.isEmpty()) return;
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastHistoryWrite < 5000) return;
        TelemetryHistory.Snapshot snapshot = displayCache.get(observedProfile);
        if (snapshot != null) {
            Map<String, TelemetryHistory.Reading> readings = new LinkedHashMap<>();
            for (TelemetryHistory.Reading r : snapshot.readings.values()) {
                Long source = observedSources.get(r.key);
                boolean observed =
                        source != null
                                && now >= source
                                && now - source <= TelemetryHistory.freshnessMs(r.key);
                readings.put(
                        r.key,
                        new TelemetryHistory.Reading(
                                r.key,
                                r.label,
                                r.group,
                                r.value,
                                r.unit,
                                r.number,
                                r.capturedWallMs,
                                observed));
            }
            history.record(
                    observedProfile,
                    new TelemetryHistory.Snapshot(
                            readings, snapshot.features, snapshot.capturedWallMs),
                    historySession,
                    settings.getBoolean("history_enabled", true));
        }
        historyDirty = false;
        lastHistoryWrite = now;
    }

    /** Hold CPU work only for active recording/finalization, including before the first tick.
     * Renew before the timeout: an hours-long charge must not first release its lock while asleep.
     * A stalled service still has a bounded lock rather than an indefinite battery drain. */
    private void updateRecordingWakeLock(){
        if(wakeLock==null)return;
        if(!destroyed&&!simulating()&&(isRecording()||isChargeRecording()||chargeMeasure.active()||phoneSaving())){
            long now=SystemClock.elapsedRealtime();
            if(!wakeLock.isHeld()||now<wakeLockRenewedMs||now-wakeLockRenewedMs>=RECORDING_WAKE_RENEW_MS){
                wakeLock.acquire(RECORDING_WAKE_TIMEOUT_MS);wakeLockRenewedMs=now;
            }
        }else if(wakeLock.isHeld())wakeLock.release();
    }

    private void startForegroundNotice(boolean startup) {
        if ((!startup && simulating()) || !ble.permissions() || (!startup && !workAllowed())) return;
        Notification notice = notification();
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(
                    71,
                    notice,
                    (ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                            | (phoneLocationForeground
                                    ? ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                                    : 0)));
        else startForeground(71, notice);
        foregroundStarted = true;
    }

    private Notification notification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel =
                new NotificationChannel(
                        "scooter",
                        "Scooter connection & recording",
                        NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content =
                PendingIntent.getActivity(
                        this,
                        0,
                        open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        boolean measuring = chargeMeasure.active() && !isChargeRecording() && !isRecording() && !phoneSaving();
        String title =
                measuring ? "Measuring charge for Charge done by" :
                isChargeRecording() ? (chargeRecovery!=null&&chargeRecovery.recovering()?"Charge recording · reconnecting":"Charge recording is active") : phoneSaving()
                        ? "Saving ride"
                        : isRecording()
                                ? (recordingError == null
                                        ? "Ride recording is active"
                                        : "Recording needs attention")
                                : ble.ready() ? "Scooter connected" : "Volt";
        // Bodies carry live facts, not a repeat of the title: name and battery when connected,
        // distance beside a running clock while recording, a progress bar while a ride is saved.
        RideStore.Summary ride = isRecording() && recordingError == null && !isChargeRecording() && !phoneSaving() ? rides.activeSummary() : null;
        String text =
                measuring ? (chargeMeasure.arming() ? "Setting the schedule…" : "Sets the schedule within "+chargeMeasureMinutesLeft()+" min · keep the charger connected") :
                isChargeRecording() ? chargeCaptureStatus() : phoneSaving()
                        ? null
                        : isRecording()
                                ? (recordingError == null ? rideDistanceText(ride) : recordingError)
                                : ble.ready() ? connectedText() : ble.message();
        if (isRecording() && phoneStatus().startsWith("Phone sensor storage")) {
            title = "Ride recording needs attention";
            text = "Phone sensors not saving";
        }
        Notification.Builder builder =
                new Notification.Builder(this, "scooter")
                        .setSmallIcon(R.drawable.ic_volt_notification)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setContentIntent(content)
                        .setOngoing(isRecording() || isChargeRecording() || chargeMeasure.active() || phoneSaving() || ble.connected())
                        .setOnlyAlertOnce(true)
                        .setCategory(Notification.CATEGORY_SERVICE)
                        .setVisibility(Notification.VISIBILITY_PRIVATE);
        if (phoneSaving()) builder.setProgress(0, 0, true);
        if (ride != null && ride.startWallMs > 0)
            builder.setWhen(ride.startWallMs).setShowWhen(true).setUsesChronometer(true);
        if(Build.VERSION.SDK_INT>=31&&(isRecording()||isChargeRecording()||chargeMeasure.active()||phoneSaving()))
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        String action = measuring ? "SCHEDULE_CHARGE_NOW" : isChargeRecording() ? "STOP_CHARGE_RECORDING" : isRecording() ? "STOP_RECORDING" : "DISCONNECT";
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,ScooterService.class).setAction(action),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        if (!phoneSaving() && !(measuring && chargeMeasure.arming()))
            builder.addAction(
                    new Notification.Action.Builder(
                                    null, measuring ? "Schedule now" : isChargeRecording() ? "Stop charge recording" : isRecording() ? "Stop recording" : "Disconnect", stop)
                            .build());
        return builder.build();
    }
    /** "UT5 MAX · 82%": the connected notification names the scooter and its charge. */
    private String connectedText() {
        ScooterProfiles.Profile profile = profiles.active();
        Double battery = ble.telemetry.number("battery_percent");
        String name = profile == null ? "Scooter" : profile.name;
        return battery == null ? name : name + " · " + Math.round(battery) + "%";
    }
    /** Recorded distance in the rider's units, or null before the odometer has moved. */
    private String rideDistanceText(RideStore.Summary ride) {
        if (ride == null || ride.distanceKm == null) return null;
        String units = settings.getString("units", "mi");
        String km = String.format(Locale.US, "%.1f km", ride.distanceKm), mi = String.format(Locale.US, "%.1f mi", ride.distanceKm / 1.609344);
        return "km".equals(units) ? km : "dual".equals(units) ? mi + " / " + km : mi;
    }
    private void updateNotification(){if(foregroundStarted&&workAllowed())getSystemService(NotificationManager.class).notify(71,notification());}
    public String snapshot(){try{JSONObject o=new JSONObject();
            o.put("format", "navee-offline-diagnostics-v1");
            o.put("app_version", AppVersion.name(this));
            o.put("captured_at_ms", System.currentTimeMillis());
            o.put("simulated", simulating());
            o.put("connected", ble.ready());
            o.put("connection", ble.state().name());
            o.put("mtu", ble.mtu());
            o.put("connection_events_loaded", ble.diagnosticEventsLoaded());
            o.put("connection_events_storage_healthy", ble.diagnosticStorageHealthy());
            JSONArray connectionEvents = new JSONArray();
            for (ConnectionEventLog.Record event : ble.diagnosticEvents()) {
                JSONObject item = new JSONObject();
                item.put("wall_ms", event.wallMs);
                item.put("elapsed_ms", event.elapsedMs);
                item.put("reason", event.reason.name());
                item.put("status_code", event.statusCode < 0 ? JSONObject.NULL : event.statusCode);
                item.put("phase", event.phase.name());
                item.put("ui_visible", event.visible);
                item.put("recording", event.recording);
                item.put("status_age_ms", event.statusAgeMs < 0 ? JSONObject.NULL : event.statusAgeMs);
                item.put("frame_age_ms", event.frameAgeMs < 0 ? JSONObject.NULL : event.frameAgeMs);
                connectionEvents.put(item);
            }
            o.put("connection_events", connectionEvents);
            JSONArray fields=new JSONArray();long now=SystemClock.elapsedRealtime();for(Telemetry.Metric m:ble.telemetry.metrics()){JSONObject f=new JSONObject();
                f.put("group", m.group);
                f.put("key", m.key);
                f.put("label", m.label);
                f.put("value", m.value == null ? JSONObject.NULL : m.value);
                f.put("number", m.numericValue == null ? JSONObject.NULL : m.numericValue);
                f.put("age_ms", m.updatedMs < 0 ? JSONObject.NULL : now - m.updatedMs);
                f.put(
                        "source",
                        m.sourceCmd < 0
                                ? JSONObject.NULL
                                : String.format(Locale.ROOT, "0x%02X", m.sourceCmd));
                fields.put(f);}
            o.put("fields", fields);
            JSONObject raw=new JSONObject();
            for (Map.Entry<Integer, byte[]> e : ble.telemetry.frames().entrySet())
                if (!ScooterCommands.sensitiveCommand(e.getKey()))
                    raw.put(
                            String.format(Locale.ROOT, "0x%02X", e.getKey()),
                            Protocol.hex(e.getValue()));
            o.put("raw_diagnostic_payloads", raw);
            JSONObject results=new JSONObject();
            for (Map.Entry<String, String> e : ble.commandResults().entrySet())
                if (!e.getKey().equals("pin")) results.put(e.getKey(), e.getValue());
            o.put("control_results", results);
            return o.toString(2);}catch(JSONException e){
            return "{}";
        }}
    @Override public void onDestroy(){
        if(chargeScheduleController!=null)chargeScheduleController.cancel();
        resetChargeEstimateContext();
        if (guardedLifecycle) { chargeWorker.shutdown(); capacityWorker.shutdown(); super.onDestroy(); return; }
        if(chargeMeasure.active()){chargeMeasure.finish();chargeDoneNotice(false,"Volt closed while measuring. Charge done by is off.");}destroyed=true;finishChargeCapture(ChargeSessionStore.Stop.PROCESS_ENDED);chargeWorker.shutdown();workActive=false;if(running==this)running=null;if(settings!=null)settings.unregisterOnSharedPreferenceChangeListener(settingsChanged);handler.removeCallbacksAndMessages(null);capacityWorker.shutdown();capacityPending.clear();rangePending.clear();persistHistory(true);if(history!=null)history.close();stopPhoneRecording();if(isRecording())try{
                rides.markGap(
                        System.currentTimeMillis(), SystemClock.elapsedRealtime(), "Service ended");
            }catch(IOException ignored){}if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();if(ble!=null)ble.destroy();stopForeground(STOP_FOREGROUND_REMOVE);foregroundStarted=false;listeners.clear();super.onDestroy();}
}
