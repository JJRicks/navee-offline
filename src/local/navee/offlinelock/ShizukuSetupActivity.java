package local.navee.offlinelock;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Explicit, optional phone-only setup. An unexported Activity, separate from normal BLE operation. */
public final class ShizukuSetupActivity extends Activity {
    public static final int REQUEST = 203;
    private Ui ui;
    private LinearLayout content;
    private ShizukuCaptureClient capture;
    private boolean consent;
    private android.window.OnBackInvokedCallback systemBack;
    private final Runnable update = this::render;
    @Override public void onCreate(Bundle saved) {
        SharedPreferences settings = getSharedPreferences("app_settings", MODE_PRIVATE);
        String theme = settings.getString("theme", "dark");
        boolean dark = "dark".equals(theme) || ("system".equals(theme)
                && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
        setTheme(dark ? R.style.VoltDark : R.style.VoltLight);
        super.onCreate(saved);
        if (Build.VERSION.SDK_INT >= 33) {
            systemBack = this::onBackPressed;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, systemBack);
        }
        ui = new Ui(this, dark, settings.getFloat("text_scale", 1f));
        capture = ShizukuCaptureClient.get(this);
        render();
    }
    @Override protected void onResume() { super.onResume(); capture.listen(update); render(); }
    @Override protected void onPause() { capture.unlisten(update); super.onPause(); }
    @Override protected void onDestroy() {
        if (Build.VERSION.SDK_INT >= 33 && systemBack != null) getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(systemBack);
        capture.unlisten(update);
        if (isFinishing()) { capture.cancel(); capture.discard(); }
        super.onDestroy();
    }
    @Override public void onBackPressed() {
        if (capture.busy) {
            new AlertDialog.Builder(this).setTitle("Stop setup?")
                    .setMessage("The helper will stop capture and restore NAVEE logging. Stay here until cleanup finishes.")
                    .setNegativeButton("Keep waiting", null).setPositiveButton("Stop setup", (d, w) -> capture.cancel()).show();
        } else super.onBackPressed();
    }
    private void render() {
        if (isFinishing() || isDestroyed()) return;
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(ui.bg);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(ui.dp(24), ui.dp(18), ui.dp(24), ui.dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = Build.VERSION.SDK_INT >= 30 ? insets.getInsets(WindowInsets.Type.systemBars()) : null;
            scroll.setPadding(bars == null ? 0 : bars.left, bars == null ? insets.getSystemWindowInsetTop() : bars.top,
                    bars == null ? 0 : bars.right, bars == null ? insets.getSystemWindowInsetBottom() : bars.bottom);
            return insets;
        });
        setContentView(scroll); scroll.requestApplyInsets();
        text("Phone-only setup", 28, true);
        if (android.os.Process.myUid() >= 100000) {
            text("This setup supports the phone's owner profile. Open Volt and NAVEE there, or import a private owner backup. Work and secondary Android profiles are not supported by this capture method.", 16, false);
            button("Back to setup choices", this::finish); return;
        }
        if (Build.VERSION.SDK_INT < 30) {
            text("Wireless debugging setup needs Android 11 or newer. Use the computer helper or import a backup on this phone.", 16, false);
            button("Back to setup choices", this::finish); return;
        }
        if (capture.busy) {
            String title = capture.phase == OwnerCaptureEngine.CONNECT_NAVEE ? "Connect your scooter in NAVEE"
                    : capture.phase == OwnerCaptureEngine.CLEANING ? "Restoring NAVEE logging…" : "Preparing capture…";
            text(title, 22, true);
            text(capture.phase == OwnerCaptureEngine.CONNECT_NAVEE
                    ? "NAVEE is opening. Connect your parked scooter there, then return to Volt. Capture stops after 90 seconds; cleanup may take a few more seconds."
                    : "Keep Shizuku running until cleanup finishes. You can cancel capture at any time.", 16, false);
            button("Cancel capture", capture::cancel); return;
        }
        if (capture.pendingCleanup()) {
            text("Finish cleanup first", 22, true);
            text("A previous capture ended before cleanup was confirmed. NAVEE may still have temporary logging enabled. Start Shizuku and grant access, then restore the saved setting. This stops NAVEE and changes only its logging property.", 16, false);
            status(); setupLinks();
            if (!capture.permitted()) permissionButton();
            else button("Restore NAVEE logging", () -> capture.start(true));
            button("Back", this::finish); return;
        }
        if (capture.owner != null) {
            text("Ready to import", 22, true);
            text("A valid " + (capture.owner.role == 1 ? "shared-user" : "owner")
                    + " Bluetooth identity was found. NAVEE is stopped and its previous logging setting is restored.", 16, false);
            text("Import adds or selects this owner profile in Volt. Your existing profiles stay available. The identity stays on this phone; it is not your NAVEE password.", 16, false);
            button("Import owner setup", () -> {
                if (capture.approveImport()) { setResult(RESULT_OK); finish(); }
            });
            button("Discard", capture::discard);
            text("After importing, you can revoke Volt in Shizuku › Authorized applications and turn off wireless debugging. Normal scooter use does not need Shizuku.", 14, false);
            return;
        }
        status();
        if (!capture.available()) {
            text("1 of 3 · Start Shizuku", 20, true);
            text("Install Shizuku, enable Developer options and Wireless debugging, then pair and start Shizuku on this phone. Follow its official guide for your Android version.", 16, false);
            text("Shizuku needs to run only for setup. It must be started again after a reboot. This flow uses debugging access; root-backed Shizuku is not supported.", 14, false);
            setupLinks(); permissionButton();
            button("Back to setup choices", this::finish); return;
        }
        if (!capture.permitted()) {
            text("2 of 3 · Allow temporary access", 20, true);
            text("Shizuku grants Volt broad Android debugging access. Volt uses it here only to restart NAVEE, temporarily enable its debug logging, read matching Bluetooth-authentication messages and restore the old setting. Nothing is uploaded.", 16, false);
            text("Granting access does not start capture. You choose when to begin on the next screen. You can revoke Volt in Shizuku afterward.", 15, false);
            permissionButton();
            button("Shizuku help", () -> open("https://shizuku.rikka.app/guide/setup/"));
            button("Back to setup choices", this::finish); return;
        }
        text("3 of 3 · Capture from your scooter", 20, true);
        text("Sign into NAVEE first and make sure it connects to your parked, powered-on scooter. Starting capture restarts NAVEE and reads only new matching authentication messages for up to 90 seconds. A separate import confirmation follows.", 16, false);
        CheckBox agree = new CheckBox(this); agree.setText("I own this scooter or have its owner's permission. Allow this capture and automatic cleanup.");
        agree.setTextColor(ui.text); agree.setTextSize(15 * ui.textScale); agree.setChecked(consent);
        agree.setPadding(0, ui.dp(12), 0, ui.dp(12)); content.addView(agree);
        TextView start = button("Start capture", () -> { if (consent) { consent = false; capture.start(false); } });
        start.setEnabled(consent); start.setAlpha(consent ? 1f : .45f);
        agree.setOnCheckedChangeListener((view, checked) -> {
            consent = checked; start.setEnabled(checked); start.setAlpha(checked ? 1f : .45f);
        });
        text("Cleanup runs automatically on success, cancel and timeout. Keep Shizuku running until it finishes. A separate safety timer restores logging if Volt stops unexpectedly; return here to verify cleanup before retrying.", 14, false);
        button("Review debugging access", () -> new AlertDialog.Builder(this).setTitle("Temporary debugging access")
                .setMessage("Shizuku grants broad Android debugging access. This helper only reads NAVEE's matching authentication messages, restarts NAVEE, and temporarily changes then restores its logging setting. Nothing is uploaded. The captured owner identity is held in memory until you choose to import or discard it. You can revoke Volt in Shizuku afterward.")
                .setPositiveButton("Close", null).show());
        button("Back to setup choices", this::finish);
    }

    private void setupLinks() {
        button("Shizuku setup guide", () -> open("https://shizuku.rikka.app/guide/setup/"));
        button("Install Shizuku", () -> open("https://shizuku.rikka.app/download/"));
        button("Open Shizuku", () -> {
            Intent launch = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
            if (launch != null) startActivity(launch); else open("https://shizuku.rikka.app/download/");
        });
    }
    private void permissionButton() {
        TextView grant = button(capture.available() ? "Allow Shizuku access" : "Check Shizuku connection", () -> {
            if (capture.available()) capture.requestPermission(); else { capture.message = "Start Shizuku with wireless debugging, then return here. Shizuku 13 or newer is required."; render(); }
        });
        grant.setContentDescription(capture.available() ? "Request broad debugging access from Shizuku; does not start capture" : "Check whether Shizuku is running");
    }
    private void status() { if (!capture.message.isEmpty()) text(capture.message, 15, true); }
    private void open(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (RuntimeException ignored) { capture.message = "No browser is available to open the official guide."; render(); }
    }
    private void text(String value, float size, boolean bold) {
        TextView view = ui.text(value, size, bold); view.setLineSpacing(ui.dp(3), 1);
        if (!bold) view.setTextColor(ui.muted);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = ui.dp(16);
        content.addView(view, p);
        if (bold && size >= 20 && Build.VERSION.SDK_INT >= 28) view.setAccessibilityHeading(true);
    }
    private TextView button(String label, Runnable action) {
        TextView button = ui.button(label, action); button.setGravity(Gravity.CENTER); button.setMinHeight(ui.dp(52));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = ui.dp(12);
        content.addView(button, p); return button;
    }
}
