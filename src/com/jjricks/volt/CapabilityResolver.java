package com.jjricks.volt;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * One explicit decision per feature: implementation support (from ModelRegistry), availability
 * now (model, runtime and state conditions) and evidence. Pure and Android-free; it never builds
 * a frame. Cached data never authorises: every decision needs this session's fresh status.
 */
public final class CapabilityResolver {
    public enum Support { KNOWN_ENCODING, UNKNOWN_MAPPING, INCOMPATIBLE }
    public enum Evidence { VOLT_VERIFIED, VOLT_LAB, OEM_MAPPED, SHARED_CONTRACT, LOCAL_TEST, NONE }
    public enum Precondition { FRESH_STATUS, STATIONARY, MOTION_REPORT, NO_WARNING, OWNER, UNLOCKED, BONDED, PIN_ENABLED, FIRMWARE_READ, FULL_PID }
    /** REGION_RULE remains readable for older reports; the current registry does not return it. */
    public enum Reason { NONE, NOT_CONNECTED, STATUS_STALE, FIELD_ABSENT, VALUE_UNKNOWN, NO_CHOICES, BUSY, MOVING, NO_MOTION_REPORT,
        WARNING_ACTIVE, LOCKED, OWNER_ONLY, NEEDS_BOND, NEEDS_PIN, FIRMWARE_UNKNOWN, FIRMWARE_OLD, FULL_PID_NEEDED,
        IDENTITY_CONFLICT, MODEL_NOT_IDENTIFIED, MODEL_NOT_MAPPED, MODEL_EXCLUDED, REGION_RULE,
        PRODUCT_UNSUPPORTED, TRANSPORT_UNSUPPORTED, DISCOVERY_ACTIVE, TEST_ACTIVE,
        NOT_TESTABLE, RECORDING, NEEDS_REAL_SCOOTER, PARKED_UNCONFIRMED, NO_TEST }
    /** Status older than this is stale (BleController.ready() uses the same bound). */
    static final long STATUS_MAX_AGE_MS=7000;

    /** Plain data copied from BleController for one evaluation. */
    public static final class Runtime {
        public boolean simulation,ready,busy,stationary,motionSeen,warningActive,owner,bonded,pinEnabledFresh,discovery,test;
        /** The feature a guided test is exercising. While test is true every ordinary change reports TEST_ACTIVE; only that test's own writes pass. */
        public String testFeature="";
        /** A ride is being recorded; guided tests do not start or write then. */
        public boolean recording;
        /** The owner ticked "Scooter is parked" because no motion report has arrived this session. */
        public boolean parkedConfirmed;
        /** The running test is an Experimental controls try: its own writes use the try rule (decideTry), not the guided-test one. */
        public boolean testTry;
        public long nowMs;
    }

    public static final class Decision {
        public final String feature; public final Support support; public final Reason reason;
        public final FeatureVariant variant;          // null unless KNOWN_ENCODING
        public final EnumSet<Precondition> preconditions;
        public final Evidence evidence; public final String oemNote; public final int registryVersion;
        public final ModelRegistry.Layer layer;
        private final StatusFields status; private final int[] choices;
        Decision(String feature,ModelRegistry.Layer layer,ModelRegistry.Rule rule,Reason reason,StatusFields status,int[] choices){
            this.feature=feature;this.layer=layer;this.support=rule.support;this.reason=reason;this.variant=rule.variant;
            this.preconditions=EnumSet.copyOf(rule.preconditions);
            this.evidence=rule.evidence;this.oemNote=rule.oemNote;this.registryVersion=ModelRegistry.VERSION;
            this.status=status==null?StatusFields.NONE:status;this.choices=choices==null?new int[0]:choices;
        }
        public boolean available(){return support==Support.KNOWN_ENCODING&&reason==Reason.NONE;}
        /** An Experimental controls row: a known encoding with a Lab variant (decideExperimental decides when it may be tried). */
        public boolean experimental(){return support==Support.KNOWN_ENCODING&&variant!=null&&variant.lab;}
        /**
         * Refused only because the scooter is moving, has sent no motion report yet, or (lock) has a
         * warning. The UI keeps such a control tappable so the tap can say why; admission still refuses.
         */
        public boolean blockedByRide(){return support==Support.KNOWN_ENCODING&&(reason==Reason.MOVING||reason==Reason.NO_MOTION_REPORT||reason==Reason.WARNING_ACTIVE);}
        /** Offered values (empty for a slider: use variant.min/max/step). */
        public int[] values(){return choices.clone();}
        public String[] labels(){String[] out=new String[choices.length];for(int i=0;i<choices.length;i++)out[i]=label(choices[i]);return out;}
        public String label(int v){return variant==null?Integer.toString(v):variant.label(v);}
        /** Another model's variant label (never the UT5 MAX catalogue's), else null; speeds keep the rider's units. */
        public String oemLabel(int v){
            if(variant==null||variant.decode==FeatureVariant.Decode.UT5_FEATURE)return null;
            ScooterCommands.Feature f=ScooterCommands.find(feature);
            return "km/h".equals(f==null?variant.unit:f.unit)?null:variant.label(v);
        }
        public boolean allows(int v){
            if(variant==null||!variant.allows(v))return false;
            if(!variant.dynamicChoices)return true;
            for(int c:choices)if(c==v)return true;return false;
        }
        public String reasonText(){return text(reason);}
        /** variant.read(status) or null. */
        public Integer current(){return variant==null?null:variant.read(status);}
        @Override public String toString(){return feature+":"+support+"/"+reason+(variant==null?"":"/"+variant.id);}
    }

