package com.jjricks.volt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Experimental controls: which controls land in Experimental controls and which keep their ordinary place, per
 * layer (UT5 MAX, N65i 22131, ST3 Pro 23452, OEM groups, unmapped and unsupported models), the never-added set,
 * the likelihood-then-owner ranking, every admission condition, the write gate's origins and the Lab variants'
 * wire and readback. The ordinary decisions (Scooter settings) must not change for any model.
 */
public final class ExperimentalControlsTest {
    private static int assertions;
    private static final long SEEN=100000,NOW=SEEN+1000;
    public static void main(String[] args) {
        split(); sweep(); ranking(); gates(); writeGate(); helpVerify(); variants(); unchanged(); simulation();
                // Experimental tries carry the ordinary rows' cautions for the same changes.
        check(ModelRegistry.experimentalCaution("start_speed",0)!=null&&ModelRegistry.experimentalCaution("start_speed",2)!=null&&ModelRegistry.experimentalCaution("start_speed",3)==null,"Kick-start below 3 km/h is cautioned");
        check(ModelRegistry.experimentalCaution("traction_control",0)!=null&&ModelRegistry.experimentalCaution("traction_control",1)==null,"Traction control off is cautioned");
        check(ModelRegistry.experimentalCaution("energy_recovery",0)!=null&&ModelRegistry.experimentalCaution("energy_recovery",2)==null&&ModelRegistry.experimentalCaution("cruise_control",0)==null,"Motor braking off is cautioned; others are not");
System.out.println("ExperimentalControls tests passed: "+assertions+" assertions");
    }

    private static DeviceDescriptor ut5(){return ModelRegistryTest.serial("Q2538ZZ9US7777777");}
    private static DeviceDescriptor model(String pid){return OemControlsTest.model(pid);}
    private static byte[] data(){byte[] d=CapabilityResolverTest.full();d[2]=0;d[35]=0;return d;}
    private static StatusFields status(byte[] d){return CapabilityResolverTest.status(d,SEEN);}
    private static CapabilityResolver.Runtime parked(){return CapabilityResolverTest.parked();}
    private static Set<String> set(String... keys){return new HashSet<>(Arrays.asList(keys));}
    private static Set<String> listed(DeviceDescriptor d){return new HashSet<>(ModelRegistry.experimental(d));}
    private static CapabilityResolver.Decision tryNow(String key,DeviceDescriptor d,byte[] data,CapabilityResolver.Runtime rt){return CapabilityResolver.decideExperimental(key,d,status(data),rt);}
    private static CapabilityResolver.Decision ordinary(String key,DeviceDescriptor d){return CapabilityResolver.decide(key,d,status(data()),parked());}

