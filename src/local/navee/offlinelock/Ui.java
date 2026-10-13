package local.navee.offlinelock;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
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
    /** Keep compact status timestamps on one line, shrinking only when their width requires it. */
    public void fitStatusLine(TextView view) {
        int maximum = Math.round(view.getTextSize());
        // Keep the original line's height as auto-size settles, including across Edit rebuilds.
        Paint.FontMetrics metrics = view.getPaint().getFontMetrics();
        view.setMinHeight((int)Math.ceil(metrics.descent - metrics.ascent));
        view.setGravity(view.getGravity() | Gravity.CENTER_VERTICAL);
        view.setMaxLines(1);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        view.setAutoSizeTextTypeUniformWithConfiguration(
                Math.min(maximum, Math.round(sp(7) / textScale)), maximum, 1, TypedValue.COMPLEX_UNIT_PX);
    }
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
    /** Search spacing is shared without changing each field's focus or input behavior. */
    public void styleSearch(EditText search) {
        search.setTextColor(text); search.setHintTextColor(muted);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14 * textScale);
        search.setPadding(dp(16), dp(14), dp(16), dp(14));
        search.setMinimumHeight(dp(52));
        search.setBackground(shape(raised, 16, outline));
    }
    public LinearLayout.LayoutParams searchLayout() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(4); params.bottomMargin = dp(12); return params;
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
    /** A compound symbol preserves row child indices and the label's spoken text. */
    public void decorate(TextView label, String iconKey) {
        if (iconKey == null || iconKey.isEmpty()) return;
        Drawable symbol = new LabelIcon(label, iconKey);
        int size = dp(20); symbol.setBounds(0, 0, size, size);
        Drawable[] old = label.getCompoundDrawablesRelative();
        label.setCompoundDrawablesRelative(symbol, old[1], old[2], old[3]);
        label.setCompoundDrawablePadding(dp(10));
    }
    /** Display-only mode cues; callers supply the formatted text, base color and spoken context. */
    public void styleDriveMode(TextView value, String formattedMode, boolean live) {
        value.setCompoundDrawablesRelative(null, null, null, null);
        value.setCompoundDrawablePadding(0);
        if ("SPORT".equals(formattedMode)) value.setTextColor(live ? blue : muted);
        else if ("ECO".equals(formattedMode)) value.setTextColor(live ? accent : muted);
        else if ("WALK".equals(formattedMode)) {
            value.setTextColor(live ? accent : muted);
            CharSequence spoken = value.getContentDescription();
            if (spoken == null || spoken.length() == 0) value.setContentDescription("WALK");
            value.setText("");
            Drawable walker = new LabelIcon(value, "walk");
            float fontScale = context.getResources().getConfiguration().fontScale;
            int size = dp(Math.max(24, Math.min(36, 24 * textScale * fontScale)));
            walker.setBounds(0, 0, size, size);
            value.setCompoundDrawablesRelative(walker, null, null, null);
        }
    }
    public String featureIcon(String key) {
        if (key == null) return "";
        if (key.contains("speed") || key.equals("cruise_control") || key.equals("driving_mode")) return "speed";
        if (key.contains("charge") || key.contains("battery") || key.equals("low_power") || key.equals("long_range_mode")) return "battery";
        if (key.equals("energy_recovery")) return "recovery";
        if (key.equals("headlight") || key.equals("auto_sensor") || key.startsWith("line_")) return "lights";
        if (key.startsWith("star_")) return "star";
        if (key.contains("lock") || key.equals("pin") || key.equals("proximity_key")) return "lock";
        if (key.contains("sound") || key.equals("prompt_tone")) return "sound";
        if (key.equals("traction_control")) return "shield";
        if (key.contains("assist") || key.contains("hill")) return "hill";
        if (key.contains("unit") || key.contains("distance") || key.contains("odometer")) return "ruler";
        if (key.contains("temperature")) return "temperature";
        if (key.contains("power") || key.contains("voltage") || key.contains("current")) return "energy";
        if (key.contains("range")) return "route";
        return "tune";
    }
    public String labelIcon(String label) {
        if (label == null) return "";
        String value = label.toLowerCase(Locale.ROOT);
        if (value.contains("history") || value.contains("storage")) return "history";
        if (value.equals("ride") || value.contains("my scooter") || value.contains("scooter setting") || value.contains("scooter name")) return "scooter";
        if (value.contains("light")) return "lights";
        if (value.contains("battery") || value.contains("charging")) return "battery";
        if (value.contains("recovery")) return "recovery";
        if (value.contains("proximity") || value.contains("pin") || value.contains("security")) return "lock";
        if (value.contains("sound")) return "sound";
        if (value.contains("unit") || value.contains("distance & speed")) return "ruler";
        if (value.contains("appearance")) return "appearance";
        if (value.contains("text size")) return "text";
        if (value.contains("stats") || value.contains("readings")) return "stats";
        if (value.contains("telemetry refresh") || value.contains("clock")) return "clock";
        if (value.contains("connect") || value.contains("nearby")) return "bluetooth";
        if (value.contains("screen awake")) return "screen";
        if (value.contains("phone sensor")) return "sensors";
        if (value.contains("map")) return "route";
        if (value.contains("app preference")) return "settings";
        if (value.contains("experimental")) return "lab";
        if (value.contains("about") || value.contains("permission")) return "info";
        return "";
    }
    private final class LabelIcon extends Drawable {
        private final TextView owner; private final String key;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int alpha = 255;
        LabelIcon(TextView owner, String key) { this.owner = owner; this.key = key; }
        @Override public void draw(Canvas canvas) {
            canvas.save(); canvas.translate(getBounds().left, getBounds().top);
            canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
            int color = owner.getCurrentTextColor();
            drawSymbol(canvas, key, withAlpha(color, Color.alpha(color) * alpha / 255), paint);
            canvas.restore();
        }
        @Override public void setAlpha(int value) { alpha = value; invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter value) { paint.setColorFilter(value); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public boolean isStateful() { return true; }
        @Override protected boolean onStateChange(int[] state) { invalidateSelf(); return true; }
    }

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
        TextView heading = text(label, 15, true); decorate(heading, labelIcon(label)); words.addView(heading);
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
        LinearLayout row = new LinearLayout(context) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int available = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
                if (getChildCount() > 1 && available > 0)
                    ((TextView)getChildAt(1)).setMaxWidth(Math.min(dp(148), (int)(available * .42f)));
                super.onMeasure(widthSpec, heightSpec);
            }
        }; row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL); row.setMinimumHeight(dp(62));
        row.setPadding(dp(2), dp(13), dp(2), dp(13));
        TextView title = text(label, 15, false); decorate(title, labelIcon(label));
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
        private boolean charging, live = true;
        public void setLive(boolean value) { live = value; describe(); invalidate(); }
        public BatteryRing() {
            super(context); setMinimumHeight(dp(140)); setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            setLayoutParams(new LinearLayout.LayoutParams(dp(160), dp(176)));
        }
        public BatteryRing(Context ignored) { this(); }
        public void set(double percent, String subtitle, boolean charging) {
            this.percent = !Double.isNaN(percent) && !Double.isInfinite(percent) && percent >= 0 && percent <= 100 ? percent : Double.NaN;
            this.subtitle = subtitle == null ? "" : subtitle; this.charging = charging;
            describe(); invalidate();
        }
        private void describe() {
            setContentDescription("Battery " + (Double.isNaN(percent) ? "not reported" : Math.round(percent) + " percent")
                    + (charging ? ", charging" : "") + (live ? "" : ", last seen reading")
                    + (subtitle.isEmpty() ? "" : ", " + subtitle));
        }
        public void set(Double percent, String subtitle, boolean charging) { set(percent == null ? Double.NaN : percent.doubleValue(), subtitle, charging); }
        @Override protected void onMeasure(int width, int height) {
            setMeasuredDimension(resolveSize(dp(160), width), resolveSize(dp(176), height));
        }
        @Override protected void onDraw(Canvas canvas) {
            String label = charging ? "Charging" + (subtitle.isEmpty() ? "" : " · " + subtitle) : subtitle;
            float drawableHeight = Math.max(1, getHeight() - (label.isEmpty() ? 0 : dp(20)));
            float radius = Math.min(getWidth(), drawableHeight) / 2f - dp(13);
            if (radius <= 0) return;
            // The open lower quarter makes the painted arc shorter below the circle
            // center. Center its visible bounds, not the invisible full-circle square.
            float cx = getWidth() / 2f, cy = drawableHeight / 2f + radius * .1464466f;
            RectF circle = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
            float stroke = Math.min(dp(12.5f), radius * .25f);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(stroke); paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(outline); canvas.drawArc(circle, 135, 270, false, paint);
            int color = !live ? muted : !Double.isNaN(percent) && percent < 15 ? danger : !Double.isNaN(percent) && percent < 30 ? orange : accent;
            if (!Double.isNaN(percent)) { paint.setColor(color); canvas.drawArc(circle, 135, (float) (270 * percent / 100), false, paint); }
            paint.setStyle(Paint.Style.FILL); paint.setTextAlign(Paint.Align.CENTER);
            String amount = Double.isNaN(percent) ? "—" : Long.toString(Math.round(percent));
            String unit = Double.isNaN(percent) ? "" : "%";
            float ringScale = Math.max(1f, radius / dp(58));
            float amountSize = Math.min(sp(34) * ringScale, radius * .76f);
            float unitSize = Math.min(sp(14) * ringScale, radius * .30f);
            float gap = unit.isEmpty() ? 0 : dp(5);
            Rect amountBounds = new Rect(), unitBounds = new Rect();
            paint.setTypeface(BOLD); paint.setTextSize(amountSize);
            paint.getTextBounds(amount, 0, amount.length(), amountBounds);
            float amountWidth = paint.measureText(amount);
            paint.setTypeface(MEDIUM); paint.setTextSize(unitSize);
            paint.getTextBounds(unit, 0, unit.length(), unitBounds);
            float blockWidth = Math.max(amountWidth, paint.measureText(unit));
            float blockHeight = amountBounds.height() + unitBounds.height() + gap;
            // Keep the whole text block inside a padded square inscribed in the ring. This
            // also fits three digits and larger accessibility text in the compact Home gauge.
            float safeSide = Math.max(1, (radius - stroke / 2 - dp(6)) * 1.4142f);
            float fit = Math.min(1, Math.min(safeSide / Math.max(1, blockWidth), safeSide / Math.max(1, blockHeight)));
            amountSize *= fit; unitSize *= fit; gap *= fit;
            paint.setTypeface(BOLD); paint.setTextSize(amountSize);
            paint.getTextBounds(amount, 0, amount.length(), amountBounds);
            paint.setTypeface(MEDIUM); paint.setTextSize(unitSize);
            paint.getTextBounds(unit, 0, unit.length(), unitBounds);
            float top = cy - (amountBounds.height() + unitBounds.height() + gap) / 2f;
            paint.setTypeface(BOLD); paint.setTextSize(amountSize); paint.setColor(live ? text : muted);
            canvas.drawText(amount, cx, top - amountBounds.top, paint);
            paint.setTypeface(MEDIUM); paint.setTextSize(unitSize); paint.setColor(muted);
            if (!unit.isEmpty()) canvas.drawText(unit, cx, top + amountBounds.height() + gap - unitBounds.top, paint);
            paint.setTextSize(Math.min(sp(11), dp(14))); paint.setColor(charging && live ? accent : muted);
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
        private long first = 0, last = 1, gapThresholdMs = 3500;
        private boolean historyMode;
        private float left, top, right, bottom;
        private final boolean use24Hour = DisplayTime.is24Hour(context,
                context.getSharedPreferences("app_settings", Context.MODE_PRIVATE));
        private final SimpleDateFormat time = new SimpleDateFormat(use24Hour ? "HH:mm:ss" : "h:mm:ss a", Locale.getDefault());
        private final SimpleDateFormat historyTime = new SimpleDateFormat(use24Hour ? "HH:mm" : "h:mm a", Locale.getDefault());
        private final SimpleDateFormat historyDate = new SimpleDateFormat("MMM d", Locale.getDefault());
        private final SimpleDateFormat historyDetail = new SimpleDateFormat(use24Hour ? "MMM d, yyyy HH:mm:ss" : "MMM d, yyyy h:mm:ss a", Locale.getDefault());
        public ChartView() {
            super(context); setMinimumHeight(dp(210)); setFocusable(true);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            setLayoutParams(new LinearLayout.LayoutParams(-1, dp(220)));
        }
        public ChartView(Context ignored) { this(); }
        public void setGapThresholdMs(long value) { gapThresholdMs = Math.max(1, value); invalidate(); }
        /** Historical aggregation intervals rely on explicit gaps, rather than the live 3.5 s rule. */
        public void setHistoryMode(boolean value) { historyMode = value; gapThresholdMs = value ? Long.MAX_VALUE : 3500; invalidate(); }
        private String axisTime(long millis) {
            return (historyMode ? last - first >= 86400000L ? historyDate : historyTime : time).format(new Date(millis));
        }
        private String detailTime(long millis) { return (historyMode ? historyDetail : time).format(new Date(millis)); }
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
            String firstLabel = axisTime(first), lastLabel = axisTime(last);
            if (paint.measureText(firstLabel) + paint.measureText(lastLabel) + dp(8) <= right - left)
                canvas.drawText(firstLabel, left, bottom + dp(21), paint);
            paint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(TextUtils.ellipsize(lastLabel, new android.text.TextPaint(paint),
                    Math.max(1, right - left), TextUtils.TruncateAt.END).toString(), right, bottom + dp(21), paint);
            // Each filled segment closes independently, preserving visible data gaps.
            float zero = Math.max(top, Math.min(bottom, y(0)));
            Path line = new Path(), fill = new Path();
            Point previous = null;
            for (Point p : points) {
                float xx = x(p.millis), yy = y(p.value);
                if (previous == null || p.gapBefore || p.millis - previous.millis > gapThresholdMs) {
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
                boolean gapBefore = i == 0 || p.gapBefore || p.millis - points.get(i - 1).millis > gapThresholdMs;
                boolean gapAfter = i == points.size() - 1 || points.get(i + 1).gapBefore || points.get(i + 1).millis - p.millis > gapThresholdMs;
                if (gapBefore && gapAfter) canvas.drawCircle(x(p.millis), y(p.value), dp(2.5f), paint);
            }
            if (selected >= 0 && selected < points.size()) {
                Point p = points.get(selected); float xx = x(p.millis), yy = y(p.value);
                paint.setColor(withAlpha(color, 75)); paint.setStrokeWidth(dp(1)); canvas.drawLine(xx, top, xx, bottom, paint);
                paint.setColor(surface); canvas.drawCircle(xx, yy, dp(6), paint);
                paint.setColor(color); canvas.drawCircle(xx, yy, dp(3.5f), paint);
                paint.setTypeface(MEDIUM); paint.setTextSize(Math.min(sp(12), dp(16))); paint.setTextAlign(Paint.Align.LEFT);
                String tooltip = number(p.value) + (unit.isEmpty() ? "" : " " + unit) + "  ·  " + detailTime(p.millis);
                float width = Math.min(right - left, paint.measureText(tooltip) + dp(20));
                float start = Math.max(left, Math.min(right - width, xx - width / 2));
                paint.setColor(raised); canvas.drawRoundRect(new RectF(start, dp(4), start + width, dp(30)), dp(10), dp(10), paint);
                paint.setColor(text); canvas.drawText(TextUtils.ellipsize(tooltip, new android.text.TextPaint(paint), width - dp(16), TextUtils.TruncateAt.END).toString(), start + dp(8), dp(21), paint);
            } else {
                paint.setTextAlign(Paint.Align.LEFT); paint.setTextSize(Math.min(sp(11), dp(15))); paint.setColor(muted);
                canvas.drawText(unit, left, dp(22), paint);
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
                Point p = points.get(selected); setContentDescription(number(p.value) + " " + unit + " at " + detailTime(p.millis));
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
            drawSymbol(canvas, key, color, paint);
            canvas.restore();
        }
    }
    private void drawSymbol(Canvas canvas, String key, int color, Paint paint) {
            paint.setColor(color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.8f); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
            Path p = new Path();
            if (key.equals("home") || key.equals("dashboard")) {
                p.moveTo(3, 10); p.lineTo(12, 3); p.lineTo(21, 10); p.moveTo(5, 9); p.lineTo(5, 21); p.lineTo(10, 21); p.lineTo(10, 15); p.lineTo(14, 15); p.lineTo(14, 21); p.lineTo(19, 21); p.lineTo(19, 9); canvas.drawPath(p, paint);
            } else if (key.equals("scooter")) {
                canvas.drawCircle(5, 19, 2.6f, paint); canvas.drawCircle(20, 19, 2.6f, paint);
                p.moveTo(5, 17); p.lineTo(8, 17); p.lineTo(11, 20); p.lineTo(17, 20);
                p.moveTo(20, 17); p.lineTo(16, 4); p.lineTo(12, 4); canvas.drawPath(p, paint);
            } else if (key.equals("walk")) {
                paint.setStyle(Paint.Style.FILL); canvas.drawCircle(13.5f, 3.5f, 2, paint);
                paint.setStyle(Paint.Style.STROKE);
                p.moveTo(6, 12); p.lineTo(8, 8); p.lineTo(12, 7);
                p.lineTo(15, 11); p.lineTo(19, 12);
                p.moveTo(12, 7); p.lineTo(10.5f, 14); p.lineTo(7, 21);
                p.moveTo(10.5f, 14); p.lineTo(15, 16); p.lineTo(16.5f, 21);
                canvas.drawPath(p, paint);
            } else if (key.equals("speed")) {
                canvas.drawArc(new RectF(3, 4, 21, 22), 160, 220, false, paint);
                p.moveTo(5, 18); p.lineTo(19, 18); p.moveTo(12, 15); p.lineTo(17, 8); canvas.drawPath(p, paint);
                paint.setStyle(Paint.Style.FILL); canvas.drawCircle(12, 15, 1.7f, paint);
            } else if (key.equals("appearance")) {
                canvas.drawCircle(12, 12, 8, paint); paint.setStyle(Paint.Style.FILL);
                canvas.drawArc(new RectF(4, 4, 20, 20), 90, 180, true, paint);
            } else if (key.equals("ruler")) {
                canvas.drawRoundRect(new RectF(2, 7, 22, 17), 2, 2, paint);
                for (int i = 6; i < 22; i += 4) canvas.drawLine(i, 7, i, i % 8 == 2 ? 12 : 10, paint);
            } else if (key.equals("text")) {
                p.moveTo(3, 19); p.lineTo(8, 5); p.lineTo(13, 19); p.moveTo(5, 14); p.lineTo(11, 14);
                p.moveTo(15, 12); p.lineTo(22, 12); p.moveTo(18.5f, 12); p.lineTo(18.5f, 19); canvas.drawPath(p, paint);
            } else if (key.equals("clock") || key.equals("history")) {
                canvas.drawArc(new RectF(4, 4, 21, 21), key.equals("history") ? -75 : 0, key.equals("history") ? 305 : 360, false, paint);
                p.moveTo(12.5f, 8); p.lineTo(12.5f, 13); p.lineTo(16, 15);
                if (key.equals("history")) { p.moveTo(2, 4); p.lineTo(2, 10); p.lineTo(8, 10); }
                canvas.drawPath(p, paint);
            } else if (key.equals("screen")) {
                canvas.drawRoundRect(new RectF(6, 2, 18, 22), 2, 2, paint); canvas.drawLine(10, 19, 14, 19, paint);
            } else if (key.equals("sensors")) {
                canvas.drawCircle(12, 12, 2, paint); canvas.drawArc(new RectF(6, 6, 18, 18), -55, 110, false, paint);
                canvas.drawArc(new RectF(6, 6, 18, 18), 125, 110, false, paint);
                canvas.drawArc(new RectF(2, 2, 22, 22), -50, 100, false, paint); canvas.drawArc(new RectF(2, 2, 22, 22), 130, 100, false, paint);
            } else if (key.equals("lab")) {
                p.moveTo(9, 3); p.lineTo(15, 3); p.moveTo(10, 3); p.lineTo(10, 10); p.lineTo(4, 20);
                p.lineTo(20, 20); p.lineTo(14, 10); p.lineTo(14, 3); p.moveTo(7, 15); p.lineTo(17, 15); canvas.drawPath(p, paint);
            } else if (key.equals("sound")) {
                p.moveTo(3, 9); p.lineTo(7, 9); p.lineTo(12, 5); p.lineTo(12, 19); p.lineTo(7, 15); p.lineTo(3, 15); p.close(); canvas.drawPath(p, paint);
                canvas.drawArc(new RectF(8, 6, 20, 18), -60, 120, false, paint); canvas.drawArc(new RectF(7, 2, 25, 22), -45, 90, false, paint);
            } else if (key.equals("recovery")) {
                canvas.drawArc(new RectF(4, 4, 20, 20), 20, 285, false, paint); p.moveTo(20, 3); p.lineTo(20, 8); p.lineTo(15, 8);
                p.moveTo(13, 8); p.lineTo(9, 13); p.lineTo(13, 13); p.lineTo(11, 17); canvas.drawPath(p, paint);
            } else if (key.equals("hill")) {
                p.moveTo(2, 20); p.lineTo(10, 6); p.lineTo(14, 13); p.lineTo(17, 8); p.lineTo(22, 20); p.close(); canvas.drawPath(p, paint);
            } else if (key.equals("temperature")) {
                p.moveTo(9, 15); p.lineTo(9, 5); p.cubicTo(9, 1, 15, 1, 15, 5); p.lineTo(15, 15); canvas.drawPath(p, paint);
                canvas.drawArc(new RectF(7, 12, 17, 22), -45, 270, false, paint); canvas.drawLine(12, 8, 12, 18, paint);
            } else if (key.equals("star")) {
                for (int i = 0; i < 10; i++) { double angle = Math.PI * i / 5 - Math.PI / 2; float radius = i % 2 == 0 ? 9 : 4;
                    float x = 12 + radius * (float)Math.cos(angle), y = 12 + radius * (float)Math.sin(angle); if (i == 0) p.moveTo(x,y); else p.lineTo(x,y); }
                p.close(); canvas.drawPath(p, paint);
            } else if (key.equals("lights")) {
                p.moveTo(13, 5); p.cubicTo(21, 4, 22, 20, 13, 19); p.close(); canvas.drawPath(p, paint);
                canvas.drawLine(3, 6, 9, 8, paint); canvas.drawLine(2, 12, 9, 12, paint); canvas.drawLine(3, 18, 9, 16, paint);
            } else if (key.equals("stats") || key.equals("chart") || key.equals("analytics")) {
                canvas.drawLine(4, 3, 4, 20, paint); canvas.drawLine(4, 20, 21, 20, paint);
                p.moveTo(7, 15); p.lineTo(11, 10); p.lineTo(15, 13); p.lineTo(21, 5); canvas.drawPath(p, paint);
            } else if (key.equals("battery")) {
                canvas.drawRoundRect(new RectF(3, 6, 20, 18), 2, 2, paint); canvas.drawLine(22, 10, 22, 14, paint);
                paint.setStyle(Paint.Style.FILL); canvas.drawRoundRect(new RectF(6, 9, 14, 15), 1, 1, paint);
            } else if (key.equals("heart")) {
                p.moveTo(12, 21); p.cubicTo(10, 19, 2, 13, 2, 8);
                p.cubicTo(2, 2, 9, 1, 12, 7); p.cubicTo(15, 1, 22, 2, 22, 8);
                p.cubicTo(22, 13, 14, 19, 12, 21); p.close(); canvas.drawPath(p, paint);
            } else if (key.equals("gear")) {
                for (int i = 0; i < 32; i++) {
                    double angle = i * Math.PI / 16 - Math.PI / 2;
                    float radius = i % 4 == 0 || i % 4 == 3 ? 10 : 7.8f;
                    float x = 12 + radius * (float) Math.cos(angle);
                    float y = 12 + radius * (float) Math.sin(angle);
                    if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
                }
                p.close(); canvas.drawPath(p, paint); canvas.drawCircle(12, 12, 3.3f, paint);
            } else if (key.equals("settings") || key.equals("tune")) {
                for (int i = 0; i < 3; i++) { float yy = 5 + i * 7, xx = i == 1 ? 15 : 8; canvas.drawLine(3, yy, 21, yy, paint); paint.setStyle(Paint.Style.FILL); paint.setColor(surface); canvas.drawCircle(xx, yy, 3, paint); paint.setStyle(Paint.Style.STROKE); paint.setColor(color); canvas.drawCircle(xx, yy, 2.5f, paint); }
            } else if (key.equals("rides") || key.equals("route")) {
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
            } else if (key.equals("pencil") || key.equals("edit")) {
                p.moveTo(4, 16); p.lineTo(15.5f, 4.5f); p.quadTo(17, 3, 18.5f, 4.5f);
                p.lineTo(20, 6); p.quadTo(21.5f, 7.5f, 20, 9); p.lineTo(8, 20);
                p.lineTo(3, 21); p.close(); p.moveTo(14, 6); p.lineTo(19, 11);
                p.moveTo(4, 16); p.lineTo(8, 20); canvas.drawPath(p, paint);
            } else if (key.equals("grip") || key.equals("drag")) {
                paint.setStyle(Paint.Style.FILL);
                for (int x = 8; x <= 16; x += 8)
                    for (int y = 5; y <= 19; y += 7) canvas.drawCircle(x, y, 1.5f, paint);
            } else if (key.equals("resize") || key.startsWith("resize_")) {
                canvas.save();
                if (key.equals("resize_nw")) canvas.rotate(180, 12, 12);
                else if (key.equals("resize_ne")) canvas.rotate(270, 12, 12);
                else if (key.equals("resize_sw")) canvas.rotate(90, 12, 12);
                p.moveTo(8, 20); p.lineTo(20, 8); p.moveTo(14, 20); p.lineTo(20, 14);
                canvas.drawPath(p, paint); canvas.restore();
            } else if (key.equals("trash")) {
                canvas.drawLine(4, 6, 20, 6, paint);
                p.moveTo(9, 6); p.lineTo(9, 3); p.lineTo(15, 3); p.lineTo(15, 6);
                p.moveTo(6, 6); p.lineTo(7, 21); p.lineTo(17, 21); p.lineTo(18, 6);
                p.moveTo(10, 10); p.lineTo(10.5f, 17);
                p.moveTo(14, 10); p.lineTo(13.5f, 17); canvas.drawPath(p, paint);
            } else if (key.equals("copy")) {
                canvas.drawRoundRect(new RectF(8, 8, 21, 21), 2, 2, paint);
                p.moveTo(16, 5); p.lineTo(16, 3); p.lineTo(3, 3); p.lineTo(3, 16); p.lineTo(5, 16);
                canvas.drawPath(p, paint);
            } else if (key.equals("more")) {
                paint.setStyle(Paint.Style.FILL);
                for (int x = 5; x <= 19; x += 7) canvas.drawCircle(x, 12, 1.8f, paint);
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
    }

}
