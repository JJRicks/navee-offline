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
    private static final String[] DESTINATIONS = {"Home", "Scooter", "Stats", "Rides", "More"};
    private static final String FIXTURE_NAME = "Synthetic dashboard fixture";
    private static final String RENAMED = "Synthetic renamed scooter";
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
            seedSyntheticProfileAndHistory(); launch();
            for (String destination : DESTINATIONS) { clickDescription(destination); assertNavigationFits(); assertNoScooterWork(); }
            clickDescription("Home"); awaitDashboard();
            main(() -> require(types().equals(new HashSet<>(Arrays.asList("lock", "scooter", "battery", "capacity", "quick", "live", "ride"))),
                    "Fresh Home did not provide the default dashboard widgets"));
            assertGrid(); scrollHomeTop(); screenshot("default-dark");
            check("Five destinations and the default dashboard load with usable navigation and non-overlapping cards");

            await(() -> activity.service().displayReadings().get("battery_percent").number != null, 5000,
                    "Persisted synthetic readings did not load");
            assertCachedReadings();
            clickDescription("Scooter"); clickText(root(), "Scooter name");
            AlertDialog rename = (AlertDialog)field(activity, "controlDialog");
            main(() -> first(rename.getWindow().getDecorView(), EditText.class).setText(RENAMED));
            click(rename.getButton(AlertDialog.BUTTON_POSITIVE));
            main(() -> require(RENAMED.equals(new ScooterProfiles(getTargetContext()).active().name), "Scooter name was not saved"));
            clickDescription("Home"); awaitDashboard();
            main(() -> require(RENAMED.contentEquals(((TextView)field(tile("scooter"), "photoName")).getText()),
                    "Dashboard scooter card did not update its name"));
            check("Synthetic numeric and text readings return as stale; scooter name changes persist and update Home");

            clickText(root(), "Edit"); await(() -> dashboard().isEditing(), 3000, "Edit mode did not open");
            assertEditInputsInert(); screenshot("edit-dark");
            Set<String> existing = types();
            for (int i = 0; i < DashboardConfig.TYPES.length; i++) {
                String type = DashboardConfig.TYPES[i];
                if (existing.contains(type)) continue;
                clickText(root(), "Add widget"); clickText(dialogRoot(), DashboardConfig.NAMES[i]); closeDialog();
                await(() -> tileOrNull(type) != null && ((View)tile(type)).getWidth() > 0, 4000, "Added widget did not lay out: " + type);
                assertGrid(); assertEditInputsInert();
            }
            main(() -> require(types().equals(new HashSet<>(Arrays.asList(DashboardConfig.TYPES))) && config().widgets.size() == DashboardConfig.TYPES.length,
                    "Palette did not construct every widget type exactly once"));
            check("All " + DashboardConfig.TYPES.length + " widget types can be added; Edit disables widget actions and performs no scooter work");

            metricId = widget("metric").id;
            openOptions(metricId); clickText(dialogRoot(), "Parameter");
            main(() -> first(dialogRoot(), EditText.class).setText("battery_percent"));
            clickText(dialogRoot(), metricLabel("battery_percent"));
            openOptions(metricId); clickText(dialogRoot(), "Title");
            main(() -> first(dialogRoot(), EditText.class).setText("Synthetic charge"));
            click(dialog().getButton(AlertDialog.BUTTON_POSITIVE));
            await(() -> "battery_percent".equals(widgetById(metricId).metric), 3000, "Parameter choice was not applied");
            main(() -> {
                Object tile = tileById(metricId); TextView amount = (TextView)field(tile, "big");
                require(amount.getText().toString().contains("73"), "Big reading did not render the cached parameter");
                require(amount.getCurrentTextColor() == activity.ui().muted, "Cached big reading is presented as live");
                require(((TextView)field(tile, "footer")).getText().toString().contains("Last known"), "Cached widget lacks a timestamp label");
            });
            setSize(metricId, 1, 4); setPosition(metricId, 1, 1);
            main(() -> require(cell(metricId).x == 0 && cell(metricId).y == 0 && cell(metricId).w == 1 && cell(metricId).h == 4,
                    "Size/Move options did not retain the selected rectangle"));
            assertGrid(); resizeByOneRow(metricId); assertGrid(); assertEditInputsInert();
            scrollHomeTop(); screenshot("customized-edit-dark");
            check("A parameter, title, size and position can be configured; a resize gesture reflows neighboring cards without overlap");

            nativeDragToNextCell(metricId); assertGrid(); assertEditInputsInert();
            scrollHomeTop(); screenshot("native-drag-dark");
            check("A real touchscreen long press starts native drag-and-drop; dropping moves the card, avoids overlap and saves its placement");

            verifyGaugeUnits(); assertNoScooterWork();
            check("Gauge speed limits display in mph, round-trip through editing and convert to km/h without changing the stored scale");

            verifyCapacityExperiment(); assertGrid(); assertNoScooterWork();
            reveal((View)field(tile("capacity"), "capacityValue")); screenshot("capacity-no-data-dark");
            check("Capacity experiment reports insufficient ride data; its title, time window and advertised Wh can be configured and saved");

            verifyChargingDisplay(); assertNoScooterWork();
            check("The battery card switches exclusively between normal and fresh charging details; charging rate and cutoff estimates reject stale inputs");

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
                require(((TextView)field(tile("scooter"), "photoName")).isEnabled(), "Scooter naming remained disabled after Done");
            });
            assertInnerScrolling(); assertGrid(); assertNoScooterWork();
            check("Done restores ordinary actions; long widget content scrolls within its card without hiding its footer");

            DashboardLayout.Cell before = cell(metricId).copy();
            recreateActivity();
            main(() -> {
                DashboardConfig.Widget restored = widgetById(metricId); DashboardLayout.Cell position = cell(metricId);
                require(restored.title.equals("Synthetic charge") && restored.metric.equals("battery_percent"), "Widget configuration did not survive recreation");
                require(position.x == before.x && position.y == before.y && position.w == before.w && position.h == before.h,
                        "Widget placement did not survive recreation");
                require(types().size() == DashboardConfig.TYPES.length && !dashboard().isEditing(), "Dashboard mode or widget list did not survive recreation");
                require(RENAMED.equals(activity.service().profiles.active().name), "Scooter rename was lost on recreation");
                require(widget("capacity").advertisedWh == 800d && widget("capacity").windowMs == 30 * 86400000L
                        && widget("capacity").title.equals("Synthetic capacity experiment"), "Capacity widget settings were lost on recreation");
            });
            assertCachedReadings(); assertGrid();
            check("Recreating the activity preserves widget choices, placement, scooter name and stale readings");

            main(() -> settings().edit().putString("theme", "light").putFloat("text_scale", 1.4f).apply());
            await(() -> !activity.ui().dark && activity.ui().textScale > 1.3f && dashboard().view().getWidth() > 0, 5000,
                    "Large light theme did not apply");
            assertGrid(); assertNavigationFits(); scrollHomeTop(); screenshot("customized-large-light");
            assertInnerScrolling(); screenshot("scrolling-note-large-light");
            clickText(root(), "Edit"); assertEditInputsInert(); assertGrid(); scrollHomeTop(); screenshot("edit-large-light");
            main(() -> invoke(activity, "navigateBack"));
            await(() -> !dashboard().isEditing(), 3000, "Back did not leave dashboard Edit");
            assertNoScooterWork();
            check("140% text and light theme retain a non-overlapping dashboard, inner scrolling and visible navigation; Back exits Edit");

            restartServiceAndActivity();
            await(() -> activity.service().displayReadings().get("battery_percent").number != null, 5000,
                    "Cached readings did not return after a service restart");
            assertCachedReadings(); assertGrid(); assertNoScooterWork();
            main(() -> require(widgetById(metricId).metric.equals("battery_percent") && types().size() == DashboardConfig.TYPES.length,
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
            // Deliberately no address or serial: no real scooter is impersonated or targeted.
            profileId = profile.id; fixtureHistory = new TelemetryHistory(getTargetContext());
            long now = System.currentTimeMillis() - 60000;
            for (int i = 0; i < 4; i++) {
                long wall = now + i * 5000;
                Map<String, TelemetryHistory.Reading> readings = new LinkedHashMap<>();
                syntheticReading(readings, "battery_percent", "73 %", "%", 73d, wall);
                syntheticReading(readings, "power_w", (900 + i * 10) + " W", "W", 900d + i * 10, wall);
                syntheticReading(readings, "battery_health_percent", "98 %", "%", 98d, wall);
                syntheticReading(readings, "controller_version", "Synthetic 1.2.3", "", null, wall);
                syntheticReading(readings, "charging_state", "Charging", "", 1d, wall);
                syntheticReading(readings, "voltage_v", "52 V", "V", 52d, wall);
                syntheticReading(readings, "current_a", "-2 A", "A", -2d, wall);
                syntheticReading(readings, "rated_capacity_ah", "14.5 Ah", "Ah", 14.5d, wall);
                syntheticReading(readings, "charge_limit_percent", "80 %", "%", 80d, wall);
                fixtureHistory.record(profileId, new TelemetryHistory.Snapshot(readings, Collections.emptyMap(), wall), 1, true);
            }
        });
        AtomicReference<TelemetryHistory.Snapshot> saved = new AtomicReference<>();
        main(() -> fixtureHistory.load(profileId, saved::set));
        await(() -> saved.get() != null, 5000, "Synthetic fixture did not persist");
        require(saved.get().readings.containsKey("controller_version"), "Text fixture was not saved");
        fixtureHistory.close(); fixtureHistory = null;
    }
    private void syntheticReading(Map<String, TelemetryHistory.Reading> readings, String key, String value, String unit, Double number, long wall) {
        Telemetry.Metric metric = null; for (Telemetry.Metric m : new Telemetry().metrics()) if (m.key.equals(key)) metric = m;
        require(metric != null, "Unknown synthetic parameter");
        readings.put(key, new TelemetryHistory.Reading(key, metric.label, metric.group, value, unit, number, wall, true));
    }
    private void launch() {
        activity = (MainActivity)startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> activity.service() != null && root().isAttachedToWindow() && root().getWidth() > 0, 6000, "Workspace did not initialize");
        awaitDashboard();
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
        main(() -> {
            TelemetryHistory.Reading number = activity.service().displayReadings().get("battery_percent");
            TelemetryHistory.Reading text = activity.service().displayReadings().get("controller_version");
            require(number.number != null && number.number == 73d && !number.live && number.capturedWallMs > 0,
                    "Battery cache is absent or incorrectly live");
            require("Synthetic 1.2.3".equals(text.value) && text.number == null && !text.live && text.capturedWallMs > 0,
                    "Nonnumeric cache is absent or incorrectly live");
            require(!activity.service().readingFresh("battery_percent") && !activity.service().featureFresh("headlight"),
                    "Cache must not provide command freshness");
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
            require(!((View)field(activity, "lockControl")).isEnabled(), "Lock remains interactive during Edit");
        }); assertNoScooterWork();
    }
    private void assertDisabledActions(View view) {
        if (view.isClickable()) require(!view.isEnabled(), "Widget action remains enabled while editing: " + view.getContentDescription());
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) assertDisabledActions(((ViewGroup)view).getChildAt(i));
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
        main(() -> viewport.fullScroll(View.FOCUS_DOWN));
        // fullScroll may schedule smooth scrolling on later frames. Main-queue idle
        // alone does not mean that animation has advanced.
        await(() -> viewport.getScrollY() > 0, 3000, "Long note cannot scroll inside its card");
        main(() -> {
            require(viewport.getChildAt(0).getHeight() > viewport.getHeight(), "Long note did not exceed its bounded viewport");
            require(viewport.getScrollY() > 0, "Long note cannot scroll inside its card");
            View footer = (View)field(note, "footer");
            require(footer.getBottom() <= ((View)note).getHeight(), "Widget footer escaped its card");
        });
    }
    private void setSize(String id, int width, int height) {
        openOptions(id); clickText(dialogRoot(), "Size");
        List<NumberPicker> pickers = all(dialogRoot(), NumberPicker.class); require(pickers.size() == 2, "Size options need both dimensions");
        main(() -> { pickers.get(0).setValue(width); pickers.get(1).setValue(height); });
        click(dialog().getButton(AlertDialog.BUTTON_POSITIVE)); waitForIdleSync();
    }
    private void setPosition(String id, int column, int row) {
        openOptions(id); clickText(dialogRoot(), "Move"); List<EditText> inputs = all(dialogRoot(), EditText.class);
        require(inputs.size() == 2, "Move options need a column and row");
        main(() -> { inputs.get(0).setText(Integer.toString(column)); inputs.get(1).setText(Integer.toString(row)); });
        click(dialog().getButton(AlertDialog.BUTTON_POSITIVE)); waitForIdleSync();
    }
    private void resizeByOneRow(String id) {
        DashboardConfig.Widget widget = widgetById(id); int height = cell(id).h;
        View resize = findDescription((View)tileById(id), "Resize " + widget.title); require(resize != null, "Resize handle is missing"); reveal(resize);
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
    private void nativeDragToNextCell(String id) {
        scrollHomeTop();
        DashboardLayout.Cell before = cell(id).copy();
        View grip = findDescription((View)tileById(id), "Move " + widgetById(id).title);
        require(grip != null, "Native drag grip is missing"); reveal(grip);
        final float[] position = new float[4]; final int[] destination = new int[3];
        main(() -> {
            int[] source = new int[2], origin = new int[2]; grip.getLocationOnScreen(source); grid().getLocationOnScreen(origin);
            int columns = ((Number)field(grid(), "columns")).intValue();
            int column = columns > 1 ? 1 : 0, row = 1;
            float columnWidth = ((Number)invoke(grid(), "columnWidth")).floatValue();
            float rowHeight = ((Number)invoke(grid(), "rowHeight")).floatValue();
            position[0] = source[0] + grip.getWidth() / 2f; position[1] = source[1] + grip.getHeight() / 2f;
            position[2] = origin[0] + (column + .5f) * columnWidth;
            position[3] = origin[1] + (row + .5f) * rowHeight;
            Rect visible = new Rect(); require(grid().getGlobalVisibleRect(visible), "Drag grid is not visible");
            require(visible.contains((int)position[2], (int)position[3]), "Native drag destination lies outside the visible grid");
            require(grip.getGlobalVisibleRect(new Rect()), "Native drag grip is not visible");
            destination[0] = column; destination[1] = row; destination[2] = columns;
        });
        long downTime = SystemClock.uptimeMillis(); boolean released = false;
        try {
            injectTouch(downTime, MotionEvent.ACTION_DOWN, position[0], position[1]);
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 180L);
            for (int step = 1; step <= 12; step++) {
                float fraction = step / 12f;
                injectTouch(downTime, MotionEvent.ACTION_MOVE, position[0] + (position[2] - position[0]) * fraction,
                        position[1] + (position[3] - position[1]) * fraction);
                SystemClock.sleep(18);
            }
            await(() -> ((Number)field(grid(), "dragX")).floatValue() >= 0, 2500,
                    "Touchscreen long press did not deliver a native drag location to the grid");
            injectTouch(downTime, MotionEvent.ACTION_UP, position[2], position[3]); released = true;
            await(() -> cell(id).x == destination[0] && cell(id).y == destination[1], 3000,
                    "Native drag drop did not move the widget to the requested cell");
        } finally {
            if (!released) injectTouch(downTime, MotionEvent.ACTION_CANCEL, position[0], position[1]);
        }
        require(cell(id).x != before.x || cell(id).y != before.y, "Native drag did not change placement");
        main(() -> {
            DashboardConfig restored = new DashboardConfig(settings(), profileId);
            DashboardLayout.Cell saved = null;
            for (DashboardConfig.Widget widget : restored.widgets) if (widget.id.equals(id)) saved = widget.layouts.get(destination[2]);
            require(saved != null && saved.x == destination[0] && saved.y == destination[1], "Native drag placement was not saved");
        }); waitForIdleSync();
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
    private void verifyCapacityExperiment() {
        String id = widget("capacity").id;
        require(widgetById(id).title.equals("Capacity experiment"), "Capacity widget default title is missing");
        await(() -> "Not enough ride data".contentEquals(((TextView)field(tileById(id), "capacityValue")).getText()),
                5000, "Capacity widget invented an estimate without recorded rides");
        openOptions(id); clickText(dialogRoot(), "Title");
        main(() -> first(dialogRoot(), EditText.class).setText("Synthetic capacity experiment"));
        click(dialog().getButton(AlertDialog.BUTTON_POSITIVE));
        openOptions(id); clickText(dialogRoot(), "Time window"); chooseDialogItem("30 days");
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
    private void closeDialog() { click(dialog().getButton(AlertDialog.BUTTON_NEGATIVE)); }
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
            TextView caption = first(item, TextView.class); require(caption != null && caption.getLayout() != null
                    && caption.getLayout().getHeight() <= caption.getHeight() + 2, "Navigation caption is clipped: " + name);
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
        if (candidate.isShown() && candidate instanceof TextView && text.contentEquals(((TextView)candidate).getText())) {
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
