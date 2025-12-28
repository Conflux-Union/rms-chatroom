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
  sourceNode?: AudioNode   // can be MediaStreamSource or MediaElementSource
  volume: number
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

  // iOS Audio Context state
  const audioContextInitialized = ref(false)
  // Track which audio elements have been connected to prevent duplicate connections
  const connectedAudioElements = new WeakSet<HTMLAudioElement>()

  function ensureAudioContext(): AudioContext {
    if (!audioContext.value) {
      audioContext.value = new (window.AudioContext || (window as any).webkitAudioContext)()
      audioContextInitialized.value = true
    }
    return audioContext.value
  }

  // iOS-specific: Resume AudioContext - must be called in user gesture sync stack
  async function resumeAudioContext(): Promise<boolean> {
    if (!audioContext.value) return false
    
    const ctx = audioContext.value
    if (ctx.state === 'suspended') {
      try {
        await ctx.resume()
        console.log(`AudioContext resumed: ${ctx.state}`)
      } catch (e) {
        console.log('Failed to resume AudioContext: ' + e)
        return false
      }
    }
    return ctx.state === 'running'
  }

  // Initialize and activate AudioContext immediately on user interaction (iOS requirement)
  async function activateAudioContext(): Promise<boolean> {
    if (!isIOS()) return true
    const audioElements = document.querySelectorAll('audio[data-livekit-audio="true"]')
    audioElements.forEach((el) => {
      ;(el as HTMLAudioElement).muted = true
      ;(el as HTMLAudioElement).volume = 0.0
    })
    const ctx = ensureAudioContext()
    console.log(`AudioContext state before activation: ${ctx.state}`)
    
    // Must resume synchronously in user gesture handler
    if (ctx.state === 'suspended') {
      try {
        // This MUST be called in the same call stack as user gesture
        await ctx.resume()
        console.log(`AudioContext state after resume: ${ctx.state}`)
      } catch (e) {
        console.log('Failed to activate AudioContext: ' + e)
        return false
      }
    }
    
    return ctx.state === 'running'
  }

  // Connect audio nodes for iOS - handle Web Audio API routing using MediaStream
  function connectAudioNodes(
    participantId: string,
    audioElement: HTMLAudioElement,
    volume: number
  ): boolean {
    if (!isIOS()) return true

    audioElement.volume = 0.0 // Mute native volume
    audioElement.muted = true // Ensure not muted
    const audioElements = document.querySelectorAll('audio[data-livekit-audio="true"]')
    audioElements.forEach((el) => {
      ;(el as HTMLAudioElement).muted = true
    })

    const ctx = ensureAudioContext()

    if (ctx.state !== 'running') {
      console.log(`AudioContext not running (${ctx.state}), cannot connect audio nodes for ${participantId}`)
      return false
    }

    const mediaStream = audioElement.srcObject as MediaStream | null
    if (!mediaStream) {
      console.log(`Audio element for ${participantId} has no srcObject, cannot create MediaStreamSource`)
      return false
    }

    if (connectedAudioElements.has(audioElement)) {
      console.log(`MediaStream for ${participantId} already connected (via its audioElement), skipping`)
      return true
    }

    try {
      const sourceNode = ctx.createMediaStreamSource(mediaStream)
      const gainNode = ctx.createGain()

      // Set initial gain based on volume (0-300% mapped to 0.0-3.0)
      const gain = Math.max(0, Math.min(3, volume / 100))
      gainNode.gain.value = gain

      // Connect nodes: source -> gain -> master
      const master = ensureMasterGain(ctx)
      sourceNode.connect(gainNode)
      gainNode.connect(master)

      connectedAudioElements.add(audioElement)

      participantAudioMap.set(participantId, {
        audioElement,
        sourceNode,
        gainNode,
        volume,
      })

      audioElement.volume = 0.0
      audioElement.muted = true

      console.log(
        `Connected MediaStream audio nodes for ${participantId} with volume ${volume}% (gain: ${gain})`
      )
      return true
    } catch (e) {
      console.log(`Failed to connect MediaStream audio nodes for ${participantId}: ${e}`)
      participantAudioMap.set(participantId, {
        audioElement,
        volume,
      })
      return false
    }
  }


  let masterGain: GainNode | null = null

  function ensureMasterGain(ctx: AudioContext): GainNode {
    if (!masterGain) {
      masterGain = ctx.createGain()
      masterGain.gain.value = 1
    }

    // iOS：put WebAudio output into <audio> for playback
    if (isIOS()) {
      if (!bgDestNode) {
        bgDestNode = ctx.createMediaStreamDestination()
      }

      // Reconnect master gain to bgDestNode
      try { masterGain.disconnect() } catch {}
      masterGain.connect(bgDestNode)

      const el = ensureBackgroundAudioElement()
      if (el.srcObject !== bgDestNode.stream) {
        el.srcObject = bgDestNode.stream
      }
    } else {
      // Non-iOS: connect master gain directly to destination
      try { masterGain.disconnect() } catch {}
      masterGain.connect(ctx.destination)
    }

    return masterGain
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
      console.log('Failed to sync participants from server:' + e)
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
      console.log('Failed to enumerate devices:' + e)
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
        console.log('Failed to switch audio input device:' + e)
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
          console.log('Failed to set audio output device:' + e)
          return false
        }
      }
    }
    return true
  }

  function loopMuteGlobalAudio() {
    setInterval(() => {
      if (isIOS()) {
        const audioElements = document.querySelectorAll('audio[data-livekit-audio="true"]')
        audioElements.forEach((el) => {
          ;(el as HTMLAudioElement).muted = true
          ;(el as HTMLAudioElement).volume = 0.0
        })
      }
    }, 100);
  }
  loopMuteGlobalAudio()

  async function joinVoice(channel: Channel): Promise<boolean> {
    if (isConnecting.value || isConnected.value) return false

    // CRITICAL: Activate AudioContext IMMEDIATELY in user gesture call stack (iOS requirement)
    // This must happen BEFORE any async operations
    let audioActivatedPromise: Promise<boolean> | null = null

    if (isIOS()) {
      // Activate AudioContext
      audioActivatedPromise = activateAudioContext()

      // Play bgaudio
      const el = ensureBackgroundAudioElement()
      const playPromise = el.play()
      if (playPromise && typeof (playPromise as any).catch === 'function') {
        playPromise.catch((e) => {
          console.log('bgAudio play failed:', e)
        })
      }
    } else {
      audioActivatedPromise = Promise.resolve(true)
    }

    // Waiting for async operations
    const audioActivated = await audioActivatedPromise
    if (isIOS() && !audioActivated) {
      console.log('Warning: AudioContext activation failed, volume control may not work')
    }
    
    const audioElements = document.querySelectorAll('audio[data-livekit-audio="true"]')
    audioElements.forEach((el) => {
      ;(el as HTMLAudioElement).muted = true
      ;(el as HTMLAudioElement).volume = 0.0
    })

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
            
            let savedVolume = userVolumes.value.get(participant.identity) ?? 100

            if (participant.identity.includes('music-bot')) {
              console.log(`Music bot detected, setting volume to 10%`)
              savedVolume = 30;
            }

            console.log(`Subscribing to audio track of ${participant.identity}, saved volume: ${savedVolume}%`)
            
            if (isIOS()) {
              // iOS: Use Web Audio API for volume control
              connectAudioNodes(participant.identity, audioElement, savedVolume)
              audioElement.volume = 0.0 // Mute native volume
              audioElement.muted = true // Ensure not muted
              const audioElements = document.querySelectorAll('audio[data-livekit-audio="true"]')
              audioElements.forEach((el) => {
                ;(el as HTMLAudioElement).muted = true
                ;(el as HTMLAudioElement).volume = 0.0
              })
            } else {
              // Non-iOS: use native audioElement.volume
              audioElement.volume = Math.min(savedVolume / 100, 1)
              participantAudioMap.set(participant.identity, { 
                audioElement, 
                volume: savedVolume 
              })
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
          // Handle screen share audio tracks
          else if (track.kind === Track.Kind.Audio && pub.source === Track.Source.ScreenShareAudio) {
            const audioElement = track.attach()
            audioElement.dataset.livekitAudio = 'true'
            audioElement.dataset.participantId = participant.identity
            audioElement.dataset.screenShareAudio = 'true'
            
            if (selectedAudioOutput.value) {
              const el = audioElement as HTMLAudioElement & { setSinkId?: (id: string) => Promise<void> }
              if (el.setSinkId) {
                try { await el.setSinkId(selectedAudioOutput.value) } catch { /* Ignore */ }
              }
            }
            document.body.appendChild(audioElement)
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

      // Resume AudioContext after connection on iOS if needed
      if (isIOS()) {
        await resumeAudioContext()
      }

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
      console.log('Voice connect error:' + e)
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
      audioContextInitialized.value = false
    }

    if (masterGain) {
      masterGain.disconnect()
      masterGain = null
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

  function setGlobalMute(muted: boolean) {
    const audioElements = document.querySelectorAll('audio[data-livekit-audio="true"]')
    // iOS change masterGain 
    if (isIOS()) {
      const ctx = audioContext.value
      if (!ctx) return

      const master = ensureMasterGain(ctx)
      master.gain.value = muted ? 0 : 1
      audioElements.forEach((el) => {
        ;(el as HTMLAudioElement).muted = true
        ;(el as HTMLAudioElement).volume = 0.0
      })
    } else {
      // other platforms mute via audioElement.muted
      audioElements.forEach((el) => {
        ;(el as HTMLAudioElement).muted = muted
      })
    }
  }

  function toggleDeafen() {
    isDeafened.value = !isDeafened.value
    setGlobalMute(isDeafened.value)
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
    if (participantAudio) {
      console.log(`[setUserVolume] Participant: ${participantId}`)
      console.log(`  - Target volume: ${clampedVolume}%`)
      console.log(`  - Is iOS: ${isIOS()}`)
      console.log(`  - Has audioElement: ${!!participantAudio.audioElement}`)
      console.log(`  - Has gainNode: ${!!participantAudio.gainNode}`)
      console.log(`  - Has sourceNode: ${!!participantAudio.sourceNode}`)
      if (isIOS() && participantAudio.gainNode) {
        // iOS: Use Web Audio API gain control
        let gain = 0;

        if (clampedVolume <= 100) {
          gain = Math.pow(Math.max(0, Math.min(clampedVolume, 100)) / 100, 2.6);
        } else {
          gain = clampedVolume / 100;
        }

        // (20*Math.log(clampedVolume+1)/Math.log(10))/(20*Math.log(301)/Math.log(10))*3.0

        const audioElements = document.querySelectorAll('audio[data-livekit-audio="true"]')
        audioElements.forEach((el) => {
          ;(el as HTMLAudioElement).muted = true
        })

        console.log(`  - Current gain value: ${participantAudio.gainNode.gain.value}`)
        console.log(`  - Setting gain to: ${gain}`)

        participantAudio.gainNode.gain.value = gain
        
        // console.log output new gain value
        console.log(`  - New gain value: ${participantAudio.gainNode.gain.value}`)
        
        // check GainNode properties
        console.log(`  - GainNode numberOfInputs: ${participantAudio.gainNode.numberOfInputs}`)
        console.log(`  - GainNode numberOfOutputs: ${participantAudio.gainNode.numberOfOutputs}`)

        // check AudioContext status
        if (audioContext.value) {
          console.log(`  - AudioContext state: ${audioContext.value.state}`)
          console.log(`  - AudioContext sampleRate: ${audioContext.value.sampleRate}`)
        }

        // check audioElement status
        if (participantAudio.audioElement) {
          console.log(`  - Audio element paused: ${participantAudio.audioElement.paused}`)
          console.log(`  - Audio element muted: ${participantAudio.audioElement.muted}`)
          console.log(`  - Audio element volume: ${participantAudio.audioElement.volume}`)
          console.log(`  - Audio element readyState: ${participantAudio.audioElement.readyState}`)
        }
        
        diagnoseAudioRouting(participantId)

      } else if (!isIOS() && participantAudio.audioElement) {
        // Non-iOS: use native volume (max 100%)
        participantAudio.audioElement.volume = Math.pow(Math.max(0, Math.min(clampedVolume, 100)) / 100, 2.6);;
      }
      
      // Update stored volume
      participantAudio.volume = clampedVolume
      participantAudioMap.set(participantId, participantAudio)
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
      console.log('Failed to mute participant:' + e)
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
      console.log('Failed to kick participant:' + e)
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
      console.log('Failed to fetch host mode status:' + e)
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
      console.error('Failed to toggle host mode:' + e)
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
      console.log('Failed to fetch screen share status:' + e)
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
      console.log('Failed to lock screen share:' + e)
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
      console.log('Failed to unlock screen share:' + e)
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
        console.log("Screen share stopped by user")
        await unlockScreenShare()
      } else {
        // Start screen sharing: first acquire lock, then start
        const lockResult = await lockScreenShare()
        if (!lockResult.success) {
          error.value = `${lockResult.sharerName || '其他用户'} 正在共享屏幕`
          console.log("Failed to start screen share: " + error.value)
          return false
        }
        
        // Lock acquired, start screen sharing with AV1 codec and system audio
        await room.value.localParticipant.setScreenShareEnabled(true, {
          resolution: ScreenSharePresets.h1080fps30.resolution,
          contentHint: 'motion',
          audio: true,
        }, {
          videoEncoding: {
            maxBitrate: 2_000_000,
            maxFramerate: 30,
            priority: 'high',
          },
          degradationPreference: 'maintain-framerate',
        })
        isScreenSharing.value = true
        console.log("Screen share started by user")

        // Find the screen share track publication
        const screenTrack = room.value.localParticipant.getTrackPublication(Track.Source.ScreenShare)
        if (screenTrack) {
          localScreenShareTrack.value = screenTrack
        }
        console.log("Screen share track obtained")
      }
      return true
    } catch (e) {
      console.log('Failed to toggle screen share:' + e)
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
      console.log(`Attaching screen share for ${participantId}`)
      videoElement.style.width = '100%'
      videoElement.style.height = '100%'
      videoElement.style.objectFit = 'contain'
      container.innerHTML = ''
      container.appendChild(videoElement)
      console.log(`Screen share attached for ${participantId}\n video readyState: ${videoElement.readyState}\n video paused: ${videoElement.paused}\n video volume: ${videoElement.volume}\n video muted: ${videoElement.muted}`)
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

  function diagnoseAudioRouting(participantId: string): void {
    const audio = participantAudioMap.get(participantId)
    if (!audio) {
      console.log(`No audio info for ${participantId}`)
      return
    }
    
    console.log(`=== Audio Routing Diagnosis for ${participantId} ===`)
    console.log(`AudioElement:`)
    console.log(`  - volume: ${audio.audioElement?.volume}`)
    console.log(`  - muted: ${audio.audioElement?.muted}`)
    console.log(`  - paused: ${audio.audioElement?.paused}`)
    console.log(`  - currentTime: ${audio.audioElement?.currentTime}`)
    console.log(`  - readyState: ${audio.audioElement?.readyState}`)
    
    if (isIOS()) {
      console.log(`Web Audio (iOS):`)
      console.log(`  - Has sourceNode: ${!!audio.sourceNode}`)
      console.log(`  - Has gainNode: ${!!audio.gainNode}`)
      console.log(`  - Gain value: ${audio.gainNode?.gain?.value}`)
      console.log(`  - GainNode inputs: ${audio.gainNode?.numberOfInputs}`)
      console.log(`  - GainNode outputs: ${audio.gainNode?.numberOfOutputs}`)
      
      if (audioContext.value) {
        console.log(`AudioContext:`)
        console.log(`  - state: ${audioContext.value.state}`)
        console.log(`  - sampleRate: ${audioContext.value.sampleRate}`)
        console.log(`  - currentTime: ${audioContext.value.currentTime}`)
      }
    }
    
    console.log(`=== End Diagnosis ===`)
  }

  let bgAudioEl: HTMLAudioElement | null = null
  let bgDestNode: MediaStreamAudioDestinationNode | null = null

  function ensureBackgroundAudioElement(): HTMLAudioElement {
    if (bgAudioEl) return bgAudioEl

    const el = document.createElement('audio')
    el.dataset.backgroundAudio = 'true'
    el.autoplay = true;
    (el as HTMLAudioElement & { playsInline?: boolean }).playsInline = true;
    (el as any).webkitPlaysInline = true;
    el.muted = false
    el.volume = 1.0
    el.style.display = 'none'

    document.body.appendChild(el)
    bgAudioEl = el
    return el
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
    diagnoseAudioRouting,
  }
})




