package com.rms.discord.data.repository

import com.rms.discord.data.api.ApiService
import com.rms.discord.data.api.JoinVoiceBody
import com.rms.discord.data.api.LeaveVoiceBody
import com.rms.discord.data.model.VoiceTokenResponse
import com.rms.discord.data.model.VoiceUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceRepository @Inject constructor(
    private val api: ApiService,
    private val authRepository: AuthRepository
) {
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _currentChannelId = MutableStateFlow<Long?>(null)
    val currentChannelId: StateFlow<Long?> = _currentChannelId.asStateFlow()

    private val _voiceUsers = MutableStateFlow<List<VoiceUser>>(emptyList())
    val voiceUsers: StateFlow<List<VoiceUser>> = _voiceUsers.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isDeafened = MutableStateFlow(false)
    val isDeafened: StateFlow<Boolean> = _isDeafened.asStateFlow()

    suspend fun joinVoice(channelId: Long): Result<VoiceTokenResponse> {
        return try {
            val token = authRepository.getToken() ?: return Result.failure(Exception("No token"))
            val response = api.joinVoice(
                authRepository.getAuthHeader(token),
                JoinVoiceBody(channelId)
            )
            _currentChannelId.value = channelId
            _isConnected.value = true
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun leaveVoice(): Result<Unit> {
        return try {
            val channelId = _currentChannelId.value ?: return Result.success(Unit)
            val token = authRepository.getToken() ?: return Result.failure(Exception("No token"))
            api.leaveVoice(authRepository.getAuthHeader(token), LeaveVoiceBody(channelId))
            _currentChannelId.value = null
            _isConnected.value = false
            _voiceUsers.value = emptyList()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchVoiceUsers(channelId: Long): Result<List<VoiceUser>> {
        return try {
            val token = authRepository.getToken() ?: return Result.failure(Exception("No token"))
            val users = api.getVoiceUsers(authRepository.getAuthHeader(token), channelId)
            _voiceUsers.value = users
            Result.success(users)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun setMuted(muted: Boolean) {
        _isMuted.value = muted
    }

    fun setDeafened(deafened: Boolean) {
        _isDeafened.value = deafened
        if (deafened) {
            _isMuted.value = true
        }
    }

    fun addVoiceUser(user: VoiceUser) {
        _voiceUsers.value = _voiceUsers.value + user
    }

    fun removeVoiceUser(userId: Long) {
        _voiceUsers.value = _voiceUsers.value.filter { it.id != userId }
    }

    fun setConnected(connected: Boolean) {
        _isConnected.value = connected
    }
}
