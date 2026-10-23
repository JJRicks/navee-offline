package com.jjricks.volt;

import java.io.IOException;
import java.util.*;

/** Pure reading of `profiles.items` for backup planning. Known fields are type-checked; fields
 * added by newer versions are tolerated and kept, because items are spliced as exact source text. */
final class BackupProfiles {
    static final int MAX_PROFILES = 2000, MAX_DEPTH = 8;
    static final class Profile {
        final String id, name, identity, mac, serial, raw;
        final int role;
        Profile(String id, String name, String identity, int role, String mac, String serial, String raw) {
            this.id=id;this.name=name;this.identity=identity;this.role=role;this.mac=mac;this.serial=serial;this.raw=raw;
        }
        /** Same rule as ScooterProfiles.Profile.configured(). */
        boolean configured(){return (role==0||role==1)&&identity.matches("(?i)[0-9a-f]{12}");}
        /** A non-empty serial identifies the scooter; otherwise owner identity plus address. */
        boolean sameScooter(Profile other) {
            if(!serial.isEmpty()||!other.serial.isEmpty())return !serial.isEmpty()&&serial.equalsIgnoreCase(other.serial);
            return !identity.isEmpty()&&!mac.isEmpty()&&identity.equalsIgnoreCase(other.identity)&&mac.equalsIgnoreCase(other.mac);
        }
    }
    private BackupProfiles() { }

    static List<Profile> parse(Object items) throws IOException {return parse(items,false);}
    /** With modelEvidence, a backup's profiles must also pass the model evidence bounds
     * ({@link #validModelEvidence}); staged backups are read this way, local profiles are not. */
    static List<Profile> parse(Object items, boolean modelEvidence) throws IOException {
        if(items==null)return new ArrayList<>();
        if(!(items instanceof String))throw new IOException("The backup has invalid scooter profiles.");
        String text=(String)items;List<String> raw;List<Profile> result=new ArrayList<>();
        try{raw=BackupJson.elements(text,MAX_DEPTH);}catch(IOException bad){throw new IOException("The backup has unreadable scooter profiles.",bad);}
        if(raw.size()>MAX_PROFILES)throw new IOException("The backup has too many scooter profiles.");
        Set<String> ids=new HashSet<>();
        for(String element:raw) {
            Map<String,Object> p;
            try{p=BackupJson.object(BackupJson.parse(element,MAX_DEPTH));}catch(IOException bad){throw new IOException("The backup has unreadable scooter profiles.",bad);}
            Object id=p.get("id");
            if(!(id instanceof String)||((String)id).isEmpty()||((String)id).length()>128||!ids.add((String)id))throw new IOException("The backup has invalid scooter profile identifiers.");
            for(String field:new String[]{"name","identity","mac","serial"})if(p.containsKey(field)&&!(p.get(field) instanceof String))throw new IOException("The backup has invalid scooter profile fields.");
            Object role=p.get("role");
            if(p.containsKey("role")&&(!(role instanceof Number)||(((Number)role).doubleValue()!=0&&((Number)role).doubleValue()!=1)))throw new IOException("The backup has an invalid scooter owner role.");
            if(p.containsKey("lastConnected")&&!(p.get("lastConnected") instanceof Number))throw new IOException("The backup has an invalid scooter connection date.");
            if(modelEvidence&&!validModelEvidence(p))throw new IOException("The backup has invalid scooter profile fields.");
            String identity=text(p,"identity");if(!identity.isEmpty()&&!identity.matches("(?i)[0-9a-f]{12}"))throw new IOException("The backup has an invalid scooter setup identity.");
            result.add(new Profile((String)id,p.containsKey("name")?text(p,"name"):"UT5 MAX",identity,role==null?0:((Number)role).intValue(),text(p,"mac"),text(p,"serial"),element));
        }
        return result;
    }
    /** Model evidence fields (advertised PID, last-seen firmware, default name), with the same
     * types and bounds as AppDataBackup.validDescriptorFields so pure restores check them too. */
    static boolean validModelEvidence(Map<String,Object> p) {
        Object pid=p.get("pid_advertised");if(p.containsKey("pid_advertised")&&!(pid instanceof String&&((String)pid).matches("[0-9]{0,5}")))return false;
        for(String field:new String[]{"fw_meter","fw_controller","fw_bms","fw_screen","fw_uwb"}){Object v=p.get(field);if(p.containsKey(field)&&!(v instanceof String&&((String)v).matches("[\\x20-\\x7e]{0,16}")))return false;}
        for(String field:new String[]{"pid_advertised_wall","fw_wall"})if(p.containsKey(field)&&!(p.get(field) instanceof Number))return false;
        return !p.containsKey("name_default")||p.get("name_default") instanceof Boolean;
    }
    /** Local items followed by the additions, each element's text unchanged. */
    static String join(List<Profile> local, List<Profile> added) {
        StringBuilder out=new StringBuilder("[");
        for(Profile p:local)out.append(out.length()>1?",":"").append(p.raw);
        for(Profile p:added)out.append(out.length()>1?",":"").append(p.raw);
        return out.append(']').toString();
    }
    static Profile find(List<Profile> profiles, String id){for(Profile p:profiles)if(p.id.equals(id))return p;return null;}
    private static String text(Map<String,Object> p,String key){Object v=p.get(key);return v instanceof String?(String)v:"";}
}
