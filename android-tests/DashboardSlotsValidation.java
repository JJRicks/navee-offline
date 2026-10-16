package local.navee.offlinelock.tests;

import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.Bundle;

import local.navee.offlinelock.DashboardConfig;
import local.navee.offlinelock.DashboardEditHistory;
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
            newHomeWidgetTypes();
            allWidgetsExample();
            releaseDefaultsPreserveExistingData();
            fullRoundTrip();
            overwriteAndReset();
            emptyAndMissingLayouts();
            malformedSnapshots();
            boundsNamesAndIsolation();
            migrationCompatibility();
            sixColumnPersistence();
            classicLockPersistence();
            quickAccessPersistence();
            legacyFourColumnSlots();
            temporaryWidthConstraints();
            editHistoryRestoresStructureOnly();
            editHistoryNoOpsAndBounds();
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

    /** The redesigned Home's four types are appended so every older type index stays stable. */
    private void newHomeWidgetTypes() {
        String[] added = {"lockbar", "overview", "tiles", "shortcuts"};
        String[] names = {"Lock bar", "Scooter overview", "Reading tiles", "Setting tiles"};
        int base = DashboardConfig.TYPES.length - added.length;
        require(base == 15 && DashboardConfig.TYPES[0].equals("lock") && DashboardConfig.TYPES[1].equals("scooter")
                        && DashboardConfig.NAMES.length == DashboardConfig.TYPES.length
                        && DashboardConfig.DESCRIPTIONS.length == DashboardConfig.TYPES.length,
                "New widget types must be appended after the fifteen existing types");
        int[] defaultHeights = {1, 4, 2, 1}, minimumHeights = {1, 3, 1, 1};
        for (int i = 0; i < added.length; i++) {
            require(DashboardConfig.TYPES[base + i].equals(added[i]) && DashboardConfig.NAMES[base + i].equals(names[i])
                            && !DashboardConfig.DESCRIPTIONS[base + i].trim().isEmpty(),
                    "Catalog entry changed for " + added[i]);
            DashboardConfig.Widget widget = new DashboardConfig.Widget(added[i]);
            require(widget.title.equals(names[i]) && widget.metric.equals("power_w") && widget.windowMs == 86400000L
                            // The lock bar starts without its Disconnect button so the slider fills the card.
                            && widget.showDisconnect == !added[i].equals("lockbar") && widget.note.isEmpty(),
                    "Constructor options changed for " + added[i]);
            require(widget.defaultHeight() == defaultHeights[i] && widget.minimumHeight() == minimumHeights[i],
                    "Default/minimum height changed for " + added[i]);
            widget.showDisconnect = false;
            require(widget.minimumHeight() == minimumHeights[i], "Disconnect option changed " + added[i] + " minimum height");
            for (int columns = 1; columns <= DashboardConfig.MAX_COLUMNS; columns++)
                require(widget.defaultWidth(columns) == columns, added[i] + " must default to full width");
            // Like most cards, each fits half of a 344 dp two-column phone grid.
            require(widget.minimumWidth(2, 344, 10, 1) == 1, added[i] + " no longer fits half a phone grid");
        }
        require(new DashboardConfig.Widget("overview").keys.equals(Arrays.asList("odometer_km", "driving_mode")),
                "Scooter overview default readings changed");
        require(new DashboardConfig.Widget("tiles").keys.equals(Arrays.asList("power_w", "temperature_c", "battery_health_percent")),
                "Reading tiles default readings changed");
        require(new DashboardConfig.Widget("shortcuts").quickActions.equals(
                        Arrays.asList("feature:maximum_speed", "feature:timed_charging")),
                "Setting tiles default shortcuts changed");
        require(new DashboardConfig.Widget("lockbar").quickActions.equals(local.navee.offlinelock.QuickAccessActions.defaultIds()),
                "Lock bar must keep the generic shortcut defaults");
        checks.add("Lock bar, Scooter overview, Reading tiles and Setting tiles append to the catalog with their constructor readings/shortcuts, full-width defaults and 1/4/2/1-row heights");
    }

    private void allWidgetsExample() throws Exception {
        String profile = "example-fresh", exampleKey = slotKey(profile, 2);
        DashboardConfig config = new DashboardConfig(preferences, profile);
        assertCapturedDefaults(config, this::require);
        require(preferences.getInt("dashboard_columns", -1) == 5,
                "Saving the example changed fresh active columns");
        require(!config.slotExists(0) && !config.slotExists(1), "Example occupied another saved slot");
        require(config.slot(2) != null && config.slot(2).name.equals("All widgets")
                        && config.slot(2).widgetCount == DashboardConfig.TYPES.length && config.slot(2).columns == 6,
                "Fresh third slot does not contain the complete named example");
        String seeded = preferences.getString(exampleKey, "");
        JSONObject snapshot = new JSONObject(seeded);
        java.util.HashSet<String> exampleIds = new java.util.HashSet<>();
        JSONArray exampleWidgets = snapshot.getJSONArray("widgets");
        for (int i = 0; i < exampleWidgets.length(); i++) {
            String id = exampleWidgets.getJSONObject(i).getString("id");
            require(exampleIds.add(id) && java.util.UUID.fromString(id).toString().equals(id),
                    "Example widget IDs are not generated UUIDs");
            for (DashboardConfig.Widget widget : config.widgets)
                require(!widget.id.equals(id), "Saved example shares an active widget ID");
        }
        Map<String, ?> original = new LinkedHashMap<>(preferences.getAll());
        new DashboardConfig(preferences, profile);
        require(original.equals(preferences.getAll()), "Reload rewrote the example or generated new IDs");

        // Reproduce an upgrade with custom active content, two occupied slots, and a missing third.
        config.widgets.get(0).title = "Existing connection";
        config.widgets.get(1).keys.clear(); config.widgets.get(1).keys.add("voltage_v");
        config.widgets.get(1).layouts.get(2).y = 28;
        config.save();
        require(config.saveSlot(0, "Commute", 2) && config.saveSlot(1, "Weekend", 4),
                "Could not seed existing saved layouts");
        require(preferences.edit().remove(exampleKey).putInt("dashboard_columns", 2)
                        .putString("unrelated_setting", "untouched").commit(), "Could not seed upgrade preferences");
        String active = signature(config);
        Map<String, ?> before = new LinkedHashMap<>(preferences.getAll());
        final int[] failedBuilds = {0};
        DashboardConfig failedExample = withExampleFactory(profile, () -> {
            failedBuilds[0]++;
            throw new IllegalStateException("Injected optional example failure");
        });
        require(failedBuilds[0] == 1 && before.equals(preferences.getAll())
                        && active.equals(signature(failedExample)) && !failedExample.slotExists(2),
                "Optional builder failure reset a valid dashboard, changed saved data, or retried during construction");
        config = new DashboardConfig(preferences, profile);
        Map<String, ?> after = new LinkedHashMap<>(preferences.getAll()); after.remove(exampleKey);
        require(before.equals(after) && active.equals(signature(config)),
                "Upgrade example changed active content, columns, another slot or unrelated data");
        seeded = preferences.getString(exampleKey, "");
        before = new LinkedHashMap<>(preferences.getAll());
        new DashboardConfig(preferences, profile);
        require(before.equals(preferences.getAll()), "Upgrade example was not idempotent");

        DashboardConfig second = new DashboardConfig(preferences, "example-second");
        after = new LinkedHashMap<>(preferences.getAll());
        after.remove("dashboard_v1_example-second"); after.remove(slotKey("example-second", 2));
        require(before.equals(after) && second.slot(2) != null,
                "New profile failed to seed its own example or changed another profile");
        JSONArray secondWidgets = new JSONObject(preferences.getString(slotKey("example-second", 2), ""))
                .getJSONArray("widgets");
        for (int i = 0; i < secondWidgets.length(); i++)
            require(!exampleIds.contains(secondWidgets.getJSONObject(i).getString("id")),
                    "Profiles share generated example IDs");

        before = new LinkedHashMap<>(preferences.getAll());
        failedBuilds[0] = 0;
        DashboardConfig freshWithoutExample = withExampleFactory("example-builder-unavailable", () -> {
            failedBuilds[0]++;
            throw new IllegalStateException("Injected fresh-profile example failure");
        });
        assertCapturedDefaults(freshWithoutExample, this::require);
        after = new LinkedHashMap<>(preferences.getAll()); after.remove("dashboard_v1_example-builder-unavailable");
        require(failedBuilds[0] == 1 && !freshWithoutExample.slotExists(2) && before.equals(after),
                "Optional builder failure blocked a new profile or changed existing preferences");
        String freshDashboard = signature(freshWithoutExample);
        DashboardConfig retry = new DashboardConfig(preferences, "example-builder-unavailable");
        require(retry.slot(2) != null && freshDashboard.equals(signature(retry)),
                "Retrying optional example changed the new profile's active dashboard");

        require(config.restoreSlot(2) && preferences.getInt("dashboard_columns", -1) == 6,
                "Example did not restore its paired layouts and column choice");
        assertAllWidgetsExample(config, this::require);
        String geometry = geometry(config, 1, 6);
        for (int columns = 1; columns <= 6; columns++) {
            java.util.List<DashboardLayout.Cell> cells = config.layout(columns, null);
            int end = 0;
            for (int i = 0; i < cells.size(); i++) {
                DashboardLayout.Cell cell = cells.get(i);
                require(cell.id.equals(config.widgets.get(i).id) && cell.x >= 0 && cell.y >= 0
                                && cell.w > 0 && cell.x + cell.w <= columns
                                && cell.h >= config.widgets.get(i).minimumHeight(),
                        "Example cell violates its type's constraints at " + columns + " columns");
                end = Math.max(end, cell.y + cell.h);
                for (int j = 0; j < i; j++)
                    require(!DashboardLayout.overlaps(cell, cells.get(j)), "Example cells overlap");
            }
            for (int row = 0; row < end; row++) {
                int occupied = 0;
                for (DashboardLayout.Cell cell : cells)
                    if (cell.y <= row && row < cell.y + cell.h) occupied += cell.w;
                require(occupied == columns, "Example leaves a phantom gap at " + columns + " columns, row " + row);
            }
            float width = new float[] {0, 280, 312, 500, 640, 640, 600}[columns];
            for (float scale : new float[] {1, 1.4f}) {
                cells = config.layout(columns, null, width, 10, scale);
                require(cells.size() == DashboardConfig.TYPES.length, "Measured example lost a widget");
                for (int i = 0; i < cells.size(); i++) {
                    DashboardConfig.Widget widget = config.widgets.get(i);
                    DashboardLayout.Cell cell = cells.get(i);
                    require(cell.x >= 0 && cell.y >= 0 && cell.x + cell.w <= columns
                                    && cell.w >= widget.minimumWidth(columns, width, 10, scale)
                                    && cell.h >= widget.minimumHeight(cell.w * width / columns - 10),
                            "Measured example violates " + widget.type + " size constraints");
                    for (int j = 0; j < i; j++)
                        require(!DashboardLayout.overlaps(cell, cells.get(j)), "Measured example cells overlap");
                }
            }
        }
        require(geometry.equals(geometry(config, 1, 6)) && seeded.equals(preferences.getString(exampleKey, "")),
                "Temporary window/font sizing changed the saved example");
        DashboardLayout.Cell scooter = null, lock = null;
        for (DashboardConfig.Widget widget : config.widgets) {
            if (widget.type.equals("scooter")) scooter = widget.layouts.get(2);
            if (widget.type.equals("lock")) lock = widget.layouts.get(2);
        }
        require(scooter != null && lock != null
                        && scooter.x == 0 && scooter.y == 0 && scooter.w == 2 && scooter.h == 3
                        && lock.x == 0 && lock.y == 3 && lock.w == 1 && lock.h == 4,
                "Compact example no longer keeps scooter and connection near the top");
        config.widgets.get(0).title = "My edited example"; config.save();
        require(seeded.equals(preferences.getString(exampleKey, "")), "Active edits mutated the saved example");
        config.reset();
        require(seeded.equals(preferences.getString(exampleKey, "")), "Restore defaults replaced the example");

        // Owner replacements (including an intentional empty dashboard) always win.
        require(config.saveSlot(2, "My replacement", 3), "Could not overwrite the example");
        String replacement = preferences.getString(exampleKey, "");
        new DashboardConfig(preferences, profile);
        require(replacement.equals(preferences.getString(exampleKey, "")), "Reload replaced the owner's slot");
        config.widgets.clear(); config.save();
        require(config.saveSlot(2, "Empty on purpose", 0), "Could not save an empty slot");
        replacement = preferences.getString(exampleKey, "");
        config = new DashboardConfig(preferences, profile);
        require(config.widgets.isEmpty() && config.slot(2).widgetCount == 0
                        && replacement.equals(preferences.getString(exampleKey, "")),
                "Seed replaced intentionally empty active or saved content");
        require(preferences.edit().putString(exampleKey, "unreadable owner data").commit(), "Could not seed damaged slot");
        config = new DashboardConfig(preferences, profile);
        require(config.slotExists(2) && config.slot(2) == null
                        && preferences.getString(exampleKey, "").equals("unreadable owner data"),
                "Seed discarded an occupied unreadable slot");
        require(preferences.edit().remove(exampleKey).remove("dashboard_columns").commit(), "Could not seed legacy Adaptive example");
        config = new DashboardConfig(preferences, profile);
        require(config.widgets.isEmpty() && !preferences.contains("dashboard_columns") && config.slot(2) != null,
                "Seeding a legacy profile changed its empty dashboard or Adaptive choice");
        require(preferences.edit().clear().commit(), "Could not reset isolated example fixture");
        checks.add("All widgets seeds only an absent third slot per profile; generated IDs, current dashboards, column choices, occupied/empty/damaged slots and other saved data survive reloads and upgrades");
        checks.add("Injected example-builder failures leave valid/custom and fresh dashboards usable, preserve saved state, and allow a later retry without regenerating active widget IDs");
        checks.add("Every example type has sensible options and explicit nonoverlapping, gap-free 1–6-column layouts; measured sizing remains safe without rewriting the saved example");
    }

    private DashboardConfig withExampleFactory(String profile,
            java.util.function.Supplier<ArrayList<DashboardConfig.Widget>> factory) throws Exception {
        java.lang.reflect.Constructor<DashboardConfig> constructor = DashboardConfig.class.getDeclaredConstructor(
                SharedPreferences.class, String.class, java.util.function.Supplier.class);
        constructor.setAccessible(true);
        return constructor.newInstance(preferences, profile, factory);
    }

    static void assertAllWidgetsExample(DashboardConfig config,
            java.util.function.BiConsumer<Boolean, String> check) {
        check.accept(config.widgets.size() == DashboardConfig.TYPES.length, "Example must contain every widget type exactly once");
        java.util.HashSet<String> types = new java.util.HashSet<>();
        for (DashboardConfig.Widget widget : config.widgets) {
            check.accept(types.add(widget.type), "Example repeats a widget type");
            check.accept(widget.layouts.size() == 6, "Example lacks an explicit layout width");
            for (int columns = 1; columns <= 6; columns++)
                check.accept(widget.layouts.containsKey(columns), "Example lacks " + columns + " columns");
            switch (widget.type) {
                case "scooter":
                    check.accept(widget.keys.equals(Arrays.asList("odometer_km", "driving_mode")), "Example scooter readings changed"); break;
                case "metric":
                    check.accept(widget.metric.equals("battery_percent"), "Example big reading must show charge"); break;
                case "gauge":
                    check.accept(widget.metric.equals("speed_kmh") && widget.minimum == 0 && widget.maximum == 50,
                            "Example speed gauge must use a sensible raw-km/h range"); break;
                case "live":
                    check.accept(widget.metric.equals("power_w") && widget.windowMs == 120000L, "Example live power window changed"); break;
                case "history":
                    check.accept(widget.metric.equals("battery_percent") && widget.windowMs == 7 * 86400000L, "Example history window changed"); break;
                case "health":
                    check.accept(widget.metric.equals("battery_health_percent") && widget.windowMs == 30 * 86400000L, "Example health window changed"); break;
                case "capacity":
                    check.accept(widget.windowMs == 90 * 86400000L && widget.advertisedWh == 0, "Example overrides model capacity or history"); break;
                case "readings":
                    check.accept(widget.keys.equals(Arrays.asList("voltage_v", "current_a", "power_w", "temperature_c")), "Example electrical readings changed"); break;
                case "quick":
                    check.accept(widget.quickActions.equals(Arrays.asList("lights", "controls", "stats", "rides")), "Example shortcuts changed"); break;
                case "lock":
                    check.accept(widget.showDisconnect, "Example hides manual disconnect"); break;
                case "lockbar":
                    check.accept(!widget.showDisconnect, "Example lock bar shows a Disconnect button"); break;
                case "overview":
                    check.accept(widget.keys.size() <= 4,
                            "Example battery overview has too many readings"); break;
                case "tiles":
                    check.accept(widget.keys.size() >= 1 && widget.keys.size() <= 4,
                            "Example reading tiles need one to four readings"); break;
                case "shortcuts":
                    check.accept(widget.quickActions.size() >= 1 && widget.quickActions.size() <= 4,
                            "Example setting tiles need one to four shortcuts"); break;
                case "note":
                    check.accept(widget.note.length() > 0 && widget.note.length() < 160, "Example explanation is missing or too long"); break;
            }
        }
        check.accept(types.equals(new java.util.HashSet<>(Arrays.asList(DashboardConfig.TYPES))), "Example types do not match the available catalog");
    }

    private void releaseDefaultsPreserveExistingData() throws Exception {
        DashboardConfig fresh = new DashboardConfig(preferences, "release-fresh");
        assertCapturedDefaults(fresh, this::require);
        require(preferences.getInt("dashboard_columns", -1) == 5, "Fresh install must select five columns");
        String original = signature(fresh);
        require(signature(new DashboardConfig(preferences, "release-fresh")).equals(original),
                "Reloading release defaults changed IDs, options or saved geometry");
        fresh.widgets.get(0).title = "My existing lock";
        fresh.widgets.get(1).keys.clear(); fresh.widgets.get(1).keys.add("voltage_v");
        fresh.widgets.get(2).layouts.get(5).y = 40;
        fresh.save();
        require(preferences.edit().putInt("dashboard_columns", 6).putString("unrelated_setting", "keep").commit(), "Could not seed existing preferences");
        require(fresh.saveSlot(1, "My backup", 6), "Could not save pre-reset layout");
        String existing = signature(fresh), slot = preferences.getString(slotKey("release-fresh", 1), "");
        Map<String, ?> before = new LinkedHashMap<>(preferences.getAll());
        require(signature(new DashboardConfig(preferences, "release-fresh")).equals(existing)
                        && before.equals(preferences.getAll()), "Upgrade changed an existing dashboard or preferences");
        DashboardConfig second = new DashboardConfig(preferences, "release-second");
        assertCapturedDefaults(second, this::require);
        require(preferences.getInt("dashboard_columns", -1) == 6, "A new profile overwrote another profile's column choice");
        for (DashboardConfig.Widget widget : second.widgets)
            for (DashboardConfig.Widget first : fresh.widgets)
                require(!widget.id.equals(first.id), "New profiles share widget IDs");
        require(preferences.edit().remove("dashboard_columns").commit(), "Could not seed legacy Adaptive choice");
        new DashboardConfig(preferences, "release-fresh");
        new DashboardConfig(preferences, "release-legacy-new-profile");
        require(!preferences.contains("dashboard_columns"), "Existing Adaptive choice was replaced on upgrade/new profile");
        before = new LinkedHashMap<>(preferences.getAll());
        fresh.reset(); assertCapturedDefaults(fresh, this::require);
        require(preferences.getInt("dashboard_columns", -1) == 5, "Explicit restore did not reset columns to five");
        require(slot.equals(preferences.getString(slotKey("release-fresh", 1), "")), "Restore defaults changed a saved slot");
        Map<String, ?> after = new LinkedHashMap<>(preferences.getAll());
        before.remove("dashboard_v1_release-fresh"); before.remove("dashboard_columns");
        after.remove("dashboard_v1_release-fresh"); after.remove("dashboard_columns");
        require(before.equals(after), "Restore defaults changed another profile, slot or unrelated preference");
        require(fresh.restoreSlot(1) && signature(fresh).equals(existing)
                        && preferences.getInt("dashboard_columns", -1) == 6,
                "Restoring the backup failed to recover custom widgets and columns after default reset");
        fresh.reset();
        String five = geometry(fresh, 5, 5);
        int defaultCount = fresh.widgets.size();
        require(defaultCount == 5, "Release default must hold exactly five widgets");
        for (int columns = 1; columns <= DashboardConfig.MAX_COLUMNS; columns++) {
            ArrayList<DashboardLayout.Cell> captured = new ArrayList<>();
            for (int i = 0; i < defaultCount; i++)
                captured.add(fresh.widgets.get(i).layouts.get(columns).copy());
            // The overview is the first, top-left card and the lock bar sits directly under it.
            DashboardLayout.Cell overviewCell = captured.get(0), lockBar = captured.get(1);
            require(fresh.widgets.get(0).type.equals("overview") && overviewCell.x == 0 && overviewCell.y == 0,
                    "Scooter overview is not the top-left default card at " + columns + " columns");
            require(fresh.widgets.get(1).type.equals("lockbar") && lockBar.x == 0
                            && lockBar.y == overviewCell.y + overviewCell.h && lockBar.h == 1,
                    "Lock bar is not directly under the overview at " + columns + " columns");
            int occupied = 0;
            for (int i = 0; i < defaultCount; i++) occupied += captured.get(i).w * captured.get(i).h;
            int end = 0;
            for (DashboardLayout.Cell cell : captured) end = Math.max(end, cell.y + cell.h);
            java.util.List<DashboardLayout.Cell> settled = fresh.layout(columns, null);
            require(settled.size() == defaultCount, "Default layout lost a widget at " + columns + " columns");
            for (int i = 0; i < defaultCount; i++) {
                DashboardLayout.Cell old = captured.get(i), cell = settled.get(i);
                require(cell.x == old.x && cell.y == old.y && cell.w == old.w && cell.h == old.h,
                        "Layout settling displaced the default " + fresh.widgets.get(i).type + " at " + columns + " columns");
            }
            for (int i = 0; i < settled.size(); i++)
                for (int j = 0; j < i; j++)
                    require(!DashboardLayout.overlaps(settled.get(i), settled.get(j)),
                            "Default cards overlap at " + columns + " columns");
            if (columns <= 5) {
                // One full-width stack with no gaps between cards.
                require(occupied == columns * end, "Default stack leaves a gap at " + columns + " columns");
                for (DashboardLayout.Cell cell : captured)
                    require(cell.x == 0 && cell.w == columns, "Default stack card is not full width at " + columns + " columns");
            }
        }
        for (int columns = 1; columns <= 6; columns++) {
            java.util.List<DashboardLayout.Cell> cells = fresh.layout(columns, null, columns * 80, 10, 1.4f);
            require(cells.size() == defaultCount, "Adaptive fallback lost a default widget");
            for (int i = 0; i < cells.size(); i++) {
                DashboardLayout.Cell cell = cells.get(i);
                require(cell.x >= 0 && cell.y >= 0 && cell.x + cell.w <= columns,
                        "Adaptive default escaped its available columns");
                for (int j = 0; j < i; j++)
                    require(!DashboardLayout.overlaps(cell, cells.get(j)), "Adaptive default cards overlap");
            }
        }
        require(geometry(fresh, 5, 5).equals(five)
                        && preferences.getInt("dashboard_columns", -1) == 5,
                "Fallback sizing rewrote the captured five-column layout or preference");
        require(preferences.edit().clear().commit(), "Could not reset isolated default fixture");
        checks.add("Five-widget Home default (scooter overview, lock bar beneath it, reading tiles, setting tiles, recorder) options/1–6-column layouts and fresh IDs match; overview stays top-left with the lock bar directly under it; five-column defaults preserve upgrades, Adaptive choices, other profiles and slots");
    }

    /** Golden sanitized release default, independent of the production default factory. */
    static void assertCapturedDefaults(DashboardConfig configuration,
            java.util.function.BiConsumer<Boolean, String> check) {
        try {
            JSONArray expected = new JSONArray(
                    "[{\"type\":\"overview\",\"title\":\"Scooter overview\",\"metric\":\"power_w\",\"note\":\"\",\"color\":0,\"window\":86400000,\"min\":0,\"max\":100,\"advertisedWh\":0,\"showDisconnect\":true,\"absoluteAxis\":false,\"quickActions\":[\"lights\",\"controls\",\"stats\",\"rides\"],\"keys\":[\"odometer_km\",\"driving_mode\"],\"layouts\":{\"1\":[0,0,1,4],\"2\":[0,0,2,4],\"3\":[0,0,3,4],\"4\":[0,0,4,4],\"5\":[0,0,5,4],\"6\":[0,0,3,4]}}"
                    + ",{\"type\":\"lockbar\",\"title\":\"Lock bar\",\"metric\":\"power_w\",\"note\":\"\",\"color\":0,\"window\":86400000,\"min\":0,\"max\":100,\"advertisedWh\":0,\"showDisconnect\":false,\"absoluteAxis\":false,\"quickActions\":[\"lights\",\"controls\",\"stats\",\"rides\"],\"keys\":[\"remaining_range_km\",\"power_w\",\"battery_health_percent\",\"temperature_c\"],\"layouts\":{\"1\":[0,4,1,1],\"2\":[0,4,2,1],\"3\":[0,4,3,1],\"4\":[0,4,4,1],\"5\":[0,4,5,1],\"6\":[0,4,3,1]}}"
                    + ",{\"type\":\"tiles\",\"title\":\"Reading tiles\",\"metric\":\"power_w\",\"note\":\"\",\"color\":0,\"window\":86400000,\"min\":0,\"max\":100,\"advertisedWh\":0,\"showDisconnect\":true,\"absoluteAxis\":false,\"quickActions\":[\"lights\",\"controls\",\"stats\",\"rides\"],\"keys\":[\"power_w\",\"temperature_c\",\"battery_health_percent\"],\"layouts\":{\"1\":[0,5,1,2],\"2\":[0,5,2,2],\"3\":[0,5,3,2],\"4\":[0,5,4,2],\"5\":[0,5,5,2],\"6\":[3,0,3,2]}}"
                    + ",{\"type\":\"shortcuts\",\"title\":\"Setting tiles\",\"metric\":\"power_w\",\"note\":\"\",\"color\":0,\"window\":86400000,\"min\":0,\"max\":100,\"advertisedWh\":0,\"showDisconnect\":true,\"absoluteAxis\":false,\"quickActions\":[\"feature:maximum_speed\",\"feature:timed_charging\"],\"keys\":[\"remaining_range_km\",\"power_w\",\"battery_health_percent\",\"temperature_c\"],\"layouts\":{\"1\":[0,7,1,1],\"2\":[0,7,2,1],\"3\":[0,7,3,1],\"4\":[0,7,4,1],\"5\":[0,7,5,1],\"6\":[3,2,3,1]}}"
                    + ",{\"type\":\"ride\",\"title\":\"Ride recorder\",\"metric\":\"power_w\",\"note\":\"\",\"color\":0,\"window\":86400000,\"min\":0,\"max\":100,\"advertisedWh\":0,\"showDisconnect\":true,\"absoluteAxis\":false,\"quickActions\":[\"lights\",\"controls\",\"stats\",\"rides\"],\"keys\":[\"remaining_range_km\",\"power_w\",\"battery_health_percent\",\"temperature_c\"],\"layouts\":{\"1\":[0,8,1,1],\"2\":[0,8,2,1],\"3\":[0,8,3,1],\"4\":[0,8,4,1],\"5\":[0,8,5,1],\"6\":[3,3,3,1]}}]");
            check.accept(configuration.widgets.size() == expected.length(), "Captured default widget count changed");
            java.util.HashSet<String> ids = new java.util.HashSet<>();
            for (int i = 0; i < expected.length(); i++) {
                DashboardConfig.Widget widget = configuration.widgets.get(i);
                JSONObject wanted = expected.getJSONObject(i);
                check.accept(ids.add(widget.id) && java.util.UUID.fromString(widget.id).toString().equals(widget.id),
                        "Default widget ID is not freshly generated");
                check.accept(widget.type.equals(wanted.getString("type")) && widget.title.equals(wanted.getString("title"))
                                && widget.metric.equals(wanted.getString("metric")) && widget.note.equals(wanted.getString("note"))
                                && widget.color == wanted.getInt("color") && widget.windowMs == wanted.getLong("window")
                                && widget.minimum == wanted.getDouble("min") && widget.maximum == wanted.getDouble("max")
                                && widget.advertisedWh == wanted.getDouble("advertisedWh")
                                && widget.showDisconnect == wanted.getBoolean("showDisconnect")
                                && widget.absoluteAxis == wanted.getBoolean("absoluteAxis"),
                        "Captured options changed for " + widget.type);
                check.accept(new JSONArray(widget.keys).toString().equals(wanted.getJSONArray("keys").toString())
                                && new JSONArray(widget.quickActions).toString().equals(wanted.getJSONArray("quickActions").toString()),
                        "Captured readings or shortcut order changed for " + widget.type);
                JSONObject layouts = wanted.getJSONObject("layouts");
                // Every default card has an explicit rectangle for every supported column count.
                check.accept(widget.layouts.size() == layouts.length() && layouts.length() == DashboardConfig.MAX_COLUMNS,
                        "Default layout set changed for " + widget.type);
                for (int columns = 1; columns <= DashboardConfig.MAX_COLUMNS; columns++) {
                    JSONArray rectangle = layouts.getJSONArray(Integer.toString(columns));
                    DashboardLayout.Cell cell = widget.layouts.get(columns);
                    check.accept(cell != null && cell.id.equals(widget.id) && cell.x == rectangle.getInt(0)
                                    && cell.y == rectangle.getInt(1) && cell.w == rectangle.getInt(2) && cell.h == rectangle.getInt(3),
                            "Captured " + columns + "-column rectangle changed for " + widget.type);
                }
            }
        } catch (Exception failure) { throw new AssertionError("Default capture mismatch", failure); }
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
        checks.add("All " + DashboardConfig.TYPES.length + " widget types, disconnect preferences, options, IDs and independent 1–6-column rectangles survive save/recreation/restore/close");
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
        require(config.widgets.size() == 5 && preferences.getInt("dashboard_columns", -1) == 5, "Default reset failed");
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
        String[] fields = {"id", "type", "color", "window", "min", "max", "advertisedWh", "keys", "title", "showDisconnect", "absoluteAxis"};
        Object[] values = {"", "future-type", 4, 0, "0", -999, -1, new JSONArray().put(9), 5, "false", "true"};
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
        // Setting tiles hold at most four shortcuts (Quick access allows eight): five is damage.
        JSONObject crowded = new JSONObject(valid);
        crowded.getJSONArray("widgets").getJSONObject(0).put("type", "shortcuts").put("quickActions", new JSONArray()
                .put("lights").put("controls").put("stats").put("rides").put("feature:maximum_speed"));
        invalid.add(crowded.toString());
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
        DashboardConfig.Widget overview = config.widgets.get(0), lock = config.widgets.get(1);
        DashboardConfig.Widget tiles = config.widgets.get(2);
        require(lock.type.equals("lockbar") && overview.type.equals("overview") && tiles.type.equals("tiles"),
                "Unexpected default widget order");
        require(overview.layouts.get(6).x == 0 && overview.layouts.get(6).y == 0 && overview.layouts.get(6).w == 3
                        && lock.layouts.get(6).x == 0 && lock.layouts.get(6).y == 4 && lock.layouts.get(6).w == 3
                        && tiles.layouts.get(6).x == 3 && tiles.layouts.get(6).y == 0 && tiles.layouts.get(6).w == 3,
                "Six-column defaults lost the two-column overview/lock beside tiles arrangement");
        require(overview.layouts.get(4).x == 0 && overview.layouts.get(4).y == 0
                        && overview.layouts.get(4).w == 4 && overview.layouts.get(4).h == 4
                        && lock.layouts.get(4).x == 0 && lock.layouts.get(4).y == 4
                        && lock.layouts.get(4).w == 4 && lock.layouts.get(4).h == 1,
                "Adding six-column defaults changed the four-column full-width stack");
        require(lock.minimumHeight() == 1, "Lock bar must fit one grid row with Disconnect enabled");
        lock.showDisconnect = false;
        require(lock.minimumHeight() == 1, "Hiding Disconnect changed the one-row lock bar minimum");
        require(overview.minimumHeight() == 3, "Scooter overview minimum height changed");
        overview.layouts.get(6).h = 3;
        String expected = signature(config);
        require(config.saveSlot(1, "Six-column split", 6), "Six-column snapshot could not be saved");
        require(config.slot(1).columns == 6, "Six-column metadata was truncated");
        String saved = preferences.getString(slotKey("six-columns", 1), "");
        config.reset();
        DashboardConfig reopened = new DashboardConfig(preferences, "six-columns");
        require(reopened.restoreSlot(1), "Six-column slot could not be restored after recreation");
        require(signature(reopened).equals(expected) && !reopened.widgets.get(1).showDisconnect,
                "Six-column restore lost the saved disconnect preference or widget geometry");
        require(preferences.getInt("dashboard_columns", -1) == 6,
                "Six-column preference did not survive restore");
        reopened.save();
        require(signature(new DashboardConfig(preferences, "six-columns")).equals(expected),
                "Six-column geometry or disconnect preference changed on save/recreation");
        java.util.List<DashboardLayout.Cell> restoredSix = reopened.layout(6, null);
        require(restoredSix.get(1).h == 1 && restoredSix.get(0).h == 3,
                "Restoring no longer keeps the one-row lock bar and compact saved overview height");
        require(preferences.getString(slotKey("six-columns", 1), "").equals(saved),
                "Restoring or saving the current layout rewrote its saved snapshot");
        require(reopened.saveSlot(2, "Five-column split", 5) && reopened.restoreSlot(2)
                        && preferences.getInt("dashboard_columns", -1) == 5,
                "Five-column preference could not round-trip");
        checks.add("Explicit five/six-column slots preserve every layout and Disconnect=false; default six columns place overview/lock bar beside tiles without changing the 1–4 stack");
    }

    /**
     * The classic Lock left the default, so swap it in place of the Lock bar (the two are exclusive)
     * to keep its height rule (four rows with Disconnect, three without) and the saved Disconnect=false
     * preference covered across a six-column slot round trip.
     */
    private void classicLockPersistence() throws Exception {
        // Restoring a six-column slot sets the shared column preference; put the earlier one back after.
        int previousColumns = preferences.getInt("dashboard_columns", -1);
        DashboardConfig config = new DashboardConfig(preferences, "classic-lock");
        config.layout(6, null);
        int at = 1;
        DashboardConfig.Widget bar = config.widgets.get(at), lock = new DashboardConfig.Widget("lock");
        require(bar.type.equals("lockbar"), "Unexpected default widget order");
        for (java.util.Map.Entry<Integer, DashboardLayout.Cell> entry : bar.layouts.entrySet()) {
            DashboardLayout.Cell from = entry.getValue();
            lock.layouts.put(entry.getKey(), new DashboardLayout.Cell(lock.id, from.x, from.y, from.w, lock.defaultHeight()));
        }
        config.widgets.set(at, lock);
        for (int columns = 1; columns <= DashboardConfig.MAX_COLUMNS; columns++)
            if (lock.layouts.containsKey(columns)) config.layout(columns, lock.id);
        require(lock.showDisconnect && lock.minimumHeight() == 4, "Lock with Disconnect must reserve room for its action button");
        lock.showDisconnect = false;
        require(lock.minimumHeight() == 3, "Hiding Disconnect must allow the compact three-row lock");
        lock.layouts.get(6).h = 3;
        String expected = signature(config);
        require(config.saveSlot(1, "Classic lock", 6), "Classic Lock snapshot could not be saved");
        config.reset();
        DashboardConfig reopened = new DashboardConfig(preferences, "classic-lock");
        require(reopened.restoreSlot(1), "Classic Lock slot could not be restored after recreation");
        require(signature(reopened).equals(expected) && reopened.widgets.get(at).type.equals("lock")
                        && !reopened.widgets.get(at).showDisconnect,
                "Classic Lock restore lost the saved disconnect preference or widget geometry");
        require(reopened.layout(6, null).get(at).h == 3,
                "Restoring Disconnect=false no longer permits its compact saved lock height");
        if (previousColumns < 0) preferences.edit().remove("dashboard_columns").commit();
        else preferences.edit().putInt("dashboard_columns", previousColumns).commit();
        checks.add("A classic Lock swapped in for the Lock bar keeps its four-row (Disconnect) / three-row minimum and restores Disconnect=false with its compact six-column height");
    }

    private void legacyFourColumnSlots() throws Exception {
        DashboardConfig config = fixture("legacy-four");
        // Lock bar did not exist before showDisconnect. Keep the fixture's classic
        // Lock, whose missing option defaults true, rather than using the new type.
        config.widgets.removeIf(widget -> widget.type.equals("lockbar"));
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
        int firstRow = Integer.MAX_VALUE;
        for (DashboardConfig.Widget widget : config.widgets) firstRow = Math.min(firstRow, widget.layouts.get(4).y);
        for (DashboardConfig.Widget widget : config.widgets) {
            DashboardLayout.Cell source = widget.layouts.get(4), converted = widget.layouts.get(6);
            DashboardLayout.Cell expectedCell = DashboardLayout.rescale(source, 4, 6);
            require(converted.x == expectedCell.x && converted.w == expectedCell.w
                            && converted.y == source.y - firstRow && converted.h == source.h,
                    "A noncolliding legacy layout did not derive proportional six-column geometry aligned to the top");
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
        java.util.List<DashboardLayout.Cell> narrow = config.layout(6, null, 280, 10, 2f);
        // The default lock bar is half of six columns; 2x text needs four of them at 280 dp.
        require(narrow.get(0).w > wide.get(0).w,
                "Narrow/large-text fixture did not actually broaden the lock bar");
        require(signature(config).equals(expected), "Temporary width constraints mutated stored cell geometry");
        require(preferences.getString("dashboard_v1_width-constraints", "").equals(stored)
                        && preferences.getString(slotKey("width-constraints", 0), "").equals(expectedSlot),
                "Temporary width constraints wrote to preferences or saved slots");
        for (int i = 0; i < narrow.size(); i++) {
            DashboardLayout.Cell cell = narrow.get(i);
            require(cell.w >= config.widgets.get(i).minimumWidth(6, 280, 10, 2f)
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

    private void editHistoryRestoresStructureOnly() throws Exception {
        DashboardConfig config = new DashboardConfig(preferences, "edit-history");
        DashboardEditHistory history = new DashboardEditHistory();
        Map<String, ?> diskBefore = new LinkedHashMap<>(preferences.getAll());
        DashboardConfig.Widget lock = config.widgets.get(0), scooter = config.widgets.get(1); // lock bar, battery overview
        String initialGeometry = geometry(config, 1, 6);
        DashboardEditHistory.Snapshot before = DashboardEditHistory.capture(config);
        // One completed action may move neighbors and resize the active widget.
        lock.layouts.get(6).x = 3; lock.layouts.get(6).w = 3;
        lock.layouts.get(6).y = 4; lock.layouts.get(6).h = 5;
        scooter.layouts.get(6).w = 2; // default overview is three columns wide
        String editedGeometry = geometry(config, 1, 6);
        require(history.record(before, config) && history.canUndo() && !history.canRedo(),
                "Movement/resize was not recorded as one history step");
        lock.title = "Keep this name"; lock.showDisconnect = false;
        scooter.keys.clear(); scooter.keys.add("odometer_km");
        require(history.undo(config) && geometry(config, 1, 6).equals(initialGeometry),
                "Undo did not restore movement/resizing in every saved column layout");
        require(config.widgets.get(0) == lock && config.widgets.get(1) == scooter
                        && lock.title.equals("Keep this name") && !lock.showDisconnect
                        && scooter.keys.equals(Arrays.asList("odometer_km")),
                "Geometry undo replaced widget identities or rolled back their current settings");
        require(history.redo(config) && geometry(config, 1, 6).equals(editedGeometry),
                "Redo failed to restore edited geometry");
        require(lock.title.equals("Keep this name") && !lock.showDisconnect,
                "Redo rolled back widget settings");

        DashboardConfig.Widget note = new DashboardConfig.Widget("note");
        note.layouts.put(6, new DashboardLayout.Cell(note.id, 0, 40, 3, 3));
        before = DashboardEditHistory.capture(config);
        config.widgets.add(note);
        require(history.record(before, config), "Adding a card was not recorded");
        note.note = "Retain edits after adding";
        require(history.undo(config) && !config.widgets.contains(note), "Undo add did not remove the added card");
        require(history.redo(config) && config.widgets.get(config.widgets.size() - 1) == note
                        && note.note.equals("Retain edits after adding"),
                "Redo add lost the same card or its current options");

        DashboardConfig.Widget copy = new DashboardConfig.Widget(note.type);
        copy.title = note.title; copy.note = note.note;
        copy.layouts.put(6, new DashboardLayout.Cell(copy.id, 3, 40, 3, 3));
        before = DashboardEditHistory.capture(config);
        config.widgets.add(copy);
        require(history.record(before, config), "Duplicating a card was not recorded");
        copy.note = "Independent copy options";
        require(history.undo(config) && config.widgets.contains(note) && !config.widgets.contains(copy),
                "Undo duplicate removed the original card");
        require(history.redo(config) && config.widgets.get(config.widgets.size() - 1) == copy
                        && copy.note.equals("Independent copy options")
                        && note.note.equals("Retain edits after adding"),
                "Redo duplicate lost identity or coupled original/copy options");

        String beforeDelete = geometry(config, 1, 6);
        before = DashboardEditHistory.capture(config);
        config.widgets.remove(lock);
        require(history.record(before, config), "Deleting a card was not recorded");
        // The retained card must keep its current object rather than a stale option snapshot.
        lock.title = "Latest retained settings";
        require(history.undo(config) && config.widgets.get(0) == lock
                        && geometry(config, 1, 6).equals(beforeDelete)
                        && lock.title.equals("Latest retained settings") && !lock.showDisconnect,
                "Undo delete failed to restore original order, all-column geometry or latest settings");
        require(history.redo(config) && !config.widgets.contains(lock), "Redo delete left the card visible");
        require(history.undo(config) && config.widgets.get(0) == lock,
                "Repeated delete restoration failed");
        require(preferences.getAll().equals(diskBefore), "In-memory editing history wrote preferences by itself");
        checks.add("Undo/redo restores move/resize/add/duplicate/delete and all-column placement while retaining widget identity and latest settings, without disk writes");
    }

    private void editHistoryNoOpsAndBounds() throws Exception {
        DashboardConfig config = new DashboardConfig(preferences, "edit-history-bounds");
        DashboardEditHistory history = new DashboardEditHistory(2);
        DashboardConfig.Widget lock = config.widgets.get(0), scooter = config.widgets.get(1); // lock bar, battery overview
        require(!history.canUndo() && !history.canRedo() && !history.undo(config) && !history.redo(config),
                "Empty history performed an action");
        DashboardEditHistory.Snapshot before = DashboardEditHistory.capture(config);
        // Fresh Cell instances with identical geometry are still a no-op.
        lock.layouts.put(6, lock.layouts.get(6).copy());
        require(!history.record(before, config) && !history.canUndo(), "Equal geometry consumed history");
        before = DashboardEditHistory.capture(config);
        config.widgets.set(0, scooter); config.widgets.set(1, lock);
        require(history.record(before, config) && history.undo(config)
                        && config.widgets.get(0) == lock && config.widgets.get(1) == scooter,
                "Membership order changes were not reversible");
        before = DashboardEditHistory.capture(config);
        lock.title = "Settings only";
        lock.metric = "power_w"; lock.windowMs = 120000; lock.color = 2;
        lock.minimum = -10; lock.maximum = 2000; lock.showDisconnect = false;
        require(!history.record(before, config) && history.canRedo(),
                "Settings-only edit consumed history or incorrectly cleared redo");
        require(history.redo(config) && config.widgets.get(0) == scooter
                        && lock.title.equals("Settings only") && lock.minimum == -10
                        && lock.maximum == 2000 && lock.color == 2 && !lock.showDisconnect,
                "Redo after settings changes reverted options");
        require(history.undo(config), "Could not prepare redo invalidation fixture");
        before = DashboardEditHistory.capture(config);
        lock.layouts.get(6).y = 9;
        require(history.record(before, config) && !history.canRedo(), "A new layout action did not discard the old redo branch");
        history.clear();
        require(!history.canUndo() && !history.canRedo(), "Ending an editing session retained history");
        for (int row = 1; row <= 4; row++) {
            before = DashboardEditHistory.capture(config);
            lock.layouts.get(6).y = row;
            require(history.record(before, config), "Could not fill bounded history");
        }
        require(history.undo(config) && lock.layouts.get(6).y == 3
                        && history.undo(config) && lock.layouts.get(6).y == 2
                        && !history.undo(config),
                "Bounded history did not retain exactly the newest completed actions");
        require(history.redo(config) && lock.layouts.get(6).y == 3
                        && history.redo(config) && lock.layouts.get(6).y == 4
                        && !history.redo(config),
                "Bounded history did not redo the retained actions in order");
        DashboardConfig other = new DashboardConfig(preferences, "edit-history-other");
        String otherBefore = signature(other);
        boolean rejected = false;
        try { history.undo(other); } catch (IllegalArgumentException expected) { rejected = true; }
        require(rejected && signature(other).equals(otherBefore), "A history from another dashboard changed this profile");
        rejected = false;
        try { history.record(DashboardEditHistory.capture(other), config); }
        catch (IllegalArgumentException expected) { rejected = true; }
        require(rejected, "A foreign snapshot was accepted for this dashboard");
        history.clear();
        before = DashboardEditHistory.capture(other);
        other.widgets.remove(0);
        require(history.record(before, other) && history.undo(other)
                        && signature(other).equals(otherBefore),
                "Cleared history could not begin a fresh independent editing session");
        checks.add("Layout no-ops and settings-only edits preserve redo; new edits branch correctly; bounded history, clear and dashboard isolation work");
    }

    private void quickAccessPersistence() throws Exception {
        DashboardConfig config = new DashboardConfig(preferences, "quick-actions");
        config.widgets.clear();
        DashboardConfig.Widget quick = new DashboardConfig.Widget("quick");
        config.widgets.add(quick);
        java.util.List<String> ids = Arrays.asList("feature:traction_control", "feature:energy_recovery",
                "feature:start_speed", "section:Battery", "section:Ride", "lights", "stats", "rides");
        for (int count = 2; count <= 8; count++) {
            quick.quickActions.clear(); quick.quickActions.addAll(ids.subList(0, count));
            config.save();
            DashboardConfig reopened = new DashboardConfig(preferences, "quick-actions");
            require(reopened.widgets.get(0).quickActions.equals(ids.subList(0, count)), "Quick actions failed round trip at count " + count);
            require(reopened.saveSlot(0, "Quick fixture", 0), "Quick actions slot save failed");
            reopened.widgets.get(0).quickActions.clear(); reopened.widgets.get(0).quickActions.addAll(Arrays.asList("stats", "rides"));
            require(reopened.restoreSlot(0) && reopened.widgets.get(0).quickActions.equals(ids.subList(0, count)), "Quick action order/options lost on slot restore");
        }
        JSONObject legacy = new JSONObject(preferences.getString("dashboard_v1_quick-actions", "").substring(1,
                preferences.getString("dashboard_v1_quick-actions", "").length()-1));
        legacy.remove("quickActions");
        require(preferences.edit().putString("dashboard_v1_quick-actions", new JSONArray().put(legacy).toString()).commit(), "Legacy fixture save failed");
        require(new DashboardConfig(preferences, "quick-actions").widgets.get(0).quickActions.equals(
                local.navee.offlinelock.QuickAccessActions.defaultIds()), "Legacy quick widget lost familiar defaults");
        legacy.put("quickActions", new JSONArray().put("unknown").put("stats").put("stats").put("feature:not_a_feature"));
        require(preferences.edit().putString("dashboard_v1_quick-actions", new JSONArray().put(legacy).toString()).commit(), "Malformed fixture save failed");
        java.util.List<String> normalized = new DashboardConfig(preferences, "quick-actions").widgets.get(0).quickActions;
        require(normalized.size() >= 2 && normalized.size() <= 8 && new java.util.HashSet<>(normalized).size() == normalized.size(), "Malformed quick actions failed safe count/deduplication");
        for (String id : normalized) require(local.navee.offlinelock.QuickAccessActions.find(id) != null, "Unknown action survived normalization");
        checks.add("Quick action selections preserve order and 2–8 counts across persistence and slots; legacy defaults and malformed IDs normalize safely");
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
            widget.absoluteAxis = i % 2 == 0;
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
                    .append(w.showDisconnect).append('|').append(w.absoluteAxis).append('|').append(w.quickActions).append('|');
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
