export type NoiseCancelMode = 'webrtc' | 'rnnoise' | 'dtln'

export type NoiseCancelSession = {
  mode: NoiseCancelMode
  rawStream: MediaStream
  processedStream: MediaStream
  processedTrack: MediaStreamTrack
  stop: () => Promise<void>
}

type StartOptions = {
  deviceId?: string
}

function publicUrl(path: string) {
  // Do not use origin (may be null under file://); using href is most stable
  return new URL(path.replace(/^\//, ''), window.location.href).toString()
}

function mustGetFirstAudioTrack(stream: MediaStream, label: string): MediaStreamTrack {
  const t = stream.getAudioTracks()[0]
  if (!t) throw new Error(`${label}: getAudioTracks()[0] is empty`)
  return t
}

async function toArrayBuffer(fileUrl: string) {
  const r = await fetch(fileUrl)
  if (!r.ok) throw new Error(`fetch bin failed: ${r.status} ${r.statusText} ${fileUrl}`)
  return await r.arrayBuffer()
}

async function addWorkletModule(ctx: AudioContext, url: string) {
  console.log('[worklet] addModule url =', url)
  await ctx.audioWorklet.addModule(url)
  console.log('[worklet] addModule OK (url)')
}


export async function startNoiseCancel(
  mode: NoiseCancelMode,
  opt: StartOptions = {},
): Promise<NoiseCancelSession> {
  const rawStream = await navigator.mediaDevices.getUserMedia({
    audio:
      mode === 'webrtc'
        ? {
            echoCancellation: true,
            noiseSuppression: true,
            autoGainControl: true,
            deviceId: opt.deviceId ? { exact: opt.deviceId } : undefined,
          }
        : {
            echoCancellation: true,
            noiseSuppression: true,
            autoGainControl: true,
            deviceId: opt.deviceId ? { exact: opt.deviceId } : undefined,
          },
    video: false,
  })

  // webrtc: return raw track directly
  if (mode === 'webrtc') {
    const processedTrack = mustGetFirstAudioTrack(rawStream, 'webrtc rawStream')
    return {
      mode,
      rawStream,
      processedStream: rawStream,
      processedTrack,
      stop: async () => rawStream.getTracks().forEach((t) => t.stop()),
    }
  }

    const ctx = new AudioContext({ sampleRate: 48000 })
    const source = ctx.createMediaStreamSource(rawStream)
    const dest = ctx.createMediaStreamDestination()

    let worklet: AudioWorkletNode

    if (mode === 'rnnoise') {
      await addWorkletModule(ctx, publicUrl('worklets/rnnoise.worklet.js'))

      // only need wasm bytes
      const rnnoiseWasmBytes = await toArrayBuffer(publicUrl('worklets/wasm/RNN/rnnoise.wasm'))

      worklet = new AudioWorkletNode(ctx, 'rnnoise-processor', {
        numberOfInputs: 1,
        numberOfOutputs: 1,
        outputChannelCount: [1],
        processorOptions: {
          rnnoiseWasmBytes,
          notchEnable: true,
          notchFreqs: [1055, 1219, 2016, 2476],
          notchQ: 14,
          notchDynamic: true,

          bandCutEnable: false,

          enableLPF: true,
          lpHz: 12000,

          eqEnable: true,
          eqHz: 3900,
          eqQ: 1.0,
          eqGainDb: 4,

          // Loosen gating to avoid clipping words (be more conservative when WebRTC is enabled)
          vadThreshold: 0.48,
          floorDb: 23,
          aggressiveness: 1.1,
          energyGateDb: -40,
        },
      })

      worklet.port.onmessage = (e) => console.log('[rnnoise worklet msg]', e.data)

  } else {
     await addWorkletModule(ctx, publicUrl('worklets/dtln.worklet.js'))

    // only need wasm bytes
    const moduleWasmBytes = await toArrayBuffer(publicUrl('worklets/wasm/dtln/dtln_rs.wasm'))

    worklet = new AudioWorkletNode(ctx, 'dtln-processor', {
      numberOfInputs: 1,
      numberOfOutputs: 1,
      outputChannelCount: [1],
      processorOptions: { moduleWasmBytes, targetSampleRate: 16000 },
    })

    worklet.port.onmessage = (e) => console.log('[dtln worklet msg]', e.data)

  }


  source.connect(worklet).connect(dest)
  await ctx.resume()

  const processedStream = dest.stream
  const processedTrack = mustGetFirstAudioTrack(processedStream, 'processedStream')

  return {
    mode,
    rawStream,
    processedStream,
    processedTrack,
    stop: async () => {
      try {
        worklet.disconnect()
      } catch {}
      try {
        source.disconnect()
      } catch {}
      try {
        await ctx.close()
      } catch {}
      rawStream.getTracks().forEach((t) => t.stop())
    },
  }
}
