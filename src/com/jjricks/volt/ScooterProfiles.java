package com.jjricks.volt;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;
import org.json.*;

/** Private local owner profiles. Credentials are never included in ordinary diagnostics exports. */
public final class ScooterProfiles {
    /** The name Volt itself once gave every migrated or Shizuku-captured profile, whatever its model. */
    static final String LEGACY_NAME="My UT5 MAX";
    public static final class Profile {
        public String id,name,identity,mac,serial; public int role; public long lastConnected;
        /** Advertised model ID, written only after an authenticated serial match on that MAC. "" = not captured. */
        public String advertisedPid=""; public long advertisedPidWall;
        /** True while the name is Volt's generic default; a user rename clears it. */
        public boolean nameDefault;
        /** Last-seen 0x73 firmware: display and reports only, never authorisation for a gated control. */
        public String fwMeter="",fwController="",fwBms="",fwScreen="",fwUwb=""; public long fwWall;
        public Profile(String id,String name,String identity,int role,String mac,String serial,long lastConnected){this.id=id;this.name=name;this.identity=identity;this.role=role;this.mac=mac;this.serial=serial;this.lastConnected=lastConnected;}
        public int pid(){try{return Integer.parseInt(serial.substring(1,5));}catch(Exception e){return -1;}}
        /** This connection's descriptor: session firmware (fresh = read in this session) plus the saved identity evidence. */
        public DeviceDescriptor descriptor(DeviceDescriptor.Firmware session,boolean fresh,DeviceDescriptor.Transport t){return DeviceDescriptor.of(serial,advertisedPid,advertisedPidWall,session,fresh,t,role);}
        /** Saved evidence only (no session firmware, standard transport), for model assumptions in dashboards and stats. */
        public DeviceDescriptor savedDescriptor(){return descriptor(DeviceDescriptor.Firmware.NONE,false,DeviceDescriptor.Transport.STANDARD);}
        public boolean configured(){try{return (role==0||role==1)&&Protocol.fromHex(identity).length==6;}catch(Exception e){return false;}}
    }
    private final SharedPreferences prefs,legacy;
    private final ArrayList<Profile> profiles=new ArrayList<>();
    public ScooterProfiles(Context context){
        prefs=context.getSharedPreferences("profiles",Context.MODE_PRIVATE);legacy=context.getSharedPreferences("scooter",Context.MODE_PRIVATE);
        try{JSONArray array=new JSONArray(prefs.getString("items","[]"));for(int i=0;i<array.length();i++){JSONObject o=array.getJSONObject(i);profiles.add(new Profile(o.getString("id"),o.optString("name","UT5 MAX"),o.optString("identity",""),o.optInt("role",0),o.optString("mac",""),o.optString("serial",""),o.optLong("lastConnected",0)));Profile p=profiles.get(profiles.size()-1);p.advertisedPid=o.optString("pid_advertised","");p.advertisedPidWall=o.optLong("pid_advertised_wall",0);p.nameDefault=o.optBoolean("name_default",false);p.fwMeter=o.optString("fw_meter","");p.fwController=o.optString("fw_controller","");p.fwBms=o.optString("fw_bms","");p.fwScreen=o.optString("fw_screen","");p.fwUwb=o.optString("fw_uwb","");p.fwWall=o.optLong("fw_wall",0);}}catch(JSONException ignored){}
        if(profiles.isEmpty()&&!prefs.getBoolean("legacy_migrated",false)){
            SharedPreferences old=context.getSharedPreferences("scooter",Context.MODE_PRIVATE);
            String identity=old.getString("identity","");
            if(identity.matches("(?i)[0-9a-f]{12}")){Profile p=new Profile(UUID.randomUUID().toString(),LEGACY_NAME,identity,old.getInt("role",0),old.getString("last_connected_mac",old.getString("mac","")),old.getString("serial",""),0);profiles.add(p);prefs.edit().putString("active",p.id).apply();save();}
        }
        if(!profiles.isEmpty())prefs.edit().putBoolean("legacy_migrated",true).apply();
    }
    public synchronized List<Profile> all(){return new ArrayList<>(profiles);}
    /** Hardware belongs to one profile; the same owner identity may belong to several scooters. */
    public synchronized Profile hardwareMatch(String excludedProfileId,String mac,String serial){
        for(Profile p:profiles){
            if(p.id.equals(excludedProfileId))continue;
            if(mac!=null&&!mac.isEmpty()&&mac.equalsIgnoreCase(p.mac)
                    ||serial!=null&&!serial.isEmpty()&&serial.equalsIgnoreCase(p.serial))return p;
        }
        return null;
    }
    public synchronized Profile active(){String id=prefs.getString("active","");for(Profile p:profiles)if(p.id.equals(id))return p;return profiles.isEmpty()?null:profiles.get(0);}
    public synchronized void select(String id){prefs.edit().putString("active",id).apply();}
    public synchronized Profile create(String name,String identity,int role){return createWithId(UUID.randomUUID().toString(),name,identity,role);}
    /** A private staged request owns one ID even if the process stops before its receipt is saved. */
    static String ownerImportProfileId(String importId){
        if(importId==null||!importId.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new IllegalArgumentException("Import failed. Pick file again.");
        return UUID.nameUUIDFromBytes(("volt-owner-import:"+importId.toLowerCase(Locale.ROOT)).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }
    synchronized Profile createForOwnerImport(String importId,String name,String identity,int role){
        String id=ownerImportProfileId(importId);
        for(Profile profile:profiles)if(profile.id.equals(id))throw new IllegalStateException("Owner import profile already exists");
        return createWithId(id,name,identity,role);
    }
    private Profile createWithId(String id,String name,String identity,int role){if(!identity.matches("(?i)[0-9a-f]{12}")||role<0||role>1)throw new IllegalArgumentException("Enter a 12-character owner identity.");boolean generic=name==null||name.trim().isEmpty();Profile p=new Profile(id,generic?"Scooter":name.trim(),identity.toUpperCase(Locale.ROOT),role,"","",0);p.nameDefault=generic;profiles.add(p);select(p.id);save();return p;}
    public synchronized void update(Profile p){save();}
    public synchronized void remove(String id){String identity="";for(Profile p:profiles)if(p.id.equals(id))identity=p.identity;profiles.removeIf(p->p.id.equals(id));boolean retained=false;for(Profile p:profiles)retained|=p.identity.equalsIgnoreCase(identity);if(!retained&&!identity.isEmpty()&&identity.equalsIgnoreCase(legacy.getString("identity","")))legacy.edit().remove("identity").remove("role").apply();prefs.edit().putBoolean("legacy_migrated",true).apply();if(active()!=null)select(active().id);save();}
    public synchronized void save(){try{JSONArray a=new JSONArray();for(Profile p:profiles){JSONObject o=new JSONObject();o.put("id",p.id);o.put("name",p.name);o.put("identity",p.identity);o.put("role",p.role);o.put("mac",p.mac);o.put("serial",p.serial);o.put("lastConnected",p.lastConnected);o.put("pid_advertised",p.advertisedPid);o.put("pid_advertised_wall",p.advertisedPidWall);o.put("name_default",p.nameDefault);o.put("fw_meter",p.fwMeter);o.put("fw_controller",p.fwController);o.put("fw_bms",p.fwBms);o.put("fw_screen",p.fwScreen);o.put("fw_uwb",p.fwUwb);o.put("fw_wall",p.fwWall);a.put(o);}prefs.edit().putString("items",a.toString()).apply();}catch(JSONException e){throw new IllegalStateException(e);}}
    public static String privateBackup(Profile p){try{JSONObject o=new JSONObject();o.put("format","navee-offline-owner-v1");o.put("name",p.name);o.put("owner_identity",p.identity);o.put("role",p.role);o.put("scooter_address",p.mac);o.put("scooter_serial",p.serial);return o.toString(2);}catch(JSONException e){throw new IllegalStateException(e);}}
}
