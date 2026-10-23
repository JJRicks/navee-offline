// Test-only Android/Shizuku API surface; never packaged in the app.
package com.jjricks.volt;
import android.os.IBinder;
/** Host stand-in for the generated AIDL API, retaining the production signatures. */
public interface IOwnerCaptureService {
    void start(String token, IOwnerCaptureCallback callback) throws Exception;
    void cancel(String token) throws Exception;
    void recover(String token, String original, IOwnerCaptureCallback callback) throws Exception;
    void destroy() throws Exception;
    abstract class Stub extends android.os.Binder implements IOwnerCaptureService {
        public static IOwnerCaptureService asInterface(IBinder binder) {
            return (IOwnerCaptureService) binder;
        }
    }
}
