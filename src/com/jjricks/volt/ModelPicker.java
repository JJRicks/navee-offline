package com.jjricks.volt;

import android.app.Activity;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;
import java.util.function.Consumer;

/**
 * "Which scooter is this?": a search box over NAVEE's line-up (NaveeModels), a write-in row for a
 * name that isn't listed, and Not sure. changed receives every new answer.
 */
final class ModelPicker {
    private final Activity activity; private final Ui ui; private final Consumer<NaveeModels.Choice> changed;
    private NaveeModels.Choice choice;
    private EditText search; private LinearLayout list; private TextView chosen, unsure;

    ModelPicker(Activity activity, Ui ui, Consumer<NaveeModels.Choice> changed) { this.activity = activity; this.ui = ui; this.changed = changed; }

    NaveeModels.Choice choice() { return choice; }

    View view() {
        LinearLayout box = new LinearLayout(activity); box.setOrientation(LinearLayout.VERTICAL);
        TextView title = ui.text("Which scooter is this?", 17, true); box.addView(title);
        TextView how = ui.caption("Pick it from NAVEE's models, or search. Not listed? Type its name."); how.setPadding(0, ui.dp(4), 0, ui.dp(10)); box.addView(how);
        search = new EditText(activity); ui.styleSearch(search); search.setHint("Search, e.g. GT3 Pro"); search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS); search.setImeOptions(EditorInfo.IME_ACTION_DONE);
        search.setContentDescription("Search scooter models"); search.setTag("model-search");
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) { render(); }
        });
        box.addView(search, new LinearLayout.LayoutParams(-1, -2));
        list = new LinearLayout(activity); list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new Capped(activity, ui.dp(230)); scroll.addView(list); scroll.setTag("model-list");
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2); sp.topMargin = ui.dp(6); box.addView(scroll, sp);
        LinearLayout foot = new LinearLayout(activity); foot.setGravity(Gravity.CENTER_VERTICAL);
        chosen = ui.caption(""); chosen.setTag("model-chosen"); foot.addView(chosen, new LinearLayout.LayoutParams(0, -2, 1));
        unsure = ui.text("Not sure", 14, false); unsure.setTextColor(ui.accent); unsure.setTypeface(ui.medium()); unsure.setMinHeight(ui.dp(44));
        unsure.setGravity(Gravity.CENTER_VERTICAL); unsure.setPadding(ui.dp(12), 0, 0, 0); unsure.setFocusable(true); unsure.setClickable(true);
        unsure.setOnClickListener(v -> choose(NaveeModels.Choice.unsure())); unsure.setTag("model-unsure"); foot.addView(unsure);
        box.addView(foot);
        render();
        return box;
    }

    /** As tall as its rows, up to max, then it scrolls: a short result list leaves no empty gap. */
    private static final class Capped extends ScrollView {
        private final int max;
        Capped(android.content.Context context, int max) { super(context); this.max = max; }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int limit = MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED ? max : Math.min(max, MeasureSpec.getSize(heightSpec));
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
        }
    }

    private void render() {
        list.removeAllViews();
        String query = search.getText().toString();
        String typed = NaveeModels.typed(query);
        List<String> matches = NaveeModels.search(query);
        if (!typed.isEmpty() && NaveeModels.exact(typed) == null) list.addView(row("Use “" + typed + "”", "Not in the list", NaveeModels.Choice.typedName(typed), "model-typed"));
        NaveeModels.Series shown = null;
        for (String model : matches) {
            NaveeModels.Series series = NaveeModels.seriesOf(model);
            if (series != shown) {
                shown = series;
                TextView header = ui.caption(series.name + " · " + series.about); header.setPadding(ui.dp(4), ui.dp(12), 0, ui.dp(2));
                if (android.os.Build.VERSION.SDK_INT >= 28) header.setAccessibilityHeading(true);
                list.addView(header);
            }
            list.addView(row(model, null, NaveeModels.Choice.listed(model), "model-" + NaveeModels.fold(model)));
        }
        if (matches.isEmpty() && typed.isEmpty()) list.addView(ui.caption("No models match."));
        String name = choice == null ? "" : choice.known() ? choice.name : "Not sure";
        chosen.setText(name.isEmpty() ? "" : "Chosen: " + name);
        unsure.setSelected(choice != null && !choice.known());
    }

    private View row(String label, String sub, NaveeModels.Choice value, String tag) {
        boolean selected = choice != null && choice.source == value.source && choice.name.equals(value.name);
        LinearLayout row = new LinearLayout(activity); row.setGravity(Gravity.CENTER_VERTICAL); row.setMinimumHeight(ui.dp(46));
        row.setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(4)); row.setTag(tag);
        row.setBackground(ui.shape(selected ? Ui.withAlpha(ui.accent, 40) : 0x00000000, 12, 0));
        LinearLayout words = new LinearLayout(activity); words.setOrientation(LinearLayout.VERTICAL);
        TextView name = ui.text(label, 15, selected); words.addView(name);
        if (sub != null) words.addView(ui.caption(sub));
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        if (selected) { TextView tick = ui.text("✓", 16, true); tick.setTextColor(ui.accent); row.addView(tick); }
        row.setClickable(true); row.setFocusable(true);
        row.setContentDescription(label + (selected ? ", chosen" : ""));
        row.setOnClickListener(v -> choose(value));
        return row;
    }

    private void choose(NaveeModels.Choice value) {
        choice = value;
        android.view.inputmethod.InputMethodManager keyboard = activity.getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
        render();
        changed.accept(value);
    }
}
