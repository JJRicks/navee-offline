package local.navee.offlinelock;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Portable archive codec and recoverable replacement, independent of Android APIs. */
final class AppDataArchive {
    static final String[] PREFERENCES = {"app_settings", "profiles", "scooter", "control_evidence", "onboarding"};
    static final String[] DIRECTORIES = {"rides", "phone-rides", "charge-recordings"};
    static final String DATABASE = "telemetry-history.db";
    static final long MAX_BYTES = 2L * 1024 * 1024 * 1024;
    static final long MAX_FILE_BYTES = 512L * 1024 * 1024;
    static final int MAX_ENTRIES = 50000, MAX_PREFERENCES = 16 * 1024 * 1024;
    private static final byte[] MAGIC = {'V','O','L','T','B','K','P',1};
    private static final int ITERATIONS = 210000, BLOCK = 64 * 1024;
    private static final String MANIFEST = "manifest.bin", PREFS = "preferences.bin";
    private static final String UUID_PATTERN = "[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}";
    private static final SecureRandom RANDOM = new SecureRandom();

    interface Storage {
        File files();
        File database();
        File work();
        int versionCode();
        Map<String, Map<String, Object>> preferences() throws IOException;
        void preferences(Map<String, Map<String, Object>> value) throws IOException;
        void validateDatabase(File database) throws IOException;
        default void syncDirectory(File directory) throws IOException { AppDataArchive.syncDirectory(directory); }
    }
    static final class Prepared {
        final File directory;
        final long createdAtMs, totalBytes;
        final int fileCount;
        final Map<String, Map<String, Object>> preferences;
        boolean consumed;
        Prepared(File directory, long created, long total, int count, Map<String, Map<String, Object>> prefs) {
            this.directory=directory;createdAtMs=created;totalBytes=total;fileCount=count;preferences=prefs;
        }
    }

