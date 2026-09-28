# ESP32 firmware

## Path A (default): direct to Supabase — one HTTP request

```
POST https://<project-ref>.supabase.co/rest/v1/readings
Headers:
  apikey: <publishable key>
  Authorization: Bearer <publishable key>
  Content-Type: application/json
  Prefer: return=minimal
Body (one reading):
{
  "cow_id": "<uuid from the cows table>",
  "timestamp": "2026-09-23T10:00:00Z",
  "temperature": 38.2,
  "accel_x": 0.02, "accel_y": -0.01, "accel_z": 0.99,
  "gyro_x": 0.5, "gyro_y": 0.0, "gyro_z": 0.1
}
```

- `temperature` must be within 30–45 °C (a Postgres CHECK rejects anything
  else with HTTP 400 — treat that as a sensor fault, not a network error).
- `timestamp` is the sampling time; the pipeline uses it (not write time), so
  an offline buffer can backfill with original timestamps. Cap the buffer
  (oldest dropped first) so an extended outage can't exhaust ESP32 memory.
- The device needs the **cow UUID**, not just the device id — simplest path is
  a lookup at boot (RPC `lookup_cow_id`, SECURITY DEFINER — survives the
per-owner cows SELECT RLS) cached in
  NVS, so the herd list is the single source of truth for the mapping.
- Retry on 5xx with backoff; on 4xx, log and drop the reading (it will never
  be accepted).

## Path B (optional): Firestore inbox via Firebase-ESP-Client

If the person writing firmware prefers `Firebase-ESP-Client` (Google's
Arduino library), devices may write readings to a **flat Firestore
collection** instead of PostgREST, and a bridge function feeds them into the
same pipeline. All health logic still lives in Supabase — Firestore is just
an inbox.

**Device contract** (collection `device_readings`, plain fields — the
library's `set()` with a JSON object does this directly):

```json
{
  "device_id": "esp32-bella",
  "timestamp": "2026-09-23T10:00:00Z",
  "temperature": 38.2,
  "accel_x": 0.02, "accel_y": -0.01, "accel_z": 0.99,
  "gyro_x": 0.5, "gyro_y": 0.0, "gyro_z": 0.1
}
```

The device writes raw sensor fields only — no classification, no status —
and does not need to keep any clocks accurate (the bridge never filters on
them).

**Setup** (in addition to the standard setup in [SETUP.md](SETUP.md)):

1. Deploy `firestore-bridge` (included in the deploy command) — it uses the
   same `SERVICE_ACCOUNT_JSON` secret already needed for FCM.
2. The `firestore-bridge` pg_cron job in `cron.sql` (step 4) invokes it
   every minute. **If you don't use this path, unschedule it:**
   `select cron.unschedule('firestore-bridge');` — a needless cron job burns
   free-tier invocations.
3. **Firestore rules + auth for the device** (Firebase console): enable
   **Anonymous** sign-in (Authentication → Sign-in method) and set these
   Firestore rules — the device may only *create* inbox docs, nothing else;
   the bridge reads/deletes with the service account, which bypasses rules:

   ```
   rules_version = '2';
   service cloud.firestore {
     match /databases/{database}/documents {
       match /device_readings/{doc} {
         allow create: if request.auth != null;
         allow read, update, delete: if false;
       }
     }
   }
   ```

   The device signs in anonymously once at boot (Firebase-ESP-Client has
   anonymous sign-in built in), then writes the raw fields above.

**How it works:** each run pulls up to 300 docs, maps them into `readings`
(duplicates impossible — `readings.source_ref` unique index + upsert
`ignoreDuplicates`), then deletes the ingested Firestore docs, so the
collection only ever holds unprocessed backlog. If the bridge is down for an
hour, catch-up takes ~2 minutes. Failed deletes are safe: the next run
re-pulls and dedup absorbs them.

**Free-tier math:** Firestore's free tier allows 20k writes/day and 1 GiB.
Six cows at 30 s ≈ 17.3k writes/day — fits, but it *is* the ceiling; at a
20 s interval you'd exceed it. Deletes are free-ish and keep storage bounded.
The bridge adds ≤1 minute of ingestion latency, which for cattle physiology
is nothing.

**Which path to choose:** direct-to-Supabase (one plain HTTPS POST) is
simpler, has no Google quota, and needs only standard Arduino libraries. The
Firestore path buys you `Firebase-ESP-Client`'s convenience (auto-reconnect,
token handling) at the cost of the quota ceiling, one more moving part, and a
minute of latency. Both feed the exact same pipeline — you can even run a mix
of devices on each.

## Hardware notes

- Retry MPU6050 init on a timer rather than only at boot — cheap breakout
  boards often ship with unsoldered header pins, which causes intermittent
  I2C (device detected on some boots, missing on others).
- Re-enter the retry loop on mid-session read failure; never send stale or
  zeroed accel/gyro values after a lost sensor.
- The MPU6050's internal temperature channel is **never** used anywhere in
  the pipeline — it reflects the chip's die temperature, not the cow. Only
  the DS18B20 reading belongs in the `temperature` field.
- The breakout board used in the reference build carries its own 4.7 kΩ
  pull-up for the DS18B20; a bare 3-wire probe needs an external one.
- The activity index is magnitude-based, so it is mount-orientation
  independent — but if you later add per-axis behavior classification
  (grazing vs walking vs rumination), that requires a per-enclosure
  calibration pass first.

## Battery level (from the decow-app design drop)

Collars include `battery_level` (0–100, percent) in each reading. The firmware
reads a voltage divider (100k/100k from the Li-ion cell into GPIO 34) via
`analogReadMilliVolts`, oversamples 8x, and maps 3.3–4.2 V to 0–100%. The app
shows a battery indicator on each cow card once a value has been reported.

## Hardened ingest (ingest-reading function)

The open anon INSERT on `readings` was revoked. Collars now POST to
`/functions/v1/ingest-reading` with:

- header `x-collar-key: <COLLAR_INGEST_KEY>` — shared device secret
  (`supabase secrets set COLLAR_INGEST_KEY=...`; value kept in
  `android/local.properties` as `collar.ingest_key`)
- body `{ device_id, temperature, accel:{x,y,z}, gyro:{x,y,z}, battery_level }`
  — the server resolves `device_id -> cow`, stamps the timestamp, and a
  per-device token bucket (burst 5, 1 per 600 s refill) absorbs floods.

Response is 200 `{ok:true}` (then the usual webhook pipeline runs) or
401/404/429 with a plain-text reason.
