package com.jjricks.volt.tests;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.util.function.BooleanSupplier;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import com.jjricks.volt.*;
import org.json.JSONObject;

/**
 * Two-phase exact Clear storage journey on a disposable emulator only.
 * 1. Fresh app: -e mode export. Pull archive_path to the host.
 * 2. pm clear target app, push the saved archive back to archive_path.
 * 3. -e mode restore-check. No Nearby/location grants or scooter service are needed.
 * Legacy: pm clear, push tests/fixtures/legacy/full-v1.voltbackup as legacy-v1.voltbackup,
 * then -e mode legacy-v1 restores the Volt 1.3.1 file with Replace everything.
 */
public final class FullBackupJourneyValidation extends Instrumentation {
    private static final String[] PREFS={"app_settings","profiles","scooter","control_evidence","onboarding"};
    private static final char[] PASSWORD="synthetic clear-storage journey".toCharArray();
    private static final String DIGEST="fixture_expected_preferences_digest";
    // Synthetic, non-empty C1 profile fields: the round trip must keep each exactly, not fall back to defaults.
    private static final String C1_PID="24431";private static final long C1_PID_WALL=1700000000000L,C1_FW_WALL=1700000000001L;
    private static final String[] C1_FW={"2.0.1.2","1.4.0.7","3.1","0.9.2","u1"}; // meter, controller, BMS, screen, UWB
    /** Device and pending keys planted before export; a backup never carries them. */
    private static final String[] EXCLUDED={"app_settings/last_page","app_settings/more_away_boot","app_settings/owner_import_applied","app_settings/notification_prompted","onboarding/nearby_asked"};
    /** Real launch creates these target-local navigation keys; exact restore intentionally retains them. */
    private static final String[] LOCAL_NAVIGATION={"more_section","stats_section"};
    private String mode;private int assertions;private Context app;private Activity current;
    @Override public void onCreate(Bundle args){mode=args==null?null:args.getString("mode");start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try {
            app=getTargetContext();admitFresh();
            File external=app.getExternalFilesDir(null);require(external!=null,"External document fixture storage is unavailable");
            File archive=new File(external,"backup-journey.voltbackup");
            if("export".equals(mode)) {
                require(!archive.exists(),"Refusing to overwrite an existing external fixture archive");
                populate();
                try(FileOutputStream out=new FileOutputStream(archive)){AppDataBackup.write(app,out,PASSWORD);out.getFD().sync();}
                require(archive.length()>0,"Encrypted backup is empty");
                result.putString("phase","EXPORTED");result.putLong("archive_bytes",archive.length());
            } else if("restore-check".equals(mode)) {
                require(archive.isFile(),"Push the previously exported archive back after clearing storage");
                int files=restoreFromWelcome(archive);verifyRestored();
                result.putString("phase","RESTORED_FROM_ONBOARDING_AFTER_CLEAR_STORAGE");result.putInt("restored_files",files);
            } else if("legacy-v1".equals(mode)) {
                File legacy=new File(external,"legacy-v1.voltbackup");require(legacy.isFile(),"Push tests/fixtures/legacy/full-v1.voltbackup after clearing storage");
                AppDataBackup.PreparedRestore prepared;
                try(InputStream in=new FileInputStream(legacy)){prepared=AppDataBackup.stage(app,in,"synthetic fixture password".toCharArray());}
                require("v1".equals(prepared.format)&&prepared.complete&&prepared.categories.size()==7,"A 1.3.1 full backup is a complete backup");
                AppDataBackup.apply(app,prepared);verifyLegacy();
                result.putString("phase","LEGACY_V1_RESTORED");
            } else if("owner-only".equals(mode)) {
                populate();restoreOwnerOnly(false,false);result.putString("phase","OWNER_FILE_MERGED_WITHOUT_DATA_LOSS");
            } else if("owner-existing".equals(mode)) {
                populate();restoreOwnerOnly(true,false);result.putString("phase","HELPER_OWNER_FILE_EXPLICITLY_REUSED_CONNECTED_PROFILE");
            } else if("owner-another".equals(mode)) {
                populate();restoreOwnerOnly(true,true);result.putString("phase","HELPER_OWNER_FILE_ADDED_SECOND_SAME_ACCOUNT_SCOOTER");
            } else throw new IllegalArgumentException("Use mode export, restore-check, legacy-v1, owner-only, owner-existing or owner-another");
            result.putString("result","PASS");result.putString("archive_path",archive.getAbsolutePath());
            result.putBoolean("nearby_granted",app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED);
            result.putInt("scooter_mutations",0);
        } catch(Throwable failure){result.putString("result","FAIL");result.putString("error",failure.getClass().getSimpleName()+": "+failure.getMessage());}
        finally{if(current!=null)try{runOnMainSync(current::finish);}catch(Throwable ignored){}}
        result.putInt("assertions",assertions);result.putString("fixture","Disposable fresh emulator; real app directories and preferences; synthetic credentials/readings; no service, Bluetooth or sensors");
        finish("PASS".equals(result.getString("result"))?0:1,result);
    }
    /** Exercise the actual first-run entry, picker, password and approval instead of bypassing UI. */
    private int restoreFromWelcome(File archive)throws Exception {
        if(Build.VERSION.SDK_INT>=31)require(app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED
                &&app.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED,"Restore journey must start without Nearby permission");
        ActivityMonitor mainMonitor=addMonitor(MainActivity.class.getName(),null,false);
        MainActivity launched=(MainActivity)startActivitySync(new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));current=launched;
        long until=SystemClock.elapsedRealtime()+8000;
        while(Boolean.TRUE.equals(field(launched,"startupRedirect"))&&SystemClock.elapsedRealtime()<until){
            Activity replacement=waitForMonitorWithTimeout(mainMonitor,500);
            if(replacement instanceof MainActivity){launched=(MainActivity)replacement;current=launched;}
        }
        removeMonitor(mainMonitor);final MainActivity main=launched;
        await(()->view(main,"onboarding-restore-backup")!=null,"Welcome restore entry missing");
        require(main.service()==null&&!prefs("onboarding").getBoolean("onboarding_done",false),"Restore required pairing or prematurely completed onboarding");
        click(main,"onboarding-restore-backup");
        AlertDialog consent=(AlertDialog)field(main,"controlDialog");
        require(consent!=null&&consent.isShowing()&&!DataMaintenance.active(),"Restore consent was bypassed");
        ActivityMonitor backupMonitor=addMonitor(BackupActivity.class.getName(),null,false);
        runOnMainSync(()->consent.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        Activity opened=waitForMonitorWithTimeout(backupMonitor,10000);removeMonitor(backupMonitor);
        require(opened instanceof BackupActivity,"Onboarding did not open the full-data restore");
        BackupActivity backup=(BackupActivity)opened;current=backup;
        await(()->view(backup,"restore-choose")!=null,"Restore file picker entry missing");
        require(DataMaintenance.active(),"Restore did not pause app data");
        String before=preferenceDigest();
        Map<String,Object> localNavigation=new LinkedHashMap<>();
        for(String key:LOCAL_NAVIGATION)if(prefs("app_settings").contains(key))localNavigation.put(key,prefs("app_settings").getAll().get(key));
        IntentFilter files=new IntentFilter(Intent.ACTION_OPEN_DOCUMENT);files.addCategory(Intent.CATEGORY_OPENABLE);files.addDataType("*/*");
        ActivityMonitor picker=addMonitor(files,new ActivityResult(Activity.RESULT_OK,new Intent().setData(android.net.Uri.fromFile(archive))),true);
        click(backup,"restore-choose");
        await(()->view(backup,"restore-open")!=null&&!Boolean.TRUE.equals(field(backup,"busy")),"Encrypted backup did not request a password");
        require(picker.getHits()==1,"Restore bypassed the user file picker");removeMonitor(picker);
        runOnMainSync(()->((EditText)field(backup,"password")).setText(new String(PASSWORD)));click(backup,"restore-open");
        await(()->view(backup,"restore-next")!=null&&!Boolean.TRUE.equals(field(backup,"busy")),"Backup did not reach its content preview");
        AppDataBackup.PreparedRestore staged=(AppDataBackup.PreparedRestore)field(backup,"staged");
        require(staged.complete&&staged.profileCount==1&&staged.fileCount==5&&staged.categories.size()==7,"Unexpected full backup inventory");
        require(before.equals(preferenceDigest())&&prefs("profiles").getAll().isEmpty()&&!app.getDatabasePath("telemetry-history.db").exists(),"Staging changed saved app data before approval");
        Switch replace=(Switch)view(backup,"restore-replace-all");require(replace!=null,"Full backup cannot restore everything");
        runOnMainSync(()->replace.setChecked(true));click(backup,"restore-next");
        await(()->view(backup,"restore-apply")!=null&&!Boolean.TRUE.equals(field(backup,"busy")),"Final restore review missing");
        require("Replace everything".contentEquals(((TextView)view(backup,"restore-apply")).getText()),"Full replacement is not explicitly labeled");
        require(before.equals(preferenceDigest())&&!prefs("onboarding").getBoolean("onboarding_done",false),"Review applied data or completed onboarding before approval");
        click(backup,"restore-apply");
        await(()->Boolean.TRUE.equals(field(backup,"terminal")),"Approved restore did not finish");
        require("Restored. Reopen Volt.".contentEquals(((TextView)field(backup,"status")).getText()),"Restore ended with an error");
        for(String key:LOCAL_NAVIGATION)require(Objects.equals(localNavigation.get(key),prefs("app_settings").getAll().get(key)),"Restore changed target-local navigation: "+key);
        return staged.fileCount;
    }
    private View view(Activity activity,String tag){return activity.getWindow().getDecorView().findViewWithTag(tag);}
    private void click(Activity activity,String tag){runOnMainSync(()->{View v=view(activity,tag);require(v!=null&&v.performClick(),"Missing action "+tag);});waitForIdleSync();}
    private Object field(Object target,String name){try{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}catch(Exception e){throw new IllegalStateException(name,e);}}
    private void await(BooleanSupplier condition,String message){long until=SystemClock.elapsedRealtime()+20000;while(!condition.getAsBoolean()&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(30);require(condition.getAsBoolean(),message);waitForIdleSync();}
    /** Legacy pairing files must never look like, or be allowed to perform, a full-data replacement. */
    private void restoreOwnerOnly(boolean existing,boolean addAnother)throws Exception {
        File ownerFile=new File(app.getExternalFilesDir(null),"legacy-owner.json");
        if(existing){
            ScooterProfiles local=new ScooterProfiles(app);ScooterProfiles.Profile original=local.active();
            original.mac="00:11:22:33:44:55";local.update(original);
            require(!original.serial.isEmpty(),"Existing owner regression requires a connected scooter with both identifiers");
        }
        String owner=existing
                ?"{\"format\":\"navee-offline-owner-v1\",\"owner_identity\":\"000000000000\",\"role\":0,\"model_name\":\"NAVEE scooter\",\"captured_at_utc\":\"2026-01-01T00:00:00.000Z\",\"note\":\"Private Bluetooth owner identity; not a NAVEE password or cloud login token.\"}"
                :"{\"format\":\"navee-offline-owner-v1\",\"owner_identity\":\"AABBCCDDEEFF\",\"role\":0,\"name\":\"Additional synthetic scooter\",\"scooter_serial\":\"OTHER-SYNTHETIC-OWNER\"}";
        Files.write(ownerFile.toPath(),owner.getBytes(StandardCharsets.UTF_8));
        MainActivity main=(MainActivity)startActivitySync(new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));current=main;
        await(()->!Boolean.TRUE.equals(field(main,"startupRedirect")),"Main activity did not initialize");
        runOnMainSync(()->main.dataBackup(true,null));waitForIdleSync();
        AlertDialog consent=(AlertDialog)field(main,"controlDialog");require(consent!=null&&consent.isShowing(),"Restore consent missing");
        ActivityMonitor monitor=addMonitor(BackupActivity.class.getName(),null,false);
        runOnMainSync(()->consent.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        Activity opened=waitForMonitorWithTimeout(monitor,10000);removeMonitor(monitor);
        require(opened instanceof BackupActivity,"Unified restore did not open");BackupActivity backup=(BackupActivity)opened;current=backup;
        await(()->view(backup,"restore-choose")!=null,"Restore form missing");
        Map<String,Map<String,?>> before=new LinkedHashMap<>();for(String name:PREFS)if(existing&&!addAnother||!name.equals("profiles"))before.put(name,new LinkedHashMap<>(prefs(name).getAll()));
        String profileId=prefs("app_settings").getString("fixture_profile_id","");
        Map<String,String> filesBefore=new LinkedHashMap<>();for(File file:durableFiles())filesBefore.put(relative(file),hash(Files.readAllBytes(file.toPath())));
        IntentFilter filter=new IntentFilter(Intent.ACTION_OPEN_DOCUMENT);filter.addCategory(Intent.CATEGORY_OPENABLE);filter.addDataType("*/*");
        ActivityMonitor picker=addMonitor(filter,new ActivityResult(Activity.RESULT_OK,new Intent().setData(android.net.Uri.fromFile(ownerFile))),true);
        click(backup,"restore-choose");await(()->view(backup,"restore-next")!=null&&!Boolean.TRUE.equals(field(backup,"busy")),"Owner file was not accepted by unified restore");
        require(picker.getHits()==1,"Owner file skipped the picker");removeMonitor(picker);
        AppDataBackup.PreparedRestore prepared=(AppDataBackup.PreparedRestore)field(backup,"staged");
        require(prepared.format.equals("owner")&&!prepared.complete&&prepared.categories.keySet().equals(Collections.singleton("scooters")),"Owner file advertised data it does not contain");
        require(view(backup,"restore-replace-all")==null&&view(backup,"restore-owner-only")!=null,"Owner file offers full replacement or lacks a scope warning");
        require(((TextView)view(backup,"restore-owner-only")).getText().toString().contains("no rides"),"Owner file warning does not explain missing rides");
        require(new ScooterProfiles(app).all().size()==1,"Owner staging changed profiles before approval");
        click(backup,"restore-next");await(()->view(backup,"restore-review")!=null&&!Boolean.TRUE.equals(field(backup,"busy")),"Owner destination review missing");
        android.widget.Spinner destination=(android.widget.Spinner)view(backup,"restore-destination");require(destination!=null,"Owner import destination missing");
        int add=-1,reuse=-1;for(int i=0;i<destination.getCount();i++){
            String name=String.valueOf(destination.getItemAtPosition(i));
            if("Add from backup".equals(name)||"Add another scooter".equals(name))add=i;
            if("Synthetic clear-storage scooter".equals(name))reuse=i;
        }
        if(existing){
            require(add>=0&&reuse>=0,"Same-account setup must offer both existing and another scooter");
            require("Choose a scooter…".equals(String.valueOf(destination.getSelectedItem())),"Same-account setup silently selected a scooter");
            require(view(backup,"restore-owner-choice")!=null&&!view(backup,"restore-review").isEnabled(),"Same-account setup lacks explanation or permits unresolved review");
            List<AppDataBackup.Destination> choices=AppDataBackup.destinations(app,prepared,Collections.singleton("scooters"));
            require(choices.size()==1&&choices.get(0).addable&&choices.get(0).suggested==null
                    &&choices.get(0).accountMatches.equals(Collections.singletonList(profileId)),"Same-account mapping cannot be explicitly resolved");
            AppDataBackup.Review review=AppDataBackup.review(app,prepared,Collections.singleton("scooters"),false,Collections.singletonMap(choices.get(0).source,profileId));
            require(review.lines.contains("Scooters: adds 0 · 1 already here"),"Existing owner preview does not explain the no-op");
            final int selection=addAnother?add:reuse;runOnMainSync(()->destination.setSelection(selection));
        }else{
            require(add>=0,"No explicit add-scooter choice");final int selection=add;runOnMainSync(()->destination.setSelection(selection));
        }
        await(()->view(backup,"restore-review").isEnabled(),"Explicit scooter choice did not enable Review");
        click(backup,"restore-review");
        await(()->view(backup,"restore-apply")!=null&&!Boolean.TRUE.equals(field(backup,"busy")),"Owner final preview missing");
        require("Restore".contentEquals(((TextView)view(backup,"restore-apply")).getText())&&view(backup,"restore-owner-only")!=null,"Owner final preview is presented as full replacement");
        require(new ScooterProfiles(app).all().size()==1,"Owner review changed profiles before approval");
        click(backup,"restore-apply");await(()->Boolean.TRUE.equals(field(backup,"terminal")),"Owner merge did not finish");
        require("Restored. Reopen Volt.".contentEquals(((TextView)field(backup,"status")).getText()),"Owner merge failed");
        ScooterProfiles profiles=new ScooterProfiles(app);require(profiles.all().size()==(existing&&!addAnother?1:2),existing&&!addAnother?"Existing owner import added a duplicate scooter":"Owner file did not add exactly one scooter");
        ScooterProfiles.Profile original=null;for(ScooterProfiles.Profile p:profiles.all())if(p.id.equals(profileId))original=p;
        require(original!=null&&original.identity.equals("000000000000")&&original.name.equals("Synthetic clear-storage scooter"),"Owner import replaced the original scooter");
        require(profileId.equals(profiles.active().id),"Owner import switched the active scooter");
        if(addAnother){ScooterProfiles.Profile added=null;for(ScooterProfiles.Profile p:profiles.all())if(!p.id.equals(profileId))added=p;
            require(added!=null&&added.identity.equals(original.identity)&&added.role==original.role&&added.mac.isEmpty()&&added.serial.isEmpty(),"New same-account scooter did not keep an independent unbound profile");}
        for(Map.Entry<String,Map<String,?>> e:before.entrySet())require(e.getValue().equals(prefs(e.getKey()).getAll()),"Owner import changed unrelated preferences: "+e.getKey());
        require(filesBefore.size()==durableFiles().size(),"Owner import changed the saved file inventory");
        for(File file:durableFiles())require(filesBefore.get(relative(file)).equals(hash(Files.readAllBytes(file.toPath()))),"Owner import changed "+relative(file));
    }
    private void admitFresh() {
        String hardware=Build.HARDWARE.toLowerCase(Locale.ROOT);
        require(hardware.contains("ranchu")||hardware.contains("goldfish")||Build.PRODUCT.startsWith("sdk_gphone"),"Refusing physical owner phones");
        for(String name:new String[]{"app_settings","profiles","scooter","control_evidence","onboarding","pending_documents","owner_capture_cleanup"})
            require(prefs(name).getAll().isEmpty(),"Refusing existing private preferences; clear this disposable emulator's app storage first");
        File[] files=app.getFilesDir().listFiles();require(files!=null&&files.length==0&&app.databaseList().length==0,"Refusing existing private files/history");
        require(!AppDataBackup.needsRecovery(app),"Refusing an existing transfer/recovery operation");
        require(!DataMaintenance.active(),"Refusing an active maintenance operation");
    }
    private void populate()throws Exception {
        SharedPreferences settings=prefs("app_settings");
        require(settings.edit().putBoolean("auto_connect",false).putBoolean("background_connection",false)
            .putBoolean("developer_options",true).putString("theme","dark").putString("units","mi").putString("temperature_unit","f")
            .putString("time_format","24h").putFloat("text_scale",1.15f).putStringSet("stats_favourites",new LinkedHashSet<>(Arrays.asList("battery_percent","odometer_km"))).commit(),"Store real app preferences");
        ScooterProfiles profiles=new ScooterProfiles(app);ScooterProfiles.Profile owner=profiles.create("Synthetic clear-storage scooter","000000000000",0);
        owner.serial="fixture-serial";owner.lastConnected=1234567890123L;
        owner.advertisedPid=C1_PID;owner.advertisedPidWall=C1_PID_WALL;owner.nameDefault=true;
        owner.fwMeter=C1_FW[0];owner.fwController=C1_FW[1];owner.fwBms=C1_FW[2];owner.fwScreen=C1_FW[3];owner.fwUwb=C1_FW[4];owner.fwWall=C1_FW_WALL;profiles.update(owner);
        require(prefs("scooter").edit().putString("identity","000000000000").putInt("role",0).putString("serial","legacy-fixture").commit(),"Store legacy setup preferences");
        require(prefs("control_evidence").edit().putString(owner.id,"{\"headlight\":\"Observed fixture\"}").commit(),"Store cached control evidence");
        require(prefs("onboarding").edit().putBoolean("onboarding_done",true).commit(),"Store onboarding completion");

        DashboardConfig dashboard=new DashboardConfig(settings,owner.id);dashboard.widgets.clear();
        DashboardConfig.Widget gauge=new DashboardConfig.Widget("gauge");gauge.metric="speed_kmh";gauge.title="Journey speed";gauge.minimum=0;gauge.maximum=51.5;gauge.color=2;
        DashboardConfig.Widget scooter=new DashboardConfig.Widget("scooter");scooter.keys.clear();scooter.keys.addAll(Arrays.asList("odometer_km","driving_mode"));scooter.title="Custom scooter card";
        for(int columns=1;columns<=6;columns++) {
            gauge.layouts.put(columns,new DashboardLayout.Cell(gauge.id,0,0,columns,3));
            scooter.layouts.put(columns,new DashboardLayout.Cell(scooter.id,0,3,columns,4));
        }
        dashboard.widgets.add(gauge);dashboard.widgets.add(scooter);dashboard.save();
        require(dashboard.saveSlot(0,"Folded and unfolded",6),"Save first layout slot");
        gauge.maximum=72.5;dashboard.save();require(dashboard.saveSlot(1,"Independent second slot",3),"Save second layout slot");
        gauge.title="Third slot custom gauge";dashboard.save();require(dashboard.saveSlot(2,"Third slot",0),"Save third layout slot");
        gauge.title="Active custom gauge";gauge.maximum=99.5;dashboard.save();
        require(settings.edit().putInt("dashboard_columns",5).putString("fixture_profile_id",owner.id)
            .putString("fixture_dashboard_export",dashboard.exportLayouts()).commit(),"Store dashboard fixture expectation");

        long wall=System.currentTimeMillis(),mono=100000;
        RideStore rides=new RideStore(new File(app.getFilesDir(),"rides"));String ride=rides.start(owner.id,"Clear-storage test ride",wall,mono);
        rides.setCurrentDirection(RideStore.CurrentDirection.POSITIVE_IS_DRAW);
        for(int i=0;i<3;i++) {
            long at=mono+i*2000;Map<String,RideStore.Sample> samples=new LinkedHashMap<>();
            samples.put("battery_percent",new RideStore.Sample(81.25-i,at));samples.put("voltage_v",new RideStore.Sample(51d,at));
            samples.put("current_a",new RideStore.Sample(2d,at));samples.put("speed_kmh",new RideStore.Sample(18d,at));samples.put("odometer_km",new RideStore.Sample(100+i*.01,at));
            rides.sample(wall+i*2000,at,samples);
        }
        RideStore.Summary summary=rides.stop(wall+6000,mono+6000);
        require(settings.edit().putString("fixture_ride_id",ride).putString("fixture_ride_summary",summary.toJson()).commit(),"Store expected ride summary");
        PhoneRideStore phone=new PhoneRideStore(new File(app.getFilesDir(),"phone-rides"));phone.start(ride,wall,mono,"pocket");
        StringBuilder phoneRows=new StringBuilder();for(int i=0;i<3;i++)phoneRows.append(PhoneRideStore.row("GPS",wall+i*1000,mono+i*1000,43.82+i*.00005,-111.78,3,5,.2,1500,4));
        phoneRows.append(PhoneRideStore.row("MOTION",wall+3000,mono+3000,3,1.25));
        phone.appendBatch(ride,phoneRows.toString(),"100000,0,0,9.8,0,0,0,0,0,0,0,0,0,1,3\n");phone.stop(ride,wall+4000,mono+4000);
        ChargeSessionStore charges=new ChargeSessionStore(new File(app.getFilesDir(),"charge-recordings"));charges.start(owner.id,ChargeSessionStore.Charger.STOCK,wall,mono);
        for(int i=0;i<3;i++) {long at=mono+i*10000;require(charges.sample(new ChargeSessionStore.Frame(wall+i*10000,at,value(1d,at),value(50d+i,at),value(52d,at),value(-2d,at),value(23d,at))),"Write charge reading");}
        charges.stop(ChargeSessionStore.Stop.STOPPED);

        TelemetryHistory history=new TelemetryHistory(app);
        Map<String,TelemetryHistory.Reading> readings=new LinkedHashMap<>();
        readings.put("battery_percent",new TelemetryHistory.Reading("battery_percent","Battery","Battery","81.25 %","%",81.25,wall,true));
        readings.put("odometer_km",new TelemetryHistory.Reading("odometer_km","Odometer","Scooter","100 km","km",100d,wall,true));
        history.record(owner.id,new TelemetryHistory.Snapshot(readings,Collections.singletonMap("traction_control",1),wall),1,true);
        require(history.closeAndAwait(10000),"History writer did not close");
        JSONObject hashes=new JSONObject();for(File file:durableFiles())hashes.put(relative(file),hash(Files.readAllBytes(file.toPath())));
        require(settings.edit().putString("fixture_file_hashes",hashes.toString()).commit(),"Store dataset byte expectations");
        require(settings.edit().putString(DIGEST,preferenceDigest()).commit(),"Store all-preferences expectation");
        require(settings.edit().putString("last_page","rides").putInt("more_away_boot",4).putString("owner_import_applied","fixture-import").putBoolean("notification_prompted",true).commit()
            &&prefs("onboarding").edit().putBoolean("nearby_asked",true).commit(),"Plant excluded device keys");
    }
    private void verifyLegacy()throws Exception {
        String id="8c1f3a52-0d7e-4b8e-9a41-5b2f6c7d8e90";SharedPreferences settings=prefs("app_settings");
        ScooterProfiles profiles=new ScooterProfiles(app);require(profiles.all().size()==1&&profiles.active().id.equals(id)&&profiles.active().configured(),"Legacy scooter restored");
        require("light".equals(settings.getString("theme",""))&&"kept only by an exact restore".equals(settings.getString("legacy_removed_key",""))&&!settings.contains("last_page")&&!settings.contains("owner_import_applied"),"Legacy durable keys restored and excluded keys left out");
        require(prefs("onboarding").getAll().equals(Collections.singletonMap("onboarding_done",true)),"Legacy wizard state left out");
        DashboardConfig dashboard=new DashboardConfig(settings,id);require(dashboard.widgets.size()==1&&dashboard.slotExists(0),"Legacy dashboard and slot reopen");
        RideStore rides=new RideStore(new File(app.getFilesDir(),"rides"));require(rides.list().size()==1&&rides.list().get(0).scooterId.equals(id),"Legacy ride reopens");
        require(new ChargeSessionStore(new File(app.getFilesDir(),"charge-recordings")).snapshot().sessions==1,"Legacy charge reopens");
        TelemetryHistory history=new TelemetryHistory(app);AtomicReference<TelemetryHistory.Snapshot> loaded=new AtomicReference<>();CountDownLatch latch=new CountDownLatch(1);
        history.load(id,s->{loaded.set(s);latch.countDown();});require(latch.await(10,TimeUnit.SECONDS)&&loaded.get()!=null&&loaded.get().readings.containsKey("battery_percent"),"Legacy SQLite history reopens");
        require(history.closeAndAwait(10000)&&!AppDataBackup.needsRecovery(app),"Legacy restore completed");
    }
    private void verifyRestored()throws Exception {
        SharedPreferences settings=prefs("app_settings");require(settings.getString(DIGEST,"").equals(preferenceDigest()),"Exact full preference inventory differs after clear-storage restore; keys: "+preferenceKeys());
        for(String key:EXCLUDED){String[] part=key.split("/");require(!prefs(part[0]).contains(part[1]),"Excluded key restored: "+key);}
        JSONObject hashes=new JSONObject(settings.getString("fixture_file_hashes","{}"));List<File> files=durableFiles();require(hashes.length()==5&&files.size()==5,"Restored durable file inventory differs");
        for(File file:files)require(hashes.getString(relative(file)).equals(hash(Files.readAllBytes(file.toPath()))),"Restored file byte hash differs: "+relative(file));
        String profileId=settings.getString("fixture_profile_id",""),ride=settings.getString("fixture_ride_id","");
        ScooterProfiles profiles=new ScooterProfiles(app);require(profiles.all().size()==1&&profiles.active().id.equals(profileId)&&profiles.active().configured(),"Restored scooter owner setup is not usable by the real profile model");
        ScooterProfiles.Profile c1=profiles.active();String[] fw={c1.fwMeter,c1.fwController,c1.fwBms,c1.fwScreen,c1.fwUwb};
        require(C1_PID.equals(c1.advertisedPid)&&c1.advertisedPidWall==C1_PID_WALL&&c1.nameDefault&&Arrays.equals(C1_FW,fw)&&c1.fwWall==C1_FW_WALL,
            "C1 profile fields changed: pid="+c1.advertisedPid+"@"+c1.advertisedPidWall+" nameDefault="+c1.nameDefault+" fw="+Arrays.toString(fw)+"@"+c1.fwWall);
        DashboardConfig dashboard=new DashboardConfig(settings,profileId);require(settings.getString("fixture_dashboard_export","").equals(dashboard.exportLayouts()),"Paired layouts/options/slots differ after reconstruction");
        require(dashboard.widgets.size()==2&&dashboard.widgets.get(0).maximum==99.5&&dashboard.widgets.get(0).title.equals("Active custom gauge"),"Active widget parameters changed");
        for(int columns=1;columns<=6;columns++)require(dashboard.widgets.get(0).layouts.get(columns).w==columns,"An arbitrary-width layout was lost");
        for(int slot=0;slot<3;slot++)require(dashboard.slotExists(slot)&&dashboard.slot(slot).widgetCount==2,"Saved layout slot is unavailable");
        RideStore rides=new RideStore(new File(app.getFilesDir(),"rides"));require(rides.list().size()==1&&settings.getString("fixture_ride_summary","").equals(rides.get(ride).toJson()),"Real ride reader did not reconstruct the exact ride statistics");
        PhoneRideStore phone=new PhoneRideStore(new File(app.getFilesDir(),"phone-rides"));require(phone.load(ride).locationCount==3&&phone.load(ride).motionSampleCount==3,"Real phone reader lost GPS or motion data");
        ChargeSessionStore charges=new ChargeSessionStore(new File(app.getFilesDir(),"charge-recordings"));require(charges.snapshot().sessions==1,"Real charge reader lost the captured session");
        TelemetryHistory history=new TelemetryHistory(app);AtomicReference<TelemetryHistory.Snapshot> loaded=new AtomicReference<>();CountDownLatch latch=new CountDownLatch(1);
        history.load(profileId,s->{loaded.set(s);latch.countDown();});require(latch.await(10,TimeUnit.SECONDS),"History restore read timed out");
        TelemetryHistory.Snapshot snapshot=loaded.get();require(snapshot!=null&&snapshot.readings.get("battery_percent").number==81.25&&!snapshot.readings.get("battery_percent").live&&snapshot.features.get("traction_control")==1,"Real history reader lost cached battery/control values or claimed they were live");
        require(history.closeAndAwait(10000),"Restored history reader did not close");
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(app.getDatabasePath("telemetry-history.db").getPath(),null,SQLiteDatabase.OPEN_READONLY);Cursor c=db.rawQuery("SELECT COUNT(*) FROM detail",null)){require(c.moveToFirst()&&c.getInt(0)==2,"Historical numeric readings were lost");}
        require(!AppDataBackup.needsRecovery(app),"Completed restore left a pending transaction");
    }
    private static ChargeSessionStore.Value value(double n,long at){return new ChargeSessionStore.Value(n,at,0x72);}
    private SharedPreferences prefs(String name){return app.getSharedPreferences(name,Context.MODE_PRIVATE);}
    private List<File> durableFiles(){List<File> files=new ArrayList<>();for(String directory:new String[]{"rides","phone-rides","charge-recordings"}){File[] children=new File(app.getFilesDir(),directory).listFiles();if(children!=null)files.addAll(Arrays.asList(children));}File db=app.getDatabasePath("telemetry-history.db");if(db.exists())files.add(db);return files;}
    private String relative(File file){return file.getParentFile().equals(app.getDatabasePath("telemetry-history.db").getParentFile())?"database/telemetry-history.db":"files/"+file.getParentFile().getName()+"/"+file.getName();}
    private String preferenceDigest()throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        for(String name:PREFS){text(out,name);Map<String,?> all=new TreeMap<>(prefs(name).getAll());for(Map.Entry<String,?> e:all.entrySet()){
            if(e.getKey().equals(DIGEST)||Arrays.asList(EXCLUDED).contains(name+"/"+e.getKey())||(name.equals("app_settings")&&Arrays.asList(LOCAL_NAVIGATION).contains(e.getKey())))continue;text(out,e.getKey());Object v=e.getValue();text(out,v.getClass().getSimpleName());
            if(v instanceof Set){for(String s:new TreeSet<>((Set<String>)v))text(out,s);}else text(out,String.valueOf(v));
        }}out.flush();return hash(bytes.toByteArray());
    }
    private String preferenceKeys(){Map<String,Set<String>> result=new LinkedHashMap<>();for(String name:PREFS)result.put(name,new TreeSet<>(prefs(name).getAll().keySet()));return result.toString();}
    private static void text(DataOutputStream out,String s)throws Exception{byte[] bytes=s.getBytes(StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes);}
    private static String hash(byte[] bytes)throws Exception{StringBuilder text=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))text.append(String.format(Locale.ROOT,"%02x",b&255));return text.toString();}
    private void require(boolean okay,String message){assertions++;if(!okay)throw new AssertionError(message);}
}
