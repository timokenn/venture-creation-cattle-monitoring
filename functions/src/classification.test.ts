import { describe, expect, it } from "vitest";
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
  FEVER_TEMP_DELTA_C,
  LOW_ACTIVITY_FRACTION,
  STALE_ACTIVITY_N_READINGS,
  STALE_ACTIVITY_TOLERANCE,
} from "./thresholds";
import {
  baselineReady,
  isActivityStale,
  isFever,
  isLowActivity,
  isPossibleDistress,
  isPossibleEstrus,
} from "./classification";
import { computeActivityIndex, GYRO_WEIGHT } from "./activity";
import { planAlertOps } from "./alerts";
import { decide } from "./pipeline";
import { Reading } from "./types";

const MIN = 60_000;
const HOUR = 60 * MIN;
const NOW = new Date("2026-01-15T12:00:00Z").getTime();
const BASELINE_ACTIVITY = 1.0;

function reading(activity: number, ageMs: number, temp = 38.5): Reading {
  return {
    timestamp: new Date(NOW - ageMs),
    temperature: temp,
    accel: { x: 0, y: 0, z: 1 },
    gyro: { x: 0, y: 0, z: 0 },
    activity_index: activity,
  };
}

describe("isActivityStale", () => {
  it(`flags when the last ${STALE_ACTIVITY_N_READINGS} activity values are identical`, () => {
    const prior = Array.from({ length: STALE_ACTIVITY_N_READINGS - 1 }, (_, i) =>
      reading(2.0, (i + 1) * 20_000),
    );
    expect(isActivityStale(reading(2.0, 0), prior)).toBe(true);
  });

  it("does not flag when values vary beyond tolerance", () => {
    const prior = Array.from({ length: STALE_ACTIVITY_N_READINGS - 1 }, (_, i) =>
      reading(2.0 + i * 0.1, (i + 1) * 20_000),
    );
    expect(isActivityStale(reading(2.35, 0), prior)).toBe(false);
  });

  it("does not flag with insufficient history", () => {
    const prior = Array.from({ length: STALE_ACTIVITY_N_READINGS - 2 }, (_, i) =>
      reading(2.0, (i + 1) * 20_000),
    );
    expect(isActivityStale(reading(2.0, 0), prior)).toBe(false);
  });

  it("tolerates variation up to the epsilon", () => {
    const prior = Array.from({ length: STALE_ACTIVITY_N_READINGS - 1 }, (_, i) =>
      reading(2.0 + (STALE_ACTIVITY_TOLERANCE / 2) * (i % 2), (i + 1) * 20_000),
    );
    expect(isActivityStale(reading(2.0, 0), prior)).toBe(true);
  });
});

describe("isFever", () => {
  it(`triggers at ${FEVER_TEMP_DELTA_C}°C above baseline with low activity`, () => {
    expect(isFever(FEVER_TEMP_DELTA_C, 0.7, true)).toBe(true);
  });
  it("does not trigger below the temp delta", () => {
    expect(isFever(FEVER_TEMP_DELTA_C - 0.01, 0.7, true)).toBe(false);
  });
  it("does not trigger when activity is normal", () => {
    expect(isFever(FEVER_TEMP_DELTA_C + 1, 1.0, true)).toBe(false);
  });
  it("does not trigger before baseline is ready", () => {
    expect(isFever(FEVER_TEMP_DELTA_C, 0.7, false)).toBe(false);
  });
});

describe("isPossibleEstrus", () => {
  const ready = true;
  const tempDelta = ESTRUS_TEMP_DELTA_C + 0.1;
  const ratio = ESTRUS_ACTIVITY_FACTOR + 0.2;
  const elevated = ESTRUS_ACTIVITY_FACTOR * BASELINE_ACTIVITY;

  function windowAllElevated(count: number): Reading[] {
    return Array.from({ length: count }, (_, i) =>
      reading(elevated, i * ((ESTRUS_WINDOW_HOURS * HOUR) / count)),
    );
  }

  it("triggers when elevation is sustained across the window", () => {
    const window = windowAllElevated(20);
    expect(
      isPossibleEstrus(window, BASELINE_ACTIVITY, tempDelta, ratio, ready),
    ).toBe(true);
  });

  it("does not trigger on a single spike", () => {
    const window = windowAllElevated(20);
    window[19] = reading(elevated * 2, 0);
    for (let i = 0; i < 19; i++) window[i] = reading(0.9, (i + 1) * 900_000);
    expect(
      isPossibleEstrus(window, BASELINE_ACTIVITY, tempDelta, ratio, ready),
      `elevated fraction must stay below ${ESTRUS_MIN_ELEVATED_FRACTION}`,
    ).toBe(false);
  });

  it("does not trigger when temperature delta is too small", () => {
    const window = windowAllElevated(20);
    expect(
      isPossibleEstrus(
        window,
        BASELINE_ACTIVITY,
        ESTRUS_TEMP_DELTA_C - 0.1,
        ratio,
        ready,
      ),
    ).toBe(false);
  });

  it("ignores samples older than the window", () => {
    const window: Reading[] = [];
    for (let i = 0; i < 10; i++) {
      window.push(reading(elevated, ESTRUS_WINDOW_HOURS * HOUR + (i + 1) * MIN));
    }
    window.push(reading(0.9, 0));
    expect(
      isPossibleEstrus(window, BASELINE_ACTIVITY, tempDelta, ratio, ready),
    ).toBe(false);
  });
});

