package local.navee.offlinelock;

import java.util.Arrays;

/** One admission policy for every send path: model × request table, origins, the unlock exception and the transmit guard. */
public final class MutationGateTest {
    private static int assertions;
    private static final long SEEN=100000,NOW=SEEN+1000;
    public static void main(String[] args) {
        table(); unlockRecovery(); origins(); dedicatedKinds(); pinRead(); simulation(); transmit();
        System.out.println("MutationGate tests passed: "+assertions+" assertions");
    }

    private static StatusFields status(){byte[] d=CapabilityResolverTest.full();d[2]=0;d[35]=0;return CapabilityResolverTest.status(d,SEEN);}
    private static DeviceDescriptor serial(String s){return ModelRegistryTest.serial(s);}
    private static DeviceDescriptor advertised(String pid){return ModelRegistryTest.advertised(pid);}
    private static DeviceDescriptor both(String s,String pid){return ModelRegistryTest.both(s,pid);}
    private static MutationGate.Request set(String key,int value){return new MutationGate.Request(MutationGate.Kind.SET,key,value,MutationGate.Origin.USER);}
    private static MutationGate.Request kind(MutationGate.Kind k,int value){return new MutationGate.Request(k,"",value,MutationGate.Origin.USER);}
    private static CapabilityResolver.Reason reason(MutationGate.Request r,DeviceDescriptor d,StatusFields s,CapabilityResolver.Runtime rt){
        CapabilityResolver.Decision decision=MutationGate.check(r,d,s,rt);
        check(decision.available()==(decision.reason==CapabilityResolver.Reason.NONE&&decision.support==CapabilityResolver.Support.KNOWN_ENCODING),"available() matches "+r);
        return decision.available()?CapabilityResolver.Reason.NONE:decision.reason;
    }

    /** Each fixture × each kind and representative feature/value against the expected reason (NONE = admitted). */
    private static void table() {
        MutationGate.Request[] requests={set("lock",1),set("lock",0),set("display_units",1),set("cruise_control",0),set("energy_recovery",30),set("charge_limit",90),
            kind(MutationGate.Kind.CHARGE_START,0),kind(MutationGate.Kind.CLOCK,0),kind(MutationGate.Kind.PIN_SET,1),kind(MutationGate.Kind.PIN_ENABLE,1),
            kind(MutationGate.Kind.PIN_READ,0),kind(MutationGate.Kind.BOND,1),kind(MutationGate.Kind.PROXIMITY,1),
            new MutationGate.Request(MutationGate.Kind.SET,"range_algorithm",2,MutationGate.Origin.AUTOMATIC_RANGE_FOLLOW)};
        String A="NONE",NM="MODEL_NOT_MAPPED",NI="MODEL_NOT_IDENTIFIED",IC="IDENTITY_CONFLICT",EX="MODEL_EXCLUDED",RR="REGION_RULE",PU="PRODUCT_UNSUPPORTED",TU="TRANSPORT_UNSUPPORTED";
        Object[][] rows={
            // fixture, lock1, lock0, units, cruise, energy, charge_limit, charge_start, clock, pin set, pin enable, pin read, bond, proximity, auto range
            {serial("Q2538ZZ9US7777777"),A,A,A,A,A,A,A,A,A,A,A,A,A,A},
            // Every 2538 keeps today's behaviour regardless of region (owner decision).
            {serial("Q2538ZZ9DE7777777"),A,A,A,A,A,A,A,A,A,A,A,A,A,A},
            {both("Q2538ZZ9US7777777","24431"),A,A,A,A,A,A,A,A,A,A,A,A,A,A},
            // C5: charging (S, Q, H) and the Bluetooth lock screen (PIN, bond, proximity) are OEM-mapped; group M reaches neither.
            {both("Q2443ZZ9US7777777","24431"),A,A,A,A,A,A,A,A,A,A,A,A,A,EX},
            {both("Q2585ZZ9DE7777777","25851"),A,A,A,RR,A,A,A,A,A,A,A,A,A,EX},
            {both("Q2504ZZ9US7777777","25041"),A,A,A,EX,NM,EX,EX,A,NM,NM,NM,NM,NM,EX},
            {advertised("39991"),A,A,NM,NM,NM,NM,NM,NM,NM,NM,NM,NM,NM,EX},
            {serial("Q2443ZZ9US7777777"),A,A,NI,NI,NI,NI,NI,NI,NI,NI,NI,NI,NI,EX},
            {both("Q2547ZZ9US7777777","25381"),A,A,IC,IC,IC,IC,IC,IC,IC,IC,IC,IC,IC,EX},
            {advertised("24351"),PU,PU,PU,PU,PU,PU,PU,PU,PU,PU,PU,PU,PU,EX},
            {advertised("27361"),TU,TU,TU,TU,TU,TU,TU,TU,TU,TU,TU,TU,TU,EX},
        };
        StatusFields s=status();
        for(Object[] row:rows){
            DeviceDescriptor d=(DeviceDescriptor)row[0];
            for(int i=0;i<requests.length;i++)
                check(reason(requests[i],d,s,CapabilityResolverTest.parked()).name().equals(row[i+1]),d+" "+requests[i]+" expected "+row[i+1]+" got "+reason(requests[i],d,s,CapabilityResolverTest.parked()));
        }
        // A conflicting advertisement never demotes a serial-2538 UT5 MAX: it stays the exact layer, with a diagnostic note.
        ModelRegistry.Resolution owner=ModelRegistry.resolve(both("Q2538ZZ9US7777777","24431"));
        check(owner.layer==ModelRegistry.Layer.UT5_MAX&&owner.conflict&&owner.note.equals("Model ID mismatch"),"Serial 2538 keeps the UT5 MAX layer");
    }