    private CapabilityResolver() {}

    /** The first failing condition sets the reason; the support level always comes from the registry. */
    public static Decision decide(String feature,DeviceDescriptor d,StatusFields s,Runtime rt){
        StatusFields status=s==null?StatusFields.NONE:s;
        ModelRegistry.Resolution r=ModelRegistry.resolve(d);
        ModelRegistry.Rule rule=ModelRegistry.rule(feature,d,r,status);
        // 1-2. Registry and model conditions (product, transport, identity, mapping, firmware, full PID).
        // Regional OEM UI policy is recorded as provenance; it does not refuse a control.
        if(rule.support!=Support.KNOWN_ENCODING||rule.reason!=Reason.NONE)return new Decision(feature,r.layer,rule,rule.reason,status,null);
        FeatureVariant v=rule.variant;
        if(rt==null||!rt.ready)return new Decision(feature,r.layer,rule,Reason.NOT_CONNECTED,status,v.choices(status));
        // Simulation keeps today's behaviour: ready, not busy, and the variant domain. It never builds frames.
        if(rt.simulation){
            int[] values=v.dynamicChoices&&status.accepted()?v.choices(status):v.values.clone();
            return new Decision(feature,r.layer,rule,rt.busy?Reason.BUSY:Reason.NONE,status,values);
        }
        // 3. Runtime.
        Reason reason=Reason.NONE;int[] choices=null;
        if(rt.discovery)reason=Reason.DISCOVERY_ACTIVE;
        else if(rt.test)reason=Reason.TEST_ACTIVE;
        else if(rt.busy)reason=Reason.BUSY;
        else if(!status.fresh(rt.nowMs,STATUS_MAX_AGE_MS))reason=Reason.STATUS_STALE;
        else {
            Integer current=v.readable?v.read(status):null;
            if(v.readable&&current==null)reason=Reason.FIELD_ABSENT;
            // UT5 MAX keeps today's rule: only Lab rows refuse an out-of-domain readback (e.g. a 0 sentinel), so the owner can set a valid value.
            else if(current!=null&&!v.allows(current)&&(r.layer!=ModelRegistry.Layer.UT5_MAX||v.lab))reason=Reason.VALUE_UNKNOWN;
            else {choices=v.choices(status);if(v.dynamicChoices&&choices.length==0)reason=Reason.NO_CHOICES;}
        }
        // 4. Preconditions.
        if(reason==Reason.NONE)reason=preconditions(rule.preconditions,status,rt);
        return new Decision(feature,r.layer,rule,reason,status,choices==null?v.choices(status):choices);
    }

