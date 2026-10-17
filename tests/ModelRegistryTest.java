package local.navee.offlinelock;

import java.util.HashSet;
import java.util.Set;

/** Layer resolution, prefix precedence, provenance-limited rules and UT5-only model assumptions. */
public final class ModelRegistryTest {
    private static int assertions;
    public static void main(String[] args) {
        // Touch the registry before ScooterCommands so a static-initialiser cycle would show here.
        check(ModelRegistry.resolve(advertised("25381")).layer==ModelRegistry.Layer.UT5_MAX&&ModelRegistry.variant("clock_sync",advertised("25381"),null,null)==ModelRegistry.CLOCK
            &&ModelRegistry.variant("lock",advertised("25381"),null,null).id.equals("lock.ut5"),"Registry initialises first");
        tables(); layers(); ut5Identity(); helpers(); assumptions(); rules();
        System.out.println("ModelRegistry tests passed: "+assertions+" assertions");
    }

    static DeviceDescriptor serial(String serial){return DeviceDescriptor.of(serial,"",0,null,false,DeviceDescriptor.Transport.STANDARD,0);}
    static DeviceDescriptor advertised(String pid){return DeviceDescriptor.of("",pid,1,null,false,DeviceDescriptor.Transport.STANDARD,0);}
    static DeviceDescriptor both(String serial,String pid){return DeviceDescriptor.of(serial,pid,1,null,false,DeviceDescriptor.Transport.STANDARD,0);}
    private static ModelRegistry.Layer layer(DeviceDescriptor d){return ModelRegistry.resolve(d).layer;}

    private static void tables() {
        Set<String> listed=new HashSet<>();for(String p:ModelRegistry.LISTED)check(listed.add(p),"Unique literal "+p);
        check(listed.size()==87,"87 OEM literals");
        Set<String> grouped=new HashSet<>();
        for(String[] group:new String[][]{ModelRegistry.Q,ModelRegistry.O,ModelRegistry.P,ModelRegistry.G,ModelRegistry.H,ModelRegistry.K,ModelRegistry.C,ModelRegistry.B,
                ModelRegistry.L,ModelRegistry.S,ModelRegistry.D,ModelRegistry.E,ModelRegistry.R,ModelRegistry.M,ModelRegistry.LEGACY_T,ModelRegistry.NOT_I_EXTRA,
                ModelRegistry.GOLF,ModelRegistry.OTHER_TRANSPORT,ModelRegistry.EXO,ModelRegistry.SINGLE})
            for(String p:group){check(listed.contains(p),"Group member is listed: "+p);grouped.add(p);}
        check(grouped.equals(listed),"Groups and single literals cover every listed ID");
        check(!ModelRegistry.oemListed("39991")&&!ModelRegistry.oemListed("")&&!ModelRegistry.oemListed("253")&&ModelRegistry.oemListed("25851"),"Unknown and short IDs are unlisted");
        check(ModelRegistry.longestPrefix("24169",ModelRegistry.LISTED).equals("2416"),"Prefix matching, never exact literals");
    }

