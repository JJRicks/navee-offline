package local.navee.offlinelock.tests;

import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.graphics.Rect;
import android.os.*;
import android.service.notification.StatusBarNotification;
import android.text.Layout;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import local.navee.offlinelock.*;

/**
 * Disposable empty emulator only. Synthetic journals that ended with the automatic parked stop:
 * ride details pill, End parked rides preference, ride backup round trip, learned-range screening
 * and the Ride saved automatically notice. Never connects Bluetooth or starts a ride.
 */
public final class RideAutoStopValidation extends Instrumentation {
    private Bundle args; private MainActivity activity; private int checks; private final List<String> screenshots = new ArrayList<>();
    private String profileId; private final List<String> automatic = new ArrayList<>(); private String manual;
    @Override public void onCreate(Bundle arguments) { args = arguments == null ? new Bundle() : arguments; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Context context = getTargetContext();
            check(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"), "Refusing physical phone");
            for (String name : new String[]{"app_settings", "profiles", "scooter", "pending_documents"}) check(context.getSharedPreferences(name, 0).getAll().isEmpty(), "Requires empty preferences");
            check(context.getFilesDir().list().length == 0 && context.databaseList().length == 0, "Requires empty private data");
            BluetoothAdapter adapter = context.getSystemService(BluetoothManager.class).getAdapter(); check(adapter == null || !adapter.isEnabled(), "Bluetooth must be off");
            context.getSharedPreferences("app_settings", 0).edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                    .putBoolean("map_basemap_enabled", false).putString("theme", args.getString("theme", "dark")).commit();
            AtomicReference<String> created = new AtomicReference<>();
            main(() -> created.set(new ScooterProfiles(context).create("Synthetic auto-stop scooter", "A1B2C3D4E5F6", 0).id)); profileId = created.get();
            File folder = new File(context.getFilesDir(), "rides");
            seed(folder); backupRoundTrip(context, folder); preferenceTypes();
            activity = (MainActivity) startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.service() != null, "Service ready");
            learnedRange();
            details();
            preference();
            notice();
            check(!activity.service().ble.connected() && !activity.service().isRecording(), "No scooter connection or active recording");
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", android.util.Log.getStackTraceString(error));
            try { screenshot("failure"); } catch (Throwable ignored) {}
        } finally {
            result.putInt("checks", checks); result.putStringArrayList("screenshots", new ArrayList<>(screenshots));
            if (activity != null) main(() -> { activity.finish(); ScooterService.setUiVisible(false); getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); });
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    /** Same shape as RideAutoStopStoreTest: 570 s riding, 30 s parked, 12 s silent link, the gap, 10 s grace. */
    private void seed(File folder) throws Exception {
        RideStore store = new RideStore(folder); long base = System.currentTimeMillis() - 4 * 3600000L;
        for (int n = 0; n < 4; n++) {
            boolean auto = n < 3; long start = base + n * 900000L, mono = 0;
            String id = store.start(profileId, auto ? "Parked campus ride " + (n + 1) : "Stopped by hand", start, 0);
            for (int sec = 0; sec <= 600; sec++) {
                mono = sec * 1000L; boolean parked = sec > 570; double f = Math.min(1, sec / 570d);
                store.sample(start + mono, mono, values(parked ? 0 : 24, 80 - 8 * f, 100 + 4 * f, mono));
            }
            long last = mono;
            for (int i = 1; i <= 12; i++) { mono = last + i * 1000L; store.sample(start + mono, mono, values(0, 72, 104, last)); }
            store.markGap(start + mono + 1000, mono + 1000, "Bluetooth disconnected");
            for (int i = 1; i <= 10; i++) store.heartbeat(start + mono + 1000 + i * 1000L, mono + 1000 + i * 1000L);
            mono += 11000;
            store.stop(start + mono, mono, auto ? RideStore.STOP_AUTO_PARKED : "");
            if (auto) automatic.add(id); else manual = id;
        }
        for (String id : automatic) { RideStore.Summary s = store.get(id); check(s.automaticStop && RideStore.STOP_AUTO_PARKED.equals(s.stopReason) && s.gapCount == 1, "Seeded automatic stop reads back"); }
        RideStore.Summary hand = store.get(manual); check(!hand.automaticStop && "".equals(hand.stopReason), "Seeded rider stop reads back as the rider's");
        check(store.get(automatic.get(0)).toJson().contains("\"stopReason\":\"automatic_parked_disconnect\"") && hand.toJson().contains("\"stopReason\":\"rider\""), "Analysis JSON names each stop");
    }
    private static Map<String, RideStore.Sample> values(double speed, double soc, double odo, long updated) {
        Map<String, RideStore.Sample> v = new LinkedHashMap<>();
        v.put("battery_percent", new RideStore.Sample(soc, updated)); v.put("odometer_km", new RideStore.Sample(odo, updated));
        v.put("speed_kmh", new RideStore.Sample(speed, updated)); v.put("temperature_c", new RideStore.Sample(20, updated));
        v.put("charging_state", new RideStore.Sample(0, updated)); return v;
    }

