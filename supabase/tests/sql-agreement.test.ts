import { beforeAll, describe, expect, it } from "vitest";
import { PGlite } from "@electric-sql/pglite";
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { nextBaseline } from "../functions/_shared/baseline.ts";
import { BASELINE_MIN_SAMPLES } from "../functions/_shared/thresholds.ts";
import { BASELINE_CASES } from "./fixtures.ts";

/**
 * The production baseline path is the SQL inside apply_reading_decision
 * (schema.sql), not nextBaseline() — so the suite must prove they agree.
 * This test loads the REAL schema.sql into an in-memory Postgres (PGlite,
 * no server needed) and runs the same fixture inputs through the RPC.
 *
 * Three-way check per case: SQL result === hand-computed expected ===
 * nextBaseline(). Drift in either implementation breaks this test.
 */

const here = path.dirname(fileURLToPath(import.meta.url));
const schemaSql = readFileSync(path.join(here, "../schema.sql"), "utf8");

const db = new PGlite();

beforeAll(async () => {
  await db.exec(schemaSql);
});

interface ApplyParams {
  readingId: string;
  cowId: string;
  readingTs: string;
  temperature: number;
  activityIndex: number;
  valid: boolean;
  dataQuality: string;
  statusAfter: string;
  alphaTemp: number;
  alphaActivity: number;
  minSamples: number;
  creates: Array<{ type: string; note: string; timestamp: string }>;
  resolves: string[];
}

async function applyDecision(p: ApplyParams): Promise<void> {
  await db.query(
    `select public.apply_reading_decision(
       $1::uuid, $2::uuid, $3::timestamptz, $4::float8, $5::float8,
       $6::boolean, $7::text, $8::text, $9::float8, $10::float8,
       $11::int, $12::jsonb, $13::jsonb, null::float8
     )`,
    [
      p.readingId,
      p.cowId,
      p.readingTs,
      p.temperature,
      p.activityIndex,
      p.valid,
      p.dataQuality,
      p.statusAfter,
      p.alphaTemp,
      p.alphaActivity,
      p.minSamples,
      JSON.stringify(p.creates),
      JSON.stringify(p.resolves),
    ],
  );
}

async function insertCow(fields: {
  deviceId: string;
  baselineTemp: number | null;
  baselineActivity: number | null;
  baselineSamples: number;
}): Promise<string> {
  const res = await db.query<{ id: string }>(
    `insert into public.cows (name, device_id, baseline_temp, baseline_activity, baseline_samples)
     values ($1, $2, $3, $4, $5) returning id`,
    [
      fields.deviceId,
      fields.deviceId,
      fields.baselineTemp,
      fields.baselineActivity,
      fields.baselineSamples,
    ],
  );
  return res.rows[0].id;
}

async function insertReading(cowId: string, temperature: number): Promise<string> {
  const res = await db.query<{ id: string }>(
    `insert into public.readings (cow_id, timestamp, temperature, accel_x, accel_y, accel_z, gyro_x, gyro_y, gyro_z)
     values ($1, now(), $2, 0, 0, 1, 0, 0, 0) returning id`,
    [cowId, temperature],
  );
  return res.rows[0].id;
}

async function cowRow(cowId: string) {
  const res = await db.query<{
    baseline_temp: string | null;
    baseline_activity: string | null;
    baseline_samples: number;
    latest_temp: string | null;
    current_status: string;
  }>(`select baseline_temp, baseline_activity, baseline_samples, latest_temp, current_status
      from public.cows where id = $1`, [cowId]);
  return res.rows[0];
}

async function alertsFor(cowId: string) {
  const res = await db.query<{ type: string; resolved: boolean; note: string }>(
    `select type, resolved, note from public.alerts where cow_id = $1 order by timestamp`,
    [cowId],
  );
  return res.rows;
}

