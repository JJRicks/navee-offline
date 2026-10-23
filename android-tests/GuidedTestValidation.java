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
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
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
 *   confirms ("Worked here") and Set back, one it acknowledges but ignores ("No change seen", the link stays
 *   up), one it rejects ("Refused by scooter"), and Set back in one tap from the list.
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
        });
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
            TestJournal.Entry entry=new TestJournal.Entry();entry.testId="0123456789abcdef";entry.profileId=other.id;
            entry.feature="display_units";entry.variantId="display_units.ut5";entry.phase="OUTSTANDING";entry.baseline=1;entry.target=0;
            entry.createdWall=entry.updatedWall=System.currentTimeMillis();
            try{((TestJournal)field(activity.service().ble,"testJournal")).commit(entry);}catch(Exception e){throw new IllegalStateException(e);}
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
        checks.add("With Developer options hidden, other-profile and forgotten-profile tests retain Scooter Review access; recovery exposes no checker or new tests, and orphan dismissal sends no command");
    }
    private void openRecoveryFromBanner(){
        main(()->{View review=root().findViewWithTag("scooter-review-test");require(review!=null&&review.isShown(),"Recovery entry is hidden");review.performClick();});settle();
    }
    private void requireRecoveryOnly(){
        main(()->{String shown=texts(root());require(!shown.contains("Run check")&&!shown.contains("Run again")&&!shown.toLowerCase(Locale.ROOT).contains("try a setting")
                &&root().findViewWithTag("developer-compatibility-check")==null,"Recovery exposed developer checker tools");});
    }

    // ---- guided -------------------------------------------------------------------------------

    private void guided() throws Exception {
        for (String[] m : MODELS) {
            String label = m[0];
            connect(label);
            openCompatibility();
            List<String> offered = offered(label);
            refusals(label, offered);
            for (int i = 0; i < offered.size(); i++) happyPath(label, offered.get(i), i);
            if (label.equals("ut5")) { rejected(label); unchanged(label); profileSwitch(label); }
            if (label.equals("mapped")) disconnectAndRestore(label);
            if (label.equals("unknown")) disconnectAlreadyRestored(label);
            report(label);
            require(fake.matchesBaseline(), label + ": the synthetic scooter did not end at its starting values: " + fake.diff());
            main(() -> activity.service().ble.disconnect()); settle();
        }
        checks.add("Every guided test write went through BleController's queue and admission to the in-process synthetic link; Bluetooth stayed off");
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
    // ---- Experimental controls ----------------------------------------------------------------

    private void experimental() throws Exception {
        String label = N65I[0];
        connect(label);
        fake.motion = true; fake.speed = 0;
        await(() -> { boolean[] r = {false}; main(() -> r[0] = activity.service().ble.stationary()); return r[0]; }, label + ": no parked motion report", 10000);
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "more")); settle();
        main(() -> invoke(more(), "selectSection", new Class<?>[]{String.class}, "Lab")); settle();
        List<String> expected = Arrays.asList("start_speed", "turn_sound", "traction_control", "hill_start_assist", "parking_assist", "hill_descent_control", "prompt_tone");
        await(() -> labKeys().equals(expected), label + ": Experimental rows " + labKeys() + " expected " + expected, 10000);
        main(() -> { for (String key : expected) require(activity.service().ble.experimentalDecision(key).available(), label + ": " + key + " not available: " + activity.service().ble.experimentalDecision(key).reasonText()); });
        awaitText("Untested on your model", "Untested here", "Untested"); screenshot(label + "-lab-list");
        int writes = fake.writes.size();
        main(() -> {
            BleController ble = activity.service().ble;
            // Never added here, an offered control never as an experiment, and a hidden one never as an ordinary change.
            for (String key : new String[]{"maximum_speed", "speed_limit", "charge_limit", "lock", "lock_expiry", "low_power", "acceleration", "dual_drive", "overspeed_control"})
                require(!ble.setExperimental(key, key.equals("maximum_speed") ? 25 : key.equals("charge_limit") ? 90 : 1), label + ": " + key + " sent as an experiment");
            require(!ble.setExperimental("display_units", 0), label + ": an offered control sent as an experiment");
            require(!ble.set("turn_sound", 1), label + ": a hidden control sent as an ordinary change");
            // A ride or charge recording holds every experiment.
            ble.visibility(true, true, 0);
            require(ble.experimentalDecision("turn_sound").reason == CapabilityResolver.Reason.RECORDING && !ble.setExperimental("turn_sound", 1), label + ": sent while recording");
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
        checks.add(label + ": never-added controls, an offered control, an ordinary change of a hidden control, moving, locked and recording all refused with zero writes");

        // Worked here: the scooter reads back the new value; Set back returns it, both confirmed.
        openTry("turn_sound"); chooseOption(1); sheetShot(label + "-lab-sheet");
        tapSheet("go");
        await(() -> result("turn_sound").startsWith("Confirmed") && fake.value("turn_sound") == 1, label + ": turn sound not confirmed: " + result("turn_sound"), 10000);
        awaitSheetText("Worked here"); sheetShot(label + "-lab-worked");
        tapSheet("back");
        await(() -> fake.value("turn_sound") == 0 && activity.service().ble.pending() == null, label + ": Set back did not restore turn sound", 10000);
        require(fake.writes.size() == writes + 2, label + ": worked-here writes " + fake.describeWrites());
        closeSheet();
        checks.add(label + ": turn signal sound tried (readback confirmed, \"Worked here\") and set back from the sheet, two writes");

        // No change seen: acknowledged but ignored. Two later reads still show the old value; the link stays up.
        fake.ignoreWrites = true;
        try {
            openTry("traction_control"); chooseOption(0); tapSheet("go");
            await(() -> BleController.NO_CHANGE.equals(result("traction_control")), label + ": ignored write not reported: " + result("traction_control"), 15000);
        } finally { fake.ignoreWrites = false; }
        main(() -> require(activity.service().ble.ready() && activity.service().ble.state() == BleController.State.READY, label + ": an ignored experiment dropped the connection"));
        awaitSheetText("No change seen"); sheetShot(label + "-lab-no-change");
        closeSheet();
        require(fake.state[11] == 1 && fake.writes.size() == writes + 3, label + ": no-change writes " + fake.describeWrites());

        // Refused: the scooter rejects the write with a status code.
        fake.rejectNext = 3;
        openTry("start_speed"); chooseOption(0); tapSheet("go");
        await(() -> result("start_speed").startsWith("Rejected"), label + ": rejection not reported: " + result("start_speed"), 10000);
        awaitSheetText("Refused by scooter"); sheetShot(label + "-lab-refused");
        closeSheet();
        require(fake.state[19] == 3 && fake.writes.size() == writes + 4, label + ": refused writes " + fake.describeWrites());

        // Set back in one tap from the list.
        openTry("prompt_tone"); chooseOption(4); tapSheet("go");
        await(() -> result("prompt_tone").startsWith("Confirmed") && fake.state[21] == 4 && activity.service().ble.pending() == null, label + ": prompt sound not confirmed", 10000);
        closeSheet();
        awaitText("Set back"); revealText("Prompt sound"); screenshot(label + "-lab-results");
        main(() -> { View action = root().findViewWithTag("lab-row-prompt_tone"); require(action != null, label + ": no prompt sound row");
            for (Object row : (List<?>)field(more(), "labRows")) if (((ScooterCommands.Feature)field(row, "feature")).key.equals("prompt_tone")) {
                TextView pill = (TextView)field(row, "action"); require("Set back".contentEquals(pill.getText()) && pill.isEnabled(), label + ": row pill " + pill.getText()); pill.performClick(); } });
        await(() -> fake.state[21] == 2 && activity.service().ble.pending() == null, label + ": one-tap Set back did not restore prompt sound", 10000);
        require(fake.writes.size() == writes + 6, label + ": set-back writes " + fake.describeWrites());
        unconfirmed(label);
        require(fake.matchesBaseline(), label + ": the synthetic scooter did not end at its starting values: " + fake.diff());
        main(() -> activity.service().ble.disconnect()); settle();
        checks.add(label + ": an ignored write reads \"No change seen\" without dropping the link, a rejected one \"Refused by scooter\", and Set back works in one tap from the list; the scooter ends at its starting values");
    }
    /**
     * A try the scooter applied and acknowledged, but no readback confirmed: neither "Not sent" nor "No change seen"
     * is true, so it reads Unconfirmed, whether the link drops first or the confirmation times out. After reconnecting,
     * the readback shows the new value and Set back returns it.
     */
    private void unconfirmed(String label) throws Exception {
        for (String mode : new String[]{"drop", "timeout", "stale", "reordered"}) {
            boolean drop = mode.equals("drop"), stale = mode.equals("stale"), reordered = mode.equals("reordered");
            int sent = fake.writes.size();
            int[] old = {0};
            if (stale || reordered) {
                // Two status reads requested before the try, captured with the old value, still unanswered.
                main(() -> fake.holdStatus = true);
                await(() -> { int[] n = {0}; main(() -> n[0] = fake.heldStatus.size()); return n[0] >= 2; }, label + ": no pre-try polls to hold", 12000);
            }
            openTry("turn_sound"); chooseOption(1);
            main(() -> { fake.holdStatus = true; old[0] = fake.heldStatus.size(); });
            tapSheet("go");
            await(() -> fake.value("turn_sound") == 1 && fake.writes.size() == sent + 1, label + ": the try never reached the scooter", 10000);
            String expected = drop ? "Cancelled after disconnection" : "Change not confirmed";
            if (drop) { SystemClock.sleep(600); main(() -> activity.service().ble.disconnect()); }
            // Stale: only the old snapshots (Off) arrive after the ACK; every fresh reply stays held. They must not read as No change.
            if (stale) { SystemClock.sleep(600); main(() -> { for (int i = 0; i < old[0]; i++) fake.releaseStatus(); }); }
            // Reordered: newer polls are answered first with valid short statuses (no turn-sound byte), then the old full
            // snapshots arrive. Counting replies would take the old ones as fresh; they must still prove nothing.
            if (reordered) {
                SystemClock.sleep(600);
                for (int i = 0; i < 2; i++) {
                    main(() -> invoke(activity.service().ble, "send", new Class<?>[]{byte[].class}, Protocol.frame(0x70, null)));
                    final int count = old[0] + i + 1;
                    await(() -> { int[] n = {0}; main(() -> n[0] = fake.heldStatus.size()); return n[0] >= count; }, label + ": the post-ACK poll was not sent", 4000);
                }
                main(() -> {
                    for (int i = 0; i < 2; i++) { byte[] fresh = fake.heldStatus.remove(old[0]); fake.notify(0x70, 0, java.util.Arrays.copyOf(fresh, 10)); }
                    for (int i = 0; i < old[0]; i++) fake.releaseStatus();
                });
            }
            await(() -> expected.equals(result("turn_sound")), label + ": result " + result("turn_sound") + " expected " + expected, 30000);
            awaitSheetText("Unconfirmed"); sheetShot(label + "-lab-unconfirmed-" + mode);
            main(() -> { View sheet = (View)field(field(more(), "labSheet"), "status");
                String shown = String.valueOf(((TextView)sheet).getText());
                require(!shown.contains("Not sent") && !shown.contains("No change"), label + ": an applied try reads " + shown); });
            closeSheet();
            main(() -> { fake.holdStatus = false; fake.heldStatus.clear(); });
            boolean[] ready = {false}; main(() -> ready[0] = activity.service().ble.ready());
            if (!ready[0]) reconnect(label, fake.writes.size());
            // The next readback shows On, so Set back is offered and returns it, confirmed.
            await(() -> { Integer[] v = {null}; main(() -> v[0] = activity.service().ble.featureValue("turn_sound")); return Integer.valueOf(1).equals(v[0]); }, label + ": the applied value never read back", 15000);
            openTry("turn_sound"); tapSheet("back");
            await(() -> fake.value("turn_sound") == 0 && activity.service().ble.pending() == null && result("turn_sound").startsWith("Confirmed"), label + ": Set back after an unconfirmed try failed: " + result("turn_sound"), 15000);
            closeSheet();
        }
        checks.add(label + ": a try the scooter applied but never confirmed reads Unconfirmed, not Not sent or No change seen, after a dropped link, a timeout, and old pre-try snapshots arriving after the ACK; the readback then offers Set back");
    }
    private List<String> labKeys() { List<String> out = new ArrayList<>(); main(() -> { for (Object row : (List<?>)field(more(), "labRows")) if (field(row, "action") != null) out.add(((ScooterCommands.Feature)field(row, "feature")).key); }); return out; }
    private CapabilityResolver.Reason labReason(String key) { CapabilityResolver.Reason[] r = {null}; main(() -> r[0] = activity.service().ble.experimentalDecision(key).reason); return r[0]; }
    private String result(String key) { String[] r = {""}; main(() -> { String v = activity.service().ble.commandResults().get(key); r[0] = v == null ? "" : v; }); return r[0]; }
    private Object sheet() { Object sheet = field(more(), "labSheet"); require(sheet != null, "No Try sheet"); return sheet; }
    private View sheetRoot() { return ((android.app.AlertDialog)field(sheet(), "dialog")).getWindow().getDecorView(); }
    private void openTry(String key) {
        main(() -> { View row = root().findViewWithTag("lab-row-" + key); require(row != null, "No row " + key); row.performClick(); }); settle();
        main(() -> require(sheet() != null && texts(sheetRoot()).contains("Untested on the N65i"), "Try sheet for " + key + ": " + texts(sheetRoot()).replace('\n', '|')));
    }
    private void chooseOption(int value) { main(() -> { View option = sheetRoot().findViewWithTag("lab-option-" + value); require(option != null, "No option " + value); option.performClick(); }); settle(); }
    private void tapSheet(String button) { main(() -> { View b = (View)field(sheet(), button); require(b.isShown() && b.isEnabled(), "Sheet " + button + " unavailable"); b.performClick(); }); settle(); }
    private void awaitSheetText(String text) {
        long end = SystemClock.uptimeMillis() + 10000; final boolean[] found = {false};
        do { main(() -> found[0] = texts(sheetRoot()).contains(text)); if (found[0]) break; SystemClock.sleep(120); } while (SystemClock.uptimeMillis() < end);
        require(found[0], "Sheet never showed " + text); settle();
    }
    private void sheetShot(String name) throws Exception { settle(); screenshot(name); }
    private void closeSheet() { main(() -> ((android.app.AlertDialog)field(sheet(), "dialog")).dismiss()); settle(); }

    private void openCompatibility() {
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "more")); settle();
        main(() -> invoke(more(), "selectSection", new Class<?>[]{String.class}, "Compatibility")); settle();
        awaitText("Try a setting", "TRY A SETTING", "Unfinished test · ");
    }

    /** The eligibility table (C4.3) for this model, as the Try a setting group shows it. */
    private List<String> offered(String label) throws Exception {
        List<String> out = new ArrayList<>();
        main(() -> {
            BleController ble = activity.service().ble;
            for (String f : FEATURES) {
                CapabilityResolver.Decision d = ble.testDecision(f);
                if (d.support == CapabilityResolver.Support.KNOWN_ENCODING) out.add(f);
            }
            for (String f : new String[]{"maximum_speed", "cruise_control", "start_speed", "energy_recovery", "traction_control", "dual_drive", "charge_limit",
                    "timed_charging", "charge_start", "lock", "lock_expiry", "proximity_key", "pin", "clock_sync", "low_power"}) {
                CapabilityResolver.Decision d = ble.testDecision(f);
                require(!d.available() && d.reason == CapabilityResolver.Reason.NOT_TESTABLE, label + ": " + f + " offered as a test: " + d);
            }
        });
        if (label.equals("ut5")) require(out.equals(Arrays.asList(FEATURES)), label + ": UT5 MAX tests " + out);
        else if (label.equals("unknown")) {
            require(out.equals(Arrays.asList("display_units")), label + ": unknown-model tests " + out);
            main(() -> require(activity.service().ble.testDecision("display_units").variant.id.equals("display_units.shared"), label + ": not the shared units variant"));
        } else require(out.contains("display_units") && !out.isEmpty(), label + ": mapped tests " + out);
        checks.add(label + ": tests offered " + out + "; speed, charging, clock, lock, PIN and proximity families refused as NOT_TESTABLE");
        return out;
    }

    /** Ineligible controls and unmet preconditions: nothing starts, nothing is written. */
    private void refusals(String label, List<String> offered) throws Exception {
        int writes = fake.writes.size();
        // No motion report on this connection: the owner must tick "Scooter is parked".
        main(() -> {
            BleController ble = activity.service().ble;
            CapabilityResolver.Decision d = ble.testDecision("display_units");
            require(d.reason == CapabilityResolver.Reason.PARKED_UNCONFIRMED, label + ": without a motion report: " + d.reason);
            ble.startTest("display_units", other(d));
            require(!ble.testing() && ble.guidedTest() == null, label + ": a test started without the parked confirmation");
            ble.startTest("maximum_speed", 10);
            require(!ble.testing(), label + ": a speed test started");
        });
        awaitText("Scooter is parked");
        revealText("Try a setting", "TRY A SETTING"); screenshot(label + "-try-parked-unconfirmed");
        revealText("Scooter is parked"); screenshot(label + "-try-parked-toggle");
        main(() -> { View row = clickable(byText(root(), "Scooter is parked")); require(row != null, label + ": no parked toggle"); row.performClick(); });
        settle();
        main(() -> require(activity.service().ble.testParked() && activity.service().ble.testDecision("display_units").available(), label + ": parked tick did not admit the test"));
        // A motion report replaces the tick; one showing speed refuses the test.
        fake.motion = true; fake.speed = 3;
        await(() -> reason("display_units") == CapabilityResolver.Reason.MOVING, label + ": moving scooter not refused: " + reason("display_units"), 10000);
        main(() -> { activity.service().ble.startTest("display_units", 0); require(!activity.service().ble.testing(), label + ": a test started while moving"); });
        awaitText("Stop the scooter first"); revealText("Try a setting", "TRY A SETTING"); screenshot(label + "-try-moving");
        fake.speed = 0;
        await(() -> reason("display_units") == CapabilityResolver.Reason.NONE, label + ": parked scooter not admitted: " + reason("display_units"), 10000);
        if (label.equals("mapped") && offered.contains("headlight")) {
            fake.set(2, 1);   // locked: the OEM tail-light mapping needs the scooter unlocked
            await(() -> reason("headlight") == CapabilityResolver.Reason.LOCKED, label + ": locked scooter not refused: " + reason("headlight"), 10000);
            main(() -> { activity.service().ble.startTest("headlight", other(activity.service().ble.testDecision("headlight"))); require(!activity.service().ble.testing(), label + ": a tail-light test started while locked"); });
            awaitText("Unlock first"); revealText("Try a setting", "TRY A SETTING"); screenshot(label + "-try-locked");
            fake.set(2, 0);
            await(() -> reason("headlight") == CapabilityResolver.Reason.NONE, label + ": unlocked scooter not admitted", 10000);
        }
        if (label.equals("unknown")) main(() -> {
            String shown = texts(root());
            for (String hidden : new String[]{"Try On", "Try Off", "Tail light", "Turn signal sound", "Automatic lights", "Range estimate"})
                require(!shown.contains(hidden), label + ": unknown model shows " + hidden);
        });
        revealText("Try a setting", "TRY A SETTING"); screenshot(label + "-try");
        require(fake.writes.size() == writes, label + ": refusals wrote " + fake.describeWrites());
        checks.add(label + ": refused without parked confirmation, while moving" + (label.equals("mapped") ? ", while locked" : "") + " and for ineligible features, with zero writes");
    }

    /** Try → approval sheet → Change → Setting confirmed → owner prompt → restore → outcome list. */
    private void happyPath(String label, String feature, int index) throws Exception {
        String name = ScooterCommands.find(feature).label;
        final int[] values = new int[2]; final String[] words = new String[2];
        main(() -> {
            CapabilityResolver.Decision d = activity.service().ble.testDecision(feature);
            require(d.available(), label + ": " + feature + " not available: " + d.reasonText());
            values[0] = d.current(); values[1] = other(d); words[0] = d.label(values[0]); words[1] = d.label(values[1]);
        });
        int writes = fake.writes.size();
        tapDescription("Test " + name + ": try " + words[1], label + " " + feature);
        awaitPhase(GuidedTest.Phase.BASELINE, label + " " + feature + " baseline");
        awaitText("Change to " + words[1]);
        main(() -> {
            String shown = texts(root());
            require(shown.contains("Test · " + name) && shown.contains("Now · " + words[0]) && shown.contains("Test value · " + words[1])
                    && shown.contains("Afterwards Volt sets it back to " + words[0] + " and checks it. Results go in the report only."), label + ": approval sheet for " + feature + ": " + shown.replace('\n', '|'));
            require(activity.service().ble.decision(feature.equals("display_units") ? "maximum_speed" : "display_units").reason == CapabilityResolver.Reason.TEST_ACTIVE
                    || !activity.service().ble.decision(feature.equals("display_units") ? "maximum_speed" : "display_units").available(), label + ": an ordinary control stayed available during the test");
        });
        require(fake.writes.size() == writes, label + ": " + feature + " wrote before approval");
        revealText("Test · " + name); screenshot(label + "-" + feature + "-1-approval");
        tapText("Change to " + words[1], label + " approval");
        awaitPhase(GuidedTest.Phase.OBSERVE, label + " " + feature + " target confirmed");
        require(fake.writes.size() == writes + 1 && fake.lastWriteValue() == values[1] && fake.value(feature) == values[1], label + ": " + feature + " target write " + fake.describeWrites());
        main(() -> {
            TestJournal.Entry e = activity.service().ble.guidedTest().entry();
            require(e.phase.equals("OBSERVE") && e.baseline == values[0] && e.target == values[1] && !e.resolved, label + ": journal before observe " + TestJournal.line(e));
        });
        if (label.equals("ut5") && feature.equals("display_units")) {
            // A running test holds the automatic range-follow write (the synthetic riding mode asks for Sport).
            main(() -> activity.service().followRidingMode(true));
            SystemClock.sleep(5000);
            require(fake.writes.size() == writes + 1, label + ": range follow wrote during a test " + fake.describeWrites());
            main(() -> activity.service().followRidingMode(false));
            checks.add(label + ": range follow enabled during a running test sends nothing");
        }
        String question = CompatibilityPanel.question(feature, words[1]);
        awaitText(question); revealText("Test · " + name); screenshot(label + "-" + feature + "-2-observe");
        String answer = index % 3 == 0 ? "Yes" : index % 3 == 1 ? "No" : "Skip";
        tapText(answer, label + " owner answer");
        awaitPhase(GuidedTest.Phase.DONE, label + " " + feature + " restored");
        require(fake.writes.size() == writes + 2 && fake.lastWriteValue() == values[0] && fake.value(feature) == values[0], label + ": " + feature + " restore write " + fake.describeWrites());
        final List<String> expected = new ArrayList<>(Arrays.asList("Setting confirmed")); if (answer.equals("Yes")) expected.add("Effect observed"); expected.add("Restoration confirmed");
        main(() -> {
            GuidedTest t = activity.service().ble.guidedTest();
            require(t.outcomeTexts().equals(expected) && t.restorationStatus().equals("confirmed"), label + ": " + feature + " outcomes " + t.outcomeTexts() + " " + t.restorationStatus());
            TestJournal.Entry e = t.entry();
            require(e.resolved && e.phase.equals("DONE") && e.effect.equals(answer.equals("Yes") ? "observed" : answer.equals("No") ? "not_observed" : "not_checked"), label + ": journal after restore " + TestJournal.line(e));
            require(activity.service().ble.unresolvedTests().isEmpty(), label + ": a finished test is listed as unfinished");
        });
        awaitText("Restoration confirmed");
        main(() -> { String shown = texts(root()); require(shown.contains(join(expected)) && !shown.contains("Use on this scooter"), label + ": outcome list " + shown.replace('\n', '|')); });
        revealText("Test · " + name); screenshot(label + "-" + feature + "-3-outcome");
        main(() -> { TextView close = byText(root(), "Close"); require(close != null, label + ": no Close"); close.performClick(); });
        await(() -> { boolean[] r = {false}; main(() -> r[0] = activity.service().ble.guidedTest() == null); return r[0]; }, label + ": Close kept the test card");
        checks.add(label + ": " + feature + " " + words[0] + " → " + words[1] + " → " + words[0] + ": approval sheet, one target and one restore write, outcomes " + expected + ", answer " + answer);
    }

    /** Nonzero ACK: Rejected, code 3; restoration needs no write and is confirmed by a fresh readback. */
    private void rejected(String label) throws Exception {
        int writes = fake.writes.size(); fake.rejectNext = 3;
        runToEnd(label, "display_units");
        require(fake.writes.size() == writes + 1, label + ": rejection writes " + fake.describeWrites());
        main(() -> { GuidedTest t = activity.service().ble.guidedTest(); require(t.outcomeTexts().equals(Arrays.asList("Rejected, code 3", "Restoration confirmed")) && t.restorationStatus().equals("confirmed"), label + ": rejected outcomes " + t.outcomeTexts()); });
        awaitText("Restoration confirmed"); revealText("Test · Dashboard units"); screenshot(label + "-rejected-outcome");
        closeCard(label);
        checks.add(label + ": a rejected write (code 3) reports \"Rejected, code 3 · Restoration confirmed\" with no restore write");
    }
    /** ACK without a change: Readback unchanged with the value read; restoration confirmed by that readback. */
    private void unchanged(String label) throws Exception {
        int writes = fake.writes.size(); fake.ignoreWrites = true;
        try { runToEnd(label, "display_units"); } finally { fake.ignoreWrites = false; }
        require(fake.writes.size() == writes + 1, label + ": unchanged writes " + fake.describeWrites());
        main(() -> { GuidedTest t = activity.service().ble.guidedTest(); require(t.outcomeTexts().equals(Arrays.asList("Readback unchanged, value 1", "Restoration confirmed")), label + ": unchanged outcomes " + t.outcomeTexts()); });
        awaitText("Restoration confirmed"); revealText("Test · Dashboard units"); screenshot(label + "-unchanged-outcome");
        closeCard(label);
        checks.add(label + ": an acknowledged write the scooter ignored reports \"Readback unchanged, value 1 · Restoration confirmed\" and is never called a confirmed setting");
    }
    private void runToEnd(String label, String feature) throws Exception {
        final int[] target = {0}; final String[] word = {""};
        main(() -> { CapabilityResolver.Decision d = activity.service().ble.testDecision(feature); target[0] = other(d); word[0] = d.label(target[0]); });
        tapDescription("Test " + ScooterCommands.find(feature).label + ": try " + word[0], label + " " + feature);
        awaitPhase(GuidedTest.Phase.BASELINE, label + " baseline"); awaitText("Change to " + word[0]);
        tapText("Change to " + word[0], label + " approval");
        awaitPhase(GuidedTest.Phase.DONE, label + " finished");
    }
    private void closeCard(String label) {
        main(() -> { TextView close = byText(root(), "Close"); require(close != null, label + ": no Close"); close.performClick(); }); settle();
    }

    /** Target confirmed, then another profile is selected: OUTSTANDING with that profile; the other profile shows a note only. */
    private void profileSwitch(String label) throws Exception {
        toObserve(label, "headlight");
        int writes = fake.writes.size();
        ScooterProfiles.Profile other = profileFor("mapped"), mine = profileFor(label);
        main(() -> { activity.service().profiles.select(other.id); activity.service().ble.profileSelected(); });
        settle();
        main(() -> {
            BleController ble = activity.service().ble;
            require(!ble.testing(), label + ": the test kept running after a profile switch");
            TestJournal journal = (TestJournal)field(ble, "testJournal");
            List<TestJournal.Entry> open = journal.unresolved(mine.id);
            require(open.size() == 1 && open.get(0).phase.equals("OUTSTANDING") && open.get(0).restoration.equals("outstanding") && open.get(0).outcomes.contains("interrupted"), label + ": journal after switch " + open);
            // The connection still belongs to the old scooter until it disconnects (the UI only switches when disconnected).
            ble.disconnect();
            require(ble.unresolvedTestsElsewhere() == 1 && ble.unresolvedTests().isEmpty(), label + ": the unfinished test is not kept with its own profile");
            activity.redraw();
        });
        awaitText("1 unfinished test on another scooter. Select it to review.");
        revealText("1 unfinished test on another scooter. Select it to review."); screenshot(label + "-switch-other-profile-note");
        require(fake.writes.size() == writes, label + ": profile switch wrote");
        main(() -> { activity.service().profiles.select(mine.id); activity.service().ble.profileSelected(); activity.redraw(); });
        awaitText("Unfinished test · Tail light");
        reconnect(label, writes);
        awaitText("Still on the test value, Off.");
        main(() -> {
            String shown = texts(root());
            require(shown.contains("Setting confirmed · Interrupted/no response") && shown.contains("Dismiss · leave as is"), label + ": unfinished card " + shown.replace('\n', '|'));
            require(!shown.contains("Try a setting") && !shown.contains("TRY A SETTING"), label + ": new tests offered while one is unfinished");
        });
        revealText("Unfinished test · Tail light"); screenshot(label + "-switch-unfinished");
        // The scooter stays on the test value; the owner dismisses: declined, never "restored".
        main(() -> { activity.service().ble.startTest("display_units", 0); require(!activity.service().ble.testing(), label + ": a new test started while one is unfinished"); });
        main(() -> { View dismiss = byDescription(root(), "Dismiss the unfinished Tail light test without restoring it"); require(dismiss != null, label + ": no Dismiss"); dismiss.performClick(); });
        settle();
        main(() -> {
            TestJournal journal = (TestJournal)field(activity.service().ble, "testJournal");
            require(journal.unresolved(mine.id).isEmpty(), label + ": dismiss left the test unresolved");
            TestJournal.Entry last = null; for (TestJournal.Entry e : journal.all(mine.id)) if (e.feature.equals("headlight")) last = e;
            require(last != null && last.restoration.equals("declined") && last.resolution.equals("dismissed"), label + ": dismissed entry " + (last == null ? null : TestJournal.line(last)));
        });
        require(fake.writes.size() == writes, label + ": dismiss wrote");
        // Put the synthetic tail light back by hand, as the owner would on the scooter.
        fake.set(4, fake.baseline[4]);
        awaitText("Try a setting", "TRY A SETTING");
        checks.add(label + ": a profile switch after the target leaves the test OUTSTANDING with its own profile, shows a note on the other one, writes nothing; Dismiss records declined");
    }

    /** Target confirmed, then the link drops: OUTSTANDING; reconnecting writes nothing; Restore is one explicit tap. */
    private void disconnectAndRestore(String label) throws Exception {
        toObserve(label, "display_units");
        int writes = fake.writes.size();
        main(() -> activity.service().ble.disconnect()); settle();
        main(() -> { BleController ble = activity.service().ble; require(!ble.testing() && ble.unresolvedTests().size() == 1, label + ": disconnect did not leave one unfinished test"); });
        awaitText("Connect to this scooter to review.");
        revealText("Unfinished test · Dashboard units"); screenshot(label + "-disconnected-unfinished");
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "controls")); settle();
        awaitText("Unfinished test · Dashboard units"); screenshot(label + "-scooter-banner");
        reconnect(label, writes);
        openCompatibility();
        awaitText("Restore to Kilometers / km/h");
        main(() -> require(texts(root()).contains("Still on the test value, Miles / mph."), label + ": review text"));
        revealText("Unfinished test · Dashboard units"); screenshot(label + "-review-restore");
        require(fake.writes.size() == writes, label + ": reconnect wrote " + fake.describeWrites());
        tapText("Restore to Kilometers / km/h", "restore");
        awaitPhase(GuidedTest.Phase.DONE, label + " explicit restore");
        require(fake.writes.size() == writes + 1 && fake.lastWriteValue() == 1 && fake.value("display_units") == 1, label + ": explicit restore writes " + fake.describeWrites());
        main(() -> {
            GuidedTest t = activity.service().ble.guidedTest();
            require(t.outcomeTexts().equals(Arrays.asList("Setting confirmed", "Interrupted/no response", "Restoration confirmed")) && t.restorationStatus().equals("confirmed"), label + ": restored outcomes " + t.outcomeTexts());
            require(activity.service().ble.unresolvedTests().isEmpty(), label + ": still unfinished after restore");
        });
        awaitText("Restoration confirmed"); revealText("Test · Dashboard units"); screenshot(label + "-review-restored");
        closeCard(label);
        checks.add(label + ": disconnect after the target → OUTSTANDING, Scooter-page banner, zero writes on reconnect, one write on the explicit Restore tap → Restoration confirmed");
    }
    /** Interrupted, then the owner set it back on the dashboard: Already back, Mark restored, no write. */
    private void disconnectAlreadyRestored(String label) throws Exception {
        toObserve(label, "display_units");
        int writes = fake.writes.size();
        main(() -> activity.service().ble.disconnect()); settle();
        fake.set(7, fake.baseline[7]);
        reconnect(label, writes);
        openCompatibility();
        awaitText("Mark restored");
        main(() -> require(texts(root()).contains("Already back to Kilometers / km/h."), label + ": review text"));
        revealText("Unfinished test · Dashboard units"); screenshot(label + "-review-already-restored");
        tapText("Mark restored", label); settle();
        main(() -> {
            TestJournal journal = (TestJournal)field(activity.service().ble, "testJournal");
            List<TestJournal.Entry> all = journal.all(activity.service().ble.profile().id); TestJournal.Entry e = all.get(all.size() - 1);
            require(e.resolved && e.restoration.equals("confirmed") && e.resolution.equals("already_restored") && e.outcomes.get(e.outcomes.size() - 1).equals("restoration_confirmed"), label + ": already-restored entry " + TestJournal.line(e));
        });
        require(fake.writes.size() == writes, label + ": Mark restored wrote");
        awaitText("Try a setting", "TRY A SETTING");
        checks.add(label + ": an interrupted test the owner already set back reads \"Already back to …\"; Mark restored records Restoration confirmed with no write");
    }
    private void toObserve(String label, String feature) throws Exception {
        final String[] word = {""};
        main(() -> { CapabilityResolver.Decision d = activity.service().ble.testDecision(feature); word[0] = d.label(other(d)); });
        tapDescription("Test " + ScooterCommands.find(feature).label + ": try " + word[0], label + " " + feature);
        awaitPhase(GuidedTest.Phase.BASELINE, label + " baseline"); awaitText("Change to " + word[0]);
        tapText("Change to " + word[0], label + " approval");
        awaitPhase(GuidedTest.Phase.OBSERVE, label + " observe");
    }
    private void reconnect(String label, int writes) throws Exception {
        ScooterProfiles.Profile p = profileFor(label);
        main(() -> fake.attach(activity.service().ble, p));
        await(() -> { boolean[] r = {false}; main(() -> r[0] = activity.service().ble.ready()); return r[0]; }, label + ": reconnect not ready", 12000);
        // Several status polls arrive; nothing is written back automatically.
        SystemClock.sleep(6000);
        require(fake.writes.size() == writes, label + ": automatic write after reconnect " + fake.describeWrites());
        main(() -> require(!activity.service().ble.testing(), label + ": a test resumed by itself"));
    }

    /** The compatibility report after a read-only check: the tests section, no identifiers, Share carries the preview. */
    private void report(String label) throws Exception {
        int writes = fake.writes.size();
        main(() -> activity.service().ble.startDiscovery());
        await(() -> { boolean[] r = {true}; main(() -> r[0] = activity.service().ble.discovering()); return !r[0]; }, label + ": check did not finish", 60000);
        require(fake.writes.size() == writes, label + ": the check wrote");
        awaitText("Preview report");
        tapText("Preview report", label); settle();
        awaitText("Guided tests", "Show JSON");
        final String[] json = {null}, summary = {null}; final int[] entries = {0}; final List<String> ids = new ArrayList<>();
        main(() -> {
            Object report = field(field(more(), "compatibilityPanel"), "report");
            json[0] = (String)invoke(report, "json"); summary[0] = (String)invoke(report, "summary");
            TestJournal journal = (TestJournal)field(activity.service().ble, "testJournal");
            List<TestJournal.Entry> all = journal.all(activity.service().ble.profile().id); entries[0] = all.size();
            for (TestJournal.Entry e : all) ids.add(e.testId);
            ids.add(activity.service().ble.profile().id);
        });
        JSONObject root = new JSONObject(json[0]);
        JSONArray tests = root.getJSONArray("tests");
        require(entries[0] > 0 && tests.length() == entries[0], label + ": report has " + tests.length() + " tests, journal " + entries[0]);
        for (int i = 0; i < tests.length(); i++) {
            JSONObject t = tests.getJSONObject(i);
            List<String> keys = new ArrayList<>(); for (Iterator<String> k = t.keys(); k.hasNext();) keys.add(k.next());
            require(keys.equals(Arrays.asList("feature", "variant", "baseline", "target", "packet_evidence", "owner_observation", "restoration")), label + ": test keys " + keys);
            JSONArray outcomes = t.getJSONObject("packet_evidence").getJSONArray("outcomes");
            for (int j = 0; j < outcomes.length(); j++) require(Arrays.asList("read_supported", "rejected", "acknowledged_unconfirmed", "readback_unchanged", "readback_different",
                    "setting_confirmed", "restoration_confirmed", "interrupted").contains(outcomes.getString(j)), label + ": outcome token " + outcomes.getString(j));
        }
        require(summary[0].contains("Guided tests"), label + ": summary has no Guided tests section");
        requireClean(json[0] + "\n" + summary[0], label + " report", ids);
        Files.write(new File(outDir(), variant() + "-" + label + "-report.json").toPath(), json[0].getBytes(StandardCharsets.UTF_8));
        Files.write(new File(outDir(), variant() + "-" + label + "-summary.txt").toPath(), summary[0].getBytes(StandardCharsets.UTF_8));
        revealLine("Guided tests"); screenshot(label + "-report-guided-tests");
        // Share hands exactly the previewed text to the system chooser (intercepted here).
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
        main(() -> { TextView close = byText(root(), "Close preview"); if (close != null) close.performClick(); }); settle();
        checks.add(label + ": report preview and Share include " + entries[0] + " guided test entries (semantic values only) and no serial, MAC, owner key, name, profile or test ID");
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

    // ---- interrupt (process death between target and restore) ----------------------------------

    /** A fresh motion report invalidates a guided value still waiting behind a GATT write. */
    private void queuedAdmissionSafety() throws Exception {
        connect("mapped");
        main(() -> {
            ((Handler)field(activity.service().ble,"handler")).removeCallbacksAndMessages(null);
            activity.service().ble.setTestParked(true);
            activity.service().ble.startTest("display_units",0);
        });
        awaitPhase(GuidedTest.Phase.BASELINE,"fresh baseline before delayed queue");
        SystemClock.sleep(200);
        main(() -> {
            fake.callbackDelayMs=1500;
            invoke(activity.service().ble,"send",new Class<?>[]{byte[].class},Protocol.frame(0x70,null));
        });
        await(() -> {boolean[] writing={false}; main(() -> writing[0]=(Boolean)field(activity.service().ble,"writing")); return writing[0];},"status write did not occupy queue");
        main(() -> {
            BleController ble=activity.service().ble;
            ble.approveTest();
            require(ble.pending()!=null && fake.writes.isEmpty(),"test target should still be queued");
            fake.notify(0x91,0,new byte[]{0,1,10});
        });
        await(() -> {boolean[] moving={false};main(() -> moving[0]=!activity.service().ble.stationary() && activity.service().ble.speedRaw()>0);return moving[0];},"moving push was not accepted");
        main(() -> {
            CapabilityResolver.Runtime rt=new CapabilityResolver.Runtime();
            rt.ready=true;rt.motionSeen=true;rt.stationary=false;rt.test=true;rt.testFeature="display_units";rt.owner=true;rt.parkedConfirmed=true;
            rt.nowMs=SystemClock.elapsedRealtime();
            BleController ble=activity.service().ble;
            StatusFields status=StatusFields.of((Protocol.Frame)field(ble,"statusFrame"),(Long)field(ble,"statusMs"));
            CapabilityResolver.Decision shouldRefuse=MutationGate.check(new MutationGate.Request(MutationGate.Kind.SET,"display_units",0,MutationGate.Origin.GUIDED_TEST),ble.descriptor(),status,rt);
            require(shouldRefuse.reason==CapabilityResolver.Reason.MOVING,"fresh admission should refuse motion");
        });
        await(() -> {boolean[] cleared={false};main(() -> cleared[0]=activity.service().ble.pending()==null);return cleared[0];},"Unsafe queued mutation was not cancelled",6000);
        SystemClock.sleep(1800);
        require(fake.writes.isEmpty(),"Queued target reached the scooter after fresh motion");
        checks.add("A moving report while a guided target waits behind a GATT write cancels the target before transmission");
    }

    private void queueLifecycle() throws Exception {
        queuedAdmissionSafety();
        connect("ut5");
        main(() -> { ((Handler)field(activity.service().ble,"handler")).removeCallbacksAndMessages(null);activity.service().ble.setTestParked(true); fake.holdStatus=true;
            invoke(activity.service().ble,"send",new Class<?>[]{byte[].class},Protocol.frame(0x70,null)); });
        await(() -> fake.heldStatus.size()>=1,"Ordinary poll A did not transmit");
        main(() -> activity.service().ble.startTest("display_units",0));
        SystemClock.sleep(100);
        require(fake.heldStatus.size()==1,"Baseline probe transmitted before inherited poll drained");
        main(() -> fake.releaseStatus());
        await(() -> fake.heldStatus.size()>=1,"Baseline poll B did not transmit after A drained");
        require(phaseNow().equals("PRECHECK"),"Inherited A falsely satisfied baseline B");
        main(() -> fake.releaseStatus());awaitPhase(GuidedTest.Phase.BASELINE,"actual baseline B");
        main(() -> invoke(activity.service().ble,"send",new Class<?>[]{byte[].class},Protocol.frame(0x70,null)));
        await(() -> fake.heldStatus.size()>=1,"Pre-write ordinary poll did not transmit");
        main(() -> activity.service().ble.approveTest());
        await(() -> { boolean[] ack={false};main(() -> { Object mutation=field(activity.service().ble,"mutation");ack[0]=mutation!=null&&(Boolean)field(mutation,"ack"); });return ack[0]; },"Target ACK did not arrive");
        // A competing ordinary read can already be queued; let its newer response arrive first.
        main(() -> invoke(activity.service().ble,"send",new Class<?>[]{byte[].class},Protocol.frame(0x70,null)));
        await(() -> fake.heldStatus.size()>=2,"Post-ACK competing poll did not transmit");
        main(() -> { while(!fake.heldStatus.isEmpty())fake.releaseStatus(fake.heldStatus.size()-1); });
        await(() -> fake.heldStatus.size()>=1,"Fresh target probe did not follow the drained mixed batch");
        require(phaseNow().equals("TARGET_AWAIT")&&fake.value("display_units")==0,"Target must await its own readback");
        main(() -> fake.releaseStatus());awaitPhase(GuidedTest.Phase.OBSERVE,"target C");
        main(() -> { fake.holdStatus=false;while(!fake.heldStatus.isEmpty())fake.releaseStatus(); });
        SystemClock.sleep(300);
        // Model an interrupted test with a poll already in flight when the owner explicitly restores.
        main(() -> {
            BleController ble=activity.service().ble;ble.guidedTest().onDisconnect(SystemClock.elapsedRealtime());ble.closeTest();
            fake.holdStatus=true;fake.set(7,1);invoke(ble,"send",new Class<?>[]{byte[].class},Protocol.frame(0x70,null));
        });
        await(() -> fake.heldStatus.size()>=1,"Pre-restore poll did not transmit");
        require(fake.heldStatus.get(0)[7]==1,"Inherited restore poll did not capture baseline");
        final String[] id={null};
        main(() -> { fake.set(7,0);fake.ignoreWrites=true;BleController ble=activity.service().ble;
            id[0]=ble.unresolvedTests().get(0).testId;ble.restoreTest(id[0]); });
        SystemClock.sleep(100);
        require(fake.heldStatus.size()>=1,"Inherited restore poll was lost");
        // The ordinary ACK follow-up can also queue while draining. Deliver every newer
        // response first and the inherited baseline last; none may serve as the fresh probe.
        main(() -> { while(!fake.heldStatus.isEmpty())fake.releaseStatus(fake.heldStatus.size()-1); });
        await(() -> fake.heldStatus.size()>=1,"Restore verification did not transmit after old poll drained");
        require(phaseNow().equals("RESTORE_AWAIT"),"Inherited baseline reply falsely confirmed restoration");
        main(() -> fake.releaseStatus());awaitPhase(GuidedTest.Phase.OUTSTANDING,"failed actual restore");
        main(() -> { require(!activity.service().ble.unresolvedTests().isEmpty(),"Failed restore lost its journal");
            fake.ignoreWrites=false;fake.holdStatus=false;while(!fake.heldStatus.isEmpty())fake.releaseStatus();activity.service().ble.restoreTest(id[0]); });
        awaitPhase(GuidedTest.Phase.DONE,"actual explicit restore");
        main(() -> activity.service().ble.closeTest());
        checks.add("Session poll A cannot impersonate baseline B; reordered pre/post-write replies cannot falsely confirm restoration; inherited replies cannot clear a failed explicit restore");

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

    private void interruptStart() throws Exception {
        connect("ut5");
        openCompatibility();
        main(() -> { View row = clickable(byText(root(), "Scooter is parked")); require(row != null, "no parked toggle"); row.performClick(); });
        settle();
        toObserve("ut5", "display_units");
        require(fake.writes.size() == 1 && fake.value("display_units") == 0, "target write " + fake.describeWrites());
        awaitText("Does the dashboard show mph?"); revealText("Test · Dashboard units"); screenshot("interrupt-1-before-kill");
        final String[] line = {""};
        main(() -> { TestJournal.Entry e = activity.service().ble.guidedTest().entry(); line[0] = e.testId + " " + e.phase + " " + e.baseline + " " + e.target; });
        JSONObject m = new JSONObject();
        m.put("journal", line[0]); m.put("writes", fake.writes.size()); m.put("units", fake.value("display_units"));
        m.put("screenshots", new JSONArray(screenshots)); m.put("assertions", assertions);
        Files.write(marker().toPath(), m.toString(2).getBytes(StandardCharsets.UTF_8));
        // Abrupt death: no onDestroy, no disconnect, no journal update. The runner relaunches.
        android.os.Process.killProcess(android.os.Process.myPid());
    }
    private void interruptResume() throws Exception {
        JSONObject m = new JSONObject(new String(Files.readAllBytes(marker().toPath()), StandardCharsets.UTF_8));
        String[] before = m.getString("journal").split(" ");
        require(before.length == 4 && before[1].equals("OBSERVE") && before[2].equals("1") && before[3].equals("0"), "interrupt marker " + m);
        ScooterProfiles.Profile p = profileFor("ut5");
        main(() -> {
            // A pre-upgrade unfinished test must remain recoverable with Developer options hidden.
            require(settings().edit().putBoolean("developer_options", false).commit(), "Could not hide Developer options for recovery");
            BleController ble = activity.service().ble;
            require(!ble.testing() && ble.guidedTest() == null, "a test resumed after process death");
            List<TestJournal.Entry> open = ble.unresolvedTests();
            require(open.size() == 1 && open.get(0).testId.equals(before[0]) && open.get(0).phase.equals("OUTSTANDING") && open.get(0).restoration.equals("outstanding")
                    && open.get(0).outcomes.equals(Arrays.asList("setting_confirmed", "interrupted")), "journal after restart " + open);
        });
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "controls")); settle();
        awaitText("Unfinished test · Dashboard units"); screenshot("interrupt-2-relaunch-banner");
        main(() -> { View review = root().findViewWithTag("scooter-review-test"); require(review != null && review.isShown(), "no Review pill"); require("Review unfinished test".contentEquals(review.getContentDescription()), "Review pill label " + review.getContentDescription()); review.performClick(); }); settle();
        awaitText("Unfinished test · Dashboard units");
        main(() -> require(!texts(root()).contains("Run check") && !texts(root()).contains("Run again")
                && !texts(root()).contains("Try a setting") && root().findViewWithTag("developer-compatibility-check") == null,
                "Recovery opened the developer checker"));
        main(() -> require(texts(root()).contains("Connect to this scooter to review.") && texts(root()).contains("Setting confirmed · Interrupted/no response"), "offline review " + texts(root()).replace('\n', '|')));
        revealText("Unfinished test · Dashboard units"); screenshot("interrupt-3-relaunch-review-offline");
        // The synthetic scooter kept the test value through the app's death.
        fake = new FakeScooter(this, "ut5"); fake.set(7, 0); fake.motion = true;
        main(() -> fake.attach(activity.service().ble, p));
        await(() -> { boolean[] r = {false}; main(() -> r[0] = activity.service().ble.ready()); return r[0]; }, "reconnect not ready", 12000);
        SystemClock.sleep(6000);
        require(fake.writes.isEmpty() && fake.reads.contains(0x70), "automatic writes after restart and reconnect: " + fake.describeWrites());
        awaitText("Restore to Kilometers / km/h");
        main(() -> require(texts(root()).contains("Still on the test value, Miles / mph."), "review text"));
        revealText("Unfinished test · Dashboard units"); screenshot("interrupt-4-reconnected-review");
        require(fake.writes.isEmpty(), "write before the Restore tap");
        tapText("Restore to Kilometers / km/h", "restore");
        awaitPhase(GuidedTest.Phase.DONE, "restore after restart");
        require(fake.writes.size() == 1 && fake.lastWriteValue() == 1 && fake.value("display_units") == 1, "restore writes " + fake.describeWrites());
        main(() -> {
            GuidedTest t = activity.service().ble.guidedTest();
            require(t.outcomeTexts().equals(Arrays.asList("Setting confirmed", "Interrupted/no response", "Restoration confirmed")) && t.restorationStatus().equals("confirmed"), "outcomes after restore " + t.outcomeTexts());
            require(activity.service().ble.unresolvedTests().isEmpty(), "still unfinished");
        });
        awaitText("Restoration confirmed"); revealText("Test · Dashboard units"); screenshot("interrupt-5-restored");
        closeCard("ut5");
        main(() -> require(texts(root()).contains("No unfinished tests.") && !texts(root()).contains("Try a setting")
                && !texts(root()).contains("Run check"), "Recovery completion exposed new developer work"));
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "controls")); settle();
        main(() -> require(byText(root(), "Unfinished test · Dashboard units") == null, "banner after restore"));
        checks.add("Process killed after the target was confirmed: on relaunch the journal reads OUTSTANDING (Setting confirmed · Interrupted/no response), the Scooter page shows the banner, "
                + "nothing is written on start or reconnect, and one explicit Restore tap writes the baseline once → Restoration confirmed");
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
            switch (feature) { case "headlight": return 4; case "range_algorithm": return 6; case "display_units": return 7; case "auto_sensor": return 8; case "turn_sound": return 12; default: throw new IllegalArgumentException(feature); }
        }
        static int offset(int cmd) {
            switch (cmd) { case 0x54: return 4; case 0x56: return 6; case 0x55: return 7; case 0x57: return 8; case 0x60: return 12;
                case 0x5f: return 11; case 0x6a: return 19; case 0x6c: return 21; default: return -1; }
        }
        int value(String feature) { return state[offset(feature)] & 255; }
        int lastWriteValue() { byte[] f = writes.get(writes.size() - 1); return f[5] & 255; }
        boolean matchesBaseline() { return Arrays.equals(state, baseline); }
        String diff() { StringBuilder b = new StringBuilder(); for (int i = 0; i < state.length; i++) if (state[i] != baseline[i]) b.append(i).append(':').append(baseline[i]).append("->").append(state[i]).append(' '); return b.toString(); }
        String describeWrites() { StringBuilder b = new StringBuilder("["); for (byte[] f : writes) b.append(String.format(Locale.ROOT, "0x%02x=%d ", f[3] & 255, f[5] & 255)); return b.append(']').toString(); }

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
            int length = frame[4] & 255, at = offset(cmd);
            if (length != 1 || at < 0) throw new IllegalStateException(String.format(Locale.ROOT, "Unexpected write 0x%02x length %d", cmd, length));
            if (rejectNext != 0) { int code = rejectNext; rejectNext = 0; notify(cmd, code, new byte[0]); return; }
            if (!ignoreWrites) state[at] = frame[5];
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