    /** The test models: what moves to (or appears in) Experimental controls, and that the rest keeps its ordinary place. */
    private static void split() {
        String[] ut5Lab={"acceleration","speed_limit","dual_drive","drive_speed_limit","long_range_mode","overspeed_control","prompt_tone"};
        expect("UT5 MAX",ut5(),ut5Lab);
        expect("N65i 22131",model("22131"),"start_speed","traction_control","hill_start_assist","parking_assist","hill_descent_control","turn_sound","prompt_tone","speed_limit");
        expect("ST3 Pro 23452",model("23452"),"hill_start_assist","parking_assist","hill_descent_control","prompt_tone","speed_limit");
        expect("Group S 24431",model("24431"),"hill_start_assist","parking_assist","hill_descent_control","prompt_tone","speed_limit");
        // GT5 Max (25462, group 2545): the official app offers kick-start speed there, so Standing start stays an ordinary Scooter row.
        expect("GT5 Max 25462",model("25462"),"hill_start_assist","parking_assist","hill_descent_control","prompt_tone","speed_limit");
        CapabilityResolver.Decision gt5Start=ordinary("start_speed",model("25462"));
        check(gt5Start.available()&&gt5Start.allows(0)&&!gt5Start.variant.lab&&ModelRegistry.modelName(model("25462")).equals("GT5 Max"),"GT5 Max keeps Standing start in Scooter settings: "+gt5Start);
        expect("2573",model("25731"),"cruise_control","traction_control","hill_start_assist","parking_assist","hill_descent_control","turn_sound","prompt_tone");
        expect("Group M 25041",model("25041"),"cruise_control","traction_control","headlight","auto_sensor","turn_sound","hill_start_assist","parking_assist",
            "hill_descent_control","prompt_tone");
        // The custom speed cap (owner decision): a Lab copy of the official app's widest cap, 8–32 km/h, where it hides one.
        expect("Group Q 25851",model("25851"),"speed_limit");
        ModelRegistry.Rule ultraCap=ModelRegistry.experimentalRule("speed_limit",model("25852"),null,null);
        check(ultraCap.support==CapabilityResolver.Support.KNOWN_ENCODING&&ultraCap.variant.lab&&ultraCap.variant.cmd==0x6b&&ultraCap.variant.allows(0)&&ultraCap.variant.allows(8)
            &&ultraCap.variant.allows(32)&&!ultraCap.variant.allows(33)&&!ultraCap.variant.allows(7),"UT5 Ultra X custom speed cap: Off or 8–32 km/h, as a try: "+ultraCap);
        check(ModelRegistry.experimentalCaution("speed_limit",20)!=null&&ModelRegistry.experimentalCaution("speed_limit",0)==null,"A cap says it limits riding speed; Off needs no caution");
        for(String pid:new String[]{"25731","25041"}){ModelRegistry.Rule offered=ModelRegistry.rule("speed_limit",model(pid),null,null);
            check(offered.support==CapabilityResolver.Support.KNOWN_ENCODING&&offered.evidence==CapabilityResolver.Evidence.OEM_MAPPED&&!offered.variant.lab,
                "Where the official app offers a cap ("+pid+"), it stays an ordinary row: "+offered);}
        // Nothing new: unmapped, serial-only, conflicting, golf cart, e-bike, wearable, no identity.
        expect("unmapped 39991",ModelRegistryTest.advertised("39991"));
        expect("serial only",ModelRegistryTest.serial("Q2443ZZ9US7777777"));
        expect("conflict",ModelRegistryTest.both("Q2443ZZ9US7777777","25851"));
        expect("golf cart",ModelRegistryTest.advertised("24351"));
        expect("e-bike",ModelRegistryTest.advertised("27361"));
        expect("wearable",ModelRegistryTest.advertised("27681"));
        expect("no identity",DeviceDescriptor.of("","",0,null,false,DeviceDescriptor.Transport.STANDARD,0));
        expect("null",null);
        // A known N65i: its tester-verified controls stay ordinary, its experimental ones say Lab (not Volt-verified).
        for(String key:new String[]{"lock","display_units","headlight","auto_sensor"})
            check(ordinary(key,model("22131")).evidence==CapabilityResolver.Evidence.VOLT_VERIFIED&&!tryNow(key,model("22131"),data(),parked()).experimental(),"N65i keeps verified "+key);
        check(tryNow("turn_sound",model("22131"),data(),parked()).evidence==CapabilityResolver.Evidence.VOLT_LAB,"Experimental evidence is Lab");
        // The UT5 MAX's Lab rows keep their catalogue variant (today's bytes), with the experimental conditions.
        for(String key:ut5Lab){
            CapabilityResolver.Decision d=tryNow(key,ut5(),data(),parked());
            check(d.variant.decode==FeatureVariant.Decode.UT5_FEATURE&&d.variant==ordinary(key,ut5()).variant,"UT5 MAX Lab variant unchanged "+key);
            check(d.preconditions.containsAll(Arrays.asList(CapabilityResolver.Precondition.STATIONARY,CapabilityResolver.Precondition.MOTION_REPORT,
                CapabilityResolver.Precondition.OWNER,CapabilityResolver.Precondition.UNLOCKED,CapabilityResolver.Precondition.NO_WARNING)),"UT5 MAX Lab conditions "+key);
        }
    }
    private static void expect(String label,DeviceDescriptor d,String... keys){
        Set<String> got=listed(d);
        check(got.equals(set(keys)),label+": experimental "+got+" expected "+Arrays.toString(keys));
        for(ScooterCommands.Feature f:ScooterCommands.catalog()){
            CapabilityResolver.Decision tried=CapabilityResolver.decideExperimental(f.key,d,status(data()),parked());
            check(tried.experimental()==got.contains(f.key),label+": decision agrees with the list for "+f.key);
            // Listed controls are exactly those not in Scooter settings; everything offered keeps its ordinary row.
            CapabilityResolver.Decision usual=CapabilityResolver.decide(f.key,d,status(data()),parked());
            if(got.contains(f.key)&&!ModelRegistry.ut5Max(d))check(usual.support==CapabilityResolver.Support.INCOMPATIBLE&&usual.reason==CapabilityResolver.Reason.MODEL_EXCLUDED,label+": "+f.key+" is hidden by the official app");
            if(usual.support==CapabilityResolver.Support.KNOWN_ENCODING&&!usual.variant.lab)check(!got.contains(f.key),label+": offered "+f.key+" stays in Scooter settings");
        }
        for(ScooterCommands.Feature f:ScooterCommands.oemOnly())check(!CapabilityResolver.decideExperimental(f.key,d,status(data()),parked()).experimental(),label+": no other model's light "+f.key);
        check(!CapabilityResolver.decideExperimental("clock_sync",d,status(data()),parked()).experimental(),label+": clock never experimental");
    }

