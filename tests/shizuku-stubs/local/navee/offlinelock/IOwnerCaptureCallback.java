// Test-only Android/Shizuku API surface; never packaged in the app.
package local.navee.offlinelock;
/** Host stand-in for the generated AIDL API, retaining the production signatures. */
public interface IOwnerCaptureCallback {
    boolean prepareCleanup(String token, String original);
    void progress(String token, int phase);
    void completed(String token, int outcome, boolean clean, String identity, int role);
    abstract class Stub extends android.os.Binder implements IOwnerCaptureCallback { }
}
