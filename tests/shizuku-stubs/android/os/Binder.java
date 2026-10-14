// Test-only Android/Shizuku API surface; never packaged in the app.
package android.os;
public class Binder implements IBinder {
    public static int getCallingUid() { return 2000; }
    public void linkToDeath(DeathRecipient recipient, int flags) throws Exception { }
}
