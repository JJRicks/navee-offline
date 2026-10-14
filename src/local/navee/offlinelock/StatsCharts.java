package local.navee.offlinelock;

import android.app.Dialog;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.*;
import java.util.function.Consumer;

/** Searchable, lazy chart views over the same numerical observations that the phone retains. */
final class StatsCharts {
    static final long[] WINDOWS = {120000, 600000, 3600000, 21600000, 86400000, 604800000, 2592000000L, 0};
    static final String[] WINDOW_LABELS = {"2 min", "10 min", "1 hour", "6 hours", "24 hours", "7 days", "30 days", "All"};
    final LinearLayout view;
    final EditText search;
    final LinkedHashMap<String, Group> groups = new LinkedHashMap<>();
    final LinkedHashMap<String, Card> cards = new LinkedHashMap<>();
    private final LinkedHashSet<Card> instances = new LinkedHashSet<>();
    private final LinkedHashMap<String, TelemetryHistory.Reading> catalog = new LinkedHashMap<>();
    private final Set<String> recorded = new LinkedHashSet<>();
    private final ScreenHost host;
    private final Ui ui;
    private final ScooterService service;
    private final SharedPreferences prefs;
    private final Set<String> favourites;
    private final Consumer<String> toggleFavourite;
    private final LinearLayout groupHolder;
    private final TextView count;
    private final StatsWindowPicker windowSelector;
    private String query, source = "", catalogSignature = "";
    private long lastDiscovery = -60000;
    private int discoveryGeneration, windowIndex;
    private boolean discovering, closed;
    private Dialog fullscreen;
    private Card fullscreenCard;
    private StatsWindowPicker fullscreenWindow;

    final class Group {
        final LinearLayout card, body;
        final TextView title;
        boolean expanded;
        Group(String name) {
            card = ui.groupCard(); body = column();
            card.setPadding(ui.dp(14), ui.dp(2), ui.dp(14), ui.dp(2));
            title = ui.text(name, 17, true); title.setGravity(Gravity.CENTER_VERTICAL);
            title.setMinHeight(ui.dp(60)); title.setPadding(0, ui.dp(10), 0, ui.dp(10));
            title.setFocusable(true);
            if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
            expanded = prefs.getBoolean("stats_chart_group_" + name, false);
            title.setOnClickListener(v -> { expanded = !expanded;
                prefs.edit().putBoolean("stats_chart_group_" + name, expanded).apply(); filter(); });
            card.addView(title, new LinearLayout.LayoutParams(-1, -2)); card.addView(body);
        }
    }

