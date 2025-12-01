from __future__ import annotations

import asyncio
import logging
import uuid
from datetime import datetime

import httpx
from fastapi import APIRouter, Depends, HTTPException, Request, status
from livekit.api import (
    AccessToken, VideoGrants, LiveKitAPI, ListParticipantsRequest,
    RoomParticipantIdentity, MuteRoomTrackRequest
)
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.orm import joinedload

from ..core.config import get_settings
from ..core.database import get_db
from ..models.server import Channel, ChannelType, VoiceInvite, Server
from ..routers.deps import CurrentUser, AdminUser
from sqlalchemy.ext.asyncio import AsyncSession


logger = logging.getLogger(__name__)

# QQ group notification config
QQ_NOTIFY_URL = "http://119.23.57.80:53000/send_group_msg"
QQ_GROUP_ID = 457054386


async def check_room_has_real_users(room_name: str) -> bool:
    """Check if room has real users (excluding MusicBot)."""
    settings = get_settings()
    livekit_http_url = settings.livekit_internal_host.replace("ws://", "http://").replace("wss://", "https://")
    api = LiveKitAPI(
        url=livekit_http_url,
        api_key=settings.livekit_api_key,
        api_secret=settings.livekit_api_secret,
    )
    try:
        response = await api.room.list_participants(ListParticipantsRequest(room=room_name))
        # Filter out MusicBot (legacy "MusicBot" and new "music-bot-{room}" format)
        real_users = [p for p in response.participants if p.identity != "MusicBot" and not p.identity.startswith("music-bot-")]
        return len(real_users) > 0
    except Exception:
        # Room doesn't exist or error, assume no users
        return False
    finally:
        await api.aclose()


async def send_qq_group_notify(username: str, server_name: str, channel_name: str, room_name: str) -> None:
    """Send notification to QQ group when first user joins voice channel."""
    # Only notify if no real users in room (MusicBot excluded)
    has_users = await check_room_has_real_users(room_name)
    if has_users:
        return
    
    message = f"[RMS ChatRoom] 玩家 {username} 在 {server_name}/{channel_name} 开启了新的语音聊天 😋"
    try:
        async with httpx.AsyncClient(timeout=5.0) as client:
            await client.post(
                QQ_NOTIFY_URL,
                json={"group_id": QQ_GROUP_ID, "message": message},
                headers={"Authorization": "Bearer rmstoken"}
            )
    except Exception as e:
        logger.warning(f"Failed to send QQ group notification: {e}")


router = APIRouter()

# Host mode state: room_name -> host_identity (None if disabled)
_host_mode_state: dict[str, str] = {}

# Screen share lock state: room_name -> (sharer_identity, sharer_name)
_screen_share_lock: dict[str, tuple[str, str]] = {}


class LiveKitTokenResponse(BaseModel):
    token: str
    url: str
    room_name: str


class VoiceChannelUser(BaseModel):
    id: str
    name: str
    is_muted: bool
    is_host: bool = False


class MuteRequest(BaseModel):
    muted: bool = True


class HostModeRequest(BaseModel):
    enabled: bool


class HostModeResponse(BaseModel):
    enabled: bool
    host_id: str | None
    host_name: str | None


class InviteCreateResponse(BaseModel):
    invite_url: str
    token: str


class InviteInfoResponse(BaseModel):
    valid: bool
    channel_name: str | None = None
    server_name: str | None = None


class GuestJoinRequest(BaseModel):
    username: str


class GuestJoinResponse(BaseModel):
    token: str
    url: str
    room_name: str
    channel_name: str


class ScreenShareStatusResponse(BaseModel):
    locked: bool
    sharer_id: str | None = None
    sharer_name: str | None = None


class ScreenShareLockResponse(BaseModel):
    success: bool
    sharer_id: str | None = None
    sharer_name: str | None = None


