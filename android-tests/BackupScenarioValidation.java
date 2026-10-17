package local.navee.offlinelock.tests;

import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import java.io.*;
import java.util.*;
import local.navee.offlinelock.*;

/**
 * Disposable emulator only; synthetic data; no service, Bluetooth or scooter commands.
 * Seeds two app states for driving Back up & restore by hand on screen, and reports what a
 * restore left behind.
 *
 * -e mode source   fresh app: two scooters ("Old UT5", "Spare scooter"), a dashboard, changed
 *                  app preferences, 3 rides and 15 charges; writes scenario-*.voltbackup files
 *                  (complete, rides, preferences, dashboards, charges) plus truncated and
 *                  corrupted copies of the complete file to the app's external files folder.
 * -e mode target   fresh app: one scooter "Fold UT5" with another serial, 1 ride, 12 charges.
 * -e mode inspect  read-only summary of scooters, rides, charges, units, theme and dashboards.
 * -e mode render [-e theme dark|light]  fresh app: seeds the target phone, then opens Restore with
 *                  a stubbed picker (scenario-corrupted, then scenario-complete pushed to the external
 *                  files folder) and renders the bad-file, category, destination and merge review
 *                  screens to restore-*.png. Closes without applying. FLAG_SECURE blocks screencap,
 *                  so these in-process renders are the visual record of the screen.
 */
