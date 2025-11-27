"""Music Bot - Plays audio to LiveKit room via Ingress URL Input."""
from __future__ import annotations

import logging
from livekit.api import LiveKitAPI, IngressInfo
from livekit.protocol.ingress import (
    CreateIngressRequest,
    DeleteIngressRequest,
    IngressInput,
    IngressAudioOptions,
    IngressAudioEncodingOptions,
)
from livekit.protocol.models import AudioCodec

from ..core.config import get_settings

logger = logging.getLogger(__name__)
settings = get_settings()


class MusicBot:
    """Bot that plays music in a LiveKit room via Ingress."""
    
    def __init__(self, room_name: str):
        self.room_name = room_name
        self._current_ingress_id: str | None = None
        self._is_playing = False
        self._api = LiveKitAPI(
            url=settings.livekit_internal_host or settings.livekit_host,
            api_key=settings.livekit_api_key,
            api_secret=settings.livekit_api_secret,
        )
    
    async def play_url(self, url: str, song_name: str = "Music") -> bool:
        """Play audio from a URL using Ingress."""
        try:
            # Stop any existing playback
            await self.stop()
            
            # Create URL input ingress with high quality audio (256kbps stereo)
            request = CreateIngressRequest(
                input_type=IngressInput.URL_INPUT,
                url=url,
                name=f"music-{song_name[:20]}",
                room_name=self.room_name,
                participant_identity="MusicBot",
                participant_name="Music Bot",
                audio=IngressAudioOptions(
                    name="music-audio",
                    options=IngressAudioEncodingOptions(
                        audio_codec=AudioCodec.OPUS,
                        bitrate=256000,  # 256kbps
                        channels=2,      # stereo
                        disable_dtx=True,
                    ),
                ),
            )
            
            info: IngressInfo = await self._api.ingress.create_ingress(request)
            self._current_ingress_id = info.ingress_id
            self._is_playing = True
            
            logger.info(f"Started playing: {song_name} (ingress: {info.ingress_id})")
            return True
            
        except Exception as e:
            logger.error(f"Failed to play URL: {e}")
            self._is_playing = False
            return False
    
    async def stop(self):
        """Stop current playback."""
        if self._current_ingress_id:
            try:
                request = DeleteIngressRequest(ingress_id=self._current_ingress_id)
                await self._api.ingress.delete_ingress(request)
                logger.info(f"Stopped ingress: {self._current_ingress_id}")
            except Exception as e:
                logger.warning(f"Failed to delete ingress: {e}")
            finally:
                self._current_ingress_id = None
                self._is_playing = False
    
    async def disconnect(self):
        """Cleanup and disconnect."""
        await self.stop()
    
    @property
    def is_playing(self) -> bool:
        return self._is_playing


# Global bot instance per room
_bots: dict[str, MusicBot] = {}


async def get_or_create_bot(room_name: str) -> MusicBot:
    """Get or create a music bot for a room."""
    if room_name not in _bots:
        _bots[room_name] = MusicBot(room_name)
    return _bots[room_name]


async def remove_bot(room_name: str):
    """Remove and disconnect a music bot."""
    if room_name in _bots:
        await _bots[room_name].disconnect()
        del _bots[room_name]
