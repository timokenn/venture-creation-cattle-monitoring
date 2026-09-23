/**
 * Scenario profiles for the simulator.
 *
 * Each profile is a function of "seconds since the scenario started" that
 * returns a raw ESP32-style sample. BASELINE_TEMP approximates a healthy cow
 * at rest; seeded history from `seed` makes the on-device baseline match.
 *
 * @typedef {Object} Sample
 * @property {number} temperature
 * @property {{x: number, y: number, z: number}} accel
 * @property {{x: number, y: number, z: number}} gyro
 */

/**
 * @typedef {'normal'|'fever'|'estrus'|'low_activity'|'distress'|'stale_sensor'|'device_offline'} ScenarioName
 */

/** @typedef {(tick: number) => Sample} Profile */

export const BASELINE_TEMP = 38.0;

/** Activity index of a static device: |a| = 1 g, no gyro → ~0.0. */
export const BASELINE_ACTIVITY = 0.0;

/** @type {ScenarioName[]} */
export const SCENARIOS = [
  "normal",
  "fever",
  "estrus",
  "low_activity",
  "distress",
  "stale_sensor",
  "device_offline",
];

/** Deterministic pseudo-noise in [-1, 1] from an integer tick. */
function noise(tick, salt = 0) {
  const x = Math.sin(tick * 12.9898 + salt * 78.233) * 43758.5453;
  return (x - Math.floor(x)) * 2 - 1;
}

function staticAccel(tick) {
  return {
    x: noise(tick, 1) * 0.02,
    y: noise(tick, 2) * 0.02,
    z: 1 + noise(tick, 3) * 0.02,
  };
}

/** Idly shifting weight; small motion bursts now and then. */
function normalProfile(tick) {
  const burst = tick % 90 < 6 ? 0.6 : 0;
  return {
    temperature: BASELINE_TEMP + noise(tick, 4) * 0.08,
    accel: {
      x: noise(tick, 1) * (0.03 + burst),
      y: noise(tick, 2) * (0.03 + burst),
      z: 1 + noise(tick, 3) * (0.03 + burst),
    },
    gyro: {
      x: noise(tick, 5) * (2 + burst * 30),
      y: noise(tick, 6) * 2,
      z: noise(tick, 7) * 2,
    },
  };
}

/** Temp ramps ~1.5°C above baseline over an hour; activity stays low. */
function feverProfile(tick) {
  const ramp = Math.min(1, tick / 1800); // full fever after 60 min
  return {
    ...normalProfile(tick),
    temperature: BASELINE_TEMP + 0.2 + ramp * 1.5 + noise(tick, 4) * 0.05,
  };
}

/** Temp slightly up, activity sharply and continuously up. */
function estrusProfile(tick) {
  return {
    temperature: BASELINE_TEMP + 0.6 + noise(tick, 4) * 0.05,
    accel: {
      x: noise(tick, 1) * 0.4,
      y: noise(tick, 2) * 0.4,
      z: 1 + noise(tick, 3) * 0.4,
    },
    gyro: { x: noise(tick, 5) * 40, y: noise(tick, 6) * 40, z: noise(tick, 7) * 40 },
  };
}

/** Activity collapses; temperature stays normal. */
function lowActivityProfile(tick) {
  return {
    temperature: BASELINE_TEMP + noise(tick, 4) * 0.05,
    accel: staticAccel(tick),
    gyro: { x: 0, y: 0, z: 0 },
  };
}

/**
 * Failed-rise distress: repeated spike-then-drop cycles. Rest is genuinely
 * low (well below the resting baseline) and each spike is a sharp, brief
 * motion burst — the pattern the detector is designed to catch.
 */
function distressProfile(tick) {
  const cycle = 240; // seconds: 3 min down, 1 min struggling
  const phase = tick % cycle;
  if (phase >= 180) {
    // Struggling: violent motion
    return {
      temperature: BASELINE_TEMP + 0.1,
      accel: {
        x: noise(tick, 1) * 1.2,
        y: noise(tick, 2) * 1.2,
        z: 1 + noise(tick, 3) * 1.2,
      },
      gyro: {
        x: noise(tick, 5) * 120,
        y: noise(tick, 6) * 120,
        z: noise(tick, 7) * 120,
      },
    };
  }
  return {
    temperature: BASELINE_TEMP + noise(tick, 4) * 0.05,
    accel: staticAccel(tick),
    gyro: { x: 0, y: 0, z: 0 },
  };
}

/** Frozen MPU6050: identical motion values every time, temp still live. */
function staleSensorProfile(tick) {
  return {
    temperature: BASELINE_TEMP + Math.sin(tick / 600) * 0.15,
    accel: { x: 0.011, y: -0.008, z: 0.995 },
    gyro: { x: 0.3, y: -0.2, z: 0.1 },
  };
}

/** Device stops transmitting — the simulator simply stops sending. */
function deviceOfflineProfile() {
  throw new Error("device_offline scenario: stop the simulator to simulate");
}

/** @type {Record<ScenarioName, Profile>} */
export const PROFILES = {
  normal: normalProfile,
  fever: feverProfile,
  estrus: estrusProfile,
  low_activity: lowActivityProfile,
  distress: distressProfile,
  stale_sensor: staleSensorProfile,
  device_offline: deviceOfflineProfile,
};
