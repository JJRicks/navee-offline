# Volt user guide

Volt is an independent community project, not affiliated with, endorsed by or sponsored by NAVEE.

For installation, compatibility and quick setup, start with the [README](../README.md). Details on the handlebar fallback, electrical readings, data gaps and background behavior are in the [connection and recording reference](recording-reference.md). [Feature availability](../FEATURES.txt) lists per-control evidence and the supported scope.

## App preferences and UI preview

Open **More → App preferences → Units** for distance/speed, temperature and time format. These settings and Appearance use rounded selectors with a sliding highlight; some narrow controls scroll sideways. Celsius/Fahrenheit changes displayed readings and chart/gauge scales; stored telemetry stays in Celsius. Map settings and Background recording guidance also live in App preferences.

Choose the range source under **Scooter → Range estimate**: **Scooter's estimate** (the default, following drive mode) or **Volt's estimate (learned)**. The choice is saved separately for each scooter. [Learned range](#range-learned-from-your-rides) uses that scooter's completed recordings for a preliminary dashboard estimate.

For UI testing, open **More → About & permissions** and tap the **Volt version seven times**. **Developer options** then appears in More. Its **Simulate scooter as connected** switch supplies sample readings, local control responses and generated chart previews. A persistent sample-data banner includes **Exit**. Simulation closes the real Bluetooth connection, cannot start a ride or phone sensors, and never saves fake telemetry into your history, last-seen cache or battery analysis. Finish any active recording before enabling it. It is temporary and turns off when the service/app process restarts; normal profile and dashboard settings remain yours. Use **Hide developer options** to hide the menu again.

## Back up and restore

**More → About & permissions → Your data → Back up & restore** is the one place for backups. Choose **Back up** or **Restore**; Volt disconnects and pauses until you use **Close Volt** and reopen it, including after cancelling. Finish recordings, pending saves and setup cleanup first.

**Back up** lists seven categories with counts: **Scooters & setup keys** (private owner credentials), **Dashboards** (both form factors, all slots, widget options and notes), **App preferences** (units, appearance, text size, favourites, map choice), **Rides** (raw scooter journals with GPS, motion, names, notes and gaps), **Charges**, **Readings history** (last-seen readings and retained rollups) and **Scooter preferences** (app-side range and charger choices, never firmware settings). Pick any subset, or **Select all** for a complete backup. Any backup other than App preferences alone also lists your scooters' names and serials, so a restore can match them. Create a password of at least eight characters; every backup is an encrypted `.voltbackup` file, and the password can't be recovered. Save it outside Volt. Android permissions, battery exemptions, Bluetooth pairing, Shizuku access, pending operations and caches are never included.

**Restore** accepts a `.voltbackup` (new or from older versions), a `.voltrides` ride backup or a dashboard layout file. Pick the file, enter the password if it is encrypted, then choose categories. Restore merges by default: settings categories take the backup's values, scooters, rides and charges are added without duplicates, history is combined with this phone's readings winning, and anything not selected stays exactly as it was. A complete backup also offers **Replace everything on this phone**, which is on by default after **Clear storage** or on a new phone. If the backup names a scooter this phone doesn't have, choose where its data goes: one of your scooters, **Add from backup** (with Scooters selected) or **Don't restore**. A matching serial is chosen for you. The review lists every change and anything skipped before you tap **Restore**.

After Android **Clear storage**, use **Restore a backup** on the welcome screen; no setup or Bluetooth permission is needed. **Rides → Ride backups** opens the same screen with Rides selected. Restoring never sends settings to the scooter; normal Bluetooth reads establish its current state, and learned range, capacity and charge curves are recomputed from the restored data. Restore uses a private recovery copy; if it is interrupted, keep app storage intact and reopen Volt to recover. Backups support up to 2 GiB and need extra free space for staging and recovery.

**Home → Edit → Layout → Export/Import** still shares the selected scooter's dashboard and three slots as a plain JSON layout file, without setup keys or readings.

**Developer options → Load All widgets into slot 3** replaces that scooter’s slot 3 after confirmation. It leaves the active dashboard and other two slots unchanged. This lets existing users explicitly load the latest example without an update overwriting a saved layout.

Technical format and recovery details: [App backups](app-data-backup.md), [Unified backup design](unified-backup.md).

## Make Home your own

Tap the **pencil** to edit Home. Drag the top-left dots to move a widget, or drag its visible lower-right corner to resize it; a long press on the dots also starts a move. A dashed outline previews the full destination while neighboring cards slide aside and fill empty rows. Releasing saves the preview; cancelling restores the starting arrangement. Tap **Done** to use scooter controls again.

Each card has matching square controls: the **move grip**, **gear** for options, **copy** for Duplicate, and a red **trash icon** at the right for Remove. The icons stay centered. Compact cards arrange the controls in two rows so the targets stay usable. Below 96 dp, a narrow Quick access rail keeps separate Move and Options targets; Options opens Edit, Duplicate and Remove actions. On a very narrow one-row card, tapping the move grip opens Move, Edit, Duplicate and Remove; dragging it still moves the widget, and the resize corner keeps its own touch target. The options sheet keeps its **X** at the top-right and has just **✓ Save** in its fixed footer. The move, options and duplicate controls stay grouped on the left, with trash at the right. For a drag-free alternative, tap the dots for **Move** or the corner for **Size**; the redundant Size row is removed from widget options. Widget menus, handles and the scooter-name pencil appear only while global dashboard editing is on. Dialogs follow the app's light/dark theme.

Curved **Undo/Redo** arrows share a rounded control immediately left of **Done**. On compact screens, this action row sits below the scooter name. Undo keeps up to 40 moves, resizes, additions, deletions and duplicates during the current editing session, retaining the widgets' latest settings. A new layout change clears Redo; cancelled gestures, unchanged actions and settings-only edits do not. Finishing editing, leaving Home or recreating the screen ends this temporary history. Restoring defaults or a saved layout starts fresh; use the saved-layout slots to keep arrangements beyond an editing session.

**Layout → Columns** offers **Adaptive** or one to six columns in a rounded selector with a sliding red highlight. Swipe the selector sideways if it does not fit. Adaptive retains the familiar one- through four-column choices. An explicit five-column choice stays available on narrow cover screens with at least 280 dp of dashboard width (about a 316 dp screen with the usual side padding); six columns become available at 480 dp of dashboard width. If a six-column choice moves to a smaller supported window, it uses five rather than jumping to Adaptive. Layouts remain separate for each column count. New five/six-column arrangements start from the saved four-column layout where available, without resetting it. In six columns, a four-column scooter card beside a two-column lock card gives a two-thirds/one-third split. On five-column cover layouts, most widget types use smaller content-width floors and tighter padding to preserve placement. Text fitting and compact editor controls use the space available; Quick access retains its touch-target floor, and larger text may still require wider cards. Saved arrangements remain intact. Dashboard gaps sit only between cards, with matching left and right page margins.

Editing keeps each widget and its content at the same dimensions as normal viewing. Translucent controls float over the card, so entering or leaving edit mode does not expand cards or rearrange their contents.

Entering and leaving edit mode briefly slides the dashboard and pops its controls into place. Visible widgets have a very subtle wiggle while editing; the card being dragged or resized stays steady. The wiggle keeps its 0.2° amplitude with a quicker 620 ms cycle, and stops when the app loses focus. Saving a newly added off-screen widget gently scrolls it into view and briefly outlines it in white; touching the page stops the cue. With reduced motion, positioning is immediate and the outline is steady.

Cards animate their size and position together during layout changes. Removal has a brief shrink-and-fade with soft puffs while the other cards can move immediately. Short transitions also follow deliberate page changes, button presses, and confirmed lock-state updates. They respect Android's disabled-animation setting; movement never substitutes for the scooter's confirmed lock state.

Navigation uses consistent short fades and glides; dialogs rise smoothly and
map content/tiles appear without abrupt layout jumps. Restored pages start at
their saved scroll position, and scrollbars appear when you scroll rather than
flashing on each settings rebuild. Transitions become immediate when Android
animations are disabled.

Subtle haptics confirm the moments that benefit from them:
- switching tabs or segmented choices, or choosing a Top speed preset;
- flipping a switch or star, or sending an accepted Quick access toggle;
- picking up and moving a dashboard widget or favourite;
- the lock slider crossing its midpoint;
- the scooter confirming a lock change, or a ride starting or being saved;
- a successful scooter connection while Volt is focused (a two-part thunk), or
  a disconnect (three fading ticks).

Ordinary taps and scrolling stay quiet. **More → App preferences → Haptic feedback** turns them off (on by default), and Android's own Touch feedback setting still applies.

Connection cues stay silent during simulation, when another window has focus,
and when returning to the app after a connection changed in the background.
The first connection and loss cue are immediate. After a drop, rapid reconnects
and repeated losses stay quiet for five seconds; another loss restarts that
quiet period. Expiring the period never replays a missed cue or delays Bluetooth.
Devices without the required vibration primitives use a short fallback. The
normal Android VIBRATE permission enables these effects without an extra prompt.
Quick access feedback marks an accepted command; the displayed setting still
waits for the scooter's readback.

Half-folding a foldable into book or tabletop posture leaves the screen exactly as it is, with no reload, re-split or replayed animation. Only a physical hinge that hides part of the screen moves content away from it.

**Edit → Layout → Saved layouts** offers three named slots for each scooter.
Save captures every widget and its options, the column choice, and all saved
one- through six-column arrangements together, including folded and unfolded
layouts. You can restore defaults or experiment with the current dashboard
without changing the slots, then restore a slot to return to that setup. Saving
over an occupied slot and restoring a slot ask for confirmation. Save your
current dashboard in another slot before replacing it if you want to keep both.
Column choice is shared across all scooters on this phone; restoring a slot
also restores that shared choice without replacing other scooters’ widgets.
Slots are local to this phone and scooter; they contain layout settings, not
readings, recordings, or owner credentials. Even an empty dashboard can be saved.

Slot 3 receives an **All widgets** example only if no saved slot 3 exists for
that scooter. Adding the example never overwrites a saved slot, including an
intentionally empty layout. The example includes all 19 widget types with sensible parameters
and one- through six-column arrangements. Open **Edit → Layout → Saved layouts**
and restore **All widgets** to try it; save your current dashboard in another
slot first if you want to return to it. Adding the example does not activate it
or change your current column choice.

The 19 choices are Lock & connection, Your scooter, Battery overview, Big reading, Gauge, Live trend, History chart, Reading list, Trip overview, Battery health, Capacity experiment, Charging, Quick access, Ride recorder, Note, Lock bar, Scooter overview, Reading tiles, and Setting tiles. Customize titles, parameters, reading lists, colors, chart windows, and gauge ranges where applicable. Up to 32 widgets are allowed, with one lock control (Lock & connection or Lock bar).

**Lock bar** is a one-row lock: offline it shows the lock state and **Connect**; connected, the tap-or-slide lock switch fills the whole card and shows Locked or Unlocked. **Scooter overview** shows charge, range, the scooter photo, a charge bar and up to four readings. **Reading tiles** shows one to four readings as small cards, and **Setting tiles** one to four Quick access settings with their current values; the first setting tile is highlighted while connected. These cards grey last-seen values; the time is in the Home status line.

While editing, card content dims so the move, options and resize handles stand out; a card returns to full opacity while you drag or resize it. New widgets remain drafts until **Save**; closing without saving leaves the dashboard unchanged. A recognized UT5 MAX photo remains visible while disconnected. Existing custom layouts are preserved; add widgets through Edit or restore defaults.

**Quick access:** open its gear → **Quick settings** to choose two to eight ordered shortcuts. Tap an item to replace it, use the up arrow to move it earlier, or add/remove items. Ordinary scooter toggles work directly; controls such as kick-start speed and energy recovery open their normal editors above Home. Scooter groups and app-preference menus open temporary panels; Stats and Rides remain page shortcuts. Existing live-state, parked, security and experimental-control checks still apply. Buttons wrap their labels and adapt their padding to fit four items cleanly at moderate card heights; short cards remain scrollable. Saved slots and duplicates retain the chosen actions.

New dashboards and **Layout → Restore default widgets** use the Home preset: Scooter overview, Lock bar directly beneath it, Reading tiles (power, pack temperature, health), Setting tiles (top speed, charge schedule), Ride recorder and a four-row Capacity experiment. The preset retains five columns on supported narrow cover screens, including the Galaxy Fold 5 at its default display settings. Only windows with less than 280 dp of dashboard width use fewer columns. Existing current layouts, custom options and all three saved slots are preserved by the update; restoring defaults is an explicit action.

**Your scooter** places a larger photo on the left and stacks up to 12 configurable readings vertically on the right. A newly added card starts with odometer and drive mode; the full default dashboard also includes estimated range. Values grow into the available space, with a moderate size cap and automatic shrinking in compact cards. Reported charges and battery throughput remain in Stats and can still be selected for a custom widget. The former four-reading default is updated once to these two readings; other custom lists and saved card positions/sizes are preserved. New scooter cards use four rows and can shrink to three. Use the pencil beside its name while editing Home to rename the local profile. On the UT5 MAX (PID 2538), owner-observed mode codes 11, 2, and 3 display as WALK, ECO, and SPORT; other codes and models retain raw labels. Raw diagnostics and ride exports remain unchanged. The battery cell uses a compact **%** label.

Live SPORT is pale blue in the dark theme and a readable darker blue in the light theme; ECO is green, and WALK uses a green pedestrian icon. Last-known modes stay grey, with their mode names retained for accessibility.

Card contents adapt as you resize: rows stack at narrow widths, values fit within readable font limits, and photos, battery cells, gauges and charts use the available space. Short content stays balanced within its card instead of gathering at the top; saved card positions and sizes stay intact. Swipe anywhere within overflowing widget content to scroll it. Your scooter, Lock & connection and Gauge keep practical minimum heights. A wide Ride recorder can use one row; narrow Quick access rails can use three rows while retaining separate editing targets.

**Gauge** uses a 270° ring around a larger centered value and unit, with space below for the minimum and maximum labels. Parameter formatting and grey stale readings retain their existing behavior.

The battery overview uses a tall red battery cell with percentage and estimated range, with power, reported health and temperature readings underneath. Narrow cards and large text wrap readings rather than hide values. While charging, a separate card above the widgets shows the scooter, progress to the charge limit, estimated time and battery-side power. The glow under the scooter breathes slowly while it is on screen; with reduced motion it stays still. Last-known readings stay grey and retain their timestamps.

Lock & connection is a full-width bar: tap anywhere on it or slide it to lock or unlock. When locked, the white knob sits on the right; when unlocked, the red knob sits on the left. A moving knob leaves a brief faint trail (none with reduced motion); the switch still shows the scooter's confirmed state. A **Disconnect** button is shown while connected by default; turn off **Show Disconnect button** in this widget's options to hide it. This option never hides **Connect** or **Cancel** while disconnected or connecting, when the card shows the last verified lock time. The connection line in each page header shows Connected, Connecting or Disconnected with the last-seen time; tap it for details and to connect or disconnect. **More → App preferences → Units → Time format** follows the phone by default, or selects 12-hour/24-hour clocks for displayed times.

The Live trend widget supports a **2 minutes** window, including for battery power. Chart widgets also offer **Relative/Absolute** Y-axis scaling; percentage axes stay within 0–100, and signed power/current can center on zero. Redundant “signed” and “Recent readings • live” labels are removed from the chart presentation; the underlying values and recorded sign remain unchanged. Stats also omits repeated instructions while retaining technical explanations that affect interpretation.

Scooter, Lights, Stats, and the widget parameter picker’s search fields release focus when the keyboard is dismissed or you tap outside, keeping the current filter. Consistent inner padding and space between search, filters and results make these controls easier to scan.

Charts need numeric readings actually supplied by the scooter. Unsupported or text-only selections have no numeric graph. Reported battery health is the controller's value, **not a measured capacity test**.

## Capacity experiment

The **Capacity experiment** widget compares recorded electrical energy with the battery gauge's percentage drop: `100 × summed Wh ÷ summed percentage points used`. It analyzes the selected scooter's explicit ride recordings, including the current recording when it qualifies, and refreshes about every ten seconds while viewed. The active ride replaces its previous contribution. It starts no recording, connection, GPS, or sensors of its own.

With current direction confirmed throughout each accepted ride, the widget uses net energy drawn minus returned. Rides with unconfirmed direction form a **separate absolute-throughput proxy**; that counts both draw and return and may overstate energy depletion. The two groups never mix, and each needs at least 20 battery percentage points before showing a result. The percentage beside either result compares it with the advertised energy; **it is not battery health or a measured capacity percentage**.

Each ride must last at least one minute, include 30 seconds of measured movement, use at least five battery percentage points, and have at least 95% electrical coverage. Battery and power readings must align with both ends. Gaps, recovery, detected clock/direction changes, missing end readings, and visible gauge rebounds above one point exclude a ride. Sampled trends can miss brief rebounds. These are experimental screening rules, not a validated capacity test. Record ordinary riding without charger input.

The window defaults to **90 days**, with 7, 30, 90, 180, 365, and 730 days available. Older rides fall out of the calculation without being deleted. Set a custom advertised energy in Wh in the widget's options; zero uses the known model rating. The UT5 MAX reference is **716.04 Wh (46.8 V × 15.3 Ah)** from [NAVEE's US specifications](https://naveetech.us/products/ut5-max). Other models need an entered reference for a percentage comparison.

Cold, load, voltage range, gauge rounding and recalibration can shift this estimate. State of charge can represent charge rather than energy, so different sections of the same healthy pack can produce different Wh equivalents. The widget reports available pack temperatures and flags near-full/near-empty readings without applying a correction. [Texas Instruments' battery-gauging comparison](https://www.ti.com/lit/an/sluaar3/sluaar3.pdf) explains why proper capacity learning also accounts for gauge behavior and operating conditions; it is an engineering reference, not evidence about which gauge NAVEE uses. This experiment cannot prove that the pack is undersized.

## Charging readings

The battery overview automatically switches to separate **Charging power** and **Time to full** tiles while the scooter freshly reports charging. Normal readings return when charging is no longer freshly reported. The separate **Charging** widget remains available. Power uses paired battery voltage/current and measures the **battery side**, not outlet consumption or charger efficiency. Live power is red for draw and green for charging; stale readings are muted.

The fallback time estimate uses reported rated Ah, the remaining percentage and the current charging rate. A known charge limit becomes the target; missing, stale or unpaired inputs leave affected readings unavailable. Tapering near full can make the current-rate estimate optimistic.

**UT5 MAX stock and high-speed estimates work without recordings or Developer options.** Volt includes experimental curves from observed charges. The high-speed curve models its earlier taper and long final 1%; the stock curve keeps its own finishing behavior. Bulk time uses rated Ah and live current, with the appropriate finishing time included from the start. Other models and incompatible readings retain the current-rate fallback. Lower cutoffs include only time to that cutoff, and the experimental ride-capacity widget does not change charging estimates.

**Scooter → Battery → Charger for estimates** offers **Auto / Stock / High-speed**. Auto is the default for scooters without an existing manual choice. On the UT5 MAX, it infers the charger from several fresh readings and remembers it for that charge, including through the taper and brief reconnects. Connecting near full, reconnecting at 96% or above without fresh readings for the remembered charger, reconnecting a high-speed charge above 85% at power a stock charger can supply, or returning after more than two minutes without readings, can leave the charger uncertain; Volt then uses the live-rate fallback. On other models, Auto uses the live-rate fallback; choose Stock or High-speed to use that charger's recordings. The row shows the charger in effect and why. You can choose Stock or High-speed explicitly. Existing manual choices stay saved per scooter. This setting changes estimates only, never charger output. Stop an active charge recording before changing it.

**More → Developer options → Charge recording** still asks you to confirm **Stock** or **High-speed**; under Auto you must pick one. That label applies to the recording and its active estimates; it does not change your saved estimate preference. Automatic guesses never label training data. Qualified sessions learn separate curves for each scooter/label; matching personal evidence refines the default over time. Neither method is a promised completion time or capacity test. The initial high-speed reference comes from one charge and remains provisional. [Automatic charger selection and evidence](validation/automatic-charger-estimates.md).

A continuous personal recording that reaches displayed 100% can refine the estimate with a **provisional finishing estimate after one charge**, within its fully observed battery-percentage range. During a long 99% plateau, a clear recorded power taper helps the estimate progress before the percentage changes. Short intervals with almost constant power give an approximate midpoint estimate. Unavailable or incompatible personal evidence leaves the default intact; it is never counted as a user's recording. Estimates end at the scooter's displayed 100%, so balancing may continue afterward. [Finishing-estimate evidence and limits](validation/charge-finishing-estimates.md).

Charge recording saves bounded local samples every ten seconds. After a temporary Bluetooth drop it keeps its Stop notification and retries for up to five minutes, even when ordinary background connection is off. It resumes the same recording only after the same scooter authenticates and sends fresh charging readings. Missing intervals are marked as gaps and excluded from learned charging curves. Completion, Stop, an explicit Disconnect, a profile change, disabled Bluetooth, lost permissions, fatal connection failure or recovery expiry ends capture. Closing or restarting the app process does not silently resume it. **Export charge recordings** saves the selected scooter's versioned JSON through Android's file picker, including original electrical readings, timestamps, gaps and the manual charger label. Export requires capture to be stopped and excludes owner credentials, names, MAC addresses, PINs and locations.

**More → Developer options → Charge recordings** opens saved sessions. Switch between **Time remaining** (a good prediction falls about one minute per minute) and **Predicted finish** (a steady prediction stays horizontal). Tap or drag the graph to inspect the original estimate, its source/version and its error against observed completion. Expand the graph for more room. A lower saved charge limit has its own view and is not compared with the 100% finish.

New samples save the same estimator result used by the dashboard, even with the screen off. Old recordings keep their original readings but have no saved estimates. The blue comparison means the recording observed the scooter reach displayed 100% through a fresh, continuous final interval; it does not claim that cell balancing finished. Interrupted sessions, or reconnecting after the charge was already full, have no observed finish line. Graphs use elapsed time anchored to the session's starting clock so phone-clock adjustments do not create false prediction drift. [Data format and validation](validation/charge-estimate-history.md).

### Charge done by

In **Scooter → Battery**, choose **Charge done by**, then enable its switch while the scooter is plugged in and freshly reporting charging. Volt uses the current estimate to your selected charge limit, adds a ten-minute buffer, and saves a start time on the scooter. For example, an 8 AM deadline with a 90-minute estimate schedules charging for 6:20 AM. If there is no time to wait, Volt requests charging now instead. An unavailable estimate leaves the schedule unchanged.

This is a **one-time schedule for the next occurrence of that time**, not a daily alarm. Once the change is confirmed, you can disconnect or close Volt; the scooter's own clock handles the start. Volt does not stay connected, keep recalculating, or change your background-connection preference. The estimate remains approximate. Some models only accept whole-hour starts; Volt rounds earlier on those models.

While Charge done by is on, turn it off before changing the manual Scheduled charging controls. You can turn it off while connected even during the scheduled pause. Initial activation needs fresh charging readings; otherwise tapping the grey switch says **Connect a charger first**. You can edit the finish-time preference while offline; it applies the next time you enable the switch. While connected and active, editing the time recalculates and confirms a new start immediately. During a scheduled pause, Volt reuses its saved estimate only when fresh battery level and charge limit still match. If it needs fresh charging data, it explains what to do; it does not cycle charging automatically. Neither time edits nor reconnecting cause ongoing adjustment or a background connection hold. The manual Charge start row stays locked while done-by is active and keeps showing your last manual time. Turning done-by off cancels its schedule and restores that manual time without enabling Scheduled charging. If restoration cannot be confirmed, Volt tells you; it does not retry automatically on reconnect. Add either the switch or its time editor to **Setting tiles** or **Quick access** through the widget's options.

Volt remembers a confirmed schedule for each scooter when firmware readback is missing or resets after a restart. On reconnect, fresh evidence can retire that remembered state: the charge target was reached, the start passed without charging, the deadline passed, or charging started early. A changed charge limit removes the old calculated deadline but keeps the actual start as a manual schedule. These checks never send a command or rearm a schedule. Confirming a schedule requires the scooter's acknowledgement and available readback; after an interrupted change, reconnect and check its state before trying again.

## Last-known readings and connected history

Last-known stats and settings stay on the phone for the selected scooter. Grey values are historical; a grey lock state is unverified until you reconnect. Only fresh authenticated scooter state enables setting changes.

Connected history starts enabled and saves scooter readings while connected, without starting a ride, GPS, or phone sensors. Its total storage budget is **50 MiB** across profiles: detailed samples for up to seven days, minute summaries for up to 30 days, and daily summaries for up to two years. Older samples can be trimmed sooner to stay within the budget. Use **More → Data & history** to inspect storage, stop new timeline samples, or clear one scooter's history or all history. Turning history off retains the last-known cache; clearing removes both its selected timelines and cache. Ride recordings and owner setup are separate.

The usual ten-second idle background timeout is unchanged. History does not prolong a connection or turn an ordinary connection into a ride recording.

## Live Stats and range estimates

**Readings** keeps its dynamic reading count, search and categories; the redundant favourites-only filter is removed. **Charts** uses the same searchable categories and offers every numerical parameter retained for the selected scooter or currently reported. One **Window** picker applies to all charts: 2 minutes, 10 minutes, 1 hour, 6 hours, 24 hours, 7 days, 30 days or All. All eight choices appear together, including on narrow screens. Stars add charts to **Overview**, where readings and charts share a reorderable favourites list. Tap the Reorder icon, then drag a row by its handle; dropping saves the order, while a cancelled drag restores the starting arrangement. Screen-reader Move up/Move down actions remain available. Each graph expands for inspection. Only visible charts load data; missing intervals remain gaps, and older retained history may be summarized.

Selecting **Scooter → Range estimate → Scooter's estimate** enables following drive mode. Volt selects the supported firmware range estimator when fresh state confirms the scooter is stationary. Missing Eco support falls back to the firmware's D-mode estimator. These firmware readings use the scooter's prediction; they do not measure usable capacity or automatically detect Boost. Unconfirmed automatic attempts are bounded across reconnects.

## Range learned from your rides

Open **Scooter → Range estimate** and choose **Volt's estimate (learned)** to use your selected scooter's completed rides for its dashboard range. **Scooter's estimate** is the default, and the choice is saved separately for each scooter. Learning stays on this phone and uses only recordings associated with that scooter, including restored rides. Volt ships no other rider's range baseline.

Learning needs at least **three usable rides, 20 battery percentage points and 3 km recorded within the last 90 days**. Short, incomplete or otherwise unsuitable recordings may be excluded. Once Volt's estimate is selected, the picker shows the current forecast, for example **About 10 km at 50% · likely 8–12 km**. Until enough history is available, **Volt's estimate (learned)** is greyed but still tappable: tapping it explains briefly that more recorded rides are needed. The first usable selection shows a short introduction. The picker shows a loading state while reading saved rides and a retry message if they cannot be read.

When a learned estimate or the battery level is unavailable, the widgets use the scooter's estimate. A last-known battery level keeps the resulting estimate grey and historical.

The choice applies to every current-range dashboard widget, including reading tiles and gauges, and the range headline in **Live Stats → Overview**. Readings and favourites explicitly named **Scooter range**, recorded charts and exported telemetry retain the scooter's reported range. Charts keep their original source throughout the recording. Choosing **Volt's estimate (learned)** changes the local display only; selecting **Scooter's estimate** enables the guarded drive-mode following described above.

When your rides recorded the scooter's power, Volt splits the forecast in two. It learns how much energy each battery percentage point holds at each part of the gauge (points near empty hold less, because the pack voltage is lower). It also learns how much energy your riding uses per km. The forecast is the energy left at the current battery level divided by that rate. Below the lowest level you have ridden to, Volt assumes 10% less energy per point than the least it has seen. Rides with a short recording gap still count toward energy per km. The **likely** range shows how far your past 6 km stretches of riding fell short of or beat the forecast (5th to 95th percentile), once there are at least eight such stretches. Scooters without power readings use the earlier method: total distance divided by total battery-percentage drop, applied to the displayed battery level. Treat the result as a **preliminary estimate for similar riding**, not a guaranteed distance to empty or a capacity/health measurement. Hills, speed, payload, weather and battery-gauge rounding can change actual range. [Calculation, screening rules and validation](validation/learned-range.md).

## Lights

All lighting controls are under **Scooter → Lights**. The former “Headlight” switch is correctly named **Tail light**: OEM code identifies that command as the tail-light setting. **Automatic lights** uses ambient brightness to control the headlight and tail light. The app confirms configuration readback, not visible light output. No separate main-headlight command is verified; use the scooter's power button for manual front-light control. The OEM describes manual headlight changes as temporarily suspending automatic control until the scooter restarts.

## First setup and everyday use

Each owner needs their own Bluetooth owner identity from their already-bound NAVEE account. First capture can use the guided [Chrome/USB helper](https://pairscooter.jjricks.com/), the optional **Use Shizuku on this phone** route on Android 11+, or the existing computer ADB/Python helper. Daily use needs none of these helpers. No owner credentials are embedded in this repository or APK. The [browser helper documentation](../setup/web/README.md) explains its explicit consent stages, local processing, recovery, and self-hosting.

The Shizuku route guides installation/start, explicit permission for broad debugging access, capture consent, and a separate import confirmation. It accepts shell-backed Shizuku 13+ only, reads fresh authentication frames from NAVEE's verified Android UID, restores the previous logging setting and stops NAVEE before making the identity available. The identity remains in memory until an explicit import; it is not uploaded or copied to Downloads. A detached safety guard and non-secret cleanup journal handle interruption. See [setup/SHIZUKU.md](../setup/SHIZUKU.md) for its permissions, lifecycle and limitations. Never run the legacy computer capture helper simultaneously with another capture method.

The Chrome/USB helper remains a testing preview in this app release. Controlled phone checks of Chrome/USB and Shizuku exercised timeout and cleanup with the scooter out of range. A tester later reported successful onboarding without identifying which helper was used, so successful real-scooter capture is not yet confirmed separately for each new path. The computer ADB helper is the established capture route. A developer can also run the static browser helper locally using the linked instructions. Browser capture currently supports the main personal Android user only, not work profiles or secondary users.

On first launch the app opens a step-by-step setup: choose the desktop helper, Shizuku, the Chrome/USB preview, import a backup file or enter a 12-character identity, then follow the selected route through capture, import, Nearby devices and connecting. On wide and unfolded screens, a step list appears beside the current numbered step. The tab bar appears once setup is finished. **More → My scooters → Run setup again** reopens it. These helpers use NAVEE already signed in on the phone; they add no NAVEE or Google account sign-in to this app or the setup website.

See [SETUP.txt](../SETUP.txt) for the capture helper and private backup/import instructions.

## Connect and use the lock

On Android 12+, allow Nearby devices. Android 8–11 instead needs precise Location
permission for Bluetooth scanning/connection, even if ride GPS is off. Close
NAVEE so it releases Bluetooth before connecting in Volt. Automatic launch
connection can reconnect to the last scooter; it never changes the electronic
lock by itself.

The lock switch needs a fresh authenticated connection and confirmed lock state.
Dragging sends the requested change without an extra confirmation dialog; the
displayed result follows the scooter's response. A connection failure does not
replay a pending setting change. Retry Connect after checking power/range;
Disconnect ends the local session. See the [connection reference](recording-reference.md)
for background timeout and handlebar fallback behavior.

## Other NAVEE models

Volt is verified on the US UT5 MAX only. Other standard NAVEE scooters connect with the same owner setup and show live readings, battery data, ride recording and the lock.

- **Lock:** locking needs fresh status, the scooter stopped and a recent motion report; unlocking needs only a fresh connection, so you can always unlock offline. The switch moves only after the scooter confirms.
- **Limited controls:** the Scooter page shows a short banner and hides controls Volt has not mapped for your model. Saved Quick access tiles stay in your layout and show “—”.
- **Identify:** when the model is unknown, tap **Identify** in the banner. Volt reconnects through a short scan to read the model ID the scooter advertises. It sends no scooter command and is unavailable while recording.
- **Identified models** also get dashboard units, range estimate, clock sync, lights, turn sound, cruise and energy recovery where the official app maps them for that model, plus its ride, battery and security settings (traction control, assists, charging, lock duration, PIN and proximity unlock, and others). These show **Untested here**: they are mapped from the official app, not tested by Volt. Controls vary by model; some need the scooter's firmware version, read when it connects.
- **Asks first:** turning off traction control, Low Battery Mode or dual motor drive asks you to confirm on these models, as the official app does.
- **Charging start** on these models is set on the hour, up to 48 hours ahead.
- **Speed settings** use the widest choices mapped from the official app for your model, regardless of region. Model and firmware requirements still apply, and the scooter may reject a value. Volt never probes for higher speeds or changes firmware. A kick-start speed below 3 km/h shows a caution first.
- **Lights** on these models follow the official app too: line and star lights (with more colours on some models), or ambient, logo and daytime running lights with Off, On or Follow headlight.
- **Estimates:** UT5 MAX charge curves, rated capacity, drive-mode names and range following are not applied to other models. Charge estimates use live readings only.
- Golf carts, e-bikes and other non-scooter products get no scooter commands.
- **Compatibility check:** **More → Compatibility** (or **Check** in the banner) shows the model ID, its source, region, firmware and which rules apply. **Run check** asks the scooter for nine known readings, one at a time, for up to 30 seconds, and lists which it answered, rejected or ignored, which status fields it reports and which controls Volt offers. It changes no setting and sends no clock or PIN request; while it runs, other changes are refused until it finishes, except the lock, which stops the check first. **Preview report** shows exactly what would be shared; nothing leaves the phone until you tap **Share** or **Save file**. The report has no serial, scooter address, owner key, PIN, names or routes.
- **Try a setting:** on the same screen you can test one reversible setting: dashboard units, tail light, turn signal sound, automatic lights or range estimate (dashboard units only when the model is not identified). Choose a value; Volt reads the current one and shows it with the test value, what to look for and that it will set it back. Nothing changes until you tap **Change to …**. The scooter must be parked (if it has sent no motion report yet, tick **Scooter is parked**) and no ride may be recording. Volt then asks what you saw (**Yes**, **No** or **Skip**), sets the setting back and checks it. Speed, charging, lock, PIN, proximity, clock and Lab settings are never tested this way. A result goes in the report only; it does not turn the control on for your model.
- **Unfinished tests:** if the connection drops or Volt closes during a test, Volt never finishes it on its own. The Scooter page shows **Unfinished test** until you review it under **More → Compatibility**: it says whether the setting is already back, still on the test value or changed some other way, and offers **Restore**, **Keep** or **Dismiss**. Kept and dismissed tests are recorded as not restored by Volt. Test records stay on this phone and are not part of backups.

## Phone recordings and maps

**More → About & permissions → Location** opens Volt’s Android app settings. Choose **Permissions → Location** there to manage precise/approximate access.

Start from **Rides → Start recording** while the app is visible. Recording starts directly with a **Date - Time - Drive mode** name, using your clock-format preference; an unavailable mode is omitted. You can rename it afterward with the pencil in ride details. Choose precise location for a useful route; denying location still permits scooter and motion recording. GPS and phone sensors stop when the recording stops. If you park and walk away with a ride still recording, Volt ends it once the scooter had reported a stop and Bluetooth stays lost for 10 seconds; the notification says **Ride saved automatically** and the ride shows **Saved automatically**. Drops while moving keep recording. Turn this off with **More → App preferences → End parked rides**. Active ride/charge capture uses an immediate ongoing notification with Stop, foreground-service protection and a renewed bounded CPU wake lock. **More → App preferences → Background recording** explains notification/battery settings. The normal ten-second idle timeout still applies outside recordings. Android may let an ongoing notification be dismissed while unlocked; force stop, lost Bluetooth, and manufacturer battery policies remain limits.

The phone journal is separate from the scooter energy accumulator. GPS continues through a Bluetooth gap. Missing or poor fixes split the route; grade uses a distance window, preferably barometric elevation, with stricter limits for GPS altitude. Implausible altitude excursions are excluded from derived elevation, grade and ascent while raw measurements are retained. GPS and scooter speed derivatives are separate from the phone's raw and gravity-free acceleration. Pocket movement, vibration, pressure changes, and GPS error remain limitations.

**Phone sensors** offers GPS, motion recording and **Pocket / Mounted** placement.
Placement is recorded as context; it does not calibrate the phone's axes to the
scooter. Raw acceleration includes gravity; linear acceleration removes gravity;
earth-frame east/north/up components require fresh orientation data. Speed-derived
GPS/scooter acceleration is separate and is expressed in m/s². Phone movement and
bumps can dominate the sensor readings. Route comparison shows measured distance,
ascent/descent and energy; it does not plan or declare an optimal route.

Saved rides bring route, phone sensors and scooter readings together. The expandable graph supports power, wheel/GPS speed, voltage, battery, relative elevation, GPS acceleration and instantaneous energy per distance. Tap or slide to inspect original values and actual sample times, pinch to zoom, and pan with two fingers or the time labels. Each series has its own scale. Inline graphs use a lightweight preview; fullscreen loads the full committed recording asynchronously, including short peaks skipped by the preview. Loading failures retain the preview and offer Retry. Rename with the pencil beside the ride title. New rides use positive current as battery draw; older recordings retain their original interpretation.

Route maps offer power, speed, grade, GPS acceleration and elevation overlays. Speed uses the scooter's reading when the ride has it for at least half the route or more of it than GPS, otherwise GPS; the legend and tooltip say which. Colors run from 0 to a top speed set from that ride, so they stay put while you pan and do not change with units; faster moments use the top color. Dashed gray marks points without a reading. The expand icon opens a full-screen route map; a separate center icon fits the whole route. Start a touch on or near the route to inspect it, then drag to follow readings—even if your finger leaves the line. Start away from the route to pan, tap away to clear the reading, or use two fingers to zoom and pan. These gestures work in both views. Opening or closing the expanded map preserves its center, zoom, selected overlay and inspected point.

**Basemap** sits directly below the map in both views. It starts off if no choice has been saved; an existing explicit choice is kept. The first time you enable it, a short explanation says the map provider receives your IP address and viewed area. Choose **Enable basemap** to continue or **Cancel** to keep tiles off. Your choice and acknowledgement are remembered. The inline map, full-screen map and **More → App preferences → Map settings** use the same switch state. Local route geometry and recording work with it off.

Map settings also selects the provider: USGS Topo, OpenStreetMap or custom HTTPS. USGS Topo is the default provider and supplies U.S. roads/terrain without an account or key, with attribution and cached viewport visits. Selecting a provider does not enable tiles. Close zooms enlarge level-16 tiles rather than requesting unsupported detail. [USGS terms](https://www.usgs.gov/faqs/what-are-terms-uselicensing-map-services-and-data-national-map) and the [OpenStreetMap tile policy](https://operations.osmfoundation.org/policies/tiles/) apply to their respective providers. Denied or `X-Blocked` responses pause requests and never replace the local route with an error picture.

Map requests disclose the viewed area and IP address to the selected provider; credentials and ride journals are not uploaded. No city/route prefetch or bulk download is offered.

Old recordings have no phone route to reconstruct. Exported route/phone files contain coordinates; raw motion includes device axes, gravity-free acceleration, earth-frame acceleration when orientation is available, and rotation quaternion. GPX exports positions/time only because Android GNSS altitude is referenced to an ellipsoid; altitude and its accuracy remain in CSV/JSON. Raw journals remain available if a large file cannot be previewed.

## Validation limits

- Emulator checks and owner reports do not establish behavior on every phone, scooter or firmware. See [VERIFICATION.txt](../VERIFICATION.txt) for executed checks and remaining physical test limits.
- Investigate the telemetry field `energy_throughput_wh`, displayed as **Energy counter (unverified)**. Observed values can decrease on this firmware, so its lifetime-counter meaning is unverified. Do not use its delta for battery-capacity conclusions. RideStore's sampled voltage/current integration is separate.
- Three short real ride recordings have been checked for electrical integration consistency. A controlled full usable-capacity or range test remains outstanding. Reported health and rated Ah are not capacity measurements.
