package com.jjricks.volt;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.MetricAffectingSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * First-run setup: one step per screen, no tab bar. Walks a new owner from "no profile" to a
 * verified local connection using the same owner-file import, identity parser, profile store
 * and connection path as the rest of the app. Progress lives in its own preferences file so a
 * rebuild, rotation or process restart resumes on the same step. The owner identity is never
 * logged or stored here; it only passes through {@link OwnerIdentityParser} into
 * {@link ScooterProfiles}.
 */
public final class OnboardingScreen implements AppScreen {
    /** MainActivity implements this to leave the flow. */
    public interface Listener {
        /** "Go to Home" on the final screen. Onboarding is already marked complete. */
        void onFinished();
        /** "Skip" on the path screen. Onboarding is already marked complete. */
        default void onSkipped() { onFinished(); }
    }

    /** Set by {@link #restart}: the owner chose to run setup again from More. */
    private static final String KEY_RERUN = "rerun";
    /** Preference file holding only setup progress; never identities. */
    public static final String PREFS = "onboarding";
    /** True once the flow was finished or skipped; {@link #needed} then stays false. */
    public static final String KEY_DONE = "onboarding_done";
    private static final String KEY_STEP = "step", KEY_PATH = "path", KEY_PROFILE = "profile_id",
            KEY_CREATED = "created_profile_id", KEY_PENDING_NAME = "pending_name",
            KEY_IMPORT_FAILED = "import_failed", KEY_ASKED_NEARBY = "nearby_asked",
            KEY_CONNECT = "connect_requested", KEY_TRIED = "connect_tried", KEY_TICK = "tick_";
    /** MainActivity's private staging file for a picked owner file awaiting the service. */
    private static final String PENDING_OWNER_FILE = "pending-owner-import.json";

    static final int WELCOME = 0, PATH = 1, PREPARE = 2, USB = 3, COMPUTER = 4, HELPER = 5,
            IMPORT = 6, IDENTITY = 7, NEARBY = 8, CONNECT = 9, DONE = 10;
    private static final String CAPTURE = "capture", WEBADB = "webadb", BACKUP = "backup", RESTORE = "restore", TYPED = "identity", SHIZUKU = "shizuku";
    private static final String WEBADB_URL = "https://pairscooter.jjricks.com/";
    private static final String PLATFORM_TOOLS_URL = "https://developer.android.com/tools/releases/platform-tools";
    private static final String SOURCE_URL = "https://github.com/JJRicks/navee-offline/releases/latest";
    private static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";
    private static final String[] STEP_NAMES = {"Prepare", "USB debugging", "Connect computer",
            "Run helper", "Import", "Nearby devices", "Connect scooter"};
    private static final String COMMAND = "python3 setup/capture-owner.py --push";
    private static final int MAX_WIDTH_DP = 560;
    /** Unfolded and other wide screens put the step list beside the current step (MainActivity's threshold). */
    private static final int WIDE_DP = 650;
    private static Bitmap photo;

    private final ScreenHost host;
    private final Ui ui;
    private final Listener listener;
    private final SharedPreferences prefs;
    private final FrameLayout root;
    private final List<Runnable> updaters = new ArrayList<>();
    private final List<AlertDialog> dialogs = new ArrayList<>();
    private LinearLayout page, content, footer;
    /** What scaffold() put in root: the page alone, or the step list and page side by side. */
    private View body;
    private TextView title;
    private View sheet;
    private EditText identityField, nameField;
    private ScooterProfiles standalone;
    private int step;
    private String renderedKey = "";
    private boolean closed;

    public OnboardingScreen(ScreenHost host, Listener listener) {
        this.host = host;
        this.ui = host.ui();
        this.listener = listener;
        this.prefs = prefs(host.activity());
        int saved = prefs.getInt(KEY_STEP, WELCOME);
        step = saved < WELCOME || saved > DONE ? WELCOME : saved;
        root = new FrameLayout(host.activity());
        root.setBackgroundColor(ui.bg);
        render();
    }

    // ---------------------------------------------------------------- integration surface

    /**
     * True while the first-run flow should replace the tabs: not finished or skipped, and either
     * already started or there is no configured owner profile. Safe before the service binds.
     */
    public static boolean needed(Context context, ScooterService service) {
        SharedPreferences p = prefs(context);
        if (p.getBoolean(KEY_DONE, false)) return false;
        // "Run setup again" keeps the flow open on every step, including Welcome, until it is left.
        if (p.getBoolean(KEY_RERUN, false)) return true;
        if (p.getInt(KEY_STEP, WELCOME) > WELCOME) return true;
        return !hasConfiguredProfile(context, service);
    }

    /** Re-opens the flow at "Choose a path" (for a "Run setup again" entry). Call rebuild() after. */
    public static void restart(Context context) {
        prefs(context).edit().clear().putInt(KEY_STEP, PATH).putBoolean(KEY_RERUN, true).commit();
    }

    /** Marks setup complete without showing it, e.g. for test fixtures. */
    public static void markDone(Context context) {
        prefs(context).edit().clear().putBoolean(KEY_DONE, true).commit();
    }

    /** Only this setup attempt may reuse an unidentified profile; Add a scooter clears these IDs. */
    ScooterProfiles.Profile ownerImportProfile() {
        if (closed) return null;
        ScooterProfiles store = profiles();
        ScooterProfiles.Profile profile = find(store, prefs.getString(KEY_PROFILE, ""));
        return profile != null ? profile : find(store, prefs.getString(KEY_CREATED, ""));
    }

    /**
     * MainActivity reports the owner-file picker outcome here: true after the picked file was
     * applied as the active profile, false when it was unreadable or rejected.
     */
    public void onOwnerImported(boolean ok) {
        if (closed) return;
        if (!ok) {
            prefs.edit().putBoolean(KEY_IMPORT_FAILED, true).apply();
            if (step != IMPORT) go(IMPORT); else render();
            return;
        }
        ScooterProfiles store = profiles();
        ScooterProfiles.Profile active = store == null ? null : store.active();
        if (active == null) throw new IllegalStateException("Imported scooter is unavailable");
        SharedPreferences.Editor edit = prefs.edit().putBoolean(KEY_IMPORT_FAILED, false);
        edit.putString(KEY_PROFILE, active.id);
        boolean moveToImport = step < IMPORT || step == IDENTITY;
        if (moveToImport) {
            // A Shizuku capture returns here before its Import page is shown; it keeps its own path.
            if ((step == IDENTITY || step <= PATH) && !SHIZUKU.equals(path())) edit.putString(KEY_PATH, BACKUP);
            edit.putInt(KEY_STEP, IMPORT);
        }
        // MainActivity keeps the recovery file until this profile/step binding is durable.
        if (!edit.commit()) throw new IllegalStateException("Could not save setup progress");
        applyPendingName();
        if (moveToImport) go(IMPORT); else refresh();
    }

    /**
     * Phone-only setup sent the owner to a backup import instead (e.g. from a work profile, where
     * capture isn't supported). MainActivity calls this for {@link ShizukuSetupActivity#RESULT_IMPORT_BACKUP}.
     */
    public void showImport() {
        if (closed) return;
        prefs.edit().putString(KEY_PATH, BACKUP).apply();
        go(IMPORT);
    }

    /** A separately consented capture has been staged privately, before Nearby permission. */
    public void onOwnerImportStaged() {
        if (!closed) go(IMPORT);
    }

    /** System Back: closes the sheet or returns to the previous step. False on the first screen. */
    public boolean back() {
        if (closed) return false;
        if (sheet != null) { closeSheet(); return true; }
        switch (step) {
            case WELCOME:
                // A re-run from More returns to the tabs instead of closing the app.
                if (prefs.getBoolean(KEY_RERUN, false)) { finish(true); return true; }
                return false;
            case PATH:
                if (prefs.getBoolean(KEY_RERUN, false)) { finish(true); return true; }
                go(WELCOME); return true;
            case PREPARE: go(PATH); return true;
            case IMPORT: go(isComputerCapture() ? HELPER : PATH); return true;
            case IDENTITY: go(PATH); return true;
            case NEARBY: go(importStep()); return true;
            case CONNECT:
                stopConnecting();
                go(hasPermission() ? importStep() : NEARBY); return true;
            case DONE: finish(false); return true;
            default: go(step - 1); return true;
        }
    }

    @Override public View view() { return root; }

    @Override public void refresh() {
        if (closed) return;
        applyPendingName();
        ScooterService service = host.service();
        if (step == NEARBY && prefs.getBoolean(KEY_ASKED_NEARBY, false) && hasPermission()) { toConnect(); return; }
        if (step == CONNECT && service != null && service.ble.ready()) {
            prefs.edit().remove(KEY_CONNECT).apply(); go(DONE); return;
        }
        if (step == CONNECT) maybeAutoConnect();
        if (!structureKey().equals(renderedKey)) { render(); return; }
        for (Runnable updater : updaters) updater.run();
    }

    @Override public void close() {
        closed = true;
        if (identityField != null) identityField.setText("");
        for (AlertDialog dialog : new ArrayList<>(dialogs)) dialog.dismiss();
        dialogs.clear();
    }

