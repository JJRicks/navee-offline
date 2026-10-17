package local.navee.offlinelock;

import java.util.Arrays;
import java.util.List;

/** Golden UT5 MAX decisions, explicit decisions for other models, and every runtime refusal. */
public final class CapabilityResolverTest {
    private static int assertions;
    private static final long SEEN=100000,NOW=SEEN+1000;
    public static void main(String[] args) {
        goldenUt5(); unknownModels(); unsupported(); presence(); runtime(); lockContract(); roles(); admission(); simulation(); copy(); oemHomeScreen();
        System.out.println("CapabilityResolver tests passed: "+assertions+" assertions");
    }

    /** Every catalogued UT5 MAX field set to a value inside its domain. */
    static byte[] full() {
        byte[] d=new byte[44];
        d[1]=3;d[2]=0;d[3]=1;d[4]=1;d[5]=60;d[6]=2;d[7]=1;d[8]=1;d[10]=(byte)0x81;d[11]=1;d[12]=0;d[13]=0;d[15]=1;d[16]=0x01;d[17]=(byte)0x82;d[18]=0x10;
        d[19]=3;d[20]=(byte)0x99;d[21]=2;d[25]=40;d[26]=0;d[27]=0x01;d[28]=0x02;d[29]=0x03;d[30]=0x04;d[31]=90;d[32]=0;d[33]=1;d[34]=2;d[37]=5;d[38]=0;d[39]=0;d[42]=(byte)0x8f;
        return d;
    }
    static StatusFields status(byte[] data,long seen){return StatusFields.of(StatusFieldsTest.notification(0x70,0,data),seen);}
    static CapabilityResolver.Runtime parked(){
        CapabilityResolver.Runtime rt=new CapabilityResolver.Runtime();
        rt.ready=true;rt.stationary=true;rt.motionSeen=true;rt.owner=true;rt.bonded=true;rt.pinEnabledFresh=true;rt.nowMs=NOW;return rt;
    }
    private static DeviceDescriptor serial(String s){return ModelRegistryTest.serial(s);}
    private static DeviceDescriptor advertised(String pid){return ModelRegistryTest.advertised(pid);}
    private static CapabilityResolver.Decision decide(String key,DeviceDescriptor d,StatusFields s,CapabilityResolver.Runtime rt){return CapabilityResolver.decide(key,d,s,rt);}