describe("isPossibleDistress", () => {
  const spikeLevel = DISTRESS_SPIKE_FACTOR * BASELINE_ACTIVITY;
  const downLevel = DISTRESS_RECOVERY_FRACTION * BASELINE_ACTIVITY;

  function cycles(count: number, spacingMs = 5 * MIN): Reading[] {
    const out: Reading[] = [];
    // Each cycle is down-then-spike; the down leg must precede the spike
    // inside the window for the state machine to count it.
    for (let i = 0; i < count; i++) {
      out.push(reading(downLevel, (count - i) * 2 * spacingMs + spacingMs));
      out.push(reading(spikeLevel, (count - i) * 2 * spacingMs));
    }
    return out;
  }

  it(`triggers on ${DISTRESS_MIN_CYCLES} spike-then-drop cycles in the window`, () => {
    expect(
      isPossibleDistress(cycles(DISTRESS_MIN_CYCLES), BASELINE_ACTIVITY),
    ).toBe(true);
  });

  it("does not trigger on a single spike-then-recover", () => {
    const window = [
      reading(downLevel, 30 * MIN),
      reading(spikeLevel, 25 * MIN),
      reading(0.9, 20 * MIN),
      reading(0.9, 10 * MIN),
      reading(0.9, 0),
    ];
    expect(isPossibleDistress(window, BASELINE_ACTIVITY)).toBe(false);
  });

  it("does not trigger on flat low activity (lying down calmly)", () => {
    const window = Array.from({ length: 12 }, (_, i) =>
      reading(downLevel, i * 3 * MIN),
    );
    expect(isPossibleDistress(window, BASELINE_ACTIVITY)).toBe(false);
  });

  it(`does not trigger with only ${DISTRESS_MIN_CYCLES - 1} cycles`, () => {
    expect(
      isPossibleDistress(cycles(DISTRESS_MIN_CYCLES - 1), BASELINE_ACTIVITY),
    ).toBe(false);
  });

  it(`ignores cycles older than ${DISTRESS_WINDOW_MINUTES} minutes`, () => {
    const window = cycles(DISTRESS_MIN_CYCLES, 20 * MIN);
    expect(isPossibleDistress(window, BASELINE_ACTIVITY)).toBe(false);
  });
});

describe("isLowActivity", () => {
  it(`triggers at ${LOW_ACTIVITY_FRACTION}× baseline with normal temp`, () => {
    expect(isLowActivity(0.2, LOW_ACTIVITY_FRACTION, true)).toBe(true);
  });
  it("does not trigger when temp is fever-range", () => {
    expect(isLowActivity(FEVER_TEMP_DELTA_C, LOW_ACTIVITY_FRACTION, true)).toBe(
      false,
    );
  });
});

describe("baselineReady", () => {
  it("gates on BASELINE_MIN_SAMPLES", () => {
    expect(baselineReady(BASELINE_MIN_SAMPLES)).toBe(true);
    expect(baselineReady(BASELINE_MIN_SAMPLES - 1)).toBe(false);
  });
});

describe("computeActivityIndex", () => {
  it("is ~0 for a static device at 1 g", () => {
    expect(computeActivityIndex({ x: 0, y: 0, z: 1 }, { x: 0, y: 0, z: 0 })).toBeCloseTo(0, 9);
  });
  it("scales with accel deviation from 1 g", () => {
    expect(computeActivityIndex({ x: 0, y: 0, z: 1.5 }, { x: 0, y: 0, z: 0 })).toBeCloseTo(0.5, 9);
  });
  it(`adds ${GYRO_WEIGHT}× the gyro magnitude`, () => {
    const idx = computeActivityIndex({ x: 0, y: 0, z: 1 }, { x: 2, y: 0, z: 0 });
    expect(idx).toBeCloseTo(GYRO_WEIGHT * 2, 9);
  });
});