    /** Real .voltrides export, delete, stage, plan and apply through the app's private storage. */
    private void backupRoundTrip(Context context, File folder) throws Exception {
        Class<?> archive = Class.forName("local.navee.offlinelock.RideArchive"), storageType = Class.forName("local.navee.offlinelock.RideArchive$Storage"),
                preparedType = Class.forName("local.navee.offlinelock.RideArchive$Prepared"), planType = Class.forName("local.navee.offlinelock.RideArchive$Plan");
        Object storage = call(Class.forName("local.navee.offlinelock.RideBackup"), null, "storage", new Class<?>[]{Context.class}, context);
        Map<String, byte[]> journals = new HashMap<>(); RideStore store = new RideStore(folder);
        for (RideStore.Summary s : store.list()) journals.put(s.id, Files.readAllBytes(new File(folder, s.id + ".csv").toPath()));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        check(Integer.valueOf(4).equals(call(archive, null, "write", new Class<?>[]{storageType, OutputStream.class, Map.class}, storage, bytes, Collections.singletonMap(profileId, "Synthetic auto-stop scooter"))), "Ride backup exports all four rides");
        for (RideStore.Summary s : store.list()) store.delete(s.id);
        check(new RideStore(folder).list().isEmpty(), "Rides removed before restore");
        Object prepared = call(archive, null, "stage", new Class<?>[]{storageType, InputStream.class}, storage, new ByteArrayInputStream(bytes.toByteArray()));
        Object plan = call(archive, null, "plan", new Class<?>[]{storageType, preparedType, Map.class}, storage, prepared, Collections.singletonMap(profileId, profileId));
        check((int) field(plan, "added") == 4, "Restore preview adds the four rides");
        call(archive, null, "apply", new Class<?>[]{storageType, planType}, storage, plan);
        RideStore reopened = new RideStore(folder);
        for (String id : automatic) { RideStore.Summary s = reopened.get(id); check(s != null && s.automaticStop && !s.active && s.scooterId.equals(profileId), "Restored ride keeps its automatic stop reason"); }
        check(!reopened.get(manual).automaticStop, "Restored rider stop stays the rider's");
        for (Map.Entry<String, byte[]> e : journals.entrySet()) check(Arrays.equals(e.getValue(), Files.readAllBytes(new File(folder, e.getKey() + ".csv").toPath())), "Restored journal bytes identical");
        Object again = call(archive, null, "plan", new Class<?>[]{storageType, preparedType, Map.class}, storage,
                call(archive, null, "stage", new Class<?>[]{storageType, InputStream.class}, storage, new ByteArrayInputStream(bytes.toByteArray())), Collections.singletonMap(profileId, profileId));
        check((int) field(again, "identical") == 4 && (int) field(again, "added") == 0, "Reimport is idempotent");
        call(archive, null, "apply", new Class<?>[]{storageType, planType}, storage, again);
    }

    /** Full app backups reject a mistyped End parked rides value before replacing live state. */
    private void preferenceTypes() throws Exception {
        Class<?> archive = Class.forName("local.navee.offlinelock.AppDataArchive");
        for (Object value : new Object[]{Boolean.TRUE, Boolean.FALSE, "true", 1}) {
            Map<String, Map<String, Object>> all = new HashMap<>();
            for (String name : new String[]{"app_settings", "profiles", "scooter", "control_evidence", "onboarding"}) all.put(name, new HashMap<>());
            all.get("app_settings").put("ride_auto_stop", value);
            boolean rejected = false;
            try { call(archive, null, "validatePreferenceTypes", new Class<?>[]{Map.class}, all); } catch (IOException expected) { rejected = true; }
            check(rejected == !(value instanceof Boolean), "Full backup type check for ride_auto_stop=" + value + " (" + value.getClass().getSimpleName() + ")");
        }
    }

