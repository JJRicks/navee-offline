package local.navee.offlinelock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Gesture-independent layout fixtures: no Android, device, BLE, or random inputs. */
public final class DashboardLayoutTest {
    private static int assertions;

    public static void main(String[] args) {
        preservesPositionsAndCopies();
        movingAndResizing();
        nearestColumnsAndStableOrder();
        boundsAndTallLayouts();
        invalidInputs();
        overlapEdges();
        deterministicLayouts();
        System.out.println("DashboardLayout tests passed: " + assertions + " assertions");
    }

    private static void preservesPositionsAndCopies() {
        List<DashboardLayout.Cell> source = Arrays.asList(
                cell("right", 2, 0, 2, 2), cell("left", 0, 0, 2, 2),
                cell("bottom", 1, 4, 2, 3));
        List<DashboardLayout.Cell> settled = DashboardLayout.settle(source, 4, null);
        check(settled != source, "New list returned");
        for (int i = 0; i < source.size(); i++) {
            check(settled.get(i) != source.get(i), "Each card is copied");
            equal(source.get(i), settled.get(i), "Free position and input order retained");
        }
        settled.get(0).x = 0;
        check(source.get(0).x == 2, "Result edits cannot mutate original cards");
        DashboardLayout.Cell copy = source.get(2).copy();
        equal(copy, source.get(2), "Cell copy preserves fields");
        copy.h = 1;
        check(source.get(2).h == 3, "Cell copy is independent");
    }

    private static void movingAndResizing() {
        List<DashboardLayout.Cell> moved = DashboardLayout.settle(Arrays.asList(
                cell("old-left", 0, 0, 2, 2), cell("old-right", 2, 0, 2, 2),
                cell("dragged", 0, 0, 2, 2)), 4, "dragged");
        equal(cell("dragged", 0, 0, 2, 2), find(moved, "dragged"), "Dragged target remains pinned");
        equal(cell("old-left", 2, 0, 2, 2), find(moved, "old-left"), "First collision fills same-row space");
        equal(cell("old-right", 2, 2, 2, 2), find(moved, "old-right"), "Next collision moves below occupied rows");
        valid(moved, 4);

        List<DashboardLayout.Cell> resized = DashboardLayout.settle(Arrays.asList(
                cell("a", 0, 0, 4, 3), cell("b", 2, 0, 2, 1),
                cell("c", 0, 1, 2, 1), cell("d", 0, 5, 4, 1)), 4, "a");
        equal(cell("a", 0, 0, 4, 3), find(resized, "a"), "Resized card keeps requested dimensions");
        check(find(resized, "b").y == 3 && find(resized, "c").y == 3,
                "Full-width resize moves colliding cards below its complete height");
        check(find(resized, "d").y == 5, "Unrelated later card keeps its free location");
        valid(resized, 4);
        same(resized, DashboardLayout.settle(resized, 4, "a"), "Settled ordinary grid is stable");
    }

    private static void nearestColumnsAndStableOrder() {
        List<DashboardLayout.Cell> nearest = DashboardLayout.settle(Arrays.asList(
                cell("priority", 1, 0, 1, 1), cell("collision", 1, 0, 1, 1)), 4, "priority");
        check(find(nearest, "collision").x == 0, "Equal-distance columns prefer left");
        List<DashboardLayout.Cell> right = DashboardLayout.settle(Arrays.asList(
                cell("block", 0, 0, 2, 2), cell("collision", 1, 0, 1, 1)), 4, "block");
        check(find(right, "collision").x == 2 && find(right, "collision").y == 0,
                "Nearest right column is used before moving down");
        List<DashboardLayout.Cell> ties = DashboardLayout.settle(Arrays.asList(
                cell("first", 0, 0, 1, 1), cell("second", 0, 0, 1, 1),
                cell("third", 0, 0, 1, 1)), 2, null);
        check(find(ties, "first").x == 0 && find(ties, "second").x == 1
                && find(ties, "third").y == 1, "Equal requested positions preserve input precedence");
        same(ties, DashboardLayout.settle(Arrays.asList(
                cell("first", 0, 0, 1, 1), cell("second", 0, 0, 1, 1),
                cell("third", 0, 0, 1, 1)), 2, "missing"), "Unknown priority has normal placement");
    }

    private static void boundsAndTallLayouts() {
        List<DashboardLayout.Cell> extreme = DashboardLayout.settle(Arrays.asList(
                cell("large", Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE),
                cell("small", Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE)),
                Integer.MAX_VALUE, "large");
        equal(cell("large", 0, 332, 4, 16), find(extreme, "large"), "Large values clamp to bounded saved-row allowance without overflow");
        equal(cell("small", 0, 0, 1, 1), find(extreme, "small"), "Negative values clamp to usable card geometry");
        List<DashboardLayout.Cell> narrow = DashboardLayout.settle(Arrays.asList(
                cell("a", 3, 0, 4, 2), cell("b", 2, 0, 2, 1)), 0, null);
        valid(narrow, 1);
        check(narrow.get(0).w == 1 && narrow.get(1).w == 1, "Columns below minimum clamp to one");

        List<DashboardLayout.Cell> stacked = new ArrayList<DashboardLayout.Cell>();
        for (int i = 0; i < 32; i++) stacked.add(cell("tall" + i, 0, 300, 4, 16));
        List<DashboardLayout.Cell> settled = DashboardLayout.settle(stacked, 4, null);
        check(settled.size() == 32, "No cards lost when requested rows are full");
        check(settled.get(31).y == 300 + 31 * 16, "Displaced cards can extend below requested-row clamp");
        valid(settled, 4);
        check(stacked.get(31).y == 300, "Displacement never mutates source");
        same(settled, DashboardLayout.settle(settled, 4, null),
                "Restoring all 32 displaced tall cards preserves their saved rows");

        // A wide card is forced below row 300 while a later narrow card fits
        // above it. Re-clamping saved rows to 300 would change placement order
        // and displace the narrow card on the second normalization.
        List<DashboardLayout.Cell> boundary = DashboardLayout.settle(Arrays.asList(
                cell("a", 0, 300, 1, 1), cell("b", 0, 300, 4, 1),
                cell("c", 0, 300, 1, 1), cell("d", 0, 300, 3, 1)), 4, null);
        check(find(boundary, "b").y == 301 && find(boundary, "c").y == 300
                && find(boundary, "d").y == 302, "Mixed-width boundary fixture actually displaces cards below row 300");
        same(boundary, DashboardLayout.settle(boundary, 4, null),
                "Persisted mixed-width layout below row 300 is idempotent");
    }

