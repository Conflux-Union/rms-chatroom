import { defineStore } from 'pinia'
import { ref, shallowRef } from 'vue'
import { Room, RoomEvent, Track, RemoteParticipant, AudioPresets } from 'livekit-client'
import type { Channel } from '../types'
import { useAuthStore } from './auth'

const API_BASE = import.meta.env.VITE_API_BASE || ''

export interface VoiceParticipant {
  id: string
  name: string
  isMuted: boolean
  isSpeaking: boolean
  isLocal: boolean
  volume: number
}

interface ParticipantAudio {
  audioElement: HTMLAudioElement
  gainNode: GainNode
  sourceNode: MediaElementAudioSourceNode
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
      list.push({
        id: p.identity,
        name: p.name || p.identity,
        isMuted: !p.isMicrophoneEnabled,
        isSpeaking: p.isSpeaking,
        isLocal: false,
        volume: userVolumes.value.get(p.identity) ?? 100,
      })
    })

    participants.value = list
  }

  function ensureAudioContext(): AudioContext {
    if (!audioContext.value) {
      audioContext.value = new AudioContext()
    }
    return audioContext.value
  }

  function setupParticipantAudio(participant: RemoteParticipant, audioElement: HTMLAudioElement) {
    const existingAudio = participantAudioMap.get(participant.identity)
    if (existingAudio) {
      return existingAudio
    }

    const ctx = ensureAudioContext()
    const sourceNode = ctx.createMediaElementSource(audioElement)
    const gainNode = ctx.createGain()
    
    sourceNode.connect(gainNode)
    gainNode.connect(ctx.destination)
    
    const volume = userVolumes.value.get(participant.identity) ?? 100
    gainNode.gain.value = volume / 100

    const audioData: ParticipantAudio = { audioElement, gainNode, sourceNode }
    participantAudioMap.set(participant.identity, audioData)
    
    return audioData
  }

  function cleanupParticipantAudio(participantId: string) {
    const audioData = participantAudioMap.get(participantId)
    if (audioData) {
      audioData.sourceNode.disconnect()
      audioData.gainNode.disconnect()
      participantAudioMap.delete(participantId)
    }
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
        },
        publishDefaults: {
          audioPreset: AudioPresets.musicHighQualityStereo,  // 128kbps max quality
        },
      })

      room.value.on(RoomEvent.ParticipantConnected, () => updateParticipants())
      room.value.on(RoomEvent.ParticipantDisconnected, (participant) => {
        cleanupParticipantAudio(participant.identity)
        updateParticipants()
      })
      room.value.on(RoomEvent.TrackMuted, () => updateParticipants())
      room.value.on(RoomEvent.TrackUnmuted, () => updateParticipants())
      room.value.on(RoomEvent.ActiveSpeakersChanged, () => updateParticipants())
      room.value.on(RoomEvent.Disconnected, () => {
        isConnected.value = false
        participants.value = []
        currentVoiceChannel.value = null
      })

      room.value.on(RoomEvent.TrackSubscribed, (track, _pub, participant) => {
        if (track.kind === Track.Kind.Audio && participant instanceof RemoteParticipant) {
          const audioElement = track.attach()
          audioElement.dataset.livekitAudio = 'true'
          audioElement.dataset.participantId = participant.identity
          document.body.appendChild(audioElement)
          setupParticipantAudio(participant, audioElement)
        }
      })

      room.value.on(RoomEvent.TrackUnsubscribed, (track, _pub, participant) => {
        if (track.kind === Track.Kind.Audio) {
          cleanupParticipantAudio(participant.identity)
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
    // Cleanup all audio nodes
    participantAudioMap.forEach((_, id) => cleanupParticipantAudio(id))
    participantAudioMap.clear()
    
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

    // Apply volume to gain node
    const audioData = participantAudioMap.get(participantId)
    if (audioData) {
      audioData.gainNode.gain.value = clampedVolume / 100
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

  return {
    room,
    isConnected,
    isConnecting,
    isMuted,
    isDeafened,
    participants,
    error,
    currentVoiceChannel,
    joinVoice,
    disconnect,
    toggleMute,
    toggleDeafen,
    setUserVolume,
    acknowledgeVolumeWarning,
    isVolumeWarningAcknowledged,
  }
})
