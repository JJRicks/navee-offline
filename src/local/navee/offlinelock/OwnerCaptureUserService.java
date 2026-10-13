package local.navee.offlinelock;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Runs under Shizuku's shell UID. Only this app UID may call the fixed-purpose capture API. */
public final class OwnerCaptureUserService extends IOwnerCaptureService.Stub {
    private static final String LOCK = "/data/local/tmp/volt-owner-capture.lock";
    private static final Set<String> FILES = new HashSet<>(Arrays.asList("owner", "token", "original", "started", "guard.sh", "ready", "status", "stopping", "guard_done"));
    private final int appUid;
    private Session current;
    private Thread worker;

    public OwnerCaptureUserService(Context context) {
        appUid = context.getApplicationInfo().uid;
        if (!"local.navee.offlinelock".equals(context.getPackageName()) || appUid < 10000 || appUid >= 100000 || Process.myUid() != 2000)
            throw new SecurityException("Shell setup unavailable");
    }
    private void authorize() {
        if (Binder.getCallingUid() != appUid) throw new SecurityException("Not authorized");
    }
    @Override public synchronized void start(String token, IOwnerCaptureCallback callback) {
        authorize(); begin(token, null, callback, false);
    }
    @Override public synchronized void recover(String token, String original, IOwnerCaptureCallback callback) {
        authorize();
        if (original != null && !OwnerCaptureEngine.validProperty(original)) throw new IllegalArgumentException("Invalid cleanup");
        begin(token, original, callback, true);
    }
    private void begin(String token, String original, IOwnerCaptureCallback callback, boolean recovery) {
        if (!OwnerCaptureEngine.validToken(token) || callback == null) throw new IllegalArgumentException("Invalid session");
        if (current != null) throw new IllegalStateException("A capture is still cleaning up");
        Session session = new Session(token, callback); current = session;
        try { callback.asBinder().linkToDeath(session.death, 0); }
        catch (Exception ignored) { current = null; return; }
        worker = new Thread(() -> {
            OwnerCaptureEngine.Result result;
            if (!recovery) result = OwnerCaptureEngine.capture(session, token);
            else {
                boolean clean = false;
                try {
                    String previous = original;
                    if (!new File(LOCK).exists()) {
                        if (previous == null) clean = true; // The reserved session never reached the property snapshot.
                        else {
                            String now = OwnerCaptureEngine.phoneEpoch(session.command("/system/bin/date", "+%s.%N"));
                            session.acquire(token); session.initialize(previous, now);
                        }
                    } else if (previous == null) {
                        if (!session.owns(token, null)) throw new SecurityException();
                        if (new File(LOCK, "original").exists()) previous = session.read("original");
                        else if (new File(LOCK, "guard.sh").exists()) throw new SecurityException();
                    }
                    if (!clean) {
                        session.progress(OwnerCaptureEngine.CLEANING);
                        clean = OwnerCaptureEngine.cleanup(session, token, previous, previous != null);
                    }
                } catch (Exception ignored) { }
                result = new OwnerCaptureEngine.Result(clean ? OwnerCaptureEngine.CANCELLED : OwnerCaptureEngine.FAILED, clean, null);
            }
            try {
                session.callback.completed(token, result.outcome, result.clean,
                        result.owner == null ? "" : result.owner.identity, result.owner == null ? 0 : result.owner.role);
            } catch (Exception ignored) { }
            session.callback.asBinder().unlinkToDeath(session.death, 0);
            synchronized (OwnerCaptureUserService.this) { if (current == session) current = null; }
            if (session.clientDied) System.exit(0);
        }, "Owner-capture-transaction");
        worker.start();
    }
    @Override public synchronized void cancel(String token) {
        authorize();
        if (current != null && current.token.equals(token)) current.cancelled = true;
    }
    @Override public void destroy() {
        // Shizuku itself uses this reserved transaction. It may cancel, never start/import.
        int uid = Binder.getCallingUid();
        if (uid != appUid && uid != 2000 && uid != 0) throw new SecurityException("Not authorized");
        final Thread pending;
        synchronized (this) { if (current != null) current.cancelled = true; pending = worker; }
        new Thread(() -> {
            try { if (pending != null) pending.join(60000); } catch (InterruptedException ignored) { }
            System.exit(0);
        }, "Owner-capture-shutdown").start();
    }
    private final class Session implements OwnerCaptureEngine.Host {
        final String token; final IOwnerCaptureCallback callback;
        volatile boolean cancelled, clientDied;
        final IBinder.DeathRecipient death = () -> { cancelled = true; clientDied = true; };
        Session(String token, IOwnerCaptureCallback callback) { this.token = token; this.callback = callback; }
        @Override public String command(String... argv) throws Exception { return OwnerCaptureProcess.run(argv); }
        @Override public long elapsed() { return SystemClock.elapsedRealtime(); }
        @Override public void pause() throws Exception { Thread.sleep(700); }
        @Override public boolean cancelled() { return cancelled; }
        @Override public boolean journal(String token, String original) throws Exception {
            return callback.prepareCleanup(token, original);
        }
        @Override public void progress(int phase) {
            try { callback.progress(token, phase); } catch (Exception ignored) { cancelled = true; }
        }
        @Override public void acquire(String token) throws Exception {
            try { Os.mkdir(LOCK, 0700); }
            catch (android.system.ErrnoException failure) {
                if (failure.errno == OsConstants.EEXIST) throw new OwnerCaptureEngine.CaptureBusy();
                throw failure;
            }
            try {
                write("owner", "shizuku"); write("token", token);
            } catch (Exception failure) {
                // No property mutation is allowed before every lock field and private journal exist.
                for (String name : FILES) new File(LOCK, name).delete();
                new File(LOCK).delete(); throw failure;
            }
        }
        @Override public void initialize(String original, String started) throws Exception {
            write("original", original); write("started", started);
        }
        @Override public void arm(String token) throws Exception {
            write("guard.sh", OwnerCaptureGuardScript.TEXT);
            java.lang.Process guard;
            try {
                guard = new ProcessBuilder("/system/bin/nohup", "/system/bin/sh", LOCK + "/guard.sh", "watch", token)
                        .redirectInput(new File("/dev/null")).redirectOutput(new File("/dev/null"))
                        .redirectError(new File("/dev/null")).start();
            } catch (Exception neverStarted) {
                // The OS did not create a watchdog. No logging mutation has been sent yet.
                write("guard_done", "done"); throw neverStarted;
            }
            long start = elapsed();
            while (elapsed() - start < 4000) {
                if (new File(LOCK, "ready").exists() && "ready".equals(read("ready"))) return;
                if (!guard.isAlive()) {
                    // The freshly spawned guard exited before its ready handshake, well before
                    // its 120s timer. There is no late restoration process to wait for.
                    write("guard_done", "done"); throw new Exception("Guard unavailable");
                }
                Thread.sleep(100);
            }
            throw new Exception("Guard unavailable");
        }
        @Override public void startLogging(String token, String component) throws Exception {
            OwnerCaptureProcess.runBounded(20, "/system/bin/sh", "-c", OwnerCaptureGuardScript.TEXT, "volt-guard", "start", token, component);
        }
        @Override public void restoreLogging(String token) throws Exception {
            OwnerCaptureProcess.runBounded(45, "/system/bin/sh", "-c", OwnerCaptureGuardScript.TEXT, "volt-guard", "clean", token);
        }
        @Override public boolean owns(String token, String original) throws Exception {
            StructStat info = Os.lstat(LOCK);
            if (!OsConstants.S_ISDIR(info.st_mode) || info.st_uid != 2000 || (info.st_mode & 0777) != 0700) return false;
            return "shizuku".equals(read("owner")) && token.equals(read("token")) && (original == null || original.equals(read("original")));
        }
        @Override public void release(String token, String original) throws Exception {
            if (!owns(token, original)) throw new SecurityException();
            String[] names = new File(LOCK).list();
            if (names == null) throw new SecurityException();
            for (String name : names) if (!FILES.contains(name)) throw new SecurityException();
            if (new File(LOCK, "guard.sh").exists()) {
                long start = elapsed();
                while (elapsed() - start < 5000 && !new File(LOCK, "guard_done").exists()) Thread.sleep(100);
                if (!new File(LOCK, "guard_done").exists()) throw new Exception("Guard still pending");
                String acknowledgment = read("guard_done");
                if (!"done".equals(acknowledgment) && !"stopped".equals(acknowledgment)) throw new Exception("Guard still pending");
            }
            names = new File(LOCK).list();
            if (names == null) throw new SecurityException();
            for (String name : names) if (!FILES.contains(name)) throw new SecurityException();
            // Atomically release the reservation only after restoration + watchdog acknowledgment.
            // File-deletion failure must not destroy the recovery keys in a still-reserved lock.
            String retired = LOCK + ".cleaned-" + java.util.UUID.randomUUID().toString();
            Os.rename(LOCK, retired);
            for (String name : names) new File(retired, name).delete();
            new File(retired).delete();
        }
        private String read(String name) throws Exception {
            String path = LOCK + "/" + name; StructStat info = Os.lstat(path);
            if (!OsConstants.S_ISREG(info.st_mode) || info.st_uid != 2000 || (info.st_mode & 0777) != 0600
                    || info.st_size > 128) throw new SecurityException();
            byte[] bytes = new byte[(int)info.st_size];
            try (FileInputStream in = new FileInputStream(Os.open(path, OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW, 0))) {
                int offset = 0, count;
                while (offset < bytes.length && (count = in.read(bytes, offset, bytes.length - offset)) > 0) offset += count;
                if (offset != bytes.length || in.read() != -1) throw new SecurityException();
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
        private void write(String name, String value) throws Exception {
            try (FileOutputStream out = new FileOutputStream(Os.open(LOCK + "/" + name,
                    OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_EXCL | OsConstants.O_NOFOLLOW, 0600))) {
                out.write(value.getBytes(StandardCharsets.UTF_8)); out.getFD().sync();
            }
        }
    }
}
