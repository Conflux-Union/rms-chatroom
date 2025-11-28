"""Music Player - Full control audio playback using livekit-rtc AudioSource."""
from __future__ import annotations

import asyncio
import io
import logging
import time
from dataclasses import dataclass, field
from enum import Enum
from typing import Callable, Any

import httpx
import numpy as np
import av
from livekit import rtc, api

from ..core.config import get_settings

logger = logging.getLogger(__name__)
settings = get_settings()

SAMPLE_RATE = 48000
NUM_CHANNELS = 2
SAMPLES_PER_CHANNEL = 960  # 20ms at 48kHz


class PlayerState(Enum):
    IDLE = "idle"
    LOADING = "loading"
    PLAYING = "playing"
    PAUSED = "paused"
    STOPPED = "stopped"


class SongInfo:
    """Song metadata - not a dataclass to avoid field order issues."""
    def __init__(
        self,
        mid: str,
        name: str,
        artist: str,
        duration: int,
        url: str = "",
        album: str = "",
        cover: str = "",
    ):
        self.mid = mid
        self.name = name
        self.artist = artist
        self.duration = duration
        self.url = url
        self.album = album
        self.cover = cover


@dataclass
class PlaybackProgress:
    position_ms: int
    duration_ms: int
    state: str
    current_song: dict | None


