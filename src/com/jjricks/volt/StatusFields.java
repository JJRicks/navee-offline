package com.jjricks.volt;

/**
 * Field-presence view of one 0x70 vehicle-status reply. A missing byte is absent, never zero;
 * a present zero is a real value. Offsets use OEM numbering (after the status byte), so
 * offset n is Protocol.Frame.data[n + 1].
 */
public final class StatusFields {
    public static final StatusFields NONE=new StatusFields(null,-1);
    public static final class Field {
        public final boolean present; public final int value; public final long observedMs; // value meaningless when !present
        Field(boolean present,int value,long observedMs){this.present=present;this.value=present?value:0;this.observedMs=observedMs;}
    }
    private final Protocol.Frame frame; private final long observedMs;
    private StatusFields(Protocol.Frame frame,long observedMs){this.frame=frame;this.observedMs=observedMs;}

    /** Accepts only a 0x70 frame with status 0; any other frame gives an empty snapshot that is not accepted. */
    public static StatusFields of(Protocol.Frame frame,long observedMs){
        return frame!=null&&frame.cmd==0x70&&frame.data.length>0&&frame.error()==0?new StatusFields(frame,observedMs):new StatusFields(null,observedMs);
    }
    public boolean accepted(){return frame!=null;}
    /** OEM data length, excluding the status byte. */
    public int length(){return frame==null?0:frame.data.length-1;}
    public boolean has(int offset,int length){return frame!=null&&offset>=0&&length>0&&offset+length<=length();}
    public Field field(int offset){return has(offset,1)?new Field(true,frame.data[offset+1]&255,observedMs):new Field(false,0,observedMs);}
    /** null when absent, never 0-for-absent. */
    public Integer u8(int offset){return has(offset,1)?frame.data[offset+1]&255:null;}
    public Long be32(int offset){
        if(!has(offset,4))return null;int o=offset+1;
        return ((long)(frame.data[o]&255)<<24)|((long)(frame.data[o+1]&255)<<16)|((long)(frame.data[o+2]&255)<<8)|(frame.data[o+3]&255L);
    }
    public long observedMs(){return observedMs;}
    public long ageMs(long nowMs){return frame==null||observedMs<0?Long.MAX_VALUE:nowMs-observedMs;}
    /** Accepted and observed within maxAgeMs (inclusive), never from the future. */
    public boolean fresh(long nowMs,long maxAgeMs){long age=ageMs(nowMs);return frame!=null&&observedMs>0&&age>=0&&age<=maxAgeMs;}
    /** The accepted frame, for UT5 variants that delegate to Feature.readback; null when not accepted. */
    public Protocol.Frame frame(){return frame;}
}
