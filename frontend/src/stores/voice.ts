import { defineStore } from 'pinia'
import { ref, shallowRef } from 'vue'
import { Room, RoomEvent, Track, RemoteParticipant, AudioPresets } from 'livekit-client'
import type { Channel } from '../types'
import { useAuthStore } from './auth'

const API_BASE = import.meta.env.VITE_API_BASE || ''

const STORAGE_KEY_INPUT = 'rms-voice-input-device'
const STORAGE_KEY_OUTPUT = 'rms-voice-output-device'

// Detect iOS devices (iPad, iPhone, iPod, or iPad with desktop mode)
function isIOS(): boolean {
  return /iPad|iPhone|iPod/.test(navigator.userAgent) ||
    (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1)
}

export interface VoiceParticipant {
  id: string
  name: string
  isMuted: boolean
  isSpeaking: boolean
  isLocal: boolean
  volume: number
}

export interface AudioDevice {
  deviceId: string
  label: string
}

interface ParticipantAudio {
  audioElement: HTMLAudioElement
  gainNode?: GainNode
  sourceNode?: MediaElementAudioSourceNode
}

export const useVoiceStore = defineStore('voice', () => {
  const room = shallowRef<Room | null>(null)
  const isConnected = ref(false)
  const isConnecting = ref(false)
  const isMuted = ref(false)
  const isDeafened = ref(false)
  const participants = ref<VoiceParticipant[]>([])
  const error = ref<string | null>(null)
  const currentVoiceChannel = ref<Channel | null>(null)

  // Volume control state
  const audioContext = shallowRef<AudioContext | null>(null)
  const participantAudioMap = new Map<string, ParticipantAudio>()
  const userVolumes = ref<Map<string, number>>(new Map())
  const volumeWarningAcknowledged = ref<Set<string>>(new Set())

  // Audio device state
  const audioInputDevices = ref<AudioDevice[]>([])
  const audioOutputDevices = ref<AudioDevice[]>([])
  const selectedAudioInput = ref<string>(localStorage.getItem(STORAGE_KEY_INPUT) || '')
  const selectedAudioOutput = ref<string>(localStorage.getItem(STORAGE_KEY_OUTPUT) || '')

  // Host mode state
  const hostModeEnabled = ref(false)
  const hostModeHostId = ref<string | null>(null)
  const hostModeHostName = ref<string | null>(null)

  // Server-side mute state cache (from API)
  const serverMuteState = ref<Map<string, boolean>>(new Map())
  let syncInterval: ReturnType<typeof setInterval> | null = null

  function updateParticipants() {
    if (!room.value) {
      participants.value = []
      return
    }

    const list: VoiceParticipant[] = []

    const local = room.value.localParticipant
    list.push({
      id: local.identity,
      name: local.name || local.identity,
      isMuted: !local.isMicrophoneEnabled,
      isSpeaking: local.isSpeaking,
      isLocal: true,
      volume: 100,
    })

    room.value.remoteParticipants.forEach((p) => {
      // Prefer server-side mute state, fallback to client state
      const serverMuted = serverMuteState.value.get(p.identity)
      const clientMuted = !p.isMicrophoneEnabled
      const isMuted = serverMuted !== undefined ? serverMuted : clientMuted

      list.push({
        id: p.identity,
        name: p.name || p.identity,
        isMuted,
        isSpeaking: p.isSpeaking,
        isLocal: false,
        volume: userVolumes.value.get(p.identity) ?? 100,
      })
    })

    participants.value = list
  }

  /**
   * Fetch mute state from server and sync with local participants.
   */
  async function syncParticipantsFromServer(): Promise<void> {
    if (!currentVoiceChannel.value || !isConnected.value) return

    const auth = useAuthStore()
    try {
      const response = await fetch(
        `${API_BASE}/api/voice/${currentVoiceChannel.value.id}/users`,
        { headers: { Authorization: `Bearer ${auth.token}` } }
      )
      if (response.ok) {
        const users: Array<{ id: string; is_muted: boolean; is_host: boolean }> = await response.json()
        const newMuteState = new Map<string, boolean>()
        for (const user of users) {
          newMuteState.set(user.id, user.is_muted)
        }
        serverMuteState.value = newMuteState
        updateParticipants()
      }
    } catch (e) {
      console.error('Failed to sync participants from server:', e)
    }
  }

  function startSyncInterval() {
    stopSyncInterval()
    syncParticipantsFromServer()
    syncInterval = setInterval(() => {
      syncParticipantsFromServer()
    }, 2000)
  }

  function stopSyncInterval() {
    if (syncInterval) {
      clearInterval(syncInterval)
      syncInterval = null
    }
  }

  async function enumerateDevices(): Promise<void> {
    try {
      // Request permission first to get device labels
      await navigator.mediaDevices.getUserMedia({ audio: true })
        .then(stream => stream.getTracks().forEach(t => t.stop()))
        .catch(() => { /* Ignore permission denial */ })

      const devices = await navigator.mediaDevices.enumerateDevices()
      
      audioInputDevices.value = devices
        .filter(d => d.kind === 'audioinput')
        .map(d => ({
          deviceId: d.deviceId,
          label: d.label || `Microphone ${d.deviceId.slice(0, 8)}`
        }))

      audioOutputDevices.value = devices
        .filter(d => d.kind === 'audiooutput')
        .map(d => ({
          deviceId: d.deviceId,
          label: d.label || `Speaker ${d.deviceId.slice(0, 8)}`
        }))

      // Validate saved selections still exist
      if (selectedAudioInput.value && !audioInputDevices.value.find(d => d.deviceId === selectedAudioInput.value)) {
        selectedAudioInput.value = ''
        localStorage.removeItem(STORAGE_KEY_INPUT)
      }
      if (selectedAudioOutput.value && !audioOutputDevices.value.find(d => d.deviceId === selectedAudioOutput.value)) {
        selectedAudioOutput.value = ''
        localStorage.removeItem(STORAGE_KEY_OUTPUT)
      }
    } catch (e) {
      console.error('Failed to enumerate devices:', e)
    }
  }

  async function setAudioInputDevice(deviceId: string): Promise<boolean> {
    selectedAudioInput.value = deviceId
    if (deviceId) {
      localStorage.setItem(STORAGE_KEY_INPUT, deviceId)
    } else {
      localStorage.removeItem(STORAGE_KEY_INPUT)
    }

    // If connected, switch device immediately
    if (room.value && isConnected.value) {
      try {
        await room.value.switchActiveDevice('audioinput', deviceId || 'default')
        return true
      } catch (e) {
        console.error('Failed to switch audio input device:', e)
        return false
      }
    }
    return true
  }

  async function setAudioOutputDevice(deviceId: string): Promise<boolean> {
    selectedAudioOutput.value = deviceId
    if (deviceId) {
      localStorage.setItem(STORAGE_KEY_OUTPUT, deviceId)
    } else {
      localStorage.removeItem(STORAGE_KEY_OUTPUT)
    }

    // Apply to all audio elements
    const audioElements = document.querySelectorAll('audio[data-livekit-audio="true"]')
    const targetId = deviceId || 'default'
    
    for (const el of audioElements) {
      const audioEl = el as HTMLAudioElement & { setSinkId?: (id: string) => Promise<void> }
      if (audioEl.setSinkId) {
        try {
          await audioEl.setSinkId(targetId)
        } catch (e) {
          console.error('Failed to set audio output device:', e)
          return false
        }
      }
    }
    return true
  }

  async function joinVoice(channel: Channel): Promise<boolean> {
    if (isConnecting.value || isConnected.value) return false

    isConnecting.value = true
    error.value = null

    try {
      const auth = useAuthStore()
      const response = await fetch(`${API_BASE}/api/voice/${channel.id}/token`, {
        headers: { Authorization: `Bearer ${auth.token}` },
      })

      if (!response.ok) {
        const err = await response.json()
        throw new Error(err.detail || 'Failed to get voice token')
      }

      const { token, url } = await response.json()

      room.value = new Room({
        adaptiveStream: true,
        dynacast: true,
        audioCaptureDefaults: {
          autoGainControl: true,
          noiseSuppression: true,
          echoCancellation: true,
          deviceId: selectedAudioInput.value || undefined,
        },
        publishDefaults: {
          audioPreset: AudioPresets.musicHighQualityStereo,
        },
      })

      room.value.on(RoomEvent.ParticipantConnected, async (participant) => {
        updateParticipants()
        // Host mode: auto-mute new participants (only host triggers this)
        const auth = useAuthStore()
        if (hostModeEnabled.value && hostModeHostId.value === String(auth.user?.id)) {
          await muteParticipant(participant.identity, true)
        }
      })
      room.value.on(RoomEvent.ParticipantDisconnected, (participant) => {
        updateParticipants()
        // If host leaves, clear host mode state locally
        if (hostModeEnabled.value && participant.identity === hostModeHostId.value) {
          hostModeEnabled.value = false
          hostModeHostId.value = null
          hostModeHostName.value = null
        }
      })
      room.value.on(RoomEvent.TrackMuted, (publication, participant) => {
        // Sync local muted state when server mutes local participant's mic
        if (participant === room.value?.localParticipant && publication.source === Track.Source.Microphone) {
          isMuted.value = true
        }
        updateParticipants()
      })
      room.value.on(RoomEvent.TrackUnmuted, (publication, participant) => {
        // Sync local muted state when server unmutes local participant's mic
        if (participant === room.value?.localParticipant && publication.source === Track.Source.Microphone) {
          isMuted.value = false
        }
        updateParticipants()
      })
      room.value.on(RoomEvent.ActiveSpeakersChanged, () => updateParticipants())
      room.value.on(RoomEvent.Disconnected, () => {
        isConnected.value = false
        participants.value = []
        currentVoiceChannel.value = null
      })

      room.value.on(RoomEvent.TrackSubscribed, async (track, _pub, participant) => {
        if (track.kind === Track.Kind.Audio && participant instanceof RemoteParticipant) {
          const audioElement = track.attach()
          audioElement.dataset.livekitAudio = 'true'
          audioElement.dataset.participantId = participant.identity
          
          const savedVolume = userVolumes.value.get(participant.identity) ?? 100
          
          if (isIOS()) {
            // iOS: only mute/unmute is supported (volume property is ignored)
            audioElement.muted = savedVolume === 0
          } else {
            // Non-iOS: use native audioElement.volume
            audioElement.volume = Math.min(savedVolume / 100, 1)
          }
          
          participantAudioMap.set(participant.identity, { audioElement })
          
          // Apply output device
          if (selectedAudioOutput.value) {
            const el = audioElement as HTMLAudioElement & { setSinkId?: (id: string) => Promise<void> }
            if (el.setSinkId) {
              try { await el.setSinkId(selectedAudioOutput.value) } catch { /* Ignore */ }
            }
          }
          document.body.appendChild(audioElement)
        }
      })

      room.value.on(RoomEvent.TrackUnsubscribed, (track, _pub, participant) => {
        if (participant instanceof RemoteParticipant) {
          participantAudioMap.delete(participant.identity)
        }
        track.detach().forEach((el) => el.remove())
      })

      await room.value.connect(url, token)
      await room.value.localParticipant.setMicrophoneEnabled(true)

      isMuted.value = false
      isDeafened.value = false
      isConnected.value = true
      currentVoiceChannel.value = channel
      updateParticipants()

      // Fetch host mode status after joining
      await fetchHostModeStatus()

      // Start periodic sync from server for accurate mute state
      startSyncInterval()

      return true
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to connect'
      console.error('Voice connect error:', e)
      return false
    } finally {
      isConnecting.value = false
    }
  }

  function disconnect() {
    stopSyncInterval()
    participantAudioMap.clear()
    serverMuteState.value.clear()
    
    if (audioContext.value) {
      audioContext.value.close()
      audioContext.value = null
    }

    if (room.value) {
      room.value.disconnect()
      room.value = null
    }
    isConnected.value = false
    participants.value = []
    currentVoiceChannel.value = null
    userVolumes.value.clear()
    volumeWarningAcknowledged.value.clear()
    hostModeEnabled.value = false
    hostModeHostId.value = null
    hostModeHostName.value = null
  }

  async function toggleMute(): Promise<boolean> {
    if (!room.value) return isMuted.value

    const newMuted = !isMuted.value
    await room.value.localParticipant.setMicrophoneEnabled(!newMuted)
    isMuted.value = newMuted
    updateParticipants()
    return isMuted.value
  }

  function toggleDeafen() {
    isDeafened.value = !isDeafened.value
    const audioElements = document.querySelectorAll('audio[data-livekit-audio="true"]')
    audioElements.forEach((el) => {
      ;(el as HTMLAudioElement).muted = isDeafened.value
    })
  }

  /**
   * Set volume for a specific participant.
   * @param participantId - The participant's identity
   * @param volume - Volume level (0-300)
   * @param bypassWarning - If true, skip the warning check for >100%
   * @returns Object with success status and whether warning should be shown
   */
  function setUserVolume(
    participantId: string,
    volume: number,
    bypassWarning = false
  ): { success: boolean; showWarning: boolean } {
    const clampedVolume = Math.max(0, Math.min(300, volume))
    const currentVolume = userVolumes.value.get(participantId) ?? 100

    // Safety check: crossing 100% threshold requires warning acknowledgement
    if (currentVolume <= 100 && clampedVolume > 100 && !bypassWarning) {
      if (!volumeWarningAcknowledged.value.has(participantId)) {
        return { success: false, showWarning: true }
      }
    }

    // Mark as warned if going above 100%
    if (clampedVolume > 100) {
      volumeWarningAcknowledged.value.add(participantId)
    }

    // Apply volume
    const participantAudio = participantAudioMap.get(participantId)
    if (participantAudio?.audioElement) {
      if (isIOS()) {
        // iOS: only mute/unmute is supported (volume property is ignored by iOS)
        participantAudio.audioElement.muted = clampedVolume === 0
      } else {
        // Non-iOS: use native volume (max 100%)
        participantAudio.audioElement.volume = Math.min(clampedVolume / 100, 1)
      }
    }

    userVolumes.value.set(participantId, clampedVolume)
    updateParticipants()
    return { success: true, showWarning: false }
  }

  function acknowledgeVolumeWarning(participantId: string) {
    volumeWarningAcknowledged.value.add(participantId)
  }

  function isVolumeWarningAcknowledged(participantId: string): boolean {
    return volumeWarningAcknowledged.value.has(participantId)
  }

  /**
   * Admin: Mute a participant's microphone via server API.
   */
  async function muteParticipant(userId: string, muted = true): Promise<boolean> {
    if (!currentVoiceChannel.value) return false

    const auth = useAuthStore()
    try {
      const response = await fetch(
        `${API_BASE}/api/voice/${currentVoiceChannel.value.id}/mute/${userId}`,
        {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            Authorization: `Bearer ${auth.token}`,
          },
          body: JSON.stringify({ muted }),
        }
      )
      return response.ok
    } catch (e) {
      console.error('Failed to mute participant:', e)
      return false
    }
  }

  /**
   * Admin: Kick a participant from voice channel via server API.
   */
  async function kickParticipant(userId: string): Promise<boolean> {
    if (!currentVoiceChannel.value) return false

    const auth = useAuthStore()
    try {
      const response = await fetch(
        `${API_BASE}/api/voice/${currentVoiceChannel.value.id}/kick/${userId}`,
        {
          method: 'POST',
          headers: {
            Authorization: `Bearer ${auth.token}`,
          },
        }
      )
      return response.ok
    } catch (e) {
      console.error('Failed to kick participant:', e)
      return false
    }
  }

  /**
   * Fetch host mode status from server.
   */
  async function fetchHostModeStatus(): Promise<void> {
    if (!currentVoiceChannel.value) return

    const auth = useAuthStore()
    try {
      const response = await fetch(
        `${API_BASE}/api/voice/${currentVoiceChannel.value.id}/host-mode`,
        { headers: { Authorization: `Bearer ${auth.token}` } }
      )
      if (response.ok) {
        const data = await response.json()
        hostModeEnabled.value = data.enabled
        hostModeHostId.value = data.host_id
        hostModeHostName.value = data.host_name
      }
    } catch (e) {
      console.error('Failed to fetch host mode status:', e)
    }
  }

  /**
   * Admin: Toggle host mode on/off.
   */
  async function toggleHostMode(): Promise<boolean> {
    if (!currentVoiceChannel.value) return false

    const auth = useAuthStore()
    try {
      const response = await fetch(
        `${API_BASE}/api/voice/${currentVoiceChannel.value.id}/host-mode`,
        {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            Authorization: `Bearer ${auth.token}`,
          },
          body: JSON.stringify({ enabled: !hostModeEnabled.value }),
        }
      )
      if (response.ok) {
        const data = await response.json()
        hostModeEnabled.value = data.enabled
        hostModeHostId.value = data.host_id
        hostModeHostName.value = data.host_name
        return true
      }
      return false
    } catch (e) {
      console.error('Failed to toggle host mode:', e)
      return false
    }
  }

  return {
    room,
    isConnected,
    isConnecting,
    isMuted,
    isDeafened,
    participants,
    error,
    currentVoiceChannel,
    audioInputDevices,
    audioOutputDevices,
    selectedAudioInput,
    selectedAudioOutput,
    hostModeEnabled,
    hostModeHostId,
    hostModeHostName,
    joinVoice,
    disconnect,
    toggleMute,
    toggleDeafen,
    setUserVolume,
    acknowledgeVolumeWarning,
    isVolumeWarningAcknowledged,
    enumerateDevices,
    setAudioInputDevice,
    setAudioOutputDevice,
    muteParticipant,
    kickParticipant,
    fetchHostModeStatus,
    toggleHostMode,
  }
})