    /** Every model ID the official app lists: experimental exactly where it hides an allowed control; never the excluded set. */
    private static void sweep() {
        Set<String> allowed=set(ModelRegistry.EXPERIMENTAL),never=set(ModelRegistry.NEVER_EXPERIMENTAL);
        for(String key:allowed)check(!never.contains(key),"Allowed and never overlap: "+key);
        for(String key:new String[]{"lock","lock_expiry","pin","proximity_key","maximum_speed","drive_speed_limit","charge_limit","timed_charging","charge_start","clock_sync"})
            check(never.contains(key),"Top speed, the drive-mode cap, charging, lock, PIN, proximity and clock are never added: "+key);
        check(allowed.contains("speed_limit")&&!never.contains("speed_limit"),"The custom speed cap, which only lowers speed, may be tried");
        int models=0;
        for(String prefix:ModelRegistry.LISTED){
            for(String pid:new String[]{prefix,prefix.length()==4?prefix+"1":prefix,prefix.length()==4?prefix+"2":prefix}){
                DeviceDescriptor d=model(pid);ModelRegistry.Resolution r=ModelRegistry.resolve(d);models++;
                Set<String> got=listed(d);
                for(ScooterCommands.Feature f:ScooterCommands.catalog()){
                    CapabilityResolver.Decision usual=CapabilityResolver.decide(f.key,d,status(data()),parked());
                    boolean expected=r.layer==ModelRegistry.Layer.UT5_MAX?f.experimental
                        :r.layer==ModelRegistry.Layer.OEM_GROUP&&allowed.contains(f.key)&&usual.reason==CapabilityResolver.Reason.MODEL_EXCLUDED;
                    check(got.contains(f.key)==expected,pid+": "+f.key+" experimental="+got.contains(f.key));
                    if(r.layer!=ModelRegistry.Layer.UT5_MAX)check(!got.contains(f.key)||!never.contains(f.key),pid+": never-added "+f.key);
                }
            }
        }
        check(models>200,"Swept every listed model ID");
    }

    /** Most likely to work first (related models, then all other listed models), then the owner's priority list. */
    private static void ranking() {
        for(DeviceDescriptor d:new DeviceDescriptor[]{ut5(),model("22131"),model("23452"),model("25462"),model("25731"),model("25041"),model("24431")}){
            List<String> order=ModelRegistry.experimental(d);List<String> priority=Arrays.asList(ModelRegistry.TRY_PRIORITY);
            StringBuilder shown=new StringBuilder();
            for(int i=0;i<order.size();i++){
                int[] n=ModelRegistry.offeredElsewhere(order.get(i),d);shown.append(order.get(i)).append(Arrays.toString(n)).append(' ');
                check(n[0]<=n[1]&&n[2]<=n[3]&&n[0]<=n[2]&&n[3]>=70,"Counts are consistent "+order.get(i)+Arrays.toString(n));
                check(priority.contains(order.get(i)),"Every experimental control has an owner priority: "+order.get(i));
                if(i==0)continue;
                int[] p=ModelRegistry.offeredElsewhere(order.get(i-1),d);
                boolean ordered=p[0]>n[0]||p[0]==n[0]&&(p[2]>n[2]||p[2]==n[2]&&priority.indexOf(order.get(i-1))<priority.indexOf(order.get(i)));
                check(ordered,ModelRegistry.modelLine(d)+": "+order.get(i-1)+" before "+order.get(i));
            }
            System.out.println("Experimental order "+(ModelRegistry.modelLine(d).isEmpty()?d.rulePid():ModelRegistry.modelLine(d))+": "+shown.toString().trim());
        }
        // The N65i: turn-signal sound and kick-start (common on other models) lead; the assists tie and follow the owner list.
        List<String> n65i=ModelRegistry.experimental(model("22131"));
        check(n65i.indexOf("hill_start_assist")<n65i.indexOf("parking_assist")&&n65i.indexOf("parking_assist")<n65i.indexOf("hill_descent_control"),"Ties follow TRY_PRIORITY "+n65i);
        check(n65i.indexOf("prompt_tone")==n65i.size()-1,"The rarest control comes last "+n65i);
        int[] none=ModelRegistry.offeredElsewhere("drive_speed_limit",ut5());check(none[2]==0,"Drive-mode cap is mapped on no other model");
        int[] q=ModelRegistry.offeredElsewhere("prompt_tone",ut5());check(q[0]==2&&q[1]==2,"UT5 MAX: both related Q models offer prompt sound "+Arrays.toString(q));
        int[] legacy=ModelRegistry.offeredElsewhere("auto_sensor",model("23321"));check(legacy[0]>0,"2332: related legacy models offer automatic lights "+Arrays.toString(legacy));
        check(ModelRegistry.experimental(model("23321")).contains("auto_sensor"),"2332 can try automatic lights");
    }

