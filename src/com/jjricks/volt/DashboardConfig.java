package com.jjricks.volt;

import android.content.SharedPreferences;

import org.json.*;

import java.util.*;

/** Local, per-scooter widgets with independent compact/expanded layouts at each column count. */
public final class DashboardConfig {
    public static final int MAX_COLUMNS = DashboardLayout.MAX_COLUMNS;
    public static final int SLOT_COUNT = 3;
    public static final int SLOT_NAME_LIMIT = 40;
    private static final int SLOT_VERSION = 1;
    private static final String COLUMN_KEY = "dashboard_columns";
    public static final int MAX_TRANSFER_BYTES = 1024 * 1024;
    private static final String TRANSFER_FORMAT = "volt-dashboard-layouts";

    public static final String[] TYPES = {
        "lock",
        "scooter",
        "battery",
        "metric",
        "gauge",
        "live",
        "history",
        "readings",
        "trip",
        "health",
        "capacity",
        "charging",
        "quick",
        "ride",
        "note",
        "lockbar",
        "overview",
        "tiles",
        "shortcuts"
    };
    /** The capacity widget's default title before it became "Usable ride energy". */
    static final String LEGACY_CAPACITY_TITLE = "Capacity experiment";
    public static final String[] NAMES = {
        "Lock & connection",
        "Your scooter",
        "Battery overview",
        "Single reading",
        "Gauge",
        "Live trend",
        "History chart",
        "Readings",
        "Trip overview",
        "Reported health",
        "Usable ride energy",
        "Charging",
        "Shortcuts",
        "Ride recorder",
        "Note",
        "Lock bar",
        "Scooter overview",
        "Reading tiles",
        "Setting tiles"
    };

    public static final class Widget {
        public final String id;
        public String type, title, metric, note = "";
        public final ArrayList<String> keys = new ArrayList<>();
        public final ArrayList<String> quickActions = new ArrayList<>(QuickAccessActions.defaultIds());
        /** Legacy placement is the compact bank, retaining its original column keys. */
        public final Map<Integer, DashboardLayout.Cell> layouts = new HashMap<>();
        public final Map<Integer, DashboardLayout.Cell> expandedLayouts = new HashMap<>();
        private boolean expandedLayoutsInitialized;
        public int color;
        public long windowMs;
        public double minimum = 0, maximum = 100;
        /** Zero selects the model's documented rating, where known. */
        public double advertisedWh = 0;
        public boolean showDisconnect = true;
        public boolean absoluteAxis;

        public Widget(String type) {
            this(UUID.randomUUID().toString(), type);
        }

        private Widget(String id, String type) {
            this.id = id;
            this.type = type;
            int index = Arrays.asList(TYPES).indexOf(type);
            title = NAMES[Math.max(0, index)];
            metric =
                    type.equals("health")
                            ? "battery_health_percent"
                            : type.equals("gauge") ? "battery_percent" : "power_w";
            windowMs =
                    type.equals("capacity")
                            ? 90 * 86400000L
                            : type.equals("live")
                                    ? 15 * 60000L
                                    : type.equals("health") ? 30 * 86400000L : 86400000L;
            keys.addAll(
                    type.equals("scooter") || type.equals("overview")
                            ? Arrays.asList("odometer_km", "driving_mode")
                            : type.equals("tiles")
                                    ? Arrays.asList("power_w", "temperature_c", "battery_health_percent")
                                    : Arrays.asList(
                                            "remaining_range_km",
                                            "power_w",
                                            "battery_health_percent",
                                            "temperature_c"));
            if (type.equals("shortcuts")) {
                quickActions.clear();
                quickActions.addAll(ModelTiles.DEFAULT);
            }
            // The compact bar matches the Home concept: Disconnect stays in connection details.
            if (type.equals("lockbar")) showDisconnect = false;
        }

        /**
         * Copies every legacy width before either bank can be edited. Initializing only
         * on the first expanded visit would copy edits already made on the cover screen.
         * Freshly added widgets use the same guard as loaded/default definitions.
         */
        public Map<Integer, DashboardLayout.Cell> layouts(boolean expanded) {
            if (!expandedLayoutsInitialized) {
                for (Map.Entry<Integer, DashboardLayout.Cell> entry : layouts.entrySet())
                    if (!expandedLayouts.containsKey(entry.getKey()))
                        expandedLayouts.put(entry.getKey(), entry.getValue().copy());
                expandedLayoutsInitialized = true;
            }
            return expanded ? expandedLayouts : layouts;
        }

        public int defaultHeight() {
            switch (type) {
                case "battery":
                case "capacity":
                case "charging":
                    return 6;
                case "scooter":
                    return 4;
                case "live":
                case "history":
                    return 5;
                case "health":
                    return 7;
                case "readings":
                case "quick":
                case "trip":
                    return 5;
                case "lock":
                case "ride":
                case "overview":
                    return 4;
                case "tiles":
                    return 2;
                case "lockbar":
                case "shortcuts":
                    return 1;
                default:
                    return 3;
            }
        }

