package local.navee.offlinelock;

import android.content.SharedPreferences;

import org.json.*;

import java.util.*;

/** Local, per-scooter dashboard definitions with independent layouts for each column count. */
public final class DashboardConfig {
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
        "note"
    };
    public static final String[] NAMES = {
        "Lock & connection",
        "Your scooter",
        "Battery overview",
        "Big reading",
        "Gauge",
        "Live trend",
        "History chart",
        "Reading list",
        "Trip overview",
        "Battery health",
        "Capacity experiment",
        "Charging",
        "Quick access",
        "Ride recorder",
        "Note"
    };
    public static final String[] DESCRIPTIONS = {
        "A large lock switch and connection controls",
        "Your name and UT5 MAX photo",
        "Charge, estimated range, power and reported health",
        "Any available parameter in a large display",
        "A parameter with your chosen scale",
        "A recent parameter chart",
        "Explore days, weeks or months of readings",
        "Choose several parameters for one card",
        "Distance, speed and riding time",
        "Reported health, charge count and a long-term chart",
        "Estimate usable energy from ride energy and battery-percentage drop",
        "Battery-side charging power, current and time at the present rate",
        "Shortcuts to lights, settings, stats and rides",
        "Start or finish an explicit ride recording",
        "A personal reminder or maintenance note"
    };

    public static final class Widget {
        public final String id;
        public String type, title, metric, note = "";
        public final ArrayList<String> keys = new ArrayList<>();
        public final Map<Integer, DashboardLayout.Cell> layouts = new HashMap<>();
        public int color;
        public long windowMs;
        public double minimum = 0, maximum = 100;
        /** Zero selects the model's documented rating, where known. */
        public double advertisedWh = 0;

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
                    Arrays.asList(
                            "remaining_range_km",
                            "power_w",
                            "battery_health_percent",
                            "temperature_c"));
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
                    return 4;
                default:
                    return 3;
            }
        }

        public int minimumHeight() {
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

        public int defaultWidth(int columns) {
            if (type.equals("metric") || type.equals("gauge")) return 1;
            if (type.equals("lock") || type.equals("scooter") || type.equals("capacity"))
                return columns;
            if (columns == 3 && (type.equals("quick") || type.equals("ride"))) return 1;
            return Math.min(columns, 2);
        }
    }

    private final SharedPreferences preferences;
    private final String storageKey;
    public final ArrayList<Widget> widgets = new ArrayList<>();

    public DashboardConfig(SharedPreferences preferences, String profileId) {
        this.preferences = preferences;
        storageKey = "dashboard_v1_" + profileId;
        String saved = preferences.getString(storageKey, null);
        if (saved != null)
            try {
                JSONArray a = new JSONArray(saved);
                HashSet<String> seen = new HashSet<>();
                for (int i = 0; i < Math.min(32, a.length()); i++) {
                    JSONObject o = a.getJSONObject(i);
                    String type = o.getString("type"), id = o.getString("id");
                    if (!Arrays.asList(TYPES).contains(type) || id.length() > 80 || !seen.add(id))
                        continue;
                    Widget w = new Widget(id, type);
                    w.title = o.optString("title", w.title);
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
                    JSONObject layouts = o.optJSONObject("layouts");
                    if (layouts != null)
                        for (int c = 1; c <= 4; c++) {
                            JSONArray p = layouts.optJSONArray(Integer.toString(c));
                            if (p != null && p.length() == 4)
                                w.layouts.put(
                                        c,
                                        new DashboardLayout.Cell(
                                                id,
                                                p.getInt(0),
                                                p.getInt(1),
                                                p.getInt(2),
                                                p.getInt(3)));
                        }
                    widgets.add(w);
                }
                return;
            } catch (Exception ignored) {
                widgets.clear();
            }
        reset();
    }

    public void reset() {
        widgets.clear();
        for (String type :
                new String[] {"lock", "scooter", "battery", "quick", "live", "capacity", "ride"})
            widgets.add(new Widget(type));
        save();
    }

    public List<DashboardLayout.Cell> layout(int columns, String priority) {
        ArrayList<DashboardLayout.Cell> cells = new ArrayList<>();
        for (Widget w : widgets) {
            DashboardLayout.Cell cell = w.layouts.get(columns);
            if (cell == null) {
                int width = w.defaultWidth(columns);
                cell = new DashboardLayout.Cell(w.id, 0, 0, width, w.defaultHeight());
            }
            cell = cell.copy();
            cell.h = Math.max(w.minimumHeight(), cell.h);
            cells.add(cell);
        }
        List<DashboardLayout.Cell> settled = DashboardLayout.settle(cells, columns, priority);
        for (DashboardLayout.Cell cell : settled)
            for (Widget w : widgets) if (w.id.equals(cell.id)) w.layouts.put(columns, cell);
        return settled;
    }

    public void save() {
        try {
            JSONArray a = new JSONArray();
            for (Widget w : widgets) {
                JSONObject o = new JSONObject();
                o.put("id", w.id);
                o.put("type", w.type);
                o.put("title", w.title);
                o.put("metric", w.metric);
                o.put("note", w.note);
                o.put("color", w.color);
                o.put("window", w.windowMs);
                o.put("min", w.minimum);
                o.put("max", w.maximum);
                o.put("advertisedWh", w.advertisedWh);
                o.put("keys", new JSONArray(w.keys));
                JSONObject layouts = new JSONObject();
                for (Map.Entry<Integer, DashboardLayout.Cell> e : w.layouts.entrySet()) {
                    DashboardLayout.Cell c = e.getValue();
                    layouts.put(
                            e.getKey().toString(), new JSONArray(new int[] {c.x, c.y, c.w, c.h}));
                }
                o.put("layouts", layouts);
                a.put(o);
            }
            preferences.edit().putString(storageKey, a.toString()).apply();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }
}
