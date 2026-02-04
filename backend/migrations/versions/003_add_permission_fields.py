"""Add SSO permission level fields to servers, channel_groups, and channels.

Revision ID: 003
Revises: 002
Create Date: 2026-02-04 00:00:00.000000

"""
from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision = '003'
down_revision = '002'
branch_labels = None
depends_on = None


def upgrade() -> None:
    # Add permission fields to servers table
    op.add_column('servers', sa.Column('min_server_level', sa.Integer(), nullable=False, server_default='1'))
    op.add_column('servers', sa.Column('min_internal_level', sa.Integer(), nullable=False, server_default='1'))
    
    # Add permission fields to channel_groups table
    op.add_column('channel_groups', sa.Column('min_server_level', sa.Integer(), nullable=False, server_default='1'))
    op.add_column('channel_groups', sa.Column('min_internal_level', sa.Integer(), nullable=False, server_default='1'))
    
    # Add permission fields to channels table
    op.add_column('channels', sa.Column('visibility_min_server_level', sa.Integer(), nullable=False, server_default='1'))
    op.add_column('channels', sa.Column('visibility_min_internal_level', sa.Integer(), nullable=False, server_default='1'))
    op.add_column('channels', sa.Column('speak_min_server_level', sa.Integer(), nullable=False, server_default='1'))
    op.add_column('channels', sa.Column('speak_min_internal_level', sa.Integer(), nullable=False, server_default='1'))


def downgrade() -> None:
    # Drop permission fields from channels table
    op.drop_column('channels', 'speak_min_internal_level')
    op.drop_column('channels', 'speak_min_server_level')
    op.drop_column('channels', 'visibility_min_internal_level')
    op.drop_column('channels', 'visibility_min_server_level')
    
    # Drop permission fields from channel_groups table
    op.drop_column('channel_groups', 'min_internal_level')
    op.drop_column('channel_groups', 'min_server_level')
    
    # Drop permission fields from servers table
    op.drop_column('servers', 'min_internal_level')
    op.drop_column('servers', 'min_server_level')
