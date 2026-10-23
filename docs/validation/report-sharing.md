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

**Owner notes.** Once **Check & share** starts the check, the offer shows **Which scooter is this?**:
- a search box over NAVEE's 49 models, matched across 11 series by name or series description, ignoring case and spacing;
- a **Use "…"** write-in row for anything unlisted (a typed name that matches a listed model counts as picking it);
- **Not sure**.

**Next** opens once the check has finished and the owner has answered. If the scooter reports a drive mode, an optional step follows. It is read-only: the owner presses the scooter's own mode button, and Volt only polls its settings read.
- Each newly reported mode number gets a name: Walk, Eco, Drive, Sport or Not sure. Names already given are greyed out.
- The step ends when the scooter returns to the first mode, or when the owner taps Done.
- **Look right?** confirms the order (for example Sport → Eco → Walk). Skip sends the report without names.

Model numbering differs by model (the UT5 MAX reports 2 Eco, 3 Sport, 11 Walk; the N65i also has a Drive mode the UT5 MAX lacks), so Volt assumes no mapping. The connection-details offer shows the same picker inline. Answers go into a `volt-owner-notes-v1` block after the report. A picked model replaces the email's "type your model" line.

## Checks run

- `bash tests/run.sh` passes. New suites:
  - `NaveeModelsTest`, 22 assertions: 49 unique models in 11 series; search across names, series and spacing; exact matches; write-in cleaning and the length cap; Not sure; the owner-notes block; both email openings.
  - `DriveModeSurveyTest`, 21 assertions: asking about each new mode; waiting on the same one; closing the cycle only at the first mode after naming others; no reused names (Not sure can repeat); following the live mode while asking; the email block.
  - `ReportInviteTest`, 20 assertions: which models are asked about (unreported standard and mapped models yes; the UT5 MAX, N65i, simulator, serial-only, conflicting and unsupported products no), answer timing and unreadable answers, the backup rule, and the exact email.
  - `ConnectionReportTest`, 48 assertions: the per-scooter journal (another scooter's failures never qualify or describe this one; it stays bounded); the failure classes and the count since the last success; the offer conditions; the activity allowlist (it keeps protocol steps and coded notices, and withholds saved-scooter names and anything unrecognised); relative times only; and the email.
- `ReportInviteValidation` on a disposable emulator, with Bluetooth off and synthetic fixture identifiers: 87 assertions. The developer simulator plays an unknown model (39991):
  - its automatic offer is suppressed for a simulated scooter;
  - **Not now** stores a three-day wait, and **Don't ask again** stores never;
  - **Check & share** starts the check, and **Stop** ends it without an email;
  - the recording flag that rides, charge capture and charge measurement set blocks the check's guard;
  - a check that finishes while Volt is in the background opens no email until Volt returns. Next then stays off until the owner answers: a search for "gt3 pro" narrows to GT3 Pro, and picking it enables Next;
  - the drive-mode step then reads mode 3, and the simulator's reported mode moves 3 → 2 → 11 → 3, standing in for button presses. The owner names Sport, Eco and Walk; a used name is disabled; the return to 3 offers "Sport → Eco → Walk"; Volt sends no command;
  - one email to the alias, opening "Scooter model, from the owner: GT3 Pro", with the Model 39991 subject, the report JSON, the owner-notes block (codes and names) and no fixture name, owner identity, serial or MAC, and stores sent;
  - Not sure and Skip send the plain report, which still asks for the name;
  - recreating the Activity mid-check stops the check and sends and stores nothing.

  Without the simulator, failures only in the app-wide log never qualify the scooter. Two timed-out owner checks in its own journal get the connection-details offer on its own. A typed, unlisted name ("Mystery GT9") becomes a write-in and opens that email. Its email holds the steps, error stage and name prompt but no fixture secrets, and the offer does not return once sent.

Nothing is sent during tests. Email launches are intercepted in-process.
