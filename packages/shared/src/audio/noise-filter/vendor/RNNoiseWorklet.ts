// Vendored from https://github.com/Dadadah/livekit-rnnoise-processor (Apache-2.0).
// The emscripten glue import path was adjusted to this directory's flat layout.
import createRNNWasmModule from "./rnnoise.js";
import MonoResampler from "./MonoResampler.js";

const RNNOISE_SAMPLE_LENGTH = 480;
const SHIFT_16_BIT_NR = 32768;
const RNNOISE_REQUIRED_SAMPLE_RATE = 48000;

interface IRnnoiseModule extends EmscriptenModule {
  _rnnoise_create: () => number;
  _rnnoise_destroy: (context: number) => void;
  _rnnoise_process_frame: (context: number, output: number, input: number) => number;
}

class RNNoiseWorklet extends AudioWorkletProcessor {
  private _rnWasmInterface: IRnnoiseModule | undefined;

  private _rnContext: number = 0;
  private _rnPtr: number = 0;

  private _queueSize: number = 4;

  private _outputBuffer: number[] = [];
  private _frameBufferIndex: number = 0;
  private _destroyed: boolean = false;
  private _debugLogs: boolean = false;
  private _vadLogs: boolean = false;
  private _shouldDenoise: boolean = true;
  private _inputResampler: MonoResampler | undefined;
  private _outputResampler: MonoResampler | undefined;

  constructor(options: AudioWorkletNodeOptions) {
    super();

    this._debugLogs = options.processorOptions?.debugLogs ?? false;
    this._vadLogs = options.processorOptions?.vadLogs ?? false;

    if (this._debugLogs) {
      console.log(`Received a sample rate of ${sampleRate}`);
    }

    if (this._debugLogs) {
      console.log("RNNoiseWorklet.constructor options:", options);
    }

    this._inputResampler = new MonoResampler(sampleRate, RNNOISE_REQUIRED_SAMPLE_RATE, RNNOISE_SAMPLE_LENGTH * 2);
    this._outputResampler = new MonoResampler(RNNOISE_REQUIRED_SAMPLE_RATE, sampleRate, RNNOISE_SAMPLE_LENGTH * 2);

    this._handleEvent();

    const rnnoiseBuffer: ArrayBuffer = options.processorOptions?.rnnoiseBuffer;
    if (!rnnoiseBuffer) {
      throw "RNNoiseWorklet must be initialized with an rnnoise array buffer!";
    }
    const instantiateWasm = async (info: WebAssembly.Imports | undefined, successCallback: (instance: WebAssembly.Instance, module: WebAssembly.Module) => void) => {
      let module = new WebAssembly.Module(rnnoiseBuffer);
      let instance = new WebAssembly.Instance(module, info);
      successCallback(instance, module);
    };

    createRNNWasmModule({ instantiateWasm }).then((module) => {
      this.initRNNoise(module as IRnnoiseModule);
    });
  }

  initRNNoise(module: IRnnoiseModule) {
    try {
      this._rnWasmInterface = module;
      this._rnContext = this._rnWasmInterface._rnnoise_create();
      this._rnPtr = this._rnWasmInterface._malloc(RNNOISE_SAMPLE_LENGTH * this._queueSize);

      if (this._debugLogs) {
        console.log("RNNoiseWorklet context:", this._rnContext);
      }
    } catch (error) {
      if (this._debugLogs) {
        console.error("RNNoiseWorklet.constructor error", error);
      }
      // release can be called even if not all the components were initialized.
      this.destroy();
      throw error;
    }
  }

  removeNoise() {
    if (!this._shouldDenoise) return;
    if (this._rnWasmInterface) {
      const vadResult = this._rnWasmInterface._rnnoise_process_frame(this._rnContext, this._rnPtr, this._rnPtr);
      if (this._debugLogs && this._vadLogs) {
        console.log("RNNoiseWorklet.process vad:", vadResult);
      }
    } else {
      console.error("RNNoiseWorklet tried to process noise without the rnnoise wasm module");
    }
  }

  process(inputs: Float32Array[][], outputs: Float32Array[][]) {
    // If the worklet has been destroyed, or the resamplers are undefiend (also implying destruction) then tell the browser we're ready for cleanup.
    if (this._destroyed || !this._inputResampler || !this._outputResampler) {
      return false;
    }

    // Awaiting initialization of wasm module
    if (!this._rnWasmInterface) {
      return true;
    }

    const input = inputs[0];
    const output = outputs[0];

    if (this._debugLogs) {
      if (!input || !output || !input[0] || !output[0] || input.length != output.length || input[0].length != output[0].length) {
        console.log("Inputs or outputs were not as expected:", input, output);
      }
    }

    if (!input[0]) {
      return true;
    }

    // Drain the first channel of the input into the buffer
    const inputResampled = this._inputResampler.resample(input[0]);

    for (const sample of inputResampled) {
      // Add sample
      this._rnWasmInterface.HEAPF32[(this._rnPtr >> 2) + this._frameBufferIndex] = sample * SHIFT_16_BIT_NR;
      this._frameBufferIndex++;
      // If we have enough samples
      if (this._frameBufferIndex >= RNNOISE_SAMPLE_LENGTH) {
        this.removeNoise();
        // Frame buffer will have either been overwritten with rnnoise output, or it would be unchanged
        // therefore we can safely push frame buffer to output buffer
        this._outputBuffer.push(
          ...this._outputResampler.resample(this._rnWasmInterface.HEAPF32.slice(this._rnPtr >> 2, (this._rnPtr >> 2) + RNNOISE_SAMPLE_LENGTH).map((val) => val / SHIFT_16_BIT_NR)),
        );
        // Reset index
        this._frameBufferIndex = 0;
      }
    }

    if (this._outputBuffer.length >= output[0].length) {
      output.forEach((channel) => {
        for (let i = 0; i < channel.length; i++) {
          channel[i] = this._outputBuffer[i];
        }
      });
      this._outputBuffer = this._outputBuffer.slice(output[0].length);
    }

    if (this._debugLogs) {
      if (output[0].length !== 128) {
        console.log("Output length was not 128 it was", output[0].length);
      }
    }

    return true;
  }

  destroy() {
    // Attempting to release a non initialized processor, do nothing.
    if (this._destroyed) {
      if (this._debugLogs) {
        console.log("Destroying an already destroyed RNNoiseWorklet.");
      }
      return;
    }
    this._destroyed = true;
    if (this._rnContext) {
      if (this._debugLogs) {
        console.log("Destroying rnnoise module");
      }

      this._rnWasmInterface?._rnnoise_destroy(this._rnContext);
      this._rnWasmInterface?._free(this._rnPtr);
      this._rnWasmInterface = undefined;
      this._rnContext = 0;
      this._rnPtr = 0;
    }

    this.port.close();
    this._outputBuffer = [];
    this._inputResampler = undefined;
    this._outputResampler = undefined;
  }

  _handleEvent() {
    this.port.onmessage = (event) => {
      if (event.data.message === "SET_ENABLED") {
        this._shouldDenoise = event.data.enable ?? this._shouldDenoise;

        if (this._debugLogs) {
          console.log("RNNoiseWorklet.SET_ENABLED: ", this._shouldDenoise);
        }
      } else if (event.data.message === "DESTROY") {
        if (this._debugLogs) {
          console.log("RNNoiseWorklet.DESTROY");
        }
        this.destroy();
      }
    };
  }
}
registerProcessor("RNNoiseWorklet", RNNoiseWorklet);