@router.get("/api/voice/{channel_id}/token", response_model=LiveKitTokenResponse)
async def get_voice_token(
    channel_id: int,
    user: CurrentUser,
    db: AsyncSession = Depends(get_db),
):
    """
    Get LiveKit token for joining a voice channel.
    The room name is 'voice_{channel_id}'.
    """
    # Verify channel exists and is voice type, load server relation
    result = await db.execute(
        select(Channel)
        .options(joinedload(Channel.server))
        .where(Channel.id == channel_id)
    )
    channel = result.scalar_one_or_none()
    
    if not channel:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Channel not found"
        )
    
    if channel.type != ChannelType.VOICE:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Not a voice channel"
        )
    
    settings = get_settings()
    room_name = f"voice_{channel_id}"
    
    # Create access token with grants
    username = user.get("nickname") or user["username"]
    token = (
        AccessToken(
            api_key=settings.livekit_api_key,
            api_secret=settings.livekit_api_secret,
        )
        .with_identity(str(user["id"]))
        .with_name(username)
        .with_grants(VideoGrants(
            room_join=True,
            room=room_name,
            can_publish=True,
            can_publish_sources=["camera", "microphone", "screen_share", "screen_share_audio"],
            can_subscribe=True,
        ))
    )
    
    jwt_token = token.to_jwt()
    
    # Send QQ group notification (fire and forget, only if first real user)
    server_name = channel.server.name if channel.server else "未知服务器"
    asyncio.create_task(send_qq_group_notify(username, server_name, channel.name, room_name))
    
    return LiveKitTokenResponse(
        token=jwt_token,
        url=settings.livekit_host,
        room_name=room_name,
    )


async def _get_livekit_api():
    """Create LiveKit API client."""
    settings = get_settings()
    livekit_http_url = settings.livekit_internal_host.replace("ws://", "http://").replace("wss://", "https://")
    return LiveKitAPI(
        url=livekit_http_url,
        api_key=settings.livekit_api_key,
        api_secret=settings.livekit_api_secret,
    )


async def _mute_participant_mic(api: LiveKitAPI, room_name: str, identity: str, muted: bool) -> bool:
    """Mute or unmute a participant's microphone track."""
    try:
        participant = await api.room.get_participant(
            RoomParticipantIdentity(room=room_name, identity=identity)
        )
        for track in participant.tracks:
            if track.source == 2:  # MICROPHONE
                await api.room.mute_published_track(MuteRoomTrackRequest(
                    room=room_name,
                    identity=identity,
                    track_sid=track.sid,
                    muted=muted,
                ))
                return True
        return False
    except Exception:
        return False


@router.get("/api/voice/{channel_id}/users", response_model=list[VoiceChannelUser])
async def get_voice_users(
    channel_id: int,
    db: AsyncSession = Depends(get_db),
):
    """
    Get list of users currently in a voice channel.
    Uses LiveKit's Room Service API to fetch participants.
    """
    # Verify channel exists and is voice type
    result = await db.execute(select(Channel).where(Channel.id == channel_id))
    channel = result.scalar_one_or_none()
    
    if not channel:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Channel not found"
        )
    
    if channel.type != ChannelType.VOICE:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Not a voice channel"
        )
    
    settings = get_settings()
    room_name = f"voice_{channel_id}"
    
    users: list[VoiceChannelUser] = []
    
    try:
        # Create LiveKit API client with internal host (http/https)
        livekit_http_url = settings.livekit_internal_host.replace("ws://", "http://").replace("wss://", "https://")
        api = LiveKitAPI(
            url=livekit_http_url,
            api_key=settings.livekit_api_key,
            api_secret=settings.livekit_api_secret,
        )
        
        # Fetch participants from LiveKit room
        response = await api.room.list_participants(ListParticipantsRequest(room=room_name))
        
        # Check host mode
        host_id = _host_mode_state.get(room_name)
        
        # If host mode active, check if host is still in room
        if host_id:
            participant_ids = {p.identity for p in response.participants}
            if host_id not in participant_ids:
                # Host left, disable host mode
                _host_mode_state.pop(room_name, None)
                host_id = None
        
        for p in response.participants:
            # Check if microphone track is muted (source=2 is MICROPHONE)
            is_muted = False
            has_mic = False
            for track in p.tracks:
                if track.source == 2:  # MICROPHONE
                    has_mic = True
                    is_muted = track.muted
                    break
            # If no mic track found, consider as muted
            if not has_mic:
                is_muted = True
            
            # Host mode backup: mute non-host participants who are not muted
            if host_id and p.identity != host_id and not is_muted:
                await _mute_participant_mic(api, room_name, p.identity, True)
                is_muted = True
            
            users.append(VoiceChannelUser(
                id=p.identity,
                name=p.name or p.identity,
                is_muted=is_muted,
                is_host=(host_id == p.identity),
            ))
        
        await api.aclose()
        
    except Exception:
        # Room may not exist yet (no participants)
        pass
    
    return users


