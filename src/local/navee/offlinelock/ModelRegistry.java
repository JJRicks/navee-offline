package local.navee.offlinelock;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Volt's versioned, offline model rules. Each scooter resolves to one layer; each feature then
 * gets one rule (support, reason, evidence, variant, preconditions). Prefix tables record which
 * model IDs the OEM 2.1.9 app treats alike; they are UI policy evidence, not proof of what a
 * controller implements. OEM group rules test only an advertised PID (see DeviceDescriptor).
 *
 * Version 1 maps only the UT5 MAX layer and the shared lock: identified OEM groups get the shared
 * lock and explicit "not mapped" decisions until their rows are added. Nothing gates on it yet.
 */
public final class ModelRegistry {
    /** Bump on ANY rule or table change; recorded in reports and test journals. */
    public static final int VERSION=1;
    public enum Layer { UT5_MAX, OEM_GROUP, SHARED, UNSUPPORTED_PRODUCT, UNSUPPORTED_TRANSPORT }
    public enum Tri { YES, NO, UNKNOWN }

    public static final class Resolution {
        public final Layer layer; public final String matchedPrefix; public final boolean conflict; public final String note;
        Resolution(Layer layer,String matchedPrefix,boolean conflict,String note){this.layer=layer;this.matchedPrefix=matchedPrefix;this.conflict=conflict;this.note=note;}
        @Override public String toString(){return layer+(matchedPrefix.isEmpty()?"":" "+matchedPrefix)+(conflict?" (conflict)":"");}
    }

    /** One feature's registry verdict; CapabilityResolver adds runtime state. */
    static final class Rule {
        final CapabilityResolver.Support support; final CapabilityResolver.Reason reason; final CapabilityResolver.Evidence evidence;
        final String oemNote; final EnumSet<CapabilityResolver.Precondition> preconditions; final FeatureVariant variant;
        Rule(CapabilityResolver.Support support,CapabilityResolver.Reason reason,CapabilityResolver.Evidence evidence,String oemNote,
                EnumSet<CapabilityResolver.Precondition> preconditions,FeatureVariant variant){
            this.support=support;this.reason=reason;this.evidence=evidence;this.oemNote=oemNote==null?"":oemNote;this.preconditions=preconditions;this.variant=variant;
        }
    }

