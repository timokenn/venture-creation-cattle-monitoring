/**
 * Single source of truth for every tunable value in the health logic.
 * Tune here (and only here) after real-world testing.
 *
 * WARNING — surface temperature: the DS18B20 probe on a wearable measures
 * skin/surface temperature, which runs below core body temperature and shifts
 * with ambient conditions and sensor placement. The deltas below are
 * placeholders; calibrate against per-cow observed surface baselines before
 * trusting fever/estrus classification. See README "Threshold calibration".
 */

// --- Temperature validation -------------------------------------------------
/** Readings outside this range are treated as sensor glitches, not health data. */
export const TEMP_VALID_MIN_C = 30;
export const TEMP_VALID_MAX_C = 45;

// --- Baseline (incremental EMA smoothing factors, 0 < alpha <= 1) -----------
/** Higher alpha adapts faster; lower alpha remembers longer. */
export const BASELINE_TEMP_EMA_ALPHA = 0.005; // ~3h time constant at 20s cadence
export const BASELINE_ACTIVITY_EMA_ALPHA = 0.005;

/** EMA is skipped until this many valid readings have been seen. */
export const BASELINE_MIN_SAMPLES = 360; // ~2h at 20s cadence

// --- Staleness check (frozen MPU6050 detection) -----------------------------
/** Consecutive effectively-unchanged activity_index values before flagging. */
export const STALE_ACTIVITY_N_READINGS = 5;
/** Two activity_index values closer than this are considered "unchanged". */
export const STALE_ACTIVITY_TOLERANCE = 1e-4;
/** Sustained staleness before a sensor_issue alert is raised. */
export const STALE_ALERT_AFTER_MINUTES = 30;

// --- Failed-rise distress detector ------------------------------------------
/** Lookback window for counting spike-then-drop cycles. */
export const DISTRESS_WINDOW_MINUTES = 45;
/** Spike-then-drop cycles required within the window to raise an alert. */
export const DISTRESS_MIN_CYCLES = 3;
/** Activity multiple above baseline that counts as a rise attempt. */
export const DISTRESS_SPIKE_FACTOR = 2.0;
/** Activity fraction of baseline between spikes that counts as "down again". */
export const DISTRESS_RECOVERY_FRACTION = 0.5;

// --- Estrus detector (windowed, sustained) ----------------------------------
/** How far back the sustained-elevation window reaches. */
export const ESTRUS_WINDOW_HOURS = 3;
/** Fraction of window samples that must be elevated (single spikes fail this). */
export const ESTRUS_MIN_ELEVATED_FRACTION = 0.6;
/** Temperature must be at least this far above baseline. */
export const ESTRUS_TEMP_DELTA_C = 0.4;
/** Activity must be at least this multiple of baseline. */
export const ESTRUS_ACTIVITY_FACTOR = 1.6;

// --- Fever detector ----------------------------------------------------------
/** Temperature must be at least this far above baseline... */
export const FEVER_TEMP_DELTA_C = 1.0;
/** ...while activity is at most this fraction of baseline. */
export const FEVER_ACTIVITY_FRACTION = 0.8;

// --- Low activity detector ---------------------------------------------------
/** Activity at most this fraction of baseline with normal temperature. */
export const LOW_ACTIVITY_FRACTION = 0.5;

// --- Offline scheduler -------------------------------------------------------
/** Nominal ESP32 send cadence; must match the firmware/simulator interval. */
export const EXPECTED_SEND_INTERVAL_SECONDS = 20;
/** Flag device_offline after this multiple of the expected interval. */
export const OFFLINE_FACTOR = 3;
/** How often the scheduled check runs. */
export const OFFLINE_CHECK_INTERVAL_MINUTES = 15;

// --- History window for recent readings -------------------------------------
/** Maximum age of readings fetched for windowed classification. */
export const RECENT_WINDOW_HOURS = Math.max(
  ESTRUS_WINDOW_HOURS,
  Math.ceil(DISTRESS_WINDOW_MINUTES / 60),
);