    private static void goldenUt5() {
        byte[] data=full();Protocol.Frame frame=StatusFieldsTest.notification(0x70,0,data);StatusFields s=StatusFields.of(frame,SEEN);
        for(DeviceDescriptor d:new DeviceDescriptor[]{serial("Q2538ZZ9US7777777"),ModelRegistryTest.both("Q2538ZZ9US7777777","25381"),advertised("25381")}){
            List<CapabilityResolver.Decision> all=CapabilityResolver.all(d,s,parked());
            check(all.size()==ScooterCommands.catalog().size()+1&&all.get(all.size()-1).feature.equals("clock_sync"),"Catalogue order plus clock_sync");
            int i=0;
            for(ScooterCommands.Feature f:ScooterCommands.catalog()){
                CapabilityResolver.Decision decision=all.get(i++);
                check(decision.feature.equals(f.key)&&decision.layer==ModelRegistry.Layer.UT5_MAX&&decision.registryVersion==ModelRegistry.VERSION,"Golden order "+f.key);
                check(decision.support==CapabilityResolver.Support.KNOWN_ENCODING&&decision.available(),"Golden UT5 available "+f.key+" "+decision);
                int[] expected=f.key.equals("range_algorithm")?ScooterCommands.choiceValues(f.key,frame):f.optionValues;
                check(Arrays.equals(decision.values(),expected),"Golden values "+f.key);
                String[] labels=new String[expected.length];for(int j=0;j<expected.length;j++)labels[j]=f.valueLabel(expected[j]);
                check(Arrays.equals(decision.labels(),f.key.equals("range_algorithm")?labels:f.options),"Golden labels "+f.key);
                check(java.util.Objects.equals(decision.current(),f.readback(frame)),"Golden readback "+f.key);
                check(decision.variant.lab==f.experimental&&decision.variant.parked==f.requiresParked,"Golden lab/parked "+f.key);
                check(decision.variant.min==f.min&&decision.variant.max==f.max&&decision.variant.step==f.step,"Golden slider bounds "+f.key);
                for(int v=-2;v<=110;v++)if(!f.key.equals("range_algorithm"))check(decision.allows(v)==f.allows(v),"Golden domain "+f.key+" "+v);
                check(decision.preconditions.contains(CapabilityResolver.Precondition.STATIONARY)==f.requiresParked,"Parked precondition "+f.key);
                check(decision.oemNote.isEmpty(),"US UT5 MAX has no OEM note "+f.key);
            }
            CapabilityResolver.Decision clock=all.get(all.size()-1);
            check(clock.available()&&clock.variant==ModelRegistry.CLOCK&&clock.current()==null,"Clock is available, never read back");
        }
        CapabilityResolver.Decision range=decide("range_algorithm",serial("X2538-SYNTHETIC"),s,parked());
        check(Arrays.equals(range.values(),new int[]{0,2,3})&&range.allows(2)&&!range.allows(1)&&range.current()==2,"Dynamic range choices");
        // Non-US UT5 MAX: identical choices and availability (owner decision); the OEM rule is a note.
        CapabilityResolver.Decision de=decide("maximum_speed",serial("Q2538ZZ9DE7777777"),s,parked());
        check(de.available()&&Arrays.equals(de.values(),new int[]{25,32,40,50})&&!de.oemNote.isEmpty(),"DE UT5 MAX keeps maximum speed");
        CapabilityResolver.Decision start=decide("start_speed",serial("Q2538ZZ9NL7777777"),s,parked());
        check(start.available()&&start.allows(0)&&start.allows(5),"Non-US UT5 MAX keeps start speed 0-5");
        check(decide("cruise_control",serial("Q2538ZZ9NL7777777"),s,parked()).available(),"Non-US UT5 MAX keeps cruise");
        check(decide("cruise_control",ModelRegistryTest.both("Q2538ZZ9US7777777","24431"),s,parked()).available(),"Mismatched owner UT5 MAX keeps every control");
    }

    private static void unknownModels() {
        StatusFields s=status(full(),SEEN);
        DeviceDescriptor[] models={serial("Q2443ZZ9US7777777"),advertised("39991"),ModelRegistryTest.both("Q2547ZZ9US7777777","25381"),serial("")};
        CapabilityResolver.Reason[] reasons={CapabilityResolver.Reason.MODEL_NOT_IDENTIFIED,CapabilityResolver.Reason.MODEL_NOT_MAPPED,
            CapabilityResolver.Reason.IDENTITY_CONFLICT,CapabilityResolver.Reason.MODEL_NOT_IDENTIFIED};
        for(int m=0;m<models.length;m++)for(CapabilityResolver.Decision decision:CapabilityResolver.all(models[m],s,parked())){
            if(decision.feature.equals("lock")){
                check(decision.support==CapabilityResolver.Support.KNOWN_ENCODING&&decision.evidence==CapabilityResolver.Evidence.SHARED_CONTRACT&&decision.available(),"Shared lock "+models[m]);
                check(decision.variant==ModelRegistry.SHARED_LOCK&&Integer.valueOf(0).equals(decision.current()),"Shared lock reads offset 2");
            } else {
                check(decision.support==CapabilityResolver.Support.UNKNOWN_MAPPING&&decision.reason==reasons[m]&&decision.variant==null&&!decision.available(),"Explicit unmapped decision "+decision);
                check(decision.values().length==0&&decision.current()==null&&!decision.allows(1)&&decision.evidence==CapabilityResolver.Evidence.NONE,"No union of other models' choices "+decision.feature);
            }
        }
        check(decide("cruise_control",serial("Q2443ZZ9US7777777"),s,parked()).reasonText().equals("Identify model first"),"Identify copy");
        check(decide("cruise_control",advertised("39991"),s,parked()).reasonText().equals("Not mapped for this model"),"Not mapped copy");
        check(decide("cruise_control",ModelRegistryTest.both("Q2547ZZ9US7777777","25381"),s,parked()).reasonText().equals("Model ID mismatch"),"Conflict copy");
    }

