import { ref, onUnmounted } from 'vue'

const SAMPLE_RATE = 48000

export function useAudioRelay() {
  const isActive = ref(false)
  const isMuted = ref(false)
  const isDeafened = ref(false)

  let mediaStream: MediaStream | null = null
  let audioContext: AudioContext | null = null
  let sendCallback: ((data: ArrayBuffer) => void) | null = null

  // Remote audio playback using MediaSource for streaming
  const remoteAudioElements = new Map<number, HTMLAudioElement>()
  const remoteMediaSources = new Map<number, MediaSource>()
  const remoteSourceBuffers = new Map<number, SourceBuffer>()
  const pendingBuffers = new Map<number, ArrayBuffer[]>()

  // Each user needs to accumulate webm data with header
  const userWebmHeaders = new Map<number, ArrayBuffer>()

  async function startCapture(onAudioData: (data: ArrayBuffer) => void): Promise<boolean> {
    try {
      mediaStream = await navigator.mediaDevices.getUserMedia({
        audio: {
          sampleRate: SAMPLE_RATE,
          channelCount: 1,
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
        },
        video: false,
      })

      sendCallback = onAudioData

      // Use AudioContext + ScriptProcessor for raw PCM capture
      audioContext = new AudioContext({ sampleRate: SAMPLE_RATE })
      const source = audioContext.createMediaStreamSource(mediaStream)

      // Use ScriptProcessorNode (deprecated but widely supported)
      const processor = audioContext.createScriptProcessor(4096, 1, 1)

      processor.onaudioprocess = (e) => {
        if (isMuted.value || !sendCallback) return

        const inputData = e.inputBuffer.getChannelData(0)
        // Convert Float32 to Int16 for transmission
        const pcmData = new Int16Array(inputData.length)
        for (let i = 0; i < inputData.length; i++) {
          const s = Math.max(-1, Math.min(1, inputData[i] ?? 0))
          pcmData[i] = s < 0 ? s * 0x8000 : s * 0x7fff
        }
        sendCallback(pcmData.buffer)
      }

      source.connect(processor)
      processor.connect(audioContext.destination)

      isActive.value = true
      return true
    } catch (e) {
      console.error('Failed to start audio capture:', e)
      return false
    }
  }

  function stopCapture() {
    if (audioContext) {
      audioContext.close()
      audioContext = null
    }
    if (mediaStream) {
      mediaStream.getTracks().forEach((track) => track.stop())
      mediaStream = null
    }
    sendCallback = null
    isActive.value = false
  }

  function playRemoteAudio(userId: number, audioData: ArrayBuffer) {
    if (isDeafened.value || audioData.byteLength === 0) return

    // Get or create audio context for playback
    let playbackCtx = remoteAudioElements.get(userId) as unknown as AudioContext
    if (!playbackCtx || playbackCtx.state === 'closed') {
      playbackCtx = new AudioContext({ sampleRate: SAMPLE_RATE })
      remoteAudioElements.set(userId, playbackCtx as unknown as HTMLAudioElement)
    }

    // Convert Int16 PCM back to Float32 and play
    const pcmData = new Int16Array(audioData)
    const floatData = new Float32Array(pcmData.length)
    for (let i = 0; i < pcmData.length; i++) {
      const sample = pcmData[i] ?? 0
      floatData[i] = sample / (sample < 0 ? 0x8000 : 0x7fff)
    }

    // Create audio buffer and play
    const audioBuffer = playbackCtx.createBuffer(1, floatData.length, SAMPLE_RATE)
    audioBuffer.getChannelData(0).set(floatData)

    const source = playbackCtx.createBufferSource()
    source.buffer = audioBuffer
    source.connect(playbackCtx.destination)
    source.start()
  }

  function toggleMute(): boolean {
    isMuted.value = !isMuted.value
    return isMuted.value
  }

  function toggleDeafen(): boolean {
    isDeafened.value = !isDeafened.value
    return isDeafened.value
  }

  function stopRemoteAudio(userId: number) {
    const ctx = remoteAudioElements.get(userId) as unknown as AudioContext
    if (ctx && ctx.close) {
      ctx.close()
    }
    remoteAudioElements.delete(userId)
    remoteMediaSources.delete(userId)
    remoteSourceBuffers.delete(userId)
    pendingBuffers.delete(userId)
    userWebmHeaders.delete(userId)
  }

  function cleanup() {
    stopCapture()
    remoteAudioElements.forEach((ctx) => {
      const audioCtx = ctx as unknown as AudioContext
      if (audioCtx && audioCtx.close) {
        audioCtx.close()
      }
    })
    remoteAudioElements.clear()
    remoteMediaSources.clear()
    remoteSourceBuffers.clear()
    pendingBuffers.clear()
    userWebmHeaders.clear()
  }

  onUnmounted(() => {
    cleanup()
  })

  return {
    isActive,
    isMuted,
    isDeafened,
    startCapture,
    stopCapture,
    playRemoteAudio,
    stopRemoteAudio,
    toggleMute,
    toggleDeafen,
    cleanup,
  }
}
