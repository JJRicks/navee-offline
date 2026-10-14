package local.navee.offlinelock;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.crypto.SecretKey;

/** Full archive and replacement tests use real streams/files, without Android or device data. */
public final class AppDataArchiveTest {
    private static int checks;
    private static final char[] PASSWORD="a long backup password".toCharArray();
    private static final String ID="01234567-89ab-cdef-0123-456789abcdef";
    private static final class Crash extends Error {}
    private static final class Store implements AppDataArchive.Storage {
        final File root;boolean failOnce,failAlways,crash;int version=24;
        Store(File root)throws Exception{this.root=root;root.mkdirs();files().mkdirs();work().mkdirs();database().getParentFile().mkdirs();if(!prefsFile().exists())preferences(empty());}
        public File files(){return new File(root,"files");}public File database(){return new File(root,"databases/"+AppDataArchive.DATABASE);}
        public File work(){return new File(root,"no-backup/transfer");}public int versionCode(){return version;}File prefsFile(){return new File(root,"prefs.bin");}
        public Map<String,Map<String,Object>> preferences()throws IOException{return AppDataArchive.decodePreferences(Files.readAllBytes(prefsFile().toPath()));}
        public void preferences(Map<String,Map<String,Object>> value)throws IOException {
            if(failAlways||failOnce){failOnce=false;throw new IOException("Injected preferences disk failure");}
            Files.write(prefsFile().toPath(),AppDataArchive.encodePreferences(value));if(crash){crash=false;throw new Crash();}
        }
        public void validateDatabase(File database)throws IOException{if(!Arrays.equals(Files.readAllBytes(database.toPath()),new byte[]{1,2,3,4}))throw new IOException("Bad fixture database");}
    }
    private static Map<String,Map<String,Object>> empty(){Map<String,Map<String,Object>> result=new LinkedHashMap<>();for(String name:AppDataArchive.PREFERENCES)result.put(name,new LinkedHashMap<>());return result;}
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private interface Action{void run()throws Exception;}
    private static void rejects(Action action,String message)throws Exception{checks++;try{action.run();throw new AssertionError(message);}catch(IOException expected){}}
    private static void file(File file,byte[] value)throws Exception{file.getParentFile().mkdirs();Files.write(file.toPath(),value);}
    private static byte[] export(Store store)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();AppDataArchive.write(store,out,PASSWORD);return out.toByteArray();}
    private static AppDataArchive.Prepared stage(Store store,byte[] bytes)throws Exception{return AppDataArchive.stage(store,new ByteArrayInputStream(bytes),PASSWORD);}
    private static Store fixture(File root)throws Exception {
        Store s=new Store(root);Map<String,Map<String,Object>> values=empty();
        Map<String,Object> app=values.get("app_settings");app.put("dashboard_v1_owner","{\"widgets\":[\"battery\",\"scooter\"]}");app.put("dashboard_slot_v1_owner_2","paired folded + unfolded");
        app.put("enabled",true);app.put("small",12);app.put("long",9223372036854775807L);app.put("float",1.125f);app.put("set",new LinkedHashSet<>(Arrays.asList("one","two")));app.put("unicode","Scooter 🛴 — café");
        values.get("profiles").put("items","[{\"identity\":\"A1B2C3D4E5F6\",\"id\":\"owner\"}]");values.get("profiles").put("active","owner");
        values.get("scooter").put("identity","A1B2C3D4E5F6");values.get("control_evidence").put("owner","last verified command");values.get("onboarding").put("onboarding_done",true);s.preferences(values);
        byte[] random=new byte[250000];new Random(1234).nextBytes(random);
        file(new File(s.files(),"rides/"+ID+".csv"),random);
        file(new File(s.files(),"phone-rides/"+ID+"-phone.csv"),"private GPS route\n".getBytes("UTF-8"));
        file(new File(s.files(),"phone-rides/"+ID+"-motion.csv"),"raw accelerometer readings\n".getBytes("UTF-8"));
        file(new File(s.files(),"charge-recordings/"+ID+".csv"),"retained charge curve\n".getBytes("UTF-8"));
        file(s.database(),new byte[]{1,2,3,4});return s;
    }
    public static void main(String[] args)throws Exception {
        File root=Files.createTempDirectory("volt-backup-test-").toFile();
        try {
            Store source=fixture(new File(root,"source"));byte[] backup=export(source),backup2=export(source);
            check(!Arrays.equals(backup,backup2),"fresh salt/nonces randomize every export");
            check(!new String(backup,"ISO-8859-1").contains("A1B2C3D4E5F6"),"owner identity is not plaintext in exported file");
            Store clean=new Store(new File(root,"clean"));AppDataArchive.Prepared prepared=stage(clean,backup);
            check(prepared.fileCount==5&&prepared.totalBytes>250000&&prepared.createdAtMs>0,"authenticated summary includes all data");
            check(clean.preferences().get("profiles").isEmpty(),"staging cannot mutate app preferences");
            check(!new File(clean.files(),"rides/"+ID+".csv").exists(),"staging cannot mutate app files");
            AppDataArchive.apply(clean,prepared);compare(source,clean);
            check(!new File(clean.work(),"transaction").exists(),"successful transaction cleans rollback data");
            rejects(()->AppDataArchive.apply(clean,prepared),"prepared restore cannot be reused");
            Store existing=fixture(new File(root,"existing"));
            Map<String,Map<String,Object>> existingPrefs=existing.preferences();existingPrefs.get("app_settings").put("obsolete","remove on restore");existing.preferences(existingPrefs);
            file(new File(existing.files(),"rides/ffffffff-ffff-ffff-ffff-ffffffffffff.csv"),new byte[]{9});
            file(new File(existing.files(),"pending-owner-import.json"),new byte[]{8});
            File journal=new File(existing.root,"owner_capture_cleanup.xml");file(journal,new byte[]{7});
            AppDataArchive.apply(existing,stage(existing,backup));compare(source,existing);
            check(!new File(existing.files(),"rides/ffffffff-ffff-ffff-ffff-ffffffffffff.csv").exists(),"restore replaces rather than merges recordings");
            check(Files.readAllBytes(journal.toPath())[0]==7&&new File(existing.files(),"pending-owner-import.json").exists(),"target cleanup journal and pending documents remain untouched");
            Store rollback=fixture(new File(root,"rollback"));Map<String,Map<String,Object>> original=rollback.preferences();original.get("app_settings").put("retain","prior state");rollback.preferences(original);
            file(new File(rollback.files(),"rides/ffffffff-ffff-ffff-ffff-ffffffffffff.csv"),new byte[]{5,6});
            AppDataArchive.Prepared failure=stage(rollback,backup);rollback.failOnce=true;
            rejects(()->AppDataArchive.apply(rollback,failure),"preference failure must fail restore");
            check(rollback.preferences().equals(original),"failed restore rolls all preference namespaces back");
            check(Arrays.equals(Files.readAllBytes(new File(rollback.files(),"rides/ffffffff-ffff-ffff-ffff-ffffffffffff.csv").toPath()),new byte[]{5,6}),"failed restore rolls original recordings back");
            check(!new File(rollback.work(),"transaction").exists(),"successful rollback finishes journal");
            Store death=fixture(new File(root,"death"));Map<String,Map<String,Object>> before=death.preferences();before.get("app_settings").put("before-crash",true);death.preferences(before);
            AppDataArchive.Prepared crashPrepared=stage(death,backup);death.crash=true;
            try{AppDataArchive.apply(death,crashPrepared);throw new AssertionError("crash expected");}catch(Crash expected){}
            check(new File(death.work(),"transaction/state").isFile(),"process death leaves a recovery journal");
            Store reopened=new Store(death.root);check(AppDataArchive.recover(reopened),"fresh process reports rollback");check(reopened.preferences().equals(before),"fresh process recovers all pre-restore preferences");
            check(!AppDataArchive.recover(reopened),"recovery is idempotent");
            Store repeat=fixture(new File(root,"repeat"));Map<String,Map<String,Object>> repeatBefore=repeat.preferences();repeatBefore.get("app_settings").put("original",true);repeat.preferences(repeatBefore);
            AppDataArchive.Prepared repeated=stage(repeat,backup);repeat.failAlways=true;
            rejects(()->AppDataArchive.apply(repeat,repeated),"failed apply and failed rollback preserve recovery");
            check(new File(repeat.work(),"transaction/state").isFile(),"failed rollback retains recovery snapshot");
            repeat.failAlways=false;check(AppDataArchive.recover(repeat)&&repeat.preferences().equals(repeatBefore),"later successful startup finishes repeated rollback");
            AppDataArchive.Prepared canceled=stage(clean,backup);AppDataArchive.discard(clean,canceled);check(!canceled.directory.exists(),"cancel removes decrypted staging");
            AppDataArchive.Prepared orphan=stage(clean,backup);check(orphan.directory.exists(),"stage exists before simulated process death");AppDataArchive.recover(clean);check(!orphan.directory.exists(),"startup cleans orphan decrypted stages");
            rejects(()->AppDataArchive.stage(clean,new ByteArrayInputStream(backup),"wrong password".toCharArray()),"wrong password rejected");
            for(int position:new int[]{9,32,70,backup.length/2,backup.length-1}) {
                byte[] bad=backup.clone();bad[position]^=1;rejects(()->stage(clean,bad),"tamper rejected at "+position);
            }
            for(int cut:new int[]{0,8,32,backup.length-1,backup.length-20}) {
                byte[] bad=Arrays.copyOf(backup,cut);rejects(()->stage(clean,bad),"truncation rejected at "+cut);
            }
            byte[] trailing=Arrays.copyOf(backup,backup.length+1);rejects(()->stage(clean,trailing),"trailing bytes rejected");
            byte[] enormous=backup.clone();Arrays.fill(enormous,32,36,(byte)0x7f);rejects(()->stage(clean,enormous),"unbounded encrypted record rejected before allocation");
            rejects(()->AppDataArchive.write(source,new ByteArrayOutputStream(),"short".toCharArray()),"short password refused");
            check(clean.preferences().equals(source.preferences()),"invalid restores never change live preferences");
            check(clean.work().listFiles().length==0,"failed imports leave no decrypted staging behind");
            Store oldVersion=new Store(new File(root,"older"));oldVersion.version=23;rejects(()->stage(oldVersion,backup),"newer app backup refused by older app");
            file(new File(source.database().getPath()+"-wal"),new byte[]{1});rejects(()->export(source),"open database refused");new File(source.database().getPath()+"-wal").delete();
            for(String path:new String[]{"../profiles.xml","files/rides/../../secret","files/rides/"+ID+".csv/child","/files/rides/"+ID+".csv","files/rides/"+ID+".csv\\x","files/unknown/data","database/other.db"})check(!AppDataArchive.allowedFile(path),"strict path allowlist "+path);
            for(String path:new String[]{"../outside","files/rides/../../outside","database/other.db"}) {
                byte[] bad=malicious(source,path,new byte[]{1},false);rejects(()->stage(clean,bad),"authenticated traversal/unexpected file refused");
            }
            check(!new File(clean.work().getParentFile(),"outside").exists(),"archive paths cannot escape staging");
            byte[] duplicate=malicious(source,"preferencez.bin",AppDataArchive.encodePreferences(source.preferences()),true);rejects(()->stage(clean,duplicate),"authenticated duplicate entry refused");
            Map<String,Map<String,Object>> unsupported=empty();unsupported.get("app_settings").put("x",new Object());rejects(()->AppDataArchive.encodePreferences(unsupported),"unsupported preference type refused");
            byte[] trailingPrefs=Arrays.copyOf(AppDataArchive.encodePreferences(empty()),AppDataArchive.encodePreferences(empty()).length+1);rejects(()->AppDataArchive.decodePreferences(trailingPrefs),"trailing preference bytes refused");
            Map<String,Map<String,Object>> badTypes=empty();badTypes.get("app_settings").put("theme",3);
            rejects(()->AppDataArchive.validatePreferenceTypes(badTypes),"wrong startup theme type rejected");
            badTypes.get("app_settings").clear();badTypes.get("app_settings").put("stats_favourites","wrong");
            rejects(()->AppDataArchive.validatePreferenceTypes(badTypes),"wrong favourites type rejected");
            badTypes.get("app_settings").clear();badTypes.get("app_settings").put("text_scale",Float.NaN);
            rejects(()->AppDataArchive.validatePreferenceTypes(badTypes),"nonfinite text scale rejected");
            Store malformedRecovery=new Store(new File(root,"malformed-recovery"));AppDataArchive.deleteTree(malformedRecovery.work());file(malformedRecovery.work(),new byte[]{1});
            rejects(()->AppDataArchive.recover(malformedRecovery),"unreadable/nondirectory recovery storage fails closed");
            System.out.println("AppDataArchiveTest: "+checks+" checks passed (encrypted roundtrip, replace, rollback, crash recovery, tamper and hostile archives)");
        } finally {AppDataArchive.deleteTree(root);}
    }
    private static void compare(Store a,Store b)throws Exception {
        check(a.preferences().equals(b.preferences()),"every preference type and namespace roundtrips");
        for(String path:new String[]{"rides/"+ID+".csv","phone-rides/"+ID+"-phone.csv","phone-rides/"+ID+"-motion.csv","charge-recordings/"+ID+".csv"})check(Arrays.equals(Files.readAllBytes(new File(a.files(),path).toPath()),Files.readAllBytes(new File(b.files(),path).toPath())),"recording bytes preserved: "+path);
        check(Arrays.equals(Files.readAllBytes(a.database().toPath()),Files.readAllBytes(b.database().toPath())),"history database preserved exactly");
    }
    /** Test-only re-encryption allows adversarial but authenticated ZIP payloads. */
    private static byte[] malicious(Store store,String name,byte[] extra,boolean duplicate)throws Exception {
        byte[] prefs=AppDataArchive.encodePreferences(store.preferences());ByteArrayOutputStream plain=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(plain)) {
            zip.putNextEntry(new ZipEntry("manifest.bin"));DataOutputStream out=new DataOutputStream(zip);out.writeUTF("volt-full-app-data");out.writeInt(1);out.writeLong(1);out.writeInt(24);out.writeInt(1);out.writeLong(prefs.length+extra.length);zip.closeEntry();
            zip.putNextEntry(new ZipEntry("preferences.bin"));zip.write(prefs);zip.closeEntry();zip.putNextEntry(new ZipEntry(name));zip.write(extra);zip.closeEntry();
        }
        byte[] payload=plain.toByteArray();
        if(duplicate){byte[] from="preferencez.bin".getBytes("UTF-8"),to="preferences.bin".getBytes("UTF-8");for(int i=0;i<=payload.length-from.length;i++){boolean same=true;for(int j=0;j<from.length;j++)same&=payload[i+j]==from[j];if(same)System.arraycopy(to,0,payload,i,to.length);}}
        Method header=AppDataArchive.class.getDeclaredMethod("header");header.setAccessible(true);byte[] h=(byte[])header.invoke(null);
        Method key=AppDataArchive.class.getDeclaredMethod("key",char[].class,byte[].class);key.setAccessible(true);SecretKey k=(SecretKey)key.invoke(null,PASSWORD,h);
        Class<?> records=Class.forName("local.navee.offlinelock.AppDataArchive$RecordOutput");Constructor<?> constructor=records.getDeclaredConstructor(OutputStream.class,byte[].class,SecretKey.class);constructor.setAccessible(true);
        ByteArrayOutputStream encrypted=new ByteArrayOutputStream();encrypted.write(h);OutputStream output=(OutputStream)constructor.newInstance(encrypted,h,k);output.write(payload);Method finish=records.getDeclaredMethod("finish");finish.setAccessible(true);finish.invoke(output);return encrypted.toByteArray();
    }
}
