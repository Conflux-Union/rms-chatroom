from __future__ import annotations

import json
import struct
from datetime import datetime

from fastapi import APIRouter, WebSocket, WebSocketDisconnect
from sqlalchemy import select, delete
from starlette.websockets import WebSocketState

from ..core.database import async_session_maker
from ..models.server import Channel, ChannelType, VoiceState
from ..services.sso_client import SSOClient
from .manager import voice_manager


router = APIRouter()

# Track which users are in relay mode
relay_mode_users: dict[int, set[int]] = {}  # channel_id -> set of user_ids


@router.websocket("/ws/voice/{channel_id}")
async def voice_websocket(websocket: WebSocket, channel_id: int, token: str | None = None):
    """
    WebSocket endpoint for WebRTC voice signaling and audio relay.

    Text Messages (JSON):
    - offer/answer/ice-candidate: WebRTC signaling (P2P mode)
    - relay_mode: Switch to server relay mode
    - mute/deafen: Audio state changes
    - users: List of users in channel

    Binary Messages:
    - Audio frames: [user_id: 4 bytes BE] + [opus_frame: N bytes]
    """
    if not token:
        await websocket.close(code=4001, reason="Missing token")
        return

    user = await SSOClient.verify_token(token)
    if not user:
        await websocket.close(code=4001, reason="Invalid token")
        return

    # Verify channel exists and is voice type
    async with async_session_maker() as db:
        result = await db.execute(select(Channel).where(Channel.id == channel_id))
        channel = result.scalar_one_or_none()
        if not channel or channel.type != ChannelType.VOICE:
            await websocket.close(code=4004, reason="Channel not found or not a voice channel")
            return

        # Remove any existing voice state for this user
        await db.execute(delete(VoiceState).where(VoiceState.user_id == user["id"]))

        # Add voice state
        voice_state = VoiceState(
            channel_id=channel_id,
            user_id=user["id"],
            username=user.get("nickname") or user["username"],
        )
        db.add(voice_state)
        await db.commit()

    await voice_manager.connect(websocket, channel_id, user)

    try:
        # Notify others that user joined
        await voice_manager.broadcast_to_channel(
            channel_id,
            {
                "type": "user_joined",
                "user_id": user["id"],
                "username": user.get("nickname") or user["username"],
            },
            exclude=websocket,
        )

        # Send current users list to the new user
        users = voice_manager.get_channel_users(channel_id)
        relay_users = relay_mode_users.get(channel_id, set())
        await websocket.send_json({
            "type": "users",
            "users": [
                {
                    "id": u["id"],
                    "username": u.get("nickname") or u["username"],
                    "relay_mode": u["id"] in relay_users,
                }
                for u in users
            ],
        })

        while True:
            message = await websocket.receive()

            # Handle binary audio data (relay mode)
            if "bytes" in message:
                audio_data = message["bytes"]
                # Prepend sender's user_id and broadcast to relay mode users
                frame = struct.pack(">I", user["id"]) + audio_data
                # Only broadcast to users in relay mode
                if channel_id in voice_manager.active_connections:
                    for ws, u in voice_manager.active_connections[channel_id]:
                        if ws == websocket:
                            continue
                        # Send to users in relay mode
                        if u["id"] in relay_mode_users.get(channel_id, set()):
                            try:
                                await ws.send_bytes(frame)
                            except Exception:
                                pass
                continue

            # Handle text messages (JSON)
            if "text" not in message:
                continue

            data = message["text"]
            try:
                msg = json.loads(data)
            except json.JSONDecodeError:
                continue

            msg_type = msg.get("type")

            if msg_type == "offer":
                # Forward SDP offer to target user
                target_id = msg.get("target_user_id")
                if target_id:
                    await voice_manager.send_to_user(channel_id, target_id, {
                        "type": "offer",
                        "from_user_id": user["id"],
                        "sdp": msg.get("sdp"),
                    })

            elif msg_type == "answer":
                # Forward SDP answer to target user
                target_id = msg.get("target_user_id")
                if target_id:
                    await voice_manager.send_to_user(channel_id, target_id, {
                        "type": "answer",
                        "from_user_id": user["id"],
                        "sdp": msg.get("sdp"),
                    })

            elif msg_type == "ice-candidate":
                # Forward ICE candidate to target user
                target_id = msg.get("target_user_id")
                if target_id:
                    await voice_manager.send_to_user(channel_id, target_id, {
                        "type": "ice-candidate",
                        "from_user_id": user["id"],
                        "candidate": msg.get("candidate"),
                    })

            elif msg_type == "mute":
                async with async_session_maker() as db:
                    result = await db.execute(
                        select(VoiceState).where(VoiceState.user_id == user["id"])
                    )
                    vs = result.scalar_one_or_none()
                    if vs:
                        vs.muted = msg.get("muted", False)
                        await db.commit()

                await voice_manager.broadcast_to_channel(channel_id, {
                    "type": "user_mute",
                    "user_id": user["id"],
                    "muted": msg.get("muted", False),
                })

            elif msg_type == "deafen":
                async with async_session_maker() as db:
                    result = await db.execute(
                        select(VoiceState).where(VoiceState.user_id == user["id"])
                    )
                    vs = result.scalar_one_or_none()
                    if vs:
                        vs.deafened = msg.get("deafened", False)
                        await db.commit()

                await voice_manager.broadcast_to_channel(channel_id, {
                    "type": "user_deafen",
                    "user_id": user["id"],
                    "deafened": msg.get("deafened", False),
                })

            elif msg_type == "relay_mode":
                # User switches to relay mode (P2P failed)
                enabled = msg.get("enabled", True)
                if channel_id not in relay_mode_users:
                    relay_mode_users[channel_id] = set()

                if enabled:
                    relay_mode_users[channel_id].add(user["id"])
                else:
                    relay_mode_users[channel_id].discard(user["id"])

                # Notify others about mode change
                await voice_manager.broadcast_to_channel(channel_id, {
                    "type": "user_relay_mode",
                    "user_id": user["id"],
                    "relay_mode": enabled,
                })

    except WebSocketDisconnect:
        pass
    finally:
        await voice_manager.disconnect(websocket, channel_id)

        # Remove from relay mode tracking
        if channel_id in relay_mode_users:
            relay_mode_users[channel_id].discard(user["id"])
            if not relay_mode_users[channel_id]:
                del relay_mode_users[channel_id]

        # Remove voice state
        async with async_session_maker() as db:
            await db.execute(delete(VoiceState).where(VoiceState.user_id == user["id"]))
            await db.commit()

        # Notify others that user left
        await voice_manager.broadcast_to_channel(channel_id, {
            "type": "user_left",
            "user_id": user["id"],
        })


@router.get("/api/voice/{channel_id}/users")
async def get_voice_users(channel_id: int):
    """Get list of users currently in a voice channel."""
    async with async_session_maker() as db:
        result = await db.execute(
            select(VoiceState).where(VoiceState.channel_id == channel_id)
        )
        states = result.scalars().all()
        return {
            "users": [
                {
                    "id": s.user_id,
                    "username": s.username,
                    "muted": s.muted,
                    "deafened": s.deafened,
                }
                for s in states
            ]
        }
