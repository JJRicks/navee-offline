package local.navee.offlinelock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Named, bounded OEM controls. No arbitrary register/opcode console is exposed.
 * Evidence: installed NAVEE 2.1.9 BleHandler and its settings activities.
 * Option domains and labels in this catalogue are Volt's UT5 MAX (PID 2538) layer.
 * Other models receive FeatureVariants from ModelRegistry.
 * Sending a control is not confirmation: wait for its response and fresh readback.
 */
public final class ScooterCommands {
    public enum Type { TOGGLE, CHOICE, SLIDER, TIME, PIN }

    public static final class Feature {
        /** description is an optional short caption: empty when the label and control already say enough. */
        public final String key, label, group, description, unit, readbackKey, capability;
        /** Search-only terms (lowercase, space separated) for words no longer in the visible copy. */
        public final String keywords;
        public final Type type;
        public final int cmd, min, max, step, payloadOffset, readbackCmd, readbackMask, readbackShift;
        public final String[] options;
        public final int[] optionValues;
        public final boolean sensitive, requiresParked, experimental, requiresBond, requiresPin;
        private final int[] prefix;

        private Feature(String key, String label, String group, String description, Type type,
                String unit, int cmd, int min, int max, int step, String[] options,
                int[] optionValues, int[] prefix, String readbackKey, int payloadOffset,
                int mask, int shift, String capability, boolean sensitive, boolean parked) {
            this.key = key; this.label = label; this.group = group; this.description = description;
            this.type = type; this.unit = unit; this.cmd = cmd; this.min = min; this.max = max;
            this.step = step; this.options = options.clone(); this.optionValues = optionValues.clone();
            this.prefix = prefix.clone(); this.readbackKey = readbackKey;
            this.payloadOffset = payloadOffset; this.readbackCmd = sensitive ? 0x7a : 0x70;
            this.readbackMask = mask; this.readbackShift = shift; this.capability = capability;
            this.sensitive = sensitive; this.requiresParked = parked;
            this.requiresBond=key.equals("proximity_key");this.requiresPin=this.requiresBond;
            this.experimental = key.equals("acceleration") || key.equals("speed_limit")
                || key.equals("dual_drive") || key.equals("drive_speed_limit")
                || key.equals("long_range_mode") || key.equals("overspeed_control")
                || key.equals("prompt_tone");
            this.keywords = keywords(key);
        }

        /** Label, caption and search-only keywords, for settings search. */
        public String searchText() {
            return label + " " + description + " " + keywords;
        }

        /** Speed caps send 0 to disable themselves, so 0 reads as Off. */
        public boolean zeroIsOff() {
            return key.equals("speed_limit") || key.equals("drive_speed_limit");
        }

        public boolean allows(int value) {
            if (value < min || value > max || (value - min) % step != 0) return false;
            if (key.equals("speed_limit") && value>0 && value<8) return false;
            if (key.equals("drive_speed_limit") && value>0 && value<5) return false;
            if (optionValues.length == 0) return true;
            for (int candidate : optionValues) if (candidate == value) return true;
            return false;
        }

        public String valueLabel(int value) {
            if (value == 0 && zeroIsOff()) return "Off";
            for (int i = 0; i < optionValues.length; i++)
                if (optionValues[i] == value) return options[i];
            return Integer.toString(value) + (unit.isEmpty() ? "" : " " + unit);
        }

        /** Extracts a field only from an accepted, sufficiently long readback. */
        public Integer readback(Protocol.Frame frame) {
            if (sensitive || type==Type.TIME || frame == null || frame.cmd != readbackCmd || frame.error() != 0
                    || payloadOffset < 0 || frame.data.length <= payloadOffset + 1) return null;
            int raw=frame.data[payloadOffset + 1]&255;
            if(key.equals("speed_limit")||key.equals("drive_speed_limit"))return (raw&128)==0?0:raw&127;
            return (raw & readbackMask) >>> readbackShift;
        }

        /** Wire selector bytes before the value (0x6e/0x6f/0x81 selector, 0x6d target and sub). */
        int[] selector() { return prefix.clone(); }

