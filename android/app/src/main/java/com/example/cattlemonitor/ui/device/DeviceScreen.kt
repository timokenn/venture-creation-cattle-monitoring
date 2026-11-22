package com.example.cattlemonitor.ui.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.cattlemonitor.R
import com.example.cattlemonitor.ServiceLocator
import com.example.cattlemonitor.data.Cow
import com.example.cattlemonitor.data.DeviceInUseException
import com.example.cattlemonitor.ui.common.AppCard
import com.example.cattlemonitor.ui.common.CowAvatar
import com.example.cattlemonitor.ui.common.OfflineBanner
import com.example.cattlemonitor.ui.common.OfflineScreen
import com.example.cattlemonitor.ui.common.ScreenHeader
import com.example.cattlemonitor.ui.common.ScreenPadding
import com.example.cattlemonitor.ui.common.SectionHeader
import com.example.cattlemonitor.ui.theme.Accent
import com.example.cattlemonitor.ui.theme.StatusNormal

/** Feedback line under the form; [isError] picks the color and icon. */
private data class Feedback(val text: String, val isError: Boolean)

@Composable
fun DeviceScreen(vm: DeviceViewModel = viewModel()) {
    val context = LocalContext.current
    val isOnline by ServiceLocator.connectivity.isOnline.collectAsState()
    val cows by vm.cows.collectAsState()
    fun failureMessage(e: Throwable): String = when (e) {
        is DeviceInUseException -> context.getString(R.string.devices_conflict)
        else -> context.getString(R.string.devices_error_prefix, e.message ?: "")
    }
    fun ok(text: String) = Feedback(text, isError = false)
    fun fail(e: Throwable) = Feedback(failureMessage(e), isError = true)

    // Hybrid offline (#9).
    if (!isOnline && cows == null) {
        OfflineScreen()
        return
    }
    var name by remember { mutableStateOf("") }
    var deviceId by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<Feedback?>(null) }

    // Rename flow: dialog target + the text field's current value.
    var renameTarget by remember { mutableStateOf<Cow?>(null) }
    var renameValue by remember { mutableStateOf("") }
    renameTarget?.let { target ->
        AlertDialog(
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
                TextButton(
                    onClick = {
                        val newName = renameValue.trim()
                        renameTarget = null
                        if (newName.isNotEmpty() && newName != target.name) {
                            vm.updateCow(target.id, newName, null) { result ->
                                message = result.fold(
                                    onSuccess = { ok(context.getString(R.string.devices_renamed)) },
                                    onFailure = { fail(it) },
                                )
                            }
                        }
                    },
                    enabled = renameValue.isNotBlank(),
                ) { Text(stringResource(R.string.devices_rename)) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    // Removing a cow also deletes her readings and alerts (FK cascade), so
    // it asks first instead of deleting on a single tap.
    var removeTarget by remember { mutableStateOf<Cow?>(null) }
    removeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text(stringResource(R.string.devices_remove_title, target.name)) },
            text = { Text(stringResource(R.string.devices_remove_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        removeTarget = null
                        vm.deleteCow(target.id) { result ->
                            message = result.fold(
                                onSuccess = { ok(context.getString(R.string.devices_deleted)) },
                                onFailure = { fail(it) },
                            )
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Accent),
                ) { Text(stringResource(R.string.devices_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    // Photo pick → upload to Storage → update cow row (photo feature).
    var photoTarget by remember { mutableStateOf<Cow?>(null) }
    // Cows that already HAVE a photo get a change/remove chooser instead.
    var photoChoiceTarget by remember { mutableStateOf<Cow?>(null) }
    val pickPhoto = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        val cow = photoTarget
        if (uri != null && cow != null) {
            vm.setCowPhoto(cow.id, uri) { result ->
                message = result.fold(
                    onSuccess = { ok(context.getString(R.string.devices_photo_saved)) },
                    onFailure = { Feedback(context.getString(R.string.devices_error_prefix, it.message ?: ""), true) },
                )
            }
        }
        photoTarget = null
    }
    photoChoiceTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { photoChoiceTarget = null },
            title = { Text(target.name) },
            text = { Text(stringResource(R.string.devices_photo_choose_hint)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        photoChoiceTarget = null
                        photoTarget = target
                        pickPhoto.launch("image/*")
                    },
                ) { Text(stringResource(R.string.devices_photo_change)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        photoChoiceTarget = null
                        vm.removeCowPhoto(target.id) { result ->
                            message = result.fold(
                                onSuccess = { ok(context.getString(R.string.devices_photo_removed)) },
                                onFailure = { Feedback(context.getString(R.string.devices_error_prefix, it.message ?: ""), true) },
                            )
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Accent),
                ) { Text(stringResource(R.string.devices_photo_remove)) }
            },
        )
    }

    // QR pairing (#6): scan the collar sticker instead of typing.
    val scanQr = rememberQrScanLauncher { scanned ->
        if (!scanned.isNullOrBlank()) deviceId = scanned
    }
    val list = cows.orEmpty()

    Column(modifier = Modifier.fillMaxSize()) {
        OfflineBanner()
        // The whole screen scrolls as one list, so the form never squeezes
        // the paired-collar list into a sliver on small phones.
        LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                ScreenHeader(
                    title = stringResource(R.string.devices_title),
                    subtitle = if (cows == null) null
                    else pluralStringResource(R.plurals.devices_paired_count, list.size, list.size),
                )
            }
            item {
                AppCard(modifier = Modifier.padding(horizontal = ScreenPadding)) {
                    Text(stringResource(R.string.devices_add_title), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.devices_add_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it; message = null },
                        label = { Text(stringResource(R.string.devices_cow_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = deviceId,
                        onValueChange = { deviceId = it; message = null },
                        label = { Text(stringResource(R.string.devices_device_id)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { scanQr() },
                            modifier = Modifier.weight(1f).height(48.dp),
                        ) {
                            Text(stringResource(R.string.devices_scan_qr), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Button(
                            onClick = {
                                vm.addCow(name.trim(), deviceId.trim()) { result ->
                                    message = result.fold(
                                        onSuccess = {
                                            name = ""
                                            deviceId = ""
                                            ok(context.getString(R.string.devices_added))
                                        },
                                        onFailure = { fail(it) },
                                    )
                                }
                            },
                            enabled = name.isNotBlank() && deviceId.isNotBlank(),
                            modifier = Modifier.weight(1f).height(48.dp),
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.devices_add), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    message?.let { FeedbackLine(it) }
                }
            }
            item {
                SectionHeader(
                    text = stringResource(R.string.devices_registered_header),
                    modifier = Modifier.padding(horizontal = ScreenPadding).padding(top = 8.dp),
                )
                if (list.isNotEmpty()) {
                    Text(
                        stringResource(R.string.devices_photo_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = ScreenPadding).padding(bottom = 10.dp),
                    )
                }
            }
            if (list.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.devices_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = ScreenPadding),
                    )
                }
            } else {
                items(list, key = { it.id }) { cow ->
                    CollarRow(
                        cow = cow,
                        onPhoto = {
                            if (cow.imageUrl != null) {
                                // Already has a photo: offer change or remove.
                                photoChoiceTarget = cow
                            } else {
                                photoTarget = cow
                                pickPhoto.launch("image/*")
                            }
                        },
                        onRename = {
                            renameTarget = cow
                            renameValue = cow.name
                        },
                        onRemove = { removeTarget = cow },
                        modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 5.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FeedbackLine(feedback: Feedback) {
    val color = if (feedback.isError) MaterialTheme.colorScheme.error else StatusNormal
    Row(
        modifier = Modifier.padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (feedback.isError) Icons.Filled.Warning else Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(feedback.text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
private fun CollarRow(
    cow: Cow,
    onPhoto: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AppCard(modifier = modifier, contentPadding = PaddingValues(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CowAvatar(cow = cow, size = 48.dp, onClick = onPhoto)
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    cow.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    cow.deviceId,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onRename) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.devices_rename),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.devices_remove),
                    tint = Accent,
                )
            }
        }
    }
}
