import { Reading } from "../functions/_shared/types.ts";
import { BASELINE_MIN_SAMPLES } from "../functions/_shared/thresholds.ts";

export const MIN = 60_000;
export const HOUR = 60 * MIN;
export const NOW = new Date("2026-01-15T12:00:00Z").getTime();
export const BASELINE_ACTIVITY = 1.0;

export function reading(activity: number, ageMs: number, temp = 38.5): Reading {
  return {
    timestamp: new Date(NOW - ageMs),
    temperature: temp,
    accel: { x: 0, y: 0, z: 1 },
    gyro: { x: 0, y: 0, z: 0 },
    activity_index: activity,
  };
}

/**
 * Single source of truth for baseline fixtures: consumed by BOTH
 * baseline.test.ts (pure TS nextBaseline) and sql-agreement.test.ts (the
 * apply_reading_decision plpgsql formula). `expected` is hand-computed from
 * the documented algorithm — running mean until BASELINE_MIN_SAMPLES samples,
 * then EMA — so neither implementation is verified only against the other.
 * `kind` selects which baseline column the SQL test exercises.
 */
export interface BaselineCase {
  label: string;
  kind: "temp" | "activity";
  prev: number | null;
  samples: number;
  value: number;
  alpha: number;
  expected: number;
}

export const BASELINE_CASES: BaselineCase[] = [
  {
    label: "temp: first sample (null baseline snaps to value)",
    kind: "temp",
    prev: null,
    samples: 0,
    value: 38.2,
    alpha: 0.005,
    expected: 38.2,
  },
  {
    label: "temp: warm-up running mean (divisor samples+1)",
    kind: "temp",
    prev: 38.0,
    samples: 1,
    value: 38.4,
    alpha: 0.005,
    expected: 38.0 + 0.4 / 2,
  },
  {
    label: "temp: last warm-up step",
    kind: "temp",
    prev: 38.0,
    samples: BASELINE_MIN_SAMPLES - 1,
    value: 40.0,
    alpha: 0.005,
    expected: 38.0 + 2.0 / BASELINE_MIN_SAMPLES,
  },
  {
    label: "temp: first EMA step",
    kind: "temp",
    prev: 38.0,
    samples: BASELINE_MIN_SAMPLES,
    value: 40.0,
    alpha: 0.005,
    expected: 38.0 + 0.005 * 2.0,
  },
  {
    label: "temp: steady EMA (value below baseline)",
    kind: "temp",
    prev: 38.0,
    samples: BASELINE_MIN_SAMPLES + 500,
    value: 36.0,
    alpha: 0.005,
    expected: 38.0 - 0.005 * 2.0,
  },
  {
    label: "activity: first sample",
    kind: "activity",
    prev: null,
    samples: 5,
    value: 0.8,
    alpha: 0.02,
    expected: 0.8,
  },
  {
    label: "activity: EMA step",
    kind: "activity",
    prev: 1.0,
    samples: BASELINE_MIN_SAMPLES,
    value: 3.0,
    alpha: 0.02,
    expected: 1.0 + 0.02 * 2.0,
  },
];
