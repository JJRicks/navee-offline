# Changelog

## Unreleased

- New default Home for fresh installs, new scooter profiles and **Restore default widgets**: Scooter overview, Lock bar directly beneath it, Reading tiles, Setting tiles and Ride recorder. Existing dashboards and saved slots are unchanged.
- Add four widgets: **Lock bar** (one-row lock with Connect; connected, the slider is the card), **Scooter overview** (charge, range, photo, charge bar and readings), **Reading tiles** and **Setting tiles** (one to four small cards). Every earlier widget stays available in Add widget, and the All widgets example includes the new ones.
- The Home status line now shows the last-seen time (newer of the last connection and the newest reading); the separate "Last seen" line under the header is gone.
- While editing Home, card content dims behind solid, high-contrast editor handles. The change animates in a short staggered fade (handles first when leaving), reverses smoothly if toggled mid-way and applies instantly with system animations off. A card being moved or resized returns to full opacity until it is dropped.

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
