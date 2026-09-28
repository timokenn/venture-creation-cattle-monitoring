package com.example.cattlemonitor.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime
import java.util.Date

/**
 * Wire DTOs mirroring the Postgres schema in supabase/schema.sql. All
 * row-to-domain parsing lives here and in CattleRepository — no raw column
 * names scattered across the UI.
 */
@Serializable
data class CowDto(
    val id: String,
    val name: String,
    @SerialName("device_id") val deviceId: String = "",
    @SerialName("baseline_temp") val baselineTemp: Double? = null,
    @SerialName("baseline_activity") val baselineActivity: Double? = null,
    @SerialName("baseline_samples") val baselineSamples: Long = 0,
    @SerialName("current_status") val currentStatus: String = "normal",
    @SerialName("last_seen") val lastSeen: String? = null,
    @SerialName("latest_temp") val latestTemp: Double? = null,
    @SerialName("latest_activity") val latestActivity: Double? = null,
    @SerialName("battery_level") val batteryLevel: Double? = null,
    @SerialName("send_interval_seconds") val sendIntervalSeconds: Int? = null,
    @SerialName("image_url") val imageUrl: String? = null,
)

@Serializable
data class ReadingDto(
    val id: String,
    @SerialName("cow_id") val cowId: String? = null,
    val timestamp: String,
    val temperature: Double,
    @SerialName("activity_index") val activityIndex: Double? = null,
    @SerialName("data_quality") val dataQuality: String? = null,
    val valid: Boolean? = null,
)

@Serializable
data class AlertDto(
    val id: String,
    @SerialName("cow_id") val cowId: String? = null,
    val type: String,
    val timestamp: String,
    val resolved: Boolean = false,
    val note: String = "",
)

@Serializable
internal data class RegisterCowRequest(
    val name: String,
    @SerialName("device_id") val deviceId: String,
)

@Serializable
internal data class FcmTokenBody(val token: String)

@Serializable
internal data class CowIdResponse(val id: String)

/** Postgres timestamptz ISO strings → Date. Null on anything unparsable. */
internal fun isoToDate(raw: String?): Date? =
    raw?.let { runCatching { Date.from(OffsetDateTime.parse(it).toInstant()) }.getOrNull() }

internal fun CowDto.toDomain(): Cow = Cow(
    id = id,
    name = name,
    deviceId = deviceId,
    baselineTemp = baselineTemp,
    baselineActivity = baselineActivity,
    baselineSamples = baselineSamples,
    status = CowStatus.from(currentStatus),
    lastSeen = isoToDate(lastSeen),
    latestTemp = latestTemp,
    latestActivity = latestActivity,
    batteryLevel = batteryLevel,
    sendIntervalSeconds = (sendIntervalSeconds ?: 20).toLong(),
    imageUrl = imageUrl,
)

internal fun ReadingDto.toDomain(): Reading = Reading(
    id = id,
    timestamp = isoToDate(timestamp) ?: Date(0),
    temperature = temperature,
    activityIndex = activityIndex,
    dataQuality = dataQuality,
    suspicious = dataQuality == "temp_out_of_range",
)

internal fun AlertDto.toDomain(): Alert? {
    val type = AlertType.from(type) ?: return null
    return Alert(
        id = id,
        cowId = cowId ?: "",
        type = type,
        timestamp = isoToDate(timestamp) ?: Date(0),
        resolved = resolved,
        note = note,
    )
}
