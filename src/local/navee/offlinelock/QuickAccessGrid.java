package local.navee.offlinelock;

import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.function.Consumer;

/**
 * Height-aware shortcuts: use available room without clipping text or shrinking touch targets.
 * The same catalog, values and actions also drive Setting tiles: separate cards with a label over
 * the current value and a power cue for toggles or a chevron for panels, the first one highlighted while connected.
 */
final class QuickAccessGrid extends ViewGroup {
    private final ScreenHost host;
    private final Ui ui;
    private final boolean editing, tiles;
    private final ArrayList<Cell> cells = new ArrayList<>();
    private int budget, columns = 1, rowHeight, fittedWidth = -1, fittedBudget = -1;
    private boolean iconsOnly, tileLabels = true, tileCompact;

    QuickAccessGrid(ScreenHost host, List<String> ids, boolean editing, Consumer<String> action) {
        this(host, ids, editing, action, false);
    }

    /** {@code tiles} selects the Setting tiles presentation; callers normalize its one-to-four ids. */
    QuickAccessGrid(ScreenHost host, List<String> ids, boolean editing, Consumer<String> action, boolean tiles) {
        super(host.activity());
        this.host = host; this.ui = host.ui(); this.editing = editing; this.tiles = tiles;
        setTag(tiles ? "setting-tiles" : "quick-access-grid");
        List<String> shown = tiles ? ids : QuickAccessActions.normalize(ids);
        for (String id : shown) {
            if (QuickAccessActions.find(id) == null) continue;
            Cell cell = new Cell(QuickAccessActions.find(id));
            cell.setOnClickListener(v -> { if (!editing) action.accept(id); });
            cells.add(cell); addView(cell);
        }
        refresh();
    }

    void fitHeight(int height) { budget = Math.max(0, height); }

