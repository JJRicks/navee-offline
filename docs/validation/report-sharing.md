# Report sharing validation

## Behavior

Scooters identify themselves to Volt only by a numeric Model ID. The N65i advertises the Bluetooth name "NAVEE" and the Model ID 22131. New models therefore reach Volt only when owners report them. Volt now asks at the two moments a report helps most, and the owner can read every word before sending it.

**Help Volt support this scooter.** About 2.5 s after a connection settles, Volt makes one offer per Model ID per app session if all of these hold:
- the model's advertised Model ID is not in `ModelRegistry.REPORTED` (shipped with each release; the UT5 MAX is the reference);
- the scooter is not simulated, recording, mid-guided-test or mid-check;
- onboarding is not on screen.

The buttons:
- **Check & share** runs the existing bounded read-only compatibility check (about 30 s, no setting changes). It then opens the email app with the redacted report, addressed to `volt-reports@jjricks.com`. That is the same summary and JSON the Developer options preview shows, with no attachment.
- **Stop** ends the check without sending anything.
- **Not now** asks again after three days.
- **Don't ask again** and a sent report end the offer for that Model ID.

**Volt couldn't connect to this scooter.** A scooter that has never connected may fail twice in a row, counting only failures since the last successful connection. The count covers only this app session's attempts on that scooter: Volt's saved connection log has no scooter identity, so a per-scooter journal keeps one scooter's failures from qualifying or describing another. Ordinary pauses and the owner's own actions don't count. When it does and the connection has given up, Volt offers once to email its connection details. These are:
- the saved connection events (reason, stage, native status code, times relative to the first event, whether Volt was on screen);
- connection activity lines that match an allowlist of protocol steps and fixed notices (a notice can name a saved scooter), in order but without their time of day;
- the advertised Model ID, if the scan saw one;
- the app version, Android version and phone model.

There is no serial, MAC, owner key, PIN, scooter or owner name, route or wall-clock time.

Both emails open with an optional line asking for the model name, since the scooter cannot supply it. Answers are stored in `app_settings`: `report_invite_<Model ID>` per model, and `connect_report_<profile>` per scooter. The per-model answer backs up with app preferences, and the per-scooter answer as that scooter's preference, so a backup merged onto another scooter carries it (`BackupPlanTest`).

## Checks run

- `bash tests/run.sh` passes. Two suites are new:
  - `ReportInviteTest`, 20 assertions: which models are asked about (unreported standard and mapped models yes; the UT5 MAX, N65i, simulator, serial-only, conflicting and unsupported products no), answer timing and unreadable answers, the backup rule, and the exact email.
  - `ConnectionReportTest`, 48 assertions: the per-scooter journal (another scooter's failures never qualify or describe this one; it stays bounded); the failure classes and the count since the last success; the offer conditions; the activity allowlist (it keeps protocol steps and coded notices, and withholds saved-scooter names and anything unrecognised); relative times only; and the email.
- `ReportInviteValidation` on a disposable emulator, with Bluetooth off and synthetic fixture identifiers: 63 assertions. The developer simulator plays an unknown model (39991):
  - its automatic offer is suppressed for a simulated scooter;
  - **Not now** stores a three-day wait, and **Don't ask again** stores never;
  - **Check & share** starts the check, and **Stop** ends it without an email;
  - the recording flag that rides, charge capture and charge measurement set blocks the check's guard;
  - a check that finishes while Volt is in the background opens no email until Volt returns, then produces one email to the alias with the Model 39991 subject, the report JSON and no fixture name, owner identity, serial or MAC, and stores sent;
  - recreating the Activity mid-check stops the check and sends and stores nothing.

  Without the simulator, failures only in the app-wide log never qualify the scooter. Two timed-out owner checks in its own journal get the connection-details offer on its own. Its email holds the steps, error stage and name prompt but no fixture secrets, and the offer does not return once sent.

Nothing is sent during tests. Email launches are intercepted in-process.
