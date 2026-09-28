package com.example.cattlemonitor.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.cattlemonitor.data.AlertType
import com.example.cattlemonitor.ui.theme.StatusWarning
import kotlin.math.roundToInt
import com.example.cattlemonitor.R
import com.example.cattlemonitor.ServiceLocator
import com.example.cattlemonitor.data.Alert
import com.example.cattlemonitor.data.Cow
import com.example.cattlemonitor.data.Reading
import com.example.cattlemonitor.ui.common.LineChart
import com.example.cattlemonitor.ui.common.OfflineBanner
import com.example.cattlemonitor.ui.common.OfflineScreen
import com.example.cattlemonitor.ui.common.StatusBadge
import com.example.cattlemonitor.ui.common.alertTypeLabel
import com.example.cattlemonitor.ui.common.relativeTime
import com.example.cattlemonitor.ui.common.statusExplanation
import com.example.cattlemonitor.ui.theme.Accent
import com.example.cattlemonitor.ui.theme.Brand
import com.example.cattlemonitor.ui.theme.Line
import com.example.cattlemonitor.ui.theme.Surface
import com.example.cattlemonitor.util.toActivityIndex
import java.util.Date
import java.util.concurrent.TimeUnit

private enum class Range(val hours: Long) {
    H24(24),
    D7(24 * 7),
}

@Composable
private fun rangeLabel(range: Range): String = stringResource(
    if (range == Range.H24) R.string.detail_range_24h else R.string.detail_range_7d,
)

@Composable
private fun timeAxisLabels(range: Range): List<String> =
    stringArrayResource(if (range == Range.H24) R.array.axis_h24 else R.array.axis_d7).toList()

