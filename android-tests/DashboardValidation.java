package local.navee.offlinelock.tests;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.InputDevice;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.NumberPicker;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import local.navee.offlinelock.BleController;
import local.navee.offlinelock.DashboardConfig;
import local.navee.offlinelock.DashboardLayout;
import local.navee.offlinelock.DashboardScreen;
import local.navee.offlinelock.DisplayTime;
import local.navee.offlinelock.MainActivity;
import local.navee.offlinelock.ScooterProfiles;
import local.navee.offlinelock.ScooterService;
import local.navee.offlinelock.Telemetry;
import local.navee.offlinelock.TelemetryHistory;

/**
 * Dashboard interaction checks for a fresh emulator only. Synthetic cached readings are
 * explicitly identified; Bluetooth stays off and no recording, scooter write or tile request occurs.
 * Run separately at 360, 411 and 701 dp; the runner also checks 140% app text in a light theme.
 */
public final class DashboardValidation extends Instrumentation {
    private static final String[] PREFS = {"app_settings", "profiles", "scooter", "pending_documents"};
    private static final String[] DESTINATIONS = {"Home", "Scooter", "Live Stats", "Rides", "More"};
    private static final String[] DEFAULT_TYPES = {"lockbar", "overview", "tiles", "shortcuts", "ride"};
    /** Lock and Lock bar are mutually exclusive; the default's Lock bar keeps the palette from adding Lock. */
    private static final int HOME_TYPE_COUNT = DashboardConfig.TYPES.length - 1;
    private static final String FIXTURE_NAME = "Synthetic dashboard fixture";
    private static final String RENAMED = "Synthetic renamed scooter";
    private static final String HOME_RENAMED = "Synthetic Home rename";
    private final List<String> checks = new ArrayList<>(), screenshots = new ArrayList<>();
    private Bundle arguments;
    private MainActivity activity;
    private String profileId, metricId, noteId;
    private boolean admitted;
    private TelemetryHistory fixtureHistory;

    @Override public void onCreate(Bundle args) { arguments = args == null ? new Bundle() : args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            requireFreshEmulator(); admitted = true;
            require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                    .putString("theme", "dark").putString("units", "mi").commit(), "Could not configure isolated emulator");
            verifyCapturedDefaults();
            verifyScooterStatsMigration();
            verifyClockFormats();
            seedSyntheticProfileAndHistory(); launch();
            for (String destination : DESTINATIONS) { clickDescription(destination); assertNavigationFits(); assertNoScooterWork(); }
            clickDescription("Home"); awaitDashboard();
            main(() -> require(types().equals(new HashSet<>(Arrays.asList(DEFAULT_TYPES)))
                    && config().widgets.size() == DEFAULT_TYPES.length && settings().getInt("dashboard_columns", 0) == 5,
                    "Fresh Home did not provide the default dashboard widgets"));
            assertGrid(); scrollHomeTop(); screenshot("default-dark");
            main(() -> {
                for (int i = 0; i < DEFAULT_TYPES.length; i++)
                    require(config().widgets.get(i).type.equals(DEFAULT_TYPES[i]), "Fresh Home default order changed at " + i);
            });
            check("Five-widget Home default (lock bar, battery overview, reading tiles, setting tiles, recorder) matches its 1–6-column layouts, survives reload and resets only the selected profile; navigation and cards fit");

            if ("layout-slots".equals(arguments.getString("mode"))) {
                verifySavedLayoutSlots();
                result.putString("result", "PASS");
                result.putStringArrayList("checks", new ArrayList<>(checks));
                result.putStringArrayList("screenshots", new ArrayList<>(screenshots));
                result.putInt("check_count", checks.size());
                result.putInt("width_dp", getTargetContext().getResources().getConfiguration().screenWidthDp);
                result.putInt("scooter_mutations", 0);
                cleanup(); finish(0, result); return;
            }

            await(() -> activity.service().displayReadings().get("battery_percent").number != null, 5000,
                    "Persisted synthetic readings did not load");
            verifyHomeWidgetsOffline();
            // The photo card is no longer in the default; add it explicitly so its offline checks keep running.
            seedScooterCard();
            assertCachedReadings();
            verifyCompactConnection();
            verifyDisconnectedScooterPhoto();
            clickDescription("Scooter"); clickText(root(), "Scooter name");
            AlertDialog rename = (AlertDialog)field(activity, "controlDialog");
            main(() -> first(rename.getWindow().getDecorView(), EditText.class).setText(RENAMED));
            click(rename.getButton(AlertDialog.BUTTON_POSITIVE));
            main(() -> require(RENAMED.equals(new ScooterProfiles(getTargetContext()).active().name), "Scooter name was not saved"));
            clickDescription("Home"); awaitDashboard();
            main(() -> require(RENAMED.contentEquals(((TextView)field(dashboard(), "profileName")).getText()),
                    "Dashboard scooter card did not update its name"));
            assertConfigurationHidden();
            enterEditPreservingContent();
            clickDescription("Rename scooter");
            AlertDialog homeRename = (AlertDialog)field(activity, "controlDialog");
            main(() -> first(homeRename.getWindow().getDecorView(), EditText.class).setText(HOME_RENAMED));
            click(homeRename.getButton(AlertDialog.BUTTON_POSITIVE));
            await(() -> HOME_RENAMED.contentEquals(((TextView)field(dashboard(), "profileName")).getText()),
                    3000, "The scooter card rename did not update Home");
            main(() -> require(HOME_RENAMED.equals(new ScooterProfiles(getTargetContext()).active().name),
                    "The scooter card rename was not saved"));
            check("Cached readings stay stale while a known UT5 MAX keeps its photo offline; settings and the edit-only Home pencil preserve its name");

            require(dashboard().isEditing(), "Renaming the scooter unexpectedly left dashboard editing");
            assertEditInputsInert(); screenshot("edit-dark");
            Set<String> existing = types();
            require(existing.contains("lockbar") && !existing.contains("lock"), "Palette fixture expects the default Lock bar");
            for (int i = 0; i < DashboardConfig.TYPES.length; i++) {
                String type = DashboardConfig.TYPES[i];
                if (existing.contains(type)) continue;
                clickText(root(), "Add widget");
                View row = dialogRoot().findViewWithTag("add-widget:" + type);
                require(row != null, "Palette lacks " + type);
                if (type.equals("lock")) {
                    verifySingleLockControlPalette(row);
                    continue;
                }
                click(row); closeDialog();
                await(() -> tileOrNull(type) != null && ((View)tile(type)).getWidth() > 0, 4000, "Added widget did not lay out: " + type);
                assertGrid(); assertEditInputsInert();
            }
            Set<String> homeTypes = new HashSet<>(Arrays.asList(DashboardConfig.TYPES)); homeTypes.remove("lock");
            main(() -> require(types().equals(homeTypes) && config().widgets.size() == HOME_TYPE_COUNT,
                    "Palette did not construct every other widget type exactly once"));
            check("All " + HOME_TYPE_COUNT + " widget types beside the Lock bar can be added; Lock shows On Home and is refused; Edit disables widget actions and performs no scooter work");
            clickText(root(), "Done"); await(() -> !dashboard().isEditing(), 3000, "Done did not leave Edit for content sizing checks");
            verifyResponsiveCards(); assertConfigurationHidden();
            enterEditPreservingContent();
            check("Every widget keeps complete readable values at its default and minimum sizes; scooter photo and statistics share a usable row");

            metricId = widget("metric").id;
            openOptions(metricId); clickText(dialogRoot(), "Parameter");
            main(() -> first(dialogRoot(), EditText.class).setText("battery_percent"));
            clickText(dialogRoot(), metricLabel("battery_percent"));
            openOptions(metricId);
            main(() -> first(dialogRoot(), EditText.class).setText("Synthetic charge"));
            closeDialog();
            await(() -> "battery_percent".equals(widgetById(metricId).metric), 3000, "Parameter choice was not applied");
            main(() -> {
                Object tile = tileById(metricId); TextView amount = (TextView)field(tile, "big");
                require(amount.getText().toString().contains("73"), "Big reading did not render the cached parameter");
                require(amount.getCurrentTextColor() == activity.ui().muted, "Cached big reading is presented as live");
                require(((TextView)field(tile, "footer")).getText().toString().contains("Last seen"), "Cached widget lacks its stale-reading label");
            });
            setSize(metricId, 1, 4); setPosition(metricId, 1, 1);
            main(() -> require(cell(metricId).x == 0 && cell(metricId).y == 0 && cell(metricId).w == 1 && cell(metricId).h == 4,
                    "Size/Move options did not retain the selected rectangle"));
            assertGrid(); verifyEditAffordances(metricId); verifyOptionsContrast(metricId);
            resizeByOneRow(metricId); assertGrid(); assertEditInputsInert();
            scrollHomeTop(); screenshot("customized-edit-dark");
            check("A single options menu, separate move grip and visible corner handle provide distinct actions; resize previews and saves the new size");

            verifyCancelledDrag(metricId); directDragToNextCell(metricId); verifyLongPressCancel(metricId);
            assertGrid(); assertEditInputsInert();
            scrollHomeTop(); screenshot("native-drag-dark");
            check("Direct touchscreen drag previews the full card and reflows neighbors before release; drop saves and cancellation restores every position");

            verifyGaugeUnits(); verifyTwoMinuteChart(); assertNoScooterWork();
            check("Gauge limits round-trip between mph and km/h; the two-minute live-chart window persists without changing dashboard placement");

            verifyCapacityExperiment(); assertGrid(); assertNoScooterWork();
            reveal((View)field(tile("capacity"), "capacityValue")); screenshot("capacity-no-data-dark");
            check("Capacity experiment reports insufficient ride data; its title, time window and advertised Wh can be configured and saved");

            verifyChargingDisplay(); verifyLiveFooters(); assertNoScooterWork();
            check("Battery charging details reject stale inputs and honor the cutoff; live cards omit redundant live-status footers");

            noteId = widget("note").id;
            openOptions(noteId); clickText(dialogRoot(), "Note");
            StringBuilder note = new StringBuilder("Synthetic scrolling fixture. ");
            for (int i = 0; i < 22; i++) note.append("Reminder ").append(i + 1).append(": inspect tires before riding.\n");
            main(() -> first(dialogRoot(), EditText.class).setText(note.toString()));
            click(dialog().getButton(AlertDialog.BUTTON_POSITIVE));
            require(widgetById(noteId).note.equals(note.toString()) && widgetById(noteId).title.equals("Note"),
                    "Note editor changed the wrong widget field");
            setSize(noteId, 1, 3);
            clickText(root(), "Done"); await(() -> !dashboard().isEditing(), 3000, "Done did not leave Edit");
            main(() -> {
                require(((View)field(activity, "connectionAction")).isEnabled(), "Connection control did not recover after Done");
            });
            assertConfigurationHidden();
            assertInnerScrolling(); assertGrid(); assertNoScooterWork();
            check("Done restores ordinary actions; a swipe on long widget text scrolls its contents without moving the dashboard");

            DashboardLayout.Cell before = cell(metricId).copy();
            recreateActivity();
            main(() -> {
                DashboardConfig.Widget restored = widgetById(metricId); DashboardLayout.Cell position = cell(metricId);
                require(restored.title.equals("Synthetic charge") && restored.metric.equals("battery_percent"), "Widget configuration did not survive recreation");
                require(position.x == before.x && position.y == before.y && position.w == before.w && position.h == before.h,
                        "Widget placement did not survive recreation");
                require(types().size() == HOME_TYPE_COUNT && !dashboard().isEditing(), "Dashboard mode or widget list did not survive recreation");
                require(HOME_RENAMED.equals(activity.service().profiles.active().name), "Scooter rename was lost on recreation");
                require(widget("capacity").advertisedWh == 800d && widget("capacity").windowMs == 30 * 86400000L
                        && widget("capacity").title.equals("Synthetic capacity experiment"), "Capacity widget settings were lost on recreation");
            });
            assertCachedReadings(); assertGrid();
            check("Recreating the activity preserves widget choices, placement, scooter name and stale readings");

            main(() -> settings().edit().putString("theme", "light").putFloat("text_scale", 1.4f).apply());
            await(() -> !activity.ui().dark && activity.ui().textScale > 1.3f && dashboard().view().getWidth() > 0, 5000,
                    "Large light theme did not apply");
            assertGrid(); assertNavigationFits(); scrollHomeTop(); screenshot("customized-large-light");
            verifyResponsiveCards(); assertConfigurationHidden();
            assertInnerScrolling(); screenshot("scrolling-note-large-light");
            enterEditPreservingContent(); assertEditInputsInert(); assertGrid();
            verifyEditAffordances(metricId); verifyOptionsContrast(metricId); scrollHomeTop(); screenshot("edit-large-light");
            main(() -> invoke(activity, "navigateBack"));
            await(() -> !dashboard().isEditing(), 3000, "Back did not leave dashboard Edit");
            assertNoScooterWork();
            check("140% text and light theme retain usable handles, readable options, inner scrolling and navigation; Back exits Edit");

            restartServiceAndActivity();
            await(() -> activity.service().displayReadings().get("battery_percent").number != null, 5000,
                    "Cached readings did not return after a service restart");
            assertCachedReadings(); assertGrid(); assertNoScooterWork();
            main(() -> require(widgetById(metricId).metric.equals("battery_percent") && types().size() == HOME_TYPE_COUNT,
                    "Dashboard customization did not survive a cold activity/service restart"));
            check("A new service reloads cached numeric and nonnumeric readings without presenting them as live or starting Bluetooth work");

