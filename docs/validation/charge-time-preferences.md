# Charge time preferences follow-up

Charge done-by time is a per-scooter local preference. Editing it requires a selected scooter but no connection, charger, estimate or Bluetooth write. During an active plan, the editor shows the next-use preference and the active toggle tile retains the actual armed deadline. An in-flight command transaction still blocks concurrent edits.

The manual start preference is stored separately from the programmed start. Charge start remains muted and locked while done-by is active, displaying the manual preference. Explicitly disabling done-by confirms schedule OFF, then confirms clock and manual-time restoration. It never turns Scheduled charging on. Expired manual times use their next occurrence. If OFF fails, the plan stays active; if restoration fails after OFF, the plan stays off and the app reports the partial result. Reconnect does not retry writes.

Persistence v2 retains the manual time. Legacy records supply it only when it can safely be identified as manual; a previously overwritten calculated time is not fabricated as a manual preference. When no saved manual time exists, disabling only cancels the schedule and the user can set Charge start once.

Validation covers offline and active preference edits without commands, retained manual time, unowned native readback, successful restoration, failures at each stage, reconnect/profile changes, legacy migration, and previously enabled manual schedules remaining off. The native fixture exercises the actual time pickers, disabled rows, dashboard tiles, simulator commands and controller callbacks at compact and unfolded sizes. It refuses physical phones and uses Bluetooth-disabled synthetic data.

Real scooter pause/start timing and power-cycle behavior remain physical validation items. This follow-up does not change the background connection policy or automatically recalculate a confirmed schedule.
