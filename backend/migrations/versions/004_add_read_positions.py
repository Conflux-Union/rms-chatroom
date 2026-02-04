"""Add read_positions table for cross-device sync

This migration adds the read_positions table to track user's read position
per channel, enabling cross-device synchronization of unread messages and mentions.

Revision ID: 004
Revises: 003
Create Date: 2026-01-14
"""

from __future__ import annotations

from typing import Sequence

from alembic import op
import sqlalchemy as sa
from sqlalchemy import inspect


revision: str = "004"
down_revision: str | None = "003"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def table_exists(table_name: str) -> bool:
    """Check if a table exists in the database."""
    conn = op.get_bind()
    inspector = inspect(conn)
    return table_name in inspector.get_table_names()


def upgrade() -> None:
    if table_exists("read_positions"):
        return

    op.create_table(
        "read_positions",
        sa.Column("id", sa.Integer(), primary_key=True, autoincrement=True),
        sa.Column("user_id", sa.Integer(), nullable=False, index=True),
        sa.Column(
            "channel_id",
            sa.Integer(),
            sa.ForeignKey("channels.id", ondelete="CASCADE"),
            nullable=False,
            index=True,
        ),
        sa.Column("last_read_message_id", sa.Integer(), nullable=False),
        sa.Column("has_mention", sa.Boolean(), nullable=False, server_default="0"),
        sa.Column("last_mention_message_id", sa.Integer(), nullable=True),
        sa.Column(
            "updated_at",
            sa.DateTime(),
            nullable=False,
            server_default=sa.func.current_timestamp(),
        ),
        sa.UniqueConstraint("user_id", "channel_id", name="uq_read_position"),
    )


def downgrade() -> None:
    op.drop_table("read_positions")
