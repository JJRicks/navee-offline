package com.jjricks.volt;

import java.util.Arrays;

/**
 * The single admission rule for every scooter write. BleController asks check() immediately
 * before queueing a change (and again just before sending it), and send() asks mayTransmit() for
 * every frame, so a future path that skips check() fails closed. Pure; it builds no frames.
 */
final class MutationGate {
    enum Kind { SET, CHARGE_START, CLOCK, PIN_SET, PIN_ENABLE, PIN_READ, BOND, PROXIMITY }
    enum Origin { USER, AUTOMATIC_RANGE_FOLLOW, GUIDED_TEST, GUIDED_RESTORE }
    static final class Request {
        final Kind kind; final String feature; final int value; final Origin origin;
        Request(Kind k,String f,int v,Origin o){kind=k;origin=o==null?Origin.USER:o;value=v;feature=feature(k,f);}
        @Override public String toString(){return kind+" "+feature+"="+value+" "+origin;}
    }
    private MutationGate() {}

    /** Kind → feature: CHARGE_START "charge_start", CLOCK "clock_sync", PIN_* "pin", BOND/PROXIMITY "proximity_key". */
    static String feature(Kind kind,String key){
        switch(kind){
            case CHARGE_START: return "charge_start";
            case CLOCK: return "clock_sync";
            case PIN_SET: case PIN_ENABLE: case PIN_READ: return "pin";
            case BOND: case PROXIMITY: return "proximity_key";
            default: return key;
        }
    }

    static CapabilityResolver.Decision check(Request r,DeviceDescriptor d,StatusFields s,CapabilityResolver.Runtime rt){
        if(r.origin==Origin.GUIDED_TEST||r.origin==Origin.GUIDED_RESTORE)return guided(r,d,s,rt);
        CapabilityResolver.Decision decision;
        switch(r.kind){
            case SET: {
                decision=CapabilityResolver.admit(r.feature,r.value,d,s,rt);
                // PIN, time, clock and proximity writes have dedicated kinds and builders.
                ScooterCommands.Feature f=r.feature.equals("clock_sync")?null:ScooterCommands.find(r.feature);
                if(decision.available()&&(f==null||f.sensitive||f.type==ScooterCommands.Type.TIME||f.requiresBond))
                    decision=CapabilityResolver.withReason(decision,CapabilityResolver.Reason.MODEL_EXCLUDED);
                break;
            }
            case PROXIMITY: decision=CapabilityResolver.admit(r.feature,r.value,d,s,rt); break;
            case PIN_READ: {
                // A PIN read changes nothing on the scooter, so riding does not block it.
                decision=CapabilityResolver.decide(r.feature,d,s,rt);
                if(decision.reason==CapabilityResolver.Reason.MOVING||decision.reason==CapabilityResolver.Reason.NO_MOTION_REPORT)
                    decision=CapabilityResolver.withReason(decision,CapabilityResolver.Reason.NONE);
                break;
            }
            default: decision=CapabilityResolver.decide(r.feature,d,s,rt);
        }
        switch(r.origin){
            case AUTOMATIC_RANGE_FOLLOW:
                // Range follow relies on UT5 MAX drive-mode codes, so it never writes to another layer.
                if(decision.layer!=ModelRegistry.Layer.UT5_MAX||r.kind!=Kind.SET||!r.feature.equals("range_algorithm"))
                    return CapabilityResolver.withReason(decision,CapabilityResolver.Reason.MODEL_EXCLUDED);
                if(rt!=null&&rt.discovery)return CapabilityResolver.withReason(decision,CapabilityResolver.Reason.DISCOVERY_ACTIVE);
                if(rt!=null&&rt.test)return CapabilityResolver.withReason(decision,CapabilityResolver.Reason.TEST_ACTIVE);
                return decision;
            default: return decision;
        }
    }

    /**
     * A guided test writes one value of the feature it is running (BleController sets rt.test and
     * rt.testFeature only while that test's journal entry is committed), through the guided-test
     * rule and its preconditions. Any other kind, feature or state is refused.
     */
    private static CapabilityResolver.Decision guided(Request r,DeviceDescriptor d,StatusFields s,CapabilityResolver.Runtime rt){
        CapabilityResolver.Decision decision=CapabilityResolver.admitTest(r.feature,r.value,d,s,rt);
        if(r.kind!=Kind.SET)return CapabilityResolver.withReason(decision,CapabilityResolver.Reason.MODEL_EXCLUDED);
        if(rt==null||!rt.test)return CapabilityResolver.withReason(decision,CapabilityResolver.Reason.NO_TEST);
        if(!r.feature.equals(rt.testFeature))return CapabilityResolver.withReason(decision,CapabilityResolver.Reason.TEST_ACTIVE);
        return decision;
    }

    /** Reads (7-byte frames without a length byte), 0x30 and 0x31 always pass; any other frame only if it is the admitted mutation. */
    static boolean mayTransmit(byte[] frame,byte[] admittedMutationBytes){
        if(frame==null||frame.length<7)return false;
        int cmd=frame[3]&255;
        if(frame.length==7||cmd==0x30||cmd==0x31)return true;
        return admittedMutationBytes!=null&&Arrays.equals(frame,admittedMutationBytes);
    }
}
