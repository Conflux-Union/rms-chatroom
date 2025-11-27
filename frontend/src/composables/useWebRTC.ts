import { ref, onUnmounted } from 'vue'
import type { VoiceUser } from '../types'

export function useWebRTC(onRemoteStream?: (userId: number, stream: MediaStream) => void) {
  const localStream = ref<MediaStream | null>(null)
  const remoteStreams = ref<Map<number, MediaStream>>(new Map())
  const peerConnections = ref<Map<number, RTCPeerConnection>>(new Map())
  const isMuted = ref(false)
  const isDeafened = ref(false)
  const voiceUsers = ref<VoiceUser[]>([])

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

    pc.onicecandidate = (event) => {
      if (event.candidate) {
        onIceCandidate(event.candidate)
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
    const pc = peerConnections.value.get(userId)
    if (pc) {
      pc.close()
      peerConnections.value.delete(userId)
    }
    remoteStreams.value.delete(userId)
  }

  function closeAllConnections() {
    peerConnections.value.forEach((pc) => pc.close())
    peerConnections.value.clear()
    remoteStreams.value.clear()
    stopLocalStream()
  }

  onUnmounted(() => {
    closeAllConnections()
  })

  return {
    localStream,
    remoteStreams,
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
  }
}
