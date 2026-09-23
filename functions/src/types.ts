import { Vector3 } from "./activity";

export type CowStatus = "normal" | "warning" | "alert" | "offline";
export type AlertType =
  | "fever"
  | "low_activity"
  | "possible_estrus"
  | "possible_distress"
  | "device_offline"
  | "sensor_issue";

export interface Reading {
  id?: string;
  timestamp: Date;
  temperature: number;
  accel: Vector3;
  gyro: Vector3;
  activity_index?: number;
  data_quality?: "ok" | "activity_stale";
  valid?: boolean;
}

export interface Alert {
  id?: string;
  type: AlertType;
  timestamp: Date;
  resolved: boolean;
  note: string;
}

export interface CowBaseline {
  baseline_temp: number | null;
  baseline_activity: number | null;
  baseline_samples: number;
}

export interface CowDoc {
  name: string;
  device_id: string;
  baseline_temp: number | null;
  baseline_activity: number | null;
  baseline_samples: number;
  current_status: CowStatus;
  last_seen: Date | null;
}

export const STATUS_FOR_ALERT: Record<AlertType, CowStatus> = {
  fever: "alert",
  possible_estrus: "warning",
  low_activity: "warning",
  possible_distress: "alert",
  device_offline: "offline",
  sensor_issue: "warning",
};