    // Prefix groups as the OEM 2.1.9 app tests them (startsWith on its advertised model ID).
    static final String[] Q={"2538","2547","2585"};
    static final String[] O={"2619","2620","2646"};
    /** OEM 2.1.9 adds EU + server-location branches for this prefix; Volt cannot evaluate them offline, so they fail closed. */
    static final String[] P={"2620"};
    static final String[] G={"2701","2745"};
    static final String[] H={"2543","2449","2657","2658","2707","2799","2800"};
    static final String[] K={"2401","2345","2714"};
    static final String[] C={"2402","2403","2418","2704"};
    static final String[] B={"2442","2518","2519"};
    static final String[] L={"2441","2517"};
    static final String[] S={"2416","2443","2529"};
    static final String[] D={"2545","2566","2582","2583","2546","2584"};
    static final String[] E={"2808","2809","2810","2811"};
    static final String[] R={"2623","2781","2782"};
    /** Different OEM home screen (plugins, binary energy recovery, older ambient lights). */
    static final String[] M={"2504","2505","2506"};
    /** OEM 2.1.9 offers no More screen for these (it also treats an empty model ID this way). */
    static final String[] LEGACY_T={"2213","2305","2306","2315","2322","2326","2327","2328","2329","2332","2333","2334","2353"};
    /** With LEGACY_T and M: OEM 2.1.9 always hides turn-signal sound for these. */
    static final String[] NOT_I_EXTRA={"2314","2417","2422"};
    /** Golf carts: a different home screen and extra opcodes. Product unsupported. */
    static final String[] GOLF={"2435","2515","2696","2697","2700","2777","2778","2779","2780"};
    /** Separate transports (own GATT service and command set): LD e-bike, Digitech e-bike. */
    static final String[] OTHER_TRANSPORT={"27361","27391"};
    /** Wearable with its own framing. Product unsupported. */
    static final String[] EXO={"27681"};
    /** Literals the OEM 2.1.9 app tests only in individual rules. */
    static final String[] SINGLE={"2314","2417","2422","2436","2437","2438","2509","2548","2549","2573","2611","2612","2634","2643","2753","2754"};
    /** Every model-ID literal in OEM 2.1.9 app code (87). Anything else is unknown to that app. */
    static final String[] LISTED={"2213","2305","2306","2314","2315","2322","2326","2327","2328","2329","2332","2333","2334","2345","2353",
        "2401","2402","2403","2416","2417","2418","2422","2435","2436","2437","2438","2441","2442","2443","2449","2504","2505","2506","2509","2515","2517",
        "2518","2519","2529","2538","2543","2545","2546","2547","2548","2549","2566","2573","2582","2583","2584","2585","2611","2612","2619","2620","2623",
        "2634","2643","2646","2657","2658","2696","2697","2700","2701","2704","2707","2714","2745","2753","2754","2777","2778","2779","2780","2781","2782",
        "2799","2800","2808","2809","2810","2811","27361","27391","27681"};
    /** UT5 MAX fields changed, read back and restored on the owner's parked scooter (FEATURES.txt). */
    private static final Set<String> VERIFIED=new HashSet<>(java.util.Arrays.asList("lock","cruise_control","energy_recovery","traction_control",
        "start_speed","maximum_speed","range_algorithm","display_units","charge_limit","timed_charging","charge_start","low_power","lock_expiry",
        "hill_start_assist","parking_assist","hill_descent_control","headlight","auto_sensor","turn_sound",
        "line_light","line_pattern","line_color","line_boost_color","star_light","star_pattern","star_color","star_boost_color"));
    /** 0x6f sel 6, local seconds; no readback, so "Sent, unverified". */
    static final FeatureVariant CLOCK=new FeatureVariant("clock_sync.local_time","clock_sync",0x6f,new int[]{6},new int[0],new String[0],"",0,0,1,
        -1,255,0,FeatureVariant.Decode.BE32,true,false,false,false,false);
    /** OEM 2.1.9 sends 0x51 [1|0] from the home slider for every standard scooter; readback 0x70 offset 2. */
    static final FeatureVariant SHARED_LOCK=new FeatureVariant("lock.shared","lock",0x51,new int[0],new int[]{0,1},new String[]{"Off","On"},"",0,1,1,
        2,255,0,FeatureVariant.Decode.EXACT,true,false,false,false,true);

    /** Built on first use so ScooterCommands and this class never wait on each other's static initialiser. */
    private static final class Ut5 {
        static final Map<String,FeatureVariant> VARIANTS;
        static {
            LinkedHashMap<String,FeatureVariant> map=new LinkedHashMap<>();
            for(ScooterCommands.Feature f:ScooterCommands.catalog())map.put(f.key,FeatureVariant.ut5(f));
            map.put(CLOCK.feature,CLOCK);VARIANTS=Collections.unmodifiableMap(map);
        }
    }

    private ModelRegistry() {}

    /**
     * First match wins: a non-standard transport; an advertised LD/Digitech/Exo/golf ID; serial
     * PID 2538 (UT5 MAX, even when an advertisement disagrees); an identity conflict (shared);
     * a 2538 family; an advertised ID the OEM app lists (OEM group); otherwise shared.
     */
    public static Resolution resolve(DeviceDescriptor d){
        if(d==null)return new Resolution(Layer.SHARED,"",false,"");
        if(d.transport==DeviceDescriptor.Transport.EXO)return new Resolution(Layer.UNSUPPORTED_PRODUCT,"",false,"");
        if(d.transport!=DeviceDescriptor.Transport.STANDARD)return new Resolution(Layer.UNSUPPORTED_TRANSPORT,"",false,"");
        String pid=d.rulePid(),match;
        if(!(match=longestPrefix(pid,OTHER_TRANSPORT)).isEmpty())return new Resolution(Layer.UNSUPPORTED_TRANSPORT,match,false,"");
        if(!(match=longestPrefix(pid,EXO)).isEmpty())return new Resolution(Layer.UNSUPPORTED_PRODUCT,match,false,"");
        if(!(match=longestPrefix(pid,GOLF)).isEmpty())return new Resolution(Layer.UNSUPPORTED_PRODUCT,match,false,"");
        // The owner-verified UT5 MAX keeps its layer even if an advertisement disagrees; the mismatch is diagnostic only.
        if("2538".equals(d.serialPid))return new Resolution(Layer.UT5_MAX,"2538",d.conflict(),d.conflict()?"Model ID mismatch":"");
        if(d.conflict())return new Resolution(Layer.SHARED,"",true,"Model ID mismatch");
        if("2538".equals(d.family()))return new Resolution(Layer.UT5_MAX,"2538",false,"");
        if(d.pidSource()==DeviceDescriptor.PidSource.ADVERTISED&&oemListed(pid))return new Resolution(Layer.OEM_GROUP,longestPrefix(pid,LISTED),false,"");
        return new Resolution(Layer.SHARED,"",false,"");
    }

