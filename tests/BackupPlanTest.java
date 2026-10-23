package com.jjricks.volt;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Restore planning: scooter resolution, merge rules and duplicate protection, all synthetic. */
public final class BackupPlanTest {
    private static int checks;
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private interface Action { void run() throws Exception; }
    private static void rejects(Action action, String message) throws Exception { checks++; try { action.run(); throw new AssertionError(message); } catch (IOException expected) { } }
    private static final String A = BackupTestStore.A, B = BackupTestStore.B, C = BackupArchiveTest.C;
    private static Set<String> set(String... values) { return new LinkedHashSet<>(Arrays.asList(values)); }

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("volt-backup-plan-").toFile();
        try {
            BackupTestStore source = BackupTestStore.rich(new File(root, "source"));
            byte[] complete = source.export(BackupTestStore.all());
            resolution(root, complete);
            scooterOnlyDestinations(root);
            damagedDestination(root, complete);
            merges(root, source, complete);
            duplicates(root, source);
            sharedDestination(root, complete);
            freshInstall(root, source);
            chargeLimit(root);
            noScooterLayout(root, source);
            modelEvidence(root);
            System.out.println("BackupPlanTest: " + checks + " checks passed (profile resolution, merge rules, UUID and clock duplicates, shared destinations, fresh install, charge limit, no-scooter layout, model evidence fields)");
        } finally { AppDataArchive.deleteTree(root); }
    }

    /** The compatibility registry's profile fields (advertised PID, firmware, default name) survive
     * a merge and an exact restore unchanged, and a backup with values outside their bounds is refused. */
    private static void modelEvidence(File root) throws Exception {
        String d = "d0000000-0000-4000-8000-00000000000d";
        String evidence = "\"pid_advertised\":\"24431\",\"pid_advertised_wall\":1760000000000,\"name_default\":true,\"fw_meter\":\"2.0.3.1\",\"fw_controller\":\"1.0.4\",\"fw_bms\":\"0.9\",\"fw_screen\":\"\",\"fw_uwb\":\"\",\"fw_wall\":1760000000001";
        String item = "{\"id\":\"" + d + "\",\"name\":\"NAVEE 24431\",\"identity\":\"ABCDEF012345\",\"role\":0,\"mac\":\"02:00:00:00:00:0D\",\"serial\":\"SERIAL-D\",\"lastConnected\":5," + evidence + ",\"fw_future\":\"kept\"}";
        BackupTestStore source = new BackupTestStore(new File(root, "evidence-source"));
        source.put("profiles", "items", "[" + item + "]"); source.put("profiles", "active", d); source.put("onboarding", "onboarding_done", true);
        check(BackupArchive.profiles(source.preferences()).get(0).raw.equals(item), "the source profile carries every model evidence field");
        BackupTestStore phone = BackupArchiveTest.phone(new File(root, "evidence-merge"));
        phone.restore(source.export(set(BackupRegistry.SCOOTERS)), set(BackupRegistry.SCOOTERS), false, null);
        BackupProfiles.Profile merged = BackupProfiles.find(BackupArchive.profiles(phone.preferences()), d);
        check(merged != null && merged.raw.equals(item), "a merged scooter keeps its advertised PID, firmware, default-name flag and newer fields exactly");
        Map<String, Object> fields = BackupJson.object(BackupJson.parse(merged.raw, BackupProfiles.MAX_DEPTH));
        check("24431".equals(fields.get("pid_advertised")) && Long.valueOf(1760000000000L).equals(fields.get("pid_advertised_wall")) && Boolean.TRUE.equals(fields.get("name_default"))
                && "2.0.3.1".equals(fields.get("fw_meter")) && Long.valueOf(1760000000001L).equals(fields.get("fw_wall")), "model evidence values read back after a merge");
        BackupTestStore exact = new BackupTestStore(new File(root, "evidence-exact"));
        exact.restore(source.export(BackupTestStore.all()), BackupTestStore.all(), true, null);
        check(("[" + item + "]").equals(exact.preferences().get("profiles").get("items")), "Replace everything restores the profile list byte for byte");
        String[][] hostile = {{"\"pid_advertised\":\"24431\"", "\"pid_advertised\":\"2443X\""}, {"\"pid_advertised\":\"24431\"", "\"pid_advertised\":24431"},
            {"\"fw_meter\":\"2.0.3.1\"", "\"fw_meter\":\"2.0.3.1-0123456789\""}, {"\"fw_bms\":\"0.9\"", "\"fw_bms\":\"0.\\u00e9\""}, {"\"fw_bms\":\"0.9\"", "\"fw_bms\":null"},
            {"\"pid_advertised_wall\":1760000000000", "\"pid_advertised_wall\":\"soon\""}, {"\"fw_wall\":1760000000001", "\"fw_wall\":true"}, {"\"name_default\":true", "\"name_default\":\"yes\""}};
        int n = 0;
        for (String[] change : hostile) {
            BackupTestStore bad = new BackupTestStore(new File(root, "evidence-bad-" + n++));
            bad.put("profiles", "items", "[" + item.replace(change[0], change[1]) + "]");
            byte[] bytes = bad.export(set(BackupRegistry.SCOOTERS));
            BackupTestStore target = new BackupTestStore(new File(root, "evidence-target-" + n));
            rejects(() -> target.stage(bytes), "a backup profile with " + change[1] + " is refused");
        }
    }

    /** Every selected incoming profile gets a choice, including one with no rides or settings. */
    private static void scooterOnlyDestinations(File root) throws Exception {
        BackupTestStore source = new BackupTestStore(new File(root, "profiles-only-source"));
        source.put("profiles", "items", "[" + BackupTestStore.profileItem(A, "First", "ABCDEF012345", "A-ONLY") + "," + BackupTestStore.profileItem(B, "No recordings", "", "B-ONLY") + "]");
        source.put("profiles", "active", A);
        source.ride(A, 1760000000000L, false, "Only A has a ride");
        Set<String> scooters = set(BackupRegistry.SCOOTERS);
        byte[] bytes = source.export(scooters);
        BackupTestStore skipped = new BackupTestStore(new File(root, "profiles-only-skip"));
        BackupArchive.Prepared prepared = skipped.stage(bytes);
        List<BackupArchive.Destination> needed = BackupArchive.destinations(skipped, prepared, scooters);
        check(needed.size() == 2 && needed.get(0).source.equals(A) && needed.get(1).source.equals(B), "Scooters alone asks about both incoming profiles in their saved order");
        check(needed.get(0).addable && A.equals(needed.get(0).suggested) && needed.get(1).addable, "new profiles may explicitly be added from backup");
        rejects(() -> BackupArchive.plan(skipped, prepared, scooters, false, Collections.emptyMap()), "profile-only restoration cannot silently add unresolved scooters");
        Map<String, String> choices = new LinkedHashMap<>(); choices.put(A, BackupArchive.SKIP); choices.put(B, BackupArchive.SKIP);
        BackupArchive.Plan nothing = BackupArchive.plan(skipped, prepared, scooters, false, choices);
        check(nothing.preferences.isEmpty() && nothing.lines.contains("2 scooters skipped · not restored"), "Don't restore is honored for a profiles-only backup");
        BackupTransaction.apply(skipped, nothing);
        check(BackupArchive.profiles(skipped.preferences()).isEmpty(), "skipped profiles were not added by the transaction");

        BackupTestStore mapped = BackupArchiveTest.phone(new File(root, "profiles-only-map"));
        Map<String, Map<String, Object>> before = mapped.preferences();
        choices.put(A, C); choices.put(B, BackupArchive.SKIP);
        BackupArchive.Plan existing = BackupArchive.plan(mapped, mapped.stage(bytes), scooters, false, choices);
        BackupTransaction.apply(mapped, existing);
        check(mapped.preferences().get("profiles").equals(before.get("profiles")), "choosing an existing scooter keeps its name, credentials and profile metadata");
        check(existing.lines.contains("1 scooter skipped · not restored"), "the unmapped second profile stays skipped when the first maps to an existing scooter");

        BackupTestStore mixed = new BackupTestStore(new File(root, "profiles-plus-rides"));
        Set<String> categories = set(BackupRegistry.SCOOTERS, BackupRegistry.RIDES);
        BackupArchive.Prepared both = mixed.stage(source.export(categories));
        check(BackupArchive.destinations(mixed, both, categories).size() == 2, "a profile with no selected attached data still gets a destination in a mixed restore");
        choices.put(A, A); choices.put(B, BackupArchive.SKIP);
        BackupArchive.Plan selected = BackupArchive.plan(mixed, both, categories, false, choices);
        BackupTransaction.apply(mixed, selected);
        List<BackupProfiles.Profile> profiles = BackupArchive.profiles(mixed.preferences());
        check(profiles.size() == 1 && profiles.get(0).id.equals(A) && A.equals(mixed.preferences().get("profiles").get("active")), "only the explicitly added profile becomes active");
        check(new RideStore(new File(mixed.files(), "rides")).list().size() == 1, "the chosen profile's ride is restored normally");
    }

    /** A complete restore repairs malformed destination JSON, while partial merges still refuse
     * it and transaction rollback keeps the exact unreadable value if a restore is interrupted. */
    private static void damagedDestination(File root, byte[] complete) throws Exception {
        BackupTestStore phone = BackupArchiveTest.phone(new File(root, "damaged-destination"));
        phone.put("profiles", "items", "[{broken profile JSON");
        Map<String, Map<String, Object>> before = phone.preferences();
        Map<String, String> files = phone.fileHashes();
        check(BackupArchive.hasData(phone), "unreadable live inventory is existing data, never a fresh-install default");
        BackupArchive.Prepared prepared = phone.stage(complete);
        rejects(() -> BackupArchive.destinations(phone, prepared, set(BackupRegistry.SCOOTERS)), "destination matching refuses malformed local identities");
        rejects(() -> BackupArchive.plan(phone, prepared, set(BackupRegistry.PREFERENCES), false, Collections.emptyMap()), "partial restore refuses to merge while current profiles are unreadable");
        check(phone.preferences().equals(before) && phone.fileHashes().equals(files), "refused partial plans changed nothing");
        BackupArchive.Plan replace = BackupArchive.plan(phone, prepared, BackupTestStore.all(), true, Collections.emptyMap());
        check(replace.replace && replace.lines.contains("Replaces everything on this phone"), "explicit complete replacement can be reviewed with malformed destination profiles");
        phone.failStep = "live-sync";
        rejects(() -> BackupTransaction.apply(phone, replace), "injected failure before commit reports failure");
        check(phone.preferences().equals(before) && phone.fileHashes().equals(files), "failed complete restore rolls back the exact raw corrupt profiles and all other data");
        BackupArchive.discard(phone, prepared);
        BackupArchive.Plan retry = BackupArchive.plan(phone, phone.stage(complete), BackupTestStore.all(), true, Collections.emptyMap());
        phone.crashPrefsAt = "profiles";
        try { BackupTransaction.apply(phone, retry); throw new AssertionError("injected process death must occur"); }
        catch (BackupTestStore.Crash expected) { }
        BackupTestStore reopened = new BackupTestStore(phone.root);
        check(BackupTransaction.recover(reopened) && reopened.preferences().equals(before) && reopened.fileHashes().equals(files), "process-death recovery restores the unreadable raw profile snapshot without parsing it");
        reopened.restore(complete, BackupTestStore.all(), true, null);
        check(BackupArchive.profiles(reopened.preferences()).size() == 2 && !reopened.preferences().get("profiles").get("items").equals(before.get("profiles").get("items")), "a successful complete restore repairs the damaged profile list");
        check(!BackupTransaction.needsRecovery(reopened), "successful repair leaves no transaction behind");
        check(!BackupArchive.hasData(new BackupTestStore(new File(root, "genuinely-empty"))), "a genuinely empty phone still permits the fresh-install default");
    }

    private static void resolution(File root, byte[] complete) throws Exception {
        BackupTestStore phone = BackupArchiveTest.phone(new File(root, "resolve"));
        BackupArchive.Prepared prepared = phone.stage(complete);
        List<BackupArchive.Destination> rides = BackupArchive.destinations(phone, prepared, set(BackupRegistry.RIDES));
        check(rides.size() == 2 && rides.get(0).source.equals(A) && C.equals(rides.get(0).suggested) && !rides.get(0).addable, "a serial match is pre-chosen and never added");
        check(rides.get(1).source.equals(B) && rides.get(1).suggested == null && !rides.get(1).addable, "an unknown scooter needs a choice when Scooters is not selected");
        List<BackupArchive.Destination> withScooters = BackupArchive.destinations(phone, prepared, set(BackupRegistry.SCOOTERS, BackupRegistry.RIDES));
        check(withScooters.get(1).addable && B.equals(withScooters.get(1).suggested), "Scooters adds an unmatched scooter by default");
        check(BackupArchive.destinations(phone, prepared, set(BackupRegistry.PREFERENCES)).isEmpty(), "app preferences need no scooter");
        rejects(() -> BackupArchive.plan(phone, prepared, set(BackupRegistry.RIDES), false, Collections.singletonMap(A, C)), "review refused while a scooter is unresolved");
        Map<String, String> chosen = new LinkedHashMap<>(); chosen.put(A, C); chosen.put(B, B);
        rejects(() -> BackupArchive.plan(phone, prepared, set(BackupRegistry.RIDES), false, chosen), "a scooter cannot be added without Scooters");
        chosen.put(B, "not-a-scooter");
        rejects(() -> BackupArchive.plan(phone, prepared, set(BackupRegistry.RIDES), false, chosen), "an unknown destination is refused");
        chosen.put(B, BackupArchive.SKIP);
        BackupArchive.Plan skipped = BackupArchive.plan(phone, prepared, set(BackupRegistry.RIDES), false, chosen);
        check(skipped.lines.equals(Arrays.asList("“Fold UT5” → Phone UT5", "Rides: 2 new · 0 already here · 0 kept here", "1 ride skipped · scooter not restored")), "skipped data is listed, never silently dropped: " + skipped.lines);
        BackupTestStore same = new BackupTestStore(new File(root, "same-ids")); Files.copy(new File(root, "source/shared_prefs.bin").toPath(), same.prefsFile().toPath(), StandardCopyOption.REPLACE_EXISTING);
        BackupArchive.Prepared mine = same.stage(complete);
        check(BackupArchive.destinations(same, mine, BackupTestStore.all()).isEmpty(), "scooters already here by ID need no choice");
        BackupArchive.discard(same, mine); BackupArchive.discard(phone, prepared);
    }

    private static void merges(File root, BackupTestStore source, byte[] complete) throws Exception {
        BackupTestStore phone = BackupArchiveTest.phone(new File(root, "merge"));
        phone.put("app_settings", "stats_chart_window", 7); phone.put("app_settings", "dashboard_restore_undo_v1_" + C, "{}");
        phone.put("app_settings", "dashboard_v1_d0000000-0000-4000-8000-00000000000d", "other scooter's dashboard");
        BackupArchive.Prepared prepared = phone.stage(complete);
        Map<String, String> chosen = new LinkedHashMap<>(); chosen.put(A, C); chosen.put(B, B);
        BackupArchive.Plan plan = BackupArchive.plan(phone, prepared, BackupTestStore.all(), false, chosen);
        check(plan.lines.contains("Scooters: adds 1 · 1 already here") && plan.lines.contains("Dashboards: replaces layouts for Phone UT5, Spare") && plan.lines.contains("Rides: 3 new · 0 already here · 0 kept here")
                && plan.lines.contains("Charges: 2 new · 0 already here · 0 kept here") && plan.lines.contains("Readings history: adds to 2 scooters") && plan.lines.contains("Online map: on")
                && plan.lines.contains("App preferences: replaces 11 settings"), "preview lines per category: " + plan.lines);
        Map<String, Map<String, Object>> before = phone.preferences();
        BackupTransaction.apply(phone, plan);
        Map<String, Map<String, Object>> after = phone.preferences(); Map<String, Object> app = after.get("app_settings");
        check("light".equals(app.get("theme")) && !app.containsKey("stats_chart_window") && !app.containsKey("keep_screen_awake") && Boolean.TRUE.equals(app.get("ride_auto_stop")), "App preferences: the backup wins and keys it lacks go back to default");
        check(app.get("last_page").equals("home") && app.get("more_away_boot").equals(9), "device keys stay");
        check(app.get("dashboard_v1_" + C).equals("[{\"id\":\"w-a\"}]") && !app.containsKey("dashboard_slot_v1_" + C + "_1") && app.get("dashboard_slot_v1_" + C + "_2").equals("slot two a") && !app.containsKey("dashboard_restore_undo_v1_" + C)
                && app.get("dashboard_v1_" + B).equals("[{\"id\":\"w-b\"}]") && Integer.valueOf(4).equals(app.get("dashboard_columns")), "Dashboards replace each mapped scooter's dashboard and slots and drop its undo");
        check("other scooter's dashboard".equals(app.get("dashboard_v1_d0000000-0000-4000-8000-00000000000d")), "an unmapped scooter's dashboard stays");
        check("never".equals(app.get("connect_report_" + C)) && !app.containsKey("connect_report_" + A), "A connection-details opt-out follows its scooter onto the mapped scooter");
        check("learned".equals(app.get("range_source_" + C)) && "HIGH_SPEED".equals(app.get("charge_charger_" + C)) && Boolean.TRUE.equals(app.get("range_follow_mode_" + C))
                && after.get("control_evidence").get(C).equals("{\"cached\":\"a\"}"), "Scooter preferences replace each mapped scooter's app-side choices, never firmware");
        List<BackupProfiles.Profile> profiles = BackupArchive.profiles(after);
        check(profiles.size() == 2 && profiles.get(0).raw.equals(BackupArchive.profiles(before).get(0).raw) && profiles.get(1).id.equals(B) && profiles.get(1).raw.contains("fw_controller"), "the local profile and credentials are kept; the new one is spliced exactly");
        check(C.equals(after.get("profiles").get("active")) && Boolean.TRUE.equals(after.get("profiles").get("legacy_migrated")) && after.get("onboarding").equals(Collections.singletonMap("onboarding_done", true)) && after.get("scooter").isEmpty(), "active scooter kept; legacy setup is never merged");
        check(before.get("app_settings").get("ride_comparison").equals(app.get("ride_comparison")), "ride comparison stays local in a merge");
        List<String> rows = BackupTestStore.rows(phone.database());
        check(rows.contains(C + ",speed,1,50") && !rows.contains(C + ",speed,1,10") && rows.contains(C + ",speed,2,11") && rows.contains(B + ",battery,1,80") && rows.size() == 4, "history unions remapped rows and this phone's row wins: " + rows);
        RideStore rides = new RideStore(new File(phone.files(), "rides"));
        int forC = 0; for (RideStore.Summary ride : rides.list()) if (ride.scooterId.equals(C)) forC++;
        check(rides.list().size() == 4 && forC == 3, "mapped rides belong to this phone's scooter");
        BackupArchive.Prepared again = phone.stage(complete);
        BackupArchive.Plan repeat = phone.plan(again, BackupTestStore.all(), false, null);
        check(repeat.additions.isEmpty() && repeat.preferences.isEmpty() && repeat.lines.contains("Rides: 0 new · 3 already here · 0 kept here") && repeat.lines.contains("Charges: 0 new · 2 already here · 0 kept here") && repeat.lines.contains("Scooters: adds 0 · 2 already here"), "a repeated restore is a no-op: " + repeat.lines);
        BackupTransaction.apply(phone, repeat);
        check(BackupTestStore.rows(phone.database()).size() == 4, "history is not duplicated by a second merge");
        BackupTestStore empty = new BackupTestStore(new File(root, "merge-into-empty"));
        BackupArchive.Prepared fresh = empty.stage(complete);
        BackupTransaction.apply(empty, empty.plan(fresh, BackupTestStore.all(), false, null));
        check(A.equals(empty.preferences().get("profiles").get("active")) && BackupArchive.profiles(empty.preferences()).size() == 2 && empty.preferences().get("onboarding").equals(Collections.singletonMap("onboarding_done", true)), "merging into an empty phone selects the backup's scooter and finishes setup");
    }

    private static void duplicates(File root, BackupTestStore source) throws Exception {
        BackupTestStore phone = BackupArchiveTest.phone(new File(root, "duplicates"));
        String[] ids = new File(source.files(), "rides").list(); Arrays.sort(ids);
        byte[] recordings = source.export(set(BackupRegistry.RIDES, BackupRegistry.CHARGES));
        phone.restore(recordings, set(BackupRegistry.RIDES, BackupRegistry.CHARGES), false, C);
        RideStore rides = new RideStore(new File(phone.files(), "rides"));
        String renamed = ids[0].substring(0, 36); rides.rename(renamed, "Kept local name");
        // The same recording under another UUID (same scooter and START clocks) also counts as here.
        File restoredRide = new File(phone.files(), "rides/" + ids[1]);
        BackupTestStore clocks = new BackupTestStore(new File(root, "clock-source"));
        Files.createDirectories(new File(clocks.files(), "rides").toPath()); Files.copy(restoredRide.toPath(), new File(clocks.files(), "rides/" + UUID.randomUUID() + ".csv").toPath());
        // .active and .csv with one UUID are the same charge.
        String chargeId = null; for (File f : new File(phone.files(), "charge-recordings").listFiles()) if (f.getName().endsWith(".active")) chargeId = f.getName().substring(0, 36);
        check(chargeId != null, "the unfinished charge was restored as unfinished");
        BackupTestStore.file(new File(clocks.files(), "charge-recordings/" + chargeId + ".csv"), "charge-v2," + C + ",STOCK,1760400000000," + (1760400000000L / 7) + "\nend,INTERRUPTED\n");
        BackupArchive.Plan plan = phone.plan(phone.stage(clocks.export(set(BackupRegistry.RIDES, BackupRegistry.CHARGES))), set(BackupRegistry.RIDES, BackupRegistry.CHARGES), false, C);
        check(plan.additions.isEmpty() && plan.lines.contains("Rides: 0 new · 0 already here · 1 kept here") && plan.lines.contains("Charges: 0 new · 0 already here · 1 kept here"), "same START clocks under another UUID, and .csv versus .active, never duplicate: " + plan.lines);
        BackupArchive.Plan conflict = phone.plan(phone.stage(recordings), set(BackupRegistry.RIDES, BackupRegistry.CHARGES), false, C);
        check(conflict.additions.isEmpty() && conflict.lines.contains("Rides: 0 new · 2 already here · 1 kept here"), "a renamed local ride is kept, the rest are already here: " + conflict.lines);
        BackupTransaction.apply(phone, conflict);
        check(new RideStore(new File(phone.files(), "rides")).get(renamed).label.equals("Kept local name"), "a conflicting ride is never overwritten");
        BackupTestStore twice = new BackupTestStore(new File(root, "twice-source"));
        Files.createDirectories(new File(twice.files(), "rides").toPath());
        File original = new File(source.files(), "rides/" + ids[1]);
        Files.copy(original.toPath(), new File(twice.files(), "rides/" + UUID.randomUUID() + ".csv").toPath()); Files.copy(original.toPath(), new File(twice.files(), "rides/" + UUID.randomUUID() + ".csv").toPath());
        BackupTestStore target = BackupArchiveTest.phone(new File(root, "twice-target"));
        BackupArchive.Plan once = target.plan(target.stage(twice.export(set(BackupRegistry.RIDES))), set(BackupRegistry.RIDES), false, C);
        check(once.lines.equals(Arrays.asList("“Saved scooter” → Phone UT5", "Rides: 1 new · 0 already here · 1 kept here")), "one recording twice in a backup is added once: " + once.lines);
    }

    private static void sharedDestination(File root, byte[] complete) throws Exception {
        BackupTestStore phone = BackupArchiveTest.phone(new File(root, "shared"));
        Map<String, Map<String, Object>> before = phone.preferences();
        BackupArchive.Prepared prepared = phone.stage(complete);
        Map<String, String> chosen = new LinkedHashMap<>(); chosen.put(A, C); chosen.put(B, C);
        Set<String> categories = set(BackupRegistry.DASHBOARDS, BackupRegistry.SCOOTER_PREFERENCES, BackupRegistry.RIDES, BackupRegistry.HISTORY);
        BackupArchive.Plan plan = BackupArchive.plan(phone, prepared, categories, false, chosen);
        check(plan.lines.contains("Dashboards kept for Phone UT5 · two scooters chose it") && plan.lines.contains("Scooter preferences kept for Phone UT5 · two scooters chose it")
                && plan.lines.contains("Rides: 3 new · 0 already here · 0 kept here") && plan.lines.contains("Readings history: adds to 1 scooter"), "two sources for one scooter merge recordings but keep its settings: " + plan.lines);
        BackupTransaction.apply(phone, plan);
        Map<String, Object> app = phone.preferences().get("app_settings");
        check(app.get("dashboard_v1_" + C).equals(before.get("app_settings").get("dashboard_v1_" + C)) && app.get("range_source_" + C).equals("scooter"), "a shared destination's dashboard and preferences stay");
        check(BackupTestStore.rows(phone.database()).contains(C + ",battery,1,80"), "both sources' history joins the shared scooter");
    }

    private static void freshInstall(File root, BackupTestStore source) throws Exception {
        BackupTestStore fresh = new BackupTestStore(new File(root, "fresh"));
        Set<String> chosen = set(BackupRegistry.RIDES, BackupRegistry.DASHBOARDS);
        BackupArchive.Prepared prepared = fresh.stage(source.export(chosen));
        List<BackupArchive.Destination> needed = BackupArchive.destinations(fresh, prepared, chosen);
        check(needed.size() == 2 && needed.get(0).suggested == null && !needed.get(0).addable && needed.get(1).suggested == null, "without Scooters a new phone can only skip scooter data");
        BackupArchive.Plan plan = fresh.plan(prepared, chosen, false, BackupArchive.SKIP);
        check(plan.additions.isEmpty() && plan.preferences.isEmpty() && plan.lines.contains("3 rides skipped · scooter not restored") && plan.lines.contains("Dashboards for 2 scooters skipped · not restored"), "everything skipped is listed: " + plan.lines);
        BackupArchive.discard(fresh, prepared);
        check(!BackupTransaction.needsRecovery(fresh), "a cancelled review leaves no staging");
        BackupArchive.Prepared onlyPrefs = fresh.stage(source.export(set(BackupRegistry.PREFERENCES)));
        BackupArchive.Plan prefs = fresh.plan(onlyPrefs, set(BackupRegistry.PREFERENCES), false, null);
        check(onlyPrefs.sources.isEmpty() && prefs.lines.get(0).equals("App preferences: replaces 11 settings") && prefs.lines.contains("Online map: on"), "an App-preferences-only file carries no scooter table and says the online map will be on: " + prefs.lines);
        BackupTransaction.apply(fresh, prefs);
        check(Boolean.TRUE.equals(fresh.preferences().get("app_settings").get("map_basemap_enabled")) && Boolean.TRUE.equals(fresh.preferences().get("app_settings").get("map_basemap_explained")), "the online map choice is restored as recorded");
    }

    private static void chargeLimit(File root) throws Exception {
        BackupTestStore phone = BackupArchiveTest.phone(new File(root, "charge-limit"));
        Set<String> local = new HashSet<>(); List<String> newest = new ArrayList<>();
        for (String name : new File(phone.files(), "charge-recordings").list()) local.add(name.substring(0, 36));
        for (int i = 0; i < 14; i++) local.add(phone.charge(C, 1761000000000L + i * 1000000L, false));
        BackupTestStore source = new BackupTestStore(new File(root, "charge-limit-source"));
        for (int i = 0; i < 15; i++) { String id = source.charge(A, 1760000000000L + i * 1000000L, false); if (i >= 10) newest.add("charge-recordings/" + id + ".csv"); }
        BackupArchive.Plan plan = phone.plan(phone.stage(source.export(set(BackupRegistry.CHARGES))), set(BackupRegistry.CHARGES), false, C);
        check(plan.lines.contains("Charges: 5 new · 0 already here · 0 kept here") && plan.lines.contains("10 charges skipped · 20-charge limit"), "a merge never passes the charge limit and says what it skipped: " + plan.lines);
        check(new HashSet<>(plan.additions).equals(new HashSet<>(newest)), "the newest backup charges fill the free places: " + plan.additions);
        BackupTransaction.apply(phone, plan);
        File folder = new File(phone.files(), "charge-recordings"); new ChargeSessionStore(folder);
        Set<String> after = new HashSet<>(); for (String name : folder.list()) after.add(name.substring(0, 36));
        check(local.size() == 15 && after.size() == ChargeSessionStore.MAX_SESSIONS && after.containsAll(local), "the next start prunes nothing, so this phone's charges all stay");
        BackupArchive.Plan full = phone.plan(phone.stage(source.export(set(BackupRegistry.CHARGES))), set(BackupRegistry.CHARGES), false, C);
        check(full.additions.isEmpty() && full.lines.contains("Charges: 0 new · 5 already here · 0 kept here") && full.lines.contains("10 charges skipped · 20-charge limit"), "a full store adds nothing: " + full.lines);
    }

    private static void noScooterLayout(File root, BackupTestStore source) throws Exception {
        BackupTestStore skipped = new BackupTestStore(new File(root, "no-scooter-source"));
        Files.copy(source.prefsFile().toPath(), skipped.prefsFile().toPath(), StandardCopyOption.REPLACE_EXISTING);
        skipped.put("app_settings", "dashboard_v1_unconfigured", "[{\"id\":\"example\"}]"); skipped.put("app_settings", "dashboard_slot_v1_unconfigured_2", "seeded");
        check(BackupArchive.inventory(skipped).get(BackupRegistry.DASHBOARDS)[0] == 2, "Home's no-scooter layout is not counted as a scooter");
        byte[] complete = skipped.export(BackupTestStore.all());
        BackupTestStore phone = BackupArchiveTest.phone(new File(root, "no-scooter-merge"));
        BackupArchive.Prepared prepared = phone.stage(complete);
        check(prepared.categories.get(BackupRegistry.DASHBOARDS) == 2, "the restore list counts real scooters only");
        List<BackupArchive.Destination> needed = BackupArchive.destinations(phone, prepared, set(BackupRegistry.DASHBOARDS));
        check(needed.size() == 2 && !needed.get(0).source.equals(BackupArchive.NO_SCOOTER) && !needed.get(1).source.equals(BackupArchive.NO_SCOOTER), "no phantom scooter needs a destination");
        BackupArchive.Plan plan = phone.plan(prepared, set(BackupRegistry.DASHBOARDS), false, BackupArchive.SKIP);
        check(!plan.lines.toString().contains("Saved scooter") && !phone.preferences().get("app_settings").containsKey("dashboard_v1_unconfigured"), "a merge never maps the no-scooter layout: " + plan.lines);
        BackupArchive.discard(phone, prepared);
        BackupTestStore cleared = new BackupTestStore(new File(root, "no-scooter-replace"));
        cleared.restore(complete, BackupTestStore.all(), true, null);
        check("seeded".equals(cleared.preferences().get("app_settings").get("dashboard_slot_v1_unconfigured_2")), "Replace everything keeps the no-scooter layout exactly");
    }
}
