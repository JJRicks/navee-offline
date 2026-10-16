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
        leadingRows();
        previewFillsVacatedRows();
        previewKeepsFullTargetAndSnapshot();
        previewBoundsAndOrder();
        deterministicPreviews();
        sixColumnLayouts();
        proportionalGridConversion();
        minimumUsableWidths();
        narrowFiveColumnLayouts();
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
        equal(cell("large", 0, 332, 6, 16), find(extreme, "large"), "Large values clamp to bounded saved-row allowance without overflow");
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
        for (int columns = 1; columns <= DashboardLayout.MAX_COLUMNS; columns++) {
            for (int variant = 0; variant < 24; variant++) {
                List<DashboardLayout.Cell> input = new ArrayList<DashboardLayout.Cell>();
                for (int i = 0; i < 12; i++) input.add(cell("card" + i,
                        (i * 3 + variant) % 8 - 1, (i + variant) % 8,
                        1 + (i + variant) % DashboardLayout.MAX_COLUMNS, 1 + (i * 2 + variant) % 5));
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

    private static void leadingRows() {
        List<DashboardLayout.Cell> input = Arrays.asList(cell("a", 0, 8, 1, 2), cell("b", 1, 12, 1, 3));
        List<DashboardLayout.Cell> result = DashboardLayout.withoutLeadingRows(input);
        check(result.get(0).y == 0 && result.get(1).y == 4, "Leading rows removed while intentional inner gaps remain");
        check(input.get(0).y == 8, "Top normalization does not alter its input");
        same(result, DashboardLayout.withoutLeadingRows(result), "Top normalization is stable");
        check(DashboardLayout.withoutLeadingRows(Collections.<DashboardLayout.Cell>emptyList()).isEmpty(), "Empty grid has no leading rows");
        check(DashboardLayout.minimumSpan("quick", 5, 400, 10, 1) == 1, "Icon-only shortcut rail fits one of five columns");
        check(DashboardLayout.minimumSpan("quick", 5, 400, 10, 2) == 1, "Icon-only shortcut rail is independent of text scale");
    }

    private static void previewFillsVacatedRows() {
        List<DashboardLayout.Cell> source = Arrays.asList(
                cell("dragged", 0, 4, 2, 2), cell("next", 0, 2, 2, 2),
                cell("last", 0, 4, 2, 2));
        List<DashboardLayout.Cell> result = DashboardLayout.preview(source, 2, "dragged");
        equal(cell("dragged", 0, 4, 2, 2), find(result, "dragged"),
                "Moving down leaves the full dragged target pinned");
        equal(cell("next", 0, 0, 2, 2), find(result, "next"),
                "Next card fills the vacated top rows");
        equal(cell("last", 0, 2, 2, 2), find(result, "last"),
                "Displaced card compacts into the next available gap");
        check(find(DashboardLayout.settle(source, 2, "dragged"), "next").y == 2,
                "Restore settling still preserves deliberate gaps");
        valid(result, 2);
    }

    private static void previewKeepsFullTargetAndSnapshot() {
        List<DashboardLayout.Cell> snapshot = Arrays.asList(
                cell("wide", 0, 0, 4, 2), cell("left", 0, 2, 2, 2),
                cell("right", 2, 2, 2, 2));
        List<DashboardLayout.Cell> requested = new ArrayList<DashboardLayout.Cell>();
        for (DashboardLayout.Cell cell : snapshot) requested.add(cell.copy());
        requested.get(0).y = 1;
        requested.get(0).h = 3;
        List<DashboardLayout.Cell> result = DashboardLayout.preview(requested, 4, "wide");
        equal(cell("wide", 0, 1, 4, 3), find(result, "wide"),
                "Preview reserves all columns and rows of a resized target");
        check(find(result, "left").y == 4 && find(result, "right").y == 4,
                "Tall cards cannot fit in a one-row gap above the full target");
        valid(result, 4);
        for (int i = 0; i < result.size(); i++)
            check(result.get(i) != requested.get(i), "Preview cells never alias gesture inputs");
        equal(cell("wide", 0, 0, 4, 2), snapshot.get(0),
                "Gesture cancellation can recover the untouched original target");
        equal(cell("left", 0, 2, 2, 2), snapshot.get(1),
                "Gesture cancellation also recovers displaced cards");
        equal(cell("right", 2, 2, 2, 2), requested.get(2),
                "Preview does not progressively mutate the requested snapshot");
        result.get(1).y = 99;
        check(requested.get(1).y == 2, "Editing a returned preview cannot affect the next preview");
    }

    private static void previewBoundsAndOrder() {
        List<DashboardLayout.Cell> source = Arrays.asList(
                cell("target", Integer.MAX_VALUE, Integer.MAX_VALUE,
                        Integer.MAX_VALUE, Integer.MAX_VALUE),
                cell("first", 0, 0, 1, 1), cell("second", 0, 0, 1, 1),
                cell("third", 0, 0, 1, 1));
        List<DashboardLayout.Cell> result = DashboardLayout.preview(source, 2, "target");
        equal(cell("target", 0, 364, 2, 16), find(result, "target"),
                "Preview bounds preserve the same clamped target as normal settling");
        equal(cell("first", 0, 0, 1, 1), find(result, "first"),
                "Equal-position cards retain first-input priority");
        equal(cell("second", 1, 0, 1, 1), find(result, "second"),
                "Equal-position collisions fill the neighboring column");
        equal(cell("third", 0, 1, 1, 1), find(result, "third"),
                "Remaining equal-position collision fills the next row");
        valid(result, 2);
        same(result, DashboardLayout.preview(result, 2, "target"),
                "A committed preview restores to the same compacted arrangement");
        same(DashboardLayout.preview(source, 2, null),
                DashboardLayout.preview(source, 2, "missing"),
                "Unknown preview priority uses normal compaction");
        check(DashboardLayout.preview(Collections.<DashboardLayout.Cell>emptyList(), 2, null).isEmpty(),
                "An empty preview remains empty");
        rejects(() -> DashboardLayout.preview(null, 2, null));
        rejects(() -> DashboardLayout.preview(Arrays.asList(
                cell("same", 0, 0, 1, 1), cell("same", 1, 0, 1, 1)), 2, "same"));
    }

    private static void deterministicPreviews() {
        for (int columns = 1; columns <= DashboardLayout.MAX_COLUMNS; columns++) {
            for (int variant = 0; variant < 16; variant++) {
                List<DashboardLayout.Cell> input = new ArrayList<DashboardLayout.Cell>();
                for (int i = 0; i < 8; i++) input.add(cell("card" + i,
                        (i * 3 + variant) % 8 - 1, (i * 2 + variant) % 14,
                        1 + (i + variant) % DashboardLayout.MAX_COLUMNS, 1 + (i * 2 + variant) % 5));
                String priority = "card" + variant % 8;
                List<DashboardLayout.Cell> first = DashboardLayout.preview(input, columns, priority);
                same(first, DashboardLayout.preview(input, columns, priority),
                        "Repeated gesture preview from the same snapshot is deterministic");
                same(first, DashboardLayout.preview(first, columns, priority),
                        "A settled preview does not keep moving on repeated frames");
                valid(first, columns);
                equal(find(DashboardLayout.settle(input, columns, priority), priority),
                        find(first, priority), "Dense compaction preserves the complete active rectangle");
                for (int i = 0; i < first.size(); i++)
                    check(first.get(i).id.equals(input.get(i).id), "Preview output retains input order");
            }
        }
    }

    private static void sixColumnLayouts() {
        List<DashboardLayout.Cell> dashboard = Arrays.asList(
                cell("scooter", 0, 0, 4, 4), cell("lock", 4, 0, 2, 4),
                cell("battery", 0, 4, 3, 6), cell("chart", 3, 4, 3, 5));
        same(dashboard, DashboardLayout.settle(dashboard, 6, null),
                "Six-column layout keeps the two-thirds scooter/one-third lock and equal halves");
        List<DashboardLayout.Cell> changed = new ArrayList<DashboardLayout.Cell>();
        for (DashboardLayout.Cell c : dashboard) changed.add(c.copy());
        changed.get(1).w = 3;
        changed.get(1).x = 3;
        List<DashboardLayout.Cell> preview = DashboardLayout.preview(changed, 6, "lock");
        equal(cell("lock", 3, 0, 3, 4), find(preview, "lock"),
                "A six-column resize keeps the entire wider lock target");
        valid(preview, 6);
        same(preview, DashboardLayout.settle(preview, 6, null),
                "Saving and settling a six-column preview preserves its placements");
        check(DashboardLayout.settle(Arrays.asList(cell("a", 0, 0, 99, 1)), 99, null).get(0).w == 6,
                "Column counts above the supported maximum cannot produce unusable extra columns");
    }

    private static void proportionalGridConversion() {
        DashboardLayout.Cell scooter = cell("scooter", 0, 0, 3, 4);
        DashboardLayout.Cell lock = cell("lock", 3, 0, 1, 4);
        equal(cell("scooter", 0, 0, 4, 4), DashboardLayout.rescale(scooter, 4, 6),
                "Existing three-quarter scooter becomes two-thirds on six columns");
        equal(cell("lock", 4, 0, 2, 4), DashboardLayout.rescale(lock, 4, 6),
                "Existing one-quarter lock receives two columns on six columns");
        equal(cell("scooter", 0, 0, 3, 4), DashboardLayout.rescale(scooter, 4, 5),
                "Five-column scooter keeps three columns");
        equal(cell("lock", 3, 0, 2, 4), DashboardLayout.rescale(lock, 4, 5),
                "Five-column lock gains the remaining two columns");
        equal(cell("scooter", 0, 0, 3, 4), scooter, "Converting keeps source geometry unchanged");
        equal(cell("lock", 3, 0, 1, 4), lock, "Converting does not mutate another source");
        for (int from = 1; from <= DashboardLayout.MAX_COLUMNS; from++) {
            for (int to = from; to <= DashboardLayout.MAX_COLUMNS; to++) {
                List<DashboardLayout.Cell> cells = new ArrayList<DashboardLayout.Cell>();
                for (int x = 0; x < from; x++)
                    cells.add(DashboardLayout.rescale(cell("card" + x, x, 3, 1, 5), from, to));
                valid(cells, to);
                int next = 0;
                for (DashboardLayout.Cell c : cells) {
                    check(c.x == next && c.y == 3 && c.h == 5,
                            "Finer grids preserve shared edges, row and height without gaps");
                    next = c.x + c.w;
                }
                check(next == to, "Converted adjacent cards fill the complete new grid");
            }
        }
        equal(cell("large", 0, -4, 6, 3), DashboardLayout.rescale(
                cell("large", Integer.MAX_VALUE, -4, Integer.MAX_VALUE, 3), 4, 6),
                "Conversion bounds horizontal geometry without overflow and leaves rows to settle");
        rejects(() -> DashboardLayout.rescale(null, 4, 6));
    }

    private static void narrowFiveColumnLayouts() {
        for (int screen : new int[]{320, 344, 360, 390, 432}) {
            float grid = screen - 36;
            check(DashboardLayout.maximumColumns(grid) == 5, "Cover screens retain five explicit columns");
            check(DashboardLayout.minimumSpan("lock", 5, grid, 10, 1) == 2,
                    "Default lock keeps two of five columns on narrow phones");
            check(DashboardLayout.minimumSpan("scooter", 5, grid, 10, 1) <= 3,
                    "Default scooter preserves its three-column position");
            float step = (grid + 10) / 5;
            check(Math.abs(5 * step - 10 - grid) < .001,
                    "The last card ends at the grid edge without an extra trailing gap");
        }
        check(DashboardLayout.maximumColumns(480) == 6, "Wider screens retain six-column editing");
        check(DashboardLayout.maximumColumns(240) == 1, "Exceptionally narrow windows keep safe bounds");
        check(DashboardLayout.maximumColumns(Float.NaN) == 1, "Invalid measurement stays bounded");
    }

    private static void minimumUsableWidths() {
        String[] types = {"lock", "scooter", "battery", "metric", "gauge", "live", "history",
                "readings", "trip", "health", "capacity", "charging", "quick", "ride", "note",
                "lockbar", "overview", "tiles", "shortcuts"};
        check(DashboardLayout.minimumSpan("lock", 6, 600, 10, 1) == 2,
                "Two-sixths lock is usable on a compact unfolded screen");
        check(DashboardLayout.minimumSpan("scooter", 6, 600, 10, 1) <= 4,
                "Four-sixths scooter is not rejected by its physical width floor");
        check(DashboardLayout.minimumSpan("lock", 6, 462, 10, 1) == 2,
                "An exactly matching minimum width does not gain an extra column");
        for (String type : types) {
            check(DashboardLayout.minimumSpan(type, 2, 344, 10, 1) == 1,
                    "Existing reasonable half-width " + type + " cards still fit");
            check(DashboardLayout.minimumWidgetWidthDp(type, 1) >= (type.equals("quick") ? 58 : 144),
                    "Every widget has room for three independent editor touch targets");
            for (int columns = 1; columns <= DashboardLayout.MAX_COLUMNS; columns++) {
                for (int width : new int[] {1, 280, 320, 600, 852, 1440}) {
                    for (float scale : new float[] {1, 1.3f, 1.4f, 1.82f, 2}) {
                        int span = DashboardLayout.minimumSpan(type, columns, width, 10, scale);
                        float floor = DashboardLayout.minimumGridWidthDp(type, columns, width, scale);
                        check(span >= 1 && span <= columns, "Physical width floor returns a bounded span");
                        check(span == columns || span * (double) (width + 10) / columns - 10 >= floor - .0001,
                                "Constrained cards meet their usable width whenever space permits");
                        check(span == 1 || (span - 1) * (double) (width + 10) / columns - 10 < floor + .0001,
                                "Constrained cards do not consume an unnecessary additional column");
                        check(span >= DashboardLayout.minimumSpan(type, columns, width, 10, 1),
                                "Larger text never lowers a widget's minimum span");
                    }
                }
            }
        }
        check(DashboardLayout.minimumSpan("lock", 6, Float.NaN, 10, 1) == 6
                        && DashboardLayout.minimumSpan("lock", 6, 0, 10, 1) == 6,
                "Unmeasured or invalid width uses full width until a real measurement arrives");
        check(DashboardLayout.minimumWidgetWidthDp("lock", Float.NaN)
                        == DashboardLayout.minimumWidgetWidthDp("lock", 1),
                "Invalid text scale cannot make minimum widths NaN");
        check(DashboardLayout.minimumSpan("lock", 6, 600, Float.NaN, 1) == 2,
                "Invalid gap falls back to a bounded zero gap");
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
