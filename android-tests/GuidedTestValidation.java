package com.jjricks.volt;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.SeekBar;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Fresh disposable emulator only (the runner also relaxes the emulator's hidden-API policy so this
 * process can build a fake GATT link, and restores it afterwards). Guided setting tests need a real
 * connection, so this runner replaces the Bluetooth link with an in-process synthetic scooter: the
 * app's own BleController sends every frame through its normal queue, admission and journal; the
 * fake BluetoothGatt hands each frame to a synthetic status table instead of a radio and answers
 * with OEM-framed notifications. No radio, adapter or real scooter is involved; Bluetooth stays off.
 *
 * Modes:
 * - guided: per synthetic model (UT5 MAX by serial, mapped 24431, unknown 39991) every eligible
 *   test through the UI (approval, baseline, apply, observe, restore, outcome list), refusals for
 *   ineligible controls and unmet preconditions, rejection and unchanged readback, disconnect and
 *   profile-switch interruptions with explicit reviews, and the report preview and Share text.
 * - interrupt-start: approves a UT5 MAX units test, waits until the target is confirmed and then
 *   kills this process before the restore (no cleanup runs).
 * - interrupt-resume: the relaunched app must show the outstanding restoration, write nothing on
 *   start or reconnect, and restore only after the explicit Restore tap.
 * - experimental: More › Experimental controls on a synthetic N65i: refusals with zero writes (never-added
 *   controls, an ordinary change of a hidden control, moving, locked, recording), then a try the scooter
 *   confirms ("Did you see it change?", "Worked here") and Set back, one it acknowledges but ignores ("No change
 *   seen", the link stays up), one it rejects ("Refused by scooter"), Set back in one tap from the list, and
 *   applied-but-unconfirmed tries (dropped link, timeout) left unfinished for review, never "Not sent".
 * The guided mode now drives Help verify and the UT5 MAX's Lab prompt sound in Experimental controls (the checker's
 * Try a setting is gone); recovery-navigation seeds a version 1 journal as an older Volt left it.
 */
public final class GuidedTestValidation extends Instrumentation {
    // Synthetic private values: none of them may reach a report.
    private static final String[][] MODELS = {
            // label, serial, advertised PID, MAC, identity, name
            {"ut5", "Q2538ZZ9US7777777", "", "C0:FF:EE:00:25:38", "A1B2C3D4E5F6", "Synthetic guided UT5"},
            {"mapped", "Q2443ZZ9US7777771", "24431", "C0:FF:EE:00:24:43", "A1B2C3D4E5F7", "Synthetic guided mapped"},
            {"unknown", "Q3999ZZ9US7777772", "39991", "C0:FF:EE:00:39:99", "A1B2C3D4E5F8", "Synthetic guided unknown"}};
    private static final String[] FEATURES = {"display_units", "headlight", "turn_sound", "auto_sensor", "range_algorithm"};
    /** Experimental mode only: a synthetic N65i (22131), whose official app hides turn sound, traction control, kick-start and more. */
    private static final String[] N65I = {"n65i", "Q2213ZZ9US7777773", "22131", "C0:FF:EE:00:22:13", "A1B2C3D4E5F9", "Synthetic guided N65i"};
    private Bundle arguments;
    private MainActivity activity;
    private boolean admitted;
    private int assertions;
    private FakeScooter fake;
    private final ArrayList<String> checks = new ArrayList<>(), screenshots = new ArrayList<>();

    @Override public void onCreate(Bundle args) { arguments = args == null ? new Bundle() : args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        String mode = arguments.getString("mode", "guided");
        try {
            admit(mode); admitted = true;
            if (!mode.equals("interrupt-resume")) {
                require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                        .putBoolean("developer_options", true)
                        .putString("theme", arguments.getString("theme", "dark")).putString("units", "mi")
                        .putFloat("text_scale", Float.parseFloat(arguments.getString("text_scale", "1"))).commit(), "Fixture settings failed");
                seed();
            }
            launch();
            if (mode.equals("guided")) { guided(); queueLifecycle(); }
            else if (mode.equals("queue")) queueLifecycle();
            else if (mode.equals("interrupt-start")) interruptStart();
            else if (mode.equals("interrupt-resume")) interruptResume();
            else if (mode.equals("recovery-navigation")) recoveryNavigation();
            else if (mode.equals("experimental")) experimental();
            else throw new IllegalArgumentException("Unknown mode " + mode);
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", error.toString());
            StringBuilder trace = new StringBuilder(); for (StackTraceElement e : error.getStackTrace()) if (e.getClassName().contains("GuidedTestValidation")) trace.append(e.getLineNumber()).append(' ');
            result.putString("at", trace.toString());
            if (activity != null) try { screenshot("failure"); } catch (Throwable ignored) { }
        } finally {
            result.putInt("assertions", assertions); result.putStringArrayList("checks", checks);
            result.putStringArrayList("screenshots", screenshots); result.putBoolean("physical_device_tested", false);
            if (fake != null) result.putInt("fake_writes", fake.writes.size());
            if (admitted && activity != null) try { main(() -> { activity.service().ble.disconnect(); activity.finish(); ScooterService.setUiVisible(false); getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); }); } catch (Throwable ignored) { }
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    // ---- Admission and fixtures --------------------------------------------------------------