@router.post("/api/voice/{channel_id}/mute/{target_user_id}")
async def mute_participant(
    channel_id: int,
    target_user_id: str,
    payload: MuteRequest,
    user: AdminUser,
    db: AsyncSession = Depends(get_db),
):
    """
    Mute or unmute a participant's microphone (admin only).
    """
    result = await db.execute(select(Channel).where(Channel.id == channel_id))
    channel = result.scalar_one_or_none()
    
    if not channel:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Channel not found")
    
    if channel.type != ChannelType.VOICE:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Not a voice channel")
    
    room_name = f"voice_{channel_id}"
    api = await _get_livekit_api()
    
    try:
        success = await _mute_participant_mic(api, room_name, target_user_id, payload.muted)
        if not success:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="Participant not found or no microphone track"
            )
        return {"success": True, "muted": payload.muted}
    finally:
        await api.aclose()


@router.post("/api/voice/{channel_id}/kick/{target_user_id}")
async def kick_participant(
    channel_id: int,
    target_user_id: str,
    user: AdminUser,
    db: AsyncSession = Depends(get_db),
):
    """
    Kick a participant from voice channel (admin only).
    """
    result = await db.execute(select(Channel).where(Channel.id == channel_id))
    channel = result.scalar_one_or_none()
    
    if not channel:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Channel not found")
    
    if channel.type != ChannelType.VOICE:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Not a voice channel")
    
    room_name = f"voice_{channel_id}"
    api = await _get_livekit_api()
    
    try:
        await api.room.remove_participant(
            RoomParticipantIdentity(room=room_name, identity=target_user_id)
        )
        return {"success": True}
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Participant not found"
        )
    finally:
        await api.aclose()


@router.get("/api/voice/{channel_id}/host-mode", response_model=HostModeResponse)
async def get_host_mode(
    channel_id: int,
    db: AsyncSession = Depends(get_db),
):
    """Get current host mode status for a voice channel."""
    result = await db.execute(select(Channel).where(Channel.id == channel_id))
    channel = result.scalar_one_or_none()
    
    if not channel:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Channel not found")
    
    if channel.type != ChannelType.VOICE:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Not a voice channel")
    
    room_name = f"voice_{channel_id}"
    host_id = _host_mode_state.get(room_name)
    host_name = None
    
    if host_id:
        # Fetch host name from LiveKit
        try:
            api = await _get_livekit_api()
            participant = await api.room.get_participant(
                RoomParticipantIdentity(room=room_name, identity=host_id)
            )
            host_name = participant.name or host_id
            await api.aclose()
        except Exception:
            # Host may have left, clear host mode
            _host_mode_state.pop(room_name, None)
            host_id = None
    
    return HostModeResponse(enabled=host_id is not None, host_id=host_id, host_name=host_name)


@router.post("/api/voice/{channel_id}/host-mode", response_model=HostModeResponse)
async def set_host_mode(
    channel_id: int,
    payload: HostModeRequest,
    user: AdminUser,
    db: AsyncSession = Depends(get_db),
):
    """
    Enable or disable host mode (admin only).
    When enabled, all participants except the host are muted.
    """
    result = await db.execute(select(Channel).where(Channel.id == channel_id))
    channel = result.scalar_one_or_none()
    
    if not channel:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Channel not found")
    
    if channel.type != ChannelType.VOICE:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Not a voice channel")
    
    room_name = f"voice_{channel_id}"
    user_id = str(user["id"])
    user_name = user.get("nickname") or user["username"]
    
    # Check if host mode is already active by another user
    current_host = _host_mode_state.get(room_name)
    if payload.enabled and current_host and current_host != user_id:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Host mode is already active by another user"
        )
    
    # Only the current host can disable host mode
    if not payload.enabled and current_host and current_host != user_id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Only the current host can disable host mode"
        )
    
    api = await _get_livekit_api()
    
    try:
        if payload.enabled:
            # Enable host mode: mute all except host
            _host_mode_state[room_name] = user_id
            
            response = await api.room.list_participants(ListParticipantsRequest(room=room_name))
            for p in response.participants:
                if p.identity != user_id:
                    await _mute_participant_mic(api, room_name, p.identity, True)
            
            return HostModeResponse(enabled=True, host_id=user_id, host_name=user_name)
        else:
            # Disable host mode (don't auto-unmute, users unmute themselves)
            _host_mode_state.pop(room_name, None)
            return HostModeResponse(enabled=False, host_id=None, host_name=None)
    finally:
        await api.aclose()