    private static void layers() {
        ModelRegistry.Resolution ld=ModelRegistry.resolve(advertised("27361"));check(ld.layer==ModelRegistry.Layer.UNSUPPORTED_TRANSPORT&&ld.matchedPrefix.equals("27361"),"LD e-bike");
        check(layer(advertised("27391"))==ModelRegistry.Layer.UNSUPPORTED_TRANSPORT,"Digitech e-bike");
        check(layer(advertised("27681"))==ModelRegistry.Layer.UNSUPPORTED_PRODUCT,"Exo by ID");
        ModelRegistry.Resolution golf=ModelRegistry.resolve(advertised("24351"));check(golf.layer==ModelRegistry.Layer.UNSUPPORTED_PRODUCT&&golf.matchedPrefix.equals("2435"),"Golf");
        check(layer(DeviceDescriptor.of("Q2443ZZ9US7777777","",0,null,false,DeviceDescriptor.Transport.EXO,0))==ModelRegistry.Layer.UNSUPPORTED_PRODUCT,"Exo transport");
        for(DeviceDescriptor.Transport t:new DeviceDescriptor.Transport[]{DeviceDescriptor.Transport.LD_EBIKE,DeviceDescriptor.Transport.DIGITECH_EBIKE,DeviceDescriptor.Transport.UNVERIFIED})
            check(layer(DeviceDescriptor.of("X2538-SYNTHETIC","",0,null,false,t,0))==ModelRegistry.Layer.UNSUPPORTED_TRANSPORT,"Non-standard transport "+t);
        check(layer(advertised("25381"))==ModelRegistry.Layer.UT5_MAX&&layer(serial("X2538-SYNTHETIC"))==ModelRegistry.Layer.UT5_MAX,"UT5 MAX advertised and serial-only");
        check(layer(serial("Q2538ZZ9US7777777"))==ModelRegistry.Layer.UT5_MAX&&layer(serial("Q2538ZZ9DE7777777"))==ModelRegistry.Layer.UT5_MAX,"Every 2538 region is UT5 MAX");
        ModelRegistry.Resolution q=ModelRegistry.resolve(advertised("25471"));check(q.layer==ModelRegistry.Layer.OEM_GROUP&&q.matchedPrefix.equals("2547"),"Listed advertised ID is an OEM group");
        check(layer(serial("Q2443ZZ9US7777777"))==ModelRegistry.Layer.SHARED,"Serial-only non-2538 is shared (provenance rule)");
        check(layer(advertised("39991"))==ModelRegistry.Layer.SHARED&&layer(serial(""))==ModelRegistry.Layer.SHARED&&layer(null)==ModelRegistry.Layer.SHARED,"Unknown and unidentified are shared");
        ModelRegistry.Resolution conflict=ModelRegistry.resolve(both("Q2547ZZ9US7777777","25381"));
        check(conflict.layer==ModelRegistry.Layer.SHARED&&conflict.conflict&&conflict.note.equals("Model ID mismatch"),"Advertised 2538x + serial 2547 is a shared conflict");
        check(layer(both("Q2401ZZ9US7777777","24431"))==ModelRegistry.Layer.SHARED,"Listed advertised ID with a conflicting serial is shared");
        check(layer(both("Q2443ZZ9US7777777","24431"))==ModelRegistry.Layer.OEM_GROUP,"Matching advertised + serial is an OEM group");
        check(layer(DeviceDescriptor.simulated("",0))==ModelRegistry.Layer.UT5_MAX&&layer(DeviceDescriptor.simulated("24431",0))==ModelRegistry.Layer.OEM_GROUP
            &&layer(DeviceDescriptor.simulated("39991",0))==ModelRegistry.Layer.SHARED,"Simulated models resolve the same way");
    }

    /** Owner decision: a serial-2538 profile always keeps the UT5 MAX layer; a mismatch is diagnostic only. */
    private static void ut5Identity() {
        ModelRegistry.Resolution mismatch=ModelRegistry.resolve(both("Q2538ZZ9US7777777","24431"));
        check(mismatch.layer==ModelRegistry.Layer.UT5_MAX&&mismatch.conflict&&mismatch.note.equals("Model ID mismatch"),"Owner UT5 MAX is never demoted by an advertised mismatch");
        ModelRegistry.Resolution agree=ModelRegistry.resolve(both("Q2538ZZ9US7777777","25381"));check(agree.layer==ModelRegistry.Layer.UT5_MAX&&!agree.conflict&&agree.note.isEmpty(),"Matching UT5 MAX");
        check(layer(both("Q2538ZZ9US7777777","27361"))==ModelRegistry.Layer.UNSUPPORTED_TRANSPORT,"Transport checks still come first");
        check(layer(both("Q2538ZZ9US7777777","24351"))==ModelRegistry.Layer.UNSUPPORTED_PRODUCT,"Product checks still come first");
    }

