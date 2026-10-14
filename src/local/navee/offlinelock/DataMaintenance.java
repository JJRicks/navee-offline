package local.navee.offlinelock;

import android.content.Context;
import java.io.IOException;

/** Process-wide barrier: after quiescing, only the backup screen may run until Volt closes. */
public final class DataMaintenance {
    private static volatile boolean active;
    private static volatile boolean recovered;
    private DataMaintenance() { }
    public static boolean active() { return active; }
    public static void enter() { active = true; }
    public static boolean recovered() { return recovered; }
    /** Clean launches need no recovery screen or disk copy. A pending transaction/stage must
     * take the worker path; this getter never waits for a worker holding the recovery monitor. */
    public static boolean readyForStartup(Context context) {
        if (active) return false;
        if (recovered) return true;
        if (AppDataBackup.needsRecovery(context) || BackupExportCache.hasOrphans(context.getCacheDir())) return false;
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
            BackupExportCache.removeOrphans(context.getCacheDir());
            recovered = true;
        }
        catch (Exception error) { throw new IOException("An interrupted restore needs recovery.", error); }
    }
}
