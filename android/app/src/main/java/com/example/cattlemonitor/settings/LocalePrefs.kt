package com.example.cattlemonitor.settings

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Separate DataStore from NotificationPrefs' "settings" store, on purpose —
// two preferencesDataStore delegates can't safely share the same file name
// across files, so this one is fully independent and additive.
private val Context.languageDataStore by preferencesDataStore(name = "language_prefs")

enum class AppLanguage(val tag: String, val displayName: String) {
    SYSTEM("", "System default"),
    ENGLISH("en", "English"),
    INDONESIAN("in", "Bahasa Indonesia"),
}

/** Stores the user's chosen app language. */
class LocalePrefs(private val context: Context) {

    private val key = stringPreferencesKey("language_tag")

    val selected: Flow<AppLanguage> = context.languageDataStore.data.map { prefs ->
        AppLanguage.entries.firstOrNull { it.tag == prefs[key] } ?: AppLanguage.SYSTEM
    }

    suspend fun setLanguage(language: AppLanguage) {
        context.languageDataStore.edit { it[key] = language.tag }
    }
}

/** Applies the chosen locale (Android 13+: per-app OS setting) and refreshes the UI. */
fun applyLanguage(context: Context, language: AppLanguage) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val localeManager = context.getSystemService(LocaleManager::class.java)
        localeManager.applicationLocales =
            if (language == AppLanguage.SYSTEM) {
                LocaleList.getEmptyLocaleList()
            } else {
                LocaleList.forLanguageTags(language.tag)
            }
        // The system recreates activities on locale change automatically.
    } else {
        // Pre-13: wrap the base configuration so all resources resolve in the
        // chosen locale, then recreate to re-compose with the new config.
        if (language == AppLanguage.SYSTEM) return
        val activity = context as? Activity ?: return
        val config = android.content.res.Configuration(activity.resources.configuration)
        config.setLocale(java.util.Locale.forLanguageTag(language.tag))
        @Suppress("DEPRECATION")
        activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
        activity.recreate()
    }
}
