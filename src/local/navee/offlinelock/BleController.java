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
            NOTHING_FOUND="No scooter found",OWNER_REJECTED="Owner identity rejected",READ_ONLY_MODEL="Read-only on this model";
    private ScooterSimulation simulation;private boolean simulationChanging;
    private final ConnectionEventLog diagnostics;private long intentionalDisconnectSequence;private boolean recoverableConnectionLoss;
    private Listener listener;private BluetoothGatt gatt;private BluetoothGattCharacteristic writer;private BluetoothLeScanner scanner;private ScooterProfiles.Profile profile;
    private State state=State.DISCONNECTED;private String message="Not connected",serial="";private boolean authenticated,wanted,scanning,writing,mtuRequested,discovered,subscribing,subscribed,challengePending,visible,recording;
    private int generation,writeOperation,authRounds,retries,pollTicks,mtu=23;private long statusMs,lastFrameMs,motionMs,warningMs;private int speedRaw=-1,driving=-1,warning;
    private Protocol.Frame statusFrame;private Mutation mutation;private ScooterCommands.PinState pinState;private long pinStateMs,mutationSequence,pinReadToken,pinReadSequence;private boolean pinReadPending;
    private long savedProfileMs,lastBatteryPollMs,scanSequence,backgroundDeadlineMs;private ScanCallback scanCallback;private Runnable scanTimeout;
    private static final class Mutation {String key;int cmd,target;long token;boolean ack,sent;Long time;byte[] bytes;char[] desiredPin;Mutation(String k,int c,int v,long t){key=k;cmd=c;target=v;token=t;}}
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
        close(State.DISCONNECTED);telemetry.clear();profile=null;scanner=null;discoveries.clear();commandResults.clear();
        simulation=enabled?new ScooterSimulation():null;
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
    /** Raw motion push facts for ride auto-end; -1/0 mean missing. All reset whenever the transport closes. */
    public long motionSampleMs(){return simulating()?0:motionMs;}public int speedRaw(){return simulating()?-1:speedRaw;}public int drivingStatus(){return simulating()?-1:driving;}
    public long lastFrameMs(){return simulating()?0:lastFrameMs;}public int connectionGeneration(){return generation;}
    public boolean stationary(){if(simulating())return simulation!=null&&state==State.READY;return motionMs>0&&now()>=motionMs&&now()-motionMs<6000&&speedRaw==0&&driving==0;}
    public ScooterProfiles.Profile profile(){return profile==null?profiles.active():profile;}public List<ScanResult> discoveries(){return new ArrayList<>(discoveries.values());}public List<String> events(){return new ArrayList<>(events);}public Map<String,String> commandResults(){return new LinkedHashMap<>(commandResults);}
    /** Wall time of a key's latest result, or 0. */
    public long commandResultTime(String key){Long time=resultTimes.get(key);return time==null?0:time;}
    /** Connected to a model whose control catalog is not verified: the Scooter page is read-only. */
    public boolean readOnlyModel(){return !simulating()&&ready()&&profile!=null&&profile.pid()!=2538;}public ScooterCommands.PinState pinState(){return pinState;}
    public Integer featureValue(String key){if(simulating())return simulation==null?null:simulation.featureValue(key);try{return ScooterCommands.find(key).readback(statusFrame);}catch(Exception e){return null;}}
    public int[] featureChoices(String key){if(simulating())return simulation==null?new int[0]:simulation.featureChoices(key);try{return ScooterCommands.choiceValues(key,statusFrame);}catch(Exception e){return new int[0];}}
    public boolean permissions(){return Build.VERSION.SDK_INT>=31?context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED&&context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED:context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;}
    public boolean bluetoothEnabled(){try{return adapter!=null&&permissions()&&adapter.isEnabled();}catch(SecurityException denied){return false;}}
    public boolean bonded(){return !simulating()&&permissions()&&gatt!=null&&gatt.getDevice().getBondState()==BluetoothDevice.BOND_BONDED;}
    public void bond(){if(simulating()){simulationUnavailable("Bluetooth pairing");return;}ScooterCommands.Feature f=ScooterCommands.find("proximity_key");if(!canChange(f))return;if(pinStateMs==0||now()-pinStateMs>10000){readPin();notice("Read PIN status, then pair this phone.");return;}if(pinState==null||pinState.status!=0||!pinState.configured||!Boolean.TRUE.equals(pinState.enabled)){notice("Configure and enable the dashboard PIN before pairing for proximity unlock.");return;}if(bonded()){notice("This phone is already paired with your scooter.");return;}try{if(gatt.getDevice().createBond())notice("Approve the Bluetooth pairing request on your phone.");else notice("Bluetooth pairing could not start. Try again.");}catch(Exception e){notice("Bluetooth pairing is unavailable.");}}
    private final BroadcastReceiver bluetoothReceiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent intent){if(simulating())return;if(BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())){int value=intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,-1);if(value==BluetoothAdapter.STATE_OFF){if(gatt!=null||wanted||state==State.WAITING)failed(BLUETOOTH_OFF,false,ConnectionEventLog.Reason.ADAPTER_OFF,value);else recordDiagnostic(ConnectionEventLog.Reason.ADAPTER_OFF,value);}else if(value==BluetoothAdapter.STATE_ON&&wanted&&gatt==null&&workAllowed())connectLast();return;}BluetoothDevice device=intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);if(device==null||gatt==null||!device.getAddress().equals(gatt.getDevice().getAddress()))return;int value=intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,-1);if(value==BluetoothDevice.BOND_BONDED)notice("Phone paired. You can now enable proximity unlock.");else if(value==BluetoothDevice.BOND_NONE)notice("Bluetooth pairing is not active.");}};
    private boolean workAllowed(){if(simulating())return false;return visible||recording||context.getSharedPreferences("app_settings",Context.MODE_PRIVATE).getBoolean("background_connection",false)||backgroundDeadlineMs>now();}
    public void visibility(boolean v,boolean r){visibility(v,r,0);}
    public void visibility(boolean v,boolean r,long deadline){if(simulating()){visible=v;recording=false;backgroundDeadlineMs=0;if(v)tickSimulation();return;}boolean resumed=v&&!visible;visible=v;recording=r;backgroundDeadlineMs=deadline;if(!workAllowed())suspend();else if(resumed&&authenticated)refresh(false);}
    public void connectLast(){if(simulating()){connectSimulation();return;}if(!workAllowed()){suspend();return;}ScooterProfiles.Profile p=profiles.active();if(p==null||!p.configured()){notice(NO_PROFILE);return;}if(gatt!=null&&(state==State.CONNECTING||state==State.AUTHENTICATING||connected())&&profile!=null&&p.id.equals(profile.id))return;wanted=true;retries=0;if(p.mac.isEmpty())scan();else connect(p,p.mac);}
    public void connectDiscovered(ScanResult result){if(simulating()){connectSimulation();return;}if(!workAllowed()){suspend();return;}ScooterProfiles.Profile p=profiles.active();if(p==null||!p.configured()){notice(NO_PROFILE);return;}wanted=true;retries=0;connect(p,result.getDevice().getAddress());}
    public void disconnect(){intentionalDisconnectSequence++;recoverableConnectionLoss=false;recordDiagnostic(ConnectionEventLog.Reason.EXPLICIT_DISCONNECT,-1);if(simulating()){state=State.DISCONNECTED;notice("Simulation disconnected");return;}wanted=false;handler.removeCallbacksAndMessages(null);close(State.DISCONNECTED);telemetry.clear();profile=null;notice("Disconnected");}
    /** Stop every pending connection operation; saved profiles and diagnostic history survive. */
    public void suspend(){
        if(simulating())return;
        recoverableConnectionLoss=false;wanted=false;handler.removeCallbacksAndMessages(null);
        if(gatt==null&&!scanning&&state==State.DISCONNECTED&&mutation==null&&queue.isEmpty())return;
        recordDiagnostic(ConnectionEventLog.Reason.BACKGROUND_PAUSE,-1);close(State.DISCONNECTED);notice("Paused in background");
    }
    public void destroy(){recordDiagnostic(ConnectionEventLog.Reason.SERVICE_CLOSED,-1);recoverableConnectionLoss=false;if(simulating()){telemetry.clear();simulation=null;simulationChanging=false;commandResults.clear();}wanted=false;close(State.DISCONNECTED);handler.removeCallbacksAndMessages(null);listener=null;try{context.unregisterReceiver(bluetoothReceiver);}catch(Exception ignored){}diagnostics.flush();}
    public void scan(){
        if(simulating()){simulationUnavailable("Bluetooth discovery");return;}
        if(!workAllowed()){suspend();return;}
        if(!permissions()){notice(NEARBY_NEEDED);return;}if(!bluetoothEnabled()){notice(BLUETOOTH_OFF);return;}
        if(mutation!=null){notice("Busy. Try shortly.");return;}
        if(gatt!=null||state==State.WAITING)close(State.DISCONNECTED);
        stopScan();discoveries.clear();scanner=adapter.getBluetoothLeScanner();if(scanner==null){notice("Bluetooth is still starting. Try again.");return;}
        final int session=generation;final long token=++scanSequence;
        scanCallback=new ScanCallback(){@Override public void onScanResult(int t,ScanResult r){handler.post(()->{
            if(!scanning||session!=generation||token!=scanSequence||!workAllowed()||r.getScanRecord()==null)return;String mac=r.getDevice().getAddress(),name=r.getScanRecord().getDeviceName();List<ParcelUuid> services=r.getScanRecord().getServiceUuids();
            ScooterProfiles.Profile active=profiles.active();boolean known=active!=null&&mac.equals(active.mac);if(!known&&(services==null||!services.contains(new ParcelUuid(SERVICE)))&&(name==null||!name.toLowerCase(Locale.ROOT).contains("navee")))return;
            if(!discoveries.containsKey(mac)){discoveries.put(mac,r);changed();}
        });}@Override public void onScanFailed(int code){handler.post(()->{if(!scanning||session!=generation||token!=scanSequence)return;stopScan();state=State.DISCONNECTED;notice("Scan failed","Scan failed ("+code+")");});}};
        scanning=true;state=State.SCANNING;message="Searching…";changed();
        try{scanner.startScan(null,new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),scanCallback);}catch(Exception e){stopScan();state=State.DISCONNECTED;notice("Scan failed","Scan failed (could not start)");return;}
        scanTimeout=()->{if(scanning&&session==generation&&token==scanSequence){stopScan();state=State.DISCONNECTED;notice(discoveries.isEmpty()?NOTHING_FOUND:"Choose your scooter.");}};handler.postDelayed(scanTimeout,14000);
    }
    private void stopScan(){++scanSequence;if(scanTimeout!=null)handler.removeCallbacks(scanTimeout);scanTimeout=null;if(scanning&&scanner!=null&&scanCallback!=null)try{scanner.stopScan(scanCallback);}catch(Exception ignored){}scanning=false;scanCallback=null;}
    private void connect(ScooterProfiles.Profile p,String mac){
        if(simulating())return;
        if(!workAllowed()){suspend();return;}
        if(!permissions()){notice(NEARBY_NEEDED);return;}if(!bluetoothEnabled()){notice(BLUETOOTH_OFF);return;}
        recordDiagnostic(ConnectionEventLog.Reason.CONNECT_ATTEMPT,-1);close(State.CONNECTING);profile=p;telemetry.clear();pinState=null;loadResults();message="Connecting…";changed();
        try{gatt=adapter.getRemoteDevice(mac).connectGatt(context,false,callbacks,BluetoothDevice.TRANSPORT_LE);}catch(Exception e){failed("Connection failed",true,ConnectionEventLog.Reason.CONNECT_FAILED,-1);return;}
        int session=generation;handler.postDelayed(()->{if(session==generation&&gatt!=null&&!ready())failed("Timed out",true,ConnectionEventLog.Reason.CONNECT_TIMEOUT,-1);},18000);
    }
    private void close(State next){
        ++generation;++writeOperation;stopScan();BluetoothGatt old=gatt;gatt=null;writer=null;if(old!=null){try{old.disconnect();}catch(Exception ignored){}try{old.close();}catch(Exception ignored){}}
        queue.clear();decoder.reset();if(mutation!=null)result(mutation.key,"Cancelled after disconnection");clearMutation();pinState=null;pinStateMs=pinReadToken=0;pinReadPending=false;authenticated=writing=mtuRequested=discovered=subscribing=subscribed=challengePending=false;authRounds=0;statusFrame=null;statusMs=motionMs=warningMs=lastFrameMs=0;serial="";speedRaw=driving=-1;warning=0;state=next;changed();
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
    private void send(byte[] frame){if(simulating())return;if(!workAllowed()||gatt==null||writer==null)return;if(frame.length==7)for(byte[] existing:queue)if(Arrays.equals(frame,existing))return;if(frame.length>7&&authenticated)queue.addFirst(frame);else queue.addLast(frame);pump();}
    private void pump(){
        if(simulating()){queue.clear();writing=false;return;}
        if(!workAllowed()||writing||queue.isEmpty()||gatt==null||writer==null)return;byte[] frame=queue.removeFirst();int cmd=frame[3]&255;int type=(writer.getProperties()&BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)!=0?BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE:BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
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
                if(f.data.length>1){try{send(Protocol.challengeResponse(f,1));challengePending=true;}catch(Exception e){failed("Owner check failed",false,ConnectionEventLog.Reason.AUTHENTICATION_FAILED,-1);return;}}
                else if(!authenticated){authenticated=true;challengePending=false;event("Owner authenticated locally");message="Connecting…";send(Protocol.frame(0x74,null));send(Protocol.frame(0x70,null));refresh(true);poll(generation);}
            }else if(f.cmd==0x31&&challengePending){if(f.error()!=0){failed("Owner check failed",false,ConnectionEventLog.Reason.AUTHENTICATION_FAILED,f.error());return;}int s=generation;handler.postDelayed(()->{if(s==generation)authenticate();},150);}
            else if(authenticated){
                if(f.cmd!=0x7a&&f.cmd!=0x63)telemetry.accept(f.cmd,f.data,now());
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
    private void remember(){if(simulating())return;if(authenticated&&gatt!=null&&!serial.isEmpty()&&statusMs>0){String address=gatt.getDevice().getAddress();boolean changed=!address.equals(profile.mac);profile.mac=address;if(changed||now()-savedProfileMs>60000||profile.lastConnected==0){profile.lastConnected=System.currentTimeMillis();profiles.update(profile);savedProfileMs=now();}boolean justReady=state!=State.READY;recoverableConnectionLoss=false;state=State.READY;if(justReady)recordDiagnostic(ConnectionEventLog.Reason.AUTHENTICATED_READY,0);retries=0;if(mutation==null)message="Connected";}}
    public void refresh(boolean full){if(simulating()){tickSimulation();changed();return;}if(!workAllowed()||!authenticated)return;for(int cmd:full?ScooterCommands.diagnosticReads():new int[]{0x70,0x72,0x79})send(Protocol.frame(cmd,null));}
    private void poll(int session){if(simulating())return;handler.postDelayed(()->{if(simulating()||!workAllowed()||session!=generation||!authenticated||gatt==null)return;long silentLimit=visible||recording?12000:35000;if((lastFrameMs>0&&now()-lastFrameMs>silentLimit)||(statusMs>0&&now()-statusMs>silentLimit)){failed("Signal lost",true,ConnectionEventLog.Reason.READ_TIMEOUT,-1);return;}send(Protocol.frame(0x70,null));int interval=context.getSharedPreferences("app_settings",Context.MODE_PRIVATE).getInt("poll_interval_ms",2000);interval=Math.max(2000,Math.min(10000,interval));if((visible||recording)&&now()-lastBatteryPollMs>=(recording?2000:interval)){send(Protocol.frame(0x72,null));send(Protocol.frame(0x79,null));lastBatteryPollMs=now();}changed();poll(session);},visible||recording?2000:15000);}
    private boolean canChange(ScooterCommands.Feature f){
        if(simulating())return simulation!=null&&state==State.READY&&f!=null&&!f.sensitive;
        if(f==null){notice("This control is unavailable.");return false;}if(!ready()){notice("Not connected");return false;}if(mutation!=null){notice("Wait for the current change to finish.");return false;}
        if(profile.pid()!=2538){notice(READ_ONLY_MODEL);return false;}
        if(f.requiresParked&&!stationary()){notice("Stop the scooter first");return false;}
        if(f.key.equals("lock")&&now()-warningMs<6000&&warning!=0){notice("Resolve scooter warning "+warning+" before locking.");return false;}
        if(f.sensitive&&profile.role!=0){notice("PIN settings require the owner profile.");return false;}return true;
    }
    public boolean set(String key,int value){ScooterCommands.Feature f=ScooterCommands.find(key);
        if(simulating()){
            if(simulation==null||!ready()){notice("Connect the simulated scooter first.");return false;}
            try{simulation.set(key,value);updateSimulation();quiet();return true;}
            catch(IllegalArgumentException invalid){notice(invalid.getMessage());return false;}
        }
        if(key.equals("lock")&&value==0){if(!ready()||mutation!=null){notice("Wait for a fresh scooter connection.");return false;}}else if(!canChange(f))return false;
        Integer current=featureValue(key);if(current!=null&&current==value){notice(f.label+" is already set.");return true;}
        try{
            byte[] bytes;if(key.equals("proximity_key")){if(pinStateMs==0||now()-pinStateMs>10000){readPin();notice("Read the PIN state, then apply proximity unlock.");return false;}boolean bonded=gatt.getDevice().getBondState()==BluetoothDevice.BOND_BONDED;bytes=ScooterCommands.setProximity(value==1,profile.role==0,bonded,pinState);}else bytes=ScooterCommands.set(key,value,statusFrame);
            begin(key,value,bytes,null);return true;
        }catch(IllegalArgumentException e){notice(e.getMessage());return false;}}
    public void setChargeStart(long seconds,int offset){if(simulating()){if(simulation==null||!ready()){notice("Connect the simulated scooter first.");return;}try{simulation.setChargeStart(seconds,offset);updateSimulation();quiet();}catch(IllegalArgumentException invalid){notice(invalid.getMessage());}return;}ScooterCommands.Feature f=ScooterCommands.find("charge_start");if(!canChange(f))return;begin("charge_start",0,ScooterCommands.setChargeStart(seconds,offset),seconds+offset);}
    public void syncClock(){if(simulating()){if(!ready()){notice("Connect the simulated scooter first.");return;}quiet();return;}if(!canChange(ScooterCommands.find("charge_start")))return;long wall=System.currentTimeMillis();byte[] bytes=ScooterCommands.setClock(wall/1000,TimeZone.getDefault().getOffset(wall)/1000);mutation=new Mutation("clock_sync",0x6f,0,++mutationSequence);mutation.bytes=bytes;message="Setting the scooter’s clock from this phone…";changed();send(bytes);}
    public void readPin(){if(simulating()){simulationUnavailable("Handlebar PIN access");return;}if(!ready()||profile.role!=0){notice("Connect as the scooter owner to read PIN settings.");return;}if(pinReadPending||mutation!=null)return;send(ScooterCommands.readPin(true));}
    public void setPin(String digits){if(simulating()){simulationUnavailable("Handlebar PIN changes");return;}ScooterCommands.Feature f=ScooterCommands.find("pin");if(!canChange(f)||pinReadPending)return;try{begin("pin",1,ScooterCommands.setPin(digits,true),null);mutation.desiredPin=digits.toCharArray();}catch(Exception e){notice(e.getMessage());}}
    public void enablePin(boolean enabled){if(simulating()){simulationUnavailable("Handlebar PIN changes");return;}ScooterCommands.Feature f=ScooterCommands.find("pin");if(!canChange(f)||pinReadPending)return;if(!enabled&&Integer.valueOf(1).equals(featureValue("proximity_key"))){notice("Turn off proximity unlock before disabling its fallback PIN.");return;}if(pinStateMs==0||now()-pinStateMs>10000){readPin();notice("Read the current PIN state first.");return;}try{begin("pin",enabled?1:0,ScooterCommands.enablePin(enabled,true,pinState!=null&&pinState.status==0&&pinState.configured),null);}catch(Exception e){notice(e.getMessage());}}
    private void begin(String key,int target,byte[] bytes,Long time){
        if(simulating())return;
        mutation=new Mutation(key,bytes[3]&255,target,++mutationSequence);mutation.time=time;mutation.bytes=bytes;message="Applying "+ScooterCommands.find(key).label.toLowerCase(Locale.ROOT)+"…";changed();send(bytes);
    }
    private void mutationTimeout(int session,long token){if(simulating())return;if(session!=generation||mutation==null||mutation.token!=token)return;String key=mutation.key;boolean missing=key.equals("clock_sync")||statusFrame==null||ScooterCommands.find(key).payloadOffset>=statusFrame.data.length-1;String text=mutation.ack&&missing?"Accepted; state not exposed by firmware":"Change not confirmed";result(key,text);clearMutation();failed("Change not confirmed",true,ConnectionEventLog.Reason.COMMAND_CONFIRMATION_TIMEOUT,-1);}
    private void confirmMutation(Protocol.Frame f){if(simulating())return;if(mutation==null||!mutation.ack||mutation.key.equals("clock_sync"))return;ScooterCommands.Feature feature=ScooterCommands.find(mutation.key);if(mutation.time!=null){Long actual=ScooterCommands.timeReadback(f);if(actual!=null&&actual.equals(mutation.time))finishMutation("Charging start time confirmed.");}else{Integer value=feature.readback(f);if(value!=null&&value==mutation.target){if(feature.key.equals("headlight")||feature.key.equals("auto_sensor"))finishMutation(feature.label+" setting confirmed.","Setting confirmed by readback; light output not measured");else finishMutation(feature.label+" confirmed.");}}}
    private void finishMutation(String text){finishMutation(text,"Confirmed");}
    private void finishMutation(String text,String evidence){String key=mutation.key;result(key,evidence);clearMutation();notice(text);}
    private void clearMutation(){if(mutation!=null&&mutation.desiredPin!=null){Arrays.fill(mutation.desiredPin,'\u0000');Arrays.fill(mutation.bytes,(byte)0);}mutation=null;}
    // Results describe this session only. Neither row captions nor Command evidence reload old outcomes.
    private void result(String key,String text){commandResults.put(key,text);resultTimes.put(key,System.currentTimeMillis());}
    /** A reconnect keeps this session's results (a cancelled change stays visible); another scooter starts clean. */
    private void loadResults(){String id=profile==null?null:profile.id;if(id!=null&&id.equals(resultsProfileId))return;commandResults.clear();resultTimes.clear();resultsProfileId=id;}
}
