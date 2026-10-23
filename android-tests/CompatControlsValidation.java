package com.jjricks.volt.tests;

import android.Manifest;
import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
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
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import com.jjricks.volt.BleController;
import com.jjricks.volt.CapabilityResolver;
import com.jjricks.volt.DashboardConfig;
import com.jjricks.volt.FeatureVariant;
import com.jjricks.volt.MainActivity;
import com.jjricks.volt.ModelRegistry;
import com.jjricks.volt.MoreScreen;
import com.jjricks.volt.OnboardingScreen;
import com.jjricks.volt.ScooterCommands;
import com.jjricks.volt.ScooterProfiles;
import com.jjricks.volt.ScooterService;

/**
 * Fresh disposable emulator only. Developer simulator with synthetic advertised model IDs and
 * synthetic serial regions (memory only, no frames): checks the C5 OEM-mapped control families on
 * the Scooter page, choice and confirm sheets, region-independent choices and firmware refusals, the Lights page and
 * Quick access tiles. Every change it makes goes to the simulator; nothing reaches Bluetooth.
 */
public final class CompatControlsValidation extends Instrumentation {
    private Bundle arguments;
    private MainActivity activity;
    private boolean admitted;
    private int assertions;
    private final ArrayList<String> checks = new ArrayList<>(), screenshots = new ArrayList<>();

