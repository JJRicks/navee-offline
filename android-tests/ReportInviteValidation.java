package com.jjricks.volt.tests;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import com.jjricks.volt.ConnectionEventLog;
import com.jjricks.volt.MainActivity;
import com.jjricks.volt.ScooterProfiles;

/**
 * Fresh disposable emulator only. The "help Volt support this scooter" offer on the developer
 * simulator's unknown model (39991): Check & share runs the read-only check and hands the email
 * app a report addressed to Volt's alias (intercepted in-process, never sent), with no fixture
 * secrets in it; Not now, Don't ask again, Close and Try again behave and persist. The automatic offer itself
 * skips simulated scooters, so the flow is opened directly once that is confirmed.
 * Then, without the simulator, a never-connected scooter whose owner check timed out twice gets the
 * "Send connection details" offer on its own, and its email holds the steps but no fixture secrets.
 */
public final class ReportInviteValidation extends Instrumentation {
    private static final String MODEL = "39991", ADDRESS = "volt-reports@jjricks.com";
    // Synthetic private values: none of them may reach the email.
    private static final String FIXTURE_NAME = "Synthetic invite fixture", FIXTURE_IDENTITY = "A1B2C3D4E5F6",
            FIXTURE_SERIAL = "X2538-SYNTHETIC", FIXTURE_MAC = "C0:FF:EE:12:34:56";
    private Bundle arguments;
    private MainActivity activity;
    private int assertions;
    private final ArrayList<String> screenshots = new ArrayList<>();
    private final AtomicReference<Intent> emailed = new AtomicReference<>();

