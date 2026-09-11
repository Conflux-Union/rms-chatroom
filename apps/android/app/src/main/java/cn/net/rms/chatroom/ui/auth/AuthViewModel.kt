package cn.net.rms.chatroom.ui.auth

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.net.rms.chatroom.data.model.User
import cn.net.rms.chatroom.data.repository.AuthException
import cn.net.rms.chatroom.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuthState(
    val isLoading: Boolean = true,
    val isAuthenticated: Boolean = false,
    val user: User? = null,
    val error: String? = null,
    val token: String? = null
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    companion object {
        private const val TAG = "AuthViewModel"
    }

    private val _state = MutableStateFlow(AuthState())
    val state: StateFlow<AuthState> = _state.asStateFlow()

    init {
        checkAuth()
        observeTokenCleared()
    }

    /**
     * Watch for token removal by TokenAuthenticator (e.g. refresh failed
     * on a background request). When tokens disappear, silently transition
     * to unauthenticated state so the user lands on the login screen.
     */
    private fun observeTokenCleared() {
        viewModelScope.launch {
            authRepository.tokenFlow.collect { token ->
                if (token == null && _state.value.isAuthenticated) {
                    Log.d(TAG, "Token cleared externally, transitioning to unauthenticated")
                    _state.value = AuthState(isLoading = false, isAuthenticated = false)
                }
            }
        }
    }

    private fun checkAuth() {
        viewModelScope.launch {
            Log.d(TAG, "checkAuth start")
            _state.value = _state.value.copy(isLoading = true, error = null)

            // One-time migration from legacy "auth_token" key
            authRepository.migrateLegacyToken()

            val token = authRepository.getAccessToken()
            if (token != null) {
                Log.d(TAG, "checkAuth token found length=${token.length}")
                authRepository.verifyToken(token)
                    .onSuccess { user ->
                        Log.d(TAG, "checkAuth verify success user=${user.username}")
                        _state.value = AuthState(
                            isLoading = false,
                            isAuthenticated = true,
                            user = user,
                            token = token
                        )
                    }
                    .onFailure { e ->
                        Log.e(TAG, "checkAuth verify failed", e)
                        val isUnauthorized = (e as? AuthException)?.isUnauthorized == true
                        if (isUnauthorized) {
                            // Access token expired — try refresh
                            tryRefreshOrLogout()
                        } else {
                            // Network/server error: proceed to main with error
                            _state.value = AuthState(
                                isLoading = false,
                                isAuthenticated = true,
                                user = null,
                                token = token,
                                error = e.message
                            )
                        }
                    }
            } else {
                Log.d(TAG, "checkAuth no token")
                _state.value = AuthState(isLoading = false, isAuthenticated = false)
            }
        }
    }

    /**
     * Attempt to refresh tokens. If refresh fails, clear everything and force re-login.
     */
    private suspend fun tryRefreshOrLogout() {
        val refreshToken = authRepository.getRefreshToken()
        if (refreshToken == null) {
            Log.d(TAG, "No refresh token available, forcing re-login")
            authRepository.clearTokens()
            _state.value = AuthState(isLoading = false, isAuthenticated = false)
            return
        }

        Log.d(TAG, "Attempting token refresh")
        authRepository.refreshTokens(refreshToken)
            .onSuccess { response ->
                Log.d(TAG, "Token refresh succeeded, verifying new token")
                // Verify the new access token
                authRepository.verifyToken(response.accessToken)
                    .onSuccess { user ->
                        Log.d(TAG, "Post-refresh verify success user=${user.username}")
                        _state.value = AuthState(
                            isLoading = false,
                            isAuthenticated = true,
                            user = user,
                            token = response.accessToken
                        )
                    }
                    .onFailure { e ->
                        Log.e(TAG, "Post-refresh verify failed", e)
                        authRepository.clearTokens()
                        _state.value = AuthState(isLoading = false, isAuthenticated = false)
                    }
            }
            .onFailure { e ->
                Log.e(TAG, "Token refresh failed", e)
                authRepository.clearTokens()
                _state.value = AuthState(isLoading = false, isAuthenticated = false)
            }
    }

    fun handleSsoCallback(accessToken: String, refreshToken: String?) {
        viewModelScope.launch {
            Log.d(TAG, "handleSsoCallback start accessToken.len=${accessToken.length} hasRefresh=${refreshToken != null}")
            _state.value = _state.value.copy(isLoading = true, error = null)
            authRepository.verifyToken(accessToken)
                .onSuccess { user ->
                    Log.d(TAG, "handleSsoCallback verify success user=${user.username}")
                    if (refreshToken != null) {
                        authRepository.saveTokens(accessToken, refreshToken)
                    } else {
                        // Fallback: no refresh token from server (shouldn't happen, but be safe)
                        authRepository.saveToken(accessToken)
                    }
                    _state.value = AuthState(
                        isLoading = false,
                        isAuthenticated = true,
                        user = user,
                        token = accessToken
                    )
                }
                .onFailure { e ->
                    Log.e(TAG, "handleSsoCallback verify failed", e)
                    _state.value = AuthState(
                        isLoading = false,
                        isAuthenticated = false,
                        token = null,
                        error = "登录失败: ${e.message}"
                    )
                }
        }
    }

    fun logout() {
        viewModelScope.launch {
            // Best-effort server-side revocation
            val accessToken = authRepository.getAccessToken()
            val refreshToken = authRepository.getRefreshToken()
            if (accessToken != null && refreshToken != null) {
                authRepository.revokeRefreshToken(accessToken, refreshToken)
            }
            authRepository.clearTokens()
            _state.value = AuthState(isLoading = false, isAuthenticated = false, token = null)
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }
}
