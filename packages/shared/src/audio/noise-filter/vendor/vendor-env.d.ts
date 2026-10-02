// Ambient types the vendored worklet sources expect. Upstream compiled with
// emscripten's @types and a DOM lib that declared the AudioWorklet global
// scope; this project's lib.dom does not, so they are declared here. Type
// declarations only — the runtime bundle is built separately (see README.md).
declare interface EmscriptenModule {
  HEAPF32: Float32Array
  _malloc(size: number): number
  _free(ptr: number): void
}

declare class AudioWorkletProcessor {
  readonly port: MessagePort
  constructor(nodeOptions?: AudioWorkletNodeOptions)
}

declare const sampleRate: number

declare function registerProcessor(
  name: string,
  processorCtor: new (options: AudioWorkletNodeOptions) => AudioWorkletProcessor,
): void
