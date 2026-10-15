package local.navee.offlinelock;

/** Activity-local connection edges. Hidden changes and new bindings never replay as feedback. */
final class ConnectionFeedbackState {
    enum Event { NONE, CONNECTED, DISCONNECTED }
    private Object source;
    private boolean connected;

    void baseline(Object source, boolean connected) {
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
        if (!focused || !changed) return Event.NONE;
        return connected ? Event.CONNECTED : Event.DISCONNECTED;
    }
}
