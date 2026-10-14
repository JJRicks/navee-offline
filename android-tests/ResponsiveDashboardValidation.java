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
            if (mode.equals("edit")) {
                verifyEditOverlays();
                check("Move and options glyphs stay centered in their visible squares with full 48 dp targets; resize target remains intact");
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
        main(() -> ((Handler) field(activity, "handler")).removeCallbacks((Runnable) field(activity, "pulse")));
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
                invoke(item, "refresh", new Class<?>[] {Map.class}, preview); grid().requestLayout();
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
    private void verifyEditOverlays() throws Exception {
        main(() -> invoke(dashboard(), "toggleEditing"));
        settle(); screenshot("edit-overlays");
        main(() -> {
            int moves = 0, options = 0, resizes = 0;
            ArrayList<String> failures = new ArrayList<>();
            for (Object item : tiles()) {
                ViewGroup card = (ViewGroup)item;
                for (int i = 0; i < card.getChildCount(); i++) {
                    View child = card.getChildAt(i);
                    CharSequence description = child.getContentDescription();
                    if (description == null) continue;
                    String label = description.toString();
                    if (!label.startsWith("Move ") && !label.startsWith("Options for ") && !label.startsWith("Resize ")) continue;
                    require(child instanceof ViewGroup, label + " has no glyph container");
                    require(child.getWidth() == activity.ui().dp(48) && child.getHeight() == activity.ui().dp(48)
                            && child.isClickable() && child.isFocusable(), label + " lost its accessible 48 dp target");
                    Rect target = relativeBounds(child, card);
                    require(target.left >= 0 && target.top >= 0 && target.right <= card.getWidth() && target.bottom <= card.getHeight(),
                            label + " target extends outside its card");
                    ViewGroup control = (ViewGroup)child;
                    require(control.getChildCount() == 1, label + " has unexpected glyph children");
                    View glyph = control.getChildAt(0);
                    Rect glyphBox = relativeBounds(glyph, control);
                    require(glyphBox.left >= 0 && glyphBox.top >= 0 && glyphBox.right <= control.getWidth() && glyphBox.bottom <= control.getHeight(),
                            label + " glyph is clipped");
                    if (label.startsWith("Resize ")) {
                        resizes++;
                        require(glyphBox.equals(new Rect(0, 0, control.getWidth(), control.getHeight())), "Resize mark lost its full-size canvas");
                        continue;
                    }
                    if (label.startsWith("Move ")) moves++; else options++;
                    InsetDrawable inset = findInset(control.getBackground());
                    require(inset != null, label + " has no visible inset square");
                    Rect square = new Rect(inset.getDrawable().getBounds());
                    require(square.width() == activity.ui().dp(26) && square.height() == activity.ui().dp(26), label + " visual square changed size");
                    float dx = glyphBox.exactCenterX() - square.exactCenterX(), dy = glyphBox.exactCenterY() - square.exactCenterY();
                    overlayGeometry.add(label + ": target=" + control.getWidth() + "x" + control.getHeight()
                            + ", square=" + square + ", glyph=" + glyphBox + ", center_delta=" + dx + "," + dy);
                    if (Math.abs(dx) > 1 || Math.abs(dy) > 1) failures.add(label + " glyph is off-center by " + dx + "," + dy + " px");
                }
            }
            require(moves == tiles().size() && options == moves && resizes == moves, "Not all widget edit controls were inspected");
            require(failures.isEmpty(), failures.toString());
        });
    }
    private InsetDrawable findInset(Drawable drawable) {
        if (drawable instanceof InsetDrawable) return (InsetDrawable)drawable;
        if (drawable instanceof LayerDrawable) {
            LayerDrawable layers = (LayerDrawable)drawable;
            for (int i = 0; i < layers.getNumberOfLayers(); i++) {
                InsetDrawable found = findInset(layers.getDrawable(i));
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
        while (view != ancestor) { box.offset(view.getLeft() - view.getScrollX(), view.getTop() - view.getScrollY());
            require(view.getParent() instanceof View, "Missing ancestor"); view = (View)view.getParent(); }
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
