package com.jjricks.volt;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** A temporary app data directory for unified backup tests: real files and fsyncs, preferences in
 * one file (committed per namespace like Android), a small text history "database", and faults. */
final class BackupTestStore implements BackupArchive.Storage {
    static final class Crash extends Error { Crash(String where) { super(where); } }
    static final char[] PASSWORD = "a long backup password".toCharArray();
    static final String FAKE_DB = "VOLTTESTDB\n";
    final File root; int version = 36; long usable = Long.MAX_VALUE;
    String failStep, crashStep, failPrefsAt, crashPrefsAt, failSync; boolean failMerge, failPrefsAlways, fast;
    Set<String> sqliteProfiles = new LinkedHashSet<>();
    final List<String> events = new ArrayList<>();
    BackupTestStore(File root) throws IOException {
        this.root = root; files().mkdirs(); database().getParentFile().mkdirs(); work().getParentFile().mkdirs();
        if (!prefsFile().exists()) write(empty());
    }
    public File files() { return new File(root, "files"); }
    public File database() { return new File(root, "databases/" + AppDataArchive.DATABASE); }
    public File work() { return new File(root, "no_backup/app-data-transfer"); }
    public int versionCode() { return version; }
    File prefsFile() { return new File(root, "shared_prefs.bin"); }
    static Map<String, Map<String, Object>> empty() { Map<String, Map<String, Object>> r = new LinkedHashMap<>(); for (String n : AppDataArchive.PREFERENCES) r.put(n, new LinkedHashMap<>()); return r; }
    public Map<String, Map<String, Object>> preferences() throws IOException { return AppDataArchive.decodePreferences(Files.readAllBytes(prefsFile().toPath())); }
    /** Replaces only the given namespaces, one commit at a time, as the Android adapter does. */
    public void preferences(Map<String, Map<String, Object>> value) throws IOException {
        for (Map.Entry<String, Map<String, Object>> namespace : value.entrySet()) {
            if (failPrefsAlways || namespace.getKey().equals(failPrefsAt)) { failPrefsAt = null; throw new IOException("Injected preference commit failure"); }
            Map<String, Map<String, Object>> all = preferences(); all.put(namespace.getKey(), new LinkedHashMap<>(namespace.getValue())); write(all);
            events.add("prefs:" + namespace.getKey());
            if (namespace.getKey().equals(crashPrefsAt)) { crashPrefsAt = null; throw new Crash("prefs:" + namespace.getKey()); }
        }
    }
    void write(Map<String, Map<String, Object>> all) throws IOException { Files.write(prefsFile().toPath(), AppDataArchive.encodePreferences(all)); }
    void put(String namespace, String key, Object value) throws IOException { Map<String, Map<String, Object>> all = preferences(); all.get(namespace).put(key, value); write(all); }
    public void validateDatabase(File database) throws IOException {
        byte[] bytes = Files.readAllBytes(database.toPath());
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        if (!text.startsWith(FAKE_DB) && !text.startsWith("SQLite format 3\0")) throw new IOException("Bad fixture database");
    }
    public Set<String> historyProfiles(File database) throws IOException {
        validateDatabase(database);
        String text = new String(Files.readAllBytes(database.toPath()), StandardCharsets.UTF_8);
        if (!text.startsWith(FAKE_DB)) return new LinkedHashSet<>(sqliteProfiles);
        Set<String> result = new LinkedHashSet<>();
        for (String line : rows(database)) result.add(line.split(",")[0]);
        return result;
    }
    static List<String> rows(File database) throws IOException {
        List<String> result = new ArrayList<>();
        if (!database.exists()) return result;
        for (String line : new String(Files.readAllBytes(database.toPath()), StandardCharsets.UTF_8).split("\n")) if (!line.isEmpty() && !line.equals(FAKE_DB.trim())) result.add(line);
        return result;
    }
    static void history(File database, String... rows) throws IOException {
        database.getParentFile().mkdirs(); StringBuilder out = new StringBuilder(FAKE_DB); for (String row : rows) out.append(row).append('\n');
        Files.write(database.toPath(), out.toString().getBytes(StandardCharsets.UTF_8));
    }
    /** Rows are pid,key,wall,value; unique on pid,key,wall; this phone's row wins. */
    public void mergeHistory(File live, File incoming, Map<String, String> mapping, File out) throws IOException {
        if (failMerge) throw new IOException("Injected history merge failure");
        LinkedHashMap<String, String> merged = new LinkedHashMap<>();
        if (live != null) for (String row : rows(live)) { String[] f = row.split(","); merged.put(f[0] + "," + f[1] + "," + f[2], row); }
        for (String row : rows(incoming)) {
            String[] f = row.split(","); String target = mapping.get(f[0]); if (target == null) continue;
            String key = target + "," + f[1] + "," + f[2]; if (!merged.containsKey(key)) merged.put(key, target + "," + f[1] + "," + f[2] + "," + f[3]);
        }
        history(out, merged.values().toArray(new String[0]));
    }
    public String[] dashboardLayout(String json) throws IOException {
        Map<String, Object> data = BackupJson.object(BackupJson.parse(json, 12));
        if (!"volt-dashboard-layouts".equals(data.get("format")) || !Long.valueOf(1).equals(data.get("version"))) throw new IOException("Not a dashboard layout file");
        Object slots = data.get("slots"); if (!(slots instanceof List) || ((List<?>) slots).size() != 3 || !(data.get("widgets") instanceof List)) throw new IOException("Expected three saved slots");
        String[] result = {json(data.get("widgets")), String.valueOf(data.get("columns")), null, null, null};
        for (int i = 0; i < 3; i++) { Object slot = ((List<?>) slots).get(i); result[2 + i] = slot == BackupJson.NULL ? null : json(slot); }
        return result;
    }
    public long usableSpace(File directory) { return usable; }
    public void step(String boundary) throws IOException {
        events.add(boundary);
        if (boundary.equals(failStep)) { failStep = null; throw new IOException("Injected failure at " + boundary); }
        if (boundary.equals(crashStep)) { crashStep = null; throw new Crash(boundary); }
    }
    public void syncDirectory(File directory) throws IOException {
        String path = root.toPath().relativize(directory.toPath()).toString();
        if (path.equals(failSync)) { failSync = null; throw new IOException("Injected directory sync failure"); }
        events.add("sync:" + path);
        if (!fast) AppDataArchive.syncDirectory(directory); // Bulk permutation loops skip real directory fsyncs.
    }
    static String json(Object value) {
        if (value instanceof String) return BackupJson.quote((String) value);
        if (value instanceof Map) { StringBuilder out = new StringBuilder("{"); for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) out.append(out.length() > 1 ? "," : "").append(BackupJson.quote((String) e.getKey())).append(':').append(json(e.getValue())); return out.append('}').toString(); }
        if (value instanceof List) { StringBuilder out = new StringBuilder("["); for (Object item : (List<?>) value) out.append(out.length() > 1 ? "," : "").append(json(item)); return out.append(']').toString(); }
        return String.valueOf(value);
    }

    // ---- Fixture data -------------------------------------------------------------------------

    static void file(File file, String text) throws IOException { file.getParentFile().mkdirs(); Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8)); }
    /** A complete stopped ride journal, optionally with phone and motion sidecars. */
    String ride(String profile, long wall, boolean phone, String label) throws IOException {
        RideStore rides = new RideStore(new File(files(), "rides"));
        String id = rides.start(profile, label, wall, wall / 7);
        for (int i = 0; i < 40; i++) { Map<String, RideStore.Sample> s = new LinkedHashMap<>(); s.put("speed_kmh", new RideStore.Sample(10 + i % 7, wall / 7 + i * 1000)); s.put("battery_percent", new RideStore.Sample(90 - i / 10d, wall / 7 + i * 1000)); rides.sample(wall + i * 1000, wall / 7 + i * 1000, s); }
        rides.stop(wall + 40000, wall / 7 + 40000);
        if (phone) {
            PhoneRideStore sensors = new PhoneRideStore(new File(files(), "phone-rides")); sensors.start(id, wall, wall / 7, "mounted");
            sensors.appendBatch(id, PhoneRideStore.row("GPS", wall + 1000, wall / 7 + 1000, 40.1, -111.2, 3, 4, 5, 6, 7), (wall / 7 + 1000) + ",1,2,3,4,5,6,7,8,9,0,0,0,1,3\n");
            sensors.stop(id, wall + 40000, wall / 7 + 40000);
        }
        return id;
    }
    String charge(String profile, long wall, boolean active) throws IOException {
        String id = UUID.randomUUID().toString();
        file(new File(files(), "charge-recordings/" + id + (active ? ".active" : ".csv")), "charge-v2," + profile + ",STOCK," + wall + "," + (wall / 7) + "\n" + (active ? "" : "end,STOPPED\n"));
        return id;
    }
    static String profileItem(String id, String name, String identity, String serial) {
        return "{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"identity\":\"" + identity + "\",\"role\":0,\"mac\":\"02:00:00:00:00:" + id.substring(0, 2) + "\",\"serial\":\"" + serial + "\",\"lastConnected\":5,\"fw_controller\":\"1.2\"}";
    }
    /** Every category populated for scooters `a` (configured) and `b`. */
    static BackupTestStore rich(File root) throws IOException {
        BackupTestStore s = new BackupTestStore(root);
        Map<String, Map<String, Object>> p = empty(); Map<String, Object> app = p.get("app_settings");
        app.put("theme", "light"); app.put("units", "km"); app.put("text_scale", 1.25f); app.put("poll_interval_ms", 1000); app.put("haptics_enabled", false);
        app.put("map_basemap_enabled", true); app.put("map_basemap_explained", true); app.put("stats_favourites", new LinkedHashSet<>(Arrays.asList("speed_kmh", "battery_percent")));
        app.put("stats_group_ride", true); app.put("ride_auto_stop", true); app.put("last_ride_label", "Commute 🛴");
        app.put("dashboard_columns", 4);
        for (String pid : new String[]{"a0000000-0000-4000-8000-00000000000a", "b0000000-0000-4000-8000-00000000000b"}) {
            app.put("dashboard_v1_" + pid, "[{\"id\":\"w-" + pid.charAt(0) + "\"}]"); app.put("dashboard_slot_v1_" + pid + "_0", "slot zero " + pid.charAt(0)); app.put("dashboard_slot_v1_" + pid + "_2", "slot two " + pid.charAt(0));
            app.put("dashboard_empty_example_v1_" + pid, false); app.put("range_source_" + pid, "learned"); app.put("range_follow_mode_" + pid, true); app.put("charge_charger_" + pid, "HIGH_SPEED");
            p.get("control_evidence").put(pid, "{\"cached\":\"" + pid.charAt(0) + "\"}");
        }
        app.put("dashboard_restore_undo_v1_" + A, "{\"undo\":true}");
        app.put("last_page", "rides"); app.put("more_away_boot", 3); app.put("notification_prompted", true); app.put("owner_import_applied", "import-1"); app.put("stats_query", "volt");
        app.put("legacy_removed_key", "only exact restores keep me");
        p.get("profiles").put("items", "[" + profileItem(A, "Fold UT5", "ABCDEF012345", "SERIAL-A") + "," + profileItem(B, "Spare", "", "SERIAL-B") + "]");
        p.get("profiles").put("active", A); p.get("profiles").put("legacy_migrated", true);
        p.get("scooter").put("identity", "ABCDEF012345"); p.get("scooter").put("role", 0);
        p.get("onboarding").put("onboarding_done", true); p.get("onboarding").put("nearby_asked", true);
        s.write(p);
        s.ride(A, 1760000000000L, true, "First ride"); s.ride(A, 1760100000000L, false, "Second"); s.ride(B, 1760200000000L, true, "Spare ride");
        s.charge(A, 1760300000000L, false); s.charge(B, 1760400000000L, true);
        history(s.database(), A + ",speed,1,10", A + ",speed,2,11", B + ",battery,1,80");
        return s;
    }
    static final String A = "a0000000-0000-4000-8000-00000000000a", B = "b0000000-0000-4000-8000-00000000000b";

    byte[] export(Collection<String> categories) throws IOException { ByteArrayOutputStream out = new ByteArrayOutputStream(); BackupArchive.write(this, out, PASSWORD, categories); return out.toByteArray(); }
    BackupArchive.Prepared stage(byte[] bytes) throws IOException { return BackupArchive.stage(this, new ByteArrayInputStream(bytes), PASSWORD); }
    BackupArchive.Prepared stage(File fixture, char[] password) throws IOException { try (InputStream in = new FileInputStream(fixture)) { return BackupArchive.stage(this, in, password); } }
    /** Plan with every suggested destination, choosing `fallback` where none is suggested. */
    BackupArchive.Plan plan(BackupArchive.Prepared prepared, Collection<String> categories, boolean replace, String fallback) throws IOException {
        Map<String, String> chosen = new LinkedHashMap<>();
        if (!replace) for (BackupArchive.Destination d : BackupArchive.destinations(this, prepared, categories)) chosen.put(d.source, d.suggested != null ? d.suggested : fallback);
        return BackupArchive.plan(this, prepared, categories, replace, chosen);
    }
    void restore(byte[] bytes, Collection<String> categories, boolean replace, String fallback) throws IOException {
        BackupArchive.Prepared prepared = stage(bytes); BackupTransaction.apply(this, plan(prepared, categories, replace, fallback));
    }
    /** Every durable file below files/ and the database, by relative path. */
    Map<String, String> fileHashes() throws IOException {
        Map<String, String> result = new TreeMap<>();
        for (String folder : new String[]{"rides", "phone-rides", "charge-recordings"}) {
            File[] list = new File(files(), folder).listFiles();
            if (list != null) for (File f : list) result.put(folder + "/" + f.getName(), Base64.getEncoder().encodeToString(RideArchive.digest(f)));
        }
        if (database().exists()) result.put("database", new String(Files.readAllBytes(database().toPath()), StandardCharsets.UTF_8));
        return result;
    }
    /** A copy of a pristine stage under this store, so permutation loops need not re-derive the key. */
    BackupArchive.Prepared copyStage(BackupArchive.Prepared p) throws IOException {
        File directory = new File(work(), "stage-" + UUID.randomUUID()); BackupArchiveTest.copyTree(p.directory, directory);
        return new BackupArchive.Prepared(p.kind, directory, new File(directory, "data"), p.createdAtMs, p.complete, p.categories, p.sources, p.preferences, p.profiles, p.rides, p.charges, p.orphans, p.history, p.fileCount, p.totalBytes);
    }
    static Set<String> all() { return new LinkedHashSet<>(Arrays.asList(BackupRegistry.CATEGORIES)); }
    static void delete(File file) throws IOException { AppDataArchive.deleteTree(file); }
}
