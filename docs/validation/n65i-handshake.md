# N65i handshake validation

## Behavior

An N65i stayed on **Checking owner identity** until Volt's 18-second connect timeout, on every attempt. A tester shared Volt's connection diagnostics and an Android Bluetooth HCI snoop capture, taken with the official app installed on the same phone.

The capture showed:
- **The owner check succeeds.** Volt connected, enabled notifications and sent the owner check with the same owner identity the official app sends. The scooter answered within 12 ms with challenge-free success, `55 aa 00 30 01 00 db fe fd`, the same reply the official app received, and started its periodic `0x90`/`0x92` reports.
- **Volt dropped the reply.** It sent nothing further and gave up at the timeout. The official app read the serial number and settings on the same link and worked.
- **Checksum:** all 134 N65i notifications in the capture sum every byte **except the leading 0x55**. The UT5 MAX includes it. Volt's decoder rejected each N65i reply as a checksum mismatch, silently. The phone's writes (Volt's and the official app's, 15 frames) use the standard sum, and the N65i accepts them.
- **Settings read:** the N65i's `0x70` reply carries ten data bytes after its status byte. Volt previously required at least 14 before it would bind and finish connecting. The lock byte sits at payload offset 2, where Volt expects it, and the serial reply matches Volt's serial format.

## Changes

- **Decoder:**
  - accepts either checksum style;
  - fixes the style per connection from the first valid reply and resets it on every connect and disconnect, so the two styles never mix within one connection;
  - keeps rejecting a frame that matches neither style. The two styles always differ by 0x55, so no frame matches both.
- **Settings reads** bind once the lock byte is present and reads 0 or 1. The UT5 MAX keeps its full 14-byte minimum. Every field reader already checks its own bounds, so a field the N65i doesn't send reads as unknown. Controls remain subject to the existing per-model compatibility rules.
- **Connection activity** notes, once per connection, a reply that failed its checks and a scooter using the header-free checksum.

## Checks run

- `bash tests/run.sh`: `ProtocolTest` now covers six cases:
  - the captured N65i success reply;
  - the UT5 MAX's standard checksum;
  - a damaged N65i checksum;
  - N65i replies within one connection;
  - refusing a mixed style;
  - a fresh style after reset.
- `FirstConnectionValidation` on a disposable emulator: 1,707 assertions. It drives a synthetic N65i handshake (header-free owner-check success, serial, ten-byte settings) to a saved binding, and reads the lock state from the short settings. It also checks two refusals:
  - a mixed-checksum reply is reported and ignored;
  - a short settings read does not bind a saved UT5 MAX, which still connects with its full read.
- **Physical:** the tester installed a release-signed test build of this change over Volt 1.5.0 (commit 6c140e6, versionName `1.5.0-n65i-test`) and reported that their N65i now connects. Telemetry accuracy and controls, including the shared lock, have not been checked on the scooter.
- The capture itself stays private: it contains the tester's owner identity. Only the non-secret reply above and aggregate counts are published.