        public int minimumHeight() {
            if (type.equals("scooter")) return 3;
            if (type.equals("lock")) return showDisconnect ? 4 : 3;
            if (type.equals("ride") || type.equals("lockbar")
                    || type.equals("tiles") || type.equals("shortcuts")) return 1;
            if (type.equals("gauge") || type.equals("overview")) return 3;
            return type.equals("live") || type.equals("history")
                    ? 4
                    : type.equals("health")
                            ? 5
                            : type.equals("battery")
                                            || type.equals("capacity")
                                            || type.equals("charging")
                                    ? 4
                                    : 2;
        }

        public int minimumHeight(float widthDp) {
            // Narrow rails keep a compact editor, with secondary actions in Options.
            if (type.equals("quick") && widthDp < 144) return 3;
            // A one-row recorder reserves an independent resize target beside its editor bar.
            if (type.equals("ride") && widthDp < 192) return 3;
            return minimumHeight();
        }

        public int defaultWidth(int columns) {
            columns = Math.max(1, Math.min(MAX_COLUMNS, columns));
            if (type.equals("metric") || type.equals("gauge"))
                return columns > 4 ? (columns + 2) / 3 : 1;
            if (type.equals("lock") || type.equals("scooter") || type.equals("capacity")
                    || type.equals("lockbar") || type.equals("overview")
                    || type.equals("tiles") || type.equals("shortcuts"))
                return columns;
            if (columns == 3 && (type.equals("quick") || type.equals("ride"))) return 1;
            return columns > 4 ? (columns + 1) / 2 : Math.min(columns, 2);
        }

        public int minimumWidth(int columns, float availableWidthDp, float gapDp, float textScale) {
            return DashboardLayout.minimumSpan(type, columns, availableWidthDp, gapDp, textScale);
        }
    }

    /** Setting tiles hold one to four shortcuts; Quick access keeps two to eight. */
    public static int minimumShortcuts(String type) {
        return "shortcuts".equals(type) ? 1 : QuickAccessActions.MIN_ITEMS;
    }

    public static int maximumShortcuts(String type) {
        return "shortcuts".equals(type) ? 4 : QuickAccessActions.MAX_ITEMS;
    }

    private final SharedPreferences preferences;
    private final String storageKey;
    private final String slotKeyPrefix;
    private final String emptyExampleKey;
    private final boolean expanded;
    public final ArrayList<Widget> widgets = new ArrayList<>();

    public DashboardConfig(SharedPreferences preferences, String profileId) {
        this(preferences, profileId, false);
    }

    /** The window class is fixed for this screen/configuration, independently of columns. */
    public DashboardConfig(SharedPreferences preferences, String profileId, boolean expanded) {
        this(preferences, profileId, expanded, AllWidgetsDashboard::create);
    }

    DashboardConfig(SharedPreferences preferences, String profileId,
            java.util.function.Supplier<ArrayList<Widget>> exampleFactory) {
        this(preferences, profileId, false, exampleFactory);
    }

    private DashboardConfig(SharedPreferences preferences, String profileId, boolean expanded,
            java.util.function.Supplier<ArrayList<Widget>> exampleFactory) {
        this.preferences = preferences;
        this.expanded = expanded;
        storageKey = "dashboard_v1_" + profileId;
        slotKeyPrefix = "dashboard_slot_v1_" + profileId + "_";
        emptyExampleKey = "dashboard_empty_example_v1_" + profileId;
        String saved = preferences.getString(storageKey, null);
        boolean loaded = false;
        if (saved != null)
            try {
                widgets.addAll(readWidgets(new JSONArray(saved)));
                loaded = true;
            } catch (Exception ignored) {
                widgets.clear();
            }
        if (!loaded) {
            // A new profile must not change another profile's global column choice.
            // Missing columns on an older installation mean Adaptive, so only a genuinely
            // first dashboard gets the release's five-column preference automatically.
            boolean firstDashboard = !preferences.contains(COLUMN_KEY);
            if (firstDashboard)
                for (String key : preferences.getAll().keySet())
                    if (key.startsWith("dashboard_v1_")) { firstDashboard = false; break; }
            loadDefaults(firstDashboard);
        }
        for (Widget widget : widgets) widget.layouts(false);
        seedExampleSlot(exampleFactory);
    }

    /** A missing example is safe to add; an occupied slot belongs to its owner, even if unreadable. */
    private void seedExampleSlot(java.util.function.Supplier<ArrayList<Widget>> exampleFactory) {
        try {
            if (!slotExists(2) && !preferences.getBoolean(emptyExampleKey, false))
                saveSlot(2, "All widgets", AllWidgetsDashboard.COLUMNS, exampleFactory.get());
        } catch (RuntimeException unavailable) {
            // Optional content must never invalidate a loaded dashboard or prevent a
            // fresh one from opening. A missing example can be retried next time.
        }
    }

