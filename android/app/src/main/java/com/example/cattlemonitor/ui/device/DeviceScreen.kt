package com.example.cattlemonitor.ui.device

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.cattlemonitor.R
import com.example.cattlemonitor.ServiceLocator
import com.example.cattlemonitor.data.DeviceInUseException
import com.example.cattlemonitor.ui.common.CowAvatar
import com.example.cattlemonitor.ui.common.OfflineBanner
import com.example.cattlemonitor.ui.common.OfflineScreen
import com.example.cattlemonitor.ui.theme.Accent
import com.example.cattlemonitor.ui.theme.Brand
import com.example.cattlemonitor.ui.theme.BrandSoft
import com.example.cattlemonitor.ui.theme.Line
import com.example.cattlemonitor.ui.theme.Surface

@Composable
fun DeviceScreen(vm: DeviceViewModel = viewModel()) {
    val context = LocalContext.current
    val isOnline by ServiceLocator.connectivity.isOnline.collectAsState()
    val cows by vm.cows.collectAsState()
    fun failureMessage(e: Throwable): String = when (e) {
        is DeviceInUseException -> context.getString(R.string.devices_conflict)
        else -> context.getString(R.string.devices_error_prefix, e.message ?: "")
    }
    // Hybrid offline (#9).
    if (!isOnline && cows == null) {
        OfflineScreen()
        return
    }
    var name by remember { mutableStateOf("") }
    var deviceId by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    // Rename flow: dialog target + the text field's current value.
    var renameTarget by remember { mutableStateOf<com.example.cattlemonitor.data.Cow?>(null) }
    var renameValue by remember { mutableStateOf("") }
    renameTarget?.let { target ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.devices_rename_title)) },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    label = { Text(stringResource(R.string.devices_cow_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        val newName = renameValue.trim()
                        renameTarget = null
                        if (newName.isNotEmpty() && newName != target.name) {
                            vm.updateCow(target.id, newName, null) { result ->
                                message = result.fold(
                                    onSuccess = { context.getString(R.string.devices_renamed) },
                                    onFailure = { failureMessage(it) },
                                )
                            }
                        }
                    },
                    enabled = renameValue.isNotBlank(),
                ) { Text(stringResource(R.string.devices_rename)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { renameTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    // Photo pick → upload to Storage → update cow row (photo feature).
    var photoTarget by remember { mutableStateOf<com.example.cattlemonitor.data.Cow?>(null) }
    // Cows that already HAVE a photo get a change/remove chooser instead.
    var photoChoiceTarget by remember { mutableStateOf<com.example.cattlemonitor.data.Cow?>(null) }
    val pickPhoto = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        val cow = photoTarget
        if (uri != null && cow != null) {
            vm.setCowPhoto(cow.id, uri) { result ->
                message = result.fold(
                    onSuccess = { context.getString(R.string.devices_photo_saved) },
                    onFailure = { context.getString(R.string.devices_error_prefix, it.message ?: "") },
                )
            }
        }
        photoTarget = null
    }
    photoChoiceTarget?.let { target ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { photoChoiceTarget = null },
            title = { Text(target.name) },
            text = { Text(stringResource(R.string.devices_photo_choose_hint)) },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        photoChoiceTarget = null
                        photoTarget = target
                        pickPhoto.launch("image/*")
                    },
                ) { Text(stringResource(R.string.devices_photo_change)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        photoChoiceTarget = null
                        vm.removeCowPhoto(target.id) { result ->
                            message = result.fold(
                                onSuccess = { context.getString(R.string.devices_photo_removed) },
                                onFailure = { context.getString(R.string.devices_error_prefix, it.message ?: "") },
                            )
                        }
                    },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = Accent,
                    ),
                ) { Text(stringResource(R.string.devices_photo_remove)) }
            },
        )
    }


    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
        OfflineBanner()
        Text(stringResource(R.string.devices_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.devices_registered_count, cows.orEmpty().size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.devices_cow_name)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = deviceId,
            onValueChange = { deviceId = it },
            label = { Text(stringResource(R.string.devices_device_id)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    vm.addCow(name.trim(), deviceId.trim()) { result ->
                        message = result.fold(
                            onSuccess = {
                                name = ""
                                deviceId = ""
                                context.getString(R.string.devices_added)
                            },
                            onFailure = { failureMessage(it) },
                        )
                    }
                },
                enabled = name.isNotBlank() && deviceId.isNotBlank(),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Brand),
            ) { Text(stringResource(R.string.devices_add)) }
            // QR pairing (#6): scan the collar sticker instead of typing.
            val scanQr = rememberQrScanLauncher { scanned ->
                if (!scanned.isNullOrBlank()) deviceId = scanned
            }
            androidx.compose.material3.OutlinedButton(onClick = { scanQr() }) {
                Text(stringResource(R.string.devices_scan_qr))
            }
        }
        message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Text(
            stringResource(R.string.devices_registered_header),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 22.dp, bottom = 6.dp),
        )
        Text(
            stringResource(R.string.devices_photo_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val list = cows.orEmpty()
        if (list.isEmpty()) {
            Text(
                stringResource(R.string.devices_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn {
                items(list, key = { it.id }) { cow ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        com.example.cattlemonitor.ui.common.CowAvatar(
                            cow = cow,
                            size = 34.dp,
                            onClick = {
                                if (cow.imageUrl != null) {
                                    // Already has a photo: offer change or remove.
                                    photoChoiceTarget = cow
                                } else {
                                    photoTarget = cow
                                    pickPhoto.launch("image/*")
                                }
                            },
                        )
                        Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(cow.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                cow.deviceId,
                                style = MaterialTheme.typography.labelSmall,
                                color = BrandSoft,
                            )
                        }
                        Text(
                            stringResource(R.string.devices_rename),
                            modifier = Modifier.clickable {
                                renameTarget = cow
                                renameValue = cow.name
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Brand,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.devices_remove),
                            modifier = Modifier.clickable {
                                vm.deleteCow(cow.id) { result ->
                                    message = result.fold(
                                        onSuccess = { context.getString(R.string.devices_deleted) },
                                        onFailure = { failureMessage(it) },
                                    )
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Accent,
                        )
                    }
                }
            }
        }
    }
}
