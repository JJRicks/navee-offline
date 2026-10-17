package local.navee.offlinelock;

import java.util.Arrays;

/**
 * C5 control families for identified OEM groups: member and non-member tables, firmware and region
 * edges, synthetic 0x70 fixtures (including ones truncated before each field) and frozen frame bytes.
 * The US UT5 MAX golden decisions live in CapabilityResolverTest and must not change.
 */
public final class OemControlsTest {
    private static int assertions;
    private static final long SEEN=100000,NOW=SEEN+1000;
    private static final String A="NONE";
    public static void main(String[] args) throws Exception {
        batch1Tables(); batch1Firmware(); batch1Region(); batch1Fields(); batch1Frames(); batch1Rules();
        System.out.println("OemControls tests passed: "+assertions+" assertions");
    }

    /** A valid serial whose model digits and region match the advertised ID (no identity conflict). */
    static DeviceDescriptor model(String pid,String area){return ModelRegistryTest.both("Q"+pid.substring(0,4)+"ZZ9"+area+"7777777",pid);}
    static DeviceDescriptor model(String pid){return model(pid,"US");}
    /** The same with meter firmware read in this session (null = 0x73 not read). */
    static DeviceDescriptor firmware(String pid,String meter){
        DeviceDescriptor.Firmware fw=meter==null?DeviceDescriptor.Firmware.NONE:new DeviceDescriptor.Firmware(meter,null,null,null,null,SEEN);
        return DeviceDescriptor.of("Q"+pid.substring(0,4)+"ZZ9US7777777",pid,1,fw,meter!=null,DeviceDescriptor.Transport.STANDARD,0);
    }
    /** Every batch field inside its OEM domain: unlocked, Eco, TCS on, tone 2, single motor, 90 %, timed on, 12 h, walk+descent. */
    static byte[] data(){byte[] d=CapabilityResolverTest.full();d[2]=0;d[35]=0;return d;}
    static StatusFields status(byte[] d){return CapabilityResolverTest.status(d,SEEN);}
    static CapabilityResolver.Decision decide(String key,DeviceDescriptor d,byte[] data){return CapabilityResolver.decide(key,d,status(data),CapabilityResolverTest.parked());}
    static String reason(String key,DeviceDescriptor d,byte[] data){
        CapabilityResolver.Decision decision=decide(key,d,data);
        check(decision.available()==(decision.reason==CapabilityResolver.Reason.NONE&&decision.support==CapabilityResolver.Support.KNOWN_ENCODING),"available() "+decision);
        if(decision.support==CapabilityResolver.Support.KNOWN_ENCODING){
            check(decision.layer==ModelRegistry.Layer.OEM_GROUP&&decision.evidence==CapabilityResolver.Evidence.OEM_MAPPED&&!decision.variant.lab
                &&decision.variant.decode!=FeatureVariant.Decode.UT5_FEATURE,"OEM-mapped, not Volt-verified, not Lab: "+decision);
        }
        return decision.available()?A:decision.reason.name();
    }
    /** rows: {feature, pid, expected reason} with a US serial region. */
    static void table(String[][] rows,byte[] data){for(String[] r:rows)check(reason(r[0],model(r[1]),data).equals(r[2]),r[0]+" on "+r[1]+" expected "+r[2]+" got "+reason(r[0],model(r[1]),data));}

