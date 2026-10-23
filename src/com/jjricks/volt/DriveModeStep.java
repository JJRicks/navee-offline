package com.jjricks.volt;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.function.Consumer;

/**
 * The optional "name your drive modes" step before a compatibility report is emailed. The owner
 * presses the scooter's own mode button; Volt only polls the reported mode (read-only) and asks
 * what each one is called. done receives the survey, or null when the owner skips.
 */
final class DriveModeStep {
    private static final DriveModeSurvey.Label[] CHOICES = {DriveModeSurvey.Label.WALK, DriveModeSurvey.Label.ECO, DriveModeSurvey.Label.DRIVE, DriveModeSurvey.Label.SPORT};
    private final Activity activity; private final Ui ui; private final BleController ble; private final Consumer<DriveModeSurvey> done;
    private DriveModeSurvey survey = new DriveModeSurvey();
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** A reading older than this, or from before the scooter was last seen disconnected, no longer shows the live mode. */
    private static final long FRESH_MS = 5000;
    private long lostMs = Long.MIN_VALUE;
    private final TextView[] chips = new TextView[CHOICES.length];
    private TextView prompt, reported, named, unsure, finish, skip;
    private LinearLayout body, choices; private View pulseDot; private ObjectAnimator pulse;
    private AlertDialog dialog; private boolean finished; private long lastRefreshMs;

    DriveModeStep(Activity activity, Ui ui, BleController ble, Consumer<DriveModeSurvey> done) {
        this.activity = activity; this.ui = ui; this.ble = ble; this.done = done;
    }

