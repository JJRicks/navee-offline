// Test-only Android/Shizuku API surface; never packaged in the app.
package android.os;
public final class Looper {
    private static final Looper MAIN = new Looper();
    public static Looper getMainLooper() { return MAIN; }
}
