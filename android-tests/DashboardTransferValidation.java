package local.navee.offlinelock.tests;

import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;

import local.navee.offlinelock.AppDataBackup;
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
            legacyDisplayLayouts();
            unifiedImport();
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
            for (int columns = 1; columns <= 6; columns++)
                w.layouts(true).put(columns, new DashboardLayout.Cell(w.id,
                        columns == 1 ? 0 : 1, index * 10 + 2, Math.max(1, columns - 1), 5));
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
        require(summary.columns == 3 && summary.widgetCount == DashboardConfig.TYPES.length && summary.savedSlotCount == 3,
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
        DashboardConfig expanded = new DashboardConfig(prefs, "target", true);
        require(expanded.widgetLayouts(expanded.widgets.get(0)).get(5).x == 1
                        && reopened.widgetLayouts(reopened.widgets.get(0)).get(5).x == 0,
                "Import/reopen selected the same five-column geometry for both displays");
        sameJson(new JSONObject(other), new JSONObject(untouched.exportLayouts()));
        for (Map.Entry<String, ?> item : original.entrySet()) {
            String key = item.getKey();
            if (key.equals("dashboard_columns") || key.equals("dashboard_v1_target")
                    || key.startsWith("dashboard_slot_v1_target_")) continue;
            require(item.getValue().equals(prefs.getAll().get(key)), "Import changed unrelated preference " + key);
        }
        require(reopened.restoreSlot(0), "Imported saved slot cannot be restored");
        require(reopened.widgets.size() == DashboardConfig.TYPES.length && reopened.widgets.get(0).note.contains("Unicode"),
                "Imported slot lost its independent widget options");
        for (int index = 0; index < reopened.widgets.size(); index++) {
            DashboardConfig.Widget w = reopened.widgets.get(index);
            require(w.layouts.size() == 6 && w.layouts(true).size() == 6,
                    "Import lost compact/expanded width arrangements");
            for (int columns = 1; columns <= 6; columns++) {
                DashboardLayout.Cell compact = w.layouts.get(columns), wide = w.layouts(true).get(columns);
                require(compact != wide && compact.x == 0 && compact.y == index * 10
                                && compact.w == columns && compact.h == 6
                                && wide.x == (columns == 1 ? 0 : 1) && wide.y == index * 10 + 2
                                && wide.w == Math.max(1, columns - 1) && wide.h == 5,
                        "Imported slot changed or coupled display geometry at width " + columns);
            }
            require(w.keys.equals(Arrays.asList("odometer_km", "battery_percent"))
                    && !w.showDisconnect && w.absoluteAxis && w.minimum == -12.5
                    && w.advertisedWh == 716.04, "Import changed widget parameters");
        }
        checks.add("All " + DashboardConfig.TYPES.length + " widget types, options, independent compact/expanded rectangles at six widths, three slots and columns survive export/import/reopen; other profiles and owner/history settings remain untouched");
    }

    private void legacyDisplayLayouts() throws Exception {
        DashboardConfig source = new DashboardConfig(prefs, "legacy-source");
        source.widgets.clear();
        DashboardConfig.Widget note = new DashboardConfig.Widget("note");
        for (int columns = 1; columns <= 6; columns++)
            note.layouts.put(columns, new DashboardLayout.Cell(note.id, columns - 1, columns * 3, 1, 4));
        source.widgets.add(note); source.save();
        require(source.saveSlot(0, "Old layout file", 5), "Could not seed legacy transfer slot");
        JSONObject legacy = new JSONObject(source.exportLayouts());
        legacy.getJSONArray("widgets").getJSONObject(0).remove("expandedLayouts");
        JSONArray slots = legacy.getJSONArray("slots");
        for (int slot = 0; slot < slots.length(); slot++) {
            if (slots.isNull(slot)) continue;
            JSONArray widgets = slots.getJSONObject(slot).getJSONArray("widgets");
            for (int index = 0; index < widgets.length(); index++)
                widgets.getJSONObject(index).remove("expandedLayouts");
        }
        require(DashboardConfig.inspectLayouts(legacy.toString()).widgetCount == 1,
                "Legacy export without expandedLayouts failed inspection");
        DashboardConfig target = new DashboardConfig(prefs, "legacy-target");
        require(target.importLayouts(legacy.toString()), "Legacy export without expandedLayouts failed import");
        String savedSlot = prefs.getString("dashboard_slot_v1_legacy-target_0", "");
        note = target.widgets.get(0);
        target.widgetLayouts(note).get(5).y = 99;
        for (int columns = 1; columns <= 6; columns++) {
            DashboardLayout.Cell expanded = note.layouts(true).get(columns);
            require(expanded != note.layouts.get(columns) && expanded.x == columns - 1
                            && expanded.y == columns * 3 && expanded.w == 1 && expanded.h == 4,
                    "First compact edit corrupted migrated imported width " + columns);
        }
        target.save();
        target = new DashboardConfig(prefs, "legacy-target", true);
        require(target.widgetLayouts(target.widgets.get(0)).get(5).y == 15
                        && target.widgets.get(0).layouts.get(5).y == 99,
                "Migrated imported display geometry did not survive reopen");
        require(target.restoreSlot(0), "Imported legacy slot failed to restore");
        note = target.widgets.get(0);
        require(note.layouts.get(5).y == 15 && target.widgetLayouts(note).get(5).y == 15,
                "Imported legacy slot did not restore both displays");
        target.exportLayouts();
        require(savedSlot.equals(prefs.getString("dashboard_slot_v1_legacy-target_0", "")),
                "Restoring/exporting imported legacy slots rewrote saved data");

        // A present empty/sparse map means intentional missing widths, not legacy data.
        for (boolean sparse : new boolean[] {false, true}) {
            JSONObject explicit = new JSONObject(legacy.toString());
            JSONObject expanded = new JSONObject();
            if (sparse) expanded.put("5", new JSONArray(new int[] {0, 8, 2, 3}));
            explicit.getJSONArray("widgets").getJSONObject(0).put("expandedLayouts", expanded);
            require(target.importLayouts(explicit.toString()), "Explicit empty/sparse expanded map failed import");
            target = new DashboardConfig(prefs, "legacy-target", true);
            note = target.widgets.get(0);
            require(note.layouts.size() == 6 && target.widgetLayouts(note).size() == (sparse ? 1 : 0),
                    "Import invented expanded widths for an explicit map");
            if (sparse) require(target.widgetLayouts(note).get(5).y == 8,
                    "Explicit expanded rectangle was replaced with legacy geometry");
        }
        checks.add("Legacy files and saved slots without expandedLayouts migrate all six widths independently; explicit empty/sparse expanded maps retain their meaning");
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
        require(config.slot(2).widgetCount == DashboardConfig.TYPES.length && config.slot(2).columns == 6,
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
        Object[] damagedExpanded = {JSONObject.NULL, new JSONArray(), "not-a-map",
                new JSONObject().put("7", new JSONArray(new int[] {0, 0, 1, 4})),
                new JSONObject().put("05", new JSONArray(new int[] {0, 0, 1, 4})),
                new JSONObject().put("5", new JSONArray(new int[] {4, 0, 2, 4})),
                new JSONObject().put("5", new JSONArray(new int[] {0, -1, 1, 4})),
                new JSONObject().put("5", new JSONArray(new int[] {0, 1325, 1, 4})),
                new JSONObject().put("5", new JSONArray(new int[] {0, 0, 1, 17})),
                new JSONObject().put("5", new JSONArray(new int[] {0, 0, 1})),
                new JSONObject().put("5", new JSONArray().put("0").put(0).put(1).put(4))};
        for (Object damaged : damagedExpanded) {
            for (boolean savedSlot : new boolean[] {false, true}) {
                JSONObject changed = new JSONObject(good);
                JSONArray widgets = savedSlot
                        ? changed.getJSONArray("slots").getJSONObject(2).getJSONArray("widgets")
                        : changed.getJSONArray("widgets");
                widgets.getJSONObject(0).put("expandedLayouts", damaged);
                invalid.add(changed.toString());
            }
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

    /** A layout file through Back up & restore, mapped to a chosen scooter in isolated storage. */
    private void unifiedImport() throws Exception {
        String prefix = fixture + "_unified_";
        java.io.File root = new java.io.File(getTargetContext().getCacheDir(), prefix);
        android.content.ContextWrapper storage = new android.content.ContextWrapper(getTargetContext()) {
            @Override public android.content.Context getApplicationContext() { return this; }
            @Override public java.io.File getFilesDir() { return new java.io.File(root, "files"); }
            @Override public java.io.File getNoBackupFilesDir() { return new java.io.File(root, "no-backup"); }
            @Override public java.io.File getDatabasePath(String name) { return new java.io.File(root, "databases/" + name); }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) { return super.getSharedPreferences(prefix + name, mode); }
        };
        try {
            for (String folder : new String[]{"files", "no-backup", "databases"}) new java.io.File(root, folder).mkdirs();
            SharedPreferences settings = storage.getSharedPreferences("app_settings", 0);
            require(storage.getSharedPreferences("profiles", 0).edit().putString("items", "[{\"id\":\"chosen\",\"name\":\"Chosen\"},{\"id\":\"other\",\"name\":\"Other\"}]").putString("active", "other").commit(), "Store fixture scooters");
            DashboardConfig source = new DashboardConfig(settings, "source");
            source.widgets.clear();
            DashboardConfig.Widget battery = new DashboardConfig.Widget("battery");
            battery.layouts.put(5, new DashboardLayout.Cell(battery.id, 0, 0, 2, 3));
            battery.layouts(true).put(5, new DashboardLayout.Cell(battery.id, 2, 7, 3, 4));
            source.widgets.add(battery); source.save();
            require(source.saveSlot(0, "Shared", 0), "Save shared slot");
            String portable = source.exportLayouts();
            String other = new DashboardConfig(settings, "other").exportLayouts();
            settings.edit().putString("dashboard_restore_undo_v1_chosen", "{}").commit();
            AppDataBackup.PreparedRestore staged = AppDataBackup.stage(storage, new java.io.ByteArrayInputStream(portable.getBytes("UTF-8")), null);
            require("layout".equals(staged.format) && staged.categories.keySet().equals(java.util.Collections.singleton("dashboards")), "A layout file stages as Dashboards");
            java.util.List<AppDataBackup.Destination> needed = AppDataBackup.destinations(storage, staged, java.util.Collections.singleton("dashboards"));
            require(needed.size() == 1 && "other".equals(needed.get(0).suggested), "A layout defaults to the selected scooter");
            AppDataBackup.Review review = AppDataBackup.review(storage, staged, java.util.Collections.singleton("dashboards"), false, java.util.Collections.singletonMap(needed.get(0).source, "chosen"));
            require(review.lines.contains("Dashboards: replaces layouts for Chosen"), "Review names the chosen scooter");
            AppDataBackup.apply(storage, review);
            sameJson(new JSONObject(portable), new JSONObject(new DashboardConfig(settings, "chosen").exportLayouts()));
            sameJson(new JSONObject(other), new JSONObject(new DashboardConfig(settings, "other").exportLayouts()));
            require(!settings.contains("dashboard_restore_undo_v1_chosen") && !AppDataBackup.needsRecovery(storage), "The stale undo is dropped and no staging remains");
            checks.add("A dashboard layout file restores through Back up & restore to a chosen scooter; the selected scooter's dashboard is untouched");
        } finally {
            for (String name : new String[]{"app_settings", "profiles", "scooter", "control_evidence", "onboarding"}) getTargetContext().deleteSharedPreferences(prefix + name);
            deleteTree(root);
        }
    }
    private static void deleteTree(java.io.File file) { java.io.File[] children = file.listFiles(); if (children != null) for (java.io.File child : children) deleteTree(child); file.delete(); }

    private void require(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private void cleanup() {
        if (prefs != null) prefs.edit().clear().commit();
        if (fixture != null) getTargetContext().deleteSharedPreferences(fixture);
    }
}
