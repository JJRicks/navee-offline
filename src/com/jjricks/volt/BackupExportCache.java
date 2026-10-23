package com.jjricks.volt;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;

/** Private exports left behind when the process died before its normal cleanup. */
final class BackupExportCache {
    private BackupExportCache() { }

    static boolean hasOrphans(File cache) {
        for (File file : candidates(cache)) if (isExport(file)) return true;
        return false;
    }

    /** Only called once, before this process can start a backup session. Cache cleanup is
     * best effort: an unavailable cache must not prevent recovery of durable app data. */
    static void removeOrphans(File cache) {
        for (File file : candidates(cache)) {
            if (!isExport(file)) continue;
            try { Files.deleteIfExists(file.toPath()); }
            catch (IOException | SecurityException ignored) { /* Android can also reclaim cache files. */ }
        }
    }

    private static File[] candidates(File cache) {
        try {
            if (cache == null || Files.isSymbolicLink(cache.toPath()) || !cache.isDirectory()) return new File[0];
            // Resolve normal Android data-directory aliases, then work only on immediate children.
            File[] files = cache.getCanonicalFile().listFiles();
            return files == null ? new File[0] : files;
        } catch (IOException | SecurityException unavailable) { return new File[0]; }
    }

    private static boolean isExport(File file) {
        String name = file.getName();
        return ((name.startsWith("volt-encrypted-") && name.endsWith(".voltbackup")
                && name.length() > "volt-encrypted-.voltbackup".length())
                || (name.startsWith("volt-rides-") && name.endsWith(".voltrides")
                && name.length() > "volt-rides-.voltrides".length()))
                && Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS);
    }
}
