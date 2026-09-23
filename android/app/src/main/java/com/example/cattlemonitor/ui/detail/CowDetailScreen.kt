package com.example.cattlemonitor.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.cattlemonitor.data.Alert
import com.example.cattlemonitor.data.Cow
import com.example.cattlemonitor.data.Reading
import com.example.cattlemonitor.ui.common.LineChart
import com.example.cattlemonitor.ui.common.StatusBadge
import com.example.cattlemonitor.ui.status.StatusText
import com.example.cattlemonitor.util.TimeFormat
import java.util.Date
import java.util.concurrent.TimeUnit

private enum class Range(val hours: Long, val label: String) {
    H24(24, "24h"),
    D7(24 * 7, "7d"),
}

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
    val cow by vm.cow.collectAsState()
    val readings by vm.readings.collectAsState()
    val alerts by vm.alerts.collectAsState()
    var range by remember { mutableStateOf(Range.H24) }

    DetailBody(
        cow = cow,
        readings = readings.orEmpty(),
        alerts = alerts.orEmpty(),
        selected = range,
        onSelectRange = { range = it },
        onBack = onBack,
        cowId = cowId,
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
) {
    val windowMs = TimeUnit.HOURS.toMillis(selected.hours)
    val cutoff = Date(System.currentTimeMillis() - windowMs)
    val inRange = readings.filter { it.timestamp.after(cutoff) }
    val tempValues = inRange
        .filter { it.temperature in 30.0..45.0 }
        .map { it.temperature }
    val activityValues = inRange.map { it.activityIndex ?: 0.0 }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(cow?.name ?: cowId, style = MaterialTheme.typography.headlineSmall)
                cow?.let { StatusBadge(it.status) }
            }
            Text(
                StatusText.explain(cow ?: return@item),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "← Back",
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Range.entries.forEach { r ->
                    FilterChip(
                        selected = selected == r,
                        onClick = { onSelectRange(r) },
                        label = { Text(r.label) },
                    )
                }
            }
        }
        item {
            Text("Temperature (°C)", style = MaterialTheme.typography.titleSmall)
        }
        item {
            LineChart(values = tempValues, modifier = Modifier.fillMaxWidth())
        }
        item {
            Text("Activity", style = MaterialTheme.typography.titleSmall)
        }
        item {
            LineChart(values = activityValues, modifier = Modifier.fillMaxWidth())
        }
        item {
            Text("Alert history", style = MaterialTheme.typography.titleSmall)
        }
        if (alerts.isEmpty()) {
            item {
                Text(
                    "No alerts recorded for this cow.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else {
            items(alerts, key = { it.id }) { a ->
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    Text("${a.type.name.lowercase()} · ${TimeFormat.relative(a.timestamp)}")
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
