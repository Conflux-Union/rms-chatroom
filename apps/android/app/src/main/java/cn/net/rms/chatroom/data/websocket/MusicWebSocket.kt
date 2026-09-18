package cn.net.rms.chatroom.data.websocket

import android.util.Log
import cn.net.rms.chatroom.BuildConfig
import cn.net.rms.chatroom.data.auth.TokenAuthenticator
import cn.net.rms.chatroom.data.model.Song
import cn.net.rms.chatroom.data.monitor.NetworkMonitor
import cn.net.rms.chatroom.data.telemetry.TelemetryReporter
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

sealed class MusicWebSocketEvent {
    data class MusicStateUpdate(
        val roomName: String,
        val isPlaying: Boolean,
        val currentSong: Song?,
        val currentIndex: Int,
        val positionMs: Long,
        val durationMs: Long,
        val state: String,
        val queueLength: Int,
        val serverTime: Double? = null
    ) : MusicWebSocketEvent()
    data class Play(
        val roomName: String,
        val song: Song,
        val url: String,
        val positionMs: Long,
        val serverTime: Double? = null
    ) : MusicWebSocketEvent()
    data class Pause(val roomName: String, val serverTime: Double? = null) : MusicWebSocketEvent()
    data class Resume(val roomName: String, val positionMs: Long, val serverTime: Double? = null) : MusicWebSocketEvent()
    data class Seek(val roomName: String, val positionMs: Long, val serverTime: Double? = null) : MusicWebSocketEvent()
    data class Stop(val roomName: String) : MusicWebSocketEvent()
    data class QueueFinished(val roomName: String) : MusicWebSocketEvent()
    data class SongUnavailable(val roomName: String, val songName: String, val reason: String) : MusicWebSocketEvent()
    data class MusicLoginStatus(val status: String, val platform: String) : MusicWebSocketEvent()
    object Connected : MusicWebSocketEvent()
    object Disconnected : MusicWebSocketEvent()
    data class Error(val error: String) : MusicWebSocketEvent()
}

