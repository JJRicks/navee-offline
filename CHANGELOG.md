# Changelog

## Unreleased

- Line up **Back up & restore** with the other rows in More's menu on wide and unfolded screens; its icon and title no longer sit further left.

## 1.6.0 — 2026-10-23

- Name N65i drive modes WALK, DRIVE and SPORT (codes 11, 2 and 3), assuming they match the GT5 Max until an N65i owner reports otherwise.
- Try controls the official app doesn't offer for your model in **More → Experimental controls**. On an identified model it lists any of kick-start speed (including Standing start), cruise control, traction control, tail light, automatic lights, turn signal sound, the three assists and prompt sound that the official app hides for it, most likely to work first; the UT5 MAX keeps its Lab controls. Everything the official app offers stays in Scooter settings as before. Each row says **Untested on your model** until you try it, then **Worked here**, **No change seen** or **Refused by scooter** from what the scooter reads back, and **Set back** returns the earlier value in one tap. A try needs the scooter stopped, unlocked and connected as the owner, with nothing else changing or recording. Speed limits, charging, the lock, PIN, proximity unlock, the clock and firmware are never added there. When the scooter accepts a try but keeps its value, Volt says so and stays connected.
- Redesign Experimental controls (formerly Firmware lab) in Volt's style: one card per group, a Try sheet with the current value, radio choices and the result, a single note when something holds every control, and labels that shorten instead of clipping at large text sizes.
- Fit Home to the scooter's model. A new dashboard's Setting tiles swap a control the official app doesn't offer that model for one it does: the GT5 Max gets Top speed and Cruise control, since its official app has no scheduled charging. Tiles you chose yourself never change. GT5 Max drive modes read WALK, DRIVE and SPORT (codes 11, 2 and 3, from an owner's report). A Top speed or other choice the scooter reports outside its model's choices shows "—" instead of a number.
- Remember which model you said a scooter is. Your answer to **Which scooter is this?**, in a report or under **More → My scooters → Edit scooter → Which model is it?**, gives a scooter Volt can't identify yet that model's name (unless you renamed it) and its photo, when Volt has one. It never changes which controls Volt offers, and Volt's own identification always wins. **Change model** picks another; **Not sure** clears it. Backups of Scooters carry it.
- Name and picture the GT5 Max (Model ID 25462), from an owner's compatibility report. Its controls are not verified; they follow the official app's map for its model group as before. Volt now has a report for it, so its owners aren't asked for one.
- While the check runs, **Help Volt support this scooter** asks which scooter it is: search NAVEE's models or type a name. It then offers an optional, skippable step to name each drive mode. You press the scooter's own mode button, and Volt only watches the mode it reports, so it never changes a setting. **Volt couldn't connect to this scooter** asks for the model too. The answers travel in the email as owner notes, so you no longer type the model name into the message.
- Simplify USB pairing into one focused card per step, with expandable help and the same explicit access and capture approvals.
- Recommend Chrome + USB and select it by default when adding a scooter or starting setup; retain routes already chosen in an unfinished setup.
- Keep the backup password field visible above the on-screen keyboard.
- Add **Report an issue** to More, under the version line. It opens your email app with a draft to volt-reports@jjricks.com. The draft holds a prompt for you to fill in, then Volt's version, your Android version and phone model, and the selected scooter's model name. Nothing is sent until you send it.
- Put **Email to Volt** right under **Run again** in the compatibility checker, so a finished check no longer needs a scroll to the preview. It asks **Which scooter is this?** and offers the optional drive-mode step, like the offer for unreported models. Then your email app opens with the report addressed to volt-reports@jjricks.com. The preview's **Share** still opens Android's share sheet.
- Name and picture the ST3 Pro (Model ID 23452, the German-market model in the protocol reference Volt credits). Its controls are not verified, and it still gets the offer to share a compatibility report.
- After connecting to a model Volt has no compatibility report for, offer once to help: one tap runs the 30-second read-only check, and your email app opens with the redacted report addressed to volt-reports@jjricks.com, so you can read it before sending. **Not now** asks again after three days; **Don't ask again** ends the offer for that model. Volt ships the list of models it already has reports for. If a scooter that has never connected fails twice, Volt offers to email its redacted connection details the same way. Both emails ask for the model name, which scooters do not report.
- Recognize the N65i (Model ID 22131): My scooters names it, Home and My scooters show its photo, and its compatibility check accepts its ten-byte settings read. Controls a tester confirmed on a parked N65i (lock, display units, headlight, auto light sensor) no longer say **Untested here**; its other mapped controls still do.
- Replace Volt's old default name **My UT5 MAX** on a scooter identified as another model, the first time it connects. New Shizuku owner captures start with Volt's default name, so the model name replaces it.
- Add **Check for updates** to More → About & permissions. It runs only when you tap it: the first time, Volt explains that GitHub sees your IP address and Volt version, and asks before any request. It then compares the latest GitHub release with this version and offers **Open release page**. Volt never downloads or installs anything itself.
- Connect to the N65i. Its replies leave the leading 0x55 out of their checksum, so Volt discarded every one, including the owner-check success, and stayed on **Checking owner identity** until it timed out. Volt now accepts either checksum style, fixed per connection by the first valid reply, and finishes connecting with the N65i's shorter settings read. The UT5 MAX handshake is unchanged. Connection activity now notes when a reply is ignored.
- Reopen Rides instantly on wide screens with a long ride selected. A finished ride's chart preview now comes from the analysis cache instead of replaying its whole recording on the main thread every time the tab is shown; the first view of a ride after Volt starts is unchanged.

