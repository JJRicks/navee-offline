# Ride auto-end

Unreleased, after Volt 1.3.1.

A ride left recording after the rider parks and walks away ends itself. The
owner's example: the scooter shows zero speed, Bluetooth disconnects and does not
come back within ten seconds. That is one conservative condition, not a rule
that every disconnection ends a ride.

## Decision

`RideAutoStopPolicy` is a pure class driven by explicit events (`connected`,
`disconnected`, `reset`, `due`) and the caller's monotonic clock
(`SystemClock.elapsedRealtime`). `ScooterService` feeds it on every BLE change
and every recording tick, so the decision runs in the service with the screen
off. No Activity timer is involved. Charges keep `ChargeRecoveryPolicy` and never
pass through this policy.

Evidence comes only from the current authenticated transport: the raw `0x91` /
`0x92` motion push (speed, driving status, receive time), the last-frame time,
the transport serial and the connection generation. Cached or last-seen display
readings are never used. Missing speed (-1) never becomes zero.

The policy arms only when all of these hold at the moment the link is lost:

| Rule | Value | Why |
| --- | --- | --- |
| Motion seen earlier in this ride | at least one fresh push with speed > 0 or driving ≠ 0 | A scooter that just powered on, or a service that restarted mid-ride, cannot look parked |
| Stationary run | ≥ 2 distinct pushes, speed 0 and driving 0, spanning ≥ 3 s, with nothing moving, missing or out of order in between | One zero is a moment at a light; a few seconds of zeros is a stop |
| Push freshness | each push ≤ 6 s old when seen; newest zero ≤ 6 s before the last frame heard | The same window `BleController.stationary()` uses for parked-only controls; zeros that stopped updating while other packets arrived are stale |
| Loss timing | loss seen ≤ 15 s after the last frame | A recording declares a silent link lost after 12 s, checked every 2 s, so walking out of range lands inside it |
| Loss kind | recoverable transport loss, permissions present, Bluetooth on | Owner rejection, disabled Bluetooth or a background pause are not parking |
| Identity | ride's profile is selected, bound profile and transport serial match the profile's saved serial | Another scooter's zeros are not evidence |

Once armed, the ride ends after **10 s of one continuous disconnection**. Any
authenticated reconnect cancels it (even before the serial is read: the scooter
is near). Separate short gaps never add up: every loss needs a new stationary run
on a new connection. New motion, a rider's Stop, explicit Disconnect, a profile
change, turning the preference off, a storage error, and a new ride all cancel
it. The policy is not persisted, so after a process restart it starts unarmed
and needs fresh motion and a new stop.

A clock that moves backwards resets the policy. A decision fires once; if saving
fails the ride stays recording with the existing "Retry save" state and is not
retried automatically.

Before acting, the service checks that the decision's policy instance is still
the current one and its ride ID is still the active ride, and that the scooter is
still disconnected. A late decision cannot close a newer ride.

## Stop and save

The ride ends through the same `stopRide` path as the Stop button and the
notification: storage error handling, phone GPS/sensor drain, wake-lock release,
work-policy and notification update. The stop time is the real time it fired;
nothing is backdated and no samples are added. A separate, auto-cancelling
notification says **Ride saved automatically**; a Toast shows if Volt is open.
Ride details show a **Saved automatically** pill.

The journal's existing `STOP` row stores `AUTO_PARKED_DISCONNECT` in its details
column (empty for a rider's stop, as before). Readers before this change ignore
the column, so journals, ride backups and full app backups stay compatible both
ways. `RideStore.Summary` exposes `stopReason` / `automaticStop`; analysis JSON
adds `stopReason` (`rider`, `automatic_parked_disconnect`).

## Learned range

Learned range normally rejects any ride with a gap or a stale end. An
automatically ended ride always closes with the silent link, one disconnect gap
and the grace, so it would never train. For those rides only, the screening
judges the ride at its last complete reading (speed, battery and odometer fresh)
when the final disconnection is its only gap and that tail is at most 60 s. The
tail contains no distance or battery use. Rides stopped by hand keep the existing
rules.

## Preference

**More → App preferences → End parked rides** (`ride_auto_stop`, default on).
Included in full app backups as a boolean.

## Automated checks

`tests/RideAutoStopPolicyTest`: stationary-drop timeout, reconnect just before
the deadline, repeated drops, moving and non-recoverable losses, startup zeros,
stale zeros, missing speed, out-of-order and future pushes, clock rollback,
another scooter/profile, explicit reset, failed save without retry, late
decisions after a new ride, process restart.

`tests/RideAutoStopStoreTest`: stop reason round trip, legacy journal meaning,
real stop time, backup staging inspection, learned range on an automatically
ended ride versus the same tail stopped by hand, long tails and earlier gaps,
streaming parity, failed save keeps recording.

## Still to validate

Emulator, synthetic recordings only, never induced scooter movement:

- Screen off: a simulated parked loss ends the ride after about 10 s, phone
  journals finish, the foreground notice goes away and **Ride saved
  automatically** appears; a following ride starts normally.
- Process interruption while disconnected: the recovered ride keeps recording
  and is not ended automatically.
- Reconnect inside the grace keeps the same ride.

Physical, with the owner present: how often the UT5 MAX sends `0x91`/`0x92`
while parked (parked-only controls already depend on it within 6 s), and the
real delay between walking away and Volt seeing the loss.