    private static ArrayList<Widget> readWidgets(JSONArray a) throws JSONException {
        ArrayList<Widget> result = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (int i = 0; i < Math.min(32, a.length()); i++) {
            JSONObject o = a.getJSONObject(i);
            String type = o.getString("type"), id = o.getString("id");
            if (!Arrays.asList(TYPES).contains(type) || id.length() > 80 || !seen.add(id))
                continue;
            Widget w = new Widget(id, type);
            w.title = o.optString("title", w.title);
            // The widget was renamed; an untouched old default title follows it, custom titles stay.
            if (type.equals("capacity") && LEGACY_CAPACITY_TITLE.equals(w.title)) w.title = NAMES[Arrays.asList(TYPES).indexOf("capacity")];
            w.metric = o.optString("metric", w.metric);
            w.note = o.optString("note", "");
            w.color = Math.max(0, Math.min(3, o.optInt("color", 0)));
            w.windowMs =
                    Math.max(
                            60000L,
                            Math.min(730 * 86400000L, o.optLong("window", w.windowMs)));
            w.minimum = o.optDouble("min", 0);
            w.maximum = o.optDouble("max", 100);
            w.advertisedWh = o.optDouble("advertisedWh", 0);
            w.showDisconnect = o.optBoolean("showDisconnect", w.showDisconnect);
            w.absoluteAxis = o.optBoolean("absoluteAxis", false);
            JSONArray shortcuts = o.optJSONArray("quickActions");
            if (shortcuts != null) {
                ArrayList<String> actionIds = new ArrayList<>();
                for (int k = 0; k < Math.min(64, shortcuts.length()); k++)
                    if (shortcuts.opt(k) instanceof String) actionIds.add(shortcuts.getString(k));
                w.quickActions.clear();
                w.quickActions.addAll(QuickAccessActions.normalize(actionIds,
                        minimumShortcuts(type), maximumShortcuts(type)));
            }
            if (!Double.isFinite(w.advertisedWh)
                    || w.advertisedWh < 0
                    || w.advertisedWh > 10000) w.advertisedWh = 0;
            if (!Double.isFinite(w.minimum)
                    || !Double.isFinite(w.maximum)
                    || w.maximum <= w.minimum) {
                w.minimum = 0;
                w.maximum = 100;
            }
            JSONArray keys = o.optJSONArray("keys");
            if (keys != null) {
                w.keys.clear();
                for (int k = 0; k < Math.min(12, keys.length()); k++)
                    w.keys.add(keys.getString(k));
            }
            // 0.6.0 stored unused generic keys on the photo card. Give those
            // existing cards the new lifetime defaults without changing their geometry.
            if (type.equals("scooter")
                    && !o.optBoolean("scooterStats", false)
                    && w.keys.equals(
                            Arrays.asList(
                                    "remaining_range_km",
                                    "power_w",
                                    "battery_health_percent",
                                    "temperature_c"))) {
                w.keys.clear();
                w.keys.addAll(
                        Arrays.asList(
                                "odometer_km", "driving_mode"));
            }
            // Retire the old four-stat default once; do not overwrite a
            // subsequently customized reading list or any saved placement.
            if (type.equals("scooter")
                    && !o.optBoolean("glanceableScooterStats", false)
                    && w.keys.equals(Arrays.asList("odometer_km", "driving_mode",
                            "charge_cycles", "capacity_throughput_ah"))) {
                w.keys.clear();
                w.keys.addAll(Arrays.asList("odometer_km", "driving_mode"));
            }
            readLayouts(o.optJSONObject("layouts"), w.layouts, id);
            if (o.has("expandedLayouts")) {
                readLayouts(o.getJSONObject("expandedLayouts"), w.expandedLayouts, id);
                // An explicitly empty/sparse expanded bank belongs to the owner too.
                w.expandedLayoutsInitialized = true;
            }
            w.layouts(false);
            result.add(w);
        }
        return result;
    }

    private static void readLayouts(JSONObject layouts, Map<Integer, DashboardLayout.Cell> target,
            String id) throws JSONException {
        if (layouts == null) return;
        for (int columns = 1; columns <= MAX_COLUMNS; columns++) {
            JSONArray rectangle = layouts.optJSONArray(Integer.toString(columns));
            if (rectangle != null && rectangle.length() == 4)
                target.put(columns, new DashboardLayout.Cell(id, rectangle.getInt(0),
                        rectangle.getInt(1), rectangle.getInt(2), rectangle.getInt(3)));
        }
    }

    /** The current screen's placements; settings and widget membership remain shared. */
    public Map<Integer, DashboardLayout.Cell> widgetLayouts(Widget widget) {
        return widget.layouts(expanded);
    }

    /** Explicit Restore defaults resets the column choice, but never any saved slots. */
    public void reset() {
        loadDefaults(true);
    }

