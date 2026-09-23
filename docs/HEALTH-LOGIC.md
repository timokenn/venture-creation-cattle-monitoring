# Health logic

How a raw sensor row becomes a status and (maybe) an alert. All constants live
in `supabase/functions/_shared/thresholds.ts` (the legacy Firebase copy in
`functions/src/thresholds.ts` must be kept identical — the parity test
enforces it).

## Pipeline

On every new reading (webhook → `process-reading`):

1. **Validate temperature** against 30–45 °C — enforced twice: as a Postgres
   CHECK constraint (a glitch can't even be inserted) *and* in `decide()`.
2. **Staleness check**: if the last N `activity_index` values are effectively
   identical, the reading is marked `data_quality: "activity_stale"` and is
   excluded from activity-based classification; a sustained staleness raises a
   deduped `sensor_issue` alert.
3. **Compute `activity_index`** — deviation of acceleration magnitude from 1 g
   plus a weighted gyro term. Magnitude-based (not per-axis), so it survives
   whatever orientation the device ends up mounted in.
4. **Update per-cow baselines** — running mean during warm-up, then EMA. The
   advance happens **inside the same SQL UPDATE as the status write**
   (`apply_reading_decision` RPC), which makes concurrent readings for one cow
   race-free, and a check-and-set on `readings.processed_at` makes duplicate
   webhook deliveries no-ops. Invalid temps and stale-activity readings never
   corrupt their respective baselines.
5. **Classify** (first match wins, always against *this cow's* baseline):

   | condition | temperature | activity |
   |---|---|---|
   | `fever` | ≥ FEVER_TEMP_DELTA_C above baseline | ≤ FEVER_ACTIVITY_FRACTION × baseline |
   | `possible_estrus` | ≥ ESTRUS_TEMP_DELTA_C above | ≥ ESTRUS_ACTIVITY_FACTOR × baseline, **sustained** across the window |
   | `possible_distress` | — | repeated spike-then-drop cycles (failed rises) |
   | `low_activity` | normal | ≤ LOW_ACTIVITY_FRACTION × baseline |

6. **Plan alerts** (deduped against open alerts; conditions clearing resolve
   them) and push via FCM **after** the transaction commits — a recorded-but-
   unpushed alert is recoverable; one lost to a push error is not.
7. **Update the cow row**: status, latest values, `last_seen` (the reading's
   timestamp, not processing time).

A pg_cron job runs `offline-check` every 15 minutes: cows with `last_seen`
older than `OFFLINE_FACTOR × EXPECTED_SEND_INTERVAL_SECONDS` get flagged
`device_offline`, and repair automatically when readings resume.

## `sensor_issue` vs `device_offline` — they are different faults

- **`device_offline`**: *no readings at all*. Device powered off, dead
  battery, or the cow wandered out of WiFi range. Raised by the scheduler.
- **`sensor_issue`** (`data_quality: "activity_stale"`): *readings keep
  arriving but the motion values are frozen* — a failing MPU6050, loose
  wiring, or firmware hang. The device looks alive while feeding garbage.
  Stale readings are treated as a data-quality fault (like out-of-range
  temperatures), not as evidence of health: a frozen sensor must never be
  classified as a "calm, low-activity cow".

## Threshold calibration — read before trusting alerts

Ship values are **placeholders**; classification only runs after
`BASELINE_MIN_SAMPLES` valid readings, which protects against cold-start
nonsense but not against wrongly calibrated deltas.

### Surface temperature is not core temperature

The DS18B20 probe on a wearable measures **skin/surface temperature**, which:

- runs **below** core body temperature (textbook cattle core temp is
  38.0–39.3 °C — do not use those numbers for the absolute ranges), and
- shifts with **ambient conditions** (sun, wind, mud) and **sensor placement**
  (behind ear vs flank vs udder differ by more than the fever delta).

Consequences for tuning:

1. The detectors compare each cow against **her own rolling baseline**
   (`baseline_temp`), so placement/ambient offsets that are roughly constant
   mostly cancel — this is why baseline-relative deltas are the right knobs,
   and why absolute textbook values are the wrong ones.
2. `FEVER_TEMP_DELTA_C` and `ESTRUS_TEMP_DELTA_C` must be calibrated against
   observed *surface* variation: collect several days of baseline readings
   per cow (simulator `seed`, then real devices when available), look at the
   diurnal spread, and set deltas clearly above that spread. If a cow's
   surface temp normally swings 0.7 °C/day, a 0.5 °C fever delta will fire
   constantly.
3. Re-check after any change of sensor placement — treat it as a new baseline.

### Suggested tuning workflow

1. Seed several days of `normal` data per cow; confirm zero alerts
   (`node main.js list`, check the Alerts screen).
2. Run each pathological scenario on a separate demo cow; verify each fires
   exactly the expected alert and nothing else.
3. If a detector is noisy → raise its delta/factor or its sustained-window
   fraction; if it's silent → lower them. Change `thresholds.ts` only.
4. `npx vitest run` after tuning: the suite imports the same constants, so
   behavior changes surface immediately.

Constant groups → what they control → exercising scenario:
staleness (`STALE_ACTIVITY_*`) → frozen-sensor detection → `stale_sensor`;
distress (`DISTRESS_*`) → failed-rise pattern → `distress`;
estrus (`ESTRUS_*`) → sustained heat window → `estrus`;
fever/low-activity (`FEVER_*`, `LOW_ACTIVITY_*`) → single-reading conditions →
`fever`, `low_activity`; offline (`EXPECTED_SEND_INTERVAL_SECONDS`,
`OFFLINE_FACTOR`) → scheduler → stop any stream.
