"""Alembic environment configuration for SQLAlchemy."""

from __future__ import annotations

import sys
from logging.config import fileConfig
from pathlib import Path

from alembic import context
from sqlalchemy import create_engine, pool
from sqlalchemy.engine import Connection

# Add backend directory to path for imports
backend_dir = Path(__file__).resolve().parent.parent
if str(backend_dir) not in sys.path:
    sys.path.insert(0, str(backend_dir))

from backend.core.config import get_settings
from backend.core.database import Base

# Import all models so Alembic can detect schema changes
import backend.models.server
import backend.models.auth

target_metadata = Base.metadata

config = context.config

# Load logging config from alembic.ini
if config.config_file_name is not None:
    fileConfig(config.config_file_name)


def get_sync_url() -> str:
    """Get synchronous database URL from application settings."""
    settings = get_settings()
    url = settings.database_url
    # Convert async URL to sync for Alembic
    # aiosqlite -> sqlite, aiomysql -> mysql+pymysql
    if url.startswith("sqlite+aiosqlite"):
        return url.replace("sqlite+aiosqlite", "sqlite")
    if url.startswith("mysql+aiomysql"):
        return url.replace("mysql+aiomysql", "mysql+pymysql")
    return url


def run_migrations_offline() -> None:
    """Run migrations in 'offline' mode.

    This generates SQL scripts without connecting to the database.
    """
    url = get_sync_url()
    context.configure(
        url=url,
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
    )

    with context.begin_transaction():
        context.run_migrations()


def do_run_migrations(connection: Connection) -> None:
    """Run migrations with the given connection."""
    context.configure(connection=connection, target_metadata=target_metadata)

    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online() -> None:
    """Run migrations in 'online' mode using synchronous engine."""
    url = get_sync_url()

    connectable = create_engine(
        url,
        poolclass=pool.NullPool,
    )

    with connectable.connect() as connection:
        do_run_migrations(connection)


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
