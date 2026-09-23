package com.example.cattlemonitor.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.example.cattlemonitor.data.AlertType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Notification preferences: which alert types trigger push notifications. */
class NotificationPrefs(private val context: Context) {

    private fun keyFor(type: AlertType) = booleanPreferencesKey("notify_${type.name}")

    val enabled: Flow<Set<AlertType>> = context.dataStore.data.map { prefs ->
        AlertType.entries.filter { prefs[keyFor(it)] != false }.toSet()
    }

    suspend fun setEnabled(type: AlertType, value: Boolean) {
        context.dataStore.edit { it[keyFor(type)] = value }
    }
}
