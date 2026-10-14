package local.navee.offlinelock.tests;

import android.Manifest;
import android.app.Instrumentation;
import android.app.Notification;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import local.navee.offlinelock.*;

/** Disposable emulator only: verifies UI/guards/lifecycle without pretending BLE is connected. */
public final class ChargeCaptureValidation extends Instrumentation {
    private MainActivity activity;
    private ScooterService service;
    private Bundle args;
    private int checks;
    private String profile;
    @Override public void onCreate(Bundle args){this.args=args==null?new Bundle():args;start();}
    @Override public void onStart(){Bundle result=new Bundle();
        try{
            admit();
            require(settings().edit().putBoolean("auto_connect",false).putBoolean("background_connection",false)
                    .putBoolean("developer_options",true).putString("theme",args.getString("theme","dark")).commit(),"Settings fixture failed");
            main(()->{ScooterProfiles profiles=new ScooterProfiles(getTargetContext());ScooterProfiles.Profile p=profiles.create("Synthetic charge fixture","000000000000",0);p.serial="X2538-SYNTHETIC";profiles.update(p);profile=p.id;});
            activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(()->activity.service()!=null,"Service missing");service=activity.service();await(()->!Boolean.TRUE.equals(field(service,"chargeModelLoading")),"Charge model load did not finish");
            main(()->{invoke(activity,"select",new Class<?>[]{String.class},"more");invoke(field(activity,"screen"),"selectSection",new Class<?>[]{String.class},"Developer");});settle();
            main(()->{
                View selector=root().findViewWithTag("charge-charger-selector");require(selector!=null,"Charger selector missing");
                require(!root().findViewWithTag("charge-capture-action").isEnabled(),"Disconnected capture action is enabled");
                require(!service.startChargeRecording(ChargeSessionStore.Charger.STOCK)&&!service.isChargeRecording(),"Disconnected start bypassed guard");
                require(!store().active()&&files().length==0,"Denied start created a journal");
                @SuppressWarnings("unchecked") List<TextView> choices=(List<TextView>)field(field(selector,"track"),"choices");
                choices.get(1).performClick();require(service.chargeCharger()==ChargeSessionStore.Charger.HIGH_SPEED,"Charger selection did not persist");
                require(settings().getString("charge_charger_"+profile,"").equals("HIGH_SPEED"),"Charger choice leaked to a global preference");
                require(service.setSimulation(true),"Could not enable existing sample preview");service.ble.setSimulationCharging(true);
                require(!service.startChargeRecording(ChargeSessionStore.Charger.HIGH_SPEED)&&files().length==0,"Simulation created charge data");
                require(service.setSimulation(false),"Could not exit preview");
            });settle();screenshot("developer");
            // Create only a low-level journal, exactly as the existing phone-recorder fixture does.
            // This tests service policy/Stop plumbing while the physical-start guard above remains enforced.
            main(()->{
                seedActive(profile);setField(service,"visible",false);setField(service,"backgroundDeadlineMs",0L);
                require(service.workAllowed(),"Explicit capture not admitted by background work policy");setField(service,"visible",true);
                require(!service.setSimulation(true),"Simulation interrupted a real journal");
                service.startRide("must not start");require(!service.isRecording(),"Ride started during charge capture");
                require(!service.setChargeCharger(ChargeSessionStore.Charger.STOCK),"Charger changed mid-session");
                Notification notice=(Notification)invoke(service,"notification",new Class<?>[]{});
                require("Charge recording is active".contentEquals(notice.extras.getCharSequence(Notification.EXTRA_TITLE)),"Notification lost capture state");
                require(notice.actions!=null&&notice.actions.length==1&&"Stop charge recording".contentEquals(notice.actions[0].title),"Notification lacks Stop capture action");
                // Postpone the unrelated disconnected-frame handler so the notification action,
                // rather than automatic disconnect cleanup, must close this synthetic journal.
                setField(service,"chargeUpdating",true);
                service.visible(false);
                require(((android.os.PowerManager.WakeLock)field(service,"wakeLock")).isHeld(),"Capture did not acquire its wake lock immediately");
                require((notice.flags&Notification.FLAG_ONGOING_EVENT)!=0,"Capture notification is not ongoing");
                require(service.getForegroundServiceType()==android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,"Charge foreground type changed");
            });
            try{
                getUiAutomation().executeShellCommand("input keyevent 223").close();
                SystemClock.sleep(11000);
                main(()->{
                    require(!getTargetContext().getSystemService(android.os.PowerManager.class).isInteractive(),"Screen-off fixture remained interactive");
                    require(service.isChargeRecording()&&service.workAllowed()&&service.backgroundDeadlineMs()==0,"Screen-off capture hit idle timeout");
                    require(((android.os.PowerManager.WakeLock)field(service,"wakeLock")).isHeld(),"Wake lock lost with screen off");
                    setField(service,"wakeLockRenewedMs",SystemClock.elapsedRealtime()-6*60*1000L);
                    invoke(service,"updateRecordingWakeLock",new Class<?>[]{});
                    require(SystemClock.elapsedRealtime()-(Long)field(service,"wakeLockRenewedMs")<1000,"Long-running capture did not renew before expiry");
                });
            }finally{getUiAutomation().executeShellCommand("input keyevent 224").close();getUiAutomation().executeShellCommand("wm dismiss-keyguard").close();}
            main(()->{
                Notification notice=(Notification)invoke(service,"notification",new Class<?>[]{});
                try{notice.actions[0].actionIntent.send();}catch(Exception e){throw new IllegalStateException(e);}
            });await(()->!service.isChargeRecording(),"Stop notification did not end capture");
            main(()->{
                require(service.chargeCaptureStatus().equals("Charge recording saved"),"Notification action did not perform the manual stop path");
                setField(service,"chargeUpdating",false);
                seedActive(profile);service.visible(false);
                require(!service.isChargeRecording(),"Disconnected journal stayed active in background");
                require(!service.workAllowed()&&!((android.os.PowerManager.WakeLock)field(service,"wakeLock")).isHeld(),"Stopped charge kept background work or wake lock");
                service.visible(true);
                seedActive(profile);ScooterProfiles.Profile second=service.profiles.create("Second synthetic","111111111111",0);
                invoke(service,"changed",new Class<?>[]{});
                require(!service.isChargeRecording()&&service.chargeCaptureStatus().contains("profile changed"),"Profile switch did not stop capture");
                require(service.chargeCharger()==null,"New scooter inherited the other charger selection");service.profiles.select(profile);
            });
            main(()->{seedActive(profile);activity.finish();getTargetContext().stopService(new Intent(getTargetContext(),ScooterService.class));});
            ScooterService ended=service;await(()->Boolean.TRUE.equals(field(ended,"destroyed")),"Service did not end");
            require(!store(ended).active(),"Service end left capture active");
            for(File file:files())require(!file.getName().endsWith(".active"),"Graceful process end left an open journal");
            activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(()->activity.service()!=null&&activity.service()!=ended,"Service did not restart");service=activity.service();
            await(()->!Boolean.TRUE.equals(field(service,"chargeModelLoading")),"Restarted model did not load");
            main(()->{
                require(!service.isChargeRecording(),"Capture silently resumed after recreation");
                require(field(service.ble,"gatt")==null&&field(service.ble,"scanner")==null,"Fixture touched real Bluetooth transport");
                require(!service.isRecording()&&!service.phoneSaving()&&field(service,"phoneRecorder")==null,"Charge fixture started sensors or rides");
            });
            AtomicReference<Boolean> exported=new AtomicReference<>(false);AtomicReference<File> exportFile=new AtomicReference<>();
            main(()->require(service.exportChargeRecordings(file->{exportFile.set(file);exported.set(true);}),"Export action refused saved journals"));
            await(exported::get,"Export callback missing");
            require(exportFile.get()!=null&&exportFile.get().isFile(),"JSON export file missing");
            String json;
            try(BufferedReader reader=new BufferedReader(new FileReader(exportFile.get()))){StringBuilder data=new StringBuilder();String line;while((line=reader.readLine())!=null)data.append(line);json=data.toString();}
            org.json.JSONObject document=new org.json.JSONObject(json);
            require(document.getString("format").equals("navee-charge-recordings-v1"),"Export schema invalid");
            require(document.getString("charger_selection").equals("manual"),"Export implies automatic charger detection");
            org.json.JSONArray sessions=document.getJSONArray("sessions");require(sessions.length()>0,"Saved journals absent from export");
            for(int index=0;index<sessions.length();index++){
                org.json.JSONObject session=sessions.getJSONObject(index);
                require(session.getString("profile_id").equals(profile),"Unrelated scooter leaked into export");
                org.json.JSONObject sample=session.getJSONArray("samples").getJSONObject(0);
                require(sample.getDouble("current_a")==-2&&sample.getDouble("power_w")==-104&&sample.getDouble("voltage_v")==52,"Export changed recorded electrical values");
                require(sample.isNull("temperature_c")&&sample.getBoolean("gap_before"),"Export changed unknown temperature or gap");
            }
            require(!json.contains("owner_identity")&&!json.contains("000000000000"),"Owner identity leaked into export");
            AtomicReference<Boolean> cleared=new AtomicReference<>(false);
            main(()->require(service.clearChargeRecordings(()->cleared.set(true)),"Charge clear refused idle capture"));
            await(cleared::get,"Charge clear callback missing");require(files().length==0,"Per-profile clear left charge files");
            result.putString("result","PASS");
        }catch(Throwable error){result.putString("result","FAIL");result.putString("error",error.toString());error.printStackTrace();try{if(activity!=null)screenshot("failure");}catch(Throwable ignored){}}
        finally{result.putInt("assertions",checks);result.putBoolean("real_bluetooth_capture_tested",false);if(activity!=null)main(()->{activity.finish();getTargetContext().stopService(new Intent(getTargetContext(),ScooterService.class));});finish("PASS".equals(result.getString("result"))?0:1,result);}
    }
    private void admit(){
        require(Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish")||Build.PRODUCT.startsWith("sdk_gphone"),"Refusing physical device");
        for(String name:new String[]{"app_settings","profiles","scooter","pending_documents","onboarding"})require(getTargetContext().getSharedPreferences(name,Context.MODE_PRIVATE).getAll().isEmpty(),"Refusing existing user preferences");
        require(getTargetContext().getFilesDir().listFiles().length==0&&getTargetContext().databaseList().length==0,"Refusing existing user data");
        for(String permission:new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT})require(getTargetContext().checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED,"Nearby permission missing");
        BluetoothManager manager=getTargetContext().getSystemService(BluetoothManager.class);require(manager==null||manager.getAdapter()==null||!manager.getAdapter().isEnabled(),"Disable emulator Bluetooth");
    }
    private void seedActive(String id){try{
        long wall=System.currentTimeMillis(),mono=SystemClock.elapsedRealtime();
        store().start(id,ChargeSessionStore.Charger.HIGH_SPEED,wall,mono);
        store().sample(new ChargeSessionStore.Frame(wall,mono,new ChargeSessionStore.Value(1d,mono,0x72),new ChargeSessionStore.Value(50d,mono,0x72),
                new ChargeSessionStore.Value(52d,mono,0x72),new ChargeSessionStore.Value(-2d,mono,0x72),null));
    }catch(IOException e){throw new IllegalStateException(e);}}
    private ChargeSessionStore store(){return store(service);}
    private ChargeSessionStore store(ScooterService source){return (ChargeSessionStore)field(source,"chargeStore");}
    private File[] files(){File[] entries=new File(getTargetContext().getFilesDir(),"charge-recordings").listFiles();return entries==null?new File[0]:entries;}
    private SharedPreferences settings(){return getTargetContext().getSharedPreferences("app_settings",Context.MODE_PRIVATE);}
    private View root(){return (View)field(activity,"root");}
    private void main(Runnable action){AtomicReference<Throwable> failure=new AtomicReference<>();runOnMainSync(()->{try{action.run();}catch(Throwable error){failure.set(error);}});if(failure.get()!=null)throw new IllegalStateException("UI assertion failed",failure.get());}
    private void settle(){waitForIdleSync();SystemClock.sleep(350);waitForIdleSync();}
    private void await(BooleanSupplier test,String message){long until=SystemClock.uptimeMillis()+10000;while(!test.getAsBoolean()&&SystemClock.uptimeMillis()<until)SystemClock.sleep(40);require(test.getAsBoolean(),message);}
    private void require(boolean condition,String message){checks++;if(!condition)throw new IllegalStateException(message);}
    private Object field(Object target,String name){try{Class<?> type=target.getClass();while(type!=null){try{Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(target);}catch(NoSuchFieldException missing){type=type.getSuperclass();}}throw new NoSuchFieldException(name);}catch(Exception e){throw new IllegalStateException(name,e);}}
    private void setField(Object target,String name,Object value){try{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);}catch(Exception e){throw new IllegalStateException(name,e);}}
    private Object invoke(Object target,String name,Class<?>[] types,Object... values){try{Method method=target.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(target,values);}catch(Exception e){throw new IllegalStateException(name,e);}}
    private void screenshot(String label)throws Exception{File directory=new File(getTargetContext().getExternalFilesDir(null),"charge-validation");require(directory.isDirectory()||directory.mkdirs(),"Screenshot folder unavailable");android.graphics.Bitmap bitmap=getUiAutomation().takeScreenshot();try(FileOutputStream out=new FileOutputStream(new File(directory,label+".png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}}
}
