package com.jjricks.volt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Uses real files and links to verify startup cleanup stays within its narrow cache scope. */
public final class BackupExportCacheTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("volt-export-cleanup-");
        try {
            Path cache = Files.createDirectory(root.resolve("cache"));
            Path orphan = Files.write(cache.resolve("volt-encrypted-123.voltbackup"), new byte[]{1,2,3});
            Path rideOrphan = Files.write(cache.resolve("volt-rides-123.voltrides"), new byte[]{1,2,3});
            Path other = Files.write(cache.resolve("user-selected.voltbackup"), new byte[]{4});
            Path near = Files.write(cache.resolve("volt-encrypted-.voltbackup"), new byte[]{5});
            Path nested = Files.createDirectory(cache.resolve("nested"));
            Path nestedExport = Files.write(nested.resolve("volt-encrypted-456.voltbackup"), new byte[]{6});
            Path directory = Files.createDirectory(cache.resolve("volt-encrypted-folder.voltbackup"));
            Path outside = Files.write(root.resolve("owner-data"), new byte[]{7,8});
            Path link = Files.createSymbolicLink(cache.resolve("volt-encrypted-link.voltbackup"), outside);
            Path cacheAlias = Files.createSymbolicLink(root.resolve("cache-alias"), cache);
            check(BackupExportCache.hasOrphans(cache.toFile()), "Recognizes a direct encrypted export");
            check(!BackupExportCache.hasOrphans(cacheAlias.toFile()), "Does not inspect a linked cache directory");
            BackupExportCache.removeOrphans(cacheAlias.toFile());
            check(Files.exists(orphan), "Linked cache cleanup leaves its target alone");
            BackupExportCache.removeOrphans(cache.toFile());
            check(!Files.exists(rideOrphan), "Deletes a stale private ride export");
            check(!Files.exists(orphan), "Deletes a stale encrypted export");
            check(Files.exists(other) && Files.exists(near), "Preserves unrelated and non-export names");
            check(Files.exists(nestedExport) && Files.isDirectory(directory), "Does not recurse or remove directories");
            check(Files.isSymbolicLink(link), "Does not remove linked export entries");
            check(Files.readAllBytes(outside).length == 2, "Does not alter a symlink target");
            check(!BackupExportCache.hasOrphans(cache.toFile()), "Only real direct export files count as orphans");
            BackupExportCache.removeOrphans(cache.toFile());
            check(Files.exists(other) && Files.exists(nestedExport), "Repeated cleanup remains harmless");
            check(!BackupExportCache.hasOrphans(root.resolve("missing").toFile()), "Missing cache is harmless");
            BackupExportCache.removeOrphans(null);
            System.out.println("BackupExportCacheTest: " + checks + " checks passed");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(path);
            }
        }
    }
}
