package local.navee.offlinelock;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fixed capture transaction. No caller-provided command, path, package or log filter. */
final class OwnerCaptureEngine {
    static final String PACKAGE = "com.navee.ucaret", PROPERTY = "log.tag." + PACKAGE;
    static final int OK = 0, FAILED = 1, CANCELLED = 2, TIMED_OUT = 3, CONFLICT = 4, BUSY = 5;
    static final int PREPARING = 1, CONNECT_NAVEE = 2, CLEANING = 3;
    static final int MAX_OUTPUT = 1048576;
    static final long CAPTURE_MS = 90000;
    private static final Pattern EPOCH = Pattern.compile("^\\s*([0-9]{9,12}\\.[0-9]{1,9})\\s+");
    interface Host {
        String command(String... argv) throws Exception;
        void acquire(String token) throws Exception;
        void initialize(String original, String started) throws Exception;
        void arm(String token) throws Exception;
        void startLogging(String token, String component) throws Exception;
        void restoreLogging(String token) throws Exception;
        boolean owns(String token, String original) throws Exception;
        void release(String token, String original) throws Exception;
        boolean journal(String token, String original) throws Exception;
        void progress(int phase);
        boolean cancelled();
        long elapsed();
        void pause() throws Exception;
    }
    static final class Result {
        final int outcome; final boolean clean; final OwnerIdentityParser.Result owner;
        Result(int outcome, boolean clean, OwnerIdentityParser.Result owner) {
            this.outcome = outcome; this.clean = clean; this.owner = clean && outcome == OK ? owner : null;
        }
    }
    static boolean validToken(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    static boolean validProperty(String value) {
        return value != null && value.length() <= 91 && value.matches("[\\x20-\\x7e]*");
    }
    static String propertyOutput(String output) {
        // getprop adds exactly one newline; never trim the property's own spaces.
        if (output == null || !output.endsWith("\n")) throw new IllegalArgumentException("Property unreadable");
        String value = output.substring(0, output.length() - 1);
        if (!validProperty(value)) throw new IllegalArgumentException("Property unsupported");
        return value;
    }
    static String packageUid(String output) {
        String value = output == null ? "" : output.trim();
        Matcher match = Pattern.compile("package:com\\.navee\\.ucaret uid:([0-9]{5,8})").matcher(value);
        if (!match.matches()) throw new IllegalArgumentException("NAVEE UID unavailable");
        int uid = Integer.parseInt(match.group(1));
        if (uid < 10000 || uid >= 100000) throw new IllegalArgumentException("Invalid NAVEE UID");
        return match.group(1);
    }
    static int[] androidUsers(String output) {
        if (output == null || output.length() > 16384) throw new IllegalArgumentException("User list unavailable");
        String[] lines = output.trim().split("\\r?\\n");
        if (lines.length < 2 || lines.length > 65 || !"Users:".equals(lines[0].trim())) throw new IllegalArgumentException("User list unavailable");
        java.util.Set<Integer> found = new java.util.LinkedHashSet<>();
        for (int i = 1; i < lines.length; i++) {
            Matcher row = Pattern.compile("UserInfo\\{(0|[1-9][0-9]{0,3}):[^\\r\\n]*\\}(?: running)?").matcher(lines[i].trim());
            if (!row.matches() || !found.add(Integer.parseInt(row.group(1)))) throw new IllegalArgumentException("User list unavailable");
        }
        if (!found.contains(0)) throw new IllegalArgumentException("Owner profile unavailable");
        int[] users = new int[found.size()]; int n = 0; for (int user : found) users[n++] = user; return users;
    }
    static boolean hasNaveePackage(String output) {
        if (output == null || output.length() > 16384) throw new IllegalArgumentException("Package list unavailable");
        if (output.trim().isEmpty()) return false;
        boolean found = false;
        for (String line : output.trim().split("\\r?\\n")) {
            Matcher row = Pattern.compile("package:([A-Za-z0-9_.]+) uid:([0-9]{5,10})").matcher(line.trim());
            if (!row.matches()) throw new IllegalArgumentException("Package list unavailable");
            long uid = Long.parseLong(row.group(2));
            if (uid < 10000 || uid > Integer.MAX_VALUE) throw new IllegalArgumentException("Package UID unavailable");
            found |= PACKAGE.equals(row.group(1));
        }
        return found;
    }
    static String launcher(String output) {
        String value = output == null ? "" : output.trim();
        if (!value.matches("com\\.navee\\.ucaret/(?:\\.?[A-Za-z_$][A-Za-z0-9_$]*)(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*"))
            throw new IllegalArgumentException("NAVEE launcher unavailable");
        return value;
    }
    static String phoneEpoch(String output) {
        String value = output == null ? "" : output.trim();
        if (!value.matches("[0-9]{9,12}\\.[0-9]{1,9}")) throw new IllegalArgumentException("Phone clock unavailable");
        return value;
    }
    /** No historical frames or explicit identity fields can enter the owner parser here. */
    static OwnerIdentityParser.Result freshOwner(String output, String since) {
        if (output == null || output.length() > MAX_OUTPUT) throw new IllegalArgumentException("Capture too large");
        BigDecimal threshold = new BigDecimal(phoneEpoch(since));
        StringBuilder frames = new StringBuilder();
        for (String line : output.split("\\r?\\n")) {
            Matcher stamp = EPOCH.matcher(line);
            if (!stamp.find() || new BigDecimal(stamp.group(1)).compareTo(threshold) < 0) continue;
            int marker = line.indexOf("Send authData:", stamp.end());
            if (marker < 0) continue;
            String payload = line.substring(marker + "Send authData:".length()).trim();
            if (payload.startsWith("[") && payload.endsWith("]")) payload = payload.substring(1, payload.length() - 1).trim();
            if (!payload.matches("(?i)[0-9a-f]{2}(?:[\\s:-]*[0-9a-f]{2}){16}")) continue;
            frames.append("Send authData: ").append(payload).append('\n');
        }
        if (frames.length() == 0) return null;
        try { return OwnerIdentityParser.parse(frames.toString()); }
        catch (IllegalArgumentException failure) {
            if (failure.getMessage() != null && failure.getMessage().contains("conflicting")) throw failure;
            return null;
        }
    }
    static Result capture(Host host, String token) {
        if (!validToken(token)) return new Result(FAILED, true, null);
        String original = null; boolean acquired = false, mutation = false, clean = true;
        int outcome = FAILED; OwnerIdentityParser.Result owner = null;
        long start = host.elapsed();
        try {
            host.progress(PREPARING);
            if (!"0".equals(host.command("/system/bin/am", "get-current-user").trim())) throw new Exception("Unsupported Android user");
            String uid = packageUid(host.command("/system/bin/cmd", "package", "list", "packages", "-U", "--user", "0", PACKAGE));
            int[] users = androidUsers(host.command("/system/bin/cmd", "user", "list", "--all"));
            for (int user : users) if (user != 0 && hasNaveePackage(host.command("/system/bin/cmd", "package", "list", "packages",
                    "-U", "--user", Integer.toString(user), PACKAGE))) throw new Exception("NAVEE installed in another Android profile");
            String component = launcher(host.command("/system/bin/cmd", "package", "resolve-activity", "--components", "--user", "0",
                    "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-p", PACKAGE));
            host.acquire(token); acquired = true;
            original = propertyOutput(host.command("/system/bin/getprop", PROPERTY));
            String since = phoneEpoch(host.command("/system/bin/date", "+%s.%N"));
            host.initialize(original, since);
            if (!host.journal(token, original)) throw new Exception();
            if (host.cancelled()) throw new InterruptedException();
            mutation = true; clean = false;
            host.arm(token);
            host.startLogging(token, component);
            host.progress(CONNECT_NAVEE);
            while (host.elapsed() - start < CAPTURE_MS) {
                if (host.cancelled()) { outcome = CANCELLED; break; }
                OwnerIdentityParser.Result next = freshOwner(host.command("/system/bin/logcat", "-d", "-v", "epoch",
                        "-t", since, "--uid=" + uid, "-e", "Send authData:", "-s", PACKAGE + ":D", "*:S"), since);
                if (owner != null && next != null) {
                    if (!owner.identity.equals(next.identity) || owner.role != next.role)
                        throw new IllegalArgumentException("conflicting capture");
                    outcome = OK; break;
                }
                if (next != null) owner = next;
                host.pause();
            }
            if (outcome == FAILED && host.elapsed() - start >= CAPTURE_MS) outcome = TIMED_OUT;
        } catch (CaptureBusy busy) { outcome = BUSY; }
        catch (IllegalArgumentException invalid) {
            outcome = invalid.getMessage() != null && invalid.getMessage().contains("conflicting") ? CONFLICT : FAILED;
        } catch (Exception ignored) { outcome = host.cancelled() ? CANCELLED : FAILED; }
        finally {
            if (acquired) {
                host.progress(CLEANING);
                clean = cleanup(host, token, original, mutation);
            }
        }
        if (host.cancelled() && outcome == OK) outcome = CANCELLED;
        return new Result(outcome, clean, owner);
    }
    static boolean cleanup(Host host, String token, String original, boolean mutation) {
        try {
            if (!validToken(token) || (original != null && !validProperty(original)) || !host.owns(token, original)) return false;
            if (mutation) {
                // NAVEE caches loggability. A restored property without stopping it is not cleanup.
                host.restoreLogging(token);
                if (!original.equals(propertyOutput(host.command("/system/bin/getprop", PROPERTY)))) return false;
            }
            host.release(token, original);
            return true;
        } catch (Exception ignored) { return false; }
    }
    static final class CaptureBusy extends Exception { private static final long serialVersionUID = 1L; }
    private OwnerCaptureEngine() { }
}
