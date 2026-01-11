"""Alembic environment configuration for async SQLAlchemy."""

from __future__ import annotations

import asyncio
import sys
from logging.config import fileConfig
from pathlib import Path

from alembic import context
from sqlalchemy import pool
from sqlalchemy.engine import Connection
from sqlalchemy.ext.asyncio import async_engine_from_config

# Add backend directory to path for imports
backend_dir = Path(__file__).resolve().parent.parent
if str(backend_dir) not in sys.path:
    sys.path.insert(0, str(backend_dir))

# Import settings directly (avoid relative imports)
from core.config import get_settings

# Import Base and all models to register them with metadata
# We need to import the models module after setting up the path
# but the models use relative imports from core.database
# So we import Base first, then manually import models
from sqlalchemy.orm import DeclarativeBase


class Base(DeclarativeBase):
    """Local Base class for Alembic - mirrors core.database.Base"""

    pass


# We can't easily import models due to relative import issues
# Instead, we'll define the metadata manually or use a different approach
# For now, let's just use an empty metadata and rely on the migration files
target_metadata = Base.metadata

config = context.config

# Load logging config from alembic.ini
if config.config_file_name is not None:
    fileConfig(config.config_file_name)


def get_url() -> str:
    """Get database URL from application settings."""
    settings = get_settings()
    url = settings.database_url
    # Convert async URL to sync for Alembic (it handles async internally)
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
    url = get_url()
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


async def run_async_migrations() -> None:
    """Run migrations in async mode."""
    settings = get_settings()

    # Build config dict for async engine
    configuration = {
        "sqlalchemy.url": settings.database_url,
        "sqlalchemy.poolclass": pool.NullPool,
    }

    connectable = async_engine_from_config(
        configuration,
        prefix="sqlalchemy.",
    )

    async with connectable.connect() as connection:
        await connection.run_sync(do_run_migrations)

    await connectable.dispose()


def run_migrations_online() -> None:
    """Run migrations in 'online' mode."""
    asyncio.run(run_async_migrations())


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
