package com.jjricks.volt;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.View;

/** Short foreground connection cues. The activity owns transition detection and calls cancel on blur. */
final class ConnectionHapticPlayer {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Vibrator activeVibrator;
    private long activeUntil;
    private int generation;

    /** Main-thread only; pass the stable window decor rather than a dashboard child. */
    void play(View decor, boolean connected) {
        cancel();
        if (!allowed(decor)) return;
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                Vibrator vibrator = decor.getContext().getSystemService(Vibrator.class);
                int[] primitives = connected
                        ? new int[] {VibrationEffect.Composition.PRIMITIVE_CLICK, VibrationEffect.Composition.PRIMITIVE_THUD}
                        : new int[] {VibrationEffect.Composition.PRIMITIVE_TICK, VibrationEffect.Composition.PRIMITIVE_TICK,
                                VibrationEffect.Composition.PRIMITIVE_TICK};
                // API 30 reports composition support, not reliable per-primitive support.
                if (vibrator != null && vibrator.hasVibrator() && vibrator.areAllPrimitivesSupported(primitives)) {
                    int[] durations = vibrator.getPrimitiveDurations(primitives);
                    boolean knownDurations = durations.length == primitives.length;
                    for (int duration : durations) knownDurations &= duration > 0;
                    if (knownDurations) {
                        float[] scales = connected ? new float[] {.25f, .85f} : new float[] {.35f, .18f, .08f};
                        int token = generation;
                        long delay = 0;
                        for (int i = 0; i < primitives.length; i++) {
                            final int primitive = primitives[i], duration = durations[i];
                            final float scale = scales[i];
                            if (i == 0) pulse(decor, vibrator, primitive, scale, duration, token);
                            else handler.postDelayed(() -> pulse(decor, vibrator, primitive, scale, duration, token), delay);
                            delay += duration + (connected ? 45 : 30);
                        }
                        return;
                    }
                }
            } catch (RuntimeException unavailable) {
                // A device's vibration service must never prevent connecting to a scooter.
                cancel();
            }
        }
        // A short platform effect is gentler than a raw buzzy waveform on older hardware.
        if (allowed(decor)) decor.performHapticFeedback(connected
                ? (Build.VERSION.SDK_INT >= 30 ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.CONTEXT_CLICK)
                : HapticFeedbackConstants.CLOCK_TICK);
    }

    private void pulse(View decor, Vibrator vibrator, int primitive, float scale, int duration, int token) {
        if (token != generation) return;
        if (!allowed(decor)) { cancel(); return; }
        try {
            VibrationEffect effect = VibrationEffect.startComposition().addPrimitive(primitive, scale).compose();
            if (Build.VERSION.SDK_INT >= 33) {
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH));
            } else {
                vibrator.vibrate(effect, new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            }
            activeVibrator = vibrator;
            activeUntil = SystemClock.uptimeMillis() + duration;
            handler.postDelayed(() -> {
                if (token == generation && SystemClock.uptimeMillis() >= activeUntil) activeVibrator = null;
            }, duration);
        } catch (RuntimeException unavailable) {
            cancel();
        }
    }

    /** Cancels queued pulses, and only interrupts hardware while one of our own pulses may be active. */
    void cancel() {
        generation++;
        handler.removeCallbacksAndMessages(null);
        Vibrator vibrator = activeVibrator;
        activeVibrator = null;
        if (vibrator != null && SystemClock.uptimeMillis() < activeUntil) {
            try { vibrator.cancel(); } catch (RuntimeException unavailable) { /* Optional feedback. */ }
        }
        activeUntil = 0;
    }

    private static boolean allowed(View view) {
        if (view == null || !view.isAttachedToWindow() || !view.hasWindowFocus()
                || !view.isShown() || view.getWindowVisibility() != View.VISIBLE
                || !view.isHapticFeedbackEnabled() || !Haptics.enabled(view.getContext())) return false;
        Context context = view.getContext();
        PowerManager power = context.getSystemService(PowerManager.class);
        if (power != null && !power.isInteractive()) return false;
        try {
            return Settings.System.getInt(context.getContentResolver(), Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
}