# ============================================================================
# Screen Share Lock APIs
# ============================================================================

async def _check_screen_share_lock(room_name: str) -> tuple[bool, str | None, str | None]:
    """
    Check if screen share is locked and if the locker is still in the room.
    Returns (is_locked, sharer_id, sharer_name).
    Auto-releases lock if sharer has left the room.
    """
    lock_info = _screen_share_lock.get(room_name)
    if not lock_info:
        return False, None, None
    
    sharer_id, sharer_name = lock_info
    
    # Verify sharer is still in the room
    try:
        api = await _get_livekit_api()
        response = await api.room.list_participants(ListParticipantsRequest(room=room_name))
        participant_ids = {p.identity for p in response.participants}
        await api.aclose()
        
        if sharer_id not in participant_ids:
            # Sharer left, auto-release lock
            _screen_share_lock.pop(room_name, None)
            return False, None, None
        
        return True, sharer_id, sharer_name
    except Exception:
        # Room doesn't exist, release lock
        _screen_share_lock.pop(room_name, None)
        return False, None, None


@router.get("/api/voice/{channel_id}/screen-share-status", response_model=ScreenShareStatusResponse)
async def get_screen_share_status(
    channel_id: int,
    db: AsyncSession = Depends(get_db),
):
    """Get current screen share lock status for a voice channel."""
    result = await db.execute(select(Channel).where(Channel.id == channel_id))
    channel = result.scalar_one_or_none()
    
    if not channel:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Channel not found")
    
    if channel.type != ChannelType.VOICE:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Not a voice channel")
    
    room_name = f"voice_{channel_id}"
    is_locked, sharer_id, sharer_name = await _check_screen_share_lock(room_name)
    
    return ScreenShareStatusResponse(locked=is_locked, sharer_id=sharer_id, sharer_name=sharer_name)


@router.post("/api/voice/{channel_id}/screen-share/lock", response_model=ScreenShareLockResponse)
async def lock_screen_share(
    channel_id: int,
    user: CurrentUser,
    db: AsyncSession = Depends(get_db),
):
    """
    Attempt to acquire screen share lock.
    Returns success=True if lock acquired, or success=False with current sharer info.
    """
    result = await db.execute(select(Channel).where(Channel.id == channel_id))
    channel = result.scalar_one_or_none()
    
    if not channel:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Channel not found")
    
    if channel.type != ChannelType.VOICE:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Not a voice channel")
    
    room_name = f"voice_{channel_id}"
    user_id = str(user["id"])
    user_name = user.get("nickname") or user["username"]
    
    # Check current lock status (auto-releases if sharer left)
    is_locked, sharer_id, sharer_name = await _check_screen_share_lock(room_name)
    
    if is_locked:
        if sharer_id == user_id:
            # Already locked by this user
            return ScreenShareLockResponse(success=True, sharer_id=user_id, sharer_name=user_name)
        # Locked by someone else
        return ScreenShareLockResponse(success=False, sharer_id=sharer_id, sharer_name=sharer_name)
    
    # Acquire lock
    _screen_share_lock[room_name] = (user_id, user_name)
    return ScreenShareLockResponse(success=True, sharer_id=user_id, sharer_name=user_name)


@router.post("/api/voice/{channel_id}/screen-share/unlock", response_model=ScreenShareLockResponse)
async def unlock_screen_share(
    channel_id: int,
    user: CurrentUser,
    db: AsyncSession = Depends(get_db),
):
    """
    Release screen share lock. Only the lock holder can release it.
    """
    result = await db.execute(select(Channel).where(Channel.id == channel_id))
    channel = result.scalar_one_or_none()
    
    if not channel:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Channel not found")
    
    if channel.type != ChannelType.VOICE:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Not a voice channel")
    
    room_name = f"voice_{channel_id}"
    user_id = str(user["id"])
    
    lock_info = _screen_share_lock.get(room_name)
    if lock_info and lock_info[0] == user_id:
        _screen_share_lock.pop(room_name, None)
    
    return ScreenShareLockResponse(success=True, sharer_id=None, sharer_name=None)


