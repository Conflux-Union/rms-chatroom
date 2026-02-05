"""Remove min_server_level from servers table

Revision ID: 005
Revises: 004
Create Date: 2026-02-04

"""
from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision = '005'
down_revision = '004'
branch_labels = None
depends_on = None


def upgrade() -> None:
    # Remove min_server_level column from servers table
    op.drop_column('servers', 'min_server_level')


def downgrade() -> None:
    # Add min_server_level column back to servers table
    op.add_column('servers',
        sa.Column('min_server_level', sa.Integer(), nullable=False, server_default='1')
    )
