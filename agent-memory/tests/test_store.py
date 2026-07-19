from unittest.mock import AsyncMock

import pytest

from app.store import MemoryStore, PostgresMemoryStore


async def test_add_and_search_memory():
    store = MemoryStore()
    item = store.add("session", "Agent Loop needs trace")
    assert item.id.startswith("mem_")
    results = await store.search("trace", "session")
    assert results[0].content == "Agent Loop needs trace"


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
    create_pool = AsyncMock(side_effect=ConnectionError("TLS handshake failed with secret"))
    monkeypatch.setattr("app.store.asyncpg.create_pool", create_pool)
    store = PostgresMemoryStore("postgresql://job_buddy:top-secret@db.example:5433/job_buddy")

    with pytest.raises(RuntimeError, match="db.example:5433") as caught:
        await store.connect()

    assert "top-secret" not in str(caught.value)
    assert create_pool.await_args.kwargs["ssl"] == "disable"
