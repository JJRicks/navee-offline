package com.jjricks.volt;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * One crash-safe transaction for every restore: build the incoming data in private staging,
 * check free space, snapshot only what the plan touches, then move, swap and commit. Startup
 * rolls back an active transaction before any store opens, and recovery is repeatable.
 * Layout in `no_backup/app-data-transfer/transaction`: scope.bin, additions.bin, previous/,
 * incoming/ and the state marker. A transaction without scope.bin is a version 1 full restore.
 */
final class BackupTransaction {
    static final long MARGIN = 64L * 1024 * 1024;
    private static final String[] FOLDERS = {"rides", "phone-rides", "charge-recordings"};
    private BackupTransaction() { }

    static void apply(BackupArchive.Storage storage, BackupArchive.Plan approved) throws IOException {
        if (approved == null) throw new IOException("Review the backup first.");
        BackupArchive.Prepared prepared = approved.prepared;
        BackupArchive.Plan current = BackupArchive.plan(storage, prepared, approved.categories, approved.replace, approved.mapping);
        if (!current.same(approved)) throw new IOException("Saved data changed. Review again.");
        AppDataArchive.checkDatabaseClosed(storage);
        File database = storage.database(), incomingDatabase = new File(prepared.data, "database/" + AppDataArchive.DATABASE);
        boolean databaseExisted = database.isFile(), history = approved.history;
        List<String> roots = approved.replace ? Arrays.asList(FOLDERS) : Collections.<String>emptyList();
        Map<String, Map<String, Object>> live = storage.preferences(), previousPrefs = new LinkedHashMap<>();
        for (String name : approved.preferences.keySet()) previousPrefs.put(name, live.get(name));
        byte[] previousBytes = AppDataArchive.encodePreferences(previousPrefs, previousPrefs.keySet());
        // Free space for the merged copy and the rollback snapshot, checked before any write.
        long need = MARGIN + 2L * previousBytes.length;
        if (history && databaseExisted) need += database.length() * (approved.replace ? 1 : 2);
        if (history && !approved.replace && incomingDatabase.isFile()) need += incomingDatabase.length();
        for (String root : roots) need += size(new File(storage.files(), root));
        if (storage.usableSpace(storage.work()) < need) throw new IOException("Not enough free storage");
        File merged = new File(prepared.directory, "merged.db");
        try {
            if (history && !approved.replace) { storage.mergeHistory(databaseExisted ? database : null, incomingDatabase, approved.historyMapping, merged); storage.validateDatabase(merged); }
            writeFile(new File(prepared.directory, "prefs.bin"), AppDataArchive.encodePreferences(approved.preferences, approved.preferences.keySet()));
            storage.step("build");
        } catch (Exception failure) {
            try { AppDataArchive.deleteTree(merged); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw io(failure, "The restore could not be prepared.");
        }
        File transaction = new File(storage.work(), "transaction"), previous = new File(transaction, "previous"), incoming = new File(transaction, "incoming");
        AppDataArchive.ensureNoTransaction(storage);
        AppDataArchive.mkdir(transaction);
        boolean active = false;
        try {
            try (DataOutputStream out = new DataOutputStream(new FileOutputStream(new File(transaction, "scope.bin")))) {
                out.writeInt(2); out.writeBoolean(approved.replace); out.writeBoolean(history); out.writeBoolean(databaseExisted);
                out.writeInt(roots.size()); for (String root : roots) out.writeUTF(root);
                out.writeInt(previousPrefs.size()); for (String name : previousPrefs.keySet()) out.writeUTF(name);
                out.flush();
            }
            sync(new File(transaction, "scope.bin"));
            AppDataArchive.mkdir(previous);
            writeFile(new File(previous, "prefs.bin"), previousBytes);
            if (history && databaseExisted) AppDataArchive.copyFile(database, new File(previous, "database/" + AppDataArchive.DATABASE), BackupArchive.HISTORY_BYTES);
            for (String root : roots) {
                File from = new File(storage.files(), root), to = new File(previous, "files/" + root); AppDataArchive.mkdir(to);
                File[] children = from.listFiles();
                if (from.exists() && children == null) throw new IOException("A recording folder could not be read.");
                if (children != null) for (File child : children) AppDataArchive.copyFile(child, new File(to, child.getName()), AppDataArchive.MAX_FILE_BYTES);
            }
            File data = new File(prepared.directory, "data");
            try (DataOutputStream out = new DataOutputStream(new FileOutputStream(new File(transaction, "additions.bin")))) {
                out.writeInt(approved.additions.size());
                for (String path : approved.additions) { out.writeUTF(path); out.writeUTF(RideArchive.identity(AppDataArchive.contained(data, "files/" + path))); }
            }
            sync(new File(transaction, "additions.bin"));
            if (!prepared.directory.renameTo(incoming)) throw new IOException("Could not prepare the restore.");
            prepared.consumed = true;
            // The whole snapshot and its parent chain are durable before live writes are allowed.
            AppDataArchive.syncDirectories(storage, transaction);
            storage.syncDirectory(storage.work()); storage.syncDirectory(storage.work().getParentFile()); storage.syncDirectory(storage.work().getParentFile().getParentFile());
            storage.step("snapshot");
            state(storage, transaction, "active"); active = true;
            storage.step("active");
            data = new File(incoming, "data");
            for (String path : approved.additions) {
                File target = new File(storage.files(), path), source = AppDataArchive.contained(data, "files/" + path);
                AppDataArchive.mkdir(target.getParentFile());
                String owned = RideArchive.identity(source);
                // Same filesystem; never replaces an existing name, and the file keeps its identity.
                Files.move(source.toPath(), target.toPath());
                if (!owned.equals(RideArchive.identity(target))) throw new IOException("Recording storage changed during restore.");
                storage.step("move:" + path);
            }
            for (String root : roots) {
                File target = new File(storage.files(), root), source = new File(data, "files/" + root);
                AppDataArchive.deleteTree(target);
                if (source.isDirectory()) Files.move(source.toPath(), target.toPath()); else AppDataArchive.mkdir(target);
                storage.step("root:" + root);
            }
            if (history) {
                for (String suffix : new String[]{"-journal", "-wal", "-shm"}) AppDataArchive.deleteTree(new File(database.getPath() + suffix));
                File replacement = approved.replace ? new File(data, "database/" + AppDataArchive.DATABASE) : new File(incoming, "merged.db");
                if (replacement.isFile()) { AppDataArchive.mkdir(database.getParentFile()); Files.move(replacement.toPath(), database.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                else AppDataArchive.deleteTree(database);
                storage.step("database");
            }
            if (!approved.preferences.isEmpty()) storage.preferences(approved.preferences);
            storage.step("preferences");
            syncLive(storage);
            storage.step("live-sync");
            storage.step("commit");
            state(storage, transaction, "committed");
        } catch (Exception failure) {
            if (active) {
                try { rollback(storage, transaction); }
                catch (Exception rollback) { failure.addSuppressed(rollback); throw new IOException("Restore failed. Reopen Volt. Don't clear storage.", failure); }
            } else try { AppDataArchive.deleteTree(transaction); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw io(failure, "The restore failed; your data was kept.");
        }
        // A committed restore stays committed if removing its private recovery copy fails; startup cleans it.
        try { storage.step("cleanup"); AppDataArchive.deleteTree(transaction); } catch (Exception ignored) { }
    }

    static boolean needsRecovery(BackupArchive.Storage storage) {
        File work = storage.work(); if (!work.exists()) return false;
        File[] files = work.listFiles(); return files == null || files.length > 0;
    }
    /** Run before any model, service, preference listener or database opens. True if a restore was rolled back. */
    static boolean recover(BackupArchive.Storage storage) throws IOException {
        File work = storage.work();
        if (!work.exists()) return false;
        if (!work.isDirectory() || Files.isSymbolicLink(work.toPath()) || work.listFiles() == null) throw new IOException("The restore recovery folder is unavailable. Keep app storage intact.");
        File transaction = new File(work, "transaction");
        // Version 1 full restores (and a category restore that died before its scope existed).
        if (transaction.exists() && !new File(transaction, "scope.bin").exists()) return AppDataArchive.recover(storage);
        boolean recovered = false;
        if (transaction.exists()) {
            if (!transaction.isDirectory() || Files.isSymbolicLink(transaction.toPath())) throw new IOException("The restore recovery record is unavailable. Keep app storage intact.");
            File marker = new File(transaction, "state");
            if (!marker.exists()) AppDataArchive.deleteTree(transaction); // Live data was never touched.
            else {
                String value = new String(AppDataArchive.readFile(marker, 32), StandardCharsets.UTF_8);
                if (value.equals("committed")) AppDataArchive.deleteTree(transaction);
                else if (value.equals("active")) { rollback(storage, transaction); recovered = true; }
                else throw new IOException("The restore recovery record is unreadable. Keep app storage intact.");
            }
        }
        File[] children = work.listFiles();
        if (children == null) throw new IOException("The restore recovery folder cannot be read. Keep app storage intact.");
        for (File file : children) {
            if (file.getName().matches("stage-[a-f0-9-]{36}")) AppDataArchive.deleteTree(file);
            else throw new IOException("An unexpected restore recovery file needs attention. Keep app storage intact.");
        }
        return recovered;
    }

    private static void rollback(BackupArchive.Storage storage, File transaction) throws IOException {
        boolean replace, history, databaseExisted; List<String> roots = new ArrayList<>(), namespaces = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(AppDataArchive.readFile(new File(transaction, "scope.bin"), 4096)))) {
            if (in.readInt() != 2) throw new IOException("The restore recovery record is unreadable. Keep app storage intact.");
            replace = in.readBoolean(); history = in.readBoolean(); databaseExisted = in.readBoolean();
            int count = in.readInt(); if (count < 0 || count > FOLDERS.length) throw new IOException("The restore recovery record is unreadable.");
            for (int i = 0; i < count; i++) { String root = in.readUTF(); if (!Arrays.asList(FOLDERS).contains(root) || roots.contains(root)) throw new IOException("The restore recovery record is unreadable."); roots.add(root); }
            count = in.readInt(); if (count < 0 || count > BackupRegistry.NAMESPACES.length) throw new IOException("The restore recovery record is unreadable.");
            for (int i = 0; i < count; i++) { String name = in.readUTF(); if (!BackupRegistry.namespace(name) || namespaces.contains(name)) throw new IOException("The restore recovery record is unreadable."); namespaces.add(name); }
            if (in.read() != -1) throw new IOException("The restore recovery record is unreadable.");
        }
        if (replace && roots.size() != FOLDERS.length) throw new IOException("The restore recovery record is unreadable.");
        File previous = new File(transaction, "previous");
        Map<String, Map<String, Object>> prefs = AppDataArchive.decodePreferences(AppDataArchive.readFile(new File(previous, "prefs.bin"), AppDataArchive.MAX_PREFERENCES), true);
        if (!new ArrayList<>(prefs.keySet()).equals(namespaces)) throw new IOException("The restore recovery record is unreadable.");
        LinkedHashMap<String, String> additions = new LinkedHashMap<>();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(AppDataArchive.readFile(new File(transaction, "additions.bin"), 16 * 1024 * 1024)))) {
            int count = in.readInt(); if (count < 0 || count > AppDataArchive.MAX_ENTRIES) throw new IOException("Invalid restore recovery list.");
            for (int i = 0; i < count; i++) {
                String path = in.readUTF(), owned = in.readUTF();
                if (!AppDataArchive.allowedFile("files/" + path) || owned.isEmpty() || owned.length() > 512 || additions.put(path, owned) != null) throw new IOException("Invalid restore recovery path.");
            }
            if (in.read() != -1) throw new IOException("Invalid restore recovery list.");
        }
        // A committed rename can succeed before its directory sync fails: re-arm before any rollback write.
        state(storage, transaction, "active");
        // Discard staged new data first so recovery can reclaim space for the old data.
        AppDataArchive.deleteTree(new File(transaction, "incoming"));
        List<String> reversed = new ArrayList<>(additions.keySet()); Collections.reverse(reversed);
        for (String path : reversed) {
            File file = AppDataArchive.contained(storage.files(), path);
            // Only a file this restore installed is removed; anything unrelated at that name stays.
            if (file.exists() && additions.get(path).equals(RideArchive.identity(file)) && !file.delete()) throw new IOException("Could not roll back an interrupted restore.");
        }
        for (String root : roots) {
            File target = new File(storage.files(), root), source = new File(previous, "files/" + root);
            AppDataArchive.deleteTree(target); AppDataArchive.mkdir(target);
            File[] children = source.listFiles();
            if (children == null) throw new IOException("The restore snapshot is incomplete. Keep app storage intact.");
            for (File child : children) AppDataArchive.copyFile(child, new File(target, child.getName()), AppDataArchive.MAX_FILE_BYTES);
        }
        if (history) {
            File database = storage.database();
            for (String suffix : new String[]{"", "-journal", "-wal", "-shm"}) AppDataArchive.deleteTree(new File(database.getPath() + suffix));
            if (databaseExisted) AppDataArchive.copyFile(new File(previous, "database/" + AppDataArchive.DATABASE), database, BackupArchive.HISTORY_BYTES);
        }
        if (!prefs.isEmpty()) storage.preferences(prefs);
        syncLive(storage);
        state(storage, transaction, "committed");
        AppDataArchive.deleteTree(transaction);
    }
    /** File fsync alone does not persist names; live folders are durable before `committed`. */
    private static void syncLive(BackupArchive.Storage storage) throws IOException {
        for (String folder : FOLDERS) { File file = new File(storage.files(), folder); if (file.exists()) storage.syncDirectory(file); }
        storage.syncDirectory(storage.files());
        File databases = storage.database().getParentFile(); if (databases.exists()) storage.syncDirectory(databases);
        storage.syncDirectory(storage.files().getParentFile());
    }
    private static void state(BackupArchive.Storage storage, File transaction, String value) throws IOException {
        File temporary = new File(transaction, "state.tmp");
        try (FileOutputStream out = new FileOutputStream(temporary)) { out.write(value.getBytes(StandardCharsets.UTF_8)); out.getFD().sync(); }
        if (!temporary.renameTo(new File(transaction, "state"))) throw new IOException("Could not save restore recovery state.");
        storage.syncDirectory(transaction);
    }
    private static void writeFile(File file, byte[] bytes) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes); out.getFD().sync(); } finally { Arrays.fill(bytes, (byte) 0); }
    }
    private static void sync(File file) throws IOException { try (FileOutputStream out = new FileOutputStream(file, true)) { out.getFD().sync(); } }
    private static long size(File folder) { long total = 0; File[] children = folder.listFiles(); if (children != null) for (File child : children) total += child.length(); return total; }
    private static IOException io(Exception failure, String message) { return failure instanceof IOException ? (IOException) failure : new IOException(message, failure); }
}
