package cn.net.rms.chatroom.data.auth

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import cn.net.rms.chatroom.BuildConfig
import cn.net.rms.chatroom.data.api.RefreshTokenRequest
import cn.net.rms.chatroom.data.api.RefreshTokenResponse
import com.google.gson.Gson
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
    private val gson: Gson
) : Authenticator {

    companion object {
        private const val TAG = "TokenAuthenticator"
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
                    clearTokens()
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Token refresh failed, clearing tokens", e)
                clearTokens()
                null
            }
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