    /** The recovery exception: unlock needs only a supported layer, a ready session, fresh status with the lock field and no pending change. */
    private static void unlockRecovery() {
        StatusFields s=status();
        for(DeviceDescriptor d:new DeviceDescriptor[]{serial("Q2538ZZ9US7777777"),both("Q2443ZZ9US7777777","24431"),advertised("39991"),serial("Q2443ZZ9US7777777")}){
            CapabilityResolver.Runtime rt=CapabilityResolverTest.parked();rt.stationary=false;rt.motionSeen=false;rt.warningActive=true;
            check(reason(set("lock",1),d,s,rt)==CapabilityResolver.Reason.NO_MOTION_REPORT,"Lock needs a motion report "+d);
            check(reason(set("lock",0),d,s,rt)==CapabilityResolver.Reason.NONE,"Unlock without a motion report or with a warning "+d);
            rt.motionSeen=true;check(reason(set("lock",1),d,s,rt)==CapabilityResolver.Reason.MOVING&&reason(set("lock",0),d,s,rt)==CapabilityResolver.Reason.NONE,"Unlock while moving "+d);
            rt=CapabilityResolverTest.parked();rt.warningActive=true;check(reason(set("lock",1),d,s,rt)==CapabilityResolver.Reason.WARNING_ACTIVE,"Warning blocks locking "+d);
            rt=CapabilityResolverTest.parked();rt.busy=true;check(reason(set("lock",0),d,s,rt)==CapabilityResolver.Reason.BUSY,"Unlock waits for a pending change "+d);
            rt=CapabilityResolverTest.parked();rt.ready=false;check(reason(set("lock",0),d,s,rt)==CapabilityResolver.Reason.NOT_CONNECTED,"Unlock needs a ready session "+d);
            rt=CapabilityResolverTest.parked();rt.nowMs=SEEN+7001;check(reason(set("lock",0),d,s,rt)==CapabilityResolver.Reason.STATUS_STALE,"Cached status never authorises unlock "+d);
            check(reason(set("lock",0),d,CapabilityResolverTest.status(new byte[2],SEEN),CapabilityResolverTest.parked())==CapabilityResolver.Reason.FIELD_ABSENT,"Unlock needs the lock field "+d);
        }
        for(String pid:new String[]{"24351","27361","27391","27681"}){
            check(!MutationGate.check(set("lock",0),advertised(pid),s,CapabilityResolverTest.parked()).available()
                &&!MutationGate.check(set("lock",1),advertised(pid),s,CapabilityResolverTest.parked()).available(),"No lock either way on "+pid);
        }
    }

