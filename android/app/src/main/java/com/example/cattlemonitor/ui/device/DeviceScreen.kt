package com.example.cattlemonitor.ui.device

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.example.cattlemonitor.data.Cow
import com.example.cattlemonitor.ui.common.EmptyState

@Composable
fun DeviceScreen(onDone: () -> Unit, vm: DeviceViewModel = viewModel()) {
    val cows by vm.cows.collectAsState()
    var name by remember { mutableStateOf("") }
    var deviceId by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Devices", style = MaterialTheme.typography.headlineSmall)
        Row(modifier = Modifier.padding(vertical = 8.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Cow name") },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = deviceId,
                onValueChange = { deviceId = it },
                label = { Text("device_id (ESP32)") },
                modifier = Modifier.weight(1f),
            )
        }
        Row {
            Button(
                onClick = {
                    vm.addCow(name.trim(), deviceId.trim()) { result ->
                        message = result.fold(
                            onSuccess = {
                                name = ""
                                deviceId = ""
                                "Cow added."
                            },
                            onFailure = { "Error: ${it.message}" },
                        )
                    }
                },
                enabled = name.isNotBlank() && deviceId.isNotBlank(),
            ) { Text("Add cow") }
            Text(
                "Done",
                modifier = Modifier
                    .clickable(onClick = onDone)
                    .padding(12.dp),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        Text(
            "Registered devices",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 16.dp),
        )
        val list = cows.orEmpty()
        if (list.isEmpty()) {
            Text("No cows registered yet.", style = MaterialTheme.typography.bodySmall)
        } else {
            LazyColumn {
                items(list, key = { it.id }) { cow ->
                    Row(
                        modifier = Modifier.padding(vertical = 6.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(cow.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                cow.deviceId,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(
                            "Remove",
                            modifier = Modifier.clickable {
                                vm.deleteCow(cow.id) { message = it.fold({ "Deleted." }, { "Error: ${it.message}" }) }
                            },
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}
