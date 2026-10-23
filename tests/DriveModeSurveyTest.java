package com.jjricks.volt;

import java.util.Collections;
import java.util.Random;

/** Naming drive modes from the reported mode number alone: order, cycle detection, duplicates and the email block. */
public final class DriveModeSurveyTest {
    private static int assertions;
    public static void main(String[] args) {
        cycle(); duplicates(); liveFollow(); pressOnBeforeAnswering(); email();
        System.out.println("DriveModeSurvey tests passed: " + assertions + " assertions");
    }

    private static void cycle() {
        DriveModeSurvey s = new DriveModeSurvey();
        check(s.asking() == null && s.count() == 0 && !s.closed(), "Nothing asked before the first reading");
        check(s.observe(3) && s.asking() == 3, "The first reading is asked about");
        check(s.name(DriveModeSurvey.Label.SPORT) && s.asking() == null && s.used(DriveModeSurvey.Label.SPORT), "Naming it");
        check(!s.observe(3) && s.asking() == null, "The same mode again just waits for the next press");
        check(s.observe(2) && s.name(DriveModeSurvey.Label.ECO), "The next mode is asked about");
        check(s.observe(11) && s.name(DriveModeSurvey.Label.WALK), "And the next");
        check(!s.observe(2) && !s.closed(), "Back at a later mode is not a full cycle");
        check(!s.observe(3) && s.closed(), "Back at the first mode after naming others closes the cycle");
        check(!s.observe(7) && s.asking() == null, "Nothing more is asked once closed");
        check(s.summary().equals("Sport → Eco → Walk") && s.count() == 3, "Summary keeps the scooter's order");
        DriveModeSurvey one = new DriveModeSurvey(); one.observe(4); one.name(DriveModeSurvey.Label.ECO);
        check(!one.observe(4) && !one.closed(), "A single named mode never closes a cycle");
    }

    private static void duplicates() {
        DriveModeSurvey s = new DriveModeSurvey(); s.observe(1); s.name(DriveModeSurvey.Label.ECO); s.observe(2);
        check(!s.name(DriveModeSurvey.Label.ECO) && s.asking() == 2, "A name already given is refused");
        check(s.name(DriveModeSurvey.Label.UNSURE) && s.observe(5) && s.name(DriveModeSurvey.Label.UNSURE) && s.count() == 3,
                "Not sure can be used for several modes");
        check(!s.used(DriveModeSurvey.Label.UNSURE) && s.used(DriveModeSurvey.Label.ECO), "Not sure never blocks itself");
        check(!new DriveModeSurvey().name(DriveModeSurvey.Label.ECO), "Nothing to name before a reading");
    }

    private static void liveFollow() {
        DriveModeSurvey s = new DriveModeSurvey(); s.observe(1);
        check(s.observe(2) && s.asking() == 2, "A press while being asked follows the live mode");
        s.name(DriveModeSurvey.Label.DRIVE);
        check(s.summary().equals("Drive") && !s.observe(2), "The answer belongs to the mode shown when it was given");
    }

    /** The owner presses on before answering: a mode already named keeps its name and is never asked about again. */
    private static void pressOnBeforeAnswering() {
        DriveModeSurvey s = new DriveModeSurvey();
        s.observe(3); s.name(DriveModeSurvey.Label.SPORT); s.observe(2); s.name(DriveModeSurvey.Label.ECO);
        check(s.observe(11) && s.asking() == 11, "A new mode is asked about");
        check(!s.observe(3) && s.asking() == null, "Pressing on to a named mode before answering does not ask about it again");
        check(s.closed() && s.named().get(3) == DriveModeSurvey.Label.SPORT && s.count() == 2, "Back at the first mode closes the cycle and keeps its name");
        check(!s.name(DriveModeSurvey.Label.UNSURE) && s.named().get(3) == DriveModeSurvey.Label.SPORT, "Not sure cannot overwrite a named mode");
        DriveModeSurvey t = new DriveModeSurvey(); t.observe(3); t.name(DriveModeSurvey.Label.SPORT); t.observe(2);
        check(!t.observe(3) && t.asking() == null && !t.closed(), "Back at the only named mode before answering just waits");
        check(!t.name(DriveModeSurvey.Label.UNSURE) && t.named().get(3) == DriveModeSurvey.Label.SPORT, "Nothing is being asked, so nothing is overwritten");
        check(t.observe(2) && t.asking() == 2 && t.name(DriveModeSurvey.Label.ECO) && t.summary().equals("Sport → Eco"), "The skipped mode is asked about when it comes round again");
    }

    private static void email() {
        DriveModeSurvey s = new DriveModeSurvey(); s.observe(2); s.name(DriveModeSurvey.Label.ECO); s.observe(3); s.name(DriveModeSurvey.Label.SPORT); s.observe(2);
        String json = OwnerNotes.json("39991", null, s);
        check(json.contains("\"format\": \"volt-owner-notes-v1\"") && json.contains("\"pid\": \"39991\"") && json.contains("\"drive_mode_cycle_closed\": true") && !json.contains("\"model\""), "Header");
        check(json.contains("\"code\": 2") && json.contains("\"label\": \"eco\"") && json.contains("\"code\": 3") && json.contains("\"label\": \"sport\"")
                && json.indexOf("\"code\": 2") < json.indexOf("\"code\": 3"), "Each mode number and its name, in order");
        DeviceDescriptor d = ModelRegistryTest.advertised("39991");
        DiscoveryCoordinator check = new DiscoveryCoordinator(1000, false); check.interrupt(1010);
        CompatibilityReport report = new CompatibilityReport.Builder("1.5.1", new Random(3)).device(d, ModelRegistry.resolve(d), "challenge_free")
                .reads(check.reads(), check.passive(), check.startMs()).fields(StatusFields.NONE)
                .decisions(Collections.<CapabilityResolver.Decision>emptyList()).tests(null).build();
        String body = ReportInvite.body(report, null, s, "39991");
        check(body.startsWith(ReportInvite.body(report)) && body.contains("}\n\nThe owner's answers:\nDrive modes: Eco → Sport\n{") && body.endsWith(json),
                "The email adds the names after the unchanged report");
        check(ReportInvite.body(report, null, null, "39991").equals(ReportInvite.body(report)) && ReportInvite.body(report, null, new DriveModeSurvey(), "39991").equals(ReportInvite.body(report)),
                "Skipping leaves the report as it was");
    }

    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
}