    void refresh() {
        ScooterService service = host.service();
        BleController ble = service == null ? null : service.ble;
        boolean live = ble != null && ble.ready();
        Map<String, Integer> values = service == null ? Collections.emptyMap() : service.displayFeatures();
        android.content.SharedPreferences preferences = host.activity().getSharedPreferences("app_settings", 0);
        String units = preferences.getString("units", "mi");
        ChargeScheduleController.View charge = service == null ? null : service.chargeSchedule();
        for (int index = 0; index < cells.size(); index++) {
            Cell cell = cells.get(index);
            ScooterCommands.Feature feature = cell.entry.feature();
            boolean doneBy = cell.entry.id.equals("charge_done_by");
            boolean doneTime = cell.entry.id.equals("charge_done_time");
            boolean manual = cell.entry.id.equals("feature:timed_charging");
            boolean startTime = cell.entry.id.equals("feature:charge_start");
            boolean chargeControl = doneBy || doneTime || manual || startTime;
            boolean featureLive = feature != null && live && (feature.type == ScooterCommands.Type.TIME
                    ? service.readingFresh("scheduled_charge_time_raw") : service.featureFresh(feature.key));
            Integer value = feature == null || feature.sensitive ? null : values.get(feature.key);
            if (feature != null && !feature.sensitive && live) {
                Integer fresh = ble.featureValue(feature.key);
                if (fresh != null) value = fresh;
            }
            boolean pending = feature != null && ble != null && feature.key.equals(ble.pending());
            // The resolver's decision for this model; an unmapped tile stays in the saved layout, muted.
            CapabilityResolver.Decision decision = feature == null || ble == null ? null : ble.decision(feature.key);
            boolean unmapped = decision != null && !feature.key.equals("range_algorithm")
                    && decision.support != CapabilityResolver.Support.KNOWN_ENCODING && !ble.controlOffered(feature.key);
            if (unmapped) { value = null; featureLive = false; }
            String state = feature == null || feature.sensitive && !unmapped ? ""
                    : unmapped ? "—" : pending ? "Updating…" : value == null ? "—"
                    : decision != null && decision.oemLabel(value) != null ? decision.oemLabel(value)
                    : DisplayUnits.featureValue(feature, value, units);
            if (feature != null && feature.key.equals("range_algorithm")) {
                featureLive = service != null && service.profiles.active() != null;
                state = featureLive && service.usesLearnedRange() ? "Volt (learned)" : "Scooter";
            }
            String chargeBlocked = charge == null ? (doneTime ? "Select a scooter first" : "Connect a charger first") : doneTime ? charge.timeBlockedReason
                    : doneBy ? charge.doneByBlockedReason : startTime ? charge.manualTimeBlockedReason : charge.manualBlockedReason;
            // An armed plan deliberately pauses charging; its own off action remains available.
            if (doneBy && charge != null && charge.doneByEnabled)
                chargeBlocked = !live ? "Connect your scooter first" : charge.busy ? "Wait for the current change to finish" : null;
            if (chargeControl && charge != null && !unmapped) {
                value = (doneBy || doneTime ? charge.doneByEnabled : charge.manualEnabled) ? 1 : 0;
                pending = charge.busy;
                featureLive = (doneTime || live) && chargeBlocked == null;
                if (doneBy || doneTime) {
                    Calendar deadline = Calendar.getInstance();
                    deadline.set(Calendar.HOUR_OF_DAY, charge.doneByMinuteOfDay / 60);
                    deadline.set(Calendar.MINUTE, charge.doneByMinuteOfDay % 60);
                    String time = DisplayTime.clock(host.activity(), preferences, deadline.getTimeInMillis());
                    String activeTime = charge.doneByWallMs > 0
                            ? DisplayTime.clock(host.activity(), preferences, charge.doneByWallMs) : time;
                    state = pending ? "Updating…" : doneTime ? time : charge.doneByEnabled ? "On • " + activeTime : "Off";
                } else if (manual) state = pending ? "Updating…" : charge.manualEnabled ? "On" : "Off";
                else state = charge.manualStartWallMs > 0 ? DisplayTime.clock(host.activity(), preferences, charge.manualStartWallMs) : "Not scheduled";
            } else if (doneBy || doneTime) state = "—";
            if (manual && !unmapped && charge != null && charge.manualEnabled && charge.startWallMs > 0 && !pending)
                state += " • " + DisplayTime.clock(host.activity(), preferences, charge.startWallMs);
            // Another model's mapping can make a UT5 MAX Lab control an ordinary toggle.
            boolean toggle = cell.entry.directToggle(decision);
            if (tiles && toggle != cell.toggle) { cell.toggle(toggle); fittedWidth = -1; }
            boolean selected = toggle && value != null && value == 1 && featureLive;
            if (tiles) {
                // A page or menu shortcut has no value; its name takes the value line instead.
                String shownValue = state.isEmpty() ? tileLabel(cell.entry) : state;
                if (!shownValue.contentEquals(cell.value.getText())) {
                    cell.value.setText(shownValue);
                    fittedWidth = -1;
                }
                cell.label.setTag(state.isEmpty() ? "" : tileLabel(cell.entry));
                // The lead tile's accent fill would make an unmapped control look active; it stays muted.
                boolean highlight = index == 0 && (live || doneTime) && !editing && !unmapped && (!chargeControl || chargeBlocked == null);
                boolean muted = (feature != null || chargeControl) && !featureLive;
                cell.label.setTextColor(highlight ? Ui.withAlpha(ui.onAccent, 220) : ui.muted);
                cell.value.setTextColor(highlight ? ui.onAccent : muted ? ui.muted : ui.text);
                if (toggle) {
                    boolean available = !editing && featureLive && value != null && (chargeControl
                            ? charge != null && !charge.busy : ble.featureValue(feature.key) != null
                            && decision != null && decision.available());
                    ((TileAction) cell.icon).appearance(selected && available ? ui.accent : ui.muted, available, highlight);
                } else ((TileAction) cell.icon).appearance(highlight ? ui.onAccent : ui.muted, !editing, false);
                if (cell.highlighted != highlight || cell.isSelected() != selected) {
                    cell.highlighted = highlight; cell.setSelected(selected); cell.updateBackground();
                }
                cell.label.setVisibility(tileLabels && !state.isEmpty() ? VISIBLE : GONE);
            } else {
                if (!state.contentEquals(cell.value.getText())) cell.value.setText(state);
                cell.value.setVisibility(iconsOnly || state.isEmpty() ? GONE : VISIBLE);
                cell.value.setTag(state);
                cell.value.setTextColor(featureLive ? ui.text : ui.muted);
                if (cell.isSelected() != selected) { cell.setSelected(selected); cell.updateBackground(); }
                cell.setAlpha((feature != null || chargeControl) && !featureLive ? .65f : 1f);
            }
            cell.setEnabled(!editing);
            String description = (tiles ? tileLabel(cell.entry) : cell.entry.label) + (state.isEmpty() ? "" : ", " + state)
                    + (unmapped ? ", not on this model" : chargeControl && chargeBlocked != null ? ", " + chargeBlocked
                            : feature != null && !featureLive ? ", last seen" : "")
                    + (toggle ? ", toggle" : ", open");
            cell.setContentDescription(description);
            if (android.os.Build.VERSION.SDK_INT >= 30) cell.setStateDescription(state);
        }
    }

