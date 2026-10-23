package com.jjricks.volt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** When Volt offers a scooter's connection details, and that the email holds steps and codes but no identities. */
public final class ConnectionReportTest {
    private static int assertions;
    private static final long NOW = 1_800_000_000_000L;
    public static void main(String[] args) {
        attempts(); offer(); redaction(); report();
        System.out.println("ConnectionReport tests passed: " + assertions + " assertions");
    }

    private static ConnectionEventLog.Record record(long elapsedMs, ConnectionEventLog.Reason reason, int code, ConnectionEventLog.Phase phase) {
        return new ConnectionEventLog.Record(NOW + elapsedMs, 900_000_000L + elapsedMs, reason, code, true, false, phase, -1, -1);
    }
    private static List<ConnectionEventLog.Record> n65iLike() {
        // The N65i before its fix: each scan-started attempt timed out during the owner check.
        return Arrays.asList(
            record(0, ConnectionEventLog.Reason.CONNECT_ATTEMPT, -1, ConnectionEventLog.Phase.SCANNING),
            record(18_000, ConnectionEventLog.Reason.CONNECT_TIMEOUT, -1, ConnectionEventLog.Phase.AUTHENTICATING),
            record(32_000, ConnectionEventLog.Reason.EXPLICIT_DISCONNECT, -1, ConnectionEventLog.Phase.SCANNING),
            record(36_000, ConnectionEventLog.Reason.CONNECT_ATTEMPT, -1, ConnectionEventLog.Phase.SCANNING),
            record(54_000, ConnectionEventLog.Reason.CONNECT_TIMEOUT, -1, ConnectionEventLog.Phase.AUTHENTICATING));
    }

    private static void attempts() {
        check(ConnectionReport.failedAttempts(n65iLike()) == 2, "Two timed-out owner checks are two failures");
        List<ConnectionEventLog.Record> recovered = new ArrayList<>(n65iLike());
        recovered.add(record(60_000, ConnectionEventLog.Reason.AUTHENTICATED_READY, 0, ConnectionEventLog.Phase.READY));
        check(ConnectionReport.failedAttempts(recovered) == 0, "A successful connection resets the count");
        recovered.add(record(70_000, ConnectionEventLog.Reason.NATIVE_DISCONNECT, 147, ConnectionEventLog.Phase.CONNECTING));
        check(ConnectionReport.failedAttempts(recovered) == 1, "Only failures after the last success count");
        for (ConnectionEventLog.Reason r : new ConnectionEventLog.Reason[]{ConnectionEventLog.Reason.EXPLICIT_DISCONNECT, ConnectionEventLog.Reason.BACKGROUND_PAUSE,
                ConnectionEventLog.Reason.ADAPTER_OFF, ConnectionEventLog.Reason.IDENTITY_REJECTED, ConnectionEventLog.Reason.SERVICE_CLOSED, ConnectionEventLog.Reason.CHARGE_STARTED})
            check(!ConnectionReport.failure(r), "The owner's own action or a pause is not a failure: " + r);
        check(ConnectionReport.failure(ConnectionEventLog.Reason.AUTHENTICATION_FAILED) && ConnectionReport.failure(ConnectionEventLog.Reason.UNSUPPORTED_RESPONSE)
                && ConnectionReport.failure(ConnectionEventLog.Reason.SERVICE_DISCOVERY_FAILED), "Protocol failures count");
    }

    private static void offer() {
        check(ConnectionReport.due(true, n65iLike(), "", NOW), "A never-connected scooter that failed twice is offered");
        check(!ConnectionReport.due(false, n65iLike(), "", NOW), "A scooter that once connected is not (it is probably just off or away)");
        check(!ConnectionReport.due(true, n65iLike().subList(0, 2), "", NOW), "One failure is not enough");
        check(!ConnectionReport.due(true, n65iLike(), "never", NOW) && !ConnectionReport.due(true, n65iLike(), "sent", NOW), "Answers end the offer");
        check(!ConnectionReport.due(true, n65iLike(), ReportInvite.later(NOW - 1000), NOW)
                && ConnectionReport.due(true, n65iLike(), ReportInvite.later(NOW - ReportInvite.LATER_MS), NOW), "Not now waits three days");
        BackupRegistry.Rule rule = BackupRegistry.rule("app_settings", ConnectionReport.PREFIX + "0f8fad5b-d9cb-469f-a165-70867728950e");
        check(rule != null && !rule.excluded() && rule.perProfile, "Answers back up per scooter with app preferences");
    }

    private static void redaction() {
        for (String line : new String[]{"21:38:34  Bluetooth MTU 247", "21:38:35  Send 0x74 (7 bytes)", "21:38:35  Receive 0x70 · status 0 · 10 bytes",
                "21:38:52  Timed out", "21:38:52  Connection lost · retrying (147)", "21:38:52  Reconnect in 4s", "21:38:35  Ignored a scooter reply that failed its checks",
                "21:38:35  Scooter replies use the header-free checksum", "21:38:35  Owner identity rejected (3)", "21:38:35  Owner authenticated locally"})
            check(ConnectionReport.safe(line), "Shared: " + line);
        for (String line : new String[]{"21:38:52  This is Ollie's UT5. Select it in My scooters, or scan for your new scooter.",
                "21:38:52  Guided test approved", "21:38:52  Timed out for My Scooter", "Timed out", "21:38:52  Send 0x30 (17 bytes) 86001234", "", null})
            check(!ConnectionReport.safe(line), "Withheld: " + line);
    }

    private static void report() {
        List<String> activity = Arrays.asList("21:38:34  Bluetooth MTU 247", "21:38:34  This is Ollie's UT5. Select it in My scooters, or scan for your new scooter.",
                "21:38:52  Timed out");
        String json = ConnectionReport.json("1.5.1", 36, "samsung SM-F946U1", "22131", "standard", 247, n65iLike(), activity);
        check(json.contains("\"format\": \"volt-connection-report-v1\"") && json.contains("\"pid\": \"22131\"") && json.contains("\"android_sdk\": 36"), "Report header");
        check(json.contains("\"reason\": \"CONNECT_TIMEOUT\"") && json.contains("\"phase\": \"AUTHENTICATING\"") && json.contains("\"at_ms\": 54000"), "Events with relative times");
        check(!json.contains(Long.toString(NOW)) && !json.contains("900000000"), "No wall-clock or boot times");
        check(json.contains("\"Bluetooth MTU 247\"") && json.contains("\"Timed out\"") && !json.contains("Ollie") && !json.contains("My scooters"), "Only safe activity lines");
        check(!json.contains("21:38"), "Activity lines lose their time of day");
        check(ConnectionReport.json("1.5.1", 36, "x", "", "unknown", 23, new ArrayList<ConnectionEventLog.Record>(), new ArrayList<String>()).contains("\"pid\": \"unknown\""),
                "A scooter with no advertised Model ID says so");
        check(ConnectionReport.subject("22131").equals("Volt connection details · Model 22131")
                && ConnectionReport.subject("").equals("Volt connection details · model not identified"), "Subjects");
        check(ConnectionReport.body(json).startsWith(ReportInvite.NAME_PROMPT + "\n\nVolt could not connect to this scooter.") && ConnectionReport.body(json).endsWith(json),
                "Body asks for the model name, then a note and the report");
    }

    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
}
