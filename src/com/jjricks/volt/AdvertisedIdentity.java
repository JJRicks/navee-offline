package com.jjricks.volt;

/**
 * Model ID from a BLE advertisement, parsed the way the OEM 2.1.9 app parses it: walk the raw
 * AD structures of ScanRecord.getBytes(), take the first type-0xFF structure, and read
 * PID = mfr[1] | mfr[2] &lt;&lt; 8 as an unpadded decimal. When that structure is missing or has
 * at most two data bytes, fall back to scan-record bytes 6..7 (little-endian).
 *
 * Android trap: never use ScanRecord.getManufacturerSpecificData(). It consumes mfr[0..1] as a
 * company ID, which swallows the PID low byte. Only the model ID leaves this parser: the MAC
 * bytes that follow it are never returned.
 */
public final class AdvertisedIdentity {
    public static final class Result {
        public final String pid; public final int marker; public final DeviceDescriptor.Transport hint;
        Result(String pid,int marker,DeviceDescriptor.Transport hint){this.pid=pid;this.marker=marker;this.hint=hint;}
        @Override public String toString(){return pid+"/"+marker+"/"+hint;}
    }
    private AdvertisedIdentity() {}

    /** Null when neither path yields two bytes. marker is mfr[0], or -1 on the fallback path. Never throws. */
    public static Result parse(byte[] scanRecord) {
        if(scanRecord==null||scanRecord.length==0)return null;
        byte[] r=scanRecord;int lo,hi,marker=-1;
        int mfr=-1,mfrLength=0;
        // Stops at a zero length or an AD that would overrun the record.
        for(int i=0;i<r.length;){
            int length=r[i]&255;if(length==0||i+length>=r.length)break;
            if((r[i+1]&255)==0xff){mfr=i+2;mfrLength=length-1;break;}
            i+=length+1;
        }
        if(mfr>=0&&mfrLength>2){marker=r[mfr]&255;lo=r[mfr+1]&255;hi=r[mfr+2]&255;}
        else if(r.length>7){lo=r[6]&255;hi=r[7]&255;}
        else return null;
        int pid=lo|hi<<8;
        DeviceDescriptor.Transport hint=pid==27361?DeviceDescriptor.Transport.LD_EBIKE:pid==27391?DeviceDescriptor.Transport.DIGITECH_EBIKE
            :pid==27681&&marker==5?DeviceDescriptor.Transport.EXO:DeviceDescriptor.Transport.STANDARD;
        return new Result(Integer.toString(pid),marker,hint);
    }
}
