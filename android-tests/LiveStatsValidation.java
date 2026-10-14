package local.navee.offlinelock.tests;

import android.Manifest;
import android.app.Instrumentation;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import local.navee.offlinelock.MainActivity;
import local.navee.offlinelock.ScooterProfiles;
import local.navee.offlinelock.ScooterService;
import local.navee.offlinelock.StatsScreen;
import local.navee.offlinelock.Ui;

/** Destructive fixtures only on a fresh disposable emulator. Never connects a real scooter. */
public final class LiveStatsValidation extends Instrumentation {
    private Bundle args;
    private MainActivity activity;
    private int checks;
    private final ArrayList<String> shots = new ArrayList<>();
    @Override public void onCreate(Bundle args) { this.args = args == null ? new Bundle() : args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            admit();
            require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                    .putBoolean("developer_options", true).putString("theme", args.getString("theme", "dark"))
                    .putFloat("text_scale", Float.parseFloat(args.getString("text_scale", "1"))).commit(), "Settings fixture failed");
            main(() -> { ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
                ScooterProfiles.Profile profile = profiles.create("Synthetic Stats fixture", "000000000000", 0);
                profile.serial = "X2538-SYNTHETIC"; profiles.update(profile); });
            activity = (MainActivity)startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.service() != null, "Service did not start");
            navigate("stats");
            main(() -> {
                require("Overview".equals(field(stats(), "selectedSection")), "Stats did not start at Overview");
                View empty = root().findViewWithTag("stats-empty-favourites");
                require(empty != null && empty.isShown() && empty.getBackground() instanceof android.graphics.drawable.GradientDrawable,
                        "Empty favourites affordance is missing");
                require(empty.getHeight() >= activity.ui().dp(120), "Empty favourites target collapsed");
                require(!Boolean.TRUE.equals(field(field(activity, "liveStatsTitle"), "live")), "Disconnected heading looks live");
                require(((TextView)field(field(activity, "liveStatsTitle"), "title")).getCurrentTextColor() == activity.ui().muted,
                        "Disconnected heading is not grey");
            });
            screenshot("overview-empty");
            main(() -> root().findViewWithTag("stats-empty-favourites").performClick()); settle();
            main(() -> {
                Map<?, ?> groups = (Map<?, ?>)field(stats(), "groups"); require(groups.size() > 3, "Reading categories missing");
                for (Object group : groups.values()) {
                    require(!Boolean.TRUE.equals(field(group, "expanded")), "Category starts expanded");
                    require(((View)field(group, "body")).getVisibility() == View.GONE, "Collapsed category still shows rows");
                    TextView title = (TextView)field(group, "title");
                    require(title.getTextSize() >= android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 17 * activity.ui().textScale, title.getResources().getDisplayMetrics()) - 1, "Category heading too small");
                    require(title.getCurrentTextColor() == activity.ui().text && title.getCompoundDrawablesRelative()[2] != null,
                            "Category heading lacks readable disclosure");
                }
                TextView filter = (TextView)field(stats(), "filterButton"); assertTextFits(filter);
                require(filter.getCompoundDrawablesRelative()[0] != null && filter.getContentDescription().toString().contains("Filter"), "Filter affordance is missing");
            });
            screenshot("readings-collapsed");
            main(() -> ((EditText)field(stats(), "search")).setText("voltage")); settle();
            main(() -> {
                Map<?, ?> rows = (Map<?, ?>)field(stats(), "rows"); Object voltage = rows.get("voltage_v");
                require(((View)field(voltage, "layout")).isShown(), "Search did not open matching category");
                ((View)field(voltage, "star")).performClick();
                ((TextView)field(stats(), "filterButton")).performClick();
                require(settings().getStringSet("stats_favourites", java.util.Collections.emptySet()).contains("voltage_v"), "Star did not save favourite");
            }); settle();
            main(() -> {
                Object voltage = ((Map<?, ?>)field(stats(), "rows")).get("voltage_v");
                TextView amount = (TextView)field(voltage, "amount");
                boolean wholeWord = false;
                for (int line = 0; line < amount.getLayout().getLineCount(); line++)
                    wholeWord |= amount.getText().subSequence(amount.getLayout().getLineStart(line), amount.getLayout().getLineEnd(line)).toString().contains("reported");
                require(wholeWord, "Narrow value column split a word instead of stacking");
            });
            screenshot("readings-filtered");
            main(() -> { ((EditText)field(stats(), "search")).setText(""); ((TextView)field(stats(), "filterButton")).performClick();
                invoke(stats(), "selectSection", new Class<?>[]{String.class}, "Overview"); }); settle();
            main(() -> require(((View)field(stats(), "favouritesCard")).isShown()
                    && !root().findViewWithTag("stats-empty-favourites").isShown(), "Favourite did not replace empty state"));
            main(() -> { require(activity.service().setSimulation(true), "Sample fixture could not start"); activity.redraw(); }); settle();
            main(() -> require(Boolean.TRUE.equals(field(field(activity, "liveStatsTitle"), "live")), "Connected sample heading is not live"));
            section("Charts");
            main(() -> {
                Map<?, ?> charts = (Map<?, ?>)field(stats(), "charts"); require(charts.size() == 3, "Charts are still a single selectable graph");
                for (String key : new String[]{"power_w", "voltage_v", "speed_kmh"}) {
                    Ui.ChartView chart = (Ui.ChartView)charts.get(key);
                    require(chart != null && chart.getVisibility() == View.VISIBLE && chart.pointCount() > 1, "Missing independent chart " + key);
                    require(chart.getParent() != ((View)charts.get(key.equals("power_w") ? "voltage_v" : "power_w")).getParent(), "Charts share a card");
                }
                require("W".equals(field(charts.get("power_w"), "unit")), "Power chart unit wrong");
                require("V".equals(field(charts.get("voltage_v"), "unit")), "Voltage chart unit wrong");
                require("mph".equals(field(charts.get("speed_kmh"), "unit")), "Speed chart did not convert units");
                View tabs = root().findViewWithTag("live-stats-sections");
                require(tabs != null, "Shared segmented selector missing");
                if (tabs.getResources().getConfiguration().fontScale <= 1.01f) {
                    View track = (View)field(tabs, "track");
                    require(track.getWidth() <= tabs.getWidth(), "Default section labels do not all fit the selector");
                }
            });
            screenshot("charts-top");
            main(() -> ((android.widget.ScrollView)field(stats(), "sectionScroll")).fullScroll(View.FOCUS_DOWN)); settle(); screenshot("charts-bottom");
            verifyRetention();
            main(() -> { activity.service().setSimulation(false); activity.redraw(); }); settle();
            main(() -> { require(!Boolean.TRUE.equals(field(field(activity, "liveStatsTitle"), "live")), "Disconnect left a live pulse");
                require(field(activity.service().ble, "gatt") == null && field(activity.service().ble, "scanner") == null,
                        "Stats validation touched real Bluetooth");
                require(!activity.service().isRecording() && !activity.service().phoneSaving(), "Stats started recording"); });
            result.putString("result", "PASS");
        } catch (Throwable error) { result.putString("result", "FAIL"); result.putString("error", error.toString());
            error.printStackTrace(); try { if (activity != null) screenshot("failure"); } catch (Throwable ignored) {} }
        finally { result.putInt("assertions", checks); result.putStringArrayList("screenshots", shots);
            if (activity != null) main(() -> { activity.finish(); getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); });
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result); }
    }
    private void verifyRetention() {
        section("Diagnostics"); navigate("home"); navigate("stats");
        main(() -> require("Diagnostics".equals(field(stats(), "selectedSection")), "Brief tab return forgot selected section"));
        navigate("home");
        main(() -> settings().edit().putLong("stats_away_elapsed", SystemClock.elapsedRealtime() - 10000).commit());
        navigate("stats");
        main(() -> require("Overview".equals(field(stats(), "selectedSection")), "Ten-second tab absence did not return Overview"));
        section("Charts");
        main(() -> { callActivityOnPause(activity); settings().edit().putLong("stats_away_elapsed", SystemClock.elapsedRealtime() - 10000).commit(); callActivityOnResume(activity); }); settle();
        main(() -> require("Overview".equals(field(stats(), "selectedSection")), "Background absence did not return Overview"));
        section("Charts");
        main(() -> { callActivityOnPause(activity); callActivityOnResume(activity); }); settle();
        main(() -> require("Charts".equals(field(stats(), "selectedSection")), "Brief background return forgot selected section"));
    }
    private void admit() {
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
        require(hardware.contains("ranchu") || hardware.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "Refusing a physical phone");
        for (String name : new String[]{"app_settings", "profiles", "scooter", "pending_documents", "onboarding"})
            require(getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE).getAll().isEmpty(), "Refusing existing user preferences");
        File[] files = getTargetContext().getFilesDir().listFiles();
        require(files != null && files.length == 0 && getTargetContext().databaseList().length == 0, "Refusing existing user data");
        for (String permission : new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT})
            require(getTargetContext().checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED, "Nearby permission missing");
        BluetoothManager manager = getTargetContext().getSystemService(BluetoothManager.class);
        require(manager == null || manager.getAdapter() == null || !manager.getAdapter().isEnabled(), "Disable emulator Bluetooth first");
    }
    private void assertTextFits(TextView text) {
        require(text.getWidth() >= activity.ui().dp(48) && text.getHeight() >= activity.ui().dp(48), "Filter target too small");
        require(text.getLayout() != null && text.getLayout().getLineCount() > 0, "Filter has no rendered text");
        for (int line = 0; line < text.getLayout().getLineCount(); line++)
            require(text.getLayout().getLineWidth(line) <= text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight() + 1,
                    "Filter text extends beyond button");
    }
    private SharedPreferences settings() { return getTargetContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE); }
    private StatsScreen stats() { Object screen = field(activity, "screen"); require(screen instanceof StatsScreen, "Stats is not active"); return (StatsScreen)screen; }
    private View root() { return (View)field(activity, "root"); }
    private void navigate(String page) { main(() -> invoke(activity, "select", new Class<?>[]{String.class}, page)); settle(); }
    private void section(String section) { main(() -> invoke(stats(), "selectSection", new Class<?>[]{String.class}, section)); settle(); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(300); waitForIdleSync(); }
    private void main(Runnable action) {
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new IllegalStateException("UI assertion failed", failure.get());
    }
    private void require(boolean condition, String message) { checks++; if (!condition) throw new IllegalStateException(message); }
    private void await(BooleanSupplier condition, String message) { long end = SystemClock.uptimeMillis() + 8000; while (!condition.getAsBoolean() && SystemClock.uptimeMillis() < end) SystemClock.sleep(40); require(condition.getAsBoolean(), message); }
    private Object field(Object target, String name) { try { Class<?> type = target.getClass(); while (type != null) { try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(target); } catch (NoSuchFieldException e) { type = type.getSuperclass(); } } throw new NoSuchFieldException(name); } catch (Exception e) { throw new IllegalStateException("Field " + name, e); } }
    private Object invoke(Object target, String name, Class<?>[] types, Object... values) { try { Method method = target.getClass().getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(target, values); } catch (Exception e) { throw new IllegalStateException("Method " + name, e); } }
    private void screenshot(String label) throws Exception {
        File directory = new File(getTargetContext().getExternalFilesDir(null), "stats-validation"); require(directory.isDirectory() || directory.mkdirs(), "Screenshot folder unavailable");
        File file = new File(directory, label + ".png"); android.graphics.Bitmap image = getUiAutomation().takeScreenshot(); require(image != null, "Screenshot unavailable");
        try (FileOutputStream output = new FileOutputStream(file)) { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output); } finally { image.recycle(); }
        shots.add(file.getAbsolutePath());
    }
}
