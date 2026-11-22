package com.example.cattlemonitor.ui.alerts

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Notifications
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.cattlemonitor.R
import com.example.cattlemonitor.ServiceLocator
import com.example.cattlemonitor.data.Alert
import com.example.cattlemonitor.data.AlertType
import com.example.cattlemonitor.data.Cow
import com.example.cattlemonitor.data.CowStatus
import com.example.cattlemonitor.ui.common.AppCard
import com.example.cattlemonitor.ui.common.EmptyState
import com.example.cattlemonitor.ui.common.InfoPill
import com.example.cattlemonitor.ui.common.LoadingState
import com.example.cattlemonitor.ui.common.OfflineBanner
import com.example.cattlemonitor.ui.common.OfflineScreen
import com.example.cattlemonitor.ui.common.PillChip
import com.example.cattlemonitor.ui.common.ScreenHeader
import com.example.cattlemonitor.ui.common.ScreenPadding
import com.example.cattlemonitor.ui.common.alertTypeLabel
import com.example.cattlemonitor.ui.common.relativeTime
import com.example.cattlemonitor.ui.theme.StatusNormal
import com.example.cattlemonitor.ui.theme.statusColor

// Unresolved first: it's the default and the one people open the tab for.
private enum class Filter { OPEN, DONE, ALL }

/** Alert type → the status color family it belongs to, for the icon and accent. */
fun AlertType.severityStatus(): CowStatus = when (this) {
    AlertType.FEVER,
    AlertType.POSSIBLE_DISTRESS,
    AlertType.SENSOR_ISSUE,
    -> CowStatus.ALERT
    AlertType.LOW_ACTIVITY,
    AlertType.POSSIBLE_ESTRUS,
    -> CowStatus.WARNING
    AlertType.DEVICE_OFFLINE -> CowStatus.OFFLINE
}

@Composable
fun AlertsScreen(onOpenCow: (String) -> Unit, vm: AlertsViewModel = viewModel()) {
    val isOnline by ServiceLocator.connectivity.isOnline.collectAsState()
    val alerts by vm.alerts.collectAsState()
    val cows by vm.cows.collectAsState()
    // Hybrid offline (#9): banner over cached data, full gate only when empty.
    if (!isOnline && alerts == null) {
        OfflineScreen()
        return
    }
    var filter by rememberSaveable { mutableStateOf(Filter.OPEN) }
    val cowsById = remember(cows) { cows.orEmpty().associateBy { it.id } }
    val all = alerts.orEmpty()
    val openCount = all.count { !it.resolved }

    Column(modifier = Modifier.fillMaxSize()) {
        OfflineBanner()
        ScreenHeader(
            title = stringResource(R.string.alerts_title),
            subtitle = when {
                alerts == null -> null
                openCount > 0 -> pluralStringResource(R.plurals.alerts_open_count, openCount, openCount)
                else -> stringResource(R.string.alerts_all_clear)
            },
        )
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = ScreenPadding)
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Filter.entries.forEach { f ->
                PillChip(
                    label = filterLabel(f),
                    selected = filter == f,
                    onClick = { filter = f },
                    count = if (f == Filter.OPEN) openCount else null,
                )
            }
        }
        val list = all.filter {
            when (filter) {
                Filter.ALL -> true
                Filter.OPEN -> !it.resolved
                Filter.DONE -> it.resolved
            }
        }
        when {
            alerts == null -> LoadingState(stringResource(R.string.alerts_loading))
            list.isEmpty() -> when (filter) {
                Filter.OPEN -> EmptyState(
                    title = stringResource(R.string.alerts_empty_open_title),
                    text = stringResource(R.string.alerts_empty_open),
                    icon = Icons.Filled.CheckCircle,
                )
                Filter.DONE -> EmptyState(
                    title = stringResource(R.string.alerts_empty_done_title),
                    text = stringResource(R.string.alerts_empty_done),
                    icon = Icons.Filled.Notifications,
                )
                Filter.ALL -> EmptyState(
                    title = stringResource(R.string.alerts_empty_all_title),
                    text = stringResource(R.string.alerts_empty_all),
                    icon = Icons.Filled.Notifications,
                )
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(list, key = { it.id }) { AlertCard(it, cowsById[it.cowId], onOpenCow) }
            }
        }
    }
}

@Composable
private fun filterLabel(f: Filter): String = stringResource(
    when (f) {
        Filter.ALL -> R.string.alerts_filter_all
        Filter.OPEN -> R.string.alerts_filter_open
        Filter.DONE -> R.string.alerts_filter_done
    },
)

/**
 * Shared alert card, also used for the alert history on Cow Detail. A
 * severity-colored stripe on the left carries the urgency; no icon clutter.
 */
@Composable
fun AlertCard(a: Alert, cow: Cow?, onOpenCow: ((String) -> Unit)?) {
    val severity = if (a.resolved) StatusNormal else statusColor(a.type.severityStatus())
    AppCard(
        onClick = onOpenCow?.let { open -> { open(a.cowId) } },
        contentPadding = PaddingValues(0.dp),
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .fillMaxHeight()
                    .background(severity),
            )
            Column(modifier = Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        alertTypeLabel(a.type),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        relativeTime(a.timestamp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (cow != null || a.resolved) {
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        cow?.let {
                            Text(
                                it.name,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        if (a.resolved) InfoPill(stringResource(R.string.alerts_resolved), StatusNormal)
                    }
                }
                if (a.note.isNotBlank()) {
                    Text(
                        a.note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}
