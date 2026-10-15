package local.navee.offlinelock;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.util.function.Consumer;

/** Map privacy/provider preferences shared by App preferences and ride rendering. */
public final class MapPreferences {
    private MapPreferences() { }
    static boolean enabled(SharedPreferences settings) { return settings.getBoolean("map_basemap_enabled", false); }

    /** One remembered choice, shared by inline maps, expanded maps and app preferences. */
    static View basemapControl(ScreenHost host, Consumer<Boolean> changed) {
        return new BasemapControl(host, changed);
    }

    private static final class BasemapControl extends LinearLayout {
        private final ScreenHost host;
        private final SharedPreferences settings;
        private final Consumer<Boolean> changed;
        private final Switch toggle;
        private boolean updating, listening;
        private Boolean shown;
        private AlertDialog prompt;
        private final SharedPreferences.OnSharedPreferenceChangeListener preferenceChanged = (p, key) -> {
            if (key == null || key.equals("map_basemap_enabled")) refresh();
        };

        BasemapControl(ScreenHost host, Consumer<Boolean> changed) {
            super(host.activity());
            this.host = host; this.settings = host.service().settings; this.changed = changed;
            Ui ui = host.ui();
            setOrientation(HORIZONTAL); setGravity(Gravity.CENTER_VERTICAL);
            setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4));
            toggle = new Haptics.FeedbackSwitch(host.activity()); toggle.setText("Basemap");
            toggle.setTextColor(ui.text); toggle.setTextSize(15 * ui.textScale);
            toggle.setMinHeight(ui.dp(48)); toggle.setContentDescription("Basemap");
            ui.styleSwitch(toggle); addView(toggle, new LinearLayout.LayoutParams(-1, -2));
            refresh();
            toggle.setOnCheckedChangeListener((button, on) -> {
                if (updating) return;
                if (!on) {
                    settings.edit().putBoolean("map_basemap_enabled", false).apply();
                    refresh();
                } else if (settings.getBoolean("map_basemap_explained", false)) {
                    settings.edit().putBoolean("map_basemap_enabled", true).apply();
                    refresh();
                } else {
                    // Do not show an enabled state or start tile requests before approval.
                    refresh();
                    explain();
                }
            });
        }

        private void refresh() {
            boolean on = enabled(settings);
            updating = true; toggle.setChecked(on); updating = false;
            if (shown == null || shown != on) {
                shown = on;
                if (changed != null) changed.accept(on);
            }
        }

        private void explain() {
            if (!isAttachedToWindow() || (prompt != null && prompt.isShowing())) return;
            prompt = consent(host, this::refresh, dialog -> { if (prompt == dialog) prompt = null; refresh(); });
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            settings.registerOnSharedPreferenceChangeListener(preferenceChanged); listening = true;
            refresh();
        }

        @Override protected void onDetachedFromWindow() {
            if (listening) { settings.unregisterOnSharedPreferenceChangeListener(preferenceChanged); listening = false; }
            if (prompt != null) prompt.dismiss();
            super.onDetachedFromWindow();
        }
    }

    /** The one copy of the map privacy fact; approval enables tiles and is remembered. */
    private static AlertDialog consent(ScreenHost host, Runnable approved, Consumer<AlertDialog> closed) {
        Ui ui = host.ui(); SharedPreferences settings = host.service().settings;
        LinearLayout body = new LinearLayout(host.activity()); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(ui.dp(24), ui.dp(20), ui.dp(24), ui.dp(12));
        body.addView(ui.text("Show online map?", 21, true));
        TextView message = ui.text("Provider sees your IP and map area. Rides stay on phone.", 15, false);
        message.setPadding(0, ui.dp(12), 0, 0); body.addView(message);
        ScrollView scroll = new ScrollView(host.activity()); scroll.addView(body);
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setView(scroll)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Enable basemap", (d, which) -> {
                    settings.edit().putBoolean("map_basemap_enabled", true)
                            .putBoolean("map_basemap_explained", true).apply();
                    approved.run();
                }).create();
        dialog.setOnDismissListener(d -> closed.accept(dialog));
        dialog.getWindow().setBackgroundDrawable(ui.shape(ui.surface, 22, ui.outline));
        Motion.present(dialog, Motion.DIALOG); dialog.show();
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE}) {
            dialog.getButton(which).setTextColor(ui.accent);
            dialog.getButton(which).setTypeface(ui.semibold());
        }
        return dialog;
    }

    /**
     * Map settings: one segmented control for Off and each provider. Picking a provider turns the
     * basemap on (after the one-time consent); Off turns it off. Custom shows its editor button.
     */
    private static final class ProviderControl extends LinearLayout {
        private static final String[] LABELS = {"Off", "USGS (US)", "OSM", "Custom"};
        private final ScreenHost host;
        private final SharedPreferences settings;
        private final SegmentedSelector selector;
        private final TextView edit;
        private AlertDialog prompt;
        private boolean listening;
        private final SharedPreferences.OnSharedPreferenceChangeListener preferenceChanged = (p, key) -> {
            if (key == null || key.startsWith("map_")) refresh();
        };

        ProviderControl(ScreenHost host) {
            super(host.activity());
            this.host = host; this.settings = host.service().settings;
            Ui ui = host.ui();
            setOrientation(VERTICAL);
            selector = new SegmentedSelector(ui, "Online map", LABELS, this::chose);
            // Single-line labels of one size share the track's height, so every value sits centred;
            // at very large text the track scrolls rather than wrapping one label.
            LinearLayout.LayoutParams selectorParams = new LinearLayout.LayoutParams(-1, -2); selectorParams.topMargin = ui.dp(12);
            addView(selector, selectorParams);
            edit = ui.secondaryButton("Edit custom provider", () -> custom(host, this::customClosed));
            addView(edit);
            refresh();
        }

        private int current() {
            if (!enabled(settings)) return 0;
            String url = MapSource.load(settings).url;
            return MapSource.USGS_URL.equals(url) ? 1 : MapSource.OSM_URL.equals(url) ? 2 : 3;
        }

        private boolean customSaved() {
            String url = MapSource.load(settings).url;
            return settings.contains("map_tile_url") && !MapSource.USGS_URL.equals(url) && !MapSource.OSM_URL.equals(url);
        }

        void refresh() {
            int chosen = current();
            selector.select(chosen, isAttachedToWindow());
            edit.setVisibility(chosen == 3 ? View.VISIBLE : View.GONE);
        }

        private void chose(int choice) {
            if (choice == 0) { settings.edit().putBoolean("map_basemap_enabled", false).apply(); refresh(); return; }
            if (choice == 3 && !customSaved()) { custom(host, this::customClosed); return; }
            if (choice == 1) MapSource.USGS.save(settings);
            else if (choice == 2) MapSource.OSM.save(settings);
            enable();
        }

        private void customClosed() { if (customSaved()) enable(); else refresh(); }

        private void enable() {
            if (settings.getBoolean("map_basemap_explained", false)) {
                settings.edit().putBoolean("map_basemap_enabled", true).apply(); refresh(); return;
            }
            // Do not show an enabled state or start tile requests before approval.
            if (!isAttachedToWindow() || (prompt != null && prompt.isShowing())) { refresh(); return; }
            prompt = consent(host, this::refresh, dialog -> { if (prompt == dialog) prompt = null; refresh(); });
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            settings.registerOnSharedPreferenceChangeListener(preferenceChanged); listening = true;
            refresh();
        }

        @Override protected void onDetachedFromWindow() {
            if (listening) { settings.unregisterOnSharedPreferenceChangeListener(preferenceChanged); listening = false; }
            if (prompt != null) prompt.dismiss();
            super.onDetachedFromWindow();
        }
    }

    public static AlertDialog show(ScreenHost host) {
        Ui ui = host.ui();
        LinearLayout body = new LinearLayout(host.activity()); body.setOrientation(1);
        body.setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(20));
        body.addView(ui.text("Map settings", 22, true));
        body.addView(new ProviderControl(host));
        ScrollView scroll = new ScrollView(host.activity()); scroll.addView(body);
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setView(scroll).setPositiveButton("Done", null).create();
        dialog.getWindow().setBackgroundDrawable(ui.shape(ui.surface, 22, ui.outline)); Motion.present(dialog, Motion.DIALOG); dialog.show();
        return dialog;
    }
    private static void custom(ScreenHost host, Runnable saved) {
        Ui ui = host.ui(); SharedPreferences settings = host.service().settings; MapSource source = MapSource.load(settings);
        LinearLayout body = new LinearLayout(host.activity()); body.setOrientation(1); body.setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(16));
        body.addView(ui.text("Custom map provider", 20, true));
        EditText url = field(host, body, "Tile URL", source.url); url.setHint("https://…/{z}/{x}/{y}.png");
        EditText credit = field(host, body, "Attribution", source.attribution);
        EditText link = field(host, body, "HTTPS attribution link", source.attributionUrl);
        ScrollView scroll = new ScrollView(host.activity()); scroll.addView(body);
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setView(scroll).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        dialog.setOnDismissListener(d -> saved.run());
        dialog.getWindow().setBackgroundDrawable(ui.shape(ui.surface, 22, ui.outline)); Motion.present(dialog, Motion.DIALOG); dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = url.getText().toString().trim(), text = credit.getText().toString().trim(), address = link.getText().toString().trim();
            if (!MapTiles.validUrl(value)) { url.setError("Use HTTPS with {z}, {x} and {y}."); return; }
            if (text.isEmpty()) { credit.setError("Add the provider’s required attribution."); return; }
            try { java.net.URI uri = new java.net.URI(address); if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalArgumentException(); }
            catch (Exception e) { link.setError("Use an HTTPS license or copyright link."); return; }
            new MapSource("Custom map", value, text, address).save(settings); dialog.dismiss();
        });
    }
    private static EditText field(ScreenHost host, LinearLayout parent, String label, String value) {
        Ui ui = host.ui(); TextView caption = ui.caption(label); caption.setPadding(0, ui.dp(12), 0, ui.dp(6)); parent.addView(caption);
        EditText field = new EditText(host.activity()); field.setText(value); field.setTextColor(ui.text); field.setHintTextColor(ui.muted); field.setTextSize(14 * ui.textScale);
        field.setBackground(ui.shape(ui.bg, 12, ui.outline)); field.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12)); field.setMinHeight(ui.dp(48)); parent.addView(field); return field;
    }
}