    /** The live drive mode: connected now, read in the last few seconds and since any disconnection; null otherwise. */
    private Integer reading() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (!ble.ready()) { lostMs = now; return null; }
        for (Telemetry.Metric m : ble.telemetry.metrics()) {
            if (!m.key.equals("driving_mode") || m.numericValue == null || m.updatedMs < 0) continue;
            if (now - m.updatedMs > FRESH_MS || m.updatedMs <= lostMs) return null;
            double v = m.numericValue;
            return v == Math.rint(v) && v >= 0 && v <= 255 ? (int) v : null;
        }
        return null;
    }

    private TextView heading(String text) {
        TextView title = ui.text(text, 22, true);
        if (android.os.Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        return title;
    }

    AlertDialog show() {
        body = new LinearLayout(activity); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(ui.dp(22), ui.dp(22), ui.dp(22), ui.dp(10));
        build();
        android.widget.ScrollView scroll = new android.widget.ScrollView(activity); scroll.addView(body);
        dialog = new AlertDialog.Builder(activity).setView(scroll).create();
        dialog.setOnDismissListener(d -> { handler.removeCallbacksAndMessages(null); if (pulse != null) pulse.cancel(); if (!finished) { finished = true; done.accept(null); } });
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(ui.shape(ui.surface, 22, ui.outline));
        dialog.show();
        begin();
        return dialog;
    }

    /** The asking screen: live card, the four mode chips, Not sure, and Skip / Done. */
    private void build() {
        body.removeAllViews();
        body.addView(heading("Name your drive modes"));
        TextView why = ui.caption("Optional, about a minute. Stay parked: you'll press your scooter's own mode button, and Volt only watches which mode it reports.");
        why.setPadding(0, ui.dp(6), 0, ui.dp(12)); body.addView(why);

        LinearLayout live = new LinearLayout(activity); live.setOrientation(LinearLayout.VERTICAL); live.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14));
        live.setBackground(ui.shape(ui.raised, 18, 0));
        LinearLayout watching = new LinearLayout(activity); watching.setGravity(Gravity.CENTER_VERTICAL);
        pulseDot = new View(activity); pulseDot.setBackground(ui.shape(ui.accent, 100, 0));
        watching.addView(pulseDot, new LinearLayout.LayoutParams(ui.dp(9), ui.dp(9)));
        reported = ui.caption("Watching your scooter…"); reported.setPadding(ui.dp(8), 0, 0, 0); watching.addView(reported);
        live.addView(watching);
        prompt = ui.text("", 18, true); prompt.setPadding(0, ui.dp(8), 0, 0); prompt.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); prompt.setTag("drive-mode-prompt");
        live.addView(prompt);
        body.addView(live);

        choices = new LinearLayout(activity); choices.setOrientation(LinearLayout.VERTICAL); choices.setPadding(0, ui.dp(12), 0, 0);
        for (int row = 0; row < 2; row++) {
            LinearLayout line = new LinearLayout(activity);
            for (int col = 0; col < 2; col++) {
                int i = row * 2 + col; DriveModeSurvey.Label label = CHOICES[i];
                TextView chip = ui.chip(label.text, false, () -> choose(label));
                chip.setTextSize(16 * ui.textScale); chip.setMinHeight(ui.dp(52)); chip.setTag("drive-mode-" + label.name().toLowerCase(java.util.Locale.ROOT));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
                lp.setMargins(col == 0 ? 0 : ui.dp(5), row == 0 ? 0 : ui.dp(10), col == 0 ? ui.dp(5) : 0, 0);
                line.addView(chip, lp); chips[i] = chip;
            }
            choices.addView(line);
        }
        unsure = ui.text(DriveModeSurvey.Label.UNSURE.text, 14, false); unsure.setTextColor(ui.accent); unsure.setTypeface(ui.medium());
        unsure.setGravity(Gravity.CENTER); unsure.setMinHeight(ui.dp(44)); unsure.setFocusable(true); unsure.setClickable(true);
        unsure.setOnClickListener(v -> choose(DriveModeSurvey.Label.UNSURE)); unsure.setTag("drive-mode-unsure");
        choices.addView(unsure);
        body.addView(choices);
        named = ui.caption(""); named.setPadding(0, ui.dp(4), 0, 0); named.setTag("drive-mode-named"); body.addView(named);

        LinearLayout buttons = new LinearLayout(activity); buttons.setPadding(0, ui.dp(12), 0, ui.dp(12));
        skip = ui.secondaryButton("Skip", () -> close(null)); skip.setTag("drive-mode-skip");
        finish = ui.button("Done", this::confirm); finish.setTag("drive-mode-done");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1); lp.rightMargin = ui.dp(6);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -2, 1); rp.leftMargin = ui.dp(6);
        buttons.addView(skip, lp); buttons.addView(finish, rp); body.addView(buttons);
    }

    private void begin() {
        if (ValueAnimator.areAnimatorsEnabled()) {
            pulse = ObjectAnimator.ofFloat(pulseDot, View.ALPHA, 1f, 0.25f); pulse.setDuration(900);
            pulse.setRepeatMode(ValueAnimator.REVERSE); pulse.setRepeatCount(ValueAnimator.INFINITE); pulse.start();
        }
        refresh(); handler.post(this::tick);
    }

    /** Follows the scooter: pulls a fresh settings read now and then and asks when the mode changes. */
    private void tick() {
        if (finished) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastRefreshMs > 1500) { lastRefreshMs = now; ble.refresh(false); }
        Integer code = reading();
        if (code != null) survey.observe(code);
        if (survey.closed()) { confirm(); return; }
        refresh();
        handler.postDelayed(this::tick, 400);
    }

    /** An answer counts only for the mode the scooter is reporting right now, the one on screen. */
    private void choose(DriveModeSurvey.Label label) {
        Integer code = reading(), asking = survey.asking();
        if (code != null && code.equals(asking) && survey.name(label)) activity.getWindow().getDecorView().performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);
        refresh();
    }

    private void refresh() {
        Integer code = reading(), asking = survey.asking();
        // Naming waits for a live reading of the mode being asked about: never while disconnected or stale.
        boolean live = code != null && code.equals(asking);
        reported.setText(!ble.ready() ? "Scooter disconnected" : code == null ? "Waiting for your scooter's mode…" : "Your scooter reports mode " + code);
        if (!ble.ready()) prompt.setText("Reconnect your scooter to keep going.");
        else if (asking != null && !live) prompt.setText("Reading your scooter's mode…");
        else if (asking != null) prompt.setText(survey.count() == 0 ? "Which mode is your scooter in now?" : "And this mode?");
        else if (survey.count() == 0) prompt.setText("Connecting to your scooter's mode…");
        else prompt.setText("Press your scooter's mode button to switch to the next mode.");
        for (int i = 0; i < CHOICES.length; i++) {
            boolean used = survey.used(CHOICES[i]);
            chips[i].setText(used ? CHOICES[i].text + " ✓" : CHOICES[i].text);
            chips[i].setEnabled(live && !used); chips[i].setAlpha(live || used ? 1f : 0.5f);
            ui.styleChip(chips[i], used);
        }
        unsure.setEnabled(live); unsure.setAlpha(live ? 1f : 0.5f);
        named.setText(survey.count() == 0 ? "" : "Named: " + survey.summary());
        finish.setEnabled(survey.count() > 0); finish.setAlpha(survey.count() > 0 ? 1f : 0.5f);
    }

    /** "Eco → Drive → Sport · Look right?" before the names join the report. */
    private void confirm() {
        if (finished || survey.count() == 0) return;
        handler.removeCallbacksAndMessages(null); if (pulse != null) pulse.cancel();
        body.removeAllViews();
        body.addView(heading("Look right?"));
        TextView order = ui.text(survey.summary(), 20, true); order.setTextColor(ui.accent); order.setPadding(0, ui.dp(12), 0, ui.dp(4)); order.setTag("drive-mode-summary"); body.addView(order);
        TextView note = ui.caption(survey.closed() ? "Your scooter went through these modes in this order." : "These are the modes you named, in the order your scooter showed them.");
        body.addView(note);
        LinearLayout buttons = new LinearLayout(activity); buttons.setPadding(0, ui.dp(16), 0, ui.dp(12));
        TextView again = ui.secondaryButton("Start over", () -> { survey = new DriveModeSurvey(); build(); begin(); });
        TextView right = ui.button("Looks right", () -> close(survey)); right.setTag("drive-mode-confirm");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1); lp.rightMargin = ui.dp(6);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -2, 1); rp.leftMargin = ui.dp(6);
        buttons.addView(again, lp); buttons.addView(right, rp); body.addView(buttons);
    }

    private void close(DriveModeSurvey result) {
        if (finished) return;
        finished = true; dialog.dismiss(); done.accept(result);
    }
}