    private static void batch1Tables() {
        String NM="MODEL_NOT_MAPPED",EX="MODEL_EXCLUDED",FP="FULL_PID_NEEDED";
        String[][] rows={
            // Traction control: Q, O, G mapped; the OEM's wider list (partial visibility trace) stays unmapped; explicit exclusions.
            {"traction_control","25471",A},{"traction_control","25851",A},{"traction_control","26191",A},{"traction_control","26201",A},{"traction_control","26461",A},
            {"traction_control","27011",A},{"traction_control","27451",A},{"traction_control","24431",NM},{"traction_control","24011",NM},{"traction_control","25451",NM},
            {"traction_control","28081",NM},{"traction_control","25731",EX},{"traction_control","26341",EX},{"traction_control","23141",EX},{"traction_control","24171",EX},
            {"traction_control","24221",EX},{"traction_control","22131",EX},{"traction_control","25041",EX},
            // Low Battery Mode: O, S, Q, H, G.
            {"low_power","26191",A},{"low_power","24169",A},{"low_power","24431",A},{"low_power","25291",A},{"low_power","25471",A},{"low_power","25431",A},{"low_power","27011",A},
            {"low_power","24011",EX},{"low_power","24021",EX},{"low_power","25451",EX},{"low_power","25041",EX},{"low_power","25731",EX},
            // Long range: 2745 and H (region rules in batch1Region); 2701 is G but not 2745.
            {"long_range_mode","27451",A},{"long_range_mode","25431",A},{"long_range_mode","24491",A},{"long_range_mode","28001",A},
            {"long_range_mode","27011",EX},{"long_range_mode","24431",EX},{"long_range_mode","25851",EX},
            // Charging family: S, Q, H; four prefixes need the fifth digit and are hidden when it is 2; never G.
            {"charge_limit","24169",A},{"charge_limit","24431",A},{"charge_limit","25291",A},{"charge_limit","25471",A},{"charge_limit","25851",A},{"charge_limit","24491",A},
            {"charge_limit","26581",A},{"charge_limit","28001",A},{"charge_limit","25431",A},{"charge_limit","25432",EX},{"charge_limit","2543",FP},{"charge_limit","27991",A},
            {"charge_limit","27992",EX},{"charge_limit","26572",EX},{"charge_limit","27072",EX},{"charge_limit","26582",A},{"charge_limit","27011",EX},{"charge_limit","27451",EX},
            {"charge_limit","26191",EX},{"charge_limit","24011",EX},{"charge_limit","25041",EX},
            {"timed_charging","24431",A},{"timed_charging","25851",A},{"timed_charging","27071",A},{"timed_charging","27072",EX},{"timed_charging","2707",FP},{"timed_charging","27011",EX},
            {"charge_start","24431",A},{"charge_start","25851",A},{"charge_start","26571",A},{"charge_start","26572",EX},{"charge_start","2657",FP},{"charge_start","26191",EX},
            // Lock time: the Bluetooth lock screen minus its own exclusions, plus three direct entries; group M is not mapped.
            {"lock_expiry","25731",A},{"lock_expiry","26341",A},{"lock_expiry","26231",A},{"lock_expiry","25471",A},{"lock_expiry","26191",A},{"lock_expiry","27011",A},
            {"lock_expiry","25451",A},{"lock_expiry","28081",A},{"lock_expiry","25481",A},{"lock_expiry","27821",A},{"lock_expiry","24361",EX},{"lock_expiry","24371",EX},
            {"lock_expiry","24381",EX},{"lock_expiry","25091",EX},{"lock_expiry","24411",EX},{"lock_expiry","25171",EX},{"lock_expiry","24431",EX},{"lock_expiry","24169",EX},
            {"lock_expiry","23141",EX},{"lock_expiry","24171",EX},{"lock_expiry","24221",EX},{"lock_expiry","22131",EX},{"lock_expiry","25041",NM},{"lock_expiry","25061",NM},
            // Walk, power and descent assist: Q and O.
            {"hill_start_assist","25471",A},{"hill_start_assist","25851",A},{"hill_start_assist","26191",A},{"hill_start_assist","26461",A},{"hill_start_assist","24431",EX},{"hill_start_assist","27011",EX},
            {"parking_assist","25851",A},{"parking_assist","26201",A},{"parking_assist","25041",EX},{"hill_descent_control","25471",A},{"hill_descent_control","26461",A},{"hill_descent_control","25431",EX},
            // Overspeed active control: eleven literals plus H, C, B, K, L, R, G; not Q, O, S, D (except 2545), M.
            {"overspeed_control","26111",A},{"overspeed_control","27531",A},{"overspeed_control","24171",A},{"overspeed_control","26341",A},{"overspeed_control","25731",A},
            {"overspeed_control","26121",A},{"overspeed_control","26431",A},{"overspeed_control","27541",A},{"overspeed_control","25091",A},{"overspeed_control","25451",A},
            {"overspeed_control","24381",A},{"overspeed_control","25431",A},{"overspeed_control","24021",A},{"overspeed_control","24421",A},{"overspeed_control","24011",A},
            {"overspeed_control","24411",A},{"overspeed_control","26231",A},{"overspeed_control","27011",A},{"overspeed_control","25471",EX},{"overspeed_control","26191",EX},
            {"overspeed_control","24431",EX},{"overspeed_control","25661",EX},{"overspeed_control","25041",EX},
            // Prompt tone: 2547, 2585 only (not 2538's layer, not 2506's sound effect).
            {"prompt_tone","25471",A},{"prompt_tone","25851",A},{"prompt_tone","24431",EX},{"prompt_tone","25061",EX},
            // Dual motor: 2443, 2585.
            {"dual_drive","24431",A},{"dual_drive","25851",A},{"dual_drive","25471",EX},{"dual_drive","24169",EX},
            // Eco/Turbo: 2345, 2714, 2509 (firmware), D, O; never Q.
            {"acceleration","23451",A},{"acceleration","27141",A},{"acceleration","26191",A},{"acceleration","25661",A},{"acceleration","25471",EX},{"acceleration","25851",EX},
            {"acceleration","24011",EX},{"acceleration","24431",EX},
            // PIN and proximity: the Bluetooth lock screen (K/C firmware in batch1Firmware); group M is not mapped.
            {"pin","24431",A},{"pin","25471",A},{"pin","26191",A},{"pin","25451",A},{"pin","25731",EX},{"pin","26341",EX},{"pin","26231",EX},{"pin","23141",EX},{"pin","24171",EX},
            {"pin","24221",EX},{"pin","22131",EX},{"pin","25041",NM},{"pin","25061",NM},
            {"proximity_key","24431",A},{"proximity_key","25851",A},{"proximity_key","26231",EX},{"proximity_key","22131",EX},{"proximity_key","25061",NM},
        };
        table(rows,data());
        // A shared or serial-only model gets none of these; the UT5 MAX keeps its own variants.
        for(String key:new String[]{"traction_control","low_power","charge_limit","lock_expiry","acceleration","pin"}){
            check(decide(key,ModelRegistryTest.serial("Q2443ZZ9US7777777"),data()).reason==CapabilityResolver.Reason.MODEL_NOT_IDENTIFIED,"Serial-only stays unidentified "+key);
            check(decide(key,ModelRegistryTest.advertised("39991"),data()).reason==CapabilityResolver.Reason.MODEL_NOT_MAPPED,"Unknown advertised stays unmapped "+key);
            check(decide(key,ModelRegistryTest.serial("Q2538ZZ9US7777777"),data()).variant.id.equals(key+".ut5"),"UT5 MAX keeps "+key+".ut5");
        }
    }