        public Long timeReadback(Protocol.Frame frame) {
            if(type!=Type.TIME || frame==null || frame.cmd!=readbackCmd || frame.error()!=0
                    || frame.data.length<payloadOffset+5) return null;
            int offset=payloadOffset+1;
            return ((long)(frame.data[offset]&255)<<24) | ((long)(frame.data[offset+1]&255)<<16)
                | ((long)(frame.data[offset+2]&255)<<8) | (frame.data[offset+3]&255L);
        }
    }

    private static final LinkedHashMap<String, Feature> FEATURES = new LinkedHashMap<String, Feature>();
    private static final String[] NO_OPTIONS = new String[0];
    private static final int[] NO_VALUES = new int[0];
    private static final int[] READS = {0x70, 0x71, 0x72, 0x73, 0x74, 0x75, 0x76, 0x79, 0x7b};

    static {
        toggle("lock", "Electronic lock", "Security", "", 0x51, 2, "lock_status", "", true);
        toggle("cruise_control", "Cruise control", "Ride", "", 0x52, 3, "cruise_control", "cruise", true);
        choice("energy_recovery", "Energy recovery", "Ride", "", 0x53,
                new int[]{0,30,60,90}, new String[]{"Off","Low","Medium","High"}, 5, "energy_recovery", "ers", true);
        choice("acceleration", "Acceleration profile", "Ride", "Other models’ Eco/Turbo.", 0x58,
                new int[]{3,5}, new String[]{"Eco","Turbo"}, 1, "driving_mode", "acceleration", true);
        // Retain the legacy key for saved control evidence. OEM DeviceLightType.TailLight
        // sends 0x54 and reads tailIsOn at 0x70 byte 4; it is not a manual headlight command.
        toggle("headlight", "Tail light", "Lights & sound", "", 0x54, 4, "tail_light", "", false);
        toggle("auto_sensor", "Automatic lights", "Lights & sound", "Power button pauses until restart.", 0x57, 8, "auto_sensor", "autoSensor", false);
        toggle("traction_control", "Traction control", "Ride", "", 0x5f, 11, "traction_control", "tcs", true);
        toggle("turn_sound", "Turn signal sound", "Lights & sound", "", 0x60, 12, "turn_sound", "turnSound", false);
        add("display_units", "Dashboard units", "Preferences", "", Type.CHOICE, "", 0x55, 0,1,1,
                new String[]{"Miles / mph","Kilometers / km/h"}, new int[]{0,1}, NO_VALUES, "display_units",7,255,0,"",false,false);
        choice("range_algorithm", "Range estimate", "Preferences", "Changes the estimate only.", 0x56,
                new int[]{0,1,2,3,4}, new String[]{"Theoretical","Actual riding","D-mode estimate","Sport estimate","Eco estimate"},6,"range_algorithm","mileageAlgorithm",false);
        add("start_speed", "Kick-start speed", "Ride", "", Type.SLIDER,"km/h",0x6a,0,5,1,
                NO_OPTIONS,NO_VALUES,NO_VALUES,"start_speed",19,255,0,"startSpeed",false,true);
        add("speed_limit", "Custom speed cap", "Ride", "", Type.SLIDER,"km/h",0x6b,0,32,1,
                NO_OPTIONS,NO_VALUES,NO_VALUES,"speed_limit",20,127,0,"limitSpeed",false,true);
        add("maximum_speed", "Maximum speed", "Ride", "Sport mode only.",Type.CHOICE,"km/h",0x6e,25,50,1,
                new String[]{"25 km/h","32 km/h","40 km/h","50 km/h"},new int[]{25,32,40,50},new int[]{1},"maximum_speed_setting",25,255,0,"maxSpeed",false,true);
        add("dual_drive", "Dual motor drive", "Ride", "",Type.TOGGLE,"",0x6e,0,1,1,
                new String[]{"Single motor","Dual motor"},new int[]{0,1},new int[]{2},"drive_mode_setting",26,255,0,"dualDrive",false,true);
        add("drive_speed_limit", "Drive mode speed cap", "Ride", "",Type.SLIDER,"km/h",0x6e,0,20,1,
                NO_OPTIONS,NO_VALUES,new int[]{3},"drive_speed_limit",42,127,0,"multiSpeedLimit",false,true);
        add("charge_limit", "Charge ceiling", "Battery", "",Type.CHOICE,"%",0x6f,80,100,5,
                new String[]{"80%","85%","90%","95%","100%"},new int[]{80,85,90,95,100},new int[]{4},"charge_limit_percent",31,255,0,"chargeLimit",false,true);
        add("timed_charging", "Scheduled charging", "Battery", "",Type.TOGGLE,"",0x6f,0,1,1,
                new String[]{"Off","On"},new int[]{0,1},new int[]{1},"timed_charging",33,255,0,"timedCharge",false,true);
        add("charge_start", "Charging start time", "Battery", "",Type.TIME,"",0x6f,0,Integer.MAX_VALUE,1,
                NO_OPTIONS,NO_VALUES,new int[]{3},"scheduled_charge_time_raw",27,255,0,"timedCharge",false,true);
        add("lock_expiry", "Lock duration", "Security", "Stays locked when off; know PIN.",Type.CHOICE,"",0x6f,1,3,1,
                new String[]{"6 hours","12 hours","24 hours"},new int[]{1,2,3},new int[]{2},"auto_lock_time",34,255,0,"lockTime",false,true);
        add("low_power", "Low Battery Mode", "Battery", "",Type.TOGGLE,"",0x6f,0,1,1,
                new String[]{"Off","On"},new int[]{0,1},new int[]{5},"low_power",32,255,0,"lowPower",false,true);
        add("long_range_mode", "Long range mode", "Battery", "",Type.TOGGLE,"",0x6f,0,1,1,
                new String[]{"Off","On"},new int[]{0,1},new int[]{7},"long_range_mode",38,255,0,"longRange",false,true);
        add("hill_start_assist", "Walk Assist", "Ride", "Follows your push; brake stops.",Type.TOGGLE,"",0x81,0,1,1,
                new String[]{"Off","On"},new int[]{0,1},new int[]{1},"slope_assist",37,1,0,"slopeSup",false,true);
        add("parking_assist", "Power Assist", "Ride", "Sport: Boost + full throttle uphill.",Type.TOGGLE,"",0x81,0,1,1,
                new String[]{"Off","On"},new int[]{0,1},new int[]{2},"slope_assist",37,2,1,"slopeSup",false,true);
        add("hill_descent_control", "Hill descent control", "Ride", "Limits downhill speed.",Type.TOGGLE,"",0x81,0,1,1,
                new String[]{"Off","On"},new int[]{0,1},new int[]{3},"slope_assist",37,4,2,"slopeSup",false,true);
        toggle("overspeed_control","Overspeed active control","Ride","",0x82,39,"overspeed_control","osc",true);
        choice("prompt_tone","Prompt sound","Lights & sound","",0x6c,
                new int[]{0,1,2,3,4},new String[]{"Sound 1","Sound 2","Sound 3","Sound 4","Sound 5"},21,"volume","promptTone",false);
        lightCatalog("line",4,10,16,"Line lights");
        lightCatalog("star",5,17,18,"Star lights");
        add("proximity_key","Proximity unlock","Security","",Type.TOGGLE,"",0x61,0,1,1,
                new String[]{"Off","On"},new int[]{0,1},NO_VALUES,"proximity_key",13,1,0,"proximityKey",false,true);
        // PIN bytes must never enter diagnostics or exports: Telemetry drops 0x7a/0x63 and parsePin keeps only state.
        add("pin","Handlebar PIN","Security","",Type.PIN,"",0x63,0,0,1,
                NO_OPTIONS,NO_VALUES,NO_VALUES,"",0,255,0,"password",true,true);
    }

