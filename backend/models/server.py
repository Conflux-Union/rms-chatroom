from __future__ import annotations

from datetime import datetime, timezone
from enum import Enum
from typing import TYPE_CHECKING

from sqlalchemy import (
    ForeignKey,
    String,
    Text,
    Integer,
    DateTime,
    Enum as SAEnum,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from ..core.database import Base


def utc_now() -> datetime:
    """Return current UTC time as timezone-aware datetime."""
    return datetime.now(timezone.utc)


class ChannelType(str, Enum):
    TEXT = "TEXT"
    VOICE = "VOICE"


class MuteScope(str, Enum):
    GLOBAL = "global"  # Global mute (all servers)
    SERVER = "server"  # Server-level mute (all channels in server)
    CHANNEL = "channel"  # Channel-level mute (specific channel only)


class Server(Base):
    __tablename__ = "servers"

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    name: Mapped[str] = mapped_column(String(100), nullable=False)
    icon: Mapped[str | None] = mapped_column(String(255), nullable=True)
    owner_id: Mapped[int] = mapped_column(Integer, nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now
    )
    
    # Permission settings for server visibility
    # min_server_level: 1-4, minimum server permission level to access this server
    # min_internal_level: 1-2, minimum internal/external level (1=external, 2=internal)
    # Default: accessible to all (min_server_level=1, min_internal_level=1)
    min_server_level: Mapped[int] = mapped_column(Integer, default=1)  # 1-4
    min_internal_level: Mapped[int] = mapped_column(Integer, default=1)  # 1-2

    channels: Mapped[list["Channel"]] = relationship(
        "Channel", back_populates="server", cascade="all, delete-orphan"
    )
    channel_groups: Mapped[list["ChannelGroup"]] = relationship(
        "ChannelGroup", back_populates="server", cascade="all, delete-orphan"
    )


class ChannelGroup(Base):
    """Channel group for organizing channels."""

    __tablename__ = "channel_groups"

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    server_id: Mapped[int] = mapped_column(
        ForeignKey("servers.id", ondelete="CASCADE"), nullable=False, index=True
    )
    name: Mapped[str] = mapped_column(String(100), nullable=False)
    position: Mapped[int] = mapped_column(Integer, default=0)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now
    )

    # Permission settings for channel group visibility
    # min_server_level: 1-4, minimum server permission level to see this group (1=lowest, 4=highest)
    # min_internal_level: 1-2, minimum internal/external level (1=external, 2=internal)
    # Default: accessible to all (min_server_level=1, min_internal_level=1)
    min_server_level: Mapped[int] = mapped_column(Integer, default=1)  # 1-4
    min_internal_level: Mapped[int] = mapped_column(Integer, default=1)  # 1-2

    server: Mapped["Server"] = relationship("Server", back_populates="channel_groups")
    channels: Mapped[list["Channel"]] = relationship("Channel", back_populates="group")


class Channel(Base):
    __tablename__ = "channels"

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    server_id: Mapped[int] = mapped_column(
        ForeignKey("servers.id", ondelete="CASCADE"), nullable=False, index=True
    )
    group_id: Mapped[int | None] = mapped_column(
        ForeignKey("channel_groups.id", ondelete="SET NULL"), nullable=True, index=True
    )
    name: Mapped[str] = mapped_column(String(100), nullable=False)
    type: Mapped[ChannelType] = mapped_column(
        SAEnum(ChannelType), default=ChannelType.TEXT
    )
    # Position within group (for grouped channels) or legacy position
    position: Mapped[int] = mapped_column(Integer, default=0)
    # Top-level position for ungrouped channels (shares sequence with ChannelGroup.position)
    # Only meaningful when group_id is NULL
    top_position: Mapped[int] = mapped_column(Integer, default=0)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now
    )
    
    # Permission settings for channel visibility and speech permissions
    # visibility_min_server_level: 1-4, minimum server permission level to see this channel
    # visibility_min_internal_level: 1-2, minimum internal/external level to see this channel
    # speak_min_server_level: 1-4, minimum server permission level to speak/post in this channel
    # speak_min_internal_level: 1-2, minimum internal/external level to speak/post in this channel
    # Default: accessible to all and all can speak (min level = 1)
    visibility_min_server_level: Mapped[int] = mapped_column(Integer, default=1)  # 1-4
    visibility_min_internal_level: Mapped[int] = mapped_column(Integer, default=1)  # 1-2
    speak_min_server_level: Mapped[int] = mapped_column(Integer, default=1)  # 1-4
    speak_min_internal_level: Mapped[int] = mapped_column(Integer, default=1)  # 1-2

    server: Mapped["Server"] = relationship("Server", back_populates="channels")
    group: Mapped["ChannelGroup | None"] = relationship(
        "ChannelGroup", back_populates="channels"
    )
    messages: Mapped[list["Message"]] = relationship(
        "Message", back_populates="channel", cascade="all, delete-orphan"
    )
    voice_states: Mapped[list["VoiceState"]] = relationship(
        "VoiceState", back_populates="channel", cascade="all, delete-orphan"
    )


