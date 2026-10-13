package local.navee.offlinelock.tests;

import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.Bundle;

import local.navee.offlinelock.DashboardConfig;
import local.navee.offlinelock.DashboardLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Real Android preference persistence in a disposable fixture, with no app launch or BLE. */
public final class DashboardSlotsValidation extends Instrumentation {
    private final ArrayList<String> checks = new ArrayList<>();
    private SharedPreferences preferences;
    private String fixtureName;
    private File historyFile;
    private SQLiteDatabase history;
    private int assertions;

    @Override public void onCreate(Bundle arguments) { start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
            require(hardware.contains("ranchu") || hardware.contains("goldfish")
                            || Build.PRODUCT.startsWith("sdk_gphone")
                            || Build.MODEL.startsWith("sdk_gphone"),
                    "Dashboard slot checks refuse physical owner phones");
            fixtureName = "dashboard_slots_validation_" + System.nanoTime();
            preferences = getTargetContext().getSharedPreferences(fixtureName, Context.MODE_PRIVATE);
            require(preferences.getAll().isEmpty(), "Fixture must be fresh");
            historyFile = new File(getTargetContext().getCacheDir(), fixtureName + ".db");
            history = SQLiteDatabase.openOrCreateDatabase(historyFile, null);
            history.execSQL("CREATE TABLE readings(profile TEXT, wall INTEGER, power REAL)");
            history.execSQL("INSERT INTO readings VALUES('alpha',1700000000000,721.5)");
            history.execSQL("INSERT INTO readings VALUES('beta',1700000000100,-61.25)");
            fullRoundTrip();
            overwriteAndReset();
            emptyAndMissingLayouts();
            malformedSnapshots();
            boundsNamesAndIsolation();
            migrationCompatibility();
            require(historyCount() == 2, "Layout changes altered history rows");
            try (Cursor row = history.rawQuery("SELECT profile,wall,power FROM readings ORDER BY wall", null)) {
                require(row.moveToFirst() && row.getString(0).equals("alpha")
                                && row.getLong(1) == 1700000000000L && row.getDouble(2) == 721.5,
                        "Layout changes altered history values");
                require(row.moveToNext() && row.getString(0).equals("beta") && row.getDouble(2) == -61.25,
                        "Layout changes altered another scooter's history");
            }
            checks.add("Dashboard operations leave separate recorded-history fixtures unchanged");
            cleanup();
            result.putString("result", "PASS");
            result.putInt("check_count", checks.size());
            result.putInt("assertions", assertions);
            result.putStringArrayList("checks", checks);
            result.putInt("scooter_mutations", 0);
            finish(0, result);
        } catch (Throwable failure) {
            result.putString("result", "FAIL");
            result.putString("error", failure.getClass().getSimpleName() + ": " + failure.getMessage());
            result.putStringArrayList("completed_checks", checks);
            result.putInt("assertions", assertions);
            try { cleanup(); } catch (Throwable ignored) { }
            finish(1, result);
        }
    }

    private void fullRoundTrip() throws Exception {
        DashboardConfig config = fixture("alpha");
        String expected = signature(config);
        long before = System.currentTimeMillis();
        require(config.saveSlot(0, "  Daily\n dashboard  ", 0), "Could not save complete snapshot");
        DashboardConfig.SavedSlot slot = config.slot(0);
        require(slot != null && slot.name.equals("Daily dashboard") && slot.widgetCount == DashboardConfig.TYPES.length
                        && slot.columns == 0 && slot.savedAtMs >= before && slot.savedAtMs <= System.currentTimeMillis(),
                "Incorrect slot metadata");
        String snapshot = preferences.getString(slotKey("alpha", 0), "");
        config.widgets.get(0).title = "Changed after snapshot";
        config.widgets.get(1).layouts.get(4).h = 15;
        config.widgets.get(2).keys.clear();
        config.widgets.remove(3);
        config.save();
        require(preferences.edit().putInt("dashboard_columns", 4).commit(), "Could not change live columns");
        DashboardConfig reopened = new DashboardConfig(preferences, "alpha");
        require(reopened.slot(0) != null && reopened.restoreSlot(0), "Reopened config could not restore slot");
        require(signature(reopened).equals(expected), "Full widget options/IDs/order/four layouts did not round-trip");
        require(preferences.getInt("dashboard_columns", -1) == 0, "Adaptive columns were not restored");
        require(signature(new DashboardConfig(preferences, "alpha")).equals(expected), "Restore did not persist live dashboard");
        // Simulate the screen closing after restore, which used to risk rewriting old state.
        reopened.save();
        require(signature(new DashboardConfig(preferences, "alpha")).equals(expected), "close/save overwrote restored state");
        require(preferences.getString(slotKey("alpha", 0), "").equals(snapshot), "Restoration changed the stored snapshot");
        checks.add("All 15 widget types, options, IDs and independent 1–4-column rectangles survive save/recreation/restore/close");
    }

    private void overwriteAndReset() throws Exception {
        DashboardConfig config = new DashboardConfig(preferences, "alpha");
        String first = signature(config);
        require(config.saveSlot(1, "Backup", 3), "Could not save slot two");
        String secondSnapshot = preferences.getString(slotKey("alpha", 1), "");
        config.widgets.get(0).title = "Overwrite only one slot";
        config.widgets.get(0).layouts.get(2).y = 44;
        String replacement = signature(config);
        require(config.saveSlot(0, "Alternate", 4), "Could not replace slot one");
        require(preferences.getString(slotKey("alpha", 1), "").equals(secondSnapshot), "Overwriting slot one changed slot two");
        config.reset();
        require(config.widgets.size() == 6, "Default reset failed");
        require(config.slot(0) != null && config.slot(1) != null, "Default reset erased saved slots");
        require(config.restoreSlot(0) && signature(config).equals(replacement), "Reset damaged alternate layout");
        require(preferences.getInt("dashboard_columns", -1) == 4, "Explicit column choice was not restored");
        DashboardConfig.Widget detached = config.widgets.get(0);
        require(config.restoreSlot(1) && signature(config).equals(first), "Second slot no longer has original snapshot");
        detached.title = "Detached widget mutation";
        require(signature(config).equals(first), "Restored list shares widget objects with previous live state");
        config.widgets.get(0).layouts.get(4).y = 99;
        require(config.restoreSlot(1) && signature(config).equals(first), "Live cell mutation changed a snapshot");
        require(preferences.getInt("dashboard_columns", -1) == 3, "Second slot lost its column preference");
        checks.add("Overwrite, later edits and Restore defaults never mutate another slot; restored objects are independent");
    }

    private void emptyAndMissingLayouts() throws Exception {
        DashboardConfig config = new DashboardConfig(preferences, "alpha");
        config.widgets.clear();
        require(config.saveSlot(2, "", 2), "An intentional empty layout could not be saved");
        require(config.slot(2).widgetCount == 0 && config.slot(2).name.equals("Slot 3"), "Empty layout metadata is incorrect");
        config.reset();
        require(config.restoreSlot(2) && config.widgets.isEmpty(), "Restoring empty dashboard added defaults");
        config.save();
        require(new DashboardConfig(preferences, "alpha").widgets.isEmpty(), "Empty layout did not survive recreation");
        DashboardConfig.Widget note = new DashboardConfig.Widget("note");
        note.layouts.put(2, new DashboardLayout.Cell(note.id, 1, 12, 1, 5));
        config.widgets.add(note);
        require(config.saveSlot(2, "Folded only", 0), "Missing unused widths prevented saving");
        note.layouts.put(4, new DashboardLayout.Cell(note.id, 1, 2, 2, 5));
        require(config.restoreSlot(2) && config.widgets.get(0).layouts.size() == 1,
                "Slot invented unused width layouts");
        config.layout(4, null);
        require(config.widgets.get(0).layouts.containsKey(4), "Restored widget cannot generate a newly used width");
        require(config.widgets.get(0).layouts.get(2).y == 12, "Generating unfolded layout altered saved folded placement");
        checks.add("Empty dashboards remain empty; unused widths stay absent and can be generated later without changing saved widths");
    }

    private void malformedSnapshots() throws Exception {
        DashboardConfig config = fixture("damage");
        require(config.saveSlot(0, "Known good", 2), "Could not seed validation snapshot");
        String valid = preferences.getString(slotKey("damage", 0), "");
        String live = signature(config);
        ArrayList<String> invalid = new ArrayList<>();
        invalid.add("{"); invalid.add("[]"); invalid.add("null");
        invalid.add(new JSONObject(valid).put("version", 999).toString());
        invalid.add(new JSONObject(valid).put("columns", -1).toString());
        invalid.add(new JSONObject(valid).put("columns", "2").toString());
        invalid.add(new JSONObject(valid).put("savedAt", 0).toString());
        invalid.add(new JSONObject(valid).put("name", "").toString());
        invalid.add(new JSONObject(valid).put("widgets", new JSONObject()).toString());
        for (String field : Arrays.asList("id", "type", "title", "metric", "note", "color", "window", "min", "max", "advertisedWh", "keys", "layouts")) {
            JSONObject damaged = new JSONObject(valid);
            damaged.getJSONArray("widgets").getJSONObject(0).remove(field);
            invalid.add(damaged.toString());
        }
        String[] fields = {"id", "type", "color", "window", "min", "max", "advertisedWh", "keys", "title"};
        Object[] values = {"", "future-type", 4, 0, "0", -999, -1, new JSONArray().put(9), 5};
        for (int i = 0; i < fields.length; i++) {
            JSONObject damaged = new JSONObject(valid);
            damaged.getJSONArray("widgets").getJSONObject(0).put(fields[i], values[i]);
            invalid.add(damaged.toString());
        }
        JSONObject duplicate = new JSONObject(valid);
        duplicate.getJSONArray("widgets").getJSONObject(1).put("id", duplicate.getJSONArray("widgets").getJSONObject(0).getString("id"));
        invalid.add(duplicate.toString());
        JSONObject marker = new JSONObject(valid);
        marker.getJSONArray("widgets").getJSONObject(1).remove("glanceableScooterStats");
        invalid.add(marker.toString());
        JSONObject excess = new JSONObject(valid);
        JSONArray many = excess.getJSONArray("widgets");
        while (many.length() <= 32) many.put(many.getJSONObject(0));
        invalid.add(excess.toString());
        JSONArray[] rectangles = {new JSONArray().put(0), new JSONArray(new int[]{-1,0,1,4}),
                new JSONArray(new int[]{0,0,3,4}), new JSONArray(new int[]{0,-1,1,4}),
                new JSONArray(new int[]{0,0,1,17}), new JSONArray().put("0").put(0).put(1).put(4)};
        for (JSONArray rectangle : rectangles) {
            JSONObject damaged = new JSONObject(valid);
            damaged.getJSONArray("widgets").getJSONObject(0).getJSONObject("layouts").put("2", rectangle);
            invalid.add(damaged.toString());
        }
        JSONObject badWidth = new JSONObject(valid);
        badWidth.getJSONArray("widgets").getJSONObject(0).getJSONObject("layouts").put("9", new JSONArray(new int[]{0,0,1,4}));
        invalid.add(badWidth.toString());
        for (String broken : invalid) {
            require(preferences.edit().putString(slotKey("damage", 0), broken).commit(), "Could not seed corrupt slot");
            Map<String, ?> before = new LinkedHashMap<>(preferences.getAll());
            require(config.slotExists(0) && config.slot(0) == null, "Corrupt occupied slot presented as valid");
            require(!config.restoreSlot(0), "Malformed snapshot was restored");
            require(signature(config).equals(live) && preferences.getAll().equals(before), "Rejected snapshot partially changed dashboard/preferences");
        }
        require(preferences.edit().putInt(slotKey("damage", 0), 7).commit(), "Could not seed wrong preference type");
        require(config.slot(0) == null && !config.restoreSlot(0) && signature(config).equals(live), "Wrong preference type was not rejected safely");
        require(config.saveSlot(0, "Repaired", 1) && config.restoreSlot(0), "A corrupt slot could not be overwritten explicitly");
        checks.add(invalid.size() + " malformed snapshot shapes rejected without partial state changes; corrupt slots can be overwritten");
    }

    private void boundsNamesAndIsolation() throws Exception {
        DashboardConfig alpha = new DashboardConfig(preferences, "alpha");
        DashboardConfig beta = fixture("beta");
        String alphaLive = signature(alpha), betaLive = signature(beta);
        require(beta.slot(0) == null && !beta.slotExists(0), "Another scooter exposed alpha's slot");
        require(preferences.edit().putBoolean("background_connection", true)
                        .putBoolean("history_enabled", true).putInt("history_retention_days", 730)
                        .putString("device_name_alpha", "Personal scooter")
                        .putString("owner_identity_alpha", "synthetic-owner-fixture").commit(),
                "Could not seed unrelated preferences");
        require(beta.saveSlot(0, "Beta", 1), "Second profile could not save independent slot one");
        Map<String, ?> beforeRestore = new LinkedHashMap<>(preferences.getAll());
        require(alpha.restoreSlot(1), "First profile restore failed");
        Map<String, ?> afterRestore = new LinkedHashMap<>(preferences.getAll());
        beforeRestore.remove("dashboard_v1_alpha"); afterRestore.remove("dashboard_v1_alpha");
        beforeRestore.remove("dashboard_columns"); afterRestore.remove("dashboard_columns");
        require(beforeRestore.equals(afterRestore), "Restore altered names, auth, history settings or another scooter");
        require(signature(new DashboardConfig(preferences, "beta")).equals(betaLive), "Restore altered another scooter's live widgets");
        require(beta.slot(0).name.equals("Beta") && alpha.slot(0).name.equals("Alternate"), "Slot metadata crossed profiles");
        Map<String, ?> beforeInvalid = new LinkedHashMap<>(preferences.getAll());
        for (int index : new int[]{Integer.MIN_VALUE, -1, 3, Integer.MAX_VALUE}) {
            require(!alpha.saveSlot(index, "Invalid", 0) && !alpha.restoreSlot(index)
                            && alpha.slot(index) == null && !alpha.slotExists(index), "Out-of-range slot accepted");
        }
        require(!alpha.saveSlot(0, "Invalid", -1) && !alpha.saveSlot(0, "Invalid", 5), "Invalid column choice accepted");
        require(beforeInvalid.equals(preferences.getAll()), "Invalid API arguments changed preferences");
        require(DashboardConfig.SLOT_COUNT == 3 && DashboardConfig.SLOT_NAME_LIMIT == 40, "Unexpected slot limits");
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 50; i++) name.appendCodePoint(0x1f9a6);
        require(beta.saveSlot(2, name.toString(), 0), "Long Unicode slot name failed");
        String trimmed = beta.slot(2).name;
        require(trimmed.codePointCount(0, trimmed.length()) == 40 && !Character.isHighSurrogate(trimmed.charAt(trimmed.length() - 1)),
                "Name limit split Unicode text");
        require(beta.saveSlot(2, "\n\t\u0000", 0) && beta.slot(2).name.equals("Slot 3"), "Blank name did not get a fallback");
        require(!alphaLive.isEmpty(), "Expected baseline dashboard fixture");
        checks.add("Exactly three per-scooter slots, Unicode-safe names, bounds checks and unrelated preference/history-setting isolation");
    }

    private void migrationCompatibility() throws Exception {
        DashboardConfig old = fixture("migration");
        old.save();
        JSONArray definitions = new JSONArray(preferences.getString("dashboard_v1_migration", ""));
        JSONObject scooter = definitions.getJSONObject(1);
        scooter.remove("scooterStats"); scooter.remove("glanceableScooterStats");
        scooter.put("keys", new JSONArray(Arrays.asList("remaining_range_km", "power_w", "battery_health_percent", "temperature_c")));
        require(preferences.edit().putString("dashboard_v1_migration", definitions.toString()).commit(), "Could not seed old dashboard");
        DashboardConfig migrated = new DashboardConfig(preferences, "migration");
        require(migrated.widgets.get(1).keys.equals(Arrays.asList("odometer_km", "driving_mode")), "Legacy scooter migration changed");
        DashboardConfig.Widget custom = migrated.widgets.get(1);
        custom.keys.clear(); custom.keys.addAll(Arrays.asList("odometer_km", "driving_mode", "charge_cycles", "capacity_throughput_ah"));
        require(migrated.saveSlot(0, "Custom four stats", 0), "Could not snapshot custom scooter stats");
        migrated.reset();
        require(migrated.restoreSlot(0) && migrated.widgets.get(1).keys.size() == 4, "Saved custom stats were remigrated");
        require(new DashboardConfig(preferences, "migration").widgets.get(1).keys.size() == 4, "Restored custom stats were remigrated on reload");
        checks.add("Existing legacy dashboard migration remains intact; saved custom scooter readings are never remigrated");
    }

    private DashboardConfig fixture(String profile) {
        DashboardConfig config = new DashboardConfig(preferences, profile);
        config.widgets.clear();
        for (int i = 0; i < DashboardConfig.TYPES.length; i++) {
            DashboardConfig.Widget widget = new DashboardConfig.Widget(DashboardConfig.TYPES[i]);
            widget.title = "Custom " + i; widget.metric = i % 2 == 0 ? "voltage_v" : "power_w";
            widget.note = "Local note " + i + "\nSecond line"; widget.color = i % 4;
            widget.windowMs = (i + 1) * 120000L; widget.minimum = -40.5 - i;
            widget.maximum = 500.25 + i; widget.advertisedWh = 780.5 + i;
            widget.keys.clear(); widget.keys.addAll(Arrays.asList("speed_kmh", "voltage_v", "power_w"));
            for (int columns = 1; columns <= 4; columns++) {
                int width = Math.min(columns, 1 + i % 2);
                widget.layouts.put(columns, new DashboardLayout.Cell(widget.id,
                        (i + columns) % (columns - width + 1), i * 10 + columns, width, 5 + i % 3));
            }
            config.widgets.add(widget);
        }
        config.save();
        return config;
    }

    private String signature(DashboardConfig config) {
        StringBuilder result = new StringBuilder();
        for (DashboardConfig.Widget w : config.widgets) {
            result.append(w.id).append('|').append(w.type).append('|').append(w.title).append('|')
                    .append(w.metric).append('|').append(w.note).append('|').append(w.color).append('|')
                    .append(w.windowMs).append('|').append(w.minimum).append('|').append(w.maximum).append('|')
                    .append(w.advertisedWh).append('|').append(w.keys).append('|');
            for (int columns = 1; columns <= 4; columns++) {
                DashboardLayout.Cell c = w.layouts.get(columns);
                if (c == null) result.append("absent;");
                else result.append(c.id).append(':').append(c.x).append(',').append(c.y).append(',').append(c.w).append(',').append(c.h).append(';');
            }
            result.append('\n');
        }
        return result.toString();
    }

    private static String slotKey(String profile, int index) { return "dashboard_slot_v1_" + profile + "_" + index; }

    private long historyCount() {
        try (Cursor cursor = history.rawQuery("SELECT COUNT(*) FROM readings", null)) {
            cursor.moveToFirst(); return cursor.getLong(0);
        }
    }

    private void cleanup() {
        if (history != null) { history.close(); history = null; }
        if (historyFile != null) SQLiteDatabase.deleteDatabase(historyFile);
        if (preferences != null) preferences.edit().clear().commit();
        if (fixtureName != null) getTargetContext().deleteSharedPreferences(fixtureName);
    }

    private void require(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
}
