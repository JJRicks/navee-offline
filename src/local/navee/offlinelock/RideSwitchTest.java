package local.navee.offlinelock;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;
import java.util.Locale;

/**
 * Lab test for switching Top speed from the handlebar. Part 1 logs which status bytes change while
 * the rider presses scooter buttons (read-only). Part 2 lets the rider try a Top speed change while
 * moving; it is off by default and turns itself off again when this screen closes.
 */
final class RideSwitchTest {
    private static final int LOW_KMH = 40, HIGH_KMH = 50;

    private RideSwitchTest() {}

    static void open(Activity activity, Ui ui, ScooterService service) {
        BleController ble = service.ble;
        String units = service.settings.getString("units", "mi");
        ScooterCommands.Feature topSpeed = ScooterCommands.find("maximum_speed");
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ui.dp(22), ui.dp(22), ui.dp(22), ui.dp(12));
        TextView heading = ui.text("Ride switch test", 22, true);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        box.addView(heading);
        TextView status = ui.text("", 13, true);
        status.setPadding(0, ui.dp(6), 0, 0);
        status.setAccessibilityLiveRegion(TextView.ACCESSIBILITY_LIVE_REGION_POLITE);
        box.addView(status);

        // ---- 1. Light button probe
        section(box, ui, "1 · Light button");
        note(box, ui, "Parked and connected: tap Start, wait a few seconds, then press the power button to switch "
                + "the lights on and off a few times, a few seconds apart. Do the same with the mode button for "
                + "comparison. Every status byte that changes is listed below, newest first.");
        TextView copy = ui.secondaryButton("Copy log", () -> {
            List<String> lines = ble.probeLog();
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Ride switch test", String.join("\n", lines)));
            Toast.makeText(activity, lines.isEmpty() ? "Nothing to copy yet." : "Log copied.", Toast.LENGTH_SHORT).show();
        });
        TextView probe = ui.button("Start", () -> ble.probe(!ble.probing()));
        pair(box, ui, copy, probe);
        TextView log = ui.text("", 12, false);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextIsSelectable(true);
        log.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        log.setBackground(ui.shape(ui.raised, 14, 0));
        log.setMinHeight(ui.dp(120));
        box.addView(log, new LinearLayout.LayoutParams(-1, -2));
        note(box, ui, "Battery, range, power, speed and trip bytes are left out because they change on their own.");

