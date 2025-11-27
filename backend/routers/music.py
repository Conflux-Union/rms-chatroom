"""QQ Music integration router - search, login, and playback control."""
from __future__ import annotations

import asyncio
import base64
import logging
from typing import Any

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from qqmusic_api import search, song, login
from qqmusic_api.login import QRLoginType, QRCodeLoginEvents, Credential

from .deps import get_current_user, CurrentUser
from ..services.music_bot import get_or_create_bot, remove_bot, MusicBot

logger = logging.getLogger(__name__)


router = APIRouter(prefix="/api/music", tags=["music"])

# Global state for QQ Music credential and playback
_credential: Credential | None = None
_current_qr: login.QR | None = None
_play_queue: list[dict[str, Any]] = []
_current_index: int = 0
_is_playing: bool = False
_current_room: str | None = None
_music_bot: MusicBot | None = None


# --- Models ---

class SearchRequest(BaseModel):
    keyword: str
    num: int = 20


class SongInfo(BaseModel):
    mid: str
    name: str
    artist: str
    album: str
    duration: int
    cover: str


class QueueItem(BaseModel):
    song: SongInfo
    requested_by: str


class PlaybackState(BaseModel):
    is_playing: bool
    current_song: SongInfo | None
    queue: list[QueueItem]
    position: int


# --- Login APIs ---