class Message(Base):
    __tablename__ = "messages"

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    channel_id: Mapped[int] = mapped_column(
        ForeignKey("channels.id", ondelete="CASCADE"), nullable=False, index=True
    )
    user_id: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    username: Mapped[str] = mapped_column(String(100), nullable=False)
    content: Mapped[str] = mapped_column(Text, nullable=False, default="")
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now, index=True
    )

    # Message recall/deletion fields
    is_deleted: Mapped[bool] = mapped_column(default=False, index=True)
    deleted_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    deleted_by: Mapped[int | None] = mapped_column(Integer, nullable=True)

    # Message editing field
    edited_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )

    # Reply feature
    reply_to_id: Mapped[int | None] = mapped_column(
        ForeignKey("messages.id", ondelete="SET NULL"), nullable=True, index=True
    )

    # Mentions feature - JSON array of user IDs: "[1, 2, 3]"
    mentioned_user_ids: Mapped[str | None] = mapped_column(Text, nullable=True)

    channel: Mapped["Channel"] = relationship("Channel", back_populates="messages")
    reply_to: Mapped["Message | None"] = relationship(
        "Message", remote_side="Message.id", foreign_keys=[reply_to_id], lazy="joined"
    )
    attachments: Mapped[list["Attachment"]] = relationship(
        "Attachment", back_populates="message", cascade="all, delete-orphan"
    )
    reactions: Mapped[list["Reaction"]] = relationship(
        "Reaction",
        back_populates="message",
        cascade="all, delete-orphan",
        lazy="selectin",
    )


class Attachment(Base):
    """File attachment for messages."""

    __tablename__ = "attachments"

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    message_id: Mapped[int | None] = mapped_column(
        ForeignKey("messages.id", ondelete="CASCADE"), nullable=True, index=True
    )
    channel_id: Mapped[int] = mapped_column(
        ForeignKey("channels.id", ondelete="CASCADE"), nullable=False, index=True
    )
    user_id: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    filename: Mapped[str] = mapped_column(String(255), nullable=False)
    stored_name: Mapped[str] = mapped_column(String(255), nullable=False, unique=True)
    content_type: Mapped[str] = mapped_column(String(100), nullable=False)
    size: Mapped[int] = mapped_column(Integer, nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now
    )

    message: Mapped["Message | None"] = relationship(
        "Message", back_populates="attachments"
    )


class VoiceState(Base):
    __tablename__ = "voice_states"

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    channel_id: Mapped[int] = mapped_column(
        ForeignKey("channels.id", ondelete="CASCADE"), nullable=False, index=True
    )
    user_id: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    username: Mapped[str] = mapped_column(String(100), nullable=False)
    muted: Mapped[bool] = mapped_column(default=False)
    deafened: Mapped[bool] = mapped_column(default=False)
    joined_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now
    )

    channel: Mapped["Channel"] = relationship("Channel", back_populates="voice_states")


class VoiceInvite(Base):
    """Single-use invite link for guest access to voice channels."""

    __tablename__ = "voice_invites"

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    channel_id: Mapped[int] = mapped_column(
        ForeignKey("channels.id", ondelete="CASCADE"), nullable=False
    )
    token: Mapped[str] = mapped_column(
        String(64), unique=True, index=True, nullable=False
    )
    created_by: Mapped[int] = mapped_column(Integer, nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now
    )
    used: Mapped[bool] = mapped_column(default=False)
    used_by_name: Mapped[str | None] = mapped_column(String(100), nullable=True)
    used_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )

    channel: Mapped["Channel"] = relationship("Channel")


class MuteRecord(Base):
    """User mute records with three scopes: global, server, or channel."""

    __tablename__ = "mute_records"

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    scope: Mapped[MuteScope] = mapped_column(SAEnum(MuteScope), nullable=False)

    # Nullable foreign keys for scope targeting
    server_id: Mapped[int | None] = mapped_column(
        ForeignKey("servers.id", ondelete="CASCADE"), nullable=True, index=True
    )
    channel_id: Mapped[int | None] = mapped_column(
        ForeignKey("channels.id", ondelete="CASCADE"), nullable=True, index=True
    )

    muted_until: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )  # NULL = permanent mute
    muted_by: Mapped[int] = mapped_column(Integer, nullable=False)
    reason: Mapped[str | None] = mapped_column(String(500), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now
    )

    # Relationships
    server: Mapped["Server | None"] = relationship("Server")
    channel: Mapped["Channel | None"] = relationship("Channel")


class Reaction(Base):
    """Emoji reaction on a message."""

    __tablename__ = "reactions"

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    message_id: Mapped[int] = mapped_column(
        ForeignKey("messages.id", ondelete="CASCADE"), nullable=False, index=True
    )
    user_id: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    username: Mapped[str] = mapped_column(String(100), nullable=False)
    emoji: Mapped[str] = mapped_column(String(32), nullable=False)  # Unicode emoji
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now
    )

    # Unique constraint: one reaction per user per emoji per message
    __table_args__ = (
        UniqueConstraint("message_id", "user_id", "emoji", name="uq_reaction"),
    )

    message: Mapped["Message"] = relationship("Message", back_populates="reactions")


class ReadPosition(Base):
    """User's read position per channel for cross-device sync."""

    __tablename__ = "read_positions"

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    channel_id: Mapped[int] = mapped_column(
        ForeignKey("channels.id", ondelete="CASCADE"), nullable=False, index=True
    )
    last_read_message_id: Mapped[int] = mapped_column(Integer, nullable=False)
    # Track if user has unread mentions in this channel
    has_mention: Mapped[bool] = mapped_column(default=False)
    last_mention_message_id: Mapped[int | None] = mapped_column(Integer, nullable=True)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now, onupdate=utc_now
    )

    # Unique constraint: one read position per user per channel
    __table_args__ = (
        UniqueConstraint("user_id", "channel_id", name="uq_read_position"),
    )

    channel: Mapped["Channel"] = relationship("Channel")