    private static void unsupported() {
        StatusFields s=status(full(),SEEN);
        for(String pid:new String[]{"24351","27361","27391","27681"}){
            for(CapabilityResolver.Decision decision:CapabilityResolver.all(advertised(pid),s,parked()))
                check(decision.support==CapabilityResolver.Support.INCOMPATIBLE&&!decision.available()&&decision.variant==null&&decision.reasonText().equals("Not supported"),"Incompatible "+pid+" "+decision.feature);
            check(!CapabilityResolver.admit("lock",0,advertised(pid),s,parked()).available()&&!CapabilityResolver.admit("lock",1,advertised(pid),s,parked()).available(),"No lock on "+pid);
        }
        check(decide("lock",advertised("27361"),s,parked()).reason==CapabilityResolver.Reason.TRANSPORT_UNSUPPORTED
            &&decide("lock",advertised("24351"),s,parked()).reason==CapabilityResolver.Reason.PRODUCT_UNSUPPORTED,"Unsupported reasons");
    }

    private static void presence() {
        DeviceDescriptor ut5=serial("Q2538ZZ9US7777777");
        StatusFields shortStatus=status(Arrays.copyOf(full(),25),SEEN);
        CapabilityResolver.Decision max=decide("maximum_speed",ut5,shortStatus,parked());
        check(max.reason==CapabilityResolver.Reason.FIELD_ABSENT&&max.current()==null&&max.reasonText().equals("Not reported"),"Missing offset 25 is absent, never 0");
        byte[] zero=full();zero[3]=0;
        CapabilityResolver.Decision cruise=decide("cruise_control",ut5,status(zero,SEEN),parked());
        check(cruise.available()&&Integer.valueOf(0).equals(cruise.current()),"Present 0 is available");
        check(decide("cruise_control",ut5,shortStatus,parked()).available(),"A short status still serves its present fields");
        check(decide("charge_start",ut5,shortStatus,parked()).available()&&decide("pin",ut5,shortStatus,parked()).available(),"Fields without 0x70 readback need no presence");
        byte[] unknown=full();unknown[20]=(byte)0x85;
        check(decide("speed_limit",ut5,status(unknown,SEEN),parked()).reason==CapabilityResolver.Reason.VALUE_UNKNOWN,"Lab rule: unknown value can't change safely");
        unknown=full();unknown[25]=0;
        CapabilityResolver.Decision sentinel=decide("maximum_speed",ut5,status(unknown,SEEN),parked());
        check(sentinel.available()&&Integer.valueOf(0).equals(sentinel.current()),"UT5 MAX: an out-of-domain readback (0) stays changeable, as before");
        check(CapabilityResolver.admit("maximum_speed",32,ut5,status(unknown,SEEN),parked()).available(),"UT5 MAX: a valid value is admitted over the sentinel");
        check(CapabilityResolver.admit("maximum_speed",0,ut5,status(unknown,SEEN),parked()).reason==CapabilityResolver.Reason.MODEL_EXCLUDED,"UT5 MAX: the sentinel itself is never written");
        check(decide("maximum_speed",serial("Q2601ZZ9US7777777"),status(unknown,SEEN),parked()).available()==false,"Non-UT5 stays read-only");
        byte[] noChoices=full();noChoices[15]=3;
        check(decide("range_algorithm",ut5,status(noChoices,SEEN),parked()).reason==CapabilityResolver.Reason.NO_CHOICES,"No dynamic choices");
        check(decide("cruise_control",ut5,StatusFields.of(StatusFieldsTest.notification(0x70,1,full()),SEEN),parked()).reason==CapabilityResolver.Reason.STATUS_STALE,"A rejected status is never fresh");
    }