public final class BackupScenarioValidation extends Instrumentation {
    static final char[] PASSWORD = "synthetic scenario password".toCharArray();
    private String mode, theme = "dark"; private int assertions; private Context app; private android.app.Activity current;
    @Override public void onCreate(Bundle args) { mode = args == null ? null : args.getString("mode"); if (args != null) theme = args.getString("theme", "dark"); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
            require(hardware.contains("ranchu") || hardware.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "Refusing a physical phone");
            app = getTargetContext();
            if ("source".equals(mode)) { fresh(); source(result); flush(); }
            else if ("target".equals(mode)) { fresh(); target(); flush(); }
            else if ("inspect".equals(mode)) inspect(result);
            else if ("render".equals(mode)) { fresh(); target(); flush(); render(result); }
            else throw new IllegalArgumentException("Use mode source, target or inspect");
            result.putString("result", "PASS");
        } catch (Throwable failure) { result.putString("result", "FAIL"); result.putString("error", failure.toString()); }
        if (current != null) { final android.app.Activity open = current; runOnMainSync(open::finishAndRemoveTask); }
        result.putInt("assertions", assertions); result.putInt("scooter_mutations", 0);
        finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
    }
    private void fresh() {
        for (String name : new String[]{"app_settings", "profiles", "scooter", "control_evidence", "onboarding", "pending_documents", "owner_capture_cleanup"})
            require(prefs(name).getAll().isEmpty(), "Refusing existing private preferences; clear this disposable emulator's app storage first");
        File[] files = app.getFilesDir().listFiles();
        require(files != null && files.length == 0 && app.databaseList().length == 0, "Refusing existing private files");
    }
    private void source(Bundle result) throws Exception {
        require(prefs("app_settings").edit().putString("units", "mi").putString("time_format", "24h").putBoolean("developer_options", true).commit(), "Store preferences");
        ScooterProfiles profiles = new ScooterProfiles(app);
        ScooterProfiles.Profile old = scooter(profiles, "Old UT5", "0A0B0C0D0E01", "SRC-0001"), spare = scooter(profiles, "Spare scooter", "0A0B0C0D0E02", "SRC-0002");
        profiles.select(old.id);
        require(prefs("onboarding").edit().putBoolean("onboarding_done", true).commit(), "Finish onboarding");
        DashboardConfig dashboard = new DashboardConfig(prefs("app_settings"), old.id); dashboard.widgets.clear();
        DashboardConfig.Widget gauge = new DashboardConfig.Widget("gauge"); gauge.metric = "speed_kmh"; gauge.title = "Scenario gauge";
        for (int columns = 1; columns <= 6; columns++) gauge.layouts.put(columns, new DashboardLayout.Cell(gauge.id, 0, 0, columns, 3));
        dashboard.widgets.add(gauge); dashboard.save(); require(dashboard.saveSlot(0, "Scenario slot", 4), "Save slot");
        long now = System.currentTimeMillis();
        ride(old.id, "Old ride one", now - 5 * 86400000L); ride(old.id, "Old ride two", now - 4 * 86400000L); ride(spare.id, "Spare ride", now - 3 * 86400000L);
        for (int i = 0; i < 15; i++) charge(old.id, now - (40 - i) * 3600000L);
        File external = app.getExternalFilesDir(null); require(external != null, "External files folder unavailable");
        String[][] sets = {{"complete"}, {"rides", "rides"}, {"preferences", "preferences"}, {"dashboards", "dashboards"}, {"charges", "charges"}};
        for (String[] set : sets) {
            File file = new File(external, "scenario-" + set[0] + ".voltbackup");
            try (FileOutputStream out = new FileOutputStream(file)) {
                if (set.length == 1) AppDataBackup.write(app, out, PASSWORD); else AppDataBackup.write(app, out, PASSWORD, Collections.singleton(set[1]));
            }
            require(file.length() > 0, "Empty " + file.getName());
        }
        byte[] complete = java.nio.file.Files.readAllBytes(new File(external, "scenario-complete.voltbackup").toPath());
        try (FileOutputStream out = new FileOutputStream(new File(external, "scenario-truncated.voltbackup"))) { out.write(complete, 0, complete.length / 2); }
        byte[] corrupted = complete.clone(); corrupted[corrupted.length / 2] ^= 0x5a;
        try (FileOutputStream out = new FileOutputStream(new File(external, "scenario-corrupted.voltbackup"))) { out.write(corrupted); }
        result.putString("written", "complete, rides, preferences, dashboards, charges, truncated, corrupted");
    }
    private void target() throws Exception {
        require(prefs("app_settings").edit().putString("units", "km").commit(), "Store preferences");
        ScooterProfiles profiles = new ScooterProfiles(app);
        ScooterProfiles.Profile mine = scooter(profiles, "Fold UT5", "0A0B0C0D0E09", "LOCAL-0009"); profiles.select(mine.id);
        require(prefs("onboarding").edit().putBoolean("onboarding_done", true).commit(), "Finish onboarding");
        long now = System.currentTimeMillis();
        ride(mine.id, "Local ride", now - 2 * 86400000L);
        for (int i = 0; i < 12; i++) charge(mine.id, now - (80 - i) * 3600000L);
    }
    private void inspect(Bundle result) throws Exception {
        ScooterProfiles profiles = new ScooterProfiles(app);
        StringBuilder names = new StringBuilder();
        for (ScooterProfiles.Profile p : profiles.all()) {
            if (names.length() > 0) names.append("; ");
            names.append(p.name).append(" serial=").append(p.serial).append(" dashboard=").append(prefs("app_settings").contains("dashboard_v1_" + p.id));
        }
        Map<String, Integer> rides = new TreeMap<>();
        for (RideStore.Summary info : new RideStore(new File(app.getFilesDir(), "rides")).list()) {
            ScooterProfiles.Profile p = null; for (ScooterProfiles.Profile q : profiles.all()) if (q.id.equals(info.scooterId)) p = q;
            rides.merge(p == null ? "unknown:" + info.scooterId : p.name, 1, Integer::sum);
        }
        File[] charges = new File(app.getFilesDir(), "charge-recordings").listFiles();
        result.putString("scooters", names.toString());
        result.putString("rides", rides.toString());
        result.putInt("charges", charges == null ? 0 : charges.length);
        result.putString("units", prefs("app_settings").getString("units", "(default)"));
        result.putString("time_format", prefs("app_settings").getString("time_format", "(default)"));
        result.putBoolean("developer_options", prefs("app_settings").getBoolean("developer_options", false));
        result.putBoolean("transfer_left", AppDataBackup.needsRecovery(app));
    }

    private void render(Bundle result) throws Exception {
        File external = app.getExternalFilesDir(null);
        File bad = new File(app.getCacheDir(), "picked-corrupted.voltbackup"), good = new File(app.getCacheDir(), "picked-complete.voltbackup");
        java.nio.file.Files.copy(new File(external, "scenario-corrupted.voltbackup").toPath(), bad.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        java.nio.file.Files.copy(new File(external, "scenario-complete.voltbackup").toPath(), good.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        require(prefs("app_settings").edit().putString("theme", theme).putBoolean("connect_on_open", false).commit(), "Store theme");
        ActivityMonitor mainMonitor = addMonitor(MainActivity.class.getName(), null, false);
        MainActivity main = (MainActivity) startActivitySync(new android.content.Intent(app, MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); current = main;
        long limit = android.os.SystemClock.elapsedRealtime() + 10000;
        while (Boolean.TRUE.equals(field(main, "startupRedirect")) && android.os.SystemClock.elapsedRealtime() < limit) { android.app.Activity next = waitForMonitorWithTimeout(mainMonitor, 500); if (next instanceof MainActivity) { main = (MainActivity) next; current = main; } }
        removeMonitor(mainMonitor); final MainActivity activity = main; await(() -> activity.service() != null, "Service did not open");
        ActivityMonitor backupMonitor = addMonitor(BackupActivity.class.getName(), null, false);
        runOnMainSync(() -> activity.dataBackup(true, null)); waitForIdleSync();
        android.app.AlertDialog consent = (android.app.AlertDialog) field(activity, "controlDialog");
        runOnMainSync(() -> consent.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick());
        android.app.Activity opened = waitForMonitorWithTimeout(backupMonitor, 20000); require(opened instanceof BackupActivity, "Restore screen did not open");
        BackupActivity backup = (BackupActivity) opened; current = backup;
        await(() -> view(backup, "restore-choose") != null, "Choose file missing");
        pick(backup, "restore-choose", bad); await(() -> view(backup, "restore-open") != null, "Password step missing");
        password(backup); await(() -> text(backup, "Wrong password or bad file.") , "Damaged file was not refused");
        require(view(backup, "restore-choose-again") != null, "No way to choose another file after a bad one");
        shot(backup, "restore-bad-file.png");
        pick(backup, "restore-choose-again", good); password(backup); await(() -> view(backup, "restore-next") != null, "Category list missing");
        require(view(backup, "restore-replace-all") != null && !((android.widget.Switch) view(backup, "restore-replace-all")).isChecked(), "A phone with data defaults to Merge");
        shot(backup, "restore-categories.png");
        click(backup, "restore-next"); await(() -> view(backup, "restore-review") != null, "Destination step missing");
        List<android.widget.Spinner> spinners = new ArrayList<>(); spinners(backup.getWindow().getDecorView(), spinners);
        require(spinners.size() == 2, "Two backup scooters need a destination");
        select(spinners.get(0), "Fold UT5"); select(spinners.get(1), "Add from backup");
        require("Destination for Old UT5: Fold UT5".contentEquals(spinners.get(0).getContentDescription()) && "Destination for Spare scooter: Add from backup".contentEquals(spinners.get(1).getContentDescription()), "TalkBack hears each destination choice: " + spinners.get(0).getContentDescription());
        shot(backup, "restore-destinations.png");
        click(backup, "restore-review"); await(() -> view(backup, "restore-apply") != null, "Review missing");
        require(text(backup, "Charges: 8 new · 0 already here · 0 kept here") && text(backup, "7 charges skipped · 20-charge limit") && text(backup, "Dashboards: replaces layouts for Fold UT5"), "Review lines");
        shot(backup, "restore-review.png");
        require(new File(app.getFilesDir(), "charge-recordings").listFiles().length == 12, "Review changed nothing");
        bad.delete(); good.delete();
        result.putString("renders", "restore-bad-file, restore-categories, restore-destinations, restore-review");
    }
    private void pick(BackupActivity backup, String tag, File file) {
        android.content.IntentFilter filter = new android.content.IntentFilter(android.content.Intent.ACTION_OPEN_DOCUMENT); filter.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        try { filter.addDataType("*/*"); } catch (android.content.IntentFilter.MalformedMimeTypeException impossible) { throw new AssertionError(impossible); }
        ActivityMonitor picker = addMonitor(filter, new ActivityResult(android.app.Activity.RESULT_OK, new android.content.Intent().setData(android.net.Uri.fromFile(file))), true);
        click(backup, tag); await(() -> picker.getHits() > 0, "Picker not used"); removeMonitor(picker);
    }
    private void password(BackupActivity backup) throws Exception {
        await(() -> view(backup, "restore-open") != null && !Boolean.TRUE.equals(fieldQuiet(backup, "busy")), "Password step missing");
        android.widget.EditText field = (android.widget.EditText) field(backup, "password");
        runOnMainSync(() -> field.setText(new String(PASSWORD))); waitForIdleSync(); click(backup, "restore-open");
        await(() -> !Boolean.TRUE.equals(fieldQuiet(backup, "busy")), "Checking did not finish");
    }
    private void select(android.widget.Spinner spinner, String item) {
        for (int i = 0; i < spinner.getCount(); i++) if (item.equals(String.valueOf(spinner.getItemAtPosition(i)))) { final int index = i; runOnMainSync(() -> spinner.setSelection(index)); waitForIdleSync(); return; }
        throw new AssertionError("No destination " + item);
    }
    private void click(BackupActivity backup, String tag) { runOnMainSync(() -> view(backup, tag).performClick()); waitForIdleSync(); }
    private static android.view.View view(BackupActivity backup, String tag) { return backup.getWindow().getDecorView().findViewWithTag(tag); }
    private static boolean text(BackupActivity backup, String value) { return find(backup.getWindow().getDecorView(), value); }
    private static boolean find(android.view.View v, String value) {
        if (v instanceof android.widget.TextView && value.contentEquals(((android.widget.TextView) v).getText())) return true;
        if (v instanceof android.view.ViewGroup) for (int i = 0; i < ((android.view.ViewGroup) v).getChildCount(); i++) if (find(((android.view.ViewGroup) v).getChildAt(i), value)) return true;
        return false;
    }
    private static void spinners(android.view.View v, List<android.widget.Spinner> out) {
        if (v instanceof android.widget.Spinner) out.add((android.widget.Spinner) v);
        else if (v instanceof android.view.ViewGroup) for (int i = 0; i < ((android.view.ViewGroup) v).getChildCount(); i++) spinners(((android.view.ViewGroup) v).getChildAt(i), out);
    }
    /** The whole scroll content, not just the visible window, so nothing below the fold is missed. */
    private void shot(BackupActivity backup, String name) throws Exception {
        waitForIdleSync();
        android.view.View content = (android.view.View) field(backup, "content");
        Exception[] failure = new Exception[1];
        runOnMainSync(() -> {
            try {
                android.view.View scroll = (android.view.View) content.getParent();
                int height = Math.max(scroll.getHeight(), content.getHeight());
                android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(scroll.getWidth(), height, android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap); canvas.drawColor(((android.graphics.drawable.ColorDrawable) scroll.getBackground()).getColor());
                canvas.translate(scroll.getPaddingLeft(), scroll.getPaddingTop()); content.draw(canvas);
                try (FileOutputStream out = new FileOutputStream(new File(app.getExternalFilesDir(null), name))) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); }
                bitmap.recycle();
            } catch (Exception e) { failure[0] = e; }
        });
        if (failure[0] != null) throw failure[0];
    }
    private void await(java.util.function.BooleanSupplier ready, String message) { long end = android.os.SystemClock.elapsedRealtime() + 20000; while (android.os.SystemClock.elapsedRealtime() < end) { if (ready.getAsBoolean()) return; android.os.SystemClock.sleep(50); } throw new AssertionError(message); }
    private static Object field(Object object, String name) throws Exception { java.lang.reflect.Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object); }
    private static Object fieldQuiet(Object object, String name) { try { return field(object, name); } catch (Exception e) { throw new RuntimeException(e); } }
    private ScooterProfiles.Profile scooter(ScooterProfiles profiles, String name, String identity, String serial) {
        ScooterProfiles.Profile p = profiles.create(name, identity, 0); p.serial = serial; profiles.update(p); return p;
    }
    private void ride(String profile, String name, long wall) throws Exception {
        RideStore store = new RideStore(new File(app.getFilesDir(), "rides")); long mono = 100000;
        store.start(profile, name, wall, mono);
        for (int i = 0; i < 3; i++) {
            long at = mono + i * 2000; Map<String, RideStore.Sample> samples = new LinkedHashMap<>();
            samples.put("battery_percent", new RideStore.Sample(80d - i, at)); samples.put("speed_kmh", new RideStore.Sample(18d, at));
            samples.put("odometer_km", new RideStore.Sample(100 + i * .01, at)); store.sample(wall + i * 2000, at, samples);
        }
        store.stop(wall + 6000, mono + 6000);
    }
    private void charge(String profile, long wall) throws Exception {
        ChargeSessionStore store = new ChargeSessionStore(new File(app.getFilesDir(), "charge-recordings")); long mono = 100000;
        store.start(profile, ChargeSessionStore.Charger.STOCK, wall, mono);
        for (int i = 0; i < 3; i++) { long at = mono + i * 10000; require(store.sample(new ChargeSessionStore.Frame(wall + i * 10000, at, value(1d, at), value(50d + i, at), value(52d, at), value(-2d, at), value(23d, at))), "Write charge reading"); }
        store.stop(ChargeSessionStore.Stop.STOPPED);
    }
    private static ChargeSessionStore.Value value(double n, long at) { return new ChargeSessionStore.Value(n, at, 0x72); }
    /** ScooterProfiles saves with apply(); write every namespace synchronously before this process exits. */
    private void flush() {
        for (String name : new String[]{"app_settings", "profiles", "onboarding"}) {
            require(prefs(name).edit().putBoolean("scenario_flush", true).commit() && prefs(name).edit().remove("scenario_flush").commit(), "Flush " + name);
        }
    }
    private SharedPreferences prefs(String name) { return app.getSharedPreferences(name, Context.MODE_PRIVATE); }
    private void require(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
}
