package com.rms.discord.ui.voice

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rms.discord.data.livekit.AudioDeviceInfo
import com.rms.discord.data.livekit.ConnectionState
import com.rms.discord.data.livekit.ParticipantInfo
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
    val error: String? = null
) {
    val isConnected: Boolean get() = connectionState == ConnectionState.CONNECTED
    val isReconnecting: Boolean get() = connectionState == ConnectionState.RECONNECTING
}

@HiltViewModel
class VoiceViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val voiceRepository: VoiceRepository
) : ViewModel() {

    private val _channelId = MutableStateFlow<Long?>(null)
    private val _channelName = MutableStateFlow("")
    private val _isLoading = MutableStateFlow(false)
    private var serviceStarted = false

    private val _state = MutableStateFlow(VoiceState())
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    init {
        observeVoiceState()
        observeDeviceState()
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
                    selectedDevice = _state.value.selectedDevice
                )
            }.collect { newState ->
                // Start foreground service only on first successful connection
                // Keep service running during reconnection attempts
                if (newState.connectionState == ConnectionState.CONNECTED && !serviceStarted) {
                    VoiceCallService.start(context, newState.channelName.ifEmpty { "语音通话" })
                    serviceStarted = true
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
