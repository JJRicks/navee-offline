package local.navee.offlinelock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Run with javac/java; fixtures reflect OEM frames and OpenSSL AES results, no Android needed. */
public final class ProtocolTest {
    private static int assertions;
    private static final byte[] AUTH_OK = bytes("55aa0030010030fefd");
    private static final byte[] CHALLENGE = bytes("55aa00301100000102030405060708090a0b0c0d0e0fb8fefd");

    public static void main(String[] args) {
        framesMatchOem();
        cryptoMatchesIndependentVectors();
        rejectMalformedAndDangerousCommands();
        notificationsRetainStatus();
        transportAssembly();
        System.out.println("Protocol tests passed: " + assertions + " assertions");
    }

    private static void framesMatchOem() {
        equal("55aa00706ffefd", Protocol.frame(0x70, null));
        equal("55aa007372fefd", Protocol.frame(0x73, new byte[0]));
        equal("55aa007473fefd", Protocol.frame(0x74, null));
        for(int cmd:new int[]{0x71,0x72,0x75,0x76,0x79,0x7b}) {
            byte[] read=Protocol.frame(cmd,null);
            check(read.length==7&&read[3]==(byte)cmd,"Diagnostic reads omit payload");
            rejects(new Runnable(){public void run(){Protocol.frame(cmd,new byte[]{1});}});
        }
        equal("55aa0051010152fefd", Protocol.frame(0x51, new byte[] {1}));
        equal("55aa0051010051fefd", Protocol.frame(0x51, new byte[] {0}));
        equal("55aa003009030086001234567800d5fefd", Protocol.auth(bytes("860012345678"), 3, 0));
        equal("55aa003009030186001234567800d6fefd", Protocol.auth(bytes("860012345678"), 3, 1));
        equal("55aa013009030086001234567800d6fefd", Protocol.auth(bytes("860012345678"), 3, 0, true));
    }

    private static void cryptoMatchesIndependentVectors() {
        // Independent AES-128-ECB/NoPadding reference outputs, plaintext 000102...0f.
        String[] frames = {
            "55aa0031101466752c7f159722b2410a6a94875538b7fefd",
            "55aa003110c8c0526bdd6b5d9d9214d15c89042f98eefefd",
            "55aa003110653dbf363ad1b7a5d22fc7bfecef6a9da7fefd",
            "55aa003110a4ceaa30911ba7617bf543f43a5d498148fefd",
            "55aa00311061d25f08fa6083a3413bda11d230bbd14ffefd"
        };
        Protocol.Frame legacy = Protocol.parse(CHALLENGE);
        for (int key = 0; key < frames.length; key++) equal(frames[key], Protocol.challengeResponse(legacy, key));
        byte[] challenge = bytes("000102030405060708090a0b0c0d0e0f");
        Protocol.Frame typedAes = Protocol.parse(notification(0x30, concat(new byte[] {0, 1}, challenge)));
        equal(frames[3], Protocol.challengeResponse(typedAes, 3));
        // The OEM treats every nonzero extended method as AES, not just method one.
        Protocol.Frame otherAes = Protocol.parse(notification(0x30, concat(new byte[] {0, 2}, challenge)));
        equal(frames[3], Protocol.challengeResponse(otherAes, 3));
        Protocol.Frame xor = Protocol.parse(notification(0x30, concat(new byte[] {0, 0}, challenge)));
        byte[] xorFrame = Protocol.challengeResponse(xor, 0);
        check(xorFrame.length == 24, "XOR response length");
        for (int i = 5; i < 21; i++) check((xorFrame[i] & 255) == 0xa0, "XOR challenge vector");
        check(Protocol.parse(xorFrame).cmd == 0x31, "Challenge reply opcode");
    }

    private static void rejectMalformedAndDangerousCommands() {
        for (final int cmd : new int[] {0x50, 0x59, 0x63, 0x6f, 0x77, 0x78, 0x7a, 0x80, 0x100, -1}) {
            rejects(new Runnable() { public void run() { Protocol.frame(cmd, new byte[] {1}); }});
        }
        rejects(new Runnable() { public void run() { Protocol.frame(0x51, new byte[] {2}); }});
        rejects(new Runnable() { public void run() { Protocol.frame(0x51, new byte[0]); }});
        rejects(new Runnable() { public void run() { Protocol.frame(0x70, new byte[] {0}); }});
        rejects(new Runnable() { public void run() { Protocol.auth(new byte[5], 0, 0); }});
        rejects(new Runnable() { public void run() { Protocol.auth(new byte[6], 5, 0); }});
        rejects(new Runnable() { public void run() { Protocol.auth(new byte[6], -1, 0); }});
        rejects(new Runnable() { public void run() { Protocol.auth(new byte[6], 0, 2); }});
        rejects(new Runnable() { public void run() { Protocol.challengeResponse(Protocol.parse(AUTH_OK), 0); }});
        rejects(new Runnable() { public void run() {
            Protocol.challengeResponse(Protocol.parse(notification(0x30, new byte[] {1, 0})), 0);
        }});
        rejects(new Runnable() { public void run() {
            Protocol.challengeResponse(Protocol.parse(notification(0x30, new byte[16])), 0);
        }});
        rejects(new Runnable() { public void run() {
            Protocol.challengeResponse(Protocol.parse(notification(0x30, new byte[19])), 0);
        }});
        rejects(new Runnable() { public void run() { Protocol.parse(new byte[0]); }});
        rejects(new Runnable() { public void run() { Protocol.parse(bytes("55aa00706ffefd")); }});
        rejects(new Runnable() { public void run() { Protocol.parse(concat(AUTH_OK, new byte[] {0})); }});
        for (final int index : new int[] {0, 1, 2, 4, 7, 8}) {
            final byte[] damaged = AUTH_OK.clone();
            damaged[index] ^= 0x20;
            rejects(new Runnable() { public void run() { Protocol.parse(damaged); }});
        }
        final byte[] checksumDamage = AUTH_OK.clone();
        checksumDamage[6] ^= 1;
        rejects(new Runnable() { public void run() { Protocol.parse(checksumDamage); }});
        rejects(new Runnable() { public void run() { Protocol.fromHex("xyz"); }});
        rejects(new Runnable() { public void run() { Protocol.fromHex("ab0"); }});
        equal("860012345678", Protocol.fromHex("86:00:12:34:56:78"));
    }

