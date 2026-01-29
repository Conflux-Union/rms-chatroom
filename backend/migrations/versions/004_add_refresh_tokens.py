"""Add auth_refresh_tokens table for persistent refresh tokens

This migration adds a dedicated table for refresh tokens so that refresh/revoke
works across restarts and multi-worker deployments.

Revision ID: 004_add_refresh_tokens
Revises: 003_add_read_positions
Create Date: 2026-01-29
"""

from __future__ import annotations

from typing import Sequence

from alembic import op
import sqlalchemy as sa
from sqlalchemy import inspect


revision: str = "004_add_refresh_tokens"
down_revision: str | None = "003_add_read_positions"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def table_exists(table_name: str) -> bool:
    conn = op.get_bind()
    inspector = inspect(conn)
    return table_name in inspector.get_table_names()


def upgrade() -> None:
    if table_exists("auth_refresh_tokens"):
        return

    op.create_table(
        "auth_refresh_tokens",
        sa.Column("id", sa.Integer(), primary_key=True, autoincrement=True),
        sa.Column("token_hash", sa.String(64), nullable=False),
        sa.Column("user_id", sa.Integer(), nullable=False, index=True),
        sa.Column("username", sa.String(100), nullable=False),
        sa.Column("nickname", sa.String(100), nullable=True),
        sa.Column("permission_level", sa.Integer(), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False, index=True),
        sa.Column("revoked_at", sa.DateTime(timezone=True), nullable=True, index=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.func.current_timestamp(),
        ),
        sa.UniqueConstraint("token_hash", name="uq_auth_refresh_tokens_hash"),
    )
    op.create_index(
        "ix_auth_refresh_tokens_token_hash",
        "auth_refresh_tokens",
        ["token_hash"],
        unique=True,
    )


def downgrade() -> None:
    op.drop_index("ix_auth_refresh_tokens_token_hash", table_name="auth_refresh_tokens")
    op.drop_table("auth_refresh_tokens")

