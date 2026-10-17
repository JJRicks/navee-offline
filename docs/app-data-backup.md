# App backups

**Back up & restore** writes a password-encrypted `.voltbackup` holding any selection of seven categories, up to a complete copy of the app's durable data. Restore merges by default, can **Replace everything** from a complete file, and also imports older full backups, `.voltrides` ride backups and dashboard layout files. It never sends stored values to the scooter. The categories, merge rules and screens are described in [Unified backup and restore](unified-backup.md); every stored key and file is classified in the [storage inventory](app-storage-inventory.md).

## Included and excluded data

| Category | Holds |
|---|---|
| Scooters & setup keys | Profiles (names, owner setup identities, addresses, serials, selected scooter), the legacy pre-profile setup (exact restores only), setup completion |
| Dashboards | Every scooter's dashboard, its three slots, widget options and notes, and the column choice |
| App preferences | Units, appearance, text size, haptics, connection and recording options, map choice and provider, favourites, chart and selector choices |
| Rides | Raw scooter journals with phone GPS/pressure and motion sidecars, names, notes, clocks and gaps |
| Charges | Complete and interrupted charge journals with their saved estimate samples |
| Readings history | `telemetry-history.db`: last-seen readings, detail and minute/daily rollups |
| Scooter preferences | Per-scooter range source, range-follows-mode, charger choice and the dormant command cache. App-side only. |

Never included: Android permission grants, battery optimization exemptions, notification settings, Bluetooth bonds, USB debugging authorization and Shizuku access (Android owns them); window, tab and boot-session state; pending owner imports, exports and onboarding progress; the owner-capture cleanup journal; transfer staging and journals; connection diagnostics; guided setting test records (kept on this phone); map tile and export caches; learned range, capacity and charge-curve results (recomputed after reopening); and the scooter's own firmware settings. A restore never reads, clears or replaces this phone's cleanup journal or pending documents.

Every backup is encrypted, so the password is required even for a rides-only file. Losing the password makes the file unusable.

## Format

The encryption is unchanged from version 1. The header is eight bytes `VOLTBKP` followed by binary version `1`, a fresh random 16-byte salt and a fresh random 8-byte nonce prefix. PBKDF2-HMAC-SHA256 uses 210,000 iterations to derive a 256-bit AES key. Passwords contain 8–1,024 Java characters.

The compressed stream is split into at most 64 KiB plaintext records. Each record contains a big-endian four-byte plaintext length followed by AES-256-GCM ciphertext and its 16-byte tag. Its 12-byte nonce is the header's eight-byte prefix followed by the monotonically increasing four-byte record index. Authenticated additional data is the complete header, record index and plaintext length. A separately authenticated zero-length record terminates the stream, and trailing bytes are rejected. All records are authenticated before the ZIP is interpreted.

The ZIP's first entry is `manifest.bin`. **Payload version 2** (written since this change) records `volt-app-data`, version 2, creation time, app version, the backup registry version, whether the backup is complete, each category's ID with its entry count and uncompressed bytes, a scooter table (ID, name, serial; no credentials) for mapping on restore, and the total entry count and bytes. It is followed by `prefs/<category>.bin` (the typed preference encoding below, limited to the namespaces that category uses), `prefs/unassigned.bin` in complete backups only, `files/rides/…`, `files/phone-rides/…`, `files/charge-recordings/…` and `database/telemetry-history.db`. Every entry must belong to a declared category, and each category's count and bytes must match. **Payload version 1** (`volt-full-app-data`, Volt 1.3.1 and earlier) has a single `preferences.bin` with all five namespaces; it is read as a complete backup and its keys are classified on import.

The preference encoding retains strings, booleans, integers, longs, floats and string sets. On import every key is reclassified by the current registry: a key is applied only if its category is selected, excluded keys are never applied, and keys the registry does not know (from older or newer versions) are restored only by Replace everything. Registered keys must have their reader's type; owner profile lists are checked with Android's JSON reader (fields added by newer versions are kept), journals are parsed, and the SQLite header, integrity and schema are checked before a preview is shown. A dashboard layout file is checked by the same validator as Home's import.

