package com.jjricks.volt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Home's default Setting tiles for a model: the release pair (Top speed and Charge schedule) where the
 * official app offers both, otherwise the controls it does offer, in a fixed order of what owners reach
 * for most. Only a model's mapping decides, never a passing state such as locked, disconnected or an
 * unreadable value, so a tile never moves because of one connection.
 */
final class ModelTiles {
    static final List<String> DEFAULT = Collections.unmodifiableList(Arrays.asList("feature:maximum_speed", "feature:timed_charging"));
    /** After the defaults, in order; Lab controls, the lock (Home has its own bar) and range source are never picked. */
    static final List<String> FALLBACK = Collections.unmodifiableList(Arrays.asList(
            "cruise_control", "energy_recovery", "headlight", "start_speed", "traction_control", "turn_sound", "auto_sensor", "display_units"));
    private ModelTiles() {}

    /** The model's Setting tiles: DEFAULT while the model isn't identified (or its identity conflicts), or when it offers none of these. */
    static List<String> settingTiles(DeviceDescriptor d) {
        if (d == null || d.simulated || d.pidSource() == DeviceDescriptor.PidSource.NONE || ModelRegistry.resolve(d).conflict) return DEFAULT;
        List<String> chosen = new ArrayList<>();
        for (String id : DEFAULT) if (offered(id.substring("feature:".length()), d)) chosen.add(id);
        for (String key : FALLBACK) {
            if (chosen.size() >= DEFAULT.size()) break;
            if (offered(key, d) && !chosen.contains("feature:" + key)) chosen.add("feature:" + key);
        }
        return chosen.isEmpty() ? DEFAULT : Collections.unmodifiableList(chosen);
    }

    /** The official app maps this control for the model (Volt knows its encoding there), and it isn't a Lab control. */
    static boolean offered(String key, DeviceDescriptor d) {
        ScooterCommands.Feature feature = ScooterCommands.find(key);
        return feature != null && !feature.experimental
                && ModelRegistry.rule(key, d, null, null).support == CapabilityResolver.Support.KNOWN_ENCODING;
    }
}
