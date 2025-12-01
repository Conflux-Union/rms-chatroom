import { defineStore } from 'pinia'
import { ref, shallowRef } from 'vue'
import {
  Room,
  RoomEvent,
  Track,
  RemoteParticipant,
  AudioPresets,
  RemoteTrackPublication,
  LocalTrackPublication,
  ScreenSharePresets,
} from 'livekit-client'
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

export interface ScreenShareInfo {
  participantId: string
  participantName: string
  track: RemoteTrackPublication
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

  // Screen share state
  const isScreenSharing = ref(false)
  const localScreenShareTrack = shallowRef<LocalTrackPublication | null>(null)
  const remoteScreenShares = ref<Map<string, ScreenShareInfo>>(new Map())
  
  // Screen share lock state (from server)
  const screenShareLocked = ref(false)
  const screenSharerId = ref<string | null>(null)
  const screenSharerName = ref<string | null>(null)

  // Server-side mute state cache (from API)
  const serverMuteState = ref<Map<string, boolean>>(new Map())
  let syncInterval: ReturnType<typeof setInterval> | null = null

  function ensureAudioContext(): AudioContext {
    if (!audioContext.value) {
      audioContext.value = new (window.AudioContext || (window as any).webkitAudioContext)()
    }
    return audioContext.value
  }

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

      room.value.on(RoomEvent.TrackSubscribed, async (track, pub, participant) => {
        if (participant instanceof RemoteParticipant) {
          // Handle audio tracks
          if (track.kind === Track.Kind.Audio && pub.source === Track.Source.Microphone) {
            const audioElement = track.attach()
            audioElement.dataset.livekitAudio = 'true'
            audioElement.dataset.participantId = participant.identity
            
            const savedVolume = userVolumes.value.get(participant.identity) ?? 100
            
            if (isIOS()) {
              // iOS: Control the volume of each user with Web Audio API
              const ctx = ensureAudioContext()
              const sourceNode = ctx.createMediaElementSource(audioElement)
              const gainNode = ctx.createGain()
              const initialGain = Math.max(0, Math.min(3, savedVolume / 100))
              gainNode.gain.value = initialGain
              sourceNode.connect(gainNode).connect(ctx.destination)
              audioElement.volume = 0
              audioElement.muted = false

              participantAudioMap.set(participant.identity, {
                audioElement,
                sourceNode,
                gainNode,
              })
            } else {
              // Non-iOS: use native audioElement.volume
              audioElement.volume = Math.min(savedVolume / 100, 1)
              participantAudioMap.set(participant.identity, { audioElement })
            }
            
            if (selectedAudioOutput.value) {
              const el = audioElement as HTMLAudioElement & { setSinkId?: (id: string) => Promise<void> }
              if (el.setSinkId) {
                try { await el.setSinkId(selectedAudioOutput.value) } catch { /* Ignore */ }
              }
            }
            document.body.appendChild(audioElement)
          }
          // Handle screen share tracks
          else if (track.kind === Track.Kind.Video && pub.source === Track.Source.ScreenShare) {
            const newMap = new Map(remoteScreenShares.value)
            newMap.set(participant.identity, {
              participantId: participant.identity,
              participantName: participant.name || participant.identity,
              track: pub as RemoteTrackPublication,
            })
            remoteScreenShares.value = newMap
          }
        }
      })

