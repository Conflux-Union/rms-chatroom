package com.rms.discord.data.livekit

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.util.Log
import com.twilio.audioswitch.AudioDevice
import dagger.hilt.android.qualifiers.ApplicationContext
import io.livekit.android.AudioOptions
import io.livekit.android.AudioType
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.RoomOptions
import io.livekit.android.audio.AudioProcessorOptions
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.AudioTrackPublishDefaults
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.participant.VideoTrackPublishDefaults
import io.livekit.android.room.track.LocalAudioTrackOptions
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.RemoteAudioTrack
import io.livekit.android.room.track.RemoteVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.ScreenSharePresets
import io.livekit.android.room.track.screencapture.ScreenCaptureParams
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
    val audioLevel: Float,
    val volume: Float = 1.0f,  // 0.0-2.0, where 1.0=100%, 2.0=200%
    val isLocal: Boolean = false
)

/**
 * Represents an audio device for UI display
 */
data class AudioDeviceInfo(
    val id: String,
    val name: String,
    val type: AudioDeviceType,
    val isSelected: Boolean
)

enum class AudioDeviceType {
    SPEAKERPHONE,
    EARPIECE,
    WIRED_HEADSET,
    BLUETOOTH
}

/**
 * Represents a remote screen share for UI display
 */
data class ScreenShareInfo(
    val participantId: String,
    val participantName: String,
    val videoTrack: RemoteVideoTrack
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

    private val _availableDevices = MutableStateFlow<List<AudioDeviceInfo>>(emptyList())
    val availableDevices: StateFlow<List<AudioDeviceInfo>> = _availableDevices.asStateFlow()

    private val _selectedDevice = MutableStateFlow<AudioDeviceInfo?>(null)
    val selectedDevice: StateFlow<AudioDeviceInfo?> = _selectedDevice.asStateFlow()

    private var audioHandler: AudioSwitchHandler? = null

    // Per-participant volume storage (identity -> volume 0.0-2.0)
    private val participantVolumes = mutableMapOf<String, Float>()

    // Screen share state
    private val _isScreenSharing = MutableStateFlow(false)
    val isScreenSharing: StateFlow<Boolean> = _isScreenSharing.asStateFlow()

    private val _remoteScreenShares = MutableStateFlow<Map<String, ScreenShareInfo>>(emptyMap())
    val remoteScreenShares: StateFlow<Map<String, ScreenShareInfo>> = _remoteScreenShares.asStateFlow()

    // MediaProjection permission data (stored after user grants permission)
    private var mediaProjectionPermissionData: Intent? = null

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

            // Create room with custom audio type for best music quality
            // Use MODE_NORMAL + CONTENT_TYPE_MUSIC + USAGE_MEDIA to match browser behavior
            val musicAudioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build()
            val musicAudioType = AudioType.CustomAudioType(
                audioMode = AudioManager.MODE_NORMAL,
                audioAttributes = musicAudioAttributes,
                audioStreamType = AudioManager.STREAM_MUSIC
            )
            // High quality stereo audio capture options (matching Web's musicHighQualityStereo)
            val audioTrackCaptureDefaults = LocalAudioTrackOptions(
                noiseSuppression = true,
                echoCancellation = true,
                autoGainControl = true
            )

            // High quality stereo audio publish options (256kbps like Web's musicHighQualityStereo)
            val audioTrackPublishDefaults = AudioTrackPublishDefaults(
                audioBitrate = 256000,  // 256kbps stereo high quality (same as Web's musicHighQualityStereo)
                dtx = false,  // Disable DTX for music quality (continuous transmission)
                red = true    // Enable redundant audio data for reliability
            )

            // High quality screen share options (1080p @ 30fps, 4Mbps)
            val screenShareCaptureDefaults = LocalVideoTrackOptions(
                captureParams = ScreenSharePresets.H1080_FPS30.capture
            )
            val screenSharePublishDefaults = VideoTrackPublishDefaults(
                videoEncoding = ScreenSharePresets.H1080_FPS30.encoding,
                simulcast = false  // Disable simulcast for screen share to maintain quality
            )

            val roomOptions = RoomOptions(
                audioTrackCaptureDefaults = audioTrackCaptureDefaults,
                audioTrackPublishDefaults = audioTrackPublishDefaults,
                screenShareTrackCaptureDefaults = screenShareCaptureDefaults,
                screenShareTrackPublishDefaults = screenSharePublishDefaults
            )

            val newRoom = LiveKit.create(
                appContext = context,
                options = roomOptions,
                overrides = LiveKitOverrides(
                    audioOptions = AudioOptions(
                        audioOutputType = musicAudioType,
                        audioProcessorOptions = AudioProcessorOptions(
                            renderPreBypass = true
                        )
                    )
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

            // Publish local audio track with high quality settings
            val localParticipant = newRoom.localParticipant
            localParticipant.setMicrophoneEnabled(true)

            _connectionState.value = ConnectionState.CONNECTED
            
            // Update available audio devices after connection
            refreshAudioDevices()
            
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
        _availableDevices.value = emptyList()
        _selectedDevice.value = null
        participantVolumes.clear()
        _isScreenSharing.value = false
        _remoteScreenShares.value = emptyMap()
        mediaProjectionPermissionData = null

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
        // Apply deafen state to all remote participants (respecting individual volume settings)
        room?.remoteParticipants?.values?.forEach { participant ->
            val identity = participant.identity?.value ?: return@forEach
            val savedVolume = participantVolumes[identity] ?: 1.0f
            val effectiveVolume = if (deafened) 0.0 else savedVolume.toDouble()
            participant.audioTrackPublications.forEach { (_, track) ->
                if (track is RemoteAudioTrack) {
                    try {
                        track.setVolume(effectiveVolume)
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

    /**
     * Set volume for a specific participant (0.0-2.0, where 1.0=100%, 2.0=200%)
     */
    fun setParticipantVolume(identity: String, volume: Float) {
        val clampedVolume = volume.coerceIn(0f, 2f)
        participantVolumes[identity] = clampedVolume
        applyVolumeToParticipant(identity, clampedVolume)
        updateParticipants()
        Log.d(TAG, "Set volume for $identity to ${(clampedVolume * 100).toInt()}%")
    }

    /**
     * Get volume for a specific participant
     */
    fun getParticipantVolume(identity: String): Float {
        return participantVolumes[identity] ?: 1.0f
    }

    private fun applyVolumeToParticipant(identity: String, volume: Float) {
        // Apply volume to remote participant's audio track
        val effectiveVolume = if (_isDeafened.value) 0.0 else volume.toDouble()
        room?.remoteParticipants?.values?.find { it.identity?.value == identity }?.let { participant ->
            participant.audioTrackPublications.forEach { (_, track) ->
                if (track is RemoteAudioTrack) {
                    try {
                        track.setVolume(effectiveVolume)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to set volume for $identity", e)
                    }
                }
            }
        }
    }

    /**
     * Select a specific audio device by its ID
     */
    fun selectAudioDevice(deviceId: String) {
        audioHandler?.let { handler ->
            val device = handler.availableAudioDevices.find { 
                getDeviceId(it) == deviceId 
            }
            device?.let { 
                handler.selectDevice(it)
                Log.d(TAG, "Selected audio device: ${it.name}")
                // Refresh device list after selection
                refreshAudioDevices()
            }
        }
    }

    /**
     * Refresh available audio devices list
     */
    fun refreshAudioDevices() {
        audioHandler?.let { handler ->
            updateAvailableDevices(handler.availableAudioDevices, handler.selectedAudioDevice)
        }
    }

    private fun updateAvailableDevices(devices: List<AudioDevice>, selected: AudioDevice?) {
        val deviceInfoList = devices.map { device ->
            val type = when (device) {
                is AudioDevice.Speakerphone -> AudioDeviceType.SPEAKERPHONE
                is AudioDevice.Earpiece -> AudioDeviceType.EARPIECE
                is AudioDevice.WiredHeadset -> AudioDeviceType.WIRED_HEADSET
                is AudioDevice.BluetoothHeadset -> AudioDeviceType.BLUETOOTH
                else -> AudioDeviceType.SPEAKERPHONE
            }
            AudioDeviceInfo(
                id = getDeviceId(device),
                name = device.name,
                type = type,
                isSelected = device == selected
            )
        }
        _availableDevices.value = deviceInfoList
        _selectedDevice.value = deviceInfoList.find { it.isSelected }

        // Update speaker state based on selected device
        selected?.let { sel ->
            _isSpeakerOn.value = sel is AudioDevice.Speakerphone
        }

        Log.d(TAG, "Audio devices updated: ${deviceInfoList.size} devices, selected: ${selected?.name}")
    }

    private fun getDeviceId(device: AudioDevice): String {
        return when (device) {
            is AudioDevice.Speakerphone -> "speakerphone"
            is AudioDevice.Earpiece -> "earpiece"
            is AudioDevice.WiredHeadset -> "wired_headset"
            is AudioDevice.BluetoothHeadset -> "bluetooth_${device.name}"
            else -> device.name
        }
    }

    private suspend fun collectRoomEvents(room: Room) {
        room.events.collect { event ->
            when (event) {
                is RoomEvent.Connected -> {
                    _connectionState.value = ConnectionState.CONNECTED
                    updateParticipants()
                    // Check existing remote screen shares (for late joiners)
                    checkExistingScreenShares()
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
                    // Check for remote screen share
                    val track = event.track
                    val participant = event.participant
                    if (track is RemoteVideoTrack && 
                        event.publication.source == Track.Source.SCREEN_SHARE &&
                        participant is RemoteParticipant) {
                        val identity = participant.identity?.value ?: return@collect
                        val name = participant.name ?: identity
                        val newMap = _remoteScreenShares.value.toMutableMap()
                        newMap[identity] = ScreenShareInfo(identity, name, track)
                        _remoteScreenShares.value = newMap
                        Log.d(TAG, "Remote screen share started: $name")
                    }
                }
                is RoomEvent.TrackUnsubscribed -> {
                    // Check for remote screen share removal by matching track reference
                    val track = event.track
                    if (track is RemoteVideoTrack) {
                        val entryToRemove = _remoteScreenShares.value.entries.find { 
                            it.value.videoTrack == track 
                        }
                        if (entryToRemove != null) {
                            val newMap = _remoteScreenShares.value.toMutableMap()
                            newMap.remove(entryToRemove.key)
                            _remoteScreenShares.value = newMap
                            Log.d(TAG, "Remote screen share stopped: ${entryToRemove.value.participantName}")
                        }
                    }
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

    /**
     * Check for existing screen shares from remote participants.
     * Called after joining to catch screen shares that started before we joined.
     */
    private fun checkExistingScreenShares() {
        val currentRoom = room ?: return
        val newMap = _remoteScreenShares.value.toMutableMap()
        
        currentRoom.remoteParticipants.values.forEach { participant ->
            participant.videoTrackPublications.forEach { (publication, track) ->
                if (publication.source == Track.Source.SCREEN_SHARE && track is RemoteVideoTrack) {
                    val identity = participant.identity?.value ?: return@forEach
                    val name = participant.name ?: identity
                    newMap[identity] = ScreenShareInfo(identity, name, track)
                    Log.d(TAG, "Found existing screen share from: $name")
                }
            }
        }
        
        _remoteScreenShares.value = newMap
    }

    private fun updateParticipants() {
        val currentRoom = room ?: return
        val list = mutableListOf<ParticipantInfo>()

        // Add local participant
        currentRoom.localParticipant.let { local ->
            list.add(participantToInfo(local, isLocal = true))
        }

        // Add remote participants
        currentRoom.remoteParticipants.values.forEach { remote ->
            list.add(participantToInfo(remote, isLocal = false))
        }

        _participants.value = list
    }

    private fun updateSpeakingState(speakers: List<Participant>) {
        val speakingIds = speakers.map { it.identity?.value }.toSet()
        _participants.value = _participants.value.map { info ->
            info.copy(isSpeaking = info.identity in speakingIds)
        }
    }

    private fun participantToInfo(participant: Participant, isLocal: Boolean): ParticipantInfo {
        val identity = participant.identity?.value ?: ""
        val audioPublication = participant.audioTrackPublications.firstOrNull()?.first
        val isMuted = audioPublication?.muted ?: false
        val volume = if (isLocal) 1.0f else (participantVolumes[identity] ?: 1.0f)

        return ParticipantInfo(
            identity = identity,
            name = participant.name ?: identity.ifEmpty { "Unknown" },
            isMuted = isMuted,
            isSpeaking = participant.isSpeaking,
            audioLevel = participant.audioLevel,
            volume = volume,
            isLocal = isLocal
        )
    }

    fun isConnected(): Boolean = _connectionState.value == ConnectionState.CONNECTED

    /**
     * Store MediaProjection permission data for screen sharing.
     * Call this after user grants MediaProjection permission.
     */
    fun setMediaProjectionPermissionData(data: Intent?) {
        mediaProjectionPermissionData = data
    }

    /**
     * Enable or disable screen sharing.
     * Requires MediaProjection permission data to be set first via setMediaProjectionPermissionData().
     */
    suspend fun setScreenShareEnabled(enabled: Boolean): Result<Unit> {
        val currentRoom = room ?: return Result.failure(IllegalStateException("Not connected to room"))
        
        return try {
            if (enabled) {
                val permissionData = mediaProjectionPermissionData
                    ?: return Result.failure(IllegalStateException("MediaProjection permission not granted"))
                
                val screenCaptureParams = ScreenCaptureParams(
                    mediaProjectionPermissionResultData = permissionData
                )
                currentRoom.localParticipant.setScreenShareEnabled(true, screenCaptureParams)
                _isScreenSharing.value = true
                Log.d(TAG, "Screen sharing enabled")
            } else {
                currentRoom.localParticipant.setScreenShareEnabled(false)
                _isScreenSharing.value = false
                Log.d(TAG, "Screen sharing disabled")
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle screen share", e)
            _isScreenSharing.value = false
            Result.failure(e)
        }
    }

    /**
     * Check if MediaProjection permission is available.
     */
    fun hasMediaProjectionPermission(): Boolean = mediaProjectionPermissionData != null
}
