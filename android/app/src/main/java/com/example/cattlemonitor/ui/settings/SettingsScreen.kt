package com.example.cattlemonitor.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.cattlemonitor.R
import com.example.cattlemonitor.data.AlertType
import com.example.cattlemonitor.settings.NotificationPrefs
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val prefs = NotificationPrefs(context)
    val enabled by prefs.enabled.collectAsState(initial = AlertType.entries.toSet())
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.settings_push_subtitle),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        AlertType.entries.forEach { type ->
            Row(
                modifier = Modifier.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    type.name.lowercase().replace('_', ' '),
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = type in enabled,
                    onCheckedChange = { v ->
                        scope.launch { prefs.setEnabled(type, v) }
                    },
                )
            }
        }
    }
}
