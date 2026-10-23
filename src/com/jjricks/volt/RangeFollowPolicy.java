package com.jjricks.volt;

import java.util.HashMap;
import java.util.Map;

/** Selects a firmware range model; never invents or rescales a reported distance. */
public final class RangeFollowPolicy {
    private final Map<String, String> attempted = new HashMap<>();

    /** UT5 MAX uses 2=Eco, 3=Sport and 11=Walk. Some firmware only offers D/S. */
    public static Integer target(int pid, Double ridingMode, int[] offered) {
        if (pid != 2538 || ridingMode == null || !Double.isFinite(ridingMode)) return null;
        if (ridingMode == 3d) return contains(offered, 3) ? 3 : null;
        if (ridingMode == 2d || ridingMode == 11d) {
            if (contains(offered, 4)) return 4;
            if (contains(offered, 2)) return 2;
        }
        return null;
    }
    private static boolean contains(int[] values, int needle) {
        if (values != null) for (int value : values) if (value == needle) return true;
        return false;
    }
    /** At most one attempt per profile/mode, including reconnects after an unconfirmed write. */
    public Integer next(String profile, int pid, Double mode, int[] offered, Integer current,
            boolean connected, boolean ready, boolean busy, boolean fresh, boolean enabled) {
        if (!enabled) { if (profile != null) attempted.remove(profile); return null; }
        // A mutation timeout can itself reconnect BLE. Forgetting here would retry forever.
        if (!connected) return null;
        if (!ready || busy || !fresh || profile == null || profile.isEmpty() || current == null) return null;
        Integer target = target(pid, mode, offered);
        String key = mode + ":" + target;
        if (target == null) return null;
        if (target.equals(current)) { attempted.put(profile, key); return null; }
        if (key.equals(attempted.get(profile))) return null;
        attempted.put(profile, key);
        return target;
    }
    public void reset() { attempted.clear(); }
}