    private void learnedRange() throws Exception {
        ScooterService service = activity.service(); AtomicReference<LearnedRangeEstimate.Result> estimate = new AtomicReference<>();
        main(() -> service.requestLearnedRangeEstimate(estimate::set)); await(() -> estimate.get() != null, "Range analysis finished");
        LearnedRangeEstimate.Result r = estimate.get();
        check(r.rides == 3 && r.rejectedRides == 1, "Learned range trains on the three automatic rides and rejects the hand-stopped tail: rides=" + r.rides + " rejected=" + r.rejectedRides);
        check(r.fullRangeKm != null && Math.abs(r.fullRangeKm - 50) < .001 && Math.abs(r.distanceKm - 12) < 1e-6 && Math.abs(r.batteryPoints - 24) < 1e-6,
                "Parked tail adds no distance or battery use: range=" + r.fullRangeKm + " km=" + r.distanceKm + " points=" + r.batteryPoints);
    }

    private void details() throws Exception {
        main(() -> invoke(activity, "select", new Class[]{String.class}, "rides")); settle();
        RidesScreen screen = (RidesScreen) field(activity, "screen"); boolean wide = (boolean) field(screen, "wide");
        main(() -> invoke(screen, wide ? "selectRide" : "showDetails", new Class[]{String.class}, automatic.get(2))); settle();
        TextView pill = text(rideRoot(screen), "Saved automatically"); check(pill != null, "Ride details show Saved automatically");
        check("Saved automatically after Bluetooth was lost while parked".contentEquals(pill.getContentDescription()), "Pill explains itself to screen readers");
        main(() -> pill.requestRectangleOnScreen(new Rect(0, -activity.ui().dp(160), pill.getWidth(), pill.getHeight() + activity.ui().dp(120)), true)); settle();
        fullyShown(pill, "Saved automatically pill");
        View parent = (View) pill.getParent(); Rect p = new Rect(), own = new Rect(); parent.getGlobalVisibleRect(p); pill.getGlobalVisibleRect(own);
        check(own.left >= p.left && own.right <= p.right, "Pill sits inside the details column");
        screenshot("details-automatic");
        if (wide) { main(() -> invoke(screen, "selectRide", new Class[]{String.class}, manual)); }
        else { main(() -> ((AlertDialog) ((List<?>) field(screen, "dialogs")).get(((List<?>) field(screen, "dialogs")).size() - 1)).dismiss()); settle(); main(() -> invoke(screen, "showDetails", new Class[]{String.class}, manual)); }
        settle(); check(text(rideRoot(screen), "Saved automatically") == null, "A rider's stop has no automatic pill");
        if (!wide) { main(() -> { List<?> dialogs = (List<?>) field(screen, "dialogs"); for (Object d : new ArrayList<>(dialogs)) ((AlertDialog) d).dismiss(); }); settle(); }
        screenshot("history");
    }
    private View rideRoot(RidesScreen screen) { List<?> dialogs = (List<?>) field(screen, "dialogs"); return dialogs.isEmpty() ? screen.view() : ((AlertDialog) dialogs.get(dialogs.size() - 1)).getWindow().getDecorView(); }

