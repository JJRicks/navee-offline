package local.navee.offlinelock;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.*;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Password-protected, local full-data transfer. OS permissions, cached map tiles and pending
 * setup/export operations are intentionally outside this portable snapshot. The target's
 * owner-capture cleanup journal is never read, exported, cleared or replaced.
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
    private AppDataBackup() {}

    public static final class PreparedRestore {
        private final AppDataArchive.Prepared prepared;
        public final long createdAtMs, totalBytes;
        public final int fileCount, preferenceCount, profileCount;
        private PreparedRestore(AppDataArchive.Prepared prepared) {
            this.prepared=prepared;createdAtMs=prepared.createdAtMs;totalBytes=prepared.totalBytes;fileCount=prepared.fileCount;
            int preferences=0,profiles=0;
            for(Map<String,Object> values:prepared.preferences.values())preferences+=values.size();
            try{Object items=prepared.preferences.get("profiles").get("items");if(items instanceof String)profiles=new JSONArray((String)items).length();}catch(Exception ignored){}
            preferenceCount=preferences;profileCount=profiles;
        }
    }

    public static synchronized void write(Context context,OutputStream destination,char[] password)throws IOException {
        AppDataArchive.write(new AndroidStorage(context),destination,password);
    }
    public static synchronized PreparedRestore stage(Context context,InputStream source,char[] password)throws IOException {
        AndroidStorage storage=new AndroidStorage(context);
        AppDataArchive.Prepared prepared=AppDataArchive.stage(storage,source,password);
        try { validateProfiles(prepared.preferences);return new PreparedRestore(prepared); }
        catch(Exception failure) {try{AppDataArchive.discard(storage,prepared);}catch(IOException cleanup){failure.addSuppressed(cleanup);}if(failure instanceof IOException)throw(IOException)failure;throw new IOException("The backup has invalid scooter profiles.",failure);}
    }
    public static synchronized void apply(Context context,PreparedRestore restore)throws IOException {
        AppDataArchive.apply(new AndroidStorage(context),restore==null?null:restore.prepared);
    }
    public static synchronized void discard(Context context,PreparedRestore restore)throws IOException {
        AppDataArchive.discard(new AndroidStorage(context),restore==null?null:restore.prepared);
    }
    /** True means recovery may require copying recordings; run recover behind a startup screen. */
    public static boolean needsRecovery(Context context) {
        File work=new File(context.getNoBackupFilesDir(),"app-data-transfer");
        if(!work.exists())return false;
        File[] files=work.listFiles();return files==null||files.length>0;
    }
    /** Returns true if an interrupted restore rolled back to the previous full data set. */
    public static synchronized boolean recover(Context context)throws IOException {
        return AppDataArchive.recover(new AndroidStorage(context));
    }

    private static void validateProfiles(Map<String,Map<String,Object>> preferences)throws IOException {
        Object raw=preferences.get("profiles").get("items");if(raw==null)return;
        String text=(String)raw;int depth=0;boolean quoted=false,escaped=false;
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
                String identity=p.optString("identity","");if(!identity.isEmpty()&&!identity.matches("(?i)[0-9a-f]{12}"))throw new IOException("The backup has an invalid scooter setup identity.");
            }
        }catch(IOException e){throw e;}catch(Exception e){throw new IOException("The backup has unreadable scooter profiles.",e);}
    }

    private static final class AndroidStorage implements AppDataArchive.Storage {
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
        @SuppressWarnings("unchecked")
        public void preferences(Map<String,Map<String,Object>> all)throws IOException {
            for(String name:AppDataArchive.PREFERENCES) {
                Map<String,Object> values=all.get(name);if(values==null)throw new IOException("The backup is missing app preferences.");
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
        }
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