    StatsCharts(ScreenHost host, Set<String> favourites, Consumer<String> toggleFavourite) {
        this.host = host; ui = host.ui(); service = host.service(); prefs = service.settings;
        this.favourites = favourites; this.toggleFavourite = toggleFavourite;
        catalog.putAll(service.displayReadings());
        view = column();
        windowIndex = Math.max(0, Math.min(WINDOWS.length - 1, prefs.getInt("stats_chart_window", 0)));
        windowSelector = selector(); windowSelector.setTag("stats-chart-window");
        LinearLayout.LayoutParams pickerSpace = new LinearLayout.LayoutParams(-1, -2); pickerSpace.bottomMargin = ui.dp(12);
        view.addView(windowSelector, pickerSpace);
        TextView retention = ui.caption("Older history is summarized. Missing intervals stay visible.");
        retention.setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(12)); view.addView(retention);
        search = new EditText(host.activity()); search.setSingleLine(true); search.setHint("Search");
        ui.styleSearch(search); search.setContentDescription("Search recorded charts");
        query = prefs.getString("stats_chart_query", ""); search.setText(query);
        view.addView(search, ui.searchLayout()); SearchFocus.bind(search, host.activity().getWindow());
        count = ui.caption(""); count.setGravity(Gravity.END); count.setMinHeight(ui.dp(48));
        count.setPadding(ui.dp(12), 0, ui.dp(4), 0);
        LinearLayout.LayoutParams countSpace = new LinearLayout.LayoutParams(-1, -2); countSpace.bottomMargin = ui.dp(4);
        view.addView(count, countSpace);
        groupHolder = column(); view.addView(groupHolder);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int n, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int n) {
                query = s.toString(); prefs.edit().putString("stats_chart_query", query).apply(); filter();
            }
            public void afterTextChanged(Editable e) {}
        });
        refresh();
    }
    private LinearLayout column() { LinearLayout v = new LinearLayout(host.activity()); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private StatsWindowPicker selector() {
        return new StatsWindowPicker(ui, WINDOW_LABELS, windowIndex, this::selectWindow);
    }
    private void selectWindow(int index) {
        if (index < 0 || index >= WINDOWS.length || index == windowIndex) return;
        windowIndex = index; prefs.edit().putInt("stats_chart_window", index).apply();
        windowSelector.select(index, true); if (fullscreenWindow != null) fullscreenWindow.select(index, true);
        for (Card card : instances) card.reset();
        refresh();
    }
    private String profileId() { ScooterProfiles.Profile p = service.profiles.active(); return p == null ? null : p.id; }
    private String source() { return String.valueOf(profileId()) + ":" + service.simulating(); }
    void refresh() {
        if (closed) return;
        String nextSource = source();
        if (!nextSource.equals(source)) {
            source = nextSource; recorded.clear(); discoveryGeneration++; discovering = false; lastDiscovery = -60000;
            for (Card card : instances) card.reset();
        }
        Map<String, TelemetryHistory.Reading> readings = service.displayReadings(); catalog.putAll(readings);
        for (TelemetryHistory.Reading r : readings.values()) if (r.number != null) recorded.add(r.key);
        rebuild();
        long now = SystemClock.elapsedRealtime();
        if (!service.simulating() && !discovering && service.history != null && now - lastDiscovery >= 60000) {
            discovering = true; lastDiscovery = now; final int generation = discoveryGeneration;
            final String requestedSource = source;
            service.history.recordedKeys(profileId(), keys -> {
                if (closed || generation != discoveryGeneration || !requestedSource.equals(source())) return;
                discovering = false; if (recorded.addAll(keys)) { rebuild(); refreshVisible(); }
            });
        }
        refreshVisible();
    }
    /** Called on scroll as well as incoming data; offscreen or collapsed charts do not query. */
    void refreshVisible() {
        if (closed) return;
        for (Card card : new ArrayList<>(instances)) card.update();
    }
    private void rebuild() {
        StringBuilder signature = new StringBuilder();
        for (String key : catalog.keySet()) if (recorded.contains(key)) signature.append(key).append('\n');
        if (signature.toString().equals(catalogSignature)) { if (cards.isEmpty()) count.setText("No recorded numerical readings yet"); return; }
        catalogSignature = signature.toString();
        for (Card card : cards.values()) release(card);
        cards.clear(); groups.clear(); groupHolder.removeAllViews();
        for (TelemetryHistory.Reading reading : catalog.values()) if (recorded.contains(reading.key)) {
            Group group = groups.get(reading.group);
            if (group == null) { group = new Group(reading.group); groups.put(reading.group, group); groupHolder.addView(group.card); }
            Card card = new Card(reading.key, false); cards.put(reading.key, card); group.body.addView(card.view);
        }
        filter();
    }
    private void filter() {
        String needle = query.trim().toLowerCase(Locale.ROOT); int matches = 0;
        for (Map.Entry<String, Group> entry : groups.entrySet()) {
            int groupMatches = 0;
            for (Card card : cards.values()) {
                TelemetryHistory.Reading r = catalog.get(card.key);
                if (!r.group.equals(entry.getKey())) continue;
                boolean match = (r.label + " " + r.key + " " + r.group).toLowerCase(Locale.ROOT).contains(needle);
                card.view.setVisibility(match ? View.VISIBLE : View.GONE); if (match) groupMatches++;
            }
            Group group = entry.getValue(); boolean open = group.expanded || !needle.isEmpty();
            group.card.setVisibility(groupMatches > 0 ? View.VISIBLE : View.GONE);
            group.body.setVisibility(open ? View.VISIBLE : View.GONE);
            group.title.setText(entry.getKey());
            android.graphics.drawable.Drawable arrow = new StatsScreen.StatsGlyph(false, open, ui.text);
            arrow.setBounds(0, 0, ui.dp(26), ui.dp(26));
            group.title.setCompoundDrawablesRelative(null, null, arrow, null);
            group.title.setContentDescription(entry.getKey() + " charts, " + (open ? "expanded" : "collapsed"));
            if (Build.VERSION.SDK_INT >= 30) group.title.setStateDescription(open ? "Expanded" : "Collapsed");
            matches += groupMatches;
        }
        count.setText(cards.isEmpty() ? "No recorded numerical readings yet" : matches == 0 ? "No matching charts" : matches + (matches == 1 ? " chart" : " charts"));
        view.post(this::refreshVisible);
    }
    Card favourite(String key) { return new Card(key, true); }
    void release(Card card) { card.disposed = true; card.generation++; instances.remove(card); }
    void starsChanged() { for (Card card : instances) card.styleStar(); }
    final class Card {
        final String key;
        final LinearLayout view;
        final TextView latest, star;
        Ui.ChartView graph;
        private View placeholder;
        private List<TelemetryHistory.Point> saved = Collections.emptyList();
        private final List<TelemetryHistory.Point> tail = new ArrayList<>();
        private long queriedAt = -10000;
        private int generation;
        private boolean querying, disposed, wasLive;
        Card(String key, boolean favourite) {
            this.key = key; view = column(); view.setTag("stats-chart-card:" + key);
            view.setPadding(0, ui.dp(8), 0, ui.dp(14));
            LinearLayout heading = new LinearLayout(host.activity()); heading.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = ui.text(label(key), 15, true); title.setTag("stats-chart-title:" + key);
            heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            star = ui.text("", 21, false); star.setGravity(Gravity.CENTER); star.setFocusable(true);
            star.setOnClickListener(v -> toggleFavourite.accept(key));
            heading.addView(star, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
            TextView expand = ui.text("⤢", 24, false); expand.setGravity(Gravity.CENTER); expand.setFocusable(true);
            expand.setContentDescription("Expand " + label(key) + " chart"); expand.setOnClickListener(v -> expand(key));
            heading.addView(expand, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
            view.addView(heading);
            latest = ui.caption(""); latest.setPadding(0, ui.dp(2), 0, ui.dp(4)); view.addView(latest);
            placeholder = new View(host.activity()); view.addView(placeholder, new LinearLayout.LayoutParams(-1, ui.dp(220)));
            instances.add(this); styleStar();
        }
        void styleStar() {
            boolean on = favourites.contains(key); star.setText(on ? "★" : "☆"); star.setSelected(on);
            star.setTextColor(on ? ui.orange : ui.dim);
            star.setContentDescription((on ? "Remove " : "Add ") + label(key) + " chart " + (on ? "from" : "to") + " favourites");
            if (Build.VERSION.SDK_INT >= 30) star.setStateDescription(on ? "Favourite" : "Not a favourite");
        }
        private void ensureGraph() {
            if (graph != null) return;
            int position = view.indexOfChild(placeholder); view.removeView(placeholder); placeholder = null;
            graph = ui.new ChartView(); graph.setTag("stats-chart:" + key); graph.setHistoryMode(true);
            view.addView(graph, position, new LinearLayout.LayoutParams(-1, ui.dp(220)));
        }
        private void reset() {
            generation++; querying = false; queriedAt = -10000; saved = Collections.emptyList(); tail.clear(); wasLive = false;
            if (graph != null) graph.set(Collections.emptyList(), unit(key), ui.muted, "Loading recorded readings…");
        }
        private void update() {
            if (disposed || !view.isShown() || !view.getGlobalVisibleRect(new Rect())) return;
            ensureGraph();
            TelemetryHistory.Reading reading = service.displayReadings().get(key);
            long wall = System.currentTimeMillis(), now = SystemClock.elapsedRealtime(), window = WINDOWS[windowIndex];
            boolean live = reading != null && reading.live && reading.number != null;
            latest.setText(reading == null || reading.number == null ? "Not currently reported" : value(key, reading.number)
                    + (service.simulating() ? " · Sample" : live ? "" : " · Last seen " + DisplayTime.recent(host.activity(), prefs, reading.capturedWallMs, wall)));
            latest.setTextColor(live ? ui.text : ui.muted);
            if (service.simulating()) {
                // The sample source is never persisted or mixed into real observations.
                saved = service.simulationHistory(key, wall - (window == 0 ? 86400000 : window), wall); tail.clear(); draw(live); return;
            }
            if (live && (tail.isEmpty() || reading.capturedWallMs > tail.get(tail.size() - 1).wallMs)) {
                boolean gap = !wasLive || (!tail.isEmpty() && reading.capturedWallMs - tail.get(tail.size() - 1).wallMs > TelemetryHistory.freshnessMs(key));
                tail.add(new TelemetryHistory.Point(reading.capturedWallMs, reading.number, gap));
            }
            wasLive = live;
            while (!tail.isEmpty() && (tail.size() > 120 || tail.get(0).wallMs < wall - (window == 0 ? 120000 : Math.min(window, 120000)))) tail.remove(0);
            if (service.history != null && !querying && now - queriedAt >= 5000) {
                querying = true; queriedAt = now; final int expectedGeneration = generation; final String expectedSource = source();
                service.history.queryWindow(profileId(), key, window, wall, points -> {
                    if (closed || disposed || generation != expectedGeneration || !expectedSource.equals(source())) return;
                    querying = false; saved = points;
                    if (view.isShown()) draw(service.readingFresh(key));
                });
            }
            draw(live);
        }
        private void draw(boolean live) {
            if (graph == null) return;
            List<Ui.Point> points = new ArrayList<>(); long now = System.currentTimeMillis(), window = WINDOWS[windowIndex];
            // Keep persisted observations collected while this card was offscreen. Only the
            // not-yet-persisted tail extends history; an old live tail must never mask newer DB rows.
            long lastSaved = saved.isEmpty() ? Long.MIN_VALUE : saved.get(saved.size() - 1).wallMs;
            for (TelemetryHistory.Point p : saved) if (window == 0 || p.wallMs >= now - window)
                points.add(new Ui.Point(p.wallMs, number(key, p.value), p.gapBefore));
            for (TelemetryHistory.Point p : tail) if (p.wallMs > lastSaved && (window == 0 || p.wallMs >= now - window))
                points.add(new Ui.Point(p.wallMs, number(key, p.value), p.gapBefore));
            graph.set(points, unit(key), live ? ui.accent : ui.muted, "No recorded readings in this window");
        }
    }
    private String label(String key) { TelemetryHistory.Reading r = catalog.get(key); return r == null ? key : r.label; }
    private double number(String key, double value) {
        if (DisplayTemperature.isTemperature(key)) return DisplayTemperature.fromCelsius(value, prefs.getString("temperature_unit", "c"));
        if ((key.endsWith("_kmh") || key.endsWith("_km")) && !"km".equals(prefs.getString("units", "mi"))) return value / RideStore.KM_PER_MILE;
        return value;
    }
    private String unit(String key) {
        if (DisplayTemperature.isTemperature(key)) return DisplayTemperature.unit(prefs.getString("temperature_unit", "c"));
        if (!"km".equals(prefs.getString("units", "mi"))) {
            if (key.endsWith("_kmh")) return "mph"; if (key.endsWith("_km")) return "mi";
        }
        return TelemetryHistory.unitFor(key);
    }
    private String value(String key, double raw) {
        double n = number(key, raw); return String.format(Locale.US, Math.abs(n) >= 1000 ? "%.0f" : "%.1f", n) + (unit(key).isEmpty() ? "" : " " + unit(key));
    }
    private void expand(String key) {
        if (fullscreen != null) fullscreen.dismiss();
        fullscreen = new Dialog(host.activity(), ui.dark ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        fullscreen.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout content = column(); content.setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(18)); content.setBackgroundColor(ui.bg);
        LinearLayout top = new LinearLayout(host.activity()); top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = ui.text(label(key), 20, true); top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView close = ui.text("×", 26, false); close.setGravity(Gravity.CENTER); close.setContentDescription("Close expanded chart");
        close.setOnClickListener(v -> fullscreen.dismiss()); top.addView(close, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48))); content.addView(top);
        fullscreenWindow = selector(); content.addView(fullscreenWindow, new LinearLayout.LayoutParams(-1, -2));
        fullscreenCard = new Card(key, false); fullscreenCard.ensureGraph();
        fullscreenCard.view.getChildAt(0).setVisibility(View.GONE);
        fullscreenCard.graph.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1));
        content.addView(fullscreenCard.view, new LinearLayout.LayoutParams(-1, 0, 1));
        fullscreen.setContentView(content);
        final Dialog opened = fullscreen; final Card expanded = fullscreenCard;
        final StatsWindowPicker expandedWindow = fullscreenWindow;
        fullscreen.setOnDismissListener(d -> {
            release(expanded); expandedWindow.close();
            if (fullscreen == opened) { fullscreenCard = null; fullscreenWindow = null; fullscreen = null; }
        });
        fullscreen.show(); Window window = fullscreen.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(ui.bg));
            window.getDecorView().setPadding(0, 0, 0, 0);
            window.setStatusBarColor(ui.bg); window.setNavigationBarColor(ui.bg);
            if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false);
            else window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            content.setOnApplyWindowInsetsListener((v, insets) -> {
                int left, topInset, right, bottom;
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets safe = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                            | android.view.WindowInsets.Type.displayCutout());
                    left = safe.left; topInset = safe.top; right = safe.right; bottom = safe.bottom;
                } else { left = insets.getSystemWindowInsetLeft(); topInset = insets.getSystemWindowInsetTop();
                    right = insets.getSystemWindowInsetRight(); bottom = insets.getSystemWindowInsetBottom(); }
                v.setPadding(ui.dp(18) + left, ui.dp(18) + topInset, ui.dp(18) + right, ui.dp(18) + bottom);
                return insets;
            });
            if (Build.VERSION.SDK_INT >= 30 && window.getInsetsController() != null) {
                int mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                window.getInsetsController().setSystemBarsAppearance(ui.dark ? 0 : mask, mask);
            }
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            content.requestApplyInsets();
        }
        content.post(this::refreshVisible);
    }
    void close() {
        closed = true; discoveryGeneration++; if (fullscreen != null) fullscreen.dismiss(); windowSelector.close();
        for (Card card : new ArrayList<>(instances)) release(card);
    }
}
