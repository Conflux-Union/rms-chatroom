// rnnoise.worklet.ultra3.js (AudioWorklet) - Electron-friendly
// ultra2 + Low-pass filter to tame harsh highs / hiss
//
// Expects processorOptions:
// {
//   rnnoiseWasmBytes: ArrayBuffer,            // REQUIRED
//   hpHz?: number,                            // default 110
//   lpHz?: number,                            // default 9000
//   enableLPF?: boolean,                      // default true
//   vadThreshold?: number,                    // default 0.60
//   vadKnee?: number,                         // default 0.15
//   floorDb?: number,                         // default 28
//   aggressiveness?: number,                  // default 1.6
//   gateAttackMs?: number,                    // default 30
//   gateReleaseMs?: number,                   // default 180
//   energyGateDb?: number,                    // default -45
//   disableGate?: boolean,                    // default false

// Additional band-cut options (for fan noise band suppression):
//   bandCutEnable?: boolean    // default false
//   bandCutLowHz?: number      // default 1570
//   bandCutHighHz?: number     // default 6188
//   bandCutDb?: number         // default -8   (negative = cut)
//   bandCutDynamic?: boolean   // default true (cut stronger when no speech)

// Multi-notch options (for tonal fan peaks like ~1055Hz / ~1219Hz):
//   notchEnable?: boolean         // default false
//   notchFreqs?: number[]         // default [1055, 1219]
//   notchQ?: number               // default 12 (higher = narrower)
//   notchDynamic?: boolean        // default true (stronger when no speech)
//
// Existing options:
//   rnnoiseWasmBytes (required), hpHz, lpHz, enableLPF, vadThreshold, vadKnee, floorDb,
//   aggressiveness, gateAttackMs, gateReleaseMs, energyGateDb, disableGate

import createRNNWasmModule from './wasm/RNN/rnnoise.js';

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

class OnePoleHighPass {
  constructor(fs, hz) { this.set(fs, hz); this.x1 = 0; this.y1 = 0; }
  set(fs, hz) {
    const fc = Math.max(10, Math.min(hz || 110, fs * 0.45));
    const rc = 1.0 / (2 * Math.PI * fc);
    const dt = 1.0 / fs;
    this.a = rc / (rc + dt);
  }
  processInPlace(buf) {
    const a = this.a;
    let x1 = this.x1, y1 = this.y1;
    for (let i = 0; i < buf.length; i++) {
      const x = buf[i];
      const y = a * (y1 + x - x1);
      buf[i] = y;
      x1 = x; y1 = y;
    }
    this.x1 = x1; this.y1 = y1;
  }
}

class OnePoleLowPass {
  constructor(fs, hz) { this.set(fs, hz); this.y1 = 0; }
  set(fs, hz) {
    const fc = Math.max(200, Math.min(hz || 9000, fs * 0.45));
    const rc = 1.0 / (2 * Math.PI * fc);
    const dt = 1.0 / fs;
    this.a = dt / (rc + dt);
  }
  processInPlace(buf) {
    const a = this.a;
    let y1 = this.y1;
    for (let i = 0; i < buf.length; i++) {
      y1 = y1 + a * (buf[i] - y1);
      buf[i] = y1;
    }
    this.y1 = y1;
  }
}

function clamp(x, lo, hi) { return Math.max(lo, Math.min(hi, x)); }
function dbToLin(db) { return Math.pow(10, db / 20); }

function energyVad(frame, gateDb) {
  let sum = 0;
  for (let i = 0; i < frame.length; i++) {
    const v = frame[i];
    sum += v * v;
  }
  const rms = Math.sqrt(sum / Math.max(1, frame.length));
  const db = 20 * Math.log10(rms + 1e-9);
  const t = (db - gateDb) / 12;
  return clamp(t, 0, 1);
}

