# Portable full app backups

The developer full-data transfer is an encrypted snapshot of the app's durable local data. It is intended for testing onboarding after clearing app storage, and for moving the same data to a new installation. Restore replaces the current data; it does not merge histories or send stored parameter values to the scooter.

## Included and excluded data

Included:

- `app_settings`: preferences, dashboard layouts and all slots/options, favourite readings/charts, chart selections, custom map-provider settings, charger metadata and other local options.
- `profiles` and the legacy `scooter` preferences: owner setup identities, profile names, scooter addresses/serials, selected profile and last connection dates.
- `control_evidence` and `onboarding` preferences: cached command results and setup progress.
- Raw electrical ride journals, phone/GPS/motion journals, retained charge recordings (including interrupted journals).
- `telemetry-history.db`: retained historical readings, rollups and last-seen parameters.

Excluded:

- Android permission grants, battery optimization exemptions, USB debugging authorization and Shizuku access. Android owns these; a fresh installation must grant them again.
- Downloaded map-tile caches, diagnostic caches, pending owner-import/export documents and an in-progress owner-capture cleanup journal. They describe temporary operations on a particular device rather than durable portable state.
- The scooter's actual firmware/settings. Restored values are observations; normal Bluetooth reads establish the current state.

The target's owner-capture cleanup journal and pending-document preferences are never replaced. A developer should finish pending setup operations before taking a backup. The file contains owner credentials and route history, so the password is required even for local exports. Losing that password makes the file unusable.

## Format version 1

The `.voltbackup` file contains a ZIP stream protected by authenticated encryption. The header is eight bytes `VOLTBKP` followed by binary version `1`, a fresh random 16-byte salt and a fresh random 8-byte nonce prefix. PBKDF2-HMAC-SHA256 uses 210,000 iterations to derive a 256-bit AES key. Passwords contain 8–1,024 Java characters; longer memorable passwords are preferable.

The compressed stream is split into at most 64 KiB plaintext records. Each record contains a big-endian four-byte plaintext length followed by AES-256-GCM ciphertext and its 16-byte tag. Its 12-byte nonce is the header's eight-byte prefix followed by the monotonically increasing four-byte record index. Authenticated additional data is the complete header, record index and plaintext length. A separately authenticated zero-length record terminates the stream, and trailing bytes are rejected. This framing bounds encryption/decryption memory and makes truncation, reordering, insertion and substitution fail authentication. All records are authenticated before the ZIP is interpreted or extracted.

The ZIP's first entry is a small versioned manifest with creation time, originating app version, file count and total uncompressed bytes. Next are a binary typed preference inventory and allowlisted recording/database entries. The preference encoding retains strings, booleans, integers, longs, floats and string sets. Unknown preference keys survive; known startup/read-path keys must have compatible types. Owner-profile JSON and the actual SQLite integrity/schema are checked before the restore can be confirmed.

Limits are 2 GiB of uncompressed durable data, 512 MiB per recording, 64 MiB for history, 16 MiB for preferences, and 50,000 files. The manifest, entry names, duplicate entries, preference counts/lengths and decompression output are bounded. Absolute paths, traversal paths, links, unknown folders and unsupported files are rejected. Importing a backup written by a newer app version is refused. A new durable storage location or schema needs an explicit update to the format inventory and tests.

## Consistency and recovery

Before a full export or restore, the app stops its screens and service writers, drains recorder/history workers and closes history. The transfer screen then works without the scooter service. Lingering non-empty SQLite journal/WAL/shared-memory files cause the backend to refuse the operation.

Import first decrypts and verifies into the app's private no-backup directory. Nothing in the live dataset is changed during password verification, parsing or the restore confirmation. Cancel and startup cleanup remove unused decrypted stages.

Before applying a restore, the backend makes a complete private rollback snapshot of the previous files and preference values. It writes an active transaction marker before changing any live data. Preferences are replaced with Android `SharedPreferences` API commits, so cached values agree with the on-disk state. A failure rolls the previous dataset back. A process interruption leaves the rollback snapshot and marker; startup finishes recovery before any normal model or service opens. Recovery itself is repeatable if interrupted. An unreadable or unsuccessful recovery blocks normal startup and keeps its snapshot.

The transfer screen requires the app to be reopened after completion, ensuring old in-memory profiles/models cannot overwrite restored data. A completed transaction is marked before its private recovery files are removed. The operations can temporarily need several times the dataset size in free internal storage. Tests cover process interruption and I/O failures; this mechanism is not a substitute for retaining an external backup against hardware/storage failure or clearing app storage during a restore.

## Validation

`tests/AppDataArchiveTest.java` exercises real encrypted streams and temporary files: all namespaces and primitive/set types, every recording directory, exact history bytes, replacement versus merging, a failing preference commit, process interruption during apply, a failing rollback followed by recovery, wrong passwords, ciphertext corruption/truncation, hostile authenticated ZIP paths/duplicates, format/version checks and cleanup.

`android-tests/AppDataBackupValidation.java` runs only on an emulator with isolated directories and namespaced real Android preferences. It checks the Android crypto provider, actual SQLite validation, fresh-data and clear-data-style restoration, preservation of target cleanup/OS permissions, post-commit preference failure rollback, malformed profile/type/database rejection, and deletion of staged private data. The separate maintenance fixture verifies that app writers are drained before transfer. No tests issue scooter commands.

`android-tests/FullBackupJourneyValidation.java` provides a two-process, actual-app-storage journey on a disposable emulator. Run its `export` mode only after clearing the emulator app. Pull the resulting `backup-journey.voltbackup` out to the host, clear the app's storage again, and push the same archive back into the app's external files folder. Its `restore-check` mode requires fresh private storage, imports the saved archive, compares every preference value and every durable file hash, and reopens the real profile/dashboard/ride/phone/charge/history readers. Both phases refuse physical phones or an existing private dataset. The fixture needs no Bluetooth, location or notification permissions and never starts the scooter service.