@Composable
fun CowDetailScreen(
    cowId: String,
    onBack: () -> Unit,
) {
    val vm: DetailViewModel = viewModel(
        key = cowId,
        factory = viewModelFactory {
            initializer { DetailViewModel(cowId) }
        },
    )
    val isOnline by ServiceLocator.connectivity.isOnline.collectAsState()
    val cow by vm.cow.collectAsState()
    val readings by vm.readings.collectAsState()
    val alerts by vm.alerts.collectAsState()
    var range by remember { mutableStateOf(Range.H24) }

    // Hybrid offline (#9): detail keeps showing cached charts under a banner;
    // the full offline gate only replaces it if we never loaded this cow.
    if (!isOnline && cow == null && readings == null) {
        OfflineScreen()
        return
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    DetailBody(
        cow = cow,
        readings = readings.orEmpty(),
        alerts = alerts.orEmpty(),
        selected = range,
        onSelectRange = { range = it },
        onBack = onBack,
        cowId = cowId,
        onExport = {
            val currentCow = cow
            if (currentCow != null) {
                // Export everything we have: all readings + all alerts merged
                // by time (the detail charts only show the selected window).
                val lines = (readings.orEmpty().map {
                    com.example.cattlemonitor.util.ExportLine(
                        timestamp = it.timestamp,
                        temperature = it.temperature,
                        activityIndex = it.activityIndex,
                        dataQuality = it.dataQuality,
                    )
                } + alerts.orEmpty().map {
                    com.example.cattlemonitor.util.ExportLine(
                        timestamp = it.timestamp,
                        alertType = it.type.name.lowercase(),
                        alertNote = it.note,
                    )
                }).sortedBy { it.timestamp }
                com.example.cattlemonitor.util.CsvExport.export(context, currentCow, lines)
            }
        },
    )
}

@Composable
private fun DetailBody(
    cow: Cow?,
    readings: List<Reading>,
    alerts: List<Alert>,
    selected: Range,
    onSelectRange: (Range) -> Unit,
    onBack: () -> Unit,
    cowId: String,
    onExport: () -> Unit = {},
) {
    val windowMs = TimeUnit.HOURS.toMillis(selected.hours)
    val cutoff = Date(System.currentTimeMillis() - windowMs)
    val inRange = readings.filter { it.timestamp.after(cutoff) }

    // 7d view uses SERVER-SIDE downsampling (#10): a week of 20s readings is
    // ~30k rows and the raw fetch caps at 5,000 (silent truncation). The
    // readings_downsample RPC returns ~1,000 10-minute buckets instead.
    var buckets by remember { mutableStateOf<List<com.example.cattlemonitor.data.Bucket>?>(null) }
    LaunchedEffect(selected) {
        if (selected == Range.D7) {
            buckets = ServiceLocator.repository.fetchDownsampled(
                cowId,
                Date(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24 * 7)),
            )
        } else {
            buckets = null
        }
    }
    val useBuckets = selected == Range.D7 && !buckets.isNullOrEmpty()
    // Suspicious readings (out-of-cow-range temp) stay visible but annotated
    // as hollow circles — hiding them would make the chart lie by omission.
    val tempValuesRaw = inRange.map { it.temperature }
    val tempSuspicious = inRange
        .withIndex()
        .filter { it.value.suspicious }
        .map { it.index }
        .toSet()
    val suspiciousCount = inRange.count { it.suspicious }
    val activityValues = inRange.map { (it.activityIndex ?: 0.0).toActivityIndex() }

    // Alert event markers (#2): for each alert in the window, mark the bucket
    // or reading at/before the alert's timestamp — the moment the pattern
    // became visible on the chart.
    val tempValues = if (useBuckets) {
        buckets!!.map { it.tempAvg ?: 38.5 }
    } else {
        tempValuesRaw
    }
    val alertMarkers = remember(alerts, inRange, buckets, cutoff) {
        alerts.mapNotNull { alert ->
            if (alert.timestamp.before(cutoff)) return@mapNotNull null
            if (useBuckets) {
                buckets!!.indexOfFirst { it.bucketStart.after(alert.timestamp) }
                    .let { if (it > 0) it - 1 else null }
            } else {
                var best: Int? = null
                for (i in inRange.indices) {
                    if (!inRange[i].timestamp.after(alert.timestamp)) best = i else break
                }
                best
            }
        }.toSet()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OfflineBanner()
        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
        item {
            Row(
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.detail_back), color = Brand, style = MaterialTheme.typography.titleSmall)
            }
            EstrusWindowCard(alerts, cow)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                com.example.cattlemonitor.ui.common.CowAvatar(cow = cow, size = 44.dp)
                Text(
                    cow?.name ?: cowId,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                cow?.let { StatusBadge(it.status) }
            }
            cow?.let {
                Text(
                    statusExplanation(it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 14.dp),
            ) {
                Range.entries.forEach { r ->
                    RangeChip(rangeLabel(r), selected == r) { onSelectRange(r) }
                }
                Spacer(Modifier.weight(1f))
                RangeChip(stringResource(R.string.detail_export), selected = false, onClick = onExport)
            }
        }
        item { SectionLabel(stringResource(R.string.detail_temp_section)) }
        item {
            ChartCard {
                LineChart(
                    values = tempValues,
                    modifier = Modifier.fillMaxWidth(),
                    baselineValue = cow?.baselineTemp,
                    showAxis = true,
                    valueSuffix = "°",
                    xAxisLabels = timeAxisLabels(selected),
                    suspicious = tempSuspicious,
                    alertMarkers = alertMarkers,
                )
            }
            if (suspiciousCount > 0) {
                Text(
                    stringResource(R.string.detail_suspicious_caption, suspiciousCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        item {
            SectionLabel(
                stringResource(
                    R.string.detail_activity_section,
                    cow?.name ?: stringResource(R.string.detail_this_cow),
                ),
            )
        }
        item {
            ChartCard {
                LineChart(
                    values = activityValues,
                    modifier = Modifier.fillMaxWidth(),
                    color = Accent,
                    baselineValue = cow?.baselineActivity?.toActivityIndex(),
                    showAxis = true,
                    xAxisLabels = timeAxisLabels(selected),
                )
            }
        }
        item { SectionLabel(stringResource(R.string.detail_alert_history)) }
        if (alerts.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.detail_no_alerts),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(alerts, key = { it.id }) { a ->
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    Text(
                        "${alertTypeLabel(a.type)} · ${relativeTime(a.timestamp)}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        a.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
}

/**
 * Breeding-window countdown for an UNRESOLVED possible_estrus alert (#7):
 * cattle estrus acceptance lasts roughly 12–18h with onset at detection;
 * show the remaining window prominently — direct economic value for the
 * farmer (AI timing) and a memorable pitch moment.
 */
@Composable
private fun EstrusWindowCard(alerts: List<Alert>, cow: Cow?) {
    val open = alerts.firstOrNull { it.type == AlertType.POSSIBLE_ESTRUS && !it.resolved } ?: return
    val hoursElapsed = (System.currentTimeMillis() - open.timestamp.time) / 3_600_000.0
    val windowStart = 12.0
    val windowEnd = 18.0
    val remaining = windowEnd - hoursElapsed
    val optimal = hoursElapsed in windowStart..windowEnd

    val text = when {
        remaining <= 0 -> stringResource(R.string.estrus_window_closed)
        optimal -> stringResource(R.string.estrus_window_open, remaining.roundToInt())
        else -> stringResource(
            R.string.estrus_window_upcoming,
            (windowStart - hoursElapsed).roundToInt().coerceAtLeast(1),
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(StatusWarning.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
            .border(1.dp, StatusWarning, RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        Text(
            stringResource(R.string.estrus_window_title, cow?.name ?: ""),
            style = MaterialTheme.typography.titleSmall,
            color = StatusWarning,
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
    )
}

@Composable
private fun ChartCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(10.dp))
            .border(1.dp, Line, RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) { content() }
}

@Composable
private fun RangeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) Brand else Color.Transparent)
            .border(1.dp, if (selected) Brand else Line, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
