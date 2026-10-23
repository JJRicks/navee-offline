package com.jjricks.volt;

import java.util.Objects;

/** In-process recovery for one explicitly started capture; never persisted or auto-resumed. */
public final class ChargeRecoveryPolicy {
    public static final long RECOVERY_TIMEOUT_MS = 5 * 60 * 1000L;
    private final String profile, serial;
    private final long startedMono, disconnectSequence;
    private long interruptedMono;

    public ChargeRecoveryPolicy(String profile, String serial, long startedMono, long disconnectSequence) {
        if (profile == null || profile.isEmpty() || serial == null || serial.isEmpty() || startedMono <= 0)
            throw new IllegalArgumentException("A verified scooter is required");
        this.profile = profile; this.serial = serial; this.startedMono = startedMono;
        this.disconnectSequence = disconnectSequence;
    }

    public boolean recovering() { return interruptedMono > 0; }
    public long remainingMs(long now) {
        return recovering() ? Math.max(0, RECOVERY_TIMEOUT_MS - (now - interruptedMono)) : 0;
    }

    public static final class Result {
        public final ChargeSessionStore.Stop stop;
        public final boolean record, beganRecovery, resumed;
        private Result(ChargeSessionStore.Stop stop, boolean record, boolean beganRecovery, boolean resumed) {
            this.stop = stop; this.record = record; this.beganRecovery = beganRecovery; this.resumed = resumed;
        }
    }
    private Result stop(ChargeSessionStore.Stop reason) { return new Result(reason, false, false, false); }

    /** All transport and frame facts must come from the current authenticated connection. */
    public Result evaluate(long now, String selectedProfile, String savedSerial, long intentionalDisconnects,
            boolean permissions, boolean bluetoothOn, boolean connected, boolean ready, boolean recoverable,
            String transportSerial, ChargeSessionStore.Frame frame) {
        return evaluate(now, selectedProfile, savedSerial, intentionalDisconnects, permissions, bluetoothOn, connected, ready,
                recoverable, transportSerial, frame, false);
    }
    /**
     * scheduledPause: Volt's own armed charging schedule may be holding current off. A fresh
     * charging=off packet after a loss then resumes the capture so the pause can be marked.
     */
    public Result evaluate(long now, String selectedProfile, String savedSerial, long intentionalDisconnects,
            boolean permissions, boolean bluetoothOn, boolean connected, boolean ready, boolean recoverable,
            String transportSerial, ChargeSessionStore.Frame frame, boolean scheduledPause) {
        if (intentionalDisconnects != disconnectSequence) return stop(ChargeSessionStore.Stop.STOPPED);
        if (!Objects.equals(profile, selectedProfile)) return stop(ChargeSessionStore.Stop.PROFILE_CHANGED);
        if (!Objects.equals(serial, savedSerial)) return stop(ChargeSessionStore.Stop.IDENTITY_CHANGED);
        if (!permissions) return stop(ChargeSessionStore.Stop.PERMISSION_LOST);
        if (!bluetoothOn) return stop(ChargeSessionStore.Stop.BLUETOOTH_OFF);
        if (now < startedMono || now - startedMono >= ChargeSessionStore.MAX_SESSION_MS)
            return stop(ChargeSessionStore.Stop.LIMIT);
        if (recovering() && (now < interruptedMono || remainingMs(now) == 0))
            return stop(ChargeSessionStore.Stop.RECOVERY_TIMEOUT);
        if (!connected) {
            if (!recoverable) return stop(ChargeSessionStore.Stop.CONNECTION_FAILED);
            boolean began = !recovering();
            if (began) interruptedMono = now;
            return new Result(null, false, began, false);
        }
        if (transportSerial != null && !transportSerial.isEmpty() && !serial.equals(transportSerial))
            return stop(ChargeSessionStore.Stop.IDENTITY_CHANGED);
        if (recovering()) {
            if (!ready || !serial.equals(transportSerial) || frame == null)
                return new Result(null, false, false, false);
            // A fresh post-loss charging=off packet may end a capture, but a 100% endpoint
            // still requires its paired electrical packet. Neither path fills the missing time.
            if (afterLoss(frame.state, now) && frame.state.value != 1
                    && afterLoss(frame.soc, now) && frame.soc.value < 99) {
                if (!scheduledPause || frame.state.value != 0) return stop(ChargeSessionStore.Stop.CHARGING_ENDED);
                interruptedMono = 0;
                return new Result(null, true, false, true);
            }
            if ((!frame.usable() && !frame.usableCompletion() && !(frame.usableChargingEnded() && frame.soc.value>=99)) || !afterLoss(frame.state, now)
                    || !afterLoss(frame.soc, now) || !afterLoss(frame.voltage, now) || !afterLoss(frame.current, now))
                return new Result(null, false, false, false);
            interruptedMono = 0;
            return new Result(null, true, false, true);
        }
        return new Result(null, ready && serial.equals(transportSerial), false, false);
    }

    private boolean afterLoss(ChargeSessionStore.Value value, long now) {
        return ChargeSessionStore.fresh(value, now) && value.mono > interruptedMono;
    }
}