Limits are 2 GiB of uncompressed data, 512 MiB per recording, 64 MiB for history, 16 MiB for each preference file, 1 MiB for the manifest and 50,000 entries. Entry names, duplicates, preference counts and lengths and decompression output are bounded. Absolute paths, traversal, links, nested or unknown folders, out-of-category files and unsupported files are rejected. A file from a newer app or registry version is refused, and an unencrypted file with a version 2 manifest is not accepted.

## Consistency and recovery

Before a backup or restore, the app stops its screens and service writers, drains recorder and history workers and closes history. The transfer screen then works without the scooter service. A lingering non-empty SQLite journal makes the backend refuse.

Import decrypts and validates into private no-backup storage (`no_backup/app-data-transfer/stage-…`). Nothing live changes while the password is checked, the file is parsed, the scooters are chosen or the preview is shown. Mapping a backup scooter to a local one rewrites only the staged journals. Cancel and startup cleanup remove unused stages.

On **Restore**, the plan is computed again; if saved data changed since the preview, the restore stops. Free space must cover the merged history and the rollback snapshot plus 64 MiB, or the restore fails with "Not enough free storage" before any change. The merged history is built as a closed copy (this phone's rows win on every unique key, backup rows are remapped to the chosen scooter, history's own retention rules keep it within its 24 MiB limit) and checked. The transaction then snapshots only what the plan touches: previous values of the touched preference namespaces, the history database if it changes, and whole recording folders for Replace everything. It records the scope and each added file's filesystem identity, syncs the snapshot and its parent chain, and writes an `active` marker. Added recordings move into place without replacing any existing name, sidecars before journals, each checked against its recorded identity. Replaced folders and the database are swapped by rename; preferences are committed per namespace through Android `SharedPreferences`. Live folders, `shared_prefs` and their parents are synced before the `committed` marker.

A failure rolls back: files the restore installed are removed only if their identity still matches (an unrelated file at that name stays), replaced folders and the database come back from the snapshot, and the touched namespaces get their previous values. A process interruption leaves the snapshot and marker; startup finishes recovery before any model or service opens, and recovery is repeatable. An unreadable or unsuccessful recovery blocks normal startup and keeps its snapshot. A transaction left by an older version (no scope record) is recovered with the version 1 rules, which restore every recording folder, the database and all five namespaces.

The transfer screen requires the app to be reopened after completion, so old in-memory models cannot overwrite restored data. The operations can temporarily need several times the dataset size in free internal storage. This is not a substitute for an external backup against hardware or storage failure, or for keeping storage intact during a restore.

## Validation

Pure Java suites (`tests/run.sh`) use real encrypted streams and temporary files:

- `BackupRegistryTest` checks that no key matches two rules and scans the app source for namespaces, typed preference keys, prefixes, constants and storage roots; an unclassified one fails, naming the key.
- `BackupArchiveTest` covers the complete-reset roundtrip, each single-category file, restores of all 127 category subsets (unselected data stays value- and byte-identical; repeats change nothing), the committed legacy fixtures (`tests/fixtures/legacy/`), wrong passwords, corruption, truncation, trailing bytes and hostile authenticated archives.
- `BackupPlanTest` covers scooter resolution, merge rules per category, UUID and same-recording duplicates, two backup scooters mapped to one, and a new phone without scooters.
- `BackupTransactionTest` injects a failure and a process death at every boundary (build, snapshot, active, each move, each folder, database, each namespace commit, live sync, commit, cleanup), failing rollback then recovery, failing directory syncs, version 1 transaction recovery, low storage, a stale review, cancel and identity-protected rollback.
- `AppDataArchiveTest` keeps covering the version 1 codec and transaction.

Emulator runners (disposable emulators only; listed in [Unified backup and restore](unified-backup.md#emulator-journeys)) cover the Android crypto provider, real SQLite merge and validation, real preference commits and post-commit failure rollback, the screens, a two-process export → host copy → Clear storage → restore journey, the Volt 1.3.1 fixture and a read-only storage inventory. These tests do not simulate physical power loss, and none sends scooter commands.
