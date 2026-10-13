package local.navee.offlinelock;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Small, dependency-free native design system. Shared by compact and unfolded layouts. */
public final class Ui {
    public final Context context;
    public final boolean dark;
    public final int bg, surface, text, muted, accent, blue, orange, outline, danger, raised;
    public final float textScale;
    private final float density;
    private static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private static final Typeface BOLD = Typeface.create("sans-serif", Typeface.BOLD);

    public Ui(Context context, boolean dark, float textScale) {
        this.context = context; this.dark = dark;
        this.textScale = Math.max(0.75f, Math.min(1.8f, textScale));
        density = context.getResources().getDisplayMetrics().density;
        bg = Color.parseColor(dark ? "#101210" : "#F3F2ED");
        surface = Color.parseColor(dark ? "#1C201D" : "#FFFFFF");
        raised = Color.parseColor(dark ? "#282E29" : "#E8EBE3");
        text = Color.parseColor(dark ? "#F5F5EF" : "#20261F");
        muted = Color.parseColor(dark ? "#ADB6AB" : "#616B5D");
        accent = Color.parseColor(dark ? "#C6E5A1" : "#416B30");
        blue = Color.parseColor(dark ? "#A7C9EB" : "#376184");
        orange = Color.parseColor(dark ? "#E8C590" : "#865B20");
        outline = Color.parseColor(dark ? "#30382F" : "#DDE2D7");
        danger = Color.parseColor(dark ? "#FF929F" : "#B3263E");
    }
    public int dp(float value) { return Math.round(value * density); }
    public float sp(float value) { return value * context.getResources().getDisplayMetrics().scaledDensity * textScale; }
    public TextView text(String value, float sp, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value == null ? "" : value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * textScale);
        view.setTextColor(text); view.setTypeface(bold ? BOLD : REGULAR);
        view.setIncludeFontPadding(false); view.setLineSpacing(dp(3), 1);
        view.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        return view;
    }
    public TextView caption(String value) {
        TextView view = text(value, 12, false); view.setTextColor(muted); return view;
    }
    public LinearLayout card() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(18), dp(18), dp(18), dp(18));
        layout.setBackground(shape(surface, 24, 0));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(12); layout.setLayoutParams(params); return layout;
    }
    public TextView button(String label, Runnable action) {
        TextView button = text(label, 14, true);
        button.setTypeface(MEDIUM); button.setGravity(Gravity.CENTER);
        button.setPadding(dp(20), dp(13), dp(20), dp(13));
        button.setMinHeight(dp(48));
        int foreground = dark ? bg : Color.WHITE;
        button.setTextColor(new ColorStateList(new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
                new int[] {muted, foreground}));
        StateListDrawable fill = new StateListDrawable();
        fill.addState(new int[] {-android.R.attr.state_enabled}, shape(raised, 100, outline));
        fill.addState(new int[] {}, shape(accent, 100, 0));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(withAlpha(dark ? bg : Color.WHITE, 45)), fill, null));
        button.setClickable(true); button.setFocusable(true);
        button.setContentDescription(label);
        button.setOnClickListener(v -> { if (action != null) action.run(); });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(6); params.bottomMargin = dp(6); button.setLayoutParams(params);
        return button;
    }
    public TextView secondaryButton(String label, Runnable action) {
        TextView button = button(label, action);
        button.setTextColor(new ColorStateList(new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
                new int[] {muted, accent}));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(withAlpha(accent, 30)), shape(raised, 100, outline), null));
        return button;
    }
    public View icon(String key, int size, int color) { return new IconView(key, size, color); }
    public LinearLayout metric(String label, String value, String hint, int color) {
        LinearLayout card = card();
        TextView title = caption(label); title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12 * textScale);
        card.addView(title);
        TextView amount = text(value == null ? "—" : value, 30, true);
        amount.setTextColor(color); amount.setPadding(0, dp(9), 0, dp(7));
        card.addView(amount);
        if (hint != null && !hint.isEmpty()) card.addView(caption(hint));
        card.setContentDescription(label + ", " + (value == null ? "Not reported" : value)
                + (hint == null || hint.isEmpty() ? "" : ", " + hint));
        return card;
    }
    /** Only a performed click dispatches a change; programmatic setChecked never sends commands. */
    public View toggleRow(String label, String description, Boolean value, boolean enabled,
            Consumer<Boolean> onChange) {
        LinearLayout row = card(); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(76));
        LinearLayout words = new LinearLayout(context); words.setOrientation(LinearLayout.VERTICAL);
        TextView heading = text(label, 15, true); words.addView(heading);
        if (description != null && !description.isEmpty()) {
            TextView detail = caption(description); detail.setPadding(0, dp(6), 0, 0); words.addView(detail);
        }
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        Switch toggle = new Switch(context);
        toggle.setShowText(false); toggle.setTextOn(""); toggle.setTextOff("");
        toggle.setChecked(Boolean.TRUE.equals(value));
        toggle.setEnabled(enabled && value != null);
        toggle.setMinimumWidth(dp(56)); toggle.setMinimumHeight(dp(48));
        toggle.setThumbTintList(new ColorStateList(new int[][] {new int[] {-android.R.attr.state_enabled},
                new int[] {android.R.attr.state_checked}, new int[] {}}, new int[] {muted, accent, muted}));
        toggle.setTrackTintList(new ColorStateList(new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
                new int[] {withAlpha(accent, 75), outline}));
        toggle.setContentDescription(label + (value == null ? ", state not reported" : ""));
        if (Build.VERSION.SDK_INT >= 30) toggle.setStateDescription(value == null ? "Not reported"
                : value ? "On" : "Off");
        toggle.setOnClickListener(v -> { if (toggle.isEnabled() && onChange != null) onChange.accept(toggle.isChecked()); });
        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(-2, -2);
        toggleParams.leftMargin = dp(14); row.addView(toggle, toggleParams);
        row.setOnClickListener(v -> { if (toggle.isEnabled()) toggle.performClick(); });
        row.setFocusable(false);
        if (!enabled || value == null) heading.setTextColor(muted);
        return row;
    }
    /** Quiet, aligned navigation row. Child 1 is the value text for live updates. */
    public LinearLayout settingRow(String label, String value, Runnable action) {
        LinearLayout row = new LinearLayout(context); row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL); row.setMinimumHeight(dp(62));
        row.setPadding(dp(2), dp(13), dp(2), dp(13));
        TextView title = text(label, 15, false);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView amount = text(value == null ? "" : value, 14, false);
        amount.setTextColor(muted); amount.setGravity(Gravity.END); amount.setMaxLines(2);
        amount.setMaxWidth(dp(148));
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-2, -2); vp.leftMargin = dp(12);
        row.addView(amount, vp);
        if (action != null) {
            View chevron = icon("chevron", 15, muted);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(15), dp(15)); cp.leftMargin = dp(10);
            row.addView(chevron, cp); row.setFocusable(true);
            row.setBackground(new RippleDrawable(ColorStateList.valueOf(withAlpha(accent, 26)), shape(Color.TRANSPARENT, 10, 0), null));
            row.setOnClickListener(v -> action.run());
        }
        return row;
    }
    public void divider(LinearLayout parent) {
        View line = new View(context); line.setBackgroundColor(withAlpha(outline, 125));
        parent.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
    }
    public View nav(String label, String iconKey, boolean selected, Runnable action) {
        LinearLayout item = new LinearLayout(context);
        item.setOrientation(LinearLayout.VERTICAL); item.setGravity(Gravity.CENTER);
        item.setPadding(dp(2), dp(8), dp(2), dp(8)); item.setMinimumHeight(dp(64));
        item.setBackground(new RippleDrawable(ColorStateList.valueOf(withAlpha(accent, 30)),
                shape(selected ? withAlpha(accent, dark ? 28 : 20) : Color.TRANSPARENT, 18, 0), null));
        item.addView(icon(iconKey, 24, selected ? accent : muted), new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView caption = text(label, 10, selected); caption.setMaxLines(2); caption.setTextColor(selected ? accent : muted);
        caption.setGravity(Gravity.CENTER); caption.setPadding(0, dp(5), 0, 0); item.addView(caption);
        item.setContentDescription(label); item.setSelected(selected); item.setFocusable(true);
        if (Build.VERSION.SDK_INT >= 30) item.setStateDescription(selected ? "Selected" : "");
        item.setOnClickListener(v -> { if (action != null) action.run(); });
        return item;
    }
    /** A compact section picker that keeps every destination visible on narrow screens. */
    public View sectionTabs(String[] labels, String selected, Consumer<String> onSelect) {
        final class Tabs extends ViewGroup {
            String current = selected;
            Tabs() { super(context); setPadding(0, dp(2), 0, dp(12)); }
            void style() {
                for (int i = 0; i < getChildCount(); i++) {
                    TextView button = (TextView) getChildAt(i);
                    boolean active = button.getText().toString().equals(current);
                    button.setSelected(active); button.setTextColor(active ? accent : muted);
                    button.setBackground(new RippleDrawable(ColorStateList.valueOf(withAlpha(accent, 30)),
                            shape(active ? withAlpha(accent, dark ? 28 : 20) : raised, 15,
                                    active ? accent : outline), null));
                    if (Build.VERSION.SDK_INT >= 30) button.setStateDescription(active ? "Selected" : "");
                }
            }
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int width = MeasureSpec.getSize(widthSpec), available = Math.max(1, width - getPaddingLeft() - getPaddingRight());
                int x = 0, y = getPaddingTop(), rowHeight = 0;
                for (int i = 0; i < getChildCount(); i++) {
                    View child = getChildAt(i);
                    child.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST),
                            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                    if (x > 0 && x + child.getMeasuredWidth() > available) { y += rowHeight + dp(6); x = 0; rowHeight = 0; }
                    x += child.getMeasuredWidth() + dp(6); rowHeight = Math.max(rowHeight, child.getMeasuredHeight());
                }
                setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(y + rowHeight + getPaddingBottom(), heightSpec));
            }
            @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
                int available = getWidth() - getPaddingLeft() - getPaddingRight(), x = 0, y = getPaddingTop(), rowHeight = 0;
                for (int i = 0; i < getChildCount(); i++) {
                    View child = getChildAt(i); int w = child.getMeasuredWidth(), h = child.getMeasuredHeight();
                    if (x > 0 && x + w > available) { y += rowHeight + dp(6); x = 0; rowHeight = 0; }
                    child.layout(getPaddingLeft() + x, y, getPaddingLeft() + x + w, y + h);
                    x += w + dp(6); rowHeight = Math.max(rowHeight, h);
                }
            }
        }
        Tabs tabs = new Tabs();
        for (String label : labels) {
            TextView button = text(label, 12, false); button.setTypeface(MEDIUM);
            button.setGravity(Gravity.CENTER); button.setMinHeight(dp(48)); button.setMinWidth(dp(48));
            button.setPadding(dp(12), dp(8), dp(12), dp(8)); button.setFocusable(true); button.setContentDescription(label);
            button.setOnClickListener(v -> {
                tabs.current = label; tabs.style(); if (onSelect != null) onSelect.accept(label);
            });
            tabs.addView(button, new ViewGroup.LayoutParams(-2, -2));
        }
        tabs.style(); return tabs;
    }
    public GradientDrawable shape(int fill, float radius, int border) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(fill); drawable.setCornerRadius(dp(radius));
        if (border != 0) drawable.setStroke(dp(1), border); return drawable;
    }
    public static int withAlpha(int color, int alpha) { return (color & 0x00ffffff) | (Math.max(0, Math.min(255, alpha)) << 24); }

    public class BatteryRing extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private double percent = Double.NaN;
        private String subtitle = "Not reported";
        private boolean charging;
        public BatteryRing() {
            super(context); setMinimumHeight(dp(140)); setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            setLayoutParams(new LinearLayout.LayoutParams(dp(160), dp(176)));
        }
        public BatteryRing(Context ignored) { this(); }
        public void set(double percent, String subtitle, boolean charging) {
            this.percent = !Double.isNaN(percent) && !Double.isInfinite(percent) && percent >= 0 && percent <= 100 ? percent : Double.NaN;
            this.subtitle = subtitle == null ? "" : subtitle; this.charging = charging;
            setContentDescription("Battery " + (Double.isNaN(this.percent) ? "not reported" : Math.round(this.percent) + " percent")
                    + (charging ? ", charging" : "") + ", " + this.subtitle); invalidate();
        }
        public void set(Double percent, String subtitle, boolean charging) { set(percent == null ? Double.NaN : percent.doubleValue(), subtitle, charging); }
        @Override protected void onMeasure(int width, int height) {
            setMeasuredDimension(resolveSize(dp(160), width), resolveSize(dp(176), height));
        }
        @Override protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = Math.min(getHeight() / 2f, getWidth() / 2f) - dp(1);
            float radius = Math.min(getWidth(), getHeight() - dp(12)) / 2f - dp(13);
            RectF circle = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(10)); paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(outline); canvas.drawArc(circle, 135, 270, false, paint);
            int color = !Double.isNaN(percent) && percent < 15 ? danger : !Double.isNaN(percent) && percent < 30 ? orange : accent;
            if (!Double.isNaN(percent)) { paint.setColor(color); canvas.drawArc(circle, 135, (float) (270 * percent / 100), false, paint); }
            paint.setStyle(Paint.Style.FILL); paint.setTypeface(BOLD); paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(Math.min(sp(38), dp(46))); paint.setColor(text);
            String amount = Double.isNaN(percent) ? "—" : Long.toString(Math.round(percent));
            canvas.drawText(amount, cx, cy + dp(3), paint);
            paint.setTypeface(MEDIUM); paint.setTextSize(Math.min(sp(12), dp(15))); paint.setColor(muted);
            canvas.drawText(Double.isNaN(percent) ? "NO READING" : "PERCENT", cx, cy + dp(25), paint);
            paint.setTextSize(Math.min(sp(11), dp(14))); paint.setColor(charging ? accent : muted);
            String label = charging ? "Charging" + (subtitle.isEmpty() ? "" : " · " + subtitle) : subtitle;
            label = TextUtils.ellipsize(label, new android.text.TextPaint(paint), Math.max(dp(40), getWidth() - dp(8)), TextUtils.TruncateAt.END).toString();
            canvas.drawText(label, cx, getHeight() - dp(9), paint);
        }
    }
    public final class CustomBatteryRing extends BatteryRing {
        public CustomBatteryRing() { super(); }
        public CustomBatteryRing(Context ignored) { super(); }
    }

    public static final class Point {
        public final long millis;
        public final double value;
        public final boolean gapBefore;
        public Point(long millis, double value) { this(millis, value, false); }
        public Point(long millis, double value, boolean gapBefore) { this.millis = millis; this.value = value; this.gapBefore = gapBefore; }
    }
    /** Actual sensor values only. Gaps break the path; touch reads a measured point. */
    public final class ChartView extends View {
        public static final int MAX_POINTS = 1600;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ArrayList<Point> points = new ArrayList<Point>();
        private String unit = "", empty = "No measured data yet";
        private int color = accent, selected = -1;
        private double min = 0, max = 1;
        private long first = 0, last = 1;
        private float left, top, right, bottom;
        private final SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss", Locale.US);
        public ChartView() {
            super(context); setMinimumHeight(dp(210)); setFocusable(true);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            setLayoutParams(new LinearLayout.LayoutParams(-1, dp(220)));
        }
        public ChartView(Context ignored) { this(); }
        public void set(List<Point> source, String unit, int color, String empty) {
            points.clear(); this.unit = unit == null ? "" : unit; this.color = color;
            this.empty = empty == null ? "No measured data yet" : empty;
            if (source != null && !source.isEmpty()) {
                int count = Math.min(MAX_POINTS, source.size());
                int previousIndex = -1;
                for (int i = 0; i < count; i++) {
                    int index = count <= 1 ? 0 : (int) ((long) i * (source.size() - 1) / (count - 1));
                    Point point = source.get(index);
                    boolean gap = false;
                    for (int skipped = previousIndex + 1; skipped <= index; skipped++) {
                        Point candidate = source.get(skipped);
                        if (candidate == null || candidate.gapBefore || !Double.isFinite(candidate.value)) gap = true;
                    }
                    if (point != null && Double.isFinite(point.value)) { points.add(new Point(point.millis, point.value, gap)); previousIndex = index; }
                }
                Collections.sort(points, new Comparator<Point>() { public int compare(Point a, Point b) {
                    return a.millis < b.millis ? -1 : a.millis > b.millis ? 1 : 0;
                }});
                for (int i = points.size() - 2; i >= 0; i--) if (points.get(i).millis == points.get(i + 1).millis) points.remove(i);
            }
            if (!points.isEmpty()) {
                min = max = points.get(0).value;
                for (Point p : points) { min = Math.min(min, p.value); max = Math.max(max, p.value); }
                double center = min / 2 + max / 2;
                // A few milliwatts/millivolts of sensor noise should remain nearly flat.
                // This changes the viewing scale only; every measured point stays intact.
                double minimumSpan = this.unit.equals("V") ? 0.1
                        : this.unit.equals("W") ? Math.max(1, Math.abs(center) * 0.02) : 1;
                double span = Math.max(max - min, minimumSpan);
                min = center - span * 0.62; max = center + span * 0.62;
                first = points.get(0).millis; last = points.get(points.size() - 1).millis;
                if (last == first) last = first + 1000;
            }
            selected = Math.min(selected, points.size() - 1);
            setContentDescription(points.isEmpty() ? this.empty : "Measured " + this.unit + " chart, " + points.size()
                    + " samples. Displayed axis " + axisNumber(min) + " to " + axisNumber(max) + ". Touch to inspect readings.");
            invalidate();
        }
        public int pointCount() { return points.size(); }
        @Override protected void onMeasure(int width, int height) {
            setMeasuredDimension(resolveSize(dp(300), width), resolveSize(dp(220), height));
        }
        private float x(long millis) { return left + (float) ((millis - first) / (double) (last - first)) * (right - left); }
        private float y(double value) { return bottom - (float) ((value - min) / (max - min)) * (bottom - top); }
        @Override protected void onDraw(Canvas canvas) {
            left = dp(44); right = getWidth() - dp(14); top = dp(40); bottom = getHeight() - dp(35);
            if (right <= left || bottom <= top) return;
            paint.setTypeface(REGULAR); paint.setTextSize(Math.min(sp(10), dp(14)));
            paint.setStrokeWidth(dp(1)); paint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < 4; i++) {
                float yy = top + (bottom - top) * i / 3;
                paint.setColor(outline); canvas.drawLine(left, yy, right, yy, paint);
                if (!points.isEmpty()) {
                    paint.setColor(muted); paint.setTextAlign(Paint.Align.RIGHT);
                    canvas.drawText(axisNumber(max - (max - min) * i / 3), left - dp(7), yy + dp(3), paint);
                }
            }
            if (points.isEmpty()) {
                paint.setColor(muted); paint.setTextSize(Math.min(sp(13), dp(17))); paint.setTextAlign(Paint.Align.CENTER);
                String message = TextUtils.ellipsize(empty, new android.text.TextPaint(paint), right - left, TextUtils.TruncateAt.END).toString();
                canvas.drawText(message, (left + right) / 2, (top + bottom) / 2, paint); return;
            }
            paint.setTextSize(Math.min(sp(10), dp(14))); paint.setColor(muted); paint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(time.format(new Date(first)), left, bottom + dp(21), paint);
            paint.setTextAlign(Paint.Align.RIGHT); canvas.drawText(time.format(new Date(last)), right, bottom + dp(21), paint);
            // Each filled segment closes independently, preserving visible data gaps.
            float zero = Math.max(top, Math.min(bottom, y(0)));
            Path line = new Path(), fill = new Path();
            Point previous = null;
            for (Point p : points) {
                float xx = x(p.millis), yy = y(p.value);
                if (previous == null || p.gapBefore || p.millis - previous.millis > 3500) {
                    if (previous != null) { fill.lineTo(x(previous.millis), zero); fill.close(); }
                    line.moveTo(xx, yy); fill.moveTo(xx, zero); fill.lineTo(xx, yy);
                } else { line.lineTo(xx, yy); fill.lineTo(xx, yy); }
                previous = p;
            }
            if (previous != null) { fill.lineTo(x(previous.millis), zero); fill.close(); }
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(0, top, 0, bottom, withAlpha(color, 42), withAlpha(color, 4), Shader.TileMode.CLAMP));
            canvas.drawPath(fill, paint); paint.setShader(null);
            paint.setColor(color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2.5f));
            paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND); canvas.drawPath(line, paint);
            paint.setStyle(Paint.Style.FILL);
            if (points.size() == 1) canvas.drawCircle(x(points.get(0).millis), y(points.get(0).value), dp(4), paint);
            else for (int i = 0; i < points.size(); i++) {
                Point p = points.get(i);
                boolean gapBefore = i == 0 || p.gapBefore || p.millis - points.get(i - 1).millis > 3500;
                boolean gapAfter = i == points.size() - 1 || points.get(i + 1).gapBefore || points.get(i + 1).millis - p.millis > 3500;
                if (gapBefore && gapAfter) canvas.drawCircle(x(p.millis), y(p.value), dp(2.5f), paint);
            }
            if (selected >= 0 && selected < points.size()) {
                Point p = points.get(selected); float xx = x(p.millis), yy = y(p.value);
                paint.setColor(withAlpha(color, 75)); paint.setStrokeWidth(dp(1)); canvas.drawLine(xx, top, xx, bottom, paint);
                paint.setColor(surface); canvas.drawCircle(xx, yy, dp(6), paint);
                paint.setColor(color); canvas.drawCircle(xx, yy, dp(3.5f), paint);
                paint.setTypeface(MEDIUM); paint.setTextSize(Math.min(sp(12), dp(16))); paint.setTextAlign(Paint.Align.LEFT);
                String tooltip = number(p.value) + (unit.isEmpty() ? "" : " " + unit) + "  ·  " + time.format(new Date(p.millis));
                float width = Math.min(right - left, paint.measureText(tooltip) + dp(20));
                float start = Math.max(left, Math.min(right - width, xx - width / 2));
                paint.setColor(raised); canvas.drawRoundRect(new RectF(start, dp(4), start + width, dp(30)), dp(10), dp(10), paint);
                paint.setColor(text); canvas.drawText(TextUtils.ellipsize(tooltip, new android.text.TextPaint(paint), width - dp(16), TextUtils.TruncateAt.END).toString(), start + dp(8), dp(21), paint);
            } else {
                paint.setTextAlign(Paint.Align.LEFT); paint.setTextSize(Math.min(sp(11), dp(15))); paint.setColor(muted);
                canvas.drawText("Touch to inspect" + (unit.isEmpty() ? "" : " · " + unit), left, dp(22), paint);
            }
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (points.isEmpty()) return false;
            if (event.getAction() == MotionEvent.ACTION_DOWN || event.getAction() == MotionEvent.ACTION_MOVE) {
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                float xx = Math.max(left, Math.min(right, event.getX()));
                long requested = first + (long) ((xx - left) / Math.max(1, right - left) * (last - first));
                int low = 0, high = points.size() - 1;
                while (low < high) { int mid = (low + high) / 2; if (points.get(mid).millis < requested) low = mid + 1; else high = mid; }
                selected = low;
                if (low > 0 && Math.abs(points.get(low - 1).millis - requested) <= Math.abs(points.get(low).millis - requested)) selected = low - 1;
                Point p = points.get(selected); setContentDescription(number(p.value) + " " + unit + " at " + time.format(new Date(p.millis)));
                invalidate(); return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP) { if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false); performClick(); return true; }
            if (event.getAction() == MotionEvent.ACTION_CANCEL) { if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false); return true; }
            return true;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
        private String axisNumber(double value) {
            double scale = Math.max(Math.abs(min), Math.abs(max)) >= 1000 ? 1000 : 1;
            double step = (max - min) / (3 * scale);
            int decimals = Math.max(0, Math.min(6, (int) Math.ceil(-Math.log10(step))));
            double scaled = value / scale;
            if (Math.abs(scaled) < Math.pow(10, -decimals) / 2) scaled = 0;
            return String.format(Locale.US, "%." + decimals + "f%s", scaled, scale == 1000 ? "k" : "");
        }
        private String number(double value) {
            double absolute = Math.abs(value);
            return absolute >= 10000 ? String.format(Locale.US, "%.0fk", value / 1000)
                    : absolute >= 1000 ? String.format(Locale.US, "%.1fk", value / 1000)
                    : absolute >= 100 ? String.format(Locale.US, "%.0f", value)
                    : String.format(Locale.US, "%.1f", value == 0 ? 0 : value);
        }
    }

    private final class IconView extends View {
        final String key;
        final int size, color;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        IconView(String key, int size, int color) {
            super(context); this.key = key == null ? "info" : key; this.size = size; this.color = color;
            setContentDescription(this.key.replace('_', ' ')); setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        @Override protected void onMeasure(int width, int height) { setMeasuredDimension(resolveSize(dp(size), width), resolveSize(dp(size), height)); }
        @Override protected void onDraw(Canvas canvas) {
            canvas.save(); float scale = Math.min(getWidth(), getHeight()) / 24f;
            canvas.translate((getWidth() - 24 * scale) / 2, (getHeight() - 24 * scale) / 2); canvas.scale(scale, scale);
            paint.setColor(color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.8f); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
            Path p = new Path();
            if (key.equals("home") || key.equals("dashboard")) {
                p.moveTo(3, 10); p.lineTo(12, 3); p.lineTo(21, 10); p.moveTo(5, 9); p.lineTo(5, 21); p.lineTo(10, 21); p.lineTo(10, 15); p.lineTo(14, 15); p.lineTo(14, 21); p.lineTo(19, 21); p.lineTo(19, 9); canvas.drawPath(p, paint);
            } else if (key.equals("lights")) {
                p.moveTo(13, 5); p.cubicTo(21, 4, 22, 20, 13, 19); p.close(); canvas.drawPath(p, paint);
                canvas.drawLine(3, 6, 9, 8, paint); canvas.drawLine(2, 12, 9, 12, paint); canvas.drawLine(3, 18, 9, 16, paint);
            } else if (key.equals("stats") || key.equals("chart") || key.equals("analytics")) {
                canvas.drawLine(4, 3, 4, 20, paint); canvas.drawLine(4, 20, 21, 20, paint);
                p.moveTo(7, 15); p.lineTo(11, 10); p.lineTo(15, 13); p.lineTo(21, 5); canvas.drawPath(p, paint);
            } else if (key.equals("battery")) {
                canvas.drawRoundRect(new RectF(3, 6, 20, 18), 2, 2, paint); canvas.drawLine(22, 10, 22, 14, paint);
                paint.setStyle(Paint.Style.FILL); canvas.drawRoundRect(new RectF(6, 9, 14, 15), 1, 1, paint);
            } else if (key.equals("settings") || key.equals("gear") || key.equals("tune")) {
                for (int i = 0; i < 3; i++) { float yy = 5 + i * 7, xx = i == 1 ? 15 : 8; canvas.drawLine(3, yy, 21, yy, paint); paint.setStyle(Paint.Style.FILL); paint.setColor(surface); canvas.drawCircle(xx, yy, 3, paint); paint.setStyle(Paint.Style.STROKE); paint.setColor(color); canvas.drawCircle(xx, yy, 2.5f, paint); }
            } else if (key.equals("rides") || key.equals("route") || key.equals("history")) {
                canvas.drawCircle(6, 5, 2.5f, paint); canvas.drawCircle(18, 19, 2.5f, paint);
                p.moveTo(6, 8); p.cubicTo(6, 14, 19, 7, 19, 12); p.cubicTo(19, 16, 9, 12, 9, 18); p.lineTo(15, 18); canvas.drawPath(p, paint);
            } else if (key.equals("bluetooth") || key.equals("scan")) {
                p.moveTo(12, 2); p.lineTo(18, 7); p.lineTo(6, 17); p.moveTo(6, 7); p.lineTo(18, 17); p.lineTo(12, 22); p.lineTo(12, 2); canvas.drawPath(p, paint);
            } else if (key.equals("lock") || key.equals("unlock")) {
                canvas.drawRoundRect(new RectF(5, 10, 19, 21), 2, 2, paint);
                RectF arc = new RectF(8, 3, 16, 15); canvas.drawArc(arc, key.equals("unlock") ? 210 : 180, key.equals("unlock") ? 150 : 180, false, paint);
                canvas.drawLine(12, 14, 12, 17, paint);
            } else if (key.equals("bolt") || key.equals("energy")) {
                p.moveTo(14, 2); p.lineTo(5, 14); p.lineTo(11, 14); p.lineTo(10, 22); p.lineTo(20, 10); p.lineTo(13, 10); p.close(); canvas.drawPath(p, paint);
            } else if (key.equals("power")) {
                canvas.drawArc(new RectF(4, 4, 20, 20), -45, 270, false, paint); canvas.drawLine(12, 2, 12, 11, paint);
            } else if (key.equals("download") || key.equals("export")) {
                canvas.drawLine(12, 3, 12, 15, paint); p.moveTo(7, 10); p.lineTo(12, 15); p.lineTo(17, 10); p.moveTo(4, 16); p.lineTo(4, 21); p.lineTo(20, 21); p.lineTo(20, 16); canvas.drawPath(p, paint);
            } else if (key.equals("check")) {
                p.moveTo(4, 12); p.lineTo(9, 17); p.lineTo(20, 6); canvas.drawPath(p, paint);
            } else if (key.equals("plus")) { canvas.drawLine(12, 4, 12, 20, paint); canvas.drawLine(4, 12, 20, 12, paint); }
            else if (key.equals("close")) { canvas.drawLine(5, 5, 19, 19, paint); canvas.drawLine(5, 19, 19, 5, paint); }
            else if (key.equals("chevron") || key.equals("back")) {
                p.moveTo(key.equals("back") ? 15 : 9, 5); p.lineTo(key.equals("back") ? 8 : 16, 12); p.lineTo(key.equals("back") ? 15 : 9, 19); canvas.drawPath(p, paint);
            } else if (key.equals("shield")) {
                p.moveTo(12, 2); p.lineTo(21, 6); p.lineTo(19, 15); p.quadTo(17, 20, 12, 22); p.quadTo(7, 20, 5, 15); p.lineTo(3, 6); p.close(); canvas.drawPath(p, paint);
            } else {
                canvas.drawCircle(12, 12, 9, paint); canvas.drawLine(12, 11, 12, 17, paint); paint.setStyle(Paint.Style.FILL); canvas.drawCircle(12, 7, 1, paint);
            }
            canvas.restore();
        }
    }
}
