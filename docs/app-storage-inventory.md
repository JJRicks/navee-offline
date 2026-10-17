# App storage inventory

Every durable location Volt 1.3.1 writes, what it holds, which scooter profile it belongs to, and how a backup treats it. This is the source list for the backup category registry in [unified backups](unified-backup.md). When code adds a preference key, namespace, file or folder, add it here and to the registry in the same change.

Paths are relative to the app's private data directory (`/data/data/local.navee.offlinelock` or its per-user equivalent). `<pid>` is a scooter profile ID: the random UUID string stored in `profiles.items[].id`. Ride, phone and charge files are named by a recording UUID, written `<uuid>`.

Classes used below:

- **Durable**: user state worth restoring after **Clear storage**.
- **Device**: describes this phone, its window or boot session. It means nothing on another installation.
- **Pending**: a half-finished operation owned by this installation (cleanup journals, staged imports/exports, restore transactions). Never transferred or overwritten.
- **Cache**: disposable and rebuilt or re-downloaded on demand.
- **Diagnostic**: device-local troubleshooting, deliberately kept out of backups.

## Preferences (`shared_prefs/<name>.xml`)

Seven namespaces exist. Volt 1.3.1's full backup copies the first five whole (`AppDataArchive.PREFERENCES`); `pending_documents` and `owner_capture_cleanup` are never read by it.

### `app_settings`

The main settings file. It mixes global options, per-scooter entries (key ends in `<pid>`) and short-lived UI state, so it must be classified key by key.

| Key | Type | Profile | Class | Holds / written by |
|---|---|---|---|---|
| `theme` | String | global | Durable | `dark`/`system`/`light`. MoreScreen; read by every activity. |
| `units` | String | global | Durable | `mi`/`km`/`dual`. |
| `temperature_unit` | String | global | Durable | `c`/`f`. |
| `time_format` | String | global | Durable | `system`/`12h`/`24h`. DisplayTime. |
| `text_scale` | Float | global | Durable (device-sensitive) | In-app text size, 0.5–3. Users may want different values on a fold's two screens; it is still one value today. |
| `stats_density` | String | global | Durable | Live Stats `comfortable`/`dense`. |
| `haptics_enabled` | Boolean | global | Durable | `Haptics.PREFERENCE`. |
| `auto_connect` | Boolean | global | Durable | Connect automatically. |
| `background_connection` | Boolean | global | Durable (device-sensitive) | Stay connected in background. Battery behaviour differs between phones. |
| `keep_screen_awake` | Boolean | global | Durable | While recording. |
| `poll_interval_ms` | Integer | global | Durable | Telemetry refresh. |
| `history_enabled` | Boolean | global | Durable | Save connected history. |
| `phone_gps`, `phone_motion` | Boolean | global | Durable | Ride sidecar recording choices. |
| `phone_placement` | String | global | Durable | `pocket`/`mounted`. |
| `map_basemap_enabled` | Boolean | global | Durable | Online basemap opt-in (privacy choice). MapPreferences. |
| `map_basemap_explained` | Boolean | global | Durable | First-enable privacy explanation acknowledged. |
| `map_tile_url`, `map_tile_attribution`, `map_tile_attribution_url` | String | global | Durable | Map provider. MapSource. |
| `stats_favourites`, `stats_chart_favourites` | String set | global | Durable | Favourite readings/charts. |
| `stats_favourite_order` | String | global | Durable | Favourite order. StatsFavouriteOrder. |
| `stats_chart_window` | Integer | global | Durable | Chart time window. |
| `stats_group_<group>`, `stats_chart_group_<group>` | Boolean | global | Durable | Expanded reading/chart categories. |
| `ride_chart_key` | String | global | Durable | Ride chart metric. |
| `ride_chart_series`, `charge_chart_series` | String set | global | Durable | Chart series choices. |
| `last_ride_label` | String | global | Durable | Default name for the next ride. |
| `developer_options` | Boolean | global | Durable | Seven-tap unlock. |
| `range_learned_intro_seen` | Boolean | global | Durable | Learned-range introduction shown. |
| `dashboard_columns` | Integer | global | Durable | Column choice shared by every scooter's dashboard. DashboardConfig. |
| `dashboard_v1_<pid>` | String (JSON) | per scooter | Durable | Active dashboard: widgets, parameters, notes and a cell for every column count (folded and unfolded layouts live inside each widget). |
| `dashboard_slot_v1_<pid>_<0..2>` | String (JSON) | per scooter | Durable | Saved layout slots. |
| `dashboard_empty_example_v1_<pid>` | Boolean | per scooter | Durable | Slot 3 intentionally empty; stops the example being reseeded. |
| `dashboard_restore_undo_v1_<pid>` | String (JSON) | per scooter | Pending | Undo for a just-restored slot. Short-lived. |
| `range_source_<pid>` | String | per scooter | Durable | `scooter`/`learned` range estimate. ScooterService. |
| `range_follow_mode_<pid>` | Boolean | per scooter | Durable | Range follows drive mode. |
| `charge_charger_<pid>` | String | per scooter | Durable | Manual charger choice (`STOCK`/`HIGH_SPEED`). |
| `ride_comparison` | String set | global (ride UUIDs) | Durable | Rides selected for comparison. |
| `ride_selected` | String | global (ride UUID) | Durable | Last opened ride. |
| `notification_prompted` | Boolean | global | Device | Volt already asked for Android's notification permission on this installation. |
| `owner_import_applied` | String | global | Pending | Import ID of the last applied `pending-owner-import.json`; protects that file from being applied twice. |
| `last_page` | String | global | Device | Last tab. |
| `more_section`, `scooter_section`, `stats_section` | String | global | Device | Open sections. |
| `more_wide_section_scroll`, `more_wide_menu_scroll` | String / Integer | global | Device | Wide-window scroll positions; depend on window size. |
| `more_away_elapsed`, `stats_away_elapsed` | Long | global | Device | `SystemClock.elapsedRealtime()` when the tab was left. Meaningless after a reboot or on another phone. |
| `more_away_boot`, `stats_away_boot` | Integer | global | Device | Boot count paired with the above. |
| `stats_query`, `stats_chart_query` | String | global | Device | Search text. |
| `dashboard_editing` | Boolean | global | Device | Home edit mode is open. |

