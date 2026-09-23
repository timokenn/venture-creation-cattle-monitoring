import {
  BASELINE_MIN_SAMPLES,
  DISTRESS_MIN_CYCLES,
  DISTRESS_RECOVERY_FRACTION,
  DISTRESS_SPIKE_FACTOR,
  DISTRESS_WINDOW_MINUTES,
  ESTRUS_ACTIVITY_FACTOR,
  ESTRUS_MIN_ELEVATED_FRACTION,
  ESTRUS_TEMP_DELTA_C,
  ESTRUS_WINDOW_HOURS,
  FEVER_ACTIVITY_FRACTION,
  FEVER_TEMP_DELTA_C,
  LOW_ACTIVITY_FRACTION,
  STALE_ACTIVITY_N_READINGS,
  STALE_ACTIVITY_TOLERANCE,
} from "./thresholds.ts";
import { Reading } from "./types.ts";

export type Classification =
  | "fever"
  | "possible_estrus"
  | "possible_distress"
  | "low_activity"
  | "normal";

/**
 * True when the last N activity_index values (including this one) are all
 * effectively identical — the signature of a frozen/stale MPU6050, not a
 * calm cow. Stale readings are excluded from activity-based classification.
 */
export function isActivityStale(
  current: Reading,
  previous: Reading[],
): boolean {
  if (current.activity_index === undefined) return false;
  const prior = previous
    .filter((r) => r.activity_index !== undefined)
    .sort((a, b) => b.timestamp.getTime() - a.timestamp.getTime())
    .slice(0, STALE_ACTIVITY_N_READINGS - 1);
  if (prior.length < STALE_ACTIVITY_N_READINGS - 1) return false;
  return prior.every(
    (r) =>
      Math.abs(r.activity_index! - current.activity_index!) <=
      STALE_ACTIVITY_TOLERANCE,
  );
}

/** Fever: temp notably above baseline while activity is below baseline. */
export function isFever(
  tempDeltaC: number,
  activityRatio: number,
  ready: boolean,
): boolean {
  return (
    ready &&
    tempDeltaC >= FEVER_TEMP_DELTA_C &&
    activityRatio <= FEVER_ACTIVITY_FRACTION
  );
}

/**
 * Estrus: temp slightly above baseline AND activity sharply above the cow's
 * own baseline, sustained across the window. A single spike never satisfies
 * the elevated fraction. "Now" is the newest timestamp in the window so the
 * function stays pure and testable.
 */
export function isPossibleEstrus(
  window: Reading[],
  baselineActivity: number,
  tempDeltaC: number,
  activityRatio: number,
  ready: boolean,
): boolean {
  if (!ready || baselineActivity <= 0) return false;
  if (tempDeltaC < ESTRUS_TEMP_DELTA_C) return false;
  if (activityRatio < ESTRUS_ACTIVITY_FACTOR) return false;
  const times = window.map((r) => r.timestamp.getTime());
  if (times.length === 0) return false;
  const now = Math.max(...times);
  const cutoff = now - ESTRUS_WINDOW_HOURS * 60 * 60 * 1000;
  const inWindow = window.filter((r) => r.timestamp.getTime() >= cutoff);
  if (inWindow.length === 0) return false;
  const elevated = inWindow.filter(
    (r) =>
      r.activity_index !== undefined &&
      r.activity_index >= ESTRUS_ACTIVITY_FACTOR * baselineActivity,
  ).length;
  return elevated / inWindow.length >= ESTRUS_MIN_ELEVATED_FRACTION;
}

/**
 * Failed-rise distress: repeated spike-then-drop cycles within the window,
 * with both levels judged relative to the cow's own baseline activity —
 * an animal struggling to rise, not lying calmly. Flat low activity (no
 * spikes) and a single spike with recovery must not trigger.
 */
export function isPossibleDistress(
  window: Reading[],
  baselineActivity: number,
): boolean {
  if (baselineActivity <= 0) return false;
  const times = window.map((r) => r.timestamp.getTime());
  if (times.length === 0) return false;
  const now = Math.max(...times);
  const cutoff = now - DISTRESS_WINDOW_MINUTES * 60 * 1000;
  const inWindow = window
    .filter((r) => r.timestamp.getTime() >= cutoff)
    .sort((a, b) => a.timestamp.getTime() - b.timestamp.getTime());
  if (inWindow.length < DISTRESS_MIN_CYCLES * 2) return false;

  const spikeLevel = DISTRESS_SPIKE_FACTOR * baselineActivity;
  const downLevel = DISTRESS_RECOVERY_FRACTION * baselineActivity;

  let spikes = 0;
  let wasDown = false;
  for (const r of inWindow) {
    if (r.activity_index === undefined) continue;
    const high = r.activity_index >= spikeLevel;
    const low = r.activity_index <= downLevel;
    if (wasDown && high) {
      spikes += 1;
      wasDown = false;
    } else if (low) {
      wasDown = true;
    }
  }
  return spikes >= DISTRESS_MIN_CYCLES;
}

/** Low activity: activity far below baseline with normal temperature. */
export function isLowActivity(
  tempDeltaC: number,
  activityRatio: number,
  ready: boolean,
): boolean {
  return (
    ready &&
    activityRatio <= LOW_ACTIVITY_FRACTION &&
    tempDeltaC < FEVER_TEMP_DELTA_C
  );
}

/** Baseline is only trusted after enough valid samples. */
export function baselineReady(samples: number): boolean {
  return samples >= BASELINE_MIN_SAMPLES;
}
