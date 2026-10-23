package com.jjricks.volt;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;
import java.util.function.Consumer;

/**
 * "Which scooter is this?" inside a Volt sheet: a search box, then NAVEE's line-up (NaveeModels)
 * as one card of radio rows per series, scrolling in all the height the sheet has, with Not sure
 * and a write-in for a name that isn't listed. Below it, a status line and the sheet's one action.
 */
final class ModelPicker {
    private final Activity activity; private final Ui ui; private final Consumer<NaveeModels.Choice> changed;
    private NaveeModels.Choice choice;
    private EditText search; private LinearLayout list, statusRow; private ScrollView scroll;
    private TextView status, primary; private View spinner;

    ModelPicker(Activity activity, Ui ui, Consumer<NaveeModels.Choice> changed) { this.activity = activity; this.ui = ui; this.changed = changed; }

    NaveeModels.Choice choice() { return choice; }

    /** Fills a sheet (its title row already added) whose height is fixed; spinner may be null. */
    void build(LinearLayout sheet, View spinner, String primaryLabel, Runnable action) {
        search = new EditText(activity); ui.styleSearch(search);
        search.setHint("Search models"); search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS); search.setImeOptions(EditorInfo.IME_ACTION_DONE);
        search.setContentDescription("Search scooter models"); search.setTag("model-search");
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) { render(); scroll.scrollTo(0, 0); }
        });
        LinearLayout.LayoutParams sp = ui.searchLayout(); sp.bottomMargin = ui.dp(2); sheet.addView(search, sp);

        // The list takes every pixel the title, search and footer leave, and scrolls on its own.
        list = new LinearLayout(activity); list.setOrientation(LinearLayout.VERTICAL); list.setPadding(0, 0, 0, ui.dp(4));
        scroll = new ScrollView(activity); scroll.addView(list); scroll.setTag("model-list");
        scroll.setVerticalFadingEdgeEnabled(true); scroll.setFadingEdgeLength(ui.dp(18));
        sheet.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        statusRow = new LinearLayout(activity); statusRow.setGravity(Gravity.CENTER_VERTICAL); statusRow.setPadding(ui.dp(4), ui.dp(12), ui.dp(4), 0);
        this.spinner = spinner;
        if (spinner != null) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)); p.rightMargin = ui.dp(10); statusRow.addView(spinner, p); }
        status = ui.caption(""); status.setTag("model-status"); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        statusRow.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        sheet.addView(statusRow);
        primary = ui.button(primaryLabel, action); primary.setTag("model-next");
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2); bp.topMargin = ui.dp(10); sheet.addView(primary, bp);
        status("", false);
        render();
    }

    /** The footer line, with the spinner while working; an empty line takes no space. */
    void status(String text, boolean working) {
        status.setText(text); statusRow.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        if (spinner != null) spinner.setVisibility(working ? View.VISIBLE : View.GONE);
    }
    void primary(String label, boolean enabled) { primary.setText(label); primary.setContentDescription(label); primary.setEnabled(enabled); }

    private void render() {
        list.removeAllViews();
        String query = search.getText().toString(), typed = NaveeModels.typed(query);
        if (typed.isEmpty()) {
            LinearLayout card = ui.groupCard(); card.addView(row("Not sure", NaveeModels.Choice.unsure(), "model-unsure"));
            ((LinearLayout.LayoutParams) card.getLayoutParams()).topMargin = ui.dp(12); list.addView(card);
        } else if (NaveeModels.exact(typed) == null) {
            list.addView(ui.sectionLabel("Not listed"));
            LinearLayout card = ui.groupCard(); card.addView(row("Use “" + typed + "”", NaveeModels.Choice.typedName(typed), "model-typed")); list.addView(card);
        }
        List<String> matches = NaveeModels.search(query);
        LinearLayout card = null; NaveeModels.Series shown = null;
        for (String model : matches) {
            NaveeModels.Series series = NaveeModels.seriesOf(model);
            if (series != shown) {
                shown = series;
                list.addView(ui.sectionLabel(series.name + " · " + series.about));
                card = ui.groupCard(); list.addView(card);
            } else ui.divider(card);
            card.addView(row(model, NaveeModels.Choice.listed(model), "model-" + NaveeModels.fold(model)));
        }
    }

    /** A radio row like Volt's other choice sheets: the name, then the ring on the right. */
    private View row(String label, NaveeModels.Choice value, String tag) {
        boolean selected = choice != null && choice.source == value.source && choice.name.equals(value.name);
        LinearLayout row = new LinearLayout(activity); row.setGravity(Gravity.CENTER_VERTICAL); row.setTag(tag);
        row.setMinimumHeight(ui.dp(56)); row.setPadding(ui.dp(4), ui.dp(10), ui.dp(4), ui.dp(10));
        TextView name = ui.text(label, 16, false); name.setMaxLines(2); name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        Ui.RadioDot dot = ui.radioDot(); dot.set(selected);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)); dp.leftMargin = ui.dp(14); row.addView(dot, dp);
        row.setClickable(true); row.setFocusable(true); row.setSelected(selected); row.setContentDescription(label);
        row.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 26)), null, ui.shape(0xFFFFFFFF, 10, 0)));
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View v, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(v, info);
                info.setClassName(android.widget.RadioButton.class.getName()); info.setCheckable(true); info.setChecked(selected);
            }
        });
        row.setOnClickListener(v -> choose(value));
        return row;
    }

    private void choose(NaveeModels.Choice value) {
        choice = value;
        InputMethodManager keyboard = activity.getSystemService(InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.clearFocus();
        int y = scroll.getScrollY(); render(); scroll.scrollTo(0, y);
        changed.accept(value);
    }
}
