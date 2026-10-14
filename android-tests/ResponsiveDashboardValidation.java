package local.navee.offlinelock.tests;

import android.Manifest;
import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import local.navee.offlinelock.BleController;
import local.navee.offlinelock.DashboardConfig;
import local.navee.offlinelock.DashboardLayout;
import local.navee.offlinelock.DashboardScreen;
import local.navee.offlinelock.MainActivity;
import local.navee.offlinelock.ScooterProfiles;
import local.navee.offlinelock.ScooterService;
import local.navee.offlinelock.TelemetryHistory;

/**
 * Isolated native measurement regressions for arbitrary window sizes and OEM fold callbacks.
 * Refuses physical devices and existing app data. READY is a view-only fixture; CONNECTING
 * temporarily changes only the display state, restoring it before the UI thread yields. No
 * transport method, GATT object, authentication, control tap, recording, or scooter command runs.
 */
public final class ResponsiveDashboardValidation extends Instrumentation {
    private Bundle arguments;
    private MainActivity activity;
    private boolean admitted;
    private final ArrayList<String> checks = new ArrayList<>(), screenshots = new ArrayList<>();
    private final ArrayList<String> overlayGeometry = new ArrayList<>();

    @Override public void onCreate(Bundle args) { arguments = args == null ? new Bundle() : args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            admit(); admitted = true;
            require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                    .putString("theme", "dark").putString("units", "mi")
                    .putFloat("text_scale", Float.parseFloat(arguments.getString("text_scale", "1"))).commit(), "Fixture settings failed");
            seed();
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.service() != null && field(activity, "screen") instanceof DashboardScreen
                    && grid().getWidth() > 0, "Dashboard did not launch");
            await(() -> activity.service().displayReadings().containsKey("odometer_km"), "Cached fixture did not load");
            stopUiPulse(); settle();
            String mode = arguments.getString("mode", "all");
            if (mode.equals("gauge")) {
                verifyGaugeContent();
                check("270-degree gauges keep live/dual/unknown/stale/long readings and endpoint labels inside narrow/wide and short/tall cards");
            } else if (mode.equals("motion")) {
                verifyEditorMotion();
                check("Editor transitions keep synchronous state and steady hit targets; gentle jiggle pauses during gestures, focus loss and detach, and disabled animations stay still");
            } else if (mode.equals("edit") || mode.equals("editor")) {
                seedEveryWidget();
                verifyEditOverlays();
                check("Editing adds one inline row of equal icon-only Move/Edit/Duplicate/Remove controls, without overlaps or resized cards");
                if (mode.equals("editor")) { verifyEditorActions(); check("Save/duplicate/remove and Undo/Redo preserve the intended widgets and settings; six-column sizing and cancelled resize keep safe layout constraints"); }
            } else if (!mode.equals("fold")) {
                assertGridAndHeader(); check("Default cards fit the available window and start immediately after the header");
                if (!mode.equals("battery")) { assertLockStates(); check("Connected, disconnected and connecting lock controls fit without inner scrolling"); }
                assertDefaultCardContents(); screenshot("default-disconnected");
                check("Default scooter and battery readings fit without hidden overflow");
                if (mode.equals("constraints")) {
                    verifyMinimumLockAndCharging();
                    check("Minimum lock height restores cleanly; minimum battery size fits its active charging readings");
                    verifyLockWidthTransitions();
                    check("Lock headings restore margins, weights and child bounds across LTR/RTL narrow-wide-narrow measurements");
                    verifyLockMotionAndDisconnect();
                    check("Disconnect preference preserves Connect/Cancel; lock motion keeps confirmed accessibility state, cancels cleanly and stops off-screen");
                }
            }
            if (mode.equals("all") || mode.equals("fold")) {
                verifyFoldCallbacks(); check("Flat OEM creases do not split Home; separating folds and real hinges retain the two-pane layout");
            }
            assertNoScooterWork();
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", error.toString());
            try { if (admitted && activity != null) screenshot("failure"); } catch (Throwable ignored) { }
        } finally {
            result.putStringArrayList("checks", checks); result.putStringArrayList("screenshots", screenshots);
            result.putStringArrayList("overlay_geometry", overlayGeometry);
            result.putInt("width_dp", getTargetContext().getResources().getConfiguration().screenWidthDp);
            result.putBoolean("physical_device_tested", false); result.putInt("scooter_mutations", 0);
            if (admitted && activity != null) {
                try { main(() -> { activity.finish(); ScooterService.setUiVisible(false);
                    getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); }); }
                catch (Throwable error) { result.putString("cleanup_error", error.toString()); }
            }
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }
    private void admit() {
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
        require(hardware.contains("ranchu") || hardware.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "Refusing a physical phone");
        for (String name : new String[] {"app_settings", "profiles", "scooter", "pending_documents"})
            require(getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE).getAll().isEmpty(), "Refusing existing preferences");
        File[] files = getTargetContext().getFilesDir().listFiles();
        require(files != null && files.length == 0 && getTargetContext().databaseList().length == 0, "Refusing existing private data");
        for (String permission : new String[] {Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT})
            require(getTargetContext().checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED, "Grant Nearby permission before the test");
        BluetoothManager manager = getTargetContext().getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        require(adapter == null || !adapter.isEnabled(), "Bluetooth must already be disabled");
    }
    private void seed() {
        AtomicReference<TelemetryHistory.Snapshot> saved = new AtomicReference<>();
        AtomicReference<TelemetryHistory> history = new AtomicReference<>();
        main(() -> {
            ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
            ScooterProfiles.Profile profile = profiles.create("Synthetic Fold dashboard", "000000000000", 0);
            profile.serial = "X2538-SYNTHETIC"; profile.lastConnected = System.currentTimeMillis() - 86400000L; profiles.update(profile);
            long wall = System.currentTimeMillis() - 60000;
            Map<String, TelemetryHistory.Reading> values = new LinkedHashMap<>();
            reading(values, "battery_percent", "100 %", "%", 100d, wall);
            reading(values, "power_w", "14.6 W", "W", 14.6d, wall);
            reading(values, "battery_health_percent", "100 %", "%", 100d, wall);
            reading(values, "remaining_range_km", "49.8 km", "km", 49.8d, wall);
            reading(values, "temperature_c", "23 °C", "°C", 23d, wall);
            reading(values, "odometer_km", "24.89 km", "km", 24.89d, wall);
            reading(values, "driving_mode", "3", "", 3d, wall);
            reading(values, "charging_state", "Not charging", "", 0d, wall);
            TelemetryHistory store = new TelemetryHistory(getTargetContext()); history.set(store);
            store.record(profile.id, new TelemetryHistory.Snapshot(values, Collections.emptyMap(), wall), 1, true);
            store.load(profile.id, saved::set);
        });
        await(() -> saved.get() != null, "Synthetic cache did not persist");
        main(() -> history.get().close());
    }
    private void reading(Map<String, TelemetryHistory.Reading> values, String key, String text, String unit, double value, long wall) {
        values.put(key, new TelemetryHistory.Reading(key, key, "Synthetic fixture", text, unit, value, wall, true));
    }
    private void stopUiPulse() {
        main(() -> {
            ((Handler)field(activity, "handler")).removeCallbacks((Runnable)field(activity, "pulse"));
            invoke(activity, "cancelDeferredBodyRefresh");
            // Freeze only this Activity's automatic presentation refresh while supplying view-only fixtures.
            if (activity.service() != null) activity.service().unlisten((Runnable)field(activity, "serviceChanged"));
        });
    }
    private void assertGridAndHeader() {
        main(() -> {
            ViewGroup grid = grid(); View toolbar = (View)field(dashboard(), "toolbar");
            require(grid.getParent() == toolbar.getParent(), "Flat window split the header away from the grid");
            int expected = toolbar.getBottom();
            for (String name : new String[] {"tools", "hint", "stale", "chargingHero"}) {
                View item = (View) field(dashboard(), name);
                if (item.getVisibility() != View.GONE) expected = Math.max(expected, item.getBottom());
            }
            require(grid.getTop() - expected <= activity.ui().dp(16), "Unexplained gap before the first card: " + (grid.getTop() - expected));
            List<Rect> occupied = new ArrayList<>();
            int first = Integer.MAX_VALUE;
            for (Object tile : tiles()) {
                View card = (View)tile; Rect box = new Rect(card.getLeft(), card.getTop(), card.getRight(), card.getBottom());
                require(box.left >= 0 && box.right <= grid.getWidth() && box.top >= 0 && box.bottom <= grid.getHeight()
                        && box.width() > 0 && box.height() > 0, "Card lies outside the grid: " + box);
                for (Rect other : occupied) require(!Rect.intersects(box, other), "Cards overlap");
                occupied.add(box); first = Math.min(first, box.top);
            }
            require(first <= activity.ui().dp(16), "Default grid has phantom leading rows");
        });
    }
    private void assertDefaultCardContents() {
        main(() -> {
            for (String type : new String[] {"scooter", "battery"}) {
                Object item = tile(type); View body = (View)field(item, "body");
                ScrollView viewport = (ScrollView)field(item, "viewport");
                require(body.getHeight() <= viewport.getHeight() + 1, type + " default needs inner scrolling: body=" + body.getWidth() + "x" + body.getHeight() + ", viewport=" + viewport.getWidth() + "x" + viewport.getHeight() + ", tile=" + ((View)item).getWidth() + "x" + ((View)item).getHeight());
                for (TextView text : texts(body)) assertText(text, viewport, type);
            }
        });
    }
    private void assertLockStates() throws Exception {
        Object item = tile("lock"); View card = (View)item;
        for (String state : new String[] {"disconnected", "unlocked", "locked", "connecting"}) {
            Runnable showState = () -> {
                invoke(activity, "updateHeader");
                if (state.equals("connecting")) {
                    BleController ble = activity.service().ble;
                    BleController.State original = ble.state();
                    try {
                        setField(ble, "state", BleController.State.CONNECTING);
                        invoke(activity, "updateHeader");
                    } finally { setField(ble, "state", original); }
                }
                if (state.equals("unlocked") || state.equals("locked")) {
                    ((View)field(activity, "lockOffline")).setVisibility(View.GONE);
                    ((View)field(activity, "lockReady")).setVisibility(View.VISIBLE);
                    invoke(activity, "updateConnectionAction", new Class<?>[] {boolean.class, boolean.class}, true, false);
                    TextView title = (TextView)field(activity, "lockTitle"); title.setText(state.equals("locked") ? "Locked" : "Unlocked");
                    // Preserve the production glyph size installed by updateHeader; only the text/state changes.
                    invoke(field(activity, "lockControl"), "update", new Class<?>[] {Boolean.class, boolean.class}, state.equals("locked"), true);
                }
                card.requestLayout();
            };
            main(showState); settle();
            main(() -> {
                // A service refresh may restore Disconnected between frames. Apply the display-only
                // fixture and measure atomically; never pause the transport or fabricate a GATT.
                showState.run();
                int width = card.getWidth(), height = card.getHeight();
                for (int pass = 0; pass < 3; pass++) {
                    forceMeasure(card);
                    card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                    card.layout(card.getLeft(), card.getTop(), card.getLeft() + width, card.getTop() + height);
                }
                View body = (View)field(item, "body"); ScrollView viewport = (ScrollView)field(item, "viewport");
                require(body.getHeight() <= viewport.getHeight() + 1, state + " lock needs hidden inner scrolling: body " + body.getHeight() + ", viewport " + viewport.getHeight());
                View action = (View)field(activity, state.equals("locked") || state.equals("unlocked") ? "lockControl" : "connectionAction");
                Rect bounds = relativeBounds(action, viewport);
                require(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= viewport.getWidth() && bounds.bottom <= viewport.getHeight(),
                        state + " action is clipped: " + bounds + " in " + viewport.getWidth() + "x" + viewport.getHeight());
                require(action.getWidth() >= activity.ui().dp(48) && action.getHeight() >= activity.ui().dp(48), state + " action lost its touch target");
                if (state.equals("locked") || state.equals("unlocked")) {
                    TextView disconnect = (TextView)field(activity, "connectionAction");
                    require(disconnect.getVisibility() == View.VISIBLE && disconnect.getText().toString().equals("Disconnect"), "Default connected card lost Disconnect");
                    Rect disconnectBounds = relativeBounds(disconnect, viewport);
                    require(disconnectBounds.left >= 0 && disconnectBounds.top >= 0 && disconnectBounds.right <= viewport.getWidth()
                            && disconnectBounds.bottom <= viewport.getHeight(), "Disconnect action is clipped: " + disconnectBounds);
                    require(disconnect.getHeight() >= activity.ui().dp(48), "Disconnect lost its touch target");
                }
                for (TextView text : texts(body)) assertText(text, viewport, state);
                if (state.equals("connecting")) {
                    CharSequence spoken = ((TextView)field(activity, "offlineTitle")).getContentDescription();
                    require(spoken != null && spoken.toString().contains(activity.service().profiles.active().name), "Connecting status lost its accessible scooter name");
                }
            });
            if (state.equals("unlocked")) screenshot("connected-view-fixture");
        }
        main(() -> invoke(activity, "updateHeader")); settle();
    }
    /** View-only checks: never fake a GATT, tap an action, or submit a lock command. */
    private void verifyLockMotionAndDisconnect() {
        main(() -> {
            boolean showDisconnect = (Boolean)field(activity, "showDashboardDisconnect");
            boolean resumed = (Boolean)field(activity, "resumed");
            View slider = (View)field(activity, "lockControl");
            try {
                TextView action = (TextView)field(activity, "connectionAction");
                for (boolean show : new boolean[] {false, true}) {
                    setField(activity, "showDashboardDisconnect", show);
                    invoke(activity, "updateConnectionAction", new Class<?>[] {boolean.class, boolean.class}, true, false);
                    require(action.getVisibility() == (show ? View.VISIBLE : View.GONE), "Disconnect setting was ignored");
                    require(action.getText().toString().equals("Disconnect"), "Ready connection action changed meaning");
                    invoke(activity, "updateConnectionAction", new Class<?>[] {boolean.class, boolean.class}, false, false);
                    require(action.getVisibility() == View.VISIBLE && action.getText().toString().equals("Connect"), "Hiding Disconnect also hid Connect");
                    invoke(activity, "updateConnectionAction", new Class<?>[] {boolean.class, boolean.class}, false, true);
                    require(action.getVisibility() == View.VISIBLE && action.getText().toString().equals("Cancel"), "Hiding Disconnect also hid Cancel");
                }
                ((View)field(activity, "lockOffline")).setVisibility(View.GONE);
                ((View)field(activity, "lockReady")).setVisibility(View.VISIBLE);
                invoke(slider, "update", new Class<?>[] {Boolean.class, boolean.class}, false, true);
                invoke(slider, "stopMotion");
                require(((Number)field(slider, "position")).floatValue() == 0, "Unlocked thumb did not settle left");
                invoke(slider, "update", new Class<?>[] {Boolean.class, boolean.class}, true, true);
                android.view.accessibility.AccessibilityNodeInfo checked = slider.createAccessibilityNodeInfo();
                try { require(checked.isCheckable() && checked.isChecked(), "Animation delayed the confirmed accessible lock state"); }
                finally { checked.recycle(); }
                require(Boolean.TRUE.equals(field(slider, "value")), "Animation replaced the confirmed value");
                if (local.navee.offlinelock.Ui.motionEnabled() && resumed && slider.isShown())
                    require(field(slider, "motion") != null && ((Number)field(slider, "position")).floatValue() < 1, "Confirmed state change skipped enabled motion");
                else require(((Number)field(slider, "position")).floatValue() == 1, "Reduced motion did not snap to the confirmed state");
                invoke(slider, "stopMotion");
                require(field(slider, "motion") == null && ((Number)field(slider, "position")).floatValue() == 1, "Stopping motion left an intermediate thumb position");

                invoke(slider, "update", new Class<?>[] {Boolean.class, boolean.class}, false, true);
                invoke(slider, "stopMotion");
                long when = SystemClock.uptimeMillis();
                float y = slider.getHeight()/2f, start = slider.getHeight()/2f, end = slider.getWidth()-slider.getHeight()/2f;
                for (int event : new int[] {android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE, android.view.MotionEvent.ACTION_CANCEL}) {
                    android.view.MotionEvent touch = android.view.MotionEvent.obtain(when, when, event, event == android.view.MotionEvent.ACTION_DOWN ? start : end, y, 0);
                    try { slider.onTouchEvent(touch); } finally { touch.recycle(); }
                    if (event == android.view.MotionEvent.ACTION_MOVE)
                        require(Boolean.FALSE.equals(field(slider, "value")) && ((Number)field(slider, "position")).floatValue() > .5f, "Drag preview changed confirmed state or failed to follow the finger");
                }
                invoke(slider, "stopMotion");
                require(!(Boolean)field(slider, "dragging") && Boolean.FALSE.equals(field(slider, "value"))
                        && ((Number)field(slider, "position")).floatValue() == 0, "Cancelled drag retained a proposed lock state");
                setField(activity, "resumed", false);
                invoke(slider, "update", new Class<?>[] {Boolean.class, boolean.class}, true, false);
                require(field(slider, "motion") == null && ((Number)field(slider, "position")).floatValue() == 1, "Background update kept animating");
                invoke(slider, "update", new Class<?>[] {Boolean.class, boolean.class}, null, false);
                require(field(slider, "motion") == null && !slider.isEnabled() && ((Number)field(slider, "position")).floatValue() == 0, "Unavailable state retained a moving/interactive control");
            } finally {
                setField(activity, "resumed", resumed);
                setField(activity, "showDashboardDisconnect", showDisconnect);
                invoke(slider, "stopMotion"); invoke(activity, "updateHeader");
            }
        });
        settle();
    }
    private void verifyMinimumLockAndCharging() throws Exception {
        int columns = ((Number)field(grid(), "columns")).intValue();
        DashboardConfig.Widget lock = (DashboardConfig.Widget)field(tile("lock"), "widget");
        int originalLockHeight = lock.layouts.get(columns).h;
        try {
            main(() -> { lock.layouts.get(columns).h = lock.minimumHeight(); grid().requestLayout(); });
            settle(); assertLockStates();
        } finally {
            main(() -> { lock.layouts.get(columns).h = originalLockHeight; grid().requestLayout(); }); settle();
        }
        assertLockStates();
        Object item = tile("battery");
        DashboardConfig.Widget battery = (DashboardConfig.Widget)field(item, "widget");
        int originalHeight = battery.layouts.get(columns).h, originalWidth = battery.layouts.get(columns).w;
        Map<String, TelemetryHistory.Reading> cached = activity.service().displayReadings();
        Map<String, TelemetryHistory.Reading> preview = new LinkedHashMap<>(cached);
        long wall = System.currentTimeMillis();
        reading(preview, "charging_state", "Charging", "", 1d, wall);
        reading(preview, "voltage_v", "52 V", "V", 52d, wall);
        reading(preview, "current_a", "-2 A", "A", -2d, wall);
        reading(preview, "battery_percent", "50 %", "%", 50d, wall);
        reading(preview, "rated_capacity_ah", "14.5 Ah", "Ah", 14.5d, wall);
        reading(preview, "charge_limit_percent", "80 %", "%", 80d, wall);
        try {
            main(() -> {
                battery.layouts.get(columns).h = battery.minimumHeight(); battery.layouts.get(columns).w = 1;
                invoke(item, "refresh", new Class<?>[] {Map.class}, preview);
                require(((View)field(item, "chargingData")).getVisibility() == View.VISIBLE, "Fresh charging calculation did not select the charging view");
                grid().requestLayout();
            });
            settle();
            main(() -> require(((View)field(item, "chargingData")).getVisibility() == View.VISIBLE, "Fresh charging fixture did not appear"));
            assertDefaultCardContents(); screenshot("minimum-charging-view-fixture");
        } finally {
            main(() -> {
                battery.layouts.get(columns).h = originalHeight; battery.layouts.get(columns).w = originalWidth;
                invoke(item, "refresh", new Class<?>[] {Map.class}, cached); grid().requestLayout();
            }); settle();
        }
        assertGridAndHeader(); assertDefaultCardContents();
    }
    private void verifyLockWidthTransitions() {
        main(() -> {
            LinearLayout heading = (LinearLayout)((View)field(activity, "lockTitle")).getParent();
            LinearLayout offline = (LinearLayout)((View)field(activity, "offlineTitle")).getParent().getParent();
            for (LinearLayout row : new LinearLayout[] {heading, offline}) {
                int left = row.getLeft(), top = row.getTop(), width = row.getWidth(), height = row.getHeight(), direction = row.getLayoutDirection();
                boolean ready = row == heading;
                try {
                    for (int layoutDirection : new int[] {View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
                      row.setLayoutDirection(layoutDirection);
                      boolean rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL;
                      for (int widthDp : new int[] {132, 540, 132}) {
                        int targetWidth = activity.ui().dp(widthDp);
                        // Remeasure the parent only, as a resized window does. Forcing its children
                        // would conceal stale child measurement or unresolved margin behavior.
                        row.forceLayout();
                        row.measure(View.MeasureSpec.makeMeasureSpec(targetWidth, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                        row.layout(0, 0, targetWidth, row.getMeasuredHeight());
                        boolean narrow = widthDp == 132;
                        require(row.getOrientation() == (narrow ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL), "Lock header did not adapt to " + widthDp + " dp");
                        View first = row.getChildAt(0), second = row.getChildAt(1);
                        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams)second.getLayoutParams();
                        int gap = activity.ui().dp(ready ? 12 : 10);
                        require(params.leftMargin == (!narrow && !rtl ? gap : 0) && params.rightMargin == (!narrow && rtl ? gap : 0),
                                "Lock header retained stale resolved margins at " + widthDp + " dp: " + params.leftMargin + "," + params.rightMargin);
                        if (ready) {
                            LinearLayout.LayoutParams state = (LinearLayout.LayoutParams)first.getLayoutParams();
                            require(state.width == (narrow ? -1 : 0) && state.weight == (narrow ? 0 : 1), "Lock state retained stale width/weight");
                            require(narrow ? second.getTop() == first.getBottom() + activity.ui().dp(4)
                                    : rtl ? first.getLeft() == second.getRight() + gap : second.getLeft() == first.getRight() + gap,
                                    "Lock caption gap did not follow its orientation");
                        } else {
                            require(first.getVisibility() == (narrow ? View.GONE : View.VISIBLE), "Offline lock symbol did not adapt");
                            require(params.width == (narrow ? -1 : 0) && params.weight == (narrow ? 0 : 1), "Offline status retained stale width/weight");
                            require(narrow ? second.getLeft() == 0 : rtl ? first.getLeft() == second.getRight() + gap
                                    : second.getLeft() == first.getRight() + gap, "Offline words retained a stale start position");
                        }
                        for (View child : new View[] {first, second}) if (child.getVisibility() != View.GONE)
                            require(child.getWidth() > 0 && child.getLeft() >= 0 && child.getTop() >= 0
                                    && child.getRight() <= row.getWidth() && child.getBottom() <= row.getHeight(), "Lock heading child lies outside resized parent");
                      }
                    }
                } finally {
                    row.setLayoutDirection(direction);
                    row.forceLayout();
                    row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                    row.layout(left, top, left + width, top + height);
                }
            }
            ((View)tile("lock")).requestLayout();
        });
        settle();
    }
    private void verifyFoldCallbacks() throws Exception {
        Rect horizontal = new Rect(0, root().getHeight() / 2, root().getWidth(), root().getHeight() / 2);
        Rect vertical = new Rect(root().getWidth() / 2, 0, root().getWidth() / 2, root().getHeight());
        for (Rect crease : new Rect[] {horizontal, vertical}) {
            inject(new FakeFeature(crease, 1, 1)); assertGridAndHeader();
        }
        screenshot("flat-crease");
        inject(new FakeFeature(horizontal, 1, 2));
        main(() -> require((Boolean)invoke(activity, "horizontalHinge"), "Half-open fold lost its tabletop layout"));
        screenshot("tabletop");
        Rect realHinge = new Rect(0, horizontal.top, horizontal.right, horizontal.top + activity.ui().dp(8));
        inject(new FakeFeature(realHinge, 2, 1));
        main(() -> require((Boolean)invoke(activity, "horizontalHinge"), "Physical hinge must remain separating while flat"));
        inject(new FakeFeature(vertical, 1, 2));
        main(() -> require((Boolean)invoke(activity, "verticalHinge"), "Half-open vertical fold lost its separating layout"));
        Rect realVertical = new Rect(vertical.left, 0, vertical.left + activity.ui().dp(8), vertical.bottom);
        inject(new FakeFeature(realVertical, 2, 1));
        main(() -> require((Boolean)invoke(activity, "verticalHinge"), "Physical vertical hinge must remain separating while flat"));
        inject(); assertGridAndHeader();
    }
    private void inject(FakeFeature... features) {
        main(() -> invoke(field(activity, "fold"), "layout", new Class<?>[] {Object.class}, new FakeLayout(features)));
        settle(); stopUiPulse();
        await(() -> grid().getWidth() > 0, "Fold callback did not finish layout");
    }
    public static final class FakeLayout {
        private final List<FakeFeature> features;
        public FakeLayout(FakeFeature... values) { features = Arrays.asList(values); }
        public List<FakeFeature> getDisplayFeatures() { return features; }
    }
    public static final class FakeFeature {
        private final Rect bounds; private final int type, state;
        public FakeFeature(Rect bounds, int type, int state) { this.bounds = new Rect(bounds); this.type = type; this.state = state; }
        public Rect getBounds() { return new Rect(bounds); }
        public int getType() { return type; }
        public int getState() { return state; }
    }
    private void assertNoScooterWork() {
        main(() -> {
            ScooterService service = activity.service();
            require(service.profiles.all().size() == 1 && service.profiles.active().mac.isEmpty(), "Unexpected scooter identity");
            require(service.ble.state() == BleController.State.DISCONNECTED && !service.isRecording() && !service.phoneSaving(), "Unexpected Bluetooth or recording work");
            require(((Number)field(service.ble, "mutationSequence")).longValue() == 0 && service.ble.commandResults().isEmpty(), "Unexpected scooter command");
        });
    }
    private void verifyGaugeContent() throws Exception {
        final DashboardConfig.Widget widget = new DashboardConfig.Widget("gauge");
        widget.title = "Speed gauge"; widget.metric = "speed_kmh"; widget.minimum = 0; widget.maximum = 50;
        main(() -> { dashboardConfig().widgets.clear(); dashboardConfig().widgets.add(widget); dashboardConfig().save(); invoke(dashboard(), "render"); });
        settleMotion();
        int columns = ((Number)field(grid(), "columns")).intValue();
        int minimum = ((Number)invoke(grid(), "minimumWidth", new Class<?>[] {DashboardConfig.Widget.class}, widget)).intValue();
        for (int width : new int[] {minimum, columns}) for (int height : new int[] {widget.minimumHeight(), widget.defaultHeight()+2}) {
            main(() -> { widget.layouts.put(columns, new DashboardLayout.Cell(widget.id, 0, 0, width, height)); invoke(grid(), "settle", new Class<?>[] {String.class}, widget.id); });
            settleMotion();
            for (String state : new String[] {"live", "dual", "unknown", "stale", "long"}) {
                main(() -> {
                    Object item = tileById(widget.id); View gauge = (View)field(item, "gauge");
                    require(gauge.getWidth() > 0 && gauge.getHeight() > 0, "Gauge has no drawing area");
                    View viewport = (View)field(item, "viewport");
                    SharedPreferences.OnSharedPreferenceChangeListener listener = (SharedPreferences.OnSharedPreferenceChangeListener)field(activity, "settingsChanged");
                    String previous = settings().getString("units", "mi"); settings().unregisterOnSharedPreferenceChangeListener(listener);
                    try {
                        // Exercise real formatting without rebuilding the Activity halfway through this draw-only fixture.
                        settings().edit().putString("units", state.equals("dual") ? "dual" : "mi").commit();
                        double number = state.equals("long") ? -12345.678 : state.equals("stale") ? -21.25 : 27.3;
                        TelemetryHistory.Reading reading = state.equals("unknown") ? null : new TelemetryHistory.Reading("speed_kmh", "Speed", "Synthetic fixture",
                                number + " km/h", "km/h", number, System.currentTimeMillis()-60000, !state.equals("stale"));
                        Map<String, TelemetryHistory.Reading> values = new LinkedHashMap<>(activity.service().displayReadings());
                        if (reading == null) values.remove(widget.metric); else values.put(widget.metric, reading);
                        invoke(item, "refresh", new Class<?>[] {Map.class}, values);
                        View card = (View)item;
                        for (int pass = 0; pass < 3; pass++) {
                            forceMeasure(card); card.measure(View.MeasureSpec.makeMeasureSpec(card.getWidth(), View.MeasureSpec.EXACTLY),
                                    View.MeasureSpec.makeMeasureSpec(card.getHeight(), View.MeasureSpec.EXACTLY));
                            card.layout(card.getLeft(), card.getTop(), card.getRight(), card.getBottom());
                        }
                        Rect target = relativeBounds(gauge, viewport);
                        require(target.left >= 0 && target.top >= 0 && target.right <= viewport.getWidth() && target.bottom <= viewport.getHeight(), "Gauge view escapes its card");
                        TextView footer = (TextView)field(item, "footer");
                        require(!state.equals("stale") || footer.getVisibility() == View.VISIBLE && footer.getText().toString().startsWith("Last seen"), "Stale gauge lost its visible timestamp");
                        Bitmap image = Bitmap.createBitmap(gauge.getWidth(), gauge.getHeight(), Bitmap.Config.ARGB_8888);
                        try {
                            android.graphics.Canvas canvas = new android.graphics.Canvas(image); canvas.drawColor(activity.ui().surface); gauge.draw(canvas);
                            android.graphics.RectF arc = (android.graphics.RectF)field(gauge, "arcBounds");
                            float stroke = ((Number)field(gauge, "arcStroke")).floatValue();
                            require(((Number)field(gauge, "SWEEP_ANGLE")).floatValue() == 270f && !arc.isEmpty(), "Gauge is no longer a 270-degree ring");
                            float ringBottom = arc.centerY()+arc.width()/2f*(float)Math.sqrt(.5)+stroke/2f;
                            require(arc.left-stroke/2 >= -1 && arc.right+stroke/2 <= gauge.getWidth()+1 && arc.top-stroke/2 >= -1 && ringBottom <= gauge.getHeight()+1, "Gauge ring is clipped");
                            android.graphics.RectF block = new android.graphics.RectF();
                            for (String fieldName : new String[] {"valueBounds", "unitBounds", "secondaryBounds", "minimumLabelBounds", "maximumLabelBounds"}) {
                                android.graphics.RectF box = (android.graphics.RectF)field(gauge, fieldName);
                                if (box.isEmpty()) continue;
                                require(box.left >= -1 && box.top >= -1 && box.right <= gauge.getWidth()+1 && box.bottom <= gauge.getHeight()+1, fieldName + " is clipped in " + state + ": " + box);
                                if (fieldName.endsWith("LabelBounds")) require(box.top >= ringBottom+activity.ui().dp(2), "Range label touches the ring");
                                else {
                                    block.union(box);
                                    float radius = arc.width()/2f-stroke/2;
                                    for (float xx : new float[] {box.left, box.right}) for (float yy : new float[] {box.top, box.bottom})
                                        require(Math.hypot(xx-arc.centerX(), yy-arc.centerY()) < radius, "Gauge reading touches the ring");
                                }
                            }
                            require(!block.isEmpty() && Math.abs(block.centerY()-arc.centerY()) <= activity.ui().dp(2), "Gauge reading is not vertically centered");
                            android.graphics.RectF low = (android.graphics.RectF)field(gauge, "minimumLabelBounds"), high = (android.graphics.RectF)field(gauge, "maximumLabelBounds");
                            require(!low.isEmpty() && !high.isEmpty() && !android.graphics.RectF.intersects(low, high), "Gauge range labels overlap or disappeared");
                            require(!state.equals("dual") || !((android.graphics.RectF)field(gauge, "secondaryBounds")).isEmpty(), "Dual units lost the second reading");
                            require(!state.equals("unknown") || field(gauge, "amount").equals("—"), "Unknown reading became a numeric value");
                            require(!state.equals("stale") || gauge.getContentDescription().toString().contains("last seen"), "Stale gauge lost its accessible state");
                            if (state.equals("live") || state.equals("dual")) saveNativeBitmap(image, "gauge-"+width+"x"+height+"-"+state);
                        } finally { image.recycle(); }
                    } finally { settings().edit().putString("units", previous).commit(); settings().registerOnSharedPreferenceChangeListener(listener); }
                });
            }
        }
        main(() -> { invoke(tileById(widget.id), "refresh", new Class<?>[] {Map.class}, activity.service().displayReadings()); });
        screenshot("gauge-card");
    }
    private void saveNativeBitmap(Bitmap bitmap, String name) {
        File folder = new File(getTargetContext().getExternalFilesDir(null), "responsive-validation"); require(folder.isDirectory() || folder.mkdirs(), "Cannot create gauge evidence folder");
        File output = new File(folder, arguments.getString("variant", "responsive").replaceAll("[^A-Za-z0-9_-]", "")+"-"+name+".png");
        try (FileOutputStream stream = new FileOutputStream(output)) { require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream), "Gauge evidence failed"); }
        catch (Exception error) { throw new IllegalStateException(error); }
        screenshots.add(output.getAbsolutePath());
    }
    private void seedEveryWidget() {
        main(() -> {
            DashboardConfig config = dashboardConfig(); config.widgets.clear();
            for (String type : DashboardConfig.TYPES) {
                DashboardConfig.Widget widget = new DashboardConfig.Widget(type);
                if (type.equals("metric") || type.equals("live")) widget.metric = "power_w";
                if (type.equals("history")) widget.metric = "battery_percent";
                if (type.equals("gauge")) { widget.metric = "speed_kmh"; widget.maximum = 50; }
                if (type.equals("note")) widget.note = "UI test layout";
                config.widgets.add(widget);
            }
            for (int columns = 1; columns <= DashboardConfig.MAX_COLUMNS; columns++) {
                int row = 0;
                for (int i = 0; i < config.widgets.size();) {
                    DashboardConfig.Widget first = config.widgets.get(i++);
                    int firstWidth = columns == 1 ? 1 : (columns + 1) / 2;
                    int height = first.defaultHeight();
                    first.layouts.put(columns, new DashboardLayout.Cell(first.id, 0, row, firstWidth, height));
                    if (columns > 1 && i < config.widgets.size()) {
                        DashboardConfig.Widget second = config.widgets.get(i++);
                        second.layouts.put(columns, new DashboardLayout.Cell(second.id, firstWidth, row, columns-firstWidth, second.defaultHeight()));
                        height = Math.max(height, second.defaultHeight());
                    }
                    row += height;
                }
            }
            config.save(); invoke(dashboard(), "render");
        }); settleMotion();
    }
    private void verifyEditOverlays() throws Exception {
        final Map<String, Rect> normalFrames = new LinkedHashMap<>();
        main(() -> {
            assertNoEditorControls();
            for (Object item : tiles()) { View card = (View)item;
                normalFrames.put(((DashboardConfig.Widget)field(item, "widget")).id, new Rect(0, 0, card.getWidth(), card.getHeight())); }
            invoke(dashboard(), "toggleEditing");
        });
        settleMotion(); screenshot("edit-overlays");
        main(() -> {
            assertEditorControls();
            for (Object item : tiles()) { View card = (View)item;
                Rect before = normalFrames.get(((DashboardConfig.Widget)field(item, "widget")).id);
                require(before.width() == card.getWidth() && before.height() == card.getHeight(), "Editor changed the card's dashboard dimensions"); }
            invoke(dashboard(), "toggleEditing");
        });
        settleMotion();
        main(this::assertNoEditorControls);
    }
    private void assertNoEditorControls() {
        for (Object item : tiles()) require(editorControls((View)item).isEmpty(), "Widget editor controls escaped dashboard edit mode");
        for (String prefix : new String[] {"Undo", "Redo"}) {
            View action = described(root(), prefix);
            require(action == null || !action.isShown(), prefix + " escaped dashboard edit mode");
        }
    }
    private void assertEditorControls() {
        for (Object item : tiles()) {
            ViewGroup card = (ViewGroup)item;
            DashboardConfig.Widget widget = (DashboardConfig.Widget)field(item, "widget");
            List<View> controls = editorControls(card);
            require(controls.size() == 5, "Expected Move/Edit/Duplicate/Remove/Resize on " + widget.title + ", got " + controls.size());
            ArrayList<Rect> occupied = new ArrayList<>();
            View grip = described(card, "Move "), options = described(card, "Options for "),
                    duplicate = described(card, "Duplicate "), remove = described(card, "Remove ");
            Rect gripBounds = relativeBounds(grip, card), optionBounds = relativeBounds(options, card),
                    duplicateBounds = relativeBounds(duplicate, card), removeBounds = relativeBounds(remove, card);
            int inlineTarget = Math.min(activity.ui().dp(48), card.getWidth() / 4);
            require(inlineTarget >= activity.ui().dp(36) - 1, "Inline controls fell below the compact target floor");
            require(gripBounds.left == 0 && optionBounds.left == gripBounds.right
                            && duplicateBounds.left == optionBounds.right && removeBounds.right == card.getWidth(),
                    "Move/Edit/Duplicate must sit together at the left, with Remove at the right");
            require(gripBounds.top == 0 && optionBounds.top == 0 && duplicateBounds.top == 0 && removeBounds.top == 0,
                    "Editor controls wrapped instead of staying on one row");
            if (widget.type.equals("scooter")) for (TextView text : texts(card)) if (text.getText().toString().equals("SPORT"))
                require(text.getLayout() != null && text.getLayout().getLineCount() == 1, "Scooter drive mode wrapped onto a second line");
            for (View control : controls) {
                String label = control.getContentDescription().toString();
                boolean resize = label.startsWith("Resize ");
                int expectedTarget = resize ? activity.ui().dp(48) : inlineTarget;
                require(control.getWidth() == expectedTarget && control.getHeight() == expectedTarget
                        && control.isClickable() && control.isFocusable(), label + " lost its equal square touch target");
                Rect target = relativeBounds(control, card);
                require(target.left >= 0 && target.top >= 0 && target.right <= card.getWidth() && target.bottom <= card.getHeight(), label + " target extends outside its card: " + target + " / " + card.getWidth() + "x" + card.getHeight());
                for (Rect other : occupied) require(!Rect.intersects(target, other), label + " touch target overlaps another editor action");
                occupied.add(target);
                require(texts(control).isEmpty(), label + " still contains visible button text");
                if (label.startsWith("Duplicate ")) require(control.isEnabled() != widget.type.equals("lock"), "Duplicate lock restriction changed");
                require(control instanceof ViewGroup, label + " has no glyph container");
                ViewGroup group = (ViewGroup)control;
                require(group.getChildCount() == 1, label + " has unexpected glyph children");
                View glyph = group.getChildAt(0); Rect glyphBox = relativeBounds(glyph, control);
                require(glyphBox.left >= 0 && glyphBox.top >= 0 && glyphBox.right <= control.getWidth() && glyphBox.bottom <= control.getHeight(), label + " glyph is clipped");
                if (resize) {
                    require(glyphBox.equals(new Rect(0, 0, control.getWidth(), control.getHeight())), "Resize mark lost its full-size canvas"); continue;
                }
                String expectedIcon = label.startsWith("Move ") ? "grip" : label.startsWith("Options for ") ? "gear" : label.startsWith("Duplicate ") ? "copy" : "trash";
                require(expectedIcon.contentEquals(glyph.getContentDescription()), label + " displays the wrong icon");
                require(glyphBox.width() == activity.ui().dp(14) && glyphBox.height() == activity.ui().dp(14), label + " icon is not the shared size");
                Drawable paintedSquare = findEditorSquare(control.getBackground());
                require(paintedSquare != null, label + " has no visible centered square");
                Rect square = new Rect(paintedSquare.getBounds());
                require(square.width() == activity.ui().dp(26) && square.height() == activity.ui().dp(26), label + " visual square changed size");
                float dx = glyphBox.exactCenterX() - square.exactCenterX(), dy = glyphBox.exactCenterY() - square.exactCenterY();
                overlayGeometry.add(label + ": target=" + target + ", glyph_center_delta=" + dx + "," + dy);
                require(Math.abs(dx) <= 1 && Math.abs(dy) <= 1, label + " glyph is off-center by " + dx + "," + dy);
                require(Math.abs(square.exactCenterX() - control.getWidth() / 2f) <= 1
                                && Math.abs(square.exactCenterY() - control.getHeight() / 2f) <= 1,
                        label + " visual square is not centered in its touch target");
                if (label.startsWith("Remove ")) require(((Number)field(glyph, "color")).intValue() == activity.ui().danger, "Remove icon lost its destructive color");
            }
        }
    }
    private List<View> editorControls(View root) {
        List<View> found = new ArrayList<>();
        CharSequence description = root.getContentDescription();
        String label = description == null ? "" : description.toString();
        if (label.startsWith("Move ") || label.startsWith("Options for ") || label.startsWith("Duplicate ") || label.startsWith("Remove ") || label.startsWith("Resize ")) found.add(root);
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)root).getChildCount(); i++) found.addAll(editorControls(((ViewGroup)root).getChildAt(i)));
        return found;
    }
    private View described(View root, String prefix) {
        CharSequence label = root.getContentDescription();
        if (label != null && label.toString().startsWith(prefix)) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)root).getChildCount(); i++) {
            View found = described(((ViewGroup)root).getChildAt(i), prefix); if (found != null) return found;
        }
        return null;
    }
    private android.app.AlertDialog editorDialog() { return (android.app.AlertDialog)field(dashboard(), "dialog"); }
    private View dialogRoot() { android.app.AlertDialog dialog = editorDialog(); require(dialog != null && dialog.isShowing(), "Expected editor sheet"); return dialog.getWindow().getDecorView(); }
    private DashboardConfig dashboardConfig() { return (DashboardConfig)field(dashboard(), "config"); }
    private Object tileById(String id) { for (Object item : tiles()) if (((DashboardConfig.Widget)field(item, "widget")).id.equals(id)) return item; throw new IllegalStateException("Missing widget " + id); }
    private void settleMotion() { waitForIdleSync(); SystemClock.sleep(260); waitForIdleSync(); }
    private void verifyEditorActions() throws Exception {
        main(() -> invoke(dashboard(), "toggleEditing")); settleMotion();
        main(() -> assertHistoryControls(false, false));
        final DashboardConfig.Widget source = (DashboardConfig.Widget)field(tile("live"), "widget");
        final int count = dashboardConfig().widgets.size();
        main(() -> {
            source.metric = "power_w"; source.color = 2; source.windowMs = 120000; source.minimum = -80; source.maximum = 1800;
            source.note = "Duplicate fixture"; source.advertisedWh = 650; source.keys.clear(); source.keys.addAll(Arrays.asList("power_w", "battery_percent"));
            described((View)tileById(source.id), "Options for ").performClick();
        }); settleMotion();
        main(() -> {
            View sheet = dialogRoot();
            require(described(sheet, "Duplicate ") == null && described(sheet, "Remove ") == null, "Old destructive actions remain inside the options sheet");
            View save = described(sheet, "Save widget options");
            require(save instanceof TextView && ((TextView)save).getText().toString().equals("✓ Save"), "Options sheet lost its Save action");
            require(save.getHeight() >= activity.ui().dp(48), "Save target is too short");
            ((android.widget.EditText)described(sheet, "Widget title")).setText("Power test"); save.performClick();
        }); settleMotion();
        main(() -> {
            require(source.title.equals("Power test") && editorDialog() == null, "Save did not persist title and close the sheet");
            assertHistoryControls(false, false);
            described((View)tileById(source.id), "Duplicate ").performClick();
        }); settleMotion();
        final AtomicReference<DashboardConfig.Widget> duplicate = new AtomicReference<>();
        main(() -> {
            require(dashboardConfig().widgets.size() == count + 1, "Duplicate did not add exactly one widget");
            for (DashboardConfig.Widget widget : dashboardConfig().widgets) if (widget.type.equals(source.type) && !widget.id.equals(source.id)) duplicate.set(widget);
            DashboardConfig.Widget copy = duplicate.get(); require(copy != null, "Duplicate missing");
            require(copy.title.equals(source.title) && copy.metric.equals(source.metric) && copy.note.equals(source.note)
                    && copy.keys.equals(source.keys) && copy.keys != source.keys && copy.color == source.color && copy.windowMs == source.windowMs
                    && copy.minimum == source.minimum && copy.maximum == source.maximum && copy.advertisedWh == source.advertisedWh, "Duplicate dropped widget options");
            for (Integer columns : source.layouts.keySet()) require(copy.layouts.containsKey(columns) && copy.layouts.get(columns) != source.layouts.get(columns), "Duplicate shares or lost a saved rectangle");
            assertHistoryControls(true, false);
        });
        historyAction("Undo");
        main(() -> { require(dashboardConfig().widgets.size() == count && !containsWidget(duplicate.get().id), "Undo did not remove the duplicated widget"); assertHistoryControls(false, true); });
        historyAction("Redo");
        main(() -> {
            require(dashboardConfig().widgets.size() == count + 1 && containsWidget(duplicate.get().id), "Redo did not restore the duplicated widget identity");
            described((View)tileById(duplicate.get().id), "Remove ").performClick();
        }); settleMotion();
        verifyDeletionHistory(duplicate.get(), count);
        main(() -> {
            require(dashboardConfig().widgets.size() == count && dashboardConfig().widgets.contains(source)
                    && !dashboardConfig().widgets.contains(duplicate.get()), "Remove deleted the wrong widget");
            invoke(dashboard(), "layoutOptions");
        }); settleMotion();
        final boolean sixAvailable = ((Number)invoke(grid(), "maximumColumns")).intValue() >= 6;
        main(() -> {
            View six = described(dialogRoot(), "6 columns"); require(six != null && six.isEnabled() == sixAvailable, "Six-column availability does not match the usable width");
            if (sixAvailable) { six.performClick(); require(settings().getInt("dashboard_columns", 0) == 6, "Six-column selector did not save the choice"); }
            invoke(dashboard(), "dismiss");
        }); settleMotion();
        main(() -> {
            int columns = ((Number)field(grid(), "columns")).intValue();
            require(columns == (sixAvailable ? 6 : ((Number)invoke(grid(), "maximumColumns")).intValue()), "Editor selected an invalid column count");
            if (sixAvailable) {
                DashboardConfig.Widget scooter = (DashboardConfig.Widget)field(tile("scooter"), "widget");
                DashboardConfig.Widget lock = (DashboardConfig.Widget)field(tile("lock"), "widget");
                scooter.layouts.put(6, new DashboardLayout.Cell(scooter.id, 0, 0, 4, 4));
                lock.layouts.put(6, new DashboardLayout.Cell(lock.id, 4, 0, 2, 4));
                invoke(grid(), "settle", new Class<?>[] {String.class}, lock.id);
            }
        }); settleMotion();
        main(() -> {
            assertEditorControls();
            int columns = ((Number)field(grid(), "columns")).intValue();
            ArrayList<DashboardLayout.Cell> cells = new ArrayList<>();
            for (DashboardConfig.Widget widget : dashboardConfig().widgets) {
                DashboardLayout.Cell cell = (DashboardLayout.Cell)invoke(grid(), "currentCell", new Class<?>[] {DashboardConfig.Widget.class}, widget);
                int minimum = ((Number)invoke(grid(), "minimumWidth", new Class<?>[] {DashboardConfig.Widget.class}, widget)).intValue();
                require(cell.w >= minimum && cell.x >= 0 && cell.x + cell.w <= columns, "Widget went below its type's minimum width");
                for (DashboardLayout.Cell other : cells) require(!(cell.x < other.x + other.w && cell.x + cell.w > other.x && cell.y < other.y + other.h && cell.y + cell.h > other.y), "Six-column projected cards overlap");
                cells.add(cell);
            }
            if (sixAvailable) {
                DashboardLayout.Cell scooter = (DashboardLayout.Cell)invoke(grid(), "currentCell", new Class<?>[] {DashboardConfig.Widget.class}, field(tile("scooter"), "widget"));
                DashboardLayout.Cell lock = (DashboardLayout.Cell)invoke(grid(), "currentCell", new Class<?>[] {DashboardConfig.Widget.class}, field(tile("lock"), "widget"));
                require(scooter.w == 4 && lock.w == 2 && scooter.y == lock.y, "Four/two-column scooter and lock split is unavailable");
            }
        }); screenshot(sixAvailable ? "six-column-editor" : "compact-editor");
        verifyResizeCancellation(source);
        verifyCommittedGestureHistory(source, false);
        verifyCommittedGestureHistory(source, true);
        verifyRemovalEffectCancellation();
        main(() -> invoke(dashboard(), "toggleEditing")); settleMotion(); main(this::assertNoEditorControls);
    }
    private void verifyEditorMotion() throws Exception {
        await(() -> grid().hasWindowFocus(), "Motion fixture did not receive window focus");
        final boolean animate = android.animation.ValueAnimator.areAnimatorsEnabled();
        final Map<String, Rect> normalFrames = new LinkedHashMap<>();
        main(() -> {
            for (Object item : tiles()) { View card = (View)item;
                normalFrames.put(((DashboardConfig.Widget)field(item, "widget")).id, new Rect(0, 0, card.getWidth(), card.getHeight())); }
            invoke(dashboard(), "toggleEditing");
            require(dashboard().isEditing(), "Entry animation delayed edit state");
        }); settleMotion();
        main(() -> {
            assertHistoryControls(false, false); assertEditorControls(); assertEditorTransformsSettled();
            for (Object item : tiles()) { View card = (View)item; Rect before = normalFrames.get(((DashboardConfig.Widget)field(item, "widget")).id);
                require(before.width() == card.getWidth() && before.height() == card.getHeight(), "Entry motion changed a widget's final dimensions"); }
        });
        final boolean[] observedJiggle = new boolean[] {false};
        for (int sample = 0; sample < 3; sample++) {
            SystemClock.sleep(100); main(() -> {
                for (Object item : tiles()) {
                    float angle = ((Number)field(item, "jiggleAngle")).floatValue();
                    require(Math.abs(angle) <= .201f, "Editing jiggle exceeded its gentle angle limit");
                    require(((View)item).getRotation() == 0, "Jiggle changed widget hit geometry");
                    if (Math.abs(angle) > .0001f) observedJiggle[0] = true;
                    if (!animate) require(angle == 0, "Disabled animations still jiggled a widget");
                }
                require(animate || Boolean.FALSE.equals(field(grid(), "jigglePosted")), "Disabled animations retained a jiggle callback");
            });
        }
        main(() -> require(!animate || observedJiggle[0], "Editing never showed its gentle jiggle"));
        screenshot("editor-motion");
        final Object moving = tiles().get(0);
        main(() -> {
            View card = (View)moving; int[] location = new int[2]; card.getLocationOnScreen(location);
            invoke(grid(), "beginGesture", new Class<?>[] {card.getClass(), boolean.class, float.class, float.class}, card, false,
                    location[0] + card.getWidth() / 2f, location[1] + card.getHeight() / 2f);
        }); SystemClock.sleep(100);
        main(() -> {
            require(((Number)field(moving, "jiggleAngle")).floatValue() == 0, "Dragged widget kept jiggling");
            invoke(grid(), "finishGesture", new Class<?>[] {boolean.class}, false);
        });
        final AtomicReference<android.app.AlertDialog> focusDialog = new AtomicReference<>();
        main(() -> { focusDialog.set(new android.app.AlertDialog.Builder(activity).setTitle("UI validation").setMessage("Temporary focus fixture").create()); focusDialog.get().show(); });
        await(() -> !grid().hasWindowFocus(), "Focus fixture did not take window focus");
        SystemClock.sleep(120); main(this::assertJiggleStopped);
        main(() -> focusDialog.get().dismiss());
        await(() -> grid().hasWindowFocus(), "Dashboard did not regain window focus"); settleMotion();
        final AtomicReference<Object> exit = new AtomicReference<>();
        final ArrayList<Object> exitGhosts = new ArrayList<>();
        final long touchStarted = SystemClock.uptimeMillis();
        main(() -> {
            if (animate) require(Boolean.TRUE.equals(field(grid(), "jigglePosted")), "Focus return did not resume editor motion");
            invoke(dashboard(), "toggleEditing");
            require(!dashboard().isEditing(), "Exit animation delayed ordinary dashboard state");
            assertNoEditorControls();
            exit.set(field(dashboard(), "editTransition"));
            if (animate) {
                require(exit.get() != null, "Exit omitted its visual transition");
                exitGhosts.addAll((List<?>)field(exit.get(), "ghosts"));
                require(!exitGhosts.isEmpty(), "Exit did not snapshot visible controls");
                long pixels = 0;
                for (Object ghost : exitGhosts) { Bitmap bitmap = (Bitmap)field(ghost, "bitmap");
                    require(bitmap != null && !bitmap.isRecycled() && bitmap.getWidth() <= 1024 && bitmap.getHeight() <= 1024,
                            "Exit control snapshot is unavailable or oversized");
                    pixels += (long)bitmap.getWidth() * bitmap.getHeight(); }
                require(pixels <= 1000000, "Exit snapshots exceed their shared budget");
                android.view.MotionEvent down = android.view.MotionEvent.obtain(touchStarted, touchStarted, android.view.MotionEvent.ACTION_DOWN, 0, 0, 0);
                try { require(grid().dispatchTouchEvent(down) && Boolean.TRUE.equals(field(grid(), "suppressTransitionTouch")),
                        "Exit transition did not consume a touch on its decorative controls"); } finally { down.recycle(); }
            } else require(exit.get() == null, "Disabled motion still created an exit transition");
        }); settleMotion();
        main(() -> {
            assertJiggleStopped(); assertEditorTransformsSettled();
            if (exit.get() != null) {
                android.view.MotionEvent up = android.view.MotionEvent.obtain(touchStarted, SystemClock.uptimeMillis(), android.view.MotionEvent.ACTION_UP, 0, 0, 0);
                try { require(grid().dispatchTouchEvent(up) && Boolean.FALSE.equals(field(grid(), "suppressTransitionTouch")),
                        "Exit touch sequence leaked into the live dashboard"); } finally { up.recycle(); }
                require(Boolean.TRUE.equals(field(exit.get(), "finished")) && field(exit.get(), "observer") == null
                                && ((List<?>)field(exit.get(), "ghosts")).isEmpty(), "Finished exit retained callbacks or ghosts");
                for (Object ghost : exitGhosts) require(field(ghost, "bitmap") == null, "Finished exit retained a control snapshot");
            }
            invoke(dashboard(), "toggleEditing"); Object first = field(dashboard(), "editTransition");
            invoke(dashboard(), "toggleEditing"); Object second = field(dashboard(), "editTransition");
            invoke(dashboard(), "toggleEditing");
            require(dashboard().isEditing(), "Rapid toggles delayed the final editing state");
            for (Object cancelled : new Object[] {first, second}) if (cancelled != null)
                require(Boolean.TRUE.equals(field(cancelled, "finished")) && field(cancelled, "observer") == null,
                        "Rapid toggle left an obsolete transition active");
        }); settleMotion();
        main(() -> {
            View dashboardView = (View)field(dashboard(), "view");
            require(dashboardView.getParent() instanceof ViewGroup, "Dashboard fixture has no attached parent");
            ((ViewGroup)dashboardView.getParent()).removeView(dashboardView);
            assertJiggleStopped();
            invoke(dashboard(), "close");
            assertJiggleStopped();
        });
    }
    private void assertJiggleStopped() {
        require(Boolean.FALSE.equals(field(grid(), "jigglePosted")), "Inactive dashboard retained its jiggle callback");
        for (Object item : tiles()) require(((Number)field(item, "jiggleAngle")).floatValue() == 0 && ((View)item).getRotation() == 0,
                "Inactive dashboard retained a tilted widget");
    }
    private void assertEditorTransformsSettled() {
        require(field(dashboard(), "editTransition") == null, "Finished editor transition remains active");
        require(Math.abs(grid().getTranslationY()) <= .01f && grid().getScaleX() == 1 && grid().getScaleY() == 1
                        && Math.abs(((View)field(dashboard(), "headerActions")).getTranslationY()) <= .01f,
                "Editor transition left the dashboard displaced");
        for (Object item : tiles()) for (View control : editorControls((View)item)) {
            require(Math.abs(control.getScaleX() - 1) <= .001f && Math.abs(control.getScaleY() - 1) <= .001f,
                    "Finished editor transition left a control scaled");
            float expectedAlpha = control.isEnabled() ? 1 : .42f;
            require(Math.abs(control.getAlpha() - expectedAlpha) <= .01f, "Editor pop did not restore the control's normal alpha");
        }
    }
    private boolean containsWidget(String id) {
        for (DashboardConfig.Widget widget : dashboardConfig().widgets) if (widget.id.equals(id)) return true;
        return false;
    }
    private void assertHistoryControls(boolean undoEnabled, boolean redoEnabled) {
        View undo = described(root(), "Undo"), redo = described(root(), "Redo");
        require(undo != null && redo != null && undo.isShown() && redo.isShown(), "Editing is missing Undo/Redo actions");
        require(undo.isEnabled() == undoEnabled && redo.isEnabled() == redoEnabled, "Undo/Redo enabled states do not match history");
        for (View action : new View[] {undo, redo}) require(action.isClickable() && action.isFocusable()
                        && action.getWidth() >= activity.ui().dp(48) && action.getHeight() >= activity.ui().dp(48),
                "Undo/Redo lost its accessible target");
        Rect a = relativeBounds(undo, root()), b = relativeBounds(redo, root());
        View done = described(root(), "Finish editing dashboard"); require(done != null, "Done action is missing beside history");
        Rect doneBounds = relativeBounds(done, root());
        require(!Rect.intersects(a, b) && a.left >= 0 && b.right <= root().getWidth(), "Undo/Redo overlap or escape the window");
        require(a.width() == b.width() && a.height() == b.height() && a.top == b.top && b.left >= a.right
                        && doneBounds.left >= b.right && Math.abs(doneBounds.exactCenterY() - b.exactCenterY()) <= 1,
                "Undo/Redo must be equal adjacent controls in the same row before Done");
    }
    private void historyAction(String prefix) {
        main(() -> { View action = described(root(), prefix); require(action != null && action.isEnabled(), prefix + " is unavailable"); action.performClick(); });
        settleMotion();
    }
    private void verifyDeletionHistory(DashboardConfig.Widget removed, int count) {
        main(() -> {
            require(dashboardConfig().widgets.size() == count && !containsWidget(removed.id), "Remove did not remove exactly its selected widget");
            Object effect = field(dashboard(), "removalEffect");
            if (!android.animation.ValueAnimator.areAnimatorsEnabled()) require(effect == null, "Disabled animations still created a removal effect");
            else if (effect != null) require(Boolean.TRUE.equals(field(effect, "finished")) && field(field(effect, "ghost"), "bitmap") == null,
                    "Completed removal effect retained its snapshot");
        });
        historyAction("Undo");
        main(() -> {
            require(dashboardConfig().widgets.size() == count + 1 && containsWidget(removed.id), "Undo removal lost the widget identity");
            assertHistoryControls(true, true);
            described((View)tileById(removed.id), "Options for ").performClick();
        }); settleMotion();
        main(() -> { View sheet = dialogRoot(); ((android.widget.EditText)described(sheet, "Widget title")).setText("Settings survive history"); described(sheet, "Save widget options").performClick(); });
        settleMotion();
        main(() -> assertHistoryControls(true, true));
        historyAction("Redo");
        main(() -> require(dashboardConfig().widgets.size() == count && !containsWidget(removed.id), "Redo removal did not remove the same widget"));
        historyAction("Undo");
        main(() -> {
            DashboardConfig.Widget restored = (DashboardConfig.Widget)field(tileById(removed.id), "widget");
            require(restored.title.equals("Settings survive history") && restored.metric.equals(removed.metric)
                            && restored.windowMs == removed.windowMs && restored.keys.equals(removed.keys),
                    "Undo removal rolled back widget settings");
        });
        historyAction("Redo");
    }
    private Map<String, String> storedCells() {
        LinkedHashMap<String, String> snapshot = new LinkedHashMap<>();
        for (DashboardConfig.Widget widget : dashboardConfig().widgets)
            for (Map.Entry<Integer, DashboardLayout.Cell> cell : widget.layouts.entrySet())
                snapshot.put(widget.id + ":" + cell.getKey(), cellText(cell.getValue()));
        return snapshot;
    }
    private void verifyCommittedGestureHistory(DashboardConfig.Widget widget, boolean resize) {
        final AtomicReference<Map<String, String>> before = new AtomicReference<>(), after = new AtomicReference<>();
        main(() -> {
            before.set(storedCells()); View card = (View)tileById(widget.id); int[] location = new int[2]; card.getLocationOnScreen(location);
            float x = location[0] + card.getWidth() / 2f, y = location[1] + card.getHeight() / 2f;
            float step = ((Number)invoke(grid(), "rowHeight")).floatValue();
            invoke(grid(), "beginGesture", new Class<?>[] {card.getClass(), boolean.class, float.class, float.class}, card, resize, x, y);
            invoke(grid(), "updateGesture", new Class<?>[] {float.class, float.class}, x, y + step * 2);
            invoke(grid(), "finishGesture", new Class<?>[] {boolean.class}, true);
        }); settleMotion();
        main(() -> {
            after.set(storedCells()); require(!before.get().equals(after.get()), "Committed " + (resize ? "resize" : "move") + " did not change geometry");
            assertHistoryControls(true, false);
        });
        historyAction("Undo"); main(() -> require(before.get().equals(storedCells()), "Undo did not restore every saved rectangle after gesture"));
        historyAction("Redo"); main(() -> require(after.get().equals(storedCells()), "Redo did not restore the committed gesture rectangles"));
        historyAction("Undo");
    }
    private void verifyRemovalEffectCancellation() {
        main(() -> {
            View card = (View)tiles().get(0); DashboardConfig.Widget widget = (DashboardConfig.Widget)field(card, "widget");
            int count = dashboardConfig().widgets.size();
            described(card, "Remove ").performClick();
            require(dashboardConfig().widgets.size() == count - 1 && !containsWidget(widget.id), "Removal waited for animation before deleting");
            Object effect = field(dashboard(), "removalEffect");
            if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
                require(effect != null && Boolean.FALSE.equals(field(effect, "finished")), "Visible removal did not start its brief effect");
                Bitmap snapshot = (Bitmap)field(field(effect, "ghost"), "bitmap");
                require(snapshot != null && !snapshot.isRecycled() && (long)snapshot.getWidth() * snapshot.getHeight() <= 2000000
                                && snapshot.getWidth() <= 2048 && snapshot.getHeight() <= 2048,
                        "Removal snapshot is missing or exceeds its bounded size");
            } else require(effect == null, "Disabled animations still created a removal snapshot");
            described(root(), "Undo").performClick();
            require(dashboardConfig().widgets.size() == count && containsWidget(widget.id), "Immediate Undo did not restore the widget");
            require(field(dashboard(), "removalEffect") == null, "Immediate Undo left a removal effect attached");
            if (effect != null) require(Boolean.TRUE.equals(field(effect, "finished")) && field(field(effect, "ghost"), "bitmap") == null,
                    "Immediate Undo did not release the removal snapshot");
        }); settleMotion();
    }
    private void verifyResizeCancellation(DashboardConfig.Widget widget) {
        final LinkedHashMap<String, String> before = new LinkedHashMap<>();
        main(() -> {
            Object history = field(dashboard(), "editHistory");
            int undoCount = ((java.util.Collection<?>)field(history, "undo")).size();
            int redoCount = ((java.util.Collection<?>)field(history, "redo")).size();
            int columns = ((Number)field(grid(), "columns")).intValue();
            for (DashboardConfig.Widget other : dashboardConfig().widgets) before.put(other.id, cellText(other.layouts.get(columns)));
            View card = (View)tileById(widget.id); int[] location = new int[2]; card.getLocationOnScreen(location);
            float x = location[0] + card.getWidth(), y = location[1] + card.getHeight();
            invoke(grid(), "beginGesture", new Class<?>[] {card.getClass(), boolean.class, float.class, float.class}, card, true, x, y);
            invoke(grid(), "updateGesture", new Class<?>[] {float.class, float.class}, x - card.getWidth() * 3, y - card.getHeight() * 3);
            DashboardLayout.Cell requested = (DashboardLayout.Cell)invoke(grid(), "previewCell");
            int minimum = ((Number)invoke(grid(), "minimumWidth", new Class<?>[] {DashboardConfig.Widget.class}, widget)).intValue();
            require(requested != null && requested.w >= minimum && requested.h >= widget.minimumHeight(), "Resize preview ignored minimum card size");
            invoke(grid(), "finishGesture", new Class<?>[] {boolean.class}, false);
            for (DashboardConfig.Widget other : dashboardConfig().widgets) require(before.get(other.id).equals(cellText(other.layouts.get(columns))), "Cancelled resize changed a saved rectangle");
            require(field(grid(), "gestureTile") == null && field(grid(), "previewBounds") == null, "Cancel left a live drag or preview");
            require(undoCount == ((java.util.Collection<?>)field(history, "undo")).size()
                            && redoCount == ((java.util.Collection<?>)field(history, "redo")).size(),
                    "Cancelled resize consumed history or discarded redo");
        }); settleMotion();
    }
    private String cellText(DashboardLayout.Cell cell) { return cell.x + "," + cell.y + "," + cell.w + "," + cell.h; }
    private Drawable findEditorSquare(Drawable drawable) {
        if (drawable == null) return null;
        if (drawable instanceof GradientDrawable && drawable.getBounds().width() == activity.ui().dp(26)
                && drawable.getBounds().height() == activity.ui().dp(26)) return drawable;
        if (drawable instanceof InsetDrawable) return findEditorSquare(((InsetDrawable)drawable).getDrawable());
        if (drawable instanceof LayerDrawable) {
            LayerDrawable layers = (LayerDrawable)drawable;
            for (int i = 0; i < layers.getNumberOfLayers(); i++) {
                Drawable found = findEditorSquare(layers.getDrawable(i));
                if (found != null) return found;
            }
        }
        return null;
    }
    private void forceMeasure(View view) {
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++)
            forceMeasure(((ViewGroup)view).getChildAt(i));
        view.forceLayout();
    }
    private void assertText(TextView text, View ancestor, String context) {
        if (!text.isShown() || text.length() == 0) return;
        android.text.Layout layout = text.getLayout(); require(layout != null && layout.getLineCount() > 0, context + " text has no layout: " + text.getText());
        Rect rect = relativeBounds(text, ancestor);
        require(rect.left >= 0 && rect.top >= 0 && rect.right <= ancestor.getWidth() + 1 && rect.bottom <= ancestor.getHeight() + 1,
                context + " text outside viewport: " + text.getText() + " " + rect);
        require(layout.getHeight() <= text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom() + 2,
                context + " text clipped vertically: " + text.getText());
        require(layout.getLineEnd(layout.getLineCount() - 1) >= text.length(), context + " text lost lines: " + text.getText());
        for (int line = 0; line < layout.getLineCount(); line++) require(layout.getEllipsisCount(line) == 0, context + " text ellipsized: " + text.getText());
    }
    private Rect relativeBounds(View view, View ancestor) {
        Rect box = new Rect(0, 0, view.getWidth(), view.getHeight());
        while (view != ancestor) {
            require(view.getParent() instanceof View, "Missing ancestor");
            View parent = (View)view.getParent();
            // A child's internal text scroll does not move its hit target; its parent's scroll does.
            box.offset(view.getLeft() - parent.getScrollX(), view.getTop() - parent.getScrollY()); view = parent;
        }
        return box;
    }
    private List<TextView> texts(View view) {
        List<TextView> found = new ArrayList<>(); if (view instanceof TextView) found.add((TextView)view);
        if (view instanceof ViewGroup) for (int i=0; i<((ViewGroup)view).getChildCount(); i++) found.addAll(texts(((ViewGroup)view).getChildAt(i)));
        return found;
    }
    private SharedPreferences settings() { return getTargetContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE); }
    private DashboardScreen dashboard() { return (DashboardScreen)field(activity, "screen"); }
    private ViewGroup grid() { return (ViewGroup)field(dashboard(), "grid"); }
    private View root() { return (View)field(activity, "root"); }
    private List<?> tiles() { return (List<?>)field(dashboard(), "tiles"); }
    private Object tile(String type) { for (Object item : tiles()) if (((DashboardConfig.Widget)field(item, "widget")).type.equals(type)) return item; throw new IllegalStateException("Missing " + type); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(100); waitForIdleSync(); }
    private void check(String text) { checks.add(text); }
    private void screenshot(String name) throws Exception {
        settle(); Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "Screenshot unavailable");
        File folder = new File(getTargetContext().getExternalFilesDir(null), "responsive-validation"); require(folder.isDirectory() || folder.mkdirs(), "Cannot create evidence folder");
        File output = new File(folder, arguments.getString("variant", "responsive").replaceAll("[^A-Za-z0-9_-]", "") + "-" + name + ".png");
        try (FileOutputStream stream = new FileOutputStream(output)) { require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream), "Screenshot failed"); }
        finally { bitmap.recycle(); } screenshots.add(output.getAbsolutePath());
    }
    private void await(BooleanSupplier condition, String message) { long end=SystemClock.elapsedRealtime()+6000;
        while (SystemClock.elapsedRealtime()<end) { AtomicReference<Boolean> result=new AtomicReference<>(false); main(() -> result.set(condition.getAsBoolean())); if(result.get())return; SystemClock.sleep(40); } throw new IllegalStateException(message); }
    private void main(Runnable action) { AtomicReference<Throwable> failure = new AtomicReference<>(); runOnMainSync(() -> { try { action.run(); } catch(Throwable error) { failure.set(error); } }); if(failure.get()!=null) throw new IllegalStateException(failure.get().getMessage(), failure.get()); }
    private static Object field(Object target,String name) { try { Field f=target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch(Exception error) { throw new IllegalStateException("Field " + name,error); } }
    private static void setField(Object target,String name,Object value) { try { Field f=target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target,value); } catch(Exception error) { throw new IllegalStateException("Field " + name,error); } }
    private static Object invoke(Object target,String name) { return invoke(target,name,new Class<?>[0]); }
    private static Object invoke(Object target,String name,Class<?>[] types,Object...args) { try { Method m=target.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(target,args); } catch(Exception error) { throw new IllegalStateException("Method " + name,error); } }
    private static void require(boolean value,String message) { if(!value)throw new IllegalStateException(message); }
}
