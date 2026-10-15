package local.navee.offlinelock;

/** Connection cues are foreground transition feedback, never a status reminder. */
public final class ConnectionFeedbackStateTest {
    private static int checks;

    public static void main(String[] args) {
        initialStateAndRepeatedUpdates();
        successfulConnectionAndRetries();
        backgroundTransitionsAreConsumed();
        resumeAndFocusBaselines();
        bindingAndRecreationAreSilent();
        simulationIsSilent();
        System.out.println("ConnectionFeedbackState tests passed: " + checks + " assertions");
    }

    private static void initialStateAndRepeatedUpdates() {
        Object source = new Object();
        ConnectionFeedbackState gate = new ConnectionFeedbackState();
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
        ConnectionFeedbackState gate = new ConnectionFeedbackState();
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
        expect(ConnectionFeedbackState.Event.CONNECTED, gate.observe(source, true, true),
                "A successful reconnect gives a new success cue");
    }

    private static void backgroundTransitionsAreConsumed() {
        Object source = new Object();
        ConnectionFeedbackState gate = new ConnectionFeedbackState();
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
        expect(ConnectionFeedbackState.Event.DISCONNECTED, gate.observe(source, false, true),
                "A new visible disconnect after hidden reconnect is still meaningful");
    }

    private static void resumeAndFocusBaselines() {
        Object source = new Object();
        ConnectionFeedbackState gate = new ConnectionFeedbackState();
        gate.baseline(source, false);
        // The activity can miss callbacks while obscured, paused or being rebound.
        // Lifecycle baselines consume the current state before focus is eligible.
        gate.baseline(source, true);
        expect(ConnectionFeedbackState.Event.NONE, gate.observe(source, true, true),
                "Focus gained after an unseen connection is silent");
        gate.baseline(source, false);
        expect(ConnectionFeedbackState.Event.NONE, gate.observe(source, false, true),
                "Resume after an unseen disconnect is silent");
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
        ConnectionFeedbackState gate = new ConnectionFeedbackState();
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
        gate = new ConnectionFeedbackState();
        expect(ConnectionFeedbackState.Event.NONE, gate.observe(second, true, true),
                "Activity recreation does not repeat a connection cue");
    }

    private static void simulationIsSilent() {
        Object real = new Object();
        ConnectionFeedbackState gate = new ConnectionFeedbackState();
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

    private static void expect(ConnectionFeedbackState.Event expected,
            ConnectionFeedbackState.Event actual, String message) {
        checks++;
        if (expected != actual) throw new AssertionError(message + ": " + actual);
    }
}
