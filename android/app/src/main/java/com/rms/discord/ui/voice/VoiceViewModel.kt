package com.rms.discord.ui.voice

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Intent
import com.rms.discord.data.livekit.AudioDeviceInfo
import com.rms.discord.data.livekit.ConnectionState
import com.rms.discord.data.livekit.ParticipantInfo
import com.rms.discord.data.livekit.ScreenShareInfo
import com.rms.discord.data.repository.AuthRepository
import com.rms.discord.data.repository.VoiceRepository
import com.rms.discord.service.VoiceCallService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VoiceState(
    val channelId: Long? = null,
    val channelName: String = "",
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val isLoading: Boolean = false,
    val isMuted: Boolean = false,
    val isDeafened: Boolean = false,
    val isSpeakerOn: Boolean = true,
    val participants: List<ParticipantInfo> = emptyList(),
    val availableDevices: List<AudioDeviceInfo> = emptyList(),
    val selectedDevice: AudioDeviceInfo? = null,
    val error: String? = null,
    // Admin features
    val isAdmin: Boolean = false,
    val userId: Long? = null,
    val hostModeEnabled: Boolean = false,
    val hostModeHostId: String? = null,
    val hostModeHostName: String? = null,
    // Invite state
    val inviteUrl: String? = null,
    val inviteLoading: Boolean = false,
    val inviteError: String? = null,
    // Screen share state
    val isScreenSharing: Boolean = false,
    val remoteScreenShares: Map<String, ScreenShareInfo> = emptyMap(),
    val screenShareLocked: Boolean = false,
    val screenSharerId: String? = null,
    val screenSharerName: String? = null
) {
    val isConnected: Boolean get() = connectionState == ConnectionState.CONNECTED
    val isReconnecting: Boolean get() = connectionState == ConnectionState.RECONNECTING
    val isCurrentUserHost: Boolean get() = hostModeHostId == userId?.toString()
    val hostButtonDisabled: Boolean get() = hostModeEnabled && !isCurrentUserHost
    val isCurrentUserScreenSharer: Boolean get() = screenSharerId == userId?.toString()
    val screenShareButtonDisabled: Boolean get() = screenShareLocked && !isCurrentUserScreenSharer && !isScreenSharing
}

