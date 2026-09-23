# Cattle Health Monitor 🐄

An Android app that shows live health and activity status for cattle, using
wearable ESP32 collars (MPU6050 accelerometer/gyro + DS18B20 temperature
probe).

Instead of fixed universal thresholds, the system learns **each cow's own
baseline** temperature and activity and flags deviations from it — catching
early signs of fever, estrus (heat), low activity, and distress that a fixed
threshold would miss.

## How it works

```
[ESP32 / simulator] → POST /rest/v1/readings → [Postgres]
        → [Database Webhook] → [process-reading Edge Function]
        → classify vs. the cow's own baseline (one atomic transaction)
        → cow status + alerts → [Android app, PostgREST + Realtime]
        → FCM push (deep link to the cow) on new alerts
```

Devices send raw sensor data only. All health logic — baselines, thresholds,
classification, alerts — runs server-side in Supabase Edge Functions, so it
can be tuned in one place without touching firmware or app.

## Features

- **Herd overview** — live status per cow: normal / warning / alert / offline
- **Per-cow detail** — temperature and activity trends against that cow's own baseline
- **Smart alerts** — fever, possible estrus, low activity, distress (failed-rise
  pattern), device offline; deduped, auto-resolving, delivered as FCM push
  notifications with deep links
- **Sensor-fault detection** — a frozen MPU6050 is flagged `sensor_issue`, never
  mistaken for a calm cow
- **Built-in simulator** — six scripted scenarios (fever, estrus, distress, …)
  to demo and tune the system without hardware

## Tech stack

| Layer | Tech |
|---|---|
| Device | ESP32 + MPU6050 + DS18B20 (Arduino) |
| Backend | Supabase — Postgres, Edge Functions, Realtime, pg_cron |
| Push | Firebase Cloud Messaging (FCM) |
| App | Kotlin + Jetpack Compose |
| Tests | Vitest, incl. a SQL↔TypeScript agreement test (PGlite) |

An optional bridge lets devices report through Firebase's Arduino library
(Firestore inbox) instead of direct REST — see [docs/FIRMWARE.md](docs/FIRMWARE.md).

## Quick start

1. Create a free Supabase project, run `supabase/schema.sql` then `supabase/cron.sql`
2. Deploy the Edge Functions, set secrets, wire the database webhook
3. Copy `android/local.properties.example` → `android/local.properties`, fill in your keys
4. Build the app in Android Studio (JDK 17) and run it on a phone
5. Feed it data: `node tools/simulator/main.js demo`

Full walkthrough with every command: **[docs/SETUP.md](docs/SETUP.md)**

## Testing

```bash
npx vitest run   # 52 tests — classification, baselines, real-SQL agreement
```

## Repository layout

```
supabase/schema.sql           # tables, constraints, RLS, RPCs
supabase/cron.sql             # offline-check + retention cron jobs
supabase/functions/           # Edge Functions (Deno/TypeScript)
supabase/tests/               # vitest suite incl. the SQL↔TS agreement test
functions/                    # legacy Firebase Cloud Functions (inactive)
tools/simulator/              # synthetic data feed for demos and tuning
android/                      # Kotlin + Jetpack Compose app
```

## Documentation

- [docs/SETUP.md](docs/SETUP.md) — full setup, simulator, free-tier notes, troubleshooting
- [docs/HEALTH-LOGIC.md](docs/HEALTH-LOGIC.md) — classification pipeline + threshold calibration
- [docs/FIRMWARE.md](docs/FIRMWARE.md) — ESP32 data contract (both paths), hardware notes