No fold or window posture is persisted. `FoldMonitor` reads the live window state, and per-width arrangements are stored inside the dashboard JSON, so they travel with Dashboards.

### `profiles`

| Key | Type | Class | Holds |
|---|---|---|---|
| `items` | String (JSON array) | Durable, **secret** | Every profile: `id`, `name`, `identity` (12-hex owner credential), `role`, `mac`, `serial`, `lastConnected`. ScooterProfiles. |
| `active` | String | Durable | Selected profile ID. |
| `legacy_migrated` | Boolean | Durable | The legacy `scooter` setup was migrated, or deliberately not. Stops a second migration creating a duplicate profile. |

### `scooter` (legacy, pre-profiles)

`identity` (**secret**), `role`, `mac`, `serial`, `last_connected_mac`. Read only by the one-time migration in `ScooterProfiles` and cleared when its profile is forgotten. Durable for exact restores; never merged.

### `control_evidence`

One String per `<pid>`: JSON of the last command results. Since Volt 1.1.0 (`c7b695b`) nothing writes or reads it. Existing installations keep a dormant copy, which the 1.3.1 full backup transfers. It never authorizes a control.

### `onboarding` (`OnboardingScreen.PREFS`)

| Key | Class | Holds |
|---|---|---|
| `onboarding_done` | Durable | Setup finished. |
| `rerun`, `step`, `path`, `profile_id`, `created_profile_id`, `pending_name`, `import_failed`, `nearby_asked`, `connect_requested`, `connect_tried`, `tick_<n>` | Pending | Wizard position and choices for a setup in progress on this installation. `nearby_asked` relates to an Android permission prompt. |

### `pending_documents` (Pending)