class MusicPlayer:
    """Audio player with full playback control via livekit-rtc AudioSource."""

    def __init__(self, room_name: str):
        self.room_name = room_name
        self._state = PlayerState.IDLE
        self._audio_data: np.ndarray | None = None  # PCM int16 stereo interleaved
        self._position: int = 0  # current sample index
        self._total_samples: int = 0
        self._duration_ms: int = 0
        self._current_song: SongInfo | None = None

        # LiveKit room and audio source
        self._room: rtc.Room | None = None
        self._source: rtc.AudioSource | None = None
        self._track: rtc.LocalAudioTrack | None = None
        self._play_task: asyncio.Task | None = None

        # Callback for song finished
        self._on_finished: Callable[[], Any] | None = None

        # Progress broadcast callback
        self._on_progress: Callable[[PlaybackProgress], Any] | None = None

    @property
    def state(self) -> PlayerState:
        return self._state

    @property
    def is_playing(self) -> bool:
        return self._state == PlayerState.PLAYING

    @property
    def current_song(self) -> SongInfo | None:
        return self._current_song

    def set_on_finished(self, callback: Callable[[], Any]):
        self._on_finished = callback

    def set_on_progress(self, callback: Callable[[PlaybackProgress], Any]):
        self._on_progress = callback

    async def connect(self) -> bool:
        """Connect to LiveKit room as MusicBot participant."""
        if self._room and self._room.connection_state == rtc.ConnectionState.CONN_CONNECTED:
            return True

        try:
            self._room = rtc.Room()

            # Generate token for music bot
            token = (
                api.AccessToken(
                    api_key=settings.livekit_api_key,
                    api_secret=settings.livekit_api_secret,
                )
                .with_identity("MusicBot")
                .with_name("Music Bot")
                .with_grants(api.VideoGrants(
                    room_join=True,
                    room=self.room_name,
                    can_publish=True,
                    can_subscribe=False,
                ))
                .to_jwt()
            )

            # Use internal host for server-side RTC connection
            livekit_url = settings.livekit_internal_host or settings.livekit_host
            logger.info(f"Connecting to LiveKit: {livekit_url}")
            
            # Configure ICE with TURN server for server-side connectivity
            ice_servers = [
                rtc.IceServer(
                    urls=["turn:43.138.176.179:3478"],
                    username="livekit",
                    password="livekitturn123",
                ),
                rtc.IceServer(urls=["stun:43.138.176.179:3478"]),
            ]
            rtc_config = rtc.RtcConfiguration(ice_servers=ice_servers)
            room_options = rtc.RoomOptions(auto_subscribe=False, rtc_config=rtc_config)
            await self._room.connect(livekit_url, token, options=room_options)
            logger.info(f"MusicPlayer connected to room: {self.room_name}")

            # Create and publish audio source
            self._source = rtc.AudioSource(SAMPLE_RATE, NUM_CHANNELS)
            self._track = rtc.LocalAudioTrack.create_audio_track("music", self._source)

            options = rtc.TrackPublishOptions()
            options.source = rtc.TrackSource.SOURCE_MICROPHONE
            await self._room.local_participant.publish_track(self._track, options)

            logger.info("Audio track published")
            return True

        except Exception as e:
            logger.error(f"Failed to connect MusicPlayer: {e}")
            self._room = None
            return False

    async def disconnect(self):
        """Disconnect from LiveKit room."""
        await self.stop()
        if self._room:
            await self._room.disconnect()
            self._room = None
            self._source = None
            self._track = None
        logger.info("MusicPlayer disconnected")

    async def load(self, url: str, song_info: SongInfo) -> bool:
        """Download and decode audio to PCM."""
        self._state = PlayerState.LOADING
        self._current_song = song_info
        self._current_song.url = url

        try:
            # Download audio data
            logger.info(f"Downloading audio: {song_info.name}")
            async with httpx.AsyncClient(timeout=60.0) as client:
                response = await client.get(url)
                response.raise_for_status()
                audio_bytes = response.content

            # Decode to PCM using PyAV
            logger.info(f"Decoding audio ({len(audio_bytes)} bytes)")
            pcm_data = await asyncio.to_thread(self._decode_audio, audio_bytes)

            if pcm_data is None:
                logger.error("Failed to decode audio")
                self._state = PlayerState.IDLE
                return False

            self._audio_data = pcm_data
            self._total_samples = len(pcm_data) // NUM_CHANNELS
            self._duration_ms = int(self._total_samples / SAMPLE_RATE * 1000)
            self._position = 0
            self._state = PlayerState.STOPPED

            logger.info(
                f"Loaded: {song_info.name}, "
                f"duration={self._duration_ms}ms, "
                f"samples={self._total_samples}"
            )
            return True

        except Exception as e:
            logger.error(f"Failed to load audio: {e}")
            self._state = PlayerState.IDLE
            return False

    def _decode_audio(self, audio_bytes: bytes) -> np.ndarray | None:
        """Decode audio bytes to PCM int16 stereo at 48kHz."""
        try:
            logger.info(f"Opening audio container ({len(audio_bytes)} bytes)")
            container = av.open(io.BytesIO(audio_bytes))
            logger.info(f"Container opened, streams: {len(container.streams)}")
            
            resampler = av.audio.resampler.AudioResampler(
                format="s16",
                layout="stereo",
                rate=SAMPLE_RATE,
            )

            all_frames = []
            frame_count = 0
            for frame in container.decode(audio=0):
                resampled = resampler.resample(frame)
                for rf in resampled:
                    arr = rf.to_ndarray().flatten()
                    all_frames.append(arr)
                frame_count += 1

            container.close()
            logger.info(f"Decoded {frame_count} frames, {len(all_frames)} resampled frames")

            if not all_frames:
                return None

            return np.concatenate(all_frames).astype(np.int16)

        except Exception as e:
            logger.error(f"Decode error: {e}")
            return None

    async def play(self) -> bool:
        """Start or resume playback."""
        if self._audio_data is None:
            logger.warning("No audio loaded")
            return False

        if not await self.connect():
            return False

        if self._state == PlayerState.PLAYING:
            return True

        self._state = PlayerState.PLAYING

        # Cancel existing play task if any
        if self._play_task and not self._play_task.done():
            self._play_task.cancel()
            try:
                await self._play_task
            except asyncio.CancelledError:
                pass

        self._play_task = asyncio.create_task(self._playback_loop())
        logger.info(f"Started playing at position {self._position}")
        return True

    async def _playback_loop(self):
        """Main playback loop - push audio frames to LiveKit."""
        audio_frame = rtc.AudioFrame.create(SAMPLE_RATE, NUM_CHANNELS, SAMPLES_PER_CHANNEL)
        frame_data = np.frombuffer(audio_frame.data, dtype=np.int16)
        samples_per_frame = SAMPLES_PER_CHANNEL * NUM_CHANNELS
        frame_duration = SAMPLES_PER_CHANNEL / SAMPLE_RATE  # seconds

        last_progress_time = 0.0

        try:
            while self._state == PlayerState.PLAYING:
                start_idx = self._position * NUM_CHANNELS
                end_idx = start_idx + samples_per_frame

                if start_idx >= len(self._audio_data):
                    # Playback finished
                    logger.info("Playback finished")
                    self._state = PlayerState.STOPPED
                    self._position = 0
                    if self._on_finished:
                        asyncio.create_task(self._call_on_finished())
                    break

                # Copy audio data to frame buffer
                chunk = self._audio_data[start_idx:end_idx]
                if len(chunk) < samples_per_frame:
                    # Pad with silence if needed
                    padded = np.zeros(samples_per_frame, dtype=np.int16)
                    padded[:len(chunk)] = chunk
                    chunk = padded

                np.copyto(frame_data, chunk)
                await self._source.capture_frame(audio_frame)

                self._position += SAMPLES_PER_CHANNEL

                # Broadcast progress every 1 second
                now = time.time()
                if now - last_progress_time >= 1.0:
                    last_progress_time = now
                    if self._on_progress:
                        asyncio.create_task(self._broadcast_progress())

                # Small sleep to avoid busy loop (capture_frame has internal queuing)
                await asyncio.sleep(frame_duration * 0.5)

        except asyncio.CancelledError:
            logger.info("Playback loop cancelled")
            raise
        except Exception as e:
            logger.error(f"Playback error: {e}")
            self._state = PlayerState.STOPPED

    async def _call_on_finished(self):
        """Call on_finished callback safely."""
        try:
            result = self._on_finished()
            if asyncio.iscoroutine(result):
                await result
        except Exception as e:
            logger.error(f"on_finished callback error: {e}")

    async def _broadcast_progress(self):
        """Broadcast current progress."""
        try:
            progress = self.get_progress()
            result = self._on_progress(progress)
            if asyncio.iscoroutine(result):
                await result
        except Exception as e:
            logger.error(f"on_progress callback error: {e}")

    async def pause(self):
        """Pause playback."""
        if self._state != PlayerState.PLAYING:
            return

        self._state = PlayerState.PAUSED

        # Cancel play task
        if self._play_task and not self._play_task.done():
            self._play_task.cancel()
            try:
                await self._play_task
            except asyncio.CancelledError:
                pass

        # Clear audio queue to stop sound immediately
        if self._source:
            self._source.clear_queue()

        logger.info(f"Paused at position {self._position}")

    async def resume(self):
        """Resume from paused state."""
        if self._state == PlayerState.PAUSED:
            await self.play()

    async def stop(self):
        """Stop playback and reset position."""
        prev_state = self._state
        self._state = PlayerState.STOPPED

        if self._play_task and not self._play_task.done():
            self._play_task.cancel()
            try:
                await self._play_task
            except asyncio.CancelledError:
                pass

        if self._source:
            self._source.clear_queue()

        self._position = 0
        if prev_state != PlayerState.IDLE:
            logger.info("Playback stopped")

    def seek(self, position_ms: int):
        """Seek to position in milliseconds."""
        if self._audio_data is None:
            return

        # Calculate sample position
        target_sample = int(position_ms * SAMPLE_RATE / 1000)
        target_sample = max(0, min(target_sample, self._total_samples))
        self._position = target_sample

        # Clear audio queue to prevent hearing old buffered audio
        if self._source:
            self._source.clear_queue()

        logger.info(f"Seeked to {position_ms}ms (sample {target_sample})")

    def get_progress(self) -> PlaybackProgress:
        """Get current playback progress."""
        position_ms = int(self._position / SAMPLE_RATE * 1000) if self._audio_data is not None else 0
        return PlaybackProgress(
            position_ms=position_ms,
            duration_ms=self._duration_ms,
            state=self._state.value,
            current_song=self._current_song.__dict__ if self._current_song else None,
        )


# Global player instances per room
_players: dict[str, MusicPlayer] = {}


async def get_or_create_player(room_name: str) -> MusicPlayer:
    """Get or create a music player for a room."""
    if room_name not in _players:
        _players[room_name] = MusicPlayer(room_name)
    return _players[room_name]


async def remove_player(room_name: str):
    """Remove and disconnect a music player."""
    if room_name in _players:
        await _players[room_name].disconnect()
        del _players[room_name]


def get_player(room_name: str) -> MusicPlayer | None:
    """Get existing player without creating."""
    return _players.get(room_name)
