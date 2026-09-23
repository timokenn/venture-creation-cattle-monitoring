import { BASELINE_MIN_SAMPLES } from "./thresholds.ts";

/**
 * Incremental baseline update — one value in, one value out, no history
 * reprocessing. Plain running mean until BASELINE_MIN_SAMPLES valid readings
 * have been seen, then exponential moving average. Classification gates on
 * sample count (see baselineReady) so early averages are never trusted.
 *
 * NOTE: the SQL twin of this exact formula lives in ../../schema.sql
 * (apply_reading_decision). The vitest agreement test in ../../tests proves
 * both stay identical — if you change either, change both.
 */
export function nextBaseline(
  prev: number | null,
  samples: number,
  value: number,
  alpha: number,
): number {
  if (prev === null || samples <= 0) return value;
  if (samples < BASELINE_MIN_SAMPLES) {
    return prev + (value - prev) / (samples + 1);
  }
  return prev + alpha * (value - prev);
}
