package com.example.cattlemonitor.ui.overview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.cattlemonitor.R
import com.example.cattlemonitor.ServiceLocator
import com.example.cattlemonitor.data.Cow
import com.example.cattlemonitor.data.CowStatus
import com.example.cattlemonitor.ui.common.AppCard
import com.example.cattlemonitor.ui.common.CowAvatar
import com.example.cattlemonitor.ui.common.EmptyState
import com.example.cattlemonitor.ui.common.LoadingState
import com.example.cattlemonitor.ui.common.OfflineBanner
import com.example.cattlemonitor.ui.common.OfflineScreen
import com.example.cattlemonitor.ui.common.ScreenHeader
import com.example.cattlemonitor.ui.common.ScreenPadding
import com.example.cattlemonitor.ui.common.SectionHeader
import com.example.cattlemonitor.ui.common.SegmentedToggle
import com.example.cattlemonitor.ui.common.StatusBadge
import com.example.cattlemonitor.ui.common.StatusDot
import com.example.cattlemonitor.ui.common.relativeTime
import com.example.cattlemonitor.ui.theme.Brand
import com.example.cattlemonitor.ui.theme.StatusAlert
import com.example.cattlemonitor.ui.theme.StatusNormal
import com.example.cattlemonitor.ui.theme.StatusOffline
import com.example.cattlemonitor.ui.theme.StatusWarning
import com.example.cattlemonitor.ui.theme.statusColor
import java.util.Date

/** Most urgent first, so cows that need attention are at the top of the list. */
private fun CowStatus.urgency(): Int = when (this) {
    CowStatus.ALERT -> 0
    CowStatus.WARNING -> 1
    CowStatus.OFFLINE -> 2
    CowStatus.NORMAL -> 3
}

@Composable
fun OverviewScreen(
    onOpenCow: (String) -> Unit,
    onAddCow: () -> Unit = {},
    vm: OverviewViewModel = viewModel(),
) {
    val isOnline by ServiceLocator.connectivity.isOnline.collectAsState()
    val cows by vm.cows.collectAsState()
    // Hybrid offline (#9): full gate only with nothing cached; otherwise a
    // slim banner over the last-known data.
    if (!isOnline && cows == null) {
        OfflineScreen()
        return
    }
    var stack by rememberSaveable { mutableStateOf(false) }
    // INSTANT staleness (#1): judge silence locally (last_seen vs 3x interval)
    // instead of waiting up to 15 min for the offline cron.
    val now = remember(cows) { Date() }

    Column(modifier = Modifier.fillMaxSize()) {
        OfflineBanner()
        val list = cows.orEmpty()
        // Live freshness: age of the newest last_seen across the herd. Falls
        // back to "no readings yet" when no cow has ever reported.
        val newest = list.mapNotNull { it.lastSeen }.maxOrNull()
        val freshness = newest?.let { stringResource(R.string.overview_updated_relative, relativeTime(it)) }
            ?: stringResource(R.string.overview_updated_now)
        ScreenHeader(
            title = stringResource(R.string.overview_title),
            // NOTE: plurals need pluralStringResource — stringResource() on a
            // <plurals> id throws Resources$NotFoundException.
            subtitle = if (cows == null) null
            else pluralStringResource(R.plurals.overview_cow_count, list.size, list.size) + " · " + freshness,
        )

        when {
            cows == null -> LoadingState(stringResource(R.string.overview_loading))
            list.isEmpty() -> EmptyState(
                title = stringResource(R.string.overview_empty_title),
                text = stringResource(R.string.overview_empty),
                icon = Icons.Filled.Add,
                actionLabel = stringResource(R.string.overview_add_cow),
                onAction = onAddCow,
            )
            else -> {
                val sorted = remember(list, now) {
                    list.sortedWith(compareBy<Cow>({ it.effectiveStatus(now).urgency() }, { it.name.lowercase() }))
                }
                // One scrolling grid for everything, so the summary scrolls away
                // with the cards instead of eating half the screen.
                LazyVerticalGrid(
                    columns = GridCells.Fixed(if (stack) 1 else 2),
                    contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) { HerdHealthCard(list, now) }
                    item(span = { GridItemSpan(maxLineSpan) }) { StatStrip(list, now) }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SectionHeader(
                            text = stringResource(R.string.overview_section_cows),
                            modifier = Modifier.padding(top = 0.dp),
                        ) {
                            SegmentedToggle(
                                options = listOf(stringResource(R.string.layout_grid), stringResource(R.string.layout_stack)),
                                selectedIndex = if (stack) 1 else 0,
                                onSelect = { stack = it == 1 },
                            )
                        }
                    }
                    items(sorted, key = { it.id }) { cow ->
                        if (stack) {
                            CowListCard(cow, now) { onOpenCow(cow.id) }
                        } else {
                            CowGridCard(cow, now) { onOpenCow(cow.id) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Colored summary at the top of the herd: one plain-language sentence about
 * how the herd is doing, plus a ring showing the share of healthy cows.
 */
@Composable
private fun HerdHealthCard(cows: List<Cow>, now: Date) {
    val statuses = cows.map { it.effectiveStatus(now) }
    val alerts = statuses.count { it == CowStatus.ALERT }
    val warnings = statuses.count { it == CowStatus.WARNING }
    val offline = statuses.count { it == CowStatus.OFFLINE }
    val healthy = statuses.count { it == CowStatus.NORMAL }
    val attention = alerts + warnings

    val background = when {
        alerts > 0 -> StatusAlert
        warnings > 0 -> StatusWarning
        offline > 0 -> StatusOffline
        else -> Brand
    }
    val headline = when {
        attention > 0 -> pluralStringResource(R.plurals.overview_need_attention, attention, attention)
        offline > 0 -> pluralStringResource(R.plurals.overview_collars_offline, offline, offline)
        else -> stringResource(R.string.overview_all_healthy)
    }
    val fraction = if (cows.isEmpty()) 0f else healthy.toFloat() / cows.size

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(24.dp))
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.overview_health_label).uppercase(),
                style = MaterialTheme.typography.titleSmall,
                color = Color.White.copy(alpha = 0.75f),
            )
            Spacer(Modifier.height(6.dp))
            Text(headline, style = MaterialTheme.typography.titleLarge, color = Color.White)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.overview_healthy_of, healthy, cows.size),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
        Spacer(Modifier.width(16.dp))
        HealthRing(fraction = fraction, modifier = Modifier.size(76.dp))
    }
}

@Composable
private fun HealthRing(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 7.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = Color.White.copy(alpha = 0.25f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke),
            )
            drawArc(
                color = Color.White,
                startAngle = -90f,
                sweepAngle = 360f * fraction,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Text(
                stringResource(R.string.overview_healthy_ring),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.8f),
            )
        }
    }
}