`path`, `name`, `mime`, `ready`, `picker`: one export waiting for the document picker (MainActivity `prepareSave`). Its file is `files/pending-export-<ms>`.

### `owner_capture_cleanup` (Pending)

`pending`, `token`, `original`: the Shizuku owner-capture cleanup journal (ShizukuCaptureClient). It records NAVEE logging state that must be restored on this phone. Never exported, cleared or replaced.

## Files

| Path | Class | Profile | Holds / notes |
|---|---|---|---|
| `files/rides/<uuid>.csv` | Durable | START record's profile field | Raw scooter ride journal: samples, names, notes, clocks, gaps, current-direction events. RideStore. |
| `files/rides/<uuid>.csv.repair` | Pending | — | Temporary tail repair. Skipped by backups. |
| `files/rides/<uuid>.csv.mapping` | Pending | — | Temporary profile reassignment; normally only in restore staging. |
| `files/phone-rides/<uuid>-phone.csv` | Durable | ride's | Phone GPS/pressure/telemetry sidecar. PhoneRideStore. |
| `files/phone-rides/<uuid>-motion.csv` | Durable | ride's | Raw motion sidecar. |
| `files/charge-recordings/<uuid>.csv` | Durable | header `charge-v2,<pid>,…` | Complete or interrupted charge journal, including saved estimate samples. ChargeSessionStore. |
| `files/charge-recordings/<uuid>.active` | Durable | header | Unfinished charge; startup recovery appends `end,INTERRUPTED` and renames it to `.csv`. |
| `files/pending-owner-import.json`, `.tmp` | Pending | — | Normalized owner setup waiting to be applied. |
| `files/pending-export-<ms>` | Pending | — | Export waiting for the save picker. |
| `databases/telemetry-history.db` | Durable | rows keyed by `profiles.name` = `<pid>` | SQLite v1: `latest` (last-seen readings per profile), `profiles`, `keys`, `detail`, `minute`, `daily` rollups. Rollups outlive pruned detail, so they are data, not a recomputable cache. Delete-journal mode, 24 MiB maximum. TelemetryHistory. |
| `databases/telemetry-history.db-journal` | Pending | — | Must be absent or empty when closed. |
| `no_backup/connection-events` (+ `.tmp`) | Diagnostic | mixed | Last 200 connection phases/reasons, 128 KiB cap. ConnectionEventLog. |
| `no_backup/app-data-transfer/` | Pending | — | Full-restore stages, rollback snapshot and state marker. |
| `no_backup/ride-transfer/` | Pending | — | Ride-restore stages and transaction. |
| `cache/map-tiles/<sha256>/…` | Cache | — | Downloaded basemap tiles, `.meta`, `.part`, `network.meta`. MapTiles. |
| `cache/charge-exports/charges-*.json` | Cache | one scooter | Developer charge export; removed after 24 h. |
| `cache/ride-<uuid>.csv` | Cache | — | Ride sensor CSV export. |
| `cache/volt-encrypted-*.voltbackup`, `cache/volt-rides-*.voltrides` | Cache | — | Prepared backup waiting for a destination; orphans removed at startup (BackupExportCache). |
| `/data/local/tmp/volt-owner-capture.lock/` | outside app storage | — | Shell-owned Shizuku capture lock. Not app data; never touched by backups. |

Volt does not use external app storage, other databases or other private folders. Instrumentation tests use `getExternalFilesDir()` for disposable fixtures only.

## Not stored on disk

- Learned range, battery capacity and charge-curve results are computed in memory from rides, history and charge journals (ScooterService caches, cleared on restart). A restore needs no derived-cache import: the next process recomputes them.
- Live readings, command results, connection feedback, simulation and fold posture are in memory only.
- Android owns permission grants, battery-optimization exemptions, notification settings, Bluetooth bonds, USB debugging and Shizuku authorization. No app file holds them.
- Physical scooter settings live in the scooter. History holds observations of them, not the settings.

Android cloud backup and device transfer are disabled (`allowBackup="false"`, `res/xml/data_extraction_rules.xml`).