    private static void runtime() {
        DeviceDescriptor ut5=serial("Q2538ZZ9US7777777");StatusFields s=status(full(),SEEN);
        CapabilityResolver.Runtime rt=parked();rt.ready=false;
        check(decide("cruise_control",ut5,s,rt).reason==CapabilityResolver.Reason.NOT_CONNECTED&&decide("cruise_control",ut5,s,null).reason==CapabilityResolver.Reason.NOT_CONNECTED,"Not connected");
        rt=parked();rt.nowMs=SEEN+7001;check(decide("cruise_control",ut5,s,rt).reason==CapabilityResolver.Reason.STATUS_STALE,"Stale status");
        rt.nowMs=SEEN+7000;check(decide("cruise_control",ut5,s,rt).available(),"7000 ms is still fresh");
        rt=parked();rt.busy=true;check(decide("cruise_control",ut5,s,rt).reason==CapabilityResolver.Reason.BUSY,"Busy");
        rt=parked();rt.stationary=false;check(decide("cruise_control",ut5,s,rt).reason==CapabilityResolver.Reason.MOVING&&decide("cruise_control",ut5,s,rt).reasonText().equals("Stop the scooter first"),"Moving");
        check(decide("headlight",ut5,s,rt).available(),"Non-parked controls stay available while moving");
        rt.motionSeen=false;check(decide("cruise_control",ut5,s,rt).reason==CapabilityResolver.Reason.NO_MOTION_REPORT,"No motion report");
        rt=parked();rt.discovery=true;check(decide("display_units",ut5,s,rt).reason==CapabilityResolver.Reason.DISCOVERY_ACTIVE,"Discovery");
        rt=parked();rt.test=true;rt.testFeature="display_units";
        check(decide("display_units",ut5,s,rt).available()&&decide("headlight",ut5,s,rt).reason==CapabilityResolver.Reason.TEST_ACTIVE,"Test in progress");
        rt=parked();rt.warningActive=true;
        check(decide("cruise_control",ut5,s,rt).available()&&decide("lock",ut5,s,rt).reason==CapabilityResolver.Reason.WARNING_ACTIVE,"Warnings block only locking");
    }

    private static void lockContract() {
        StatusFields s=status(full(),SEEN);
        for(DeviceDescriptor d:new DeviceDescriptor[]{serial("Q2538ZZ9US7777777"),advertised("39991"),serial("Q2443ZZ9US7777777"),advertised("24431")}){
            CapabilityResolver.Runtime rt=parked();rt.warningActive=true;
            check(CapabilityResolver.admit("lock",1,d,s,rt).reason==CapabilityResolver.Reason.WARNING_ACTIVE,"Warning blocks lock "+d);
            check(CapabilityResolver.admit("lock",0,d,s,rt).available(),"Warning never blocks unlock "+d);
            rt=parked();rt.stationary=false;rt.motionSeen=false;
            check(CapabilityResolver.admit("lock",1,d,s,rt).reason==CapabilityResolver.Reason.NO_MOTION_REPORT&&CapabilityResolver.admit("lock",0,d,s,rt).available(),"Unlock without a motion report "+d);
            rt.motionSeen=true;check(CapabilityResolver.admit("lock",1,d,s,rt).reason==CapabilityResolver.Reason.MOVING&&CapabilityResolver.admit("lock",0,d,s,rt).available(),"Unlock while moving "+d);
            rt=parked();rt.busy=true;check(CapabilityResolver.admit("lock",0,d,s,rt).reason==CapabilityResolver.Reason.BUSY,"Unlock waits for a pending change");
            rt=parked();rt.nowMs=SEEN+8000;check(CapabilityResolver.admit("lock",0,d,s,rt).reason==CapabilityResolver.Reason.STATUS_STALE,"Unlock needs fresh status");
            rt=parked();rt.ready=false;check(CapabilityResolver.admit("lock",0,d,s,rt).reason==CapabilityResolver.Reason.NOT_CONNECTED,"Unlock needs a ready session");
            check(CapabilityResolver.admit("lock",0,d,status(new byte[2],SEEN),parked()).reason==CapabilityResolver.Reason.FIELD_ABSENT,"Unlock needs the lock field");
            CapabilityResolver.Decision recovery=CapabilityResolver.admit("lock",0,d,s,parked());
            check(recovery.preconditions.equals(java.util.EnumSet.of(CapabilityResolver.Precondition.FRESH_STATUS)),"Recovery contract preconditions");
        }
    }