    private ScooterCommands() {}

    /** Words removed from captions that people still search for. Never shown. */
    private static String keywords(String key) {
        switch (key) {
            case "lock": return "immobilize";
            case "energy_recovery": return "regen regeneration coasting";
            case "display_units": return "miles kilometers dashboard";
            case "start_speed": return "kickstart standing start";
            case "charge_limit": return "ceiling";
            case "timed_charging": case "charge_start": return "schedule";
            case "hill_start_assist": return "walk push";
            case "parking_assist": return "boost uphill";
            case "hill_descent_control": return "hdc downhill";
            case "line_boost_color": case "star_boost_color": return "boost";
            default: return "";
        }
    }

    public static List<Feature> catalog() {
        return Collections.unmodifiableList(new ArrayList<Feature>(FEATURES.values()));
    }

    public static Feature find(String key) {
        Feature feature = FEATURES.get(key);
        require(feature != null, "Unknown scooter feature");
        return feature;
    }

    /** The normal scalar builder deliberately excludes PIN, time, and multi-field voice writes. */
    public static byte[] set(String key, int value) {
        Feature feature = find(key);
        require(!feature.sensitive && feature.type != Type.TIME, "Feature needs its dedicated builder");
        require(feature.allows(value), "Value is outside the OEM feature domain");
        if (key.equals("speed_limit")) {
            require(value == 0 || value >= 8, "Off or 8–32 km/h");
            return WireCommands.enabledCap(feature.cmd,-1,value,value!=0);
        }
        if (key.equals("drive_speed_limit")) {
            require(value == 0 || value >= 5, "Off or 5–20 km/h");
            return WireCommands.enabledCap(feature.cmd,3,value,value!=0);
        }
        int[] selector=feature.prefix;
        if (selector.length==0) return WireCommands.scalar(feature.cmd,value);
        if (selector.length==1) return WireCommands.selector(feature.cmd,selector[0],value);
        require(selector.length==2 && feature.cmd==0x6d,"Feature has no wire encoder");
        return WireCommands.light(selector[0],selector[1],value);
    }

