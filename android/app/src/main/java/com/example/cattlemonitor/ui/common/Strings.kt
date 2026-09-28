package com.example.cattlemonitor.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.cattlemonitor.R
import com.example.cattlemonitor.data.AlertType
import com.example.cattlemonitor.data.Cow
import com.example.cattlemonitor.data.CowStatus
import com.example.cattlemonitor.ui.status.StatusText
import com.example.cattlemonitor.util.TimeFormat
import java.util.Date

/**
 * Composable string helpers — the single localized path for status labels,
 * alert-type names, relative time and status explanations. Keep the time
 * thresholds in sync with util/TimeFormat (unit-tested there).
 */

/** Ear-tag badge text for a cow status. */
@Composable
fun statusLabel(status: CowStatus): String = stringResource(
    when (status) {
        CowStatus.NORMAL -> R.string.status_ok
        CowStatus.WARNING -> R.string.status_watch
        CowStatus.ALERT -> R.string.status_alert
        CowStatus.OFFLINE -> R.string.status_offline
    },
)

/** Human-readable alert type name ("possible estrus", "fever", …). */
@Composable
fun alertTypeLabel(type: AlertType): String = stringResource(
    when (type) {
        AlertType.FEVER -> R.string.alert_type_fever
        AlertType.LOW_ACTIVITY -> R.string.alert_type_low_activity
        AlertType.POSSIBLE_ESTRUS -> R.string.alert_type_possible_estrus
        AlertType.POSSIBLE_DISTRESS -> R.string.alert_type_possible_distress
        AlertType.DEVICE_OFFLINE -> R.string.alert_type_device_offline
        AlertType.SENSOR_ISSUE -> R.string.alert_type_sensor_issue
    },
)

/** Localized relative time: "just now", "5m ago", "3h ago", "2d ago". */
@Composable
fun relativeTime(d: Date?, now: Date = Date()): String =
    when (val p = TimeFormat.relativeParts(d, now)) {
        TimeFormat.Relative.Never -> stringResource(R.string.time_never)
        TimeFormat.Relative.JustNow -> stringResource(R.string.time_just_now)
        is TimeFormat.Relative.Minutes -> stringResource(R.string.time_minutes_ago, p.n)
        is TimeFormat.Relative.Hours -> stringResource(R.string.time_hours_ago, p.n)
        is TimeFormat.Relative.Days -> stringResource(R.string.time_days_ago, p.n)
    }

/** The long explanation under the status badge on Cow Detail, localized. */
@Composable
fun statusExplanation(cow: Cow, now: Date = Date()): String {
    // Instant staleness (#1): a silent collar is OFFLINE *now*, not when the
    // cron gets around to it — reflect that in the explanation too.
    val effective = cow.copy(status = cow.effectiveStatus(now))
    // Bucket selection is shared with the (English, unit-tested) reference
    // implementation in ui/status/StatusText.explainParts.
    val p = StatusText.explainParts(effective, now)
    val base = stringResource(
        when (p.case) {
            StatusText.ExplainCase.NORMAL -> R.string.status_normal_explain
            StatusText.ExplainCase.WARNING -> R.string.status_warning_explain
            StatusText.ExplainCase.ALERT -> R.string.status_alert_explain
            StatusText.ExplainCase.OFFLINE_NO_DATA -> R.string.status_offline_no_data
            StatusText.ExplainCase.OFFLINE_STALE -> R.string.status_offline_no_readings
        },
    )
    val suffix = when (p.lastSeen) {
        StatusText.LastSeenBucket.NONE -> ""
        StatusText.LastSeenBucket.MINUTES ->
            stringResource(R.string.last_reading_ago, stringResource(R.string.time_minutes_ago, p.minutesAgo))
        StatusText.LastSeenBucket.HOURS ->
            stringResource(R.string.last_reading_ago, stringResource(R.string.time_hours_ago, p.minutesAgo / 60))
        StatusText.LastSeenBucket.DAYS ->
            stringResource(R.string.last_reading_ago, stringResource(R.string.time_days_ago, p.minutesAgo / 1440))
    }
    return base + suffix
}
