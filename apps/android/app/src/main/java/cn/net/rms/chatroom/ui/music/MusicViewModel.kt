package cn.net.rms.chatroom.ui.music

import androidx.lifecycle.ViewModel
import cn.net.rms.chatroom.data.manager.MusicPlaybackManager
import cn.net.rms.chatroom.data.manager.MusicState
import cn.net.rms.chatroom.data.model.Song
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Thin UI facade over [MusicPlaybackManager]. Playback state, the music
 * WebSocket, and ExoPlayer live in the manager so music keeps playing while
 * the user navigates away from the voice screen (mirrors the web music store).
 */
@HiltViewModel
class MusicViewModel @Inject constructor(
    private val playback: MusicPlaybackManager
) : ViewModel() {

    val state: StateFlow<MusicState> = playback.state

    init {
        playback.checkAllLoginStatus()
    }

    // Login
    fun checkAllLoginStatus() = playback.checkAllLoginStatus()
    fun getQRCode(platform: String = "qq") = playback.getQRCode(platform)
    fun logout(platform: String = "qq") = playback.logout(platform)
    fun dismissQRCode() = playback.dismissQRCode()

    // Search
    fun setSearchPlatform(platform: String) = playback.setSearchPlatform(platform)
    fun search(keyword: String) = playback.search(keyword)
    fun clearSearchResults() = playback.clearSearchResults()

    // Queue
    fun addToQueue(song: Song) = playback.addToQueue(song)
    fun removeFromQueue(index: Int) = playback.removeFromQueue(index)
    fun clearQueue() = playback.clearQueue()
    fun refreshQueue() = playback.refreshQueue()

    // Playback control
    fun togglePlayPause() = playback.togglePlayPause()
    fun skipNext() = playback.skipNext()
    fun skipPrevious() = playback.skipPrevious()
    fun seek(positionMs: Long) = playback.seek(positionMs)
    fun stopPlayback() = playback.stopPlayback()

    // Volume / errors
    fun setVolume(volume: Float) = playback.setVolume(volume)
    fun clearError() = playback.clearError()
}
