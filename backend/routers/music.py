"""QQ Music integration router - search, login, and playback control."""
from __future__ import annotations

import asyncio
import base64
import dataclasses
import json
import logging
from pathlib import Path
from typing import Any

import httpx
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from qqmusic_api import search, song, login
from qqmusic_api.login import QRLoginType, QRCodeLoginEvents, Credential

from .deps import get_current_user, CurrentUser

# Go music service URL
MUSIC_SERVICE_URL = "http://127.0.0.1:9100"

# Credential persistence file
CREDENTIAL_FILE = Path(__file__).parent.parent / "qq_credential.json"

logger = logging.getLogger(__name__)


router = APIRouter(prefix="/api/music", tags=["music"])

# Global state for QQ Music credential and playback
_credential: Credential | None = None
_current_qr: login.QR | None = None
_play_queue: list[dict[str, Any]] = []
_current_index: int = 0
_current_room: str | None = None

# WebSocket broadcast function (set by websocket module)
_ws_broadcast: Any = None


async def _broadcast_playback_state() -> None:
    """Broadcast current playback state to all connected clients."""
    global _ws_broadcast, _current_room, _play_queue, _current_index
    
    if not _ws_broadcast:
        return
    
    try:
        # Get progress from Go service
        progress_data = {
            "position_ms": 0,
            "duration_ms": 0,
            "state": "idle",
            "current_song": None,
            "current_index": _current_index,
            "queue_length": len(_play_queue),
        }
        
        if _current_room:
            try:
                progress = await _call_music_service("GET", "/progress", {"room_name": _current_room})
                progress_data["position_ms"] = progress.get("position_ms", 0)
                progress_data["duration_ms"] = progress.get("duration_ms", 0)
                progress_data["state"] = progress.get("state", "idle")
                progress_data["current_song"] = progress.get("song")
            except Exception:
                pass
        
        # If no song from Go service, use queue info
        if not progress_data["current_song"] and _play_queue and 0 <= _current_index < len(_play_queue):
            progress_data["current_song"] = _play_queue[_current_index]["song"]
        
        await _ws_broadcast("music_state", progress_data)
    except Exception as e:
        logger.error(f"Failed to broadcast playback state: {e}")


def _save_credential(cred: Credential | None) -> None:
    """Save credential to file."""
    try:
        if cred is None:
            CREDENTIAL_FILE.unlink(missing_ok=True)
        else:
            data = dataclasses.asdict(cred)
            CREDENTIAL_FILE.write_text(json.dumps(data, ensure_ascii=False))
        logger.info(f"Credential saved: {cred is not None}")
    except Exception as e:
        logger.error(f"Failed to save credential: {e}")


def _load_credential() -> Credential | None:
    """Load credential from file."""
    try:
        if CREDENTIAL_FILE.exists():
            data = json.loads(CREDENTIAL_FILE.read_text())
            cred = Credential(**data)
            logger.info("Credential loaded from file")
            return cred
    except Exception as e:
        logger.error(f"Failed to load credential: {e}")
    return None


# Load credential on module import
_credential = _load_credential()


def set_ws_broadcast(broadcast_func):
    """Set WebSocket broadcast function for progress updates."""
    global _ws_broadcast
    _ws_broadcast = broadcast_func


# --- Go Music Service Client ---

async def _call_music_service(method: str, endpoint: str, json_data: dict | None = None) -> dict:
    """Call the Go music service."""
    async with httpx.AsyncClient(timeout=5.0) as client:
        url = f"{MUSIC_SERVICE_URL}{endpoint}"
        if method == "GET":
            resp = await client.get(url, params=json_data)
        else:
            resp = await client.post(url, json=json_data)
        
        if resp.status_code != 200:
            raise HTTPException(status_code=resp.status_code, detail=resp.text)
        
        return resp.json()


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
            _save_credential(cred)
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
    _save_credential(None)
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
    global _play_queue, _current_index, _current_room
    
    current_song = None
    if _play_queue and 0 <= _current_index < len(_play_queue):
        current_song = _play_queue[_current_index]["song"]
    
    # Get actual playing state from music service
    is_playing = False
    if _current_room:
        try:
            progress = await _call_music_service("GET", "/progress", {"room_name": _current_room})
            is_playing = progress.get("state") == "playing"
        except Exception:
            pass
    
    return {
        "is_playing": is_playing,
        "current_song": current_song,
        "current_index": _current_index,
        "queue": _play_queue
    }


