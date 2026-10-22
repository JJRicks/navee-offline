package local.navee.offlinelock;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.*;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Password-protected, local category backup and restore (docs/unified-backup.md). OS permissions,
 * cached map tiles, pending setup/export operations and physical scooter settings are outside
 * every backup. The target's owner-capture cleanup journal is never read, exported, cleared or
 * replaced. Older full backups, .voltrides, dashboard layouts and pairing-only owner files still import.
 *
 * All methods perform disk/crypto work and belong on a worker. Before write/apply, the caller
 * must stop app writers, drain history/recording queues and close every database. After apply,
 * discard all old model instances and restart the app process. Startup recovery must finish
 * before the activity or service opens any model or registers preference listeners.
 */
public final class AppDataBackup {
    public static final String MIME_TYPE = "application/octet-stream";
    public static final String FILE_EXTENSION = ".voltbackup";
    public static final long MAX_UNCOMPRESSED_BYTES = AppDataArchive.MAX_BYTES;
    /** Stable category IDs in display order. */
    public static final List<String> CATEGORIES = Collections.unmodifiableList(Arrays.asList(BackupRegistry.CATEGORIES));
    public static final int UNSUPPORTED = BackupArchive.UNSUPPORTED, ENCRYPTED = BackupArchive.ENCRYPTED, RIDE_FILE = BackupArchive.RIDE_FILE, LAYOUT_FILE = BackupArchive.LAYOUT_FILE;
    public static final String SKIP = BackupArchive.SKIP;
    private AppDataBackup() {}

    public static String label(String category){return BackupRegistry.label(category);}
    public static final class PreparedRestore {
        final BackupArchive.Prepared prepared;
        public final long createdAtMs, totalBytes;
        public final int fileCount, preferenceCount, profileCount;
        public final boolean complete, encrypted;
        /** v2, v1, rides, layout or owner (pairing access only; never a complete backup). */
        public final String format;
        /** Categories in the file with a preview count, in display order. */
        public final Map<String,Integer> categories;
        private PreparedRestore(BackupArchive.Prepared prepared) {
            this.prepared=prepared;createdAtMs=prepared.createdAtMs;totalBytes=prepared.totalBytes;fileCount=prepared.fileCount;preferenceCount=prepared.preferenceCount;
            profileCount=prepared.profiles.size();complete=prepared.complete;encrypted=prepared.encrypted();format=prepared.kind;categories=Collections.unmodifiableMap(new LinkedHashMap<>(prepared.categories));
        }
    }
    /** A backup scooter that needs a destination; `suggested` may be null. */
    public static final class Destination {
        public final String source, name, suggested; public final boolean addable;
        private Destination(BackupArchive.Destination d){source=d.source;name=d.name;suggested=d.suggested;addable=d.addable;}
    }
    public static final class Review {
        final BackupArchive.Plan plan; public final List<String> lines; public final boolean replace;
        private Review(BackupArchive.Plan plan){this.plan=plan;lines=plan.lines;replace=plan.replace;}
    }