    /** Runtime-gated builder: missing firmware fields cannot be treated as supported controls. */
    public static byte[] set(String key,int value,Protocol.Frame vehicleStatus) {
        Feature feature=find(key);
        require(feature.readback(vehicleStatus)!=null,"Not available now");
        if(key.equals("range_algorithm")) {
            boolean offered=false;
            for(int choice:choiceValues(key,vehicleStatus)) offered|=choice==value;
            require(offered,"Not available now");
        }
        return set(key,value);
    }

    /** Dynamic capability mode gates the UT5 MAX range estimate selector. */
    public static int[] choiceValues(String key,Protocol.Frame vehicleStatus) {
        Feature feature=find(key);
        if(!key.equals("range_algorithm")) return feature.optionValues.clone();
        if(vehicleStatus==null || vehicleStatus.cmd!=0x70 || vehicleStatus.error()!=0 || vehicleStatus.data.length<=16)
            return NO_VALUES.clone();
        switch(vehicleStatus.data[16]&255) {
            case 0:return new int[]{0};
            case 1:return new int[]{0,2,3};
            case 2:return new int[]{4,2,3};
            default:return NO_VALUES.clone();
        }
    }

    public static Long timeReadback(Protocol.Frame vehicleStatus) {
        return find("charge_start").timeReadback(vehicleStatus);
    }

    /** Only named multi-field controls are accepted; an integer is never treated as an opcode. */
    public static byte[] setParams(String key, int... values) {
        require(key!=null,"Feature key must not be null");
        require(values != null, "Parameters must not be null");
        if (key.equals("voice")) {
            require(values.length == 3 && binary(values[0]) && between(values[1],0,100) && binary(values[2]),
                    "Voice needs enabled, volume 0–100, and language 0 (Chinese) / 1 (English)");
            return WireCommands.voice(values[0]!=0,values[1],values[2]);
        }
        require(values.length == 1,"Scalar feature accepts exactly one parameter");
        return set(key,values[0]);
    }

    public static byte[] setChargeStart(long unixSeconds, int timezoneOffsetSeconds) {
        return WireCommands.localTime(3,unixSeconds,timezoneOffsetSeconds);
    }

    /** The OEM synchronizes this clock after owner authentication. */
    public static byte[] setClock(long unixSeconds, int timezoneOffsetSeconds) {
        return WireCommands.localTime(6,unixSeconds,timezoneOffsetSeconds);
    }

    public static int[] diagnosticReads() { return READS.clone(); }

    /** PIN is not part of any periodic polling or diagnostic bundle. */
    public static int[] followUpReads(String key) {
        Feature feature=find(key);
        if (feature.sensitive) return new int[]{0x7a,0x70};
        if (feature.group.equals("Battery")) return new int[]{0x70,0x72};
        return new int[]{0x70};
    }

    public static boolean sensitiveCommand(int cmd) { return cmd==0x30 || cmd==0x31 || cmd==0x63 || cmd==0x7a; }

    public static byte[] readPin(boolean ownerAuthenticated) {
        require(ownerAuthenticated,"PIN access requires the authenticated owner");
        return Protocol.frame(0x7a,null);
    }

