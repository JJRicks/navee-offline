package com.jjricks.volt;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristics;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

/** Keeps narrow-card captions readable: whole words, a short label, then a named icon. */
final class DashboardCaption extends TextView {
    private String normalLabel, shortLabel;
    private final Drawable fallbackIcon;
    private boolean oneLine;

    DashboardCaption(Context context, Ui ui, String fullName, String normalLabel,
            String shortLabel, Drawable icon) {
        super(context);
        fallbackIcon = icon;
        if (fallbackIcon != null && fallbackIcon.getBounds().isEmpty()) {
            int size = ui.dp(16);
            fallbackIcon.setBounds(0, 0, size, size);
        }
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12 * ui.textScale);
        setTextColor(ui.muted);
        setTypeface(ui.regular());
        setIncludeFontPadding(false);
        setLineSpacing(ui.dp(3), 1);
        setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
        setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        setMaxLines(2);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        setLabels(fullName, normalLabel, shortLabel);
        setText(this.normalLabel);
    }

    /**
     * One line, never wrapped: the normal label when it fits on one line, otherwise the short
     * label, ellipsized only if even that is too wide. The full name stays the description.
     */
    void fitOneLine() {
        oneLine = true;
        setMaxLines(1);
        setEllipsize(android.text.TextUtils.TruncateAt.END);
        requestLayout();
    }

    /** Updates changing captions without losing their measured fallback choices. */
    void setLabels(String fullName, String normalLabel, String shortLabel) {
        String normal = normalLabel == null ? "" : normalLabel;
        String compact = shortLabel == null ? normal : shortLabel;
        String description = fullName == null ? normal : fullName;
        boolean changed = !normal.equals(this.normalLabel) || !compact.equals(this.shortLabel);
        this.normalLabel = normal;
        this.shortLabel = compact;
        setContentDescription(description);
        setTooltipText(description);
        if (changed) requestLayout();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = Math.max(0, MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight());
        boolean unbounded = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED;
        String shown = unbounded || fits(normalLabel, width) ? normalLabel
                : fits(shortLabel, width) || oneLine ? shortLabel : null;
        Drawable icon = shown == null ? fallbackIcon : null;
        // No fitting text with a missing icon is still accessible, without rendering
        // a label broken into individual letters. Callers normally supply an icon.
        String text = shown == null ? "" : shown;
        if (!text.contentEquals(getText())) setText(text);
        if (getCompoundDrawablesRelative()[0] != icon)
            setCompoundDrawablesRelative(icon, null, null, null);
        super.onMeasure(widthSpec, heightSpec);
    }

    private boolean fits(String text, int width) {
        if (text.isEmpty()) return true;
        if (width <= 0) return false;
        StaticLayout layout = StaticLayout.Builder.obtain(text, 0, text.length(), getPaint(), width)
                .setIncludePad(getIncludeFontPadding())
                .setLineSpacing(getLineSpacingExtra(), getLineSpacingMultiplier())
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                .build();
        if (layout.getLineCount() > (oneLine ? 1 : 2)) return false;
        for (int line = 0; line < layout.getLineCount(); line++) {
            if (layout.getLineMax(line) > width) return false;
            int end = layout.getLineEnd(line);
            if (end >= text.length()) continue;
            char before = text.charAt(end - 1), after = text.charAt(end);
            if (!Character.isWhitespace(before) && !Character.isWhitespace(after)
                    && before != '-' && before != '/' && before != '\u2010' && before != '\u2013')
                return false;
        }
        return true;
    }
}