    /** Firmware gates: equal, just below, just above, unknown (not read this session), and the OEM's lexicographic order. */
    private static void batch1Firmware() {
        String OLD="FIRMWARE_OLD",UNK="FIRMWARE_UNKNOWN";
        Object[][] rows={
            // Lock time: blocked up to and including 2.0.1.1 for K and C, 2.0.5.3 for B.
            {"lock_expiry","24011","2.0.1.1",OLD},{"lock_expiry","24011","2.0.1.0",OLD},{"lock_expiry","24011","2.0.1.2",A},{"lock_expiry","24011",null,UNK},
            {"lock_expiry","24011","2.0.10.0",A},{"lock_expiry","27041","2.0.1.1",OLD},{"lock_expiry","27041","2.0.2.0",A},{"lock_expiry","23451","2.0.0.4",OLD},
            {"lock_expiry","24421","2.0.5.3",OLD},{"lock_expiry","24421","2.0.5.4",A},{"lock_expiry","24421",null,UNK},{"lock_expiry","25191","2.0.5.2",OLD},
            {"lock_expiry","25471",null,A},
            // Eco/Turbo for 2509: blocked below 2.0.3.5.
            {"acceleration","25091","2.0.3.5",A},{"acceleration","25091","2.0.3.4",OLD},{"acceleration","25091",null,UNK},{"acceleration","25091","2.0.4.0",A},{"acceleration","23451",null,A},
            // Bluetooth lock screen (PIN, proximity) for K and C: blocked up to and including 2.0.0.4.
            {"pin","24011","2.0.0.4",OLD},{"pin","24011","2.0.0.5",A},{"pin","24011",null,UNK},{"pin","24181","2.0.0.3",OLD},{"proximity_key","27141","2.0.0.4",OLD},
            {"proximity_key","27141","2.0.0.5",A},{"proximity_key","24031",null,UNK},{"pin","24421",null,A},
        };
        for(Object[] r:rows){
            String key=(String)r[0],pid=(String)r[1],meter=(String)r[2],expected=(String)r[3];
            CapabilityResolver.Decision d=decide(key,firmware(pid,meter),data());
            check((d.available()?A:d.reason.name()).equals(expected),key+" "+pid+" meter "+meter+" expected "+expected+" got "+d);
            if(!expected.equals(A))check(d.support==CapabilityResolver.Support.KNOWN_ENCODING,"A firmware refusal keeps the mapping visible "+d);
            boolean kc=ModelRegistry.any(model(pid),ModelRegistry.K)||ModelRegistry.any(model(pid),ModelRegistry.C),b=ModelRegistry.any(model(pid),ModelRegistry.B);
            boolean gated=key.equals("acceleration")?pid.startsWith("2509"):key.equals("lock_expiry")?kc||b:kc;
            check(d.preconditions.contains(CapabilityResolver.Precondition.FIRMWARE_READ)==gated,"Firmware precondition recorded "+key+" "+pid);
        }
        // Stored firmware never authorises: the same version from an earlier session is unknown.
        DeviceDescriptor stored=DeviceDescriptor.of("Q2401ZZ9US7777777","24011",1,new DeviceDescriptor.Firmware("2.0.5.0",null,null,null,null,SEEN),false,DeviceDescriptor.Transport.STANDARD,0);
        check(decide("lock_expiry",stored,data()).reason==CapabilityResolver.Reason.FIRMWARE_UNKNOWN,"Stored firmware is not this session's");
    }

