// Test-only Android/Shizuku API surface; never packaged in the app.
package android.content;
public interface SharedPreferences {
    boolean getBoolean(String key, boolean fallback);
    String getString(String key, String fallback);
    Editor edit();
    interface Editor {
        Editor putBoolean(String key, boolean value);
        Editor putString(String key, String value);
        Editor remove(String key);
        Editor clear();
        boolean commit();
    }
}