/** One tile per status with its count. */
@Composable
private fun StatStrip(cows: List<Cow>, now: Date) {
    val counts = CowStatus.entries.associateWith { s -> cows.count { it.effectiveStatus(now) == s } }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CowStatus.entries.forEach { status ->
            val count = counts[status] ?: 0
            val color = statusColor(status)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (count > 0) color.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface,
                        RoundedCornerShape(16.dp),
                    )
                    .border(
                        1.dp,
                        if (count > 0) color.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(16.dp),
                    )
                    .padding(horizontal = 10.dp, vertical = 10.dp),
            ) {
                Text(
                    "$count",
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (count > 0) color else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(color, size = 6.dp)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        statusLabelPlain(status),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun statusLabelPlain(status: CowStatus) = stringResource(
    when (status) {
        CowStatus.NORMAL -> R.string.status_label_normal
        CowStatus.WARNING -> R.string.status_label_watch
        CowStatus.ALERT -> R.string.status_label_alert
        CowStatus.OFFLINE -> R.string.status_label_offline
    },
)

/** Cards that need attention get a status-tinted border so they stand out. */
@Composable
private fun cardBorder(status: CowStatus): Color = when (status) {
    CowStatus.ALERT, CowStatus.WARNING -> statusColor(status).copy(alpha = 0.6f)
    else -> MaterialTheme.colorScheme.outline
}

/** "38.6" big, "°C" small. */
@Composable
private fun TemperatureText(temp: Double?, big: Boolean) {
    val style = if (big) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall
    if (temp == null) {
        Text("—", style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Text(
        buildAnnotatedString {
            append(String.format("%.1f", temp))
            withStyle(SpanStyle(fontSize = MaterialTheme.typography.titleMedium.fontSize, color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                append(" °C")
            }
        },
        style = style,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** Compact two-column card: photo + badge on top, temperature, then freshness and battery. */
@Composable
private fun CowGridCard(cow: Cow, now: Date, onClick: () -> Unit) {
    val status = cow.effectiveStatus(now) // instant OFFLINE within ~1 min of silence
    AppCard(onClick = onClick, borderColor = cardBorder(status), contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            CowAvatar(cow = cow, size = 44.dp)
            Spacer(Modifier.weight(1f))
            StatusBadge(status = status)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            cow.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        TemperatureText(cow.latestTemp, big = false)
        Spacer(Modifier.height(8.dp))
        CardFooter(cow)
    }
}

/** Full-width list card: photo, name and freshness on the left; temperature and badge on the right. */
@Composable
private fun CowListCard(cow: Cow, now: Date, onClick: () -> Unit) {
    val status = cow.effectiveStatus(now)
    AppCard(onClick = onClick, borderColor = cardBorder(status), contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CowAvatar(cow = cow, size = 56.dp)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    cow.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                CardFooter(cow)
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                StatusBadge(status = status)
                Spacer(Modifier.height(6.dp))
                TemperatureText(cow.latestTemp, big = false)
            }
        }
    }
}

@Composable
private fun CardFooter(cow: Cow) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            cow.lastSeen?.let { relativeTime(it) } ?: stringResource(R.string.overview_no_data_yet),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        cow.batteryLevel?.let {
            Spacer(Modifier.width(10.dp))
            BatteryIndicator(it)
        }
    }
}

/** Small battery bar + percent, shown only when the collar reports battery. */
@Composable
fun BatteryIndicator(level: Double, modifier: Modifier = Modifier) {
    val pct = level.toInt().coerceIn(0, 100)
    val fill = when {
        pct >= 50 -> StatusNormal
        pct >= 20 -> StatusWarning
        else -> StatusAlert
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .width(18.dp)
                .height(10.dp)
                .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(2.dp))
                .padding(1.5.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width((15 * pct / 100f).dp)
                    .background(fill, RoundedCornerShape(1.dp)),
            )
        }
        Text("$pct%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
