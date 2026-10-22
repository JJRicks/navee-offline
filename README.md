<h1 align="center">⚡ Volt</h1>
<p align="center"><strong>Your scooter. Your connection.</strong></p>
<p align="center">
  <a href="https://github.com/JJRicks/navee-offline/releases/latest"><strong>Download for Android</strong></a>
  &nbsp; · &nbsp;
  <a href="docs/getting-started.md">Setup guide</a>
  &nbsp; · &nbsp;
  <a href="docs/user-guide.md">User guide</a>
  &nbsp; · &nbsp;
  <a href="CHANGELOG.md">What's new</a>
</p>

An Android companion for **NAVEE scooters**. Connect over Bluetooth, control your scooter, make the dashboard yours, and explore your rides and battery data. Everyday use works without a Volt account or server.

> [!NOTE]
> **Independent community project.** Volt is not affiliated with, endorsed by, sponsored by, or supported by NAVEE or its app vendor. Product names identify compatible hardware only.

## Make it yours

| Feature | What you get |
| :--- | :--- |
| 🔒 **Local controls** | Lock/unlock and supported riding, lighting and charging settings. |
| 🧩 **Your dashboard** | Movable, resizable widgets, quick controls and saved layouts for folded and unfolded screens. |
| 📊 **Live insights** | Searchable readings, charts, favourites and last-seen values. |
| 🗺️ **Ride recordings** | Optional GPS routes, phone sensors, energy readings and complete ride backups. |
| 🔋 **Battery insights** | Optional range learned from your rides, charging estimates and experimental usable ride energy. |

## Install & connect

**You'll need:** Android **8.0+** with Bluetooth LE and an already activated, owner-bound **NAVEE scooter**. Available controls depend on the model; the US UT5 MAX is physically verified, while other models use mappings from the official app. [Compatibility details →](docs/user-guide.md#other-navee-models)

1. **Install Volt.** Download the `.apk` from [Releases](https://github.com/JJRicks/navee-offline/releases/latest), open it on your phone and allow installation when Android asks.
2. **Pair once.** Keep your powered-on scooter nearby and safely parked, and confirm it connects in NAVEE. Follow Volt's onboarding and the [setup guide](docs/getting-started.md): computer ADB is the established route; Chrome/USB is a testing preview, and on-phone Shizuku has limited validation. You can also import an existing setup or full backup.
3. **Connect.** Grant the requested Bluetooth permissions (Location on Android 8–11), close NAVEE to release its connection, and connect in Volt.

First-time capture needs your working NAVEE account and Android debugging access, with your explicit approval. You never enter your NAVEE or Google password into Volt. **After setup, everyday use needs no computer, ADB or Shizuku.**

## Good to know

- **Local by default.** Controls and recording work offline. **Basemap** starts off; the switch below a ride map explains provider access before enabling online tiles and remembers your choice. Existing saved choices are kept. [Privacy →](PRIVACY.md)
- **Keep a backup.** [Back up & restore](docs/user-guide.md#back-up-and-restore) saves an encrypted copy of what you choose, from rides alone to setup, settings, rides and history. Export before clearing app data, and keep backup files private.
- **Estimates have limits.** Range, charging time, usable capacity and phone motion are estimates; reported battery health is not an independent capacity test. [Understanding readings →](docs/recording-reference.md)

## Go further

| Looking for… | Start here |
| :--- | :--- |
| Pairing options or setup help | [Getting started](docs/getting-started.md) |
| Dashboard, settings, rides and backups | [User guide](docs/user-guide.md) |
| Supported controls and test evidence | [Feature availability](FEATURES.txt) · [Verification history](VERIFICATION.txt) |
| A bug report or an idea | [Issues](https://github.com/JJRicks/navee-offline/issues) · [Contributing](CONTRIBUTING.md) |
| A source build | [Build guide](docs/building.md) · [Development notes](docs/development-notes.md) |
| A security concern | [Security reporting](SECURITY.md) |

---

Previously **Navee Offline**; existing installations and data remain compatible. Built with Java and native Android views.

[MIT-licensed source](LICENSE) · [Third-party and image notices](NOTICE) · [Public-release preparation](docs/public-release.md).

The manufacturer-sourced scooter photo is a temporary placeholder; redistribution permission has not been established, so it must be replaced or cleared before public distribution (see [NOTICE](NOTICE)). Protocol research builds on [pepperonas/navee-st3-pro](https://github.com/pepperonas/navee-st3-pro).
