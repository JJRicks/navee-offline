package local.navee.offlinelock.tests;

import android.Manifest;
import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import local.navee.offlinelock.*;

/**
 * Disposable emulator only: legacy Battery cell clipping, the charging hero's glow pulse and the
 * lock slider trail. Each is checked in View.draw bitmaps and in real `screencap` frames of the
 * hardware-rendered window. Synthetic readings and the memory-only simulator; Bluetooth stays off,
 * no GATT is opened and no scooter command is built. Refuses physical devices and existing data.
 */
public final class MotionPolishValidation extends Instrumentation {
    private Bundle args;
    private MainActivity activity;
    private String mode, variant;
    private int assertions;
    private final ArrayList<String> checks = new ArrayList<>(), screenshots = new ArrayList<>(), notes = new ArrayList<>();
    private File folder;

    @Override public void onCreate(Bundle arguments) { args = arguments == null ? new Bundle() : arguments; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        boolean admitted = false;
        try {
            admit(); admitted = true;
            mode = args.getString("mode", "battery");
            variant = args.getString("variant", mode).replaceAll("[^A-Za-z0-9_.-]", "");
            folder = new File(getTargetContext().getExternalFilesDir(null), "motion-polish-validation");
            require(folder.isDirectory() || folder.mkdirs(), "Cannot create evidence folder");
            seed();
            activity = (MainActivity)startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.service() != null && screen() != null && grid().getWidth() > 0, "Dashboard not ready");
            main(() -> require(activity.service().setSimulation(true) || activity.service().simulating(), "Simulator failed to enable"));
            await(() -> activity.service().simulating() && activity.service().ble.ready() && tileOrNull("battery") != null, "Simulator not ready");
            settle();
            notes.add("motion_enabled=" + Ui.motionEnabled());
            if (mode.equals("battery")) verifyBatteryCells();
            else if (mode.equals("glow")) verifyGlow();
            else if (mode.equals("slider")) verifySliders();
            else throw new IllegalArgumentException("Unknown mode " + mode);
            main(() -> require(field(activity.service().ble, "gatt") == null, "Fixture opened Bluetooth"));
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", String.valueOf(error));
            StringBuilder trace = new StringBuilder();
            for (StackTraceElement e : error.getStackTrace()) { if (trace.length() > 1500) break; trace.append(e).append('\n'); }
            result.putString("trace", trace.toString());
            if (activity != null) try { save(screencap(), "failure"); } catch (Throwable ignored) { }
        } finally {
            result.putInt("assertions", assertions);
            result.putStringArrayList("checks", checks); result.putStringArrayList("notes", notes);
            result.putStringArrayList("screenshots", screenshots);
            result.putBoolean("physical_device_tested", false); result.putInt("scooter_mutations", 0);
            if (admitted && activity != null) try { main(() -> { activity.finish(); ScooterService.setUiVisible(false); getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); }); } catch (Throwable ignored) { }
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    private void admit() {
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
        require(hardware.contains("ranchu") || hardware.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "Refusing a physical phone");
        for (String name : new String[] {"app_settings", "profiles", "scooter", "pending_documents"})
            require(getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE).getAll().isEmpty(), "Refusing existing preferences");
        File[] files = getTargetContext().getFilesDir().listFiles();
        require(files != null && files.length == 0 && getTargetContext().databaseList().length == 0, "Refusing existing private data");
        for (String permission : new String[] {Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT})
            require(getTargetContext().checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED, "Grant Nearby permission before the test");
        BluetoothManager manager = getTargetContext().getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        require(adapter == null || !adapter.isEnabled(), "Bluetooth must already be disabled");
    }

    /** Two columns; the layout depends on the mode. Battery cells: narrow-tall, narrow-short, wide-short. */
    private void seed() {
        main(() -> {
            settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                    .putBoolean("developer_options", true).putString("theme", args.getString("theme", "dark"))
                    .putString("units", "mi").putInt("dashboard_columns", 2)
                    .putFloat("text_scale", Float.parseFloat(args.getString("text_scale", "1"))).commit();
            ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
            ScooterProfiles.Profile profile = profiles.create("Motion polish fixture", "000000000000", 0);
            profile.serial = "X2538-SYNTHETIC"; profiles.update(profile);
            DashboardConfig config = new DashboardConfig(settings(), profile.id); config.widgets.clear();
            if (mode.equals("battery")) {
                add(config, "battery", 0, 0, 1, 6); add(config, "battery", 1, 0, 1, 4); add(config, "battery", 0, 6, 2, 4);
            } else if (mode.equals("glow")) {
                add(config, "lockbar", 0, 0, 2, 1); add(config, "battery", 0, 1, 2, 6);
            } else {
                add(config, "lockbar", 0, 0, 2, 1); add(config, "lock", 0, 1, 2, 4); add(config, "battery", 0, 5, 1, 6);
            }
            config.save();
        });
    }
    private void add(DashboardConfig config, String type, int x, int y, int w, int h) {
        DashboardConfig.Widget widget = new DashboardConfig.Widget(type);
        widget.layouts.put(2, new DashboardLayout.Cell(widget.id, x, y, w, h));
        config.widgets.add(widget);
    }

    // ---------------------------------------------------------------- Item 3: legacy Battery cell

    private static final class CellState {
        final String name; final double percent; final boolean charging, live;
        CellState(String name, double percent, boolean charging, boolean live) { this.name = name; this.percent = percent; this.charging = charging; this.live = live; }
    }
    /** One cell's evidence: a View.draw bitmap and the matching crop of a real screencap. */
    private static final class CellShot { Bitmap drawn; int[] screen; int sw, sh; }

    private void verifyBatteryCells() throws Exception {
        stopUiPulse(); settle();
        final List<Object> items = new ArrayList<>();
        main(() -> { for (Object item : tiles()) if (((DashboardConfig.Widget)field(item, "widget")).type.equals("battery")) items.add(item); });
        require(items.size() == 3, "Expected three legacy Battery cells");
        double[] levels = {1, 5, 14, 15, 50, 99, 100};
        String[][] groups = {{"discharging", "true", "false"}, {"charging", "true", "true"}, {"stale", "false", "false"}};
        int checked = 0;
        for (String pass : new String[] {"initial", "resized"}) {
            if (pass.equals("resized")) {
                // Resize all three in place: short-narrow, tall-narrow and a taller wide cell.
                main(() -> {
                    int[][] cells = {{0, 0, 1, 4}, {1, 0, 1, 8}, {0, 8, 2, 5}};
                    for (int i = 0; i < 3; i++) {
                        DashboardLayout.Cell cell = ((DashboardConfig.Widget)field(items.get(i), "widget")).layouts.get(2);
                        cell.x = cells[i][0]; cell.y = cells[i][1]; cell.w = cells[i][2]; cell.h = cells[i][3];
                    }
                    grid().requestLayout();
                });
                settle();
            }
            String sizes = sizes(items);
            notes.add(pass + " cell sizes px " + sizes);
            for (String[] group : groups) {
                boolean live = Boolean.parseBoolean(group[1]), charging = Boolean.parseBoolean(group[2]);
                ArrayList<CellState> states = new ArrayList<>();
                states.add(new CellState(group[0] + "-0", 0, charging, live));
                for (double level : levels) states.add(new CellState(group[0] + "-" + (int)level, level, charging, live));
                ArrayList<CellShot[]> shots = new ArrayList<>();
                for (CellState state : states) {
                    CellShot[] shot = captureCells(items, state);
                    shots.add(shot);
                    for (int i = 0; i < shot.length; i++) {
                        checkCell(items.get(i), shot[i], shots.size() == 1 ? null : shots.get(0)[i], pass + "/" + state.name + "/cell" + i);
                        checked++;
                    }
                    if (state.percent == 1 || state.percent == 14 || state.percent == 100) save(lastFrame, pass + "-" + state.name);
                }
                saveCornerSheet(shots, states, pass + "-" + group[0] + "-corners");
            }
            for (CellState state : new CellState[] {new CellState("unknown", Double.NaN, false, true), new CellState("unknown-stale", Double.NaN, false, false)}) {
                CellShot[] shot = captureCells(items, state);
                for (int i = 0; i < shot.length; i++) { checkCell(items.get(i), shot[i], null, pass + "/" + state.name + "/cell" + i); checked++; }
                save(lastFrame, pass + "-" + state.name);
            }
        }
        check("Legacy Battery cell: " + checked + " cell renders (0/1/5/14/15/50/99/100% discharging, charging and last-seen, unknown live/stale; narrow, short, wide and resized) keep every pixel outside the outer rounded border unchanged and add no fill fringe in the border ring, in both View.draw bitmaps and real screencap frames");
    }

    private Bitmap lastFrame;
    private final AtomicReference<Rect> lastViewport = new AtomicReference<>();

    private String sizes(List<Object> items) {
        StringBuilder text = new StringBuilder();
        main(() -> { for (Object item : items) { View cell = (View)field(item, "battery"); text.append(cell.getWidth()).append('x').append(cell.getHeight()).append(' '); } });
        return text.toString().trim();
    }

    private CellShot[] captureCells(List<Object> items, CellState state) throws Exception {
        long wall = System.currentTimeMillis() - (state.live ? 0 : 3600000L);
        Map<String, TelemetryHistory.Reading> values = new LinkedHashMap<>();
        if (!Double.isNaN(state.percent)) reading(values, "battery_percent", Math.round(state.percent) + " %", "%", state.percent, wall, state.live);
        // A reported but unreadable charge; without any reading the cell shows a last-seen state.
        else if (state.live) values.put("battery_percent", new TelemetryHistory.Reading("battery_percent", "battery_percent", "Synthetic fixture", "Unreadable", "%", null, wall, true));
        reading(values, "remaining_range_km", "38.4 km", "km", 38.4, wall, state.live);
        reading(values, "charging_state", state.charging ? "Charging" : "Not charging", "", state.charging ? 1 : 0, wall, state.live);
        reading(values, "voltage_v", "50.4 V", "V", 50.4, wall, state.live);
        reading(values, "power_w", state.charging ? "-140 W" : "18 W", "W", state.charging ? -140 : 18, wall, state.live);
        main(() -> { for (Object item : items) invoke(item, "refresh", new Class<?>[] {Map.class}, values); grid().requestLayout(); });
        settle();
        CellShot[] shots = new CellShot[items.size()];
        main(() -> {
            for (int i = 0; i < items.size(); i++) {
                Object cell = field(items.get(i), "battery");
                double percent = (Double)field(cell, "percent");
                require(Double.compare(percent, state.percent) == 0 && (Boolean)field(cell, "live") == state.live
                        && (Boolean)field(cell, "charging") == (state.charging && state.live), "Battery fixture did not reach " + state.name + ": " + ((View)cell).getContentDescription());
                shots[i] = new CellShot(); shots[i].drawn = draw((View)cell);
            }
        });
        // Real composited frames. Rows of each cell's crop (cell plus a 4 px margin) are taken from
        // whichever frame shows them inside the scroll viewport, clear of the floating navigation;
        // a cell taller than the viewport is assembled from two or more scrolled frames.
        int m = 4;
        boolean[][] filled = new boolean[items.size()][];
        main(() -> {
            for (int i = 0; i < items.size(); i++) {
                View cell = (View)field(items.get(i), "battery");
                shots[i].sw = cell.getWidth() + 2 * m; shots[i].sh = cell.getHeight() + 2 * m;
                shots[i].screen = new int[shots[i].sw * shots[i].sh]; filled[i] = new boolean[shots[i].sh];
            }
        });
        for (int round = 0; ; round++) {
            int[] rows = new int[items.size() * 3];
            main(() -> {
                Rect clear = viewport();
                for (int i = 0; i < items.size(); i++) {
                    View cell = (View)field(items.get(i), "battery");
                    int[] at = new int[2]; cell.getLocationOnScreen(at);
                    rows[i * 3] = at[0] - m; rows[i * 3 + 1] = at[1] - m;
                    // Visible crop rows: [first, last)
                    rows[i * 3 + 2] = cell.isShown() ? 1 : 0;
                }
                lastViewport.set(clear);
            });
            Bitmap frame = screencap(); lastFrame = frame;
            if (notes.size() < 3) notes.add("screencap " + frame.getWidth() + "x" + frame.getHeight());
            Rect clear = lastViewport.get();
            boolean all = true;
            for (int i = 0; i < items.size(); i++) {
                int x = rows[i * 3], y = rows[i * 3 + 1], w = shots[i].sw;
                require(x >= 0 && x + w <= frame.getWidth(), "Cell crop leaves the screencap horizontally");
                for (int r = 0; r < shots[i].sh; r++) {
                    int sy = y + r;
                    if (filled[i][r] || rows[i * 3 + 2] == 0 || sy < clear.top || sy >= clear.bottom || sy >= frame.getHeight()) continue;
                    frame.getPixels(shots[i].screen, r * w, w, x, sy, w, 1);
                    filled[i][r] = true;
                }
                for (boolean f : filled[i]) if (!f) { all = false; break; }
            }
            if (all) break;
            require(round < 8, "Could not bring every Battery cell row on screen");
            // Scroll the first missing row of the first incomplete cell to the viewport's top.
            main(() -> {
                for (int i = 0; i < items.size(); i++) {
                    int r = 0; while (r < filled[i].length && filled[i][r]) r++;
                    if (r == filled[i].length) continue;
                    View cell = (View)field(items.get(i), "battery");
                    int[] at = new int[2]; cell.getLocationOnScreen(at);
                    scroll().scrollBy(0, at[1] - m + r - viewport().top - ui().dp(8));
                    return;
                }
            });
            settle();
        }
        return shots;
    }

    /** Fully drawn and clear of the floating navigation, which overlays the scroll's bottom padding. */
    private boolean fullyVisible(View view) {
        Rect visible = new Rect();
        if (!view.isShown() || !view.getGlobalVisibleRect(visible) || visible.width() != view.getWidth() || visible.height() != view.getHeight()) return false;
        int[] at = new int[2]; view.getLocationOnScreen(at);
        Rect clear = viewport();
        return at[1] - 4 >= clear.top && at[1] + view.getHeight() + 4 <= clear.bottom;
    }
    private android.widget.ScrollView scroll() { return (android.widget.ScrollView)field(activity, "scroll"); }
    private Rect viewport() {
        android.widget.ScrollView scroll = scroll(); int[] at = new int[2]; scroll.getLocationOnScreen(at);
        return new Rect(at[0], at[1] + scroll.getPaddingTop(), at[0] + scroll.getWidth(), at[1] + scroll.getHeight() - scroll.getPaddingBottom());
    }

    /** Signed distance from a pixel centre to the w×h rounded rectangle with corner r (negative inside). */
    private static float inside(float px, float py, float w, float h, float r) {
        float qx = Math.abs(px - w / 2) - (w / 2 - r), qy = Math.abs(py - h / 2) - (h / 2 - r);
        float ox = Math.max(qx, 0), oy = Math.max(qy, 0);
        return (float)Math.sqrt(ox * ox + oy * oy) + Math.min(Math.max(qx, qy), 0) - r;
    }

    private void checkCell(Object item, CellShot shot, CellShot baseline, String label) {
        View cell = (View)field(item, "battery");
        float corner = ui().dp(14), edge = Math.max(1, ui().dp(1));
        int w = shot.drawn.getWidth(), h = shot.drawn.getHeight();
        int[] drawn = new int[w * h], base = null;
        shot.drawn.getPixels(drawn, 0, w, 0, 0, w, h);
        if (baseline != null) {
            require(baseline.drawn.getWidth() == w && baseline.drawn.getHeight() == h && baseline.sw == shot.sw && baseline.sh == shot.sh, "Baseline size changed for " + label);
            base = new int[w * h]; baseline.drawn.getPixels(base, 0, w, 0, 0, w, h);
        }
        // View.draw: nothing at all outside the outer silhouette; the border ring equals the empty cell.
        int outside = 0, fringe = 0;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            float d = inside(x + .5f, y + .5f, w, h, corner);
            int p = drawn[y * w + x];
            if (d > 1 && (p >>> 24) != 0) outside++;
            if (base != null && d > -edge / 2 + 1 && delta(p, base[y * w + x]) > 1) fringe++;
        }
        require(outside == 0, label + ": View.draw painted " + outside + " pixels outside the rounded border");
        require(fringe == 0, label + ": View.draw fill changed " + fringe + " border-ring pixels");
        // Hardware frame: outside pixels match the card around the cell; the ring equals the empty cell.
        int m = 4, sw = shot.sw, sh = shot.sh;
        int hwOutside = 0, hwFringe = 0, worst = 0;
        int[][] refs = {{0, 0}, {sw - 1, 0}, {0, sh - 1}, {sw - 1, sh - 1}};
        for (int y = 0; y < sh; y++) for (int x = 0; x < sw; x++) {
            float d = inside(x - m + .5f, y - m + .5f, w, h, corner);
            int p = shot.screen[y * sw + x];
            if (base != null && d > -edge / 2 + 1) {
                int diff = delta(p, baseline.screen[y * sw + x]);
                if (diff > 3) { hwFringe++; worst = Math.max(worst, diff); }
            } else if (base == null && d > 1.5f) {
                // Nearest outer corner of the crop is card background, well clear of the cell.
                int[] ref = refs[(x < sw / 2 ? 0 : 1) + (y < sh / 2 ? 0 : 2)];
                int diff = delta(p, shot.screen[ref[1] * sw + ref[0]]);
                if (diff > 6) { hwOutside++; worst = Math.max(worst, diff); }
            }
        }
        require(hwOutside == 0, label + ": screencap shows " + hwOutside + " non-background pixels outside the border (worst " + worst + ")");
        require(hwFringe == 0, label + ": screencap fill changed " + hwFringe + " pixels at/outside the border ring (worst " + worst + ")");
        assertions++;
    }

    private static int delta(int a, int b) {
        int worst = 0;
        for (int shift = 0; shift <= 24; shift += 8) worst = Math.max(worst, Math.abs(((a >>> shift) & 255) - ((b >>> shift) & 255)));
        return worst;
    }

    /** Rows: states; columns: each cell's four corners from the real screencap, 4× nearest-neighbour. */
    private void saveCornerSheet(List<CellShot[]> shots, List<CellState> states, String name) throws Exception {
        int corner = ui().dp(14) + 8, scale = 4, cells = shots.get(0).length;
        int tile = corner * scale + 4;
        Bitmap sheet = Bitmap.createBitmap(tile * cells * 4, tile * states.size(), Bitmap.Config.ARGB_8888);
        sheet.eraseColor(0xff808080);
        for (int row = 0; row < shots.size(); row++) for (int c = 0; c < cells; c++) {
            CellShot shot = shots.get(row)[c];
            int[][] origins = {{0, 0}, {shot.sw - corner, 0}, {0, shot.sh - corner}, {shot.sw - corner, shot.sh - corner}};
            for (int k = 0; k < 4; k++) for (int y = 0; y < corner; y++) for (int x = 0; x < corner; x++) {
                int p = shot.screen[(origins[k][1] + y) * shot.sw + origins[k][0] + x];
                for (int dy = 0; dy < scale; dy++) for (int dx = 0; dx < scale; dx++)
                    sheet.setPixel((c * 4 + k) * tile + 2 + x * scale + dx, row * tile + 2 + y * scale + dy, p);
            }
        }
        save(sheet, name);
    }

    // ---------------------------------------------------------------- Item 4: charging glow

    private void verifyGlow() throws Exception {
        boolean motion = Ui.motionEnabled();
        main(() -> { activity.service().ble.setSimulationCharging(true); screen().refresh(); });
        await(() -> hero().getVisibility() == View.VISIBLE && field(hero(), "reveal") == null && hero().getHeight() > 0, "Charging hero did not appear");
        // Hero top at the viewport top, so short landscape windows still show the floor and glow.
        main(() -> { int[] at = new int[2]; hero().getLocationOnScreen(at); scroll().scrollBy(0, at[1] - viewport().top); });
        settle();
        main(() -> require(((View)field(hero(), "photo")).getVisibility() == View.VISIBLE, "Synthetic X2538 profile lost the scooter photo"));
        ArrayList<android.animation.ValueAnimator> seen = new ArrayList<>();
        if (motion) {
            await(() -> pulse() != null && pulse().isRunning(), "Charging glow did not start");
            seen.add(pulse());
        } else main(() -> require(pulse() == null && glow() == 1f, "Reduced motion kept a glow animator"));

        // Frames ~0.5 s apart over ~4 s, with the Activity's own 1 s refresh still running.
        int[] regions = glowRegions();
        // One discarded frame: right after the reveal and scroll the first composited frame can
        // differ by a few levels at text/photo edges (seen once in frame 0 only), unrelated to the glow.
        screencap(); SystemClock.sleep(300);
        ArrayList<int[][]> frames = new ArrayList<>();
        ArrayList<String> timeline = new ArrayList<>();
        ArrayList<Bitmap> raw = new ArrayList<>();
        long start = SystemClock.uptimeMillis();
        for (int i = 0; i < 8; i++) {
            long due = start + i * 500L;
            while (SystemClock.uptimeMillis() < due) SystemClock.sleep(5);
            float before = glow();
            Bitmap frame = screencap();
            float after = glow();
            frames.add(cropRegions(frame, regions));
            raw.add(frame);
            timeline.add(String.format(Locale.US, "t=%dms glow=%.2f..%.2f", SystemClock.uptimeMillis() - start, before, after));
            if (motion) { android.animation.ValueAnimator now = pulse(); require(now != null, "Glow stopped during frames"); if (!seen.contains(now)) seen.add(now); }
        }
        for (int i = 0; i < raw.size(); i++) {
            if (i == 0) save(raw.get(i), "glow-frame-0");
            saveCrop(raw.get(i), regions[0], regions[1], regions[2], regions[3], 1, "glow-hero-" + i);
        }
        notes.add("glow frames " + timeline);
        double low = Double.MAX_VALUE, high = -1; int worstGlow = 0, worstStatic = 0; int[] regionWorst = new int[6];
        for (int[][] frame : frames) {
            double mean = 0; for (int p : frame[1]) mean += luminance(p); mean /= frame[1].length;
            low = Math.min(low, mean); high = Math.max(high, mean);
            for (int k = 0; k < frame[1].length; k++) worstGlow = Math.max(worstGlow, delta(frame[1][k], frames.get(0)[1][k]));
            for (int r = 2; r < frame.length; r++) for (int k = 0; k < frame[r].length; k++) {
                int d = delta(frame[r][k], frames.get(0)[r][k]);
                worstStatic = Math.max(worstStatic, d); regionWorst[r] = Math.max(regionWorst[r], d);
            }
        }
        notes.add(String.format(Locale.US, "glow band mean luminance %.2f..%.2f, worst glow pixel change %d, worst static pixel change %d", low, high, worstGlow, worstStatic));
        notes.add("static region worst (percent, chip, upper photo, floor line) " + regionWorst[2] + "," + regionWorst[3] + "," + regionWorst[4] + "," + regionWorst[5]);
        // HWUI/SwiftShader re-rasterises text and photo edges by a few levels between frames while
        // anything on screen animates (seen: ±4 at 344 dp, ±9 at 725 dp, edges only); a moved
        // glyph or photo edge changes by ~100+ levels.
        require(worstStatic <= 12, "Percent, chip, upper photo or floor line moved during the pulse (" + worstStatic + ")");
        if (motion) {
            require(high - low >= 1.0, String.format(Locale.US, "Glow motion is not visible in screencap frames (%.2f)", high - low));
            require(worstGlow <= 80, "Glow pulse is not subtle (" + worstGlow + ")");
            require(seen.size() == 1, "The glow animator was replaced during ordinary refreshes: " + seen.size());
            check(String.format(Locale.US, "Charging glow: 8 real frames 0.5 s apart show a subtle pulse (band luminance %.1f..%.1f, max pixel change %d) while SOC, chip, upper photo and floor line stay put (max %d levels at antialiased edges); one animator across 1 s refreshes", low, high, worstGlow, worstStatic));
        } else {
            require(high - low < .3 && worstGlow <= 3, "Reduced motion glow moved");
            check("Reduced motion: no glow animator, glow == 1 and 8 real frames over 4 s are identical");
        }

        // Repeated refreshes and dashboard rebuilds keep a single loop.
        for (int i = 0; i < 6; i++) { main(() -> { screen().refresh(); invoke(screen(), "render"); screen().refresh(); }); SystemClock.sleep(80); }
        settle();
        if (motion) { await(() -> pulse() != null && pulse().isRunning(), "Glow did not survive rebuilds"); remember(seen); requireOneRunning(seen, "after 6 refresh+render rounds"); }
        else main(() -> require(pulse() == null, "Rebuild started a reduced-motion glow"));

        // Edit mode stops it; finishing editing resumes it.
        main(() -> invoke(screen(), "toggleEditing")); settleMotion();
        main(() -> require(pulse() == null && glow() == 1f && hero().getVisibility() == View.GONE, "Edit mode kept the glow running"));
        requireNoneRunning(seen, "edit mode");
        save(screencap(), "glow-editing");
        main(() -> invoke(screen(), "toggleEditing")); settleMotion();
        main(() -> screen().refresh());
        resumed(motion, seen, "finishing editing");

        // Stale data (simulated disconnect) hides the hero and stops the loop.
        main(() -> { activity.service().ble.disconnect(); screen().refresh(); }); settle();
        main(() -> require(pulse() == null && glow() == 1f, "Disconnected/stale charging kept the glow running"));
        requireNoneRunning(seen, "disconnect");
        main(() -> { activity.service().ble.connectLast(); activity.service().ble.setSimulationCharging(true); screen().refresh(); });
        resumed(motion, seen, "reconnecting");

        // Detach and reattach the hero.
        main(() -> {
            View hero = hero(); ViewGroup parent = (ViewGroup)hero.getParent(); int index = parent.indexOfChild(hero);
            ViewGroup.LayoutParams params = hero.getLayoutParams();
            parent.removeView(hero);
            require(pulse() == null && glow() == 1f, "Detached hero kept the glow running");
            parent.addView(hero, index, params);
        });
        requireNoneRunningExceptCurrent(seen, "detach");
        resumed(motion, seen, "reattaching");

        // Backgrounding (Home key) stops it; returning resumes it in the same Activity.
        MainActivity before = activity;
        shell("input keyevent KEYCODE_HOME");
        await(() -> !(Boolean)field(activity, "resumed"), "Home key did not background the app");
        long hidden = SystemClock.uptimeMillis();
        await(() -> pulse() == null && glow() == 1f, "Backgrounded app kept the glow running");
        AtomicReference<String> life = new AtomicReference<>();
        main(() -> life.set("stopped glow " + (SystemClock.uptimeMillis() - hidden) + " ms after onPause; window visibility " + hero().getWindowVisibility() + ", stage onScreen " + field(field(hero(), "stage"), "onScreen")));
        notes.add(life.get());
        requireNoneRunning(seen, "background");
        shell("am start -n local.navee.offlinelock/.MainActivity");
        await(() -> (Boolean)field(activity, "resumed"), "App did not return to the foreground");
        require(activity == before, "Returning created a second Activity");
        settle();
        main(() -> screen().refresh());
        resumed(motion, seen, "returning from background");

        // Leaving Home closes the screen and stops its loop; coming back starts exactly one new one.
        Object oldScreen = screen(); Object oldHero = hero();
        main(() -> invoke(activity, "select", new Class<?>[] {String.class}, "stats")); settle(); SystemClock.sleep(400);
        main(() -> require((Boolean)field(oldScreen, "closed") && field(oldHero, "pulse") == null, "Leaving Home kept the glow running"));
        requireNoneRunning(seen, "leaving Home");
        main(() -> invoke(activity, "select", new Class<?>[] {String.class}, "home"));
        await(() -> screen() != null && screen() != oldScreen, "Home did not return");
        settle(); main(() -> screen().refresh());
        resumed(motion, seen, "returning to Home");
        save(screencap(), "glow-final");
        check("Glow lifecycle: one loop through refreshes and rebuilds; stops (static glow == 1) for edit mode, disconnect, detach, Home key backgrounding and leaving Home; resumes cleanly with a single running animator" + (motion ? "" : " (reduced motion: never starts)"));
    }

    private void resumed(boolean motion, List<android.animation.ValueAnimator> seen, String when) {
        if (!motion) { settle(); main(() -> require(pulse() == null && glow() == 1f, "Reduced motion started the glow " + when)); return; }
        await(() -> pulse() != null && pulse().isRunning(), "Glow did not resume after " + when);
        remember(seen); requireOneRunning(seen, when);
    }
    private void remember(List<android.animation.ValueAnimator> seen) { main(() -> { android.animation.ValueAnimator now = pulse(); if (now != null && !seen.contains(now)) seen.add(now); }); }
    private void requireOneRunning(List<android.animation.ValueAnimator> seen, String when) {
        main(() -> { int running = 0; for (android.animation.ValueAnimator a : seen) if (a.isRunning()) running++; require(running == 1, running + " glow animators running " + when); });
    }
    private void requireNoneRunning(List<android.animation.ValueAnimator> seen, String when) {
        main(() -> { for (android.animation.ValueAnimator a : seen) require(!a.isRunning(), "An old glow animator kept running after " + when); });
    }
    private void requireNoneRunningExceptCurrent(List<android.animation.ValueAnimator> seen, String when) {
        main(() -> { android.animation.ValueAnimator now = pulse(); for (android.animation.ValueAnimator a : seen) require(a == now || !a.isRunning(), "An old glow animator kept running after " + when); });
    }

    /** Screen rectangles: [hero x, y, w, h], glow band, then static regions (percent, chip, upper photo, the charged part of the floor line; its uncharged grey is translucent over the glow). */
    private int[] glowRegions() {
        int[] out = new int[24];
        main(() -> {
            View hero = hero(), stage = (View)field(hero, "stage"), photo = (View)field(hero, "photo");
            View percent = (View)field(hero, "percent"), chip = (View)field(hero, "chip");
            int[] h = new int[2], s = new int[2], p = new int[2], c = new int[2], f = new int[2];
            hero.getLocationOnScreen(h); stage.getLocationOnScreen(s); percent.getLocationOnScreen(p); chip.getLocationOnScreen(c); photo.getLocationOnScreen(f);
            int sw = stage.getWidth(), sh = stage.getHeight(), floor = s[1] + sh - ui().dp(18);
            int[] values = {
                h[0], h[1], hero.getWidth(), hero.getHeight(),
                s[0] + sw / 7, floor + ui().dp(4), sw * 5 / 7, ui().dp(18) - ui().dp(5),
                p[0], p[1], percent.getWidth(), percent.getHeight(),
                c[0], c[1], chip.getWidth(), chip.getHeight(),
                f[0], f[1], photo.getWidth(), Math.max(1, floor - ui().dp(44) - f[1]),
                s[0] + Math.round(sw * .06f) + ui().dp(3), floor - 1, Math.max(1, Math.round(sw * .88f * (float)((Double)field(hero, "level") / 100)) - ui().dp(6)), 3};
            System.arraycopy(values, 0, out, 0, values.length);
        });
        return out;
    }
    private int[][] cropRegions(Bitmap frame, int[] r) {
        int[][] out = new int[r.length / 4][];
        out[0] = new int[0]; // the whole hero: saved for review only, may extend past a short window
        for (int i = 1; i < out.length; i++) {
            int x = r[i * 4], y = r[i * 4 + 1], w = r[i * 4 + 2], h = r[i * 4 + 3];
            require(x >= 0 && y >= 0 && w > 0 && h > 0 && x + w <= frame.getWidth() && y + h <= frame.getHeight(), "Glow region " + i + " is off screen");
            out[i] = new int[w * h]; frame.getPixels(out[i], 0, w, x, y, w, h);
        }
        return out;
    }
    private static double luminance(int p) { return .2126 * ((p >> 16) & 255) + .7152 * ((p >> 8) & 255) + .0722 * (p & 255); }

    // ---------------------------------------------------------------- Item 8: lock slider trail

    private void verifySliders() throws Exception {
        stopUiPulse(); settle();
        boolean motion = Ui.motionEnabled();
        List<View> sliders = new ArrayList<>();
        main(() -> collectSliders(root(), sliders));
        require(sliders.size() == 2, "Expected the Lock bar and classic Lock sliders, found " + sliders.size());
        int index = 0;
        for (View slider : sliders) {
            String name = "slider" + index++ + ((Boolean)field(slider, "compact") ? "-bar" : "-classic");
            main(() -> { invoke(slider, "update", new Class<?>[] {Boolean.class, boolean.class}, false, true); invoke(slider, "stopMotion"); });
            settle();
            String a11y = accessibility(slider);
            AtomicReference<Bitmap> base = new AtomicReference<>();
            main(() -> base.set(draw(slider)));
            save(base.get(), name + "-drawn-rest");
            float[] y = new float[1]; float[] g = new float[4];
            main(() -> {
                LockSliderGeometry geometry = (LockSliderGeometry)invoke(slider, "sliderGeometry");
                y[0] = slider.getHeight() / 2f; g[0] = geometry.centerFor(0); g[1] = geometry.radius;
            });
            // 1. Trail while moving right, gone after a pause; thumb and outside of the track untouched.
            AtomicReference<Bitmap> still = new AtomicReference<>(), trail = new AtomicReference<>(), faded = new AtomicReference<>();
            long when = SystemClock.uptimeMillis();
            main(() -> { touch(slider, MotionEvent.ACTION_DOWN, pos(slider, 0), y[0], when); touch(slider, MotionEvent.ACTION_MOVE, pos(slider, .55f), y[0], when); });
            SystemClock.sleep(200);
            main(() -> { require((Boolean)field(slider, "dragging"), "Drag did not start"); still.set(draw(slider)); });
            main(() -> touch(slider, MotionEvent.ACTION_MOVE, pos(slider, .2f), y[0], when));
            SystemClock.sleep(200);
            main(() -> {
                for (float p : new float[] {.3f, .4f, .48f, .55f}) { touch(slider, MotionEvent.ACTION_MOVE, pos(slider, p), y[0], when); SystemClock.sleep(14); }
                trail.set(draw(slider));
            });
            SystemClock.sleep(200);
            main(() -> faded.set(draw(slider)));
            float thumbX = centre(slider, .55f);
            int[] stats = trailStats(slider, still.get(), trail.get(), thumbX, g[1]);
            notes.add(name + " trail: changed pixels " + stats[0] + ", max channel change " + stats[1] + ", outside track " + stats[2] + ", inside thumb " + stats[3] + ", ahead of thumb " + stats[4]);
            save(still.get(), name + "-drawn-drag-still"); save(trail.get(), name + "-drawn-drag-trail");
            if (motion) {
                require(stats[0] >= 40, name + ": moving thumb drew no visible trail");
                require(stats[1] <= 160, name + ": trail is not subtle (" + stats[1] + ")");
            } else {
                require(stats[0] == 0, name + ": disabled motion still drew a trail");
                main(() -> require(((LockSliderTrail)field(slider, "trail")).isEmpty(), name + ": disabled motion recorded a trail"));
            }
            require(stats[2] == 0, name + ": trail painted outside the track");
            require(stats[3] == 0, name + ": trail changed the crisp thumb/icon");
            require(stats[4] == 0, name + ": trail ran ahead of the thumb");
            require(changed(still.get(), faded.get()) == 0, name + ": trail did not clear after the thumb stopped");
            // 2. Cancel: back to the confirmed state with nothing left behind.
            main(() -> touch(slider, MotionEvent.ACTION_CANCEL, pos(slider, .55f), y[0], when));
            settled(slider, base.get(), name + " cancel");
            // 3. Rejection: release on the lock side while the command is refused (no service), then the scooter reports unlocked.
            long when2 = SystemClock.uptimeMillis();
            main(() -> { touch(slider, MotionEvent.ACTION_DOWN, pos(slider, 0), y[0], when2); for (float p : new float[] {.3f, .6f, .9f}) { touch(slider, MotionEvent.ACTION_MOVE, pos(slider, p), y[0], when2); SystemClock.sleep(12); } });
            main(() -> {
                Object service = field(activity, "scooterService");
                setField(activity, "scooterService", null);
                try { touch(slider, MotionEvent.ACTION_UP, pos(slider, .9f), y[0], when2); }
                finally { setField(activity, "scooterService", service); }
                require(Boolean.FALSE.equals(field(slider, "value")), name + ": refused release changed the confirmed value");
            });
            settled(slider, base.get(), name + " rejected release");
            main(() -> invoke(slider, "update", new Class<?>[] {Boolean.class, boolean.class}, false, true));
            settled(slider, base.get(), name + " rejection report");
            // 4. Fast reversal, then disconnect mid-drag: unavailable immediately, no trail ghost.
            long when3 = SystemClock.uptimeMillis();
            main(() -> { touch(slider, MotionEvent.ACTION_DOWN, pos(slider, 0), y[0], when3); for (float p : new float[] {.3f, .7f, .95f, .6f, .2f}) { touch(slider, MotionEvent.ACTION_MOVE, pos(slider, p), y[0], when3); SystemClock.sleep(10); } });
            AtomicReference<Bitmap> offline = new AtomicReference<>();
            main(() -> {
                invoke(slider, "update", new Class<?>[] {Boolean.class, boolean.class}, null, false);
                require(((LockSliderTrail)field(slider, "trail")).isEmpty() && !(Boolean)field(slider, "dragging"), name + ": disconnect kept a trail or drag");
                offline.set(draw(slider));
            });
            SystemClock.sleep(250);
            main(() -> { require(changed(offline.get(), draw(slider)) == 0, name + ": disconnected slider kept changing"); invoke(slider, "stopMotion"); require(changed(offline.get(), draw(slider)) == 0, name + ": disconnect left a ghost"); });
            main(() -> { invoke(slider, "update", new Class<?>[] {Boolean.class, boolean.class}, false, true); invoke(slider, "stopMotion"); });
            settled(slider, base.get(), name + " reconnect");
            // 5. A real hardware-rendered drag: injected touches move continuously while screencap runs.
            hardwareDrag(slider, name);
            require(a11y.equals(accessibility(slider)), name + ": accessibility changed\n" + a11y + "\n" + accessibility(slider));
            notes.add(name + " a11y " + a11y);
        }
        check("Lock slider: " + (motion ? "a subtle trail follows the moving thumb only inside the track, behind it, leaves the thumb/icon pixels untouched and clears within ~0.2 s; "
                : "disabled motion draws and records no trail; ") + "cancel, refused release, rejection report, fast reversal and disconnect mid-drag settle with no ghost, in View.draw and real screencap frames; accessibility node unchanged");
    }

    private void collectSliders(View view, List<View> out) {
        if (view.getClass().getName().endsWith("$LockControl") && view.isShown()) out.add(view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) collectSliders(((ViewGroup)view).getChildAt(i), out);
    }
    private float pos(View slider, float p) { return centre(slider, p); }
    private float centre(View slider, float p) { AtomicReference<Float> x = new AtomicReference<>(); Runnable r = () -> x.set(((LockSliderGeometry)invoke(slider, "sliderGeometry")).centerFor(p)); if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) r.run(); else main(r); return x.get(); }
    private void touch(View slider, int action, float x, float y, long down) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        try { slider.onTouchEvent(event); } finally { event.recycle(); }
    }
    /** [changed pixels, max change, outside track, inside thumb, ahead of thumb] between two drawn frames. */
    private int[] trailStats(View slider, Bitmap still, Bitmap moving, float thumbX, float radius) {
        RectF track = new RectF(); main(() -> track.set((RectF)field(slider, "track")));
        int w = still.getWidth(), h = still.getHeight(), cy = h / 2;
        int[] a = new int[w * h], b = new int[w * h]; still.getPixels(a, 0, w, 0, 0, w, h); moving.getPixels(b, 0, w, 0, 0, w, h);
        int[] out = new int[5];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int d = delta(a[y * w + x], b[y * w + x]);
            if (d <= 1) continue;
            out[0]++; out[1] = Math.max(out[1], d);
            float px = x + .5f, py = y + .5f;
            if (!track.contains(px, py)) out[2]++;
            float dx = px - thumbX, dy = py - cy;
            if (dx * dx + dy * dy < (radius - 1.5f) * (radius - 1.5f)) out[3]++;
            if (px > thumbX + radius + 1) out[4]++;
        }
        return out;
    }
    private static int changed(Bitmap a, Bitmap b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return Integer.MAX_VALUE;
        int w = a.getWidth(), h = a.getHeight(), count = 0;
        int[] x = new int[w * h], y = new int[w * h]; a.getPixels(x, 0, w, 0, 0, w, h); b.getPixels(y, 0, w, 0, 0, w, h);
        for (int i = 0; i < x.length; i++) if (delta(x[i], y[i]) > 1) count++;
        return count;
    }
    /** After the settle animation: no motion, no trail drawn, identical to the resting unlocked slider. */
    private void settled(View slider, Bitmap rest, String label) {
        SystemClock.sleep(450); settle();
        main(() -> {
            android.animation.ValueAnimator motion = (android.animation.ValueAnimator)field(slider, "motion");
            require((motion == null || !motion.isRunning()) && !(Boolean)field(slider, "dragging") && ((Number)field(slider, "position")).floatValue() == 0, label + ": did not settle to the confirmed unlocked state");
            Bitmap now = draw(slider);
            require(changed(rest, now) == 0, label + ": left a ghost (" + changed(rest, now) + " pixels differ from rest)");
        });
    }
    private String accessibility(View slider) {
        AtomicReference<String> text = new AtomicReference<>();
        main(() -> {
            AccessibilityNodeInfo info = slider.createAccessibilityNodeInfo();
            try {
                ArrayList<String> actions = new ArrayList<>();
                for (AccessibilityNodeInfo.AccessibilityAction action : info.getActionList()) actions.add(Integer.toString(action.getId()));
                Collections.sort(actions);
                text.set(info.getClassName() + " checkable=" + info.isCheckable() + " checked=" + info.isChecked() + " enabled=" + info.isEnabled()
                        + " clickable=" + info.isClickable() + " desc=" + info.getContentDescription()
                        + (Build.VERSION.SDK_INT >= 30 ? " state=" + info.getStateDescription() : "") + " actions=" + actions);
            } finally { info.recycle(); }
        });
        return text.get();
    }

    private void hardwareDrag(View slider, String name) throws Exception {
        int[] box = new int[4]; RectF track = new RectF();
        main(() -> {
            if (!fullyVisible(slider)) { int[] at = new int[2]; slider.getLocationOnScreen(at); scroll().scrollBy(0, at[1] - viewport().top - ui().dp(8)); }
        });
        settle();
        main(() -> require(fullyVisible(slider), "Slider is not fully on screen"));
        settle();
        main(() -> { int[] at = new int[2]; slider.getLocationOnScreen(at); box[0] = at[0]; box[1] = at[1]; box[2] = slider.getWidth(); box[3] = slider.getHeight(); track.set((RectF)field(slider, "track")); });
        int m = 6;
        Bitmap rest = screencap();
        int[] restCrop = crop(rest, box[0] - m, box[1] - m, box[2] + 2 * m, box[3] + 2 * m);
        float y = box[1] + box[3] / 2f, left = box[0] + centre(slider, .1f), right = box[0] + centre(slider, .85f);
        float startX = box[0] + centre(slider, 0);
        AtomicBoolean stop = new AtomicBoolean();
        long down = SystemClock.uptimeMillis();
        inject(MotionEvent.ACTION_DOWN, startX, y, down);
        Thread mover = new Thread(() -> {
            while (!stop.get()) {
                double t = (SystemClock.uptimeMillis() - down) / 640.0;
                float x = (float)(left + (right - left) * (.5 - .5 * Math.cos(2 * Math.PI * t)));
                inject(MotionEvent.ACTION_MOVE, x, y, down);
                SystemClock.sleep(8);
            }
        });
        mover.start();
        int outsideTrack = 0, insideChanged = 0;
        try {
            SystemClock.sleep(350);
            for (int i = 0; i < 4; i++) {
                Bitmap frame = screencap();
                save(frame, name + "-hw-drag-" + i);
                saveCrop(frame, box[0] - m, box[1] - m, box[2] + 2 * m, box[3] + 2 * m, 3, name + "-hw-drag-zoom-" + i);
                int[] now = crop(frame, box[0] - m, box[1] - m, box[2] + 2 * m, box[3] + 2 * m);
                int w = box[2] + 2 * m;
                for (int k = 0; k < now.length; k++) {
                    float px = k % w - m + .5f, py = k / w - m + .5f;
                    int d = delta(now[k], restCrop[k]);
                    if (track.contains(px, py)) { if (d > 3) insideChanged++; }
                    else if (d > 3) outsideTrack++;
                }
                SystemClock.sleep(120);
            }
        } finally {
            stop.set(true); mover.join(2000);
            // Release back on the confirmed (unlocked) side: no command is sent.
            inject(MotionEvent.ACTION_MOVE, startX, y, down); SystemClock.sleep(30);
            inject(MotionEvent.ACTION_UP, startX, y, down);
        }
        SystemClock.sleep(600); settle();
        Bitmap after = screencap();
        save(after, name + "-hw-after-release");
        int[] afterCrop = crop(after, box[0] - m, box[1] - m, box[2] + 2 * m, box[3] + 2 * m);
        int ghost = 0; for (int k = 0; k < afterCrop.length; k++) if (delta(afterCrop[k], restCrop[k]) > 3) ghost++;
        notes.add(name + " hardware drag: changed track pixels " + insideChanged + ", outside track " + outsideTrack + ", after release " + ghost);
        main(() -> require(Boolean.FALSE.equals(field(slider, "value")), name + ": hardware drag changed the confirmed value"));
        require(insideChanged > 0, name + ": injected drag did not move the thumb in screencap frames");
        require(outsideTrack == 0, name + ": drag changed " + outsideTrack + " screencap pixels outside the track");
        require(ghost == 0, name + ": screencap after release differs from rest by " + ghost + " pixels");
    }
    private void inject(int action, float x, float y, long down) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { getUiAutomation().injectInputEvent(event, action != MotionEvent.ACTION_MOVE); } finally { event.recycle(); }
    }

    // ---------------------------------------------------------------- shared helpers

    private Bitmap draw(View view) {
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        return bitmap;
    }
    /** The real `screencap` tool, run as shell: the composited hardware-rendered display. */
    private Bitmap screencap() throws Exception {
        ParcelFileDescriptor pipe = getUiAutomation().executeShellCommand("screencap -p");
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pipe)) {
            Bitmap bitmap = BitmapFactory.decodeStream(in);
            require(bitmap != null, "screencap returned no image");
            return bitmap.getConfig() == Bitmap.Config.ARGB_8888 ? bitmap : bitmap.copy(Bitmap.Config.ARGB_8888, false);
        }
    }
    private void shell(String command) throws Exception {
        ParcelFileDescriptor pipe = getUiAutomation().executeShellCommand(command);
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pipe)) { byte[] buffer = new byte[4096]; while (in.read(buffer) >= 0) { } }
    }
    private static int[] crop(Bitmap frame, int x, int y, int w, int h) {
        require(x >= 0 && y >= 0 && x + w <= frame.getWidth() && y + h <= frame.getHeight(), "Crop leaves the screencap");
        int[] out = new int[w * h]; frame.getPixels(out, 0, w, x, y, w, h); return out;
    }
    private void saveCrop(Bitmap frame, int x, int y, int w, int h, int scale, String name) throws Exception {
        x = Math.max(0, x); y = Math.max(0, y); w = Math.min(w, frame.getWidth() - x); h = Math.min(h, frame.getHeight() - y);
        Bitmap part = Bitmap.createBitmap(frame, x, y, w, h);
        Bitmap big = Bitmap.createScaledBitmap(part, w * scale, h * scale, false);
        save(big, name);
    }
    private void save(Bitmap bitmap, String name) throws Exception {
        File file = new File(folder, variant + "-" + name + ".png");
        try (FileOutputStream out = new FileOutputStream(file)) { require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out), "PNG failed"); }
        screenshots.add(file.getName());
    }
    private void reading(Map<String, TelemetryHistory.Reading> values, String key, String text, String unit, double value, long wall, boolean live) {
        values.put(key, new TelemetryHistory.Reading(key, key, "Synthetic fixture", text, unit, value, wall, live));
    }
    private void stopUiPulse() {
        main(() -> {
            ((Handler)field(activity, "handler")).removeCallbacks((Runnable)field(activity, "pulse"));
            invoke(activity, "cancelDeferredBodyRefresh");
            if (activity.service() != null) activity.service().unlisten((Runnable)field(activity, "serviceChanged"));
        });
    }
    private void settleMotion() {
        waitForIdleSync(); SystemClock.sleep(260);
        long end = SystemClock.elapsedRealtime() + 4000;
        while (true) {
            AtomicReference<Boolean> moving = new AtomicReference<>(false);
            main(() -> moving.set(screen() != null && field(screen(), "editTransition") != null));
            if (!moving.get()) break;
            require(SystemClock.elapsedRealtime() < end, "Editor transition did not finish");
            SystemClock.sleep(16);
        }
        waitForIdleSync();
    }
    private DashboardScreen screen() { Object screen = field(activity, "screen"); return screen instanceof DashboardScreen ? (DashboardScreen)screen : null; }
    private ViewGroup grid() { return (ViewGroup)field(screen(), "grid"); }
    private View root() { return (View)field(activity, "root"); }
    private Ui ui() { return (Ui)field(screen(), "ui"); }
    private View hero() { return (View)field(screen(), "chargingHero"); }
    private android.animation.ValueAnimator pulse() { AtomicReference<Object> a = new AtomicReference<>(); Runnable r = () -> a.set(field(hero(), "pulse")); if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) r.run(); else main(r); return (android.animation.ValueAnimator)a.get(); }
    private float glow() { AtomicReference<Float> g = new AtomicReference<>(); Runnable r = () -> g.set((Float)field(hero(), "glow")); if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) r.run(); else main(r); return g.get(); }
    private List<?> tiles() { return (List<?>)field(screen(), "tiles"); }
    private Object tileOrNull(String type) {
        if (screen() == null) return null;
        for (Object item : tiles()) if (((DashboardConfig.Widget)field(item, "widget")).type.equals(type)) return item;
        return null;
    }
    private SharedPreferences settings() { return getTargetContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE); }
    private void check(String text) { checks.add(text); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(250); waitForIdleSync(); }
    private void await(BooleanSupplier condition, String message) {
        long end = SystemClock.elapsedRealtime() + 12000;
        while (SystemClock.elapsedRealtime() < end) { AtomicReference<Boolean> ready = new AtomicReference<>(false); main(() -> ready.set(condition.getAsBoolean())); if (ready.get()) return; SystemClock.sleep(50); }
        throw new AssertionError(message);
    }
    private void main(Runnable action) { AtomicReference<Throwable> error = new AtomicReference<>(); runOnMainSync(() -> { try { action.run(); } catch (Throwable t) { error.set(t); } }); if (error.get() != null) throw new RuntimeException(error.get().getMessage(), error.get()); }
    private static Object field(Object target, String name) {
        if (target == null) return null;
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass())
            try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch (NoSuchFieldException next) { } catch (Exception error) { throw new IllegalStateException(name, error); }
        throw new IllegalStateException("Field " + name);
    }
    private static void setField(Object target, String name, Object value) { try { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target, value); } catch (Exception error) { throw new IllegalStateException("Field " + name, error); } }
    private static Object invoke(Object target, String name) { return invoke(target, name, new Class<?>[0]); }
    private static Object invoke(Object target, String name, Class<?>[] types, Object... values) { try { Method m = target.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(target, values); } catch (Exception error) { throw new IllegalStateException("Method " + name, error); } }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
