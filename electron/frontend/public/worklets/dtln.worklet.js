// dtln.worklet.js (AudioWorklet) - Electron-friendly (no fetch, no URL.createObjectURL in worklet)
// Expects processorOptions:
// {
//   moduleWasmBytes: ArrayBuffer,   // dtln_rs.wasm bytes (fetched in main thread)
//   targetSampleRate: number        // usually 16000
// }
//
// We do proper 10ms framing, downsample to 16k (160 samples), run DTLN, upsample back.
import * as DTLNNS from './wasm/dtln/dtln_rs.js';

function pickFactory(ns) {
  // 1) 先试 ESM 导出
  if (ns && typeof ns.default === 'function') return ns.default;

  for (const k of ['createDTLNWasmModule', 'createModule', 'Module']) {
    if (ns && typeof ns[k] === 'function') return ns[k];
  }

  // 2) 很多 emscripten 打包会把工厂挂到 globalThis（但不导出）
  const g = globalThis;
  for (const k of ['createDTLNWasmModule', 'createModule', 'Module']) {
    if (typeof g[k] === 'function') return g[k];
  }

  // 3) 兜底：找第一个函数导出
  for (const v of Object.values(ns || {})) {
    if (typeof v === 'function') return v;
  }
  return null;
}

const DTLN_FACTORY = pickFactory(DTLNNS);



if (typeof URL === 'undefined') {
  globalThis.URL = class URL {
    constructor(path, base) {
      const b = (base && base.href) ? base.href : String(base || '');
      const baseDir = b ? b.replace(/[^/]*$/, '') : '';
      this.href = base ? (baseDir + String(path)) : String(path);
    }
    toString() { return this.href; }
  };
}

class RingBuffer {
  constructor(size) {
    this.buf = new Float32Array(size);
    this.size = size;
    this.w = 0;
    this.r = 0;
  }
  availableRead() {
    if (this.w >= this.r) return this.w - this.r;
    return this.size - (this.r - this.w);
  }
  availableWrite() { return this.size - 1 - this.availableRead(); }
  push(input) {
    for (let i = 0; i < input.length; i++) {
      if (this.availableWrite() <= 0) break;
      this.buf[this.w] = input[i] ?? 0;
      this.w = (this.w + 1) % this.size;
    }
  }
  pop(out) {
    const n = Math.min(out.length, this.availableRead());
    for (let i = 0; i < n; i++) {
      out[i] = this.buf[this.r];
      this.r = (this.r + 1) % this.size;
    }
    for (let i = n; i < out.length; i++) out[i] = 0;
    return n;
  }
}

function resampleLinear(inBuf, inRate, outBuf, outRate) {
  const ratio = inRate / outRate;
  const outLen = outBuf.length;
  const inLen = inBuf.length;
  for (let i = 0; i < outLen; i++) {
    const t = i * ratio;
    const i0 = Math.floor(t);
    const frac = t - i0;
    const s0 = inBuf[Math.min(inLen - 1, Math.max(0, i0))] || 0;
    const s1 = inBuf[Math.min(inLen - 1, Math.max(0, i0 + 1))] || 0;
    outBuf[i] = s0 + (s1 - s0) * frac;
  }
}