// Biquad peaking EQ (center freq boost/cut)
class PeakingEQ {
  constructor(fs, f0, q, gainDb) {
    this.b0 = 1; this.b1 = 0; this.b2 = 0;
    this.a1 = 0; this.a2 = 0;
    this.x1 = 0; this.x2 = 0;
    this.y1 = 0; this.y2 = 0;
    this.set(fs, f0, q, gainDb);
  }
  set(fs, f0, q, gainDb) {
    const nyq = fs * 0.5;
    const F0 = clamp(f0 || 3000, 20, nyq * 0.95);
    const Q = clamp(q || 1.0, 0.1, 12.0);
    const A = Math.pow(10, (gainDb || 0) / 40); // sqrt(10^(dB/20))

    const w0 = 2 * Math.PI * (F0 / fs);
    const cosw0 = Math.cos(w0);
    const sinw0 = Math.sin(w0);
    const alpha = sinw0 / (2 * Q);

    // RBJ peaking EQ
    const b0 = 1 + alpha * A;
    const b1 = -2 * cosw0;
    const b2 = 1 - alpha * A;
    const a0 = 1 + alpha / A;
    const a1 = -2 * cosw0;
    const a2 = 1 - alpha / A;

    // normalize
    this.b0 = b0 / a0;
    this.b1 = b1 / a0;
    this.b2 = b2 / a0;
    this.a1 = a1 / a0;
    this.a2 = a2 / a0;

    // reset state to avoid pops when retuning aggressively
    this.x1 = this.x2 = 0;
    this.y1 = this.y2 = 0;
  }
  processInPlace(buf) {
    let x1 = this.x1, x2 = this.x2, y1 = this.y1, y2 = this.y2;
    const b0 = this.b0, b1 = this.b1, b2 = this.b2, a1 = this.a1, a2 = this.a2;

    for (let i = 0; i < buf.length; i++) {
      const x0 = buf[i];
      const y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
      buf[i] = y0;
      x2 = x1; x1 = x0;
      y2 = y1; y1 = y0;
    }

    this.x1 = x1; this.x2 = x2;
    this.y1 = y1; this.y2 = y2;
  }
}


// Biquad notch filter (very narrow cut at f0)
class NotchFilter {
  constructor(fs, f0, q) {
    this.b0 = 1; this.b1 = 0; this.b2 = 0;
    this.a1 = 0; this.a2 = 0;
    this.x1 = 0; this.x2 = 0;
    this.y1 = 0; this.y2 = 0;
    this.set(fs, f0, q);
  }
  set(fs, f0, q) {
    const nyq = fs * 0.5;
    const F0 = clamp(f0 || 1000, 20, nyq * 0.95);
    const Q = clamp(q || 12.0, 0.5, 60.0);

    const w0 = 2 * Math.PI * (F0 / fs);
    const cosw0 = Math.cos(w0);
    const sinw0 = Math.sin(w0);
    const alpha = sinw0 / (2 * Q);

    // RBJ notch
    const b0 = 1;
    const b1 = -2 * cosw0;
    const b2 = 1;
    const a0 = 1 + alpha;
    const a1 = -2 * cosw0;
    const a2 = 1 - alpha;

    this.b0 = b0 / a0;
    this.b1 = b1 / a0;
    this.b2 = b2 / a0;
    this.a1 = a1 / a0;
    this.a2 = a2 / a0;

    // reset state to avoid ringing when retuning
    this.x1 = this.x2 = 0;
    this.y1 = this.y2 = 0;
  }
  processInPlace(buf) {
    let x1 = this.x1, x2 = this.x2, y1 = this.y1, y2 = this.y2;
    const b0 = this.b0, b1 = this.b1, b2 = this.b2, a1 = this.a1, a2 = this.a2;

    for (let i = 0; i < buf.length; i++) {
      const x0 = buf[i];
      const y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
      buf[i] = y0;
      x2 = x1; x1 = x0;
      y2 = y1; y1 = y0;
    }
    this.x1 = x1; this.x2 = x2;
    this.y1 = y1; this.y2 = y2;
  }
}

