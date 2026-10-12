package local.navee.offlinelock;

/** Synthetic identities only. Compile with Protocol and OwnerIdentityParser; no Android/ADB needed. */
public final class OwnerIdentityParserTest {
    private static int assertions;
    private static final String ID = "860012345678";

    public static void main(String[] args) {
        explicitBackups();
        captureFrames();
        invalidOrAmbiguousInputs();
        System.out.println("OwnerIdentityParserTest: " + assertions + " assertions passed");
    }

    private static void explicitBackups() {
        equal(ID, 0, "  " + ID + "\n");
        equal("AB12CD34EF56", 0, "ab12cd34ef56");
        equal(ID, 0, "{\"format\":\"navee-offline-owner-v1\",\"owner_identity\":\"" + ID
                + "\",\"role\":0,\"model_name\":\"NAVEE UT5 MAX\"}");
        equal(ID, 1, "{\"owner_identity\":\"" + ID + "\",\"role\":1}");
        equal(ID, 1, "Owner identity: " + ID + "\r\nShared-user option: ON\r\n");
        equal(ID, 0, "Keep this private.\nOwner identity: " + ID + "\nShared-user option: OFF\n");
        equal(ID, 1, "{ \"identity\" : \"" + ID + "\", \"role\" : \"1\" }");
        equal(ID, 0, "owner_identity: " + ID + "\nOwner identity: " + ID + "\n");
    }

    private static void captureFrames() {
        for (int key = 0; key <= 4; key++) {
            equal(ID, 0, row(Protocol.auth(Protocol.fromHex(ID), key, 0)));
            equal(ID, 1, row(Protocol.auth(Protocol.fromHex(ID), key, 1)));
        }
        String valid = row(Protocol.auth(Protocol.fromHex(ID), 3, 0));
        equal(ID, 0, "D unrelated: Authorization: do not import this\n" + valid + "\n" + valid);
        equal(ID, 0, valid + " trailing explanation not part of the bracketed frame");
        equal(ID, 0, "Send authData: " + Protocol.hex(Protocol.auth(Protocol.fromHex(ID), 1, 0)));
        equal(ID, 0, "Send authData: [" + Protocol.hex(Protocol.auth(Protocol.fromHex(ID), 1, 0))
                .toUpperCase() + "]");
        byte[] damaged = Protocol.auth(Protocol.fromHex(ID), 1, 0);
        damaged[14] ^= 1;
        equal(ID, 0, row(damaged) + "\n" + valid);
    }

    private static void invalidOrAmbiguousInputs() {
        rejects(null); rejects(""); rejects("\n   "); rejects("1234"); rejects("1234567890XYZ");
        rejects("owner_identity: 1234567890123");
        rejects("{\"owner_identity\":\"123456789012345\"}");
        rejects("{\"owner_identity\":\"" + ID + "\",\"role\":2}");
        rejects("{\"owner_identity\":\"" + ID + "\",\"role\":10}");
        rejects("{\"owner_identity\":\"" + ID + "\",\"role\":-1}");
        rejects("{\"owner_identity\":\"" + ID + "\",\"role\":1.5}");
        rejects("{\"owner_identity\":\"" + ID + "\",\"role\":true}");
        rejects("Owner identity: " + ID + "\nrole:1\nShared-user option: OFF");
        rejects("owner_identity: " + ID + "\nowner_identity: 860087654321\n");
        rejects("{\"owner_identity\":\"" + ID + "\",\"role\":0,\"role\":1}");
        rejects(row(Protocol.auth(Protocol.fromHex(ID), 0, 0)) + "\n"
                + row(Protocol.auth(Protocol.fromHex("860087654321"), 0, 0)));
        rejects(row(Protocol.auth(Protocol.fromHex(ID), 0, 0)) + "\n"
                + row(Protocol.auth(Protocol.fromHex(ID), 0, 1)));
        rejects("Send otherData: [" + Protocol.hex(Protocol.auth(Protocol.fromHex(ID), 0, 0)) + "]");
        rejects("Authorization: " + ID);
        rejects("owner_identity: " + ID + "garbage");
        rejects("unrelated_identity: " + ID);
        byte[] frame = Protocol.auth(Protocol.fromHex(ID), 0, 0);
        for (int offset : new int[]{0, 1, 2, 3, 4, 5, 6, 13, 14, 15, 16}) {
            byte[] damaged = frame.clone();
            damaged[offset] ^= 0x40;
            if (offset < 14) {
                int sum = 0; for (int i = 0; i < 14; i++) sum += damaged[i] & 255;
                damaged[14] = (byte) sum; // Metadata rejection independent of checksum rejection.
            }
            rejects(row(damaged));
        }
        byte[] flag = Protocol.auth(Protocol.fromHex(ID), 0, 0, true);
        rejects(row(flag));
        rejects("Send authData: [55 aa 00 30 09 00 00 86 00 12 34 56 78 00 00 fe fd]");
        rejects("Send authData: " + Protocol.hex(frame) + " arbitrary suffix");
        rejects("Send authData: [" + Protocol.hex(frame).substring(2) + "]");
        rejects("Send authData: [" + Protocol.hex(frame) + " 00]");
        StringBuilder huge = new StringBuilder(1048577);
        while (huge.length() <= 1048576) huge.append('x');
        rejects(huge.toString());
    }

    private static String row(byte[] bytes) {
        StringBuilder out = new StringBuilder("1791800000.123 123 456 D com.navee.ucaret: Send authData: [");
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) out.append(' ');
            out.append(String.format("%02x", bytes[i] & 255));
        }
        return out.append(']').toString();
    }
    private static void equal(String id, int role, String input) {
        OwnerIdentityParser.Result result = OwnerIdentityParser.parse(input);
        if (!id.equals(result.identity) || role != result.role)
            throw new AssertionError("Parsed identity or role differs from synthetic fixture.");
        assertions++;
    }
    private static void rejects(String input) {
        try { OwnerIdentityParser.parse(input); }
        catch (IllegalArgumentException expected) { assertions++; return; }
        throw new AssertionError("Invalid/ambiguous identity setup was accepted.");
    }
}
