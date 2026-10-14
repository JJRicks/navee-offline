package local.navee.offlinelock;

import java.util.ArrayList;
import java.util.Arrays;

/** Optional saved example. It contains definitions only; every reading still comes from the scooter. */
final class AllWidgetsDashboard {
    static final int COLUMNS = 6;
    private static final long DAY = 86400000L;

    private AllWidgetsDashboard() {}

    static ArrayList<DashboardConfig.Widget> create() {
        ArrayList<DashboardConfig.Widget> result = new ArrayList<>();
        DashboardConfig.Widget scooter = add(result, "scooter");
        DashboardConfig.Widget lock = add(result, "lock");
        DashboardConfig.Widget battery = add(result, "battery");
        DashboardConfig.Widget quick = add(result, "quick");
        DashboardConfig.Widget gauge = add(result, "gauge");
        gauge.title = "Speed"; gauge.metric = "speed_kmh"; gauge.maximum = 50;
        DashboardConfig.Widget metric = add(result, "metric");
        metric.title = "Battery charge"; metric.metric = "battery_percent";
        DashboardConfig.Widget ride = add(result, "ride");
        DashboardConfig.Widget trip = add(result, "trip");
        DashboardConfig.Widget readings = add(result, "readings");
        readings.title = "Electrical readings";
        readings.keys.clear();
        readings.keys.addAll(Arrays.asList("voltage_v", "current_a", "power_w", "temperature_c"));
        DashboardConfig.Widget live = add(result, "live");
        live.title = "Battery power"; live.metric = "power_w"; live.windowMs = 2 * 60000L;
        DashboardConfig.Widget history = add(result, "history");
        history.title = "Charge history"; history.metric = "battery_percent"; history.windowMs = 7 * DAY;
        DashboardConfig.Widget health = add(result, "health");
        health.windowMs = 30 * DAY;
        DashboardConfig.Widget capacity = add(result, "capacity");
        capacity.windowMs = 90 * DAY;
        DashboardConfig.Widget charging = add(result, "charging");
        DashboardConfig.Widget note = add(result, "note");
        note.title = "Make it yours";
        note.note = "Every widget, ready to try. Edit cards to choose readings, resize them, or make your own layout.";

        // Explicit arrangements for every supported width avoid missing-position packing.
        // The cover display keeps Connect near the top and gives charts the full width.
        int y = 0;
        int[] singleHeights = {3, 4, 4, 4, 3, 3, 3, 4, 5, 4, 4, 5, 5, 4, 3};
        for (int i = 0; i < result.size(); i++) {
            row(1, y, singleHeights[i], new int[] {1}, result.get(i));
            y += singleHeights[i];
        }
        row(2, 0, 3, new int[] {2}, scooter);
        row(2, 3, 4, new int[] {1, 1}, lock, battery);
        // Give shortcuts their own row. At large text the gauge and reading both
        // widen together; a one-column shortcut beside either would be stranded.
        row(2, 7, 4, new int[] {2}, quick);
        row(2, 11, 3, new int[] {1, 1}, gauge, metric);
        row(2, 14, 2, new int[] {2}, ride);
        row(2, 16, 4, new int[] {2}, trip);
        row(2, 20, 5, new int[] {2}, readings);
        row(2, 25, 4, new int[] {2}, live);
        row(2, 29, 4, new int[] {2}, history);
        row(2, 33, 5, new int[] {2}, health);
        row(2, 38, 5, new int[] {2}, capacity);
        row(2, 43, 4, new int[] {2}, charging);
        row(2, 47, 3, new int[] {2}, note);

        row(3, 0, 4, new int[] {2, 1}, scooter, lock);
        row(3, 4, 4, new int[] {1, 2}, battery, quick);
        row(3, 8, 3, new int[] {1, 1, 1}, gauge, metric, ride);
        row(3, 11, 4, new int[] {3}, live);
        row(3, 15, 5, new int[] {1, 2}, trip, readings);
        row(3, 20, 4, new int[] {3}, history);
        row(3, 24, 5, new int[] {3}, health);
        row(3, 29, 5, new int[] {3}, capacity);
        row(3, 34, 4, new int[] {2, 1}, charging, note);

        for (int columns : new int[] {4, 5}) {
            int left = (columns + 1) / 2, right = columns - left;
            row(columns, 0, 4, new int[] {left, right}, scooter, lock);
            row(columns, 4, 4, new int[] {left, right}, battery, quick);
            row(columns, 8, 3, new int[] {right, left}, gauge, metric);
            row(columns, 11, 2, new int[] {columns}, ride);
            row(columns, 13, 5, new int[] {right, left}, trip, readings);
            row(columns, 18, 5, new int[] {left, right}, live, history);
            row(columns, 23, 6, new int[] {left, right}, health, capacity);
            row(columns, 29, 4, new int[] {left, right}, charging, note);
        }
        row(6, 0, 4, new int[] {4, 2}, scooter, lock);
        row(6, 4, 4, new int[] {3, 3}, battery, quick);
        row(6, 8, 3, new int[] {2, 2, 2}, gauge, metric, ride);
        row(6, 11, 5, new int[] {3, 3}, trip, readings);
        row(6, 16, 5, new int[] {3, 3}, live, history);
        row(6, 21, 6, new int[] {3, 3}, health, capacity);
        row(6, 27, 4, new int[] {3, 3}, charging, note);
        return result;
    }

    private static DashboardConfig.Widget add(ArrayList<DashboardConfig.Widget> result, String type) {
        DashboardConfig.Widget widget = new DashboardConfig.Widget(type);
        result.add(widget);
        return widget;
    }

    private static void row(int columns, int y, int height, int[] spans,
            DashboardConfig.Widget... widgets) {
        if (spans.length != widgets.length) throw new IllegalArgumentException("Missing example span");
        int x = 0;
        for (int i = 0; i < widgets.length; i++) {
            DashboardConfig.Widget widget = widgets[i];
            widget.layouts.put(columns, new DashboardLayout.Cell(widget.id, x, y, spans[i], height));
            x += spans[i];
        }
        if (x != columns) throw new IllegalArgumentException("Example row must fill its columns");
    }
}
