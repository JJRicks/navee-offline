package local.navee.offlinelock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Android-free placement for the dashboard's small, resizable card grid. */
public final class DashboardLayout {
    private static final int MAX_REQUESTED_ROW = 300;
    private static final int MAX_HEIGHT = 16;

    private DashboardLayout() {}

    public static final class Cell {
        public final String id;
        public int x, y, w, h;

        public Cell(String id, int x, int y, int w, int h) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        public Cell copy() {
            return new Cell(id, x, y, w, h);
        }
    }

    /**
     * Returns independent cells in input order. A matching priority card keeps its
     * requested rectangle after bounds checking; remaining cards are placed in
     * requested row/column order. Existing positions are retained when free.
     *
     * Collisions search the same row at the nearest column, preferring the left
     * column on ties, then later rows. Gesture targets are limited to row 300 by
     * the UI. Persisted rows allow an additional 16 rows per card so a displaced
     * card is not pulled back into collisions when the saved layout is restored.
     * Cards may extend farther down so none is dropped at the row boundary.
     * Null or unknown priority IDs simply use normal placement order.
     */
    public static List<Cell> settle(List<Cell> source, int columns, String priorityId) {
        if (source == null) throw new IllegalArgumentException("Missing dashboard cells");
        int columnCount = clamp(columns, 1, 4);
        int maximumSavedRow = (int) Math.min(Integer.MAX_VALUE - MAX_HEIGHT,
                MAX_REQUESTED_ROW + (long) source.size() * MAX_HEIGHT);
        List<Cell> result = new ArrayList<Cell>(source.size());
        Set<String> ids = new HashSet<String>();
        Cell priority = null;
        for (Cell original : source) {
            if (original == null || original.id == null || original.id.trim().isEmpty())
                throw new IllegalArgumentException("Missing dashboard cell ID");
            if (!ids.add(original.id))
                throw new IllegalArgumentException("Duplicate dashboard cell ID");
            Cell cell = original.copy();
            cell.w = clamp(cell.w, 1, columnCount);
            cell.h = clamp(cell.h, 1, MAX_HEIGHT);
            cell.x = clamp(cell.x, 0, columnCount - cell.w);
            cell.y = clamp(cell.y, 0, maximumSavedRow);
            result.add(cell);
            if (cell.id.equals(priorityId)) priority = cell;
        }

        List<Cell> ordered = new ArrayList<Cell>(result);
        // Collections.sort is stable: equal rectangles keep input order.
        Collections.sort(ordered, new Comparator<Cell>() {
            @Override public int compare(Cell a, Cell b) {
                int byRow = Integer.compare(a.y, b.y);
                return byRow != 0 ? byRow : Integer.compare(a.x, b.x);
            }
        });
        if (priority != null) {
            ordered.remove(priority);
            ordered.add(0, priority);
        }

        List<Cell> placed = new ArrayList<Cell>(result.size());
        for (Cell cell : ordered) {
            int desiredX = cell.x;
            int row = cell.y;
            boolean found = false;
            while (!found) {
                cell.y = row;
                for (int distance = 0; distance < columnCount; distance++) {
                    int left = desiredX - distance;
                    if (left >= 0 && fits(cell, left, placed)) {
                        found = true;
                        break;
                    }
                    int right = desiredX + distance;
                    if (distance != 0 && right <= columnCount - cell.w
                            && fits(cell, right, placed)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    if (row == Integer.MAX_VALUE - MAX_HEIGHT)
                        throw new IllegalArgumentException("Dashboard has too many rows");
                    row++;
                }
            }
            placed.add(cell);
        }
        return result;
    }

    private static boolean fits(Cell cell, int x, List<Cell> placed) {
        cell.x = x;
        for (Cell other : placed) if (overlaps(cell, other)) return false;
        return true;
    }

    /** Touching edges do not overlap; rectangles with no area do not overlap. */
    public static boolean overlaps(Cell a, Cell b) {
        if (a == null || b == null) throw new IllegalArgumentException("Missing dashboard cell");
        return a.w > 0 && a.h > 0 && b.w > 0 && b.h > 0
                && a.x < (long) b.x + b.w && b.x < (long) a.x + a.w
                && a.y < (long) b.y + b.h && b.y < (long) a.y + a.h;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
