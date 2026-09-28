package com.example.cattlemonitor.util

/**
 * Activity has no natural physical unit — it's derived from how far the
 * collar's accelerometer/gyro deviate from resting (see
 * supabase/functions/_shared/activity.ts: |accelMag - 1g| + 0.2 * gyroMag).
 * That raw number is small and not meaningful to a person at a glance, so
 * the UI rescales it into an unitless 0–100 "Activity Index" — purely a
 * display concern, the raw value stored in Supabase is untouched.
 *
 * EXPECTED_MAX_DEVIATION is the raw deviation we treat as "fully active"
 * (100 on the index). It's a starting estimate — recalibrate once there's
 * real data from collars in the field.
 */
private const val EXPECTED_MAX_DEVIATION = 4.0

/** Raw activity deviation → 0–100 index for display. */
fun Double.toActivityIndex(): Double =
    (this / EXPECTED_MAX_DEVIATION * 100).coerceIn(0.0, 100.0)
