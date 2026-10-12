package local.navee.offlinelock;

import android.content.SharedPreferences;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
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
    private final ScrollView scroll;
    private final LinearLayout page, overviewSection, readingsSection, chartsSection, diagnosticsSection;
    private final LinkedHashMap<String, Row> rows = new LinkedHashMap<String, Row>();
    private final LinkedHashMap<String, Group> groups = new LinkedHashMap<String, Group>();
    private final Set<String> favourites;
    private final boolean dense;
    private String query, chartKey, selectedSection;
    private final EditText search;
    private static final String[] SECTIONS = {"Overview", "Readings", "Charts", "Diagnostics"};
    private boolean favouritesOnly;
    private AlertDialog metricDialog;
    private final TextView status, batteryHeadline, batteryDetails, batteryCaveat, filterButton,
            resultCount, rawValues, eventValues, chartTitle, livePower;
    private final TextView refreshButton;
    private final Ui.BatteryRing batteryRing;
    private final Ui.ChartView chart;
    private static final class Row {
        final String key, group, label;
        final LinearLayout layout;
        final TextView amount, evidence, star;
        Row(String key, String group, String label, LinearLayout layout, TextView amount, TextView evidence, TextView star) {
            this.key = key; this.group = group; this.label = label; this.layout = layout;
            this.amount = amount; this.evidence = evidence; this.star = star;
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

    public StatsScreen(ScreenHost host) {
        this.host = host; ui = host.ui(); service = host.service(); preferences = service.settings;
        dense = "dense".equals(preferences.getString("stats_density", "comfortable"));
        favourites = new LinkedHashSet<String>(preferences.getStringSet("stats_favourites", new LinkedHashSet<String>()));
        favouritesOnly = preferences.getBoolean("stats_favourites_only", false);
        query = preferences.getString("stats_query", "");
        chartKey = preferences.getString("stats_chart_key", "power_w");
        selectedSection = validSection(preferences.getString("stats_section", "Overview"));
        scroll = new ScrollView(host.activity()); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        page = new LinearLayout(host.activity()); page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(ui.dp(18), ui.dp(6), ui.dp(18), ui.dp(24)); scroll.addView(page);
        status = ui.caption(""); status.setPadding(0, 0, 0, ui.dp(14)); page.addView(status);
        page.addView(ui.sectionTabs(SECTIONS, selectedSection, this::selectSection));
        overviewSection = vertical(); readingsSection = vertical(); chartsSection = vertical(); diagnosticsSection = vertical();
        for (LinearLayout section : new LinearLayout[] {overviewSection, readingsSection, chartsSection, diagnosticsSection}) {
            section.setPadding(0, ui.dp(12), 0, 0); page.addView(section);
        }

        boolean compactBattery = host.activity().getResources().getConfiguration().screenWidthDp < 360;
        LinearLayout battery = ui.card(); battery.setOrientation(compactBattery ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL); battery.setGravity(Gravity.CENTER_VERTICAL);
        batteryRing = ui.new BatteryRing(); battery.addView(batteryRing, new LinearLayout.LayoutParams(ui.dp(144), ui.dp(160)));
        LinearLayout batteryWords = vertical(); batteryWords.setPadding(compactBattery ? 0 : ui.dp(12), compactBattery ? ui.dp(10) : 0, 0, 0);
        batteryWords.addView(ui.caption("Battery"));
        batteryHeadline = ui.text("Waiting for readings", 20, true); batteryHeadline.setPadding(0, ui.dp(8), 0, ui.dp(10)); batteryWords.addView(batteryHeadline);
        batteryDetails = ui.text("", 13, false); batteryDetails.setTextColor(ui.muted); batteryWords.addView(batteryDetails);
        battery.addView(batteryWords, compactBattery ? new LinearLayout.LayoutParams(-1, -2) : new LinearLayout.LayoutParams(0, -2, 1)); overviewSection.addView(battery);
        batteryCaveat = ui.caption("Reported health is a controller estimate, not a measured capacity test.");
        batteryCaveat.setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(16)); overviewSection.addView(batteryCaveat);

        LinearLayout chartCard = ui.card(); chartTitle = ui.text("Measured battery power", 16, true); chartCard.addView(chartTitle);
        livePower = ui.caption("Direction remains unconfirmed until you establish what the scooter's sign means.");
        livePower.setPadding(0, ui.dp(7), 0, ui.dp(12)); chartCard.addView(livePower);
        LinearLayout choices = new LinearLayout(host.activity()); choices.setOrientation(LinearLayout.HORIZONTAL);
        addChartChoice(choices, "Power", "power_w"); addChartChoice(choices, "Voltage", "voltage_v"); addChartChoice(choices, "Speed", "speed_kmh");
        chartCard.addView(choices); chart = ui.new ChartView(); chartCard.addView(chart);
        chartCard.addView(ui.caption("Two minutes of measured data. Gaps remain visible. Record a ride for a persistent history.")); chartsSection.addView(chartCard);

        LinearLayout guidance = ui.card(); guidance.addView(ui.text("Compare rides", 16, true));
        TextView help = ui.caption("Record a ride to compare distance, battery use and energy per mile. Battery health above is reported by the scooter; measured capacity needs a longer test.");
        help.setPadding(0, ui.dp(8), 0, 0); guidance.addView(help); overviewSection.addView(guidance);
        TextView heading = ui.caption("Tap a reading for its source and favourite setting."); heading.setPadding(0, ui.dp(4), 0, ui.dp(12));
        search = new EditText(host.activity()); search.setSingleLine(true); search.setHint("Search voltage, odometer, firmware…");
        search.setTextColor(ui.text); search.setHintTextColor(ui.muted); search.setTextSize(14 * ui.textScale);
        search.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12)); search.setMinimumHeight(ui.dp(48));
        search.setBackground(ui.shape(ui.raised, 16, ui.outline)); search.setContentDescription("Search scooter measurements"); search.setText(query);
        readingsSection.addView(search, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout filters = new LinearLayout(host.activity()); filters.setOrientation(LinearLayout.HORIZONTAL); filters.setGravity(Gravity.CENTER_VERTICAL);
        filterButton = ui.secondaryButton("Favourites", () -> {
            favouritesOnly = !favouritesOnly; preferences.edit().putBoolean("stats_favourites_only", favouritesOnly).apply(); applyFilter();
        });
        filters.addView(filterButton, new LinearLayout.LayoutParams(-2, -2));
        resultCount = ui.caption(""); resultCount.setPadding(ui.dp(12), 0, 0, 0); filters.addView(resultCount, new LinearLayout.LayoutParams(0, -2, 1)); readingsSection.addView(filters);
        readingsSection.addView(heading);
        for (Telemetry.Metric metric : service.ble.telemetry.metrics()) addMetric(metric);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                query = s.toString(); preferences.edit().putString("stats_query", query).apply(); applyFilter();
            }
            public void afterTextChanged(Editable e) {}
        });

        LinearLayout evidence = ui.card(); evidence.addView(ui.text("Diagnostics", 18, true));
        TextView explanation = ui.caption("Payloads include the response status followed by data. Unsupported fields and units stay raw. Owner credentials and PIN responses are excluded from these diagnostics.");
        explanation.setPadding(0, ui.dp(8), 0, ui.dp(10)); evidence.addView(explanation);
        refreshButton = ui.secondaryButton("Refresh diagnostic reads", () -> { service.ble.refresh(true); host.toast("Requested all supported diagnostic reads."); }); evidence.addView(refreshButton);
        evidence.addView(ui.button("Export diagnostics JSON", () -> host.saveText("navee-diagnostics.json", "application/json", service.snapshot())));
        rawValues = ui.text("No accepted read payloads yet.", 11, false); rawValues.setTypeface(Typeface.MONOSPACE); rawValues.setTextColor(ui.muted);
        rawValues.setTextIsSelectable(true); rawValues.setPadding(0, ui.dp(12), 0, 0); evidence.addView(rawValues); diagnosticsSection.addView(evidence);
        LinearLayout events = ui.card(); events.addView(ui.text("Recent connection events", 16, true));
        eventValues = ui.text("", 11, false); eventValues.setTypeface(Typeface.MONOSPACE); eventValues.setTextColor(ui.muted); eventValues.setPadding(0, ui.dp(10), 0, 0); events.addView(eventValues); diagnosticsSection.addView(events);
        applyFilter(); showSection(); refresh();
    }
    private LinearLayout vertical() { LinearLayout result = new LinearLayout(host.activity()); result.setOrientation(LinearLayout.VERTICAL); return result; }
    private static String validSection(String section) {
        for (String known : SECTIONS) if (known.equals(section)) return known;
        return "Overview";
    }
    private void showSection() {
        overviewSection.setVisibility(selectedSection.equals("Overview") ? View.VISIBLE : View.GONE);
        readingsSection.setVisibility(selectedSection.equals("Readings") ? View.VISIBLE : View.GONE);
        chartsSection.setVisibility(selectedSection.equals("Charts") ? View.VISIBLE : View.GONE);
        diagnosticsSection.setVisibility(selectedSection.equals("Diagnostics") ? View.VISIBLE : View.GONE);
    }
    private void selectSection(String section) {
        selectedSection = validSection(section); showSection();
        if (!selectedSection.equals("Readings")) {
            InputMethodManager keyboard = host.activity().getSystemService(InputMethodManager.class);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
            search.clearFocus();
        }
        preferences.edit().putString("stats_section", selectedSection).apply();
        refresh(); scroll.post(() -> scroll.scrollTo(0, 0));
    }
    private void addChartChoice(LinearLayout row, String label, String key) {
        TextView button = ui.secondaryButton(label, () -> { chartKey = key; preferences.edit().putString("stats_chart_key", key).apply(); refreshChart(); });
        button.setTextSize(12 * ui.textScale); button.setPadding(ui.dp(8), ui.dp(10), ui.dp(8), ui.dp(10));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1); p.rightMargin = ui.dp(5); row.addView(button, p);
    }
    private void addMetric(Telemetry.Metric metric) {
        Group group = groups.get(metric.group);
        if (group == null) {
            LinearLayout card = ui.card(), body = vertical();
            TextView title = ui.text(metric.group, 16, true); title.setMinimumHeight(ui.dp(48)); title.setGravity(Gravity.CENTER_VERTICAL);
            boolean expanded = preferences.getBoolean("stats_group_" + metric.group, metric.group.equals("Battery") || metric.group.equals("Ride"));
            group = new Group(metric.group, card, body, title, expanded); final Group created = group;
            title.setContentDescription(metric.group + " measurements, expand or collapse");
            title.setOnClickListener(v -> { created.expanded = !created.expanded; preferences.edit().putBoolean("stats_group_" + created.name, created.expanded).apply(); applyFilter(); });
            card.addView(title); card.addView(body); groups.put(metric.group, group); readingsSection.addView(card);
        }
        LinearLayout row = new LinearLayout(host.activity()); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, ui.dp(dense ? 8 : 14), 0, ui.dp(dense ? 8 : 14)); row.setMinimumHeight(ui.dp(dense ? 52 : 64));
        LinearLayout words = vertical(); TextView label = ui.text(metric.label, dense ? 13 : 14, false); words.addView(label);
        TextView source = ui.caption(""); source.setTextSize(11 * ui.textScale); source.setPadding(0, ui.dp(4), 0, 0); words.addView(source);
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        TextView amount = ui.text("Not reported", dense ? 13 : 14, true); amount.setGravity(Gravity.END); amount.setMaxWidth(ui.dp(150));
        LinearLayout.LayoutParams amountParams = new LinearLayout.LayoutParams(-2, -2); amountParams.leftMargin = ui.dp(12); row.addView(amount, amountParams);
        TextView star = ui.text("★", 13, false); star.setTextColor(ui.orange); star.setPadding(ui.dp(8), 0, 0, 0);
        star.setVisibility(favourites.contains(metric.key) ? View.VISIBLE : View.GONE); star.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); row.addView(star);
        row.setFocusable(true); row.setOnClickListener(v -> showMetricDetails(metric.key));
        group.body.addView(row); rows.put(metric.key, new Row(metric.key, metric.group, metric.label, row, amount, source, star));
        View divider = new View(host.activity()); divider.setBackgroundColor(ui.outline); group.body.addView(divider, new LinearLayout.LayoutParams(-1, ui.dp(1)));
        divider.setTag(metric.key);
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
            for (int i = 0; i < group.body.getChildCount(); i++) {
                View child = group.body.getChildAt(i);
                if (child.getTag() instanceof String) {
                    Row row = rows.get(child.getTag()); if (row != null) child.setVisibility(row.layout.getVisibility());
                }
            }
            group.title.setText(group.name + (open ? "  −" : "  +"));
        }
        filterButton.setText(favouritesOnly ? "Show all" : "Favourites"); filterButton.setTextColor(favouritesOnly ? ui.accent : ui.muted);
        resultCount.setText(count == 0 ? "No matching measurements" : count + (count == 1 ? " measurement" : " measurements"));
    }
    @Override public View view() { return scroll; }
    @Override public void refresh() {
        long now = SystemClock.elapsedRealtime();
        Map<String, Telemetry.Metric> metrics = new LinkedHashMap<String, Telemetry.Metric>();
        for (Telemetry.Metric m : service.ble.telemetry.metrics()) {
            metrics.put(m.key, m);
            if (!selectedSection.equals("Readings")) continue;
            Row row = rows.get(m.key); if (row == null) continue;
            row.amount.setText(display(m)); row.amount.setTextColor(m.value == null ? ui.muted : ui.text);
            boolean stale = m.updatedMs >= 0 && now - m.updatedMs > 15000;
            row.evidence.setText(m.updatedMs < 0 ? "Not supplied" : age(now - m.updatedMs));
            row.evidence.setVisibility(stale ? View.VISIBLE : View.GONE);
            row.star.setVisibility(favourites.contains(m.key) ? View.VISIBLE : View.GONE);
            row.layout.setContentDescription(m.label + ", " + display(m) + ", " + row.evidence.getText() + ". Tap for details.");
        }
        status.setText("Disconnected · last known readings");
        status.setVisibility(service.ble.connected() ? View.GONE : View.VISIBLE);
        status.setTextColor(service.ble.connected() ? ui.accent : ui.orange); refreshButton.setEnabled(service.ble.connected());
        if (selectedSection.equals("Overview")) {
            Telemetry.Metric soc = metrics.get("battery_percent"), temp = metrics.get("temperature_c"), voltage = metrics.get("voltage_v");
            // charging_state is explicitly raw; do not invent a charging badge from an unconfirmed enum.
            batteryRing.set(soc == null ? null : soc.numericValue, soc == null || soc.updatedMs < 0 ? "No reading" : age(now - soc.updatedMs), false);
            batteryHeadline.setText(voltage == null || voltage.value == null ? "Waiting for voltage" : voltage.value);
            batteryDetails.setText("Temperature  " + value(temp) + "\nReported health  " + value(metrics.get("battery_health_percent"))
                    + "\nRated capacity  " + value(metrics.get("rated_capacity_ah")));
        }
        if (selectedSection.equals("Charts")) refreshChart();
        if (!selectedSection.equals("Diagnostics")) return;
        StringBuilder raw = new StringBuilder(); Map<Integer, Integer> statuses = service.ble.telemetry.statuses();
        for (Map.Entry<Integer, byte[]> e : service.ble.telemetry.frames().entrySet()) {
            if (ScooterCommands.sensitiveCommand(e.getKey())) continue;
            raw.append(String.format(Locale.US, "0x%02X", e.getKey())).append("  status ").append(statuses.get(e.getKey())).append("  ")
                    .append(e.getValue().length).append(" bytes\n").append(Protocol.hex(e.getValue())).append("\n\n");
        }
        rawValues.setText(raw.length() == 0 ? "No accepted read payloads yet." : raw.toString().trim());
        List<String> events = service.ble.events(); StringBuilder recent = new StringBuilder();
        for (int i = Math.max(0, events.size() - 12); i < events.size(); i++) { if (recent.length() > 0) recent.append('\n'); recent.append(events.get(i)); }
        eventValues.setText(recent.length() == 0 ? "No connection events yet." : recent.toString());
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
        chart.set(values, unit, color, "Connect for measured " + unit + " readings");
        livePower.setText(chartKey.equals("power_w") ? "Signed power. Confirm current direction in Rides to label draw and regeneration."
                : chartKey.equals("voltage_v") ? "Battery voltage under the current load."
                : "Speed reported by the scooter.");
    }
    private void showMetricDetails(String key) {
        Telemetry.Metric metric = null;
        for (Telemetry.Metric candidate : service.ble.telemetry.metrics()) if (candidate.key.equals(key)) { metric = candidate; break; }
        if (metric == null) return;
        String source = metric.updatedMs < 0 ? "Not supplied" : age(SystemClock.elapsedRealtime() - metric.updatedMs)
                + " · response " + String.format(Locale.US, "0x%02X", metric.sourceCmd);
        String detail = display(metric) + "\n\n" + source + "\n" + metric.key;
        if (key.equals("energy_throughput_wh")) detail += "\n\nThis field's meaning is unverified. It is not used to calculate ride energy.";
        metricDialog = new AlertDialog.Builder(host.activity()).setTitle(metric.label).setMessage(detail)
                .setNeutralButton(favourites.contains(key) ? "Remove favourite" : "Add favourite", (d, which) -> {
                    if (!favourites.remove(key)) favourites.add(key);
                    preferences.edit().putStringSet("stats_favourites", new LinkedHashSet<String>(favourites)).apply();
                    applyFilter(); refresh();
                }).setPositiveButton("Close", null).create(); metricDialog.show();
    }
    @Override public void close() { if (metricDialog != null) metricDialog.dismiss(); }
    private String display(Telemetry.Metric metric) {
        if (metric.value == null) return "Not reported";
        String units = preferences.getString("units", "mi");
        if (metric.key.equals("stored_trips")) return DisplayUnits.metricText(metric.value, units);
        if (metric.numericValue == null || "km".equals(units)) return metric.value;
        boolean speed = metric.key.endsWith("_kmh"), distance = metric.key.endsWith("_km");
        if (!speed && !distance) return metric.value;
        String converted = String.format(Locale.US, "%.2f %s", metric.numericValue / RideStore.KM_PER_MILE, speed ? "mph" : "mi");
        return "dual".equals(units) ? metric.value + "  /  " + converted : converted;
    }
    private static String value(Telemetry.Metric m) { return m == null || m.value == null ? "Not reported" : m.value; }
    private static String age(long millis) {
        if (millis < 0) return "Clock changed";
        if (millis < 1000) return "Just received";
        if (millis < 60000) return millis / 1000 + "s ago";
        if (millis < 3600000) return millis / 60000 + "m ago";
        return millis / 3600000 + "h ago";
    }
}
