package com.jjricks.volt;
import java.util.*;
public final class StatsFavouriteOrderTest {
    private static int checks;
    private static void require(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    public static void main(String[] args) {
        Set<String> readings = new HashSet<>(Arrays.asList("power_w", "voltage_v"));
        Set<String> charts = new HashSet<>(Arrays.asList("power_w", "speed_kmh"));
        List<String> catalog = Arrays.asList("voltage_v", "power_w", "speed_kmh");
        List<String> order = StatsFavouriteOrder.resolve("", readings, charts, catalog);
        require(order.equals(Arrays.asList("reading:voltage_v", "reading:power_w", "chart:power_w", "chart:speed_kmh")), "Unordered favourites migration should follow catalogue deterministically");
        require(StatsFavouriteOrder.move(order, "chart:power_w", -1), "Move chart through reading");
        require(order.get(1).equals("chart:power_w"), "Distinct chart/reading IDs mixed up");
        require(StatsFavouriteOrder.resolve(StatsFavouriteOrder.encode(order), readings, charts, catalog).equals(order), "Roundtrip order");
        require(!StatsFavouriteOrder.move(order, order.get(0), -1) && !StatsFavouriteOrder.move(order, order.get(3), 1), "Boundary move");
        readings.remove("voltage_v");
        order = StatsFavouriteOrder.resolve(StatsFavouriteOrder.encode(order) + "\nchart:power_w\nchart:unknown\nreading:power_w", readings, charts, catalog);
        require(order.equals(Arrays.asList("chart:power_w", "reading:power_w", "chart:speed_kmh")), "Removed/duplicate/unknown entries survived");
        readings.add("voltage_v");
        order = StatsFavouriteOrder.resolve(StatsFavouriteOrder.encode(order), readings, charts, catalog);
        require(order.get(3).equals("reading:voltage_v"), "New favourite should append without sorting existing entries");
        require(!StatsFavouriteOrder.move(order, "missing", 1), "Absent move");
        require(StatsFavouriteOrder.resolve(null, Collections.emptySet(), Collections.emptySet(), catalog).isEmpty(), "Empty migration");
        System.out.println("StatsFavouriteOrder: " + checks + " checks passed");
    }
}
