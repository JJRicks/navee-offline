package local.navee.offlinelock;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/** OEM framing. Named, bounded user controls; unbind/reset/firmware opcodes are excluded. */
public final class Protocol {
    public static final int AUTH = 0x30;
    public static final int CHALLENGE_REPLY = 0x31;
    public static final int LOCK = 0x51;
    public static final int READ_VEHICLE = 0x70;
    public static final int READ_DRIVE = 0x71;
    public static final int READ_BATTERY = 0x72;
    public static final int READ_FIRMWARE = 0x73;
    public static final int READ_SERIAL = 0x74;
    public static final int READ_BATTERY_SERIAL = 0x75;
    public static final int READ_HISTORY = 0x76;
    public static final int READ_BATTERY_EXTRA = 0x79;
    /** Sensitive: request only in an explicit, owner-authenticated PIN workflow. */
    public static final int READ_PIN = 0x7a;
    public static final int READ_FAULTS = 0x7b;
    public static final int KEY_COUNT = 5;

    // Exact constants from the installed OEM app, including its fourth key.
    private static final byte[][] KEYS = {
        fromHex("a0a1a2a3a4a5a6a7a8a9aaabacadaeaf"),
        fromHex("446d10726dbe05f662dfaaf01327303f"),
        fromHex("a285ccec814fe961742995e8eba92247"),
        fromHex("3fee80ff96df5cf542eaac93281fe529"),
        fromHex("4eb4d464d6ef53ed6ce94558de9a5ee3")
    };

    private Protocol() {}

    /** Reads have no length byte. Writes contain a length byte before their payload. */
    public static byte[] frame(int cmd, byte[] payload) {
        byte[] data = payload == null ? new byte[0] : payload;
        switch (cmd) {
            case READ_VEHICLE:
            case READ_DRIVE:
            case READ_BATTERY:
            case READ_FIRMWARE:
            case READ_SERIAL:
            case READ_BATTERY_SERIAL:
            case READ_HISTORY:
            case READ_BATTERY_EXTRA:
            case READ_PIN:
            case READ_FAULTS:
                require(data.length == 0, "Read commands cannot contain payload");
                return encode(cmd, data, false, 0);
            case AUTH:
                require(data.length == 9, "Authentication payload must contain nine bytes");
                checkKey(data[0] & 255);
                require((data[1] & 255) <= 1 && data[8] == 0, "Invalid authentication role/trailer");
                break;
            case CHALLENGE_REPLY:
                require(data.length >= 16 && data.length <= 240 && data.length % 16 == 0,
                        "Challenge response must contain whole AES blocks");
                break;
            default:
                require(ScooterCommands.validPayload(cmd,data),
                        "Command or payload is outside the offline control allowlist");
        }
        return encode(cmd, data, true, 0);
    }

    /** Identity is the existing owner's six-byte OEM account identity; role 0 is owner. */
    public static byte[] auth(byte[] identity6, int keyIndex, int role) {
        return auth(identity6, keyIndex, role, false);
    }

    public static byte[] auth(byte[] identity6, int keyIndex, int role, boolean encrypted) {
        require(identity6 != null && identity6.length == 6, "Owner identity must contain six bytes");
        checkKey(keyIndex);
        require(role == 0 || role == 1, "Role must be owner (0) or shared user (1)");
        byte[] data = new byte[9];
        data[0] = (byte) keyIndex;
        data[1] = (byte) role;
        System.arraycopy(identity6, 0, data, 2, 6);
        return encode(AUTH, data, true, encrypted ? 1 : 0);
    }

