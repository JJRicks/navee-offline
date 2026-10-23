package com.jjricks.volt.tests;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import com.jjricks.volt.*;

/** Disposable emulator only; uses the app's memory-only simulator and never opens a BLE link. */
public final class BatteryWidgetValidation extends Instrumentation {
    Bundle args;
    MainActivity activity;
    String profileId;
    int assertions;
    final ArrayList<String> screenshots = new ArrayList<>();
    @Override public void onCreate(Bundle arguments) { args = arguments == null ? new Bundle() : arguments; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            require(Build.PRODUCT.startsWith("sdk_gphone") || Build.HARDWARE.contains("ranchu"), "Refusing physical device");
            require(settings().getAll().isEmpty(), "Refusing existing app preferences");
            require(getTargetContext().databaseList().length == 0, "Refusing existing app databases");
            require(getTargetContext().getFilesDir().list().length == 0, "Refusing existing app files");
            main(() -> {
                settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                        .putBoolean("developer_options", true).putString("theme", args.getString("theme", "dark"))
                        .putString("units", "mi").putInt("dashboard_columns", 2)
                        .putFloat("text_scale", Float.parseFloat(args.getString("text_scale", "1"))).commit();
                ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
                ScooterProfiles.Profile profile = profiles.create("Battery layout fixture", "000000000000", 0);
                profile.serial = "X2538-SYNTHETIC"; profiles.update(profile); profileId = profile.id;
                DashboardConfig config = new DashboardConfig(settings(), profileId); config.widgets.clear();
                int i = 0;
                for (String type : new String[]{"battery", "charging", "health", "readings", "capacity", "history"}) {
                    DashboardConfig.Widget widget = new DashboardConfig.Widget(type);
                    widget.layouts.put(2, new DashboardLayout.Cell(widget.id, i % 2, (i / 2) * 6, 1, 6));
                    if (type.equals("readings")) { widget.keys.clear(); widget.keys.addAll(Arrays.asList("voltage_v", "power_w", "temperature_c", "battery_percent")); }
                    if (type.equals("history")) { widget.metric = "power_w"; widget.absoluteAxis = true; }
                    config.widgets.add(widget); i++;
                }
                config.save();
            });
            activity = (MainActivity)startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.service() != null && screen() != null, "Dashboard not ready");
            main(() -> require(activity.service().setSimulation(true), "Simulator failed to enable"));
            await(() -> activity.service().simulating() && tile("battery") != null, "Simulator not ready");
            settle();
            checkDischarging();
            checkCharging();
            checkHealth();
            checkShortReading();
            checkLongReading();
            checkDetails();
            checkAxisRoundTrip();
            main(() -> require(field(activity.service().ble, "gatt") == null, "Fixture opened Bluetooth"));
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", error.toString());
            if (activity != null) try { screenshot("failure"); } catch (Exception ignored) { }
        } finally {
            result.putInt("assertions", assertions); result.putStringArrayList("screenshots", screenshots);
            result.putBoolean("physical_device_tested", false);
            if (activity != null) try { main(() -> { activity.finish(); ScooterService.setUiVisible(false); getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); }); } catch (Throwable ignored) { }
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    private void checkDischarging() throws Exception {
        main(() -> {
            activity.service().ble.setSimulationCharging(false); screen().refresh();
            Object battery = tile("battery");
            TextView watts = ((Map<String, TextView>)field(battery, "amounts")).get("power_w");
            require(watts != null && watts.getCurrentTextColor() == activity.ui().danger, "Discharge power is not red");
            require(((View)field(battery, "chargingData")).getVisibility() == View.GONE, "Charging stats visible when idle");
        });
        settle(); screenshot("overview");
    }

    private void checkCharging() throws Exception {
        main(() -> { activity.service().ble.setSimulationCharging(true); screen().refresh(); });
        settle();
        main(() -> {
            View hero = (View)field(screen(), "chargingHero");
            require(hero.getVisibility() == View.VISIBLE && hero.getHeight() > 0, "Charging hero did not expand");
            require(hero.getLayoutParams().height == -2 && hero.getAlpha() == 1f, "Charging transition did not settle");
            TextView heroPower = (TextView)field(hero, "power");
            require(heroPower.getText().toString().endsWith(" W") && !heroPower.getText().toString().contains(" A"), "Hero exposed charging amps");
            Object battery = tile("battery");
            TextView watts = (TextView)field(battery, "chargingWatts");
            require(watts.getCurrentTextColor() == activity.ui().ok, "Charge power is not green");
            require(((View)field(battery, "chargingData")).getVisibility() == View.VISIBLE, "Missing battery charging stats");
            Object charging = tile("charging");
            assertText((TextView)field(charging, "chargingWatts"));
            assertText((TextView)field(charging, "chargingEta"));
            for (TextView text : texts((View)charging)) require(!text.getText().toString().equals("Charging current"), "Charging current row remains");
        });
        screenshot("charging");
        // Interrupt both directions; the latest state must win without a stuck explicit height.
        main(() -> { activity.service().ble.setSimulationCharging(false); screen().refresh(); });
        SystemClock.sleep(60);
        main(() -> { activity.service().ble.setSimulationCharging(true); screen().refresh(); });
        SystemClock.sleep(60);
        main(() -> { activity.service().ble.setSimulationCharging(false); screen().refresh(); });
        settle();
        main(() -> {
            View hero = (View)field(screen(), "chargingHero");
            require(hero.getVisibility() == View.GONE && hero.getLayoutParams().height == -2, "Charging hide/interruption left layout space");
            require(field(hero, "reveal") == null, "Charging animator remains alive");
        });
    }

    private void checkHealth() throws Exception {
        main(() -> {
            Object health = tile("health");
            TextView value = ((Map<String, TextView>)field(health, "amounts")).get("battery_health_percent");
            require(value != null && value.getIncludeFontPadding(), "Health value omits glyph padding");
            require(value.getBaseline() + value.getPaint().getFontMetrics().top >= 0, "Health glyph is clipped above baseline");
            assertText(value);
            bring((View)health);
        }); settle(); screenshot("health-readings");
        main(() -> { for (TextView text : texts((View)tile("readings"))) assertText(text); });
    }

    private void checkShortReading() throws Exception {
        AtomicReference<TextView> value = new AtomicReference<>();
        main(() -> {
            value.set(((Map<String, TextView>)field(tile("readings"), "amounts")).get("voltage_v"));
            value.get().setText("50.41 V");
        });
        waitForIdleSync();
        main(() -> {
            assertText(value.get());
            require(value.get().getLayout().getLineCount() == 1,
                    "Short number/unit reading orphaned the unit instead of fitting one line");
        });
        screenshot("short-reading");
    }

    private void checkLongReading() throws Exception {
        AtomicReference<TextView> value = new AtomicReference<>();
        main(() -> {
            value.set(((Map<String, TextView>)field(tile("readings"), "amounts")).get("voltage_v"));
            value.get().setText("Unknown diagnostic condition with an extended controller status description");
        });
        waitForIdleSync();
        main(() -> {
            assertText(value.get());
            require(value.get().getLayout().getHeight() <= value.get().getHeight() - value.get().getCompoundPaddingTop() - value.get().getCompoundPaddingBottom(),
                    "Long controller value was clipped vertically");
            screen().refresh();
        });
    }

    private void checkDetails() throws Exception {
        main(() -> bring((View)tile("capacity"))); settle(); screenshot("capacity");
        main(() -> {
            View capacity = (View)tile("capacity"); bring(capacity);
            require(((View)field(capacity, "capacityBar")).getHeight() > 0, "Missing capacity comparison bar");
            TextView method = (TextView)field(capacity, "capacityMethod");
            require(method.getVisibility() == View.VISIBLE && method.getText().toString().contains("Sample"), "Sample capacity not labeled");
            TextView button = null;
            for (TextView text : texts(capacity)) if (text.getText().toString().equals("Estimate details")) button = text;
            require(button != null, "Capacity evidence is unreachable"); button.performClick();
            require(field(screen(), "dialog") != null, "Capacity details did not open");
        }); settle(); screenshot("capacity-details");
        main(() -> ((android.app.AlertDialog)field(screen(), "dialog")).dismiss());
    }

    private void checkAxisRoundTrip() {
        main(() -> {
            DashboardConfig config = new DashboardConfig(settings(), profileId);
            DashboardConfig.Widget history = null;
            for (DashboardConfig.Widget widget : config.widgets) if (widget.type.equals("history")) history = widget;
            require(history != null && history.absoluteAxis, "Axis preference did not persist");
            require(config.saveSlot(1, "Axis fixture", 2), "Slot save failed");
            history.absoluteAxis = false; config.save();
            require(config.restoreSlot(1), "Slot restore failed");
            for (DashboardConfig.Widget widget : config.widgets) if (widget.type.equals("history")) require(widget.absoluteAxis, "Saved slot lost axis preference");
        });
    }
    private void bring(View view) { view.requestRectangleOnScreen(new android.graphics.Rect(0, 0, view.getWidth(), view.getHeight()), true); }
    private void assertText(TextView view) {
        if (!view.isShown()) return;
        require(view.getWidth() > 0 && view.getLayout() != null, "Text was not laid out");
        int count = view.getLayout().getLineCount();
        for (int i = 0; i < count; i++) require(view.getLayout().getLineMax(i) <= view.getWidth() - view.getCompoundPaddingLeft() - view.getCompoundPaddingRight() + 2, "Text exceeds card width: " + view.getText());
        require(count == 0 || view.getLayout().getLineEnd(count - 1) == view.length(), "Text was truncated: " + view.getText());
    }
    private ArrayList<TextView> texts(View view) {
        ArrayList<TextView> found = new ArrayList<>(); if (view instanceof TextView) found.add((TextView)view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) found.addAll(texts(((ViewGroup)view).getChildAt(i)));
        return found;
    }
    private DashboardScreen screen() { Object screen = field(activity, "screen"); return screen instanceof DashboardScreen ? (DashboardScreen)screen : null; }
    private Object tile(String type) {
        if (screen() == null) return null;
        for (Object tile : (List<?>)field(screen(), "tiles")) if (((DashboardConfig.Widget)field(tile, "widget")).type.equals(type)) return tile;
        return null;
    }
    private SharedPreferences settings() { return getTargetContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE); }
    private Object field(Object object, String name) {
        if (object == null) return null;
        try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
        catch (Exception error) { throw new RuntimeException(name, error); }
    }
    private void main(Runnable action) { AtomicReference<Throwable> error = new AtomicReference<>(); runOnMainSync(() -> { try { action.run(); } catch (Throwable t) { error.set(t); } }); if (error.get() != null) throw new RuntimeException(error.get()); }
    private void await(BooleanSupplier condition, String message) { long end = SystemClock.elapsedRealtime() + 12000; while (SystemClock.elapsedRealtime() < end) { AtomicReference<Boolean> ready = new AtomicReference<>(false); main(() -> ready.set(condition.getAsBoolean())); if (ready.get()) return; SystemClock.sleep(60); } throw new AssertionError(message); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(400); waitForIdleSync(); }
    private void require(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    private void screenshot(String name) throws Exception {
        File dir = new File(getTargetContext().getExternalFilesDir(null), "battery-widget-validation"); dir.mkdirs();
        File file = new File(dir, name + ".png"); Bitmap bitmap = getUiAutomation().takeScreenshot();
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
        bitmap.recycle(); screenshots.add(file.getAbsolutePath());
    }
}
