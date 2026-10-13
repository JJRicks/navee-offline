package local.navee.offlinelock;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;

import java.util.*;

/** A local, editable dashboard. Widget movement never shares gestures with scooter controls. */
public final class DashboardScreen implements AppScreen {
    public interface Actions {
        View lock();

        void clearLock();

        void navigate(String page);

        void startRide();

        void rename();
    }

    private final ScreenHost host;
    private final Actions actions;
    private final Ui ui;
    private final ScooterService service;
    private final SharedPreferences preferences;
    private final DashboardConfig config;
    private final String profileId;
    private final LinearLayout view, toolbar;
    private final FrameLayout edit;
    private final TextView add, layoutButton, stale;
    private final Context dialogContext;
    private final Grid grid;
    private final ArrayList<Tile> tiles = new ArrayList<>();
    private boolean editing, closed;
    private AlertDialog dialog;
    private Bitmap scooterPhoto;

    public DashboardScreen(ScreenHost host, Actions actions) {
        this.host = host;
        this.actions = actions;
        ui = host.ui();
        dialogContext =
                new ContextThemeWrapper(
                        host.activity(),
                        ui.dark
                                ? android.R.style.Theme_Material_Dialog_Alert
                                : android.R.style.Theme_Material_Light_Dialog_Alert);
        service = host.service();
        preferences = host.activity().getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        ScooterProfiles.Profile profile = service == null ? null : service.profiles.active();
        profileId = profile == null ? "unconfigured" : profile.id;
        config = new DashboardConfig(preferences, profileId);
        editing = preferences.getBoolean("dashboard_editing", false);
        view = column();
        toolbar = row();
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = ui.text("Your dashboard", 16, true);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        edit = iconAction("pencil", "Edit dashboard", this::toggleEditing);
        toolbar.addView(edit, new LinearLayout.LayoutParams(ui.dp(64), ui.dp(48)));
        view.addView(toolbar);
        LinearLayout tools = row();
        add = smallButton("Add widget", this::palette);
        ui.decorate(add, "plus");
        LinearLayout.LayoutParams addSpace = new LinearLayout.LayoutParams(0, -2, 1);
        addSpace.setMarginEnd(ui.dp(8));
        tools.addView(add, addSpace);
        layoutButton = smallButton("Layout", this::layoutOptions);
        tools.addView(layoutButton, new LinearLayout.LayoutParams(0, -2, 1));
        view.addView(tools);
        stale = ui.caption("");
        ui.fitStatusLine(stale);
        stale.setPadding(ui.dp(3), ui.dp(5), ui.dp(3), ui.dp(10));
        view.addView(stale);
        grid = new Grid();
        view.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        render();
    }

    @Override
    public View view() {
        return view;
    }

    private LinearLayout column() {
        LinearLayout v = new LinearLayout(host.activity());
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }

    private LinearLayout row() {
        LinearLayout v = column();
        v.setOrientation(LinearLayout.HORIZONTAL);
        return v;
    }

