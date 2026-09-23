package com.example.cattlemonitor.ui.overview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.cattlemonitor.data.Cow
import com.example.cattlemonitor.ui.common.EmptyState
import com.example.cattlemonitor.ui.common.StatusBadge
import com.example.cattlemonitor.util.TimeFormat

@Composable
fun OverviewScreen(
    onOpenCow: (String) -> Unit,
    onOpenAlerts: () -> Unit,
    onOpenDevice: () -> Unit,
    onOpenSettings: () -> Unit,
    vm: OverviewViewModel = viewModel(),
) {
    val cows by vm.cows.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Herd", style = MaterialTheme.typography.headlineSmall)
            Row {
                Text(
                    "Alerts",
                    modifier = Modifier
                        .clickable(onClick = onOpenAlerts)
                        .padding(8.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "Devices",
                    modifier = Modifier
                        .clickable(onClick = onOpenDevice)
                        .padding(8.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "Settings",
                    modifier = Modifier
                        .clickable(onClick = onOpenSettings)
                        .padding(8.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        when {
            cows == null -> EmptyState("Loading herd…")
            cows!!.isEmpty() -> EmptyState(
                "No cows added yet.\nUse Devices → add a cow and its ESP32 device id.",
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(cows!!, key = { it.id }) { cow ->
                    CowCard(cow) { onOpenCow(cow.id) }
                }
            }
        }
    }
}

@Composable
private fun CowCard(cow: Cow, onClick: () -> Unit) {
    Card(modifier = Modifier.clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(cow.name, style = MaterialTheme.typography.titleMedium)
                StatusBadge(cow.status)
            }
            Spacer(modifier = Modifier.height(4.dp))
            cow.latestTemp?.let {
                Text(
                    "${String.format("%.1f", it)}°C",
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            cow.lastSeen?.let {
                Text(
                    "updated ${TimeFormat.relative(it)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            } ?: Text(
                "no data received yet",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