    /** Long range is a riding control: its region rule is enforced. RU/US always; EU/AT/IT/SE/ME with the scooter flag; never DE, CN, NA or no region. */
    private static void batch1Region() {
        byte[] flag0=data(),flag1=data();flag1[35]=1;
        String RR="REGION_RULE";
        String[][] rows={{"US","0",A},{"RU","0",A},{"CN","0",RR},{"EU","0",RR},{"EU","1",A},{"AT","1",A},{"IT","1",A},{"SE","1",A},{"ME","1",A},
            {"DE","1",RR},{"NE","1",RR},{"NL","1",RR},{"ZZ","1",RR},{"UK","1",RR},{"eu","1",RR}};
        for(String[] r:rows)for(String pid:new String[]{"27451","25431"}){
            CapabilityResolver.Decision d=decide("long_range_mode",model(pid,r[0]),r[1].equals("1")?flag1:flag0);
            check((d.available()?A:d.reason.name()).equals(r[2]),"Long range "+pid+" "+r[0]+" flag "+r[1]+" expected "+r[2]+" got "+d);
            check(d.support==CapabilityResolver.Support.KNOWN_ENCODING&&(r[2].equals(A)==d.oemNote.isEmpty()),"Region refusal keeps the mapping, with a note");
        }
        // Offset 35 absent is not a flag; an invalid serial has no region.
        check(decide("long_range_mode",model("27451","EU"),Arrays.copyOf(flag1,35)).reason==CapabilityResolver.Reason.REGION_RULE,"Absent flag");
        DeviceDescriptor invalid=ModelRegistryTest.both("Q2745ZZ9US777777","27451");
        check(invalid.region==null&&decide("long_range_mode",invalid,flag1).reason==CapabilityResolver.Reason.REGION_RULE,"No region");
        // Convenience and charging rows ignore region (permissive).
        for(String area:new String[]{"DE","NL","ZZ"})check(decide("charge_limit",model("24431",area),flag0).available()&&decide("low_power",model("24431",area),flag0).available(),"No region rule for charging "+area);
    }

