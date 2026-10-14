// Test-only Android/Shizuku API surface; never packaged in the app.
package android.os;
import java.util.ArrayList;
import java.util.List;
/** Deterministic main-thread queue; advancing time never sleeps on the host. */
public final class Handler {
    private static final List<Task> tasks = new ArrayList<>();
    private static long now;
    private static long sequence;
    public Handler(Looper looper) { }
    public boolean post(Runnable runnable) { return postDelayed(runnable, 0); }
    public boolean postDelayed(Runnable runnable, long delay) {
        tasks.add(new Task(now + delay, sequence++, runnable)); return true;
    }
    public static void reset() { tasks.clear(); now = 0; sequence = 0; }
    public static void drain() { advanceBy(0); }
    public static void advanceBy(long delay) {
        long end = now + delay;
        while (true) {
            Task next = null;
            for (Task task : tasks) {
                if (task.at <= end && (next == null || task.at < next.at
                        || (task.at == next.at && task.sequence < next.sequence))) next = task;
            }
            if (next == null) break;
            tasks.remove(next); now = next.at; next.runnable.run();
        }
        now = end;
    }
    private static final class Task {
        final long at, sequence; final Runnable runnable;
        Task(long at, long sequence, Runnable runnable) {
            this.at = at; this.sequence = sequence; this.runnable = runnable;
        }
    }
}
