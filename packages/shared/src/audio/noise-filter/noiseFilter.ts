// RNNoise-based AI noise suppression for the local microphone track.
//
// Adapted from github.com/Dadadah/livekit-rnnoise-processor (Apache-2.0,
// see ./LICENSE). Unlike upstream, the worklet bundle and the WASM binary
// are bundled app assets instead of CDN downloads: the Tauri shell serves
// the app over a custom protocol and web builds must stay same-origin.
import type { AudioProcessorOptions, Room, TrackProcessor } from 'livekit-client'
import { Track } from 'livekit-client'
import workletUrl from './rnnoise-worklet.js?url'
import wasmUrl from './vendor/rnnoise.wasm?url'

// Fetched once, then handed to every worklet node via processorOptions
// (structured clone per node).
let wasmBytes: ArrayBuffer | null = null

export function isAiNoiseSuppressionSupported(): boolean {
  return (
    typeof AudioWorkletNode !== 'undefined' &&
    typeof WebAssembly !== 'undefined' &&
    typeof fetch !== 'undefined'
  )
}

class RNNoiseNode extends AudioWorkletNode {
  static async loadModule(ctx: AudioContext): Promise<void> {
    await ctx.audioWorklet.addModule(workletUrl)
    if (!wasmBytes) {
      const resp = await fetch(wasmUrl)
      wasmBytes = await resp.arrayBuffer()
    }
  }

  constructor(ctx: AudioContext) {
    if (!wasmBytes || wasmBytes.byteLength === 0) {
      throw new Error('RNNoise WASM not initialized, call loadModule() first')
    }
    super(ctx, 'RNNoiseWorklet', {
      processorOptions: { rnnoiseBuffer: wasmBytes },
      numberOfInputs: 1,
      numberOfOutputs: 1,
    })
  }
}

/**
 * A livekit track processor that reduces noise in the voice stream with
 * RNNoise. Attach with LocalAudioTrack.setProcessor(), detach with
 * LocalAudioTrack.stopProcessor().
 */
export class RNNoiseTrackProcessor implements TrackProcessor<Track.Kind.Audio, AudioProcessorOptions> {
  readonly name = 'rnnoise-track-processor'
  processedTrack?: MediaStreamTrack
  private audioOpts?: AudioProcessorOptions
  private denoiseNode?: AudioWorkletNode
  private orgSourceNode?: MediaStreamAudioSourceNode
  private enabled = true

  static isSupported(): boolean {
    return isAiNoiseSuppressionSupported()
  }

  async init(opts: AudioProcessorOptions): Promise<void> {
    if (!opts.audioContext || !opts.track) {
      throw new Error('audioContext and track are required')
    }
    await RNNoiseNode.loadModule(opts.audioContext)
    this.initInternal(opts, false)
  }

  async restart(opts: AudioProcessorOptions): Promise<void> {
    this.initInternal({ ...opts, audioContext: opts.audioContext ?? this.audioOpts?.audioContext }, true)
  }

  async onPublish(_room: Room): Promise<void> {}

  async onUnpublish(): Promise<void> {}

  async setEnabled(enable: boolean): Promise<void> {
    if (!this.denoiseNode) return
    this.enabled = enable
    this.denoiseNode.port.postMessage({ message: 'SET_ENABLED', enable })
  }

  async isEnabled(): Promise<boolean> {
    return this.denoiseNode ? this.enabled : false
  }

  async destroy(): Promise<void> {
    this.closeInternal()
  }

  private initInternal(opts: AudioProcessorOptions, restart: boolean): void {
    if (!opts.audioContext || !opts.track) {
      throw new Error('audioContext and track are required')
    }
    if (restart) {
      this.closeInternal()
    }
    this.audioOpts = opts
    const ctx = opts.audioContext

    const denoiseNode = new RNNoiseNode(ctx)
    const orgSourceNode = ctx.createMediaStreamSource(new MediaStream([opts.track]))
    const destination = ctx.createMediaStreamDestination()

    orgSourceNode.connect(denoiseNode)
    denoiseNode.connect(destination)

    this.denoiseNode = denoiseNode
    this.orgSourceNode = orgSourceNode
    this.processedTrack = destination.stream.getAudioTracks()[0]
  }

  private closeInternal(): void {
    this.denoiseNode?.port.postMessage({ message: 'DESTROY' })
    this.denoiseNode?.port.close()
    this.denoiseNode?.disconnect()
    this.orgSourceNode?.disconnect()
    this.denoiseNode = undefined
    this.orgSourceNode = undefined
    this.processedTrack = undefined
  }
}