        // ---- 2. Top speed while moving
        section(box, ui, "2 · Top speed while moving");
        note(box, ui, "Test at low speed on a clear path with the phone mounted. Raising applies at once. Lowering "
                + "is only sent when you are already a little below the new top speed, so the scooter never has to "
                + "brake for it. This switch turns off again when you close this screen.");
        LinearLayout allowRow = new LinearLayout(activity);
        allowRow.setOrientation(LinearLayout.HORIZONTAL);
        allowRow.setGravity(Gravity.CENTER_VERTICAL);
        allowRow.setMinimumHeight(ui.dp(56));
        TextView allowLabel = ui.text("Allow while moving", 15, false);
        allowRow.addView(allowLabel, new LinearLayout.LayoutParams(0, -2, 1));
        Switch allow = new Haptics.FeedbackSwitch(activity);
        allow.setShowText(false); allow.setTextOn(""); allow.setTextOff("");
        allow.setMinimumWidth(ui.dp(56)); allow.setMinimumHeight(ui.dp(48));
        ui.styleSwitch(allow);
        allow.setContentDescription("Allow Top speed changes while moving");
        allow.setChecked(service.settings.getBoolean(BleController.LAB_MOVING_TOP_SPEED, false));
        allow.setOnCheckedChangeListener((view, on) ->
                service.settings.edit().putBoolean(BleController.LAB_MOVING_TOP_SPEED, on).apply());
        allowRow.addView(allow);
        box.addView(allowRow);
        TextView low = ui.secondaryButton(DisplayUnits.featureValue(topSpeed, LOW_KMH, units), () -> ble.set("maximum_speed", LOW_KMH));
        TextView high = ui.button(DisplayUnits.featureValue(topSpeed, HIGH_KMH, units), () -> ble.set("maximum_speed", HIGH_KMH));
        low.setContentDescription("Set Top speed to " + DisplayUnits.featureValue(topSpeed, LOW_KMH, units));
        high.setContentDescription("Set Top speed to " + DisplayUnits.featureValue(topSpeed, HIGH_KMH, units));
        pair(box, ui, low, high);
        TextView result = ui.text("", 13, false);
        result.setTextColor(ui.muted); result.setLineSpacing(ui.dp(2), 1);
        result.setAccessibilityLiveRegion(TextView.ACCESSIBILITY_LIVE_REGION_POLITE);
        box.addView(result);
        note(box, ui, "Copy the log and the result line and send them back.");

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(box);
        AlertDialog dialog = new AlertDialog.Builder(activity).setView(scroll).setPositiveButton("Close", null).create();
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable tick = new Runnable() {
            @Override public void run() {
                boolean on = ble.probing();
                int speed = ble.speedTenths();
                Integer cap = ble.featureValue("maximum_speed");
                status.setText((ble.ready() ? "Connected" : "Not connected · connect to the scooter first")
                        + (speed < 0 ? "" : " · " + DisplayUnits.speed(speed / 10.0, units))
                        + (cap == null ? "" : " · Top speed " + DisplayUnits.featureValue(topSpeed, cap, units))
                        + (on ? String.format(Locale.US, " · probing, %d s left", (ble.probeRemainingMs() + 999) / 1000) : ""));
                probe.setText(on ? "Stop" : "Start");
                List<String> lines = ble.probeLog();
                String text = lines.isEmpty() ? (on ? "Watching… press a button on the scooter." : "No changes logged yet.")
                        : String.join("\n", lines);
                if (!text.contentEquals(log.getText())) log.setText(text);
                String evidence = ble.commandResults().get("maximum_speed");
                String pending = ble.pending();
                String line = (pending != null ? "Sending… " : "") + "Last result: " + (evidence == null ? "none yet" : evidence)
                        + (ble.message() == null || ble.message().isEmpty() ? "" : "\n" + ble.message());
                if (!line.contentEquals(result.getText())) result.setText(line);
                handler.postDelayed(this, 300);
            }
        };
        dialog.setOnDismissListener(d -> {
            handler.removeCallbacks(tick);
            if (ble.probing()) ble.probe(false);
            service.settings.edit().putBoolean(BleController.LAB_MOVING_TOP_SPEED, false).apply();
        });
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(ui.shape(ui.surface, 22, ui.outline));
        dialog.show();
        if (dialog.getButton(AlertDialog.BUTTON_POSITIVE) != null) dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ui.accent);
        tick.run();
    }

    private static void section(LinearLayout box, Ui ui, String title) {
        TextView view = ui.text(title, 16, true);
        if (Build.VERSION.SDK_INT >= 28) view.setAccessibilityHeading(true);
        view.setPadding(0, ui.dp(18), 0, 0);
        box.addView(view);
    }

    private static void note(LinearLayout box, Ui ui, String text) {
        TextView view = ui.text(text, 13, false);
        view.setTextColor(ui.muted); view.setLineSpacing(ui.dp(2), 1);
        view.setPadding(0, ui.dp(6), 0, ui.dp(4));
        box.addView(view);
    }

    private static void pair(LinearLayout box, Ui ui, TextView left, TextView right) {
        LinearLayout row = new LinearLayout(box.getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, ui.dp(8), 0, ui.dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1); lp.rightMargin = ui.dp(6);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -2, 1); rp.leftMargin = ui.dp(6);
        row.addView(left, lp); row.addView(right, rp);
        box.addView(row);
    }
}