    /** Decorative cue within the tile's single touch target and accessibility action. */
    private final class TileAction extends View {
        String glyph;
        final android.graphics.Paint ring = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        int color;
        boolean highlighted;
        TileAction(boolean toggle) {
            super(host.activity()); color = ui.muted; kind(toggle);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        void kind(boolean toggle) {
            glyph = toggle ? "power" : "chevron";
            setTag("setting-action:" + glyph);
            invalidate();
        }
        void appearance(int next, boolean available, boolean onHighlight) {
            setEnabled(available); setAlpha(available ? 1f : .45f);
            if (next != color || highlighted != onHighlight) { color = next; highlighted = onHighlight; invalidate(); }
        }
        @Override protected void onDraw(android.graphics.Canvas canvas) {
            float size = Math.min(getWidth(), getHeight());
            if (size <= 0) return;
            float x = getWidth() / 2f, y = getHeight() / 2f;
            if (glyph.equals("power")) {
                float stroke = ui.dp(1), radius = (size - stroke) / 2f;
                if (highlighted) {
                    // The first tile has an accent background; retain a neutral interior so On stays legible.
                    ring.setStyle(android.graphics.Paint.Style.FILL); ring.setColor(ui.surface);
                    canvas.drawCircle(x, y, radius, ring);
                }
                ring.setStyle(android.graphics.Paint.Style.STROKE); ring.setStrokeWidth(stroke);
                ring.setColor(Ui.withAlpha(highlighted ? ui.onAccent : color, 90));
                canvas.drawCircle(x, y, radius, ring);
                size *= .55f;
            }
            ui.drawIcon(canvas, glyph, color, x, y, size);
        }
    }