    /** Every admission condition, and that only the experimental rule (never the ordinary one) is consulted. */
    private static void gates() {
        DeviceDescriptor n65i=model("22131");
        check(tryNow("turn_sound",n65i,data(),parked()).available(),"Parked owner, unlocked, fresh status: available");
        check(tryNow("start_speed",n65i,data(),parked()).allows(0)&&tryNow("start_speed",n65i,data(),parked()).allows(5),"Standing start and 5 km/h offered");
        CapabilityResolver.Runtime rt=parked();rt.stationary=false;
        check(tryNow("turn_sound",n65i,data(),rt).reason==CapabilityResolver.Reason.MOVING&&tryNow("turn_sound",n65i,data(),rt).blockedByRide(),"Moving refuses");
        rt=parked();rt.stationary=false;rt.motionSeen=false;rt.parkedConfirmed=true;
        check(tryNow("turn_sound",n65i,data(),rt).reason==CapabilityResolver.Reason.NO_MOTION_REPORT,"The guided tests' parked tick does not admit an experimental change");
        byte[] locked=data();locked[2]=1;check(tryNow("turn_sound",n65i,locked,parked()).reason==CapabilityResolver.Reason.LOCKED,"Locked refuses");
        check(tryNow("prompt_tone",ut5(),locked,parked()).reason==CapabilityResolver.Reason.LOCKED,"A UT5 MAX Lab row needs unlocked too");
        rt=parked();rt.owner=false;check(tryNow("turn_sound",n65i,data(),rt).reason==CapabilityResolver.Reason.OWNER_ONLY,"Owner only");
        rt=parked();rt.warningActive=true;check(tryNow("turn_sound",n65i,data(),rt).reason==CapabilityResolver.Reason.WARNING_ACTIVE,"Scooter warning refuses");
        rt=parked();rt.busy=true;check(tryNow("turn_sound",n65i,data(),rt).reason==CapabilityResolver.Reason.BUSY,"A pending change refuses");
        rt=parked();rt.discovery=true;check(tryNow("turn_sound",n65i,data(),rt).reason==CapabilityResolver.Reason.DISCOVERY_ACTIVE,"A compatibility check refuses");
        rt=parked();rt.test=true;rt.testFeature="turn_sound";check(tryNow("turn_sound",n65i,data(),rt).reason==CapabilityResolver.Reason.TEST_ACTIVE,"Any guided test refuses, even of the same setting");
        rt=parked();rt.recording=true;check(tryNow("turn_sound",n65i,data(),rt).reason==CapabilityResolver.Reason.RECORDING,"Ride or charge recording refuses");
        rt=parked();rt.nowMs=SEEN+7001;check(tryNow("turn_sound",n65i,data(),rt).reason==CapabilityResolver.Reason.STATUS_STALE,"Stale status refuses");
        rt=parked();rt.ready=false;check(tryNow("turn_sound",n65i,data(),rt).reason==CapabilityResolver.Reason.NOT_CONNECTED,"Not connected");
        check(tryNow("turn_sound",n65i,Arrays.copyOf(data(),10),parked()).reason==CapabilityResolver.Reason.FIELD_ABSENT,"The N65i's ten-byte status has no turn-sound field: not reported");
        byte[] odd=data();odd[12]=7;check(tryNow("turn_sound",n65i,odd,parked()).reason==CapabilityResolver.Reason.VALUE_UNKNOWN,"An unexplained value can't change safely");
        byte[] fast=data();fast[19]=9;check(tryNow("start_speed",n65i,fast,parked()).reason==CapabilityResolver.Reason.VALUE_UNKNOWN,"Kick-start readback outside 0-5 refuses");
        check(CapabilityResolver.admitExperimental("start_speed",0,n65i,status(data()),parked()).available(),"Standing start admitted");
        check(CapabilityResolver.admitExperimental("start_speed",6,n65i,status(data()),parked()).reason==CapabilityResolver.Reason.MODEL_EXCLUDED,"Outside 0-5 refused");
        check(CapabilityResolver.admitExperimental("prompt_tone",5,n65i,status(data()),parked()).reason==CapabilityResolver.Reason.MODEL_EXCLUDED,"Outside the five sounds refused");
        // An offered control is never tried this way (it lives in Scooter settings), nor is anything the experimental rule leaves out.
        for(String key:new String[]{"display_units","cruise_control","energy_recovery","maximum_speed","charge_limit","lock","pin","proximity_key","lock_expiry","low_power","acceleration","dual_drive","overspeed_control"}){
            CapabilityResolver.Decision d=tryNow(key,n65i,data(),parked());
            check(!d.experimental()&&!d.available(),"N65i "+key+" not tried experimentally: "+d);
        }
        // The custom speed cap is tried within the official app's widest range: Off or 8–32 km/h.
        check(CapabilityResolver.admitExperimental("speed_limit",20,n65i,status(data()),parked()).available()
            &&CapabilityResolver.admitExperimental("speed_limit",0,n65i,status(data()),parked()).available(),"N65i speed cap tried at 20 km/h or Off");
        for(int out:new int[]{7,33})check(CapabilityResolver.admitExperimental("speed_limit",out,n65i,status(data()),parked()).reason==CapabilityResolver.Reason.MODEL_EXCLUDED,"Speed cap outside 8–32 refused: "+out);
        check(tryNow("turn_sound",ModelRegistryTest.advertised("39991"),data(),parked()).reason==CapabilityResolver.Reason.MODEL_NOT_MAPPED,"Unmapped model keeps its reason");
        check(tryNow("turn_sound",ModelRegistryTest.serial("Q2443ZZ9US7777777"),data(),parked()).reason==CapabilityResolver.Reason.MODEL_NOT_IDENTIFIED,"Serial-only keeps its reason");
        check(tryNow("turn_sound",ModelRegistryTest.both("Q2443ZZ9US7777777","25851"),data(),parked()).reason==CapabilityResolver.Reason.IDENTITY_CONFLICT,"Conflict keeps its reason");
    }

