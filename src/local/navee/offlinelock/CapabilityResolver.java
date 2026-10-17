package local.navee.offlinelock;

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
    public enum Reason { NONE, NOT_CONNECTED, STATUS_STALE, FIELD_ABSENT, VALUE_UNKNOWN, NO_CHOICES, BUSY, MOVING, NO_MOTION_REPORT,
        WARNING_ACTIVE, LOCKED, OWNER_ONLY, NEEDS_BOND, NEEDS_PIN, FIRMWARE_UNKNOWN, FIRMWARE_OLD, FULL_PID_NEEDED,
        IDENTITY_CONFLICT, MODEL_NOT_IDENTIFIED, MODEL_NOT_MAPPED, MODEL_EXCLUDED, REGION_RULE,
        PRODUCT_UNSUPPORTED, TRANSPORT_UNSUPPORTED, DISCOVERY_ACTIVE, TEST_ACTIVE }
    /** Status older than this is stale (BleController.ready() uses the same bound). */
    static final long STATUS_MAX_AGE_MS=7000;

    /** Plain data copied from BleController for one evaluation. */
    public static final class Runtime {
        public boolean simulation,ready,busy,stationary,motionSeen,warningActive,owner,bonded,pinEnabledFresh,discovery,test;
        /** The feature a guided test is exercising; other features report TEST_ACTIVE while test is true. */
        public String testFeature="";
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
        /** Offered values (empty for a slider: use variant.min/max/step). */
        public int[] values(){return choices.clone();}
        public String[] labels(){String[] out=new String[choices.length];for(int i=0;i<choices.length;i++)out[i]=label(choices[i]);return out;}
        public String label(int v){return variant==null?Integer.toString(v):variant.label(v);}
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
        // 1-2. Registry and model conditions (product, transport, identity, mapping, region, firmware, full PID).
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
        else if(rt.test&&!feature.equals(rt.testFeature))reason=Reason.TEST_ACTIVE;
        else if(rt.busy)reason=Reason.BUSY;
        else if(!status.fresh(rt.nowMs,STATUS_MAX_AGE_MS))reason=Reason.STATUS_STALE;
        else {
            Integer current=v.readable?v.read(status):null;
            if(v.readable&&current==null)reason=Reason.FIELD_ABSENT;
            else if(current!=null&&!v.allows(current))reason=Reason.VALUE_UNKNOWN;
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

    /** Catalogue order, then "clock_sync". */
    public static List<Decision> all(DeviceDescriptor d,StatusFields s,Runtime rt){
        List<Decision> out=new ArrayList<>();
        for(ScooterCommands.Feature f:ScooterCommands.catalog())out.add(decide(f.key,d,s,rt));
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
            default: return "";
        }
    }
}
