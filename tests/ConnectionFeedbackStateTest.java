package local.navee.offlinelock;

/** Connection cues are foreground transition feedback, never a status reminder. */
public final class ConnectionFeedbackStateTest {
    private static int checks;
    private static final class Clock implements java.util.function.LongSupplier {
        long now = 1_000;
        @Override public long getAsLong() { return now; }
        void advance(long ms) { now += ms; }
    }

    public static void main(String[] args) {
        initialStateAndRepeatedUpdates();
        successfulConnectionAndRetries();
        backgroundTransitionsAreConsumed();
        resumeAndFocusBaselines();
        bindingAndRecreationAreSilent();
        simulationIsSilent();
        reconnectQuietPeriod();
        focusBaselinesPreserveQuietPeriod();
        hiddenAndReplacementConnections();
        System.out.println("ConnectionFeedbackState tests passed: " + checks + " assertions");
    }

    private static void initialStateAndRepeatedUpdates() {
        Object source = new Object();
        Clock clock = new Clock();
        ConnectionFeedbackState gate = new ConnectionFeedbackState(clock);
        expect(ConnectionFeedbackState.Event.NONE, gate.observe(source, true, true),
                "Opening an already connected service is silent");
        // Telemetry, status text, redraws and a stale live reading all retain the
        // verified session boolean. They must not act as another connection.
        for (int update = 0; update < 100; update++) {
            expect(ConnectionFeedbackState.Event.NONE, gate.observe(source, true, true),
                    "Repeated connected status is silent");
        }
        expect(ConnectionFeedbackState.Event.DISCONNECTED, gate.observe(source, false, true),
                "The real link loss still emits after repeated updates");
        expect(ConnectionFeedbackState.Event.NONE, gate.observe(source, false, true),
                "Repeated disconnect notice is silent");
    }

    private static void successfulConnectionAndRetries() {
        Object source = new Object();
        Clock clock = new Clock();
        ConnectionFeedbackState gate = new ConnectionFeedbackState(clock);
        gate.baseline(source, false);
        for (int attempt = 0; attempt < 10; attempt++) {
            expect(ConnectionFeedbackState.Event.NONE, gate.observe(source, false, true),
                    "Scanning, authenticating and failed retries are not successful sessions");
        }
        expect(ConnectionFeedbackState.Event.CONNECTED, gate.observe(source, true, true),
                "First verified connection gives one success cue");
        expect(ConnectionFeedbackState.Event.DISCONNECTED, gate.observe(source, false, true),
                "Explicit or unexpected disconnect gives one loss cue");
        expect(ConnectionFeedbackState.Event.NONE, gate.observe(source, false, true),
                "Waiting for reconnect gives no second loss cue");
        clock.advance(ConnectionFeedbackState.RECONNECT_QUIET_MS);
        expect(ConnectionFeedbackState.Event.CONNECTED, gate.observe(source, true, true),
                "A successful reconnect after the quiet period gives a new success cue");
    }

    private static void backgroundTransitionsAreConsumed() {
        Object source = new Object();
        Clock clock = new Clock();
        ConnectionFeedbackState gate = new ConnectionFeedbackState(clock);
        gate.baseline(source, false);
        expect(ConnectionFeedbackState.Event.NONE, gate.observe(source, true, false),
                "A background connection is silent");
        expect(ConnectionFeedbackState.Event.NONE, gate.observe(source, true, true),
                "Returning to focus does not replay background connection");
        expect(ConnectionFeedbackState.Event.NONE, gate.observe(source, false, false),
                "A background disconnect is silent");
        expect(ConnectionFeedbackState.Event.NONE, gate.observe(source, false, true),
                "Returning to focus does not replay background disconnect");
        expect(ConnectionFeedbackState.Event.NONE, gate.observe(source, true, false),
                "A subsequent hidden reconnect is consumed");
        clock.advance(ConnectionFeedbackState.RECONNECT_QUIET_MS);
        expect(ConnectionFeedbackState.Event.DISCONNECTED, gate.observe(source, false, true),
                "A new visible disconnect after a stable hidden reconnect is meaningful");
    }

