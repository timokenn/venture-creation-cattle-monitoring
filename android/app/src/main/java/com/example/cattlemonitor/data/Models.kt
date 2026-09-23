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
)

data class Reading(
    val id: String,
    val timestamp: Date,
    val temperature: Double,
    val activityIndex: Double?,
    val dataQuality: String?,
)

data class Alert(
    val id: String,
    val cowId: String,
    val type: AlertType,
    val timestamp: Date,
    val resolved: Boolean,
    val note: String,
)
