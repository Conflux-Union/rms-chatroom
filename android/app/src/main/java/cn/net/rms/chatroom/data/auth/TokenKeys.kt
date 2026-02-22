package cn.net.rms.chatroom.data.auth

import androidx.datastore.preferences.core.stringPreferencesKey

/** Shared DataStore keys for token storage. Single source of truth. */
object TokenKeys {
    val ACCESS_TOKEN_KEY = stringPreferencesKey("access_token")
    val REFRESH_TOKEN_KEY = stringPreferencesKey("refresh_token")
    val LEGACY_TOKEN_KEY = stringPreferencesKey("auth_token")
}
