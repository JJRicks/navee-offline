package local.navee.offlinelock;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** All read-only evidence, with stable inputs and scroll while readings update. */
public final class StatsScreen implements AppScreen {
    private final ScreenHost host;
    private final Ui ui;
    private final ScooterService service;
    private final SharedPreferences preferences;
    private final ScrollView scroll, sectionScroll, readingsScroll;
    /** True when the page shows two panes (sections left, Readings right) beside MainActivity's side rail. */
    private final boolean wide;
    private static final int WIDE_DP = 650, RAIL_DP = 92;
    /** Pane scroll positions kept across a rebuild, as MainActivity keeps the phone page's scroll. */
    private static int savedSectionScroll, savedReadingsScroll;
    private final LinearLayout page, overviewSection, readingsSection, chartsSection, diagnosticsSection;
    private final LinkedHashMap<String, Row> rows = new LinkedHashMap<String, Row>();
    private final LinkedHashMap<String, Group> groups = new LinkedHashMap<String, Group>();
    private final LinkedHashMap<String, ValueRow> favouriteRows = new LinkedHashMap<String, ValueRow>();
    private final LinkedHashMap<String, ValueRow> deviceRows = new LinkedHashMap<String, ValueRow>();
    private final StatsCharts chartBrowser;
    private final Set<String> chartFavourites;
    private final LinkedHashMap<String, StatsCharts.Card> favouriteCharts = new LinkedHashMap<>();
    private boolean chartBrowserReady;
    private final android.view.ViewTreeObserver.OnScrollChangedListener chartScroll = () -> { if (chartBrowserReady) chartBrowserRefresh(); };
    private final TextView reorderFavourites;
    private AlertDialog orderDialog;
    private final SegmentedSelector sectionTabs;
    private final Set<String> favourites;
    private final boolean dense;
    private String query, selectedSection;
    private String favouritesShown, devicesShown, eventsShown;
    private final EditText search;
    private static final String[] SECTIONS = {"Overview", "Readings", "Charts", "Diagnostics"};
    private static final String[] WIDE_SECTIONS = {"Overview", "Charts", "Diagnostics"};
    private AlertDialog metricDialog;
    private final TextView status, batteryHeadline, batteryDetails, batteryCaveat,
            resultCount, rawValues, favouritesHint;
    private final TextView refreshButton;
    private final LinearLayout favouritesCard, deviceCard, eventsCard;
    private final View deviceLabel;
    private final Ui.BatteryRing batteryRing;
    private static final class Row {
        final String key, group, label;
        final LinearLayout layout;
        final TextView name, amount, evidence, star;
        Row(String key, String group, String label, LinearLayout layout, TextView name, TextView amount, TextView evidence, TextView star) {
            this.key = key; this.group = group; this.label = label; this.layout = layout;
            this.name = name; this.amount = amount; this.evidence = evidence; this.star = star;
        }
    }
    private static final class Group {
        final String name;
        final LinearLayout card, body;
        final TextView title;
        boolean expanded;
        Group(String name, LinearLayout card, LinearLayout body, TextView title, boolean expanded) {
            this.name = name; this.card = card; this.body = body; this.title = title; this.expanded = expanded;
        }
    }
    /** A read-only row: optional icon square, name with a last-seen time under it, bold value. */
    private static final class ValueRow {
        final LinearLayout layout;
        final TextView name, amount, time;
        ValueRow(LinearLayout layout, TextView name, TextView amount, TextView time) {
            this.layout = layout; this.name = name; this.amount = amount; this.time = time;
        }
    }

