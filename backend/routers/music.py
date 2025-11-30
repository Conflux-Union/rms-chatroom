"""QQ Music integration router - search, login, and playback control."""
from __future__ import annotations

import asyncio
import base64
import dataclasses
import json
import logging
import time as time_module
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


@dataclasses.dataclass
class RoomMusicState:
    """Per-room music playback state."""
    room_name: str
    play_queue: list[dict[str, Any]] = dataclasses.field(default_factory=list)
    current_index: int = 0


# Global state for QQ Music credential (shared across all rooms)
_credential: Credential | None = None
_current_qr: login.QR | None = None

# Per-room music state: room_name -> RoomMusicState
_room_states: dict[str, RoomMusicState] = {}

# WebSocket broadcast function (set by websocket module)
_ws_broadcast: Any = None


def _get_room_state(room_name: str) -> RoomMusicState:
    """Get or create room state for a given room."""
    if room_name not in _room_states:
        _room_states[room_name] = RoomMusicState(room_name=room_name)
    return _room_states[room_name]


async def _broadcast_playback_state(room_name: str) -> None:
    """Broadcast current playback state to all connected clients for a specific room."""
    global _ws_broadcast
    
    if not _ws_broadcast:
        return
    
    try:
        state = _get_room_state(room_name)
        
        # Get progress from Go service
        progress_data = {
            "room_name": room_name,
            "position_ms": 0,
            "duration_ms": 0,
            "state": "idle",
            "current_song": None,
            "current_index": state.current_index,
            "queue_length": len(state.play_queue),
        }
        
        try:
            progress = await _call_music_service("GET", "/progress", {"room_name": room_name})
            progress_data["position_ms"] = progress.get("position_ms", 0)
            progress_data["duration_ms"] = progress.get("duration_ms", 0)
            progress_data["state"] = progress.get("state", "idle")
            progress_data["current_song"] = progress.get("song")
        except Exception:
            pass
        
        # If no song from Go service, use queue info
        if not progress_data["current_song"] and state.play_queue and 0 <= state.current_index < len(state.play_queue):
            progress_data["current_song"] = state.play_queue[state.current_index]["song"]
        
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


def _is_credential_valid_locally() -> bool:
    """Check if credential is valid using local timestamp (no network request)."""
    if not _credential:
        return False
    extra = _credential.extra_fields
    if "musickeyCreateTime" in extra:
        create_time = extra["musickeyCreateTime"]
        expires_in = extra.get("keyExpiresIn", 259200)
        return time_module.time() < create_time + expires_in
    return True  # Assume valid if cannot determine


async def _ensure_credential_valid() -> Credential | None:
    """Ensure credential is valid, auto-refresh if expired."""
    global _credential
    if not _credential:
        return None
    
    try:
        if await _credential.is_expired():
            logger.info("Credential expired, attempting refresh...")
            if await _credential.can_refresh():
                success = await _credential.refresh()
                if success:
                    _save_credential(_credential)
                    logger.info("Credential refreshed successfully")
                    return _credential
            # Refresh failed
            logger.warning("Refresh failed, need re-login")
            _credential = None
            _save_credential(None)
            return None
    except Exception as e:
        logger.warning(f"Credential check failed: {e}")
        # Use local timestamp check when network fails
        if _is_credential_valid_locally():
            return _credential
    
    return _credential


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


class QueueAddRequest(BaseModel):
    room_name: str
    song: SongInfo


class QueueRequest(BaseModel):
    room_name: str


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
    
    cred = await _ensure_credential_valid()
    return {"logged_in": cred is not None}


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
    cred = await _ensure_credential_valid()
    
    try:
        # Try to get high quality with credential, fallback to MP3_128
        file_type = song.SongFileType.MP3_320 if cred else song.SongFileType.MP3_128
        urls = await song.get_song_urls([mid], file_type=file_type, credential=cred)
        
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
async def add_to_queue(req: QueueAddRequest, user: CurrentUser):
    """Add a song to the play queue for a specific room."""
    state = _get_room_state(req.room_name)
    
    state.play_queue.append({
        "song": req.song.model_dump(),
        "requested_by": user.get("username", "Unknown")
    })
    
    return {"success": True, "position": len(state.play_queue), "room_name": req.room_name}


@router.delete("/queue/{room_name}/{index}")
async def remove_from_queue(room_name: str, index: int, _user: CurrentUser):
    """Remove a song from the queue for a specific room."""
    state = _get_room_state(room_name)
    
    if index < 0 or index >= len(state.play_queue):
        raise HTTPException(status_code=400, detail="Invalid index")
    
    state.play_queue.pop(index)
    
    if index < state.current_index:
        state.current_index -= 1
    elif index == state.current_index and state.current_index >= len(state.play_queue):
        state.current_index = max(0, len(state.play_queue) - 1)
    
    return {"success": True, "room_name": room_name}