# ============================================================================
# Voice Invite APIs (Guest access without login)
# ============================================================================

@router.post("/api/voice/{channel_id}/invite", response_model=InviteCreateResponse)
async def create_voice_invite(
    channel_id: int,
    request: Request,
    user: AdminUser,
    db: AsyncSession = Depends(get_db),
):
    """Create a single-use invite link for a voice channel (admin only)."""
    result = await db.execute(select(Channel).where(Channel.id == channel_id))
    channel = result.scalar_one_or_none()
    
    if not channel:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Channel not found")
    
    if channel.type != ChannelType.VOICE:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Not a voice channel")
    
    token = uuid.uuid4().hex
    invite = VoiceInvite(
        channel_id=channel_id,
        token=token,
        created_by=user["id"],
    )
    db.add(invite)
    await db.flush()
    
    # Build invite URL based on request origin
    base_url = str(request.base_url).rstrip("/")
    # Frontend runs on different port in dev, use origin header if available
    origin = request.headers.get("origin", base_url)
    invite_url = f"{origin}/voice-invite/{token}"
    
    return InviteCreateResponse(invite_url=invite_url, token=token)


@router.get("/api/voice/invite/{token}", response_model=InviteInfoResponse)
async def get_voice_invite_info(
    token: str,
    db: AsyncSession = Depends(get_db),
):
    """Get invite info without authentication. Returns channel/server name if valid."""
    result = await db.execute(
        select(VoiceInvite)
        .options(joinedload(VoiceInvite.channel))
        .where(VoiceInvite.token == token)
    )
    invite = result.scalar_one_or_none()
    
    if not invite:
        return InviteInfoResponse(valid=False)
    
    if invite.used:
        return InviteInfoResponse(valid=False)
    
    # Get server name
    server_result = await db.execute(
        select(Server).where(Server.id == invite.channel.server_id)
    )
    server = server_result.scalar_one_or_none()
    
    return InviteInfoResponse(
        valid=True,
        channel_name=invite.channel.name,
        server_name=server.name if server else None,
    )


@router.post("/api/voice/invite/{token}/join", response_model=GuestJoinResponse)
async def join_voice_as_guest(
    token: str,
    payload: GuestJoinRequest,
    db: AsyncSession = Depends(get_db),
):
    """
    Join a voice channel as a guest using an invite token.
    This endpoint does NOT require authentication.
    The invite token becomes invalid after use.
    """
    if not payload.username or len(payload.username.strip()) < 1:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Username is required"
        )
    
    username = payload.username.strip()[:50]  # Limit length
    
    result = await db.execute(
        select(VoiceInvite)
        .options(joinedload(VoiceInvite.channel))
        .where(VoiceInvite.token == token)
    )
    invite = result.scalar_one_or_none()
    
    if not invite:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Invite not found"
        )
    
    if invite.used:
        raise HTTPException(
            status_code=status.HTTP_410_GONE,
            detail="This invite has already been used"
        )
    
    # Mark invite as used
    invite.used = True
    invite.used_by_name = username
    invite.used_at = datetime.utcnow()
    await db.flush()
    
    # Generate LiveKit token for guest
    settings = get_settings()
    room_name = f"voice_{invite.channel_id}"
    guest_identity = f"guest_{token[:8]}"
    
    lk_token = (
        AccessToken(
            api_key=settings.livekit_api_key,
            api_secret=settings.livekit_api_secret,
        )
        .with_identity(guest_identity)
        .with_name(f"[Guest] {username}")
        .with_grants(VideoGrants(
            room_join=True,
            room=room_name,
            can_publish=True,
            can_publish_sources=["camera", "microphone", "screen_share", "screen_share_audio"],
            can_subscribe=True,
        ))
    )
    
    return GuestJoinResponse(
        token=lk_token.to_jwt(),
        url=settings.livekit_host,
        room_name=room_name,
        channel_name=invite.channel.name,
    )