    /** Readback: exact values, masked bits, unknown values, sentinels, and a status truncated just before each field. */
    private static void batch1Fields() {
        Object[][] fields={{"traction_control","25471",11},{"low_power","24431",32},{"long_range_mode","27451",38},{"charge_limit","24431",31},{"timed_charging","24431",33},
            {"lock_expiry","25471",34},{"hill_start_assist","25851",37},{"parking_assist","25851",37},{"hill_descent_control","25851",37},{"overspeed_control","27011",39},
            {"prompt_tone","25851",21},{"dual_drive","24431",26},{"acceleration","26191",1},{"proximity_key","24431",13}};
        byte[] d=data();
        for(Object[] f:fields){
            String key=(String)f[0];int offset=(Integer)f[2];
            CapabilityResolver.Decision full=decide(key,model((String)f[1]),d);
            check(full.available()&&full.variant.offset==offset,"Field "+key+" at "+offset);
            CapabilityResolver.Decision cut=decide(key,model((String)f[1]),Arrays.copyOf(d,offset));
            check(cut.reason==CapabilityResolver.Reason.FIELD_ABSENT&&cut.current()==null,"Truncated before "+key+" is absent, never 0");
            check(decide(key,model((String)f[1]),Arrays.copyOf(d,offset+1)).current()!=null,"Present at length "+(offset+1));
        }
        check(decide("charge_start",model("24431"),Arrays.copyOf(d,13)).available(),"Charge start has no readback gate (BE32 confirmed after the write)");
        check(decide("pin",model("24431"),Arrays.copyOf(d,13)).available(),"PIN state comes from 0x7a, not 0x70");
        // Current values from the fixture.
        Object[][] current={{"traction_control","25471",1},{"low_power","24431",0},{"charge_limit","24431",90},{"timed_charging","24431",1},{"lock_expiry","25471",2},
            {"hill_start_assist","25851",1},{"parking_assist","25851",0},{"hill_descent_control","25851",1},{"prompt_tone","25851",2},{"dual_drive","24431",0},{"acceleration","26191",3}};
        for(Object[] c:current)check(Integer.valueOf((Integer)c[2]).equals(decide((String)c[0],model((String)c[1]),d).current()),"Current "+c[0]);
        // Exact readback: any other byte is unknown and refuses changes (never guessed as on/off).
        Object[][] unknown={{"traction_control","25471",11,2},{"low_power","24431",32,3},{"timed_charging","24431",33,255},{"lock_expiry","25471",34,0},{"lock_expiry","25471",34,4},
            {"charge_limit","24431",31,0},{"charge_limit","24431",31,87},{"prompt_tone","25851",21,5},{"dual_drive","24431",26,2},{"acceleration","26191",1,2},{"acceleration","26191",1,11},
            {"overspeed_control","27011",39,128}};
        for(Object[] u:unknown){
            byte[] x=d.clone();x[(Integer)u[2]]=(byte)(int)(Integer)u[3];
            check(decide((String)u[0],model((String)u[1]),x).reason==CapabilityResolver.Reason.VALUE_UNKNOWN,"Unknown value "+u[0]+"="+u[3]);
        }
        byte[] unset=d.clone();unset[31]=0;CapabilityResolver.Decision limit=decide("charge_limit",model("24431"),unset);
        check(limit.current()==0&&limit.label(0).equals("Default (80%)")&&!limit.allows(0)&&Arrays.equals(limit.values(),new int[]{80,85,90,95,100}),"Unset charge limit is shown, never offered");
        byte[] bits=d.clone();bits[37]=(byte)0xfa; // bit 1 set, bits 0 and 2 clear, unrelated high bits
        check(decide("hill_start_assist",model("26191"),bits).current()==0&&decide("parking_assist",model("26191"),bits).current()==1&&decide("hill_descent_control",model("26191"),bits).current()==0,"Assist bits");
        byte[] near=d.clone();near[13]=(byte)0xfe;check(decide("proximity_key",model("24431"),near).current()==0,"Proximity bit 0 only");
        byte[] turbo=d.clone();turbo[1]=5;check(decide("acceleration",model("26191"),turbo).current()==5&&decide("acceleration",model("26191"),turbo).label(5).equals("Turbo"),"Turbo");
    }