@router.get("/login/qrcode")
async def get_login_qrcode():
    """Generate QQ login QR code."""
    global _current_qr
    try:
        _current_qr = await login.get_qrcode(QRLoginType.WX)
        qr_base64 = base64.b64encode(_current_qr.data).decode('utf-8')
        return {
            "qrcode": f"data:{_current_qr.mimetype};base64,{qr_base64}",
            "type": "wechat"
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to get QR code: {e}")


@router.get("/login/status")
async def check_login_status():
    """Check QR code login status."""
    global _credential, _current_qr
    
    if not _current_qr:
        raise HTTPException(status_code=400, detail="No QR code generated")
    
    try:
        status, cred = await login.check_qrcode(_current_qr)
        
        status_map = {
            QRCodeLoginEvents.SCAN: "waiting",
            QRCodeLoginEvents.CONF: "scanned",
            QRCodeLoginEvents.TIMEOUT: "expired",
            QRCodeLoginEvents.DONE: "success",
            QRCodeLoginEvents.REFUSE: "refused",
            QRCodeLoginEvents.OTHER: "unknown",
        }
        
        result = {"status": status_map.get(status, "unknown")}
        
        if status == QRCodeLoginEvents.DONE and cred:
            _credential = cred
            result["logged_in"] = True
            
        return result
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to check status: {e}")


@router.get("/login/check")
async def check_logged_in():
    """Check if QQ Music is logged in."""
    global _credential
    
    if not _credential:
        return {"logged_in": False}
    
    try:
        expired = await login.check_expired(_credential)
        return {"logged_in": not expired}
    except Exception:
        return {"logged_in": False}


@router.post("/login/logout")
async def logout():
    """Clear QQ Music credential."""
    global _credential
    _credential = None
    return {"success": True}


# --- Search APIs ---

@router.post("/search")
async def search_songs(req: SearchRequest, _user: CurrentUser):
    """Search for songs on QQ Music."""
    try:
        results = await search.search_by_type(keyword=req.keyword, num=req.num)
        
        # results is a list directly
        song_list = results if isinstance(results, list) else results.get("list", [])
        
        songs = []
        for item in song_list:
            singer_names = [s.get("name", "") for s in item.get("singer", [])]
            album_info = item.get("album", {}) or {}
            songs.append({
                "mid": item.get("mid", ""),
                "name": item.get("name", ""),
                "artist": ", ".join(singer_names),
                "album": album_info.get("name", ""),
                "duration": item.get("interval", 0),
                "cover": f"https://y.qq.com/music/photo_new/T002R300x300M000{album_info.get('mid', '')}.jpg"
            })
        
        return {"songs": songs}
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Search failed: {e}")


@router.get("/song/{mid}/url")
async def get_song_url(mid: str, _user: CurrentUser):
    """Get playable URL for a song."""
    global _credential
    
    try:
        # Try to get high quality with credential, fallback to MP3_128
        file_type = song.SongFileType.MP3_320 if _credential else song.SongFileType.MP3_128
        urls = await song.get_song_urls([mid], file_type=file_type, credential=_credential)
        
        url = urls.get(mid, "")
        if not url:
            # Fallback to lower quality
            urls = await song.get_song_urls([mid], file_type=song.SongFileType.MP3_128)
            url = urls.get(mid, "")
        
        if not url:
            raise HTTPException(status_code=404, detail="Song URL not available")
        
        return {"url": url, "mid": mid}
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to get song URL: {e}")


# --- Queue Management APIs ---

@router.post("/queue/add")
async def add_to_queue(song_info: SongInfo, user: CurrentUser):
    """Add a song to the play queue."""
    global _play_queue
    
    _play_queue.append({
        "song": song_info.model_dump(),
        "requested_by": user.get("username", "Unknown")
    })
    
    return {"success": True, "position": len(_play_queue)}


@router.delete("/queue/{index}")
async def remove_from_queue(index: int, _user: CurrentUser):
    """Remove a song from the queue."""
    global _play_queue, _current_index
    
    if index < 0 or index >= len(_play_queue):
        raise HTTPException(status_code=400, detail="Invalid index")
    
    _play_queue.pop(index)
    
    if index < _current_index:
        _current_index -= 1
    elif index == _current_index and _current_index >= len(_play_queue):
        _current_index = max(0, len(_play_queue) - 1)
    
    return {"success": True}


@router.get("/queue")
async def get_queue(_user: CurrentUser):
    """Get current play queue."""
    global _play_queue, _current_index, _is_playing
    
    current_song = None
    if _play_queue and 0 <= _current_index < len(_play_queue):
        current_song = _play_queue[_current_index]["song"]
    
    return {
        "is_playing": _is_playing,
        "current_song": current_song,
        "current_index": _current_index,
        "queue": _play_queue
    }


@router.post("/queue/clear")
async def clear_queue(_user: CurrentUser):
    """Clear the play queue."""
    global _play_queue, _current_index, _is_playing
    
    _play_queue = []
    _current_index = 0
    _is_playing = False
    
    return {"success": True}


# --- Playback Control APIs ---

@router.post("/control/play")
async def play(_user: CurrentUser):
    """Start/resume playback."""
    global _is_playing
    _is_playing = True
    return {"success": True, "is_playing": True}


@router.post("/control/pause")
async def pause(_user: CurrentUser):
    """Pause playback."""
    global _is_playing
    _is_playing = False
    return {"success": True, "is_playing": False}


@router.post("/control/skip")
async def skip(_user: CurrentUser):
    """Skip to next song."""
    global _current_index, _play_queue
    
    if _current_index < len(_play_queue) - 1:
        _current_index += 1
        return {"success": True, "current_index": _current_index}
    
    return {"success": False, "message": "No more songs in queue"}


@router.post("/control/previous")
async def previous(_user: CurrentUser):
    """Go to previous song."""
    global _current_index
    
    if _current_index > 0:
        _current_index -= 1
        return {"success": True, "current_index": _current_index}
    
    return {"success": False, "message": "Already at first song"}


# --- Bot Control APIs ---

class BotStartRequest(BaseModel):
    room_name: str


@router.post("/bot/start")
async def start_bot(req: BotStartRequest, _user: CurrentUser):
    """Start music bot for a voice channel room (uses Ingress)."""
    global _music_bot, _current_room
    
    try:
        # Disconnect existing bot if any
        if _music_bot:
            await _music_bot.disconnect()
        
        # Create bot instance (no WebRTC connection needed, uses Ingress)
        _music_bot = await get_or_create_bot(req.room_name)
        _current_room = req.room_name
        
        return {"success": True, "room": req.room_name}
    except Exception as e:
        logger.error(f"Failed to start bot: {e}")
        raise HTTPException(status_code=500, detail=f"Failed to start bot: {e}")


@router.post("/bot/stop")
async def stop_bot(_user: CurrentUser):
    """Stop the music bot."""
    global _music_bot, _current_room, _is_playing
    
    if _music_bot:
        await _music_bot.disconnect()
        _music_bot = None
        _current_room = None
        _is_playing = False
    
    return {"success": True}


@router.get("/bot/status")
async def get_bot_status(_user: CurrentUser):
    """Get music bot status."""
    global _music_bot, _current_room, _is_playing
    
    return {
        "connected": _music_bot is not None,
        "room": _current_room,
        "is_playing": _is_playing and _music_bot and _music_bot.is_playing
    }


class BotPlayRequest(BaseModel):
    room_name: str


@router.post("/bot/play")
async def bot_play(req: BotPlayRequest, _user: CurrentUser):
    """Start playing the current song through the bot via Ingress."""
    global _music_bot, _play_queue, _current_index, _is_playing, _credential, _current_room
    
    # Auto-create bot if not exists or room changed
    if not _music_bot or _current_room != req.room_name:
        if _music_bot:
            await _music_bot.disconnect()
        _music_bot = await get_or_create_bot(req.room_name)
        _current_room = req.room_name
    
    if not _play_queue or _current_index >= len(_play_queue):
        raise HTTPException(status_code=400, detail="No song in queue")
    
    current_song = _play_queue[_current_index]["song"]
    
    # Get song URL
    try:
        file_type = song.SongFileType.MP3_320 if _credential else song.SongFileType.MP3_128
        urls = await song.get_song_urls([current_song["mid"]], file_type=file_type, credential=_credential)
        url = urls.get(current_song["mid"], "")
        
        if not url:
            urls = await song.get_song_urls([current_song["mid"]], file_type=song.SongFileType.MP3_128)
            url = urls.get(current_song["mid"], "")
        
        if not url:
            raise HTTPException(status_code=404, detail="Song URL not available")
        
        # Play via Ingress URL Input
        success = await _music_bot.play_url(url, current_song["name"])
        
        if success:
            _is_playing = True
            return {"success": True, "playing": current_song["name"]}
        else:
            raise HTTPException(status_code=500, detail="Failed to start playback")
        
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to play: {e}")
        raise HTTPException(status_code=500, detail=f"Failed to play: {e}")


@router.post("/bot/pause")
async def bot_pause(_user: CurrentUser):
    """Pause the bot playback."""
    global _music_bot, _is_playing
    
    if _music_bot:
        await _music_bot.stop()
        _is_playing = False
    
    return {"success": True}


@router.post("/bot/skip")
async def bot_skip(_user: CurrentUser):
    """Skip to next song on the bot."""
    global _music_bot, _current_index, _play_queue, _is_playing
    
    if _music_bot:
        await _music_bot.stop()
    
    if _current_index < len(_play_queue) - 1:
        _current_index += 1
        if _music_bot and _is_playing:
            return await bot_play(_user)
        return {"success": True, "current_index": _current_index}
    
    _is_playing = False
    return {"success": False, "message": "No more songs in queue"}