describe("apply_reading_decision (real schema.sql on PGlite)", () => {
  it.each(BASELINE_CASES.map((c) => [c.label, c] as const))(
    "baseline agreement: %s",
    async (_label, c) => {
      const isTemp = c.kind === "temp";
      const cowId = await insertCow({
        deviceId: `agreement-${c.kind}-${c.samples}-${c.value}`,
        baselineTemp: isTemp ? c.prev : 38.5,
        baselineActivity: isTemp ? 1.0 : c.prev,
        baselineSamples: c.samples,
      });
      const readingId = await insertReading(cowId, isTemp ? c.value : 38.5);

      await applyDecision({
        readingId,
        cowId,
        readingTs: new Date().toISOString(),
        temperature: isTemp ? c.value : 38.5,
        activityIndex: isTemp ? 1.0 : c.value,
        valid: true,
        dataQuality: "ok",
        statusAfter: "normal",
        alphaTemp: c.alpha,
        alphaActivity: c.alpha,
        minSamples: BASELINE_MIN_SAMPLES,
        creates: [],
        resolves: [],
      });

      const row = await cowRow(cowId);
      const sqlValue = Number(isTemp ? row.baseline_temp : row.baseline_activity);
      const tsValue = nextBaseline(c.prev, c.samples, c.value, c.alpha);

      expect(sqlValue).toBeCloseTo(c.expected, 12);
      expect(tsValue).toBeCloseTo(c.expected, 12);
      expect(row.baseline_samples).toBe(c.samples + 1);
    },
  );

  it("CAS idempotency: a duplicate delivery cannot re-advance the baseline", async () => {
    const cowId = await insertCow({
      deviceId: "cas-dup",
      baselineTemp: 38.0,
      baselineActivity: 1.0,
      baselineSamples: BASELINE_MIN_SAMPLES,
    });
    const readingId = await insertReading(cowId, 40.0);
    const params: Omit<ApplyParams, "readingId"> = {
      cowId,
      readingTs: new Date().toISOString(),
      temperature: 40.0,
      activityIndex: 1.0,
      valid: true,
      dataQuality: "ok",
      statusAfter: "normal",
      alphaTemp: 0.005,
      alphaActivity: 0.005,
      minSamples: BASELINE_MIN_SAMPLES,
      creates: [],
      resolves: [],
    };

    await applyDecision({ ...params, readingId });
    const afterFirst = await cowRow(cowId);

    await applyDecision({ ...params, readingId }); // duplicate webhook delivery
    const afterSecond = await cowRow(cowId);

    expect(Number(afterSecond.baseline_temp)).toBe(Number(afterFirst.baseline_temp));
    expect(afterSecond.baseline_samples).toBe(afterFirst.baseline_samples);
    // and it did move exactly once, not zero times
    expect(Number(afterFirst.baseline_temp)).toBeCloseTo(38.0 + 0.005 * 2.0, 12);
  });

  it("invalid temperature: no baseline/sample/latest_temp changes", async () => {
    const cowId = await insertCow({
      deviceId: "invalid-temp",
      baselineTemp: 38.0,
      baselineActivity: 1.0,
      baselineSamples: BASELINE_MIN_SAMPLES,
    });
    const readingId = await insertReading(cowId, 38.0); // row inserted, then flagged suspicious by logic

    await applyDecision({
      readingId,
      cowId,
      readingTs: new Date().toISOString(),
      temperature: 50, // out of range → decide() stores valid=true + temp_out_of_range
      activityIndex: 2.0,
      valid: true,
      dataQuality: "temp_out_of_range",
      statusAfter: "normal",
      alphaTemp: 0.005,
      alphaActivity: 0.005,
      minSamples: BASELINE_MIN_SAMPLES,
      creates: [],
      resolves: [],
    });

    const row = await cowRow(cowId);
    expect(Number(row.baseline_temp)).toBe(38.0);
    expect(row.baseline_samples).toBe(BASELINE_MIN_SAMPLES); // unchanged — suspicious data never teaches baselines
    expect(Number(row.latest_temp)).toBe(50); // suspicious data IS shown (app flags it)
    expect(Number(row.baseline_activity)).toBeCloseTo(1.0 + 0.005 * 1.0, 12); // activity still advances
  });

  it("alert dedup + resolve through the JSONB plumbing", async () => {
    const cowId = await insertCow({
      deviceId: "alert-plumbing",
      baselineTemp: 38.0,
      baselineActivity: 1.0,
      baselineSamples: BASELINE_MIN_SAMPLES,
    });

    const r1 = await insertReading(cowId, 39.5);
    const ts = new Date().toISOString();
    await applyDecision({
      readingId: r1,
      cowId,
      readingTs: ts,
      temperature: 39.5,
      activityIndex: 0.5,
      valid: true,
      dataQuality: "ok",
      statusAfter: "alert",
      alphaTemp: 0.005,
      alphaActivity: 0.005,
      minSamples: BASELINE_MIN_SAMPLES,
      creates: [{ type: "fever", note: "Temp 39.5°C, 1.5°C above baseline", timestamp: ts }],
      resolves: [],
    });
    expect(await alertsFor(cowId)).toEqual([
      { type: "fever", resolved: false, note: "Temp 39.5°C, 1.5°C above baseline" },
    ]);

    // Same condition still active → dedup, no second row.
    const r2 = await insertReading(cowId, 39.6);
    await applyDecision({
      readingId: r2,
      cowId,
      readingTs: ts,
      temperature: 39.6,
      activityIndex: 0.5,
      valid: true,
      dataQuality: "ok",
      statusAfter: "alert",
      alphaTemp: 0.005,
      alphaActivity: 0.005,
      minSamples: BASELINE_MIN_SAMPLES,
      creates: [{ type: "fever", note: "Temp 39.6°C, 1.6°C above baseline", timestamp: ts }],
      resolves: [],
    });
    expect((await alertsFor(cowId)).filter((a) => a.type === "fever")).toHaveLength(1);

    // Condition clears → resolve the open alert by id.
    const openId = (
      await db.query<{ id: string }>(
        `select id from public.alerts where cow_id = $1 and type = 'fever' and resolved = false`,
        [cowId],
      )
    ).rows[0].id;
    const r3 = await insertReading(cowId, 38.1);
    await applyDecision({
      readingId: r3,
      cowId,
      readingTs: ts,
      temperature: 38.1,
      activityIndex: 1.0,
      valid: true,
      dataQuality: "ok",
      statusAfter: "normal",
      alphaTemp: 0.005,
      alphaActivity: 0.005,
      minSamples: BASELINE_MIN_SAMPLES,
      creates: [],
      resolves: [openId],
    });
    expect(await alertsFor(cowId)).toEqual([
      { type: "fever", resolved: true, note: "Temp 39.5°C, 1.5°C above baseline" },
    ]);

    // Condition returns later → a NEW alert row is created.
    const r4 = await insertReading(cowId, 39.5);
    await applyDecision({
      readingId: r4,
      cowId,
      readingTs: ts,
      temperature: 39.5,
      activityIndex: 0.5,
      valid: true,
      dataQuality: "ok",
      statusAfter: "alert",
      alphaTemp: 0.005,
      alphaActivity: 0.005,
      minSamples: BASELINE_MIN_SAMPLES,
      creates: [{ type: "fever", note: "recurrence", timestamp: ts }],
      resolves: [],
    });
    const fevers = (await alertsFor(cowId)).filter((a) => a.type === "fever");
    expect(fevers).toHaveLength(2);
    expect(fevers[1]).toEqual({ type: "fever", resolved: false, note: "recurrence" });
  });

  it("CHECK constraint: an out-of-range temperature cannot even be inserted", async () => {
    const cowId = await insertCow({
      deviceId: "check-temp",
      baselineTemp: 38.0,
      baselineActivity: 1.0,
      baselineSamples: 10,
    });
    await expect(
      db.query(`insert into public.readings (cow_id, temperature, accel_x, accel_y, accel_z, gyro_x, gyro_y, gyro_z)
                values ($1, 50, 0, 0, 1, 0, 0, 0)`, [cowId]),
    ).rejects.toThrow(/check/i);
  });
});

describe("apply_offline_transition (real schema.sql on PGlite)", () => {
  it("flags offline once (idempotent), repairs status, and resolves the alert", async () => {
    const cowId = await insertCow({
      deviceId: "offline-transition",
      baselineTemp: 38.0,
      baselineActivity: 1.0,
      baselineSamples: 10,
    });

    await db.query(`select public.apply_offline_transition($1::uuid, true, now())`, [cowId]);
    await db.query(`select public.apply_offline_transition($1::uuid, true, now())`, [cowId]);
    expect(await alertsFor(cowId)).toHaveLength(1);
    expect((await cowRow(cowId)).current_status).toBe("offline");

    await db.query(`select public.apply_offline_transition($1::uuid, false, now())`, [cowId]);
    expect((await alertsFor(cowId))[0].resolved).toBe(true);
    expect((await cowRow(cowId)).current_status).toBe("normal");
  });
});
