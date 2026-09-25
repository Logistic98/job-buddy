import os
from datetime import datetime, timezone
from types import SimpleNamespace
from uuid import uuid4

import pytest
from sqlalchemy import Boolean, Column, DateTime, Integer, MetaData, String, Table, select
from sqlalchemy.ext.asyncio import create_async_engine
from sqlalchemy.schema import CreateSchema, DropSchema

from scripts import migrate_legacy


class Snapshot:
    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        return False


class LegacyConnection(Snapshot):
    def __init__(self):
        self.now = datetime.now(timezone.utc)
        self.reflections = 0

    async def execution_options(self, **kwargs):
        assert kwargs == {"isolation_level": "REPEATABLE READ", "postgresql_readonly": True}
        return self

    async def run_sync(self, callback):
        self.reflections += 1
        if self.reflections == 1:
            return Table("agent_memory_items", MetaData(), Column("id", String, primary_key=True))
        return Table("agent_memory_revisions", MetaData(), Column("memory_id", String), Column("version", Integer))

    async def scalar(self, query):
        return 1

    def begin(self):
        return Snapshot()

    def stream(self, query):
        assert "ORDER BY agent_memory_items.id" in str(query)
        return self

    def mappings(self):
        return self

    async def __aiter__(self):
        yield {
            "id": "mem_legacy123",
            "tenant_id": "tenant",
            "operator_id": "user",
            "scope": "long_term",
            "kind": "long_term",
            "source": "manual",
            "enabled": True,
            "content": "当前事实杭州",
            "version": 2,
            "expires_at": None,
            "created_at": self.now,
        }

    async def execute(self, query):
        assert "mem_legacy123" in query.compile().params.values()
        rows = [{"content": "以前事实上海", "recorded_at": self.now}]
        return SimpleNamespace(mappings=lambda: SimpleNamespace(all=lambda: rows))


class LegacyEngine:
    def connect(self):
        return LegacyConnection()

    async def dispose(self):
        pass


@pytest.mark.asyncio
async def test_migration_preserves_id_owner_and_rollback(memory_store, monkeypatch):
    monkeypatch.setattr(migrate_legacy, "create_async_engine", lambda *args, **kwargs: LegacyEngine())
    monkeypatch.setattr(migrate_legacy, "database_url", lambda: "postgresql://test")
    monkeypatch.setattr(migrate_legacy, "build_memory", lambda: memory_store.memory)
    monkeypatch.setattr(migrate_legacy, "MemoryStore", lambda engine: memory_store)
    close = memory_store.close
    monkeypatch.setattr(memory_store, "close", lambda: None)
    try:
        await migrate_legacy.migrate(False)
        assert memory_store.list_items(tenant_id="tenant", operator_id="user") == []
        await migrate_legacy.migrate(True)
        await migrate_legacy.migrate(True)
        records = memory_store.list_items(tenant_id="tenant", operator_id="user")
        assert len(records) == 1
        assert records[0]["id"] == "mem_legacy123"
        assert records[0]["content"] == "当前事实杭州"
        result = memory_store.rollback("mem_legacy123", tenant_id="tenant", operator_id="user")
        assert result["content"] == "以前事实上海"
    finally:
        monkeypatch.setattr(memory_store, "close", close)


@pytest.mark.asyncio
async def test_migration_reads_real_postgres_snapshot(memory_store, monkeypatch):
    url = os.getenv("CHECKPOINT_TEST_DATABASE_URL")
    if not url:
        pytest.skip("CHECKPOINT_TEST_DATABASE_URL is required for PostgreSQL integration")
    engine = create_async_engine(url.replace("postgresql://", "postgresql+asyncpg://", 1))
    schema = "legacy_test_" + uuid4().hex
    metadata = MetaData(schema=schema)
    items = Table(
        "agent_memory_items",
        metadata,
        Column("id", String, primary_key=True),
        *[Column(name, String) for name in ["tenant_id", "operator_id", "scope", "kind", "source", "content"]],
        Column("enabled", Boolean),
        Column("version", Integer),
        Column("expires_at", DateTime(timezone=True)),
        Column("created_at", DateTime(timezone=True)),
    )
    revisions = Table(
        "agent_memory_revisions",
        metadata,
        Column("memory_id", String),
        Column("version", Integer),
        Column("content", String),
        Column("recorded_at", DateTime(timezone=True)),
    )
    now = datetime.now(timezone.utc)
    close = memory_store.close
    try:
        async with engine.begin() as connection:
            await connection.execute(CreateSchema(schema))
            await connection.run_sync(metadata.create_all)
            await connection.execute(
                items.insert().values(
                    id="mem_legacy123",
                    tenant_id="tenant",
                    operator_id="user",
                    scope="long_term",
                    kind="long_term",
                    source="manual",
                    enabled=True,
                    content="当前事实杭州",
                    version=2,
                    created_at=now,
                )
            )
            await connection.execute(
                revisions.insert().values(
                    memory_id="mem_legacy123",
                    version=1,
                    content="以前事实上海",
                    recorded_at=now,
                )
            )
        monkeypatch.setenv("AGENT_MEMORY_DB_SSL_MODE", "disable")
        separator = "&" if "?" in url else "?"
        monkeypatch.setattr(migrate_legacy, "database_url", lambda: url + separator + "search_path=" + schema)
        monkeypatch.setattr(migrate_legacy, "build_memory", lambda: memory_store.memory)
        monkeypatch.setattr(migrate_legacy, "MemoryStore", lambda engine: memory_store)
        monkeypatch.setattr(memory_store, "close", lambda: None)
        await migrate_legacy.migrate(False)
        assert memory_store.list_items(tenant_id="tenant", operator_id="user") == []
        await migrate_legacy.migrate(True)
        await migrate_legacy.migrate(True)
        assert len(memory_store.list_items(tenant_id="tenant", operator_id="user")) == 1
        assert (
            memory_store.rollback("mem_legacy123", tenant_id="tenant", operator_id="user")["content"] == "以前事实上海"
        )
        async with engine.connect() as connection:
            assert await connection.scalar(select(items.c.content)) == "当前事实杭州"
    finally:
        monkeypatch.setattr(memory_store, "close", close)
        async with engine.begin() as connection:
            await connection.execute(DropSchema(schema, cascade=True, if_exists=True))
        await engine.dispose()
