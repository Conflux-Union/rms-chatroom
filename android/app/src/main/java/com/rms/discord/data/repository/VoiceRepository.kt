package com.rms.discord.data.repository

import android.util.Log
import com.rms.discord.data.api.ApiService
import com.rms.discord.data.api.GuestJoinBody
import android.content.Intent
import com.rms.discord.data.livekit.AudioDeviceInfo
import com.rms.discord.data.livekit.ConnectionState
import com.rms.discord.data.livekit.LiveKitManager
import com.rms.discord.data.livekit.ParticipantInfo
import com.rms.discord.data.livekit.ScreenShareInfo
import com.rms.discord.data.model.HostModeRequest
import com.rms.discord.data.model.HostModeResponse
import com.rms.discord.data.model.InviteCreateResponse
import com.rms.discord.data.model.MuteParticipantRequest
import com.rms.discord.data.model.VoiceInviteInfo
import com.rms.discord.data.model.VoiceTokenResponse
import com.rms.discord.data.model.VoiceUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceRepository @Inject constructor(
    private val api: ApiService,
    private val authRepository: AuthRepository,
    private val liveKitManager: LiveKitManager
) {
    companion object {
        private const val TAG = "VoiceRepository"
    }

    private val _currentChannelId = MutableStateFlow<Long?>(null)
    val currentChannelId: StateFlow<Long?> = _currentChannelId.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Host mode state
    private val _hostModeEnabled = MutableStateFlow(false)
    val hostModeEnabled: StateFlow<Boolean> = _hostModeEnabled.asStateFlow()

    private val _hostModeHostId = MutableStateFlow<String?>(null)
    val hostModeHostId: StateFlow<String?> = _hostModeHostId.asStateFlow()

    private val _hostModeHostName = MutableStateFlow<String?>(null)
    val hostModeHostName: StateFlow<String?> = _hostModeHostName.asStateFlow()

    // Delegate to LiveKitManager
    val connectionState: StateFlow<ConnectionState> = liveKitManager.connectionState
    val isConnected: StateFlow<Boolean> get() = MutableStateFlow(false).also { flow ->
        // Map connectionState to boolean
    }.let { 
        object : StateFlow<Boolean> {
            override val replayCache: List<Boolean> = listOf(liveKitManager.connectionState.value == ConnectionState.CONNECTED)
            override val value: Boolean get() = liveKitManager.connectionState.value == ConnectionState.CONNECTED
            override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<Boolean>): Nothing {
                liveKitManager.connectionState.collect { state ->
                    collector.emit(state == ConnectionState.CONNECTED)
                }
            }
        }
    }
    
    val isMuted: StateFlow<Boolean> = liveKitManager.isMuted
    val isDeafened: StateFlow<Boolean> = liveKitManager.isDeafened
    val isSpeakerOn: StateFlow<Boolean> = liveKitManager.isSpeakerOn
    val participants: StateFlow<List<ParticipantInfo>> = liveKitManager.participants
    val availableDevices: StateFlow<List<AudioDeviceInfo>> = liveKitManager.availableDevices
    val selectedDevice: StateFlow<AudioDeviceInfo?> = liveKitManager.selectedDevice
    
    // Screen share state
    val isScreenSharing: StateFlow<Boolean> = liveKitManager.isScreenSharing
    val remoteScreenShares: StateFlow<Map<String, ScreenShareInfo>> = liveKitManager.remoteScreenShares

    // Convert ParticipantInfo to VoiceUser for backward compatibility
    val voiceUsers: StateFlow<List<VoiceUser>> get() = object : StateFlow<List<VoiceUser>> {
        override val replayCache: List<List<VoiceUser>> = listOf(participants.value.map { it.toVoiceUser() })
        override val value: List<VoiceUser> get() = participants.value.map { it.toVoiceUser() }
        override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<List<VoiceUser>>): Nothing {
            participants.collect { list ->
                collector.emit(list.map { it.toVoiceUser() })
            }
        }
    }

    suspend fun getVoiceToken(channelId: Long): Result<VoiceTokenResponse> {
        return try {
            val token = authRepository.getToken()
                ?: return Result.failure(AuthException("未登录，请先登录"))
            val response = api.getVoiceToken(authRepository.getAuthHeader(token), channelId)
            _currentChannelId.value = channelId
            Result.success(response)
        } catch (e: Exception) {
            Log.e(TAG, "getVoiceToken failed", e)
            Result.failure(e.toAuthException())
        }
    }

    suspend fun joinVoice(channelId: Long): Result<Unit> {
        _error.value = null
        return try {
            // Get token from server
            val tokenResult = getVoiceToken(channelId)
            if (tokenResult.isFailure) {
                return Result.failure(tokenResult.exceptionOrNull()!!)
            }
            
            val response = tokenResult.getOrThrow()
            
            // Connect to LiveKit
            val connectResult = liveKitManager.connect(response.url, response.token, response.roomName)
            if (connectResult.isFailure) {
                _currentChannelId.value = null
                return Result.failure(connectResult.exceptionOrNull()!!)
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "joinVoice failed", e)
            _error.value = e.message
            Result.failure(e.toAuthException())
        }
    }

    fun leaveVoice() {
        liveKitManager.disconnect()
        _currentChannelId.value = null
        _error.value = null
        // Clear host mode state
        _hostModeEnabled.value = false
        _hostModeHostId.value = null
        _hostModeHostName.value = null
    }

    suspend fun fetchVoiceUsers(channelId: Long): Result<List<VoiceUser>> {
        return try {
            val token = authRepository.getToken()
                ?: return Result.failure(AuthException("未登录，请先登录"))
            val users = api.getVoiceUsers(authRepository.getAuthHeader(token), channelId)
            Result.success(users)
        } catch (e: Exception) {
            Log.e(TAG, "fetchVoiceUsers failed", e)
            Result.failure(e.toAuthException())
        }
    }

    fun setMuted(muted: Boolean) {
        liveKitManager.setMuted(muted)
    }

    fun setDeafened(deafened: Boolean) {
        liveKitManager.setDeafened(deafened)
    }

    fun setSpeakerOn(speakerOn: Boolean) {
        liveKitManager.setSpeakerOn(speakerOn)
    }

    fun selectAudioDevice(deviceId: String) {
        liveKitManager.selectAudioDevice(deviceId)
    }

    fun refreshAudioDevices() {
        liveKitManager.refreshAudioDevices()
    }

    fun setParticipantVolume(identity: String, volume: Float) {
        liveKitManager.setParticipantVolume(identity, volume)
    }

    fun getParticipantVolume(identity: String): Float {
        return liveKitManager.getParticipantVolume(identity)
    }

    // Voice Invite APIs
    suspend fun getVoiceInviteInfo(inviteToken: String): Result<VoiceInviteInfo> {
        return try {
            val info = api.getVoiceInviteInfo(inviteToken)
            Result.success(info)
        } catch (e: Exception) {
            Log.e(TAG, "getVoiceInviteInfo failed", e)
            Result.failure(e)
        }
    }

    suspend fun joinVoiceAsGuest(inviteToken: String, username: String): Result<VoiceTokenResponse> {
        return try {
            val response = api.joinVoiceAsGuest(inviteToken, GuestJoinBody(username))
            Result.success(response)
        } catch (e: Exception) {
            Log.e(TAG, "joinVoiceAsGuest failed", e)
            Result.failure(e)
        }
    }

    suspend fun connectAsGuest(inviteToken: String, username: String): Result<Unit> {
        _error.value = null
        return try {
            val tokenResult = joinVoiceAsGuest(inviteToken, username)
            if (tokenResult.isFailure) {
                return Result.failure(tokenResult.exceptionOrNull()!!)
            }
            
            val response = tokenResult.getOrThrow()
            val connectResult = liveKitManager.connect(response.url, response.token, response.roomName)
            if (connectResult.isFailure) {
                return Result.failure(connectResult.exceptionOrNull()!!)
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "connectAsGuest failed", e)
            _error.value = e.message
            Result.failure(e)
        }
    }

    fun clearError() {
        _error.value = null
    }

    // Voice Admin APIs
    suspend fun muteParticipant(channelId: Long, userId: String, muted: Boolean = true): Result<Boolean> {
        return try {
            val token = authRepository.getToken()
                ?: return Result.failure(AuthException("Not logged in"))
            val response = api.muteParticipant(
                authRepository.getAuthHeader(token),
                channelId,
                userId,
                MuteParticipantRequest(muted)
            )
            Result.success(response.success)
        } catch (e: Exception) {
            Log.e(TAG, "muteParticipant failed", e)
            Result.failure(e.toAuthException())
        }
    }

    suspend fun kickParticipant(channelId: Long, userId: String): Result<Boolean> {
        return try {
            val token = authRepository.getToken()
                ?: return Result.failure(AuthException("Not logged in"))
            val response = api.kickParticipant(
                authRepository.getAuthHeader(token),
                channelId,
                userId
            )
            Result.success(response.success)
        } catch (e: Exception) {
            Log.e(TAG, "kickParticipant failed", e)
            Result.failure(e.toAuthException())
        }
    }

    suspend fun fetchHostMode(channelId: Long): Result<HostModeResponse> {
        return try {
            val token = authRepository.getToken()
                ?: return Result.failure(AuthException("Not logged in"))
            val response = api.getHostMode(authRepository.getAuthHeader(token), channelId)
            _hostModeEnabled.value = response.enabled
            _hostModeHostId.value = response.hostId
            _hostModeHostName.value = response.hostName
            Result.success(response)
        } catch (e: Exception) {
            Log.e(TAG, "fetchHostMode failed", e)
            Result.failure(e.toAuthException())
        }
    }

    suspend fun setHostMode(channelId: Long, enabled: Boolean): Result<HostModeResponse> {
        return try {
            val token = authRepository.getToken()
                ?: return Result.failure(AuthException("Not logged in"))
            val response = api.setHostMode(
                authRepository.getAuthHeader(token),
                channelId,
                HostModeRequest(enabled)
            )
            _hostModeEnabled.value = response.enabled
            _hostModeHostId.value = response.hostId
            _hostModeHostName.value = response.hostName
            Result.success(response)
        } catch (e: Exception) {
            Log.e(TAG, "setHostMode failed", e)
            Result.failure(e.toAuthException())
        }
    }

    suspend fun createVoiceInvite(channelId: Long): Result<InviteCreateResponse> {
        return try {
            val token = authRepository.getToken()
                ?: return Result.failure(AuthException("Not logged in"))
            val response = api.createVoiceInvite(authRepository.getAuthHeader(token), channelId)
            Result.success(response)
        } catch (e: Exception) {
            Log.e(TAG, "createVoiceInvite failed", e)
            Result.failure(e.toAuthException())
        }
    }

    // Screen share functions
    fun setMediaProjectionPermissionData(data: Intent?) {
        liveKitManager.setMediaProjectionPermissionData(data)
    }

    suspend fun setScreenShareEnabled(enabled: Boolean): Result<Unit> {
        return liveKitManager.setScreenShareEnabled(enabled)
    }

    fun hasMediaProjectionPermission(): Boolean {
        return liveKitManager.hasMediaProjectionPermission()
    }

    private fun ParticipantInfo.toVoiceUser(): VoiceUser {
        return VoiceUser(
            id = identity,
            name = name,
            isMuted = isMuted,
            isHost = _hostModeHostId.value == identity,
            isSpeaking = isSpeaking
        )
    }
}
