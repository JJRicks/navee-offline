package com.jjricks.volt;

import java.util.Arrays;
import java.util.List;

/** Home's default Setting tiles follow what the official app offers each model; a passing state never moves a tile. */
public final class ModelTilesTest {
    private static int assertions;
    public static void main(String[] args) {
        List<String> def = ModelTiles.DEFAULT;
        check(def.equals(Arrays.asList("feature:maximum_speed", "feature:timed_charging")), "The release default pair");
        check(ModelTiles.settingTiles(ModelRegistryTest.advertised("25381")).equals(def), "The UT5 MAX keeps Top speed and Charge schedule");
        check(ModelTiles.settingTiles(ModelRegistryTest.advertised("25462")).equals(Arrays.asList("feature:maximum_speed", "feature:cruise_control")),
                "The GT5 Max keeps Top speed (offered, even with an unreadable value) and swaps scheduled charging for cruise control");
        check(ModelTiles.settingTiles(ModelRegistryTest.advertised("22131")).equals(Arrays.asList("feature:cruise_control", "feature:energy_recovery")),
                "The N65i gets two controls its official app offers");
        for (String pid : new String[]{"25381", "25462", "22131", "23452", "26191", "27011", "24431"})
            for (String id : ModelTiles.settingTiles(ModelRegistryTest.advertised(pid))) {
                String key = id.substring("feature:".length());
                check(ModelTiles.offered(key, ModelRegistryTest.advertised(pid)) || ModelTiles.settingTiles(ModelRegistryTest.advertised(pid)) == def,
                        "Model " + pid + " is given only offered tiles: " + key);
                check(!ScooterCommands.find(key).experimental && !key.equals("lock"), "No Lab control or lock tile: " + key);
            }
        check(ModelTiles.settingTiles(null) == def && ModelTiles.settingTiles(DeviceDescriptor.of("", "", 0, DeviceDescriptor.Firmware.NONE, false, DeviceDescriptor.Transport.STANDARD, 0)) == def,
                "An unidentified scooter keeps the defaults until Volt knows its model");
        check(ModelTiles.settingTiles(DeviceDescriptor.simulated("25462", 0)) == def, "The simulator never rewrites a saved dashboard");
        check(ModelTiles.settingTiles(ModelRegistryTest.advertised("39991")) == def, "A model offering none of them keeps the defaults rather than an empty card");
        check(ModelTiles.settingTiles(ModelRegistryTest.advertised("25462").withOwnerModel("UT5 Max")).equals(ModelTiles.settingTiles(ModelRegistryTest.advertised("25462")))
                && ModelTiles.settingTiles(ModelRegistryTest.advertised("23451").withOwnerModel("GT5 Max")).equals(ModelTiles.settingTiles(ModelRegistryTest.advertised("23451"))),
                "The owner's model answer never chooses controls");
        System.out.println("ModelTiles tests passed: " + assertions + " assertions");
    }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
}
