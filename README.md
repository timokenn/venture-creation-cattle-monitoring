# Cattle Health Monitor

Live health/activity monitoring for cattle using wearable ESP32 devices (MPU6050
accelerometer/gyro + DS18B20 temperature probe), **Supabase** (Postgres + Edge
Functions + Realtime) with **Firebase Cloud Messaging** for push only, and a
Kotlin/Compose Android app.

The core principle: **devices send raw sensor data only** — all health logic
(baselines, thresholds, classification, alerts) lives server-side (Edge
Functions), so it can be tuned in one place without touching firmware or app.

```
[ESP32 / simulator] → POST /rest/v1/readings → [Postgres]
        → [Database Webhook] → [process-reading Edge Function]
        → decide() + apply_reading_decision RPC (one atomic transaction)
        → cow status + alerts → [Android app, PostgREST + Realtime]
        → FCM push (deep link to the cow) on new alerts
```

## Repository layout

```
supabase/schema.sql           # tables, constraints, RLS, RPCs — run once
supabase/cron.sql             # offline-check + retention cron jobs — run once
supabase/functions/           # Edge Functions (Deno/TypeScript)
  _shared/                    #   pure health logic (thresholds, pipeline, …)
  process-reading/            #   webhook target: classify + persist + push
  offline-check/              #   15-min device_offline scan (pg_cron target)
  firestore-bridge/           #   OPTIONAL: ingest Firebase-Arduino devices
  register-cow|update-cow|delete-cow|register-token|fcm-test/
supabase/tests/               # vitest suite incl. the SQL↔TS agreement test
functions/                    # LEGACY Firebase Cloud Functions (inactive)
tools/simulator/              # synthetic data feed — test without hardware
android/                      # Kotlin + Jetpack Compose app
```

`functions/` is kept for the optional Firebase path but is **not part of the
active backend**; it still answers to the same shared test suite
(`npm --prefix functions test`) so its threshold copy can't silently drift.

## Setup (free tier, no credit card)

No credentials are committed to this repo: the Android app reads its Supabase
config from the gitignored `android/local.properties` (step 5), Edge Function
secrets live in `supabase secrets` (step 2), and cron jobs read the URL/key
from Supabase Vault (step 1).

### 1. Supabase project + database

