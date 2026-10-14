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
import java.util.function.Consumer;

/** Foreground connected-device service keeps recording alive through folds and screen-off. */
public final class ScooterService extends Service {
    private static volatile boolean uiVisible;
    private static volatile ScooterService running;
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
    private static final class CapacityCache {
        final long mono;
        final BatteryCapacityEstimate.Result result;
        CapacityCache(long mono, BatteryCapacityEstimate.Result result) { this.mono = mono; this.result = result; }
    }
    private ChargeSessionStore chargeStore;
    private ChargeSessionStore.Snapshot chargeSnapshot;
    private boolean chargeModelLoading, chargeUpdating;
    private long chargeEndingMono;
    private String chargeStatus = "No charge recording yet";
    private final ExecutorService chargeWorker = Executors.newSingleThreadExecutor(r -> new Thread(r,"charge-curve-analysis"));
    private PhoneRideRecorder phoneRecorder;private boolean phoneLocationForeground;private int phoneSavingCount;private String phoneFinalStatus;
    private PowerManager.WakeLock wakeLock;private boolean visible,foregroundStarted,gapMarked,workActive,destroyed;private int samples;private long lastNotification,backgroundDeadlineMs;
    private String trendProfile = "", recordingError;
    private final RangeFollowPolicy rangeFollowPolicy = new RangeFollowPolicy();
    private boolean selectingRange;
    private final ArrayDeque<Ui.Point> power=new ArrayDeque<>(),voltage=new ArrayDeque<>(),speed=new ArrayDeque<>();
    private final SharedPreferences.OnSharedPreferenceChangeListener settingsChanged =
            (prefs, key) -> {
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
        settings = getSharedPreferences("app_settings", MODE_PRIVATE);
        profiles=new ScooterProfiles(this);ble=new BleController(this,profiles);ble.setListener(this::changed);try{
            rides = new RideStore(new File(getFilesDir(), "rides"));
        }catch(IOException e){rides=null;}
        history=new TelemetryHistory(this);ensureDisplayCache();
        try { chargeStore=new ChargeSessionStore(new File(getFilesDir(),"charge-recordings"));refreshChargeCurves(); }
        catch(IOException unavailable){chargeStatus="Charge recording storage is unavailable";}

        try{
            phoneRides = new PhoneRideStore(new File(getFilesDir(), "phone-rides"));
        }catch(IOException ignored){}
        PowerManager pm=getSystemService(PowerManager.class);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NaveeOffline:RideRecorder");
        wakeLock.setReferenceCounted(false);
        visible=uiVisible;running=this;settings.registerOnSharedPreferenceChangeListener(settingsChanged);ble.visibility(visible,isRecording()||isChargeRecording(),backgroundDeadlineMs);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(!ble.permissions()){stopIdleWork();return START_NOT_STICKY;}
        // A queued startForegroundService still needs to fulfill Android's startup contract.
        // Current visibility is checked immediately afterwards, before any BLE work starts.
        startForegroundNotice(true);applyVisibility(uiVisible);String action=intent==null?null:intent.getAction();
        if("STOP_RECORDING".equals(action))stopRide();
        else if("STOP_CHARGE_RECORDING".equals(action))stopChargeRecording();
        else if("DISCONNECT".equals(action)){ble.disconnect();if(!isRecording()&&!isChargeRecording()&&!phoneSaving()&&!visible){stopIdleWork();return START_NOT_STICKY;}}
        applyWorkPolicy(!"DISCONNECT".equals(action)||isRecording());return isRecording()?START_STICKY:START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent){return binder;}
    public void listen(Runnable listener){listeners.add(listener);}public void unlisten(Runnable listener){listeners.remove(listener);}
    public boolean simulating() { return ble != null && ble.simulating(); }

    /** Runtime-only preview: actual caches and journals never receive sample readings. */
    public boolean setSimulation(boolean enabled) {
        if (destroyed || ble == null) return false;
        if (enabled == simulating()) return true;
        if (enabled && (isRecording() || isChargeRecording() || phoneSaving())) {
            android.widget.Toast.makeText(this, "Finish the current recording before simulating", android.widget.Toast.LENGTH_LONG).show();
            return false;
        }
        if (enabled && (profiles.active() == null || !settings.getBoolean("developer_options", false))) return false;
        // Flush only genuine data before the BLE layer closes its current connection.
        if (enabled) { observeTelemetry(); persistHistory(true); }
        historyConnected = false; historyDirty = false; observedSources.clear();
        power.clear(); voltage.clear(); speed.clear();
        invalidateCapacityEstimate();
        ble.setSimulation(enabled);
        cancelBackgroundDeadline();
        applyWorkPolicy(false);
        changed();
        return simulating() == enabled;
    }

