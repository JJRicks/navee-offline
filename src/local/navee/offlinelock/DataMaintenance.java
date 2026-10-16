package local.navee.offlinelock;

import android.content.Context;
import android.content.Intent;
import java.io.IOException;

/** Process-wide barrier: after quiescing, only the backup screen may run until Volt closes. */
public final class DataMaintenance {
    private static volatile boolean active;
    private static volatile boolean recovered;
    private static volatile Intent transfer;
    private DataMaintenance() { }
    public static boolean active() { return active; }
    public static void enter() { active = true; }
    /** Published only after the writer drain, and retained across repeated launcher entries. */
    static synchronized void preparedTransfer(Intent intent) {
        if (!active) throw new IllegalStateException("App data must be paused first");
        if (transfer != null && !transfer.getComponent().equals(intent.getComponent()))
            throw new IllegalStateException("Another transfer already owns this session");
        if (transfer == null) transfer = new Intent(intent);
    }
    static Intent preparedTransfer() { Intent current = transfer; return current == null ? null : new Intent(current); }
    public static boolean recovered() { return recovered; }
    /** Clean launches need no recovery screen or disk copy. A pending transaction/stage must
     * take the worker path; this getter never waits for a worker holding the recovery monitor. */
    public static boolean readyForStartup(Context context) {
        if (active) return false;
        if (recovered) return true;
        if (AppDataBackup.needsRecovery(context) || RideBackup.needsRecovery(context) || BackupExportCache.hasOrphans(context.getCacheDir())) return false;
        recovered = true;
        return true;
    }
    public static synchronized void ensureRecovered(Context context) throws IOException {
        if (recovered) return;
        // MainActivity's startup gate has not exposed any backup controls yet. Never clean
        // this cache after entering maintenance: its current export may still be in use.
        if (active) throw new IOException("Close the current backup session before recovering app data.");
        try {
            AppDataBackup.recover(context);
            RideBackup.recover(context);
            BackupExportCache.removeOrphans(context.getCacheDir());
            recovered = true;
        }
        catch (Exception error) { throw new IOException("An interrupted restore needs recovery.", error); }
    }
}
