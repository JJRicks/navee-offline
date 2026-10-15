package local.navee.offlinelock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Android-free placement for the dashboard's small, resizable card grid. */
public final class DashboardLayout {
    public static final int MAX_COLUMNS = 6;
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
        int columnCount = clamp(columns, 1, MAX_COLUMNS);
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

    /**
     * Minimum card width, including its internal padding but excluding the grid
     * gap. These are usable content floors, not a limit on the grid's granularity:
     * a six-column grid can give a lock two columns and a scooter four columns.
     * Text scaling increases the content allowance while fixed padding stays fixed.
     */
    public static float minimumWidgetWidthDp(String type, float textScale) {
        // A narrow shortcut rail uses icon-only 48 dp targets and a vertical editor toolbar.
        if ("quick".equals(type)) return 58;
        // Other cards keep four compact editor targets on one row.
        int width = "capacity".equals(type) ? 152 : 144;
        float scale = Float.isFinite(textScale) ? Math.max(1, textScale) : 1;
        return 32 + (width - 32) * scale;
    }

    /**
     * Minimum number of columns for a widget at this measured grid width. The
     * grid has gaps only between cards: span * (availableWidth + gap) / columns
     * minus one gap. If even a full-width card cannot meet its floor,
     * use the entire available width; never return an out-of-bounds span.
     */
    public static int minimumSpan(String type, int columns, float availableWidthDp,
            float gapDp, float textScale) {
        int count = clamp(columns, 1, MAX_COLUMNS);
        if (!Float.isFinite(availableWidthDp) || availableWidthDp <= 0) return count;
        float gap = Float.isFinite(gapDp) ? Math.max(0, gapDp) : 0;
        float minimum = minimumGridWidthDp(type, count, availableWidthDp, textScale);
        for (int span = 1; span < count; span++)
            if (span * ((double) availableWidthDp + gap) / count - gap >= minimum - .0001)
                return span;
        return count;
    }

    /** Five-column cover-screen layouts trade some whitespace for preserving placement. */
    public static float minimumGridWidthDp(String type, int columns, float availableWidthDp, float textScale) {
        float normal = minimumWidgetWidthDp(type, textScale);
        if (columns < 5 || !Float.isFinite(availableWidthDp) || availableWidthDp >= 400
                || "quick".equals(type)) return normal;
        // Two 48dp editor targets fit side by side. Content retains text fitting/scrolling;
        // larger accessibility text can still request more space without rewriting storage.
        return Math.max(104, normal * Math.max(.7f, availableWidthDp / 400f));
    }

    /** Maximum explicit grid choice; Adaptive remains independent of this finer granularity. */
    public static int maximumColumns(float availableWidthDp) {
        if (!Float.isFinite(availableWidthDp) || availableWidthDp <= 0) return 1;
        if (availableWidthDp >= 480) return MAX_COLUMNS;
        if (availableWidthDp >= 280) return 5;
        return Math.max(1, Math.min(4, (int)(availableWidthDp / 150)));
    }

    /**
     * Copies a rectangle into a finer grid using proportional left/right edges.
     * Flooring shared boundaries preserves adjacent cards without introducing
     * gaps; the default three-to-one split becomes four-to-two in six columns.
     * Saved source rectangles are never modified.
     */
    public static Cell rescale(Cell source, int sourceColumns, int targetColumns) {
        if (source == null) throw new IllegalArgumentException("Missing dashboard cell");
        int from = clamp(sourceColumns, 1, MAX_COLUMNS);
        int to = clamp(targetColumns, 1, MAX_COLUMNS);
        int width = clamp(source.w, 1, from);
        int x = clamp(source.x, 0, from - width);
        int left = x * to / from;
        int right = (x + width) * to / from;
        Cell result = source.copy();
        result.x = Math.min(to - 1, left);
        result.w = clamp(right - left, 1, to - result.x);
        return result;
    }

    /**
     * Resolves a gesture snapshot, then fills empty rows above the other cards.
     * The priority card keeps its bounded requested rectangle throughout the
     * preview. Other cards keep their resolved columns and move upward to the
     * earliest free row, in stable row/column order. The returned cells are
     * independent copies, so cancelling a gesture can reuse its untouched
     * starting snapshot and committing can use this exact preview.
     *
     * Unlike {@link #settle}, this deliberately removes vertical gaps. Keep
     * settle for restoring existing layouts, where deliberate spacing should
     * survive until the user adjusts a widget.
     */
    public static List<Cell> preview(List<Cell> source, int columns, String priorityId) {
        List<Cell> result = settle(source, columns, priorityId);
        List<Cell> ordered = new ArrayList<Cell>(result);
        Collections.sort(ordered, new Comparator<Cell>() {
            @Override public int compare(Cell a, Cell b) {
                int byRow = Integer.compare(a.y, b.y);
                return byRow != 0 ? byRow : Integer.compare(a.x, b.x);
            }
        });

        List<Cell> placed = new ArrayList<Cell>(result.size());
        for (Cell cell : result) {
            if (cell.id.equals(priorityId)) {
                placed.add(cell);
                ordered.remove(cell);
                break;
            }
        }
        for (Cell cell : ordered) {
            int settledRow = cell.y;
            for (int row = 0; row <= settledRow; row++) {
                cell.y = row;
                if (fits(cell, cell.x, placed)) break;
            }
            placed.add(cell);
        }
        return result;
    }

    /** Remove only empty leading rows; preserve all spacing between cards and the source. */
    public static List<Cell> withoutLeadingRows(List<Cell> source) {
        List<Cell> result = new ArrayList<Cell>(source.size());
        int first = Integer.MAX_VALUE;
        for (Cell cell : source) first = Math.min(first, cell.y);
        for (Cell cell : source) {
            Cell copy = cell.copy();
            copy.y -= first;
            result.add(copy);
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
