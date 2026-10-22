package local.navee.offlinelock;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.*;
import java.util.zip.*;
import javax.crypto.SecretKey;

/**
 * Category backups (`.voltbackup` payload version 2) and the read-only restore plan, without
 * Android APIs. Every new file uses the version 1 record encryption unchanged. Staging also
 * accepts the older full backup, `.voltrides`, dashboard layouts and pairing-only owner files. Nothing here changes
 * live data: BackupTransaction applies an approved Plan. The caller pauses every app writer first.
 */
final class BackupArchive {
    static final String FORMAT = "volt-app-data", LAYOUT_SOURCE = "layout-file", SKIP = "", NO_SCOOTER = "unconfigured";
    static final int VERSION = 2, MAX_MANIFEST = 1024 * 1024, MAX_LAYOUT = 1024 * 1024, MAX_OWNER = 16 * 1024;
    static final long HISTORY_BYTES = 64L * 1024 * 1024;
    /** File kinds recognised by content, never by name. */
    static final int UNSUPPORTED = 0, ENCRYPTED = 1, RIDE_FILE = 2, LAYOUT_FILE = 3;
    static final String[] CATEGORIES = BackupRegistry.CATEGORIES;
    private static final String MANIFEST = "manifest.bin", UNASSIGNED = "prefs/unassigned.bin", DATABASE = "database/" + AppDataArchive.DATABASE;
    private static final String UUID_PATTERN = "[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}";
    private static final String[] RECORDING_FOLDERS = {"rides", "phone-rides", "charge-recordings"};

    /** Android supplies preferences, SQLite and layout validation; tests supply fakes and faults. */
    interface Storage extends AppDataArchive.Storage {
        /** Profile IDs (the `profiles.name` rows) of a validated history database. */
        Set<String> historyProfiles(File database) throws IOException;
        /** Write a closed, checked copy of `live` (may be absent) plus `incoming` rows, remapped by
         * source → destination profile, this phone's rows winning, pruned to the history limit. */
        void mergeHistory(File live, File incoming, Map<String, String> mapping, File out) throws IOException;
        /** A validated layout file as {dashboard, columns, slot 1, slot 2, slot 3}; a null slot is empty. */
        String[] dashboardLayout(String json) throws IOException;
        default void validateProfiles(String items) throws IOException { }
        default long usableSpace(File directory) { return directory.getUsableSpace(); }
        /** Fault injection at a named transaction boundary. */
        default void step(String boundary) throws IOException { }
    }
    static final class Source {
        final String id, name, serial;
        Source(String id, String name, String serial) { this.id = id; this.name = name; this.serial = serial; }
    }
    /** One staged ride (with its sidecars) or charge. */
    static final class Recording {
        final String id, path, profile; final long wall, mono; final boolean readable;
        final List<String> group; // paths below files/, sidecars first, journal last
        Recording(String id, String path, String profile, long wall, long mono, boolean readable, List<String> group) {
            this.id = id; this.path = path; this.profile = profile; this.wall = wall; this.mono = mono; this.readable = readable; this.group = group;
        }
    }
    static final class Prepared {
        final String kind; // v2, v1, rides, layout or owner (pairing access only)
        final File directory, data;
        final long createdAtMs; final boolean complete;
        /** Categories in the file, in registry order, with an item count for the preview. */
        final LinkedHashMap<String, Integer> categories;
        final LinkedHashMap<String, Source> sources;
        final Map<String, Map<String, Object>> preferences;
        final List<BackupProfiles.Profile> profiles;
        final List<Recording> rides, charges;
        final List<String> orphans;
        final Set<String> history;
        final int fileCount, preferenceCount; final long totalBytes;
        boolean consumed;
        Prepared(String kind, File directory, File data, long created, boolean complete, LinkedHashMap<String, Integer> categories, LinkedHashMap<String, Source> sources,
                Map<String, Map<String, Object>> preferences, List<BackupProfiles.Profile> profiles, List<Recording> rides, List<Recording> charges, List<String> orphans,
                Set<String> history, int files, long bytes) {
            this.kind = kind; this.directory = directory; this.data = data; createdAtMs = created; this.complete = complete; this.categories = categories; this.sources = sources;
            this.preferences = preferences; this.profiles = profiles; this.rides = rides; this.charges = charges; this.orphans = orphans; this.history = history;
            fileCount = files; totalBytes = bytes; int count = 0; for (Map<String, Object> values : preferences.values()) count += values.size(); preferenceCount = count;
        }
        boolean encrypted() { return kind.equals("v2") || kind.equals("v1"); }
    }
    /** Where one backup scooter's data goes. `suggested` is pre-chosen; null needs a choice. */
    static final class Destination {
        final String source, name, suggested; final boolean addable;
        Destination(String source, String name, String suggested, boolean addable) { this.source = source; this.name = name; this.suggested = suggested; this.addable = addable; }
    }
    static final class Plan {
        final Prepared prepared; final Set<String> categories; final boolean replace;
        final Map<String, String> mapping, historyMapping;
        final List<String> additions, lines;
        final Map<String, Map<String, Object>> preferences;
        final boolean history;
        Plan(Prepared prepared, Set<String> categories, boolean replace, Map<String, String> mapping, Map<String, String> historyMapping, List<String> additions,
                List<String> lines, Map<String, Map<String, Object>> preferences, boolean history) {
            this.prepared = prepared; this.categories = Collections.unmodifiableSet(categories); this.replace = replace; this.mapping = Collections.unmodifiableMap(mapping);
            this.historyMapping = Collections.unmodifiableMap(historyMapping); this.additions = Collections.unmodifiableList(additions); this.lines = Collections.unmodifiableList(lines);
            this.preferences = preferences; this.history = history;
        }
        /** Same effect on the same live data, so an approval still describes what apply will do. */
        boolean same(Plan other) {
            return other != null && replace == other.replace && additions.equals(other.additions) && lines.equals(other.lines) && preferences.equals(other.preferences)
                    && historyMapping.equals(other.historyMapping) && history == other.history;
        }
    }
    private static final class Entry {
        final String name, category; final File file; final long length;
        Entry(String name, String category, File file) { this.name = name; this.category = category; this.file = file; length = file.length(); }
    }
    private BackupArchive() { }

    // ---- Export ------------------------------------------------------------------------------

    /** Cheap counts for the Back up list: {items, bytes} per category. Files are listed, not parsed. */
    static LinkedHashMap<String, long[]> inventory(Storage storage) throws IOException {
        Map<String, Map<String, Object>> live = storage.preferences();
        LinkedHashMap<String, long[]> result = new LinkedHashMap<>();
        for (String category : CATEGORIES) result.put(category, new long[2]);
        result.get(BackupRegistry.SCOOTERS)[0] = profiles(live).size();
        result.get(BackupRegistry.DASHBOARDS)[0] = perProfile(live, BackupRegistry.DASHBOARDS).size();
        result.get(BackupRegistry.SCOOTER_PREFERENCES)[0] = perProfile(live, BackupRegistry.SCOOTER_PREFERENCES).size();
        result.get(BackupRegistry.PREFERENCES)[0] = subset(live, BackupRegistry.PREFERENCES).get("app_settings").size();
        for (Entry entry : collect(storage, new HashSet<>(Arrays.asList(CATEGORIES)))) {
            long[] count = result.get(entry.category); count[1] += entry.length;
            if (entry.name.startsWith("files/rides/") || entry.name.startsWith("files/charge-recordings/") || entry.name.equals(DATABASE)) count[0]++;
        }
        return result;
    }

