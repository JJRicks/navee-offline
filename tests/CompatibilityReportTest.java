package local.navee.offlinelock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The report whitelist: synthetic secrets never escape, keys stay known, outcomes stay distinct and imports are revalidated. */
public final class CompatibilityReportTest {
    private static int assertions;
    private static final long T0=80000;
    private static final String SERIAL="Q2443ZZ9US7777777",MAC="C0:FF:EE:12:34:56",IDENTITY="A1B2C3D4E5F6",NAME="Alice's scooter";
    public static void main(String[] args) {
        privacy(); keys(); outcomes(); fields(); device(); tests(); imports(); ids(); authVariants();
        System.out.println("CompatibilityReport tests passed: "+assertions+" assertions");
    }

    /** A status frame seeded with values that must not appear: start-charge time at 27–30 and offset 41 = 0x5A. */
    private static StatusFields seededStatus(ModelRegistry.Layer layer){
        byte[] d=CapabilityResolverTest.full();d[27]=(byte)0xDE;d[28]=(byte)0xAD;d[29]=(byte)0xC0;d[30]=(byte)0xDE;d[41]=0x5A;d[40]=0x33;d[0]=0x44;d[14]=0x55;
        return CapabilityResolverTest.status(d,T0);
    }
    private static DiscoveryCoordinator finishedCheck(){
        DiscoveryCoordinator d=new DiscoveryCoordinator(T0);long t=T0;
        while(!d.finished(t)){
            Integer cmd=d.nextRead(t);
            if(cmd!=null){
                if(cmd==0x7b)d.onFrame(cmd,3,0,t+30);          // rejected
                else if(cmd==0x75){}                             // silent probe
                else d.onFrame(cmd,0,cmd==0x70?44:cmd==0x74?17:cmd==0x71?4:12,t+30);
            }
            if(t==T0+500)d.onFrame(0x90,0,18,t);
            t+=100;
        }
        return d;
    }
    private static CompatibilityReport build(DeviceDescriptor descriptor,Random random){
        ModelRegistry.Resolution r=ModelRegistry.resolve(descriptor);
        StatusFields s=seededStatus(r.layer);
        DiscoveryCoordinator d=finishedCheck();
        // A hand-made 0x7A row (PIN digits 4321) is offered to the builder; the whitelist must drop it.
        List<DiscoveryCoordinator.Read> reads=new ArrayList<>(d.reads());
        DiscoveryCoordinator.Read pin=new DiscoveryCoordinator.Read(0x7a,ReadPolicy.Cls.CORE);pin.outcome=DiscoveryCoordinator.Outcome.READ_SUPPORTED;pin.status=0;pin.length=4321;pin.attempts=1;pin.firstMs=T0+4321;
        reads.add(pin);
        CapabilityResolver.Runtime rt=CapabilityResolverTest.parked();rt.nowMs=T0+1000;
        List<TestJournal.Entry> tests=Arrays.asList(
            entry("turn_sound","turn_sound.oem",0,1,Arrays.asList("setting_confirmed","effect_observed","restoration_confirmed"),Arrays.asList(120L,640L,900L),"observed","confirmed"),
            entry("charge_start","x",1,2,null,null,"not_checked","confirmed"),     // time: never reported
            entry("pin","x",4,3,null,null,"not_checked","confirmed"),              // PIN: never reported
            entry("cruise_control","cruise_control.oem",0,1,null,null,"observed","confirmed")); // riding control: never a guided test
        return new CompatibilityReport.Builder("1.3.1",random).device(descriptor,r,"aes").reads(reads,d.passive(),T0).fields(s)
            .decisions(CapabilityResolver.all(descriptor,s,rt)).tests(tests).build();
    }
    /** A journal entry seeded with a profile ID, test ID and wall times that must never reach a report. */
    static TestJournal.Entry entry(String feature,String variant,int baseline,int target,List<String> outcomes,List<Long> elapsed,String effect,String restoration){
        TestJournal.Entry e=new TestJournal.Entry();
        e.testId="deadbeefcafebabe";e.profileId="p7777777";e.feature=feature;e.variantId=variant;e.pidPrefix="2443";e.pidSource="advertised";e.meterFw="2.0.3.1";
        e.registryVersion=ModelRegistry.VERSION;e.baseline=baseline;e.target=target;e.phase="DONE";e.effect=effect;e.restoration=restoration;
        if(outcomes!=null)e.outcomes=new ArrayList<>(outcomes);if(elapsed!=null)e.elapsedMs=new ArrayList<>(elapsed);
        e.createdWall=1700000000000L;e.updatedWall=1700000000001L;e.resolved=true;return e;
    }
    private static DeviceDescriptor fixture(){
        DeviceDescriptor.Firmware fw=new DeviceDescriptor.Firmware("2.0.3.1","1.1.0","BMS7",null,null,T0);
        return DeviceDescriptor.of(SERIAL,"24431",1700000000000L,fw,true,DeviceDescriptor.Transport.STANDARD,0);
    }

