package com.jjricks.volt;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded stdout + concurrently drained, discarded stderr. Never exposes subprocess errors/logs. */
final class OwnerCaptureProcess {
    // The guard may be waiting for a child with a six-second timeout. Let its
    // signal/EXIT traps finish before using a forced kill as a last resort.
    static final int TERMINATION_GRACE_SECONDS = 10;
    static String run(String... argv) throws Exception {
        return runBounded(8, argv);
    }
    static String runBounded(int seconds, String... argv) throws Exception {
        Process process = new ProcessBuilder(argv).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicBoolean failed = new AtomicBoolean();
        Thread stdout = drain(process.getInputStream(), output, failed, process);
        Thread stderr = drain(process.getErrorStream(), null, failed, process);
        try {
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) throw new Exception("Command timed out");
            stdout.join(1000); stderr.join(1000);
            if (stdout.isAlive() || stderr.isAlive() || failed.get() || process.exitValue() != 0)
                throw new Exception("Command failed");
            return new String(output.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            terminate(process);
            try { process.getInputStream().close(); } catch (Exception ignored) { }
            try { process.getErrorStream().close(); } catch (Exception ignored) { }
            try { process.getOutputStream().close(); } catch (Exception ignored) { }
        }
    }
    private static void terminate(Process process) {
        boolean interrupted = Thread.interrupted();
        try {
            if (!process.isAlive()) return;
            process.destroy();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TERMINATION_GRACE_SECONDS);
            while (process.isAlive()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                try { process.waitFor(remaining, TimeUnit.NANOSECONDS); }
                catch (InterruptedException ignored) { interrupted = true; }
            }
            if (process.isAlive()) process.destroyForcibly();
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
    private static Thread drain(InputStream in, ByteArrayOutputStream out, AtomicBoolean failed, Process process) {
        Thread worker = new Thread(() -> {
            try (InputStream stream = in) {
                byte[] bytes = new byte[4096]; int count; long total = 0;
                while ((count = stream.read(bytes)) != -1) {
                    total += count;
                    if (total > OwnerCaptureEngine.MAX_OUTPUT) {
                        if (failed.compareAndSet(false, true)) process.destroy();
                        // Keep draining without retaining bytes while termination completes.
                    } else if (out != null) out.write(bytes, 0, count);
                }
            } catch (Exception ignored) { failed.set(true); }
        }, "Owner-capture-output");
        worker.setDaemon(true); worker.start(); return worker;
    }
    private OwnerCaptureProcess() { }
}