    private static MutationGate.Request request(MutationGate.Kind kind,String key,int value,MutationGate.Origin origin){return new MutationGate.Request(kind,key,value,origin);}
    private static CapabilityResolver.Decision gate(String key,int value,MutationGate.Origin origin,DeviceDescriptor d,byte[] data){
        return MutationGate.check(request(MutationGate.Kind.SET,key,value,origin),d,status(data),parked());
    }
    /** A running try's own write (guided origin, rt.testTry), as BleController admits it. */
    private static CapabilityResolver.Runtime trying(String key){CapabilityResolver.Runtime rt=parked();rt.test=true;rt.testFeature=key;rt.testTry=true;return rt;}
    private static CapabilityResolver.Decision tryGate(String key,int value,boolean restore,DeviceDescriptor d,byte[] data,CapabilityResolver.Runtime rt){
        return MutationGate.check(request(MutationGate.Kind.SET,key,value,restore?MutationGate.Origin.GUIDED_RESTORE:MutationGate.Origin.GUIDED_TEST),d,status(data),rt);
    }
    private static CapabilityResolver.Runtime simulator(){CapabilityResolver.Runtime rt=new CapabilityResolver.Runtime();rt.simulation=true;rt.ready=true;return rt;}
    /**
     * The write gate. On a scooter a try is a journalled guided write under the try rule (rt.testTry); a plain
     * experimental change is the simulator's alone; Lab rows never take an ordinary change.
     */
    private static void writeGate() {
        DeviceDescriptor n65i=model("22131");
        check(tryGate("turn_sound",1,false,n65i,data(),trying("turn_sound")).available()&&tryGate("turn_sound",0,true,n65i,data(),trying("turn_sound")).available(),"A try's write and set back are admitted");
        check(gate("turn_sound",1,MutationGate.Origin.EXPERIMENTAL,n65i,data()).reason==CapabilityResolver.Reason.MODEL_EXCLUDED,"No plain experimental write on a scooter: every try is journalled");
        check(MutationGate.check(request(MutationGate.Kind.SET,"prompt_tone",3,MutationGate.Origin.EXPERIMENTAL),DeviceDescriptor.simulated("24431",0),StatusFields.NONE,simulator()).available(),"The simulator's plain change");
        CapabilityResolver.Runtime guidedTest=trying("turn_sound");guidedTest.testTry=false;
        check(!tryGate("turn_sound",1,false,n65i,data(),guidedTest).available(),"An older guided test never writes a hidden control (the test table refuses it)");
        check(tryGate("turn_sound",1,false,n65i,data(),trying("traction_control")).reason==CapabilityResolver.Reason.TEST_ACTIVE,"Only the running try's control");
        check(tryGate("turn_sound",1,false,n65i,data(),parked()).reason==CapabilityResolver.Reason.NO_TEST,"No guided write without a running try");
        check(!gate("turn_sound",1,MutationGate.Origin.USER,n65i,data()).available(),"An ordinary change of the same control is still refused");
        check(!tryGate("display_units",0,false,n65i,data(),trying("display_units")).available(),"A verified, offered control is not tried");
        check(gate("display_units",0,MutationGate.Origin.USER,n65i,data()).available(),"...it keeps its ordinary change");
        check(tryGate("range_algorithm",3,false,n65i,data(),trying("range_algorithm")).available(),"Help verify: the N65i's OEM-mapped range estimate");
        for(String key:new String[]{"prompt_tone","acceleration","dual_drive","speed_limit"}){
            int value=key.equals("speed_limit")?8:key.equals("prompt_tone")?3:key.equals("acceleration")?5:1;
            check(gate(key,value,MutationGate.Origin.USER,ut5(),data()).reason==CapabilityResolver.Reason.MODEL_EXCLUDED,"UT5 MAX Lab "+key+" only from Experimental controls");
            check(tryGate(key,value,false,ut5(),data(),trying(key)).available(),"UT5 MAX Lab "+key+" as a try");
        }
        check(gate("cruise_control",0,MutationGate.Origin.USER,ut5(),data()).available(),"UT5 MAX ordinary controls unchanged");
        for(MutationGate.Kind kind:new MutationGate.Kind[]{MutationGate.Kind.CHARGE_START,MutationGate.Kind.CLOCK,MutationGate.Kind.PIN_SET,MutationGate.Kind.PIN_ENABLE,
                MutationGate.Kind.PIN_READ,MutationGate.Kind.BOND,MutationGate.Kind.PROXIMITY})
            for(DeviceDescriptor d:new DeviceDescriptor[]{ut5(),n65i,model("25041")}){
                check(!MutationGate.check(request(kind,"",1,MutationGate.Origin.EXPERIMENTAL),d,status(data()),parked()).available(),"No "+kind+" as an experiment");
                check(!MutationGate.check(request(kind,"",1,MutationGate.Origin.GUIDED_TEST),d,status(data()),trying(MutationGate.feature(kind,""))).available(),"No "+kind+" as a try");
            }
        for(String key:ModelRegistry.NEVER_EXPERIMENTAL)
            for(DeviceDescriptor d:new DeviceDescriptor[]{n65i,model("23452"),model("25041"),model("25731"),ModelRegistryTest.advertised("39991")})
                for(int v=0;v<=100;v+=key.equals("charge_limit")?5:1)
                    if(v<=10||key.equals("charge_limit")||key.equals("maximum_speed")){
                        // Units and range estimate are never experimental, but are Help verify where the official app offers them.
                        if(!ModelRegistry.testable(key))check(!tryGate(key,v,false,d,data(),trying(key)).available()&&!tryGate(key,v,true,d,data(),trying(key)).available(),"Never as a try: "+key+"="+v+" on "+d.rulePid());
                        check(!gate(key,v,MutationGate.Origin.EXPERIMENTAL,d,data()).available(),"Never as an experiment: "+key+"="+v+" on "+d.rulePid());
                    }
        CapabilityResolver.Runtime moving=trying("turn_sound");moving.stationary=false;
        check(tryGate("turn_sound",1,false,n65i,data(),moving).reason==CapabilityResolver.Reason.MOVING,"Gate refuses a try while moving");
        check(tryGate("turn_sound",2,false,n65i,data(),trying("turn_sound")).reason==CapabilityResolver.Reason.MODEL_EXCLUDED,"Gate refuses a value outside the domain");
    }

