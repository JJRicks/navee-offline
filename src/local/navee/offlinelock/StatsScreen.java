package local.navee.offlinelock;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
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
    private final LinkedHashMap<String, TextView> chartChips = new LinkedHashMap<String, TextView>();
    private final Set<String> favourites;
    private final boolean dense;
    private String query, chartKey, selectedSection;
    private String favouritesShown, devicesShown, eventsShown;
    private final EditText search;
    private static final String[] SECTIONS = {"Overview", "Readings", "Charts", "Diagnostics"};
    private static final String[] WIDE_SECTIONS = {"Overview", "Charts", "Diagnostics"};
    private boolean favouritesOnly;
    private AlertDialog metricDialog;
    private final TextView status, batteryHeadline, batteryDetails, batteryCaveat, filterButton,
            resultCount, rawValues, chartTitle, livePower, favouritesHint;
    private final TextView refreshButton;
    private final LinearLayout favouritesCard, deviceCard, eventsCard;
    private final View deviceLabel;
    private final Ui.BatteryRing batteryRing;
    private final Ui.ChartView chart;
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
        favouritesOnly = preferences.getBoolean("stats_favourites_only", false);
        query = preferences.getString("stats_query", "");
        chartKey = preferences.getString("stats_chart_key", "power_w");
        int widthDp = host.activity().getResources().getConfiguration().screenWidthDp;
        // Same trigger as MainActivity's side rail: the content area beside the rail is wide enough for two panes.
        wide = widthDp >= WIDE_DP;
        selectedSection = validSection(preferences.getString("stats_section", "Overview"));
        // Readings has its own pane when wide; a saved Readings tab opens Overview beside it (the saved choice is kept).
        if (wide && selectedSection.equals("Readings")) selectedSection = "Overview";
        status = ui.caption(""); status.setPadding(0, 0, 0, ui.dp(10));
        overviewSection = vertical(); readingsSection = vertical(); chartsSection = vertical(); diagnosticsSection = vertical();
        View tabs = fittedTabs(ui.sectionTabs(wide ? WIDE_SECTIONS : SECTIONS, selectedSection, this::selectSection));
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
        overviewSection.addView(ui.sectionLabel("Favourites"));
        favouritesCard = ui.groupCard(); overviewSection.addView(favouritesCard);
        favouritesHint = ui.caption("No favourites yet. In Readings, tap the star beside a reading to show it here.");
        favouritesHint.setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(8)); overviewSection.addView(favouritesHint);

        // Charts: one card with the measured series, its chips and the two-minute window note.
        LinearLayout chartCard = ui.card(); chartTitle = ui.text("Measured battery power", 17, true); chartCard.addView(chartTitle);
        if (Build.VERSION.SDK_INT >= 28) chartTitle.setAccessibilityHeading(true);
        livePower = ui.caption("Direction remains unconfirmed until you establish what the scooter's sign means.");
        livePower.setPadding(0, ui.dp(5), 0, ui.dp(8)); chartCard.addView(livePower);
        LinearLayout choices = new LinearLayout(host.activity()); choices.setOrientation(LinearLayout.HORIZONTAL); choices.setGravity(Gravity.CENTER_VERTICAL);
        addChartChoice(choices, "Power", "power_w"); addChartChoice(choices, "Voltage", "voltage_v"); addChartChoice(choices, "Speed", "speed_kmh");
        chartCard.addView(choices, new LinearLayout.LayoutParams(-1, -2)); chart = ui.new ChartView(); chartCard.addView(chart);
        TextView window = ui.caption("2-minute window"); window.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11 * ui.textScale);
        window.setGravity(Gravity.CENTER_HORIZONTAL); window.setPadding(0, ui.dp(2), 0, 0); chartCard.addView(window); chartsSection.addView(chartCard);

        // Readings: search, the Favourites filter chip with a count, then collapsible groups.
        search = new EditText(host.activity()); search.setSingleLine(true); search.setHint("Search");
        ui.styleSearch(search); search.setContentDescription("Search scooter measurements"); search.setText(query);
        readingsSection.addView(search, ui.searchLayout());
        SearchFocus.bind(search, host.activity().getWindow());
        LinearLayout filters = new LinearLayout(host.activity()); filters.setOrientation(LinearLayout.HORIZONTAL); filters.setGravity(Gravity.CENTER_VERTICAL);
        filterButton = ui.text("Favourites", 12.5f, false); filterButton.setTypeface(ui.semibold());
        filterButton.setGravity(Gravity.CENTER); filterButton.setMinHeight(ui.dp(48)); filterButton.setMinWidth(ui.dp(48));
        filterButton.setPadding(ui.dp(14), 0, ui.dp(14), 0); filterButton.setFocusable(true); filterButton.setClickable(true);
        filterButton.setContentDescription("Show favourites only");
        filterButton.setOnClickListener(v -> {
            favouritesOnly = !favouritesOnly; preferences.edit().putBoolean("stats_favourites_only", favouritesOnly).apply(); applyFilter();
        });
        filters.addView(filterButton, new LinearLayout.LayoutParams(-2, -2));
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
        LinearLayout actions = new LinearLayout(host.activity()); actions.setOrientation(LinearLayout.HORIZONTAL);
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
    /**
     * Keeps the section tabs (four on a phone, three in the wide left pane) on one row: trims tab padding only as far as the
     * width requires, then shares any spare width so the row spans the page. At very large text
     * the shared picker still wraps rather than clipping a label.
     */
    private View fittedTabs(View tabs) {
        LinearLayout holder = new LinearLayout(host.activity()) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                fitTabs(tabs, MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight());
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        holder.addView(tabs, new LinearLayout.LayoutParams(-1, -2));
        return holder;
    }
    private void fitTabs(View view, int available) {
        if (!(view instanceof ViewGroup) || available <= 0) return;
        ViewGroup group = (ViewGroup) view; int count = group.getChildCount(); if (count == 0) return;
        for (int i = 0; i < count; i++) if (!(group.getChildAt(i) instanceof TextView)) return;
        int inner = available - group.getPaddingLeft() - group.getPaddingRight() - ui.dp(6) * (count - 1) - ui.dp(1);
        int[] paddings = {12, 10, 8, 6, 4};
        int[] natural = new int[count];
        for (int padding : paddings) {
            int total = 0;
            for (int i = 0; i < count; i++) {
                TextView tab = (TextView) group.getChildAt(i);
                natural[i] = Math.max(ui.dp(48), (int) Math.ceil(tab.getPaint().measureText(tab.getText().toString())) + 2 * ui.dp(padding) + 1);
                total += natural[i];
            }
            boolean fits = total <= inner, last = padding == paddings[paddings.length - 1];
            if (!fits && !last) continue;
            int extra = fits ? (inner - total) / count : 0;
            for (int i = 0; i < count; i++) {
                TextView tab = (TextView) group.getChildAt(i); int side = ui.dp(padding), width = fits ? natural[i] + extra : ui.dp(48);
                if (tab.getPaddingLeft() != side || tab.getPaddingRight() != side)
                    tab.setPadding(side, tab.getPaddingTop(), side, tab.getPaddingBottom());
                if (tab.getMinWidth() != width) tab.setMinWidth(width);
            }
            return;
        }
    }
    private void showSection() {
        overviewSection.setVisibility(selectedSection.equals("Overview") ? View.VISIBLE : View.GONE);
        readingsSection.setVisibility(readingsShown() ? View.VISIBLE : View.GONE);
        chartsSection.setVisibility(selectedSection.equals("Charts") ? View.VISIBLE : View.GONE);
        diagnosticsSection.setVisibility(selectedSection.equals("Diagnostics") ? View.VISIBLE : View.GONE);
    }
    private void selectSection(String section) {
        selectedSection = validSection(section); showSection();
        if (!readingsShown()) {
            InputMethodManager keyboard = host.activity().getSystemService(InputMethodManager.class);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
            search.clearFocus();
        }
        preferences.edit().putString("stats_section", selectedSection).apply();
        // When wide only the left pane changes, so only it returns to the top.
        refresh(); sectionScroll.post(() -> sectionScroll.scrollTo(0, 0));
    }
    private void addChartChoice(LinearLayout row, String label, String key) {
        TextView chip = ui.chip(label, key.equals(chartKey), () -> {
            chartKey = key; preferences.edit().putString("stats_chart_key", key).apply();
            for (Map.Entry<String, TextView> e : chartChips.entrySet()) ui.styleChip(e.getValue(), e.getKey().equals(chartKey));
            refreshChart();
        });
        chartChips.put(key, chip);
        // The pill stays chip-sized; its frame extends the touch target to 48dp.
        FrameLayout target = new FrameLayout(host.activity()); target.setMinimumHeight(ui.dp(48));
        target.addView(chip, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL));
        target.setOnClickListener(v -> chip.performClick());
        target.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.rightMargin = ui.dp(6); row.addView(target, p);
    }
    private void addMetric(TelemetryHistory.Reading metric) {
        Group group = groups.get(metric.group);
        if (group == null) {
            LinearLayout wrapper = vertical(), body = ui.groupCard();
            TextView title = ui.text(metric.group, 11, false); title.setTypeface(ui.semibold()); title.setTextColor(ui.muted);
            title.setLetterSpacing(0.08f); title.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            title.setMinHeight(ui.dp(48)); title.setPadding(ui.dp(12), 0, ui.dp(12), 0);
            // A small dark pill drawn inside the full 48dp touch target.
            title.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 30)),
                    new InsetDrawable(ui.shape(ui.surface, 12, 0), 0, ui.dp(5), 0, ui.dp(5)), null));
            title.setFocusable(true); title.setClickable(true);
            if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
            boolean expanded = preferences.getBoolean("stats_group_" + metric.group, metric.group.equals("Battery") || metric.group.equals("Ride"));
            group = new Group(metric.group, wrapper, body, title, expanded); final Group created = group;
            title.setOnClickListener(v -> { created.expanded = !created.expanded; preferences.edit().putBoolean("stats_group_" + created.name, created.expanded).apply(); applyFilter(); });
            wrapper.addView(title, new LinearLayout.LayoutParams(-1, -2)); wrapper.addView(body);
            groups.put(metric.group, group); readingsSection.addView(wrapper);
        }
        // The divider sits above its row; applyFilter hides it for the first visible row of a group.
        View divider = new View(host.activity()); divider.setBackgroundColor(ui.raised); divider.setTag(metric.key);
        group.body.addView(divider, new LinearLayout.LayoutParams(-1, ui.dp(1)));
        LinearLayout row = new LinearLayout(host.activity()); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, ui.dp(dense ? 4 : 8), 0, ui.dp(dense ? 4 : 8)); row.setMinimumHeight(ui.dp(dense ? 48 : 56));
        LinearLayout words = vertical(); String name = isKnownDriveMode(metric.key) ? "Drive mode" : metric.label;
        TextView label = ui.text(name, dense ? 13 : 14, false); words.addView(label);
        TextView source = ui.caption(""); source.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11 * ui.textScale); source.setPadding(0, ui.dp(2), 0, 0); words.addView(source);
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        TextView amount = ui.text("Not reported", dense ? 13 : 14, true); amount.setGravity(Gravity.END); amount.setMaxWidth(ui.dp(150));
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
        Row row = rows.get(key); if (row != null) styleStar(row);
        applyFilter(); refresh();
    }
    private void styleFavouritesFilter() {
        boolean on = favouritesOnly;
        SpannableStringBuilder label = new SpannableStringBuilder(on ? "★" : "☆");
        label.setSpan(new ForegroundColorSpan(on ? ui.orange : ui.muted), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        label.append("  Favourites");
        filterButton.setText(label); filterButton.setTextColor(on ? ui.orange : ui.text); filterButton.setSelected(on);
        Drawable pill = ui.shape(ui.surface, 100, on ? Ui.withAlpha(ui.orange, 120) : 0);
        filterButton.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.orange, 40)),
                new InsetDrawable(pill, 0, ui.dp(5), 0, ui.dp(5)), null));
        if (Build.VERSION.SDK_INT >= 30) filterButton.setStateDescription(on ? "On" : "Off");
    }
    private void applyFilter() {
        String needle = query.trim().toLowerCase(Locale.ROOT); int count = 0;
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (Row row : rows.values()) {
            boolean show = (!favouritesOnly || favourites.contains(row.key)) && (needle.isEmpty()
                    || (row.label + " " + row.key + " " + row.group).toLowerCase(Locale.ROOT).contains(needle));
            row.layout.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) { count++; counts.put(row.group, counts.containsKey(row.group) ? counts.get(row.group) + 1 : 1); }
        }
        for (Group group : groups.values()) {
            int matches = counts.containsKey(group.name) ? counts.get(group.name) : 0;
            group.card.setVisibility(matches == 0 ? View.GONE : View.VISIBLE);
            boolean open = group.expanded || !needle.isEmpty() || favouritesOnly;
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
            group.title.setText(group.name.toUpperCase(Locale.ROOT) + (open ? "  ▾" : "  ▸"));
            group.title.setContentDescription(group.name + " readings");
            if (Build.VERSION.SDK_INT >= 30) group.title.setStateDescription(open ? "Expanded" : "Collapsed");
            else group.title.setContentDescription(group.name + " readings, " + (open ? "expanded" : "collapsed"));
        }
        styleFavouritesFilter();
        resultCount.setText(count == 0 ? favouritesOnly && needle.isEmpty() ? "No favourites yet" : "No matching readings"
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
        if (selectedSection.equals("Charts")) refreshChart();
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
    private void refreshFavourites(Map<String, TelemetryHistory.Reading> readings) {
        // Rebuild only when the chosen readings or their live state change; values update in place.
        StringBuilder signature = new StringBuilder(); List<TelemetryHistory.Reading> chosen = new ArrayList<TelemetryHistory.Reading>();
        for (TelemetryHistory.Reading reading : readings.values()) {
            if (!favourites.contains(reading.key)) continue;
            chosen.add(reading); signature.append(reading.key).append(reading.live && reading.value != null ? "+" : "-").append(',');
        }
        if (!signature.toString().equals(favouritesShown)) {
            favouritesShown = signature.toString(); favouritesCard.removeAllViews(); favouriteRows.clear();
            for (TelemetryHistory.Reading reading : chosen) {
                if (!favouriteRows.isEmpty()) ui.divider(favouritesCard);
                ValueRow row = valueRow(reading.key, isKnownDriveMode(reading.key) ? "Drive mode" : reading.label,
                        readingIcon(reading.key), reading.live && reading.value != null);
                favouritesCard.addView(row.layout); favouriteRows.put(reading.key, row);
            }
            favouritesCard.setVisibility(chosen.isEmpty() ? View.GONE : View.VISIBLE);
            favouritesHint.setVisibility(chosen.isEmpty() ? View.VISIBLE : View.GONE);
        }
        for (Map.Entry<String, ValueRow> e : favouriteRows.entrySet()) updateValueRow(e.getValue(), e.getKey(), readings.get(e.getKey()));
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
        LinearLayout row = new LinearLayout(host.activity()); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, ui.dp(dense ? 6 : 8), 0, ui.dp(dense ? 6 : 8)); row.setMinimumHeight(ui.dp(dense ? 48 : 56));
        if (icon != null) {
            LinearLayout.LayoutParams square = new LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)); square.rightMargin = ui.dp(12);
            row.addView(ui.iconSquare(icon, live ? ui.accent : ui.dim), square);
        }
        LinearLayout words = vertical();
        TextView name = ui.text(label, dense ? 13.5f : 14.5f, false); words.addView(name);
        TextView time = ui.caption(""); time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11 * ui.textScale); time.setPadding(0, ui.dp(2), 0, 0);
        time.setVisibility(View.GONE); words.addView(time);
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        TextView amount = ui.text("", dense ? 13.5f : 14.5f, true); amount.setGravity(Gravity.END); amount.setMaxWidth(ui.dp(160));
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
    private void refreshChart() {
        boolean imperial = !"km".equals(preferences.getString("units", "mi"));
        String unit = chartKey.equals("voltage_v") ? "V" : chartKey.equals("speed_kmh") ? imperial ? "mph" : "km/h" : "W";
        int color = chartKey.equals("voltage_v") ? ui.blue : chartKey.equals("speed_kmh") ? ui.orange : ui.accent;
        chartTitle.setText(chartKey.equals("voltage_v") ? "Observed battery voltage" : chartKey.equals("speed_kmh") ? "Measured scooter speed" : "Measured signed battery power");
        long offset = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        List<Ui.Point> values = new ArrayList<Ui.Point>();
        for (Ui.Point point : service.trend(chartKey)) values.add(new Ui.Point(point.millis + offset,
                chartKey.equals("speed_kmh") && imperial ? point.value / RideStore.KM_PER_MILE : point.value));
        chart.set(values, unit, service.readingFresh(chartKey) ? color : ui.muted, "Connect for measured " + unit + " readings");
        livePower.setText(chartKey.equals("power_w") ? "Signed power. Confirm current direction in Rides to label draw and regeneration."
                : chartKey.equals("voltage_v") ? "Battery voltage under the current load."
                : "Speed reported by the scooter.");
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
    @Override public void close() {
        if (metricDialog != null) metricDialog.dismiss();
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
