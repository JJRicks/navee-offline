package com.jjricks.volt;

import java.util.Random;

/** Raw advertisement walk: first manufacturer AD, fallback bytes, transport hints and robustness. */
public final class AdvertisedIdentityTest {
    private static int assertions;
    public static void main(String[] args) {
        canonical(); fallback(); malformed(); hints(); fuzz();
        System.out.println("AdvertisedIdentity tests passed: "+assertions+" assertions");
    }

    private static void canonical() {
        // flags AD, then 0xFF AD: marker B0, PID 0x5B9C LE, then six MAC bytes; getBytes() pads with zeros.
        AdvertisedIdentity.Result r=AdvertisedIdentity.parse(padded(0x02,0x01,0x06,0x0b,0xff,0xb0,0x9c,0x5b,1,2,3,4,5,6));
        check(r!=null&&r.pid.equals("23452")&&r.marker==0xb0&&r.hint==DeviceDescriptor.Transport.STANDARD,"Canonical record");
        // Unpadded, that AD claims one byte more than exists: the walk stops and bytes 6..7 give the same PID.
        AdvertisedIdentity.Result unpadded=AdvertisedIdentity.parse(bytes(0x02,0x01,0x06,0x0b,0xff,0xb0,0x9c,0x5b,1,2,3,4,5,6));
        check(unpadded!=null&&unpadded.pid.equals("23452")&&unpadded.marker==-1,"Unpadded canonical record uses the fallback");
        AdvertisedIdentity.Result first=AdvertisedIdentity.parse(bytes(0x04,0xff,0x00,0x51,0x63,0x04,0xff,0x00,0x01,0x02));
        check(first!=null&&first.pid.equals("25425"),"Only the first manufacturer AD is used");
        check(!r.toString().contains("010203"),"Result carries no MAC bytes");
    }

    private static void fallback() {
        // No 0xFF AD: bytes 6..7 little-endian.
        AdvertisedIdentity.Result none=AdvertisedIdentity.parse(bytes(0x02,0x01,0x06,0x05,0x09,0x4e,0x41,0x56,0x45,0x45));
        check(none!=null&&none.pid.equals(Integer.toString(0x41|0x56<<8))&&none.marker==-1,"Fallback bytes 6..7");
        // A manufacturer AD with only two data bytes falls back.
        AdvertisedIdentity.Result shortMfr=AdvertisedIdentity.parse(bytes(0x03,0xff,0x01,0x02,0x00,0x00,0x34,0x12));
        check(shortMfr!=null&&shortMfr.pid.equals(Integer.toString(0x1234))&&shortMfr.marker==-1,"Two-byte manufacturer AD falls back");
        AdvertisedIdentity.Result emptyMfr=AdvertisedIdentity.parse(bytes(0x01,0xff,0x00,0x00,0x00,0x00,0xa2,0x09));
        check(emptyMfr!=null&&emptyMfr.pid.equals("2466"),"Empty manufacturer AD falls back");
        // A zero-length AD stops the walk; the OEM then still uses the fallback bytes.
        AdvertisedIdentity.Result zero=AdvertisedIdentity.parse(bytes(0x00,0x0b,0xff,0xb0,0x9c,0x5b,0xea,0x09));
        check(zero!=null&&zero.pid.equals("2538")&&zero.marker==-1,"Zero-length AD stops the walk");
    }

    private static void malformed() {
        check(AdvertisedIdentity.parse(null)==null&&AdvertisedIdentity.parse(new byte[0])==null,"Empty record");
        check(AdvertisedIdentity.parse(bytes(0x00))==null,"Zero-length AD in a short record");
        check(AdvertisedIdentity.parse(bytes(0x0b,0xff,0xb0,0x9c,0x5b))==null,"Overrunning AD in a short record stops with null");
        check(AdvertisedIdentity.parse(bytes(0x02,0x01,0x06,0x0b,0xff,0xb0,0x9c))==null,"Truncated record stops with null");
        // Overrun with eight or more bytes: the walk stops, fallback bytes apply.
        AdvertisedIdentity.Result overrun=AdvertisedIdentity.parse(bytes(0x02,0x01,0x06,0x20,0xff,0xb0,0x9c,0x5b));
        check(overrun!=null&&overrun.pid.equals(Integer.toString(0x9c|0x5b<<8))&&overrun.marker==-1,"Overrun falls back like the OEM");
    }

    private static void hints() {
        check(mfr(5,27681).hint==DeviceDescriptor.Transport.EXO,"Exo needs marker 5 and 27681");
        check(mfr(4,27681).hint==DeviceDescriptor.Transport.STANDARD,"27681 without marker 5 is not Exo");
        check(mfr(0,27361).hint==DeviceDescriptor.Transport.LD_EBIKE&&mfr(0,27391).hint==DeviceDescriptor.Transport.DIGITECH_EBIKE,"E-bike transports");
        check(mfr(0,65535).pid.equals("65535")&&mfr(0,0).pid.equals("0")&&mfr(0,25381).pid.equals("25381"),"Full 16-bit range, unpadded decimal");
    }

    private static void fuzz() {
        Random random=new Random(20261016L);int parsed=0;
        for(int i=0;i<10000;i++){
            byte[] record=new byte[random.nextInt(40)];random.nextBytes(record);
            AdvertisedIdentity.Result r=AdvertisedIdentity.parse(record);
            if(r!=null){parsed++;int pid=Integer.parseInt(r.pid);if(pid<0||pid>65535||!DeviceDescriptor.pid(r.pid).equals(r.pid))throw new AssertionError("Fuzz PID out of range");}
        }
        check(parsed>0,"Fuzzed records parse without throwing");
    }

    private static AdvertisedIdentity.Result mfr(int marker,int pid){return AdvertisedIdentity.parse(padded(0x02,0x01,0x06,0x0b,0xff,marker,pid&255,pid>>>8,1,2,3,4,5,6));}
    private static byte[] padded(int... values){byte[] out=new byte[31];for(int i=0;i<values.length;i++)out[i]=(byte)values[i];return out;}
    private static byte[] bytes(int... values){byte[] out=new byte[values.length];for(int i=0;i<values.length;i++)out[i]=(byte)values[i];return out;}
    private static void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}
}
