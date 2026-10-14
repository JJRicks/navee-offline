package local.navee.offlinelock;

import android.app.Dialog;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.function.IntConsumer;

/** Compact chart-window control: all choices appear together, without a scrolling tab strip. */
final class StatsWindowPicker extends LinearLayout {
    private final Ui ui;
    private final String[] labels;
    private final IntConsumer changed;
    private final TextView button;
    final ArrayList<TextView> choices = new ArrayList<>();
    private Dialog dialog;
    private int selected;

    StatsWindowPicker(Ui ui, String[] labels, int selected, IntConsumer changed) {
        super(ui.context); this.ui = ui; this.labels = labels.clone(); this.changed = changed;
        setOrientation(VERTICAL);
        button = ui.secondaryButton("", this::open);
        button.setMinHeight(ui.dp(48));
        addView(button, new LayoutParams(-1, -2)); select(selected, false);
    }
    void select(int index, boolean ignoredAnimation) {
        selected = Math.max(0, Math.min(labels.length - 1, index));
        button.setText("Window: " + labels[selected] + " ▾");
        button.setContentDescription("Chart time window: " + labels[selected] + ". Choose time window");
        if (Build.VERSION.SDK_INT >= 30) button.setStateDescription(labels[selected]);
        for (int i = 0; i < choices.size(); i++) style(choices.get(i), i == selected);
    }
    private void style(TextView choice, boolean active) {
        choice.setSelected(active); choice.setTextColor(active ? ui.onAccent : ui.text);
        choice.setBackground(ui.shape(active ? ui.accent : ui.raised, 14, active ? 0 : ui.outline));
        if (Build.VERSION.SDK_INT >= 30) choice.setStateDescription(active ? "Selected" : "");
    }
    private void open() {
        close(); choices.clear();
        final Dialog opened = new Dialog(ui.context); opened.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog = opened;
        LinearLayout content = new LinearLayout(ui.context); content.setOrientation(VERTICAL);
        content.setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(18));
        LinearLayout heading = new LinearLayout(ui.context); heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = ui.text("Chart window", 20, true);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        heading.addView(title, new LayoutParams(0, -2, 1));
        TextView dismiss = ui.text("×", 26, false); dismiss.setGravity(Gravity.CENTER); dismiss.setFocusable(true);
        dismiss.setContentDescription("Close chart window choices"); dismiss.setOnClickListener(v -> opened.dismiss());
        heading.addView(dismiss, new LayoutParams(ui.dp(48), ui.dp(48))); content.addView(heading);
        for (int row = 0; row < (labels.length + 1) / 2; row++) {
            LinearLayout line = new LinearLayout(ui.context); line.setBaselineAligned(false);
            for (int column = 0; column < 2; column++) {
                final int index = row * 2 + column;
                LayoutParams cell = new LayoutParams(0, -1, 1);
                if (column == 0) cell.rightMargin = ui.dp(4); else cell.leftMargin = ui.dp(4);
                if (index >= labels.length) { line.addView(new View(ui.context), cell); continue; }
                TextView choice = ui.text(labels[index], 14, true); choice.setGravity(Gravity.CENTER);
                choice.setMinHeight(ui.dp(48)); choice.setPadding(ui.dp(8), ui.dp(10), ui.dp(8), ui.dp(10));
                choice.setFocusable(true); choice.setClickable(true); choice.setTag("stats-window-choice:" + index);
                choice.setContentDescription("Chart time window: " + labels[index]);
                choice.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                    @Override public void onInitializeAccessibilityNodeInfo(View view, AccessibilityNodeInfo info) {
                        super.onInitializeAccessibilityNodeInfo(view, info);
                        info.setClassName("android.widget.RadioButton"); info.setCheckable(true); info.setChecked(view.isSelected());
                        info.setCollectionItemInfo(AccessibilityNodeInfo.CollectionItemInfo.obtain(index / 2, 1, index % 2, 1, false, view.isSelected()));
                    }
                });
                choice.setOnClickListener(v -> { select(index, false); if (changed != null) changed.accept(index); opened.dismiss(); });
                style(choice, index == selected); choices.add(choice); line.addView(choice, cell);
            }
            LayoutParams spacing = new LayoutParams(-1, -2); spacing.topMargin = ui.dp(8); content.addView(line, spacing);
        }
        opened.setContentView(content);
        opened.setOnDismissListener(d -> { if (dialog == opened) dialog = null; });
        Window window = opened.getWindow();
        Motion.present(opened, Motion.DIALOG);
        if (window != null) {
            window.setBackgroundDrawable(ui.shape(ui.surface, 24, ui.outline)); window.getDecorView().setPadding(0, 0, 0, 0);
            int available = getResources().getDisplayMetrics().widthPixels - ui.dp(32);
            window.setLayout(Math.max(1, Math.min(ui.dp(520), available)), WindowManager.LayoutParams.WRAP_CONTENT);
        }
        opened.show();
    }
    void close() { if (dialog != null) { Dialog old = dialog; dialog = null; old.dismiss(); } }
}
