package com.rms.discord.ui.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rms.discord.data.livekit.ConnectionState
import com.rms.discord.data.livekit.ParticipantInfo
import com.rms.discord.data.model.VoiceInviteInfo
import com.rms.discord.data.repository.VoiceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VoiceInviteState(
    val inviteToken: String = "",
    val inviteInfo: VoiceInviteInfo? = null,
    val username: String = "",
    val isLoadingInfo: Boolean = false,
    val isJoining: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val participants: List<ParticipantInfo> = emptyList(),
    val isMuted: Boolean = false,
    val isDeafened: Boolean = false,
    val error: String? = null
) {
    val isConnected: Boolean get() = connectionState == ConnectionState.CONNECTED
    val isValidInvite: Boolean get() = inviteInfo?.valid == true
}

@HiltViewModel
class VoiceInviteViewModel @Inject constructor(
    private val voiceRepository: VoiceRepository
) : ViewModel() {

    private val _inviteToken = MutableStateFlow("")
    private val _inviteInfo = MutableStateFlow<VoiceInviteInfo?>(null)
    private val _username = MutableStateFlow("")
    private val _isLoadingInfo = MutableStateFlow(false)
    private val _isJoining = MutableStateFlow(false)
    private val _localError = MutableStateFlow<String?>(null)

    private val _state = MutableStateFlow(VoiceInviteState())
    val state: StateFlow<VoiceInviteState> = _state.asStateFlow()

    init {
        observeState()
    }

    private fun observeState() {
        viewModelScope.launch {
            combine(
                _inviteToken,
                _inviteInfo,
                _username,
                _isLoadingInfo,
                _isJoining,
                voiceRepository.connectionState,
                voiceRepository.participants,
                voiceRepository.isMuted,
                voiceRepository.isDeafened,
                voiceRepository.error
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                VoiceInviteState(
                    inviteToken = values[0] as String,
                    inviteInfo = values[1] as VoiceInviteInfo?,
                    username = values[2] as String,
                    isLoadingInfo = values[3] as Boolean,
                    isJoining = values[4] as Boolean,
                    connectionState = values[5] as ConnectionState,
                    participants = values[6] as List<ParticipantInfo>,
                    isMuted = values[7] as Boolean,
                    isDeafened = values[8] as Boolean,
                    error = (values[9] as String?) ?: _localError.value
                )
            }.collect { newState ->
                _state.value = newState
            }
        }
    }

    fun setInviteToken(token: String) {
        _inviteToken.value = token
        loadInviteInfo(token)
    }

    private fun loadInviteInfo(token: String) {
        viewModelScope.launch {
            _isLoadingInfo.value = true
            _localError.value = null

            voiceRepository.getVoiceInviteInfo(token)
                .onSuccess { info ->
                    _inviteInfo.value = info
                    if (!info.valid) {
                        _localError.value = "邀请链接无效或已过期"
                    }
                }
                .onFailure { e ->
                    _localError.value = e.message ?: "加载邀请信息失败"
                }

            _isLoadingInfo.value = false
        }
    }

    fun setUsername(username: String) {
        _username.value = username
    }

    fun joinAsGuest() {
        val token = _inviteToken.value
        val username = _username.value.trim()

        if (username.isEmpty()) {
            _localError.value = "请输入用户名"
            return
        }

        viewModelScope.launch {
            _isJoining.value = true
            _localError.value = null

            voiceRepository.connectAsGuest(token, username)
                .onFailure { e ->
                    _localError.value = e.message ?: "加入失败"
                }

            _isJoining.value = false
        }
    }

    fun leaveVoice() {
        voiceRepository.leaveVoice()
    }

    fun toggleMute() {
        val newMuted = !_state.value.isMuted
        voiceRepository.setMuted(newMuted)
    }

    fun toggleDeafen() {
        val newDeafened = !_state.value.isDeafened
        voiceRepository.setDeafened(newDeafened)
    }

    fun clearError() {
        _localError.value = null
        voiceRepository.clearError()
    }

    override fun onCleared() {
        super.onCleared()
        if (_state.value.isConnected) {
            voiceRepository.leaveVoice()
        }
    }
}
