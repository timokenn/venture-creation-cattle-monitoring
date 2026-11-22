package com.example.cattlemonitor.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.cattlemonitor.R
import com.example.cattlemonitor.data.AlertType
import com.example.cattlemonitor.settings.NotificationPrefs
import com.example.cattlemonitor.ui.alerts.icon
import com.example.cattlemonitor.ui.alerts.severityStatus
import com.example.cattlemonitor.ui.common.AppCard
import com.example.cattlemonitor.ui.common.IconBubble
import com.example.cattlemonitor.ui.common.alertTypeLabel
import com.example.cattlemonitor.ui.theme.statusColor
import kotlinx.coroutines.launch

/** Short explanation under each alert type's switch. */
@Composable
private fun alertTypeDescription(type: AlertType): String = stringResource(
    when (type) {
        AlertType.FEVER -> R.string.alert_desc_fever
        AlertType.LOW_ACTIVITY -> R.string.alert_desc_low_activity
        AlertType.POSSIBLE_ESTRUS -> R.string.alert_desc_possible_estrus
        AlertType.POSSIBLE_DISTRESS -> R.string.alert_desc_possible_distress
        AlertType.DEVICE_OFFLINE -> R.string.alert_desc_device_offline
        AlertType.SENSOR_ISSUE -> R.string.alert_desc_sensor_issue
    },
)

/** Push-notification switches, one per alert type. */
@Composable
fun NotificationsSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { NotificationPrefs(context) }
    val enabled by prefs.enabled.collectAsState(initial = AlertType.entries.toSet())
    val scope = rememberCoroutineScope()

    AppCard(modifier = modifier) {
        Text(stringResource(R.string.settings_push_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.settings_push_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        AlertType.entries.forEachIndexed { i, type ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
            Row(
                modifier = Modifier.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBubble(icon = type.icon(), tint = statusColor(type.severityStatus()), size = 36.dp)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(alertTypeLabel(type), style = MaterialTheme.typography.titleMedium)
                    Text(
                        alertTypeDescription(type),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = type in enabled,
                    onCheckedChange = { v -> scope.launch { prefs.setEnabled(type, v) } },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }
        }
    }
}
