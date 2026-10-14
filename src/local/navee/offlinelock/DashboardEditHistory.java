package local.navee.offlinelock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** In-memory layout undo/redo for one dashboard editing session. */
public final class DashboardEditHistory {
    public static final int MAX_STEPS = 40;
    private final int limit;
    private final Deque<Change> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private DashboardConfig owner;

    public DashboardEditHistory() {
        this(MAX_STEPS);
    }

    /** A smaller bound is useful for short-lived editors and isolated validation. */
    public DashboardEditHistory(int limit) {
        if (limit < 1 || limit > MAX_STEPS)
            throw new IllegalArgumentException("Invalid dashboard undo limit");
        this.limit = limit;
    }

    /**
     * Membership/order and independent geometry copies only. Widget references
     * deliberately retain their current settings, including when a deleted card
     * is restored. Undo never rolls titles, parameters or other options backward.
     */
    public static final class Snapshot {
        private final DashboardConfig owner;
        private final ArrayList<Entry> entries = new ArrayList<>();

        private Snapshot(DashboardConfig config) {
            owner = config;
            for (DashboardConfig.Widget widget : config.widgets) entries.add(new Entry(widget));
        }

        private boolean sameLayout(Snapshot other) {
            if (entries.size() != other.entries.size()) return false;
            for (int i = 0; i < entries.size(); i++)
                if (!entries.get(i).sameLayout(other.entries.get(i))) return false;
            return true;
        }

        private void restore(DashboardConfig config) {
            config.widgets.clear();
            for (Entry entry : entries) {
                entry.widget.layouts.clear();
                for (Map.Entry<Integer, DashboardLayout.Cell> layout : entry.layouts.entrySet())
                    entry.widget.layouts.put(layout.getKey(), copy(layout.getValue()));
                config.widgets.add(entry.widget);
            }
        }
    }

    private static final class Entry {
        final DashboardConfig.Widget widget;
        final Map<Integer, DashboardLayout.Cell> layouts = new HashMap<>();

        Entry(DashboardConfig.Widget widget) {
            this.widget = widget;
            for (Map.Entry<Integer, DashboardLayout.Cell> layout : widget.layouts.entrySet())
                layouts.put(layout.getKey(), copy(layout.getValue()));
        }

        boolean sameLayout(Entry other) {
            if (widget != other.widget || !layouts.keySet().equals(other.layouts.keySet())) return false;
            for (Integer columns : layouts.keySet()) {
                DashboardLayout.Cell a = layouts.get(columns), b = other.layouts.get(columns);
                if (a == null || b == null) {
                    if (a != b) return false;
                } else if (!Objects.equals(a.id, b.id) || a.x != b.x || a.y != b.y
                        || a.w != b.w || a.h != b.h) return false;
            }
            return true;
        }
    }

    private static final class Change {
        final Snapshot before, after;

        Change(Snapshot before, Snapshot after) {
            this.before = before;
            this.after = after;
        }
    }

    public static Snapshot capture(DashboardConfig config) {
        if (config == null) throw new IllegalArgumentException("Missing dashboard");
        return new Snapshot(config);
    }

    /**
     * Call after a completed structural edit, not a canceled gesture. No-op and
     * settings-only edits neither consume history nor discard an available redo.
     * The caller owns saving and refreshing the dashboard after a real change.
     */
    public boolean record(Snapshot before, DashboardConfig config) {
        checkOwner(config);
        if (before == null || before.owner != config)
            throw new IllegalArgumentException("Snapshot belongs to another dashboard");
        Snapshot after = capture(config);
        if (before.sameLayout(after)) return false;
        owner = config;
        undo.addLast(new Change(before, after));
        while (undo.size() > limit) undo.removeFirst();
        redo.clear();
        return true;
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    /** Restores only membership/order and placement; no disk writes or scooter actions. */
    public boolean undo(DashboardConfig config) {
        checkOwner(config);
        if (undo.isEmpty()) return false;
        Change change = undo.removeLast();
        change.before.restore(config);
        redo.addLast(change);
        return true;
    }

    /** Reapplies only membership/order and placement, retaining the latest widget settings. */
    public boolean redo(DashboardConfig config) {
        checkOwner(config);
        if (redo.isEmpty()) return false;
        Change change = redo.removeLast();
        change.after.restore(config);
        undo.addLast(change);
        return true;
    }

    public void clear() {
        undo.clear();
        redo.clear();
        owner = null;
    }

    private void checkOwner(DashboardConfig config) {
        if (config == null || (owner != null && owner != config))
            throw new IllegalArgumentException("Undo history belongs to another dashboard");
    }

    private static DashboardLayout.Cell copy(DashboardLayout.Cell cell) {
        return cell == null ? null : cell.copy();
    }
}
