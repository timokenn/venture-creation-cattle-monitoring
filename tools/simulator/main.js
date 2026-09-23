#!/usr/bin/env node
/**
 * Cattle Monitor Simulator (Supabase edition)
 *
 * Writes synthetic ESP32 readings to the `readings` table via Supabase's
 * auto-generated REST API (PostgREST), authenticated with the project's
 * publishable key — the exact trust level and endpoint a real ESP32 uses,
 * so this doubles as the firmware contract demo.
 *
 * Usage:
 *   node main.js seed --cow bella --days 3
 *   node main.js seed --cow bella --days 3 --seed-scenario distress
 *   node main.js stream --cow bella --scenario fever --interval 30
 *   node main.js demo                          # full demo walkthrough
 *   node main.js list                          # list registered cows
 *   node main.js help
 *
 * Env (required):
 *   SUPABASE_URL      e.g. https://abcd.supabase.co
 *   SUPABASE_KEY      publishable (anon) key
 */
import { PROFILES, SCENARIOS } from "./scenarios.js";

function parseArgs(argv) {
  const args = { _: [] };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--seed-scenario") {
      args.seedScenario = argv[++i];
    } else if (a.startsWith("--")) {
      args[a.slice(2)] = argv[++i];
    } else {
      args._.push(a);
    }
  }
  return args;
}

const args = parseArgs(process.argv.slice(2));
const command = args._[0] ?? "help";

const SUPABASE_URL = (process.env.SUPABASE_URL ?? "").replace(/\/$/, "");
const SUPABASE_KEY = process.env.SUPABASE_KEY ?? "";

if (!SUPABASE_URL || !SUPABASE_KEY) {
  console.error("Set SUPABASE_URL and SUPABASE_KEY (publishable key) in the environment.");
  process.exit(command === "help" ? 0 : 1);
}

const HEADERS = {
  apikey: SUPABASE_KEY,
  Authorization: `Bearer ${SUPABASE_KEY}`,
  "Content-Type": "application/json",
  Prefer: "return=representation",
};

const DEFAULT_INTERVAL_S = 30;
const BATCH_SIZE = 500;

function usage() {
  console.log(`Commands:
  seed    --cow ID [--days 3] [--interval 60] [--seed-scenario normal]
          Backfill history so baselines + charts have data.
  stream  --cow ID [--scenario normal] [--interval 30]
          Stream live readings for a scenario until Ctrl+C.
  demo    Seed healthy history for six cows, then stream one scenario each.
  list    List cows and current status.
  help    This message.

Scenarios: ${SCENARIOS.join(", ")}`);
}

async function api(path, init) {
  const res = await fetch(`${SUPABASE_URL}/rest/v1/${path}`, { headers: HEADERS, ...init });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`${init?.method ?? "GET"} ${path} → ${res.status}: ${text}`);
  }
  return res.status === 204 ? null : res.json();
}

async function ensureCow(cowId, name) {
  const existing = await api(`cows?device_id=eq.esp32-${cowId}&select=id,name`);
  if (existing.length > 0) return existing[0];
  const created = await api("cows", {
    method: "POST",
    body: JSON.stringify({
      name: name ?? cowId,
      device_id: `esp32-${cowId}`,
      current_status: "normal",
    }),
  });
  console.log(`created cow ${cowId}`);
  return created[0];
}

function resolveProfile(scenarioName) {
  const profile = PROFILES[scenarioName];
  if (!profile) {
    console.error(`unknown scenario "${scenarioName}"; options: ${SCENARIOS.join(", ")}`);
    process.exit(1);
  }
  return profile;
}

function toRow(cowId, sample, at) {
  return {
    cow_id: cowId,
    timestamp: at.toISOString(),
    temperature: sample.temperature,
    accel_x: sample.accel.x,
    accel_y: sample.accel.y,
    accel_z: sample.accel.z,
    gyro_x: sample.gyro.x,
    gyro_y: sample.gyro.y,
    gyro_z: sample.gyro.z,
  };
}