@Singleton
class MusicWebSocket @Inject constructor(
    client: OkHttpClient,
    gson: Gson,
    tokenAuthenticator: TokenAuthenticator,
    telemetryReporter: TelemetryReporter,
    networkMonitor: NetworkMonitor
) : BaseWebSocket<MusicWebSocketEvent>(
    client, gson, tokenAuthenticator, telemetryReporter, networkMonitor,
    tag = TAG, wsName = "music"
) {
    private var currentRoomName: String? = null

    override fun isReadyToConnect(): Boolean = currentRoomName != null

    override fun onDisconnected() {
        currentRoomName = null
    }

    fun connect(token: String, roomName: String) {
        if (connectionState.value == ConnectionState.CONNECTED &&
            isConnectedWith(token) && currentRoomName == roomName
        ) {
            Log.d(TAG, "Already connected with same token and room")
            return
        }

        // Set the room before the base connect: doConnect() checks readiness
        // synchronously, and base connect() never clears subclass state.
        currentRoomName = roomName
        connect(token)
    }

    override fun buildUrl(token: String): String {
        val room = currentRoomName ?: return "${BuildConfig.WS_BASE_URL}/ws/music?token=$token"
        val encodedRoom = URLEncoder.encode(room, "UTF-8")
        return "${BuildConfig.WS_BASE_URL}/ws/music?token=$token&room_name=$encodedRoom"
    }

    override fun connectedEvent(): MusicWebSocketEvent = MusicWebSocketEvent.Connected
    override fun disconnectedEvent(): MusicWebSocketEvent = MusicWebSocketEvent.Disconnected
    override fun errorEvent(message: String): MusicWebSocketEvent = MusicWebSocketEvent.Error(message)

    override fun handleMessage(json: JsonObject): MusicWebSocketEvent? {
        val serverTime = json.get("server_time")?.asDouble

        return when (val type = json.get("type")?.asString) {
            "play" -> {
                val roomName = json.get("room_name")?.asString ?: ""
                val url = json.get("url")?.asString ?: ""
                val positionMs = json.get("position_ms")?.asLong ?: 0L
                val song = parseSong(json.getAsJsonObject("song"))

                Log.d(TAG, "Play command: room=$roomName, song=${song.name}, url=$url, serverTime=$serverTime")
                MusicWebSocketEvent.Play(roomName, song, url, positionMs, serverTime)
            }
            "pause" -> {
                val roomName = json.get("room_name")?.asString ?: ""
                Log.d(TAG, "Pause command: room=$roomName, serverTime=$serverTime")
                MusicWebSocketEvent.Pause(roomName, serverTime)
            }
            "resume" -> {
                val roomName = json.get("room_name")?.asString ?: ""
                val positionMs = json.get("position_ms")?.asLong ?: 0L
                Log.d(TAG, "Resume command: room=$roomName, position=$positionMs, serverTime=$serverTime")
                MusicWebSocketEvent.Resume(roomName, positionMs, serverTime)
            }
            "seek" -> {
                val roomName = json.get("room_name")?.asString ?: ""
                val positionMs = json.get("position_ms")?.asLong ?: 0L
                Log.d(TAG, "Seek command: room=$roomName, position=$positionMs, serverTime=$serverTime")
                MusicWebSocketEvent.Seek(roomName, positionMs, serverTime)
            }
            "stop" -> {
                val roomName = json.get("room_name")?.asString ?: ""
                Log.d(TAG, "Stop command: room=$roomName")
                MusicWebSocketEvent.Stop(roomName)
            }
            "queue_finished" -> {
                val roomName = json.get("room_name")?.asString ?: ""
                Log.d(TAG, "Queue finished: room=$roomName")
                MusicWebSocketEvent.QueueFinished(roomName)
            }
            "music_state" -> {
                val data = json.getAsJsonObject("data")
                val roomName = data.get("room_name")?.asString ?: ""
                val state = data.get("state")?.asString ?: "idle"
                val positionMs = data.get("position_ms")?.asLong ?: 0L
                val durationMs = data.get("duration_ms")?.asLong ?: 0L
                val currentIndex = data.get("current_index")?.asInt ?: 0
                val queueLength = data.get("queue_length")?.asInt ?: 0
                val dataServerTime = data.get("server_time")?.asDouble

                val currentSong = if (data.has("current_song") && !data.get("current_song").isJsonNull) {
                    parseSong(data.getAsJsonObject("current_song"))
                } else null

                Log.d(TAG, "Music state update: room=$roomName, state=$state, song=${currentSong?.name}")

                MusicWebSocketEvent.MusicStateUpdate(
                    roomName = roomName,
                    isPlaying = state == "playing",
                    currentSong = currentSong,
                    currentIndex = currentIndex,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    state = state,
                    queueLength = queueLength,
                    serverTime = dataServerTime
                )
            }
            "song_unavailable" -> {
                val roomName = json.get("room_name")?.asString ?: ""
                // Server sends a "song" object + "error"; older payloads used "song_name"/"reason"
                val songName = when {
                    json.has("song") && json.get("song").isJsonObject ->
                        json.getAsJsonObject("song").get("name")?.asString ?: ""
                    else -> json.get("song_name")?.asString ?: ""
                }
                val reason = json.get("reason")?.asString
                    ?: json.get("error")?.asString
                    ?: "Unknown reason"
                Log.w(TAG, "Song unavailable: room=$roomName, song=$songName, reason=$reason")
                MusicWebSocketEvent.SongUnavailable(roomName, songName, reason)
            }
            "music_login_status" -> {
                val status = json.get("status")?.asString ?: ""
                val platform = json.get("platform")?.asString ?: "qq"
                Log.d(TAG, "Music login status: platform=$platform, status=$status")
                MusicWebSocketEvent.MusicLoginStatus(status, platform)
            }
            "connected" -> {
                Log.d(TAG, "Music WebSocket server confirmed connection")
                null
            }
            else -> {
                Log.d(TAG, "Unknown music message type: $type")
                null
            }
        }
    }

    private fun parseSong(songObj: JsonObject): Song {
        return Song(
            mid = songObj.get("mid")?.asString ?: "",
            name = songObj.get("name")?.asString ?: "",
            artist = songObj.get("artist")?.asString ?: "",
            album = songObj.get("album")?.asString ?: "",
            duration = songObj.get("duration")?.asInt ?: 0,
            cover = songObj.get("cover")?.asString ?: "",
            platform = songObj.get("platform")?.asString ?: "qq"
        )
    }

    fun getCurrentRoom(): String? = currentRoomName

    companion object {
        private const val TAG = "MusicWebSocket"
    }
}