## 1.5.0 — 2026-10-22

- New Android app ID: `com.jjricks.volt` replaces `local.navee.offlinelock`. Android treats this as a different app, so it installs beside Volt 1.4.x instead of updating it. To move: in the old app, More → About & permissions → Your data → Back up & restore → Back up (everything selected). In the new app, choose **Restore Volt backup** on the welcome screen, check your scooters, rides and charges, then uninstall the old app. Grant permissions again; Bluetooth pairing belongs to the phone and carries over.
- The APK is now `volt.apk`. Legacy owner setup files and backup formats still import.
- Make uncertain UT5 MAX standard-charger estimates to full more conservative, while preserving voltage matching and the final taper.
- Prevent shorter percentage-only charge history from overriding uncertain early full-time estimates; preserve existing lower-cutoff calculations.
- Wait for automatic charger identification or manual selection before enabling Charge done by during UT5 MAX charging.
- Increase the Charge done by scheduling buffer from ten to twenty minutes for newly enabled or explicitly updated plans.
- Estimate UT5 MAX standard-charger time below 89% from pack voltage, live current and charge age, instead of the battery percentage alone. The gauge's late catch-up and the charger's first-hour current decline no longer make early estimates, especially for lower limits such as 80%, optimistic.
- Give Charge done by a separate cautious estimate (more charge still needed than the gauge's observed catch-up implies, strongest observed current decline, longest observed finish) before its twenty-minute buffer; the dashboard shows the most likely time.
- Track when charging starts, or infer it from about ten minutes of current readings, without saving anything.
- Record new predictions as estimator `ut5-stock-pack-v6`; preserve earlier recordings and saved predictions.
- Offer to measure a UT5 MAX standard charge for up to ten minutes before arming Charge done by when Volt did not see the charge begin. Volt asks first, stays connected with a notification offering **Schedule now**, then arms the existing one-shot schedule with the charge's inferred age. If measuring cannot finish, it falls back to the cautious plan or explains why nothing was set.
- Keep a charge recording open through a pause that Volt's own charging schedule creates, marking the paused interval in the journal and export (`schedule_pauses`) so a scheduled charge can be recorded end to end.
- Show the last confirmed lock state while disconnected or reconnecting, clearly marked as last known.
- Move the compatibility checker into Developer options; keep unfinished test recovery accessible.
- Put Back up & restore directly in More, replacing the separate owner-only export. New backups include all saved app data by default and clearly identify partial selections.
- Make full backup restore prominent during onboarding. Older owner setup files import through the same restore flow with an explicit scooter-access-only notice.
- Rename Capacity experiment to Usable ride energy; an untouched old default title follows the rename and custom titles are kept. It is not a battery-health test.
- Require 60 battery points across six rides before estimating, set aside rides whose gauge corrected itself mid-ride, and show a 90% range.
- Show charge per full gauge (Ah) and a charging check from completed charge recordings.
- Make Volt's learned range follow the battery gauge: energy per percentage point is learned across the gauge, so low-battery forecasts are no longer optimistic, and levels below any recorded ride are treated cautiously.
- Separate riding efficiency from battery energy; rides with a short recording gap now count toward energy per km.
- Show the learned forecast and its likely range in the Range estimate picker.

## 1.4.8 — 2026-10-21

- Refine the experimental UT5 MAX stock-charger time to full below 89% by matching pack voltage and charging current to earlier completed charges, reducing dependence on the displayed battery percentage.
- Keep the existing estimate from 89% through the final 99% taper, and retain the existing fast-charger and unknown-charger behavior. Incompatible readings fall back to the existing estimate.
- Record new predictions as estimator `ut5-stock-voltage-v4`, while preserving earlier saved predictions and recordings.
- Run public-repository CI on GitHub-hosted workers; keep disposable private workers restricted to private-repository runs.

## 1.4.7 — 2026-10-19

- Edit the Charge done by time without a connected scooter or charger; changes apply when next enabled.
- Editing the time while connected and active recalculates the scooter's start once, using a live estimate or the saved estimate during an unchanged scheduled pause.
- Preserve the manual Charge start time while done-by is active, and restore it when done-by is disabled without enabling scheduled charging.

## 1.4.6 — 2026-10-19

- Add **Charge done by** in Scooter settings and dashboard shortcuts. Use the current charge estimate plus a ten-minute buffer to save a one-time start on the scooter; no ongoing phone connection is needed.
- Remember confirmed charging schedules across reconnects and reconcile fresh completion or override evidence without automatically rearming them.
- Drag along recorded map routes to inspect readings, retaining the gesture when your finger leaves the line; keep off-route panning and two-finger zoom.
- Make the default Capacity experiment card one row shorter while preserving saved dashboard layouts.

## 1.4.5 — 2026-10-18

- Recover charge recording after a sustained, valid change in charging power instead of repeatedly rejecting readings against an old baseline. Isolated spikes remain excluded; discarded readings stay marked as a gap.
- Use one shared Y axis for estimated and observed finish times, and for estimated and actual countdowns. Keep both traces and their individual readouts without duplicate, clipped axis columns.
- Preserve existing recordings and their genuine missing intervals; this update does not invent historical samples.

## 1.4.4 — 2026-10-18

- Return default Reading tiles to Power, Temp and Health for compact screens. Existing saved dashboards keep their selected readings and layout.
- Add Capacity experiment below Record ride in folded and unfolded defaults, and refresh the unfolded website dashboard capture.
- Show Processing while the capacity estimate loads; keep a completed estimate visible during refreshes instead of briefly claiming there is not enough data.

## 1.4.3 — 2026-10-18

- Arrange the unfolded default with the scooter overview beside live readings, followed by full-width lock, settings and ride controls. Preserve the folded geometry and existing custom dashboards; add Charge limit to default readings on both screens.
- Refresh the unfolded website dashboard with the same sample readings and Charge limit; keep the phone screenshot’s three-reading example.
- Support two bounded, disposable CI workers so core and setup checks can run together.

## 1.4.2 — 2026-10-18

- Make switches clearer in both states, with solid Volt red for On and a brighter grey for Off.
- Align Quick access and Setting tiles fill, ripple and clipping corners across screen sizes.
- Inspect ride-map readings only near the route; tap elsewhere to dismiss them.
- Use a transparent lightning bolt for Android notification icons.
- Apply the selected range estimate consistently across current-value widgets and the Stats summary; identify stored scooter range separately.
- Show battery percentage consumed in each ride's overview.

## 1.4.1 — 2026-10-18

- Keep folded and unfolded dashboard positions and sizes independent, including when both use the same column count. Preserve both layouts in saved slots, exports, backups and Undo/Redo.

## 1.4.0 — 2026-10-17

- Add model-aware controls, local compatibility reports, read-only discovery, and guarded developer tests for mapped NAVEE scooters. Physical validation still covers the UT5 MAX only.
- Unify backup and restore with selectable categories, profile mapping, and recovery over damaged app data.
- Colour recorded ride routes by speed and optionally end parked rides after a lost connection.
- Start rides immediately with date, time and available drive mode; keep manual renaming afterward.
- Add automatic stock/high-speed charger selection and a provisional UT5 MAX fast-charge reference, with steadier reconnect/taper handling and matching learned curves.
- Require an explicit charger label when recording under Automatic; show which family estimates actually use.
- Fix battery-fill clipping, add lock-slider motion trails and a subtle charging glow, and improve narrow-screen map labels and enlarged-text settings.
- Simplify the homepage and alternate its lock/unlock demo, while retaining the repository link and non-affiliation notice.

- Recheck queued setting changes immediately before transmission, bound stalled guided-test preparation, and preserve outside changes during a test.
- Fix the homepage compatibility link hit area and add disposable CI workers with isolated retries and bounded cleanup.

## 1.3.1 — 2026-10-16

- Simplify **Scooter → Range estimate** to **Scooter's estimate** (default, following drive mode) and **Volt's estimate (learned)**. Remove the duplicate App preferences entry; keep existing quick-access shortcuts working offline.
- Grey out Volt's option until enough usable rides exist, with a brief explanation when tapped. Show a short introduction only on the first usable selection.
- Stream recorded rides for analysis to avoid temporary memory-limit failures. Distinguish checking, insufficient history and read failures; allow an immediate retry after a read error.
- Update an open picker when a ride finishes and guard against stale choices after history or scooter changes. Keep simulation separate from real ride learning.

Install over your existing Volt APK to preserve setup and recordings. Range learning remains local and experimental; the calculation and eligibility thresholds are unchanged. Volt is an independent community project, not affiliated with, endorsed by or sponsored by NAVEE.

## 1.3.0 — 2026-10-16

- Back up and restore complete ride recordings from Rides, including any recorded routes and phone motion, names and notes.
- Preview a safe merge: skip identical rides, retain local conflicts, and explicitly choose the destination scooter for unknown profiles. Validate archives and recover interrupted transfers before reopening the recording stores.
- Add an optional per-scooter Learned range source in App preferences. Use recent usable rides to refine Scooter overview, Your scooter and Battery overview, with the scooter's estimate as the fallback and default.
- Keep learning local to each scooter, rebuild estimates from restored rides, and preserve the original range in raw readings, generic widgets, charts and exports.

Install over your existing Volt APK to preserve setup and recordings. Ride-only backups are unencrypted and may contain private routes; the existing full app backup is password-protected. Learned range is a preliminary estimate for similar riding, not a capacity measurement or guaranteed distance. Volt is an independent community project, not affiliated with, endorsed by or sponsored by NAVEE.

## 1.2.4 — 2026-10-16

- Remove the clock/age footers from Scooter Overview and Reading tiles, including while connecting. Keep cached values muted and the disconnected header's Last seen time.

Install over your existing Volt APK to preserve setup and recordings. Volt is an independent community project, not affiliated with, endorsed by or sponsored by NAVEE.

## 1.2.3 — 2026-10-16

- Refine the UT5 MAX default charging estimate with a second observed finish and paired voltage/power matching for the long 99% stage. Keep the live-current bulk model and rated capacity.
- Let a completed charge learn from a sufficiently long, uninterrupted finishing segment after an earlier gap, without joining missing data or qualifying unreliable earlier intervals.
- Simplify charging on Home by removing estimate-source badges and the charger row; keep power, estimated time and meaningful unavailable/low-current states. Charger selection remains in Scooter settings and charge capture.
- Save the updated estimator version with future predictions; preserve existing recordings.

Install over your existing Volt APK to preserve setup and recordings. Charging estimates remain experimental; another physical charge will help validate the refinement. Volt is an independent community project, not affiliated with, endorsed by or sponsored by NAVEE.

## 1.2.2 — 2026-10-15

- Restore horizontal padding around the Lock bar's Connect and Cancel buttons, including after connection-state changes.

Install over the previous Volt APK to preserve setup and recordings. Volt is an independent community project, not affiliated with, endorsed by or sponsored by NAVEE.

## 1.2.1 — 2026-10-15

- Show charge recordings in one ride-style chart with colored category toggles, fullscreen, pinch zoom, panning and raw-sample inspection.
- Compare saved finish predictions against the observed finish on a shared scale. Show power, battery level and countdown by default, with voltage, current, temperature and saved charging limits available alongside them.
- Keep graph selections, zoom and inspected samples when opening or closing fullscreen; preserve missing-data gaps and older recordings.

Install over the previous Volt APK to preserve setup and recordings. Older charge recordings retain their saved readings; estimates are shown only when recorded. Volt is an independent community project, not affiliated with, endorsed by or sponsored by NAVEE.

## 1.2.0 — 2026-10-15

- Save charging-time predictions, estimator version and estimate sources with charge samples, including while recording in the background.
- Inspect saved charges with Time remaining and Predicted finish graphs, raw sample values and an observed-100% comparison when the finish was captured. Older recordings remain readable without invented historical estimates.
- Give direct-toggle setting tiles a power-button cue instead of a navigation arrow; keep arrows for settings that open another view.

Install over the previous Volt APK to preserve setup and recordings. Saved predictions are available for new charge samples; older recordings remain readable. Volt is an independent community project, not affiliated with, endorsed by or sponsored by NAVEE.

## 1.1.0 — 2026-10-15

- Improve UT5 MAX stock-charger time estimates by default, combining live charging current with an experimental recorded finishing curve. Personal recordings can refine estimates; other models and incompatible charging power keep the live-rate fallback.
- Simplify Home, Scooter, Rides, preferences and setup screens with concise labels, status chips, icons and tooltips. Keep stale-data ages, charging assumptions, capture consent and privacy choices visible where they matter.
- Keep charger choices unchanged when cancelling charge capture, restore Close after cancelling a backup export, and route dashboard layout imports to their own confirmation.
- Keep navigation labels and expanded ride charts usable with larger text sizes.
- Base low-speed ride-chart shading on wheel speed, and limit hatch drawing to the visible chart when zoomed.
- Keep lock-slider thumbs inside short or tall cards, preserve separate edit/resize touch targets in one-row widgets, and wrap reading tiles when their numbers need more space.
- Keep widget content stationary when entering edit mode and use the recorder’s icon-only action when a compact card cannot fit its label.

- New default Home for fresh installs, new scooter profiles and **Restore default widgets**: Scooter overview, Lock bar directly beneath it, Reading tiles, Setting tiles and Ride recorder. Existing dashboards and saved slots are unchanged.
- Add four widgets: **Lock bar** (one-row lock with Connect; connected, the slider is the card), **Scooter overview** (charge, range, photo, charge bar and readings), **Reading tiles** and **Setting tiles** (one to four small cards). Every earlier widget stays available in Add widget, and the All widgets example includes the new ones.
- The Home status line now shows the last-seen time (newer of the last connection and the newest reading); the separate "Last seen" line under the header is gone.
- While editing Home, card content dims behind solid, high-contrast editor handles. The change animates in a short staggered fade (handles first when leaving), reverses smoothly if toggled mid-way and applies instantly with system animations off. A card being moved or resized returns to full opacity until it is dropped.

Install over the previous Volt APK to preserve setup, dashboards, saved slots and recordings. Charging estimates remain experimental. Volt is an independent community project, not affiliated with, endorsed by or sponsored by NAVEE.

## 1.0.1 — 2026-10-15

- Keep an explicitly started charge recording alive through a temporary Bluetooth drop, retrying for up to five minutes while retaining its Stop notification and background protection.
- Resume the same recording only after the same scooter authenticates and supplies fresh charging readings. Mark missing intervals as gaps and exclude them from learned charging curves.
- Respect Stop, explicit Disconnect, profile changes, disabled Bluetooth, revoked permissions and the existing recording limits during recovery. Interrupted app processes do not silently resume capture.
- Retain a small private connection-event history across app restarts and include it in manually exported diagnostics, without recording owner credentials, PINs, Bluetooth addresses or packet contents in that history.

Upgrade over the previous APK to preserve setup and recordings. Recovery is tested with simulated faults; another real overnight charge is needed to validate physical Bluetooth recovery.

## 1.0.0 — 2026-10-14

- Use singular chart windows (1 minute/hour/day) and consistent Odo. abbreviation.
- Fit Trip overview, Readings and Charging values to short-card height, trimming noninteractive padding before reducing values within their existing readable limits. Keep scrolling when all readings cannot fit, and preserve saved card dimensions.
- Keep the first connection/disconnect haptics immediate while suppressing rapid repeats for five seconds after each drop. Preserve the quiet period across focus changes, and never replay suppressed events or delay Bluetooth.
- Include reproducible emulator-only Quick access and connection haptic regression checks in the repository, including dialog focus, preferences, guarded actions and lifecycle cancellation.

- Add a shared Basemap switch below ride maps and in map settings. Online tiles start off for new setups; first use explains the tile provider connection and requires consent. Preserve an existing explicit choice and keep recorded routes usable without tiles.
- Introduce the Volt project homepage and refresh the pairing site's independent-project notice.
- Simplify the README with quick installation steps and move detailed setup/build guidance into dedicated docs. Add clearer privacy, support, contribution and third-party notices.

Install over the previous project APK to preserve setup, dashboards and recordings. Volt is an independent community project, not affiliated with, endorsed by or sponsored by NAVEE. Experimental estimates, physical-device testing limits and preview setup-helper limits remain documented in VERIFICATION.txt.

## 0.13.0 — 2026-10-14

- Rename the app to Volt in the launcher, onboarding, About, notifications and user documentation. Keep the existing Android package, setup, saved data and repository identity.
- Give the dashboard equal left/right margins. Retain five-column layouts on narrow cover screens, fit compact lock text and editor controls, and preserve the saved placement instead of silently switching to two columns.
- Focus and select the ride name and show the keyboard when starting a recording.
- Expand ride maps into a full-screen view with zoom, route-color and reading controls. Keep a separate recenter button and preserve the inspected position and selection when opening or closing the expanded map.
- Make the Location permission row open Volt's Android app settings, with directions to Permissions → Location.
- Match the USB setup and recovery website to Volt's dark/red theme, rounded controls and locally bundled Saira fonts.
- Keep narrow widget captions readable with whole-word labels, abbreviations and accessible icons. Give readings space before hiding the scooter image; keep compact recorder/editor targets separate and restore right-edge grid dots.
- Add optional directional haptics to accepted Quick access on/off commands, respecting app and Android preferences.
- Give the launcher a stylized lightning-bolt icon with adaptive and monochrome variants.
- Add a two-part connection thunk and fading disconnect ticks while Volt is focused. Background events, returning to the app, and simulated connections stay silent; unsupported hardware uses a short fallback.

Install over the previous official APK to keep setup, dashboards and recordings. Physical haptic feel and Galaxy Fold hardware checks remain for owner testing. Existing experimental battery/sensor and setup-capture validation limits still apply; see VERIFICATION.txt.

## 0.12.1 — 2026-10-14

- Add subtle haptic feedback for tabs, choices, switches, favourites, dashboard editing and confirmed lock/ride actions. Haptic feedback is on by default and can be disabled in App preferences; Android’s Touch feedback setting is respected.
- Make committed switch-thumb swipes save their changes, with one callback in the requested direction. Keep canceled gestures and programmatic updates silent.
- Play favourite-star feedback before rebuilding the card, including when removing a chart from Overview.
- Keep the current layout steady when a foldable crease moves between flat and half-open; continue avoiding physical hinges and adapting to actual window-size changes.
- Stop More’s split width from shifting after its entrance animation.
- Make the Live Stats validation fixture robust to continuous live-indicator animation.

Install over the previous official APK to preserve setup, dashboards and recordings. Physical haptic feel, fold transitions and scooter-confirmed lock/ride feedback still need owner testing; see VERIFICATION.txt.

## 0.12.0 — 2026-10-14

- Add password-protected full app backup/restore in Developer options, including private scooter setup, history, rides/routes, charges, preferences, layouts and widget parameters. Offer restore on the welcome screen after clearing storage.
- Pause the connection for a consistent backup, validate imports before confirmation, and recover the previous data if replacement is interrupted.
- Synchronize restore recovery markers and directory changes to strengthen interruption recovery; reclaim temporary encrypted exports left by an interrupted save.
- Export/import shareable dashboard JSON with the active arrangement, all three slots, column choice and widget options. Add an explicit Developer action to replace slot 3 with the latest All widgets example.

- Add consistent, short motion across tabs, submenus, dialogs, settings rebuilds and ride maps. Respect disabled Android animations.
- Prevent scrollbar flashes and scroll-position jumps when restoring pages or changing settings; bring in map content and tiles smoothly.
- Drag Stats favourites by their grab handles from an icon-only Reorder control. Save on drop, restore the starting order on cancellation, and retain screen-reader Move up/Move down actions.
- Add an **All widgets** example to saved-layout slot 3 only when absent for that scooter. Include all 15 widget types with useful options and one- through six-column arrangements. Preserve the active dashboard, current column choice, existing slot 3 and other saved layouts; restoring the example remains optional.
- Keep the active dashboard safe if optional example creation fails. Give Quick access a full-width row in the narrow example so enlarged text does not strand it beside an empty space.

Install over the previous official APK to keep setup and recordings. Existing battery-estimate and physical sensor/recording limitations remain; see VERIFICATION.txt for current validation evidence.

## 0.11.0 — 2026-10-14

- Use the owner's ten-widget Home preset and saved options for new/reset dashboards, including maximum-speed and scheduled-charging Quick access. The five-column choice adapts to narrower screens, and one- through four-column defaults keep Connect near the top. Captured widget positions, sizes and options are preserved, along with existing current layouts and saved slots.
- Browse searchable, categorized charts for every recorded numerical parameter. One compact 2-minute-through-All picker controls live and expanded graphs; star and manually reorder a mix of reading/chart favourites in Overview.
- Keep the Readings count and spacing while removing its redundant favourites filter; enlarge and bring the Live Stats pulse closer to its title.
- Inspect original ride values and sample timestamps by tapping or sliding inline/fullscreen graphs. Expanded views load the complete committed recording asynchronously, preserve short peaks, support multiple sensor overlays and independent scales, and retain a usable plot with large text. Add ride renaming, retry after failed detail loads and clear fractional time ticks.
- Move basemap/provider controls into App preferences, enable USGS for new preferences, add elevation route coloring, and reject implausible altitude excursions from derived metrics while retaining raw data. New rides use positive current as draw; existing journals retain their original interpretation.
- Add breathing room between the ride map and its controls.
- Add explicit charge recording with manual Stock/High-speed labels, qualified per-scooter charging curves and versioned JSON export. Learned ETA requires matching live power at the current battery level; otherwise use the current-rate estimate.
- Protect active ride/charge recording with immediate ongoing Stop notifications, appropriate foreground-service behavior and a renewed bounded CPU wake lock. Add Background recording guidance and Android settings shortcuts; the ordinary ten-second idle timeout remains.
- Let narrow Quick access rails use three rows, with separate Move/Options targets on the thinnest cards. Keep new widgets as drafts until Save, improve shortcut reordering/removal and duplicate animation, compact empty grid rows, and allow a wide recorder to use one row.
- Refine battery/health/capacity cards, fit short reading values with their units, separate charging power/time tiles, and distinguish draw/charge power colors. Chart widgets gain Relative/Absolute scaling with bounded percentage axes and responsive time labels.
- Group distance/speed, temperature and time format under Units, add preference spacing, simplify charging labels and use confirmed-state sliding scooter selectors with wrapping light patterns.
- Follow the current riding mode's supported firmware range estimator while stationary, with fixed choices available and bounded attempts across reconnects. Range remains scooter-reported; automatic Boost detection is not included.

Upgrade over the previous official APK to retain setup and recordings. Battery capacity, charging ETA and phone-sensor results remain estimates. Real overnight Bluetooth/charger behavior, physical range/control readback and outdoor sensor accuracy still need owner testing. See VERIFICATION.txt for the tested integration scope and separate release checks.

## 0.10.0 — 2026-10-13

- Limit speed-gauge scale summaries and editor prefills to tenths while preserving exact stored bounds on unchanged saves.

- Clean up floating-point noise in gauge scale editors while preserving the exact stored limit when saving unchanged text.

- Add a Celsius/Fahrenheit selector under App preferences for temperature readings, dashboard gauges and charts, and ride summaries. Stored telemetry and gauge bounds remain in Celsius; Fahrenheit scale edits convert back before saving.
- Let My Scooter values grow larger while retaining width/height fitting, the readable minimum and long-value wrapping.

- Modernize the shared widget-readings picker with a themed searchable sheet, icons, selection counter and pinned Save button. Preserve filtered selections and enforce the 12-reading limit. My Scooter uses compact captions for all parameters while retaining full names for search, accessibility and tooltips.

- Make the battery overview's empty capacity easier to distinguish with a dim red track, faint diagonal texture and thin outline; stale and unavailable readings remain neutral grey.

- Configure two to eight ordered Quick access items, including direct scooter toggles and settings panels that open above Home. Preserve existing safety/readback checks and legacy shortcut defaults; saved layouts and duplicates retain the selection. Fit compact buttons to the available height without clipping labels.
- Enlarge editor icons, keep move/options/duplicate together at the left, remove the redundant Size row from options, and double the subtle wiggle speed. Saving a new off-screen widget scrolls it into view with a short white outline; user touch cancels the cue and reduced motion uses immediate positioning.

- Use rounded sliding selectors for appearance, distance/speed units and time format.
- Add a hidden Developer options menu, unlocked by seven taps on the About version. Its temporary simulated connection supplies sample readings, local-only controls and chart previews, visibly marked with an Exit banner. Real Bluetooth is closed, recording is blocked and samples never enter stored telemetry or battery history.
- Animate entering/leaving dashboard edit mode with a short slide and control pop, plus a barely visible widget wiggle that pauses during gestures, focus loss and disabled system animations.

- Refresh Gauge with a 270° ring, a larger centered value and unit, and padded minimum/maximum labels; existing value formatting and stale-state presentation are retained.
- Put each widget's move grip, options gear, copy and red trash icons in one row of matching squares. Keep glyphs centered as targets shrink from 52 to 36 dp on minimum-width cards; the resize corner stays 48 dp. Widget options have only a fixed ✓ Save button below the settings and retain the top-right X.
- Add grouped Undo/Redo arrows immediately left of Done, with the action row below the scooter name on compact screens. Keep up to 40 layout changes in the current editing session, preserving widget settings; new grid edits clear Redo, while no-op, cancelled and settings-only changes leave it intact. Defaults and slot restores start fresh history.
- Offer up to six dashboard columns on sufficiently wide screens, with a rounded scrolling selector and animated selection highlight. Adaptive keeps its existing one- through four-column behavior. New five/six-column arrangements derive from saved layouts; existing arrangements and slots remain intact.
- Give each widget a minimum usable width based on its contents and text size. Saved slots support all one- through six-column arrangements, and a six-column grid allows a four/two split between the scooter and lock cards.
- Show Disconnect in the connected lock widget by default, with a widget option to hide it. Connect and Cancel remain available when needed.
- Add short size/position transitions during dashboard editing, a 200 ms soft-puff removal effect, confirmed-state lock movement, page transitions and button feedback, respecting Android's disabled-animation setting. Lock commands and confirmation requirements are unchanged.

Install over the preceding official APK to retain profiles, dashboards, saved slots and recordings. The new sample-data mode never adds fake recordings or battery history. Existing battery-capacity and phone-sensor estimates remain experimental; see VERIFICATION.txt for executed checks and physical validation limits.

## 0.9.1 — 2026-10-13

- Dashboard lock controls adapt to the width of their card: narrow cards stack the lock heading and keep the action within reach. Short slider labels preserve the full-height touch target, and Connecting no longer repeats the scooter name already shown in Home's header.
- The battery graphic yields height to wrapped readings using its readable text size. Single-column readings put icons beside values, avoiding unnecessary inner scrolling in narrow and large-text charging cards.
- Dashboard row height follows Android's font-size preference as well as the app's text setting, preserving saved grid positions while allowing larger text more space.
- A fully opened fold crease no longer reserves an empty upper pane. Tabletop folds and physical hinges still split the layout; Home, setup and ride details use the same separation rule.
- Center the dashboard editor's move and options icons in their translucent squares while retaining their 48 dp touch targets.
- Add emulator-only dashboard layout regressions for different window widths, larger text, visible control bounds and OEM fold-state changes.

## 0.9.0 — 2026-10-13

### Dashboard and setup

- Home's Layout sheet has three named save slots per scooter. Each keeps the widget list and options, column choice, and folded/unfolded arrangements together. Restore defaults and current edits preserve the saved slots; replacing or restoring a slot asks for confirmation. Restoring a slot also restores the phone's shared column choice without replacing other scooters' widgets.
- **First run, unfolded:** on foldables' inner screens and other wide screens, the numbered setup steps show a step list beside the current step: the scooter, how long setup takes and each step marked done, current, to do or not needed. On a book-posture hinge the split falls on the fold. Welcome, the path choice and the finish screen stay single-column.
- Optional **Use Shizuku on this phone** owner capture on Android 11+, using shell-backed Shizuku 13+. Debugging permission, capture consent and importing the result are separate steps. The captured identity stays in memory until import.
- A **Chrome/USB testing preview** at [pairscooter.jjricks.com](https://pairscooter.jjricks.com/) guides computer users through USB authorization, a short capture, and saving their private setup. The dedicated origin keeps its USB permission and recovery state separate from the rest of the website. Capture processing stays in the browser; there is no account sign-in or capture upload service.
- Both new helpers restore logging and stop NAVEE before making a captured result available. Bounded commands, a shared session lock and interruption recovery protect cleanup. The browser forgets USB permission after safely completed sessions where the browser supports it. Existing computer capture, backup import and manual identity entry remain available.

### Upgrade and limits

Install over the preceding official APK to keep profiles, dashboards, saved slots and recordings. Initial capture still requires Android debugging access and an already signed-in, owner-bound NAVEE app. Everyday Bluetooth control remains local.

The browser helper remains a testing preview in this app release. A successful real-scooter capture through either new helper is still awaiting validation; the available phone checks were performed with the scooter out of range. Browser capture supports the main personal Android user only. See VERIFICATION.txt and the setup documentation for test scope and recovery limitations. Existing experimental battery and phone-sensor estimates retain their limitations.

## 0.8.0 — 2026-10-13

A full visual redesign of every screen. Bluetooth, commands, storage, exports and preference keys are unchanged.

### Design

- New "Volt" look: near-black screens, soft rounded cards, Saira type (bundled, SIL Open Font License) and a single scooter-red accent. Red marks things you can change or act on; grey cards are information; last-seen values stay grey with their times. A light variant follows the Appearance setting.
- Floating tab bar with Scooter as the raised centre button. Tabs are now Home, Stats, Scooter, Rides, More.
- Dialogs use rounded Volt surfaces with red buttons; pickers open as bottom sheets.

### Screens

- **First run:** a step-by-step onboarding replaces the first-time setup guide's wall of text: choose capture, backup file or 12-character identity; a checklist; USB debugging; running the capture helper (with Copy); import; Nearby devices; connecting; done. The tab bar stays hidden until it is finished. **More › My scooters › Run setup again** reopens it.
- **Home:** one header with the connection line, scooter name and edit pencil. The lock is a full-width bar: tap anywhere on it or slide it (locked: knob on the right; unlocked: red knob on the left). Ride recording is its own card. The battery overview is a tall red cell; while charging, a charging card with the scooter photo, a progress line to the charge limit, time remaining and battery-side power appears above the widgets. All widgets, sheets and edit controls are restyled.
- **Scooter:** one scrolling page grouped into Ride, Battery, Lights, Dashboard & sound, Security and Profile, with search. Top speed is a red card with the scooter's four choices shown in your units (the scooter still receives km/h). Choices open a sheet that waits for the scooter's confirmation. Line and star lights have their own page.
- **Stats:** red section tabs, battery overview with favourites, readings with tappable favourite stars, charts and diagnostics.
- **Rides:** record card, route thumbnails in history, a live recording card with Notes and End, full-page ride details, route map and comparison, and bottom-sheet sensor and map-source pickers.
- **More:** profile card and grouped menu, restyled submenus, and a two-step handlebar PIN entry (enter, then repeat).

### Layout and upgrade

- Expanded screens use two-column Scooter settings and separate menu/detail panes in Stats, Rides and More. More preserves both scroll positions during quick returns and resets them after one minute away from the tab or app.
- Chart choices wrap as whole labels, and diagnostic actions stack when needed. Narrow battery-health cards keep their percentage visible, and the raised Scooter tab has one accessible target.
- Setup correctly opens the scooter chooser after scanning has begun, and a newly typed owner identity replaces any abandoned pending import and name. Quick access commands remain disabled while editing the dashboard.
- Install over the previous official APK to retain profiles, layouts and rides. Initial owner capture still requires ADB; battery and phone-sensor estimates remain experimental. See VERIFICATION.txt for validation scope.

## 0.7.2 — 2026-10-12

- More keeps its submenu for one minute away from the tab or app, then returns to the settings index. Active use, quick returns, rebuilds and explicit Security shortcuts retain their expected behavior.
- USGS Topo is the default optional U.S. basemap. Choose USGS, OpenStreetMap or a custom provider directly in a ride preview or Phone sensors settings.
- Route maps support smooth pinch-to-zoom and two-finger panning, anchored under your fingers. Zoom buttons and Fit remain available.
- Map errors stay out of the imagery: policy-block images are rejected, denied providers pause requests, and local routes remain visible. Old unverified tile caches refresh before reuse. USGS close-ups enlarge available tiles instead of requesting unsupported levels.

## 0.7.1 — 2026-10-12

- Cached readings now say “Last seen.” Dates outside today use a compact uppercase day/month before the time, such as `05 MAR · 13:05`, retaining the selected system/12/24-hour clock.
- Compact connection status and dashboard timestamps shrink to stay on one line when space is tight.

## 0.7.0 — 2026-10-12

This release brings the phone recording and customizable dashboard work together after the 0.4.1 release.

### Added

- A customizable Home with 15 widget types, drag and resize previews, per-scooter layouts, and separate folded/unfolded defaults. Existing custom dashboards remain intact.
- Last-known readings and settings, visibly stale after disconnect, plus connected telemetry history with a 50 MiB storage budget.
- Optional GPS, barometer and experimental motion journals for explicit ride recordings. Saved rides include local routes, estimated grade, acceleration views, and CSV/JSON/GPX exports.
- Optional OpenStreetMap tiles and a replaceable HTTPS map provider. Bluetooth control and recording continue to work without map services.
- Battery and charging widgets with reported health, live power, and a rough remaining charging time. The capacity experiment compares qualified recorded energy with percentage drop and advertised capacity.
- Scooter naming, a UT5 MAX image, concise connection status, system/12/24-hour clocks, and a two-minute live chart window.

### Improved

- Grouped scooter settings with all lighting controls together, clearer icons, less repeated text, consistent units, and search fields that release keyboard focus.
- Responsive cards, accessible edit controls, smooth layout previews, and matching card dimensions inside and outside edit mode.
- Correct Tail light naming for the verified command; automatic headlight behavior remains firmware-controlled.
- Reopening a widget search no longer inherits the previous keyboard session and immediately loses focus; normal Back and outside-tap dismissal are retained.
- Diagnostic exports and map requests identify the installed application version instead of a stale hardcoded version.

### Upgrade and limits

Install the signed release APK over the existing official build. The package and signing identity are unchanged; no uninstall or data reset is needed. Builds signed with a contributor's local key require that same key for in-place updates.

This is an independent app for an already activated, owner-bound UT5 MAX. Initial owner setup still uses the documented ADB capture/import flow. No owner credentials are included in the release.

Battery capacity, charging time, hill grade and pocket motion are estimates. Reported battery health is not a measured capacity test. Outdoor sensor accuracy and the newest features on Samsung still need field validation. The observed Boost toggles did not reveal a reliable active-state flag in the captured status packets; automatic Boost detection is not included. See VERIFICATION.txt for the exact validation scope.

## 0.4.1 — 2026-10-12

Initial repository release with offline Bluetooth controls, owner setup, telemetry, ride recording, portable builds, and the confirmed Samsung startup fix.
