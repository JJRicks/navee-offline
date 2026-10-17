package local.navee.offlinelock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/** Stable, named dashboard shortcuts. IDs never contain commands or arbitrary navigation targets. */
public final class QuickAccessActions {
    public static final int MIN_ITEMS = 2, MAX_ITEMS = 8;
    public static final class Entry {
        public final String id, label, icon, group, featureKey;
        private Entry(String id, String label, String icon, String group, String featureKey) {
            this.id = id; this.label = label; this.icon = icon; this.group = group;
            this.featureKey = featureKey;
        }
        public ScooterCommands.Feature feature() {
            return featureKey == null ? null : ScooterCommands.find(featureKey);
        }
        /** Sensitive and experimental controls retain their explained, guarded panels. */
        public boolean directToggle() {
            ScooterCommands.Feature f = feature();
            return f != null && f.type == ScooterCommands.Type.TOGGLE && !f.experimental
                    && !f.sensitive && !f.key.equals("proximity_key");
        }
        /**
         * The connected model's decision: on another model's mapping a non-Lab on/off control toggles
         * directly even where the UT5 MAX keeps it in Lab. The UT5 MAX and unmapped controls keep directToggle().
         */
        public boolean directToggle(CapabilityResolver.Decision d) {
            ScooterCommands.Feature f = feature();
            if (f == null || d == null || d.support != CapabilityResolver.Support.KNOWN_ENCODING || d.variant == null
                    || d.variant.decode == FeatureVariant.Decode.UT5_FEATURE) return directToggle();
            return f.type == ScooterCommands.Type.TOGGLE && !d.variant.lab && !f.sensitive
                    && !f.key.equals("proximity_key") && Arrays.equals(d.variant.values, new int[]{0, 1});
        }
    }
    private static final LinkedHashMap<String, Entry> ENTRIES = new LinkedHashMap<>();
    static {
        add("lights", "Lights", "lights", "Menus", null);
        add("controls", "Settings", "settings", "Menus", null);
        add("stats", "Live Stats", "stats", "Pages", null);
        add("rides", "Rides", "rides", "Pages", null);
        add("section:Ride", "Ride settings", "scooter", "Menus", null);
        add("section:Battery", "Battery settings", "battery", "Menus", null);
        add("section:Line lights", "Line lights", "lights", "Menus", null);
        add("section:Star lights", "Star lights", "star", "Menus", null);
        add("section:Dashboard & sound", "Dashboard & sound", "sound", "Menus", null);
        add("section:Security", "PIN & proximity", "shield", "Menus", null);
        add("section:Profile", "Scooter name", "pencil", "Menus", null);
        add("more:App", "App preferences", "settings", "Menus", null);
        add("more:Scooters", "My scooters", "scooter", "Menus", null);
        add("more:Lab", "Experimental controls", "lab", "Menus", null);
        add("more:Data", "Data & history", "history", "Menus", null);
        add("more:About", "About & permissions", "info", "Menus", null);
        for (ScooterCommands.Feature feature : ScooterCommands.catalog())
            add("feature:" + feature.key, feature.label, icon(feature.key),
                    feature.experimental ? "Experimental controls" : feature.group, feature.key);
    }
    private QuickAccessActions() {}
    private static void add(String id, String label, String icon, String group, String featureKey) {
        ENTRIES.put(id, new Entry(id, label, icon, group, featureKey));
    }
    public static List<Entry> catalog() {
        return Collections.unmodifiableList(new ArrayList<>(ENTRIES.values()));
    }
    public static Entry find(String id) { return ENTRIES.get(id); }
    public static List<String> defaultIds() {
        return Arrays.asList("lights", "controls", "stats", "rides");
    }
    /** Unknown/duplicate values cannot become executable actions. Missing legacy lists keep defaults. */
    public static ArrayList<String> normalize(List<String> ids) {
        return normalize(ids, MIN_ITEMS, MAX_ITEMS);
    }
    /** Same rules with a widget-specific count, e.g. one to four setting tiles. */
    public static ArrayList<String> normalize(List<String> ids, int minimum, int maximum) {
        int most = Math.max(1, Math.min(MAX_ITEMS, maximum));
        int least = Math.max(1, Math.min(most, minimum));
        ArrayList<String> result = new ArrayList<>();
        if (ids == null) result.addAll(defaultIds().subList(0, Math.min(most, defaultIds().size())));
        else for (String id : ids) {
            if (result.size() >= most) break;
            if (find(id) != null && !result.contains(id)) result.add(id);
        }
        for (String fallback : defaultIds()) {
            if (result.size() >= least) break;
            if (!result.contains(fallback)) result.add(fallback);
        }
        return result;
    }
    /** Mirrors the named UI icon family while keeping the catalog independent of Android. */
    private static String icon(String key) {
        if (key.equals("hill_start_assist")) return "walk";
        if (key.equals("charge_start") || key.equals("timed_charging")) return "clock";
        if (key.contains("speed") || key.equals("cruise_control")) return "speed";
        if (key.contains("charge") || key.equals("low_power") || key.equals("long_range_mode")) return "battery";
        if (key.equals("energy_recovery")) return "recovery";
        if (key.equals("headlight") || key.equals("auto_sensor") || key.startsWith("line_")) return "lights";
        if (key.startsWith("star_")) return "star";
        if (key.contains("lock") || key.equals("pin") || key.equals("proximity_key")) return "lock";
        if (key.contains("sound") || key.equals("prompt_tone")) return "sound";
        if (key.equals("traction_control")) return "shield";
        if (key.contains("assist") || key.contains("hill")) return "hill";
        if (key.contains("unit")) return "ruler";
        if (key.contains("range")) return "route";
        return "tune";
    }
}
