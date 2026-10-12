package local.navee.offlinelock;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Local preferences, owner setup, security and explained experimental controls. */
public final class MoreScreen implements AppScreen {
    private final ScreenHost host;
    private final Ui ui;
    private final ScrollView scroll;
    private final LinearLayout content;
    private LinearLayout cardDestination, onboardingCard, sectionMenu;
    private TextView sectionBack;
    private static final String[] SECTIONS = {"App", "Scooters", "Security", "Lab", "About"};
    private final Map<String,LinearLayout> sections = new LinkedHashMap<>();
    private final Map<String,Integer> sectionScrollPositions = new LinkedHashMap<>();
    private String selectedSection;
    private boolean closed;
    private final List<Runnable> valueRefreshers = new ArrayList<>();
    private final List<AlertDialog> dialogs = new ArrayList<>();
    private final List<View> profileEditActions = new ArrayList<>();
    private TextView renameProfileButton, forgetProfileButton, backupProfileButton, disconnectProfileButton;
    private TextView profileSummary, pinSummary, bondSummary, securityNotice, setupState;
    private TextView readPinButton, setPinButton, bondButton;
    private Switch pinToggle, proximityToggle;
    private TextView labSummary, connectionSummary;
    private final List<LabRow> labRows = new ArrayList<>();
    private static final class LabRow {
        final ScooterCommands.Feature feature;
        final TextView value, action;
        LabRow(ScooterCommands.Feature feature, TextView value, TextView action) {
            this.feature = feature; this.value = value; this.action = action;
        }
    }

