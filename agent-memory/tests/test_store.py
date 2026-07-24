from unittest.mock import AsyncMock

import asyncpg
import pytest

from app.store import MemoryStore, PostgresMemoryStore


async def test_add_and_search_memory():
    store = MemoryStore()
    item = store.add("session", "Agent Loop needs trace")
    assert item.id.startswith("mem_")
    results = await store.search("trace", "session")
    assert results[0].content == "Agent Loop needs trace"


async def test_list_clear_and_disabled_search():
    store = MemoryStore()
    first = store.add(
        "long_term",
        "优先远程岗位",
        kind="long_term",
        category="preference",
        source="manual",
    )
    store.add("long_term", "排除外包岗位", enabled=False)

    listed = store.list_items("long_term")
    assert len(listed) == 2
    assert first.id in {item.id for item in listed}
    assert [item.id for item in await store.search("外包", "long_term")] == []
    assert store.clear(tenant_id="default-tenant", operator_id="anonymous", scope="long_term") == 2
    assert store.list_items("long_term") == []


def test_postgres_store_derives_dsn_from_spring_datasource(monkeypatch):
    monkeypatch.delenv("AGENT_MEMORY_DATABASE_URL", raising=False)
    monkeypatch.delenv("DATABASE_URL", raising=False)
    monkeypatch.setenv("SPRING_DATASOURCE_URL", "jdbc:postgresql://127.0.0.1:5432/job_buddy")
    monkeypatch.setenv("SPRING_DATASOURCE_USERNAME", "job_buddy")
    monkeypatch.setenv("SPRING_DATASOURCE_PASSWORD", "")

    store = PostgresMemoryStore()

    assert store.dsn == "postgresql://job_buddy@127.0.0.1:5432/job_buddy"


def test_postgres_store_disables_ssl_when_mode_is_not_declared(monkeypatch):
    monkeypatch.delenv("AGENT_MEMORY_DB_SSL_MODE", raising=False)
    store = PostgresMemoryStore("postgresql://job_buddy@127.0.0.1:5432/job_buddy")

    assert store._database_ssl_mode() == "disable"


def test_postgres_store_honors_dsn_and_environment_ssl_modes(monkeypatch):
    monkeypatch.delenv("AGENT_MEMORY_DB_SSL_MODE", raising=False)
    store = PostgresMemoryStore("postgresql://job_buddy@db.example/job_buddy?sslmode=require")
    assert store._database_ssl_mode() == "require"

    monkeypatch.setenv("AGENT_MEMORY_DB_SSL_MODE", "verify-full")
    assert store._database_ssl_mode() == "verify-full"


def test_postgres_store_rejects_invalid_ssl_mode(monkeypatch):
    monkeypatch.setenv("AGENT_MEMORY_DB_SSL_MODE", "sometimes")
    store = PostgresMemoryStore("postgresql://job_buddy@127.0.0.1:5432/job_buddy")

    with pytest.raises(ValueError, match="AGENT_MEMORY_DB_SSL_MODE"):
        store._database_ssl_mode()


async def test_postgres_connect_error_is_redacted(monkeypatch):
    monkeypatch.delenv("AGENT_MEMORY_DB_SSL_MODE", raising=False)
    monkeypatch.setenv("AGENT_MEMORY_DB_CONNECT_ATTEMPTS", "1")
    create_pool = AsyncMock(side_effect=ConnectionError("TLS handshake failed with secret"))
    monkeypatch.setattr("app.store.asyncpg.create_pool", create_pool)
    store = PostgresMemoryStore("postgresql://job_buddy:top-secret@db.example:5433/job_buddy")

    with pytest.raises(RuntimeError, match="db.example:5433") as caught:
        await store.connect()

    assert "top-secret" not in str(caught.value)
    assert create_pool.await_args.kwargs["ssl"] == "disable"


