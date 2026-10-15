# Privacy and local data

Volt is an independent project, **not affiliated with or endorsed by NAVEE**.
This describes Volt and its included setup helpers, not the official NAVEE app
or external services you choose to use.

## What stays on your phone

Bluetooth controls, diagnostics, and recording do not use a Volt account or
server. The app has no analytics SDK, advertising SDK, automatic crash uploader,
or automatic ride upload. It saves the following in Android's private app
storage:

| Data | Purpose |
| --- | --- |
| Owner identity, role, scooter address/serial, profile name | Reconnect to your scooter locally |
| Preferences, widget layouts, saved slots, notes, favorites | Restore your chosen interface |
| Last-seen parameters and connection/command results | Show stale values and explain connection state |
| Connected history | Draw local historical charts and estimates |
| Ride readings and optional location, pressure, and motion samples | Analyze a ride you record |
| Charge samples and a manually selected charger label | Review charging and estimate remaining time |

Connected history is enabled by default. It does not start GPS or phone sensors.
It has a shared **50 MiB** budget: detailed samples for up to seven days, minute
summaries for up to 30 days, and daily summaries for up to two years. Older detail
can be removed sooner to meet the budget. Turning it off retains last-seen values;
clearing history also clears the selected last-seen cache. Controls are in
**More → Data & history**.

Ride files are separate from connected history and remain until you delete them
or clear app storage. Charge recording retains up to 20 sessions across profiles
and charger labels; older sessions are pruned to stay within that limit. Phone
location and motion sensors run only during ride recording when enabled and
permitted. A foreground notification supports background recording; Android or
device restrictions can still interrupt it.

Android cloud backup and device-transfer extraction are disabled in the manifest
and backup rules. Local profiles, including owner credentials, are not separately
encrypted by Volt. They rely on Android's app sandbox and device protection.

## Android permissions

