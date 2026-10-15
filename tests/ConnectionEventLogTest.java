package local.navee.offlinelock;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class ConnectionEventLogTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            ConnectionEventLog log = ConnectionEventLog.open(new File(args[0]));
            log.flush().get(10, TimeUnit.SECONDS);
            require(log.loaded(), "new process loaded journal");
            require(log.snapshot().size() == Integer.parseInt(args[1]), "persisted count survived process restart");
            if (args.length > 2) require(log.snapshot().get(log.snapshot().size() - 1).elapsedMs
                    == Long.parseLong(args[2]), "last event survived process restart");
            for (ConnectionEventLog.Record record : log.snapshot())
                require(record.reason == ConnectionEventLog.Reason.NATIVE_DISCONNECT && record.statusCode == 8
                        && record.recording && !record.visible && record.phase == ConnectionEventLog.Phase.READY
                        && record.statusAgeMs == 1000 && record.frameAgeMs == 500, "persisted context round-trips");
            return;
        }
        boundedAndRestart();
        oldAndNewControllerShareOneWriter();
        corruptionAndOversizeRecover();
        failuresDoNotLoseMemoryOrCrash();
        typedPrivacyBoundary();
        System.out.println("ConnectionEventLog tests passed: " + checks + " assertions");
    }
    private static ConnectionEventLog.Record event(long time) {
        return new ConnectionEventLog.Record(1792026000000L + time, time,
                ConnectionEventLog.Reason.NATIVE_DISCONNECT, 8, false, true,
                ConnectionEventLog.Phase.READY, 1000, 500);
    }
    private static void boundedAndRestart() throws Exception {
        File path = Files.createTempDirectory("volt-connection-bounded").resolve("events").toFile();
        ConnectionEventLog log = ConnectionEventLog.open(path);
        for (int i = 0; i < 1000; i++) log.record(event(i));
        log.flush().get(10, TimeUnit.SECONDS);
        List<ConnectionEventLog.Record> records = log.snapshot();
        require(log.loaded() && log.storageHealthy(), "asynchronous load and disk write completed");
        require(records.size() == ConnectionEventLog.MAX_RECORDS, "record count is bounded");
        require(records.get(0).elapsedMs == 800 && records.get(199).elapsedMs == 999,
                "rotation retains latest events in insertion order");
        require(path.length() <= ConnectionEventLog.MAX_FILE_BYTES, "file size is bounded");
        require(!new File(path + ".tmp").exists(), "atomic temporary file cleaned up");
        ConnectionEventLog.Record last = records.get(199);
        require(last.statusCode == 8 && last.reason == ConnectionEventLog.Reason.NATIVE_DISCONNECT
                && last.phase == ConnectionEventLog.Phase.READY && last.recording && !last.visible
                && last.statusAgeMs == 1000 && last.frameAgeMs == 500, "loss context preserved");
        records.clear();
        require(log.snapshot().size() == ConnectionEventLog.MAX_RECORDS, "export snapshot cannot mutate ring");
        restart(path, "200", "999");
    }
    private static void oldAndNewControllerShareOneWriter() throws Exception {
        File path = Files.createTempDirectory("volt-connection-lifecycle").resolve("events").toFile();
        ConnectionEventLog old = ConnectionEventLog.open(path);
        old.record(event(1)); old.flush();
        ConnectionEventLog replacement = ConnectionEventLog.open(new File(path.getParentFile(), "./events"));
        require(old == replacement, "controller replacement reuses canonical single writer");
        replacement.record(event(2)); old.record(event(3));
        old.flush().get(10, TimeUnit.SECONDS);
        require(replacement.snapshot().size() == 3, "old pending work cannot overwrite replacement events");
        restart(path, "3", "3");
    }
    private static void corruptionAndOversizeRecover() throws Exception {
        for (int kind = 0; kind < 3; kind++) {
            File path = Files.createTempDirectory("volt-connection-corrupt").resolve("events").toFile();
            if (kind == 0) Files.write(path.toPath(), new byte[]{1, 2, 3});
            else if (kind == 1) Files.write(path.toPath(), new byte[ConnectionEventLog.MAX_FILE_BYTES + 1]);
            else {
                File valid = Files.createTempDirectory("volt-connection-valid").resolve("events").toFile();
                ConnectionEventLog source = ConnectionEventLog.open(valid);
                source.record(event(50)); source.flush().get(10, TimeUnit.SECONDS);
                byte[] tampered = Files.readAllBytes(valid.toPath()); tampered[24] ^= 1;
                Files.write(path.toPath(), tampered);
            }
            ConnectionEventLog log = ConnectionEventLog.open(path);
            log.record(event(99));
            log.flush().get(10, TimeUnit.SECONDS);
            require(log.loaded() && log.storageHealthy(), "bad journal gracefully replaced: " + kind);
            require(log.snapshot().size() == 1 && log.snapshot().get(0).elapsedMs == 99,
                    "bad journal cannot erase new in-memory event: " + kind);
            restart(path, "1", "99");
        }
    }
    private static void failuresDoNotLoseMemoryOrCrash() throws Exception {
        File parent = Files.createTempDirectory("volt-connection-denied").resolve("not-a-directory").toFile();
        Files.write(parent.toPath(), new byte[]{0});
        ConnectionEventLog log = ConnectionEventLog.open(new File(parent, "events"));
        log.record(event(17)); log.flush().get(10, TimeUnit.SECONDS);
        require(!log.storageHealthy(), "storage errors exposed without crashing connection");
        require(log.loaded() && log.snapshot().size() == 1, "in-memory log remains usable");
        Files.delete(parent.toPath()); Files.createDirectory(parent.toPath());
        log.record(event(18)); log.flush().get(10, TimeUnit.SECONDS);
        require(log.storageHealthy() && log.snapshot().size() == 2, "next event retries recovered storage");
    }
    private static void typedPrivacyBoundary() {
        for (Field field : ConnectionEventLog.Record.class.getDeclaredFields()) {
            Class<?> type = field.getType();
            require(type.isPrimitive() || type.isEnum(), "record cannot carry arbitrary text, identities or byte payloads");
            require(Modifier.isFinal(field.getModifiers()), "record immutable after enqueue");
        }
        boolean rejected = false;
        try { new ConnectionEventLog.Record(1, 1, null, -1, false, false,
                ConnectionEventLog.Phase.READY, -1, -1); }
        catch (IllegalArgumentException expected) { rejected = true; }
        require(rejected, "only whitelisted reason enums accepted");
    }
    private static void restart(File path, String... expected) throws Exception {
        ArrayList<String> command = new ArrayList<>(Arrays.asList(
                new File(System.getProperty("java.home"), "bin/java").getPath(), "-cp",
                System.getProperty("java.class.path"), ConnectionEventLogTest.class.getName(), path.getPath()));
        command.addAll(Arrays.asList(expected));
        Process process = new ProcessBuilder(command).inheritIO().start();
        if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("Restart timed out"); }
        require(process.exitValue() == 0, "fresh process reads committed journal");
    }
    private static void require(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
}
