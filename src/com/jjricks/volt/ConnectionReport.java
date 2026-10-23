package com.jjricks.volt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Connection details for a scooter that never connected: what happened at each step, and nothing
 * about who or where. Decisions and text only; MainActivity offers it and opens the email.
 */
final class ConnectionReport {
    /** app_settings key prefix, one answer per scooter profile: "never", "sent" or "later:<wall ms>". */
    static final String PREFIX = "connect_report_";
    /** Failed attempts before Volt offers to send the details; one failure is often just range or power. */
    static final int ATTEMPTS = 2;
    static final int MAX_EVENTS = 40, MAX_ACTIVITY = 60;
    private ConnectionReport() {}

    /** A step the scooter should have passed and did not; ordinary pauses and the owner's own actions never count. */
    static boolean failure(ConnectionEventLog.Reason reason) {
        switch (reason) {
            case CONNECT_TIMEOUT: case CONNECT_FAILED: case NATIVE_DISCONNECT: case SERVICE_DISCOVERY_FAILED:
            case NOTIFICATION_FAILED: case AUTHENTICATION_FAILED: case UNSUPPORTED_RESPONSE: case WRITE_FAILED: case WRITE_TIMEOUT:
                return true;
            default: return false;
        }
    }

    /** Failed attempts since the last successful connection, newest first, stopping at that success. */
    static int failedAttempts(List<ConnectionEventLog.Record> records) {
        int failed = 0;
        for (int i = records.size() - 1; i >= 0; i--) {
            ConnectionEventLog.Reason reason = records.get(i).reason;
            if (reason == ConnectionEventLog.Reason.AUTHENTICATED_READY) break;
            if (failure(reason)) failed++;
        }
        return failed;
    }

    /** Offer once enough attempts have failed on a scooter that has never connected, unless the owner answered. */
    static boolean due(boolean neverConnected, List<ConnectionEventLog.Record> records, String stored, long nowMs) {
        return neverConnected && failedAttempts(records) >= ATTEMPTS && ReportInvite.due(stored, nowMs);
    }

    // Connection activity is shared only when it is a protocol step or a fixed notice: a notice can name a saved scooter.
    private static final Pattern SAFE = Pattern.compile("\\d\\d:\\d\\d:\\d\\d  (?:"
            + "(?:Send|Receive) 0x[0-9A-F]{2}(?: · status \\d+)? · \\d+ bytes|Send 0x[0-9A-F]{2} \\(\\d+ bytes\\)"
            + "|Bluetooth MTU \\d+|Reconnect in \\d+s|Owner authenticated locally|Model ID captured"
            + "|Ignored a scooter reply that failed its checks|Scooter replies use the header-free checksum"
            + "|(?:Timed out|Connection lost|Signal lost|Connection failed|Send failed|Owner check failed|Owner identity rejected"
            + "|Unsupported scooter|Unsupported firmware|Unrecognised scooter|Not the saved scooter|Bluetooth off)"
            + "(?: · retrying| · change cancelled)?(?: \\(-?\\d+\\))?)");
    static boolean safe(String line) { return line != null && SAFE.matcher(line).matches(); }

    /**
     * The report: app, Android and phone, the scooter's advertised Model ID if any, then the saved
     * connection events (times relative to the first one) and the safe activity lines, in order and without times of day.
     */
    static String json(String appVersion, int sdk, String phone, String modelId, String transport, int mtu,
            List<ConnectionEventLog.Record> records, List<String> activity) {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("format", "volt-connection-report-v1");
        tree.put("app_version", appVersion);
        Map<String, Object> phoneInfo = new LinkedHashMap<>();
        phoneInfo.put("android_sdk", (long) sdk); phoneInfo.put("model", phone);
        tree.put("phone", phoneInfo);
        Map<String, Object> scooter = new LinkedHashMap<>();
        scooter.put("pid", modelId == null || modelId.isEmpty() ? "unknown" : modelId);
        scooter.put("transport", transport); scooter.put("mtu", (long) mtu);
        tree.put("scooter", scooter);
        List<Object> events = new ArrayList<>();
        int from = Math.max(0, records.size() - MAX_EVENTS);
        long start = records.isEmpty() ? 0 : records.get(from).elapsedMs;
        for (int i = from; i < records.size(); i++) {
            ConnectionEventLog.Record r = records.get(i);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("at_ms", r.elapsedMs - start); e.put("reason", r.reason.name()); e.put("phase", r.phase.name());
            if (r.statusCode >= 0) e.put("status_code", (long) r.statusCode);
            e.put("ui_visible", r.visible);
            events.add(e);
        }
        tree.put("connection_events", events);
        List<Object> lines = new ArrayList<>();
        // Shared in order without their time of day.
        for (String line : activity) if (safe(line)) lines.add(line.substring(10));
        tree.put("activity", lines.size() > MAX_ACTIVITY ? new ArrayList<>(lines.subList(lines.size() - MAX_ACTIVITY, lines.size())) : lines);
        return CompatibilityReport.json(tree);
    }

    static String subject(String modelId) {
        return "Volt connection details · " + (modelId == null || modelId.isEmpty() ? "model not identified" : "Model " + modelId);
    }

    /** The whole email: a short note, then the report. */
    static String body(String json) {
        return ReportInvite.NAME_PROMPT + "\n\nVolt could not connect to this scooter. These are its connection details; it is everything Volt sends, and nothing is attached.\n\n" + json;
    }
}
