package com.example.cattlemonitor.ui.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.cattlemonitor.R
import com.example.cattlemonitor.ServiceLocator
import com.example.cattlemonitor.data.Cow
import com.example.cattlemonitor.data.CowStatus
import com.example.cattlemonitor.ui.common.CowAvatar
import com.example.cattlemonitor.ui.common.EmptyState
import com.example.cattlemonitor.ui.common.OfflineBanner
import com.example.cattlemonitor.ui.common.OfflineScreen
import com.example.cattlemonitor.ui.common.StatusBadge
import com.example.cattlemonitor.ui.common.relativeTime
import com.example.cattlemonitor.ui.common.statusLabel
import com.example.cattlemonitor.ui.theme.Brand
import com.example.cattlemonitor.ui.theme.InkSoft
import com.example.cattlemonitor.ui.theme.Line
import com.example.cattlemonitor.ui.theme.StatusAlert
import com.example.cattlemonitor.ui.theme.StatusNormal
import com.example.cattlemonitor.ui.theme.StatusWarning
import com.example.cattlemonitor.ui.theme.Surface
import java.util.Date

@Composable
fun OverviewScreen(
    onOpenCow: (String) -> Unit,
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
    var stack by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        OfflineBanner()
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(stringResource(R.string.overview_title), style = MaterialTheme.typography.headlineSmall)
            val list = cows.orEmpty()
            // Live freshness: age of the newest last_seen across the herd, not a
            // hardcoded "updated moments ago". Falls back to the plain phrase
            // when no cow has ever reported (all null last_seen).
            val newest = list.mapNotNull { it.lastSeen }.maxOrNull()
            val freshness = newest?.let { stringResource(R.string.overview_updated_relative, relativeTime(it)) }
                ?: stringResource(R.string.overview_updated_now)
            Text(
                // NOTE: plurals need pluralStringResource — stringResource() on a
                // <plurals> id throws Resources$NotFoundException (the post-login
                // crash on real devices).
                pluralStringResource(R.plurals.overview_cow_count, list.size, list.size) + " · " + freshness,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        cows?.let { list ->
            if (list.isNotEmpty()) {
                StatStrip(list)
            }
        }

        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LayoutChip(stringResource(R.string.layout_grid), selected = !stack) { stack = false }
            LayoutChip(stringResource(R.string.layout_stack), selected = stack) { stack = true }
        }

        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            when {
                cows == null -> EmptyState(stringResource(R.string.overview_loading))
                cows!!.isEmpty() -> EmptyState(stringResource(R.string.overview_empty))
                stack -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                ) {
                    lazyItems(cows!!, key = { it.id }) { cow ->
                        CowCard(cow, stack = true) { onOpenCow(cow.id) }
                    }
                }
                else -> LazyVerticalGrid(
                    // Hard 2 columns per the design mockup. (Adaptive(160.dp)
                    // computed a single column on narrow phones — grid looked
                    // identical to stack.)
                    columns = GridCells.Fixed(2),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                ) {
                    items(cows!!, key = { it.id }) { cow ->
                        CowCard(cow, stack = false) { onOpenCow(cow.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LayoutChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Brand else Color.Transparent)
            .border(1.dp, if (selected) Brand else Line, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 4.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) Color.White else InkSoft,
        )
    }
}

@Composable
private fun StatStrip(cows: List<Cow>) {
    // INSTANT staleness (#1): judge silence locally (last_seen vs 3x interval)
    // instead of waiting up to 15 min for the offline cron.
    val now = remember { Date() }
    val counts = CowStatus.entries.associateWith { s -> cows.count { it.effectiveStatus(now) == s } }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CowStatus.entries.forEach { status ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .background(Surface, RoundedCornerShape(10.dp))
                    .border(1.dp, Line, RoundedCornerShape(10.dp))
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Text(
                    "${counts[status] ?: 0}",
                    style = MaterialTheme.typography.headlineSmall,
                    color = com.example.cattlemonitor.ui.theme.statusColor(status),
                )
                Text(
                    statusLabelPlain(status),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Spacer(Modifier.height(6.dp))
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

/**
 * Cow card, per the approved design: rounded 20dp card whose status badge is
 * an ear tag hanging OFF THE TOP-RIGHT CORNER (offset upward, overlapping the
 * edge). Grid = 2-column compact card; Stack = identical card, full width.
 * Only shape/position differ — contents are the same in both modes.
 */
@Composable
private fun CowCard(cow: Cow, stack: Boolean, onClick: () -> Unit) {
    val now = remember { Date() }
    val status = cow.effectiveStatus(now) // instant OFFLINE within ~1 min of silence

    Box(modifier = Modifier.padding(top = 8.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface, RoundedCornerShape(20.dp))
                .border(1.dp, Line, RoundedCornerShape(20.dp))
                .clickable(onClick = onClick)
                .padding(start = 14.dp, end = 14.dp, top = 16.dp, bottom = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Stack mode has the width for the cow's photo INSIDE the card;
                // Grid stays compact.
                if (stack) {
                    CowAvatar(cow = cow, size = 56.dp)
                    Spacer(Modifier.width(12.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(cow.name, style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        cow.latestTemp?.let { "${String.format("%.1f", it)}°C" } ?: "—",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    LastSeenText(cow)
                    cow.batteryLevel?.let { BatteryIndicator(it) }
                }
            }
        }
        // Ear tag hanging over the top-right corner (8dp above the card edge).
        StatusBadge(
            status = status,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(y = (-8).dp),
        )
    }
}

@Composable
private fun LastSeenText(cow: Cow) {
    cow.lastSeen?.let {
        Text(
            stringResource(R.string.overview_updated_relative, relativeTime(it)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } ?: Text(
        stringResource(R.string.overview_no_data_yet),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Small battery bar + percent, shown only when the collar reports battery. */
@Composable
private fun BatteryIndicator(level: Double) {
    val pct = level.toInt().coerceIn(0, 100)
    val fill = when {
        pct >= 50 -> StatusNormal
        pct >= 20 -> StatusWarning
        else -> StatusAlert
    }
    Row(
        modifier = Modifier.padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .width(18.dp)
                .height(10.dp)
                .border(1.dp, InkSoft, RoundedCornerShape(2.dp))
                .padding(1.5.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width((15 * pct / 100f).dp)
                    .background(fill, RoundedCornerShape(1.dp)),
            )
        }
        Text("$pct%", style = MaterialTheme.typography.labelSmall, color = InkSoft)
    }
}
