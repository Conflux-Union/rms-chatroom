package cn.net.rms.chatroom.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        private val FLOATING_WINDOW_ENABLED = booleanPreferencesKey("floating_window_enabled")
        private val BACKGROUND_MESSAGE_SERVICE_ENABLED = booleanPreferencesKey("background_message_service_enabled")
        private val TELEMETRY_ENABLED = booleanPreferencesKey("telemetry_enabled")
        private val THEME_MODE = stringPreferencesKey("theme_mode")
        private fun lastReadMessageKey(channelId: Long) = longPreferencesKey("last_read_message_$channelId")
    }

    val floatingWindowEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[FLOATING_WINDOW_ENABLED] ?: true
    }

    val backgroundMessageServiceEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[BACKGROUND_MESSAGE_SERVICE_ENABLED] ?: false
    }

    val telemetryEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[TELEMETRY_ENABLED] ?: true
    }

    val themeMode: Flow<ThemeMode> = dataStore.data.map { prefs ->
        ThemeMode.fromStored(prefs[THEME_MODE])
    }

    suspend fun setFloatingWindowEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[FLOATING_WINDOW_ENABLED] = enabled
        }
    }

    suspend fun setBackgroundMessageServiceEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[BACKGROUND_MESSAGE_SERVICE_ENABLED] = enabled
        }
    }

    suspend fun setTelemetryEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[TELEMETRY_ENABLED] = enabled
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { prefs ->
            prefs[THEME_MODE] = mode.name
        }
    }

    suspend fun getLastReadMessageId(channelId: Long): Long? {
        return dataStore.data.first()[lastReadMessageKey(channelId)]
    }

    suspend fun setLastReadMessageId(channelId: Long, messageId: Long) {
        dataStore.edit { prefs ->
            prefs[lastReadMessageKey(channelId)] = messageId
        }
    }
}
