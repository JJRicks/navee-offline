package local.navee.offlinelock;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Persistent local sessions, honest energy interpretation and useful comparisons. */
public final class RidesScreen implements AppScreen {
    private final ScreenHost host;
    private final Ui ui;
    private final ScooterService service;
    private final SharedPreferences preferences;
    private final ScrollView scroll;
    private final LinearLayout page, activeCard, history;
    private final TextView recordButton, connectButton, directionButton, compareButton, activeTitle,
            activeValues, activeQuality, historyCount;
    private final Ui.ChartView liveChart;
    private final Set<String> selected;
    private final LinkedHashMap<String, TextView> inlineCompare = new LinkedHashMap<String, TextView>();
    private final List<AlertDialog> dialogs = new ArrayList<AlertDialog>();
    private final SimpleDateFormat date = new SimpleDateFormat("EEE, MMM d · h:mm a", Locale.US);
    private String historySignature = "", chartKey;
    private int visibleHistory = 20;

    public RidesScreen(ScreenHost host) {
        this.host = host; ui = host.ui(); service = host.service(); preferences = service.settings;
        selected = new LinkedHashSet<String>(preferences.getStringSet("ride_comparison", new LinkedHashSet<String>()));
        chartKey = preferences.getString("ride_chart_key", "power_w");
        scroll = new ScrollView(host.activity()); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        page = vertical(); page.setPadding(ui.dp(18), ui.dp(22), ui.dp(18), ui.dp(24)); scroll.addView(page);
        page.addView(ui.text("Your rides, understood.", 25, true));
        TextView intro = ui.caption("Record locally with the screen on or off. Compare real distance and electrical readings, with the gaps accounted for.");
        intro.setPadding(0, ui.dp(8), 0, ui.dp(18)); page.addView(intro);

        LinearLayout actions = ui.card();
        recordButton = ui.button("Start a ride recording", this::toggleRecording); actions.addView(recordButton);
        connectButton = ui.secondaryButton("Connect to record", host::connect); actions.addView(connectButton);
        directionButton = ui.secondaryButton("Energy direction · Unconfirmed", this::chooseDirection); actions.addView(directionButton);
        TextView note = ui.caption("Default: measured absolute throughput. Net consumption needs a confirmed current direction. Health percentages do not establish usable capacity.");
        note.setPadding(0, ui.dp(7), 0, 0); actions.addView(note); page.addView(actions);

        activeCard = ui.card(); activeTitle = ui.text("Recording", 18, true); activeTitle.setTextColor(ui.accent); activeCard.addView(activeTitle);
        activeValues = ui.text("", 17, true); activeValues.setPadding(0, ui.dp(12), 0, ui.dp(10)); activeCard.addView(activeValues);
        activeQuality = ui.caption(""); activeCard.addView(activeQuality);
        activeCard.addView(ui.secondaryButton("Add recording notes", this::recordingNotes));
        addChartChoices(activeCard, key -> { chartKey = key; preferences.edit().putString("ride_chart_key", key).apply(); refreshActive(); });
        liveChart = ui.new ChartView(); activeCard.addView(liveChart); page.addView(activeCard);

        LinearLayout comparison = ui.card(); comparison.addView(ui.text("Sport, Boost and everything between", 17, true));
        TextView guidance = ui.caption("Label your rides clearly, then choose any two recordings below. Compare distance, battery change, energy per distance and measurement coverage. Match conditions before drawing conclusions.");
        guidance.setPadding(0, ui.dp(8), 0, ui.dp(10)); comparison.addView(guidance);
        compareButton = ui.secondaryButton("Choose two rides to compare", this::showComparison); comparison.addView(compareButton); page.addView(comparison);
        TextView title = ui.text("Saved on this phone", 20, true); title.setPadding(0, ui.dp(12), 0, ui.dp(6)); page.addView(title);
        historyCount = ui.caption(""); historyCount.setPadding(0, 0, 0, ui.dp(12)); page.addView(historyCount);
        history = vertical(); page.addView(history);
        refresh();
    }
    private LinearLayout vertical() { LinearLayout result = new LinearLayout(host.activity()); result.setOrientation(LinearLayout.VERTICAL); return result; }
    @Override public View view() { return scroll; }
    @Override public void refresh() {
        boolean recording = service.isRecording(), ready = service.ble.ready();
        recordButton.setText(recording ? service.recordingError() == null ? "Stop recording & save ride" : "Retry stop & save ride" : "Start a ride recording");
        recordButton.setEnabled(service.rides != null && (recording || ready));
        connectButton.setVisibility(!ready && !recording ? View.VISIBLE : View.GONE);
        directionButton.setText("Energy direction · " + directionLabel(currentDirection()));
        activeCard.setVisibility(recording ? View.VISIBLE : View.GONE); if (recording) refreshActive();
        refreshHistory(); updateComparisonActions();
    }
    private void toggleRecording() {
        if (service.isRecording()) {
            service.stopRide(); host.toast(service.isRecording() ? "Recording could not be stopped; the journal is still active." : "Ride saved locally."); refresh(); return;
        }
        if (service.rides == null) { host.toast("Ride storage is unavailable."); return; }
        if (!service.ble.ready()) { host.toast("Connect to the scooter before starting a recording."); return; }
        EditText label = input("Sport + Boost, evening hills…"); label.setText(preferences.getString("last_ride_label", "Sport + Boost"));
        show(new AlertDialog.Builder(host.activity()).setTitle("Name this ride")
                .setMessage("The label helps you compare modes or routes later. Sensor readings stay on this phone.")
                .setView(label).setNegativeButton("Cancel", null).setPositiveButton("Start recording", (dialog, which) -> {
                    String name = label.getText().toString().trim(); if (name.isEmpty()) name = "Ride";
                    preferences.edit().putString("last_ride_label", name).apply(); service.startRide(name); refresh();
                }).create());
    }
    private EditText input(String hint) {
        EditText input = new EditText(host.activity()); input.setSingleLine(true); input.setHint(hint);
        input.setTextColor(ui.text); input.setHintTextColor(ui.muted); input.setTextSize(16 * ui.textScale);
        input.setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(16)); return input;
    }
    private void recordingNotes() {
        if (service.rides == null) return;
        RideStore.Summary recording = service.rides.activeSummary(); if (recording == null) return;
        EditText notes = input("Mode, route, rider load or anything useful…"); notes.setSingleLine(false); notes.setMinLines(3); notes.setMaxLines(8);
        notes.setText(recording.notes);
        show(new AlertDialog.Builder(host.activity()).setTitle("Notes for " + recording.label).setView(notes)
                .setNegativeButton("Cancel", null).setPositiveButton("Save notes", (dialog, which) -> {
                    if (!recording.id.equals(service.rides.activeId())) { host.toast("This recording has ended; its notes were not changed."); return; }
                    try { service.rides.setNotes(notes.getText().toString()); host.toast("Recording notes saved locally."); }
                    catch (IOException e) { host.toast("Could not save the notes."); }
                }).create());
    }
    private RideStore.CurrentDirection currentDirection() {
        try { return RideStore.CurrentDirection.valueOf(preferences.getString("current_direction", "UNCONFIRMED")); }
        catch (Exception ignored) { return RideStore.CurrentDirection.UNCONFIRMED; }
    }
    private String directionLabel(RideStore.CurrentDirection direction) {
        return direction == RideStore.CurrentDirection.UNCONFIRMED ? "Unconfirmed"
                : direction == RideStore.CurrentDirection.POSITIVE_IS_DRAW ? "Positive current draws" : "Negative current draws";
    }
    private void chooseDirection() {
        String[] choices = {"Unconfirmed · absolute throughput", "Positive current = battery draw", "Negative current = battery draw"};
        show(new AlertDialog.Builder(host.activity()).setTitle("Interpret measured current")
                .setSingleChoiceItems(choices, currentDirection().ordinal(), (dialog, which) -> {
                    RideStore.CurrentDirection direction = RideStore.CurrentDirection.values()[which];
                    try {
                        if (service.rides != null) service.rides.setCurrentDirection(direction);
                        preferences.edit().putString("current_direction", direction.name()).apply();
                        dialog.dismiss(); host.toast(service.isRecording() ? "Applies to future measured intervals; earlier data stays unchanged." : "Energy interpretation saved."); refresh();
                    } catch (IOException e) { host.toast("Could not save energy interpretation."); }
                }).setNegativeButton("Close", null).setNeutralButton("How to choose", (dialog, which) ->
                    show(new AlertDialog.Builder(host.activity()).setTitle("Confirm before interpreting")
                        .setMessage("The sign bit tells us signed current, not its physical meaning. Leave this unconfirmed until charging and riding behavior establish which direction draws from the battery. Battery input while plugged in is charger input, not proof of regenerative braking. Changing this choice never changes scooter settings.")
                        .setPositiveButton("Got it", null).create())).create());
    }
    private void refreshActive() {
        if (service.rides == null) return;
        RideStore.Summary s = service.rides.activeSummary(); if (s == null) return;
        String error = service.recordingError();
        activeTitle.setText((error == null ? "● Recording · " : "Recording needs attention · ") + s.label);
        activeTitle.setTextColor(error == null ? ui.accent : ui.danger);
        long elapsed = Math.max(s.elapsedMs, System.currentTimeMillis() - s.startWallMs);
        activeValues.setText(duration(elapsed) + " elapsed  ·  " + distance(s.distanceKm)
                + "\n" + quantity(s.measuredThroughputWh, "Wh", 2) + " measured throughput"
                + "\n" + change(s.batteryDropPercent) + " battery change");
        activeQuality.setText(coverage(s) + "\n" + s.gapCount + " gaps  ·  " + s.sampleCount + " samples"
                + (service.ble.connected() ? "" : "\nConnection missing; this gap continues until reconnect or stop.")
                + (s.recovered ? "\nRecovered after recorder restart; the missing interval is excluded." : "")
                + (error == null ? "" : "\n\n" + error));
        activeQuality.setTextColor(error == null ? ui.muted : ui.danger);
        try { liveChart.set(points(service.rides.trend(s.id, 600), chartKey), chartUnit(chartKey), chartColor(chartKey), "Waiting for paired readings"); }
        catch (IOException e) { host.toast("Could not read the ride chart."); }
    }
    private void refreshHistory() {
        if (service.rides == null) { historyCount.setText("Ride storage unavailable."); return; }
        try {
            List<RideStore.Summary> summaries = service.rides.list();
            List<RideStore.Summary> stopped = new ArrayList<RideStore.Summary>();
            StringBuilder signature = new StringBuilder();
            for (RideStore.Summary s : summaries) if (!s.active) { stopped.add(s); signature.append(s.id).append(':').append(s.sampleCount).append(';'); }
            signature.append('|').append(visibleHistory);
            historyCount.setText(stopped.isEmpty() ? "Your first saved recording will appear here." : stopped.size() + " recordings · stored offline");
            if (signature.toString().equals(historySignature)) return;
            historySignature = signature.toString(); history.removeAllViews(); inlineCompare.clear();
            Set<String> available = new LinkedHashSet<String>(); for (RideStore.Summary s : stopped) available.add(s.id);
            if (selected.retainAll(available)) saveSelection();
            for (int i = 0; i < Math.min(visibleHistory, stopped.size()); i++) addHistory(stopped.get(i));
            if (stopped.size() > visibleHistory) history.addView(ui.secondaryButton("Show more recordings", () -> { visibleHistory += 20; refreshHistory(); }));
            if (stopped.isEmpty()) {
                LinearLayout empty = ui.card(); empty.addView(ui.icon("route", 32, ui.blue));
                TextView copy = ui.text("Start with the next ride", 18, true); copy.setPadding(0, ui.dp(10), 0, ui.dp(8)); empty.addView(copy);
                empty.addView(ui.caption("Connect, give the ride a label, and start recording. Each recording keeps its sensor evidence, gaps and notes.")); history.addView(empty);
            }
        } catch (IOException e) { historyCount.setText("Could not read saved rides."); }
    }
    private void addHistory(RideStore.Summary s) {
        LinearLayout card = ui.card(); TextView name = ui.text(s.label.isEmpty() ? "Ride" : s.label, 18, true); card.addView(name);
        TextView when = ui.caption(date.format(new Date(s.startWallMs)) + "  ·  " + duration(s.elapsedMs)); when.setPadding(0, ui.dp(7), 0, ui.dp(12)); card.addView(when);
        TextView metrics = ui.text(distance(s.distanceKm) + "  ·  " + quantity(s.measuredThroughputWh, "Wh", 2), 17, true); metrics.setTextColor(ui.accent); card.addView(metrics);
        TextView quality = ui.caption("Power coverage " + percent(s.electricalCoverage) + "  ·  " + change(s.batteryDropPercent) + " battery change"); quality.setPadding(0, ui.dp(8), 0, ui.dp(8)); card.addView(quality);
        LinearLayout actions = new LinearLayout(host.activity()); actions.setOrientation(LinearLayout.HORIZONTAL); actions.setGravity(Gravity.CENTER_VERTICAL);
        TextView details = ui.secondaryButton("View details", () -> showDetails(s.id)); actions.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
        CheckBox select = new CheckBox(host.activity()); select.setText("Compare"); select.setTextColor(ui.muted); select.setTextSize(12 * ui.textScale);
        select.setButtonTintList(android.content.res.ColorStateList.valueOf(ui.accent)); select.setChecked(selected.contains(s.id));
        select.setMinimumHeight(ui.dp(48)); select.setPadding(ui.dp(8), 0, 0, 0); select.setContentDescription("Compare " + s.label + " from " + date.format(new Date(s.startWallMs)));
        select.setOnClickListener(v -> {
            if (select.isChecked()) {
                if (selected.size() >= 2 && !selected.contains(s.id)) { select.setChecked(false); host.toast("Choose two rides at a time."); return; }
                selected.add(s.id);
            } else selected.remove(s.id);
            saveSelection(); updateComparisonActions();
        }); actions.addView(select); card.addView(actions);
        TextView compareHere = ui.secondaryButton("Compare these two rides", this::showComparison);
        compareHere.setVisibility(selected.size() == 2 && selected.contains(s.id) ? View.VISIBLE : View.GONE);
        card.addView(compareHere); inlineCompare.put(s.id, compareHere); history.addView(card);
    }
    private void updateComparisonActions() {
        compareButton.setEnabled(selected.size() == 2);
        compareButton.setText(selected.size() == 2 ? "Compare selected rides" : "Choose two rides to compare · " + selected.size() + "/2");
        for (java.util.Map.Entry<String, TextView> action : inlineCompare.entrySet())
            action.getValue().setVisibility(selected.size() == 2 && selected.contains(action.getKey()) ? View.VISIBLE : View.GONE);
    }
    private void saveSelection() { preferences.edit().putStringSet("ride_comparison", new LinkedHashSet<String>(selected)).apply(); }
    private void showDetails(String id) {
        try {
            RideStore.Summary s = service.rides.get(id); if (s == null) { host.toast("This recording is unavailable."); return; }
            List<RideStore.Point> measured = service.rides.trend(id, 600);
            ScrollView bodyScroll = new ScrollView(host.activity()); LinearLayout body = vertical(); body.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(20)); bodyScroll.addView(body);
            body.addView(ui.caption(date.format(new Date(s.startWallMs)) + " · " + duration(s.elapsedMs)));
            LinearLayout exports = new LinearLayout(host.activity()); exports.setOrientation(LinearLayout.HORIZONTAL);
            TextView csv = ui.secondaryButton("Export CSV", () -> exportCsv(id));
            csv.setContentDescription("Export the complete ride sensor CSV");
            TextView json = ui.secondaryButton("Export JSON", () -> host.saveText("ride-" + id.substring(0, 8) + "-analysis.json", "application/json", s.toJson()));
            json.setContentDescription("Export ride analysis JSON");
            LinearLayout.LayoutParams csvParams = new LinearLayout.LayoutParams(0, -2, 1); csvParams.rightMargin = ui.dp(5);
            LinearLayout.LayoutParams jsonParams = new LinearLayout.LayoutParams(0, -2, 1); jsonParams.leftMargin = ui.dp(5);
            exports.addView(csv, csvParams); exports.addView(json, jsonParams); body.addView(exports);
            addPair(body, "Odometer distance", distance(s.distanceKm), "Battery change", change(s.batteryDropPercent));
            addPair(body, "Measured throughput", quantity(s.measuredThroughputWh, "Wh", 2), "Power coverage", percent(s.electricalCoverage));
            addPair(body, "Average speed", speed(s.averageSpeedKmh), "Maximum speed", speed(s.maximumSpeedKmh));
            LinearLayout energy = ui.card(); energy.addView(ui.text("Electrical energy", 17, true));
            TextView interpretation = ui.caption(s.energyInterpretation); interpretation.setPadding(0, ui.dp(8), 0, ui.dp(12)); energy.addView(interpretation);
            boolean partialDirection = s.confirmedDirectionCoverageMs > 0 && !s.directionConfirmed;
            line(energy, partialDirection ? "Battery draw · confirmed intervals" : "Battery draw", quantity(s.drawWh, "Wh", 2));
            line(energy, partialDirection ? "Battery input / charging · confirmed intervals" : "Battery input / charging", quantity(s.returnedWh, "Wh", 2));
            if (partialDirection) energy.addView(ui.caption("Draw and input cover " + duration(s.confirmedDirectionCoverageMs)
                    + " of confirmed-direction measurements. " + duration(s.unconfirmedDirectionCoverageMs) + " remain absolute throughput."));
            line(energy, "Net measured energy", quantity(s.netConsumptionWh, "Wh", 2));
            line(energy, s.netWhPerKm == null ? "Throughput per distance" : "Net energy per distance", efficiency(s));
            TextView caveat = ui.caption(DisplayUnits.metricText(s.efficiencyStatus, preferences.getString("units", "mi"))); caveat.setPadding(0, ui.dp(10), 0, 0); energy.addView(caveat); body.addView(energy);
            LinearLayout quality = ui.card(); quality.addView(ui.text("Measurement quality", 17, true));
            line(quality, "Power coverage", coverage(s)); line(quality, "Unobserved power time", duration(s.skippedElectricalMs));
            line(quality, "Recorded samples / gaps", s.sampleCount + " / " + s.gapCount); line(quality, "Moving / stationary", duration(s.movingMs) + " / " + duration(s.stationaryMs));
            line(quality, "Moving throughput", quantity(s.movingThroughputWh, "Wh", 2));
            line(quality, "Stationary throughput", quantity(s.stationaryThroughputWh, "Wh", 2));
            line(quality, "Throughput with unknown motion", quantity(s.unknownMotionThroughputWh, "Wh", 2));
            line(quality, "Minimum / maximum voltage", quantity(s.minimumVoltageV, "V", 2) + " / " + quantity(s.maximumVoltageV, "V", 2));
            line(quality, "Peak absolute power", quantity(s.peakAbsolutePowerW, "W", 0));
            line(quality, "Temperature range", quantity(s.minimumTemperatureC, "°C", 0) + " / " + quantity(s.maximumTemperatureC, "°C", 0));
            if (s.recovered) quality.addView(ui.caption("This recording recovered after a process restart. The missing interval was excluded."));
            if (s.odometerReset) quality.addView(ui.caption("The odometer moved backwards; distance and efficiency are unavailable."));
            quality.addView(ui.caption("Observed voltage changes alone do not establish battery sag or health. Stationary and moving energy are separated only where speed was measured.")); body.addView(quality);
            LinearLayout plot = ui.card(); plot.addView(ui.text("Recorded sensor history", 17, true)); Ui.ChartView chart = ui.new ChartView();
            addChartChoices(plot, key -> chart.set(points(measured, key), chartUnit(key), chartColor(key), "No measured values for this field"));
            plot.addView(chart); chart.set(points(measured, "power_w"), "W", ui.accent, "No paired electrical measurements"); body.addView(plot);
            if (!s.notes.isEmpty()) { LinearLayout notes = ui.card(); notes.addView(ui.text("Notes", 16, true)); notes.addView(ui.caption(s.notes)); body.addView(notes); }
            AlertDialog dialog = new AlertDialog.Builder(host.activity()).setTitle(s.label.isEmpty() ? "Ride details" : s.label).setView(bodyScroll)
                    .setPositiveButton("Done", null).setNeutralButton("Delete ride", (d, which) -> confirmDelete(id, s.label)).create(); show(dialog);
        } catch (IOException e) { host.toast("Could not read this recording."); }
    }
    private void exportCsv(String id) {
        try {
            File output = new File(host.activity().getCacheDir(), "ride-" + id + ".csv"); service.rides.exportCsv(id, output);
            host.saveFile("ride-" + id.substring(0, 8) + "-sensors.csv", "text/csv", output);
        } catch (IOException e) { host.toast("Could not export the ride CSV."); }
    }
    private void confirmDelete(String id, String label) {
        show(new AlertDialog.Builder(host.activity()).setTitle("Delete this recording?").setMessage((label.isEmpty() ? "This ride" : label) + " and its sensor journal will be removed from this phone.")
                .setNegativeButton("Keep ride", null).setPositiveButton("Delete", (d, which) -> {
                    try { service.rides.delete(id); selected.remove(id); saveSelection(); historySignature = ""; refresh(); host.toast("Recording deleted."); }
                    catch (IOException | IllegalStateException e) { host.toast("Could not delete this recording."); }
                }).create());
    }
    private void showComparison() {
        if (selected.size() != 2 || service.rides == null) return;
        try {
            List<RideStore.Summary> chosen = new ArrayList<RideStore.Summary>();
            for (String id : selected) { RideStore.Summary summary = service.rides.get(id); if (summary != null) chosen.add(summary); }
            if (chosen.size() != 2) { host.toast("Select two available recordings."); return; }
            RideStore.Summary a = chosen.get(0), b = chosen.get(1);
            ScrollView scroller = new ScrollView(host.activity()); LinearLayout content = vertical(); content.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(20)); scroller.addView(content);
            TextView intro = ui.caption("Observations from two recordings. Coverage, mode labels and conditions matter; this is not a controlled capacity test."); intro.setPadding(0, 0, 0, ui.dp(16)); content.addView(intro);
            LinearLayout names = new LinearLayout(host.activity()); names.setOrientation(LinearLayout.HORIZONTAL);
            TextView first = ui.text(a.label, 17, true); first.setTextColor(ui.accent); names.addView(first, new LinearLayout.LayoutParams(0, -2, 1));
            TextView second = ui.text(b.label, 17, true); second.setTextColor(ui.blue); names.addView(second, new LinearLayout.LayoutParams(0, -2, 1)); content.addView(names);
            compareLine(content, "Distance", distance(a.distanceKm), distance(b.distanceKm));
            compareLine(content, "Duration", duration(a.elapsedMs), duration(b.elapsedMs));
            compareLine(content, "Battery change", change(a.batteryDropPercent), change(b.batteryDropPercent));
            compareLine(content, "Measured throughput", quantity(a.measuredThroughputWh, "Wh", 2), quantity(b.measuredThroughputWh, "Wh", 2));
            compareLine(content, "Net measured energy", quantity(a.netConsumptionWh, "Wh", 2), quantity(b.netConsumptionWh, "Wh", 2));
            compareLine(content, "Energy per distance", efficiency(a), efficiency(b));
            compareLine(content, "Power coverage", percent(a.electricalCoverage), percent(b.electricalCoverage));
            compareLine(content, "Gaps", Long.toString(a.gapCount), Long.toString(b.gapCount));
            compareLine(content, "Average speed", speed(a.averageSpeedKmh), speed(b.averageSpeedKmh));
            compareLine(content, "Minimum voltage", quantity(a.minimumVoltageV, "V", 2), quantity(b.minimumVoltageV, "V", 2));
            compareLine(content, "Stationary throughput", quantity(a.stationaryThroughputWh, "Wh", 2), quantity(b.stationaryThroughputWh, "Wh", 2));
            String units = preferences.getString("units", "mi");
            TextView caveats = ui.caption(a.label + ": " + DisplayUnits.metricText(a.efficiencyStatus, units) + "\n\n" + b.label + ": " + DisplayUnits.metricText(b.efficiencyStatus, units)); caveats.setPadding(0, ui.dp(16), 0, 0); content.addView(caveats);
            show(new AlertDialog.Builder(host.activity()).setTitle("Ride comparison").setView(scroller).setPositiveButton("Done", null).create());
        } catch (IOException e) { host.toast("Could not compare the recordings."); }
    }
    private void compareLine(LinearLayout content, String title, String first, String second) {
        LinearLayout card = ui.card(); card.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12)); card.addView(ui.caption(title));
        LinearLayout values = new LinearLayout(host.activity()); values.setOrientation(LinearLayout.HORIZONTAL);
        TextView a = ui.text(first, 15, true); a.setTextColor(ui.accent); a.setPadding(0, ui.dp(7), ui.dp(7), 0);
        TextView b = ui.text(second, 15, true); b.setTextColor(ui.blue); b.setPadding(ui.dp(7), ui.dp(7), 0, 0);
        values.addView(a, new LinearLayout.LayoutParams(0, -2, 1)); values.addView(b, new LinearLayout.LayoutParams(0, -2, 1)); card.addView(values); content.addView(card);
    }
    private void addPair(LinearLayout parent, String aLabel, String aValue, String bLabel, String bValue) {
        LinearLayout row = new LinearLayout(host.activity()); row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout a = smallMetric(aLabel, aValue, ui.accent), b = smallMetric(bLabel, bValue, ui.blue);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(0, -2, 1); ap.rightMargin = ui.dp(5);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, -2, 1); bp.leftMargin = ui.dp(5);
        row.addView(a, ap); row.addView(b, bp); parent.addView(row);
    }
    private LinearLayout smallMetric(String label, String value, int color) {
        LinearLayout card = ui.card(); card.setPadding(ui.dp(13), ui.dp(13), ui.dp(13), ui.dp(13)); card.addView(ui.caption(label));
        TextView amount = ui.text(value, 19, true); amount.setTextColor(color); amount.setPadding(0, ui.dp(9), 0, 0); card.addView(amount); return card;
    }
    private void line(LinearLayout parent, String label, String value) {
        TextView field = ui.caption(label); field.setPadding(0, ui.dp(10), 0, ui.dp(3)); parent.addView(field);
        parent.addView(ui.text(value, 14, true));
    }
    private void addChartChoices(LinearLayout parent, java.util.function.Consumer<String> choose) {
        String[] labels = {"Power", "Voltage", "Speed", "Battery"}; String[] keys = {"power_w", "voltage_v", "speed_kmh", "battery_percent"};
        for (int i = 0; i < 2; i++) {
            LinearLayout row = new LinearLayout(host.activity()); row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = 0; j < 2; j++) {
                final int index = i * 2 + j; TextView choice = ui.secondaryButton(labels[index], () -> choose.accept(keys[index]));
                choice.setTextSize(12 * ui.textScale); choice.setPadding(ui.dp(8), ui.dp(10), ui.dp(8), ui.dp(10));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1); if (j == 0) params.rightMargin = ui.dp(6); row.addView(choice, params);
            }
            parent.addView(row);
        }
    }
    private List<Ui.Point> points(List<RideStore.Point> source, String key) {
        List<Ui.Point> result = new ArrayList<Ui.Point>(); boolean imperial = !"km".equals(preferences.getString("units", "mi"));
        for (RideStore.Point p : source) {
            Double value = key.equals("voltage_v") ? p.voltageV : key.equals("speed_kmh") ? p.speedKmh : key.equals("battery_percent") ? p.batteryPercent : p.powerW;
            if (value != null) result.add(new Ui.Point(p.wallMs, key.equals("speed_kmh") && imperial ? value / RideStore.KM_PER_MILE : value));
        }
        return result;
    }
    private String chartUnit(String key) { return key.equals("voltage_v") ? "V" : key.equals("battery_percent") ? "%" : key.equals("speed_kmh") ? !"km".equals(preferences.getString("units", "mi")) ? "mph" : "km/h" : "W"; }
    private int chartColor(String key) { return key.equals("voltage_v") ? ui.blue : key.equals("speed_kmh") ? ui.orange : ui.accent; }
    private String distance(Double km) {
        if (km == null) return "Not measured"; String units = preferences.getString("units", "mi");
        String metric = String.format(Locale.US, "%.2f km", km), miles = String.format(Locale.US, "%.2f mi", km / RideStore.KM_PER_MILE);
        return units.equals("dual") ? miles + " / " + metric : units.equals("km") ? metric : miles;
    }
    private String speed(Double kmh) {
        if (kmh == null) return "Not measured"; String units = preferences.getString("units", "mi");
        String metric = String.format(Locale.US, "%.1f km/h", kmh), miles = String.format(Locale.US, "%.1f mph", kmh / RideStore.KM_PER_MILE);
        return units.equals("dual") ? miles + " / " + metric : units.equals("km") ? metric : miles;
    }
    private String efficiency(RideStore.Summary s) {
        Double km = s.netWhPerKm == null ? s.throughputWhPerKm : s.netWhPerKm;
        Double mi = s.netWhPerMile == null ? s.throughputWhPerMile : s.netWhPerMile;
        if (km == null || mi == null) return "Insufficient data";
        String units = preferences.getString("units", "mi");
        String value = units.equals("dual") ? quantity(mi, "Wh/mi", 1) + " / " + quantity(km, "Wh/km", 1)
                : units.equals("km") ? quantity(km, "Wh/km", 1) : quantity(mi, "Wh/mi", 1);
        return value + (s.netWhPerKm == null ? " · throughput" : " · net");
    }
    private static String quantity(Double value, String unit, int decimals) { return value == null ? "Not measured" : String.format(Locale.US, "%." + decimals + "f %s", value, unit); }
    private static String change(Double drop) { return drop == null ? "Not measured" : String.format(Locale.US, "%.0f points %s", Math.abs(drop), drop >= 0 ? "used" : "gained"); }
    private static String percent(double coverage) { return String.format(Locale.US, "%.0f%%", coverage * 100); }
    private static String coverage(RideStore.Summary s) { return "Power coverage " + percent(s.electricalCoverage) + " · " + duration(s.electricalCoverageMs) + " observed / " + duration(s.elapsedMs) + " recorded"; }
    private static String duration(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        return seconds >= 3600 ? String.format(Locale.US, "%dh %02dm", seconds / 3600, seconds % 3600 / 60)
                : seconds >= 60 ? String.format(Locale.US, "%dm %02ds", seconds / 60, seconds % 60) : seconds + "s";
    }
    private void show(AlertDialog dialog) {
        dialogs.add(dialog); dialog.setOnDismissListener(d -> dialogs.remove(dialog)); dialog.show();
    }
    @Override public void close() {
        for (AlertDialog dialog : new ArrayList<AlertDialog>(dialogs)) dialog.dismiss(); dialogs.clear();
    }
}