    private static void roles() {
        DeviceDescriptor ut5=serial("Q2538ZZ9US7777777");StatusFields s=status(full(),SEEN);CapabilityResolver.Runtime rt=parked();rt.owner=false;
        check(decide("pin",ut5,s,rt).reason==CapabilityResolver.Reason.OWNER_ONLY&&decide("pin",ut5,s,rt).reasonText().equals("Owner only"),"Shared role: PIN is owner only");
        check(decide("proximity_key",ut5,s,rt).reason==CapabilityResolver.Reason.OWNER_ONLY,"Shared role: proximity is owner only");
        check(decide("cruise_control",ut5,s,rt).available()&&CapabilityResolver.admit("lock",1,ut5,s,rt).available(),"Shared role keeps ordinary controls");
    }

    private static void admission() {
        DeviceDescriptor ut5=serial("Q2538ZZ9US7777777");StatusFields s=status(full(),SEEN);
        check(CapabilityResolver.admit("maximum_speed",50,ut5,s,parked()).available()&&CapabilityResolver.admit("maximum_speed",60,ut5,s,parked()).reason==CapabilityResolver.Reason.MODEL_EXCLUDED,"Value domain");
        check(CapabilityResolver.admit("range_algorithm",3,ut5,s,parked()).available()&&!CapabilityResolver.admit("range_algorithm",1,ut5,s,parked()).available(),"Only offered range choices");
        check(CapabilityResolver.admit("speed_limit",0,ut5,s,parked()).available()&&!CapabilityResolver.admit("speed_limit",7,ut5,s,parked()).available(),"Cap Off or 8+");
        CapabilityResolver.Runtime rt=parked();rt.bonded=false;
        check(CapabilityResolver.admit("proximity_key",1,ut5,s,rt).reason==CapabilityResolver.Reason.NEEDS_BOND&&CapabilityResolver.admit("proximity_key",0,ut5,s,rt).available(),"Proximity enable needs a bond");
        rt=parked();rt.pinEnabledFresh=false;
        check(CapabilityResolver.admit("proximity_key",1,ut5,s,rt).reason==CapabilityResolver.Reason.NEEDS_PIN,"Proximity enable needs an enabled PIN");
        CapabilityResolver.Decision refused=CapabilityResolver.admit("maximum_speed",60,ut5,s,parked());
        check(refused.support==CapabilityResolver.Support.KNOWN_ENCODING&&refused.variant!=null&&refused.evidence==CapabilityResolver.Evidence.VOLT_VERIFIED,"Refusal keeps support and evidence");
    }

    private static void simulation() {
        CapabilityResolver.Runtime rt=new CapabilityResolver.Runtime();rt.simulation=true;rt.ready=true;rt.nowMs=NOW;
        DeviceDescriptor sim=DeviceDescriptor.simulated("",0);
        for(CapabilityResolver.Decision decision:CapabilityResolver.all(sim,StatusFields.NONE,rt))check(decision.available(),"Simulation needs only ready + idle "+decision.feature);
        check(CapabilityResolver.admit("lock",1,sim,StatusFields.NONE,rt).available()&&!CapabilityResolver.admit("maximum_speed",60,sim,StatusFields.NONE,rt).available(),"Simulation keeps the variant domain");
        rt.busy=true;check(decide("lock",sim,StatusFields.NONE,rt).reason==CapabilityResolver.Reason.BUSY,"Simulation busy");
        rt.busy=false;rt.ready=false;check(decide("lock",sim,StatusFields.NONE,rt).reason==CapabilityResolver.Reason.NOT_CONNECTED,"Simulation not ready");
        rt.ready=true;check(decide("cruise_control",DeviceDescriptor.simulated("39991",0),StatusFields.NONE,rt).reason==CapabilityResolver.Reason.MODEL_NOT_MAPPED,"Simulated unknown model");
        // Decisions expose no frame builder: encoding stays with the variant and the caller.
        for(java.lang.reflect.Method m:CapabilityResolver.Decision.class.getMethods())check(m.getReturnType()!=byte[].class,"Decision builds no frames: "+m.getName());
    }

