from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, status
from livekit.api import AccessToken, VideoGrants, LiveKitAPI, ListParticipantsRequest
from pydantic import BaseModel
from sqlalchemy import select

from ..core.config import get_settings
from ..core.database import get_db
from ..models.server import Channel, ChannelType
from ..routers.deps import CurrentUser
from sqlalchemy.ext.asyncio import AsyncSession


router = APIRouter()


class LiveKitTokenResponse(BaseModel):
    token: str
    url: str
    room_name: str


class VoiceChannelUser(BaseModel):
    id: str
    name: str
    is_muted: bool


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
            
            users.append(VoiceChannelUser(
                id=p.identity,
                name=p.name or p.identity,
                is_muted=is_muted,
            ))
        
        await api.aclose()
        
    except Exception:
        # Room may not exist yet (no participants)
        pass
    
    return users
