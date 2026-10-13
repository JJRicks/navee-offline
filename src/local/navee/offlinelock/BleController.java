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
import java.text.SimpleDateFormat;
import java.util.*;
import org.json.JSONObject;

/** One serialized BLE session. Activity/fold changes never own or recreate the connection. */
public final class BleController {
    public interface Listener {void changed();}
    public enum State {DISCONNECTED,SCANNING,CONNECTING,AUTHENTICATING,READY,WAITING}
    public static final UUID SERVICE=UUID.fromString("0000d0ff-3c17-d293-8e48-14fe2e4da212"),WRITE=UUID.fromString("0000b002-0000-1000-8000-00805f9b34fb"),NOTIFY=UUID.fromString("0000b003-0000-1000-8000-00805f9b34fb"),CCCD=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    public final Telemetry telemetry=new Telemetry();
    private final Context context;private final ScooterProfiles profiles;private final Handler handler=new Handler(Looper.getMainLooper());private final BluetoothAdapter adapter;
    private final Protocol.Decoder decoder=new Protocol.Decoder();private final ArrayDeque<byte[]> queue=new ArrayDeque<>();private final ArrayList<String> events=new ArrayList<>();
    private final LinkedHashMap<String,ScanResult> discoveries=new LinkedHashMap<>();private final LinkedHashMap<String,String> commandResults=new LinkedHashMap<>();
    private Listener listener;private BluetoothGatt gatt;private BluetoothGattCharacteristic writer;private BluetoothLeScanner scanner;private ScooterProfiles.Profile profile;
    private State state=State.DISCONNECTED;private String message="Ready when your scooter is.",serial="";private boolean authenticated,wanted,scanning,writing,mtuRequested,discovered,subscribing,subscribed,challengePending,visible,recording;
    private int generation,writeOperation,authRounds,retries,pollTicks,mtu=23;private long statusMs,lastFrameMs,motionMs,warningMs;private int speedRaw=-1,driving=-1,warning;
    private Protocol.Frame statusFrame;private Mutation mutation;private ScooterCommands.PinState pinState;private long pinStateMs,mutationSequence,pinReadToken,pinReadSequence;private boolean pinReadPending;
    private long savedProfileMs,lastBatteryPollMs,scanSequence,backgroundDeadlineMs;private ScanCallback scanCallback;private Runnable scanTimeout;
    private static final class Mutation {String key;int cmd,target;long token;boolean ack,sent;Long time;byte[] bytes;char[] desiredPin;Mutation(String k,int c,int v,long t){key=k;cmd=c;target=v;token=t;}}
    public BleController(Context c,ScooterProfiles p){context=c.getApplicationContext();profiles=p;BluetoothManager m=context.getSystemService(BluetoothManager.class);adapter=m==null?null:m.getAdapter();IntentFilter filter=new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);if(Build.VERSION.SDK_INT>=33)context.registerReceiver(bluetoothReceiver,filter,Context.RECEIVER_EXPORTED);else context.registerReceiver(bluetoothReceiver,filter);}
    public void setListener(Listener l){listener=l;}
    public State state(){return state;}public String message(){return message;}public boolean ready(){return workAllowed()&&state==State.READY&&authenticated&&gatt!=null&&statusMs>0&&now()-statusMs<=7000&&!serial.isEmpty();}
    public boolean connected(){return gatt!=null&&authenticated;}public Boolean locked(){Integer n=featureValue("lock");return ready()&&n!=null?(n!=0):null;}
    public String pending(){return mutation==null?null:mutation.key;}public int mtu(){return mtu;}public long statusAge(){return statusMs==0?Long.MAX_VALUE:now()-statusMs;}public long lastFrameAge(){return lastFrameMs==0?Long.MAX_VALUE:now()-lastFrameMs;}
    public boolean stationary(){return motionMs>0&&now()>=motionMs&&now()-motionMs<6000&&speedRaw==0&&driving==0;}
    public ScooterProfiles.Profile profile(){return profile==null?profiles.active():profile;}public List<ScanResult> discoveries(){return new ArrayList<>(discoveries.values());}public List<String> events(){return new ArrayList<>(events);}public Map<String,String> commandResults(){return new LinkedHashMap<>(commandResults);}public ScooterCommands.PinState pinState(){return pinState;}
    public Integer featureValue(String key){try{return ScooterCommands.find(key).readback(statusFrame);}catch(Exception e){return null;}}
    public int[] featureChoices(String key){try{return ScooterCommands.choiceValues(key,statusFrame);}catch(Exception e){return new int[0];}}
    public boolean permissions(){return Build.VERSION.SDK_INT>=31?context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED&&context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED:context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;}
    public boolean bluetoothEnabled(){return adapter!=null&&permissions()&&adapter.isEnabled();}
    public boolean bonded(){return permissions()&&gatt!=null&&gatt.getDevice().getBondState()==BluetoothDevice.BOND_BONDED;}
    public void bond(){ScooterCommands.Feature f=ScooterCommands.find("proximity_key");if(!canChange(f))return;if(pinStateMs==0||now()-pinStateMs>10000){readPin();notice("Read PIN status, then pair this phone.");return;}if(pinState==null||pinState.status!=0||!pinState.configured||!Boolean.TRUE.equals(pinState.enabled)){notice("Configure and enable the dashboard PIN before pairing for proximity unlock.");return;}if(bonded()){notice("This phone is already paired with your scooter.");return;}try{if(gatt.getDevice().createBond())notice("Approve the Bluetooth pairing request on your phone.");else notice("Bluetooth pairing could not start. Try again.");}catch(Exception e){notice("Bluetooth pairing is unavailable.");}}
    private final BroadcastReceiver bluetoothReceiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent intent){if(BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())){int value=intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,-1);if(value==BluetoothAdapter.STATE_OFF&&gatt!=null)failed("Bluetooth is off. Pending actions were cancelled.",true);else if(value==BluetoothAdapter.STATE_ON&&wanted&&gatt==null&&workAllowed())connectLast();return;}BluetoothDevice device=intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);if(device==null||gatt==null||!device.getAddress().equals(gatt.getDevice().getAddress()))return;int value=intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,-1);if(value==BluetoothDevice.BOND_BONDED)notice("Phone paired. You can now enable proximity unlock.");else if(value==BluetoothDevice.BOND_NONE)notice("Bluetooth pairing is not active.");}};
    private boolean workAllowed(){return visible||recording||context.getSharedPreferences("app_settings",Context.MODE_PRIVATE).getBoolean("background_connection",false)||backgroundDeadlineMs>now();}
    public void visibility(boolean v,boolean r){visibility(v,r,0);}
    public void visibility(boolean v,boolean r,long deadline){boolean resumed=v&&!visible;visible=v;recording=r;backgroundDeadlineMs=deadline;if(!workAllowed())suspend();else if(resumed&&authenticated)refresh(false);}
    public void connectLast(){if(!workAllowed()){suspend();return;}ScooterProfiles.Profile p=profiles.active();if(p==null||!p.configured()){notice("Add your scooter’s owner identity to get started.");return;}if(gatt!=null&&(state==State.CONNECTING||state==State.AUTHENTICATING||connected())&&profile!=null&&p.id.equals(profile.id))return;wanted=true;retries=0;if(p.mac.isEmpty())scan();else connect(p,p.mac);}
    public void connectDiscovered(ScanResult result){if(!workAllowed()){suspend();return;}ScooterProfiles.Profile p=profiles.active();if(p==null||!p.configured()){notice("Set up an owner profile first.");return;}wanted=true;retries=0;connect(p,result.getDevice().getAddress());}
    public void disconnect(){wanted=false;handler.removeCallbacksAndMessages(null);close(State.DISCONNECTED);telemetry.clear();profile=null;notice("Disconnected. Tap Connect when you’re ready.");}
    /** Stop every pending connection operation; saved profiles and diagnostic history survive. */
    public void suspend(){
        wanted=false;handler.removeCallbacksAndMessages(null);
        if(gatt==null&&!scanning&&state==State.DISCONNECTED&&mutation==null&&queue.isEmpty())return;
        close(State.DISCONNECTED);notice("Bluetooth paused while the app is in the background.");
    }
    public void destroy(){wanted=false;close(State.DISCONNECTED);handler.removeCallbacksAndMessages(null);listener=null;try{context.unregisterReceiver(bluetoothReceiver);}catch(Exception ignored){}}
    public void scan(){
        if(!workAllowed()){suspend();return;}
        if(!permissions()){notice("Allow Nearby devices to find your scooter.");return;}if(!bluetoothEnabled()){notice("Turn on Bluetooth to connect.");return;}
        if(mutation!=null){notice("Wait for the current scooter change before scanning.");return;}
        if(gatt!=null||state==State.WAITING)close(State.DISCONNECTED);
        stopScan();discoveries.clear();scanner=adapter.getBluetoothLeScanner();if(scanner==null){notice("Bluetooth is still starting. Try again.");return;}
        final int session=generation;final long token=++scanSequence;
        scanCallback=new ScanCallback(){@Override public void onScanResult(int t,ScanResult r){handler.post(()->{
            if(!scanning||session!=generation||token!=scanSequence||!workAllowed()||r.getScanRecord()==null)return;String mac=r.getDevice().getAddress(),name=r.getScanRecord().getDeviceName();List<ParcelUuid> services=r.getScanRecord().getServiceUuids();
            ScooterProfiles.Profile active=profiles.active();boolean known=active!=null&&mac.equals(active.mac);if(!known&&(services==null||!services.contains(new ParcelUuid(SERVICE)))&&(name==null||!name.toLowerCase(Locale.ROOT).contains("navee")))return;
            if(!discoveries.containsKey(mac)){discoveries.put(mac,r);changed();}
        });}@Override public void onScanFailed(int code){handler.post(()->{if(!scanning||session!=generation||token!=scanSequence)return;stopScan();state=State.DISCONNECTED;notice("Discovery failed ("+code+"). Try again.");});}};
        scanning=true;state=State.SCANNING;message="Finding nearby Navee scooters…";changed();
        try{scanner.startScan(null,new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),scanCallback);}catch(Exception e){stopScan();state=State.DISCONNECTED;notice("Could not start Bluetooth discovery.");return;}
        scanTimeout=()->{if(scanning&&session==generation&&token==scanSequence){stopScan();state=State.DISCONNECTED;notice(discoveries.isEmpty()?"No scooter found. Power it on and close NAVEE.":"Choose your scooter.");}};handler.postDelayed(scanTimeout,14000);
    }
    private void stopScan(){++scanSequence;if(scanTimeout!=null)handler.removeCallbacks(scanTimeout);scanTimeout=null;if(scanning&&scanner!=null&&scanCallback!=null)try{scanner.stopScan(scanCallback);}catch(Exception ignored){}scanning=false;scanCallback=null;}
    private void connect(ScooterProfiles.Profile p,String mac){
        if(!workAllowed()){suspend();return;}
        if(!permissions()){notice("Allow Nearby devices to connect.");return;}if(!bluetoothEnabled()){notice("Turn on Bluetooth to connect.");return;}
        close(State.CONNECTING);profile=p;telemetry.clear();pinState=null;loadResults();message="Connecting to "+p.name+"…";changed();
        try{gatt=adapter.getRemoteDevice(mac).connectGatt(context,false,callbacks,BluetoothDevice.TRANSPORT_LE);}catch(Exception e){failed("Could not open the Bluetooth connection.",true);return;}
        int session=generation;handler.postDelayed(()->{if(session==generation&&gatt!=null&&!ready())failed("Connection timed out. The scooter may be asleep.",true);},18000);
    }
    private void close(State next){
        ++generation;++writeOperation;stopScan();BluetoothGatt old=gatt;gatt=null;writer=null;if(old!=null)try{old.disconnect();old.close();}catch(Exception ignored){}
        queue.clear();decoder.reset();if(mutation!=null)result(mutation.key,"Cancelled after disconnection");clearMutation();pinState=null;pinStateMs=pinReadToken=0;pinReadPending=false;authenticated=writing=mtuRequested=discovered=subscribing=subscribed=challengePending=false;authRounds=0;statusFrame=null;statusMs=motionMs=warningMs=lastFrameMs=0;serial="";speedRaw=driving=-1;warning=0;state=next;changed();
    }
    private void failed(String text,boolean retry){
        boolean reconnect=retry&&wanted&&workAllowed();close(reconnect?State.WAITING:State.DISCONNECTED);notice(text);
        if(reconnect&&profile!=null&&!profile.mac.isEmpty()){
            int session=generation;long delay=Math.min(60000,1000L<<Math.min(6,retries++));event("Reconnect in "+delay/1000+"s");
            handler.postDelayed(()->{if(session==generation&&wanted&&gatt==null&&workAllowed())connect(profile,profile.mac);},delay);
        }
    }
    private void notice(String text){message=text;event(text);changed();}private void changed(){if(listener!=null)listener.changed();}
    private void event(String text){SimpleDateFormat f=new SimpleDateFormat("HH:mm:ss",Locale.ROOT);events.add(f.format(new Date())+"  "+text);while(events.size()>180)events.remove(0);}
    private static long now(){return SystemClock.elapsedRealtime();}
    private final BluetoothGattCallback callbacks=new BluetoothGattCallback(){
        @Override public void onConnectionStateChange(BluetoothGatt g,int code,int connection){handler.post(()->{
            if(g!=gatt)return;if(!workAllowed()){suspend();return;}if(code!=0||connection==BluetoothProfile.STATE_DISCONNECTED){failed("Bluetooth disconnected ("+code+"). Waiting for the scooter.",true);return;}
            if(connection==BluetoothProfile.STATE_CONNECTED&&!mtuRequested){mtuRequested=true;message="Preparing secure local control…";changed();if(!g.requestMtu(185))discover(g);}
        });}
        @Override public void onMtuChanged(BluetoothGatt g,int value,int code){handler.post(()->{if(g==gatt){if(!workAllowed()){suspend();return;}mtu=value;event("Bluetooth MTU "+value);discover(g);}});}
        @Override public void onServicesDiscovered(BluetoothGatt g,int code){handler.post(()->{
            if(g!=gatt||subscribing)return;if(!workAllowed()){suspend();return;}BluetoothGattService s=g.getService(SERVICE);if(code!=0||s==null){failed("The supported Navee service was not found.",false);return;}
            writer=s.getCharacteristic(WRITE);BluetoothGattCharacteristic n=s.getCharacteristic(NOTIFY);if(writer==null||n==null){failed("Required scooter channels are missing.",false);return;}
            BluetoothGattDescriptor d=n.getDescriptor(CCCD);if(d==null||!g.setCharacteristicNotification(n,true)){failed("Could not receive scooter notifications.",true);return;}
            subscribing=true;int result;if(Build.VERSION.SDK_INT>=33)result=g.writeDescriptor(d,BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);else{d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);result=g.writeDescriptor(d)?0:-1;}if(result!=0)failed("Could not subscribe to scooter notifications.",true);
        });}
        @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int code){handler.post(()->{if(g!=gatt||subscribed)return;if(!workAllowed()){suspend();return;}if(code!=0){failed("Scooter notification setup failed.",true);return;}subscribed=true;authenticate();});}
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value){byte[] copy=value.clone();handler.post(()->{if(g==gatt&&c.getUuid().equals(NOTIFY))incoming(copy);});}
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c){if(Build.VERSION.SDK_INT<33&&c.getValue()!=null){byte[] copy=c.getValue().clone();handler.post(()->{if(g==gatt&&c.getUuid().equals(NOTIFY))incoming(copy);});}}
        @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int code){handler.post(()->{if(g!=gatt)return;if(!workAllowed()){suspend();return;}writing=false;++writeOperation;if(code!=0){failed("Bluetooth write failed ("+code+"). Pending actions were cancelled.",true);return;}handler.postDelayed(BleController.this::pump,80);});}
    };
    private void discover(BluetoothGatt g){if(g!=gatt||discovered)return;if(!workAllowed()){suspend();return;}discovered=true;if(!g.discoverServices())failed("Could not inspect the scooter’s services.",true);}
    private void authenticate(){if(!workAllowed()){suspend();return;}if(profile==null||++authRounds>3){failed("Owner authentication did not complete.",false);return;}state=State.AUTHENTICATING;message="Authenticating offline…";authenticated=false;challengePending=false;changed();send(Protocol.auth(Protocol.fromHex(profile.identity),1,profile.role));}
    private void send(byte[] frame){if(!workAllowed()||gatt==null||writer==null)return;if(frame.length==7)for(byte[] existing:queue)if(Arrays.equals(frame,existing))return;if(frame.length>7&&authenticated)queue.addFirst(frame);else queue.addLast(frame);pump();}
    private void pump(){
        if(!workAllowed()||writing||queue.isEmpty()||gatt==null||writer==null)return;byte[] frame=queue.removeFirst();int cmd=frame[3]&255;int type=(writer.getProperties()&BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)!=0?BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE:BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        writing=true;if(mutation!=null&&Arrays.equals(frame,mutation.bytes)){mutation.sent=true;long token=mutation.token;int s=generation;handler.postDelayed(()->mutationTimeout(s,token),6500);}
        if(cmd==0x7a){pinReadPending=true;pinReadToken=mutation!=null&&mutation.ack?mutation.token:0;int s=generation;long request=++pinReadSequence;handler.postDelayed(()->{if(s==generation&&request==pinReadSequence&&pinReadPending){pinReadPending=false;notice("PIN status did not arrive. Tap Read PIN status to try again.");}},4500);}
        if(!ScooterCommands.sensitiveCommand(cmd))event(String.format(Locale.ROOT,"Send 0x%02X (%d bytes)",cmd,frame.length));
        int result;if(Build.VERSION.SDK_INT>=33)result=gatt.writeCharacteristic(writer,frame,type);else{writer.setWriteType(type);writer.setValue(frame);result=gatt.writeCharacteristic(writer)?0:-1;}
        if(result!=0){writing=false;failed("Bluetooth is busy. Pending actions were cancelled.",true);return;}int op=++writeOperation,session=generation;handler.postDelayed(()->{if(session==generation&&writing&&op==writeOperation)failed("Bluetooth send timed out. Pending actions were cancelled.",true);},3500);
    }
    private void incoming(byte[] bytes){
        if(!workAllowed())return;
        for(Protocol.Frame f:decoder.feed(bytes)){
            lastFrameMs=now();if(f.flag!=0){failed("Unsupported encrypted scooter response.",false);return;}
            if(!ScooterCommands.sensitiveCommand(f.cmd))event(String.format(Locale.ROOT,"Receive 0x%02X · status %d · %d bytes",f.cmd,f.error(),f.data.length-1));
            if(f.cmd==0x30){
                if(f.error()!=0){failed("Owner authentication rejected ("+f.error()+"). Check the setup identity.",false);return;}
                if(f.data.length>1){try{send(Protocol.challengeResponse(f,1));challengePending=true;}catch(Exception e){failed("Unsupported local authentication challenge.",false);return;}}
                else if(!authenticated){authenticated=true;challengePending=false;event("Owner authenticated locally");message="Reading your scooter…";send(Protocol.frame(0x74,null));send(Protocol.frame(0x70,null));refresh(true);poll(generation);}
            }else if(f.cmd==0x31&&challengePending){if(f.error()!=0){failed("Authentication challenge rejected.",false);return;}int s=generation;handler.postDelayed(()->{if(s==generation)authenticate();},150);}
            else if(authenticated){
                if(f.cmd!=0x7a&&f.cmd!=0x63)telemetry.accept(f.cmd,f.data,now());
                if(f.cmd==0x74&&f.error()==0&&f.data.length>=2){String received=new String(Arrays.copyOfRange(f.data,1,Math.min(18,f.data.length)),StandardCharsets.US_ASCII).replace("\u0000","").trim();if(!received.matches("[A-Za-z][A-Za-z0-9]{16}")){failed("The scooter returned an invalid serial identity.",false);return;}if(!profile.serial.isEmpty()&&!profile.serial.equals(received)){failed("This scooter’s serial does not match the saved profile.",false);return;}serial=received;profile.serial=received;remember();}
                if(f.cmd==0x70&&f.error()==0&&f.data.length>=14&&(f.data[3]&255)<=1){statusFrame=f;statusMs=now();remember();confirmMutation(f);}
                if(f.cmd==0x90&&f.error()==0&&f.data.length>=9){warning=f.data[1]&255;warningMs=now();}
                if((f.cmd==0x91||f.cmd==0x92)&&f.error()==0&&f.data.length>=(f.cmd==0x91?4:5)){driving=f.data[2]&255;speedRaw=(f.data[3]&255)*(f.cmd==0x91?10:1)+(f.cmd==0x92?(f.data[4]&255)<<8:0);motionMs=now();}
                if(f.cmd==0x7a&&profile.role==0){pinReadPending=false;try{pinState=ScooterCommands.parsePin(f,true);pinStateMs=now();}catch(Exception e){pinState=null;pinStateMs=0;notice("PIN state is unavailable on this firmware.");}
                    if(mutation!=null&&mutation.key.equals("pin")&&mutation.sent&&mutation.ack&&pinReadToken==mutation.token&&ScooterCommands.pinReadbackMatches(f,mutation.target==1,mutation.desiredPin,true)){
                        finishMutation("PIN settings confirmed.");
                    }
                }
                if(mutation!=null&&mutation.sent&&f.cmd==mutation.cmd){if(f.error()!=0){String key=mutation.key;clearMutation();result(key,"Rejected ("+f.error()+")");notice("Scooter rejected the change ("+f.error()+").");}else if(mutation.key.equals("clock_sync")){finishMutation("Scooter accepted the local clock. This firmware exposes no clock readback.","Accepted; no clock readback available");}else{mutation.ack=true;int s=generation;long token=mutation.token;String key=mutation.key;handler.postDelayed(()->{if(s==generation&&mutation!=null&&mutation.token==token)for(int cmd:ScooterCommands.followUpReads(key))send(Protocol.frame(cmd,null));},250);}}
            }
            changed();
        }
    }
    private void remember(){if(authenticated&&gatt!=null&&!serial.isEmpty()&&statusMs>0){String address=gatt.getDevice().getAddress();boolean changed=!address.equals(profile.mac);profile.mac=address;if(changed||now()-savedProfileMs>60000||profile.lastConnected==0){profile.lastConnected=System.currentTimeMillis();profiles.update(profile);savedProfileMs=now();}state=State.READY;retries=0;if(mutation==null)message="Connected locally. Your scooter’s state is confirmed.";}}
    public void refresh(boolean full){if(!workAllowed()||!authenticated)return;for(int cmd:full?ScooterCommands.diagnosticReads():new int[]{0x70,0x72,0x79})send(Protocol.frame(cmd,null));}
    private void poll(int session){handler.postDelayed(()->{if(!workAllowed()||session!=generation||!authenticated||gatt==null)return;long silentLimit=visible||recording?12000:35000;if((lastFrameMs>0&&now()-lastFrameMs>silentLimit)||(statusMs>0&&now()-statusMs>silentLimit)){failed("Scooter readings stopped. Reconnecting locally…",true);return;}send(Protocol.frame(0x70,null));int interval=context.getSharedPreferences("app_settings",Context.MODE_PRIVATE).getInt("poll_interval_ms",2000);interval=Math.max(2000,Math.min(10000,interval));if((visible||recording)&&now()-lastBatteryPollMs>=(recording?2000:interval)){send(Protocol.frame(0x72,null));send(Protocol.frame(0x79,null));lastBatteryPollMs=now();}changed();poll(session);},visible||recording?2000:15000);}
    private boolean canChange(ScooterCommands.Feature f){
        if(f==null){notice("This control is unavailable.");return false;}if(!ready()){notice("Wait for a fresh, verified scooter connection.");return false;}if(mutation!=null){notice("Wait for the current change to finish.");return false;}
        if(profile.pid()!=2538){notice("This control catalog is verified for UT5 MAX model 2538. Other models remain read-only.");return false;}
        if(f.requiresParked&&!stationary()){notice("Wait for a fresh stationary reading before changing this setting.");return false;}
        if(f.key.equals("lock")&&now()-warningMs<6000&&warning!=0){notice("Resolve scooter warning "+warning+" before locking.");return false;}
        if(f.sensitive&&profile.role!=0){notice("PIN settings require the owner profile.");return false;}return true;
    }
    public boolean set(String key,int value){ScooterCommands.Feature f=ScooterCommands.find(key);
        if(key.equals("lock")&&value==0){if(!ready()||mutation!=null){notice("Wait for a fresh scooter connection.");return false;}}else if(!canChange(f))return false;
        Integer current=featureValue(key);if(current!=null&&current==value){notice(f.label+" is already set.");return true;}
        try{
            byte[] bytes;if(key.equals("proximity_key")){if(pinStateMs==0||now()-pinStateMs>10000){readPin();notice("Read the PIN state, then apply proximity unlock.");return false;}boolean bonded=gatt.getDevice().getBondState()==BluetoothDevice.BOND_BONDED;bytes=ScooterCommands.setProximity(value==1,profile.role==0,bonded,pinState);}else bytes=ScooterCommands.set(key,value,statusFrame);
            begin(key,value,bytes,null);return true;
        }catch(IllegalArgumentException e){notice(e.getMessage());return false;}}
    public void setChargeStart(long seconds,int offset){ScooterCommands.Feature f=ScooterCommands.find("charge_start");if(!canChange(f))return;begin("charge_start",0,ScooterCommands.setChargeStart(seconds,offset),seconds+offset);}
    public void syncClock(){if(!canChange(ScooterCommands.find("charge_start")))return;long wall=System.currentTimeMillis();byte[] bytes=ScooterCommands.setClock(wall/1000,TimeZone.getDefault().getOffset(wall)/1000);mutation=new Mutation("clock_sync",0x6f,0,++mutationSequence);mutation.bytes=bytes;message="Setting the scooter’s clock from this phone…";changed();send(bytes);}
    public void readPin(){if(!ready()||profile.role!=0){notice("Connect as the scooter owner to read PIN settings.");return;}if(pinReadPending||mutation!=null)return;send(ScooterCommands.readPin(true));}
    public void setPin(String digits){ScooterCommands.Feature f=ScooterCommands.find("pin");if(!canChange(f)||pinReadPending)return;try{begin("pin",1,ScooterCommands.setPin(digits,true),null);mutation.desiredPin=digits.toCharArray();}catch(Exception e){notice(e.getMessage());}}
    public void enablePin(boolean enabled){ScooterCommands.Feature f=ScooterCommands.find("pin");if(!canChange(f)||pinReadPending)return;if(!enabled&&Integer.valueOf(1).equals(featureValue("proximity_key"))){notice("Turn off proximity unlock before disabling its fallback PIN.");return;}if(pinStateMs==0||now()-pinStateMs>10000){readPin();notice("Read the current PIN state first.");return;}try{begin("pin",enabled?1:0,ScooterCommands.enablePin(enabled,true,pinState!=null&&pinState.status==0&&pinState.configured),null);}catch(Exception e){notice(e.getMessage());}}
    private void begin(String key,int target,byte[] bytes,Long time){
        mutation=new Mutation(key,bytes[3]&255,target,++mutationSequence);mutation.time=time;mutation.bytes=bytes;message="Applying "+ScooterCommands.find(key).label.toLowerCase(Locale.ROOT)+"…";changed();send(bytes);
    }
    private void mutationTimeout(int session,long token){if(session!=generation||mutation==null||mutation.token!=token)return;String key=mutation.key;boolean missing=key.equals("clock_sync")||statusFrame==null||ScooterCommands.find(key).payloadOffset>=statusFrame.data.length-1;String text=mutation.ack&&missing?"Accepted; state not exposed by firmware":"Change not confirmed";result(key,text);clearMutation();failed(text+". Pending commands were cancelled.",true);}
    private void confirmMutation(Protocol.Frame f){if(mutation==null||!mutation.ack||mutation.key.equals("clock_sync"))return;ScooterCommands.Feature feature=ScooterCommands.find(mutation.key);if(mutation.time!=null){Long actual=ScooterCommands.timeReadback(f);if(actual!=null&&actual.equals(mutation.time))finishMutation("Charging start time confirmed.");}else{Integer value=feature.readback(f);if(value!=null&&value==mutation.target){if(feature.key.equals("headlight")||feature.key.equals("auto_sensor"))finishMutation(feature.label+" setting confirmed.","Setting confirmed by readback; light output not measured");else finishMutation(feature.label+" confirmed.");}}}
    private void finishMutation(String text){finishMutation(text,"Confirmed");}
    private void finishMutation(String text,String evidence){String key=mutation.key;result(key,evidence);clearMutation();notice(text);}
    private void clearMutation(){if(mutation!=null&&mutation.desiredPin!=null){Arrays.fill(mutation.desiredPin,'\u0000');Arrays.fill(mutation.bytes,(byte)0);}mutation=null;}
    private void result(String key,String text){commandResults.put(key,text);if(profile!=null)context.getSharedPreferences("control_evidence",Context.MODE_PRIVATE).edit().putString(profile.id,new JSONObject(commandResults).toString()).apply();}
    private void loadResults(){commandResults.clear();if(profile==null)return;try{JSONObject saved=new JSONObject(context.getSharedPreferences("control_evidence",Context.MODE_PRIVATE).getString(profile.id,"{}"));for(ScooterCommands.Feature feature:ScooterCommands.catalog())if(saved.has(feature.key))commandResults.put(feature.key,saved.getString(feature.key));if(saved.has("clock_sync"))commandResults.put("clock_sync",saved.getString("clock_sync"));}catch(Exception ignored){}}
}
