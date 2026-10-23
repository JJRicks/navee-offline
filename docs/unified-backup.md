# Unified backup and restore (design)

Status: implemented on top of Volt 1.3.1 (`d3d61e8`); the decisions on the earlier open questions are recorded under [Decisions](#decisions), and differences from the first draft under [Implementation notes](#implementation-notes). The [emulator journeys](#emulator-journeys) ran and passed on disposable emulators emulator-5560 and emulator-5558. A physical phone is still needed for the restore flow on real owner data, the system file picker with cloud providers, and the restore screens seen directly rather than as in-process renders. The storage it covers is listed in [App storage inventory](app-storage-inventory.md).

## Goal

There is one **Back up & restore** entry in ordinary settings, replacing the developer full backup, the separate ride backups and settings Dashboard backups. The user picks categories, sees counts and a preview, and gets anything from a single category up to a complete portable copy of the app after **Clear storage**. Files from the old features still import. The existing crypto, bounded parsing, validation-before-mutation, writer drain, fsync ordering, rollback and startup recovery are reused, not replaced.

## Current state (1.3.1)

| Feature | Format | Semantics | Entry |
|---|---|---|---|
| Full app data | `.voltbackup` v1: `VOLTBKP\1` header, PBKDF2 → AES-256-GCM 64 KiB records, ZIP of `manifest.bin` (`volt-full-app-data` v1), `preferences.bin` (five namespaces), `files/{rides,phone-rides,charge-recordings}/…`, `database/telemetry-history.db` | Replace everything | Developer options, onboarding welcome |
| Rides | `.voltrides`: plain ZIP, `manifest.bin` (`volt-ride-backup` v1) with source profiles and SHA-256 per file | Add-only UUID merge, explicit profile mapping, conflicts keep local | Rides → Ride backups |
| Dashboards | JSON `volt-dashboard-layouts` v1, one scooter's dashboard, three slots and `dashboard_columns` | Replace that scooter's layouts | More → App preferences → Dashboard backups; Home → Edit → Layout |

The v1 full backup copies namespaces whole, so it carries device state (`more_away_boot`), pending operations (`owner_import_applied`, onboarding wizard state) and a dormant cache (`control_evidence`). Category restore needs key-level classification instead.

## Categories

Stable IDs go in the manifest. Labels are UI copy.

| ID | Label | Contents | Profile link |
|---|---|---|---|
| `scooters` | Scooters & setup keys | `profiles.*`, legacy `scooter.*`, `onboarding.onboarding_done` | defines profiles |
| `dashboards` | Dashboards | `dashboard_v1_<pid>`, `dashboard_slot_v1_<pid>_<n>`, `dashboard_empty_example_v1_<pid>`, `dashboard_columns` | per scooter (`<pid>` suffix) |
| `preferences` | App preferences | Global durable `app_settings` keys: units, appearance, text size, haptics, connection and recording options, map choice and provider, favourites, chart and selector choices, `developer_options`, `range_learned_intro_seen`, `last_ride_label` | none |
| `rides` | Rides | `files/rides/<uuid>.csv`, `files/phone-rides/<uuid>-{phone,motion}.csv` as one group per ride; `ride_comparison`, `ride_selected` | START record profile |
| `charges` | Charges | `files/charge-recordings/<uuid>.{csv,active}` (complete, interrupted and estimate samples) | journal header profile |
| `history` | Readings history | `databases/telemetry-history.db`: last-seen readings, detail, minute/daily rollups | `profiles.name` rows |
| `scooter-preferences` | Scooter preferences | `range_source_<pid>`, `range_follow_mode_<pid>`, `charge_charger_<pid>`, `control_evidence.<pid>` | per scooter |

`control_evidence` is a dormant cache, unused since 1.1.0. It is restored as data only and never authorizes a control: controls still require fresh authenticated state. Scooter preferences restore app-side choices only. They never push settings to the scooter.

### Never exported or restored

| Item | Reason |
|---|---|
| `app_settings`: `last_page`, `more_section`, `scooter_section`, `stats_section`, `more_wide_*_scroll`, `more_away_*`, `stats_away_*`, `stats_query`, `stats_chart_query`, `dashboard_editing` | Device, window or boot-session state |
| `app_settings.notification_prompted`, `onboarding.nearby_asked` | Tied to this installation's Android permission prompts |
| `app_settings.owner_import_applied`, `pending-owner-import.*`, `pending-export-*`, `pending_documents.*` | Pending operations of this installation |
| `onboarding.*` except `onboarding_done` | Setup wizard in progress on this installation |
| `app_settings.dashboard_restore_undo_v1_<pid>` | Short-lived undo. Removed for every scooter whose dashboard is restored, so a stale undo cannot revert the restore. |
| `owner_capture_cleanup.*`, `/data/local/tmp/volt-owner-capture.lock` | Shizuku cleanup journal for this phone. Never read, cleared or replaced. |
| `no_backup/connection-events` | Device diagnostics |
| `no_backup/compat-tests` (+ `.tmp`) | Guided setting test records stay on this phone (D4). Never exported, cleared or replaced by a restore. |
| `no_backup/app-data-transfer`, `no_backup/ride-transfer`, `*.repair`, `*.mapping`, `*-journal` | Transactions and temporary files |
| `cache/*` (map tiles, charge/ride exports, prepared backups) | Disposable |
| Android permissions, battery-optimization exemption, notification settings, Bluetooth bonds, USB debugging, Shizuku grant | Owned by Android, not app files |
| Physical scooter settings | Observations are restored, firmware state is not. Reapplying settings would be a separate reviewed feature. |
| Learned range, capacity and charge-curve results | In memory only. Recomputed from restored rides, charges and history when Volt reopens. |

## Category registry

`BackupRegistry` (new, pure Java) is the single classification table. Each rule has:

- `namespace` plus an exact key or a prefix, and a `perProfile` flag (the profile ID is the suffix; for dashboard slots it is `<pid>_<n>`).
- the expected type (String, Boolean, Integer, Long, Float or String set). This replaces the hand-written lists in `AppDataArchive.validatePreferenceTypes`.
- exactly one category, **or** `EXCLUDED` with a reason string.

File roots get the same treatment (`files/rides`, …, `databases/telemetry-history.db`, excluded roots). The registry has an integer `VERSION`, written to the manifest.

Rules:

1. A key matching zero or two rules fails `BackupRegistryTest` (see Tests). New code cannot ship an unclassified key.
2. **Unassigned keys** found at runtime have no rule. Examples are keys removed in an older version and test fixture keys. They are exported only in a *complete* backup (`prefs/unassigned.bin`) and restored only by **Replace everything**. This keeps the complete-reset roundtrip exact without leaking them into category restores.
3. On import, keys are reclassified by the *current* registry. A key is applied only if its current category is selected; excluded keys are never applied, even from v1 files.

## Format: `.voltbackup` version 2

**Encryption.** Every new backup is encrypted, whatever the categories, using the existing v1 framing unchanged: header `VOLTBKP\1`, random salt and nonce prefix, PBKDF2-HMAC-SHA256 with 210,000 iterations, AES-256-GCM records with authenticated header, index and length, a terminal record, rejection of trailing bytes, and authentication before ZIP parsing. There is no new scheme and no plaintext export of credentials. This means new ride-only or dashboard-only backups also need a password. Rides contain locations, and one file type with one password rule is simpler to explain. The record framing stays version 1, and the payload version changes in the manifest. Older Volt versions reject a v2 file with "Unsupported backup format", which is correct.

**ZIP payload** (entry order fixed, first entry the manifest):

```
manifest.bin   DataOutputStream:
  UTF "volt-app-data", int 2, long createdMs, int appVersionCode, int registryVersion,
  boolean complete                       // every category exported, unassigned keys included
  int categoryCount; per category: UTF id, int items, long bytes
  int profileCount; per profile: UTF id, UTF name, UTF serial   // for mapping; no credentials
  int entryCount, long totalBytes
prefs/<category>.bin    typed preference subset (existing encoding, namespace list declared per file)
prefs/unassigned.bin    complete backups only
files/rides/<uuid>.csv, files/phone-rides/<uuid>-{phone,motion}.csv
files/charge-recordings/<uuid>.{csv,active}
database/telemetry-history.db
```

The profile table lists every profile referenced by an exported category, plus all profiles when `scooters` is selected. Names and serials are inside the encrypted payload. Every entry must belong to a declared category, and per-category counts and bytes must match. Unknown, duplicate, traversal, absolute, link or out-of-category entries are rejected. Existing limits stay: 2 GiB total, 512 MiB per recording, 64 MiB history file, 16 MiB preferences, 50,000 entries, and a bounded manifest (1 MiB). Files from a newer app version or a newer registry version than the reader supports are refused.

**Legacy imports.** The Restore picker accepts all three old formats. The file is identified by its content, not its name:

| Input | Detected by | Treated as |
|---|---|---|
| `.voltbackup` v1 | header plus `volt-full-app-data` v1 manifest | complete backup; prefs classified by registry; all categories present |
| `.voltrides` | ZIP whose first entry is a `volt-ride-backup` manifest | `rides` only, unencrypted, existing RideArchive validation; its source-profile list feeds mapping |
| Dashboard JSON | `"format":"volt-dashboard-layouts"` | `dashboards` only, one source ("Layout file") mapped to one destination scooter (default: selected scooter) |

The new code no longer writes `.voltrides` files or full v1 backups. Dashboard JSON export stays only on Home → Edit → Layout as a sharing tool (decision 1).

## Restore semantics

### Modes

- **Merge** is the default and works with any selection. Each selected category follows its merge rule below. Unselected categories and excluded items stay byte- and value-identical.
- **Replace everything** is available only when the file is complete (v1, or v2 with `complete`) and every category is selected. It reproduces the 1.3.1 full restore: the five namespaces become the backup's values, unassigned keys included, while excluded keys keep this phone's values. The recording folders and history file are replaced whole. The switch defaults on when this phone has no scooters, rides, charges or history (the Clear-storage case), and off otherwise.

### Merge rule per category

| Category | Merge |
|---|---|
| Scooters | Add backup profiles whose ID is not here. An ID already here keeps this phone's profile and credentials ("already here"). A backup profile matching a local one by non-empty serial (else identity + MAC) maps to the local profile and is not duplicated. `active` changes only if this phone had no profiles. Set `legacy_migrated=true`, so the legacy `scooter` namespace can never migrate a second copy; that namespace itself is only restored by Replace everything. If the result has a configured profile, onboarding is written as finished (`clear()` + `onboarding_done=true`, as `OnboardingScreen` does). |
| Dashboards | For each mapped scooter, replace its dashboard, three slots and empty-example flag, and remove its restore-undo key. Replace `dashboard_columns`. Other scooters' dashboards stay. |
| App preferences | Backup wins: each category key takes the backup value, and keys of this category missing from the backup are removed (back to default). |
| Rides | Add-only by UUID, as RideArchive today. Identical groups are skipped. A differing or partial local group is kept and counted as a conflict. No new UUID is ever synthesized. Hardening: a ride whose mapped profile and START wall and monotonic clocks equal an existing ride with another UUID also counts as a conflict. Orphan phone sidecars are skipped and counted. `ride_comparison` and `ride_selected` stay local. |
| Charges | Same as rides by UUID. `<uuid>.active` and `<uuid>.csv` are the same identity. Journals are validated by `ChargeSessionStore` parsing, which v1 did not do. An imported `.active` becomes interrupted at the next startup, which is existing behaviour. |
| Readings history | Merge the database by mapped profile. Rows are unioned on the existing UNIQUE keys (`detail(pid,kid,wall)`, `minute`/`daily(pid,kid,bucket)`), and this phone's row wins on collision. `latest` keeps this phone's snapshot; otherwise the newest backup snapshot is used. Profile and key IDs are remapped by name. The result must pass `integrity_check` and the schema check, and stay within the history size limit (decision 6). |
| Scooter preferences | For each mapped scooter, replace its `range_source_`, `range_follow_mode_`, `charge_charger_` and `control_evidence` entries. |

### Profile resolution (dependencies)

Each backup profile referenced by a selected category resolves to exactly one of:

1. the same ID here;
2. added by the selected Scooters category;
3. a local scooter. This is pre-chosen when the serial matches, otherwise the user chooses;
4. **Don't restore**.

Review stays disabled until every source is resolved. Ride and charge files are rewritten in staging: the START profile field via `RideStore.reassignJournal`, and the charge header via a new `ChargeSessionStore.reassignJournal`. History rows are remapped in the merged copy, and per-scooter keys are renamed. Identity and conflict checks run *after* mapping, so repeated imports stay idempotent and learned range never trains on duplicates. Data is never silently dropped or attached to another scooter: the preview lists every skipped item ("3 rides skipped · scooter not restored"). If two sources map to one destination, rides, charges and history merge, but that destination's Dashboards and Scooter preferences are skipped and the preview says so. On a phone with no scooters, a backup without Scooters can only be restored with **Don't restore** for its scooter data, and the screen says "Include Scooters or set up a scooter first".

## Transaction

All restores, including `.voltrides` and dashboard JSON, use one engine (`BackupTransaction`) in the existing `no_backup/app-data-transfer` folder. `DataMaintenance.readyForStartup`/`AppDataBackup.needsRecovery` already guard that folder.

1. **Pause.** Use the existing `ScooterService.prepareDataMaintenance`. It refuses recordings, pending phone saves, charge exports, history cleanup, pending scooter writes and Shizuku cleanup. It drains writers, closes history and commits every namespace. MainActivity also refuses pending owner imports and exports. Simulation is off because the service is destroyed. The transfer screen runs alone until Volt closes. Export uses the same pause, so the snapshot is consistent.
2. **Stage (no live change).** Decrypt or copy to `stage-<uuid>`, authenticate, extract with bounds, then validate: typed prefs via registry, profile JSON (existing `validateProfiles`), ride, phone and charge journals, SQLite header, integrity and schema.
3. **Plan (read-only).** Resolve profiles and compute per-category results against the live state. This is the preview.
4. **Build incoming (still no live change).** Apply mappings to staged journals. Build `merged.db` (copy of the live DB plus the attached backup DB, merged in one SQLite transaction, integrity-checked, closed, fsynced). Compute the final value map for each touched namespace, keeping excluded and unselected keys from live. Write `prefs.bin`. Re-plan; if live data changed since the preview, stop with "Saved data changed. Review again."
5. **Storage preflight.** Usable space must cover incoming plus snapshot plus a 64 MiB margin. Otherwise the restore fails with "Not enough free storage" before any mutation.
6. **Snapshot.** In `transaction/`: `previous/prefs.bin` (the touched namespaces); `previous/database` if History is touched; `previous/files/<root>` only for roots replaced whole; `scope.bin` listing replaced roots and touched namespaces; `additions.bin` listing each added path and its staged file identity. Rename the stage to `incoming`, sync the tree and parent chain, then write `state=active`.
7. **Apply.** Move added files with no-replace moves, sidecars before journals, and check identity after each move (RideArchive method). Replace whole roots in Replace-everything mode. Rename `merged.db` over the live DB after removing any empty journal. Commit prefs per namespace through `SharedPreferences` (keeps caches coherent). Sync the live folders, `shared_prefs` and parents, then write `state=committed`, then clean up.
8. **Rollback** (failure, or startup with `state=active`): re-arm `active`; delete only identity-matched additions; restore the snapshotted DB and roots; restore prefs; sync; `committed`; delete. Recovery is repeatable. An unreadable record blocks startup and keeps the snapshot, as today.
9. **Legacy recovery.** A v1 transaction (no `scope.bin`) means every root and all five namespaces, so the same rollback covers it. `RideArchive.recover` stays for an interrupted `ride-transfer` left by an older version.

The app must be reopened after any transfer, including cancel. Derived models are rebuilt from restored data in the new process, and nothing derived is imported.

## UI

Copy is short. The screen keeps FLAG_SECURE, follows the user's theme and text size (as RideBackupActivity does), uses 48 dp rows with checkbox semantics and spoken counts, and a polite live region for status. It has no decorative animation, and any expand honours `Ui.motionEnabled()`.

**Entry.** More → **Back up & restore** opens a dialog with **Back up** and **Restore**. Then a confirm: "Volt pauses until you reopen it." / Continue. Onboarding’s prominent **Restore Volt backup** button and setup-path option go straight to Restore, before pairing or Bluetooth permission. The former owner-only export now uses this same full-default flow.

**Back up** (BackupActivity):

```
Back up
[x] Select all
[x] Scooters & setup keys   2 · Private
[x] Dashboards              2 scooters
[x] App preferences
[x] Rides                   14 · 38 MB
[x] Charges                 3 · 1 MB        (shown only if any)
[x] Readings history        12 MB
[x] Scooter preferences     2 scooters
About 51 MB
New password / Repeat password
Password can't be recovered.
[Create backup]  →  save picker  →  "Backup saved."   [Close Volt]
```

Empty categories show "None" and are disabled. Counts are file counts and sizes plus profile counts, computed after the pause on the worker. Nothing is parsed for this.

**Restore:**

1. **Choose file.** If the file is encrypted, ask for the password, then "Checking backup…".
2. "Backup from 16 Oct 2026, 15:30", followed by the categories in the file, with counts, all checked. A complete file adds the **Replace everything on this phone** switch.
3. **Scooters** (only if needed): "Rides from "Old UT5" go to:" with a menu of [this phone's scooters…, Add from backup (if Scooters is selected), Don't restore].
4. **Review**, one line per category, then the action:
   - "Rides: 12 new · 2 already here · 1 kept here"
   - "Dashboards: replaces layouts for Fold UT5"
   - "Scooters: adds 1 · 1 already here"
   - "App preferences: replaces 24 settings"
   - "Readings history: adds to 2 scooters"
   - Skipped lines, if any.
   - Action: **Restore**, or **Replace everything** in that mode.
5. "Restoring…", then "Restored. Reopen Volt." and [Close Volt]. Errors use the existing short messages: "Wrong password or bad file.", "Not enough free storage", "Restore failed. Reopen Volt. Don't clear storage."

A note under the category list: "Permissions and Bluetooth pairing aren't included." (as today).

**Old entry points:**

- Developer options: remove **Export/Restore all app data**. Keep **Load All widgets into slot 3**.
- More → App preferences → **Dashboard backups**: remove.
- Rides → **Ride backups**: a shortcut into the unified flow with only Rides preselected (decision 2).
- Home → Edit → Layout → Export/Import: kept as a plain layout-file sharing tool (decision 1).
- MainActivity `fullBackup`/`rideBackup` collapse into `dataBackup(Boolean restore, String preselect)` on `ScreenHost` (a null direction shows the Back up / Restore dialog). RideBackupActivity and its manifest entry are removed, and its mapping UI moves into BackupActivity. `BackupExportCache` keeps the `volt-encrypted-*.voltbackup` pattern for prepared exports.
- Profile/onboarding → **Save private owner backup** is now replaced by the unified full-default backup. Legacy owner setup files still import with an access-only notice (supersedes original decision 8).

## Implementation plan

1. **Legacy fixtures first, from baseline code.** Add small synthetic fixtures under `tests/fixtures/legacy/`: a v1 `.voltbackup` (synthetic password, written by the current `AppDataArchive.write`), a `.voltrides` and a dashboard JSON, plus a README saying they are synthetic. They hold no real data.
2. `BackupRegistry` + `tests/BackupRegistryTest.java`: rules from the inventory, types, excluded reasons, source scanner. Replace `validatePreferenceTypes` with registry types.
3. Refactor `AppDataArchive`: expose the crypto (header, key, `RecordOutput`, `decrypt`) and generalize preference encoding to declared namespace subsets. Keep v1 stage parsing.
4. `BackupArchive` (pure): v2 writer by category, `stage` that detects v1/v2/`.voltrides`/dashboard JSON, `Prepared` with categories, counts and profile table, and `plan` with profile resolution and per-category results.
5. `BackupTransaction` (pure): build incoming, preflight, snapshot, apply, rollback and recover (v1 and v2 layouts). Fault hooks go on `Storage`, as the existing tests use.
6. Store helpers: `ChargeSessionStore.inspectJournal`/`reassignJournal` (Java 8, bounded); reuse `RideStore.inspectJournal`/`reassignJournal` and the RideArchive validators (package-private).
7. `AppDataBackup` Android adapter: namespace-subset prefs read/commit, `mergeHistory(live, incoming, map, out)` with SQLite ATTACH and INSERT OR IGNORE, `usableSpace`, and the existing DB validation.
8. `DataMaintenance.ensureRecovered` calls `BackupTransaction.recover`, then `RideBackup.recover`, then cache cleanup.
9. UI: unified `BackupActivity`; `MoreScreen` entry and removals; `RidesScreen` shortcut; `OnboardingScreen` link; `MainActivity.beginBackup` and `ScreenHost`; `DashboardScreen` layout tool kept (decision 1); remove `RideBackupActivity` and its `AndroidManifest.xml` entry.
10. Tests: pure suites listed below in a separate block at the end of `tests/run.sh`; update the android tests; `bash tests/run.sh`, `bash build.sh`, `bash android-tests/build.sh`.
11. Docs: rewrite `docs/app-data-backup.md` (v2 format, categories, transaction) and note in `docs/ride-backups.md` that `.voltrides` is import-only. Update `docs/user-guide.md` (Back up and restore), `docs/getting-started.md`, `FEATURES.txt`, `PRIVACY.md` table, `README.md` line 41 and `SECURITY.md`. Keep `docs/app-storage-inventory.md` in sync.

## Tests

Pure Java (`javac --release 8`, temp dirs, synthetic data):

- `BackupRegistryTest`:
  - Every rule maps to one category or is excluded, and no key matches two rules.
  - It scans `src/local/navee/offlinelock/*.java` for `getSharedPreferences("…")` namespaces, for string literals and `"prefix_" +` prefixes, and for `static final String` constants used as the first argument of `get/put{String,Int,Boolean,Long,Float,StringSet}`. A short reviewed ignore list covers JSON, Bundle and Intent keys.
  - It checks `new File(get{Files,NoBackupFiles,Cache}Dir()|getDatabasePath, "…")` roots.
  - It fails naming the key and "assign it in BackupRegistry".
- `BackupArchiveTest`:
  - v2 roundtrip for each single category and for all 127 non-empty subsets. Unselected keys and files must be value- and byte-identical before and after export plus merge restore.
  - Complete export to a fresh store under Replace everything equals the source, except excluded keys.
  - Legacy v1 fixture: Replace everything matches the 1.3.1 result, and a selective restore from v1 works.
  - `.voltrides` and dashboard JSON fixtures import.
  - Wrong password; ciphertext corruption, truncation and trailing bytes; hostile ZIP (traversal, duplicate, unknown, out-of-category, count and byte mismatch, oversize, nested); newer app or registry version; unencrypted v2 refused.
- `BackupPlanTest`:
  - Profile resolution: same ID, added, serial match, chosen, skipped, refusal while unresolved.
  - Duplicate and conflicting UUIDs for rides and charges, including `.active` vs `.csv` and the same START clocks under another UUID.
  - Idempotent repeat before and after mapping; preview counts.
  - Two sources mapped to one destination; the no-scooter fresh-install case.
- `BackupTransactionTest`:
  - Crash or failure at each boundary: stage, build, snapshot, active, each move, DB swap, each namespace commit, live sync, committed, cleanup.
  - Failing rollback followed by recovery; repeated recovery.
  - v1 transaction recovery; low storage refused before mutation.
  - Cancel discards the stage; identity-protected rollback keeps an unrelated file.
- Existing `AppDataArchiveTest`, `RideArchiveTest` and `BackupExportCacheTest` are kept and updated where APIs move.

Emulator only. Every test admits only `ranchu`/`goldfish`/`sdk_gphone` and refuses existing app data, as the current fixtures do. Never on a real phone, and no scooter commands:

- `FullBackupJourneyValidation`: export v2 complete, pull to host, `pm clear`, push, then restore-check under Replace everything. It compares every durable preference value and file hash and reopens the profile, dashboard, ride, phone, charge and history readers. It also plants excluded keys, checks they are not restored, and adds a `legacy-v1` mode using the committed fixture.
- `AppDataBackupValidation`: real SQLite history merge (union, phone wins, remap, integrity, size limit), real preference subset commit and post-commit failure rollback, Android crypto provider, and staged data deleted.
- `RideBackupValidation`, reworked: `.voltrides` import through the unified screen. It covers mapping, review, cancel and apply; reopen and relearn range from three restored rides with no duplicate after a second import; and failed document-provider close with retry.
- `BackupUiValidation`: the entry in More, consent, category list and counts, Select all, password errors, preview lines, Replace-everything switch default, cancel at each step, repeated launches during the writer drain, refusal during recording, and terminal Close Volt. It also takes renders at narrow dark/default font and wide light/140% font.
- `DashboardTransferValidation`: JSON import through the unified flow, mapped to a chosen scooter.
- `BackupInventoryValidation` (new): after populating every feature, list `shared_prefs`, `files`, `no_backup`, `databases` and `cache`, and assert that every namespace, key and file is classified by `BackupRegistry`.
- `AppDataMaintenanceValidation`: unchanged drain and freeze guarantees for the unified entry.
- `DashboardTransferValidation` also keeps its direct DashboardConfig export/import checks for the Home layout tool.

## Acceptance mapping

| Acceptance item (handoff item 7) | Covered by |
|---|---|
| Full export → host copy → clear → restore, compare all durable state, reopen readers | FullBackupJourneyValidation (v2 and legacy-v1 modes) |
| Partial permutations leave unselected data identical | BackupArchiveTest (127 subsets); AppDataBackupValidation spot permutations with real prefs/SQLite |
| Existing `.voltbackup`, `.voltrides`, dashboard JSON | Legacy fixtures in BackupArchiveTest; RideBackupValidation; DashboardTransferValidation; FullBackupJourney legacy-v1 |
| Duplicate/conflicting UUIDs | BackupPlanTest; RideBackupValidation relearn check |
| New installation | FullBackupJourneyValidation; BackupPlanTest no-scooter case |
| Password errors, corruption, truncated/hostile archives | BackupArchiveTest |
| Low storage | BackupTransactionTest preflight |
| Failed document-provider close | RideBackupValidation / BackupUiValidation save retry |
| Cancellation | BackupTransactionTest; BackupUiValidation |
| Process death at transaction boundaries | BackupTransactionTest crash injection; existing interrupted-move emulator check |

## Decisions

Recorded answers to the open questions of the first draft:

1. Home → Edit → Layout keeps plain **Export/Import** of dashboard layout files as a sharing tool. Settings no longer have Dashboard backups. Restore also accepts layout files.
2. Rides → **Ride backups** stays as a shortcut into Back up & restore with Rides preselected.
3. `.voltrides` export is retired: new ride backups are always encrypted `.voltbackup` v2 files. `.voltrides` import stays.
4. `text_scale`, `background_connection`, `keep_screen_awake` and `poll_interval_ms` stay in App preferences; there is no separate "This phone" group.
5. `map_basemap_enabled` and `map_basemap_explained` restore as the user's recorded choice. The preview shows "Online map: on" whenever the restored result has it on.
6. A merged history that would exceed the 24 MiB limit is pruned with TelemetryHistory's own retention rules (`applyRetention`) before it replaces history; if it is still too large the restore stops before any change.
7. No per-scooter export filter.
8. **Superseded by the backup discovery follow-up:** the plaintext owner-only export is retired from profile and onboarding screens. Both now use the unified encrypted backup. Legacy owner-file import remains supported.
9. `control_evidence` keeps transferring, assigned to Scooter preferences. It never authorizes a control.
10. A complete backup onto a phone with scooters, rides, charges or history defaults to Merge; onto an empty phone the Replace everything switch starts on.

Registered ahead of sibling work: the Boolean `ride_auto_stop` (App preferences). Profile validation tolerates additive fields in `profiles.items` (for example `pid_advertised`, `pid_advertised_wall`, `name_default` and `fw_*`), type-checks only the known ones, and splices profile items as their exact text so those fields survive a merge. The registry test fails on unassigned keys found in the source and ignores registered keys this source never writes.

## Implementation notes

Classes: `BackupRegistry` (table and types), `BackupJson`/`BackupProfiles` (bounded JSON reading and profile splicing without Android), `BackupArchive` (export, staging of all four formats, destinations and the read-only plan), `BackupTransaction` (build, preflight, snapshot, apply, rollback, recovery), `AppDataBackup` (Android preferences, SQLite merge and validation, layout validation) and `BackupActivity` (the one screen). `RideBackupActivity` is removed. `DataMaintenance.ensureRecovered` runs `AppDataBackup.recover` (now `BackupTransaction.recover`, which hands a version 1 transaction to the version 1 rollback), then `RideBackup.recover`, then cache cleanup.

Differences from the first draft:

- The manifest's scooter table lists every local scooter whenever a scooter-linked category is selected, not only referenced ones, so export reads no journal. It holds names and serials only, inside the encryption.
- Unreadable ride or charge files found in a backup are kept for Replace everything (1.3.1 copied them byte for byte) but are never merged; the preview counts them as skipped.
- Each journal of a ride group is validated; an invalid sidecar skips the whole ride in a merge.
- Staged charge journals take their start time as modification time, because charge pruning keeps the newest 20 by that time. A merge adds only as many charges as fit under that limit (the newest backup charges first), so the next start prunes nothing that was there; the review counts the rest as skipped.
- Home's no-scooter layout (`dashboard_*_unconfigured*`, written after **Skip setup**) is not a scooter: category counts, destinations and merges ignore it, and only Replace everything restores it.
- The review names where each mapped scooter's data goes (“Old UT5” → Fold UT5), as the 1.3.x ride restore confirmed.
- Merge history: rows are added with `INSERT OR IGNORE`, so this phone's row wins on every unique key; `latest` keeps this phone's snapshot, else the newest backup snapshot by its `wall`. Recording session numbers are copied as they are.
- If a backup scooter is set to **Don't restore** while Scooters is selected, that scooter is not added either.
- `onboarding` becomes `{onboarding_done: true}` when Scooters is merged and the result has a configured scooter, and in Replace everything when the backup's setup was finished.
- A low-storage refusal or a "Saved data changed" refusal keeps the reviewed file staged, so the user can retry without choosing it again. "Saved data changed" reviews again with the same choices (or returns to the category list if they no longer apply); the stale approval is never retried.

## Emulator journeys

Disposable emulators only (`ranchu`/`goldfish`/`sdk_gphone`), Bluetooth off, never a physical phone, no scooter commands. Build with `bash build.sh` and `bash android-tests/build.sh`, then:

1. `python3 android-tests/run-backups.py --adb … --serial emulator-NNNN --app dist/navee-offline-lock.apk --runner .local/build/parked-validation/parked-validation.apk --out <dir>` runs, each after `pm clear`:
   - `FullBackupJourneyValidation` `export` → pull `backup-journey.voltbackup` to the host → `pm clear` → push → `restore-check` (complete v2, Replace everything, every durable preference and file hash, excluded keys absent, real profile/dashboard/ride/phone/charge/history readers);
   - `FullBackupJourneyValidation` `legacy-v1` with `tests/fixtures/legacy/full-v1.voltbackup` pushed as `legacy-v1.voltbackup`;
   - `AppDataBackupValidation` (Android crypto, real SQLite validation and history merge, preference subset commit, post-commit failure rollback, staging removal);
   - `DashboardTransferValidation` (layout file through the unified flow to a chosen scooter, plus the Home layout tool);
   - `BackupUiValidation` at 344×882 dp dark/default font and 725×604 dp light/140% font, with `backup-categories.png` renders;
   - `AppDataMaintenanceValidation` (writer drain and freeze);
   - `BackupInventoryValidation` last, read-only, against the storage the journeys left.
2. `python3 android-tests/run-ride-backups.py …` (default cases 344×882 dark/1 and 725×604 light/1.4): `.voltrides` import through Back up & restore with Rides preselected, destination choice, review and apply, no duplicate after a second import, failed provider close and retry, interrupted move recovery, then the cold `check-reopened-range` process relearning range from three restored rides.

3. `python3 android-tests/run-backup-scenario.py … --render` seeds a synthetic source phone (two scooters, 3 rides, 15 charges), writes complete, single-category, truncated and corrupted `.voltbackup` files, seeds a target phone (another scooter, 12 charges) and renders the restore screens at both default cases: a refused damaged file with **Choose another file**, the category list (Merge by default), the two-scooter destination step and the merge review with the 20-charge limit line. FLAG_SECURE keeps these screens out of screenshots, so the renders are drawn in-process. Without `--render` it leaves the files in `Download/volt-scenario/` for driving the system picker by hand, and `--inspect` reports scooters, rides, charges and settings afterwards.

All three runners passed on emulator-5560 and emulator-5558: every `run-backups.py` journey, both `run-ride-backups.py` cases plus the cold reopen, and `run-backup-scenario.py --render` at both sizes. Inspect the mapping, preview, category and restore renders. Physical-phone restore has not been exercised: real owner data, cloud document providers in the system picker, and the screens seen directly (FLAG_SECURE) still need a phone. Power loss during a restore is not simulated.

## Backup discovery follow-up

The More index now exposes Back up & restore directly. New backups select every available category; a live scope label and `volt-full` / `volt-selected` filename distinguish complete copies from subsets. The old private owner-only export is removed from My scooters. The forget dialog keeps Save backup first, which now opens the same full-default encrypted backup flow.

Restore also accepts strictly validated `navee-offline-owner-v1` JSON through the existing staged, reviewed, transactional merge. These files are marked `owner`, contain only Scooters & setup keys, and are never complete or eligible for Replace everything. Both category preview and final approval explicitly say that rides, history, dashboards and settings are absent. Ordinary pairing-file imports retain their setup role and use the same clear access-only wording. No backup format or encryption change is required.

Owner identities belong to NAVEE accounts, so one identity and role can apply to several scooters. When an owner setup file has no serial or Bluetooth address and an existing profile uses that identity and role, Restore asks **Which scooter is this setup for?** The user chooses a named existing scooter, **Add another scooter**, or **Don't restore**; nothing is preselected and Review stays unavailable until a choice is made. Choosing an existing scooter leaves its credentials, name, current selection and all saved data unchanged. Choosing another creates a separate profile without moving any recordings. Repeating an import and explicitly choosing the existing scooter is idempotent.

Identifier-less owner files receive a fresh staged profile ID each time, so an account-derived UUID from an older import cannot silently conflate two scooters. Hardware-identified owner files and complete/category backups retain their existing matching rules. Core regressions cover two known same-account scooters, existing and new destinations, unlocated profiles, older derived IDs, repeated imports and strict role/identity/hardware boundaries. The native backup journeys exercise both explicit reuse and adding a second same-account scooter through the real picker and approval flow.
