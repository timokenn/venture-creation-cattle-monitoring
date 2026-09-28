package com.example.cattlemonitor.data

import java.util.Date

enum class CowStatus {
    NORMAL,
    WARNING,
    ALERT,
    OFFLINE;

    companion object {
        fun from(raw: String?): CowStatus =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: NORMAL
    }
}

enum class AlertType {
    FEVER,
    LOW_ACTIVITY,
    POSSIBLE_ESTRUS,
    POSSIBLE_DISTRESS,
    DEVICE_OFFLINE,
    SENSOR_ISSUE;

    companion object {
        fun from(raw: String?): AlertType? =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    }
}

data class Cow(
    val id: String,
    val name: String,
    val deviceId: String,
    val baselineTemp: Double?,
    val baselineActivity: Double?,
    val baselineSamples: Long,
    val status: CowStatus,
    val lastSeen: Date?,
    val latestTemp: Double?,
    val latestActivity: Double?,
    /** Latest reported collar battery %, null until firmware reports it. */
    val batteryLevel: Double? = null,
    /** Nominal seconds between collar readings (server constant). */
    val sendIntervalSeconds: Long = 20L,
    /** Profile photo URL (public Storage), null until the user adds one. */
    val imageUrl: String? = null,
) {
    /**
     * Status with INSTANT staleness applied: the cron flags OFFLINE every
     * 15 minutes, but the app can see "no reading for 3x the send interval"
     * right now — so a silent collar shows OFFLINE within ~a minute, not a
     * quarter-hour late. Sync with OFFLINE_FACTOR/EXPECTED_SEND_INTERVAL in
     * supabase/functions/_shared/thresholds.ts.
     */
    fun effectiveStatus(now: Date = Date()): CowStatus {
        if (status == CowStatus.OFFLINE) return CowStatus.OFFLINE
        val last = lastSeen ?: return status
        val silentForMs = now.time - last.time
        return if (silentForMs > 3 * sendIntervalSeconds * 1000) {
            CowStatus.OFFLINE
        } else {
            status
        }
    }
}

data class Reading(
    val id: String,
    val timestamp: Date,
    val temperature: Double,
    val activityIndex: Double?,
    val dataQuality: String?,
    /** Sensor-suspicious (e.g. temp_out_of_range): shown, but never taught to baselines. */
    val suspicious: Boolean = false,
)

data class Alert(
    val id: String,
    val cowId: String,
    val type: AlertType,
    val timestamp: Date,
    val resolved: Boolean,
    val note: String,
)