# ============================================================================
# QQ Bot APIs
# ============================================================================

class VoiceChannelInfo(BaseModel):
    channel_id: int
    channel_name: str
    server_name: str
    users: list[str]


class AllVoiceChannelsResponse(BaseModel):
    channels: list[VoiceChannelInfo]
    total_users: int


class AllVoiceUsersResponse(BaseModel):
    """Response model for /api/voice/user/all - optimized for frontend polling."""
    users: dict[int, list[VoiceChannelUser]]  # channel_id -> users


@router.get("/api/voice/user/all", response_model=AllVoiceUsersResponse)
async def get_all_voice_users(
    db: AsyncSession = Depends(get_db),
):
    """
    Get all users in all voice channels in a single request.
    Returns a dict mapping channel_id to list of users.
    Reduces polling overhead compared to per-channel requests.
    """
    result = await db.execute(
        select(Channel).where(Channel.type == ChannelType.VOICE)
    )
    channels = result.scalars().all()
    
    settings = get_settings()
    livekit_http_url = settings.livekit_internal_host.replace("ws://", "http://").replace("wss://", "https://")
    api = LiveKitAPI(
        url=livekit_http_url,
        api_key=settings.livekit_api_key,
        api_secret=settings.livekit_api_secret,
    )
    
    users_by_channel: dict[int, list[VoiceChannelUser]] = {}
    
    try:
        for channel in channels:
            room_name = f"voice_{channel.id}"
            try:
                response = await api.room.list_participants(ListParticipantsRequest(room=room_name))
                host_id = _host_mode_state.get(room_name)
                
                # Check if host is still in room
                if host_id:
                    participant_ids = {p.identity for p in response.participants}
                    if host_id not in participant_ids:
                        _host_mode_state.pop(room_name, None)
                        host_id = None
                
                channel_users: list[VoiceChannelUser] = []
                for p in response.participants:
                    is_muted = True
                    for track in p.tracks:
                        if track.source == 2:  # MICROPHONE
                            is_muted = track.muted
                            break
                    
                    # Host mode enforcement
                    if host_id and p.identity != host_id and not is_muted:
                        await _mute_participant_mic(api, room_name, p.identity, True)
                        is_muted = True
                    
                    channel_users.append(VoiceChannelUser(
                        id=p.identity,
                        name=p.name or p.identity,
                        is_muted=is_muted,
                        is_host=(host_id == p.identity),
                    ))
                
                if channel_users:
                    users_by_channel[channel.id] = channel_users
            except Exception:
                # Room doesn't exist (no participants)
                pass
    finally:
        await api.aclose()
    
    return AllVoiceUsersResponse(users=users_by_channel)


@router.get("/api/qqbot/get_voice_channel_people", response_model=AllVoiceChannelsResponse)
async def get_all_voice_channel_people(
    db: AsyncSession = Depends(get_db),
):
    """Get all users currently in voice channels (for QQ bot)."""
    # Get all voice channels with server info
    result = await db.execute(
        select(Channel)
        .options(joinedload(Channel.server))
        .where(Channel.type == ChannelType.VOICE)
    )
    channels = result.scalars().all()
    
    settings = get_settings()
    livekit_http_url = settings.livekit_internal_host.replace("ws://", "http://").replace("wss://", "https://")
    api = LiveKitAPI(
        url=livekit_http_url,
        api_key=settings.livekit_api_key,
        api_secret=settings.livekit_api_secret,
    )
    
    channel_infos: list[VoiceChannelInfo] = []
    total_users = 0
    
    try:
        for channel in channels:
            room_name = f"voice_{channel.id}"
            try:
                response = await api.room.list_participants(ListParticipantsRequest(room=room_name))
                users = [p.name or p.identity for p in response.participants]
                if users:
                    channel_infos.append(VoiceChannelInfo(
                        channel_id=channel.id,
                        channel_name=channel.name,
                        server_name=channel.server.name if channel.server else "未知服务器",
                        users=users,
                    ))
                    total_users += len(users)
            except Exception:
                # Room may not exist (no participants)
                pass
    finally:
        await api.aclose()
    
    return AllVoiceChannelsResponse(channels=channel_infos, total_users=total_users)
