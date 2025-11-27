# TODOs

## Volume Amplification (>100%)

### Goal
Allow users to amplify audio volume beyond 100% (up to 300%) for participants in voice channels, similar to Discord's volume boost feature.

### Current Implementation
- Volume control uses `HTMLAudioElement.volume` property directly
- Works correctly for 0-100% range
- Maximum volume limited to 100%

### Attempted Solution
Used Web Audio API with `GainNode` to amplify audio:
```typescript
const ctx = new AudioContext()
const sourceNode = ctx.createMediaElementSource(audioElement)
const gainNode = ctx.createGain()
sourceNode.connect(gainNode)
gainNode.connect(ctx.destination)
gainNode.gain.value = volume / 100  // e.g., 2.0 for 200%
```

### Problems Encountered
1. **Audio quality degradation**: When using `createMediaElementSource()`, the audio quality noticeably decreased (sounded like "radio quality")
2. **Dual audio path**: The audio element continued playing directly while also routing through Web Audio API, causing volume control to not work properly (setting gain to 0 still had sound)
3. **Muting breaks audio**: Setting `audioElement.muted = true` or `audioElement.volume = 0` after `createMediaElementSource()` completely silences the Web Audio API output as well

### Possible Solutions to Explore
1. **MediaStream approach**: Use `captureStream()` instead of `createMediaElementSource()`
2. **Server-side gain**: Apply volume amplification on the LiveKit server or Ingress
3. **WebRTC track manipulation**: Modify the audio track gain before it reaches the audio element
4. **Alternative Web Audio setup**: Research if there's a way to properly isolate the audio routing

### References
- [Web Audio API - MDN](https://developer.mozilla.org/en-US/docs/Web/API/Web_Audio_API)
- [MediaElementAudioSourceNode](https://developer.mozilla.org/en-US/docs/Web/API/MediaElementAudioSourceNode)
- [LiveKit Audio Processing](https://docs.livekit.io/guides/room/receive/#audio-processing)
