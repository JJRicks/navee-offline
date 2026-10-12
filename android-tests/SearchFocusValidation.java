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
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import local.navee.offlinelock.BleController;
import local.navee.offlinelock.DashboardConfig;
import local.navee.offlinelock.DashboardScreen;
import local.navee.offlinelock.MainActivity;
import local.navee.offlinelock.ScooterService;
import local.navee.offlinelock.StatsScreen;
import local.navee.offlinelock.Telemetry;

/** Real IME and touch checks on a fresh emulator; no scooter or radio changes. */
public final class SearchFocusValidation extends Instrumentation {
    private static final String[] PREFS = {"app_settings", "profiles", "scooter", "pending_documents"};
    private final List<String> checks = new ArrayList<>(), screenshots = new ArrayList<>();
    private MainActivity activity;
    private boolean admitted;
    private Bundle arguments;
    private int keyboardRequests;
    private Rect lastTapScreenBounds = new Rect();
    @Override public void onCreate(Bundle args) { arguments = args == null ? new Bundle() : args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            requireFreshEmulator(); admitted = true;
            require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                    .putString("theme", "dark").commit(), "Could not prepare emulator preferences");
            activity = (MainActivity)startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.service() != null && root().isAttachedToWindow() && root().getWidth() > 0, 6000, "Workspace did not initialize");
            for (String theme : new String[] {"dark", "light"}) {
                main(() -> settings().edit().putString("theme", theme).apply());
                await(() -> activity.ui().dark == theme.equals("dark"), 4000, "Theme did not apply");
                controlsChecks(theme); statsChecks(theme); dialogChecks(theme); assertNoScooterWork();
            }
            result.putString("result", "PASS"); result.putStringArrayList("checks", new ArrayList<>(checks));
            result.putStringArrayList("screenshots", new ArrayList<>(screenshots)); result.putInt("check_count", checks.size());
            result.putBoolean("actual_ime_visible", true); result.putInt("scooter_mutations", 0); cleanup(); finish(0, result);
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", error.getClass().getSimpleName() + ": " + error.getMessage());
            result.putStringArrayList("completed_checks", new ArrayList<>(checks));
            try { if (admitted && activity != null && !activity.isDestroyed()) screenshot("failure"); } catch (Throwable ignored) { }
            result.putStringArrayList("screenshots", new ArrayList<>(screenshots));
            try { cleanup(); } catch (Throwable failure) { result.putString("cleanup_error", failure.toString()); }
            finish(1, result);
        }
    }
    private void controlsChecks(String theme) {
        clickDescription("Scooter"); Object controls = screen(); EditText search = (EditText)field(controls, "search");
        showKeyboard(search, activity.getWindow(), "speed"); tap((View)field(controls, "results"));
        assertDismissed(search, activity.getWindow(), "speed", "Outside results tap");
        require(screen() == controls, "An inert outside tap navigated away from settings");
        showKeyboard(search, activity.getWindow(), "speed"); sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        assertDismissed(search, activity.getWindow(), "speed", "System Back");
        require(screen() == controls, "Hiding the keyboard also navigated away");
        showKeyboard(search, activity.getWindow(), "speed"); tap(findDescription(root(), "Stats"));
        await(() -> screen() instanceof StatsScreen && !imeVisible(activity.getWindow()), 4000,
                "Outside navigation tap was swallowed or left the keyboard open");
        checks.add(theme + ": outside taps and system Back clear settings search focus, preserve the filter, and deliver navigation actions");
    }
    private void statsChecks(String theme) {
        clickText(root(), "Readings"); Object stats = screen(); EditText search = (EditText)field(stats, "search");
        showKeyboard(search, activity.getWindow(), "battery");
        boolean original = settings().getBoolean("stats_favourites_only", false); tap((View)field(stats, "filterButton"));
        assertDismissed(search, activity.getWindow(), "battery", "Stats filter tap");
        require(settings().getBoolean("stats_favourites_only", false) != original, "Dismissing search consumed the Favourites action");
        if (settings().getBoolean("stats_favourites_only", false)) click((View)field(stats, "filterButton"));
        showKeyboard(search, activity.getWindow(), "battery"); ScrollView scroll = (ScrollView)field(activity, "scroll");
        int before = scroll.getScrollY(); Rect bounds = new Rect(), fieldBounds = new Rect();
        main(() -> { require(scroll.getGlobalVisibleRect(bounds), "Stats scroller is not visible");
            require(search.getGlobalVisibleRect(fieldBounds), "Stats search is not visible"); });
        float x = bounds.centerX(), start = bounds.bottom - activity.ui().dp(18);
        float end = Math.max(fieldBounds.bottom + activity.ui().dp(18), start - activity.ui().dp(120));
        require(start - end > activity.ui().dp(25), "No outside-search area available for a real swipe");
        long now = SystemClock.uptimeMillis();
        inject(now, MotionEvent.ACTION_DOWN, x, start);
        for (int i = 1; i <= 10; i++) { inject(now, MotionEvent.ACTION_MOVE, x, start + (end - start) * i / 10f); SystemClock.sleep(16); }
        inject(now, MotionEvent.ACTION_UP, x, end);
        assertDismissed(search, activity.getWindow(), "battery", "Outside swipe");
        await(() -> scroll.getScrollY() > before, 3000, "Dismissing search consumed the scrolling gesture");
        checks.add(theme + ": measurement search preserves its query while outside taps and swipes still filter and scroll");
    }
    private void dialogChecks(String theme) throws Exception {
        clickDescription("Home"); await(() -> screen() instanceof DashboardScreen, 4000, "Dashboard did not appear");
        DashboardScreen dashboard = (DashboardScreen)screen();
        if (!dashboard.isEditing()) clickDescription("Edit dashboard");
        Object tile = liveTile(dashboard); final DashboardConfig.Widget widget = (DashboardConfig.Widget)field(tile, "widget");
        click(findDescription((View)tile, "Options for " + widget.title)); clickText(dialogRoot(dashboard), "Parameter");
        AlertDialog picker = dialog(dashboard); EditText search = first(picker.getWindow().getDecorView(), EditText.class);
        showKeyboard(search, picker.getWindow(), "voltage"); tap(findText(picker.getWindow().getDecorView(), "Choose parameter"));
        assertDismissed(search, picker.getWindow(), "voltage", "Dialog title tap");
        require(picker.isShowing(), "An inert title tap dismissed the parameter picker");
        showKeyboard(search, picker.getWindow(), "voltage"); sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        assertDismissed(search, picker.getWindow(), "voltage", "Dialog system Back");
        require(picker.isShowing(), "Hiding the picker keyboard also dismissed the dialog");
        showKeyboard(search, picker.getWindow(), "voltage"); tap(findText(picker.getWindow().getDecorView(), metricLabel("voltage_v")));
        await(() -> !picker.isShowing() && widget.metric.equals("voltage_v") && !imeVisible(activity.getWindow()), 4000,
                "Outside parameter tap was swallowed or did not save the selection");
        checks.add(theme + ": parameter picker clears keyboard focus without clearing its query or consuming a parameter selection");

        tile = liveTile(dashboard); DashboardConfig.Widget current = (DashboardConfig.Widget)field(tile, "widget");
        click(findDescription((View)tile, "Options for " + current.title)); clickText(dialogRoot(dashboard), "Title");
        AlertDialog title = dialog(dashboard); EditText ordinary = first(title.getWindow().getDecorView(), EditText.class);
        showKeyboard(ordinary, title.getWindow(), "Ordinary title form"); tap(findText(title.getWindow().getDecorView(), "Title"));
        waitForIdleSync(); SystemClock.sleep(180);
        main(() -> require(ordinary.hasFocus() && "Ordinary title form".contentEquals(ordinary.getText()),
                "Search-only dismissal changed focus or text in an ordinary title form"));
        screenshot(theme + "-title-form"); click(title.getButton(AlertDialog.BUTTON_NEGATIVE));
        main(() -> getTargetContext().getSystemService(InputMethodManager.class).hideSoftInputFromWindow(root().getWindowToken(), 0));
        await(() -> !imeVisible(activity.getWindow()), 4000, "Form keyboard did not close"); clickText(root(), "Done");
        checks.add(theme + ": ordinary widget title editing retains its normal focus and text behavior");
    }
    private void showKeyboard(EditText search, Window window, String text) {
        require(search != null, "Search field is missing");
        String stage = "IME request #" + (++keyboardRequests) + " in "
                + (window == activity.getWindow() ? screen().getClass().getSimpleName() : "widget dialog")
                + " (" + text + ")";
        await(() -> window.getDecorView().hasWindowFocus() && search.isAttachedToWindow() && search.isShown(),
                4000, stage + ": input window did not gain focus");
        main(() -> search.requestRectangleOnScreen(new Rect(0, 0, search.getWidth(), search.getHeight()), true)); waitForIdleSync();
        awaitStableInputBounds(search, window, stage);
        // Touch focus follows the same path as the user. Calling requestFocus and
        // showSoftInput together can race IMM after the neutral root held focus.
        tap(search);
        try { await(search::hasFocus, 3000, stage + ": tapping the field did not focus it"); }
        catch (IllegalStateException error) { throw new IllegalStateException(error.getMessage() + "; " + keyboardDiagnostics(search, window), error); }
        waitForIdleSync(); SystemClock.sleep(100);
        main(() -> {
            search.setText(text); search.setSelection(search.length());
            InputMethodManager keyboard = getTargetContext().getSystemService(InputMethodManager.class);
            require(keyboard != null, "No input method service"); keyboard.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
        });
        try {
            await(() -> search.hasFocus() && imeVisible(window), 5000,
                    stage + ": software keyboard was not shown; enable the emulator software keyboard for this validation");
        } catch (IllegalStateException error) { throw new IllegalStateException(error.getMessage() + "; " + keyboardDiagnostics(search, window), error); }
        waitForIdleSync(); SystemClock.sleep(250);
    }
    private void awaitStableInputBounds(EditText search, Window window, String stage) {
        Rect previous = new Rect(); final long[] since = {SystemClock.elapsedRealtime()};
        await(() -> {
            Rect next = screenBounds(search);
            boolean steady = next.equals(previous) && !next.isEmpty() && window.getDecorView().hasWindowFocus()
                    && !window.getDecorView().isLayoutRequested();
            if (!steady) since[0] = SystemClock.elapsedRealtime();
            previous.set(next);
            return steady && SystemClock.elapsedRealtime() - since[0] >= 300;
        }, 4000, stage + ": input bounds or window focus did not settle");
    }
    private Rect screenBounds(View view) {
        Rect visible = new Rect();
        if (!view.getLocalVisibleRect(visible)) return new Rect();
        int[] origin = new int[2]; view.getLocationOnScreen(origin);
        visible.offset(origin[0], origin[1]); return visible;
    }
    private String keyboardDiagnostics(EditText search, Window window) {
        AtomicReference<String> details = new AtomicReference<>();
        main(() -> {
            View focus = window.getCurrentFocus(), decor = window.getDecorView();
            InputMethodManager keyboard = getTargetContext().getSystemService(InputMethodManager.class);
            ArrayList<String> windows = new ArrayList<>();
            for (android.view.accessibility.AccessibilityWindowInfo item : getUiAutomation().getWindows())
                windows.add(item.getType() + ":" + item.getTitle());
            details.set("searchBounds=" + screenBounds(search) + ", tappedBounds=" + lastTapScreenBounds
                    + ", tapped=[" + lastTapScreenBounds.centerX() + "," + lastTapScreenBounds.centerY() + "]"
                    + ", decorBounds=" + screenBounds(decor)
                    + ", windowCallback=" + (window.getCallback() == null ? "null" : window.getCallback().getClass().getName())
                    + ", currentFocus=" + (focus == null ? "null" : focus.getClass().getSimpleName() + ":" + focus.getContentDescription())
                    + ", windowFocus=" + decor.hasWindowFocus() + ", imeVisible=" + imeVisible(window)
                    + ", immActive=" + (keyboard != null && keyboard.isActive(search))
                    + ", fieldFocus=" + search.hasFocus() + ", enabled=" + search.isEnabled()
                    + ", focusable=" + search.isFocusable() + "/" + search.isFocusableInTouchMode()
                    + ", attached=" + search.isAttachedToWindow() + ", selection=[" + search.getSelectionStart() + "," + search.getSelectionEnd() + "]"
                    + ", accessibilityWindows=" + windows);
        });
        return details.get();
    }
    private boolean imeVisible(Window window) {
        WindowInsets insets = window.getDecorView().getRootWindowInsets();
        return insets != null && insets.isVisible(WindowInsets.Type.ime());
    }
    private void assertDismissed(EditText input, Window window, String text, String action) {
        try { await(() -> !input.hasFocus() && !imeVisible(window), 4000, action + " did not hide keyboard and clear search focus"); }
        catch (IllegalStateException error) {
            throw new IllegalStateException(error.getMessage() + "; " + keyboardDiagnostics(input, window), error);
        }
        main(() -> require(text.contentEquals(input.getText()), action + " changed the search text"));
    }
    private void tap(View view) {
        require(view != null, "Missing touch target"); Rect rect = new Rect();
        main(() -> {
            rect.set(screenBounds(view)); require(rect.width() > 0 && rect.height() > 0, "Touch target is not visible");
            lastTapScreenBounds = new Rect(rect);
        });
        long now = SystemClock.uptimeMillis(); inject(now, MotionEvent.ACTION_DOWN, rect.centerX(), rect.centerY());
        inject(now, MotionEvent.ACTION_UP, rect.centerX(), rect.centerY()); waitForIdleSync();
    }
    private void inject(long down, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0); event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { require(getUiAutomation().injectInputEvent(event, true), "Emulator rejected a touch event"); } finally { event.recycle(); }
    }
    private void requireFreshEmulator() {
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
        require(hardware.contains("ranchu") || hardware.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "Search validation refuses physical phones");
        require(Build.VERSION.SDK_INT >= 31, "Use Android 12 or newer");
        for (String key : PREFS) require(getTargetContext().getSharedPreferences(key, Context.MODE_PRIVATE).getAll().isEmpty(),
                "Fresh emulator app data required; existing preferences refused");
        File[] files = getTargetContext().getFilesDir().listFiles();
        require(files != null && files.length == 0 && getTargetContext().databaseList().length == 0, "Fresh emulator files and databases required");
        require(getTargetContext().checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && getTargetContext().checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED,
                "Grant Nearby devices externally on the fresh emulator");
        BluetoothManager manager = getTargetContext().getSystemService(BluetoothManager.class); BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        require(adapter == null || !adapter.isEnabled(), "Bluetooth must be off; validation never changes radio state");
    }
    private void assertNoScooterWork() {
        main(() -> {
            ScooterService service = activity.service();
            require(service != null && service.profiles.all().isEmpty() && !service.isRecording() && !service.phoneSaving()
                    && service.ble.state() == BleController.State.DISCONNECTED, "Unexpected scooter or recording work");
            require(((Number)field(service.ble, "mutationSequence")).longValue() == 0 && service.ble.commandResults().isEmpty(), "Unexpected scooter write");
        });
    }
    private Object liveTile(DashboardScreen dashboard) {
        for (Object tile : (List<?>)field(dashboard, "tiles"))
            if (((DashboardConfig.Widget)field(tile, "widget")).type.equals("live")) return tile;
        throw new IllegalStateException("Default live widget is missing");
    }
    private AlertDialog dialog(Object owner) {
        AlertDialog value = (AlertDialog)field(owner, "dialog"); require(value != null && value.isShowing(), "Widget dialog is missing"); return value;
    }
    private View dialogRoot(Object owner) { return dialog(owner).getWindow().getDecorView(); }
    private Object screen() { return field(activity, "screen"); }
    private View root() { return (View)field(activity, "root"); }
    private SharedPreferences settings() { return getTargetContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE); }
    private String metricLabel(String key) { for (Telemetry.Metric m : new Telemetry().metrics()) if (m.key.equals(key)) return m.label; throw new IllegalStateException(key); }
    private void clickDescription(String name) { click(findDescription(root(), name)); }
    private void clickText(View root, String text) { click(findText(root, text)); }
    private void click(View target) {
        require(target != null, "UI action is missing"); View candidate = target;
        while (!candidate.isClickable() && candidate.getParent() instanceof View) candidate = (View)candidate.getParent();
        View action = candidate; require(action.isClickable() && action.isEnabled(), "UI action is disabled");
        main(() -> { action.requestRectangleOnScreen(new Rect(0, 0, action.getWidth(), action.getHeight()), true);
            require(action.performClick(), "UI action did not dispatch"); }); waitForIdleSync();
    }
    private View findDescription(View view, String name) {
        if (name.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) {
            View found = findDescription(((ViewGroup)view).getChildAt(i), name); if (found != null) return found;
        }
        return null;
    }
    private TextView findText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView)view).getText()) && view.isShown()) return (TextView)view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) {
            TextView found = findText(((ViewGroup)view).getChildAt(i), text); if (found != null) return found;
        }
        return null;
    }
    private <T extends View> T first(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) {
            T found = first(((ViewGroup)view).getChildAt(i), type); if (found != null) return found;
        }
        return null;
    }
    private void screenshot(String name) throws Exception {
        Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "Screenshot unavailable");
        File folder = new File(getTargetContext().getExternalFilesDir(null), "search-validation"); require(folder.isDirectory() || folder.mkdirs(), "Cannot create screenshot directory");
        String variant = arguments.getString("variant", "search").replaceAll("[^A-Za-z0-9_-]", ""); File file = new File(folder, variant + "-" + name + ".png");
        try (FileOutputStream stream = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); }
        finally { bitmap.recycle(); } screenshots.add(file.getAbsolutePath());
    }
    private void cleanup() {
        if (!admitted) return;
        if (activity != null && !activity.isDestroyed()) { main(activity::finish); await(activity::isDestroyed, 4000, "Activity did not finish"); }
        main(() -> { ScooterService.setUiVisible(false); getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); }); waitForIdleSync(); admitted = false;
    }
    private void await(BooleanSupplier condition, long timeout, String message) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < deadline) { AtomicReference<Boolean> result = new AtomicReference<>(false);
            main(() -> result.set(condition.getAsBoolean())); if (result.get()) return; SystemClock.sleep(40); }
        throw new IllegalStateException(message);
    }
    private void main(Runnable action) {
        AtomicReference<Throwable> error = new AtomicReference<>(); runOnMainSync(() -> { try { action.run(); } catch (Throwable failure) { error.set(failure); } });
        if (error.get() != null) throw new IllegalStateException(error.get().getMessage(), error.get());
    }
    private static Object field(Object object, String name) {
        try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot inspect " + name, error); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
