package com.example.cattlemonitor.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.cattlemonitor.R
import com.example.cattlemonitor.ServiceLocator
import com.example.cattlemonitor.data.Alert
import com.example.cattlemonitor.data.AlertType
import com.example.cattlemonitor.data.Cow
import com.example.cattlemonitor.data.Reading
import com.example.cattlemonitor.data.isoToDate
import com.example.cattlemonitor.ui.alerts.AlertCard
import com.example.cattlemonitor.ui.common.AppCard
import com.example.cattlemonitor.ui.common.CowAvatar
import com.example.cattlemonitor.ui.common.LineChart
import com.example.cattlemonitor.ui.common.OfflineBanner
import com.example.cattlemonitor.ui.common.OfflineScreen
import com.example.cattlemonitor.ui.common.Pager
import com.example.cattlemonitor.ui.common.ScreenPadding
import com.example.cattlemonitor.ui.common.SectionHeader
import com.example.cattlemonitor.ui.common.SegmentedToggle
import com.example.cattlemonitor.ui.common.StatusBadge
import com.example.cattlemonitor.ui.common.relativeTime
import com.example.cattlemonitor.ui.common.statusExplanation
import com.example.cattlemonitor.ui.overview.BatteryIndicator
import com.example.cattlemonitor.ui.theme.Accent
import com.example.cattlemonitor.ui.theme.StatusNormal
import com.example.cattlemonitor.ui.theme.StatusWarning
import com.example.cattlemonitor.ui.theme.statusColor
import com.example.cattlemonitor.util.toActivityIndex
import kotlinx.coroutines.launch
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Alert history page size on Cow Detail. */
private const val ALERTS_PER_PAGE = 5

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
    val scope = rememberCoroutineScope()
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
                // Detailed export: fetch the FULL history from the server in
                // the background (up to 20k readings, every sensor column)
                // instead of exporting only the rows already loaded for the
                // charts. Falls back to the in-memory readings if the fetch
                // fails (e.g. offline), so export always produces a file.
                val currentAlerts = alerts.orEmpty()
                val cachedReadings = readings.orEmpty()
                scope.launch {
                    val fetched = runCatching {
                        ServiceLocator.repository.fetchReadingsForExport(currentCow.id, Date(0))
                    }.getOrElse { emptyList() }
                    val readingLines = if (fetched.isNotEmpty()) {
                        fetched.map { row ->
                            com.example.cattlemonitor.util.ExportLine(
                                timestamp = isoToDate(row.timestamp) ?: Date(0),
                                temperature = row.temperature,
                                activityIndex = row.activity,
                                battery = row.battery,
                                accelX = row.accelX,
                                accelY = row.accelY,
                                accelZ = row.accelZ,
                                gyroX = row.gyroX,
                                gyroY = row.gyroY,
                                gyroZ = row.gyroZ,
                                dataQuality = row.quality,
                            )
                        }
                    } else {
                        cachedReadings.map {
                            com.example.cattlemonitor.util.ExportLine(
                                timestamp = it.timestamp,
                                temperature = it.temperature,
                                activityIndex = it.activityIndex,
                                dataQuality = it.dataQuality,
                            )
                        }
                    }
                    val alertLines = currentAlerts.map {
                        com.example.cattlemonitor.util.ExportLine(
                            timestamp = it.timestamp,
                            alertType = it.type.name.lowercase(),
                            alertNote = it.note,
                        )
                    }
                    com.example.cattlemonitor.util.CsvExport.export(
                        context,
                        currentCow,
                        (readingLines + alertLines).sortedBy { it.timestamp },
                    )
                }
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


    val status = cow?.effectiveStatus(Date())
    var alertPage by rememberSaveable { mutableStateOf(0) }
    Column(modifier = Modifier.fillMaxSize()) {
        OfflineBanner()
        LazyColumn(
            contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            // Top bar: back on the left, export on the right.
            item {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack, contentPadding = PaddingValues(start = 0.dp, end = 12.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.detail_back), style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(
                        onClick = onExport,
                        enabled = cow != null,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.detail_export), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            // Hero: who she is, how she's doing, and why.
            item {
                val tint = status?.let { statusColor(it) } ?: MaterialTheme.colorScheme.outline
                AppCard(borderColor = tint.copy(alpha = 0.5f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CowAvatar(cow = cow, size = 64.dp)
                        Column(modifier = Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text(
                                cow?.name ?: cowId,
                                style = MaterialTheme.typography.headlineSmall,
                            )
                            cow?.let {
                                Text(
                                    it.deviceId,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        status?.let { StatusBadge(it) }
                    }
                    cow?.let {
                        Box(
                            modifier = Modifier
                                .padding(top = 14.dp)
                                .fillMaxWidth()
                                .background(tint.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                                .padding(12.dp),
                        ) {
                            Text(
                                statusExplanation(it),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }

            item { EstrusWindowCard(alerts, cow) }

            cow?.let { c -> item { MetricTiles(c) } }

            item {
                SegmentedToggle(
                    options = Range.entries.map { rangeLabel(it) },
                    selectedIndex = selected.ordinal,
                    onSelect = { onSelectRange(Range.entries[it]) },
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            item {
                ChartCard(
                    title = stringResource(R.string.detail_temp_section),
                    unit = "°C",
                    hasData = tempValues.size >= 2,
                ) {
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
                    if (suspiciousCount > 0) {
                        Row(modifier = Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Top) {
                            Icon(
                                Icons.Filled.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp).padding(top = 1.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                pluralStringResource(R.plurals.detail_suspicious_caption, suspiciousCount, suspiciousCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                ChartCard(
                    title = stringResource(R.string.detail_activity_section),
                    unit = stringResource(R.string.detail_activity_unit),
                    lineColor = Accent,
                    hasData = activityValues.size >= 2,
                ) {
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

            item { SectionHeader(stringResource(R.string.detail_alert_history), modifier = Modifier.padding(top = 0.dp)) }
            val pageCount = (alerts.size + ALERTS_PER_PAGE - 1) / ALERTS_PER_PAGE
            val page = alertPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            if (alerts.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.detail_no_alerts),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                // Paged instead of one endless scroll: ALERTS_PER_PAGE at a time.
                items(
                    alerts.drop(page * ALERTS_PER_PAGE).take(ALERTS_PER_PAGE),
                    key = { it.id },
                ) { a -> AlertCard(a, cow = null, onOpenCow = null) }
                item { Pager(page = page, pageCount = pageCount, onPageChange = { alertPage = it }) }
            }
        }
    }
}

/** At-a-glance numbers in one card: temperature and activity vs. her normal, freshness, battery. */
@Composable
private fun MetricTiles(cow: Cow) {
    val learning = stringResource(R.string.detail_learning_normal)
    val divider = MaterialTheme.colorScheme.outline
    AppCard(contentPadding = PaddingValues(0.dp)) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Metric(
                label = stringResource(R.string.detail_metric_temp),
                value = cow.latestTemp?.let { "%.1f °C".format(it) } ?: "—",
                caption = cow.baselineTemp?.let { stringResource(R.string.detail_normal_value, "%.1f °C".format(it)) } ?: learning,
                modifier = Modifier.weight(1f),
            )
            VerticalDivider(color = divider)
            Metric(
                label = stringResource(R.string.detail_metric_activity),
                value = cow.latestActivity?.let { "${it.toActivityIndex().roundToInt()}" } ?: "—",
                caption = cow.baselineActivity?.let {
                    stringResource(R.string.detail_normal_value, "${it.toActivityIndex().roundToInt()}")
                } ?: learning,
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider(color = divider)
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Metric(
                label = stringResource(R.string.detail_metric_last_seen),
                value = cow.lastSeen?.let { relativeTime(it) } ?: stringResource(R.string.overview_no_data_yet),
                modifier = Modifier.weight(1f),
            )
            cow.batteryLevel?.let { level ->
                VerticalDivider(color = divider)
                Metric(
                    label = stringResource(R.string.detail_metric_battery),
                    value = "${level.toInt().coerceIn(0, 100)}%",
                    modifier = Modifier.weight(1f),
                    trailing = { BatteryIndicator(level) },
                )
            }
        }
    }
}

@Composable
private fun Metric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            trailing?.invoke()
        }
        Text(value, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 4.dp))
        if (caption != null) {
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            .background(StatusWarning.copy(alpha = 0.14f), RoundedCornerShape(20.dp))
            .border(1.dp, StatusWarning, RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) {
        Text(
            stringResource(R.string.estrus_window_title, cow?.name ?: ""),
            style = MaterialTheme.typography.titleMedium,
            color = StatusWarning,
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Chart inside a card: title and unit on one line, a small legend, then the plot. */
@Composable
private fun ChartCard(
    title: String,
    unit: String,
    hasData: Boolean,
    lineColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.error,
    content: @Composable () -> Unit,
) {
    AppCard {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(8.dp))
            Text(
                unit,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
        Row(
            modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LegendSwatch(lineColor, stringResource(R.string.detail_legend_reading), line = true)
            Spacer(Modifier.width(14.dp))
            LegendSwatch(StatusNormal, stringResource(R.string.detail_legend_normal), line = false)
        }
        if (hasData) {
            content()
        } else {
            // LineChart draws nothing with fewer than 2 points; say so instead
            // of leaving an empty box.
            Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.detail_no_readings),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LegendSwatch(color: androidx.compose.ui.graphics.Color, label: String, line: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(14.dp)
                .height(if (line) 3.dp else 10.dp)
                .background(if (line) color else color.copy(alpha = 0.25f), RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
