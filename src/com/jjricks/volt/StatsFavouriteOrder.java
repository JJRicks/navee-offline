package com.jjricks.volt;

import java.util.*;

/** Stable mixed reading/chart ordering with migration from the old unordered reading set. */
final class StatsFavouriteOrder {
    private StatsFavouriteOrder() {}
    static List<String> resolve(String saved, Set<String> readings, Set<String> charts, Iterable<String> catalog) {
        Set<String> available = new LinkedHashSet<>();
        for (String key : catalog) {
            if (readings.contains(key)) available.add("reading:" + key);
            if (charts.contains(key)) available.add("chart:" + key);
        }
        List<String> result = new ArrayList<>();
        if (saved != null) for (String id : saved.split("\n")) if (available.remove(id)) result.add(id);
        result.addAll(available); return result;
    }
    static boolean move(List<String> order, String id, int direction) {
        int index = order.indexOf(id), target = index + Integer.signum(direction);
        if (index < 0 || target < 0 || target >= order.size() || index == target) return false;
        Collections.swap(order, index, target); return true;
    }
    static String encode(List<String> order) { return String.join("\n", order); }
}
