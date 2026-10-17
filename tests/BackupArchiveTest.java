package local.navee.offlinelock;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.crypto.SecretKey;

/** Category backups: complete and partial roundtrips, every category subset, legacy files and hostile input. */
public final class BackupArchiveTest {
    private static int checks;
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private interface Action { void run() throws Exception; }
    private static void rejects(Action action, String message) throws Exception { checks++; try { action.run(); throw new AssertionError(message); } catch (IOException expected) { } }
    static final String C = "c0000000-0000-4000-8000-00000000000c";
    private static final File FIXTURES = new File("tests/fixtures/legacy");
    private static final String FIXTURE_PROFILE = "8c1f3a52-0d7e-4b8e-9a41-5b2f6c7d8e90";

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("volt-unified-backup-").toFile();
        try {
            BackupTestStore source = BackupTestStore.rich(new File(root, "source"));
            Map<String, Map<String, Object>> sourcePrefs = source.preferences(); Map<String, String> sourceFiles = source.fileHashes();
            byte[] complete = source.export(BackupTestStore.all());
            check(!Arrays.equals(complete, source.export(BackupTestStore.all())), "fresh salt and nonces for every export");
            check(!new String(complete, StandardCharsets.ISO_8859_1).contains("ABCDEF012345") && !new String(complete, StandardCharsets.ISO_8859_1).contains("Fold UT5"), "credentials and names are never plaintext");
            check(source.preferences().equals(sourcePrefs) && source.fileHashes().equals(sourceFiles), "export changes nothing");
            completeRoundtrip(root, source, complete, sourcePrefs, sourceFiles);
            singleCategories(root, source);
            subsets(root, complete);
            legacy(root);
            hostile(root, source, complete);
            System.out.println("BackupArchiveTest: " + checks + " checks passed (complete reset roundtrip, 7 single and 127 subset restores, legacy v1/.voltrides/layout, hostile archives)");
        } finally { AppDataArchive.deleteTree(root); }
    }

    /** Durable state without keys a backup never carries. */
    static Map<String, Map<String, Object>> durable(Map<String, Map<String, Object>> prefs) {
        Map<String, Map<String, Object>> result = BackupArchive.copy(prefs);
        for (String name : BackupRegistry.NAMESPACES) result.get(name).keySet().removeIf(key -> { BackupRegistry.Rule rule = BackupRegistry.rule(name, key); return rule != null && rule.excluded(); });
        return result;
    }
    static Map<String, Map<String, Object>> excluded(Map<String, Map<String, Object>> prefs) {
        Map<String, Map<String, Object>> result = BackupArchive.copy(prefs);
        for (String name : BackupRegistry.NAMESPACES) result.get(name).keySet().removeIf(key -> { BackupRegistry.Rule rule = BackupRegistry.rule(name, key); return rule == null || !rule.excluded(); });
        return result;
    }

    private static void completeRoundtrip(File root, BackupTestStore source, byte[] complete, Map<String, Map<String, Object>> sourcePrefs, Map<String, String> sourceFiles) throws Exception {
        BackupTestStore fresh = new BackupTestStore(new File(root, "cleared"));
        BackupArchive.Prepared prepared = fresh.stage(complete);
        check(prepared.kind.equals("v2") && prepared.complete && prepared.encrypted() && prepared.categories.keySet().equals(BackupTestStore.all()), "complete backup declares all seven categories");
        check(prepared.categories.get(BackupRegistry.SCOOTERS) == 2 && prepared.categories.get(BackupRegistry.RIDES) == 3 && prepared.categories.get(BackupRegistry.CHARGES) == 2
                && prepared.categories.get(BackupRegistry.DASHBOARDS) == 2 && prepared.categories.get(BackupRegistry.HISTORY) == 2 && prepared.categories.get(BackupRegistry.SCOOTER_PREFERENCES) == 2, "preview counts per category");
        check(prepared.sources.get(BackupTestStore.A).name.equals("Fold UT5") && prepared.sources.get(BackupTestStore.A).serial.equals("SERIAL-A"), "profile table carries names and serials for mapping");
        check(fresh.preferences().equals(BackupTestStore.empty()) && fresh.fileHashes().isEmpty(), "staging cannot change live data");
        BackupArchive.Plan plan = fresh.plan(prepared, BackupTestStore.all(), true, null);
        check(plan.lines.get(0).equals("Replaces everything on this phone") && plan.lines.contains("Online map: on"), "replace preview names the mode and the online map");
        BackupTransaction.apply(fresh, plan);
        check(durable(fresh.preferences()).equals(durable(sourcePrefs)), "complete reset roundtrip restores every durable value, unassigned keys included");
        check("only exact restores keep me".equals(fresh.preferences().get("app_settings").get("legacy_removed_key")), "unassigned keys travel only in complete backups");
        check(excluded(fresh.preferences()).equals(BackupTestStore.empty()), "device, pending and wizard keys are never restored");
        check(fresh.fileHashes().equals(sourceFiles), "every recording and the history database are byte-identical");
        check(!BackupTransaction.needsRecovery(fresh), "a finished restore leaves no private data");
        RideStore rides = new RideStore(new File(fresh.files(), "rides"));
        check(rides.list().size() == 3, "restored rides reopen in the real reader");
        // Replace everything over a phone with data keeps that phone's excluded keys.
        BackupTestStore phone = phone(new File(root, "replace-over"));
        phone.put("app_settings", "dashboard_restore_undo_v1_" + C, "{}"); phone.put("onboarding", "step", 4);
        Map<String, Map<String, Object>> phoneExcluded = excluded(phone.preferences());
        phone.restore(complete, BackupTestStore.all(), true, null);
        Map<String, Map<String, Object>> after = phone.preferences();
        check(durable(after).equals(durable(sourcePrefs)) && phone.fileHashes().equals(sourceFiles), "Replace everything replaces every category");
        phoneExcluded.get("app_settings").remove("dashboard_restore_undo_v1_" + C); phoneExcluded.get("onboarding").clear();
        check(excluded(after).equals(phoneExcluded), "this phone's device keys stay; a stale dashboard undo and finished-setup wizard state go");
    }

    /** A phone that already has data: scooter C is the backup's scooter A by serial. */
    static BackupTestStore phone(File root) throws IOException {
        BackupTestStore s = new BackupTestStore(root);
        Map<String, Map<String, Object>> p = BackupTestStore.empty(); Map<String, Object> app = p.get("app_settings");
        app.put("theme", "dark"); app.put("units", "mi"); app.put("keep_screen_awake", true); app.put("dashboard_columns", 2);
        app.put("dashboard_v1_" + C, "[{\"id\":\"local\"}]"); app.put("dashboard_slot_v1_" + C + "_1", "local slot"); app.put("range_source_" + C, "scooter"); app.put("charge_charger_" + C, "STOCK");
        app.put("ride_comparison", new LinkedHashSet<>(Collections.singletonList("x"))); app.put("last_page", "home"); app.put("more_away_boot", 9);
        p.get("profiles").put("items", "[" + BackupTestStore.profileItem(C, "Phone UT5", "0123456789AB", "SERIAL-A") + "]"); p.get("profiles").put("active", C); p.get("profiles").put("legacy_migrated", true);
        p.get("control_evidence").put(C, "{\"local\":1}"); p.get("onboarding").put("onboarding_done", true);
        s.write(p);
        s.ride(C, 1750000000000L, true, "Local ride"); s.charge(C, 1750100000000L, false);
        BackupTestStore.history(s.database(), C + ",speed,1,50", C + ",speed,9,51");
        return s;
    }
    /** Category projection of live data, for checking that unselected data is untouched. */
    static Map<String, Object> projection(BackupTestStore store, String category) throws IOException {
        Map<String, Object> result = new TreeMap<>();
        result.put("prefs", BackupArchive.subset(store.preferences(), category));
        for (Map.Entry<String, String> file : store.fileHashes().entrySet()) {
            String path = file.getKey();
            String owner = path.equals("database") ? BackupRegistry.HISTORY : path.startsWith("charge-recordings/") ? BackupRegistry.CHARGES : BackupRegistry.RIDES;
            if (owner.equals(category)) result.put(path, file.getValue());
        }
        return result;
    }
    private static Map<String, Map<String, Object>> projections(BackupTestStore store) throws IOException {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>(); for (String category : BackupRegistry.CATEGORIES) result.put(category, projection(store, category)); return result;
    }

    private static void singleCategories(File root, BackupTestStore source) throws Exception {
        for (String category : BackupRegistry.CATEGORIES) {
            byte[] one = source.export(Collections.singleton(category));
            BackupTestStore phone = phone(new File(root, "single-" + category));
            Map<String, Map<String, Object>> before = projections(phone), excludedBefore = excluded(phone.preferences());
            BackupArchive.Prepared prepared = phone.stage(one);
            check(prepared.categories.keySet().equals(Collections.singleton(category)) && !prepared.complete, "single category file declares only " + category);
            rejects(() -> phone.plan(prepared, BackupTestStore.all(), false, BackupArchive.SKIP), "categories absent from the file cannot be selected: " + category);
            rejects(() -> phone.plan(prepared, Collections.singleton(category), true, null), "a partial file cannot Replace everything: " + category);
            BackupTransaction.apply(phone, phone.plan(prepared, Collections.singleton(category), false, BackupArchive.SKIP));
            Map<String, Map<String, Object>> after = projections(phone);
            for (String other : BackupRegistry.CATEGORIES) if (!other.equals(category)) check(after.get(other).equals(before.get(other)), category + " restore leaves " + other + " identical");
            Map<String, Map<String, Object>> excludedAfter = excluded(phone.preferences());
            if (category.equals(BackupRegistry.DASHBOARDS)) excludedBefore.get("app_settings").remove("dashboard_restore_undo_v1_" + C);
            check(excludedAfter.equals(excludedBefore), category + " restore leaves device and pending keys identical");
            check(!after.get(category).equals(before.get(category)), category + " restore changed its own category");
        }
    }

    private static void subsets(File root, byte[] complete) throws Exception {
        BackupTestStore template = phone(new File(root, "subset-template"));
        BackupTestStore masterStore = new BackupTestStore(new File(root, "subset-master"));
        BackupArchive.Prepared master = masterStore.stage(complete);
        for (int mask = 1; mask < 128; mask++) {
            Set<String> selected = new LinkedHashSet<>();
            for (int i = 0; i < 7; i++) if ((mask & (1 << i)) != 0) selected.add(BackupRegistry.CATEGORIES[i]);
            File copy = new File(root, "subset-" + mask); copyTree(template.root, copy);
            BackupTestStore phone = new BackupTestStore(copy); phone.fast = true;
            Map<String, Map<String, Object>> before = projections(phone), excludedBefore = excluded(phone.preferences());
            BackupTransaction.apply(phone, phone.plan(phone.copyStage(master), selected, false, BackupArchive.SKIP));
            Map<String, Map<String, Object>> after = projections(phone);
            for (String category : BackupRegistry.CATEGORIES) if (!selected.contains(category)) check(after.get(category).equals(before.get(category)), "subset " + selected + " leaves " + category + " identical");
            Map<String, Map<String, Object>> excludedAfter = excluded(phone.preferences());
            if (selected.contains(BackupRegistry.DASHBOARDS)) excludedBefore.get("app_settings").remove("dashboard_restore_undo_v1_" + C);
            check(excludedAfter.equals(excludedBefore), "subset " + selected + " leaves device keys identical");
            // Backup scooter A is this phone's C by serial: its rides join C, never as a duplicate scooter.
            List<BackupProfiles.Profile> profiles = BackupArchive.profiles(phone.preferences());
            check(profiles.size() == (selected.contains(BackupRegistry.SCOOTERS) ? 2 : 1) && profiles.get(0).id.equals(C), "subset " + selected + " adds only the unmatched scooter");
            if (selected.contains(BackupRegistry.RIDES)) check(new RideStore(new File(phone.files(), "rides")).list().size() == (selected.contains(BackupRegistry.SCOOTERS) ? 4 : 3), "subset " + selected + " adds mapped rides");
            // Idempotence is sampled here (every single category, pairs with Scooters, all) and covered in BackupPlanTest.
            if (Integer.bitCount(mask) == 1 || mask % 9 == 0 || mask == 127) {
                BackupTransaction.apply(phone, phone.plan(phone.copyStage(master), selected, false, BackupArchive.SKIP));
                check(projections(phone).equals(after), "repeating subset " + selected + " changes nothing");
            }
            AppDataArchive.deleteTree(copy);
        }
        BackupArchive.discard(masterStore, master);
    }
    static void copyTree(File from, File to) throws IOException {
        if (from.isDirectory()) { to.mkdirs(); for (File child : from.listFiles()) copyTree(child, new File(to, child.getName())); }
        else { Files.copy(from.toPath(), to.toPath(), StandardCopyOption.COPY_ATTRIBUTES); }
    }

    private static void legacy(File root) throws Exception {
        char[] password = "synthetic fixture password".toCharArray();
        File full = new File(FIXTURES, "full-v1.voltbackup"), rides = new File(FIXTURES, "rides-v1.voltrides"), layout = new File(FIXTURES, "dashboards-v1.json");
        check(full.isFile() && rides.isFile() && layout.isFile(), "legacy fixtures are present");
        byte[] head = Arrays.copyOf(Files.readAllBytes(full.toPath()), 64);
        check(BackupArchive.sniff(head, 64) == BackupArchive.ENCRYPTED && BackupArchive.sniff(Files.readAllBytes(rides.toPath()), 8) == BackupArchive.RIDE_FILE
                && BackupArchive.sniff(Files.readAllBytes(layout.toPath()), 8) == BackupArchive.LAYOUT_FILE && BackupArchive.sniff("hello".getBytes("UTF-8"), 5) == BackupArchive.UNSUPPORTED, "files are recognised by content");
        BackupTestStore cleared = new BackupTestStore(new File(root, "legacy-cleared")); cleared.sqliteProfiles.add(FIXTURE_PROFILE);
        rejects(() -> cleared.stage(full, "wrong password!".toCharArray()), "legacy wrong password refused");
        rejects(() -> cleared.stage(full, null), "an encrypted file needs a password");
        BackupArchive.Prepared v1 = cleared.stage(full, password);
        check(v1.kind.equals("v1") && v1.complete && v1.categories.keySet().equals(BackupTestStore.all()) && v1.rides.size() == 1 && v1.rides.get(0).readable && v1.charges.size() == 1, "a 1.3.1 full backup is a complete backup");
        BackupTransaction.apply(cleared, cleared.plan(v1, BackupTestStore.all(), true, null));
        Map<String, Map<String, Object>> prefs = cleared.preferences(); Map<String, Object> app = prefs.get("app_settings");
        check("light".equals(app.get("theme")) && Float.valueOf(1.25f).equals(app.get("text_scale")) && Integer.valueOf(4).equals(app.get("dashboard_columns")) && app.containsKey("dashboard_v1_" + FIXTURE_PROFILE)
                && "learned".equals(app.get("range_source_" + FIXTURE_PROFILE)) && "kept only by an exact restore".equals(app.get("legacy_removed_key")), "legacy Replace everything matches the 1.3.1 result for durable keys");
        check(!app.containsKey("last_page") && !app.containsKey("more_away_boot") && !app.containsKey("owner_import_applied") && !app.containsKey("notification_prompted") && !app.containsKey("dashboard_restore_undo_v1_" + FIXTURE_PROFILE)
                && prefs.get("onboarding").equals(Collections.singletonMap("onboarding_done", true)), "legacy excluded keys are not applied");
        check(prefs.get("scooter").get("identity").equals("ABCDEF012345") && prefs.get("control_evidence").containsKey(FIXTURE_PROFILE), "legacy setup and dormant evidence restore exactly");
        RideStore restored = new RideStore(new File(cleared.files(), "rides"));
        check(restored.list().size() == 1 && restored.list().get(0).scooterId.equals(FIXTURE_PROFILE) && new File(cleared.files(), "phone-rides").list().length == 2 && cleared.database().length() > 1000, "legacy rides, sidecars, charges and SQLite history restore");
        // A selective restore from the same v1 file onto a phone with another scooter.
        BackupTestStore phone = phone(new File(root, "legacy-selective")); phone.sqliteProfiles.add(FIXTURE_PROFILE);
        BackupArchive.Prepared selective = phone.stage(full, password);
        List<BackupArchive.Destination> needed = BackupArchive.destinations(phone, selective, Collections.singleton(BackupRegistry.RIDES));
        check(needed.size() == 1 && needed.get(0).source.equals(FIXTURE_PROFILE) && needed.get(0).suggested == null && !needed.get(0).addable && needed.get(0).name.equals("Synthetic UT5"), "an unknown scooter needs a choice");
        BackupArchive.Plan ridesOnly = BackupArchive.plan(phone, selective, Collections.singleton(BackupRegistry.RIDES), false, Collections.singletonMap(FIXTURE_PROFILE, C));
        check(ridesOnly.lines.get(0).equals("Rides: 1 new · 0 already here · 0 kept here") && ridesOnly.preferences.isEmpty() && !ridesOnly.history, "selective v1 restore previews only rides");
        Map<String, Map<String, Object>> phonePrefs = phone.preferences();
        BackupTransaction.apply(phone, ridesOnly);
        check(phone.preferences().equals(phonePrefs) && new RideStore(new File(phone.files(), "rides")).list().size() == 2, "selective v1 restore adds the ride only");
        // The same ride from a .voltrides file is already here after mapping.
        BackupArchive.Prepared voltrides = phone.stage(rides, null);
        check(voltrides.kind.equals("rides") && !voltrides.encrypted() && voltrides.categories.keySet().equals(Collections.singleton(BackupRegistry.RIDES)) && voltrides.sources.get(FIXTURE_PROFILE).name.equals("Synthetic UT5"), ".voltrides imports as Rides only");
        BackupArchive.Plan again = BackupArchive.plan(phone, voltrides, Collections.singleton(BackupRegistry.RIDES), false, Collections.singletonMap(FIXTURE_PROFILE, C));
        check(again.additions.isEmpty() && again.lines.get(0).equals("Rides: 0 new · 1 already here · 0 kept here"), "a mapped ride is recognised across formats, so learned range never trains on a duplicate");
        BackupTransaction.apply(phone, again);
        BackupTestStore fresh = new BackupTestStore(new File(root, "legacy-rides"));
        BackupArchive.Prepared alone = fresh.stage(rides, null);
        check(BackupArchive.destinations(fresh, alone, Collections.singleton(BackupRegistry.RIDES)).get(0).suggested == null, "a phone without scooters can only skip ride data");
        rejects(() -> BackupArchive.plan(fresh, alone, Collections.singleton(BackupRegistry.RIDES), false, Collections.singletonMap(FIXTURE_PROFILE, C)), "rides cannot attach to a scooter that is not here");
        BackupArchive.discard(fresh, alone);
        // A dashboard layout file goes to the selected scooter, replacing its dashboard and slots.
        phone.put("app_settings", "dashboard_restore_undo_v1_" + C, "{}");
        BackupArchive.Prepared layoutFile = phone.stage(layout, null);
        check(layoutFile.kind.equals("layout") && layoutFile.categories.keySet().equals(Collections.singleton(BackupRegistry.DASHBOARDS)), "a layout file imports as Dashboards only");
        List<BackupArchive.Destination> target = BackupArchive.destinations(phone, layoutFile, Collections.singleton(BackupRegistry.DASHBOARDS));
        check(target.size() == 1 && target.get(0).source.equals(BackupArchive.LAYOUT_SOURCE) && C.equals(target.get(0).suggested) && target.get(0).name.equals("Layout file"), "a layout file defaults to the selected scooter");
        BackupArchive.Plan layoutPlan = phone.plan(layoutFile, Collections.singleton(BackupRegistry.DASHBOARDS), false, null);
        check(layoutPlan.lines.get(0).equals("Dashboards: replaces Phone UT5"), "layout preview names the scooter");
        BackupTransaction.apply(phone, layoutPlan);
        app = phone.preferences().get("app_settings");
        check(((String) app.get("dashboard_v1_" + C)).contains("\"Synthetic\"") && ((String) app.get("dashboard_slot_v1_" + C + "_0")).contains("Shared slot") && !app.containsKey("dashboard_slot_v1_" + C + "_1")
                && Boolean.TRUE.equals(app.get("dashboard_empty_example_v1_" + C)) && Integer.valueOf(3).equals(app.get("dashboard_columns")) && !app.containsKey("dashboard_restore_undo_v1_" + C), "layout import replaces the dashboard, all three slots and columns, and drops a stale undo");
        BackupTestStore noScooter = new BackupTestStore(new File(root, "layout-no-scooter"));
        BackupArchive.Prepared orphanLayout = noScooter.stage(layout, null);
        check(BackupArchive.destinations(noScooter, orphanLayout, Collections.singleton(BackupRegistry.DASHBOARDS)).get(0).suggested == null, "a layout needs a scooter first");
        BackupArchive.discard(noScooter, orphanLayout);
        File broken = new File(root, "broken.json"); Files.write(broken.toPath(), "{\"format\":\"volt-dashboard-layouts\",\"version\":2,\"slots\":[]}".getBytes("UTF-8"));
        rejects(() -> phone.stage(broken, null), "an invalid layout file is refused");
        check(!BackupTransaction.needsRecovery(phone), "legacy imports leave no staging");
    }

    private static void hostile(File root, BackupTestStore source, byte[] complete) throws Exception {
        BackupTestStore target = phone(new File(root, "hostile"));
        Map<String, Map<String, Object>> before = target.preferences(); Map<String, String> files = target.fileHashes();
        rejects(() -> BackupArchive.stage(target, new ByteArrayInputStream(complete), "wrong password".toCharArray()), "wrong password refused");
        for (int position : new int[]{9, 40, 80, complete.length / 2, complete.length - 1}) { byte[] bad = complete.clone(); bad[position] ^= 1; rejects(() -> target.stage(bad), "ciphertext corruption refused at " + position); }
        for (int cut : new int[]{0, 8, 33, complete.length / 3, complete.length - 1}) { byte[] bad = Arrays.copyOf(complete, cut); rejects(() -> target.stage(bad), "truncation refused at " + cut); }
        rejects(() -> target.stage(Arrays.copyOf(complete, complete.length + 1)), "trailing bytes refused");
        byte[] manifest = manifest(36, 1, false, new String[]{"rides"}, new int[]{1}, new long[]{5}, 1, 5);
        String ride = "files/rides/" + UUID.randomUUID() + ".csv";
        rejects(() -> target.stage(encrypt(entries("manifest.bin", manifest, "files/rides/../../escape.csv", "12345".getBytes()))), "traversal refused");
        check(!new File(target.root, "escape.csv").exists() && !new File(target.work().getParentFile(), "escape.csv").exists(), "nothing escapes staging");
        rejects(() -> target.stage(encrypt(entries("manifest.bin", manifest, "notes.txt", "12345".getBytes()))), "unknown entry refused");
        rejects(() -> target.stage(encrypt(entries("manifest.bin", manifest, "files/rides/" + UUID.randomUUID() + "/x.csv", "12345".getBytes()))), "nested entry refused");
        rejects(() -> target.stage(encrypt(entries("manifest.bin", manifest, "files/charge-recordings/" + UUID.randomUUID() + ".csv", "12345".getBytes()))), "out-of-category file refused");
        rejects(() -> target.stage(encrypt(entries("manifest.bin", manifest, ride, "1234".getBytes()))), "byte mismatch refused");
        rejects(() -> target.stage(encrypt(entries("manifest.bin", manifest(36, 1, false, new String[]{"rides"}, new int[]{2}, new long[]{5}, 1, 5), ride, "12345".getBytes()))), "count mismatch refused");
        rejects(() -> target.stage(encrypt(entries("manifest.bin", manifest, ride, "12345".getBytes(), "files/rides/" + UUID.randomUUID() + ".csv", "1".getBytes()))), "extra entry refused");
        rejects(() -> target.stage(encrypt(duplicate(entries("manifest.bin", manifest(36, 1, false, new String[]{"rides"}, new int[]{2}, new long[]{10}, 2, 10), ride, "12345".getBytes(), ride.replace(".csv", ".csw"), "12345".getBytes()), ride))), "duplicate entry refused");
        rejects(() -> target.stage(encrypt(entries("manifest.bin", manifest(37, 1, false, new String[]{"rides"}, new int[]{1}, new long[]{5}, 1, 5), ride, "12345".getBytes()))), "newer app version refused");
        rejects(() -> target.stage(encrypt(entries("manifest.bin", manifest(36, 2, false, new String[]{"rides"}, new int[]{1}, new long[]{5}, 1, 5), ride, "12345".getBytes()))), "newer registry refused");
        rejects(() -> target.stage(encrypt(entries("manifest.bin", manifest(36, 1, true, new String[]{"rides"}, new int[]{1}, new long[]{5}, 1, 5), ride, "12345".getBytes()))), "complete flag without every category refused");
        rejects(() -> target.stage(encrypt(entries("manifest.bin", manifest(36, 1, false, new String[]{"rides", "rides"}, new int[]{1, 1}, new long[]{5, 5}, 1, 5), ride, "12345".getBytes()))), "repeated category refused");
        rejects(() -> target.stage(encrypt(entries("manifest.bin", manifest(36, 1, false, new String[]{"secrets"}, new int[]{1}, new long[]{5}, 1, 5), ride, "12345".getBytes()))), "unknown category refused");
        byte[] prefs = AppDataArchive.encodePreferences(Collections.singletonMap("app_settings", Collections.<String, Object>singletonMap("theme", "dark")), Collections.singleton("app_settings"));
        rejects(() -> target.stage(encrypt(entries("manifest.bin", manifest(36, 1, false, new String[]{"preferences"}, new int[]{0}, new long[]{0}, 1, prefs.length), "prefs/unassigned.bin", prefs))), "unassigned keys refused outside a complete backup");
        byte[] badType = AppDataArchive.encodePreferences(Collections.singletonMap("app_settings", Collections.<String, Object>singletonMap("theme", 7)), Collections.singleton("app_settings"));
        rejects(() -> target.stage(encrypt(entries("manifest.bin", manifest(36, 1, false, new String[]{"preferences"}, new int[]{1}, new long[]{badType.length}, 1, badType.length), "prefs/preferences.bin", badType))), "wrong preference type refused");
        byte[] huge = new byte[AppDataArchive.MAX_PREFERENCES + 1];
        rejects(() -> target.stage(encrypt(entries("manifest.bin", manifest(36, 1, false, new String[]{"preferences"}, new int[]{1}, new long[]{huge.length}, 1, huge.length), "prefs/preferences.bin", huge))), "oversize preferences refused");
        Map<String, Object> privateKeys = new LinkedHashMap<>(); privateKeys.put("items", "[{\"id\":\"x\",\"role\":5}]");
        byte[] badProfiles = AppDataArchive.encodePreferences(Collections.singletonMap("profiles", privateKeys), Collections.singleton("profiles"));
        rejects(() -> target.stage(encrypt(entries("manifest.bin", manifest(36, 1, false, new String[]{"scooters"}, new int[]{1}, new long[]{badProfiles.length}, 1, badProfiles.length), "prefs/scooters.bin", badProfiles))), "invalid scooter profiles refused");
        byte[] badDb = "not a database".getBytes();
        rejects(() -> target.stage(encrypt(entries("manifest.bin", manifest(36, 1, false, new String[]{"history"}, new int[]{1}, new long[]{badDb.length}, 1, badDb.length), "database/telemetry-history.db", badDb))), "invalid history database refused");
        // A plain ZIP with a category manifest is not accepted: new backups are always encrypted.
        rejects(() -> target.stage(entries("manifest.bin", manifest, ride, "12345".getBytes())), "an unencrypted category backup is refused");
        rejects(() -> BackupArchive.stage(target, new ByteArrayInputStream("plain text".getBytes()), null), "unknown files are refused");
        BackupTestStore older = new BackupTestStore(new File(root, "older")); older.version = 35;
        rejects(() -> older.stage(complete), "a file from a newer app version is refused");
        check(target.preferences().equals(before) && target.fileHashes().equals(files), "hostile files never change live data");
        File[] left = target.work().listFiles();
        check(left == null || left.length == 0, "hostile files leave no staging behind");
        rejects(() -> source.export(Collections.<String>emptySet()), "an empty selection is refused");
        rejects(() -> source.export(Collections.singleton("secrets")), "an unknown category is refused");
        rejects(() -> BackupArchive.write(source, new ByteArrayOutputStream(), "short".toCharArray(), BackupTestStore.all()), "short password refused");
        Files.write(new File(source.database().getPath() + "-journal").toPath(), new byte[]{1});
        rejects(() -> source.export(BackupTestStore.all()), "open history refused");
        new File(source.database().getPath() + "-journal").delete();
    }
    static byte[] manifest(int app, int registry, boolean complete, String[] categories, int[] items, long[] bytes, int entries, long total) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(b);
        out.writeUTF("volt-app-data"); out.writeInt(2); out.writeLong(1); out.writeInt(app); out.writeInt(registry); out.writeBoolean(complete);
        out.writeInt(categories.length); for (int i = 0; i < categories.length; i++) { out.writeUTF(categories[i]); out.writeInt(items[i]); out.writeLong(bytes[i]); }
        out.writeInt(0); out.writeInt(entries); out.writeLong(total); out.flush(); return b.toByteArray();
    }
    static byte[] entries(Object... nameAndBytes) throws IOException {
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(plain)) { for (int i = 0; i < nameAndBytes.length; i += 2) { zip.putNextEntry(new ZipEntry((String) nameAndBytes[i])); zip.write((byte[]) nameAndBytes[i + 1]); zip.closeEntry(); } }
        return plain.toByteArray();
    }
    /** Rename the second entry to the first one's name inside the ZIP bytes. */
    static byte[] duplicate(byte[] zip, String name) {
        byte[] from = name.replace(".csv", ".csw").getBytes(StandardCharsets.UTF_8), to = name.getBytes(StandardCharsets.UTF_8);
        outer: for (int i = 0; i <= zip.length - from.length; i++) { for (int j = 0; j < from.length; j++) if (zip[i + j] != from[j]) continue outer; System.arraycopy(to, 0, zip, i, to.length); }
        return zip;
    }
    /** Test-only encryption of an arbitrary authenticated payload with the shipped record framing. */
    static byte[] encrypt(byte[] payload) throws IOException {
        byte[] header = AppDataArchive.header(); SecretKey key = AppDataArchive.key(BackupTestStore.PASSWORD, header);
        ByteArrayOutputStream out = new ByteArrayOutputStream(); out.write(header);
        AppDataArchive.RecordOutput records = new AppDataArchive.RecordOutput(out, header, key); records.write(payload, 0, payload.length); records.finish();
        return out.toByteArray();
    }
}
