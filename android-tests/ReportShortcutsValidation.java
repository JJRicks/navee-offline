package com.jjricks.volt.tests;

import android.Manifest;
import android.app.Instrumentation;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import com.jjricks.volt.MainActivity;
import com.jjricks.volt.ScooterProfiles;

/**
 * Fresh disposable emulator only. Two shortcuts to Volt's report address: More's Report an issue
 * drafts an email (intercepted in-process, never sent) with versions, phone and scooter model but no
 * fixture secrets; and the compatibility checker's Email to Volt, right under Run again once a check
 * has finished, asks which scooter this is and drafts the finished report to the alias.
 */
public final class ReportShortcutsValidation extends Instrumentation {
    private static final String MODEL = "39991", ADDRESS = "volt-reports@jjricks.com";
    private static final String FIXTURE_NAME = "Synthetic shortcut fixture", FIXTURE_IDENTITY = "A1B2C3D4E5F6",
            FIXTURE_SERIAL = "X2538-SYNTHETIC", FIXTURE_MAC = "C0:FF:EE:12:34:56";
    private Bundle arguments;
    private MainActivity activity;
    private int assertions;
    private final ArrayList<String> screenshots = new ArrayList<>();
    private final AtomicReference<Intent> started = new AtomicReference<>();

    @Override public void onCreate(Bundle args) { arguments = args == null ? new Bundle() : args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            admit();
            require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false).putBoolean("developer_options", true)
                    .putString("theme", arguments.getString("theme", "dark")).commit(), "Fixture settings failed");
            seed();
            // Intercept, never start, the email app and the share sheet.
            addMonitor(new ActivityMonitor() {
                @Override public ActivityResult onStartActivity(Intent intent) {
                    if (!Intent.ACTION_SENDTO.equals(intent.getAction()) && !Intent.ACTION_CHOOSER.equals(intent.getAction())) return null;
                    started.set(intent); return new ActivityResult(0, null);
                }
            });
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.service() != null, "App did not launch"); settle();
            main(() -> require(activity.service().setSimulation(true), "Simulation did not start")); settle();
            main(() -> activity.service().ble.setSimulationModel(MODEL)); settle();
            await(() -> activity.service().ble.ready(), "Simulated scooter did not connect");
            settle();

            reportIssue();
            checkerEmail();
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", error.toString()); error.printStackTrace();
        } finally {
            result.putInt("assertions", assertions); result.putString("screenshots", screenshots.toString());
            result.putBoolean("physical_device_tested", false);
            try { if (activity != null) main(() -> activity.finish()); } catch (Throwable ignored) { }
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    /** More's index ends with the version line, then Report an issue, which drafts the email. */
    private void reportIssue() throws Exception {
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "more")); settle();
        main(() -> {
            View link = root().findViewWithTag("more-report-issue"); require(link instanceof TextView && link.isShown(), "No Report an issue in More");
            ViewGroup parent = (ViewGroup) link.getParent(); View before = parent.getChildAt(parent.indexOfChild(link) - 1);
            require(before instanceof TextView && String.valueOf(((TextView) before).getText()).endsWith("not affiliated with NAVEE")
                    && "Report an issue".contentEquals(((TextView) link).getText()), "Report an issue is not right under the version line");
            link.requestRectangleOnScreen(new Rect(0, 0, link.getWidth(), link.getHeight()), true);
        });
        settle(); screenshot("more-report-issue");
        started.set(null);
        main(() -> root().findViewWithTag("more-report-issue").performClick()); settle();
        Intent mail = started.get(); require(mail != null, "Report an issue opened nothing");
        if (Intent.ACTION_CHOOSER.equals(mail.getAction())) mail = mail.getParcelableExtra(Intent.EXTRA_INTENT);
        String[] to = mail.getStringArrayExtra(Intent.EXTRA_EMAIL);
        String subject = mail.getStringExtra(Intent.EXTRA_SUBJECT), body = mail.getStringExtra(Intent.EXTRA_TEXT);
        require(to != null && to.length == 1 && ADDRESS.equals(to[0]), "The issue email is not addressed to the alias");
        require(subject != null && subject.startsWith("Volt issue report · Volt "), "Issue subject: " + subject);
        require(body != null && body.startsWith("What happened, and what did you expect instead?\n") && body.contains(" · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")")
                && body.contains(Build.MODEL) && body.endsWith("Scooter: Model " + MODEL + " (simulated)\n"), "Issue body: " + body);
        noSecrets(body, "issue email");
    }

    /** After a finished check, Email to Volt sits right under Run again; it asks which scooter this is, then drafts the report to the alias. */
    private void checkerEmail() throws Exception {
        main(() -> invoke(more(), "selectSection", new Class<?>[]{String.class}, "Compatibility")); settle();
        main(() -> require(byText(root(), "Run check") != null && root().findViewWithTag("compatibility-email") == null, "Email to Volt appeared before a check"));
        main(() -> byText(root(), "Run check").performClick());
        await(() -> { AtomicReference<Boolean> running = new AtomicReference<>(); main(() -> running.set(activity.service().ble.discovering())); return running.get(); }, "The check did not start");
        long end = SystemClock.uptimeMillis() + 40000;
        while (SystemClock.uptimeMillis() < end) { AtomicReference<Boolean> running = new AtomicReference<>(true); main(() -> running.set(activity.service().ble.discovering())); if (!running.get()) break; SystemClock.sleep(200); }
        // Body refreshes share a 1 Hz pulse: wait for the results to be drawn.
        await(() -> { AtomicReference<Boolean> drawn = new AtomicReference<>(false); main(() -> drawn.set(root().findViewWithTag("compatibility-email") != null)); return drawn.get(); }, "No Email to Volt after the check");
        main(() -> {
            View email = root().findViewWithTag("compatibility-email"); TextView again = byText(root(), "Run again");
            require(again != null && email.isShown() && email.getParent() == again.getParent(), "Email to Volt and Run again are apart");
            ViewGroup parent = (ViewGroup) email.getParent();
            require(parent.indexOfChild(email) == parent.indexOfChild(again) + 1 && "Email to Volt".contentEquals(((TextView) email).getText()), "Email to Volt is not right under Run again");
            email.requestRectangleOnScreen(new Rect(0, 0, email.getWidth(), email.getHeight()), true);
        });
        settle(); screenshot("checker-email");
        // Closing the sheet sends nothing and, unlike the offer, stores no answer.
        started.set(null);
        main(() -> root().findViewWithTag("compatibility-email").performClick()); settle();
        require(sheetShowing() && "Next".equals(sheetText("model-next")) && !sheet("model-next").isEnabled() && sheetText("model-status").startsWith("Check finished"),
                "Email to Volt did not ask which scooter this is");
        main(() -> { android.app.Dialog d = (android.app.Dialog) field(activity, "reportDialog"); d.cancel(); }); settle();
        require(!sheetShowing() && started.get() == null && settings().getString("report_invite_" + MODEL, "").isEmpty(), "Closing sent something or stored an answer");
        // Pick GT3 Pro, skip the drive modes: one email to the alias, opening with the owner's answer.
        main(() -> root().findViewWithTag("compatibility-email").performClick()); settle();
        main(() -> ((android.widget.EditText) sheet("model-search")).setText("gt3 pro")); settle();
        main(() -> sheet("model-gt3pro").performClick()); settle();
        require(sheet("model-next").isEnabled() && "Check finished ✓".equals(sheetText("model-status")), "Picking a model did not enable Next");
        screenshot("checker-email-picker");
        main(() -> sheet("model-next").performClick()); settle();
        await(() -> sheet("drive-mode-skip") != null, "The drive-mode step did not follow");
        main(() -> sheet("drive-mode-skip").performClick());
        end = SystemClock.uptimeMillis() + 15000;
        while (started.get() == null && SystemClock.uptimeMillis() < end) SystemClock.sleep(100);
        Intent mail = started.get(); require(mail != null, "Email to Volt opened no email");
        if (Intent.ACTION_CHOOSER.equals(mail.getAction())) mail = mail.getParcelableExtra(Intent.EXTRA_INTENT);
        String[] to = mail.getStringArrayExtra(Intent.EXTRA_EMAIL);
        String subject = mail.getStringExtra(Intent.EXTRA_SUBJECT), body = mail.getStringExtra(Intent.EXTRA_TEXT);
        require(to != null && to.length == 1 && ADDRESS.equals(to[0]) && ("Volt compatibility report · Model " + MODEL).equals(subject), "The report is not addressed to the alias: " + subject);
        require(body != null && body.startsWith("Scooter model, from the owner: GT3 Pro\n") && body.contains("\"format\": \"volt-compat-report-v1\"")
                && body.contains("\"pid\": \"" + MODEL + "\"") && body.contains("Simulated scooter (not real evidence)") && body.contains("\"format\": \"volt-owner-notes-v1\""), "Report email is incomplete");
        noSecrets(body, "report email");
        settle();
        require(!sheetShowing() && "sent".equals(settings().getString("report_invite_" + MODEL, "")), "A sent report did not close and persist");
    }

    private android.app.Dialog sheetDialog() { return (android.app.Dialog) field(activity, "reportDialog"); }
    private boolean sheetShowing() { AtomicReference<Boolean> open = new AtomicReference<>(false); main(() -> open.set(sheetDialog() != null && sheetDialog().isShowing())); return open.get(); }
    /** On the main thread: the view with this tag in the showing report sheet or drive-mode step. */
    private View sheet(String tag) {
        AtomicReference<View> found = new AtomicReference<>();
        Runnable look = () -> { android.app.Dialog d = sheetDialog(); found.set(d != null && d.isShowing() ? d.getWindow().getDecorView().findViewWithTag(tag) : null); };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) look.run(); else main(look);
        return found.get();
    }
    private String sheetText(String tag) { AtomicReference<String> text = new AtomicReference<>(""); main(() -> { View v = sheet(tag); if (v instanceof TextView) text.set(String.valueOf(((TextView) v).getText())); }); return text.get(); }

    private void noSecrets(String text, String where) {
        for (String secret : new String[]{FIXTURE_NAME, FIXTURE_IDENTITY, FIXTURE_SERIAL, FIXTURE_MAC, FIXTURE_MAC.replace(":", "")})
            require(!text.toUpperCase(Locale.ROOT).contains(secret.toUpperCase(Locale.ROOT)), "A fixture secret reached the " + where);
    }
    private void admit() {
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
        require(hardware.contains("ranchu") || hardware.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "Refusing a physical phone");
        for (String name : new String[]{"app_settings", "profiles", "scooter", "pending_documents", "control_evidence"})
            require(prefs(name).getAll().isEmpty(), "Refusing existing private preferences");
        File[] files = getTargetContext().getFilesDir().listFiles();
        require(files != null && files.length == 0 && getTargetContext().databaseList().length == 0, "Refusing existing private data");
        for (String permission : new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT})
            require(getTargetContext().checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED, "Grant Nearby permission on the emulator first");
        BluetoothManager manager = getTargetContext().getSystemService(BluetoothManager.class);
        require(manager == null || manager.getAdapter() == null || !manager.getAdapter().isEnabled(), "Emulator Bluetooth must stay off");
    }
    private void seed() {
        main(() -> {
            ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
            ScooterProfiles.Profile profile = profiles.create(FIXTURE_NAME, FIXTURE_IDENTITY, 0);
            profile.serial = FIXTURE_SERIAL; profile.mac = FIXTURE_MAC; profile.lastConnected = System.currentTimeMillis() - 60000; profiles.update(profile);
        });
        prefs("onboarding").edit().putBoolean("onboarding_done", true).commit();
    }
    private void screenshot(String label) throws Exception {
        File dir = new File(getTargetContext().getExternalFilesDir(null), "report-shortcuts-validation"); require(dir.isDirectory() || dir.mkdirs(), "Screenshot directory unavailable");
        File file = new File(dir, arguments.getString("theme", "dark") + "-" + label + ".png");
        android.graphics.Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "Screenshot unavailable");
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); } finally { bitmap.recycle(); }
        screenshots.add(file.getAbsolutePath());
    }
    private View root() { return (View) field(activity, "root"); }
    private Object more() { Object screen = field(activity, "screen"); require(screen != null && screen.getClass().getSimpleName().equals("MoreScreen"), "More screen is not active"); return screen; }
    private TextView byText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText()) && view.isShown()) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { TextView found = byText(((ViewGroup) view).getChildAt(i), text); if (found != null) return found; }
        return null;
    }
    private SharedPreferences settings() { return prefs("app_settings"); }
    private SharedPreferences prefs(String name) { return getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(300); waitForIdleSync(); }
    private void main(Runnable task) {
        AtomicReference<Throwable> error = new AtomicReference<>();
        runOnMainSync(() -> { try { task.run(); } catch (Throwable failure) { error.set(failure); } });
        if (error.get() != null) throw new IllegalStateException(error.get());
    }
    private void await(BooleanSupplier condition, String message) {
        long end = SystemClock.uptimeMillis() + 10000;
        while (!condition.getAsBoolean() && SystemClock.uptimeMillis() < end) SystemClock.sleep(50);
        require(condition.getAsBoolean(), message);
    }
    private void require(boolean condition, String message) { assertions++; if (!condition) throw new IllegalStateException(message); }
    private Object field(Object target, String name) {
        try { Class<?> type = target.getClass(); while (type != null) { try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch (NoSuchFieldException next) { type = type.getSuperclass(); } }
            throw new NoSuchFieldException(name);
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    private Object invoke(Object target, String name, Class<?>[] types, Object... args) {
        try { Method m = target.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(target, args); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
}
