package local.navee.offlinelock.tests;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.Bundle;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import local.navee.offlinelock.*;
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
    /** Device and pending keys planted before export; a backup never carries them. */
    private static final String[] EXCLUDED={"app_settings/last_page","app_settings/more_away_boot","app_settings/owner_import_applied","app_settings/notification_prompted","onboarding/nearby_asked"};
    private String mode;private int assertions;private Context app;
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
                AppDataBackup.PreparedRestore prepared;
                try(InputStream in=new FileInputStream(archive)){prepared=AppDataBackup.stage(app,in,PASSWORD);}
                require(prepared.profileCount==1&&prepared.fileCount==5,"Unexpected authenticated backup inventory");
                require(prefs("profiles").getAll().isEmpty()&&!app.getDatabasePath("telemetry-history.db").exists(),"Import staging changed the fresh installation before approval");
                AppDataBackup.apply(app,prepared);verifyRestored();
                result.putString("phase","RESTORED_AFTER_CLEAR_STORAGE");result.putInt("restored_files",prepared.fileCount);
            } else if("legacy-v1".equals(mode)) {
                File legacy=new File(external,"legacy-v1.voltbackup");require(legacy.isFile(),"Push tests/fixtures/legacy/full-v1.voltbackup after clearing storage");
                AppDataBackup.PreparedRestore prepared;
                try(InputStream in=new FileInputStream(legacy)){prepared=AppDataBackup.stage(app,in,"synthetic fixture password".toCharArray());}
                require("v1".equals(prepared.format)&&prepared.complete&&prepared.categories.size()==7,"A 1.3.1 full backup is a complete backup");
                AppDataBackup.apply(app,prepared);verifyLegacy();
                result.putString("phase","LEGACY_V1_RESTORED");
            } else throw new IllegalArgumentException("Use mode export, restore-check or legacy-v1");
            result.putString("result","PASS");result.putString("archive_path",archive.getAbsolutePath());
            result.putBoolean("nearby_granted",app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED);
            result.putInt("scooter_mutations",0);
        } catch(Throwable failure){result.putString("result","FAIL");result.putString("error",failure.getClass().getSimpleName()+": "+failure.getMessage());}
        result.putInt("assertions",assertions);result.putString("fixture","Disposable fresh emulator; real app directories and preferences; synthetic credentials/readings; no service, Bluetooth or sensors");
        finish("PASS".equals(result.getString("result"))?0:1,result);
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
        owner.serial="fixture-serial";owner.lastConnected=1234567890123L;profiles.update(owner);
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
        SharedPreferences settings=prefs("app_settings");require(settings.getString(DIGEST,"").equals(preferenceDigest()),"Exact full preference inventory differs after clear-storage restore");
        for(String key:EXCLUDED){String[] part=key.split("/");require(!prefs(part[0]).contains(part[1]),"Excluded key restored: "+key);}
        JSONObject hashes=new JSONObject(settings.getString("fixture_file_hashes","{}"));List<File> files=durableFiles();require(hashes.length()==5&&files.size()==5,"Restored durable file inventory differs");
        for(File file:files)require(hashes.getString(relative(file)).equals(hash(Files.readAllBytes(file.toPath()))),"Restored file byte hash differs: "+relative(file));
        String profileId=settings.getString("fixture_profile_id",""),ride=settings.getString("fixture_ride_id","");
        ScooterProfiles profiles=new ScooterProfiles(app);require(profiles.all().size()==1&&profiles.active().id.equals(profileId)&&profiles.active().configured(),"Restored scooter owner setup is not usable by the real profile model");
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
            if(e.getKey().equals(DIGEST)||Arrays.asList(EXCLUDED).contains(name+"/"+e.getKey()))continue;text(out,e.getKey());Object v=e.getValue();text(out,v.getClass().getSimpleName());
            if(v instanceof Set){for(String s:new TreeSet<>((Set<String>)v))text(out,s);}else text(out,String.valueOf(v));
        }}out.flush();return hash(bytes.toByteArray());
    }
    private static void text(DataOutputStream out,String s)throws Exception{byte[] bytes=s.getBytes(StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes);}
    private static String hash(byte[] bytes)throws Exception{StringBuilder text=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))text.append(String.format(Locale.ROOT,"%02x",b&255));return text.toString();}
    private void require(boolean okay,String message){assertions++;if(!okay)throw new AssertionError(message);}
}