    /**
     * Help verify: the guided-test table's controls the official app offers for the model but Volt hasn't verified
     * there (OEM_MAPPED, never VOLT_VERIFIED), with the try conditions; their ordinary rows are unchanged.
     */
    private static void helpVerify() {
        check(ModelRegistry.verifiable(ut5()).isEmpty(),"UT5 MAX: every guided-test control is verified");
        check(ModelRegistry.verifiable(model("22131")).equals(Arrays.asList("range_algorithm")),"N65i: only range estimate is unverified "+ModelRegistry.verifiable(model("22131")));
        check(ModelRegistry.verifiable(model("23452")).equals(Arrays.asList(ModelRegistry.TESTABLE)),"ST3 Pro: the whole table "+ModelRegistry.verifiable(model("23452")));
        check(ModelRegistry.verifiable(model("25731")).equals(Arrays.asList("display_units","headlight","auto_sensor","range_algorithm")),"2573: turn sound is hidden there, so it's experimental instead");
        check(ModelRegistry.verifiable(ModelRegistryTest.serial("Q2443ZZ9US7777777")).equals(Arrays.asList("display_units")),"Unidentified: dashboard units, as the guided tests offered");
        check(ModelRegistry.verifiable(ModelRegistryTest.advertised("39991")).equals(Arrays.asList("display_units")),"Unlisted: dashboard units only");
        for(DeviceDescriptor d:new DeviceDescriptor[]{ModelRegistryTest.both("Q2443ZZ9US7777777","25851"),ModelRegistryTest.advertised("24351"),ModelRegistryTest.advertised("27361")})
            check(ModelRegistry.verifiable(d).isEmpty(),"Nothing for a conflict, golf cart or e-bike");
        for(String pid:new String[]{"22131","23452","25731","25041","24431","25462"})
            for(String key:ModelRegistry.verifiable(model(pid))){
                CapabilityResolver.Decision v=CapabilityResolver.decideVerify(key,model(pid),status(data()),parked()),usual=ordinary(key,model(pid));
                check(v.evidence==CapabilityResolver.Evidence.OEM_MAPPED&&!v.variant.lab&&!v.experimental(),"Help verify is OEM-mapped, not Lab: "+pid+" "+key);
                check(usual.variant==v.variant&&usual.evidence==CapabilityResolver.Evidence.OEM_MAPPED,"Its ordinary row is the same mapping, unchanged: "+pid+" "+key);
                check(!listed(model(pid)).contains(key),"Never both experimental and Help verify: "+pid+" "+key);
                check(CapabilityResolver.decideTry(key,model(pid),status(data()),parked()).variant==v.variant,"decideTry picks Help verify "+key);
            }
        DeviceDescriptor st3=model("23452");
        check(CapabilityResolver.decideVerify("headlight",st3,status(data()),parked()).available(),"ST3 Pro tail light can be verified now");
        CapabilityResolver.Runtime rt=parked();rt.stationary=false;rt.motionSeen=false;rt.parkedConfirmed=true;
        check(CapabilityResolver.decideVerify("headlight",st3,status(data()),rt).reason==CapabilityResolver.Reason.NO_MOTION_REPORT,"A try needs a real motion report (no parked tick)");
        rt=parked();rt.owner=false;check(CapabilityResolver.decideVerify("display_units",st3,status(data()),rt).reason==CapabilityResolver.Reason.OWNER_ONLY,"Owner only, unlike the old guided tests");
        byte[] locked=data();locked[2]=1;check(CapabilityResolver.decideVerify("display_units",st3,status(locked),parked()).reason==CapabilityResolver.Reason.LOCKED,"Unlocked");
        rt=parked();rt.recording=true;check(CapabilityResolver.decideVerify("display_units",st3,status(data()),rt).reason==CapabilityResolver.Reason.RECORDING,"Not while recording");
        check(CapabilityResolver.decideVerify("display_units",ut5(),status(data()),parked()).reason==CapabilityResolver.Reason.NOT_TESTABLE,"Verified on the UT5 MAX: nothing to verify");
        check(CapabilityResolver.decideVerify("cruise_control",st3,status(data()),parked()).reason==CapabilityResolver.Reason.NOT_TESTABLE,"Only the guided-test table");
    }