    /** Unreadable saved data still requires an explicit Replace everything choice. A complete
     * backup must remain usable for repair without trusting the destination's profile JSON. */
    static boolean hasData(Storage storage) {
        try {
            Map<String, long[]> counts = inventory(storage);
            for (String category : new String[]{BackupRegistry.SCOOTERS, BackupRegistry.RIDES, BackupRegistry.CHARGES, BackupRegistry.HISTORY})
                if (counts.get(category)[0] > 0) return true;
            return false;
        } catch (IOException unreadable) { return true; }
    }

    /** Encrypt the selected categories; selecting all seven makes a complete backup. */
    static void write(Storage storage, OutputStream output, char[] password, Collection<String> selected) throws IOException {
        AppDataArchive.requirePassword(password); AppDataArchive.ensureNoTransaction(storage); AppDataArchive.checkDatabaseClosed(storage);
        Set<String> categories = new LinkedHashSet<>();
        for (String category : CATEGORIES) if (selected.contains(category)) categories.add(category);
        if (categories.isEmpty() || categories.size() != new HashSet<>(selected).size()) throw new IOException("Choose what to back up.");
        boolean complete = categories.size() == CATEGORIES.length;
        Map<String, Map<String, Object>> live = storage.preferences();
        LinkedHashMap<String, byte[]> prefs = new LinkedHashMap<>();
        try {
            for (String category : categories) { Map<String, Map<String, Object>> values = subset(live, category); if (count(values) > 0) prefs.put("prefs/" + category + ".bin", encode(values)); }
            if (complete) { Map<String, Map<String, Object>> values = unassigned(live); if (count(values) > 0) prefs.put(UNASSIGNED, encode(values)); }
            List<Entry> files = collect(storage, categories);
            LinkedHashMap<String, long[]> totals = new LinkedHashMap<>();
            for (String category : categories) totals.put(category, new long[2]);
            long total = 0;
            for (Map.Entry<String, byte[]> entry : prefs.entrySet()) {
                total = AppDataArchive.addBounded(total, entry.getValue().length, AppDataArchive.MAX_BYTES);
                String category = entry.getKey().equals(UNASSIGNED) ? null : entry.getKey().substring(6, entry.getKey().length() - 4);
                if (category != null) { totals.get(category)[0]++; totals.get(category)[1] += entry.getValue().length; }
            }
            for (Entry entry : files) { total = AppDataArchive.addBounded(total, entry.length, AppDataArchive.MAX_BYTES); totals.get(entry.category)[0]++; totals.get(entry.category)[1] += entry.length; }
            if (prefs.size() + files.size() > AppDataArchive.MAX_ENTRIES) throw new IOException("There are too many files for one backup.");
            // Names and serials let a restore map rides and settings to a scooter; credentials stay in the Scooters category.
            List<BackupProfiles.Profile> table = new ArrayList<>();
            if (!categories.equals(Collections.singleton(BackupRegistry.PREFERENCES))) try { table = profiles(live); } catch (IOException unreadable) { /* The items still travel exactly. */ }
            ByteArrayOutputStream manifestBytes = new ByteArrayOutputStream(); DataOutputStream manifest = new DataOutputStream(manifestBytes);
            manifest.writeUTF(FORMAT); manifest.writeInt(VERSION); manifest.writeLong(System.currentTimeMillis()); manifest.writeInt(storage.versionCode());
            manifest.writeInt(BackupRegistry.VERSION); manifest.writeBoolean(complete);
            manifest.writeInt(totals.size());
            for (Map.Entry<String, long[]> entry : totals.entrySet()) { manifest.writeUTF(entry.getKey()); manifest.writeInt((int) entry.getValue()[0]); manifest.writeLong(entry.getValue()[1]); }
            manifest.writeInt(table.size());
            for (BackupProfiles.Profile p : table) { manifest.writeUTF(bounded(p.id, 128)); manifest.writeUTF(bounded(p.name, 200)); manifest.writeUTF(bounded(p.serial, 128)); }
            manifest.writeInt(prefs.size() + files.size()); manifest.writeLong(total); manifest.flush();
            if (manifestBytes.size() > MAX_MANIFEST) throw new IOException("Backup metadata is too large.");
            byte[] header = AppDataArchive.header(); SecretKey key = AppDataArchive.key(password, header);
            output.write(header);
            AppDataArchive.RecordOutput encrypted = new AppDataArchive.RecordOutput(output, header, key);
            ZipOutputStream zip = new ZipOutputStream(encrypted);
            try {
                zip.putNextEntry(new ZipEntry(MANIFEST)); manifestBytes.writeTo(zip); zip.closeEntry();
                for (Map.Entry<String, byte[]> entry : prefs.entrySet()) { zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry(); }
                byte[] buffer = new byte[AppDataArchive.BLOCK];
                for (Entry entry : files) {
                    if (entry.file.length() != entry.length) throw new IOException("App data changed during backup. Try again.");
                    zip.putNextEntry(new ZipEntry(entry.name));
                    try (InputStream in = new FileInputStream(entry.file)) {
                        long copied = AppDataArchive.copy(in, zip, buffer, AppDataArchive.fileLimit(entry.name));
                        if (copied != entry.length || entry.file.length() != entry.length) throw new IOException("App data changed during backup. Try again.");
                    }
                    zip.closeEntry();
                }
                zip.finish(); encrypted.finish(); output.flush();
            } finally { try { zip.close(); } catch (IOException ignored) { } encrypted.clear(); }
        } finally { for (byte[] bytes : prefs.values()) Arrays.fill(bytes, (byte) 0); }
    }
    /** Durable files of the selected categories, sorted. Temporary repair and mapping files are skipped. */
    private static List<Entry> collect(Storage storage, Set<String> categories) throws IOException {
        List<Entry> result = new ArrayList<>();
        for (String folderName : RECORDING_FOLDERS) {
            String category = folderName.equals("charge-recordings") ? BackupRegistry.CHARGES : BackupRegistry.RIDES;
            if (!categories.contains(category)) continue;
            File folder = new File(storage.files(), folderName); File[] children = folder.listFiles();
            if (folder.exists() && (children == null || Files.isSymbolicLink(folder.toPath()))) throw new IOException("A recording folder could not be read.");
            if (children != null) for (File child : children) {
                String name = "files/" + folderName + "/" + child.getName();
                if (!AppDataArchive.allowedFile(name)) { if (child.getName().endsWith(".repair") || child.getName().endsWith(".mapping")) continue; throw new IOException("An unsupported recording file needs attention before backup."); }
                if (Files.isSymbolicLink(child.toPath()) || !child.isFile() || !child.getCanonicalFile().getParentFile().equals(folder.getCanonicalFile())) throw new IOException("Unexpected recording file.");
                if (child.length() > AppDataArchive.fileLimit(name)) throw new IOException("One recording is too large for a backup.");
                result.add(new Entry(name, category, child));
            }
        }
        File database = storage.database();
        if (categories.contains(BackupRegistry.HISTORY) && database.exists()) {
            if (Files.isSymbolicLink(database.toPath()) || !database.isFile() || database.length() > HISTORY_BYTES) throw new IOException("History storage is not a supported database.");
            result.add(new Entry(DATABASE, BackupRegistry.HISTORY, database));
        }
        Collections.sort(result, (a, b) -> a.name.compareTo(b.name));
        return result;
    }

    // ---- Staging -----------------------------------------------------------------------------

