package com.jjricks.volt;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** The model picker's list and search, write-ins, and the owner-notes block both report emails carry. */
public final class NaveeModelsTest {
    private static int assertions;
    public static void main(String[] args) {
        list(); search(); choices(); notes();
        System.out.println("NaveeModels tests passed: " + assertions + " assertions");
    }

    private static void list() {
        check(NaveeModels.SERIES.size() == 11 && NaveeModels.all().size() == 49, "Eleven series, 49 models");
        check(new java.util.HashSet<>(NaveeModels.all()).size() == 49, "No model is listed twice");
        check(NaveeModels.seriesOf("N65i").name.equals("N") && NaveeModels.seriesOf("ST3 Pro").about.equals("Premium commuting") && NaveeModels.seriesOf("Nope") == null, "Series lookup");
    }

    private static void search() {
        check(NaveeModels.search("").size() == 49, "Empty search lists everything");
        check(NaveeModels.search("gt3").equals(Arrays.asList("GT3", "GT3 Pro", "GT3 Max")), "Name search: " + NaveeModels.search("gt3"));
        check(NaveeModels.search("UT5 MAX").equals(Collections.singletonList("UT5 Max")) && NaveeModels.search("ut-5max").equals(Collections.singletonList("UT5 Max")), "Case and spacing are ignored");
        check(NaveeModels.search("kids").size() == 4 && NaveeModels.search("off-road").size() == 3, "Series descriptions match");
        check(NaveeModels.search("easyride").size() == 2 && NaveeModels.search("v40i pro").containsAll(Arrays.asList("V40i Pro", "V40i Pro II")), "Bracketed and numbered variants match");
        check(NaveeModels.search("zzz").isEmpty(), "No match");
    }

    private static void choices() {
        check(NaveeModels.exact("ut5 max").equals("UT5 Max") && NaveeModels.exact("E20").equals("E20 (EasyRide 20)") && NaveeModels.exact("gt3") .equals("GT3") && NaveeModels.exact("gt") == null, "Exact names");
        NaveeModels.Choice typedListed = NaveeModels.Choice.typedName("  gt3   pro ");
        check(typedListed.source == NaveeModels.Choice.Source.LIST && typedListed.name.equals("GT3 Pro"), "A typed name that is listed counts as picking it");
        NaveeModels.Choice writeIn = NaveeModels.Choice.typedName("Mystery\tX9\n");
        check(writeIn.source == NaveeModels.Choice.Source.TYPED && writeIn.name.equals("Mystery X9") && writeIn.known(), "A write-in is cleaned");
        StringBuilder longName = new StringBuilder(); for (int i = 0; i < 100; i++) longName.append('x');
        check(NaveeModels.Choice.typedName(longName.toString()).name.length() == NaveeModels.MAX_TYPED, "A write-in is capped");
        check(!NaveeModels.Choice.typedName("   ").known() && !NaveeModels.Choice.unsure().known(), "Blank is Not sure");
        boolean refused = false; try { NaveeModels.Choice.listed("Nope"); } catch (IllegalArgumentException expected) { refused = true; }
        check(refused, "Only listed models can be picked from the list");
    }

    private static void notes() {
        NaveeModels.Choice gt3 = NaveeModels.Choice.listed("GT3 Pro");
        check(OwnerNotes.firstLine(gt3).equals("Scooter model, from the owner: GT3 Pro") && OwnerNotes.firstLine(null).equals(ReportInvite.NAME_PROMPT)
                && OwnerNotes.firstLine(NaveeModels.Choice.unsure()).equals(ReportInvite.NAME_PROMPT), "First line names the model or asks for it");
        DriveModeSurvey modes = new DriveModeSurvey(); modes.observe(1); modes.name(DriveModeSurvey.Label.ECO); modes.observe(3); modes.name(DriveModeSurvey.Label.SPORT); modes.observe(1);
        String json = OwnerNotes.json("23452", gt3, modes);
        check(json.contains("\"format\": \"volt-owner-notes-v1\"") && json.contains("\"pid\": \"23452\"") && json.contains("\"source\": \"list\"") && json.contains("\"name\": \"GT3 Pro\"")
                && json.contains("\"label\": \"eco\"") && json.contains("\"code\": 3") && json.contains("\"drive_mode_cycle_closed\": true"), "Owner notes hold both answers");
        String unsure = OwnerNotes.json("", NaveeModels.Choice.unsure(), null);
        check(unsure.contains("\"pid\": \"unknown\"") && unsure.contains("\"source\": \"unsure\"") && !unsure.contains("\"name\"") && !unsure.contains("drive_modes"), "Not sure is recorded without a name");
        check(OwnerNotes.section("1", null, null).isEmpty() && OwnerNotes.section("1", NaveeModels.Choice.unsure(), new DriveModeSurvey()).isEmpty(), "Nothing added, nothing appended");
        DeviceDescriptor d = ModelRegistryTest.advertised("39991");
        DiscoveryCoordinator c = new DiscoveryCoordinator(1000, false); c.interrupt(1010);
        CompatibilityReport report = new CompatibilityReport.Builder("1.5.1", new Random(5)).device(d, ModelRegistry.resolve(d), "challenge_free")
                .reads(c.reads(), c.passive(), c.startMs()).fields(StatusFields.NONE).decisions(Collections.<CapabilityResolver.Decision>emptyList()).tests(null).build();
        String body = ReportInvite.body(report, gt3, modes, "39991");
        check(body.startsWith("Scooter model, from the owner: GT3 Pro\n\nThis came from Volt's read-only compatibility check.") && body.contains(report.json())
                && body.contains("Drive modes, named by the owner: Eco → Sport") && body.endsWith(OwnerNotes.json("39991", gt3, modes)), "Report email with the owner's answers");
        check(ReportInvite.body(report).startsWith(ReportInvite.NAME_PROMPT) && !ReportInvite.body(report).contains("volt-owner-notes"), "Without answers the email asks for the name");
        String connection = ConnectionReport.json("1.5.1", 36, "x", "", "unknown", 23, Collections.<ConnectionEventLog.Record>emptyList(), Collections.<String>emptyList());
        String details = ConnectionReport.body(connection, NaveeModels.Choice.typedName("GT3"), "");
        check(details.startsWith("Scooter model, from the owner: GT3\n\nVolt could not connect to this scooter.") && details.contains("\"name\": \"GT3\"")
                && details.contains("\"pid\": \"unknown\""), "Connection details carry the picked model even without a Model ID");
    }

    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
}