    public static byte[] setPin(String fourDigits, boolean ownerAuthenticated) {
        require(ownerAuthenticated,"PIN access requires the authenticated owner");
        require(fourDigits != null && fourDigits.matches("[0-9]{4}"),"PIN must contain four decimal digits");
        byte[] values = new byte[]{1,1,0,0,0,0};
        for(int i=0;i<4;i++) values[i+2]=(byte)(fourDigits.charAt(i)-'0');
        return Protocol.frame(0x63,values);
    }

    public static byte[] enablePin(boolean enabled, boolean ownerAuthenticated, boolean existingPinKnown) {
        require(ownerAuthenticated,"PIN access requires the authenticated owner");
        require(!enabled || existingPinKnown,"A PIN must be configured before enabling it");
        return Protocol.frame(0x63,new byte[]{(byte)(enabled?1:0),0,0,0,0,0});
    }

    public static byte[] setProximity(boolean enabled,boolean ownerAuthenticated,boolean bonded,PinState pinState) {
        require(ownerAuthenticated,"Proximity changes require the authenticated owner");
        require(!enabled || bonded,"Bond this phone before enabling proximity unlock");
        require(!enabled || (pinState!=null && pinState.status==0 && pinState.configured && Boolean.TRUE.equals(pinState.enabled)),
                "Enable a configured handlebar PIN before proximity unlock");
        return set("proximity_key",enabled?1:0);
    }

    /** Deliberately retains only state, never the actual four digits supplied by the scooter. */
    public static final class PinState {
        public final int status;
        public final Boolean enabled;
        public final boolean configured;
        private PinState(int status, Boolean enabled, boolean configured) {
            this.status=status;this.enabled=enabled;this.configured=configured;
        }
        public String toString() { return status==0 ? (Boolean.TRUE.equals(enabled)?"PIN enabled":"PIN disabled") : "PIN read rejected"; }
    }

    public static PinState parsePin(Protocol.Frame frame, boolean ownerAuthenticated) {
        require(ownerAuthenticated,"PIN access requires the authenticated owner");
        require(frame!=null && frame.cmd==0x7a,"Expected an explicit PIN response");
        if(frame.error()!=0) return new PinState(frame.error(),null,false);
        require(frame.data.length==6 && binary(frame.data[1]&255),"Unexpected PIN response shape");
        boolean configured=true;
        for(int i=2;i<6;i++) configured &= (frame.data[i]&255)<10;
        return new PinState(0,frame.data[1]==1,configured);
    }

    /**
     * Verifies an explicit PIN write's readback without retaining or formatting PIN digits.
     * Caller must first require the matching write ACK and a post-write response. Clear the
     * ephemeral expectedPin array when the transaction finishes, fails, or disconnects.
     */
    public static boolean pinReadbackMatches(Protocol.Frame frame,boolean expectedEnabled,char[] expectedPin,boolean ownerAuthenticated) {
        require(ownerAuthenticated,"PIN access requires the authenticated owner");
        PinState state;
        try { state=parsePin(frame,true); } catch(IllegalArgumentException invalid) { return false; }
        if(state.status!=0 || state.enabled==null || state.enabled.booleanValue()!=expectedEnabled) return false;
        if(expectedPin==null) return !expectedEnabled || state.configured;
        if(!expectedEnabled || !state.configured || expectedPin.length!=4) return false;
        int difference=0;
        for(int i=0;i<4;i++) {
            if(expectedPin[i]<'0' || expectedPin[i]>'9') return false;
            difference |= (frame.data[i+2]&255) ^ (expectedPin[i]-'0');
        }
        return difference==0;
    }

