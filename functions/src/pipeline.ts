import {
  BASELINE_ACTIVITY_EMA_ALPHA,
  BASELINE_TEMP_EMA_ALPHA,
  TEMP_VALID_MAX_C,
  TEMP_VALID_MIN_C,
} from "./thresholds";
import {
  Classification,
  baselineReady,
  isActivityStale,
  isFever,
  isLowActivity,
  isPossibleDistress,
  isPossibleEstrus,
} from "./classification";
import { Vector3, computeActivityIndex } from "./activity";
import { nextBaseline } from "./baseline";
import { planAlertOps } from "./alerts";
import { AlertType, CowStatus, Reading, STATUS_FOR_ALERT } from "./types";

export interface TempSample {
  temperature: number;
  accel: Vector3;
  gyro: Vector3;
}

export interface CowSnapshot {
  baseline_temp: number | null;
  baseline_activity: number | null;
  baseline_samples: number;
}

export interface UnresolvedAlertRow {
  id: string;
  type: AlertType;
}

export interface CreatedAlert {
  type: AlertType;
  note: string;
  timestamp: Date;
}

export interface Decision {
  activityIndex: number;
  reading: Reading;
  baseline_temp: number;
  baseline_activity: number;
  baseline_samples: number;
  classification: Classification | "invalid";
  activeTypes: AlertType[];
  statusAfter: CowStatus;
  notes: Partial<Record<AlertType, string>>;
  ops: ReturnType<typeof planAlertOps>;
  createdAlerts: CreatedAlert[];
}

const SEVERITY: Record<CowStatus, number> = {
  normal: 0,
  warning: 1,
  alert: 2,
  offline: 1,
};

/**
 * Pure decision pipeline: one raw sample plus cow state in, everything the
 * trigger needs to persist out. No I/O — fully unit-testable.
 */
export function decide(
  sample: TempSample,
  cow: CowSnapshot,
  recentValid: Reading[],
  unresolved: UnresolvedAlertRow[],
  opts: { now: Date },
): Decision {
  const activityIndex = computeActivityIndex(sample.accel, sample.gyro);
  const valid =
    sample.temperature >= TEMP_VALID_MIN_C &&
    sample.temperature <= TEMP_VALID_MAX_C;

  const reading: Reading = {
    timestamp: opts.now,
    temperature: sample.temperature,
    accel: sample.accel,
    gyro: sample.gyro,
    activity_index: activityIndex,
    data_quality: "ok",
    valid,
  };

  const recent = [...recentValid].sort(
    (a, b) => a.timestamp.getTime() - b.timestamp.getTime(),
  );
  const stale = isActivityStale(reading, recent);
  if (stale) reading.data_quality = "activity_stale";

  const ready =
    valid &&
    baselineReady(cow.baseline_samples) &&
    cow.baseline_temp !== null &&
    cow.baseline_activity !== null;
  const tempDeltaC = ready ? sample.temperature - cow.baseline_temp! : 0;
  const activityRatio = ready ? activityIndex / cow.baseline_activity! : 1;

  let classification: Classification | "invalid" = "normal";
  if (!valid) {
    classification = "invalid";
  } else if (ready) {
    const window = [...recent, reading];
    if (isFever(tempDeltaC, activityRatio, ready)) {
      classification = "fever";
    } else if (
      isPossibleEstrus(
        window,
        cow.baseline_activity!,
        tempDeltaC,
        activityRatio,
        ready,
      )
    ) {
      classification = "possible_estrus";
    } else if (!stale && isPossibleDistress(window, cow.baseline_activity!)) {
      classification = "possible_distress";
    } else if (!stale && isLowActivity(tempDeltaC, activityRatio, ready)) {
      classification = "low_activity";
    }
  }

  const activeTypes: AlertType[] = [];
  const notes: Partial<Record<AlertType, string>> = {};

  if (classification === "fever") {
    activeTypes.push("fever");
    notes.fever =
      `Temp ${sample.temperature.toFixed(1)}°C, ${tempDeltaC.toFixed(1)}°C ` +
      `above baseline, activity low`;
  } else if (classification === "possible_estrus") {
    activeTypes.push("possible_estrus");
    notes.possible_estrus =
      `Temp ${tempDeltaC.toFixed(1)}°C above baseline with sustained high activity`;
  } else if (classification === "possible_distress") {
    activeTypes.push("possible_distress");
    notes.possible_distress =
      "Repeated failed rise attempts detected (spike-then-drop pattern)";
  } else if (classification === "low_activity") {
    activeTypes.push("low_activity");
    notes.low_activity =
      `Activity ${activityRatio.toFixed(2)}× baseline with normal temperature`;
  }

  if (stale) {
    activeTypes.push("sensor_issue");
    notes.sensor_issue =
      "Motion sensor output appears frozen (repeated identical activity values)";
  }

  const ops = planAlertOps(activeTypes, notes, unresolved, opts.now);
  const createdAlerts: CreatedAlert[] = ops
    .filter((o): o is Extract<typeof o, { op: "create" }> => o.op === "create")
    .map((o) => ({ type: o.type, note: o.note, timestamp: o.timestamp }));

  const statusAfter: CowStatus =
    activeTypes.length === 0
      ? "normal"
      : activeTypes
          .map((t) => STATUS_FOR_ALERT[t])
          .reduce((worst, s) => (SEVERITY[s] > SEVERITY[worst] ? s : worst));

  // Invalid readings never update the temp baseline; stale readings never
  // update the activity baseline (a frozen sensor must not drag it down).
  const baseline_temp = valid
    ? nextBaseline(
        cow.baseline_temp,
        cow.baseline_samples,
        sample.temperature,
        BASELINE_TEMP_EMA_ALPHA,
      )
    : (cow.baseline_temp ?? sample.temperature);
  const baseline_activity =
    stale === false
      ? nextBaseline(
          cow.baseline_activity,
          cow.baseline_samples,
          activityIndex,
          BASELINE_ACTIVITY_EMA_ALPHA,
        )
      : (cow.baseline_activity ?? activityIndex);

  return {
    activityIndex,
    reading,
    baseline_temp,
    baseline_activity,
    baseline_samples: cow.baseline_samples + (valid ? 1 : 0),
    classification,
    activeTypes,
    statusAfter,
    notes,
    ops,
    createdAlerts,
  };
}
