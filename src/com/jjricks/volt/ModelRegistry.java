package com.jjricks.volt;

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
 * Version 2 maps the UT5 MAX layer, the shared lock, and for identified OEM groups the OEM home
 * screen family (units, range estimate, clock, lights, turn sound, cruise, energy recovery).
 * Version 3 adds the OEM More-screen toggles and choices (traction control, assists, charging,
 * lock time, overspeed control, prompt tone, dual motor, acceleration, PIN and proximity).
 * Version 4 adds the speed family (maximum speed tables, custom speed cap ranges, kick-start
 * speed, group M's on/off energy recovery) with their riding-region rules. Version 5 adds
 * lighting: line/star zones for 2547/2585 (six colours on 2585) and group S's ambient, logo and
 * daytime lights. Ambient lights for other groups stay unmapped. Every
 * other OEM-group key is "not mapped" until its rows are added. OEM-mapped rows are not tested by
 * Volt on those models. BleController and the UI gate every control on these decisions.
 *
 * The guided-test table (testRule: which settings an owner may try once and set back) was
 * developed alongside versions 3-5 as its own version 3; version 6 is the first registry with both
 * the guided-test table and the More-screen, speed and lighting rows. A finished test is
 * report-only evidence; it never enables a control. Version 7 defers regional UI restrictions:
 * each already-mapped model gets the union of its OEM regional choices. Region and visibility
 * flags remain provenance, not capability negotiation; model, firmware and runtime guards remain.
 * Version 11 adds the experimental table (experimentalRule): controls the official app hides for an
 * identified OEM-group model can be tried from Experimental controls; ordinary rules are unchanged.
 */
public final class ModelRegistry {
    /** Bump on ANY rule or table change; recorded in reports and test journals. */
    public static final int VERSION=12;
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
    /** OEM 2.1.9 adds EU + server-location visibility branches for this prefix; Volt defers regional UI policy. */
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
    /**
     * Other models Volt can name and picture, by exact advertised Model ID, with any controls a Volt tester
     * changed, read back, observed and restored on a parked scooter (FEATURES.txt). The N65i (22131; meter
     * 0.0.0.5, controller 0.0.1.1) passed guided tests and a confirmed lock in October 2026. The ST3 Pro is
     * named from the MIT protocol reference Volt credits (23452, German market); nothing on it is verified. The
     * GT5 Max (25462, EU; meter 1.0.0.4) is named from an owner's compatibility report, its model confirmed by the
     * maintainer in October 2026. Its owner reported using cruise control (which the official app hides there by
     * region) in October 2026, and the maintainer chose to mark it verified; nothing else on it is.
     */
    private static final class Known {
        final String name,photo;final Set<String> features;
        Known(String name,String photo,String... features){this.name=name;this.photo=photo;this.features=new HashSet<>(java.util.Arrays.asList(features));}
    }
    private static final Map<String,Known> KNOWN=new LinkedHashMap<>();
    static{
        KNOWN.put("22131",new Known("N65i","n65i","lock","display_units","headlight","auto_sensor"));
        KNOWN.put("23452",new Known("ST3 Pro","st3pro"));
        KNOWN.put("25462",new Known("GT5 Max","gt5max","cruise_control"));
        KNOWN.put("25852",new Known("UT5 Ultra X","ut5ultrax"));
    }
    /**
     * Photos bundled before Volt knows their model's advertised Model ID, by NaveeModels name; nothing shows them yet.
     * When a report brings the ID, one KNOWN line (Model ID, name, photo) puts the name and photo to work, and the
     * model leaves this table.
     */
    static final Map<String,String> WAITING_PHOTOS;
    static{Map<String,String> w=new LinkedHashMap<>();w.put("GT3 Max","gt3max");w.put("XT5 Ultra","xt5ultra");WAITING_PHOTOS=java.util.Collections.unmodifiableMap(w);}
    /** The name and photo of each model known by Model ID, for ModelRegistryTest. */
    static Map<String,String[]> knownModels(){Map<String,String[]> out=new LinkedHashMap<>();for(Map.Entry<String,Known> e:KNOWN.entrySet())out.put(e.getKey(),new String[]{e.getValue().name,e.getValue().photo});return out;}
    /** A known model; the advertised Model ID must agree with the serial and resolve to a standard layer. */
    private static Known known(DeviceDescriptor d){
        if(d==null||d.pidSource()!=DeviceDescriptor.PidSource.ADVERTISED)return null;
        Resolution r=resolve(d);
        if(r.conflict)return null; // an unresolved identity keeps generic naming and evidence
        return r.layer==Layer.OEM_GROUP||r.layer==Layer.SHARED?KNOWN.get(d.rulePid()):null;
    }
    /** Promotes an OEM-mapped control to Volt-verified on the exact model a tester checked it on. */
    private static Rule verifiedOn(Known t,String feature,Rule rule){
        if(t==null||!t.features.contains(feature)||rule.support!=CapabilityResolver.Support.KNOWN_ENCODING
                ||rule.evidence!=CapabilityResolver.Evidence.OEM_MAPPED)return rule;
        return new Rule(rule.support,rule.reason,CapabilityResolver.Evidence.VOLT_VERIFIED,rule.oemNote,rule.preconditions,rule.variant);
    }
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

    // OEM-group variants (C2): exact readback bytes, so any other value reads as unknown.
    private static final String[] OFF_ON={"Off","On"};
    static final FeatureVariant OEM_UNITS=oem("display_units.oem","display_units",0x55,new int[]{0,1},new String[]{"Miles / mph","Kilometers / km/h"},7,false);
    /** Range estimate sets come from offset 15 (FeatureVariant.choices); "Actual riding" only for the prefixes below. */
    static final FeatureVariant OEM_RANGE=new FeatureVariant("range_algorithm.oem","range_algorithm",0x56,new int[0],new int[]{0,2,3,4},
        new String[]{"Theoretical","D-mode estimate","Sport estimate","Eco estimate"},"",0,4,1,6,255,0,FeatureVariant.Decode.EXACT,false,false,false,true,true);
    static final FeatureVariant OEM_RANGE_PERSONAL=new FeatureVariant("range_algorithm.oem_personal","range_algorithm",0x56,new int[0],new int[]{0,1,2,3,4},
        new String[]{"Theoretical","Actual riding","D-mode estimate","Sport estimate","Eco estimate"},"",0,4,1,6,255,0,FeatureVariant.Decode.EXACT,false,false,false,true,true);
    /** OEM 2.1.9 sends this after every authentication; Volt sends it only from the manual button. */
    static final FeatureVariant OEM_CLOCK=new FeatureVariant("clock_sync.oem","clock_sync",0x6f,new int[]{6},new int[0],new String[0],"",0,0,1,
        -1,255,0,FeatureVariant.Decode.BE32,false,false,false,false,false);
    static final FeatureVariant OEM_TAIL=oem("headlight.oem","headlight",0x54,new int[]{0,1},OFF_ON,4,false);
    static final FeatureVariant OEM_AUTO=oem("auto_sensor.oem","auto_sensor",0x57,new int[]{0,1},OFF_ON,8,false);
    static final FeatureVariant OEM_TURN=oem("turn_sound.oem","turn_sound",0x60,new int[]{0,1},OFF_ON,12,false);
    static final FeatureVariant OEM_CRUISE=oem("cruise_control.oem","cruise_control",0x52,new int[]{0,1},OFF_ON,3,true);
    static final FeatureVariant OEM_ENERGY=oem("energy_recovery.levels","energy_recovery",0x53,new int[]{0,30,60,90},new String[]{"Off","Low","Medium","High"},5,true);
    static final FeatureVariant OEM_ENERGY_NO_OFF=OEM_ENERGY.narrowed("energy_recovery.levels_no_off",new int[]{30,60,90},new String[]{"Low","Medium","High"});
    /** OEM 2.1.9 offers Off only for these prefixes (plus groups G, H, C, B, O, K and L). */
    static final String[] ENERGY_OFF={"2538","2585","2611","2753","2417","2634","2573","2612","2643","2754","2509","2545","2623","2782","2438"};
    /** OEM 2.1.9 hides High for these prefixes when the serial region is NL or unknown. */
    static final String[] ENERGY_NO_HIGH_NL={"2714","2704","2345","2403"};
    /** OEM 2.1.9 adds "Actual riding" to range estimate set 0 for these prefixes (and group G). */
    static final String[] RANGE_PERSONAL={"2213","2332"};
    /** OEM 2.1.9 hides automatic lights for these prefixes (and group M). */
    static final String[] NO_AUTO_LIGHT={"2332","2417","2422"};
    /** OEM 2.1.9 hides cruise for 2573/2634 (and group M). */
    static final String[] NO_CRUISE={"2573","2634"};
    /** OEM 2.1.9 hides turn-signal sound for 2573/2634 (and group R) even when it would otherwise show it. */
    static final String[] NO_TURN_SOUND={"2573","2634"};

    /**
     * Guided tests (C4): reversible, observable, non-riding settings with one byte of exact readback.
     * PIN, proximity, lock, lock time, every speed control, charging, clock, country, binding,
     * firmware, weather, composite writes and Lab rows are never tested this way.
     */
    static final String[] TESTABLE={"display_units","headlight","turn_sound","auto_sensor","range_algorithm"};
    /** OEM 2.1.9 sends 0x55 [0|1] for every standard scooter; the only test offered to an unidentified model. Test-only: it maps nothing. */
    static final FeatureVariant SHARED_UNITS=oem("display_units.shared","display_units",0x55,new int[]{0,1},new String[]{"Miles / mph","Kilometers / km/h"},7,false);

    // OEM-group variants (C5 batch 1): More-screen toggles and choices, same wire as the UT5 MAX. Exact readback unless masked.
    static final FeatureVariant OEM_TCS=oem("traction_control.oem","traction_control",0x5f,new int[]{0,1},OFF_ON,11,true);
    static final FeatureVariant OEM_LOW_POWER=oemAt("low_power.oem","low_power",0x6f,new int[]{5},new int[]{0,1},OFF_ON,32,255,0,true);
    static final FeatureVariant OEM_LONG_RANGE=oemAt("long_range_mode.oem","long_range_mode",0x6f,new int[]{7},new int[]{0,1},OFF_ON,38,255,0,true);
    /** Raw 0 is the OEM's "not set" value (it shows 80%): Volt shows it but treats it as unknown. */
    static final FeatureVariant OEM_CHARGE_LIMIT=oemAt("charge_limit.oem","charge_limit",0x6f,new int[]{4},new int[]{80,85,90,95,100},
        new String[]{"80%","85%","90%","95%","100%"},31,255,0,true).withSentinel(0,"Default (80%)");
    static final FeatureVariant OEM_TIMED_CHARGING=oemAt("timed_charging.oem","timed_charging",0x6f,new int[]{1},new int[]{0,1},OFF_ON,33,255,0,true);
    /** 0x6f sel 3, BE32 local seconds at offsets 27-30; whole hours within 48 h (chargeStartAllowed). */
    static final FeatureVariant OEM_CHARGE_START=new FeatureVariant("charge_start.oem_hours","charge_start",0x6f,new int[]{3},new int[0],new String[0],"",0,Integer.MAX_VALUE,1,
        27,255,0,FeatureVariant.Decode.BE32,true,false,false,false,false);
    /** 1/2/3 at offset 34; the 6/12/24-hour meaning is the OEM screen's, confirmed by Volt only on the UT5 MAX. */
    static final FeatureVariant OEM_LOCK_TIME=oemAt("lock_expiry.oem","lock_expiry",0x6f,new int[]{2},new int[]{1,2,3},new String[]{"6 hours","12 hours","24 hours"},34,255,0,true);
    static final FeatureVariant OEM_WALK_ASSIST=oemAt("hill_start_assist.oem","hill_start_assist",0x81,new int[]{1},new int[]{0,1},OFF_ON,37,1,0,true);
    static final FeatureVariant OEM_POWER_ASSIST=oemAt("parking_assist.oem","parking_assist",0x81,new int[]{2},new int[]{0,1},OFF_ON,37,2,1,true);
    static final FeatureVariant OEM_DESCENT=oemAt("hill_descent_control.oem","hill_descent_control",0x81,new int[]{3},new int[]{0,1},OFF_ON,37,4,2,true);
    static final FeatureVariant OEM_OVERSPEED=oem("overspeed_control.oem","overspeed_control",0x82,new int[]{0,1},OFF_ON,39,true);
    static final FeatureVariant OEM_PROMPT_TONE=oem("prompt_tone.oem","prompt_tone",0x6c,new int[]{0,1,2,3,4},new String[]{"Sound 1","Sound 2","Sound 3","Sound 4","Sound 5"},21,true);
    static final FeatureVariant OEM_DUAL_DRIVE=oemAt("dual_drive.oem","dual_drive",0x6e,new int[]{2},new int[]{0,1},new String[]{"Single motor","Dual motor"},26,255,0,true);
    /** 3 Eco, 5 Turbo at offset 1; any other mode code reads as unknown. */
    static final FeatureVariant OEM_ACCELERATION=oem("acceleration.oem","acceleration",0x58,new int[]{3,5},new String[]{"Eco","Turbo"},1,true);
    /** Volt's strict PIN and proximity flows (owner, bond first, PIN always); readback 0x7a and offset 13 bit 0. */
    static final FeatureVariant OEM_PIN=new FeatureVariant("pin.oem","pin",0x63,new int[0],new int[0],new String[0],"",0,0,1,0,255,0,FeatureVariant.Decode.EXACT,true,true,false,false,false);
    static final FeatureVariant OEM_PROXIMITY=new FeatureVariant("proximity_key.oem","proximity_key",0x61,new int[0],new int[]{0,1},OFF_ON,"",0,1,1,13,1,0,FeatureVariant.Decode.MASKED,true,true,false,false,true);

    // OEM-group variants (C5 batch 2): the speed family. Choices are the official app's per-model tables; never probed.
    static final FeatureVariant SPEED_25_32=maxSpeed("25_32",25,32);
    static final FeatureVariant SPEED_25_50=maxSpeed("25_50",25,32,40,50);
    static final FeatureVariant SPEED_25_50_45=maxSpeed("25_50_45",25,32,45,50);
    static final FeatureVariant SPEED_25_60=maxSpeed("25_60",25,32,40,50,60);
    static final FeatureVariant SPEED_25_70=maxSpeed("25_70",25,32,40,50,70);
    static final FeatureVariant SPEED_25_40_STEP5=maxSpeed("25_40_step5",25,30,35,40);
    static final FeatureVariant SPEED_25_50_STEP5=maxSpeed("25_50_step5",25,30,35,40,45,50);
    static final FeatureVariant SPEED_25_65_STEP5=maxSpeed("25_65_step5",25,30,35,40,45,50,55,60,65);
    static final FeatureVariant SPEED_25_45=maxSpeed("25_45",25,32,40,45);
    /** Custom speed cap: Off (0) or km/h with the enable bit, readback offset 20 bit 7 + 7-bit km/h. */
    static final FeatureVariant LIMIT_2782=speedLimit("radio",new int[]{8,15,25,32});
    static final FeatureVariant LIMIT_8_16=speedLimit("8_16",range(8,16));
    static final FeatureVariant LIMIT_8_20=speedLimit("8_20",range(8,20));
    static final FeatureVariant LIMIT_8_32=speedLimit("8_32",range(8,32));
    /** Kick-start speed: 0-5 km/h only in the OEM's USA bucket (not group M); 3-5 elsewhere. */
    static final FeatureVariant START_0_5=startSpeed("0_5",range(0,5));
    static final FeatureVariant START_3_5=startSpeed("3_5",range(3,5));
    /** Group M's energy recovery is on/off (offset 5 == 1), not levels. */
    static final FeatureVariant OEM_ENERGY_BINARY=oem("energy_recovery.binary","energy_recovery",0x53,new int[]{0,1},OFF_ON,5,true);

    // OEM-group variants (C5 batch 3): lighting. Region rules here hide convenience controls, so they are notes only.
    private static final String[] ZONE_PATTERNS={"Always on","Follow headlights","Breathing"};
    private static final String[] TWO_COLOURS={"Ice blue","Red"};
    private static final String[] SIX_COLOURS={"Ice blue","Red","Blue","Green","Purple","Rainbow"};
    /** Line (0x6d target 4: offsets 10 and 16) and star (target 5: offsets 17 and 18) zones for 2547 and 2585. */
    static final FeatureVariant LINE_LIGHT=zone("line_light",4,0,new int[]{0,1},OFF_ON,10,128,7);
    static final FeatureVariant LINE_PATTERN=zone("line_pattern",4,1,new int[]{0,1,2},ZONE_PATTERNS,10,127,0);
    static final FeatureVariant LINE_COLOR_TWO=zone("line_color",4,2,new int[]{0,1},TWO_COLOURS,16,15,0);
    static final FeatureVariant LINE_COLOR_SIX=zone("line_color",4,2,new int[]{0,1,2,3,4,5},SIX_COLOURS,16,15,0);
    static final FeatureVariant LINE_BOOST_TWO=zone("line_boost_color",4,3,new int[]{0,1},TWO_COLOURS,16,240,4);
    static final FeatureVariant LINE_BOOST_SIX=zone("line_boost_color",4,3,new int[]{0,1,2,3,4,5},SIX_COLOURS,16,240,4);
    static final FeatureVariant STAR_LIGHT=zone("star_light",5,0,new int[]{0,1},OFF_ON,17,128,7);
    static final FeatureVariant STAR_PATTERN=zone("star_pattern",5,1,new int[]{0,1,2},ZONE_PATTERNS,17,127,0);
    static final FeatureVariant STAR_COLOR_TWO=zone("star_color",5,2,new int[]{0,1},TWO_COLOURS,18,15,0);
    static final FeatureVariant STAR_COLOR_SIX=zone("star_color",5,2,new int[]{0,1,2,3,4,5},SIX_COLOURS,18,15,0);
    static final FeatureVariant STAR_BOOST_TWO=zone("star_boost_color",5,3,new int[]{0,1},TWO_COLOURS,18,240,4);
    static final FeatureVariant STAR_BOOST_SIX=zone("star_boost_color",5,3,new int[]{0,1,2,3,4,5},SIX_COLOURS,18,240,4);
    /** Group S light controls: 0x6d [type 1 ambient | 2 logo | 3 daytime, 0 off | 1 on | 2 follow headlight], raw at offsets 10/23/24. */
    private static final String[] LIGHT_MODES={"Off","On","Follow headlight"};
    static final FeatureVariant S_AMBIENT=oemAt("ambient_light_mode.oem","ambient_light_mode",0x6d,new int[]{1},new int[]{0,1,2},LIGHT_MODES,10,255,0,false);
    static final FeatureVariant S_LOGO=oemAt("logo_light.oem","logo_light",0x6d,new int[]{2},new int[]{0,1,2},LIGHT_MODES,23,255,0,false);
    static final FeatureVariant S_DAYTIME=oemAt("daytime_light.oem","daytime_light",0x6d,new int[]{3},new int[]{0,1,2},LIGHT_MODES,24,255,0,false);
    /** OEM 2.1.9's line/star lights outside the UT5 MAX: 2547 (two colours) and 2585 (six colours). */
    static final String[] ZONE_LIGHTS={"2547","2585"};

    /** Maximum speed: OEM 2.1.9 shows the row only in its USA bucket, for these prefixes plus groups S, Q, H, K, L, B, C, D, O and G. */
    static final String[] MAX_SPEED_ROW={"2509"};
    /** OEM 2.1.9 choice tables (km/h) by prefix; anything else in the row gets 25/32/40/45. */
    static final String[] MAX_25_32={"2611","2753","2612","2643","2754","2701"};
    static final String[] MAX_25_40_STEP5={"2509","2657","2707","2745"};
    /** These two need the full model ID: ending in 1 adds 45 and 50. */
    static final String[] MAX_FIFTH_DIGIT={"2543","2799"};
    static final String[] MAX_25_65_STEP5={"2449","2800"};
    /** Custom speed cap: OEM 2.1.9 offers it for these prefixes (range by region) plus 2782 and group M. */
    static final String[] SPEED_LIMIT_SEVEN={"2573","2634","2611","2753","2612","2643","2754"};
    /** Kick-start speed: OEM 2.1.9 offers it for these prefixes plus groups S, Q, K, L, B, C, D, O and M. */
    static final String[] START_SPEED={"2611","2753","2612","2643","2754","2573","2634","2314","2509","2543","2449","2623","2782"};
    /** ...and for these (plus group G) only when the serial region is known and not DE. */
    static final String[] START_SPEED_REGIONAL={"2657","2658","2707","2799","2800","2781"};

    /** OEM 2.1.9 never shows traction control for these (nor for the legacy group or group M). */
    static final String[] NO_TCS={"2573","2634","2314","2417","2422"};
    /** Long range: OEM 2.1.9 offers it for 2745 and group H, in RU/US or (with the scooter's flag at offset 35) these regions. */
    static final String[] LONG_RANGE_FLAGGED_AREAS={"EU","AT","IT","SE","ME"};
    /** Charge limit and timed charging: OEM 2.1.9 hides them for these prefixes when the full model ID ends in 2. */
    static final String[] CHARGE_FIFTH_DIGIT={"2543","2799","2657","2707"};
    /** OEM 2.1.9 opens lock time directly for these; they have no Bluetooth lock screen (PIN, proximity). */
    static final String[] LOCK_TIME_DIRECT={"2573","2634","2623"};
    /** OEM 2.1.9 hides the lock-time row on its lock screen for these prefixes (and groups L, S and 2506). */
    static final String[] NO_LOCK_TIME={"2436","2437","2438","2509"};
    /** Overspeed active control: OEM 2.1.9 offers it for these prefixes plus groups H, C, B, K, L, R and G. */
    static final String[] OVERSPEED={"2611","2753","2417","2634","2573","2612","2643","2754","2509","2545","2438"};
    static final String[] PROMPT_TONE={"2547","2585"};
    static final String[] DUAL_DRIVE={"2443","2585"};
    /** Eco/Turbo: OEM 2.1.9 offers it for these prefixes plus groups D and O (not group Q). */
    static final String[] ACCELERATION={"2345","2714","2509"};

    private static FeatureVariant zone(String key,int target,int sub,int[] values,String[] labels,int offset,int mask,int shift){
        return oemAt(key+(values.length==6?".oem_six":".oem"),key,0x6d,new int[]{target,sub},values,labels,offset,mask,shift,false);
    }
    private static FeatureVariant maxSpeed(String id,int... kmh){
        return oemAt("maximum_speed."+id,"maximum_speed",0x6e,new int[]{1},kmh,kmhLabels(kmh,false),25,255,0,true);
    }
    private static FeatureVariant speedLimit(String id,int[] kmh){
        int[] values=new int[kmh.length+1];System.arraycopy(kmh,0,values,1,kmh.length);
        return new FeatureVariant("speed_limit."+id,"speed_limit",0x6b,new int[0],values,kmhLabels(values,true),"km/h",0,kmh[kmh.length-1],1,
            20,127,0,FeatureVariant.Decode.CAP7,true,false,false,false,true);
    }
    private static FeatureVariant startSpeed(String id,int[] kmh){return oemAt("start_speed."+id,"start_speed",0x6a,new int[0],kmh,kmhLabels(kmh,false),19,255,0,true);}
    private static int[] range(int from,int to){int[] out=new int[to-from+1];for(int i=0;i<out.length;i++)out[i]=from+i;return out;}
    private static String[] kmhLabels(int[] kmh,boolean zeroOff){String[] out=new String[kmh.length];for(int i=0;i<kmh.length;i++)out[i]=zeroOff&&kmh[i]==0?"Off":kmh[i]+" km/h";return out;}
    private static FeatureVariant oem(String id,String feature,int cmd,int[] values,String[] labels,int offset,boolean parked){
        return oemAt(id,feature,cmd,new int[0],values,labels,offset,255,0,parked);
    }
    private static FeatureVariant oemAt(String id,String feature,int cmd,int[] selector,int[] values,String[] labels,int offset,int mask,int shift,boolean parked){
        int min=values[0],max=values[0];for(int v:values){min=Math.min(min,v);max=Math.max(max,v);}
        FeatureVariant.Decode decode=mask==255&&shift==0?FeatureVariant.Decode.EXACT:FeatureVariant.Decode.MASKED;
        return new FeatureVariant(id,feature,cmd,selector,values,labels,"",min,max,1,offset,mask,shift,decode,parked,false,false,false,true);
    }

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
        if(!"clock_sync".equals(feature))ScooterCommands.find(feature); // unknown keys are a programming error (OEM-only keys are known)
        if(r==null)r=resolve(d);
        switch(r.layer){
            case UNSUPPORTED_PRODUCT: return refused(CapabilityResolver.Support.INCOMPATIBLE,CapabilityResolver.Reason.PRODUCT_UNSUPPORTED);
            case UNSUPPORTED_TRANSPORT: return refused(CapabilityResolver.Support.INCOMPATIBLE,CapabilityResolver.Reason.TRANSPORT_UNSUPPORTED);
            case UT5_MAX: {
                FeatureVariant v=Ut5.VARIANTS.get(feature);
                if(v==null)return excluded(); // another model's light control; the UT5 MAX has none
                CapabilityResolver.Evidence evidence=v.lab?CapabilityResolver.Evidence.VOLT_LAB
                    :VERIFIED.contains(feature)?CapabilityResolver.Evidence.VOLT_VERIFIED:CapabilityResolver.Evidence.OEM_MAPPED;
                return new Rule(CapabilityResolver.Support.KNOWN_ENCODING,CapabilityResolver.Reason.NONE,evidence,ut5Note(feature,d),preconditions(v),v);
            }
            default: {
                Known t=known(d);
                if(feature.equals("lock"))return new Rule(CapabilityResolver.Support.KNOWN_ENCODING,CapabilityResolver.Reason.NONE,
                    t!=null&&t.features.contains("lock")?CapabilityResolver.Evidence.VOLT_VERIFIED:CapabilityResolver.Evidence.SHARED_CONTRACT,
                    "",preconditions(SHARED_LOCK),SHARED_LOCK);
                if(r.layer==Layer.OEM_GROUP){Rule oem=oemRule(feature,d,s);if(oem!=null)return verifiedOn(t,feature,oem);}
                CapabilityResolver.Reason reason=r.conflict?CapabilityResolver.Reason.IDENTITY_CONFLICT
                    :d==null||d.pidSource()!=DeviceDescriptor.PidSource.ADVERTISED?CapabilityResolver.Reason.MODEL_NOT_IDENTIFIED:CapabilityResolver.Reason.MODEL_NOT_MAPPED;
                return refused(CapabilityResolver.Support.UNKNOWN_MAPPING,reason);
            }
        }
    }
    /**
     * The OEM 2.1.9 home-screen family for an advertised, listed PID, or null when not mapped yet.
     * Regional visibility is provenance only. Choices are the union of the OEM's regional
     * tables for this same model; this does not borrow another model's values or bypass its
     * firmware, full-PID or actual setting-field requirements.
     */
    private static Rule oemRule(String feature,DeviceDescriptor d,StatusFields s){
        boolean m=any(d,M);
        switch(feature){
            case "display_units": return oemMapped(OEM_UNITS,"",false);
            case "range_algorithm": return oemMapped(any(d,RANGE_PERSONAL)||any(d,G)?OEM_RANGE_PERSONAL:OEM_RANGE,"",false);
            case "clock_sync": return oemMapped(OEM_CLOCK,"",false);
            case "headlight": return m?excluded():oemMapped(OEM_TAIL,tailNote(d),true);
            case "auto_sensor": return m||any(d,NO_AUTO_LIGHT)?excluded():oemMapped(OEM_AUTO,"",true);
            case "turn_sound": return !turnSoundGroup(d)||any(d,NO_TURN_SOUND)||any(d,R)?excluded()
                :oemMapped(OEM_TURN,d.oemArea()==null||"DE".equals(d.oemArea())?"OEM 2.1.9 hides this in this region":"",true);
            case "cruise_control": {
                if(m||any(d,NO_CRUISE))return excluded();
                // breakSpeed (offset 35) controls regional visibility, not the actual cruise
                // field (offset 3). The OEM's USA bucket bypasses it for this same model.
                // 2620 also has an EU + server-location visibility path; no server is needed here.
                Integer flag=s==null?null:s.u8(35);
                boolean offered="USA".equals(d.sku())||d.region!=null&&flag!=null&&flag==1;
                return oemMapped(OEM_CRUISE,offered?"":"OEM 2.1.9 limits regional visibility using the scooter flag or server location; Volt defers this policy",true);
            }
            case "energy_recovery": {
                if(m)return oemMapped(OEM_ENERGY_BINARY,"",true);
                boolean off=any(d,ENERGY_OFF)||any(d,G)||any(d,H)||any(d,C)||any(d,B)||any(d,O)||any(d,K)||any(d,L);
                String note=any(d,ENERGY_NO_HIGH_NL)&&(d.region==null||"NL".equals(d.oemArea()))
                    ?"OEM 2.1.9 hides High in NL or without a serial region; Volt defers this policy":"";
                return oemMapped(off?OEM_ENERGY:OEM_ENERGY_NO_OFF,note,true);
            }
            // C5 batch 1: More-screen toggles and choices.
            case "traction_control":
                if(any(d,Q)||any(d,O)||any(d,G))return oemMapped(OEM_TCS,"",false);
                // OEM 2.1.9 also lists it for most other models, but that visibility trace is partial: not mapped.
                return m||noMoreScreen(d)||any(d,NO_TCS)?excluded():null;
            case "low_power": return any(d,O)||any(d,S)||any(d,Q)||any(d,H)||any(d,G)?oemMapped(OEM_LOW_POWER,"",false):excluded();
            case "long_range_mode": {
                if(!any(d,"2745")&&!any(d,H))return excluded();
                String area=d.oemArea();Integer flag=s==null?null:s.u8(35);
                boolean offered="RU".equals(area)||"US".equals(area)||area!=null&&contains(LONG_RANGE_FLAGGED_AREAS,area)&&flag!=null&&flag==1;
                // The actual field is offset 38. US/RU bypass breakSpeed, so it is not a
                // negotiated presence bit and is retained only in the OEM provenance note.
                return oemMapped(OEM_LONG_RANGE,offered?"":"OEM 2.1.9 limits regional visibility, sometimes using the scooter flag; Volt defers this policy",false);
            }
            case "charge_limit": return chargeFamily(d,OEM_CHARGE_LIMIT);
            case "timed_charging": return chargeFamily(d,OEM_TIMED_CHARGING);
            case "charge_start": return chargeFamily(d,OEM_CHARGE_START);
            case "lock_expiry": {
                if(any(d,LOCK_TIME_DIRECT))return oemMapped(OEM_LOCK_TIME,"",false);
                if(m)return null; // group M reaches its lock settings another way: not mapped
                if(!bluetoothLockScreen(d)||any(d,NO_LOCK_TIME)||any(d,L)||any(d,S))return excluded();
                Rule rule=oemMapped(OEM_LOCK_TIME,"",false);
                // OEM 2.1.9 blocks lock time up to meter firmware 2.0.1.1 (K, C) or 2.0.5.3 (B).
                if(any(d,K)||any(d,C))return firmware(rule,meterAtMost(d,"2.0.1.1"));
                if(any(d,B))return firmware(rule,meterAtMost(d,"2.0.5.3"));
                return rule;
            }
            case "hill_start_assist": return assist(d,OEM_WALK_ASSIST);
            case "parking_assist": return assist(d,OEM_POWER_ASSIST);
            case "hill_descent_control": return assist(d,OEM_DESCENT);
            case "overspeed_control":
                return any(d,OVERSPEED)||any(d,H)||any(d,C)||any(d,B)||any(d,K)||any(d,L)||any(d,R)||any(d,G)?oemMapped(OEM_OVERSPEED,"",false):excluded();
            case "prompt_tone": return any(d,PROMPT_TONE)?oemMapped(OEM_PROMPT_TONE,"",false):excluded();
            case "dual_drive": return any(d,DUAL_DRIVE)?oemMapped(OEM_DUAL_DRIVE,"",false):excluded();
            case "acceleration": {
                if(!any(d,ACCELERATION)&&!any(d,D)&&!any(d,O))return excluded();
                Rule rule=oemMapped(OEM_ACCELERATION,"",false);
                // OEM 2.1.9 blocks Eco/Turbo for 2509 below meter firmware 2.0.3.5.
                return any(d,"2509")?firmware(rule,meterBelow(d,"2.0.3.5")):rule;
            }
            case "pin": case "proximity_key": {
                if(m)return null; // group M reaches its lock settings another way: not mapped
                if(!bluetoothLockScreen(d))return excluded();
                Rule rule=oemMapped(feature.equals("pin")?OEM_PIN:OEM_PROXIMITY,"",false);
                // OEM 2.1.9 blocks its Bluetooth lock screen for K and C up to meter firmware 2.0.0.4.
                return any(d,K)||any(d,C)?firmware(rule,meterAtMost(d,"2.0.0.4")):rule;
            }
            // C5 batch 2: the speed family. Per-model union of OEM regional choices.
            case "maximum_speed": {
                if(!any(d,MAX_SPEED_ROW)&&!any(d,S)&&!any(d,Q)&&!any(d,H)&&!any(d,K)&&!any(d,L)&&!any(d,B)&&!any(d,C)&&!any(d,D)&&!any(d,O)&&!any(d,G))return excluded();
                FeatureVariant table=maxSpeedTable(d);
                if(table==null)return gated(oemMapped(SPEED_25_40_STEP5,"",false),CapabilityResolver.Reason.FULL_PID_NEEDED,CapabilityResolver.Precondition.FULL_PID);
                // The official prompt is for the US area only; CN and RU share the USA row without it.
                String note=any(d,"2585")&&"US".equals(d.oemArea())?SPEED_PROMPT_NOTE:
                    "USA".equals(d.sku())?"":"OEM 2.1.9 limits this row by region; Volt offers this model's same OEM speed table";
                Rule rule=oemMapped(table,note,false);
                // OEM 2.1.9 blocks maximum speed for K and C below meter firmware 2.0.1.2.
                if(any(d,K)||any(d,C)){rule=firmware(rule,meterBelow(d,"2.0.1.2"));if(rule.reason!=CapabilityResolver.Reason.NONE)return rule;}
                return rule;
            }
            case "speed_limit": {
                String area=d.oemArea();
                if(any(d,"2782"))return oemMapped(LIMIT_2782,"",false);
                if(any(d,"2506"))return oemMapped(LIMIT_8_20,"CN".equals(area)||"US".equals(area)||"RU".equals(area)?"":
                    "OEM 2.1.9 limits this region to 16 km/h; Volt uses this model's 20 km/h regional maximum",false);
                if(any(d,SPEED_LIMIT_SEVEN))return oemMapped(LIMIT_8_32,"RU".equals(area)?"":
                    "OEM 2.1.9 varies this range by region; Volt uses this model's 32 km/h regional maximum",false);
                return m?oemMapped(LIMIT_8_16,"",false):excluded();
            }
            case "start_speed": {
                boolean always=m||any(d,START_SPEED)||any(d,S)||any(d,Q)||any(d,K)||any(d,L)||any(d,B)||any(d,C)||any(d,D)||any(d,O);
                boolean regional=any(d,G)||any(d,START_SPEED_REGIONAL);
                if(!always&&!regional)return excluded();
                String area=d.oemArea();
                String note=!always&&(area==null||"DE".equals(area))?"OEM 2.1.9 hides this without a serial region or in DE; Volt defers this policy":
                    !m&&!"USA".equals(d.sku())?"OEM 2.1.9 offers 3–5 km/h here; Volt uses this model's 0–5 km/h regional choices":"";
                Rule rule=oemMapped(m?START_3_5:START_0_5,note,false);
                if(any(d,"2314")){
                    // OEM 2.1.9 gates 2314 by the fifth digit: 1 below meter 0.0.0.8, 2 below meter 2.0.0.9.
                    Tri one=endsWith(d,'1');
                    if(one==Tri.UNKNOWN)return gated(rule,CapabilityResolver.Reason.FULL_PID_NEEDED,CapabilityResolver.Precondition.FULL_PID);
                    if(one==Tri.YES)return firmware(rule,meterBelow(d,"0.0.0.8"));
                    if(endsWith(d,'2')==Tri.YES)return firmware(rule,meterBelow(d,"2.0.0.9"));
                    return rule;
                }
                // OEM 2.1.9 blocks it for K and C below meter firmware 2.0.1.2.
                if(any(d,K)||any(d,C))return firmware(rule,meterBelow(d,"2.0.1.2"));
                return rule;
            }
            // C5 batch 3: lighting (convenience: region visibility deferred, noted).
            case "ambient_light_mode": case "logo_light": case "daytime_light": {
                if(!any(d,S))return null;
                FeatureVariant v=feature.equals("logo_light")?S_LOGO:feature.equals("daytime_light")?S_DAYTIME:S_AMBIENT;
                return oemMapped(v,lightNote(d),false);
            }
            default:
                if(feature.startsWith("line_")||feature.startsWith("star_"))return zoneLight(feature,d);
                return null;
        }
    }
    /**
     * The rule for a guided test of this feature: the normal rule on UT5 MAX and OEM groups (so
     * model and firmware refusals still apply), SHARED_UNITS for an unidentified standard
     * scooter, and NOT_TESTABLE for anything outside TESTABLE or marked Lab.
     */
    static Rule testRule(String feature,DeviceDescriptor d,Resolution r,StatusFields s){
        if(r==null)r=resolve(d);
        if(!testable(feature))return refused(CapabilityResolver.Support.INCOMPATIBLE,CapabilityResolver.Reason.NOT_TESTABLE);
        switch(r.layer){
            case UT5_MAX: case OEM_GROUP: {
                Rule rule=rule(feature,d,r,s);
                if(rule.variant!=null&&(rule.variant.lab||!rule.variant.readable))return refused(CapabilityResolver.Support.INCOMPATIBLE,CapabilityResolver.Reason.NOT_TESTABLE);
                return rule;
            }
            case SHARED: {
                if(r.conflict)return refused(CapabilityResolver.Support.UNKNOWN_MAPPING,CapabilityResolver.Reason.IDENTITY_CONFLICT);
                if(!feature.equals("display_units"))return rule(feature,d,r,s);
                return new Rule(CapabilityResolver.Support.KNOWN_ENCODING,CapabilityResolver.Reason.NONE,CapabilityResolver.Evidence.OEM_MAPPED,
                    "OEM 2.1.9 sends this for every standard scooter",preconditions(SHARED_UNITS),SHARED_UNITS);
            }
            default: return rule(feature,d,r,s);
        }
    }
    static boolean testable(String feature){for(String t:TESTABLE)if(t.equals(feature))return true;return false;}

    /**
     * Experimental controls (owner decision, October 2026). The UT5 MAX keeps its catalogue Lab rows. On an
     * identified OEM-group model, a control the official app hides for that model (MODEL_EXCLUDED) may be tried,
     * with the wire and readback the official app uses where it offers it. Anything the official app offers for
     * the model keeps its ordinary rule and place; controls Volt has not mapped stay hidden (Volt can't tell
     * whether the official app offers them); unidentified, conflicting, unmapped and incompatible models get nothing.
     * The custom speed cap joined in October 2026 (owner decision): it only lowers speed below the owner's Top speed,
     * reads back from its own byte, and Set back restores the previous setting (a cap or Off).
     */
    static final String[] EXPERIMENTAL={"cruise_control","traction_control","start_speed","hill_start_assist","parking_assist",
        "hill_descent_control","headlight","auto_sensor","turn_sound","prompt_tone","speed_limit"};
    /**
     * Never tried this way on any OEM-group model, as the guided tests: the lock, lock time, PIN and proximity
     * unlock; maximum speed, which can raise speed, the drive-mode cap, which shares the 0x6e speed and mode command,
     * and overspeed control, which acts on speed while riding; Eco/Turbo, whose readback is the riding-mode byte, and dual motor drive, which shares the
     * 0x6e speed and mode command; the 0x6f block (charging, Low Battery Mode, long range mode, clock); and the
     * light zones, whose status byte 10 means line lights on some models and ambient light on others.
     */
    static final String[] NEVER_EXPERIMENTAL={"lock","lock_expiry","pin","proximity_key","maximum_speed","drive_speed_limit",
        "overspeed_control","acceleration","dual_drive","charge_limit","timed_charging","charge_start","low_power","long_range_mode","clock_sync",
        "energy_recovery","display_units","range_algorithm","ambient_light_mode","logo_light","daytime_light"};
    /** Lab copies of the variants the official app uses where it offers each EXPERIMENTAL control (0–5 km/h kick-start; its widest speed cap, 8–32 km/h). */
    private static final Map<String,FeatureVariant> TRY=new LinkedHashMap<>();
    static{
        for(FeatureVariant v:new FeatureVariant[]{OEM_CRUISE,OEM_TCS,START_0_5,OEM_WALK_ASSIST,OEM_POWER_ASSIST,OEM_DESCENT,OEM_TAIL,OEM_AUTO,OEM_TURN,OEM_PROMPT_TONE,LIMIT_8_32})
            TRY.put(v.feature,v.lab());
        if(!TRY.keySet().equals(new HashSet<>(java.util.Arrays.asList(EXPERIMENTAL))))throw new IllegalStateException("Experimental table mismatch");
        for(String f:NEVER_EXPERIMENTAL)if(TRY.containsKey(f))throw new IllegalStateException("Never experimental: "+f);
    }
    /**
     * The rule for trying a control from Experimental controls (CapabilityResolver.decideExperimental), never the
     * ordinary one: a UT5 MAX catalogue Lab row, or on an OEM-group model an EXPERIMENTAL control the official app
     * hides for it, as a Lab variant. Either needs the scooter parked with a motion report, the owner, unlocked and
     * no scooter warning. Anything else returns the ordinary rule, which the experimental gate refuses (not Lab).
     */
    static Rule experimentalRule(String feature,DeviceDescriptor d,Resolution r,StatusFields s){
        if(r==null)r=resolve(d);
        Rule rule=rule(feature,d,r,s);
        if(r.layer==Layer.UT5_MAX)return rule.variant!=null&&rule.variant.lab?tryable(rule.variant,rule.evidence,rule.oemNote):rule;
        FeatureVariant v=r.layer==Layer.OEM_GROUP&&!r.conflict&&rule.support==CapabilityResolver.Support.INCOMPATIBLE
            &&rule.reason==CapabilityResolver.Reason.MODEL_EXCLUDED?TRY.get(feature):null;
        return v==null?rule:tryable(v,CapabilityResolver.Evidence.VOLT_LAB,"OEM 2.1.9 hides this for this model");
    }
    /** A key Experimental controls may try as an experiment on some layer: EXPERIMENTAL, or a UT5 MAX catalogue Lab row. */
    static boolean experimentalKey(String feature){
        if(contains(EXPERIMENTAL,feature))return true;
        for(ScooterCommands.Feature f:ScooterCommands.catalog())if(f.key.equals(feature))return f.experimental;
        return false;
    }
    /**
     * Help verify (owner decision, October 2026, replacing the checker's guided tests): a control of the guided-test
     * table (TESTABLE, testRule) that the official app offers for this model but Volt hasn't verified on it
     * (OEM_MAPPED evidence, never VOLT_VERIFIED). Its ordinary row is unchanged; a try uses the experimental
     * conditions. Anything else is NOT_TESTABLE.
     */
    static Rule verifyRule(String feature,DeviceDescriptor d,Resolution r,StatusFields s){
        if(r==null)r=resolve(d);
        if(!testable(feature))return refused(CapabilityResolver.Support.INCOMPATIBLE,CapabilityResolver.Reason.NOT_TESTABLE);
        Rule rule=testRule(feature,d,r,s);
        if(rule.support!=CapabilityResolver.Support.KNOWN_ENCODING||rule.reason!=CapabilityResolver.Reason.NONE)return rule;
        if(rule.evidence!=CapabilityResolver.Evidence.OEM_MAPPED||rule.variant==null||rule.variant.lab||!rule.variant.readable)
            return refused(CapabilityResolver.Support.INCOMPATIBLE,CapabilityResolver.Reason.NOT_TESTABLE);
        return tryable(rule.variant,rule.evidence,rule.oemNote);
    }
    /** The Help verify controls for this device, in the guided-test table's order. */
    public static java.util.List<String> verifiable(DeviceDescriptor d){
        java.util.List<String> out=new java.util.ArrayList<>();Resolution r=resolve(d);
        for(String key:TESTABLE){Rule rule=verifyRule(key,d,r,StatusFields.NONE);if(rule.support==CapabilityResolver.Support.KNOWN_ENCODING)out.add(key);}
        return out;
    }
    private static Rule tryable(FeatureVariant v,CapabilityResolver.Evidence evidence,String note){
        EnumSet<CapabilityResolver.Precondition> p=preconditions(v);
        p.add(CapabilityResolver.Precondition.STATIONARY);p.add(CapabilityResolver.Precondition.MOTION_REPORT);p.add(CapabilityResolver.Precondition.OWNER);
        p.add(CapabilityResolver.Precondition.UNLOCKED);p.add(CapabilityResolver.Precondition.NO_WARNING);
        return new Rule(CapabilityResolver.Support.KNOWN_ENCODING,CapabilityResolver.Reason.NONE,evidence,note,p,v);
    }
    /**
     * Owner value, most wanted first: a fixed and admittedly subjective list (owner suggestion, October 2026), used only
     * after the likelihood counts below. Keys the model can't try this way are simply skipped.
     */
    static final String[] TRY_PRIORITY={"start_speed","cruise_control","energy_recovery","traction_control","acceleration","turn_sound","headlight",
        "auto_sensor","display_units","hill_start_assist","parking_assist","hill_descent_control","prompt_tone","dual_drive","overspeed_control",
        "long_range_mode","speed_limit","drive_speed_limit"};
    /** OEM 2.1.9's prefix families, for "related models" (P is part of O; SINGLE literals have no family). */
    private static final String[][] FAMILIES={Q,O,G,H,K,C,B,L,S,D,E,R,M,LEGACY_T};
    private static final Map<String,int[]> ELSEWHERE=new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * Where the official app offers this control instead, from the OEM tables: {related models offering it, related
     * models, other models offering it, other models}. Related models share this model's OEM prefix family; the
     * others are every other standard-scooter model ID the official app lists. A model counts as offering a control
     * when its ordinary rule is a known, non-Lab encoding (firmware and full-ID conditions aside).
     */
    public static int[] offeredElsewhere(String feature,DeviceDescriptor d){
        String own=resolve(d).matchedPrefix;
        return ELSEWHERE.computeIfAbsent(feature+"|"+own,k->{
            String[] family=null;for(String[] f:FAMILIES)if(contains(f,own))family=f;
            int[] out=new int[4];
            for(String pid:LISTED){
                if(pid.equals(own))continue;
                DeviceDescriptor other=DeviceDescriptor.of("",pid,1,null,false,DeviceDescriptor.Transport.STANDARD,0);
                Resolution r=resolve(other);if(r.layer!=Layer.OEM_GROUP&&r.layer!=Layer.UT5_MAX)continue;
                Rule rule=rule(feature,other,r,StatusFields.NONE);
                boolean offered=rule.support==CapabilityResolver.Support.KNOWN_ENCODING&&rule.variant!=null&&!rule.variant.lab;
                boolean related=family!=null&&contains(family,pid);
                if(related){out[1]++;if(offered)out[0]++;}
                out[3]++;if(offered)out[2]++;
            }
            return out;
        }).clone();
    }
    /**
     * The controls Experimental controls lists for this device, most likely to work first: (a) more related models
     * (same OEM family) whose official app offers it, then more of all other listed models (shared firmware is more
     * likely for a control most models have; the OEM excludes by family, so the related count is usually zero);
     * (b) TRY_PRIORITY; then catalogue order. Nothing for a model that gets no experimental rule.
     */
    public static java.util.List<String> experimental(DeviceDescriptor d){
        java.util.List<String> out=new java.util.ArrayList<>();Resolution r=resolve(d);
        for(ScooterCommands.Feature f:ScooterCommands.catalog()){Rule rule=experimentalRule(f.key,d,r,StatusFields.NONE);if(rule.variant!=null&&rule.variant.lab)out.add(f.key);}
        final java.util.List<String> catalogue=new java.util.ArrayList<>(out),priority=java.util.Arrays.asList(TRY_PRIORITY);
        final Map<String,int[]> counts=new java.util.HashMap<>();for(String key:out)counts.put(key,offeredElsewhere(key,d));
        java.util.Collections.sort(out,(a,b)->{
            int[] x=counts.get(a),y=counts.get(b);
            if(x[0]!=y[0])return y[0]-x[0];
            if(x[2]!=y[2])return y[2]-x[2];
            int pa=priority.indexOf(a),pb=priority.indexOf(b);pa=pa<0?priority.size():pa;pb=pb<0?priority.size():pb;
            return pa!=pb?pa-pb:catalogue.indexOf(a)-catalogue.indexOf(b);
        });
        return out;
    }

    /** Line and star zones for 2547/2585; any other model's ambient lighting is not mapped. */
    private static Rule zoneLight(String feature,DeviceDescriptor d){
        if(!any(d,ZONE_LIGHTS))return null;
        boolean six=any(d,"2585");FeatureVariant v;
        switch(feature){
            case "line_light": v=LINE_LIGHT;break; case "line_pattern": v=LINE_PATTERN;break;
            case "line_color": v=six?LINE_COLOR_SIX:LINE_COLOR_TWO;break; case "line_boost_color": v=six?LINE_BOOST_SIX:LINE_BOOST_TWO;break;
            case "star_light": v=STAR_LIGHT;break; case "star_pattern": v=STAR_PATTERN;break;
            case "star_color": v=six?STAR_COLOR_SIX:STAR_COLOR_TWO;break; case "star_boost_color": v=six?STAR_BOOST_SIX:STAR_BOOST_TWO;break;
            default: return null;
        }
        return oemMapped(v,lightNote(d),false);
    }
    /** OEM 2.1.9 hides these light screens without a serial region or in DE/AT; Volt notes it (permissive). */
    private static String lightNote(DeviceDescriptor d){String a=d.oemArea();return a==null||"DE".equals(a)||"AT".equals(a)?"OEM 2.1.9 hides this in this region":"";}
    /** OEM 2.1.9's maximum-speed choices for this model ID, or null when the fifth digit decides and is unknown. */
    static FeatureVariant maxSpeedTable(DeviceDescriptor d){
        if(any(d,C)||any(d,B)||any(d,E)||any(d,MAX_25_32))return SPEED_25_32;
        if(any(d,"2416"))return SPEED_25_50;
        if(any(d,"2443","2529"))return SPEED_25_50_45;
        if(any(d,"2547"))return SPEED_25_60;
        if(any(d,"2585"))return SPEED_25_70;
        if(any(d,MAX_25_40_STEP5))return SPEED_25_40_STEP5;
        if(any(d,MAX_FIFTH_DIGIT)){Tri one=endsWith(d,'1');return one==Tri.UNKNOWN?null:one==Tri.YES?SPEED_25_50_STEP5:SPEED_25_40_STEP5;}
        if(any(d,"2658"))return SPEED_25_50_STEP5;
        if(any(d,MAX_25_65_STEP5))return SPEED_25_65_STEP5;
        return SPEED_25_45;
    }
    /** OEM 2.1.9 offers no More screen for the legacy group (nor an empty model ID). */
    private static boolean noMoreScreen(DeviceDescriptor d){return d.rulePid().isEmpty()||any(d,LEGACY_T);}
    /**
     * OEM 2.1.9's Bluetooth lock screen (PIN, proximity, lock time): every model with a More screen except
     * 2314/2417/2422 and the three that open lock time directly. Group M is handled by the caller.
     */
    static boolean bluetoothLockScreen(DeviceDescriptor d){return !noMoreScreen(d)&&!any(d,M)&&!any(d,NOT_I_EXTRA)&&!any(d,LOCK_TIME_DIRECT);}
    /** Charge limit, timed charging and charge start: groups S, Q and H, minus four prefixes whose full ID ends in 2. */
    private static Rule chargeFamily(DeviceDescriptor d,FeatureVariant v){
        if(!any(d,S)&&!any(d,Q)&&!any(d,H))return excluded();
        Rule rule=oemMapped(v,"",false);
        if(any(d,CHARGE_FIFTH_DIGIT)){
            Tri two=endsWith(d,'2');
            if(two==Tri.UNKNOWN)return gated(rule,CapabilityResolver.Reason.FULL_PID_NEEDED,CapabilityResolver.Precondition.FULL_PID);
            if(two==Tri.YES)return excluded();
        }
        return rule;
    }
    /** Walk, power and descent assist: group Q and group O. */
    private static Rule assist(DeviceDescriptor d,FeatureVariant v){return any(d,Q)||any(d,O)?oemMapped(v,"",false):excluded();}
    /** A firmware gate: YES = blocked (old firmware), UNKNOWN = not read in this session. Both fail closed. */
    private static Rule firmware(Rule rule,Tri blocked){
        if(blocked==Tri.NO)return gated(rule,CapabilityResolver.Reason.NONE,CapabilityResolver.Precondition.FIRMWARE_READ);
        return gated(rule,blocked==Tri.YES?CapabilityResolver.Reason.FIRMWARE_OLD:CapabilityResolver.Reason.FIRMWARE_UNKNOWN,CapabilityResolver.Precondition.FIRMWARE_READ);
    }
    /** The same mapping refused for a model condition (firmware or full model ID), with that condition recorded. */
    private static Rule gated(Rule rule,CapabilityResolver.Reason reason,CapabilityResolver.Precondition needs){
        EnumSet<CapabilityResolver.Precondition> p=EnumSet.copyOf(rule.preconditions);if(needs!=null)p.add(needs);
        return new Rule(rule.support,reason,rule.evidence,rule.oemNote,p,rule.variant);
    }
    private static boolean contains(String[] values,String value){for(String v:values)if(v.equals(value))return true;return false;}
    /** OEM 2.1.9's turn-signal sound group: a known PID outside the legacy, M and extra exclusions. */
    static boolean turnSoundGroup(DeviceDescriptor d){return d!=null&&!d.rulePid().isEmpty()&&!any(d,LEGACY_T)&&!any(d,NOT_I_EXTRA)&&!any(d,M);}
    /** Tail light: OEM 2.1.9 hides it with no region or in DE (turn-sound group, 2417, 2422), otherwise with no region or in the ITA bucket. */
    private static String tailNote(DeviceDescriptor d){
        String area=d.oemArea();boolean strictDe=turnSoundGroup(d)||any(d,"2417","2422");
        return area==null||(strictDe?"DE".equals(area):"ITA".equals(d.sku()))?"OEM 2.1.9 hides this in this region":"";
    }
    private static Rule oemMapped(FeatureVariant v,String note,boolean unlocked){
        EnumSet<CapabilityResolver.Precondition> p=preconditions(v);if(unlocked)p.add(CapabilityResolver.Precondition.UNLOCKED);
        return new Rule(CapabilityResolver.Support.KNOWN_ENCODING,CapabilityResolver.Reason.NONE,CapabilityResolver.Evidence.OEM_MAPPED,note,p,v);
    }
    private static Rule excluded(){
        return new Rule(CapabilityResolver.Support.INCOMPATIBLE,CapabilityResolver.Reason.MODEL_EXCLUDED,CapabilityResolver.Evidence.NONE,
            "OEM 2.1.9 hides this for this model",EnumSet.noneOf(CapabilityResolver.Precondition.class),null);
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

    /**
     * Charge start time domain. The UT5 MAX keeps any minute (today's behaviour); other layers use the
     * OEM picker's domain: a whole local hour after now and at most 48 hours ahead.
     */
    public static boolean chargeStartAllowed(Layer layer,long unixSeconds,int tzOffsetSeconds,long nowUnixSeconds){
        if(layer==Layer.UT5_MAX)return true;
        long local=unixSeconds+tzOffsetSeconds;
        return local>0&&local%3600==0&&unixSeconds>nowUnixSeconds&&unixSeconds-nowUnixSeconds<=48L*3600;
    }

    /**
     * Confirmation text before sending this value, or null. Only for OEM-mapped variants off the UT5 MAX
     * layer, where the official app asks first; the UT5 MAX keeps today's direct controls.
     */
    private static final String ASKS="The official app asks before this change. Volt has not tested it on this model.";
    private static final String UNTESTED="Volt has not tested it on this model.";
    private static final String SPEED_PROMPT_NOTE="OEM 2.1.9 asks before speeds above 32 km/h";
    private static final String SHOWS="The official app shows a caution for this change. Volt has not tested it on this model.";
    public static String caution(CapabilityResolver.Decision d,int value){
        if(d==null||d.layer==Layer.UT5_MAX||d.variant==null||d.evidence!=CapabilityResolver.Evidence.OEM_MAPPED)return null;
        switch(d.feature){
            case "traction_control": return value==0?"Turn off traction control? "+ASKS:null;
            case "low_power": return value==0?"Turn off Low Battery Mode? "+ASKS:null;
            case "dual_drive": return value==0?"Switch to single motor? "+ASKS:null;
            case "energy_recovery": return d.variant==OEM_ENERGY_BINARY&&value==0?"Turn off energy recovery? "+SHOWS:null;
            case "start_speed": return value<3?"Kick-start speed below 3 km/h. "+SHOWS:null;
            case "maximum_speed": return value>32&&d.variant==SPEED_25_70?"Above 32 km/h: check the rules where you ride. "
                    +(SPEED_PROMPT_NOTE.equals(d.oemNote)?ASKS:UNTESTED):null;
            default: return null;
        }
    }

    /**
     * The same changes ordinary rows caution about (caution above), for an Experimental controls try. There
     * the official app doesn't offer the control at all, so the words describe the effect, not its prompt.
     */
    public static String experimentalCaution(String feature,int value){
        switch(feature){
            case "traction_control": return value==0?"Turns traction control off. Ride gently on wet or loose ground until you know how it behaves.":null;
            case "start_speed": return value<3?"Below 3 km/h, the motor can pull from a standstill as soon as you use the throttle.":null;
            case "energy_recovery": return value==0?"Turns motor braking off, so your brakes do more of the stopping.":null;
            case "speed_limit": return value!=0?"Caps your speed while riding, below your Top speed setting. Set back restores your previous setting.":null;
            default: return null;
        }
    }

    // Model assumptions kept out of telemetry: true only for the exact UT5 MAX layer.
    public static boolean ut5Max(DeviceDescriptor d){return d!=null&&resolve(d).layer==Layer.UT5_MAX;}
    public static Double ratedWh(DeviceDescriptor d){return ut5Max(d)?716.04:null;}
    public static boolean showsPhoto(DeviceDescriptor d){return !photo(d).isEmpty();}
    /** Exact advertised Model IDs Volt already has a compatibility report for; extend it each release. */
    static final Set<String> REPORTED=new HashSet<>(java.util.Arrays.asList("22131","25462","25852"));
    /** True when Volt needs no new compatibility report for this model (the UT5 MAX is the reference). */
    public static boolean reported(DeviceDescriptor d){return ut5Max(d)||d!=null&&REPORTED.contains(d.rulePid());}
    /** Bundled photo key ("ut5max", "n65i") for this model, or "" for none. */
    public static String photo(DeviceDescriptor d){if(ut5Max(d))return "ut5max";Known t=known(d);return t!=null?t.photo:ownerPhoto(ownerModel(d));}
    public static int chargeReferenceProduct(DeviceDescriptor d){return ut5Max(d)?2538:-1;}
    /**
     * Models whose drive-mode codes Volt names: the UT5 MAX (2538) and the GT5 Max (25462) from owner reports, and the
     * N65i (22131), assumed by the maintainer to match the GT5 Max until an owner reports otherwise; 0 keeps raw codes.
     */
    public static int driveModeLabelProduct(DeviceDescriptor d){
        if(ut5Max(d))return 2538;if(known(d)==null)return 0;
        return "25462".equals(d.rulePid())||"22131".equals(d.rulePid())?25462:0;
    }
    public static int rangeFollowProduct(DeviceDescriptor d){return ut5Max(d)?2538:0;}
    public static String modelName(DeviceDescriptor d){if(ut5Max(d))return "UT5 MAX";Known t=known(d);return t!=null?t.name:ownerModel(d);}
    /**
     * The owner's answer, when it names a listed NAVEE model and Volt can't name this scooter itself (Volt's own identification always
     * wins); "" otherwise. It only names and pictures the scooter: rules, reports and the model line never use it.
     */
    public static String ownerModel(DeviceDescriptor d){
        if(d==null||d.simulated||d.ownerModel.isEmpty()||ut5Max(d)||known(d)!=null)return "";
        return d.ownerModel.equals(NaveeModels.exact(d.ownerModel))?d.ownerModel:"";
    }
    /** The bundled photo for a listed model's name, whether Volt knows its Model ID yet or not; "" when none. */
    static String ownerPhoto(String model){
        if(model.isEmpty())return "";if(model.equals("UT5 Max"))return "ut5max";
        for(Known k:KNOWN.values())if(k.name.equals(model))return k.photo;
        String waiting=WAITING_PHOTOS.get(model);return waiting==null?"":waiting;
    }
    /** "UT5 MAX", a known name such as "N65i", "Model 24431" (advertised), "Model 2443 (unconfirmed)" (serial only) or "". */
    public static String modelLine(DeviceDescriptor d){
        if(d==null)return "";if(ut5Max(d))return "UT5 MAX";
        Known t=known(d);if(t!=null)return t.name;
        if(!d.rulePid().isEmpty())return "Model "+d.rulePid();
        return d.serialPid.isEmpty()?"":"Model "+d.serialPid+" (unconfirmed)";
    }
}