    /** Recognise a picked file from its first bytes, so a password is asked only when needed. */
    static int sniff(byte[] head, int length) {
        if (length >= 7 && new String(head, 0, 7, StandardCharsets.ISO_8859_1).equals("VOLTBKP")) return ENCRYPTED;
        if (length >= 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4) return RIDE_FILE;
        int i = length >= 3 && (head[0] & 0xff) == 0xef && (head[1] & 0xff) == 0xbb && (head[2] & 0xff) == 0xbf ? 3 : 0;
        while (i < length && " \t\r\n".indexOf(head[i]) >= 0) i++;
        return i < length && head[i] == '{' ? LAYOUT_FILE : UNSUPPORTED;
    }
    /** Authenticate, extract with bounds and validate into private staging. A password is needed
     * only for an encrypted file. Live data never changes here. */
    static Prepared stage(Storage storage, InputStream input, char[] password) throws IOException {
        AppDataArchive.ensureNoTransaction(storage);
        BufferedInputStream in = new BufferedInputStream(input, 65536); in.mark(64);
        byte[] head = new byte[64]; int length = 0;
        for (int n; length < head.length && (n = in.read(head, length, head.length - length)) > 0; ) length += n;
        in.reset();
        switch (sniff(head, length)) {
            case ENCRYPTED: return encrypted(storage, in, password);
            case RIDE_FILE: return rides(storage, in);
            case LAYOUT_FILE: return jsonFile(storage, in);
            default: throw new IOException("Choose a Volt backup file.");
        }
    }
    static void discard(Storage storage, Prepared prepared) throws IOException {
        if (prepared != null && !prepared.consumed && AppDataArchive.isStage(storage, prepared.directory)) { AppDataArchive.deleteTree(prepared.directory); prepared.consumed = true; }
    }
    private static File newStage(Storage storage) throws IOException {
        AppDataArchive.mkdir(storage.work());
        File directory = new File(storage.work(), "stage-" + UUID.randomUUID()); AppDataArchive.mkdir(directory); return directory;
    }
    private static IOException failed(File directory, Exception failure) {
        try { AppDataArchive.deleteTree(directory); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
        return failure instanceof IOException ? (IOException) failure : new IOException("The backup could not be opened.", failure);
    }
    private static Prepared encrypted(Storage storage, InputStream in, char[] password) throws IOException {
        AppDataArchive.requirePassword(password);
        File directory = newStage(storage);
        try {
            File payload = new File(directory, "payload.zip");
            // Authenticate every record, including the terminal record, before interpreting ZIP input.
            try (FileOutputStream out = new FileOutputStream(payload)) { AppDataArchive.decrypt(in, out, password); out.getFD().sync(); }
            File data = new File(directory, "data"); AppDataArchive.mkdir(data);
            String format;
            try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(payload)))) {
                ZipEntry first = zip.getNextEntry();
                if (first == null || first.isDirectory() || !MANIFEST.equals(first.getName())) throw new IOException("This is not a supported Volt app backup.");
                format = new DataInputStream(new ByteArrayInputStream(AppDataArchive.readBounded(zip, MAX_MANIFEST))).readUTF();
            }
            Prepared result;
            if ("volt-full-app-data".equals(format)) {
                AppDataArchive.Prepared legacy = AppDataArchive.extract(storage, payload, data);
                AppDataArchive.deleteTree(new File(data, "preferences.bin"));
                result = finish(storage, "v1", directory, data, legacy.createdAtMs, true, Arrays.asList(CATEGORIES), legacy.preferences, null, legacy.fileCount, legacy.totalBytes);
            } else if (FORMAT.equals(format)) result = extract(storage, payload, directory, data);
            else throw new IOException("Unsupported backup format.");
            if (!payload.delete()) throw new IOException("Could not remove the temporary backup payload.");
            return result;
        } catch (Exception failure) { throw failed(directory, failure); }
    }
    private static Prepared extract(Storage storage, File archive, File directory, File data) throws IOException {
        Map<String, Map<String, Object>> prefs = new LinkedHashMap<>();
        LinkedHashMap<String, long[]> declared = new LinkedHashMap<>(), actual = new LinkedHashMap<>();
        LinkedHashMap<String, Source> table = new LinkedHashMap<>();
        long created, expectedBytes, total = 0; int expectedEntries, entries = 0, fileCount = 0; boolean complete;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(archive)))) {
            zip.getNextEntry();
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(AppDataArchive.readBounded(zip, MAX_MANIFEST)));
            if (!FORMAT.equals(in.readUTF()) || in.readInt() != VERSION) throw new IOException("Unsupported backup format.");
            created = in.readLong(); int app = in.readInt(), registry = in.readInt(); complete = in.readBoolean();
            if (created <= 0 || app <= 0 || app > storage.versionCode() || registry <= 0 || registry > BackupRegistry.VERSION) throw new IOException("The backup needs a newer app version, or has invalid metadata.");
            int categories = in.readInt(); if (categories < 1 || categories > CATEGORIES.length) throw new IOException("The backup has invalid metadata.");
            for (int i = 0; i < categories; i++) {
                String id = in.readUTF(); int items = in.readInt(); long bytes = in.readLong();
                if (!BackupRegistry.known(id) || items < 0 || items > AppDataArchive.MAX_ENTRIES || bytes < 0 || bytes > AppDataArchive.MAX_BYTES || declared.put(id, new long[]{items, bytes}) != null) throw new IOException("The backup has invalid categories.");
            }
            if (complete && declared.size() != CATEGORIES.length) throw new IOException("The backup has invalid categories.");
            int profiles = in.readInt(); if (profiles < 0 || profiles > BackupProfiles.MAX_PROFILES) throw new IOException("The backup has invalid scooters.");
            for (int i = 0; i < profiles; i++) {
                String id = in.readUTF(), name = in.readUTF(), serial = in.readUTF();
                if (id.isEmpty() || id.length() > 128 || name.length() > 200 || serial.length() > 128 || table.put(id, new Source(id, name, serial)) != null) throw new IOException("The backup has invalid scooters.");
            }
            expectedEntries = in.readInt(); expectedBytes = in.readLong();
            if (in.read() != -1 || expectedEntries < 0 || expectedEntries > AppDataArchive.MAX_ENTRIES || expectedBytes < 0 || expectedBytes > AppDataArchive.MAX_BYTES) throw new IOException("The backup has invalid metadata.");
            zip.closeEntry();
            Set<String> seen = new HashSet<>(Collections.singleton(MANIFEST)); ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (!seen.add(name) || entry.isDirectory() || ++entries > expectedEntries) throw new IOException("The backup contains an unsupported or duplicate entry.");
                String category; long length;
                if (name.equals(UNASSIGNED) || name.startsWith("prefs/")) {
                    category = name.equals(UNASSIGNED) ? null : name.substring(6, Math.max(6, name.length() - 4));
                    if (category == null ? !complete : !name.equals("prefs/" + category + ".bin") || !declared.containsKey(category)) throw new IOException("The backup contains an unsupported entry.");
                    byte[] bytes = AppDataArchive.readBounded(zip, AppDataArchive.MAX_PREFERENCES); length = bytes.length;
                    try {
                        for (Map.Entry<String, Map<String, Object>> namespace : AppDataArchive.decodePreferences(bytes, true).entrySet()) {
                            Map<String, Object> values = prefs.computeIfAbsent(namespace.getKey(), k -> new LinkedHashMap<>());
                            for (Map.Entry<String, Object> value : namespace.getValue().entrySet()) if (values.put(value.getKey(), value.getValue()) != null) throw new IOException("The backup repeats an app preference.");
                        }
                    } finally { Arrays.fill(bytes, (byte) 0); }
                } else {
                    category = fileCategory(name);
                    if (category == null || !declared.containsKey(category)) throw new IOException("The backup contains an unsupported or out-of-category file.");
                    File target = AppDataArchive.contained(data, name); AppDataArchive.mkdir(target.getParentFile());
                    try (FileOutputStream out = new FileOutputStream(target)) { length = AppDataArchive.copy(zip, out, new byte[AppDataArchive.BLOCK], Math.min(AppDataArchive.fileLimit(name), AppDataArchive.MAX_BYTES - total)); out.getFD().sync(); }
                    fileCount++;
                }
                total = AppDataArchive.addBounded(total, length, AppDataArchive.MAX_BYTES);
                if (category != null) { long[] sum = actual.computeIfAbsent(category, k -> new long[2]); sum[0]++; sum[1] += length; }
                zip.closeEntry();
            }
        }
        if (entries != expectedEntries || total != expectedBytes) throw new IOException("The backup is incomplete or has inconsistent contents.");
        for (Map.Entry<String, long[]> category : declared.entrySet()) {
            long[] sum = actual.containsKey(category.getKey()) ? actual.get(category.getKey()) : new long[2];
            if (sum[0] != category.getValue()[0] || sum[1] != category.getValue()[1]) throw new IOException("The backup is incomplete or has inconsistent contents.");
        }
        return finish(storage, "v2", directory, data, created, complete, declared.keySet(), prefs, table, fileCount, total);
    }
    private static String fileCategory(String name) {
        if (!AppDataArchive.allowedFile(name)) return null;
        if (name.equals(DATABASE)) return BackupRegistry.HISTORY;
        return name.startsWith("files/charge-recordings/") ? BackupRegistry.CHARGES : BackupRegistry.RIDES;
    }
    private static Prepared rides(Storage storage, InputStream in) throws IOException {
        File work = storage.work(); AppDataArchive.mkdir(work);
        RideArchive.Storage view = new RideArchive.Storage() {
            public File files() { return storage.files(); }
            public File work() { return work; }
            public void sync(File directory) throws IOException { storage.syncDirectory(directory); }
        };
        RideArchive.Prepared rides = RideArchive.stage(view, in);
        try {
            File data = new File(rides.directory, "data"), files = new File(data, "files"); AppDataArchive.mkdir(files);
            for (String folder : new String[]{"rides", "phone-rides"}) {
                File from = new File(rides.directory, folder);
                if (from.exists()) Files.move(from.toPath(), new File(files, folder).toPath());
            }
            LinkedHashMap<String, Source> table = new LinkedHashMap<>();
            for (Map.Entry<String, String> source : rides.profiles.entrySet()) table.put(source.getKey(), new Source(source.getKey(), source.getValue(), ""));
            int count = 0; for (String folder : new String[]{"rides", "phone-rides"}) { File[] list = new File(files, folder).listFiles(); if (list != null) count += list.length; }
            return finish(storage, "rides", rides.directory, data, rides.createdAtMs, false, Collections.singleton(BackupRegistry.RIDES), new LinkedHashMap<>(), table, count, rides.bytes);
        } catch (Exception failure) { throw failed(rides.directory, failure); }
    }
    private static Prepared jsonFile(Storage storage, InputStream in) throws IOException {
        byte[] bytes = AppDataArchive.readBounded(in, MAX_LAYOUT); String json;
        try { json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (IOException bad) { throw new IOException("Choose a Volt backup file.", bad); }
        if (json.startsWith("﻿")) json = json.substring(1);
        // Strict parsing rejects duplicate keys and excessive nesting before either Android
        // layout parsing or credentials are considered. File names never select an import mode.
        Map<String, Object> parsed = BackupJson.object(BackupJson.parse(json, 12));
        if ("navee-offline-owner-v1".equals(parsed.get("format"))) return owner(storage, parsed, bytes.length);
        return layout(storage, json, bytes.length);
    }
    private static Prepared layout(Storage storage, String json, int length) throws IOException {
        String[] layout = storage.dashboardLayout(json);
        if (layout == null || layout.length != 5 || layout[0] == null || layout[1] == null) throw new IOException("Not a dashboard layout file.");
        Map<String, Object> app = new LinkedHashMap<>();
        try { app.put("dashboard_columns", Integer.parseInt(layout[1])); } catch (NumberFormatException bad) { throw new IOException("Not a dashboard layout file.", bad); }
        app.put("dashboard_v1_" + LAYOUT_SOURCE, layout[0]);
        for (int i = 0; i < 3; i++) if (layout[2 + i] != null) app.put("dashboard_slot_v1_" + LAYOUT_SOURCE + "_" + i, layout[2 + i]);
        // An imported empty third slot is intentional; keep the example from being reseeded.
        app.put("dashboard_empty_example_v1_" + LAYOUT_SOURCE, layout[4] == null);
        Map<String, Map<String, Object>> prefs = new LinkedHashMap<>(); prefs.put("app_settings", app);
        LinkedHashMap<String, Source> table = new LinkedHashMap<>(); table.put(LAYOUT_SOURCE, new Source(LAYOUT_SOURCE, "Layout file", ""));
        File directory = newStage(storage);
        try { File data = new File(directory, "data"); AppDataArchive.mkdir(data); return finish(storage, "layout", directory, data, 0, false, Collections.singleton(BackupRegistry.DASHBOARDS), prefs, table, 0, length); }
        catch (Exception failure) { throw failed(directory, failure); }
    }
    /** Old owner exports contain one access key, not app data. Convert to a Scooters-only
     * staged profile so the ordinary preview, add-only merge and transaction apply unchanged. */
    private static Prepared owner(Storage storage, Map<String, Object> json, int length) throws IOException {
        if (length > MAX_OWNER) throw new IOException("The pairing file is too large.");
        Set<String> allowed = new HashSet<>(Arrays.asList("format", "owner_identity", "role", "name", "scooter_address", "scooter_serial", "model_name", "captured_at_utc", "note"));
        if (!allowed.containsAll(json.keySet())) throw new IOException("The pairing file contains unsupported fields.");
        String identity = ownerText(json, "owner_identity", 12, true).toUpperCase(Locale.ROOT);
        if (!identity.matches("[0-9A-F]{12}")) throw new IOException("The pairing file has an invalid owner identity.");
        Object roleValue = json.get("role");
        if (!(roleValue instanceof Number) || (((Number)roleValue).doubleValue() != 0 && ((Number)roleValue).doubleValue() != 1))
            throw new IOException("The pairing file has an invalid owner role.");
        int role = ((Number)roleValue).intValue();
        // The command-line and browser setup helpers emit this metadata rather than a
        // user's profile name. It is display text only, never model capability evidence.
        String model = ownerText(json, "model_name", 80, false).trim();
        ownerText(json, "captured_at_utc", 64, false); ownerText(json, "note", 1024, false);
        String name = ownerText(json, "name", 80, false).trim(); if (name.isEmpty()) name = model.isEmpty() ? "Scooter" : model;
        String mac = ownerText(json, "scooter_address", 17, false).toUpperCase(Locale.ROOT);
        if (!mac.isEmpty() && !mac.matches("([0-9A-F]{2}:){5}[0-9A-F]{2}")) throw new IOException("The pairing file has an invalid Bluetooth address.");
        String serial = ownerText(json, "scooter_serial", 128, false);
        // Old files had no UUID. A stable private ID makes repeat imports idempotent even
        // before either a serial or address has been discovered; names do not affect it.
        String id = UUID.nameUUIDFromBytes(("volt-owner-v1\0" + identity + "\0" + role + "\0" + mac + "\0" + serial.toUpperCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8)).toString();
        String raw = "{\"id\":" + BackupJson.quote(id) + ",\"name\":" + BackupJson.quote(name)
                + ",\"identity\":" + BackupJson.quote(identity) + ",\"role\":" + role
                + ",\"mac\":" + BackupJson.quote(mac) + ",\"serial\":" + BackupJson.quote(serial) + ",\"lastConnected\":0}";
        Map<String, Object> profile = new LinkedHashMap<>(); profile.put("items", "[" + raw + "]"); profile.put("active", id);
        Map<String, Map<String, Object>> prefs = new LinkedHashMap<>(); prefs.put("profiles", profile);
        File directory = newStage(storage);
        try { File data = new File(directory, "data"); AppDataArchive.mkdir(data); return finish(storage, "owner", directory, data, 0, false, Collections.singleton(BackupRegistry.SCOOTERS), prefs, null, 0, length); }
        catch (Exception failure) { throw failed(directory, failure); }
    }
    private static String ownerText(Map<String, Object> json, String key, int max, boolean required) throws IOException {
        Object value = json.get(key);
        if (!json.containsKey(key) && !required) return "";
        if (!(value instanceof String) || ((String)value).length() > max || ((String)value).matches("(?s).*[\\x00-\\x1f\\x7f].*"))
            throw new IOException("The pairing file has an invalid " + key.replace('_', ' ') + ".");
        return (String)value;
    }
    /** Validate everything staged and describe it. Unreadable recordings are kept for an exact
     * Replace everything (as older versions copied them) but are never merged. */
    private static Prepared finish(Storage storage, String kind, File directory, File data, long created, boolean complete, Collection<String> present,
            Map<String, Map<String, Object>> preferences, LinkedHashMap<String, Source> table, int fileCount, long totalBytes) throws IOException {
        Map<String, Map<String, Object>> prefs = new LinkedHashMap<>();
        for (String name : BackupRegistry.NAMESPACES) prefs.put(name, preferences.containsKey(name) ? preferences.get(name) : new LinkedHashMap<>());
        for (String name : preferences.keySet()) if (!BackupRegistry.namespace(name)) throw new IOException("The backup has an unsupported preference group.");
        BackupRegistry.validateTypes(prefs);
        Object items = prefs.get("profiles").get("items");
        List<BackupProfiles.Profile> profiles = BackupProfiles.parse(items, true);
        if (items != null) storage.validateProfiles((String) items);
        LinkedHashMap<String, Source> sources = new LinkedHashMap<>();
        if (table != null) sources.putAll(table);
        for (BackupProfiles.Profile p : profiles) if (!sources.containsKey(p.id)) sources.put(p.id, new Source(p.id, p.name, p.serial));
        List<Recording> rides = new ArrayList<>(), charges = new ArrayList<>(); List<String> orphans = new ArrayList<>();
        File files = new File(data, "files");
        Set<String> rideIds = new HashSet<>();
        File[] journals = new File(files, "rides").listFiles();
        if (journals != null) for (File journal : sorted(journals)) {
            String id = journal.getName().substring(0, journal.getName().length() - 4); rideIds.add(id.toLowerCase(Locale.ROOT));
            List<String> group = new ArrayList<>();
            for (String path : RideArchive.paths(id)) if (new File(files, path).isFile()) group.add(path);
            RideStore.Summary summary = null;
            try {
                for (String path : group) RideArchive.validateText(new File(files, path), path.startsWith("rides/"), path.endsWith("-motion.csv"));
                summary = RideStore.inspectJournal(journal);
                if (summary.scooterId.isEmpty() || summary.scooterId.length() > 160) summary = null;
            } catch (IOException unreadable) { summary = null; }
            rides.add(summary == null ? new Recording(id, "rides/" + journal.getName(), "", 0, 0, false, group)
                    : new Recording(id, "rides/" + journal.getName(), summary.scooterId, summary.startWallMs, summary.startMonotonicMs, true, group));
        }
        File[] phones = new File(files, "phone-rides").listFiles();
        if (phones != null) for (File phone : sorted(phones)) {
            String name = phone.getName(); String id = name.length() > 36 ? name.substring(0, 36) : "";
            if (!id.matches(UUID_PATTERN) || !name.matches(UUID_PATTERN + "-(phone|motion)\\.csv") || !rideIds.contains(id.toLowerCase(Locale.ROOT))) orphans.add("phone-rides/" + name);
        }
        Set<String> chargeIds = new HashSet<>();
        File[] saved = new File(files, "charge-recordings").listFiles();
        if (saved != null) for (File charge : sorted(saved)) {
            String id = charge.getName().substring(0, 36);
            if (!chargeIds.add(id.toLowerCase(Locale.ROOT))) throw new IOException("The backup repeats a charge recording.");
            ChargeSessionStore.Journal journal = null;
            try { journal = ChargeSessionStore.inspectJournal(charge); } catch (IOException unreadable) { journal = null; }
            String path = "charge-recordings/" + charge.getName();
            if (journal == null) charges.add(new Recording(id, path, "", 0, 0, false, Collections.singletonList(path)));
            else {
                // Charge pruning keeps the newest by modification time; date a restored journal by its start.
                if (journal.startedWall > 0) charge.setLastModified(journal.startedWall);
                charges.add(new Recording(id, path, journal.profile, journal.startedWall, journal.startedMono, true, Collections.singletonList(path)));
            }
        }
        Set<String> history = new LinkedHashSet<>();
        File database = new File(data, DATABASE);
        if (database.exists()) { storage.validateDatabase(database); history.addAll(storage.historyProfiles(database)); }
        for (String source : referenced(prefs, profiles, rides, charges, history, new HashSet<>(Arrays.asList(CATEGORIES))))
            if (!sources.containsKey(source)) sources.put(source, new Source(source, "Saved scooter", ""));
        LinkedHashMap<String, Integer> categories = new LinkedHashMap<>();
        for (String category : CATEGORIES) if (present.contains(category)) {
            int count;
            if (category.equals(BackupRegistry.SCOOTERS)) count = profiles.size();
            else if (category.equals(BackupRegistry.DASHBOARDS)) count = perProfile(prefs, category).size();
            else if (category.equals(BackupRegistry.SCOOTER_PREFERENCES)) count = perProfile(prefs, category).size();
            else if (category.equals(BackupRegistry.PREFERENCES)) count = subset(prefs, category).get("app_settings").size();
            else if (category.equals(BackupRegistry.RIDES)) count = rides.size();
            else if (category.equals(BackupRegistry.CHARGES)) count = charges.size();
            else count = database.exists() ? Math.max(1, history.size()) : 0;
            categories.put(category, count);
        }
        return new Prepared(kind, directory, data, created, complete, categories, sources, prefs, profiles, rides, charges, orphans, history, fileCount, totalBytes);
    }
    private static File[] sorted(File[] files) { Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName())); return files; }

    // ---- Plan --------------------------------------------------------------------------------

    /** Backup scooters whose data the chosen categories would place, other than IDs already here. */
    static List<Destination> destinations(Storage storage, Prepared prepared, Collection<String> selected) throws IOException {
        checkStage(storage, prepared);
        Map<String, Map<String, Object>> live = storage.preferences();
        List<BackupProfiles.Profile> local = profiles(live);
        Object active = live.get("profiles") == null ? null : live.get("profiles").get("active");
        List<Destination> result = new ArrayList<>();
        // In the backup's scooter order, so the screen is stable.
        Set<String> referenced = referenced(prepared.preferences, prepared.profiles, prepared.rides, prepared.charges, prepared.history, new HashSet<>(selected));
        List<String> ordered = new ArrayList<>(prepared.sources.keySet()); ordered.retainAll(referenced);
        for (String source : ordered) {
            if (BackupProfiles.find(local, source) != null) continue;
            Source info = prepared.sources.get(source);
            BackupProfiles.Profile item = BackupProfiles.find(prepared.profiles, source);
            BackupProfiles.Profile match = item == null ? new BackupProfiles.Profile(source, info.name, "", 0, "", info.serial, "") : item;
            String matched = null;
            for (BackupProfiles.Profile p : local) if (sameScooter(prepared, p, match)) { matched = p.id; break; }
            // A layout file belongs to no scooter: default to the selected one, as Home's import did.
            if (source.equals(LAYOUT_SOURCE) && !local.isEmpty()) matched = active instanceof String && BackupProfiles.find(local, (String) active) != null ? (String) active : local.get(0).id;
            boolean addable = selected.contains(BackupRegistry.SCOOTERS) && item != null && matched == null;
            result.add(new Destination(source, info.name, matched != null ? matched : addable ? source : null, addable));
        }
        return result;
    }
    /** Compute the complete effect against live data. Staged journals are re-associated here
     * (private copies only), so identity checks run after mapping and repeated imports stay idempotent. */
    static Plan plan(Storage storage, Prepared prepared, Collection<String> selected, boolean replace, Map<String, String> chosen) throws IOException {
        checkStage(storage, prepared); AppDataArchive.ensureNoTransaction(storage);
        Set<String> categories = new LinkedHashSet<>();
        for (String category : CATEGORIES) if (selected.contains(category)) categories.add(category);
        if (categories.isEmpty() || categories.size() != new HashSet<>(selected).size() || !prepared.categories.keySet().containsAll(categories)) throw new IOException("Choose what to restore.");
        if (replace && (!prepared.complete || categories.size() != CATEGORIES.length)) throw new IOException("Replace everything needs a complete backup with every category.");
        Map<String, Map<String, Object>> live = copy(storage.preferences());
        List<String> lines = new ArrayList<>(), additions = new ArrayList<>();
        Map<String, String> mapping = new LinkedHashMap<>(), historyMapping = new LinkedHashMap<>();
        Map<String, Map<String, Object>> result;
        boolean history;
        if (replace) {
            result = replacement(live, prepared.preferences);
            history = true;
            lines.add("Replaces everything on this phone");
            for (Map.Entry<String, Integer> category : prepared.categories.entrySet()) lines.add(BackupRegistry.label(category.getKey()) + ": " + count(category.getKey(), category.getValue()));
        } else {
            // Merges need trustworthy existing identities; complete replacement only snapshots
            // their raw preference values for rollback and does not resolve against them.
            List<BackupProfiles.Profile> local = profiles(live);
            for (String source : referenced(prepared.preferences, prepared.profiles, prepared.rides, prepared.charges, prepared.history, categories)) mapping.put(source, source);
            List<Destination> needed = destinations(storage, prepared, categories);
            for (Destination d : needed) {
                String target = chosen == null ? null : chosen.get(d.source);
                if (target == null) throw new IOException("Choose where each scooter's data goes.");
                if (!target.equals(SKIP) && BackupProfiles.find(local, target) == null && !(d.addable && target.equals(d.source))) throw new IOException("Choose where each scooter's data goes.");
                mapping.put(d.source, target);
            }
            Map<String, Integer> uses = new HashMap<>();
            for (String target : mapping.values()) if (!target.equals(SKIP)) uses.merge(target, 1, Integer::sum);
            result = copy(live);
            Map<String, String> names = new HashMap<>();
            for (BackupProfiles.Profile p : local) names.put(p.id, p.name);
            for (Source s : prepared.sources.values()) names.putIfAbsent(s.id, s.name);
            // Where each chosen scooter's data lands, as the earlier ride restore confirmed; a layout's Dashboards line names it.
            for (Destination d : needed) { String target = mapping.get(d.source); if (!target.equals(SKIP) && !target.equals(d.source) && !d.source.equals(LAYOUT_SOURCE)) lines.add("“" + d.name + "” → " + names.get(target)); }
            if (categories.contains(BackupRegistry.SCOOTERS)) scooters(prepared, live, local, mapping, result, lines);
            if (categories.contains(BackupRegistry.DASHBOARDS)) perScooter(BackupRegistry.DASHBOARDS, prepared, mapping, uses, names, result, lines);
            if (categories.contains(BackupRegistry.PREFERENCES)) {
                Map<String, Map<String, Object>> incoming = subset(prepared.preferences, BackupRegistry.PREFERENCES);
                for (String name : BackupRegistry.NAMESPACES) {
                    result.get(name).keySet().removeIf(key -> BackupRegistry.PREFERENCES.equals(BackupRegistry.category(name, key)));
                    result.get(name).putAll(incoming.get(name));
                }
                lines.add("App preferences: replaces " + plural(count(incoming), "setting"));
            }
            if (categories.contains(BackupRegistry.RIDES)) recordings(storage, prepared.data, prepared.rides, false, mapping, additions, lines, prepared.orphans);
            if (categories.contains(BackupRegistry.CHARGES)) recordings(storage, prepared.data, prepared.charges, true, mapping, additions, lines, null);
            if (categories.contains(BackupRegistry.HISTORY)) {
                Set<String> targets = new LinkedHashSet<>(); int skipped = 0;
                for (String source : prepared.history) { String target = mapping.get(source); if (target == null || target.equals(SKIP)) skipped++; else { historyMapping.put(source, target); targets.add(target); } }
                lines.add(targets.isEmpty() ? "Readings history: nothing to add" : "Readings history: adds to " + plural(targets.size(), "scooter"));
                if (skipped > 0) lines.add("Readings for " + plural(skipped, "scooter") + " skipped · not restored");
            }
            if (categories.contains(BackupRegistry.SCOOTER_PREFERENCES)) perScooter(BackupRegistry.SCOOTER_PREFERENCES, prepared, mapping, uses, names, result, lines);
            history = !historyMapping.isEmpty();
        }
        if ((replace || categories.contains(BackupRegistry.PREFERENCES)) && Boolean.TRUE.equals(result.get("app_settings").get("map_basemap_enabled"))) lines.add("Online map: on");
        Map<String, Map<String, Object>> touched = new LinkedHashMap<>();
        for (String name : BackupRegistry.NAMESPACES) if (!result.get(name).equals(live.get(name))) touched.put(name, result.get(name));
        return new Plan(prepared, categories, replace, mapping, historyMapping, additions, lines, touched, history);
    }
    private static void scooters(Prepared prepared, Map<String, Map<String, Object>> live, List<BackupProfiles.Profile> local, Map<String, String> mapping,
            Map<String, Map<String, Object>> result, List<String> lines) {
        List<BackupProfiles.Profile> added = new ArrayList<>(); int here = 0, skipped = 0;
        for (BackupProfiles.Profile p : prepared.profiles) {
            boolean matched = BackupProfiles.find(local, p.id) != null;
            for (BackupProfiles.Profile mine : local) matched |= sameScooter(prepared, mine, p);
            String target = mapping.get(p.id);
            if (target == null || target.equals(SKIP)) skipped++;
            else if (matched || !target.equals(p.id)) here++;
            else added.add(p);
        }
        Map<String, Object> profiles = result.get("profiles");
        if (!added.isEmpty()) {
            profiles.put("items", BackupProfiles.join(local, added));
            if (local.isEmpty()) {
                Object active = prepared.preferences.get("profiles").get("active");
                profiles.put("active", active instanceof String && BackupProfiles.find(added, (String) active) != null ? active : added.get(0).id);
            }
        }
        // A restored profile list must never let the pre-profile setup migrate a second copy.
        if (!local.isEmpty() || !added.isEmpty()) profiles.put("legacy_migrated", true);
        boolean configured = false;
        for (BackupProfiles.Profile p : local) configured |= p.configured();
        boolean alreadyConfigured = configured;
        for (BackupProfiles.Profile p : added) configured |= p.configured();
        if (configured && (!prepared.kind.equals("owner") || !alreadyConfigured)) { Map<String, Object> onboarding = result.get("onboarding"); onboarding.clear(); onboarding.put(BackupRegistry.OnboardingKeys.DONE, true); }
        lines.add("Scooters: adds " + added.size() + " · " + here + " already here");
        if (skipped > 0) lines.add(plural(skipped, "scooter") + " skipped · not restored");
    }
    private static boolean sameScooter(Prepared prepared, BackupProfiles.Profile mine, BackupProfiles.Profile incoming) {
        if (mine.sameScooter(incoming)) return true;
        // Setup helpers omit hardware identifiers even when the local profile learned them
        // after connecting. Match that same access key and role without replacing any local
        // credential. Full/category archives and owner files with identifiers stay strict.
        return prepared.kind.equals("owner") && incoming.serial.isEmpty()
                && incoming.mac.isEmpty() && mine.role == incoming.role
                && !mine.identity.isEmpty() && mine.identity.equalsIgnoreCase(incoming.identity);
    }
    /** Dashboards or scooter preferences: each mapped scooter's entries are replaced as a set. */
    private static void perScooter(String category, Prepared prepared, Map<String, String> mapping, Map<String, Integer> uses, Map<String, String> names,
            Map<String, Map<String, Object>> result, List<String> lines) {
        Set<String> sources = perProfile(prepared.preferences, category), replaced = new LinkedHashSet<>(), shared = new LinkedHashSet<>(); int skipped = 0;
        for (String source : sources) {
            String target = mapping.get(source);
            if (target == null || target.equals(SKIP)) { skipped++; continue; }
            if (uses.getOrDefault(target, 0) > 1) { shared.add(target); continue; }
            replaced.add(target);
            for (String name : BackupRegistry.NAMESPACES) {
                result.get(name).keySet().removeIf(key -> { BackupRegistry.Rule rule = BackupRegistry.rule(name, key);
                    return rule != null && rule.perProfile && target.equals(rule.profile(key)) && (category.equals(rule.category) || (category.equals(BackupRegistry.DASHBOARDS) && rule.excluded() && rule.key.startsWith("dashboard_"))); });
                for (Map.Entry<String, Object> entry : prepared.preferences.get(name).entrySet()) {
                    BackupRegistry.Rule rule = BackupRegistry.rule(name, entry.getKey());
                    if (rule != null && category.equals(rule.category) && rule.perProfile && source.equals(rule.profile(entry.getKey()))) result.get(name).put(rule.rename(entry.getKey(), target), entry.getValue());
                }
            }
        }
        if (category.equals(BackupRegistry.DASHBOARDS) && !replaced.isEmpty() && prepared.preferences.get("app_settings").containsKey("dashboard_columns"))
            result.get("app_settings").put("dashboard_columns", prepared.preferences.get("app_settings").get("dashboard_columns"));
        List<String> labels = new ArrayList<>(); for (String target : replaced) labels.add(names.getOrDefault(target, "Saved scooter"));
        String label = BackupRegistry.label(category);
        // Name what is replaced, so the line never reads as replacing the scooter itself.
        String what = category.equals(BackupRegistry.DASHBOARDS) ? "layouts" : "settings";
        lines.add(label + ": " + (labels.isEmpty() ? "nothing to replace" : "replaces " + what + " for " + String.join(", ", labels)));
        for (String target : shared) lines.add(label + " kept for " + names.getOrDefault(target, "Saved scooter") + " · two scooters chose it");
        if (skipped > 0) lines.add(label + " for " + plural(skipped, "scooter") + " skipped · not restored");
    }
    /** Add-only by UUID, then by scooter and START clocks; a differing local copy is kept. */
    private static void recordings(Storage storage, File data, List<Recording> staged, boolean charges, Map<String, String> mapping, List<String> additions, List<String> lines, List<String> orphans) throws IOException {
        File liveFolder = new File(storage.files(), charges ? "charge-recordings" : "rides");
        Map<String, String> clocks = new HashMap<>();
        File[] existing = liveFolder.listFiles();
        if (liveFolder.exists() && existing == null) throw new IOException("Saved recordings cannot be read.");
        int kept = 0;
        if (existing != null) for (File file : existing) {
            String key = null;
            if (charges && file.getName().matches("[a-f0-9-]{36}\\.(csv|active)")) kept++;
            if (charges && file.getName().matches(UUID_PATTERN + "\\.(csv|active)")) {
                try { ChargeSessionStore.Journal j = ChargeSessionStore.inspectJournal(file); key = j.profile + "|" + j.startedWall + "|" + j.startedMono; } catch (IOException unreadable) { key = null; }
            } else if (!charges && file.getName().matches(UUID_PATTERN + "\\.csv")) {
                RideStore.Start start = RideStore.start(file); if (start != null) key = start.scooterId + "|" + start.wallMs + "|" + start.monotonicMs;
            }
            if (key != null) clocks.put(key, file.getName().substring(0, 36).toLowerCase(Locale.ROOT));
        }
        int identical = 0, conflicts = 0, notRestored = 0, unreadable = 0;
        List<Recording> candidates = new ArrayList<>();
        for (Recording recording : staged) {
            if (!recording.readable) { unreadable++; continue; }
            String target = mapping.get(recording.profile);
            if (target == null || target.equals(SKIP)) { notRestored++; continue; }
            File journal = AppDataArchive.contained(data, "files/" + recording.path);
            if (!recording.profile.equals(target) || !current(journal, charges).equals(target)) {
                if (charges) ChargeSessionStore.reassignJournal(journal, target); else RideStore.reassignJournal(journal, target);
            }
            boolean exists = false, equal = true;
            List<String> group = charges ? Arrays.asList("charge-recordings/" + recording.id + ".csv", "charge-recordings/" + recording.id + ".active") : RideArchive.paths(recording.id);
            for (String path : group) {
                File mine = new File(storage.files(), path), incoming = new File(data, "files/" + path);
                if (Files.exists(mine.toPath(), LinkOption.NOFOLLOW_LINKS)) { exists = true; if (Files.isSymbolicLink(mine.toPath()) || !mine.isFile()) throw new IOException("Unexpected recording file."); }
                if (mine.exists() != incoming.isFile() || (mine.exists() && (mine.length() != incoming.length() || !Arrays.equals(RideArchive.digest(mine), RideArchive.digest(incoming))))) equal = false;
            }
            if (exists) { if (equal) identical++; else conflicts++; continue; }
            String clock = target + "|" + recording.wall + "|" + recording.mono;
            if (clocks.containsKey(clock)) { conflicts++; continue; }
            clocks.put(clock, recording.id.toLowerCase(Locale.ROOT));
            candidates.add(recording);
        }
        // The charge store prunes above MAX_SESSIONS on its next start: add only the newest that fit, so nothing here is lost.
        int full = 0, room = Math.max(0, ChargeSessionStore.MAX_SESSIONS - kept);
        if (charges && candidates.size() > room) {
            List<Recording> newest = new ArrayList<>(candidates);
            newest.sort((a, b) -> a.wall != b.wall ? Long.compare(b.wall, a.wall) : a.id.compareTo(b.id));
            Set<Recording> fit = new HashSet<>(newest.subList(0, room));
            full = candidates.size() - fit.size(); candidates.retainAll(fit);
        }
        for (Recording recording : candidates) additions.addAll(recording.group);
        String noun = charges ? "charge" : "ride";
        lines.add((charges ? "Charges: " : "Rides: ") + candidates.size() + " new · " + identical + " already here · " + conflicts + " kept here");
        if (full > 0) lines.add(plural(full, noun) + " skipped · " + ChargeSessionStore.MAX_SESSIONS + "-charge limit");
        if (notRestored > 0) lines.add(plural(notRestored, noun) + " skipped · scooter not restored");
        if (unreadable > 0) lines.add(plural(unreadable, noun) + " skipped · unreadable");
        if (orphans != null && !orphans.isEmpty()) lines.add(plural(orphans.size(), "phone file") + " skipped · no ride");
    }
    private static String current(File journal, boolean charge) throws IOException {
        if (charge) return ChargeSessionStore.inspectJournal(journal).profile;
        RideStore.Start start = RideStore.start(journal); if (start == null) throw new IOException("The backup contains an unreadable ride."); return start.scooterId;
    }
    /** Replace everything: backup values for every assigned and unassigned key, this phone's
     * excluded keys kept; a finished setup drops wizard progress and dashboard undo is dropped. */
    static Map<String, Map<String, Object>> replacement(Map<String, Map<String, Object>> live, Map<String, Map<String, Object>> backup) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (String name : BackupRegistry.NAMESPACES) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : live.get(name).entrySet()) {
                BackupRegistry.Rule rule = BackupRegistry.rule(name, entry.getKey());
                if (rule != null && rule.excluded() && !rule.key.equals("dashboard_restore_undo_v1_")) values.put(entry.getKey(), entry.getValue());
            }
            for (Map.Entry<String, Object> entry : backup.get(name).entrySet()) {
                BackupRegistry.Rule rule = BackupRegistry.rule(name, entry.getKey());
                if (rule == null || !rule.excluded()) values.put(entry.getKey(), copyValue(entry.getValue()));
            }
            result.put(name, values);
        }
        if (Boolean.TRUE.equals(result.get("onboarding").get(BackupRegistry.OnboardingKeys.DONE))) result.get("onboarding").keySet().removeIf(key -> !key.equals(BackupRegistry.OnboardingKeys.DONE));
        return result;
    }
    /** Every backup scooter referenced by the given categories, in a stable order. */
    private static Set<String> referenced(Map<String, Map<String, Object>> prefs, List<BackupProfiles.Profile> profiles, List<Recording> rides, List<Recording> charges, Set<String> history, Set<String> categories) {
        Set<String> result = new LinkedHashSet<>();
        if (categories.contains(BackupRegistry.SCOOTERS)) for (BackupProfiles.Profile profile : profiles) result.add(profile.id);
        for (String category : new String[]{BackupRegistry.DASHBOARDS, BackupRegistry.SCOOTER_PREFERENCES}) if (categories.contains(category)) result.addAll(perProfile(prefs, category));
        if (categories.contains(BackupRegistry.RIDES)) for (Recording r : rides) if (r.readable) result.add(r.profile);
        if (categories.contains(BackupRegistry.CHARGES)) for (Recording r : charges) if (r.readable) result.add(r.profile);
        if (categories.contains(BackupRegistry.HISTORY)) result.addAll(history);
        return result;
    }

    // ---- Helpers -----------------------------------------------------------------------------

    static void checkStage(Storage storage, Prepared prepared) throws IOException {
        if (prepared == null || prepared.consumed || !AppDataArchive.isStage(storage, prepared.directory) || !prepared.data.isDirectory()) throw new IOException("Choose the backup again.");
    }
    static List<BackupProfiles.Profile> profiles(Map<String, Map<String, Object>> prefs) throws IOException {
        Map<String, Object> profiles = prefs.get("profiles");
        try { return BackupProfiles.parse(profiles == null ? null : profiles.get("items")); }
        catch (IOException unreadable) { throw new IOException("Saved scooters on this phone can't be read.", unreadable); }
    }
    /** Keys of one category, by namespace; every namespace is present. */
    static Map<String, Map<String, Object>> subset(Map<String, Map<String, Object>> prefs, String category) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (String name : BackupRegistry.NAMESPACES) {
            Map<String, Object> values = new LinkedHashMap<>();
            Map<String, Object> source = prefs.get(name);
            if (source != null) for (Map.Entry<String, Object> entry : source.entrySet()) if (category.equals(BackupRegistry.category(name, entry.getKey()))) values.put(entry.getKey(), copyValue(entry.getValue()));
            result.put(name, values);
        }
        return result;
    }
    private static Map<String, Map<String, Object>> unassigned(Map<String, Map<String, Object>> prefs) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (String name : BackupRegistry.NAMESPACES) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : prefs.get(name).entrySet()) if (BackupRegistry.rule(name, entry.getKey()) == null) values.put(entry.getKey(), copyValue(entry.getValue()));
            result.put(name, values);
        }
        return result;
    }
    /** Scooter IDs with entries of a per-scooter category. Home's no-scooter layout is not a
     * scooter: it is never counted, mapped or merged, and travels only with Replace everything. */
    private static Set<String> perProfile(Map<String, Map<String, Object>> prefs, String category) {
        Set<String> result = new LinkedHashSet<>();
        for (String name : BackupRegistry.NAMESPACES) if (prefs.get(name) != null) for (String key : prefs.get(name).keySet()) {
            BackupRegistry.Rule rule = BackupRegistry.rule(name, key);
            if (rule != null && category.equals(rule.category) && rule.perProfile && rule.profile(key) != null) result.add(rule.profile(key));
        }
        result.remove(NO_SCOOTER);
        return result;
    }
    private static byte[] encode(Map<String, Map<String, Object>> values) throws IOException {
        List<String> names = new ArrayList<>(); for (Map.Entry<String, Map<String, Object>> entry : values.entrySet()) if (!entry.getValue().isEmpty()) names.add(entry.getKey());
        return AppDataArchive.encodePreferences(values, names);
    }
    static int count(Map<String, Map<String, Object>> values) { int n = 0; for (Map<String, Object> v : values.values()) n += v.size(); return n; }
    static Map<String, Map<String, Object>> copy(Map<String, Map<String, Object>> prefs) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (String name : BackupRegistry.NAMESPACES) { Map<String, Object> values = new LinkedHashMap<>(); Map<String, Object> source = prefs.get(name);
            if (source != null) for (Map.Entry<String, Object> e : source.entrySet()) values.put(e.getKey(), copyValue(e.getValue())); result.put(name, values); }
        return result;
    }
    private static Object copyValue(Object value) { return value instanceof Set ? new LinkedHashSet<>((Set<?>) value) : value; }
    private static String count(String category, int items) {
        if (category.equals(BackupRegistry.SCOOTERS) || category.equals(BackupRegistry.DASHBOARDS) || category.equals(BackupRegistry.SCOOTER_PREFERENCES) || category.equals(BackupRegistry.HISTORY)) return plural(items, "scooter");
        if (category.equals(BackupRegistry.PREFERENCES)) return plural(items, "setting");
        return plural(items, category.equals(BackupRegistry.RIDES) ? "ride" : "charge");
    }
    static String plural(int count, String noun) { return count + " " + noun + (count == 1 ? "" : "s"); }
    private static String bounded(String value, int max) { return value.length() <= max ? value : value.substring(0, max); }
}
