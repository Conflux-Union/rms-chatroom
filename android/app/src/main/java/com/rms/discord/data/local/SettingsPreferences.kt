package com.rms.discord.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        private val FLOATING_WINDOW_ENABLED = booleanPreferencesKey("floating_window_enabled")
    }

    val floatingWindowEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[FLOATING_WINDOW_ENABLED] ?: true
    }

    suspend fun setFloatingWindowEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[FLOATING_WINDOW_ENABLED] = enabled
        }
    }
}
