package cn.net.rms.chatroom.data.repository

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import cn.net.rms.chatroom.data.api.ApiService
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
        private val TOKEN_KEY = stringPreferencesKey("auth_token")
    }

    val tokenFlow: Flow<String?> = dataStore.data.map { prefs ->
        prefs[TOKEN_KEY]
    }

    suspend fun getToken(): String? {
        return dataStore.data.first()[TOKEN_KEY]
    }

    fun getTokenBlocking(): String? = runBlocking {
        getToken()
    }

    suspend fun saveToken(token: String) {
        dataStore.edit { prefs ->
            prefs[TOKEN_KEY] = token
        }
    }

    suspend fun clearToken() {
        dataStore.edit { prefs ->
            prefs.remove(TOKEN_KEY)
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
