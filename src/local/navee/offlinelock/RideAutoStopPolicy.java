package local.navee.offlinelock;

import java.util.Objects;

/**
 * Decides when one ride recording was forgotten at a stop: fresh scooter motion pushes showed it
 * parked, then the Bluetooth link was lost and stayed lost. In-process only and never persisted, so
 * a restarted service starts unarmed and needs new evidence. Every input carries the caller's
 * monotonic clock (SystemClock.elapsedRealtime in the app); the policy never reads a clock itself.
 *
 * Thresholds and why:
 * - A stationary run needs {@link #STATIONARY_SAMPLES} distinct motion pushes (speed 0 and driving
 *   status 0) spanning at least {@link #STATIONARY_SPAN_MS}. One zero report is a moment at a
 *   light; a few seconds of repeated zeros is a stop. Any moving, missing or out-of-order push
 *   clears the run. The ride must also have shown fresh motion first, so a scooter that has just
 *   started, or a service that restarted mid-ride, cannot look parked.
 * - A push counts only when it is at most {@link #MOTION_FRESH_MS} old when seen, the same window
 *   BleController.stationary() uses for parked-only controls. At the loss, the newest stationary
 *   push must also be within that window of the last frame heard: zeros that stopped updating
 *   while other packets kept arriving are stale, not parking.
 * - The loss must be seen within {@link #LOSS_WINDOW_MS} of the last frame. A recording declares
 *   a silent link lost after 12 s (checked every 2 s), so walking out of range lands inside it.
 * - Only a recoverable transport loss arms. The ride ends after {@link #DISCONNECT_GRACE_MS} of
 *   one continuous disconnection; any authenticated reconnect cancels it, and separate short gaps
 *   never add up because each loss needs a new stationary run on a new connection.
 */
public final class RideAutoStopPolicy {
    public static final int STATIONARY_SAMPLES = 2;
    public static final long STATIONARY_SPAN_MS = 3000, MOTION_FRESH_MS = 6000, LOSS_WINDOW_MS = 15000,
            DISCONNECT_GRACE_MS = 10000;
    public final String rideId, profile, serial;
    private int connection;
    private boolean bound, moved;
    private long lastNow, lastMotion, lastFrame, runStart, runLast, lossMono;
    private int runCount;

    public RideAutoStopPolicy(String rideId, String profile, String serial) {
        if (rideId == null || rideId.isEmpty() || profile == null || profile.isEmpty() || serial == null || serial.isEmpty())
            throw new IllegalArgumentException("A ride on a verified scooter is required");
        this.rideId = rideId; this.profile = profile; this.serial = serial;
    }

    /** Waiting out the disconnect grace after a parked loss. */
    public boolean armed() { return lossMono > 0; }
    public long remainingMs(long now) { return armed() ? Math.max(0, DISCONNECT_GRACE_MS - (now - lossMono)) : 0; }

    /** Explicit stop, user disconnect, profile change, setting off or uncertain state: forget all evidence. */
    public void reset() { bound = moved = false; lastMotion = lastFrame = lossMono = 0; clearRun(); }

    /**
     * An authenticated connection is up. The motion facts are the transport's own (zero/-1 when no
     * push has arrived), never cached display readings.
     */
    public void connected(long now, String boundProfile, String transportSerial, int transport,
            long frameMono, long motionMono, int speedRaw, int driving) {
        if (!clock(now)) return;
        lossMono = 0; // Any authenticated reconnect ends the continuous disconnection.
        if (!profile.equals(boundProfile) || !serial.equals(transportSerial)) {
            // Unknown or different scooter: nothing here is evidence for this ride.
            bound = false; lastMotion = lastFrame = 0; clearRun(); return;
        }
        if (!bound || transport != connection) { bound = true; connection = transport; lastMotion = lastFrame = 0; clearRun(); }
        if (frameMono > 0 && frameMono <= now && frameMono >= lastFrame) lastFrame = frameMono;
        if (motionMono <= 0) { clearRun(); return; }
        // Preserve a parked run during complete radio silence until the loss watchdog fires.
        // Other fresh frames with old motion establish uncertainty and invalidate that run.
        if (motionMono == lastMotion) {
            if (lastFrame - motionMono > MOTION_FRESH_MS || speedRaw < 0 || driving < 0) clearRun();
            return;
        }
        if (motionMono < lastMotion || motionMono > now) { lastMotion = motionMono; clearRun(); return; }
        if (lastMotion > 0 && motionMono - lastMotion > MOTION_FRESH_MS) clearRun();
        lastMotion = motionMono;
        if (now - motionMono > MOTION_FRESH_MS || speedRaw < 0 || driving < 0) { clearRun(); return; }
        if (speedRaw > 0 || driving != 0) { moved = true; clearRun(); return; }
        if (!moved) return;
        if (runCount == 0) runStart = motionMono;
        runLast = motionMono; runCount++;
    }

    /** The authenticated connection is gone. Only the first observation of a loss can arm. */
    public void disconnected(long now, boolean recoverable) {
        if (!clock(now)) return;
        if (!recoverable) { reset(); return; }
        if (!bound) return; // Already handled this loss, or never had this ride's scooter.
        bound = false;
        boolean parked = recoverable && runCount >= STATIONARY_SAMPLES && runLast - runStart >= STATIONARY_SPAN_MS
                && lastFrame >= runLast && lastFrame - runLast <= MOTION_FRESH_MS
                && now >= lastFrame && now - lastFrame <= LOSS_WINDOW_MS;
        clearRun(); lastMotion = lastFrame = 0;
        if (parked) lossMono = now;
    }

    /** True once when the grace has run out; the caller then ends this ride through its normal stop. */
    public boolean due(long now) {
        if (!clock(now) || !armed() || now - lossMono < DISCONNECT_GRACE_MS) return false;
        lossMono = 0; return true;
    }

    /** True only for the policy instance that belongs to the ride still recording. */
    public boolean owns(String activeRideId) { return Objects.equals(rideId, activeRideId); }

    private void clearRun() { runCount = 0; runStart = runLast = 0; }

    /** A clock that moves backwards can no longer measure the gap or the stop: fail closed. */
    private boolean clock(long now) {
        if (now <= 0 || now < lastNow) { reset(); lastNow = Math.max(0, now); return false; }
        lastNow = now; return true;
    }
}