@router.post("/queue/clear")
async def clear_queue(_user: CurrentUser):
    """Clear the play queue."""
    global _play_queue, _current_index, _current_room
    
    if _current_room:
        try:
            await _call_music_service("POST", "/stop", {"room_name": _current_room})
        except Exception:
            pass
    
    _play_queue = []
    _current_index = 0
    
    return {"success": True}


# --- Playback Control APIs ---

@router.post("/control/play")
async def play(_user: CurrentUser):
    """Start/resume playback."""
    global _current_room
    if _current_room:
        try:
            await _call_music_service("POST", "/resume", {"room_name": _current_room})
        except Exception:
            pass
    return {"success": True}


@router.post("/control/pause")
async def pause(_user: CurrentUser):
    """Pause playback."""
    global _current_room
    if _current_room:
        try:
            await _call_music_service("POST", "/pause", {"room_name": _current_room})
        except Exception:
            pass
    return {"success": True, "is_playing": False}


@router.post("/control/skip")
async def skip(_user: CurrentUser):
    """Skip to next song."""
    global _current_index, _play_queue, _current_room
    
    if _current_room:
        try:
            await _call_music_service("POST", "/stop", {"room_name": _current_room})
        except Exception:
            pass
    
    if _current_index < len(_play_queue) - 1:
        _current_index += 1
        return {"success": True, "current_index": _current_index}
    
    return {"success": False, "message": "No more songs in queue"}


@router.post("/control/previous")
async def previous(_user: CurrentUser):
    """Go to previous song."""
    global _current_index, _current_room
    
    if _current_room:
        try:
            await _call_music_service("POST", "/stop", {"room_name": _current_room})
        except Exception:
            pass
    
    if _current_index > 0:
        _current_index -= 1
        return {"success": True, "current_index": _current_index}
    
    return {"success": False, "message": "Already at first song"}


# --- Bot Control APIs ---

class BotStartRequest(BaseModel):
    room_name: str


async def _play_current_song() -> bool:
    """Internal: load and play current song via Go service."""
    global _play_queue, _current_index, _credential, _current_room
    
    if not _current_room or not _play_queue or _current_index >= len(_play_queue):
        return False
    
    current = _play_queue[_current_index]["song"]
    
    # Get song URL
    try:
        file_type = song.SongFileType.MP3_320 if _credential else song.SongFileType.MP3_128
        urls = await song.get_song_urls([current["mid"]], file_type=file_type, credential=_credential)
        url = urls.get(current["mid"], "")
        
        if not url:
            urls = await song.get_song_urls([current["mid"]], file_type=song.SongFileType.MP3_128)
            url = urls.get(current["mid"], "")
        
        if not url:
            logger.error(f"Song URL not available: {current['name']}")
            return False
        
        # Call Go music service to play
        await _call_music_service("POST", "/play", {
            "room_name": _current_room,
            "song": {
                "mid": current["mid"],
                "name": current["name"],
                "artist": current["artist"],
                "duration": current["duration"],
                "url": url,
            }
        })
        
        return True
        
    except Exception as e:
        logger.error(f"Failed to play song: {e}")
        return False


@router.post("/bot/start")
async def start_bot(req: BotStartRequest, _user: CurrentUser):
    """Start music bot for a voice channel room."""
    global _current_room
    
    try:
        # Stop existing player if room changed
        if _current_room and _current_room != req.room_name:
            try:
                await _call_music_service("POST", "/stop", {"room_name": _current_room})
            except Exception:
                pass
        
        _current_room = req.room_name
        return {"success": True, "room": req.room_name}
    except Exception as e:
        logger.error(f"Failed to start bot: {e}")
        raise HTTPException(status_code=500, detail=f"Failed to start bot: {e}")


@router.post("/bot/stop")
async def stop_bot(_user: CurrentUser):
    """Stop the music bot."""
    global _current_room
    
    if _current_room:
        try:
            await _call_music_service("POST", "/stop", {"room_name": _current_room})
        except Exception:
            pass
        _current_room = None
    
    return {"success": True}


@router.get("/bot/status")
async def get_bot_status(_user: CurrentUser):
    """Get music bot status."""
    global _current_room
    
    is_playing = False
    if _current_room:
        try:
            progress = await _call_music_service("GET", "/progress", {"room_name": _current_room})
            is_playing = progress.get("state") == "playing"
        except Exception:
            pass
    
    return {
        "connected": _current_room is not None,
        "room": _current_room,
        "is_playing": is_playing
    }