    public StatsScreen(ScreenHost host) {
        this.host = host; ui = host.ui(); service = host.service(); preferences = service.settings;
        dense = "dense".equals(preferences.getString("stats_density", "comfortable"));
        favourites = new LinkedHashSet<String>(preferences.getStringSet("stats_favourites", new LinkedHashSet<String>()));
        chartFavourites = new LinkedHashSet<String>(preferences.getStringSet("stats_chart_favourites", new LinkedHashSet<String>()));
        query = preferences.getString("stats_query", "");
        int widthDp = host.activity().getResources().getConfiguration().screenWidthDp;
        // Same trigger as MainActivity's side rail: the content area beside the rail is wide enough for two panes.
        wide = widthDp >= WIDE_DP;
        selectedSection = validSection(preferences.getString("stats_section", "Overview"));
        // Readings has its own pane when wide; a saved Readings tab opens Overview beside it (the saved choice is kept).
        if (wide && selectedSection.equals("Readings")) selectedSection = "Overview";
        status = ui.caption(""); status.setPadding(0, 0, 0, ui.dp(10));
        overviewSection = vertical(); readingsSection = vertical(); chartsSection = vertical(); diagnosticsSection = vertical();
        String[] sections = wide ? WIDE_SECTIONS : SECTIONS;
        sectionTabs = new SegmentedSelector(ui, "Live Stats section", sections, choice -> selectSection(sections[choice]));
        sectionTabs.useCompactPadding();
        sectionTabs.setTag("live-stats-sections");
        sectionTabs.select(java.util.Arrays.asList(sections).indexOf(selectedSection), false);
        LinearLayout tabHolder = vertical();
        tabHolder.setPadding(0, ui.dp(2), 0, ui.dp(12));
        tabHolder.addView(sectionTabs, new LinearLayout.LayoutParams(-1, -2));
        View tabs = tabHolder;
        if (!wide) {
            scroll = new ScrollView(host.activity()); scroll.setFillViewport(true); scroll.setClipToPadding(false);
            page = vertical(); page.setPadding(ui.dp(18), ui.dp(6), ui.dp(18), ui.dp(24)); scroll.addView(page);
            sectionScroll = scroll; readingsScroll = null;
            page.addView(status); page.addView(tabs, new LinearLayout.LayoutParams(-1, -2));
            for (LinearLayout section : new LinearLayout[] {overviewSection, readingsSection, chartsSection, diagnosticsSection}) {
                section.setPadding(0, ui.dp(4), 0, 0); page.addView(section);
            }
        } else {
            // Two panes that scroll on their own: sections on the left, Readings always on the right.
            // The outer frame is still the page's ScrollView for MainActivity, but it never scrolls.
            scroll = new PaneFrame(host.activity());
            LinearLayout frame = vertical(); frame.setPadding(ui.dp(24), ui.dp(6), ui.dp(24), 0);
            scroll.addView(frame, new ScrollView.LayoutParams(-1, -1));
            frame.addView(status, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout panes = new LinearLayout(host.activity()); panes.setOrientation(LinearLayout.HORIZONTAL); panes.setBaselineAligned(false);
            frame.addView(panes, new LinearLayout.LayoutParams(-1, 0, 1));
            page = vertical(); page.setPadding(0, 0, 0, ui.dp(24));
            page.addView(tabs, new LinearLayout.LayoutParams(-1, -2));
            for (LinearLayout section : new LinearLayout[] {overviewSection, chartsSection, diagnosticsSection}) {
                section.setPadding(0, ui.dp(4), 0, 0); page.addView(section);
            }
            LinearLayout readingsPage = vertical(); readingsPage.setPadding(0, 0, 0, ui.dp(24));
            readingsPage.addView(readingsSection);
            sectionScroll = paneScroll(page); readingsScroll = paneScroll(readingsPage);
            // Equal panes with the 20dp gutter between them; TalkBack reads the left pane first.
            panes.addView(sectionScroll, new LinearLayout.LayoutParams(0, -1, 1));
            panes.addView(new View(host.activity()), new LinearLayout.LayoutParams(ui.dp(20), 1));
            panes.addView(readingsScroll, new LinearLayout.LayoutParams(0, -1, 1));
            final int left = savedSectionScroll, right = savedReadingsScroll;
            sectionScroll.post(() -> sectionScroll.scrollTo(0, left));
            readingsScroll.post(() -> readingsScroll.scrollTo(0, right));
        }

        // Overview: the battery ring beside range and electrical detail, then the rider's favourite readings.
        // When wide, judge the ring layout by the pane's width as if it were a phone page with its 18dp sides.
        int layoutDp = wide ? (widthDp - RAIL_DP - 48 - 20) / 2 + 36 : widthDp;
        boolean compactBattery = layoutDp < 340 || (layoutDp < 430 && ui.textScale * host.activity().getResources().getConfiguration().fontScale > 1.2f);
        LinearLayout battery = ui.card(); battery.setOrientation(compactBattery ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        battery.setGravity(compactBattery ? Gravity.CENTER_HORIZONTAL : Gravity.CENTER_VERTICAL);
        batteryRing = ui.new BatteryRing(); battery.addView(batteryRing, new LinearLayout.LayoutParams(ui.dp(120), ui.dp(136)));
        LinearLayout batteryWords = vertical(); batteryWords.setPadding(compactBattery ? 0 : ui.dp(10), compactBattery ? ui.dp(8) : 0, 0, 0);
        batteryHeadline = ui.text("Waiting for readings", 17, true); batteryHeadline.setPadding(0, 0, 0, ui.dp(6)); batteryWords.addView(batteryHeadline);
        batteryDetails = ui.text("", 12.5f, false); batteryDetails.setTextColor(ui.muted); batteryWords.addView(batteryDetails);
        battery.addView(batteryWords, compactBattery ? new LinearLayout.LayoutParams(-1, -2) : new LinearLayout.LayoutParams(0, -2, 1)); overviewSection.addView(battery);
        batteryCaveat = ui.caption("Reported health is a controller estimate, not a measured capacity test.");
        batteryCaveat.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f * ui.textScale);
        batteryCaveat.setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(4)); overviewSection.addView(batteryCaveat);
        TextView favouritesTitle = ui.text("Favourites", 19, true);
        favouritesTitle.setPadding(ui.dp(4), ui.dp(12), ui.dp(4), ui.dp(10));
        if (Build.VERSION.SDK_INT >= 28) favouritesTitle.setAccessibilityHeading(true);
        LinearLayout favouriteHeader = new LinearLayout(host.activity()); favouriteHeader.setGravity(Gravity.CENTER_VERTICAL);
        favouriteHeader.addView(favouritesTitle, new LinearLayout.LayoutParams(0, -2, 1));
        reorderFavourites = ui.secondaryButton("Reorder", this::showFavouriteOrder);
        reorderFavourites.setContentDescription("Reorder favourite readings and charts");
        favouriteHeader.addView(reorderFavourites, new LinearLayout.LayoutParams(-2, -2));
        overviewSection.addView(favouriteHeader);
        favouritesCard = ui.groupCard(); overviewSection.addView(favouritesCard);
        favouritesHint = ui.text("Choose your favourites\nStar a reading or chart to add it here", 14, false);
        favouritesHint.setTextColor(ui.muted); favouritesHint.setGravity(Gravity.CENTER);
        favouritesHint.setMinHeight(ui.dp(120)); favouritesHint.setPadding(ui.dp(20), ui.dp(22), ui.dp(20), ui.dp(22));
        GradientDrawable empty = new GradientDrawable(); empty.setColor(Ui.withAlpha(ui.surface, 140));
        empty.setCornerRadius(ui.dp(18)); empty.setStroke(ui.dp(1), ui.dim, ui.dp(6), ui.dp(5));
        favouritesHint.setBackground(empty); favouritesHint.setTag("stats-empty-favourites");
        favouritesHint.setFocusable(true); favouritesHint.setOnClickListener(v -> {
            if (!wide) selectSection("Readings");
            else readingsScroll.smoothScrollTo(0, 0);
        });
        overviewSection.addView(favouritesHint, new LinearLayout.LayoutParams(-1, -2));

        chartBrowser = new StatsCharts(host, chartFavourites, this::toggleChartFavourite);
        chartsSection.addView(chartBrowser.view); chartBrowserReady = true;
        sectionScroll.getViewTreeObserver().addOnScrollChangedListener(chartScroll);

        // Readings: keep the count row geometry when removing the redundant Favourites filter.
        search = new EditText(host.activity()); search.setSingleLine(true); search.setHint("Search");
        ui.styleSearch(search); search.setContentDescription("Search scooter measurements"); search.setText(query);
        readingsSection.addView(search, ui.searchLayout());
        SearchFocus.bind(search, host.activity().getWindow());
        LinearLayout filters = new LinearLayout(host.activity()); filters.setGravity(Gravity.CENTER_VERTICAL);
        filters.setMinimumHeight(ui.dp(48));
        View formerFilterSpace = new View(host.activity());
        LinearLayout.LayoutParams filterParams = new LinearLayout.LayoutParams(0, ui.dp(48), 1); filterParams.rightMargin = ui.dp(4);
        filters.addView(formerFilterSpace, filterParams);
        resultCount = ui.caption(""); resultCount.setGravity(Gravity.END); resultCount.setPadding(ui.dp(12), 0, ui.dp(4), 0);
        filters.addView(resultCount, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams filterSpacing = new LinearLayout.LayoutParams(-1, -2); filterSpacing.bottomMargin = ui.dp(4);
        readingsSection.addView(filters, filterSpacing);
        for (TelemetryHistory.Reading reading : service.displayReadings().values()) addMetric(reading);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                query = s.toString(); preferences.edit().putString("stats_query", query).apply(); applyFilter();
            }
            public void afterTextChanged(Editable e) {}
        });

        // Diagnostics: actions, device firmware, the connection log and raw payloads in a dark inset.
        LinearLayout evidence = ui.card(); TextView diagnosticsTitle = ui.text("Diagnostics", 17, true); evidence.addView(diagnosticsTitle);
        if (Build.VERSION.SDK_INT >= 28) diagnosticsTitle.setAccessibilityHeading(true);
        TextView explanation = ui.caption("Payloads include the response status followed by data. Unsupported fields and units stay raw. Owner credentials and PIN responses are excluded from these diagnostics.");
        explanation.setPadding(0, ui.dp(5), 0, ui.dp(6)); evidence.addView(explanation);
        LinearLayout actions = new StackingRow();
        refreshButton = ui.secondaryButton("Refresh", () -> { service.ble.refresh(true); host.toast("Requested all supported diagnostic reads."); });
        ui.decorate(refreshButton, "recovery"); refreshButton.setContentDescription("Refresh diagnostic reads");
        TextView export = ui.secondaryButton("Export", () -> host.saveText("navee-diagnostics.json", "application/json", service.snapshot()));
        ui.decorate(export, "export"); export.setContentDescription("Export diagnostics JSON");
        for (TextView action : new TextView[] {refreshButton, export}) {
            action.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12)); action.setCompoundDrawablePadding(ui.dp(8));
        }
        LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(0, -2, 1); first.rightMargin = ui.dp(4); first.topMargin = ui.dp(6);
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(0, -2, 1); second.leftMargin = ui.dp(4); second.topMargin = ui.dp(6);
        actions.addView(refreshButton, first); actions.addView(export, second); evidence.addView(actions); diagnosticsSection.addView(evidence);
        deviceLabel = ui.sectionLabel("Device"); diagnosticsSection.addView(deviceLabel);
        deviceCard = ui.groupCard(); diagnosticsSection.addView(deviceCard);
        diagnosticsSection.addView(ui.sectionLabel("Recent connection events"));
        eventsCard = ui.groupCard(); diagnosticsSection.addView(eventsCard);
        diagnosticsSection.addView(ui.sectionLabel("Raw read payloads"));
        LinearLayout rawCard = ui.card(); LinearLayout inset = vertical();
        inset.setBackground(ui.shape(ui.dark ? ui.bg : ui.raised, 12, 0)); inset.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        rawValues = ui.text("No accepted read payloads yet.", 11, false); rawValues.setTypeface(Typeface.MONOSPACE); rawValues.setTextColor(ui.muted);
        rawValues.setTextIsSelectable(true); inset.addView(rawValues); rawCard.addView(inset, new LinearLayout.LayoutParams(-1, -2));
        diagnosticsSection.addView(rawCard);
        applyFilter(); showSection(); refresh();
    }
    private LinearLayout vertical() { LinearLayout result = new LinearLayout(host.activity()); result.setOrientation(LinearLayout.VERTICAL); return result; }
    private ScrollView paneScroll(View content) {
        ScrollView pane = new ScrollView(host.activity()); pane.setFillViewport(true); pane.setClipToPadding(false);
        pane.addView(content, new ScrollView.LayoutParams(-1, -2)); return pane;
    }
    /**
     * The wide page's outer ScrollView. It sizes its content to exactly its own viewport, so it has
     * no scroll range and never intercepts drags; each pane inside scrolls on its own.
     */
    private static final class PaneFrame extends ScrollView {
        PaneFrame(Context context) { super(context); setFillViewport(true); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
            setMeasuredDimension(width, height);
            if (getChildCount() == 0) return;
            getChildAt(0).measure(MeasureSpec.makeMeasureSpec(Math.max(0, width - getPaddingLeft() - getPaddingRight()), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.max(0, height - getPaddingTop() - getPaddingBottom()), MeasureSpec.EXACTLY));
        }
    }
    /** Whether the Readings section is on screen: always when wide, otherwise only as the selected tab. */
    private boolean readingsShown() { return wide || selectedSection.equals("Readings"); }
    private static String validSection(String section) {
        for (String known : SECTIONS) if (known.equals(section)) return known;
        return "Overview";
    }
    private void showSection() {
        overviewSection.setVisibility(selectedSection.equals("Overview") ? View.VISIBLE : View.GONE);
        readingsSection.setVisibility(readingsShown() ? View.VISIBLE : View.GONE);
        chartsSection.setVisibility(selectedSection.equals("Charts") ? View.VISIBLE : View.GONE);
        diagnosticsSection.setVisibility(selectedSection.equals("Diagnostics") ? View.VISIBLE : View.GONE);
    }
    private void selectSection(String section) {
        selectedSection = validSection(section); showSection();
        sectionTabs.select(java.util.Arrays.asList(wide ? WIDE_SECTIONS : SECTIONS).indexOf(selectedSection), true);
        boolean hideChartSearch = !selectedSection.equals("Charts") && chartBrowser.search.hasFocus();
        if (!readingsShown() || hideChartSearch) {
            InputMethodManager keyboard = host.activity().getSystemService(InputMethodManager.class);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
            if (!readingsShown()) search.clearFocus();
            if (hideChartSearch) chartBrowser.search.clearFocus();
        }
        preferences.edit().putString("stats_section", selectedSection).apply();
        // When wide only the left pane changes, so only it returns to the top.
        refresh(); sectionScroll.post(() -> sectionScroll.scrollTo(0, 0));
    }
    /** Two equal buttons side by side, stacked full width when either label would have to break. */
    private final class StackingRow extends LinearLayout {
        StackingRow() { super(host.activity()); setOrientation(HORIZONTAL); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int half = (MeasureSpec.getSize(widthSpec) - ui.dp(8)) / 2;
            boolean stack = false;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                child.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                if (child.getMeasuredWidth() > half) stack = true;
            }
            if (stack != (getOrientation() == VERTICAL)) {
                setOrientation(stack ? VERTICAL : HORIZONTAL);
                for (int i = 0; i < getChildCount(); i++) {
                    LayoutParams p = stack ? new LayoutParams(-1, -2) : new LayoutParams(0, -2, 1);
                    p.topMargin = ui.dp(6);
                    if (!stack) { if (i == 0) p.rightMargin = ui.dp(4); else p.leftMargin = ui.dp(4); }
                    getChildAt(i).setLayoutParams(p);
                }
            }
            super.onMeasure(widthSpec, heightSpec);
        }
    }
    /** Balance labels and values, stacking before a narrow pane splits a value mid-word. */
    private final class ReadingRow extends LinearLayout {
        TextView amount;
        View words;
        private final int fixedDp;
        private boolean stacked;
        ReadingRow(int fixedDp) { super(host.activity()); this.fixedDp = fixedDp; }
        @Override protected void onMeasure(int width, int height) {
            int inner = Math.max(0, MeasureSpec.getSize(width) - getPaddingLeft() - getPaddingRight());
            int available = Math.max(0, inner - ui.dp(fixedDp));
            int valueWidth = Math.min(ui.dp(150), Math.max(ui.dp(40), Math.round(available * .45f)));
            float longest = 0;
            if (amount != null) for (String word : amount.getText().toString().split("\\s+"))
                longest = Math.max(longest, amount.getPaint().measureText(word));
            stacked = amount != null && words != null && MeasureSpec.getMode(width) != MeasureSpec.UNSPECIFIED && longest > valueWidth;
            if (!stacked) {
                if (amount != null) { amount.setMaxWidth(valueWidth); amount.setGravity(Gravity.END); }
                super.onMeasure(width, height); return;
            }
            View leading = getChildAt(0) == words ? null : getChildAt(0);
            View trailing = getChildAt(getChildCount() - 1) == amount ? null : getChildAt(getChildCount() - 1);
            int fixed = leading != null ? ui.dp(48) : trailing != null ? ui.dp(48) : 0;
            int contentWidth = Math.max(1, inner - fixed);
            int freeHeight = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            words.measure(MeasureSpec.makeMeasureSpec(contentWidth, MeasureSpec.EXACTLY), freeHeight);
            amount.setMaxWidth(contentWidth); amount.setGravity(Gravity.START);
            amount.measure(MeasureSpec.makeMeasureSpec(contentWidth, MeasureSpec.EXACTLY), freeHeight);
            if (leading != null) leading.measure(MeasureSpec.makeMeasureSpec(ui.dp(36), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ui.dp(36), MeasureSpec.EXACTLY));
            if (trailing != null) trailing.measure(MeasureSpec.makeMeasureSpec(ui.dp(48), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ui.dp(48), MeasureSpec.EXACTLY));
            int needed = words.getMeasuredHeight() + ui.dp(6) + amount.getMeasuredHeight();
            setMeasuredDimension(resolveSize(MeasureSpec.getSize(width), width), resolveSize(Math.max(getMinimumHeight(), needed + getPaddingTop() + getPaddingBottom()), height));
        }
        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            if (!stacked) { super.onLayout(changed, left, top, right, bottom); return; }
            View leading = getChildAt(0) == words ? null : getChildAt(0);
            View trailing = getChildAt(getChildCount() - 1) == amount ? null : getChildAt(getChildCount() - 1);
            int x = getPaddingLeft() + (leading == null ? 0 : ui.dp(48));
            int y = getPaddingTop();
            words.layout(x, y, x + words.getMeasuredWidth(), y + words.getMeasuredHeight());
            y += words.getMeasuredHeight() + ui.dp(6);
            amount.layout(x, y, x + amount.getMeasuredWidth(), y + amount.getMeasuredHeight());
            if (leading != null) { int center = (getHeight() - leading.getMeasuredHeight()) / 2;
                leading.layout(getPaddingLeft(), center, getPaddingLeft() + leading.getMeasuredWidth(), center + leading.getMeasuredHeight()); }
            if (trailing != null) { int edge = getWidth() - getPaddingRight(), center = (getHeight() - trailing.getMeasuredHeight()) / 2;
                trailing.layout(edge - trailing.getMeasuredWidth(), center, edge, center + trailing.getMeasuredHeight()); }
        }
    }
    private void addMetric(TelemetryHistory.Reading metric) {
        Group group = groups.get(metric.group);
        if (group == null) {
            LinearLayout wrapper = ui.groupCard(), body = vertical();
            wrapper.setPadding(ui.dp(14), ui.dp(2), ui.dp(14), ui.dp(2));
            TextView title = ui.text(metric.group, 17, true); title.setTextColor(ui.text);
            title.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            title.setMinHeight(ui.dp(60)); title.setPadding(0, ui.dp(10), 0, ui.dp(10));
            title.setCompoundDrawablePadding(ui.dp(12));
            title.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 30)),
                    null, ui.shape(Color.WHITE, 12, 0)));
            title.setFocusable(true); title.setClickable(true);
            if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
            boolean expanded = preferences.getBoolean("stats_group_" + metric.group, false);
            group = new Group(metric.group, wrapper, body, title, expanded); final Group created = group;
            title.setOnClickListener(v -> { created.expanded = !created.expanded; preferences.edit().putBoolean("stats_group_" + created.name, created.expanded).apply(); applyFilter(); });
            wrapper.addView(title, new LinearLayout.LayoutParams(-1, -2)); wrapper.addView(body, new LinearLayout.LayoutParams(-1, -2));
            groups.put(metric.group, group); readingsSection.addView(wrapper);
        }
        // The divider sits above its row; applyFilter hides it for the first visible row of a group.
        View divider = new View(host.activity()); divider.setBackgroundColor(ui.raised); divider.setTag(metric.key);
        group.body.addView(divider, new LinearLayout.LayoutParams(-1, ui.dp(1)));
        ReadingRow row = new ReadingRow(60); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, ui.dp(dense ? 4 : 8), 0, ui.dp(dense ? 4 : 8)); row.setMinimumHeight(ui.dp(dense ? 48 : 56));
        LinearLayout words = vertical(); row.words = words; String name = isKnownDriveMode(metric.key) ? "Drive mode" : metric.label;
        TextView label = ui.text(name, dense ? 13 : 14, false); words.addView(label);
        TextView source = ui.caption(""); source.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11 * ui.textScale); source.setPadding(0, ui.dp(2), 0, 0); words.addView(source);
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        TextView amount = ui.text("Not reported", dense ? 13 : 14, true); amount.setGravity(Gravity.END); row.amount = amount;
        LinearLayout.LayoutParams amountParams = new LinearLayout.LayoutParams(-2, -2); amountParams.leftMargin = ui.dp(12); row.addView(amount, amountParams);
        TextView star = ui.text("☆", 17, false); star.setGravity(Gravity.CENTER); star.setFocusable(true); star.setClickable(true);
        star.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.orange, 40)), null, ui.shape(Color.WHITE, 24, 0)));
        star.setOnClickListener(v -> toggleFavourite(metric.key));
        // 48dp square target; the negative end margin keeps the glyph close to the card edge, as drawn.
        LinearLayout.LayoutParams starParams = new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)); starParams.rightMargin = -ui.dp(8);
        row.addView(star, starParams);
        row.setFocusable(true); row.setOnClickListener(v -> showMetricDetails(metric.key));
        group.body.addView(row);
        Row created = new Row(metric.key, metric.group, name, row, label, amount, source, star);
        rows.put(metric.key, created); styleStar(created);
    }
    private void styleStar(Row row) {
        boolean on = favourites.contains(row.key);
        row.star.setText(on ? "★" : "☆"); row.star.setTextColor(on ? ui.orange : ui.dim); row.star.setSelected(on);
        row.star.setContentDescription(on ? "Remove " + row.label + " from favourites" : "Add " + row.label + " to favourites");
        if (Build.VERSION.SDK_INT >= 30) row.star.setStateDescription(on ? "Favourite" : "Not a favourite");
    }
    private void toggleFavourite(String key) {
        if (!favourites.remove(key)) favourites.add(key);
        preferences.edit().putStringSet("stats_favourites", new LinkedHashSet<String>(favourites)).apply();
        saveFavouriteOrder(favouriteOrder());
        Row row = rows.get(key); if (row != null) styleStar(row);
        applyFilter(); refresh();
    }
    private void applyFilter() {
        String needle = query.trim().toLowerCase(Locale.ROOT); int count = 0;
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (Row row : rows.values()) {
            boolean show = (needle.isEmpty()
                    || (row.label + " " + row.key + " " + row.group).toLowerCase(Locale.ROOT).contains(needle));
            row.layout.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) { count++; counts.put(row.group, counts.containsKey(row.group) ? counts.get(row.group) + 1 : 1); }
        }
        for (Group group : groups.values()) {
            int matches = counts.containsKey(group.name) ? counts.get(group.name) : 0;
            group.card.setVisibility(matches == 0 ? View.GONE : View.VISIBLE);
            boolean open = group.expanded || !needle.isEmpty();
            group.body.setVisibility(open ? View.VISIBLE : View.GONE);
            boolean shownAbove = false;
            for (int i = 0; i < group.body.getChildCount(); i++) {
                View child = group.body.getChildAt(i);
                if (child.getTag() instanceof String) {
                    Row row = rows.get(child.getTag());
                    boolean rowShown = row != null && row.layout.getVisibility() == View.VISIBLE;
                    child.setVisibility(rowShown && shownAbove ? View.VISIBLE : View.GONE);
                    shownAbove |= rowShown;
                }
            }
            group.title.setText(group.name);
            Drawable arrow = new StatsGlyph(false, open, ui.text); arrow.setBounds(0, 0, ui.dp(26), ui.dp(26));
            group.title.setCompoundDrawablesRelative(null, null, arrow, null);
            group.title.setContentDescription(group.name + " readings");
            if (Build.VERSION.SDK_INT >= 30) group.title.setStateDescription(open ? "Expanded" : "Collapsed");
            else group.title.setContentDescription(group.name + " readings, " + (open ? "expanded" : "collapsed"));
        }
        resultCount.setText(count == 0 ? "No matching readings"
                : count + (count == 1 ? " reading" : " readings"));
    }
    @Override public View view() { return scroll; }
    @Override public void refresh() {
        Map<String, TelemetryHistory.Reading> readings = service.displayReadings();
        int saved = 0, live = 0;
        for (TelemetryHistory.Reading reading : readings.values()) {
            if (reading.value != null) { saved++; if (reading.live) live++; }
            if (!readingsShown()) continue;
            Row row = rows.get(reading.key); if (row == null) continue;
            boolean fresh = reading.live && reading.value != null;
            row.amount.setText(display(reading)); row.amount.setTextColor(fresh ? ui.text : ui.muted);
            row.name.setTextColor(fresh ? ui.text : ui.muted);
            String age = reading.value == null ? "Not supplied" : reading.live ? "Live" : savedTime(reading.capturedWallMs);
            row.evidence.setText(age);
            row.evidence.setVisibility(reading.value != null && !reading.live ? View.VISIBLE : View.GONE);
            styleStar(row);
            row.layout.setContentDescription(row.label + ", " + display(reading) + ", " + age + ". Tap for details.");
            if (isKnownDriveMode(reading.key)) {
                row.amount.setContentDescription("Drive mode, " + display(reading) + ", " + age);
                ui.styleDriveMode(row.amount, display(reading), reading.live);
            }
        }
        status.setText(saved == 0 ? "Connect for readings" : "Last seen readings");
        status.setVisibility(saved == 0 || live < saved ? View.VISIBLE : View.GONE);
        status.setTextColor(ui.muted); refreshButton.setEnabled(service.ble.connected());
        if (selectedSection.equals("Overview")) refreshOverview(readings);
        chartBrowser.refresh();
        if (!selectedSection.equals("Diagnostics")) return;
        StringBuilder raw = new StringBuilder(); Map<Integer, Integer> statuses = service.ble.telemetry.statuses();
        for (Map.Entry<Integer, byte[]> e : service.ble.telemetry.frames().entrySet()) {
            if (ScooterCommands.sensitiveCommand(e.getKey())) continue;
            raw.append(String.format(Locale.US, "0x%02X", e.getKey())).append("  status ").append(statuses.get(e.getKey())).append("  ")
                    .append(e.getValue().length).append(" bytes\n").append(Protocol.hex(e.getValue())).append("\n\n");
        }
        rawValues.setText(raw.length() == 0 ? "No accepted read payloads yet." : raw.toString().trim());
        refreshDevices(readings); refreshEvents();
    }
    private void refreshOverview(Map<String, TelemetryHistory.Reading> readings) {
        TelemetryHistory.Reading soc = readings.get("battery_percent"), range = readings.get("remaining_range_km");
        batteryRing.set(soc == null ? null : soc.number, soc == null || soc.value == null ? "No reading"
                : soc.live ? "" : "Last seen", false);
        batteryRing.setLive(soc != null && soc.live);
        batteryHeadline.setText(rangeHeadline(range));
        batteryHeadline.setTextColor(range != null && range.live && range.value != null ? ui.text : ui.muted);
        StringBuilder electrical = new StringBuilder();
        for (String key : new String[] {"voltage_v", "current_a", "power_w"}) {
            TelemetryHistory.Reading reading = readings.get(key);
            if (reading == null || reading.value == null) continue;
            if (electrical.length() > 0) electrical.append(" · ");
            electrical.append(display(reading));
        }
        StringBuilder pack = new StringBuilder();
        TelemetryHistory.Reading temperature = readings.get("temperature_c"), health = readings.get("battery_health_percent");
        if (temperature != null && temperature.value != null) pack.append("Pack ").append(display(temperature));
        if (health != null && health.value != null)
            pack.append(pack.length() == 0 ? "Reported health " : " · reported health ").append(display(health));
        StringBuilder details = new StringBuilder(electrical.length() == 0 ? "Voltage, current and power not reported" : electrical);
        if (pack.length() > 0) details.append('\n').append(pack);
        if (soc != null && soc.value != null && !soc.live) details.append('\n').append(savedTime(soc.capturedWallMs));
        batteryDetails.setText(details);
        refreshFavourites(readings);
    }
    private String rangeHeadline(TelemetryHistory.Reading range) {
        if (range == null || range.value == null) return "Range not reported";
        if (range.number == null) return "Range " + range.value;
        String units = preferences.getString("units", "mi");
        long km = Math.round(range.number), miles = Math.round(range.number / RideStore.KM_PER_MILE);
        String amount = "km".equals(units) ? km + " km" : "dual".equals(units) ? miles + " mi / " + km + " km" : miles + " mi";
        return "About " + amount + " left";
    }
    private List<String> favouriteOrder() {
        return StatsFavouriteOrder.resolve(preferences.getString("stats_favourite_order", ""), favourites,
                chartFavourites, service.displayReadings().keySet());
    }
    private void saveFavouriteOrder(List<String> order) {
        preferences.edit().putString("stats_favourite_order", StatsFavouriteOrder.encode(order)).apply();
    }
    private void toggleChartFavourite(String key) {
        if (!chartFavourites.remove(key)) chartFavourites.add(key);
        preferences.edit().putStringSet("stats_chart_favourites", new LinkedHashSet<>(chartFavourites)).apply();
        saveFavouriteOrder(favouriteOrder()); chartBrowser.starsChanged(); refreshFavourites(service.displayReadings());
    }
    private void chartBrowserRefresh() { chartBrowser.refreshVisible(); }
    private void refreshFavourites(Map<String, TelemetryHistory.Reading> readings) {
        List<String> order = favouriteOrder(); String signature = StatsFavouriteOrder.encode(order);
        for (String id : order) if (id.startsWith("reading:")) {
            TelemetryHistory.Reading r = readings.get(id.substring(8));
            signature += r != null && r.live && r.value != null ? "+" : "-";
        }
        if (!signature.equals(favouritesShown)) {
            favouritesShown = signature; favouritesCard.removeAllViews(); favouriteRows.clear();
            for (StatsCharts.Card card : favouriteCharts.values()) chartBrowser.release(card); favouriteCharts.clear();
            for (String id : order) {
                boolean chart = id.startsWith("chart:"); String key = id.substring(id.indexOf(':') + 1);
                TelemetryHistory.Reading reading = readings.get(key); if (reading == null) continue;
                if (favouritesCard.getChildCount() > 0) ui.divider(favouritesCard);
                if (chart) {
                    StatsCharts.Card card = chartBrowser.favourite(key); favouriteCharts.put(key, card); favouritesCard.addView(card.view);
                } else {
                    ValueRow row = valueRow(key, isKnownDriveMode(key) ? "Drive mode" : reading.label,
                            readingIcon(key), reading.live && reading.value != null);
                    favouritesCard.addView(row.layout); favouriteRows.put(key, row);
                }
            }
            favouritesCard.setVisibility(order.isEmpty() ? View.GONE : View.VISIBLE);
            favouritesHint.setVisibility(order.isEmpty() ? View.VISIBLE : View.GONE);
            reorderFavourites.setVisibility(order.size() < 2 ? View.GONE : View.VISIBLE);
            favouritesCard.post(() -> { if (chartBrowserReady) chartBrowser.refreshVisible(); });
        }
        for (Map.Entry<String, ValueRow> e : favouriteRows.entrySet()) updateValueRow(e.getValue(), e.getKey(), readings.get(e.getKey()));
    }
    private void showFavouriteOrder() {
        if (orderDialog != null) orderDialog.dismiss();
        LinearLayout content = vertical(); content.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8));
        ScrollView scroller = new ScrollView(host.activity()); scroller.addView(content);
        rebuildFavouriteOrder(content);
        orderDialog = new AlertDialog.Builder(host.activity()).setTitle("Reorder favourites").setView(scroller)
                .setPositiveButton("Done", null).create(); orderDialog.show();
    }
    private void rebuildFavouriteOrder(LinearLayout content) {
        content.removeAllViews(); List<String> order = favouriteOrder();
        Map<String, TelemetryHistory.Reading> readings = service.displayReadings();
        for (int i = 0; i < order.size(); i++) {
            String id = order.get(i), key = id.substring(id.indexOf(':') + 1);
            boolean chart = id.startsWith("chart:");
            LinearLayout row = new LinearLayout(host.activity()); row.setGravity(Gravity.CENTER_VERTICAL);
            TextView label = ui.text(readings.get(key).label + (chart ? " · Chart" : ""), 14, false);
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            for (int direction : new int[]{-1, 1}) {
                TextView button = ui.text(direction < 0 ? "↑" : "↓", 23, true); button.setGravity(Gravity.CENTER);
                boolean enabled = direction < 0 ? i > 0 : i < order.size() - 1;
                button.setEnabled(enabled); button.setTextColor(enabled ? ui.text : ui.dim); button.setFocusable(true);
                button.setContentDescription("Move " + readings.get(key).label + (chart ? " chart" : " reading") + (direction < 0 ? " up" : " down"));
                button.setOnClickListener(v -> {
                    List<String> current = favouriteOrder();
                    if (StatsFavouriteOrder.move(current, id, direction)) {
                        saveFavouriteOrder(current); refreshFavourites(service.displayReadings()); rebuildFavouriteOrder(content);
                    }
                });
                row.addView(button, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
            }
            content.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
    }
    private void refreshDevices(Map<String, TelemetryHistory.Reading> readings) {
        StringBuilder signature = new StringBuilder(); List<TelemetryHistory.Reading> device = new ArrayList<TelemetryHistory.Reading>();
        for (TelemetryHistory.Reading reading : readings.values()) {
            if (!"Device".equals(reading.group)) continue;
            device.add(reading); signature.append(reading.key).append(',');
        }
        if (!signature.toString().equals(devicesShown)) {
            devicesShown = signature.toString(); deviceCard.removeAllViews(); deviceRows.clear();
            for (TelemetryHistory.Reading reading : device) {
                if (!deviceRows.isEmpty()) ui.divider(deviceCard);
                ValueRow row = valueRow(reading.key, reading.label, null, false);
                deviceCard.addView(row.layout); deviceRows.put(reading.key, row);
            }
            deviceLabel.setVisibility(device.isEmpty() ? View.GONE : View.VISIBLE);
            deviceCard.setVisibility(device.isEmpty() ? View.GONE : View.VISIBLE);
        }
        for (Map.Entry<String, ValueRow> e : deviceRows.entrySet()) updateValueRow(e.getValue(), e.getKey(), readings.get(e.getKey()));
    }
    private void refreshEvents() {
        List<String> events = service.ble.events(); StringBuilder signature = new StringBuilder();
        int start = Math.max(0, events.size() - 12);
        for (int i = start; i < events.size(); i++) signature.append(events.get(i)).append('\n');
        if (signature.toString().equals(eventsShown)) return;
        eventsShown = signature.toString(); eventsCard.removeAllViews();
        if (events.isEmpty()) {
            TextView none = ui.text("No connection events yet.", 13, false); none.setTextColor(ui.muted);
            none.setPadding(0, ui.dp(14), 0, ui.dp(14)); eventsCard.addView(none); return;
        }
        // Newest first; each event keeps its own recorded time.
        for (int i = events.size() - 1; i >= start; i--) {
            if (i != events.size() - 1) ui.divider(eventsCard);
            String event = events.get(i); int split = event.indexOf("  ");
            String time = split > 0 ? event.substring(0, split) : "", text = split > 0 ? event.substring(split).trim() : event;
            LinearLayout row = vertical(); row.setPadding(0, ui.dp(8), 0, ui.dp(8)); row.setMinimumHeight(ui.dp(48));
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView message = ui.text(text, 13, false); row.addView(message);
            if (!time.isEmpty()) {
                TextView when = ui.caption(time); when.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11 * ui.textScale);
                when.setPadding(0, ui.dp(2), 0, 0); row.addView(when);
            }
            row.setFocusable(true); row.setContentDescription(time.isEmpty() ? text : text + ", " + time);
            eventsCard.addView(row);
        }
    }
    private ValueRow valueRow(String key, String label, String icon, boolean live) {
        ReadingRow row = new ReadingRow(icon == null ? 12 : 60); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, ui.dp(dense ? 6 : 8), 0, ui.dp(dense ? 6 : 8)); row.setMinimumHeight(ui.dp(dense ? 48 : 56));
        if (icon != null) {
            LinearLayout.LayoutParams square = new LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)); square.rightMargin = ui.dp(12);
            row.addView(ui.iconSquare(icon, live ? ui.accent : ui.dim), square);
        }
        LinearLayout words = vertical(); row.words = words;
        TextView name = ui.text(label, dense ? 13.5f : 14.5f, false); words.addView(name);
        TextView time = ui.caption(""); time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11 * ui.textScale); time.setPadding(0, ui.dp(2), 0, 0);
        time.setVisibility(View.GONE); words.addView(time);
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        TextView amount = ui.text("", dense ? 13.5f : 14.5f, true); amount.setGravity(Gravity.END); row.amount = amount;
        LinearLayout.LayoutParams amountParams = new LinearLayout.LayoutParams(-2, -2); amountParams.leftMargin = ui.dp(12); row.addView(amount, amountParams);
        row.setFocusable(true); row.setClickable(true);
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 26)), null, ui.shape(Color.WHITE, 10, 0)));
        row.setOnClickListener(v -> showMetricDetails(key));
        return new ValueRow(row, name, amount, time);
    }
    private void updateValueRow(ValueRow row, String key, TelemetryHistory.Reading reading) {
        boolean has = reading != null && reading.value != null, fresh = has && reading.live;
        String shown = reading == null ? "Not reported" : display(reading);
        row.amount.setText(shown); row.amount.setContentDescription(null);
        row.amount.setTextColor(fresh ? ui.text : ui.muted); row.name.setTextColor(fresh ? ui.text : ui.muted);
        String age = !has ? "Not supplied" : fresh ? "Live" : savedTime(reading.capturedWallMs);
        row.time.setText(age); row.time.setVisibility(has && !fresh ? View.VISIBLE : View.GONE);
        row.layout.setContentDescription(row.name.getText() + ", " + shown + ", " + age + ". Tap for details.");
        if (reading != null && isKnownDriveMode(key)) {
            row.amount.setContentDescription("Drive mode, " + shown + ", " + age);
            ui.styleDriveMode(row.amount, shown, reading.live);
        }
    }
    /** Bounded vector glyphs keep the filter and disclosure clear at any text size. */
    static final class StatsGlyph extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean filter, open;
        StatsGlyph(boolean filter, boolean open, int color) { this.filter = filter; this.open = open; paint.setColor(color); }
        @Override public void draw(Canvas canvas) {
            canvas.save(); canvas.translate(getBounds().left, getBounds().top);
            canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
            Path path = new Path();
            if (filter) { path.moveTo(3, 4); path.lineTo(21, 4); path.lineTo(14, 12); path.lineTo(14, 19); path.lineTo(10, 21); path.lineTo(10, 12); path.close(); }
            else if (open) { path.moveTo(6, 9); path.lineTo(12, 15); path.lineTo(18, 9); }
            else { path.moveTo(9, 6); path.lineTo(15, 12); path.lineTo(9, 18); }
            canvas.drawPath(path, paint); canvas.restore();
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
    private void showMetricDetails(String key) {
        TelemetryHistory.Reading reading = service.displayReadings().get(key);
        if (reading == null) return;
        String when = reading.capturedWallMs <= 0 ? "Not supplied" : DisplayTime.dateTime(host.activity(), preferences, reading.capturedWallMs);
        String detail = display(reading) + "\n\n" + (reading.live ? "Live reading" : "Last seen reading")
                + "\n" + when + "\n" + reading.key;
        if (key.equals("energy_throughput_wh")) detail += "\n\nThis field's meaning is unverified. It is not used to calculate ride energy.";
        metricDialog = new AlertDialog.Builder(host.activity()).setTitle(reading.label).setMessage(detail)
                .setNeutralButton(favourites.contains(key) ? "Remove favourite" : "Add favourite", (d, which) -> toggleFavourite(key))
                .setPositiveButton("Close", null).create(); metricDialog.show();
    }
    void resetScroll() { sectionScroll.scrollTo(0, 0); if (readingsScroll != null) readingsScroll.scrollTo(0, 0); clearSavedScroll(); }
    static void clearSavedScroll() { savedSectionScroll = savedReadingsScroll = 0; }
    @Override public void close() {
        sectionTabs.finishMotion();
        if (metricDialog != null) metricDialog.dismiss();
        if (orderDialog != null) orderDialog.dismiss();
        if (sectionScroll.getViewTreeObserver().isAlive()) sectionScroll.getViewTreeObserver().removeOnScrollChangedListener(chartScroll);
        chartBrowser.close();
        savedSectionScroll = wide ? sectionScroll.getScrollY() : 0;
        savedReadingsScroll = wide ? readingsScroll.getScrollY() : 0;
    }
    private boolean isKnownDriveMode(String key) {
        ScooterProfiles.Profile profile = service.profiles.active();
        return key.equals("driving_mode") && profile != null && profile.pid() == 2538;
    }
    private String display(TelemetryHistory.Reading metric) {
        if (metric.value == null) return "Not reported";
        if (isKnownDriveMode(metric.key) && metric.number != null)
            return Telemetry.drivingModeLabel(2538, metric.number);
        if (DisplayTemperature.isTemperature(metric.key) && metric.number != null)
            return DisplayTemperature.format(metric.number, preferences.getString("temperature_unit", "c"), 0);
        String units = preferences.getString("units", "mi");
        if (metric.key.equals("stored_trips")) return DisplayUnits.metricText(metric.value, units);
        if (metric.number == null || "km".equals(units)) return metric.value;
        boolean speed = metric.key.endsWith("_kmh"), distance = metric.key.endsWith("_km");
        if (!speed && !distance) return metric.value;
        String converted = String.format(Locale.US, "%.2f %s", metric.number / RideStore.KM_PER_MILE, speed ? "mph" : "mi");
        return "dual".equals(units) ? metric.value + "  /  " + converted : converted;
    }
    private String savedTime(long capturedWallMs) {
        return capturedWallMs <= 0 ? "Last seen: time unavailable" : "Last seen "
                + DisplayTime.recent(host.activity(), preferences, capturedWallMs, System.currentTimeMillis());
    }
    private String readingIcon(String key) {
        if (key.equals("remaining_range_km")) return "route";
        if (key.equals("power_w")) return "bolt";
        if (key.equals("battery_health_percent")) return "heart";
        if (key.equals("temperature_c")) return "temperature";
        return ui.featureIcon(key);
    }
}
