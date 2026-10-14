package local.navee.offlinelock;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** Local preferences, owner setup, security and explained experimental controls. */
public final class MoreScreen implements AppScreen {
    private final ScreenHost host;
    private final Ui ui;
    private final ScrollView scroll;
    private final LinearLayout content;
    /** Unfolded/tablet width: the menu stays on the left and the chosen submenu opens on the right. */
    private final boolean wide;
    private final boolean standalone;
    /** Wide mode only: each pane scrolls on its own. */
    private ScrollView menuScroll, sectionScroll;
    private final Row[] menuRows = new Row[SECTIONS.length];
    private LinearLayout cardDestination, onboardingCard, sectionMenu, indexView, sectionHeader;
    private View sectionBack;
    private TextView sectionTitle, sectionBackLabel;
    private static final String[] SECTIONS = {"App", "Scooters", "Security", "Lab", "Data", "About", "Developer"};
    private static final String[] SECTION_LABELS = {"App preferences", "My scooters", "PIN & proximity unlock",
            "Experimental controls", "Data & history", "About & permissions", "Developer options"};
    private static final String[] SECTION_TITLES = {"App preferences", "My scooters", "PIN & proximity",
            "Firmware lab", "Data & history", "About & permissions", "Developer options"};
    private static final String[] SECTION_ICONS = {"settings", "scooter", "shield", "lab", "history", "info", "lab"};
    /** TelemetryHistory's database file; only its size on disk is read here, never its contents. */
    private static final String HISTORY_DATABASE = "telemetry-history.db";
    private final Map<String,LinearLayout> sections = new LinkedHashMap<>();
    private final Map<String,Integer> sectionScrollPositions = new LinkedHashMap<>();
    private String selectedSection;
    private boolean closed;
    private final List<Runnable> valueRefreshers = new ArrayList<>();
    private final List<SegmentedSelector> preferenceSelectors = new ArrayList<>();
    private final List<AlertDialog> dialogs = new ArrayList<>();
    private final List<Row> profileEditActions = new ArrayList<>();
    private Row renameProfileRow, forgetProfileRow, backupProfileRow, disconnectProfileRow, chooseProfileRow;
    private Row scootersIndexRow, dataIndexRow;
    private Row readPinRow, setPinRow, bondRow, pinRow, proximityRow;
    private Row nearbyRow, locationRow, notificationRow;
    private Row developerIndexRow, simulationRow;
    private TextView simulationStatus, chargeCaptureStatus, chargeLearningStatus;
    private Row chargeCaptureRow, clearChargeRow, exportChargeRow;
    private SegmentedSelector chargeChargerSelector;
    private ChargeSessionStore.Charger chosenCharger=ChargeSessionStore.Charger.STOCK;
    private android.widget.Toast developerCountdown;
    private int developerTaps;
    private long lastDeveloperTap;
    private TextView securityNotice, setupState, connectionSummary;
    private LinearLayout indexProfileCard, scootersProfileCard;
    private ImageView indexPhoto, scootersPhoto;
    private View indexPhotoFallback, scootersPhotoFallback;
    private TextView indexProfileName, indexProfileState, scootersName, scootersRole, scootersModel;
    private TextView labSummary, historyUsage, historyStatus;
    private TextView[] historyCounts;
    private LinearLayout historyBar;
    private View historyBarFill, historyBarRest;
    private LinearLayout historyStorageCard;
    private boolean historyStoragePending;
    private long historyStorageChecked;
    private static Bitmap scooterPhoto;
    private final List<LabRow> labRows = new ArrayList<>();
    private static final class LabRow {
        final ScooterCommands.Feature feature;
        final TextView value, action;
        LabRow(ScooterCommands.Feature feature, TextView value, TextView action) {
            this.feature = feature; this.value = value; this.action = action;
        }
    }

    public MoreScreen(ScreenHost host) { this(host, null); }

