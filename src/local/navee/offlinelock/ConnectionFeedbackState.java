package local.navee.offlinelock;

import java.util.function.LongSupplier;

/** Activity-local connection edges. Hidden changes and new bindings never replay as feedback. */
final class ConnectionFeedbackState {
    static final long RECONNECT_QUIET_MS = 5_000;
    enum Event { NONE, CONNECTED, DISCONNECTED }
    private final LongSupplier elapsedTime;
    private Object source;
    private boolean connected;
    private long lastLossAt = -1;

    ConnectionFeedbackState(LongSupplier elapsedTime) {
        this.elapsedTime = elapsedTime;
    }

    void baseline(Object source, boolean connected) {
        // Focus changes must not reset a recent loss and bypass the quiet period.
        if (source == null || this.source != source) lastLossAt = -1;
        else if (this.connected && !connected) lastLossAt = elapsedTime.getAsLong();
        this.source = source;
        this.connected = source != null && connected;
    }

    Event observe(Object source, boolean connected, boolean focused) {
        if (source == null || this.source != source) {
            baseline(source, connected);
            return Event.NONE;
        }
        boolean changed = this.connected != connected;
        this.connected = connected;
        if (!changed) return Event.NONE;
        long now = elapsedTime.getAsLong();
        boolean quiet = lastLossAt >= 0
                && (now < lastLossAt || now - lastLossAt < RECONNECT_QUIET_MS);
        // Consume even hidden/suppressed drops. A flapping link stays quiet until
        // there has been a five-second gap; no timer later replays an old edge.
        if (!connected) lastLossAt = now;
        if (!focused || quiet) return Event.NONE;
        return connected ? Event.CONNECTED : Event.DISCONNECTED;
    }
}
