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
    val accessToken: String? = null
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
    }

    private fun checkAuth() {
        viewModelScope.launch {
            Log.d(TAG, "checkAuth start")
            _state.value = _state.value.copy(isLoading = true, error = null)
            val accessToken = authRepository.getAccessToken()
            if (accessToken != null) {
                Log.d(TAG, "checkAuth accessToken found length=${accessToken.length}")
                authRepository.verifyToken(accessToken)
                    .onSuccess { user ->
                        Log.d(TAG, "checkAuth verify success user=${user.username}")
                        _state.value = AuthState(
                            isLoading = false,
                            isAuthenticated = true,
                            user = user,
                            accessToken = accessToken
                        )
                    }
                    .onFailure { e ->
                        Log.e(TAG, "checkAuth verify failed", e)
                        val isUnauthorized = (e as? AuthException)?.isUnauthorized == true
                        if (isUnauthorized) {
                            // Try to refresh token
                            Log.d(TAG, "checkAuth attempting token refresh")
                            authRepository.refreshAccessToken()
                                .onSuccess { newToken ->
                                    Log.d(TAG, "checkAuth refresh success, verifying new token")
                                    authRepository.verifyToken(newToken)
                                        .onSuccess { user ->
                                            _state.value = AuthState(
                                                isLoading = false,
                                                isAuthenticated = true,
                                                user = user,
                                                accessToken = newToken
                                            )
                                        }
                                        .onFailure { verifyError ->
                                            Log.e(TAG, "checkAuth verify after refresh failed", verifyError)
                                            authRepository.clearTokens()
                                            _state.value = AuthState(
                                                isLoading = false,
                                                isAuthenticated = false,
                                                accessToken = null,
                                                error = "登录已过期，请重新登录"
                                            )
                                        }
                                }
                                .onFailure { refreshError ->
                                    Log.e(TAG, "checkAuth refresh failed", refreshError)
                                    authRepository.clearTokens()
                                    _state.value = AuthState(
                                        isLoading = false,
                                        isAuthenticated = false,
                                        accessToken = null,
                                        error = "登录已过期，请重新登录"
                                    )
                                }
                        } else {
                            // Other errors (network, server): proceed to main with error message
                            _state.value = AuthState(
                                isLoading = false,
                                isAuthenticated = true,
                                user = null,
                                accessToken = accessToken,
                                error = e.message
                            )
                        }
                    }
            } else {
                Log.d(TAG, "checkAuth no accessToken")
                _state.value = AuthState(isLoading = false, isAuthenticated = false)
            }
        }
    }

    fun handleSsoCallback(accessToken: String, refreshToken: String) {
        viewModelScope.launch {
            Log.d(TAG, "handleSsoCallback start accessToken length=${accessToken.length}")
            _state.value = _state.value.copy(isLoading = true, error = null)
            authRepository.verifyToken(accessToken)
                .onSuccess { user ->
                    Log.d(TAG, "handleSsoCallback verify success user=${user.username}")
                    authRepository.saveTokens(accessToken, refreshToken)
                    _state.value = AuthState(
                        isLoading = false,
                        isAuthenticated = true,
                        user = user,
                        accessToken = accessToken
                    )
                }
                .onFailure { e ->
                    Log.e(TAG, "handleSsoCallback verify failed", e)
                    _state.value = AuthState(
                        isLoading = false,
                        isAuthenticated = false,
                        accessToken = null,
                        error = "登录失败: ${e.message}"
                    )
                }
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.clearTokens()
            _state.value = AuthState(isLoading = false, isAuthenticated = false, accessToken = null)
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }
}
