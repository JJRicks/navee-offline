package local.navee.offlinelock;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded stdout + concurrently drained, discarded stderr. Never exposes subprocess errors/logs. */
final class OwnerCaptureProcess {
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
            process.destroyForcibly();
            try { process.getInputStream().close(); } catch (Exception ignored) { }
            try { process.getErrorStream().close(); } catch (Exception ignored) { }
            try { process.getOutputStream().close(); } catch (Exception ignored) { }
        }
    }
    private static Thread drain(InputStream in, ByteArrayOutputStream out, AtomicBoolean failed, Process process) {
        Thread worker = new Thread(() -> {
            try (InputStream stream = in) {
                byte[] bytes = new byte[4096]; int count, total = 0;
                while ((count = stream.read(bytes)) != -1) {
                    total += count;
                    if (total > OwnerCaptureEngine.MAX_OUTPUT) { failed.set(true); process.destroyForcibly(); break; }
                    if (out != null) out.write(bytes, 0, count);
                }
            } catch (Exception ignored) { failed.set(true); }
        }, "Owner-capture-output");
        worker.setDaemon(true); worker.start(); return worker;
    }
    private OwnerCaptureProcess() { }
}
