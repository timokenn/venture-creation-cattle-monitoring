# Setup

Free tier end to end — Supabase free plan + Firebase Spark, no credit card.

No credentials are committed to this repo: the Android app reads its Supabase
config from the gitignored `android/local.properties` (step 5), Edge Function
secrets live in `supabase secrets` (step 2), and cron jobs read the URL/key
from Supabase Vault (step 1).

## 1. Supabase project + database

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

## 2. Edge Functions

```bash
supabase login
supabase link --project-ref <your-project-ref>   # the xxxxx in https://xxxxx.supabase.co
supabase functions deploy                        # deploys every function in supabase/functions/
```

Then set the secrets:

```bash
supabase secrets set WEBHOOK_SECRET=<random string>
supabase secrets set SERVICE_ACCOUNT_JSON=<contents of the Firebase service-account JSON>
supabase secrets set FCM_PROJECT_ID=<your Firebase project id>
```

## 3. Wire the Database Webhook

Dashboard → Database → Webhooks → **Create a new hook**:

- Table `readings`, event **Insert**, Edge Function **process-reading**.
- Add a custom header exactly as configured in the function:
  `x-webhook-secret: <same value as WEBHOOK_SECRET>`. Requests without it get
  a 401 — that's the check that stops strangers who find your function URL
  from feeding the classifier fake readings.

## 4. Firebase (push only — no Blaze required)

1. Create/use a Firebase project, add an **Android app** with package
   `com.example.cattlemonitor` (must match the app's `applicationId`).
2. Download `google-services.json` from the Firebase console into
   `android/app/` — it is gitignored, so it must be provided per machine
   (`google-services.json.example` shows the expected shape).
3. **Project settings → Service accounts → Generate new private key** — that
   JSON is the `SERVICE_ACCOUNT_JSON` secret above; the Firebase **project id**
   is `FCM_PROJECT_ID`.

## 5. Android app

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

### Testing the backend without the app

```bash
npx vitest run          # 52 tests: logic + real-SQL agreement (PGlite)
node tools/simulator/main.js list
```

The SQL-agreement suite loads the **actual `schema.sql`** into an in-memory
Postgres (PGlite) and asserts the SQL baseline formula, the CAS idempotency
guard, and alert dedup/resolve all behave exactly like the TypeScript logic.

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