    /** {items, bytes} per category, for the Back up list. Lists files; parses nothing large. */
    public static synchronized Map<String,long[]> inventory(Context context)throws IOException {return BackupArchive.inventory(new AndroidStorage(context));}
    /** Unreadable inventory also counts as existing data: replacement must be chosen explicitly. */
    public static synchronized boolean hasData(Context context) {
        return BackupArchive.hasData(new AndroidStorage(context));
    }
    /** This phone's scooters as ID → name, in their saved order. */
    public static Map<String,String> localScooters(Context context)throws IOException {
        Map<String,String> result=new LinkedHashMap<>();for(BackupProfiles.Profile p:BackupArchive.profiles(new AndroidStorage(context).preferences()))result.put(p.id,p.name);return result;
    }
    /** A complete backup of every category. */
    public static synchronized void write(Context context,OutputStream destination,char[] password)throws IOException {write(context,destination,password,CATEGORIES);}
    public static synchronized void write(Context context,OutputStream destination,char[] password,Collection<String> categories)throws IOException {
        BackupArchive.write(new AndroidStorage(context),destination,password,categories);
    }
    public static int sniff(byte[] head,int length){return BackupArchive.sniff(head,length);}
    /** A password is needed only when {@link #sniff} reports an encrypted file. */
    public static synchronized PreparedRestore stage(Context context,InputStream source,char[] password)throws IOException {
        return new PreparedRestore(BackupArchive.stage(new AndroidStorage(context),source,password));
    }
    public static synchronized List<Destination> destinations(Context context,PreparedRestore restore,Collection<String> categories)throws IOException {
        List<Destination> result=new ArrayList<>();for(BackupArchive.Destination d:BackupArchive.destinations(new AndroidStorage(context),prepared(restore),categories))result.add(new Destination(d));return result;
    }
    public static synchronized Review review(Context context,PreparedRestore restore,Collection<String> categories,boolean replace,Map<String,String> destinations)throws IOException {
        return new Review(BackupArchive.plan(new AndroidStorage(context),prepared(restore),categories,replace,destinations));
    }
    public static synchronized void apply(Context context,Review review)throws IOException {
        BackupTransaction.apply(new AndroidStorage(context),review==null?null:review.plan);
    }
    /** Replace everything from a complete backup, as the earlier full restore did. */
    public static synchronized void apply(Context context,PreparedRestore restore)throws IOException {
        apply(context,review(context,restore,CATEGORIES,true,Collections.<String,String>emptyMap()));
    }
    public static synchronized void discard(Context context,PreparedRestore restore)throws IOException {
        BackupArchive.discard(new AndroidStorage(context),restore==null?null:restore.prepared);
    }
    /** True means recovery may require copying recordings; run recover behind a startup screen. */
    public static boolean needsRecovery(Context context) {
        File work=new File(context.getNoBackupFilesDir(),"app-data-transfer");
        if(!work.exists())return false;
        File[] files=work.listFiles();return files==null||files.length>0;
    }
    /** Returns true if an interrupted restore rolled back to the previous data. */
    public static synchronized boolean recover(Context context)throws IOException {
        return BackupTransaction.recover(new AndroidStorage(context));
    }
    private static BackupArchive.Prepared prepared(PreparedRestore restore)throws IOException {if(restore==null)throw new IOException("Choose the backup again.");return restore.prepared;}

