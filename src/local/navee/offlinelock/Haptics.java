package local.navee.offlinelock;

import android.content.Context;
import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.Switch;

/**
 * Subtle touch feedback where a moment benefits from it: picking something up, passing a slot,
 * choosing a tab or segment, flipping a switch, and the scooter confirming a deliberate action.
 * Ordinary taps and scrolling stay silent. Riders can turn it off in App preferences (on by
 * default), and Android's own Touch feedback setting still applies on top.
 */
final class Haptics {
    static final String PREFERENCE = "haptics_enabled";
    private Haptics() {}

    static boolean enabled(Context context) {
        return context != null && context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                .getBoolean(PREFERENCE, true);
    }

    /** A selection moved one step: a tab, a segment, a dragged item reaching a new slot. */
    static void tick(View view) {
        perform(view, Build.VERSION.SDK_INT >= 34 ? HapticFeedbackConstants.SEGMENT_TICK : HapticFeedbackConstants.CLOCK_TICK);
    }

    /** A switch or star changed state. */
    static void toggle(View view, boolean on) {
        perform(view, Build.VERSION.SDK_INT >= 34
                ? (on ? HapticFeedbackConstants.TOGGLE_ON : HapticFeedbackConstants.TOGGLE_OFF)
                : HapticFeedbackConstants.CLOCK_TICK);
    }

    /** Something was picked up to move. */
    static void lift(View view) {
        perform(view, Build.VERSION.SDK_INT >= 34 ? HapticFeedbackConstants.DRAG_START : HapticFeedbackConstants.LONG_PRESS);
    }

    /** A deliberate action took effect: the lock engaged or released, a ride started or was saved. */
    static void confirm(View view) {
        perform(view, Build.VERSION.SDK_INT >= 30 ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.CONTEXT_CLICK);
    }

    private static void perform(View view, int effect) {
        if (view != null && enabled(view.getContext())) view.performHapticFeedback(effect);
    }

    /**
     * A Switch that answers the rider's flip with a toggle haptic; programmatic changes stay silent.
     * The haptic follows the state the rider asked for: scooter settings put the switch back to the
     * reported state until the scooter replies.
     */
    static final class FeedbackSwitch extends Switch {
        FeedbackSwitch(Context context) { super(context); }
        @Override public boolean performClick() {
            boolean requested = !isChecked();
            boolean handled = super.performClick();
            Haptics.toggle(this, requested);
            return handled;
        }
    }
}