      room.value.on(RoomEvent.TrackUnsubscribed, (track, pub, participant) => {
        if (participant instanceof RemoteParticipant) {
          // Handle audio track cleanup
          if (pub.source === Track.Source.Microphone) {
            const info = participantAudioMap.get(participant.identity)
            if (info?.gainNode) {
              info.gainNode.disconnect()
            }
            if (info?.sourceNode) {
              info.sourceNode.disconnect()
            }
            participantAudioMap.delete(participant.identity)
          }
          // Handle screen share cleanup
          else if (pub.source === Track.Source.ScreenShare) {
            const newMap = new Map(remoteScreenShares.value)
            newMap.delete(participant.identity)
            remoteScreenShares.value = newMap
          }
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

      // Check existing remote participants for screen shares (for late joiners)
      room.value.remoteParticipants.forEach((participant) => {
        participant.trackPublications.forEach((pub) => {
          if (pub.source === Track.Source.ScreenShare && pub.track) {
            const newMap = new Map(remoteScreenShares.value)
            newMap.set(participant.identity, {
              participantId: participant.identity,
              participantName: participant.name || participant.identity,
              track: pub as RemoteTrackPublication,
            })
            remoteScreenShares.value = newMap
          }
        })
      })

      // Fetch host mode and screen share status after joining
      await fetchHostModeStatus()
      await fetchScreenShareStatus()

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
    isScreenSharing.value = false
    localScreenShareTrack.value = null
    remoteScreenShares.value = new Map()
    screenShareLocked.value = false
    screenSharerId.value = null
    screenSharerName.value = null
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
         // --- iOS：用 Web Audio 的 gain 控制 ---
        if (participantAudio.gainNode) {
          const gain = Math.max(0, Math.min(3, clampedVolume / 100))
          participantAudio.gainNode.gain.value = gain
        }
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

  /**
   * Fetch screen share lock status from server.
   */
  async function fetchScreenShareStatus(): Promise<void> {
    if (!currentVoiceChannel.value) return

    const auth = useAuthStore()
    try {
      const response = await fetch(
        `${API_BASE}/api/voice/${currentVoiceChannel.value.id}/screen-share-status`,
        { headers: { Authorization: `Bearer ${auth.token}` } }
      )
      if (response.ok) {
        const data = await response.json()
        screenShareLocked.value = data.locked
        screenSharerId.value = data.sharer_id
        screenSharerName.value = data.sharer_name
      }
    } catch (e) {
      console.error('Failed to fetch screen share status:', e)
    }
  }

  /**
   * Attempt to acquire screen share lock from server.
   */
  async function lockScreenShare(): Promise<{ success: boolean; sharerName: string | null }> {
    if (!currentVoiceChannel.value) return { success: false, sharerName: null }

    const auth = useAuthStore()
    try {
      const response = await fetch(
        `${API_BASE}/api/voice/${currentVoiceChannel.value.id}/screen-share/lock`,
        {
          method: 'POST',
          headers: { Authorization: `Bearer ${auth.token}` },
        }
      )
      if (response.ok) {
        const data = await response.json()
        screenShareLocked.value = data.success || data.sharer_id !== null
        screenSharerId.value = data.sharer_id
        screenSharerName.value = data.sharer_name
        return { success: data.success, sharerName: data.sharer_name }
      }
      return { success: false, sharerName: null }
    } catch (e) {
      console.error('Failed to lock screen share:', e)
      return { success: false, sharerName: null }
    }
  }

  /**
   * Release screen share lock on server.
   */
  async function unlockScreenShare(): Promise<void> {
    if (!currentVoiceChannel.value) return

    const auth = useAuthStore()
    try {
      await fetch(
        `${API_BASE}/api/voice/${currentVoiceChannel.value.id}/screen-share/unlock`,
        {
          method: 'POST',
          headers: { Authorization: `Bearer ${auth.token}` },
        }
      )
      screenShareLocked.value = false
      screenSharerId.value = null
      screenSharerName.value = null
    } catch (e) {
      console.error('Failed to unlock screen share:', e)
    }
  }

  /**
   * Toggle screen sharing on/off.
   */
  async function toggleScreenShare(): Promise<boolean> {
    if (!room.value || !isConnected.value) return false

    try {
      if (isScreenSharing.value) {
        // Stop screen sharing: first stop track, then release lock
        await room.value.localParticipant.setScreenShareEnabled(false)
        isScreenSharing.value = false
        localScreenShareTrack.value = null
        await unlockScreenShare()
      } else {
        // Start screen sharing: first acquire lock, then start
        const lockResult = await lockScreenShare()
        if (!lockResult.success) {
          error.value = `${lockResult.sharerName || '其他用户'} 正在共享屏幕`
          return false
        }
        
        // Lock acquired, start screen sharing with AV1 codec
        await room.value.localParticipant.setScreenShareEnabled(true, {
          resolution: ScreenSharePresets.h1080fps30.resolution,
          contentHint: 'motion',
        }, {
          videoCodec: 'av1',
          videoEncoding: {
            maxBitrate: 2_000_000,
            maxFramerate: 30,
            priority: 'high',
          },
          degradationPreference: 'maintain-framerate',
        })
        isScreenSharing.value = true
        // Find the screen share track publication
        const screenTrack = room.value.localParticipant.getTrackPublication(Track.Source.ScreenShare)
        if (screenTrack) {
          localScreenShareTrack.value = screenTrack
        }
      }
      return true
    } catch (e) {
      console.error('Failed to toggle screen share:', e)
      // User may have cancelled the screen share picker, release lock
      if (!isScreenSharing.value) {
        await unlockScreenShare()
      }
      isScreenSharing.value = false
      localScreenShareTrack.value = null
      return false
    }
  }

  /**
   * Attach a screen share track to a container element.
   */
  function attachScreenShare(participantId: string, container: HTMLElement): void {
    const screenShare = remoteScreenShares.value.get(participantId)
    if (screenShare?.track?.videoTrack) {
      const videoElement = screenShare.track.videoTrack.attach()
      videoElement.style.width = '100%'
      videoElement.style.height = '100%'
      videoElement.style.objectFit = 'contain'
      container.innerHTML = ''
      container.appendChild(videoElement)
    }
  }

  /**
   * Attach local screen share track to a container element.
   */
  function attachLocalScreenShare(container: HTMLElement): void {
    if (localScreenShareTrack.value?.videoTrack) {
      const videoElement = localScreenShareTrack.value.videoTrack.attach()
      videoElement.style.width = '100%'
      videoElement.style.height = '100%'
      videoElement.style.objectFit = 'contain'
      container.innerHTML = ''
      container.appendChild(videoElement)
    }
  }

  /**
   * Detach screen share from a container.
   */
  function detachScreenShare(container: HTMLElement): void {
    container.innerHTML = ''
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
    isScreenSharing,
    remoteScreenShares,
    screenShareLocked,
    screenSharerId,
    screenSharerName,
    toggleScreenShare,
    fetchScreenShareStatus,
    attachScreenShare,
    attachLocalScreenShare,
    detachScreenShare,
  }
})