    /** Caller owns streams and must quiesce every app data writer before export/apply. */
    static void write(Storage storage, OutputStream output, char[] password) throws IOException {
        requirePassword(password); ensureNoTransaction(storage); checkDatabaseClosed(storage);
        byte[] prefs=encodePreferences(storage.preferences());
        List<FileEntry> entries=collect(storage.files(), storage.database());
        long total=prefs.length; for(FileEntry e:entries)total=addBounded(total,e.length,MAX_BYTES);
        byte[] header=header(); SecretKey key=key(password,header);
        output.write(header);
        RecordOutput encrypted=new RecordOutput(output,header,key);
        ZipOutputStream zip=new ZipOutputStream(encrypted);
        try {
            zip.putNextEntry(new ZipEntry(MANIFEST));
            DataOutputStream manifest=new DataOutputStream(zip);
            manifest.writeUTF("volt-full-app-data");manifest.writeInt(1);manifest.writeLong(System.currentTimeMillis());
            manifest.writeInt(storage.versionCode());manifest.writeInt(entries.size());manifest.writeLong(total);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(PREFS));zip.write(prefs);zip.closeEntry();
            byte[] buffer=new byte[BLOCK];
            for(FileEntry entry:entries) {
                long length=entry.length;
                if(entry.file.length()!=length)throw new IOException("App data changed during backup. Try again with recordings stopped.");
                zip.putNextEntry(new ZipEntry(entry.name));
                try(InputStream in=new FileInputStream(entry.file)) {
                    long copied=copy(in,zip,buffer,MAX_FILE_BYTES);
                    if(copied!=length||entry.file.length()!=length)throw new IOException("App data changed during backup. Try again with recordings stopped.");
                }
                zip.closeEntry();
            }
            zip.finish();encrypted.finish();output.flush();
        } finally { try{zip.close();}catch(IOException ignored){} Arrays.fill(prefs,(byte)0); encrypted.clear(); }
    }

    static Prepared stage(Storage storage, InputStream input, char[] password) throws IOException {
        requirePassword(password);ensureNoTransaction(storage);mkdir(storage.work());
        File directory=new File(storage.work(),"stage-"+UUID.randomUUID());mkdir(directory);
        try {
            File payload=new File(directory,"payload.zip");
            // Authenticate every record, including the terminal record, before interpreting ZIP input.
            try(FileOutputStream out=new FileOutputStream(payload)) { decrypt(input,out,password);out.getFD().sync(); }
            File data=new File(directory,"data");mkdir(data);
            Prepared result=extract(storage,payload,data);
            if(!payload.delete())throw new IOException("Could not remove temporary encrypted-backup payload.");
            return new Prepared(directory,result.createdAtMs,result.totalBytes,result.fileCount,result.preferences);
        } catch(Exception failure) {
            try{deleteTree(directory);}catch(IOException cleanup){failure.addSuppressed(cleanup);}
            if(failure instanceof IOException)throw(IOException)failure;
            throw new IOException("The backup could not be opened.",failure);
        }
    }

    private static Prepared extract(Storage storage,File archive,File data)throws IOException {
        Set<String> seen=new HashSet<>();long total=0,created=0,expectedBytes=0;int count=0,expectedCount=0;
        Map<String,Map<String,Object>> prefs=null;
        try(ZipInputStream zip=new ZipInputStream(new BufferedInputStream(new FileInputStream(archive)))) {
            ZipEntry entry=zip.getNextEntry();
            if(entry==null||!MANIFEST.equals(entry.getName())||entry.isDirectory())throw new IOException("This is not a supported Volt app backup.");
            DataInputStream in=new DataInputStream(new ByteArrayInputStream(readBounded(zip,256)));
            if(!"volt-full-app-data".equals(in.readUTF())||in.readInt()!=1)throw new IOException("Unsupported backup format.");
            created=in.readLong();int version=in.readInt();expectedCount=in.readInt();expectedBytes=in.readLong();
            if(in.read()!=-1||created<=0||version<=0||version>storage.versionCode()||expectedCount<0||expectedCount>MAX_ENTRIES||expectedBytes<0||expectedBytes>MAX_BYTES)
                throw new IOException("The backup needs a compatible or newer app version, or has invalid metadata.");
            seen.add(MANIFEST);zip.closeEntry();
            while((entry=zip.getNextEntry())!=null) {
                String name=entry.getName();
                if(!seen.add(name)||entry.isDirectory()||(!PREFS.equals(name)&&!allowedFile(name)))throw new IOException("The backup contains an unsupported or duplicate entry.");
                if(seen.size()>MAX_ENTRIES+2)throw new IOException("The backup contains too many files.");
                if(PREFS.equals(name)) {
                    byte[] bytes=readBounded(zip,MAX_PREFERENCES);total=addBounded(total,bytes.length,MAX_BYTES);
                    try{prefs=decodePreferences(bytes);}finally{Arrays.fill(bytes,(byte)0);}
                } else {
                    File target=contained(data,name);mkdir(target.getParentFile());
                    long copied;
                    try(FileOutputStream out=new FileOutputStream(target)){copied=copy(zip,out,new byte[BLOCK],Math.min(fileLimit(name),MAX_BYTES-total));out.getFD().sync();}
                    total=addBounded(total,copied,MAX_BYTES);count++;
                }
                zip.closeEntry();
            }
        }
        if(prefs==null||count!=expectedCount||total!=expectedBytes)throw new IOException("The backup is incomplete or has inconsistent contents.");
        validatePreferenceTypes(prefs);
        File db=new File(data,"database/"+DATABASE);if(db.exists())storage.validateDatabase(db);
        // Save the parsed preferences in the staged tree for process-death-safe transaction recovery.
        writePreferencesFile(new File(data,PREFS),prefs);
        return new Prepared(data,created,total,count,prefs);
    }

    static void apply(Storage storage,Prepared prepared)throws IOException {
        if(prepared==null||prepared.consumed||!isStage(storage,prepared.directory))throw new IOException("Choose the backup again before restoring.");
        ensureNoTransaction(storage);checkDatabaseClosed(storage);
        File transaction=new File(storage.work(),"transaction"),previous=new File(transaction,"previous"),incoming=new File(transaction,"incoming");
        mkdir(transaction);
        boolean active=false;
        try {
            mkdir(previous);
            snapshot(storage,previous); // A full rollback exists before the first live-data mutation.
            if(!prepared.directory.renameTo(incoming))throw new IOException("Could not prepare the restore transaction.");
            prepared.consumed=true;
            // Persist the snapshot's parent chain before a durable active marker permits live writes.
            storage.syncDirectory(transaction);
            storage.syncDirectory(storage.work());
            storage.syncDirectory(storage.work().getParentFile());
            storage.syncDirectory(storage.work().getParentFile().getParentFile());
            state(storage,transaction,"active");active=true;
            replace(storage,new File(incoming,"data"),prepared.preferences);
            state(storage,transaction,"committed");
        } catch(Exception failure) {
            if(active) {
                try { rollback(storage,transaction); }
                catch(Exception rollbackFailure) { failure.addSuppressed(rollbackFailure);throw new IOException("Restore was interrupted. Reopen Volt to recover the previous data; do not clear app storage.",failure); }
            } else {
                try{deleteTree(transaction);}catch(IOException cleanup){failure.addSuppressed(cleanup);}
            }
            if(failure instanceof IOException)throw(IOException)failure;
            throw new IOException("The restore failed; the previous data was retained.",failure);
        }
        // A completed restore remains committed even if removing its private recovery copy fails.
        try{deleteTree(transaction);}catch(IOException ignored){}
    }

    /** Run before opening any models, preferences listeners, services or databases. */
    static boolean recover(Storage storage)throws IOException {
        File work=storage.work();
        if(work.exists()&&(!work.isDirectory()||Files.isSymbolicLink(work.toPath())||work.listFiles()==null))throw new IOException("The restore recovery folder is unavailable. Keep app storage intact.");
        File transaction=new File(work,"transaction");boolean recovered=false;
        if(transaction.exists()) {
            if(!transaction.isDirectory()||Files.isSymbolicLink(transaction.toPath()))throw new IOException("The restore recovery record is unavailable. Keep app storage intact.");
            File state=new File(transaction,"state");
            if(!state.exists())deleteTree(transaction); // Snapshot was incomplete; live data was never touched.
            else {
                String value=new String(readFile(state,32),StandardCharsets.UTF_8);
                if(value.equals("committed"))deleteTree(transaction);
                else if(value.equals("active")){rollback(storage,transaction);recovered=true;}
                else throw new IOException("The restore recovery record is unreadable. Keep app storage intact.");
            }
        }
        File[] children=storage.work().listFiles();
        if(children==null&&work.exists())throw new IOException("The restore recovery folder cannot be read. Keep app storage intact.");
        if(children!=null)for(File file:children) {
            if(file.getName().matches("stage-[a-f0-9-]{36}"))deleteTree(file);
            else throw new IOException("An unexpected restore recovery file needs attention. Keep app storage intact.");
        }
        return recovered;
    }

    static void discard(Storage storage,Prepared prepared)throws IOException {
        if(prepared!=null&&!prepared.consumed&&isStage(storage,prepared.directory)) {deleteTree(prepared.directory);prepared.consumed=true;}
    }
    private static void rollback(Storage storage,File transaction)throws IOException {
        File previous=new File(transaction,"previous");
        Map<String,Map<String,Object>> prefs=decodePreferences(readFile(new File(previous,PREFS),MAX_PREFERENCES));
        // A committed rename can succeed before its directory sync fails. Re-arm recovery
        // durably before rollback writes, so another interruption cannot expose a mixed tree.
        state(storage,transaction,"active");
        // Discard staged new data first so recovery can reclaim enough disk space for the old data.
        deleteTree(new File(transaction,"incoming"));
        replace(storage,previous,prefs);
        state(storage,transaction,"committed");deleteTree(transaction);
    }
    private static void snapshot(Storage storage,File destination)throws IOException {
        for(FileEntry entry:collect(storage.files(),storage.database()))copyFile(entry.file,contained(destination,entry.name),MAX_FILE_BYTES);
        writePreferencesFile(new File(destination,PREFS),storage.preferences());
        syncDirectories(storage,destination);
    }
    private static void replace(Storage storage,File source,Map<String,Map<String,Object>> prefs)throws IOException {
        // Clear all replaced roots first. Unknown files within these app-owned journal directories
        // are not preserved; transient documents and owner-capture recovery live outside them.
        for(String name:DIRECTORIES)deleteTree(new File(storage.files(),name));
        for(String suffix:new String[]{"","-journal","-wal","-shm"})deleteTree(new File(storage.database().getPath()+suffix));
        for(String name:DIRECTORIES) {
            File target=new File(storage.files(),name);mkdir(target);
            File from=new File(source,"files/"+name);File[] files=from.listFiles();
            if(files!=null)for(File file:files)copyFile(file,new File(target,file.getName()),MAX_FILE_BYTES);
        }
        File db=new File(source,"database/"+DATABASE);if(db.exists())copyFile(db,storage.database(),64L*1024*1024);
        storage.preferences(prefs);
        // File fsync alone does not persist new names or deleted journals. Complete the live
        // directory updates before committed allows recovery to discard the previous dataset.
        for(String name:DIRECTORIES)storage.syncDirectory(new File(storage.files(),name));
        storage.syncDirectory(storage.files());
        File databaseDirectory=storage.database().getParentFile();
        if(databaseDirectory.exists())storage.syncDirectory(databaseDirectory);
        storage.syncDirectory(storage.files().getParentFile());
    }
    private static void checkDatabaseClosed(Storage storage)throws IOException {
        for(String suffix:new String[]{"-journal","-wal","-shm"}) {
            File file=new File(storage.database().getPath()+suffix);
            if(file.exists()&&file.length()>0)throw new IOException("History is still open. Close recordings and try the backup again.");
        }
    }
    private static void ensureNoTransaction(Storage storage)throws IOException {
        if(new File(storage.work(),"transaction").exists())throw new IOException("Reopen Volt to finish recovering the previous restore first.");
    }
    private static boolean isStage(Storage storage,File directory)throws IOException {
        return directory.isDirectory()&&directory.getName().matches("stage-[a-f0-9-]{36}")&&directory.getCanonicalFile().getParentFile().equals(storage.work().getCanonicalFile());
    }
    private static void state(Storage storage,File transaction,String value)throws IOException {
        File temporary=new File(transaction,"state.tmp");
        try(FileOutputStream out=new FileOutputStream(temporary)){out.write(value.getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        if(!temporary.renameTo(new File(transaction,"state")))throw new IOException("Could not save restore recovery state.");
        storage.syncDirectory(transaction);
    }

    /** Persist directory entries as well as file bytes; never silently weaken restore durability. */
    static void syncDirectory(File directory)throws IOException {
        if(!directory.isDirectory()||Files.isSymbolicLink(directory.toPath()))throw new IOException("Restore storage cannot be synchronized.");
        try(FileChannel channel=FileChannel.open(directory.toPath(),StandardOpenOption.READ)){channel.force(true);}
    }
    private static void syncDirectories(Storage storage,File directory)throws IOException {
        if(Files.isSymbolicLink(directory.toPath()))throw new IOException("Unexpected restore storage link.");
        File[] children=directory.listFiles();
        if(children==null)throw new IOException("Restore storage cannot be read.");
        for(File child:children)if(child.isDirectory())syncDirectories(storage,child);
        storage.syncDirectory(directory);
    }

    private static final class FileEntry {
        final String name;final File file;final long length;FileEntry(String name,File file){this.name=name;this.file=file;this.length=file.length();}
    }
    private static List<FileEntry> collect(File files,File database)throws IOException {
        List<FileEntry> result=new ArrayList<>();long total=0;
        for(String directory:DIRECTORIES) {
            File folder=new File(files,directory);File[] children=folder.listFiles();
            if(folder.exists()&&children==null)throw new IOException("A recording folder could not be read.");
            if(children!=null)for(File child:children) {
                String name="files/"+directory+"/"+child.getName();
                // Temporary tail-repair files are not durable recordings.
                if(!allowedFile(name)){if(child.getName().endsWith(".repair"))continue;throw new IOException("An unsupported recording file needs attention before backup.");}
                if(Files.isSymbolicLink(child.toPath())||!child.isFile()||!child.getCanonicalFile().getParentFile().equals(folder.getCanonicalFile()))throw new IOException("Unexpected recording file.");
                if(child.length()>fileLimit(name))throw new IOException("One recording is too large for this backup format.");
                total=addBounded(total,child.length(),MAX_BYTES);result.add(new FileEntry(name,child));
            }
        }
        if(database.exists()) {
            if(Files.isSymbolicLink(database.toPath())||!database.isFile()||database.length()>64L*1024*1024)throw new IOException("History storage is not a supported database.");
            total=addBounded(total,database.length(),MAX_BYTES);result.add(new FileEntry("database/"+DATABASE,database));
        }
        if(result.size()>MAX_ENTRIES)throw new IOException("There are too many recording files for this backup format.");
        Collections.sort(result,Comparator.comparing(e->e.name));return result;
    }
    static boolean allowedFile(String name) {
        if(name==null||name.length()>180)return false;
        return name.equals("database/"+DATABASE)||name.matches("files/rides/"+UUID_PATTERN+"\\.csv")
            ||name.matches("files/charge-recordings/"+UUID_PATTERN+"\\.(csv|active)")
            ||name.matches("files/phone-rides/[a-zA-Z0-9-]{1,80}-(phone|motion)\\.csv");
    }
    private static long fileLimit(String name){return name.startsWith("database/")?64L*1024*1024:MAX_FILE_BYTES;}
    private static File contained(File base,String relative)throws IOException {
        File file=new File(base,relative);String parent=base.getCanonicalPath()+File.separator;
        if(!file.getCanonicalPath().startsWith(parent))throw new IOException("Invalid archive path.");return file;
    }
    private static void copyFile(File from,File to,long limit)throws IOException {
        if(Files.isSymbolicLink(from.toPath())||!from.isFile())throw new IOException("A restore file is unavailable.");
        mkdir(to.getParentFile());try(InputStream in=new FileInputStream(from);FileOutputStream out=new FileOutputStream(to)){copy(in,out,new byte[BLOCK],limit);out.getFD().sync();}
    }
    static void deleteTree(File file)throws IOException {
        if(!file.exists())return;
        if(Files.isSymbolicLink(file.toPath())){if(!file.delete())throw new IOException("Could not remove temporary data.");return;}
        if(file.isDirectory()){File[] children=file.listFiles();if(children==null)throw new IOException("Could not read temporary data.");for(File child:children)deleteTree(child);}
        if(!file.delete())throw new IOException("Could not remove temporary data.");
    }
    private static void mkdir(File directory)throws IOException {
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("There is not enough available app storage.");
        if(Files.isSymbolicLink(directory.toPath()))throw new IOException("Unexpected storage link.");
    }
    private static long copy(InputStream in,OutputStream out,byte[] buffer,long limit)throws IOException {
        long total=0;for(int n;(n=in.read(buffer))!=-1;){if(n==0)continue;total=addBounded(total,n,limit);out.write(buffer,0,n);}return total;
    }
    private static long addBounded(long current,long amount,long limit)throws IOException {
        if(amount<0||current>limit-amount)throw new IOException("The backup exceeds the supported storage limit.");return current+amount;
    }
    private static byte[] readBounded(InputStream in,int maximum)throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();copy(in,out,new byte[8192],maximum);return out.toByteArray();
    }
    private static byte[] readFile(File file,int maximum)throws IOException {try(InputStream in=new FileInputStream(file)){return readBounded(in,maximum);}}

    /** Reject known startup/read-path type mismatches before replacing live state. Unknown keys
     * remain portable, preserving preferences added by compatible versions. */
    static void validatePreferenceTypes(Map<String,Map<String,Object>> all)throws IOException {
        Map<String,Object> app=all.get("app_settings");
        types(app,String.class,"theme units temperature_unit time_format more_section scooter_section stats_density stats_query stats_section stats_favourite_order ride_chart_key ride_selected last_ride_label phone_placement owner_import_applied stats_chart_query more_wide_section_scroll map_tile_url map_tile_attribution map_tile_attribution_url");
        types(app,Boolean.class,"notification_prompted keep_screen_awake auto_connect background_connection phone_gps phone_motion history_enabled dashboard_editing developer_options map_basemap_enabled map_basemap_explained haptics_enabled");
        types(app,Integer.class,"poll_interval_ms dashboard_columns stats_chart_window more_wide_menu_scroll more_away_boot stats_away_boot");
        types(app,Long.class,"more_away_elapsed stats_away_elapsed");types(app,Float.class,"text_scale");
        types(app,Set.class,"stats_favourites stats_chart_favourites ride_comparison ride_chart_series charge_chart_series");
        for(String key:app.keySet()) {
            if(key.startsWith("dashboard_v1_")||key.startsWith("dashboard_slot_v1_")||key.startsWith("charge_charger_")||key.startsWith("range_source_"))type(app,key,String.class);
            if(key.startsWith("dashboard_empty_example_v1_")||key.startsWith("range_follow_mode_")||key.startsWith("stats_group_")||key.startsWith("stats_chart_group_"))type(app,key,Boolean.class);
        }
        if(app.containsKey("text_scale")) {float scale=(Float)app.get("text_scale");if(!Float.isFinite(scale)||scale<.5f||scale>3f)throw new IOException("The backup has an invalid display text scale.");}
        types(all.get("profiles"),String.class,"items active");types(all.get("profiles"),Boolean.class,"legacy_migrated");
        types(all.get("scooter"),String.class,"identity last_connected_mac mac serial");types(all.get("scooter"),Integer.class,"role");
        for(String key:all.get("control_evidence").keySet())type(all.get("control_evidence"),key,String.class);
        Map<String,Object> onboarding=all.get("onboarding");
        types(onboarding,Boolean.class,"rerun onboarding_done import_failed nearby_asked connect_requested connect_tried");
        types(onboarding,Integer.class,"step");types(onboarding,String.class,"path profile_id created_profile_id pending_name");
        for(String key:onboarding.keySet())if(key.startsWith("tick_"))type(onboarding,key,Boolean.class);
    }
    private static void types(Map<String,Object> values,Class<?> expected,String keys)throws IOException {
        for(String key:keys.split(" "))type(values,key,expected);
    }
    private static void type(Map<String,Object> values,String key,Class<?> expected)throws IOException {
        if(values.containsKey(key)&&!expected.isInstance(values.get(key)))throw new IOException("The backup has an incompatible app preference type.");
    }

    static byte[] encodePreferences(Map<String,Map<String,Object>> all)throws IOException {
        ByteArrayOutputStream buffer=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(buffer);
        out.writeInt(PREFERENCES.length);
        for(String name:PREFERENCES) {
            string(out,name);Map<String,Object> values=all.get(name);if(values==null)throw new IOException("Missing app preferences.");
            if(values.size()>20000)throw new IOException("Too many app preferences.");out.writeInt(values.size());
            for(String key:new TreeSet<>(values.keySet())) {
                if(key.length()>512)throw new IOException("An app preference key is too long.");string(out,key);Object value=values.get(key);
                if(value instanceof String){out.writeByte(1);string(out,(String)value);}
                else if(value instanceof Boolean){out.writeByte(2);out.writeBoolean((Boolean)value);}
                else if(value instanceof Integer){out.writeByte(3);out.writeInt((Integer)value);}
                else if(value instanceof Long){out.writeByte(4);out.writeLong((Long)value);}
                else if(value instanceof Float){out.writeByte(5);out.writeFloat((Float)value);}
                else if(value instanceof Set){out.writeByte(6);Set<?> set=(Set<?>)value;if(set.size()>20000)throw new IOException("An app preference set is too large.");out.writeInt(set.size());for(Object item:set){if(!(item instanceof String))throw new IOException("Unsupported app preference.");string(out,(String)item);}}
                else throw new IOException("Unsupported app preference type.");
                if(buffer.size()>MAX_PREFERENCES)throw new IOException("App preferences exceed the backup limit.");
            }
        }
        out.flush();return buffer.toByteArray();
    }
    static Map<String,Map<String,Object>> decodePreferences(byte[] data)throws IOException {
        if(data.length>MAX_PREFERENCES)throw new IOException("App preferences exceed the backup limit.");
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(data));Map<String,Map<String,Object>> result=new LinkedHashMap<>();
        if(in.readInt()!=PREFERENCES.length)throw new IOException("Unsupported app preference inventory.");
        for(String expected:PREFERENCES) {
            if(!expected.equals(string(in,512)))throw new IOException("Unsupported app preference inventory.");
            int count=in.readInt();if(count<0||count>20000)throw new IOException("Invalid preference count.");Map<String,Object> values=new LinkedHashMap<>();
            for(int i=0;i<count;i++) {
                String key=string(in,2048);if(key.length()>512||values.containsKey(key))throw new IOException("Invalid or duplicate preference key.");
                Object value;
                switch(in.readUnsignedByte()) {
                    case 1:value=string(in,MAX_PREFERENCES);break;
                    case 2:int flag=in.readUnsignedByte();if(flag>1)throw new IOException("Invalid preference boolean.");value=flag==1;break;
                    case 3:value=in.readInt();break;case 4:value=in.readLong();break;case 5:value=in.readFloat();break;
                    case 6:int size=in.readInt();if(size<0||size>20000)throw new IOException("Invalid preference set size.");Set<String> items=new LinkedHashSet<>();for(int j=0;j<size;j++)if(!items.add(string(in,MAX_PREFERENCES)))throw new IOException("Duplicate preference set item.");value=items;break;
                    default:throw new IOException("Unsupported preference type.");
                }
                values.put(key,value);
            }
            result.put(expected,values);
        }
        if(in.read()!=-1)throw new IOException("Unexpected preference payload.");return result;
    }
    private static void writePreferencesFile(File file,Map<String,Map<String,Object>> prefs)throws IOException {
        byte[] bytes=encodePreferences(prefs);try(FileOutputStream out=new FileOutputStream(file)){out.write(bytes);out.getFD().sync();}finally{Arrays.fill(bytes,(byte)0);}
    }
    private static void string(DataOutputStream out,String value)throws IOException {
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX_PREFERENCES)throw new IOException("App preference is too large.");out.writeInt(bytes.length);out.write(bytes);
    }
    private static String string(DataInputStream in,int maximum)throws IOException {
        int length=in.readInt();if(length<0||length>maximum||length>in.available())throw new IOException("Invalid preference string.");
        byte[] bytes=new byte[length];in.readFully(bytes);
        try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(Exception e){throw new IOException("Invalid preference text.",e);}
    }

    private static void requirePassword(char[] password)throws IOException {
        if(password==null||password.length<8||password.length>1024)throw new IOException("Use a backup password of 8 to 1,024 characters.");
    }
    private static byte[] header() {
        byte[] value=new byte[MAGIC.length+16+8];System.arraycopy(MAGIC,0,value,0,MAGIC.length);
        byte[] random=new byte[24];RANDOM.nextBytes(random);System.arraycopy(random,0,value,MAGIC.length,24);return value;
    }
    private static SecretKey key(char[] password,byte[] header)throws IOException {
        PBEKeySpec spec=new PBEKeySpec(password,Arrays.copyOfRange(header,MAGIC.length,MAGIC.length+16),ITERATIONS,256);
        byte[] bytes=null;
        try{bytes=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();return new SecretKeySpec(bytes,"AES");}
        catch(GeneralSecurityException e){throw new IOException("Backup encryption is unavailable on this device.",e);}
        finally{spec.clearPassword();if(bytes!=null)Arrays.fill(bytes,(byte)0);}
    }
    private static byte[] crypt(int mode,SecretKey key,byte[] header,int index,int length,byte[] bytes,int count)throws IOException {
        try {
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");byte[] nonce=new byte[12];
            System.arraycopy(header,header.length-8,nonce,0,8);ByteBuffer.wrap(nonce,8,4).putInt(index);
            cipher.init(mode,key,new GCMParameterSpec(128,nonce));cipher.updateAAD(header);
            cipher.updateAAD(ByteBuffer.allocate(8).putInt(index).putInt(length).array());return cipher.doFinal(bytes,0,count);
        }catch(GeneralSecurityException e){throw new IOException(mode==Cipher.DECRYPT_MODE?"The password is incorrect or the backup is damaged.":"The backup could not be encrypted.",e);}
    }
    private static void decrypt(InputStream input,OutputStream output,char[] password)throws IOException {
        DataInputStream in=new DataInputStream(input);byte[] header=new byte[MAGIC.length+24];in.readFully(header);
        if(!Arrays.equals(MAGIC,Arrays.copyOf(header,MAGIC.length)))throw new IOException("Choose a Volt encrypted app backup.");
        SecretKey key=key(password,header);long total=0;int index=0;
        while(true) {
            int length=in.readInt();if(length<0||length>BLOCK)throw new IOException("Invalid encrypted backup record.");
            byte[] encrypted=new byte[length+16];in.readFully(encrypted);
            byte[] plain=crypt(Cipher.DECRYPT_MODE,key,header,index++,length,encrypted,encrypted.length);
            if(length==0){if(in.read()!=-1)throw new IOException("The backup has unexpected trailing data.");break;}
            total=addBounded(total,plain.length,MAX_BYTES+32L*1024*1024);output.write(plain);Arrays.fill(plain,(byte)0);
        }
    }
    private static final class RecordOutput extends OutputStream {
        final DataOutputStream out;final byte[] header,buffer=new byte[BLOCK];final SecretKey key;int count,index;long total;boolean finished;
        RecordOutput(OutputStream out,byte[] header,SecretKey key){this.out=new DataOutputStream(out);this.header=header;this.key=key;}
        @Override public void write(int b)throws IOException {buffer[count++]=(byte)b;if(count==BLOCK)record();}
        @Override public void write(byte[] b,int off,int length)throws IOException {
            if(finished)throw new IOException("Backup already completed.");
            while(length>0){int n=Math.min(length,BLOCK-count);System.arraycopy(b,off,buffer,count,n);count+=n;off+=n;length-=n;if(count==BLOCK)record();}
        }
        private void record()throws IOException {
            total=addBounded(total,count,MAX_BYTES+32L*1024*1024);out.writeInt(count);
            out.write(crypt(Cipher.ENCRYPT_MODE,key,header,index++,count,buffer,count));Arrays.fill(buffer,(byte)0);count=0;
        }
        void finish()throws IOException {if(finished)return;if(count>0)record();record();finished=true;out.flush();}
        void clear(){Arrays.fill(buffer,(byte)0);}
    }
}