    private static void helpers() {
        check(ModelRegistry.longestPrefix("27361",new String[]{"2736","27361"}).equals("27361")&&ModelRegistry.longestPrefix("2",new String[]{"27"}).isEmpty(),"Most specific prefix wins");
        check(ModelRegistry.any(advertised("24431"),ModelRegistry.S)&&!ModelRegistry.any(serial("Q2443ZZ9US7777777"),ModelRegistry.S),"Group rules test only the advertised ID");
        check(ModelRegistry.endsWith(serial("Q2543ZZ9US7777777"),'1')==ModelRegistry.Tri.UNKNOWN,"endsWith on a serial-only PID is UNKNOWN");
        check(ModelRegistry.endsWith(advertised("2543"),'1')==ModelRegistry.Tri.UNKNOWN,"endsWith needs five digits");
        check(ModelRegistry.endsWith(advertised("25431"),'1')==ModelRegistry.Tri.YES&&ModelRegistry.endsWith(advertised("25432"),'1')==ModelRegistry.Tri.NO,"Fifth digit");
        DeviceDescriptor noFirmware=advertised("24011");
        check(ModelRegistry.meterBelow(noFirmware,"2.0.1.2")==ModelRegistry.Tri.UNKNOWN&&ModelRegistry.meterAtMost(noFirmware,"2.0.0.4")==ModelRegistry.Tri.UNKNOWN,"No session firmware is UNKNOWN");
        DeviceDescriptor.Firmware fw=DeviceDescriptor.Firmware.fromTelemetry("2.0.1.2",null,null,null,null,5);
        check(ModelRegistry.meterBelow(noFirmware.withFirmware(fw,false),"2.0.1.3")==ModelRegistry.Tri.UNKNOWN,"Stored firmware never authorises");
        DeviceDescriptor read=noFirmware.withFirmware(fw,true);
        check(ModelRegistry.meterBelow(read,"2.0.1.2")==ModelRegistry.Tri.NO&&ModelRegistry.meterBelow(read,"2.0.1.3")==ModelRegistry.Tri.YES,"Exclusive edge");
        check(ModelRegistry.meterAtMost(read,"2.0.1.2")==ModelRegistry.Tri.YES&&ModelRegistry.meterAtMost(read,"2.0.1.1")==ModelRegistry.Tri.NO,"Inclusive edge");
        DeviceDescriptor ten=noFirmware.withFirmware(DeviceDescriptor.Firmware.fromTelemetry("2.0.10.0",null,null,null,null,5),true);
        check(ModelRegistry.meterBelow(ten,"2.0.2.0")==ModelRegistry.Tri.YES,"Lexicographic quirk is kept: 2.0.10.0 is below 2.0.2.0");
        DeviceDescriptor raw=noFirmware.withFirmware(DeviceDescriptor.Firmware.fromTelemetry("32 30 (raw)","1.0.0.0",null,null,null,5),true);
        check(ModelRegistry.meterBelow(raw,"9")==ModelRegistry.Tri.UNKNOWN,"Unreadable meter version is UNKNOWN");
    }

    private static void assumptions() {
        DeviceDescriptor ut5=serial("Q2538ZZ9US7777777"),other=advertised("25471"),unknown=serial("Q2443ZZ9US7777777");
        check(ModelRegistry.ratedWh(ut5)==716.04&&ModelRegistry.ratedWh(other)==null&&ModelRegistry.ratedWh(unknown)==null,"Rated Wh is UT5 MAX only");
        check(ModelRegistry.showsPhoto(ut5)&&!ModelRegistry.showsPhoto(other),"Photo is UT5 MAX only");
        check(ModelRegistry.chargeReferenceProduct(ut5)==2538&&ModelRegistry.chargeReferenceProduct(other)==-1,"Charge reference is UT5 MAX only");
        check(ModelRegistry.driveModeLabelProduct(ut5)==2538&&ModelRegistry.driveModeLabelProduct(unknown)==0,"Drive-mode labels are UT5 MAX only");
        check(ModelRegistry.rangeFollowProduct(ut5)==2538&&ModelRegistry.rangeFollowProduct(other)==0,"Range follow is UT5 MAX only");
        check(ModelRegistry.modelName(ut5).equals("UT5 MAX")&&ModelRegistry.modelName(other).isEmpty(),"Model name");
        check(ModelRegistry.modelLine(ut5).equals("UT5 MAX")&&ModelRegistry.modelLine(advertised("24431")).equals("Model 24431")
            &&ModelRegistry.modelLine(unknown).equals("Model 2443 (unconfirmed)")&&ModelRegistry.modelLine(serial("")).isEmpty(),"Model line");
        check(ModelRegistry.ratedWh(both("Q2538ZZ9US7777777","24431"))==716.04,"A mismatched owner UT5 MAX keeps its assumptions");
    }