    private void admit(String mode) throws Exception {
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
        require(hardware.contains("ranchu") || hardware.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "Refusing a physical phone");
        for (String permission : new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT})
            require(getTargetContext().checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED, "Grant Nearby permission on the emulator first");
        BluetoothManager manager = getTargetContext().getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        require(adapter == null || !adapter.isEnabled(), "Bluetooth must be disabled before validation");
        if (mode.equals("interrupt-resume")) {
            // Only the data the interrupt-start run left: the synthetic profiles and its marker.
            require(marker().isFile(), "No interrupt marker; run interrupt-start first");
            ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
            require(!profiles.all().isEmpty(), "No fixture profiles");
            for (ScooterProfiles.Profile p : profiles.all()) require(p.name.startsWith("Synthetic guided") || p.name.equals("UT5 MAX") || p.name.equals("NAVEE scooter"), "Refusing a non-synthetic profile " + p.name);
            return;
        }
        for (String name : new String[]{"app_settings", "profiles", "scooter", "pending_documents", "control_evidence"})
            require(prefs(name).getAll().isEmpty(), "Refusing existing private preferences");
        File[] files = getTargetContext().getFilesDir().listFiles();
        require(files != null && files.length == 0 && getTargetContext().databaseList().length == 0, "Refusing existing private data");
        File[] local = getTargetContext().getNoBackupFilesDir().listFiles();
        require(local == null || !Arrays.asList(local).contains(new File(getTargetContext().getNoBackupFilesDir(), "compat-tests")), "Refusing an existing test journal");
        File dir = outDir(); File[] old = dir.listFiles(); if (old != null) for (File f : old) require(f.delete(), "Stale output");
    }
    private void seed() {
        main(() -> {
            ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
            List<String[]> models = new ArrayList<>(Arrays.asList(MODELS));
            if (arguments.getString("mode", "guided").equals("experimental")) models.add(N65I);
            for (String[] m : models) {
                ScooterProfiles.Profile p = profiles.create(m[5], m[4], 0);
                p.serial = m[1]; p.mac = m[3]; p.lastConnected = System.currentTimeMillis() - 60000;
                if (!m[2].isEmpty()) { p.advertisedPid = m[2]; p.advertisedPidWall = System.currentTimeMillis() - 120000; }
                profiles.update(p);
                // Range follow is an existing automatic write on the UT5 MAX; it stays off except where its hold is checked.
                settings().edit().putBoolean("range_follow_mode_" + p.id, false).commit();
            }
            profiles.select(profiles.all().get(0).id);
            OnboardingScreen.markDone(getTargetContext());
            // The offer to send a first report for an unreported model would cover the screens under test.
            settings().edit().putString(ReportInvite.PREFIX + "24431", "never").putString(ReportInvite.PREFIX + "39991", "never").commit();
            if (arguments.getString("mode", "guided").equals("recovery-navigation")) seedVersionOneJournal(profiles);
        });
    }
    /**
     * An unfinished guided test as an older Volt (journal version 1, from before Experimental controls) left it, for
     * the mapped profile: it must load and stay recoverable from the Scooter page with Developer options hidden.
     */
    private void seedVersionOneJournal(ScooterProfiles profiles) {
        String id = null; for (ScooterProfiles.Profile p : profiles.all()) if (p.serial.equals(MODELS[1][1])) id = p.id;
        TestJournal.Entry entry = new TestJournal.Entry(); entry.testId = "0123456789abcdef"; entry.profileId = id;
        entry.feature = "display_units"; entry.variantId = "display_units.ut5"; entry.phase = "OUTSTANDING"; entry.baseline = 1; entry.target = 0;
        entry.createdWall = entry.updatedWall = System.currentTimeMillis();
        String line = TestJournal.line(entry); line = line.substring(0, line.indexOf("\tkind="));
        java.util.zip.CRC32 crc = new java.util.zip.CRC32(); crc.update(line.getBytes(StandardCharsets.UTF_8));
        line = line + "\tcrc=" + String.format(Locale.ROOT, "%08x", crc.getValue());
        try { Files.write(new File(getTargetContext().getNoBackupFilesDir(), "compat-tests").toPath(), (TestJournal.HEADER_V1 + "\n" + line + "\n").getBytes(StandardCharsets.UTF_8)); }
        catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    private void launch() {
        activity = (MainActivity)startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> activity.service() != null && root().isAttachedToWindow() && root().getWidth() > 0, "App did not launch"); settle();
    }
    private ScooterProfiles.Profile profileFor(String label) {
        String serial = label.equals(N65I[0]) ? N65I[1] : null; for (String[] m : MODELS) if (m[0].equals(label)) serial = m[1];
        for (ScooterProfiles.Profile p : activity.service().profiles.all()) if (p.serial.equals(serial)) return p;
        throw new IllegalStateException("No profile " + label);
    }

    /** Other-profile and forgotten-profile journals still have an ordinary recovery entry with dev tools hidden. */
    private void recoveryNavigation()throws Exception {
        ScooterProfiles.Profile other=profileFor("mapped");
        main(()->{
            settings().edit().putBoolean("developer_options",false).commit();
            // The version 1 journal an older Volt left: one unfinished guided test, loaded as kind "test".
            TestJournal.Entry loaded=((TestJournal)field(activity.service().ble,"testJournal")).find("0123456789abcdef");
            require(loaded!=null&&!loaded.isTry()&&loaded.profileId.equals(other.id)&&loaded.phase.equals("OUTSTANDING"),"Version 1 journal entry did not load: "+(loaded==null?null:TestJournal.line(loaded)));
            require(activity.service().ble.unresolvedTests().isEmpty()&&activity.service().ble.unresolvedTestsElsewhere()==1,"Elsewhere-only fixture is invalid");
            invoke(activity,"select",new Class<?>[]{String.class},"controls");
        });settle();
        awaitText("Unfinished test · another scooter");openRecoveryFromBanner();
        awaitText("1 unfinished test on another scooter. Select it to review.");
        requireRecoveryOnly();
        main(()->{
            activity.service().profiles.remove(other.id);activity.service().ble.profileSelected();
            require(activity.service().ble.unresolvedTestsElsewhere()==0&&activity.service().ble.orphanedTests().size()==1,"Orphan-only fixture is invalid");
            invoke(activity,"select",new Class<?>[]{String.class},"controls");
        });settle();
        awaitText("Unfinished test · removed scooter");openRecoveryFromBanner();
        awaitText("Its scooter was forgotten, so Volt can't check this setting or set it back. Check it on the scooter itself.");
        requireRecoveryOnly();
        tapDescription("Dismiss the unfinished Dashboard units test of a forgotten scooter","orphan recovery");
        awaitText("No unfinished tests.");requireRecoveryOnly();
        main(()->{
            require(activity.service().ble.orphanedTests().isEmpty(),"Orphan dismissal did not resolve the journal");
            require(((Number)field(activity.service().ble,"mutationSequence")).longValue()==0,"Recovery navigation wrote a scooter command");
            invoke(activity,"select",new Class<?>[]{String.class},"controls");
        });settle();
        main(()->require(!root().findViewWithTag("scooter-review-test").isShown(),"Resolved recovery banner remains visible"));
        checks.add("A version 1 journal's unfinished guided test loads; with Developer options hidden, other-profile and forgotten-profile tests retain Scooter Review access; recovery exposes no checker or new tests, and orphan dismissal sends no command");
    }
    private void openRecoveryFromBanner(){
        main(()->{View review=root().findViewWithTag("scooter-review-test");require(review!=null&&review.isShown(),"Recovery entry is hidden");review.performClick();});settle();
    }
    private void requireRecoveryOnly(){
        main(()->{String shown=texts(root());require(!shown.contains("Run check")&&!shown.contains("Run again")&&!shown.toLowerCase(Locale.ROOT).contains("try a setting")
                &&root().findViewWithTag("developer-compatibility-check")==null,"Recovery exposed developer checker tools");});
    }

    // ---- guided: Help verify and experimental tries over the synthetic link --------------------

    /**
     * Per synthetic model: what Experimental controls offers to try (Help verify on the mapped and unknown models, the
     * UT5 MAX's Lab prompt sound), refusals with zero writes, each try with its readback, the owner's answer and Set back,
     * the interruptions left for review, and the report with the tries.
     */
    private void guided() throws Exception {
        for (String[] m : MODELS) {
            String label = m[0];
            connect(label);
            openLab();
            List<String> keys = tryKeys(label);
            refusals(label, keys.get(0));
            for (int i = 0; i < keys.size(); i++) tryPath(label, keys.get(i), i);
            if (label.equals("ut5")) { sliders(label); rejected(label); unchanged(label); profileSwitch(label); keptForReport(label); }
            if (label.equals("mapped")) disconnectAndRestore(label);
            if (label.equals("unknown")) disconnectAlreadyRestored(label);
            report(label);
            require(fake.matchesBaseline(), label + ": the synthetic scooter did not end at its starting values: " + fake.diff());
            main(() -> activity.service().ble.disconnect()); settle();
        }
        checks.add("Every try's write went through BleController's queue, the try gate and the journal to the in-process synthetic link; Bluetooth stayed off");
    }

    /** Selects the model's profile and attaches the synthetic link as an authenticated, ready session. */
    private void connect(String label) throws Exception {
        ScooterProfiles.Profile p = profileFor(label);
        main(() -> { BleController ble = activity.service().ble; ble.disconnect(); activity.service().profiles.select(p.id); ble.profileSelected(); });
        settle();
        fake = new FakeScooter(this, label);
        main(() -> fake.attach(activity.service().ble, p));
        await(() -> { boolean[] r = {false}; main(() -> r[0] = activity.service().ble.ready()); return r[0]; }, label + ": the synthetic session never became ready", 12000);
        main(() -> {
            BleController ble = activity.service().ble;
            DeviceDescriptor d = ble.descriptor(); ModelRegistry.Layer layer = ble.resolution().layer;
            require(label.equals("ut5") ? layer == ModelRegistry.Layer.UT5_MAX && d.pidSource() == DeviceDescriptor.PidSource.SERIAL
                    : label.equals("mapped") ? layer == ModelRegistry.Layer.OEM_GROUP && d.advertisedPid.equals("24431")
                    : label.equals(N65I[0]) ? layer == ModelRegistry.Layer.OEM_GROUP && d.advertisedPid.equals("22131")
                    : layer == ModelRegistry.Layer.SHARED && d.advertisedPid.equals("39991"), label + ": descriptor " + d + " layer " + layer);
        });
    }
    private void parked(String label) {
        fake.motion = true; fake.speed = 0;
        await(() -> { boolean[] r = {false}; main(() -> r[0] = activity.service().ble.stationary()); return r[0]; }, label + ": no parked motion report", 10000);
    }
    private void openLab() {
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "more")); settle();
        main(() -> invoke(more(), "selectSection", new Class<?>[]{String.class}, "Lab")); settle();
    }
    private void openCompatibility() {
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "more")); settle();
        main(() -> invoke(more(), "selectSection", new Class<?>[]{String.class}, "Compatibility")); settle();
    }

    /** What Experimental controls lists: Help verify exactly where the official app offers and Volt hasn't verified. */
    private List<String> tryKeys(String label) throws Exception {
        List<String> verify = new ArrayList<>(), rows = labKeys();
        main(() -> {
            BleController ble = activity.service().ble;
            verify.addAll(ModelRegistry.verifiable(ble.descriptor()));
            // The guided-test table's removed home: the checker offers no setting change.
            for (String f : FEATURES) require(!ble.testDecision(f).available(), label + ": a guided test can still start for " + f);
            for (String f : new String[]{"maximum_speed", "charge_limit", "lock", "lock_expiry", "proximity_key", "pin", "clock_sync"})
                require(ble.tryDecision(f).support != CapabilityResolver.Support.KNOWN_ENCODING, label + ": " + f + " offered as a try");
        });
        if (label.equals("ut5")) require(verify.isEmpty() && rows.containsAll(Arrays.asList("prompt_tone", "speed_limit", "drive_speed_limit")), label + ": UT5 MAX rows " + rows + " verify " + verify);
        else if (label.equals("unknown")) require(verify.equals(Arrays.asList("display_units")) && rows.equals(verify), label + ": unknown-model rows " + rows);
        else require(verify.equals(Arrays.asList(FEATURES)) && rows.containsAll(verify), label + ": mapped rows " + rows + " verify " + verify);
        if (!verify.isEmpty()) { revealText("Help verify"); screenshot(label + "-help-verify"); }
        checks.add(label + ": Help verify " + verify + "; speed, charging, clock, lock, PIN and proximity never offered as tries");
        return label.equals("ut5") ? Arrays.asList("prompt_tone") : verify;
    }

    /** Unmet conditions and never-added controls: nothing starts, nothing is written. */
    private void refusals(String label, String key) throws Exception {
        int writes = fake.writes.size();
        // No motion report on this connection yet: a try waits for one (no parked tick for tries).
        await(() -> labReason(key) == CapabilityResolver.Reason.NO_MOTION_REPORT, label + ": without a motion report: " + labReason(key), 10000);
        main(() -> require(!activity.service().ble.setExperimental(key, other(activity.service().ble.tryDecision(key))), label + ": a try started without a motion report"));
        awaitText("Waiting for the scooter to report that it's parked."); screenshot(label + "-try-no-motion");
        parked(label);
        fake.speed = 3;
        await(() -> labReason(key) == CapabilityResolver.Reason.MOVING, label + ": moving not refused: " + labReason(key), 10000);
        main(() -> require(!activity.service().ble.setExperimental(key, other(activity.service().ble.tryDecision(key))), label + ": a try started while moving"));
        awaitText("Stop the scooter to try these."); screenshot(label + "-try-moving");
        fake.speed = 0;
        if (label.equals("mapped")) {
            fake.set(2, 1);
            await(() -> labReason(key) == CapabilityResolver.Reason.LOCKED, label + ": locked not refused: " + labReason(key), 10000);
            main(() -> require(!activity.service().ble.setExperimental(key, other(activity.service().ble.tryDecision(key))), label + ": a try started while locked"));
            awaitText("Unlock the scooter to try these."); screenshot(label + "-try-locked");
            fake.set(2, 0);
        }
        await(() -> labReason(key) == CapabilityResolver.Reason.NONE, label + ": parked and unlocked not admitted: " + labReason(key), 10000);
        main(() -> {
            BleController ble = activity.service().ble;
            ble.visibility(true, true, 0);
            require(ble.tryDecision(key).reason == CapabilityResolver.Reason.RECORDING && !ble.setExperimental(key, other(ble.tryDecision(key))), label + ": a try started while recording");
            ble.visibility(true, false, 0);
            for (String f : new String[]{"maximum_speed", "charge_limit", "lock", "lock_expiry", "low_power", "timed_charging"})
                require(!ble.setExperimental(f, f.equals("maximum_speed") ? 25 : f.equals("charge_limit") ? 90 : 1), label + ": " + f + " sent as a try");
            require(!ble.testing() && ble.guidedTest() == null, label + ": a refused try left a test");
        });
        require(fake.writes.size() == writes, label + ": refusals wrote " + fake.describeWrites());
        checks.add(label + ": refused without a motion report, while moving" + (label.equals("mapped") ? ", while locked" : "") + ", while recording and for never-added controls, with zero writes");
    }

    /** Try → one journalled write → readback → "Did you see it change?" → kept (no restore write) → Set back from the row. */
    private void tryPath(String label, String feature, int index) throws Exception {
        final int[] values = new int[2]; final String[] kind = {""};
        main(() -> { CapabilityResolver.Decision d = activity.service().ble.tryDecision(feature);
            require(d.available(), label + ": " + feature + " not available: " + d.reasonText());
            values[0] = d.current(); values[1] = other(d); kind[0] = d.experimental() ? "experimental" : "verify"; });
        int writes = fake.writes.size();
        openTry(feature); chooseOption(values[1]); sheetShot(label + "-" + feature + "-1-sheet");
        require(fake.writes.size() == writes, label + ": " + feature + " wrote before Try");
        tapSheet("go");
        awaitPhase(GuidedTest.Phase.OBSERVE, label + " " + feature + " target confirmed");
        require(fake.writes.size() == writes + 1 && fake.lastWriteValue() == values[1], label + ": " + feature + " target write " + fake.describeWrites());
        main(() -> {
            TestJournal.Entry e = activity.service().ble.guidedTest().entry();
            require(e.phase.equals("OBSERVE") && e.kind.equals(kind[0]) && e.baseline == values[0] && e.target == values[1] && !e.resolved, label + ": journal before the answer " + TestJournal.line(e));
            require(activity.service().ble.decision(feature.equals("display_units") ? "cruise_control" : "display_units").reason == CapabilityResolver.Reason.TEST_ACTIVE
                    || !activity.service().ble.decision(feature.equals("display_units") ? "cruise_control" : "display_units").available(), label + ": an ordinary control stayed available during the try");
        });
        if (label.equals("ut5")) {
            // A running try holds the automatic range-follow write (the synthetic riding mode asks for Sport).
            main(() -> activity.service().followRidingMode(true));
            SystemClock.sleep(5000);
            require(fake.writes.size() == writes + 1, label + ": range follow wrote during a try " + fake.describeWrites());
            main(() -> activity.service().followRidingMode(false));
            checks.add(label + ": range follow enabled during a running try sends nothing");
        }
        awaitSheetText(kind[0].equals("verify") ? "?" : "Did you see it change?"); sheetShot(label + "-" + feature + "-2-observe");
        String answer = index % 3 == 0 ? "yes" : index % 3 == 1 ? "no" : "skip";
        main(() -> { View b = sheetRoot().findViewWithTag("lab-observe-" + answer); require(b != null && b.isShown(), label + ": no " + answer); b.performClick(); }); settle();
        awaitPhase(GuidedTest.Phase.DONE, label + " " + feature + " kept");
        require(fake.writes.size() == writes + 1 && fake.value(feature) == values[1], label + ": " + feature + " a try wrote again after the answer " + fake.describeWrites());
        main(() -> {
            TestJournal.Entry e = activity.service().ble.guidedTest().entry();
            require(e.resolved && e.resolution.equals("kept") && e.restoration.equals("declined") && e.effect.equals(answer.equals("yes") ? "observed" : answer.equals("no") ? "not_observed" : "not_checked"), label + ": journal after the answer " + TestJournal.line(e));
            require(activity.service().ble.unresolvedTests().isEmpty() && Integer.valueOf(values[0]).equals(activity.service().ble.experimentBaseline(feature)), label + ": kept try not offered for Set back");
        });
        awaitSheetText("Worked here"); sheetShot(label + "-" + feature + "-3-worked");
        closeSheet();
        // Set back in one tap from the row, journalled like the try.
        main(() -> { for (Object row : (List<?>)field(more(), "labRows")) if (((ScooterCommands.Feature)field(row, "feature")).key.equals(feature)) {
            TextView pill = (TextView)field(row, "action"); require("Set back".contentEquals(pill.getText()) && pill.isEnabled(), label + ": row pill " + pill.getText()); pill.performClick(); } });
        awaitPhase(GuidedTest.Phase.DONE, label + " " + feature + " set back");
        await(() -> fake.value(feature) == values[0], label + ": " + feature + " not set back", 10000);
        require(fake.writes.size() == writes + 2 && fake.lastWriteValue() == values[0], label + ": " + feature + " set-back writes " + fake.describeWrites());
        main(() -> { TestJournal.Entry e = activity.service().ble.latestTry(feature);
            require(e.resolved && e.restoration.equals("confirmed") && e.resolution.equals("restored") && CompatibilityReport.tryResult(e).equals("worked_here"), label + ": after Set back " + TestJournal.line(e));
            require(activity.service().ble.experimentBaseline(feature) == null, label + ": Set back still offered"); });
        checks.add(label + ": " + feature + " " + values[0] + " → " + values[1] + " (answer " + answer + "), kept, then Set back from the row: one try write and one set-back write, both read back");
    }

    /**
     * The UT5 MAX speed caps are sliders (no listed values) whose bit 7 enables them. Off → 10 km/h and 10 km/h → Off
     * each send one journalled write, read back, keep and Set back from the row; an interrupted one is restored after review.
     */
    private void sliders(String label) throws Exception {
        for (String feature : new String[]{"speed_limit", "drive_speed_limit"}) {
            final int at = FakeScooter.offset(feature);
            for (boolean fromOff : new boolean[]{true, false}) {
                final int before = fromOff ? 0 : 10, after = fromOff ? 10 : 0; final String step = label + " " + feature + " " + before + " → " + after;
                fake.set(at, fromOff ? 0x00 : 0x8a);
                await(() -> { Integer[] v = {null}; main(() -> v[0] = activity.service().ble.tryDecision(feature).current()); return v[0] != null && v[0] == before; }, step + ": never read " + before, 10000);
                int writes = fake.writes.size();
                openTry(feature); slideTo(after);
                if (fromOff) sheetShot(label + "-" + feature + "-slider");
                require(fake.writes.size() == writes, step + ": wrote before Try");
                tapSheet("go");
                awaitPhase(GuidedTest.Phase.OBSERVE, step + " confirmed");
                require(fake.writes.size() == writes + 1 && fake.lastWriteValue() == after && fake.value(feature) == after, step + ": try write " + fake.describeWrites());
                main(() -> { TestJournal.Entry e = activity.service().ble.guidedTest().entry();
                    require(e.kind.equals("experimental") && e.baseline == before && e.target == after && e.related == -1 && e.phase.equals("OBSERVE"), step + ": journal " + TestJournal.line(e)); });
                if (fromOff && feature.equals("speed_limit")) { sliderReview(step, feature, before, after); continue; }
                awaitSheetText("Did you see it change?");
                if (!fromOff) sheetShot(label + "-" + feature + "-observe-off");
                main(() -> { View b = sheetRoot().findViewWithTag("lab-observe-yes"); require(b != null && b.isShown(), step + ": no Yes"); b.performClick(); }); settle();
                awaitPhase(GuidedTest.Phase.DONE, step + " kept");
                awaitSheetText("Worked here");
                require(fake.writes.size() == writes + 1, step + ": wrote again after the answer " + fake.describeWrites());
                closeSheet();
                main(() -> { for (Object row : (List<?>)field(more(), "labRows")) if (((ScooterCommands.Feature)field(row, "feature")).key.equals(feature)) {
                    TextView pill = (TextView)field(row, "action"); require("Set back".contentEquals(pill.getText()) && pill.isEnabled(), step + ": row pill " + pill.getText()); pill.performClick(); } });
                awaitPhase(GuidedTest.Phase.DONE, step + " set back");
                await(() -> fake.value(feature) == before, step + ": not set back", 10000);
                require(fake.writes.size() == writes + 2 && fake.lastWriteValue() == before, step + ": set-back writes " + fake.describeWrites());
                main(() -> { TestJournal.Entry e = activity.service().ble.latestTry(feature);
                    require(e.resolved && e.restoration.equals("confirmed") && e.resolution.equals("restored") && CompatibilityReport.tryResult(e).equals("worked_here"), step + ": after Set back " + TestJournal.line(e)); });
                checks.add(step + ": one journalled slider write read back without a related-setting conflict, kept, then Set back from the row");
            }
            fake.set(at, fake.baseline[at]);
        }
    }
    /** The link drops after a slider try's target: unfinished; Review → Restore to Off writes once and reads it back. */
    private void sliderReview(String step, String feature, int before, int after) throws Exception {
        // Dropped while the sheet asks (closing the sheet first would count as Skip).
        int writes = fake.writes.size();
        main(() -> activity.service().ble.disconnect()); settle();
        closeSheet();
        main(() -> { TestJournal.Entry e = activity.service().ble.latestTry(feature); require(!e.resolved && e.phase.equals("OUTSTANDING"), step + ": after the drop " + TestJournal.line(e)); });
        reconnect("ut5", writes); parked("ut5"); openLab();
        openTry(feature); tapSheetTag("lab-review");
        awaitText("Restore to Off");
        main(() -> require(texts(root()).contains("Still on the test value, 10 km/h."), step + ": review " + texts(root()).replace('\n', '|')));
        tapText("Restore to Off", step + " restore");
        awaitPhase(GuidedTest.Phase.DONE, step + " restored after review");
        await(() -> fake.value(feature) == before, step + ": review did not set it back", 10000);
        require(fake.writes.size() == writes + 1 && fake.lastWriteValue() == before, step + ": restore writes " + fake.describeWrites());
        main(() -> { TestJournal.Entry e = activity.service().ble.latestTry(feature); require(e.resolved && e.restoration.equals("confirmed"), step + ": after review " + TestJournal.line(e)); });
        awaitText("Restoration confirmed"); closeCard("ut5");
        openLab();
        checks.add(step + ": dropped after the target, the review restored Off with one write, confirmed by readback");
    }
    /** Moves the try sheet's slider as the owner would (the accessibility set-progress action is a user change). */
    private void slideTo(int value) {
        main(() -> {
            SeekBar slider = (SeekBar)sheetRoot().findViewWithTag("lab-slider"); require(slider != null && slider.isShown(), "No slider");
            List<?> range = (List<?>)field(sheet(), "range"); int index = range.indexOf(value); require(index >= 0, "Slider has no " + value + " in " + range);
            if (!Integer.valueOf(value).equals(field(sheet(), "selected"))) {
                Bundle args = new Bundle(); args.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, index);
                require(slider.performAccessibilityAction(android.R.id.accessibilityActionSetProgress, args), "The slider did not move to " + value);
            }
            require(Integer.valueOf(value).equals(field(sheet(), "selected")) && slider.getProgress() == index, "Slider selects " + field(sheet(), "selected"));
        }); settle();
    }
    /** Nonzero ACK: Refused by scooter; nothing to set back, confirmed by a fresh readback. */
    private void rejected(String label) throws Exception {
        int writes = fake.writes.size(); fake.rejectNext = 3;
        openTry("prompt_tone"); chooseOption(4); tapSheet("go");
        awaitPhase(GuidedTest.Phase.DONE, label + " rejected");
        require(fake.writes.size() == writes + 1, label + ": rejection writes " + fake.describeWrites());
        main(() -> { GuidedTest t = activity.service().ble.guidedTest(); require(t.outcomeTexts().equals(Arrays.asList("Rejected, code 3", "Restoration confirmed")) && CompatibilityReport.tryResult(t.entry()).equals("refused"), label + ": rejected outcomes " + t.outcomeTexts()); });
        awaitSheetText("Refused by scooter"); sheetShot(label + "-rejected");
        closeSheet();
        checks.add(label + ": a rejected try (code 3) reads Refused by scooter, with no restore write");
    }
    /** ACK without a change: No change seen, and the connection stays up. */
    private void unchanged(String label) throws Exception {
        int writes = fake.writes.size(); fake.ignoreWrites = true;
        try { openTry("prompt_tone"); chooseOption(4); tapSheet("go"); awaitPhase(GuidedTest.Phase.DONE, label + " unchanged"); } finally { fake.ignoreWrites = false; }
        require(fake.writes.size() == writes + 1, label + ": unchanged writes " + fake.describeWrites());
        main(() -> { GuidedTest t = activity.service().ble.guidedTest(); require(t.outcomeTexts().equals(Arrays.asList("Readback unchanged, value 2", "Restoration confirmed")) && CompatibilityReport.tryResult(t.entry()).equals("no_change_seen"), label + ": unchanged outcomes " + t.outcomeTexts());
            require(activity.service().ble.ready(), label + ": an ignored try dropped the connection"); });
        awaitSheetText("No change seen"); sheetShot(label + "-unchanged");
        closeSheet();
        checks.add(label + ": an acknowledged try the scooter ignored reads No change seen, never Worked here, and the link stays up");
    }
    private void tryToObserve(String label, String feature) throws Exception {
        final int[] target = {0};
        main(() -> target[0] = other(activity.service().ble.tryDecision(feature)));
        tryToObserve(label, feature, target[0]);
    }
    private void tryToObserve(String label, String feature, int target) throws Exception {
        openTry(feature); chooseOption(target); tapSheet("go");
        awaitPhase(GuidedTest.Phase.OBSERVE, label + " " + feature + " observe");
    }

    /** A try kept for the report: its preview shows it kept, and saveReport sets it back while a save of that preview is writing. */
    private void keptForReport(String label) throws Exception {
        openLab(); tryToObserve(label, "prompt_tone", 4);
        awaitSheetText("Did you see it change?");
        main(() -> { View b = sheetRoot().findViewWithTag("lab-observe-yes"); require(b != null && b.isShown(), label + ": no Yes"); b.performClick(); }); settle();
        awaitPhase(GuidedTest.Phase.DONE, label + " kept for the report");
        main(() -> require("kept".equals(activity.service().ble.latestTry("prompt_tone").resolution), label + ": not kept"));
        closeSheet();
    }
    /** Target confirmed, then another profile is selected: unfinished with its own profile; reviewed and dismissed. */
    private void profileSwitch(String label) throws Exception {
        tryToObserve(label, "prompt_tone");
        int writes = fake.writes.size();
        ScooterProfiles.Profile other = profileFor("mapped"), mine = profileFor(label);
        main(() -> { activity.service().profiles.select(other.id); activity.service().ble.profileSelected(); });
        settle();
        main(() -> {
            BleController ble = activity.service().ble;
            require(!ble.testing(), label + ": the try kept running after a profile switch");
            List<TestJournal.Entry> open = ((TestJournal)field(ble, "testJournal")).unresolved(mine.id);
            require(open.size() == 1 && open.get(0).isTry() && open.get(0).phase.equals("OUTSTANDING") && open.get(0).outcomes.contains("interrupted"), label + ": journal after switch " + open);
            ble.disconnect();
            require(ble.unresolvedTestsElsewhere() == 1 && ble.unresolvedTests().isEmpty(), label + ": the unfinished try is not kept with its own profile");
            activity.redraw();
        });
        require(fake.writes.size() == writes, label + ": profile switch wrote");
        main(() -> { activity.service().profiles.select(mine.id); activity.service().ble.profileSelected(); invoke(activity, "select", new Class<?>[]{String.class}, "controls"); });
        settle();
        awaitText("Unfinished try · Prompt sound"); screenshot(label + "-switch-banner");
        reconnect(label, writes);
        parked(label);
        openLab();
        awaitText("Unfinished · review", "Unfinished");
        main(() -> require(!activity.service().ble.setExperimental("prompt_tone", 4) && !activity.service().ble.setExperimental("prompt_tone", 1), label + ": a new try started while one is unfinished"));
        revealText("Prompt sound"); screenshot(label + "-switch-unfinished-row");
        main(() -> { for (Object row : (List<?>)field(more(), "labRows")) if (((ScooterCommands.Feature)field(row, "feature")).key.equals("prompt_tone")) {
            TextView pill = (TextView)field(row, "action"); require("Review".contentEquals(pill.getText()), label + ": row pill " + pill.getText()); pill.performClick(); } });
        settle();
        awaitText("Still on the test value, Sound 1.");
        main(() -> require(texts(root()).contains("Unfinished try · Prompt sound") && texts(root()).contains("Keep Sound 1") && texts(root()).contains("Restore to Sound 3"), label + ": review card " + texts(root()).replace('\n', '|')));
        revealText("Unfinished try · Prompt sound"); screenshot(label + "-switch-review");
        main(() -> { View dismiss = byDescription(root(), "Dismiss the unfinished Prompt sound try without restoring it"); require(dismiss != null, label + ": no Dismiss"); dismiss.performClick(); });
        settle();
        main(() -> {
            TestJournal journal = (TestJournal)field(activity.service().ble, "testJournal");
            require(journal.unresolved(mine.id).isEmpty(), label + ": dismiss left the try unresolved");
            TestJournal.Entry last = journal.latestTry(mine.id, "prompt_tone");
            require(last != null && last.restoration.equals("declined") && last.resolution.equals("dismissed"), label + ": dismissed entry " + (last == null ? null : TestJournal.line(last)));
        });
        require(fake.writes.size() == writes, label + ": dismiss wrote");
        fake.set(21, fake.baseline[21]);
        checks.add(label + ": a profile switch after the target leaves the try unfinished with its own profile, shows Unfinished try on the banner and the row, writes nothing; Dismiss records declined");
    }

    /** Target confirmed, then the link drops: unfinished; reconnecting writes nothing; Restore is one explicit tap in the review. */
    private void disconnectAndRestore(String label) throws Exception {
        tryToObserve(label, "display_units");
        int writes = fake.writes.size();
        main(() -> activity.service().ble.disconnect()); settle();
        main(() -> { BleController ble = activity.service().ble; require(!ble.testing() && ble.unresolvedTests().size() == 1 && ble.unresolvedTests().get(0).isTry(), label + ": disconnect did not leave one unfinished try"); });
        closeSheetIfOpen();
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "controls")); settle();
        awaitText("Unfinished try · Dashboard units"); screenshot(label + "-scooter-banner");
        reconnect(label, writes);
        openLab();
        openTry("display_units");
        awaitSheetText("This try didn't finish"); sheetShot(label + "-unfinished-sheet");
        tapSheetTag("lab-review");
        awaitText("Restore to Kilometers / km/h");
        main(() -> require(texts(root()).contains("Still on the test value, Miles / mph.") && texts(root()).contains("Keep Miles / mph"), label + ": review text"));
        revealText("Unfinished try · Dashboard units"); screenshot(label + "-review-restore");
        require(fake.writes.size() == writes, label + ": reconnect wrote " + fake.describeWrites());
        tapText("Restore to Kilometers / km/h", "restore");
        awaitPhase(GuidedTest.Phase.DONE, label + " explicit restore");
        require(fake.writes.size() == writes + 1 && fake.lastWriteValue() == 1 && fake.value("display_units") == 1, label + ": explicit restore writes " + fake.describeWrites());
        main(() -> {
            GuidedTest t = activity.service().ble.guidedTest();
            require(t.outcomeTexts().equals(Arrays.asList("Setting confirmed", "Interrupted/no response", "Restoration confirmed")) && t.restorationStatus().equals("confirmed"), label + ": restored outcomes " + t.outcomeTexts());
            require(activity.service().ble.unresolvedTests().isEmpty(), label + ": still unfinished after restore");
        });
        awaitText("Restoration confirmed"); revealText("Try · Dashboard units"); screenshot(label + "-review-restored");
        closeCard(label);
        checks.add(label + ": disconnect after the target → unfinished try (row Review, sheet note, Scooter banner), zero writes on reconnect, one write on the explicit Restore tap → Restoration confirmed");
    }
    /** Interrupted, then the owner set it back on the dashboard: Already back, Mark restored, no write. */
    private void disconnectAlreadyRestored(String label) throws Exception {
        tryToObserve(label, "display_units");
        int writes = fake.writes.size();
        main(() -> activity.service().ble.disconnect()); settle();
        closeSheetIfOpen();
        fake.set(7, fake.baseline[7]);
        reconnect(label, writes);
        openCompatibility();
        awaitText("Mark restored");
        main(() -> require(texts(root()).contains("Already back to Kilometers / km/h."), label + ": review text"));
        revealText("Unfinished try · Dashboard units"); screenshot(label + "-review-already-restored");
        tapText("Mark restored", label); settle();
        main(() -> {
            TestJournal.Entry e = activity.service().ble.latestTry("display_units");
            require(e.resolved && e.restoration.equals("confirmed") && e.resolution.equals("already_restored") && e.outcomes.get(e.outcomes.size() - 1).equals("restoration_confirmed"), label + ": already-restored entry " + TestJournal.line(e));
        });
        require(fake.writes.size() == writes, label + ": Mark restored wrote");
        checks.add(label + ": an interrupted try the owner already set back reads \"Already back to …\"; Mark restored records Restoration confirmed with no write");
    }
    private void closeCard(String label) {
        main(() -> { TextView close = byText(root(), "Close"); require(close != null, label + ": no Close"); close.performClick(); }); settle();
    }
    private void reconnect(String label, int writes) throws Exception {
        ScooterProfiles.Profile p = profileFor(label);
        main(() -> fake.attach(activity.service().ble, p));
        await(() -> { boolean[] r = {false}; main(() -> r[0] = activity.service().ble.ready()); return r[0]; }, label + ": reconnect not ready", 12000);
        // Several status polls arrive; nothing is written back automatically.
        SystemClock.sleep(6000);
        require(fake.writes.size() == writes, label + ": automatic write after reconnect " + fake.describeWrites());
        main(() -> require(!activity.service().ble.testing(), label + ": a try resumed by itself"));
    }

    /** The compatibility report after a read-only check: the tries section, no identifiers; Share marks them reported. */
    private void report(String label) throws Exception {
        int writes = fake.writes.size();
        openCompatibility();
        main(() -> activity.service().ble.startDiscovery());
        await(() -> { boolean[] r = {true}; main(() -> r[0] = activity.service().ble.discovering()); return !r[0]; }, label + ": check did not finish", 60000);
        require(fake.writes.size() == writes, label + ": the check wrote");
        awaitText("Preview report");
        tapText("Preview report", label); settle();
        awaitText("Experimental controls tries", "Show JSON");
        final String[] json = {null}, summary = {null}; final int[] tries = {0}; final List<String> ids = new ArrayList<>();
        main(() -> {
            Object report = field(field(more(), "compatibilityPanel"), "report");
            json[0] = (String)invoke(report, "json"); summary[0] = (String)invoke(report, "summary");
            List<TestJournal.Entry> all = ((TestJournal)field(activity.service().ble, "testJournal")).all(activity.service().ble.profile().id);
            for (TestJournal.Entry e : all) { ids.add(e.testId); if (e.isTry()) tries[0]++; require(!e.reported, label + ": reported before sharing"); }
            ids.add(activity.service().ble.profile().id);
        });
        JSONObject root = new JSONObject(json[0]);
        JSONArray list = root.getJSONArray("experimental");
        require(tries[0] > 0 && list.length() == tries[0] && root.getJSONArray("tests").length() == 0, label + ": report has " + list.length() + " tries, journal " + tries[0]);
        for (int i = 0; i < list.length(); i++) {
            JSONObject t = list.getJSONObject(i);
            List<String> keys = new ArrayList<>(); for (Iterator<String> k = t.keys(); k.hasNext();) keys.add(k.next());
            require(keys.containsAll(Arrays.asList("feature", "group", "variant", "baseline", "target", "packet_evidence", "owner_observation", "restoration", "result", "kept")), label + ": try keys " + keys);
            require(t.getString("group").equals(label.equals("ut5") ? "experimental" : "help_verify"), label + ": group " + t.getString("group"));
            require(Arrays.asList("worked_here", "no_change_seen", "refused", "other_value", "unconfirmed", "not_sent", "interrupted").contains(t.getString("result")), label + ": result " + t.getString("result"));
        }
        require(summary[0].contains("Experimental controls tries") && summary[0].contains("Worked here"), label + ": summary has no tries section");
        if (label.equals("ut5")) require(summary[0].contains("Refused by scooter") && summary[0].contains("No change seen"), label + ": UT5 MAX summary misses refused or unchanged tries");
        requireClean(json[0] + "\n" + summary[0], label + " report", ids);
        Files.write(new File(outDir(), variant() + "-" + label + "-report.json").toPath(), json[0].getBytes(StandardCharsets.UTF_8));
        Files.write(new File(outDir(), variant() + "-" + label + "-summary.txt").toPath(), summary[0].getBytes(StandardCharsets.UTF_8));
        revealLine("Experimental controls tries"); screenshot(label + "-report-tries");
        // The UT5 MAX saves the file (marked once written); the others Share, which hands exactly the previewed text to the
        // system chooser (intercepted here) and marks the tries reported at the handoff.
        if (label.equals("ut5")) saveReport(label, json[0]); else {
        final Intent[] chooser = {null};
        ActivityMonitor intercept = new ActivityMonitor() {
            @Override public ActivityResult onStartActivity(Intent intent) {
                if (Intent.ACTION_CHOOSER.equals(intent.getAction())) { chooser[0] = intent; return new ActivityResult(Activity.RESULT_CANCELED, null); }
                return null;
            }
        };
        addMonitor(intercept);
        try {
            main(() -> { TextView share = byText(root(), "Share"); require(share != null, label + ": no Share"); share.performClick(); });
            await(() -> chooser[0] != null, label + ": Share did not open the chooser");
            Intent send = chooser[0].getParcelableExtra(Intent.EXTRA_INTENT);
            String text = send.getStringExtra(Intent.EXTRA_TEXT);
            require((summary[0] + "\n" + json[0]).equals(text), label + ": shared text differs from the preview");
            requireClean(text, label + " shared text", ids);
        } finally { removeMonitor(intercept); }
        }
        main(() -> { for (TestJournal.Entry e : ((TestJournal)field(activity.service().ble, "testJournal")).all(activity.service().ble.profile().id)) require(e.reported, label + ": shared or saved but not reported " + TestJournal.line(e)); });
        main(() -> { TextView close = byText(root(), "Close preview"); if (close != null) close.performClick(); }); settle();
        checks.add(label + ": report preview and " + (label.equals("ut5") ? "Save file" : "Share") + " include " + tries[0] + " tries (semantic values only, honest results), no serial, MAC, owner key, name, profile or test ID; "
                + (label.equals("ut5") ? "only the written file marks them reported" : "sharing marks them reported"));
    }
    /**
     * Save file marks the tries reported only once the file is written: a cancelled picker and a save refused while
     * another export is pending leave them unreported. The document picker is intercepted; the file lands in the cache.
     */
    private void saveReport(String label, String json) throws Exception {
        File target = new File(activity.getCacheDir(), "saved-compat-report.json"), fifo = new File(activity.getCacheDir(), "saved-compat-report.fifo");
        target.delete(); fifo.delete();
        SharedPreferences documents = activity.getSharedPreferences("pending_documents", Context.MODE_PRIVATE);
        final File[] destination = {null}; final int[] pickers = {0};
        ActivityMonitor picker = new ActivityMonitor() {
            @Override public ActivityResult onStartActivity(Intent intent) {
                if (!Intent.ACTION_CREATE_DOCUMENT.equals(intent.getAction())) return null;
                pickers[0]++;
                return destination[0] != null ? new ActivityResult(Activity.RESULT_OK, new Intent().setData(Uri.fromFile(destination[0]))) : new ActivityResult(Activity.RESULT_CANCELED, null);
            }
        };
        addMonitor(picker);
        try {
            tapText("Save file", label + " save (cancelled)");
            await(() -> pickers[0] == 1 && !documents.contains("path"), label + ": the cancelled save did not finish", 10000);
            requireUnreported(label, "a cancelled save");
            documents.edit().putString("path", new File(activity.getFilesDir(), "pending-export-harness").getAbsolutePath()).putBoolean("ready", false).putBoolean("picker", true).commit();
            tapText("Save file", label + " save (another export pending)"); SystemClock.sleep(1500);
            require(pickers[0] == 1, label + ": a refused save opened the picker");
            requireUnreported(label, "a save refused while another export is pending");
            documents.edit().clear().commit();
            // A save still writing (a pipe nobody reads yet) when the kept try is set back: the file holds the old
            // snapshot, so only the entries that still read as it shows them are acknowledged.
            android.system.Os.mkfifo(fifo.getAbsolutePath(), 0600); destination[0] = fifo;
            final String[] stale = {null}; final List<String> staleRevisions = new ArrayList<>();
            main(() -> { Object report = field(field(more(), "compatibilityPanel"), "report"); stale[0] = (String)invoke(report, "json"); staleRevisions.addAll(((CompatibilityReport)report).included()); });
            require(stale[0].equals(json) && stale[0].contains("\"kept\": true"), label + ": the preview does not show the kept try");
            tapText("Save file", label + " save (still writing)");
            await(() -> pickers[0] == 2 && documents.contains("path"), label + ": the save did not reach the destination", 10000);
            require(documents.getString("report_tests", "").equals(android.text.TextUtils.join(",", staleRevisions)), label + ": staged revisions " + documents.getString("report_tests", ""));
            final String kept = latestTryId("prompt_tone");
            main(() -> require(activity.service().ble.setBack("prompt_tone"), label + ": Set back did not start " + activity.service().ble.message()));
            awaitPhase(GuidedTest.Phase.DONE, label + " set back during the save");
            await(() -> fake.value("prompt_tone") == 2, label + ": prompt sound not set back", 10000);
            await(() -> { String[] r = {""}; main(() -> { TestJournal.Entry e = activity.service().ble.latestTry("prompt_tone"); r[0] = e.resolution + "/" + e.restoration; }); return r[0].equals("restored/confirmed"); }, label + ": the set back was not confirmed", 10000);
            await(() -> { String[] now = {""}; main(() -> now[0] = (String)invoke(field(field(more(), "compatibilityPanel"), "report"), "json")); return !now[0].equals(stale[0]); }, label + ": the open preview kept the old snapshot", 10000);
            String written = new String(Files.readAllBytes(fifo.toPath()), StandardCharsets.UTF_8);
            require(written.equals(stale[0]), label + ": the slow save did not write its own snapshot");
            await(() -> !documents.contains("path"), label + ": the slow save did not finish", 10000);
            await(() -> { boolean[] others = {true}; main(() -> { for (TestJournal.Entry e : ((TestJournal)field(activity.service().ble, "testJournal")).all(activity.service().ble.profile().id)) if (!e.testId.equals(kept)) others[0] &= e.reported; }); return others[0]; },
                    label + ": the slow save did not acknowledge the unchanged entries", 10000);
            SystemClock.sleep(500);
            main(() -> { TestJournal.Entry e = ((TestJournal)field(activity.service().ble, "testJournal")).find(kept);
                require(e != null && !e.reported && e.restoration.equals("confirmed"), label + ": the set-back try was acknowledged by a file that shows it kept " + (e == null ? null : TestJournal.line(e))); });
            checks.add(label + ": a save still writing when a kept try is set back writes its own snapshot and acknowledges only the entries that still read that way; the confirmed restoration stays unreported and the open preview follows the journal");
            destination[0] = target;
            final String[] fresh = {null}; main(() -> fresh[0] = (String)invoke(field(field(more(), "compatibilityPanel"), "report"), "json"));
            tapText("Save file", label + " save (written)");
            await(() -> pickers[0] == 3 && !documents.contains("path") && target.length() > 0, label + ": the save never wrote the file", 10000);
            await(() -> { boolean[] all = {true}; main(() -> { for (TestJournal.Entry e : ((TestJournal)field(activity.service().ble, "testJournal")).all(activity.service().ble.profile().id)) all[0] &= e.reported; }); return all[0]; },
                    label + ": a written report file did not mark its tries reported", 10000);
            String saved = new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8);
            require(saved.equals(fresh[0]) && !saved.equals(json), label + ": the saved file differs from the refreshed preview");
        } finally { removeMonitor(picker); documents.edit().clear().commit(); target.delete(); fifo.delete(); }
        checks.add(label + ": Save file marks the tries reported only once the file is written; a cancelled picker and a save refused while another export is pending leave them unreported");
    }
    private String latestTryId(String feature) { String[] id = {""}; main(() -> id[0] = activity.service().ble.latestTry(feature).testId); return id[0]; }
    private void requireUnreported(String label, String after) {
        main(() -> { for (TestJournal.Entry e : ((TestJournal)field(activity.service().ble, "testJournal")).all(activity.service().ble.profile().id)) require(!e.reported, label + ": reported after " + after + " " + TestJournal.line(e)); });
    }
    private void requireClean(String text, String where, List<String> ids) {
        String lower = text.toLowerCase(Locale.ROOT), compact = lower.replaceAll("[^0-9a-z]", "");
        List<String> forbidden = new ArrayList<>(ids);
        for (String[] m : MODELS) { forbidden.add(m[1]); forbidden.add(m[3]); forbidden.add(m[3].replace(":", "")); forbidden.add(m[4]); forbidden.add(m[5]); }
        forbidden.add("synthetic"); forbidden.add("test_id"); forbidden.add("testid");
        for (String value : forbidden) {
            String v = value.toLowerCase(Locale.ROOT);
            require(!lower.contains(v), where + " contains " + value);
            String c = v.replaceAll("[^0-9a-z]", "");
            if (c.length() >= 8) require(!compact.contains(c), where + " contains " + value + " without separators");
        }
        require(!java.util.regex.Pattern.compile("\"(mac|address|serial|identity|pin|profile[a-z_]*|id|test_id|created|updated)\"\\s*:").matcher(lower).find(), where + " has a private key");
    }

    // ---- queue: admission at the radio boundary and reply ordering ------------------------------

    /** A fresh motion report invalidates a try's value still waiting behind a GATT write: Not sent, never written. */
    private void queuedAdmissionSafety() throws Exception {
        connect("mapped"); parked("mapped");
        main(() -> {
            ((Handler)field(activity.service().ble,"handler")).removeCallbacksAndMessages(null);
            fake.callbackDelayMs=1500;
            require(activity.service().ble.setExperimental("display_units",0),"try did not start: "+activity.service().ble.message());
        });
        await(() -> {boolean[] queued={false};main(() -> queued[0]=activity.service().ble.pending()!=null);return queued[0];},"try target was not queued");
        main(() -> {
            require(fake.writes.isEmpty()&&(Boolean)field(activity.service().ble,"writing"),"the target should wait behind the slow read write");
            fake.notify(0x91,0,new byte[]{0,1,10});
        });
        await(() -> {boolean[] moving={false};main(() -> moving[0]=!activity.service().ble.stationary() && activity.service().ble.speedRaw()>0);return moving[0];},"moving push was not accepted");
        await(() -> {boolean[] cleared={false};main(() -> cleared[0]=activity.service().ble.pending()==null);return cleared[0];},"Unsafe queued try was not cancelled",6000);
        SystemClock.sleep(1800);
        require(fake.writes.isEmpty(),"Queued try reached the scooter after fresh motion");
        main(() -> { fake.speed=0; fake.callbackDelayMs=0; });
        main(() -> { GuidedTest t=activity.service().ble.guidedTest();require(t!=null&&t.outcomes().get(0)==GuidedTest.Outcome.NOT_SENT,"cancelled try outcome "+(t==null?null:t.outcomeTexts())); });
        main(() -> fake.notify(0x91,0,new byte[]{0,0,0}));
        awaitPhase(GuidedTest.Phase.DONE,"refused try verified unchanged");
        main(() -> require(CompatibilityReport.tryResult(activity.service().ble.guidedTest().entry()).equals("not_sent"),"refused try result"));
        checks.add("A moving report while a try's target waits behind a GATT write cancels it before transmission: Not sent, verified unchanged");
    }

    private void queueLifecycle() throws Exception {
        queuedAdmissionSafety();
        connect("ut5"); parked("ut5");
        main(() -> { ((Handler)field(activity.service().ble,"handler")).removeCallbacksAndMessages(null); fake.holdStatus=true;
            invoke(activity.service().ble,"send",new Class<?>[]{byte[].class},Protocol.frame(0x70,null)); });
        await(() -> fake.heldStatus.size()>=1,"Ordinary poll A did not transmit");
        main(() -> require(activity.service().ble.setExperimental("prompt_tone",4),"try did not start "+activity.service().ble.message()));
        SystemClock.sleep(100);
        require(fake.heldStatus.size()==1,"Baseline probe transmitted before inherited poll drained");
        main(() -> fake.releaseStatus());
        await(() -> fake.heldStatus.size()>=1,"Baseline poll B did not transmit after A drained");
        require(phaseNow().equals("PRECHECK"),"Inherited A falsely satisfied baseline B");
        // An ordinary poll still in flight when the try's write is acknowledged: its reply must never count as the readback.
        main(() -> invoke(activity.service().ble,"send",new Class<?>[]{byte[].class},Protocol.frame(0x70,null)));
        await(() -> fake.heldStatus.size()>=2,"Pre-write ordinary poll did not transmit");
        main(() -> fake.releaseStatus());
        await(() -> { boolean[] ack={false};main(() -> { Object mutation=field(activity.service().ble,"mutation");ack[0]=mutation!=null&&(Boolean)field(mutation,"ack"); });return ack[0]; },"Target ACK did not arrive");
        // A competing ordinary read can already be queued; let its newer response arrive first.
        main(() -> invoke(activity.service().ble,"send",new Class<?>[]{byte[].class},Protocol.frame(0x70,null)));
        await(() -> fake.heldStatus.size()>=2,"Post-ACK competing poll did not transmit");
        main(() -> { while(!fake.heldStatus.isEmpty())fake.releaseStatus(fake.heldStatus.size()-1); });
        await(() -> fake.heldStatus.size()>=1,"Fresh target probe did not follow the drained mixed batch");
        require(phaseNow().equals("TARGET_AWAIT")&&fake.state[21]==4,"Target must await its own readback");
        main(() -> fake.releaseStatus());awaitPhase(GuidedTest.Phase.OBSERVE,"target C");
        main(() -> { fake.holdStatus=false;while(!fake.heldStatus.isEmpty())fake.releaseStatus(); });
        SystemClock.sleep(300);
        // Model an interrupted try with a poll already in flight when the owner explicitly restores.
        main(() -> {
            BleController ble=activity.service().ble;ble.guidedTest().onDisconnect(SystemClock.elapsedRealtime());ble.closeTest();
            fake.holdStatus=true;fake.set(21,2);invoke(ble,"send",new Class<?>[]{byte[].class},Protocol.frame(0x70,null));
        });
        await(() -> fake.heldStatus.size()>=1,"Pre-restore poll did not transmit");
        require(fake.heldStatus.get(0)[21]==2,"Inherited restore poll did not capture baseline");
        final String[] id={null};
        main(() -> { fake.set(21,4);fake.ignoreWrites=true;BleController ble=activity.service().ble;
            id[0]=ble.unresolvedTests().get(0).testId;ble.restoreTest(id[0]); });
        SystemClock.sleep(100);
        require(fake.heldStatus.size()>=1,"Inherited restore poll was lost");
        main(() -> { while(!fake.heldStatus.isEmpty())fake.releaseStatus(fake.heldStatus.size()-1); });
        await(() -> fake.heldStatus.size()>=1,"Restore verification did not transmit after old poll drained");
        require(phaseNow().equals("RESTORE_AWAIT"),"Inherited baseline reply falsely confirmed restoration");
        main(() -> fake.releaseStatus());awaitPhase(GuidedTest.Phase.OUTSTANDING,"failed actual restore");
        main(() -> { require(!activity.service().ble.unresolvedTests().isEmpty(),"Failed restore lost its journal");
            fake.ignoreWrites=false;fake.holdStatus=false;while(!fake.heldStatus.isEmpty())fake.releaseStatus();activity.service().ble.restoreTest(id[0]); });
        awaitPhase(GuidedTest.Phase.DONE,"actual explicit restore");
        main(() -> activity.service().ble.closeTest());
        checks.add("Session poll A cannot impersonate a try's baseline B; reordered pre/post-write replies cannot falsely confirm it; inherited replies cannot clear a failed explicit restore");

        // Three-second write callbacks are valid (below the 3.5 s write watchdog). Reads answer
        // promptly after transmission, while the ordinary two-second status poll competes for FIFO space.
        main(() -> { fake.allReads=true;fake.callbackDelayMs=3000;BleController ble=activity.service().ble;
            invoke(ble,"poll",new Class<?>[]{int.class},(Integer)field(ble,"generation"));ble.startDiscovery(); });
        await(() -> { boolean[] running={true};main(() -> running[0]=activity.service().ble.discovering());return !running[0]; },"Slow discovery exceeded its cap",40000);
        main(() -> {
            BleController ble=activity.service().ble;DiscoveryCoordinator d=ble.discovery();
            require(d!=null&&!d.interrupted(),"Slow callbacks dropped the synthetic link");
            boolean sawUnsent=false;
            for(DiscoveryCoordinator.Read r:d.reads()){
                if(r.attempts==0){sawUnsent=true;require(r.outcome==DiscoveryCoordinator.Outcome.INTERRUPTED,"Unsent read marked silent: "+r);}
                else require(r.outcome==DiscoveryCoordinator.Outcome.READ_SUPPORTED,"Prompt successful read was marked failed: "+r);
            }
            require(sawUnsent,"Slow queue fixture did not exercise hard-cap truncation");
            require(((java.util.Map<?,?>)field(ble,"discoveryQueue")).isEmpty(),"Finished check left queued work");
            require(((ReadPolicy)field(ble,"readPolicy")).periodicBattery().length>0,"Discovery suppressed successful normal polling");
            // Stop with both a tagged discovery frame and an ordinary frame still queued.
            invoke(ble,"send",new Class<?>[]{byte[].class},Protocol.frame(0x72,null));ble.startDiscovery();
            DiscoveryCoordinator next=ble.discovery();Integer cmd=next.nextRead(SystemClock.elapsedRealtime());
            require(cmd!=null,"No discovery reservation");
            invoke(ble,"send",new Class<?>[]{byte[].class,DiscoveryCoordinator.class},Protocol.frame(cmd,null),next);
            invoke(ble,"send",new Class<?>[]{byte[].class},Protocol.frame(0x79,null));
            require(!((java.util.Map<?,?>)field(ble,"discoveryQueue")).isEmpty(),"Stop fixture had no queued discovery work");
            ble.stopDiscovery();
            require(((java.util.Map<?,?>)field(ble,"discoveryQueue")).isEmpty(),"Stop left discovery requests queued");
            boolean ordinary=false;for(Object value:(java.util.ArrayDeque<?>)field(ble,"queue"))if((((byte[])value)[3]&255)==0x79)ordinary=true;
            require(ordinary,"Stopping discovery removed another caller's queued read");
            ble.disconnect();require(((Integer)field(ble,"outstandingStatusReads"))==0,"Disconnect retained old session reads");
        });
        checks.add("Three-second GATT callbacks: attempted reads succeed, unsent reads are interrupted at cap; stop removes only its own queued work");
    }


    // ---- interrupt (process death while a try waits for the owner's answer) ---------------------

    private void interruptStart() throws Exception {
        connect("ut5"); parked("ut5");
        openLab();
        tryToObserve("ut5", "prompt_tone", 4);
        require(fake.writes.size() == 1 && fake.state[21] == 4, "target write " + fake.describeWrites());
        awaitSheetText("Did you see it change?"); sheetShot("interrupt-1-before-kill");
        final String[] line = {""};
        main(() -> { TestJournal.Entry e = activity.service().ble.guidedTest().entry(); line[0] = e.testId + " " + e.phase + " " + e.baseline + " " + e.target + " " + e.kind; });
        JSONObject m = new JSONObject();
        m.put("journal", line[0]); m.put("writes", fake.writes.size()); m.put("prompt", fake.state[21]);
        m.put("screenshots", new JSONArray(screenshots)); m.put("assertions", assertions);
        Files.write(marker().toPath(), m.toString(2).getBytes(StandardCharsets.UTF_8));
        // Abrupt death: no onDestroy, no disconnect, no journal update. The runner relaunches.
        android.os.Process.killProcess(android.os.Process.myPid());
    }
    private void interruptResume() throws Exception {
        JSONObject m = new JSONObject(new String(Files.readAllBytes(marker().toPath()), StandardCharsets.UTF_8));
        String[] before = m.getString("journal").split(" ");
        require(before.length == 5 && before[1].equals("OBSERVE") && before[2].equals("2") && before[3].equals("4") && before[4].equals("experimental"), "interrupt marker " + m);
        ScooterProfiles.Profile p = profileFor("ut5");
        main(() -> {
            // An unfinished try stays recoverable with Developer options hidden.
            require(settings().edit().putBoolean("developer_options", false).commit(), "Could not hide Developer options for recovery");
            BleController ble = activity.service().ble;
            require(!ble.testing() && ble.guidedTest() == null, "a try resumed after process death");
            List<TestJournal.Entry> open = ble.unresolvedTests();
            require(open.size() == 1 && open.get(0).testId.equals(before[0]) && open.get(0).isTry() && open.get(0).phase.equals("OUTSTANDING") && open.get(0).restoration.equals("outstanding")
                    && open.get(0).outcomes.equals(Arrays.asList("setting_confirmed", "interrupted")), "journal after restart " + open);
        });
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "controls")); settle();
        awaitText("Unfinished try · Prompt sound"); screenshot("interrupt-2-relaunch-banner");
        main(() -> { View review = root().findViewWithTag("scooter-review-test"); require(review != null && review.isShown(), "no Review pill"); require("Review unfinished try".contentEquals(review.getContentDescription()), "Review pill label " + review.getContentDescription()); review.performClick(); }); settle();
        awaitText("Unfinished try · Prompt sound");
        main(() -> require(!texts(root()).contains("Run check") && !texts(root()).contains("Run again")
                && !texts(root()).toLowerCase(Locale.ROOT).contains("try a setting") && root().findViewWithTag("developer-compatibility-check") == null,
                "Recovery opened the developer checker"));
        main(() -> require(texts(root()).contains("Connect to this scooter to review.") && texts(root()).contains("Setting confirmed · Interrupted/no response"), "offline review " + texts(root()).replace('\n', '|')));
        revealText("Unfinished try · Prompt sound"); screenshot("interrupt-3-relaunch-review-offline");
        // The synthetic scooter kept the value tried through the app's death.
        fake = new FakeScooter(this, "ut5"); fake.set(21, 4); fake.motion = true;
        main(() -> fake.attach(activity.service().ble, p));
        await(() -> { boolean[] r = {false}; main(() -> r[0] = activity.service().ble.ready()); return r[0]; }, "reconnect not ready", 12000);
        SystemClock.sleep(6000);
        require(fake.writes.isEmpty() && fake.reads.contains(0x70), "automatic writes after restart and reconnect: " + fake.describeWrites());
        awaitText("Restore to Sound 3");
        main(() -> require(texts(root()).contains("Still on the test value, Sound 5.") && texts(root()).contains("Keep Sound 5"), "review text"));
        revealText("Unfinished try · Prompt sound"); screenshot("interrupt-4-reconnected-review");
        require(fake.writes.isEmpty(), "write before the Restore tap");
        tapText("Restore to Sound 3", "restore");
        awaitPhase(GuidedTest.Phase.DONE, "restore after restart");
        require(fake.writes.size() == 1 && fake.lastWriteValue() == 2 && fake.state[21] == 2, "restore writes " + fake.describeWrites());
        main(() -> {
            GuidedTest t = activity.service().ble.guidedTest();
            require(t.outcomeTexts().equals(Arrays.asList("Setting confirmed", "Interrupted/no response", "Restoration confirmed")) && t.restorationStatus().equals("confirmed"), "outcomes after restore " + t.outcomeTexts());
            require(activity.service().ble.unresolvedTests().isEmpty(), "still unfinished");
            require(CompatibilityReport.tryResult(activity.service().ble.latestTry("prompt_tone")).equals("worked_here"), "the try's result after recovery");
        });
        awaitText("Restoration confirmed"); revealText("Try · Prompt sound"); screenshot("interrupt-5-restored");
        closeCard("ut5");
        main(() -> require(texts(root()).contains("No unfinished tests.") && !texts(root()).toLowerCase(Locale.ROOT).contains("try a setting")
                && !texts(root()).contains("Run check"), "Recovery completion exposed new developer work"));
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "controls")); settle();
        main(() -> require(byText(root(), "Unfinished try · Prompt sound") == null, "banner after restore"));
        checks.add("Process killed while a try waited for the owner's answer: on relaunch the journal reads OUTSTANDING (Setting confirmed · Interrupted/no response), the Scooter page shows Unfinished try, "
                + "nothing is written on start or reconnect, and one explicit Restore tap writes the earlier value once → Restoration confirmed");
    }

    // ---- Experimental controls on a synthetic N65i --------------------------------------------

    private void experimental() throws Exception {
        String label = N65I[0];
        connect(label); parked(label);
        openLab();
        // The custom speed cap joins the experiments the official app hides on the N65i.
        List<String> expected = Arrays.asList("start_speed", "turn_sound", "speed_limit", "traction_control", "hill_start_assist", "parking_assist", "hill_descent_control", "prompt_tone", "range_algorithm");
        await(() -> labKeys().equals(expected), label + ": Experimental controls rows " + labKeys() + " expected " + expected, 10000);
        main(() -> { for (String key : expected) require(activity.service().ble.tryDecision(key).available(), label + ": " + key + " not available: " + activity.service().ble.tryDecision(key).reasonText()); });
        awaitText("Untested on your model", "Untested here", "Untested"); screenshot(label + "-lab-list");
        revealText("Help verify"); screenshot(label + "-lab-help-verify");
        int writes = fake.writes.size();
        main(() -> {
            BleController ble = activity.service().ble;
            // Never added here, an offered control never as an experiment, and a hidden one never as an ordinary change.
            for (String key : new String[]{"maximum_speed", "charge_limit", "lock", "lock_expiry", "low_power", "acceleration", "dual_drive", "overspeed_control"})
                require(!ble.setExperimental(key, key.equals("maximum_speed") ? 25 : key.equals("charge_limit") ? 90 : 1), label + ": " + key + " sent as an experiment");
            require(!ble.setExperimental("display_units", 0), label + ": a verified, offered control sent as a try");
            require(!ble.set("turn_sound", 1), label + ": a hidden control sent as an ordinary change");
            ble.visibility(true, true, 0);
            require(ble.tryDecision("turn_sound").reason == CapabilityResolver.Reason.RECORDING && !ble.setExperimental("turn_sound", 1), label + ": sent while recording");
            ble.visibility(true, false, 0);
        });
        fake.speed = 3;
        await(() -> labReason("turn_sound") == CapabilityResolver.Reason.MOVING, label + ": moving not refused: " + labReason("turn_sound"), 10000);
        main(() -> require(!activity.service().ble.setExperimental("turn_sound", 1), label + ": sent while moving"));
        awaitText("Stop the scooter to try these."); screenshot(label + "-lab-moving");
        fake.speed = 0; fake.set(2, 1);
        await(() -> labReason("turn_sound") == CapabilityResolver.Reason.LOCKED, label + ": locked not refused: " + labReason("turn_sound"), 10000);
        main(() -> require(!activity.service().ble.setExperimental("turn_sound", 1), label + ": sent while locked"));
        awaitText("Unlock the scooter to try these."); screenshot(label + "-lab-locked");
        fake.set(2, 0);
        await(() -> labReason("turn_sound") == CapabilityResolver.Reason.NONE, label + ": parked and unlocked not admitted: " + labReason("turn_sound"), 10000);
        require(fake.writes.size() == writes, label + ": refusals wrote " + fake.describeWrites());
        checks.add(label + ": never-added controls, a verified control, an ordinary change of a hidden control, moving, locked and recording all refused with zero writes");

        // Worked here, then the owner's answer, then Set back from the sheet: all read back.
        openTry("turn_sound"); chooseOption(1); sheetShot(label + "-lab-sheet");
        tapSheet("go");
        awaitPhase(GuidedTest.Phase.OBSERVE, label + " turn sound confirmed");
        awaitSheetText("Did you see it change?"); sheetShot(label + "-lab-observe");
        tapSheetTag("lab-observe-yes");
        awaitPhase(GuidedTest.Phase.DONE, label + " turn sound kept");
        awaitSheetText("Worked here"); sheetShot(label + "-lab-worked");
        main(() -> require(activity.service().ble.latestTry("turn_sound").effect.equals("observed"), label + ": the answer was not saved"));
        tapSheet("back");
        awaitPhase(GuidedTest.Phase.DONE, label + " turn sound set back");
        await(() -> fake.value("turn_sound") == 0, label + ": Set back did not restore turn sound", 10000);
        require(fake.writes.size() == writes + 2, label + ": worked-here writes " + fake.describeWrites());
        closeSheet();
        checks.add(label + ": turn signal sound tried (readback confirmed, \"Did you see it change?\" Yes, \"Worked here\"), kept, then set back from the sheet: two writes");

        // No change seen: acknowledged but ignored; the try is done and the link stays up.
        fake.ignoreWrites = true;
        try { openTry("traction_control"); chooseOption(0); tapSheet("go"); awaitPhase(GuidedTest.Phase.DONE, label + " ignored try"); } finally { fake.ignoreWrites = false; }
        main(() -> require(activity.service().ble.ready() && CompatibilityReport.tryResult(activity.service().ble.latestTry("traction_control")).equals("no_change_seen"), label + ": an ignored try dropped the link or reads " + CompatibilityReport.tryResult(activity.service().ble.latestTry("traction_control"))));
        awaitSheetText("No change seen"); sheetShot(label + "-lab-no-change");
        closeSheet();
        require(fake.state[11] == 1 && fake.writes.size() == writes + 3, label + ": no-change writes " + fake.describeWrites());

        // Refused: the scooter rejects the write with a status code.
        fake.rejectNext = 3;
        openTry("start_speed"); chooseOption(0); tapSheet("go");
        awaitPhase(GuidedTest.Phase.DONE, label + " rejected try");
        awaitSheetText("Refused by scooter"); sheetShot(label + "-lab-refused");
        closeSheet();
        require(fake.state[19] == 3 && fake.writes.size() == writes + 4, label + ": refused writes " + fake.describeWrites());

        // Leaving the question unanswered counts as Skip; Set back works in one tap from the list.
        openTry("prompt_tone"); chooseOption(4); tapSheet("go");
        awaitPhase(GuidedTest.Phase.OBSERVE, label + " prompt sound confirmed");
        closeSheet();
        awaitPhase(GuidedTest.Phase.DONE, label + " prompt sound kept by closing");
        main(() -> require(activity.service().ble.latestTry("prompt_tone").effect.equals("not_checked") && fake.state[21] == 4, label + ": closing the sheet did not skip"));
        awaitText("Set back"); revealText("Prompt sound"); screenshot(label + "-lab-results");
        main(() -> { for (Object row : (List<?>)field(more(), "labRows")) if (((ScooterCommands.Feature)field(row, "feature")).key.equals("prompt_tone")) {
                TextView pill = (TextView)field(row, "action"); require("Set back".contentEquals(pill.getText()) && pill.isEnabled(), label + ": row pill " + pill.getText()); pill.performClick(); } });
        await(() -> fake.state[21] == 2 && activity.service().ble.pending() == null && !activity.service().ble.testing(), label + ": one-tap Set back did not restore prompt sound", 10000);
        require(fake.writes.size() == writes + 6, label + ": set-back writes " + fake.describeWrites());
        unconfirmed(label);
        require(fake.matchesBaseline(), label + ": the synthetic scooter did not end at its starting values: " + fake.diff());
        main(() -> activity.service().ble.disconnect()); settle();
        checks.add(label + ": an ignored try reads \"No change seen\" without dropping the link, a rejected one \"Refused by scooter\", closing the question skips it, and Set back works in one tap from the list; the scooter ends at its starting values");
    }
    /**
     * A try the scooter applied and acknowledged, but no readback confirmed: neither "Not sent" nor "No change seen" is
     * true. The try stays unfinished (row Review, sheet note), is reported as unconfirmed or interrupted, and the owner's
     * review then sets it back, confirmed by readback.
     */
    private void unconfirmed(String label) throws Exception {
        for (String mode : new String[]{"drop", "timeout", "stale", "reordered"}) {
            boolean drop = mode.equals("drop"), stale = mode.equals("stale"), reordered = mode.equals("reordered");
            int sent = fake.writes.size();
            openTry("turn_sound"); chooseOption(1);
            main(() -> fake.holdStatus = true);
            tapSheet("go");
            await(() -> fake.heldStatus.size() >= 1, label + ": no baseline probe", 10000);
            // Stale and reordered: ordinary polls sent before the write, answered with the old value (Off) only after the ACK.
            int earlier = reordered ? 2 : stale ? 1 : 0;
            for (int i = 0; i < earlier; i++) {
                final int count = i + 2;
                main(() -> invoke(activity.service().ble, "send", new Class<?>[]{byte[].class}, Protocol.frame(0x70, null)));
                await(() -> fake.heldStatus.size() >= count, label + ": no pre-write poll");
            }
            main(() -> fake.releaseStatus());
            await(() -> fake.value("turn_sound") == 1 && fake.writes.size() == sent + 1, label + ": the try never reached the scooter", 10000);
            if (stale) { SystemClock.sleep(600); main(() -> fake.releaseStatus()); }
            // Reordered: newer polls answer first with valid short statuses (no turn-sound byte), then the old full snapshots.
            // Only replies after every earlier read has drained may count, and one is still owed: never "no change".
            if (reordered) {
                SystemClock.sleep(600);
                final int[] base = {0}; main(() -> base[0] = fake.heldStatus.size());
                for (int i = 0; i < 2; i++) {
                    final int count = base[0] + i + 1;
                    main(() -> invoke(activity.service().ble, "send", new Class<?>[]{byte[].class}, Protocol.frame(0x70, null)));
                    await(() -> fake.heldStatus.size() >= count, label + ": the post-ACK poll was not sent", 4000);
                }
                main(() -> {
                    for (int i = 0; i < 2; i++) { byte[] fresh = fake.heldStatus.remove(base[0]); fake.notify(0x70, 0, java.util.Arrays.copyOf(fresh, 10)); }
                    for (int i = 0; i < earlier; i++) fake.releaseStatus();
                });
            }
            if (drop) { SystemClock.sleep(600); main(() -> activity.service().ble.disconnect()); }
            final String wanted = drop ? "interrupted" : "unconfirmed";
            await(() -> { String[] r = {""}; main(() -> { TestJournal.Entry e = activity.service().ble.latestTry("turn_sound"); r[0] = e == null || e.resolved ? "" : CompatibilityReport.tryResult(e); }); return wanted.equals(r[0]); },
                    label + ": an applied, unconfirmed try is not " + wanted, 30000);
            awaitSheetText("This try didn't finish"); sheetShot(label + "-lab-unconfirmed-" + mode);
            main(() -> { String shown = String.valueOf(((TextView)field(field(more(), "labSheet"), "status")).getText());
                require(!shown.contains("Not sent") && !shown.contains("No change"), label + ": an applied try reads " + shown); });
            closeSheet();
            main(() -> { fake.holdStatus = false; fake.heldStatus.clear(); });
            boolean[] ready = {false}; main(() -> ready[0] = activity.service().ble.ready());
            if (!ready[0]) reconnect(label, fake.writes.size());
            parked(label);
            final int writes = fake.writes.size();
            openTry("turn_sound"); tapSheetTag("lab-review");
            awaitText("Restore to Off");
            main(() -> require(texts(root()).contains("Still on the test value, On."), label + ": review " + texts(root()).replace('\n', '|')));
            tapText("Restore to Off", label + " restore");
            awaitPhase(GuidedTest.Phase.DONE, label + " unconfirmed try restored");
            await(() -> fake.value("turn_sound") == 0, label + ": review did not set it back", 10000);
            require(fake.writes.size() == writes + 1, label + ": restore writes " + fake.describeWrites());
            main(() -> { TestJournal.Entry e = activity.service().ble.latestTry("turn_sound");
                require(e.resolved && e.restoration.equals("confirmed") && CompatibilityReport.tryResult(e).equals(wanted), label + ": after review " + TestJournal.line(e)); });
            awaitText("Restoration confirmed"); closeCard(label);
            openLab();
        }
        checks.add(label + ": a try the scooter applied but never confirmed stays unfinished and is reported unconfirmed or interrupted, never Not sent or No change seen, after a dropped link, a timeout, an old snapshot arriving after the ACK, and reordered replies; the review sets it back");
    }

    // ---- Experimental controls helpers ----------------------------------------------------------

    private List<String> labKeys() { List<String> out = new ArrayList<>(); main(() -> { for (Object row : (List<?>)field(more(), "labRows")) if (field(row, "action") != null) out.add(((ScooterCommands.Feature)field(row, "feature")).key); }); return out; }
    private CapabilityResolver.Reason labReason(String key) { CapabilityResolver.Reason[] r = {null}; main(() -> r[0] = activity.service().ble.tryDecision(key).reason); return r[0]; }
    private Object sheet() { Object sheet = field(more(), "labSheet"); require(sheet != null, "No Try sheet"); return sheet; }
    private View sheetRoot() { return ((android.app.AlertDialog)field(sheet(), "dialog")).getWindow().getDecorView(); }
    private void openTry(String key) {
        main(() -> { View row = root().findViewWithTag("lab-row-" + key); require(row != null, "No row " + key); row.performClick(); }); settle();
        main(() -> require(sheet() != null && (texts(sheetRoot()).contains("Untested on ") || texts(sheetRoot()).contains("Unverified on ")), "Try sheet for " + key + ": " + texts(sheetRoot()).replace('\n', '|')));
    }
    private void chooseOption(int value) { main(() -> { View option = sheetRoot().findViewWithTag("lab-option-" + value); require(option != null, "No option " + value); option.performClick(); }); settle(); }
    private void tapSheet(String button) { main(() -> { View b = (View)field(sheet(), button); require(b.isShown() && b.isEnabled(), "Sheet " + button + " unavailable"); b.performClick(); }); settle(); }
    private void tapSheetTag(String tag) { main(() -> { View b = sheetRoot().findViewWithTag(tag); require(b != null && b.isShown() && b.isEnabled(), "Sheet " + tag + " unavailable"); b.performClick(); }); settle(); }
    private void awaitSheetText(String text) {
        long end = SystemClock.uptimeMillis() + 10000; final boolean[] found = {false};
        do { main(() -> found[0] = texts(sheetRoot()).contains(text)); if (found[0]) break; SystemClock.sleep(120); } while (SystemClock.uptimeMillis() < end);
        final String[] shown = {""}; main(() -> shown[0] = texts(sheetRoot()));
        require(found[0], "Sheet never showed " + text + ": " + shown[0].replace('\n', '|')); settle();
    }
    private void sheetShot(String name) throws Exception { settle(); screenshot(name); }
    private void closeSheet() { main(() -> ((android.app.AlertDialog)field(sheet(), "dialog")).dismiss()); settle(); }
    private void closeSheetIfOpen() { main(() -> { Object s = field(more(), "labSheet"); if (s != null) ((android.app.AlertDialog)field(s, "dialog")).dismiss(); }); settle(); }
    private void revealTag(String tag) {
        main(() -> { View v = root().findViewWithTag(tag); require(v != null && v.isShown(), "Missing " + tag); v.requestRectangleOnScreen(new Rect(0, 0, v.getWidth(), Math.min(v.getHeight(), root().getHeight() / 2)), true); }); settle();
    }

    // ---- Synthetic link --------------------------------------------------------------------------

    /**
     * A synthetic scooter behind a fake BluetoothGatt. BleController keeps its own queue, gate and
     * callbacks; the fake records each written frame and answers on the main looper. Status bytes
     * use OEM offsets (offset n = notification data[n + 1]).
     */
    static final class FakeScooter {
        final GuidedTestValidation owner; final String label;
        final byte[] state = new byte[44], baseline;
        final List<byte[]> writes = new CopyOnWriteArrayList<>(); final List<Integer> reads = new CopyOnWriteArrayList<>();
        volatile boolean motion, ignoreWrites, holdStatus, allReads; volatile int speed, rejectNext, callbackDelayMs;
        final List<byte[]> heldStatus=new CopyOnWriteArrayList<>();private String serial;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private BluetoothGatt gatt; private BluetoothGattCharacteristic writer, notifier; private BluetoothGattCallback callbacks;
        FakeScooter(GuidedTestValidation owner, String label) {
            this.owner = owner; this.label = label;
            byte[] d = state;
            d[1]=3;d[2]=0;d[3]=1;d[4]=1;d[5]=60;d[6]=2;d[7]=1;d[8]=1;d[10]=(byte)0x81;d[11]=1;d[12]=0;d[13]=0;d[15]=1;d[16]=0x01;d[17]=(byte)0x82;d[18]=0x10;
            d[19]=3;d[20]=(byte)0x99;d[21]=2;d[25]=40;d[26]=0;d[31]=90;d[32]=0;d[33]=1;d[34]=2;d[37]=5;d[38]=0;d[39]=0;d[42]=(byte)0x8f;
            baseline = d.clone();
        }
        void set(int offset, int value) { state[offset] = (byte)value; }
        static int offset(String feature) {
            switch (feature) { case "headlight": return 4; case "range_algorithm": return 6; case "display_units": return 7; case "auto_sensor": return 8; case "turn_sound": return 12;
                case "traction_control": return 11; case "start_speed": return 19; case "speed_limit": return 20; case "prompt_tone": return 21; case "drive_speed_limit": return 42;
                default: throw new IllegalArgumentException(feature); }
        }
        static int offset(int cmd) {
            switch (cmd) { case 0x54: return 4; case 0x56: return 6; case 0x55: return 7; case 0x57: return 8; case 0x60: return 12;
                case 0x5f: return 11; case 0x6a: return 19; case 0x6b: return 20; case 0x6c: return 21; default: return -1; }
        }
        /** The speed caps' bit 7 is their enable flag: clear reads Off (0) whatever the low bits keep. */
        static int cap(int raw) { return (raw & 128) == 0 ? 0 : raw & 127; }
        int value(String feature) { int raw = state[offset(feature)] & 255; return feature.equals("speed_limit") || feature.equals("drive_speed_limit") ? cap(raw) : raw; }
        /** The value a write carries: one byte, or 0x6e [selector 3, value] for the drive mode speed cap. */
        static int written(byte[] f) {
            int cmd = f[3] & 255; boolean selected = (f[4] & 255) == 2; int raw = selected ? f[6] & 255 : f[5] & 255;
            return cmd == 0x6b || cmd == 0x6e && selected && (f[5] & 255) == 3 ? cap(raw) : raw;
        }
        int lastWriteValue() { return written(writes.get(writes.size() - 1)); }
        boolean matchesBaseline() { return Arrays.equals(state, baseline); }
        String diff() { StringBuilder b = new StringBuilder(); for (int i = 0; i < state.length; i++) if (state[i] != baseline[i]) b.append(i).append(':').append(baseline[i]).append("->").append(state[i]).append(' '); return b.toString(); }
        String describeWrites() { StringBuilder b = new StringBuilder("["); for (byte[] f : writes) b.append(String.format(Locale.ROOT, "0x%02x=%d ", f[3] & 255, written(f))); return b.append(']').toString(); }

        /** Runs on the main looper: BleController's fields become an authenticated, ready session on this link. */
        void attach(BleController ble, ScooterProfiles.Profile p) {
            try {
                serial=p.serial;
                BluetoothAdapter adapter = owner.getTargetContext().getSystemService(BluetoothManager.class).getAdapter();
                BluetoothDevice device = adapter.getRemoteDevice(p.mac);
                Class<?> unsafeType = Class.forName("sun.misc.Unsafe"); Field theUnsafe = unsafeType.getDeclaredField("theUnsafe"); theUnsafe.setAccessible(true);
                gatt = (BluetoothGatt)unsafeType.getMethod("allocateInstance", Class.class).invoke(theUnsafe.get(null), BluetoothGatt.class);
                Class<?> service = Class.forName("android.bluetooth.IBluetoothGatt");
                InvocationHandler radio = (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        if (method.getName().equals("equals")) return proxy == args[0];
                        if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                        return "SyntheticGattService";
                    }
                    if (method.getName().equals("writeCharacteristic")) {
                        byte[] frame = null; if (args != null) for (Object a : args) if (a instanceof byte[]) frame = ((byte[])a).clone();
                        setHidden(gatt, "mDeviceBusy", false);
                        final byte[] sent = frame; if (sent != null) handler.postDelayed(() -> received(sent), 15);
                        return 0;
                    }
                    Class<?> r = method.getReturnType();
                    return r == int.class ? 0 : r == boolean.class ? false : r == long.class ? 0L : null;
                };
                setHidden(gatt, "mService", Proxy.newProxyInstance(service.getClassLoader(), new Class<?>[]{service}, radio));
                setHidden(gatt, "mClientRegistered", true); setHidden(gatt, "mDeviceBusy", false); setHidden(gatt, "mDeviceBusyLock", new Object()); setHidden(gatt, "mStateLock", new Object());
                setHidden(gatt, "mDevice", device); setHidden(gatt, "mServices", new CopyOnWriteArrayList<BluetoothGattService>());
                BluetoothGattService s = new BluetoothGattService(BleController.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);
                setHidden(s, "mDevice", device);
                writer = new BluetoothGattCharacteristic(BleController.WRITE, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE);
                notifier = new BluetoothGattCharacteristic(BleController.NOTIFY, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0);
                s.addCharacteristic(writer); s.addCharacteristic(notifier);
                callbacks = (BluetoothGattCallback)owner.field(ble, "callbacks");
                setApp(ble, "profile", p); setApp(ble, "gatt", gatt); setApp(ble, "writer", writer); setApp(ble, "serial", p.serial);
                setApp(ble, "authenticated", true); setApp(ble, "discovered", true); setApp(ble, "subscribed", true); setApp(ble, "wanted", false);
                setApp(ble, "authVariant", "challenge_free"); setApp(ble, "state", BleController.State.READY);
                owner.invoke(ble, "policy");
                // The session's first status, then BleController's own 2 s poll.
                notify(0x70, 0, state);
                owner.invoke(ble, "poll", new Class<?>[]{int.class}, (Integer)owner.field(ble, "generation"));
            } catch (Exception e) { throw new IllegalStateException("Synthetic link unavailable (is hidden_api_policy relaxed?)", e); }
        }
        /** One frame written by BleController: complete the GATT write, then answer like the scooter. */
        private void received(byte[] frame) {
            if (frame.length < 7 || (frame[0] & 255) != 0x55 || (frame[1] & 255) != 0xaa) return;
            final BluetoothGatt sentGatt=gatt;handler.postDelayed(() -> callbacks.onCharacteristicWrite(sentGatt, writer, 0),callbackDelayMs);
            int cmd = frame[3] & 255;
            if (frame.length == 7) {
                reads.add(cmd);
                if (cmd == 0x70) { if(holdStatus)heldStatus.add(state.clone());else notify(0x70, 0, state); if (motion) notify(0x91, 0, new byte[]{0, 0, (byte)speed}); }
                else if(allReads)notify(cmd,0,cmd==0x74?serial.getBytes(StandardCharsets.US_ASCII):new byte[40]);
                return; // other reads stay silent: this synthetic scooter only reports status
            }
            if (cmd == 0x30 || cmd == 0x31) throw new IllegalStateException("The synthetic link never authenticates");
            writes.add(frame);
            int length = frame[4] & 255, at = offset(cmd), value = 5;
            // The drive mode speed cap is 0x6e [selector 3, value]; every other write here is one byte.
            if (cmd == 0x6e && length == 2 && (frame[5] & 255) == 3) { at = 42; value = 6; }
            else if (length != 1) at = -1;
            if (at < 0) throw new IllegalStateException(String.format(Locale.ROOT, "Unexpected write 0x%02x length %d", cmd, length));
            if (rejectNext != 0) { int code = rejectNext; rejectNext = 0; notify(cmd, code, new byte[0]); return; }
            if (!ignoreWrites) state[at] = frame[value];
            notify(cmd, 0, new byte[0]);
        }
        void releaseStatus(){releaseStatus(0);}
        void releaseStatus(int index){if(heldStatus.size()>index)notify(0x70,0,heldStatus.remove(index));}
        private void notify(int cmd, int status, byte[] payload) {
            byte[] p = new byte[payload.length + 9]; p[0] = 0x55; p[1] = (byte)0xaa; p[3] = (byte)cmd; p[4] = (byte)(payload.length + 1); p[5] = (byte)status;
            System.arraycopy(payload, 0, p, 6, payload.length);
            int sum = 0; for (int i = 0; i < p.length - 3; i++) sum += p[i] & 255;
            p[p.length - 3] = (byte)sum; p[p.length - 2] = (byte)0xfe; p[p.length - 1] = (byte)0xfd;
            final byte[] packet = p; final BluetoothGatt g = gatt;
            handler.postDelayed(() -> callbacks.onCharacteristicChanged(g, notifier, packet), 10);
        }
        private static void setHidden(Object target, String name, Object value) throws Exception {
            Class<?> type = target.getClass(); Field f = null;
            while (type != null && f == null) { try { f = type.getDeclaredField(name); } catch (NoSuchFieldException e) { type = type.getSuperclass(); } }
            if (f == null) throw new NoSuchFieldException(name);
            f.setAccessible(true); f.set(target, value);
        }
        private static void setApp(Object target, String name, Object value) throws Exception { setHidden(target, name, value); }
    }

    // ---- Helpers --------------------------------------------------------------------------------

    private int other(CapabilityResolver.Decision d) { Integer now = d.current(); for (int v : d.values()) if (now == null || v != now) return v; throw new IllegalStateException("No other value for " + d.feature); }
    private CapabilityResolver.Reason reason(String feature) { CapabilityResolver.Reason[] r = {null}; main(() -> r[0] = activity.service().ble.testDecision(feature).reason); return r[0]; }
    private void awaitPhase(GuidedTest.Phase phase, String what) {
        await(() -> { Object[] p = {null}; main(() -> { GuidedTest t = activity.service().ble.guidedTest(); p[0] = t == null ? null : t.phase(); }); return p[0] == phase; },
                what + ": phase " + phaseNow() + " note " + noteNow() + " writes " + (fake == null ? "" : fake.describeWrites()), 15000);
    }
    private String phaseNow() { Object[] p = {null}; main(() -> { GuidedTest t = activity.service().ble.guidedTest(); p[0] = t == null ? "none" : t.phase(); }); return String.valueOf(p[0]); }
    private String noteNow() { Object[] p = {null}; main(() -> { GuidedTest t = activity.service().ble.guidedTest(); p[0] = t == null ? activity.service().ble.message() : t.note(); }); return String.valueOf(p[0]); }
    /** Waits for the panel's 1 Hz refresh to draw one of these texts (exact TextView text or a substring of one). */
    private void awaitText(String... options) {
        long end = SystemClock.uptimeMillis() + 10000; final boolean[] found = {false};
        do { main(() -> { String shown = texts(root()); for (String o : options) found[0] |= shown.contains(o); }); if (found[0]) break; SystemClock.sleep(120); } while (SystemClock.uptimeMillis() < end);
        if (!found[0]) { final String[] shown = {""}; main(() -> shown[0] = texts(root())); throw new IllegalStateException("Missing " + Arrays.toString(options) + " in " + shown[0].replace('\n', '|')); }
        settle();
    }
    private static String join(List<String> parts) { StringBuilder b = new StringBuilder(); for (String p : parts) b.append(b.length() == 0 ? "" : " · ").append(p); return b.toString(); }
    private void revealText(String... options) {
        main(() -> {
            TextView found = null; for (String text : options) if (found == null) found = byText(root(), text);
            if (found == null) for (String text : options) if (found == null) found = containing(root(), text);
            require(found != null, "Missing " + Arrays.toString(options));
            View v = found; android.widget.ScrollView scroll = null;
            for (android.view.ViewParent p = v.getParent(); p != null; p = p.getParent()) if (p instanceof android.widget.ScrollView) { scroll = (android.widget.ScrollView)p; break; }
            if (scroll == null) { found.requestRectangleOnScreen(new Rect(0, 0, found.getWidth(), found.getHeight()), true); return; }
            int[] at = new int[2], top = new int[2]; found.getLocationInWindow(at); scroll.getLocationInWindow(top);
            scroll.scrollTo(0, Math.max(0, scroll.getScrollY() + at[1] - top[1] - found.getHeight()));
        }); settle();
    }
    /** Scrolls a line inside a long TextView (the report summary) to near the top of the screen. */
    private void revealLine(String text) {
        main(() -> {
            TextView found = containing(root(), text); require(found != null && found.getLayout() != null, "Missing line " + text);
            int line = found.getLayout().getLineForOffset(found.getText().toString().indexOf(text));
            android.widget.ScrollView scroll = null;
            for (android.view.ViewParent p = found.getParent(); p != null; p = p.getParent()) if (p instanceof android.widget.ScrollView) { scroll = (android.widget.ScrollView)p; break; }
            require(scroll != null, "No scroll view");
            int[] at = new int[2], top = new int[2]; found.getLocationInWindow(at); scroll.getLocationInWindow(top);
            scroll.scrollTo(0, Math.max(0, scroll.getScrollY() + at[1] - top[1] + found.getTotalPaddingTop() + found.getLayout().getLineTop(line) - found.getLineHeight()));
        }); settle();
    }
    private TextView byText(View view, String text) {
        if (view instanceof TextView && text.equals(plain(((TextView)view).getText())) && view.isShown()) return (TextView)view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) { TextView found = byText(((ViewGroup)view).getChildAt(i), text); if (found != null) return found; }
        return null;
    }
    private TextView containing(View view, String text) {
        if (view instanceof TextView && plain(((TextView)view).getText()).contains(text) && view.isShown()) return (TextView)view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) { TextView found = containing(((ViewGroup)view).getChildAt(i), text); if (found != null) return found; }
        return null;
    }
    private View byDescription(View view, String description) {
        if (view.getContentDescription() != null && description.contentEquals(view.getContentDescription()) && view.isShown()) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) { View found = byDescription(((ViewGroup)view).getChildAt(i), description); if (found != null) return found; }
        return null;
    }
    /** Shown text without word joiners (CompatibilityPanel.unbroken). */
    private static String plain(CharSequence text) { return text.toString().replace("\u2060", ""); }
    private View clickable(View view) { for (View v = view; v != null; v = v.getParent() instanceof View ? (View)v.getParent() : null) if (v.isClickable()) return v; return null; }
    private String texts(View view) {
        StringBuilder out = new StringBuilder();
        if (view instanceof TextView && view.isShown()) out.append(plain(((TextView)view).getText())).append('\n');
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) out.append(texts(((ViewGroup)view).getChildAt(i)));
        return out.toString();
    }
    private MoreScreen more() { Object screen = field(activity, "screen"); require(screen instanceof MoreScreen, "More screen is not active"); return (MoreScreen)screen; }
    private File outDir() { File dir = new File(getTargetContext().getExternalFilesDir(null), "guided-validation"); require(dir.isDirectory() || dir.mkdirs(), "Output directory unavailable"); return dir; }
    private File marker() { return new File(outDir(), "interrupt-marker.json"); }
    private String variant() { return arguments.getString("variant", "guided").replaceAll("[^a-zA-Z0-9_-]", ""); }
    private SharedPreferences settings() { return prefs("app_settings"); }
    private SharedPreferences prefs(String name) { return getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE); }
    private View root() { return (View)field(activity, "root"); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(300); waitForIdleSync(); }
    /** Runs on the main thread; a failed check there is rethrown here so the run fails cleanly instead of crashing the app. */
    private void main(Runnable task) {
        final Throwable[] failure = {null};
        runOnMainSync(() -> { try { task.run(); } catch (Throwable t) { failure[0] = t; } });
        if (failure[0] instanceof RuntimeException) throw (RuntimeException)failure[0];
        if (failure[0] instanceof Error) throw (Error)failure[0];
        if (failure[0] != null) throw new IllegalStateException(failure[0]);
    }
    /** Waits for the panel's 1 Hz refresh to draw an enabled control with this description, then taps it. */
    private void tapDescription(String description, String what) {
        final boolean[] tapped = {false}; long end = SystemClock.uptimeMillis() + 10000;
        do { main(() -> { View v = byDescription(root(), description); if (v != null && v.isEnabled()) { v.performClick(); tapped[0] = true; } }); if (tapped[0]) break; SystemClock.sleep(120); } while (SystemClock.uptimeMillis() < end);
        require(tapped[0], what + ": no enabled control \"" + description + "\"");
    }
    private void tapText(String text, String what) {
        final boolean[] tapped = {false}; long end = SystemClock.uptimeMillis() + 10000;
        do { main(() -> { TextView v = byText(root(), text); if (v != null && v.isEnabled()) { v.performClick(); tapped[0] = true; } }); if (tapped[0]) break; SystemClock.sleep(120); } while (SystemClock.uptimeMillis() < end);
        require(tapped[0], what + ": no enabled \"" + text + "\"");
    }
    private void await(BooleanSupplier condition, String message) { await(condition, message, 8000); }
    private void await(BooleanSupplier condition, String message, long ms) { long end = SystemClock.uptimeMillis() + ms; while (!condition.getAsBoolean() && SystemClock.uptimeMillis() < end) SystemClock.sleep(60); require(condition.getAsBoolean(), message); }
    private void require(boolean condition, String message) { assertions++; if (!condition) throw new IllegalStateException(message); }
    Object field(Object target, String name) { try { Class<?> type = target.getClass(); while (type != null) { try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch (NoSuchFieldException e) { type = type.getSuperclass(); } } throw new NoSuchFieldException(name); } catch (Exception e) { throw new IllegalStateException("Field " + name, e); } }
    Object invoke(Object target, String name) { return invoke(target, name, new Class<?>[0]); }
    Object invoke(Object target, String name, Class<?>[] types, Object... args) { try { Class<?> type = target.getClass(); while (type != null) { try { Method m = type.getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(target, args); } catch (NoSuchMethodException e) { type = type.getSuperclass(); } } throw new NoSuchMethodException(name); } catch (Exception e) { throw new IllegalStateException("Method " + name, e); } }
    private void screenshot(String label) throws Exception {
        File file = new File(outDir(), variant() + "-" + label + ".png");
        android.graphics.Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "Screenshot unavailable");
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); } finally { bitmap.recycle(); }
        screenshots.add(file.getAbsolutePath());
    }
}