    /** Lab variants: the official app's wire where it offers the control, and the same readback as the catalogue decoder. */
    private static void variants() {
        Random random=new Random(7);
        for(String key:ModelRegistry.EXPERIMENTAL){
            CapabilityResolver.Decision d=tryNow(key,pickModel(key),data(),parked());
            FeatureVariant v=d.variant;
            check(v.lab&&v.parked&&v.owner&&v.readable&&v.id.endsWith(".lab")&&v.feature.equals(key),"Lab variant "+v);
            // The bytes are those the official app sends on a model that offers it (and the UT5 MAX catalogue sends).
            ScooterCommands.Feature f=ScooterCommands.find(key);
            for(int value:d.values())check(Arrays.equals(v.encode(value),ScooterCommands.set(key,value)),"Wire "+key+"="+value);
            for(int i=0;i<300;i++){
                byte[] raw=new byte[44];random.nextBytes(raw);StatusFields s=status(raw);
                check(java.util.Objects.equals(v.read(s),f.readback(s.frame())),"Readback "+key+" matches the catalogue decoder");
            }
        }
    }
    private static DeviceDescriptor pickModel(String key){
        for(String pid:new String[]{"25041","25731","22131","23452"})if(ModelRegistry.experimental(model(pid)).contains(key))return model(pid);
        throw new AssertionError("No fixture model for "+key);
    }

