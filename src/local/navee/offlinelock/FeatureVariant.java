package local.navee.offlinelock;

import java.util.Arrays;

/**
 * One value domain and readback decoder for one feature key on one model layer. The key stays
 * the ScooterCommands key (saved layouts reference it); a different domain for the same wire
 * field is a different variant id. UT5_FEATURE variants delegate to the UT5 MAX catalogue so
 * their choices, readback and bytes are exactly today's.
 */
public final class FeatureVariant {
    public enum Decode { UT5_FEATURE, EXACT, MASKED, CAP7, BE32 }
    public final String id;          // stable, e.g. "maximum_speed.ut5", "energy_recovery.levels_no_off"
    public final String feature;     // ScooterCommands key, or "clock_sync"
    public final int cmd; public final int[] selector;
    public final int[] values; public final String[] labels; public final String unit;
    /** Slider bounds; values is empty for a slider. */
    public final int min,max,step;
    public final int offset,mask,shift; public final Decode decode;
    public final boolean parked,owner,lab,dynamicChoices;
    /** False when there is no 0x70 readback (PIN, clock, a time value): read() is then always null. */
    public final boolean readable;

    FeatureVariant(String id,String feature,int cmd,int[] selector,int[] values,String[] labels,String unit,int min,int max,int step,
            int offset,int mask,int shift,Decode decode,boolean parked,boolean owner,boolean lab,boolean dynamicChoices,boolean readable){
        require(id!=null&&feature!=null&&values.length==labels.length,"Variant domain is malformed");
        this.id=id;this.feature=feature;this.cmd=cmd;this.selector=selector.clone();this.values=values.clone();this.labels=labels.clone();this.unit=unit==null?"":unit;
        this.min=min;this.max=max;this.step=Math.max(1,step);this.offset=offset;this.mask=mask;this.shift=shift;this.decode=decode;
        this.parked=parked;this.owner=owner;this.lab=lab;this.dynamicChoices=dynamicChoices;this.readable=readable&&offset>=0&&decode!=Decode.BE32;
    }

    /** Exact copy of today's UT5 MAX options, values, readback, parked rule and Lab (experimental) flag. */
    public static FeatureVariant ut5(ScooterCommands.Feature f){
        return new FeatureVariant(f.key+".ut5",f.key,f.cmd,f.selector(),f.optionValues,f.options,f.unit,f.min,f.max,f.step,
            f.payloadOffset,f.readbackMask,f.readbackShift,Decode.UT5_FEATURE,f.requiresParked,f.sensitive||f.requiresBond,f.experimental,
            f.key.equals("range_algorithm"),!f.sensitive&&f.type!=ScooterCommands.Type.TIME);
    }

    /** The same wire field with fewer values (for example a regional ceiling). Values must already be allowed. */
    public FeatureVariant narrowed(String id,int[] values,String[] labels){
        for(int v:values)require(allows(v),"Narrowed value is outside the variant domain");
        return new FeatureVariant(id,feature,cmd,selector,values,labels,unit,min,max,step,offset,mask,shift,decode,parked,owner,lab,dynamicChoices,readable);
    }

    /** Current value from an accepted status, or null when absent. Never 0-for-absent. */
    public Integer read(StatusFields s){
        if(!readable||s==null||!s.accepted())return null;
        if(decode==Decode.UT5_FEATURE)return ScooterCommands.find(feature).readback(s.frame());
        Integer raw=s.u8(offset);if(raw==null)return null;
        switch(decode){
            case MASKED: return (raw&mask)>>>shift;
            case CAP7: return (raw&128)==0?0:raw&127;
            default: return raw;
        }
    }
    /** BE32 local seconds (charging start), or null when absent. */
    public Long readTime(StatusFields s){
        if(s==null||!s.accepted()||offset<0)return null;
        if(decode==Decode.UT5_FEATURE)return ScooterCommands.find(feature).timeReadback(s.frame());
        return decode==Decode.BE32?s.be32(offset):null;
    }
    /** Values offered now: dynamic choices come from the status (UT5 range estimate), else the domain. */
    public int[] choices(StatusFields s){
        if(dynamicChoices&&decode==Decode.UT5_FEATURE){
            int[] dynamic=ScooterCommands.choiceValues(feature,s==null?null:s.frame());
            if(values.length==0)return dynamic;
            int n=0;int[] kept=new int[dynamic.length];for(int v:dynamic)if(contains(values,v))kept[n++]=v;return Arrays.copyOf(kept,n);
        }
        return values.clone();
    }
    public boolean allows(int value){
        if(values.length>0&&!contains(values,value))return false;
        if(decode==Decode.UT5_FEATURE)return ScooterCommands.find(feature).allows(value);
        return values.length>0||(value>=min&&value<=max&&(value-min)%step==0);
    }
    public String label(int value){
        if(decode==Decode.UT5_FEATURE&&values.length==0)return ScooterCommands.find(feature).valueLabel(value);
        for(int i=0;i<values.length;i++)if(values[i]==value)return labels[i];
        if(decode==Decode.CAP7&&value==0)return "Off";
        return Integer.toString(value)+(unit.isEmpty()?"":" "+unit);
    }
    /** Frame for one allowed value. Time, clock and PIN writes keep their dedicated builders. */
    public byte[] encode(int value){
        require(allows(value),"Value is outside the OEM feature domain");
        if(decode==Decode.UT5_FEATURE)return ScooterCommands.set(feature,value);
        require(decode!=Decode.BE32,"Feature needs its dedicated builder");
        if(decode==Decode.CAP7)return WireCommands.enabledCap(cmd,selector.length==0?-1:selector[0],value,value!=0);
        if(selector.length==0)return WireCommands.scalar(cmd,value);
        if(selector.length==1)return WireCommands.selector(cmd,selector[0],value);
        require(selector.length==2&&cmd==0x6d,"Feature has no wire encoder");
        return WireCommands.light(selector[0],selector[1],value);
    }
    @Override public String toString(){return id;}

    private static boolean contains(int[] values,int value){for(int v:values)if(v==value)return true;return false;}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalArgumentException(message);}
}