    public MoreScreen(ScreenHost host) {
        this.host = host; ui = host.ui();
        scroll = new ScrollView(host.activity());
        scroll.setFillViewport(true); scroll.setClipToPadding(false);
        content = column(); content.setPadding(ui.dp(18), ui.dp(6), ui.dp(18), ui.dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        cardDestination = content;
        onboarding();
        selectedSection = settings().getString("more_section", "");
        if (!knownSection(selectedSection)) selectedSection = "";
        sectionBack = ui.secondaryButton("‹  More settings", () -> selectSection("")); content.addView(sectionBack);
        sectionMenu = ui.card(); sectionMenu.setPadding(ui.dp(18), ui.dp(3), ui.dp(18), ui.dp(3)); content.addView(sectionMenu);
        String[] labels = {"App preferences", "My scooters", "PIN & proximity unlock", "Experimental controls", "About & permissions"};
        String[] details = {"", "", "", "Lab", ""};
        for (int i = 0; i < SECTIONS.length; i++) {
            final String section = SECTIONS[i];
            if (i > 0) ui.divider(sectionMenu);
            sectionMenu.addView(ui.settingRow(labels[i], details[i], () -> selectSection(section)));
        }
        for (String section : SECTIONS) {
            cardDestination = column(); sections.put(section, cardDestination);
            content.addView(cardDestination, new LinearLayout.LayoutParams(-1, -2));
            if (section.equals("App")) preferences();
            else if (section.equals("Scooters")) profiles();
            else if (section.equals("Security")) security();
            else if (section.equals("Lab")) lab();
            else privacy();
        }
        cardDestination = content;
        showSelectedSection();
        refresh();
    }
    @Override public View view() { return scroll; }
    @Override public void close() {
        closed = true;
        for (AlertDialog dialog : new ArrayList<>(dialogs)) if (dialog.isShowing()) dialog.dismiss();
        dialogs.clear();
    }

    public boolean back() { if (selectedSection.isEmpty()) return false; selectSection(""); return true; }

    private boolean knownSection(String section) {
        for (String candidate : SECTIONS) if (candidate.equals(section)) return true;
        return false;
    }
    private void showSelectedSection() {
        sectionMenu.setVisibility(selectedSection.isEmpty() ? View.VISIBLE : View.GONE);
        sectionBack.setVisibility(selectedSection.isEmpty() ? View.GONE : View.VISIBLE);
        for (Map.Entry<String,LinearLayout> section : sections.entrySet())
            section.getValue().setVisibility(section.getKey().equals(selectedSection) ? View.VISIBLE : View.GONE);
    }
    private void selectSection(String section) {
        if (closed || (!section.isEmpty() && !knownSection(section)) || section.equals(selectedSection)) return;
        sectionScrollPositions.put(selectedSection, scroll.getScrollY());
        selectedSection = section;
        settings().edit().putString("more_section", section).apply();
        showSelectedSection(); refresh();
        final int position = sectionScrollPositions.containsKey(section) ? sectionScrollPositions.get(section) : 0;
        scroll.post(() -> { if (!closed && section.equals(selectedSection)) scroll.scrollTo(0, position); });
    }

    private ScooterService service() { return host.service(); }
    private ScooterProfiles.Profile active() { return service() == null ? null : service().profiles.active(); }
    private SharedPreferences settings() { return service().settings; }
    private LinearLayout column() {
        LinearLayout view = new LinearLayout(host.activity()); view.setOrientation(LinearLayout.VERTICAL); return view;
    }
    private void heading(String title, String detail) {
        TextView h = ui.text(title, 27, true); h.setPadding(ui.dp(2), 0, 0, ui.dp(8)); content.addView(h);
        TextView d = ui.caption(detail); d.setPadding(ui.dp(2), 0, 0, ui.dp(20)); content.addView(d);
    }
    private LinearLayout card(String title, String detail) {
        LinearLayout c = ui.card();
        TextView name = ui.text(title, 19, true); c.addView(name);
        if (detail != null && !detail.isEmpty()) {
            TextView note = ui.caption(detail); note.setPadding(0, ui.dp(8), 0, ui.dp(10)); c.addView(note);
        }
        cardDestination.addView(c); return c;
    }
    private void note(LinearLayout box, String value) {
        TextView text = ui.caption(value); text.setPadding(0, ui.dp(7), 0, ui.dp(7)); box.addView(text);
    }
    private TextView button(LinearLayout box, String label, Runnable action, boolean primary) {
        TextView view = primary ? ui.button(label, action) : ui.text(label, 15, false);
        if (!primary) {
            view.setTextColor(ui.text); view.setGravity(Gravity.CENTER_VERTICAL); view.setMinHeight(ui.dp(52));
            view.setPadding(ui.dp(2), ui.dp(10), ui.dp(2), ui.dp(10)); view.setFocusable(true);
            view.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Ui.withAlpha(ui.accent, 24)), ui.shape(android.graphics.Color.TRANSPARENT, 10, 0), null));
            view.setOnClickListener(v -> { if (action != null) action.run(); });
            if (box.getChildCount() > 0) ui.divider(box);
        }
        box.addView(view); return view;
    }
    private TextView value(LinearLayout box, String label) {
        TextView text = ui.text(label, 15, true); text.setTextColor(ui.accent);
        text.setPadding(0, ui.dp(10), 0, ui.dp(10)); box.addView(text); return text;
    }

    private void onboarding() {
        LinearLayout c = card("Your scooter. Your data.",
                "One private owner setup connects your already activated scooter directly over Bluetooth.");
        onboardingCard = c;
        setupState = value(c, "Add an owner profile to begin");
        note(c, "1  Sign into NAVEE with your own scooter account.\n"
                + "2  Capture the Bluetooth owner identity once using a computer.\n"
                + "3  Import the private setup file, allow Nearby devices, and connect.");
        button(c, "Open first-time setup guide", this::setupGuide, true);
        profileEditActions.add(button(c, "Import my owner setup", () -> { if (canEditProfiles()) host.importOwner(); }, false));
        button(c, "Allow Nearby devices", host::requestBluetooth, false);
    }

    private void profiles() {
        LinearLayout c = card("My scooters", null);
        profileSummary = value(c, "No scooter profile");
        connectionSummary = ui.caption(""); c.addView(connectionSummary);
        disconnectProfileButton = button(c, "Disconnect scooter", () -> {
            if (service().isRecording()) { host.toast("Stop the ride recording before disconnecting to change profiles."); return; }
            service().ble.disconnect(); refresh();
        }, false);
        profileEditActions.add(button(c, "Choose a scooter", this::chooseProfile, true));
        profileEditActions.add(button(c, "Add with owner identity", this::addProfile, false));
        profileEditActions.add(button(c, "Import owner setup file", () -> { if (canEditProfiles()) host.importOwner(); }, false));
        renameProfileButton = button(c, "Rename active scooter", this::renameProfile, false);
        backupProfileButton = button(c, "Save private owner backup", this::backupProfile, false);
        forgetProfileButton = button(c, "Forget active profile", this::forgetProfile, false);
        button(c, "First-time setup guide", this::setupGuide, false);
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
        box.addView(ui.text(title, 22, true)); note(box, detail); return box;
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
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(ui.shape(ui.surface, 22, ui.outline));
        if (dialog.getButton(AlertDialog.BUTTON_POSITIVE) != null) dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ui.accent);
        if (dialog.getButton(AlertDialog.BUTTON_NEGATIVE) != null) dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(ui.muted);
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
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setTitle("Forget " + p.name + "?")
                .setMessage("Remove its private setup from this phone. Keep a private backup if you need to reconnect later. Recorded rides stay saved.")
                .setNegativeButton("Keep profile", null).setPositiveButton("Forget profile", (d, which) -> {
                    if (!canEditProfiles()) return; service().profiles.remove(p.id); host.redraw();
                }).create(); show(dialog);
    }

    private void preferences() {
        LinearLayout c = card("Appearance", null);
        preferenceChoice(c, "Appearance", "theme", new String[]{"Dark", "Follow system", "Light"},
                new String[]{"dark", "system", "light"}, "dark");
        preferenceChoice(c, "Distance & speed", "units", new String[]{"Miles / mph", "Kilometers / km/h", "Show both"},
                new String[]{"mi", "km", "dual"}, "mi");
        preferenceChoice(c, "Stats detail", "stats_density", new String[]{"Comfortable", "Dense"},
                new String[]{"comfortable", "dense"}, "comfortable");
        ui.divider(c); LinearLayout text = ui.settingRow("Text size", "", this::textSize); c.addView(text);
        valueRefreshers.add(() -> ((TextView)text.getChildAt(1)).setText(Math.round(settings().getFloat("text_scale", 1f) * 100) + "%"));
        LinearLayout connection = card("Connection & recording", null);
        LinearLayout rate = ui.settingRow("Telemetry refresh", "", this::telemetryRate); connection.addView(rate);
        valueRefreshers.add(() -> ((TextView)rate.getChildAt(1)).setText(settings().getInt("poll_interval_ms", 2000) / 1000 + " seconds"));
        preferenceToggle(connection, "Connect automatically", "When the app opens", "auto_connect", true);
        preferenceToggle(connection, "Stay connected in background", "When off, idle connections close after 10 seconds. Recordings keep running.", "background_connection", false);
        preferenceToggle(connection, "Keep screen awake", "While a recording is on screen", "keep_screen_awake", true);
    }
    private void preferenceChoice(LinearLayout box, String title, String key, String[] labels, String[] values, String fallback) {
        if (box.getChildCount() > 1) ui.divider(box);
        LinearLayout row = ui.settingRow(title, "", () -> {
            String stored = settings().getString(key, fallback); int selected = 0;
            for (int i = 0; i < values.length; i++) if (stored.equals(values[i])) selected = i;
            AlertDialog dialog = new AlertDialog.Builder(host.activity()).setTitle(title)
                    .setSingleChoiceItems(labels, selected, (d, choice) -> {
                        settings().edit().putString(key, values[choice]).apply(); d.dismiss();
                        if (key.equals("theme") || key.equals("stats_density")) host.rebuild(); else host.redraw();
                    }).setNegativeButton("Close", null).create(); show(dialog);
        });
        box.addView(row); TextView amount = (TextView)row.getChildAt(1);
        valueRefreshers.add(() -> {
            String stored = settings().getString(key, fallback), shown = labels[0];
            for (int i = 0; i < values.length; i++) if (stored.equals(values[i])) shown = labels[i];
            amount.setText(shown);
        });
    }
    private void preferenceToggle(LinearLayout box, String title, String detail, String key, boolean fallback) {
        ui.divider(box);
        View row = ui.toggleRow(title, detail, settings().getBoolean(key, fallback), true, enabled -> {
            settings().edit().putBoolean(key, enabled).apply(); host.redraw();
        });
        row.setPadding(ui.dp(2), ui.dp(12), ui.dp(2), ui.dp(12)); row.setBackground(null);
        row.setLayoutParams(new LinearLayout.LayoutParams(-1, -2)); box.addView(row);
        Switch toggle = switchOf(row);
        valueRefreshers.add(() -> refreshSwitch(toggle, settings().getBoolean(key, fallback), true));
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
        String[] labels = {"Fast · every 2 seconds", "Balanced · every 5 seconds", "Battery saver · every 10 seconds"};
        int stored = settings().getInt("poll_interval_ms", 2000), selected = 0;
        for (int i = 0; i < intervals.length; i++) if (stored == intervals[i]) selected = i;
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setTitle("Battery telemetry refresh")
                .setSingleChoiceItems(labels, selected, (d, choice) -> {
                    settings().edit().putInt("poll_interval_ms", intervals[choice]).apply();
                    d.dismiss(); refresh(); host.toast("Ride recording always keeps its faster 2-second battery refresh.");
                }).setNegativeButton("Close", null).create(); show(dialog);
    }

    private void security() {
        LinearLayout c = card("Security & fallback", "Your handlebar PIN gives you a phone-free way to unlock. PIN digits never enter stats, recordings, or diagnostic exports.");
        pinSummary = value(c, "PIN state has not been read");
        readPinButton = button(c, "Read PIN state", () -> { service().ble.readPin(); refresh(); }, false);
        setPinButton = button(c, "Set or change PIN", this::setPin, true);
        note(c, "Read state reports whether a PIN is configured and enabled. It does not show your digits.");
        View pinRow = ui.toggleRow("Handlebar PIN", "Enable your configured fallback PIN.", null, false,
                enabled -> { service().ble.enablePin(enabled); host.toast(service().ble.message()); refresh(); });
        c.addView(pinRow); pinToggle = switchOf(pinRow);
        View nearRow = ui.toggleRow("Proximity unlock", "Pair this phone and enable its PIN fallback before turning proximity unlock on.", null, false,
                enabled -> { service().ble.set("proximity_key", enabled ? 1 : 0); host.toast(service().ble.message()); refresh(); });
        c.addView(nearRow); proximityToggle = switchOf(nearRow);
        bondSummary = ui.caption(""); c.addView(bondSummary);
        bondButton = button(c, "Pair this phone with scooter", () -> { service().ble.bond(); host.toast(service().ble.message()); refresh(); }, false);
        securityNotice = ui.caption(""); c.addView(securityNotice);
        button(c, "How to unlock with handlebar PIN", this::pinGuide, false);
    }
    private Switch switchOf(View row) {
        ViewGroup group = (ViewGroup) row;
        for (int i = 0; i < group.getChildCount(); i++) if (group.getChildAt(i) instanceof Switch)
            return (Switch) group.getChildAt(i);
        throw new IllegalStateException("Toggle row did not provide its native switch");
    }
    private void refreshSwitch(Switch toggle, Boolean state, boolean enabled) {
        toggle.setChecked(Boolean.TRUE.equals(state));
        toggle.setEnabled(enabled && state != null);
        if (Build.VERSION.SDK_INT >= 30) toggle.setStateDescription(state == null ? "Not reported" : state ? "On" : "Off");
        ViewGroup row = (ViewGroup) toggle.getParent();
        View words = row.getChildAt(0);
        if (words instanceof ViewGroup && ((ViewGroup) words).getChildCount() > 0) {
            View heading = ((ViewGroup) words).getChildAt(0);
            if (heading instanceof TextView) {
                TextView label = (TextView) heading;
                label.setTextColor(enabled && state != null ? ui.text : ui.muted);
                toggle.setContentDescription(label.getText() + (state == null ? ", state not reported" : ""));
            }
        }
    }
    private boolean ownerReady() {
        ScooterProfiles.Profile p = active();
        return service() != null && service().ble.ready() && p != null && p.role == 0 && p.pid() == 2538;
    }
    private void setPin() {
        if (!ownerReady()) { host.toast("Connect as the scooter owner before changing its PIN."); return; }
        LinearLayout box = dialogBox("Set handlebar PIN", "Choose four digits you can remember. The scooter confirms the saved PIN before the app reports success.");
        int input = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        EditText pin = input("New four-digit PIN", input, false), repeated = input("Repeat PIN", input, false);
        pin.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
        repeated.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)}); box.addView(pin); box.addView(repeated);
        TextView error = ui.caption(""); error.setTextColor(ui.danger); box.addView(error);
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setView(box).setNegativeButton("Cancel", null)
                .setPositiveButton("Set PIN", null).create(); show(dialog, () -> { pin.setText(""); repeated.setText(""); });
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String digits = pin.getText().toString();
            if (!digits.matches("[0-9]{4}")) { error.setText("Enter exactly four numeric digits."); return; }
            if (!digits.equals(repeated.getText().toString())) { error.setText("The two entries do not match."); return; }
            if (!ownerReady()) { error.setText("Reconnect as the scooter owner first."); return; }
            if (service().ble.pending() != null) { error.setText("Wait for the current scooter change to finish."); return; }
            service().ble.setPin(digits);
            if ("pin".equals(service().ble.pending())) {
                host.toast(service().ble.message()); dialog.dismiss();
            } else error.setText("PIN change did not start. " + service().ble.message());
            refresh();
        });
    }
    private void pinGuide() {
        LinearLayout box = dialogBox("Phone-free fallback", "Use the PIN you configured while the scooter is parked and electronically locked.");
        note(box, "1  Briefly press a turn-signal button to enter PIN unlock.\n\n"
                + "2  Adjust each digit using the handlebar buttons. Briefly squeeze a brake lever to confirm each digit.\n\n"
                + "3  After all four digits, press the power button to confirm and unlock.");
        note(box, "With proximity unlock configured, bring your paired phone close and squeeze a brake lever. Verify both fallback methods while parked before relying on them.");
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setView(box).setPositiveButton("Got it", null).create(); show(dialog);
    }

    private void lab() {
        LinearLayout c = card("Firmware lab", "Explore explained OEM commands hidden from the UT5 MAX interface. Every change still needs a fresh vehicle readback.");
        labSummary = value(c, "Connect to inspect available fields");
        note(c, "An exposed status field is a candidate for testing, not proof the feature works. Unreported fields stay unavailable. Nothing here writes arbitrary commands or changes firmware.");
        for (ScooterCommands.Feature f : ScooterCommands.catalog()) {
            if (!f.experimental) continue;
            LinearLayout row = column(); row.setPadding(0, ui.dp(12), 0, ui.dp(12));
            row.addView(ui.text(f.label, 16, true)); note(row, f.description);
            TextView state = ui.caption(""); row.addView(state);
            TextView action = button(row, "Inspect " + f.label.toLowerCase(Locale.ROOT), () -> experimental(f), false);
            c.addView(row); labRows.add(new LabRow(f, state, action));
        }
        button(c, "View command evidence", this::commandEvidence, false);
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

    private void privacy() {
        LinearLayout c = card("Navee Offline", "Scooter controls and ride recording connect directly over Bluetooth. No account or server login is required.");
        note(c, "Owner setup, scooter profiles, preferences, and ride recordings stay on this phone. You choose when to export them. Ordinary diagnostics leave out owner identity and PIN digits; a private owner backup intentionally includes its setup identity.");
        note(c, "Nearby devices connects to your scooter. Optional location records your route during a ride. Phone motion and pressure sensors run only while recording. The recording notification includes a Stop action.");
        note(c, "Street maps load from OpenStreetMap only when you enable Street map on a route. GPS and the recorded route still work without a map connection. Loading tiles sends the map area and your network address to the tile provider.");
        if (Build.VERSION.SDK_INT < 31) note(c, "Older Android versions require location permission for Bluetooth discovery.");
        button(c, "Nearby device permission", host::requestBluetooth, false);
        button(c, "Connect selected scooter", host::connect, true);
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

    @Override public void refresh() {
        ScooterService s = service(); if (s == null) return;
        for (Runnable refresh : valueRefreshers) refresh.run();
        ScooterProfiles.Profile p = active();
        boolean configured = p != null && p.configured();
        onboardingCard.setVisibility(configured ? View.GONE : View.VISIBLE);
        boolean profilesEditable = !s.isRecording() && s.ble.state() == BleController.State.DISCONNECTED && s.ble.pending() == null;
        for (View action : profileEditActions) action.setEnabled(profilesEditable);
        renameProfileButton.setEnabled(profilesEditable && p != null);
        forgetProfileButton.setEnabled(profilesEditable && p != null);
        backupProfileButton.setEnabled(configured);
        disconnectProfileButton.setEnabled(!s.isRecording() && s.ble.state() != BleController.State.DISCONNECTED);
        if (profileSummary != null) profileSummary.setText(p == null ? "No scooter profile"
                : p.name + "  ·  " + (p.role == 0 ? "Owner" : "Shared") + "\n"
                    + service().profiles.all().size() + " local " + (service().profiles.all().size() == 1 ? "profile" : "profiles")
                    + (p.serial.isEmpty() ? " · connect to verify scooter" : " · model " + p.pid()));
        if (setupState != null) setupState.setText(p != null && p.configured() ? "Owner profile ready · connect next" : "Add an owner profile to begin");
        connectionSummary.setText(s.isRecording() ? "Ride recording is active. Stop it before changing profiles."
                : s.ble.state() == BleController.State.DISCONNECTED ? "Profiles can be changed while disconnected."
                : "Disconnect before selecting, adding, or editing owner profiles.");
        ScooterCommands.PinState pin = s.ble.pinState(); boolean owner = ownerReady(), busy = s.ble.pending() != null;
        pinSummary.setText(pin == null ? "PIN state has not been read"
                : pin.status != 0 ? "PIN state was rejected by the scooter"
                : !pin.configured ? "No fallback PIN configured"
                : Boolean.TRUE.equals(pin.enabled) ? "Fallback PIN enabled" : "Fallback PIN disabled");
        readPinButton.setEnabled(owner && !busy); setPinButton.setEnabled(owner && !busy);
        refreshSwitch(pinToggle, pin == null || pin.status != 0 ? null : pin.enabled,
                owner && !busy && pin != null && pin.status == 0 && pin.configured);
        Integer proximity = s.ble.featureValue("proximity_key"); boolean paired = s.ble.bonded();
        boolean enablePrerequisites = paired && pin != null && pin.status == 0 && pin.configured && Boolean.TRUE.equals(pin.enabled);
        refreshSwitch(proximityToggle, proximity == null ? null : proximity == 1,
                owner && !busy && proximity != null && (proximity == 1 || enablePrerequisites));
        bondSummary.setText(paired ? "This phone is paired with the connected scooter."
                : "Proximity unlock needs a Bluetooth pairing and enabled handlebar PIN.");
        bondButton.setEnabled(owner && !busy && !paired && pin != null && pin.status == 0 && pin.configured && Boolean.TRUE.equals(pin.enabled));
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