class RNNoiseProcessor extends AudioWorkletProcessor {
  constructor(options) {
    super();
    this.ready = false;

    this.inRate = sampleRate;
    this.inFrameLen = Math.max(1, Math.round(this.inRate * 0.01));
    this.rnRate = 48000;
    this.rnFrameLen = 480;

    this.inFrame = new Float32Array(this.inFrameLen);
    this.rnIn = new Float32Array(this.rnFrameLen);
    this.rnOut = new Float32Array(this.rnFrameLen);
    this.outFrame = new Float32Array(this.inFrameLen);

    this.rbIn = new RingBuffer(this.inFrameLen * 30);
    this.rbOut = new RingBuffer(this.inFrameLen * 30);
    this._tmpOut = null;

    const po = (options && options.processorOptions) ? options.processorOptions : {};
    this.rnnoiseWasmBytes = po.rnnoiseWasmBytes || null;

    this.hpHz = typeof po.hpHz === 'number' ? po.hpHz : 110;
    this.lpHz = typeof po.lpHz === 'number' ? po.lpHz : 9000;
    this.enableLPF = (typeof po.enableLPF === 'boolean') ? po.enableLPF : true;

    // EQ boost
    this.eqEnable = !!po.eqEnable;
    this.eqHz = typeof po.eqHz === 'number' ? po.eqHz : 3000;
    this.eqQ = typeof po.eqQ === 'number' ? po.eqQ : 1.0;
    this.eqGainDb = typeof po.eqGainDb === 'number' ? po.eqGainDb : 3.0;

    // Band-cut (wide peaking cut) for fan noise band suppression
    this.bandCutEnable = !!po.bandCutEnable;
    this.bandCutLowHz = typeof po.bandCutLowHz === 'number' ? po.bandCutLowHz : 1570;
    this.bandCutHighHz = typeof po.bandCutHighHz === 'number' ? po.bandCutHighHz : 6188;
    this.bandCutDb = typeof po.bandCutDb === 'number' ? po.bandCutDb : -8.0; // negative = cut
    this.bandCutDynamic = (typeof po.bandCutDynamic === 'boolean') ? po.bandCutDynamic : true;

    // Multi-notch (tonal peaks)
    this.notchEnable = !!po.notchEnable;
    this.notchFreqs = Array.isArray(po.notchFreqs) ? po.notchFreqs : [1055, 1219];
    this.notchQ = typeof po.notchQ === 'number' ? po.notchQ : 12.0;
    this.notchDynamic = (typeof po.notchDynamic === 'boolean') ? po.notchDynamic : true;

    this.vadThreshold = typeof po.vadThreshold === 'number' ? po.vadThreshold : 0.60;
    this.vadKnee = typeof po.vadKnee === 'number' ? po.vadKnee : 0.15;
    this.floorDb = typeof po.floorDb === 'number' ? po.floorDb : 28;
    this.aggressiveness = typeof po.aggressiveness === 'number' ? po.aggressiveness : 1.6;
    this.gateAttackMs = typeof po.gateAttackMs === 'number' ? po.gateAttackMs : 30;
    this.gateReleaseMs = typeof po.gateReleaseMs === 'number' ? po.gateReleaseMs : 180;
    this.energyGateDb = typeof po.energyGateDb === 'number' ? po.energyGateDb : -45;
    this.disableGate = !!po.disableGate;

    this._vadSmooth = 1.0;
    this._gateGain = 1.0;

    this.hpIn = new OnePoleHighPass(this.inRate, this.hpHz);
    this.hpOut = new OnePoleHighPass(this.inRate, this.hpHz);
    this.lpOut = new OnePoleLowPass(this.inRate, this.lpHz);
    this.eqOut = new PeakingEQ(this.inRate, this.eqHz, this.eqQ, this.eqGainDb);

    // Compute default center/Q from band
    const _bcLow = Math.max(20, this.bandCutLowHz);
    const _bcHigh = Math.max(_bcLow + 1, this.bandCutHighHz);
    const _bcCenter = Math.sqrt(_bcLow * _bcHigh);
    const _bcQ = _bcCenter / (_bcHigh - _bcLow);
    this.bandEq = new PeakingEQ(this.inRate, _bcCenter, _bcQ, this.bandCutDb);
    this._bandTmp = new Float32Array(this.inFrameLen);

    // Notch filters (up to 4)
    const _nf = (this.notchFreqs || []).slice(0, 4);
    this._notchCount = _nf.length;
    this.notches = [];
    for (let i = 0; i < _nf.length; i++) {
      this.notches.push(new NotchFilter(this.inRate, _nf[i], this.notchQ));
    }
    this._notchTmp = new Float32Array(this.inFrameLen);

    this.boot().catch((e) => {
      this.ready = false;
      try { this.port.postMessage({ type: 'error', error: String(e) }); } catch {}
    });
  }

  _ensureTmp(len) {
    if (!this._tmpOut || this._tmpOut.length !== len) this._tmpOut = new Float32Array(len);
  }