    public boolean isChargeRecording(){return chargeStore!=null&&chargeStore.active();}
    private String chargerKey(){return "charge_charger_"+activeProfileId();}
    public ChargeSessionStore.Charger chargeCharger(){
        try{return ChargeSessionStore.Charger.valueOf(settings.getString(chargerKey(),""));}catch(IllegalArgumentException invalid){return null;}
    }
    public boolean setChargeCharger(ChargeSessionStore.Charger charger){
        if(charger==null||isChargeRecording()||activeProfileId().isEmpty())return false;
        settings.edit().putString(chargerKey(),charger.name()).apply();changed();return true;
    }
    public String chargeCaptureUnavailable(){
        if(destroyed||chargeStore==null)return "Charge recording storage is unavailable";
        if(!settings.getBoolean("developer_options",false))return "Enable developer options first";
        if(simulating())return "Turn off simulation to record a real charge";
        if(isRecording()||phoneSaving())return "Finish the ride recording first";
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
            chargeStore.start(activeProfileId(),charger,wall,mono);chargeEndingMono=0;setChargeChargerForCapture(charger);
            chargeStore.sample(chargeFrame(mono,wall));chargeStatus="Charge recording started";
            cancelBackgroundDeadline();applyWorkPolicy(false);updateNotification();changed();return isChargeRecording();
        }catch(IOException|IllegalStateException failure){chargeStatus="Could not start charge recording";finishChargeCapture(ChargeSessionStore.Stop.ERROR);changed();return false;}
    }
    private void setChargeChargerForCapture(ChargeSessionStore.Charger charger){settings.edit().putString(chargerKey(),charger.name()).apply();}
    public void stopChargeRecording(){finishChargeCapture(ChargeSessionStore.Stop.STOPPED);}
    public String chargeCaptureStatus(){
        if(!isChargeRecording())return chargeStatus;
        return (chargeStore.charger()==ChargeSessionStore.Charger.STOCK?"Stock charger":"High-speed charger")+" · "+chargeStore.sampleCount()+" local samples";
    }
    public String chargeLearningSummary(){
        if(chargeModelLoading)return "Loading saved charge recordings";
        ChargeSessionStore.Charger charger=chargeCharger();
        ChargeSessionStore.Curve curve=chargeSnapshot==null||charger==null?null:chargeSnapshot.curves.get(ChargeSessionStore.key(activeProfileId(),charger));
        int qualified=curve==null?0:curve.qualifiedSessions;
        return qualified+" qualified "+(charger==null?"selected-charger":charger==ChargeSessionStore.Charger.HIGH_SPEED?"high-speed":"stock")+" charge"+(qualified==1?"":"s")
                +" · "+(chargeSnapshot==null?0:chargeSnapshot.sessions)+" recordings stored";
    }
    public boolean clearChargeRecordings(Runnable completion){
        if(chargeStore==null||isChargeRecording()||chargeModelLoading)return false;
        final String profile=activeProfileId();if(profile.isEmpty())return false;chargeModelLoading=true;
        chargeWorker.execute(()->{String error=null;ChargeSessionStore.Snapshot snapshot=null;
            try{chargeStore.clear(profile);snapshot=chargeStore.snapshot();}catch(IOException failure){error="Charge recordings could not be cleared";}
            final String message=error;final ChargeSessionStore.Snapshot result=snapshot;
            handler.post(()->{if(destroyed)return;chargeModelLoading=false;if(result!=null)chargeSnapshot=result;
                chargeStatus=message==null?"Charge recordings cleared":message;changed();if(completion!=null)completion.run();});});return true;
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
            else if(!ble.connected())stop=ChargeSessionStore.Stop.DISCONNECTED;
            else{
                ChargeSessionStore.Frame frame=chargeFrame(mono,wall);
                boolean completed=frame.usableCompletion()&&chargeStore.sampleCompletion(frame);
                if(!isChargeRecording()){chargeStatus="Charge recording reached its storage or time limit";refreshChargeCurves();applyWorkPolicy(false);return;}
                if(completed)stop=ChargeSessionStore.Stop.COMPLETE;
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
                    boolean sampled=chargeStore.sample(frame);
                    if(!isChargeRecording()){chargeStatus="Charge recording reached its storage or time limit";refreshChargeCurves();applyWorkPolicy(false);return;}
                    if(sampled&&frame.soc.value>=100)stop=ChargeSessionStore.Stop.COMPLETE;
                    else if(mono<chargeStore.lastFreshMono()||mono-chargeStore.lastFreshMono()>30000)stop=ChargeSessionStore.Stop.READINGS_STOPPED;
                }
            }
            if(stop!=null)finishChargeCapture(stop);
        }catch(IOException failure){finishChargeCapture(ChargeSessionStore.Stop.ERROR);}
        finally{chargeUpdating=false;}
    }
    private void finishChargeCapture(ChargeSessionStore.Stop reason){
        if(!isChargeRecording())return;
        chargeEndingMono=0;
        try{chargeStore.stop(reason);chargeStatus=reason==ChargeSessionStore.Stop.COMPLETE?"Charge recording saved · battery reached 100%"
                :reason==ChargeSessionStore.Stop.STOPPED?"Charge recording saved":reason==ChargeSessionStore.Stop.ERROR?"Charge recording stopped after a storage error"
                :"Charge recording stopped · "+reason.name().toLowerCase(Locale.ROOT).replace('_',' ');}
        catch(IOException failure){chargeStatus="Recording stopped; charge file could not be finalized";}
        if(wakeLock!=null&&wakeLock.isHeld()&&!isRecording()&&!phoneSaving())wakeLock.release();
        if(!destroyed){refreshChargeCurves();applyWorkPolicy(false);updateNotification();}
    }
    private ChargingEstimate.Value chargingValue(Map<String,TelemetryHistory.Reading> readings,String key){
        TelemetryHistory.Reading reading=readings.get(key);
        return new ChargingEstimate.Value(reading==null?null:reading.number,reading!=null&&reading.live,reading==null?0:reading.capturedWallMs);
    }
    /** Always retains the existing fresh/paired gate before consulting this profile's selected charger. */
    public ChargingEstimate.Result chargingEstimate(Map<String,TelemetryHistory.Reading> readings){
        ChargingEstimate.Value soc=chargingValue(readings,"battery_percent");
        ChargingEstimate.Result baseline=ChargingEstimate.calculate(chargingValue(readings,"charging_state"),chargingValue(readings,"voltage_v"),
                chargingValue(readings,"current_a"),soc,chargingValue(readings,"rated_capacity_ah"),chargingValue(readings,"charge_limit_percent"));
        ChargeSessionStore.Charger charger=chargeCharger();
        ChargeSessionStore.Curve curve=simulating()||chargeSnapshot==null||charger==null?null:chargeSnapshot.curves.get(ChargeSessionStore.key(activeProfileId(),charger));
        return ChargingEstimate.withObservedCurve(baseline,soc,curve);
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
        if (simulating()) return visible;
        return visible
                || isRecording()
                || isChargeRecording()
                || phoneSaving()
                || settings.getBoolean("background_connection", false)
                || backgroundDeadlineMs > SystemClock.elapsedRealtime();
    }
    public void visible(boolean value){setUiVisible(value);if(running!=this)applyVisibility(value);}
    private void applyVisibility(boolean value){
        if(destroyed)return;boolean wasVisible=visible;visible=value;
        if(value)cancelBackgroundDeadline();
        else if(wasVisible){
            if(!isRecording()&&!isChargeRecording()&&!settings.getBoolean("background_connection",false)){
                backgroundDeadlineMs=SystemClock.elapsedRealtime()+10000;handler.removeCallbacks(backgroundStop);handler.postDelayed(backgroundStop,10000);
            }else cancelBackgroundDeadline();
        }
        applyWorkPolicy(value&&!wasVisible);
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
                || phoneSaving()
                || settings.getBoolean("background_connection", false)) cancelBackgroundDeadline();
        ble.visibility(visible,isRecording()||isChargeRecording(),backgroundDeadlineMs);
        if(!workAllowed()){stopIdleWork();return;}
        workActive=true;startForegroundNotice(false);if(isRecording()&&phoneRecorder==null)startPhoneRecording();handler.removeCallbacks(tick);handler.post(tick);
        if (isRecording() && !ble.connected()) ble.connectLast();
        else if (connect
                && (visible || settings.getBoolean("background_connection", false))
                && settings.getBoolean("auto_connect", true)
                && ble.state() == BleController.State.DISCONNECTED) ble.connectLast();
        changed();
    }
    private void stopIdleWork(){
        workActive=false;cancelBackgroundDeadline();handler.removeCallbacks(tick);ble.visibility(visible,isRecording()||isChargeRecording(),0);ble.suspend();
        if(wakeLock!=null&&wakeLock.isHeld()&&!isRecording()&&!isChargeRecording()&&!phoneSaving())wakeLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE);foregroundStarted=false;stopSelf();
    }
    public void startRide(String label){if(isChargeRecording()){android.widget.Toast.makeText(this,"Stop charge recording before starting a ride",android.widget.Toast.LENGTH_SHORT).show();return;}if(simulating()) {
            android.widget.Toast.makeText(this, "Turn off simulation to record a real ride", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }if(phoneSaving()){
            android.widget.Toast.makeText(
                            this, "Finishing the previous ride", android.widget.Toast.LENGTH_SHORT)
                    .show();
            return;}if(rides==null||!ble.ready()||isRecording())return;try{rides.setCurrentDirection(RideStore.CurrentDirection.POSITIVE_IS_DRAW);
            rides.start(profiles.active().id,label,System.currentTimeMillis(),SystemClock.elapsedRealtime());invalidateCapacityEstimate();recordingError=null;gapMarked=false;samples=0;applyWorkPolicy(false);ble.refresh(false);}catch(IOException|IllegalStateException e){android.widget.Toast.makeText(this,e.getMessage(),android.widget.Toast.LENGTH_LONG).show();}}
    public void stopRide(){if(!isRecording())return;try{rides.stop(System.currentTimeMillis(),SystemClock.elapsedRealtime());invalidateCapacityEstimate();recordingError=null;}catch(IOException e){
            recordingError =
                    "Recording could not be saved. Free some phone storage, then try Stop again.";
            android.widget.Toast.makeText(this,recordingError,android.widget.Toast.LENGTH_LONG).show();changed();return;}stopPhoneRecording();if(wakeLock.isHeld()&&!phoneSaving())wakeLock.release();applyWorkPolicy(false);changed();}
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
                    if (isChargeRecording() && !wakeLock.isHeld()) wakeLock.acquire(12 * 60 * 60 * 1000L);
                    if (isRecording()) {
                        if (!wakeLock.isHeld()) wakeLock.acquire(12 * 60 * 60 * 1000L);
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
                                    "Recording paused because phone storage could not be written."
                                        + " Free some space, then stop to save the session.";
                            android.widget.Toast.makeText(
                                            ScooterService.this,
                                            recordingError,
                                            android.widget.Toast.LENGTH_LONG)
                                    .show();
                        }
                    } else if (wakeLock.isHeld() && !phoneSaving() && !isChargeRecording()) wakeLock.release();
                    if (mono - lastNotification > 5000) {
                        lastNotification = mono;
                        updateNotification();
                    }
                    if (visible || isRecording() || isChargeRecording()) changed();
                    if (workActive && workAllowed())
                        handler.postDelayed(this, visible || isRecording() || isChargeRecording() ? 1000 : 15000);
                }
            };

    private void addTrend(ArrayDeque<Ui.Point> values,String key,long now){for(Telemetry.Metric m:ble.telemetry.metrics())if(m.key.equals(key)&&m.numericValue!=null&&m.updatedMs>=0&&now>=m.updatedMs&&now-m.updatedMs<2500){if(values.isEmpty()||values.peekLast().millis<m.updatedMs)values.add(new Ui.Point(m.updatedMs,m.numericValue));break;}while(!values.isEmpty()&&now-values.peek().millis>120000)values.remove();while(values.size()>240)values.remove();}
    private void changed(){if(!destroyed)updateChargeCapture(SystemClock.elapsedRealtime(),System.currentTimeMillis());observeTelemetry();followRangeMode();for(Runnable r:new ArrayList<>(listeners))r.run();}
    public boolean followsRidingMode() {
        return settings.getBoolean("range_follow_mode_" + activeProfileId(), true);
    }
    public void followRidingMode(boolean enabled) {
        settings.edit().putBoolean("range_follow_mode_" + activeProfileId(), enabled).apply();
        rangeFollowPolicy.reset();
        changed();
    }
    private void followRangeMode() {
        if (destroyed || ble == null || selectingRange) return;
        ScooterProfiles.Profile profile = profiles.active();
        Telemetry.Metric mode = null;
        for (Telemetry.Metric metric : ble.telemetry.metrics()) if (metric.key.equals("driving_mode")) { mode = metric; break; }
        long now = SystemClock.elapsedRealtime();
        ScooterProfiles.Profile bound = ble.profile();
        boolean sameProfile = profile != null && bound != null && profile.id.equals(bound.id);
        boolean fresh = sameProfile && mode != null && mode.numericValue != null && mode.updatedMs > 0
                && now >= mode.updatedMs && now - mode.updatedMs <= 7000 && ble.statusAge() <= 7000;
        Integer target = rangeFollowPolicy.next(profile == null ? "" : profile.id,
                profile == null ? 0 : profile.pid(), mode == null ? null : mode.numericValue,
                ble.featureChoices("range_algorithm"), ble.featureValue("range_algorithm"),
                ble.connected(), ble.ready() && ble.stationary(), ble.pending() != null, fresh, followsRidingMode());
        if (target != null) {
            selectingRange = true;
            try { ble.set("range_algorithm", target); } finally { selectingRange = false; }
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

    /** Display-only analysis. It does not start recording, connect BLE, or request sensors. */
    public void requestCapacityEstimate(Double advertisedWh, Consumer<BatteryCapacityEstimate.Result> callback) {
        requestCapacityEstimate(advertisedWh, BatteryCapacityEstimate.DEFAULT_WINDOW_DAYS, callback);
    }

    public void invalidateCapacityEstimate() {
        capacityCache.clear();
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
                    "Simulation uses sample data; real ride analysis is paused"));
            return;
        }
        int days = Math.max(1, Math.min(3650, windowDays));
        Double reference = advertisedWh != null && Double.isFinite(advertisedWh) && advertisedWh > 0 ? advertisedWh : null;
        if (destroyed || rides == null || profile.isEmpty()) {
            callback.accept(BatteryCapacityEstimate.unavailable(profile, reference, days,
                    profile.isEmpty() ? "Choose a scooter to analyze recorded rides" : "Ride storage is unavailable"));
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
        String title =
                isChargeRecording() ? "Charge recording is active" : phoneSaving()
                        ? "Saving ride"
                        : isRecording()
                                ? (recordingError == null
                                        ? "Ride recording is active"
                                        : "Recording needs attention")
                                : ble.ready() ? "Navee connected offline" : "Navee Offline";
        String text =
                isChargeRecording() ? chargeCaptureStatus() : phoneSaving()
                        ? "Finishing the last phone sensor measurements."
                        : isRecording()
                                ? (recordingError == null
                                        ? "Scooter and phone ride data are being saved locally."
                                        : recordingError)
                                : ble.message();
        if (isRecording() && phoneStatus().startsWith("Phone sensor storage")) {
            title = "Ride recording needs attention";
            text = "Scooter data continues. Phone sensor storage is unavailable.";
        }
        Notification.Builder builder =
                new Notification.Builder(this, "scooter")
                        .setSmallIcon(R.drawable.ic_lock)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setContentIntent(content)
                        .setOngoing(isRecording() || isChargeRecording() || phoneSaving() || ble.connected())
                        .setOnlyAlertOnce(true)
                        .setVisibility(Notification.VISIBILITY_PRIVATE);
        String action = isChargeRecording() ? "STOP_CHARGE_RECORDING" : isRecording() ? "STOP_RECORDING" : "DISCONNECT";
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,ScooterService.class).setAction(action),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        if (!phoneSaving())
            builder.addAction(
                    new Notification.Action.Builder(
                                    null, isChargeRecording() ? "Stop charge recording" : isRecording() ? "Stop recording" : "Disconnect", stop)
                            .build());
        return builder.build();
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
    @Override public void onDestroy(){destroyed=true;finishChargeCapture(ChargeSessionStore.Stop.PROCESS_ENDED);chargeWorker.shutdown();workActive=false;if(running==this)running=null;settings.unregisterOnSharedPreferenceChangeListener(settingsChanged);handler.removeCallbacksAndMessages(null);capacityWorker.shutdown();capacityPending.clear();persistHistory(true);if(history!=null)history.close();stopPhoneRecording();if(isRecording())try{
                rides.markGap(
                        System.currentTimeMillis(), SystemClock.elapsedRealtime(), "Service ended");
            }catch(IOException ignored){}if(wakeLock.isHeld())wakeLock.release();ble.destroy();stopForeground(STOP_FOREGROUND_REMOVE);foregroundStarted=false;listeners.clear();super.onDestroy();}
}