    private static void copy() {
        String[][] texts={{"STATUS_STALE","Waiting for fresh status"},{"BUSY","Wait for the current change"},{"NO_MOTION_REPORT","No motion report yet"},
            {"WARNING_ACTIVE","Resolve scooter warning first"},{"LOCKED","Unlock first"},{"NEEDS_BOND","Pair this phone first"},{"NEEDS_PIN","Enable the handlebar PIN first"},
            {"FIRMWARE_UNKNOWN","Firmware not read yet"},{"FIRMWARE_OLD","Needs newer scooter firmware"},{"FULL_PID_NEEDED","Identify model first"},{"MODEL_EXCLUDED","Not on this model"},
            {"REGION_RULE","Not offered for this region"},{"DISCOVERY_ACTIVE","Checking compatibility…"},{"TEST_ACTIVE","Test in progress"},{"NO_CHOICES","No choices reported"},{"NONE",""}};
        for(String[] t:texts)check(CapabilityResolver.text(CapabilityResolver.Reason.valueOf(t[0])).equals(t[1]),"Reason copy "+t[0]);
        for(CapabilityResolver.Reason r:CapabilityResolver.Reason.values())check(r==CapabilityResolver.Reason.NONE||!CapabilityResolver.text(r).isEmpty(),"Every reason has copy "+r);
    }

