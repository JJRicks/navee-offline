package local.navee.offlinelock;
import android.app.Activity;
import java.io.File;
/** Shared native screen contract; connection ownership remains in the service. */
public interface ScreenHost {
    Activity activity(); Ui ui(); ScooterService service();
    void redraw(); void rebuild(); void toast(String text); void connect();
    void requestBluetooth(); void importOwner();
    void requestRideLocation(Runnable ready);
    void saveText(String name,String mime,String content);
    void saveFile(String name,String mime,File content);
}