    /** Frozen frame bytes for every new variant value; each also equals the UT5 MAX encoder where its domain allows the value. */
    private static void batch1Frames() throws Exception {
        String[][] frames={
            {"OEM_TCS","0","55aa005f01005ffefd"},
            {"OEM_TCS","1","55aa005f010160fefd"},
            {"OEM_LOW_POWER","0","55aa006f02050075fefd"},
            {"OEM_LOW_POWER","1","55aa006f02050176fefd"},
            {"OEM_LONG_RANGE","0","55aa006f02070077fefd"},
            {"OEM_LONG_RANGE","1","55aa006f02070178fefd"},
            {"OEM_CHARGE_LIMIT","80","55aa006f020450c4fefd"},
            {"OEM_CHARGE_LIMIT","85","55aa006f020455c9fefd"},
            {"OEM_CHARGE_LIMIT","90","55aa006f02045acefefd"},
            {"OEM_CHARGE_LIMIT","95","55aa006f02045fd3fefd"},
            {"OEM_CHARGE_LIMIT","100","55aa006f020464d8fefd"},
            {"OEM_TIMED_CHARGING","0","55aa006f02010071fefd"},
            {"OEM_TIMED_CHARGING","1","55aa006f02010172fefd"},
            {"OEM_LOCK_TIME","1","55aa006f02020173fefd"},
            {"OEM_LOCK_TIME","2","55aa006f02020274fefd"},
            {"OEM_LOCK_TIME","3","55aa006f02020375fefd"},
            {"OEM_WALK_ASSIST","0","55aa008102010083fefd"},
            {"OEM_WALK_ASSIST","1","55aa008102010184fefd"},
            {"OEM_POWER_ASSIST","0","55aa008102020084fefd"},
            {"OEM_POWER_ASSIST","1","55aa008102020185fefd"},
            {"OEM_DESCENT","0","55aa008102030085fefd"},
            {"OEM_DESCENT","1","55aa008102030186fefd"},
            {"OEM_OVERSPEED","0","55aa0082010082fefd"},
            {"OEM_OVERSPEED","1","55aa0082010183fefd"},
            {"OEM_PROMPT_TONE","0","55aa006c01006cfefd"},
            {"OEM_PROMPT_TONE","1","55aa006c01016dfefd"},
            {"OEM_PROMPT_TONE","2","55aa006c01026efefd"},
            {"OEM_PROMPT_TONE","3","55aa006c01036ffefd"},
            {"OEM_PROMPT_TONE","4","55aa006c010470fefd"},
            {"OEM_DUAL_DRIVE","0","55aa006e02020071fefd"},
            {"OEM_DUAL_DRIVE","1","55aa006e02020172fefd"},
            {"OEM_ACCELERATION","3","55aa005801035bfefd"},
            {"OEM_ACCELERATION","5","55aa005801055dfefd"},
            {"OEM_PROXIMITY","0","55aa0061010061fefd"},
            {"OEM_PROXIMITY","1","55aa0061010162fefd"},
        };
        for(String[] f:frames){
            FeatureVariant v=(FeatureVariant)ModelRegistry.class.getDeclaredField(f[0]).get(null);int value=Integer.parseInt(f[1]);
            check(Protocol.hex(v.encode(value)).equals(f[2]),"Frame "+v.id+"="+value+" "+Protocol.hex(v.encode(value)));
            ScooterCommands.Feature ut5=ScooterCommands.find(v.feature);
            if(ut5.allows(value)&&!v.feature.equals("proximity_key"))check(Arrays.equals(v.encode(value),ScooterCommands.set(v.feature,value)),"Same wire as the UT5 MAX "+v.id+"="+value);
        }
        for(String name:new String[]{"OEM_TCS","OEM_LOW_POWER","OEM_LONG_RANGE","OEM_CHARGE_LIMIT","OEM_TIMED_CHARGING","OEM_LOCK_TIME","OEM_WALK_ASSIST","OEM_POWER_ASSIST",
                "OEM_DESCENT","OEM_OVERSPEED","OEM_PROMPT_TONE","OEM_DUAL_DRIVE","OEM_ACCELERATION","OEM_PROXIMITY"}){
            FeatureVariant v=(FeatureVariant)ModelRegistry.class.getDeclaredField(name).get(null);
            for(int value=-1;value<=256;value++)if(!v.allows(value)){boolean refused=false;try{v.encode(value);}catch(IllegalArgumentException expected){refused=true;}check(refused,"Outside the domain is never encoded "+v.id+"="+value);}
        }
        boolean refused=false;try{ModelRegistry.OEM_CHARGE_START.encode(1);}catch(IllegalArgumentException expected){refused=true;}
        check(refused,"Charge start keeps its dedicated builder");
        check(Protocol.hex(ScooterCommands.setChargeStart(1700002800L,0)).equals("55aa006f05036553fbf019fefd"),"Charge start frame (whole hour)");
    }

