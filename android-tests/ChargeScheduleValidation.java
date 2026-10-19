package local.navee.offlinelock.tests;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.app.TimePickerDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.TimePicker;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import local.navee.offlinelock.*;

/** Disposable empty emulator, radio disabled. All schedule operations use Volt's simulator. */
public final class ChargeScheduleValidation extends Instrumentation {
    private MainActivity activity;
    private ScooterService service;
    private Bundle args;
    private boolean admitted;
    private String profile;
    private int assertions;
    private final ArrayList<String> checks=new ArrayList<>(),screenshots=new ArrayList<>();
    @Override public void onCreate(Bundle arguments){args=arguments==null?new Bundle():arguments;start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        admit();admitted=true;
        require(settings().edit().putBoolean("auto_connect",false).putBoolean("background_connection",false)
                .putBoolean("developer_options",true).putString("theme",args.getString("theme","dark"))
                .putString("time_format","24h").putFloat("text_scale",Float.parseFloat(args.getString("text_scale","1"))).commit(),"Could not seed preferences");
        main(()->{
            ScooterProfiles profiles=new ScooterProfiles(getTargetContext());
            ScooterProfiles.Profile owner=profiles.create("Synthetic charging schedule","000000000000",0);
            owner.serial="X2538-SYNTHETIC";profiles.update(owner);profile=owner.id;
            DashboardConfig config=new DashboardConfig(settings(),profile);config.widgets.clear();
            DashboardConfig.Widget tiles=new DashboardConfig.Widget("shortcuts");tiles.title="Charging controls";
            tiles.quickActions.clear();tiles.quickActions.addAll(Arrays.asList("charge_done_by","charge_done_time","feature:timed_charging","feature:charge_start"));
            config.widgets.add(tiles);config.save();OnboardingScreen.markDone(getTargetContext());
        });
        activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(()->activity.service()!=null&&root().getWidth()>0,"App did not launch");service=activity.service();
        main(()->require(service.setSimulation(true),"Simulation did not start"));settle();
        controls();verifyOrdering();verifyUnplugged();verifyDisabledThumb(false);
        controls();main(()->service.ble.setSimulationCharging(true));settle();
        verifyPickers();verifyStalePicker();verifyPlanAndPausedControls();verifyManualAndQuickRefresh();
        assertNoRadio();
        main(()->require(service.setSimulation(false),"Simulation did not stop"));
        for(String key:settings().getAll().keySet())require(!key.startsWith("charge_schedule_owned_"),"Simulation persisted a charging plan: "+key);
        checks.add("Simulated charging intentions never touch the radio or persist into real scooter preferences");
        result.putString("result","PASS");
    }catch(Throwable failure){result.putString("result","FAIL");result.putString("error",failure.toString());if(activity!=null)try{screenshot("failure");}catch(Throwable ignored){}
    }finally{result.putInt("assertions",assertions);result.putStringArrayList("checks",checks);result.putStringArrayList("screenshots",screenshots);result.putBoolean("physical_device_tested",false);result.putInt("scooter_mutations",0);
        if(admitted&&activity!=null)try{main(()->{if(service!=null)service.setSimulation(false);activity.finish();ScooterService.setUiVisible(false);getTargetContext().stopService(new Intent(getTargetContext(),ScooterService.class));});}catch(Throwable ignored){}
        finish("PASS".equals(result.getString("result"))?0:1,result);
    }}
    private void verifyOrdering(){main(()->{
        Object manual=control("timed_charging"),start=control("charge_start");
        assertImmediatelyAfter((View)field(manual,"card"),(View)field(field(done(false),"row"),"card"));
        assertImmediatelyAfter((View)field(start,"card"),(View)field(field(done(true),"row"),"card"));
        require("Charge done by".contentEquals(((TextView)field(field(done(true),"row"),"name")).getText()),"Done time uses a different setting label");
    });checks.add("Done-by toggle follows Scheduled charging; done-by time follows Charge start in Scooter > Battery");}
    private void assertImmediatelyAfter(View previous,View next){ViewGroup parent=(ViewGroup)previous.getParent();require(next.getParent()==parent,"Schedule rows have different sections");int i=parent.indexOfChild(previous)+1;while(i<parent.getChildCount()&&parent.getChildAt(i)!=next){View gap=parent.getChildAt(i++);require(!(gap instanceof ViewGroup),"Another control separates related schedule rows");}require(i<parent.getChildCount(),"Done-by setting is not immediately after its matching schedule setting");}
    private void verifyUnplugged(){main(()->{
        ChargeScheduleController.View state=service.chargeSchedule();require(!state.chargerConnected&&!state.doneByEnabled,"Unplugged fixture starts armed");
        require("Connect a charger first".equals(state.doneByBlockedReason)&&"Connect a charger first".equals(state.timeBlockedReason),"Wrong unplugged explanation");
        Switch toggle=(Switch)field(done(false),"toggle");View line=(View)field(field(done(false),"row"),"line");
        require(!toggle.isEnabled()&&!toggle.isClickable()&&line.isEnabled()&&line.getAlpha()<1,"Unplugged toggle is not muted but explainable");
        require(String.valueOf(line.getContentDescription()).contains("Connect a charger first"),"Unplugged accessible reason missing");
        invoke(activity,"quickAction",new Class<?>[]{String.class},"charge_done_by");
        require(!service.chargeSchedule().doneByEnabled,"Unplugged shortcut armed a schedule");
    });checks.add("Unplugged done-by time and toggle are dimmed, with the exact Connect a charger first explanation");}
    private void verifyDisabledThumb(boolean manual)throws Exception{
        AtomicInteger taps=new AtomicInteger();AtomicReference<Switch> target=new AtomicReference<>();
        main(()->{
            Object row=manual?control("timed_charging"):done(false);
            Switch toggle=(Switch)field(row,"toggle");ViewGroup line=(ViewGroup)field(field(row,"row"),"line");
            require(!toggle.isEnabled()&&!toggle.isClickable(),"Disabled switch can consume a click");
            // Rebuilt by controls() after this probe; replace only the explanatory callback.
            line.setOnClickListener(v->taps.incrementAndGet());target.set(toggle);
            line.requestRectangleOnScreen(new Rect(0,-activity.ui().dp(110),line.getWidth(),line.getHeight()+activity.ui().dp(110)),true);
        });settle();
        int[] at=new int[2];main(()->{
            Switch toggle=target.get();Rect visible=new Rect();
            require(toggle.getGlobalVisibleRect(visible)&&visible.width()>=toggle.getWidth()&&visible.height()>=toggle.getHeight(),"Disabled switch thumb is not fully visible for a real tap");
            toggle.getLocationOnScreen(at);at[0]+=toggle.getWidth()/2;at[1]+=toggle.getHeight()/2;
        });
        screenshot(manual?"blocked-manual-row":"unplugged-done-by-row");
        long when=SystemClock.uptimeMillis();
        MotionEvent down=MotionEvent.obtain(when,when,MotionEvent.ACTION_DOWN,at[0],at[1],0);
        MotionEvent up=MotionEvent.obtain(when,when+60,MotionEvent.ACTION_UP,at[0],at[1],0);
        try{sendPointerSync(down);SystemClock.sleep(60);sendPointerSync(up);}finally{down.recycle();up.recycle();}
        // View may post performClick from ACTION_UP: observe after the UI queue has run it.
        await(()->taps.get()==1,"Disabled switch thumb swallowed the parent row explanation");settle();
        require(taps.get()==1,"Disabled switch thumb delivered more than one parent row action");
        checks.add((manual?"Blocked Scheduled charging":"Unplugged done-by")+" switch thumb touch reaches its explanatory row action");
    }
    private void verifyPickers(){
        for(boolean is24:new boolean[]{true,false}){
            main(()->settings().edit().putString("time_format",is24?"24h":"12h").commit());
            for(String method:new String[]{"showChargingTime","showChargeDoneTime"}){
                main(()->invoke(activity,method));settle();
                main(()->{TimePickerDialog dialog=picker();TimePicker time=find(dialog.getWindow().getDecorView(),TimePicker.class);require(time!=null&&time.is24HourView()==is24,"Charge start/done-by pickers disagree with time preference");dialog.dismiss();});settle();
            }
        }
        main(()->{settings().edit().putString("time_format","24h").commit();invoke(activity,"showChargeDoneTime");});settle();
        final int[] chosen=new int[1];main(()->{Calendar deadline=Calendar.getInstance();deadline.add(Calendar.HOUR_OF_DAY,8);chosen[0]=deadline.get(Calendar.HOUR_OF_DAY)*60+deadline.get(Calendar.MINUTE);TimePickerDialog dialog=picker();dialog.updateTime(chosen[0]/60,chosen[0]%60);dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();});settle();
        main(()->require(service.chargeSchedule().doneByMinuteOfDay==chosen[0],"Time picker did not save the selected deadline"));
        checks.add("Both native time pickers follow 12/24-hour preference; done-by saves the selected time");
    }
    private void verifyStalePicker(){
        for(String method:new String[]{"showChargeDoneTime","showChargingTime"}){
            final int[] prior=new int[1];final long[] start=new long[1];
            main(()->{prior[0]=service.chargeSchedule().doneByMinuteOfDay;start[0]=service.chargeSchedule().startWallMs;invoke(activity,method);});settle();
            main(()->{TimePickerDialog dialog=picker();ScooterProfiles.Profile other=service.profiles.create("Other synthetic scooter","000000000001",0);other.serial="X2538-OTHER";service.profiles.update(other);dialog.updateTime(7,23);dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();service.profiles.select(profile);});settle();
            main(()->require(service.chargeSchedule().doneByMinuteOfDay==prior[0]&&service.chargeSchedule().startWallMs==start[0],"Old time picker changed a newly selected scooter"));
        }
        checks.add("Changing scooters before accepting either time picker prevents the stale action");
    }
    private void verifyPlanAndPausedControls() throws Exception {
        main(()->invoke(activity,"quickAction",new Class<?>[]{String.class},"charge_done_by"));settle();
        main(()->{ChargeScheduleController.View state=service.chargeSchedule();require(state.doneByEnabled&&!state.manualEnabled&&!state.busy,"Done-by transaction did not finish");require(state.startWallMs>System.currentTimeMillis()&&state.doneByWallMs>state.startWallMs,"Computed charging plan is not in the future");require(Integer.valueOf(1).equals(service.ble.featureValue("timed_charging")),"Done-by did not arm the simulated scooter scheduler");require("Disable Charge done by first".equals(state.manualBlockedReason),"Wrong mutually-exclusive explanation");service.ble.setSimulationCharging(false);});settle();
        main(()->{require(service.chargeSchedule().doneByEnabled,"Expected charge pause erased the plan");Switch toggle=(Switch)field(done(false),"toggle");require(toggle.isChecked()&&toggle.isEnabled(),"Paused done-by plan cannot be disabled");require(!((Switch)field(control("timed_charging"),"toggle")).isEnabled(),"Manual switch remained active while done-by armed");});
        verifyDisabledThumb(true);screenshot("paused-plan");controls();
        main(()->{AtomicReference<String> response=new AtomicReference<>();service.setManualChargeEnabled(true,response::set);require("Disable Charge done by first".equals(response.get()),"Manual API bypassed done-by exclusion");});
        home();verifyTiles(true);
        main(()->invoke(activity,"showQuickSettings",new Class<?>[]{String.class},"Battery"));settle();
        main(()->{List<?> rows=(List<?>)field(activity,"quickChargeRows");require(rows.size()==2,"Battery quick menu omitted done-by rows");Object toggleRow=rows.get(0);require(((Switch)field(toggleRow,"toggle")).isChecked()&&((Switch)field(toggleRow,"toggle")).isEnabled(),"Paused plan is not actionable in quick menu");((View)field(field(toggleRow,"row"),"line")).performClick();});settle();
        main(()->{require(!service.chargeSchedule().doneByEnabled,"Quick menu did not disable the paused schedule");for(Object row:(List<?>)field(activity,"quickChargeRows"))if(!(Boolean)field(row,"time"))require(!((Switch)field(row,"toggle")).isChecked(),"Quick menu retained stale enabled state");invoke(activity,"closeQuickMenu");});settle();
        verifyTiles(false);checks.add("Simulated done-by applies clock/start/enable, stays cancellable during pause, excludes manual scheduling and refreshes quick-menu/tile state");
    }
    private void verifyTiles(boolean armed){main(()->{
        ViewGroup grid=root().findViewWithTag("setting-tiles");require(grid!=null&&grid.getChildCount()==4,"Charging setting tiles missing");
        for(int i=0;i<grid.getChildCount();i++){View cell=grid.getChildAt(i);String id=(String)field(field(cell,"entry"),"id");boolean toggle=id.equals("charge_done_by")||id.equals("feature:timed_charging");require((Boolean)field(cell,"toggle")==toggle,"Wrong power/chevron cue for "+id);require(String.valueOf(cell.getContentDescription()).endsWith(toggle?", toggle":", open"),"Wrong accessible action for "+id);
            if(id.equals("charge_done_by")){require(cell.isSelected()==armed,"Done-by tile state did not refresh");if(!armed)require(String.valueOf(cell.getContentDescription()).contains("Connect a charger first"),"Unplugged tile lost its reason");}
        }
    });}
    private void verifyManualAndQuickRefresh(){controls();
        AtomicReference<String> response=new AtomicReference<>("pending");
        main(()->service.setManualChargeStart(System.currentTimeMillis()+3*60*60_000L,response::set));
        await(()->!"pending".equals(response.get()),"Manual start transaction did not complete");
        require(response.get()==null,"Manual start transaction failed: "+response.get());
        response.set("pending");main(()->service.setManualChargeEnabled(true,response::set));
        await(()->!"pending".equals(response.get()),"Manual enable transaction did not complete");
        require(response.get()==null,"Manual enable transaction failed: "+response.get());
        main(activity::redraw);settle();
        main(()->{require(service.chargeSchedule().manualEnabled,"Manual schedule did not enable while unplugged");require(((Switch)field(control("timed_charging"),"toggle")).isChecked(),"Scooter setting did not show manual schedule");require(!"Not scheduled".contentEquals(((TextView)field(control("charge_start"),"value")).getText()),"Scheduled start time was not refreshed");});
        response.set("pending");main(()->service.setManualChargeEnabled(false,response::set));
        await(()->!"pending".equals(response.get()),"Manual disable transaction did not complete");
        require(response.get()==null,"Manual disable transaction failed: "+response.get());settle();
        checks.add("Existing manual charging remains usable without a connected charger and refreshes its switch/start-time rows");
    }
    private void controls(){main(()->{settings().edit().putString("scooter_section","").commit();invoke(activity,"select",new Class<?>[]{String.class},"home");});settle();main(()->invoke(activity,"select",new Class<?>[]{String.class},"controls"));settle();main(()->{service.ble.tickSimulation();activity.redraw();});settle();}
    private void home(){main(()->invoke(activity,"select",new Class<?>[]{String.class},"home"));settle();}
    private Object control(String key){for(Object row:(List<?>)field(field(activity,"screen"),"rows"))if(((ScooterCommands.Feature)field(row,"feature")).key.equals(key))return row;throw new IllegalStateException("Missing "+key);}
    private Object done(boolean time){for(Object row:(List<?>)field(field(activity,"screen"),"chargeRows"))if((Boolean)field(row,"time")==time)return row;throw new IllegalStateException("Missing done-by row");}
    private TimePickerDialog picker(){Object dialog=field(activity,"controlDialog");require(dialog instanceof TimePickerDialog&&((TimePickerDialog)dialog).isShowing(),"Native time picker missing");return (TimePickerDialog)dialog;}
    private <T> T find(View view,Class<T> type){if(type.isInstance(view))return type.cast(view);if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){T found=find(((ViewGroup)view).getChildAt(i),type);if(found!=null)return found;}return null;}
    private void admit(){String hw=Build.HARDWARE.toLowerCase(Locale.ROOT);require(hw.contains("ranchu")||hw.contains("goldfish")||Build.PRODUCT.startsWith("sdk_gphone"),"Refusing physical phone");for(String name:new String[]{"app_settings","profiles","scooter","pending_documents","control_evidence"})require(prefs(name).getAll().isEmpty(),"Refusing existing preferences");File[] files=getTargetContext().getFilesDir().listFiles();require(files!=null&&files.length==0&&getTargetContext().databaseList().length==0,"Refusing existing app data");for(String permission:new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT})require(getTargetContext().checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED,"Grant emulator Nearby permission");BluetoothManager manager=getTargetContext().getSystemService(BluetoothManager.class);BluetoothAdapter adapter=manager==null?null:manager.getAdapter();require(adapter==null||!adapter.isEnabled(),"Disable emulator Bluetooth first");}
    private void assertNoRadio(){main(()->{for(String name:new String[]{"gatt","writer","scanner","mutation"})require(field(service.ble,name)==null,"Simulator touched "+name);require(((Collection<?>)field(service.ble,"queue")).isEmpty(),"Simulator queued radio bytes");for(String name:new String[]{"scanning","writing","wanted","challengePending","pinReadPending"})require(!Boolean.TRUE.equals(field(service.ble,name)),"Simulator started "+name);require(!service.isRecording()&&!service.isChargeRecording()&&!service.phoneSaving(),"Schedule UI started recording");});}
    private SharedPreferences settings(){return prefs("app_settings");}private SharedPreferences prefs(String name){return getTargetContext().getSharedPreferences(name,Context.MODE_PRIVATE);}private View root(){return (View)field(activity,"root");}
    private void settle(){waitForIdleSync();SystemClock.sleep(160);waitForIdleSync();}
    private void await(BooleanSupplier condition,String message){long end=SystemClock.uptimeMillis()+10000;while(SystemClock.uptimeMillis()<end){AtomicReference<Boolean> ready=new AtomicReference<>();main(()->ready.set(condition.getAsBoolean()));if(Boolean.TRUE.equals(ready.get()))return;SystemClock.sleep(40);}throw new IllegalStateException(message);}
    private void main(Runnable action){AtomicReference<Throwable> failure=new AtomicReference<>();runOnMainSync(()->{try{action.run();}catch(Throwable error){failure.set(error);}});if(failure.get()!=null)throw new IllegalStateException(failure.get().getMessage(),failure.get());}
    private void require(boolean ok,String message){assertions++;if(!ok)throw new IllegalStateException(message);}
    private Object field(Object target,String name){try{Class<?> type=target.getClass();while(type!=null){try{Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(target);}catch(NoSuchFieldException missing){type=type.getSuperclass();}}throw new NoSuchFieldException(name);}catch(Exception error){throw new IllegalStateException("Field "+name,error);}}
    private Object invoke(Object target,String name){return invoke(target,name,new Class<?>[0]);}private Object invoke(Object target,String name,Class<?>[] types,Object... values){try{Method method=target.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(target,values);}catch(Exception error){throw new IllegalStateException("Method "+name,error);}}
    private void screenshot(String label)throws Exception{File dir=new File(getTargetContext().getExternalFilesDir(null),"charge-schedule-validation");require(dir.isDirectory()||dir.mkdirs(),"Screenshot directory missing");File file=new File(dir,label+".png");android.graphics.Bitmap bitmap=getUiAutomation().takeScreenshot();require(bitmap!=null,"Screenshot unavailable");try(FileOutputStream out=new FileOutputStream(file)){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}screenshots.add(file.getAbsolutePath());}
}