    private static void notificationsRetainStatus() {
        Protocol.Frame auth = Protocol.parse(CHALLENGE);
        check(auth.cmd == 0x30 && auth.flag == 0 && auth.error() == 0, "Auth metadata");
        equal("00000102030405060708090a0b0c0d0e0f", auth.data);
        equal("000102030405060708090a0b0c0d0e0f", auth.payload());
        Protocol.Frame car = Protocol.parse(notification(0x70, new byte[] {0, 1, 2, 1, 0}));
        check(car.data[3] == 1 && car.payload()[2] == 1, "OEM lock offset includes error byte");
        Protocol.Frame error = Protocol.parse(notification(0x51, new byte[] {7}));
        check(error.error() == 7 && error.payload().length == 0, "Nonzero lock error preserved");
    }

    private static void transportAssembly() {
        for (int split = 0; split <= CHALLENGE.length; split++) {
            Protocol.Decoder decoder = new Protocol.Decoder();
            List<Protocol.Frame> frames = new ArrayList<Protocol.Frame>();
            frames.addAll(decoder.feed(Arrays.copyOfRange(CHALLENGE, 0, split)));
            frames.addAll(decoder.feed(Arrays.copyOfRange(CHALLENGE, split, CHALLENGE.length)));
            check(frames.size() == 1 && frames.get(0).cmd == 0x30, "Split frame boundary " + split);
            check(decoder.bufferedBytes() == 0, "Split buffer drained");
        }
        Protocol.Decoder decoder = new Protocol.Decoder();
        List<Protocol.Frame> frames = new ArrayList<Protocol.Frame>();
        for (byte b : CHALLENGE) frames.addAll(decoder.feed(new byte[] {b}));
        check(frames.size() == 1, "One-byte fragments");
        frames = decoder.feed(concat(CHALLENGE, AUTH_OK, notification(0x70, new byte[] {0, 1, 2, 0})));
        check(frames.size() == 3 && frames.get(2).cmd == 0x70, "Coalesced replies");
        byte[] bad = AUTH_OK.clone();
        bad[6] ^= 1;
        frames = decoder.feed(concat(new byte[] {2, 3, 0x55}, bad, bytes("55aa003000"), AUTH_OK));
        check(frames.size() == 1 && frames.get(0).cmd == 0x30, "Noise and corrupted frames resynchronize");
        check(decoder.rejectedBytes() > 0, "Invalid bytes recorded");
        // Footer/header bytes and even complete inner frames inside declared data cannot split it.
        byte[] embedded = notification(0x90, concat(new byte[] {0}, AUTH_OK, bytes("fefd55aa")));
        frames = decoder.feed(embedded);
        check(frames.size() == 1 && frames.get(0).cmd == 0x90, "Header bytes inside payload stay inside");
        byte[] maxData = new byte[255];
        for (int i = 1; i < maxData.length; i++) maxData[i] = (byte) i;
        frames = decoder.feed(notification(0x91, maxData));
        check(frames.size() == 1 && frames.get(0).data.length == 255, "Maximum length notification");
        decoder.feed(bytes("55aa0030ff"));
        frames = decoder.feed(concat(new byte[600], AUTH_OK));
        check(frames.size() == 1 && decoder.bufferedBytes() == 0, "Malformed long length stays bounded");
        decoder.feed(Arrays.copyOf(CHALLENGE, 12));
        check(decoder.bufferedBytes() == 12, "Truncated candidate retained");
        decoder.reset();
        check(decoder.feed(AUTH_OK).size() == 1, "Reset drops stale fragments on reconnect");
    }

    private static byte[] notification(int cmd, byte[] data) {
        byte[] result = new byte[data.length + 8];
        result[0] = 0x55;
        result[1] = (byte) 0xaa;
        result[3] = (byte) cmd;
        result[4] = (byte) data.length;
        System.arraycopy(data, 0, result, 5, data.length);
        int sum = 0;
        for (int i = 0; i < result.length - 3; i++) sum += result[i] & 255;
        result[result.length - 3] = (byte) sum;
        result[result.length - 2] = (byte) 0xfe;
        result[result.length - 1] = (byte) 0xfd;
        return result;
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) length += part.length;
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }

    private static byte[] bytes(String hex) { return Protocol.fromHex(hex); }
    private static void equal(String expected, byte[] actual) { check(expected.equals(Protocol.hex(actual)), "Expected " + expected + " got " + Protocol.hex(actual)); }
    private static void check(boolean condition, String description) {
        assertions++;
        if (!condition) throw new AssertionError(description);
    }
    private static void rejects(Runnable operation) {
        boolean rejected = false;
        try { operation.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "Malformed or forbidden input must be rejected");
    }
}
