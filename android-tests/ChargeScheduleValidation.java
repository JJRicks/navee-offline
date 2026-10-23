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
    private long savedManualStart, armedDeadline;
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
        verifyOfflinePreference();
        verifyStandaloneMeasureTick();
        main(()->require(service.setSimulation(true),"Simulation did not start"));settle();
        controls();verifyOrdering();verifyUnplugged();verifyDisabledThumb(false);
        controls();main(()->service.ble.setSimulationCharging(true));settle();
        verifyStartupEstimateGuard();
        verifyPickers();verifyStalePicker();verifyMeasureWindow();verifyPlanAndPausedControls();verifyManualAndQuickRefresh();
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
    private void verifyOfflinePreference(){
        main(()->{
            require(!service.ble.ready()&&service.chargeSchedule().timeBlockedReason==null,"Disconnected time preference was blocked");
            invoke(activity,"quickAction",new Class<?>[]{String.class},"charge_done_time");
        });settle();
        main(()->{TimePickerDialog dialog=picker();dialog.updateTime(9,17);dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();});settle();
        main(()->{
            require(service.chargeSchedule().doneByMinuteOfDay==557&&!service.chargeSchedule().doneByEnabled,"Offline preference did not save locally");
            require(settings().contains("charge_schedule_owned_"+profile),"Offline preference did not persist");
            ViewGroup grid=root().findViewWithTag("setting-tiles");require(grid!=null,"Offline time shortcut disappeared");
            boolean found=false;for(int i=0;i<grid.getChildCount();i++){View cell=grid.getChildAt(i);if("charge_done_time".equals(field(field(cell,"entry"),"id"))){found=true;String spoken=String.valueOf(cell.getContentDescription());require(!spoken.contains("Connect")&&spoken.contains("09:17"),"Offline shortcut is incorrectly blocked or stale");}}
            require(found,"Offline time shortcut missing");
            require(!service.ble.ready()&&!service.chargeSchedule().busy,"Offline preference started a connection/transaction");
            settings().edit().remove("charge_schedule_owned_"+profile).commit();
        });
        assertNoRadio();checks.add("Done-by time picker and shortcut save a per-scooter preference offline without radio operations");
    }
    private void verifyOrdering(){main(()->{
        Object manual=control("timed_charging"),start=control("charge_start");
        assertImmediatelyAfter((View)field(manual,"card"),(View)field(field(done(false),"row"),"card"));
        assertImmediatelyAfter((View)field(start,"card"),(View)field(field(done(true),"row"),"card"));
        require("Charge done by".contentEquals(((TextView)field(field(done(true),"row"),"name")).getText()),"Done time uses a different setting label");
    });checks.add("Done-by toggle follows Scheduled charging; done-by time follows Charge start in Scooter > Battery");}
    private void assertImmediatelyAfter(View previous,View next){ViewGroup parent=(ViewGroup)previous.getParent();require(next.getParent()==parent,"Schedule rows have different sections");int i=parent.indexOfChild(previous)+1;while(i<parent.getChildCount()&&parent.getChildAt(i)!=next){View gap=parent.getChildAt(i++);require(!(gap instanceof ViewGroup),"Another control separates related schedule rows");}require(i<parent.getChildCount(),"Done-by setting is not immediately after its matching schedule setting");}
    private void verifyUnplugged(){main(()->{
        ChargeScheduleController.View state=service.chargeSchedule();require(!state.chargerConnected&&!state.doneByEnabled,"Unplugged fixture starts armed");
        require("Connect a charger first".equals(state.doneByBlockedReason)&&state.timeBlockedReason==null,"Toggle must require a charger while local time stays editable");
        View timeLine=(View)field(field(done(true),"row"),"line");require(timeLine.isEnabled()&&timeLine.getAlpha()==1f,"Unplugged done-by time is dimmed");
        Switch toggle=(Switch)field(done(false),"toggle");View line=(View)field(field(done(false),"row"),"line");
        require(!toggle.isEnabled()&&!toggle.isClickable()&&line.isEnabled()&&line.getAlpha()<1,"Unplugged toggle is not muted but explainable");
        require(String.valueOf(line.getContentDescription()).contains("Connect a charger first"),"Unplugged accessible reason missing");
        invoke(activity,"quickAction",new Class<?>[]{String.class},"charge_done_by");
        require(!service.chargeSchedule().doneByEnabled,"Unplugged shortcut armed a schedule");
    });checks.add("Unplugged toggle explains charger requirement while the time preference stays editable");}
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
    private void verifyStartupEstimateGuard(){
        main(()->{
            String reason="Wait for charger detection or select a charger";
            require(service.chargerBasis()==ScooterService.ChargerBasis.UNKNOWN,"Startup fixture already knows a charger family");
            require(service.chargingEstimate(service.displayReadings()).remainingMinutesToLimit!=null,"Unknown charger lost the dashboard's current-rate estimate");
            ChargeScheduleController.View state=service.chargeSchedule();
            require(reason.equals(state.doneByBlockedReason)&&state.timeBlockedReason==null,"Unknown startup estimate did not block only the done-by switch");
            AtomicReference<String> result=new AtomicReference<>();
            service.setChargeDoneByEnabled(true,result::set);
            require(reason.equals(result.get())&&!service.chargeSchedule().busy&&!service.chargeSchedule().doneByEnabled,"Startup estimate attempted to arm a schedule");
            require(service.setChargeCharger(ChargeSessionStore.Charger.STOCK),"Could not select the simulated standard charger");
            require(service.chargeSchedule().doneByBlockedReason==null,"Manual charger choice did not release the startup guard");
        });settle();
        checks.add("Automatic startup keeps the dashboard rate estimate but requires charger detection or an explicit choice before arming done-by");
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
    /** Measure-then-schedule: bounded, visible, cancellable, and never left unarmed without a reason. */
    private void verifyMeasureWindow() throws Exception {
        long sixHours=6*60*60_000L;
        main(()->require(!service.chargeMeasureOffered(),"Simulation has no real charge age, so measuring must not be offered"));
        // Offer dialog: if measuring became impossible meanwhile, Measure first still schedules.
        main(()->invoke(activity,"offerChargeMeasure"));settle();
        main(()->{AlertDialog dialog=(AlertDialog)field(activity,"controlDialog");require(dialog.isShowing(),"Measure offer did not open");
            String text=texts(dialog.getWindow().getDecorView());
            require(text.contains("about 10 minutes")&&text.contains("20-minute buffer")&&text.contains("even in the background")&&text.contains("notification"),"Offer does not explain the bounded background connection: "+text);
            require("Measure first".contentEquals(dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText())&&"Schedule now".contentEquals(dialog.getButton(AlertDialog.BUTTON_NEGATIVE).getText()),"Offer buttons are not Measure first / Schedule now");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();});
        await(()->service.chargeSchedule().doneByEnabled&&!service.chargeSchedule().busy&&!service.isChargeMeasuring(),"Measure first without a usable window did not fall back to scheduling");
        disableDoneBy();
        checks.add("The measure offer explains the bounded background connection; when measuring cannot run, Measure first still arms the cautious plan");

        main(()->{int minute=service.chargeSchedule().doneByMinuteOfDay;
            require((Boolean)invoke(service,"beginChargeMeasure",new Class<?>[]{int.class,long.class},minute,System.currentTimeMillis()+sixHours),"Window did not start");
            require(service.isChargeMeasuring()&&service.workAllowed()&&service.chargeMeasureMinutesLeft()==12,"Window is not a bounded twelve-minute connection");
            require("Wait for Charge done by to finish measuring".equals(service.dataMaintenanceUnavailable()),"Backup could interrupt a measuring window");
            android.app.Notification notice=(android.app.Notification)invoke(service,"notification");
            require("Measuring charge for Charge done by".equals(String.valueOf(notice.extras.getCharSequence(android.app.Notification.EXTRA_TITLE))),"Notification does not say what Volt is doing");
            require(String.valueOf(notice.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)).startsWith("Sets the schedule within 12 min"),"Notification omits the bound");
            require(notice.actions!=null&&notice.actions.length==1&&"Schedule now".contentEquals(notice.actions[0].title),"Notification lacks Schedule now");
        });
        controls();
        // The row refreshes on the next redraw after the window starts; wait for it rather than racing it.
        try{await(()->String.valueOf(((TextView)field(field(done(false),"row"),"value")).getText()).startsWith("Measuring · 12 min"),"Row does not show measuring");}
        catch(IllegalStateException failed){AtomicReference<String> why=new AtomicReference<>();main(()->why.set("row="+((TextView)field(field(done(false),"row"),"value")).getText()+" measuring="+service.isChargeMeasuring()+" left="+service.chargeMeasureMinutesLeft()));throw new IllegalStateException(failed.getMessage()+": "+why.get());}
        main(()->{Object row=done(false);
            require(((View)field(row,"spinner")).getVisibility()==View.VISIBLE&&((Switch)field(row,"toggle")).getVisibility()==View.GONE,"Measuring row still offers the plain switch");});
        screenshot("measuring");
        main(()->((View)field(field(done(false),"row"),"line")).performClick());settle();
        main(()->{AlertDialog dialog=(AlertDialog)field(activity,"controlDialog");require(dialog.isShowing()&&"Stop measuring".contentEquals(dialog.getButton(AlertDialog.BUTTON_NEGATIVE).getText())
                &&"Schedule now".contentEquals(dialog.getButton(AlertDialog.BUTTON_NEUTRAL).getText())&&"Keep measuring".contentEquals(dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText()),"Measuring dialog choices missing");
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();});settle();
        main(()->require(!service.isChargeMeasuring()&&!service.chargeSchedule().doneByEnabled&&!service.chargeSchedule().busy,"Stop measuring armed something"));
        checks.add("Measuring is bounded to twelve minutes, blocks backup, shows its purpose and a Schedule now action, and the owner's Stop leaves Charge done by off");

        main(()->{require((Boolean)invoke(service,"beginChargeMeasure",new Class<?>[]{int.class,long.class},service.chargeSchedule().doneByMinuteOfDay,System.currentTimeMillis()+sixHours),"Second window did not start");service.scheduleChargeNow();});
        await(()->service.chargeSchedule().doneByEnabled&&!service.chargeSchedule().busy&&!service.isChargeMeasuring(),"Schedule now did not arm the plan");
        disableDoneBy();
        main(()->{Object window=field(service,"chargeMeasure");
            require((Boolean)invoke(service,"beginChargeMeasure",new Class<?>[]{int.class,long.class},service.chargeSchedule().doneByMinuteOfDay,System.currentTimeMillis()+sixHours),"Third window did not start");
            try{Field deadline=ChargeMeasureWindow.class.getDeclaredField("deadlineMono");deadline.setAccessible(true);deadline.setLong(window,SystemClock.elapsedRealtime());}catch(Exception e){throw new IllegalStateException(e);}
            invoke(service,"updateChargeMeasure",new Class<?>[]{long.class},SystemClock.elapsedRealtime());});
        await(()->service.chargeSchedule().doneByEnabled&&!service.chargeSchedule().busy&&!service.isChargeMeasuring(),"Timeout did not arm the cautious plan");
        disableDoneBy();
        main(()->{require((Boolean)invoke(service,"beginChargeMeasure",new Class<?>[]{int.class,long.class},service.chargeSchedule().doneByMinuteOfDay,System.currentTimeMillis()+sixHours),"Fourth window did not start");
            service.ble.setSimulationCharging(false);service.ble.tickSimulation();
            invoke(service,"updateChargeMeasure",new Class<?>[]{long.class},SystemClock.elapsedRealtime());
            require(!service.isChargeMeasuring()&&!service.chargeSchedule().doneByEnabled&&!service.chargeSchedule().busy,"Unplugging while measuring did not end the window unarmed");
            service.ble.setSimulationCharging(true);service.ble.tickSimulation();});settle();
        checks.add("Schedule now and the timeout arm the cautious plan through the same one-shot transaction; unplugging ends the window without arming");
        // A finish time edited to one too close to wait for ends measuring at once. Charging continues
        // under the existing not-enough-time rule; nothing rolls on to tomorrow's deadline.
        AtomicReference<String> edited=new AtomicReference<>("pending");int[] original={0};
        main(()->{original[0]=service.chargeSchedule().doneByMinuteOfDay;require((Boolean)invoke(service,"beginChargeMeasure",new Class<?>[]{int.class,long.class},service.chargeSchedule().doneByMinuteOfDay,System.currentTimeMillis()+sixHours),"Fifth window did not start");
            java.util.Calendar soon=java.util.Calendar.getInstance();soon.add(java.util.Calendar.MINUTE,5);
            service.setChargeDoneByTime(soon.get(java.util.Calendar.HOUR_OF_DAY),soon.get(java.util.Calendar.MINUTE),edited::set);});
        await(()->!"pending".equals(edited.get())&&!service.isChargeMeasuring()&&!service.chargeSchedule().busy,"An earlier finish time did not end measuring at once");
        main(()->{ChargeScheduleController.View plan=service.chargeSchedule();
            require(edited.get()==null&&!plan.doneByEnabled&&!plan.busy,"A too-close finish time armed a plan instead of charging now: result="+edited.get());});
        AtomicReference<String> restored=new AtomicReference<>("pending");main(()->service.setChargeDoneByTime(original[0]/60,original[0]%60,restored::set));
        await(()->!"pending".equals(restored.get())&&!service.chargeSchedule().busy,"Original finish time was not restored");require(restored.get()==null,"Restoring the finish time failed");
        checks.add("Editing the finish time while measuring re-plans at once: a too-close deadline ends measuring and keeps charging instead of arming tomorrow");
    }
    /** Outside simulation, a measuring window with no recording must keep the CPU awake between ticks. */
    private void verifyStandaloneMeasureTick(){
        main(()->{
            require(!service.simulating()&&!service.isRecording()&&!service.isChargeRecording(),"Standalone tick check needs a real, idle service");
            require((Boolean)invoke(service,"beginChargeMeasure",new Class<?>[]{int.class,long.class},service.chargeSchedule().doneByMinuteOfDay,System.currentTimeMillis()+6*60*60_000L),"Standalone window did not start");
            ((Runnable)field(service,"tick")).run();
            require(((android.os.PowerManager.WakeLock)field(service,"wakeLock")).isHeld(),"A standalone measuring tick released its wake lock");
            service.cancelChargeMeasure();
        });settle();
        main(()->require(!service.isChargeMeasuring()&&!service.chargeSchedule().doneByEnabled,"Standalone window did not stop cleanly"));
        checks.add("Outside simulation, a measuring window with no recording keeps its wake lock through a full service tick");
    }
    private void disableDoneBy(){
        AtomicReference<String> response=new AtomicReference<>("pending");main(()->service.setChargeDoneByEnabled(false,response::set));
        await(()->!"pending".equals(response.get())&&!service.chargeSchedule().busy,"Disabling Charge done by did not finish");
        require(response.get()==null&&!service.chargeSchedule().doneByEnabled,"Disabling Charge done by failed: "+response.get());
    }
    private String texts(View view){if(view instanceof TextView)return String.valueOf(((TextView)view).getText());StringBuilder out=new StringBuilder();
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)out.append(texts(((ViewGroup)view).getChildAt(i))).append('\n');return out.toString();}
    private void verifyPlanAndPausedControls() throws Exception {
        AtomicReference<String> initialResponse=new AtomicReference<>("pending");
        main(()->{savedManualStart=Math.floorDiv(System.currentTimeMillis()+2*60*60_000L,60_000L)*60_000L;service.setManualChargeStart(savedManualStart,initialResponse::set);});
        await(()->!"pending".equals(initialResponse.get()),"Initial manual time did not finish");require(initialResponse.get()==null,"Initial manual time failed");
        main(()->invoke(activity,"quickAction",new Class<?>[]{String.class},"charge_done_by"));settle();
        main(()->{ChargeScheduleController.View state=service.chargeSchedule();require(state.doneByEnabled&&!state.manualEnabled&&!state.busy,"Done-by transaction did not finish");require(state.startWallMs>System.currentTimeMillis()&&state.doneByWallMs>state.startWallMs,"Computed charging plan is not in the future");require(Integer.valueOf(1).equals(service.ble.featureValue("timed_charging")),"Done-by did not arm the simulated scooter scheduler");require("Disable Charge done by first".equals(state.manualBlockedReason)&&"Disable Charge done by first".equals(state.manualTimeBlockedReason),"Wrong mutually-exclusive explanation");require(state.manualStartWallMs==savedManualStart,"Done-by overwrote manual time");armedDeadline=state.doneByWallMs;service.ble.setSimulationCharging(false);});settle();
        main(()->{require(service.chargeSchedule().doneByEnabled,"Expected charge pause erased the plan");Switch toggle=(Switch)field(done(false),"toggle");require(toggle.isChecked()&&toggle.isEnabled(),"Paused done-by plan cannot be disabled");require(!((Switch)field(control("timed_charging"),"toggle")).isEnabled(),"Manual switch remained active while done-by armed");});
        main(()->{
            Object row=control("charge_start");String expected=DisplayTime.clock(activity,settings(),savedManualStart);
            require(expected.contentEquals(((TextView)field(row,"value")).getText()),"Locked manual row shows the calculated time instead of the saved manual time");
            require(((View)field(field(row,"row"),"line")).getAlpha()<1,"Manual time was not visually locked");
            invoke(activity,"showChargingTime");Object dialog=field(activity,"controlDialog");require(!(dialog instanceof TimePickerDialog)||!((TimePickerDialog)dialog).isShowing(),"Locked manual time opened a picker");
            invoke(activity,"showChargeDoneTime");
        });settle();
        main(()->{TimePickerDialog dialog=picker();int minute=(service.chargeSchedule().doneByMinuteOfDay+60)%1440;dialog.updateTime(minute/60,minute%60);dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();});settle();
        main(()->{
            ChargeScheduleController.View updated=service.chargeSchedule();
            require(updated.doneByEnabled&&!updated.busy,"Editing the connected paused deadline did not complete");
            require(updated.doneByWallMs==armedDeadline+60*60_000L,"Editing the connected paused deadline did not update the active schedule");
            require(updated.manualStartWallMs==savedManualStart,"Reprogramming the deadline overwrote the manual preference");
            require(Integer.valueOf(1).equals(service.ble.featureValue("timed_charging")),"Updating the deadline left the native schedule disabled");
            armedDeadline=updated.doneByWallMs;
        });
        verifyDisabledThumb(true);screenshot("paused-plan");controls();
        main(()->{AtomicReference<String> response=new AtomicReference<>();service.setManualChargeEnabled(true,response::set);require("Disable Charge done by first".equals(response.get()),"Manual API bypassed done-by exclusion");});
        home();verifyTiles(true);
        main(()->invoke(activity,"showQuickSettings",new Class<?>[]{String.class},"Battery"));settle();
        main(()->{List<?> rows=(List<?>)field(activity,"quickChargeRows");require(rows.size()==2,"Battery quick menu omitted done-by rows");Object toggleRow=rows.get(0);require(((Switch)field(toggleRow,"toggle")).isChecked()&&((Switch)field(toggleRow,"toggle")).isEnabled(),"Paused plan is not actionable in quick menu");((View)field(field(toggleRow,"row"),"line")).performClick();});settle();
        main(()->{require(!service.chargeSchedule().doneByEnabled&&!service.chargeSchedule().manualEnabled&&!service.chargeSchedule().busy,"Quick menu did not disable the paused schedule");require(service.chargeSchedule().startWallMs==savedManualStart&&service.chargeSchedule().manualStartWallMs==savedManualStart,"Disabling did not restore the saved manual time");require(Integer.valueOf(0).equals(service.ble.featureValue("timed_charging")),"Restoring manual time unexpectedly enabled charging schedule");for(Object row:(List<?>)field(activity,"quickChargeRows"))if(!(Boolean)field(row,"time"))require(!((Switch)field(row,"toggle")).isChecked(),"Quick menu retained stale enabled state");invoke(activity,"closeQuickMenu");});settle();
        verifyTiles(false);checks.add("Simulated done-by applies clock/start/enable, reprograms an edited deadline during a verified pause, restores the manual time on cancellation and refreshes quick-menu/tile state");
    }
    private void verifyTiles(boolean armed){main(()->{
        ViewGroup grid=root().findViewWithTag("setting-tiles");require(grid!=null&&grid.getChildCount()==4,"Charging setting tiles missing");
        for(int i=0;i<grid.getChildCount();i++){View cell=grid.getChildAt(i);String id=(String)field(field(cell,"entry"),"id");boolean toggle=id.equals("charge_done_by")||id.equals("feature:timed_charging");require((Boolean)field(cell,"toggle")==toggle,"Wrong power/chevron cue for "+id);require(String.valueOf(cell.getContentDescription()).endsWith(toggle?", toggle":", open"),"Wrong accessible action for "+id);
            if(id.equals("charge_done_by")){if(armed)require(String.valueOf(cell.getContentDescription()).contains(DisplayTime.clock(activity,settings(),armedDeadline)),"Active tile shows next-use preference instead of the armed deadline");require(cell.isSelected()==armed,"Done-by tile state did not refresh");if(!armed)require(String.valueOf(cell.getContentDescription()).contains("Connect a charger first"),"Unplugged tile lost its reason");}
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
