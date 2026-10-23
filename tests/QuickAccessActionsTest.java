package com.jjricks.volt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;

/** Catalog/normalization checks, including the boundary that keeps security approvals in their menus. */
public final class QuickAccessActionsTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        check(QuickAccessActions.normalize(null).equals(Arrays.asList("lights", "controls", "stats", "rides")), "legacy defaults");
        check(QuickAccessActions.normalize(Arrays.asList()).equals(Arrays.asList("lights", "controls")), "minimum size");
        check(QuickAccessActions.normalize(Arrays.asList("feature:traction_control", "feature:traction_control", "shell:reboot", "wrong"))
                .equals(Arrays.asList("feature:traction_control", "lights")), "unknown and duplicate actions never execute");
        HashSet<String> seen = new HashSet<>();
        ArrayList<String> all = new ArrayList<>();
        for (QuickAccessActions.Entry entry : QuickAccessActions.catalog()) {
            check(seen.add(entry.id), "unique action ID");
            check(QuickAccessActions.find(entry.id) == entry, "stable lookup");
            check(!entry.label.isEmpty() && !entry.icon.isEmpty() && !entry.group.isEmpty(), "complete label metadata");
            all.add(entry.id);
        }
        check(QuickAccessActions.normalize(all).size() == 8, "maximum size");
        check(QuickAccessActions.normalize(Arrays.asList("feature:maximum_speed"), 1, 4)
                .equals(Arrays.asList("feature:maximum_speed")), "setting tiles keep a single shortcut");
        check(QuickAccessActions.normalize(Arrays.asList(), 1, 4).equals(Arrays.asList("lights")), "setting tiles minimum");
        check(QuickAccessActions.normalize(all, 1, 4).equals(all.subList(0, 4)), "setting tiles maximum");
        check(QuickAccessActions.normalize(null, 1, 4).equals(QuickAccessActions.defaultIds()), "setting tiles legacy");
        check(QuickAccessActions.find(null) == null && QuickAccessActions.find("nonsense") == null, "invalid IDs");
        check(QuickAccessActions.find("feature:traction_control").directToggle(), "traction is a direct toggle");
        check(QuickAccessActions.find("charge_done_by").directToggle(), "done-by has an on/off cue");
        check(!QuickAccessActions.find("charge_done_time").directToggle(), "done-by time opens the picker");
        check(QuickAccessActions.find("charge_done_by").feature() == null, "local scheduling is not a BLE command");
        check(QuickAccessActions.normalize(Arrays.asList("charge_done_by", "charge_done_time"), 1, 4)
                .equals(Arrays.asList("charge_done_by", "charge_done_time")), "schedule shortcuts survive saved-layout normalization");
        check(!QuickAccessActions.find("feature:start_speed").directToggle(), "kick start opens its range editor");
        check(!QuickAccessActions.find("feature:energy_recovery").directToggle(), "energy recovery opens choices");
        check(!QuickAccessActions.find("feature:proximity_key").directToggle(), "proximity keeps PIN and bond approval flow");
        check(!QuickAccessActions.find("feature:pin").directToggle(), "PIN stays in security menu");
        for (ScooterCommands.Feature feature : ScooterCommands.catalog()) {
            QuickAccessActions.Entry entry = QuickAccessActions.find("feature:" + feature.key);
            check(entry != null && entry.feature() == feature, "every named feature has a shortcut");
            check(!feature.experimental || !entry.directToggle(), "experimental settings retain inspection confirmation");
            check(!feature.sensitive || !entry.directToggle(), "sensitive settings never direct-toggle");
        }
        try { QuickAccessActions.catalog().clear(); throw new AssertionError("catalog mutable"); }
        catch (UnsupportedOperationException expected) { checks++; }
        System.out.println("QuickAccessActionsTest: " + checks + " checks passed");
    }
}