    private void loadDefaults(boolean resetColumns) {
        widgets.clear();
        widgets.addAll(DefaultDashboard.create());
        save(resetColumns);
    }

    public List<DashboardLayout.Cell> layout(int columns, String priority) {
        columns = Math.max(1, Math.min(MAX_COLUMNS, columns));
        ArrayList<DashboardLayout.Cell> cells = new ArrayList<>();
        for (Widget w : widgets) {
            Map<Integer, DashboardLayout.Cell> layouts = widgetLayouts(w);
            DashboardLayout.Cell cell = layouts.get(columns);
            // New finer grids start from this window class's existing layout.
            // Materializing them must not rewrite any previously saved width.
            if (cell == null && columns > 4) {
                int sourceColumns = layouts.containsKey(4) ? 4 : columns - 1;
                while (sourceColumns > 0 && !layouts.containsKey(sourceColumns)) sourceColumns--;
                if (sourceColumns > 0)
                    cell = DashboardLayout.rescale(layouts.get(sourceColumns), sourceColumns, columns);
            }
            if (cell == null) {
                int width = w.defaultWidth(columns);
                cell = new DashboardLayout.Cell(w.id, 0, 0, width, w.defaultHeight());
            }
            cell = cell.copy();
            cell.h = Math.max(w.minimumHeight(), cell.h);
            cells.add(cell);
        }
        List<DashboardLayout.Cell> settled = DashboardLayout.settle(cells, columns, priority);
        if (priority == null) settled = DashboardLayout.withoutLeadingRows(settled);
        for (DashboardLayout.Cell cell : settled)
            for (Widget w : widgets) if (w.id.equals(cell.id)) widgetLayouts(w).put(columns, cell);
        return settled;
    }

    /**
     * Adapts measured cards to usable widths without overwriting stored placement.
     * Temporary window/font changes can therefore expand cards and return to the
     * original layout later. Gesture previews should start from these displayed
     * rectangles and persist them only when the owner commits an actual edit.
     */
    public List<DashboardLayout.Cell> layout(int columns, String priority,
            float availableWidthDp, float gapDp, float textScale) {
        columns = Math.max(1, Math.min(MAX_COLUMNS, columns));
        List<DashboardLayout.Cell> saved = layout(columns, priority);
        ArrayList<DashboardLayout.Cell> visible = new ArrayList<>();
        for (int i = 0; i < widgets.size(); i++) {
            DashboardLayout.Cell cell = saved.get(i).copy();
            cell.w = Math.max(cell.w,
                    widgets.get(i).minimumWidth(columns, availableWidthDp, gapDp, textScale));
            cell.h = Math.max(cell.h, widgets.get(i).minimumHeight(
                    cell.w * (availableWidthDp + gapDp) / columns - gapDp));
            visible.add(cell);
        }
        List<DashboardLayout.Cell> result = DashboardLayout.settle(visible, columns, priority);
        return priority == null ? DashboardLayout.withoutLeadingRows(result) : result;
    }

    /**
     * Setting tiles still on the release pair become the model's own (ModelTiles); tiles the owner chose are never touched.
     * True when something changed and the dashboard needs saving.
     */
    public boolean adaptSettingTiles(java.util.List<String> modelTiles) {
        boolean changed = false;
        if (modelTiles == null || modelTiles.equals(ModelTiles.DEFAULT)) return false;
        for (Widget widget : widgets)
            if (widget.type.equals("shortcuts") && widget.quickActions.equals(ModelTiles.DEFAULT)) {
                widget.quickActions.clear(); widget.quickActions.addAll(modelTiles); changed = true;
            }
        return changed;
    }

    public void save() {
        save(false);
    }

    /** The stored column choice, or -1 when none is stored. */
    public int columnPreference() {
        return preferences.contains(COLUMN_KEY) ? preferences.getInt(COLUMN_KEY, 0) : -1;
    }

    /** Puts back a value from {@link #columnPreference()}; -1 removes the stored choice. */
    public void restoreColumnPreference(int columns) {
        if (columns < 0) preferences.edit().remove(COLUMN_KEY).apply();
        else preferences.edit().putInt(COLUMN_KEY, columns).apply();
    }

