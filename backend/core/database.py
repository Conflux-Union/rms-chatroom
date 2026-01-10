from __future__ import annotations

import logging
from sqlalchemy import text, inspect
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.orm import DeclarativeBase

from .config import get_settings

logger = logging.getLogger(__name__)


class Base(DeclarativeBase):
    pass


settings = get_settings()
engine = create_async_engine(settings.database_url, echo=settings.debug)
async_session_maker = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)


async def run_migrations(conn):
    """Run database migrations to add missing tables and columns."""
    
    def sync_migrations(connection):
        """Synchronous migration logic."""
        inspector = inspect(connection)
        existing_tables = inspector.get_table_names()
        
        migrations_run = []
        
        # Migration 1: Create channel_groups table if not exists
        if "channel_groups" not in existing_tables:
            connection.execute(text("""
                CREATE TABLE channel_groups (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    server_id INTEGER NOT NULL,
                    name VARCHAR(100) NOT NULL,
                    position INTEGER DEFAULT 0,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (server_id) REFERENCES servers(id) ON DELETE CASCADE
                )
            """))
            migrations_run.append("Created channel_groups table")
        
        # Migration 2: Add group_id column to channels if not exists
        if "channels" in existing_tables:
            columns = [col["name"] for col in inspector.get_columns("channels")]
            if "group_id" not in columns:
                connection.execute(text("""
                    ALTER TABLE channels ADD COLUMN group_id INTEGER 
                    REFERENCES channel_groups(id) ON DELETE SET NULL
                """))
                migrations_run.append("Added group_id column to channels")
        
        return migrations_run
    
    migrations = await conn.run_sync(sync_migrations)
    for migration in migrations:
        logger.info(f"Migration: {migration}")
    
    return migrations


async def init_db():
    async with engine.begin() as conn:
        # First create all tables from models
        await conn.run_sync(Base.metadata.create_all)
        # Then run any additional migrations
        await run_migrations(conn)


async def get_db():
    async with async_session_maker() as session:
        try:
            yield session
            await session.commit()
        except Exception:
            await session.rollback()
            raise