    private static void origins() {
        StatusFields s=status();DeviceDescriptor ut5=serial("Q2538ZZ9US7777777");
        MutationGate.Request auto=new MutationGate.Request(MutationGate.Kind.SET,"range_algorithm",2,MutationGate.Origin.AUTOMATIC_RANGE_FOLLOW);
        check(reason(auto,ut5,s,CapabilityResolverTest.parked())==CapabilityResolver.Reason.NONE,"Range follow on the UT5 MAX");
        CapabilityResolver.Runtime rt=CapabilityResolverTest.parked();rt.discovery=true;
        check(reason(auto,ut5,s,rt)==CapabilityResolver.Reason.DISCOVERY_ACTIVE,"No range follow during discovery");
        rt=CapabilityResolverTest.parked();rt.test=true;rt.testFeature="range_algorithm";
        check(reason(auto,ut5,s,rt)==CapabilityResolver.Reason.TEST_ACTIVE,"No range follow during a test");
        for(DeviceDescriptor d:new DeviceDescriptor[]{both("Q2443ZZ9US7777777","24431"),both("Q2585ZZ9US7777777","25851"),advertised("39991"),serial("Q2443ZZ9US7777777"),advertised("24351")})
            check(reason(auto,d,s,CapabilityResolverTest.parked())==CapabilityResolver.Reason.MODEL_EXCLUDED,"Range follow refused off the UT5 MAX layer "+d);
        check(reason(new MutationGate.Request(MutationGate.Kind.SET,"cruise_control",1,MutationGate.Origin.AUTOMATIC_RANGE_FOLLOW),ut5,s,CapabilityResolverTest.parked())==CapabilityResolver.Reason.MODEL_EXCLUDED,"Range follow writes only the range estimate");
        for(MutationGate.Origin guided:new MutationGate.Origin[]{MutationGate.Origin.GUIDED_TEST,MutationGate.Origin.GUIDED_RESTORE})
            check(reason(new MutationGate.Request(MutationGate.Kind.SET,"display_units",1,guided),ut5,s,CapabilityResolverTest.parked())==CapabilityResolver.Reason.TEST_ACTIVE,"No guided writes without a journal "+guided);
        check(new MutationGate.Request(MutationGate.Kind.SET,"lock",1,null).origin==MutationGate.Origin.USER,"Missing origin is a user request");
    }

    /** PIN, time, clock and proximity have dedicated kinds; SET refuses them. Kinds map to one feature. */
    private static void dedicatedKinds() {
        StatusFields s=status();DeviceDescriptor ut5=serial("Q2538ZZ9US7777777");
        for(String key:new String[]{"pin","charge_start","clock_sync","proximity_key"})
            check(reason(set(key,1),ut5,s,CapabilityResolverTest.parked())==CapabilityResolver.Reason.MODEL_EXCLUDED,"SET refuses dedicated "+key);
        String[][] map={{"CHARGE_START","charge_start"},{"CLOCK","clock_sync"},{"PIN_SET","pin"},{"PIN_ENABLE","pin"},{"PIN_READ","pin"},{"BOND","proximity_key"},{"PROXIMITY","proximity_key"},{"SET","lock"}};
        for(String[] m:map)check(new MutationGate.Request(MutationGate.Kind.valueOf(m[0]),"lock",0,MutationGate.Origin.USER).feature.equals(m[1]),"Kind feature "+m[0]);
        CapabilityResolver.Runtime rt=CapabilityResolverTest.parked();rt.bonded=false;
        check(reason(kind(MutationGate.Kind.PROXIMITY,1),ut5,s,rt)==CapabilityResolver.Reason.NEEDS_BOND&&reason(kind(MutationGate.Kind.PROXIMITY,0),ut5,s,rt)==CapabilityResolver.Reason.NONE,"Proximity enable needs a bond");
        rt=CapabilityResolverTest.parked();rt.pinEnabledFresh=false;
        check(reason(kind(MutationGate.Kind.PROXIMITY,1),ut5,s,rt)==CapabilityResolver.Reason.NEEDS_PIN,"Proximity enable needs a fresh enabled PIN");
        rt=CapabilityResolverTest.parked();rt.owner=false;
        for(MutationGate.Kind k:new MutationGate.Kind[]{MutationGate.Kind.PIN_SET,MutationGate.Kind.PIN_ENABLE,MutationGate.Kind.PIN_READ,MutationGate.Kind.BOND,MutationGate.Kind.PROXIMITY})
            check(reason(kind(k,1),ut5,s,rt)==CapabilityResolver.Reason.OWNER_ONLY,"Owner only "+k);
        check(reason(set("cruise_control",1),ut5,s,rt)==CapabilityResolver.Reason.NONE,"Shared role keeps ordinary controls");
        rt=CapabilityResolverTest.parked();rt.stationary=false;
        for(MutationGate.Kind k:new MutationGate.Kind[]{MutationGate.Kind.CHARGE_START,MutationGate.Kind.CLOCK,MutationGate.Kind.PIN_SET,MutationGate.Kind.BOND})
            check(reason(kind(k,1),ut5,s,rt)==CapabilityResolver.Reason.MOVING,"UT5 MAX parked rule "+k);
        // Non-UT5 clock: the OEM sends it from any state, so Volt's manual button needs no parked state there.
        check(reason(kind(MutationGate.Kind.CLOCK,0),both("Q2443ZZ9US7777777","24431"),s,rt)==CapabilityResolver.Reason.NONE,"OEM-group clock while moving");
        // Value domain per model: Off is not an OEM energy level for group S.
        check(reason(set("energy_recovery",0),both("Q2443ZZ9US7777777","24431"),s,CapabilityResolverTest.parked())==CapabilityResolver.Reason.MODEL_EXCLUDED,"Energy Off refused for S");
        check(reason(set("energy_recovery",0),both("Q2585ZZ9US7777777","25851"),s,CapabilityResolverTest.parked())==CapabilityResolver.Reason.NONE,"Energy Off for 2585");
    }

