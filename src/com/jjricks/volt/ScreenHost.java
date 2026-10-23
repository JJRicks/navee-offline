package com.jjricks.volt;
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
    /** Back up & restore. A null direction first asks Back up or Restore; preselect is a category ID or null. */
    default void dataBackup(Boolean restore, String preselect) { }
    default void exportDashboardLayouts() { }
    default void importDashboardLayouts() { }
    /** Opens the owner's email app with a draft to Volt's report address; false when no app can. */
    default boolean emailVolt(String subject, String body) { return false; }
    /** Asks which scooter this is (and optionally its drive modes), then drafts the finished check's report to Volt's report address. */
    default void emailReport(CompatibilityReport report) { }
    /** Asks which listed NAVEE model this scooter is and keeps the answer on its profile. */
    default void chooseScooterModel(String profileId) { }
}