    /** Called by Protocol.frame as its defensive write allowlist. */
    static boolean validPayload(int cmd, byte[] d) {
        if(d==null) return false;
        int a=d.length>0?d[0]&255:-1, b=d.length>1?d[1]&255:-1;
        switch(cmd) {
            case 0x51: case 0x52: case 0x54: case 0x55: case 0x57: case 0x5f: case 0x60: case 0x61: case 0x82:
                return d.length==1 && binary(a);
            case 0x53: return d.length==1 && (a==0||a==30||a==60||a==90);
            case 0x56: return d.length==1 && between(a,0,4);
            case 0x58: return d.length==1 && (a==3||a==5);
            case 0x63:
                if(d.length!=6 || !binary(a) || !binary(b)) return false;
                if(b==1 && a!=1) return false;
                for(int i=2;i<6;i++) if((d[i]&255)>9 || (b==0 && d[i]!=0)) return false;
                return true;
            case 0x6a: return d.length==1 && between(a,0,5);
            case 0x6b: return d.length==1 && (a==0 || (a>=128 && between(a&127,8,32)));
            case 0x6c:
                return (d.length==1 && between(a,0,4))
                    || (d.length==2 && (a==0 || (a>=128 && between(a&127,0,100))) && binary(b));
            case 0x6d:
                if(d.length==2) return between(a,1,3) && between(b,0,2);
                if(d.length!=3 || (a!=4 && a!=5)) return false;
                int c=d[2]&255;
                return (b==0 && binary(c)) || (b==1 && between(c,0,2)) || ((b==2||b==3) && between(c,0,1));
            case 0x6e:
                return d.length==2 && ((a==1 && (b==25||b==32||b==40||b==50))
                    || (a==2 && binary(b)) || (a==3 && (b==0 || (b>=128 && between(b&127,5,20)))));
            case 0x6f:
                if(d.length==5) return (a==3||a==6) && (d[1]!=0||d[2]!=0||d[3]!=0||d[4]!=0);
                return d.length==2 && (((a==1||a==5||a==7) && binary(b))
                    || (a==2 && between(b,1,3)) || (a==4 && between(b,80,100) && b%5==0));
            case 0x81: return d.length==2 && between(a,1,3) && binary(b);
            default: return false;
        }
    }

    private static void lightCatalog(String key,int target,int settingOffset,int colorOffset,String label) {
        String readbackKey=target==4?"ambient_light":"drive_light";
        add(key+"_light",label,"Lights & sound","",Type.TOGGLE,"",0x6d,0,1,1,
            new String[]{"Off","On"},new int[]{0,1},new int[]{target,0},readbackKey,settingOffset,128,7,"ambientV3",false,false);
        add(key+"_pattern",label+" pattern","Lights & sound","",Type.CHOICE,"",0x6d,0,2,1,
            new String[]{"Always on","Follow headlights","Breathing"},new int[]{0,1,2},new int[]{target,1},readbackKey,settingOffset,127,0,"ambientV3",false,false);
        add(key+"_color",label+" color","Lights & sound","",Type.CHOICE,"",0x6d,0,1,1,
            new String[]{"Ice blue","Red"},new int[]{0,1},new int[]{target,2},target==4?"eco_light":"sport_light",colorOffset,15,0,"ambientV3",false,false);
        add(key+"_boost_color",label+" Boost color","Lights & sound","",Type.CHOICE,"",0x6d,0,1,1,
            new String[]{"Ice blue","Red"},new int[]{0,1},new int[]{target,3},target==4?"eco_light":"sport_light",colorOffset,240,4,"ambientV3",false,false);
    }

    private static void toggle(String key,String label,String group,String description,int cmd,int offset,String readback,String capability,boolean parked) {
        add(key,label,group,description,Type.TOGGLE,"",cmd,0,1,1,new String[]{"Off","On"},new int[]{0,1},NO_VALUES,readback,offset,255,0,capability,false,parked);
    }
    private static void choice(String key,String label,String group,String description,int cmd,int[] values,String[] options,int offset,String readback,String capability,boolean parked) {
        int min=values[0],max=values[0];for(int value:values){min=Math.min(min,value);max=Math.max(max,value);}
        add(key,label,group,description,Type.CHOICE,"",cmd,min,max,1,options,values,NO_VALUES,readback,offset,255,0,capability,false,parked);
    }
    private static void add(String key,String label,String group,String description,Type type,String unit,int cmd,int min,int max,int step,
            String[] options,int[] values,int[] prefix,String readback,int offset,int mask,int shift,String capability,boolean sensitive,boolean parked) {
        require(options.length==values.length,"Option labels and values differ");
        FEATURES.put(key,new Feature(key,label,group,description,type,unit,cmd,min,max,step,options,values,prefix,readback,offset,mask,shift,capability,sensitive,parked));
    }
    private static boolean binary(int value) { return value==0||value==1; }
    private static boolean between(int value,int min,int max) { return value>=min&&value<=max; }
    private static void require(boolean condition,String message) { if(!condition) throw new IllegalArgumentException(message); }
}