    /** Value-specific final check. Unlock (lock 0) follows the recovery contract instead of decide(). */
    public static Decision admit(String feature,int value,DeviceDescriptor d,StatusFields s,Runtime rt){
        StatusFields status=s==null?StatusFields.NONE:s;
        if(feature.equals("lock")&&value==0&&rt!=null&&!rt.simulation){
            // Recovery: an owner can always unlock offline. Supported layer, authenticated and ready,
            // the lock field present in a fresh status, and no pending change. No motion or warning checks.
            ModelRegistry.Resolution r=ModelRegistry.resolve(d);ModelRegistry.Rule rule=ModelRegistry.rule(feature,d,r,status);
            Reason reason=rule.support!=Support.KNOWN_ENCODING||rule.reason!=Reason.NONE?rule.reason
                :!rt.ready?Reason.NOT_CONNECTED:rt.busy?Reason.BUSY:!status.fresh(rt.nowMs,STATUS_MAX_AGE_MS)?Reason.STATUS_STALE
                :rule.variant.read(status)==null?Reason.FIELD_ABSENT:Reason.NONE;
            ModelRegistry.Rule recovery=rule.variant==null?rule:new ModelRegistry.Rule(rule.support,rule.reason,rule.evidence,rule.oemNote,EnumSet.of(Precondition.FRESH_STATUS),rule.variant);
            return new Decision(feature,r.layer,recovery,reason,status,rule.variant==null?null:rule.variant.values.clone());
        }
        Decision decision=decide(feature,d,status,rt);
        if(!decision.available())return decision;
        Reason reason=Reason.NONE;
        if(!decision.allows(value))reason=Reason.MODEL_EXCLUDED; // value outside this model's offered domain
        else if(!rt.simulation&&feature.equals("proximity_key")&&value==1){
            if(!rt.bonded)reason=Reason.NEEDS_BOND;else if(!rt.pinEnabledFresh)reason=Reason.NEEDS_PIN;
        }
        if(reason==Reason.NONE)return decision;
        ModelRegistry.Rule rule=new ModelRegistry.Rule(decision.support,Reason.NONE,decision.evidence,decision.oemNote,decision.preconditions,decision.variant);
        return new Decision(feature,decision.layer,rule,reason,status,decision.choices);
    }

    /**
     * Whether a guided test of this feature may run (and write) now. It uses the guided-test rule
     * (ModelRegistry.testRule), never the ordinary one, so a SHARED test variant enables no control.
     * Preconditions: a real connection, fresh status with the field present and inside the domain,
     * no pending change, no check, no ride recording, the rule's own conditions (unlocked, owner),
     * and stationary: a motion report showing it parked, or with none this session the owner's tick.
     */
    public static Decision decideTest(String feature,DeviceDescriptor d,StatusFields s,Runtime rt){
        StatusFields status=s==null?StatusFields.NONE:s;
        ModelRegistry.Resolution r=ModelRegistry.resolve(d);
        ModelRegistry.Rule rule=ModelRegistry.testRule(feature,d,r,status);
        if(rule.support!=Support.KNOWN_ENCODING||rule.reason!=Reason.NONE)return new Decision(feature,r.layer,rule,rule.reason,status,null);
        FeatureVariant v=rule.variant;
        Reason reason=Reason.NONE;int[] choices=null;
        if(rt==null||!rt.ready)reason=Reason.NOT_CONNECTED;
        else if(rt.simulation)reason=Reason.NEEDS_REAL_SCOOTER;
        else if(rt.discovery)reason=Reason.DISCOVERY_ACTIVE;
        else if(rt.test&&!feature.equals(rt.testFeature))reason=Reason.TEST_ACTIVE;
        else if(rt.busy)reason=Reason.BUSY;
        else if(rt.recording)reason=Reason.RECORDING;
        else if(!status.fresh(rt.nowMs,STATUS_MAX_AGE_MS))reason=Reason.STATUS_STALE;
        else {
            Integer current=v.read(status);
            if(current==null)reason=Reason.FIELD_ABSENT;
            else if(!v.allows(current))reason=Reason.VALUE_UNKNOWN;
            else {choices=v.choices(status);if(choices.length==0)reason=Reason.NO_CHOICES;}
        }
        if(reason==Reason.NONE){
            EnumSet<Precondition> p=EnumSet.copyOf(rule.preconditions);p.remove(Precondition.STATIONARY);p.remove(Precondition.MOTION_REPORT);
            reason=preconditions(p,status,rt);
        }
        if(reason==Reason.NONE&&!rt.stationary)reason=rt.motionSeen?Reason.MOVING:rt.parkedConfirmed?Reason.NONE:Reason.PARKED_UNCONFIRMED;
        return new Decision(feature,r.layer,rule,reason,status,choices==null?v.choices(status):choices);
    }
    /** decideTest plus the value: it must be in the variant domain and, for dynamic choices, offered now. */
    public static Decision admitTest(String feature,int value,DeviceDescriptor d,StatusFields s,Runtime rt){
        Decision decision=decideTest(feature,d,s,rt);
        if(!decision.available()||decision.allows(value))return decision;
        return withReason(decision,Reason.MODEL_EXCLUDED);
    }