    private void preference() throws Exception {
        SharedPreferences settings = getTargetContext().getSharedPreferences("app_settings", 0);
        check(!settings.contains(ScooterServiceKeys.AUTO_STOP), "Preference absent on a fresh install");
        main(() -> { invoke(activity, "select", new Class[]{String.class}, "more"); invoke(field(activity, "screen"), "selectSection", new Class[]{String.class}, "App"); }); settle();
        View root = activity.getWindow().getDecorView();
        TextView title = text(root, "End parked rides"); check(title != null, "End parked rides row present");
        TextView sub = text(root, "10 s after Bluetooth drops at a stop"); check(sub != null, "Row explains the rule");
        View row = (View) title.getParent().getParent(); Switch toggle = find(row, Switch.class); check(toggle != null, "Row has a switch");
        TextView heading = text(root, "Connection & recording"); check(heading != null, "Connection & recording heading present");
        centre(row);
        fullyShown(title, "Preference title"); fullyShown(sub, "Preference detail"); fullyShown(toggle, "Preference switch");
        Rect t = new Rect(), s = new Rect(), w = new Rect(); title.getGlobalVisibleRect(t); sub.getGlobalVisibleRect(s); toggle.getGlobalVisibleRect(w);
        check(t.right <= w.left && s.right <= w.left && t.bottom <= s.top + 1, "Title, detail and switch do not overlap");
        check(toggle.isChecked() && toggle.isEnabled() && toggle.getHeight() >= activity.ui().dp(48), "Default on, enabled, 48dp switch target");
        check("End parked rides".contentEquals(toggle.getContentDescription()), "Switch is named for screen readers");
        screenshot("preference-on");
        main(row::performClick); settle();
        check(!toggle.isChecked() && settings.contains(ScooterServiceKeys.AUTO_STOP) && !settings.getBoolean(ScooterServiceKeys.AUTO_STOP, true), "Row tap turns the preference off");
        File xml = new File(getTargetContext().getDataDir(), "shared_prefs/app_settings.xml");
        await(() -> { try { return new String(Files.readAllBytes(xml.toPath()), "UTF-8").contains("name=\"ride_auto_stop\" value=\"false\""); } catch (IOException e) { return false; } }, "Off choice written to disk");
        screenshot("preference-off");
        main(() -> { invoke(field(activity, "screen"), "selectSection", new Class[]{String.class}, "Data"); invoke(field(activity, "screen"), "selectSection", new Class[]{String.class}, "App"); }); settle();
        Switch again = find((View) text(activity.getWindow().getDecorView(), "End parked rides").getParent().getParent(), Switch.class);
        check(again != null && !again.isChecked(), "Reopened section still shows off");
        main(() -> ((View) again.getParent()).performClick()); settle();
        check(again.isChecked() && settings.getBoolean(ScooterServiceKeys.AUTO_STOP, false), "Turning it back on persists");
    }
    /** Scrolls the row to mid-screen so the floating navigation bar cannot cover it, then checks that. */
    private void centre(View row) {
        main(() -> { View v = row; while (v != null && !(v instanceof ScrollView)) v = v.getParent() instanceof View ? (View) v.getParent() : null;
            check(v != null, "Preference row scrolls"); ScrollView scroll = (ScrollView) v; Rect r = new Rect(); row.getDrawingRect(r); scroll.offsetDescendantRectToMyCoords(row, r);
            scroll.scrollTo(0, Math.max(0, r.centerY() - scroll.getHeight() / 2)); }); settle();
        int[] at = new int[2]; row.getLocationOnScreen(at); int screen = activity.getWindow().getDecorView().getHeight();
        // Compact widths float the navigation bar over the content; wide layouts use a side rail.
        int clearance = activity.getResources().getConfiguration().screenWidthDp >= 600 ? 24 : 140;
        check(at[1] + row.getHeight() <= screen - activity.ui().dp(clearance), "Row clear of the navigation bar and gesture area");
    }
    /** The service's key, read from the class so a rename fails here instead of passing silently. */
    private static final class ScooterServiceKeys { static final String AUTO_STOP = readKey(); private static String readKey() { try { Field f = ScooterService.class.getDeclaredField("RIDE_AUTO_STOP"); f.setAccessible(true); return (String) f.get(null); } catch (Exception e) { throw new IllegalStateException(e); } } }

