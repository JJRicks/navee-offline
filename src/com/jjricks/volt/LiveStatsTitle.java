package com.jjricks.volt;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Connection-aware heading; its decorative pulse never announces sample updates to TalkBack. */
final class LiveStatsTitle extends LinearLayout {
    private final Ui ui;
    private final TextView title;
    private final Signal signal;
    private boolean live;

    LiveStatsTitle(Ui ui) {
        super(ui.context); this.ui = ui;
        setGravity(Gravity.CENTER_VERTICAL); setPadding(0, ui.dp(2), 0, 0);
        title = ui.text("Live Stats", 24, true);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        addView(title, new LayoutParams(-2, -2));
        signal = new Signal(); LayoutParams dot = new LayoutParams(ui.dp(28), ui.dp(34)); dot.leftMargin = 0;
        addView(signal, dot); setLive(false);
    }
    void setLive(boolean value) {
        live = value; title.setTextColor(value ? ui.text : ui.muted);
        title.setContentDescription(value ? "Live Stats, connected" : "Live Stats, disconnected");
        signal.invalidate();
    }
    private final class Signal extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Signal() { super(ui.context); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas canvas) {
            float x = getWidth() / 2f, y = getHeight() / 2f;
            boolean moving = live && isShown() && hasWindowFocus() && Ui.motionEnabled();
            float phase = moving ? (SystemClock.uptimeMillis() % 3000) / 3000f : 0;
            int color = live ? ui.accent : ui.dim;
            if (moving) {
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(ui.dp(1));
                for (int n = 0; n < 2; n++) {
                    float progress = (phase + n * .5f) % 1;
                    paint.setColor(Ui.withAlpha(color, Math.round(80 * (1 - progress))));
                    canvas.drawCircle(x, y, ui.dp(5) + ui.dp(8) * progress, paint);
                }
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Ui.withAlpha(color, moving ? (int)Math.round(210 + 45 * Math.cos(phase * 2 * Math.PI)) : 255));
            canvas.drawCircle(x, y, ui.dp(4.5f), paint);
            if (moving) postInvalidateDelayed(40);
        }
        @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); invalidate(); }
        @Override protected void onVisibilityChanged(View changed, int visibility) { super.onVisibilityChanged(changed, visibility); invalidate(); }
    }
}
