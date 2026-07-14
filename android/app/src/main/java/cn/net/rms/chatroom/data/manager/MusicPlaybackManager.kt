package cn.net.rms.chatroom.data.manager

import android.content.Context
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import cn.net.rms.chatroom.data.api.ApiService
import cn.net.rms.chatroom.data.model.MusicQueueAddRequest
import cn.net.rms.chatroom.data.model.MusicRoomRequest
import cn.net.rms.chatroom.data.model.MusicSearchRequest
import cn.net.rms.chatroom.data.model.MusicSeekRequest
import cn.net.rms.chatroom.data.model.QueueItem
import cn.net.rms.chatroom.data.model.Song
import cn.net.rms.chatroom.data.repository.AuthRepository
import cn.net.rms.chatroom.data.repository.VoiceRepository
import cn.net.rms.chatroom.data.repository.isUnauthorized
import cn.net.rms.chatroom.data.websocket.MusicWebSocket
import cn.net.rms.chatroom.data.websocket.MusicWebSocketEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

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

    // Room playback state (backend is the master controller per room)
    val playbackActive: Boolean = false,
    val playbackRoom: String? = null,

    // Current room, follows the joined voice channel
    val currentRoomName: String? = null,

    // UI state
    val isLoading: Boolean = false,
    val error: String? = null
)

/**
 * Process-wide music playback controller, mirroring the web music store:
 * it follows the *joined* voice channel (not the visible screen), so playback
 * survives navigating away from the voice screen and stops on leaving voice.
 */
