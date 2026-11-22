package com.example.cattlemonitor.ui.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
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
 * Herd summary: one plain sentence about the herd, a bar showing how the
 * herd splits across statuses, and a legend with the counts.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HerdHealthCard(cows: List<Cow>, now: Date) {
    val counts = CowStatus.entries.associateWith { s -> cows.count { it.effectiveStatus(now) == s } }
    val alerts = counts[CowStatus.ALERT] ?: 0
    val warnings = counts[CowStatus.WARNING] ?: 0
    val offline = counts[CowStatus.OFFLINE] ?: 0
    val attention = alerts + warnings
    val headline = when {
        attention > 0 -> pluralStringResource(R.plurals.overview_need_attention, attention, attention)
        offline > 0 -> pluralStringResource(R.plurals.overview_collars_offline, offline, offline)
        else -> stringResource(R.string.overview_all_healthy)
    }
    // Most urgent segment first, matching the card order below.
    val order = listOf(CowStatus.ALERT, CowStatus.WARNING, CowStatus.OFFLINE, CowStatus.NORMAL)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brand)
            .padding(20.dp),
    ) {
        Text(
            stringResource(R.string.overview_health_label).uppercase(),
            style = MaterialTheme.typography.titleSmall,
            color = Color.White.copy(alpha = 0.7f),
        )
        Text(
            headline,
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
        // Composition bar: one segment per status, width = share of the herd.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.18f)),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            order.forEach { status ->
                val n = counts[status] ?: 0
                if (n > 0) {
                    Box(
                        modifier = Modifier
                            .weight(n.toFloat())
                            .fillMaxHeight()
                            .background(if (status == CowStatus.NORMAL) Color(0xFFB9D7A8) else statusColor(status)),
                    )
                }
            }
        }
        FlowRow(
            modifier = Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            order.reversed().forEach { status ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(if (status == CowStatus.NORMAL) Color(0xFFB9D7A8) else statusColor(status), size = 8.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${counts[status] ?: 0} ${statusLabelPlain(status)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = 0.9f),
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
