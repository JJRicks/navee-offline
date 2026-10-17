package local.navee.offlinelock.tests;
import android.app.Instrumentation;
import android.app.Activity;
import android.app.NotificationManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.content.*;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.json.*;
import local.navee.offlinelock.*;

/** Explicit ADB-only validation; this runner is a separate APK, never a production component. */
public final class ParkedValidation extends Instrumentation {
    private Bundle args;private volatile ScooterService service;private final JSONArray results=new JSONArray();private boolean bound;private Boolean originalBackground;
    private Activity launchedActivity;private Map<String,Object> smokePreferences;private boolean smokeMode,reconnectAfterSmoke;
    private static final String[] SMOKE_PREFS={"background_connection","auto_connect","controls_section","controls_query","stats_section","stats_query","stats_favourites","stats_favourites_only","more_section"};
    private static final Set<String> LINE_KEYS=new HashSet<>(Arrays.asList("line_light","line_pattern","line_color","line_boost_color"));
    private static final Set<String> STAR_KEYS=new HashSet<>(Arrays.asList("star_light","star_pattern","star_color","star_boost_color"));
    @Override public void onCreate(Bundle args){this.args=args==null?new Bundle():args;start();}
    private final ServiceConnection connection=new ServiceConnection(){public void onServiceConnected(ComponentName n,IBinder b){service=((ScooterService.LocalBinder)b).service();}public void onServiceDisconnected(ComponentName n){service=null;}};
    @Override public void onStart(){Bundle output=new Bundle();try{
        String mode=args.getString("mode","snapshot");smokeMode=mode.equals("navigation")||mode.equals("background");
        if(smokeMode)backupSmokePreferences();
        Activity activity=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));launchedActivity=activity;
        runMain(()->bound=getTargetContext().bindService(new Intent(getTargetContext(),ScooterService.class),connection,Context.BIND_AUTO_CREATE));
        awaitReadOnly(()->service!=null,8000,"Local service unavailable");
        if(smokeMode){
            runMain(()->{if(service.isRecording())throw new IllegalStateException("Smoke test skipped: a user recording is active; it was not interrupted");if(service.ble.pending()!=null)throw new IllegalStateException("Smoke test skipped: a scooter setting is pending");});
        }else{
            runMain(()->{originalBackground=service.settings.getBoolean("background_connection",false);service.settings.edit().putBoolean("background_connection",true).commit();service.visible(true);if(!service.isRecording())service.ble.connectLast();});waitFor(()->service.ble.ready(),35000,"Scooter connection unavailable");
            runMain(()->service.ble.refresh(true));Thread.sleep(2200);
        }
        if(mode.equals("navigation")){
            navigationSmoke(activity);output.putString("navigation","All five tabs, 7 Controls / 4 Stats / 6 More sections, search and optional favourite passed without scooter mutations");
        }else if(mode.equals("background")){
            backgroundSmoke();output.putString("background","Full 10s grace, cancelled old deadline, fresh second pause, hard cutoff, late hidden start and connecting cutoff checked");
        }else if(mode.equals("bgreset")){
            originalBackground=false;runMain(()->service.settings.edit().putBoolean("background_connection",false).commit());output.putString("background","Restored default background connection off");
        }else if(mode.equals("profiles")){
            Context fixture=new ContextWrapper(getTargetContext()){@Override public SharedPreferences getSharedPreferences(String name,int mode){return getBaseContext().getSharedPreferences("validation_profiles_"+name,mode);}};fixture.getSharedPreferences("profiles",0).edit().clear().commit();SharedPreferences legacy=fixture.getSharedPreferences("scooter",0);legacy.edit().clear().putString("identity","112233AABBCC").putInt("role",0).commit();
            ScooterProfiles profiles=new ScooterProfiles(fixture);if(profiles.active()==null||!profiles.active().configured())throw new IllegalStateException("Legacy profile did not migrate");String id=profiles.active().id;profiles.remove(id);if(new ScooterProfiles(fixture).active()!=null||legacy.contains("identity"))throw new IllegalStateException("Forgotten legacy identity returned");ScooterProfiles.Profile shared=profiles.create("Fixture shared","AABBCCDDEEFF",1);OwnerIdentityParser.Result roundtrip=OwnerIdentityParser.parse(ScooterProfiles.privateBackup(shared));if(roundtrip.role!=1||!roundtrip.identity.equals(shared.identity))throw new IllegalStateException("Private shared-role backup did not round trip");fixture.getSharedPreferences("profiles",0).edit().clear().commit();legacy.edit().clear().commit();output.putString("profiles","Isolated migration, forget and role backup passed; real owner setup untouched");
        }else if(mode.equals("restore")){
            String key=args.getString("feature","");int value=Integer.parseInt(args.getString("value","-1"));ScooterCommands.Feature feature=ScooterCommands.find(key);if(feature.sensitive||feature.requiresBond||feature.type==ScooterCommands.Type.TIME||!feature.allows(value))throw new IllegalArgumentException("Only an understood scalar baseline can be restored");AtomicReference<Boolean> sent=new AtomicReference<>();runMain(()->sent.set(service.ble.set(key,value)));if(!Boolean.TRUE.equals(sent.get()))throw new IllegalStateException(service.ble.message());waitFor(()->service.ble.pending()==null,12000,"Restoration did not settle");if(!Integer.valueOf(value).equals(service.ble.featureValue(key)))throw new IllegalStateException("Restoration was not confirmed");status(key+": requested baseline confirmed");
        }else if(mode.equals("lock")){
            validate(ScooterCommands.find("lock"));
        }else if(mode.equals("pin")){
            runMain(()->service.ble.readPin());waitFor(()->service.ble.pinState()!=null,8000,"PIN state unavailable");ScooterCommands.PinState pin=service.ble.pinState();output.putString("pin_state","Enabled="+pin.enabled+", configured="+pin.configured+", status="+pin.status);
        }else if(mode.equals("clock")){
            runMain(()->service.ble.syncClock());waitFor(()->service.ble.pending()==null,10000,"Clock ACK unavailable");output.putString("clock",service.ble.commandResults().get("clock_sync"));
        }else if(mode.equals("time")){
            Double stored=service.ble.telemetry.number("scheduled_charge_time_raw");if(stored==null||stored<=0)throw new IllegalStateException("No restorable stored charging timestamp");long before=stored.longValue();long now=System.currentTimeMillis();int offset=TimeZone.getDefault().getOffset(now)/1000;long target=now/1000+3600;
            try{runMain(()->service.ble.setChargeStart(target,offset));waitFor(()->service.ble.pending()==null,10000,"Charging time did not settle");if(!Double.valueOf(target+offset).equals(service.ble.telemetry.number("scheduled_charge_time_raw")))throw new IllegalStateException("Charging time did not match");status("charge_start: new timestamp confirmed");}
            finally{waitFor(()->service.ble.ready()&&service.ble.pending()==null,15000,"Cannot restore stored charging time");runMain(()->service.ble.setChargeStart(before-offset,offset));waitFor(()->service.ble.pending()==null,10000,"Charging time restoration did not settle");if(!Double.valueOf(before).equals(service.ble.telemetry.number("scheduled_charge_time_raw")))throw new IllegalStateException("Charging time restoration did not match");status("charge_start: original timestamp restored");}
        }else if(mode.equals("sweep")){
            if(service.isRecording())throw new IllegalStateException("Finish the existing recording before parked control validation");
            String only=args.getString("feature","");boolean lab=Boolean.parseBoolean(args.getString("lab","false"));
            for(ScooterCommands.Feature feature:ScooterCommands.catalog()){
                if(!only.isEmpty()&&!only.equals(feature.key))continue;
                if(lab&&only.isEmpty()&&!feature.experimental)continue;
                if(feature.sensitive||feature.requiresBond||feature.type==ScooterCommands.Type.TIME||feature.key.equals("lock"))continue;
                if(feature.experimental&&!lab)continue;
                validate(feature);
            }
        }else if(mode.equals("ui")){
            String page=args.getString("page","home");java.lang.reflect.Method select=MainActivity.class.getDeclaredMethod("select",String.class);select.setAccessible(true);
            runMain(()->{try{select.invoke(activity,page);}catch(Exception e){throw new IllegalStateException(e);}});waitForIdleSync();Thread.sleep(1200);
            AtomicReference<Bitmap> rendered=new AtomicReference<>();runMain(()->{((MainActivity)activity).redraw();View view=activity.findViewById(android.R.id.content);int width=view.getWidth(),height=view.getHeight();forceLayout(view);view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height);Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));rendered.set(bitmap);});
            File dir=new File(getTargetContext().getExternalFilesDir(null),"validation");dir.mkdirs();try(FileOutputStream file=new FileOutputStream(new File(dir,"ui-"+page+".png"))){rendered.get().compress(Bitmap.CompressFormat.PNG,100,file);}output.putString("render","Native app view "+rendered.get().getWidth()+"x"+rendered.get().getHeight());java.lang.reflect.Field monitorField=MainActivity.class.getDeclaredField("fold");monitorField.setAccessible(true);FoldMonitor monitor=(FoldMonitor)monitorField.get(activity);output.putString("fold",monitor.source+"; callbacks="+monitor.layoutCallbacks+"; bounds="+monitor.hinge.toShortString());rendered.get().recycle();
        }else if(mode.equals("record")){
            if(service.isRecording())throw new IllegalStateException("A recording is already active");
            runMain(()->{service.startRide("Stationary validation — not a ridden range test");service.settings.edit().putBoolean("background_connection",originalBackground).commit();service.visible(false);});if(!service.isRecording())throw new IllegalStateException("Recording did not start");
            int seconds=Math.min(180,Math.max(10,Integer.parseInt(args.getString("seconds","45"))));
            String id=service.rides.activeId();Thread.sleep(seconds*1000L);runMain(()->service.stopRide());if(service.isRecording())throw new IllegalStateException("Recording could not stop/save");save("stationary-recording.json",service.rides.get(id).toJson());File dir=new File(getTargetContext().getExternalFilesDir(null),"validation");dir.mkdirs();service.rides.exportCsv(id,new File(dir,"stationary-recording.csv"));output.putString("recording","Completed stationary validation");
        }else if(!mode.equals("snapshot"))throw new IllegalArgumentException("Unknown validation mode");
        save("latest-diagnostics.json",service.snapshot());JSONObject report=new JSONObject();report.put("format","navee-parked-control-validation-v1");report.put("checked_at_ms",System.currentTimeMillis());report.put("results",results);report.put("warning","Readback validation only. Propulsion, descent, cruise engagement, physical lighting and ridden energy behavior require rider checks.");save("control-validation.json",report.toString(2));
        output.putString("result","Saved local diagnostics and validation evidence");output.putInt("controls",smokeMode?0:results.length());if(smokeMode)output.putInt("checks",results.length());output.putString("original_background",String.valueOf(smokeMode&&smokePreferences!=null?smokePreferences.getOrDefault("background_connection",false):originalBackground));cleanup();finish(0,output);
    }catch(Throwable e){try{save("partial-control-validation.json",results.toString(2));if(service!=null)save("failed-diagnostics.json",service.snapshot());}catch(Exception ignored){}output.putString("error",e.getClass().getSimpleName()+": "+e.getMessage());cleanup();finish(1,output);}finally{cleanup();}}
    private void cleanup(){if(smokeMode&&smokePreferences!=null)restoreSmokePreferences();if(service!=null&&originalBackground!=null){boolean previous=originalBackground;runMain(()->{service.settings.edit().putBoolean("background_connection",previous).commit();if(!getTargetContext().getSystemService(PowerManager.class).isInteractive())service.visible(false);});originalBackground=null;}if(bound){runMain(()->getTargetContext().unbindService(connection));bound=false;}}
    private void backupSmokePreferences(){
        smokePreferences=new LinkedHashMap<>();Map<String,?> all=getTargetContext().getSharedPreferences("app_settings",Context.MODE_PRIVATE).getAll();
        for(String key:SMOKE_PREFS)if(all.containsKey(key)){Object value=all.get(key);smokePreferences.put(key,value instanceof Set?new LinkedHashSet<>((Set<?>)value):value);}
    }
    @SuppressWarnings("unchecked") private void restoreSmokePreferences(){
        final Map<String,Object> saved=smokePreferences;
        runMain(()->{
            SharedPreferences.Editor editor=getTargetContext().getSharedPreferences("app_settings",Context.MODE_PRIVATE).edit();
            for(String key:SMOKE_PREFS){editor.remove(key);Object value=saved.get(key);if(value instanceof Boolean)editor.putBoolean(key,(Boolean)value);else if(value instanceof Integer)editor.putInt(key,(Integer)value);else if(value instanceof Long)editor.putLong(key,(Long)value);else if(value instanceof Float)editor.putFloat(key,(Float)value);else if(value instanceof String)editor.putString(key,(String)value);else if(value instanceof Set)editor.putStringSet(key,new LinkedHashSet<>((Set<String>)value));}
            if(!editor.commit())throw new IllegalStateException("Could not restore smoke-test preferences");
            if(launchedActivity!=null&&!launchedActivity.isFinishing())selectOnMain(launchedActivity,"home");
            if(service!=null){boolean interactive=getTargetContext().getSystemService(PowerManager.class).isInteractive();service.visible(interactive);if(interactive&&reconnectAfterSmoke&&!service.isRecording()&&service.ble.state()==BleController.State.DISCONNECTED)service.ble.connectLast();}
        });smokePreferences=null;reconnectAfterSmoke=false;
    }
    private Object field(Object object,String name){
        for(Class<?> type=object.getClass();type!=null;type=type.getSuperclass())try{java.lang.reflect.Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(object);}catch(NoSuchFieldException ignored){}catch(ReflectiveOperationException e){throw new IllegalStateException("Cannot inspect "+name,e);}
        throw new IllegalStateException("Missing validation field: "+name);
    }
    private void selectOnMain(Activity activity,String page){
        try{java.lang.reflect.Method method=MainActivity.class.getDeclaredMethod("select",String.class);method.setAccessible(true);method.invoke(activity,page);}
        catch(ReflectiveOperationException e){throw new IllegalStateException("Cannot select "+page,e);}
    }
    private Object currentScreen(Activity activity){return field(activity,"screen");}
    private View screenView(Activity activity){return ((AppScreen)currentScreen(activity)).view();}
    private void navigate(Activity activity,String page)throws Exception{
        runMain(()->{assertNoUserRecording();selectOnMain(activity,page);});waitForIdleSync();
        runMain(()->{ScrollView view=(ScrollView)field(activity,"scroll");view.scrollTo(0,0);require(page.equals(field(activity,"selectedPage")),"Navigation did not select "+page);});
        waitForIdleSync();
    }
    private View findText(View view,String text){
        if(!view.isShown())return null;
        if(view instanceof TextView&&text.equals(((TextView)view).getText().toString()))return view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=findText(group.getChildAt(i),text);if(found!=null)return found;}}
        return null;
    }
    private EditText findInput(View view,String description){
        if(!view.isShown())return null;
        if(view instanceof EditText&&description.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return (EditText)view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){EditText found=findInput(group.getChildAt(i),description);if(found!=null)return found;}}
        return null;
    }
    private void clickSection(Activity activity,String section)throws Exception{
        runMain(()->{ScrollView scroll=(ScrollView)field(activity,"scroll");scroll.scrollTo(0,0);View tab=findText(screenView(activity),section);require(tab!=null&&tab.isClickable(),"Visible section picker missing: "+section);tab.performClick();});waitForIdleSync();
    }
    /** Opens a More submenu from its menu row: on a phone Back first returns to the menu; the wide layout keeps it beside the submenu. */
    private void clickMoreSection(Activity activity,String label)throws Exception{
        runMain(()->{Object more=currentScreen(activity);
            try{java.lang.reflect.Method back=more.getClass().getDeclaredMethod("back");back.setAccessible(true);back.invoke(more);}catch(ReflectiveOperationException e){throw new IllegalStateException("Cannot invoke More Back",e);}
            View row=findText(screenView(activity),label);require(row!=null,"Visible More menu row missing: "+label);
            while(!row.isClickable()&&row.getParent() instanceof View)row=(View)row.getParent();
            require(row.isClickable(),"More menu row is not clickable: "+label);row.performClick();});
        waitForIdleSync();
    }
    private void require(boolean condition,String failure){if(!condition)throw new IllegalStateException(failure);}
    private void assertNoUserRecording(){require(service!=null&&!service.isRecording(),"Smoke test stopped: a user recording is active; no stop/disconnect was requested");require(service.ble.pending()==null,"Smoke test stopped: a scooter change is pending");}
    @SuppressWarnings("unchecked") private void assertSectionMap(Object screen,String selected,int size){
        Map<String,View> sections=(Map<String,View>)field(screen,"sections");require(sections.size()==size,"Unexpected section catalogue size");
        for(Map.Entry<String,View> section:sections.entrySet())require(section.getValue().getVisibility()==(section.getKey().equals(selected)?View.VISIBLE:View.GONE),"Wrong section visibility: "+section.getKey()+" while "+selected+" selected");
    }
    /** Scooter tab: null is the main page, "lights" the Lights page, otherwise a search query. */
    @SuppressWarnings("unchecked") private void assertControls(Object screen,String mode){
        List<Object> rows=(List<Object>)field(screen,"rows");int shown=0;
        for(Object row:rows){
            ScooterCommands.Feature feature=(ScooterCommands.Feature)field(row,"feature");View card=(View)field(row,"card");
            require(!feature.key.equals("maximum_speed"),"Maximum speed belongs to the top speed card, not a settings row");
            boolean zone=LINE_KEYS.contains(feature.key)||STAR_KEYS.contains(feature.key),light=zone||feature.key.equals("headlight")||feature.key.equals("auto_sensor");
            boolean expected=mode==null?!zone:mode.equals("lights")?light:mode.equals("kickstart")&&feature.key.equals("start_speed");
            require(card.isShown()==expected,"Unexpected control row visibility: "+feature.key);if(card.isShown())shown++;
        }
        View topSpeed=(View)field(field(screen,"topSpeed"),"card");require(topSpeed.isShown()==(mode==null),"Unexpected top speed card visibility");
        if(mode==null)require(shown>0,"Scooter page has no settings rows");
        else if(mode.equals("lights"))require(shown==LINE_KEYS.size()+STAR_KEYS.size()+2,"Lights page must show both headlight controls and every zone control");
        else if(mode.equals("kickstart"))require(shown==1,"Kickstart search must show exactly the start-speed control");
        else require(shown==0,"No-match query left a control visible");
        Map<View,String[]> extras=(Map<View,String[]>)field(screen,"extras");View headlightRow=(View)field(screen,"headlightRow");
        // The read-only headlight row (switched by the power button) belongs to the Lights page only.
        for(Map.Entry<View,String[]> e:extras.entrySet())require(e.getKey().isShown()==(e.getKey()==headlightRow?"lights".equals(mode):mode==null),"Unexpected extra settings row visibility");
        if("lights".equals(mode))require(findText(headlightRow,"Power button")!=null,"Lights page headlight row lost its Power button value");
    }
    /** Wide Stats keeps Readings in its own right-hand pane, so it has no Readings tab and that section is always shown. */
    private boolean statsWide(Object screen){return Boolean.TRUE.equals(field(screen,"wide"));}
    private void assertStatsSections(Object screen,String selected){
        String[] names={"Overview","Readings","Charts","Diagnostics"};String[] fields={"overviewSection","readingsSection","chartsSection","diagnosticsSection"};boolean wide=statsWide(screen);
        for(int i=0;i<names.length;i++){boolean shown=names[i].equals(selected)||wide&&names[i].equals("Readings");require(((View)field(screen,fields[i])).getVisibility()==(shown?View.VISIBLE:View.GONE),"Unexpected Stats section visibility: "+names[i]);}
    }
    private long mutations(){return ((Number)field(service.ble,"mutationSequence")).longValue();}
    private void navigationSmoke(Activity activity)throws Exception{
        AtomicReference<Long> initial=new AtomicReference<>();runMain(()->{assertNoUserRecording();initial.set(mutations());service.settings.edit().putString("controls_query","").putString("stats_query","").putBoolean("stats_favourites_only",false).commit();});
        for(String page:new String[]{"home","controls","stats","rides","more"}){navigate(activity,page);status("Read-only navigation: "+page);}
        navigate(activity,"controls");
        runMain(()->assertControls(currentScreen(activity),null));
        runMain(()->{EditText input=findInput(screenView(activity),"Search scooter settings");require(input!=null,"Controls search missing");input.setText("kickstart");assertControls(currentScreen(activity),"kickstart");input.setText("no-match");assertControls(currentScreen(activity),"no-match");require(findText(screenView(activity),"No matching settings")!=null,"No-match explanation missing");input.setText("");});
        runMain(()->selectOnMain(activity,"lights"));waitForIdleSync();runMain(()->assertControls(currentScreen(activity),"lights"));
        runMain(()->{Object controls=currentScreen(activity);try{java.lang.reflect.Method back=controls.getClass().getDeclaredMethod("back");back.setAccessible(true);require(Boolean.TRUE.equals(back.invoke(controls)),"Lights page Back was not handled");}catch(ReflectiveOperationException e){throw new IllegalStateException("Cannot invoke Lights Back",e);}});
        waitForIdleSync();runMain(()->assertControls(currentScreen(activity),null));render(activity,"navigation-controls.png");
        navigate(activity,"stats");
        AtomicReference<Boolean> wideStats=new AtomicReference<>();runMain(()->wideStats.set(statsWide(currentScreen(activity))));
        for(String section:new String[]{"Overview","Readings","Charts","Diagnostics"}){if(wideStats.get()&&section.equals("Readings"))continue;clickSection(activity,section);runMain(()->assertStatsSections(currentScreen(activity),section));}
        if(wideStats.get())runMain(()->require(findText(screenView(activity),"Readings")==null,"Wide Stats must not offer a Readings tab beside the Readings pane"));
        else clickSection(activity,"Readings");
        runMain(()->{EditText input=findInput(screenView(activity),"Search scooter measurements");require(input!=null,"Stats search missing");input.setText("voltage");require(findText(screenView(activity),"Battery voltage")!=null,"Voltage search result missing");
            Map<?,?> rows=(Map<?,?>)field(currentScreen(activity),"rows");Object voltage=rows.get("voltage_v");require(voltage!=null,"Voltage favourite row missing");View star=(View)field(voltage,"star");require(star.isShown(),"Voltage favourite is not reachable");star.performClick();star.performClick();});
        render(activity,"navigation-stats-readings.png");runMain(()->{EditText input=findInput(screenView(activity),"Search scooter measurements");input.setText("");require(input.getText().length()==0&&"".equals(field(currentScreen(activity),"query")),"Clearing Stats search did not clear its input/query");});clickSection(activity,"Overview");render(activity,"navigation-stats-overview.png");
        navigate(activity,"more");for(String[] section:new String[][]{{"App","App preferences"},{"Scooters","My scooters"},{"Security","PIN & proximity unlock"},{"Lab","Experimental controls"},{"Compatibility","Compatibility"},{"Data","Data & history"},{"About","About & permissions"}}){clickMoreSection(activity,section[1]);runMain(()->assertSectionMap(currentScreen(activity),section[0],8));}
        clickMoreSection(activity,"App preferences");render(activity,"navigation-more-app.png");
        runMain(()->require(mutations()==initial.get(),"Navigation/search/favourite actions submitted a scooter mutation"));
        offlineSliderCancel(activity);
        runMain(()->require(mutations()==initial.get(),"Slider cancellation submitted a scooter mutation"));
        navigate(activity,"home");JSONObject evidence=new JSONObject();evidence.put("mode","navigation");evidence.put("tabs",5);evidence.put("controls_groups",8);evidence.put("stats_sections",4);evidence.put("more_sections",8);evidence.put("scooter_mutations",0);evidence.put("slider_cancel","Disconnected: kick-start row present, slider only in the connected edit sheet; no mutation");results.put(evidence);
    }
    @SuppressWarnings("unchecked") private void offlineSliderCancel(Activity activity)throws Exception{
        runMain(()->{assertNoUserRecording();reconnectAfterSmoke=service.ble.connected()||service.ble.state()==BleController.State.CONNECTING||service.ble.state()==BleController.State.AUTHENTICATING;service.ble.disconnect();service.settings.edit().putString("controls_query","").putString("controls_section","Ride").commit();});
        navigate(activity,"controls");
        runMain(()->{
            require(service.ble.state()==BleController.State.DISCONNECTED&&!service.ble.connected(),"Slider cancel regression requires no live GATT");
            Object target=null;for(Object row:(List<Object>)field(currentScreen(activity),"rows"))if(((ScooterCommands.Feature)field(row,"feature")).key.equals("start_speed")){target=row;break;}
            require(target!=null,"Kick-start slider row unavailable");
            // Rows have no inline slider; the SeekBar lives in an edit sheet that opens only while connected.
        });status("Read-only slider row check passed while disconnected; no control was sent");
    }
    private void dispatch(View view,long down,long stamp,int action,float x,float y){android.view.MotionEvent event=android.view.MotionEvent.obtain(down,stamp,action,x,y,0);try{view.dispatchTouchEvent(event);}finally{event.recycle();}}
    private void render(Activity activity,String filename)throws Exception{
        waitForIdleSync();AtomicReference<Bitmap> rendered=new AtomicReference<>();runMain(()->{((MainActivity)activity).redraw();View view=activity.findViewById(android.R.id.content);int width=view.getWidth(),height=view.getHeight();require(width>0&&height>0,"Render surface has no dimensions");forceLayout(view);view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height);Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));rendered.set(bitmap);});
        File dir=new File(getTargetContext().getExternalFilesDir(null),"validation");dir.mkdirs();try{try(FileOutputStream file=new FileOutputStream(new File(dir,filename))){rendered.get().compress(Bitmap.CompressFormat.PNG,100,file);}}finally{rendered.get().recycle();}
    }
    private boolean foreground(){return Boolean.TRUE.equals(field(service,"foregroundStarted"));}
    private boolean hasConnectionNotification(){for(android.service.notification.StatusBarNotification notification:getTargetContext().getSystemService(NotificationManager.class).getActiveNotifications())if(notification.getId()==71)return true;return false;}
    private void assertHardOff(String phase)throws Exception{
        runMain(()->{assertNoUserRecording();require(!service.isVisible()&&!service.workAllowed(),phase+": hidden work remained eligible");require(service.backgroundDeadlineMs()==0||service.backgroundDeadlineMs()<=SystemClock.elapsedRealtime(),phase+": deadline was extended");require(service.ble.state()==BleController.State.DISCONNECTED&&!service.ble.connected(),phase+": Bluetooth remained active");require(!foreground(),phase+": service retained foreground status");});
        awaitReadOnly(()->!hasConnectionNotification(),1500,phase+": connection notification remained");
    }
    private void backgroundSmoke()throws Exception{
        AtomicReference<Long> initial=new AtomicReference<>(),first=new AtomicReference<>(),second=new AtomicReference<>();
        runMain(()->{assertNoUserRecording();initial.set(mutations());reconnectAfterSmoke=service.ble.connected()||service.ble.state()==BleController.State.CONNECTING||service.ble.state()==BleController.State.AUTHENTICATING;service.settings.edit().putBoolean("auto_connect",true).putBoolean("background_connection",false).commit();service.visible(true);long pause=SystemClock.elapsedRealtime();service.visible(false);first.set(service.backgroundDeadlineMs());require(first.get()>=pause+9950&&first.get()<=SystemClock.elapsedRealtime()+10050,"First pause did not get a full 10s grace");});
        status("Background smoke: first grace remains eligible before its deadline");sleepUntil(first.get()-8000);
        runMain(()->{assertNoUserRecording();require(!service.isVisible()&&service.workAllowed()&&service.backgroundDeadlineMs()==first.get(),"Grace stopped early or its deadline changed");service.visible(true);require(service.backgroundDeadlineMs()==0&&service.workAllowed(),"Resume did not cancel the grace deadline");});
        sleepUntil(first.get()-2000);
        runMain(()->{assertNoUserRecording();long pause=SystemClock.elapsedRealtime();service.visible(false);second.set(service.backgroundDeadlineMs());require(second.get()>=pause+9950&&second.get()>first.get()+6000,"Second pause did not get a fresh full grace");});
        sleepUntil(first.get()+1000);
        runMain(()->{assertNoUserRecording();require(service.workAllowed()&&service.backgroundDeadlineMs()==second.get(),"Old queued pause timer cancelled the second grace");require(foreground(),"Foreground service stopped during the second grace");});
        status("Background smoke: old deadline cancelled; checking hard second deadline");sleepUntil(second.get()+500);assertHardOff("Second grace deadline");
        Thread.sleep(2000);assertHardOff("Two seconds after cutoff");
        runMain(()->getTargetContext().startForegroundService(new Intent(getTargetContext(),ScooterService.class)));Thread.sleep(300);assertHardOff("Late hidden start");
        AtomicReference<Boolean> connecting=new AtomicReference<>(false);AtomicReference<Long> connectingDeadline=new AtomicReference<>();
        runMain(()->{assertNoUserRecording();service.visible(true);require(service.workAllowed()&&service.backgroundDeadlineMs()==0,"Visible return did not restore work policy");ScooterProfiles.Profile profile=service.profiles.active();
            if(profile!=null&&profile.configured()&&!profile.mac.isEmpty()&&service.ble.permissions()&&service.ble.bluetoothEnabled()){
                require(service.ble.state()==BleController.State.CONNECTING||service.ble.state()==BleController.State.AUTHENTICATING,"Visible return did not attempt the saved automatic connection");connecting.set(true);long pause=SystemClock.elapsedRealtime();service.visible(false);connectingDeadline.set(service.backgroundDeadlineMs());require(connectingDeadline.get()>=pause+9950,"Connecting pause did not receive a full grace");
                try{java.lang.reflect.Method failure=BleController.class.getDeclaredMethod("failed",String.class,boolean.class,ConnectionEventLog.Reason.class,int.class);failure.setAccessible(true);failure.invoke(service.ble,"Validation: simulated connection failure",true,ConnectionEventLog.Reason.CONNECT_TIMEOUT,-1);}
                catch(ReflectiveOperationException error){throw new IllegalStateException("Cannot simulate the transport-only failure",error);}
                require(service.backgroundDeadlineMs()==connectingDeadline.get()&&((Number)field(service.ble,"backgroundDeadlineMs")).longValue()==connectingDeadline.get(),"A failed connection extended or cleared its fixed grace deadline");
                require(service.ble.state()==BleController.State.WAITING||service.ble.state()==BleController.State.CONNECTING||service.ble.state()==BleController.State.AUTHENTICATING,"The simulated failure did not enter its retry path");
            }
        });
        if(connecting.get()){status("Background smoke: simulated transport failure/retries must stop at the original fixed deadline");sleepUntil(connectingDeadline.get()+500);assertHardOff("Connecting/failure grace deadline");Thread.sleep(2000);assertHardOff("Two seconds after simulated-failure cutoff");}
        runMain(()->require(mutations()==initial.get(),"Lifecycle smoke submitted a scooter setting mutation"));
        JSONObject evidence=new JSONObject();evidence.put("mode","background");evidence.put("grace_ms",10000);evidence.put("first_resume_cancelled",true);evidence.put("second_pause_full_grace",true);evidence.put("old_deadline_did_not_disconnect",true);evidence.put("hard_cutoff",true);evidence.put("late_hidden_start_no_new_grace",true);evidence.put("connecting_case",connecting.get()?"Started while CONNECTING/AUTHENTICATING, simulated transport-only failure entered retries, original fixed deadline retained, hard cutoff and no late retry/notification after 2s":"Skipped: no verified scooter address/Bluetooth permission or enabled adapter");evidence.put("scooter_mutations",0);evidence.put("recordings_created",0);results.put(evidence);
    }
    private void sleepUntil(long monotonic)throws InterruptedException{for(;;){long remaining=monotonic-SystemClock.elapsedRealtime();if(remaining<=0)return;Thread.sleep(Math.min(500,remaining));}}
    /** Keep assertion failures on the instrumentation thread, never uncaught on MainLooper. */
    private void runMain(Runnable task){
        if(Looper.myLooper()==Looper.getMainLooper()){task.run();return;}
        AtomicReference<Throwable> failure=new AtomicReference<>();
        super.runOnMainSync(()->{try{task.run();}catch(Throwable error){failure.set(error);}});
        Throwable error=failure.get();if(error instanceof Error)throw (Error)error;if(error instanceof RuntimeException)throw (RuntimeException)error;if(error!=null)throw new IllegalStateException("Main-thread validation failed",error);
    }
    /** Unlike legacy waitFor, never changes controller visibility or starts connection work. */
    private void awaitReadOnly(BooleanSupplier condition,long timeout,String failure)throws Exception{long deadline=SystemClock.elapsedRealtime()+timeout;for(;;){AtomicReference<Boolean> value=new AtomicReference<>();runMain(()->value.set(condition.getAsBoolean()));if(Boolean.TRUE.equals(value.get()))return;if(SystemClock.elapsedRealtime()>deadline)throw new IllegalStateException(failure);Thread.sleep(100);}}
    private void validate(ScooterCommands.Feature feature)throws Exception{
        if(feature.requiresParked)waitFor(()->service.ble.stationary(),10000,"No fresh stationary reading; no test write");
        Integer before=service.ble.featureValue(feature.key);JSONObject row=new JSONObject();row.put("key",feature.key);row.put("experimental",feature.experimental);
        if(before==null||!feature.allows(before)){row.put("outcome",before==null?"Unavailable readback; no write":"Current value outside understood write domain; no write");results.put(row);status(feature.key+": skipped");return;}
        int target=before;int[] choices=service.ble.featureChoices(feature.key);
        if(choices.length>0){for(int candidate:choices)if(candidate!=before){target=candidate;break;}}
        else if(feature.type==ScooterCommands.Type.TOGGLE)target=before==0?1:0;
        else for(int candidate=feature.min;candidate<=feature.max;candidate+=feature.step)if(candidate!=before&&feature.allows(candidate)){target=candidate;break;}
        if(feature.key.equals("start_speed")&&before!=5)target=5;
        if(target==before){row.put("outcome","No alternative supported value; no write");results.put(row);status(feature.key+": single option");return;}
        row.put("before",before);row.put("test",target);boolean restored=false;boolean confirmed=false;String failure=null;
        try{int desired=target;AtomicReference<Boolean> submitted=new AtomicReference<>();runMain(()->submitted.set(service.ble.set(feature.key,desired)));if(!Boolean.TRUE.equals(submitted.get()))throw new IllegalStateException("Change declined: "+service.ble.message());
            waitFor(()->service.ble.pending()==null,12000,"Change did not settle");waitFor(()->service.ble.ready(),12000,"Connection did not recover");confirmed=Integer.valueOf(target).equals(service.ble.featureValue(feature.key));row.put("after",service.ble.featureValue(feature.key));row.put("change_confirmed",confirmed);row.put("evidence",service.ble.commandResults().get(feature.key));
        }catch(Exception e){failure=e.getMessage();row.put("failure",failure);}
        finally{
            waitFor(()->service.ble.pending()==null&&service.ble.ready(),22000,"Cannot safely restore: connection unavailable");
            if(before.equals(service.ble.featureValue(feature.key)))restored=true;
            else{runMain(()->service.ble.set(feature.key,before));waitFor(()->service.ble.pending()==null,12000,"Restoration did not settle");restored=service.ble.ready()&&before.equals(service.ble.featureValue(feature.key));}
            row.put("restored",restored);row.put("outcome",confirmed&&restored?"Change and restoration confirmed":restored?"No confirmed change; baseline restored":"Restoration unconfirmed");results.put(row);status(feature.key+": "+row.getString("outcome"));
            if(!restored)throw new IllegalStateException("Stop validation: "+feature.key+" restoration is not confirmed");
        }
        if(!confirmed&&failure!=null)throw new IllegalStateException("Stop validation after declined/unconfirmed change: "+feature.key+" · "+failure);
        Thread.sleep(300);
    }
    private void waitFor(BooleanSupplier condition,long timeout,String failure)throws Exception{long deadline=SystemClock.elapsedRealtime()+timeout;for(;;){AtomicReference<Boolean> value=new AtomicReference<>();runMain(()->{if(service!=null)service.ble.visibility(true,service.isRecording());value.set(condition.getAsBoolean());});if(Boolean.TRUE.equals(value.get()))return;if(SystemClock.elapsedRealtime()>deadline)throw new IllegalStateException(failure);Thread.sleep(100);}}
    private void save(String name,String text)throws IOException{File dir=new File(getTargetContext().getExternalFilesDir(null),"validation");dir.mkdirs();try(FileOutputStream stream=new FileOutputStream(new File(dir,name))){stream.write(text.getBytes(StandardCharsets.UTF_8));}}
    private void status(String text){Bundle b=new Bundle();b.putString("progress",text);sendStatus(0,b);}
    private void forceLayout(View view){view.forceLayout();if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++)forceLayout(group.getChildAt(i));}}
}
