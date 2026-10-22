package local.navee.offlinelock;

import java.util.ArrayList;
import java.util.Arrays;

/** Sanitized owner-selected release defaults; widget IDs are created afresh for each dashboard. */
final class DefaultDashboard {
    static final int COLUMNS = 5;

    private DefaultDashboard() {}

    static ArrayList<DashboardConfig.Widget> create() {
        ArrayList<DashboardConfig.Widget> result = new ArrayList<>();
        // Keep the existing compact cards in place, including their six-column arrangement.
        DashboardConfig.Widget overview = add(result, "overview", "Scooter overview",
                "power_w", 86400000L, stack(0, 4, 0, 0, 3, 4));
        overview.keys.clear();
        overview.keys.addAll(Arrays.asList("odometer_km", "driving_mode"));
        DashboardConfig.Widget lock = add(result, "lockbar", "Lock bar",
                "power_w", 86400000L, stack(4, 1, 0, 4, 3, 1));
        lock.showDisconnect = false;
        DashboardConfig.Widget readings = add(result, "tiles", "Reading tiles",
                "power_w", 86400000L, stack(5, 2, 3, 0, 3, 2));
        readings.keys.clear();
        readings.keys.addAll(Arrays.asList("power_w", "temperature_c", "battery_health_percent"));
        DashboardConfig.Widget settings = add(result, "shortcuts", "Setting tiles",
                "power_w", 86400000L, stack(7, 1, 3, 2, 3, 1));
        settings.quickActions.clear();
        settings.quickActions.addAll(Arrays.asList("feature:maximum_speed", "feature:timed_charging"));
        add(result, "ride", "Ride recorder",
                "power_w", 86400000L, stack(8, 1, 3, 3, 3, 1));
        // Six columns need to clear the overview/lock stack before spanning the whole grid.
        add(result, "capacity", "Usable ride energy",
                "power_w", 90 * 86400000L, stack(9, 4, 0, 5, 6, 4));
        setExpandedLayouts(result);
        return result;
    }

    /** Wide Home: overview/readings above full-width controls and Usable ride energy. */
    private static void setExpandedLayouts(ArrayList<DashboardConfig.Widget> widgets) {
        for (int columns = 1; columns <= DashboardConfig.MAX_COLUMNS; columns++) {
            if (columns == 1) {
                // A deliberately single-column wide dashboard still uses the compact stack.
                for (DashboardConfig.Widget widget : widgets)
                    widget.layouts(true).put(columns, widget.layouts.get(columns).copy());
                continue;
            }
            int overviewWidth = Math.max(1, Math.min(columns - 1, (columns * 3 + 2) / 5));
            for (DashboardConfig.Widget widget : widgets) {
                DashboardLayout.Cell cell;
                switch (widget.type) {
                    case "overview":
                        cell = new DashboardLayout.Cell(widget.id, 0, 0, overviewWidth, 4);
                        break;
                    case "tiles":
                        cell = new DashboardLayout.Cell(widget.id, overviewWidth, 0,
                                columns - overviewWidth, 4);
                        break;
                    case "capacity":
                        cell = new DashboardLayout.Cell(widget.id, 0, 7, columns, 4);
                        break;
                    default:
                        int row = widget.type.equals("lockbar") ? 4
                                : widget.type.equals("shortcuts") ? 5 : 6;
                        cell = new DashboardLayout.Cell(widget.id, 0, row, columns, 1);
                }
                widget.layouts(true).put(columns, cell);
            }
        }
    }

    /** Full width at row {@code y} for one to five columns, then the given six-column cell. */
    private static int[][] stack(int y, int h, int x6, int y6, int w6, int h6) {
        int[][] placements = new int[6][];
        for (int columns = 1; columns <= 5; columns++)
            placements[columns - 1] = new int[] {columns, 0, y, columns, h};
        placements[5] = new int[] {6, x6, y6, w6, h6};
        return placements;
    }

    private static DashboardConfig.Widget add(ArrayList<DashboardConfig.Widget> result,
            String type, String title, String metric, long windowMs, int[][] placements) {
        DashboardConfig.Widget widget = new DashboardConfig.Widget(type);
        widget.title = title;
        widget.metric = metric;
        widget.note = "";
        widget.color = 0;
        widget.windowMs = windowMs;
        widget.minimum = 0;
        widget.maximum = 100;
        widget.advertisedWh = 0;
        widget.showDisconnect = true;
        widget.absoluteAxis = false;
        widget.keys.clear();
        widget.keys.addAll(Arrays.asList("remaining_range_km", "power_w",
                "battery_health_percent", "temperature_c"));
        widget.quickActions.clear();
        widget.quickActions.addAll(Arrays.asList("lights", "controls", "stats", "rides"));
        for (int[] cell : placements)
            widget.layouts.put(cell[0], new DashboardLayout.Cell(widget.id,
                    cell[1], cell[2], cell[3], cell[4]));
        result.add(widget);
        return widget;
    }
}