    /** The widgets in their storage form, for a short-lived copy such as a pending Undo. */
    public String widgetsJson() {
        try {
            return writeWidgets(widgets).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The widgets as last saved, or null when none are saved. */
    public String storedWidgetsJson() {
        return preferences.getString(storageKey, null);
    }

    /** Reads widgets written by {@link #widgetsJson()}; null when unreadable. */
    public static ArrayList<Widget> parseWidgets(String json) {
        try {
            return json == null ? null : readWidgets(new JSONArray(json));
        } catch (JSONException | RuntimeException invalid) {
            return null;
        }
    }

    private void save(boolean resetColumns) {
        try {
            SharedPreferences.Editor edit = preferences.edit()
                    .putString(storageKey, writeWidgets(widgets).toString());
            if (resetColumns) edit.putInt(COLUMN_KEY, DefaultDashboard.COLUMNS);
            edit.apply();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JSONArray writeWidgets(List<Widget> widgets) throws JSONException {
        JSONArray a = new JSONArray();
        for (Widget w : widgets) {
            JSONObject o = new JSONObject();
            o.put("id", w.id);
            o.put("type", w.type);
            if (w.type.equals("scooter")) {
                o.put("scooterStats", true);
                o.put("glanceableScooterStats", true);
            }
            o.put("title", w.title);
            o.put("metric", w.metric);
            o.put("note", w.note);
            o.put("color", w.color);
            o.put("window", w.windowMs);
            o.put("min", w.minimum);
            o.put("max", w.maximum);
            o.put("advertisedWh", w.advertisedWh);
            o.put("showDisconnect", w.showDisconnect);
            o.put("absoluteAxis", w.absoluteAxis);
            o.put("quickActions", new JSONArray(QuickAccessActions.normalize(w.quickActions,
                    minimumShortcuts(w.type), maximumShortcuts(w.type))));
            o.put("keys", new JSONArray(w.keys));
            o.put("layouts", writeLayouts(w.layouts(false)));
            o.put("expandedLayouts", writeLayouts(w.layouts(true)));
            a.put(o);
        }
        return a;
    }

    private static JSONObject writeLayouts(Map<Integer, DashboardLayout.Cell> layouts)
            throws JSONException {
        JSONObject result = new JSONObject();
        for (Map.Entry<Integer, DashboardLayout.Cell> entry : layouts.entrySet()) {
            DashboardLayout.Cell cell = entry.getValue();
            result.put(entry.getKey().toString(),
                    new JSONArray(new int[] {cell.x, cell.y, cell.w, cell.h}));
        }
        return result;
    }

    /** Read-only metadata. Widget data stays private so later edits cannot mutate a slot. */
    public static final class SavedSlot {
        public final String name;
        public final long savedAtMs;
        public final int widgetCount, columns;

        private SavedSlot(JSONObject snapshot) throws JSONException {
            name = snapshot.getString("name");
            savedAtMs = snapshot.getLong("savedAt");
            widgetCount = snapshot.getJSONArray("widgets").length();
            columns = snapshot.getInt("columns");
        }
    }

    /** An occupied but unreadable slot may still be overwritten explicitly by its owner. */
    public boolean slotExists(int index) {
        return validSlot(index) && preferences.contains(slotKeyPrefix + index);
    }

    public SavedSlot slot(int index) {
        try {
            JSONObject snapshot = readSlot(index);
            return snapshot == null ? null : new SavedSlot(snapshot);
        } catch (JSONException | RuntimeException invalid) {
            return null;
        }
    }

    /** Captures every stored width together, without materializing missing width layouts. */
    public boolean saveSlot(int index, String name, int columns) {
        return saveSlot(index, name, columns, widgets);
    }

    /** Explicit developer action: replace only slot 3 with the shipped example. */
    public boolean forceExampleSlot() {
        return saveSlot(2, "All widgets", AllWidgetsDashboard.COLUMNS, AllWidgetsDashboard.create());
    }

    /** Consent preview. It contains no live readings, owner identity or scooter identifier. */
    public static final class LayoutTransfer {
        public final int columns, widgetCount, savedSlotCount;
        private LayoutTransfer(JSONObject data) throws JSONException {
            columns = data.getInt("columns");
            widgetCount = data.getJSONArray("widgets").length();
            int count = 0;
            JSONArray slots = data.getJSONArray("slots");
            for (int i = 0; i < SLOT_COUNT; i++) if (!slots.isNull(i)) count++;
            savedSlotCount = count;
        }
    }

    /** A portable, explicitly scoped export for the current scooter's dashboard and slots. */
    public String exportLayouts() throws JSONException {
        JSONObject data = new JSONObject();
        data.put("format", TRANSFER_FORMAT);
        data.put("version", 1);
        data.put("columns", preferences.getInt(COLUMN_KEY, 0));
        data.put("widgets", writeWidgets(widgets));
        JSONArray slots = new JSONArray();
        for (int i = 0; i < SLOT_COUNT; i++) {
            JSONObject slot = readSlot(i);
            slots.put(slot == null ? JSONObject.NULL : slot);
        }
        data.put("slots", slots);
        String result = data.toString(2);
        // Export and import share one schema. Never silently omit a damaged saved slot.
        parseTransfer(result);
        return result;
    }

    public static LayoutTransfer inspectLayouts(String json) throws JSONException {
        return new LayoutTransfer(parseTransfer(json));
    }

    /** A validated layout file as stored values: {dashboard, columns, slot 1, slot 2, slot 3}
     * (null for an empty slot), exactly as {@link #importLayouts} would save them. */
    static String[] transferValues(String json) throws JSONException {
        JSONObject data = parseTransfer(json);
        readWidgets(data.getJSONArray("widgets"));
        String[] result = {data.getJSONArray("widgets").toString(), Integer.toString(data.getInt("columns")), null, null, null};
        JSONArray slots = data.getJSONArray("slots");
        for (int i = 0; i < SLOT_COUNT; i++) if (!slots.isNull(i)) result[2 + i] = slots.getJSONObject(i).toString();
        return result;
    }

    /** Replace all four dashboard definitions together, only after complete validation. */
    public boolean importLayouts(String json) {
        try {
            JSONObject data = parseTransfer(json);
            JSONArray definitions = data.getJSONArray("widgets");
            ArrayList<Widget> restored = readWidgets(definitions);
            JSONArray slots = data.getJSONArray("slots");
            Map<String, ?> previous = preferences.getAll();
            SharedPreferences.Editor edit = preferences.edit()
                    .putString(storageKey, definitions.toString())
                    .putInt(COLUMN_KEY, data.getInt("columns"))
                    // An imported empty third slot is intentional, unlike an upgrade that
                    // has never received its example. Keep it empty after reopening.
                    .putBoolean(emptyExampleKey, slots.isNull(2));
            String[] changedKeys = new String[SLOT_COUNT + 3];
            changedKeys[0] = storageKey;
            changedKeys[1] = COLUMN_KEY;
            changedKeys[2] = emptyExampleKey;
            for (int i = 0; i < SLOT_COUNT; i++) {
                String key = slotKeyPrefix + i;
                changedKeys[i + 3] = key;
                if (slots.isNull(i)) edit.remove(key);
                else edit.putString(key, slots.getJSONObject(i).toString());
            }
            if (!edit.commit()) {
                restorePreferences(previous, changedKeys);
                return false;
            }
            widgets.clear();
            widgets.addAll(restored);
            return true;
        } catch (JSONException | RuntimeException invalid) {
            return false;
        }
    }

    private static JSONObject parseTransfer(String json) throws JSONException {
        require(json != null && json.length() <= MAX_TRANSFER_BYTES, "Layout file is too large");
        require(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= MAX_TRANSFER_BYTES,
                "Layout file is too large");
        // Bound parser recursion before Android's JSON parser sees untrusted nested input.
        int depth = 0;
        boolean quoted = false, escape = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (quoted) {
                require(c >= 0x20, "Invalid layout text");
                if (escape) escape = false;
                else if (c == '\\') escape = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '[' || c == '{') require(++depth <= 12, "Layout file is too deeply nested");
            else if (c == ']' || c == '}') require(--depth >= 0, "Invalid layout JSON");
            else require(" \t\r\n:,0123456789.-+eEtruefalsn".indexOf(c) >= 0, "Invalid layout JSON");
        }
        require(depth == 0 && !quoted, "Invalid layout JSON");
        JSONTokener parser = new JSONTokener(json);
        Object parsed = parser.nextValue();
        require(parsed instanceof JSONObject && parser.nextClean() == 0, "Invalid layout JSON");
        JSONObject data = (JSONObject) parsed;
        exactKeys(data, "format", "version", "columns", "widgets", "slots");
        require(TRANSFER_FORMAT.equals(string(data.get("format"))), "Not a dashboard layout file");
        require(integer(data.get("version")) == 1, "Unknown layout file version");
        long columns = integer(data.get("columns"));
        require(columns >= 0 && columns <= MAX_COLUMNS, "Invalid column choice");
        validateTransferWidgets(data.getJSONArray("widgets"));
        JSONArray slots = data.getJSONArray("slots");
        require(slots.length() == SLOT_COUNT, "Expected three saved slots");
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (slots.isNull(i)) continue;
            JSONObject slot = slots.getJSONObject(i);
            exactKeys(slot, "version", "name", "savedAt", "columns", "widgets");
            validateSlot(slot);
            validateTransferWidgets(slot.getJSONArray("widgets"));
        }
        return data;
    }

    private static void validateTransferWidgets(JSONArray widgets) throws JSONException {
        // Reuse local snapshot validation, then apply portable-file bounds and an allowlist.
        JSONObject snapshot = new JSONObject();
        snapshot.put("version", SLOT_VERSION);
        snapshot.put("name", "Layout");
        snapshot.put("savedAt", 1L);
        snapshot.put("columns", 0);
        snapshot.put("widgets", widgets);
        validateSlot(snapshot);
        HashSet<String> metricKeys = new HashSet<>();
        for (Telemetry.Metric metric : new Telemetry().metrics()) metricKeys.add(metric.key);
        for (int i = 0; i < widgets.length(); i++) {
            JSONObject widget = widgets.getJSONObject(i);
            allowedKeys(widget, "id", "type", "title", "metric", "note", "color", "window",
                    "min", "max", "advertisedWh", "showDisconnect", "absoluteAxis", "quickActions",
                    "keys", "layouts", "expandedLayouts", "scooterStats", "glanceableScooterStats");
            require(string(widget.get("title")).length() <= 80, "Widget title is too long");
            require(string(widget.get("note")).length() <= 1200, "Widget note is too long");
            require(metricKeys.contains(string(widget.get("metric"))), "Unknown reading key");
            require(Double.isFinite(number(widget.get("max")) - number(widget.get("min"))),
                    "Gauge span is too large");
            JSONArray keys = widget.getJSONArray("keys");
            HashSet<String> used = new HashSet<>();
            for (int k = 0; k < keys.length(); k++) {
                String key = string(keys.get(k));
                require(metricKeys.contains(key) && used.add(key), "Invalid reading key");
            }
            for (String flag : new String[] {"scooterStats", "glanceableScooterStats"})
                if (widget.has(flag)) require(widget.get(flag) instanceof Boolean, "Invalid widget flag");
        }
    }

    private static void exactKeys(JSONObject object, String... names) throws JSONException {
        require(object.length() == names.length, "Unexpected layout fields");
        allowedKeys(object, names);
    }

    private static void allowedKeys(JSONObject object, String... names) throws JSONException {
        Set<String> allowed = new HashSet<>(Arrays.asList(names));
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) require(allowed.contains(keys.next()), "Unexpected layout fields");
    }

    private boolean saveSlot(int index, String name, int columns, List<Widget> definitions) {
        if (!validSlot(index) || columns < 0 || columns > MAX_COLUMNS) return false;
        try {
            JSONObject snapshot = new JSONObject();
            snapshot.put("version", SLOT_VERSION);
            snapshot.put("name", slotName(name, index));
            snapshot.put("savedAt", System.currentTimeMillis());
            snapshot.put("columns", columns);
            snapshot.put("widgets", writeWidgets(definitions));
            validateSlot(snapshot);
            String key = slotKeyPrefix + index;
            Map<String, ?> previous = preferences.getAll();
            if (preferences.edit().putString(key, snapshot.toString()).commit()) return true;
            // SharedPreferences changes its memory map even when the disk commit fails.
            // Put the original value back and report failure instead of claiming a saved slot.
            restorePreferences(previous, key);
        } catch (JSONException | RuntimeException invalid) {
            return false;
        }
        return false;
    }

    /** Validates first, then persists dashboard and column preference in one transaction. */
    public boolean restoreSlot(int index) {
        try {
            JSONObject snapshot = readSlot(index);
            if (snapshot == null) return false;
            JSONArray savedWidgets = snapshot.getJSONArray("widgets");
            ArrayList<Widget> restored = readWidgets(savedWidgets);
            Map<String, ?> previous = preferences.getAll();
            if (!preferences.edit()
                    .putString(storageKey, savedWidgets.toString())
                    .putInt(COLUMN_KEY, snapshot.getInt("columns"))
                    .commit()) {
                restorePreferences(previous, storageKey, COLUMN_KEY);
                return false;
            }
            // The current screen's close()/save() must now save the restored definitions.
            widgets.clear();
            widgets.addAll(restored);
            return true;
        } catch (JSONException | RuntimeException invalid) {
            return false;
        }
    }

    private JSONObject readSlot(int index) throws JSONException {
        if (!validSlot(index)) return null;
        String saved = preferences.getString(slotKeyPrefix + index, null);
        if (saved == null) return null;
        JSONObject snapshot = new JSONObject(saved);
        validateSlot(snapshot);
        return snapshot;
    }

    private static boolean validSlot(int index) {
        return index >= 0 && index < SLOT_COUNT;
    }

    private static String slotName(String name, int index) {
        if (name == null) name = "";
        StringBuilder clean = new StringBuilder();
        boolean gap = false;
        int count = 0;
        for (int offset = 0; offset < name.length() && count < SLOT_NAME_LIMIT; ) {
            int codePoint = name.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isISOControl(codePoint)) {
                gap = clean.length() > 0;
            } else {
                if (gap) {
                    clean.append(' ');
                    count++;
                    gap = false;
                    if (count == SLOT_NAME_LIMIT) break;
                }
                clean.appendCodePoint(codePoint);
                count++;
            }
        }
        String result = clean.toString().trim();
        return result.isEmpty() ? "Slot " + (index + 1) : result;
    }

