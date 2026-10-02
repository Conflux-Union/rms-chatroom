type SampleMode = "passthrough" | "linear" | "average";

export default class MonoResampler {
  private sourceSampleRate: number;
  private targetSampleRate: number;
  private bufferSize: number;
  private sampleMode: SampleMode;
  private sampleRatio: number;
  private inverseSampleRatio: number;
  private carryWeight: number = 0;
  private carryOut: number = 0;
  private outBuffer: Float32Array;

  constructor(sourceSampleRate: number, targetSampleRate: number, bufferSize: number) {
    this.sourceSampleRate = sourceSampleRate;
    this.targetSampleRate = targetSampleRate;
    this.bufferSize = bufferSize;
    this.sampleRatio = this.targetSampleRate / this.sourceSampleRate;
    this.inverseSampleRatio = this.sourceSampleRate / this.targetSampleRate;
    if (this.sourceSampleRate === this.targetSampleRate) {
      this.sampleMode = "passthrough";
    } else if (this.sourceSampleRate > this.targetSampleRate) {
      this.sampleMode = "average";
    } else {
      this.sampleMode = "linear";
    }
    this.outBuffer = new Float32Array(Math.ceil(this.sampleRatio * this.bufferSize));
  }

  get carryOverWeight(): number {
    return this.carryWeight;
  }

  get carryOverVal(): number {
    return this.carryOut;
  }

  passthrough(buffer: Float32Array): Float32Array {
    return buffer;
  }

  linear(buffer: Float32Array): Float32Array {
    let weight = this.carryWeight;
    let futureWeight = weight % 1;
    let previousWeight = 1 - futureWeight;
    let sourceIndex = Math.floor(weight);
    let outputIndex = 0;
    let previousComponent: number = 0;
    let futureComponent: number = 0;

    if (weight != 0) {
      this.outBuffer[outputIndex++] = this.carryOut * previousWeight + buffer[sourceIndex] * futureWeight;
      weight += this.inverseSampleRatio - 1;
    }

    for (sourceIndex = Math.floor(weight); outputIndex < this.outBuffer.length && weight <= buffer.length - 1; weight += this.inverseSampleRatio, sourceIndex = Math.floor(weight)) {
      futureWeight = weight % 1;
      previousWeight = 1 - futureWeight;
      previousComponent = buffer[sourceIndex] * previousWeight;
      futureComponent = futureWeight != 0 ? buffer[sourceIndex + 1] * futureWeight : 0;
      this.outBuffer[outputIndex++] = previousComponent + futureComponent;
    }

    this.carryWeight = weight - sourceIndex;
    this.carryOut = buffer[sourceIndex] || 0.0;
    return this.outBuffer.slice(0, outputIndex);
  }

  average(buffer: Float32Array): Float32Array {
    let outputVal: number = 0;
    let weight: number = 0;
    let workingIndex: number = 0;
    let currentIndex: number = 0;
    let amountToNext: number = 0;
    let outputIndex: number = 0;

    do {
      outputVal = this.carryOut;
      if (this.carryWeight == 0) {
        weight = this.inverseSampleRatio;
      } else {
        weight = this.carryWeight;
        this.carryWeight = 0;
      }
      while (weight > 0 && workingIndex < buffer.length) {
        amountToNext = 1 + workingIndex - currentIndex;
        if (weight >= amountToNext) {
          outputVal += buffer[workingIndex++] * amountToNext;
          currentIndex = workingIndex;
          weight -= amountToNext;
        } else {
          outputVal += buffer[workingIndex + 1] * weight;
          currentIndex += weight;
          weight = 0;
          break;
        }
      }

      if (weight === 0) {
        this.outBuffer[outputIndex++] = outputVal / this.inverseSampleRatio;
      } else {
        this.carryWeight = weight;
        this.carryOut = outputVal;
        break;
      }
    } while (workingIndex < buffer.length && outputIndex < this.outBuffer.length);
    return this.outBuffer.slice(0, outputIndex);
  }

  resample(buffer: Float32Array): Float32Array {
    if (buffer.length == 0) {
      return buffer;
    }
    switch (this.sampleMode) {
      case "passthrough":
        return this.passthrough(buffer);
      case "linear":
        return this.linear(buffer);
      case "average":
        return this.average(buffer);
    }
  }
}
