package com.rms.discord.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rms.discord.data.model.User
import com.rms.discord.data.repository.AuthRepository
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
    val error: String? = null
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _state = MutableStateFlow(AuthState())
    val state: StateFlow<AuthState> = _state.asStateFlow()

    init {
        checkAuth()
    }

    private fun checkAuth() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            val token = authRepository.getToken()
            if (token != null) {
                authRepository.verifyToken(token)
                    .onSuccess { user ->
                        _state.value = AuthState(
                            isLoading = false,
                            isAuthenticated = true,
                            user = user
                        )
                    }
                    .onFailure { e ->
                        authRepository.clearToken()
                        _state.value = AuthState(
                            isLoading = false,
                            isAuthenticated = false,
                            error = "自动登录失败: ${e.message}"
                        )
                    }
            } else {
                _state.value = AuthState(isLoading = false, isAuthenticated = false)
            }
        }
    }

    fun handleSsoCallback(token: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            authRepository.verifyToken(token)
                .onSuccess { user ->
                    authRepository.saveToken(token)
                    _state.value = AuthState(
                        isLoading = false,
                        isAuthenticated = true,
                        user = user
                    )
                }
                .onFailure { e ->
                    _state.value = AuthState(
                        isLoading = false,
                        isAuthenticated = false,
                        error = "登录失败: ${e.message}"
                    )
                }
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.clearToken()
            _state.value = AuthState(isLoading = false, isAuthenticated = false)
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }
}