    /** Most-specific startsWith match, "" if none. */
    static String longestPrefix(String pid,String[] prefixes){
        String best="";if(pid==null||pid.isEmpty())return best;
        for(String p:prefixes)if(pid.startsWith(p)&&p.length()>best.length())best=p;
        return best;
    }
    /** Matches one of the 87 model-ID literals in OEM 2.1.9 app code. */
    public static boolean oemListed(String pid){return !longestPrefix(pid,LISTED).isEmpty();}
    /** startsWith on rulePid() only; false when there is no advertised PID. */
    static boolean any(DeviceDescriptor d,String... prefixes){return d!=null&&!longestPrefix(d.rulePid(),prefixes).isEmpty();}
    /** OEM fifth-digit rules; UNKNOWN unless the full advertised PID is known. */
    static Tri endsWith(DeviceDescriptor d,char digit){return d==null||!d.fullPid()?Tri.UNKNOWN:d.rulePid().charAt(4)==digit?Tri.YES:Tri.NO;}
    /** Firmware gates fail closed: UNKNOWN unless the meter version was read in this session. */
    static Tri meterBelow(DeviceDescriptor d,String version){
        if(d==null||!d.firmwareThisSession||d.firmware.meter==null)return Tri.UNKNOWN;
        return DeviceDescriptor.compareOem(d.firmware.meter,version)<0?Tri.YES:Tri.NO;
    }
    static Tri meterAtMost(DeviceDescriptor d,String version){
        if(d==null||!d.firmwareThisSession||d.firmware.meter==null)return Tri.UNKNOWN;
        return DeviceDescriptor.compareOem(d.firmware.meter,version)<=0?Tri.YES:Tri.NO;
    }

    /** Variant for this feature on this device, or null; applicability is decided by CapabilityResolver. */
    static FeatureVariant variant(String feature,DeviceDescriptor d,Resolution r,StatusFields s){return rule(feature,d,r,s).variant;}