    /** The OEM home-screen family (registry version 2) for identified OEM groups, row by row. */
    private static void oemHomeScreen() {
        byte[] data=full();data[2]=0;data[35]=0;StatusFields s=status(data,SEEN);
        DeviceDescriptor sGroup=ModelRegistryTest.both("Q2443ZZ9US7777777","24431"),q=ModelRegistryTest.both("Q2585ZZ9DE7777777","25851"),m=ModelRegistryTest.both("Q2504ZZ9US7777777","25041");
        String[] home={"lock","display_units","range_algorithm","clock_sync","headlight","auto_sensor","turn_sound","cruise_control","energy_recovery"};
        for(CapabilityResolver.Decision decision:CapabilityResolver.all(sGroup,s,parked())){
            boolean mapped=Arrays.asList(home).contains(decision.feature);
            check(mapped?decision.support==CapabilityResolver.Support.KNOWN_ENCODING:decision.support==CapabilityResolver.Support.UNKNOWN_MAPPING&&decision.reason==CapabilityResolver.Reason.MODEL_NOT_MAPPED,"OEM group S "+decision);
            if(mapped&&!decision.feature.equals("lock"))check(decision.evidence==CapabilityResolver.Evidence.OEM_MAPPED&&decision.variant.decode==FeatureVariant.Decode.EXACT||decision.feature.equals("clock_sync"),"OEM-mapped evidence, exact readback "+decision.feature);
            if(mapped)check(decision.available(),"Mapped and available on S/US "+decision);
        }
        // Units: 0 mph, 1 km at offset 7; never lock-gated.
        CapabilityResolver.Decision units=decide("display_units",sGroup,s,parked());
        check(Arrays.equals(units.values(),new int[]{0,1})&&Integer.valueOf(1).equals(units.current())&&!units.preconditions.contains(CapabilityResolver.Precondition.UNLOCKED),"Units");
        check(Arrays.equals(units.variant.encode(1),ScooterCommands.set("display_units",1)),"Units bytes are the shared wire encoding");
        byte[] locked=data.clone();locked[2]=1;StatusFields lockedStatus=status(locked,SEEN);
        check(decide("display_units",sGroup,lockedStatus,parked()).available()&&decide("headlight",sGroup,lockedStatus,parked()).reason==CapabilityResolver.Reason.LOCKED
            &&decide("cruise_control",sGroup,lockedStatus,parked()).reason==CapabilityResolver.Reason.LOCKED&&decide("energy_recovery",sGroup,lockedStatus,parked()).reason==CapabilityResolver.Reason.LOCKED,"Tiles need the scooter unlocked");
        check(decide("headlight",serial("Q2538ZZ9US7777777"),lockedStatus,parked()).available(),"The UT5 MAX keeps today's rules (no unlock gate)");
        // Range estimate sets from offset 15.
        int[][] sets={{0},{0,2,3},{4,2,3}};
        for(int set=0;set<3;set++){byte[] r=data.clone();r[15]=(byte)set;r[6]=0;
            check(Arrays.equals(decide("range_algorithm",sGroup,status(r,SEEN),parked()).values(),sets[set]),"Range set "+set);}
        byte[] personal=data.clone();personal[15]=0;personal[6]=0;
        for(String pid:new String[]{"22131","23321","27011","27451"})check(Arrays.equals(decide("range_algorithm",advertised(pid),status(personal,SEEN),parked()).values(),new int[]{0,1}),"Actual riding in set 0 for "+pid);
        byte[] none=data.clone();none[15]=3;check(decide("range_algorithm",sGroup,status(none,SEEN),parked()).reason==CapabilityResolver.Reason.NO_CHOICES,"Unknown range set offers nothing");
        // Clock: manual, no readback, not parked off the UT5 MAX.
        CapabilityResolver.Runtime moving=parked();moving.stationary=false;
        check(decide("clock_sync",sGroup,s,moving).available()&&decide("clock_sync",sGroup,s,parked()).current()==null,"OEM clock");
        // Tail light: all except M; region hiding deferred with a note.
        check(decide("headlight",m,s,parked()).reason==CapabilityResolver.Reason.MODEL_EXCLUDED&&decide("headlight",m,s,parked()).support==CapabilityResolver.Support.INCOMPATIBLE,"No tail light on M");
        check(decide("headlight",q,s,parked()).available()&&!decide("headlight",q,s,parked()).oemNote.isEmpty()&&decide("headlight",sGroup,s,parked()).oemNote.isEmpty(),"Tail light DE note, shown");
        check(!decide("headlight",ModelRegistryTest.both("Q2213ZZ9IT7777777","22131"),s,parked()).oemNote.isEmpty()&&decide("headlight",ModelRegistryTest.both("Q2443ZZ9IT7777777","24431"),s,parked()).oemNote.isEmpty(),"Tail light ITA rule outside the turn-sound group");
        // Automatic lights: all except M, 2332, 2417, 2422.
        for(String pid:new String[]{"23321","24171","24221","25051"})check(decide("auto_sensor",advertised(pid),s,parked()).reason==CapabilityResolver.Reason.MODEL_EXCLUDED,"No automatic lights on "+pid);
        check(decide("auto_sensor",advertised("24021"),s,parked()).available(),"Automatic lights on C");
        // Turn sound: the OEM's turn-sound group except 2573, 2634 and R.
        for(String pid:new String[]{"22131","23141","25731","26341","26231","27811","27821","25061","24171"})check(decide("turn_sound",advertised(pid),s,parked()).reason==CapabilityResolver.Reason.MODEL_EXCLUDED,"No turn sound on "+pid);
        check(decide("turn_sound",sGroup,s,parked()).available()&&!decide("turn_sound",q,s,parked()).oemNote.isEmpty(),"Turn sound, DE note");
        // Cruise: not M/2573/2634; SKU USA, or a known region with offset 35 == 1, else the region rule.
        for(String pid:new String[]{"25041","25731","26341"})check(decide("cruise_control",advertised(pid),s,parked()).reason==CapabilityResolver.Reason.MODEL_EXCLUDED,"No cruise on "+pid);
        CapabilityResolver.Decision eur=decide("cruise_control",q,s,parked());
        check(eur.reason==CapabilityResolver.Reason.REGION_RULE&&eur.support==CapabilityResolver.Support.KNOWN_ENCODING&&eur.reasonText().equals("Not offered for this region")&&!eur.oemNote.isEmpty(),"EUR cruise without the flag");
        check(decide("cruise_control",q,status(Arrays.copyOf(data,35),SEEN),parked()).reason==CapabilityResolver.Reason.REGION_RULE,"Offset 35 absent is not a flag");
        byte[] flagged=data.clone();flagged[35]=1;
        check(decide("cruise_control",q,status(flagged,SEEN),parked()).available(),"Cruise with offset 35 == 1");
        check(decide("cruise_control",advertised("25851"),status(flagged,SEEN),parked()).reason==CapabilityResolver.Reason.REGION_RULE,"No region: the flag cannot help");
        check(decide("cruise_control",ModelRegistryTest.both("Q2620ZZ9EU7777777","26201"),s,parked()).reason==CapabilityResolver.Reason.REGION_RULE,"2620 EU server branch fails closed");
        check(decide("cruise_control",sGroup,s,moving).reason==CapabilityResolver.Reason.MOVING,"Cruise needs the scooter parked");
        // Energy recovery: levels, Off only for the OEM list, no High for four prefixes in NL or without a region; M not mapped yet.
        check(Arrays.equals(decide("energy_recovery",q,s,parked()).values(),new int[]{0,30,60,90}),"2585 offers Off");
        CapabilityResolver.Decision noOff=decide("energy_recovery",sGroup,s,parked());
        check(Arrays.equals(noOff.values(),new int[]{30,60,90})&&noOff.variant.id.equals("energy_recovery.levels_no_off")&&!noOff.allows(0),"S has no Off");
        for(String pid:new String[]{"27011","25431","24021","24421","26191","23451","24411","26111","25451","27821"})
            check(decide("energy_recovery",ModelRegistryTest.both("Q"+pid.substring(0,4)+"ZZ9US7777777",pid),s,parked()).allows(0),"Off for "+pid);
        for(String pid:new String[]{"27141","27041","23451","24031"}){
            check(Arrays.equals(decide("energy_recovery",ModelRegistryTest.both("Q"+pid.substring(0,4)+"ZZ9NL7777777",pid),s,parked()).values(),new int[]{0,30,60}),"No High in NL for "+pid);
            check(Arrays.equals(decide("energy_recovery",ModelRegistryTest.both("Q"+pid.substring(0,4)+"ZZ9NE7777777",pid),s,parked()).values(),new int[]{0,30,60,90}),"NE is not NL for "+pid);
        }
        check(decide("energy_recovery",m,s,parked()).reason==CapabilityResolver.Reason.MODEL_NOT_MAPPED,"M energy (binary) not mapped yet");
        byte[] offNow=data.clone();offNow[5]=0;
        check(decide("energy_recovery",sGroup,status(offNow,SEEN),parked()).reason==CapabilityResolver.Reason.VALUE_UNKNOWN,"Off read back on S is outside its domain");
        // Every OEM-group variant encodes the same wire bytes as the shared UT5 MAX encoders.
        for(String key:new String[]{"display_units","headlight","auto_sensor","turn_sound","cruise_control","energy_recovery","range_algorithm"}){
            FeatureVariant v=decide(key,q,status(flagged,SEEN),parked()).variant;
            for(int value:v.values)check(Arrays.equals(v.encode(value),ScooterCommands.set(key,value)),"Same wire bytes "+key+"="+value);
        }
        // Serial-only and unknown-advertised models stay limited to the shared lock.
        check(decide("display_units",serial("Q2443ZZ9US7777777"),s,parked()).reason==CapabilityResolver.Reason.MODEL_NOT_IDENTIFIED
            &&decide("display_units",advertised("39991"),s,parked()).reason==CapabilityResolver.Reason.MODEL_NOT_MAPPED,"Shared layer: no units before a guided test");
        check(CapabilityResolver.all(serial("Q2538ZZ9US7777777"),s,parked()).size()==ScooterCommands.catalog().size()+1,"Catalogue unchanged");
    }

    private static void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}
}