    /**
     * Whether a control may be tried from Experimental controls now. It uses the experimental rule
     * (ModelRegistry.experimentalRule), never the ordinary one, and only a Lab variant qualifies: an
     * offered control keeps its ordinary row and anything else stays refused. Beyond decide(): no
     * guided test at all, no ride or charge recording, the field present in a fresh status and inside
     * the variant domain (a readback is how the owner sees whether it took), then the rule's parked,
     * owner, unlocked and no-warning conditions. Simulation needs only ready, idle and not recording.
     */
    public static Decision decideExperimental(String feature,DeviceDescriptor d,StatusFields s,Runtime rt){
        StatusFields status=s==null?StatusFields.NONE:s;
        ModelRegistry.Resolution r=ModelRegistry.resolve(d);
        ModelRegistry.Rule rule=ModelRegistry.experimentalRule(feature,d,r,status);
        if(rule.support!=Support.KNOWN_ENCODING||rule.reason!=Reason.NONE)return new Decision(feature,r.layer,rule,rule.reason,status,null);
        // An offered (non-Lab) control is not an experiment here: refused outright, so a try decision is KNOWN only where it can be tried.
        if(rule.variant==null||!rule.variant.lab)return new Decision(feature,r.layer,new ModelRegistry.Rule(Support.INCOMPATIBLE,Reason.MODEL_EXCLUDED,Evidence.NONE,"",
            EnumSet.noneOf(Precondition.class),null),Reason.MODEL_EXCLUDED,status,null);
        return tryNow(feature,r,rule,status,rt);
    }
    /**
     * Whether a Help verify control (ModelRegistry.verifyRule: offered by the official app, not verified by Volt)
     * may be tried now, under the same conditions as an experimental try.
     */
    public static Decision decideVerify(String feature,DeviceDescriptor d,StatusFields s,Runtime rt){
        StatusFields status=s==null?StatusFields.NONE:s;
        ModelRegistry.Resolution r=ModelRegistry.resolve(d);
        ModelRegistry.Rule rule=ModelRegistry.verifyRule(feature,d,r,status);
        if(rule.support!=Support.KNOWN_ENCODING||rule.reason!=Reason.NONE)return new Decision(feature,r.layer,rule,rule.reason,status,null);
        return tryNow(feature,r,rule,status,rt);
    }
    /** An Experimental controls try of either group: the experimental decision where it has a Lab rule, else Help verify's; KNOWN_ENCODING only where a try is possible. */
    public static Decision decideTry(String feature,DeviceDescriptor d,StatusFields s,Runtime rt){
        Decision experimental=decideExperimental(feature,d,s,rt);
        if(experimental.experimental())return experimental;
        Decision verify=decideVerify(feature,d,s,rt);
        return verify.support==Support.KNOWN_ENCODING?verify:experimental;
    }
    /** decideTry plus the value: inside the variant's domain (and offered now, for dynamic choices). */
    public static Decision admitTry(String feature,int value,DeviceDescriptor d,StatusFields s,Runtime rt){
        Decision decision=decideTry(feature,d,s,rt);
        if(!decision.available()||decision.allows(value))return decision;
        return withReason(decision,Reason.MODEL_EXCLUDED);
    }
    /** The runtime part shared by both groups; only the running try's own writes pass while it runs. */
    private static Decision tryNow(String feature,ModelRegistry.Resolution r,ModelRegistry.Rule rule,StatusFields status,Runtime rt){
        FeatureVariant v=rule.variant;
        if(rt==null||!rt.ready)return new Decision(feature,r.layer,rule,Reason.NOT_CONNECTED,status,v.choices(status));
        if(rt.simulation){
            int[] values=v.dynamicChoices&&status.accepted()?v.choices(status):v.values.clone();
            return new Decision(feature,r.layer,rule,rt.busy?Reason.BUSY:rt.recording?Reason.RECORDING:Reason.NONE,status,values);
        }
        Reason reason=Reason.NONE;int[] choices=null;
        if(rt.discovery)reason=Reason.DISCOVERY_ACTIVE;
        else if(rt.test&&!(rt.testTry&&feature.equals(rt.testFeature)))reason=Reason.TEST_ACTIVE;
        else if(rt.busy)reason=Reason.BUSY;
        else if(rt.recording)reason=Reason.RECORDING;
        else if(!status.fresh(rt.nowMs,STATUS_MAX_AGE_MS))reason=Reason.STATUS_STALE;
        else {
            Integer current=v.readable?v.read(status):null;
            if(current==null)reason=Reason.FIELD_ABSENT;
            else if(!v.allows(current))reason=Reason.VALUE_UNKNOWN;
            else {choices=v.choices(status);if(v.dynamicChoices&&choices.length==0)reason=Reason.NO_CHOICES;}
        }
        if(reason==Reason.NONE)reason=preconditions(rule.preconditions,status,rt);
        return new Decision(feature,r.layer,rule,reason,status,choices==null?v.choices(status):choices);
    }
    /** decideExperimental plus the value: inside the Lab variant's domain (and offered now, for dynamic choices). */
    public static Decision admitExperimental(String feature,int value,DeviceDescriptor d,StatusFields s,Runtime rt){
        Decision decision=decideExperimental(feature,d,s,rt);
        if(!decision.available()||decision.allows(value))return decision;
        return withReason(decision,Reason.MODEL_EXCLUDED);
    }

