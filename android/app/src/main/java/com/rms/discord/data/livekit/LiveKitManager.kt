package com.rms.discord.data.livekit

import android.content.Context
import android.util.Log
import com.twilio.audioswitch.AudioDevice
import dagger.hilt.android.qualifiers.ApplicationContext
import io.livekit.android.AudioOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.RemoteAudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING
}

data class ParticipantInfo(
    val identity: String,
    val name: String,
    val isMuted: Boolean,
    val isSpeaking: Boolean,
    val audioLevel: Float
)

@Singleton
class LiveKitManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "LiveKitManager"
    }

    private var room: Room? = null
    private var scope: CoroutineScope? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _participants = MutableStateFlow<List<ParticipantInfo>>(emptyList())
    val participants: StateFlow<List<ParticipantInfo>> = _participants.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isDeafened = MutableStateFlow(false)
    val isDeafened: StateFlow<Boolean> = _isDeafened.asStateFlow()

    private val _isSpeakerOn = MutableStateFlow(true) // Default to speaker
    val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn.asStateFlow()

    private val _currentRoomName = MutableStateFlow<String?>(null)
    val currentRoomName: StateFlow<String?> = _currentRoomName.asStateFlow()

    private var audioHandler: AudioSwitchHandler? = null

    suspend fun connect(url: String, token: String, roomName: String): Result<Unit> {
        if (_connectionState.value == ConnectionState.CONNECTED) {
            disconnect()
        }

        _connectionState.value = ConnectionState.CONNECTING
        _currentRoomName.value = roomName

        return try {
            // Create audio handler with speaker as default
            val handler = AudioSwitchHandler(context)
            handler.preferredDeviceList = listOf(
                AudioDevice.Speakerphone::class.java,
                AudioDevice.BluetoothHeadset::class.java,
                AudioDevice.WiredHeadset::class.java,
                AudioDevice.Earpiece::class.java
            )
            audioHandler = handler

            // Create room with audio handler
            val newRoom = LiveKit.create(
                appContext = context,
                overrides = LiveKitOverrides(
                    audioOptions = AudioOptions(audioHandler = handler)
                )
            )
            room = newRoom

            // Setup scope for event collection
            scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
            scope?.launch {
                collectRoomEvents(newRoom)
            }

            // Connect to room
            newRoom.connect(url, token)

            // Publish local audio track
            val localParticipant = newRoom.localParticipant
            localParticipant.setMicrophoneEnabled(true)

            _connectionState.value = ConnectionState.CONNECTED
            Log.d(TAG, "Connected to room: $roomName")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to LiveKit", e)
            _connectionState.value = ConnectionState.DISCONNECTED
            _currentRoomName.value = null
            Result.failure(e)
        }
    }

    fun disconnect() {
        scope?.cancel()
        scope = null

        room?.disconnect()
        room?.release()
        room = null
        audioHandler = null

        _connectionState.value = ConnectionState.DISCONNECTED
        _currentRoomName.value = null
        _participants.value = emptyList()
        _isMuted.value = false
        _isDeafened.value = false
        _isSpeakerOn.value = true

        Log.d(TAG, "Disconnected from room")
    }

    fun setMuted(muted: Boolean) {
        room?.localParticipant?.let { local ->
            scope?.launch {
                try {
                    local.setMicrophoneEnabled(!muted)
                    _isMuted.value = muted
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set mute state", e)
                }
            }
        }
    }

    fun setDeafened(deafened: Boolean) {
        _isDeafened.value = deafened
        // When deafened, also mute
        if (deafened) {
            setMuted(true)
        }
        // Mute/unmute all remote audio tracks by setting volume
        room?.remoteParticipants?.values?.forEach { participant ->
            participant.audioTrackPublications.forEach { (publication, track) ->
                if (track is RemoteAudioTrack) {
                    try {
                        track.setVolume(if (deafened) 0.0 else 1.0)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    fun setSpeakerOn(speakerOn: Boolean) {
        _isSpeakerOn.value = speakerOn
        audioHandler?.let { handler ->
            val devices = handler.availableAudioDevices
            val targetDevice = if (speakerOn) {
                devices.firstOrNull { it is AudioDevice.Speakerphone }
            } else {
                devices.firstOrNull { it is AudioDevice.Earpiece }
                    ?: devices.firstOrNull { it is AudioDevice.WiredHeadset }
                    ?: devices.firstOrNull { it is AudioDevice.BluetoothHeadset }
            }
            targetDevice?.let { handler.selectDevice(it) }
        }
    }

    private suspend fun collectRoomEvents(room: Room) {
        room.events.collect { event ->
            when (event) {
                is RoomEvent.Connected -> {
                    _connectionState.value = ConnectionState.CONNECTED
                    updateParticipants()
                }
                is RoomEvent.Disconnected -> {
                    _connectionState.value = ConnectionState.DISCONNECTED
                }
                is RoomEvent.Reconnecting -> {
                    _connectionState.value = ConnectionState.RECONNECTING
                }
                is RoomEvent.Reconnected -> {
                    _connectionState.value = ConnectionState.CONNECTED
                }
                is RoomEvent.ParticipantConnected -> {
                    updateParticipants()
                }
                is RoomEvent.ParticipantDisconnected -> {
                    updateParticipants()
                }
                is RoomEvent.TrackPublished -> {
                    updateParticipants()
                }
                is RoomEvent.TrackSubscribed -> {
                    updateParticipants()
                }
                is RoomEvent.TrackMuted -> {
                    updateParticipants()
                }
                is RoomEvent.TrackUnmuted -> {
                    updateParticipants()
                }
                is RoomEvent.ActiveSpeakersChanged -> {
                    updateSpeakingState(event.speakers)
                }
                else -> {}
            }
        }
    }

    private fun updateParticipants() {
        val currentRoom = room ?: return
        val list = mutableListOf<ParticipantInfo>()

        // Add local participant
        currentRoom.localParticipant.let { local ->
            list.add(participantToInfo(local))
        }

        // Add remote participants
        currentRoom.remoteParticipants.values.forEach { remote ->
            list.add(participantToInfo(remote))
        }

        _participants.value = list
    }

    private fun updateSpeakingState(speakers: List<Participant>) {
        val speakingIds = speakers.map { it.identity?.value }.toSet()
        _participants.value = _participants.value.map { info ->
            info.copy(isSpeaking = info.identity in speakingIds)
        }
    }

    private fun participantToInfo(participant: Participant): ParticipantInfo {
        // audioTrackPublications is List<Pair<TrackPublication, Track?>>
        // Check if participant has audio track and its muted state
        val audioPublication = participant.audioTrackPublications.firstOrNull()?.first
        // Default to not muted if no track info yet (most users join with mic on)
        val isMuted = audioPublication?.muted ?: false

        return ParticipantInfo(
            identity = participant.identity?.value ?: "",
            name = participant.name ?: participant.identity?.value ?: "Unknown",
            isMuted = isMuted,
            isSpeaking = participant.isSpeaking,
            audioLevel = participant.audioLevel
        )
    }

    fun isConnected(): Boolean = _connectionState.value == ConnectionState.CONNECTED
}
