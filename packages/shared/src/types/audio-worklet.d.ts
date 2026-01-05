// 让 TS 在项目里认识 AudioWorklet 的全局环境（只做类型用，不影响运行）

declare const sampleRate: number;

declare class AudioWorkletProcessor {
  readonly port: MessagePort;
  constructor(options?: any);
  process(
    inputs: Float32Array[][],
    outputs: Float32Array[][],
    parameters: Record<string, Float32Array>
  ): boolean;
}

declare function registerProcessor(
  name: string,
  processorCtor: new (...args: any[]) => AudioWorkletProcessor
): void;

// 这个可有可无，但加了更舒服
interface AudioWorkletNodeOptions {
  processorOptions?: any;
}
