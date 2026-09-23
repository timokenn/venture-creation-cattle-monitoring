import { AlertType } from "./types.ts";

export interface UnresolvedAlert {
  id: string;
  type: AlertType;
}

export type AlertOp =
  | { op: "create"; type: AlertType; note: string; timestamp: Date }
  | { op: "resolve"; id: string };

/**
 * Plan alert writes from the currently-active condition types:
 *  - one create per active type that has no unresolved alert yet (dedupe)
 *  - one resolve per unresolved alert whose type is no longer active
 *
 * The pipeline derives active types from classification (health) + staleness
 * (sensor_issue); device_offline is managed exclusively by the scheduled
 * check and is therefore always "inactive" from the reading path's
 * perspective, which resolves it as soon as a device reports again.
 */
export function planAlertOps(
  activeTypes: AlertType[],
  notes: Partial<Record<AlertType, string>>,
  unresolved: UnresolvedAlert[],
  now: Date,
): AlertOp[] {
  const ops: AlertOp[] = [];
  const active = new Set(activeTypes);

  for (const type of active) {
    const alreadyOpen = unresolved.some((a) => a.type === type);
    if (!alreadyOpen) {
      ops.push({
        op: "create",
        type,
        note: notes[type] ?? type,
        timestamp: now,
      });
    }
  }

  for (const a of unresolved) {
    if (!active.has(a.type)) {
      ops.push({ op: "resolve", id: a.id });
    }
  }

  return ops;
}
