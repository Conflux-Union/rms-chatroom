package com.rms.discord.ui.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rms.discord.data.model.VoiceUser
import com.rms.discord.data.repository.VoiceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VoiceState(
    val channelId: Long? = null,
    val isConnected: Boolean = false,
    val isLoading: Boolean = false,
    val isMuted: Boolean = false,
    val isDeafened: Boolean = false,
    val voiceUsers: List<VoiceUser> = emptyList(),
    val error: String? = null
)

@HiltViewModel
class VoiceViewModel @Inject constructor(
    private val voiceRepository: VoiceRepository
) : ViewModel() {

    private val _channelId = MutableStateFlow<Long?>(null)
    private val _isLoading = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)

    private val _state = MutableStateFlow(VoiceState())
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    init {
        observeVoiceState()
    }

    private fun observeVoiceState() {
        viewModelScope.launch {
            combine(
                _channelId,
                voiceRepository.isConnected,
                voiceRepository.isMuted,
                voiceRepository.isDeafened,
                voiceRepository.voiceUsers,
                _isLoading,
                _error
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                VoiceState(
                    channelId = values[0] as Long?,
                    isConnected = values[1] as Boolean,
                    isMuted = values[2] as Boolean,
                    isDeafened = values[3] as Boolean,
                    voiceUsers = values[4] as List<VoiceUser>,
                    isLoading = values[5] as Boolean,
                    error = values[6] as String?
                )
            }.collect { newState ->
                _state.value = newState
            }
        }
    }

    fun setChannelId(channelId: Long) {
        _channelId.value = channelId
        // Fetch current voice users
        viewModelScope.launch {
            voiceRepository.fetchVoiceUsers(channelId)
        }
    }

    fun joinVoice() {
        val channelId = _channelId.value ?: return
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            voiceRepository.joinVoice(channelId)
                .onSuccess { response ->
                    // TODO: Connect to LiveKit using response.token and response.url
                    // This will be implemented in Phase 3
                }
                .onFailure { e ->
                    _error.value = e.message
                }

            _isLoading.value = false
        }
    }

    fun leaveVoice() {
        viewModelScope.launch {
            _isLoading.value = true
            voiceRepository.leaveVoice()
            // TODO: Disconnect from LiveKit
            _isLoading.value = false
        }
    }

    fun toggleMute() {
        val newMuted = !_state.value.isMuted
        voiceRepository.setMuted(newMuted)
        // TODO: Update LiveKit audio track
    }

    fun toggleDeafen() {
        val newDeafened = !_state.value.isDeafened
        voiceRepository.setDeafened(newDeafened)
        // TODO: Update LiveKit audio reception
    }

    fun clearError() {
        _error.value = null
    }
}
