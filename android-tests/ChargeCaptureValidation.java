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
            verifyDefaultChargeEstimate();
            verifyReferenceLifecycle();
            verifyOrdinaryChargerChoice();
            main(()->{invoke(activity,"select",new Class<?>[]{String.class},"more");invoke(field(activity,"screen"),"selectSection",new Class<?>[]{String.class},"Developer");});settle();
            verifyCaptureConfirmation();
            main(()->{
                View selector=root().findViewWithTag("charge-charger-selector");require(selector!=null,"Charger selector missing");
                require(!root().findViewWithTag("charge-capture-action").isEnabled(),"Disconnected capture action is enabled");
                require(!service.startChargeRecording(ChargeSessionStore.Charger.STOCK)&&!service.isChargeRecording(),"Disconnected start bypassed guard");
                require(!store().active()&&files().length==0,"Denied start created a journal");
                @SuppressWarnings("unchecked") List<TextView> choices=(List<TextView>)field(field(selector,"track"),"choices");
                require(choices.size()==3,"Developer selector is missing Automatic");
                choices.get(2).performClick();require(service.chargeCharger()==ChargeSessionStore.Charger.HIGH_SPEED,"Charger selection did not persist");
                require(settings().getString("charge_charger_"+profile,"").equals("HIGH_SPEED"),"Charger choice leaked to a global preference");
                require(service.setSimulation(true),"Could not enable existing sample preview");service.ble.setSimulationCharging(true);
                require(!service.startChargeRecording(ChargeSessionStore.Charger.HIGH_SPEED)&&files().length==0,"Simulation created charge data");
                choices.get(0).performClick();
                require(service.chargeCharger()==null&&!settings().contains("charge_charger_"+profile),"Automatic did not clear developer override");
                require(service.effectiveChargeCharger()==null,"Simulation classified an automatic charger");
                require(service.setSimulation(false),"Could not exit preview");
                invoke(field(activity,"screen"),"refreshDeveloper",new Class<?>[]{});
                require(service.chargeCharger()==null,"Developer refresh replaced Automatic with Stock");
            });settle();screenshot("developer");
            // Create only a low-level journal, exactly as the existing phone-recorder fixture does.
            // This tests service policy/Stop plumbing while the physical-start guard above remains enforced.
            main(()->{
                seedActive(profile);setField(service,"visible",false);setField(service,"backgroundDeadlineMs",0L);
                require(service.workAllowed(),"Explicit capture not admitted by background work policy");setField(service,"visible",true);
                require(!service.setSimulation(true),"Simulation interrupted a real journal");
                service.startRide("must not start");require(!service.isRecording(),"Ride started during charge capture");
                require(!service.setChargeCharger(ChargeSessionStore.Charger.STOCK),"Charger changed mid-session");
                require(!service.setChargeCharger(null),"Automatic override changed mid-session");
                require(service.chargeCharger()==null&&service.effectiveChargeCharger()==ChargeSessionStore.Charger.HIGH_SPEED,
                        "Capture label overwrote Automatic or failed to supply its confirmed charger");
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
                require(service.chargeCharger()==null&&service.effectiveChargeCharger()==null,"Capture label persisted after recording ended");
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
            verifyRecoveryTransitions();
            main(()->{seedActive(profile);activity.finish();getTargetContext().stopService(new Intent(getTargetContext(),ScooterService.class));});
            ScooterService ended=service;await(()->Boolean.TRUE.equals(field(ended,"destroyed")),"Service did not end");
            require(!store(ended).active(),"Service end left capture active");
            for(File file:files())require(!file.getName().endsWith(".active"),"Graceful process end left an open journal");
            activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(()->activity.service()!=null&&activity.service()!=ended,"Service did not restart");service=activity.service();
            await(()->!Boolean.TRUE.equals(field(service,"chargeModelLoading")),"Restarted model did not load");
            main(()->{
                require(!service.isChargeRecording(),"Capture silently resumed after recreation");
                require(service.chargeCharger()==null&&!settings().contains("charge_charger_"+profile),"Automatic did not survive service recreation");
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
    /** Opening and cancelling the new charger picker must not write setup preferences. */
    private void verifyCaptureConfirmation(){
        main(()->{
            MoreScreen more=(MoreScreen)field(activity,"screen");
            ChargeSessionStore.Charger previous=service.chargeCharger();
            invoke(more,"recordChargeDialog",new Class<?>[]{ScooterService.class},service);
            android.app.AlertDialog dialog=lastDialog(more);
            View selector=dialog.getWindow().getDecorView().findViewWithTag("charge-confirmation-selector");
            require(selector!=null,"Charge confirmation has no charger choice");
            @SuppressWarnings("unchecked") List<TextView> choices=(List<TextView>)field(field(selector,"track"),"choices");
            require(choices.size()==2,"Recording labels must stay explicitly Stock or High-speed");
            require(findText(dialog.getWindow().getDecorView(),"Record with Stock charger?")!=null,"Unknown Automatic capture did not prefill Stock");
            choices.get(1).performClick();
            require(service.chargeCharger()==previous,"Unconfirmed charger choice changed estimates");
            require(findText(dialog.getWindow().getDecorView(),"Record with High-speed charger?")!=null,"Confirmation did not reflect its local choice");
            findText(dialog.getWindow().getDecorView(),"Cancel").performClick();
            require(service.chargeCharger()==previous&&!service.isChargeRecording()&&files().length==0,"Cancelling charge confirmation changed capture state");
            invoke(more,"refreshDeveloper",new Class<?>[]{});
            require(service.chargeCharger()==previous,"Capture dismissal changed estimate preference");
            require(service.setChargeCharger(ChargeSessionStore.Charger.HIGH_SPEED),"Manual capture prefill fixture failed");
            invoke(more,"recordChargeDialog",new Class<?>[]{ScooterService.class},service);
            dialog=lastDialog(more);
            require(findText(dialog.getWindow().getDecorView(),"Record with High-speed charger?")!=null,"Capture did not prefill effective charger");
            findText(dialog.getWindow().getDecorView(),"Cancel").performClick();
            require(service.setChargeCharger(previous),"Could not restore capture preference fixture");

            invoke(more,"recordChargeDialog",new Class<?>[]{ScooterService.class},service);
            dialog=lastDialog(more);
            ScooterProfiles.Profile second=service.profiles.create("Consent target fixture","222222222222",0);
            findText(dialog.getWindow().getDecorView(),"Start recording").performClick();
            require(service.chargeCharger()==null&&!service.isChargeRecording()&&files().length==0,"Stale charge consent affected a different scooter");
            service.profiles.select(profile);service.profiles.remove(second.id);
            require(service.chargeCharger()==previous,"Stale consent changed original scooter estimates");
        });
        await(()->!Boolean.TRUE.equals(field(service,"chargeModelLoading")),"Consent fixture model reload did not finish");
    }
    private android.app.AlertDialog lastDialog(MoreScreen more){
        @SuppressWarnings("unchecked") List<android.app.AlertDialog> dialogs=(List<android.app.AlertDialog>)field(more,"dialogs");
        require(!dialogs.isEmpty(),"Charge confirmation missing");return dialogs.get(dialogs.size()-1);
    }
    private TextView findText(View view,String text){
        if(view instanceof TextView&&text.contentEquals(((TextView)view).getText()))return(TextView)view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){TextView found=findText(group.getChildAt(i),text);if(found!=null)return found;}}
        return null;
    }
    /** Cold/late Automatic stays uncertain; confirmed choices get model defaults without recordings. */
    private void verifyDefaultChargeEstimate(){
        main(()->{
            require(files().length==0&&service.chargeCharger()==null,"Default estimate fixture already has owner training/setup");
            Map<String,TelemetryHistory.Reading> readings=new LinkedHashMap<>();
            String[] keys={"charging_state","voltage_v","current_a","battery_percent","rated_capacity_ah","charge_limit_percent"};
            double[] numbers={1,53.814,-110d/53.814,99,15.3,100};
            for(int i=0;i<keys.length;i++)readings.put(keys[i],new TelemetryHistory.Reading(keys[i],keys[i],"Battery","fixture","",numbers[i],1000,true));
            ScooterProfiles.Profile active=service.profiles.active();String serial=active.serial;
            Object snapshot=field(service,"chargeSnapshot");
            try{
                settings().edit().putBoolean("developer_options",false).commit();setField(service,"chargeSnapshot",null);
                readings.put("battery_percent",new TelemetryHistory.Reading("battery_percent","Battery","Battery","fixture","",95d,1000,true));
                ChargingEstimate.Result late=service.chargingEstimate(readings);
                require(!late.referenceToFull&&!late.learnedToFull&&late.remainingMinutesToFull!=null&&service.effectiveChargeCharger()==null,
                        "Cold late-SOC Automatic guessed a charger instead of using live rate");
                readings.put("battery_percent",new TelemetryHistory.Reading("battery_percent","Battery","Battery","fixture","",99d,1000,true));
                require(service.setChargeCharger(ChargeSessionStore.Charger.STOCK),"Could not select Stock reference");
                ChargingEstimate.Result estimate=service.chargingEstimate(readings);
                require(estimate.referenceToFull&&estimate.referenceToLimit&&!estimate.learnedToFull&&estimate.learnedSessions==0,
                        "Confirmed stock UT5 MAX needs recordings or developer options for its reference estimate");
                require(estimate.referenceCharger==ChargeSessionStore.Charger.STOCK,"Stock reference lost its family");
                require(estimate.remainingMinutesToFull>20&&estimate.remainingMinutesToFull<30,"UT5 MAX default missed its long 99% finish");
                Object dashboard=field(activity,"screen");
                TextView etaValue=new TextView(activity),stateTag=new TextView(activity);
                invoke(dashboard,"showChargeTime",new Class<?>[]{TextView.class,TextView.class,ChargingEstimate.Result.class},etaValue,stateTag,estimate);
                require(etaValue.getText().toString().startsWith("≈")&&stateTag.getVisibility()==View.GONE,"Available estimate is missing or crowded by a source badge");
                Map<String,TelemetryHistory.Reading> uncapped=new LinkedHashMap<>(readings);uncapped.remove("charge_limit_percent");
                ChargingEstimate.Result unknownLimit=service.chargingEstimate(uncapped);
                String[] captions=(String[])invoke(dashboard,"chargeTimeLabels",new Class<?>[]{ChargingEstimate.Result.class,boolean.class},unknownLimit,true);
                for(String caption:captions)require(caption.contains("if uncapped"),"A visible compact charge label omits its unknown-cutoff condition");
                uncapped.put("current_a",new TelemetryHistory.Reading("current_a","Current","Battery","fixture","",-.01,1000,true));
                ChargingEstimate.Result lowCurrent=service.chargingEstimate(uncapped);
                invoke(dashboard,"showChargeTime",new Class<?>[]{TextView.class,TextView.class,ChargingEstimate.Result.class},etaValue,stateTag,lowCurrent);
                require("—".contentEquals(etaValue.getText())&&"Low current".contentEquals(stateTag.getText()),"Low-current UI invents a time or loses its reason");
                readings.put("voltage_v",new TelemetryHistory.Reading("voltage_v","Voltage","Battery","fixture","",54.335,1000,true));
                readings.put("current_a",new TelemetryHistory.Reading("current_a","Current","Battery","fixture","",-48d/54.335,1000,true));
                ChargingEstimate.Result taper=service.chargingEstimate(readings);
                require(taper.referenceToFull&&taper.remainingMinutesToFull>2&&taper.remainingMinutesToFull<9,
                        "Default taper does not progress while SOC stays at 99%");
                active.serial="X9999-SYNTHETIC";
                ChargingEstimate.Result unknown=service.chargingEstimate(readings);
                require(!unknown.referenceToFull&&!unknown.learnedToFull,"Unknown scooter inherited the UT5 MAX reference");
                active.serial=serial;
                require(service.setChargeCharger(ChargeSessionStore.Charger.HIGH_SPEED),"Could not select High-speed reference");
                readings.put("battery_percent",new TelemetryHistory.Reading("battery_percent","Battery","Battery","fixture","",50d,1000,true));
                readings.put("voltage_v",new TelemetryHistory.Reading("voltage_v","Voltage","Battery","fixture","",50d,1000,true));
                readings.put("current_a",new TelemetryHistory.Reading("current_a","Current","Battery","fixture","",-10d,1000,true));
                ChargingEstimate.Result fast=service.chargingEstimate(readings);
                require(fast.referenceToFull&&!fast.learnedToFull&&fast.remainingMinutesToFull!=null&&fast.referenceCharger==ChargeSessionStore.Charger.HIGH_SPEED,
                        "Confirmed high-speed UT5 MAX has no default reference without recordings");
                require(ChargingEstimate.recordingSnapshot(fast).fullSource==ChargeSessionStore.Source.FAST_REFERENCE,
                        "Fast estimate recording lost its source family");
                active.serial="X9999-SYNTHETIC";
                require(!service.chargingEstimate(readings).referenceToFull,"Unknown model inherited high-speed UT5 MAX reference");
                active.serial=serial;
                require(service.setChargeCharger(null),"Could not return to Automatic");
                require(service.effectiveChargeCharger()==null&&!service.chargingEstimate(readings).referenceToFull,
                        "Supplied display readings bypassed authenticated detection");
                readings.put("current_a",readings.get("current_a").stale());
                require(service.chargingEstimate(readings).remainingMinutesToFull==null,"Model default bypassed stale-data gate");
                require(files().length==0&&!service.isChargeRecording(),"Default estimate created a charge recording");
            }finally{
                active.serial=serial;setField(service,"chargeSnapshot",snapshot);
                settings().edit().remove("charge_charger_"+profile).putBoolean("developer_options",true).commit();
            }
        });
    }
    /** The real service must discard temporary power tolerance at lifecycle boundaries. */
    private void verifyReferenceLifecycle(){
        main(()->{
            StockChargeReferenceGate gate=(StockChargeReferenceGate)field(service,"stockChargeReferenceGate");
            require(gate.accepts("fixture",95,95,100,100),"Reference gate fixture did not enter");
            invoke(service,"changed",new Class<?>[]{});
            require(!gate.accepts("fixture",95,94.5,101,101),"Disconnected service retained reference grace without a widget refresh");
            require(gate.accepts("fixture",95,95,102,102),"Reference fixture could not re-enter");
            require(service.setChargeCharger(ChargeSessionStore.Charger.HIGH_SPEED),"Charger change fixture failed");
            require(!gate.accepts("fixture",95,94.5,103,103),"Changing charger retained reference grace");
            require(gate.accepts("fixture",95,95,104,104),"Reference fixture could not re-enter");
            settings().edit().remove("charge_charger_"+profile).commit();
            require(!gate.accepts("fixture",95,94.5,105,105),"Preference restore retained reference grace");
            require(gate.accepts("fixture",95,95,106,106),"Reference fixture could not re-enter");
            require(service.setSimulation(true),"Simulation fixture could not start");
            require(!gate.accepts("fixture",95,94.5,107,107),"Simulation inherited real reference grace");
            require(service.setSimulation(false),"Simulation fixture could not stop");
            require(!gate.accepts("fixture",95,94.5,108,108),"Exiting simulation retained reference grace");
        });
    }
    /** Ordinary preferences work offline without enabling capture or Developer options. */
    private void verifyOrdinaryChargerChoice()throws Exception{
        main(()->{
            settings().edit().putBoolean("developer_options",false).commit();
            invoke(activity,"select",new Class<?>[]{String.class},"controls");
            View row=root().findViewWithTag("estimate-charger-setting");
            require(row!=null&&row.isEnabled(),"Ordinary charger setting missing or requires connection/developer mode");
            require(((TextView)root().findViewWithTag("estimate-charger-value")).getText().toString().equals("Automatic"),"Default Automatic preference is not shown");
            row.performClick();
        });settle();screenshot("charger-choice");
        main(()->{
            chooseEstimateCharger(2);
            require(service.chargeCharger()==ChargeSessionStore.Charger.HIGH_SPEED,"Normal-user high-speed selection did not persist");
            require(settings().getString("charge_charger_"+profile,"").equals("HIGH_SPEED"),"Normal charger selection was not per-profile");
            require(!settings().getBoolean("developer_options",false),"Charger setting enabled Developer options");
            root().findViewWithTag("estimate-charger-setting").performClick();chooseEstimateCharger(0);
            require(service.chargeCharger()==null&&!settings().contains("charge_charger_"+profile),"Normal-user Automatic did not clear the saved override");
            require(((TextView)root().findViewWithTag("estimate-charger-value")).getText().toString().equals("Automatic"),"Cleared override still appears as manual");
            root().findViewWithTag("estimate-charger-setting").performClick();chooseEstimateCharger(1);
            require(service.chargeCharger()==ChargeSessionStore.Charger.STOCK,"Normal-user stock selection did not restore preference");
            require(files().length==0&&!service.isChargeRecording()&&service.ble.pending()==null,"Charger preference started capture or sent a scooter command");
            // A sheet opened before a profile switch must not write the new scooter's preference.
            root().findViewWithTag("estimate-charger-setting").performClick();
            service.profiles.create("Charger preference fixture","222222222222",0);
            chooseEstimateCharger(2);
            require(service.chargeCharger()==null,"Old charger sheet changed a newly selected scooter");
            service.profiles.select(profile);
            require(service.chargeCharger()==ChargeSessionStore.Charger.STOCK,"Profile switch lost original charger preference");
            require(service.effectiveChargeCharger()==ChargeSessionStore.Charger.STOCK,"Manual preference is not the effective charger");
            // Low-level synthetic journal only: the ordinary UI obeys the existing capture guard.
            setField(service,"chargeUpdating",true);seedActive(profile);
            require(service.chargeCharger()==ChargeSessionStore.Charger.STOCK&&service.effectiveChargeCharger()==ChargeSessionStore.Charger.HIGH_SPEED,
                    "Active capture did not use its own confirmed label separately from the saved override");
            invoke(field(activity,"screen"),"refresh",new Class<?>[]{});
            require(!root().findViewWithTag("estimate-charger-setting").isEnabled(),"Normal charger preference remained enabled during capture");
            invoke(activity,"showChargerEstimateChoice",new Class<?>[]{});
            android.app.AlertDialog dialog=(android.app.AlertDialog)field(activity,"controlDialog");
            require(dialog==null||!dialog.isShowing(),"Charger sheet bypassed active-capture guard");
            service.stopChargeRecording();setField(service,"chargeUpdating",false);
            require(service.chargeCharger()==ChargeSessionStore.Charger.STOCK&&service.effectiveChargeCharger()==ChargeSessionStore.Charger.STOCK,
                    "Stopping capture lost the saved manual override");
            require(service.setChargeCharger(null),"Could not reset preference to Automatic");
            settings().edit().putBoolean("developer_options",true).commit();
        });
        await(()->!Boolean.TRUE.equals(field(service,"chargeModelLoading")),"Preference guard model reload did not finish");
        AtomicReference<Boolean> cleared=new AtomicReference<>(false);
        main(()->require(service.clearChargeRecordings(()->cleared.set(true)),"Could not clean synthetic preference guard journal"));
        await(cleared::get,"Preference guard journal cleanup did not finish");
        require(files().length==0,"Preference test left a journal for later tests");
    }
    private void chooseEstimateCharger(int index){
        android.app.AlertDialog dialog=(android.app.AlertDialog)field(activity,"controlDialog");
        require(dialog!=null&&dialog.isShowing(),"Charger preference sheet missing");
        View selector=dialog.getWindow().getDecorView().findViewWithTag("estimate-charger-selector");
        require(selector!=null,"Charger preference choices missing");
        @SuppressWarnings("unchecked") List<TextView> choices=(List<TextView>)field(field(selector,"track"),"choices");
        require(choices.size()==3,"Normal charger selector is missing Automatic");
        choices.get(index).performClick();
    }
    /** Fault injection stays below the real-start gate: no adapter or GATT is enabled. */
    private void verifyRecoveryTransitions(){
        main(()->{
            service.visible(true);setField(service,"chargeUpdating",true);
            seedActive(profile);ChargeRecoveryPolicy policy=recoveryPolicy();setField(service,"chargeRecovery",policy);
            long lost=store().startedMono()+1;
            ChargeRecoveryPolicy.Result waiting=evaluate(policy,lost,false,false,true,null);
            require(!(Boolean)invoke(service,"applyChargeRecoveryResult",new Class<?>[]{ChargeRecoveryPolicy.Result.class},waiting),"Loss unexpectedly admitted readings");
            service.visible(false);
            require(service.isChargeRecording()&&service.workAllowed()&&service.backgroundDeadlineMs()==0,"Recovery lost recording work admission");
            require(((android.os.PowerManager.WakeLock)field(service,"wakeLock")).isHeld(),"Recovery dropped CPU wake lock");
            Notification notice=(Notification)invoke(service,"notification",new Class<?>[]{});
            require(notice.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("reconnecting"),"Recovery notice did not identify reconnecting state");
            require((notice.flags&Notification.FLAG_ONGOING_EVENT)!=0&&notice.actions.length==1&&notice.actions[0].title.toString().equals("Stop charge recording"),"Recovery notice lost persistent Stop action");
            require(store().sampleCount()==1,"Waiting fabricated a sample");
            long resumedAt=lost+20000;
            ChargeSessionStore.Frame resumed=sampleFrame(resumedAt,51,-.2);
            ChargeRecoveryPolicy.Result resumedResult=evaluate(policy,resumedAt,true,true,true,resumed);
            require((Boolean)invoke(service,"applyChargeRecoveryResult",new Class<?>[]{ChargeRecoveryPolicy.Result.class},resumedResult),"Fresh authenticated reconnect failed to resume");
            try{require(store().sample(resumed)&&store().sampleCount()==2,"Reconnect failed to append to the same journal");}catch(IOException e){throw new IllegalStateException(e);}
            require(!policy.recovering()&&service.isChargeRecording(),"Resume ended the journal");
            service.stopChargeRecording();
            try{
                StringWriter out=new StringWriter();store().exportJson(profile,out);
                org.json.JSONArray sessions=new org.json.JSONObject(out.toString()).getJSONArray("sessions");boolean found=false;
                for(int i=0;i<sessions.length();i++){
                    org.json.JSONArray samples=sessions.getJSONObject(i).getJSONArray("samples");
                    if(samples.length()==2&&samples.getJSONObject(1).getDouble("current_a")==-.2){
                        require(samples.getJSONObject(1).getBoolean("gap_before"),"Export bridged the missing charge interval");found=true;
                    }
                }
                require(found,"Resumed single journal missing from export");
            }catch(Exception e){throw new IllegalStateException(e);}
            // A deadline ends the explicit recovery owner, including queued reconnect work.
            service.visible(true);seedActive(profile);policy=recoveryPolicy();setField(service,"chargeRecovery",policy);lost=store().startedMono()+1;
            invoke(service,"applyChargeRecoveryResult",new Class<?>[]{ChargeRecoveryPolicy.Result.class},evaluate(policy,lost,false,false,true,null));
            service.visible(false);setField(service.ble,"wanted",true);setField(service.ble,"state",BleController.State.WAITING);
            invoke(service,"applyChargeRecoveryResult",new Class<?>[]{ChargeRecoveryPolicy.Result.class},evaluate(policy,lost+ChargeRecoveryPolicy.RECOVERY_TIMEOUT_MS,false,false,true,null));
            require(!service.isChargeRecording()&&!service.workAllowed()&&!((android.os.PowerManager.WakeLock)field(service,"wakeLock")).isHeld(),"Timeout retained background charge work");
            require(!Boolean.TRUE.equals(field(service.ble,"wanted"))&&service.ble.state()==BleController.State.DISCONNECTED,"Timeout left reconnect ownership active");
            require(service.chargeCaptureStatus().contains("recovery timeout"),"Timeout reason was lost");
            // The real public Disconnect callback is terminal even if a recovery is pending.
            service.visible(true);seedActive(profile);policy=recoveryPolicy();setField(service,"chargeRecovery",policy);lost=store().startedMono()+1;
            invoke(service,"applyChargeRecoveryResult",new Class<?>[]{ChargeRecoveryPolicy.Result.class},evaluate(policy,lost,false,false,true,null));
            setField(service,"chargeUpdating",false);service.ble.disconnect();
            require(!service.isChargeRecording()&&service.chargeCaptureStatus().equals("Charge recording saved"),"Public Disconnect callback did not stop capture intentionally");
            // Stop delivered through onStartCommand must not immediately auto-connect again.
            for(boolean foreground:new boolean[]{true,false}){
                setField(service,"chargeUpdating",true);service.visible(true);seedActive(profile);policy=recoveryPolicy();setField(service,"chargeRecovery",policy);lost=store().startedMono()+1;
                invoke(service,"applyChargeRecoveryResult",new Class<?>[]{ChargeRecoveryPolicy.Result.class},evaluate(policy,lost,false,false,true,null));
                settings().edit().putBoolean("auto_connect",true).putBoolean("background_connection",true).commit();
                service.visible(foreground);setField(service.ble,"wanted",true);setField(service.ble,"state",BleController.State.WAITING);
                service.onStartCommand(new Intent(getTargetContext(),ScooterService.class).setAction("STOP_CHARGE_RECORDING"),0,901);
                require(!service.isChargeRecording()&&!Boolean.TRUE.equals(field(service.ble,"wanted")),"Stop re-enabled auto-connect with foreground="+foreground);
                require(service.ble.state()==BleController.State.DISCONNECTED,"Stop left retry transport active");
                settings().edit().putBoolean("auto_connect",false).putBoolean("background_connection",false).commit();
            }
            service.visible(true);setField(service,"chargeUpdating",false);
            try{
                org.json.JSONObject snapshot=new org.json.JSONObject(service.snapshot());
                require(snapshot.getJSONArray("connection_events").length()>0,"Diagnostic snapshot lacks persisted event stream");
                require(snapshot.has("connection_events_loaded")&&snapshot.has("connection_events_storage_healthy"),"Diagnostic snapshot lacks availability flags");
                String json=snapshot.getJSONArray("connection_events").toString();
                require(!json.contains("000000000000")&&!json.contains("X2538-SYNTHETIC")&&!json.contains(profile),"Diagnostic events leaked owner/radio identity");
            }catch(Exception e){throw new IllegalStateException(e);}
            require(field(service.ble,"gatt")==null&&field(service.ble,"scanner")==null,"Fault injection touched real Bluetooth transport");
        });
    }
    private ChargeRecoveryPolicy recoveryPolicy(){
        return new ChargeRecoveryPolicy(profile,service.profiles.active().serial,store().startedMono(),service.ble.intentionalDisconnectSequence());
    }
    private ChargeRecoveryPolicy.Result evaluate(ChargeRecoveryPolicy policy,long now,boolean connected,boolean ready,boolean retry,ChargeSessionStore.Frame frame){
        return policy.evaluate(now,profile,service.profiles.active().serial,service.ble.intentionalDisconnectSequence(),true,true,connected,ready,retry,
                connected?service.profiles.active().serial:"",frame);
    }
    private ChargeSessionStore.Frame sampleFrame(long mono,double soc,double amps){
        return new ChargeSessionStore.Frame(System.currentTimeMillis(),mono,new ChargeSessionStore.Value(1d,mono,0x72),new ChargeSessionStore.Value(soc,mono,0x72),
                new ChargeSessionStore.Value(52d,mono,0x72),new ChargeSessionStore.Value(amps,mono,0x72),null);
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
