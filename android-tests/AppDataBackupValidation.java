package com.jjricks.volt.tests;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.Bundle;
import com.jjricks.volt.AppDataBackup;
import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.*;

/** Isolated real Android preferences/SQLite/crypto. Never uses physical phones or actual app data. */
public final class AppDataBackupValidation extends Instrumentation {
    private static final String[] PREFS={"app_settings","profiles","scooter","control_evidence","onboarding"};
    private static final String ID="01234567-89ab-cdef-0123-456789abcdef";
    private static final char[] PASSWORD="emulator-only backup password".toCharArray();
    private final ArrayList<String> checks=new ArrayList<>();
    private final ArrayList<FixtureContext> fixtures=new ArrayList<>();
    private File directory;private int assertions;
    @Override public void onCreate(Bundle args){start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try {
            String hardware=Build.HARDWARE.toLowerCase(Locale.ROOT);
            require(hardware.contains("ranchu")||hardware.contains("goldfish")||Build.PRODUCT.startsWith("sdk_gphone")||Build.MODEL.startsWith("sdk_gphone"),"Full backup validation refuses physical owner phones");
            directory=new File(getTargetContext().getCacheDir(),"app-backup-validation-"+System.nanoTime());require(directory.mkdirs(),"Create fixture folder");
            FixtureContext source=context("source");populate(source);
            Map<String,Map<String,?>> before=snapshot(source);byte[] database=Files.readAllBytes(source.getDatabasePath("telemetry-history.db").toPath());
            byte[] backup=export(source);require(backup.length>100000,"Fixture must exercise several authenticated records");
            FixtureContext restored=context("restored");
            SharedPreferences cleanup=restored.getSharedPreferences("owner_capture_cleanup",0);cleanup.edit().putString("session","target-cleanup-must-survive").commit();
            restored.getSharedPreferences("pending_documents",0).edit().putString("path","target-local-document").commit();
            int permission=restored.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT);
            AppDataBackup.PreparedRestore prepared=AppDataBackup.stage(restored,new ByteArrayInputStream(backup),PASSWORD);
            require(prepared.profileCount==1&&prepared.fileCount==6&&prepared.preferenceCount>=15&&prepared.totalBytes>250000,"Restore consent summary has expected counts");
            require(restored.getSharedPreferences("profiles",0).getAll().isEmpty(),"Staging does not import profiles");
            AppDataBackup.apply(restored,prepared);
            require(snapshot(restored).equals(before),"Every preference type, owner profile, layout and option survived a fresh-data restore");
            require(Arrays.equals(database,Files.readAllBytes(restored.getDatabasePath("telemetry-history.db").toPath())),"SQLite history bytes survived exactly");
            verifyRecordings(source,restored);
            try(SQLiteDatabase db=SQLiteDatabase.openDatabase(restored.getDatabasePath("telemetry-history.db").getPath(),null,SQLiteDatabase.OPEN_READONLY)) {
                try(Cursor c=db.rawQuery("SELECT value FROM detail",null)){require(c.moveToFirst()&&c.getDouble(0)==81.25,"Restored numerical history is readable");}
                try(Cursor c=db.rawQuery("SELECT data FROM latest WHERE profile='owner'",null)){require(c.moveToFirst()&&c.getString(0).contains("battery_percent"),"Restored cached parameters are readable");}
            }
            require(cleanup.getString("session","").equals("target-cleanup-must-survive"),"Target owner capture cleanup survives restore");
            require(restored.getSharedPreferences("pending_documents",0).getString("path","").equals("target-local-document"),"Target pending document state survives restore");
            require(permission==restored.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT),"Restore leaves Android permissions unchanged");
            require(!AppDataBackup.needsRecovery(restored),"Successful restore removed private staging/recovery data");
            checks.add("Password-protected all-data restore into empty Android storage preserves owner identities, all preference types, paired layouts, raw ride/motion/charge journals, SQLite history and cached parameters; target cleanup journal and OS grants remain untouched");