@router.get("/queue/{room_name}")
async def get_queue(room_name: str, _user: CurrentUser):
    """Get current play queue for a specific room."""
    state = _get_room_state(room_name)
    
    current_song = None
    if state.play_queue and 0 <= state.current_index < len(state.play_queue):
        current_song = state.play_queue[state.current_index]["song"]
    
    # Get actual playing state from music service
    is_playing = False
    try:
        progress = await _call_music_service("GET", "/progress", {"room_name": room_name})
        is_playing = progress.get("state") == "playing"
    except Exception:
        pass
    
    return {
        "room_name": room_name,
        "is_playing": is_playing,
        "current_song": current_song,
        "current_index": state.current_index,
        "queue": state.play_queue
    }


@router.post("/queue/clear")
async def clear_queue(req: QueueRequest, _user: CurrentUser):
    """Clear the play queue for a specific room."""
    state = _get_room_state(req.room_name)
    
    try:
        await _call_music_service("POST", "/stop", {"room_name": req.room_name})
    except Exception:
        pass
    
    state.play_queue = []
    state.current_index = 0
    
    return {"success": True, "room_name": req.room_name}


# --- Bot Control APIs ---

class BotStartRequest(BaseModel):
    room_name: str


async def _play_current_song(room_name: str) -> bool:
    """Internal: load and play current song via Go service for a specific room."""
    cred = await _ensure_credential_valid()
    
    state = _get_room_state(room_name)
    
    if not state.play_queue or state.current_index >= len(state.play_queue):
        return False
    
    current = state.play_queue[state.current_index]["song"]
    
    # Get song URL
    try:
        file_type = song.SongFileType.MP3_320 if cred else song.SongFileType.MP3_128
        urls = await song.get_song_urls([current["mid"]], file_type=file_type, credential=cred)
        url = urls.get(current["mid"], "")
        
        if not url:
            urls = await song.get_song_urls([current["mid"]], file_type=song.SongFileType.MP3_128)
            url = urls.get(current["mid"], "")
        
        if not url:
            logger.error(f"Song URL not available: {current['name']}")
            return False
        
        # Call Go music service to play
        await _call_music_service("POST", "/play", {
            "room_name": room_name,
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
    # Ensure room state exists
    _get_room_state(req.room_name)
    return {"success": True, "room": req.room_name}


@router.post("/bot/stop")
async def stop_bot(req: QueueRequest, _user: CurrentUser):
    """Stop the music bot for a specific room."""
    try:
        await _call_music_service("POST", "/stop", {"room_name": req.room_name})
    except Exception:
        pass
    
    # Optionally clean up room state
    if req.room_name in _room_states:
        del _room_states[req.room_name]
    
    return {"success": True, "room_name": req.room_name}


@router.get("/bot/status/{room_name}")
async def get_bot_status(room_name: str, _user: CurrentUser):
    """Get music bot status for a specific room."""
    is_playing = False
    try:
        progress = await _call_music_service("GET", "/progress", {"room_name": room_name})
        is_playing = progress.get("state") == "playing"
    except Exception:
        pass
    
    state = _room_states.get(room_name)
    
    return {
        "connected": state is not None,
        "room": room_name,
        "is_playing": is_playing,
        "queue_length": len(state.play_queue) if state else 0
    }


class BotPlayRequest(BaseModel):
    room_name: str


@router.post("/bot/play")
async def bot_play(req: BotPlayRequest, _user: CurrentUser):
    """Start playing the current song through the bot for a specific room."""
    state = _get_room_state(req.room_name)
    
    if not state.play_queue or state.current_index >= len(state.play_queue):
        raise HTTPException(status_code=400, detail="No song in queue")
    
    current_song = state.play_queue[state.current_index]["song"]
    
    try:
        success = await _play_current_song(req.room_name)
        
        if success:
            # Broadcast state change
            asyncio.create_task(_broadcast_playback_state(req.room_name))
            return {"success": True, "playing": current_song["name"], "room_name": req.room_name}
        else:
            raise HTTPException(status_code=500, detail="Failed to start playback")
        
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to play: {e}")
        raise HTTPException(status_code=500, detail=f"Failed to play: {e}")


@router.post("/bot/pause")
async def bot_pause(req: QueueRequest, _user: CurrentUser):
    """Pause the bot playback for a specific room."""
    try:
        await _call_music_service("POST", "/pause", {"room_name": req.room_name})
        # Broadcast state change
        asyncio.create_task(_broadcast_playback_state(req.room_name))
    except Exception:
        pass
    
    return {"success": True, "room_name": req.room_name}


@router.post("/bot/resume")
async def bot_resume(req: QueueRequest, _user: CurrentUser):
    """Resume the bot playback for a specific room."""
    state = _get_room_state(req.room_name)
    
    # Check if there's a song to resume
    if not state.play_queue or state.current_index >= len(state.play_queue):
        return {"success": False, "message": "No song to resume", "room_name": req.room_name}
    
    try:
        # Use longer timeout for resume since it may need to reconnect to LiveKit
        async with httpx.AsyncClient(timeout=30.0) as client:
            resp = await client.post(
                f"{MUSIC_SERVICE_URL}/resume",
                json={"room_name": req.room_name}
            )
            if resp.status_code != 200:
                logger.error(f"Resume failed: {resp.text}")
                # If resume fails, try to play the current song fresh
                success = await _play_current_song(req.room_name)
                if success:
                    asyncio.create_task(_broadcast_playback_state(req.room_name))
                    return {"success": True, "is_playing": True, "room_name": req.room_name}
                return {"success": False, "message": "Failed to resume", "room_name": req.room_name}
        
        # Broadcast state change
        asyncio.create_task(_broadcast_playback_state(req.room_name))
        return {"success": True, "is_playing": True, "room_name": req.room_name}
    except Exception as e:
        logger.error(f"Resume exception: {e}")
        # Fallback: try to play current song fresh
        try:
            success = await _play_current_song(req.room_name)
            if success:
                asyncio.create_task(_broadcast_playback_state(req.room_name))
                return {"success": True, "is_playing": True, "room_name": req.room_name}
        except Exception as e2:
            logger.error(f"Fallback play failed: {e2}")
    
    return {"success": False, "message": "No player", "room_name": req.room_name}


@router.post("/bot/skip")
async def bot_skip(req: QueueRequest, _user: CurrentUser):
    """Skip to next song on the bot for a specific room."""
    state = _get_room_state(req.room_name)
    
    try:
        await _call_music_service("POST", "/stop", {"room_name": req.room_name})
    except Exception:
        pass
    
    if state.current_index < len(state.play_queue) - 1:
        state.current_index += 1
        success = await _play_current_song(req.room_name)
        # Broadcast state change
        asyncio.create_task(_broadcast_playback_state(req.room_name))
        if success:
            return {"success": True, "current_index": state.current_index, "room_name": req.room_name}
        return {"success": True, "current_index": state.current_index, "room_name": req.room_name}
    
    # Broadcast when queue ends
    asyncio.create_task(_broadcast_playback_state(req.room_name))
    return {"success": False, "message": "No more songs in queue", "room_name": req.room_name}


@router.post("/bot/previous")
async def bot_previous(req: QueueRequest, _user: CurrentUser):
    """Go to previous song on the bot for a specific room."""
    state = _get_room_state(req.room_name)
    
    try:
        await _call_music_service("POST", "/stop", {"room_name": req.room_name})
    except Exception:
        pass
    
    if state.current_index > 0:
        state.current_index -= 1
        success = await _play_current_song(req.room_name)
        # Broadcast state change
        asyncio.create_task(_broadcast_playback_state(req.room_name))
        if success:
            return {"success": True, "current_index": state.current_index, "room_name": req.room_name}
        return {"success": True, "current_index": state.current_index, "room_name": req.room_name}
    
    # Broadcast when at first song
    asyncio.create_task(_broadcast_playback_state(req.room_name))
    return {"success": False, "message": "Already at first song", "room_name": req.room_name}


class SeekRequestWithRoom(BaseModel):
    room_name: str
    position_ms: int


@router.post("/bot/seek")
async def bot_seek(req: SeekRequestWithRoom, _user: CurrentUser):
    """Seek to position in the current song for a specific room."""
    await _call_music_service("POST", "/seek", {
        "room_name": req.room_name,
        "position_ms": req.position_ms
    })
    return {"success": True, "position_ms": req.position_ms, "room_name": req.room_name}


@router.get("/bot/progress/{room_name}")
async def bot_progress(room_name: str, _user: CurrentUser):
    """Get current playback progress for a specific room."""
    try:
        progress = await _call_music_service("GET", "/progress", {"room_name": room_name})
        return {
            "room_name": room_name,
            "position_ms": progress.get("position_ms", 0),
            "duration_ms": progress.get("duration_ms", 0),
            "state": progress.get("state", "idle"),
            "current_song": progress.get("song"),
        }
    except Exception:
        return {
            "room_name": room_name,
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
    Automatically plays the next song in queue for the specific room.
    """
    logger.info(f"Song ended callback received for room: {req.room_name}")
    
    state = _get_room_state(req.room_name)
    
    # Check if there are more songs in queue
    if state.current_index < len(state.play_queue) - 1:
        state.current_index += 1
        logger.info(f"Playing next song in room {req.room_name}, index: {state.current_index}")
        
        # Play next song
        success = await _play_current_song(req.room_name)
        
        # Broadcast state change to all clients
        asyncio.create_task(_broadcast_playback_state(req.room_name))
        
        return {"success": success, "current_index": state.current_index, "room_name": req.room_name}
    else:
        logger.info(f"Queue finished for room {req.room_name}, no more songs")
        # Stop the bot and disconnect from room when queue is empty
        try:
            await _call_music_service("POST", "/stop", {"room_name": req.room_name})
            logger.info(f"Bot stopped and left room: {req.room_name}")
        except Exception as e:
            logger.error(f"Failed to stop bot: {e}")
        # Broadcast stopped state
        asyncio.create_task(_broadcast_playback_state(req.room_name))
        return {"success": True, "message": "Queue finished", "room_name": req.room_name}
