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
        batch2MaximumSpeed(); batch2SpeedLimit(); batch2StartSpeed(); batch2Energy(); batch2Frames();
        batch3Zones(); batch3GroupS(); batch3Frames();
        regionalUnion(); quickAccessFollowsDecision();
        System.out.println("OemControls tests passed: "+assertions+" assertions");
    }

    /** A valid serial whose model digits and region match the advertised ID (no identity conflict). */
    static DeviceDescriptor model(String pid,String area){return ModelRegistryTest.both("Q"+pid.substring(0,4)+"ZZ9"+area+"7777777",pid);}
    static DeviceDescriptor model(String pid){return model(pid,"US");}
    /** The same with meter firmware read in this session (null = 0x73 not read). */
    static DeviceDescriptor firmware(String pid,String meter){
        return firmware(pid,"US",meter);
    }
    static DeviceDescriptor firmware(String pid,String area,String meter){
        DeviceDescriptor.Firmware fw=meter==null?DeviceDescriptor.Firmware.NONE:new DeviceDescriptor.Firmware(meter,null,null,null,null,SEEN);
        String serial=area==null?null:"Q"+pid.substring(0,4)+"ZZ9"+area+"7777777";
        return DeviceDescriptor.of(serial,pid,1,fw,meter!=null,DeviceDescriptor.Transport.STANDARD,0);
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

    /** Regional long-range visibility is provenance only; the same-model actual field is still required. */
    private static void batch1Region() {
        byte[] flag0=data(),flag1=data();flag1[35]=1;
        String N="noted";
        String[][] rows={{"US","0",A},{"RU","0",A},{"CN","0",N},{"EU","0",N},{"EU","1",A},{"AT","1",A},{"IT","1",A},{"SE","1",A},{"ME","1",A},
            {"DE","1",N},{"NE","1",N},{"NL","1",N},{"ZZ","1",N},{"UK","1",N},{"eu","1",N}};
        for(String[] r:rows)for(String pid:new String[]{"27451","25431"}){
            CapabilityResolver.Decision d=decide("long_range_mode",model(pid,r[0]),r[1].equals("1")?flag1:flag0);
            check(d.available(),"Long range "+pid+" "+r[0]+" flag "+r[1]+" has same-model availability: "+d);
            check(d.support==CapabilityResolver.Support.KNOWN_ENCODING&&(r[2].equals(A)==d.oemNote.isEmpty()),"Regional provenance remains accurate");
        }
        // Truncation also removes actual offset 38; this is a missing field, not a regional refusal.
        check(decide("long_range_mode",model("27451","EU"),Arrays.copyOf(flag1,35)).reason==CapabilityResolver.Reason.FIELD_ABSENT,"Actual long-range field required");
        DeviceDescriptor invalid=ModelRegistryTest.both("Q2745ZZ9US777777","27451");
        check(invalid.region==null&&decide("long_range_mode",invalid,flag1).available(),"No region retains same-model long range");
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


    static void values(String key,DeviceDescriptor d,byte[] data,int[] expected){
        CapabilityResolver.Decision decision=decide(key,d,data);
        check(Arrays.equals(decision.values(),expected),key+" "+d+" values "+Arrays.toString(decision.values())+" expected "+Arrays.toString(expected));
    }
    static int[] ints(int... v){return v;}
    static int[] span(int from,int to){int[] out=new int[to-from+1];for(int i=0;i<out.length;i++)out[i]=from+i;return out;}
    static int[] off(int[] kmh){int[] out=new int[kmh.length+1];System.arraycopy(kmh,0,out,1,kmh.length);return out;}

    /** Maximum speed: the OEM table per prefix (never probed), regional provenance, K/C firmware and the fifth digit. */
    private static void batch2MaximumSpeed() {
        byte[] d=data();d[25]=25; // 25 km/h is in every table
        Object[][] tables={{"24021",ints(25,32)},{"27041",ints(25,32)},{"24421",ints(25,32)},{"25191",ints(25,32)},{"27011",ints(25,32)},{"24169",ints(25,32,40,50)},
            {"24431",ints(25,32,45,50)},{"25291",ints(25,32,45,50)},{"25471",ints(25,32,40,50,60)},{"25851",ints(25,32,40,50,70)},{"25091",ints(25,30,35,40)},
            {"26571",ints(25,30,35,40)},{"27071",ints(25,30,35,40)},{"27451",ints(25,30,35,40)},{"25431",ints(25,30,35,40,45,50)},{"25432",ints(25,30,35,40)},
            {"27991",ints(25,30,35,40,45,50)},{"27993",ints(25,30,35,40)},{"26581",ints(25,30,35,40,45,50)},{"24491",ints(25,30,35,40,45,50,55,60,65)},
            {"28001",ints(25,30,35,40,45,50,55,60,65)},{"24411",ints(25,32,40,45)},{"25171",ints(25,32,40,45)},{"25451",ints(25,32,40,45)},{"25661",ints(25,32,40,45)},
            {"26191",ints(25,32,40,45)},{"26461",ints(25,32,40,45)},{"27141",ints(25,32,40,45)},{"24011",ints(25,32,40,45)}};
        for(Object[] t:tables){
            String pid=(String)t[0];int[] expected=(int[])t[1];
            boolean kc=ModelRegistry.any(model(pid),ModelRegistry.K)||ModelRegistry.any(model(pid),ModelRegistry.C);
            DeviceDescriptor dev=kc?firmware(pid,"2.0.1.2"):model(pid);
            check(reason("maximum_speed",dev,d).equals(A),"Maximum speed on "+pid+" "+decide("maximum_speed",dev,d));
            values("maximum_speed",dev,d,expected);
            for(int kmh=0;kmh<=80;kmh++){boolean offered=false;for(int v:expected)offered|=v==kmh;check(decide("maximum_speed",dev,d).allows(kmh)==offered,"Only the OEM table "+pid+" "+kmh);}
        }
        check(decide("maximum_speed",model("2543"),d).reason==CapabilityResolver.Reason.FULL_PID_NEEDED&&decide("maximum_speed",model("2799"),d).reason==CapabilityResolver.Reason.FULL_PID_NEEDED,"Fifth digit needed");
        for(String pid:new String[]{"28081","26111","27531","25731","26341","23141","24171","25041","25061","22131","26231","27821"})
            check(reason("maximum_speed",model(pid),d).equals("MODEL_EXCLUDED"),"No maximum speed row on "+pid);
        for(String area:new String[]{"US","CN","RU","EU","DE","IT","NE","NL","ZZ","us"}){
            CapabilityResolver.Decision x=decide("maximum_speed",model("24431",area),d);
            check(x.available()&&Arrays.equals(x.values(),ints(25,32,45,50)),"Same-model max speed regardless of region "+area+" "+x);}
        check(decide("maximum_speed",ModelRegistryTest.both("Q2443ZZ9US777777","24431"),d).available(),"No region retains mapped maximum speed");
        check(decide("maximum_speed",model("26201","EU"),d).available(),"2620's EU visibility needs no server");
        // K and C: blocked below meter 2.0.1.2; equal passes; unknown fails closed; lexicographic order.
        String[][] fw={{"2.0.1.2",A},{"2.0.1.1","FIRMWARE_OLD"},{"2.0.0.9","FIRMWARE_OLD"},{"2.0.2.0",A},{"2.0.10.0",A},{null,"FIRMWARE_UNKNOWN"}};
        for(String pid:new String[]{"24011","23451","27141","24021","24031","24181","27041"})for(String[] f:fw){
            CapabilityResolver.Decision x=decide("maximum_speed",firmware(pid,f[0]),d);
            check((x.available()?A:x.reason.name()).equals(f[1]),"Max speed "+pid+" meter "+f[0]+" "+x);
        }
        check(decide("maximum_speed",model("24431"),d).preconditions.contains(CapabilityResolver.Precondition.FIRMWARE_READ)==false,"No firmware gate for S");
        // Readback offset 25 exact: absent, unknown, a value outside this table.
        check(decide("maximum_speed",model("24431"),Arrays.copyOf(d,25)).reason==CapabilityResolver.Reason.FIELD_ABSENT,"Truncated before offset 25");
        byte[] zero=d.clone();zero[25]=0;check(decide("maximum_speed",model("24431"),zero).reason==CapabilityResolver.Reason.VALUE_UNKNOWN,"0 is the OEM's unset value: unknown, not a speed");
        byte[] forty=d.clone();forty[25]=40;check(decide("maximum_speed",model("24431"),forty).reason==CapabilityResolver.Reason.VALUE_UNKNOWN&&decide("maximum_speed",model("24169"),forty).current()==40,"40 is outside the 2443 table");
        // 2585 above 32 asks first (the official app's US prompt); other models and lower speeds do not.
        check(ModelRegistry.caution(decide("maximum_speed",model("25851"),d),40)!=null&&ModelRegistry.caution(decide("maximum_speed",model("25851"),d),32)==null
            &&ModelRegistry.caution(decide("maximum_speed",model("25471"),d),60)==null&&!decide("maximum_speed",model("25851"),d).oemNote.isEmpty(),"2585 prompt above 32 km/h");
        // CN and RU share the USA row, but the official prompt is for the US area only: no OEM claim there.
        for(String area:new String[]{"CN","RU"}){
            CapabilityResolver.Decision x=decide("maximum_speed",model("25851",area),d);
            String caution=ModelRegistry.caution(x,40);
            check(x.available()&&x.oemNote.isEmpty()&&caution!=null&&!caution.contains("official app"),"2585 "+area+" makes no OEM prompt claim: "+caution);
        }
        check(ModelRegistry.caution(decide("maximum_speed",model("25851"),d),40).contains("official app asks"),"2585 US keeps the OEM prompt wording");
        // The UT5 MAX keeps its four choices; the wider allowlist never reaches it.
        CapabilityResolver.Decision ut5=decide("maximum_speed",ModelRegistryTest.serial("Q2538ZZ9US7777777"),d);
        check(Arrays.equals(ut5.values(),ints(25,32,40,50))&&!ut5.allows(60)&&!ut5.allows(45)&&ut5.variant.id.equals("maximum_speed.ut5"),"UT5 MAX maximum speed unchanged");
    }

    /** Custom speed cap: union of this model's regional ranges, Off as 0, 7-bit km/h with the enable bit. */
    private static void batch2SpeedLimit() {
        byte[] d=data();d[20]=(byte)0x88; // on, 8 km/h: inside every range
        Object[][] rows={{"27821","US",off(ints(8,15,25,32))},{"27821","DE",off(ints(8,15,25,32))},{"25061","US",off(span(8,20))},{"25061","CN",off(span(8,20))},
            {"25061","RU",off(span(8,20))},{"25061","DE",off(span(8,20))},{"25041","US",off(span(8,16))},{"25051","RU",off(span(8,16))},
            {"25731","EU",off(span(8,32))},{"26341","RU",off(span(8,32))},{"26111","DE",off(span(8,32))},{"27531","US",off(span(8,32))},{"26121","eu",off(span(8,32))},
            {"26431","ZZ",off(span(8,32))},{"27541","EU",off(span(8,32))}};
        for(Object[] r:rows){DeviceDescriptor dev=model((String)r[0],(String)r[1]);check(reason("speed_limit",dev,d).equals(A),"Speed cap "+r[0]+" "+r[1]);values("speed_limit",dev,d,(int[])r[2]);}
        check(Arrays.equals(decide("speed_limit",ModelRegistryTest.both("Q2573ZZ9US777777","25731"),d).values(),off(span(8,32))),"No region: same-model 8-32 union");
        for(String pid:new String[]{"25471","24431","27811","26231","24011","28081"})check(reason("speed_limit",model(pid),d).equals("MODEL_EXCLUDED"),"No speed cap on "+pid);
        CapabilityResolver.Decision cap=decide("speed_limit",model("27821"),d);
        check(cap.current()==8&&cap.label(0).equals("Off")&&cap.allows(0)&&!cap.allows(9)&&!cap.allows(33),"2782 radio choices");
        byte[] offNow=d.clone();offNow[20]=0x05;check(decide("speed_limit",model("25731","EU"),offNow).current()==0,"Enable bit clear reads as Off");
        byte[] seven=d.clone();seven[20]=(byte)0x87;check(decide("speed_limit",model("25731","EU"),seven).reason==CapabilityResolver.Reason.VALUE_UNKNOWN,"7 km/h is outside every range");
        byte[] high=d.clone();high[20]=(byte)(0x80|30);
        check(decide("speed_limit",model("25731","EU"),high).current()==30&&decide("speed_limit",model("26341","RU"),high).current()==30,"Same-model union accepts regional values");
        check(decide("speed_limit",model("25061","EU"),high).reason==CapabilityResolver.Reason.VALUE_UNKNOWN,"Union never borrows another model's range");
        check(decide("speed_limit",model("25731","EU"),Arrays.copyOf(d,20)).reason==CapabilityResolver.Reason.FIELD_ABSENT,"Truncated before offset 20");
        CapabilityResolver.Decision ut5=decide("speed_limit",ModelRegistryTest.serial("Q2538ZZ9US7777777"),d);
        check(ut5.variant.lab&&ut5.variant.id.equals("speed_limit.ut5"),"UT5 MAX keeps the Lab cap");
    }

    /** Kick-start speed: same-model regional union 0-5, group M remains 3-5; firmware gates remain. */
    private static void batch2StartSpeed() {
        byte[] d=data();d[19]=3;
        Object[][] rows={{"24431","US",span(0,5)},{"24431","CN",span(0,5)},{"24431","RU",span(0,5)},{"24431","EU",span(0,5)},{"24431","DE",span(0,5)},{"24431","ZZ",span(0,5)},
            {"25471","US",span(0,5)},{"24411","US",span(0,5)},{"24421","IT",span(0,5)},{"25451","US",span(0,5)},{"26191","NE",span(0,5)},{"26111","US",span(0,5)},
            {"25731","EU",span(0,5)},{"25091","US",span(0,5)},{"25431","US",span(0,5)},{"24491","DE",span(0,5)},{"26231","US",span(0,5)},{"27821","US",span(0,5)},
            {"25041","US",span(3,5)},{"25061","RU",span(3,5)},{"27011","US",span(0,5)},{"27451","IT",span(0,5)},{"27811","ZZ",span(0,5)},{"26571","US",span(0,5)},
            {"26581","EU",span(0,5)},{"27071","UK",span(0,5)},{"27991","US",span(0,5)},{"28001","SE",span(0,5)}};
        for(Object[] r:rows){DeviceDescriptor dev=model((String)r[0],(String)r[1]);check(reason("start_speed",dev,d).equals(A),"Start speed "+r[0]+" "+r[1]+" "+decide("start_speed",dev,d));values("start_speed",dev,d,(int[])r[2]);}
        // Formerly regional members retain the same-model choices even with no region or DE.
        for(String pid:new String[]{"27011","27451","27811","26571","26581","27071","27991","28001"}){
            check(reason("start_speed",model(pid,"DE"),d).equals(A),"DE retains start speed on "+pid);
            DeviceDescriptor invalid=ModelRegistryTest.both("Q"+pid.substring(0,4)+"ZZ9US777777",pid);
            check(invalid.region==null&&reason("start_speed",invalid,d).equals(A),"No region retains start speed on "+pid);
        }
        check(reason("start_speed",ModelRegistryTest.both("Q2443ZZ9US777777","24431"),d).equals(A)&&Arrays.equals(decide("start_speed",ModelRegistryTest.both("Q2443ZZ9US777777","24431"),d).values(),span(0,5)),"Mapped members keep full same-model choices without a region");
        for(String pid:new String[]{"28081","24171","24221","22131","24361"})check(reason("start_speed",model(pid),d).equals("MODEL_EXCLUDED"),"No start speed on "+pid);
        // Firmware: K/C below 2.0.1.2; 2314 by fifth digit (1: below 0.0.0.8; 2: below 2.0.0.9; others: none).
        Object[][] fw={{"24011","2.0.1.2",A},{"24011","2.0.1.1","FIRMWARE_OLD"},{"24011",null,"FIRMWARE_UNKNOWN"},{"24181","2.0.1.3",A},
            {"23141","0.0.0.8",A},{"23141","0.0.0.7","FIRMWARE_OLD"},{"23141",null,"FIRMWARE_UNKNOWN"},{"23142","2.0.0.9",A},{"23142","2.0.0.8","FIRMWARE_OLD"},
            {"23142","0.0.0.8","FIRMWARE_OLD"},{"23142",null,"FIRMWARE_UNKNOWN"},{"23143",null,A}};
        for(Object[] f:fw){CapabilityResolver.Decision x=decide("start_speed",firmware((String)f[0],(String)f[1]),d);
            check((x.available()?A:x.reason.name()).equals(f[2]),"Start speed "+f[0]+" meter "+f[1]+" "+x);}
        check(reason("start_speed",model("2314"),d).equals("FULL_PID_NEEDED"),"2314 needs the fifth digit");
        // Readback offset 19 exact; 0-2 show the official app's caution; 3-5 on a 3-5 model read 0 as unknown.
        check(decide("start_speed",model("24431"),Arrays.copyOf(d,19)).reason==CapabilityResolver.Reason.FIELD_ABSENT,"Truncated before offset 19");
        byte[] zero=d.clone();zero[19]=0;check(decide("start_speed",model("25041","EU"),zero).reason==CapabilityResolver.Reason.VALUE_UNKNOWN&&decide("start_speed",model("24431","EU"),zero).current()==0,"0 is allowed by regional union but remains outside group M");
        byte[] six=d.clone();six[19]=6;check(decide("start_speed",model("24431"),six).reason==CapabilityResolver.Reason.VALUE_UNKNOWN,"6 is outside every domain");
        for(int v=0;v<=5;v++)check((ModelRegistry.caution(decide("start_speed",model("24431"),d),v)!=null)==(v<3),"Caution below 3 km/h: "+v);
        CapabilityResolver.Decision ut5=decide("start_speed",ModelRegistryTest.serial("Q2538ZZ9DE7777777"),d);
        check(ut5.variant.id.equals("start_speed.ut5")&&ut5.allows(0)&&ut5.allows(5)&&ModelRegistry.caution(ut5,0)==null,"Every UT5 MAX keeps 0-5 and no prompt");
    }

    /**
     * All known standard model IDs retain the same six riding-control decisions across regions,
     * including absent/unknown regions and either regional visibility flag. Explicit per-model
     * domain fixtures above independently establish the expected unions. Exercise admission and
     * encoded bytes too: presentation alone must never bypass the remaining runtime guards.
     */
    private static void regionalUnion() {
        String[] keys={"cruise_control","energy_recovery","long_range_mode","maximum_speed","speed_limit","start_speed"};
        String[] areas={"US","RU","CN","EU","DE","NL","AT","IT","SE","ZZ",null};
        int mapped=0;
        for(String prefix:ModelRegistry.LISTED){
            String pid=prefix.length()==4?prefix+"1":prefix;
            DeviceDescriptor reference=firmware(pid,"US","9.9.9.9");
            if(ModelRegistry.resolve(reference).layer!=ModelRegistry.Layer.OEM_GROUP)continue;
            byte[] d=data();d[25]=25;d[20]=(byte)0x88;
            if(ModelRegistry.any(reference,ModelRegistry.M))d[5]=1;
            for(String key:keys){
                CapabilityResolver.Decision base=decide(key,reference,d);
                if(base.available())mapped++;
                for(String area:areas)for(int flag:new int[]{0,1}){
                    DeviceDescriptor dev=firmware(pid,area,"9.9.9.9");
                    byte[] sample=d.clone();sample[35]=(byte)flag;
                    CapabilityResolver.Decision x=decide(key,dev,sample);
                    String context=pid+" "+key+" "+area+" flag="+flag;
                    check(x.reason==base.reason&&x.support==base.support&&x.reason!=CapabilityResolver.Reason.REGION_RULE,"Same-model regional availability: "+context);
                    check(Arrays.equals(x.values(),base.values()),"Same-model regional choices: "+context);
                    if(!base.available())continue;
                    check(x.variant.id.equals(base.variant.id)&&x.preconditions.equals(base.preconditions),"Regional union retains exact encoding and guards: "+context);
                    StatusFields s=status(sample);
                    for(int value:base.values()){
                        MutationGate.Request request=new MutationGate.Request(MutationGate.Kind.SET,key,value,MutationGate.Origin.USER);
                        check(MutationGate.check(request,dev,s,CapabilityResolverTest.parked()).available(),"Same-model union admitted: "+context+" "+value);
                        check(Arrays.equals(x.variant.encode(value),base.variant.encode(value)),"Regional wire bytes stable: "+context+" "+value);
                    }
                    int first=base.values()[0];
                    MutationGate.Request request=new MutationGate.Request(MutationGate.Kind.SET,key,first,MutationGate.Origin.USER);
                    CapabilityResolver.Runtime rt=CapabilityResolverTest.parked();rt.stationary=false;
                    check(MutationGate.check(request,dev,s,rt).reason==CapabilityResolver.Reason.MOVING,"Motion guard retained: "+context);
                    rt=CapabilityResolverTest.parked();rt.motionSeen=false;rt.stationary=false;
                    check(MutationGate.check(request,dev,s,rt).reason==CapabilityResolver.Reason.NO_MOTION_REPORT,"Motion presence retained: "+context);
                    rt=CapabilityResolverTest.parked();rt.nowMs=SEEN+7001;
                    check(MutationGate.check(request,dev,s,rt).reason==CapabilityResolver.Reason.STATUS_STALE,"Fresh status retained: "+context);
                    rt=CapabilityResolverTest.parked();rt.discovery=true;
                    check(MutationGate.check(request,dev,s,rt).reason==CapabilityResolver.Reason.DISCOVERY_ACTIVE,"Discovery gate retained: "+context);
                    check(decide(key,dev,Arrays.copyOf(sample,x.variant.offset)).reason==CapabilityResolver.Reason.FIELD_ABSENT,"Actual field presence retained: "+context);
                    byte[] unknown=sample.clone();unknown[x.variant.offset]=(byte)0xfe;
                    check(decide(key,dev,unknown).reason==CapabilityResolver.Reason.VALUE_UNKNOWN,"Current value domain retained: "+context);
                }
            }
        }
        check(mapped>100,"Matrix covers mapped riding controls across the OEM model catalogue");
        // Regional policy cannot waive meter version or full model ID evidence.
        for(String area:areas)for(String key:new String[]{"maximum_speed","start_speed"}){
            for(String pid:new String[]{"24011","27041"}){
                check(decide(key,firmware(pid,area,null),data()).reason==CapabilityResolver.Reason.FIRMWARE_UNKNOWN,"Unknown firmware still refused: "+key+" "+area);
                check(decide(key,firmware(pid,area,"2.0.1.1"),data()).reason==CapabilityResolver.Reason.FIRMWARE_OLD,"Old firmware still refused: "+key+" "+area);
            }
            String prefix=key.equals("maximum_speed")?"2543":"2314";
            check(decide(key,firmware(prefix,area,"9.9.9.9"),data()).reason==CapabilityResolver.Reason.FULL_PID_NEEDED,"Full PID still required: "+key+" "+area);
        }
        for(String area:areas){
            DeviceDescriptor ut5=firmware("25381",area,"9.9.9.9");
            byte[] d=data();d[25]=25;
            values("maximum_speed",ut5,d,ints(25,32,40,50));
            CapabilityResolver.Decision start=decide("start_speed",ut5,d);
            check(start.values().length==0&&start.variant.min==0&&start.variant.max==5&&start.variant.step==1,
                "UT5 retains the continuous 0–5 slider, not the OEM choice sheet: "+area);
            check(decide("speed_limit",ut5,d).variant.lab,"UT5 experimental speed cap unchanged: "+area);
        }
    }

    /** Quick Access tiles: the decided variant labels the value and decides direct toggles; the UT5 MAX is unchanged. */
    private static void quickAccessFollowsDecision() {
        byte[] d=data();
        byte[] unset=d.clone();unset[31]=0;
        check("Default (80%)".equals(decide("charge_limit",model("24431"),unset).oemLabel(0)),"Unset charge limit tile label");
        byte[] on=d.clone();on[5]=1;
        check("On".equals(decide("energy_recovery",model("25041"),on).oemLabel(1)),"On/off energy recovery tile label");
        check(decide("maximum_speed",model("24431"),d).oemLabel(25)==null,"Speeds keep the rider's units");
        DeviceDescriptor ut5=ModelRegistryTest.serial("Q2538ZZ9US7777777");
        for(ScooterCommands.Feature f:ScooterCommands.catalog()){
            CapabilityResolver.Decision x=decide(f.key,ut5,d);
            QuickAccessActions.Entry e=QuickAccessActions.find("feature:"+f.key);
            check(x.oemLabel(1)==null&&e.directToggle(x)==e.directToggle(),"UT5 MAX tile unchanged: "+f.key);
            check(!f.sensitive||!e.directToggle(decide(f.key,model("24431"),d)),"Sensitive never direct on another model: "+f.key);
        }
        check(QuickAccessActions.find("feature:long_range_mode").directToggle(decide("long_range_mode",model("27451"),d)),"Long range toggles on 2745");
        check(QuickAccessActions.find("feature:dual_drive").directToggle(decide("dual_drive",model("24431"),d)),"Dual motor toggles on 2443");
        check(QuickAccessActions.find("feature:overspeed_control").directToggle(decide("overspeed_control",model("27011"),d)),"Overspeed toggles where mapped");
        check(!QuickAccessActions.find("feature:energy_recovery").directToggle(decide("energy_recovery",model("25041"),on)),"A choice stays a choice");
    }

    /** Group M's energy recovery: on/off at offset 5 (== 1), unlocked and parked; turning it off shows the official app's caution. */
    private static void batch2Energy() {
        byte[] on=data();on[5]=1;byte[] offNow=data();offNow[5]=0;
        for(String pid:new String[]{"25041","25051","25061"}){
            CapabilityResolver.Decision x=decide("energy_recovery",model(pid),on);
            check(x.available()&&x.variant==ModelRegistry.OEM_ENERGY_BINARY&&x.current()==1&&Arrays.equals(x.values(),ints(0,1))&&x.label(1).equals("On"),"M energy on/off "+pid);
            check(decide("energy_recovery",model(pid),offNow).current()==0&&decide("energy_recovery",model(pid),data()).reason==CapabilityResolver.Reason.VALUE_UNKNOWN,"Levels read as unknown on "+pid);
            byte[] locked=on.clone();locked[2]=1;check(decide("energy_recovery",model(pid),locked).reason==CapabilityResolver.Reason.LOCKED,"Needs the scooter unlocked");
            check(decide("energy_recovery",model(pid),Arrays.copyOf(on,5)).reason==CapabilityResolver.Reason.FIELD_ABSENT,"Truncated before offset 5");
            check(ModelRegistry.caution(x,0)!=null&&ModelRegistry.caution(x,1)==null,"Off shows a caution");
        }
        check(!decide("energy_recovery",model("24431"),on).allows(1)&&!decide("energy_recovery",ModelRegistryTest.serial("Q2538ZZ9US7777777"),on).allows(1),"Only group M offers 1");
        check(ModelRegistry.caution(decide("energy_recovery",model("25851"),data()),0)==null,"Levels need no caution");
    }

    /** Frozen bytes for the speed family: every table value, the cap's Off/enable forms, start speeds and M energy. */
    private static void batch2Frames() throws Exception {
        String[][] frames={
            {"0x6e","25","55aa006e02011989fefd"},
            {"0x6e","30","55aa006e02011e8efefd"},
            {"0x6e","32","55aa006e02012090fefd"},
            {"0x6e","35","55aa006e02012393fefd"},
            {"0x6e","40","55aa006e02012898fefd"},
            {"0x6e","45","55aa006e02012d9dfefd"},
            {"0x6e","50","55aa006e020132a2fefd"},
            {"0x6e","55","55aa006e020137a7fefd"},
            {"0x6e","60","55aa006e02013cacfefd"},
            {"0x6e","65","55aa006e020141b1fefd"},
            {"0x6e","70","55aa006e020146b6fefd"},
            {"0x6b","0","55aa006b01006bfefd"},
            {"0x6b","8","55aa006b0188f3fefd"},
            {"0x6b","15","55aa006b018ffafefd"},
            {"0x6b","16","55aa006b0190fbfefd"},
            {"0x6b","20","55aa006b0194fffefd"},
            {"0x6b","25","55aa006b019904fefd"},
            {"0x6b","32","55aa006b01a00bfefd"},
            {"0x6a","0","55aa006a01006afefd"},
            {"0x6a","1","55aa006a01016bfefd"},
            {"0x6a","2","55aa006a01026cfefd"},
            {"0x6a","3","55aa006a01036dfefd"},
            {"0x6a","4","55aa006a01046efefd"},
            {"0x6a","5","55aa006a01056ffefd"},
            {"0x53","0","55aa0053010053fefd"},
            {"0x53","1","55aa0053010154fefd"},
        };
        java.util.Map<String,String> hex=new java.util.HashMap<>();for(String[] f:frames)hex.put(f[0]+"/"+f[1],f[2]);
        for(String name:new String[]{"SPEED_25_32","SPEED_25_50","SPEED_25_50_45","SPEED_25_60","SPEED_25_70","SPEED_25_40_STEP5","SPEED_25_50_STEP5","SPEED_25_65_STEP5","SPEED_25_45",
                "LIMIT_2782","LIMIT_8_16","LIMIT_8_20","LIMIT_8_32","START_0_5","START_3_5","OEM_ENERGY_BINARY"}){
            FeatureVariant v=(FeatureVariant)ModelRegistry.class.getDeclaredField(name).get(null);
            for(int value:v.values){
                String expected=hex.get(String.format("0x%02x",v.cmd)+"/"+value);
                if(expected!=null)check(Protocol.hex(v.encode(value)).equals(expected),"Frame "+v.id+"="+value);
                else check(Protocol.hex(v.encode(value)).startsWith(String.format("55aa00%02x",v.cmd)),"Frame shape "+v.id+"="+value);
                if(ScooterCommands.find(v.feature).allows(value))check(Arrays.equals(v.encode(value),ScooterCommands.set(v.feature,value)),"Same wire as the UT5 MAX "+v.id+"="+value);
            }
            for(int value=-1;value<=256;value++)if(!v.allows(value)){boolean refused=false;try{v.encode(value);}catch(IllegalArgumentException expected){refused=true;}check(refused,"Never encoded "+v.id+"="+value);}
        }
        int covered=0;for(String[] f:frames)for(String name:new String[]{"SPEED_25_70","SPEED_25_65_STEP5","LIMIT_8_32","START_0_5","OEM_ENERGY_BINARY"}){
            FeatureVariant v=(FeatureVariant)ModelRegistry.class.getDeclaredField(name).get(null);
            if(String.format("0x%02x",v.cmd).equals(f[0])&&v.allows(Integer.parseInt(f[1]))){check(Protocol.hex(v.encode(Integer.parseInt(f[1]))).equals(f[2]),"Fixture "+f[0]+" "+f[1]);covered++;}
        }
        check(covered>=frames.length,"Every frozen frame is produced by a variant");
    }


    private static final String[] ZONES={"line_light","line_pattern","line_color","line_boost_color","star_light","star_pattern","star_color","star_boost_color"};

    /** Line and star zones: 2547 two colours, 2585 six; other models' ambient lights stay unmapped; region is a note only. */
    private static void batch3Zones() {
        byte[] d=data(); // line on + Follow headlights (0x81), line colours std 1 / boost 0, star on + Breathing (0x82), star std 0 / boost 1
        for(String key:ZONES){
            for(String pid:new String[]{"25471","25851"}){
                CapabilityResolver.Decision x=decide(key,model(pid),d);
                check(reason(key,model(pid),d).equals(A)&&!x.variant.parked&&!x.preconditions.contains(CapabilityResolver.Precondition.UNLOCKED),"Zone "+key+" on "+pid);
                boolean colour=key.endsWith("_color");
                check(Arrays.equals(x.values(),colour&&pid.equals("25851")?span(0,5):colour?ints(0,1):key.endsWith("_pattern")?ints(0,1,2):ints(0,1)),"Zone choices "+key+" "+pid);
                check(x.oemNote.isEmpty()&&!decide(key,model(pid,"DE"),d).oemNote.isEmpty()&&decide(key,model(pid,"DE"),d).available()&&decide(key,model(pid,"AT"),d).available()
                    &&!decide(key,model(pid,"AT"),d).oemNote.isEmpty()&&decide(key,model(pid,"ZZ"),d).oemNote.isEmpty(),"Region hiding deferred with a note "+key);
                CapabilityResolver.Runtime moving=CapabilityResolverTest.parked();moving.stationary=false;
                check(CapabilityResolver.decide(key,model(pid),status(d),moving).available(),"Lights need no parked state "+key);
            }
            for(String pid:new String[]{"24431","26191","24011","23451","27141","25041","25061","24169","22131"})
                check(reason(key,model(pid),d).equals("MODEL_NOT_MAPPED"),"No zone lights mapped on "+pid+" "+key);
            check(decide(key,ModelRegistryTest.serial("Q2538ZZ9US7777777"),d).variant.id.equals(key+".ut5"),"UT5 MAX keeps "+key+".ut5");
        }
        Object[][] current={{"line_light",1},{"line_pattern",1},{"line_color",1},{"line_boost_color",0},{"star_light",1},{"star_pattern",2},{"star_color",0},{"star_boost_color",1}};
        for(Object[] c:current)for(String pid:new String[]{"25471","25851"})check(Integer.valueOf((Integer)c[1]).equals(decide((String)c[0],model(pid),d).current()),"Zone readback "+c[0]+" "+pid);
        byte[] green=d.clone();green[16]=0x53; // standard 3 (green), boost 5 (rainbow)
        check(decide("line_color",model("25851"),green).current()==3&&decide("line_color",model("25851"),green).label(3).equals("Green")
            &&decide("line_boost_color",model("25851"),green).label(5).equals("Rainbow"),"Six colours on 2585");
        check(decide("line_color",model("25471"),green).reason==CapabilityResolver.Reason.VALUE_UNKNOWN&&decide("line_boost_color",model("25471"),green).reason==CapabilityResolver.Reason.VALUE_UNKNOWN,"2547 has two colours");
        byte[] six=d.clone();six[18]=0x66;check(decide("star_color",model("25851"),six).reason==CapabilityResolver.Reason.VALUE_UNKNOWN,"Colour 6 is outside every table");
        byte[] pattern=d.clone();pattern[10]=(byte)0x83;check(decide("line_pattern",model("25851"),pattern).reason==CapabilityResolver.Reason.VALUE_UNKNOWN&&decide("line_light",model("25851"),pattern).current()==1,"Pattern 3 is unknown; the on bit still reads");
        int[][] cuts={{10,0},{16,2},{17,4},{18,6}};
        for(int[] c:cuts){byte[] cut=Arrays.copyOf(d,c[0]);check(decide(ZONES[c[1]],model("25851"),cut).reason==CapabilityResolver.Reason.FIELD_ABSENT
            &&decide(ZONES[c[1]+1],model("25851"),cut).reason==CapabilityResolver.Reason.FIELD_ABSENT,"Truncated before offset "+c[0]);}
        check(MutationGate.check(new MutationGate.Request(MutationGate.Kind.SET,"line_color",5,MutationGate.Origin.USER),model("25851"),status(d),CapabilityResolverTest.parked()).available()
            &&MutationGate.check(new MutationGate.Request(MutationGate.Kind.SET,"line_color",5,MutationGate.Origin.USER),model("25471"),status(d),CapabilityResolverTest.parked()).reason==CapabilityResolver.Reason.MODEL_EXCLUDED
            &&!MutationGate.check(new MutationGate.Request(MutationGate.Kind.SET,"line_color",2,MutationGate.Origin.USER),ModelRegistryTest.serial("Q2538ZZ9US7777777"),status(d),CapabilityResolverTest.parked()).available(),"Colour domain per model");
    }

    /** Group S light controls: three keys with no UT5 MAX counterpart, outside the catalogue, raw 0/1/2 readback. */
    private static void batch3GroupS() {
        String[] keys={"ambient_light_mode","logo_light","daytime_light"};int[] offsets={10,23,24};
        check(ScooterCommands.oemOnly().size()==3,"Three OEM-only keys");
        for(ScooterCommands.Feature f:ScooterCommands.catalog())check(!ScooterCommands.oemOnly(f.key),"OEM-only keys stay out of the UT5 MAX catalogue: "+f.key);
        for(int i=0;i<keys.length;i++){
            String key=keys[i];ScooterCommands.Feature f=ScooterCommands.find(key);
            check(ScooterCommands.oemOnly(key)&&!f.experimental&&f.payloadOffset==offsets[i]&&Arrays.equals(f.optionValues,ints(0,1,2)),"OEM-only feature "+key);
            byte[] d=data();d[10]=2;d[23]=1;d[24]=0;
            for(String pid:new String[]{"24169","24431","25291"}){
                CapabilityResolver.Decision x=decide(key,model(pid),d);
                check(reason(key,model(pid),d).equals(A)&&Arrays.equals(x.values(),ints(0,1,2))&&x.variant.offset==offsets[i]&&x.label(2).equals("Follow headlight"),"S light "+key+" on "+pid);
                check(x.current()==(i==0?2:i==1?1:0),"Raw readback "+key);
                check(decide(key,model(pid,"DE"),d).available()&&!decide(key,model(pid,"DE"),d).oemNote.isEmpty()&&x.oemNote.isEmpty(),"Region note only "+key);
            }
            byte[] three=data();three[offsets[i]]=3;check(decide(key,model("24431"),three).reason==CapabilityResolver.Reason.VALUE_UNKNOWN,"3 is unknown "+key);
            check(decide(key,model("24431"),Arrays.copyOf(data(),offsets[i])).reason==CapabilityResolver.Reason.FIELD_ABSENT,"Truncated before offset "+offsets[i]);
            for(String pid:new String[]{"25471","25851","23451","27141","24011","25041","26191"})check(reason(key,model(pid),d).equals("MODEL_NOT_MAPPED"),"Not mapped on "+pid+" "+key);
            CapabilityResolver.Decision ut5=decide(key,ModelRegistryTest.serial("Q2538ZZ9US7777777"),d);
            check(ut5.support==CapabilityResolver.Support.INCOMPATIBLE&&ut5.reason==CapabilityResolver.Reason.MODEL_EXCLUDED&&ut5.variant==null,"Not on the UT5 MAX "+key);
            check(decide(key,ModelRegistryTest.serial("Q2443ZZ9US7777777"),d).reason==CapabilityResolver.Reason.MODEL_NOT_IDENTIFIED,"Serial-only S stays unidentified "+key);
        }
        check(CapabilityResolver.all(ModelRegistryTest.serial("Q2538ZZ9US7777777"),status(data()),CapabilityResolverTest.parked()).size()==ScooterCommands.catalog().size()+1,"UT5 MAX decision list unchanged");
    }

    /** Frozen bytes for every lighting value (two-colour variants are the first two six-colour values). */
    private static void batch3Frames() throws Exception {
        String[][] frames={
            {"LINE_LIGHT","0","55aa006d0304000073fefd"},
            {"LINE_LIGHT","1","55aa006d0304000174fefd"},
            {"LINE_PATTERN","0","55aa006d0304010074fefd"},
            {"LINE_PATTERN","1","55aa006d0304010175fefd"},
            {"LINE_PATTERN","2","55aa006d0304010276fefd"},
            {"LINE_COLOR_SIX","0","55aa006d0304020075fefd"},
            {"LINE_COLOR_SIX","1","55aa006d0304020176fefd"},
            {"LINE_COLOR_SIX","2","55aa006d0304020277fefd"},
            {"LINE_COLOR_SIX","3","55aa006d0304020378fefd"},
            {"LINE_COLOR_SIX","4","55aa006d0304020479fefd"},
            {"LINE_COLOR_SIX","5","55aa006d030402057afefd"},
            {"LINE_BOOST_SIX","0","55aa006d0304030076fefd"},
            {"LINE_BOOST_SIX","1","55aa006d0304030177fefd"},
            {"LINE_BOOST_SIX","2","55aa006d0304030278fefd"},
            {"LINE_BOOST_SIX","3","55aa006d0304030379fefd"},
            {"LINE_BOOST_SIX","4","55aa006d030403047afefd"},
            {"LINE_BOOST_SIX","5","55aa006d030403057bfefd"},
            {"STAR_LIGHT","0","55aa006d0305000074fefd"},
            {"STAR_LIGHT","1","55aa006d0305000175fefd"},
            {"STAR_PATTERN","0","55aa006d0305010075fefd"},
            {"STAR_PATTERN","1","55aa006d0305010176fefd"},
            {"STAR_PATTERN","2","55aa006d0305010277fefd"},
            {"STAR_COLOR_SIX","0","55aa006d0305020076fefd"},
            {"STAR_COLOR_SIX","1","55aa006d0305020177fefd"},
            {"STAR_COLOR_SIX","2","55aa006d0305020278fefd"},
            {"STAR_COLOR_SIX","3","55aa006d0305020379fefd"},
            {"STAR_COLOR_SIX","4","55aa006d030502047afefd"},
            {"STAR_COLOR_SIX","5","55aa006d030502057bfefd"},
            {"STAR_BOOST_SIX","0","55aa006d0305030077fefd"},
            {"STAR_BOOST_SIX","1","55aa006d0305030178fefd"},
            {"STAR_BOOST_SIX","2","55aa006d0305030279fefd"},
            {"STAR_BOOST_SIX","3","55aa006d030503037afefd"},
            {"STAR_BOOST_SIX","4","55aa006d030503047bfefd"},
            {"STAR_BOOST_SIX","5","55aa006d030503057cfefd"},
            {"S_AMBIENT","0","55aa006d0201006ffefd"},
            {"S_AMBIENT","1","55aa006d02010170fefd"},
            {"S_AMBIENT","2","55aa006d02010271fefd"},
            {"S_LOGO","0","55aa006d02020070fefd"},
            {"S_LOGO","1","55aa006d02020171fefd"},
            {"S_LOGO","2","55aa006d02020272fefd"},
            {"S_DAYTIME","0","55aa006d02030071fefd"},
            {"S_DAYTIME","1","55aa006d02030172fefd"},
            {"S_DAYTIME","2","55aa006d02030273fefd"},
        };
        for(String[] f:frames){
            FeatureVariant v=(FeatureVariant)ModelRegistry.class.getDeclaredField(f[0]).get(null);int value=Integer.parseInt(f[1]);
            check(Protocol.hex(v.encode(value)).equals(f[2]),"Frame "+v.id+"="+value+" "+Protocol.hex(v.encode(value)));
            if(ScooterCommands.find(v.feature).allows(value))check(Arrays.equals(v.encode(value),ScooterCommands.set(v.feature,value)),"Same wire as the catalogue encoder "+v.id+"="+value);
            String two=f[0].replace("_SIX","_TWO");
            if(!two.equals(f[0])&&value<2)check(Arrays.equals(((FeatureVariant)ModelRegistry.class.getDeclaredField(two).get(null)).encode(value),v.encode(value)),"Two-colour bytes "+two);
        }
        for(String name:new String[]{"LINE_COLOR_TWO","STAR_BOOST_TWO","LINE_COLOR_SIX","S_AMBIENT","S_LOGO","S_DAYTIME","LINE_PATTERN"}){
            FeatureVariant v=(FeatureVariant)ModelRegistry.class.getDeclaredField(name).get(null);
            for(int value=-1;value<=256;value++)if(!v.allows(value)){boolean refused=false;try{v.encode(value);}catch(IllegalArgumentException expected){refused=true;}check(refused,"Never encoded "+v.id+"="+value);}
        }
    }

    static void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}
}
