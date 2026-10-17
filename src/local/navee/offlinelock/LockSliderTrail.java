package local.navee.offlinelock;

/**
 * Visual-only motion trail for the lock slider: a short, bounded history of thumb positions on a
 * monotonic clock. The trail runs from where the thumb was {@link #WINDOW_MS} ago to where it is
 * now, so it lengthens with speed and shrinks to nothing within one window once motion stops.
 * It never touches the confirmed lock value.
 */
public final class LockSliderTrail {
    public static final long WINDOW_MS = 56;
    static final int CAPACITY = 16;
    private final float[] positions = new float[CAPACITY];
    private final long[] times = new long[CAPACITY];
    private int newest = -1, size;

    public void clear() { newest = -1; size = 0; }

    public boolean isEmpty() { return size == 0; }

    /** Adds a sample; a clock that goes backwards starts a fresh trail rather than inventing motion. */
    public void record(float position, long nowMs) {
        if (size > 0 && nowMs < times[newest]) clear();
        if (size > 0 && nowMs == times[newest]) { positions[newest] = position; return; }
        newest = (newest + 1) % CAPACITY;
        positions[newest] = position;
        times[newest] = nowMs;
        if (size < CAPACITY) size++;
    }

    /**
     * Where the thumb was one window ago, interpolated between samples, with {@code current} as
     * the sample at {@code nowMs}. Equal to {@code current} when idle or empty.
     */
    public float tail(long nowMs, float current) {
        if (size == 0) return current;
        if (nowMs < times[newest]) { clear(); return current; }
        long target = nowMs - WINDOW_MS;
        float laterPosition = current;
        long laterTime = nowMs;
        for (int i = 0; i < size; i++) {
            int index = (newest - i + CAPACITY) % CAPACITY;
            long time = times[index];
            float position = positions[index];
            if (time <= target) {
                if (laterTime == time) return position;
                float f = (target - time) / (float) (laterTime - time);
                return position + (laterPosition - position) * f;
            }
            laterPosition = position;
            laterTime = time;
        }
        // History does not reach a full window back; the oldest retained sample bounds the trail.
        return laterPosition;
    }
}
