package com.example.cattlemonitor.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.example.cattlemonitor.R
import com.example.cattlemonitor.settings.AppLanguage
import com.example.cattlemonitor.settings.LocalePrefs
import com.example.cattlemonitor.settings.applyLanguage
import kotlinx.coroutines.launch

/** Display name of a language option, localized ("System default" / "Bawaan sistem"). */
@Composable
fun languageDisplayName(lang: AppLanguage): String = when (lang) {
    AppLanguage.SYSTEM -> stringResource(R.string.language_system)
    // Self-named languages stay the same in every locale.
    else -> lang.displayName
}

/**
 * Compact language picker (System / English / Bahasa Indonesia) — shared by
 * the login screen and settings, so the language can be switched before
 * signing in. Persists the choice in DataStore and applies the per-app
 * locale immediately (Android 13+; pre-13 recreates the activity).
 */
@Composable
fun LanguagePickerButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { LocalePrefs(context) }
    val selected by prefs.selected.collectAsState(initial = AppLanguage.SYSTEM)
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }

    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }) {
            Text(languageDisplayName(selected))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AppLanguage.entries.forEach { lang ->
                DropdownMenuItem(
                    text = { Text(languageDisplayName(lang)) },
                    onClick = {
                        expanded = false
                        scope.launch {
                            prefs.setLanguage(lang)
                            applyLanguage(context, lang)
                        }
                    },
                )
            }
        }
    }
}
