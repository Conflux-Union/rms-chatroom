"""Music Bot - Plays audio to LiveKit room."""
from __future__ import annotations

import asyncio
import logging
from typing import Callable

import av
import httpx
import numpy as np
from livekit import rtc
from livekit.api import AccessToken, VideoGrants

from ..core.config import get_settings

logger = logging.getLogger(__name__)
settings = get_settings()

# Audio settings
SAMPLE_RATE = 48000
NUM_CHANNELS = 2
SAMPLES_PER_CHANNEL = 960  # 20ms at 48kHz


class MusicBot:
    """Bot that plays music in a LiveKit room."""
    
    def __init__(self, room_name: str):
        self.room_name = room_name
        self.room: rtc.Room | None = None
        self.audio_source: rtc.AudioSource | None = None
        self.audio_track: rtc.LocalAudioTrack | None = None
        self._is_playing = False
        self._should_stop = False
        self._current_task: asyncio.Task | None = None
        self._on_song_end: Callable[[], None] | None = None
    
    def _generate_token(self) -> str:
        """Generate a token for the music bot."""
        token = AccessToken(
            api_key=settings.livekit_api_key,
            api_secret=settings.livekit_api_secret,
        )
        token.with_identity("MusicBot")
        token.with_name("Music Bot")
        token.with_grants(VideoGrants(
            room_join=True,
            room=self.room_name,
            can_publish=True,
            can_subscribe=False,
        ))
        return token.to_jwt()
    
    async def connect(self) -> bool:
        """Connect the bot to the LiveKit room."""
        try:
            self.room = rtc.Room()
            token = self._generate_token()
            
            await self.room.connect(settings.livekit_host, token)
            
            # Create audio source and track
            self.audio_source = rtc.AudioSource(SAMPLE_RATE, NUM_CHANNELS)
            self.audio_track = rtc.LocalAudioTrack.create_audio_track(
                "music", self.audio_source
            )
            
            # Publish the audio track
            options = rtc.TrackPublishOptions(source=rtc.TrackSource.SOURCE_MICROPHONE)
            await self.room.local_participant.publish_track(self.audio_track, options)
            
            logger.info(f"MusicBot connected to room: {self.room_name}")
            return True
            
        except Exception as e:
            logger.error(f"MusicBot connection failed: {e}")
            return False
    
    async def disconnect(self):
        """Disconnect the bot from the room."""
        self._should_stop = True
        
        if self._current_task:
            self._current_task.cancel()
            try:
                await self._current_task
            except asyncio.CancelledError:
                pass
        
        if self.room:
            await self.room.disconnect()
            self.room = None
        
        self.audio_source = None
        self.audio_track = None
        self._is_playing = False
        logger.info("MusicBot disconnected")
    
    async def play_url(self, url: str, on_end: Callable[[], None] | None = None):
        """Play audio from a URL."""
        if not self.room or not self.audio_source:
            logger.error("Bot not connected")
            return
        
        self._on_song_end = on_end
        self._should_stop = False
        self._is_playing = True
        
        self._current_task = asyncio.create_task(self._stream_audio(url))
        
        try:
            await self._current_task
        except asyncio.CancelledError:
            pass
        finally:
            self._is_playing = False
            if self._on_song_end and not self._should_stop:
                self._on_song_end()
    
    async def _stream_audio(self, url: str):
        """Stream audio from URL to LiveKit."""
        try:
            async with httpx.AsyncClient() as client:
                async with client.stream("GET", url, timeout=30.0) as response:
                    if response.status_code != 200:
                        logger.error(f"Failed to fetch audio: {response.status_code}")
                        return
                    
                    # Use av to decode audio stream
                    buffer = bytearray()
                    
                    async for chunk in response.aiter_bytes(chunk_size=8192):
                        if self._should_stop:
                            break
                        buffer.extend(chunk)
                        
                        # Process buffer when we have enough data
                        if len(buffer) > 32768:
                            await self._process_audio_buffer(bytes(buffer))
                            buffer.clear()
                    
                    # Process remaining buffer
                    if buffer and not self._should_stop:
                        await self._process_audio_buffer(bytes(buffer))
                        
        except Exception as e:
            logger.error(f"Error streaming audio: {e}")
    
    async def _process_audio_buffer(self, data: bytes):
        """Process audio buffer and send to LiveKit."""
        if not self.audio_source:
            return
        
        try:
            # Create a container from bytes
            container = av.open(av.io.BytesIO(data))
            
            # Find audio stream
            audio_stream = next(
                (s for s in container.streams if s.type == 'audio'), 
                None
            )
            
            if not audio_stream:
                return
            
            # Set up resampler
            resampler = av.audio.resampler.AudioResampler(
                format='s16',
                layout='stereo',
                rate=SAMPLE_RATE,
            )
            
            for frame in container.decode(audio=0):
                if self._should_stop:
                    break
                
                # Resample to our target format
                resampled_frames = resampler.resample(frame)
                
                for resampled_frame in resampled_frames:
                    if self._should_stop:
                        break
                    
                    # Convert to numpy array
                    audio_data = resampled_frame.to_ndarray()
                    
                    # Reshape for LiveKit (samples, channels)
                    if audio_data.ndim == 1:
                        audio_data = np.stack([audio_data, audio_data], axis=1)
                    elif audio_data.shape[0] == NUM_CHANNELS:
                        audio_data = audio_data.T
                    
                    # Send frames in chunks
                    for i in range(0, len(audio_data), SAMPLES_PER_CHANNEL):
                        if self._should_stop:
                            break
                        
                        chunk = audio_data[i:i + SAMPLES_PER_CHANNEL]
                        if len(chunk) < SAMPLES_PER_CHANNEL:
                            # Pad with zeros
                            padding = np.zeros(
                                (SAMPLES_PER_CHANNEL - len(chunk), NUM_CHANNELS),
                                dtype=np.int16
                            )
                            chunk = np.vstack([chunk, padding])
                        
                        # Create AudioFrame
                        frame = rtc.AudioFrame.create(
                            SAMPLE_RATE, NUM_CHANNELS, SAMPLES_PER_CHANNEL
                        )
                        np.copyto(
                            np.frombuffer(frame.data, dtype=np.int16).reshape(
                                SAMPLES_PER_CHANNEL, NUM_CHANNELS
                            ),
                            chunk.astype(np.int16)
                        )
                        
                        await self.audio_source.capture_frame(frame)
                        
                        # Small delay to prevent buffer overflow
                        await asyncio.sleep(0.018)  # ~18ms for 20ms frames
            
            container.close()
            
        except Exception as e:
            logger.error(f"Error processing audio: {e}")
    
    def stop(self):
        """Stop current playback."""
        self._should_stop = True
    
    @property
    def is_playing(self) -> bool:
        return self._is_playing


# Global bot instance per room
_bots: dict[str, MusicBot] = {}


async def get_or_create_bot(room_name: str) -> MusicBot:
    """Get or create a music bot for a room."""
    if room_name not in _bots:
        bot = MusicBot(room_name)
        if await bot.connect():
            _bots[room_name] = bot
        else:
            raise Exception("Failed to connect music bot")
    return _bots[room_name]


async def remove_bot(room_name: str):
    """Remove and disconnect a music bot."""
    if room_name in _bots:
        await _bots[room_name].disconnect()
        del _bots[room_name]