  async boot() {
    if (!this.rnnoiseWasmBytes) throw new Error('RNNoise: missing rnnoiseWasmBytes');

    const wasmBinary = new Uint8Array(this.rnnoiseWasmBytes);
    this.mod = await createRNNWasmModule({ wasmBinary, noInitialRun: true });

    const create = this.mod._rnnoise_create || this.mod.rnnoise_create;
    const process = this.mod._rnnoise_process_frame || this.mod.rnnoise_process_frame;
    if (!create || !process) throw new Error('RNNoise: exports missing');

    const st = create();
    if (!st) throw new Error('RNNoise: rnnoise_create returned 0');
    this.stPtr = st;

    const malloc = this.mod._malloc || this.mod.malloc;
    const free = this.mod._free || this.mod.free;
    if (!malloc || !free) throw new Error('RNNoise: malloc/free missing');

    this.inPtr = malloc(this.rnFrameLen * 4);
    this.outPtr = malloc(this.rnFrameLen * 4);
    this._free = free;
    this._process = process;

    const frameMs = 10;
    const atk = clamp(this.gateAttackMs, 5, 500);
    const rel = clamp(this.gateReleaseMs, 10, 2000);
    this._atkA = Math.exp(-frameMs / atk);
    this._relA = Math.exp(-frameMs / rel);

    this._floorLin = dbToLin(-Math.abs(this.floorDb));
    this.lpOut.set(this.inRate, this.lpHz);
    this.eqOut.set(this.inRate, this.eqHz, this.eqQ, this.eqGainDb);

    // Update band-cut EQ tuning
    const bcLow = Math.max(20, this.bandCutLowHz);
    const bcHigh = Math.max(bcLow + 1, this.bandCutHighHz);
    const bcCenter = Math.sqrt(bcLow * bcHigh);
    const bcQ = bcCenter / (bcHigh - bcLow);
    this.bandEq.set(this.inRate, bcCenter, bcQ, this.bandCutDb);

    // Update notch tuning
    const nf = (this.notchFreqs || []).slice(0, 4);
    this._notchCount = nf.length;
    this.notches.length = 0;
    for (let i = 0; i < nf.length; i++) {
      this.notches.push(new NotchFilter(this.inRate, nf[i], this.notchQ));
    }

    this.ready = true;
    try {
      this.port.postMessage({
        type: 'ready',
        inRate: this.inRate,
        inFrameLen: this.inFrameLen,
        hpHz: this.hpHz,
        lpHz: this.lpHz,
        enableLPF: this.enableLPF,
        eqEnable: this.eqEnable,
        eqHz: this.eqHz,
        eqQ: this.eqQ,
        eqGainDb: this.eqGainDb,
        bandCutEnable: this.bandCutEnable,
        bandCutLowHz: this.bandCutLowHz,
        bandCutHighHz: this.bandCutHighHz,
        bandCutDb: this.bandCutDb,
        bandCutDynamic: this.bandCutDynamic,
        notchEnable: this.notchEnable,
        notchFreqs: this.notchFreqs,
        notchQ: this.notchQ,
        notchDynamic: this.notchDynamic,
        vadThreshold: this.vadThreshold,
        floorDb: this.floorDb,
        aggressiveness: this.aggressiveness,
        energyGateDb: this.energyGateDb,
        disableGate: this.disableGate,
      });
    } catch {}
  }

  _vadToGate(vad) {
    const thr = this.vadThreshold;
    const knee = Math.max(0.01, this.vadKnee);
    const t = clamp((vad - (thr - knee)) / (2 * knee), 0, 1);
    const s = t * t * (3 - 2 * t);
    let g = this._floorLin + (1 - this._floorLin) * s;
    const p = clamp(this.aggressiveness, 1.0, 3.0);
    return Math.pow(g, p);
  }

