package cn.net.rms.chatroom.data.repository

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import cn.net.rms.chatroom.data.auth.TokenKeys
import cn.net.rms.chatroom.data.api.ApiService
import cn.net.rms.chatroom.data.api.RefreshTokenRequest
import cn.net.rms.chatroom.data.api.RefreshTokenResponse
import cn.net.rms.chatroom.data.api.LogoutRequest
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
    }

    // Points to access token for backward compat
    val tokenFlow: Flow<String?> = dataStore.data.map { prefs ->
        prefs[TokenKeys.ACCESS_TOKEN_KEY]
    }

    // Primary accessor — returns access token
    suspend fun getAccessToken(): String? {
        return dataStore.data.first()[TokenKeys.ACCESS_TOKEN_KEY]
    }

    suspend fun getRefreshToken(): String? {
        return dataStore.data.first()[TokenKeys.REFRESH_TOKEN_KEY]
    }

    // Backward compat alias
    suspend fun getToken(): String? = getAccessToken()

    fun getTokenBlocking(): String? = runBlocking {
        getAccessToken()
    }

    suspend fun saveTokens(accessToken: String, refreshToken: String) {
        dataStore.edit { prefs ->
            prefs[TokenKeys.ACCESS_TOKEN_KEY] = accessToken
            prefs[TokenKeys.REFRESH_TOKEN_KEY] = refreshToken
            prefs.remove(TokenKeys.LEGACY_TOKEN_KEY)
        }
        Log.d(TAG, "Saved access_token (len=${accessToken.length}) and refresh_token (len=${refreshToken.length})")
    }

    // Backward compat — saves as access token only
    suspend fun saveToken(token: String) {
        dataStore.edit { prefs ->
            prefs[TokenKeys.ACCESS_TOKEN_KEY] = token
        }
    }

    suspend fun clearTokens() {
        dataStore.edit { prefs ->
            prefs.remove(TokenKeys.ACCESS_TOKEN_KEY)
            prefs.remove(TokenKeys.REFRESH_TOKEN_KEY)
            prefs.remove(TokenKeys.LEGACY_TOKEN_KEY)
        }
        Log.d(TAG, "Cleared all tokens")
    }

    // Backward compat alias
    suspend fun clearToken() = clearTokens()

    /**
     * One-time migration: move legacy "auth_token" to "access_token".
     * Called on app startup before any auth checks.
     */
    suspend fun migrateLegacyToken() {
        val prefs = dataStore.data.first()
        val legacyToken = prefs[TokenKeys.LEGACY_TOKEN_KEY] ?: return

        val currentAccessToken = prefs[TokenKeys.ACCESS_TOKEN_KEY]
        if (currentAccessToken == null) {
            dataStore.edit { p ->
                p[TokenKeys.ACCESS_TOKEN_KEY] = legacyToken
                p.remove(TokenKeys.LEGACY_TOKEN_KEY)
            }
            Log.d(TAG, "Migrated legacy auth_token to access_token")
        } else {
            // Both exist — just clean up legacy
            dataStore.edit { p ->
                p.remove(TokenKeys.LEGACY_TOKEN_KEY)
            }
            Log.d(TAG, "Removed stale legacy auth_token (access_token already exists)")
        }
    }

    /**
     * Call POST /api/auth/refresh to get new token pair.
     * No auth header needed — the refresh token is the credential.
     */
    suspend fun refreshTokens(refreshToken: String): Result<RefreshTokenResponse> {
        return try {
            val response = api.refreshToken(RefreshTokenRequest(refreshToken))
            saveTokens(response.accessToken, response.refreshToken)
            Log.d(TAG, "Token refresh succeeded")
            Result.success(response)
        } catch (e: Exception) {
            Log.e(TAG, "Token refresh failed", e)
            Result.failure(e.toAuthException())
        }
    }

    /**
     * Call POST /api/auth/logout to revoke refresh token on server.
     * Best-effort: if it fails, we still clear local tokens.
     */
    suspend fun revokeRefreshToken(accessToken: String, refreshToken: String): Result<Unit> {
        return try {
            api.logout("Bearer $accessToken", LogoutRequest(refreshToken))
            Log.d(TAG, "Server-side token revocation succeeded")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Server-side token revocation failed (best-effort)", e)
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
