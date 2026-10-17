package local.navee.offlinelock.tests;

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
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import local.navee.offlinelock.BleController;
import local.navee.offlinelock.CapabilityResolver;
import local.navee.offlinelock.FeatureVariant;
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
 */
public final class CompatibilityGatingValidation extends Instrumentation {
    private static final String[][] MODELS = {{"", "ut5"}, {"24431", "mapped"}, {"39991", "unknown"}};
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
            }
            checks.add("Scooter rows show only controls with a known encoding for the simulated model; the UT5 MAX keeps every row");
            checks.add("Limited-model banner, clock row and lock switch follow the resolver; lock and unlock work on every standard model");
            checks.add("Unmapped Quick access tiles show a muted dash, say \"not on this model\" and stay in the saved layout");
            main(() -> activity.service().ble.setSimulationModel("")); settle();
            require(layouts.equals(layoutSnapshot()), "Saved dashboard layouts changed while switching simulated models");
            main(() -> require(activity.service().setSimulation(false), "Simulation did not stop")); settle();
            verifyIdentifyWhileRecording();
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
            ScooterProfiles.Profile profile = profiles.create("Synthetic compatibility fixture", "000000000000", 0);
            profile.serial = "X2538-SYNTHETIC"; profile.lastConnected = System.currentTimeMillis() - 60000; profiles.update(profile);
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
                CapabilityResolver.Decision decision = ble.decision(feature.key);
                // A Lab control on the UT5 MAX has a row only where another model maps it normally (not Lab).
                boolean ordinary = decision.support == CapabilityResolver.Support.KNOWN_ENCODING && decision.variant != null && !decision.variant.lab
                        && decision.variant.decode != FeatureVariant.Decode.UT5_FEATURE;
                boolean known = feature.key.equals("range_algorithm") || (feature.experimental ? ordinary : ble.controlOffered(feature.key));
                if (!feature.experimental) require(known == (feature.key.equals("range_algorithm") || decision.support == CapabilityResolver.Support.KNOWN_ENCODING), label + ": simulated models always have identity evidence");
                require((card.getVisibility() == View.VISIBLE) == (known && onPage), label + ": row " + feature.key + " visibility does not follow its decision");
                if (ut5) require(known != feature.experimental, "UT5 MAX row set changed: " + feature.key);
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
            // The UT5 MAX and the mapped S-group model (Bluetooth lock screen) have the PIN; the unknown model does not.
            require(owner == !pid.equals("39991"), label + ": PIN owner rows ready=" + owner + " for " + pin);
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
                boolean ordinary = decision.support == CapabilityResolver.Support.KNOWN_ENCODING && decision.variant != null && !decision.variant.lab
                        && decision.variant.decode != FeatureVariant.Decode.UT5_FEATURE;
                if (ordinary) {
                    require(!enabled && "In Scooter settings for this model".equals(invoke(more(), "labReason", new Class<?>[]{ScooterCommands.Feature.class}, feature)), label + ": OEM-mapped " + feature.key + " stays out of the Lab");
                } else if (!(decision.available() || decision.blockedByRide())) {
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
