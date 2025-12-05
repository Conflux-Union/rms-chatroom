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
    }

    private fun checkAuth() {
        viewModelScope.launch {
            Log.d(TAG, "checkAuth start")
            _state.value = _state.value.copy(isLoading = true, error = null)
            val token = authRepository.getToken()
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
                            // 401: Token invalid, clear and redirect to login
                            authRepository.clearToken()
                            _state.value = AuthState(
                                isLoading = false,
                                isAuthenticated = false,
                                token = null,
                                error = "登录已过期，请重新登录"
                            )
                        } else {
                            // Other errors (network, server): proceed to main with error message
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

    fun handleSsoCallback(token: String) {
        viewModelScope.launch {
            Log.d(TAG, "handleSsoCallback start length=${token.length}")
            _state.value = _state.value.copy(isLoading = true, error = null)
            authRepository.verifyToken(token)
                .onSuccess { user ->
                    Log.d(TAG, "handleSsoCallback verify success user=${user.username}")
                    authRepository.saveToken(token)
                    _state.value = AuthState(
                        isLoading = false,
                        isAuthenticated = true,
                        user = user,
                        token = token
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
            authRepository.clearToken()
            _state.value = AuthState(isLoading = false, isAuthenticated = false, token = null)
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }
}
