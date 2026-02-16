"""Add missing permission columns to servers, channel_groups, and channels.

Migration 003 was marked as executed but the columns were never actually created.
This migration re-adds them idempotently.

Revision ID: 006
Revises: 005
Create Date: 2026-02-16

"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy import inspect


# revision identifiers, used by Alembic.
revision = '006'
down_revision = '005'
branch_labels = None
depends_on = None


def _has_column(inspector, table: str, column: str) -> bool:
    return any(c['name'] == column for c in inspector.get_columns(table))


def upgrade() -> None:
    conn = op.get_bind()
    inspector = inspect(conn)

    # servers
    if not _has_column(inspector, 'servers', 'min_server_level'):
        op.add_column('servers', sa.Column('min_server_level', sa.Integer(), nullable=False, server_default='1'))
    if not _has_column(inspector, 'servers', 'min_internal_level'):
        op.add_column('servers', sa.Column('min_internal_level', sa.Integer(), nullable=False, server_default='1'))

    # channel_groups
    if not _has_column(inspector, 'channel_groups', 'min_server_level'):
        op.add_column('channel_groups', sa.Column('min_server_level', sa.Integer(), nullable=False, server_default='1'))
    if not _has_column(inspector, 'channel_groups', 'min_internal_level'):
        op.add_column('channel_groups', sa.Column('min_internal_level', sa.Integer(), nullable=False, server_default='1'))

    # channels
    for col in ['visibility_min_server_level', 'visibility_min_internal_level',
                'speak_min_server_level', 'speak_min_internal_level']:
        if not _has_column(inspector, 'channels', col):
            op.add_column('channels', sa.Column(col, sa.Integer(), nullable=False, server_default='1'))


def downgrade() -> None:
    op.drop_column('channels', 'speak_min_internal_level')
    op.drop_column('channels', 'speak_min_server_level')
    op.drop_column('channels', 'visibility_min_internal_level')
    op.drop_column('channels', 'visibility_min_server_level')
    op.drop_column('channel_groups', 'min_internal_level')
    op.drop_column('channel_groups', 'min_server_level')
    op.drop_column('servers', 'min_internal_level')
    op.drop_column('servers', 'min_server_level')