@HiltViewModel
class VoiceViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val voiceRepository: VoiceRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _channelId = MutableStateFlow<Long?>(null)
    private val _channelName = MutableStateFlow("")
    private val _isLoading = MutableStateFlow(false)
    private var serviceStarted = false

    // Admin state
    private val _isAdmin = MutableStateFlow(false)
    private val _userId = MutableStateFlow<Long?>(null)
    private val _inviteUrl = MutableStateFlow<String?>(null)
    private val _inviteLoading = MutableStateFlow(false)
    private val _inviteError = MutableStateFlow<String?>(null)

    private val _state = MutableStateFlow(VoiceState())
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    init {
        observeVoiceState()
        observeDeviceState()
        observeHostModeState()
        observeScreenShareState()
        loadUserInfo()
    }

    private fun loadUserInfo() {
        viewModelScope.launch {
            val token = authRepository.getToken() ?: return@launch
            val result = authRepository.verifyToken(token)
            result.onSuccess { user ->
                _isAdmin.value = user.permissionLevel >= 4
                _userId.value = user.id
            }
        }
    }

    private fun observeVoiceState() {
        viewModelScope.launch {
            combine(
                _channelId,
                _channelName,
                voiceRepository.connectionState,
                voiceRepository.isMuted,
                voiceRepository.isDeafened,
                voiceRepository.isSpeakerOn,
                voiceRepository.participants,
                _isLoading,
                voiceRepository.error
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                VoiceState(
                    channelId = values[0] as Long?,
                    channelName = values[1] as String,
                    connectionState = values[2] as ConnectionState,
                    isMuted = values[3] as Boolean,
                    isDeafened = values[4] as Boolean,
                    isSpeakerOn = values[5] as Boolean,
                    participants = values[6] as List<ParticipantInfo>,
                    isLoading = values[7] as Boolean,
                    error = values[8] as String?,
                    availableDevices = _state.value.availableDevices,
                    selectedDevice = _state.value.selectedDevice,
                    isAdmin = _isAdmin.value,
                    userId = _userId.value,
                    hostModeEnabled = _state.value.hostModeEnabled,
                    hostModeHostId = _state.value.hostModeHostId,
                    hostModeHostName = _state.value.hostModeHostName,
                    inviteUrl = _inviteUrl.value,
                    inviteLoading = _inviteLoading.value,
                    inviteError = _inviteError.value,
                    isScreenSharing = _state.value.isScreenSharing,
                    remoteScreenShares = _state.value.remoteScreenShares
                )
            }.collect { newState ->
                // Start foreground service on first successful connection
                // This ensures RECORD_AUDIO permission is already granted
                if (newState.connectionState == ConnectionState.CONNECTED && !serviceStarted) {
                    VoiceCallService.start(context, newState.channelName.ifEmpty { "语音通话" })
                    serviceStarted = true
                    fetchHostMode()
                    fetchScreenShareStatus()
                }
                // Stop service only when truly disconnected (not during reconnection)
                if (newState.connectionState == ConnectionState.DISCONNECTED && serviceStarted) {
                    VoiceCallService.stop(context)
                    serviceStarted = false
                }
                _state.value = newState
            }
        }
    }

    private fun observeHostModeState() {
        viewModelScope.launch {
            combine(
                voiceRepository.hostModeEnabled,
                voiceRepository.hostModeHostId,
                voiceRepository.hostModeHostName
            ) { enabled, hostId, hostName ->
                Triple(enabled, hostId, hostName)
            }.collect { (enabled, hostId, hostName) ->
                _state.value = _state.value.copy(
                    hostModeEnabled = enabled,
                    hostModeHostId = hostId,
                    hostModeHostName = hostName
                )
            }
        }
    }

    private fun observeDeviceState() {
        viewModelScope.launch {
            combine(
                voiceRepository.availableDevices,
                voiceRepository.selectedDevice
            ) { devices, selected ->
                Pair(devices, selected)
            }.collect { (devices, selected) ->
                _state.value = _state.value.copy(
                    availableDevices = devices,
                    selectedDevice = selected
                )
            }
        }
    }

    private fun observeScreenShareState() {
        viewModelScope.launch {
            combine(
                voiceRepository.isScreenSharing,
                voiceRepository.remoteScreenShares,
                voiceRepository.screenShareLocked,
                voiceRepository.screenSharerId,
                voiceRepository.screenSharerName
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                VoiceScreenShareUpdate(
                    isSharing = values[0] as Boolean,
                    remoteShares = values[1] as Map<String, ScreenShareInfo>,
                    locked = values[2] as Boolean,
                    sharerId = values[3] as String?,
                    sharerName = values[4] as String?
                )
            }.collect { update ->
                _state.value = _state.value.copy(
                    isScreenSharing = update.isSharing,
                    remoteScreenShares = update.remoteShares,
                    screenShareLocked = update.locked,
                    screenSharerId = update.sharerId,
                    screenSharerName = update.sharerName
                )
            }
        }
    }
    
    private data class VoiceScreenShareUpdate(
        val isSharing: Boolean,
        val remoteShares: Map<String, ScreenShareInfo>,
        val locked: Boolean,
        val sharerId: String?,
        val sharerName: String?
    )

    fun setChannelId(channelId: Long, channelName: String = "") {
        _channelId.value = channelId
        _channelName.value = channelName
        // Fetch current voice users from API
        viewModelScope.launch {
            voiceRepository.fetchVoiceUsers(channelId)
        }
    }

    fun joinVoice() {
        val channelId = _channelId.value ?: return
        viewModelScope.launch {
            _isLoading.value = true
            voiceRepository.joinVoice(channelId)
            _isLoading.value = false
        }
    }

    fun leaveVoice() {
        _isLoading.value = true
        voiceRepository.leaveVoice()
        if (serviceStarted) {
            VoiceCallService.stop(context)
            serviceStarted = false
        }
        _isLoading.value = false
    }

    fun toggleMute() {
        val newMuted = !_state.value.isMuted
        voiceRepository.setMuted(newMuted)
    }

    fun toggleDeafen() {
        val newDeafened = !_state.value.isDeafened
        voiceRepository.setDeafened(newDeafened)
    }

    fun toggleSpeaker() {
        val newSpeakerOn = !_state.value.isSpeakerOn
        voiceRepository.setSpeakerOn(newSpeakerOn)
    }

    fun selectAudioDevice(deviceId: String) {
        voiceRepository.selectAudioDevice(deviceId)
    }

    fun refreshAudioDevices() {
        voiceRepository.refreshAudioDevices()
    }

    fun setParticipantVolume(identity: String, volume: Float) {
        voiceRepository.setParticipantVolume(identity, volume)
    }

    fun clearError() {
        voiceRepository.clearError()
    }

    // Admin functions
    fun muteParticipant(userId: String, muted: Boolean = true) {
        val channelId = _channelId.value ?: return
        viewModelScope.launch {
            voiceRepository.muteParticipant(channelId, userId, muted)
        }
    }

    fun kickParticipant(userId: String) {
        val channelId = _channelId.value ?: return
        viewModelScope.launch {
            voiceRepository.kickParticipant(channelId, userId)
        }
    }

    private fun fetchHostMode() {
        val channelId = _channelId.value ?: return
        viewModelScope.launch {
            voiceRepository.fetchHostMode(channelId)
        }
    }
    
    private fun fetchScreenShareStatus() {
        val channelId = _channelId.value ?: return
        viewModelScope.launch {
            voiceRepository.fetchScreenShareStatus(channelId)
        }
    }

    fun toggleHostMode() {
        val channelId = _channelId.value ?: return
        val newEnabled = !_state.value.hostModeEnabled
        viewModelScope.launch {
            voiceRepository.setHostMode(channelId, newEnabled)
        }
    }

    fun createInvite() {
        val channelId = _channelId.value ?: return
        _inviteLoading.value = true
        _inviteError.value = null
        _inviteUrl.value = null
        viewModelScope.launch {
            val result = voiceRepository.createVoiceInvite(channelId)
            result.onSuccess { response ->
                _inviteUrl.value = response.inviteUrl
            }.onFailure { error ->
                _inviteError.value = error.message ?: "Failed to create invite"
            }
            _inviteLoading.value = false
        }
    }

    fun clearInviteState() {
        _inviteUrl.value = null
        _inviteError.value = null
        _inviteLoading.value = false
    }

    // Screen share functions
    fun setMediaProjectionPermissionData(data: Intent?) {
        voiceRepository.setMediaProjectionPermissionData(data)
    }

    fun toggleScreenShare() {
        viewModelScope.launch {
            if (_state.value.isScreenSharing) {
                voiceRepository.setScreenShareEnabled(false)
            } else {
                // Restart service with mediaProjection type before starting screen share
                if (serviceStarted) {
                    VoiceCallService.start(
                        context,
                        _channelName.value.ifEmpty { "语音通话" },
                        enableScreenShare = true
                    )
                }
                voiceRepository.setScreenShareEnabled(true)
            }
        }
    }

    fun hasMediaProjectionPermission(): Boolean {
        return voiceRepository.hasMediaProjectionPermission()
    }

    override fun onCleared() {
        super.onCleared()
        // Disconnect when ViewModel is cleared
        if (_state.value.isConnected || serviceStarted) {
            voiceRepository.leaveVoice()
            if (serviceStarted) {
                VoiceCallService.stop(context)
                serviceStarted = false
            }
        }
    }
}
