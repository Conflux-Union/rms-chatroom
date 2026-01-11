"""Add top_position column to channels table

This migration adds the top_position column for unified top-level sorting.
Ungrouped channels use top_position to sort alongside channel groups.
Grouped channels use position for within-group sorting.

Revision ID: 002_add_top_position
Revises: 001_initial
Create Date: 2025-01-11
"""

from __future__ import annotations

from typing import Sequence

from alembic import op
import sqlalchemy as sa
from sqlalchemy import inspect, text


revision: str = "002_add_top_position"
down_revision: str | None = "001_initial"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def get_existing_columns(table_name: str) -> set[str]:
    """Get set of existing column names for a table."""
    conn = op.get_bind()
    inspector = inspect(conn)
    try:
        return {col["name"] for col in inspector.get_columns(table_name)}
    except Exception:
        return set()


def upgrade() -> None:
    existing_cols = get_existing_columns("channels")

    if "top_position" not in existing_cols:
        # Add top_position column with default 0
        with op.batch_alter_table("channels", schema=None) as batch_op:
            batch_op.add_column(
                sa.Column(
                    "top_position", sa.Integer(), server_default="0", nullable=False
                )
            )

        # Migrate existing data: for ungrouped channels, copy position to top_position
        conn = op.get_bind()
        conn.execute(
            text("""
            UPDATE channels 
            SET top_position = position 
            WHERE group_id IS NULL
        """)
        )


def downgrade() -> None:
    with op.batch_alter_table("channels", schema=None) as batch_op:
        batch_op.drop_column("top_position")
