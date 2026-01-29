package cn.net.rms.chatroom.data.repository

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import cn.net.rms.chatroom.data.api.ApiService
import cn.net.rms.chatroom.data.api.RefreshTokenRequest
import cn.net.rms.chatroom.data.model.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import retrofit2.HttpException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val api: ApiService,
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        private const val TAG = "AuthRepository"
        private val ACCESS_TOKEN_KEY = stringPreferencesKey("access_token")
        private val REFRESH_TOKEN_KEY = stringPreferencesKey("refresh_token")
        private val LEGACY_AUTH_TOKEN_KEY = stringPreferencesKey("auth_token")
    }

    val accessTokenFlow: Flow<String?> = dataStore.data.map { prefs ->
        prefs[ACCESS_TOKEN_KEY]
    }

    suspend fun getAccessToken(): String? {
        val prefs = dataStore.data.first()
        val token = prefs[ACCESS_TOKEN_KEY]
        if (token != null) return token

        val legacyToken = prefs[LEGACY_AUTH_TOKEN_KEY]
        if (legacyToken != null) {
            // Backward compatibility: migrate auth_token -> access_token to avoid forced logout on upgrade.
            dataStore.edit { p ->
                p[ACCESS_TOKEN_KEY] = legacyToken
                p.remove(LEGACY_AUTH_TOKEN_KEY)
            }
            return legacyToken
        }

        return null
    }

    suspend fun getRefreshToken(): String? {
        return dataStore.data.first()[REFRESH_TOKEN_KEY]
    }

    fun getAccessTokenBlocking(): String? = runBlocking {
        getAccessToken()
    }

    suspend fun saveTokens(accessToken: String, refreshToken: String) {
        dataStore.edit { prefs ->
            prefs[ACCESS_TOKEN_KEY] = accessToken
            prefs[REFRESH_TOKEN_KEY] = refreshToken
        }
    }

    suspend fun clearTokens() {
        dataStore.edit { prefs ->
            prefs.remove(ACCESS_TOKEN_KEY)
            prefs.remove(REFRESH_TOKEN_KEY)
        }
    }

    suspend fun refreshAccessToken(): Result<String> {
        return try {
            val refreshToken = getRefreshToken()
            if (refreshToken == null) {
                Log.e(TAG, "refreshAccessToken: no refresh token")
                return Result.failure(AuthException("No refresh token", isUnauthorized = true))
            }
            Log.d(TAG, "refreshAccessToken start")
            val response = api.refreshToken(RefreshTokenRequest(refreshToken))
            val newAccessToken = response.accessToken
            Log.d(TAG, "refreshAccessToken success, new token length=${newAccessToken.length}")
            // Update only access token, keep refresh token
            dataStore.edit { prefs ->
                prefs[ACCESS_TOKEN_KEY] = newAccessToken
            }
            Result.success(newAccessToken)
        } catch (e: Exception) {
            Log.e(TAG, "refreshAccessToken failed", e)
            Result.failure(e.toAuthException())
        }
    }

    suspend fun verifyToken(token: String): Result<User> {
        return try {
            Log.d(TAG, "verifyToken start length=${token.length}")
            val response = api.verifyToken("Bearer $token")
            Log.d(TAG, "verifyToken response success=${response.success} user=${response.user?.username}")
            if (response.success && response.user != null) {
                Result.success(response.user)
            } else {
                Result.failure(AuthException("Token验证失败"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "verifyToken failed", e)
            Result.failure(e.toAuthException())
        }
    }

    fun getAuthHeader(token: String): String = "Bearer $token"
}

class AuthException(
    message: String,
    val isUnauthorized: Boolean = false
) : Exception(message)

fun Exception.toAuthException(): AuthException {
    return when (this) {
        is UnknownHostException -> AuthException("无法连接服务器，请检查网络", isUnauthorized = false)
        is ConnectException -> AuthException("连接服务器失败，请稍后重试", isUnauthorized = false)
        is SocketTimeoutException -> AuthException("连接超时，请检查网络", isUnauthorized = false)
        is HttpException -> {
            val isUnauthorized = code() == 401
            AuthException("服务器错误 (${code()}): ${message()}", isUnauthorized = isUnauthorized)
        }
        else -> AuthException("未知错误: ${this.message ?: this.javaClass.simpleName}", isUnauthorized = false)
    }
}
