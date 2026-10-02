// Minimal typing for the vendored emscripten glue (worklet/dist/rnnoise.js
// upstream). Only the surface used by RNNoiseWorklet.ts is declared.
declare const createRNNWasmModule: (opts: {
  instantiateWasm?: (
    imports: WebAssembly.Imports | undefined,
    successCallback: (instance: WebAssembly.Instance, module: WebAssembly.Module) => unknown,
  ) => void | Promise<void>
}) => Promise<unknown>

export default createRNNWasmModule