    /** Posts the real notice through the service's private method; the shipped app gains no hook. */
    private void notice() throws Exception {
        Context context = getTargetContext(); NotificationManager manager = context.getSystemService(NotificationManager.class);
        check(manager.areNotificationsEnabled(), "Runner granted POST_NOTIFICATIONS");
        String id = automatic.get(2); ScooterService service = activity.service();
        main(() -> invoke(service, "showAutoStopNotice", new Class[]{String.class}, id));
        await(() -> notice(manager) != null, "Ride saved automatically notice posted");
        Notification n = notice(manager).getNotification();
        check("Ride saved automatically".contentEquals(n.extras.getCharSequence(Notification.EXTRA_TITLE)), "Notice title");
        check("Parked campus ride 3 · Bluetooth lost while parked".contentEquals(n.extras.getCharSequence(Notification.EXTRA_TEXT)), "Notice names the ride and the reason: " + n.extras.getCharSequence(Notification.EXTRA_TEXT));
        check((n.flags & Notification.FLAG_AUTO_CANCEL) != 0 && (n.flags & Notification.FLAG_ONGOING_EVENT) == 0 && n.contentIntent != null, "Notice is dismissable, auto-cancels and opens Volt");
        main(() -> invoke(service, "cancelAutoStopNotice", new Class[0]));
        await(() -> notice(manager) == null, "The service clears the notice (as startRide does)");
        // Left posted for the runner, which opens the shade and captures it after this process ends.
        main(() -> invoke(service, "showAutoStopNotice", new Class[]{String.class}, id));
        await(() -> notice(manager) != null, "Notice reposted for the shade capture");
    }
    private static StatusBarNotification notice(NotificationManager manager) { for (StatusBarNotification n : manager.getActiveNotifications()) if (n.getId() == 72) return n; return null; }

    private void fullyShown(View view, String what) {
        Rect r = new Rect(); check(view.isShown() && view.getGlobalVisibleRect(r) && r.width() == view.getWidth() && r.height() == view.getHeight(), what + " fully visible, not clipped");
        if (view instanceof TextView) { Layout layout = ((TextView) view).getLayout(); check(layout != null && layout.getEllipsisCount(layout.getLineCount() - 1) == 0 && layout.getHeight() <= view.getHeight() - view.getPaddingTop() - view.getPaddingBottom() + 1, what + " text not ellipsized or cut"); }
    }
    private void screenshot(String label) throws Exception {
        File dir = new File(getTargetContext().getExternalFilesDir(null), "auto-stop-validation"); dir.mkdirs();
        File file = new File(dir, args.getString("variant", "auto-stop") + "-" + label + ".png");
        android.graphics.Bitmap bitmap = getUiAutomation().takeScreenshot();
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); } finally { bitmap.recycle(); }
        screenshots.add(file.getAbsolutePath());
    }
    private void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private void await(BooleanSupplier test, String why) { long end = SystemClock.elapsedRealtime() + 15000; while (SystemClock.elapsedRealtime() < end) { if (test.getAsBoolean()) { checks++; return; } SystemClock.sleep(50); } throw new AssertionError(why); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(350); waitForIdleSync(); }
    private void main(Runnable action) { AtomicReference<Throwable> failure = new AtomicReference<>(); runOnMainSync(() -> { try { action.run(); } catch (Throwable e) { failure.set(e); } }); if (failure.get() != null) throw new IllegalStateException(failure.get()); }
    private static TextView text(View view, String label) { if (view instanceof TextView && label.contentEquals(((TextView) view).getText()) && view.isShown()) return (TextView) view; if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { TextView r = text(((ViewGroup) view).getChildAt(i), label); if (r != null) return r; } return null; }
    private static <T> T find(View view, Class<T> type) { if (type.isInstance(view)) return type.cast(view); if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { T r = find(((ViewGroup) view).getChildAt(i), type); if (r != null) return r; } return null; }
    private static Object field(Object target, String name) { try { Class<?> c = target.getClass(); while (c != null) { try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch (NoSuchFieldException e) { c = c.getSuperclass(); } } throw new NoSuchFieldException(name); } catch (Exception e) { throw new IllegalStateException(name, e); } }
    private static Object invoke(Object target, String name, Class<?>[] types, Object... values) { try { Method m = target.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(target, values); } catch (Exception e) { throw new IllegalStateException(name, e); } }
    private static Object call(Class<?> type, Object object, String name, Class<?>[] types, Object... values) throws Exception { Method m = type.getDeclaredMethod(name, types); m.setAccessible(true); try { return m.invoke(object, values); } catch (InvocationTargetException e) { if (e.getCause() instanceof Exception) throw (Exception) e.getCause(); throw (Error) e.getCause(); } }
}