            // Clear every durable namespace and recording/database in this isolated fixture,
            // matching Clear storage's effect on app data while retaining the chosen backup bytes.
            for(String name:PREFS)require(restored.getSharedPreferences(name,0).edit().clear().commit(),"Clear fixture preference namespace");
            delete(restored.getFilesDir());delete(restored.getDatabasePath("telemetry-history.db"));restored.getFilesDir().mkdirs();
            AppDataBackup.apply(restored,AppDataBackup.stage(restored,new ByteArrayInputStream(backup),PASSWORD));
            require(snapshot(restored).equals(before),"Backup survives clear-data-style replacement with existing preference caches");verifyRecordings(source,restored);
            checks.add("A second restore after clearing durable fixture data refreshes real Android preference caches and restores all recordings");

            FixtureContext rollback=context("rollback");populate(rollback);
            rollback.getSharedPreferences("app_settings",0).edit().putString("retained","previous local state").commit();
            // Unchanged namespaces are not rewritten; make profiles differ so its injected failure follows a real app_settings commit.
            rollback.getSharedPreferences("profiles",0).edit().putBoolean("legacy_migrated",false).commit();
            Map<String,Map<String,?>> original=snapshot(rollback);AppDataBackup.PreparedRestore replace=AppDataBackup.stage(rollback,new ByteArrayInputStream(backup),PASSWORD);
            rollback.failCommit=true;
            expectFailure(()->AppDataBackup.apply(rollback,replace),"Injected post-write preference failure must abort restore");
            require(snapshot(rollback).equals(original),"Partial Android preference commits roll back all namespaces");verifyRecordings(source,rollback);
            require(!AppDataBackup.needsRecovery(rollback),"Completed rollback removes transaction");
            checks.add("Injected Android preference failure after a real commit restores all previous cached/disk preference values and recordings");