    private static void resumeAndFocusBaselines() {
        Object source = new Object();
        Clock clock = new Clock();
        ConnectionFeedbackState gate = new ConnectionFeedbackState(clock);
        gate.baseline(source, false);
        // The activity can miss callbacks while obscured, paused or being rebound.
        // Lifecycle baselines consume the current state before focus is eligible.
        gate.baseline(source, true);
        expect(ConnectionFeedbackState.Event.NONE, gate.observe(source, true, true),
                "Focus gained after an unseen connection is silent");
        gate.baseline(source, false);
        expect(ConnectionFeedbackState.Event.NONE, gate.observe(source, false, true),
                "Resume after an unseen disconnect is silent");
        clock.advance(ConnectionFeedbackState.RECONNECT_QUIET_MS);
        expect(ConnectionFeedbackState.Event.CONNECTED, gate.observe(source, true, true),
                "A later visible connection still emits");
        gate.baseline(source, true);
        expect(ConnectionFeedbackState.Event.DISCONNECTED, gate.observe(source, false, true),
                "An unchanged focus baseline does not hide the next real loss");
    }

    private static void bindingAndRecreationAreSilent() {
        // These compare equal deliberately: service identity must use object identity.
        Object first = new String("service");
        Object second = new String("service");
        Clock clock = new Clock();
        ConnectionFeedbackState gate = new ConnectionFeedbackState(clock);
        gate.baseline(first, false);
        expect(ConnectionFeedbackState.Event.NONE, gate.observe(second, true, true),
                "A replacement service baselines even when equals() matches");
        expect(ConnectionFeedbackState.Event.DISCONNECTED, gate.observe(second, false, true),
                "The replacement service can report its own later disconnect");
        expect(ConnectionFeedbackState.Event.NONE, gate.observe(null, false, true),
                "Binder removal is not a physical disconnect");
        expect(ConnectionFeedbackState.Event.NONE, gate.observe(first, true, true),
                "Rebinding to a connected service is silent");
        gate.baseline(second, false);
        expect(ConnectionFeedbackState.Event.CONNECTED, gate.observe(second, true, true),
                "Explicit binding baseline permits a later real success");
        gate = new ConnectionFeedbackState(clock);
        expect(ConnectionFeedbackState.Event.NONE, gate.observe(second, true, true),
                "Activity recreation does not repeat a connection cue");
    }

    private static void simulationIsSilent() {
        Object real = new Object();
        Clock clock = new Clock();
        ConnectionFeedbackState gate = new ConnectionFeedbackState(clock);
        gate.baseline(real, true);
        expect(ConnectionFeedbackState.Event.NONE, gate.observe(null, true, true),
                "Entering simulation does not claim a real connection");
        expect(ConnectionFeedbackState.Event.NONE, gate.observe(null, false, true),
                "Simulation disconnect is silent");
        expect(ConnectionFeedbackState.Event.NONE, gate.observe(null, true, true),
                "Simulation reconnect is silent");
        expect(ConnectionFeedbackState.Event.NONE, gate.observe(real, false, true),
                "Leaving simulation silently establishes real disconnected baseline");
        expect(ConnectionFeedbackState.Event.CONNECTED, gate.observe(real, true, true),
                "An actual connection after simulation still emits");
    }