    private TextView smallButton(String label, Runnable run) {
        TextView v = ui.secondaryButton(label, run);
        v.setTextSize(12 * ui.textScale);
        v.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8));
        return v;
    }

    private void toggleEditing() {
        grid.finishGesture(false);
        editing = !editing;
        preferences.edit().putBoolean("dashboard_editing", editing).apply();
        render();
    }

    private FrameLayout iconAction(String key, String label, Runnable action) {
        FrameLayout target = new FrameLayout(host.activity());
        View icon = ui.icon(key, 22, ui.accent);
        target.addView(icon, new FrameLayout.LayoutParams(ui.dp(22), ui.dp(22), Gravity.CENTER));
        target.setContentDescription(label);
        target.setFocusable(true);
        target.setMinimumWidth(ui.dp(48));
        target.setMinimumHeight(ui.dp(48));
        target.setBackground(
                new android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(Ui.withAlpha(ui.accent, 35)),
                        null,
                        ui.shape(Color.WHITE, 14, 0)));
        if (action != null) target.setOnClickListener(v -> action.run());
        return target;
    }

    private void render() {
        if (closed) return;
        grid.finishGesture(false);
        for (Tile tile : tiles) tile.active = false;
        tiles.clear();
        grid.removeAllViews();
        actions.clearLock();
        edit.removeAllViews();
        if (editing) {
            TextView done = ui.text("Done", 14, true);
            done.setTextColor(ui.accent);
            done.setGravity(Gravity.CENTER);
            edit.addView(done, new FrameLayout.LayoutParams(-1, -1));
        } else {
            edit.addView(
                    ui.icon("pencil", 22, ui.accent),
                    new FrameLayout.LayoutParams(ui.dp(22), ui.dp(22), Gravity.CENTER));
        }
        edit.setContentDescription(editing ? "Finish editing dashboard" : "Edit dashboard");
        add.setVisibility(editing ? View.VISIBLE : View.GONE);
        layoutButton.setVisibility(editing ? View.VISIBLE : View.GONE);
        for (DashboardConfig.Widget widget : config.widgets) {
            Tile tile = new Tile(widget);
            tiles.add(tile);
            grid.addView(tile, new ViewGroup.LayoutParams(-1, -2));
        }
        grid.requestLayout();
        grid.invalidate();
        refresh();
    }

    @Override
    public void refresh() {
        if (closed) return;
        ScooterProfiles.Profile current = service == null ? null : service.profiles.active();
        String id = current == null ? "unconfigured" : current.id;
        if (!profileId.equals(id)) {
            host.rebuild();
            return;
        }
        boolean live = service != null && service.ble.ready();
        long last = 0;
        Map<String, TelemetryHistory.Reading> readings = readings();
        for (TelemetryHistory.Reading r : readings.values())
            last = Math.max(last, r.capturedWallMs);
        stale.setMaxLines(editing ? Integer.MAX_VALUE : 1);
        if (editing) stale.setText("Drag the dots to move · Drag the lower-right corner to resize");
        else
            stale.setText(
                    live
                            ? ""
                            : last > 0
                                    ? "Last seen · " + when(last)
                                    : "");
        stale.setVisibility(stale.getText().length() == 0 ? View.GONE : View.VISIBLE);
        for (Tile tile : tiles) tile.refresh(readings);
    }

    private Map<String, TelemetryHistory.Reading> readings() {
        return service == null ? Collections.emptyMap() : service.displayReadings();
    }

    private String when(long time) {
        return DisplayTime.recent(host.activity(), preferences, time, System.currentTimeMillis());
    }

    private int color(DashboardConfig.Widget widget) {
        return new int[] {ui.accent, ui.blue, ui.orange, ui.danger}[widget.color];
    }

    private String units() {
        return preferences.getString("units", "mi");
    }

    private String format(TelemetryHistory.Reading r) {
        if (r == null || r.value == null) return "—";
        if (r.key.equals("driving_mode") && r.number != null) {
            ScooterProfiles.Profile profile = service == null ? null : service.profiles.active();
            return Telemetry.drivingModeLabel(profile == null ? 0 : profile.pid(), r.number);
        }
        if (r.number != null) {
            if (r.key.endsWith("_kmh")) return DisplayUnits.speed(r.number, units());
            if (r.key.endsWith("_km"))
                return DisplayUnits.metricText(
                        String.format(Locale.US, "%.2f km", r.number), units());
        }
        return DisplayUnits.metricText(r.value, units());
    }

    private double chartNumber(String key, double value) {
        return !units().equals("km") && (key.endsWith("_km") || key.endsWith("_kmh"))
                ? value / 1.609344
                : value;
    }

    private String chartUnit(String key, Map<String, TelemetryHistory.Reading> values) {
        if (key.endsWith("_kmh")) return units().equals("km") ? "km/h" : "mph";
        if (key.endsWith("_km")) return units().equals("km") ? "km" : "mi";
        TelemetryHistory.Reading r = values.get(key);
        return r == null ? "" : r.unit;
    }

    private String label(String key) {
        if (key.equals("driving_mode")) return "Drive mode";
        for (Telemetry.Metric m : new Telemetry().metrics())
            if (m.key.equals(key)) return m.label.replace(" (signed)", "");
        return key;
    }

    private String widgetIcon(String type) {
        switch (type) {
            case "lock":
                return "lock";
            case "scooter":
                return "scooter";
            case "battery":
            case "health":
            case "capacity":
            case "charging":
                return "battery";
            case "quick":
                return "tune";
            case "ride":
            case "trip":
                return "rides";
            case "note":
                return "note";
            case "gauge":
                return "speed";
            default:
                return "stats";
        }
    }

    private final class Tile extends FrameLayout {
        final DashboardConfig.Widget widget;
        final LinearLayout body, heading;
        final TextView footer, title;
        final LinkedHashMap<String, TextView> amounts = new LinkedHashMap<>();
        Ui.BatteryRing battery;
        Ui.ChartView chart;
        Gauge gauge;
        TextView big, subtitle, rideButton, photoName;
        TextView capacityValue, capacityCompare, capacityEvidence, capacityMethod, capacityNote;
        TextView chargingWatts,
                chargingAmps,
                chargingEta,
                chargingEtaLabel,
                chargingFull,
                chargingNote,
                chargingTemperature;
        LinearLayout batteryData, chargingData;
        boolean capacityPending;
        long capacityChecked;
        ImageView photo;
        TextView photoHint;
        ScrollView viewport;
        LinearLayout scooterRow, scooterInfo, scooterNameRow, batteryRow;
        FrameLayout photoPane;
        View renameControl;
        final ArrayList<LinearLayout> scooterStats = new ArrayList<>();
        int scooterColumns;
        final ArrayList<Ui.Point> livePoints = new ArrayList<>();
        boolean wasLive;
        boolean active = true, querying;
        long queriedAt;
        String queryKey = "";
        List<TelemetryHistory.Point> history = Collections.emptyList();

        Tile(DashboardConfig.Widget widget) {
            super(host.activity());
            this.widget = widget;
            setClipChildren(false);
            setClipToPadding(false);
            setBackground(ui.shape(ui.surface, 24, editing ? ui.outline : 0));
            LinearLayout content = column();
            content.setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(10));
            addView(content, new FrameLayout.LayoutParams(-1, -1));
            heading = row();
            heading.setGravity(Gravity.CENTER_VERTICAL);
            title = ui.text(widget.title, 15, true);
            title.setMaxLines(2);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            ui.decorate(title, widgetIcon(widget.type));
            heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            content.addView(heading, new LinearLayout.LayoutParams(-1, -2));
            body = column();
            if (widget.type.equals("lock") || widget.type.equals("metric")
                    || widget.type.equals("gauge") || widget.type.equals("ride"))
                body.setGravity(Gravity.CENTER_VERTICAL);
            body.setPadding(0, ui.dp(8), 0, 0);
            viewport = new WidgetScrollView();
            viewport.setVerticalScrollBarEnabled(true);
            viewport.setScrollbarFadingEnabled(false);
            viewport.setFillViewport(true);
            viewport.setClipToPadding(false);
            viewport.addView(body, new ScrollView.LayoutParams(-1, -1));
            content.addView(viewport, new LinearLayout.LayoutParams(-1, 0, 1));
            footer = ui.caption("");
            footer.setMaxLines(2);
            footer.setPadding(0, ui.dp(5), 0, 0);
            content.addView(footer);
            build();
            if (editing) {
                FrameLayout grip =
                        floatingAction("grip", "Move " + widget.title, () -> position(widget));
                EditorTouch touch = new EditorTouch(this, false);
                grip.setTag(touch);
                grip.setOnTouchListener(touch);
                grip.setOnLongClickListener(
                        v -> {
                            touch.start();
                            return true;
                        });
                addFloating(grip, Gravity.TOP | Gravity.LEFT, 4);
                addFloating(
                        floatingAction(
                                "gear", "Options for " + widget.title, () -> options(widget)),
                        Gravity.TOP | Gravity.RIGHT,
                        4);
                FrameLayout resize =
                        floatingAction("resize", "Resize " + widget.title, () -> size(widget));
                resize.setOnTouchListener(new EditorTouch(this, true));
                addFloating(resize, Gravity.BOTTOM | Gravity.RIGHT, 4);
            }
        }

        private FrameLayout floatingAction(String icon, String label, Runnable action) {
            FrameLayout control = iconAction(icon, label, action);
            control.setBackground(
                    new android.graphics.drawable.RippleDrawable(
                            android.content.res.ColorStateList.valueOf(Ui.withAlpha(ui.accent, 60)),
                            new android.graphics.drawable.InsetDrawable(
                                    ui.shape(Ui.withAlpha(ui.surface, 190), 12, 0), ui.dp(6)),
                            ui.shape(Color.WHITE, 14, 0)));
            control.setElevation(ui.dp(4));
            return control;
        }

        private void addFloating(View control, int gravity, int inset) {
            FrameLayout.LayoutParams p =
                    new FrameLayout.LayoutParams(ui.dp(48), ui.dp(48), gravity);
            p.leftMargin = p.rightMargin = ui.dp(4);
            if ((gravity & Gravity.VERTICAL_GRAVITY_MASK) == Gravity.BOTTOM)
                p.bottomMargin = ui.dp(inset);
            else p.topMargin = ui.dp(inset);
            addView(control, p);
        }

        private void build() {
            switch (widget.type) {
                case "lock":
                    body.addView(actions.lock());
                    break;
                case "scooter":
                    photoName = new FittedValue("", 14, 19, 3);
                    scooterNameRow = row();
                    scooterNameRow.setGravity(Gravity.CENTER_VERTICAL);
                    FrameLayout namePane = new FrameLayout(host.activity()) {
                        @Override
                        protected void onLayout(boolean changed, int l, int t, int r, int b) {
                            super.onLayout(changed, l, t, r, b);
                            if (renameControl == null) return;
                            android.text.Layout text = photoName.getLayout();
                            float textRight = 0;
                            if (text != null)
                                for (int line = 0; line < text.getLineCount(); line++)
                                    textRight = Math.max(textRight, text.getLineRight(line));
                            int size = ui.dp(48);
                            int left = Math.max(0, Math.min(getWidth() - size,
                                    photoName.getLeft() + photoName.getCompoundPaddingLeft()
                                            + Math.round(textRight) + ui.dp(4)));
                            int top = Math.max(0, (getHeight() - size) / 2);
                            renameControl.layout(left, top, left + size, top + size);
                        }
                    };
                    // Reserve the same space in both modes. The pencil floats beside
                    // the actual name, and scrolls with it without covering wrapped text.
                    namePane.setMinimumHeight(ui.dp(48));
                    FrameLayout.LayoutParams nameText =
                            new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL);
                    nameText.rightMargin = ui.dp(52);
                    namePane.addView(photoName, nameText);
                    if (editing) {
                        renameControl = floatingAction("pencil", "Rename scooter", actions::rename);
                        namePane.addView(renameControl,
                                new FrameLayout.LayoutParams(ui.dp(48), ui.dp(48)));
                    }
                    scooterNameRow.addView(namePane, new LinearLayout.LayoutParams(-1, -2));
                    body.addView(scooterNameRow);
                    scooterRow = row();
                    scooterRow.setGravity(Gravity.CENTER_VERTICAL);
                    photoPane = new FrameLayout(host.activity());
                    photo = new ImageView(host.activity());
                    photo.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    photo.setContentDescription("NAVEE UT5 MAX scooter");
                    if (scooterPhoto == null) {
                        BitmapFactory.Options o = new BitmapFactory.Options();
                        o.inSampleSize = 2;
                        scooterPhoto =
                                BitmapFactory.decodeResource(
                                        host.activity().getResources(), R.drawable.ut5max, o);
                    }
                    photo.setImageBitmap(scooterPhoto);
                    photoPane.addView(photo, new FrameLayout.LayoutParams(-1, -1));
                    photoHint = ui.caption("Scooter");
                    photoHint.setGravity(Gravity.CENTER);
                    photoPane.addView(photoHint, new FrameLayout.LayoutParams(-1, -1));
                    scooterRow.addView(
                            photoPane, new LinearLayout.LayoutParams(ui.dp(88), ui.dp(100)));
                    scooterInfo = column();
                    LinearLayout.LayoutParams info = new LinearLayout.LayoutParams(0, -2, 1);
                    info.leftMargin = ui.dp(8);
                    scooterRow.addView(scooterInfo, info);
                    body.addView(scooterRow);
                    for (String key : widget.keys) {
                        LinearLayout item = column();
                        item.setPadding(0, ui.dp(3), ui.dp(5), ui.dp(3));
                        // The two glanceable stats can grow into a roomy card. Width
                        // and per-row height still bring them down to the readable
                        // floor on compact cards or with larger accessibility text.
                        TextView value = new FittedValue(
                                "—", 12, widget.keys.size() <= 2 ? 28 : 18, 2);
                        item.addView(value, new LinearLayout.LayoutParams(-1, -2));
                        TextView label =
                                ui.caption(
                                        key.equals("capacity_throughput_ah")
                                                ? "Battery throughput"
                                                : key.equals("charge_cycles")
                                                        ? "Reported charges"
                                                        : label(key));
                        label.setTextSize(12 * ui.textScale);
                        item.addView(label, new LinearLayout.LayoutParams(-1, -2));
                        scooterStats.add(item);
                        amounts.put(key, value);
                    }
                    break;
                case "battery":
                    batteryRow = row();
                    batteryRow.setGravity(Gravity.CENTER);
                    battery = ui.new BatteryRing();
                    batteryRow.addView(
                            battery, new LinearLayout.LayoutParams(ui.dp(140), ui.dp(152)));
                    body.addView(batteryRow, new LinearLayout.LayoutParams(-1, -2));
                    IconReadingStrip readings = new IconReadingStrip();
                    batteryData = readings;
                    for (String key : widget.keys)
                        amounts.put(key, readings.addReading(readingIcon(key), label(key)));
                    body.addView(batteryData, new LinearLayout.LayoutParams(-1, -2));
                    IconReadingStrip charging = new IconReadingStrip();
                    chargingData = charging;
                    chargingWatts = charging.addReading("bolt", "Charging power");
                    chargingAmps = charging.addReading("battery", "Charging current");
                    chargingEta = charging.addReading("clock", "Time to full");
                    chargingTemperature = charging.addReading("temperature", "Battery temperature");
                    // Compact status is announced and available on long press, without
                    // adding a second block of explanatory text to the glanceable card.
                    chargingEtaLabel = ui.caption("Time to full");
                    chargingNote = ui.caption("");
                    body.addView(chargingData, new LinearLayout.LayoutParams(-1, -2));
                    break;
                case "metric":
                    big = new FittedValue("—", 14, 32, 2);
                    body.addView(big, new LinearLayout.LayoutParams(-1, -2));
                    subtitle = ui.caption(label(widget.metric));
                    body.addView(subtitle);
                    break;
                case "gauge":
                    gauge = new Gauge(widget);
                    body.addView(gauge, new LinearLayout.LayoutParams(-1, ui.dp(96)));
                    big = new FittedValue("—", 14, 24, 2);
                    big.setGravity(Gravity.CENTER);
                    body.addView(big, new LinearLayout.LayoutParams(-1, -2));
                    subtitle = ui.caption(label(widget.metric));
                    subtitle.setGravity(Gravity.CENTER);
                    body.addView(subtitle);
                    break;
                case "live":
                case "history":
                    chart = ui.new ChartView();
                    chart.setHistoryMode(true);
                    body.addView(chart, new LinearLayout.LayoutParams(-1, ui.dp(140)));
                    subtitle = ui.caption(label(widget.metric));
                    body.addView(subtitle);
                    break;
                case "health":
                    for (String key :
                            new String[] {
                                "battery_health_percent", "charge_cycles", "rated_capacity_ah"
                            }) addReading(key);
                    chart = ui.new ChartView();
                    chart.setHistoryMode(true);
                    body.addView(chart, new LinearLayout.LayoutParams(-1, ui.dp(190)));
                    subtitle =
                            ui.caption("Controller-reported health; not a measured capacity test.");
                    body.addView(subtitle);
                    break;
                case "capacity":
                    capacityValue = ui.text("Not enough ride data", 24, true);
                    capacityCompare = ui.text("", 16, true);
                    capacityEvidence = ui.caption("Checking recorded rides…");
                    capacityMethod =
                            ui.caption("Experimental energy / battery-percentage estimate");
                    capacityNote =
                            ui.caption(
                                    "Cold, riding load and battery-gauge changes affect this"
                                            + " estimate.");
                    for (TextView text :
                            new TextView[] {
                                capacityValue,
                                capacityCompare,
                                capacityMethod,
                                capacityEvidence,
                                capacityNote
                            }) {
                        text.setPadding(0, 0, 0, ui.dp(10));
                        body.addView(text);
                    }
                    body.addView(
                            ui.secondaryButton(
                                    "How this is estimated", () -> showCapacityMethod(widget)));
                    break;
                case "charging":
                    buildCharging(body, true);
                    break;
                case "readings":
                    for (String key : widget.keys) addReading(key);
                    break;
                case "trip":
                    for (String key :
                            new String[] {
                                "speed_kmh",
                                "trip_km",
                                "trip_minutes",
                                "maximum_speed_kmh",
                                "odometer_km"
                            }) addReading(key);
                    break;
                case "quick":
                    shortcut("Lights", "lights", "lights");
                    shortcut("Scooter settings", "controls", "scooter");
                    shortcut("All readings", "stats", "stats");
                    shortcut("Ride history", "rides", "rides");
                    break;
                case "ride":
                    subtitle = ui.text("Ready for your next ride", 18, true);
                    body.addView(subtitle);
                    rideButton =
                            ui.button(
                                    "Start recording",
                                    () -> {
                                        if (service != null && service.isRecording())
                                            service.stopRide();
                                        else actions.startRide();
                                        host.redraw();
                                    });
                    body.addView(rideButton);
                    body.addView(ui.secondaryButton("View rides", () -> actions.navigate("rides")));
                    break;
                case "note":
                    subtitle =
                            ui.text(
                                    widget.note.isEmpty()
                                            ? "Add a reminder in widget options."
                                            : widget.note,
                                    16,
                                    false);
                    body.addView(subtitle);
                    break;
            }
        }

        /** Let a swipe anywhere inside overflowing content scroll that widget first. */
        private final class WidgetScrollView extends ScrollView {
            float startY;

            WidgetScrollView() {
                super(host.activity());
            }

            @Override
            public boolean onInterceptTouchEvent(MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startY = event.getY();
                        if (!editing && (canScrollVertically(-1) || canScrollVertically(1)))
                            getParent().requestDisallowInterceptTouchEvent(true);
                        break;
                    case MotionEvent.ACTION_MOVE:
                        float delta = event.getY() - startY;
                        if (Math.abs(delta) > ViewConfiguration.get(getContext()).getScaledTouchSlop()
                                && !canScrollVertically(delta > 0 ? -1 : 1))
                            getParent().requestDisallowInterceptTouchEvent(false);
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        getParent().requestDisallowInterceptTouchEvent(false);
                        break;
                }
                return super.onInterceptTouchEvent(event);
            }
        }

        private String readingIcon(String key) {
            if (key.equals("remaining_range_km")) return "route";
            if (key.equals("power_w")) return "bolt";
            if (key.equals("battery_health_percent")) return "heart";
            if (key.equals("temperature_c")) return "temperature";
            return ui.featureIcon(key);
        }

        /** One glanceable row at ordinary widths; extra readings wrap at readable sizes. */
        private final class IconReadingStrip extends LinearLayout {
            final ArrayList<LinearLayout> cells = new ArrayList<>();
            int columns;

            IconReadingStrip() {
                super(host.activity());
                setOrientation(VERTICAL);
            }

            TextView addReading(String iconKey, String label) {
                LinearLayout cell = column();
                cell.setGravity(Gravity.CENTER);
                cell.setPadding(ui.dp(3), ui.dp(4), ui.dp(3), ui.dp(4));
                cell.setTooltipText(label);
                cell.addView(ui.icon(iconKey, 18, ui.muted),
                        new LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)));
                TextView value = new FittedValue("—", 12, 15, 2);
                value.setGravity(Gravity.CENTER);
                value.setContentDescription(label + ", not reported");
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
                params.topMargin = ui.dp(3);
                cell.addView(value, params);
                cells.add(cell);
                return value;
            }

            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                int width = MeasureSpec.getSize(widthSpec);
                int next = Math.min(Math.min(4, cells.size()),
                        Math.max(1, width / Math.max(1, ui.dp(62 * ui.textScale))));
                if (cells.size() == 4 && next == 3) next = 2;
                if (next != columns) {
                    columns = next;
                    for (LinearLayout cell : cells)
                        if (cell.getParent() instanceof ViewGroup)
                            ((ViewGroup) cell.getParent()).removeView(cell);
                    removeAllViews();
                    for (int i = 0; i < cells.size(); i += columns) {
                        LinearLayout line = row();
                        line.setGravity(Gravity.BOTTOM);
                        for (int j = i; j < Math.min(i + columns, cells.size()); j++)
                            line.addView(cells.get(j), new LinearLayout.LayoutParams(0, -2, 1));
                        for (int j = Math.min(i + columns, cells.size()); j < i + columns; j++)
                            line.addView(new View(host.activity()),
                                    new LinearLayout.LayoutParams(0, 1, 1));
                        addView(line, new LinearLayout.LayoutParams(-1, -2));
                    }
                }
                super.onMeasure(widthSpec, heightSpec);
            }
        }

        private void shortcut(String label, String page, String icon) {
            LinearLayout line = ui.settingRow(label, "", () -> actions.navigate(page));
            ui.decorate((TextView) line.getChildAt(0), icon);
            body.addView(line);
        }

        private void viewSize(View view, int width, int height) {
            ViewGroup.LayoutParams params = view.getLayoutParams();
            if (params.width != width || params.height != height) {
                params.width = width;
                params.height = height;
                view.setLayoutParams(params);
            }
        }

        private void linearSize(View v, int width, int height, float weight, int margin) {
            LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) v.getLayoutParams();
            if (p.width != width
                    || p.height != height
                    || p.weight != weight
                    || p.leftMargin != margin) {
                p.width = width;
                p.height = height;
                p.weight = weight;
                p.leftMargin = margin;
                v.setLayoutParams(p);
            }
        }

        /** Fit from the available width, never from a previous WRAP_CONTENT height. */
        private final class FittedValue extends TextView {
            final int minimumSp, maximumSp, targetLines;
            int fittedWidth = -1;
            String fittedText;
            float fittedPixels;
            int heightBudget = Integer.MAX_VALUE;

            FittedValue(String text, int minimum, int maximum, int lines) {
                super(host.activity());
                minimumSp = (int) Math.ceil(minimum * ui.textScale);
                maximumSp = Math.max(minimumSp, Math.round(maximum * ui.textScale));
                targetLines = lines;
                setText(text);
                setTextColor(ui.text);
                setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
                setIncludeFontPadding(false);
                setLineSpacing(ui.dp(3), 1);
                setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, maximumSp);
                // At the readable floor, long custom text wraps into the card's
                // scroller instead of disappearing behind a maximum-line limit.
            }

            void fitHeight(int pixels) {
                int next = Math.max(1, pixels);
                if (heightBudget == next) return;
                heightBudget = next;
                fittedWidth = -1;
                requestLayout();
            }

            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                int width = MeasureSpec.getSize(widthSpec)
                        - getCompoundPaddingLeft() - getCompoundPaddingRight();
                String text = getText().toString();
                float density = getResources().getDisplayMetrics().scaledDensity;
                if (width != fittedWidth || !text.equals(fittedText)) {
                    fittedWidth = width;
                    fittedText = text;
                    fittedPixels = maximumSp * density;
                    if (width > 0 && MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED) {
                        android.text.TextPaint paint = new android.text.TextPaint(getPaint());
                        for (int size = maximumSp; size >= minimumSp; size--) {
                            fittedPixels = size * density;
                            paint.setTextSize(fittedPixels);
                            android.text.StaticLayout layout = android.text.StaticLayout.Builder
                                    .obtain(text, 0, text.length(), paint, width)
                                    .setIncludePad(getIncludeFontPadding())
                                    .setLineSpacing(getLineSpacingExtra(), getLineSpacingMultiplier())
                                    .setBreakStrategy(getBreakStrategy())
                                    .setHyphenationFrequency(getHyphenationFrequency())
                                    .setTextDirection(android.text.TextDirectionHeuristics.FIRSTSTRONG_LTR)
                                    .build();
                            int count = layout.getLineCount();
                            boolean fits = count <= targetLines && layout.getHeight() <= heightBudget
                                    && (count == 0 || layout.getLineEnd(count - 1) == text.length());
                            for (int line = 0; fits && line < count; line++)
                                fits = layout.getLineMax(line) <= width;
                            if (fits) break;
                        }
                    }
                }
                if (getTextSize() != fittedPixels)
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, fittedPixels);
                super.onMeasure(widthSpec, heightSpec);
            }
        }

        private void arrangeScooter(int width) {
            int photoWidth = Math.max(ui.dp(36), Math.min(Math.round(width * .45f),
                    width - ui.dp(8) - ui.dp(106 * ui.textScale)));
            viewSize(photoPane, photoWidth, photoPane.getLayoutParams().height);
            int available = width - photoWidth - ui.dp(8);
            boolean glanceable = widget.keys.size() <= 2;
            int columns = !glanceable && available >= ui.dp(180 * ui.textScale) ? 2 : 1;
            if (columns == scooterColumns) return;
            scooterColumns = columns;
            for (LinearLayout item : scooterStats)
                if (item.getParent() instanceof ViewGroup)
                    ((ViewGroup) item.getParent()).removeView(item);
            scooterInfo.removeAllViews();
            for (int i = 0; i < scooterStats.size(); i += columns) {
                LinearLayout line = row();
                for (int j = i; j < Math.min(i + columns, scooterStats.size()); j++)
                    line.addView(scooterStats.get(j), new LinearLayout.LayoutParams(0, -2, 1));
                if (columns == 2 && i + 1 == scooterStats.size())
                    line.addView(new View(host.activity()), new LinearLayout.LayoutParams(0, 1, 1));
                scooterInfo.addView(line, new LinearLayout.LayoutParams(-1, -2));
            }
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int width = Math.max(1, MeasureSpec.getSize(widthSpec) - ui.dp(28));
            if (photo != null) arrangeScooter(width);
            super.onMeasure(widthSpec, heightSpec);
            int budget =
                    Math.max(
                            1,
                            viewport.getMeasuredHeight()
                                    - body.getPaddingTop()
                                    - body.getPaddingBottom());
            if (photo != null) {
                int rows = Math.max(1, scooterInfo.getChildCount());
                int rowBudget = Math.max(1, (budget - scooterNameRow.getMeasuredHeight()) / rows);
                for (LinearLayout item : scooterStats) {
                    TextView label = (TextView) item.getChildAt(1);
                    ((FittedValue) item.getChildAt(0)).fitHeight(
                            rowBudget - label.getMeasuredHeight()
                                    - item.getPaddingTop() - item.getPaddingBottom());
                }
                viewSize(
                        photoPane,
                        photoPane.getLayoutParams().width,
                        Math.max(ui.dp(40), budget - scooterNameRow.getMeasuredHeight()));
            }
            if (gauge != null) {
                viewSize(
                        gauge,
                        -1,
                        Math.max(
                                ui.dp(40),
                                Math.min(
                                        ui.dp(96),
                                        budget
                                                - big.getMeasuredHeight()
                                                - subtitle.getMeasuredHeight())));
            }
            if (chart != null) {
                int other = subtitle == null ? 0 : subtitle.getMeasuredHeight();
                if (widget.type.equals("health"))
                    for (TextView amount : amounts.values())
                        other += ((View) amount.getParent()).getMeasuredHeight();
                viewSize(chart, -1, Math.max(ui.dp(120), budget - other));
            }
            if (battery != null) {
                View strip = chargingData.getVisibility() == View.VISIBLE ? chargingData : batteryData;
                int diameter = Math.min(width, Math.max(
                        ui.dp(56), budget - strip.getMeasuredHeight() - ui.dp(12)));
                viewSize(battery, diameter, diameter + ui.dp(12));
                // The reading strip stays at the bottom. The ring fills, then centers
                // inside, the remaining space when card width limits its diameter.
                viewSize(batteryRow, -1,
                        Math.max(diameter + ui.dp(12), budget - strip.getMeasuredHeight()));
            }
            super.onMeasure(widthSpec, heightSpec);
        }

        private void addReading(String key) {
            addReading(body, key);
        }

        private void addReading(LinearLayout container, String key) {
            ReadingRow line = new ReadingRow(label(key));
            container.addView(line, new LinearLayout.LayoutParams(-1, -2));
            amounts.put(key, line.value);
        }

        private TextView chargingRow(LinearLayout container, String label) {
            ReadingRow line = new ReadingRow(label);
            container.addView(line, new LinearLayout.LayoutParams(-1, -2));
            if (label.equals("Time to full")) chargingEtaLabel = line.name;
            return line.value;
        }

        /** A value never takes the entire width away from its label. */
        private final class ReadingRow extends LinearLayout {
            final TextView name, value;
            boolean narrow;

            ReadingRow(String label) {
                super(host.activity());
                setGravity(Gravity.CENTER_VERTICAL);
                name = ui.caption(label);
                value = new FittedValue("—", 12, 16, 2);
                addView(name);
                addView(value);
            }

            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                narrow = MeasureSpec.getSize(widthSpec) < ui.dp(220 * ui.textScale);
                setOrientation(narrow ? VERTICAL : HORIZONTAL);
                setPadding(0, ui.dp(narrow ? 4 : 6), 0, ui.dp(narrow ? 4 : 6));
                linearSize(name, narrow ? -1 : 0, -2, narrow ? 0 : 1, 0);
                linearSize(value, narrow ? -1 : 0, -2, narrow ? 0 : 1, narrow ? 0 : ui.dp(8));
                value.setGravity(narrow ? Gravity.START : Gravity.END);
                super.onMeasure(widthSpec, heightSpec);
            }
        }

        private void buildCharging(LinearLayout container, boolean detailed) {
            chargingWatts = chargingRow(container, "Charging power");
            if (detailed) chargingAmps = chargingRow(container, "Charging current");
            chargingEta = chargingRow(container, "Time to full");
            if (detailed) chargingFull = chargingRow(container, "To 100% at this rate");
            chargingNote = ui.caption("Connect for fresh charging readings.");
            container.addView(chargingNote);
        }

        private ChargingEstimate.Value chargeValue(
                Map<String, TelemetryHistory.Reading> values, String key) {
            TelemetryHistory.Reading r = values.get(key);
            return new ChargingEstimate.Value(
                    r == null ? null : r.number,
                    r != null && r.live,
                    r == null ? 0 : r.capturedWallMs);
        }

        private String duration(Double minutes) {
            if (minutes == null || !Double.isFinite(minutes)) return "—";
            long value = Math.max(0, Math.round(minutes));
            return value == 0
                    ? "At target"
                    : "≈ " + (value >= 60 ? value / 60 + "h " : "") + value % 60 + "m";
        }

        private void refreshCharging(Map<String, TelemetryHistory.Reading> values) {
            ChargingEstimate.Result estimate =
                    ChargingEstimate.calculate(
                            chargeValue(values, "charging_state"), chargeValue(values, "voltage_v"),
                            chargeValue(values, "current_a"),
                                    chargeValue(values, "battery_percent"),
                            chargeValue(values, "rated_capacity_ah"),
                                    chargeValue(values, "charge_limit_percent"));
            if (batteryData != null) {
                batteryData.setVisibility(estimate.charging ? View.GONE : View.VISIBLE);
                chargingData.setVisibility(estimate.charging ? View.VISIBLE : View.GONE);
            }
            chargingWatts.setText(
                    estimate.inputWatts == null
                            ? "—"
                            : String.format(Locale.US, "%.0f W", estimate.inputWatts));
            chargingWatts.setTextColor(estimate.inputWatts == null ? ui.muted : color(widget));
            if (chargingAmps != null) {
                chargingAmps.setText(
                        estimate.inputAmps == null
                                ? "—"
                                : String.format(Locale.US, "%.2f A", estimate.inputAmps));
                chargingAmps.setTextColor(estimate.inputAmps == null ? ui.muted : ui.text);
            }
            boolean limit = estimate.targetPercent != null && estimate.targetPercent < 100;
            chargingEtaLabel.setText(
                    limit
                            ? String.format(Locale.US, "To %.0f%% limit", estimate.targetPercent)
                            : "Time to full");
            Double minutes =
                    limit ? estimate.remainingMinutesToLimit : estimate.remainingMinutesToFull;
            chargingEta.setText(duration(minutes));
            chargingEta.setTextColor(minutes == null ? ui.muted : ui.text);
            if (chargingFull != null) {
                chargingFull.setText(
                        limit ? "Raise charge limit" : duration(estimate.remainingMinutesToFull));
                chargingFull.setTextColor(ui.muted);
                ((View) chargingFull.getParent()).setVisibility(limit ? View.VISIBLE : View.GONE);
            }
            chargingNote.setText(
                    (estimate.inputWatts == null ? "" : "Battery-side readings. ")
                            + estimate.status);
            describeCharging(chargingWatts, "Charging power", "Battery-side power");
            if (chargingAmps != null)
                describeCharging(chargingAmps, "Charging current", "Battery-side current");
            describeCharging(chargingEta, chargingEtaLabel.getText().toString(), estimate.status);
            if (chargingTemperature != null) {
                TelemetryHistory.Reading temperature = values.get("temperature_c");
                chargingTemperature.setText(format(temperature));
                chargingTemperature.setTextColor(
                        temperature != null && temperature.live ? ui.text : ui.muted);
                describeCharging(chargingTemperature, "Battery temperature", "");
            }
        }

        private void describeCharging(TextView value, String label, String detail) {
            String description = label + ", " + value.getText()
                    + (detail.isEmpty() ? "" : ". " + detail);
            value.setContentDescription(description);
            if (value.getParent() instanceof View)
                ((View) value.getParent()).setTooltipText(description);
        }

        void refresh(Map<String, TelemetryHistory.Reading> values) {
            if (!active) return;
            if (capacityValue != null) refreshCapacity();
            if (chargingWatts != null) refreshCharging(values);
            int accent = color(widget);
            boolean anyStale = false;
            long oldest = Long.MAX_VALUE;
            boolean any = false;
            ArrayList<String> shown = new ArrayList<>(amounts.keySet());
            if (big != null) shown.add(widget.metric);
            if (battery != null) shown.add("battery_percent");
            for (String key : shown) {
                TelemetryHistory.Reading r = values.get(key);
                boolean live = r != null && r.live;
                anyStale |= r != null && r.value != null && !live;
                if (r != null && r.value != null) {
                    any = true;
                    oldest = Math.min(oldest, r.capturedWallMs);
                }
                TextView amount = amounts.get(key);
                if (amount != null) {
                    amount.setText(format(r));
                    amount.setTextColor(live ? ui.text : ui.muted);
                    amount.setContentDescription(
                            label(key)
                                    + ", "
                                    + format(r)
                                    + (r != null && !live
                                            ? ", last seen " + when(r.capturedWallMs)
                                            : ""));
                    if (batteryData != null && amount.getParent() instanceof View)
                        ((View) amount.getParent()).setTooltipText(amount.getContentDescription());
                    if (key.equals("driving_mode"))
                        ui.styleDriveMode(amount, format(r), live);
                }
            }
            TelemetryHistory.Reading selected = values.get(widget.metric);
            if (big != null) {
                big.setText(format(selected));
                big.setTextColor(selected != null && selected.live ? accent : ui.muted);
                if (widget.metric.equals("driving_mode")) {
                    big.setContentDescription("Drive mode, " + format(selected)
                            + (selected != null && !selected.live
                                    ? ", last seen " + when(selected.capturedWallMs) : ""));
                    ui.styleDriveMode(big, format(selected), selected != null && selected.live);
                }
            }
            if (gauge != null) gauge.set(selected);
            if (battery != null) {
                TelemetryHistory.Reading charge = values.get("battery_percent"),
                        charging = values.get("charging_state");
                boolean isCharging =
                        charging != null
                                && charging.live
                                && charging.number != null
                                && charging.number == 1;
                battery.set(charge == null ? null : charge.number, "", isCharging);
                battery.setLive(charge != null && charge.live);
            }
            if (photo != null) {
                ScooterProfiles.Profile p = service == null ? null : service.profiles.active();
                photoName.setText(p == null ? "Your scooter" : p.name);
                // Model identity is saved with the profile, independent of live readings.
                boolean show = p != null && p.pid() == 2538;
                photo.setVisibility(show ? View.VISIBLE : View.GONE);
                photoHint.setVisibility(show ? View.GONE : View.VISIBLE);
            }
            if (rideButton != null) {
                boolean recording = service != null && service.isRecording();
                rideButton.setText(recording ? "Stop & save" : "Start recording");
                rideButton.setEnabled(
                        service != null
                                && (recording || service.ble.ready())
                                && !service.phoneSaving());
                RideStore.Summary summary = recording ? service.rides.activeSummary() : null;
                subtitle.setText(
                        summary == null
                                ? "Ready for your next ride"
                                : String.format(
                                        Locale.US,
                                        "%d:%02d recording",
                                        summary.elapsedMs / 60000,
                                        summary.elapsedMs / 1000 % 60));
            }
            footer.setText(anyStale ? "Last seen" : "");
            footer.setContentDescription(anyStale ? "Last seen · " + when(oldest) : "");
            footer.setVisibility(footer.getText().length() == 0 ? View.GONE : View.VISIBLE);
            footer.setTextColor(anyStale ? ui.muted : ui.accent);
            if (chargingData != null && chargingData.getVisibility() == View.VISIBLE)
                footer.setVisibility(View.GONE);
            if (chart != null) refreshChart(values);
            // Editing is a layout operation; data controls stay inert until Done.
            body.setAlpha(editing ? .92f : 1f);
            if (editing) disableInputs(body);
        }

        private void disableInputs(View v) {
            if (v == renameControl) return;
            if (v instanceof ViewGroup) {
                for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++)
                    disableInputs(((ViewGroup) v).getChildAt(i));
            }
            if (v.isClickable()) v.setEnabled(false);
        }

        private void refreshCapacity() {
            if (service == null || capacityPending) return;
            if (service.profiles.active() == null) {
                capacityEvidence.setText(
                        "Set up a scooter profile, then record rides to build an estimate.");
                return;
            }
            long now = android.os.SystemClock.elapsedRealtime();
            if (capacityChecked > 0 && now - capacityChecked < 10000) return;
            capacityChecked = now;
            capacityPending = true;
            service.requestCapacityEstimate(
                    rating(widget),
                    (int) (widget.windowMs / 86400000L),
                    result -> {
                        capacityPending = false;
                        if (closed || !active || !profileId.equals(result.profileId)) return;
                        boolean estimated = result.estimatedUsableWh != null;
                        boolean proxy = !estimated && result.throughputEquivalentWh != null;
                        Double value =
                                estimated
                                        ? result.estimatedUsableWh
                                        : result.throughputEquivalentWh;
                        Double percent =
                                estimated
                                        ? result.percentOfAdvertised
                                        : result.throughputPercentOfAdvertised;
                        capacityValue.setText(
                                value == null
                                        ? "Not enough ride data"
                                        : String.format(Locale.US, "≈ %.0f Wh", value));
                        capacityValue.setTextColor(
                                value == null ? ui.muted : proxy ? ui.orange : color(widget));
                        capacityMethod.setText(
                                estimated
                                        ? "Ride-based energy estimate · experimental"
                                        : proxy
                                                ? "Throughput equivalent · experimental proxy"
                                                : "Experimental energy / battery-percentage"
                                                        + " estimate");
                        capacityCompare.setText(
                                percent != null && result.advertisedWh != null
                                        ? String.format(
                                                Locale.US,
                                                "%.0f%% of %.0f Wh advertised%s",
                                                percent,
                                                result.advertisedWh,
                                                proxy ? " · proxy only" : "")
                                        : result.advertisedWh != null
                                                ? String.format(
                                                        Locale.US,
                                                        "Reference: %.0f Wh advertised",
                                                        result.advertisedWh)
                                                : "Set advertised Wh in widget options to compare");
                        capacityCompare.setTextColor(ui.muted);
                        String evidence =
                                value == null
                                        ? result.status
                                        : String.format(
                                                Locale.US,
                                                "%d rides · %.1f battery points · %.1f%% coverage",
                                                estimated
                                                        ? result.qualifiedRides
                                                        : result.proxyRides,
                                                estimated
                                                        ? result.batteryDropPoints
                                                        : result.proxyBatteryDropPoints,
                                                result.electricalCoverage * 100);
                        if (result.minimumTemperatureC != null
                                && result.maximumTemperatureC != null)
                            evidence +=
                                    String.format(
                                            Locale.US,
                                            "\nPack: %.0f–%.0f °C",
                                            result.minimumTemperatureC,
                                            result.maximumTemperatureC);
                        evidence +=
                                "\n"
                                        + result.windowDays
                                        + "-day window"
                                        + (result.includesActive ? " · recording live" : "");
                        capacityEvidence.setText(evidence);
                        capacityNote.setText(
                                proxy
                                        ? "Draw and return are both counted. This proxy can"
                                                + " overstate usable energy; the percentage is not"
                                                + " battery health."
                                        : "Cold, voltage range and battery-gauge changes affect"
                                                + " this estimate; it is not a measured capacity"
                                                + " test.");
                        capacityNote.setTextColor(ui.muted);
                    });
        }

        private void refreshChart(Map<String, TelemetryHistory.Reading> values) {
            long now = System.currentTimeMillis();
            String key = widget.type.equals("health") ? "battery_health_percent" : widget.metric;
            if (widget.type.equals("live")) {
                TelemetryHistory.Reading reading = values.get(key);
                boolean live = reading != null && reading.live && reading.number != null;
                if (live
                        && (livePoints.isEmpty()
                                || reading.capturedWallMs
                                        > livePoints.get(livePoints.size() - 1).millis)) {
                    boolean gap =
                            !wasLive
                                    || (!livePoints.isEmpty()
                                            && reading.capturedWallMs
                                                            - livePoints.get(livePoints.size() - 1)
                                                                    .millis
                                                    > 20000);
                    livePoints.add(new Ui.Point(reading.capturedWallMs, reading.number, gap));
                }
                wasLive = live;
                while (!livePoints.isEmpty()
                        && (livePoints.get(0).millis < now - widget.windowMs
                                || livePoints.size() > 900)) livePoints.remove(0);
            }
            if (service != null
                    && service.history != null
                    && !querying
                    && (now - queriedAt > 10000 || !queryKey.equals(key))) {
                querying = true;
                queriedAt = now;
                queryKey = key;
                service.history.query(
                        profileId,
                        key,
                        now - widget.windowMs,
                        now,
                        points -> {
                            if (closed || !active) return;
                            querying = false;
                            history = points;
                            drawChart(readings(), key);
                        });
            }
            drawChart(values, key);
        }

        private void drawChart(Map<String, TelemetryHistory.Reading> values, String key) {
            ArrayList<Ui.Point> points = new ArrayList<>();
            long tailStart = livePoints.isEmpty() ? Long.MAX_VALUE : livePoints.get(0).millis;
            for (TelemetryHistory.Point p : history)
                if (p.wallMs < tailStart)
                    points.add(new Ui.Point(p.wallMs, chartNumber(key, p.value), p.gapBefore));
            for (Ui.Point p : livePoints)
                points.add(new Ui.Point(p.millis, chartNumber(key, p.value), p.gapBefore));
            chart.set(points, chartUnit(key, values), color(widget), "No saved readings yet");
            if (!widget.type.equals("health")) {
                String metricLabel = label(key);
                subtitle.setText(
                        (widget.title.equalsIgnoreCase(metricLabel) ? "" : metricLabel + " · ")
                                + windowName(widget.windowMs));
            }
            footer.setText(widget.type.equals("live") && !wasLive && !points.isEmpty()
                    ? "Last seen" : "");
            footer.setTextColor(ui.muted);
            footer.setVisibility(footer.getText().length() == 0 ? View.GONE : View.VISIBLE);
        }
    }

    private final class Gauge extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final DashboardConfig.Widget widget;
        private TelemetryHistory.Reading reading;

        Gauge(DashboardConfig.Widget widget) {
            super(host.activity());
            this.widget = widget;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void set(TelemetryHistory.Reading reading) {
            this.reading = reading;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float pad = ui.dp(12), w = getWidth(), h = getHeight();
            RectF arc = new RectF(pad, pad, w - pad, 2 * h - pad);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(ui.dp(10));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(ui.outline);
            c.drawArc(arc, 180, 180, false, paint);
            if (reading != null && reading.number != null) {
                double number = reading.number,
                        fraction =
                                Math.max(
                                        0,
                                        Math.min(
                                                1,
                                                (number - widget.minimum)
                                                        / (widget.maximum - widget.minimum)));
                paint.setColor(reading.live ? color(widget) : ui.muted);
                c.drawArc(arc, 180, (float) (180 * fraction), false, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setTextSize(ui.sp(11));
            paint.setColor(ui.muted);
            paint.setTextAlign(Paint.Align.LEFT);
            c.drawText(
                    String.format(Locale.US, "%.0f", chartNumber(widget.metric, widget.minimum)),
                    pad,
                    h - ui.dp(2),
                    paint);
            paint.setTextAlign(Paint.Align.RIGHT);
            c.drawText(
                    String.format(Locale.US, "%.0f", chartNumber(widget.metric, widget.maximum)),
                    w - pad,
                    h - ui.dp(2),
                    paint);
        }
    }

    /** Editing gestures own their hit targets; content scrolling never starts a move. */
    private final class EditorTouch implements View.OnTouchListener {
        final Tile tile;
        final boolean resize;
        float downX, downY;
        boolean started;
        View handle;
        final Runnable hold = () -> start();

        EditorTouch(Tile tile, boolean resize) {
            this.tile = tile;
            this.resize = resize;
        }

        void start() {
            if (started || handle == null || !editing || !tile.active || !tile.hasWindowFocus())
                return;
            started = true;
            handle.removeCallbacks(hold);
            grid.beginGesture(tile, resize, downX, downY);
            handle.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    handle = v;
                    started = false;
                    downX = event.getRawX();
                    downY = event.getRawY();
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    if (!resize) v.postDelayed(hold, ViewConfiguration.getLongPressTimeout());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!started
                            && Math.hypot(event.getRawX() - downX, event.getRawY() - downY)
                                    > ViewConfiguration.get(host.activity()).getScaledTouchSlop())
                        start();
                    if (started) grid.updateGesture(event.getRawX(), event.getRawY());
                    return true;
                case MotionEvent.ACTION_POINTER_UP:
                    if (event.getActionIndex() != 0) return true;
                    // End cleanly if the primary finger leaves during a multi-touch gesture.
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.removeCallbacks(hold);
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                    if (started)
                        grid.finishGesture(event.getActionMasked() != MotionEvent.ACTION_CANCEL);
                    else if (handle != null && event.getActionMasked() == MotionEvent.ACTION_UP)
                        v.performClick();
                    handle = null;
                    started = false;
                    return true;
                default:
                    return true;
            }
        }
    }

    private final class Grid extends ViewGroup {
        int columns = 2;
        private int measuredWidth;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Tile gestureTile;
        List<DashboardLayout.Cell> gestureSnapshot;
        private DashboardLayout.Cell gestureOrigin, requestedCell;
        private boolean resizing;
        private float rawX, rawY, startRawX, startRawY, anchorX, anchorY, startGridY;
        private int gestureColumns, gestureMinimumHeight;
        private RectF previewBounds;
        private android.animation.ValueAnimator previewAnimation;
        private final Runnable scrollFrame =
                new Runnable() {
                    @Override
                    public void run() {
                        if (gestureTile == null) return;
                        ScrollView scroll = outerScroll();
                        if (scroll != null) {
                            int[] p = new int[2];
                            scroll.getLocationOnScreen(p);
                            float y = rawY - p[1];
                            int speed =
                                    y < ui.dp(64)
                                            ? -ui.dp(9)
                                            : y > scroll.getHeight() - ui.dp(64) ? ui.dp(9) : 0;
                            if (speed != 0) {
                                scroll.scrollBy(0, speed);
                                updateGesture(rawX, rawY);
                            }
                        }
                        postOnAnimation(this);
                    }
                };

        Grid() {
            super(host.activity());
            setWillNotDraw(false);
            setClipChildren(false);
            setClipToPadding(false);
        }

        @Override
        public void onWindowFocusChanged(boolean focused) {
            super.onWindowFocusChanged(focused);
            if (!focused) finishGesture(false);
        }

        float columnWidth() {
            return Math.max(1, measuredWidth / (float) columns);
        }

        float rowHeight() {
            return ui.dp(64 * Math.max(1, ui.textScale));
        }

        private boolean motionEnabled() {
            return android.animation.ValueAnimator.areAnimatorsEnabled();
        }

        private List<DashboardLayout.Cell> snapshot() {
            ArrayList<DashboardLayout.Cell> result = new ArrayList<>();
            for (DashboardConfig.Widget widget : config.widgets)
                result.add(widget.layouts.get(columns).copy());
            return result;
        }

        private void apply(List<DashboardLayout.Cell> cells, int layoutColumns) {
            for (DashboardLayout.Cell cell : cells)
                for (DashboardConfig.Widget widget : config.widgets)
                    if (widget.id.equals(cell.id)) widget.layouts.put(layoutColumns, cell.copy());
        }

        void beginGesture(Tile tile, boolean resize, float x, float y) {
            finishGesture(false);
            config.layout(columns, null);
            gestureSnapshot = snapshot();
            gestureColumns = columns;
            gestureTile = tile;
            resizing = resize;
            gestureOrigin = tile.widget.layouts.get(columns).copy();
            gestureMinimumHeight =
                    Math.max(
                            gestureOrigin.y + gestureOrigin.h,
                            Math.round(getHeight() / rowHeight()));
            requestedCell = null;
            startRawX = rawX = x;
            startRawY = rawY = y;
            int[] p = new int[2];
            tile.getLocationOnScreen(p);
            anchorX = x - p[0];
            anchorY = y - p[1];
            getLocationOnScreen(p);
            startGridY = p[1];
            tile.animate().cancel();
            tile.setTranslationX(0);
            tile.setTranslationY(0);
            tile.setScaleX(1);
            tile.setScaleY(1);
            tile.setElevation(ui.dp(12));
            tile.setAlpha(.94f);
            tile.setBackground(ui.shape(ui.surface, 24, ui.accent));
            updateGesture(x, y);
            postOnAnimation(scrollFrame);
        }

        void updateGesture(float x, float y) {
            if (gestureTile == null) return;
            rawX = x;
            rawY = y;
            int[] p = new int[2];
            getLocationOnScreen(p);
            DashboardLayout.Cell target = gestureOrigin.copy();
            if (resizing) {
                target.w =
                        Math.max(
                                1,
                                Math.min(
                                        columns - target.x,
                                        gestureOrigin.w
                                                + Math.round((x - startRawX) / columnWidth())));
                target.h =
                        Math.max(
                                gestureTile.widget.minimumHeight(),
                                Math.min(
                                        16,
                                        gestureOrigin.h
                                                + Math.round(
                                                        (y - startRawY + startGridY - p[1])
                                                                / rowHeight())));
            } else {
                target.x =
                        Math.max(
                                0,
                                Math.min(
                                        columns - target.w,
                                        Math.round((x - p[0] - anchorX) / columnWidth())));
                target.y =
                        Math.max(0, Math.min(300, Math.round((y - p[1] - anchorY) / rowHeight())));
            }
            if (requestedCell == null
                    || target.x != requestedCell.x
                    || target.y != requestedCell.y
                    || target.w != requestedCell.w
                    || target.h != requestedCell.h) {
                requestedCell = target;
                ArrayList<DashboardLayout.Cell> next = new ArrayList<>();
                for (DashboardLayout.Cell original : gestureSnapshot)
                    next.add(original.id.equals(target.id) ? target.copy() : original.copy());
                apply(DashboardLayout.preview(next, columns, target.id), columns);
                animatePreview(target);
                requestLayout();
            }
            followFinger();
            invalidate();
        }

        DashboardLayout.Cell previewCell() {
            return requestedCell == null ? null : requestedCell.copy();
        }

        private void followFinger() {
            if (gestureTile == null || resizing) return;
            int[] p = new int[2];
            getLocationOnScreen(p);
            gestureTile.setTranslationX(rawX - p[0] - anchorX - gestureTile.getLeft());
            gestureTile.setTranslationY(rawY - p[1] - anchorY - gestureTile.getTop());
        }

        private RectF bounds(DashboardLayout.Cell cell) {
            return new RectF(
                    cell.x * columnWidth(),
                    cell.y * rowHeight(),
                    (cell.x + cell.w) * columnWidth() - ui.dp(10),
                    (cell.y + cell.h) * rowHeight() - ui.dp(10));
        }

        private void animatePreview(DashboardLayout.Cell cell) {
            RectF end = bounds(cell);
            if (previewAnimation != null) previewAnimation.cancel();
            if (previewBounds == null || !motionEnabled()) {
                previewBounds = end;
                return;
            }
            RectF start = new RectF(previewBounds);
            previewAnimation = android.animation.ValueAnimator.ofFloat(0, 1);
            previewAnimation.setDuration(120);
            previewAnimation.addUpdateListener(
                    a -> {
                        float f = (float) a.getAnimatedValue();
                        previewBounds =
                                new RectF(
                                        start.left + (end.left - start.left) * f,
                                        start.top + (end.top - start.top) * f,
                                        start.right + (end.right - start.right) * f,
                                        start.bottom + (end.bottom - start.bottom) * f);
                        invalidate();
                    });
            previewAnimation.start();
        }

        void finishGesture(boolean commit) {
            if (gestureTile == null) return;
            removeCallbacks(scrollFrame);
            if (!commit) apply(gestureSnapshot, gestureColumns);
            else config.save();
            Tile tile = gestureTile;
            gestureTile = null;
            gestureSnapshot = null;
            gestureOrigin = requestedCell = null;
            if (previewAnimation != null) previewAnimation.cancel();
            previewBounds = null;
            tile.setAlpha(1);
            tile.setElevation(0);
            tile.setBackground(ui.shape(ui.surface, 24, editing ? ui.outline : 0));
            if (motionEnabled())
                tile.animate()
                        .translationX(0)
                        .translationY(0)
                        .scaleX(1)
                        .scaleY(1)
                        .setDuration(200)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator())
                        .start();
            else {
                tile.setTranslationX(0);
                tile.setTranslationY(0);
            }
            requestLayout();
            invalidate();
        }

        void settle(String priority) {
            config.layout(columns, priority);
            requestLayout();
            invalidate();
        }

        private ScrollView outerScroll() {
            ViewParent parent = getParent();
            while (parent != null && !(parent instanceof ScrollView)) parent = parent.getParent();
            return parent instanceof ScrollView ? (ScrollView) parent : null;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            measuredWidth = MeasureSpec.getSize(widthSpec);
            int maximum = Math.max(1, Math.min(4, (int) (measuredWidth / ui.dp(150))));
            int requested = preferences.getInt("dashboard_columns", 0);
            int next = requested == 0 ? maximum : Math.min(maximum, Math.max(1, requested));
            // Parents can probe several widths in one measurement pass. Keep the
            // active layout stable until an actual laid-out width changes.
            columns = gestureTile == null ? next : gestureColumns;
            List<DashboardLayout.Cell> cells =
                    config.layout(columns, gestureTile == null ? null : gestureTile.widget.id);
            int end = gestureTile == null ? 0 : gestureMinimumHeight;
            for (int i = 0; i < tiles.size(); i++) {
                DashboardLayout.Cell cell = cells.get(i);
                int width = Math.max(1, Math.round(cell.w * columnWidth()) - ui.dp(10));
                int height = Math.round(cell.h * rowHeight()) - ui.dp(10);
                tiles.get(i)
                        .measure(
                                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
                end = Math.max(end, cell.y + cell.h);
            }
            setMeasuredDimension(
                    measuredWidth,
                    Math.max(
                            ui.dp(100),
                            Math.round(end * rowHeight()) + (editing ? ui.dp(140) : 0)));
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (gestureTile != null && w != oldw) {
                finishGesture(false);
                requestLayout();
            }
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            for (Tile tile : tiles) {
                DashboardLayout.Cell cell = tile.widget.layouts.get(columns);
                int x = Math.round(cell.x * columnWidth()), y = Math.round(cell.y * rowHeight());
                int w = tile.getMeasuredWidth(), h = tile.getMeasuredHeight();
                boolean moved =
                        tile.getLeft() != x
                                || tile.getTop() != y
                                || tile.getWidth() != w
                                || tile.getHeight() != h;
                float oldX = tile.getLeft() + tile.getTranslationX(),
                        oldY = tile.getTop() + tile.getTranslationY();
                float oldW = tile.getWidth() * tile.getScaleX(),
                        oldH = tile.getHeight() * tile.getScaleY();
                tile.layout(x, y, x + w, y + h);
                if (tile == gestureTile) {
                    followFinger();
                    continue;
                }
                if (editing && moved && oldW > 0 && oldH > 0 && motionEnabled()) {
                    tile.animate().cancel();
                    tile.setPivotX(0);
                    tile.setPivotY(0);
                    tile.setTranslationX(oldX - x);
                    tile.setTranslationY(oldY - y);
                    tile.setScaleX(oldW / w);
                    tile.setScaleY(oldH / h);
                    tile.animate()
                            .translationX(0)
                            .translationY(0)
                            .scaleX(1)
                            .scaleY(1)
                            .setDuration(200)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator())
                            .start();
                }
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            if (!editing) return;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Ui.withAlpha(ui.muted, 75));
            for (int col = 0; col <= columns; col++)
                for (float y = 0; y < getHeight(); y += rowHeight())
                    c.drawCircle(col * columnWidth(), y, ui.dp(1.5f), paint);
            if (previewBounds != null) {
                paint.setColor(Ui.withAlpha(ui.accent, 46));
                c.drawRoundRect(previewBounds, ui.dp(24), ui.dp(24), paint);
                paint.setColor(Ui.withAlpha(ui.accent, 180));
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(ui.dp(2));
                paint.setPathEffect(new DashPathEffect(new float[] {ui.dp(7), ui.dp(5)}, 0));
                c.drawRoundRect(previewBounds, ui.dp(24), ui.dp(24), paint);
                paint.setPathEffect(null);
                paint.setStyle(Paint.Style.FILL);
            }
        }
    }

    private void palette() {
        if (config.widgets.size() >= 32) {
            host.toast("Up to 32 widgets per scooter.");
            return;
        }
        LinearLayout list = column();
        for (int i = 0; i < DashboardConfig.TYPES.length; i++) {
            final String type = DashboardConfig.TYPES[i];
            LinearLayout card =
                    ui.settingRow(
                            DashboardConfig.NAMES[i],
                            "",
                            () -> {
                                if (type.equals("lock"))
                                    for (DashboardConfig.Widget item : config.widgets)
                                        if (item.type.equals("lock")) {
                                            host.toast(
                                                    "The lock widget is already on your"
                                                            + " dashboard.");
                                            return;
                                        }
                                DashboardConfig.Widget widget = new DashboardConfig.Widget(type);
                                int end = 0;
                                for (DashboardConfig.Widget item : config.widgets) {
                                    DashboardLayout.Cell cell = item.layouts.get(grid.columns);
                                    if (cell != null) end = Math.max(end, cell.y + cell.h);
                                }
                                widget.layouts.put(
                                        grid.columns,
                                        new DashboardLayout.Cell(
                                                widget.id,
                                                0,
                                                end,
                                                widget.defaultWidth(grid.columns),
                                                widget.defaultHeight()));
                                config.widgets.add(widget);
                                config.save();
                                dismiss();
                                render();
                                options(widget);
                            });
            ui.decorate((TextView) card.getChildAt(0), widgetIcon(type));
            list.addView(card);
            TextView detail = ui.caption(DashboardConfig.DESCRIPTIONS[i]);
            detail.setPadding(ui.dp(2), 0, ui.dp(2), ui.dp(12));
            list.addView(detail);
            ui.divider(list);
        }
        show("Add widget", list);
    }

    private void options(DashboardConfig.Widget widget) {
        if (!editing) return;
        LinearLayout list = column();
        list.addView(
                ui.settingRow(
                        "Title",
                        widget.title,
                        () ->
                                editText(
                                        widget,
                                        "Title",
                                        widget.title,
                                        value -> {
                                            widget.title =
                                                    value.trim().isEmpty()
                                                            ? widget.title
                                                            : value.trim();
                                        })));
        if (widget.type.equals("scooter"))
            list.addView(
                    ui.settingRow(
                            "Scooter name",
                            service == null || service.profiles.active() == null
                                    ? ""
                                    : service.profiles.active().name,
                            () -> {
                                dismiss();
                                actions.rename();
                            }));
        if (Arrays.asList("metric", "gauge", "live", "history").contains(widget.type))
            list.addView(
                    ui.settingRow("Parameter", label(widget.metric), () -> chooseMetric(widget)));
        if (widget.type.equals("readings")
                || widget.type.equals("battery")
                || widget.type.equals("scooter"))
            list.addView(
                    ui.settingRow(
                            "Readings",
                            widget.keys.size() + " selected",
                            () -> chooseReadings(widget)));
        if (widget.type.equals("note"))
            list.addView(
                    ui.settingRow(
                            "Note",
                            widget.note,
                            () ->
                                    editText(
                                            widget,
                                            "Note",
                                            widget.note,
                                            value -> widget.note = value)));
        if (Arrays.asList("live", "history", "health", "capacity").contains(widget.type))
            list.addView(
                    ui.settingRow(
                            "Time window",
                            windowName(widget.windowMs),
                            () -> chooseWindow(widget)));
        if (widget.type.equals("capacity"))
            list.addView(
                    ui.settingRow(
                            "Advertised capacity",
                            ratingLabel(widget),
                            () -> advertisedCapacity(widget)));
        if (widget.type.equals("gauge")) {
            list.addView(
                    ui.settingRow(
                            "Scale minimum",
                            String.format(
                                    Locale.US,
                                    "%.2f %s",
                                    chartNumber(widget.metric, widget.minimum),
                                    chartUnit(widget.metric, readings())),
                            () -> scale(widget, false)));
            list.addView(
                    ui.settingRow(
                            "Scale maximum",
                            String.format(
                                    Locale.US,
                                    "%.2f %s",
                                    chartNumber(widget.metric, widget.maximum),
                                    chartUnit(widget.metric, readings())),
                            () -> scale(widget, true)));
        }
        list.addView(
                ui.settingRow(
                        "Color",
                        new String[] {"Sage", "Blue", "Amber", "Rose"}[widget.color],
                        () -> {
                            dismiss();
                            dialog =
                                    new AlertDialog.Builder(dialogContext)
                                            .setTitle("Widget color")
                                            .setSingleChoiceItems(
                                                    new String[] {"Sage", "Blue", "Amber", "Rose"},
                                                    widget.color,
                                                    (d, n) -> {
                                                        widget.color = n;
                                                        changedWidget(widget);
                                                    })
                                            .setNegativeButton("Cancel", null)
                                            .show();
                        }));
        DashboardLayout.Cell cell = widget.layouts.get(grid.columns);
        if (cell != null) {
            list.addView(
                    ui.settingRow(
                            "Size", cell.w + " columns × " + cell.h + " rows", () -> size(widget)));
            list.addView(
                    ui.settingRow(
                            "Move",
                            "Column " + (cell.x + 1) + ", row " + (cell.y + 1),
                            () -> position(widget)));
        }
        Button duplicate = optionButton(
                        "Duplicate",
                        "copy", ui.accent,
                        () -> {
                            if (widget.type.equals("lock")) {
                                host.toast("One lock widget per dashboard.");
                                return;
                            }
                            if (config.widgets.size() >= 32) {
                                host.toast("Up to 32 widgets per scooter.");
                                return;
                            }
                            DashboardConfig.Widget copy = new DashboardConfig.Widget(widget.type);
                            copy.title = widget.title;
                            copy.metric = widget.metric;
                            copy.note = widget.note;
                            copy.keys.clear();
                            copy.keys.addAll(widget.keys);
                            copy.color = widget.color;
                            copy.windowMs = widget.windowMs;
                            copy.minimum = widget.minimum;
                            copy.maximum = widget.maximum;
                            copy.advertisedWh = widget.advertisedWh;
                            config.widgets.add(copy);
                            changedWidget(copy);
                        });
        Button remove = optionButton(
                        "Remove widget",
                        "trash", ui.danger,
                        () -> {
                            config.widgets.remove(widget);
                            config.save();
                            dismiss();
                            render();
                        });
        LinearLayout actions = row();
        actions.setGravity(Gravity.CENTER);
        actions.setBaselineAligned(false);
        actions.setPadding(0, ui.dp(16), 0, 0);
        LinearLayout.LayoutParams duplicateSize = new LinearLayout.LayoutParams(0, -1, 1);
        duplicateSize.setMarginEnd(ui.dp(12));
        actions.addView(duplicate, duplicateSize);
        actions.addView(remove, new LinearLayout.LayoutParams(0, -1, 1));
        showOptions(widget.title, list, actions);
    }

    private Button optionButton(String label, String icon, int color, Runnable action) {
        Button button = new Button(dialogContext);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14 * ui.textScale);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(color);
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(ui.dp(48));
        button.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
        button.setBackgroundTintList(null);
        button.setStateListAnimator(null);
        boolean destructive = color == ui.danger;
        button.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Ui.withAlpha(color, 35)),
                ui.shape(destructive ? Ui.withAlpha(color, 20) : ui.raised, 16,
                        destructive ? Ui.withAlpha(color, 120) : ui.outline), null));
        ui.decorate(button, icon);
        button.setCompoundDrawablePadding(ui.dp(8));
        button.setOnClickListener(v -> action.run());
        return button;
    }

    /** Keep the close affordance and widget actions outside the scrolling settings. */
    private void showOptions(String title, View settings, View actions) {
        dismiss();
        LinearLayout sheet = column();
        sheet.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(20));
        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = ui.text(title, 20, true);
        heading.setTag("widget-options-title");
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        FrameLayout close = iconAction("close", "Close widget options", this::dismiss);
        LinearLayout.LayoutParams closeSize = new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48));
        closeSize.setMarginStart(ui.dp(8));
        header.addView(close, closeSize);
        sheet.addView(header, new LinearLayout.LayoutParams(-1, -2));
        ScrollView scroll = new ScrollView(host.activity());
        scroll.setClipToPadding(false);
        scroll.setPadding(0, ui.dp(8), 0, 0);
        scroll.addView(settings);
        sheet.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        sheet.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        dialog = new AlertDialog.Builder(dialogContext).create();
        dialog.show();
        // Replace AlertDialog's wrapping panel so the weighted settings area fills
        // the sheet and keeps the action row against its bottom edge.
        dialog.setContentView(sheet);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(ui.shape(ui.surface, 24, 0));
            dialog.getWindow().setLayout(-1,
                    (int) (host.activity().getResources().getDisplayMetrics().heightPixels * .86));
        }
    }

    private void changedWidget(DashboardConfig.Widget widget) {
        config.save();
        dismiss();
        render();
    }

    private Double rating(DashboardConfig.Widget widget) {
        if (widget.advertisedWh > 0) return widget.advertisedWh;
        ScooterProfiles.Profile profile = service == null ? null : service.profiles.active();
        // NAVEE US UT5 MAX specification: 46.8 V nominal × 15.3 Ah.
        return profile != null && profile.pid() == 2538 ? 716.04 : null;
    }

    private String ratingLabel(DashboardConfig.Widget widget) {
        Double value = rating(widget);
        return value == null
                ? "Set comparison value"
                : String.format(
                        Locale.US, "%.2f Wh%s", value, widget.advertisedWh == 0 ? " · model" : "");
    }

    private void advertisedCapacity(DashboardConfig.Widget widget) {
        dismiss();
        LinearLayout content = column();
        content.addView(
                ui.caption(
                        "Use the pack’s advertised energy in Wh, or nominal voltage × rated Ah."
                            + " Enter 0 to use the model rating when known. This only changes the"
                            + " comparison."));
        EditText input = new EditText(dialogContext);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setText(Double.toString(widget.advertisedWh));
        content.addView(input);
        dialog =
                new AlertDialog.Builder(dialogContext)
                        .setTitle("Advertised capacity (Wh)")
                        .setView(content)
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Save", null)
                        .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                        v -> {
                            try {
                                double n = Double.parseDouble(input.getText().toString());
                                if (!Double.isFinite(n) || n < 0 || n > 10000)
                                    throw new IllegalArgumentException();
                                widget.advertisedWh = n;
                                changedWidget(widget);
                            } catch (Exception e) {
                                input.setError("Enter Wh between 0 and 10,000.");
                            }
                        });
    }

    private void showCapacityMethod(DashboardConfig.Widget widget) {
        LinearLayout content = column();
        content.addView(ui.text("Experimental capacity estimate", 20, true));
        content.addView(
                ui.caption(
                        "Estimated full-pack energy = 100 × recorded energy ÷ battery-percentage"
                            + " points used. Qualified rides are combined by their percentage drop,"
                            + " with the active ride replacing its previous contribution."));
        content.addView(
                ui.caption(
                        "Use rides lasting at least a minute, each dropping at least 5 percentage"
                            + " points, with at least 20 points combined and 95% electrical"
                            + " coverage. Gaps, mismatched endpoints and battery-gauge rises are"
                            + " filtered. The selected window controls how much recent ride data is"
                            + " included."));
        content.addView(
                ui.caption(
                        "With confirmed current direction, the estimate uses net energy. Otherwise"
                            + " it shows a separate absolute-throughput proxy: returned energy is"
                            + " counted too, so it can overstate usable capacity. A percentage"
                            + " beside this proxy is not battery health."));
        content.addView(
                ui.caption(
                        "Cold, load, gauge rounding and recalibration affect both estimates. Energy"
                            + " per percentage point also changes with battery voltage. These"
                            + " estimates do not establish the cells’ actual capacity or prove a"
                            + " pack is smaller than advertised. No extra discharge or ride is"
                            + " started."));
        content.addView(
                ui.caption(
                        "UT5 MAX model reference: 46.8 V × 15.3 Ah = 716.04 Wh, from NAVEE US"
                                + " product specifications. Other models need a comparison value in"
                                + " widget options."));
        show("How this is estimated", content);
    }

    private void editText(
            DashboardConfig.Widget widget,
            String label,
            String value,
            java.util.function.Consumer<String> apply) {
        dismiss();
        EditText input = new EditText(dialogContext);
        input.setText(value);
        input.setSelectAllOnFocus(true);
        input.setMaxLines(label.equals("Note") ? 6 : 2);
        input.setFilters(
                new android.text.InputFilter[] {
                    new android.text.InputFilter.LengthFilter(label.equals("Note") ? 1200 : 80)
                });
        dialog =
                new AlertDialog.Builder(dialogContext)
                        .setTitle(label)
                        .setView(input)
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton(
                                "Save",
                                (d, w) -> {
                                    apply.accept(input.getText().toString());
                                    changedWidget(widget);
                                })
                        .show();
    }

    private void chooseMetric(DashboardConfig.Widget widget) {
        List<Telemetry.Metric> metrics = new Telemetry().metrics();
        LinearLayout content = column();
        EditText search = new EditText(dialogContext);
        search.setHint("Search parameters");
        search.setSingleLine(true);
        ui.styleSearch(search);
        content.addView(search, ui.searchLayout());
        LinearLayout rows = column();
        content.addView(rows);
        Runnable fill =
                () -> {
                    rows.removeAllViews();
                    String q = search.getText().toString().toLowerCase(Locale.ROOT);
                    for (Telemetry.Metric metric : metrics)
                        if ((metric.label + metric.group + metric.key)
                                .toLowerCase(Locale.ROOT)
                                .contains(q)) {
                            LinearLayout row =
                                    ui.settingRow(
                                            metric.label,
                                            metric.group,
                                            () -> {
                                                widget.metric = metric.key;
                                                changedWidget(widget);
                                            });
                            ui.decorate((TextView) row.getChildAt(0), ui.featureIcon(metric.key));
                            rows.addView(row);
                        }
                };
        search.addTextChangedListener(
                new android.text.TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int st, int c, int a) {}

                    public void onTextChanged(CharSequence s, int st, int b, int c) {
                        fill.run();
                    }

                    public void afterTextChanged(android.text.Editable e) {}
                });
        fill.run();
        show("Choose parameter", content);
        SearchFocus.bind(search, dialog.getWindow());
    }

    private void chooseReadings(DashboardConfig.Widget widget) {
        dismiss();
        List<Telemetry.Metric> metrics = new Telemetry().metrics();
        String[] names = new String[metrics.size()];
        boolean[] checked = new boolean[names.length];
        LinkedHashSet<String> chosen = new LinkedHashSet<>(widget.keys);
        for (int i = 0; i < names.length; i++) {
            names[i] = metrics.get(i).group + " · " + metrics.get(i).label;
            checked[i] = chosen.contains(metrics.get(i).key);
        }
        dialog =
                new AlertDialog.Builder(dialogContext)
                        .setTitle("Choose up to 12 readings")
                        .setMultiChoiceItems(
                                names,
                                checked,
                                (d, n, on) -> {
                                    if (on) chosen.add(metrics.get(n).key);
                                    else chosen.remove(metrics.get(n).key);
                                })
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Save", null)
                        .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                        v -> {
                            if (chosen.size() > 12) {
                                host.toast("Choose up to 12 readings.");
                                return;
                            }
                            widget.keys.clear();
                            widget.keys.addAll(chosen);
                            changedWidget(widget);
                        });
    }

    private String windowName(long ms) {
        if (ms < 3600000) return ms / 60000 + " minutes";
        if (ms < 86400000) return ms / 3600000 + " hours";
        return ms / 86400000 + " days";
    }

    private void chooseWindow(DashboardConfig.Widget widget) {
        dismiss();
        long[] times = {
            2 * 60000L,
            5 * 60000L,
            15 * 60000L,
            3600000L,
            6 * 3600000L,
            86400000L,
            7 * 86400000L,
            30 * 86400000L,
            90 * 86400000L,
            365 * 86400000L,
            730 * 86400000L
        };
        if (widget.type.equals("capacity"))
            times =
                    new long[] {
                        7 * 86400000L,
                        30 * 86400000L,
                        90 * 86400000L,
                        180 * 86400000L,
                        365 * 86400000L,
                        730 * 86400000L
                    };
        final long[] options = times;
        String[] labels = new String[times.length];
        for (int i = 0; i < times.length; i++) labels[i] = windowName(times[i]);
        dialog =
                new AlertDialog.Builder(dialogContext)
                        .setTitle(
                                widget.type.equals("capacity")
                                        ? "Ride data window"
                                        : "Chart time window")
                        .setItems(
                                labels,
                                (d, n) -> {
                                    widget.windowMs = options[n];
                                    changedWidget(widget);
                                })
                        .setNegativeButton("Cancel", null)
                        .show();
    }

    private void scale(DashboardConfig.Widget widget, boolean maximum) {
        dismiss();
        EditText input = new EditText(dialogContext);
        input.setInputType(
                InputType.TYPE_CLASS_NUMBER
                        | InputType.TYPE_NUMBER_FLAG_DECIMAL
                        | InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setText(
                Double.toString(
                        chartNumber(widget.metric, maximum ? widget.maximum : widget.minimum)));
        dialog =
                new AlertDialog.Builder(dialogContext)
                        .setTitle(
                                maximum
                                        ? "Scale maximum (display units)"
                                        : "Scale minimum (display units)")
                        .setView(input)
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Save", null)
                        .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                        v -> {
                            try {
                                double n = Double.parseDouble(input.getText().toString());
                                if (!units().equals("km")
                                        && (widget.metric.endsWith("_km")
                                                || widget.metric.endsWith("_kmh"))) n *= 1.609344;
                                if (!Double.isFinite(n)
                                        || (maximum ? n <= widget.minimum : n >= widget.maximum))
                                    throw new IllegalArgumentException();
                                if (maximum) widget.maximum = n;
                                else widget.minimum = n;
                                changedWidget(widget);
                            } catch (Exception e) {
                                input.setError("Enter a finite range with maximum above minimum.");
                            }
                        });
    }

    private void size(DashboardConfig.Widget widget) {
        dismiss();
        DashboardLayout.Cell cell = widget.layouts.get(grid.columns);
        LinearLayout controls = column();
        NumberPicker width = new NumberPicker(dialogContext);
        width.setMinValue(1);
        width.setMaxValue(grid.columns);
        width.setValue(cell.w);
        NumberPicker height = new NumberPicker(dialogContext);
        height.setMinValue(widget.minimumHeight());
        height.setMaxValue(16);
        height.setValue(Math.max(widget.minimumHeight(), cell.h));
        controls.addView(ui.caption("Columns"));
        controls.addView(width);
        controls.addView(ui.caption("Rows"));
        controls.addView(height);
        dialog =
                new AlertDialog.Builder(dialogContext)
                        .setTitle("Widget size")
                        .setView(controls)
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton(
                                "Apply",
                                (d, n) -> {
                                    DashboardLayout.Cell latest = widget.layouts.get(grid.columns);
                                    latest.w = width.getValue();
                                    latest.h = height.getValue();
                                    grid.settle(widget.id);
                                    changedWidget(widget);
                                })
                        .show();
    }

    private void position(DashboardConfig.Widget widget) {
        dismiss();
        DashboardLayout.Cell cell = widget.layouts.get(grid.columns);
        LinearLayout controls = column();
        EditText x = new EditText(dialogContext), y = new EditText(dialogContext);
        x.setInputType(InputType.TYPE_CLASS_NUMBER);
        y.setInputType(InputType.TYPE_CLASS_NUMBER);
        x.setText(Integer.toString(cell.x + 1));
        y.setText(Integer.toString(cell.y + 1));
        controls.addView(ui.caption("Column (1–" + grid.columns + ")"));
        controls.addView(x);
        controls.addView(ui.caption("Row (1–301)"));
        controls.addView(y);
        dialog =
                new AlertDialog.Builder(dialogContext)
                        .setTitle("Move widget")
                        .setView(controls)
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Move", null)
                        .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                        v -> {
                            try {
                                int xx = Integer.parseInt(x.getText().toString()) - 1,
                                        yy = Integer.parseInt(y.getText().toString()) - 1;
                                if (xx < 0 || xx >= grid.columns || yy < 0 || yy > 300)
                                    throw new IllegalArgumentException();
                                DashboardLayout.Cell latest = widget.layouts.get(grid.columns);
                                latest.x = xx;
                                latest.y = yy;
                                grid.settle(widget.id);
                                changedWidget(widget);
                            } catch (Exception e) {
                                host.toast("Use the column and row ranges shown.");
                            }
                        });
    }

    private void layoutOptions() {
        LinearLayout choices = column();
        int selectedColumns = preferences.getInt("dashboard_columns", 0);
        choices.addView(
                ui.settingRow(
                        "Columns",
                        selectedColumns == 0 ? "Adaptive" : Integer.toString(selectedColumns),
                        () -> {
                            dismiss();
                            dialog =
                                    new AlertDialog.Builder(dialogContext)
                                            .setTitle("Dashboard columns")
                                            .setSingleChoiceItems(
                                                    new String[] {"Adaptive", "1", "2", "3", "4"},
                                                    preferences.getInt("dashboard_columns", 0),
                                                    (d, n) -> {
                                                        preferences
                                                                .edit()
                                                                .putInt("dashboard_columns", n)
                                                                .apply();
                                                        dismiss();
                                                        grid.requestLayout();
                                                    })
                                            .setNegativeButton("Cancel", null)
                                            .show();
                        }));
        choices.addView(
                ui.caption(
                        "Layouts are saved separately for each column count. Narrow screens use"
                                + " fewer columns."));
        choices.addView(
                ui.settingRow(
                        "Restore default widgets",
                        "",
                        () -> {
                            dismiss();
                            dialog =
                                    new AlertDialog.Builder(dialogContext)
                                            .setTitle("Restore default dashboard?")
                                            .setMessage(
                                                    "This replaces this scooter’s widget layout."
                                                            + " Saved readings and rides are kept.")
                                            .setNegativeButton("Cancel", null)
                                            .setPositiveButton(
                                                    "Restore",
                                                    (d, n) -> {
                                                        config.reset();
                                                        render();
                                                    })
                                            .show();
                        }));
        show("Dashboard layout", choices);
    }

    private void show(String title, View content) {
        dismiss();
        ScrollView scroll = new ScrollView(host.activity());
        scroll.setFillViewport(false);
        int p = ui.dp(20);
        scroll.setPadding(p, 0, p, p);
        scroll.addView(content);
        dialog =
                new AlertDialog.Builder(dialogContext)
                        .setTitle(title)
                        .setView(scroll)
                        .setNegativeButton("Close", null)
                        .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(ui.accent);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(ui.shape(ui.surface, 24, 0));
            dialog.getWindow()
                    .setLayout(
                            -1,
                            (int)
                                    (host.activity().getResources().getDisplayMetrics().heightPixels
                                            * .86));
        }
    }

    private void dismiss() {
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
    }

    public boolean isEditing() {
        return editing;
    }

    public boolean back() {
        if (editing) {
            editing = false;
            preferences.edit().putBoolean("dashboard_editing", false).apply();
            render();
            return true;
        }
        return false;
    }

    @Override
    public void close() {
        closed = true;
        for (Tile tile : tiles) tile.active = false;
        dismiss();
        if (grid != null) grid.finishGesture(false);
    }
}