    /** Android's own JSON reader must accept what ScooterProfiles will read; unknown fields are tolerated. */
    private static void validateProfiles(String text)throws IOException {
        int depth=0;boolean quoted=false,escaped=false;
        // Bound nesting before Android's recursive JSON parser sees user-selected data.
        for(int i=0;i<text.length();i++) {char c=text.charAt(i);if(quoted){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='"')quoted=false;}else if(c=='"')quoted=true;else if(c=='['||c=='{'){if(++depth>8)throw new IOException("The backup has invalid scooter profiles.");}else if(c==']'||c=='}')depth--;}
        try {
            JSONArray profiles=new JSONArray(text);if(profiles.length()>2000)throw new IOException("The backup has too many scooter profiles.");Set<String> ids=new HashSet<>();
            for(int i=0;i<profiles.length();i++) {
                JSONObject p=profiles.getJSONObject(i);Object id=p.opt("id");
                if(!(id instanceof String)||((String)id).isEmpty()||((String)id).length()>128||!ids.add((String)id))throw new IOException("The backup has invalid scooter profile identifiers.");
                for(String field:new String[]{"name","identity","mac","serial"})if(p.has(field)&&!(p.opt(field) instanceof String))throw new IOException("The backup has invalid scooter profile fields.");
                if(p.has("role")&&(!(p.opt("role") instanceof Number)||(p.optDouble("role",-1)!=0&&p.optDouble("role",-1)!=1)))throw new IOException("The backup has an invalid scooter owner role.");
                if(p.has("lastConnected")&&!(p.opt("lastConnected") instanceof Number))throw new IOException("The backup has an invalid scooter connection date.");
                if(!validDescriptorFields(p))throw new IOException("The backup has invalid scooter profile fields.");
                String identity=p.optString("identity","");if(!identity.isEmpty()&&!identity.matches("(?i)[0-9a-f]{12}"))throw new IOException("The backup has an invalid scooter setup identity.");
            }
        }catch(IOException e){throw e;}catch(Exception e){throw new IOException("The backup has unreadable scooter profiles.",e);}
    }

    /** Model evidence fields (advertised PID, last-seen firmware, default name). Types and bounds only, so a newer backup is not rejected. */
    static boolean validDescriptorFields(JSONObject p) {
        Object pid=p.opt("pid_advertised");if(pid!=null&&!(pid instanceof String&&((String)pid).matches("[0-9]{0,5}")))return false;
        for(String field:new String[]{"fw_meter","fw_controller","fw_bms","fw_screen","fw_uwb"}){Object v=p.opt(field);if(v!=null&&!(v instanceof String&&((String)v).matches("[\\x20-\\x7e]{0,16}")))return false;}
        for(String field:new String[]{"pid_advertised_wall","fw_wall"})if(p.has(field)&&!(p.opt(field) instanceof Number))return false;
        return !p.has("name_default")||p.opt("name_default") instanceof Boolean;
    }

    private static final class AndroidStorage implements BackupArchive.Storage {
        final Context context;
        AndroidStorage(Context context){this.context=context.getApplicationContext();}
        public File files(){return context.getFilesDir();}
        public File database(){return context.getDatabasePath(AppDataArchive.DATABASE);}
        public File work(){return new File(context.getNoBackupFilesDir(),"app-data-transfer");}
        public int versionCode(){try{return context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionCode;}catch(Exception e){return 0;}}
        public Map<String,Map<String,Object>> preferences()throws IOException {
            Map<String,Map<String,Object>> all=new LinkedHashMap<>();
            for(String name:AppDataArchive.PREFERENCES) {
                Map<String,Object> values=new LinkedHashMap<>();
                for(Map.Entry<String,?> entry:context.getSharedPreferences(name,Context.MODE_PRIVATE).getAll().entrySet()) {
                    Object value=entry.getValue();if(value instanceof Set)value=new LinkedHashSet<>((Set<?>)value);
                    values.put(entry.getKey(),value);
                }
                all.put(name,values);
            }
            return all;
        }
        /** Replaces only the given namespaces, each with one Android commit. */
        @SuppressWarnings("unchecked")
        public void preferences(Map<String,Map<String,Object>> all)throws IOException {
            for(String name:AppDataArchive.PREFERENCES) {
                Map<String,Object> values=all.get(name);if(values==null)continue;
                SharedPreferences.Editor edit=context.getSharedPreferences(name,Context.MODE_PRIVATE).edit().clear();
                for(Map.Entry<String,Object> entry:values.entrySet()) {
                    String key=entry.getKey();Object value=entry.getValue();
                    if(value instanceof String)edit.putString(key,(String)value);
                    else if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);
                    else if(value instanceof Integer)edit.putInt(key,(Integer)value);
                    else if(value instanceof Long)edit.putLong(key,(Long)value);
                    else if(value instanceof Float)edit.putFloat(key,(Float)value);
                    else if(value instanceof Set)edit.putStringSet(key,new LinkedHashSet<>((Set<String>)value));
                    else throw new IOException("The backup has an unsupported preference type.");
                }
                // API commits keep Android's process-local preferences caches coherent.
                if(!edit.commit())throw new IOException("App preferences could not be written. Previous data will be recovered.");
            }
            // SharedPreferences commits sync XML bytes; persist the rename/directory entries too
            // before the archive transaction can discard its rollback snapshot.
            AppDataArchive.syncDirectory(new File(context.getApplicationInfo().dataDir,"shared_prefs"));
        }
        public void validateProfiles(String items)throws IOException {AppDataBackup.validateProfiles(items);}
        public long usableSpace(File directory){File existing=directory;while(existing!=null&&!existing.exists())existing=existing.getParentFile();return existing==null?0:existing.getUsableSpace();}
        public String[] dashboardLayout(String json)throws IOException {
            try{return DashboardConfig.transferValues(json);}catch(JSONException|RuntimeException invalid){throw new IOException("Not a dashboard layout file.",invalid);}
        }
        public Set<String> historyProfiles(File file)throws IOException {
            Set<String> result=new LinkedHashSet<>();
            try(SQLiteDatabase db=SQLiteDatabase.openDatabase(file.getPath(),null,SQLiteDatabase.OPEN_READONLY|SQLiteDatabase.NO_LOCALIZED_COLLATORS)) {
                try(Cursor c=db.rawQuery("SELECT name FROM profiles UNION SELECT profile FROM latest",null)){while(c.moveToNext()&&result.size()<=BackupProfiles.MAX_PROFILES)if(!c.isNull(0))result.add(c.getString(0));}
            }catch(Exception e){throw new IOException("The backup history database could not be read.",e);}
            if(result.size()>BackupProfiles.MAX_PROFILES)throw new IOException("The backup history has too many scooters.");
            return result;
        }
        /** A closed merged copy: this phone's rows win on every UNIQUE key, the backup's rows are
         * added under the mapped profile, then history's own retention keeps it within its limit. */
        public void mergeHistory(File live,File incoming,Map<String,String> mapping,File out)throws IOException {
            for(String suffix:new String[]{"","-journal"})new File(out.getPath()+suffix).delete();
            AppDataArchive.copyFile(live!=null?live:incoming,out,BackupArchive.HISTORY_BYTES);
            try(SQLiteDatabase db=SQLiteDatabase.openDatabase(out.getPath(),null,SQLiteDatabase.OPEN_READWRITE|SQLiteDatabase.NO_LOCALIZED_COLLATORS)) {
                if(live==null)for(String table:new String[]{"latest","detail","minute","daily","profiles","keys"})db.execSQL("DELETE FROM "+table);
                db.execSQL("ATTACH DATABASE ? AS b",new Object[]{incoming.getPath()});
                Map<String,String[]> newest=new HashMap<>();
                db.beginTransaction();
                try {
                    db.execSQL("INSERT OR IGNORE INTO keys(name) SELECT name FROM b.keys");
                    for(Map.Entry<String,String> map:mapping.entrySet()) {
                        String source=map.getKey(),target=map.getValue();
                        db.execSQL("INSERT OR IGNORE INTO profiles(name) VALUES(?)",new Object[]{target});
                        long to=id(db,"SELECT id FROM profiles WHERE name=?",target);Long from=optionalId(db,"SELECT id FROM b.profiles WHERE name=?",source);
                        if(from!=null) {
                            db.execSQL("INSERT OR IGNORE INTO detail(pid,kid,wall,value,session) SELECT ?,k.id,d.wall,d.value,d.session FROM b.detail d JOIN b.keys bk ON bk.id=d.kid JOIN keys k ON k.name=bk.name WHERE d.pid=?",new Object[]{to,from});
                            for(String table:new String[]{"minute","daily"})
                                db.execSQL("INSERT OR IGNORE INTO "+table+"(pid,kid,bucket,total,samples,last_value,first_wall,last_wall,first_session,last_session,gaps) SELECT ?,k.id,r.bucket,r.total,r.samples,r.last_value,r.first_wall,r.last_wall,r.first_session,r.last_session,r.gaps FROM b."+table+" r JOIN b.keys bk ON bk.id=r.kid JOIN keys k ON k.name=bk.name WHERE r.pid=?",new Object[]{to,from});
                        }
                        // Last-seen readings: this phone's snapshot wins, else the newest backup snapshot.
                        try(Cursor c=db.rawQuery("SELECT data FROM b.latest WHERE profile=?",new String[]{source})) {
                            if(c.moveToFirst()){String data=c.getString(0);long wall=new JSONObject(data).optLong("wall",0);String[] best=newest.get(target);if(best==null||Long.parseLong(best[0])<wall)newest.put(target,new String[]{Long.toString(wall),data});}
                        }
                    }
                    for(Map.Entry<String,String[]> latest:newest.entrySet())db.execSQL("INSERT OR IGNORE INTO latest(profile,data) VALUES(?,?)",new Object[]{latest.getKey(),latest.getValue()[1]});
                    db.setTransactionSuccessful();
                } finally { db.endTransaction(); }
                db.execSQL("DETACH DATABASE b");
                TelemetryHistory.applyRetention(db,System.currentTimeMillis());
                db.execSQL("PRAGMA incremental_vacuum");
            } catch(IOException e){throw e;} catch(Exception e){throw new IOException("Readings history could not be merged.",e);}
            File journal=new File(out.getPath()+"-journal");if(journal.exists()&&journal.length()>0)throw new IOException("Readings history could not be merged.");journal.delete();
            if(out.length()>TelemetryHistory.DB_BYTES)throw new IOException("Readings history is too large to merge.");
            try(FileOutputStream sync=new FileOutputStream(out,true)){sync.getFD().sync();}
        }
        private static long id(SQLiteDatabase db,String sql,String arg)throws IOException{Long id=optionalId(db,sql,arg);if(id==null)throw new IOException("Readings history could not be merged.");return id;}
        private static Long optionalId(SQLiteDatabase db,String sql,String arg){try(Cursor c=db.rawQuery(sql,new String[]{arg})){return c.moveToFirst()?c.getLong(0):null;}}
        public void validateDatabase(File file)throws IOException {
            byte[] header=new byte[16];
            try(DataInputStream in=new DataInputStream(new FileInputStream(file))){in.readFully(header);}
            if(!Arrays.equals(header,new byte[]{'S','Q','L','i','t','e',' ','f','o','r','m','a','t',' ','3',0}))throw new IOException("The backup history database is invalid.");
            try(SQLiteDatabase db=SQLiteDatabase.openDatabase(file.getPath(),null,SQLiteDatabase.OPEN_READONLY|SQLiteDatabase.NO_LOCALIZED_COLLATORS)) {
                if(db.getVersion()!=1||!db.isDatabaseIntegrityOk())throw new IOException("The backup history database is damaged or uses a newer format.");
                Map<String,String> required=new LinkedHashMap<>();
                required.put("latest","profile,data");required.put("profiles","id,name");required.put("keys","id,name");
                required.put("detail","pid,kid,wall,value,session");
                for(String table:new String[]{"minute","daily"})required.put(table,"pid,kid,bucket,total,samples,last_value,first_wall,last_wall,first_session,last_session,gaps");
                try(Cursor c=db.rawQuery("SELECT type,name,sql FROM sqlite_master WHERE type IN ('table','view','trigger')",null)) {
                    while(c.moveToNext()) {
                        String type=c.getString(0),name=c.getString(1),sql=c.getString(2);
                        if(!"table".equals(type)||sql==null||!sql.toUpperCase(Locale.ROOT).startsWith("CREATE TABLE")||(!required.containsKey(name)&&!"android_metadata".equals(name)))
                            throw new IOException("The backup history database has an unsupported schema.");
                    }
                }
                for(Map.Entry<String,String> table:required.entrySet()) {
                    List<String> names=new ArrayList<>();
                    try(Cursor c=db.rawQuery("PRAGMA table_info("+table.getKey()+")",null)){while(c.moveToNext())names.add(c.getString(1));}
                    if(!String.join(",",names).equals(table.getValue()))throw new IOException("The backup history database is missing required readings tables.");
                }
            } catch(IOException e){throw e;}catch(Exception e){throw new IOException("The backup history database could not be verified.",e);}
        }
    }
}
