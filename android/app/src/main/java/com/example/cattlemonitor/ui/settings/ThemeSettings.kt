package com.example.cattlemonitor.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.cattlemonitor.R
import com.example.cattlemonitor.ServiceLocator
import com.example.cattlemonitor.settings.ThemeMode
import com.example.cattlemonitor.settings.ThemePrefs
import kotlinx.coroutines.launch

/**
 * Appearance section (#settings): Light / Dark / Match-system chips bound to
 * ThemePrefs. Changing the choice recomposes the whole app tree from
 * MainActivity's theme resolution — no restart needed.
 */
@Composable
fun ThemeSection() {
    val context = LocalContext.current
    val prefs = remember { ThemePrefs(context) }
    val selected by prefs.selected.collectAsState(initial = ThemeMode.LIGHT)
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(stringResource(R.string.settings_theme_title), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.settings_theme_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = mode == selected,
                    onClick = { scope.launch { prefs.set(mode) } },
                    label = {
                        Text(
                            stringResource(
                                when (mode) {
                                    ThemeMode.LIGHT -> R.string.theme_light
                                    ThemeMode.DARK -> R.string.theme_dark
                                    ThemeMode.SYSTEM -> R.string.theme_system
                                },
                            ),
                        )
                    },
                )
            }
        }
    }
}
