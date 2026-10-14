package local.navee.offlinelock;

import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.function.Consumer;

/** Height-aware shortcuts: use available room without clipping text or shrinking touch targets. */
final class QuickAccessGrid extends ViewGroup {
    private final ScreenHost host;
    private final Ui ui;
    private final boolean editing;
    private final ArrayList<Cell> cells = new ArrayList<>();
    private int budget, columns = 1, rowHeight;
    private boolean iconsOnly;

    QuickAccessGrid(ScreenHost host, List<String> ids, boolean editing, Consumer<String> action) {
        super(host.activity());
        this.host = host; this.ui = host.ui(); this.editing = editing;
        setTag("quick-access-grid");
        for (String id : QuickAccessActions.normalize(ids)) {
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
        TelemetryHistory.Reading schedule = service == null ? null
                : service.displayReadings().get("scheduled_charge_time_raw");
        for (Cell cell : cells) {
            ScooterCommands.Feature feature = cell.entry.feature();
            boolean featureLive = feature != null && live && (feature.type == ScooterCommands.Type.TIME
                    ? service.readingFresh("scheduled_charge_time_raw") : service.featureFresh(feature.key));
            Integer value = feature == null || feature.sensitive ? null : values.get(feature.key);
            if (feature != null && !feature.sensitive && live) {
                Integer fresh = ble.featureValue(feature.key);
                if (fresh != null) value = fresh;
            }
            boolean pending = feature != null && ble != null && feature.key.equals(ble.pending());
            String state = feature == null || feature.sensitive ? ""
                    : pending ? "Updating…" : value == null ? "—"
                    : DisplayUnits.featureValue(feature, value, units);
            boolean showSchedule = feature != null && feature.key.equals("timed_charging")
                    && schedule != null && schedule.number != null && schedule.number > 0;
            if (showSchedule) {
                long wall = (long)(schedule.number * 1000)
                        - TimeZone.getDefault().getOffset(System.currentTimeMillis());
                state += " • " + DisplayTime.clock(host.activity(), preferences, wall);
            }
            if (!state.contentEquals(cell.value.getText())) cell.value.setText(state);
            cell.value.setVisibility(iconsOnly || state.isEmpty() ? GONE : VISIBLE);
            cell.value.setTag(state);
            cell.value.setTextColor(featureLive ? ui.text : ui.muted);
            boolean selected = cell.entry.directToggle() && value != null && value == 1 && featureLive;
            if (cell.isSelected() != selected) { cell.setSelected(selected); cell.updateBackground(); }
            cell.setEnabled(!editing);
            cell.setAlpha(feature != null && !featureLive ? .65f : 1f);
            String description = cell.entry.label + (state.isEmpty() ? "" : ", " + state)
                    + (feature != null && !featureLive ? ", last seen" : "")
                    + (showSchedule && !schedule.live ? ", scheduled time last seen" : "")
                    + (cell.entry.directToggle() ? ", toggle" : ", open");
            cell.setContentDescription(description);
            if (android.os.Build.VERSION.SDK_INT >= 30) cell.setStateDescription(state);
        }
    }

    private final class Cell extends LinearLayout {
        final QuickAccessActions.Entry entry;
        final View icon;
        final TextView label, value;
        Cell(QuickAccessActions.Entry entry) {
            super(host.activity()); this.entry = entry;
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
        void updateBackground() {
            setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 40)),
                    ui.shape(isSelected() ? Ui.withAlpha(ui.accent, 35) : ui.raised, 12,
                            isSelected() ? Ui.withAlpha(ui.accent, 100) : 0), null));
        }
        void measureContent(int width, boolean compact) {
            label.setVisibility(iconsOnly ? GONE : VISIBLE);
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

    private int measureCells(int width, boolean compact) {
        int height = ui.dp(48);
        for (Cell cell : cells) { cell.measureContent(width, compact); height = Math.max(height, cell.getMeasuredHeight()); }
        return height;
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int gap = ui.dp(6), width = cells.get(0).getMeasuredWidth();
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        for (int i = 0; i < cells.size(); i++) {
            int x = (i % columns) * (width + gap), y = (i / columns) * (rowHeight + gap);
            if (rtl) x = getWidth() - x - width;
            cells.get(i).layout(x, y, x + width, y + rowHeight);
        }
    }
}
