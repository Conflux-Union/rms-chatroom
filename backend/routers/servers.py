from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from ..core.database import get_db
from ..models.server import Server, Channel, ChannelType
from .deps import CurrentUser, AdminUser


router = APIRouter(prefix="/api/servers", tags=["servers"])


class ServerCreate(BaseModel):
    name: str
    icon: str | None = None


class ServerResponse(BaseModel):
    id: int
    name: str
    icon: str | None
    owner_id: int

    class Config:
        from_attributes = True


class ChannelResponse(BaseModel):
    id: int
    name: str
    type: str
    position: int

    class Config:
        from_attributes = True


class ServerDetailResponse(ServerResponse):
    channels: list[ChannelResponse]


@router.get("", response_model=list[ServerResponse])
async def list_servers(user: CurrentUser, db: AsyncSession = Depends(get_db)):
    """List all servers."""
    result = await db.execute(select(Server).order_by(Server.id))
    servers = result.scalars().all()
    return servers


@router.post("", response_model=ServerResponse, status_code=status.HTTP_201_CREATED)
async def create_server(payload: ServerCreate, user: AdminUser, db: AsyncSession = Depends(get_db)):
    """Create a new server (admin only)."""
    server = Server(name=payload.name, icon=payload.icon, owner_id=user["id"])
    db.add(server)
    await db.flush()

    # Create default channels
    general_text = Channel(server_id=server.id, name="general", type=ChannelType.TEXT, position=0)
    general_voice = Channel(server_id=server.id, name="General", type=ChannelType.VOICE, position=1)
    db.add_all([general_text, general_voice])
    await db.flush()

    return server


@router.get("/{server_id}", response_model=ServerDetailResponse)
async def get_server(server_id: int, user: CurrentUser, db: AsyncSession = Depends(get_db)):
    """Get server details with channels."""
    result = await db.execute(
        select(Server).where(Server.id == server_id).options(selectinload(Server.channels))
    )
    server = result.scalar_one_or_none()
    if not server:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Server not found")

    return ServerDetailResponse(
        id=server.id,
        name=server.name,
        icon=server.icon,
        owner_id=server.owner_id,
        channels=[
            ChannelResponse(id=c.id, name=c.name, type=c.type.value, position=c.position)
            for c in sorted(server.channels, key=lambda x: x.position)
        ],
    )


@router.delete("/{server_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_server(server_id: int, user: AdminUser, db: AsyncSession = Depends(get_db)):
    """Delete a server (admin only)."""
    result = await db.execute(select(Server).where(Server.id == server_id))
    server = result.scalar_one_or_none()
    if not server:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Server not found")

    await db.delete(server)
