package com.example.cattlemonitor.ui.alerts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import com.example.cattlemonitor.data.Alert
import com.example.cattlemonitor.ui.common.EmptyState
import com.example.cattlemonitor.util.TimeFormat

private enum class Filter(val label: String) { ALL("All"), OPEN("Unresolved"), DONE("Resolved") }

@Composable
fun AlertsScreen(onOpenCow: (String) -> Unit, vm: AlertsViewModel = viewModel()) {
    val alerts by vm.alerts.collectAsState()
    var filter by remember { mutableStateOf(Filter.OPEN) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Alerts", style = MaterialTheme.typography.headlineSmall)
        Row {
            Filter.entries.forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text(f.label) },
                    modifier = Modifier.padding(end = 8.dp),
                )
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
            alerts == null -> EmptyState("Loading alerts…")
            list.isEmpty() -> EmptyState("No ${filter.name.lowercase()} alerts.")
            else -> LazyColumn {
                items(list, key = { it.id }) { AlertRow(it, onOpenCow) }
            }
        }
    }
}

@Composable
private fun AlertRow(a: Alert, onOpenCow: (String) -> Unit) {
    Column(
        modifier = Modifier
            .clickable { onOpenCow(a.cowId) }
            .padding(vertical = 8.dp),
    ) {
        Text(
            "${a.type.name.lowercase().replace('_', ' ')}" +
                if (a.resolved) "  · resolved" else "",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(a.note, style = MaterialTheme.typography.bodySmall)
        Text(
            TimeFormat.relative(a.timestamp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
