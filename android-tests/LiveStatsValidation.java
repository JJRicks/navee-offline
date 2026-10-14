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
                require(findText((View)field(stats(), "readingsSection"), "Favourites") == null, "Redundant favourites filter remains on Readings");
                TextView count = (TextView)field(stats(), "resultCount");
                require(count.getText().toString().matches("[0-9]+ readings"), "Reading count missing");
                require(((View)count.getParent()).getHeight() >= activity.ui().dp(48), "Count row lost its spacing");
            });
            screenshot("readings-collapsed");
            main(() -> ((EditText)field(stats(), "search")).setText("voltage")); settle();
            main(() -> {
                Map<?, ?> rows = (Map<?, ?>)field(stats(), "rows"); Object voltage = rows.get("voltage_v");
                require(((View)field(voltage, "layout")).isShown(), "Search did not open matching category");
                ((View)field(voltage, "star")).performClick();
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
            main(() -> { ((EditText)field(stats(), "search")).setText("");
                invoke(stats(), "selectSection", new Class<?>[]{String.class}, "Overview"); }); settle();
            main(() -> require(((View)field(stats(), "favouritesCard")).isShown()
                    && !root().findViewWithTag("stats-empty-favourites").isShown(), "Favourite did not replace empty state"));
            main(() -> { require(activity.service().setSimulation(true), "Sample fixture could not start"); activity.redraw(); }); settle();
            main(() -> require(Boolean.TRUE.equals(field(field(activity, "liveStatsTitle"), "live")), "Connected sample heading is not live"));
            section("Charts");
            main(() -> {
                Object browser = field(stats(), "chartBrowser"); Map<?, ?> charts = (Map<?, ?>)field(browser, "cards");
                long numeric = activity.service().displayReadings().values().stream().filter(r -> r.number != null).count();
                require(charts.size() == numeric && charts.size() > 30, "Numerical chart catalogue is incomplete");
                for (Object card : charts.values()) require(field(card, "graph") == null, "Collapsed chart eagerly allocated a graph");
                require(!charts.containsKey("scooter_serial"), "Text field presented as numerical chart");
                ((EditText)field(browser, "search")).setText("Battery power");
            }); settle();
            main(() -> ((android.widget.ScrollView)field(stats(), "sectionScroll")).fullScroll(View.FOCUS_DOWN)); settle();
            main(() -> {
                Object browser = field(stats(), "chartBrowser"); Map<?, ?> charts = (Map<?, ?>)field(browser, "cards");
                Object card = charts.get("power_w"); Ui.ChartView graph = (Ui.ChartView)field(card, "graph");
                require(graph != null && graph.pointCount() > 1 && graph.isShown(), "Visible chart did not load sample history");
                require("W".equals(field(graph, "unit")), "Power chart unit wrong");
                verifySavedTailMerge(browser, card, graph);
                int allocated = 0; for (Object value : charts.values()) if (field(value, "graph") != null) allocated++;
                require(allocated <= 2, "Search loaded hidden numerical charts");
                ((View)field(card, "star")).performClick();
                require(settings().getStringSet("stats_chart_favourites", java.util.Collections.emptySet()).contains("power_w"), "Chart favourite not persisted");
                invoke(browser, "selectWindow", new Class<?>[]{int.class}, 7);
                require(settings().getInt("stats_chart_window", -1) == 7, "All window not persisted");
                invoke(browser, "expand", new Class<?>[]{String.class}, "power_w");
            }); settle();
            main(() -> {
                Object browser = field(stats(), "chartBrowser"); Object card = field(browser, "fullscreenCard");
                Ui.ChartView graph = (Ui.ChartView)field(card, "graph");
                require(graph != null && graph.pointCount() > 1 && graph.getHeight() > activity.ui().dp(220), "Fullscreen graph did not expand/load");
                long time = SystemClock.uptimeMillis();
                graph.dispatchTouchEvent(android.view.MotionEvent.obtain(time, time, 0, graph.getWidth() * .5f, graph.getHeight() * .5f, 0));
                graph.dispatchTouchEvent(android.view.MotionEvent.obtain(time, time + 10, 1, graph.getWidth() * .5f, graph.getHeight() * .5f, 0));
                require(((Number)field(graph, "selected")).intValue() >= 0, "Fullscreen trace inspection missing");
            }); screenshot("chart-fullscreen");
            main(() -> ((android.app.Dialog)field(field(stats(), "chartBrowser"), "fullscreen")).dismiss());
            main(() -> ((EditText)field(field(stats(), "chartBrowser"), "search")).setText("temperature_c")); settle();
            main(() -> ((android.widget.ScrollView)field(stats(), "sectionScroll")).fullScroll(View.FOCUS_DOWN)); settle();
            main(() -> {
                Object browser = field(stats(), "chartBrowser"); Map<?, ?> cards = (Map<?, ?>)field(browser, "cards");
                Object card = cards.get("temperature_c"); Ui.ChartView graph = (Ui.ChartView)field(card, "graph");
                require(graph != null && graph.pointCount() > 1 && "°C".equals(field(graph, "unit")), "Temperature history missing/unit incorrect");
            }); screenshot("charts-search");
            section("Overview");
            main(() -> {
                require(((Map<?, ?>)field(stats(), "favouriteRows")).containsKey("voltage_v"), "Reading favourite lost when starring chart");
                require(((Map<?, ?>)field(stats(), "favouriteCharts")).containsKey("power_w"), "Chart not added to mixed overview");
                List<?> order = (List<?>)invoke(stats(), "favouriteOrder", new Class<?>[]{});
                require(order.size() == 2, "Mixed favourites count is wrong");
                ((android.widget.ScrollView)field(stats(), "sectionScroll")).fullScroll(View.FOCUS_DOWN);
            }); settle();
            main(() -> {
                Object card = ((Map<?, ?>)field(stats(), "favouriteCharts")).get("power_w");
                Ui.ChartView graph = (Ui.ChartView)field(card, "graph");
                require(graph != null && graph.pointCount() > 1, "Favourite chart did not load when scrolled into view");
            }); screenshot("overview-chart");
            main(() -> invoke(stats(), "showFavouriteOrder", new Class<?>[]{})); settle(); screenshot("favourites-reorder");
            main(() -> {
                android.app.AlertDialog dialog = (android.app.AlertDialog)field(stats(), "orderDialog");
                View button = findDescription(dialog.getWindow().getDecorView(), "Move Battery power (signed) chart up");
                require(button != null && button.isEnabled(), "Mixed reorder control missing"); button.performClick();
                require(settings().getString("stats_favourite_order", "").startsWith("chart:power_w"), "Chart reorder not saved"); dialog.dismiss();
            });
            navigate("home"); navigate("stats");
            main(() -> require(((List<?>)invoke(stats(), "favouriteOrder", new Class<?>[]{})).get(0).equals("chart:power_w"), "Mixed order did not survive screen recreation"));
            verifyRetention();
            main(() -> { activity.service().setSimulation(false); activity.redraw(); }); settle();
            main(() -> { require(!Boolean.TRUE.equals(field(field(activity, "liveStatsTitle"), "live")), "Disconnect left a live pulse");
                require(field(activity.service().ble, "gatt") == null && field(activity.service().ble, "scanner") == null,
                        "Stats validation touched real Bluetooth");
                require(!activity.service().isRecording() && !activity.service().phoneSaving(), "Stats started recording"); });
            result.putString("result", "PASS");
        } catch (Throwable error) { result.putString("result", "FAIL"); Throwable cause = error; while (cause.getCause() != null) cause = cause.getCause(); result.putString("error", error.toString() + ": " + cause.toString());
            error.printStackTrace(); try { if (activity != null) screenshot("failure"); } catch (Throwable ignored) {} }
        finally { result.putInt("assertions", checks); result.putStringArrayList("screenshots", shots);
            if (activity != null) main(() -> { activity.finish(); getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); });
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result); }
    }
    @SuppressWarnings("unchecked")
    private void verifySavedTailMerge(Object browser, Object card, Ui.ChartView graph) {
        long now = System.currentTimeMillis();
        List<local.navee.offlinelock.TelemetryHistory.Point> saved = java.util.Arrays.asList(
                new local.navee.offlinelock.TelemetryHistory.Point(now - 8000, 10, true),
                new local.navee.offlinelock.TelemetryHistory.Point(now - 5000, 20, false),
                new local.navee.offlinelock.TelemetryHistory.Point(now - 2000, 30, true));
        try { Field f = card.getClass().getDeclaredField("saved"); f.setAccessible(true); f.set(card, saved); }
        catch (Exception e) { throw new IllegalStateException(e); }
        List<local.navee.offlinelock.TelemetryHistory.Point> tail = (List<local.navee.offlinelock.TelemetryHistory.Point>)field(card, "tail");
        tail.clear(); tail.add(new local.navee.offlinelock.TelemetryHistory.Point(now - 9000, 5, true));
        tail.add(new local.navee.offlinelock.TelemetryHistory.Point(now, 40, false));
        invoke(card, "draw", new Class<?>[]{boolean.class}, true);
        List<Ui.Point> points = (List<Ui.Point>)field(graph, "points");
        require(points.size() == 4 && points.get(1).value == 20 && points.get(2).gapBefore && points.get(3).value == 40,
                "Old live tail erased recorded samples collected while chart was offscreen");
        invoke(browser, "refreshVisible", new Class<?>[]{});
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
    private View findText(View root, String text) {
        if (root instanceof TextView && ((TextView)root).getText().toString().equals(text) && root.isShown()) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)root).getChildCount(); i++) {
            View found = findText(((ViewGroup)root).getChildAt(i), text); if (found != null) return found;
        }
        return null;
    }
    private View findDescription(View root, String description) {
        if (description.equals(String.valueOf(root.getContentDescription()))) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)root).getChildCount(); i++) {
            View found = findDescription(((ViewGroup)root).getChildAt(i), description); if (found != null) return found;
        }
        return null;
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