    private static void reconnectQuietPeriod() {
        Clock clock = new Clock();
        ConnectionFeedbackState gate = new ConnectionFeedbackState(clock);
        Object source = new Object();
        gate.baseline(source, false);
        expect(ConnectionFeedbackState.Event.CONNECTED, gate.observe(source, true, true),
                "Initial connection stays immediate");
        expect(ConnectionFeedbackState.Event.DISCONNECTED, gate.observe(source, false, true),
                "First loss stays immediate, even after a short connection");
        for (int cycle = 0; cycle < 20; cycle++) {
            clock.advance(1_000);
            expect(ConnectionFeedbackState.Event.NONE, gate.observe(source, true, true),
                    "Rapid reconnect stays quiet");
            clock.advance(1_000);
            expect(ConnectionFeedbackState.Event.NONE, gate.observe(source, false, true),
                    "Repeated loss extends the quiet period");
        }
        clock.advance(ConnectionFeedbackState.RECONNECT_QUIET_MS - 1);
        expect(ConnectionFeedbackState.Event.NONE, gate.observe(source, true, true),
                "Reconnect just inside boundary is silent");
        clock.advance(1);
        expect(ConnectionFeedbackState.Event.NONE, gate.observe(source, true, true),
                "Expiration does not replay the suppressed reconnect");
        expect(ConnectionFeedbackState.Event.DISCONNECTED, gate.observe(source, false, true),
                "A new edge at exact boundary emits normally");
        clock.advance(ConnectionFeedbackState.RECONNECT_QUIET_MS);
        expect(ConnectionFeedbackState.Event.CONNECTED, gate.observe(source, true, true),
                "A new reconnect at exact boundary emits normally");
    }

    private static void focusBaselinesPreserveQuietPeriod() {
        Clock clock = new Clock();
        ConnectionFeedbackState gate = new ConnectionFeedbackState(clock);
        Object source = new Object();
        gate.baseline(source, true);
        expect(ConnectionFeedbackState.Event.DISCONNECTED, gate.observe(source, false, true),
                "Initial loss before a dialog is audible");
        clock.advance(100);
        gate.baseline(source, false);
        expect(ConnectionFeedbackState.Event.NONE, gate.observe(source, true, true),
                "Dismissing a dialog does not bypass cooldown");
        clock.advance(100);
        gate.baseline(source, false); // A loss while obscured, missed by observer.
        clock.advance(ConnectionFeedbackState.RECONNECT_QUIET_MS - 1);
        expect(ConnectionFeedbackState.Event.NONE, gate.observe(source, true, true),
                "Unseen loss consumed by focus baseline restarts cooldown");
        clock.advance(ConnectionFeedbackState.RECONNECT_QUIET_MS);
        gate.baseline(source, true);
        expect(ConnectionFeedbackState.Event.NONE, gate.observe(source, true, true),
                "Focus baseline never invents a delayed cue");
        expect(ConnectionFeedbackState.Event.DISCONNECTED, gate.observe(source, false, true),
                "Later real loss works after focus baseline");
    }

    private static void hiddenAndReplacementConnections() {
        Clock clock = new Clock();
        ConnectionFeedbackState gate = new ConnectionFeedbackState(clock);
        Object first = new Object(), second = new Object();
        gate.baseline(first, true);
        expect(ConnectionFeedbackState.Event.NONE, gate.observe(first, false, false),
                "Hidden loss is silent but starts quiet period");
        expect(ConnectionFeedbackState.Event.NONE, gate.observe(first, true, true),
                "Foreground rapid reconnect after hidden loss is silent");
        gate.baseline(second, false);
        expect(ConnectionFeedbackState.Event.CONNECTED, gate.observe(second, true, true),
                "Another controller does not inherit previous controller cooldown");
        expect(ConnectionFeedbackState.Event.DISCONNECTED, gate.observe(second, false, true),
                "New controller loss remains immediate");
        gate.baseline(null, false);
        gate.baseline(second, false);
        expect(ConnectionFeedbackState.Event.CONNECTED, gate.observe(second, true, true),
                "Leaving simulation establishes a fresh real source");
    }

    private static void expect(ConnectionFeedbackState.Event expected,
            ConnectionFeedbackState.Event actual, String message) {
        checks++;
        if (expected != actual) throw new AssertionError(message + ": " + actual);
    }
}
