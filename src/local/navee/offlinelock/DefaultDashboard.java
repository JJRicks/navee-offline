package local.navee.offlinelock;

import java.util.ArrayList;
import java.util.Arrays;

/** Sanitized owner-selected release defaults; widget IDs are created afresh for each dashboard. */
final class DefaultDashboard {
    static final int COLUMNS = 5;

    private DefaultDashboard() {}

    static ArrayList<DashboardConfig.Widget> create() {
        ArrayList<DashboardConfig.Widget> result = new ArrayList<>();
        add(result, "lock", "Lock & connection",
                "power_w", 86400000L, new int[][] {
                    {1, 0, 4, 1, 4},
                    {2, 0, 4, 1, 4},
                    {3, 2, 0, 1, 4},
                    {4, 2, 3, 2, 4},
                    {5, 0, 3, 2, 4},
                    {6, 4, 0, 2, 4}
                });
        DashboardConfig.Widget scooter = add(result, "scooter", "Your scooter",
                "power_w", 86400000L, new int[][] {
                    {1, 0, 0, 1, 4},
                    {2, 0, 0, 2, 4},
                    {3, 0, 0, 2, 4},
                    {4, 0, 0, 3, 3},
                    {5, 0, 0, 3, 3},
                    {6, 0, 0, 4, 4}
                });
        scooter.keys.clear();
        scooter.keys.addAll(Arrays.asList("odometer_km", "remaining_range_km", "driving_mode"));
        add(result, "battery", "Battery overview",
                "power_w", 86400000L, new int[][] {
                    {1, 0, 8, 1, 4},
                    {2, 0, 8, 1, 4},
                    {3, 2, 4, 1, 5},
                    {4, 2, 7, 2, 5},
                    {5, 2, 3, 3, 4},
                    {6, 3, 4, 3, 5}
                });
        add(result, "live", "Live trend",
                "power_w", 900000L, new int[][] {
                    {1, 0, 12, 1, 5},
                    {2, 0, 12, 2, 5},
                    {3, 0, 4, 2, 5},
                    {4, 0, 3, 2, 5},
                    {5, 0, 13, 5, 4},
                    {6, 0, 4, 3, 5}
                });
        add(result, "capacity", "Capacity experiment",
                "power_w", 7776000000L, new int[][] {
                    {1, 0, 17, 1, 6},
                    {2, 0, 17, 2, 6},
                    {3, 0, 13, 3, 6},
                    {4, 0, 12, 4, 6},
                    {5, 0, 22, 5, 5},
                    {6, 0, 13, 6, 6}
                });
        add(result, "ride", "Ride recorder",
                "power_w", 86400000L, new int[][] {
                    {1, 0, 23, 1, 4},
                    {2, 0, 23, 2, 4},
                    {3, 0, 9, 1, 4},
                    {4, 0, 8, 2, 4},
                    {5, 0, 7, 5, 1},
                    {6, 0, 9, 3, 4}
                });
        // These four cards were captured only at five columns. Give smaller grids
        // explicit gaps or later rows so absent positions cannot displace the lock card.
        DashboardConfig.Widget shortcuts = add(result, "quick", "Quick access",
                "power_w", 86400000L, new int[][] {
                    {1, 0, 27, 1, 3},
                    {2, 1, 4, 1, 4},
                    {3, 0, 19, 3, 3},
                    {4, 0, 18, 4, 3},
                    {5, 3, 0, 2, 3}
                });
        shortcuts.quickActions.clear();
        shortcuts.quickActions.addAll(Arrays.asList("feature:maximum_speed", "feature:timed_charging"));
        add(result, "health", "Battery health",
                "battery_health_percent", 2592000000L, new int[][] {
                    {1, 0, 30, 1, 5},
                    {2, 0, 30, 2, 5},
                    {3, 0, 22, 3, 5},
                    {4, 0, 21, 4, 5},
                    {5, 0, 8, 5, 5}
                });
        add(result, "history", "History chart",
                "battery_percent", 604800000L, new int[][] {
                    {1, 0, 35, 1, 5},
                    {2, 0, 35, 2, 5},
                    {3, 0, 27, 3, 5},
                    {4, 0, 26, 4, 5},
                    {5, 0, 17, 5, 5}
                });
        add(result, "charging", "Charging",
                "power_w", 86400000L, new int[][] {
                    {1, 0, 40, 1, 4},
                    {2, 1, 8, 1, 4},
                    {3, 0, 32, 3, 4},
                    {4, 0, 31, 4, 4},
                    {5, 0, 27, 5, 4}
                });
        return result;
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