    /** Dashboard sheet: the existing controls, without changing More's saved navigation state. */
    MoreScreen(ScreenHost host, String initialSection) {
        this.host = host; ui = host.ui();
        standalone = initialSection != null;
        wide = !standalone && host.activity().getResources().getConfiguration().screenWidthDp >= 650;
        scroll = new PageScroll();
        scroll.setFillViewport(true); scroll.setClipToPadding(false);
        content = column();
        if (wide) content.setPadding(ui.dp(24), ui.dp(6), ui.dp(24), 0);
        else content.setPadding(ui.dp(18), ui.dp(6), ui.dp(18), ui.dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(-1, wide ? -1 : -2));
        selectedSection = standalone ? initialSection : settings().getString("more_section", "");
        if (!knownSection(selectedSection)) selectedSection = "";

        LinearLayout menuParent = content, sectionParent = content;
        if (wide) {
            menuParent = column(); sectionParent = column();
            menuScroll = paneScroll(menuParent); sectionScroll = paneScroll(sectionParent);
            content.addView(new Panes(menuScroll, sectionScroll), new LinearLayout.LayoutParams(-1, -1));
        }
        indexView = column(); menuParent.addView(indexView, new LinearLayout.LayoutParams(-1, -2));
        cardDestination = indexView;
        indexProfile();
        onboarding();
        sectionMenu = group();
        if (wide) sectionMenu.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4));
        for (int i = 0; i < SECTIONS.length; i++) {
            final String section = SECTIONS[i];
            if (!knownSection(section)) continue;
            Row row = row(sectionMenu, SECTION_ICONS[i], SECTION_LABELS[i], null, () -> selectSection(section));
            // Room for the selected row's red tint to frame its icon in the wide menu.
            if (wide) row.view.setPadding(ui.dp(8), ui.dp(9), ui.dp(8), ui.dp(9));
            menuRows[i] = row;
            if (section.equals("Scooters")) scootersIndexRow = row;
            else if (section.equals("Data")) dataIndexRow = row;
            else if (section.equals("Lab")) badge(row.value, "LAB");
            else if (section.equals("Developer")) {
                developerIndexRow = row;
                row.view.setTag("developer-options-menu");
            }
        }
        TextView footer = ui.text("Navee Offline " + AppVersion.name(host.activity()) + " · not affiliated with NAVEE", 11.5f, false);
        footer.setTextColor(ui.muted); footer.setGravity(Gravity.CENTER);
        footer.setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(8));
        indexView.addView(footer, new LinearLayout.LayoutParams(-1, -2));

        sectionHeader = column(); sectionParent.addView(sectionHeader, new LinearLayout.LayoutParams(-1, -2));
        sectionBack = backLink(); sectionHeader.addView(sectionBack, new LinearLayout.LayoutParams(-2, -2));
        // The menu beside the submenu is the way back, so the wide layout has no "‹ More" link.
        if (wide) sectionBack.setVisibility(View.GONE);
        sectionTitle = ui.text("", 24, true); sectionTitle.setPadding(ui.dp(2), 0, 0, ui.dp(4));
        if (Build.VERSION.SDK_INT >= 28) sectionTitle.setAccessibilityHeading(true);
        // Picking a row on the left changes the right pane in place; announce its new title.
        if (wide) sectionTitle.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        sectionHeader.addView(sectionTitle);

        for (String section : SECTIONS) {
            if (!knownSection(section)) continue;
            cardDestination = column(); sections.put(section, cardDestination);
            sectionParent.addView(cardDestination, new LinearLayout.LayoutParams(-1, -2));
            if (section.equals("App")) preferences();
            else if (section.equals("Scooters")) profiles();
            else if (section.equals("Security")) security();
            else if (section.equals("Lab")) lab();
            else if (section.equals("Data")) dataHistory();
            else if (section.equals("Developer")) developerOptions();
            else privacy();
        }
        cardDestination = column(); sections.put("Units", cardDestination);
        sectionParent.addView(cardDestination, new LinearLayout.LayoutParams(-1, -2));
        units();
        cardDestination = content;
        showSelectedSection();
        refresh();
        restoreWideScroll();
    }
    @Override public View view() { return scroll; }
    @Override public void close() {
        saveWideScroll();
        closed = true;
        if (developerCountdown != null) { developerCountdown.cancel(); developerCountdown = null; }
        for (SegmentedSelector selector : preferenceSelectors) selector.finishMotion();
        for (AlertDialog dialog : new ArrayList<>(dialogs)) if (dialog.isShowing()) dialog.dismiss();
        dialogs.clear();
    }

    /**
     * Wide mode: MainActivity only remembers the outer page scroller, which stays put while the two
     * panes scroll. Keep the panes' positions across a brief tab return or rebuild, including the
     * default App preferences pane shown with no submenu chosen. MainActivity drops the saved
     * positions when the submenu expires or a shortcut opens one, so nothing stale is restored.
     */
    private static final String WIDE_SECTION_SCROLL = "more_wide_section_scroll", WIDE_MENU_SCROLL = "more_wide_menu_scroll";
    private void saveWideScroll() {
        if (!wide || closed || service() == null || sectionScroll == null || menuScroll == null) return;
        settings().edit().putString(WIDE_SECTION_SCROLL, shownSection() + ":" + sectionScroll.getScrollY())
                .putInt(WIDE_MENU_SCROLL, menuScroll.getScrollY()).apply();
    }
    private void restoreWideScroll() {
        if (!wide || service() == null || sectionScroll == null || menuScroll == null) return;
        SharedPreferences prefs = settings();
        String saved = prefs.getString(WIDE_SECTION_SCROLL, "");
        int split = saved.lastIndexOf(':');
        final int section = split > 0 && saved.substring(0, split).equals(shownSection()) ? parseScroll(saved.substring(split + 1)) : 0;
        final int menu = prefs.getInt(WIDE_MENU_SCROLL, 0);
        Motion.scrollBeforeDraw(sectionScroll, section); Motion.scrollBeforeDraw(menuScroll, menu);
    }
    /**
     * The submenu expired while this screen stayed alive (the app was in the background for more
     * than a minute). Put the live panes back at the top so neither this screen nor the close()
     * of an expiry rebuild carries the old offsets forward.
     */
    public void resetScroll() {
        sectionScrollPositions.clear();
        if (sectionScroll != null) sectionScroll.scrollTo(0, 0);
        if (menuScroll != null) menuScroll.scrollTo(0, 0);
    }
    private static int parseScroll(String value) {
        try { return Math.max(0, Integer.parseInt(value)); } catch (NumberFormatException e) { return 0; }
    }

    /** Units returns to its parent; otherwise wide Back leaves the tab, keeping its selected pane. */
    public boolean back() {
        if ("Units".equals(selectedSection)) { selectSection("App"); return true; }
        if (standalone || wide || selectedSection.isEmpty()) return false;
        selectSection(""); return true;
    }

    private boolean knownSection(String section) {
        if ("Units".equals(section)) return true;
        if ("Developer".equals(section) && !developerEnabled()) return false;
        for (String candidate : SECTIONS) if (candidate.equals(section)) return true;
        return false;
    }
    private boolean developerEnabled() { return settings().getBoolean("developer_options", false); }
    /**
     * The submenu on screen. Wide mode always shows one: with none chosen (first visit, or one expired
     * after a minute away) it opens App preferences without storing it, so the retention rules are unchanged.
     */
    private String shownSection() { return wide && selectedSection.isEmpty() ? SECTIONS[0] : selectedSection; }
    private ScrollView sectionScroller() { return wide ? sectionScroll : scroll; }
    private void showSelectedSection() {
        String shown = shownSection();
        boolean index = shown.isEmpty();
        indexView.setVisibility(index || wide ? View.VISIBLE : View.GONE);
        boolean units = "Units".equals(shown);
        sectionHeader.setVisibility(index || (standalone && !units) ? View.GONE : View.VISIBLE);
        sectionBack.setVisibility(units || !wide ? View.VISIBLE : View.GONE);
        sectionBackLabel.setText(units ? "App preferences" : "More");
        sectionBack.setContentDescription(units ? "Back to App preferences" : "Back to More");
        if (units) sectionTitle.setText("Units");
        for (int i = 0; i < SECTIONS.length; i++) if (SECTIONS[i].equals(shown)) sectionTitle.setText(SECTION_TITLES[i]);
        for (Map.Entry<String,LinearLayout> section : sections.entrySet())
            section.getValue().setVisibility(section.getKey().equals(shown) ? View.VISIBLE : View.GONE);
        if (index || wide) dataIndexRow.value(historyFileSize());
        if (wide) for (int i = 0; i < SECTIONS.length; i++)
            if (menuRows[i] != null) markMenuRow(menuRows[i], SECTIONS[i].equals(units ? "App" : shown));
    }
    /** Wide menu: the row whose submenu is open on the right gets a red tint and the selected state. */
    private void markMenuRow(Row row, boolean selected) {
        row.view.setSelected(selected);
        row.view.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 26)),
                selected ? ui.shape(Ui.withAlpha(ui.accent, ui.dark ? 46 : 30), 14, 0) : null, ui.shape(Color.WHITE, 14, 0)));
    }
    private void selectSection(String section) {
        if (closed || (!section.isEmpty() && !knownSection(section)) || section.equals(selectedSection)) return;
        final ScrollView pane = sectionScroller();
        String leaving = shownSection();
        sectionScrollPositions.put(leaving, pane.getScrollY());
        Motion.page(pane, sectionDirection(leaving, section), () -> {
            selectedSection = section;
            if (!standalone) settings().edit().putString("more_section", section).apply();
            showSelectedSection(); refresh();
            String shown = shownSection();
            Motion.scrollBeforeDraw(pane, sectionScrollPositions.containsKey(shown) ? sectionScrollPositions.get(shown) : 0);
        });
    }
    /** Deeper levels arrive from the right and leave the same way back; wide panes simply crossfade. */
    private int sectionDirection(String from, String to) {
        if ("Units".equals(to)) return 1;
        if ("Units".equals(from)) return -1;
        if (wide) return 0;
        return to.isEmpty() ? -1 : 1;
    }

    private ScooterService service() { return host.service(); }
    private ScooterProfiles.Profile active() { return service() == null ? null : service().profiles.active(); }
    private SharedPreferences settings() { return service().settings; }
    private LinearLayout column() {
        LinearLayout view = new LinearLayout(host.activity()); view.setOrientation(LinearLayout.VERTICAL); return view;
    }
    private LinearLayout row() {
        LinearLayout view = new LinearLayout(host.activity()); view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL); return view;
    }

    // ---- Wide (unfolded) layout ------------------------------------------------------------

    /**
     * The page's scroller. On a phone it scrolls the whole page as before. In wide mode it holds its
     * content at exactly its own height so the two panes inside can each scroll on their own.
     */
    private final class PageScroll extends ScrollView {
        PageScroll() { super(host.activity()); }
        @Override protected void measureChildWithMargins(View child, int widthSpec, int widthUsed, int heightSpec, int heightUsed) {
            if (!wide || MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED) {
                super.measureChildWithMargins(child, widthSpec, widthUsed, heightSpec, heightUsed); return;
            }
            MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
            int childWidth = getChildMeasureSpec(widthSpec,
                    getPaddingLeft() + getPaddingRight() + lp.leftMargin + lp.rightMargin + widthUsed, lp.width);
            int height = Math.max(0, MeasureSpec.getSize(heightSpec) - getPaddingTop() - getPaddingBottom()
                    - lp.topMargin - lp.bottomMargin - heightUsed);
            child.measure(childWidth, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        }
    }
    private ScrollView paneScroll(LinearLayout column) {
        ScrollView pane = new ScrollView(host.activity());
        pane.setClipToPadding(false); pane.setPadding(0, 0, 0, ui.dp(24));
        pane.addView(column, new ScrollView.LayoutParams(-1, -2));
        return pane;
    }
    /**
     * Menu on the left, submenu on the right, 20dp apart. The gap sits on the window's centre line, where
     * a book-posture hinge falls, so no row lies under the fold; this also gives the menu the narrower
     * column of the approved mockup. Each pane keeps a usable minimum width.
     */
    private final class Panes extends LinearLayout {
        final View first, gutter, second;
        int split = -1;
        Panes(View left, View right) {
            super(host.activity()); setOrientation(HORIZONTAL); setBaselineAligned(false);
            first = left; second = right; gutter = new View(host.activity());
            addView(first, new LinearLayout.LayoutParams(0, -1));
            addView(gutter, new LinearLayout.LayoutParams(ui.dp(20), -1));
            addView(second, new LinearLayout.LayoutParams(0, -1));
        }
        private int split(int size) {
            int gap = ui.dp(20), min = ui.dp(220), max = size - gap - ui.dp(260);
            if (max < min) return Math.max(0, (size - gap) / 2);
            int want = Math.round((size - gap) * 0.42f);
            View window = getRootView();
            if (isAttachedToWindow() && window != null && window.getWidth() > 0) {
                int[] at = new int[2]; getLocationInWindow(at);
                want = window.getWidth() / 2 - gap / 2 - at[0];
            }
            return Math.max(min, Math.min(max, want));
        }
        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); post(this::requestLayout); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int size = MeasureSpec.getSize(widthSpec), gap = ui.dp(20);
            split = split(size);
            ((LinearLayout.LayoutParams) first.getLayoutParams()).width = split;
            ((LinearLayout.LayoutParams) second.getLayoutParams()).width = Math.max(0, size - split - gap);
            super.onMeasure(widthSpec, heightSpec);
        }
        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            // The window position is only final now; re-split once if it moved the centre line.
            if (split(r - l) != split) post(this::requestLayout);
        }
    }

    // ---- Volt building blocks -------------------------------------------------------------

    /** Small red "‹ More" link above a submenu title. */
    private View backLink() {
        LinearLayout link = row();
        link.setMinimumHeight(ui.dp(48)); link.setPadding(0, 0, ui.dp(14), 0);
        link.addView(ui.icon("back", 16, ui.accent), new LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)));
        TextView label = sectionBackLabel = ui.text("More", 14, false); label.setTypeface(ui.medium()); label.setTextColor(ui.accent);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.leftMargin = ui.dp(4);
        link.addView(label, lp);
        link.setContentDescription("Back to More"); link.setFocusable(true);
        link.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 30)), null, ui.shape(Color.WHITE, 10, 0)));
        link.setOnClickListener(v -> selectSection("Units".equals(selectedSection) ? "App" : ""));
        return link;
    }
    /** Group label; keeps natural case in its text so screen readers say words, not letters. */
    private TextView label(String text) {
        TextView view = ui.sectionLabel(text); view.setText(text); view.setAllCaps(true);
        cardDestination.addView(view); return view;
    }
    private LinearLayout group() { LinearLayout g = ui.groupCard(); cardDestination.addView(g); return g; }
    private TextView footnote(String value) {
        TextView text = ui.caption(value); text.setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(12));
        cardDestination.addView(text); return text;
    }
    private void badge(TextView value, String text) {
        value.setText(text); value.setVisibility(View.VISIBLE);
        value.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10.5f * ui.textScale);
        value.setTypeface(ui.bold()); value.setLetterSpacing(0.08f); value.setTextColor(ui.orange);
        value.setPadding(ui.dp(7), ui.dp(2), ui.dp(7), ui.dp(2)); value.setMaxLines(1);
        value.setBackground(ui.shape(Color.TRANSPARENT, 6, Ui.withAlpha(ui.orange, 120)));
        value.setContentDescription("Lab");
    }

    /** Boxed row glyph: red when actionable, danger-red for destructive rows, grey with its disabled row. */
    private final class RowIcon extends View {
        final String key; boolean danger;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        RowIcon(String key) {
            super(host.activity()); this.key = key;
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            paint.setStyle(Paint.Style.FILL); paint.setColor(ui.dark ? ui.bg : ui.raised);
            canvas.drawRoundRect(new RectF(0, 0, w, h), ui.dp(10), ui.dp(10), paint);
            ui.drawIcon(canvas, key, !isEnabled() ? ui.dim : danger ? ui.danger : ui.accent, w / 2, h / 2, Math.min(w, h) * .54f);
        }
    }

    /** One divided row in a group card: boxed icon, title, optional sub-text, trailing value and chevron. */
    private final class Row {
        LinearLayout view; RowIcon icon; TextView title, sub, value; View chevron; Switch toggle; boolean danger;
        void value(String text) {
            value.setText(text == null ? "" : text);
            value.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
        }
        void sub(CharSequence text) {
            sub.setText(text == null ? "" : text);
            sub.setVisibility(text == null || text.length() == 0 ? View.GONE : View.VISIBLE);
        }
        void enabled(boolean on) {
            view.setEnabled(on);
            if (icon != null) { icon.setEnabled(on); icon.invalidate(); }
            title.setTextColor(on ? (danger ? ui.danger : ui.text) : ui.muted);
            if (chevron != null) chevron.setAlpha(on ? 1f : .4f);
        }
    }
    private Row row(LinearLayout group, String iconKey, String title, String sub, Runnable action) {
        if (group.getChildCount() > 0) ui.divider(group);
        Row r = new Row();
        r.view = row(); r.view.setMinimumHeight(ui.dp(58)); r.view.setPadding(0, ui.dp(9), 0, ui.dp(9));
        if (iconKey != null) {
            r.icon = new RowIcon(iconKey);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)); ip.rightMargin = ui.dp(12);
            r.view.addView(r.icon, ip);
        }
        LinearLayout words = column();
        r.title = ui.text(title, 15, false); words.addView(r.title);
        r.sub = ui.text("", 11.5f, false); r.sub.setTextColor(ui.muted); r.sub.setPadding(0, ui.dp(3), 0, 0);
        words.addView(r.sub); r.sub(sub);
        r.view.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        r.value = ui.text("", 13.5f, false); r.value.setTextColor(ui.muted); r.value.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        r.value.setMaxLines(2); r.value.setMaxWidth(ui.dp(140));
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-2, -2); vp.leftMargin = ui.dp(10);
        r.view.addView(r.value, vp); r.value(null);
        if (action != null) {
            r.chevron = ui.icon("chevron", 15, ui.muted);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ui.dp(15), ui.dp(15)); cp.leftMargin = ui.dp(8);
            r.view.addView(r.chevron, cp);
            clickable(r.view, action);
        }
        group.addView(r.view, new LinearLayout.LayoutParams(-1, -2));
        return r;
    }
    private void clickable(View view, Runnable action) {
        view.setFocusable(true);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 26)), null, ui.shape(Color.WHITE, 10, 0)));
        view.setOnClickListener(v -> { if (!closed && action != null) action.run(); });
    }
    /** Destructive row: red label, red glyph, no chevron; it always opens a confirmation first. */
    private Row dangerRow(LinearLayout group, String title, Runnable action) {
        Row r = row(group, "trash", title, null, null);
        r.danger = true; r.icon.danger = true; r.title.setTextColor(ui.danger);
        clickable(r.view, action);
        return r;
    }
    /** Only a performed click dispatches a change; programmatic setChecked never sends commands. */
    private Row toggleRow(LinearLayout group, String iconKey, String title, String sub, Consumer<Boolean> onChange) {
        Row r = row(group, iconKey, title, sub, null);
        Switch toggle = new Switch(host.activity());
        toggle.setShowText(false); toggle.setTextOn(""); toggle.setTextOff("");
        toggle.setMinimumWidth(ui.dp(56)); toggle.setMinimumHeight(ui.dp(48));
        ui.styleSwitch(toggle); toggle.setEnabled(false);
        toggle.setContentDescription(title);
        toggle.setOnClickListener(v -> { if (toggle.isEnabled() && onChange != null) onChange.accept(toggle.isChecked()); });
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2); tp.leftMargin = ui.dp(12);
        r.view.addView(toggle, tp); r.toggle = toggle;
        r.view.setOnClickListener(v -> { if (toggle.isEnabled()) toggle.performClick(); });
        r.view.setFocusable(false);
        return r;
    }
    private void refreshSwitch(Row row, Boolean state, boolean enabled) {
        Switch toggle = row.toggle;
        toggle.setChecked(Boolean.TRUE.equals(state));
        toggle.setEnabled(enabled && state != null);
        if (Build.VERSION.SDK_INT >= 30) toggle.setStateDescription(state == null ? "Not reported" : state ? "On" : "Off");
        boolean live = enabled && state != null;
        row.title.setTextColor(live ? ui.text : ui.muted);
        if (row.icon != null) { row.icon.setEnabled(live); row.icon.invalidate(); }
        toggle.setContentDescription(row.title.getText() + (state == null ? ", state not reported" : ""));
    }
    /** Wrapping row of chips; each chip keeps a 48dp touch target around a slimmer visible pill. */
    private final class Flow extends ViewGroup {
        Flow() { super(host.activity()); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec), available = Math.max(1, width - getPaddingLeft() - getPaddingRight());
            int x = 0, y = getPaddingTop(), rowHeight = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                child.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                if (x > 0 && x + child.getMeasuredWidth() > available) { y += rowHeight; x = 0; rowHeight = 0; }
                x += child.getMeasuredWidth() + ui.dp(8); rowHeight = Math.max(rowHeight, child.getMeasuredHeight());
            }
            setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(y + rowHeight + getPaddingBottom(), heightSpec));
        }
        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int available = getWidth() - getPaddingLeft() - getPaddingRight(), x = 0, y = getPaddingTop(), rowHeight = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i); int w = child.getMeasuredWidth(), h = child.getMeasuredHeight();
                if (x > 0 && x + w > available) { y += rowHeight; x = 0; rowHeight = 0; }
                child.layout(getPaddingLeft() + x, y, getPaddingLeft() + x + w, y + h);
                x += w + ui.dp(8); rowHeight = Math.max(rowHeight, h);
            }
        }
    }
    private void styleChip(TextView chip, boolean selected) {
        ui.styleChip(chip, selected);
        Drawable pill = chip.getBackground();
        chip.setBackground(new InsetDrawable(pill, 0, ui.dp(5), 0, ui.dp(5)));
    }
    private ImageView photo() {
        ImageView view = new ImageView(host.activity());
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (scooterPhoto == null) {
            try {
                BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 4;
                scooterPhoto = BitmapFactory.decodeResource(host.activity().getResources(), R.drawable.ut5max, options);
            } catch (RuntimeException | OutOfMemoryError unavailable) { scooterPhoto = null; }
        }
        if (scooterPhoto != null) view.setImageBitmap(scooterPhoto);
        return view;
    }
    private String historyFileSize() {
        try {
            File base = host.activity().getDatabasePath(HISTORY_DATABASE); long total = 0;
            for (String suffix : new String[] {"", "-journal", "-wal", "-shm"}) {
                File file = new File(base.getPath() + suffix); if (file.isFile()) total += file.length();
            }
            return mib(total);
        } catch (RuntimeException unavailable) { return ""; }
    }
    private static String mib(long bytes) {
        return bytes <= 0 ? "" : String.format(Locale.US, "%.1f MiB", bytes / 1048576.0);
    }

    // ---- Index --------------------------------------------------------------------------

    private void indexProfile() {
        LinearLayout c = ui.card(); c.setOrientation(LinearLayout.HORIZONTAL); c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12)); c.setMinimumHeight(ui.dp(80));
        indexPhoto = photo();
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(ui.dp(58), ui.dp(64)); pp.rightMargin = ui.dp(12);
        c.addView(indexPhoto, pp);
        indexPhotoFallback = new RowIcon("scooter");
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)); fp.rightMargin = ui.dp(14);
        c.addView(indexPhotoFallback, fp);
        LinearLayout words = column();
        indexProfileName = ui.text("", 19, true); indexProfileName.setMaxLines(2); words.addView(indexProfileName);
        indexProfileState = ui.text("", 13, false); indexProfileState.setTextColor(ui.muted);
        indexProfileState.setPadding(0, ui.dp(3), 0, 0); words.addView(indexProfileState);
        c.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        View chevron = ui.icon("chevron", 16, ui.text);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)); cp.leftMargin = ui.dp(10);
        c.addView(chevron, cp);
        c.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 26)), ui.shape(ui.surface, 20, 0), null));
        c.setFocusable(true); c.setOnClickListener(v -> selectSection("Scooters"));
        indexProfileCard = c; cardDestination.addView(c);
    }

    private void onboarding() {
        LinearLayout c = ui.card();
        TextView title = ui.text("Your scooter. Your data.", 19, true); c.addView(title);
        TextView detail = ui.caption("One private owner setup connects your already activated scooter directly over Bluetooth.");
        detail.setPadding(0, ui.dp(6), 0, ui.dp(4)); c.addView(detail);
        onboardingCard = c; cardDestination.addView(c);
        setupState = value(c, "Add an owner profile to begin");
        note(c, "1  Sign into NAVEE with your own scooter account.\n"
                + "2  Capture the Bluetooth owner identity once using a computer.\n"
                + "3  Import the private setup file, allow Nearby devices, and connect.");
        TextView guide = ui.button("Open first-time setup guide", this::setupGuide); c.addView(guide);
        LinearLayout actions = ui.groupCard(); actions.setBackground(null); actions.setPadding(0, 0, 0, 0);
        c.addView(actions);
        profileEditActions.add(row(actions, "download", "Import my owner setup", null, () -> { if (canEditProfiles()) host.importOwner(); }));
        row(actions, "bluetooth", "Allow Nearby devices", null, host::requestBluetooth);
    }

    // ---- Shared dialog pieces -----------------------------------------------------------

    private void note(LinearLayout box, String value) {
        TextView text = ui.caption(value); text.setPadding(0, ui.dp(7), 0, ui.dp(7)); box.addView(text);
    }
    private TextView button(LinearLayout box, String label, Runnable action, boolean primary) {
        TextView view = primary ? ui.button(label, action) : ui.text(label, 15, false);
        if (!primary) {
            view.setTextColor(new ColorStateList(new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}}, new int[] {ui.muted, ui.text})); view.setGravity(Gravity.CENTER_VERTICAL); view.setMinHeight(ui.dp(52));
            view.setPadding(ui.dp(2), ui.dp(10), ui.dp(2), ui.dp(10)); view.setFocusable(true);
            view.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 24)), ui.shape(Color.TRANSPARENT, 10, 0), null));
            view.setOnClickListener(v -> { if (action != null) action.run(); });
            if (box.getChildCount() > 0) ui.divider(box);
        }
        ui.decorate(view, ui.labelIcon(label)); box.addView(view); return view;
    }
    private TextView value(LinearLayout box, String label) {
        TextView text = ui.text(label, 15, true); text.setTextColor(ui.accent);
        text.setPadding(0, ui.dp(10), 0, ui.dp(10)); box.addView(text); return text;
    }

    private boolean canEditProfiles() {
        ScooterService s = service();
        if (s == null) { host.toast("Your local scooter service is starting."); return false; }
        if (s.isRecording()) { host.toast("Stop the ride recording before changing owner profiles."); return false; }
        if (s.ble.state() != BleController.State.DISCONNECTED || s.ble.pending() != null) {
            host.toast("Disconnect the scooter before changing owner profiles."); return false;
        }
        return true;
    }

    private EditText input(String hint, int type, boolean multiline) {
        EditText edit = new EditText(host.activity()); edit.setHint(hint); edit.setTextColor(ui.text);
        edit.setHintTextColor(ui.muted); edit.setTextSize(15 * ui.textScale); edit.setInputType(type);
        edit.setSingleLine(!multiline); edit.setMinHeight(ui.dp(52));
        edit.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        edit.setBackground(ui.shape(ui.raised, 13, ui.outline));
        edit.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = ui.dp(8); params.bottomMargin = ui.dp(8); edit.setLayoutParams(params); return edit;
    }
    private LinearLayout dialogBox(String title, String detail) {
        LinearLayout box = column(); box.setPadding(ui.dp(22), ui.dp(22), ui.dp(22), ui.dp(10));
        TextView heading = ui.text(title, 22, true);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        box.addView(heading); note(box, detail); return box;
    }
    /** Two equal buttons at the foot of a custom dialog: a quiet one and the red main action. */
    private TextView[] dialogButtons(LinearLayout box, String quiet, Runnable quietAction, String main, Runnable mainAction) {
        LinearLayout buttons = row(); buttons.setPadding(0, ui.dp(10), 0, ui.dp(12));
        TextView left = ui.secondaryButton(quiet, quietAction), right = ui.button(main, mainAction);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1); lp.rightMargin = ui.dp(6);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -2, 1); rp.leftMargin = ui.dp(6);
        buttons.addView(left, lp); buttons.addView(right, rp); box.addView(buttons);
        return new TextView[] {left, right};
    }
    /** Destructive confirmation: short title, the subject named in the body, "Cancel" and a red action. */
    private void confirm(String title, String body, String action, Runnable onConfirm) {
        LinearLayout box = dialogBox(title, body);
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setView(box).create();
        dialogButtons(box, "Cancel", dialog::dismiss, action, () -> { dialog.dismiss(); onConfirm.run(); });
        show(dialog);
    }
    private void show(AlertDialog dialog) {
        show(dialog, null);
    }
    private void show(AlertDialog dialog, Runnable onDismiss) {
        dialogs.add(dialog);
        dialog.setOnDismissListener(d -> {
            dialogs.remove(dialog);
            if (onDismiss != null) onDismiss.run();
        });
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(ui.shape(ui.surface, 22, ui.outline));
        Motion.present(dialog, Motion.DIALOG);
        dialog.show();
        if (dialog.getButton(AlertDialog.BUTTON_POSITIVE) != null) dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ui.accent);
        if (dialog.getButton(AlertDialog.BUTTON_NEGATIVE) != null) dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(ui.muted);
    }

    // ---- My scooters --------------------------------------------------------------------

    private void profiles() {
        LinearLayout c = ui.card(); c.setOrientation(LinearLayout.HORIZONTAL); c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(ui.dp(14), ui.dp(14), ui.dp(16), ui.dp(14)); c.setMinimumHeight(ui.dp(96));
        scootersPhoto = photo();
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(ui.dp(104), ui.dp(120)); pp.rightMargin = ui.dp(14);
        c.addView(scootersPhoto, pp);
        scootersPhotoFallback = new RowIcon("scooter");
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)); fp.rightMargin = ui.dp(14);
        c.addView(scootersPhotoFallback, fp);
        LinearLayout words = column();
        scootersName = ui.text("", 21, true); scootersName.setMaxLines(2); words.addView(scootersName);
        scootersRole = ui.text("", 13, false); scootersRole.setTextColor(ui.muted); scootersRole.setPadding(0, ui.dp(4), 0, 0);
        words.addView(scootersRole);
        scootersModel = ui.text("", 13, false); scootersModel.setTextColor(ui.muted); scootersModel.setPadding(0, ui.dp(2), 0, 0);
        words.addView(scootersModel);
        c.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        c.setFocusable(true); scootersProfileCard = c; cardDestination.addView(c);
        connectionSummary = footnote("");

        LinearLayout profiles = group();
        chooseProfileRow = row(profiles, "scooter", "Choose a scooter", null, this::chooseProfile);
        profileEditActions.add(chooseProfileRow);
        profileEditActions.add(row(profiles, "plus", "Add with owner identity", null, this::addProfile));
        profileEditActions.add(row(profiles, "download", "Import owner setup file", null, () -> { if (canEditProfiles()) host.importOwner(); }));
        renameProfileRow = row(profiles, "pencil", "Rename active scooter", null, this::renameProfile);

        LinearLayout owner = group();
        disconnectProfileRow = row(owner, "bluetooth", "Disconnect scooter", "Needed before changing profiles", () -> {
            if (service().isRecording()) { host.toast("Stop the ride recording before disconnecting to change profiles."); return; }
            service().ble.disconnect(); refresh();
        });
        backupProfileRow = row(owner, "shield", "Save private owner backup", null, this::backupProfile);
        row(owner, "info", "Run setup again", "Opens the onboarding flow", () -> { OnboardingScreen.restart(host.activity()); host.rebuild(); });
        forgetProfileRow = dangerRow(owner, "Forget active profile", this::forgetProfile);
    }

    private void chooseProfile() {
        if (!canEditProfiles()) return;
        List<ScooterProfiles.Profile> all = service().profiles.all();
        if (all.isEmpty()) { addProfile(); return; }
        String[] names = new String[all.size()]; int selected = -1;
        ScooterProfiles.Profile active = active();
        for (int i = 0; i < all.size(); i++) {
            ScooterProfiles.Profile p = all.get(i);
            names[i] = p.name + (p.role == 1 ? " · shared profile" : " · owner")
                    + (p.mac.isEmpty() ? " · not connected yet" : "");
            if (active != null && active.id.equals(p.id)) selected = i;
        }
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setTitle("Choose scooter")
                .setSingleChoiceItems(names, selected, (d, which) -> {
                    if (!canEditProfiles()) return;
                    service().profiles.select(all.get(which).id); d.dismiss();
                    host.toast("Selected " + all.get(which).name + ". Tap Connect when ready."); host.redraw();
                }).setNegativeButton("Close", null).create(); show(dialog);
    }

    private void addProfile() {
        if (!canEditProfiles()) return;
        LinearLayout box = dialogBox("Add your scooter", "Paste the 12-character owner identity or your private owner backup. Your NAVEE password or cloud token will not work here.");
        EditText name = input("Scooter name", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, false);
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(60)}); box.addView(name);
        EditText identity = input("Private owner identity or backup text",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_FLAG_MULTI_LINE, true);
        identity.setMinLines(2); identity.setMaxLines(4); identity.setVerticalScrollBarEnabled(true);
        identity.setFilters(new InputFilter[]{new InputFilter.LengthFilter(1048576)}); box.addView(identity);
        TextView error = ui.caption(""); error.setTextColor(ui.danger); box.addView(error);
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setView(box).setNegativeButton("Cancel", null)
                .setPositiveButton("Save profile", null).create(); show(dialog, () -> identity.setText(""));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!canEditProfiles()) return;
            try {
                OwnerIdentityParser.Result parsed = OwnerIdentityParser.parse(identity.getText().toString());
                service().profiles.create(name.getText().toString(), parsed.identity, parsed.role);
                dialog.dismiss(); host.toast("Owner setup saved privately on this phone."); host.redraw();
            } catch (IllegalArgumentException invalid) { error.setText(invalid.getMessage()); }
        });
    }
    private void renameProfile() {
        if (!canEditProfiles()) return;
        ScooterProfiles.Profile p = active(); if (p == null) { addProfile(); return; }
        LinearLayout box = dialogBox("Rename scooter", "Give this local profile an easy name.");
        EditText name = input("Scooter name", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, false);
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(60)}); name.setText(p.name); box.addView(name);
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setView(box).setNegativeButton("Cancel", null)
                .setPositiveButton("Save name", null).create(); show(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!canEditProfiles()) return;
            String value = name.getText().toString().trim(); if (value.isEmpty()) { name.setError("Enter a scooter name"); return; }
            p.name = value; service().profiles.update(p); dialog.dismiss(); host.redraw();
        });
    }
    private void backupProfile() {
        ScooterProfiles.Profile p = active(); if (p == null || !p.configured()) { host.toast("Add an owner profile first."); return; }
        LinearLayout box = dialogBox("Private owner backup", "This file contains the Bluetooth identity used to control " + p.name
                + ". Keep it private. It can configure your offline app on another phone.");
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setView(box).setNegativeButton("Cancel", null)
                .setPositiveButton("Save private backup", (d, which) -> {
                    String name = p.name.replaceAll("[^a-zA-Z0-9._-]+", "-");
                    host.saveText("navee-owner-" + name + ".json", "application/json", ScooterProfiles.privateBackup(p));
                }).create(); show(dialog);
    }
    private void forgetProfile() {
        if (!canEditProfiles()) return;
        ScooterProfiles.Profile p = active(); if (p == null) return;
        confirm("Forget scooter?", "This removes the private setup for " + p.name
                        + " from this phone. Keep a private backup if you need to reconnect later. Recorded rides stay saved.",
                "Forget", () -> {
                    if (!canEditProfiles()) return; service().profiles.remove(p.id); host.redraw();
                });
    }

    // ---- App preferences ----------------------------------------------------------------

    private void preferences() {
        segmentedField("Appearance", "theme", new String[]{"Dark", "Follow system", "Light"},
                new String[]{"dark", "system", "light"}, "dark");
        segmentedField("Live Stats detail", "stats_density", new String[]{"Comfortable", "Dense"},
                new String[]{"comfortable", "dense"}, "comfortable");
        View spacer = new View(host.activity());
        cardDestination.addView(spacer, new LinearLayout.LayoutParams(-1, ui.dp(16)));
        LinearLayout display = group();
        display.setTag("preference-display-options");
        Row units = row(display, "ruler", "Units", "Distance, temperature & time", () -> selectSection("Units"));
        units.view.setTag("preference-units-menu");
        Row text = row(display, "text", "Text size", null, this::textSize);
        valueRefreshers.add(() -> text.value(Math.round(settings().getFloat("text_scale", 1f) * 100) + "%"));
        row(display, "map", "Map settings", "Basemap and tile provider", () -> MapPreferences.show(host));
        label("Connection & recording");
        LinearLayout connection = group();
        Row rate = row(connection, "clock", "Telemetry refresh", null, this::telemetryRate);
        valueRefreshers.add(() -> rate.value(settings().getInt("poll_interval_ms", 2000) / 1000 + " s"));
        preferenceToggle(connection, "bluetooth", "Connect automatically", "When the app opens", "auto_connect", true);
        preferenceToggle(connection, "bluetooth", "Stay connected in background", "When off, idle connections close after 10 seconds. Recordings keep running.", "background_connection", false);
        preferenceToggle(connection, "screen", "Keep screen awake", "While a recording is on screen", "keep_screen_awake", true);
        row(connection,"battery","Background recording",null,this::backgroundRecording);
    }
    private void backgroundRecording(){
        LinearLayout box=dialogBox("Background recording",null);
        android.os.PowerManager power=host.activity().getSystemService(android.os.PowerManager.class);
        note(box,power.isIgnoringBatteryOptimizations(host.activity().getPackageName())
                ?"Battery optimization is disabled for this app."
                :"For long recordings, set this app's battery use to Unrestricted in Android settings.");
        note(box,"Start recording while the app is open, then turn the screen off. Ride and charge recordings keep Bluetooth active even when Stay connected in background is off. The recording notification has a Stop action.");
        note(box,"Android 14 and later may let you swipe away the notification while unlocked; that does not stop recording. Force stop, revoked permissions, Bluetooth loss and device-specific battery restrictions can interrupt capture.");
        box.addView(ui.button("Recording notifications",()->openAndroidSettings(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)));
        TextView battery=ui.button("App battery settings",()->openAndroidSettings(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS));
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,-2);bp.topMargin=ui.dp(10);box.addView(battery,bp);
        ScrollView scroller=new ScrollView(host.activity());scroller.addView(box);
        show(new AlertDialog.Builder(host.activity()).setView(scroller).setPositiveButton("Close",null).create());
    }
    private void openAndroidSettings(String action){
        android.content.Intent intent=new android.content.Intent(action);
        if(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS.equals(action))intent.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,host.activity().getPackageName());
        else intent.setData(android.net.Uri.parse("package:"+host.activity().getPackageName()));
        try{host.activity().startActivity(intent);}catch(android.content.ActivityNotFoundException missing){host.toast("Open this app's settings in Android");}
    }
    private void units() {
        segmentedField("Distance & speed", "units", new String[]{"Miles / mph", "km / km/h", "Both"},
                new String[]{"mi", "km", "dual"}, "mi");
        segmentedField("Temperature", "temperature_unit", new String[]{"Celsius · °C", "Fahrenheit · °F"},
                new String[]{"c", "f"}, "c");
        segmentedField("Time format", "time_format", new String[]{"Follow system", "12 hour", "24 hour"},
                new String[]{"system", "12h", "24h"}, "system");
    }
    private void segmentedField(String title, String key, String[] labels, String[] values, String fallback) {
        TextView caption = ui.text(title, 12.5f, false); caption.setTextColor(ui.muted);
        caption.setPadding(ui.dp(2), ui.dp(12), ui.dp(2), ui.dp(6));
        cardDestination.addView(caption);
        SegmentedSelector selector = new SegmentedSelector(ui, title, labels, choice -> {
            if (closed || values[choice].equals(settings().getString(key, fallback))) return;
            settings().edit().putString(key, values[choice]).apply();
            // MainActivity coalesces theme and display-unit rebuilds after the 190ms slide. A second
            // immediate rebuild here would destroy the animation (and the user's scroll position).
            if (key.equals("time_format")) host.redraw();
        });
        selector.setTag("preference-selector-" + key);
        Runnable restyle = () -> {
            String stored = settings().getString(key, fallback); int selected = -1;
            for (int i = 0; i < values.length; i++) if (stored.equals(values[i])) selected = i;
            if (selected < 0) for (int i = 0; i < values.length; i++) if (fallback.equals(values[i])) selected = i;
            selector.select(Math.max(0, selected), false);
        };
        cardDestination.addView(selector, new LinearLayout.LayoutParams(-1, -2));
        preferenceSelectors.add(selector);
        restyle.run(); valueRefreshers.add(restyle);
    }
    private void preferenceToggle(LinearLayout box, String icon, String title, String detail, String key, boolean fallback) {
        Row row = toggleRow(box, icon, title, detail, enabled -> {
            settings().edit().putBoolean(key, enabled).apply(); host.redraw();
        });
        valueRefreshers.add(() -> refreshSwitch(row, settings().getBoolean(key, fallback), true));
    }

    // ---- Data & history -----------------------------------------------------------------

    private void dataHistory() {
        label("Connected history");
        LinearLayout recording = group();
        preferenceToggle(recording, "history", "Save connected history",
                "Readings while connected, no ride needed. GPS and phone sensors still need a ride recording.", "history_enabled", true);
        footnote("Turning history off stops new timeline samples. The most recent readings stay saved so you can view them after disconnecting.");

        LinearLayout storage = ui.card(); historyStorageCard = storage; cardDestination.addView(storage);
        LinearLayout head = row();
        TextView title = ui.text("Local storage", 14, false); title.setTextColor(ui.muted);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        historyUsage = ui.text("Checking…", 17, true); historyUsage.setGravity(Gravity.END); head.addView(historyUsage);
        storage.addView(head);
        historyBar = row(); historyBar.setBackground(ui.shape(ui.raised, 4, 0)); historyBar.setClipToOutline(true);
        historyBarFill = new View(host.activity()); historyBarFill.setBackground(ui.shape(ui.accent, 4, 0));
        historyBarRest = new View(host.activity());
        historyBar.addView(historyBarFill, new LinearLayout.LayoutParams(0, -1, 0f));
        historyBar.addView(historyBarRest, new LinearLayout.LayoutParams(0, -1, 1f));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, ui.dp(8)); bp.topMargin = ui.dp(12); bp.bottomMargin = ui.dp(6);
        storage.addView(historyBar, bp);
        historyBar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        String[][] retention = {{"Detailed samples", "7 days"}, {"Minute summaries", "30 days"}, {"Daily summaries", "2 years"}};
        historyCounts = new TextView[retention.length];
        for (int i = 0; i < retention.length; i++) {
            if (i > 0) ui.divider(storage);
            LinearLayout line = row(); line.setPadding(0, ui.dp(8), 0, ui.dp(8));
            LinearLayout words = column();
            TextView name = ui.text(retention[i][0], 13.5f, false); name.setTextColor(ui.muted); words.addView(name);
            historyCounts[i] = ui.text("", 11.5f, false); historyCounts[i].setTextColor(ui.dim); words.addView(historyCounts[i]);
            line.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            TextView limit = ui.text("up to " + retention[i][1], 13.5f, true); line.addView(limit);
            storage.addView(line);
        }
        historyStatus = ui.caption(""); historyStatus.setPadding(0, ui.dp(6), 0, 0); storage.addView(historyStatus);
        TextView budget = ui.caption("A 50 MiB budget prunes older detail first, so retention can be shorter.");
        budget.setPadding(0, ui.dp(6), 0, 0); storage.addView(budget);

        LinearLayout clear = group();
        dangerRow(clear, "Clear this scooter's history", () -> clearHistory(false));
        dangerRow(clear, "Clear all connected history", () -> clearHistory(true));
        footnote("Ride recordings, charge recordings and owner setup are kept separately.");
    }
    private void refreshHistoryStorage() {
        if (historyUsage == null || service() == null || service().history == null || historyStoragePending) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (historyStorageChecked > 0 && now - historyStorageChecked < 10000) return;
        historyStorageChecked = now; historyStoragePending = true;
        service().history.storage(storage -> {
            historyStoragePending = false; if (closed) return;
            String used = String.format(Locale.US, "%.1f of %.0f MiB", storage.bytes / 1048576.0, storage.limitBytes / 1048576.0);
            historyUsage.setText(used);
            float share = storage.limitBytes <= 0 ? 0f : Math.max(0f, Math.min(1f, storage.bytes / (float) storage.limitBytes));
            // A visible sliver shows that something is stored even when it rounds to nothing.
            if (storage.bytes > 0) share = Math.max(share, 0.02f);
            ((LinearLayout.LayoutParams) historyBarFill.getLayoutParams()).weight = share;
            ((LinearLayout.LayoutParams) historyBarRest.getLayoutParams()).weight = 1f - share;
            historyBar.requestLayout();
            long[] rows = {storage.detailedRows, storage.minuteRows, storage.dailyRows};
            for (int i = 0; i < rows.length; i++) historyCounts[i].setText(String.format(Locale.US, "%,d saved", rows[i]));
            historyStatus.setText(storage.status == null ? "" : storage.status);
            historyStatus.setVisibility(storage.status == null || storage.status.isEmpty() ? View.GONE : View.VISIBLE);
            historyStorageCard.setContentDescription("Local storage, " + used + " used. "
                    + String.format(Locale.US, "%,d detailed, %,d minute and %,d daily samples saved.", rows[0], rows[1], rows[2]));
            dataIndexRow.value(mib(storage.bytes));
        });
    }
    private void clearHistory(boolean all) {
        ScooterProfiles.Profile profile = active();
        if (!all && profile == null) { host.toast("Choose a scooter first."); return; }
        final String profileId = all ? null : profile.id;
        String subject = all ? "all scooters" : profile.name;
        confirm("Clear history?", "This removes the saved timeline and last-seen readings for " + subject
                        + ". Ride recordings and owner setup stay. New history can build up again while connected.",
                "Clear", () -> {
                    if (service() == null || service().history == null) return;
                    service().clearHistory(profileId, () -> {
                        if (closed) return;
                        historyStorageChecked = 0; refreshHistoryStorage(); host.redraw();
                        host.toast("History cleanup finished. Check storage status.");
                    });
                });
    }

    private void textSize() {
        LinearLayout box = dialogBox("Text size", "Adjust all screens. Your phone's accessibility text size still applies.");
        TextView sample = ui.text("Easy to read, folded or open", 18, true); box.addView(sample);
        SeekBar seek = new SeekBar(host.activity()); seek.setMax(12);
        int initial = Math.round((settings().getFloat("text_scale", 1f) - 0.8f) / 0.05f);
        seek.setProgress(Math.max(0, Math.min(12, initial))); box.addView(seek);
        TextView percent = value(box, "");
        Runnable preview = () -> {
            float scale = 0.8f + seek.getProgress() * 0.05f;
            percent.setText(Math.round(scale * 100) + "%"); sample.setTextSize(18 * scale);
        };
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int value, boolean user) { preview.run(); }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        }); preview.run();
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setView(box).setNegativeButton("Cancel", null)
                .setPositiveButton("Apply", (d, which) -> {
                    settings().edit().putFloat("text_scale", 0.8f + seek.getProgress() * 0.05f).apply(); host.rebuild();
                }).create(); show(dialog);
    }
    private void telemetryRate() {
        final int[] intervals = {2000, 5000, 10000};
        int stored = settings().getInt("poll_interval_ms", 2000), selected = 0;
        for (int i = 0; i < intervals.length; i++) if (stored == intervals[i]) selected = i;
        LinearLayout box = dialogBox("Battery telemetry refresh",
                "Ride recording always uses the faster 2-second battery refresh.");
        SegmentedSelector selector = new SegmentedSelector(ui, "Telemetry refresh",
                new String[]{"2 seconds", "5 seconds", "10 seconds"}, choice -> {
                    settings().edit().putInt("poll_interval_ms", intervals[choice]).apply();
                    refresh();
                });
        selector.select(selected, false); box.addView(selector, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setView(box)
                .setPositiveButton("Done", null).create(); show(dialog);
    }

    // ---- PIN & proximity ----------------------------------------------------------------

    private void security() {
        label("Handlebar PIN");
        LinearLayout pin = group();
        pinRow = toggleRow(pin, "lock", "Fallback PIN", "Digits are never shown or exported",
                enabled -> { service().ble.enablePin(enabled); host.toast(service().ble.message()); refresh(); });
        readPinRow = row(pin, "history", "Read PIN state", "Shows whether a PIN is set, never its digits",
                () -> { service().ble.readPin(); refresh(); });
        setPinRow = row(pin, "pencil", "Set or change PIN", null, this::setPin);
        label("Proximity unlock");
        LinearLayout near = group();
        proximityRow = toggleRow(near, "bluetooth", "Proximity unlock", "Pair this phone first",
                enabled -> { service().ble.set("proximity_key", enabled ? 1 : 0); host.toast(service().ble.message()); refresh(); });
        bondRow = row(near, "bluetooth", "Pair this phone with scooter", null,
                () -> { service().ble.bond(); host.toast(service().ble.message()); refresh(); });
        securityNotice = footnote("");
        LinearLayout guide = group();
        row(guide, "info", "How to unlock with the handlebar PIN", "Phone-free fallback", this::pinGuide);
    }
    private boolean ownerReady() {
        ScooterProfiles.Profile p = active();
        return service() != null && service().ble.ready() && p != null && p.role == 0 && p.pid() == 2538;
    }
    /** One of the four PIN boxes; it only knows whether it is filled, never which digit. */
    private final class PinBox extends View {
        boolean filled, active;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        PinBox() { super(host.activity()); setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); }
        void set(boolean filled, boolean active) {
            if (filled == this.filled && active == this.active) return;
            this.filled = filled; this.active = active; invalidate();
        }
        @Override protected void onDraw(Canvas canvas) {
            float glow = ui.dp(3), radius = ui.dp(12);
            RectF box = new RectF(glow, glow, getWidth() - glow, getHeight() - glow);
            if (active) {
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(glow); paint.setColor(Ui.withAlpha(ui.accent, 70));
                RectF ring = new RectF(box); ring.inset(-glow / 2, -glow / 2);
                canvas.drawRoundRect(ring, radius + glow / 2, radius + glow / 2, paint);
            }
            paint.setStyle(Paint.Style.FILL); paint.setColor(ui.dark ? ui.bg : ui.raised);
            canvas.drawRoundRect(box, radius, radius, paint);
            float stroke = active ? ui.dp(1.5f) : ui.dp(1);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(stroke); paint.setColor(active ? ui.accent : ui.outline);
            RectF edge = new RectF(box); edge.inset(stroke / 2, stroke / 2);
            canvas.drawRoundRect(edge, radius, radius, paint);
            if (filled) {
                paint.setStyle(Paint.Style.FILL); paint.setColor(ui.text);
                canvas.drawCircle(box.centerX(), box.centerY(), ui.dp(6), paint);
            }
        }
    }
    private static boolean fourDigits(CharSequence value) {
        if (value.length() != 4) return false;
        for (int i = 0; i < 4; i++) { char c = value.charAt(i); if (c < '0' || c > '9') return false; }
        return true;
    }
    /** A short horizontal shake, skipped when the phone has animations turned off. */
    private void shake(View view) {
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        float d = ui.dp(10);
        ObjectAnimator animator = ObjectAnimator.ofFloat(view, View.TRANSLATION_X, 0, d, -d, d * .6f, -d * .6f, d * .3f, 0);
        animator.setDuration(320); animator.start();
    }
    private void setPin() {
        if (!ownerReady()) { host.toast("Connect as the scooter owner before changing its PIN."); return; }
        LinearLayout box = dialogBox("Set handlebar PIN", "Four digits. Keep it somewhere safe; it is your phone-free fallback. The scooter confirms the saved PIN before the app reports success.");
        LinearLayout header = row(); header.setPadding(0, ui.dp(8), 0, ui.dp(8));
        TextView stepLabel = ui.text("Enter a new PIN", 13.5f, true); stepLabel.setTypeface(ui.semibold());
        stepLabel.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        header.addView(stepLabel, new LinearLayout.LayoutParams(0, -2, 1));
        View[] dashes = new View[2];
        for (int i = 0; i < 2; i++) {
            dashes[i] = new View(host.activity()); dashes[i].setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(ui.dp(18), ui.dp(4)); dp.leftMargin = ui.dp(5);
            header.addView(dashes[i], dp);
        }
        box.addView(header);

        FrameLayout entry = new FrameLayout(host.activity());
        LinearLayout boxes = row();
        PinBox[] cells = new PinBox[4];
        for (int i = 0; i < 4; i++) {
            cells[i] = new PinBox();
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ui.dp(60), 1);
            cp.leftMargin = i == 0 ? 0 : ui.dp(2); cp.rightMargin = i == 3 ? 0 : ui.dp(2);
            boxes.addView(cells[i], cp);
        }
        entry.addView(boxes, new FrameLayout.LayoutParams(-1, ui.dp(60)));
        // The real field sits over the boxes: it takes taps and the keyboard, but draws nothing.
        EditText field = new EditText(host.activity());
        field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
        field.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setTextColor(Color.TRANSPARENT); field.setHintTextColor(Color.TRANSPARENT); field.setHighlightColor(Color.TRANSPARENT);
        field.setHint("Enter a new PIN, four digits"); field.setBackground(null); field.setPadding(0, 0, 0, 0);
        field.setCursorVisible(false); field.setLongClickable(false); field.setSaveEnabled(false);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        ActionMode.Callback noMenu = new ActionMode.Callback() {
            @Override public boolean onCreateActionMode(ActionMode mode, Menu menu) { return false; }
            @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) { return false; }
            @Override public boolean onActionItemClicked(ActionMode mode, MenuItem item) { return false; }
            @Override public void onDestroyActionMode(ActionMode mode) { }
        };
        field.setCustomSelectionActionModeCallback(noMenu); field.setCustomInsertionActionModeCallback(noMenu);
        entry.addView(field, new FrameLayout.LayoutParams(-1, ui.dp(60)));
        box.addView(entry, new LinearLayout.LayoutParams(-1, -2));
        TextView error = ui.caption(""); error.setTextColor(ui.danger); error.setPadding(0, ui.dp(8), 0, 0);
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE); box.addView(error);

        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setView(box).create();
        final char[][] first = {null};
        final int[] step = {1};
        final TextView[] buttons = dialogButtons(box, "Cancel", dialog::dismiss, "Next", null);
        TextView next = buttons[1];
        Runnable update = () -> {
            int filled = field.length(); boolean focused = field.hasFocus();
            for (int i = 0; i < 4; i++) cells[i].set(i < filled, focused && i == filled);
            next.setEnabled(filled == 4);
        };
        Runnable stepOne = () -> {
            if (first[0] != null) Arrays.fill(first[0], '\0');
            first[0] = null; step[0] = 1; field.setText("");
            stepLabel.setText("Enter a new PIN"); stepLabel.setContentDescription("Step 1 of 2. Enter a new PIN");
            field.setHint("Enter a new PIN, four digits");
            next.setText("Next"); next.setContentDescription("Next");
            dashes[0].setBackground(ui.shape(ui.accent, 2, 0)); dashes[1].setBackground(ui.shape(ui.raised, 2, 0));
            update.run();
        };
        next.setOnClickListener(v -> {
            Editable typed = field.getText();
            if (!fourDigits(typed)) { error.setText("Enter exactly four numeric digits."); return; }
            if (step[0] == 1) {
                first[0] = new char[4]; typed.getChars(0, 4, first[0], 0);
                step[0] = 2; field.setText(""); error.setText("");
                stepLabel.setText("Repeat PIN"); stepLabel.setContentDescription("Step 2 of 2. Repeat PIN");
                field.setHint("Repeat PIN, four digits");
                next.setText("Set PIN"); next.setContentDescription("Set PIN");
                dashes[1].setBackground(ui.shape(ui.accent, 2, 0));
                update.run(); return;
            }
            char[] repeated = new char[4]; typed.getChars(0, 4, repeated, 0);
            boolean same = Arrays.equals(first[0], repeated); Arrays.fill(repeated, '\0');
            if (!same) {
                shake(boxes); error.setText("PINs didn't match. Try again."); stepOne.run(); return;
            }
            if (!ownerReady()) { error.setText("Reconnect as the scooter owner first."); return; }
            if (service().ble.pending() != null) { error.setText("Wait for the current scooter change to finish."); return; }
            service().ble.setPin(new String(first[0]));
            if ("pin".equals(service().ble.pending())) {
                host.toast(service().ble.message()); dialog.dismiss();
            } else error.setText("PIN change did not start. " + service().ble.message());
            refresh();
        });
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) { if (s.length() > 0) error.setText(""); update.run(); }
        });
        field.setOnFocusChangeListener((v, focused) -> update.run());
        field.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_DONE && next.isEnabled()) { next.performClick(); return true; }
            return action == EditorInfo.IME_ACTION_DONE;
        });
        stepOne.run();
        if (dialog.getWindow() != null) dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        show(dialog, () -> {
            field.setText("");
            if (first[0] != null) Arrays.fill(first[0], '\0');
            first[0] = null;
        });
        field.requestFocus();
    }
    private void pinGuide() {
        LinearLayout box = dialogBox("Phone-free fallback", "Use the PIN you configured while the scooter is parked and electronically locked.");
        note(box, "1  Briefly press a turn-signal button to enter PIN unlock.\n\n"
                + "2  Adjust each digit using the handlebar buttons. Briefly squeeze a brake lever to confirm each digit.\n\n"
                + "3  After all four digits, press the power button to confirm and unlock.");
        note(box, "With proximity unlock configured, bring your paired phone close and squeeze a brake lever. Verify both fallback methods while parked before relying on them.");
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setView(box).setPositiveButton("Got it", null).create(); show(dialog);
    }

    // ---- Firmware lab -------------------------------------------------------------------

    private void lab() {
        LinearLayout warning = ui.card(); warning.setOrientation(LinearLayout.HORIZONTAL); warning.setGravity(Gravity.CENTER_VERTICAL);
        warning.setBackground(ui.shape(Ui.withAlpha(ui.orange, ui.dark ? 30 : 26), 20, Ui.withAlpha(ui.orange, 100)));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)); ip.rightMargin = ui.dp(14);
        warning.addView(ui.icon("lab", 24, ui.orange), ip);
        LinearLayout words = column();
        words.addView(ui.text("Unverified on UT5 MAX", 15, true));
        TextView body = ui.text("These commands come from NAVEE's app for other models. Inspect before applying; every change still needs a fresh vehicle readback.", 12.5f, false);
        body.setTextColor(ui.muted); body.setPadding(0, ui.dp(4), 0, 0); words.addView(body);
        warning.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        cardDestination.addView(warning);
        labSummary = footnote("Connect to inspect available fields");
        label("Experimental");
        LinearLayout list = group();
        for (ScooterCommands.Feature f : ScooterCommands.catalog()) {
            if (!f.experimental) continue;
            if (list.getChildCount() > 0) ui.divider(list);
            LinearLayout line = row(); line.setMinimumHeight(ui.dp(58)); line.setPadding(ui.dp(2), ui.dp(9), 0, ui.dp(9));
            LinearLayout text = column();
            text.addView(ui.text(f.label, 15, false));
            TextView description = ui.text(f.description, 11.5f, false); description.setTextColor(ui.muted);
            description.setPadding(0, ui.dp(3), 0, 0); text.addView(description);
            TextView state = ui.text("", 11.5f, false); state.setTextColor(ui.dim); state.setPadding(0, ui.dp(2), 0, 0);
            text.addView(state);
            line.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
            TextView inspect = inspectPill(f);
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-2, -2); pp.leftMargin = ui.dp(10);
            line.addView(inspect, pp);
            list.addView(line, new LinearLayout.LayoutParams(-1, -2));
            labRows.add(new LabRow(f, state, inspect));
        }
        TextView evidence = ui.text("View command evidence", 13.5f, false); evidence.setTypeface(ui.medium());
        evidence.setTextColor(ui.accent); evidence.setGravity(Gravity.CENTER); evidence.setMinHeight(ui.dp(48));
        clickable(evidence, this::commandEvidence);
        cardDestination.addView(evidence, new LinearLayout.LayoutParams(-1, -2));
        footnote("An exposed status field is a candidate for testing, not proof the feature works. Unreported fields stay unavailable. Nothing here writes arbitrary commands or changes firmware.");
    }
    /** Small red "Inspect" pill; grey while its field is unavailable. The touch target stays 48dp tall. */
    private TextView inspectPill(ScooterCommands.Feature feature) {
        TextView pill = ui.text("Inspect", 12.5f, true);
        pill.setGravity(Gravity.CENTER); pill.setMinHeight(ui.dp(48)); pill.setMinWidth(ui.dp(48));
        pill.setPadding(ui.dp(14), 0, ui.dp(14), 0);
        pill.setTextColor(new ColorStateList(new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
                new int[] {ui.dim, ui.onAccent}));
        StateListDrawable fill = new StateListDrawable();
        fill.addState(new int[] {-android.R.attr.state_enabled}, ui.shape(ui.raised, 100, 0));
        fill.addState(new int[] {}, ui.shape(ui.accent, 100, 0));
        pill.setBackground(new InsetDrawable(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(Color.WHITE, 45)), fill, null),
                0, ui.dp(8), 0, ui.dp(8)));
        pill.setContentDescription("Inspect " + feature.label.toLowerCase(Locale.ROOT));
        pill.setFocusable(true); pill.setEnabled(false);
        pill.setOnClickListener(v -> experimental(feature));
        return pill;
    }
    void inspectQuickFeature(ScooterCommands.Feature feature) {
        if (!closed && feature != null && feature.experimental) experimental(feature);
    }
    private void experimental(ScooterCommands.Feature feature) {
        BleController ble = service().ble; Integer current = ble.featureValue(feature.key);
        if (!ble.ready() || current == null) { host.toast("This firmware has not supplied a fresh status field for that control."); return; }
        if (!feature.allows(current)) {
            host.toast("The current firmware value is outside the explained range, so a reversible change cannot be confirmed."); return;
        }
        LinearLayout box = dialogBox(feature.label, feature.description);
        note(box, "Experimental on UT5 MAX. This command comes from another OEM model's controls. Apply while parked; the app waits for an accepted command and matching fresh state.");
        note(box, "Current field: " + DisplayUnits.featureValue(feature, current, settings().getString("units", "mi")) + "\nSource command: "
                + String.format(Locale.ROOT, "0x%02X", feature.cmd) + " · status byte " + feature.payloadOffset + " of 0x70");
        final int[] selected = {current};
        if (feature.type == ScooterCommands.Type.SLIDER) {
            TextView amount = value(box, DisplayUnits.featureValue(feature, selected[0], settings().getString("units", "mi")));
            List<Integer> valid = new ArrayList<>();
            for (int n = feature.min; n <= feature.max; n += feature.step) if (feature.allows(n)) valid.add(n);
            SeekBar slider = new SeekBar(host.activity()); slider.setMax(Math.max(0, valid.size() - 1));
            int currentIndex = valid.indexOf(current); slider.setProgress(Math.max(0, currentIndex)); box.addView(slider);
            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int n, boolean user) {
                    selected[0] = valid.get(n); amount.setText(DisplayUnits.featureValue(feature, selected[0], settings().getString("units", "mi")));
                }
                @Override public void onStartTrackingTouch(SeekBar s) { }
                @Override public void onStopTrackingTouch(SeekBar s) { }
            });
            int minimumCap = feature.key.equals("speed_limit") ? 8 : 5;
            note(box, "Off disables the cap; otherwise choose "
                    + DisplayUnits.speedRange(minimumCap, feature.max, settings().getString("units", "mi")) + ".");
        } else {
            LinearLayout options = column(); box.addView(options);
            TextView selectedText = value(box, "Selected: " + DisplayUnits.featureValue(feature, selected[0], settings().getString("units", "mi")));
            for (int i = 0; i < feature.optionValues.length; i++) {
                final int choice = feature.optionValues[i];
                button(options, DisplayUnits.featureValue(feature, choice, settings().getString("units", "mi")), () -> { selected[0] = choice; selectedText.setText("Selected: " + DisplayUnits.featureValue(feature, choice, settings().getString("units", "mi"))); }, false);
            }
        }
        TextView error = ui.caption(""); error.setTextColor(ui.danger); box.addView(error);
        ScrollView inner = new ScrollView(host.activity()); inner.addView(box);
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setView(inner).setNegativeButton("Cancel", null)
                .setPositiveButton("Apply experimental setting", null).create(); show(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!feature.allows(selected[0])) { error.setText("Choose a value inside the explained OEM range."); return; }
            Integer latest = service().ble.featureValue(feature.key);
            if (!service().ble.ready() || latest == null || !feature.allows(latest) || !latest.equals(current)) {
                error.setText("The scooter state changed or became unavailable. Close this panel and inspect its fresh state again."); return;
            }
            if (service().ble.set(feature.key, selected[0])) { host.toast(service().ble.message()); dialog.dismiss(); }
            else error.setText(service().ble.message()); refresh();
        });
    }
    private void commandEvidence() {
        LinearLayout box = dialogBox("Command evidence", "Only command names, acceptance, and readback results are shown. Authentication and PIN bytes are excluded.");
        Map<String,String> results = service().ble.commandResults();
        if (results.isEmpty()) note(box, "No saved setting-change evidence for this scooter yet.");
        for (Map.Entry<String,String> e : results.entrySet()) {
            if (e.getKey().equals("clock_sync")) { note(box, "Local clock sync\n" + e.getValue()); continue; }
            ScooterCommands.Feature feature;
            try { feature = ScooterCommands.find(e.getKey()); } catch (IllegalArgumentException ignored) { continue; }
            note(box, feature.label + "\n" + e.getValue());
        }
        List<String> events = service().ble.events();
        TextView heading = ui.text("Connection activity", 16, true); heading.setPadding(0, ui.dp(14), 0, ui.dp(4)); box.addView(heading);
        StringBuilder lines = new StringBuilder();
        for (int i = Math.max(0, events.size() - 35); i < events.size(); i++) lines.append(events.get(i)).append('\n');
        TextView activity = ui.caption(lines.length() == 0 ? "No connection activity yet." : lines.toString());
        activity.setTextIsSelectable(true); box.addView(activity);
        ScrollView inner = new ScrollView(host.activity()); inner.addView(box);
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setView(inner).setPositiveButton("Close", null).create(); show(dialog);
    }

    // ---- About & permissions ------------------------------------------------------------

    private void privacy() {
        LinearLayout c = ui.card();
        TextView version = ui.text("Navee Offline " + AppVersion.name(host.activity()), 17, true);
        version.setTag("about-version");
        version.setMinHeight(ui.dp(48)); version.setGravity(Gravity.CENTER_VERTICAL);
        clickable(version, this::tapDeveloperVersion);
        c.addView(version);
        TextView about = ui.caption("Controls and ride recording connect directly over Bluetooth. No account or server login. Independent project, not affiliated with NAVEE.");
        about.setPadding(0, ui.dp(6), 0, 0); c.addView(about);
        cardDestination.addView(c);
        label("Permissions");
        LinearLayout permissions = group();
        nearbyRow = row(permissions, "bluetooth", "Nearby devices", "Connects to your scooter", host::requestBluetooth);
        locationRow = row(permissions, "route", "Location", "Optional, for ride routes", null);
        notificationRow = row(permissions, "sound", "Notifications", "Recording notification with Stop", ()->openAndroidSettings(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS));
        if (Build.VERSION.SDK_INT < 31) footnote("Older Android versions require location permission for Bluetooth discovery.");
        label("Your data");
        LinearLayout data = ui.card(); cardDestination.addView(data);
        note(data, "Owner setup, scooter profiles, preferences, and ride recordings stay on this phone. You choose when to export them. Ordinary diagnostics leave out owner identity and PIN digits; a private owner backup intentionally includes its setup identity.");
        note(data, "Nearby devices connects to your scooter. Optional location records your route during a ride. Phone motion and pressure sensors run only while recording. The recording notification includes a Stop action.");
        note(data, "Route maps load tiles from the map source chosen in Rides (USGS Topo by default) only when you turn on Basemap. GPS and the recorded route still work without a map connection. Loading tiles sends the map area and your network address to that provider.");
        LinearLayout more = group();
        row(more, "stats", "Connection activity", null, this::commandEvidence);
        row(more, "copy", "Licenses & attribution", null, this::licenses);
        row(more, "bluetooth", "Connect selected scooter", null, host::connect);
    }

    /** Deliberate local opt-in, matching Android's familiar seven-tap developer unlock. */
    private void tapDeveloperVersion() {
        if (closed) return;
        if (developerCountdown != null) { developerCountdown.cancel(); developerCountdown = null; }
        if (developerEnabled()) { host.toast("Developer options are already enabled."); return; }
        long now = SystemClock.uptimeMillis();
        if (now - lastDeveloperTap > 3000) developerTaps = 0;
        lastDeveloperTap = now;
        int remaining = 7 - ++developerTaps;
        if (remaining <= 0) {
            developerTaps = 0;
            settings().edit().putBoolean("developer_options", true).apply();
            host.toast("Developer options enabled.");
            host.rebuild();
        } else if (remaining <= 3) {
            developerCountdown = android.widget.Toast.makeText(host.activity(),
                    remaining + (remaining == 1 ? " more tap" : " more taps") + " to enable developer options.",
                    android.widget.Toast.LENGTH_SHORT);
            developerCountdown.show();
        }
    }

    private void developerOptions() {
        LinearLayout controls = group();
        simulationRow = toggleRow(controls, "scooter", "Simulate scooter as connected", null, enabled -> {
            ScooterService s = service();
            if (closed || s == null || !developerEnabled()) return;
            s.setSimulation(enabled);
            // The service may refuse while a ride or phone recording is active. Always show
            // its actual state, never the tentative state of a performed switch click.
            refreshDeveloper(); host.redraw();
        });
        simulationRow.view.setTag("developer-simulation-row");
        simulationRow.toggle.setTag("developer-simulation-toggle");
        simulationStatus = footnote("Simulation active · sample data");
        simulationStatus.setTag("developer-simulation-status");
        simulationStatus.setTextColor(ui.orange);
        simulationStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        footnote("Sample data only. Bluetooth and recording are paused. Simulation turns off when the app process restarts.");
        label("Charge recording");
        ScooterService current=service();
        chosenCharger=current!=null&&current.chargeCharger()!=null?current.chargeCharger():ChargeSessionStore.Charger.STOCK;
        chargeChargerSelector=new SegmentedSelector(ui,"Attached charger",new String[]{"Stock","High-speed"},choice->{
            ScooterService s=service();if(s==null||s.isChargeRecording())return;
            chosenCharger=choice==0?ChargeSessionStore.Charger.STOCK:ChargeSessionStore.Charger.HIGH_SPEED;
            s.setChargeCharger(chosenCharger);refreshDeveloper();
        });
        chargeChargerSelector.setTag("charge-charger-selector");chargeChargerSelector.select(chosenCharger==ChargeSessionStore.Charger.STOCK?0:1,false);
        preferenceSelectors.add(chargeChargerSelector);
        LinearLayout.LayoutParams chargerParams=new LinearLayout.LayoutParams(-1,-2);chargerParams.bottomMargin=ui.dp(12);
        cardDestination.addView(chargeChargerSelector,chargerParams);
        LinearLayout capture=group();
        chargeCaptureRow=row(capture,"battery","Record this charge",null,()->{
            ScooterService s=service();if(s==null)return;
            if(s.isChargeRecording()){s.stopChargeRecording();refreshDeveloper();return;}
            String unavailable=s.chargeCaptureUnavailable();if(unavailable!=null){host.toast(unavailable);return;}
            String charger=chosenCharger==ChargeSessionStore.Charger.STOCK?"stock":"high-speed";
            AlertDialog dialog=new AlertDialog.Builder(host.activity()).setTitle("Record this charge?")
                    .setMessage("Use the "+charger+" charger currently attached. The app saves battery percentage, voltage, current, power and temperature locally every 10 seconds. It keeps Bluetooth awake in the background with a Stop notification.\n\nCapture stops at 100%, when charging ends, on disconnect, or when the app process ends. Up to 20 recordings are kept, at most about 20 MB. Nothing is uploaded. The selected charger is not detected automatically.")
                    .setNegativeButton("Cancel",null).setPositiveButton("Start recording",(d,which)->{
                        if(!s.startChargeRecording(chosenCharger))host.toast(s.chargeCaptureStatus());refreshDeveloper();
                    }).create();show(dialog);
        });chargeCaptureRow.view.setTag("charge-capture-action");
        chargeCaptureStatus=footnote("");chargeCaptureStatus.setTag("charge-capture-status");
        chargeLearningStatus=footnote("");chargeLearningStatus.setTag("charge-learning-status");
        footnote("Choose the charger currently attached. Estimates need two usable recordings covering the remaining percentage range and compatible live power at the current battery level. Each needs at least 10 complete percentage intervals. Missing or incompatible data keeps the current-rate estimate; the app does not identify chargers.");
        LinearLayout chargeData=group();
        exportChargeRow=row(chargeData,"download","Export charge recordings", "JSON · this scooter",()->{
            ScooterService s=service();if(s==null)return;
            if(!s.exportChargeRecordings(file->{
                if(closed||host.activity().isDestroyed())return;
                if(file==null){host.toast(s.chargeCaptureStatus());return;}
                host.saveFile("charge-recordings.json","application/json",file);
            }))host.toast("Stop charge recording before exporting, or wait for the current export");
        });exportChargeRow.view.setTag("charge-export-action");
        clearChargeRow=row(chargeData,"trash","Clear this scooter's charge recordings",null,()->{
            ScooterService s=service();if(s==null||s.isChargeRecording())return;
            AlertDialog dialog=new AlertDialog.Builder(host.activity()).setTitle("Clear charge recordings?")
                    .setMessage("Remove this scooter's local charge samples and learned charge curves. Ride recordings and other scooters are kept.")
                    .setNegativeButton("Cancel",null).setPositiveButton("Clear recordings",(d,which)->s.clearChargeRecordings(this::refreshDeveloper)).create();show(dialog);
        });clearChargeRow.view.setTag("charge-clear-action");
        LinearLayout visibility = group();
        Row hide = row(visibility, "close", "Hide developer options", null, () -> {
            if (closed) return;
            ScooterService s = service();
            if(s!=null&&s.isChargeRecording()){host.toast("Stop charge recording before hiding developer options");return;}
            if (s != null && !s.setSimulation(false)) { refreshDeveloper(); return; }
            selectedSection = "";
            settings().edit().putBoolean("developer_options", false).putString("more_section", "").apply();
            host.rebuild();
        });
        hide.view.setTag("developer-hide-options");
    }

    private void refreshDeveloper() {
        ScooterService s = service();
        boolean enabled = developerEnabled();
        boolean simulating = s != null && s.simulating();
        if (developerIndexRow != null) {
            developerIndexRow.view.setVisibility(enabled ? View.VISIBLE : View.GONE);
            developerIndexRow.value(simulating ? "Simulating" : null);
            developerIndexRow.value.setTextColor(ui.orange);
        }
        if (simulationRow != null) {
            refreshSwitch(simulationRow, simulating, enabled && s != null);
            simulationStatus.setVisibility(simulating ? View.VISIBLE : View.GONE);
        }
        if(chargeCaptureRow!=null){
            boolean capturing=s!=null&&s.isChargeRecording();
            String unavailable=s==null?"Connect your scooter first":s.chargeCaptureUnavailable();
            chargeCaptureRow.title.setText(capturing?"Stop charge recording":"Record this charge");
            chargeCaptureRow.enabled(enabled&&s!=null&&(capturing||unavailable==null));
            chargeCaptureStatus.setText(capturing||unavailable==null?s.chargeCaptureStatus():unavailable);
            chargeLearningStatus.setText(s==null?"":s.chargeLearningSummary());
            clearChargeRow.enabled(enabled&&s!=null&&!capturing&&!s.chargeExporting());
            exportChargeRow.enabled(enabled&&s!=null&&!capturing&&!s.chargeExporting());
            chargeChargerSelector.setAlpha(capturing?.5f:1f);
            setChildrenEnabled(chargeChargerSelector,!capturing);
        }
        // A stale saved shortcut must not reveal the section after developer options are hidden.
        if (!enabled && "Developer".equals(selectedSection)) selectSection("");
    }

    private void setChildrenEnabled(View view,boolean enabled){
        view.setEnabled(enabled);if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)setChildrenEnabled(((ViewGroup)view).getChildAt(i),enabled);
    }

    private void licenses() {
        LinearLayout box = dialogBox("Licenses & attribution", "Navee Offline is an independent project, not affiliated with NAVEE or its app vendor.");
        note(box, "Saira typeface\nCopyright 2020 The Saira Project Authors. SIL Open Font License 1.1; not covered by the app's MIT licence.");
        note(box, "Shizuku API 13.1.5\nCopyright (c) 2021 RikkaW. MIT License. Optional phone-only owner setup.");
        note(box, "AndroidX annotations 1.3.0\nCopyright The Android Open Source Project. Apache License 2.0.");
        note(box, "Protocol research reference\npepperonas/navee-st3-pro, MIT License.");
        note(box, "UT5 MAX photo\nSupplied by the project user; its author and licence were not supplied. The app's licence grants no rights to the photo or NAVEE marks.");
        note(box, "Maps\nMap data and tiles belong to the selected provider. USGS Topo: U.S. Geological Survey, National Geospatial Program. OpenStreetMap: © OpenStreetMap contributors.");
        note(box, "Full notices ship with the app source in NOTICE and licenses/. Dependency and font licenses are also bundled in the APK assets.");
        ScrollView inner = new ScrollView(host.activity()); inner.addView(box);
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setView(inner).setPositiveButton("Close", null).create(); show(dialog);
    }
    private boolean granted(String permission) {
        return host.activity().checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }
    private void permissionValue(Row row, String text, boolean good) {
        row.value(text); row.value.setTextColor(good ? ui.ok : ui.muted);
        row.value.setTypeface(good ? ui.semibold() : ui.regular());
    }

    private void setupGuide() {
        LinearLayout box = dialogBox("One-time owner setup", "Use your own NAVEE account and your own already activated scooter. The saved identity is Bluetooth ownership data, not your password or a cloud login token.");
        note(box, "1  On a computer, install Android SDK Platform-Tools (ADB) and Python 3. The app source bundle includes setup/capture-owner.py.\n\n"
                + "2  On your phone, enable Developer options by tapping Build number seven times in About phone. In Developer options, enable USB debugging. Connect a data-capable USB cable and approve the computer's debugging prompt.\n\n"
                + "3  Sign into the official NAVEE app beforehand. Keep the scooter safely parked and powered on; close other scooter apps.\n\n"
                + "4  From the extracted app source folder, run:");
        TextView command = ui.text("python3 setup/capture-owner.py --push", 14, true);
        command.setTextColor(ui.accent); command.setTextIsSelectable(true); command.setPadding(ui.dp(12), ui.dp(14), ui.dp(12), ui.dp(14));
        command.setBackground(ui.shape(ui.raised, 12, ui.outline)); box.addView(command);
        note(box, "The helper opens NAVEE. Connect to your scooter there. It waits up to 90 seconds, captures only a valid fresh owner-authentication packet, closes NAVEE and restores its temporary log setting, then saves a private JSON backup. --push copies the JSON to phone Downloads.\n\n"
                + "If several phones are attached, add -s PHONE_SERIAL using the serial shown by adb devices. You can pass --output ollie-owner.json to choose a desktop filename.\n\n"
                + "5  Back here, tap Import owner setup file and choose navee-owner-setup.json from Downloads. Allow Nearby devices and connect. Keep the desktop backup private.\n\n"
                + "Everyday controls then work directly over Bluetooth. You only need another capture after changing ownership or when the scooter rejects its stored identity.");
        ScrollView inner = new ScrollView(host.activity()); inner.addView(box);
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setView(inner).setNegativeButton("Close", null)
                .setPositiveButton("Import setup file", (d, which) -> { if (canEditProfiles()) host.importOwner(); }).create(); show(dialog);
    }

    // ---- Refresh ------------------------------------------------------------------------

    private void refreshProfiles(ScooterService s, ScooterProfiles.Profile p) {
        boolean recognised = p != null && p.pid() == 2538;
        boolean photo = recognised && scooterPhoto != null;
        indexPhoto.setVisibility(photo ? View.VISIBLE : View.GONE);
        indexPhotoFallback.setVisibility(photo ? View.GONE : View.VISIBLE);
        scootersPhoto.setVisibility(photo ? View.VISIBLE : View.GONE);
        scootersPhotoFallback.setVisibility(photo ? View.GONE : View.VISIBLE);
        String role = p == null ? "" : p.role == 0 ? "Owner" : "Shared";
        BleController.State state = s.ble.state();
        String link = state == BleController.State.READY ? "connected"
                : state == BleController.State.DISCONNECTED ? "disconnected" : "connecting";
        String name = p == null ? "No scooter yet" : p.name;
        String status = p == null ? "Add an owner profile to begin" : role + " · " + link;
        indexProfileName.setText(name); indexProfileState.setText(status);
        indexProfileCard.setContentDescription(name + ", " + status + ". Open My scooters");
        int count = s.profiles.all().size();
        scootersIndexRow.value(count == 0 ? "" : String.valueOf(count));
        scootersName.setText(name);
        scootersRole.setText(p == null ? "Add an owner profile to begin"
                : "Active · " + (p.role == 0 ? "owner" : "shared") + " profile");
        scootersModel.setText(p == null ? "" : p.serial.isEmpty() ? "Connect to verify model" : "Model " + p.pid());
        scootersModel.setVisibility(p == null ? View.GONE : View.VISIBLE);
        scootersProfileCard.setContentDescription(name + ", " + scootersRole.getText()
                + (p == null ? "" : ", " + scootersModel.getText()));
        chooseProfileRow.value(count == 0 ? "" : count + (count == 1 ? " profile" : " profiles"));
        if (setupState != null) setupState.setText(p != null && p.configured() ? "Owner profile ready · connect next" : "Add an owner profile to begin");
        connectionSummary.setText(s.isRecording() ? "Ride recording is active. Stop it before changing profiles."
                : state == BleController.State.DISCONNECTED ? "Profiles can be changed while disconnected."
                : "Disconnect before selecting, adding, or editing owner profiles.");
    }
    private void refreshPermissions(ScooterService s) {
        boolean nearby = s.ble.permissions();
        permissionValue(nearbyRow, nearby ? "Allowed" : "Not allowed", nearby);
        boolean fine = granted(Manifest.permission.ACCESS_FINE_LOCATION), coarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION);
        permissionValue(locationRow, fine ? "Allowed" : coarse ? "Approximate" : "Asked in rides", fine || coarse);
        NotificationManager notifications = host.activity().getSystemService(NotificationManager.class);
        boolean notify = notifications != null && notifications.areNotificationsEnabled();
        permissionValue(notificationRow, notify ? "Allowed" : "Off", notify);
    }
    private CharSequence pinStatus(ScooterCommands.PinState pin) {
        String privacy = "digits are never shown or exported";
        if (pin == null) return "Not read yet · " + privacy;
        if (pin.status != 0) return "PIN state was rejected by the scooter";
        if (!pin.configured) return "No fallback PIN configured";
        if (!Boolean.TRUE.equals(pin.enabled)) return "Disabled · " + privacy;
        SpannableString enabled = new SpannableString("Enabled · " + privacy);
        enabled.setSpan(new ForegroundColorSpan(ui.ok), 0, "Enabled".length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return enabled;
    }

    @Override public void refresh() {
        ScooterService s = service(); if (s == null) return;
        refreshDeveloper();
        if (shownSection().equals("Data")) refreshHistoryStorage();
        for (Runnable refresh : valueRefreshers) refresh.run();
        ScooterProfiles.Profile p = active();
        boolean configured = p != null && p.configured();
        onboardingCard.setVisibility(configured ? View.GONE : View.VISIBLE);
        boolean profilesEditable = !s.isRecording() && s.ble.state() == BleController.State.DISCONNECTED && s.ble.pending() == null;
        for (Row action : profileEditActions) action.enabled(profilesEditable);
        renameProfileRow.enabled(profilesEditable && p != null);
        forgetProfileRow.enabled(profilesEditable && p != null);
        backupProfileRow.enabled(configured);
        disconnectProfileRow.enabled(!s.isRecording() && s.ble.state() != BleController.State.DISCONNECTED);
        refreshProfiles(s, p);
        refreshPermissions(s);
        ScooterCommands.PinState pin = s.ble.pinState(); boolean owner = ownerReady(), busy = s.ble.pending() != null;
        pinRow.sub(pinStatus(pin));
        readPinRow.enabled(owner && !busy); setPinRow.enabled(owner && !busy);
        refreshSwitch(pinRow, pin == null || pin.status != 0 ? null : pin.enabled,
                owner && !busy && pin != null && pin.status == 0 && pin.configured);
        Integer proximity = s.ble.featureValue("proximity_key"); boolean paired = s.ble.bonded();
        boolean enablePrerequisites = paired && pin != null && pin.status == 0 && pin.configured && Boolean.TRUE.equals(pin.enabled);
        refreshSwitch(proximityRow, proximity == null ? null : proximity == 1,
                owner && !busy && proximity != null && (proximity == 1 || enablePrerequisites));
        proximityRow.sub(paired ? "This phone is paired with the connected scooter"
                : "Needs a Bluetooth pairing and an enabled fallback PIN");
        bondRow.enabled(owner && !busy && !paired && pin != null && pin.status == 0 && pin.configured && Boolean.TRUE.equals(pin.enabled));
        permissionValue(bondRow, paired ? "Paired" : "", paired);
        securityNotice.setText(busy ? "Applying a setting · waiting for scooter confirmation"
                : !owner ? "Connect as the UT5 MAX owner to change security settings."
                : !enablePrerequisites ? "Read PIN state, enable the fallback PIN, then pair this phone to use proximity unlock."
                : "Proximity unlock is ready to configure. Test it while parked.");
        int available = 0;
        Map<String,String> results = s.ble.commandResults();
        for (LabRow row : labRows) {
            Integer current = s.ble.featureValue(row.feature.key);
            String result = results.get(row.feature.key);
            if (current != null) available++;
            boolean knownBaseline = current != null && row.feature.allows(current);
            row.value.setText(current == null ? "Status field not supplied by this firmware"
                    : !knownBaseline ? "Current firmware value " + current + " is outside the explained range · unavailable"
                    : "Candidate state: " + DisplayUnits.featureValue(row.feature, current, settings().getString("units", "mi"))
                        + (result == null ? " · behavior unverified" : " · " + result));
            row.action.setEnabled(s.ble.ready() && p != null && p.pid() == 2538 && !busy && knownBaseline);
        }
        labSummary.setText(!s.ble.ready() ? "Connect to inspect fresh firmware fields"
                : available + " of " + labRows.size() + " candidate status fields supplied");
    }
}