    @Override public void onCreate(Bundle args) { arguments = args == null ? new Bundle() : args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            admit(); admitted = true;
            require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                    .putBoolean("developer_options", true).putString("theme", arguments.getString("theme", "dark")).putString("units", "mi")
                    .putFloat("text_scale", Float.parseFloat(arguments.getString("text_scale", "1"))).commit(), "Fixture settings failed");
            seed(); launch();
            main(() -> require(activity.service().setSimulation(true), "Simulation did not start")); settle();
            qGroupNoRegion();
            qGroupUs();
            twoColourZones();
            firmwareUnknown();
            groupM();
            groupS();
            regionalLongRange();
            main(() -> { activity.service().ble.setSimulationRegion(null); activity.service().ble.setSimulationModel(""); }); settle();
            ut5Unchanged();
            main(() -> require(activity.service().setSimulation(false), "Simulation did not stop")); settle();
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", error.toString());
            if (activity != null) try { screenshot("failure"); } catch (Throwable ignored) { }
        } finally {
            result.putInt("assertions", assertions); result.putStringArrayList("checks", checks);
            result.putStringArrayList("screenshots", screenshots); result.putBoolean("physical_device_tested", false);
            if (admitted && activity != null) try { main(() -> { if (activity.service() != null) activity.service().setSimulation(false); activity.finish(); ScooterService.setUiVisible(false); getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); }); } catch (Throwable ignored) { }
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    /** 2585 (synthetic 25851): missing region still offers this model's permissive OEM choice union. */
    private void qGroupNoRegion() throws Exception {
        model("25851", null);
        scooterPage("q-noregion");
        expectCaption("maximum_speed", "Untested here");
        main(() -> require(((View)field(field(screen(), "topSpeed"), "card")).getVisibility() != View.VISIBLE, "UT5 MAX top speed card shown for another model"));
        for (String key : new String[]{"traction_control", "low_power", "charge_limit", "timed_charging", "hill_start_assist", "parking_assist",
                "hill_descent_control", "prompt_tone", "dual_drive", "start_speed"}) expectCaption(key, "Untested here");
        expectHidden("long_range_mode"); expectHidden("speed_limit"); expectHidden("overspeed_control");
        // Model-specific domains remain exact even without region metadata.
        expectChoices("maximum_speed", new int[]{25, 32, 40, 50, 70});
        expectChoices("start_speed", new int[]{0, 1, 2, 3, 4, 5});
        screenshotRow("q-noregion-rows", "traction_control");
        // A long charge-start value moves under the label beside its caption instead of squeezing both.
        main(() -> {
            Object row = row("charge_start");
            String value = String.valueOf(((TextView)field(row, "value")).getText()), sub = String.valueOf(((TextView)field(field(row, "row"), "sub")).getText());
            require(value.isEmpty() && sub.startsWith("Not scheduled") && sub.contains("Untested here"), "Charge start row value \"" + value + "\" caption \"" + sub + "\"");
        });
        screenshotRow("q-noregion-charge-start", "charge_start");
        screenshotRow("q-noregion-speed", "maximum_speed");
        // Traction control off asks first; Cancel keeps it on.
        toggleRow("traction_control");
        confirmSheet("Turn off traction control? The official app asks before this change. Volt has not tested it on this model.", "q-tcs-confirm", "Cancel");
        main(() -> require(activity.service().ble.featureValue("traction_control") == 1, "Cancel changed traction control"));
        // Single motor asks first; Continue sends it to the simulator, and turning it back on asks nothing.
        toggleRow("dual_drive");
        confirmSheet("Switch to single motor? The official app asks before this change. Volt has not tested it on this model.", "q-dual-confirm", "Continue");
        await(() -> Integer.valueOf(0).equals(activity.service().ble.featureValue("dual_drive")), "Continue did not switch to single motor");
        toggleRow("dual_drive");
        await(() -> Integer.valueOf(1).equals(activity.service().ble.featureValue("dual_drive")), "Dual motor did not return without a prompt");
        noDialog("Dual motor back on");
        // Prompt tone (Lab on the UT5 MAX) is an ordinary five-sound choice here; charge ceiling has five steps.
        choiceSheet("prompt_tone", new String[]{"Sound 1", "Sound 2", "Sound 3", "Sound 4", "Sound 5"}, "q-prompt-choices"); closeSheet();
        choiceSheet("charge_limit", new String[]{"80%", "85%", "90%", "95%", "100%"}, "q-charge-choices"); closeSheet();
        choiceSheet("start_speed", null, "q-start-choices"); closeSheet();
        // Lights page: six colours per zone colour row on their own line.
        lightsPage("q-lights");
        main(() -> {
            Object row = row("line_color");
            require(Boolean.TRUE.equals(field(row, "wideSwatches")), "2585 line colour swatches are not on their own line");
            int visible = 0; for (Object v : ((java.util.Map<?, ?>)field(row, "options")).values()) if (((View)v).getVisibility() == View.VISIBLE) visible++;
            require(visible == 6, "2585 line colour shows " + visible + " swatches");
        });
        // Experimental controls keep OEM-mapped controls out: 2585 offers every control Volt could try, so nothing is listed.
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "more")); settle();
        main(() -> invoke(more(), "selectSection", new Class<?>[]{String.class}, "Lab")); settle();
        main(() -> {
            for (String key : new String[]{"prompt_tone", "dual_drive"})
                require(!activity.service().ble.experimentalDecision(key).experimental(), "Offered " + key + " is not experimental");
            require(((List<?>)field(more(), "labRows")).isEmpty() && "Nothing to try".contentEquals(((TextView)field(more(), "labTitle")).getText()), "2585 lists experimental controls");
        });
        screenshot("q-lab");
        main(() -> invoke(more(), "selectSection", new Class<?>[]{String.class}, "")); settle();
        home("q-noregion");
        checks.add("25851 without region: maximum speed 25/32/40/50/70 km/h, Untested here rows, TCS/single-motor confirm sheets (Cancel keeps, Continue sends to the simulator), 0-5 km/h kick-start, six-colour zones, nothing in Experimental controls");
    }

    /** 2585: US and DE offer the same model-specific table, retaining high-speed and low-start cautions. */
    private void qGroupUs() throws Exception {
        model("25851", "US");
        scooterPage("q-us");
        expectCaption("maximum_speed", "Untested here");
        main(() -> {
            BleController ble = activity.service().ble;
            require(Arrays.equals(ble.featureChoices("maximum_speed"), new int[]{25, 32, 40, 50, 70}), "2585 US table " + Arrays.toString(ble.featureChoices("maximum_speed")));
            require(Arrays.equals(ble.featureChoices("start_speed"), new int[]{0, 1, 2, 3, 4, 5}), "2585 US kick-start " + Arrays.toString(ble.featureChoices("start_speed")));
            require(ModelRegistry.caution(ble.decision("maximum_speed"), 32) == null, "No prompt at 32 km/h");
        });
        screenshotRow("q-us-speed", "maximum_speed");
        choiceSheet("maximum_speed", null, "q-us-speed-choices");
        int before = activity.service().ble.featureValue("maximum_speed");
        chooseInSheet(70);
        confirmSheet("Above 32 km/h: check the rules where you ride. The official app asks before this change. Volt has not tested it on this model.", "q-us-speed-confirm", "Cancel");
        main(() -> require(activity.service().ble.featureValue("maximum_speed") == before, "Cancel changed maximum speed"));
        closeSheet();
        choiceSheet("start_speed", null, "q-us-start-choices");
        chooseInSheet(1);
        confirmSheet("Kick-start speed below 3 km/h. The official app shows a caution for this change. Volt has not tested it on this model.", "q-us-start-confirm", "Cancel");
        closeSheet();
        // Region metadata cannot hide or reduce this same model's known choices.
        model("25851", "DE"); scooterPage("q-de");
        expectCaption("maximum_speed", "Untested here");
        expectCaption("start_speed", "Untested here");
        expectChoices("maximum_speed", new int[]{25, 32, 40, 50, 70});
        expectChoices("start_speed", new int[]{0, 1, 2, 3, 4, 5});
        choiceSheet("maximum_speed", null, "q-de-speed-choices"); closeSheet();
        choiceSheet("start_speed", null, "q-de-start-choices"); closeSheet();
        checks.add("25851 missing/US/DE regions: maximum speed offers exactly 25/32/40/50/70 km/h (shown in mph), 70 asks first, 32 does not; kick-start 0-5 with a caution below 3; the actual Scooter and dashboard sheets match those domains");
    }

    /** 2547: two colours per zone (the UT5 MAX swatches only) and a 25-60 km/h table without the 2585 prompt. */
    private void twoColourZones() throws Exception {
        model("25471", "US");
        scooterPage("q2547");
        main(() -> {
            BleController ble = activity.service().ble;
            require(Arrays.equals(ble.featureChoices("maximum_speed"), new int[]{25, 32, 40, 50, 60}), "2547 table " + Arrays.toString(ble.featureChoices("maximum_speed")));
            require(ModelRegistry.caution(ble.decision("maximum_speed"), 60) == null, "2547 has no >32 prompt");
        });
        lightsPage("q2547-lights");
        main(() -> {
            Object row = row("line_color");
            int visible = 0; for (Object v : ((java.util.Map<?, ?>)field(row, "options")).values()) if (((View)v).getVisibility() == View.VISIBLE) visible++;
            require(visible == 2 && !Boolean.TRUE.equals(field(row, "wideSwatches")), "2547 line colour shows " + visible + " swatches");
        });
        checks.add("25471 US: two-colour zones beside the label; maximum speed 25/32/40/50/60 km/h with no prompt");
    }

    /** Group K (2345): firmware gates fail closed while the meter firmware is unread. */
    private void firmwareUnknown() throws Exception {
        model("23451", "US");
        scooterPage("k-firmware");
        expectCaption("lock_expiry", "Firmware not read yet");
        expectCaption("maximum_speed", "Firmware not read yet");
        expectCaption("start_speed", "Firmware not read yet");
        expectCaption("acceleration", "Untested here");
        screenshotRow("k-firmware-rows", "maximum_speed");
        screenshotRow("k-firmware-lock", "lock_expiry");
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "more")); settle();
        main(() -> invoke(more(), "selectSection", new Class<?>[]{String.class}, "Security")); settle();
        main(() -> {
            CapabilityResolver.Decision pin = activity.service().ble.decision("pin");
            require(pin.reason == CapabilityResolver.Reason.FIRMWARE_UNKNOWN && !(Boolean)invoke(more(), "ownerReady"), "K PIN without firmware: " + pin);
            String notice = String.valueOf(((TextView)field(more(), "securityNotice")).getText());
            require(notice.equals("Firmware not read yet"), "K security notice " + notice);
        });
        screenshot("k-security");
        main(() -> invoke(more(), "selectSection", new Class<?>[]{String.class}, "")); settle();
        checks.add("23451: lock duration, maximum speed, kick-start and PIN say 'Firmware not read yet' and stay disabled; Eco/Turbo is an ordinary row");
    }

    /** Group M (2504): on/off energy recovery with the OEM caution, 8-16 km/h speed cap, 3-5 kick-start. */
    private void groupM() throws Exception {
        model("25041", null);
        scooterPage("m");
        expectCaption("energy_recovery", "Untested here");
        expectCaption("speed_limit", "Untested here");
        expectChoices("start_speed", new int[]{3, 4, 5});
        main(() -> {
            BleController ble = activity.service().ble;
            require(Arrays.equals(ble.featureChoices("energy_recovery"), new int[]{0, 1}), "M energy recovery " + Arrays.toString(ble.featureChoices("energy_recovery")));
            int[] cap = ble.featureChoices("speed_limit");
            require(cap.length == 10 && cap[0] == 0 && cap[1] == 8 && cap[9] == 16, "M speed cap " + Arrays.toString(cap));
        });
        expectHidden("lock_expiry"); expectHidden("maximum_speed");
        screenshotRow("m-rows", "energy_recovery");
        // The simulator starts at a UT5 level outside {0,1}: set On first (a synthetic simulator value), then Off asks.
        main(() -> require(activity.service().ble.set("energy_recovery", 1), "Simulator refused energy recovery On: " + activity.service().ble.message())); settle();
        choiceSheet("energy_recovery", new String[]{"Off", "On"}, "m-energy-choices");
        chooseInSheet(0);
        confirmSheet("Turn off energy recovery? The official app shows a caution for this change. Volt has not tested it on this model.", "m-energy-confirm", "Cancel");
        closeSheet();
        choiceSheet("speed_limit", null, "m-cap-choices");
        closeSheet();
        checks.add("25041: energy recovery Off/On with the OEM caution, speed cap Off/8-16 km/h, no lock duration or maximum speed rows");
    }

    /** Group S (2443): ambient, logo and daytime light rows on the Lights page; dual motor toggle. */
    private void groupS() throws Exception {
        model("24431", "US");
        main(() -> require(activity.service().ble.set("energy_recovery", 30), "Simulator refused energy recovery Low: " + activity.service().ble.message())); settle();
        lightsPage("s-lights");
        for (String key : new String[]{"ambient_light_mode", "logo_light", "daytime_light"}) expectCaption(key, "Untested here");
        main(() -> {
            for (String key : new String[]{"line_light", "star_light"}) require(((View)field(row(key), "card")).getVisibility() != View.VISIBLE, "S shows " + key);
        });
        choiceSheet("logo_light", new String[]{"Off", "On", "Follow headlight"}, "s-logo-choices");
        closeSheet();
        main(() -> invoke(screen(), "showMain")); settle();
        checks.add("24431: ambient, logo and daytime lights (Off/On/Follow headlight) on the Lights page; no line/star zones");
    }

    /** 2745 (group G): region metadata does not gate mapped long-range or kick-start controls. */
    private void regionalLongRange() throws Exception {
        model("27451", null);
        // Back to a level after group M's on/off value (a synthetic simulator value in 2745's domain).
        main(() -> require(activity.service().ble.set("energy_recovery", 30), "Simulator refused energy recovery Low: " + activity.service().ble.message())); settle();
        for (String region : new String[]{null, "US", "DE"}) {
            model("27451", region);
            String label = "g-" + (region == null ? "noregion" : region.toLowerCase(Locale.ROOT));
            scooterPage(label);
            expectCaption("long_range_mode", "Untested here");
            expectCaption("start_speed", "Untested here");
            expectChoices("long_range_mode", new int[]{0, 1});
            expectChoices("start_speed", new int[]{0, 1, 2, 3, 4, 5});
            screenshotRow(label + "-rows", "long_range_mode");
            choiceSheet("start_speed", null, label + "-start-choices"); closeSheet();
            final int before = activity.service().ble.featureValue("long_range_mode");
            toggleRow("long_range_mode");
            await(() -> Integer.valueOf(before == 1 ? 0 : 1).equals(activity.service().ble.featureValue("long_range_mode")), label + ": long range did not toggle");
            toggleRow("long_range_mode");
            await(() -> Integer.valueOf(before).equals(activity.service().ble.featureValue("long_range_mode")), label + ": long range did not restore");
        }
        checks.add("27451 missing/US/DE regions: same long-range Off/On control toggles and restores, same 0-5 km/h kick-start choices appear in the sheet");
    }

    /** Back on the UT5 MAX simulator: no Untested captions, the top speed card, no OEM-only rows. */
    private void ut5Unchanged() throws Exception {
        scooterPage("ut5");
        main(() -> {
            BleController ble = activity.service().ble;
            require(((View)field(field(screen(), "topSpeed"), "card")).getVisibility() == View.VISIBLE, "UT5 MAX top speed card");
            for (Object row : (List<?>)field(screen(), "rows")) {
                ScooterCommands.Feature feature = (ScooterCommands.Feature)field(row, "feature");
                String sub = String.valueOf(((TextView)field(field(row, "row"), "sub")).getText());
                require(!sub.contains("Untested here") && !sub.contains("Not offered"), "UT5 MAX caption on " + feature.key + ": " + sub);
                if (ScooterCommands.oemOnly(feature.key) || feature.experimental || feature.key.equals("maximum_speed"))
                    require(((View)field(row, "card")).getVisibility() != View.VISIBLE, "UT5 MAX shows " + feature.key);
                if (ble.decision(feature.key).variant != null) require(ble.decision(feature.key).variant.decode == FeatureVariant.Decode.UT5_FEATURE || ScooterCommands.oemOnly(feature.key), "UT5 MAX variant " + feature.key);
            }
            require(ModelRegistry.caution(ble.decision("traction_control"), 0) == null && ModelRegistry.caution(ble.decision("maximum_speed"), 50) == null, "UT5 MAX asks before a change");
        });
        // Traction control off on the UT5 MAX sends at once (no confirm sheet).
        toggleRow("traction_control"); noDialog("UT5 MAX traction control");
        await(() -> Integer.valueOf(0).equals(activity.service().ble.featureValue("traction_control")), "UT5 MAX traction control did not turn off directly");
        toggleRow("traction_control");
        await(() -> Integer.valueOf(1).equals(activity.service().ble.featureValue("traction_control")), "UT5 MAX traction control did not turn on");
        home("ut5");
        checks.add("UT5 MAX simulator: no Untested/region captions, top speed card kept, no OEM-only rows, traction control toggles without a sheet");
    }

    // ---- steps ----
    private void model(String pid, String region) {
        main(() -> { activity.service().ble.setSimulationRegion(region); activity.service().ble.setSimulationModel(pid); }); settle();
        main(() -> require(activity.service().ble.descriptor().simulated && String.valueOf(region).equals(String.valueOf(activity.service().ble.descriptor().region)), "Simulator region " + activity.service().ble.descriptor()));
    }
    private void scooterPage(String label) throws Exception {
        main(() -> { activity.getSharedPreferences("app_settings", Context.MODE_PRIVATE).edit().putString("scooter_section", "").commit(); invoke(activity, "select", new Class<?>[]{String.class}, "home"); }); settle();
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "controls")); settle();
        main(() -> { activity.service().ble.tickSimulation(); activity.redraw(); }); settle();
        main(() -> {
            BleController ble = activity.service().ble;
            require(screen().getClass().getSimpleName().equals("ControlsScreen"), "Scooter page is not active");
            boolean lights = Boolean.TRUE.equals(field(screen(), "lights"));
            for (Object row : (List<?>)field(screen(), "rows")) {
                ScooterCommands.Feature feature = (ScooterCommands.Feature)field(row, "feature");
                if (feature.key.equals("range_algorithm")) continue;
                View card = (View)field(row, "card");
                if (card.getVisibility() != View.VISIBLE) continue;
                CapabilityResolver.Decision d = ble.decision(feature.key);
                String sub = String.valueOf(((TextView)field(field(row, "row"), "sub")).getText());
                require(d.support == CapabilityResolver.Support.KNOWN_ENCODING, label + ": shown row " + feature.key + " without a known encoding");
                if (!ble.descriptor().rulePid().isEmpty() && ModelRegistry.resolve(ble.descriptor()).layer != ModelRegistry.Layer.UT5_MAX) {
                    require(d.evidence == CapabilityResolver.Evidence.OEM_MAPPED, label + ": " + feature.key + " evidence " + d.evidence);
                    boolean refused = !d.available() && !d.blockedByRide();
                    require(refused ? sub.contains(d.reasonText()) : sub.contains("Untested here"), label + ": " + feature.key + " caption \"" + sub + "\" for " + d);
                }
            }
            require(!lights, label + ": main page expected");
        });
        screenshot(label + "-scooter");
        checks.add(label + ": every shown row has a known encoding, OEM_MAPPED evidence and Untested here or its refusal reason");
    }
    private void lightsPage(String label) throws Exception {
        scooterPage(label + "-main");
        main(() -> invoke(screen(), "showLights")); settle(); SystemClock.sleep(500); settle();
        main(() -> require(Boolean.TRUE.equals(field(screen(), "lights")), "Lights page did not open"));
        screenshot(label);
        main(() -> { View scroll = (View)field(activity, "scroll"); if (scroll instanceof ViewGroup) scroll.scrollTo(0, ((ViewGroup)scroll).getChildAt(0).getHeight()); }); settle();
        screenshot(label + "-end");
        main(() -> { View scroll = (View)field(activity, "scroll"); if (scroll != null) scroll.scrollTo(0, 0); }); settle();
    }
    private void home(String label) throws Exception {
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "home")); settle();
        main(() -> { activity.service().ble.tickSimulation(); activity.redraw(); }); settle();
        int[] counts = {0, 0};
        main(() -> {
            BleController ble = activity.service().ble;
            for (View grid : tagged(root(), "quick-access-grid", "setting-tiles"))
                for (int i = 0; i < ((ViewGroup)grid).getChildCount(); i++) {
                    View cell = ((ViewGroup)grid).getChildAt(i);
                    Object entry = field(cell, "entry");
                    ScooterCommands.Feature feature = (ScooterCommands.Feature)invoke(entry, "feature");
                    if (feature == null || feature.key.equals("range_algorithm")) continue;
                    CapabilityResolver.Decision d = ble.decision(feature.key);
                    boolean toggle = (Boolean)invoke(entry, "directToggle", new Class<?>[]{CapabilityResolver.Decision.class}, d);
                    String description = String.valueOf(cell.getContentDescription());
                    require(description.endsWith(toggle ? ", toggle" : ", open"), label + ": tile " + feature.key + " says " + description);
                    if (Boolean.TRUE.equals(field(grid, "tiles"))) require(toggle == Boolean.TRUE.equals(field(cell, "toggle")), label + ": tile cue " + feature.key);
                    boolean unmapped = !ble.controlOffered(feature.key);
                    require(unmapped == description.contains("not on this model"), label + ": tile " + feature.key + " mapped state " + description);
                    // An OEM-mapped non-Lab toggle toggles directly here even where the UT5 MAX keeps it in Lab.
                    if (d.variant != null && d.variant.decode != FeatureVariant.Decode.UT5_FEATURE && !d.variant.lab && feature.type == ScooterCommands.Type.TOGGLE
                            && d.support == CapabilityResolver.Support.KNOWN_ENCODING && !feature.sensitive && !feature.key.equals("proximity_key"))
                        require(toggle, label + ": " + feature.key + " should toggle directly");
                    counts[toggle ? 0 : 1]++;
                }
        });
        reveal("quick-access-grid"); screenshot(label + "-quick");
        reveal("setting-tiles"); screenshot(label + "-tiles");
        if (!label.equals("ut5")) {
            // The dashboard opens the same model-specific choices, including without region metadata.
            expectChoices("maximum_speed", new int[]{25, 32, 40, 50, 70});
            main(() -> invoke(activity, "quickAction", new Class<?>[]{String.class}, "feature:maximum_speed")); settle();
            main(() -> {
                Object sheet = field(activity, "choiceSheet");
                require(sheet != null && field(activity, "quickMenuDialog") == null, label + ": maximum speed tile did not open its choices");
                require(Arrays.equals((int[])field(sheet, "choices"), new int[]{25, 32, 40, 50, 70}), label + ": maximum speed tile choices differ from the model domain");
            });
            screenshot(label + "-tile-speed-choices"); closeSheet();
            // A dual motor tile toggles here (Lab on the UT5 MAX) and asks before single motor.
            main(() -> invoke(activity, "quickAction", new Class<?>[]{String.class}, "feature:dual_drive")); settle();
            confirmSheet("Switch to single motor? The official app asks before this change. Volt has not tested it on this model.", label + "-tile-dual-confirm", "Cancel");
            // A prompt tone tile opens its choices, not the Lab.
            main(() -> invoke(activity, "quickAction", new Class<?>[]{String.class}, "feature:prompt_tone")); settle();
            main(() -> require(field(activity, "choiceSheet") != null && field(activity, "quickMenuDialog") == null, label + ": prompt tone tile did not open its choices"));
            screenshot(label + "-tile-prompt-choices");
            closeSheet();
        } else {
            main(() -> invoke(activity, "quickAction", new Class<?>[]{String.class}, "feature:prompt_tone")); settle();
            main(() -> require(field(activity, "quickMenuDialog") != null && field(activity, "choiceSheet") == null, "UT5 MAX prompt tone tile left the Lab"));
            screenshot(label + "-tile-prompt-lab");
            main(() -> invoke(activity, "closeQuickMenu")); settle();
        }
        main(() -> { View scroll = (View)field(activity, "scroll"); if (scroll != null) scroll.scrollTo(0, 0); }); settle();
        checks.add(label + ": " + counts[0] + " toggle and " + counts[1] + " open tiles follow the decided variant");
    }
    private void expectCaption(String key, String text) {
        main(() -> {
            Object row = row(key);
            require(((View)field(row, "card")).getVisibility() == View.VISIBLE, "Row " + key + " hidden for " + activity.service().ble.descriptor());
            String sub = String.valueOf(((TextView)field(field(row, "row"), "sub")).getText());
            require(sub.contains(text), "Row " + key + " caption \"" + sub + "\" lacks \"" + text + "\"");
            if (!text.equals("Untested here")) require(!((View)field(row, "action")).isEnabled() || field(row, "toggle") != null && !((View)field(row, "toggle")).isEnabled(), "Refused row " + key + " is enabled");
        });
    }
    private void expectChoices(String key, int[] expected) {
        main(() -> {
            BleController ble = activity.service().ble;
            require(ble.decision(key).available(), key + ": mapped control unavailable for " + ble.descriptor() + ": " + ble.decision(key));
            require(Arrays.equals(ble.featureChoices(key), expected), key + ": choices " + Arrays.toString(ble.featureChoices(key)) + " instead of " + Arrays.toString(expected));
        });
    }
    private void expectHidden(String key) { main(() -> require(((View)field(row(key), "card")).getVisibility() != View.VISIBLE, "Row " + key + " shown for " + activity.service().ble.descriptor())); }
    private void screenshotRow(String label, String key) throws Exception {
        main(() -> {
            // Bring the row near the top, below the page header.
            View card = (View)field(row(key), "card"), scroll = (View)field(activity, "scroll");
            int[] a = new int[2], b = new int[2]; card.getLocationInWindow(a); scroll.getLocationInWindow(b);
            scroll.scrollBy(0, a[1] - b[1] - Math.round(96 * card.getResources().getDisplayMetrics().density));
        }); settle();
        screenshot(label);
    }
    private void toggleRow(String key) {
        main(() -> { View toggle = (View)field(row(key), "toggle"); require(toggle != null && toggle.isEnabled(), "Toggle " + key + " unavailable"); toggle.performClick(); }); settle();
    }
    private void choiceSheet(String key, String[] labels, String label) throws Exception {
        main(() -> require(field(activity, "choiceSheet") == null, "A choice sheet is still open before " + key));
        main(() -> invoke(activity, "editFeature", new Class<?>[]{ScooterCommands.Feature.class}, ScooterCommands.find(key))); settle(); SystemClock.sleep(400); settle();
        main(() -> {
            Object sheet = field(activity, "choiceSheet"); require(sheet != null, "No choice sheet for " + key);
            int[] choices = (int[])field(sheet, "choices");
            require(Arrays.equals(choices, activity.service().ble.featureChoices(key)), key + ": sheet choices differ from the decision");
            if (labels != null) {
                String shown = texts((View)field(sheet, "selector") != null ? (View)field(sheet, "selector") : ((android.app.Dialog)field(sheet, "dialog")).getWindow().getDecorView());
                for (String l : labels) require(shown.contains(l) || ((View)field(sheet, "selector")) != null, key + ": missing choice " + l + " in " + shown);
                CapabilityResolver.Decision d = activity.service().ble.decision(key);
                require(Arrays.equals(d.labels(), labels), key + ": labels " + Arrays.toString(d.labels()));
            }
        });
        screenshot(label);
    }
    private void chooseInSheet(int value) {
        main(() -> {
            Object sheet = field(activity, "choiceSheet"); int[] choices = (int[])field(sheet, "choices");
            int index = -1; for (int i = 0; i < choices.length; i++) if (choices[i] == value) index = i;
            require(index >= 0, "Choice " + value + " not offered");
            invoke(sheet, "choose", new Class<?>[]{int.class}, index);
        }); settle();
    }
    private void closeSheet() {
        main(() -> {
            Object sheet = field(activity, "choiceSheet"); if (sheet != null) ((android.app.Dialog)field(sheet, "dialog")).dismiss();
            Object dialog = field(activity, "controlDialog"); if (dialog != null) ((android.app.Dialog)dialog).dismiss();
            invoke(activity, "closeQuickMenu");
        }); settle(); SystemClock.sleep(300); settle();
        main(() -> require(field(activity, "choiceSheet") == null, "Choice sheet did not close"));
    }
    /** The confirm sheet: its exact copy, a screenshot, then the named button. */
    private void confirmSheet(String message, String label, String button) throws Exception {
        long end = SystemClock.uptimeMillis() + 6000; AccessibilityNodeInfo node = null;
        while (SystemClock.uptimeMillis() < end) { node = find(message); if (node != null) break; SystemClock.sleep(100); }
        require(node != null, "Confirm sheet \"" + message + "\" not shown");
        settle(); SystemClock.sleep(300); screenshot(label);
        AccessibilityNodeInfo action = find(button);
        require(action != null && action.performAction(AccessibilityNodeInfo.ACTION_CLICK), "Confirm sheet button " + button);
        settle(); SystemClock.sleep(300);
        require(find(message) == null, "Confirm sheet stayed open");
    }
    private void noDialog(String what) { settle(); SystemClock.sleep(300); require(find("Volt has not tested it on this model") == null, what + ": unexpected confirm sheet"); }
    private AccessibilityNodeInfo find(String text) {
        AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow(); if (root == null) return null;
        for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByText(text)) {
            CharSequence t = n.getText(); if (t != null && (t.toString().equals(text) || text.length() > 20 && t.toString().contains(text))) return n;
        }
        return null;
    }

    // ---- fixture ----
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
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        require(adapter == null || !adapter.isEnabled(), "Bluetooth must be disabled before validation");
    }
    private void seed() {
        main(() -> {
            ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
            ScooterProfiles.Profile profile = profiles.create("Synthetic controls fixture", "000000000000", 0);
            profile.serial = "X2538-SYNTHETIC"; profile.lastConnected = System.currentTimeMillis() - 60000; profiles.update(profile);
            DashboardConfig config = new DashboardConfig(settings(), profile.id);
            DashboardConfig.Widget quick = new DashboardConfig.Widget("quick");
            quick.quickActions.clear(); quick.quickActions.addAll(Arrays.asList("feature:traction_control", "feature:dual_drive", "feature:prompt_tone",
                    "feature:maximum_speed", "feature:hill_start_assist", "feature:energy_recovery", "lights", "controls"));
            DashboardConfig.Widget tiles = new DashboardConfig.Widget("shortcuts");
            tiles.quickActions.clear(); tiles.quickActions.addAll(Arrays.asList("feature:dual_drive", "feature:low_power", "feature:prompt_tone", "feature:charge_limit"));
            config.widgets.add(quick); config.widgets.add(tiles); config.save();
            OnboardingScreen.markDone(getTargetContext());
        });
    }
    private void launch() {
        activity = (MainActivity)startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> activity.service() != null && root().isAttachedToWindow() && root().getWidth() > 0, "App did not launch"); settle();
    }

    // ---- helpers ----
    private Object screen() { return field(activity, "screen"); }
    private Object row(String key) {
        for (Object row : (List<?>)field(screen(), "rows")) if (((ScooterCommands.Feature)field(row, "feature")).key.equals(key)) return row;
        throw new IllegalStateException("No row " + key);
    }
    private MoreScreen more() { Object screen = screen(); require(screen instanceof MoreScreen, "More screen is not active"); return (MoreScreen)screen; }
    private String texts(View view) {
        StringBuilder out = new StringBuilder();
        if (view instanceof TextView && view.isShown()) out.append(((TextView)view).getText()).append('\n');
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) out.append(texts(((ViewGroup)view).getChildAt(i)));
        return out.toString();
    }
    private void reveal(String tag) {
        main(() -> { View view = root().findViewWithTag(tag); require(view != null, "Missing " + tag); view.requestRectangleOnScreen(new Rect(0, 0, view.getWidth(), view.getHeight()), true); }); settle();
    }
    private List<View> tagged(View view, String... tags) {
        ArrayList<View> found = new ArrayList<>();
        for (String tag : tags) if (tag.equals(view.getTag())) found.add(view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) found.addAll(tagged(((ViewGroup)view).getChildAt(i), tags));
        return found;
    }
    private SharedPreferences settings() { return prefs("app_settings"); }
    private SharedPreferences prefs(String name) { return getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE); }
    private View root() { return (View)field(activity, "root"); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(300); waitForIdleSync(); }
    private void main(Runnable task) { runOnMainSync(task); }
    private void await(BooleanSupplier condition, String message) { long end = SystemClock.uptimeMillis() + 8000; while (!condition.getAsBoolean() && SystemClock.uptimeMillis() < end) SystemClock.sleep(40); require(condition.getAsBoolean(), message); }
    private void require(boolean condition, String message) { assertions++; if (!condition) throw new IllegalStateException(message); }
    private Object field(Object target, String name) { try { Class<?> type = target.getClass(); while (type != null) { try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch (NoSuchFieldException e) { type = type.getSuperclass(); } } throw new NoSuchFieldException(name); } catch (Exception e) { throw new IllegalStateException("Field " + name, e); } }
    private Object invoke(Object target, String name) { return invoke(target, name, new Class<?>[0]); }
    private Object invoke(Object target, String name, Class<?>[] types, Object... args) { try { Class<?> type = target.getClass(); while (type != null) { try { Method m = type.getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(target, args); } catch (NoSuchMethodException e) { type = type.getSuperclass(); } } throw new NoSuchMethodException(name); } catch (Exception e) { throw new IllegalStateException("Method " + name, e); } }
    private void screenshot(String label) throws Exception {
        File dir = new File(getTargetContext().getExternalFilesDir(null), "compatibility-validation"); require(dir.isDirectory() || dir.mkdirs(), "Screenshot directory unavailable");
        String variant = arguments.getString("variant", "controls").replaceAll("[^a-zA-Z0-9_-]", ""); File file = new File(dir, variant + "-" + label + ".png");
        android.graphics.Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "Screenshot unavailable");
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); } finally { bitmap.recycle(); }
        screenshots.add(file.getAbsolutePath());
    }
}