    /** The same decision with another reason (MutationGate's request-level rules). */
    static Decision withReason(Decision d,Reason reason){
        ModelRegistry.Rule rule=new ModelRegistry.Rule(d.support,Reason.NONE,d.evidence,d.oemNote,d.preconditions,d.variant);
        return new Decision(d.feature,d.layer,rule,reason,d.status,d.choices);
    }

    /**
     * Catalogue order, then the OEM-only controls this model's registry maps (KNOWN_ENCODING, so
     * offered whatever the runtime reason), then "clock_sync". OEM-only keys the model does not map
     * are left out rather than listed as refused: a UT5 MAX, shared or unmapped model has no such
     * control, and its list (and report) stays exactly what it was.
     */
    public static List<Decision> all(DeviceDescriptor d,StatusFields s,Runtime rt){
        List<Decision> out=new ArrayList<>();
        for(ScooterCommands.Feature f:ScooterCommands.catalog())out.add(decide(f.key,d,s,rt));
        for(ScooterCommands.Feature f:ScooterCommands.oemOnly()){Decision decision=decide(f.key,d,s,rt);if(decision.support==Support.KNOWN_ENCODING)out.add(decision);}
        out.add(decide("clock_sync",d,s,rt));
        return out;
    }

    private static Reason preconditions(EnumSet<Precondition> p,StatusFields s,Runtime rt){
        if(p.contains(Precondition.OWNER)&&!rt.owner)return Reason.OWNER_ONLY;
        if(p.contains(Precondition.STATIONARY)&&!rt.stationary)return rt.motionSeen?Reason.MOVING:Reason.NO_MOTION_REPORT;
        if(p.contains(Precondition.NO_WARNING)&&rt.warningActive)return Reason.WARNING_ACTIVE;
        if(p.contains(Precondition.UNLOCKED)){Integer lock=s.u8(2);if(lock==null||lock!=0)return Reason.LOCKED;}
        return Reason.NONE;
    }

    /** Short UI copy. */
    static String text(Reason reason){
        switch(reason){
            case NONE: return "";
            case NOT_CONNECTED: return "Connect first";
            case STATUS_STALE: return "Waiting for fresh status";
            case FIELD_ABSENT: return "Not reported";
            case VALUE_UNKNOWN: return "Unknown value · can't change safely";
            case NO_CHOICES: return "No choices reported";
            case BUSY: return "Wait for the current change";
            case MOVING: return "Stop the scooter first";
            case NO_MOTION_REPORT: return "No motion report yet";
            case WARNING_ACTIVE: return "Resolve scooter warning first";
            case LOCKED: return "Unlock first";
            case OWNER_ONLY: return "Owner only";
            case NEEDS_BOND: return "Pair this phone first";
            case NEEDS_PIN: return "Enable the handlebar PIN first";
            case FIRMWARE_UNKNOWN: return "Firmware not read yet";
            case FIRMWARE_OLD: return "Needs newer scooter firmware";
            case FULL_PID_NEEDED: case MODEL_NOT_IDENTIFIED: return "Identify model first";
            case IDENTITY_CONFLICT: return "Model ID mismatch";
            case MODEL_NOT_MAPPED: return "Not mapped for this model";
            case MODEL_EXCLUDED: return "Not on this model";
            case REGION_RULE: return "Not offered for this region";
            case PRODUCT_UNSUPPORTED: case TRANSPORT_UNSUPPORTED: return "Not supported";
            case DISCOVERY_ACTIVE: return "Checking compatibility…";
            case TEST_ACTIVE: return "Test in progress";
            case NOT_TESTABLE: return "Not offered as a test";
            case RECORDING: return "Stop recording first";
            case NEEDS_REAL_SCOOTER: return "Needs a real scooter";
            case PARKED_UNCONFIRMED: return "Confirm the scooter is parked";
            case NO_TEST: return "No test running";
            default: return "";
        }
    }
}
