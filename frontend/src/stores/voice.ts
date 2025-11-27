import { defineStore } from 'pinia'
import { ref, shallowRef } from 'vue'
import { Room, RoomEvent, Track } from 'livekit-client'
import type { Channel } from '../types'
import { useAuthStore } from './auth'

const API_BASE = import.meta.env.VITE_API_BASE || ''

export interface VoiceParticipant {
  id: string
  name: string
  isMuted: boolean
  isSpeaking: boolean
  isLocal: boolean
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
    })

    room.value.remoteParticipants.forEach((p) => {
      list.push({
        id: p.identity,
        name: p.name || p.identity,
        isMuted: !p.isMicrophoneEnabled,
        isSpeaking: p.isSpeaking,
        isLocal: false,
      })
    })

    participants.value = list
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
      })

      room.value.on(RoomEvent.ParticipantConnected, () => updateParticipants())
      room.value.on(RoomEvent.ParticipantDisconnected, () => updateParticipants())
      room.value.on(RoomEvent.TrackMuted, () => updateParticipants())
      room.value.on(RoomEvent.TrackUnmuted, () => updateParticipants())
      room.value.on(RoomEvent.ActiveSpeakersChanged, () => updateParticipants())
      room.value.on(RoomEvent.Disconnected, () => {
        isConnected.value = false
        participants.value = []
        currentVoiceChannel.value = null
      })

      room.value.on(RoomEvent.TrackSubscribed, (track, _pub, _participant) => {
        if (track.kind === Track.Kind.Audio) {
          const audioElement = track.attach()
          audioElement.dataset.livekitAudio = 'true'
          document.body.appendChild(audioElement)
        }
      })

      room.value.on(RoomEvent.TrackUnsubscribed, (track) => {
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
    if (room.value) {
      room.value.disconnect()
      room.value = null
    }
    isConnected.value = false
    participants.value = []
    currentVoiceChannel.value = null
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
  }
})
