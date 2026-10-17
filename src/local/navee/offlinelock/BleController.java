package local.navee.offlinelock;

import android.Manifest;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.*;
import java.nio.charset.StandardCharsets;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;

/** One serialized BLE session. Activity/fold changes never own or recreate the connection. */
public final class BleController {
    public interface Listener {void changed();}
    public enum State {DISCONNECTED,SCANNING,CONNECTING,AUTHENTICATING,READY,WAITING}
    public static final UUID SERVICE=UUID.fromString("0000d0ff-3c17-d293-8e48-14fe2e4da212"),WRITE=UUID.fromString("0000b002-0000-1000-8000-00805f9b34fb"),NOTIFY=UUID.fromString("0000b003-0000-1000-8000-00805f9b34fb"),CCCD=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    public final Telemetry telemetry=new Telemetry();
    private final Context context;private final ScooterProfiles profiles;private final Handler handler=new Handler(Looper.getMainLooper());private final BluetoothAdapter adapter;
    private final Protocol.Decoder decoder=new Protocol.Decoder();private final ArrayDeque<byte[]> queue=new ArrayDeque<>();private final ArrayList<String> events=new ArrayList<>();
    private final LinkedHashMap<String,ScanResult> discoveries=new LinkedHashMap<>();private final LinkedHashMap<String,String> commandResults=new LinkedHashMap<>();
    private final HashMap<String,Long> resultTimes=new HashMap<>();private String resultsProfileId;
    /** Short connection states shared by the connection dialog, onboarding and the notification. */
    public static final String NEARBY_NEEDED="Nearby devices permission needed",BLUETOOTH_OFF="Bluetooth off",NO_PROFILE="No scooter set up",
            NOTHING_FOUND="No scooter found",OWNER_REJECTED="Owner identity rejected",LIMITED_MODEL="Limited controls on this model";
    private ScooterSimulation simulation;private boolean simulationChanging;private String simulationModel="";
    /** Advertised model IDs seen in this scan, by MAC. One is committed to a profile only after authentication and a serial match on that MAC. */
    private final LinkedHashMap<String,AdvertisedIdentity.Result> advertised=new LinkedHashMap<>();
    private AdvertisedIdentity.Result pendingAdvertised;private String pendingAdvertisedMac="";private boolean identifying;
    /** Session 0x73 firmware for descriptor(); null = recompute from telemetry. */
    private DeviceDescriptor.Firmware sessionFirmware;
    /** bonded() is a Bluetooth service call; decisions reuse it for up to a second (setProximity re-checks the live bond). */
    private boolean bondCache;private long bondCheckedMs;
    /** Which reads this session sends (UT5 MAX: today's arrays). Reset on every connection. */
    private ReadPolicy readPolicy;
    /** Authentication handshake variant of this session, for the compatibility report; "" until authenticated. */
    private String authVariant="";
    /** The last compatibility check and its snapshot; never persisted. */
    private DiscoveryCoordinator discovery;private boolean discovering;private String discoveryProfileId;
    private DeviceDescriptor discoveryDescriptor;private StatusFields discoveryStatus;private List<CapabilityResolver.Decision> discoveryDecisions;private String discoveryAuth;
    private final ConnectionEventLog diagnostics;private long intentionalDisconnectSequence;private boolean recoverableConnectionLoss;
    private Listener listener;private BluetoothGatt gatt;private BluetoothGattCharacteristic writer;private BluetoothLeScanner scanner;private ScooterProfiles.Profile profile;
    private State state=State.DISCONNECTED;private String message="Not connected",serial="";private boolean authenticated,wanted,scanning,writing,mtuRequested,discovered,subscribing,subscribed,challengePending,visible,recording;
    private int generation,writeOperation,authRounds,retries,pollTicks,mtu=23;private long statusMs,lastFrameMs,motionMs,warningMs;private int speedRaw=-1,driving=-1,warning;
    private Protocol.Frame statusFrame;private Mutation mutation;private ScooterCommands.PinState pinState;private long pinStateMs,mutationSequence,pinReadToken,pinReadSequence;private boolean pinReadPending;
    private long savedProfileMs,lastBatteryPollMs,scanSequence,backgroundDeadlineMs;private ScanCallback scanCallback;private Runnable scanTimeout;
    private static final class Mutation {String key;int cmd,target;long token;boolean ack,sent;Long time;byte[] bytes;char[] desiredPin;MutationGate.Request request;FeatureVariant variant;Mutation(String k,int c,int v,long t){key=k;cmd=c;target=v;token=t;}}
    public BleController(Context c,ScooterProfiles p){context=c.getApplicationContext();profiles=p;diagnostics=ConnectionEventLog.open(new File(context.getNoBackupFilesDir(),"connection-events"));BluetoothManager m=context.getSystemService(BluetoothManager.class);adapter=m==null?null:m.getAdapter();IntentFilter filter=new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);if(Build.VERSION.SDK_INT>=33)context.registerReceiver(bluetoothReceiver,filter,Context.RECEIVER_EXPORTED);else context.registerReceiver(bluetoothReceiver,filter);}
    public void setListener(Listener l){listener=l;}
    /** Runtime only. The transition guard also prevents listeners from persisting display samples. */
    public boolean simulating(){return simulationChanging||simulation!=null;}
    public void setSimulation(boolean enabled){
        if(simulationChanging||enabled==(simulation!=null))return;
        simulationChanging=true;
        // Cancel pending real operations before any sample can be displayed. In-flight
        // callbacks also lose their generation/GATT identity when close() runs.
        wanted=false;recoverableConnectionLoss=false;handler.removeCallbacksAndMessages(null);clearMutation();
        close(State.DISCONNECTED);clearDiscovery();telemetry.clear();profile=null;scanner=null;discoveries.clear();advertised.clear();commandResults.clear();
        simulation=enabled?new ScooterSimulation():null;if(simulation!=null)simulation.setModel(simulationModel);
        state=enabled?State.READY:State.DISCONNECTED;
        if(enabled)updateSimulation();
        simulationChanging=false;
        notice(enabled?"Simulation on":"Simulation off");
    }
    /** Called by the visible UI's existing timer; no simulator background loop is created. */
    public void tickSimulation(){if(simulation==null||state!=State.READY)return;updateSimulation();}
    private void updateSimulation(){
        if(simulation==null)return;
        long time=Math.max(1,now());simulation.tick(telemetry,time);
        statusMs=lastFrameMs=motionMs=warningMs=time;speedRaw=driving=warning=0;serial="SIMULATION";
    }
    public Map<String,Integer> simulationFeatures(){return simulation==null?Collections.emptyMap():simulation.features();}
    public boolean simulationCharging(){return simulation!=null&&simulation.charging();}
    /** Developer simulator model: "" (UT5 MAX) or a synthetic advertised ID. Memory only; never reaches a profile. */
    public String simulationModel(){return simulationModel;}
    public void setSimulationModel(String pid){simulationModel=pid==null?"":pid;if(simulation!=null){simulation.setModel(simulationModel);tickSimulation();}changed();}
    public void setSimulationCharging(boolean charging){if(simulation==null)return;simulation.setCharging(charging);tickSimulation();changed();}
    private void connectSimulation(){if(simulation==null)return;state=State.READY;updateSimulation();notice("Simulated connection");}
    /** Simulation changes need no caption or toast: the persistent banner already says the data is simulated. */
    private void quiet(){message="";changed();}
    private void simulationUnavailable(String action){notice(action+" is unavailable in simulation. No scooter command was sent.");}
    public State state(){return state;}public String message(){return message;}public boolean ready(){if(simulating())return simulation!=null&&state==State.READY;return workAllowed()&&state==State.READY&&authenticated&&gatt!=null&&statusMs>0&&now()-statusMs<=7000&&!serial.isEmpty();}
    public boolean connected(){return simulating()?simulation!=null&&state==State.READY:gatt!=null&&authenticated;}public Boolean locked(){Integer n=featureValue("lock");return ready()&&n!=null?(n!=0):null;}
    public String pending(){return mutation==null?null:mutation.key;}public int mtu(){return mtu;}public long statusAge(){return statusMs==0?Long.MAX_VALUE:now()-statusMs;}public long lastFrameAge(){return lastFrameMs==0?Long.MAX_VALUE:now()-lastFrameMs;}
    /** User disconnect is distinct from a transport loss, even while a retry is already pending. */
    public long intentionalDisconnectSequence(){return intentionalDisconnectSequence;}
    public boolean recoverableConnectionLoss(){return recoverableConnectionLoss;}
    /** Never returns the saved profile serial: recovery must verify this authenticated transport. */
    public String currentTransportSerial(){return !simulating()&&gatt!=null&&authenticated?serial:"";}
    public List<ConnectionEventLog.Record> diagnosticEvents(){return diagnostics.snapshot();}
    public boolean diagnosticEventsLoaded(){return diagnostics.loaded();}
    public boolean diagnosticStorageHealthy(){return diagnostics.storageHealthy();}
    public void recordDiagnostic(ConnectionEventLog.Reason reason,int code){
        if(simulating())return;
        long elapsed=now();
        diagnostics.record(new ConnectionEventLog.Record(System.currentTimeMillis(),elapsed,reason,code,
                visible,recording,ConnectionEventLog.Phase.valueOf(state.name()),
                statusMs<=0?-1:Math.max(0,elapsed-statusMs),lastFrameMs<=0?-1:Math.max(0,elapsed-lastFrameMs)));
    }
    public boolean stationary(){if(simulating())return simulation!=null&&state==State.READY;return motionMs>0&&now()>=motionMs&&now()-motionMs<6000&&speedRaw==0&&driving==0;}
    public ScooterProfiles.Profile profile(){return profile==null?profiles.active():profile;}public List<ScanResult> discoveries(){return new ArrayList<>(discoveries.values());}public List<String> events(){return new ArrayList<>(events);}public Map<String,String> commandResults(){return new LinkedHashMap<>(commandResults);}
    /** Wall time of a key's latest result, or 0. */
    public long commandResultTime(String key){Long time=resultTimes.get(key);return time==null?0:time;}
    public ScooterCommands.PinState pinState(){return pinState;}
    /** Model rules for this scooter (simulated model while simulating). */
    public ModelRegistry.Resolution resolution(){return ModelRegistry.resolve(descriptor());}
    /** Connected to a model outside the exact UT5 MAX layer: some controls are hidden or unavailable. */
    public boolean limitedModel(){return ready()&&resolution().layer!=ModelRegistry.Layer.UT5_MAX;}
    /** No advertised model ID yet (and no conflict): Identify can capture it. */
    public boolean modelUnidentified(){DeviceDescriptor d=descriptor();ModelRegistry.Resolution r=ModelRegistry.resolve(d);return r.layer==ModelRegistry.Layer.SHARED&&!r.conflict&&d.pidSource()!=DeviceDescriptor.PidSource.ADVERTISED;}
    /**
     * Whether the UI shows this control for the scooter's model: a known encoding, or no identity
     * evidence yet (a profile that has never connected). Availability is still decision(key).
     */
    public boolean controlOffered(String key){
        CapabilityResolver.Decision d=decision(key);
        return d.support==CapabilityResolver.Support.KNOWN_ENCODING||!simulating()&&descriptor().pidSource()==DeviceDescriptor.PidSource.NONE;
    }
    /** The decision the UI shows for a control; the same resolver admits every write. */
    public CapabilityResolver.Decision decision(String key){return duringDiscovery(CapabilityResolver.decide(key,descriptor(),status(),runtime(key)));}
    /** Value-specific decision (lock direction): unlock follows the recovery contract. */
    public CapabilityResolver.Decision decision(String key,int value){return duringDiscovery(CapabilityResolver.admit(key,value,descriptor(),status(),runtime(key)));}
    private StatusFields status(){return StatusFields.of(statusFrame,statusMs);}
    /** A lock change stops a running compatibility check first, so only other features see it as active. */
    private CapabilityResolver.Runtime runtime(String feature){
        CapabilityResolver.Runtime rt=new CapabilityResolver.Runtime();long now=now();ScooterProfiles.Profile p=profile();
        rt.discovery=discovering&&!"lock".equals(feature);
        rt.simulation=simulating();rt.ready=ready();rt.busy=mutation!=null;rt.stationary=stationary();rt.motionSeen=rt.simulation||motionMs>0;
        rt.warningActive=warning!=0&&now-warningMs<6000;rt.owner=p!=null&&p.role==0;
        if(bondCheckedMs==0||now-bondCheckedMs>1000){bondCache=bonded();bondCheckedMs=now;}rt.bonded=bondCache;
        rt.pinEnabledFresh=pinFresh()&&pinState!=null&&pinState.status==0&&pinState.configured&&Boolean.TRUE.equals(pinState.enabled);
        rt.nowMs=now;return rt;
    }
    /** The simulator's decisions skip runtime checks; a running check still holds its writes. */
    private CapabilityResolver.Decision duringDiscovery(CapabilityResolver.Decision d){
        return discovering&&simulating()&&d.available()&&!d.feature.equals("lock")?CapabilityResolver.withReason(d,CapabilityResolver.Reason.DISCOVERY_ACTIVE):d;
    }
    private boolean pinFresh(){return pinStateMs!=0&&now()-pinStateMs<=10000;}
    /**
     * Read-only model evidence for this connection: the profile's saved identity plus the 0x73
     * firmware read in this session (telemetry is cleared on every connect). GATT is verified before
     * authentication, so the transport is standard. Every control decision starts here.
     */
    public DeviceDescriptor descriptor(){
        if(simulating())return DeviceDescriptor.simulated(simulation==null?simulationModel:simulation.model(),0);
        ScooterProfiles.Profile p=profile();DeviceDescriptor.Firmware fw=sessionFirmware;
        if(fw==null){
            String[] keys={"meter_version","controller_version","battery_version","screen_version","uwb_version"},values=new String[5];long seen=-1;
            for(Telemetry.Metric m:telemetry.metrics())for(int i=0;i<keys.length;i++)if(m.sourceCmd==0x73&&m.key.equals(keys[i])){values[i]=m.value;seen=Math.max(seen,m.updatedMs);}
            fw=sessionFirmware=DeviceDescriptor.Firmware.fromTelemetry(values[0],values[1],values[2],values[3],values[4],seen);
        }
        if(p==null)return DeviceDescriptor.of("","",0,fw,false,DeviceDescriptor.Transport.STANDARD,0);
        return p.descriptor(fw,connected()&&fw.any(),DeviceDescriptor.Transport.STANDARD);
    }
    public Integer featureValue(String key){if(simulating())return simulation==null?null:simulation.featureValue(key);try{return ScooterCommands.find(key).readback(statusFrame);}catch(Exception e){return null;}}
    /** Values this model offers now (UT5 MAX: today's catalogue choices); empty when not mapped. */
    public int[] featureChoices(String key){try{return decision(key).values();}catch(Exception e){return new int[0];}}
    public boolean permissions(){return Build.VERSION.SDK_INT>=31?context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED&&context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED:context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;}
    public boolean bluetoothEnabled(){try{return adapter!=null&&permissions()&&adapter.isEnabled();}catch(SecurityException denied){return false;}}
    public boolean bonded(){return !simulating()&&permissions()&&gatt!=null&&gatt.getDevice().getBondState()==BluetoothDevice.BOND_BONDED;}
    public void bond(){if(simulating()){simulationUnavailable("Bluetooth pairing");return;}if(!gate(new MutationGate.Request(MutationGate.Kind.BOND,"proximity_key",1,MutationGate.Origin.USER)))return;if(pinStateMs==0||now()-pinStateMs>10000){readPin();notice("Read PIN status, then pair this phone.");return;}if(pinState==null||pinState.status!=0||!pinState.configured||!Boolean.TRUE.equals(pinState.enabled)){notice("Configure and enable the dashboard PIN before pairing for proximity unlock.");return;}if(bonded()){notice("This phone is already paired with your scooter.");return;}try{if(gatt.getDevice().createBond())notice("Approve the Bluetooth pairing request on your phone.");else notice("Bluetooth pairing could not start. Try again.");}catch(Exception e){notice("Bluetooth pairing is unavailable.");}}
    private final BroadcastReceiver bluetoothReceiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent intent){if(simulating())return;if(BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())){int value=intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,-1);if(value==BluetoothAdapter.STATE_OFF){if(gatt!=null||wanted||state==State.WAITING)failed(BLUETOOTH_OFF,false,ConnectionEventLog.Reason.ADAPTER_OFF,value);else recordDiagnostic(ConnectionEventLog.Reason.ADAPTER_OFF,value);}else if(value==BluetoothAdapter.STATE_ON&&wanted&&gatt==null&&workAllowed())connectLast();return;}BluetoothDevice device=intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);if(device==null||gatt==null||!device.getAddress().equals(gatt.getDevice().getAddress()))return;int value=intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,-1);bondCheckedMs=0;if(value==BluetoothDevice.BOND_BONDED)notice("Phone paired. You can now enable proximity unlock.");else if(value==BluetoothDevice.BOND_NONE)notice("Bluetooth pairing is not active.");}};
    private boolean workAllowed(){if(simulating())return false;return visible||recording||context.getSharedPreferences("app_settings",Context.MODE_PRIVATE).getBoolean("background_connection",false)||backgroundDeadlineMs>now();}
    public void visibility(boolean v,boolean r){visibility(v,r,0);}
    public void visibility(boolean v,boolean r,long deadline){if(simulating()){visible=v;recording=false;backgroundDeadlineMs=0;if(v)tickSimulation();return;}boolean resumed=v&&!visible;visible=v;recording=r;backgroundDeadlineMs=deadline;if(!workAllowed())suspend();else if(resumed&&authenticated)refresh(false);}
    public void connectLast(){if(simulating()){connectSimulation();return;}if(!workAllowed()){suspend();return;}ScooterProfiles.Profile p=profiles.active();if(p==null||!p.configured()){notice(NO_PROFILE);return;}if(gatt!=null&&(state==State.CONNECTING||state==State.AUTHENTICATING||connected())&&profile!=null&&p.id.equals(profile.id))return;wanted=true;retries=0;identifying=false;if(p.mac.isEmpty())scan();else connect(p,p.mac);}
    public void connectDiscovered(ScanResult result){if(simulating()){connectSimulation();return;}if(!workAllowed()){suspend();return;}ScooterProfiles.Profile p=profiles.active();if(p==null||!p.configured()){notice(NO_PROFILE);return;}wanted=true;retries=0;
        // Remember this device's advertised model ID; remember() commits it only after authentication and a serial match.
        String mac=result.getDevice().getAddress();AdvertisedIdentity.Result id=advertised.get(mac);if(id==null&&result.getScanRecord()!=null)id=AdvertisedIdentity.parse(result.getScanRecord().getBytes());
        pendingAdvertised=id;pendingAdvertisedMac=id==null?"":mac;connect(p,mac);}
    /**
     * Captures the advertised model ID: an intentional disconnect, a scan for the saved scooter
     * only (8 s), then the normal authenticated connection. Sends no scooter command.
     */
    public void identifyModel(){
        if(simulating()){simulationUnavailable("Identify model");return;}
        if(recording){notice("Stop recording first");return;}
        if(mutation!=null){notice("Wait for the current change to finish.");return;}
        if(discovering){notice("Stop the compatibility check first");return;}
        ScooterProfiles.Profile p=profiles.active();if(p==null||!p.configured()){notice(NO_PROFILE);return;}if(p.mac.isEmpty()){notice("Connect first");return;}
        if(!workAllowed()){suspend();return;}if(!permissions()){notice(NEARBY_NEEDED);return;}if(!bluetoothEnabled()){notice(BLUETOOTH_OFF);return;}
        intentionalDisconnectSequence++;recordDiagnostic(ConnectionEventLog.Reason.EXPLICIT_DISCONNECT,-1);
        wanted=true;retries=0;scan(p.mac);if(scanning)identifying=true;
    }
    public void disconnect(){intentionalDisconnectSequence++;recoverableConnectionLoss=false;recordDiagnostic(ConnectionEventLog.Reason.EXPLICIT_DISCONNECT,-1);if(simulating()){state=State.DISCONNECTED;notice("Simulation disconnected");return;}wanted=false;identifying=false;handler.removeCallbacksAndMessages(null);close(State.DISCONNECTED);telemetry.clear();sessionFirmware=null;profile=null;notice("Disconnected");}
    /** Stop every pending connection operation; saved profiles and diagnostic history survive. */
    public void suspend(){
        if(simulating())return;
        recoverableConnectionLoss=false;wanted=false;handler.removeCallbacksAndMessages(null);
        if(gatt==null&&!scanning&&state==State.DISCONNECTED&&mutation==null&&queue.isEmpty())return;
        recordDiagnostic(ConnectionEventLog.Reason.BACKGROUND_PAUSE,-1);close(State.DISCONNECTED);notice("Paused in background");
    }
    public void destroy(){recordDiagnostic(ConnectionEventLog.Reason.SERVICE_CLOSED,-1);recoverableConnectionLoss=false;if(simulating()){telemetry.clear();simulation=null;simulationChanging=false;commandResults.clear();}wanted=false;close(State.DISCONNECTED);handler.removeCallbacksAndMessages(null);listener=null;try{context.unregisterReceiver(bluetoothReceiver);}catch(Exception ignored){}diagnostics.flush();}
    public void scan(){scan("");}
    /** only: a saved MAC to reconnect to (Identify), or "" for discovery. */
    private void scan(String only){
        if(simulating()){simulationUnavailable("Bluetooth discovery");return;}
        if(!workAllowed()){suspend();return;}
        if(!permissions()){notice(NEARBY_NEEDED);return;}if(!bluetoothEnabled()){notice(BLUETOOTH_OFF);return;}
        if(mutation!=null){notice("Busy. Try shortly.");return;}
        if(gatt!=null||state==State.WAITING)close(State.DISCONNECTED);
        stopScan();discoveries.clear();advertised.clear();identifying=false;scanner=adapter.getBluetoothLeScanner();if(scanner==null){notice("Bluetooth is still starting. Try again.");if(!only.isEmpty())identifyFailed();return;}
        final int session=generation;final long token=++scanSequence;
        scanCallback=new ScanCallback(){@Override public void onScanResult(int t,ScanResult r){handler.post(()->{
            if(!scanning||session!=generation||token!=scanSequence||!workAllowed()||r.getScanRecord()==null)return;String mac=r.getDevice().getAddress(),name=r.getScanRecord().getDeviceName();List<ParcelUuid> services=r.getScanRecord().getServiceUuids();
            if(!only.isEmpty()&&!only.equals(mac))return;
            ScooterProfiles.Profile active=profiles.active();boolean known=active!=null&&mac.equals(active.mac);if(!known&&(services==null||!services.contains(new ParcelUuid(SERVICE)))&&(name==null||!name.toLowerCase(Locale.ROOT).contains("navee")))return;
            // Raw AD walk only (see AdvertisedIdentity): the model ID, never the MAC bytes after it.
            AdvertisedIdentity.Result id=AdvertisedIdentity.parse(r.getScanRecord().getBytes());if(id!=null)advertised.put(mac,id);else advertised.remove(mac);
            if(!only.isEmpty()){if(id!=null){stopScan();connectDiscovered(r);}return;} // Identify waits for a packet with the model ID; the timeout reconnects
            if(!discoveries.containsKey(mac)){discoveries.put(mac,r);changed();}
        });}@Override public void onScanFailed(int code){handler.post(()->{if(!scanning||session!=generation||token!=scanSequence)return;stopScan();state=State.DISCONNECTED;notice("Scan failed","Scan failed ("+code+")");if(!only.isEmpty())identifyFailed();});}};
        scanning=true;state=State.SCANNING;message="Searching…";changed();
        try{scanner.startScan(null,new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),scanCallback);}catch(Exception e){stopScan();state=State.DISCONNECTED;notice("Scan failed","Scan failed (could not start)");if(!only.isEmpty())identifyFailed();return;}
        scanTimeout=()->{if(scanning&&session==generation&&token==scanSequence){stopScan();state=State.DISCONNECTED;
            if(!only.isEmpty()){identifying=false;connectLast();notice("Model ID not captured");return;}
            notice(discoveries.isEmpty()?NOTHING_FOUND:"Choose your scooter.");}};handler.postDelayed(scanTimeout,only.isEmpty()?14000:8000);
    }
    /** Identify could not scan: it already closed the connection, so reconnect as the timeout does. */
    private void identifyFailed(){identifying=false;handler.post(()->{if(wanted&&gatt==null&&!scanning&&workAllowed())connectLast();});}
    private void stopScan(){++scanSequence;if(scanTimeout!=null)handler.removeCallbacks(scanTimeout);scanTimeout=null;if(scanning&&scanner!=null&&scanCallback!=null)try{scanner.stopScan(scanCallback);}catch(Exception ignored){}scanning=false;scanCallback=null;}
    private void connect(ScooterProfiles.Profile p,String mac){
        if(simulating())return;
        if(!workAllowed()){suspend();return;}
        if(!permissions()){notice(NEARBY_NEEDED);return;}if(!bluetoothEnabled()){notice(BLUETOOTH_OFF);return;}
        recordDiagnostic(ConnectionEventLog.Reason.CONNECT_ATTEMPT,-1);close(State.CONNECTING);if(discoveryProfileId!=null&&!discoveryProfileId.equals(p.id))clearDiscovery();profile=p;telemetry.clear();sessionFirmware=null;pinState=null;loadResults();message="Connecting…";changed();
        if(!mac.equals(pendingAdvertisedMac)){pendingAdvertised=null;pendingAdvertisedMac="";}
        try{gatt=adapter.getRemoteDevice(mac).connectGatt(context,false,callbacks,BluetoothDevice.TRANSPORT_LE);}catch(Exception e){failed("Connection failed",true,ConnectionEventLog.Reason.CONNECT_FAILED,-1);return;}
        int session=generation;handler.postDelayed(()->{if(session==generation&&gatt!=null&&!ready())failed("Timed out",true,ConnectionEventLog.Reason.CONNECT_TIMEOUT,-1);},18000);
    }
    private void close(State next){
        if(discovering){discovery.onDisconnect(now());finishDiscovery();event("Compatibility check interrupted");}
        readPolicy=null;authVariant="";
        ++generation;++writeOperation;stopScan();BluetoothGatt old=gatt;gatt=null;writer=null;if(old!=null){try{old.disconnect();}catch(Exception ignored){}try{old.close();}catch(Exception ignored){}}
        queue.clear();decoder.reset();if(mutation!=null)result(mutation.key,"Cancelled after disconnection");clearMutation();pinState=null;pinStateMs=pinReadToken=0;pinReadPending=false;authenticated=writing=mtuRequested=discovered=subscribing=subscribed=challengePending=false;authRounds=0;statusFrame=null;sessionFirmware=null;bondCheckedMs=0;statusMs=motionMs=warningMs=lastFrameMs=0;serial="";speedRaw=driving=-1;warning=0;state=next;changed();
    }
    private void failed(String text,boolean retry,ConnectionEventLog.Reason reason,int code){
        if(simulating())return;
        recoverableConnectionLoss=retry&&reason!=ConnectionEventLog.Reason.COMMAND_CONFIRMATION_TIMEOUT;recordDiagnostic(reason,code);
        // A dropped lock or setting change is a safety fact; otherwise say whether a retry follows.
        // Error codes stay in the event log and diagnostics, never in the shown state.
        boolean dropped=mutation!=null;
        boolean reconnect=retry&&wanted&&workAllowed();close(reconnect?State.WAITING:State.DISCONNECTED);
        String shown=text+(dropped?" · change cancelled":reconnect?" · retrying":"");notice(shown,code==-1?shown:shown+" ("+code+")");
        if(reconnect&&profile!=null&&!profile.mac.isEmpty()){
            int session=generation;long delay=Math.min(60000,1000L<<Math.min(6,retries++));event("Reconnect in "+delay/1000+"s");
            handler.postDelayed(()->{if(session==generation&&wanted&&gatt==null&&workAllowed())connect(profile,profile.mac);},delay);
        }
    }
    private void notice(String text){notice(text,text);}
    /** Shows a short state; the event log may keep a longer form (for example with an error code). */
    private void notice(String text,String logged){message=text;event(logged);changed();}private void changed(){if(listener!=null)listener.changed();}
    private void event(String text){SimpleDateFormat f=new SimpleDateFormat("HH:mm:ss",Locale.ROOT);events.add(f.format(new Date())+"  "+text);while(events.size()>180)events.remove(0);}
    private static long now(){return SystemClock.elapsedRealtime();}
    private final BluetoothGattCallback callbacks=new BluetoothGattCallback(){
        @Override public void onConnectionStateChange(BluetoothGatt g,int code,int connection){handler.post(()->{
            if(g!=gatt)return;if(!workAllowed()){suspend();return;}if(code!=0||connection==BluetoothProfile.STATE_DISCONNECTED){failed("Connection lost",true,ConnectionEventLog.Reason.NATIVE_DISCONNECT,code);return;}
            if(connection==BluetoothProfile.STATE_CONNECTED&&!mtuRequested){mtuRequested=true;message="Connecting…";changed();if(!g.requestMtu(185))discover(g);}
        });}
        @Override public void onMtuChanged(BluetoothGatt g,int value,int code){handler.post(()->{if(g==gatt){if(!workAllowed()){suspend();return;}mtu=value;event("Bluetooth MTU "+value);discover(g);}});}
        @Override public void onServicesDiscovered(BluetoothGatt g,int code){handler.post(()->{
            if(g!=gatt||subscribing)return;if(!workAllowed()){suspend();return;}BluetoothGattService s=g.getService(SERVICE);if(code!=0||s==null){failed("Unsupported scooter",false,ConnectionEventLog.Reason.SERVICE_DISCOVERY_FAILED,code);return;}
            writer=s.getCharacteristic(WRITE);BluetoothGattCharacteristic n=s.getCharacteristic(NOTIFY);if(writer==null||n==null){failed("Unsupported scooter",false,ConnectionEventLog.Reason.SERVICE_DISCOVERY_FAILED,-1);return;}
            BluetoothGattDescriptor d=n.getDescriptor(CCCD);if(d==null||!g.setCharacteristicNotification(n,true)){failed("Connection failed",true,ConnectionEventLog.Reason.NOTIFICATION_FAILED,-1);return;}
            subscribing=true;int result;if(Build.VERSION.SDK_INT>=33)result=g.writeDescriptor(d,BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);else{d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);result=g.writeDescriptor(d)?0:-1;}if(result!=0)failed("Connection failed",true,ConnectionEventLog.Reason.NOTIFICATION_FAILED,result);
        });}
        @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int code){handler.post(()->{if(g!=gatt||subscribed)return;if(!workAllowed()){suspend();return;}if(code!=0){failed("Connection failed",true,ConnectionEventLog.Reason.NOTIFICATION_FAILED,code);return;}subscribed=true;authenticate();});}
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value){byte[] copy=value.clone();handler.post(()->{if(g==gatt&&c.getUuid().equals(NOTIFY))incoming(copy);});}
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c){if(Build.VERSION.SDK_INT<33&&c.getValue()!=null){byte[] copy=c.getValue().clone();handler.post(()->{if(g==gatt&&c.getUuid().equals(NOTIFY))incoming(copy);});}}
        @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int code){handler.post(()->{if(g!=gatt)return;if(!workAllowed()){suspend();return;}writing=false;++writeOperation;if(code!=0){failed("Send failed",true,ConnectionEventLog.Reason.WRITE_FAILED,code);return;}handler.postDelayed(BleController.this::pump,80);});}
    };
    private void discover(BluetoothGatt g){if(simulating())return;if(g!=gatt||discovered)return;if(!workAllowed()){suspend();return;}discovered=true;if(!g.discoverServices())failed("Connection failed",true,ConnectionEventLog.Reason.SERVICE_DISCOVERY_FAILED,-1);}
    private void authenticate(){if(simulating())return;if(!workAllowed()){suspend();return;}if(profile==null||++authRounds>3){failed("Owner check failed",false,ConnectionEventLog.Reason.AUTHENTICATION_FAILED,-1);return;}state=State.AUTHENTICATING;message="Checking owner…";authenticated=false;challengePending=false;changed();send(Protocol.auth(Protocol.fromHex(profile.identity),1,profile.role));}
    private void send(byte[] frame){if(simulating())return;if(!workAllowed()||gatt==null||writer==null)return;
        // Last line of defence: a write that is not the admitted mutation never reaches the queue.
        if(!MutationGate.mayTransmit(frame,mutation==null?null:mutation.bytes)){event("Unadmitted write dropped");return;}if(frame.length==7)for(byte[] existing:queue)if(Arrays.equals(frame,existing))return;if(frame.length>7&&authenticated)queue.addFirst(frame);else queue.addLast(frame);pump();}
    private void pump(){
        if(simulating()){queue.clear();writing=false;return;}
        if(!workAllowed()||writing||queue.isEmpty()||gatt==null||writer==null)return;byte[] frame=queue.removeFirst();int cmd=frame[3]&255;int type=(writer.getProperties()&BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)!=0?BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE:BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        if(frame.length==7&&readPolicy!=null)readPolicy.requested(cmd,now());
        writing=true;if(mutation!=null&&Arrays.equals(frame,mutation.bytes)){mutation.sent=true;long token=mutation.token;int s=generation;handler.postDelayed(()->mutationTimeout(s,token),6500);}
        if(cmd==0x7a){pinReadPending=true;pinReadToken=mutation!=null&&mutation.ack?mutation.token:0;int s=generation;long request=++pinReadSequence;handler.postDelayed(()->{if(s==generation&&request==pinReadSequence&&pinReadPending){pinReadPending=false;notice("PIN status did not arrive. Tap Read PIN status to try again.");}},4500);}
        if(!ScooterCommands.sensitiveCommand(cmd))event(String.format(Locale.ROOT,"Send 0x%02X (%d bytes)",cmd,frame.length));
        int result;if(Build.VERSION.SDK_INT>=33)result=gatt.writeCharacteristic(writer,frame,type);else{writer.setWriteType(type);writer.setValue(frame);result=gatt.writeCharacteristic(writer)?0:-1;}
        if(result!=0){writing=false;failed("Send failed",true,ConnectionEventLog.Reason.WRITE_FAILED,result);return;}int op=++writeOperation,session=generation;handler.postDelayed(()->{if(session==generation&&writing&&op==writeOperation)failed("Send failed",true,ConnectionEventLog.Reason.WRITE_TIMEOUT,-1);},3500);
    }
    private void incoming(byte[] bytes){
        if(simulating())return;
        if(!workAllowed())return;
        for(Protocol.Frame f:decoder.feed(bytes)){
            lastFrameMs=now();if(f.flag!=0){failed("Unsupported firmware",false,ConnectionEventLog.Reason.UNSUPPORTED_RESPONSE,-1);return;}
            if(!ScooterCommands.sensitiveCommand(f.cmd))event(String.format(Locale.ROOT,"Receive 0x%02X · status %d · %d bytes",f.cmd,f.error(),f.data.length-1));
            if(f.cmd==0x30){
                if(f.error()!=0){failed(OWNER_REJECTED,false,ConnectionEventLog.Reason.AUTHENTICATION_FAILED,f.error());return;}
                if(f.data.length>1){authVariant=CompatibilityReport.authVariant(f.data.length,f.data[1]&255);try{send(Protocol.challengeResponse(f,1));challengePending=true;}catch(Exception e){failed("Owner check failed",false,ConnectionEventLog.Reason.AUTHENTICATION_FAILED,-1);return;}}
                else if(!authenticated){authenticated=true;challengePending=false;if(authVariant.isEmpty())authVariant="challenge_free";event("Owner authenticated locally");message="Connecting…";policy();send(Protocol.frame(0x74,null));send(Protocol.frame(0x70,null));refresh(true);poll(generation);}
            }else if(f.cmd==0x31&&challengePending){if(f.error()!=0){failed("Owner check failed",false,ConnectionEventLog.Reason.AUTHENTICATION_FAILED,f.error());return;}int s=generation;handler.postDelayed(()->{if(s==generation)authenticate();},150);}
            else if(authenticated){
                if(readPolicy!=null)readPolicy.responded(f.cmd,f.error(),now());
                if(discovering)discovery.onFrame(f.cmd,f.error(),f.data.length-1,now());
                if(f.cmd!=0x7a&&f.cmd!=0x63)telemetry.accept(f.cmd,f.data,now());if(f.cmd==0x73)sessionFirmware=null;
                if(f.cmd==0x74&&f.error()==0&&f.data.length>=2){String received=new String(Arrays.copyOfRange(f.data,1,Math.min(18,f.data.length)),StandardCharsets.US_ASCII).replace("\u0000","").trim();if(!received.matches("[A-Za-z][A-Za-z0-9]{16}")){failed("Unrecognised scooter",false,ConnectionEventLog.Reason.IDENTITY_REJECTED,-1);return;}if(!profile.serial.isEmpty()&&!profile.serial.equals(received)){failed("Not the saved scooter",false,ConnectionEventLog.Reason.IDENTITY_REJECTED,-1);return;}serial=received;profile.serial=received;remember();}
                if(f.cmd==0x70&&f.error()==0&&f.data.length>=14&&(f.data[3]&255)<=1){statusFrame=f;statusMs=now();remember();confirmMutation(f);}
                if(f.cmd==0x90&&f.error()==0&&f.data.length>=9){warning=f.data[1]&255;warningMs=now();}
                if((f.cmd==0x91||f.cmd==0x92)&&f.error()==0&&f.data.length>=(f.cmd==0x91?4:5)){driving=f.data[2]&255;speedRaw=(f.data[3]&255)*(f.cmd==0x91?10:1)+(f.cmd==0x92?(f.data[4]&255)<<8:0);motionMs=now();}
                if(f.cmd==0x7a&&profile.role==0){pinReadPending=false;try{pinState=ScooterCommands.parsePin(f,true);pinStateMs=now();}catch(Exception e){pinState=null;pinStateMs=0;notice("PIN state is unavailable on this firmware.");}
                    if(mutation!=null&&mutation.key.equals("pin")&&mutation.sent&&mutation.ack&&pinReadToken==mutation.token&&ScooterCommands.pinReadbackMatches(f,mutation.target==1,mutation.desiredPin,true)){
                        finishMutation("PIN settings confirmed.");
                    }
                }
                if(mutation!=null&&mutation.sent&&f.cmd==mutation.cmd){if(f.error()!=0){String key=mutation.key;clearMutation();result(key,"Rejected ("+f.error()+")");notice("Change rejected","Change rejected ("+f.error()+")");}else if(mutation.key.equals("clock_sync")){finishMutation("Clock sent (unverified)","Accepted; no clock readback available");}else{mutation.ack=true;int s=generation;long token=mutation.token;String key=mutation.key;handler.postDelayed(()->{if(s==generation&&mutation!=null&&mutation.token==token)for(int cmd:ScooterCommands.followUpReads(key))send(Protocol.frame(cmd,null));},250);}}
            }
            changed();
        }
    }
    private void remember(){if(simulating())return;if(authenticated&&gatt!=null&&!serial.isEmpty()&&statusMs>0){String address=gatt.getDevice().getAddress();boolean changed=!address.equals(profile.mac);profile.mac=address;
        // Authenticated, serial verified on this MAC: the advertised model ID becomes evidence (a conflict is kept too; the resolver handles it).
        boolean captured=pendingAdvertised!=null&&address.equals(pendingAdvertisedMac)&&serial.equals(profile.serial);
        if(captured){profile.advertisedPid=pendingAdvertised.pid;profile.advertisedPidWall=System.currentTimeMillis();pendingAdvertised=null;pendingAdvertisedMac="";changed=true;event("Model ID captured");}
        if(profile.nameDefault){String name=ModelRegistry.modelName(profile.savedDescriptor());if(name.isEmpty())name="NAVEE scooter";if(!name.equals(profile.name)){profile.name=name;changed=true;}}
        if(changed||now()-savedProfileMs>60000||profile.lastConnected==0){profile.lastConnected=System.currentTimeMillis();profiles.update(profile);savedProfileMs=now();}boolean justReady=state!=State.READY;recoverableConnectionLoss=false;state=State.READY;if(justReady)recordDiagnostic(ConnectionEventLog.Reason.AUTHENTICATED_READY,0);retries=0;if(mutation==null)message="Connected";if(identifying){identifying=false;notice(captured?"Model ID captured":"Model ID not captured");}}}
    public void refresh(boolean full){if(simulating()){tickSimulation();changed();return;}if(!workAllowed()||!authenticated)return;
        // UT5 MAX: today's bundle, or 0x70 + 0x72 + 0x79. A running check sends its own reads, so only the core status read joins it.
        if(discovering){send(Protocol.frame(0x70,null));return;}
        ReadPolicy policy=policy();if(full){for(int cmd:policy.afterAuth())send(Protocol.frame(cmd,null));return;}
        send(Protocol.frame(0x70,null));for(int cmd:policy.periodicBattery())send(Protocol.frame(cmd,null));}
    /** This session's read policy, following the model as it becomes known (no identity yet keeps today's arrays). */
    private ReadPolicy policy(){
        DeviceDescriptor d=descriptor();boolean identified=d.pidSource()!=DeviceDescriptor.PidSource.NONE;ModelRegistry.Layer layer=ModelRegistry.resolve(d).layer;
        if(readPolicy==null)readPolicy=identified?ReadPolicy.forLayer(layer):ReadPolicy.legacy();else readPolicy.layer(layer,identified);
        return readPolicy;
    }
    private void poll(int session){if(simulating())return;handler.postDelayed(()->{if(simulating()||!workAllowed()||session!=generation||!authenticated||gatt==null)return;long silentLimit=visible||recording?12000:35000;if((lastFrameMs>0&&now()-lastFrameMs>silentLimit)||(statusMs>0&&now()-statusMs>silentLimit)){failed("Signal lost",true,ConnectionEventLog.Reason.READ_TIMEOUT,-1);return;}if(readPolicy!=null)readPolicy.tick(now());send(Protocol.frame(0x70,null));int interval=context.getSharedPreferences("app_settings",Context.MODE_PRIVATE).getInt("poll_interval_ms",2000);interval=Math.max(2000,Math.min(10000,interval));if(!discovering&&(visible||recording)&&now()-lastBatteryPollMs>=(recording?2000:interval)){for(int cmd:policy().periodicBattery())send(Protocol.frame(cmd,null));lastBatteryPollMs=now();}changed();poll(session);},visible||recording?2000:15000);}
    /** Every write's admission: the resolver decision for this request, on this session's fresh state. */
    private CapabilityResolver.Decision check(MutationGate.Request r){return duringDiscovery(MutationGate.check(r,descriptor(),status(),runtime(r.feature)));}
    private boolean gate(MutationGate.Request r){CapabilityResolver.Decision d=check(r);if(d.available())return true;refused(r,d);return false;}
    /** Refusal notice; the UT5 MAX keeps its existing wording. */
    private void refused(MutationGate.Request r,CapabilityResolver.Decision d){
        boolean ut5=d.layer==ModelRegistry.Layer.UT5_MAX;
        if(r.kind==MutationGate.Kind.SET&&r.feature.equals("lock")&&r.value==0&&(d.reason==CapabilityResolver.Reason.NOT_CONNECTED||d.reason==CapabilityResolver.Reason.BUSY||d.reason==CapabilityResolver.Reason.STATUS_STALE)){notice("Wait for a fresh scooter connection.");return;}
        switch(d.reason){
            case NOT_CONNECTED: notice("Not connected");return;
            case BUSY: notice("Wait for the current change to finish.");return;
            case WARNING_ACTIVE: notice("Resolve scooter warning "+warning+" before locking.");return;
            case MOVING: notice("Stop the scooter first");return;
            case NO_MOTION_REPORT: notice(ut5?"Stop the scooter first":d.reasonText());return;
            case OWNER_ONLY: if(ut5&&d.feature.equals("pin")){notice("PIN settings require the owner profile.");return;}break;
            case NEEDS_BOND: if(ut5){notice("Bond this phone before enabling proximity unlock");return;}break;
            case NEEDS_PIN: if(ut5){notice("Enable a configured handlebar PIN before proximity unlock");return;}break;
            case FIELD_ABSENT: if(ut5){notice("Not available now");return;}break;
            case MODEL_EXCLUDED: if(ut5){notice("Value is outside the OEM feature domain");return;}break;
            default: break;
        }
        notice(d.reasonText());
    }
    public boolean set(String key,int value){return set(key,value,MutationGate.Origin.USER);}
    /** origin AUTOMATIC_RANGE_FOLLOW is admitted only on the UT5 MAX layer. */
    public boolean set(String key,int value,MutationGate.Origin origin){ScooterCommands.Feature f=ScooterCommands.find(key);
        if(discovering&&key.equals("lock")&&origin==MutationGate.Origin.USER)stopDiscovery();
        MutationGate.Request request=new MutationGate.Request(key.equals("proximity_key")?MutationGate.Kind.PROXIMITY:MutationGate.Kind.SET,key,value,origin);
        if(simulating()){
            if(simulation==null||!ready()){notice("Connect the simulated scooter first.");return false;}
            CapabilityResolver.Decision d=check(request);if(!d.available()){notice(d.reasonText());return false;}
            try{simulation.set(key,value);updateSimulation();quiet();return true;}
            catch(IllegalArgumentException invalid){notice(invalid.getMessage());return false;}
        }
        CapabilityResolver.Decision d=check(request);boolean proximity=request.kind==MutationGate.Kind.PROXIMITY;
        // Proximity: a stale PIN state is refreshed first (below), as before; every other refusal stops here.
        boolean pinWait=proximity&&(d.reason==CapabilityResolver.Reason.NEEDS_PIN||d.reason==CapabilityResolver.Reason.NEEDS_BOND);
        if(!d.available()&&!pinWait){refused(request,d);return false;}
        Integer current=featureValue(key);if(current!=null&&current==value){notice(f.label+" is already set.");return true;}
        try{
            byte[] bytes;
            if(proximity){if(!pinFresh()){readPin();notice("Read the PIN state, then apply proximity unlock.");return false;}if(!d.available()){refused(request,d);return false;}boolean bonded=gatt.getDevice().getBondState()==BluetoothDevice.BOND_BONDED;bytes=ScooterCommands.setProximity(value==1,profile.role==0,bonded,pinState);}
            else if(d.variant.decode==FeatureVariant.Decode.UT5_FEATURE)bytes=ScooterCommands.set(key,value,statusFrame);
            else bytes=d.variant.encode(value);
            return begin(request,d.variant,value,bytes,null);
        }catch(IllegalArgumentException e){notice(e.getMessage());return false;}}
    public void setChargeStart(long seconds,int offset){MutationGate.Request request=new MutationGate.Request(MutationGate.Kind.CHARGE_START,"charge_start",0,MutationGate.Origin.USER);
        if(simulating()){if(simulation==null||!ready()){notice("Connect the simulated scooter first.");return;}CapabilityResolver.Decision d=check(request);if(!d.available()){notice(d.reasonText());return;}try{simulation.setChargeStart(seconds,offset);updateSimulation();quiet();}catch(IllegalArgumentException invalid){notice(invalid.getMessage());}return;}
        if(!gate(request))return;try{begin(request,null,0,ScooterCommands.setChargeStart(seconds,offset),seconds+offset);}catch(IllegalArgumentException e){notice(e.getMessage());}}
    public void syncClock(){MutationGate.Request request=new MutationGate.Request(MutationGate.Kind.CLOCK,"clock_sync",0,MutationGate.Origin.USER);
        if(simulating()){if(!ready()){notice("Connect the simulated scooter first.");return;}CapabilityResolver.Decision d=check(request);if(!d.available()){notice(d.reasonText());return;}quiet();return;}
        if(!gate(request))return;long wall=System.currentTimeMillis();begin(request,null,0,ScooterCommands.setClock(wall/1000,TimeZone.getDefault().getOffset(wall)/1000),null);}
    public void readPin(){if(simulating()){simulationUnavailable("Handlebar PIN access");return;}
        CapabilityResolver.Decision d=check(new MutationGate.Request(MutationGate.Kind.PIN_READ,"pin",0,MutationGate.Origin.USER));
        if(!d.available()){if(d.reason==CapabilityResolver.Reason.NOT_CONNECTED||d.reason==CapabilityResolver.Reason.OWNER_ONLY)notice("Connect as the scooter owner to read PIN settings.");else if(d.reason!=CapabilityResolver.Reason.BUSY)notice(d.reasonText());return;}
        if(pinReadPending||mutation!=null)return;send(ScooterCommands.readPin(true));}
    public void setPin(String digits){if(simulating()){simulationUnavailable("Handlebar PIN changes");return;}MutationGate.Request request=new MutationGate.Request(MutationGate.Kind.PIN_SET,"pin",1,MutationGate.Origin.USER);if(!gate(request)||pinReadPending)return;try{if(begin(request,null,1,ScooterCommands.setPin(digits,true),null))mutation.desiredPin=digits.toCharArray();}catch(Exception e){notice(e.getMessage());}}
    public void enablePin(boolean enabled){if(simulating()){simulationUnavailable("Handlebar PIN changes");return;}MutationGate.Request request=new MutationGate.Request(MutationGate.Kind.PIN_ENABLE,"pin",enabled?1:0,MutationGate.Origin.USER);if(!gate(request)||pinReadPending)return;if(!enabled&&Integer.valueOf(1).equals(featureValue("proximity_key"))){notice("Turn off proximity unlock before disabling its fallback PIN.");return;}if(!pinFresh()){readPin();notice("Read the current PIN state first.");return;}try{begin(request,null,enabled?1:0,ScooterCommands.enablePin(enabled,true,pinState!=null&&pinState.status==0&&pinState.configured),null);}catch(Exception e){notice(e.getMessage());}}
    // ---- Compatibility check (read-only) ------------------------------------------------------

    /**
     * Starts the read-only compatibility check from the owner's explicit tap. It sends only the
     * known reads in DiscoveryCoordinator.ORDER; while it runs every write except a user lock change
     * is refused, the battery poll pauses and range follow waits. Authentication is unchanged.
     */
    public void startDiscovery(){
        if(discovering)return;
        if(simulating()){if(simulation==null||!ready()){notice("Connect the simulated scooter first.");return;}}
        else{
            if(!ready()){notice("Connect first");return;}
            if(recording){notice("Stop recording first");return;}
            if(mutation!=null||pinReadPending){notice("Wait for the current change to finish.");return;}
        }
        clearDiscovery();ScooterProfiles.Profile p=profile();
        discovery=new DiscoveryCoordinator(now());discovering=true;discoveryProfileId=p==null?"":p.id;
        event("Compatibility check started");message="Checking compatibility…";changed();
        final int session=generation;
        handler.post(new Runnable(){@Override public void run(){discoveryTick(session,this);}});
    }
    /** Owner Stop (or a lock change): pending reads become "Interrupted". */
    public void stopDiscovery(){if(!discovering)return;discovery.interrupt(now());finishDiscovery();notice("Compatibility check stopped");}
    public boolean discovering(){return discovering;}
    /** The running or last check for the active scooter this app session, or null. */
    public DiscoveryCoordinator discovery(){ScooterProfiles.Profile p=profile();return discovery!=null&&(p==null?"":p.id).equals(discoveryProfileId)?discovery:null;}
    /** The finished check's sanitized report (fresh report ID per call), or null while running or when there is none. */
    public CompatibilityReport compatibilityReport(String appVersion){
        if(discovering||discovery()==null||discoveryDescriptor==null)return null;
        return new CompatibilityReport.Builder(appVersion,new java.security.SecureRandom()).device(discoveryDescriptor,ModelRegistry.resolve(discoveryDescriptor),discoveryAuth)
            .reads(discovery.reads(),discovery.passive(),discovery.startMs()).fields(discoveryStatus).decisions(discoveryDecisions).build();
    }
    /** The finished check's 0x70 snapshot (presence and values), for the Fields list. */
    public StatusFields discoveryStatus(){return discovery()==null||discoveryStatus==null?StatusFields.NONE:discoveryStatus;}
    public List<CapabilityResolver.Decision> discoveryDecisions(){return discovery()==null||discoveryDecisions==null?Collections.<CapabilityResolver.Decision>emptyList():discoveryDecisions;}
    private void discoveryTick(int session,Runnable self){
        if(!discovering||session!=generation)return;
        long t=now();
        if(simulating()?!ready():gatt==null||!authenticated){discovery.onDisconnect(t);finishDiscovery();changed();return;}
        Integer cmd=discovery.nextRead(t);
        if(cmd!=null){if(simulating())simulatedReply(cmd,t);else send(Protocol.frame(cmd,null));changed();}
        if(discovery.finished(t)){finishDiscovery();notice(discovery.interrupted()?"Compatibility check stopped":"Compatibility check finished");return;}
        handler.postDelayed(self,100);
    }
    /** Simulator only: synthetic lengths, two silent probes; the report marks itself simulated. */
    private void simulatedReply(int cmd,long t){
        if(discovery.resolved()==0)discovery.onFrame(0x90,0,18,t);
        int length=cmd==0x74?17:cmd==0x70?43:cmd==0x73?40:cmd==0x72?36:cmd==0x76?16:cmd==0x7b?8:cmd==0x79?2:-1;
        if(length>=0)discovery.onFrame(cmd,0,length,t+40);
    }
    /** Snapshot for the report while this session's evidence is still intact. */
    private void finishDiscovery(){
        discovering=false;
        discoveryDescriptor=descriptor();discoveryStatus=status();discoveryAuth=simulating()?null:authVariant;
        discoveryDecisions=CapabilityResolver.all(discoveryDescriptor,discoveryStatus,runtime(""));
        event("Compatibility check ended");
    }
    private void clearDiscovery(){if(discovering)return;discovery=null;discoveryProfileId=null;discoveryDescriptor=null;discoveryStatus=null;discoveryDecisions=null;discoveryAuth=null;}
    /**
     * Queues one admitted mutation. The gate is evaluated again immediately before sending (same
     * request, fresh state); if the decision changed nothing is sent.
     */
    private boolean begin(MutationGate.Request request,FeatureVariant variant,int target,byte[] bytes,Long time){
        if(simulating())return false;
        CapabilityResolver.Decision again=check(request);
        if(!again.available()){if(request.feature.equals("pin"))Arrays.fill(bytes,(byte)0);refused(request,again);return false;}
        String key=request.feature;
        mutation=new Mutation(key,bytes[3]&255,target,++mutationSequence);mutation.time=time;mutation.bytes=bytes;mutation.request=request;mutation.variant=variant;
        message=key.equals("clock_sync")?"Setting the scooter’s clock from this phone…":"Applying "+ScooterCommands.find(key).label.toLowerCase(Locale.ROOT)+"…";changed();send(bytes);
        return true;
    }
    private void mutationTimeout(int session,long token){if(simulating())return;if(session!=generation||mutation==null||mutation.token!=token)return;String key=mutation.key;boolean missing=key.equals("clock_sync")||statusFrame==null||ScooterCommands.find(key).payloadOffset>=statusFrame.data.length-1;String text=mutation.ack&&missing?"Accepted; state not exposed by firmware":"Change not confirmed";result(key,text);clearMutation();failed("Change not confirmed",true,ConnectionEventLog.Reason.COMMAND_CONFIRMATION_TIMEOUT,-1);}
    private void confirmMutation(Protocol.Frame f){if(simulating())return;if(mutation==null||!mutation.ack||mutation.key.equals("clock_sync"))return;ScooterCommands.Feature feature=ScooterCommands.find(mutation.key);if(mutation.time!=null){Long actual=ScooterCommands.timeReadback(f);if(actual!=null&&actual.equals(mutation.time))finishMutation("Charging start time confirmed.");}else{Integer value=mutation.variant!=null&&mutation.variant.decode!=FeatureVariant.Decode.UT5_FEATURE?mutation.variant.read(StatusFields.of(f,now())):feature.readback(f);if(value!=null&&value==mutation.target){if(feature.key.equals("headlight")||feature.key.equals("auto_sensor"))finishMutation(feature.label+" setting confirmed.","Setting confirmed by readback; light output not measured");else finishMutation(feature.label+" confirmed.");}}}
    private void finishMutation(String text){finishMutation(text,"Confirmed");}
    private void finishMutation(String text,String evidence){String key=mutation.key;result(key,evidence);clearMutation();notice(text);}
    private void clearMutation(){if(mutation!=null&&mutation.desiredPin!=null){Arrays.fill(mutation.desiredPin,'\u0000');Arrays.fill(mutation.bytes,(byte)0);}mutation=null;}
    // Results describe this session only. Neither row captions nor Command evidence reload old outcomes.
    private void result(String key,String text){commandResults.put(key,text);resultTimes.put(key,System.currentTimeMillis());}
    /** A reconnect keeps this session's results (a cancelled change stays visible); another scooter starts clean. */
    private void loadResults(){String id=profile==null?null:profile.id;if(id!=null&&id.equals(resultsProfileId))return;commandResults.clear();resultTimes.clear();resultsProfileId=id;}
}
