package com.example.cattlemonitor.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Own DataStore file — two preferencesDataStore delegates cannot share a file
// name in the same app (LocalePrefs uses "language_prefs", NotificationPrefs
// "settings", AuthRepository "auth_prefs").
private val Context.themeDataStore by preferencesDataStore(name = "theme_prefs")

/** App theme choice. LIGHT is the default (the brand parchment identity). */
enum class ThemeMode(val key: String) {
    LIGHT("light"),
    DARK("dark"),
    SYSTEM("system");

    companion object {
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: LIGHT
    }
}

/** Persists the user's theme choice; MainActivity resolves it to a dark flag. */
class ThemePrefs(context: Context) {

    private val appContext = context.applicationContext
    private val key = stringPreferencesKey("theme_mode")

    val selected: Flow<ThemeMode> = appContext.themeDataStore.data.map { prefs ->
        ThemeMode.fromKey(prefs[key])
    }

    suspend fun set(mode: ThemeMode) {
        appContext.themeDataStore.edit { it[key] = mode.key }
    }
}
