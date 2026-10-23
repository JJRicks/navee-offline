package com.jjricks.volt;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Fault injection and process death at every restore boundary, rollback and startup recovery. */
public final class BackupTransactionTest {
    private static int checks;
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private interface Action { void run() throws Exception; }
    private static void rejects(Action action, String message) throws Exception { checks++; try { action.run(); throw new AssertionError(message); } catch (IOException expected) { } }
    private static final String C = BackupArchiveTest.C;

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("volt-backup-transaction-").toFile();
        try {
            BackupTestStore source = BackupTestStore.rich(new File(root, "source"));
            byte[] complete = source.export(BackupTestStore.all());
            BackupTestStore template = BackupArchiveTest.phone(new File(root, "template"));
            BackupTestStore probe = copy(template, new File(root, "probe"));
            probe.restore(complete, BackupTestStore.all(), false, BackupArchive.SKIP);
            List<String> boundaries = new ArrayList<>();
            for (String event : probe.events) if (!event.startsWith("sync:") && !event.startsWith("prefs:")) boundaries.add(event);
            check(boundaries.indexOf("snapshot") < boundaries.indexOf("active") && boundaries.indexOf("active") < boundaries.indexOf("database") && boundaries.indexOf("live-sync") < boundaries.indexOf("commit") && boundaries.get(0).equals("build"), "boundaries run in order: " + boundaries);
            int moves = 0; for (String b : boundaries) if (b.startsWith("move:")) moves++;
            check(moves == 9, "every added file is its own boundary: " + boundaries);
            for (String mode : new String[]{"merge", "replace"}) {
                List<String> points = new ArrayList<>(boundaries);
                if (mode.equals("replace")) { BackupTestStore replace = copy(template, new File(root, "probe-replace")); replace.restore(complete, BackupTestStore.all(), true, null);
                    points.clear(); for (String event : replace.events) if (!event.startsWith("sync:") && !event.startsWith("prefs:")) points.add(event); }
                for (String point : points) {
                    if (point.equals("cleanup")) continue;
                    fault(root, template, complete, mode, point, false);
                    fault(root, template, complete, mode, point, true);
                }
                Set<String> touched = plan(copy(template, new File(root, "touched-" + mode)), complete, mode).preferences.keySet();
                check(touched.size() >= (mode.equals("replace") ? 4 : 2), mode + " touches several namespaces: " + touched);
                for (String namespace : touched) { prefsFault(root, template, complete, mode, namespace, false); prefsFault(root, template, complete, mode, namespace, true); }
            }
            cleanupFailure(root, template, complete);
            failingRollback(root, template, complete);
            syncFailure(root, template, complete);
            legacyTransaction(root, template, complete);
            lowStorage(root, template, complete);
            changedAfterReview(root, template, complete);
            cancel(root, template, complete);
            unrelatedFile(root, template, complete);
            System.out.println("BackupTransactionTest: " + checks + " checks passed (failure and process death at every boundary, rollback, repeated and v1 recovery, low storage, cancel)");
        } finally { AppDataArchive.deleteTree(root); }
    }

    static BackupTestStore copy(BackupTestStore template, File to) throws IOException { BackupArchiveTest.copyTree(template.root, to); BackupTestStore s = new BackupTestStore(to); s.fast = true; return s; }
    private static int counter;
    private static Map<String, Object> state(BackupTestStore store) throws IOException { Map<String, Object> r = new LinkedHashMap<>(); r.put("prefs", store.preferences()); r.put("files", store.fileHashes()); return r; }
    private static BackupArchive.Plan plan(BackupTestStore store, byte[] bytes, String mode) throws IOException {
        return store.plan(store.stage(bytes), BackupTestStore.all(), mode.equals("replace"), BackupArchive.SKIP);
    }

    private static void fault(File root, BackupTestStore template, byte[] complete, String mode, String point, boolean crash) throws Exception {
        BackupTestStore store = copy(template, new File(root, "fault-" + (counter++)));
        Map<String, Object> before = state(store);
        BackupArchive.Plan plan = plan(store, complete, mode);
        if (crash) store.crashStep = point; else store.failStep = point;
        boolean committed = point.equals("cleanup");
        try { BackupTransaction.apply(store, plan); check(committed, mode + " " + point + " fault must not report success"); }
        catch (IOException failure) { check(!crash, mode + " failure at " + point + " is reported"); check(state(store).equals(before), mode + " failure at " + point + " rolls back to the previous data"); check(!new File(store.work(), "transaction").exists(), mode + " failure at " + point + " leaves no transaction");
            BackupArchive.discard(store, plan.prepared); check(!BackupTransaction.needsRecovery(store), mode + " failure at " + point + " leaves no private data once dismissed"); }
        catch (BackupTestStore.Crash death) {
            check(crash, "crash only when injected");
            // A new process: recovery runs before any store opens.
            BackupTestStore reopened = new BackupTestStore(store.root);
            boolean rolledBack = BackupTransaction.recover(reopened);
            check(rolledBack == (!point.equals("build") && !point.equals("snapshot")), mode + " recovery after death at " + point + " reports a rollback only once live data may have changed");
            check(state(reopened).equals(before), mode + " process death at " + point + " recovers the previous data");
            check(!BackupTransaction.recover(reopened) && !BackupTransaction.needsRecovery(reopened), mode + " recovery after " + point + " is complete and repeatable");
        }
    }
    private static void prefsFault(File root, BackupTestStore template, byte[] complete, String mode, String namespace, boolean crash) throws Exception {
        BackupTestStore store = copy(template, new File(root, "prefs-" + (counter++)));
        Map<String, Object> before = state(store);
        BackupArchive.Plan plan = plan(store, complete, mode);
        check(plan.preferences.containsKey(namespace), mode + " plan touches " + namespace);
        if (crash) store.crashPrefsAt = namespace; else store.failPrefsAt = namespace;
        try { BackupTransaction.apply(store, plan); throw new AssertionError("preference fault must fail"); }
        catch (IOException failure) { check(!crash && state(store).equals(before), mode + " failed " + namespace + " commit rolls every namespace and file back"); }
        catch (BackupTestStore.Crash death) {
            BackupTestStore reopened = new BackupTestStore(store.root);
            check(crash && BackupTransaction.recover(reopened) && state(reopened).equals(before), mode + " death after committing " + namespace + " recovers every namespace");
        }
    }
    private static void cleanupFailure(File root, BackupTestStore template, byte[] complete) throws Exception {
        BackupTestStore store = copy(template, new File(root, "cleanup"));
        BackupArchive.Plan plan = plan(store, complete, "merge"); store.failStep = "cleanup";
        BackupTransaction.apply(store, plan);
        Map<String, Object> after = state(store);
        check(BackupTransaction.needsRecovery(store) && new File(store.work(), "transaction/state").isFile(), "a committed restore keeps its record if cleanup fails");
        check(!BackupTransaction.recover(store) && state(store).equals(after) && !BackupTransaction.needsRecovery(store), "startup removes a committed transaction without rolling back");
    }
    private static void failingRollback(File root, BackupTestStore template, byte[] complete) throws Exception {
        BackupTestStore store = copy(template, new File(root, "failing-rollback"));
        Map<String, Object> before = state(store);
        BackupArchive.Plan plan = plan(store, complete, "merge"); store.failPrefsAlways = true;
        rejects(() -> BackupTransaction.apply(store, plan), "failed apply with failed rollback is reported");
        check(new File(store.work(), "transaction/state").isFile(), "a failed rollback keeps the snapshot");
        rejects(() -> BackupTransaction.recover(store), "recovery fails while the fault persists");
        check(new File(store.work(), "transaction/previous/prefs.bin").isFile(), "a failed recovery keeps the snapshot");
        rejects(() -> BackupArchive.stage(store, new ByteArrayInputStream(complete), BackupTestStore.PASSWORD), "no new restore starts before recovery");
        store.failPrefsAlways = false;
        check(BackupTransaction.recover(store) && state(store).equals(before), "a later startup finishes the rollback");
    }
    private static void syncFailure(File root, BackupTestStore template, byte[] complete) throws Exception {
        for (String folder : new String[]{"no_backup/app-data-transfer/transaction", "files/rides", "databases", "files"}) {
            BackupTestStore store = copy(template, new File(root, "sync-" + (counter++)));
            Map<String, Object> before = state(store);
            BackupArchive.Plan plan = plan(store, complete, "merge"); store.failSync = folder;
            rejects(() -> BackupTransaction.apply(store, plan), "a failed directory sync of " + folder + " is never success");
            BackupArchive.discard(store, plan.prepared);
            check(state(store).equals(before) && !BackupTransaction.needsRecovery(store), "a failed directory sync of " + folder + " rolls back");
        }
    }
    /** A transaction left by Volt 1.3.1 (no scope) is still rolled back by the version 1 rules. */
    private static void legacyTransaction(File root, BackupTestStore template, byte[] complete) throws Exception {
        BackupTestStore store = copy(template, new File(root, "legacy-transaction"));
        Map<String, Object> before = state(store);
        File fixture = new File("tests/fixtures/legacy/full-v1.voltbackup");
        AppDataArchive.Prepared v1;
        try (InputStream in = new FileInputStream(fixture)) { v1 = AppDataArchive.stage(store, in, "synthetic fixture password".toCharArray()); }
        store.crashPrefsAt = "app_settings";
        try { AppDataArchive.apply(store, v1); throw new AssertionError("crash expected"); } catch (BackupTestStore.Crash expected) { }
        check(new File(store.work(), "transaction/state").isFile() && !new File(store.work(), "transaction/scope.bin").exists(), "an interrupted 1.3.1 restore left its transaction");
        BackupTestStore reopened = new BackupTestStore(store.root);
        check(BackupTransaction.recover(reopened) && state(reopened).equals(before) && !BackupTransaction.needsRecovery(reopened), "a version 1 transaction is rolled back over every root and namespace");
        File stray = new File(store.work(), "transaction/scope.bin"); stray.getParentFile().mkdirs(); Files.write(stray.toPath(), new byte[]{1});
        rejects(() -> AppDataArchive.recover(store), "version 1 recovery never interprets a category transaction");
        AppDataArchive.deleteTree(stray.getParentFile());
    }
    private static void lowStorage(File root, BackupTestStore template, byte[] complete) throws Exception {
        BackupTestStore store = copy(template, new File(root, "low-storage"));
        Map<String, Object> before = state(store);
        BackupArchive.Prepared prepared = store.stage(complete);
        BackupArchive.Plan plan = store.plan(prepared, BackupTestStore.all(), false, BackupArchive.SKIP);
        store.usable = BackupTransaction.MARGIN;
        try { BackupTransaction.apply(store, plan); throw new AssertionError("low storage must refuse"); }
        catch (IOException refused) { check(refused.getMessage().equals("Not enough free storage"), "low storage is refused with a short message"); }
        check(state(store).equals(before) && !prepared.consumed && !new File(store.work(), "transaction").exists(), "low storage is refused before any mutation, keeping the reviewed file");
        store.usable = Long.MAX_VALUE; BackupTransaction.apply(store, plan);
        check(!state(store).equals(before), "the same review applies once space is free");
    }
    private static void changedAfterReview(File root, BackupTestStore template, byte[] complete) throws Exception {
        BackupTestStore store = copy(template, new File(root, "changed"));
        BackupArchive.Plan plan = plan(store, complete, "merge");
        store.put("app_settings", "last_page", "stats");
        try { BackupTransaction.apply(store, plan); throw new AssertionError("changed data must refuse"); }
        catch (IOException refused) { check(refused.getMessage().equals("Saved data changed. Review again."), "data changed since the preview is refused"); }
        check("dark".equals(store.preferences().get("app_settings").get("theme")), "nothing applied after a stale review");
        rejects(() -> BackupTransaction.apply(store, null), "no restore without a review");
    }
    private static void cancel(File root, BackupTestStore template, byte[] complete) throws Exception {
        BackupTestStore store = copy(template, new File(root, "cancel"));
        BackupArchive.Prepared prepared = store.stage(complete);
        check(BackupTransaction.needsRecovery(store), "decrypted staging is private recovery data");
        BackupArchive.discard(store, prepared);
        check(!prepared.directory.exists() && !BackupTransaction.needsRecovery(store), "cancel removes decrypted staging");
        rejects(() -> store.plan(prepared, BackupTestStore.all(), false, BackupArchive.SKIP), "a cancelled file cannot be reviewed");
        BackupArchive.Prepared orphan = store.stage(complete);
        check(!BackupTransaction.recover(store) && !orphan.directory.exists(), "startup removes staging left by process death");
        BackupArchive.Plan plan = plan(store, complete, "merge"); BackupTransaction.apply(store, plan);
        rejects(() -> BackupTransaction.apply(store, plan), "an applied review cannot replay");
        File unexpected = new File(store.work(), "surprise"); store.work().mkdirs(); Files.write(unexpected.toPath(), new byte[]{1});
        rejects(() -> BackupTransaction.recover(store), "unexpected recovery files block startup");
        unexpected.delete();
    }
    /** Rollback removes only files whose identity matches what the restore installed. */
    private static void unrelatedFile(File root, BackupTestStore template, byte[] complete) throws Exception {
        BackupTestStore store = copy(template, new File(root, "unrelated"));
        BackupArchive.Plan plan = plan(store, complete, "merge");
        String first = plan.additions.get(0);
        store.crashStep = "move:" + first;
        try { BackupTransaction.apply(store, plan); throw new AssertionError("crash expected"); } catch (BackupTestStore.Crash expected) { }
        File installed = new File(store.files(), first);
        check(installed.isFile(), "the first addition was installed before the crash");
        // A different file (new identity) now holds that name, as if another writer created it.
        File other = new File(installed.getPath() + ".other"); Files.write(other.toPath(), "unrelated file".getBytes("UTF-8"));
        Files.move(other.toPath(), installed.toPath(), StandardCopyOption.REPLACE_EXISTING);
        BackupTestStore reopened = new BackupTestStore(store.root);
        check(BackupTransaction.recover(reopened) && new String(Files.readAllBytes(installed.toPath()), "UTF-8").equals("unrelated file"), "an unrelated file at an added path survives rollback");
    }
}