    @Override public void onCreate(Bundle args) { arguments = args == null ? new Bundle() : args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            admit();
            // The developer simulator needs Developer options.
            require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false).putBoolean("developer_options", true)
                    .putString("theme", arguments.getString("theme", "dark")).commit(), "Fixture settings failed");
            seed();
            // Intercept, never start, the email app; anything else starts normally.
            addMonitor(new ActivityMonitor() {
                @Override public ActivityResult onStartActivity(Intent intent) {
                    if (!Intent.ACTION_SENDTO.equals(intent.getAction()) && !Intent.ACTION_CHOOSER.equals(intent.getAction())) return null;
                    emailed.set(intent); return new ActivityResult(0, null);
                }
            });
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.service() != null, "App did not launch"); settle();
            main(() -> require(activity.service().setSimulation(true), "Simulation did not start")); settle();
            main(() -> activity.service().ble.setSimulationModel(MODEL)); settle();
            await(() -> activity.service().ble.ready(), "Simulated scooter did not connect");
            SystemClock.sleep(3500); settle();
            require(field(activity, "reportDialog") == null, "The automatic offer appeared for a simulated scooter");

            open(); screenshot("offer");
            click(AlertDialog.BUTTON_NEGATIVE); settle();
            require(!showing() && settings().getString("report_invite_" + MODEL, "").startsWith("later:"), "Not now did not close and wait");

            settings().edit().remove("report_invite_" + MODEL).commit();
            open(); click(AlertDialog.BUTTON_NEUTRAL); settle();
            require(!showing() && "never".equals(settings().getString("report_invite_" + MODEL, "")), "Don't ask again did not persist");

            settings().edit().remove("report_invite_" + MODEL).commit();
            open(); click(AlertDialog.BUTTON_POSITIVE); settle();
            require(activity.service().ble.discovering() && "Which scooter is this?".equals(firstText()) && "Next".equals(textOf("model-next")) && !view("model-next").isEnabled()
                    && status().startsWith("Checking your scooter"), "Check & share did not start the check in the picker sheet");
            // The list is the sheet: taller than its window, scrolling between the fixed title and footer.
            main(() -> {
                android.widget.ScrollView list = (android.widget.ScrollView) find("model-list"); View footer = find("model-next");
                require(list.getChildAt(0).getHeight() > list.getHeight() && list.getHeight() > footer.getHeight() * 4 && footer.isShown(), "The model list does not scroll in the sheet");
            });
            screenshot("checking");
            main(() -> ((android.widget.ScrollView) find("model-list")).fullScroll(View.FOCUS_DOWN)); settle();
            require(onScreen("model-k100plus") && !onScreen("model-ut3max"), "The end of the list is unreachable"); screenshot("list-end");
            main(() -> ((android.widget.ScrollView) find("model-list")).fullScroll(View.FOCUS_UP)); settle();
            // Turning the phone resizes the open sheet to the new window, keeping the same sheet, its answer and the check.
            android.app.Dialog sheet = dialog(); require(sheetFits(), "The sheet is not nine-tenths of Volt's window");
            getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_FREEZE_90);
            await(() -> landscape() && sheetFits(), "The sheet did not follow the turn to landscape");
            main(() -> find("model-unsure").performClick()); settle(); screenshot("landscape");
            getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_FREEZE_0);
            await(() -> !landscape() && sheetFits(), "The sheet kept its landscape height in portrait");
            require(dialog() == sheet && showing() && view("model-unsure").isSelected(), "Turning the phone lost the sheet or its answer");
            getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_UNFREEZE);
            // A check that stops (here, stopped directly) offers Try again, and answering the question keeps it available.
            main(() -> activity.service().ble.stopDiscovery());
            await(() -> !activity.service().ble.discovering() && "Try again".equals(textOf("model-next")) && view("model-next").isEnabled(), "A stopped check did not offer Try again");
            require(showing() && emailed.get() == null && status().contains("stopped"), "A stopped check sent a report or closed the sheet");
            main(() -> find("model-unsure").performClick()); settle();
            require("Try again".equals(textOf("model-next")) && view("model-next").isEnabled() && find("model-unsure").isSelected(), "Answering disabled Try again");
            main(() -> find("model-gt3").performClick()); settle();
            require("Try again".equals(textOf("model-next")) && view("model-next").isEnabled() && !view("model-unsure").isSelected(), "Changing the answer disabled Try again");

            // The same guard Check & share uses: the recording flag that rides, charge capture and charge measurement set blocks it.
            main(() -> {
                Object ble = activity.service().ble; require((Boolean) invoke(ble, "discoveryAllowed", new Class<?>[0]), "A connected idle scooter cannot be checked");
                setField(ble, "recording", true); require(!(Boolean) invoke(ble, "discoveryAllowed", new Class<?>[0]), "Recording (charge capture or measurement) did not block the check");
                setField(ble, "recording", false);
            });

            // A check that finishes while Volt is in the background waits for Volt to return before opening email.
            settings().edit().putBoolean("background_connection", true).commit();
            main(() -> find("model-unsure").performClick()); settle();
            tap("model-next"); settle();
            require(activity.service().ble.discovering() && !view("model-next").isEnabled() && status().startsWith("Checking"), "Try again did not restart the check");
            main(() -> activity.moveTaskToBack(true)); settle();
            await(() -> !activity.service().ble.discovering(), "The check did not finish in the background");
            SystemClock.sleep(1500);
            require(emailed.get() == null && !"sent".equals(settings().getString("report_invite_" + MODEL, "")), "Email opened while Volt was in the background");
            getTargetContext().startActivity(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            // Back in front: the check is done, and Next waits for the owner to say which scooter this is.
            await(() -> status().startsWith("Check finished"), "The finished check was not shown after Volt returned");
            require(view("model-next").isEnabled() && "Check finished ✓".equals(status()) && emailed.get() == null, "Not sure, chosen while stopped, did not carry over");
            // Search with the keyboard up: the sheet keeps its title, results and footer in view.
            main(() -> {
                android.widget.EditText search = (android.widget.EditText) find("model-search"); search.requestFocus();
                activity.getSystemService(android.view.inputmethod.InputMethodManager.class).showSoftInput(search, 0); search.setText("gt3 pro");
            });
            SystemClock.sleep(1500); settle();
            require(view("model-gt3pro") != null && view("model-gt3") == null && view("model-typed") == null && view("model-unsure") == null, "Search did not narrow to GT3 Pro");
            screenshot("search-keyboard");
            main(() -> find("model-gt3pro").performClick()); settle();
            require(view("model-next").isEnabled() && find("model-gt3pro").isSelected(), "Picking a model did not select it");
            screenshot("model-picker");
            tap("model-next"); settle();
            // The owner presses the scooter's own mode button; the simulator's reported mode stands in for it.
            await(() -> "Which mode is your scooter in now?".equals(textOf("drive-mode-prompt")), "The drive-mode step did not ask about the current mode");
            screenshot("drive-modes");
            main(() -> find("drive-mode-sport").performClick()); settle();
            require(textOf("drive-mode-prompt").startsWith("Press your scooter's mode button"), "Volt did not ask for the next mode");
            simulateMode(2); await(() -> "And this mode?".equals(textOf("drive-mode-prompt")), "A changed mode was not asked about");
            require(!view("drive-mode-sport").isEnabled(), "A used name stayed available");
            // Disconnected, nothing can be named (not even by a click that gets through); after reconnecting, naming waits for a fresh reading.
            main(() -> require(activity.service().setSimulation(false), "Simulation did not stop"));
            await(() -> "Reconnect your scooter to keep going.".equals(textOf("drive-mode-prompt")) && !view("drive-mode-eco").isEnabled() && !view("drive-mode-unsure").isEnabled(),
                    "Naming stayed available while disconnected");
            main(() -> find("drive-mode-eco").performClick()); settle();
            require(!textOf("drive-mode-named").contains("Eco"), "An answer given while disconnected was saved");
            screenshot("drive-modes-disconnected");
            main(() -> require(activity.service().setSimulation(true), "Simulation did not restart")); settle();
            main(() -> activity.service().ble.setSimulationModel(MODEL)); settle();
            await(() -> activity.service().ble.ready(), "Simulated scooter did not reconnect");
            simulateMode(2); await(() -> "And this mode?".equals(textOf("drive-mode-prompt")) && view("drive-mode-eco").isEnabled(), "Naming did not resume after reconnecting");
            main(() -> find("drive-mode-eco").performClick()); settle();
            simulateMode(11); await(() -> "And this mode?".equals(textOf("drive-mode-prompt")), "The third mode was not asked about");
            main(() -> find("drive-mode-walk").performClick()); settle();
            simulateMode(3); await(() -> "Sport → Eco → Walk".equals(textOf("drive-mode-summary")), "Returning to the first mode did not offer the summary");
            screenshot("drive-modes-confirm");
            require(field(activity.service().ble, "mutation") == null, "The drive-mode step sent a command");
            main(() -> find("drive-mode-confirm").performClick());
            long end = SystemClock.uptimeMillis() + 15000;
            while (emailed.get() == null && SystemClock.uptimeMillis() < end) SystemClock.sleep(100);
            Intent mail = emailed.get(); require(mail != null, "No email was opened after the drive modes were confirmed");
            if (Intent.ACTION_CHOOSER.equals(mail.getAction())) mail = mail.getParcelableExtra(Intent.EXTRA_INTENT);
            String[] to = mail.getStringArrayExtra(Intent.EXTRA_EMAIL);
            String subject = mail.getStringExtra(Intent.EXTRA_SUBJECT), body = mail.getStringExtra(Intent.EXTRA_TEXT);
            require(to != null && to.length == 1 && ADDRESS.equals(to[0]), "Email is not addressed to the alias");
            require(("Volt compatibility report · Model " + MODEL).equals(subject), "Email subject: " + subject);
            require(body != null && body.contains("\"format\": \"volt-compat-report-v1\"") && body.contains("\"pid\": \"" + MODEL + "\""), "Email lacks the report");
            require(body.startsWith("Scooter model, from the owner: GT3 Pro\n") && body.contains("}\n\nThe owner's answers:\nDrive modes: Sport → Eco → Walk\n{")
                    && body.contains("\"format\": \"volt-owner-notes-v1\"") && body.contains("\"code\": 11") && body.contains("\"label\": \"walk\""), "Email lacks the owner's answers");
            for (String secret : new String[]{FIXTURE_NAME, FIXTURE_IDENTITY, FIXTURE_SERIAL, FIXTURE_MAC, FIXTURE_MAC.replace(":", "")})
                require(!body.toUpperCase(Locale.ROOT).contains(secret.toUpperCase(Locale.ROOT)), "A fixture secret reached the email");
            settle();
            require(!showing() && "sent".equals(settings().getString("report_invite_" + MODEL, "")), "A shared report did not close and persist");
            main(() -> require(activity.service().profiles.active().ownerModel.isEmpty(), "A check on the simulator kept its answer on the saved scooter"));
            settings().edit().putBoolean("background_connection", false).commit();

            // Recreating the Activity mid-check stops the check and sends nothing.
            settings().edit().remove("report_invite_" + MODEL).commit(); emailed.set(null);
            open(); click(AlertDialog.BUTTON_POSITIVE); settle();
            require(activity.service().ble.discovering(), "The check did not start before recreation");
            ActivityMonitor recreated = addMonitor(MainActivity.class.getName(), null, false);
            main(() -> activity.recreate());
            MainActivity next = (MainActivity) waitForMonitorWithTimeout(recreated, 10000); removeMonitor(recreated);
            require(next != null && next != activity, "The Activity was not recreated");
            activity = next; await(() -> activity.service() != null, "The recreated Activity did not bind");
            await(() -> !activity.service().ble.discovering(), "Recreation left the check running");
            SystemClock.sleep(1000);
            require(emailed.get() == null && settings().getString("report_invite_" + MODEL, "").isEmpty() && field(activity, "reportDialog") == null,
                    "Recreation sent a report, stored an answer or re-offered");

            // Not sure and Skip: the plain report, still asking for the name in the email.
            // Close stops the check, sends nothing and waits like Not now.
            settings().edit().remove("report_invite_" + MODEL).commit();
            open(); click(AlertDialog.BUTTON_POSITIVE); settle();
            require(activity.service().ble.discovering(), "The check did not start");
            main(() -> first(v -> "Close".equals(String.valueOf(v.getContentDescription()))).performClick()); settle();
            require(!showing() && !activity.service().ble.discovering() && emailed.get() == null && settings().getString("report_invite_" + MODEL, "").startsWith("later:"),
                    "Close did not stop the check and wait");
            settings().edit().remove("report_invite_" + MODEL).commit();
            simulateMode(3); emailed.set(null);
            open(); click(AlertDialog.BUTTON_POSITIVE); settle();
            long checkEnd = SystemClock.uptimeMillis() + 45000;
            while (!(status().startsWith("Check finished")) && SystemClock.uptimeMillis() < checkEnd) SystemClock.sleep(200);
            require(status().startsWith("Check finished") && !view("model-next").isEnabled(), "The check did not finish, or Next came before an answer");
            screenshot("finished");
            main(() -> find("model-unsure").performClick()); settle();
            tap("model-next"); settle();
            await(() -> view("drive-mode-skip") != null, "The drive-mode step did not open");
            main(() -> find("drive-mode-skip").performClick());
            end = SystemClock.uptimeMillis() + 15000;
            while (emailed.get() == null && SystemClock.uptimeMillis() < end) SystemClock.sleep(100);
            Intent skipped = emailed.get(); require(skipped != null, "Skipping the drive modes sent nothing");
            if (Intent.ACTION_CHOOSER.equals(skipped.getAction())) skipped = skipped.getParcelableExtra(Intent.EXTRA_INTENT);
            String plain = skipped.getStringExtra(Intent.EXTRA_TEXT);
            require(plain.startsWith("Scooter model, as the NAVEE app") && !plain.contains("volt-owner-notes") && "sent".equals(settings().getString("report_invite_" + MODEL, "")),
                    "Not sure and Skip changed the report");
            main(() -> require(activity.service().setSimulation(false), "Simulation did not stop")); settle();
            connectionDetails();
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", error.toString()); error.printStackTrace();
        } finally {
            result.putInt("assertions", assertions); result.putString("screenshots", screenshots.toString());
            result.putBoolean("physical_device_tested", false);
            // A failure mid-turn must not leave the emulator turned.
            try { getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_FREEZE_0); getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_UNFREEZE); } catch (Throwable ignored) { }
            try { if (activity != null) main(() -> activity.finish()); } catch (Throwable ignored) { }
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    /** Two timed-out owner checks on a scooter that never connected: Volt offers its connection details by itself. */
    private void connectionDetails() throws Exception {
        emailed.set(null);
        main(() -> {
            ScooterProfiles profiles = activity.service().profiles; ScooterProfiles.Profile p = profiles.active();
            p.lastConnected = 0; profiles.update(p);
            // Another scooter's failures in the app-wide log must not count; only this scooter's journal does.
            ConnectionEventLog log = (ConnectionEventLog) field(activity.service().ble, "diagnostics");
            Object journal = field(activity.service().ble, "journal");
            long wall = System.currentTimeMillis(), boot = SystemClock.elapsedRealtime();
            for (int i = 0; i < 3; i++) log.record(new ConnectionEventLog.Record(wall, boot, ConnectionEventLog.Reason.CONNECT_TIMEOUT, -1, true, false, ConnectionEventLog.Phase.AUTHENTICATING, -1, -1));
            invoke(activity, "maybeOfferConnectionReport", new Class<?>[0]);
            require(field(activity, "reportDialog") == null, "Failures from the app-wide log qualified this scooter");
            invoke(journal, "attempt", new Class<?>[]{String.class}, p.id);
            for (int i = 0; i < 2; i++) {
                ConnectionEventLog.Record attempt = new ConnectionEventLog.Record(wall + i * 20000, boot + i * 20000, ConnectionEventLog.Reason.CONNECT_ATTEMPT, -1, true, false, ConnectionEventLog.Phase.SCANNING, -1, -1);
                ConnectionEventLog.Record timeout = new ConnectionEventLog.Record(wall + i * 20000 + 18000, boot + i * 20000 + 18000, ConnectionEventLog.Reason.CONNECT_TIMEOUT, -1, true, false, ConnectionEventLog.Phase.AUTHENTICATING, -1, -1);
                for (ConnectionEventLog.Record r : new ConnectionEventLog.Record[]{attempt, timeout})
                    invoke(journal, "record", new Class<?>[]{String.class, ConnectionEventLog.Record.class}, p.id, r);
            }
        });
        String profileId = activity.service().profiles.active().id;
        main(() -> invoke(activity, "maybeOfferConnectionReport", new Class<?>[0])); settle();
        require(showing() && "Send details".contentEquals(button(AlertDialog.BUTTON_POSITIVE).getText()), "No connection-details offer after two failed first connections");
        screenshot("connection-offer");
        click(AlertDialog.BUTTON_POSITIVE); settle();
        require(showing() && "Send".equals(textOf("model-next")) && !view("model-next").isEnabled() && emailed.get() == null, "Send details did not ask which scooter it is");
        main(() -> ((android.widget.EditText) find("model-search")).setText("Mystery GT9")); settle();
        require(view("model-typed") != null && view("model-gt3") == null, "An unlisted name did not offer a write-in");
        main(() -> find("model-typed").performClick()); settle();
        require(view("model-next").isEnabled(), "A write-in did not enable Send");
        screenshot("connection-picker");
        tap("model-next"); settle();
        Intent mail = emailed.get(); require(mail != null, "No email was opened for the connection details");
        if (Intent.ACTION_CHOOSER.equals(mail.getAction())) mail = mail.getParcelableExtra(Intent.EXTRA_INTENT);
        String[] to = mail.getStringArrayExtra(Intent.EXTRA_EMAIL);
        String subject = mail.getStringExtra(Intent.EXTRA_SUBJECT), body = mail.getStringExtra(Intent.EXTRA_TEXT);
        require(to != null && to.length == 1 && ADDRESS.equals(to[0]), "Connection details are not addressed to the alias");
        require(subject != null && subject.startsWith("Volt connection details · "), "Connection details subject: " + subject);
        require(body != null && body.contains("\"format\": \"volt-connection-report-v1\"") && body.contains("\"reason\": \"CONNECT_TIMEOUT\"")
                && body.contains("\"phase\": \"AUTHENTICATING\"") && body.startsWith("Scooter model, from the owner: Mystery GT9\n")
                && body.contains("\"source\": \"typed\""), "Connection details lack the steps or the owner's write-in");
        for (String secret : new String[]{FIXTURE_NAME, FIXTURE_IDENTITY, FIXTURE_SERIAL, FIXTURE_MAC, FIXTURE_MAC.replace(":", "")})
            require(!body.toUpperCase(Locale.ROOT).contains(secret.toUpperCase(Locale.ROOT)), "A fixture secret reached the connection details");
        require(!showing() && "sent".equals(settings().getString("connect_report_" + profileId, "")), "Sent connection details did not close and persist");
        main(() -> require(activity.service().profiles.active().ownerModel.isEmpty(), "A typed, unlisted name was kept as the scooter's model"));
        main(() -> invoke(activity, "maybeOfferConnectionReport", new Class<?>[0])); settle();
        require(!showing(), "The connection-details offer came back after it was sent");
    }

    private void open() {
        main(() -> invoke(activity, "showReportInvite", new Class<?>[]{String.class}, MODEL)); settle();
        require(showing() && "Check & share".contentEquals(button(AlertDialog.BUTTON_POSITIVE).getText()), "The offer did not open");
    }
    private android.app.Dialog dialog() { return (android.app.Dialog) field(activity, "reportDialog"); }
    private boolean showing() { AtomicReference<Boolean> open = new AtomicReference<>(false); main(() -> open.set(dialog() != null && dialog().isShowing())); return open.get(); }
    /** The offer's own buttons (the picker sheet has none of these). */
    private Button button(int which) { AtomicReference<Button> found = new AtomicReference<>(); main(() -> found.set(((AlertDialog) dialog()).getButton(which))); return found.get(); }
    private void click(int which) { main(() -> ((AlertDialog) dialog()).getButton(which).performClick()); }
    private void tap(String tag) { main(() -> { View v = find(tag); require(v != null && v.isEnabled(), "Cannot tap " + tag); v.performClick(); }); }
    /** The open sheet is nine-tenths of Volt's window tall (within rounding). */
    private boolean sheetFits() {
        AtomicReference<Boolean> fits = new AtomicReference<>(false);
        main(() -> {
            int window = activity.getWindow().getDecorView().getHeight(), sheet = dialog() == null ? 0 : dialog().getWindow().getDecorView().getHeight();
            fits.set(window > 0 && Math.abs(sheet - Math.round(window * .9f)) <= 3);
        });
        return fits.get();
    }
    private boolean landscape() {
        AtomicReference<Boolean> wide = new AtomicReference<>(false);
        main(() -> { View d = activity.getWindow().getDecorView(); wide.set(d.getWidth() > d.getHeight()); });
        return wide.get();
    }
    /** The picker sheet's footer line. */
    private String status() { return textOf("model-status"); }

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
        File dir = new File(getTargetContext().getExternalFilesDir(null), "report-invite-validation"); require(dir.isDirectory() || dir.mkdirs(), "Screenshot directory unavailable");
        File file = new File(dir, arguments.getString("theme", "dark") + "-" + label + ".png");
        android.graphics.Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "Screenshot unavailable");
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); } finally { bitmap.recycle(); }
        screenshots.add(file.getAbsolutePath());
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
    /** On the main thread: the first view, depth first, in the showing offer, sheet or step that matches. */
    private View first(java.util.function.Predicate<View> match) { android.app.Dialog d = dialog(); return d != null && d.isShowing() ? first(d.getWindow().getDecorView(), match) : null; }
    private static View first(View view, java.util.function.Predicate<View> match) {
        if (match.test(view)) return view;
        if (view instanceof android.view.ViewGroup) for (int i = 0; i < ((android.view.ViewGroup) view).getChildCount(); i++) {
            View found = first(((android.view.ViewGroup) view).getChildAt(i), match); if (found != null) return found;
        }
        return null;
    }
    /** The first text in the showing sheet: nothing may come before its title. */
    private String firstText() {
        AtomicReference<String> text = new AtomicReference<>("");
        main(() -> { View v = first(c -> c instanceof TextView && c.isShown() && ((TextView) c).getText().length() > 0); if (v != null) text.set(String.valueOf(((TextView) v).getText())); });
        return text.get();
    }
    /** Some of the view with this tag is drawn inside the window (not scrolled out of view). */
    private boolean onScreen(String tag) {
        AtomicReference<Boolean> shown = new AtomicReference<>(false);
        main(() -> { View v = find(tag); shown.set(v != null && v.isShown() && v.getGlobalVisibleRect(new android.graphics.Rect())); });
        return shown.get();
    }
    /** On the main thread: the view with this tag in the showing offer or step. */
    private View find(String tag) { android.app.Dialog d = dialog(); return d != null && d.isShowing() ? d.getWindow().getDecorView().findViewWithTag(tag) : null; }
    private View view(String tag) { AtomicReference<View> found = new AtomicReference<>(); main(() -> found.set(find(tag))); return found.get(); }
    private String textOf(String tag) {
        AtomicReference<String> text = new AtomicReference<>("");
        main(() -> { View v = find(tag); if (v instanceof TextView) text.set(String.valueOf(((TextView) v).getText())); });
        return text.get();
    }
    /** Stands in for the owner pressing the scooter's mode button: the simulator now reports this mode. */
    @SuppressWarnings("unchecked") private void simulateMode(int code) {
        main(() -> {
            try {
                Object simulation = field(activity.service().ble, "simulation");
                java.util.Map<String, Object> fixed = (java.util.Map<String, Object>) field(simulation, "fixed");
                Class<?> sample = Class.forName("com.jjricks.volt.ScooterSimulation$Sample", true, simulation.getClass().getClassLoader());
                java.lang.reflect.Constructor<?> make = sample.getDeclaredConstructor(String.class, Double.class); make.setAccessible(true);
                fixed.put("driving_mode", make.newInstance("Mode " + code + " (simulation)", (double) code));
                // The simulator derives driving_mode from the acceleration control's readback, so move that too.
                ((java.util.Map<String, Integer>) field(simulation, "features")).put("acceleration", code);
                activity.service().ble.refresh(false);
            } catch (Exception error) { throw new IllegalStateException(error); }
        });
    }
    private void setField(Object target, String name, Object value) {
        try { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target, value); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    private Object invoke(Object target, String name, Class<?>[] types, Object... args) {
        try { Method m = target.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(target, args); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
}
