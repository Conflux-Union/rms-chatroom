package cn.net.rms.chatroom.data.auth

import android.util.Base64
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import cn.net.rms.chatroom.BuildConfig
import cn.net.rms.chatroom.data.api.RefreshTokenRequest
import cn.net.rms.chatroom.data.api.RefreshTokenResponse
import cn.net.rms.chatroom.data.telemetry.TelemetryReporter
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OkHttp Authenticator that intercepts 401 responses and attempts
 * to refresh the access token using the stored refresh token.
 *
 * Depends only on DataStore + Gson (no ApiService/AuthRepository)
 * to avoid circular DI dependency. Uses a bare OkHttpClient for
 * the refresh call. runBlocking is safe here because OkHttp calls
 * authenticate() on its own thread pool, separate from Dispatchers.IO.
 */
@Singleton
class TokenAuthenticator @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val gson: Gson,
    private val telemetryReporter: TelemetryReporter
) : Authenticator {

    companion object {
        private const val TAG = "TokenAuthenticator"
        private const val EXPIRY_MARGIN_MS = 30_000L
    }

    private val lock = Any()
    private val refreshClient = OkHttpClient.Builder().build()

    override fun authenticate(route: Route?, response: Response): Request? {
        // Skip refresh for auth endpoints (logout, revoke, refresh itself)
        val path = response.request.url.encodedPath
        if (path.endsWith("/auth/logout") || path.endsWith("/auth/revoke") || path.endsWith("/auth/refresh")) {
            return null
        }

        if (responseCount(response) >= 2) {
            Log.w(TAG, "Giving up after 2 auth attempts")
            return null
        }

        synchronized(lock) {
            val prefs = runBlocking { dataStore.data.first() }
            val currentToken = prefs[TokenKeys.ACCESS_TOKEN_KEY]
            val requestToken = response.request.header("Authorization")?.removePrefix("Bearer ")

            // Another thread already refreshed — retry with the new token
            if (currentToken != null && currentToken != requestToken) {
                Log.d(TAG, "Token already refreshed by another thread, retrying")
                return response.request.newBuilder()
                    .header("Authorization", "Bearer $currentToken")
                    .build()
            }

            val refreshToken = prefs[TokenKeys.REFRESH_TOKEN_KEY]
            if (refreshToken == null) {
                Log.w(TAG, "No refresh token available, cannot retry")
                return null
            }

            return try {
                Log.d(TAG, "Attempting token refresh")
                val refreshResponse = doRefresh(refreshToken)
                if (refreshResponse != null) {
                    runBlocking {
                        dataStore.edit { p ->
                            p[TokenKeys.ACCESS_TOKEN_KEY] = refreshResponse.accessToken
                            p[TokenKeys.REFRESH_TOKEN_KEY] = refreshResponse.refreshToken
                        }
                    }
                    Log.d(TAG, "Token refresh succeeded, retrying request")
                    response.request.newBuilder()
                        .header("Authorization", "Bearer ${refreshResponse.accessToken}")
                        .build()
                } else {
                    Log.e(TAG, "Token refresh returned null, clearing tokens")
                    // Hard refresh failure forces a re-login; report it so a
                    // broken refresh path is visible server-side.
                    telemetryReporter.report(
                        "token_refresh_failure",
                        message = "refresh rejected, tokens cleared",
                        meta = mapOf("path" to path)
                    )
                    clearTokens()
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Token refresh failed, clearing tokens", e)
                telemetryReporter.report(
                    "token_refresh_failure",
                    message = e.toString(),
                    meta = mapOf("path" to path)
                )
                clearTokens()
                null
            }
        }
    }

    /**
     * Returns the stored access token, proactively refreshing it when it is
     * within [EXPIRY_MARGIN_MS] of expiry. Used by WebSocket (re)connects,
     * where auth happens only at handshake time so a 401-triggered refresh
     * cannot help. Shares [lock] with [authenticate] so concurrent refreshes
     * are serialized and refresh-token rotation is not raced.
     *
     * Unlike the 401 path, a failed refresh does NOT clear tokens: the
     * failure may be a transient network error and the reconnect loop will
     * retry. Returns null only when no access token is stored.
     * Blocking — call from a background thread.
     */
    fun getFreshToken(): String? {
        val stored = runBlocking { dataStore.data.first() }[TokenKeys.ACCESS_TOKEN_KEY] ?: return null
        if (!isExpiringSoon(stored)) return stored

        synchronized(lock) {
            val prefs = runBlocking { dataStore.data.first() }
            val current = prefs[TokenKeys.ACCESS_TOKEN_KEY] ?: return null
            // Another thread may have refreshed while we waited on the lock
            if (!isExpiringSoon(current)) return current

            val refreshToken = prefs[TokenKeys.REFRESH_TOKEN_KEY] ?: return current
            return try {
                val refreshResponse = doRefresh(refreshToken)
                if (refreshResponse != null) {
                    runBlocking {
                        dataStore.edit { p ->
                            p[TokenKeys.ACCESS_TOKEN_KEY] = refreshResponse.accessToken
                            p[TokenKeys.REFRESH_TOKEN_KEY] = refreshResponse.refreshToken
                        }
                    }
                    Log.d(TAG, "Proactive token refresh succeeded")
                    refreshResponse.accessToken
                } else {
                    Log.w(TAG, "Proactive token refresh rejected, keeping current token")
                    current
                }
            } catch (e: Exception) {
                Log.w(TAG, "Proactive token refresh failed: ${e.message}")
                current
            }
        }
    }

    private fun isExpiringSoon(jwt: String): Boolean {
        return try {
            val parts = jwt.split(".")
            if (parts.size < 2) return false
            val payload = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            val exp = JsonParser.parseString(String(payload)).asJsonObject.get("exp")?.asLong
                ?: return false
            exp * 1000 - System.currentTimeMillis() < EXPIRY_MARGIN_MS
        } catch (e: Exception) {
            // Unparseable token: connect with it as-is, the server will decide
            false
        }
    }

    private fun doRefresh(refreshToken: String): RefreshTokenResponse? {
        val body = gson.toJson(RefreshTokenRequest(refreshToken))
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("${BuildConfig.API_BASE_URL}/api/auth/refresh")
            .post(body)
            .build()

        val response = refreshClient.newCall(request).execute()
        if (!response.isSuccessful) {
            Log.e(TAG, "Refresh request failed with ${response.code}")
            return null
        }

        val responseBody = response.body?.string() ?: return null
        return gson.fromJson(responseBody, RefreshTokenResponse::class.java)
    }

    private fun clearTokens() {
        runBlocking {
            dataStore.edit { p ->
                p.remove(TokenKeys.ACCESS_TOKEN_KEY)
                p.remove(TokenKeys.REFRESH_TOKEN_KEY)
            }
        }
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