describe("planAlertOps", () => {
  const now = new Date(NOW);

  it("creates alerts for active types with no open alert", () => {
    const ops = planAlertOps(["fever"], { fever: "hot" }, [], now);
    expect(ops).toEqual([
      { op: "create", type: "fever", note: "hot", timestamp: now },
    ]);
  });

  it("does not duplicate an already-open alert of the same type", () => {
    const ops = planAlertOps(
      ["fever"],
      { fever: "hot" },
      [{ id: "a1", type: "fever" }],
      now,
    );
    expect(ops).toEqual([]);
  });

  it("resolves alerts whose condition cleared", () => {
    const ops = planAlertOps(
      [],
      {},
      [
        { id: "a1", type: "fever" },
        { id: "a2", type: "device_offline" },
      ],
      now,
    );
    expect(ops).toEqual([
      { op: "resolve", id: "a1" },
      { op: "resolve", id: "a2" },
    ]);
  });
});

describe("decide (pipeline integration)", () => {
  const cow = {
    baseline_temp: 38.0,
    baseline_activity: BASELINE_ACTIVITY,
    baseline_samples: BASELINE_MIN_SAMPLES,
  };

  it("classifies fever and plans a new alert", () => {
    const d = decide(
      { temperature: 38.0 + FEVER_TEMP_DELTA_C + 0.2, accel: { x: 0, y: 0, z: 1 }, gyro: { x: 0, y: 0, z: 0 } },
      cow,
      [],
      [],
      { now: new Date(NOW) },
    );
    expect(d.classification).toBe("fever");
    expect(d.activeTypes).toContain("fever");
    expect(d.statusAfter).toBe("alert");
    expect(d.createdAlerts.map((a) => a.type)).toEqual(["fever"]);
    expect(d.ops[0]).toMatchObject({ op: "create", type: "fever" });
  });

  it("marks out-of-range temperature invalid and skips classification", () => {
    const d = decide(
      { temperature: 50, accel: { x: 0, y: 0, z: 1 }, gyro: { x: 0, y: 0, z: 0 } },
      cow,
      [],
      [],
      { now: new Date(NOW) },
    );
    expect(d.classification).toBe("invalid");
    expect(d.reading.valid).toBe(false);
    expect(d.activeTypes).toEqual([]);
    expect(d.baseline_temp).toBe(38.0);
    expect(d.baseline_samples).toBe(cow.baseline_samples);
  });

  it("classifies normal and advances the baseline incrementally", () => {
    const d = decide(
      // accel z=2 → activity index 1.0 → ratio 1.0, i.e. normal motion
      { temperature: 38.1, accel: { x: 0, y: 0, z: 2 }, gyro: { x: 0, y: 0, z: 0 } },
      cow,
      [],
      [],
      { now: new Date(NOW) },
    );
    expect(d.classification).toBe("normal");
    expect(d.statusAfter).toBe("normal");
    expect(d.baseline_temp).toBeCloseTo(38.0 + 0.005 * 0.1, 9);
    expect(d.baseline_samples).toBe(cow.baseline_samples + 1);
  });

  it("flags stale activity as sensor_issue and never as low_activity", () => {
    const prior = Array.from({ length: STALE_ACTIVITY_N_READINGS - 1 }, (_, i) =>
      reading(0.5, (i + 1) * 20_000),
    );
    const d = decide(
      { temperature: 38.5, accel: { x: 0, y: 0, z: 1.5 }, gyro: { x: 0, y: 0, z: 0 } },
      cow,
      prior,
      [],
      { now: new Date(NOW) },
    );
    expect(d.reading.data_quality).toBe("activity_stale");
    expect(d.activeTypes).toContain("sensor_issue");
    expect(d.activeTypes).not.toContain("low_activity");
    expect(d.statusAfter).toBe("warning");
    expect(d.baseline_activity).toBe(BASELINE_ACTIVITY);
  });

  it("does not duplicate an open alert while the condition persists", () => {
    const d = decide(
      { temperature: 38.0 + FEVER_TEMP_DELTA_C + 0.2, accel: { x: 0, y: 0, z: 1 }, gyro: { x: 0, y: 0, z: 0 } },
      cow,
      [],
      [{ id: "a1", type: "fever" }],
      { now: new Date(NOW) },
    );
    expect(d.ops).toEqual([]);
    expect(d.createdAlerts).toEqual([]);
  });

  it("resolves a device_offline alert when the device reports again", () => {
    const d = decide(
      { temperature: 38.1, accel: { x: 0, y: 0, z: 2 }, gyro: { x: 0, y: 0, z: 0 } },
      cow,
      [],
      [{ id: "o1", type: "device_offline" }],
      { now: new Date(NOW) },
    );
    expect(d.ops).toEqual([{ op: "resolve", id: "o1" }]);
    expect(d.statusAfter).toBe("normal");
  });

  it("does not classify before the baseline is ready", () => {
    const d = decide(
      { temperature: 40, accel: { x: 0, y: 0, z: 1 }, gyro: { x: 0, y: 0, z: 0 } },
      { baseline_temp: 38.0, baseline_activity: 1.0, baseline_samples: 10 },
      [],
      [],
      { now: new Date(NOW) },
    );
    expect(d.classification).toBe("normal");
    expect(d.activeTypes).toEqual([]);
  });
});
