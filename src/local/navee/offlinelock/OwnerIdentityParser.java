package local.navee.offlinelock;

import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Imports only explicit local setup text or checksum-verified OEM owner-auth frames. */
public final class OwnerIdentityParser {
    private static final int MAX_INPUT = 1048576;
    private static final Pattern ID_FIELD = Pattern.compile(
            "(?im)(?<![\\w])(?:\"owner_identity\"|owner_identity|owner identity|\"identity\"|identity)\\s*:\\s*\"?([0-9a-f]{12})(?=\"?(?:\\s*(?:[,}\\r\\n]|$)))");
    private static final Pattern ID_KEY = Pattern.compile(
            "(?i)(?<![\\w])(?:\"owner_identity\"|owner_identity|owner identity|\"identity\"|identity)\\s*:");
    private static final Pattern ROLE_FIELD = Pattern.compile(
            "(?im)(?<![\\w])(?:\"role\"|role)\\s*:\\s*\"?([01])(?=\"?(?:\\s*(?:[,}\\r\\n]|$)))");
    private static final Pattern ROLE_KEY = Pattern.compile("(?i)(?<![\\w])(?:\"role\"|role)\\s*:");
    private static final Pattern SHARED_FIELD = Pattern.compile(
            "(?im)^\\s*Shared-user option\\s*:\\s*(ON|OFF)\\s*$");
    private static final String AUTH_MARKER = "Send authData:";

    public static final class Result {
        public final String identity;
        public final int role;
        Result(String identity, int role) { this.identity = identity; this.role = role; }
    }

    public static Result parse(String input) {
        if (input == null || input.trim().isEmpty() || input.length() > MAX_INPUT)
            throw new IllegalArgumentException("Setup file is empty or too large.");
        String trimmed = input.trim();
        if (trimmed.matches("(?i)[0-9a-f]{12}"))
            return new Result(trimmed.toUpperCase(Locale.ROOT), 0);

        Matcher ids = ID_FIELD.matcher(input);
        String explicitId = null;
        int identityFields = 0;
        while (ids.find()) {
            identityFields++;
            String value = ids.group(1).toUpperCase(Locale.ROOT);
            if (explicitId != null && !explicitId.equals(value)) ambiguous();
            explicitId = value;
        }
        if (count(ID_KEY, input) != identityFields)
            throw new IllegalArgumentException("Owner identity must contain exactly 12 hexadecimal characters.");
        if (explicitId != null) return new Result(explicitId, explicitRole(input));

        Result found = null;
        for (String line : input.split("\\r?\\n")) {
            int at = line.indexOf(AUTH_MARKER);
            if (at < 0) continue;
            String candidate = line.substring(at + AUTH_MARKER.length()).trim();
            if (candidate.startsWith("[")) {
                int end = candidate.indexOf(']');
                if (end < 0) continue;
                candidate = candidate.substring(1, end).trim();
            }
            // Never manufacture a packet by stripping arbitrary log text into hex.
            if (!candidate.matches("(?i)[0-9a-f]{2}(?:[\\s:-]*[0-9a-f]{2}){16}")) continue;
            byte[] p;
            try { p = Protocol.fromHex(candidate); } catch (RuntimeException invalid) { continue; }
            if (p.length != 17 || (p[0] & 255) != 0x55 || (p[1] & 255) != 0xaa
                    || p[2] != 0 || (p[3] & 255) != 0x30 || p[4] != 9
                    || (p[5] & 255) > 4 || (p[6] & 255) > 1 || p[13] != 0
                    || (p[15] & 255) != 0xfe || (p[16] & 255) != 0xfd) continue;
            int sum = 0;
            for (int i = 0; i < 14; i++) sum += p[i] & 255;
            if ((sum & 255) != (p[14] & 255)) continue;
            Result captured = new Result(Protocol.hex(Arrays.copyOfRange(p, 7, 13))
                    .toUpperCase(Locale.ROOT), p[6] & 255);
            if (found != null && (!found.identity.equals(captured.identity) || found.role != captured.role))
                ambiguous();
            found = captured;
        }
        if (found == null) throw new IllegalArgumentException(
                "No valid owner identity found. Import your private setup backup or a fresh NAVEE authentication capture.");
        return found;
    }

    private static int explicitRole(String input) {
        int role = -1;
        Matcher roles = ROLE_FIELD.matcher(input);
        int roleFields = 0;
        while (roles.find()) {
            roleFields++;
            int value = Integer.parseInt(roles.group(1));
            if (role != -1 && role != value) ambiguous();
            role = value;
        }
        if (count(ROLE_KEY, input) != roleFields)
            throw new IllegalArgumentException("Owner setup role must be 0 (owner) or 1 (shared user).");
        Matcher shared = SHARED_FIELD.matcher(input);
        while (shared.find()) {
            int value = "ON".equalsIgnoreCase(shared.group(1)) ? 1 : 0;
            if (role != -1 && role != value) ambiguous();
            role = value;
        }
        return role == -1 ? 0 : role;
    }

    private static int count(Pattern pattern, String input) {
        int result = 0;
        Matcher matcher = pattern.matcher(input);
        while (matcher.find()) result++;
        return result;
    }

    private static void ambiguous() {
        throw new IllegalArgumentException(
                "This file contains conflicting owner identities or roles. Capture one NAVEE account connection or import one private setup backup.");
    }
    private OwnerIdentityParser() { }
}
