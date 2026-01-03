"""WebSocket endpoint for music state synchronization."""
from __future__ import annotations

import asyncio
import json
import logging
import time
from typing import Any

from fastapi import APIRouter, WebSocket, WebSocketDisconnect

from ..services.sso_client import SSOClient


logger = logging.getLogger(__name__)

router = APIRouter()

# Per-room WebSocket clients: room_name -> set of (WebSocket, user_info)
_room_clients: dict[str, set[WebSocket]] = {}
# WebSocket to room mapping for cleanup
_ws_to_room: dict[WebSocket, str] = {}
_lock = asyncio.Lock()


async def broadcast_music_state(event_type: str, data: dict[str, Any]) -> None:
    """Broadcast music commands/state to clients in a specific room."""
    room_name = data.get("room_name")
    if not room_name:
        logger.warning(f"No room_name in broadcast data for event {event_type}")
        return

    async with _lock:
        clients = _room_clients.get(room_name, set())
        if not clients:
            logger.warning(f"No clients in room {room_name}, cannot broadcast {event_type}")
            return

        # Add server timestamp for clock synchronization
        server_time = time.time()

        # For play/pause/resume/seek commands, send directly without wrapping in "data"
        if event_type in ("play", "pause", "resume", "seek"):
            message = json.dumps({"type": event_type, "server_time": server_time, **data})
        else:
            # For music_state, wrap in "data" field
            data_with_time = {**data, "server_time": server_time}
            message = json.dumps({"type": event_type, "data": data_with_time})

        logger.info(f"Broadcasting music event '{event_type}' to {len(clients)} clients in room {room_name}")

        disconnected: list[WebSocket] = []

        for ws in clients:
            try:
                await ws.send_text(message)
            except Exception as e:
                logger.error(f"Failed to send to client: {e}")
                disconnected.append(ws)

        for ws in disconnected:
            clients.discard(ws)
            _ws_to_room.pop(ws, None)

        if disconnected:
            logger.warning(f"Removed {len(disconnected)} disconnected clients from room {room_name}")


async def get_user_from_token(token: str) -> dict | None:
    """Verify token and get user info."""
    return await SSOClient.verify_token(token)


def get_room_client_count(room_name: str) -> int:
    """Get number of clients in a room (for cleanup decisions)."""
    return len(_room_clients.get(room_name, set()))


@router.websocket("/ws/music")
async def music_websocket(
    websocket: WebSocket,
    token: str | None = None,
    room_name: str | None = None
):
    """
    WebSocket endpoint for music state synchronization.
    Clients receive real-time updates when playback state changes.

    Query params:
    - token: Authentication token (required)
    - room_name: Voice room name to join (required, e.g., "voice_123")

    Messages sent to clients:
    - {"type": "music_state", "data": {...}, "server_time": float}
    - {"type": "play", "room_name": str, "song": {...}, "url": str, "position_ms": int, "server_time": float}
    - {"type": "pause", "room_name": str, "server_time": float}
    - {"type": "resume", "room_name": str, "position_ms": int, "server_time": float}
    - {"type": "seek", "room_name": str, "position_ms": int, "server_time": float}
    - {"type": "connected", "room_name": str}
    - {"type": "song_unavailable", "room_name": str, "song_name": str, "reason": str}
    """
    if not token:
        await websocket.close(code=4001, reason="Missing token")
        return

    if not room_name:
        await websocket.close(code=4002, reason="Missing room_name")
        return

    user = await get_user_from_token(token)
    if not user:
        await websocket.close(code=4001, reason="Invalid token")
        return

    await websocket.accept()

    async with _lock:
        if room_name not in _room_clients:
            _room_clients[room_name] = set()
        _room_clients[room_name].add(websocket)
        _ws_to_room[websocket] = room_name

    logger.info(f"Music WebSocket connected: user {user.get('username')} joined room {room_name}")

    try:
        await websocket.send_json({"type": "connected", "room_name": room_name})

        # Keep connection alive, handle ping/pong and room changes
        while True:
            try:
                data = await websocket.receive_text()
                msg = json.loads(data)
                msg_type = msg.get("type")

                if msg_type == "ping":
                    await websocket.send_json({"type": "pong"})
                elif msg_type == "join_room":
                    # Allow client to switch rooms
                    new_room = msg.get("room_name")
                    if new_room and new_room != room_name:
                        async with _lock:
                            # Leave old room
                            if room_name in _room_clients:
                                _room_clients[room_name].discard(websocket)
                                if not _room_clients[room_name]:
                                    del _room_clients[room_name]
                            # Join new room
                            if new_room not in _room_clients:
                                _room_clients[new_room] = set()
                            _room_clients[new_room].add(websocket)
                            _ws_to_room[websocket] = new_room
                        room_name = new_room
                        logger.info(f"User {user.get('username')} switched to room {new_room}")
                        await websocket.send_json({"type": "room_changed", "room_name": new_room})
            except json.JSONDecodeError:
                continue

    except WebSocketDisconnect:
        pass
    except Exception as e:
        logger.error(f"Music WebSocket error: {e}")
    finally:
        async with _lock:
            current_room = _ws_to_room.pop(websocket, None)
            if current_room and current_room in _room_clients:
                _room_clients[current_room].discard(websocket)
                if not _room_clients[current_room]:
                    del _room_clients[current_room]
        logger.info(f"Music WebSocket disconnected: user {user.get('username')} left room {room_name}")