    private final class Cell extends LinearLayout {
        final QuickAccessActions.Entry entry;
        final View icon;
        final TextView label, value;
        boolean highlighted, toggle;
        Cell(QuickAccessActions.Entry entry) {
            super(host.activity()); this.entry = entry; toggle = entry.directToggle();
            // Keep the fill, pressed ripple and clipping on the same rounded boundary.
            // Narrow rows must not depend on a device-specific RippleDrawable outline
            // when their pressed/selected state or dimensions change.
            setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), ui.dp(cornerRadius()));
                }
            });
            setClipToOutline(true);
            if (tiles) {
                setTag("setting-tile:" + entry.id);
                setOrientation(HORIZONTAL); setGravity(Gravity.CENTER_VERTICAL);
                setClickable(true); setFocusable(true); setMinimumHeight(ui.dp(48));
                LinearLayout words = new LinearLayout(host.activity());
                words.setOrientation(VERTICAL);
                label = ui.text(tileLabel(entry), 12.5f, false);
                label.setTextColor(ui.muted); label.setMaxLines(1);
                label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                label.setLineSpacing(0, 1);
                words.addView(label, new LinearLayout.LayoutParams(-1, -2));
                value = ui.text("", 18, true);
                value.setMaxLines(1); value.setEllipsize(android.text.TextUtils.TruncateAt.END);
                value.setLineSpacing(0, 1);
                words.addView(value, new LinearLayout.LayoutParams(-1, -2));
                addView(words, new LinearLayout.LayoutParams(0, -2, 1));
                icon = new TileAction(toggle);
                int side = ui.dp(toggle ? 28 : 16);
                LinearLayout.LayoutParams indicator = new LinearLayout.LayoutParams(side, side);
                indicator.setMarginStart(ui.dp(6));
                addView(icon, indicator);
                setTooltipText(entry.label); updateBackground();
                for (View child : new View[]{words, label, value, icon})
                    child.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                return;
            }
            setTag("quick-action:" + entry.id);
            setOrientation(VERTICAL); setGravity(Gravity.CENTER);
            setClickable(true); setFocusable(true); setMinimumHeight(ui.dp(48));
            icon = ui.icon(entry.icon, 20, ui.accent);
            addView(icon, new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)));
            label = ui.text(entry.label, 12, false);
            label.setGravity(Gravity.CENTER); label.setIncludeFontPadding(false);
            label.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);
            addView(label, new LinearLayout.LayoutParams(-1, -2));
            value = ui.text("", 11, false); value.setTypeface(ui.medium());
            value.setGravity(Gravity.CENTER); value.setIncludeFontPadding(false);
            addView(value, new LinearLayout.LayoutParams(-1, -2));
            setTooltipText(entry.label); updateBackground();
            // The grouped button announces both its label and the current state once.
            for (View child : new View[]{icon, label, value})
                child.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        /** Setting tiles only: the cue and its size follow the connected model's decision. */
        void toggle(boolean next) {
            toggle = next; ((TileAction) icon).kind(next);
            int side = ui.dp(next ? 28 : 16);
            icon.getLayoutParams().width = side; icon.getLayoutParams().height = side;
            icon.requestLayout();
        }
        void updateBackground() {
            float radius = cornerRadius();
            if (tiles) {
                // White on accentDeep stays above 4.5:1 in both themes.
                int fill = highlighted ? ui.accentDeep : isSelected() ? blendAccent() : editing ? ui.raised : ui.surface;
                setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(highlighted ? ui.onAccent : ui.accent, 40)),
                        ui.shape(fill, radius, isSelected() && !highlighted ? Ui.withAlpha(ui.accent, 100) : 0),
                        ui.shape(android.graphics.Color.WHITE, radius, 0)));
                return;
            }
            setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 40)),
                    ui.shape(isSelected() ? Ui.withAlpha(ui.accent, 35) : ui.raised, radius,
                            isSelected() ? Ui.withAlpha(ui.accent, 100) : 0),
                    ui.shape(android.graphics.Color.WHITE, radius, 0)));
        }
        private float cornerRadius() { return tiles ? 20 : 12; }
        int blendAccent() {
            float a = ui.dark ? .14f : .08f;
            int from = ui.surface, to = ui.accent;
            return android.graphics.Color.rgb(
                    Math.round(android.graphics.Color.red(from) + (android.graphics.Color.red(to) - android.graphics.Color.red(from)) * a),
                    Math.round(android.graphics.Color.green(from) + (android.graphics.Color.green(to) - android.graphics.Color.green(from)) * a),
                    Math.round(android.graphics.Color.blue(from) + (android.graphics.Color.blue(to) - android.graphics.Color.blue(from)) * a));
        }
        /** Setting tile content at this width: value shrinks to fit, action cue and label drop when needed. */
        void measureTile(int width, boolean refit, int rowLimit) {
            if (!refit) {
                measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                return;
            }
            float factor = Math.max(1, ui.textScale * getResources().getConfiguration().fontScale);
            icon.setVisibility(width >= ui.dp(110 * factor) ? VISIBLE : GONE);
            label.setVisibility(tileLabels && !"".equals(label.getTag()) ? VISIBLE : GONE);
            int vertical = ui.dp(tileCompact ? 3 : 8);
            setPadding(ui.dp(14), vertical, ui.dp(12), vertical);
            int text = width - getPaddingLeft() - getPaddingRight()
                    - (icon.getVisibility() == VISIBLE ? icon.getLayoutParams().width + ui.dp(6) : 0);
            // Label and value are single lines without extra spacing, so their font metrics are their heights.
            int room = rowLimit - getPaddingTop() - getPaddingBottom() - (label.getVisibility() == VISIBLE
                    ? label.getPaint().getFontMetricsInt().descent - label.getPaint().getFontMetricsInt().ascent : 0);
            float size = 18 * ui.textScale, floor = 12 * ui.textScale;
            // A long scheduled time gets the space before its decorative cue does.
            value.setTextSize(floor);
            if (toggle && icon.getVisibility() == VISIBLE
                    && value.getPaint().measureText(value.getText().toString()) > text) {
                icon.setVisibility(GONE);
                text += icon.getLayoutParams().width + ui.dp(6);
            }
            value.setTextSize(size);
            while (size > floor && (value.getPaint().measureText(value.getText().toString()) > text
                    || value.getPaint().getFontMetricsInt().descent - value.getPaint().getFontMetricsInt().ascent > room)) {
                size -= .5f;
                value.setTextSize(size);
            }
            measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        }
        void measureContent(int width, boolean compact) {
            label.setVisibility(iconsOnly ? GONE : VISIBLE);
            icon.setAlpha(editing && iconsOnly ? .22f : 1f);
            value.setVisibility(iconsOnly || "".equals(value.getTag()) ? GONE : VISIBLE);
            setPadding(ui.dp(4), ui.dp(compact ? 3 : 7), ui.dp(4), ui.dp(compact ? 3 : 7));
            int side = ui.dp(compact ? 18 : 20);
            icon.getLayoutParams().width = icon.getLayoutParams().height = side;
            ((LinearLayout.LayoutParams)label.getLayoutParams()).topMargin = ui.dp(compact ? 2 : 4);
            ((LinearLayout.LayoutParams)value.getLayoutParams()).topMargin = ui.dp(2);
            label.setTextSize((compact ? 11 : 12) * ui.textScale);
            value.setTextSize((compact ? 10 : 11) * ui.textScale);
            measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            // Two lines are preferred; long labels may grow instead of being truncated.
            if (label.getLineCount() > 2) {
                label.setTextSize(10 * ui.textScale);
                measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            }
        }
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        if (tiles) { measureTiles(MeasureSpec.getSize(widthSpec)); return; }
        int width = MeasureSpec.getSize(widthSpec), gap = ui.dp(6);
        iconsOnly = width < ui.dp(100);
        columns = Math.min(Math.min(4, cells.size()), Math.max(1,
                (width + gap) / Math.max(1, ui.dp(72 * ui.textScale) + gap)));
        if (cells.size() == 4 && columns == 3) columns = 2;
        int cellWidth = Math.max(1, (width - (columns - 1) * gap) / columns);
        int rows = (cells.size() + columns - 1) / columns;
        int natural = measureCells(cellWidth, false);
        int required = rows * natural + (rows - 1) * gap;
        if (budget > 0 && required > budget) {
            natural = measureCells(cellWidth, true);
            required = rows * natural + (rows - 1) * gap;
        }
        // Give back a few redundant padding pixels, never crop a label to hide a scrollbar.
        rowHeight = natural;
        if (budget >= required) rowHeight = Math.max(natural, (budget - (rows - 1) * gap) / rows);
        for (Cell cell : cells) cell.measure(MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY));
        setMeasuredDimension(width, rows * rowHeight + (rows - 1) * gap);
    }

    /** Separate cards: wrap below ~88 dp of text each when the height holds the rows, else stay on one row and drop labels. */
    private void measureTiles(int width) {
        int gap = ui.dp(10), count = cells.size();
        if (count == 0) { setMeasuredDimension(width, 0); return; }
        float factor = Math.max(1, ui.textScale * getResources().getConfiguration().fontScale);
        // Wrap only into rows the height can hold (48 dp each); a one-row card keeps every tile on one row.
        int maxRows = budget > 0 ? Math.max(1, (budget + gap) / (ui.dp(48) + gap)) : count;
        columns = count;
        while (columns > 1 && (width - (columns - 1) * gap) / columns < ui.dp(88 * factor)) {
            int next = columns == 4 ? 2 : columns - 1;
            if ((count + next - 1) / next > maxRows) break;
            columns = next;
        }
        int rows = (count + columns - 1) / columns;
        int cellWidth = Math.max(1, (width - (columns - 1) * gap) / columns);
        // Too narrow for a label beside the value: show the value alone (the description keeps the label).
        boolean cramped = columns > 1 && cellWidth < ui.dp(88 * factor);
        int perRow = budget > 0 ? (budget - (rows - 1) * gap) / rows : Integer.MAX_VALUE;
        int natural;
        if (width != fittedWidth || budget != fittedBudget) {
            // Choose once per size so visibility and size changes do not request layout on every pass.
            fittedWidth = width; fittedBudget = budget;
            tileLabels = !cramped; tileCompact = false;
            natural = measureTileCells(cellWidth, true, perRow);
            if (natural > perRow) { tileCompact = true; natural = measureTileCells(cellWidth, true, perRow); }
            if (natural > perRow) { tileLabels = false; natural = measureTileCells(cellWidth, true, perRow); }
        } else natural = measureTileCells(cellWidth, false, perRow);
        rowHeight = perRow != Integer.MAX_VALUE && perRow >= natural ? perRow : natural;
        for (Cell cell : cells) cell.measure(MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY));
        setMeasuredDimension(width, rows * rowHeight + (rows - 1) * gap);
    }

    private int measureTileCells(int width, boolean refit, int rowLimit) {
        int height = ui.dp(48);
        for (Cell cell : cells) { cell.measureTile(width, refit, rowLimit); height = Math.max(height, cell.getMeasuredHeight()); }
        return height;
    }

    private int measureCells(int width, boolean compact) {
        int height = ui.dp(48);
        for (Cell cell : cells) { cell.measureContent(width, compact); height = Math.max(height, cell.getMeasuredHeight()); }
        return height;
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (cells.isEmpty()) return;
        int gap = ui.dp(tiles ? 10 : 6), width = cells.get(0).getMeasuredWidth();
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        for (int i = 0; i < cells.size(); i++) {
            int x = (i % columns) * (width + gap), y = (i / columns) * (rowHeight + gap);
            if (rtl) x = getWidth() - x - width;
            cells.get(i).layout(x, y, x + width, y + rowHeight);
        }
    }

    /** Setting tiles use short names: the Scooter page's "Top speed", and "Charge schedule". */
    private static String tileLabel(QuickAccessActions.Entry entry) {
        if ("feature:maximum_speed".equals(entry.id)) return "Top speed";
        if ("feature:timed_charging".equals(entry.id)) return "Charge schedule";
        return entry.label;
    }
}
