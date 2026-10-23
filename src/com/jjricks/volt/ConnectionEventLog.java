package com.jjricks.volt;

import java.io.*;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.CRC32;

/** Device-local troubleshooting, deliberately unable to accept identities or protocol payloads. */
public final class ConnectionEventLog {
    public static final int MAX_RECORDS = 200;
    public static final int MAX_FILE_BYTES = 128 * 1024;
    private static final int MAGIC = 0x56434c47, VERSION = 1;
    public enum Phase { DISCONNECTED, SCANNING, CONNECTING, AUTHENTICATING, READY, WAITING }
    public enum Reason {
        CONNECT_ATTEMPT, AUTHENTICATED_READY, NATIVE_DISCONNECT, ADAPTER_OFF,
        EXPLICIT_DISCONNECT, BACKGROUND_PAUSE, WRITE_FAILED, WRITE_TIMEOUT,
        READ_TIMEOUT, CONNECT_TIMEOUT, CONNECT_FAILED, NOTIFICATION_FAILED,
        SERVICE_DISCOVERY_FAILED, AUTHENTICATION_FAILED, IDENTITY_REJECTED,
        UNSUPPORTED_RESPONSE, COMMAND_CONFIRMATION_TIMEOUT, SERVICE_CLOSED,
        CHARGE_STARTED, CHARGE_RECOVERY_STARTED, CHARGE_RECOVERY_RESUMED,
        CHARGE_RECOVERY_TIMEOUT, CHARGE_STOPPED, CHARGE_WRONG_SCOOTER,
        CHARGE_RECOVERY_CANCELLED
    }
    public static final class Record {
        public final long wallMs, elapsedMs, statusAgeMs, frameAgeMs;
        public final Reason reason;
        public final Phase phase;
        /** A native status/reason number, or -1 when unavailable. */
        public final int statusCode;
        public final boolean visible, recording;
        public Record(long wallMs, long elapsedMs, Reason reason, int statusCode,
                boolean visible, boolean recording, Phase phase, long statusAgeMs, long frameAgeMs) {
            if (wallMs < 0 || elapsedMs < 0 || reason == null || phase == null
                    || statusAgeMs < -1 || frameAgeMs < -1)
                throw new IllegalArgumentException("Invalid connection event");
            this.wallMs = wallMs; this.elapsedMs = elapsedMs; this.reason = reason;
            this.statusCode = statusCode; this.visible = visible; this.recording = recording;
            this.phase = phase; this.statusAgeMs = statusAgeMs; this.frameAgeMs = frameAgeMs;
        }
    }

