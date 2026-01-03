package cn.net.rms.chatroom.ui.music

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import cn.net.rms.chatroom.data.api.ApiService
import cn.net.rms.chatroom.data.model.MusicBotStartRequest
import cn.net.rms.chatroom.data.model.MusicQueueAddRequest
import cn.net.rms.chatroom.data.model.MusicRoomRequest
import cn.net.rms.chatroom.data.model.MusicSearchRequest
import cn.net.rms.chatroom.data.model.MusicSeekRequest
import cn.net.rms.chatroom.data.model.QueueItem
import cn.net.rms.chatroom.data.model.Song
import cn.net.rms.chatroom.data.repository.AuthRepository
import cn.net.rms.chatroom.data.websocket.MusicWebSocket
import cn.net.rms.chatroom.data.websocket.MusicWebSocketEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MusicState(
    // Login state (per platform)
    val qqLoggedIn: Boolean = false,
    val neteaseLoggedIn: Boolean = false,
    val isLoggedIn: Boolean = false,  // True if any platform is logged in
    val qrCodeUrl: String? = null,
    val loginStatus: String = "idle",
    val loginPlatform: String = "qq",  // Current login platform

    // Search state
    val searchPlatform: String = "all",  // "all", "qq", or "netease"
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
    val volume: Float = 1.0f,  // Volume level (0.0 to 1.0)

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
    application: Application,
    private val api: ApiService,
    private val authRepository: AuthRepository,
    private val musicWebSocket: MusicWebSocket
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MusicViewModel"
    }

    private val _state = MutableStateFlow(MusicState())
    val state: StateFlow<MusicState> = _state.asStateFlow()

    private var loginPollingJob: Job? = null
    private var webSocketObserveJob: Job? = null

    // ExoPlayer for client-side music playback
    private val exoPlayer: ExoPlayer = ExoPlayer.Builder(application).build().apply {
        addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_ENDED -> {
                        Log.d(TAG, "ExoPlayer: Song ended")
                    }
                    Player.STATE_READY -> {
                        Log.d(TAG, "ExoPlayer: Ready to play")
                    }
                    Player.STATE_BUFFERING -> {
                        Log.d(TAG, "ExoPlayer: Buffering")
                    }
                    Player.STATE_IDLE -> {
                        Log.d(TAG, "ExoPlayer: Idle")
                    }
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e(TAG, "ExoPlayer error: ${error.message}", error)
                _state.value = _state.value.copy(
                    error = "播放失败: ${error.message}",
                    playbackState = "error"
                )
            }
        })
    }

    init {
        viewModelScope.launch {
            checkLoginStatus()
        }
        observeMusicWebSocketEvents()
        loadSavedVolume()
    }

    private fun loadSavedVolume() {
        val sharedPrefs = getApplication<Application>().getSharedPreferences("music_prefs", android.content.Context.MODE_PRIVATE)
        val savedVolume = sharedPrefs.getFloat("volume", 1.0f)
        _state.value = _state.value.copy(volume = savedVolume)
        exoPlayer.volume = savedVolume
    }

    private fun observeMusicWebSocketEvents() {
        webSocketObserveJob?.cancel()
        webSocketObserveJob = viewModelScope.launch {
            musicWebSocket.events.collect { event ->
                when (event) {
                    is MusicWebSocketEvent.Play -> {
                        val currentRoom = _state.value.currentRoomName
                        if (currentRoom != null && event.roomName == currentRoom) {
                            Log.d(TAG, "WebSocket Play: song=${event.song.name}, url=${event.url}, position=${event.positionMs}")
                            handlePlayCommand(event.song, event.url, event.positionMs)
                        }
                    }
                    is MusicWebSocketEvent.Pause -> {
                        val currentRoom = _state.value.currentRoomName
                        if (currentRoom != null && event.roomName == currentRoom) {
                            Log.d(TAG, "WebSocket Pause")
                            handlePauseCommand()
                        }
                    }
                    is MusicWebSocketEvent.Resume -> {
                        val currentRoom = _state.value.currentRoomName
                        if (currentRoom != null && event.roomName == currentRoom) {
                            Log.d(TAG, "WebSocket Resume: position=${event.positionMs}")
                            handleResumeCommand(event.positionMs)
                        }
                    }
                    is MusicWebSocketEvent.Seek -> {
                        val currentRoom = _state.value.currentRoomName
                        if (currentRoom != null && event.roomName == currentRoom) {
                            Log.d(TAG, "WebSocket Seek: position=${event.positionMs}")
                            handleSeekCommand(event.positionMs)
                        }
                    }
                    is MusicWebSocketEvent.MusicStateUpdate -> {
                        val currentRoom = _state.value.currentRoomName
                        if (currentRoom != null && event.roomName == currentRoom) {
                            Log.d(TAG, "WebSocket state update: playing=${event.isPlaying}, song=${event.currentSong?.name}")
                            _state.value = _state.value.copy(
                                isPlaying = event.isPlaying,
                                currentSong = event.currentSong,
                                currentIndex = event.currentIndex,
                                positionMs = event.positionMs,
                                durationMs = event.durationMs,
                                playbackState = event.state
                            )
                            // Refresh queue when state changes (e.g., song ended, skip)
                            if (event.state == "idle" || event.currentIndex != _state.value.currentIndex) {
                                refreshQueue(currentRoom)
                            }
                        }
                    }
                    is MusicWebSocketEvent.SongUnavailable -> {
                        val currentRoom = _state.value.currentRoomName
                        if (currentRoom != null && event.roomName == currentRoom) {
                            Log.w(TAG, "Song unavailable: ${event.songName} - ${event.reason}")
                            _state.value = _state.value.copy(
                                error = "歌曲不可用: ${event.songName}"
                            )
                        }
                    }
                    is MusicWebSocketEvent.Connected -> {
                        Log.d(TAG, "Music WebSocket connected")
                    }
                    is MusicWebSocketEvent.Disconnected -> {
                        Log.d(TAG, "Music WebSocket disconnected")
                    }
                    is MusicWebSocketEvent.Error -> {
                        Log.e(TAG, "Music WebSocket error: ${event.error}")
                    }
                }
            }
        }
    }

    // Handle WebSocket play command
    private fun handlePlayCommand(song: Song, url: String, positionMs: Long) {
        try {
            val mediaItem = MediaItem.fromUri(url)
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.seekTo(positionMs)
            exoPlayer.play()

            _state.value = _state.value.copy(
                currentSong = song,
                isPlaying = true,
                playbackState = "playing",
                positionMs = positionMs
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play song", e)
            _state.value = _state.value.copy(
                error = "播放失败: ${e.message}",
                playbackState = "error"
            )
        }
    }

    // Handle WebSocket pause command
    private fun handlePauseCommand() {
        exoPlayer.pause()
        _state.value = _state.value.copy(
            isPlaying = false,
            playbackState = "paused"
        )
    }

    // Handle WebSocket resume command
    private fun handleResumeCommand(positionMs: Long) {
        exoPlayer.seekTo(positionMs)
        exoPlayer.play()
        _state.value = _state.value.copy(
            isPlaying = true,
            playbackState = "playing",
            positionMs = positionMs
        )
    }

    // Handle WebSocket seek command
    private fun handleSeekCommand(positionMs: Long) {
        exoPlayer.seekTo(positionMs)
        _state.value = _state.value.copy(positionMs = positionMs)
    }
    
    // Set current room name when user joins a voice channel
    fun setCurrentRoom(roomName: String?) {
        _state.value = _state.value.copy(currentRoomName = roomName)
        if (roomName != null) {
            refreshQueue(roomName)
            getBotStatus(roomName)
            connectMusicWebSocket(roomName)
        } else {
            // Disconnect WebSocket and stop playback when leaving voice channel
            musicWebSocket.disconnect()
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            _state.value = _state.value.copy(
                isPlaying = false,
                playbackState = "idle",
                currentSong = null,
                positionMs = 0,
                durationMs = 0
            )
        }
    }

    private fun connectMusicWebSocket(roomName: String) {
        viewModelScope.launch {
            val token = authRepository.getToken()
            if (token != null) {
                musicWebSocket.connect(token, roomName)
            }
        }
    }

    private suspend fun getAuthHeader(): String {
        val token = authRepository.getToken() ?: ""
        return "Bearer $token"
    }

    // Login functions
    fun checkAllLoginStatus() {
        viewModelScope.launch {
            try {
                val response = api.checkAllMusicLogin(getAuthHeader())
                _state.value = _state.value.copy(
                    qqLoggedIn = response.qq.loggedIn,
                    neteaseLoggedIn = response.netease.loggedIn,
                    isLoggedIn = response.qq.loggedIn || response.netease.loggedIn
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    qqLoggedIn = false,
                    neteaseLoggedIn = false,
                    isLoggedIn = false
                )
            }
        }
    }

    fun checkLoginStatus() {
        checkAllLoginStatus()
    }

    fun getQRCode(platform: String = "qq") {
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(loginStatus = "loading", loginPlatform = platform)
                val response = api.getMusicQRCode(platform)
                _state.value = _state.value.copy(
                    qrCodeUrl = response.qrcode,
                    loginStatus = "waiting"
                )
                startLoginPolling(platform)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loginStatus = "error",
                    error = "获取二维码失败: ${e.message}"
                )
            }
        }
    }

    private fun startLoginPolling(platform: String) {
        loginPollingJob?.cancel()
        loginPollingJob = viewModelScope.launch {
            while (true) {
                delay(2000)
                try {
                    val response = api.checkMusicLoginStatus(platform)
                    _state.value = _state.value.copy(loginStatus = response.status)
                    
                    when (response.status) {
                        "success" -> {
                            val newState = if (platform == "qq") {
                                _state.value.copy(qqLoggedIn = true, isLoggedIn = true, qrCodeUrl = null)
                            } else {
                                _state.value.copy(neteaseLoggedIn = true, isLoggedIn = true, qrCodeUrl = null)
                            }
                            _state.value = newState
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

    fun logout(platform: String = "qq") {
        viewModelScope.launch {
            try {
                api.musicLogout(getAuthHeader(), platform)
                val newState = if (platform == "qq") {
                    _state.value.copy(
                        qqLoggedIn = false,
                        isLoggedIn = _state.value.neteaseLoggedIn
                    )
                } else {
                    _state.value.copy(
                        neteaseLoggedIn = false,
                        isLoggedIn = _state.value.qqLoggedIn
                    )
                }
                _state.value = newState
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
    fun setSearchPlatform(platform: String) {
        _state.value = _state.value.copy(searchPlatform = platform)
    }

    fun search(keyword: String, platform: String = _state.value.searchPlatform) {
        if (keyword.isBlank()) {
            _state.value = _state.value.copy(searchResults = emptyList())
            return
        }
        
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(isSearching = true)
                val response = api.searchMusic(getAuthHeader(), MusicSearchRequest(keyword, platform = platform))
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

    fun botPrevious() {
        val roomName = _state.value.currentRoomName ?: return
        viewModelScope.launch {
            try {
                api.musicBotPrevious(getAuthHeader(), MusicRoomRequest(roomName))
                refreshQueue(roomName)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "上一首失败")
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
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "停止机器人失败")
            }
        }
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

    // Volume control
    fun setVolume(volume: Float) {
        val clampedVolume = volume.coerceIn(0f, 1f)
        _state.value = _state.value.copy(volume = clampedVolume)
        exoPlayer.volume = clampedVolume

        // Save to SharedPreferences
        val sharedPrefs = getApplication<Application>().getSharedPreferences("music_prefs", android.content.Context.MODE_PRIVATE)
        sharedPrefs.edit().putFloat("volume", clampedVolume).apply()
    }

    override fun onCleared() {
        super.onCleared()
        loginPollingJob?.cancel()
        webSocketObserveJob?.cancel()
        musicWebSocket.disconnect()
        exoPlayer.release()
    }
}