    static Rule rule(String feature,DeviceDescriptor d,Resolution r,StatusFields s){
        if(!"clock_sync".equals(feature))ScooterCommands.find(feature); // unknown keys are a programming error
        if(r==null)r=resolve(d);
        switch(r.layer){
            case UNSUPPORTED_PRODUCT: return refused(CapabilityResolver.Support.INCOMPATIBLE,CapabilityResolver.Reason.PRODUCT_UNSUPPORTED);
            case UNSUPPORTED_TRANSPORT: return refused(CapabilityResolver.Support.INCOMPATIBLE,CapabilityResolver.Reason.TRANSPORT_UNSUPPORTED);
            case UT5_MAX: {
                FeatureVariant v=Ut5.VARIANTS.get(feature);
                CapabilityResolver.Evidence evidence=v.lab?CapabilityResolver.Evidence.VOLT_LAB
                    :VERIFIED.contains(feature)?CapabilityResolver.Evidence.VOLT_VERIFIED:CapabilityResolver.Evidence.OEM_MAPPED;
                return new Rule(CapabilityResolver.Support.KNOWN_ENCODING,CapabilityResolver.Reason.NONE,evidence,ut5Note(feature,d),preconditions(v),v);
            }
            default: {
                if(feature.equals("lock"))return new Rule(CapabilityResolver.Support.KNOWN_ENCODING,CapabilityResolver.Reason.NONE,
                    CapabilityResolver.Evidence.SHARED_CONTRACT,"",preconditions(SHARED_LOCK),SHARED_LOCK);
                CapabilityResolver.Reason reason=r.conflict?CapabilityResolver.Reason.IDENTITY_CONFLICT
                    :d==null||d.pidSource()!=DeviceDescriptor.PidSource.ADVERTISED?CapabilityResolver.Reason.MODEL_NOT_IDENTIFIED:CapabilityResolver.Reason.MODEL_NOT_MAPPED;
                return refused(CapabilityResolver.Support.UNKNOWN_MAPPING,reason);
            }
        }
    }
    private static Rule refused(CapabilityResolver.Support support,CapabilityResolver.Reason reason){
        return new Rule(support,reason,CapabilityResolver.Evidence.NONE,"",EnumSet.noneOf(CapabilityResolver.Precondition.class),null);
    }
    private static EnumSet<CapabilityResolver.Precondition> preconditions(FeatureVariant v){
        EnumSet<CapabilityResolver.Precondition> p=EnumSet.of(CapabilityResolver.Precondition.FRESH_STATUS);
        if(v.parked){p.add(CapabilityResolver.Precondition.STATIONARY);p.add(CapabilityResolver.Precondition.MOTION_REPORT);}
        if(v.feature.equals("lock"))p.add(CapabilityResolver.Precondition.NO_WARNING);
        if(v.owner)p.add(CapabilityResolver.Precondition.OWNER);
        if(v.feature.equals("proximity_key")){p.add(CapabilityResolver.Precondition.BONDED);p.add(CapabilityResolver.Precondition.PIN_ENABLED);}
        return p;
    }
    /** Every UT5 MAX keeps today's choices in every region; the OEM's regional rule is recorded only as a note. */
    private static String ut5Note(String feature,DeviceDescriptor d){
        // A serial without a valid region is the OEM's strictest case (SKU EUR, light rules as DE); no serial notes nothing.
        if(d==null||d.region==null&&d.serialPid.isEmpty())return "";
        boolean usa="USA".equals(d.sku());String area=d.oemArea();
        switch(feature){
            case "maximum_speed": return usa?"":"OEM 2.1.9 hides this outside US-region models";
            case "start_speed": return usa?"":"OEM 2.1.9 offers only 3–5 km/h in this region";
            case "cruise_control": return usa?"":area==null?"OEM 2.1.9 hides this without a serial region":"OEM 2.1.9 shows this here only when the scooter flags it";
            case "headlight": case "turn_sound": return area==null||"DE".equals(area)?"OEM 2.1.9 hides this in this region":"";
            default:
                if((feature.startsWith("line_")||feature.startsWith("star_"))&&("DE".equals(area)||"AT".equals(area)))return "OEM 2.1.9 hides this in this region";
                return "";
        }
    }

    // Model assumptions kept out of telemetry: true only for the exact UT5 MAX layer.
    public static boolean ut5Max(DeviceDescriptor d){return d!=null&&resolve(d).layer==Layer.UT5_MAX;}
    public static Double ratedWh(DeviceDescriptor d){return ut5Max(d)?716.04:null;}
    public static boolean showsPhoto(DeviceDescriptor d){return ut5Max(d);}
    public static int chargeReferenceProduct(DeviceDescriptor d){return ut5Max(d)?2538:-1;}
    public static int driveModeLabelProduct(DeviceDescriptor d){return ut5Max(d)?2538:0;}
    public static int rangeFollowProduct(DeviceDescriptor d){return ut5Max(d)?2538:0;}
    public static String modelName(DeviceDescriptor d){return ut5Max(d)?"UT5 MAX":"";}
    /** "UT5 MAX", "Model 24431" (advertised), "Model 2443 (unconfirmed)" (serial only) or "". */
    public static String modelLine(DeviceDescriptor d){
        if(d==null)return "";if(ut5Max(d))return "UT5 MAX";
        if(!d.rulePid().isEmpty())return "Model "+d.rulePid();
        return d.serialPid.isEmpty()?"":"Model "+d.serialPid+" (unconfirmed)";
    }
}
