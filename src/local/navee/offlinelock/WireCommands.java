package local.navee.offlinelock;

/**
 * One encoder per wire operation, shared by every model layer. Domains live in the caller
 * (ScooterCommands for UT5 MAX, FeatureVariant otherwise); Protocol.frame still applies the
 * ScooterCommands.validPayload allowlist to every result. PIN and proximity builders stay in
 * ScooterCommands because they are sensitive.
 */
final class WireCommands {
    private WireCommands() {}

    /** [v]: 0x51-0x58, 0x5f, 0x60, 0x61, 0x6a, 0x6c (one byte), 0x82. */
    static byte[] scalar(int cmd,int value) { return Protocol.frame(cmd,new byte[]{(byte)value}); }

    /** [selector, v]: 0x6e sel 1/2, 0x6f sel 1/2/4/5/7, 0x81. */
    static byte[] selector(int cmd,int selector,int value) { return Protocol.frame(cmd,new byte[]{(byte)selector,(byte)value}); }

    /** km/h with the OEM enable bit 7. selector &lt; 0 is the one-byte 0x6b form; Off sends 0. */
    static byte[] enabledCap(int cmd,int selector,int kmh,boolean enabled) {
        byte value=(byte)(enabled?kmh|128:0);
        return selector<0?Protocol.frame(cmd,new byte[]{value}):Protocol.frame(cmd,new byte[]{(byte)selector,value});
    }

    /** 0x6d [target 4 line | 5 star, sub, v]. */
    static byte[] light(int target,int sub,int value) { return Protocol.frame(0x6d,new byte[]{(byte)target,(byte)sub,(byte)value}); }

    /** 0x6f [selector 3 charge start | 6 clock, BE32 local seconds]. */
    static byte[] localTime(int selector,long unixSeconds,int tzOffsetSeconds) {
        require(tzOffsetSeconds>=-18*3600&&tzOffsetSeconds<=18*3600,"Timezone offset is invalid");
        long local=unixSeconds+tzOffsetSeconds;
        require(unixSeconds>0&&local>0&&local<=0xffffffffL,"Timestamp must fit OEM's four-byte clock");
        return Protocol.frame(0x6f,new byte[]{(byte)selector,(byte)(local>>>24),(byte)(local>>>16),(byte)(local>>>8),(byte)local});
    }

    /** 0x6c two-byte form: [enabled bit 7 | volume, language]. */
    static byte[] voice(boolean enabled,int volume,int language) {
        return Protocol.frame(0x6c,new byte[]{(byte)(enabled?128|volume:0),(byte)language});
    }

    private static void require(boolean condition,String message) { if(!condition) throw new IllegalArgumentException(message); }
}
