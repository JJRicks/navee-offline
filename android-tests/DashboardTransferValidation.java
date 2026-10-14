package local.navee.offlinelock.tests;

import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;

import local.navee.offlinelock.DashboardConfig;
import local.navee.offlinelock.DashboardLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Portable dashboard files against real Android JSON/preferences; synthetic emulator fixtures only. */
public final class DashboardTransferValidation extends Instrumentation {
    private SharedPreferences prefs;
    private String fixture;
    private int assertions;
    private final ArrayList<String> checks = new ArrayList<>();

    @Override public void onCreate(Bundle arguments) { start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
            require(hardware.contains("ranchu") || hardware.contains("goldfish")
                    || Build.PRODUCT.startsWith("sdk_gphone") || Build.MODEL.startsWith("sdk_gphone"),
                    "Dashboard transfer checks refuse physical owner phones");
            fixture = "dashboard_transfer_validation_" + System.nanoTime();
            prefs = getTargetContext().getSharedPreferences(fixture, Context.MODE_PRIVATE);
            roundTrip();
            emptyAndExample();
            malformed();
            commitFailure();
            result.putString("result", "PASS");
            result.putInt("assertions", assertions);
            result.putStringArrayList("checks", checks);
            result.putInt("scooter_mutations", 0);
            cleanup();
            finish(0, result);
        } catch (Throwable failure) {
            result.putString("result", "FAIL");
            result.putString("error", failure.getClass().getSimpleName() + ": " + failure.getMessage());
            result.putInt("assertions", assertions);
            result.putStringArrayList("checks", checks);
            try { cleanup(); } catch (Throwable ignored) { }
            finish(1, result);
        }
    }

    private void roundTrip() throws Exception {
        DashboardConfig source = new DashboardConfig(prefs, "source");
        source.widgets.clear();
        for (String type : DashboardConfig.TYPES) {
            DashboardConfig.Widget w = new DashboardConfig.Widget(type);
            w.title = "Custom " + type;
            w.note = "An owner's note with Unicode 🦦 and \"quotes\"";
            w.metric = "speed_kmh";
            w.color = 2;
            w.windowMs = 120000;
            w.minimum = -12.5;
            w.maximum = 63.75;
            w.advertisedWh = 716.04;
            w.showDisconnect = false;
            w.absoluteAxis = true;
            w.keys.clear();
            w.keys.addAll(Arrays.asList("odometer_km", "battery_percent"));
            w.quickActions.clear();
            w.quickActions.addAll(Arrays.asList("feature:maximum_speed", "feature:traction_control"));
            int index = source.widgets.size();
            for (int columns = 1; columns <= 6; columns++)
                w.layouts.put(columns, new DashboardLayout.Cell(w.id, 0, index * 10, columns, 6));
            source.widgets.add(w);
        }
        source.save();
        require(source.saveSlot(0, "Folded & unfolded 🦦", 6), "Could not save fixture slot");
        source.widgets.get(0).note = "Active differs from saved";
        require(source.saveSlot(1, "Another setup", 0), "Could not save second slot");
        require(prefs.edit().putInt("dashboard_columns", 3)
                .putString("owner_secret", "synthetic-secret-not-exportable")
                .putString("historical-data", "synthetic-history").commit(), "Could not save fixture settings");
        source.save();
        String portable = source.exportLayouts();
        DashboardConfig.LayoutTransfer summary = DashboardConfig.inspectLayouts(portable);
        require(summary.columns == 3 && summary.widgetCount == 15 && summary.savedSlotCount == 3,
                "Consent preview is incorrect");
        require(!portable.contains("synthetic-secret") && !portable.contains("owner_secret")
                && !portable.contains("synthetic-history") && !portable.contains("dashboard_v1_source"),
                "Export leaked out-of-scope preferences/profile identity");
        DashboardConfig target = new DashboardConfig(prefs, "target");
        DashboardConfig untouched = new DashboardConfig(prefs, "other");
        String other = untouched.exportLayouts();
        Map<String, ?> original = new HashMap<>(prefs.getAll());
        require(target.importLayouts(portable), "Portable import failed");
        sameJson(new JSONObject(portable), new JSONObject(target.exportLayouts()));
        DashboardConfig reopened = new DashboardConfig(prefs, "target");
        sameJson(new JSONObject(portable), new JSONObject(reopened.exportLayouts()));
        sameJson(new JSONObject(other), new JSONObject(untouched.exportLayouts()));
        for (Map.Entry<String, ?> item : original.entrySet()) {
            String key = item.getKey();
            if (key.equals("dashboard_columns") || key.equals("dashboard_v1_target")
                    || key.startsWith("dashboard_slot_v1_target_")) continue;
            require(item.getValue().equals(prefs.getAll().get(key)), "Import changed unrelated preference " + key);
        }
        require(reopened.restoreSlot(0), "Imported saved slot cannot be restored");
        require(reopened.widgets.size() == 15 && reopened.widgets.get(0).note.contains("Unicode"),
                "Imported slot lost its independent widget options");
        for (DashboardConfig.Widget w : reopened.widgets) {
            require(w.layouts.size() == 6, "Import lost folded/unfolded arrangements");
            require(w.keys.equals(Arrays.asList("odometer_km", "battery_percent"))
                    && !w.showDisconnect && w.absoluteAxis && w.minimum == -12.5
                    && w.advertisedWh == 716.04, "Import changed widget parameters");
        }
        checks.add("All 15 widget types, options, six widths, three independent slots and columns survive export/import/reopen; other profiles and owner/history settings remain untouched");
    }

    private void emptyAndExample() throws Exception {
        DashboardConfig config = new DashboardConfig(prefs, "empty");
        JSONObject data = new JSONObject(config.exportLayouts());
        data.put("widgets", new JSONArray());
        data.put("slots", new JSONArray().put(JSONObject.NULL).put(JSONObject.NULL).put(JSONObject.NULL));
        data.put("columns", 0);
        require(config.importLayouts(data.toString()), "Empty layouts did not import");
        config = new DashboardConfig(prefs, "empty");
        require(config.widgets.isEmpty() && !config.slotExists(0) && !config.slotExists(1)
                && !config.slotExists(2), "Empty layout or slots were reseeded on reload");
        require(config.saveSlot(0, "Empty but saved", 0), "Could not save empty slot");
        String active = prefs.getString("dashboard_v1_empty", null);
        String first = prefs.getString("dashboard_slot_v1_empty_0", null);
        require(config.forceExampleSlot(), "Could not force example into slot 3");
        require(config.widgets.isEmpty() && active.equals(prefs.getString("dashboard_v1_empty", null))
                && first.equals(prefs.getString("dashboard_slot_v1_empty_0", null))
                && !config.slotExists(1) && prefs.getInt("dashboard_columns", -1) == 0,
                "Example reset changed active layout, other slots or global columns");
        require(config.slot(2).widgetCount == 15 && config.slot(2).columns == 6,
                "Example reset did not load all widgets");
        DashboardConfig destination = new DashboardConfig(prefs, "empty-target");
        require(destination.importLayouts(config.exportLayouts()), "Empty saved slot transfer failed");
        require(destination.slot(0).widgetCount == 0 && destination.slot(1) == null,
                "Empty and missing slots became indistinguishable");
        checks.add("Intentionally empty dashboards/slots remain empty after reopen; force-example replaces only slot 3 and keeps active layout, other slots and columns");
    }

    private void malformed() throws Exception {
        DashboardConfig config = new DashboardConfig(prefs, "malformed");
        String good = config.exportLayouts();
        ArrayList<String> invalid = new ArrayList<>();
        invalid.add("{}"); invalid.add("[]"); invalid.add(good + " {}");
        invalid.add(good.replace("\"version\": 1", "\"version\": 2"));
        invalid.add(good.replace("\"version\": 1", "\"version\": 1.0"));
        invalid.add(good.replace("\"volt-dashboard-layouts\"", "\"other-file\""));
        invalid.add(new JSONObject(good).put("owner_token", "synthetic").toString());
        invalid.add(new JSONObject(good).put("columns", 7).toString());
        invalid.add(new JSONObject(good).put("slots", new JSONArray()).toString());
        invalid.add(new JSONObject(good).put("slots", new JSONArray().put(1).put(JSONObject.NULL).put(JSONObject.NULL)).toString());
        String[] fields = {"type", "id", "title", "note", "metric", "color", "window", "min", "max",
                "advertisedWh", "showDisconnect", "absoluteAxis", "keys", "quickActions", "layouts"};
        Object[] values = {"not-a-widget", "", repeat("x", 81), repeat("x", 1201), "unknown_parameter",
                4, 59999, "NaN", -1, 10001, "true", 1, new JSONArray().put("unknown"),
                new JSONArray().put("feature:maximum_speed").put("evil-command"),
                new JSONObject().put("6", new JSONArray().put(6).put(0).put(1).put(4))};
        for (int i = 0; i < fields.length; i++) {
            JSONObject changed = new JSONObject(good);
            changed.getJSONArray("widgets").getJSONObject(0).put(fields[i], values[i]);
            invalid.add(changed.toString());
        }
        JSONObject duplicate = new JSONObject(good);
        duplicate.getJSONArray("widgets").getJSONObject(1).put("id",
                duplicate.getJSONArray("widgets").getJSONObject(0).getString("id"));
        invalid.add(duplicate.toString());
        JSONObject overflowing = new JSONObject(good);
        overflowing.getJSONArray("widgets").getJSONObject(0).put("min", -1e308).put("max", 1e308);
        invalid.add(overflowing.toString());
        JSONObject slotDamage = new JSONObject(good);
        slotDamage.getJSONArray("slots").getJSONObject(2).put("columns", "6");
        invalid.add(slotDamage.toString());
        JSONObject extra = new JSONObject(good);
        extra.getJSONArray("widgets").getJSONObject(0).put("command", "arbitrary shell");
        invalid.add(extra.toString());
        JSONObject repeated = new JSONObject(good);
        JSONArray tooMany = new JSONArray();
        for (int i = 0; i < 33; i++) tooMany.put(repeated.getJSONArray("widgets").getJSONObject(0));
        repeated.put("widgets", tooMany); invalid.add(repeated.toString());
        invalid.add(repeat("[", 10000) + repeat("]", 10000));
        invalid.add(repeat(" ", DashboardConfig.MAX_TRANSFER_BYTES + 1));
        invalid.add("/*\"*/" + repeat("[", 10000) + repeat("]", 10000));
        for (String candidate : invalid) {
            Map<String, ?> before = new HashMap<>(prefs.getAll());
            String snapshot = config.exportLayouts();
            require(!config.importLayouts(candidate), "Accepted malformed input");
            require(before.equals(prefs.getAll()), "Malformed input partially changed preferences");
            require(snapshot.equals(config.exportLayouts()), "Malformed input changed in-memory widgets");
            try {
                DashboardConfig.inspectLayouts(candidate);
                throw new AssertionError("Consent preview accepted malformed input");
            } catch (org.json.JSONException expected) { assertions++; }
        }
        String slotKey = "dashboard_slot_v1_malformed_0";
        require(prefs.edit().putString(slotKey, "damaged").commit(), "Could not seed damaged slot");
        try {
            config.exportLayouts();
            throw new AssertionError("Export silently discarded a damaged slot");
        } catch (org.json.JSONException expected) { assertions++; }
        checks.add(invalid.size() + " malformed/version/type/count/length/unknown-field/deep-nesting payloads rejected before any state change; damaged saved slots block export rather than silently losing data");
    }

    private void commitFailure() throws Exception {
        DashboardConfig source = new DashboardConfig(prefs, "commit-source");
        source.widgets.clear(); source.save();
        final boolean[] failNext = {false};
        SharedPreferences failing = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[] {SharedPreferences.class},
                (proxy, method, args) -> {
                    Object value = method.invoke(prefs, args);
                    if (!method.getName().equals("edit")) return value;
                    SharedPreferences.Editor delegate = (SharedPreferences.Editor) value;
                    return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                            new Class<?>[] {SharedPreferences.Editor.class}, (editorProxy, editorMethod, editorArgs) -> {
                                Object outcome = editorMethod.invoke(delegate, editorArgs);
                                if (editorMethod.getName().equals("commit") && failNext[0]) {
                                    failNext[0] = false;
                                    return false; // Worst case: memory and disk changed despite failure.
                                }
                                return outcome instanceof SharedPreferences.Editor ? editorProxy : outcome;
                            });
                });
        DashboardConfig target = new DashboardConfig(failing, "commit-target");
        String before = target.exportLayouts();
        Map<String, ?> original = new HashMap<>(prefs.getAll());
        failNext[0] = true;
        require(!target.importLayouts(source.exportLayouts()), "Failed commit reported success");
        require(original.equals(prefs.getAll()), "Failed commit did not restore previous preferences");
        require(before.equals(target.exportLayouts()), "Failed commit changed in-memory widgets");
        checks.add("Injected commit failure restores all changed preference keys and leaves in-memory widgets intact");
    }

    private void sameJson(Object expected, Object actual) throws Exception {
        if (expected instanceof JSONObject) {
            require(actual instanceof JSONObject, "JSON object type changed");
            JSONObject a = (JSONObject) expected, b = (JSONObject) actual;
            require(a.length() == b.length(), "JSON object fields changed");
            java.util.Iterator<String> keys = a.keys();
            while (keys.hasNext()) { String key = keys.next(); sameJson(a.get(key), b.get(key)); }
        } else if (expected instanceof JSONArray) {
            require(actual instanceof JSONArray, "JSON array type changed");
            JSONArray a = (JSONArray) expected, b = (JSONArray) actual;
            require(a.length() == b.length(), "JSON array length changed");
            for (int i = 0; i < a.length(); i++) sameJson(a.get(i), b.get(i));
        } else require(expected.equals(actual), "JSON value changed");
    }

    private static String repeat(String value, int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }

    private void require(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private void cleanup() {
        if (prefs != null) prefs.edit().clear().commit();
        if (fixture != null) getTargetContext().deleteSharedPreferences(fixture);
    }
}