async function writeReadingsBatched(cowId, items) {
  for (let i = 0; i < items.length; i += BATCH_SIZE) {
    const chunk = items.slice(i, i + BATCH_SIZE).map(({ sample, at }) => toRow(cowId, sample, at));
    await api("readings", { method: "POST", body: JSON.stringify(chunk) });
  }
  const last = items[items.length - 1];
  if (last) {
    await api(`cows?id=eq.${cowId}`, {
      method: "PATCH",
      body: JSON.stringify({ last_seen: last.at.toISOString() }),
    });
  }
}

async function seed(cowId, days, intervalS, scenarioName) {
  const cow = await ensureCow(cowId);
  const scenario = scenarioName ?? "normal";
  const profile = resolveProfile(scenario);
  const total = Math.floor((days * 24 * 3600) / intervalS);
  console.log(`seeding ${days}d of ${scenario} for ${cowId}: ${total} readings…`);
  const start = Date.now() - days * 86_400_000;
  const items = [];
  for (let i = 0; i < total; i++) {
    const at = new Date(start + i * intervalS * 1000);
    items.push({ sample: profile(Math.floor((at.getTime() - start) / 1000)), at });
  }
  await writeReadingsBatched(cow.id, items);
  console.log(`done: ${total} readings written for ${cowId}`);
  console.log("note: seeded readings are raw inserts — run a stream to see live classification, or let the webhook process them.");
}

function stream(cowId, scenarioName, intervalS) {
  resolveProfile(scenarioName);
  console.log(`streaming ${scenarioName} for ${cowId} every ${intervalS}s (Ctrl+C to stop)`);
  let tick = 0;
  let cowUuid = null;
  const timer = setInterval(async () => {
    const at = new Date();
    try {
      if (cowUuid === null) {
        const cow = await ensureCow(cowId);
        cowUuid = cow.id;
      }
      const sample = PROFILES[scenarioName](tick);
      await api("readings", {
        method: "POST",
        body: JSON.stringify(toRow(cowUuid, sample, at)),
      });
      console.log(`  [${at.toISOString()}] T=${sample.temperature.toFixed(2)}°C`);
    } catch (err) {
      console.error(String(err?.message ?? err));
      clearInterval(timer);
      process.exit(1);
    }
    tick++;
  }, intervalS * 1000);
  process.on("SIGINT", () => {
    clearInterval(timer);
    console.log("\nstopped");
    process.exit(0);
  });
}

async function listCows() {
  const cows = await api("cows?select=id,name,current_status,last_seen,baseline_temp&order=name");
  if (cows.length === 0) {
    console.log("no cows registered");
    return;
  }
  for (const c of cows) {
    const last = c.last_seen ? c.last_seen : "never";
    const bt = c.baseline_temp !== null ? Number(c.baseline_temp).toFixed(2) : "?";
    console.log(
      `${String(c.name).padEnd(14)} ${String(c.current_status).padEnd(9)} last=${String(last).padEnd(24)} baseline_temp=${bt}`,
    );
  }
}

async function demo() {
  console.log("=== Cattle Monitor demo walkthrough ===");
  const herd = [
    { id: "demo-normal", scenario: "normal" },
    { id: "demo-fever", scenario: "fever" },
    { id: "demo-estrus", scenario: "estrus" },
    { id: "demo-lowact", scenario: "low_activity" },
    { id: "demo-distress", scenario: "distress" },
    { id: "demo-stale", scenario: "stale_sensor" },
  ];
  for (const cow of herd) {
    await seed(cow.id, 2, 60, "normal");
  }
  console.log("--- history seeded; now streaming scenarios live ---");
  herd.forEach((cow) => stream(cow.id, cow.scenario, DEFAULT_INTERVAL_S));
}

if (command === "seed") {
  await seed(args.cow, Number(args.days ?? 3), Number(args.interval ?? 60), args.seedScenario ?? "normal");
} else if (command === "stream") {
  stream(args.cow, args.scenario ?? "normal", Number(args.interval ?? DEFAULT_INTERVAL_S));
} else if (command === "demo") {
  await demo();
} else if (command === "list") {
  await listCows();
} else {
  usage();
  process.exit(command === "help" ? 0 : 1);
}
