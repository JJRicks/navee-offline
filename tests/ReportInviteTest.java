package com.jjricks.volt;

import java.util.Collections;
import java.util.Random;

/** Who is asked to share a compatibility report, how answers persist, and exactly what the email holds. */
public final class ReportInviteTest {
    private static int assertions;
    private static final long NOW = 1_800_000_000_000L;
    public static void main(String[] args) {
        models(); answers(); email();
        System.out.println("ReportInvite tests passed: " + assertions + " assertions");
    }

    private static void models() {
        check(ReportInvite.model(ModelRegistryTest.advertised("39991")).equals("39991"), "An unreported standard model is asked about");
        check(ReportInvite.model(ModelRegistryTest.advertised("24431")).equals("24431"), "An unreported mapped model is asked about");
        check(ReportInvite.model(ModelRegistryTest.advertised("22131")).isEmpty(), "The N65i already has a report");
        check(ReportInvite.model(ModelRegistryTest.serial("Q2538ZZ9US7777777")).isEmpty()
                && ReportInvite.model(ModelRegistryTest.advertised("25381")).isEmpty(), "The UT5 MAX is never asked about");
        check(ReportInvite.model(ModelRegistryTest.serial("Q2443ZZ9US7777777")).isEmpty(), "Without an advertised Model ID there is nothing to key the answer on");
        check(ReportInvite.model(ModelRegistryTest.both("Q2547ZZ9US7777777", "24431")).isEmpty(), "A Model ID that contradicts the serial is not asked about");
        check(ReportInvite.model(DeviceDescriptor.simulated("39991", 0)).isEmpty(), "The developer simulator is never asked about");
        check(ReportInvite.model(null).isEmpty(), "No scooter, no offer");
        for (String pid : ModelRegistry.EXO) check(ReportInvite.model(ModelRegistryTest.advertised(pid)).isEmpty(), "Unsupported products are not asked about: " + pid);
    }

    private static void answers() {
        check(ReportInvite.due("", NOW) && ReportInvite.due(null, NOW), "No answer yet: ask");
        check(!ReportInvite.due("never", NOW) && !ReportInvite.due("sent", NOW), "Don't ask again and a sent report end the offer");
        check(!ReportInvite.due(ReportInvite.later(NOW - ReportInvite.LATER_MS + 1), NOW), "Not now waits three days");
        check(ReportInvite.due(ReportInvite.later(NOW - ReportInvite.LATER_MS), NOW), "Not now asks again after three days");
        check(!ReportInvite.due("later:soon", NOW) && !ReportInvite.due("maybe", NOW), "An unreadable answer never nags");
        BackupRegistry.Rule rule = BackupRegistry.rule("app_settings", ReportInvite.PREFIX + "39991");
        check(rule != null && !rule.excluded() && !rule.perProfile, "Answers back up with app preferences, per model rather than per scooter");
    }

    private static void email() {
        DeviceDescriptor d = ModelRegistryTest.advertised("39991");
        DiscoveryCoordinator check = new DiscoveryCoordinator(NOW, false);
        check.interrupt(NOW + 10);
        CompatibilityReport report = new CompatibilityReport.Builder("1.5.1", new Random(7)).device(d, ModelRegistry.resolve(d), "challenge_free")
                .reads(check.reads(), check.passive(), check.startMs()).fields(StatusFields.NONE)
                .decisions(Collections.<CapabilityResolver.Decision>emptyList()).tests(null).build();
        check(report != null, "A report builds");
        String body = ReportInvite.body(report);
        check(body.contains(report.summary()) && body.endsWith(report.json()), "The email is the preview's summary and JSON, unchanged");
        check(body.startsWith(ReportInvite.NAME_PROMPT + "\n\nThis came from Volt's read-only compatibility check."), "The email asks for the model name, then says where it came from");
        check(ReportInvite.subject("39991").equals("Volt compatibility report · Model 39991"), "Subject names the Model ID");
        check(ReportInvite.ADDRESS.equals("volt-reports@jjricks.com"), "Reports go to the dedicated alias");
    }

    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
}
