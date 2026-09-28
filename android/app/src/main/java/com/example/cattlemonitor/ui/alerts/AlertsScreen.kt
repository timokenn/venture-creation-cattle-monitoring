package com.example.cattlemonitor.ui.alerts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.cattlemonitor.R
import com.example.cattlemonitor.ServiceLocator
import com.example.cattlemonitor.data.Alert
import com.example.cattlemonitor.data.CowStatus
import com.example.cattlemonitor.ui.common.EmptyState
import com.example.cattlemonitor.ui.common.OfflineBanner
import com.example.cattlemonitor.ui.common.OfflineScreen
import com.example.cattlemonitor.ui.common.alertTypeLabel
import com.example.cattlemonitor.ui.common.relativeTime
import com.example.cattlemonitor.ui.theme.Brand
import com.example.cattlemonitor.ui.theme.Line
import com.example.cattlemonitor.ui.theme.statusColor

private enum class Filter { ALL, OPEN, DONE }

// Alert type → the status color family it belongs to, for the severity bar.
private fun com.example.cattlemonitor.data.AlertType.severityStatus(): CowStatus = when (this) {
    com.example.cattlemonitor.data.AlertType.FEVER,
    com.example.cattlemonitor.data.AlertType.POSSIBLE_DISTRESS,
    com.example.cattlemonitor.data.AlertType.SENSOR_ISSUE,
    -> CowStatus.ALERT
    com.example.cattlemonitor.data.AlertType.LOW_ACTIVITY,
    com.example.cattlemonitor.data.AlertType.POSSIBLE_ESTRUS,
    -> CowStatus.WARNING
    com.example.cattlemonitor.data.AlertType.DEVICE_OFFLINE -> CowStatus.OFFLINE
}

@Composable
fun AlertsScreen(onOpenCow: (String) -> Unit, vm: AlertsViewModel = viewModel()) {
    val isOnline by ServiceLocator.connectivity.isOnline.collectAsState()
    val alerts by vm.alerts.collectAsState()
    // Hybrid offline (#9): banner over cached data, full gate only when empty.
    if (!isOnline && alerts == null) {
        OfflineScreen()
        return
    }
    var filter by remember { mutableStateOf(Filter.OPEN) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
        OfflineBanner()
        Text(stringResource(R.string.alerts_title), style = MaterialTheme.typography.headlineSmall)
        Row(modifier = Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Filter.entries.forEach { f ->
                FilterChip(filterLabel(f), selected = filter == f) { filter = f }
            }
        }
        val list = alerts.orEmpty().filter {
            when (filter) {
                Filter.ALL -> true
                Filter.OPEN -> !it.resolved
                Filter.DONE -> it.resolved
            }
        }
        when {
            alerts == null -> EmptyState(stringResource(R.string.alerts_loading))
            list.isEmpty() -> EmptyState(
                stringResource(
                    R.string.alerts_empty,
                    stringResource(
                        when (filter) {
                            Filter.ALL -> R.string.alerts_filter_all
                            Filter.OPEN -> R.string.alerts_filter_open
                            Filter.DONE -> R.string.alerts_filter_done
                        },
                    ).lowercase(),
                ),
            )
            else -> LazyColumn {
                items(list, key = { it.id }) { AlertRow(it, onOpenCow) }
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

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) Brand else Color.Transparent)
            .border(1.dp, if (selected) Brand else MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
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

@Composable
private fun AlertRow(a: Alert, onOpenCow: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clickable { onOpenCow(a.cowId) }
            .padding(vertical = 10.dp),
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(statusColor(a.type.severityStatus()), RoundedCornerShape(3.dp)),
        )
        Column(modifier = Modifier.padding(start = 10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Row {
                    Text(
                        alertTypeLabel(a.type),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (a.resolved) {
                        Text(
                            "  ·  " + stringResource(R.string.alerts_resolved_suffix),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    relativeTime(a.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(a.note, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
