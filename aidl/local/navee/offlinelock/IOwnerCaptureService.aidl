package local.navee.offlinelock;
import local.navee.offlinelock.IOwnerCaptureCallback;
interface IOwnerCaptureService {
    void start(String token, IOwnerCaptureCallback callback) = 0;
    void cancel(String token) = 1;
    void recover(String token, String original, IOwnerCaptureCallback callback) = 2;
    void destroy() = 16777114;
}