    /** Charge start domain (D5), cautions, admission and the UT5 MAX staying exactly as it was. */
    private static void batch1Rules() {
        long now=1700000000L,hour=1700002800L; // 1700002800 is a whole UTC hour after now
        check(hour%3600==0&&hour>now,"Fixture is a whole hour ahead");
        ModelRegistry.Layer oem=ModelRegistry.Layer.OEM_GROUP,shared=ModelRegistry.Layer.SHARED,ut5=ModelRegistry.Layer.UT5_MAX;
        check(ModelRegistry.chargeStartAllowed(oem,hour,0,now)&&ModelRegistry.chargeStartAllowed(oem,hour+47*3600,0,now),"Whole hours within 48 h");
        long last=((now+48*3600)/3600)*3600;check(ModelRegistry.chargeStartAllowed(oem,last,0,now)&&!ModelRegistry.chargeStartAllowed(oem,last+3600,0,now),"48 h is the last start");
        check(!ModelRegistry.chargeStartAllowed(oem,hour+60,0,now)&&!ModelRegistry.chargeStartAllowed(oem,hour+1800,0,now),"Minutes refused off the UT5 MAX");
        check(!ModelRegistry.chargeStartAllowed(oem,hour-3600,0,now)&&!ModelRegistry.chargeStartAllowed(oem,now,0,now)&&!ModelRegistry.chargeStartAllowed(shared,hour+60,0,now),"Past and shared refused");
        check(ModelRegistry.chargeStartAllowed(oem,hour+5*3600-19800,19800,now)&&!ModelRegistry.chargeStartAllowed(oem,hour,19800,now),"A whole local hour, not UTC (+05:30)");
        check(ModelRegistry.chargeStartAllowed(ut5,hour+60,0,now)&&ModelRegistry.chargeStartAllowed(ut5,now+7*86400,0,now),"UT5 MAX keeps any minute");
        // Cautions: only for OEM-mapped variants off the UT5 MAX layer.
        byte[] d=data();
        check(ModelRegistry.caution(decide("traction_control",model("25471"),d),0)!=null&&ModelRegistry.caution(decide("traction_control",model("25471"),d),1)==null,"TCS off asks first");
        check(ModelRegistry.caution(decide("low_power",model("24431"),d),0)!=null&&ModelRegistry.caution(decide("dual_drive",model("24431"),d),0)!=null,"Low power and dual motor off ask first");
        for(ScooterCommands.Feature f:ScooterCommands.catalog())for(int v:new int[]{0,1,2,30,40,60,70})
            check(ModelRegistry.caution(decide(f.key,ModelRegistryTest.serial("Q2538ZZ9US7777777"),d),v)==null,"UT5 MAX keeps direct controls "+f.key);
        // Admission: the domain is the variant's; values outside it are refused before any frame.
        CapabilityResolver.Runtime rt=CapabilityResolverTest.parked();
        check(MutationGate.check(new MutationGate.Request(MutationGate.Kind.SET,"acceleration",5,MutationGate.Origin.USER),model("26191"),status(d),rt).available(),"Turbo admitted on O");
        check(MutationGate.check(new MutationGate.Request(MutationGate.Kind.SET,"acceleration",4,MutationGate.Origin.USER),model("26191"),status(d),rt).reason==CapabilityResolver.Reason.MODEL_EXCLUDED,"Unknown mode refused");
        check(MutationGate.check(new MutationGate.Request(MutationGate.Kind.SET,"charge_limit",0,MutationGate.Origin.USER),model("24431"),status(d),rt).reason==CapabilityResolver.Reason.MODEL_EXCLUDED,"Sentinel never sent");
        check(MutationGate.check(new MutationGate.Request(MutationGate.Kind.SET,"pin",1,MutationGate.Origin.USER),model("24431"),status(d),rt).reason==CapabilityResolver.Reason.MODEL_EXCLUDED,"PIN keeps its dedicated kind");
        rt.owner=false;
        check(MutationGate.check(new MutationGate.Request(MutationGate.Kind.PIN_SET,"pin",1,MutationGate.Origin.USER),model("24431"),status(d),rt).reason==CapabilityResolver.Reason.OWNER_ONLY,"PIN owner only");
        rt=CapabilityResolverTest.parked();rt.bonded=false;
        check(MutationGate.check(new MutationGate.Request(MutationGate.Kind.PROXIMITY,"proximity_key",1,MutationGate.Origin.USER),model("24431"),status(d),rt).reason==CapabilityResolver.Reason.NEEDS_BOND,"Proximity: bond first");
        rt=CapabilityResolverTest.parked();rt.pinEnabledFresh=false;
        check(MutationGate.check(new MutationGate.Request(MutationGate.Kind.PROXIMITY,"proximity_key",1,MutationGate.Origin.USER),model("24431"),status(d),rt).reason==CapabilityResolver.Reason.NEEDS_PIN,"Proximity: PIN always");
        rt=CapabilityResolverTest.parked();rt.stationary=false;
        String[][] parkedRows={{"traction_control","25471"},{"low_power","24431"},{"charge_limit","24431"},{"lock_expiry","25851"},{"acceleration","26191"},{"prompt_tone","25851"},{"pin","24431"}};
        for(String[] r:parkedRows)check(CapabilityResolver.decide(r[0],model(r[1]),status(d),rt).reason==CapabilityResolver.Reason.MOVING,"Parked only "+r[0]);
        check(ModelRegistry.VERSION>=3,"Registry version records C5");
    }

    static void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}
}
