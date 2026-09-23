export interface Vector3 {
  x: number;
  y: number;
  z: number;
}

/** Weight of gyro magnitude relative to accel deviation (see thresholds rationale in README). */
export const GYRO_WEIGHT = 0.2;

/**
 * Motion score from a single sample: how far the acceleration vector deviates
 * from static 1 g, plus a weighted angular-rate term. Higher = more motion.
 */
export function computeActivityIndex(accel: Vector3, gyro: Vector3): number {
  const accelMag = Math.hypot(accel.x, accel.y, accel.z);
  const accelDeviation = Math.abs(accelMag - 1);
  const gyroMag = Math.hypot(gyro.x, gyro.y, gyro.z);
  return accelDeviation + GYRO_WEIGHT * gyroMag;
}
