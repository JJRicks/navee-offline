package local.navee.offlinelock;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;
import org.json.*;

/** Private local owner profiles. Credentials are never included in ordinary diagnostics exports. */
public final class ScooterProfiles {
    public static final class Profile {
        public String id,name,identity,mac,serial; public int role; public long lastConnected;
        public Profile(String id,String name,String identity,int role,String mac,String serial,long lastConnected){this.id=id;this.name=name;this.identity=identity;this.role=role;this.mac=mac;this.serial=serial;this.lastConnected=lastConnected;}
        public int pid(){try{return Integer.parseInt(serial.substring(1,5));}catch(Exception e){return -1;}}
        public boolean configured(){try{return (role==0||role==1)&&Protocol.fromHex(identity).length==6;}catch(Exception e){return false;}}
    }
    private final SharedPreferences prefs,legacy;
    private final ArrayList<Profile> profiles=new ArrayList<>();
    public ScooterProfiles(Context context){
        prefs=context.getSharedPreferences("profiles",Context.MODE_PRIVATE);legacy=context.getSharedPreferences("scooter",Context.MODE_PRIVATE);
        try{JSONArray array=new JSONArray(prefs.getString("items","[]"));for(int i=0;i<array.length();i++){JSONObject o=array.getJSONObject(i);profiles.add(new Profile(o.getString("id"),o.optString("name","UT5 MAX"),o.optString("identity",""),o.optInt("role",0),o.optString("mac",""),o.optString("serial",""),o.optLong("lastConnected",0)));}}catch(JSONException ignored){}
        if(profiles.isEmpty()&&!prefs.getBoolean("legacy_migrated",false)){
            SharedPreferences old=context.getSharedPreferences("scooter",Context.MODE_PRIVATE);
            String identity=old.getString("identity","");
            if(identity.matches("(?i)[0-9a-f]{12}")){Profile p=new Profile(UUID.randomUUID().toString(),"My UT5 MAX",identity,old.getInt("role",0),old.getString("last_connected_mac",old.getString("mac","")),old.getString("serial",""),0);profiles.add(p);prefs.edit().putString("active",p.id).apply();save();}
        }
        if(!profiles.isEmpty())prefs.edit().putBoolean("legacy_migrated",true).apply();
    }
    public synchronized List<Profile> all(){return new ArrayList<>(profiles);}
    public synchronized Profile active(){String id=prefs.getString("active","");for(Profile p:profiles)if(p.id.equals(id))return p;return profiles.isEmpty()?null:profiles.get(0);}
    public synchronized void select(String id){prefs.edit().putString("active",id).apply();}
    public synchronized Profile create(String name,String identity,int role){if(!identity.matches("(?i)[0-9a-f]{12}")||role<0||role>1)throw new IllegalArgumentException("Enter a 12-character owner identity.");Profile p=new Profile(UUID.randomUUID().toString(),name.trim().isEmpty()?"UT5 MAX":name.trim(),identity.toUpperCase(Locale.ROOT),role,"","",0);profiles.add(p);select(p.id);save();return p;}
    public synchronized void update(Profile p){save();}
    public synchronized void remove(String id){String identity="";for(Profile p:profiles)if(p.id.equals(id))identity=p.identity;profiles.removeIf(p->p.id.equals(id));boolean retained=false;for(Profile p:profiles)retained|=p.identity.equalsIgnoreCase(identity);if(!retained&&!identity.isEmpty()&&identity.equalsIgnoreCase(legacy.getString("identity","")))legacy.edit().remove("identity").remove("role").apply();prefs.edit().putBoolean("legacy_migrated",true).apply();if(active()!=null)select(active().id);save();}
    public synchronized void save(){try{JSONArray a=new JSONArray();for(Profile p:profiles){JSONObject o=new JSONObject();o.put("id",p.id);o.put("name",p.name);o.put("identity",p.identity);o.put("role",p.role);o.put("mac",p.mac);o.put("serial",p.serial);o.put("lastConnected",p.lastConnected);a.put(o);}prefs.edit().putString("items",a.toString()).apply();}catch(JSONException e){throw new IllegalStateException(e);}}
    public static String privateBackup(Profile p){try{JSONObject o=new JSONObject();o.put("format","navee-offline-owner-v1");o.put("name",p.name);o.put("owner_identity",p.identity);o.put("role",p.role);o.put("scooter_address",p.mac);o.put("scooter_serial",p.serial);return o.toString(2);}catch(JSONException e){throw new IllegalStateException(e);}}
}