async def test_postgres_connect_retries_transient_connection_failure(monkeypatch):
    monkeypatch.setenv("AGENT_MEMORY_DB_CONNECT_ATTEMPTS", "4")
    monkeypatch.setenv("AGENT_MEMORY_DB_CONNECT_BACKOFF_SECONDS", "0")
    pool = AsyncMock()
    create_pool = AsyncMock(
        side_effect=[
            asyncpg.exceptions.ConnectionDoesNotExistError("connection was closed in the middle of operation"),
            pool,
        ]
    )
    monkeypatch.setattr("app.store.asyncpg.create_pool", create_pool)
    store = PostgresMemoryStore("postgresql://job_buddy@db.example:5433/job_buddy")
    store.init_schema = AsyncMock()

    await store.connect()

    assert create_pool.await_count == 2
    assert create_pool.await_args.kwargs["timeout"] == 8.0
    assert store.pool is pool
    store.init_schema.assert_awaited_once()


async def test_postgres_connect_does_not_retry_authentication_failure(monkeypatch):
    monkeypatch.setenv("AGENT_MEMORY_DB_CONNECT_ATTEMPTS", "4")
    monkeypatch.setenv("AGENT_MEMORY_DB_CONNECT_BACKOFF_SECONDS", "0")
    create_pool = AsyncMock(side_effect=asyncpg.exceptions.InvalidPasswordError("password authentication failed"))
    monkeypatch.setattr("app.store.asyncpg.create_pool", create_pool)
    store = PostgresMemoryStore("postgresql://job_buddy:top-secret@db.example:5433/job_buddy")

    with pytest.raises(RuntimeError, match="db.example:5433") as caught:
        await store.connect()

    assert create_pool.await_count == 1
    assert "top-secret" not in str(caught.value)


async def test_postgres_connect_retries_transient_schema_initialization_failure(monkeypatch):
    monkeypatch.setenv("AGENT_MEMORY_DB_CONNECT_ATTEMPTS", "2")
    monkeypatch.setenv("AGENT_MEMORY_DB_CONNECT_BACKOFF_SECONDS", "0")
    first_pool = AsyncMock()
    second_pool = AsyncMock()
    create_pool = AsyncMock(side_effect=[first_pool, second_pool])
    monkeypatch.setattr("app.store.asyncpg.create_pool", create_pool)
    store = PostgresMemoryStore("postgresql://job_buddy@db.example:5433/job_buddy")
    store.init_schema = AsyncMock(
        side_effect=[
            asyncpg.exceptions.ConnectionDoesNotExistError("connection was closed in the middle of operation"),
            None,
        ]
    )

    await store.connect()

    assert create_pool.await_count == 2
    first_pool.close.assert_awaited_once()
    assert store.pool is second_pool


async def test_postgres_connect_stops_after_transient_retry_limit(monkeypatch):
    monkeypatch.setenv("AGENT_MEMORY_DB_CONNECT_ATTEMPTS", "3")
    monkeypatch.setenv("AGENT_MEMORY_DB_CONNECT_BACKOFF_SECONDS", "0.5")
    create_pool = AsyncMock(side_effect=TimeoutError("timeout with top-secret"))
    sleep = AsyncMock()
    monkeypatch.setattr("app.store.asyncpg.create_pool", create_pool)
    monkeypatch.setattr("app.store.asyncio.sleep", sleep)
    store = PostgresMemoryStore("postgresql://job_buddy:top-secret@db.example:5433/job_buddy")

    with pytest.raises(RuntimeError, match="已尝试 3 次") as caught:
        await store.connect()

    assert create_pool.await_count == 3
    assert [call.args[0] for call in sleep.await_args_list] == [0.5, 1.0]
    assert "top-secret" not in str(caught.value)


@pytest.mark.parametrize(
    ("name", "value"),
    [
        ("AGENT_MEMORY_DB_CONNECT_ATTEMPTS", "0"),
        ("AGENT_MEMORY_DB_CONNECT_TIMEOUT_SECONDS", "nan"),
        ("AGENT_MEMORY_DB_CONNECT_BACKOFF_SECONDS", "-1"),
    ],
)
async def test_postgres_connect_rejects_invalid_retry_config_before_io(monkeypatch, name, value):
    monkeypatch.setenv(name, value)
    create_pool = AsyncMock()
    monkeypatch.setattr("app.store.asyncpg.create_pool", create_pool)
    store = PostgresMemoryStore("postgresql://job_buddy@db.example:5433/job_buddy")

    with pytest.raises(ValueError, match=name):
        await store.connect()

    create_pool.assert_not_awaited()
