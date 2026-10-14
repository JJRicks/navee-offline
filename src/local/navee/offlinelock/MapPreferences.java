package local.navee.offlinelock;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** Map privacy/provider preferences shared by App preferences and ride rendering. */
public final class MapPreferences {
    private MapPreferences() { }
    static boolean enabled(SharedPreferences settings) { return settings.getBoolean("map_basemap_enabled", true); }
    public static AlertDialog show(ScreenHost host) {
        Ui ui = host.ui(); SharedPreferences settings = host.service().settings;
        LinearLayout body = new LinearLayout(host.activity()); body.setOrientation(1);
        body.setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(20));
        body.addView(ui.text("Map settings", 22, true));
        Switch basemap = new Switch(host.activity()); basemap.setText("Show basemap");
        basemap.setTextColor(ui.text); basemap.setTextSize(16 * ui.textScale); basemap.setMinHeight(ui.dp(56));
        basemap.setChecked(enabled(settings)); ui.styleSwitch(basemap);
        basemap.setOnCheckedChangeListener((v, on) -> settings.edit().putBoolean("map_basemap_enabled", on).apply());
        body.addView(basemap);
        TextView caption = ui.caption("Map tiles download for the area you view. Your recorded route stays on this phone. Without a basemap, the route and its readings still work offline.");
        caption.setPadding(0, 0, 0, ui.dp(14)); body.addView(caption);
        MapSource source = MapSource.load(settings);
        TextView current = ui.caption(source.name); current.setPadding(0, ui.dp(10), 0, ui.dp(12));
        SegmentedSelector[] holder = new SegmentedSelector[1];
        Runnable refresh = () -> { MapSource chosen = MapSource.load(settings); current.setText(chosen.name); if (holder[0] != null) holder[0].select(MapSource.USGS_URL.equals(chosen.url) ? 0 : MapSource.OSM_URL.equals(chosen.url) ? 1 : 2, true); };
        SegmentedSelector selector = new SegmentedSelector(ui, "Map source", new String[]{"USGS", "OpenStreetMap", "Custom"}, choice -> {
            if (choice == 2) { custom(host, refresh); return; }
            MapSource selected = choice == 0 ? MapSource.USGS : MapSource.OSM; selected.save(settings); current.setText(selected.name);
        });
        holder[0] = selector;
        selector.select(MapSource.USGS_URL.equals(source.url) ? 0 : MapSource.OSM_URL.equals(source.url) ? 1 : 2, false);
        body.addView(selector); body.addView(current);
        body.addView(ui.caption("USGS Topo covers the United States. Other regions can use OpenStreetMap or an HTTPS provider with the required attribution."));
        TextView edit = ui.secondaryButton("Edit custom provider", () -> custom(host, refresh));
        body.addView(edit);
        ScrollView scroll = new ScrollView(host.activity()); scroll.addView(body);
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setView(scroll).setPositiveButton("Done", null).create();
        dialog.getWindow().setBackgroundDrawable(ui.shape(ui.surface, 22, ui.outline)); Motion.present(dialog, Motion.DIALOG); dialog.show();
        return dialog;
    }
    private static void custom(ScreenHost host, Runnable saved) {
        Ui ui = host.ui(); SharedPreferences settings = host.service().settings; MapSource source = MapSource.load(settings);
        LinearLayout body = new LinearLayout(host.activity()); body.setOrientation(1); body.setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(16));
        body.addView(ui.text("Custom map provider", 20, true));
        EditText url = field(host, body, "HTTPS tile URL · {z}, {x}, {y}", source.url);
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
        EditText field = new EditText(host.activity()); field.setText(value); field.setTextColor(ui.text); field.setTextSize(14 * ui.textScale);
        field.setBackground(ui.shape(ui.bg, 12, ui.outline)); field.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12)); field.setMinHeight(ui.dp(48)); parent.addView(field); return field;
    }
}