| Permission | Why Volt uses it |
| --- | --- |
| Nearby devices (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`) on Android 12+ | Scan for and connect to the scooter over Bluetooth LE. Scanning is declared `neverForLocation`; Volt does not derive location from Bluetooth scans. |
| Legacy Bluetooth (`BLUETOOTH`, `BLUETOOTH_ADMIN`) and precise Location (`ACCESS_FINE_LOCATION`) on Android 8–11 | These Android versions require precise Location permission for Volt's Bluetooth scan/connect path, even when ride GPS is off. Grant it to connect; granting it alone does not start location recording. |
| Location (`ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`) for optional ride GPS | Record a route only during a ride with phone GPS enabled. Approximate access is accepted, but precise access gives a more useful route. Denying ride GPS still allows scooter-only recording; Android 8–11 separately needs precise Location for Bluetooth as above. |
| Notifications (`POST_NOTIFICATIONS` on Android 13+) | Show connection and recording status, including the recording Stop action. Allow notifications to keep these controls visible. |
| Foreground service (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `FOREGROUND_SERVICE_LOCATION`) | Keep an active Bluetooth connection or recording running outside the app. The location service type is used when permitted ride GPS is active. Volt does not request `ACCESS_BACKGROUND_LOCATION`; start a ride while the app is visible. |
| Vibration (`VIBRATE`) | Optional button, switch and connection haptics, respecting app and system settings. |
| Wake lock (`WAKE_LOCK`) | A renewed, time-bounded CPU wake lock supports active ride/charge recording and pending phone-data writes with the screen off. It does not keep the display on. |
| Internet (`INTERNET`) | Fetch map tiles when Basemap is enabled. Bluetooth control and recording do not need this access. External links open in your browser. |
| Shizuku API (`moe.shizuku.manager.permission.API_V23`) | Optional on-phone owner setup through Shizuku. Shizuku authorization grants broad Android debugging access; capture requires a separate consent step. Everyday Bluetooth use does not need it. |

Android only prompts for runtime permissions where applicable. Foreground-service,
internet, vibration and wake-lock permissions are declared normal permissions,
not separate approval dialogs. Manage runtime permissions in Android's app settings;
manage Shizuku access in Shizuku. Device power restrictions and force stop can
still interrupt background work.

## When network access is used

**Basemap is optional and starts off if you have not saved a choice.** Existing
explicit choices are preserved. The **Basemap** switch directly below a ride map,
including the full-screen map, shares its remembered state with
**App preferences → Map settings**. The first new enable explains that the
provider receives your IP address and viewed map area; choose **Enable basemap** or
**Cancel**. Cancel keeps tiles off. Once you approve, that acknowledgement is
remembered too. Full app backups include both the choice and acknowledgement.

When enabled, the app requests visible map tiles from
USGS Topo by default, OpenStreetMap if selected, or your chosen HTTPS provider.
Requests reveal your network address, the requested map area and zoom, and an
app/version User-Agent to that provider. A visible area can reveal where you
rode even though Volt does not upload a route file. Tiles are cached locally;
there is no bulk map download. GPS and the locally drawn route work without
tiles or an internet connection.

Opening setup, help, attribution, or other external links uses your browser.
The destination and its host may receive normal web request data such as your
IP address and browser details. The pairing website has no analytics in its
source and no capture upload endpoint, but this is not a promise that hosting
access or error logs are disabled. The official NAVEE app's own networking and
logging are governed separately.

## Owner setup helpers

USB, Shizuku, and desktop capture briefly enable NAVEE logging and restart its
app after your approval. They parse fresh authentication messages locally to
obtain the Bluetooth owner identity. They do not ask for or retrieve your NAVEE
password. NAVEE may emit other verbose logs during that period; the helpers do
not clear the phone's global log buffer.

The browser helper keeps the temporary ADB key and captured identity in memory.
Its persistent recovery journal contains a phone serial, random session token,
capture stage, and original logging setting—not the owner identity. Verified
cleanup removes the journal. Keep browser site data intact if cleanup is
unfinished. Shizuku also keeps recovery metadata on the phone while a capture
needs cleanup. See the [setup guide](setup/web/README.md) for interruption and
permission-revocation details.

Saving to phone Downloads, downloading a computer copy, and importing into Volt
are explicit actions. Files in Downloads or a shared/cloud folder follow that
location's permissions and sync behavior. Delete temporary copies after import
if you do not want them retained there.

## Exports are different

| Export | Sensitive content |
| --- | --- |
| Private owner JSON | Plaintext Bluetooth credential, profile name, scooter address and serial |
| Full `.voltbackup` | Password-encrypted profiles/credentials, settings, dashboards, retained histories, rides, and charge recordings |
| Dashboard JSON | Layouts, widget options, notes and custom titles; no owner credentials or recorded readings |
| Ride CSV/JSON/GPX | Timestamps and measurements; phone/joined/route exports can include precise locations |
| Charge JSON | Electrical samples, times, gaps and charger label; excludes owner credentials, names, addresses, PINs and locations |
| Ordinary diagnostics | Readings and diagnostic payloads, potentially device serials; excludes owner identity and PIN responses |

Exports only go to a destination you choose through the file picker or helper.
A destination may itself sync to a cloud service. The full backup password is
not saved or recoverable. Use a strong password and keep it separately; see
[the backup format](docs/app-data-backup.md) for technical details and exclusions.

Review files and screenshots before sharing. A redacted diagnostic file can
still reveal device identifiers, usage times, or other personal context.

## Removing data

Use the history controls, ride deletion, charge-history clearing, or profile
removal for individual categories. Removing a profile does not delete its saved
rides. Android **Clear storage** or uninstall removes Volt's private data; it
does not delete exports, browser recovery data, or copies synced elsewhere.
Finish setup recovery before clearing app or browser storage. Remove exported
copies separately, and revoke USB/Shizuku authorizations if no longer needed.
