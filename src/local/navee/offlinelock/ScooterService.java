package local.navee.offlinelock;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.io.*;
import java.util.*;
import org.json.*;

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
    public ScooterProfiles profiles;public BleController ble;public RideStore rides;public PhoneRideStore phoneRides;public SharedPreferences settings;
    private PhoneRideRecorder phoneRecorder;private boolean phoneLocationForeground;private int phoneSavingCount;private String phoneFinalStatus;
    private PowerManager.WakeLock wakeLock;private boolean visible,foregroundStarted,gapMarked,workActive,destroyed;private int samples;private long lastNotification,backgroundDeadlineMs;private String trendProfile="",recordingError;
    private final ArrayDeque<Ui.Point> power=new ArrayDeque<>(),voltage=new ArrayDeque<>(),speed=new ArrayDeque<>();
    private final SharedPreferences.OnSharedPreferenceChangeListener settingsChanged=(prefs,key)->{
        if(!key.equals("background_connection")&&!key.equals("auto_connect"))return;
        Runnable apply=()->{if(!destroyed)applyWorkPolicy(key.equals("auto_connect")&&settings.getBoolean("auto_connect",true));};
        if(Looper.myLooper()==Looper.getMainLooper())apply.run();else handler.post(apply);
    };
    @Override public void onCreate(){super.onCreate();settings=getSharedPreferences("app_settings",MODE_PRIVATE);profiles=new ScooterProfiles(this);ble=new BleController(this,profiles);ble.setListener(this::changed);try{rides=new RideStore(new File(getFilesDir(),"rides"));}catch(IOException e){rides=null;}
        try{phoneRides=new PhoneRideStore(new File(getFilesDir(),"phone-rides"));}catch(IOException ignored){}
        PowerManager pm=getSystemService(PowerManager.class);wakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"NaveeOffline:RideRecorder");wakeLock.setReferenceCounted(false);
        visible=uiVisible;running=this;settings.registerOnSharedPreferenceChangeListener(settingsChanged);ble.visibility(visible,isRecording(),backgroundDeadlineMs);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(!ble.permissions()){stopIdleWork();return START_NOT_STICKY;}
        // A queued startForegroundService still needs to fulfill Android's startup contract.
        // Current visibility is checked immediately afterwards, before any BLE work starts.
        startForegroundNotice(true);applyVisibility(uiVisible);String action=intent==null?null:intent.getAction();
        if("STOP_RECORDING".equals(action))stopRide();
        else if("DISCONNECT".equals(action)){ble.disconnect();if(!isRecording()&&!phoneSaving()&&!visible){stopIdleWork();return START_NOT_STICKY;}}
        applyWorkPolicy(!"DISCONNECT".equals(action)||isRecording());return isRecording()?START_STICKY:START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent){return binder;}
    public void listen(Runnable listener){listeners.add(listener);}public void unlisten(Runnable listener){listeners.remove(listener);}
    public boolean isRecording(){return rides!=null&&rides.activeId()!=null;}
    public String recordingError(){return recordingError;}
    public boolean phoneSaving(){return phoneSavingCount>0;}
    public String phoneStatus(){return phoneSaving()?"Saving phone sensors":phoneRecorder==null?(phoneFinalStatus!=null?phoneFinalStatus:phoneRides==null?"Phone sensor storage unavailable":"Phone sensors idle"):phoneRecorder.status();}
    public String phoneLiveReadings(){return phoneRecorder==null?"":phoneRecorder.liveMotion();}
    public boolean isVisible(){return visible;}
    public long backgroundDeadlineMs(){return backgroundDeadlineMs;}
    public boolean workAllowed(){return visible||isRecording()||phoneSaving()||settings.getBoolean("background_connection",false)||backgroundDeadlineMs>SystemClock.elapsedRealtime();}
    public void visible(boolean value){setUiVisible(value);if(running!=this)applyVisibility(value);}
    private void applyVisibility(boolean value){
        if(destroyed)return;boolean wasVisible=visible;visible=value;
        if(value)cancelBackgroundDeadline();
        else if(wasVisible){
            if(!isRecording()&&!settings.getBoolean("background_connection",false)){
                backgroundDeadlineMs=SystemClock.elapsedRealtime()+10000;handler.removeCallbacks(backgroundStop);handler.postDelayed(backgroundStop,10000);
            }else cancelBackgroundDeadline();
        }
        applyWorkPolicy(value&&!wasVisible);
    }
    private void cancelBackgroundDeadline(){backgroundDeadlineMs=0;handler.removeCallbacks(backgroundStop);}
    private final Runnable backgroundStop=new Runnable(){@Override public void run(){
        if(destroyed)return;
        if(visible||isRecording()||phoneSaving()||settings.getBoolean("background_connection",false)){cancelBackgroundDeadline();return;}
        if(backgroundDeadlineMs==0)return;
        long remaining=backgroundDeadlineMs-SystemClock.elapsedRealtime();
        if(remaining>0){handler.postDelayed(this,remaining);return;}
        stopIdleWork();
    }};
    private void applyWorkPolicy(boolean connect){
        if(destroyed)return;
        if(visible||isRecording()||phoneSaving()||settings.getBoolean("background_connection",false))cancelBackgroundDeadline();
        ble.visibility(visible,isRecording(),backgroundDeadlineMs);
        if(!workAllowed()){stopIdleWork();return;}
        workActive=true;startForegroundNotice(false);if(isRecording()&&phoneRecorder==null)startPhoneRecording();handler.removeCallbacks(tick);handler.post(tick);
        if(isRecording()&&!ble.connected())ble.connectLast();
        else if(connect&&(visible||settings.getBoolean("background_connection",false))&&settings.getBoolean("auto_connect",true)&&ble.state()==BleController.State.DISCONNECTED)ble.connectLast();
        changed();
    }
    private void stopIdleWork(){
        workActive=false;cancelBackgroundDeadline();handler.removeCallbacks(tick);ble.visibility(visible,isRecording(),0);ble.suspend();
        if(wakeLock!=null&&wakeLock.isHeld()&&!isRecording()&&!phoneSaving())wakeLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE);foregroundStarted=false;stopSelf();
    }
    public void startRide(String label){if(phoneSaving()){android.widget.Toast.makeText(this,"Finishing the previous ride",android.widget.Toast.LENGTH_SHORT).show();return;}if(rides==null||!ble.ready())return;try{rides.start(profiles.active().id,label,System.currentTimeMillis(),SystemClock.elapsedRealtime());RideStore.CurrentDirection direction;try{direction=RideStore.CurrentDirection.valueOf(settings.getString("current_direction","UNCONFIRMED"));}catch(Exception e){direction=RideStore.CurrentDirection.UNCONFIRMED;}rides.setCurrentDirection(direction);recordingError=null;gapMarked=false;samples=0;applyWorkPolicy(false);ble.refresh(false);}catch(IOException|IllegalStateException e){android.widget.Toast.makeText(this,e.getMessage(),android.widget.Toast.LENGTH_LONG).show();}}
    public void stopRide(){if(!isRecording())return;try{rides.stop(System.currentTimeMillis(),SystemClock.elapsedRealtime());recordingError=null;}catch(IOException e){recordingError="Recording could not be saved. Free some phone storage, then try Stop again.";android.widget.Toast.makeText(this,recordingError,android.widget.Toast.LENGTH_LONG).show();changed();return;}stopPhoneRecording();if(wakeLock.isHeld()&&!phoneSaving())wakeLock.release();applyWorkPolicy(false);changed();}
    private void startPhoneRecording(){
        if(phoneRides==null||!isRecording()||phoneRecorder!=null)return;
        boolean gps=settings.getBoolean("phone_gps",true)&&visible&&PhoneRideRecorder.hasLocationPermission(this);
        if(gps){phoneLocationForeground=true;try{startForegroundNotice(false);}catch(SecurityException|IllegalStateException e){phoneLocationForeground=false;gps=false;startForegroundNotice(false);}}
        phoneFinalStatus=null;phoneRecorder=new PhoneRideRecorder(this,phoneRides);
        phoneRecorder.start(rides.activeId(),settings.getString("phone_placement","pocket"),gps,settings.getBoolean("phone_motion",true));
    }
    private void stopPhoneRecording(){if(phoneRecorder!=null){PhoneRideRecorder finishing=phoneRecorder;phoneRecorder=null;phoneSavingCount++;finishing.stop(()->handler.post(()->{phoneSavingCount=Math.max(0,phoneSavingCount-1);phoneFinalStatus=finishing.status();if(!destroyed){if(!isRecording()&&!phoneSaving()&&wakeLock.isHeld())wakeLock.release();applyWorkPolicy(false);changed();}}));}phoneLocationForeground=false;}
    private void recordPhoneTelemetry(long now){
        if(phoneRecorder==null)return;Telemetry.Metric speedMetric=null,volts=null,amps=null,battery=null;
        if(ble.connected())for(Telemetry.Metric m:ble.telemetry.metrics()){
            if(m.numericValue==null||m.updatedMs<0||now<m.updatedMs||now-m.updatedMs>3000)continue;
            if(m.key.equals("speed_kmh"))speedMetric=m;else if(m.key.equals("voltage_v"))volts=m;else if(m.key.equals("current_a"))amps=m;else if(m.key.equals("battery_percent"))battery=m;
        }
        boolean pair=volts!=null&&amps!=null&&volts.numericValue>0&&volts.numericValue<=200&&Math.abs(amps.numericValue)<=500&&volts.updatedMs==amps.updatedMs&&volts.sourceCmd==amps.sourceCmd;
        phoneRecorder.telemetry(now,speedMetric==null?null:speedMetric.numericValue,pair?volts.numericValue*amps.numericValue:null,battery==null?null:battery.numericValue,speedMetric==null?-1:speedMetric.updatedMs,pair?volts.updatedMs:-1,battery==null?-1:battery.updatedMs);
    }
    public List<Ui.Point> trend(String key){ArrayDeque<Ui.Point> source=key.equals("voltage_v")?voltage:key.equals("speed_kmh")?speed:power;return new ArrayList<>(source);}
    private final Runnable tick=new Runnable(){public void run(){if(destroyed||!workActive)return;if(!workAllowed()){stopIdleWork();return;}long mono=SystemClock.elapsedRealtime(),wall=System.currentTimeMillis();
        ScooterProfiles.Profile selected=profiles.active();String selectedId=selected==null?"":selected.id;if(!selectedId.equals(trendProfile)){trendProfile=selectedId;power.clear();voltage.clear();speed.clear();}
        if(ble.connected()){
            addTrend(power,"power_w",mono);addTrend(voltage,"voltage_v",mono);addTrend(speed,"speed_kmh",mono);
        }
        if(isRecording()){
            if(!wakeLock.isHeld())wakeLock.acquire(12*60*60*1000L);
            recordPhoneTelemetry(mono);
            try{if(recordingError==null){
                if(!ble.connected()){if(!gapMarked){rides.markGap(wall,mono,"Bluetooth disconnected");gapMarked=true;}rides.heartbeat(wall,mono);}
                else{gapMarked=false;Map<String,RideStore.Sample> values=new LinkedHashMap<>();for(Telemetry.Metric m:ble.telemetry.metrics())values.put(m.key,new RideStore.Sample(m.numericValue,m.value,"",m.sourceCmd,m.updatedMs));rides.sample(wall,mono,values);if(++samples%2==0)ble.refresh(false);}
            }}catch(IOException e){recordingError="Recording paused because phone storage could not be written. Free some space, then stop to save the session.";android.widget.Toast.makeText(ScooterService.this,recordingError,android.widget.Toast.LENGTH_LONG).show();}
        }else if(wakeLock.isHeld()&&!phoneSaving())wakeLock.release();
        if(mono-lastNotification>5000){lastNotification=mono;updateNotification();}
        if(visible||isRecording())changed();if(workActive&&workAllowed())handler.postDelayed(this,visible||isRecording()?1000:15000);
    }};
    private void addTrend(ArrayDeque<Ui.Point> values,String key,long now){for(Telemetry.Metric m:ble.telemetry.metrics())if(m.key.equals(key)&&m.numericValue!=null&&m.updatedMs>=0&&now>=m.updatedMs&&now-m.updatedMs<2500){if(values.isEmpty()||values.peekLast().millis<m.updatedMs)values.add(new Ui.Point(m.updatedMs,m.numericValue));break;}while(!values.isEmpty()&&now-values.peek().millis>120000)values.remove();while(values.size()>240)values.remove();}
    private void changed(){for(Runnable r:new ArrayList<>(listeners))r.run();}
    private void startForegroundNotice(boolean startup){if(!ble.permissions()||(!startup&&!workAllowed()))return;Notification notice=notification();if(Build.VERSION.SDK_INT>=29)startForeground(71,notice,(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE|(phoneLocationForeground?ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION:0)));else startForeground(71,notice);foregroundStarted=true;}
    private Notification notification(){
        NotificationManager manager=getSystemService(NotificationManager.class);NotificationChannel channel=new NotificationChannel("scooter","Scooter connection & recording",NotificationManager.IMPORTANCE_LOW);channel.setShowBadge(false);manager.createNotificationChannel(channel);
        Intent open=new Intent(this,MainActivity.class);PendingIntent content=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        String title=phoneSaving()?"Saving ride":isRecording()?(recordingError==null?"Ride recording is active":"Recording needs attention"):ble.ready()?"Navee connected offline":"Navee Offline";String text=phoneSaving()?"Finishing the last phone sensor measurements.":isRecording()?(recordingError==null?"Scooter and phone ride data are being saved locally.":recordingError):ble.message();
        if(isRecording()&&phoneStatus().startsWith("Phone sensor storage")){title="Ride recording needs attention";text="Scooter data continues. Phone sensor storage is unavailable.";}
        Notification.Builder builder=new Notification.Builder(this,"scooter").setSmallIcon(R.drawable.ic_lock).setContentTitle(title).setContentText(text).setContentIntent(content).setOngoing(isRecording()||phoneSaving()||ble.connected()).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE);
        String action=isRecording()?"STOP_RECORDING":"DISCONNECT";PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,ScooterService.class).setAction(action),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);if(!phoneSaving())builder.addAction(new Notification.Action.Builder(null,isRecording()?"Stop recording":"Disconnect",stop).build());return builder.build();
    }
    private void updateNotification(){if(foregroundStarted&&workAllowed())getSystemService(NotificationManager.class).notify(71,notification());}
    public String snapshot(){try{JSONObject o=new JSONObject();o.put("format","navee-offline-diagnostics-v1");o.put("app_version","0.5.0");o.put("captured_at_ms",System.currentTimeMillis());o.put("connected",ble.ready());o.put("connection",ble.state().name());o.put("mtu",ble.mtu());JSONArray fields=new JSONArray();long now=SystemClock.elapsedRealtime();for(Telemetry.Metric m:ble.telemetry.metrics()){JSONObject f=new JSONObject();f.put("group",m.group);f.put("key",m.key);f.put("label",m.label);f.put("value",m.value==null?JSONObject.NULL:m.value);f.put("number",m.numericValue==null?JSONObject.NULL:m.numericValue);f.put("age_ms",m.updatedMs<0?JSONObject.NULL:now-m.updatedMs);f.put("source",m.sourceCmd<0?JSONObject.NULL:String.format(Locale.ROOT,"0x%02X",m.sourceCmd));fields.put(f);}o.put("fields",fields);JSONObject raw=new JSONObject();for(Map.Entry<Integer,byte[]> e:ble.telemetry.frames().entrySet())if(!ScooterCommands.sensitiveCommand(e.getKey()))raw.put(String.format(Locale.ROOT,"0x%02X",e.getKey()),Protocol.hex(e.getValue()));o.put("raw_diagnostic_payloads",raw);JSONObject results=new JSONObject();for(Map.Entry<String,String> e:ble.commandResults().entrySet())if(!e.getKey().equals("pin"))results.put(e.getKey(),e.getValue());o.put("control_results",results);return o.toString(2);}catch(JSONException e){return "{}";}}
    @Override public void onDestroy(){destroyed=true;workActive=false;if(running==this)running=null;settings.unregisterOnSharedPreferenceChangeListener(settingsChanged);handler.removeCallbacksAndMessages(null);stopPhoneRecording();if(isRecording())try{rides.markGap(System.currentTimeMillis(),SystemClock.elapsedRealtime(),"Service ended");}catch(IOException ignored){}if(wakeLock.isHeld())wakeLock.release();ble.destroy();stopForeground(STOP_FOREGROUND_REMOVE);foregroundStarted=false;listeners.clear();super.onDestroy();}
}
