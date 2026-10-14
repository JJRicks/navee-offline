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
            sixColumnPersistence();
            legacyFourColumnSlots();
            temporaryWidthConstraints();
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
        require(signature(reopened).equals(expected), "Full widget options/IDs/order/six layouts did not round-trip");
        require(preferences.getInt("dashboard_columns", -1) == 0, "Adaptive columns were not restored");
        require(signature(new DashboardConfig(preferences, "alpha")).equals(expected), "Restore did not persist live dashboard");
        // Simulate the screen closing after restore, which used to risk rewriting old state.
        reopened.save();
        require(signature(new DashboardConfig(preferences, "alpha")).equals(expected), "close/save overwrote restored state");
        require(preferences.getString(slotKey("alpha", 0), "").equals(snapshot), "Restoration changed the stored snapshot");
        checks.add("All 15 widget types, disconnect preferences, options, IDs and independent 1–6-column rectangles survive save/recreation/restore/close");
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
        invalid.add(new JSONObject(valid).put("columns", 7).toString());
        invalid.add(new JSONObject(valid).put("columns", "2").toString());
        invalid.add(new JSONObject(valid).put("savedAt", 0).toString());
        invalid.add(new JSONObject(valid).put("name", "").toString());
        invalid.add(new JSONObject(valid).put("widgets", new JSONObject()).toString());
        for (String field : Arrays.asList("id", "type", "title", "metric", "note", "color", "window", "min", "max", "advertisedWh", "keys", "layouts")) {
            JSONObject damaged = new JSONObject(valid);
            damaged.getJSONArray("widgets").getJSONObject(0).remove(field);
            invalid.add(damaged.toString());
        }
        String[] fields = {"id", "type", "color", "window", "min", "max", "advertisedWh", "keys", "title", "showDisconnect"};
        Object[] values = {"", "future-type", 4, 0, "0", -999, -1, new JSONArray().put(9), 5, "false"};
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
        badWidth.getJSONArray("widgets").getJSONObject(0).getJSONObject("layouts").put("7", new JSONArray(new int[]{0,0,1,4}));
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
        require(!alpha.saveSlot(0, "Invalid", -1) && !alpha.saveSlot(0, "Invalid", 7), "Invalid column choice accepted");
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

    private void sixColumnPersistence() throws Exception {
        DashboardConfig config = new DashboardConfig(preferences, "six-columns");
        config.layout(6, null);
        DashboardConfig.Widget lock = config.widgets.get(0), scooter = config.widgets.get(1);
        require(lock.type.equals("lock") && scooter.type.equals("scooter"), "Unexpected default widget order");
        require(scooter.layouts.get(6).x == 0 && scooter.layouts.get(6).w == 4
                        && lock.layouts.get(6).x == 4 && lock.layouts.get(6).w == 2,
                "Six-column defaults lost the two-thirds scooter/one-third lock split");
        require(scooter.layouts.get(4).w == 3 && lock.layouts.get(4).x == 3
                        && lock.layouts.get(4).w == 1,
                "Adding six-column defaults changed the four-column defaults");
        require(lock.minimumHeight() == 4, "Lock with Disconnect must reserve room for its action button");
        lock.showDisconnect = false;
        require(lock.minimumHeight() == 3, "Hiding Disconnect must allow the compact three-row lock");
        lock.layouts.get(6).h = 3;
        String expected = signature(config);
        require(config.saveSlot(1, "Six-column split", 6), "Six-column snapshot could not be saved");
        require(config.slot(1).columns == 6, "Six-column metadata was truncated");
        String saved = preferences.getString(slotKey("six-columns", 1), "");
        config.reset();
        DashboardConfig reopened = new DashboardConfig(preferences, "six-columns");
        require(reopened.restoreSlot(1), "Six-column slot could not be restored after recreation");
        require(signature(reopened).equals(expected) && !reopened.widgets.get(0).showDisconnect,
                "Six-column restore lost the saved disconnect preference or widget geometry");
        require(preferences.getInt("dashboard_columns", -1) == 6,
                "Six-column preference did not survive restore");
        reopened.save();
        require(signature(new DashboardConfig(preferences, "six-columns")).equals(expected),
                "Six-column geometry or disconnect preference changed on save/recreation");
        require(reopened.layout(6, null).get(0).h == 3,
                "Restoring Disconnect=false no longer permits its compact saved lock height");
        require(preferences.getString(slotKey("six-columns", 1), "").equals(saved),
                "Restoring or saving the current layout rewrote its saved snapshot");
        require(reopened.saveSlot(2, "Five-column split", 5) && reopened.restoreSlot(2)
                        && preferences.getInt("dashboard_columns", -1) == 5,
                "Five-column preference could not round-trip");
        checks.add("Explicit five/six-column slots preserve every layout and Disconnect=false; default six-column scooter/lock uses 4+2 without changing 1–4");
    }

    private void legacyFourColumnSlots() throws Exception {
        DashboardConfig config = fixture("legacy-four");
        for (DashboardConfig.Widget widget : config.widgets) {
            widget.layouts.remove(5); widget.layouts.remove(6);
            widget.showDisconnect = true;
        }
        config.save();
        String expected = signature(config);
        String geometry = geometry(config, 1, 4);
        require(config.saveSlot(0, "Existing four-column layout", 4), "Could not seed legacy slot");
        JSONObject legacy = new JSONObject(preferences.getString(slotKey("legacy-four", 0), ""));
        require(legacy.getInt("version") == 1, "Legacy fixture must exercise version-one snapshots");
        JSONArray definitions = legacy.getJSONArray("widgets");
        for (int i = 0; i < definitions.length(); i++) definitions.getJSONObject(i).remove("showDisconnect");
        String oldSnapshot = legacy.toString();
        require(preferences.edit().putString(slotKey("legacy-four", 0), oldSnapshot).commit(),
                "Could not store snapshot from before disconnect options and six columns");
        config.widgets.get(0).showDisconnect = false;
        config.widgets.get(0).title = "Changed current layout";
        require(config.restoreSlot(0) && signature(config).equals(expected),
                "Legacy 1–4-column snapshot changed geometry/options or did not default Disconnect to true");
        require(signature(new DashboardConfig(preferences, "legacy-four")).equals(expected),
                "Legacy layout changed on recreation");
        require(preferences.getString(slotKey("legacy-four", 0), "").equals(oldSnapshot),
                "Loading a legacy slot rewrote its serialized snapshot");
        config.layout(6, null);
        require(geometry(config, 1, 4).equals(geometry), "Deriving six columns rewrote existing 1–4 geometry");
        for (DashboardConfig.Widget widget : config.widgets) {
            DashboardLayout.Cell source = widget.layouts.get(4), converted = widget.layouts.get(6);
            DashboardLayout.Cell expectedCell = DashboardLayout.rescale(source, 4, 6);
            require(converted.x == expectedCell.x && converted.w == expectedCell.w
                            && converted.y == source.y && converted.h == source.h,
                    "A noncolliding legacy layout did not derive six-column geometry proportionally");
        }
        config.layout(5, null);
        require(geometry(config, 1, 4).equals(geometry), "Deriving five columns rewrote legacy geometry");
        checks.add("Version-one 1–4-column snapshots without the new option restore unchanged with Disconnect=true; 5/6 are derived independently");
    }

    private void temporaryWidthConstraints() throws Exception {
        DashboardConfig config = new DashboardConfig(preferences, "width-constraints");
        config.layout(6, null);
        config.save();
        require(config.saveSlot(0, "Original wide layout", 6), "Could not save width-constraint fixture");
        String expected = signature(config);
        String expectedSlot = preferences.getString(slotKey("width-constraints", 0), "");
        String stored = preferences.getString("dashboard_v1_width-constraints", "");
        java.util.List<DashboardLayout.Cell> wide = config.layout(6, null, 900, 10, 1);
        java.util.List<DashboardLayout.Cell> narrow = config.layout(6, null, 280, 10, 1.4f);
        require(narrow.get(0).w > wide.get(0).w,
                "Narrow/large-text fixture did not actually broaden the lock widget");
        require(signature(config).equals(expected), "Temporary width constraints mutated stored cell geometry");
        require(preferences.getString("dashboard_v1_width-constraints", "").equals(stored)
                        && preferences.getString(slotKey("width-constraints", 0), "").equals(expectedSlot),
                "Temporary width constraints wrote to preferences or saved slots");
        for (int i = 0; i < narrow.size(); i++) {
            DashboardLayout.Cell cell = narrow.get(i);
            require(cell.w >= config.widgets.get(i).minimumWidth(6, 280, 10, 1.4f)
                            && cell.x >= 0 && cell.x + cell.w <= 6,
                    "Narrow layout violated a card's minimum span or bounds");
            for (int j = 0; j < i; j++)
                require(!DashboardLayout.overlaps(cell, narrow.get(j)), "Width adaptation overlapped cards");
        }
        java.util.List<DashboardLayout.Cell> restored = config.layout(6, null, 900, 10, 1);
        for (int i = 0; i < wide.size(); i++) {
            DashboardLayout.Cell before = wide.get(i), after = restored.get(i);
            require(before.x == after.x && before.y == after.y && before.w == after.w && before.h == after.h,
                    "Returning to a wide window failed to restore the original displayed geometry");
        }
        config.save();
        require(signature(new DashboardConfig(preferences, "width-constraints")).equals(expected),
                "Closing after width adaptation persisted temporary expanded cells");
        checks.add("Narrow windows and large text widen cards without overlap or stored-layout mutation, then recover the original wide layout");
    }

    private String geometry(DashboardConfig config, int firstColumn, int lastColumn) {
        StringBuilder result = new StringBuilder();
        for (DashboardConfig.Widget widget : config.widgets) {
            result.append(widget.id).append(':');
            for (int columns = firstColumn; columns <= lastColumn; columns++) {
                DashboardLayout.Cell cell = widget.layouts.get(columns);
                if (cell == null) result.append("absent;");
                else result.append(cell.x).append(',').append(cell.y).append(',')
                        .append(cell.w).append(',').append(cell.h).append(';');
            }
        }
        return result.toString();
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
            widget.showDisconnect = i % 2 != 0;
            widget.keys.clear(); widget.keys.addAll(Arrays.asList("speed_kmh", "voltage_v", "power_w"));
            for (int columns = 1; columns <= DashboardConfig.MAX_COLUMNS; columns++) {
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
                    .append(w.advertisedWh).append('|').append(w.keys).append('|')
                    .append(w.showDisconnect).append('|');
            for (int columns = 1; columns <= DashboardConfig.MAX_COLUMNS; columns++) {
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