@Singleton
class MusicPlaybackManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: ApiService,
    private val authRepository: AuthRepository,
    private val voiceRepository: VoiceRepository,
    private val musicWebSocket: MusicWebSocket
) {
    companion object {
        private const val TAG = "MusicPlaybackManager"
        private const val PROGRESS_UPDATE_INTERVAL_MS = 500L
    }

    // ExoPlayer requires all access from its application looper thread
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(MusicState())
    val state: StateFlow<MusicState> = _state.asStateFlow()

    private var progressJob: Job? = null

    private val exoPlayer: ExoPlayer = ExoPlayer.Builder(context)
        .setLooper(Looper.getMainLooper())
        .build()
        .apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        // Backend advances the queue and pushes the next play command
                        stopProgressUpdates()
                        _state.value = _state.value.copy(
                            isPlaying = false,
                            playbackState = "idle"
                        )
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "ExoPlayer error: ${error.message}", error)
                    _state.value = _state.value.copy(
                        error = "播放失败: ${error.message}",
                        playbackState = "error"
                    )
                }
            })
        }

    init {
        scope.launch {
            val sharedPrefs = context.getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
            val savedVolume = sharedPrefs.getFloat("volume", 1.0f)
            _state.value = _state.value.copy(volume = savedVolume)
            exoPlayer.volume = savedVolume
        }
        observeVoiceChannel()
        observeWebSocketEvents()
    }

    // Follow the joined voice channel, like the web store's voice-channel watcher
    private fun observeVoiceChannel() {
        scope.launch {
            voiceRepository.currentChannelId.collect { channelId ->
                setCurrentRoom(channelId?.let { "voice_$it" })
            }
        }
    }

    private fun setCurrentRoom(roomName: String?) {
        if (_state.value.currentRoomName == roomName) return
        _state.value = _state.value.copy(currentRoomName = roomName)
        if (roomName != null) {
            refreshQueue(roomName)
            refreshPlaybackStatus(roomName)
            connectWebSocket(roomName)
        } else {
            musicWebSocket.disconnect()
            stopProgressUpdates()
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            _state.value = _state.value.copy(
                isPlaying = false,
                playbackState = "idle",
                currentSong = null,
                positionMs = 0,
                durationMs = 0,
                playbackActive = false,
                playbackRoom = null
            )
        }
    }

    private fun connectWebSocket(roomName: String) {
        scope.launch {
            val token = authRepository.getToken()
            if (token != null) {
                musicWebSocket.connect(token, roomName)
            }
        }
    }

    private fun observeWebSocketEvents() {
        scope.launch {
            musicWebSocket.events.collect { event ->
                val currentRoom = _state.value.currentRoomName
                when (event) {
                    is MusicWebSocketEvent.Play -> {
                        if (currentRoom != null && event.roomName == currentRoom) {
                            Log.d(TAG, "WebSocket Play: song=${event.song.name}, position=${event.positionMs}")
                            handlePlayCommand(event.song, event.url, event.positionMs, currentRoom)
                        }
                    }
                    is MusicWebSocketEvent.Pause -> {
                        if (currentRoom != null && event.roomName == currentRoom) {
                            handlePauseCommand()
                        }
                    }
                    is MusicWebSocketEvent.Resume -> {
                        if (currentRoom != null && event.roomName == currentRoom) {
                            handleResumeCommand(event.positionMs)
                        }
                    }
                    is MusicWebSocketEvent.Seek -> {
                        if (currentRoom != null && event.roomName == currentRoom) {
                            handleSeekCommand(event.positionMs)
                        }
                    }
                    is MusicWebSocketEvent.Stop -> {
                        if (currentRoom != null && event.roomName == currentRoom) {
                            handleStopCommand(currentRoom)
                        }
                    }
                    is MusicWebSocketEvent.QueueFinished -> {
                        if (currentRoom != null && event.roomName == currentRoom) {
                            stopProgressUpdates()
                            _state.value = _state.value.copy(
                                isPlaying = false,
                                playbackState = "idle"
                            )
                        }
                    }
                    is MusicWebSocketEvent.MusicStateUpdate -> {
                        if (currentRoom != null && event.roomName == currentRoom) {
                            val previousIndex = _state.value.currentIndex
                            _state.value = _state.value.copy(
                                isPlaying = event.isPlaying,
                                currentSong = event.currentSong,
                                currentIndex = event.currentIndex,
                                positionMs = event.positionMs,
                                durationMs = event.durationMs,
                                playbackState = event.state
                            )
                            // Refresh queue when state changes (e.g., song ended, skip)
                            if (event.state == "idle" || event.currentIndex != previousIndex) {
                                refreshQueue(currentRoom)
                            }
                        }
                    }
                    is MusicWebSocketEvent.SongUnavailable -> {
                        if (currentRoom != null && event.roomName == currentRoom) {
                            Log.w(TAG, "Song unavailable: ${event.songName} - ${event.reason}")
                            _state.value = _state.value.copy(
                                error = "歌曲不可用: ${event.songName}"
                            )
                        }
                    }
                    is MusicWebSocketEvent.MusicLoginStatus -> {
                        handleMusicLoginStatus(event.platform, event.status)
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

    private fun handlePlayCommand(song: Song, url: String, positionMs: Long, roomName: String) {
        try {
            _state.value = _state.value.copy(
                currentSong = song,
                isPlaying = true,
                playbackState = "playing",
                positionMs = positionMs,
                durationMs = song.duration * 1000L
            )
            // Sync queue and current index, like the web store does on play
            refreshQueue(roomName)

            exoPlayer.setMediaItem(MediaItem.fromUri(url))
            exoPlayer.prepare()
            exoPlayer.seekTo(positionMs)
            exoPlayer.play()
            startProgressUpdates()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play song", e)
            _state.value = _state.value.copy(
                error = "播放失败: ${e.message}",
                playbackState = "error"
            )
        }
    }

    private fun handlePauseCommand() {
        exoPlayer.pause()
        stopProgressUpdates()
        _state.value = _state.value.copy(
            isPlaying = false,
            playbackState = "paused"
        )
    }

    private fun handleResumeCommand(positionMs: Long) {
        exoPlayer.seekTo(positionMs)
        exoPlayer.play()
        startProgressUpdates()
        _state.value = _state.value.copy(
            isPlaying = true,
            playbackState = "playing",
            positionMs = positionMs
        )
    }

    private fun handleSeekCommand(positionMs: Long) {
        exoPlayer.seekTo(positionMs)
        _state.value = _state.value.copy(positionMs = positionMs)
    }

    private fun handleStopCommand(roomName: String) {
        stopProgressUpdates()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        _state.value = _state.value.copy(
            isPlaying = false,
            playbackState = "idle",
            currentSong = null,
            positionMs = 0,
            playbackActive = false,
            playbackRoom = null
        )
        refreshQueue(roomName)
    }

    // Drive the progress bar from local playback, like the web audio 'timeupdate'
    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                if (exoPlayer.isPlaying) {
                    _state.value = _state.value.copy(positionMs = exoPlayer.currentPosition)
                }
                delay(PROGRESS_UPDATE_INTERVAL_MS)
            }
        }
    }

    private fun stopProgressUpdates() {
        progressJob?.cancel()
        progressJob = null
    }

    private suspend fun getAuthHeader(): String {
        val token = authRepository.getToken() ?: ""
        return "Bearer $token"
    }

    // --- Login functions ---

    fun checkAllLoginStatus() {
        scope.launch {
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

    private fun handleMusicLoginStatus(platform: String, status: String) {
        // Only handle if this is the platform we're waiting for
        if (platform != _state.value.loginPlatform) return

        _state.value = _state.value.copy(loginStatus = status)

        when (status) {
            "success" -> {
                _state.value = if (platform == "qq") {
                    _state.value.copy(qqLoggedIn = true, isLoggedIn = true, qrCodeUrl = null)
                } else {
                    _state.value.copy(neteaseLoggedIn = true, isLoggedIn = true, qrCodeUrl = null)
                }
            }
            "expired", "refused" -> {
                _state.value = _state.value.copy(qrCodeUrl = null)
            }
        }
    }

    fun getQRCode(platform: String = "qq") {
        scope.launch {
            try {
                _state.value = _state.value.copy(loginStatus = "loading", loginPlatform = platform)
                val response = api.getMusicQRCode(platform)
                _state.value = _state.value.copy(
                    qrCodeUrl = response.qrcode,
                    loginStatus = "waiting"
                )
                // Login status is pushed via WebSocket (music_login_status event)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loginStatus = "error",
                    error = "获取二维码失败: ${e.message}"
                )
            }
        }
    }

    fun logout(platform: String = "qq") {
        scope.launch {
            try {
                api.musicLogout(getAuthHeader(), platform)
                _state.value = if (platform == "qq") {
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
            } catch (e: Exception) {
                if (!e.isUnauthorized) _state.value = _state.value.copy(error = "退出登录失败")
            }
        }
    }

    fun dismissQRCode() {
        _state.value = _state.value.copy(qrCodeUrl = null, loginStatus = "idle")
    }

    // --- Search functions ---

    fun setSearchPlatform(platform: String) {
        _state.value = _state.value.copy(searchPlatform = platform)
    }

    fun search(keyword: String, platform: String = _state.value.searchPlatform) {
        if (keyword.isBlank()) {
            _state.value = _state.value.copy(searchResults = emptyList())
            return
        }

        scope.launch {
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
                    error = if (e.isUnauthorized) null else "搜索失败: ${e.message}"
                )
            }
        }
    }

    fun clearSearchResults() {
        _state.value = _state.value.copy(searchResults = emptyList())
    }

    // --- Queue functions ---

    fun refreshQueue(roomName: String? = _state.value.currentRoomName) {
        if (roomName == null) return
        scope.launch {
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
        scope.launch {
            try {
                api.addToMusicQueue(getAuthHeader(), MusicQueueAddRequest(roomName, song))
                refreshQueue(roomName)
            } catch (e: Exception) {
                if (!e.isUnauthorized) _state.value = _state.value.copy(error = "添加失败: ${e.message}")
            }
        }
    }

    fun removeFromQueue(index: Int) {
        val roomName = _state.value.currentRoomName ?: return
        scope.launch {
            try {
                api.removeFromMusicQueue(getAuthHeader(), roomName, index)
                refreshQueue(roomName)
            } catch (e: Exception) {
                if (!e.isUnauthorized) _state.value = _state.value.copy(error = "删除失败: ${e.message}")
            }
        }
    }

    fun clearQueue() {
        val roomName = _state.value.currentRoomName ?: return
        scope.launch {
            try {
                api.clearMusicQueue(getAuthHeader(), MusicRoomRequest(roomName))
                refreshQueue(roomName)
            } catch (e: Exception) {
                if (!e.isUnauthorized) _state.value = _state.value.copy(error = "清空失败: ${e.message}")
            }
        }
    }

    // --- Playback control functions (commands to the backend room controller) ---

    fun refreshPlaybackStatus(roomName: String? = _state.value.currentRoomName) {
        if (roomName == null) return
        scope.launch {
            try {
                val response = api.getMusicPlaybackStatus(getAuthHeader(), roomName)
                _state.value = _state.value.copy(
                    playbackActive = response.active,
                    playbackRoom = response.room,
                    isPlaying = response.isPlaying
                )
            } catch (e: Exception) {
                // Ignore errors
            }
        }
    }

    fun play(roomName: String) {
        scope.launch {
            try {
                _state.value = _state.value.copy(playbackState = "loading")
                val response = api.musicPlaybackPlay(getAuthHeader(), MusicRoomRequest(roomName))
                if (response.success) {
                    _state.value = _state.value.copy(
                        isPlaying = true,
                        playbackActive = true,
                        playbackRoom = roomName,
                        playbackState = "playing"
                    )
                    refreshQueue(roomName)
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    playbackState = "idle",
                    error = if (e.isUnauthorized) null else "播放失败: ${e.message}"
                )
            }
        }
    }

    fun pause() {
        val roomName = _state.value.currentRoomName ?: return
        scope.launch {
            try {
                api.musicPlaybackPause(getAuthHeader(), MusicRoomRequest(roomName))
                _state.value = _state.value.copy(
                    isPlaying = false,
                    playbackState = "paused"
                )
            } catch (e: Exception) {
                if (!e.isUnauthorized) _state.value = _state.value.copy(error = "暂停失败")
            }
        }
    }

    fun resume() {
        val roomName = _state.value.currentRoomName ?: return
        scope.launch {
            try {
                val response = api.musicPlaybackResume(getAuthHeader(), MusicRoomRequest(roomName))
                if (response.success) {
                    _state.value = _state.value.copy(
                        isPlaying = true,
                        playbackState = "playing"
                    )
                }
            } catch (e: Exception) {
                if (!e.isUnauthorized) _state.value = _state.value.copy(error = "恢复播放失败")
            }
        }
    }

    fun skipNext() {
        val roomName = _state.value.currentRoomName ?: return
        scope.launch {
            try {
                api.musicPlaybackSkip(getAuthHeader(), MusicRoomRequest(roomName))
                refreshQueue(roomName)
            } catch (e: Exception) {
                if (!e.isUnauthorized) _state.value = _state.value.copy(error = "跳过失败")
            }
        }
    }

    fun skipPrevious() {
        val roomName = _state.value.currentRoomName ?: return
        scope.launch {
            try {
                api.musicPlaybackPrevious(getAuthHeader(), MusicRoomRequest(roomName))
                refreshQueue(roomName)
            } catch (e: Exception) {
                if (!e.isUnauthorized) _state.value = _state.value.copy(error = "上一首失败")
            }
        }
    }

    fun seek(positionMs: Long) {
        val roomName = _state.value.currentRoomName ?: return
        scope.launch {
            try {
                api.musicPlaybackSeek(getAuthHeader(), MusicSeekRequest(roomName, positionMs))
                _state.value = _state.value.copy(positionMs = positionMs)
            } catch (e: Exception) {
                // Ignore errors
            }
        }
    }

    fun stopPlayback() {
        val roomName = _state.value.currentRoomName ?: return
        scope.launch {
            try {
                api.stopMusicPlayback(getAuthHeader(), MusicRoomRequest(roomName))
                // The backend broadcasts a stop command; handleStopCommand
                // stops the local player when it arrives.
                _state.value = _state.value.copy(
                    playbackActive = false,
                    playbackRoom = null,
                    isPlaying = false,
                    playbackState = "idle"
                )
            } catch (e: Exception) {
                if (!e.isUnauthorized) _state.value = _state.value.copy(error = "停止播放失败")
            }
        }
    }

    // Toggle play/pause for the joined voice room
    fun togglePlayPause() {
        val currentState = _state.value
        when {
            currentState.isPlaying -> pause()
            currentState.playbackState == "paused" -> resume()
            else -> currentState.currentRoomName?.let { play(it) }
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    // --- Volume control ---

    fun setVolume(volume: Float) {
        val clampedVolume = volume.coerceIn(0f, 1f)
        _state.value = _state.value.copy(volume = clampedVolume)
        exoPlayer.volume = clampedVolume

        val sharedPrefs = context.getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
        sharedPrefs.edit().putFloat("volume", clampedVolume).apply()
    }
}