    private static void privacy() {
        for(DeviceDescriptor descriptor:new DeviceDescriptor[]{fixture(),DeviceDescriptor.of("Q2538ZZ9US7777777","",0,null,false,DeviceDescriptor.Transport.STANDARD,1)}){
            CompatibilityReport report=build(descriptor,new Random(1));
            for(String text:new String[]{report.json(),report.summary()}){
                String low=text.toLowerCase(Locale.ROOT),compact=low.replaceAll("[^0-9a-z]","");
                for(String secret:new String[]{SERIAL,"7777777","zz9us","q2443",MAC,"c0ffee123456","563412eeffc0","56:34:12:ee:ff:c0",IDENTITY,NAME,"alice",
                        "deadbeef","cafebabe","dead c0de","dec0de","de ad c0 de","4321","1700000000000"})
                {String plain=secret.toLowerCase(Locale.ROOT),bare=plain.replaceAll("[^0-9a-z]","");
                    // Also with separators removed (MAC/identity written as hex); short digit runs are checked only as written.
                    check(!low.contains(plain)&&(bare.length()<8||!compact.contains(bare)),"Secret escaped: "+secret);}
                check(!low.contains("0x7a")&&!low.contains("0x63")&&!low.contains("0x30")&&!low.contains("0x31"),"No PIN or auth opcodes");
            }
            // Structural: offsets 27–30 never appear; presence-only offsets carry no value.
            String json=report.json();
            for(int o=27;o<=30;o++)check(!json.contains("\"offset\": "+o+",")&&!json.contains("\"offset\": "+o+"\n"),"Time-of-day offset "+o+" excluded");
            for(String name:new String[]{"binding_status","night_mode","osa_flag_unmapped","unread_41"}){
                int at=json.indexOf("\"name\": \""+name+"\"");check(at>0,"Presence row "+name);
                String row=json.substring(at,json.indexOf('}',at));check(row.contains("\"present\": true")&&!row.contains("\"value\""),"Presence only: "+name);
            }
        }
    }
    private static void keys() {
        CompatibilityReport report=build(fixture(),new Random(2));
        Set<String> keys=new TreeSet<>();Matcher m=Pattern.compile("\"([^\"\\\\]*)\"\\s*:").matcher(report.json());while(m.find())keys.add(m.group(1));
        check(!keys.isEmpty()&&CompatibilityReport.KEYS.containsAll(keys),"Key set within whitelist: "+keys);
        // Every string value is from a fixed vocabulary or pattern: letters, digits, '.', '_', ':' only.
        Matcher v=Pattern.compile(":\\s*\"([^\"]*)\"").matcher(report.json());
        while(v.find())check(v.group(1).matches("[0-9A-Za-z._+-]{1,48}"),"Bounded string value: "+v.group(1));
    }
    private static void outcomes() {
        CompatibilityReport report=build(fixture(),new Random(3));String json=report.json(),summary=report.summary();
        check(json.contains("\"outcome\": \"read_supported\"")&&json.contains("\"outcome\": \"rejected\"")&&json.contains("\"outcome\": \"no_response\""),"Read outcomes distinct");
        check(summary.contains("0x7B optional: Rejected, code 3")&&summary.contains("0x75 probe: No response (1 attempt)")&&summary.contains("0x70 core: Read supported · 44 bytes"),"Summary read wording\n"+summary);
        check(summary.contains("0x90 yes (18 bytes)")&&summary.contains("0x91 not seen")&&summary.contains("absence is not proof"),"Passive reports and absence");
        // Interrupted is not "no response".
        DiscoveryCoordinator d=new DiscoveryCoordinator(T0);d.nextRead(T0);d.onDisconnect(T0+10);
        CompatibilityReport cut=new CompatibilityReport.Builder("1.3.1",new Random(4)).reads(d.reads(),d.passive(),T0).build();
        check(cut.json().contains("\"outcome\": \"interrupted\"")&&!cut.json().contains("no_response")&&cut.summary().contains("0x74 core: Interrupted"),"Interruption is its own outcome");
        // Late replies are flagged.
        DiscoveryCoordinator l=new DiscoveryCoordinator(T0);l.nextRead(T0);l.onFrame(0x74,0,17,T0+2600);
        CompatibilityReport late=new CompatibilityReport.Builder("1.3.1",new Random(4)).reads(l.reads(),l.passive(),T0).build();
        check(late.json().contains("\"late\": true")&&late.json().contains("\"first_ms\": 2600")&&late.summary().contains("(late)"),"Late reply flagged with relative time");
        // A status-0 reply with no data is its own outcome, never "read supported".
        DiscoveryCoordinator e=new DiscoveryCoordinator(T0);e.nextRead(T0);e.onFrame(0x74,0,17,T0+10);e.nextRead(T0+300);e.onFrame(0x70,0,43,T0+310);e.nextRead(T0+600);e.onFrame(0x73,0,0,T0+610);
        CompatibilityReport empty=new CompatibilityReport.Builder("1.3.1",new Random(4)).reads(e.reads(),e.passive(),T0).build();
        check(empty.json().contains("\"outcome\": \"empty_reply\"")&&empty.summary().contains("0x73 core: Replied with no data (not proof of support)")&&!empty.summary().contains("0x73 core: Read supported"),"Empty reply is not read supported\n"+empty.summary());
        check(CompatibilityReport.parse(empty.json()).json().contains("\"outcome\": \"empty_reply\""),"Empty reply survives import");
    }
    private static void fields() {
        CompatibilityReport ut5=build(DeviceDescriptor.of("Q2538ZZ9US7777777","",0,null,false,DeviceDescriptor.Transport.STANDARD,0),new Random(5));
        CompatibilityReport other=build(fixture(),new Random(5));
        check(ut5.json().contains("\"ambient_light\"")&&!ut5.json().contains("lighting_byte_10"),"UT5 lighting names");
        check(other.json().contains("\"lighting_byte_10\"")&&other.json().contains("\"lighting_byte_18\"")&&!other.json().contains("eco_light"),"Other models get raw lighting names");
        check(other.json().contains("\"status_length\": 44"),"0x70 length");
        check(CompatibilityReport.fieldLabel(40,ModelRegistry.Layer.UT5_MAX).equals("OSA flag (unmapped)")&&CompatibilityReport.fieldLabel(16,ModelRegistry.Layer.SHARED).equals("Lighting byte 16 (raw)"),"Panel labels");
        for(int o=0;o<=42;o++){
            boolean timeOfDay=o>=27&&o<=30;
            check(timeOfDay==(CompatibilityReport.fieldName(o,ModelRegistry.Layer.SHARED)==null),"Name table "+o);
            check(!(CompatibilityReport.valueOffset(o)&&CompatibilityReport.presenceOffset(o))&&(timeOfDay!=(CompatibilityReport.valueOffset(o)||CompatibilityReport.presenceOffset(o))),"Offset class "+o);
        }
        // An absent byte is "present": false, never 0.
        byte[] shortFrame=new byte[20];shortFrame[2]=1;
        StatusFields s=CapabilityResolverTest.status(shortFrame,T0);
        CompatibilityReport r=new CompatibilityReport.Builder("1.3.1",new Random(6)).fields(s).build();
        int at=r.json().indexOf("\"name\": \"maximum_speed_setting\"");String row=r.json().substring(at,r.json().indexOf('}',at));
        check(row.contains("\"present\": false")&&!row.contains("value"),"Absent byte has no value");
    }
    private static void device() {
        String json=build(fixture(),new Random(7)).json();
        check(json.contains("\"pid\": \"24431\"")&&json.contains("\"pid_prefix\": \"2443\"")&&json.contains("\"pid_source\": \"advertised\"")&&json.contains("\"pid_digits\": 5"),"Full advertised PID (D6)");
        check(json.contains("\"region\": \"US\"")&&json.contains("\"region_source\": \"serial\"")&&json.contains("\"sku\": \"USA\""),"Derived region only");
        check(json.contains("\"meter\": \"2.0.3.1\"")&&json.contains("\"bms\": \"BMS7\"")&&!json.contains("\"screen\""),"Firmware this session");
        check(json.contains("\"auth\": \"aes\"")&&json.contains("\"role\": \"owner\"")&&json.contains("\"layer\": \"oem_group\""),"Auth, role, layer");
        String serialOnly=build(DeviceDescriptor.of("Q2538ZZ9US7777777","",0,null,false,DeviceDescriptor.Transport.STANDARD,1),new Random(7)).json();
        check(!serialOnly.contains("\"pid\":")&&serialOnly.contains("\"pid_source\": \"serial\"")&&serialOnly.contains("\"layer\": \"ut5_max\"")&&serialOnly.contains("\"role\": \"shared\""),"Serial-only PID has no full pid");
        check(!serialOnly.contains("\"firmware\""),"No firmware unless read this session");
        String mismatch=build(DeviceDescriptor.of("Q2538ZZ9US7777777","26201",1,null,false,DeviceDescriptor.Transport.STANDARD,0),new Random(7)).json();
        check(mismatch.contains("\"pid_conflict\": true")&&mismatch.contains("\"layer\": \"ut5_max\""),"D1: 2538 keeps UT5 layer, mismatch flagged");
        check(build(fixture(),new Random(7)).summary().contains("Model ID 24431 (advertised)"),"Summary model line");
    }
    private static void tests() {
        CompatibilityReport report=build(fixture(),new Random(8));String json=report.json(),summary=report.summary();
        check(json.contains("\"variant\": \"turn_sound.oem\""),"Test entry kept");
        check(!json.contains("\"variant\": \"x\"")&&!json.contains("cruise_control.oem"),"Time, PIN and riding tests dropped");
        check(json.contains("\"packet_evidence\"")&&json.contains("\"owner_observation\"")&&json.contains("\"outcomes\": [\"setting_confirmed\", \"restoration_confirmed\"]")
            &&json.contains("\"elapsed_ms\": [120, 900]")&&json.contains("\"effect\": \"observed\""),"Packet evidence and owner observation are separate\n"+json);
        check(summary.contains("Packet evidence: Setting confirmed, Restoration confirmed")&&summary.contains("Owner observation: Effect observed")&&summary.contains("Restoration: Restoration confirmed"),"Summary keeps them apart\n"+summary);
        check(!json.contains("p7777777")&&!json.contains("deadbeefcafebabe")&&!json.contains("1700000000000")&&!json.contains("\"phase\""),"No profile, test ID, wall time or phase");
        // Every wire outcome keeps its docs/03 meaning; read support is not a test step and effect observed is the owner's.
        List<String> all=new ArrayList<>();for(GuidedTest.Outcome o:GuidedTest.Outcome.values())all.add(o.token(3));
        List<Long> times=new ArrayList<>();for(int i=0;i<all.size();i++)times.add(10L*i);
        CompatibilityReport every=new CompatibilityReport.Builder("1.3.1",new Random(9)).tests(Arrays.asList(entry("turn_sound","turn_sound.oem",0,1,all,times,"not_checked","outstanding"))).build();
        String everyJson=every.json(),everySummary=every.summary();
        check(everyJson.contains("[\"rejected\", \"acknowledged_unconfirmed\", \"readback_unchanged\", \"readback_different\", \"setting_confirmed\", \"restoration_confirmed\", \"interrupted\"]")
            &&everyJson.contains("\"step_values\": [3, null, 3, 3, null, null, null]")&&everyJson.contains("\"elapsed_ms\": [10, 20, 30, 40, 50, 70, 80]"),"Seven distinct step outcomes\n"+everyJson);
        for(String text:new String[]{"Rejected, code 3","Acknowledged, unconfirmed","Readback unchanged, value 3","Readback different, value 3","Setting confirmed","Restoration confirmed","Interrupted/no response","Owner observation: Not checked","Restoration: Outstanding (not confirmed)"})
            check(everySummary.contains(text),"Summary vocabulary "+text+"\n"+everySummary);
        check(everyJson.contains("\"effect\": \"not_checked\"")&&everyJson.contains("\"restoration\": \"outstanding\""),"No owner check and an open restoration are explicit");
        // Each step keeps its own rejection code.
        CompatibilityReport twice=new CompatibilityReport.Builder("1.3.1",new Random(9)).tests(Arrays.asList(entry("turn_sound","turn_sound.oem",0,1,
            Arrays.asList("setting_confirmed","effect_observed","rejected_5","rejected_7"),Arrays.asList(10L,20L,30L,40L),"observed","outstanding"))).build();
        check(twice.summary().contains("Packet evidence: Setting confirmed, Rejected, code 5, Rejected, code 7")&&twice.json().contains("\"step_values\": [null, 5, 7]"),"Per-step codes\n"+twice.summary());
        String bad=CompatibilityReport.parse(twice.json().replace("[null, 5, 7]","[4, 999, 7]")).json();
        check(bad.contains("\"step_values\": [null, null, 7]"),"Imported values are bounded and only on valued steps\n"+bad);
        String forged=everyJson.replace("\"restoration\": \"outstanding\"","\"restoration\": \"restored!\"").replace("\"interrupted\"]","\"interrupted\", \"effect_observed\", \"setting_confirmed_by_magic\"]");
        String cleaned=CompatibilityReport.parse(forged).json();
        check(cleaned.contains("\"restoration\": \"outstanding\"")&&!cleaned.contains("magic")&&!cleaned.contains("\"effect_observed\""),"An imported restoration is never upgraded and unknown steps drop\n"+cleaned);
    }
    private static void imports() {
        CompatibilityReport original=build(fixture(),new Random(10));
        CompatibilityReport again=CompatibilityReport.parse(original.json());
        check(again!=null&&again.json().equals(original.json()),"Round trip is stable");
        String hostile=original.json().replace("\"format\": \"volt-compat-report-v1\",","\"format\": \"volt-compat-report-v1\",\"serial\": \""+SERIAL+"\",\"mac\": \""+MAC+"\",\"notes\": \"Alice\",")
            .replace("\"pid\": \"24431\"","\"pid\": \"99999\"").replace("\"status_length\": 44","\"status_length\": 4096")
            .replace("\"meter\": \"2.0.3.1\"","\"meter\": \"Q2443ZZ9US7777777\"").replace("\"region\": \"US\"","\"region\": \"us<script>\"");
        CompatibilityReport cleaned=CompatibilityReport.parse(hostile);
        check(cleaned!=null,"Hostile report still parses");
        String j=cleaned.json();
        check(!j.contains("serial\"")&&!j.contains(SERIAL)&&!j.contains("C0:FF")&&!j.contains("Alice")&&!j.contains("notes"),"Unknown keys dropped");
        check(!j.contains("99999")&&!j.contains("4096")&&!j.contains("script")&&!j.contains("\"region_source\""),"Out-of-range values dropped");
        check(CompatibilityReport.parse("{\"format\":\"other\"}")==null&&CompatibilityReport.parse("not json")==null&&CompatibilityReport.parse("[1,2]")==null,"Not a report");
        check(CompatibilityReport.parse("{\"format\":\"volt-compat-report-v1\",\"reads\":[{\"cmd\":\"0x7A\",\"outcome\":\"read_supported\"},{\"cmd\":\"0x51\",\"outcome\":\"rejected\"},{\"cmd\":\"0x70\",\"class\":\"probe\",\"outcome\":\"rejected\",\"status\":3,\"length\":0,\"attempts\":9}]}")
            .json().equals("{\n  \"format\": \"volt-compat-report-v1\",\n  \"reads\": [\n    {\n      \"cmd\": \"0x70\",\n      \"class\": \"core\",\n      \"outcome\": \"rejected\",\n      \"status\": 3,\n      \"length\": 0,\n      \"late\": false\n    }\n  ]\n}\n"),
            "Imported reads: write and PIN opcodes dropped, class recomputed, attempts bounded");
        check(CompatibilityReport.parse("{\"format\":\"volt-compat-report-v1\",\"fields\":[{\"offset\":28,\"name\":\"x\",\"present\":true,\"value\":5},{\"offset\":41,\"name\":\"unread_41\",\"present\":true,\"value\":90},{\"offset\":3,\"name\":\"evil\",\"present\":true}]}")
            .json().contains("{\n      \"offset\": 41,\n      \"name\": \"unread_41\",\n      \"present\": true\n    }"),"Imported fields: time dropped, offset 41 value dropped, wrong name dropped");
        check(!CompatibilityReport.parse("{\"format\":\"volt-compat-report-v1\",\"fields\":[{\"offset\":3,\"name\":\"evil\",\"present\":true}]}").json().contains("evil"),"Wrong field name dropped");
        String deep="{\"format\":\"volt-compat-report-v1\",\"x\":"+repeat("[",40)+repeat("]",40)+"}";
        check(CompatibilityReport.parse(deep)==null,"Deep nesting refused");
        check(CompatibilityReport.parse("{\"format\":\"volt-compat-report-v1\",\"registry_version\":2.5,\"app_version\":\"1.3.1\\u0000\"}").json().equals("{\n  \"format\": \"volt-compat-report-v1\"\n}\n"),"Floats and control characters dropped");
    }
    private static void ids() {
        Random random=new Random(11);
        CompatibilityReport a=build(fixture(),random),b=build(fixture(),random);
        check(a.reportId().matches("[0-9a-f]{16}")&&b.reportId().matches("[0-9a-f]{16}")&&!a.reportId().equals(b.reportId()),"Fresh report ID per build");
        check(!a.json().contains("\"report_id\": \""+b.reportId()),"IDs differ in JSON");
    }
    private static void authVariants() {
        check(CompatibilityReport.authVariant(1,0).equals("challenge_free")&&CompatibilityReport.authVariant(17,0).equals("aes")
            &&CompatibilityReport.authVariant(18,0).equals("xor")&&CompatibilityReport.authVariant(34,1).equals("aes_extended"),"Auth variants");
        // The simulator authenticates nothing; the summary says so instead of a bare "?".
        DeviceDescriptor sim=DeviceDescriptor.simulated("24431",0);
        String simulated=new CompatibilityReport.Builder("1.3.1",new Random(5)).device(sim,ModelRegistry.resolve(sim),null).build().summary();
        check(simulated.contains("Authentication: not checked (simulated)")&&!simulated.contains("?"),"Simulated auth wording\n"+simulated);
        String missing=new CompatibilityReport.Builder("1.3.1",new Random(6)).device(fixture(),ModelRegistry.resolve(fixture()),null).build().summary();
        check(missing.contains("Authentication: not recorded")&&!missing.contains("Authentication: ?"),"Missing auth wording\n"+missing);
    }
    private static String repeat(String s,int n){StringBuilder b=new StringBuilder();for(int i=0;i<n;i++)b.append(s);return b.toString();}
    private static void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}
}
