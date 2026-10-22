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
    /** More › Developer options › Compatibility; legacy unfinished tests retain a recovery-only route. */
    private CompatibilityPanel compatibilityPanel;
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
    private Row renameProfileRow, forgetProfileRow, disconnectProfileRow, chooseProfileRow;
    private Row scootersIndexRow, dataIndexRow;
    private Row setPinRow, bondRow, pinRow, proximityRow;
    private View pinRefresh, pinSpinner, proximitySpinner;
    private TextView proximityPinChip, proximityPairedChip, securityConnect;
    private Row onboardingImport, onboardingNearby, onboardingConnect;
    private final List<Row> lockedProfileRows = new ArrayList<>();
    private TextView profileUnlock;
    private Row nearbyRow, locationRow, notificationRow;
    private Row developerIndexRow, simulationRow, simulationModelRow;
    /** Developer simulator models: "" is the UT5 MAX simulator; the others are synthetic advertised IDs. */
    private static final String[] SIMULATION_MODELS = {"", "24431", "39991"};
    private static final String[] SIMULATION_MODEL_NAMES = {"UT5 MAX", "Mapped model (synthetic 24431)", "Unknown model (synthetic 39991)"};
    private TextView simulationStatus, chargeCaptureStatus, chargeCurveValue, chargeCurveScope;
    private LinearLayout chargeCurve;
    private Row chargeCaptureRow, clearChargeRow, exportChargeRow, chargeHistoryRow;
    private ChargeHistoryDialog chargeHistoryDialog;
    private SegmentedSelector chargeChargerSelector;
    private android.widget.Toast developerCountdown;
    private int developerTaps;
    private long lastDeveloperTap;
    private TextView securityNotice;
    private LinearLayout indexProfileCard, scootersProfileCard;
    private ImageView indexPhoto, scootersPhoto;
    private View indexPhotoFallback, scootersPhotoFallback;
    private TextView indexProfileName, indexProfileState, scootersName, scootersRole, scootersModel;
    private TextView labCount, labConnect, historyUsage, historyStatus;
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
        row(sectionMenu, "download", "Back up & restore", null,
                () -> host.dataBackup(null, null)).view.setTag("data-backup");
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
        TextView footer = ui.text("Volt " + AppVersion.name(host.activity()) + " · not affiliated with NAVEE", 11.5f, false);
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
        if (knownSection("Compatibility")) {
            cardDestination = column(); sections.put("Compatibility", cardDestination);
            sectionParent.addView(cardDestination, new LinearLayout.LayoutParams(-1, -2));
            compatibilityPanel = new CompatibilityPanel(host, cardDestination, !developerEnabled());
        }
        cardDestination = content;
        showSelectedSection();
        refresh();
        restoreWideScroll();
    }
    @Override public View view() { return scroll; }
    @Override public void close() {
        saveWideScroll();
        closed = true;
        if (chargeHistoryDialog != null) chargeHistoryDialog.close();
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
        if ("Compatibility".equals(selectedSection) && !standalone) {
            selectSection(developerEnabled() ? "Developer" : ""); return true;
        }
        if (standalone || wide || selectedSection.isEmpty()) return false;
        selectSection(""); return true;
    }

    private boolean knownSection(String section) {
        if ("Units".equals(section)) return true;
        if ("Compatibility".equals(section)) return developerEnabled() || testRecoveryNeeded();
        if ("Developer".equals(section) && !developerEnabled()) return false;
        for (String candidate : SECTIONS) if (candidate.equals(section)) return true;
        return false;
    }
    private boolean developerEnabled() { return settings().getBoolean("developer_options", false); }
    private boolean testRecoveryNeeded() {
        BleController ble = service() == null ? null : service().ble;
        return ble != null && (!ble.testRecordsHealthy() || !ble.unresolvedTests().isEmpty()
                || ble.unresolvedTestsElsewhere() > 0 || !ble.orphanedTests().isEmpty() || ble.testing());
    }
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
        boolean compatibility = "Compatibility".equals(shown);
        sectionHeader.setVisibility(index || (standalone && !units) ? View.GONE : View.VISIBLE);
        sectionBack.setVisibility(units || compatibility || !wide ? View.VISIBLE : View.GONE);
        String parent = units ? "App preferences" : compatibility && developerEnabled() ? "Developer options" : "More";
        sectionBackLabel.setText(parent);
        sectionBack.setContentDescription("Back to " + parent);
        if (units) sectionTitle.setText("Units");
        if (compatibility) sectionTitle.setText(developerEnabled() ? "Compatibility" : "Unfinished test");
        for (int i = 0; i < SECTIONS.length; i++) if (SECTIONS[i].equals(shown)) sectionTitle.setText(SECTION_TITLES[i]);
        for (Map.Entry<String,LinearLayout> section : sections.entrySet())
            section.getValue().setVisibility(section.getKey().equals(shown) ? View.VISIBLE : View.GONE);
        if (index || wide) dataIndexRow.value(historyFileSize());
        if (wide) for (int i = 0; i < SECTIONS.length; i++)
            if (menuRows[i] != null) markMenuRow(menuRows[i], SECTIONS[i].equals(units ? "App" : compatibility ? "Developer" : shown));
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
        if ("Units".equals(to) || "Compatibility".equals(to)) return 1;
        if ("Units".equals(from) || "Compatibility".equals(from)) return -1;
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
            if (isAttachedToWindow() && window != null && window.getWidth() > 0)
                // Layout position, not on-screen position: a tab transition slides this page in,
                // and measuring mid-slide used to leave the menu narrow until the next layout.
                want = window.getWidth() / 2 - gap / 2 - Ui.layoutLeftInWindow(this);
            return Math.max(min, Math.min(max, want));
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int size = MeasureSpec.getSize(widthSpec), gap = ui.dp(20);
            split = split(size);
            ((LinearLayout.LayoutParams) first.getLayoutParams()).width = split;
            ((LinearLayout.LayoutParams) second.getLayoutParams()).width = Math.max(0, size - split - gap);
            super.onMeasure(widthSpec, heightSpec);
        }
        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            // The window position is final only now. Re-split before this frame draws rather than
            // posting another layout, so the first frame already shows the resting widths.
            int width = r - l, wanted = split(width);
            if (wanted != split) {
                split = wanted;
                int gap = ui.dp(20), height = MeasureSpec.makeMeasureSpec(b - t, MeasureSpec.EXACTLY);
                ((LinearLayout.LayoutParams) first.getLayoutParams()).width = split;
                ((LinearLayout.LayoutParams) second.getLayoutParams()).width = Math.max(0, width - split - gap);
                first.measure(MeasureSpec.makeMeasureSpec(split, MeasureSpec.EXACTLY), height);
                second.measure(MeasureSpec.makeMeasureSpec(Math.max(0, width - split - gap), MeasureSpec.EXACTLY), height);
            }
            super.onLayout(changed, l, t, r, b);
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
        link.setOnClickListener(v -> selectSection("Units".equals(selectedSection) ? "App"
                : "Compatibility".equals(selectedSection) && developerEnabled() ? "Developer" : ""));
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

    /**
     * Boxed row glyph: red when actionable, danger-red for destructive rows, grey with its disabled row.
     * A step row shows its number instead: green once done, red for the next step, grey after that.
     */
    private final class RowIcon extends View {
        final String key; boolean danger;
        String step; int stepState;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        RowIcon(String key) {
            super(host.activity()); this.key = key;
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        void step(int state) { if (state != stepState) { stepState = state; invalidate(); } }
        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            paint.setStyle(Paint.Style.FILL); paint.setColor(ui.dark ? ui.bg : ui.raised);
            canvas.drawRoundRect(new RectF(0, 0, w, h), ui.dp(10), ui.dp(10), paint);
            if (step == null) {
                ui.drawIcon(canvas, key, !isEnabled() ? ui.dim : danger ? ui.danger : ui.accent, w / 2, h / 2, Math.min(w, h) * .54f);
                return;
            }
            float r = Math.min(w, h) * .34f;
            int fill = stepState == 2 ? ui.ok : stepState == 1 ? ui.accent : Color.TRANSPARENT;
            paint.setColor(fill); canvas.drawCircle(w / 2, h / 2, r, paint);
            if (stepState == 0) {
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(ui.dp(1.2f)); paint.setColor(ui.dim);
                canvas.drawCircle(w / 2, h / 2, r, paint); paint.setStyle(Paint.Style.FILL);
            }
            paint.setColor(stepState == 0 ? ui.muted : ui.onAccent); paint.setTypeface(ui.bold());
            paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(r * 1.05f);
            canvas.drawText(step, w / 2, h / 2 - (paint.descent() + paint.ascent()) / 2, paint);
        }
    }

    /** One divided row in a group card: boxed icon, title, optional sub-text, trailing value and chevron. */
    private final class Row {
        LinearLayout view; RowIcon icon; TextView title, sub, value; View chevron, lock; Switch toggle; boolean danger;
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
        /** A padlock replaces the chevron while profile changes are blocked by the connection or a ride. */
        void locked(boolean on) {
            if (lock != null) lock.setVisibility(on ? View.VISIBLE : View.GONE);
            if (chevron != null) chevron.setVisibility(on ? View.GONE : View.VISIBLE);
        }
    }
    private Row lockable(Row r) {
        r.lock = ui.icon("lock", 15, ui.dim);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.dp(15), ui.dp(15)); lp.leftMargin = ui.dp(8);
        r.view.addView(r.lock, lp); r.lock.setVisibility(View.GONE);
        lockedProfileRows.add(r); return r;
    }
    /** Small trailing pill: a step number, a check or a short state such as "PIN ✓". */
    private void pill(TextView value, String text, int color, boolean filled) {
        value.setText(text); value.setVisibility(View.VISIBLE);
        value.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11.5f * ui.textScale);
        value.setTypeface(ui.semibold()); value.setMaxLines(1);
        value.setTextColor(filled ? color : ui.muted);
        value.setPadding(ui.dp(8), ui.dp(2), ui.dp(8), ui.dp(2));
        value.setBackground(ui.shape(filled ? Ui.withAlpha(color, ui.dark ? 46 : 34) : ui.raised, 100, 0));
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
        Switch toggle = new Haptics.FeedbackSwitch(host.activity());
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
        TextView title = ui.text("Your scooter. Your data.", 19, true); title.setPadding(0, 0, 0, ui.dp(4)); c.addView(title);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        onboardingCard = c; cardDestination.addView(c);
        // The setup checklist: each step shows its number until it is done, then a check.
        LinearLayout actions = ui.groupCard(); actions.setBackground(null); actions.setPadding(0, 0, 0, 0);
        c.addView(actions);
        onboardingImport = row(actions, "download", "Import my owner setup", null, () -> { if (canEditProfiles()) host.importOwner(); });
        profileEditActions.add(onboardingImport);
        onboardingNearby = row(actions, "bluetooth", "Allow Nearby devices", null, host::requestBluetooth);
        onboardingConnect = row(actions, "scooter", "Connect", null, () -> {
            ScooterProfiles.Profile p = active();
            if (p == null || !p.configured()) { host.toast("Import your owner setup first"); return; }
            host.connect();
        });
        row(actions, "info", "Owner setup guide", null, () -> { OnboardingScreen.restart(host.activity()); host.rebuild(); });
    }
    private void checklistStep(Row row, int number, boolean done) {
        pill(row.value, done ? "✓" : String.valueOf(number), done ? ui.ok : ui.muted, done);
        row.value.setContentDescription(done ? "Done" : "Step " + number);
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
        if (s.isRecording()) { host.toast("Stop ride first"); return false; }
        if (s.ble.state() != BleController.State.DISCONNECTED || s.ble.pending() != null) {
            host.toast("Disconnect first"); return false;
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
        box.addView(heading);
        if (detail != null && !detail.isEmpty()) note(box, detail);
        else heading.setPadding(0, 0, 0, ui.dp(6));
        return box;
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

        LinearLayout profiles = group();
        chooseProfileRow = lockable(row(profiles, "scooter", "Choose a scooter", null, this::chooseProfile));
        profileEditActions.add(chooseProfileRow);
        profileEditActions.add(lockable(row(profiles, "plus", "Add with owner identity", null, this::addProfile)));
        profileEditActions.add(lockable(row(profiles, "download", "Import owner setup file", null, () -> { if (canEditProfiles()) host.importOwner(); })));
        renameProfileRow = lockable(row(profiles, "pencil", "Rename active scooter", null, this::renameProfile));
        // One way out of the lock: end the ride, or disconnect. Shown only while profile changes are blocked.
        profileUnlock = ui.secondaryButton("Disconnect", () -> {
            ScooterService s = service(); if (closed || s == null) return;
            if (s.isRecording()) {
                s.stopRide();
                if (s.isRecording()) host.toast("Couldn't stop · still recording");
            } else s.ble.disconnect();
            refresh();
        });
        LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(-1, -2); up.topMargin = 0; up.bottomMargin = ui.dp(12);
        cardDestination.addView(profileUnlock, up);

        LinearLayout owner = group();
        disconnectProfileRow = row(owner, "bluetooth", "Disconnect scooter", null, () -> {
            if (service().isRecording()) { host.toast("Stop ride first"); return; }
            service().ble.disconnect(); refresh();
        });
        row(owner, "info", "Run setup again", null, () -> { OnboardingScreen.restart(host.activity()); host.rebuild(); });
        forgetProfileRow = lockable(dangerRow(owner, "Forget active profile", this::forgetProfile));
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
                    service().profiles.select(all.get(which).id); service().ble.profileSelected(); d.dismiss();
                    host.toast(all.get(which).name + " selected"); host.redraw();
                }).setNegativeButton("Close", null).create(); show(dialog);
    }

    private void addProfile() {
        if (!canEditProfiles()) return;
        LinearLayout box = dialogBox("Add your scooter", "Not your NAVEE password.");
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
                dialog.dismiss(); host.toast("Scooter added"); host.redraw();
            } catch (IllegalArgumentException invalid) { error.setText(invalid.getMessage()); }
        });
    }
    private void renameProfile() {
        if (!canEditProfiles()) return;
        ScooterProfiles.Profile p = active(); if (p == null) { addProfile(); return; }
        LinearLayout box = dialogBox("Rename scooter", null);
        EditText name = input("Scooter name", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, false);
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(60)}); name.setText(p.name); box.addView(name);
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setView(box).setNegativeButton("Cancel", null)
                .setPositiveButton("Save name", null).create(); show(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!canEditProfiles()) return;
            String value = name.getText().toString().trim(); if (value.isEmpty()) { name.setError("Enter a scooter name"); return; }
            p.name = value; p.nameDefault = false; service().profiles.update(p); dialog.dismiss(); host.redraw();
        });
    }
    private void backupBeforeForgetting() {
        host.dataBackup(false, null);
    }
    private void forgetProfile() {
        if (!canEditProfiles()) return;
        ScooterProfiles.Profile p = active(); if (p == null) return;
        LinearLayout box = dialogBox("Forget scooter?", "Removes " + p.name + "'s setup. Rides stay.");
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setView(box).create();
        if (p.configured()) {
            TextView backup = ui.secondaryButton("Save backup first", () -> { dialog.dismiss(); backupBeforeForgetting(); });
            box.addView(backup);
        }
        dialogButtons(box, "Cancel", dialog::dismiss, "Forget", () -> {
            dialog.dismiss();
            if (!canEditProfiles()) return; service().profiles.remove(p.id); host.redraw();
        });
        show(dialog);
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
        Row units = row(display, "ruler", "Units", null, () -> selectSection("Units"));
        units.view.setTag("preference-units-menu");
        valueRefreshers.add(() -> units.value(unitsSummary()));
        Row text = row(display, "text", "Text size", null, this::textSize);
        valueRefreshers.add(() -> text.value(Math.round(settings().getFloat("text_scale", 1f) * 100) + "%"));
        preferenceToggle(display, "vibrate", "Haptic feedback", null, Haptics.PREFERENCE, true);
        Row map = row(display, "map", "Map settings", null, this::mapSettings);
        valueRefreshers.add(() -> map.value(basemapSummary()));
        label("Connection & recording");
        LinearLayout connection = group();
        Row rate = row(connection, "clock", "Telemetry refresh", null, this::telemetryRate);
        valueRefreshers.add(() -> rate.value(settings().getInt("poll_interval_ms", 2000) / 1000 + " s"));
        preferenceToggle(connection, "bluetooth", "Connect automatically", null, "auto_connect", true);
        preferenceToggle(connection, "bluetooth", "Stay connected in background", "Recordings always stay connected", "background_connection", false);
        preferenceToggle(connection, "screen", "Keep screen awake", "While recording", "keep_screen_awake", true);
        // Default on. ScooterService/RideAutoStopPolicy decide; this only stores the choice.
        preferenceToggle(connection, "walk", "End parked rides", "10 s after Bluetooth drops at a stop", "ride_auto_stop", true);
        // Battery use is the one setting that decides whether long background recordings survive:
        // show it as the value and open Android's page for this app directly.
        Row background = row(connection, "battery", "Background recording", null,
                () -> openAndroidSettings(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS));
        valueRefreshers.add(() -> {
            boolean unrestricted = batteryUnrestricted();
            background.value(unrestricted ? "Unrestricted ✓" : "Optimized ⚠");
            background.value.setTextColor(unrestricted ? ui.ok : ui.orange);
            background.value.setContentDescription(unrestricted ? "Battery use unrestricted"
                    : "Battery optimized; long recordings may stop. Opens battery settings");
        });
    }
    private boolean batteryUnrestricted() {
        try {
            android.os.PowerManager power = host.activity().getSystemService(android.os.PowerManager.class);
            return power != null && power.isIgnoringBatteryOptimizations(host.activity().getPackageName());
        } catch (RuntimeException unavailable) { return false; }
    }
    /** Units row value, e.g. "mi · °C · 12h"; "Follow system" resolves to the phone's clock. */
    private String unitsSummary() {
        String units = settings().getString("units", "mi"), temperature = settings().getString("temperature_unit", "c");
        String time = settings().getString("time_format", "system");
        boolean hours24 = "24h".equals(time) || (!"12h".equals(time) && android.text.format.DateFormat.is24HourFormat(host.activity()));
        return ("km".equals(units) ? "km" : "dual".equals(units) ? "mi + km" : "mi") + " · "
                + ("f".equals(temperature) ? "°F" : "°C") + " · " + (hours24 ? "24h" : "12h");
    }
    /** Current basemap as a row value: Off, USGS, OSM or Custom. */
    private String basemapSummary() {
        SharedPreferences settings = settings();
        if (!MapPreferences.enabled(settings)) return "Off";
        String url = MapSource.load(settings).url;
        return MapSource.USGS_URL.equals(url) ? "USGS" : MapSource.OSM_URL.equals(url) ? "OSM" : "Custom";
    }
    private void mapSettings() {
        AlertDialog dialog = MapPreferences.show(host);
        if (dialog != null) dialog.setOnDismissListener(d -> { if (!closed) refresh(); });
    }
    private void openAndroidSettings(String action){
        android.content.Intent intent=new android.content.Intent(action);
        if(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS.equals(action))intent.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,host.activity().getPackageName());
        else intent.setData(android.net.Uri.parse("package:"+host.activity().getPackageName()));
        if (startAndroidSettings(intent)) return;
        if (!android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS.equals(action)
                && startAndroidSettings(new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:"+host.activity().getPackageName())))) return;
        // Some manufacturers omit a settings activity. Fall back to the public
        // app list instead of relying on private permission-controller routes.
        if (startAndroidSettings(new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_SETTINGS))) {
            host.toast("Choose Volt to manage its Android settings");
            return;
        }
        host.toast("Open Volt's settings in Android");
    }
    private boolean startAndroidSettings(android.content.Intent intent) {
        try {
            host.activity().startActivity(intent);
            return true;
        } catch (android.content.ActivityNotFoundException | SecurityException unavailable) {
            return false;
        }
    }
    private void openLocationSettings() {
        // Android exposes app details publicly, but not a portable direct link
        // to one app's Location permission page.
        openAndroidSettings(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
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
    private Row preferenceToggle(LinearLayout box, String icon, String title, String detail, String key, boolean fallback) {
        Row row = toggleRow(box, icon, title, detail, enabled -> {
            settings().edit().putBoolean(key, enabled).apply(); host.redraw();
        });
        valueRefreshers.add(() -> refreshSwitch(row, settings().getBoolean(key, fallback), true));
        return row;
    }

    // ---- Data & history -----------------------------------------------------------------

    private void dataHistory() {
        label("Connected history");
        LinearLayout recording = group();
        Row history = preferenceToggle(recording, "history", "Save connected history", "While connected", "history_enabled", true);
        valueRefreshers.add(() -> history.sub(settings().getBoolean("history_enabled", true) ? "While connected" : "Off · last readings kept"));

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

        LinearLayout clear = group();
        dangerRow(clear, "Clear this scooter's history", () -> clearHistory(false));
        dangerRow(clear, "Clear all connected history", () -> clearHistory(true));
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
        confirm("Clear history?", (all ? "Removes history for all scooters" : "Removes " + profile.name + "'s history") + ". Rides stay.",
                "Clear", () -> {
                    if (service() == null || service().history == null) return;
                    service().clearHistory(profileId, () -> {
                        if (closed) return;
                        historyStorageChecked = 0; refreshHistoryStorage(); host.redraw();
                        host.toast("History cleared");
                    });
                });
    }

    private void textSize() {
        LinearLayout box = dialogBox("Text size", null);
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
        LinearLayout box = dialogBox("Telemetry refresh", "Rides always 2 s");
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
        // Steps 1-3 (PIN, pairing, proximity) are numbered on the rows themselves.
        label("Handlebar PIN");
        LinearLayout pin = group();
        pinRow = toggleRow(pin, "lock", "Fallback PIN", null,
                enabled -> { service().ble.enablePin(enabled); host.toast(service().ble.message()); refresh(); });
        pinRow.icon.step = "1";
        pinRefresh = rowButton(pinRow, "history", "Read PIN state", () -> { service().ble.readPin(); refresh(); });
        pinSpinner = rowSpinner(pinRow);
        setPinRow = row(pin, "pencil", "Set or change PIN", null, this::setPin);
        label("Proximity unlock");
        LinearLayout near = group();
        bondRow = row(near, "bluetooth", "Pair this phone with scooter", null,
                () -> { service().ble.bond(); host.toast(service().ble.message()); refresh(); });
        bondRow.icon.step = "2";
        proximityRow = toggleRow(near, "bluetooth", "Proximity unlock", null,
                enabled -> { service().ble.set("proximity_key", enabled ? 1 : 0); host.toast(service().ble.message()); refresh(); });
        proximityRow.icon.step = "3";
        proximitySpinner = rowSpinner(proximityRow);
        LinearLayout chips = row(); chips.setPadding(0, ui.dp(5), 0, 0);
        proximityPinChip = ui.text("", 11.5f, false); chips.addView(proximityPinChip);
        proximityPairedChip = ui.text("", 11.5f, false);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2); cp.leftMargin = ui.dp(6);
        chips.addView(proximityPairedChip, cp);
        ((LinearLayout) proximityRow.title.getParent()).addView(chips);
        // A disabled switch explains itself: tapping the row says what is still missing.
        Switch proximityToggle = proximityRow.toggle;
        proximityRow.view.setOnClickListener(v -> {
            if (proximityToggle.isEnabled()) proximityToggle.performClick();
            else if (proximityReason != null) host.toast(proximityReason);
        });
        securityConnect = ui.button("Connect", host::connect);
        cardDestination.addView(securityConnect);
        securityNotice = footnote("");
        LinearLayout guide = group();
        row(guide, "info", "Unlock without phone", null, this::pinGuide);
    }
    private String proximityReason;
    /** A 48dp icon button placed just before a row's switch. */
    private View rowButton(Row r, String iconKey, String description, Runnable action) {
        FrameLayout button = new FrameLayout(host.activity());
        button.addView(ui.icon(iconKey, 18, ui.accent), new FrameLayout.LayoutParams(ui.dp(18), ui.dp(18), Gravity.CENTER));
        button.setContentDescription(description);
        clickable(button, action);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)); lp.leftMargin = ui.dp(4);
        r.view.addView(button, r.view.indexOfChild(r.toggle), lp);
        return button;
    }
    /** Small spinner shown on a row while the scooter confirms its change. */
    private View rowSpinner(Row r) {
        android.widget.ProgressBar bar = new android.widget.ProgressBar(host.activity(), null, android.R.attr.progressBarStyleSmall);
        bar.setIndeterminate(true); bar.setIndeterminateTintList(ColorStateList.valueOf(ui.accent));
        bar.setContentDescription("Waiting for scooter confirmation");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)); lp.leftMargin = ui.dp(10);
        r.view.addView(bar, r.view.indexOfChild(r.toggle), lp);
        bar.setVisibility(View.GONE);
        return bar;
    }
    private boolean ownerReady() {
        ScooterProfiles.Profile p = active();
        if (service() == null || !service().ble.ready() || p == null || p.role != 0) return false;
        // The resolver decides whether this model has the PIN; riding or a pending change only disable the rows.
        CapabilityResolver.Decision pin = service().ble.decision("pin");
        return pin.available() || pin.blockedByRide() || pin.reason == CapabilityResolver.Reason.BUSY;
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
        LinearLayout box = dialogBox("Set handlebar PIN", "Your phone-free fallback. Keep it safe. Digits are never shown or exported.");
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
        LinearLayout box = dialogBox("Unlock without phone", "Scooter parked and locked.");
        box.addView(new HandlebarDiagram(), new LinearLayout.LayoutParams(-1, ui.dp(116)));
        String[] steps = {"Tap turn signal", "Set digit, squeeze brake", "Press power"};
        for (int i = 0; i < steps.length; i++) {
            LinearLayout line = row(); line.setPadding(0, ui.dp(5), 0, ui.dp(5));
            TextView number = ui.text(String.valueOf(i + 1), 12, true); number.setGravity(Gravity.CENTER);
            number.setTextColor(ui.onAccent); number.setBackground(ui.shape(ui.accent, 100, 0));
            number.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)); np.rightMargin = ui.dp(10);
            line.addView(number, np);
            TextView words = ui.text(steps[i], 14, false); words.setContentDescription("Step " + (i + 1) + ", " + steps[i]);
            line.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            box.addView(line);
        }
        note(box, "With proximity unlock: paired phone close, squeeze brake. Test both while parked before relying on them.");
        ScrollView inner = new ScrollView(host.activity()); inner.addView(box);
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setView(inner).setPositiveButton("Got it", null).create(); show(dialog);
    }
    /** Schematic handlebar: 1 turn-signal button, 2 brake lever, 3 power button. */
    private final class HandlebarDiagram extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        HandlebarDiagram() {
            super(host.activity());
            setContentDescription("Handlebar: 1 turn-signal button, 2 brake lever, 3 power button");
        }
        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), bar = h * .5f;
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(ui.dim); paint.setStrokeWidth(ui.dp(6));
            canvas.drawLine(w * .1f, bar, w * .9f, bar, paint);
            paint.setColor(ui.muted); paint.setStrokeWidth(ui.dp(13));
            canvas.drawLine(w * .04f, bar, w * .17f, bar, paint); canvas.drawLine(w * .83f, bar, w * .96f, bar, paint);
            // Brake lever under the left grip.
            paint.setStrokeWidth(ui.dp(4)); canvas.drawLine(w * .22f, bar + ui.dp(6), w * .06f, bar + ui.dp(20), paint);
            // Turn-signal switch pod beside the left grip.
            paint.setStyle(Paint.Style.FILL); paint.setColor(ui.raised);
            RectF pod = new RectF(w * .21f, bar - ui.dp(15), w * .21f + ui.dp(22), bar + ui.dp(4));
            canvas.drawRoundRect(pod, ui.dp(5), ui.dp(5), paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(ui.dp(1.2f)); paint.setColor(ui.muted);
            canvas.drawRoundRect(pod, ui.dp(5), ui.dp(5), paint);
            // Centre display with its power button.
            paint.setStyle(Paint.Style.FILL); paint.setColor(ui.raised);
            canvas.drawCircle(w / 2, bar, ui.dp(15), paint);
            paint.setStyle(Paint.Style.STROKE); paint.setColor(ui.muted); canvas.drawCircle(w / 2, bar, ui.dp(15), paint);
            ui.drawIcon(canvas, "power", ui.text, w / 2, bar, ui.dp(16));
            marker(canvas, "1", pod.centerX(), pod.top - ui.dp(14));
            marker(canvas, "2", w * .1f, bar + ui.dp(36));
            marker(canvas, "3", w / 2, bar - ui.dp(32));
        }
        private void marker(Canvas canvas, String number, float x, float y) {
            float r = ui.dp(10);
            paint.setStyle(Paint.Style.FILL); paint.setColor(ui.accent); canvas.drawCircle(x, y, r, paint);
            paint.setColor(ui.onAccent); paint.setTypeface(ui.bold()); paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(r * 1.15f);
            canvas.drawText(number, x, y - (paint.descent() + paint.ascent()) / 2, paint);
        }
    }

    // ---- Firmware lab -------------------------------------------------------------------

    private void lab() {
        LinearLayout warning = ui.card(); warning.setOrientation(LinearLayout.HORIZONTAL); warning.setGravity(Gravity.CENTER_VERTICAL);
        warning.setBackground(ui.shape(Ui.withAlpha(ui.orange, ui.dark ? 30 : 26), 20, Ui.withAlpha(ui.orange, 100)));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)); ip.rightMargin = ui.dp(14);
        warning.addView(ui.icon("lab", 24, ui.orange), ip);
        LinearLayout words = column();
        words.addView(ui.text("Unverified on UT5 MAX", 15, true));
        TextView body = ui.text("From other NAVEE models.", 12.5f, false);
        body.setTextColor(ui.muted); body.setPadding(0, ui.dp(4), 0, 0); words.addView(body);
        warning.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        cardDestination.addView(warning);
        labConnect = ui.button("Connect", host::connect);
        cardDestination.addView(labConnect);
        // Section label with the number of fields this firmware supplies, e.g. "3/7".
        LinearLayout header = row();
        TextView heading = ui.sectionLabel("Experimental"); heading.setText("Experimental"); heading.setAllCaps(true);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        labCount = ui.text("", 11.5f, false); labCount.setTypeface(ui.semibold()); labCount.setTextColor(ui.muted);
        labCount.setPadding(ui.dp(8), ui.dp(14), ui.dp(6), ui.dp(6));
        header.addView(labCount);
        cardDestination.addView(header);
        LinearLayout list = group();
        for (ScooterCommands.Feature f : ScooterCommands.catalog()) {
            if (!f.experimental) continue;
            if (list.getChildCount() > 0) ui.divider(list);
            LinearLayout line = row(); line.setMinimumHeight(ui.dp(58)); line.setPadding(ui.dp(2), ui.dp(9), 0, ui.dp(9));
            LinearLayout text = column();
            // M46: rows show only the label; any short description lives in the Inspect dialog.
            text.addView(ui.text(f.label, 15, false));
            line.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
            TextView state = ui.text("", 11.5f, false);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2); sp.leftMargin = ui.dp(8);
            line.addView(state, sp);
            TextView inspect = inspectPill(f);
            // While Inspect is unavailable, tapping the row says why.
            clickable(line, () -> {
                String reason = labReason(f);
                if (reason == null) experimental(f); else host.toast(reason);
            });
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-2, -2); pp.leftMargin = ui.dp(10);
            line.addView(inspect, pp);
            list.addView(line, new LinearLayout.LayoutParams(-1, -2));
            labRows.add(new LabRow(f, state, inspect));
        }
        TextView evidence = ui.text("View command evidence", 13.5f, false); evidence.setTypeface(ui.medium());
        evidence.setTextColor(ui.accent); evidence.setGravity(Gravity.CENTER); evidence.setMinHeight(ui.dp(48));
        clickable(evidence, this::commandEvidence);
        cardDestination.addView(evidence, new LinearLayout.LayoutParams(-1, -2));
        footnote("Never changes firmware.");
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
    /** Why Inspect is unavailable for this field right now, or null when it can be opened. */
    private String labReason(ScooterCommands.Feature feature) {
        ScooterService s = service(); if (s == null) return "Connect first";
        BleController ble = s.ble; ScooterProfiles.Profile p = active();
        if (!ble.ready()) return "Connect first";
        // Lab on the UT5 MAX, but an ordinary OEM-mapped control on this model: it lives in Scooter settings.
        if (oemMapped(ble.decision(feature.key))) return "In Scooter settings for this model";
        Integer current = ble.featureValue(feature.key);
        if (current == null) return "Not reported by firmware";
        if (!feature.allows(current)) return "Unknown value · can't change safely";
        CapabilityResolver.Decision decision = ble.decision(feature.key);
        if (decision.support != CapabilityResolver.Support.KNOWN_ENCODING || (!decision.available() && !decision.blockedByRide()
                && decision.reason != CapabilityResolver.Reason.BUSY)) return decision.reasonText();
        if (ble.pending() != null) return "Wait for the current change";
        return null;
    }
    /** A known, non-Lab variant from another model's mapping (never the UT5 MAX catalogue). */
    private static boolean oemMapped(CapabilityResolver.Decision d) {
        return d.support == CapabilityResolver.Support.KNOWN_ENCODING && d.variant != null && !d.variant.lab
                && d.variant.decode != FeatureVariant.Decode.UT5_FEATURE;
    }
    void inspectQuickFeature(ScooterCommands.Feature feature) {
        if (!closed && feature != null && feature.experimental) experimental(feature);
    }
    private void experimental(ScooterCommands.Feature feature) {
        BleController ble = service().ble; Integer current = ble.featureValue(feature.key);
        if (!ble.ready() || current == null) { host.toast("Not reported by firmware"); return; }
        if (!feature.allows(current)) {
            host.toast("Unknown value · can't change safely"); return;
        }
        LinearLayout box = dialogBox(feature.label, feature.description);
        TextView parked = ui.text("Apply while parked.", 13.5f, true); parked.setTextColor(ui.orange);
        parked.setPadding(0, ui.dp(4), 0, ui.dp(4)); box.addView(parked);
        // Source details stay one tap away under Details.
        TextView source = ui.caption("Current field: " + DisplayUnits.featureValue(feature, current, settings().getString("units", "mi")) + "\nSource command: "
                + String.format(Locale.ROOT, "0x%02X", feature.cmd) + " · status byte " + feature.payloadOffset + " of 0x70");
        source.setPadding(0, 0, 0, ui.dp(7)); source.setVisibility(View.GONE);
        TextView details = ui.text("Details ⌄", 13, false); details.setTextColor(ui.muted);
        details.setGravity(Gravity.CENTER_VERTICAL); details.setMinHeight(ui.dp(40));
        details.setContentDescription("Details");
        clickable(details, () -> {
            boolean open = source.getVisibility() != View.VISIBLE;
            source.setVisibility(open ? View.VISIBLE : View.GONE); details.setText(open ? "Details ⌃" : "Details ⌄");
            if (Build.VERSION.SDK_INT >= 30) details.setStateDescription(open ? "Expanded" : "Collapsed");
        });
        if (Build.VERSION.SDK_INT >= 30) details.setStateDescription("Collapsed");
        box.addView(details, new LinearLayout.LayoutParams(-2, -2)); box.addView(source);
        final int[] selected = {current};
        if (feature.type == ScooterCommands.Type.SLIDER) {
            TextView amount = value(box, DisplayUnits.featureValue(feature, selected[0], settings().getString("units", "mi")));
            List<Integer> valid = new ArrayList<>();
            for (int n = feature.min; n <= feature.max; n += feature.step) if (feature.allows(n)) valid.add(n);
            SeekBar slider = new SeekBar(host.activity()); slider.setMax(Math.max(0, valid.size() - 1));
            int currentIndex = valid.indexOf(current); slider.setProgress(Math.max(0, currentIndex)); box.addView(slider);
            // End labels show the range, e.g. "Off" … "20 mph".
            LinearLayout ends = row(); ends.setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(4));
            TextView low = ui.caption(valid.isEmpty() ? "" : DisplayUnits.featureValue(feature, valid.get(0), settings().getString("units", "mi")));
            TextView high = ui.caption(valid.isEmpty() ? "" : DisplayUnits.featureValue(feature, valid.get(valid.size() - 1), settings().getString("units", "mi")));
            high.setGravity(Gravity.END);
            ends.addView(low, new LinearLayout.LayoutParams(0, -2, 1)); ends.addView(high, new LinearLayout.LayoutParams(0, -2, 1));
            box.addView(ends);
            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int n, boolean user) {
                    selected[0] = valid.get(n); amount.setText(DisplayUnits.featureValue(feature, selected[0], settings().getString("units", "mi")));
                }
                @Override public void onStartTrackingTouch(SeekBar s) { }
                @Override public void onStopTrackingTouch(SeekBar s) { }
            });
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
            if (!feature.allows(selected[0])) return;
            Integer latest = service().ble.featureValue(feature.key);
            if (!service().ble.ready() || latest == null || !feature.allows(latest) || !latest.equals(current)) {
                error.setText("Scooter changed · reopen"); return;
            }
            if (service().ble.set(feature.key, selected[0])) { host.toast(service().ble.message()); dialog.dismiss(); }
            else error.setText(service().ble.message()); refresh();
        });
    }
    private void commandEvidence() {
        LinearLayout box = dialogBox("Command evidence", "PIN and auth bytes hidden");
        Map<String,String> results = service().ble.commandResults();
        if (results.isEmpty()) note(box, "No changes yet");
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
        TextView version = ui.text("Volt " + AppVersion.name(host.activity()), 17, true);
        version.setTag("about-version");
        version.setMinHeight(ui.dp(48)); version.setGravity(Gravity.CENTER_VERTICAL);
        clickable(version, this::tapDeveloperVersion);
        c.addView(version);
        TextView about = ui.caption("Bluetooth only · no account");
        about.setPadding(0, ui.dp(6), 0, 0); c.addView(about);
        cardDestination.addView(c);
        label("Permissions");
        LinearLayout permissions = group();
        nearbyRow = row(permissions, "bluetooth", "Nearby devices", "Connects to your scooter", host::requestBluetooth);
        locationRow = row(permissions, "route", "Location",
                Build.VERSION.SDK_INT < 31 ? "Ride route · also needed for Bluetooth" : "Ride route, only while recording", this::openLocationSettings);
        notificationRow = row(permissions, "sound", "Notifications", "Recording notification with Stop", ()->openAndroidSettings(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS));
        label("Your data");
        LinearLayout data = ui.card(); cardDestination.addView(data);
        note(data, "All data stays on this phone. Back up & restore is in More.");
        LinearLayout maps = group();
        Row online = row(maps, "map", "Online map", null, this::mapSettings);
        valueRefreshers.add(() -> online.value(basemapSummary()));
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
        LinearLayout diagnostics = group();
        row(diagnostics, "check", "Compatibility checker", "Model check & report",
                () -> selectSection("Compatibility")).view.setTag("developer-compatibility-check");
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
        simulationModelRow = row(controls, "scooter", "Simulated model", null, this::chooseSimulationModel);
        simulationModelRow.view.setTag("developer-simulation-model");
        simulationStatus = footnote("Simulation active · sample data");
        simulationStatus.setTag("developer-simulation-status");
        simulationStatus.setTextColor(ui.orange);
        simulationStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        label("Charge recording");
        TextView attached = ui.text("Charger for estimates", 12.5f, false); attached.setTextColor(ui.muted);
        attached.setPadding(ui.dp(2), 0, ui.dp(2), ui.dp(6)); cardDestination.addView(attached);
        ScooterService current=service();
        ChargeSessionStore.Charger chosen=current==null?null:current.chargeCharger();
        chargeChargerSelector=new SegmentedSelector(ui,"Charger for estimates",new String[]{"Auto","Stock","High-speed"},choice->{
            ScooterService s=service();if(s==null||s.isChargeRecording())return;
            s.setChargeCharger(choice==0?null:choice==1?ChargeSessionStore.Charger.STOCK:ChargeSessionStore.Charger.HIGH_SPEED);
            refreshDeveloper();
        });
        chargeChargerSelector.setTag("charge-charger-selector");chargeChargerSelector.select(chosen==null?0:chosen==ChargeSessionStore.Charger.STOCK?1:2,false);
        preferenceSelectors.add(chargeChargerSelector);
        LinearLayout.LayoutParams chargerParams=new LinearLayout.LayoutParams(-1,-2);chargerParams.bottomMargin=ui.dp(12);
        cardDestination.addView(chargeChargerSelector,chargerParams);
        footnote("Auto recognizes UT5 MAX chargers from live power. If uncertain, Volt uses the current rate; choose a charger to override.");
        // Count qualified evidence without claiming every range is ready at a fixed session count.
        chargeCurve=ui.card();chargeCurve.setTag("charge-learning-status");
        LinearLayout curveHead=row();
        TextView curveTitle=ui.text("Saved charge curves",14,false);curveHead.addView(curveTitle,new LinearLayout.LayoutParams(0,-2,1));
        chargeCurveValue=ui.text("",14,true);curveHead.addView(chargeCurveValue);
        chargeCurve.addView(curveHead);
        chargeCurveScope=ui.text("",12.5f,false);chargeCurveScope.setTextColor(ui.muted);chargeCurveScope.setTag("charge-learning-scope");
        chargeCurve.addView(chargeCurveScope);
        cardDestination.addView(chargeCurve);
        LinearLayout capture=group();
        chargeCaptureRow=row(capture,"battery","Record this charge",null,()->{
            ScooterService s=service();if(s==null)return;
            if(s.isChargeRecording()){s.stopChargeRecording();refreshDeveloper();return;}
            String unavailable=s.chargeCaptureUnavailable();if(unavailable!=null){host.toast(unavailable);return;}
            recordChargeDialog(s);
        });chargeCaptureRow.view.setTag("charge-capture-action");
        chargeCaptureStatus=footnote("");chargeCaptureStatus.setTag("charge-capture-status");
        LinearLayout chargeData=group();
        chargeHistoryRow=row(chargeData,"chart","Charge recordings", "Saved estimates & finish times",()->{
            if(chargeHistoryDialog!=null)chargeHistoryDialog.close();
            chargeHistoryDialog=new ChargeHistoryDialog(host);chargeHistoryDialog.show();
        });chargeHistoryRow.view.setTag("charge-history-action");
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
                    .setMessage("Removes charges and learned curves. Rides stay.")
                    .setNegativeButton("Cancel",null).setPositiveButton("Clear recordings",(d,which)->s.clearChargeRecordings(this::refreshDeveloper)).create();show(dialog);
        });clearChargeRow.view.setTag("charge-clear-action");
        label("Test layouts");
        LinearLayout backups = group();
        row(backups, "grid", "Load All widgets into slot 3", null, () -> {
            ScooterProfiles.Profile profile = active();
            if (profile == null) { host.toast("Choose a scooter first."); return; }
            confirm("Replace slot 3?", "Overwrites slot 3 only.", "Replace slot 3", () -> {
                boolean saved = new DashboardConfig(settings(), profile.id).forceExampleSlot();
                host.toast(saved ? "All widgets saved in slot 3." : "Slot 3 could not be saved.");
            });
        }).view.setTag("developer-example-slot");
        LinearLayout visibility = group();
        Row hide = row(visibility, "close", "Hide developer options", null, () -> {
            if (closed) return;
            ScooterService s = service();
            if(s!=null&&s.isChargeRecording()){host.toast("Stop charge recording before hiding developer options");return;}
            if(s!=null&&s.ble.testing()){host.toast("Finish or cancel the test first");return;}
            if (s != null && !s.setSimulation(false)) { refreshDeveloper(); return; }
            selectedSection = "";
            settings().edit().putBoolean("developer_options", false).putString("more_section", "").apply();
            host.rebuild();
        });
        hide.view.setTag("developer-hide-options");
    }

    private void chooseSimulationModel() {
        ScooterService s = service();
        if (closed || s == null || !developerEnabled()) return;
        int selected = Math.max(0, java.util.Arrays.asList(SIMULATION_MODELS).indexOf(s.ble.simulationModel()));
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setTitle("Simulated model")
                .setSingleChoiceItems(SIMULATION_MODEL_NAMES, selected, (d, which) -> {
                    ScooterService current = service();
                    if (closed || current == null || !developerEnabled()) return;
                    current.ble.setSimulationModel(SIMULATION_MODELS[which]); d.dismiss();
                    refreshDeveloper(); host.redraw();
                }).setNegativeButton("Close", null).create(); show(dialog);
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
            int model = s == null ? 0 : Math.max(0, java.util.Arrays.asList(SIMULATION_MODELS).indexOf(s.ble.simulationModel()));
            simulationModelRow.value(model == 0 ? "UT5 MAX" : model == 1 ? "Mapped" : "Unknown");
            simulationModelRow.enabled(enabled && s != null);
        }
        if(chargeCaptureRow!=null){
            ChargeSessionStore.Charger selected=s==null?null:s.chargeCharger();
            chargeChargerSelector.select(selected==null?0:selected==ChargeSessionStore.Charger.STOCK?1:2,false);
            boolean capturing=s!=null&&s.isChargeRecording();
            String unavailable=s==null?"Connect your scooter first":s.chargeCaptureUnavailable();
            chargeCaptureRow.title.setText(capturing?"Stop charge recording":"Record this charge");
            chargeCaptureRow.enabled(enabled&&s!=null&&(capturing||unavailable==null));
            chargeCaptureStatus.setText(capturing||unavailable==null?s.chargeCaptureStatus():unavailable);
            refreshChargeCurve(s);
            clearChargeRow.enabled(enabled&&s!=null&&!capturing&&!s.chargeExporting());
            exportChargeRow.enabled(enabled&&s!=null&&!capturing&&!s.chargeExporting());
            chargeHistoryRow.enabled(enabled&&s!=null&&s.profiles!=null&&s.profiles.active()!=null);
            chargeChargerSelector.setAlpha(capturing?.5f:1f);
            setChildrenEnabled(chargeChargerSelector,!capturing);
        }
        // A stale saved shortcut must not reveal the section after developer options are hidden.
        if (!enabled && "Developer".equals(selectedSection)) selectSection("");
    }

    /**
     * "Record with Stock charger?": the charger is picked inside the dialog, and the title follows it.
     * Older recordings beyond the 20 kept are dropped.
     */
    private void recordChargeDialog(ScooterService s) {
        ScooterProfiles.Profile profile = active();
        if (profile == null) { host.toast("Choose a scooter first."); return; }
        final String profileId = profile.id;
        // Capture labels train curves, so only an explicit preference is pre-selected; an
        // Automatic guess must never become a label in one tap. An open panel must never start
        // a different scooter's recording.
        final ChargeSessionStore.Charger[] selected = {s.chargeCharger()};
        LinearLayout box = dialogBox("", "Saved on phone · stops at 100% · keeps the last 20");
        TextView heading = (TextView) box.getChildAt(0);
        final TextView[] start = new TextView[1];
        Runnable title = () -> {
            heading.setText(selected[0] == null ? "Which charger is attached?"
                    : "Record with " + (selected[0] == ChargeSessionStore.Charger.STOCK ? "Stock" : "High-speed") + " charger?");
            if (start[0] != null) { start[0].setEnabled(selected[0] != null); start[0].setAlpha(selected[0] != null ? 1f : .5f); }
        };
        SegmentedSelector picker = new SegmentedSelector(ui, "Attached charger", new String[]{"Stock", "High-speed"}, choice -> {
            selected[0] = choice == 0 ? ChargeSessionStore.Charger.STOCK : ChargeSessionStore.Charger.HIGH_SPEED;
            title.run();
        });
        picker.setTag("charge-confirmation-selector");
        picker.select(selected[0] == null ? -1 : selected[0] == ChargeSessionStore.Charger.STOCK ? 0 : 1, false);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, -2); pp.topMargin = ui.dp(6); pp.bottomMargin = ui.dp(6);
        box.addView(picker, pp);
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setView(box).create();
        start[0] = dialogButtons(box, "Cancel", dialog::dismiss, "Start recording", () -> {
            if (selected[0] == null) return;
            dialog.dismiss();
            ScooterProfiles.Profile current = active();
            if (closed || service() != s || current == null || !profileId.equals(current.id)) {
                host.toast("Scooter changed · reopen"); return;
            }
            if (!s.startChargeRecording(selected[0])) host.toast(s.chargeCaptureStatus());
            refreshDeveloper();
        })[1];
        start[0].setTag("charge-confirmation-start");
        title.run();
        show(dialog, picker::finishMotion);
    }
    /** One completed finish can be useful; broad ranges can need more evidence. */
    private void refreshChargeCurve(ScooterService s) {
        if (chargeCurve == null) return;
        int qualified = s == null ? -1 : s.qualifiedChargeCurves();
        // Count only the family estimates use; while Automatic is undecided, none applies.
        ChargeSessionStore.Charger effective = s == null ? null : s.effectiveChargeCharger();
        String scope = qualified < 0 ? "" : effective == null ? "Not applied until the charger is known"
                : effective == ChargeSessionStore.Charger.STOCK ? "Stock charges" : "High-speed charges";
        chargeCurveValue.setText(qualified < 0 ? "…" : effective == null ? "—" : Integer.toString(qualified));
        chargeCurveValue.setTextColor(qualified > 0 ? ui.accent : ui.muted);
        chargeCurveScope.setText(scope);chargeCurveScope.setVisibility(scope.isEmpty() ? View.GONE : View.VISIBLE);
        chargeCurve.setContentDescription(qualified < 0 ? "Saved charge curves loading" : effective == null ? "Saved charge curves not applied until the charger is known"
                : qualified + " qualified " + (effective == ChargeSessionStore.Charger.STOCK ? "stock" : "high-speed") + " charge recordings");
        chargeCurve.setTooltipText("A completed charge can refine its finishing range. Other ranges need two matching recordings. Estimates depend on coverage and live power; UT5 MAX stock and high-speed estimates also work without recordings.");
    }
    private void setChildrenEnabled(View view,boolean enabled){
        view.setEnabled(enabled);if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)setChildrenEnabled(((ViewGroup)view).getChildAt(i),enabled);
    }

    private void licenses() {
        LinearLayout box = dialogBox("Licenses & attribution", null);
        note(box, "Saira typeface\nCopyright 2020 The Saira Project Authors. SIL Open Font License 1.1; not covered by the app's MIT licence.");
        note(box, "Shizuku API 13.1.5\nCopyright (c) 2021 RikkaW. MIT License. Optional phone-only owner setup.");
        note(box, "AndroidX annotations 1.3.0\nCopyright The Android Open Source Project. Apache License 2.0.");
        note(box, "Protocol research reference\npepperonas/navee-st3-pro, MIT License.");
        note(box, "UT5 MAX photo\n© NAVEE; not covered by app licence.");
        note(box, "Maps\nMap data and tiles belong to the selected provider. USGS Topo: U.S. Geological Survey, National Geospatial Program. OpenStreetMap: © OpenStreetMap contributors.");
        LinearLayout notices = ui.groupCard(); notices.setBackground(null); notices.setPadding(0, 0, 0, 0); box.addView(notices);
        row(notices, "copy", "View full notices", null, this::fullNotices);
        ScrollView inner = new ScrollView(host.activity()); inner.addView(box);
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setView(inner).setPositiveButton("Close", null).create(); show(dialog);
    }
    /** The licence texts bundled in the APK assets, shown as they ship. */
    private void fullNotices() {
        LinearLayout box = dialogBox("Full notices", null);
        try {
            android.content.res.AssetManager assets = host.activity().getAssets();
            String[] files = assets.list("");
            if (files != null) {
                Arrays.sort(files);
                for (String file : files) {
                    if (!file.endsWith(".txt")) continue;
                    StringBuilder text = new StringBuilder();
                    try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                            assets.open(file), java.nio.charset.StandardCharsets.UTF_8))) {
                        String line; while ((line = reader.readLine()) != null) text.append(line).append('\n');
                    }
                    TextView name = ui.text(file.substring(0, file.length() - 4).replace('-', ' '), 15, true);
                    name.setPadding(0, ui.dp(12), 0, ui.dp(4)); box.addView(name);
                    TextView body = ui.caption(text.toString().trim()); body.setTextIsSelectable(true); box.addView(body);
                }
            }
        } catch (java.io.IOException unreadable) { note(box, "Notices could not be read."); }
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

    // ---- Refresh ------------------------------------------------------------------------

    private void refreshProfiles(ScooterService s, ScooterProfiles.Profile p) {
        DeviceDescriptor saved = p == null ? null : p.savedDescriptor();
        boolean recognised = ModelRegistry.showsPhoto(saved);
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
        // The model line appears once a connection has reported it; the UT5 MAX is shown by name.
        String modelLine = ModelRegistry.modelLine(saved);
        boolean modelKnown = !modelLine.isEmpty();
        scootersModel.setText(modelLine);
        scootersModel.setVisibility(modelKnown ? View.VISIBLE : View.GONE);
        scootersProfileCard.setContentDescription(name + ", " + scootersRole.getText()
                + (modelKnown ? ", " + scootersModel.getText() : ""));
        chooseProfileRow.value(count == 0 ? "" : count + (count == 1 ? " profile" : " profiles"));
    }
    private void refreshPermissions(ScooterService s) {
        boolean nearby = s.ble.permissions();
        permissionValue(nearbyRow, nearby ? "Allowed" : "Not allowed", nearby);
        boolean fine = granted(Manifest.permission.ACCESS_FINE_LOCATION), coarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION);
        permissionValue(locationRow, fine ? "Allowed" : coarse ? "Approximate" : "Not allowed", fine || coarse);
        NotificationManager notifications = host.activity().getSystemService(NotificationManager.class);
        boolean notify = notifications != null && notifications.areNotificationsEnabled();
        permissionValue(notificationRow, notify ? "Allowed" : "Off", notify);
    }
    private CharSequence pinStatus(ScooterCommands.PinState pin) {
        if (pin == null) return "Not read";
        if (pin.status != 0) return "Rejected by scooter";
        if (!pin.configured) return "Not set";
        if (!Boolean.TRUE.equals(pin.enabled)) return "Disabled";
        SpannableString enabled = new SpannableString("Enabled");
        enabled.setSpan(new ForegroundColorSpan(ui.ok), 0, "Enabled".length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return enabled;
    }

    @Override public void refresh() {
        if(chargeHistoryDialog!=null)chargeHistoryDialog.refresh();
        ScooterService s = service(); if (s == null) return;
        refreshDeveloper();
        if (shownSection().equals("Data")) refreshHistoryStorage();
        if (compatibilityPanel != null) compatibilityPanel.refresh();
        for (Runnable refresh : valueRefreshers) refresh.run();
        ScooterProfiles.Profile p = active();
        boolean configured = p != null && p.configured();
        onboardingCard.setVisibility(configured ? View.GONE : View.VISIBLE);
        boolean profilesEditable = !s.isRecording() && s.ble.state() == BleController.State.DISCONNECTED && s.ble.pending() == null;
        for (Row action : profileEditActions) action.enabled(profilesEditable);
        renameProfileRow.enabled(profilesEditable && p != null);
        forgetProfileRow.enabled(profilesEditable && p != null);
        for (Row row : lockedProfileRows) row.locked(!profilesEditable);
        boolean recording = s.isRecording(), linked = s.ble.state() != BleController.State.DISCONNECTED;
        profileUnlock.setVisibility(recording || linked ? View.VISIBLE : View.GONE);
        profileUnlock.setText(recording ? "Stop ride" : "Disconnect");
        profileUnlock.setContentDescription(recording ? "Stop ride to change profiles" : "Disconnect to change profiles");
        checklistStep(onboardingImport, 1, configured);
        checklistStep(onboardingNearby, 2, s.ble.permissions());
        checklistStep(onboardingConnect, 3, s.ble.ready());
        // Connect needs the owner setup first; the row stays tappable to say so.
        onboardingConnect.title.setTextColor(configured ? ui.text : ui.muted);
        onboardingConnect.icon.setEnabled(configured); onboardingConnect.icon.invalidate();
        disconnectProfileRow.enabled(!s.isRecording() && s.ble.state() != BleController.State.DISCONNECTED);
        refreshProfiles(s, p);
        refreshPermissions(s);
        ScooterCommands.PinState pin = s.ble.pinState(); boolean owner = ownerReady(), busy = s.ble.pending() != null;
        String pending = s.ble.pending();
        pinRow.sub(pinStatus(pin));
        setPinRow.enabled(owner && !busy);
        pinRefresh.setEnabled(owner && !busy); pinRefresh.setAlpha(owner && !busy ? 1f : .35f);
        boolean pinPending = "pin".equals(pending);
        pinSpinner.setVisibility(pinPending ? View.VISIBLE : View.GONE);
        pinRefresh.setVisibility(pinPending ? View.GONE : View.VISIBLE);
        refreshSwitch(pinRow, pin == null || pin.status != 0 ? null : pin.enabled,
                owner && !busy && pin != null && pin.status == 0 && pin.configured);
        Integer proximity = s.ble.featureValue("proximity_key"); boolean paired = s.ble.bonded();
        boolean pinOn = pin != null && pin.status == 0 && pin.configured && Boolean.TRUE.equals(pin.enabled);
        boolean enablePrerequisites = paired && pinOn;
        refreshSwitch(proximityRow, proximity == null ? null : proximity == 1,
                owner && !busy && proximity != null && (proximity == 1 || enablePrerequisites));
        proximitySpinner.setVisibility("proximity_key".equals(pending) ? View.VISIBLE : View.GONE);
        pill(proximityPinChip, pinOn ? "PIN ✓" : "PIN —", ui.ok, pinOn);
        proximityPinChip.setContentDescription(pinOn ? "PIN enabled" : "PIN not enabled");
        pill(proximityPairedChip, paired ? "Paired ✓" : "Paired —", ui.ok, paired);
        proximityPairedChip.setContentDescription(paired ? "Phone paired" : "Phone not paired");
        BleController.State link = s.ble.state();
        CapabilityResolver.Decision near = s.ble.decision("proximity_key");
        // The UT5 MAX keeps its wording; other models say what the resolver decided.
        String modelReason = near.layer == ModelRegistry.Layer.UT5_MAX ? "UT5 MAX owner only" : p != null && p.role != 0 ? "Owner only"
                : near.support != CapabilityResolver.Support.KNOWN_ENCODING ? near.reasonText() : s.ble.decision("pin").reasonText();
        proximityReason = proximityRow.toggle.isEnabled() ? null
                : !owner ? (link == BleController.State.READY ? modelReason : "Connect first")
                : busy ? "Waiting for scooter"
                : proximity == null ? "Not reported by firmware"
                : !pinOn ? "Enable the fallback PIN first"
                : !paired ? "Pair this phone first" : null;
        if (proximityReason != null) proximityRow.toggle.setContentDescription(proximityRow.title.getText() + ", " + proximityReason);
        bondRow.enabled(owner && !busy && !paired && pinOn);
        permissionValue(bondRow, paired ? "Paired" : "", paired);
        // Step badges: green once done, red for the next step.
        pinRow.icon.step(pinOn ? 2 : 1);
        bondRow.icon.step(paired ? 2 : pinOn ? 1 : 0);
        proximityRow.icon.step(proximity != null && proximity == 1 ? 2 : enablePrerequisites ? 1 : 0);
        securityConnect.setVisibility(link == BleController.State.DISCONNECTED ? View.VISIBLE : View.GONE);
        String notice = owner ? "Test while parked." : link == BleController.State.READY ? modelReason : "";
        securityNotice.setText(notice);
        securityNotice.setVisibility(notice.isEmpty() ? View.GONE : View.VISIBLE);
        int available = 0;
        Map<String,String> results = s.ble.commandResults();
        for (LabRow row : labRows) {
            Integer current = s.ble.featureValue(row.feature.key);
            String result = results.get(row.feature.key);
            if (current != null) available++;
            boolean knownBaseline = current != null && row.feature.allows(current);
            // Grey "—": not supplied. Orange "?": outside the explained range. Otherwise the value with
            // a green dot once a change was confirmed by readback, orange while behaviour is unverified.
            boolean confirmed = result != null && (result.startsWith("Confirmed") || result.startsWith("Setting confirmed"));
            if (current == null) {
                pill(row.value, "—", ui.muted, false);
                row.value.setContentDescription("Not reported by firmware");
            } else if (!knownBaseline) {
                pill(row.value, "?", ui.orange, true);
                row.value.setContentDescription("Unknown value " + current);
            } else {
                String shown = DisplayUnits.featureValue(row.feature, current, settings().getString("units", "mi"));
                pill(row.value, "● " + shown, confirmed ? ui.ok : ui.orange, true);
                row.value.setContentDescription(shown + (confirmed ? ", confirmed" : result == null ? ", unverified" : ", " + result));
            }
            CapabilityResolver.Decision decision = s.ble.decision(row.feature.key);
            boolean inspectable = s.ble.ready() && (decision.available() || decision.blockedByRide()) && !busy && knownBaseline && !oemMapped(decision);
            row.action.setEnabled(inspectable);
            // A disabled pill passes its taps to the row, which explains why.
            row.action.setClickable(inspectable);
        }
        boolean disconnected = s.ble.state() == BleController.State.DISCONNECTED;
        labConnect.setVisibility(disconnected ? View.VISIBLE : View.GONE);
        labCount.setText(s.ble.ready() ? available + "/" + labRows.size() : "");
        labCount.setContentDescription(s.ble.ready() ? available + " of " + labRows.size() + " fields reported" : null);
    }
}