    private static void validateSlot(JSONObject snapshot) throws JSONException {
        require(integer(snapshot.get("version")) == SLOT_VERSION, "Unknown slot version");
        String name = string(snapshot.get("name"));
        require(!name.isEmpty() && name.equals(slotName(name, 0)), "Invalid slot name");
        require(integer(snapshot.get("savedAt")) > 0, "Invalid slot timestamp");
        long columns = integer(snapshot.get("columns"));
        require(columns >= 0 && columns <= MAX_COLUMNS, "Invalid column choice");
        JSONArray definitions = snapshot.getJSONArray("widgets");
        require(definitions.length() <= 32, "Too many widgets");
        HashSet<String> ids = new HashSet<>();
        for (int i = 0; i < definitions.length(); i++) {
            JSONObject widget = definitions.getJSONObject(i);
            String id = string(widget.get("id")), type = string(widget.get("type"));
            require(!id.trim().isEmpty() && id.length() <= 80 && ids.add(id), "Invalid widget ID");
            require(Arrays.asList(TYPES).contains(type), "Unknown widget type");
            string(widget.get("title"));
            string(widget.get("metric"));
            string(widget.get("note"));
            long color = integer(widget.get("color")), window = integer(widget.get("window"));
            require(color >= 0 && color <= 3, "Invalid widget color");
            require(window >= 60000L && window <= 730 * 86400000L, "Invalid chart window");
            double minimum = number(widget.get("min")), maximum = number(widget.get("max"));
            require(maximum > minimum, "Invalid gauge range");
            double advertised = number(widget.get("advertisedWh"));
            require(advertised >= 0 && advertised <= 10000, "Invalid battery rating");
            // Optional in version-1 snapshots created before this preference existed.
            if (widget.has("showDisconnect"))
                require(widget.get("showDisconnect") instanceof Boolean, "Invalid disconnect preference");
            if (widget.has("absoluteAxis"))
                require(widget.get("absoluteAxis") instanceof Boolean, "Invalid chart axis preference");
            if (widget.has("quickActions")) {
                JSONArray actions = widget.getJSONArray("quickActions");
                require(actions.length() >= minimumShortcuts(type)
                        && actions.length() <= maximumShortcuts(type), "Invalid shortcut count");
                HashSet<String> actionIds = new HashSet<>();
                for (int k = 0; k < actions.length(); k++) {
                    String action = string(actions.get(k));
                    require(QuickAccessActions.find(action) != null && actionIds.add(action),
                            "Invalid shortcut");
                }
            }
            JSONArray keys = widget.getJSONArray("keys");
            require(keys.length() <= 12, "Too many reading keys");
            for (int k = 0; k < keys.length(); k++) string(keys.get(k));
            // Slot format starts after both legacy scooter migrations. Missing markers
            // are damage, not a reason to silently change a saved custom reading list.
            if (type.equals("scooter")) {
                require(Boolean.TRUE.equals(widget.get("scooterStats")), "Missing scooter marker");
                require(Boolean.TRUE.equals(widget.get("glanceableScooterStats")), "Missing scooter marker");
            }
            validateLayouts(widget.getJSONObject("layouts"));
            if (widget.has("expandedLayouts"))
                validateLayouts(widget.getJSONObject("expandedLayouts"));
        }
    }