            AppDataBackup.PreparedRestore cancel=AppDataBackup.stage(restored,new ByteArrayInputStream(backup),PASSWORD);require(AppDataBackup.needsRecovery(restored),"Decrypted staging detected");AppDataBackup.discard(restored,cancel);require(!AppDataBackup.needsRecovery(restored),"Cancel removes decrypted data");
            AppDataBackup.stage(restored,new ByteArrayInputStream(backup),PASSWORD);require(!AppDataBackup.recover(restored)&&!AppDataBackup.needsRecovery(restored),"Startup cleans orphan staging without changing live data");
            expectFailure(()->AppDataBackup.stage(restored,new ByteArrayInputStream(backup),"incorrect password".toCharArray()),"Wrong password is rejected");
            byte[] damaged=backup.clone();damaged[damaged.length/2]^=1;expectFailure(()->AppDataBackup.stage(restored,new ByteArrayInputStream(damaged),PASSWORD),"Tampered file rejected");
            require(snapshot(restored).equals(before)&&!AppDataBackup.needsRecovery(restored),"Failed decrypt leaves live data unchanged and no private leftovers");
            checks.add("Android AES-GCM rejects wrong passwords/tampering; canceled, failed and interrupted imports remove their private decrypted stages");
            source.getSharedPreferences("app_settings",0).edit().putInt("theme",1).commit();byte[] badType=export(source);
            expectFailure(()->AppDataBackup.stage(restored,new ByteArrayInputStream(badType),PASSWORD),"Wrong theme type rejected before it can break app startup");
            source.getSharedPreferences("app_settings",0).edit().remove("theme").commit();
            source.getSharedPreferences("profiles",0).edit().putString("items","{broken").commit();byte[] badProfiles=export(source);
            expectFailure(()->AppDataBackup.stage(restored,new ByteArrayInputStream(badProfiles),PASSWORD),"Malformed profile JSON rejected");
            require(snapshot(restored).equals(before)&&!AppDataBackup.needsRecovery(restored),"Invalid startup preferences/profiles leave target untouched");
            source.getSharedPreferences("profiles",0).edit().putString("items",(String)before.get("profiles").get("items")).commit();
            checks.add("Known preference-type mismatches and malformed owner profiles are rejected before they can break startup");
            String evidence="[{\"id\":\"owner\",\"identity\":\"A1B2C3D4E5F6\",\"role\":0,\"name\":\"Fixture scooter\",\"pid_advertised\":\"24431\",\"pid_advertised_wall\":1700000000000,\"name_default\":true,\"fw_meter\":\"2.0.1.2\",\"fw_controller\":\"\",\"fw_wall\":1700000000000}]";
            source.getSharedPreferences("profiles",0).edit().putString("items",evidence).commit();byte[] modelEvidence=export(source);
            AppDataBackup.discard(restored,AppDataBackup.stage(restored,new ByteArrayInputStream(modelEvidence),PASSWORD));
            for(String bad:new String[]{"\"pid_advertised\":\"2443a\"","\"pid_advertised\":\"123456\"","\"pid_advertised\":24431","\"fw_meter\":\"0123456789abcdefg\"","\"name_default\":\"yes\"","\"fw_wall\":\"1\""}) {
                source.getSharedPreferences("profiles",0).edit().putString("items","[{\"id\":\"owner\",\"identity\":\"A1B2C3D4E5F6\",\"role\":0,"+bad+"}]").commit();byte[] invalid=export(source);
                expectFailure(()->AppDataBackup.stage(restored,new ByteArrayInputStream(invalid),PASSWORD),"Invalid model evidence rejected: "+bad);
            }
            require(snapshot(restored).equals(before)&&!AppDataBackup.needsRecovery(restored),"Model evidence checks leave target untouched");
            source.getSharedPreferences("profiles",0).edit().putString("items",(String)before.get("profiles").get("items")).commit();
            checks.add("Profiles with model evidence fields stage; malformed advertised IDs, firmware strings and flags are rejected");
            // Same authenticated format, but corrupt database content, must be rejected before apply.
            file(source.getDatabasePath("telemetry-history.db"),new byte[]{1,2,3,4});byte[] badDatabase=export(source);
            expectFailure(()->AppDataBackup.stage(restored,new ByteArrayInputStream(badDatabase),PASSWORD),"Malformed authenticated SQLite database is rejected");
            require(snapshot(restored).equals(before),"Invalid database never imports preferences");
            checks.add("Malformed history is rejected before any live preference/file mutation");
            categoryMerge();
            checks.add("Real SQLite history merge (union, this phone wins, profile remap, integrity) and a preferences-only restore that commits one namespace");
            result.putString("result","PASS");result.putInt("assertions",assertions);result.putStringArrayList("checks",checks);result.putInt("scooter_mutations",0);
            cleanup();finish(0,result);
        }catch(Throwable failure){result.putString("result","FAIL");result.putString("error",failure.getClass().getSimpleName()+": "+failure.getMessage());result.putInt("assertions",assertions);result.putStringArrayList("checks",checks);try{cleanup();}catch(Exception ignored){}finish(1,result);}
    }
    /** History-only and preferences-only restores into a phone that already has data. */
    private void categoryMerge()throws Exception {
        long now=System.currentTimeMillis();
        FixtureContext source=context("merge-source"),target=context("merge-target");
        history(source,"backup-scooter",new long[]{now-60000,now-30000},new double[]{10,11},now-30000);
        history(target,"phone-scooter",new long[]{now-60000,now-5000},new double[]{50,51},now-5000);
        require(source.getSharedPreferences("profiles",0).edit().putString("items","[{\"id\":\"backup-scooter\",\"name\":\"Old\",\"serial\":\"SER-1\",\"fw_controller\":\"1\"}]").commit(),"Store source profile");
        require(target.getSharedPreferences("profiles",0).edit().putString("items","[{\"id\":\"phone-scooter\",\"name\":\"Mine\",\"identity\":\"A1B2C3D4E5F6\",\"role\":0,\"serial\":\"SER-1\"}]").putString("active","phone-scooter").commit(),"Store target profile");
        require(source.getSharedPreferences("app_settings",0).edit().putString("units","km").putString("theme","light").commit()&&target.getSharedPreferences("app_settings",0).edit().putString("units","mi").putString("last_page","stats").commit(),"Store settings");
        ByteArrayOutputStream out=new ByteArrayOutputStream();AppDataBackup.write(source,out,PASSWORD,Collections.singleton("history"));
        Map<String,Map<String,?>> before=snapshot(target);
        AppDataBackup.PreparedRestore prepared=AppDataBackup.stage(target,new ByteArrayInputStream(out.toByteArray()),PASSWORD);
        List<AppDataBackup.Destination> needed=AppDataBackup.destinations(target,prepared,Collections.singleton("history"));
        require(needed.size()==1&&"phone-scooter".equals(needed.get(0).suggested),"Serial match suggests this phone's scooter");
        AppDataBackup.Review review=AppDataBackup.review(target,prepared,Collections.singleton("history"),false,Collections.singletonMap("backup-scooter","phone-scooter"));
        require(review.lines.contains("Readings history: adds to 1 scooter"),"History review line");
        AppDataBackup.apply(target,review);
        require(snapshot(target).equals(before),"History-only restore leaves every preference unchanged");
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(target.getDatabasePath("telemetry-history.db").getPath(),null,SQLiteDatabase.OPEN_READONLY)) {
            require(db.isDatabaseIntegrityOk(),"Merged history passes integrity_check");
            Map<Long,Double> values=new HashMap<>();
            try(Cursor c=db.rawQuery("SELECT d.wall,d.value FROM detail d JOIN profiles p ON p.id=d.pid WHERE p.name='phone-scooter'",null)){while(c.moveToNext())values.put(c.getLong(0),c.getDouble(1));}
            require(values.size()==3&&values.get(now-60000)==50&&values.get(now-30000)==11&&values.get(now-5000)==51,"Rows union under the mapped scooter and this phone's row wins");
            try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM profiles WHERE name='backup-scooter'",null)){require(c.moveToFirst()&&c.getInt(0)==0,"History is never attached to an unmapped scooter");}
            try(Cursor c=db.rawQuery("SELECT data FROM latest WHERE profile='phone-scooter'",null)){require(c.moveToFirst()&&c.getString(0).contains(Long.toString(now-5000)),"This phone's last-seen readings win");}
        }
        out.reset();AppDataBackup.write(source,out,PASSWORD,Collections.singleton("preferences"));
        AppDataBackup.PreparedRestore prefs=AppDataBackup.stage(target,new ByteArrayInputStream(out.toByteArray()),PASSWORD);
        AppDataBackup.apply(target,AppDataBackup.review(target,prefs,Collections.singleton("preferences"),false,Collections.<String,String>emptyMap()));
        SharedPreferences settings=target.getSharedPreferences("app_settings",0);
        require("km".equals(settings.getString("units",""))&&"light".equals(settings.getString("theme",""))&&"stats".equals(settings.getString("last_page",""))&&target.getSharedPreferences("profiles",0).getAll().equals(before.get("profiles")),"Preferences-only restore replaces settings, keeps device keys and scooters");
        require(!AppDataBackup.needsRecovery(target),"Category restores leave no private data");
    }
    private static void history(Context c,String profile,long[] walls,double[] values,long latestWall)throws Exception {
        try(SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(c.getDatabasePath("telemetry-history.db"),null)) {
            db.execSQL("CREATE TABLE latest(profile TEXT PRIMARY KEY,data TEXT NOT NULL)");db.execSQL("CREATE TABLE profiles(id INTEGER PRIMARY KEY,name TEXT UNIQUE NOT NULL)");db.execSQL("CREATE TABLE keys(id INTEGER PRIMARY KEY,name TEXT UNIQUE NOT NULL)");
            db.execSQL("CREATE TABLE detail(pid INTEGER,kid INTEGER,wall INTEGER,value REAL,session INTEGER,UNIQUE(pid,kid,wall))");
            for(String table:new String[]{"minute","daily"})db.execSQL("CREATE TABLE "+table+"(pid INTEGER,kid INTEGER,bucket INTEGER,total REAL,samples INTEGER,last_value REAL,first_wall INTEGER,last_wall INTEGER,first_session INTEGER,last_session INTEGER,gaps INTEGER,UNIQUE(pid,kid,bucket))");
            db.execSQL("INSERT INTO profiles VALUES(7,?)",new Object[]{profile});db.execSQL("INSERT INTO keys VALUES(3,'battery_percent')");
            for(int i=0;i<walls.length;i++)db.execSQL("INSERT INTO detail VALUES(7,3,?,?,1)",new Object[]{walls[i],values[i]});
            db.execSQL("INSERT INTO latest VALUES(?,?)",new Object[]{profile,"{\"wall\":"+latestWall+",\"readings\":{},\"features\":{}}"});db.setVersion(1);
        }
    }
    private FixtureContext context(String name)throws Exception{FixtureContext c=new FixtureContext(getTargetContext(),new File(directory,name),"backup_validation_"+directory.getName()+"_"+name+"_");fixtures.add(c);return c;}
    private final class FixtureContext extends ContextWrapper {
        final File root;final String prefix;boolean failCommit;
        FixtureContext(Context base,File root,String prefix)throws Exception{super(base);this.root=root;this.prefix=prefix;getFilesDir().mkdirs();getNoBackupFilesDir().mkdirs();getDatabasePath("telemetry-history.db").getParentFile().mkdirs();}
        @Override public Context getApplicationContext(){return this;}
        @Override public File getFilesDir(){return new File(root,"files");}
        @Override public File getNoBackupFilesDir(){return new File(root,"no-backup");}
        @Override public File getDatabasePath(String name){return new File(root,"databases/"+name);}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){
            SharedPreferences actual=super.getSharedPreferences(prefix+name,mode);
            if(!name.equals("profiles"))return actual;
            return(SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class<?>[]{SharedPreferences.class},(proxy,method,args)->{
                if(method.getName().equals("edit")) {
                    SharedPreferences.Editor edit=actual.edit();final Object[] wrapped=new Object[1];
                    wrapped[0]=Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),new Class<?>[]{SharedPreferences.Editor.class},(p,m,a)->{
                        Object result=m.invoke(edit,a);
                        if(m.getName().equals("commit")&&failCommit){failCommit=false;return false;}
                        return result instanceof SharedPreferences.Editor?wrapped[0]:result;
                    });return wrapped[0];
                }
                return method.invoke(actual,args);
            });
        }
    }
    private void populate(FixtureContext c)throws Exception {
        SharedPreferences.Editor settings=c.getSharedPreferences("app_settings",0).edit();
        settings.putString("dashboard_v1_owner","fixture layouts and parameters").putString("dashboard_slot_v1_owner_2","folded / unfolded")
            .putInt("dashboard_columns",6).putLong("last_seen",1234567890123L).putFloat("fixture_float",1.125f).putBoolean("developer_options",true)
            .putStringSet("favourites",new LinkedHashSet<>(Arrays.asList("battery_percent","speed_kmh"))).putString("units","mi").putString("temperature_unit","F");require(settings.commit(),"Store settings");
        require(c.getSharedPreferences("profiles",0).edit().putString("items","[{\"id\":\"owner\",\"identity\":\"A1B2C3D4E5F6\",\"role\":0,\"name\":\"Fixture scooter\"}]").putString("active","owner").putBoolean("legacy_migrated",true).commit(),"Store owner profile");
        require(c.getSharedPreferences("scooter",0).edit().putString("identity","A1B2C3D4E5F6").putInt("role",0).commit(),"Store legacy owner");
        require(c.getSharedPreferences("control_evidence",0).edit().putString("owner","fixture last command").commit(),"Store command cache");
        require(c.getSharedPreferences("onboarding",0).edit().putBoolean("onboarding_done",true).commit(),"Store onboarding state");
        byte[] random=new byte[250000];new Random(42).nextBytes(random);file(new File(c.getFilesDir(),"rides/"+ID+".csv"),random);
        for(String suffix:new String[]{"phone","motion"})file(new File(c.getFilesDir(),"phone-rides/"+ID+"-"+suffix+".csv"),(suffix+",sensor,fixture\n").getBytes("UTF-8"));
        file(new File(c.getFilesDir(),"charge-recordings/"+ID+".csv"),"finished charge\n".getBytes("UTF-8"));
        file(new File(c.getFilesDir(),"charge-recordings/ffffffff-ffff-ffff-ffff-ffffffffffff.active"),"interrupted charge\n".getBytes("UTF-8"));
        try(SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(c.getDatabasePath("telemetry-history.db"),null)) {
            db.execSQL("CREATE TABLE latest(profile TEXT PRIMARY KEY,data TEXT NOT NULL)");db.execSQL("CREATE TABLE profiles(id INTEGER PRIMARY KEY,name TEXT UNIQUE NOT NULL)");db.execSQL("CREATE TABLE keys(id INTEGER PRIMARY KEY,name TEXT UNIQUE NOT NULL)");
            db.execSQL("CREATE TABLE detail(pid INTEGER,kid INTEGER,wall INTEGER,value REAL,session INTEGER,UNIQUE(pid,kid,wall))");
            for(String table:new String[]{"minute","daily"})db.execSQL("CREATE TABLE "+table+"(pid INTEGER,kid INTEGER,bucket INTEGER,total REAL,samples INTEGER,last_value REAL,first_wall INTEGER,last_wall INTEGER,first_session INTEGER,last_session INTEGER,gaps INTEGER,UNIQUE(pid,kid,bucket))");
            db.execSQL("INSERT INTO latest VALUES('owner','{\"battery_percent\":81.25}')");db.execSQL("INSERT INTO detail VALUES(1,1,123456789,81.25,1)");db.setVersion(1);
        }
    }
    private static byte[] export(Context c)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();AppDataBackup.write(c,out,PASSWORD);return out.toByteArray();}
    private static Map<String,Map<String,?>> snapshot(Context c){Map<String,Map<String,?>> result=new LinkedHashMap<>();for(String name:PREFS)result.put(name,new LinkedHashMap<>(c.getSharedPreferences(name,0).getAll()));return result;}
    private void verifyRecordings(FixtureContext a,FixtureContext b)throws Exception {
        for(String path:new String[]{"rides/"+ID+".csv","phone-rides/"+ID+"-phone.csv","phone-rides/"+ID+"-motion.csv","charge-recordings/"+ID+".csv","charge-recordings/ffffffff-ffff-ffff-ffff-ffffffffffff.active"})
            require(Arrays.equals(Files.readAllBytes(new File(a.getFilesDir(),path).toPath()),Files.readAllBytes(new File(b.getFilesDir(),path).toPath())),"Recording preserved: "+path);
    }
    private static void file(File f,byte[] bytes)throws Exception{f.getParentFile().mkdirs();Files.write(f.toPath(),bytes);}
    private interface Action{void run()throws Exception;}
    private void expectFailure(Action action,String message)throws Exception{assertions++;try{action.run();throw new AssertionError(message);}catch(IOException expected){}}
    private void require(boolean okay,String message){assertions++;if(!okay)throw new AssertionError(message);}
    private void cleanup()throws Exception{for(FixtureContext c:fixtures){for(String name:PREFS)c.getSharedPreferences(name,0).edit().clear().commit();c.getSharedPreferences("owner_capture_cleanup",0).edit().clear().commit();c.getSharedPreferences("pending_documents",0).edit().clear().commit();}if(directory!=null)delete(directory);}
    private static void delete(File file)throws Exception{if(!file.exists())return;if(file.isDirectory()){File[] children=file.listFiles();if(children==null)throw new IOException("Cannot read fixture folder");for(File child:children)delete(child);}if(!file.delete())throw new IOException("Cannot remove fixture data");}
}
