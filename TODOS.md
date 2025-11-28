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

---

## Music Player Enhancement (Progress Bar, Pause/Resume, Queue)

### Goal
实现完整的音乐播放器功能：
1. 暂停后可以从当前进度继续播放
2. 播放完一首自动播放队列中的下一首
3. 进度条显示和拖动 seek

### Current Implementation (Ingress)
当前使用 LiveKit Ingress URL Input 方案：
- 通过 `CreateIngressRequest` 推送音频 URL 到房间
- Ingress 服务下载音频并转码推流

### Problems with Ingress
1. **单向流** - 不支持 seek/pause/resume
2. **无进度信息** - Ingress 不返回播放进度
3. **无结束事件** - 不知道何时播放完成，无法自动下一首

### Proposed Solution: livekit-rtc AudioSource

使用 `livekit-rtc` Python SDK 直接控制音频播放：

```python
# 核心 API
source = rtc.AudioSource(sample_rate=48000, num_channels=2)
track = rtc.LocalAudioTrack.create_audio_track("music", source)
await room.local_participant.publish_track(track, options)

# 推送音频帧
frame = rtc.AudioFrame.create(sample_rate, num_channels, samples_per_channel)
np.copyto(np.frombuffer(frame.data, dtype=np.int16), audio_data)
await source.capture_frame(frame)

# 控制方法
source.clear_queue()      # 清空队列（用于暂停/seek）
source.queued_duration    # 队列中剩余音频时长
```

### Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    MusicPlayer                          │
├─────────────────────────────────────────────────────────┤
│ - audio_data: bytes (PCM)    # 完整音频数据             │
│ - position: int              # 当前播放位置（样本数）    │
│ - duration: float            # 总时长                   │
│ - is_playing: bool           # 播放状态                 │
│ - source: AudioSource        # LiveKit 音频源           │
├─────────────────────────────────────────────────────────┤
│ + load(url) → 下载并解码为 PCM                          │
│ + play() → 从 position 开始推送帧                       │
│ + pause() → 停止推送，clear_queue()                     │
│ + resume() → 从暂停位置继续                             │
│ + seek(ms) → 更新 position                              │
│ + get_progress() → 返回当前进度                         │
│ + on_finished() → 播放结束回调（触发下一首）            │
└─────────────────────────────────────────────────────────┘
```

### Potential Issues

| 问题 | 严重程度 | 说明 |
|------|---------|------|
| `capture_frame` 性能 | ⚠️ 中 | GitHub Issue #209 报告有 6-30ms 延迟，但不影响播放质量 |
| 需要下载完整音频 | ⚠️ 中 | 比 ingress 启动更慢，但支持 seek |
| CPU 占用 | ✅ 低 | 只是推送 PCM，不需要转码 |
| 服务器内存 | ⚠️ 中 | 需要在内存中保存音频数据（一首歌约 10-40MB） |
| 复杂度 | ⚠️ 中 | 需要自己管理播放循环、状态同步 |

### Implementation Tasks

**后端核心**
- [ ] 创建 MusicPlayer 类 - 基于 livekit-rtc AudioSource
- [ ] 实现 load(url) - 下载音频并用 pydub/ffmpeg 转为 PCM
- [ ] 实现 play() - 从当前 position 开始循环推送 AudioFrame
- [ ] 实现 pause() - 停止推送循环，调用 source.clear_queue()
- [ ] 实现 resume() - 从暂停位置继续播放
- [ ] 实现 seek(position_ms) - 更新 position 并 clear_queue
- [ ] 实现 get_progress() - 返回当前播放进度
- [ ] 实现 on_finished 回调 - 播放结束时触发下一首

**后端 API**
- [ ] 修改 music.py router - 添加 seek/progress API 端点
- [ ] 添加 WebSocket 推送 - 实时推送播放进度到前端
- [ ] 修改队列逻辑 - 播放结束自动播放下一首

**前端**
- [ ] 添加进度条 UI 组件
- [ ] 实现进度条拖动 seek 功能
- [ ] WebSocket 接收进度更新

**测试**
- [ ] 验证 play/pause/resume/seek 功能
- [ ] 验证队列自动播放下一首
- [ ] 部署到服务器并测试

### References
- [livekit-rtc AudioSource API](https://docs.livekit.io/reference/python/livekit/rtc/audio_source.html)
- [GitHub Issue #258 - Publish audio from WAV file](https://github.com/livekit/python-sdks/issues/258)
- [GitHub Issue #209 - capture_frame performance](https://github.com/livekit/python-sdks/issues/209)
- [livekit/python-sdks examples](https://github.com/livekit/python-sdks/tree/main/examples)