class BotPlayRequest(BaseModel):
    room_name: str


class SeekRequest(BaseModel):
    position_ms: int


@router.post("/bot/play")
async def bot_play(req: BotPlayRequest, _user: CurrentUser):
    """Start playing the current song through the bot."""
    global _play_queue, _current_index, _current_room
    
    _current_room = req.room_name
    
    if not _play_queue or _current_index >= len(_play_queue):
        raise HTTPException(status_code=400, detail="No song in queue")
    
    current_song = _play_queue[_current_index]["song"]
    
    try:
        success = await _play_current_song()
        
        if success:
            # Broadcast state change
            asyncio.create_task(_broadcast_playback_state())
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
    global _current_room
    
    if _current_room:
        try:
            await _call_music_service("POST", "/pause", {"room_name": _current_room})
            # Broadcast state change
            asyncio.create_task(_broadcast_playback_state())
        except Exception:
            pass
    
    return {"success": True}


@router.post("/bot/resume")
async def bot_resume(_user: CurrentUser):
    """Resume the bot playback."""
    global _current_room
    
    if _current_room:
        try:
            await _call_music_service("POST", "/resume", {"room_name": _current_room})
            # Broadcast state change
            asyncio.create_task(_broadcast_playback_state())
            return {"success": True, "is_playing": True}
        except Exception:
            pass
    
    return {"success": False, "message": "No player"}


@router.post("/bot/skip")
async def bot_skip(_user: CurrentUser):
    """Skip to next song on the bot."""
    global _current_index, _play_queue, _current_room
    
    if _current_room:
        try:
            await _call_music_service("POST", "/stop", {"room_name": _current_room})
        except Exception:
            pass
    
    if _current_index < len(_play_queue) - 1:
        _current_index += 1
        if _current_room:
            success = await _play_current_song()
            # Broadcast state change
            asyncio.create_task(_broadcast_playback_state())
            if success:
                return {"success": True, "current_index": _current_index}
        return {"success": True, "current_index": _current_index}
    
    # Broadcast when queue ends
    asyncio.create_task(_broadcast_playback_state())
    return {"success": False, "message": "No more songs in queue"}


@router.post("/bot/seek")
async def bot_seek(req: SeekRequest, _user: CurrentUser):
    """Seek to position in the current song."""
    global _current_room
    
    if not _current_room:
        raise HTTPException(status_code=400, detail="No player")
    
    await _call_music_service("POST", "/seek", {
        "room_name": _current_room,
        "position_ms": req.position_ms
    })
    return {"success": True, "position_ms": req.position_ms}


@router.get("/bot/progress")
async def bot_progress(_user: CurrentUser):
    """Get current playback progress."""
    global _current_room
    
    if not _current_room:
        return {
            "position_ms": 0,
            "duration_ms": 0,
            "state": "idle",
            "current_song": None,
        }
    
    try:
        progress = await _call_music_service("GET", "/progress", {"room_name": _current_room})
        return {
            "position_ms": progress.get("position_ms", 0),
            "duration_ms": progress.get("duration_ms", 0),
            "state": progress.get("state", "idle"),
            "current_song": progress.get("song"),
        }
    except Exception:
        return {
            "position_ms": 0,
            "duration_ms": 0,
            "state": "idle",
            "current_song": None,
        }


# --- Internal Callback APIs (called by Go music service) ---

class SongEndedRequest(BaseModel):
    room_name: str


@router.post("/internal/song-ended")
async def handle_song_ended(req: SongEndedRequest):
    """
    Callback from Go music service when a song finishes playing.
    Automatically plays the next song in queue.
    """
    global _current_index, _play_queue, _current_room
    
    logger.info(f"Song ended callback received for room: {req.room_name}")
    
    # Verify this is for the current room
    if _current_room != req.room_name:
        logger.warning(f"Room mismatch: expected {_current_room}, got {req.room_name}")
        return {"success": False, "message": "Room mismatch"}
    
    # Check if there are more songs in queue
    if _current_index < len(_play_queue) - 1:
        _current_index += 1
        logger.info(f"Playing next song, index: {_current_index}")
        
        # Play next song
        success = await _play_current_song()
        
        # Broadcast state change to all clients
        asyncio.create_task(_broadcast_playback_state())
        
        return {"success": success, "current_index": _current_index}
    else:
        logger.info("Queue finished, no more songs")
        # Broadcast stopped state
        asyncio.create_task(_broadcast_playback_state())
        return {"success": True, "message": "Queue finished"}