    private static void validateLayouts(JSONObject layouts) throws JSONException {
        Iterator<String> widths = layouts.keys();
        while (widths.hasNext()) {
            String width = widths.next();
            require(width.length() == 1 && width.charAt(0) >= '1'
                    && width.charAt(0) <= '0' + MAX_COLUMNS, "Invalid layout width");
            int columnCount = Integer.parseInt(width);
            JSONArray rectangle = layouts.getJSONArray(width);
            require(rectangle.length() == 4, "Invalid layout rectangle");
            long x = integer(rectangle.get(0)), y = integer(rectangle.get(1));
            long w = integer(rectangle.get(2)), h = integer(rectangle.get(3));
            require(w >= 1 && w <= columnCount && x >= 0 && x <= columnCount - w,
                    "Layout exceeds column bounds");
            // Matches the grid's 300 gesture rows plus displaced 16-row cards.
            require(y >= 0 && y <= 300 + 32 * 32 && h >= 1 && h <= 16,
                    "Layout exceeds row bounds");
        }
    }

    private static String string(Object value) throws JSONException {
        require(value instanceof String, "Expected text");
        return (String) value;
    }

    private static long integer(Object value) throws JSONException {
        require(value instanceof Integer || value instanceof Long, "Expected integer");
        return ((Number) value).longValue();
    }

    private static double number(Object value) throws JSONException {
        require(value instanceof Number, "Expected number");
        double result = ((Number) value).doubleValue();
        require(Double.isFinite(result), "Expected finite number");
        return result;
    }

    private static void require(boolean valid, String message) throws JSONException {
        if (!valid) throw new JSONException(message);
    }

    private void restorePreferences(Map<String, ?> previous, String... keys) {
        SharedPreferences.Editor rollback = preferences.edit();
        for (String key : keys) {
            Object value = previous.get(key);
            if (value == null) rollback.remove(key);
            else if (value instanceof String) rollback.putString(key, (String) value);
            else if (value instanceof Integer) rollback.putInt(key, (Integer) value);
            else if (value instanceof Long) rollback.putLong(key, (Long) value);
            else if (value instanceof Boolean) rollback.putBoolean(key, (Boolean) value);
            else if (value instanceof Float) rollback.putFloat(key, (Float) value);
            else if (value instanceof Set) {
                @SuppressWarnings("unchecked") Set<String> strings = (Set<String>) value;
                rollback.putStringSet(key, strings);
            }
        }
        rollback.apply();
    }
}