    private static void invalidInputs() {
        check(DashboardLayout.settle(Collections.<DashboardLayout.Cell>emptyList(), 2, null).isEmpty(),
                "Empty dashboard remains empty");
        rejects(() -> DashboardLayout.settle(null, 2, null));
        rejects(() -> DashboardLayout.settle(Arrays.asList((DashboardLayout.Cell) null), 2, null));
        rejects(() -> DashboardLayout.settle(Arrays.asList(cell(null, 0, 0, 1, 1)), 2, null));
        rejects(() -> DashboardLayout.settle(Arrays.asList(cell("  ", 0, 0, 1, 1)), 2, null));
        rejects(() -> DashboardLayout.settle(Arrays.asList(
                cell("same", 0, 0, 1, 1), cell("same", 1, 0, 1, 1)), 2, null));
    }

    private static void overlapEdges() {
        DashboardLayout.Cell base = cell("base", 0, 0, 2, 2);
        check(DashboardLayout.overlaps(base, cell("inside", 1, 1, 1, 1)), "Contained rectangle overlaps");
        check(!DashboardLayout.overlaps(base, cell("right", 2, 0, 1, 1)), "Touching horizontal edge is free");
        check(!DashboardLayout.overlaps(base, cell("below", 0, 2, 1, 1)), "Touching vertical edge is free");
        check(!DashboardLayout.overlaps(base, cell("empty", 1, 1, 0, 2)), "Zero-area rectangle is empty");
        check(DashboardLayout.overlaps(cell("huge", Integer.MAX_VALUE - 1, 0, 4, 1),
                cell("within", Integer.MAX_VALUE, 0, 1, 1)), "Overlap arithmetic does not overflow");
        rejects(() -> DashboardLayout.overlaps(base, null));
    }

    private static void deterministicLayouts() {
        // Exhaustive, repeatable small-grid gesture cases, including mixed sizes
        // and narrow-screen conversions. No random seed can hide a regression.
        for (int columns = 1; columns <= 4; columns++) {
            for (int variant = 0; variant < 24; variant++) {
                List<DashboardLayout.Cell> input = new ArrayList<DashboardLayout.Cell>();
                for (int i = 0; i < 12; i++) input.add(cell("card" + i,
                        (i * 3 + variant) % 6 - 1, (i + variant) % 8,
                        1 + (i + variant) % 4, 1 + (i * 2 + variant) % 5));
                String priority = "card" + variant % 12;
                List<DashboardLayout.Cell> first = DashboardLayout.settle(input, columns, priority);
                List<DashboardLayout.Cell> second = DashboardLayout.settle(input, columns, priority);
                same(first, second, "Repeated settling is deterministic");
                valid(first, columns);
                DashboardLayout.Cell requested = find(input, priority), pinned = find(first, priority);
                int width = Math.min(columns, requested.w);
                check(pinned.x == Math.max(0, Math.min(columns - width, requested.x))
                        && pinned.y == requested.y && pinned.w == width && pinned.h == requested.h,
                        "Priority rectangle survives dense collision resolution");
                for (int i = 0; i < first.size(); i++)
                    check(first.get(i).id.equals(input.get(i).id), "Returned order matches input IDs");
            }
        }
    }

    private static DashboardLayout.Cell cell(String id, int x, int y, int w, int h) {
        return new DashboardLayout.Cell(id, x, y, w, h);
    }

    private static DashboardLayout.Cell find(List<DashboardLayout.Cell> cells, String id) {
        for (DashboardLayout.Cell cell : cells) if (cell.id.equals(id)) return cell;
        throw new AssertionError("Missing " + id);
    }

    private static void valid(List<DashboardLayout.Cell> cells, int columns) {
        for (int i = 0; i < cells.size(); i++) {
            DashboardLayout.Cell cell = cells.get(i);
            check(cell.x >= 0 && cell.y >= 0 && cell.w >= 1 && cell.h >= 1
                    && cell.w <= columns && cell.h <= 16 && cell.x + cell.w <= columns,
                    "Settled rectangle is inside column bounds");
            for (int j = 0; j < i; j++)
                check(!DashboardLayout.overlaps(cell, cells.get(j)), "Settled rectangles never overlap");
        }
    }

    private static void same(List<DashboardLayout.Cell> expected,
            List<DashboardLayout.Cell> actual, String message) {
        check(expected.size() == actual.size(), message + " size");
        for (int i = 0; i < expected.size(); i++) equal(expected.get(i), actual.get(i), message);
    }

    private static void equal(DashboardLayout.Cell expected, DashboardLayout.Cell actual, String message) {
        check(expected.id.equals(actual.id) && expected.x == actual.x && expected.y == actual.y
                && expected.w == actual.w && expected.h == actual.h, message);
    }

    private static void rejects(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { assertions++; return; }
        throw new AssertionError("Expected invalid dashboard input to be rejected");
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
