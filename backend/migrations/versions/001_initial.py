"""Initial schema - baseline migration

This migration establishes the baseline schema. It uses batch mode for SQLite
compatibility and checks for existing tables/columns before making changes.

Revision ID: 001
Revises:
Create Date: 2025-01-11
"""

from __future__ import annotations

from typing import Sequence

from alembic import op
import sqlalchemy as sa
from sqlalchemy import inspect


revision: str = "001"
down_revision: str | None = None
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def get_existing_tables() -> set[str]:
    """Get set of existing table names."""
    conn = op.get_bind()
    inspector = inspect(conn)
    return set(inspector.get_table_names())


def get_existing_columns(table_name: str) -> set[str]:
    """Get set of existing column names for a table."""
    conn = op.get_bind()
    inspector = inspect(conn)
    try:
        return {col["name"] for col in inspector.get_columns(table_name)}
    except Exception:
        return set()


def upgrade() -> None:
    existing_tables = get_existing_tables()

    # Create servers table if not exists
    if "servers" not in existing_tables:
        op.create_table(
            "servers",
            sa.Column("id", sa.Integer(), primary_key=True, autoincrement=True),
            sa.Column("name", sa.String(100), nullable=False),
            sa.Column("icon", sa.String(255), nullable=True),
            sa.Column("owner_id", sa.Integer(), nullable=False),
            sa.Column("created_at", sa.DateTime(), server_default=sa.func.now()),
        )

    # Create channel_groups table if not exists
    if "channel_groups" not in existing_tables:
        op.create_table(
            "channel_groups",
            sa.Column("id", sa.Integer(), primary_key=True, autoincrement=True),
            sa.Column(
                "server_id",
                sa.Integer(),
                sa.ForeignKey("servers.id", ondelete="CASCADE"),
                nullable=False,
            ),
            sa.Column("name", sa.String(100), nullable=False),
            sa.Column("position", sa.Integer(), server_default="0"),
            sa.Column("created_at", sa.DateTime(), server_default=sa.func.now()),
        )

    # Create channels table if not exists
    if "channels" not in existing_tables:
        op.create_table(
            "channels",
            sa.Column("id", sa.Integer(), primary_key=True, autoincrement=True),
            sa.Column(
                "server_id",
                sa.Integer(),
                sa.ForeignKey("servers.id", ondelete="CASCADE"),
                nullable=False,
            ),
            sa.Column(
                "group_id",
                sa.Integer(),
                sa.ForeignKey("channel_groups.id", ondelete="SET NULL"),
                nullable=True,
            ),
            sa.Column("name", sa.String(100), nullable=False),
            sa.Column(
                "type",
                sa.Enum("text", "voice", name="channeltype"),
                server_default="text",
            ),
            sa.Column("position", sa.Integer(), server_default="0"),
            sa.Column("created_at", sa.DateTime(), server_default=sa.func.now()),
        )
    else:
        # Add group_id column if channels table exists but column doesn't
        existing_cols = get_existing_columns("channels")
        if "group_id" not in existing_cols:
            with op.batch_alter_table("channels", schema=None) as batch_op:
                batch_op.add_column(sa.Column("group_id", sa.Integer(), nullable=True))
                batch_op.create_foreign_key(
                    "fk_channels_group_id",
                    "channel_groups",
                    ["group_id"],
                    ["id"],
                    ondelete="SET NULL",
                )

    # Create messages table if not exists
    if "messages" not in existing_tables:
        op.create_table(
            "messages",
            sa.Column("id", sa.Integer(), primary_key=True, autoincrement=True),
            sa.Column(
                "channel_id",
                sa.Integer(),
                sa.ForeignKey("channels.id", ondelete="CASCADE"),
                nullable=False,
                index=True,
            ),
            sa.Column("user_id", sa.Integer(), nullable=False, index=True),
            sa.Column("username", sa.String(100), nullable=False),
            sa.Column("content", sa.Text(), nullable=False, server_default=""),
            sa.Column(
                "created_at", sa.DateTime(), server_default=sa.func.now(), index=True
            ),
            sa.Column("is_deleted", sa.Boolean(), server_default="0", index=True),
            sa.Column("deleted_at", sa.DateTime(), nullable=True),
            sa.Column("deleted_by", sa.Integer(), nullable=True),
            sa.Column("edited_at", sa.DateTime(), nullable=True),
            sa.Column(
                "reply_to_id",
                sa.Integer(),
                sa.ForeignKey("messages.id", ondelete="SET NULL"),
                nullable=True,
                index=True,
            ),
            sa.Column("mentioned_user_ids", sa.Text(), nullable=True),
        )

    # Create attachments table if not exists
    if "attachments" not in existing_tables:
        op.create_table(
            "attachments",
            sa.Column("id", sa.Integer(), primary_key=True, autoincrement=True),
            sa.Column(
                "message_id",
                sa.Integer(),
                sa.ForeignKey("messages.id", ondelete="CASCADE"),
                nullable=True,
                index=True,
            ),
            sa.Column(
                "channel_id",
                sa.Integer(),
                sa.ForeignKey("channels.id", ondelete="CASCADE"),
                nullable=False,
                index=True,
            ),
            sa.Column("user_id", sa.Integer(), nullable=False),
            sa.Column("filename", sa.String(255), nullable=False),
            sa.Column("stored_name", sa.String(255), nullable=False, unique=True),
            sa.Column("content_type", sa.String(100), nullable=False),
            sa.Column("size", sa.Integer(), nullable=False),
            sa.Column("created_at", sa.DateTime(), server_default=sa.func.now()),
        )

    # Create voice_states table if not exists
    if "voice_states" not in existing_tables:
        op.create_table(
            "voice_states",
            sa.Column("id", sa.Integer(), primary_key=True, autoincrement=True),
            sa.Column(
                "channel_id",
                sa.Integer(),
                sa.ForeignKey("channels.id", ondelete="CASCADE"),
                nullable=False,
            ),
            sa.Column("user_id", sa.Integer(), nullable=False, unique=True),
            sa.Column("username", sa.String(100), nullable=False),
            sa.Column("muted", sa.Boolean(), server_default="0"),
            sa.Column("deafened", sa.Boolean(), server_default="0"),
            sa.Column("joined_at", sa.DateTime(), server_default=sa.func.now()),
        )

    # Create voice_invites table if not exists
    if "voice_invites" not in existing_tables:
        op.create_table(
            "voice_invites",
            sa.Column("id", sa.Integer(), primary_key=True, autoincrement=True),
            sa.Column(
                "channel_id",
                sa.Integer(),
                sa.ForeignKey("channels.id", ondelete="CASCADE"),
                nullable=False,
            ),
            sa.Column("token", sa.String(64), nullable=False, unique=True, index=True),
            sa.Column("created_by", sa.Integer(), nullable=False),
            sa.Column("created_at", sa.DateTime(), server_default=sa.func.now()),
            sa.Column("used", sa.Boolean(), server_default="0"),
            sa.Column("used_by_name", sa.String(100), nullable=True),
            sa.Column("used_at", sa.DateTime(), nullable=True),
        )

    # Create mute_records table if not exists
    if "mute_records" not in existing_tables:
        op.create_table(
            "mute_records",
            sa.Column("id", sa.Integer(), primary_key=True, autoincrement=True),
            sa.Column("user_id", sa.Integer(), nullable=False, index=True),
            sa.Column(
                "scope",
                sa.Enum("global", "server", "channel", name="mutescope"),
                nullable=False,
            ),
            sa.Column(
                "server_id",
                sa.Integer(),
                sa.ForeignKey("servers.id", ondelete="CASCADE"),
                nullable=True,
                index=True,
            ),
            sa.Column(
                "channel_id",
                sa.Integer(),
                sa.ForeignKey("channels.id", ondelete="CASCADE"),
                nullable=True,
                index=True,
            ),
            sa.Column("muted_until", sa.DateTime(), nullable=True),
            sa.Column("muted_by", sa.Integer(), nullable=False),
            sa.Column("reason", sa.String(500), nullable=True),
            sa.Column("created_at", sa.DateTime(), server_default=sa.func.now()),
        )

    # Create reactions table if not exists
    if "reactions" not in existing_tables:
        op.create_table(
            "reactions",
            sa.Column("id", sa.Integer(), primary_key=True, autoincrement=True),
            sa.Column(
                "message_id",
                sa.Integer(),
                sa.ForeignKey("messages.id", ondelete="CASCADE"),
                nullable=False,
                index=True,
            ),
            sa.Column("user_id", sa.Integer(), nullable=False, index=True),
            sa.Column("username", sa.String(100), nullable=False),
            sa.Column("emoji", sa.String(32), nullable=False),
            sa.Column("created_at", sa.DateTime(), server_default=sa.func.now()),
            sa.UniqueConstraint("message_id", "user_id", "emoji", name="uq_reaction"),
        )


def downgrade() -> None:
    # Drop tables in reverse order of dependencies
    op.drop_table("reactions")
    op.drop_table("mute_records")
    op.drop_table("voice_invites")
    op.drop_table("voice_states")
    op.drop_table("attachments")
    op.drop_table("messages")
    op.drop_table("channels")
    op.drop_table("channel_groups")
    op.drop_table("servers")
