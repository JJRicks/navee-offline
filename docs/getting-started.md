# Get started with Volt

[Download the APK](https://github.com/JJRicks/navee-offline/releases/latest) · [Owner setup website (testing preview)](https://pairscooter.jjricks.com/) · [User guide](user-guide.md)

## Before you install

| You need | Supported setup |
| --- | --- |
| Phone | Android 8.0 or newer, with Bluetooth LE |
| Scooter | Already activated, owner-bound NAVEE scooter. **US NAVEE UT5 MAX (model/PID 2538)**: full support. Other NAVEE scooters: lock and readings, with other controls only where evidence maps them |
| NAVEE app | Signed into your account and able to connect to your scooter |

Only the US UT5 MAX has been physically verified with Volt. On other models, controls mapped from the official app are not tested by Volt, and golf carts, e-bikes and other non-scooter products get no scooter commands. See [Other NAVEE models](user-guide.md#other-navee-models). Firmware changes can affect both setup and available controls. Volt does not activate a new scooter, bypass ownership, transfer cloud ownership or recover a NAVEE account.

Volt is an independent community project, not affiliated with, endorsed by, sponsored by, or supported by NAVEE or its app vendor. Product names identify compatible hardware only.

## Install and connect

1. Download the APK attached to [this project's latest release](https://github.com/JJRicks/navee-offline/releases/latest), then open it on your phone. If Android asks, allow your browser or file manager to install this APK.
2. Keep the scooter powered on, safely parked and within Bluetooth range. Check that NAVEE can connect to it.
3. Open Volt and follow onboarding. Choose an owner-setup method below, or restore an existing setup file or full backup.
4. Allow the requested Bluetooth permissions. Android 12+ uses **Nearby devices**; Android 8–11 requires precise **Location** permission for Bluetooth discovery. Optional GPS ride recording is separate.
5. Close NAVEE so it releases the Bluetooth connection, then connect in Volt.

Everyday use needs no computer, ADB or Shizuku. Volt can reconnect to the last scooter on launch; opening the app never automatically locks or unlocks it.

## Choose an owner-setup method

The one-time capture retrieves your scooter's Bluetooth owner identity from your already signed-in NAVEE app. **You never enter a NAVEE or Google password into Volt or the setup website.** Capture requires broad Android debugging access with explicit consent, processes the identity locally, and temporarily restarts NAVEE with logging enabled. Read the selected helper's cleanup and recovery instructions before starting. Do not run multiple capture helpers together.

| Method | What you need | Status and instructions |
| --- | --- | --- |
| **Computer ADB** | Computer, USB data cable, Android Platform-Tools and Python 3 | Established capture route. [Full instructions](../SETUP.txt). |
| **Chrome/USB** | Desktop Chrome or Edge and a USB data cable; no installed ADB or Python | **Testing preview.** Open [pairscooter.jjricks.com](https://pairscooter.jjricks.com/). [Consent, cleanup and recovery](../setup/web/README.md). |
| **Shizuku on this phone** | Android 11+ and shell-backed Shizuku 13+ | Real-device validation is limited. Choose **Use Shizuku on this phone** in onboarding. [Setup and recovery](../setup/SHIZUKU.md). |
| **Existing setup or full backup** | Your own previously exported file | Choose the corresponding import/restore option in onboarding. [Backup guide](user-guide.md#back-up-and-restore). |

The browser and Shizuku helpers currently support only the main personal Android user, not work profiles or secondary users; they also reject NAVEE installations in another profile. Controlled phone checks of these two helpers exercised timeout and cleanup with the scooter out of range. A tester later reported successful onboarding without identifying the helper used, so successful real-scooter capture has not been confirmed separately for each new path.

### Computer ADB quick start

Computer ADB is the established capture route. Install Android Platform-Tools and Python 3, enable USB debugging and approve your own computer on the phone, then run this from an extracted source checkout:

```sh
python3 setup/capture-owner.py --push
```

Connect to the parked scooter in NAVEE when prompted. After capture and cleanup, the helper saves a private setup file on the computer and in the phone's Downloads. Import it in Volt. Keep a private backup; delete the Downloads copy once no longer needed. The [full instructions](../SETUP.txt) cover multiple phones, filenames and troubleshooting. Complete any interrupted helper's cleanup before changing methods.

After setup, revoke temporary debugging access and disable USB/wireless debugging when no longer needed. For Shizuku, revoke Volt under **Authorized applications**. Keep owner setup files private: their identity can control your scooter.

## Before your first ride

- **Keep a fallback:** configure and test the scooter's handlebar PIN while parked. Lock duration is not a promise of automatic unlocking. [Lock and PIN details](recording-reference.md#security-and-the-handlebar-fallback).
- **Check freshness:** disconnected readings are last-seen values, and controls require a fresh authenticated connection. The [user guide](user-guide.md#connect-and-use-the-lock) explains connection behavior.
- **Choose maps deliberately:** route geometry is drawn locally. Online basemap tiles start off unless you previously chose otherwise; enabling **Basemap** below a ride map explains that the provider receives your IP address and viewed area. The choice is remembered. [Privacy and permissions](../PRIVACY.md).
- **Treat estimates as estimates:** battery health is a scooter report, not an independent capacity test. Range, charging time, experimental usable energy, hill grade and phone acceleration depend on available measurements and conditions. [Reading and recording limits](recording-reference.md).

## Updates and backups

Install newer APKs from [project Releases](https://github.com/JJRicks/navee-offline/releases) over the current app. Updates signed with the same project key retain app data. Volt was previously named **Navee Offline**; the repository name, Android package and data formats remain compatible.

Before clearing Android app storage, uninstalling, or switching to a differently signed build, save what you need:

- **Dashboard backup:** **More → App preferences → Dashboard backups** exports layouts and widget options without owner keys or telemetry. Custom titles and notes are included.
- **Full backup:** Developer options provide password-encrypted `.voltbackup` files containing profiles, private owner setup, preferences, history and recordings. Save outside Volt, keep the file private, and retain its password; it cannot be recovered.

Restore does not grant Android permissions, Bluetooth pairing or Shizuku authorization; grant them again as needed. Read [Back up and restore](user-guide.md#back-up-and-restore) before using a full backup, especially before clearing storage.

For everyday controls, recording and dashboard customization, continue to the [user guide](user-guide.md). To report a problem, see [Contributing](../CONTRIBUTING.md); use [Security](../SECURITY.md) for exposed credentials or vulnerabilities.
