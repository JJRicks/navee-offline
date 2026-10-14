// Test-only Android/Shizuku API surface; never packaged in the app.
package rikka.shizuku;
import android.content.ComponentName;
import android.content.ServiceConnection;
import java.util.ArrayList;
import java.util.List;
/** Simulates binding only; no capture logic is implemented in this stub. */
public final class Shizuku {
    public interface PermissionListener { void onResult(int request, int grant); }
    public static final List<ServiceConnection> bindings = new ArrayList<>();
    public static final List<ServiceConnection> unbound = new ArrayList<>();
    public static boolean failBind;
    public static void reset() { bindings.clear(); unbound.clear(); failBind = false; }
    public static void addBinderReceivedListenerSticky(Runnable listener) { }
    public static void addBinderDeadListener(Runnable listener) { }
    public static void addRequestPermissionResultListener(PermissionListener listener) { }
    public static boolean pingBinder() { return true; }
    public static int getVersion() { return 13; }
    public static int getUid() { return 2000; }
    public static int checkSelfPermission() { return 0; }
    public static boolean shouldShowRequestPermissionRationale() { return false; }
    public static void requestPermission(int request) { }
    public static void bindUserService(UserServiceArgs args, ServiceConnection connection) {
        bindings.add(connection);
        if (failBind) throw new IllegalStateException("simulated bind failure");
    }
    public static void unbindUserService(UserServiceArgs args, ServiceConnection connection, boolean remove) {
        unbound.add(connection);
    }
    public static final class UserServiceArgs {
        public UserServiceArgs(ComponentName name) { }
        public UserServiceArgs daemon(boolean value) { return this; }
        public UserServiceArgs tag(String value) { return this; }
        public UserServiceArgs version(int value) { return this; }
        public UserServiceArgs debuggable(boolean value) { return this; }
        public UserServiceArgs processNameSuffix(String value) { return this; }
    }
}
