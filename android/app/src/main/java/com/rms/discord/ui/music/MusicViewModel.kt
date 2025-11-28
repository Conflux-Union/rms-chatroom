package com.rms.discord.ui.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rms.discord.data.api.ApiService
import com.rms.discord.data.model.MusicBotStartRequest
import com.rms.discord.data.model.MusicQueueAddRequest
import com.rms.discord.data.model.MusicRoomRequest
import com.rms.discord.data.model.MusicSearchRequest
import com.rms.discord.data.model.MusicSeekRequest
import com.rms.discord.data.model.QueueItem
import com.rms.discord.data.model.Song
import com.rms.discord.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MusicState(
    // Login state
    val isLoggedIn: Boolean = false,
    val qrCodeUrl: String? = null,
    val loginStatus: String = "idle",
    
    // Search state
    val searchResults: List<Song> = emptyList(),
    val isSearching: Boolean = false,
    
    // Playback state
    val isPlaying: Boolean = false,
    val currentSong: Song? = null,
    val currentIndex: Int = 0,
    val queue: List<QueueItem> = emptyList(),
    val playbackState: String = "idle",
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    
    // Bot state
    val botConnected: Boolean = false,
    val botRoom: String? = null,
    
    // Current room for multi-channel support
    val currentRoomName: String? = null,
    
    // UI state
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class MusicViewModel @Inject constructor(
    private val api: ApiService,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _state = MutableStateFlow(MusicState())
    val state: StateFlow<MusicState> = _state.asStateFlow()

    private var progressPollingJob: Job? = null
    private var loginPollingJob: Job? = null

    init {
        viewModelScope.launch {
            checkLoginStatus()
        }
    }
    
    // Set current room name when user joins a voice channel
    fun setCurrentRoom(roomName: String?) {
        _state.value = _state.value.copy(currentRoomName = roomName)
        if (roomName != null) {
            refreshQueue(roomName)
            getBotStatus(roomName)
        }
    }

    private suspend fun getAuthHeader(): String {
        val token = authRepository.getToken() ?: ""
        return "Bearer $token"
    }

    // Login functions
    fun checkLoginStatus() {
        viewModelScope.launch {
            try {
                val response = api.checkMusicLogin(getAuthHeader())
                _state.value = _state.value.copy(isLoggedIn = response.loggedIn)
            } catch (e: Exception) {
                _state.value = _state.value.copy(isLoggedIn = false)
            }
        }
    }

    fun getQRCode() {
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(loginStatus = "loading")
                val response = api.getMusicQRCode()
                _state.value = _state.value.copy(
                    qrCodeUrl = response.qrcode,
                    loginStatus = "waiting"
                )
                startLoginPolling()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loginStatus = "error",
                    error = "获取二维码失败: ${e.message}"
                )
            }
        }
    }

    private fun startLoginPolling() {
        loginPollingJob?.cancel()
        loginPollingJob = viewModelScope.launch {
            while (true) {
                delay(2000)
                try {
                    val response = api.checkMusicLoginStatus()
                    _state.value = _state.value.copy(loginStatus = response.status)
                    
                    when (response.status) {
                        "success" -> {
                            _state.value = _state.value.copy(
                                isLoggedIn = true,
                                qrCodeUrl = null
                            )
                            break
                        }
                        "expired", "refused" -> {
                            _state.value = _state.value.copy(qrCodeUrl = null)
                            break
                        }
                    }
                } catch (e: Exception) {
                    break
                }
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            try {
                api.musicLogout(getAuthHeader())
                _state.value = _state.value.copy(isLoggedIn = false)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "退出登录失败")
            }
        }
    }

    fun dismissQRCode() {
        loginPollingJob?.cancel()
        _state.value = _state.value.copy(qrCodeUrl = null, loginStatus = "idle")
    }

    // Search functions
    fun search(keyword: String) {
        if (keyword.isBlank()) {
            _state.value = _state.value.copy(searchResults = emptyList())
            return
        }
        
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(isSearching = true)
                val response = api.searchMusic(getAuthHeader(), MusicSearchRequest(keyword))
                _state.value = _state.value.copy(
                    searchResults = response.songs,
                    isSearching = false
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    searchResults = emptyList(),
                    isSearching = false,
                    error = "搜索失败: ${e.message}"
                )
            }
        }
    }

    fun clearSearchResults() {
        _state.value = _state.value.copy(searchResults = emptyList())
    }

    // Queue functions
    fun refreshQueue(roomName: String? = _state.value.currentRoomName) {
        if (roomName == null) return
        viewModelScope.launch {
            try {
                val response = api.getMusicQueue(getAuthHeader(), roomName)
                _state.value = _state.value.copy(
                    queue = response.queue,
                    currentIndex = response.currentIndex,
                    currentSong = response.currentSong,
                    isPlaying = response.isPlaying
                )
            } catch (e: Exception) {
                // Ignore errors
            }
        }
    }

    fun addToQueue(song: Song) {
        val roomName = _state.value.currentRoomName ?: return
        viewModelScope.launch {
            try {
                api.addToMusicQueue(getAuthHeader(), MusicQueueAddRequest(roomName, song))
                refreshQueue(roomName)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "添加失败: ${e.message}")
            }
        }
    }

    fun removeFromQueue(index: Int) {
        val roomName = _state.value.currentRoomName ?: return
        viewModelScope.launch {
            try {
                api.removeFromMusicQueue(getAuthHeader(), roomName, index)
                refreshQueue(roomName)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "删除失败: ${e.message}")
            }
        }
    }

    fun clearQueue() {
        val roomName = _state.value.currentRoomName ?: return
        viewModelScope.launch {
            try {
                api.clearMusicQueue(getAuthHeader(), MusicRoomRequest(roomName))
                refreshQueue(roomName)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "清空失败: ${e.message}")
            }
        }
    }

    // Bot control functions
    fun getBotStatus(roomName: String? = _state.value.currentRoomName) {
        if (roomName == null) return
        viewModelScope.launch {
            try {
                val response = api.getMusicBotStatus(getAuthHeader(), roomName)
                _state.value = _state.value.copy(
                    botConnected = response.connected,
                    botRoom = response.room,
                    isPlaying = response.isPlaying
                )
                
                if (response.isPlaying) {
                    startProgressPolling(roomName)
                }
            } catch (e: Exception) {
                // Ignore errors
            }
        }
    }

    fun botPlay(roomName: String) {
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(playbackState = "loading", currentRoomName = roomName)
                val response = api.musicBotPlay(getAuthHeader(), MusicBotStartRequest(roomName))
                if (response.success) {
                    _state.value = _state.value.copy(
                        isPlaying = true,
                        botConnected = true,
                        botRoom = roomName,
                        playbackState = "playing"
                    )
                    startProgressPolling(roomName)
                    refreshQueue(roomName)
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    playbackState = "idle",
                    error = "播放失败: ${e.message}"
                )
            }
        }
    }

    fun botPause() {
        val roomName = _state.value.currentRoomName ?: return
        viewModelScope.launch {
            try {
                api.musicBotPause(getAuthHeader(), MusicRoomRequest(roomName))
                _state.value = _state.value.copy(
                    isPlaying = false,
                    playbackState = "paused"
                )
                stopProgressPolling()
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "暂停失败")
            }
        }
    }

    fun botResume() {
        val roomName = _state.value.currentRoomName ?: return
        viewModelScope.launch {
            try {
                val response = api.musicBotResume(getAuthHeader(), MusicRoomRequest(roomName))
                if (response.success) {
                    _state.value = _state.value.copy(
                        isPlaying = true,
                        playbackState = "playing"
                    )
                    startProgressPolling(roomName)
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "恢复播放失败")
            }
        }
    }

    fun botSkip() {
        val roomName = _state.value.currentRoomName ?: return
        viewModelScope.launch {
            try {
                api.musicBotSkip(getAuthHeader(), MusicRoomRequest(roomName))
                refreshQueue(roomName)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "跳过失败")
            }
        }
    }

    fun botSeek(positionMs: Long) {
        val roomName = _state.value.currentRoomName ?: return
        viewModelScope.launch {
            try {
                api.musicBotSeek(getAuthHeader(), MusicSeekRequest(roomName, positionMs))
                _state.value = _state.value.copy(positionMs = positionMs)
            } catch (e: Exception) {
                // Ignore errors
            }
        }
    }

    fun stopBot() {
        val roomName = _state.value.currentRoomName ?: return
        viewModelScope.launch {
            try {
                api.stopMusicBot(getAuthHeader(), MusicRoomRequest(roomName))
                _state.value = _state.value.copy(
                    botConnected = false,
                    botRoom = null,
                    isPlaying = false,
                    playbackState = "idle"
                )
                stopProgressPolling()
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "停止机器人失败")
            }
        }
    }

    private fun startProgressPolling(roomName: String) {
        progressPollingJob?.cancel()
        progressPollingJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                try {
                    val response = api.getMusicProgress(getAuthHeader(), roomName)
                    _state.value = _state.value.copy(
                        positionMs = response.positionMs,
                        durationMs = response.durationMs,
                        playbackState = response.state,
                        isPlaying = response.state == "playing",
                        currentSong = response.currentSong ?: _state.value.currentSong
                    )
                    
                    if (response.state != "playing") {
                        break
                    }
                } catch (e: Exception) {
                    break
                }
            }
        }
    }

    private fun stopProgressPolling() {
        progressPollingJob?.cancel()
        progressPollingJob = null
    }

    // Toggle play/pause
    fun togglePlayPause(voiceRoomName: String?) {
        val currentState = _state.value
        when {
            currentState.isPlaying -> botPause()
            currentState.playbackState == "paused" -> botResume()
            voiceRoomName != null -> botPlay(voiceRoomName)
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    override fun onCleared() {
        super.onCleared()
        progressPollingJob?.cancel()
        loginPollingJob?.cancel()
    }
}
