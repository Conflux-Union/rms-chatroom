from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, status
from livekit.api import (
    AccessToken, VideoGrants, LiveKitAPI, ListParticipantsRequest,
    RoomParticipantIdentity, MuteRoomTrackRequest
)
from pydantic import BaseModel
from sqlalchemy import select

from ..core.config import get_settings
from ..core.database import get_db
from ..models.server import Channel, ChannelType
from ..routers.deps import CurrentUser, AdminUser
from sqlalchemy.ext.asyncio import AsyncSession


router = APIRouter()

# Host mode state: room_name -> host_identity (None if disabled)
_host_mode_state: dict[str, str] = {}


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
    
    # Create access token with grants
    token = (
        AccessToken(
            api_key=settings.livekit_api_key,
            api_secret=settings.livekit_api_secret,
        )
        .with_identity(str(user["id"]))
        .with_name(user.get("nickname") or user["username"])
        .with_grants(VideoGrants(
            room_join=True,
            room=room_name,
            can_publish=True,
            can_subscribe=True,
        ))
    )
    
    jwt_token = token.to_jwt()
    
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