    /** Nothing ordinary changes: Scooter settings decisions, guided tests and the compatibility list are as before. */
    private static void unchanged() {
        DeviceDescriptor n65i=model("22131");
        check(CapabilityResolver.decideTest("turn_sound",n65i,status(data()),parked()).reason==CapabilityResolver.Reason.MODEL_EXCLUDED,"Guided test of a hidden control stays refused");
        for(CapabilityResolver.Decision d:CapabilityResolver.all(n65i,status(data()),parked()))
            check(d.variant==null||!d.variant.lab,"Ordinary decisions carry no Lab variant off the UT5 MAX: "+d);
        for(String key:ModelRegistry.EXPERIMENTAL)check(ordinary(key,n65i).support!=CapabilityResolver.Support.KNOWN_ENCODING||!listed(n65i).contains(key),"N65i "+key+" is ordinary or experimental, never both");
    }

    private static void simulation() {
        CapabilityResolver.Runtime rt=new CapabilityResolver.Runtime();rt.simulation=true;rt.ready=true;
        DeviceDescriptor sim=DeviceDescriptor.simulated("24431",0);
        check(CapabilityResolver.decideExperimental("prompt_tone",sim,StatusFields.NONE,rt).available(),"Simulator: ready and idle");
        rt.busy=true;check(CapabilityResolver.decideExperimental("prompt_tone",sim,StatusFields.NONE,rt).reason==CapabilityResolver.Reason.BUSY,"Simulator: busy");
        rt.busy=false;rt.recording=true;check(CapabilityResolver.decideExperimental("prompt_tone",sim,StatusFields.NONE,rt).reason==CapabilityResolver.Reason.RECORDING,"Simulator: recording");
        check(!CapabilityResolver.decideExperimental("dual_drive",sim,StatusFields.NONE,rt).experimental(),"Simulator: offered dual motor stays ordinary");
        List<String> simulated=ModelRegistry.experimental(DeviceDescriptor.simulated("",0));
        check(new HashSet<>(simulated).equals(new HashSet<>(ModelRegistry.experimental(ut5()))),"UT5 MAX simulator lists the UT5 MAX Lab rows "+simulated);
        check(ModelRegistry.experimental(DeviceDescriptor.simulated("39991",0)).isEmpty(),"Unknown simulated model lists nothing");
    }

    private static void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}
}
