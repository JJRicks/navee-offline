package local.navee.offlinelock;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.security.SecureRandom;
import rikka.shizuku.Shizuku;

/** Process-local coordinator: survives the NAVEE foreground switch, never persists a captured identity. */
final class ShizukuCaptureClient {
    static final int REQUEST_PERMISSION = 7041;
    // Bump with every UserService/guard behavior change, including same-version previews.
    private static final int USER_SERVICE_VERSION = 2;
    private static ShizukuCaptureClient singleton;
    static synchronized ShizukuCaptureClient get(Context context) {
        if (singleton == null) singleton = new ShizukuCaptureClient(context.getApplicationContext());
        return singleton;
    }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences journal;
    private final Shizuku.UserServiceArgs args;
    private IOwnerCaptureService remote;
    private ServiceConnection binding;
    private Runnable listener;
    private volatile String token = "";
    private boolean sessionCancelled, recoveryAttempt;
    private volatile boolean remoteDispatched;
    volatile boolean busy;
    int phase;
    String message = "";
    OwnerIdentityParser.Result owner;
    private OwnerIdentityParser.Result approved;
    private ShizukuCaptureClient(Context context) {
        journal = context.getSharedPreferences("owner_capture_cleanup", Context.MODE_PRIVATE);
        args = new Shizuku.UserServiceArgs(new ComponentName(context, OwnerCaptureUserService.class))
                .daemon(true).tag("volt-owner-capture-v1").version(USER_SERVICE_VERSION).debuggable(false).processNameSuffix("owner_setup");
        Shizuku.addBinderReceivedListenerSticky(this::changed);
        Shizuku.addBinderDeadListener(() -> lost());
        Shizuku.addRequestPermissionResultListener((request, grant) -> {
            if (request == REQUEST_PERMISSION) {
                message = grant == PackageManager.PERMISSION_GRANTED ? "Access granted. Capture still needs your approval."
                        : "Access was not granted. You can retry or use another setup method.";
                changed();
            }
        });
    }
    void listen(Runnable update) { listener = update; }
    void unlisten(Runnable update) { if (listener == update) listener = null; }
    private void changed() { handler.post(() -> { if (listener != null) listener.run(); }); }
    boolean pendingCleanup() { return journal.getBoolean("pending", false); }
    boolean available() {
        try { return Shizuku.pingBinder() && Shizuku.getVersion() >= 13 && Shizuku.getUid() == 2000; }
        catch (RuntimeException ignored) { return false; }
    }
    boolean permitted() {
        try { return available() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED; }
        catch (RuntimeException ignored) { return false; }
    }
    void requestPermission() {
        if (!available()) { message = "Start Shizuku using wireless debugging, then return here."; changed(); return; }
        try {
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                message = "Open Shizuku › Authorized applications and allow Volt, then return here.";
            } else Shizuku.requestPermission(REQUEST_PERMISSION);
        } catch (RuntimeException ignored) { message = "Shizuku disconnected. Start it again and retry."; }
        changed();
    }
    void start(boolean recovery) {
        if (busy || !permitted() || (!recovery && pendingCleanup())) return;
        owner = null; approved = null; message = ""; sessionCancelled = false; busy = true; phase = OwnerCaptureEngine.PREPARING;
        byte[] random = new byte[32]; new SecureRandom().nextBytes(random);
        String session = recovery ? journal.getString("token", "") : Protocol.hex(random).toLowerCase(java.util.Locale.ROOT);
        String original = recovery ? journal.getString("original", null) : null;
        if (!OwnerCaptureEngine.validToken(session) || (recovery && original != null && !OwnerCaptureEngine.validProperty(original))) {
            busy = false; message = "Cleanup record is invalid. Use the computer helper's recovery instructions before capturing again."; changed(); return;
        }
        token = session; recoveryAttempt = recovery; remoteDispatched = false;
        // Reserve a non-secret recovery token before the shell process can acquire its lock.
        // Even a process kill between mkdir and the original-property callback stays recoverable.
        if (!recovery && !journal.edit().putBoolean("pending", true).putString("token", session).remove("original").commit()) {
            busy = false; message = "Could not save the cleanup record. No capture was started."; changed(); return;
        }
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                if (binding != this || !busy || !session.equals(token)) return;
                remote = IOwnerCaptureService.Stub.asInterface(binder);
                try {
                    binder.linkToDeath(() -> handler.post(() -> { if (binding == this) lost(); }), 0);
                    // Mark before IPC: an exception can mean the remote started but
                    // its reply was lost. Only definitely-unsent calls may drop a receipt.
                    remoteDispatched = true;
                    if (recovery) remote.recover(session, original, callback); else remote.start(session, callback);
                } catch (Exception ignored) { lost(); }
            }
            @Override public void onServiceDisconnected(ComponentName name) { if (binding == this) lost(); }
        };
        binding = connection;
        try { Shizuku.bindUserService(args, connection); }
        catch (RuntimeException ignored) { lost(); }
        handler.postDelayed(() -> {
            if (binding == connection && busy && remote == null) {
                discardUnsentJournal();
                busy = false; phase = 0;
                message = pendingCleanup() ? "Shizuku could not start the helper. Restart it and restore the saved logging setting."
                        : "Shizuku could not start the helper. No capture was started. Restart Shizuku and retry.";
                unbind(); changed();
            }
        }, 15000);
        changed();
    }
    private final IOwnerCaptureCallback callback = new IOwnerCaptureCallback.Stub() {
        private void authorize() { if (Binder.getCallingUid() != 2000) throw new SecurityException("Not authorized"); }
        @Override public boolean prepareCleanup(String session, String original) {
            authorize();
            synchronized (ShizukuCaptureClient.this) {
                if (!busy || !token.equals(session) || !OwnerCaptureEngine.validProperty(original)) return false;
                return journal.edit().putBoolean("pending", true).putString("token", session)
                        .putString("original", original).commit();
            }
        }
        @Override public void progress(String session, int next) {
            authorize(); handler.post(() -> { if (busy && token.equals(session)) { phase = next; changed(); } });
        }
        @Override public void completed(String session, int outcome, boolean clean, String identity, int role) {
            authorize();
            // The only credential boundary. It is never logged, copied, put in an Intent or saved state.
            handler.post(() -> {
                if (!token.equals(session)) return;
                boolean wasCancelled = sessionCancelled || !busy;
                busy = false; phase = 0; owner = null;
                if (clean && !journal.edit().clear().commit()) cleanResultFailure();
                else if (!clean) message = "Cleanup needs your attention. Start Shizuku again, then restore NAVEE logging below.";
                else if (!wasCancelled && outcome == OwnerCaptureEngine.OK && identity != null
                        && identity.matches("[0-9A-Fa-f]{12}") && (role == 0 || role == 1)) {
                    owner = new OwnerIdentityParser.Result(identity.toUpperCase(java.util.Locale.ROOT), role);
                    message = "Owner setup found. NAVEE logging was restored.";
                } else if (outcome == OwnerCaptureEngine.TIMED_OUT) message = "No setup found within 90 seconds. NAVEE logging was restored. Connect in NAVEE and retry.";
                else if (outcome == OwnerCaptureEngine.CONFLICT) message = "More than one owner setup appeared. NAVEE logging was restored. Connect just one scooter and retry.";
                else if (outcome == OwnerCaptureEngine.BUSY) message = "Another capture helper owns the setup lock. Finish or recover that session first.";
                else if (outcome == OwnerCaptureEngine.CANCELLED || wasCancelled) message = "Setup stopped. NAVEE logging was restored.";
                else message = "Capture did not finish. NAVEE logging was restored. Check NAVEE can connect and retry.";
                unbind(); changed();
            });
        }
    };
    private void cleanResultFailure() { message = "Cleanup finished, but its local receipt could not be saved. Restore logging again before importing."; }
    void cancel() {
        owner = null; sessionCancelled = true;
        if (busy) {
            phase = OwnerCaptureEngine.CLEANING; message = "Stopping capture and restoring NAVEE logging…";
            if (remote != null) {
                try { remote.cancel(token); } catch (Exception ignored) { lost(); }
            } else {
                discardUnsentJournal(); busy = false; phase = 0;
                message = pendingCleanup() ? "Setup stopped. The previous session still needs logging restored."
                        : "Setup stopped. No capture was started.";
                unbind();
            }
        }
        changed();
    }
    private void discardUnsentJournal() {
        // A bind failure cannot have changed the phone. Recovery, however, refers
        // to an older possibly-mutating session and must retain its receipt.
        if (busy && binding != null && !recoveryAttempt && !remoteDispatched
                && token.equals(journal.getString("token", ""))) journal.edit().clear().commit();
    }
    private void lost() {
        discardUnsentJournal();
        remote = null; busy = false; owner = null;
        message = pendingCleanup() ? "Shizuku disconnected before cleanup was confirmed. Restart it and restore logging."
                : "Shizuku disconnected. Start it again and retry.";
        unbind(); changed();
    }
    private void unbind() {
        ServiceConnection connection = binding; binding = null; remote = null;
        if (connection != null) try { Shizuku.unbindUserService(args, connection, true); } catch (RuntimeException ignored) { }
    }
    boolean approveImport() {
        if (owner == null || busy || pendingCleanup()) return false;
        approved = owner; owner = null; return true;
    }
    OwnerIdentityParser.Result takeApproved() { OwnerIdentityParser.Result value = approved; approved = null; return value; }
    void discard() { owner = null; changed(); }
}