    private static void rules() {
        DeviceDescriptor us=serial("Q2538ZZ9US7777777"),de=serial("Q2538ZZ9DE7777777");
        for(ScooterCommands.Feature f:ScooterCommands.catalog()){
            ModelRegistry.Rule rule=ModelRegistry.rule(f.key,us,null,StatusFields.NONE);
            check(rule.support==CapabilityResolver.Support.KNOWN_ENCODING&&rule.variant!=null&&rule.variant.id.equals(f.key+".ut5"),"UT5 rule "+f.key);
            check((rule.evidence==CapabilityResolver.Evidence.VOLT_LAB)==f.experimental,"Lab evidence follows experimental "+f.key);
            check(rule.oemNote.isEmpty(),"US UT5 MAX carries no regional note "+f.key);
            check(ModelRegistry.variant(f.key,us,null,null)==rule.variant,"variant() is the rule's variant");
        }
        check(ModelRegistry.rule("cruise_control",us,null,null).evidence==CapabilityResolver.Evidence.VOLT_VERIFIED
            &&ModelRegistry.rule("pin",us,null,null).evidence==CapabilityResolver.Evidence.OEM_MAPPED
            &&ModelRegistry.rule("clock_sync",us,null,null).evidence==CapabilityResolver.Evidence.OEM_MAPPED,"Evidence levels");
        // Regional OEM rules on a non-US UT5 MAX are notes, never restrictions.
        ModelRegistry.Rule max=ModelRegistry.rule("maximum_speed",de,null,null);
        check(max.support==CapabilityResolver.Support.KNOWN_ENCODING&&max.reason==CapabilityResolver.Reason.NONE&&!max.oemNote.isEmpty()
            &&java.util.Arrays.equals(max.variant.values,ScooterCommands.find("maximum_speed").optionValues),"DE UT5 MAX keeps max speed, with an OEM note");
        check(!ModelRegistry.rule("start_speed",de,null,null).oemNote.isEmpty()&&!ModelRegistry.rule("headlight",de,null,null).oemNote.isEmpty()
            &&!ModelRegistry.rule("line_light",de,null,null).oemNote.isEmpty()&&ModelRegistry.rule("display_units",de,null,null).oemNote.isEmpty(),"Regional notes");
        DeviceDescriptor none=serial("Q2538ZZ9US777777");
        check(none.region==null&&ModelRegistry.rule("maximum_speed",none,null,null).support==CapabilityResolver.Support.KNOWN_ENCODING
            &&!ModelRegistry.rule("maximum_speed",none,null,null).oemNote.isEmpty()&&!ModelRegistry.rule("cruise_control",none,null,null).oemNote.isEmpty()
            &&!ModelRegistry.rule("headlight",none,null,null).oemNote.isEmpty()&&ModelRegistry.rule("display_units",none,null,null).oemNote.isEmpty(),"No-region UT5 MAX keeps controls, with the OEM's strictest notes");
        check(!ModelRegistry.rule("maximum_speed",serial("Q2538ZZ9us7777777"),null,null).oemNote.isEmpty(),"Lowercase US region is not the OEM's USA bucket");
        ModelRegistry.Rule lock=ModelRegistry.rule("lock",advertised("39991"),null,null);
        check(lock.support==CapabilityResolver.Support.KNOWN_ENCODING&&lock.evidence==CapabilityResolver.Evidence.SHARED_CONTRACT&&lock.variant==ModelRegistry.SHARED_LOCK,"Shared lock contract");
        check(ModelRegistry.rule("display_units",advertised("24431"),null,null).reason==CapabilityResolver.Reason.MODEL_NOT_MAPPED,"OEM group rows arrive later");
        check(ModelRegistry.rule("display_units",both("Q2547ZZ9US7777777","25381"),null,null).reason==CapabilityResolver.Reason.IDENTITY_CONFLICT,"Conflict reason");
        boolean unknownKey=false;try{ModelRegistry.rule("not_a_feature",us,null,null);}catch(IllegalArgumentException expected){unknownKey=true;}
        check(unknownKey,"Unknown keys are rejected");
    }

    private static void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}
}