    /** The response data includes its error byte; only successful challenge frames are accepted. */
    public static byte[] challengeResponse(Frame notification, int keyIndex) {
        require(notification != null && notification.cmd == AUTH, "Expected an authentication notification");
        require(notification.error() == 0, "Scooter rejected authentication");
        checkKey(keyIndex);
        byte[] data = notification.data;
        require(data.length > 1, "Authentication completed without a challenge");
        int offset = 1;
        boolean xor = false;
        // OEM legacy response: error + 16-byte challenge. Extended: error + method + challenge.
        if (data.length > 17) {
            xor = data[1] == 0;
            offset = 2;
        }
        byte[] challenge = Arrays.copyOfRange(data, offset, data.length);
        byte[] response;
        if (xor) {
            require(challenge.length == 16, "XOR challenge must contain sixteen bytes");
            response = new byte[16];
            for (int i = 0; i < response.length; i++) response[i] = (byte) (challenge[i] ^ KEYS[keyIndex][i]);
        } else {
            require(challenge.length >= 16 && challenge.length % 16 == 0,
                    "AES challenge must contain whole blocks");
            try {
                Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEYS[keyIndex], "AES"));
                response = cipher.doFinal(challenge);
            } catch (GeneralSecurityException failure) {
                throw new IllegalStateException("Device lacks required AES support", failure);
            }
        }
        return frame(CHALLENGE_REPLY, response);
    }

    private static byte[] encode(int cmd, byte[] data, boolean lengthPresent, int flag) {
        int prefixLength = lengthPresent ? 5 : 4;
        byte[] packet = new byte[prefixLength + data.length + 3];
        packet[0] = 0x55;
        packet[1] = (byte) 0xaa;
        packet[2] = (byte) flag;
        packet[3] = (byte) cmd;
        if (lengthPresent) packet[4] = (byte) data.length;
        System.arraycopy(data, 0, packet, prefixLength, data.length);
        packet[packet.length - 3] = (byte) checksum(packet, packet.length - 3);
        packet[packet.length - 2] = (byte) 0xfe;
        packet[packet.length - 1] = (byte) 0xfd;
        return packet;
    }

    public static final class Frame {
        public final int cmd;
        public final int flag;
        /** Complete notification data, with error/status/type at offset zero. */
        public final byte[] data;

        private Frame(int cmd, int flag, byte[] data) {
            this.cmd = cmd;
            this.flag = flag;
            this.data = data;
        }

        public int error() { return data[0] & 255; }
        public byte[] payload() { return Arrays.copyOfRange(data, 1, data.length); }
    }

    /** Parses a complete inbound notification, never an outbound read request. */
    public static Frame parse(byte[] packet) {
        require(packet != null && packet.length >= 9, "Notification is too short");
        require((packet[0] & 255) == 0x55 && (packet[1] & 255) == 0xaa, "Bad notification header");
        require(packet[2] == 0 || packet[2] == 1, "Unknown encryption flag");
        int length = packet[4] & 255;
        require(length >= 1 && packet.length == length + 8, "Notification length mismatch");
        require((packet[packet.length - 2] & 255) == 0xfe && (packet[packet.length - 1] & 255) == 0xfd,
                "Bad notification footer");
        require((packet[packet.length - 3] & 255) == checksum(packet, packet.length - 3),
                "Notification checksum mismatch");
        return new Frame(packet[3] & 255, packet[2] & 255, Arrays.copyOfRange(packet, 5, 5 + length));
    }

    /**
     * Bounded stream assembler supporting fragmented and coalesced Bluetooth notifications.
     * Header/footer bytes inside data are ordinary data; a candidate's declared length wins.
     * Reset on disconnect or transaction timeout to discard any truncated stale candidate.
     */
    public static final class Decoder {
        private static final int MAX_FRAME = 263;
        private final byte[] buffer = new byte[MAX_FRAME];
        private int count;
        private long rejected;

        public List<Frame> feed(byte[] notification) {
            require(notification != null, "Notification must not be null");
            List<Frame> frames = new ArrayList<Frame>();
            for (byte next : notification) {
                if (count == buffer.length) {
                    discard(1);
                    rejected++;
                }
                buffer[count++] = next;
                drain(frames);
            }
            return frames;
        }

        public void reset() { count = 0; }
        public int bufferedBytes() { return count; }
        public long rejectedBytes() { return rejected; }

        private void drain(List<Frame> frames) {
            while (count >= 2) {
                if ((buffer[0] & 255) != 0x55 || (buffer[1] & 255) != 0xaa) {
                    discard(1);
                    rejected++;
                    continue;
                }
                if (count < 5) return;
                int length = buffer[4] & 255;
                if ((buffer[2] != 0 && buffer[2] != 1) || length == 0) {
                    discard(1);
                    rejected++;
                    continue;
                }
                int total = length + 8;
                if (count < total) return;
                try {
                    frames.add(parse(Arrays.copyOf(buffer, total)));
                    discard(total);
                } catch (IllegalArgumentException invalid) {
                    discard(1);
                    rejected++;
                }
            }
        }

        private void discard(int amount) {
            count -= amount;
            System.arraycopy(buffer, amount, buffer, 0, count);
        }
    }

    public static String hex(byte[] data) {
        require(data != null, "Bytes must not be null");
        char[] digits = "0123456789abcdef".toCharArray();
        char[] result = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            result[i * 2] = digits[(data[i] & 255) >>> 4];
            result[i * 2 + 1] = digits[data[i] & 15];
        }
        return new String(result);
    }

    public static byte[] fromHex(String text) {
        require(text != null, "Hex text must not be null");
        String cleaned = text.replaceAll("[\\s:-]", "");
        require(cleaned.length() % 2 == 0, "Hex text must contain complete bytes");
        byte[] result = new byte[cleaned.length() / 2];
        for (int i = 0; i < result.length; i++) {
            int high = Character.digit(cleaned.charAt(i * 2), 16);
            int low = Character.digit(cleaned.charAt(i * 2 + 1), 16);
            require(high >= 0 && low >= 0, "Invalid hex text");
            result[i] = (byte) ((high << 4) | low);
        }
        return result;
    }

    private static int checksum(byte[] data, int end) {
        int sum = 0;
        for (int i = 0; i < end; i++) sum += data[i] & 255;
        return sum & 255;
    }

    private static void checkKey(int index) {
        require(index >= 0 && index < KEY_COUNT, "Authentication key index must be 0 through 4");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