            result.putString("result", "PASS"); result.putStringArrayList("checks", new ArrayList<>(checks));
            result.putStringArrayList("screenshots", new ArrayList<>(screenshots)); result.putInt("check_count", checks.size());
            result.putInt("width_dp", getTargetContext().getResources().getConfiguration().screenWidthDp);
            result.putInt("widget_types", DashboardConfig.TYPES.length); result.putInt("scooter_mutations", 0);
            result.putBoolean("synthetic_readings", true); result.putBoolean("hardware_sensor_claims", false);
            result.putBoolean("native_drag_gesture_tested", true); cleanup(); finish(0, result);
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", error.getClass().getSimpleName() + ": " + error.getMessage());
            result.putStringArrayList("completed_checks", new ArrayList<>(checks));
            try { if (admitted && activity != null && !activity.isDestroyed()) screenshot("failure"); } catch (Throwable ignored) { }
            result.putStringArrayList("screenshots", new ArrayList<>(screenshots));
            try { cleanup(); } catch (Throwable failure) { result.putString("cleanup_error", failure.toString()); }
            finish(1, result);
        }
    }

    private void verifySavedLayoutSlots() throws Exception {
        enterEditPreservingContent();
        clickText(root(), "Layout");
        for (int i = 0; i < 2; i++) {
            require(!dialogRoot().findViewWithTag("dashboard-slot-restore-" + i).isEnabled(),
                    "An empty layout slot permits restore");
            require(dialogRoot().findViewWithTag("dashboard-slot-save-" + i).isEnabled(),
                    "An empty layout slot cannot be saved");
        }
        require(dialogRoot().findViewWithTag("dashboard-slot-restore-2").isEnabled()
                        && dialogRoot().findViewWithTag("dashboard-slot-save-2").isEnabled()
                        && config().slot(2) != null && config().slot(2).name.equals("All widgets")
                        && config().slot(2).widgetCount == DashboardConfig.TYPES.length,
                "Seeded example is not available beside the two empty slots");
        String savedExample = settings().getString("dashboard_slot_v1_" + profileId + "_2", "");
        screenshot("slots-example-dark");
        click(findDescription(dialogRoot(), "Close dashboard layout"));
        main(() -> {
            DashboardConfig.Widget custom = new DashboardConfig.Widget("note");
            custom.title = "Saved fixture"; custom.note = "Retain my widget options";
            for (int c = 1; c <= 4; c++)
                custom.layouts.put(c, new DashboardLayout.Cell(custom.id, 0, 30, c, 3));
            config().widgets.add(custom); config().save(); invoke(dashboard(), "render");
        });
        waitForIdleSync();
        main(() -> config().save());
        String expected = settings().getString("dashboard_v1_" + profileId, "");
        clickText(root(), "Layout");
        click(dialogRoot().findViewWithTag("dashboard-slot-save-0"));
        main(() -> first(dialogRoot(), EditText.class).setText("My commute"));
        click(dialog().getButton(AlertDialog.BUTTON_POSITIVE));
        require("My commute".equals(config().slot(0).name), "Saved slot name was not retained");
        require(dialogRoot().findViewWithTag("dashboard-slot-restore-0").isEnabled(),
                "Saved slot is not restorable in the sheet");
        click(dialogRoot().findViewWithTag("dashboard-slot-save-0"));
        main(() -> first(dialogRoot(), EditText.class).setText("Cancelled replacement"));
        click(dialog().getButton(AlertDialog.BUTTON_NEGATIVE));
        require("My commute".equals(config().slot(0).name), "Cancel replaced a saved slot");
        check("Two slots start empty beside the restorable All widgets example; save enables restore and cancel leaves an existing slot intact");

        clickText(dialogRoot(), "Restore default widgets");
        click(dialog().getButton(AlertDialog.BUTTON_NEGATIVE));
        require(expected.equals(settings().getString("dashboard_v1_" + profileId, "")),
                "Cancelled default reset changed the dashboard");
        clickText(root(), "Layout");
        clickText(dialogRoot(), "Restore default widgets");
        click(dialog().getButton(AlertDialog.BUTTON_POSITIVE));
        require(config().widgets.size() == DEFAULT_TYPES.length && config().slot(0).widgetCount == DEFAULT_TYPES.length + 1,
                "Default reset overwrote a saved slot");
        clickText(root(), "Layout");
        click(dialogRoot().findViewWithTag("dashboard-slot-restore-0"));
        click(dialog().getButton(AlertDialog.BUTTON_NEGATIVE));
        require(config().widgets.size() == DEFAULT_TYPES.length, "Cancel restored a slot");
        click(dialogRoot().findViewWithTag("dashboard-slot-restore-0"));
        click(dialog().getButton(AlertDialog.BUTTON_POSITIVE));
        require(config().widgets.size() == DEFAULT_TYPES.length + 1, "Restore did not recreate saved widgets");
        require(expected.equals(settings().getString("dashboard_v1_" + profileId, "")),
                "Restored widget options or paired rectangles differ from the saved dashboard");
        assertGrid(); assertEditInputsInert(); assertNoScooterWork();
        check("Default reset preserves slots; confirmed restore recovers all widget options and layouts; cancellation changes nothing");

        clickText(root(), "Layout");
        click(dialogRoot().findViewWithTag("dashboard-slot-save-1"));
        main(() -> first(dialogRoot(), EditText.class).setText("Another view"));
        click(dialog().getButton(AlertDialog.BUTTON_POSITIVE));
        click(dialogRoot().findViewWithTag("dashboard-slot-save-0"));
        main(() -> first(dialogRoot(), EditText.class).setText("My replaced commute"));
        click(dialog().getButton(AlertDialog.BUTTON_POSITIVE));
        require("My replaced commute".equals(config().slot(0).name)
                        && "Another view".equals(config().slot(1).name)
                        && savedExample.equals(settings().getString("dashboard_slot_v1_" + profileId + "_2", "")),
                "Replacing a slot changed another slot");
        click(findDescription(dialogRoot(), "Close dashboard layout"));
        finishActivity(); launch();
        require(config().widgets.size() == DEFAULT_TYPES.length + 1 && "My replaced commute".equals(config().slot(0).name),
                "Saved or active dashboard did not survive activity recreation");
        check("Replacing one slot preserves the other slots; saved and current layouts survive activity recreation");

        main(() -> settings().edit().putString("theme", "light").putFloat("text_scale", 1.4f).apply());
        await(() -> !activity.ui().dark && activity.ui().textScale > 1.3f && dashboard().view().getWidth() > 0,
                5000, "Light theme/large text did not apply");
        clickText(root(), "Layout");
        for (int i = 0; i < 3; i++) {
            View save = dialogRoot().findViewWithTag("dashboard-slot-save-" + i);
            View restore = dialogRoot().findViewWithTag("dashboard-slot-restore-" + i);
            reveal(save);
            main(() -> {
                int target = activity.ui().dp(48);
                require(save.getHeight() >= target && restore.getHeight() >= target,
                        "Slot buttons lost their touch targets");
                for (View button : new View[]{save, restore}) {
                    TextView text = (TextView)button;
                    require(text.getLayout() != null, "Slot button text is not laid out");
                    for (int line = 0; line < text.getLineCount(); line++)
                        require(text.getLayout().getEllipsisCount(line) == 0, "Slot action label is clipped");
                }
            });
        }
        screenshot("slots-large-light");
        clickText(dialogRoot(), "Restore default widgets");
        click(dialog().getButton(AlertDialog.BUTTON_NEGATIVE));
        assertNoScooterWork();
        check("Light theme and 140% text keep slot actions and default reset reachable with full touch targets");
    }

    private void requireFreshEmulator() {
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
        require(hardware.contains("ranchu") || hardware.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"),
                "Dashboard validation refuses physical phones");
        require(Build.VERSION.SDK_INT >= 31, "Use an Android 12 or newer emulator");
        for (String name : PREFS) require(getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE).getAll().isEmpty(),
                "Fresh emulator app data required; existing preferences refused");
        File[] files = getTargetContext().getFilesDir().listFiles();
        require(files != null && files.length == 0, "Fresh emulator app data required; existing private files refused");
        require(getTargetContext().databaseList().length == 0, "Fresh emulator app data required; existing databases refused");
        require(getTargetContext().checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && getTargetContext().checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED,
                "Grant Nearby devices externally on the fresh emulator before this runner");
        BluetoothManager manager = getTargetContext().getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        require(adapter == null || !adapter.isEnabled(), "Bluetooth must be off or unavailable; this runner never changes radio state");
    }
    private void seedSyntheticProfileAndHistory() {
        main(() -> {
            ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
            ScooterProfiles.Profile profile = profiles.create(FIXTURE_NAME, "000000000000", 0);
            profile.lastConnected = System.currentTimeMillis() - 86400000L;
            profiles.update(profile);
            // Deliberately no address or serial: no real scooter is impersonated or targeted.
            profileId = profile.id; fixtureHistory = new TelemetryHistory(getTargetContext());
            long now = System.currentTimeMillis() - 60000;
            for (int i = 0; i < 4; i++) {
                long wall = now + i * 5000;
                Map<String, TelemetryHistory.Reading> readings = new LinkedHashMap<>();
                syntheticReading(readings, "battery_percent", "73 %", "%", 73d, wall);
                syntheticReading(readings, "power_w", (900 + i * 10) + " W", "W", 900d + i * 10, wall);
                syntheticReading(readings, "battery_health_percent", "98 %", "%", 98d, wall);
                syntheticReading(readings, "remaining_range_km", "35.4 km", "km", 35.4d, wall);
                syntheticReading(readings, "temperature_c", "23.5 °C", "°C", 23.5d, wall);
                syntheticReading(readings, "controller_version", "Synthetic 1.2.3", "", null, wall);
                syntheticReading(readings, "charging_state", "Charging", "", 1d, wall);
                syntheticReading(readings, "voltage_v", "52 V", "V", 52d, wall);
                syntheticReading(readings, "current_a", "-2 A", "A", -2d, wall);
                syntheticReading(readings, "rated_capacity_ah", "14.5 Ah", "Ah", 14.5d, wall);
                syntheticReading(readings, "charge_limit_percent", "80 %", "%", 80d, wall);
                syntheticReading(readings, "odometer_km", "160.9344 km", "km", 160.9344d, wall);
                syntheticReading(readings, "driving_mode", "3", "", 3d, wall);
                syntheticReading(readings, "charge_cycles", "12 charges", "charges", 12d, wall);
                syntheticReading(readings, "capacity_throughput_ah", "150 Ah", "Ah", 150d, wall);
                fixtureHistory.record(profileId, new TelemetryHistory.Snapshot(readings, Collections.emptyMap(), wall), 1, true);
            }
        });
        AtomicReference<TelemetryHistory.Snapshot> saved = new AtomicReference<>();
        main(() -> fixtureHistory.load(profileId, saved::set));
        await(() -> saved.get() != null, 5000, "Synthetic fixture did not persist");
        require(saved.get().readings.containsKey("controller_version"), "Text fixture was not saved");
        fixtureHistory.close(); fixtureHistory = null;
    }
    private void verifyCapturedDefaults() {
        SharedPreferences isolated = getTargetContext().getSharedPreferences("dashboard_defaults_validation", Context.MODE_PRIVATE);
        require(isolated.getAll().isEmpty(), "Default-layout checks require empty isolated preferences");
        try {
            DashboardConfig fresh = new DashboardConfig(isolated, "fresh");
            assertCapturedDefaults(fresh);
            require(isolated.getInt("dashboard_columns", 0) == 5, "Fresh defaults did not select five columns");
            fresh.save();
            String original = isolated.getString("dashboard_v1_fresh", "");
            Set<String> originalIds = new HashSet<>();
            for (DashboardConfig.Widget widget : fresh.widgets) originalIds.add(widget.id);
            DashboardConfig reloaded = new DashboardConfig(isolated, "fresh");
            assertCapturedDefaults(reloaded); reloaded.save();
            require(original.equals(isolated.getString("dashboard_v1_fresh", "")),
                    "Saving and reopening defaults changed their identities, options or geometry");

            DashboardConfig other = new DashboardConfig(isolated, "other");
            other.widgets.clear();
            // Deliberately retain an existing custom widget subset.
            other.widgets.add(new DashboardConfig.Widget("quick"));
            DashboardConfig.Widget note = new DashboardConfig.Widget("note");
            note.title = "Existing custom card"; note.note = "Keep this local reminder";
            note.color = 2; note.metric = "voltage_v"; note.windowMs = 120000;
            note.minimum = 30; note.maximum = 55; note.advertisedWh = 800;
            note.keys.clear(); note.keys.add("voltage_v");
            note.layouts.put(2, new DashboardLayout.Cell(note.id, 1, 7, 1, 3));
            note.layouts.put(4, new DashboardLayout.Cell(note.id, 2, 9, 2, 5));
            other.widgets.add(note); other.save();
            String customSaved = isolated.getString("dashboard_v1_other", "");
            DashboardConfig customReloaded = new DashboardConfig(isolated, "other");
            require(customReloaded.widgets.size() == 2 && customReloaded.widgets.get(0).type.equals("quick"),
                    "Opening an existing dashboard replaced its custom widget list");
            customReloaded.save();
            require(customSaved.equals(isolated.getString("dashboard_v1_other", "")),
                    "Opening an existing dashboard changed its options or saved placements");

            require(isolated.edit().putString("dashboard_v1_empty", "[]").putInt("dashboard_columns", 3).commit(),
                    "Could not seed an intentionally empty dashboard and explicit column choice");
            DashboardConfig empty = new DashboardConfig(isolated, "empty");
            require(empty.widgets.isEmpty(), "An intentionally empty dashboard was replaced by defaults");
            empty.save();
            reloaded.widgets.get(0).title = "Customized target lock";
            reloaded.widgets.remove(2);
            DashboardConfig.Widget targetNote = new DashboardConfig.Widget("note");
            targetNote.note = "Only this profile should reset";
            reloaded.widgets.add(targetNote); originalIds.add(targetNote.id); reloaded.save();
            Map<String, ?> beforeReset = new LinkedHashMap<>(isolated.getAll());
            reloaded.reset(); assertCapturedDefaults(reloaded);
            for (DashboardConfig.Widget widget : reloaded.widgets)
                require(!originalIds.contains(widget.id), "Explicit reset reused a previous widget identity");
            Map<String, ?> afterReset = new LinkedHashMap<>(isolated.getAll());
            beforeReset.remove("dashboard_v1_fresh"); afterReset.remove("dashboard_v1_fresh");
            beforeReset.remove("dashboard_columns"); afterReset.remove("dashboard_columns");
            require(isolated.getInt("dashboard_columns", 0) == 5 && beforeReset.equals(afterReset),
                    "Reset failed to restore columns or changed another profile/slot");
            require(new DashboardConfig(isolated, "empty").widgets.isEmpty(), "Reset repopulated another profile's empty dashboard");
            String resetSaved = isolated.getString("dashboard_v1_fresh", "");
            DashboardConfig resetReloaded = new DashboardConfig(isolated, "fresh");
            assertCapturedDefaults(resetReloaded); resetReloaded.save();
            require(resetSaved.equals(isolated.getString("dashboard_v1_fresh", "")),
                    "The restored preset changed after saving and reopening");
        } finally { require(isolated.edit().clear().commit(), "Could not remove isolated default-layout fixture"); }
    }
    private void assertCapturedDefaults(DashboardConfig configuration) {
        DashboardSlotsValidation.assertCapturedDefaults(configuration, DashboardValidation::require);
    }
    private void verifyScooterStatsMigration() throws Exception {
        SharedPreferences isolated = getTargetContext().getSharedPreferences("dashboard_migration_validation", Context.MODE_PRIVATE);
        require(isolated.getAll().isEmpty(), "Dashboard migration checks require empty isolated preferences");
        List<String> oldKeys = Arrays.asList("remaining_range_km", "power_w", "battery_health_percent", "temperature_c");
        String legacy = "[{\"id\":\"legacy-photo\",\"type\":\"scooter\",\"title\":\"Existing photo\","
                + "\"keys\":[\"remaining_range_km\",\"power_w\",\"battery_health_percent\",\"temperature_c\"],"
                + "\"layouts\":{\"2\":[1,3,1,4],\"4\":[2,5,2,6]}}]";
        try {
            require(isolated.edit().putString("dashboard_v1_legacy", legacy).commit(), "Could not seed isolated legacy layout");
            DashboardConfig migrated = new DashboardConfig(isolated, "legacy");
            require(migrated.widgets.size() == 1, "Legacy migration changed widget count");
            DashboardConfig.Widget photo = migrated.widgets.get(0);
            require(photo.keys.equals(Arrays.asList("odometer_km", "driving_mode")),
                    "Legacy unused photo keys did not migrate to the two glanceable scooter readings");
            require(photo.id.equals("legacy-photo") && photo.title.equals("Existing photo")
                    && sameCell(photo.layouts.get(2), new DashboardLayout.Cell(photo.id, 1, 3, 1, 4))
                    && sameCell(photo.layouts.get(4), new DashboardLayout.Cell(photo.id, 2, 5, 2, 6)),
                    "Scooter statistics migration changed existing identity, title or geometry");
            migrated.save();
            org.json.JSONObject saved = new org.json.JSONArray(isolated.getString("dashboard_v1_legacy", "")).getJSONObject(0);
            require(saved.getBoolean("scooterStats"), "Saving migrated scooter statistics did not mark their schema");
            require(saved.getBoolean("glanceableScooterStats"), "Saving compact scooter statistics did not mark the new schema");

            String previousDefault = legacy.replace("\"remaining_range_km\",\"power_w\",\"battery_health_percent\",\"temperature_c\"",
                    "\"odometer_km\",\"driving_mode\",\"charge_cycles\",\"capacity_throughput_ah\"")
                    .replace("\"type\":\"scooter\"", "\"type\":\"scooter\",\"scooterStats\":true");
            require(isolated.edit().putString("dashboard_v1_previous-default", previousDefault).commit(),
                    "Could not seed the previous default scooter readings");
            DashboardConfig compact = new DashboardConfig(isolated, "previous-default");
            DashboardConfig.Widget compactPhoto = compact.widgets.get(0);
            require(compactPhoto.keys.equals(Arrays.asList("odometer_km", "driving_mode")),
                    "Previous default scooter readings retained charge count or throughput");
            require(compactPhoto.minimumHeight() < 4
                    && sameCell(compactPhoto.layouts.get(2), photo.layouts.get(2))
                    && sameCell(compactPhoto.layouts.get(4), photo.layouts.get(4)),
                    "Making the scooter card smaller changed its existing saved placement");
            compact.save();
            DashboardConfig compactReloaded = new DashboardConfig(isolated, "previous-default");
            require(compactReloaded.widgets.get(0).keys.equals(compactPhoto.keys),
                    "Compact scooter readings changed after saving and reopening");

            DashboardConfig fresh = new DashboardConfig(isolated, "fresh-custom");
            DashboardConfig.Widget custom = null;
            for (DashboardConfig.Widget widget : fresh.widgets) if (widget.type.equals("scooter")) custom = widget;
            require(custom != null, "Fresh configuration lacks a scooter widget");
            custom.keys.clear(); custom.keys.addAll(oldKeys); fresh.save();
            DashboardConfig reloaded = new DashboardConfig(isolated, "fresh-custom");
            DashboardConfig.Widget restored = null;
            for (DashboardConfig.Widget widget : reloaded.widgets) if (widget.id.equals(custom.id)) restored = widget;
            require(restored != null && restored.keys.equals(oldKeys),
                    "Reload migrated a new widget's deliberately selected readings a second time");
            restored.keys.clear();
            restored.keys.addAll(Arrays.asList("odometer_km", "driving_mode", "charge_cycles", "capacity_throughput_ah"));
            reloaded.save();
            DashboardConfig explicitFour = new DashboardConfig(isolated, "fresh-custom");
            DashboardConfig.Widget explicitRestored = null;
            for (DashboardConfig.Widget widget : explicitFour.widgets) if (widget.id.equals(custom.id)) explicitRestored = widget;
            require(explicitRestored != null && explicitRestored.keys.equals(restored.keys),
                    "A newly chosen four-reading scooter card was mistaken for an old default");
        } finally { require(isolated.edit().clear().commit(), "Could not remove isolated migration fixture"); }
    }
    private void syntheticReading(Map<String, TelemetryHistory.Reading> readings, String key, String value, String unit, Double number, long wall) {
        Telemetry.Metric metric = null; for (Telemetry.Metric m : new Telemetry().metrics()) if (m.key.equals(key)) metric = m;
        require(metric != null, "Unknown synthetic parameter");
        readings.put(key, new TelemetryHistory.Reading(key, metric.label, metric.group, value, unit, number, wall, true));
    }
    private void verifyClockFormats() {
        SharedPreferences isolated = getTargetContext().getSharedPreferences("clock_validation", Context.MODE_PRIVATE);
        require(isolated.getAll().isEmpty(), "Clock checks require empty isolated preferences");
        Locale originalLocale = Locale.getDefault(); java.util.TimeZone originalZone = java.util.TimeZone.getDefault();
        try {
            Locale.setDefault(Locale.US); java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
            java.util.Calendar calendar = java.util.Calendar.getInstance(); calendar.clear();
            calendar.set(2026, java.util.Calendar.JANUARY, 28, 13, 5);
            long wall = calendar.getTimeInMillis();
            isolated.edit().putString("time_format", "24h").commit();
            require(DisplayTime.is24Hour(getTargetContext(), isolated)
                    && DisplayTime.clock(getTargetContext(), isolated, wall).equals("13:05"),
                    "Explicit 24-hour clock did not show 13:05");
            isolated.edit().putString("time_format", "12h").commit();
            String clock = DisplayTime.clock(getTargetContext(), isolated, wall).replaceAll("[\\s\\p{Z}]", "");
            require(!DisplayTime.is24Hour(getTargetContext(), isolated) && clock.equals("1:05PM"),
                    "Explicit 12-hour clock did not show 1:05 PM");
            require(DisplayTime.recent(getTargetContext(), isolated, wall, wall + 60000).equals(
                    DisplayTime.clock(getTargetContext(), isolated, wall)), "A same-day connection unnecessarily repeats its date");
            String yesterday = DisplayTime.recent(getTargetContext(), isolated, wall, wall + 86400000L);
            require(yesterday.equals("28 JAN · " + DisplayTime.clock(getTargetContext(), isolated, wall)),
                    "An older connection lost its date or clock and can be confused with today");
            String previousYear = DisplayTime.recent(getTargetContext(), isolated, wall, wall + 370 * 86400000L);
            require(previousYear.equals(yesterday), "A previous-year connection lost its compact day/month prefix");
            require(DisplayTime.clock(getTargetContext(), isolated, 0).isEmpty()
                    && DisplayTime.recent(getTargetContext(), isolated, 0, wall).isEmpty(),
                    "A scooter with no connection history invented a clock time");
            isolated.edit().putString("time_format", "system").commit();
            require(DisplayTime.is24Hour(getTargetContext(), isolated) == android.text.format.DateFormat.is24HourFormat(getTargetContext()),
                    "Follow system ignored the phone's clock format");
        } finally {
            Locale.setDefault(originalLocale); java.util.TimeZone.setDefault(originalZone);
            require(isolated.edit().clear().commit(), "Could not remove isolated clock preferences");
        }
    }
    /** Offline view of the redesigned Home widgets, using only the synthetic cached readings. */
    private void verifyHomeWidgetsOffline() {
        await(() -> {
            Object overview = tileOrNull("overview");
            return overview != null && bodyText(overview).contains("73");
        }, 5000, "Scooter overview did not render the cached charge");
        main(() -> {
            require(activity.service().ble.state() == BleController.State.DISCONNECTED, "Home widget fixture must stay disconnected");
            Object lockBar = tile("lockbar");
            View lockBody = (View)field(lockBar, "body");
            require(findText(lockBody, "Lock state unknown") != null, "Disconnected Lock bar lost its unknown-state title");
            TextView connect = findText(lockBody, "Connect");
            require(connect != null && connect.isEnabled(), "Disconnected Lock bar lacks an enabled Connect action");
            require(connect.getHeight() >= activity.ui().dp(48) - 1, "Lock bar Connect lost its 48 dp touch target");
            require(((View)lockBar).getHeight() <= Math.round(((Number)invoke(grid(), "rowHeight")).floatValue()) + 1,
                    "Default Lock bar grew beyond one grid row");

            String overview = bodyText(tile("overview")).toLowerCase(Locale.ROOT);
            require(overview.contains("73") && overview.contains("%"), "Scooter overview lacks its cached percentage");
            require(overview.contains("odometer") && overview.contains("drive mode"),
                    "Scooter overview lacks its default Odometer and Drive mode readings");

            String readings = bodyText(tile("tiles")).toLowerCase(Locale.ROOT);
            require(readings.contains("power") && readings.contains("temp") && readings.contains("health"),
                    "Reading tiles lack their default Power, Temp. and Health labels: " + readings);
            require(readings.contains("98"), "Reading tiles did not render the cached health value: " + readings);

            String settings = bodyText(tile("shortcuts")).toLowerCase(Locale.ROOT);
            require(settings.contains("top speed") && settings.contains("scheduled charging"),
                    "Setting tiles lack their default Top speed and Scheduled charging tiles: " + settings);

            for (String type : new String[] {"lockbar", "overview", "tiles", "shortcuts"}) {
                Object tile = tile(type);
                Object footer = field(tile, "footer");
                require(!(footer instanceof TextView) || !((TextView)footer).isShown()
                                || !((TextView)footer).getText().toString().contains("Last seen"),
                        type + " draws its own Last seen footer; the header carries the time");
                require(!bodyText(tile).contains("Last seen"), type + " repeats Last seen inside its card");
            }
            // The header's status line now carries the time; the old caption below it is gone.
            Object stale = optionalField(dashboard(), "stale");
            require(!(stale instanceof View) || !((View)stale).isShown()
                            || !(stale instanceof TextView) || ((TextView)stale).length() == 0,
                    "Home still shows a separate Last seen caption under the header");
        });
        assertNoScooterWork();
        check("Offline Lock bar offers Connect with an unknown state; overview, reading and setting tiles show cached values without their own Last seen footers");
    }
    private void seedScooterCard() {
        main(() -> {
            DashboardConfig.Widget scooter = new DashboardConfig.Widget("scooter");
            // Below the default stack (rows 0–8) or, at six columns, below the left column (rows 0–4).
            for (int c = 1; c <= DashboardConfig.MAX_COLUMNS; c++)
                scooter.layouts.put(c, new DashboardLayout.Cell(scooter.id, 0, c == 6 ? 5 : 9, c, scooter.defaultHeight()));
            config().widgets.add(scooter); config().save(); invoke(dashboard(), "render");
        });
        await(() -> tileOrNull("scooter") != null && ((View)tile("scooter")).getWidth() > 0, 4000, "Seeded scooter card did not lay out");
        assertGrid();
    }
    /** Lock and Lock bar are exclusive: the palette marks both On Home and refuses a second lock control. */
    private void verifySingleLockControlPalette(View lockRow) {
        View barRow = dialogRoot().findViewWithTag("add-widget:lockbar");
        require(barRow != null && findText(barRow, "On Home") != null, "Palette does not mark the Lock bar as On Home");
        require(findText(lockRow, "On Home") != null, "Palette offers Lock beside the existing Lock bar");
        int before = config().widgets.size();
        if (lockRow.isClickable() && lockRow.isEnabled()) click(lockRow);
        main(() -> require(!types().contains("lock") && config().widgets.size() == before,
                "Palette added Lock beside the Lock bar"));
        AlertDialog open = (AlertDialog)field(dashboard(), "dialog");
        if (open != null && open.isShowing()) {
            View close = findDescription(dialogRoot(), "Close add widget");
            if (close == null) close = findDescription(dialogRoot(), "Close widget options");
            require(close != null, "Refused Lock left an unclosable sheet");
            click(close);
        }
        main(() -> require(!types().contains("lock"), "Closing the palette added Lock"));
    }
    private static String bodyText(Object tile) {
        StringBuilder text = new StringBuilder();
        for (TextView view : all((View)field(tile, "body"), TextView.class))
            if (view.isShown() && view.length() > 0) text.append(view.getText()).append('\n');
        return text.toString();
    }
    private boolean goneOrAbsent(String name) {
        Object view = optionalField(activity, name);
        return !(view instanceof View) || ((View)view).getVisibility() == View.GONE;
    }
    private static Object optionalField(Object value, String name) {
        try { Field field = value.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(value); }
        catch (ReflectiveOperationException error) { return null; }
    }
    private void verifyCompactConnection() {
        main(() -> {
            ScooterProfiles.Profile profile = activity.service().profiles.active();
            BleController ble = activity.service().ble;
            long originalLast = profile.lastConnected; BleController.State originalState = ble.state();
            try {
                // Home's status line belongs to the dashboard header.
                profile.lastConnected = 0; invoke(dashboard(), "refresh");
                TextView status = (TextView)field(dashboard(), "status");
                require("Disconnected".contentEquals(status.getText()), "No connection history should show only Disconnected");
                profile.lastConnected = System.currentTimeMillis() - 60000; invoke(dashboard(), "refresh");
                require(status.getText().toString().equals("Disconnected · last seen " + DisplayTime.recent(
                        getTargetContext(), settings(), profile.lastConnected, System.currentTimeMillis())),
                        "Disconnected status did not display the most recent connection time");
                require(status.getContentDescription().toString().contains("last connected"),
                        "Connection time lacks an accessible explanation");
                for (BleController.State state : new BleController.State[] {BleController.State.WAITING,
                        BleController.State.SCANNING, BleController.State.CONNECTING, BleController.State.AUTHENTICATING}) {
                    // View-state fixture only; no BLE transition method, callbacks or queue is called.
                    setField(ble, "state", state); invoke(activity, "updateHeader"); invoke(dashboard(), "refresh");
                    CharSequence text = status.getText();
                    require(text.toString().startsWith("Connecting") && text instanceof android.text.Spanned,
                            state + " did not render the compact connecting status");
                    android.text.style.ImageSpan[] spinners = ((android.text.Spanned)text).getSpans(0, text.length(), android.text.style.ImageSpan.class);
                    require(spinners.length == 1 && spinners[0].getDrawable().getBounds().width() > 0,
                            state + " lacks one inline loading indicator");
                    require(goneOrAbsent("lockHint") && goneOrAbsent("connectionDetail"),
                            state + " added an explanatory text block to the compact connection card");
                }
            } finally {
                profile.lastConnected = originalLast; setField(ble, "state", originalState); invoke(activity, "updateHeader"); invoke(dashboard(), "refresh");
            }
        });
        assertNoScooterWork();
    }
    private void launch() {
        activity = (MainActivity)startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> activity.service() != null && root().isAttachedToWindow() && root().getWidth() > 0, 6000, "Workspace did not initialize");
        awaitDashboard();
    }
    private void verifyDisconnectedScooterPhoto() {
        main(() -> {
            ScooterService service = activity.service();
            require(service.ble.state() == BleController.State.DISCONNECTED, "Offline photo fixture must stay disconnected");
            ScooterProfiles.Profile profile = service.profiles.active();
            String originalSerial = profile.serial;
            Object scooter = tile("scooter");
            Map<String, TelemetryHistory.Reading> cached = service.displayReadings();
            try {
                // Model-only display fixture; never saved or used for a BLE connection.
                profile.serial = "X2538-SYNTHETIC";
                require(profile.pid() == 2538, "Synthetic photo fixture did not identify the UT5 MAX model");
                invoke(scooter, "refresh", new Class<?>[] {Map.class}, cached);
                android.widget.ImageView photo = (android.widget.ImageView)field(scooter, "photo");
                require(photo.getVisibility() == View.VISIBLE && photo.getDrawable() != null
                        && ((View)field(scooter, "photoHint")).getVisibility() == View.GONE,
                        "A disconnected known UT5 MAX lost its scooter image or still shows the placeholder");
                for (Object value : ((Map<?, ?>)field(scooter, "amounts")).values())
                    require(((TextView)value).getCurrentTextColor() == activity.ui().muted,
                            "Keeping the scooter photo visible made an offline reading appear live");
                require(!service.readingFresh("odometer_km") && !service.readingFresh("driving_mode"),
                        "Displaying an offline scooter photo promoted cached readings to fresh data");
            } finally {
                profile.serial = originalSerial;
                invoke(scooter, "refresh", new Class<?>[] {Map.class}, cached);
            }
            require(((View)field(scooter, "photo")).getVisibility() == View.GONE,
                    "Restoring an unknown model incorrectly retained the UT5 MAX image");
        });
        assertNoScooterWork();
    }
    private void awaitDashboard() { await(() -> field(activity, "screen") instanceof DashboardScreen && grid().getWidth() > 0,
            5000, "Dashboard did not attach"); waitForIdleSync(); }
    private void recreateActivity() {
        ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
        try {
            main(() -> activity.recreate());
            android.app.Activity replacement = waitForMonitorWithTimeout(monitor, 6000);
            require(replacement instanceof MainActivity, "Activity recreation was not observed");
            activity = (MainActivity)replacement; awaitDashboard();
            await(() -> activity.service() != null, 5000, "Recreated activity did not bind its service");
        } finally { removeMonitor(monitor); }
    }
    private void restartServiceAndActivity() {
        ScooterService old = activity.service(); finishActivity();
        main(() -> { ScooterService.setUiVisible(false); getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); });
        await(() -> (Boolean)field(old, "destroyed"), 4000, "Old service did not finish"); launch();
        require(activity.service() != old, "A fresh service was not created");
    }
    private void assertCachedReadings() {
        // Loading the service's cache and redrawing its listeners are separate
        // main-loop steps. Assert the rendered result after both have completed.
        final String[] rendered = {"Dashboard has not rendered cached readings"};
        try {
            await(() -> {
                Object scooter = tileOrNull("scooter");
                if (scooter == null) return false;
                Map<?, ?> readings = (Map<?, ?>)field(scooter, "amounts");
                TextView odometer = (TextView)readings.get("odometer_km");
                TextView mode = (TextView)readings.get("driving_mode");
                String odoText = odometer == null ? "<missing>" : odometer.getText().toString();
                String modeText = mode == null ? "<missing>" : mode.getText().toString();
                String chargeText = metricId == null ? "<not configured>"
                        : ((TextView)field(tileById(metricId), "big")).getText().toString();
                rendered[0] = "odometer=" + odoText + ", mode=" + modeText + ", battery reading=" + chargeText;
                return odoText.equals("100.00 mi") && modeText.equals("3 (raw)")
                        && (metricId == null || chargeText.contains("73"));
            }, 5000, "Cached readings loaded but their dashboard values did not render");
        } catch (IllegalStateException error) {
            throw new IllegalStateException(error.getMessage() + "; actual " + rendered[0], error);
        }
        main(() -> {
            TelemetryHistory.Reading number = activity.service().displayReadings().get("battery_percent");
            TelemetryHistory.Reading text = activity.service().displayReadings().get("controller_version");
            require(number.number != null && number.number == 73d && !number.live && number.capturedWallMs > 0,
                    "Battery cache is absent or incorrectly live");
            require("Synthetic 1.2.3".equals(text.value) && text.number == null && !text.live && text.capturedWallMs > 0,
                    "Nonnumeric cache is absent or incorrectly live");
            require(!activity.service().readingFresh("battery_percent") && !activity.service().featureFresh("headlight"),
                    "Cache must not provide command freshness");
            Map<?, ?> stats = (Map<?, ?>)field(tile("scooter"), "amounts");
            require(stats.keySet().equals(new HashSet<>(Arrays.asList("odometer_km", "driving_mode"))),
                    "A newly added My Scooter card should contain odometer and drive mode");
            TextView odometer = (TextView)stats.get("odometer_km"), mode = (TextView)stats.get("driving_mode");
            require("100.00 mi".contentEquals(odometer.getText()) && "3 (raw)".contentEquals(mode.getText()),
                    "My Scooter did not format its cached odometer and unknown-model mode: "
                            + odometer.getText() + ", " + mode.getText());
            require(odometer.getCurrentTextColor() == activity.ui().muted && mode.getCurrentTextColor() == activity.ui().muted,
                    "My Scooter's disconnected stats appear live");
        });
    }
    private void assertNoScooterWork() {
        main(() -> {
            ScooterService service = activity.service(); require(service != null, "Workspace disappeared");
            List<ScooterProfiles.Profile> profiles = service.profiles.all();
            require(profiles.size() == 1 && profileId.equals(profiles.get(0).id)
                    && profiles.get(0).mac.isEmpty() && profiles.get(0).identity.equals("000000000000"), "Unexpected profile state");
            require(!service.isRecording() && !service.phoneSaving() && service.ble.state() == BleController.State.DISCONNECTED,
                    "Unexpected recording or Bluetooth work");
            require(((Number)field(service.ble, "mutationSequence")).longValue() == 0 && service.ble.commandResults().isEmpty(),
                    "Unexpected scooter write");
            try { require(service.rides.list().isEmpty(), "Dashboard validation created ride history"); }
            catch (java.io.IOException error) { throw new IllegalStateException(error); }
        });
    }
    private void assertEditInputsInert() {
        main(() -> {
            require(dashboard().isEditing(), "Expected dashboard Edit mode");
            for (Object tile : tiles()) assertDisabledActions((View)field(tile, "body"));
            require(!((View)field(activity, "connectionAction")).isEnabled(), "Connection remains interactive during Edit");
            View lockControl = (View)field(activity, "lockControl");
            require(lockControl == null || !lockControl.isEnabled(), "Lock remains interactive during Edit");
        }); assertNoScooterWork();
    }
    private void assertDisabledActions(View view) {
        if ("Rename scooter".contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) {
            require(view.isEnabled(), "Edit-only scooter rename is disabled");
        } else if (view.isClickable()) require(!view.isEnabled(), "Widget action remains enabled while editing: " + view.getContentDescription());
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) assertDisabledActions(((ViewGroup)view).getChildAt(i));
    }
    private void assertConfigurationHidden() {
        main(() -> {
            require(!dashboard().isEditing(), "Expected ordinary dashboard mode");
            require(findDescription(root(), "Rename scooter") == null, "Scooter rename pencil is visible outside editing");
            require(!((View)field(dashboard(), "profileName")).isClickable(), "Scooter name remains a configuration action outside editing");
            for (Object tile : tiles()) {
                DashboardConfig.Widget widget = (DashboardConfig.Widget)field(tile, "widget");
                for (String prefix : new String[] {"Options for ", "Move ", "Resize ", "Duplicate ", "Remove "})
                    require(findDescription((View)tile, prefix + widget.title) == null,
                            "Widget configuration control remains outside editing: " + prefix + widget.title);
            }
        });
    }
    private static final class CardGeometry {
        DashboardLayout.Cell cell;
        Rect outer, body, heading, footer;
        final Map<String, Rect> textBounds = new LinkedHashMap<>();
        final Map<String, Float> textSizes = new LinkedHashMap<>();
        final Map<String, String> textValues = new LinkedHashMap<>();
    }
    private Map<String, CardGeometry> contentGeometry() {
        Map<String, CardGeometry> result = new LinkedHashMap<>();
        for (Object object : tiles()) {
            DashboardConfig.Widget widget = (DashboardConfig.Widget)field(object, "widget");
            View tile = (View)object, body = (View)field(object, "body");
            CardGeometry geometry = new CardGeometry(); geometry.cell = cell(widget.id).copy();
            geometry.outer = new Rect(tile.getLeft(), tile.getTop(), tile.getRight(), tile.getBottom());
            geometry.body = relativeBounds(body, tile);
            geometry.heading = relativeBounds((View)field(object, "heading"), tile);
            geometry.footer = relativeBounds((View)field(object, "footer"), tile);
            textGeometry(body, body, "body", geometry);
            result.put(widget.id, geometry);
        }
        return result;
    }
    private void textGeometry(View body, View current, String path, CardGeometry geometry) {
        if (current.getVisibility() != View.VISIBLE) return;
        if (current instanceof TextView) {
            geometry.textBounds.put(path, relativeBounds(current, body));
            geometry.textSizes.put(path, ((TextView)current).getTextSize());
            geometry.textValues.put(path, ((TextView)current).getText().toString());
        }
        if (current instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)current).getChildCount(); i++)
            textGeometry(body, ((ViewGroup)current).getChildAt(i), path + "/" + i, geometry);
    }
    private void enterEditPreservingContent() {
        require(!dashboard().isEditing(), "Content comparison needs ordinary dashboard mode first");
        // Compare layout itself at the same scroll position, independently of the
        // long-note scrolling exercise earlier in the suite.
        main(() -> { for (Object tile : tiles()) first((View)tile, ScrollView.class).scrollTo(0, 0); });
        awaitSettledLayout();
        AtomicReference<Map<String, CardGeometry>> before = new AtomicReference<>();
        main(() -> before.set(contentGeometry()));
        clickDescription("Edit dashboard"); await(() -> dashboard().isEditing(), 3000, "Edit pencil did not open edit mode");
        awaitSettledLayout();
        main(() -> {
            Map<String, CardGeometry> after = contentGeometry();
            require(after.keySet().equals(before.get().keySet()), "Entering Edit changed the widget list");
            for (Map.Entry<String, CardGeometry> entry : before.get().entrySet()) {
                CardGeometry a = entry.getValue(), b = after.get(entry.getKey());
                String type = widgetById(entry.getKey()).type;
                require(sameCell(a.cell, b.cell) && a.outer.equals(b.outer),
                        type + ": entering Edit changed the card's position or size");
                require(a.body.equals(b.body) && a.heading.equals(b.heading) && a.footer.equals(b.footer),
                        type + ": editing controls changed the content, heading or footer geometry");
                require(a.textBounds.equals(b.textBounds) && a.textSizes.equals(b.textSizes),
                        type + ": entering Edit changed body text sizing, placement or added a configuration row; " + textDifferences(a, b));
            }
        });
    }
    private String textDifferences(CardGeometry before, CardGeometry after) {
        java.util.LinkedHashSet<String> paths = new java.util.LinkedHashSet<>(before.textBounds.keySet());
        paths.addAll(after.textBounds.keySet()); ArrayList<String> differences = new ArrayList<>();
        for (String path : paths) {
            if (!java.util.Objects.equals(before.textBounds.get(path), after.textBounds.get(path))
                    || !java.util.Objects.equals(before.textSizes.get(path), after.textSizes.get(path))) {
                String left = before.textValues.get(path), right = after.textValues.get(path);
                differences.add(path + " bounds " + before.textBounds.get(path) + " -> " + after.textBounds.get(path)
                        + ", size " + before.textSizes.get(path) + " -> " + after.textSizes.get(path)
                        + ", text " + diagnosticText(left) + (java.util.Objects.equals(left, right) ? "" : " -> " + diagnosticText(right)));
                if (differences.size() == 5) break;
            }
        }
        return differences.toString();
    }
    private String diagnosticText(String text) {
        if (text == null) return "null";
        text = text.replace('\n', ' ');
        return text.length() <= 96 ? text : text.substring(0, 96) + "…";
    }
    private void verifyResponsiveCards() {
        require(!dashboard().isEditing(), "Responsive content checks require ordinary dashboard mode");
        main(() -> {
            int columns = ((Number)field(grid(), "columns")).intValue();
            float columnWidth = ((Number)invoke(grid(), "columnWidth")).floatValue();
            float rowHeight = ((Number)invoke(grid(), "rowHeight")).floatValue();
            for (Object object : tiles()) {
                DashboardConfig.Widget widget = (DashboardConfig.Widget)field(object, "widget");
                View tile = (View)object;
                Set<Integer> widths = new HashSet<>(Arrays.asList(1, widget.defaultWidth(columns), columns));
                Set<Integer> heights = new HashSet<>(Arrays.asList(widget.minimumHeight(), widget.defaultHeight()));
                for (int width : widths) for (int height : heights) {
                    measureCard(tile, Math.round(width * columnWidth) - activity.ui().dp(10),
                            Math.round(height * rowHeight) - activity.ui().dp(10));
                    String context = widget.type + " " + width + "x" + height + " at " + activity.ui().textScale + "x text";
                    if (widget.type.equals("scooter") && width == 1 && height == widget.minimumHeight())
                        verifyDriveModeStyles(object, tile.getMeasuredWidth(), tile.getMeasuredHeight(), context);
                    ScrollView viewport = first(tile, ScrollView.class);
                    View body = (View)field(object, "body");
                    require(viewport != null && viewport.getHeight() > 0 && body.getWidth() > 0 && body.getHeight() > 0,
                            context + ": content has no usable viewport");
                    if (Arrays.asList("lock", "lockbar", "metric", "gauge", "ride").contains(widget.type))
                        assertBalancedContent((ViewGroup)body, viewport, context);
                    if (widget.type.equals("lock") || widget.type.equals("lockbar")) {
                        View slider = (View)field(activity, "lockControl");
                        boolean sliderShown = slider != null && slider.isShown();
                        Rect bounds = sliderShown ? relativeBounds(slider, body) : new Rect();
                        // The bar shows once connected; offline the card offers Connect instead.
                        require(!sliderShown || (Math.abs(bounds.width() - body.getWidth()) <= 1 && bounds.left <= 1),
                                context + ": lock bar does not span its card");
                        require(!sliderShown || bounds.height() >= activity.ui().dp(48),
                                context + ": lock bar lost its full-size touch target");
                        require(goneOrAbsent("lockHint") && goneOrAbsent("connectionDetail"),
                                context + ": compact lock card contains a redundant explanation");
                    }
                    for (TextView text : all(body, TextView.class))
                        if (text.isShown() && text.length() > 0) assertReadableValue(text, body, context + " body text");
                    for (String name : new String[] {"photoPane", "chart", "gauge", "battery"}) {
                        View visual = (View)field(object, name);
                        if (visual != null && visual.isShown()) {
                            assertExplicitDimensions(visual, context + " " + name);
                            Rect bounds = relativeBounds(visual, body);
                            require(visual.getWidth() > 0 && visual.getHeight() > 0 && bounds.left >= 0 && bounds.right <= body.getWidth() + 1,
                                    context + ": visual has no readable width/height or extends horizontally outside its body");
                        }
                    }
                }
            }
            assertScooterRowAtMinimum(columns, columnWidth, rowHeight);
            assertBatteryAtMinimum(columns, columnWidth, rowHeight);
            // The matrix deliberately measured children outside their parent's
            // normal layout pass. Invalidate those cached subtree measurements
            // before returning to the actual saved dashboard geometry.
            for (Object tile : tiles()) forceSubtreeMeasure((View)tile);
            grid().requestLayout();
        });
        await(() -> !grid().isLayoutRequested(), 3000, "Dashboard did not restore normal placement after sizing checks");
        assertGrid(); assertNoScooterWork();
    }
    private void forceSubtreeMeasure(View view) {
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++)
            forceSubtreeMeasure(((ViewGroup)view).getChildAt(i));
        view.forceLayout();
    }
    private void assertBalancedContent(ViewGroup body, ScrollView viewport, String context) {
        if (body.getHeight() > viewport.getHeight() + 1) return; // Long custom content intentionally scrolls.
        int top = Integer.MAX_VALUE, bottom = Integer.MIN_VALUE;
        for (int i = 0; i < body.getChildCount(); i++) {
            View child = body.getChildAt(i);
            if (child.getVisibility() == View.GONE || child.getHeight() == 0) continue;
            int before = 0, after = 0;
            if (child.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams)child.getLayoutParams();
                before = margins.topMargin; after = margins.bottomMargin;
            }
            top = Math.min(top, child.getTop() - before); bottom = Math.max(bottom, child.getBottom() + after);
        }
        if (top == Integer.MAX_VALUE) return;
        int topGap = top - body.getPaddingTop(), bottomGap = body.getHeight() - body.getPaddingBottom() - bottom;
        require(Math.abs(topGap - bottomGap) <= activity.ui().dp(2),
                context + ": fitting widget content is not vertically balanced: top=" + topGap + ", bottom=" + bottomGap);
    }
    private void verifyDriveModeStyles(Object object, int width, int height, String context) {
        TextView mode = (TextView)((Map<?, ?>)field(object, "amounts")).get("driving_mode");
        require(mode != null, "Synthetic scooter statistics lack drive mode");
        CharSequence originalText = mode.getText(), originalDescription = mode.getContentDescription();
        android.content.res.ColorStateList originalColors = mode.getTextColors();
        android.graphics.drawable.Drawable[] originalDrawables = mode.getCompoundDrawablesRelative().clone();
        int originalPadding = mode.getCompoundDrawablePadding();
        try {
            applyModeStyle(mode, "SPORT", true);
            require("SPORT".contentEquals(mode.getText()) && mode.getCurrentTextColor() == activity.ui().accent,
                    "Live SPORT is not recognizable red text");
            measureCard((View)object, width, height); assertReadableValue(mode, (View)field(object, "body"), context + " SPORT");
            applyModeStyle(mode, "ECO", true);
            require("ECO".contentEquals(mode.getText()) && mode.getCurrentTextColor() == activity.ui().ok
                    && mode.getCurrentTextColor() != activity.ui().accent, "Live ECO is not distinct green text");
            measureCard((View)object, width, height); assertReadableValue(mode, (View)field(object, "body"), context + " ECO");
            applyModeStyle(mode, "WALK", true);
            assertWalkingIcon(mode, activity.ui().text, context);
            measureCard((View)object, width, height); assertWalkingIconFits(mode, context);
            applyModeStyle(mode, "SPORT", false);
            require("SPORT".contentEquals(mode.getText()) && mode.getCurrentTextColor() == activity.ui().muted,
                    "Cached SPORT appears live");
            applyModeStyle(mode, "ECO", false);
            require("ECO".contentEquals(mode.getText()) && mode.getCurrentTextColor() == activity.ui().muted,
                    "Cached ECO appears live");
            applyModeStyle(mode, "WALK", false);
            assertWalkingIcon(mode, activity.ui().muted, context + " cached");
            measureCard((View)object, width, height); assertWalkingIconFits(mode, context + " cached");
            String unknown = Telemetry.drivingModeLabel(0, 11d);
            applyModeStyle(mode, unknown, true);
            require(unknown.equals("11 (raw)") && unknown.contentEquals(mode.getText())
                    && mode.getCompoundDrawablesRelative()[0] == null,
                    "An unsupported raw drive mode was presented as a recognized walking mode");
        } finally {
            mode.setText(originalText); mode.setTextColor(originalColors); mode.setContentDescription(originalDescription);
            mode.setCompoundDrawablesRelative(originalDrawables[0], originalDrawables[1], originalDrawables[2], originalDrawables[3]);
            mode.setCompoundDrawablePadding(originalPadding); measureCard((View)object, width, height);
        }
    }
    private void applyModeStyle(TextView view, String mode, boolean live) {
        view.setText(mode); view.setTextColor(live ? activity.ui().text : activity.ui().muted);
        view.setContentDescription("Drive mode, " + mode + (live ? "" : ", last seen"));
        activity.ui().styleDriveMode(view, mode, live);
    }
    private void assertWalkingIcon(TextView mode, int color, String context) {
        require(mode.length() == 0 && mode.getCompoundDrawablesRelative()[0] != null
                && mode.getCurrentTextColor() == color, context + ": WALK does not use the expected walking icon and live/stale color");
        require(mode.getContentDescription() != null
                && mode.getContentDescription().toString().toLowerCase(Locale.ROOT).contains("walk"),
                context + ": walking icon lacks an accessible mode label");
    }
    private void assertWalkingIconFits(TextView mode, String context) {
        android.graphics.drawable.Drawable icon = mode.getCompoundDrawablesRelative()[0];
        require(icon != null && icon.getBounds().width() > 0 && icon.getBounds().height() > 0,
                context + ": walking icon has no drawable bounds");
        require(icon.getBounds().width() + mode.getPaddingLeft() + mode.getPaddingRight() <= mode.getWidth()
                && icon.getBounds().height() + mode.getPaddingTop() + mode.getPaddingBottom() <= mode.getHeight(),
                context + ": walking icon is clipped in its compact statistics cell");
    }
    private void measureCard(View tile, int width, int height) {
        tile.measure(View.MeasureSpec.makeMeasureSpec(Math.max(1, width), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(Math.max(1, height), View.MeasureSpec.EXACTLY));
        tile.layout(tile.getLeft(), tile.getTop(), tile.getLeft() + tile.getMeasuredWidth(), tile.getTop() + tile.getMeasuredHeight());
    }
    private void assertExplicitDimensions(View visual, String context) {
        ViewGroup.LayoutParams requested = visual.getLayoutParams();
        require(requested != null, context + ": visible graphic has no layout parameters");
        if (requested.height > 0)
            require(visual.getMeasuredHeight() == requested.height,
                    context + ": graphic measured height lags requested height (" + visual.getMeasuredHeight() + " vs " + requested.height + ")");
        if (requested.width > 0)
            require(visual.getMeasuredWidth() == requested.width,
                    context + ": graphic measured width lags requested width (" + visual.getMeasuredWidth() + " vs " + requested.width + ")");
    }
    private void assertReadableValue(TextView value, View body, String context) {
        android.text.Layout layout = value.getLayout();
        require(layout != null && value.getWidth() > 0 && value.getHeight() > 0, context + ": value has no text layout");
        String text = value.getText().toString();
        require(layout.getLineCount() > 0 && layout.getLineEnd(layout.getLineCount() - 1) >= text.length(),
                context + ": value loses text beyond its maximum lines: " + text);
        require(layout.getHeight() <= value.getHeight() - value.getCompoundPaddingTop() - value.getCompoundPaddingBottom() + 1,
                context + ": value is vertically clipped: " + text);
        for (int line = 0; line < layout.getLineCount(); line++)
            require(layout.getEllipsisCount(line) == 0 && layout.getLineMax(line) <= layout.getWidth() + 1,
                    context + ": value is truncated or clipped horizontally: " + text);
        float readableFloor = 12 * activity.ui().textScale * getTargetContext().getResources().getDisplayMetrics().scaledDensity;
        // Compact timestamps deliberately shrink to preserve the full date and time on one line.
        if (value == field(activity, "connectionTitle")) readableFloor = value.getAutoSizeMinTextSize();
        require(value.getTextSize() >= readableFloor - .5f, context + ": value shrank below the readable text floor: " + text);
        Rect bounds = relativeBounds(value, body);
        require(bounds.left >= 0 && bounds.right <= body.getWidth() + 1,
                context + ": value extends outside the card's scrollable content width: " + text);
    }
    private void assertScooterRowAtMinimum(int columns, float columnWidth, float rowHeight) {
        Object scooter = tile("scooter"); View card = (View)scooter, body = (View)field(scooter, "body");
        View photo = (View)field(scooter, "photo"), hint = (View)field(scooter, "photoHint"), info = (View)field(scooter, "scooterInfo");
        Map<View, Integer> visibility = new LinkedHashMap<>();
        View current = photo;
        while (current != body) {
            visibility.put(current, current.getVisibility()); current.setVisibility(View.VISIBLE);
            require(current.getParent() instanceof View, "Scooter photo left its card hierarchy");
            current = (View)current.getParent();
        }
        int hintVisibility = hint.getVisibility(); hint.setVisibility(View.GONE);
        try {
            DashboardConfig.Widget widget = (DashboardConfig.Widget)field(scooter, "widget");
            measureCard(card, Math.round(columns * columnWidth) - activity.ui().dp(10),
                    Math.round(widget.minimumHeight() * rowHeight) - activity.ui().dp(10));
            assertExplicitDimensions((View)field(scooter, "photoPane"), "Synthetic visible scooter photo at minimum size");
            Rect image = relativeBounds(photo, body), stats = relativeBounds(info, body);
            require(image.width() > 0 && image.height() > 0 && image.right <= stats.left,
                    "Scooter photo is not laid out to the left of the statistics");
            Map<?, ?> readings = (Map<?, ?>)field(scooter, "amounts");
            View odometer = (View)((View)readings.get("odometer_km")).getParent();
            View mode = (View)((View)readings.get("driving_mode")).getParent();
            require(relativeBounds(odometer, body).bottom <= relativeBounds(mode, body).top,
                    "Scooter odometer and drive mode are not stacked vertically beside the photo");
            ScrollView viewport = first(card, ScrollView.class);
            Rect visibleBody = new Rect(viewport.getScrollX(), viewport.getScrollY(),
                    viewport.getScrollX() + viewport.getWidth(), viewport.getScrollY() + viewport.getHeight());
            for (Object value : ((Map<?, ?>)field(scooter, "amounts")).values()) {
                View number = (View)value;
                require(number.getParent() instanceof View, "Scooter statistic lost its label container");
                Rect bounds = relativeBounds((View)number.getParent(), body);
                require(visibleBody.contains(bounds), "Default scooter statistic or its label requires scrolling at its minimum full-width size: "
                        + ((TextView)value).getText() + "; item=" + bounds + ", viewport=" + visibleBody
                        + ", card=" + card.getMeasuredWidth() + "x" + card.getMeasuredHeight()
                        + ", body=" + body.getMeasuredWidth() + "x" + body.getMeasuredHeight()
                        + ", textScale=" + activity.ui().textScale);
            }
        } finally {
            for (Map.Entry<View, Integer> entry : visibility.entrySet()) entry.getKey().setVisibility(entry.getValue());
            hint.setVisibility(hintVisibility);
        }
    }
    private void assertBatteryAtMinimum(int columns, float columnWidth, float rowHeight) {
        Object batteryTile = tile("battery"); View card = (View)batteryTile;
        DashboardConfig.Widget widget = (DashboardConfig.Widget)field(batteryTile, "widget");
        measureCard(card, Math.round(columns * columnWidth) - activity.ui().dp(10),
                Math.round(widget.minimumHeight() * rowHeight) - activity.ui().dp(10));
        View body = (View)field(batteryTile, "body"), ring = (View)field(batteryTile, "battery");
        View strip = (View)field(batteryTile, "batteryData"); ScrollView viewport = first(card, ScrollView.class);
        Rect ringBounds = relativeBounds(ring, body), stripBounds = relativeBounds(strip, body);
        require(findText(body, "Battery") == null && field(batteryTile, "subtitle") == null,
                "Battery card repeats its name next to the battery cell");
        // Volt battery cell: a full-width track filling from the bottom, readings underneath.
        require(ringBounds.left <= 1 && Math.abs(ringBounds.width() - body.getWidth()) <= 1,
                "Battery cell does not span its card");
        require(ringBounds.bottom <= stripBounds.top && stripBounds.bottom <= viewport.getHeight() + 1,
                "Battery cell and compact readings do not fit in the minimum full-width card");
        int available = viewport.getHeight() - body.getPaddingTop() - body.getPaddingBottom() - strip.getHeight()
                - ((android.widget.LinearLayout.LayoutParams)strip.getLayoutParams()).topMargin;
        require(ring.getHeight() >= available - 2,
                "Battery cell leaves unused space that could make it taller: actual=" + ring.getHeight()
                        + ", available=" + available + ", viewport=" + viewport.getWidth() + "x" + viewport.getHeight()
                        + ", strip=" + strip.getWidth() + "x" + strip.getHeight());
        require(ring.getContentDescription() != null && ring.getContentDescription().toString().contains("about"),
                "Battery cell does not speak the estimated range it shows");
        List<?> cells = (List<?>)field(strip, "cells");
        require(cells.size() == 3, "Default battery card does not expose its three glanceable readings below the cell");
        String[] icons = {"bolt", "heart", "temperature"};
        android.view.ViewParent readingsRow = null;
        Rect previous = null;
        for (int index = 0; index < cells.size(); index++) {
            View cell = (View)cells.get(index);
            require(findDescription(cell, icons[index]) != null,
                    "Compact battery reading lacks its identifying " + icons[index] + " icon");
            TextView value = first(cell, TextView.class);
            require(value != null && value.getContentDescription() != null
                    && value.getContentDescription().length() > value.length(),
                    "An icon-only battery reading lost its accessible parameter label");
            if (body.getWidth() >= activity.ui().dp(248 * activity.ui().textScale)) {
                Rect bounds = relativeBounds(cell, body);
                if (readingsRow == null) readingsRow = cell.getParent();
                require(cell.getParent() == readingsRow && (previous == null || previous.right <= bounds.left),
                        "Glanceable battery readings do not share one row when space permits");
                previous = bounds;
            }
        }
    }
    private void assertGrid() {
        waitForIdleSync();
        main(() -> {
            ViewGroup grid = grid(); List<Rect> occupied = new ArrayList<>();
            require(grid.getChildCount() == config().widgets.size(), "A configured widget was dropped from the grid");
            for (Object item : tiles()) {
                View tile = (View)item; Rect rect = new Rect(tile.getLeft(), tile.getTop(), tile.getRight(), tile.getBottom());
                require(rect.width() > 0 && rect.height() > 0 && rect.left >= 0 && rect.right <= grid.getWidth() + 1
                        && rect.top >= 0 && rect.bottom <= grid.getHeight(), "Widget rectangle lies outside its grid");
                for (Rect other : occupied) require(!Rect.intersects(rect, other), "Widget cards overlap"); occupied.add(rect);
                ScrollView viewport = first(tile, ScrollView.class);
                require(viewport != null && viewport.getHeight() > 0 && viewport.getChildAt(0).getMeasuredHeight() > 0,
                        "Widget content has no usable scroll viewport");
            }
        });
    }
    private void assertInnerScrolling() {
        Object note = tileById(noteId); ScrollView viewport = first((View)note, ScrollView.class); reveal(viewport);
        main(() -> viewport.scrollTo(0, 0)); awaitSettledLayout();
        ScrollView outer = (ScrollView)field(activity, "scroll");
        final int[] outerBefore = new int[1]; final Rect touchArea = new Rect();
        main(() -> {
            require(viewport.getGlobalVisibleRect(touchArea), "Long-note viewport is not visible for a swipe");
            require(touchArea.height() > activity.ui().dp(48), "Long-note viewport is too small for a real swipe");
            outerBefore[0] = outer.getScrollY();
        });
        float x = touchArea.centerX(), startY = touchArea.bottom - activity.ui().dp(12);
        float endY = touchArea.top + activity.ui().dp(12);
        long down = SystemClock.uptimeMillis();
        injectTouch(down, MotionEvent.ACTION_DOWN, x, startY);
        for (int step = 1; step <= 10; step++) {
            injectTouch(down, MotionEvent.ACTION_MOVE, x, startY + (endY - startY) * step / 10f);
            SystemClock.sleep(20);
        }
        injectTouch(down, MotionEvent.ACTION_UP, x, endY);
        await(() -> viewport.getScrollY() > 0, 3000, "A swipe on the note text did not scroll the widget contents");
        main(() -> {
            require(viewport.getChildAt(0).getHeight() > viewport.getHeight(), "Long note did not exceed its bounded viewport");
            require(outer.getScrollY() == outerBefore[0], "The dashboard intercepted a swipe intended for the widget contents");
            View footer = (View)field(note, "footer");
            require(footer.getBottom() <= ((View)note).getHeight(), "Widget footer escaped its card");
        });
    }
    private void setSize(String id, int width, int height) {
        View corner = findDescription((View)tileById(id), "Resize " + widgetById(id).title);
        require(corner != null, "Resize handle is missing"); click(corner);
        List<NumberPicker> pickers = all(dialogRoot(), NumberPicker.class); require(pickers.size() == 2, "Size options need both dimensions");
        main(() -> { pickers.get(0).setValue(width); pickers.get(1).setValue(height); });
        click(dialog().getButton(AlertDialog.BUTTON_POSITIVE)); waitForIdleSync();
    }
    private void setPosition(String id, int column, int row) {
        View grip = findDescription((View)tileById(id), "Move " + widgetById(id).title);
        require(grip != null, "Move grip is missing"); click(grip); List<EditText> inputs = all(dialogRoot(), EditText.class);
        require(inputs.size() == 2, "Move options need a column and row");
        main(() -> { inputs.get(0).setText(Integer.toString(column)); inputs.get(1).setText(Integer.toString(row)); });
        click(dialog().getButton(AlertDialog.BUTTON_POSITIVE)); waitForIdleSync();
    }
    private void verifyEditAffordances(String id) {
        View tile = (View)tileById(id); String title = widgetById(id).title;
        require(countDescription(tile, "Options for " + title) == 1, "A widget must expose exactly one options menu");
        require(findText(tile, "Options") == null && findText(tile, "Resize") == null,
                "An extra bottom adjustment bar duplicates the corner controls");
        View grip = findDescription(tile, "Move " + title), corner = findDescription(tile, "Resize " + title);
        View options = findDescription(tile, "Options for " + title);
        View duplicate = findDescription(tile, "Duplicate " + title), remove = findDescription(tile, "Remove " + title);
        require(grip != null && corner != null, "Dedicated move and corner resize handles are missing");
        require(options != null && findDescription(options, "gear") != null && all(options, TextView.class).isEmpty(),
                "Widget options need the icon-only gear action");
        require(duplicate != null && findDescription(duplicate, "copy") != null && all(duplicate, TextView.class).isEmpty(),
                "Widget duplication needs the icon-only copy action");
        require(remove != null && findDescription(remove, "trash") != null,
                "Widget removal needs its trash action");
        main(() -> {
            Rect cornerBounds = relativeBounds(corner, tile), gripBounds = relativeBounds(grip, tile),
                    optionsBounds = relativeBounds(options, tile), duplicateBounds = relativeBounds(duplicate, tile),
                    removeBounds = relativeBounds(remove, tile);
            int resizeTouch = activity.ui().dp(48), inlineTouch = Math.min(resizeTouch, tile.getWidth() / 4),
                    tolerance = activity.ui().dp(24);
            require(inlineTouch >= activity.ui().dp(36) - 1, "Inline editor targets fell below the compact size floor");
            require(corner.getWidth() >= resizeTouch && corner.getHeight() >= resizeTouch, "Resize hit area is smaller than 48 dp");
            require(cornerBounds.right >= tile.getWidth() - tolerance && cornerBounds.bottom >= tile.getHeight() - tolerance,
                    "Resize target is not in the widget's bottom-right corner");
            require(gripBounds.left == 0 && gripBounds.top == 0,
                    "Move grip is not in the widget's top-left corner");
            require(optionsBounds.left == gripBounds.right && duplicateBounds.left == optionsBounds.right
                            && optionsBounds.top == gripBounds.top && duplicateBounds.top == gripBounds.top,
                    "Gear and copy actions must share one inline row beside the move grip");
            require(removeBounds.right == tile.getWidth() && removeBounds.top == gripBounds.top,
                    "Trash action is not in the widget's top-right corner");
            List<Rect> occupied = new ArrayList<>();
            for (View control : new View[] {grip, options, duplicate, remove}) {
                require(control.getWidth() == inlineTouch && control.getHeight() == inlineTouch,
                        "Inline editor controls must have equal square targets");
                require(control.isClickable() && control.isFocusable() && control.getContentDescription() != null,
                        "Icon-only controls lost their accessible action");
                Rect bounds = relativeBounds(control, tile);
                for (Rect other : occupied) require(!Rect.intersects(bounds, other), "Inline editor targets overlap");
                occupied.add(bounds);
                require(translucentBackground(control), "An editor overlay background is fully opaque");
            }
        });
        click(corner);
        require(all(dialogRoot(), NumberPicker.class).size() == 2 && findText(dialogRoot(), "Color") == null,
                "Tapping the resize handle must open size, not the options menu");
        closeDialog();
        click(grip);
        require(all(dialogRoot(), EditText.class).size() == 2 && findText(dialogRoot(), "Color") == null,
                "Tapping the move grip must open accessible placement, not widget options");
        closeDialog();
    }
    private boolean translucentBackground(View view) {
        android.graphics.drawable.Drawable background = view.getBackground();
        if (background == null) return true;
        Bitmap sample = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888);
        Rect original = new Rect(background.getBounds());
        try {
            background.setBounds(0, 0, 20, 20); background.draw(new android.graphics.Canvas(sample));
            return Color.alpha(sample.getPixel(10, 10)) < 255;
        } finally { background.setBounds(original); sample.recycle(); }
    }
    private Rect relativeBounds(View child, View ancestor) {
        Rect rect = new Rect(0, 0, child.getWidth(), child.getHeight());
        ((ViewGroup)ancestor).offsetDescendantRectToMyCoords(child, rect); return rect;
    }
    private int countDescription(View root, String description) {
        int count = description.contentEquals(root.getContentDescription() == null ? "" : root.getContentDescription()) ? 1 : 0;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)root).getChildCount(); i++)
            count += countDescription(((ViewGroup)root).getChildAt(i), description);
        return count;
    }
    private void verifyOptionsContrast(String id) {
        openOptions(id);
        main(() -> {
            TextView label = findText(dialogRoot(), "Title");
            TextView title = dialogRoot().findViewWithTag("widget-options-title");
            TextView save = findText(dialogRoot(), "✓ Save");
            View close = findDescription(dialogRoot(), "Close widget options");
            require(title != null && title.isShown() && label != null && save != null && save.isShown()
                            && close != null && close.isShown(),
                    "Options dialog title, labels, Save and close actions must be visible");
            require(findText(dialogRoot(), "Duplicate") == null && findText(dialogRoot(), "Remove") == null
                            && findText(dialogRoot(), "Remove widget") == null,
                    "Widget options still duplicate the editor's copy/remove actions");
            for (TextView text : new TextView[] {title, label})
                require(contrast(text.getCurrentTextColor(), activity.ui().surface) >= 4.5,
                        "Options dialog text has insufficient contrast in " + (activity.ui().dark ? "dark" : "light") + " theme: " + text.getText());
            require(contrast(save.getCurrentTextColor(), activity.ui().accent) >= 4.5,
                    "Save text has insufficient contrast against its accent button");
        });
        closeDialog();
    }
    private double contrast(int foreground, int background) {
        double a = luminance(foreground), b = luminance(background); return (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
    }
    private double luminance(int color) {
        return .2126 * linear(Color.red(color)) + .7152 * linear(Color.green(color)) + .0722 * linear(Color.blue(color));
    }
    private double linear(int channel) { double c = channel / 255d; return c <= .04045 ? c / 12.92 : Math.pow((c + .055) / 1.055, 2.4); }
    private void resizeByOneRow(String id) {
        DashboardConfig.Widget widget = widgetById(id); int height = cell(id).h;
        View resize = findDescription((View)tileById(id), "Resize " + widget.title); require(resize != null, "Resize handle is missing"); reveal(resize);
        awaitSettledLayout();
        await(() -> resize.hasWindowFocus() && resize.isShown(), 3000, "Resize handle did not regain window focus after its dialog");
        main(() -> {
            float delta = ((Number)invoke(grid(), "rowHeight")).floatValue() * 1.1f;
            long now = SystemClock.uptimeMillis(); float x = resize.getWidth() / 2f, y = resize.getHeight() / 2f;
            MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
            MotionEvent move = MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_MOVE, x, y + delta, 0);
            MotionEvent up = MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, x, y + delta, 0);
            try { require(resize.dispatchTouchEvent(down), "Resize did not accept touch"); resize.dispatchTouchEvent(move); resize.dispatchTouchEvent(up); }
            finally { down.recycle(); move.recycle(); up.recycle(); }
        });
        await(() -> cell(id).h == height + 1, 3000, "Resize gesture did not add a row"); waitForIdleSync();
    }
    private void prepareDragFixture(String id) {
        // Two neighboring cards guarantee that the preview must show a real reflow.
        int columns = ((Number)field(grid(), "columns")).intValue();
        require(columns >= 2, "Gesture fixture requires at least two dashboard columns");
        String neighbor = widget("note").id;
        setSize(id, 1, 3); setSize(neighbor, 1, 3);
        setPosition(id, 1, 1); setPosition(neighbor, 2, 1);
        scrollHomeTop(); awaitSettledLayout(); assertGrid();
        require(cell(id).x == 0 && cell(id).y == 0 && cell(neighbor).x == 1 && cell(neighbor).y == 0,
                "Gesture fixture did not put the two cards beside one another");
    }
    private Map<String, DashboardLayout.Cell> layoutSnapshot() {
        Map<String, DashboardLayout.Cell> snapshot = new LinkedHashMap<>();
        for (DashboardConfig.Widget widget : config().widgets) snapshot.put(widget.id, cell(widget.id).copy());
        return snapshot;
    }
    private boolean sameCell(DashboardLayout.Cell left, DashboardLayout.Cell right) {
        return left != null && right != null && left.x == right.x && left.y == right.y && left.w == right.w && left.h == right.h;
    }
    private boolean sameLayout(Map<String, DashboardLayout.Cell> snapshot) {
        for (Map.Entry<String, DashboardLayout.Cell> entry : snapshot.entrySet())
            if (!sameCell(entry.getValue(), cell(entry.getKey()))) return false;
        return true;
    }
    private String savedLayout() { return settings().getString("dashboard_v1_" + profileId, ""); }
    private void awaitSettledLayout() {
        await(() -> root().hasWindowFocus(), 3000, "Dashboard did not regain window focus after its dialog");
        final long[] stableSince = {SystemClock.elapsedRealtime()};
        await(() -> {
            boolean settled = !grid().isLayoutRequested();
            for (Object object : tiles()) {
                View view = (View)object;
                if (Math.abs(view.getTranslationX()) > .1f || Math.abs(view.getTranslationY()) > .1f
                        || Math.abs(view.getScaleX() - 1) > .001f || Math.abs(view.getScaleY() - 1) > .001f) settled = false;
            }
            if (!settled) stableSince[0] = SystemClock.elapsedRealtime();
            // A pending layout can start a ViewPropertyAnimator on the next frame.
            // Require a quiet interval instead of accepting one pre-animation frame.
            return settled && SystemClock.elapsedRealtime() - stableSince[0] >= 240;
        }, 3000, "Widget reflow animation did not settle");
    }
    private void verifyCancelledDrag(String id) { prepareDragFixture(id); touchscreenDrag(id, true); }
    private void directDragToNextCell(String id) { touchscreenDrag(id, false); }
    private void touchscreenDrag(String id, boolean cancel) {
        scrollHomeTop();
        DashboardLayout.Cell before = cell(id).copy();
        String neighborId = widget("note").id;
        Map<String, DashboardLayout.Cell> snapshot = layoutSnapshot();
        String savedBefore = savedLayout();
        View grip = findDescription((View)tileById(id), "Move " + widgetById(id).title);
        require(grip != null, "Touchscreen move grip is missing"); reveal(grip);
        awaitSettledLayout();
        final float[] position = new float[4];
        main(() -> {
            int[] source = new int[2]; grip.getLocationOnScreen(source);
            float columnWidth = ((Number)invoke(grid(), "columnWidth")).floatValue();
            position[0] = source[0] + grip.getWidth() / 2f; position[1] = source[1] + grip.getHeight() / 2f;
            position[2] = position[0] + columnWidth; position[3] = position[1];
            Rect visible = new Rect(); require(grid().getGlobalVisibleRect(visible), "Drag grid is not visible");
            require(visible.contains((int)position[2], (int)position[3]), "Touchscreen drag destination lies outside the visible grid");
            require(grip.getGlobalVisibleRect(new Rect()), "Touchscreen move grip is not visible");
        });
        AtomicReference<WindowTouchTrace> trace = new AtomicReference<>();
        main(() -> trace.set(new WindowTouchTrace(activity.getWindow())));
        long downTime = SystemClock.uptimeMillis(); boolean released = false;
        try {
            injectTouch(downTime, MotionEvent.ACTION_DOWN, position[0], position[1]);
            // Deliberately no long hold: motion past touch slop must start the move.
            for (int step = 1; step <= 8; step++) {
                float fraction = step / 8f;
                injectTouch(downTime, MotionEvent.ACTION_MOVE, position[0] + (position[2] - position[0]) * fraction, position[1]);
                SystemClock.sleep(16);
            }
            try {
                await(() -> field(grid(), "gestureTile") == tileById(id) && cell(id).x == 1 && cell(id).y == 0,
                        2500, "Direct touchscreen drag did not preview the requested placement");
            } catch (IllegalStateException error) {
                // Capture live state before finally sends CANCEL and restores the layout.
                throw new IllegalStateException(error.getMessage() + "; " + dragDiagnostics(id, position, cancel)
                        + "; " + trace.get().describe(), error);
            }
            main(() -> {
                DashboardLayout.Cell preview = (DashboardLayout.Cell)invoke(grid(), "previewCell");
                require(preview != null && preview.w == before.w && preview.h == before.h && preview.x == 1 && preview.y == 0,
                        "Drag preview does not represent the full target rectangle");
                require(((List<?>)field(grid(), "gestureSnapshot")).size() == config().widgets.size(),
                        "Move did not retain an original placement for every widget");
                require(!sameCell(snapshot.get(neighborId), cell(neighborId)),
                        "Overlapping neighbor did not reflow before release");
                require(savedBefore.equals(savedLayout()), "A preview was saved before a successful release");
            });
            assertGrid();
            injectTouch(downTime, cancel ? MotionEvent.ACTION_CANCEL : MotionEvent.ACTION_UP, position[2], position[3]); released = true;
            await(() -> field(grid(), "gestureTile") == null, 3000, "Touchscreen drag did not finish");
            if (cancel) {
                main(() -> {
                    require(sameLayout(snapshot), "Cancelled drag did not restore every widget rectangle");
                    require(savedBefore.equals(savedLayout()), "Cancelled drag changed saved preferences");
                });
            } else {
                main(() -> {
                    require(cell(id).x == 1 && cell(id).y == 0, "Drop did not keep the preview placement");
                    DashboardConfig restored = new DashboardConfig(settings(), profileId);
                    DashboardLayout.Cell saved = null;
                    for (DashboardConfig.Widget widget : restored.widgets) if (widget.id.equals(id))
                        saved = widget.layouts.get(((Number)field(grid(), "columns")).intValue());
                    require(sameCell(saved, cell(id)), "Dropped placement was not saved");
                });
            }
        } finally {
            try { if (!released) injectTouch(downTime, MotionEvent.ACTION_CANCEL, position[0], position[1]); }
            finally { main(() -> trace.get().restore()); }
        }
        waitForIdleSync();
    }
    private String dragDiagnostics(String id, float[] injected, boolean cancel) {
        AtomicReference<String> details = new AtomicReference<>();
        main(() -> {
            Object target = tileById(id), active = field(grid(), "gestureTile");
            View targetView = (View)target;
            int[] location = new int[2]; targetView.getLocationOnScreen(location);
            DashboardLayout.Cell preview = (DashboardLayout.Cell)invoke(grid(), "previewCell");
            DashboardLayout.Cell origin = (DashboardLayout.Cell)field(grid(), "gestureOrigin");
            details.set("attempt=" + (cancel ? "cancel-preview" : "commit-drop")
                    + ", expected=[1,0," + cell(id).w + "," + cell(id).h + "]"
                    + ", actual=" + describeCell(cell(id)) + ", preview=" + describeCell(preview)
                    + ", gestureTile=" + (active == null ? "null" : ((DashboardConfig.Widget)field(active, "widget")).id)
                    + ", wantedTile=" + id + ", origin=" + describeCell(origin)
                    + ", windowFocus=" + root().hasWindowFocus() + "/" + targetView.hasWindowFocus()
                    + ", columns=" + field(grid(), "columns") + ", columnWidth=" + invoke(grid(), "columnWidth")
                    + ", injectedStart=[" + injected[0] + "," + injected[1] + "]"
                    + ", injectedEnd=[" + injected[2] + "," + injected[3] + "]"
                    + ", raw=[" + field(grid(), "rawX") + "," + field(grid(), "rawY") + "]"
                    + ", tileScreen=[" + location[0] + "," + location[1] + "]"
                    + ", translation=[" + targetView.getTranslationX() + "," + targetView.getTranslationY() + "]"
                    + ", scrollY=" + ((ScrollView)field(activity, "scroll")).getScrollY());
        });
        return details.get();
    }
    private String describeCell(DashboardLayout.Cell cell) {
        return cell == null ? "null" : "[" + cell.x + "," + cell.y + "," + cell.w + "," + cell.h + "]";
    }
    private void verifyLongPressCancel(String id) {
        Map<String, DashboardLayout.Cell> snapshot = layoutSnapshot(); String saved = savedLayout();
        View grip = findDescription((View)tileById(id), "Move " + widgetById(id).title); reveal(grip);
        awaitSettledLayout();
        final float[] position = new float[2];
        main(() -> { int[] origin = new int[2]; grip.getLocationOnScreen(origin);
            position[0] = origin[0] + grip.getWidth() / 2f; position[1] = origin[1] + grip.getHeight() / 2f; });
        long now = SystemClock.uptimeMillis();
        try {
            injectTouch(now, MotionEvent.ACTION_DOWN, position[0], position[1]);
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 120L);
            await(() -> field(grid(), "gestureTile") == tileById(id), 2000, "A long press on the grip did not also start movement");
        } finally { injectTouch(now, MotionEvent.ACTION_CANCEL, position[0], position[1]); }
        await(() -> field(grid(), "gestureTile") == null, 3000, "Long-press cancellation did not finish");
        main(() -> require(sameLayout(snapshot) && saved.equals(savedLayout()), "Cancelling a held grip changed the layout"));
    }
    private void injectTouch(long downTime, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { require(getUiAutomation().injectInputEvent(event, true), "Emulator rejected a native touch event"); }
        finally { event.recycle(); }
    }
    private void verifyGaugeUnits() {
        String id = widget("gauge").id;
        openOptions(id); clickText(dialogRoot(), "Parameter");
        main(() -> first(dialogRoot(), EditText.class).setText("speed_kmh"));
        clickText(dialogRoot(), metricLabel("speed_kmh"));
        openOptions(id); clickText(dialogRoot(), "Scale maximum");
        main(() -> first(dialogRoot(), EditText.class).setText("20"));
        click(dialog().getButton(AlertDialog.BUTTON_POSITIVE));
        require(Math.abs(widgetById(id).maximum - 20 * 1.609344) < .000001, "mph gauge maximum was not stored in canonical units");
        openOptions(id);
        require(findText(dialogRoot(), "20.00 mph") != null, "Gauge scale summary does not follow mph units");
        clickText(dialogRoot(), "Scale maximum");
        require(Math.abs(Double.parseDouble(first(dialogRoot(), EditText.class).getText().toString()) - 20) < .000001,
                "Gauge scale editor did not convert the saved maximum back to mph");
        click(dialog().getButton(AlertDialog.BUTTON_POSITIVE));
        require(Math.abs(widgetById(id).maximum - 20 * 1.609344) < .000001, "Saving an unchanged mph scale changed its range");
        main(() -> settings().edit().putString("units", "km").apply());
        await(() -> dashboard().isEditing() && settings().getString("units", "").equals("km") && grid().getWidth() > 0,
                4000, "Metric dashboard did not rebuild"); waitForIdleSync();
        openOptions(id); require(findText(dialogRoot(), "32.19 km/h") != null, "Gauge scale summary does not convert to km/h"); closeDialog();
        require(Math.abs(widgetById(id).maximum - 20 * 1.609344) < .000001, "Changing display units altered the gauge range");
        main(() -> settings().edit().putString("units", "mi").apply()); waitForIdleSync();
        awaitDashboard();
    }
    private void verifyTwoMinuteChart() {
        String id = widget("live").id;
        Map<String, DashboardLayout.Cell> before = layoutSnapshot();
        openOptions(id); clickText(dialogRoot(), "2 min"); closeDialog();
        require(widgetById(id).windowMs == 120000L, "Two-minute chart window was not selected");
        DashboardConfig saved = new DashboardConfig(settings(), profileId);
        DashboardConfig.Widget restored = null;
        for (DashboardConfig.Widget widget : saved.widgets) if (widget.id.equals(id)) restored = widget;
        require(restored != null && restored.windowMs == 120000L, "Two-minute chart window did not persist");
        require(sameLayout(before), "Changing the chart window moved or resized a widget");
        main(() -> {
            for (TextView text : all((View)tileById(id), TextView.class)) if (text.isShown()) {
                String value = text.getText().toString().toLowerCase(Locale.ROOT);
                require(!value.contains("signed") && !value.contains("recent readings"),
                        "Live chart retained redundant explanatory text: " + text.getText());
            }
        });
    }
    private void verifyCapacityExperiment() {
        String id = widget("capacity").id;
        require(widgetById(id).title.equals("Capacity experiment"), "Capacity widget default title is missing");
        await(() -> "Not enough ride data".contentEquals(((TextView)field(tileById(id), "capacityValue")).getText()),
                5000, "Capacity widget invented an estimate without recorded rides");
        openOptions(id);
        main(() -> first(dialogRoot(), EditText.class).setText("Synthetic capacity experiment"));
        closeDialog();
        openOptions(id); clickText(dialogRoot(), "30 days"); closeDialog();
        require(widgetById(id).windowMs == 30 * 86400000L, "Capacity time window choice was not applied");
        openOptions(id); clickText(dialogRoot(), "Advertised capacity");
        main(() -> first(dialogRoot(), EditText.class).setText("800"));
        click(dialog().getButton(AlertDialog.BUTTON_POSITIVE));
        require(widgetById(id).advertisedWh == 800d, "Advertised capacity choice was not applied");
        DashboardConfig saved = new DashboardConfig(settings(), profileId); DashboardConfig.Widget restored = null;
        for (DashboardConfig.Widget widget : saved.widgets) if (widget.id.equals(id)) restored = widget;
        require(restored != null && restored.advertisedWh == 800d && restored.windowMs == 30 * 86400000L
                && restored.title.equals("Synthetic capacity experiment"), "Capacity options were not persisted");
        await(() -> "Not enough ride data".contentEquals(((TextView)field(tileById(id), "capacityValue")).getText()),
                5000, "Configuring advertised Wh created a capacity measurement without recorded rides");
    }
    private void verifyChargingDisplay() {
        main(() -> {
            Object chargeTile = tile("charging"), batteryTile = tile("battery");
            Map<String, TelemetryHistory.Reading> cached = activity.service().displayReadings();
            for (Object tile : new Object[] {chargeTile, batteryTile}) {
                invoke(tile, "refreshCharging", new Class<?>[] {Map.class}, cached);
                require("—".contentEquals(((TextView)field(tile, "chargingWatts")).getText())
                        && "—".contentEquals(((TextView)field(tile, "chargingEta")).getText()),
                        "Stale cached charging values produced a live power or time estimate");
                require(((TextView)field(tile, "chargingWatts")).getCurrentTextColor() == activity.ui().muted,
                        "Unavailable charging power is styled as live");
            }
            assertBatteryMode(batteryTile, false, "Cached charging state");
            // These readings go directly to the view formatter, never the service,
            // BLE controller, cache or hardware. Restore the real stale display in finally.
            Map<String, TelemetryHistory.Reading> preview = new LinkedHashMap<>(cached);
            long wall = System.currentTimeMillis();
            syntheticReading(preview, "charging_state", "Charging", "", 1d, wall);
            syntheticReading(preview, "voltage_v", "52 V", "V", 52d, wall);
            syntheticReading(preview, "current_a", "-2 A", "A", -2d, wall);
            syntheticReading(preview, "battery_percent", "50 %", "%", 50d, wall);
            syntheticReading(preview, "rated_capacity_ah", "14.5 Ah", "Ah", 14.5d, wall);
            syntheticReading(preview, "charge_limit_percent", "80 %", "%", 80d, wall);
            try {
                for (Object tile : new Object[] {chargeTile, batteryTile}) {
                    invoke(tile, "refreshCharging", new Class<?>[] {Map.class}, preview);
                    require("104 W".contentEquals(((TextView)field(tile, "chargingWatts")).getText()),
                            "Charge widget did not format battery-side V × |I|");
                    require(((TextView)field(tile, "chargingEtaLabel")).getText().toString().contains("80%"),
                            "ETA label ignored the configured charge cutoff");
                    require("≈ 2h 11m".contentEquals(((TextView)field(tile, "chargingEta")).getText()),
                            "ETA did not use the remaining Ah to the cutoff");
                }
                assertBatteryMode(batteryTile, true, "Fresh active charging state");
                require("2.00 A".contentEquals(((TextView)field(chargeTile, "chargingAmps")).getText()),
                        "Detailed charging card lost its measured current");
                preview.remove("charge_limit_percent");
                invoke(chargeTile, "refreshCharging", new Class<?>[] {Map.class}, preview);
                require("Time to full".contentEquals(((TextView)field(chargeTile, "chargingEtaLabel")).getText())
                        && "≈ 3h 38m".contentEquals(((TextView)field(chargeTile, "chargingEta")).getText()),
                        "Unknown cutoff did not keep the hypothetical full estimate separate");
                require(((TextView)field(chargeTile, "chargingNote")).getText().toString().contains("cutoff unknown"),
                        "Unknown charge cutoff was not explained");
                TelemetryHistory.Reading current = preview.get("current_a");
                preview.put("current_a", current.stale());
                invoke(chargeTile, "refreshCharging", new Class<?>[] {Map.class}, preview);
                require("—".contentEquals(((TextView)field(chargeTile, "chargingWatts")).getText())
                        && "—".contentEquals(((TextView)field(chargeTile, "chargingEta")).getText()),
                        "One stale electrical input did not suppress the charging estimate");
                syntheticReading(preview, "charging_state", "Not charging", "", 0d, wall);
                invoke(batteryTile, "refreshCharging", new Class<?>[] {Map.class}, preview);
                assertBatteryMode(batteryTile, false, "Fresh stopped charging state");
                syntheticReading(preview, "charging_state", "Charging", "", 1d, wall);
                invoke(batteryTile, "refreshCharging", new Class<?>[] {Map.class}, preview);
                assertBatteryMode(batteryTile, true, "Charging state returns while current is unavailable");
                preview.put("charging_state", preview.get("charging_state").stale());
                invoke(batteryTile, "refreshCharging", new Class<?>[] {Map.class}, preview);
                assertBatteryMode(batteryTile, false, "Previously active charging state becomes stale");
            } finally {
                invoke(chargeTile, "refreshCharging", new Class<?>[] {Map.class}, cached);
                invoke(batteryTile, "refreshCharging", new Class<?>[] {Map.class}, cached);
            }
        });
    }
    private void assertBatteryMode(Object batteryTile, boolean charging, String context) {
        View normal = (View)field(batteryTile, "batteryData"), details = (View)field(batteryTile, "chargingData");
        require(normal.getVisibility() == (charging ? View.GONE : View.VISIBLE)
                && details.getVisibility() == (charging ? View.VISIBLE : View.GONE),
                context + " did not exclusively select the correct battery details");
        require(((View)field(batteryTile, "battery")).getVisibility() == View.VISIBLE,
                context + " hid the shared battery percentage ring");
    }
    private void verifyLiveFooters() {
        main(() -> {
            Map<String, TelemetryHistory.Reading> cached = activity.service().displayReadings();
            Map<String, TelemetryHistory.Reading> preview = new LinkedHashMap<>();
            for (TelemetryHistory.Reading reading : cached.values()) {
                preview.put(reading.key, new TelemetryHistory.Reading(reading.key, reading.label, reading.group,
                        reading.value, reading.unit, reading.number, System.currentTimeMillis(), true));
            }
            try {
                for (Object tile : tiles()) {
                    invoke(tile, "refresh", new Class<?>[] {Map.class}, preview);
                    TextView footer = (TextView)field(tile, "footer");
                    require(!footer.isShown() || !footer.getText().toString().toLowerCase(Locale.ROOT).contains("live"),
                            "A current widget repeats its live status: " + footer.getText());
                }
            } finally {
                for (Object tile : tiles()) invoke(tile, "refresh", new Class<?>[] {Map.class}, cached);
            }
        });
    }
    private void chooseDialogItem(String label) {
        ListView list = dialog().getListView(); require(list != null, "Expected a choice list");
        int found = -1;
        for (int i = 0; i < list.getAdapter().getCount(); i++) if (label.equals(String.valueOf(list.getAdapter().getItem(i)))) { found = i; break; }
        require(found >= 0, "Missing dialog choice " + label); final int index = found;
        main(() -> list.setSelection(index)); waitForIdleSync();
        await(() -> list.getChildAt(index - list.getFirstVisiblePosition()) != null, 3000, "Dialog choice did not become visible");
        main(() -> require(list.performItemClick(list.getChildAt(index - list.getFirstVisiblePosition()), index,
                list.getAdapter().getItemId(index)), "Dialog choice did not dispatch")); waitForIdleSync();
    }
    private void openOptions(String id) {
        Object tile = tileById(id); DashboardConfig.Widget widget = widgetById(id);
        View options = findDescription((View)tile, "Options for " + widget.title); require(options != null, "Widget options are missing"); click(options);
    }
    private void closeDialog() {
        View close = findDescription(dialogRoot(), "Close widget options");
        click(close != null ? close : dialog().getButton(AlertDialog.BUTTON_NEGATIVE));
    }
    private AlertDialog dialog() { AlertDialog dialog = (AlertDialog)field(dashboard(), "dialog"); require(dialog != null && dialog.isShowing(), "Dashboard dialog is missing"); return dialog; }
    private View dialogRoot() { return dialog().getWindow().getDecorView(); }
    private DashboardScreen dashboard() { return (DashboardScreen)field(activity, "screen"); }
    private DashboardConfig config() { return (DashboardConfig)field(dashboard(), "config"); }
    private ViewGroup grid() { return (ViewGroup)field(dashboard(), "grid"); }
    private List<?> tiles() { return (List<?>)field(dashboard(), "tiles"); }
    private Set<String> types() { Set<String> result = new HashSet<>(); for (DashboardConfig.Widget widget : config().widgets) result.add(widget.type); return result; }
    private DashboardConfig.Widget widget(String type) { for (DashboardConfig.Widget widget : config().widgets) if (widget.type.equals(type)) return widget; throw new IllegalStateException("Missing widget type " + type); }
    private DashboardConfig.Widget widgetById(String id) { for (DashboardConfig.Widget widget : config().widgets) if (widget.id.equals(id)) return widget; throw new IllegalStateException("Missing widget ID"); }
    private DashboardLayout.Cell cell(String id) { DashboardLayout.Cell cell = widgetById(id).layouts.get(((Number)field(grid(), "columns")).intValue()); require(cell != null, "Widget has no current rectangle"); return cell; }
    private Object tileOrNull(String type) { for (Object tile : tiles()) if (((DashboardConfig.Widget)field(tile, "widget")).type.equals(type)) return tile; return null; }
    private Object tile(String type) { Object tile = tileOrNull(type); require(tile != null, "Missing tile " + type); return tile; }
    private Object tileById(String id) { for (Object tile : tiles()) if (((DashboardConfig.Widget)field(tile, "widget")).id.equals(id)) return tile; throw new IllegalStateException("Missing tile ID"); }
    private String metricLabel(String key) { for (Telemetry.Metric metric : new Telemetry().metrics()) if (metric.key.equals(key)) return metric.label; throw new IllegalStateException("Unknown parameter"); }
    private void scrollHomeTop() { main(() -> ((ScrollView)field(activity, "scroll")).scrollTo(0, 0)); waitForIdleSync(); }
    private void assertNavigationFits() {
        main(() -> { for (String name : DESTINATIONS) {
            View item = findDescription(root(), name); require(item != null && item.isShown(), "Missing navigation " + name);
            Rect visible = new Rect(); require(item.getGlobalVisibleRect(visible) && visible.width() >= item.getWidth() - 2
                    && visible.height() >= item.getHeight() - 2, "Navigation target is clipped: " + name);
            TextView caption = first(item, TextView.class);
            if (caption != null) require(caption.getLayout() != null
                    && caption.getLayout().getHeight() <= caption.getHeight() + 2, "Navigation caption is clipped: " + name);
            else { int minimum = Math.round(48 * item.getResources().getDisplayMetrics().density);
                // The compact floating tab bar is icon-only; each target must still be 48dp.
                require(item.getWidth() >= minimum && item.getHeight() >= minimum, "Navigation target smaller than 48dp: " + name); }
        }});
    }
    private void clickDescription(String label) { View target = findDescription(root(), label); require(target != null, "Missing navigation " + label); click(target); }
    private void clickText(View tree, String label) {
        TextView target = findActionableText(tree, tree, label);
        require(target != null, "Missing enabled UI action " + label); click(target);
    }
    private void click(View target) {
        View actionable = target; while (!actionable.isClickable() && actionable.getParent() instanceof View) actionable = (View)actionable.getParent();
        final View button = actionable; require(button.isClickable() && button.isEnabled(), "UI action is disabled"); reveal(button);
        main(() -> require(button.performClick(), "UI action did not dispatch")); waitForIdleSync();
    }
    private void reveal(View view) { main(() -> view.requestRectangleOnScreen(new Rect(0, 0, view.getWidth(), view.getHeight()), true)); waitForIdleSync(); }
    private View root() { return (View)field(activity, "root"); }
    private SharedPreferences settings() { return getTargetContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE); }
    private void screenshot(String name) throws Exception {
        waitForIdleSync(); SystemClock.sleep(120); Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "Screenshot unavailable");
        String variant = arguments.getString("variant", "dashboard").replaceAll("[^A-Za-z0-9_-]", "");
        File directory = new File(getTargetContext().getExternalFilesDir(null), "dashboard-validation");
        require(directory.isDirectory() || directory.mkdirs(), "Screenshot directory could not be created");
        File output = new File(directory, variant + "-" + name + ".png");
        try (FileOutputStream stream = new FileOutputStream(output)) { require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream), "Screenshot encoding failed"); }
        finally { bitmap.recycle(); } screenshots.add(output.getAbsolutePath());
    }
    private void finishActivity() {
        if (activity != null && !activity.isDestroyed()) { MainActivity closing = activity; main(closing::finish); await(closing::isDestroyed, 4000, "Activity did not finish"); }
    }
    private void cleanup() {
        if (!admitted) return; if (fixtureHistory != null) { fixtureHistory.close(); fixtureHistory = null; }
        // The admission gate guarantees disposable app data. Keep evidence for retrieval;
        // the caller clears the emulator package before the next variant.
        finishActivity(); main(() -> { ScooterService.setUiVisible(false); getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); });
        waitForIdleSync(); admitted = false;
    }
    private void await(BooleanSupplier condition, long timeout, String message) {
        long until = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < until) { AtomicReference<Boolean> ready = new AtomicReference<>(false); main(() -> ready.set(condition.getAsBoolean())); if (ready.get()) return; SystemClock.sleep(40); }
        throw new IllegalStateException(message);
    }
    private void main(Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>(); runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new IllegalStateException(failure.get().getMessage(), failure.get());
    }
    private static Object field(Object value, String name) {
        require(value != null, "Null object while inspecting " + name);
        try { Field field = value.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(value); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot inspect " + name, error); }
    }
    private static void setField(Object value, String name, Object replacement) {
        try { Field field = value.getClass().getDeclaredField(name); field.setAccessible(true); field.set(value, replacement); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot set synthetic field " + name, error); }
    }
    private static Object invoke(Object value, String name) {
        return invoke(value, name, new Class<?>[0]);
    }
    private static Object invoke(Object value, String name, Class<?>[] signature, Object... args) {
        try { Method method = value.getClass().getDeclaredMethod(name, signature); method.setAccessible(true); return method.invoke(value, args); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot invoke " + name + ": " + error.getCause(), error); }
    }
    private static View findDescription(View root, String description) {
        if (root.isShown() && description.contentEquals(root.getContentDescription() == null ? "" : root.getContentDescription())) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)root).getChildCount(); i++) { View found = findDescription(((ViewGroup)root).getChildAt(i), description); if (found != null) return found; }
        return null;
    }
    private static TextView findText(View root, String text) {
        if (root.isShown() && root instanceof TextView && text.contentEquals(((TextView)root).getText())) return (TextView)root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)root).getChildCount(); i++) { TextView found = findText(((ViewGroup)root).getChildAt(i), text); if (found != null) return found; }
        return null;
    }
    private static TextView findActionableText(View boundary, View candidate, String text) {
        if (candidate.isShown() && candidate instanceof TextView && !(candidate instanceof EditText)
                && text.contentEquals(((TextView)candidate).getText())) {
            View action = candidate;
            while (action != boundary) {
                if (action.isClickable()) {
                    // A setting row's trailing value can equal another row's label
                    // (e.g. Title = Note). Only its leading label identifies the action.
                    if (action.isEnabled() && (action == candidate || first(action, TextView.class) == candidate))
                        return (TextView)candidate;
                    break;
                }
                if (!(action.getParent() instanceof View)) break;
                action = (View)action.getParent();
            }
        }
        if (candidate instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)candidate).getChildCount(); i++) {
            TextView found = findActionableText(boundary, ((ViewGroup)candidate).getChildAt(i), text);
            if (found != null) return found;
        }
        return null;
    }
    private static <T> T first(View root, Class<T> type) { List<T> found = all(root, type); return found.isEmpty() ? null : found.get(0); }
    private static <T> List<T> all(View root, Class<T> type) { List<T> result = new ArrayList<>(); collect(root, type, result); return result; }
    private static <T> void collect(View root, Class<T> type, List<T> result) {
        if (type.isInstance(root)) result.add(type.cast(root));
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)root).getChildCount(); i++) collect(((ViewGroup)root).getChildAt(i), type, result);
    }
    private void check(String message) { checks.add(message); Bundle status = new Bundle(); status.putString("check", message); sendStatus(0, status); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
