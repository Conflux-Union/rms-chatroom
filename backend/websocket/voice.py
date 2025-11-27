from __future__ import annotations

import json
from datetime import datetime

from fastapi import APIRouter, WebSocket, WebSocketDisconnect
from sqlalchemy import select, delete

from ..core.database import async_session_maker
from ..models.server import Channel, ChannelType, VoiceState
from ..services.sso_client import SSOClient
from .manager import voice_manager


router = APIRouter()


@router.websocket("/ws/voice/{channel_id}")
async def voice_websocket(websocket: WebSocket, channel_id: int, token: str | None = None):
    """
    WebSocket endpoint for WebRTC voice signaling.

    Messages:
    - join: User joins voice channel
    - leave: User leaves voice channel
    - offer: WebRTC SDP offer
    - answer: WebRTC SDP answer
    - ice-candidate: ICE candidate
    - mute/unmute: Audio state changes
    - users: List of users in channel
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
        await websocket.send_json({
            "type": "users",
            "users": [{"id": u["id"], "username": u.get("nickname") or u["username"]} for u in users],
        })

        while True:
            data = await websocket.receive_text()
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

    except WebSocketDisconnect:
        pass
    finally:
        await voice_manager.disconnect(websocket, channel_id)

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
