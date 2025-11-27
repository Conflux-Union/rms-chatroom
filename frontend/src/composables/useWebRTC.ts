import { ref, onUnmounted } from 'vue'
import type { VoiceUser } from '../types'

export type ConnectionMode = 'p2p' | 'relay' | 'connecting'

const ICE_CONNECTION_TIMEOUT = 5000 // 5 seconds

export function useWebRTC(
  onRemoteStream?: (userId: number, stream: MediaStream) => void,
  onConnectionFailed?: (userId: number) => void
) {
  const localStream = ref<MediaStream | null>(null)
  const remoteStreams = ref<Map<number, MediaStream>>(new Map())
  const peerConnections = ref<Map<number, RTCPeerConnection>>(new Map())
  const connectionModes = ref<Map<number, ConnectionMode>>(new Map())
  const isMuted = ref(false)
  const isDeafened = ref(false)
  const voiceUsers = ref<VoiceUser[]>([])

  const connectionTimeouts = new Map<number, ReturnType<typeof setTimeout>>()

  const iceServers = [
    { urls: 'stun:stun.l.google.com:19302' },
    { urls: 'stun:stun1.l.google.com:19302' },
  ]

  async function startLocalStream() {
    try {
      localStream.value = await navigator.mediaDevices.getUserMedia({
        audio: true,
        video: false,
      })
      return true
    } catch (e) {
      console.error('Failed to get local stream:', e)
      return false
    }
  }

  function stopLocalStream() {
    if (localStream.value) {
      localStream.value.getTracks().forEach((track) => track.stop())
      localStream.value = null
    }
  }

  function createPeerConnection(userId: number, onIceCandidate: (candidate: RTCIceCandidate) => void) {
    const pc = new RTCPeerConnection({ iceServers })
    connectionModes.value.set(userId, 'connecting')

    // Start connection timeout
    const timeout = setTimeout(() => {
      const mode = connectionModes.value.get(userId)
      if (mode === 'connecting') {
        console.log(`P2P connection timeout for user ${userId}, switching to relay`)
        connectionModes.value.set(userId, 'relay')
        if (onConnectionFailed) {
          onConnectionFailed(userId)
        }
      }
    }, ICE_CONNECTION_TIMEOUT)
    connectionTimeouts.set(userId, timeout)

    pc.onicecandidate = (event) => {
      if (event.candidate) {
        onIceCandidate(event.candidate)
      }
    }

    pc.oniceconnectionstatechange = () => {
      const state = pc.iceConnectionState
      if (state === 'connected' || state === 'completed') {
        // P2P succeeded
        clearTimeout(connectionTimeouts.get(userId))
        connectionTimeouts.delete(userId)
        connectionModes.value.set(userId, 'p2p')
        console.log(`P2P connection established with user ${userId}`)
      } else if (state === 'failed' || state === 'disconnected') {
        // P2P failed
        clearTimeout(connectionTimeouts.get(userId))
        connectionTimeouts.delete(userId)
        const currentMode = connectionModes.value.get(userId)
        if (currentMode !== 'relay') {
          connectionModes.value.set(userId, 'relay')
          console.log(`P2P connection failed for user ${userId}, switching to relay`)
          if (onConnectionFailed) {
            onConnectionFailed(userId)
          }
        }
      }
    }

    pc.ontrack = (event) => {
      if (event.streams && event.streams[0]) {
        remoteStreams.value.set(userId, event.streams[0])
        if (onRemoteStream) {
          onRemoteStream(userId, event.streams[0])
        }
      }
    }

    // Add local tracks
    if (localStream.value) {
      const stream = localStream.value
      stream.getTracks().forEach((track) => {
        pc.addTrack(track, stream)
      })
    }

    peerConnections.value.set(userId, pc)
    return pc
  }

  function getConnectionMode(userId: number): ConnectionMode {
    return connectionModes.value.get(userId) || 'connecting'
  }

  function hasAnyP2PConnection(): boolean {
    for (const mode of connectionModes.value.values()) {
      if (mode === 'p2p') return true
    }
    return false
  }

  function needsRelay(): boolean {
    for (const mode of connectionModes.value.values()) {
      if (mode === 'relay') return true
    }
    return false
  }

  async function createOffer(userId: number, onIceCandidate: (candidate: RTCIceCandidate) => void) {
    const pc = createPeerConnection(userId, onIceCandidate)
    const offer = await pc.createOffer()
    await pc.setLocalDescription(offer)
    return offer
  }

  async function handleOffer(
    userId: number,
    sdp: RTCSessionDescriptionInit,
    onIceCandidate: (candidate: RTCIceCandidate) => void
  ) {
    const pc = createPeerConnection(userId, onIceCandidate)
    await pc.setRemoteDescription(new RTCSessionDescription(sdp))
    const answer = await pc.createAnswer()
    await pc.setLocalDescription(answer)
    return answer
  }

  async function handleAnswer(userId: number, sdp: RTCSessionDescriptionInit) {
    const pc = peerConnections.value.get(userId)
    if (pc) {
      await pc.setRemoteDescription(new RTCSessionDescription(sdp))
    }
  }

  async function handleIceCandidate(userId: number, candidate: RTCIceCandidateInit) {
    const pc = peerConnections.value.get(userId)
    if (pc) {
      await pc.addIceCandidate(new RTCIceCandidate(candidate))
    }
  }

  function toggleMute() {
    isMuted.value = !isMuted.value
    if (localStream.value) {
      localStream.value.getAudioTracks().forEach((track) => {
        track.enabled = !isMuted.value
      })
    }
    return isMuted.value
  }

  function toggleDeafen() {
    isDeafened.value = !isDeafened.value
    // Mute all remote streams
    remoteStreams.value.forEach((stream) => {
      stream.getAudioTracks().forEach((track) => {
        track.enabled = !isDeafened.value
      })
    })
    return isDeafened.value
  }

  function closePeerConnection(userId: number) {
    clearTimeout(connectionTimeouts.get(userId))
    connectionTimeouts.delete(userId)
    const pc = peerConnections.value.get(userId)
    if (pc) {
      pc.close()
      peerConnections.value.delete(userId)
    }
    remoteStreams.value.delete(userId)
    connectionModes.value.delete(userId)
  }

  function closeAllConnections() {
    connectionTimeouts.forEach((timeout) => clearTimeout(timeout))
    connectionTimeouts.clear()
    peerConnections.value.forEach((pc) => pc.close())
    peerConnections.value.clear()
    remoteStreams.value.clear()
    connectionModes.value.clear()
    stopLocalStream()
  }

  onUnmounted(() => {
    closeAllConnections()
  })

  return {
    localStream,
    remoteStreams,
    connectionModes,
    isMuted,
    isDeafened,
    voiceUsers,
    startLocalStream,
    stopLocalStream,
    createOffer,
    handleOffer,
    handleAnswer,
    handleIceCandidate,
    toggleMute,
    toggleDeafen,
    closePeerConnection,
    closeAllConnections,
    getConnectionMode,
    hasAnyP2PConnection,
    needsRelay,
  }
}