class DTLNProcessor extends AudioWorkletProcessor {
  constructor(options) {
    super();

    this.ready = false;
    this.mod = null;

    this.inRate = sampleRate;
    this.inFrameLen = Math.max(1, Math.round(this.inRate * 0.01)); // ~10ms

    const po = (options && options.processorOptions) ? options.processorOptions : {};
    // this.moduleJsUrl = po.moduleJsUrl || '';
    this.moduleWasmBytes = po.moduleWasmBytes || null;
    this.targetRate = po.targetSampleRate || 16000;

    // target frame length for 10ms
    this.targetFrameLen = Math.round(this.targetRate * 0.01); // 160 @16k

    this.inFrame = new Float32Array(this.inFrameLen);
    this.targetIn = new Float32Array(this.targetFrameLen);
    this.targetOut = new Float32Array(this.targetFrameLen);
    this.outFrame = new Float32Array(this.inFrameLen);

    this.rbIn = new RingBuffer(this.inFrameLen * 30);
    this.rbOut = new RingBuffer(this.inFrameLen * 30);

    this.stPtr = 0;
    this.inPtr = 0;
    this.outPtr = 0;

    this.boot().catch((e) => {
      this.ready = false;
      try { this.port.postMessage({ type: 'error', error: String(e) }); } catch {}
    });
  }
async boot() {
  if (!this.moduleWasmBytes) throw new Error('DTLN: missing moduleWasmBytes');

  // 注意：dtln_rs.js 可能不导出工厂，但会把工厂函数挂在 globalThis 上
  const factory = pickFactory(DTLNNS);
  if (!factory) throw new Error('DTLN: dtln_rs.js factory not found (no export, no global)');

  const wasmBinary = new Uint8Array(this.moduleWasmBytes);

  this.mod = await factory({
    wasmBinary,
    noInitialRun: true,
  });

  const init = this.mod._dtln_init || this.mod.dtln_init;
  const process = this.mod._dtln_process || this.mod.dtln_process;
  if (!init || !process) throw new Error('DTLN: exports missing (dtln_init / dtln_process)');

  const malloc = this.mod._malloc || this.mod.malloc;
  const free = this.mod._free || this.mod.free;
  if (!malloc || !free) throw new Error('DTLN: malloc/free missing');

  // init state (some builds take sampleRate; if yours doesn't, it will just ignore extra args)
  try { this.stPtr = init(this.targetRate) || init(); } catch { this.stPtr = init(); }
  if (!this.stPtr) throw new Error('DTLN: dtln_init returned 0');

  this.inPtr = malloc(this.targetFrameLen * 4);
  this.outPtr = malloc(this.targetFrameLen * 4);

  this._free = free;
  this._process = process;

  this.ready = true;
  try {
    this.port.postMessage({
      type: 'ready',
      inRate: this.inRate,
      inFrameLen: this.inFrameLen,
      targetRate: this.targetRate,
      targetFrameLen: this.targetFrameLen,
    });
  } catch {}
}

  process(inputs, outputs) {
    const input = inputs[0] && inputs[0][0];
    const output = outputs[0] && outputs[0][0];
    if (!output) return true;

    if (!input) {
      output.fill(0);
      return true;
    }

    this.rbIn.push(input);

    if (!this.ready) {
      const tmp = new Float32Array(output.length);
      const n = this.rbIn.pop(tmp);
      output.set(n ? tmp : input);
      return true;
    }

    while (this.rbIn.availableRead() >= this.inFrameLen && this.rbOut.availableWrite() >= this.inFrameLen) {
      this.rbIn.pop(this.inFrame);

      // downsample to 16k/160
      if (this.inRate === this.targetRate && this.inFrameLen === this.targetFrameLen) {
        this.targetIn.set(this.inFrame);
      } else {
        resampleLinear(this.inFrame, this.inRate, this.targetIn, this.targetRate);
      }

      // call dtln
      const heapF32 = this.mod.HEAPF32;
      heapF32.set(this.targetIn, this.inPtr >> 2);
      this._process(this.stPtr, this.outPtr, this.inPtr);
      this.targetOut.set(heapF32.subarray(this.outPtr >> 2, (this.outPtr >> 2) + this.targetFrameLen));

      // upsample back to input rate/frameLen
      if (this.inRate === this.targetRate && this.inFrameLen === this.targetFrameLen) {
        this.outFrame.set(this.targetOut);
      } else {
        resampleLinear(this.targetOut, this.targetRate, this.outFrame, this.inRate);
      }

      this.rbOut.push(this.outFrame);
    }

    const n = Math.min(output.length, this.rbOut.availableRead());
    if (n > 0) {
      const tmpOut = new Float32Array(output.length);
      this.rbOut.pop(tmpOut);
      output.set(tmpOut);
    } else {
      output.fill(0);
    }
    return true;
  }
}

registerProcessor('dtln-processor', DTLNProcessor);