    // ---------------------------------------------------------------- state

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static boolean hasConfiguredProfile(Context context, ScooterService service) {
        if (service != null) {
            for (ScooterProfiles.Profile p : service.profiles.all()) if (p.configured()) return true;
            return false;
        }
        // Read-only look at the stored list so an unbound service never triggers a write here.
        SharedPreferences stored = context.getSharedPreferences("profiles", Context.MODE_PRIVATE);
        try {
            JSONArray items = new JSONArray(stored.getString("items", "[]"));
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.getJSONObject(i);
                int role = o.optInt("role", 0);
                if (o.optString("identity", "").matches("(?i)[0-9a-f]{12}") && (role == 0 || role == 1)) return true;
            }
        } catch (Exception ignored) { }
        if (!stored.getBoolean("legacy_migrated", false)) {
            String legacy = context.getSharedPreferences("scooter", Context.MODE_PRIVATE).getString("identity", "");
            if (legacy != null && legacy.matches("(?i)[0-9a-f]{12}")) return true;
        }
        return false;
    }

    private String path() { return prefs.getString(KEY_PATH, CAPTURE); }
    private boolean isComputerCapture() { return CAPTURE.equals(path()) || WEBADB.equals(path()); }
    private int importStep() { return TYPED.equals(path()) ? IDENTITY : IMPORT; }

    private boolean hasPermission() {
        Context c = host.activity();
        if (Build.VERSION.SDK_INT >= 31)
            return c.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * The service's profile store when bound. Without Nearby devices permission the service
     * cannot be running, so a private instance over the same preferences is safe and the
     * service picks the profile up when it starts. While permission exists but the service is
     * still binding this returns null so two stores never write side by side.
     */
    private ScooterProfiles profiles() {
        ScooterService service = host.service();
        if (service != null) return service.profiles;
        if (hasPermission()) return null;
        if (standalone == null) standalone = new ScooterProfiles(host.activity());
        return standalone;
    }

    private static ScooterProfiles.Profile find(ScooterProfiles store, String id) {
        if (store == null || id == null || id.isEmpty()) return null;
        for (ScooterProfiles.Profile p : store.all()) if (p.id.equals(id)) return p;
        return null;
    }

    private ScooterProfiles.Profile onboardingProfile() {
        ScooterProfiles store = profiles();
        ScooterProfiles.Profile p = find(store, prefs.getString(KEY_PROFILE, ""));
        return p != null ? p : store == null ? null : store.active();
    }

    /** Same rules as More › My scooters: never while recording or mid-command; disconnect first. */
    private boolean prepareProfileChange() {
        ScooterService service = host.service();
        if (service == null) return true;
        String unavailable = service.scooterChangeUnavailable();
        if (unavailable != null) { host.toast(unavailable); return false; }
        if (service.ble.state() != BleController.State.DISCONNECTED) service.ble.disconnect();
        return true;
    }

    /** A name typed while the picked file was still waiting is applied once its profile exists. */
    private void applyPendingName() {
        String name = prefs.getString(KEY_PENDING_NAME, null);
        if (name == null) return;
        ScooterProfiles store = profiles();
        ScooterProfiles.Profile p = find(store, prefs.getString(KEY_PROFILE, ""));
        if (p == null) return;
        if (!name.trim().isEmpty() && !name.trim().equals(p.name)) { p.name = name.trim(); p.nameDefault = false; store.update(p); }
        prefs.edit().remove(KEY_PENDING_NAME).apply();
    }

    private File pendingOwnerFile() { return new File(host.activity().getFilesDir(), PENDING_OWNER_FILE); }

    /** Reads only the display name and role of the staged file; the identity is not kept. */
    private String[] pendingSummary() {
        String name = MainActivity.IMPORTED_NAME; int role = 0;
        try (InputStream in = new FileInputStream(pendingOwnerFile())) {
            byte[] buffer = new byte[8192]; int total = 0, count;
            while (total < buffer.length && (count = in.read(buffer, total, buffer.length - total)) > 0) total += count;
            JSONObject json = new JSONObject(new String(buffer, 0, total, StandardCharsets.UTF_8));
            name = json.optString("name", name).trim(); role = json.optInt("role", 0);
            if (name.isEmpty()) name = MainActivity.IMPORTED_NAME;
        } catch (Exception ignored) { }
        return new String[] {name, role == 1 ? "shared role" : "owner role"};
    }

    private String importMode() {
        String id = prefs.getString(KEY_PROFILE, "");
        ScooterProfiles store = profiles();
        if (!id.isEmpty()) {
            if (store == null) return "loading";
            ScooterProfiles.Profile p = find(store, id);
            if (p != null && p.configured()) return "applied";
        }
        if (pendingOwnerFile().isFile()) return "pending";
        return "choose";
    }

    private String structureKey() {
        return step + "|" + path() + "|" + (step == IMPORT ? importMode() : "");
    }

    private void go(int next) {
        // Steps move like pages: forward arrives from the right, Back returns from the left.
        Motion.page(root, Integer.signum(next - step), () -> {
            step = next;
            prefs.edit().putInt(KEY_STEP, next).apply();
            render();
        });
        if (title != null) title.post(() -> {
            if (!closed && title != null) title.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
        });
    }

    private void finish(boolean skipped) {
        prefs.edit().clear().putBoolean(KEY_DONE, true).commit();
        closed = true;
        if (identityField != null) identityField.setText("");
        if (listener != null) { if (skipped) listener.onSkipped(); else listener.onFinished(); }
    }

    private void toConnect() {
        prefs.edit().putBoolean(KEY_CONNECT, true).remove(KEY_ASKED_NEARBY).apply();
        go(CONNECT);
        maybeAutoConnect();
    }

    private void afterProfile() {
        if (hasPermission()) toConnect(); else go(NEARBY);
    }

    private void maybeAutoConnect() {
        if (!prefs.getBoolean(KEY_CONNECT, false)) return;
        ScooterService service = host.service();
        if (service == null || service.isRecording()) return;
        ScooterProfiles.Profile active = service.profiles.active();
        if (active == null || !active.configured() || pendingOwnerFile().isFile()) return;
        BleController.State state = service.ble.state();
        if (state != BleController.State.DISCONNECTED) {
            // Startup may already be scanning for a profile with no saved scooter. Only
            // host.connect() opens the chooser, so restart that scan through it; any other
            // in-progress connection is left alone.
            boolean scanningWithoutChooser = active.mac.isEmpty()
                    && (state == BleController.State.SCANNING || state == BleController.State.WAITING);
            if (!scanningWithoutChooser) { prefs.edit().remove(KEY_CONNECT).apply(); return; }
            service.ble.disconnect();
        }
        prefs.edit().remove(KEY_CONNECT).putBoolean(KEY_TRIED, true).apply();
        host.connect();
    }

    private void stopConnecting() {
        prefs.edit().remove(KEY_CONNECT).apply();
        ScooterService service = host.service();
        if (service == null || service.isRecording() || service.ble.ready()) return;
        if (service.ble.state() != BleController.State.DISCONNECTED) service.ble.disconnect();
    }

    // ---------------------------------------------------------------- rendering

    private void render() {
        if (closed) return;
        String keepName = nameField != null ? nameField.getText().toString() : null;
        int keepStep = renderedKey.isEmpty() ? -1 : Integer.parseInt(renderedKey.substring(0, renderedKey.indexOf('|')));
        if (identityField != null) identityField.setText("");
        updaters.clear(); identityField = null; nameField = null; title = null; sheet = null;
        root.removeAllViews();
        switch (step) {
            case WELCOME: welcome(); break;
            case PATH: choosePath(); break;
            case PREPARE: prepare(); break;
            case USB: usbDebugging(); break;
            case COMPUTER: connectComputer(); break;
            case HELPER: runHelper(); break;
            case IMPORT: importFile(); break;
            case IDENTITY: enterIdentity(); break;
            case NEARBY: nearby(); break;
            case CONNECT: connectScooter(); break;
            default: done(); break;
        }
        if (keepName != null && nameField != null && keepStep == step) nameField.setText(keepName);
        renderedKey = structureKey();
        if (Build.VERSION.SDK_INT >= 28) root.setAccessibilityPaneTitle(number(step) > 0
                ? "Setup step " + number(step) + " of 7" : "Setup");
        for (Runnable updater : updaters) updater.run();
    }

    private static int number(int step) {
        switch (step) {
            case PREPARE: return 1; case USB: return 2; case COMPUTER: return 3; case HELPER: return 4;
            case IMPORT: case IDENTITY: return 5; case NEARBY: return 6; case CONNECT: return 7;
            default: return 0;
        }
    }

    /** Page skeleton: optional progress header, scrolling body and a pinned footer. */
    private void scaffold(boolean header) {
        page = new LinearLayout(host.activity()) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int width = MeasureSpec.getSize(widthSpec), max = ui.dp(MAX_WIDTH_DP);
                if (MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED && width > max)
                    widthSpec = MeasureSpec.makeMeasureSpec(max, MeasureSpec.EXACTLY);
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(14));
        if (header) page.addView(header(), new LinearLayout.LayoutParams(-1, -2));
        ScrollView scroll = new ScrollView(host.activity());
        scroll.setFillViewport(true); scroll.setClipToPadding(false); scroll.setVerticalScrollBarEnabled(false);
        content = column();
        content.setPadding(0, ui.dp(header ? 16 : 8), 0, ui.dp(16));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        footer = column();
        footer.setPadding(0, ui.dp(4), 0, 0);
        page.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        boolean split = header && number(step) > 0
                && host.activity().getResources().getConfiguration().screenWidthDp >= WIDE_DP;
        if (split) {
            FrameLayout detail = new FrameLayout(host.activity());
            detail.addView(page, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL));
            body = new Panes(sidePanel(), detail);
            root.addView(body, new FrameLayout.LayoutParams(-1, -1));
        } else {
            body = page;
            root.addView(page, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL));
        }
    }

    /** Wide screens: the scooter, a title and all seven steps, beside the step on screen. */
    private View sidePanel() {
        ScrollView scroll = new ScrollView(host.activity());
        scroll.setFillViewport(true); scroll.setVerticalScrollBarEnabled(false);
        LinearLayout side = column();
        side.setPadding(ui.dp(20), ui.dp(12), 0, ui.dp(16));
        Hero hero = new Hero(false);
        // The step's own content carries the page; the picture here is decoration.
        hero.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        side.addView(hero, new LinearLayout.LayoutParams(-1, ui.dp(170)));
        TextView heading = ui.text("Set up your scooter", 24, true);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2); hp.topMargin = ui.dp(14);
        side.addView(heading, hp);
        boolean capture = isComputerCapture();
        int current = number(step);
        for (int n = 1; n <= STEP_NAMES.length; n++) {
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
            sp.topMargin = ui.dp(n == 1 ? 18 : 4);
            // Backup files and typed identities start at Import; the computer steps don't apply.
            side.addView(stepRow(n, current, capture || n > 4), sp);
        }
        scroll.addView(side, new ScrollView.LayoutParams(-1, -2));
        return scroll;
    }

    private View stepRow(int n, int current, boolean applies) {
        boolean now = n == current, done = applies && n < current;
        LinearLayout item = row(); item.setGravity(Gravity.CENTER_VERTICAL);
        item.setMinimumHeight(ui.dp(44));
        item.setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6));
        if (now) item.setBackground(ui.shape(Ui.withAlpha(ui.accent, ui.dark ? 40 : 26), 14, 0));
        FrameLayout badge = new FrameLayout(host.activity());
        badge.setBackground(oval(done ? ui.ok : now ? ui.accent : ui.raised));
        if (done) {
            badge.addView(ui.icon("check", 14, ui.dark ? ui.bg : Color.WHITE),
                    new FrameLayout.LayoutParams(ui.dp(14), ui.dp(14), Gravity.CENTER));
        } else {
            TextView number = ui.text(applies ? Integer.toString(n) : "–", 12.5f, true);
            number.setTextColor(now ? ui.onAccent : ui.muted); number.setGravity(Gravity.CENTER);
            badge.addView(number, new FrameLayout.LayoutParams(-1, -1));
        }
        item.addView(badge, new LinearLayout.LayoutParams(ui.dp(26), ui.dp(26)));
        TextView name = ui.text(STEP_NAMES[n - 1], 14.5f, now);
        name.setTextColor(now ? ui.text : applies ? ui.muted : ui.dim);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1); lp.leftMargin = ui.dp(12);
        item.addView(name, lp);
        // One spoken item per step; the row is information only, so it takes no keyboard focus.
        badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        item.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        item.setContentDescription("Step " + n + ", " + STEP_NAMES[n - 1] + ", "
                + (now ? "current" : done ? "done" : applies ? "to do" : "not needed"));
        if (Build.VERSION.SDK_INT >= 28) item.setScreenReaderFocusable(true);
        return item;
    }

    private View header() {
        int n = number(step);
        LinearLayout row = row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout backButton = new FrameLayout(host.activity());
        backButton.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 40)),
                oval(ui.surface), null));
        backButton.addView(ui.icon("back", 20, ui.text), new FrameLayout.LayoutParams(ui.dp(20), ui.dp(20), Gravity.CENTER));
        backButton.setContentDescription("Back"); backButton.setFocusable(true); backButton.setClickable(true);
        backButton.setOnClickListener(v -> back());
        row.addView(backButton, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        Progress progress = new Progress(n);
        LinearLayout.LayoutParams bar = new LinearLayout.LayoutParams(0, ui.dp(24), 1);
        bar.leftMargin = ui.dp(12); bar.rightMargin = ui.dp(12);
        row.addView(progress, bar);
        TextView end;
        if (step == PATH) {
            end = link(prefs.getBoolean(KEY_RERUN, false) ? "Cancel" : "Skip", () -> {
                if (prefs.getBoolean(KEY_RERUN, false)) finish(true); else confirmSkip();
            });
            end.setTextColor(ui.muted);
            end.setContentDescription(prefs.getBoolean(KEY_RERUN, false) ? "Cancel adding a scooter" : "Skip setup for now");
            end.setPadding(ui.dp(8), 0, ui.dp(4), 0);
        } else {
            end = ui.text(n > 0 ? n + "/7" : "", 12.5f, false);
            end.setTextColor(ui.muted); end.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            end.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        end.setMinWidth(ui.dp(34));
        row.addView(end, new LinearLayout.LayoutParams(-2, ui.dp(48)));
        return row;
    }

    private void heading(String text, boolean big, boolean centered) {
        title = ui.text(text, big ? 32 : 26, true);
        title.setLineSpacing(0, 1.02f);
        if (centered) title.setGravity(Gravity.CENTER_HORIZONTAL);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        content.addView(title, new LinearLayout.LayoutParams(-1, -2));
    }

    private TextView paragraph(CharSequence text, boolean centered) {
        TextView view = ui.text("", 14.5f, false);
        view.setText(text); view.setTextColor(ui.muted); view.setLineSpacing(ui.dp(3), 1);
        if (centered) view.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = ui.dp(12);
        content.addView(view, params);
        return view;
    }

    private TextView fine(String text) {
        TextView view = ui.text(text, 12, false);
        view.setTextColor(ui.muted); view.setLineSpacing(ui.dp(2), 1);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = ui.dp(14);
        content.addView(view, params);
        return view;
    }

    private TextView primary(String label, Runnable action) {
        TextView button = ui.button(label, action);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16 * ui.textScale);
        button.setMinHeight(ui.dp(56));
        footer.addView(button);
        return button;
    }

    /**
     * Puts a small spinner at the start of a footer button, hidden until shown. The button is
     * re-parented into a frame in the same footer slot; the returned view is the spinner.
     */
    private View spinnerOn(TextView button) {
        int index = footer.indexOfChild(button);
        ViewGroup.LayoutParams params = button.getLayoutParams();
        footer.removeView(button);
        FrameLayout frame = new FrameLayout(host.activity());
        frame.addView(button, new FrameLayout.LayoutParams(-1, -2));
        android.widget.ProgressBar spinner = new android.widget.ProgressBar(host.activity());
        spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(ui.dim));
        spinner.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(ui.dp(20), ui.dp(20), Gravity.START | Gravity.CENTER_VERTICAL);
        sp.setMarginStart(ui.dp(18));
        frame.addView(spinner, sp);
        spinner.setVisibility(View.GONE);
        footer.addView(frame, index, params);
        return spinner;
    }

    private TextView footerLink(String label, Runnable action) {
        TextView view = link(label, action);
        footer.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }

    private TextView link(String label, Runnable action) {
        TextView view = ui.text(label, 14.5f, false);
        view.setTextColor(ui.accent); view.setGravity(Gravity.CENTER);
        view.setMinHeight(ui.dp(48)); view.setPadding(ui.dp(8), 0, ui.dp(8), 0);
        view.setFocusable(true); view.setClickable(true);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 30)), null,
                ui.shape(Color.WHITE, 12, 0)));
        view.setOnClickListener(v -> { if (action != null) action.run(); });
        return view;
    }

    // ---------------------------------------------------------------- steps

    private void welcome() {
        scaffold(false);
        content.addView(new Hero(false), new LinearLayout.LayoutParams(-1, ui.dp(250)));
        View gap = new View(host.activity());
        content.addView(gap, new LinearLayout.LayoutParams(-1, ui.dp(18)));
        heading("Your scooter, no cloud", true, false);
        content.addView(features(), featureParams());
        primary("Get started", () -> go(PATH));
        TextView restore = ui.secondaryButton("Restore Volt backup", () -> host.dataBackup(true, null));
        restore.setTag("onboarding-restore-backup");
        restore.setContentDescription("Restore Volt backup: scooters, rides, history, dashboards and settings");
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2); rp.topMargin = ui.dp(8);
        footer.addView(restore, rp);
        footerLink("I already have an owner file", () -> {
            prefs.edit().putString(KEY_PATH, BACKUP).apply(); go(IMPORT);
        });
    }

    /** Welcome: what the app does, as four labelled icons instead of a sentence. */
    private View features() {
        LinearLayout strip = row();
        String[][] items = {{"lock", "Lock"}, {"tune", "Settings"}, {"battery", "Battery"}, {"rides", "Rides"}};
        for (int i = 0; i < items.length; i++) {
            LinearLayout tile = column(); tile.setGravity(Gravity.CENTER);
            tile.setPadding(ui.dp(4), ui.dp(12), ui.dp(4), ui.dp(10));
            tile.setBackground(ui.shape(ui.surface, 16, 0));
            tile.addView(ui.icon(items[i][0], 22, ui.accent), new LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)));
            TextView label = ui.text(items[i][1], 12.5f, false);
            label.setTextColor(ui.muted); label.setGravity(Gravity.CENTER); label.setMaxLines(1);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.topMargin = ui.dp(6);
            tile.addView(label, lp);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) tp.leftMargin = ui.dp(8);
            strip.addView(tile, tp);
        }
        // One spoken summary for the strip; it keeps the "over Bluetooth" fact the old sentence carried.
        strip.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        for (int i = 0; i < strip.getChildCount(); i++)
            strip.getChildAt(i).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        strip.setFocusable(true);
        strip.setContentDescription("Lock, settings, battery and rides, over Bluetooth");
        return strip;
    }

    private LinearLayout.LayoutParams featureParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = ui.dp(16);
        return params;
    }

    private void choosePath() {
        scaffold(true);
        heading("How would you like to set up?", false, false);
        String selected = path();
        // Wireless debugging needs Android 11; below that the Shizuku card can't be chosen.
        boolean shizukuSupported = Build.VERSION.SDK_INT >= 30;
        String[][] options = {
                {RESTORE, "download", "Restore Volt backup", "Scooters, rides, dashboards & settings", null},
                {CAPTURE, "screen", "Capture it from the NAVEE app", "Computer · 10 min", null},
                {SHIZUKU, "screen", "Use Shizuku on this phone", "No computer · Android 11+", null},
                {WEBADB, "screen", "Use Chrome and a USB cable", "Chrome + USB cable", "Preview"},
                {BACKUP, "download", "Import an owner setup file", "Scooter access only · no rides or history", null},
                {TYPED, "lock", "Enter a 12-character identity", null, null}};
        LinearLayout group = column();
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-1, -2); gp.topMargin = ui.dp(16);
        content.addView(group, gp);
        for (String[] o : options) {
            boolean disabled = SHIZUKU.equals(o[0]) && !shizukuSupported;
            boolean on = o[0].equals(selected) && !disabled;
            View card = option(o[1], o[2], o[3], o[4], on, disabled, disabled
                    ? () -> host.toast("Needs Android 11 or newer")
                    : () -> { prefs.edit().putString(KEY_PATH, o[0]).apply(); render(); });
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2); cp.bottomMargin = ui.dp(10);
            group.addView(card, cp);
        }
        primary("Continue", () -> {
            String chosen = path();
            if (RESTORE.equals(chosen)) {
                host.dataBackup(true, null);
            } else if (SHIZUKU.equals(chosen)) {
                if (!shizukuSupported) { host.toast("Needs Android 11 or newer"); return; }
                if (pendingOwnerFile().exists()) { host.toast("Finish importing your selected owner file first."); go(IMPORT); return; }
                if (!prepareProfileChange()) return;
                host.activity().startActivityForResult(new Intent(host.activity(), ShizukuSetupActivity.class), ShizukuSetupActivity.REQUEST);
            } else go(CAPTURE.equals(chosen) || WEBADB.equals(chosen) ? PREPARE : BACKUP.equals(chosen) ? IMPORT : IDENTITY);
        });
    }

    private void confirmSkip() {
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setTitle("Skip setup for now?")
                .setMessage("Add it later in More.")
                .setNegativeButton("Keep going", null)
                .setPositiveButton("Skip", (d, w) -> finish(true)).create();
        show(dialog);
    }

    private void prepare() {
        scaffold(true);
        heading("Before you start", false, false);
        String[][] items = {
                {"NAVEE app works", "Signed in and connecting"},
                {WEBADB.equals(path()) ? "A computer with Chrome or Edge" : "A computer with ADB and Python 3", null},
                {"A USB data cable", "Not charge-only"},
                {"Scooter parked and powered on", "Close any other scooter apps"}};
        LinearLayout group = column();
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-1, -2); gp.topMargin = ui.dp(16);
        content.addView(group, gp);
        for (int i = 0; i < items.length; i++) group.addView(tick("prep" + i, items[i][0], items[i][1]));
        primary("Continue", () -> go(USB));
        if (WEBADB.equals(path())) return;
        // Both links open the download directly; the helper script ships in the source bundle.
        LinearLayout links = row(); links.setGravity(Gravity.CENTER);
        TextView adb = link("Where do I get ADB?", () -> openUrl(PLATFORM_TOOLS_URL));
        adb.setContentDescription("Where do I get ADB? Opens the Platform-Tools download");
        links.addView(adb, new LinearLayout.LayoutParams(0, -2, 1));
        TextView source = link("Get source", () -> openUrl(SOURCE_URL));
        source.setContentDescription("Get source. Opens the source bundle with the capture helper");
        links.addView(source, new LinearLayout.LayoutParams(0, -2, 1));
        footer.addView(links, new LinearLayout.LayoutParams(-1, -2));
    }

    private void openUrl(String url) {
        try { host.activity().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (RuntimeException unavailable) { host.toast("No browser found"); }
    }

    private void usbDebugging() {
        scaffold(true);
        heading("Turn on USB debugging", false, false);
        Ui system = new Ui(host.activity(), !ui.dark, ui.textScale);
        LinearLayout panel = mockPanel(system);
        LinearLayout top = row(); top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(ui.dp(14), ui.dp(14), ui.dp(16), ui.dp(8));
        top.addView(system.icon("back", 18, system.text), new LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)));
        TextView about = mockText(system, "About phone", 16, true, system.text);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, -2); ap.leftMargin = ui.dp(14);
        top.addView(about, ap);
        panel.addView(top);
        panel.addView(mockSetting(system, "Android version", Build.VERSION.RELEASE, false));
        panel.addView(mockSetting(system, "Build number", Build.DISPLAY, true));
        panel.setContentDescription("Example: Settings, About phone. Tap Build number seven times.");
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, -2); pp.topMargin = ui.dp(18);
        content.addView(panel, pp);
        content.addView(numbered(1, "Settings › About phone", "Tap **Build number** seven times"));
        content.addView(numbered(2, "Settings › System › Developer options", "Turn on **USB debugging**"));
        primary("I've turned it on", () -> go(COMPUTER));
        footerLink("Open Settings", () -> openSettings(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)));
    }

    private void connectComputer() {
        if (WEBADB.equals(path())) { connectBrowser(); return; }
        scaffold(true);
        heading("Plug into your computer", false, false);
        Ui system = new Ui(host.activity(), !ui.dark, ui.textScale);
        LinearLayout panel = mockPanel(system);
        panel.setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(12));
        panel.addView(mockText(system, "Allow USB debugging?", 16.5f, true, system.text));
        TextView fingerprint = mockText(system, "The computer's RSA key fingerprint is:\n4F:2A:…:9C", 13, false, system.muted);
        fingerprint.setPadding(0, ui.dp(10), 0, ui.dp(10));
        panel.addView(fingerprint);
        LinearLayout always = row(); always.setGravity(Gravity.CENTER_VERTICAL);
        // Drawn unticked: the browser and computer steps both say to leave it unchecked.
        View box = new MockCheck(system.muted);
        always.addView(box, new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)));
        TextView label = mockText(system, "Always allow from this computer", 14, false, system.text);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1); lp.leftMargin = ui.dp(10);
        always.addView(label, lp);
        panel.addView(always);
        LinearLayout buttons = row(); buttons.setGravity(Gravity.END);
        buttons.setPadding(0, ui.dp(14), 0, 0);
        buttons.addView(mockText(system, "Cancel", 15, true, system.blue));
        TextView allow = mockText(system, "Allow", 15, true, system.blue);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-2, -2); alp.leftMargin = ui.dp(28);
        buttons.addView(allow, alp);
        panel.addView(buttons);
        panel.setContentDescription("Example: the Allow USB debugging prompt on your phone, with Always allow from this computer unticked.");
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, -2); pp.topMargin = ui.dp(18);
        content.addView(panel, pp);
        content.addView(numbered(1, "Connect your phone with the data cable", null));
        content.addView(numbered(2, "Tap **Allow** on the prompt above", null));
        content.addView(numbered(3, "`adb devices` shows **device**", null));
        primary("It shows \u201Cdevice\u201D", () -> go(HELPER));
        footerLink("It says unauthorized", () -> info("Phone says unauthorized",
                "1  Unlock your phone, unplug the cable and plug it back in.\n"
                        + "2  Tap Allow on the \u201CAllow USB debugging?\u201D prompt.\n"
                        + "3  No prompt? In Developer options, tap Revoke USB debugging authorizations, then reconnect."));
    }

    private void runHelper() {
        if (WEBADB.equals(path())) { runBrowserHelper(); return; }
        scaffold(true);
        heading("Run the capture helper", false, false);
        paragraph("On your computer, run:", false);
        Ui term = ui.dark ? ui : new Ui(host.activity(), true, ui.textScale);
        LinearLayout block = column();
        block.setBackground(ui.shape(term.bg, 16, term.raised));
        block.setPadding(ui.dp(16), ui.dp(14), ui.dp(12), ui.dp(12));
        SpannableStringBuilder line = new SpannableStringBuilder("$ " + COMMAND);
        line.setSpan(new android.text.style.ForegroundColorSpan(term.dim), 0, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView command = ui.text("", 14, false);
        command.setText(line); command.setTypeface(Typeface.MONOSPACE); command.setTextColor(term.danger);
        command.setLineSpacing(ui.dp(4), 1); command.setTextIsSelectable(true);
        command.setContentDescription("Command: " + COMMAND);
        block.addView(command, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout copy = row(); copy.setGravity(Gravity.CENTER);
        copy.setPadding(ui.dp(14), 0, ui.dp(16), 0);
        copy.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(term.accent, 50)),
                ui.shape(term.raised, 100, 0), null));
        copy.addView(term.icon("copy", 16, term.text), new LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)));
        TextView copyLabel = term.text("Copy", 14, false); copyLabel.setTypeface(term.semibold());
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2); cl.leftMargin = ui.dp(8);
        copy.addView(copyLabel, cl);
        copy.setContentDescription("Copy command"); copy.setFocusable(true); copy.setClickable(true);
        copy.setOnClickListener(v -> copyCommand());
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, ui.dp(44));
        cp.gravity = Gravity.END; cp.topMargin = ui.dp(10);
        block.addView(copy, cp);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2); bp.topMargin = ui.dp(14);
        content.addView(block, bp);
        content.addView(numbered(1, "Connect in NAVEE", null));
        content.addView(numbered(2, "Wait up to 90 s", null));
        content.addView(numbered(3, "File saves to Downloads", null));
        primary("The helper finished", () -> go(IMPORT));
        footerLink("Capture didn't work", this::troubleshooting);
    }

    private void connectBrowser() {
        scaffold(true);
        heading("Connect through your browser", false, false);
        paragraph("On your computer, open:", false);
        TextView address = ui.text(WEBADB_URL, 18, true);
        address.setTextIsSelectable(true);
        address.setPadding(0, ui.dp(12), 0, ui.dp(18));
        content.addView(address);
        content.addView(numbered(1, "Connect your phone with a USB data cable", null));
        content.addView(numbered(2, "Pick your phone in Chrome", null));
        content.addView(numbered(3, "Approve USB debugging on this phone", "Leave **Always allow from this computer** unchecked"));
        primary("The website connected", () -> go(HELPER));
        footerLink("Phone isn't listed", () -> info("Connecting in Chrome",
                "\u2022  Phone unlocked, USB debugging on\n"
                        + "\u2022  A data cable, not charge-only\n"
                        + "\u2022  Android Studio, other ADB tools and setup tabs closed\n"
                        + "\u2022  Chrome or Edge, not Safari or Firefox"));
    }

    private void runBrowserHelper() {
        scaffold(true);
        heading("Approve the capture on the website", false, false);
        paragraph("Keep the cable in until cleanup finishes.", false);
        content.addView(numbered(1, "Read the capture explanation and choose **Start**", null));
        content.addView(numbered(2, "Select your scooter in NAVEE and connect", "Up to **90 s**"));
        content.addView(numbered(3, "After cleanup, choose **Save to phone Downloads**", null));
        paragraph("This file is a scooter key. Keep it private.", false);
        primary("The file is in Downloads", () -> go(IMPORT));
        footerLink("Capture didn't work", () -> info("Browser capture",
                "\u2022  NAVEE signed in, scooter nearby\n"
                        + "\u2022  Other scooter apps closed\n"
                        + "\u2022  Stopped early? Finish cleanup in the same browser first"));
    }

    private void copyCommand() {
        try {
            ClipboardManager clipboard = host.activity().getSystemService(ClipboardManager.class);
            if (clipboard == null) throw new IllegalStateException();
            clipboard.setPrimaryClip(ClipData.newPlainText("Capture command", COMMAND));
            // Android 13 and newer confirm clipboard writes themselves.
            if (Build.VERSION.SDK_INT < 33) host.toast("Command copied.");
        } catch (RuntimeException e) { host.toast("Copy failed"); }
    }

    private void importFile() {
        scaffold(true);
        heading(SHIZUKU.equals(path()) ? "Your owner setup" : "Import your owner file", false, false);
        String mode = importMode();
        if (mode.equals("choose") || mode.equals("loading")) {
            paragraph("Pick navee-owner-setup.json. This restores scooter access only, not rides or other app data.", false);
            LinearLayout card = fileCard(mode.equals("loading") ? "Loading your owner profile…" : "No file chosen yet", null, false);
            LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2); fp.topMargin = ui.dp(18);
            content.addView(card, fp);
            if (prefs.getBoolean(KEY_IMPORT_FAILED, false)) {
                TextView error = fine("Not an owner file.");
                error.setTextColor(ui.danger);
            }
            fine("This file unlocks your scooter. Keep it private.");
            TextView choose = primary("Choose owner file", this::pickFile);
            choose.setEnabled(!mode.equals("loading"));
            footerLink("Enter the identity instead", () -> { prefs.edit().putString(KEY_PATH, TYPED).apply(); go(IDENTITY); });
            footerLink("Restore Volt backup instead", () -> host.dataBackup(true, null));
            return;
        }
        boolean applied = mode.equals("applied");
        ScooterProfiles.Profile p = applied ? find(profiles(), prefs.getString(KEY_PROFILE, "")) : null;
        String[] summary = applied && p != null ? new String[] {p.name, p.role == 1 ? "shared role" : "owner role"} : pendingSummary();
        // One success card: the lock glyph says it is held privately on this phone.
        LinearLayout found = row(); found.setGravity(Gravity.CENTER_VERTICAL);
        found.setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14));
        found.setBackground(ui.shape(blend(ui.surface, ui.ok, ui.dark ? 0.10f : 0.08f), 18, Ui.withAlpha(ui.ok, ui.dark ? 90 : 110)));
        FrameLayout lock = new FrameLayout(host.activity());
        lock.setBackground(oval(ui.ok));
        lock.addView(ui.icon("lock", 18, ui.dark ? ui.bg : ui.onAccent), new FrameLayout.LayoutParams(ui.dp(18), ui.dp(18), Gravity.CENTER));
        lock.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        found.addView(lock, new LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)));
        LinearLayout words = column();
        words.addView(ui.text("Owner identity found", 15.5f, true));
        TextView detail = ui.caption(summary[0] + " · " + summary[1]);
        detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f * ui.textScale); detail.setPadding(0, ui.dp(3), 0, 0);
        words.addView(detail);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, -2, 1); wp.leftMargin = ui.dp(12);
        found.addView(words, wp);
        found.setFocusable(true);
        found.setContentDescription("Owner identity found, stored privately on this phone. " + detail.getText());
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-1, -2); gp.topMargin = ui.dp(18);
        content.addView(found, gp);
        // A re-picked file that failed keeps this identity; say so here too (no toast during setup).
        if (prefs.getBoolean(KEY_IMPORT_FAILED, false)) fine("Not an owner file.").setTextColor(ui.danger);
        nameField = nameInput(prefs.getString(KEY_PENDING_NAME, summary[0]));
        fine(SHIZUKU.equals(path()) ? "Stored privately. Revoke Shizuku now." : "This file unlocks your scooter. Keep it private.");
        // The picked file is still in Downloads after import; offer to remove that copy right here.
        if (!SHIZUKU.equals(path()) && host.activity() instanceof MainActivity
                && ((MainActivity) host.activity()).hasPickedOwnerFile()) {
            TextView delete = link("Delete Downloads copy", () -> {
                boolean deleted = ((MainActivity) host.activity()).deletePickedOwnerFile();
                host.toast(deleted ? "Downloads copy deleted" : "Couldn't delete · remove it in Files");
                if (deleted) render();
            });
            delete.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            content.addView(delete, new LinearLayout.LayoutParams(-2, -2));
        }
        primary("Save and continue", () -> {
            String name = nameField.getText().toString().trim();
            if (applied) {
                ScooterProfiles store = profiles();
                ScooterProfiles.Profile target = find(store, prefs.getString(KEY_PROFILE, ""));
                if (store == null || target == null) { render(); return; }
                if (!name.isEmpty() && !name.equals(target.name)) {
                    if (!prepareProfileChange()) return;
                    target.name = name; target.nameDefault = false; store.update(target);
                }
                store.select(target.id);
            } else prefs.edit().putString(KEY_PENDING_NAME, name).apply();
            hideKeyboard();
            afterProfile();
        });
        if (SHIZUKU.equals(path())) footerLink("Open Shizuku", this::openShizuku);
        else if (applied) footerLink("Choose a different file", this::pickFile);
    }

    /** Volt's access is revoked inside Shizuku (Authorized applications); open it there. */
    private void openShizuku() {
        Intent launch = host.activity().getPackageManager().getLaunchIntentForPackage(SHIZUKU_PACKAGE);
        try {
            if (launch == null) throw new IllegalStateException();
            host.activity().startActivity(launch);
        } catch (RuntimeException unavailable) { host.toast("Shizuku isn't installed"); }
    }

    private void pickFile() {
        ScooterService service = host.service();
        if (service != null && service.isRecording()) { host.toast("Stop recording first"); return; }
        prefs.edit().putBoolean(KEY_IMPORT_FAILED, false).apply();
        host.importOwner();
    }

    private void enterIdentity() {
        scaffold(true);
        heading("Enter owner identity", false, false);
        paragraph("Not a password.", false);
        FrameLayout boxes = new FrameLayout(host.activity());
        HexBoxes display = new HexBoxes();
        boxes.addView(display, new FrameLayout.LayoutParams(-1, -1));
        EditText field = new EditText(host.activity());
        identityField = field;
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        field.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_ACTION_NEXT);
        field.setFilters(new InputFilter[] {new InputFilter.LengthFilter(1048576)});
        field.setSingleLine(true);
        field.setBackground(null); field.setTextColor(Color.TRANSPARENT); field.setHintTextColor(Color.TRANSPARENT);
        field.setHighlightColor(Color.TRANSPARENT); field.setCursorVisible(false);
        field.setHint("Owner identity, 12 characters, or a private backup");
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        field.setOnFocusChangeListener((v, focus) -> display.invalidate());
        boxes.addView(field, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, ui.dp(60)); bp.topMargin = ui.dp(18);
        content.addView(boxes, bp);
        TextView status = ui.caption("");
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f * ui.textScale);
        status.setPadding(0, ui.dp(8), 0, 0);
        content.addView(status);
        nameField = nameInput("");
        TextView save = primary("Save and continue", this::saveIdentity);
        // While the local service starts, Save waits with a spinner instead of answering a tap with a toast.
        View starting = spinnerOn(save);
        Runnable update = () -> {
            String raw = field.getText().toString();
            String compact = raw.replaceAll("[\\s:\\-]", "").toUpperCase(Locale.ROOT);
            OwnerIdentityParser.Result parsed = parseIdentity(raw);
            boolean typing = compact.matches("[0-9A-F]{0,12}");
            display.set(typing ? compact : parsed != null ? parsed.identity : "", !typing && parsed == null && !raw.trim().isEmpty());
            boolean storeReady = profiles() != null;
            save.setEnabled(parsed != null && storeReady);
            starting.setVisibility(storeReady ? View.GONE : View.VISIBLE);
            save.setContentDescription(storeReady ? "Save and continue" : "Save and continue, starting");
            if (parsed != null && !typing) {
                status.setText("Private backup recognised · " + (parsed.role == 1 ? "shared role" : "owner role"));
                status.setTextColor(ui.ok);
            } else if (!typing && !raw.trim().isEmpty()) {
                status.setText("Only 0–9 and A–F.");
                status.setTextColor(ui.danger);
            } else status.setText("");
            status.setVisibility(status.getText().length() == 0 ? View.GONE : View.VISIBLE);
        };
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { update.run(); }
        });
        updaters.add(update);
        update.run();
    }

    /** Same parser as More › Add with owner identity; separators in a typed code are ignored. */
    private static OwnerIdentityParser.Result parseIdentity(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String compact = raw.replaceAll("[\\s:\\-]", "");
        try {
            if (compact.matches("(?i)[0-9a-f]{12}")) return OwnerIdentityParser.parse(compact);
            if (compact.matches("(?i)[0-9a-f]*")) return null;
            return OwnerIdentityParser.parse(raw);
        } catch (IllegalArgumentException invalid) { return null; }
    }

    private void saveIdentity() {
        if (identityField == null) return;
        OwnerIdentityParser.Result parsed = parseIdentity(identityField.getText().toString());
        if (parsed == null) return;
        ScooterProfiles store = profiles();
        if (store == null) return;
        if (!prepareProfileChange()) return;
        // A typed identity supersedes an owner file picked earlier in this flow but not yet
        // applied; otherwise granting Nearby devices later would import and select that file.
        File abandoned = pendingOwnerFile();
        if (abandoned.isFile() && !abandoned.delete()) {
            host.toast("Couldn't clear old file. Retry.");
            return;
        }
        // Its pending display name goes too, so the typed identity keeps the name entered here.
        prefs.edit().remove(KEY_PENDING_NAME).apply();
        String name = nameField == null ? "" : nameField.getText().toString();
        try {
            // Replace only a profile this flow created earlier, never one the owner already had.
            ScooterProfiles.Profile earlier = find(store, prefs.getString(KEY_CREATED, ""));
            ScooterProfiles.Profile profile;
            if (earlier != null && earlier.identity.equalsIgnoreCase(parsed.identity) && earlier.role == parsed.role) {
                if (!name.trim().isEmpty()) { earlier.name = name.trim(); earlier.nameDefault = false; }
                store.update(earlier); store.select(earlier.id); profile = earlier;
            } else {
                if (earlier != null) store.remove(earlier.id);
                profile = store.create(name, parsed.identity, parsed.role);
            }
            prefs.edit().putString(KEY_PROFILE, profile.id).putString(KEY_CREATED, profile.id).apply();
        } catch (IllegalArgumentException invalid) { host.toast(invalid.getMessage()); return; }
        identityField.setText("");
        hideKeyboard();
        afterProfile();
    }

    private void nearby() {
        scaffold(true);
        heading("Allow Nearby devices", false, false);
        FrameLayout tile = new FrameLayout(host.activity());
        tile.setBackground(ui.shape(Ui.withAlpha(ui.accent, ui.dark ? 38 : 30), 28, 0));
        tile.addView(ui.icon("bluetooth", 40, ui.accent), new FrameLayout.LayoutParams(ui.dp(40), ui.dp(40), Gravity.CENTER));
        tile.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ui.dp(84), ui.dp(84));
        tp.gravity = Gravity.CENTER_HORIZONTAL; tp.topMargin = ui.dp(26);
        content.addView(tile, tp);
        TextView body = paragraph("Bluetooth only. No servers.", true);
        ((LinearLayout.LayoutParams) body.getLayoutParams()).topMargin = ui.dp(18);
        primary("Allow", () -> {
            if (hasPermission()) { toConnect(); return; }
            prefs.edit().putBoolean(KEY_ASKED_NEARBY, true).apply();
            host.requestBluetooth();
            refresh();
        });
        TextView settings = footerLink("Open app settings", () -> openSettings(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", host.activity().getPackageName(), null))));
        updaters.add(() -> settings.setVisibility(prefs.getBoolean(KEY_ASKED_NEARBY, false) && !hasPermission()
                ? View.VISIBLE : View.GONE));
    }

    private void connectScooter() {
        scaffold(true);
        heading("Find your scooter", false, false);
        LinearLayout list = column();
        list.setBackground(ui.shape(ui.surface, 20, 0));
        list.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(4));
        String[] labels = {"Found your scooter", "Bluetooth connected", "Checking owner identity",
                "Saving this scooter"};
        StateDot[] dots = new StateDot[labels.length];
        TextView[] texts = new TextView[labels.length];
        LinearLayout[] rows = new LinearLayout[labels.length];
        for (int i = 0; i < labels.length; i++) {
            if (i > 0) ui.divider(list);
            LinearLayout r = row(); r.setGravity(Gravity.CENTER_VERTICAL); r.setMinimumHeight(ui.dp(56));
            r.setPadding(0, ui.dp(10), 0, ui.dp(10));
            dots[i] = new StateDot();
            r.addView(dots[i], new LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)));
            texts[i] = ui.text(labels[i], 15, false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1); lp.leftMargin = ui.dp(14);
            r.addView(texts[i], lp);
            r.setFocusable(true);
            rows[i] = r; list.addView(r);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = ui.dp(18);
        content.addView(list, lp);
        // Status line: a spinner while the service starts, otherwise only problems are shown;
        // the checklist above already shows progress.
        LinearLayout statusRow = row(); statusRow.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ProgressBar starting = new android.widget.ProgressBar(host.activity());
        starting.setIndeterminate(true);
        starting.setIndeterminateTintList(ColorStateList.valueOf(ui.accent));
        starting.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        statusRow.addView(starting, new LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)));
        TextView status = ui.text("", 12, false);
        status.setTextColor(ui.muted); status.setLineSpacing(ui.dp(2), 1);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(0, -2, 1); stp.leftMargin = ui.dp(8);
        statusRow.addView(status, stp);
        LinearLayout.LayoutParams srp = new LinearLayout.LayoutParams(-1, -2); srp.topMargin = ui.dp(14);
        content.addView(statusRow, srp);
        TextView connect = primary("Connect", () -> {
            // Without Nearby devices the service can't start, so the main button asks for it.
            if (host.service() == null && !hasPermission()) { host.requestBluetooth(); return; }
            connectNow();
        });
        TextView cancel = footerLink("Cancel", this::stopConnecting);
        updaters.add(() -> {
            ScooterService service = host.service();
            ScooterProfiles.Profile active = service == null ? null : service.profiles.active();
            boolean configured = active != null && active.configured();
            BleController.State state = service == null ? BleController.State.DISCONNECTED : service.ble.state();
            boolean ready = service != null && service.ble.ready(), linked = service != null && service.ble.connected();
            int progress = ready ? 4 : linked ? 3 : state == BleController.State.AUTHENTICATING ? 2
                    : state == BleController.State.CONNECTING ? 1 : 0;
            boolean busy = !ready && (linked || state != BleController.State.DISCONNECTED);
            boolean waitingToStart = prefs.getBoolean(KEY_CONNECT, false);
            title.setText(progress >= 1 && active != null ? "Connecting to " + active.name : "Find your scooter");
            for (int i = 0; i < rows.length; i++) {
                int mode = i < progress ? StateDot.DONE : i == progress && (busy || waitingToStart) ? StateDot.ACTIVE : StateDot.WAITING;
                dots[i].setMode(mode);
                String label = i == 0 && progress == 0 && (busy || waitingToStart) ? "Looking for your scooter" : labels[i];
                texts[i].setText(label);
                texts[i].setTextColor(mode == StateDot.WAITING ? ui.dim : ui.text);
                texts[i].setTypeface(mode == StateDot.ACTIVE ? ui.semibold() : ui.regular());
                rows[i].setContentDescription(label + ", " + (mode == StateDot.DONE ? "done" : mode == StateDot.ACTIVE ? "in progress" : "not yet"));
            }
            boolean needsNearby = service == null && !hasPermission();
            boolean startingService = service == null && !needsNearby;
            String message;
            if (service == null) message = needsNearby ? "" : "Starting…";
            else if (!configured || pendingOwnerFile().isFile()) message = "Waiting for your owner profile…";
            else if (state == BleController.State.SCANNING)
                message = ""; // The active checklist row already shows that discovery is in progress.
            else if ((state == BleController.State.DISCONNECTED || state == BleController.State.WAITING)
                    && prefs.getBoolean(KEY_TRIED, false)) {
                // After an attempt, the controller's message is the problem to show (scan timeout, failure).
                String problem = service.ble.message();
                boolean nothingFound = problem != null && problem.startsWith("No scooter found");
                message = nothingFound ? "Is it on? Is NAVEE closed?" : problem;
            } else message = "";
            status.setText(message == null ? "" : message);
            starting.setVisibility(startingService ? View.VISIBLE : View.GONE);
            statusRow.setVisibility(status.getText().length() == 0 ? View.GONE : View.VISIBLE);
            connect.setText(needsNearby ? "Allow Nearby devices" : prefs.getBoolean(KEY_TRIED, false) ? "Try again" : "Connect");
            connect.setContentDescription(connect.getText());
            connect.setEnabled(needsNearby || (service != null && configured && !pendingOwnerFile().isFile()));
            connect.setVisibility(busy || waitingToStart ? View.GONE : View.VISIBLE);
            cancel.setVisibility(busy || waitingToStart ? View.VISIBLE : View.GONE);
        });
    }

    private void connectNow() {
        ScooterService service = host.service();
        ScooterProfiles.Profile active = service == null ? null : service.profiles.active();
        if (active == null || !active.configured()) return;
        prefs.edit().putBoolean(KEY_TRIED, true).remove(KEY_CONNECT).apply();
        host.connect();
        refresh();
    }

    private void done() {
        scaffold(false);
        content.addView(new Hero(true), new LinearLayout.LayoutParams(-1, ui.dp(250)));
        View gap = new View(host.activity());
        content.addView(gap, new LinearLayout.LayoutParams(-1, ui.dp(18)));
        heading("You're connected", true, true);
        ScooterProfiles.Profile p = onboardingProfile();
        paragraph((p == null ? "Your scooter" : p.name) + " is ready.", true);
        LinearLayout group = column();
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-1, -2); gp.topMargin = ui.dp(18);
        content.addView(group, gp);
        TextView backup = ui.secondaryButton("Back up Volt", () -> host.dataBackup(false, null));
        backup.setTag("onboarding-create-backup");
        ui.decorate(backup, "shield");
        backup.setMinHeight(ui.dp(54));
        backup.setContentDescription("Back up Volt: scooter access and all saved app data");
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2); bp.bottomMargin = ui.dp(10);
        if (p != null && p.configured()) group.addView(backup, bp);
        // The closing reminder depends on what this setup turned on.
        if (isComputerCapture()) group.addView(tick("done1", "Turn off USB debugging", "You don't need the computer any more"));
        else if (SHIZUKU.equals(path())) {
            TextView revoke = ui.secondaryButton("Revoke Shizuku", this::openShizuku);
            ui.decorate(revoke, "lock");
            revoke.setMinHeight(ui.dp(54));
            revoke.setContentDescription("Revoke Shizuku. Opens Shizuku to remove Volt's access");
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2); rp.bottomMargin = ui.dp(10);
            group.addView(revoke, rp);
        }
        primary("Go to Home", () -> finish(false));
    }

    // ---------------------------------------------------------------- troubleshooting sheet

    private void troubleshooting() {
        if (sheet != null) return;
        FrameLayout overlay = new FrameLayout(host.activity());
        View scrim = new View(host.activity());
        scrim.setBackgroundColor(Ui.withAlpha(Color.BLACK, ui.dark ? 150 : 110));
        scrim.setOnClickListener(v -> closeSheet());
        scrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        overlay.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout panel = new LinearLayout(host.activity()) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int width = MeasureSpec.getSize(widthSpec), max = ui.dp(MAX_WIDTH_DP);
                if (MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED && width > max)
                    widthSpec = MeasureSpec.makeMeasureSpec(max, MeasureSpec.EXACTLY);
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClickable(true);
        GradientDrawable fill = new GradientDrawable(); fill.setColor(ui.surface);
        float r = ui.dp(26); fill.setCornerRadii(new float[] {r, r, r, r, 0, 0, 0, 0});
        fill.setStroke(ui.dp(1), ui.raised);
        panel.setBackground(fill);
        panel.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(16));
        View grab = new View(host.activity()); grab.setBackground(ui.shape(ui.outline, 2, 0));
        LinearLayout.LayoutParams grp = new LinearLayout.LayoutParams(ui.dp(36), ui.dp(4)); grp.gravity = Gravity.CENTER_HORIZONTAL;
        panel.addView(grab, grp);
        LinearLayout top = row(); top.setGravity(Gravity.CENTER_VERTICAL); top.setPadding(0, ui.dp(8), 0, ui.dp(8));
        TextView heading = ui.text("Capture didn't work", 21, true);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        top.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        FrameLayout close = new FrameLayout(host.activity());
        close.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 40)), oval(ui.raised), null));
        close.addView(ui.icon("close", 18, ui.text), new FrameLayout.LayoutParams(ui.dp(18), ui.dp(18), Gravity.CENTER));
        close.setContentDescription("Close"); close.setFocusable(true); close.setClickable(true);
        close.setOnClickListener(v -> closeSheet());
        top.addView(close, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        panel.addView(top);
        LinearLayout group = column();
        group.setBackground(ui.shape(ui.raised, 18, 0));
        group.setPadding(ui.dp(14), ui.dp(2), ui.dp(14), ui.dp(2));
        String[][] fixes = {
                {"screen", "Phone not listed as device", "Approve prompt \u00B7 use data cable"},
                {"scooter", "NAVEE didn't connect", "Check NAVEE connects alone"},
                {"clock", "Helper timed out", "Close other apps, rerun"},
                {"file", "File already exists", "Add --output newname"}};
        for (int i = 0; i < fixes.length; i++) {
            if (i > 0) { View line = new View(host.activity()); line.setBackgroundColor(ui.outline); group.addView(line, new LinearLayout.LayoutParams(-1, ui.dp(1))); }
            LinearLayout item = row(); item.setGravity(Gravity.CENTER_VERTICAL); item.setPadding(0, ui.dp(12), 0, ui.dp(12));
            FrameLayout square = new FrameLayout(host.activity());
            square.setBackground(ui.shape(ui.surface, 11, 0));
            View glyph = fixes[i][0].equals("file") ? new DocIcon(ui.accent) : ui.icon(fixes[i][0], 20, ui.accent);
            square.addView(glyph, new FrameLayout.LayoutParams(ui.dp(20), ui.dp(20), Gravity.CENTER));
            item.addView(square, new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
            LinearLayout words = column();
            words.addView(ui.text(fixes[i][1], 15, false));
            TextView detail = ui.caption(fixes[i][2]); detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f * ui.textScale);
            detail.setPadding(0, ui.dp(3), 0, 0); words.addView(detail);
            LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, -2, 1); wp.leftMargin = ui.dp(14);
            item.addView(words, wp);
            item.setFocusable(true); item.setContentDescription(fixes[i][1] + ". " + fixes[i][2]);
            group.addView(item);
        }
        ScrollView scroll = new ScrollView(host.activity());
        scroll.addView(group, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2, 1);
        panel.addView(scroll, sp);
        TextView again = ui.secondaryButton("Try again", this::closeSheet);
        again.setMinHeight(ui.dp(54));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, -2); ap.topMargin = ui.dp(12);
        panel.addView(again, ap);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        pp.topMargin = ui.dp(64);
        overlay.addView(panel, pp);
        if (body != null) body.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        if (Build.VERSION.SDK_INT >= 28) overlay.setAccessibilityPaneTitle("Capture didn't work");
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        sheet = overlay;
        heading.post(() -> heading.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null));
    }

    private void closeSheet() {
        if (sheet == null) return;
        root.removeView(sheet); sheet = null;
        if (body != null) body.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        if (title != null) title.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
    }

    // ---------------------------------------------------------------- pieces

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(host.activity());
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(host.activity());
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }

    private GradientDrawable oval(int color) {
        GradientDrawable d = new GradientDrawable(); d.setShape(GradientDrawable.OVAL); d.setColor(color); return d;
    }

    private static int blend(int base, int over, float amount) {
        float a = Math.max(0, Math.min(1, amount));
        int r = Math.round(Color.red(base) * (1 - a) + Color.red(over) * a);
        int g = Math.round(Color.green(base) * (1 - a) + Color.green(over) * a);
        int b = Math.round(Color.blue(base) * (1 - a) + Color.blue(over) * a);
        return Color.rgb(r, g, b);
    }

    /**
     * Selectable path card: red outline and a filled radio ring when chosen. An optional status chip
     * (e.g. Preview) sits beside the title; a disabled card is dimmed and explains itself on tap.
     */
    private View option(String icon, String heading, String detail, String chip, boolean on, boolean disabled, Runnable action) {
        LinearLayout card = row(); card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14));
        card.setMinimumHeight(ui.dp(76));
        GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(ui.dp(20));
        fill.setColor(on ? blend(ui.surface, ui.accent, ui.dark ? 0.09f : 0.06f) : ui.surface);
        fill.setStroke(ui.dp(1.5f), on ? ui.accent : ui.surface);
        card.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 30)), fill, null));
        FrameLayout square = new FrameLayout(host.activity());
        square.setBackground(ui.shape(ui.dark ? ui.bg : ui.raised, 13, 0));
        square.addView(ui.icon(icon, 22, ui.accent), new FrameLayout.LayoutParams(ui.dp(22), ui.dp(22), Gravity.CENTER));
        card.addView(square, new LinearLayout.LayoutParams(ui.dp(46), ui.dp(46)));
        LinearLayout words = column();
        TextView name = ui.text(heading, 16, true);
        if (chip == null) words.addView(name);
        else {
            LinearLayout top = row(); top.setGravity(Gravity.CENTER_VERTICAL);
            top.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            TextView tag = ui.text(chip, 11.5f, false);
            tag.setTypeface(ui.semibold()); tag.setTextColor(ui.orange);
            tag.setBackground(ui.shape(Ui.withAlpha(ui.orange, ui.dark ? 40 : 30), 100, 0));
            tag.setPadding(ui.dp(9), ui.dp(3), ui.dp(9), ui.dp(3));
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2); tp.leftMargin = ui.dp(8);
            top.addView(tag, tp);
            words.addView(top);
        }
        if (detail != null) {
            TextView sub = ui.caption(detail); sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f * ui.textScale);
            sub.setPadding(0, ui.dp(4), 0, 0); words.addView(sub);
        }
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, -2, 1);
        wp.leftMargin = ui.dp(14); wp.rightMargin = ui.dp(10);
        card.addView(words, wp);
        card.addView(new Radio(on), new LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)));
        if (disabled) {
            // Still focusable and tappable so it can say why; dimmed so it reads as unavailable.
            square.setAlpha(.45f); words.setAlpha(.45f);
            card.getChildAt(card.getChildCount() - 1).setAlpha(.45f);
        }
        card.setFocusable(true); card.setClickable(true);
        card.setContentDescription(heading + (chip == null ? "" : ", " + chip) + (detail == null ? "" : ". " + detail)
                + (disabled ? ". Needs Android 11 or newer" : ""));
        card.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.RadioButton"); info.setCheckable(true); info.setChecked(on);
            }
        });
        card.setOnClickListener(v -> action.run());
        return card;
    }

    /** Tickable reminder row; its state is kept with the setup progress. */
    private View tick(String key, String heading, String detail) {
        LinearLayout card = row(); card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14));
        card.setMinimumHeight(ui.dp(64));
        card.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 30)),
                ui.shape(ui.surface, 18, 0), null));
        Box box = new Box();
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ui.dp(24), ui.dp(24));
        card.addView(box, bp);
        LinearLayout words = column();
        TextView name = ui.text(heading, 15.5f, false);
        words.addView(name);
        if (detail != null) {
            TextView sub = ui.caption(detail); sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f * ui.textScale);
            sub.setPadding(0, ui.dp(3), 0, 0); words.addView(sub);
        }
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, -2, 1); wp.leftMargin = ui.dp(14);
        card.addView(words, wp);
        Runnable style = () -> {
            boolean on = prefs.getBoolean(KEY_TICK + key, false);
            box.on = on; box.invalidate();
            name.setTextColor(on ? ui.muted : ui.text);
            if (Build.VERSION.SDK_INT >= 30) card.setStateDescription(on ? "Checked" : "Not checked");
        };
        card.setFocusable(true); card.setClickable(true);
        card.setContentDescription(detail == null ? heading : heading + ". " + detail);
        card.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.CheckBox"); info.setCheckable(true);
                info.setChecked(prefs.getBoolean(KEY_TICK + key, false));
            }
        });
        card.setOnClickListener(v -> {
            prefs.edit().putBoolean(KEY_TICK + key, !prefs.getBoolean(KEY_TICK + key, false)).apply();
            style.run();
        });
        style.run();
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2); cp.bottomMargin = ui.dp(10);
        card.setLayoutParams(cp);
        return card;
    }

    private View numbered(int n, String heading, String detail) {
        LinearLayout item = row(); item.setGravity(Gravity.TOP);
        TextView number = ui.text(Integer.toString(n), 13.5f, true);
        number.setGravity(Gravity.CENTER); number.setBackground(oval(ui.raised));
        number.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        item.addView(number, new LinearLayout.LayoutParams(ui.dp(30), ui.dp(30)));
        LinearLayout words = column();
        words.setPadding(0, ui.dp(4), 0, 0);
        TextView name = ui.text("", 15.5f, false); name.setText(rich(heading));
        words.addView(name);
        if (detail != null) {
            TextView sub = ui.text("", 13, false); sub.setText(rich(detail)); sub.setTextColor(ui.muted);
            sub.setPadding(0, ui.dp(3), 0, 0); words.addView(sub);
        }
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, -2, 1); wp.leftMargin = ui.dp(14);
        item.addView(words, wp);
        item.setFocusable(true);
        item.setContentDescription("Step " + n + ". " + heading.replace("**", "").replace("`", "")
                + (detail == null ? "" : ". " + detail.replace("**", "")));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, -2); ip.topMargin = ui.dp(16);
        item.setLayoutParams(ip);
        return item;
    }

    private LinearLayout fileCard(String heading, String detail, boolean ok) {
        LinearLayout card = row(); card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(ui.dp(14), ui.dp(14), ui.dp(16), ui.dp(14));
        card.setBackground(ui.shape(ui.surface, 18, 0));
        FrameLayout icon = new FrameLayout(host.activity());
        icon.setBackground(ui.shape(ui.raised, 10, 0));
        icon.addView(new DocIcon(ui.text), new FrameLayout.LayoutParams(ui.dp(22), ui.dp(22), Gravity.CENTER));
        card.addView(icon, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(50)));
        LinearLayout words = column();
        words.addView(ui.text(heading, 15.5f, true));
        if (detail != null) {
            TextView sub = ui.caption(detail); sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f * ui.textScale);
            sub.setPadding(0, ui.dp(3), 0, 0); words.addView(sub);
        }
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, -2, 1); wp.leftMargin = ui.dp(14);
        card.addView(words, wp);
        if (ok) card.addView(ui.icon("check", 22, ui.ok), new LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)));
        card.setFocusable(true);
        card.setContentDescription(detail == null ? heading : heading + ". " + detail);
        return card;
    }

    private EditText nameInput(String value) {
        TextView label = ui.text("Name your scooter", 12.5f, false);
        label.setTextColor(ui.muted);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = ui.dp(20);
        content.addView(label, lp);
        EditText edit = new EditText(host.activity());
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        edit.setImeOptions(EditorInfo.IME_ACTION_DONE);
        edit.setFilters(new InputFilter[] {new InputFilter.LengthFilter(60)});
        edit.setSingleLine(true);
        edit.setText(value == null ? "" : value);
        edit.setTextColor(ui.text); edit.setHintTextColor(ui.muted); edit.setHint("Scooter name");
        edit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16 * ui.textScale); edit.setTypeface(ui.regular());
        edit.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14));
        edit.setMinHeight(ui.dp(54));
        edit.setBackground(ui.shape(ui.dark ? ui.bg : ui.surface, 14, ui.outline));
        edit.setContentDescription("Name your scooter");
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-1, -2); ep.topMargin = ui.dp(8);
        content.addView(edit, ep);
        return edit;
    }

    private LinearLayout mockPanel(Ui system) {
        LinearLayout panel = column();
        panel.setBackground(ui.shape(system.bg, 20, ui.dark ? 0 : ui.outline));
        panel.setClipToOutline(true);
        panel.setFocusable(true);
        panel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        return panel;
    }

    private TextView mockText(Ui system, String text, float sp, boolean bold, int color) {
        TextView view = new TextView(host.activity());
        view.setText(text); view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * ui.textScale);
        view.setTypeface(Typeface.create("sans-serif" + (bold ? "-medium" : ""), Typeface.NORMAL));
        view.setLineSpacing(ui.dp(2), 1);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    private View mockSetting(Ui system, String name, String value, boolean highlighted) {
        FrameLayout item = new FrameLayout(host.activity());
        item.setPadding(ui.dp(18), ui.dp(12), ui.dp(16), ui.dp(12));
        if (highlighted) item.setBackgroundColor(system.raised);
        LinearLayout words = column();
        words.addView(mockText(system, name, 15.5f, false, system.text));
        TextView sub = mockText(system, value == null || value.isEmpty() ? "—" : value, 13, false, system.muted);
        sub.setMaxLines(1); sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        sub.setPadding(0, ui.dp(3), highlighted ? ui.dp(80) : 0, 0);
        words.addView(sub);
        item.addView(words, new FrameLayout.LayoutParams(-1, -2));
        if (highlighted) {
            TextView tap = new TextView(host.activity());
            tap.setText("×7"); tap.setTextColor(ui.onAccent); tap.setTypeface(ui.bold());
            tap.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14 * ui.textScale); tap.setGravity(Gravity.CENTER);
            GradientDrawable pill = new GradientDrawable(); pill.setColor(ui.accent); pill.setCornerRadius(ui.dp(100));
            pill.setStroke(ui.dp(5), Ui.withAlpha(ui.accent, 70));
            tap.setBackground(pill);
            tap.setPadding(ui.dp(14), ui.dp(5), ui.dp(14), ui.dp(5));
            tap.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            item.addView(tap, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));
        }
        item.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        return item;
    }

    private CharSequence rich(String source) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        int i = 0;
        while (i < source.length()) {
            if (source.startsWith("**", i)) {
                int end = source.indexOf("**", i + 2);
                if (end > 0) {
                    int start = out.length(); out.append(source, i + 2, end);
                    out.setSpan(new FontSpan(ui.semibold(), 0), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 2; continue;
                }
            }
            if (source.charAt(i) == '`') {
                int end = source.indexOf('`', i + 1);
                if (end > 0) {
                    int start = out.length(); out.append('\u00A0').append(source, i + 1, end).append('\u00A0');
                    out.setSpan(new FontSpan(Typeface.MONOSPACE, 0.9f), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new BackgroundColorSpan(ui.raised), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1; continue;
                }
            }
            out.append(source.charAt(i)); i++;
        }
        return out;
    }

    private void info(String heading, String message) {
        show(new AlertDialog.Builder(host.activity()).setTitle(heading).setMessage(message)
                .setPositiveButton("OK", null).create());
    }

    private void show(AlertDialog dialog) {
        if (closed) return;
        dialogs.add(dialog);
        dialog.setOnDismissListener(d -> dialogs.remove(dialog));
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(ui.shape(ui.surface, 22, ui.outline));
        Motion.present(dialog, Motion.DIALOG);
        dialog.show();
        if (dialog.getButton(AlertDialog.BUTTON_POSITIVE) != null) dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ui.accent);
        if (dialog.getButton(AlertDialog.BUTTON_NEGATIVE) != null) dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(ui.muted);
    }

    private void openSettings(Intent intent) {
        try { host.activity().startActivity(intent); }
        catch (RuntimeException unavailable) {
            try { host.activity().startActivity(new Intent(Settings.ACTION_SETTINGS)); }
            catch (RuntimeException none) { host.toast("Open your phone's Settings app to continue."); }
        }
    }

    private void hideKeyboard() {
        View focus = host.activity().getCurrentFocus();
        android.view.inputmethod.InputMethodManager input =
                host.activity().getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (focus != null && input != null) input.hideSoftInputFromWindow(focus.getWindowToken(), 0);
    }

    private static boolean motionEnabled() {
        return Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled();
    }

    private static final class FontSpan extends MetricAffectingSpan {
        private final Typeface face; private final float scale;
        FontSpan(Typeface face, float scale) { this.face = face; this.scale = scale; }
        @Override public void updateDrawState(TextPaint paint) { apply(paint); }
        @Override public void updateMeasureState(TextPaint paint) { apply(paint); }
        private void apply(TextPaint paint) { paint.setTypeface(face); if (scale > 0) paint.setTextSize(paint.getTextSize() * scale); }
    }

    // ---------------------------------------------------------------- custom views

    /** Seven segments; the current one is half filled. */
    private final class Progress extends View {
        private final int current; private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Progress(int current) {
            super(host.activity()); this.current = current;
            setContentDescription(current > 0 ? "Step " + current + " of 7" : "Setup, choose a path");
        }
        @Override protected void onDraw(Canvas canvas) {
            float gap = ui.dp(4), h = ui.dp(5), w = (getWidth() - gap * 6) / 7f, top = (getHeight() - h) / 2f;
            boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            for (int k = 1; k <= 7; k++) {
                float left = rtl ? getWidth() - k * w - (k - 1) * gap : (k - 1) * (w + gap);
                RectF bar = new RectF(left, top, left + w, top + h);
                paint.setColor(k < current ? ui.accent : ui.raised);
                canvas.drawRoundRect(bar, h / 2, h / 2, paint);
                if (k == current) {
                    canvas.save();
                    float half = w / 2;
                    canvas.clipRect(rtl ? bar.right - half : bar.left, bar.top, rtl ? bar.right : bar.left + half, bar.bottom);
                    paint.setColor(ui.accent); canvas.drawRoundRect(bar, h / 2, h / 2, paint);
                    canvas.restore();
                }
            }
        }
    }

    /**
     * Step list (about 40%) and the step side by side, 20dp apart; on a vertical hinge the gap covers
     * the hinge instead, so nothing sits under the fold.
     */
    private final class Panes extends LinearLayout {
        private final View side, gutter, detail;
        Panes(View side, View detail) {
            super(host.activity()); setOrientation(HORIZONTAL); setBaselineAligned(false);
            this.side = side; this.detail = detail; gutter = new View(host.activity());
            addView(side, new LinearLayout.LayoutParams(0, -1)); addView(gutter, new LinearLayout.LayoutParams(ui.dp(20), -1)); addView(detail, new LinearLayout.LayoutParams(0, -1));
        }
        // The hinge test needs this view's position in the window, which is known only once attached.
        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); post(this::requestLayout); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int size = Math.max(0, MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight());
            int gap = ui.dp(20), first = Math.round((size - gap) * .4f);
            android.graphics.Rect hinge = verticalHinge();
            if (hinge != null && isAttachedToWindow()) {
                int relative = hinge.left - Ui.layoutLeftInWindow(this) - getPaddingLeft(), hingeGap = Math.max(ui.dp(20), hinge.width() + ui.dp(16));
                int proposed = relative - ui.dp(8);
                if (proposed >= ui.dp(200) && size - proposed - hingeGap >= ui.dp(260)) { first = proposed; gap = hingeGap; }
            }
            ((LinearLayout.LayoutParams) side.getLayoutParams()).width = Math.max(0, first);
            ((LinearLayout.LayoutParams) gutter.getLayoutParams()).width = gap;
            ((LinearLayout.LayoutParams) detail.getLayoutParams()).width = Math.max(0, size - first - gap);
            super.onMeasure(widthSpec, heightSpec);
        }
    }
    /**
     * The activity's fold bounds (window coordinates) when a vertical hinge splits the screen, else null.
     * Read the same way RidesScreen reads them; there is no public accessor.
     */
    private android.graphics.Rect verticalHinge() {
        try {
            java.lang.reflect.Field field = host.activity().getClass().getDeclaredField("fold"); field.setAccessible(true);
            Object fold = field.get(host.activity());
            android.graphics.Rect hinge = fold instanceof FoldMonitor && ((FoldMonitor) fold).isSeparating() ? ((FoldMonitor) fold).hinge : null;
            return hinge != null && hinge.height() > hinge.width() && hinge.left > 0 && hinge.height() > 0 ? hinge : null;
        } catch (ReflectiveOperationException | RuntimeException unavailable) { return null; }
    }

    /** Scooter photo over a soft red floor glow; the done screen adds a green check badge. */
    private final class Hero extends View {
        private final boolean badge; private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        Hero(boolean badge) {
            super(host.activity()); this.badge = badge;
            if (photo == null) {
                try {
                    BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 2;
                    photo = BitmapFactory.decodeResource(host.activity().getResources(), R.drawable.ut5max, options);
                } catch (RuntimeException | OutOfMemoryError ignored) { photo = null; }
            }
            setContentDescription(badge ? "NAVEE UT5 MAX scooter, connected" : "NAVEE UT5 MAX scooter");
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        }
        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), cx = w / 2f;
            float glowW = Math.min(w * 0.36f, ui.dp(150)), glowY = h - ui.dp(30);
            canvas.save();
            canvas.scale(1f, 0.28f, cx, glowY);
            paint.setShader(new RadialGradient(cx, glowY, glowW,
                    new int[] {Ui.withAlpha(ui.accent, ui.dark ? 150 : 95), Ui.withAlpha(ui.accent, 0)}, null, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, glowY, glowW, paint);
            paint.setShader(null);
            canvas.restore();
            float imageRight = cx + ui.dp(60), imageTop = ui.dp(8);
            if (photo != null && photo.getHeight() > 0) {
                float height = h - ui.dp(12), width = height * photo.getWidth() / photo.getHeight();
                if (width > w) { width = w; height = width * photo.getHeight() / photo.getWidth(); }
                RectF target = new RectF(cx - width / 2, h - ui.dp(4) - height, cx + width / 2, h - ui.dp(4));
                canvas.drawBitmap(photo, null, target, paint);
                imageRight = target.right; imageTop = target.top;
            }
            if (badge) {
                float r = ui.dp(24), bx = Math.min(w - r - ui.dp(8), imageRight - r * 0.6f), by = imageTop + r + ui.dp(18);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(ui.bg); canvas.drawCircle(bx, by, r + ui.dp(6), paint);
                paint.setColor(ui.ok); canvas.drawCircle(bx, by, r, paint);
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(ui.dp(3.2f));
                paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setColor(ui.dark ? ui.bg : ui.onAccent);
                Path check = new Path();
                check.moveTo(bx - r * 0.38f, by + r * 0.02f); check.lineTo(bx - r * 0.1f, by + r * 0.3f); check.lineTo(bx + r * 0.4f, by - r * 0.26f);
                canvas.drawPath(check, paint);
                paint.setStyle(Paint.Style.FILL);
            }
        }
    }

    private final class Radio extends View {
        private final boolean on; private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Radio(boolean on) { super(host.activity()); this.on = on; setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas canvas) {
            float r = Math.min(getWidth(), getHeight()) / 2f, stroke = on ? ui.dp(6) : ui.dp(2);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(stroke);
            paint.setColor(on ? ui.accent : ui.outline);
            canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, r - stroke / 2, paint);
        }
    }

    private final class Box extends View {
        boolean on; private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Box() { super(host.activity()); setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas canvas) {
            float s = Math.min(getWidth(), getHeight()), stroke = ui.dp(2);
            RectF rect = new RectF(stroke / 2, stroke / 2, s - stroke / 2, s - stroke / 2);
            if (on) {
                paint.setStyle(Paint.Style.FILL); paint.setColor(ui.accent);
                canvas.drawRoundRect(rect, ui.dp(7), ui.dp(7), paint);
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(ui.dp(2.6f)); paint.setColor(ui.onAccent);
                paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
                Path check = new Path();
                check.moveTo(s * 0.27f, s * 0.52f); check.lineTo(s * 0.43f, s * 0.68f); check.lineTo(s * 0.74f, s * 0.35f);
                canvas.drawPath(check, paint);
            } else {
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(stroke); paint.setColor(ui.outline);
                canvas.drawRoundRect(rect, ui.dp(7), ui.dp(7), paint);
            }
        }
    }

    /** Empty checkbox inside the mock system prompt: Always allow stays unticked. */
    private final class MockCheck extends View {
        private final int color; private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        MockCheck(int color) { super(host.activity()); this.color = color; setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas canvas) {
            float s = Math.min(getWidth(), getHeight()), stroke = ui.dp(2);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(stroke); paint.setColor(color);
            canvas.drawRoundRect(new RectF(stroke / 2, stroke / 2, s - stroke / 2, s - stroke / 2), ui.dp(4), ui.dp(4), paint);
        }
    }

    /** Document glyph for the owner file card. */
    private final class DocIcon extends View {
        private final int color; private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        DocIcon(int color) { super(host.activity()); this.color = color; setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas canvas) {
            float s = Math.min(getWidth(), getHeight()) / 24f;
            canvas.save(); canvas.scale(s, s);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.8f); paint.setColor(color);
            paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
            Path p = new Path();
            p.moveTo(6, 3); p.lineTo(14, 3); p.lineTo(19, 8); p.lineTo(19, 21); p.lineTo(6, 21); p.close();
            p.moveTo(14, 3); p.lineTo(14, 8); p.lineTo(19, 8);
            p.moveTo(9, 13); p.lineTo(16, 13); p.moveTo(9, 17); p.lineTo(14, 17);
            canvas.drawPath(p, paint);
            canvas.restore();
        }
    }

    /** Checklist indicator: grey ring, red spinner, or green check. */
    private final class StateDot extends View {
        static final int WAITING = 0, ACTIVE = 1, DONE = 2;
        private int mode = WAITING; private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        StateDot() { super(host.activity()); setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); }
        void setMode(int value) { if (value != mode) { mode = value; invalidate(); } }
        @Override protected void onDraw(Canvas canvas) {
            float s = Math.min(getWidth(), getHeight()), c = s / 2f, stroke = ui.dp(2.2f);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
            if (mode == DONE) {
                paint.setStrokeWidth(ui.dp(2.8f)); paint.setColor(ui.ok);
                Path check = new Path();
                check.moveTo(s * 0.16f, s * 0.52f); check.lineTo(s * 0.4f, s * 0.75f); check.lineTo(s * 0.86f, s * 0.27f);
                canvas.drawPath(check, paint);
                return;
            }
            RectF ring = new RectF(stroke + ui.dp(1), stroke + ui.dp(1), s - stroke - ui.dp(1), s - stroke - ui.dp(1));
            paint.setStrokeWidth(stroke); paint.setColor(mode == ACTIVE ? ui.raised : ui.outline);
            canvas.drawOval(ring, paint);
            if (mode == ACTIVE) {
                boolean moving = motionEnabled();
                float angle = moving ? (android.os.SystemClock.uptimeMillis() % 1000L) * 0.36f : 0f;
                paint.setColor(ui.accent);
                canvas.drawArc(ring, angle - 90, 100, false, paint);
                if (moving) postInvalidateOnAnimation();
            }
        }
    }

    /** Six two-character boxes mirroring the identity field laid invisibly over them. */
    private final class HexBoxes extends View {
        private String value = ""; private boolean error;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG), letters = new Paint(Paint.ANTI_ALIAS_FLAG);
        HexBoxes() {
            super(host.activity()); setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            letters.setTypeface(Typeface.MONOSPACE); letters.setTextAlign(Paint.Align.CENTER);
        }
        void set(String text, boolean invalid) { value = text == null ? "" : text; error = invalid; invalidate(); }
        @Override protected void onDraw(Canvas canvas) {
            float gap = ui.dp(7), inset = ui.dp(3), w = (getWidth() - gap * 5 - inset * 2) / 6f, h = getHeight() - inset * 2;
            boolean focused = identityField != null && identityField.hasFocus();
            int cursor = focused && value.length() < 12 && !error ? value.length() / 2 : -1;
            boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            letters.setTextSize(ui.sp(17)); letters.setColor(ui.text);
            Paint.FontMetrics m = letters.getFontMetrics();
            for (int i = 0; i < 6; i++) {
                int slot = rtl ? 5 - i : i;
                float left = inset + slot * (w + gap);
                RectF box = new RectF(left, inset, left + w, inset + h);
                paint.setStyle(Paint.Style.FILL); paint.setColor(ui.surface);
                canvas.drawRoundRect(box, ui.dp(13), ui.dp(13), paint);
                paint.setStyle(Paint.Style.STROKE);
                if (i == cursor) {
                    paint.setStrokeWidth(ui.dp(3)); paint.setColor(Ui.withAlpha(ui.accent, 70));
                    RectF glow = new RectF(box); glow.inset(-ui.dp(1.5f), -ui.dp(1.5f));
                    canvas.drawRoundRect(glow, ui.dp(14), ui.dp(14), paint);
                    paint.setStrokeWidth(ui.dp(2)); paint.setColor(ui.accent);
                } else {
                    paint.setStrokeWidth(ui.dp(1)); paint.setColor(error ? ui.danger : ui.outline);
                }
                canvas.drawRoundRect(box, ui.dp(13), ui.dp(13), paint);
                int start = i * 2;
                if (start < value.length()) {
                    String pair = value.substring(start, Math.min(value.length(), start + 2));
                    canvas.drawText(pair, box.centerX(), box.centerY() - (m.ascent + m.descent) / 2, letters);
                }
            }
        }
    }
}