    private static void pinRead() {
        StatusFields s=status();DeviceDescriptor ut5=serial("Q2538ZZ9US7777777");CapabilityResolver.Runtime rt=CapabilityResolverTest.parked();rt.stationary=false;rt.motionSeen=false;
        check(reason(kind(MutationGate.Kind.PIN_READ,0),ut5,s,rt)==CapabilityResolver.Reason.NONE,"A PIN read is allowed while riding");
        rt.busy=true;check(reason(kind(MutationGate.Kind.PIN_READ,0),ut5,s,rt)==CapabilityResolver.Reason.BUSY,"No PIN read during a change");
        rt=CapabilityResolverTest.parked();rt.ready=false;check(reason(kind(MutationGate.Kind.PIN_READ,0),ut5,s,rt)==CapabilityResolver.Reason.NOT_CONNECTED,"PIN read needs a ready session");
    }

    private static void simulation() {
        CapabilityResolver.Runtime rt=new CapabilityResolver.Runtime();rt.simulation=true;rt.ready=true;rt.nowMs=NOW;
        check(MutationGate.check(set("cruise_control",1),DeviceDescriptor.simulated("",0),StatusFields.NONE,rt).available(),"UT5 MAX simulator keeps its controls");
        check(MutationGate.check(set("lock",1),DeviceDescriptor.simulated("39991",0),StatusFields.NONE,rt).available()
            &&MutationGate.check(set("cruise_control",1),DeviceDescriptor.simulated("39991",0),StatusFields.NONE,rt).reason==CapabilityResolver.Reason.MODEL_NOT_MAPPED,"Unknown simulator model: lock only");
        check(MutationGate.check(set("display_units",1),DeviceDescriptor.simulated("24431",0),StatusFields.NONE,rt).available()
            &&MutationGate.check(set("drive_speed_limit",10),DeviceDescriptor.simulated("24431",0),StatusFields.NONE,rt).reason==CapabilityResolver.Reason.MODEL_NOT_MAPPED,"Mapped simulator model");
        for(java.lang.reflect.Method m:MutationGate.class.getDeclaredMethods())check(m.getReturnType()!=byte[].class,"The gate builds no frames: "+m.getName());
    }

    private static void transmit() {
        byte[] read=Protocol.frame(0x70,null),pinRead=ScooterCommands.readPin(true);
        byte[] auth=Protocol.auth(new byte[6],1,0);
        byte[] lock=ScooterCommands.set("lock",1),unlock=ScooterCommands.set("lock",0),units=ScooterCommands.set("display_units",1);
        check(MutationGate.mayTransmit(read,null)&&MutationGate.mayTransmit(pinRead,null)&&MutationGate.mayTransmit(auth,null),"Reads and auth always pass");
        byte[] challenge=auth.clone();challenge[3]=0x31;check(MutationGate.mayTransmit(challenge,null),"Challenge replies (longer than 7 bytes) pass");
        check(MutationGate.mayTransmit(lock,lock.clone()),"The admitted mutation passes");
        check(!MutationGate.mayTransmit(lock,null),"A write without an admitted mutation is dropped");
        check(!MutationGate.mayTransmit(unlock,lock),"Same opcode with a different value is dropped");
        check(!MutationGate.mayTransmit(units,lock),"Another write is dropped");
        check(!MutationGate.mayTransmit(null,lock)&&!MutationGate.mayTransmit(new byte[3],null),"Malformed frames are dropped");
        check(!MutationGate.mayTransmit(ScooterCommands.setClock(1700000000L,0),ScooterCommands.setChargeStart(1700000000L,0)),"Clock is not the admitted charge start");
        check(Arrays.equals(lock,ScooterCommands.set("lock",1)),"Guard never alters bytes");
    }

    private static void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}
}