  process(inputs, outputs) {
    const input = inputs[0] && inputs[0][0];
    const output = outputs[0] && outputs[0][0];
    if (!output) return true;

    if (!input) { output.fill(0); return true; }

    this.rbIn.push(input);

    if (!this.ready) {
      this._ensureTmp(output.length);
      const n = this.rbIn.pop(this._tmpOut);
      output.set(n ? this._tmpOut : input);
      return true;
    }

    while (this.rbIn.availableRead() >= this.inFrameLen && this.rbOut.availableWrite() >= this.inFrameLen) {
      this.rbIn.pop(this.inFrame);

      this.hpIn.processInPlace(this.inFrame);
      const eVad = energyVad(this.inFrame, this.energyGateDb);

      if (this.inRate === this.rnRate && this.inFrameLen === this.rnFrameLen) {
        this.rnIn.set(this.inFrame);
      } else {
        resampleLinear(this.inFrame, this.inRate, this.rnIn, this.rnRate);
      }

      const heapF32 = this.mod.HEAPF32;
      heapF32.set(this.rnIn, this.inPtr >> 2);

      let vad = 1.0;
      try {
        const ret = this._process(this.stPtr, this.outPtr, this.inPtr);
        if (typeof ret === 'number' && Number.isFinite(ret)) vad = clamp(ret, 0, 1);
      } catch {}

      vad = Math.max(vad, eVad);

      this.rnOut.set(heapF32.subarray(this.outPtr >> 2, (this.outPtr >> 2) + this.rnFrameLen));

      if (this.inRate === this.rnRate && this.inFrameLen === this.rnFrameLen) {
        this.outFrame.set(this.rnOut);
      } else {
        resampleLinear(this.rnOut, this.rnRate, this.outFrame, this.inRate);
      }

      let g = 1.0;
      if (!this.disableGate) {
        if (vad > this._vadSmooth) {
          this._vadSmooth = this._atkA * this._vadSmooth + (1 - this._atkA) * vad;
        } else {
          this._vadSmooth = this._relA * this._vadSmooth + (1 - this._relA) * vad;
        }

        const targetG = this._vadToGate(this._vadSmooth);
        const a = (targetG < this._gateGain) ? this._atkA : this._relA;
        this._gateGain = a * this._gateGain + (1 - a) * targetG;
        g = this._gateGain;
      }

      for (let i = 0; i < this.outFrame.length; i++) this.outFrame[i] *= g;

      // Post filters: HPF -> optional LPF -> optional band-cut -> optional EQ
      this.hpOut.processInPlace(this.outFrame);
      if (this.enableLPF) this.lpOut.processInPlace(this.outFrame);

      // Multi-notch for tonal peaks (e.g., ~1055Hz/~1219Hz). Very narrow, hurts speech less than wide cuts.
      // Dynamic mode: stronger when no speech.
      if (this.notchEnable && this._notchCount > 0) {
        const alphaN = this.notchDynamic ? clamp(1.0 - this._vadSmooth, 0.0, 1.0) : 1.0;
        if (alphaN > 0.001) {
          this._notchTmp.set(this.outFrame);
          for (let i = 0; i < this._notchCount; i++) this.notches[i].processInPlace(this.outFrame);
          const aN = alphaN, bN = 1.0 - alphaN;
          for (let i = 0; i < this.outFrame.length; i++) {
            this.outFrame[i] = bN * this._notchTmp[i] + aN * this.outFrame[i];
          }
        }
      }

      // Wide band-cut (e.g., 1570-6188Hz) to reduce fan noise.
      // Dynamic mode: applies stronger cut when no speech, weaker when speech to avoid "吞字/闷".
      if (this.bandCutEnable && this.bandCutDb !== 0) {
        const alpha = this.bandCutDynamic ? clamp(1.0 - this._vadSmooth, 0.0, 1.0) : 1.0;
        if (alpha > 0.001) {
          this._bandTmp.set(this.outFrame);
          this.bandEq.processInPlace(this.outFrame);
          // crossfade between original and band-cut output
          const a = alpha;
          const b = 1.0 - alpha;
          for (let i = 0; i < this.outFrame.length; i++) {
            this.outFrame[i] = b * this._bandTmp[i] + a * this.outFrame[i];
          }
        }
      }

      if (this.eqEnable && this.eqGainDb !== 0) this.eqOut.processInPlace(this.outFrame);

      this.rbOut.push(this.outFrame);
    }

    this._ensureTmp(output.length);
    const n = Math.min(output.length, this.rbOut.availableRead());
    if (n > 0) {
      this.rbOut.pop(this._tmpOut);
      output.set(this._tmpOut);
    } else {
      output.fill(0);
    }
    return true;
  }
}

registerProcessor('rnnoise-processor', RNNoiseProcessor);
