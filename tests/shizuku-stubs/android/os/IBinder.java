// Test-only Android/Shizuku API surface; never packaged in the app.
package android.os;
public interface IBinder {
    interface DeathRecipient { void binderDied(); }
    void linkToDeath(DeathRecipient recipient, int flags) throws Exception;
}