1. Create a project at [database.new](https://database.new) (free tier is fine).
2. Open the **SQL Editor** and run `supabase/schema.sql` top to bottom.
3. Enable **pg_cron** and **pg_net** extensions (Dashboard → Database →
   Extensions), then run `supabase/cron.sql` **after replacing the two
   placeholder values in its section 1** (project URL + publishable key).
   The script refuses to run against unfilled placeholders, and re-running
   it updates the stored values — safe to run again any time.
4. Note your **Project URL** and **publishable ("anon") key** from the Connect
   panel — the simulator and app both need them. (New projects show the key
   as `sb_publishable_...`; older ones as a long `eyJ...` JWT — both work.)

### 2. Edge Functions

```bash
supabase login
supabase link --project-ref <your-project-ref>   # the xxxxx in https://xxxxx.supabase.co
supabase functions deploy                        # deploys every function in supabase/functions/
```

Deploy from the repo root with the Supabase CLI (`supabase login`,
`supabase link --project-ref <ref>` first). Then set the secrets:

```bash
supabase secrets set WEBHOOK_SECRET=<random string>
supabase secrets set SERVICE_ACCOUNT_JSON=<contents of the Firebase service-account JSON>
supabase secrets set FCM_PROJECT_ID=<your Firebase project id>
```

### 3. Wire the Database Webhook

Dashboard → Database → Webhooks → **Create a new hook**:

- Table `readings`, event **Insert**, Edge Function **process-reading**.
- Add a custom header exactly as configured in the function:
  `x-webhook-secret: <same value as WEBHOOK_SECRET>`. Requests without it get
  a 401 — that's the check that stops strangers who find your function URL
  from feeding the classifier fake readings.

### 4. Firebase (push only — no Blaze required)

1. Create/use a Firebase project, add an **Android app** with package
   `com.example.cattlemonitor` (must match the app's `applicationId`).
2. Download `google-services.json` from the Firebase console into
   `android/app/` — it is gitignored, so it must be provided per machine
   (`google-services.json.example` shows the expected shape).
3. **Project settings → Service accounts → Generate new private key** — that
   JSON is the `SERVICE_ACCOUNT_JSON` secret above; the Firebase **project id**
   is `FCM_PROJECT_ID`.

### 5. Android app

1. Copy `android/local.properties.example` to `android/local.properties` and
   fill in your project URL + publishable key. That file is **gitignored**,
   so credentials never enter the repo (`SUPABASE_URL` / `SUPABASE_KEY`
   environment variables work too). Gradle resolves config in that order:
   local.properties → env vars → `REPLACE-ME` placeholder.
2. Build with Android Studio (generates the Gradle wrapper on import), or run
   `gradle wrapper` once in `android/` then `./gradlew assembleDebug`.
   **JDK 17+ required** (Android Studio's embedded JDK works).
3. Grant the POST_NOTIFICATIONS permission on Android 13+ on first launch.

## Feeding data without hardware: the simulator

The simulator uses the same public endpoint + publishable key a real ESP32
will — it doubles as the firmware contract demo.

```bash
cd tools/simulator
export SUPABASE_URL=https://<ref>.supabase.co
export SUPABASE_KEY=<publishable key>

node main.js seed --cow bella --days 3        # healthy history (baselines + charts)
node main.js stream --cow bella --scenario fever --interval 30
node main.js list                             # cows + status
node main.js demo                             # 6 cows, one scenario each
```

Scenarios: `normal`, `fever`, `estrus`, `low_activity`, `distress`
(repeated failed-rise cycles), `stale_sensor` (frozen MPU6050),
`device_offline` (stop the stream).

`--seed-scenario` can seed pathological history too. Seeded readings are raw
inserts: the Database Webhook fires for each row, so baselines/status/alerts
build up exactly as they would from a real device (a 3-day backfill takes a
few minutes to churn through).

## How the health logic works

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

### Testing the backend without the app

```bash
npx vitest run          # 52 tests: logic + real-SQL agreement (PGlite)
node tools/simulator/main.js list
```

The SQL-agreement suite loads the **actual `schema.sql`** into an in-memory
Postgres (PGlite) and asserts the SQL baseline formula, the CAS idempotency
guard, and alert dedup/resolve all behave exactly like the TypeScript logic.

### `sensor_issue` vs `device_offline` — they are different faults

- **`device_offline`**: *no readings at all*. Device powered off, dead
  battery, or the cow wandered out of WiFi range. Raised by the scheduler.
- **`sensor_issue`** (`data_quality: "activity_stale"`): *readings keep
  arriving but the motion values are frozen* — a failing MPU6050, loose
  wiring, or firmware hang. The device looks alive while feeding garbage.
  Stale readings are treated as a data-quality fault (like out-of-range
  temperatures), not as evidence of health: a frozen sensor must never be
  classified as a "calm, low-activity cow".

## Threshold calibration — read before trusting alerts

All constants live in `supabase/functions/_shared/thresholds.ts` (the legacy
Firebase copy in `functions/src/thresholds.ts` must be kept identical — the
parity test enforces it). Ship values are **placeholders**; classification
only runs after `BASELINE_MIN_SAMPLES` valid readings, which protects against
cold-start nonsense but not against wrongly calibrated deltas.

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

## ESP32 firmware contract (for later)

The Supabase contract is deliberately tiny — one HTTP request:

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
  a lookup at boot (`GET /rest/v1/cows?device_id=eq.<id>&select=id`) cached in
  NVS, so the herd list is the single source of truth for the mapping.
- Retry on 5xx with backoff; on 4xx, log and drop the reading (it will never
  be accepted).

Hardware notes: retry MPU6050 init on a timer rather than only at boot
(unsoldered breakout header pins cause intermittent I2C), re-enter the retry
loop on mid-session read failure, and never use the MPU6050's internal
temperature channel anywhere in the pipeline — only the DS18B20 represents
the cow.

## Free-tier notes

- **Project pausing**: free projects pause after ~7 days without *database*
  activity. Real devices streaming never hit this; during development, any
  query (even opening the Table Editor) resets the clock. If the app suddenly
  can't connect after a quiet week, restore the project from the dashboard.
- **500 MB database cap**: at the default 30 s interval one cow writes ~2,900
  readings/day (~1.5 MB/day incl. indexes). The hourly retention cron deletes
  readings older than 90 days, which keeps six demo cows around ~1.5 GB of
  cumulative inserts before deletion — comfortable headroom. Increase the
  interval or shorten retention if you scale the herd.
- **FCM token pruning** (`UNREGISTERED` tokens): dead tokens are pruned at
  push time in `fcmAdmin.ts`, so there is no separate job needed. TODO (post-
  MVP): periodic sweep of tokens older than N months.

## Optional: devices that write to Firestore (Firebase Arduino library)

If the person writing firmware prefers `Firebase-ESP-Client` (Google's
Arduino library), devices may POST readings to a **flat Firestore
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

**Setup** (in addition to the standard setup above):

1. Deploy `firestore-bridge` (included in the deploy command above) — it
   uses the same `SERVICE_ACCOUNT_JSON` secret already needed for FCM.
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
   anonymous sign-in built in), then writes the raw fields below.

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

**Which path to choose:** direct-to-Supabase (one plain HTTPS POST, see the
contract above) is simpler, has no Google quota, and needs only standard
Arduino libraries. The Firestore path buys you `Firebase-ESP-Client`'s
convenience (auto-reconnect, token handling) at the cost of the quota
ceiling, one more moving part, and a minute of latency. Both feed the exact
same pipeline — you can even run a mix of devices on each.

## Troubleshooting

- **App shows data but never updates live** → the `supabase_realtime`
  publication is missing the tables. `schema.sql` adds them; verify with
  `select * from pg_publication_tables;`. The app also polls every 60 s as a
  fallback, so data still refreshes — just slower.
- **Webhook returns 401** → the `x-webhook-secret` header isn't set (or
  doesn't match `WEBHOOK_SECRET`) in the hook configuration.
- **409 on Add Device** → that `device_id` is already registered to another
  cow; the app surfaces this as "device already in use".
- **Seeded cows show `normal` but no latest values** → readings arrived but
  the webhook wasn't wired yet; trigger reprocessing by re-streaming.
