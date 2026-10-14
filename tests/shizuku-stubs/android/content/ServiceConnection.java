// Test-only Android/Shizuku API surface; never packaged in the app.
package android.content;
import android.os.IBinder;
public interface ServiceConnection {
    void onServiceConnected(ComponentName name, IBinder binder);
    void onServiceDisconnected(ComponentName name);
}
