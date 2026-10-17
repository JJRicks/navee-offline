# Legacy backup fixtures

Synthetic files written by Volt 1.3.1 code (`AppDataArchive.write`, `RideArchive.write` and the `volt-dashboard-layouts` v1 schema). They contain no real rides, routes, credentials or phone data: the scooter, owner identity `ABCDEF012345`, MAC, serial, ride and readings are invented.

| File | Format | Notes |
|---|---|---|
| `full-v1.voltbackup` | Encrypted full backup, format 1, app version 36 | Password `synthetic fixture password`. One profile `8c1f3a52-0d7e-4b8e-9a41-5b2f6c7d8e90`, one ride with phone and motion sidecars, one charge, a real SQLite readings history, durable, excluded and unassigned preference keys. |
| `rides-v1.voltrides` | Ride backup, format 1 | The same ride and sidecars. |
| `dashboards-v1.json` | Dashboard layouts, format 1 | One widget, slot 1 saved, slots 2 and 3 empty. |

Do not regenerate them with newer code: they pin what older versions wrote.