    // A single process-local writer per canonical path prevents a destroyed Service's pending
    // writes from replacing a newer Service's journal. Threads remain daemon/idle after flush.
    private static final Map<String, ConnectionEventLog> OPEN = new HashMap<>();
    public static synchronized ConnectionEventLog open(File file) {
        String path;
        try { path = file.getCanonicalPath(); }
        catch (IOException unavailable) { path = file.getAbsolutePath(); }
        ConnectionEventLog existing = OPEN.get(path);
        if (existing != null) return existing;
        ConnectionEventLog created = new ConnectionEventLog(new File(path));
        OPEN.put(path, created);
        return created;
    }
    private final File file;
    private final ArrayDeque<Record> records = new ArrayDeque<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "connection-diagnostics"); thread.setDaemon(true); return thread;
    });
    private boolean loaded, scheduled = true, storageHealthy = true;
    private long revision;
    private ConnectionEventLog(File file) {
        this.file = file;
        worker.execute(() -> {
            List<Record> saved;
            try { saved = read(file); }
            catch (IOException | IllegalArgumentException | SecurityException corrupt) {
                saved = Collections.emptyList();
                synchronized (this) { storageHealthy = false; }
            }
            synchronized (this) {
                ArrayList<Record> pending = new ArrayList<>(records);
                records.clear();
                for (Record record : saved) append(record);
                for (Record record : pending) append(record);
                loaded = true;
            }
            drain();
        });
    }
    public synchronized void record(Record record) {
        Objects.requireNonNull(record, "record");
        append(record); revision++;
        if (!scheduled) { scheduled = true; worker.execute(this::drain); }
    }
    private void append(Record record) {
        records.addLast(record);
        while (records.size() > MAX_RECORDS) records.removeFirst();
    }
    /** Nonblocking in-memory copy. loaded() distinguishes a still-loading previous process journal. */
    public synchronized List<Record> snapshot() { return new ArrayList<>(records); }
    public synchronized boolean loaded() { return loaded; }
    public synchronized boolean storageHealthy() { return storageHealthy; }
    /** Enqueues a barrier; never wait for this on the UI thread. No lifecycle shuts down shared work. */
    public Future<?> flush() { return worker.submit(() -> {}); }
    private void drain() {
        while (true) {
            List<Record> copy; long captured;
            synchronized (this) { copy = new ArrayList<>(records); captured = revision; }
            boolean written = true;
            try { write(file, copy); }
            catch (IOException | SecurityException unavailable) { written = false; }
            synchronized (this) {
                storageHealthy = written;
                if (revision == captured) { scheduled = false; return; }
            }
        }
    }
    private static void write(File file, List<Record> records) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeInt(MAGIC); out.writeInt(VERSION); out.writeInt(records.size());
            for (Record record : records) {
                out.writeLong(record.wallMs); out.writeLong(record.elapsedMs);
                out.writeUTF(record.reason.name()); out.writeInt(record.statusCode);
                out.writeByte((record.visible ? 1 : 0) | (record.recording ? 2 : 0));
                out.writeUTF(record.phase.name());
                out.writeLong(record.statusAgeMs); out.writeLong(record.frameAgeMs);
            }
        }
        byte[] bytes = buffer.toByteArray();
        if (bytes.length + 8 > MAX_FILE_BYTES) throw new IOException("Connection journal too large");
        CRC32 crc = new CRC32(); crc.update(bytes);
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Storage unavailable");
        File temporary = new File(parent, file.getName() + ".tmp");
        try {
            try (FileOutputStream stream = new FileOutputStream(temporary);
                    DataOutputStream out = new DataOutputStream(stream)) {
                out.write(bytes); out.writeLong(crc.getValue()); out.flush(); stream.getFD().sync();
            }
            try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary.toPath()); }
    }
    private static List<Record> read(File file) throws IOException {
        if (!file.exists()) return Collections.emptyList();
        if (file.length() > MAX_FILE_BYTES) throw new IOException("Oversized connection journal");
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (InputStream input = new FileInputStream(file)) {
            byte[] chunk = new byte[4096]; int count;
            while ((count = input.read(chunk)) != -1) {
                if (buffer.size() + count > MAX_FILE_BYTES) throw new IOException("Oversized connection journal");
                buffer.write(chunk, 0, count);
            }
        }
        byte[] bytes = buffer.toByteArray();
        if (bytes.length < 20) throw new IOException("Incomplete connection journal");
        CRC32 crc = new CRC32(); crc.update(bytes, 0, bytes.length - 8);
        try (DataInputStream check = new DataInputStream(new ByteArrayInputStream(bytes, bytes.length - 8, 8))) {
            if (check.readLong() != crc.getValue()) throw new IOException("Corrupt connection journal");
        }
        ArrayList<Record> records = new ArrayList<>();
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes, 0, bytes.length - 8))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION) throw new IOException("Unknown connection journal");
            int count = input.readInt();
            if (count < 0 || count > MAX_RECORDS) throw new IOException("Invalid connection record count");
            for (int i = 0; i < count; i++) {
                long wall = input.readLong(), elapsed = input.readLong();
                Reason reason = Reason.valueOf(input.readUTF()); int status = input.readInt();
                int flags = input.readUnsignedByte();
                if (flags > 3) throw new IOException("Invalid connection flags");
                Phase phase = Phase.valueOf(input.readUTF());
                records.add(new Record(wall, elapsed, reason, status, (flags & 1) != 0,
                        (flags & 2) != 0, phase, input.readLong(), input.readLong()));
            }
            if (input.read() != -1) throw new IOException("Unexpected connection journal data");
        }
        return records;
    }
}
