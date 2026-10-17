package local.navee.offlinelock.tests;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.net.Uri;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;
import local.navee.offlinelock.BleController;
import local.navee.offlinelock.CapabilityResolver;
import local.navee.offlinelock.DashboardConfig;
import local.navee.offlinelock.MainActivity;
import local.navee.offlinelock.ModelRegistry;
import local.navee.offlinelock.MoreScreen;
import local.navee.offlinelock.OnboardingScreen;
import local.navee.offlinelock.ScooterCommands;
import local.navee.offlinelock.ScooterProfiles;
import local.navee.offlinelock.ScooterService;

/**
 * Fresh disposable emulator only. Uses the developer simulator's model choices (UT5 MAX, a mapped
 * OEM group, an unknown model) to check that every UI gate follows the same resolver decisions as
 * the write gate: Scooter rows, the limited-model banner, the clock row, the lock switch and Quick
 * access tiles (muted, never removed from the saved layout). The simulator builds no frames.
 * More › Compatibility runs the read-only check on each model and exports the report through the
 * preview's Share and Save file paths (intercepted in-process; the saved file is a synthetic file in
 * the app cache, copied next to the screenshots). The UT5 MAX connection read policy is compared
 * with the 1.3.1 arrays.
 */
public final class CompatibilityGatingValidation extends Instrumentation {
    private static final String[][] MODELS = {{"", "ut5"}, {"24431", "mapped"}, {"39991", "unknown"}};
    // Synthetic private values: none of them may reach a shared or saved report.
    private static final String FIXTURE_NAME = "Synthetic compatibility fixture", FIXTURE_IDENTITY = "A1B2C3D4E5F6",
            FIXTURE_SERIAL = "X2538-SYNTHETIC", FIXTURE_MAC = "C0:FF:EE:12:34:56";
    /** The 1.3.1 UT5 MAX read bundle after authentication and its battery poll (BleController.refresh/poll in 1.3.1). */
    private static final int[] UT5_AFTER_AUTH_131 = {0x70, 0x71, 0x72, 0x73, 0x74, 0x75, 0x76, 0x79, 0x7b}, UT5_BATTERY_131 = {0x72, 0x79};
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
            if ("import-name".equals(arguments.getString("mode", "gating"))) { importName(); result.putString("result", "PASS"); return; }
            seed(); launch();
            Map<String, String> layouts = layoutSnapshot();
            main(() -> require(activity.service().setSimulation(true), "Simulation did not start")); settle();
            for (String[] model : MODELS) {
                main(() -> activity.service().ble.setSimulationModel(model[0])); settle();
                verifyScooterPage(model[0], model[1]);
                verifyHome(model[0], model[1]);
                verifyMore(model[0], model[1]);
                verifyCompatibility(model[0], model[1]);
            }
            checks.add("Scooter rows show only controls with a known encoding for the simulated model; the UT5 MAX keeps every row");
            checks.add("Limited-model banner, clock row and lock switch follow the resolver; lock and unlock work on every standard model");
            checks.add("Compatibility check (simulator): reads only, holds every write except the lock, finishes with a preview-only report");
            checks.add("Unmapped Quick access tiles show a muted dash, say \"not on this model\" and stay in the saved layout");
            main(() -> activity.service().ble.setSimulationModel("")); settle();
            require(layouts.equals(layoutSnapshot()), "Saved dashboard layouts changed while switching simulated models");
            main(() -> require(activity.service().setSimulation(false), "Simulation did not stop")); settle();
            verifyIdentifyWhileRecording();
            verifyUt5ReadSchedule();
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
            ScooterProfiles.Profile profile = profiles.create(FIXTURE_NAME, FIXTURE_IDENTITY, 0);
            profile.serial = FIXTURE_SERIAL; profile.mac = FIXTURE_MAC; profile.lastConnected = System.currentTimeMillis() - 60000; profiles.update(profile);
            // Quick access and Setting tiles with scooter controls (mapped and unmapped on the other models).
            DashboardConfig config = new DashboardConfig(settings(), profile.id);
            DashboardConfig.Widget quick = new DashboardConfig.Widget("quick");
            quick.quickActions.clear(); quick.quickActions.addAll(Arrays.asList("feature:headlight", "feature:cruise_control",
                    "feature:energy_recovery", "feature:traction_control", "feature:maximum_speed", "feature:dual_drive", "lights", "controls"));
            DashboardConfig.Widget tiles = new DashboardConfig.Widget("shortcuts");
            tiles.quickActions.clear(); tiles.quickActions.addAll(Arrays.asList("feature:auto_sensor", "feature:turn_sound", "feature:charge_limit", "feature:range_algorithm"));
            config.widgets.add(quick); config.widgets.add(tiles); config.save();
            OnboardingScreen.markDone(getTargetContext());
        });
    }
    private void launch() {
        activity = (MainActivity)startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> activity.service() != null && root().isAttachedToWindow() && root().getWidth() > 0, "App did not launch"); settle();
    }

    private void verifyScooterPage(String pid, String label) throws Exception {
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "home")); settle();
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "controls")); settle();
        main(() -> { activity.service().ble.tickSimulation(); activity.redraw(); }); settle();
        int[] untested = {0};
        main(() -> {
            BleController ble = activity.service().ble;
            Object screen = field(activity, "screen");
            require(screen.getClass().getSimpleName().equals("ControlsScreen"), "Scooter page is not active");
            boolean ut5 = pid.isEmpty();
            for (Object row : (List<?>)field(screen, "rows")) {
                ScooterCommands.Feature feature = (ScooterCommands.Feature)field(row, "feature");
                View card = (View)field(row, "card");
                String group = (String)invoke(screen, "groupOf", new Class<?>[]{ScooterCommands.Feature.class}, feature);
                boolean onPage = !group.equals("Line lights") && !group.equals("Star lights");
                boolean known = feature.key.equals("range_algorithm") || ble.controlOffered(feature.key);
                CapabilityResolver.Decision decision = ble.decision(feature.key);
                require(known == (feature.key.equals("range_algorithm") || decision.support == CapabilityResolver.Support.KNOWN_ENCODING), label + ": simulated models always have identity evidence");
                require((card.getVisibility() == View.VISIBLE) == (known && onPage), label + ": row " + feature.key + " visibility does not follow its decision");
                if (ut5) require(known, "UT5 MAX lost row " + feature.key);
                // "Untested here": every OEM-mapped control on another model, never on the UT5 MAX.
                String sub = String.valueOf(((TextView)field(field(row, "row"), "sub")).getText());
                if (ut5 || feature.key.equals("range_algorithm")) require(!sub.contains("Untested here"), label + ": unexpected Untested caption on " + feature.key);
                else if (card.getVisibility() == View.VISIBLE) {
                    boolean caption = sub.contains("Untested here"), refused = !decision.available() && !decision.blockedByRide();
                    require(decision.evidence == CapabilityResolver.Evidence.OEM_MAPPED, label + ": shown row " + feature.key + " is not OEM-mapped");
                    require(caption != refused && (!refused || sub.contains(decision.reasonText())), label + ": row " + feature.key + " caption \"" + sub + "\" for " + decision);
                    if (caption) untested[0]++;
                }
            }
            View banner = (View)field(screen, "readOnlyBanner");
            require((banner.getVisibility() == View.VISIBLE) == !ut5, label + ": limited-model banner");
            if (!ut5) require(texts(banner).contains(BleController.LIMITED_MODEL), label + ": banner copy");
            require(((View)field(screen, "identifyAction")).getVisibility() != View.VISIBLE, label + ": no Identify for a simulated advertised model");
            View clockCard = (View)field(field(screen, "clockLine"), "card");
            boolean clockKnown = ble.controlOffered("clock_sync");
            require((clockCard.getVisibility() == View.VISIBLE) == clockKnown, label + ": clock row visibility");
            require(clockKnown == !pid.equals("39991"), label + ": clock row is offered for the UT5 MAX and the mapped group only");
            CapabilityResolver.Decision clock = ble.decision("clock_sync");
            require(((View)field(screen, "clockButton")).isEnabled() == (clock.available() || clock.blockedByRide()), label + ": clock row enabled state");
            View topSpeed = (View)field(field(screen, "topSpeed"), "card");
            require((topSpeed.getVisibility() == View.VISIBLE) == ble.controlOffered("maximum_speed"), label + ": top speed card");
            // Identify sends nothing in simulation and says so.
            ble.identifyModel();
            require(ble.message().contains("unavailable in simulation") && ble.state() == BleController.State.READY, label + ": Identify in simulation: " + ble.message());
        });
        if (pid.equals("24431")) require(untested[0] > 0, label + ": no Untested here caption on the mapped model");
        screenshot(label + "-scooter");
        main(() -> { View scroll = (View)field(activity, "scroll"); if (scroll instanceof ViewGroup) scroll.scrollTo(0, ((ViewGroup)scroll).getChildAt(0).getHeight()); }); settle();
        screenshot(label + "-scooter-end");
        main(() -> { View scroll = (View)field(activity, "scroll"); if (scroll != null) scroll.scrollTo(0, 0); }); settle();
        checks.add(label + ": Scooter page follows decisions (" + untested[0] + " Untested here captions)");
    }

    private void verifyHome(String pid, String label) throws Exception {
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "home")); settle();
        int[] counts = {0, 0};
        main(() -> {
            BleController ble = activity.service().ble;
            for (View grid : tagged(root(), "quick-access-grid", "setting-tiles"))
                for (int i = 0; i < ((ViewGroup)grid).getChildCount(); i++) {
                    View cell = ((ViewGroup)grid).getChildAt(i);
                    Object entry = field(cell, "entry");
                    ScooterCommands.Feature feature = (ScooterCommands.Feature)invoke(entry, "feature");
                    if (feature == null || feature.key.equals("range_algorithm")) continue;
                    boolean unmapped = !ble.controlOffered(feature.key);
                    CharSequence description = cell.getContentDescription();
                    require(unmapped == (description != null && description.toString().contains("not on this model")), label + ": tile " + feature.key + " muted state");
                    if (unmapped) require(texts(cell).contains("—") && !cell.isSelected() && !Boolean.TRUE.equals(field(cell, "highlighted")), label + ": unmapped tile " + feature.key + " is not muted: " + texts(cell));
                    if (pid.isEmpty()) require(!unmapped, "UT5 MAX tile became unmapped: " + feature.key);
                    counts[unmapped ? 1 : 0]++;
                }
        });
        require(counts[0] + counts[1] >= 8, label + ": Quick access and Setting tiles fixtures missing");
        if (pid.equals("39991")) require(counts[0] == 0, label + ": the unknown model shows a mapped control tile");
        if (pid.equals("24431")) require(counts[0] > 0 && counts[1] > 0, label + ": the mapped model needs mapped and unmapped tiles");
        reveal("quick-access-grid"); screenshot(label + "-quick");
        reveal("setting-tiles"); screenshot(label + "-tiles");
        main(() -> { View scroll = (View)field(activity, "scroll"); if (scroll != null) scroll.scrollTo(0, 0); }); settle();
        // The lock switch both ways, on every standard model (unlock is the recovery exception).
        for (int target : new int[]{1, 0}) {
            main(() -> {
                BleController ble = activity.service().ble;
                Object lock = field(activity, "lockControl");
                require(lock instanceof View, label + ": lock switch missing");
                require(ble.decision("lock", 1).available() && ble.decision("lock", 0).available(), label + ": lock and unlock are offered on a standard model");
                require(((View)lock).isEnabled(), label + ": lock switch enabled");
                require(ble.featureValue("lock") == 1 - target, label + ": lock starts at " + ble.featureValue("lock"));
                ((View)lock).performClick();
            });
            await(() -> { Integer value = activity.service().ble.featureValue("lock"); return value != null && value == target; }, label + ": lock switch did not change to " + target);
            main(() -> { activity.redraw(); View lock = (View)field(activity, "lockControl"); lock.requestRectangleOnScreen(new Rect(0, 0, lock.getWidth(), lock.getHeight()), true); }); settle();
            screenshot(label + "-home-" + (target == 1 ? "locked" : "unlocked"));
        }
        checks.add(label + ": " + counts[0] + " mapped and " + counts[1] + " muted control tiles; lock switch locked and unlocked");
    }

    private void verifyMore(String pid, String label) throws Exception {
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "more")); settle();
        main(() -> invoke(more(), "selectSection", new Class<?>[]{String.class}, "Security")); settle();
        main(() -> {
            BleController ble = activity.service().ble;
            MoreScreen more = more();
            CapabilityResolver.Decision pin = ble.decision("pin");
            boolean owner = (Boolean)invoke(more, "ownerReady");
            require(owner == pid.isEmpty(), label + ": PIN owner rows ready=" + owner + " for " + pin);
            require(owner == (pin.available() || pin.blockedByRide() || pin.reason == CapabilityResolver.Reason.BUSY), label + ": PIN rows follow the pin decision");
            String notice = String.valueOf(((TextView)field(more, "securityNotice")).getText());
            CapabilityResolver.Decision near = ble.decision("proximity_key");
            String expected = owner ? "Test while parked." : near.support != CapabilityResolver.Support.KNOWN_ENCODING ? near.reasonText() : pin.reasonText();
            require(notice.equals(expected), label + ": security notice \"" + notice + "\" expected \"" + expected + "\"");
            if (!owner) require(!((View)field(field(more, "proximityRow"), "toggle")).isEnabled(), label + ": proximity toggle stays off on an unmapped model");
        });
        screenshot(label + "-more-security");
        main(() -> invoke(more(), "selectSection", new Class<?>[]{String.class}, "Lab")); settle();
        main(() -> {
            BleController ble = activity.service().ble;
            int rows = 0;
            for (Object row : (List<?>)field(more(), "labRows")) {
                ScooterCommands.Feature feature = (ScooterCommands.Feature)field(row, "feature");
                CapabilityResolver.Decision decision = ble.decision(feature.key);
                boolean enabled = ((View)field(row, "action")).isEnabled();
                if (!(decision.available() || decision.blockedByRide())) {
                    require(!enabled, label + ": Lab Inspect enabled for " + feature.key);
                    String reason = (String)invoke(more(), "labReason", new Class<?>[]{ScooterCommands.Feature.class}, feature);
                    require(reason != null && (reason.equals(decision.reasonText()) || ble.featureValue(feature.key) == null || !feature.allows(ble.featureValue(feature.key))), label + ": Lab reason " + reason);
                }
                if (pid.isEmpty()) require(enabled == (ble.featureValue(feature.key) != null && feature.allows(ble.featureValue(feature.key))), "UT5 MAX Lab row changed: " + feature.key);
                rows++;
            }
            require(rows > 0, label + ": no Lab rows");
        });
        screenshot(label + "-more-lab");
        main(() -> invoke(more(), "selectSection", new Class<?>[]{String.class}, "Scooters")); settle();
        main(() -> {
            // The model line describes the saved profile; the simulated model never reaches it.
            ScooterProfiles.Profile profile = activity.service().profiles.active();
            TextView model = (TextView)field(more(), "scootersModel");
            require(model.getText().toString().equals(ModelRegistry.modelLine(profile.savedDescriptor())) && model.getText().toString().equals("UT5 MAX"), label + ": model line " + model.getText());
            require(profile.advertisedPid.isEmpty(), label + ": simulation wrote an advertised model ID to the profile");
        });
        screenshot(label + "-more-scooters");
        main(() -> invoke(more(), "selectSection", new Class<?>[]{String.class}, "")); settle();
        checks.add(label + ": More PIN, proximity, Lab and model line follow decisions");
    }

    /**
     * More › Compatibility on the simulator: the check holds every write except the lock, changes
     * no simulated setting, finishes within its bounds and offers the report only as a preview.
     */
    private void verifyCompatibility(String pid, String label) throws Exception {
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "more")); settle();
        main(() -> invoke(more(), "selectSection", new Class<?>[]{String.class}, "Compatibility")); settle();
        main(() -> require(field(more(), "compatibilityPanel") != null && texts(root()).contains("Run check"), label + ": Compatibility panel without Run check"));
        screenshot(label + "-more-compatibility");
        // Guided setting tests need a real scooter: the simulator offers none and starts none.
        main(() -> {
            BleController ble = activity.service().ble; Map<String, Integer> unchanged = new TreeMap<>(ble.simulationFeatures());
            for (String f : new String[]{"display_units", "headlight", "turn_sound", "auto_sensor", "range_algorithm"}) {
                CapabilityResolver.Decision d = ble.testDecision(f);
                require(!d.available() && (d.reason == CapabilityResolver.Reason.NEEDS_REAL_SCOOTER || d.support != CapabilityResolver.Support.KNOWN_ENCODING), label + ": simulated test offered for " + f + ": " + d);
            }
            ble.startTest("display_units", 0);
            require(!ble.testing() && ble.guidedTest() == null && ble.unresolvedTests().isEmpty() && unchanged.equals(new TreeMap<>(ble.simulationFeatures())), label + ": the simulator ran a guided test");
            require(ble.decision("display_units").reason != CapabilityResolver.Reason.TEST_ACTIVE, label + ": controls held by a test that never started");
        });
        revealText("TRY A SETTING", "Try a setting"); screenshot(label + "-compatibility-try");
        final Map<String, Integer> before = new TreeMap<>();
        main(() -> {
            BleController ble = activity.service().ble; before.putAll(ble.simulationFeatures());
            ble.startDiscovery(); int[] held = {0};
            require(ble.discovering(), label + ": check did not start");
            for (ScooterCommands.Feature f : ScooterCommands.catalog()) {
                CapabilityResolver.Decision d = ble.decision(f.key);
                if (f.key.equals("lock")) require(d.reason != CapabilityResolver.Reason.DISCOVERY_ACTIVE, label + ": lock held by the check");
                // A control that is otherwise available is held by the check; one already refused (e.g. a region rule) keeps its reason.
                else { require(!d.available(), label + ": " + f.key + " available during the check: " + d); if (d.reason == CapabilityResolver.Reason.DISCOVERY_ACTIVE) held[0]++; }
            }
            require(pid.equals("39991") || held[0] > 0, label + ": no control reports the check");
            require(!ble.set("display_units", 0) && !ble.set("display_units", 1), label + ": a setting was admitted during the check");
            require(ble.decision("clock_sync").reason == CapabilityResolver.Reason.DISCOVERY_ACTIVE || ble.decision("clock_sync").support != CapabilityResolver.Support.KNOWN_ENCODING, label + ": clock not held");
        });
        settle(); screenshot(label + "-compatibility-running");
        long end = SystemClock.uptimeMillis() + 35000;
        while (SystemClock.uptimeMillis() < end) { final boolean[] running = {true}; main(() -> running[0] = activity.service().ble.discovering()); if (!running[0]) break; SystemClock.sleep(200); }
        settle();
        // Body refreshes share a 1 Hz pulse: wait for the results to be drawn.
        long drawn = SystemClock.uptimeMillis() + 4000; final boolean[] drawnResults = {false};
        do { main(() -> drawnResults[0] = byText(root(), "Preview report") != null); if (drawnResults[0]) break; SystemClock.sleep(100); } while (SystemClock.uptimeMillis() < drawn);
        main(() -> {
            BleController ble = activity.service().ble;
            require(!ble.discovering() && invoke(ble, "discovery") != null, label + ": check did not finish within 35 s");
            require(before.equals(new TreeMap<>(ble.simulationFeatures())), label + ": the check changed a simulated setting");
            String shown = texts(root());
            require(shown.contains("READS") || shown.contains("Reads"), label + ": no Reads group");
            require(shown.contains("No response") && shown.contains("bytes"), label + ": read outcomes missing");
        });
        revealText("Reports from the scooter"); screenshot(label + "-compatibility-reads");
        revealText("CONTROLS", "Controls"); screenshot(label + "-compatibility-controls");
        main(() -> { TextView preview = byText(root(), "Preview report"); require(preview != null, label + ": no Preview report"); preview.performClick(); });
        settle();
        main(() -> {
            String shown = texts(root());
            require(shown.contains("Share") && shown.contains("Save file") && shown.contains("Simulated scooter (not real evidence)"), label + ": preview incomplete");
            Object report = field(field(more(), "compatibilityPanel"), "report");
            String json = (String)invoke(report, "json");
            require(json.contains("\"simulated\": true") && !json.contains("SIMULATION") && !json.contains("\"pid\": \"\""), label + ": report content");
        });
        revealText("REPORT PREVIEW", "Report preview"); screenshot(label + "-compatibility-preview");
        revealText("Share"); screenshot(label + "-compatibility-preview-actions");
        verifyExport(pid, label);
        main(() -> {
            TextView close = byText(root(), "Close preview"); require(close != null, label + ": no Close preview"); close.performClick();
            BleController ble = activity.service().ble; Object previous = invoke(ble, "discovery"); ble.startDiscovery();
            require(ble.discovering() && invoke(ble, "discovery") != previous, label + ": check did not restart: " + ble.message() + " resumed=" + field(activity, "resumed"));
            ble.stopDiscovery();
            require(!ble.discovering(), label + ": Stop did not end the check");
        });
        settle();
        // Body refreshes share a 1 Hz pulse, so wait for the panel rather than one settle.
        final String[] afterStop = {""};
        long until = SystemClock.uptimeMillis() + 4000;
        do { main(() -> afterStop[0] = texts(root())); if (afterStop[0].contains("Interrupted")) break; SystemClock.sleep(100); } while (SystemClock.uptimeMillis() < until);
        if (!afterStop[0].contains("Interrupted")) screenshot(label + "-stop-failure");
        require(afterStop[0].contains("Interrupted"), label + ": Stop did not mark reads interrupted: " + afterStop[0].replace('\n', '|'));
        main(() -> invoke(more(), "selectSection", new Class<?>[]{String.class}, "")); settle();
        checks.add(label + ": compatibility check holds writes, ends within bounds and previews its report");
    }
    /**
     * The preview's Share and Save file paths, intercepted before any other app sees them: Share
     * hands the system chooser the summary and JSON as text/plain; Save file writes the JSON to the
     * document the owner picks (here a synthetic file in the app cache). The saved file is parsed
     * and checked for the expected sections and for the synthetic private values.
     */
    private void verifyExport(String pid, String label) throws Exception {
        final Intent[] chooser = {null}, create = {null};
        File saved = new File(getTargetContext().getCacheDir(), "synthetic-" + label + "-compat-report.json");
        require(!saved.exists() || saved.delete(), label + ": stale export");
        ActivityMonitor intercept = new ActivityMonitor() {
            @Override public ActivityResult onStartActivity(Intent intent) {
                if (Intent.ACTION_CHOOSER.equals(intent.getAction())) { chooser[0] = intent; return new ActivityResult(Activity.RESULT_CANCELED, null); }
                if (Intent.ACTION_CREATE_DOCUMENT.equals(intent.getAction())) { create[0] = intent; return new ActivityResult(Activity.RESULT_OK, new Intent().setData(Uri.fromFile(saved))); }
                return null;
            }
        };
        addMonitor(intercept);
        try {
            final String[] shownJson = {null}, shownSummary = {null};
            main(() -> {
                Object report = field(field(more(), "compatibilityPanel"), "report");
                shownJson[0] = (String)invoke(report, "json"); shownSummary[0] = (String)invoke(report, "summary");
                TextView toggle = byText(root(), "Show JSON"); require(toggle != null, label + ": no Show JSON"); toggle.performClick();
            });
            settle();
            main(() -> require(texts(root()).contains("\"format\"") && byText(root(), "Show summary") != null, label + ": JSON view not shown"));
            revealText("Show summary"); screenshot(label + "-compatibility-json");
            main(() -> { TextView share = byText(root(), "Share"); require(share != null, label + ": no Share"); share.performClick(); });
            await(() -> chooser[0] != null, label + ": Share did not open the chooser");
            Intent send = chooser[0].getParcelableExtra(Intent.EXTRA_INTENT);
            require(send != null && Intent.ACTION_SEND.equals(send.getAction()) && "text/plain".equals(send.getType())
                    && "Volt compatibility report".equals(send.getStringExtra(Intent.EXTRA_SUBJECT)), label + ": share intent " + send);
            String text = send.getStringExtra(Intent.EXTRA_TEXT);
            require((shownSummary[0] + "\n" + shownJson[0]).equals(text), label + ": shared text differs from the preview");
            requireClean(text, label + " shared text");
            settle();
            main(() -> { TextView save = byText(root(), "Save file"); require(save != null, label + ": no Save file"); save.performClick(); });
            await(() -> create[0] != null, label + ": Save file did not open the document picker");
            require("application/json".equals(create[0].getType()) && "volt-compat-report.json".equals(create[0].getStringExtra(Intent.EXTRA_TITLE)), label + ": save intent " + create[0]);
            SharedPreferences documents = prefs("pending_documents");
            await(() -> saved.length() > 0 && !documents.contains("path"), label + ": report file was not written");
            String json = new String(Files.readAllBytes(saved.toPath()), StandardCharsets.UTF_8);
            require(json.equals(shownJson[0]), label + ": saved file differs from the previewed JSON");
            requireClean(json, label + " saved file");
            File dir = new File(getTargetContext().getExternalFilesDir(null), "compatibility-validation"); require(dir.isDirectory() || dir.mkdirs(), "Export directory unavailable");
            String variant = arguments.getString("variant", "compat").replaceAll("[^a-zA-Z0-9_-]", "");
            Files.write(new File(dir, variant + "-" + label + "-volt-compat-report.json").toPath(), json.getBytes(StandardCharsets.UTF_8));
            Files.write(new File(dir, variant + "-" + label + "-shared.txt").toPath(), text.getBytes(StandardCharsets.UTF_8));
            verifyReportSections(new JSONObject(json), pid, label);
            require(saved.delete(), label + ": synthetic export not removed");
            main(() -> { TextView toggle = byText(root(), "Show summary"); if (toggle != null) toggle.performClick(); });
            settle();
        } finally { removeMonitor(intercept); }
        checks.add(label + ": Share and Save file carry exactly the previewed report; the saved JSON has every section and no serial, MAC, owner key, name or route");
    }
    /** The synthetic private values, case-insensitively, also without separators and as hex. */
    private void requireClean(String text, String where) {
        String lower = text.toLowerCase(Locale.ROOT), compact = lower.replaceAll("[^0-9a-z]", "");
        ArrayList<String> forbidden = new ArrayList<>(Arrays.asList(FIXTURE_NAME, "synthetic", FIXTURE_SERIAL, FIXTURE_SERIAL.replace("-", ""), "2538-",
                FIXTURE_MAC, FIXTURE_MAC.replace(":", ""), "563412eeffc0", FIXTURE_IDENTITY, "owner_identity", "latitude", "longitude", "scooter_name"));
        // Private keys ("serial" is allowed only as a value: pid_source and region_source; the summary names "routes" in its exclusion note).
        require(!java.util.regex.Pattern.compile("\"(mac|address|serial|identity|pin|lat|lon|lng|points|route[a-z_]*|track|profile[a-z_]*|name_default|location)\"\\s*:").matcher(lower).find(), where + " has a private key");
        StringBuilder nameHex = new StringBuilder(); for (byte b : FIXTURE_NAME.getBytes(StandardCharsets.UTF_8)) nameHex.append(String.format(Locale.ROOT, "%02x", b & 255));
        forbidden.add(nameHex.toString());
        for (String value : forbidden) {
            String v = value.toLowerCase(Locale.ROOT);
            require(!lower.contains(v), where + " contains " + value);
            String c = v.replaceAll("[^0-9a-z]", "");
            if (c.length() >= 8) require(!compact.contains(c), where + " contains " + value + " without separators");
        }
    }
    private void verifyReportSections(JSONObject report, String pid, String label) throws Exception {
        Set<String> allowed = new HashSet<>(Arrays.asList("format", "report_id", "app_version", "registry_version", "simulated", "device", "reads", "passive", "status_length", "fields", "decisions", "tests",
                "pid", "pid_prefix", "pid_source", "pid_digits", "pid_conflict", "region", "region_source", "sku", "firmware", "meter", "controller", "bms", "screen", "uwb",
                "transport", "auth", "layer", "role", "cmd", "class", "outcome", "status", "length", "attempts", "first_ms", "late", "seen", "offset", "name", "present", "value",
                "feature", "support", "available", "reason", "evidence", "variant", "baseline", "target", "packet_evidence", "outcomes", "elapsed_ms", "step_values", "owner_observation", "effect", "restoration"));
        requireKeys(report, allowed, label);
        require("volt-compat-report-v1".equals(report.getString("format")) && report.getString("report_id").matches("[0-9a-f]{16}")
                && !report.getString("app_version").isEmpty() && report.getInt("registry_version") > 0 && report.getBoolean("simulated"), label + ": report header");
        JSONObject device = report.getJSONObject("device");
        String layer = device.getString("layer");
        require(pid.isEmpty() ? layer.equals("ut5_max") : pid.equals("24431") ? layer.equals("oem_group") : !layer.equals("ut5_max") && !layer.equals("oem_group"), label + ": layer " + layer);
        if (!pid.isEmpty()) require(pid.equals(device.optString("pid")) && "advertised".equals(device.getString("pid_source")) && device.getInt("pid_digits") == pid.length(), label + ": full advertised PID " + device);
        JSONArray reads = report.getJSONArray("reads");
        Set<String> cmds = new HashSet<>(), expected = new HashSet<>(Arrays.asList("0x70", "0x71", "0x72", "0x73", "0x74", "0x75", "0x76", "0x79", "0x7B", "0x7b"));
        boolean answered = false, silent = false;
        for (int i = 0; i < reads.length(); i++) {
            JSONObject r = reads.getJSONObject(i); String cmd = r.getString("cmd"); cmds.add(cmd.toLowerCase(Locale.ROOT));
            require(expected.contains(cmd) && r.getInt("attempts") >= 1 && r.getInt("attempts") <= 3, label + ": read " + r);
            answered |= r.getString("outcome").equals("read_supported"); silent |= r.getString("outcome").equals("no_response");
        }
        require(cmds.size() == 9 && !cmds.contains("0x7a") && answered && silent, label + ": reads " + cmds);
        require(report.getJSONArray("passive").length() == 3 && report.getJSONArray("decisions").length() > 0, label + ": passive/decisions sections");
        // The simulator builds no 0x70 frame, so a simulated report has no fields section.
        JSONArray fields = report.optJSONArray("fields"); if (fields == null) fields = new JSONArray();
        for (int i = 0; i < fields.length(); i++) { int offset = fields.getJSONObject(i).getInt("offset"); require(offset < 27 || offset > 30, label + ": time-of-day offset " + offset + " exported"); require(offset != 41 || !fields.getJSONObject(i).has("value"), label + ": raw offset 41 exported"); }
    }
    private void requireKeys(Object node, Set<String> allowed, String label) throws Exception {
        if (node instanceof JSONObject) { JSONObject o = (JSONObject)node; for (Iterator<String> keys = o.keys(); keys.hasNext();) { String key = keys.next(); require(allowed.contains(key), label + ": unexpected report key " + key); requireKeys(o.get(key), allowed, label); } }
        else if (node instanceof JSONArray) { JSONArray a = (JSONArray)node; for (int i = 0; i < a.length(); i++) requireKeys(a.get(i), allowed, label); }
    }

    /**
     * The UT5 MAX keeps the 1.3.1 read schedule: the after-authentication bundle and the battery
     * poll come from the connection's ReadPolicy (not the simulator), and stay the 1.3.1 arrays even
     * after silences, a rejection, a finished check and a disagreeing advertised model ID (D1).
     */
    private void verifyUt5ReadSchedule() {
        main(() -> {
            BleController ble = activity.service().ble;
            require(!ble.simulating(), "Schedule check needs the simulator off");
            ScooterProfiles.Profile profile = ble.profile(); String advertised = profile.advertisedPid; long advertisedWall = profile.advertisedPidWall;
            try {
                for (String pid : new String[]{"", "24431"}) {
                    profile.advertisedPid = pid; profile.advertisedPidWall = pid.isEmpty() ? 0 : System.currentTimeMillis();
                    setField(ble, "readPolicy", null);
                    Object policy = invoke(ble, "policy");
                    require(Boolean.TRUE.equals(invoke(policy, "isLegacy")), "UT5 MAX read policy is adaptive (advertised " + pid + ")");
                    long t = SystemClock.elapsedRealtime();
                    for (int round = 0; round < 3; round++) {
                        requireSchedule(policy, "round " + round + " advertised " + pid);
                        for (int cmd : new int[]{0x71, 0x72, 0x75, 0x76, 0x79, 0x7b}) invoke(policy, "requested", new Class<?>[]{int.class, long.class}, cmd, t);
                        t += 3000; invoke(policy, "tick", new Class<?>[]{long.class}, t);
                        invoke(policy, "responded", new Class<?>[]{int.class, int.class, int.class, long.class}, 0x79, 3, 0, t);
                        invoke(policy, "unanswered", new Class<?>[]{int.class}, 0x75);
                        policy = invoke(ble, "policy");
                    }
                    requireSchedule(policy, "after silences and a rejection, advertised " + pid);
                }
            } finally { profile.advertisedPid = advertised; profile.advertisedPidWall = advertisedWall; setField(ble, "readPolicy", null); }
            require(Arrays.equals(ScooterCommands.diagnosticReads(), UT5_AFTER_AUTH_131), "ScooterCommands.diagnosticReads changed from 1.3.1");
        });
        checks.add("UT5 MAX connection read policy: after-auth bundle 70 71 72 73 74 75 76 79 7B and battery poll 72 79, as in 1.3.1, after silences, a rejection and a disagreeing advertised model ID");
    }
    private void requireSchedule(Object policy, String when) {
        require(Arrays.equals((int[])invoke(policy, "afterAuth"), UT5_AFTER_AUTH_131), "UT5 MAX after-auth reads differ from 1.3.1 (" + when + "): " + Arrays.toString((int[])invoke(policy, "afterAuth")));
        require(Arrays.equals((int[])invoke(policy, "periodicBattery"), UT5_BATTERY_131), "UT5 MAX battery poll differs from 1.3.1 (" + when + ")");
        for (int cmd : UT5_AFTER_AUTH_131) require(Boolean.TRUE.equals(invoke(policy, "allowed", new Class<?>[]{int.class}, cmd)), "UT5 MAX read " + Integer.toHexString(cmd) + " suppressed (" + when + ")");
        require(!Boolean.TRUE.equals(invoke(policy, "allowed", new Class<?>[]{int.class}, 0x7a)), "0x7A allowed as a read");
    }
    private void setField(Object target, String name, Object value) { try { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target, value); } catch (Exception e) { throw new IllegalStateException("Field " + name, e); } }

    /** Scrolls the first shown TextView with one of these texts to the top of the screen. */
    private void revealText(String... options) {
        main(() -> {
            TextView found = null; for (String text : options) if (found == null) found = byText(root(), text);
            require(found != null, "Missing " + Arrays.toString(options));
            View v = found; android.widget.ScrollView scroll = null;
            for (android.view.ViewParent p = v.getParent(); p != null; p = p.getParent()) if (p instanceof android.widget.ScrollView) { scroll = (android.widget.ScrollView)p; break; }
            if (scroll == null) { found.requestRectangleOnScreen(new Rect(0, 0, found.getWidth(), found.getHeight()), true); return; }
            int[] at = new int[2], top = new int[2]; found.getLocationInWindow(at); scroll.getLocationInWindow(top);
            scroll.scrollTo(0, Math.max(0, scroll.getScrollY() + at[1] - top[1] - found.getHeight()));
        }); settle();
    }
    private TextView byText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView)view).getText()) && view.isShown()) return (TextView)view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) { TextView found = byText(((ViewGroup)view).getChildAt(i), text); if (found != null) return found; }
        return null;
    }

    /** Identify disconnects; a recording must refuse it before any Bluetooth work. */
    private void verifyIdentifyWhileRecording() throws Exception {
        String[] id = {null};
        main(() -> {
            ScooterService service = activity.service();
            try { id[0] = service.rides.start(service.profiles.active().id, "Synthetic Identify refusal", System.currentTimeMillis(), SystemClock.elapsedRealtime()); }
            catch (Exception error) { throw new IllegalStateException(error); }
            invoke(service, "applyWorkPolicy", new Class<?>[]{boolean.class}, false);
            require(service.isRecording() && Boolean.TRUE.equals(field(service.ble, "recording")), "Fixture recording did not start");
            service.ble.identifyModel();
            require(service.ble.message().equals("Stop recording first"), "Identify while recording: " + service.ble.message());
            require(!Boolean.TRUE.equals(field(service.ble, "scanning")) && !Boolean.TRUE.equals(field(service.ble, "identifying")), "Identify started a scan while recording");
            activity.redraw();
        }); settle();
        main(() -> { invoke(activity, "select", new Class<?>[]{String.class}, "controls"); }); settle();
        main(() -> require(((View)field(field(activity, "screen"), "readOnlyBanner")).getVisibility() != View.VISIBLE, "Limited banner shown without a connection")); 
        screenshot("recording-scooter");
        main(() -> activity.service().stopRide()); settle();
        main(() -> require(!activity.service().isRecording(), "Fixture recording did not stop"));
        checks.add("Identify is refused while recording (\"Stop recording first\") and in simulation; no scan starts");
    }

    /** C1: an owner file without a name imports as the default "Imported scooter". */
    private void importName() throws Exception {
        // An existing user importing a second scooter; first-run setup uses the same import code.
        main(() -> OnboardingScreen.markDone(getTargetContext()));
        launch();
        for (String text : new String[]{"{\"owner_identity\":\"0123456789AB\",\"role\":0,\"scooter_serial\":\"X2538SYNTHETIC000\"}",
                "{\"owner_identity\":\"0123456789AC\",\"role\":0,\"name\":\"  \",\"scooter_serial\":\"X2443SYNTHETIC000\"}"}) {
            // With Bluetooth off, the previous import's connect step asks the system to enable it; dismiss that.
            if (!Boolean.TRUE.equals(field(activity, "resumed"))) getUiAutomation().executeShellCommand("input keyevent 4").close();
            await(() -> Boolean.TRUE.equals(field(activity, "resumed")), "App did not return to the foreground");
            int before = activity.service().profiles.all().size();
            main(() -> { invoke(activity, "stageOwnerImport", new Class<?>[]{String.class}, text); invoke(activity, "maybeApplyOwnerImport"); }); settle();
            await(() -> activity.service().profiles.all().size() == before + 1, "Owner import did not create a profile: resumed=" + field(activity, "resumed")
                    + " pending=" + new File(getTargetContext().getFilesDir(), "pending-owner-import.json").isFile() + " applying=" + field(activity, "applyingOwnerImport")
                    + " recording=" + activity.service().isRecording() + " message=" + activity.service().ble.message());
            main(() -> {
                ScooterProfiles.Profile profile = activity.service().profiles.active();
                require(profile.name.equals("Imported scooter") && profile.nameDefault, "Imported profile name " + profile.name + " default=" + profile.nameDefault);
                ScooterProfiles reloaded = new ScooterProfiles(getTargetContext());
                ScooterProfiles.Profile saved = null; for (ScooterProfiles.Profile p : reloaded.all()) if (p.id.equals(profile.id)) saved = p;
                require(saved != null && saved.nameDefault && saved.name.equals("Imported scooter"), "name_default did not persist");
                // The connect-time rule (BleController.remember): the model name, else "NAVEE scooter".
                String model = ModelRegistry.modelName(saved.savedDescriptor());
                require(model.equals(saved.serial.startsWith("X2538") ? "UT5 MAX" : ""), "Model name for " + saved.serial + ": " + model);
            });
            checks.add("Owner file " + (text.contains("\"name\"") ? "with a blank name" : "without a name") + " imports as Imported scooter with nameDefault; model name rule gives "
                    + (text.contains("X2538") ? "UT5 MAX" : "NAVEE scooter"));
        }
        if (!Boolean.TRUE.equals(field(activity, "resumed"))) getUiAutomation().executeShellCommand("input keyevent 4").close();
        await(() -> Boolean.TRUE.equals(field(activity, "resumed")), "App did not return to the foreground");
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "more")); settle();
        main(() -> invoke(more(), "selectSection", new Class<?>[]{String.class}, "Scooters")); settle();
        main(() -> require(texts(root()).contains("Imported scooter"), "More does not show Imported scooter")); 
        screenshot("import-scooters");
    }

    private String texts(View view) {
        StringBuilder out = new StringBuilder();
        if (view instanceof TextView && view.isShown()) out.append(((TextView)view).getText()).append('\n');
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) out.append(texts(((ViewGroup)view).getChildAt(i)));
        return out.toString();
    }
    private MoreScreen more() { Object screen = field(activity, "screen"); require(screen instanceof MoreScreen, "More screen is not active"); return (MoreScreen)screen; }
    private void reveal(String tag) {
        main(() -> { View view = root().findViewWithTag(tag); require(view != null, "Missing " + tag); view.requestRectangleOnScreen(new Rect(0, 0, view.getWidth(), view.getHeight()), true); }); settle();
    }

    private List<View> tagged(View view, String... tags) {
        ArrayList<View> found = new ArrayList<>();
        for (String tag : tags) if (tag.equals(view.getTag())) found.add(view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) found.addAll(tagged(((ViewGroup)view).getChildAt(i), tags));
        return found;
    }
    /** Saved layouts and slots: switching the simulated model must never rewrite them. */
    private Map<String, String> layoutSnapshot() {
        TreeMap<String, String> layouts = new TreeMap<>();
        for (Map.Entry<String, ?> e : settings().getAll().entrySet()) {
            String key = e.getKey().toLowerCase(Locale.ROOT);
            if (key.contains("dashboard") || key.contains("layout") || key.contains("slot") || key.contains("quick")) layouts.put(e.getKey(), String.valueOf(e.getValue()));
        }
        return layouts;
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
        String variant = arguments.getString("variant", "compat").replaceAll("[^a-zA-Z0-9_-]", ""); File file = new File(dir, variant + "-" + label + ".png");
        android.graphics.Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "Screenshot unavailable");
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); } finally { bitmap.recycle(); }
        screenshots.add(file.getAbsolutePath());
    }
}
